package com.javelinstrike.game

import kotlin.math.atan2
import kotlin.math.sqrt

/** A throw: [power] from 0 to 1, and the [elevation] (radians) the hand was moving at, if known. */
class Release(val power: Float, val elevation: Float?)

/**
 * Turns gyroscope and accelerometer readings into javelin throws.
 *
 * While the player holds the screen (the "grip"), the phone's rotation is added up.
 * Pulling the arm back rotates the phone one way; once it has turned far enough the
 * javelin is drawn. Whipping the arm forward then spins the phone hard the other way.
 * The javelin leaves the hand when the swing finishes or the thumb lets go.
 *
 * The release is measured from the hand's velocity: during the swing the phone's
 * acceleration is turned into world directions (using the gyroscope to follow how the
 * phone turns, and gravity to know which way is up) and added up into a velocity.
 * Its direction at release is the throwing angle: moving up through the release throws
 * high, moving level throws flat. Its speed, with the arm's spin, sets the power.
 *
 * Only the direction of the draw matters, not how the phone is held, so it works in
 * either hand and in any grip. Sensor callbacks and the game loop run on different
 * threads, so every method is synchronized.
 */
class ThrowDetector {
    enum class Phase { IDLE, GRIP, DRAWN, SWING }

    var phase = Phase.IDLE
        @Synchronized get
        private set

    /** Turns off detection (and drops any grip) while the game can't take a throw. */
    var enabled = false
        @Synchronized get
        @Synchronized set(v) {
            if (!v && field) reset()
            field = v
        }

    /** Scales how hard you need to swing; above 1 makes full power easier to reach. */
    @Volatile var sensitivity = 1f

    /** Fastest forward spin of the last throw (rad/s), handy for tuning. */
    @Volatile var lastPeakSpin = 0f
        private set

    private var rx = 0f
    private var ry = 0f
    private var rz = 0f
    private var dx = 0f
    private var dy = 0f
    private var dz = 0f
    private var maxDraw = 0f
    private var peakSpin = 0f
    private var swingTime = 0f
    private var lastGyroNanos = -1L

    // Which way is up, in the phone's axes when the grip started.
    private var ux = 0f
    private var uy = 1f
    private var uz = 0f
    private var hasUp = false
    private var gravX = 0f
    private var gravY = 0f
    private var gravZ = 0f
    // How the phone has turned since the grip started (a quaternion).
    private var qw = 1f
    private var qx = 0f
    private var qy = 0f
    private var qz = 0f
    // The hand's velocity during the swing, in the grip-start axes (m/s).
    private var vx = 0f
    private var vy = 0f
    private var vz = 0f
    private var lastAccelNanos = -1L
    private var sawAccel = false

    private var pending: Release? = null
    private var pendingCancel = false
    private var pendingDrawn = false

    /** How far back the arm is, 0 to 1, for the on-screen meter. */
    val drawAmount: Float
        @Synchronized get() = when (phase) {
            Phase.IDLE -> 0f
            Phase.SWING -> 1f
            else -> (maxDraw / DRAW_FULL).coerceIn(0f, 1f)
        }

    /** The power the javelin would have if it left the hand right now (0 during the draw). */
    val livePower: Float
        @Synchronized get() = if (phase == Phase.SWING) currentPower() else 0f

    /** The angle the hand is moving at right now, during the swing; null when unknown. */
    val liveAngle: Float?
        @Synchronized get() = if (phase == Phase.SWING) currentAngle() else null

    /** The thumb pressed ([down] = true) or let go of the screen. */
    @Synchronized fun grip(down: Boolean) {
        if (!enabled) return
        if (down) {
            if (phase == Phase.IDLE) {
                reset()
                phase = Phase.GRIP
                startTracking()
            }
            return
        }
        when (phase) {
            Phase.SWING -> release()
            // Letting go with the arm pulled back is an early release; before that it's nothing.
            Phase.DRAWN -> {
                pendingCancel = true
                reset()
            }
            Phase.GRIP -> reset()
            Phase.IDLE -> {}
        }
    }

    /** Gravity as the phone measures it (pointing up), in the phone's axes. */
    @Synchronized fun gravity(gx: Float, gy: Float, gz: Float) {
        gravX = gx
        gravY = gy
        gravZ = gz
    }

