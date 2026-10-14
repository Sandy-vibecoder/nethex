package app.phantom.spectre

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.telephony.CellSignalStrengthCdma
import android.telephony.CellSignalStrengthGsm
import android.telephony.CellSignalStrengthLte
import android.telephony.CellSignalStrengthNr
import android.telephony.CellSignalStrengthTdscdma
import android.telephony.CellSignalStrengthWcdma
import android.telephony.TelephonyManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.util.ArrayDeque
import kotlin.math.abs
import kotlin.math.min

/**
 * WiFi link, cell signal, gateway latency and the RSSI motion detector.
 * Mirrors wifi_loop / cell_loop / net_loop / motion() in phantom.py.
 */
class Telemetry(private val ctx: Context) {
    private val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val lock = Any()
    private var wifi = JSONObject().put("ok", false).put("err", "starting")
    private var cell = JSONObject().put("ok", false).put("err", "starting")
    private var net = JSONObject()
    private val hist = ArrayDeque<Int>()          // last 90 RSSI samples
    private var scanItems = JSONArray()
    private var scanTs = 0.0
    @Volatile private var running = false

    // current values other components read
    @Volatile var gateway: String? = null
    @Volatile var ip: String? = null
    @Volatile var prefix: Int = 24
    @Volatile var iface: String? = null

    fun start() {
        running = true
        thread("wifi", 800) { pollWifi() }
        thread("cell", 2000) { pollCell() }
        thread("net", 2000) { pollNet() }
    }

    fun stop() { running = false }

    private fun thread(name: String, everyMs: Long, body: () -> Unit) {
        Thread({
            while (running) {
                try { body() } catch (_: Exception) {}
                try { Thread.sleep(everyMs) } catch (_: InterruptedException) { return@Thread }
            }
        }, "spectre-$name").apply { isDaemon = true; start() }
    }

    private fun pollWifi() {
        @Suppress("DEPRECATION")
        val info = wm.connectionInfo
        val rssi = info?.rssi ?: -127
        val rawBssid = info?.bssid
        val bssid = if (rawBssid == null || rawBssid == "02:00:00:00:00:00") "" else rawBssid
        val onWifi = try { cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true } catch (_: Exception) { false }
        val connected = info != null && onWifi && rssi > -127 && rssi < 0
        synchronized(lock) {
            if (connected) {
                var ssid = info!!.ssid ?: ""
                if (ssid.startsWith("\"") && ssid.endsWith("\"") && ssid.length >= 2) ssid = ssid.substring(1, ssid.length - 1)
                if (ssid == "<unknown ssid>" || ssid.isEmpty()) ssid = "(hidden)"
                wifi = JSONObject()
                    .put("ok", true).put("err", JSONObject.NULL)
                    .put("ssid", ssid).put("bssid", bssid.lowercase())
                    .put("rssi", rssi).put("frequency_mhz", info.frequency)
                    .put("link_speed_mbps", info.linkSpeed)
                    .put("ip", Util.ipv4FromInt(info.ipAddress)).put("ts", Util.now())
                hist.addLast(rssi); while (hist.size > 90) hist.removeFirst()
            } else {
                wifi = JSONObject().put("ok", false).put("err", if (onWifi) "wifi link reports no signal yet" else "not connected to WiFi")
                hist.clear()
            }
        }
    }

    private fun pollCell() {
        val ss = try { tm.signalStrength } catch (_: SecurityException) { null } catch (_: Exception) { null }
        var best: JSONObject? = null
        if (ss != null) {
            for (c in ss.cellSignalStrengths) {
                val dbm = c.dbm
                if (dbm <= -140 || dbm >= 0 || dbm == Int.MAX_VALUE) continue
                if (best == null || dbm > best.getInt("dbm")) {
                    val type = when (c) {
                        is CellSignalStrengthNr -> "NR"
                        is CellSignalStrengthLte -> "LTE"
                        is CellSignalStrengthWcdma -> "WCDMA"
                        is CellSignalStrengthTdscdma -> "TDSCDMA"
                        is CellSignalStrengthGsm -> "GSM"
                        is CellSignalStrengthCdma -> "CDMA"
                        else -> "CELL"
                    }
                    best = JSONObject().put("dbm", dbm).put("type", type).put("level", c.level).put("asu", c.asuLevel)
                }
            }
        }
        val op = try { tm.networkOperatorName } catch (_: Exception) { "" }
        synchronized(lock) {
            cell = if (best != null) {
                best.put("ok", true).put("err", JSONObject.NULL).put("ts", Util.now()).put("operator", op ?: "").put("registered", true)
            } else {
                JSONObject().put("ok", false).put("err", "no cell signal reported (no SIM, airplane mode, or permission denied)")
            }
        }
    }

