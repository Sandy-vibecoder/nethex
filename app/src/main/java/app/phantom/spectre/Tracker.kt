package app.phantom.spectre

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pedestrian dead reckoning: heading from the rotation vector, distance from the step detector
 * (accelerometer peak detection when the phone has no step sensor). Same frame as phantom.py:
 * x right, y down, heading 0 = map up, clockwise.
 */
class Tracker(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lock = Any()

    private var x = 0.0; private var y = 0.0
    private var yaw: Double? = null          // raw device azimuth, degrees, compass clockwise
    private var offset = 0.0                 // map heading = yaw + offset
    private var steplen = 0.7
    private var steps = 0; private var seq = 0
    private var lastStepTs = 0.0
    private var src = "none"
    private val trail = ArrayDeque<DoubleArray>()
    private val sensors = JSONObject().put("rotation", false).put("step", false).put("accel", false)
    var err: String? = null

    private val rotMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val accelBuf = ArrayDeque<Double>()
    private var lastPeak = 0.0
    private var hasStepSensor = false

    fun start() {
        val rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val step = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var any = false
        if (rot != null) { sm.registerListener(this, rot, SensorManager.SENSOR_DELAY_GAME); any = true }
        if (step != null) { sm.registerListener(this, step, SensorManager.SENSOR_DELAY_FASTEST); hasStepSensor = true; any = true }
        else if (accel != null) { sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME); any = true }
        synchronized(lock) {
            sensors.put("rotation", rot != null).put("step", step != null).put("accel", accel != null)
            err = if (any) null else "no motion sensors on this phone"
        }
    }

    fun stop() { sm.unregisterListener(this) }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR -> {
                val v = if (e.values.size >= 4) e.values.copyOf(4) else e.values.copyOf(3)
                try {
                    SensorManager.getRotationMatrixFromVector(rotMatrix, v)
                    SensorManager.getOrientation(rotMatrix, orientation)
                    val az = (Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0
                    synchronized(lock) { yaw = az }
                } catch (_: Exception) {}
            }
            Sensor.TYPE_STEP_DETECTOR -> { synchronized(lock) { src = "detector" }; advance(1) }
            Sensor.TYPE_ACCELEROMETER -> if (!hasStepSensor) {
                val mag = sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble())
                val now = Util.now()
                var stepped = false
                synchronized(lock) {
                    accelBuf.addLast(mag); while (accelBuf.size > 12) accelBuf.removeFirst()
                    if (accelBuf.size >= 6) {
                        val mean = accelBuf.sum() / accelBuf.size
                        val cur = accelBuf.last(); val prev = accelBuf.elementAt(accelBuf.size - 2)
                        if (cur - mean > 1.6 && cur > prev && now - lastPeak > 0.32) { stepped = true; lastPeak = now; src = "accel" }
                    }
                }
                if (stepped) advance(1)
            }
        }
    }

    private fun heading(): Double = synchronized(lock) { ((yaw ?: 0.0) + offset + 360.0) % 360.0 }

    private fun advance(n: Int) {
        val h = Math.toRadians(heading())
        synchronized(lock) {
            val d = steplen * n
            x += d * sin(h); y -= d * cos(h)
            steps += n; seq++; lastStepTs = Util.now()
            trail.addLast(doubleArrayOf(Math.round(x * 100) / 100.0, Math.round(y * 100) / 100.0))
            while (trail.size > 400) trail.removeFirst()
        }
    }

    fun set(nx: Double, ny: Double) = synchronized(lock) {
        x = nx; y = ny; seq++; trail.clear(); trail.addLast(doubleArrayOf(nx, ny))
    }

    fun face(deg: Double) = synchronized(lock) { offset = ((deg - (yaw ?: 0.0)) % 360.0 + 360.0) % 360.0 }

    fun setStepLen(m: Double) = synchronized(lock) { steplen = m.coerceIn(0.3, 1.2) }

    fun reset() = synchronized(lock) { x = 0.0; y = 0.0; steps = 0; seq++; trail.clear() }

    fun snapshot(): JSONObject = synchronized(lock) {
        val tr = JSONArray()
        val list = trail.toList(); val from = maxOf(0, list.size - 120)
        for (i in from until list.size) tr.put(JSONArray().put(list[i][0]).put(list[i][1]))
        JSONObject()
            .put("x", Math.round(x * 100) / 100.0).put("y", Math.round(y * 100) / 100.0)
            .put("heading", Math.round(heading() * 10) / 10.0).put("has_yaw", yaw != null)
            .put("steps", steps).put("steplen", steplen).put("seq", seq)
            .put("sensors", JSONObject(sensors.toString())).put("src", src)
            .put("err", err ?: JSONObject.NULL)
            .put("moving", Util.now() - lastStepTs < 1.5)
            .put("trail", tr)
    }
}
