package app.phantom.spectre

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pedestrian dead reckoning that works on phones without a gyroscope.
 *
 * Heading, in order of preference: rotation vector (gyro fused) → game rotation vector → geomagnetic
 * rotation vector → accelerometer + magnetometer computed here. Smoothed with a circular low-pass.
 *
 * Steps: the hardware step detector when it is firing; accelerometer peak detection otherwise. Many
 * phones (Samsung especially) only start reporting hardware steps after 8–10 steps, so the accelerometer
 * path makes the first steps show up immediately.
 *
 * Frame matches phantom.py: x right, y down, heading 0 = map up, clockwise.
 */
class Tracker(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val lock = Any()
    private val thread = HandlerThread("spectre-sensors").apply { start() }
    private val handler = Handler(thread.looper)

    private var x = 0.0; private var y = 0.0
    private var yaw: Double? = null          // smoothed device azimuth, compass clockwise
    private var offset = 0.0                 // map heading = yaw + offset
    private var steplen = 0.7
    private var steps = 0; private var seq = 0
    private var lastStepTs = 0.0
    private var src = "none"
    private var rotSrc = "none"
    private val trail = ArrayDeque<DoubleArray>()
    var err: String? = null

    private val rotMatrix = FloatArray(9)
    private val orientation = FloatArray(3)
    private val gravity = FloatArray(3)
    private val geomag = FloatArray(3)
    private var haveGravity = false; private var haveGeomag = false
    private var useManualCompass = false

    private val accelBuf = ArrayDeque<Double>()
    private var lastAccelPeak = 0.0
    private var lastHwStep = 0.0
    private var hwStepSeen = false

    fun start() {
        val rot = sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val game = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        val geo = sm.getDefaultSensor(Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR)
        val mag = sm.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
        val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val step = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        var any = false
        when {
            rot != null -> { sm.registerListener(this, rot, SensorManager.SENSOR_DELAY_GAME, handler); rotSrc = "rotation"; any = true }
            game != null -> { sm.registerListener(this, game, SensorManager.SENSOR_DELAY_GAME, handler); rotSrc = "game"; any = true }
            geo != null -> { sm.registerListener(this, geo, SensorManager.SENSOR_DELAY_GAME, handler); rotSrc = "geomagnetic"; any = true }
            mag != null && accel != null -> { sm.registerListener(this, mag, SensorManager.SENSOR_DELAY_GAME, handler); useManualCompass = true; rotSrc = "compass"; any = true }
            else -> rotSrc = "none"
        }
        if (step != null) { sm.registerListener(this, step, SensorManager.SENSOR_DELAY_FASTEST, handler); any = true }
        if (accel != null) { sm.registerListener(this, accel, SensorManager.SENSOR_DELAY_GAME, handler); any = true }
        synchronized(lock) {
            src = if (step != null) "detector" else if (accel != null) "accel" else "none"
            err = if (any) null else "no motion sensors on this phone"
        }
    }

    fun stop() { sm.unregisterListener(this); thread.quitSafely() }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR -> {
                val v = if (e.values.size >= 4) e.values.copyOf(4) else e.values.copyOf(3)
                try {
                    SensorManager.getRotationMatrixFromVector(rotMatrix, v)
                    SensorManager.getOrientation(rotMatrix, orientation)
                    pushYaw((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0)
                } catch (_: Exception) {}
            }
            Sensor.TYPE_MAGNETIC_FIELD -> {
                System.arraycopy(e.values, 0, geomag, 0, 3); haveGeomag = true
                computeCompass()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // low-pass for gravity (compass), raw magnitude for steps
                for (i in 0..2) gravity[i] = gravity[i] * 0.85f + e.values[i] * 0.15f
                haveGravity = true
                if (useManualCompass) computeCompass()
                accelStep(sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble()))
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                val now = Util.now()
                synchronized(lock) { lastHwStep = now; hwStepSeen = true; src = "detector" }
                advance(1)
            }
        }
    }

    private fun computeCompass() {
        if (!useManualCompass || !haveGravity || !haveGeomag) return
        if (SensorManager.getRotationMatrix(rotMatrix, null, gravity, geomag)) {
            SensorManager.getOrientation(rotMatrix, orientation)
            pushYaw((Math.toDegrees(orientation[0].toDouble()) + 360.0) % 360.0)
        }
    }

    /** Circular low-pass so a compass without a gyro does not jitter the arrow. */
    private fun pushYaw(raw: Double) {
        synchronized(lock) {
            val cur = yaw
            if (cur == null) { yaw = raw; return }
            var d = raw - cur
            while (d > 180) d -= 360
            while (d < -180) d += 360
            val k = if (useManualCompass) 0.12 else 0.35
            yaw = ((cur + d * k) % 360.0 + 360.0) % 360.0
        }
    }

    /** Accelerometer peak detector, used until the hardware step detector proves it is alive. */
    private fun accelStep(mag: Double) {
        val now = Util.now()
        var stepped = false
        synchronized(lock) {
            accelBuf.addLast(mag); while (accelBuf.size > 14) accelBuf.removeFirst()
            if (accelBuf.size < 8) return
            val mean = accelBuf.sum() / accelBuf.size
            val cur = accelBuf.last(); val prev = accelBuf.elementAt(accelBuf.size - 2); val prev2 = accelBuf.elementAt(accelBuf.size - 3)
            val hwAlive = hwStepSeen && now - lastHwStep < 2.5
            if (!hwAlive && prev - mean > 1.4 && prev >= cur && prev >= prev2 && now - lastAccelPeak > 0.33) {
                stepped = true; lastAccelPeak = now; src = "accel"
            }
        }
        if (stepped) advance(1)
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

    fun set(nx: Double, ny: Double) = synchronized(lock) { x = nx; y = ny; seq++; trail.clear(); trail.addLast(doubleArrayOf(nx, ny)) }
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
            .put("sensors", JSONObject().put("rotation", rotSrc != "none").put("rot_src", rotSrc).put("step", src == "detector").put("accel", true))
            .put("src", src).put("err", err ?: JSONObject.NULL)
            .put("moving", Util.now() - lastStepTs < 1.5)
            .put("trail", tr)
    }
}