    /** Angular velocity in rad/s, in the phone's own axes, with the sensor's timestamp. */
    @Synchronized fun gyro(nanos: Long, wx: Float, wy: Float, wz: Float) {
        val dt = if (lastGyroNanos < 0) 0f else ((nanos - lastGyroNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastGyroNanos = nanos
        if (phase != Phase.IDLE) turn(wx * dt, wy * dt, wz * dt)
        when (phase) {
            Phase.IDLE -> {}
            Phase.GRIP, Phase.DRAWN -> {
                rx += wx * dt
                ry += wy * dt
                rz += wz * dt
                val mag = sqrt(rx * rx + ry * ry + rz * rz)
                if (mag > maxDraw) {
                    maxDraw = mag
                    dx = rx / mag
                    dy = ry / mag
                    dz = rz / mag
                }
                if (phase == Phase.GRIP && maxDraw >= DRAW_MIN) {
                    phase = Phase.DRAWN
                    pendingDrawn = true
                }
                if (phase == Phase.DRAWN && forwardSpin(wx, wy, wz) > SWING_START) {
                    phase = Phase.SWING
                    peakSpin = 0f
                    swingTime = 0f
                    // The hand starts its forward swing from (nearly) still.
                    vx = 0f; vy = 0f; vz = 0f
                }
            }
            Phase.SWING -> {
                val f = forwardSpin(wx, wy, wz)
                swingTime += dt
                if (f > peakSpin) peakSpin = f
                if ((swingTime > 0.04f && f < peakSpin * 0.35f) || swingTime > 0.6f) release()
            }
        }
    }

    /** Linear acceleration (gravity removed) in m/s², in the phone's axes. Adds to power and sets the angle. */
    @Synchronized fun push(nanos: Long, ax: Float, ay: Float, az: Float) {
        val dt = if (lastAccelNanos < 0) 0f else ((nanos - lastAccelNanos) / 1e9f).coerceIn(0f, 0.05f)
        lastAccelNanos = nanos
        if (phase != Phase.SWING) return
        sawAccel = true
        // Turn the reading into grip-start axes and add it to the velocity.
        val (wx, wy, wz) = rotate(ax, ay, az)
        vx += wx * dt
        vy += wy * dt
        vz += wz * dt
    }

    /** The throw that just happened, or null. Each throw is returned once. */
    @Synchronized fun takeThrow(): Release? = pending.also { pending = null }

    /** True once if the thumb let go before the throw. */
    @Synchronized fun takeCancel(): Boolean = pendingCancel.also { pendingCancel = false }

    /** True once when the arm has been pulled back far enough to throw. */
    @Synchronized fun takeDrawn(): Boolean = pendingDrawn.also { pendingDrawn = false }

    /** How fast the phone is spinning against the draw direction, i.e. forward. */
    private fun forwardSpin(wx: Float, wy: Float, wz: Float) = -(wx * dx + wy * dy + wz * dz)

    private fun startTracking() {
        qw = 1f; qx = 0f; qy = 0f; qz = 0f
        vx = 0f; vy = 0f; vz = 0f
        val g = sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ)
        hasUp = g > 5f
        if (hasUp) {
            ux = gravX / g; uy = gravY / g; uz = gravZ / g
        }
    }

    /** Turns the tracked orientation by a small rotation (radians about each phone axis). */
    private fun turn(ax: Float, ay: Float, az: Float) {
        val hx = ax * 0.5f
        val hy = ay * 0.5f
        val hz = az * 0.5f
        // q = q * (1, h)
        val w = qw - qx * hx - qy * hy - qz * hz
        val x = qx + qw * hx + qy * hz - qz * hy
        val y = qy + qw * hy + qz * hx - qx * hz
        val z = qz + qw * hz + qx * hy - qy * hx
        val n = sqrt(w * w + x * x + y * y + z * z)
        qw = w / n; qx = x / n; qy = y / n; qz = z / n
    }

    /** A vector in the phone's current axes, in the axes it had when the grip started. */
    private fun rotate(x: Float, y: Float, z: Float): Triple<Float, Float, Float> {
        // v' = q v q*
        val tx = 2f * (qy * z - qz * y)
        val ty = 2f * (qz * x - qx * z)
        val tz = 2f * (qx * y - qy * x)
        return Triple(
            x + qw * tx + (qy * tz - qz * ty),
            y + qw * ty + (qz * tx - qx * tz),
            z + qw * tz + (qx * ty - qy * tx),
        )
    }

    private fun handSpeed() = sqrt(vx * vx + vy * vy + vz * vz)

    /** The angle above level the hand is moving at, or null if it can't be measured. */
    private fun currentAngle(): Float? {
        if (!sawAccel || !hasUp || handSpeed() < MIN_HAND_SPEED) return null
        val up = vx * ux + vy * uy + vz * uz
        val level = sqrt(maxOf(0f, handSpeed() * handSpeed() - up * up))
        return atan2(up, level)
    }

    private fun currentPower() = powerFor(peakSpin, if (sawAccel) handSpeed() else -1f, sensitivity)

    private fun release() {
        lastPeakSpin = peakSpin
        pending = Release(currentPower(), currentAngle())
        reset()
    }

    private fun reset() {
        phase = Phase.IDLE
        rx = 0f; ry = 0f; rz = 0f
        dx = 0f; dy = 0f; dz = 0f
        maxDraw = 0f
        peakSpin = 0f
        sawAccel = false
        vx = 0f; vy = 0f; vz = 0f
    }

    companion object {
        /** The arm must turn back this far (radians, about 26°) before a throw counts. */
        const val DRAW_MIN = 0.45f
        /** The draw meter is full at this angle (about 70°). */
        const val DRAW_FULL = 1.2f
        /** A forward spin faster than this (rad/s) starts the throw. */
        const val SWING_START = 3f
        /** Forward spin (rad/s) for full power at normal sensitivity. */
        const val SPIN_FULL = 24f
        /** Hand speed at release (m/s) for full power at normal sensitivity. */
        const val SPEED_FULL = 9f
        /** Below this hand speed (m/s) the direction is too uncertain to set the angle. */
        const val MIN_HAND_SPEED = 1f

        /** Power from the arm's peak spin and the hand's speed at release ([handSpeed] < 0 if unknown). */
        fun powerFor(peakSpin: Float, handSpeed: Float, sensitivity: Float): Float {
            val spin = ((peakSpin - SWING_START) / (SPIN_FULL / sensitivity - SWING_START)).coerceIn(0f, 1f)
            if (handSpeed < 0f) return spin
            val speed = (handSpeed / (SPEED_FULL / sensitivity)).coerceIn(0f, 1f)
            return spin * 0.6f + speed * 0.4f
        }
    }
}

/**
 * Touch-screen throwing for phones without a gyroscope (or players who prefer it):
 * press, drag down to pull back, then flick up. The flick speed sets the power and its
 * direction sets the aim.
 */
class FlickThrow {
    private val xs = FloatArray(16)
    private val ys = FloatArray(16)
    private val ts = FloatArray(16)
    private var n = 0
    private var startX = 0f
    private var startY = 0f
    private var maxPull = 0f

