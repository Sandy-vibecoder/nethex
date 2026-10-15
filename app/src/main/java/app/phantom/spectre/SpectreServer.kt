package app.phantom.spectre

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject

/** Local HTTP API on 127.0.0.1:8765, identical routes to phantom.py, so phantom.html runs unchanged. */
class SpectreServer(
    private val ctx: Context,
    private val tel: Telemetry,
    private val tracker: Tracker,
    private val presence: Presence,
    private val startedAt: Double
) : NanoHTTPD("127.0.0.1", PORT) {

    companion object { const val PORT = 8765 }

    private fun json(obj: Any, code: Response.Status = Response.Status.OK): Response {
        val r = newFixedLengthResponse(code, "application/json", obj.toString())
        r.addHeader("Cache-Control", "no-store"); r.addHeader("Access-Control-Allow-Origin", "*")
        return r
    }

    private fun asset(name: String, mime: String): Response {
        return try {
            val bytes = ctx.assets.open(name).use { it.readBytes() }
            val r = newFixedLengthResponse(Response.Status.OK, mime, bytes.inputStream(), bytes.size.toLong())
            r.addHeader("Cache-Control", "no-store"); r
        } catch (e: Exception) {
            json(JSONObject().put("error", "asset missing: $name"), Response.Status.NOT_FOUND)
        }
    }

    override fun serve(session: IHTTPSession): Response {
        val path = session.uri
        val q = HashMap<String, String>()
        for ((k, v) in session.parameters) if (v.isNotEmpty()) q[k] = v[0]
        return try {
            when (path) {
                "/api/track" -> {
                    val body = tracker.snapshot()
                    body.put("wifi", tel.wifiSnapshot()).put("cell", tel.cellSnapshot()).put("net", tel.netSnapshot())
                        .put("motion", tel.motion()).put("scan_age", tel.scanAge() ?: JSONObject.NULL)
                        .put("presence", presence.brief()).put("ok", true)
                    json(body)
                }
                "/api/scan" -> { val (items, age) = tel.scan(); json(JSONObject().put("ok", items.length() > 0).put("items", items).put("age_s", age ?: JSONObject.NULL)) }
                "/api/devices" -> {
                    val body = presence.snapshot(q["since"]?.toLongOrNull() ?: 0L)
                    val net = tel.netSnapshot(); body.put("gateway", net.opt("gateway") ?: JSONObject.NULL).put("subnet", net.opt("subnet") ?: JSONObject.NULL)
                    json(body)
                }
                "/api/devices/sweep" -> { presence.sweepNow(); json(JSONObject().put("ok", true)) }
                "/api/devices/name" -> json(JSONObject().put("ok", presence.setName(q["key"] ?: "", q["name"] ?: "")))
                "/api/devices/watch" -> json(JSONObject().put("ok", presence.setWatch(q["key"] ?: "", (q["on"] ?: "1") == "1")))
                "/api/devices/forget" -> json(JSONObject().put("ok", presence.forget(q["key"] ?: "")))
                "/api/presence/config" -> json(presence.configure(q))
                "/api/notify" -> { presence.notify((q["title"] ?: "Nethex").take(60), (q["body"] ?: "").take(200), "spectre-" + (q["id"] ?: "app"), true); json(JSONObject().put("ok", true)) }
                "/api/status" -> {
                    val snap = tracker.snapshot()
                    json(JSONObject().put("ok", true).put("uptime_s", (Util.now() - startedAt).toInt()).put("host", "127.0.0.1").put("port", PORT)
                        .put("tools", JSONObject().put("termux_api", true).put("sensor", snap.getJSONObject("sensors").optBoolean("rotation") || snap.getJSONObject("sensors").optBoolean("step"))
                            .put("telephony", tel.cellSnapshot().optBoolean("ok")).put("notify", true).put("ping", true))
                        .put("sensors", snap.getJSONObject("sensors")).put("track_err", snap.opt("err") ?: JSONObject.NULL)
                        .put("operator", JSONObject().put("name", tel.cellSnapshot().optString("operator", "")))
                        .put("net", tel.netSnapshot()).put("app", "android")
                        .put("launch_sound", ctx.getSharedPreferences("nethex", Context.MODE_PRIVATE).getBoolean("launch_sound", true)))
                }
                "/api/track/set" -> { tracker.set(q["x"]?.toDoubleOrNull() ?: 0.0, q["y"]?.toDoubleOrNull() ?: 0.0); json(tracker.snapshot()) }
                "/api/track/face" -> { tracker.face(q["deg"]?.toDoubleOrNull() ?: 0.0); json(tracker.snapshot()) }
                "/api/track/steplen" -> { tracker.setStepLen(q["m"]?.toDoubleOrNull() ?: 0.7); json(tracker.snapshot()) }
                "/api/track/reset" -> { tracker.reset(); json(tracker.snapshot()) }
                "/api/app/prefs" -> {
                    val sp = ctx.getSharedPreferences("nethex", Context.MODE_PRIVATE)
                    q["launch_sound"]?.let { sp.edit().putBoolean("launch_sound", it == "1").apply() }
                    json(JSONObject().put("ok", true).put("launch_sound", sp.getBoolean("launch_sound", true)))
                }
                "/", "/index.html", "/phantom.html" -> asset("phantom.html", "text/html; charset=utf-8")
                "/icon.svg" -> asset("icon.svg", "image/svg+xml")
                "/manifest.webmanifest" -> json(JSONObject().put("name", "Nethex").put("short_name", "Nethex").put("start_url", "/").put("display", "standalone").put("background_color", "#07090e").put("theme_color", "#07090e"))
                "/sw.js" -> newFixedLengthResponse(Response.Status.OK, "application/javascript", "")
                else -> json(JSONObject().put("error", "not found"), Response.Status.NOT_FOUND)
            }
        } catch (e: Exception) {
            json(JSONObject().put("error", e.message ?: "error"), Response.Status.INTERNAL_ERROR)
        }
    }
}