    private fun pollNet() {
        val lp: LinkProperties? = try { cm.getLinkProperties(cm.activeNetwork) } catch (_: Exception) { null }
        var gw: String? = null; var myIp: String? = null; var pfx = 24; var ifn: String? = null
        if (lp != null) {
            ifn = lp.interfaceName
            for (r in lp.routes) {
                val g = r.gateway
                if (r.isDefaultRoute && g is Inet4Address) { gw = g.hostAddress; break }
            }
            for (a in lp.linkAddresses) {
                val ad = a.address
                if (ad is Inet4Address) { myIp = ad.hostAddress; pfx = a.prefixLength; break }
            }
        }
        var lat: Double? = null
        if (gw != null) {
            val out = Util.exec(listOf("/system/bin/ping", "-c", "1", "-W", "1", gw), 4000)
            val m = Regex("time[=<]([\\d.]+)").find(out)
            if (m != null) lat = m.groupValues[1].toDoubleOrNull()?.let { Math.round(it * 10) / 10.0 }
        }
        gateway = gw; ip = myIp; prefix = pfx; iface = ifn
        synchronized(lock) {
            net = JSONObject()
                .put("latency_ms", lat ?: JSONObject.NULL)
                .put("gateway", gw ?: JSONObject.NULL)
                .put("iface", ifn ?: JSONObject.NULL)
                .put("ip", myIp ?: JSONObject.NULL)
                .put("subnet", if (myIp != null) "$myIp/$pfx" else JSONObject.NULL)
        }
    }

    fun wifiSnapshot(): JSONObject = synchronized(lock) { JSONObject(wifi.toString()) }
    fun cellSnapshot(): JSONObject = synchronized(lock) { JSONObject(cell.toString()) }
    fun netSnapshot(): JSONObject = synchronized(lock) { JSONObject(net.toString()) }

    /** Crude presence/motion detector from RSSI alone: deviation from the rolling median plus sample jitter. */
    fun motion(): JSONObject {
        val vals = synchronized(lock) { hist.toList() }
        if (vals.size < 10) return JSONObject().put("ready", false).put("n", vals.size)
        val sorted = vals.sorted()
        val base = if (sorted.size % 2 == 1) sorted[sorted.size / 2].toDouble() else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        val recent = vals.takeLast(8)
        val dev = recent.sumOf { abs(it - base) } / recent.size
        var jitter = 0.0
        for (i in 1 until recent.size) jitter += abs(recent[i] - recent[i - 1])
        jitter /= (recent.size - 1)
        val score = min(1.0, maxOf(dev, jitter) / 5.0)
        val state = if (score < 0.3) "still" else if (score < 0.7) "motion" else "strong"
        val h = JSONArray(); for (v in vals.takeLast(60)) h.put(v)
        return JSONObject().put("ready", true).put("baseline", base).put("dev", Math.round(dev * 100) / 100.0)
            .put("jitter", Math.round(jitter * 100) / 100.0).put("score", Math.round(score * 100) / 100.0)
            .put("state", state).put("history", h)
    }

    /** Access points the radio can hear. Android throttles startScan to 4 per 2 minutes; results stay cached. */
    fun scan(): Pair<JSONArray, Int?> {
        synchronized(lock) {
            if (Util.now() - scanTs < 25 && scanItems.length() > 0) return Pair(scanItems, (Util.now() - scanTs).toInt())
        }
        try { @Suppress("DEPRECATION") wm.startScan() } catch (_: Exception) {}
        try { Thread.sleep(2500) } catch (_: InterruptedException) {}
        val items = JSONArray()
        try {
            for (r in wm.scanResults) {
                val bssid = r.BSSID ?: continue
                @Suppress("DEPRECATION")
                val ssid = if (Build.VERSION.SDK_INT >= 33) (r.wifiSsid?.toString()?.trim('"') ?: r.SSID) else r.SSID
                items.put(JSONObject().put("ssid", if (ssid.isNullOrEmpty()) "(hidden)" else ssid).put("bssid", bssid.lowercase())
                    .put("rssi", r.level).put("frequency_mhz", r.frequency))
            }
        } catch (_: SecurityException) {}
        synchronized(lock) {
            if (items.length() > 0) { scanItems = items; scanTs = Util.now() }
            return Pair(scanItems, if (scanTs > 0) (Util.now() - scanTs).toInt() else null)
        }
    }

    fun scanAge(): Int? = synchronized(lock) { if (scanTs > 0) (Util.now() - scanTs).toInt() else null }
}