    var active = false
        private set

    /** 0 to 1: how far the finger has been pulled back. */
    val drawAmount get() = if (active) (maxPull / PULL_FULL).coerceIn(0f, 1f) else 0f

    /** Aim (radians) while dragging, from how far sideways the finger has moved. */
    var previewYaw = 0f
        private set

    fun down(x: Float, y: Float, t: Float) {
        active = true
        startX = x
        startY = y
        maxPull = 0f
        n = 0
        previewYaw = 0f
        add(x, y, t)
    }

    fun move(x: Float, y: Float, t: Float) {
        if (!active) return
        add(x, y, t)
        if (y - startY > maxPull) maxPull = y - startY
        previewYaw = ((x - startX) / 160f).coerceIn(-1f, 1f) * MAX_YAW
    }

    /** Returns (power, yaw) for a good flick, or null if it wasn't a throw. */
    fun up(x: Float, y: Float, t: Float, sensitivity: Float): Pair<Float, Float>? {
        if (!active) return null
        add(x, y, t)
        active = false
        if (maxPull < PULL_MIN) return null
        // Velocity over the last tenth of a second of the gesture.
        val last = (n - 1) % xs.size
        var first = last
        for (k in 1 until minOf(n, xs.size)) {
            val i = ((last - k) % xs.size + xs.size) % xs.size
            first = i
            if (ts[last] - ts[i] >= 0.1f) break
        }
        val dt = ts[last] - ts[first]
        if (dt <= 0.001f) return null
        val vx = (xs[last] - xs[first]) / dt
        val vy = (ys[first] - ys[last]) / dt
        if (vy < FLICK_MIN) return null
        val power = ((vy - FLICK_MIN) / (FLICK_FULL / sensitivity - FLICK_MIN)).coerceIn(0f, 1f)
        val yaw = (kotlin.math.atan2(vx, vy) * 1.2f).coerceIn(-MAX_YAW, MAX_YAW)
        return power to yaw
    }

    fun cancel() { active = false }

    private fun add(x: Float, y: Float, t: Float) {
        val i = n % xs.size
        xs[i] = x; ys[i] = y; ts[i] = t
        n++
    }

    companion object {
        const val PULL_MIN = 18f
        const val PULL_FULL = 60f
        /** Upward flick speeds in virtual pixels per second. */
        const val FLICK_MIN = 150f
        const val FLICK_FULL = 1500f
        const val MAX_YAW = 0.45f
    }
}
