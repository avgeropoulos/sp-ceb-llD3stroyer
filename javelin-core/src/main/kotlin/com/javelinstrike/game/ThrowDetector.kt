package com.javelinstrike.game

import kotlin.math.asin
import kotlin.math.sqrt

/** A throw: [power] from 0 to 1, and the [elevation] (radians) it leaves at, if the phone could tell. */
class Release(val power: Float, val elevation: Float?)

/**
 * Turns gyroscope and accelerometer readings into javelin throws.
 *
 * While the player holds the screen (the "grip"), the phone's rotation is added up.
 * Pulling the arm back rotates the phone one way; once it has turned far enough the
 * javelin is drawn. Whipping the arm forward then spins the phone hard the other way.
 * The javelin leaves the hand when the swing finishes or the thumb lets go.
 *
 * The gyroscope follows how the phone turns from the moment of the grip, and gravity says
 * which way was up then, so the phone's lean is known all through the throw. How far it
 * leans back when the javelin leaves the hand sets the angle: lean back more, or let go
 * earlier while it's still tilted back, and it flies high. How fast the hand is moving
 * (the phone's acceleration added up into a velocity) and how hard the arm whips round
 * set the power. A slow forward move that isn't a throw doesn't count.
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
    private var peakSpeed = 0f
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

    /** How far back the arm is right now, 0 to 1, for the on-screen meter. It drops as you come forward. */
    val drawAmount: Float
        @Synchronized get() = when (phase) {
            Phase.IDLE, Phase.SWING -> 0f
            else -> (currentDraw() / DRAW_FULL).coerceIn(0f, 1f)
        }

    /** The power the javelin would have if it left the hand right now (0 until the swing). */
    val livePower: Float
        @Synchronized get() = if (phase == Phase.SWING) currentPower() else 0f

    /** The angle the javelin would leave at if it were let go right now; null when unknown. */
    val liveAngle: Float?
        @Synchronized get() = if (phase == Phase.IDLE) null else currentAngle()

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
        if (phase != Phase.IDLE) {
            turn(wx * dt, wy * dt, wz * dt)
            rx += wx * dt
            ry += wy * dt
            rz += wz * dt
        }
        when (phase) {
            Phase.IDLE -> {}
            Phase.GRIP, Phase.DRAWN -> {
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
                // Brought back forward without throwing: no longer drawn.
                if (phase == Phase.DRAWN && currentDraw() < DRAW_MIN * 0.4f && forwardSpin(wx, wy, wz) < SWING_START) {
                    disarm()
                }
                if (phase == Phase.DRAWN && forwardSpin(wx, wy, wz) > SWING_START) {
                    phase = Phase.SWING
                    peakSpin = 0f
                    peakSpeed = 0f
                    swingTime = 0f
                    // The hand starts its forward swing from (nearly) still.
                    vx = 0f; vy = 0f; vz = 0f
                }
            }
            Phase.SWING -> {
                val f = forwardSpin(wx, wy, wz)
                swingTime += dt
                if (f > peakSpin) peakSpin = f
                if ((swingTime > 0.04f && f < peakSpin * 0.35f) || swingTime > 0.6f) {
                    // Too gentle to be a throw: just moving the phone forward.
                    if (peakSpin < SWING_MIN / kotlin.math.sqrt(sensitivity)) disarm() else release()
                }
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
        val s = handSpeed()
        if (s > peakSpeed) peakSpeed = s
    }

    /** The throw that just happened, or null. Each throw is returned once. */
    @Synchronized fun takeThrow(): Release? = pending.also { pending = null }

    /** True once if the thumb let go before the throw. */
    @Synchronized fun takeCancel(): Boolean = pendingCancel.also { pendingCancel = false }

    /** True once when the arm has been pulled back far enough to throw. */
    @Synchronized fun takeDrawn(): Boolean = pendingDrawn.also { pendingDrawn = false }

    /** How fast the phone is spinning against the draw direction, i.e. forward. */
    private fun forwardSpin(wx: Float, wy: Float, wz: Float) = -(wx * dx + wy * dy + wz * dz)

    /** How far back the arm is now: the turn since the grip, along the draw direction. */
    private fun currentDraw() = if (maxDraw <= 0f) 0f else maxOf(0f, rx * dx + ry * dy + rz * dz)

    /** Back to just gripping, so the arm has to be pulled back again. */
    private fun disarm() {
        phase = Phase.GRIP
        maxDraw = currentDraw()
        peakSpin = 0f
        peakSpeed = 0f
        sawAccel = false
        vx = 0f; vy = 0f; vz = 0f
    }

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

    /**
     * The throwing angle for the phone's lean right now: how far its screen faces up (the top
     * leaning back) compared with when it was gripped. Null if gravity wasn't known.
     */
    private fun currentAngle(): Float? {
        if (!hasUp) return null
        val (nx, ny, nz) = rotate(0f, 0f, 1f)
        val lean = asin((nx * ux + ny * uy + nz * uz).coerceIn(-1f, 1f))
        return elevationForLean(lean)
    }

    private fun currentPower() = powerFor(peakSpin, if (sawAccel) peakSpeed else -1f, sensitivity)

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
        peakSpeed = 0f
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
        /** A swing whose spin never gets this fast (rad/s, normal ARM) isn't a throw. */
        const val SWING_MIN = 6f

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
 * Touch-screen throwing, like a slingshot, for phones without a gyroscope (or playing sitting
 * down): press anywhere and pull your finger down. The further you pull, the harder the throw,
 * over most of the screen's height, so there's plenty of room to be precise. Pull sideways to
 * aim, and let go to throw. Push back up to where you started to cancel.
 */
class FlickThrow {
    private var startX = 0f
    private var startY = 0f
    private var pull = 0f
    private var side = 0f

    /** How far down (virtual pixels) a full-power pull is; the game sets it from the screen size. */
    var fullPull = 300f

    /** Scales the pull: above 1 reaches full power with a shorter pull (the ARM setting). */
    var sensitivity = 1f

    var active = false
        private set

    /** 0 to 1: the power you'd throw with if you let go now. */
    val power: Float
        get() = if (!active || pull < PULL_MIN) 0f
        else ((pull - PULL_MIN) / (fullPull / sensitivity - PULL_MIN)).coerceIn(0f, 1f)

    /** How far back the athlete's arm is drawn, 0 to 1. */
    val drawAmount get() = if (active) (pull / (fullPull / sensitivity)).coerceIn(0f, 1f) else 0f

    /** Aim (radians) from how far sideways the finger has moved. */
    var previewYaw = 0f
        private set

    fun down(x: Float, y: Float, @Suppress("UNUSED_PARAMETER") t: Float) {
        active = true
        startX = x
        startY = y
        pull = 0f
        side = 0f
        previewYaw = 0f
    }

    fun move(x: Float, y: Float, @Suppress("UNUSED_PARAMETER") t: Float) {
        if (!active) return
        pull = maxOf(0f, y - startY)
        side = x - startX
        previewYaw = (side / SIDE_FULL).coerceIn(-1f, 1f) * MAX_YAW
    }

    /** Lets go: returns (power, yaw) for a throw, or null if it wasn't pulled back. */
    fun up(x: Float, y: Float, t: Float): Pair<Float, Float>? {
        if (!active) return null
        move(x, y, t)
        val p = power
        val pulled = pull >= PULL_MIN
        active = false
        return if (pulled) p to previewYaw else null
    }

    fun cancel() { active = false }

    companion object {
        /** A pull shorter than this (virtual pixels) isn't a throw. */
        const val PULL_MIN = 18f
        /** Sideways drag for the widest aim. */
        const val SIDE_FULL = 140f
        const val MAX_YAW = 0.45f
    }
}
