package com.javelinstrike.game

import kotlin.math.sqrt

/**
 * Reads the phone's motion for the running and spinning events. Sensor callbacks and the
 * game loop run on different threads, so every method is synchronized.
 *
 * * **Steps**: pumping your arms like a sprinter shakes the phone back and forth. Each hard
 *   shake (linear acceleration above [STEP_HIGH], after dropping below [STEP_LOW]) is one step.
 *   Phones without a linear accelerometer use the gyroscope's spin the same way.
 * * **Spin**: turning round with the phone adds up its rotation, whichever way it's held.
 */
class MotionTracker {
    private var steps = 0
    private var armed = true
    private var gyroArmed = true
    private var sawAccel = false
    private var lastNanos = -1L
    private var angle = 0f
    private var rate = 0f

    /** Scales how hard you have to shake; above 1 is easier, like the javelin's ARM setting. */
    @Volatile var sensitivity = 1f

    @Synchronized fun gyro(nanos: Long, wx: Float, wy: Float, wz: Float) {
        val dt = if (lastNanos < 0) 0f else ((nanos - lastNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastNanos = nanos
        val w = sqrt(wx * wx + wy * wy + wz * wz)
        angle += w * dt
        rate += (w - rate) * kotlin.math.min(1f, dt * 12f)
        if (!sawAccel) {
            if (gyroArmed && w > GYRO_HIGH / sensitivity) {
                steps++
                gyroArmed = false
            } else if (w < GYRO_LOW / sensitivity) {
                gyroArmed = true
            }
        }
    }

    /** Linear acceleration (gravity removed), m/s². */
    @Synchronized fun accel(ax: Float, ay: Float, az: Float) {
        sawAccel = true
        val a = sqrt(ax * ax + ay * ay + az * az)
        if (armed && a > STEP_HIGH / sensitivity) {
            steps++
            armed = false
        } else if (a < STEP_LOW / sensitivity) {
            armed = true
        }
    }

    /** Steps since the last call. */
    @Synchronized fun takeSteps(): Int = steps.also { steps = 0 }

    /** Total rotation so far, in radians (it only ever grows). */
    val spinAngle: Float @Synchronized get() = angle

    /** How fast the phone is turning right now, rad/s (smoothed). */
    val spinRate: Float @Synchronized get() = rate

    companion object {
        const val STEP_HIGH = 9f
        const val STEP_LOW = 4f
        const val GYRO_HIGH = 6f
        const val GYRO_LOW = 2.5f
    }
}

/**
 * Turns a stream of steps (from arm pumping or alternate taps) into running speed.
 * Speed follows your step rate: about 4.3 steps a second is top speed.
 */
class Stride(private val topSpeed: Float = 10f) {
    private val times = ArrayDeque<Float>()
    var speed = 0f
        private set

    fun step(now: Float) {
        times.addLast(now)
        while (times.size > 8) times.removeFirst()
    }

    fun update(now: Float, dt: Float) {
        while (times.isNotEmpty() && now - times.first() > WINDOW) times.removeFirst()
        val cadence = times.size / WINDOW
        val target = kotlin.math.min(topSpeed, cadence * STRIDE)
        val k = if (target > speed) ACCEL else DECEL
        speed += (target - speed) * kotlin.math.min(1f, dt * k)
    }

    fun reset() {
        times.clear()
        speed = 0f
    }

    companion object {
        /** Steps are counted over this many seconds. */
        const val WINDOW = 1.2f
        /** Metres per step. */
        const val STRIDE = 2.3f
        const val ACCEL = 2.2f
        const val DECEL = 1.2f
    }
}
