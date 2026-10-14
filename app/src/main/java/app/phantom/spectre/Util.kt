package app.phantom.spectre

import java.io.File
import java.util.concurrent.TimeUnit

object Util {
    /** Run a system command (ping, ip) and return stdout; empty string on failure or timeout. */
    fun exec(cmd: List<String>, timeoutMs: Long = 6000): String {
        return try {
            val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val out = StringBuilder()
            val reader = Thread {
                try { p.inputStream.bufferedReader().use { r -> out.append(r.readText()) } } catch (_: Exception) {}
            }
            reader.start()
            if (!p.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                p.destroyForcibly()
            }
            reader.join(1500)
            out.toString()
        } catch (_: Exception) {
            ""
        }
    }

    fun now(): Double = System.currentTimeMillis() / 1000.0

    fun ipv4FromInt(ip: Int): String =
        "${ip and 0xff}.${(ip shr 8) and 0xff}.${(ip shr 16) and 0xff}.${(ip shr 24) and 0xff}"

    fun fileOrNull(f: File): File? = if (f.exists()) f else null
}
