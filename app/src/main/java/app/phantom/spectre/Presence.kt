package app.phantom.spectre

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.InetAddress
import java.util.ArrayDeque
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Live devices on the LAN. Every sweep pings all 254 hosts of the phone's /24 (any reply or ARP answer
 * marks a device present), then reads the kernel neighbour table. A device is "gone" after `grace`
 * missed sweeps. Join/leave events raise Android notifications. Names, watch flags and config persist
 * in spectre_devices.json so the user can change them from the app at any time.
 */
class Presence(private val ctx: Context, private val tel: Telemetry) {
    class Dev(val key: String) {
        var ip = ""; var mac = ""; var host = ""; var name = ""; var watch = false
        var firstSeen = 0.0; var lastSeen = 0.0; var online = false; var missed = 99
        var latMs: Double? = null; var joins = 0; var hostTry = 0.0; var me = false; var gateway = false
        fun label() = name.ifEmpty { host.ifEmpty { ip.ifEmpty { mac.ifEmpty { "device" } } } }
        fun json(): JSONObject = JSONObject()
            .put("key", key).put("ip", ip).put("mac", mac).put("host", host).put("name", name).put("watch", watch)
            .put("first_seen", firstSeen).put("last_seen", lastSeen).put("online", online).put("missed", missed)
            .put("lat_ms", latMs ?: JSONObject.NULL).put("joins", joins).put("me", me).put("gateway", gateway)
            .put("age_s", if (lastSeen > 0) (Util.now() - lastSeen).toInt() else JSONObject.NULL)
    }

    val cfg = JSONObject().put("sweep_every", 15).put("grace", 3).put("notify", true).put("scope", "all").put("join", true).put("vibrate", true)
    private val devices = LinkedHashMap<String, Dev>()
    private val events = ArrayDeque<JSONObject>()
    private var seq = 0L
    private var sweepTs = 0.0
    @Volatile private var sweeping = false
    private var booted = false
    var err: String? = null
    private val lock = Any()
    private val file = File(ctx.filesDir, "spectre_devices.json")
    @Volatile private var running = false
    private var notifyId = 1000

    fun start() {
        load()
        running = true
        Thread({
            while (running) {
                try { sweep() } catch (e: Exception) { synchronized(lock) { err = e.message } }
                val every = synchronized(lock) { cfg.optInt("sweep_every", 15) }.coerceIn(5, 300)
                try { Thread.sleep(every * 1000L) } catch (_: InterruptedException) { return@Thread }
            }
        }, "spectre-presence").apply { isDaemon = true; start() }
    }

    fun stop() { running = false }

    fun sweepNow() { Thread({ try { sweep() } catch (_: Exception) {} }, "spectre-sweep").start() }

    // ---------- persistence ----------
    private fun load() {
        try {
            if (!file.exists()) return
            val j = JSONObject(file.readText())
            val c = j.optJSONObject("cfg")
            if (c != null) for (k in c.keys()) if (cfg.has(k)) cfg.put(k, c.get(k))
            val ds = j.optJSONObject("devices") ?: return
            synchronized(lock) {
                for (k in ds.keys()) {
                    val d = ds.getJSONObject(k); val dev = Dev(k)
                    dev.ip = d.optString("ip"); dev.mac = d.optString("mac"); dev.host = d.optString("host"); dev.name = d.optString("name")
                    dev.watch = d.optBoolean("watch"); dev.firstSeen = d.optDouble("first_seen", 0.0); dev.lastSeen = d.optDouble("last_seen", 0.0)
                    dev.joins = d.optInt("joins"); devices[k] = dev
                }
            }
        } catch (_: Exception) {}
    }

    fun save() {
        try {
            val out = JSONObject()
            synchronized(lock) {
                out.put("cfg", JSONObject(cfg.toString()))
                val ds = JSONObject()
                for ((k, d) in devices) ds.put(k, JSONObject().put("ip", d.ip).put("mac", d.mac).put("host", d.host).put("name", d.name)
                    .put("watch", d.watch).put("first_seen", d.firstSeen).put("last_seen", d.lastSeen).put("joins", d.joins))
                out.put("devices", ds)
            }
            val tmp = File(file.path + ".tmp"); tmp.writeText(out.toString()); tmp.renameTo(file)
        } catch (_: Exception) {}
    }

    // ---------- notifications ----------
    fun notify(title: String, body: String, id: String, vibrate: Boolean) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val n = android.app.Notification.Builder(ctx, SpectreService.EVENT_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat).setContentTitle(title).setContentText(body)
                .setStyle(android.app.Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true).setCategory(android.app.Notification.CATEGORY_STATUS)
                .setContentIntent(SpectreService.launchIntent(ctx))
                .build()
            nm.notify(id.hashCode() and 0x7fffffff, n)
            if (vibrate) buzz()
        } catch (_: Exception) {}
    }

    private fun buzz() {
        try {
            val v: Vibrator = if (Build.VERSION.SDK_INT >= 31) (ctx.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
            else @Suppress("DEPRECATION") (ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator)
            v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1))
        } catch (_: Exception) {}
    }

    private fun pushEvent(kind: String, d: Dev) {
        val ev: JSONObject; val doNotify: Boolean; val vib: Boolean
        synchronized(lock) {
            seq++
            ev = JSONObject().put("seq", seq).put("t", Util.now()).put("type", kind).put("key", d.key).put("label", d.label())
                .put("ip", d.ip).put("mac", d.mac).put("watch", d.watch)
            events.addLast(ev); while (events.size > 300) events.removeFirst()
            doNotify = booted && cfg.optBoolean("notify", true) && (cfg.optString("scope", "all") == "all" || d.watch) && (kind == "leave" || cfg.optBoolean("join", true))
            vib = cfg.optBoolean("vibrate", true)
        }
        if (doNotify) notify("Nethex", "${d.label()} ${if (kind == "leave") "left the network" else "joined the network"}  (${d.ip})", "spectre-" + d.key, vib)
    }

    // ---------- the sweep ----------
    private fun sweep() {
        synchronized(lock) { if (sweeping) return; sweeping = true }
        try {
            val me = tel.ip; val gw = tel.gateway; val iface = tel.iface
            if (me == null) { synchronized(lock) { err = "not on a WiFi network" }; return }
            val base = me.substringBeforeLast('.')
            val answered = HashMap<String, Double>()
            val pool = Executors.newFixedThreadPool(48)
            val futures = (1..254).map { i ->
                val ip = "$base.$i"
                pool.submit(Callable<Pair<String, Double?>> {
                    val out = Util.exec(listOf("/system/bin/ping", "-c", "1", "-W", "1", ip), 4000)
                    val m = Regex("time[=<]([\\d.]+)").find(out)
                    Pair(ip, m?.groupValues?.get(1)?.toDoubleOrNull())
                })
            }
            for (f in futures) { try { val (ip, lat) = f.get(8, TimeUnit.SECONDS); if (lat != null) answered[ip] = Math.round(lat * 10) / 10.0 } catch (_: Exception) {} }
            pool.shutdownNow()
            try { Thread.sleep(3000) } catch (_: InterruptedException) {}

            val present = HashMap<String, String>(); val unknown = HashSet<String>()
            val neigh = Util.exec(listOf("/system/bin/ip", "neigh", "show"), 6000)
            val re = Regex("(\\d+\\.\\d+\\.\\d+\\.\\d+) dev (\\S+)(?: lladdr (\\S+))? (\\S+)")
            for (line in neigh.lines()) {
                val m = re.find(line) ?: continue
                val ip = m.groupValues[1]; val mac = m.groupValues[3]; val st = m.groupValues[4]
                if (!ip.startsWith("$base.")) continue
                if (st == "REACHABLE" && mac.isNotEmpty()) present[ip] = mac.lowercase()
                else if (st in setOf("STALE", "DELAY", "PROBE") && mac.isNotEmpty()) { unknown.add(ip); if (answered.containsKey(ip)) present[ip] = mac.lowercase() }
            }
            for (ip in answered.keys) if (!present.containsKey(ip)) present[ip] = ""
            present[me] = selfMac(iface)

            val now = Util.now()
            val fired = ArrayList<Pair<String, Dev>>()
            synchronized(lock) {
                val grace = cfg.optInt("grace", 3)
                val seen = HashSet<String>()
                for ((ip, mac) in present) {
                    var key = if (mac.isNotEmpty()) mac else "ip:$ip"
                    var d = devices[key]
                    if (d == null && mac.isEmpty()) { d = devices.values.firstOrNull { it.ip == ip && it.mac.isNotEmpty() }; if (d != null) key = d.key }
                    if (d == null) { d = Dev(key); d.firstSeen = now; devices[key] = d }
                    val was = d.online
                    d.ip = ip; if (mac.isNotEmpty()) d.mac = mac; d.lastSeen = now; d.online = true; d.missed = 0; d.latMs = answered[ip]
                    d.me = ip == me; d.gateway = ip == gw
                    if (!was) { d.joins++; fired.add(Pair("join", d)) }
                    seen.add(key)
                }
                for ((key, d) in devices) {
                    if (key in seen || d.ip in unknown) continue
                    if (d.online) { d.missed++; if (d.missed >= grace) { d.online = false; fired.add(Pair("leave", d)) } }
                    else d.missed = minOf(d.missed + 1, 99)
                }
                sweepTs = now; err = null
            }
            for ((k, d) in fired) pushEvent(k, d)
            synchronized(lock) { booted = true }
            // hostnames via the router's DNS, one try per 10 min per device
            val toResolve = synchronized(lock) { devices.values.filter { it.online && it.host.isEmpty() && now - it.hostTry > 600 }.map { it.hostTry = now; it.key to it.ip } }
            for ((key, ip) in toResolve) {
                val h = try { InetAddress.getByName(ip).hostName } catch (_: Exception) { null }
                if (h != null && h != ip) synchronized(lock) { devices[key]?.host = h.substringBefore('.') }
            }
            if (fired.isNotEmpty()) save()
        } finally {
            synchronized(lock) { sweeping = false }
        }
    }

    private fun selfMac(iface: String?): String {
        if (iface == null) return ""
        val out = Util.exec(listOf("/system/bin/ip", "link", "show", iface), 4000)
        return Regex("link/ether ([0-9a-f:]{17})").find(out)?.groupValues?.get(1) ?: ""
    }

    // ---------- API ----------
    fun snapshot(since: Long): JSONObject = synchronized(lock) {
        val items = devices.values.sortedWith(compareBy<Dev> { !it.online }.thenBy { ipKey(it.ip) }).map { it.json() }
        val arr = JSONArray(); for (i in items) arr.put(i)
        val evs = JSONArray(); for (e in events) if (e.getLong("seq") > since) evs.put(e)
        JSONObject().put("ok", true).put("items", arr).put("events", evs).put("seq", seq)
            .put("online", devices.values.count { it.online }).put("total", devices.size)
            .put("sweep_age_s", if (sweepTs > 0) (Util.now() - sweepTs).toInt() else JSONObject.NULL)
            .put("sweeping", sweeping).put("err", err ?: JSONObject.NULL).put("cfg", JSONObject(cfg.toString())).put("notify_ok", true)
    }

    fun brief(): JSONObject = synchronized(lock) {
        JSONObject().put("online", devices.values.count { it.online }).put("total", devices.size).put("seq", seq)
            .put("sweep_age_s", if (sweepTs > 0) (Util.now() - sweepTs).toInt() else JSONObject.NULL)
    }

    private fun ipKey(ip: String): Long = ip.split('.').fold(0L) { acc, p -> acc * 256 + (p.toLongOrNull() ?: 0) }

    fun setName(key: String, name: String): Boolean { val ok = synchronized(lock) { devices[key]?.let { it.name = name.take(40); true } ?: false }; save(); return ok }
    fun setWatch(key: String, on: Boolean): Boolean { val ok = synchronized(lock) { devices[key]?.let { it.watch = on; true } ?: false }; save(); return ok }
    fun forget(key: String): Boolean { val ok = synchronized(lock) { devices.remove(key) != null }; save(); return ok }

    fun configure(q: Map<String, String>): JSONObject {
        synchronized(lock) {
            q["sweep_every"]?.toIntOrNull()?.let { cfg.put("sweep_every", it.coerceIn(5, 300)) }
            q["grace"]?.toIntOrNull()?.let { cfg.put("grace", it.coerceIn(1, 20)) }
            q["notify"]?.let { cfg.put("notify", it == "1") }
            q["scope"]?.let { cfg.put("scope", if (it == "watched") "watched" else "all") }
            q["join"]?.let { cfg.put("join", it == "1") }
            q["vibrate"]?.let { cfg.put("vibrate", it == "1") }
        }
        save()
        return synchronized(lock) { JSONObject(cfg.toString()) }
    }
}
