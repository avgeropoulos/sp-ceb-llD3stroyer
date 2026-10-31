package com.javelinstrike.game

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * World coordinates are metres: x to the right, y up, z down the field.
 * The thrower stands at the foul line, z = 0.
 */

const val GRAVITY = 9.81f
/** The usual throwing angle, about 36° above level. Leaning the phone changes it. */
const val RELEASE_ANGLE = 0.63f
/** The flattest and steepest throws you can aim: 20° and 55°. */
const val MIN_ELEVATION = 0.35f
const val MAX_ELEVATION = 0.96f
/** Even the gentlest throw goes about 30 m, so the whole swing range is spent on far targets. */
const val MIN_SPEED = 17.5f
const val MAX_SPEED = 38f
/** Sideways push per m/s of wind, in m/s². */
const val WIND_PUSH = 0.3f
const val RELEASE_X = 0.35f
const val RELEASE_Y = 2f
const val RELEASE_Z = 0.4f
const val JAVELIN_LEN = 2.6f
/** Targets stay within this far of the middle of the field (metres). */
const val FIELD_EDGE = 34f
/** Furthest left or right the player can aim, in radians. */
const val MAX_AIM = 0.42f

fun speedForPower(power: Float) = MIN_SPEED + (MAX_SPEED - MIN_SPEED) * power.coerceIn(0f, 1f)

class Javelin(
    var x: Float, var y: Float, var z: Float,
    var vx: Float, var vy: Float, var vz: Float,
) {
    var landed = false
        private set

    /** Moves the javelin on. Returns true on the step it hits the ground. */
    fun step(dt: Float, wind: Float): Boolean {
        if (landed) return false
        vx += wind * WIND_PUSH * dt
        vy -= GRAVITY * dt
        val nx = x + vx * dt
        val ny = y + vy * dt
        val nz = z + vz * dt
        if (ny <= 0f) {
            val f = y / (y - ny)
            x += (nx - x) * f
            z += (nz - z) * f
            y = 0f
            landed = true
            return true
        }
        x = nx; y = ny; z = nz
        return false
    }

    val speed get() = sqrt(vx * vx + vy * vy + vz * vz)

    companion object {
        /** A throw at [power] (0 to 1), [yaw] radians to the right and [elevation] radians up. */
        fun launch(power: Float, yaw: Float, elevation: Float = RELEASE_ANGLE): Javelin {
            val v = speedForPower(power)
            val el = elevation.coerceIn(MIN_ELEVATION, MAX_ELEVATION)
            val flat = v * cos(el)
            return Javelin(
                RELEASE_X, RELEASE_Y, RELEASE_Z,
                flat * sin(yaw), v * sin(el), flat * cos(yaw),
            )
        }

        /** Where a throw would land, as (x, z). Used for the power markers, tests and tuning levels. */
        fun landing(power: Float, yaw: Float = 0f, wind: Float = 0f, elevation: Float = RELEASE_ANGLE): Pair<Float, Float> {
            val j = launch(power, yaw, elevation)
            while (!j.step(1f / 120f, wind)) { /* fly */ }
            return j.x to j.z
        }
    }
}

/** A javelin stuck in the ground, pointing back along the way it came. */
class Stuck(val x: Float, val z: Float, val dx: Float, val dy: Float, val dz: Float, val hit: Boolean)

/**
 * The target: rings on the ground with a shield on a post in the middle. A moving target
 * slides [sway] metres side to side and [swayZ] metres nearer and further.
 */
class Target(
    val baseX: Float,
    val baseZ: Float,
    val radius: Float,
    val sway: Float = 0f,
    val swayZ: Float = 0f,
    val speed: Float = 0f,
    phase: Float = 0f,
) {
    private var t = phase
    var x = baseX
        private set
    var z = baseZ
        private set

    val moving get() = speed > 0f && (sway > 0f || swayZ > 0f)

    init { place() }

    fun update(dt: Float) {
        t += dt * speed
        place()
    }

    /** Where the target will be [ahead] seconds from now, as (x, z). */
    fun positionAt(ahead: Float): Pair<Float, Float> {
        val tt = t + ahead * speed
        return baseX + sway * sin(tt) to baseZ + swayZ * sin(tt * 0.73f + 1.3f)
    }

    private fun place() {
        val (px, pz) = positionAt(0f)
        x = px
        z = pz
    }

    /** 3 = bullseye, 2 = middle ring, 1 = outer ring, 0 = miss. */
    fun ring(px: Float, pz: Float): Int {
        val d = hypot(px - x, pz - z)
        return when {
            d <= radius / 3f -> 3
            d <= radius * 2f / 3f -> 2
            d <= radius -> 1
            else -> 0
        }
    }
}

/** A simple pinhole camera that can tilt down. [project] fills [sx], [sy] and [ppm]. */
class Camera {
    var x = 0f
    var y = 3f
    var z = -7f
    var pitch = 0.14f
    var focal = 420f
    var cx = 0f
    var cy = 380f

    var sx = 0f
        private set
    var sy = 0f
        private set
    /** Screen pixels per metre at the last projected point. */
    var ppm = 0f
        private set

    /** Returns false if the point is behind (or too close to) the camera. */
    fun project(px: Float, py: Float, pz: Float): Boolean {
        val c = cos(pitch)
        val s = sin(pitch)
        val rx = px - x
        val ry = py - y
        val rz = pz - z
        val depth = -ry * s + rz * c
        if (depth < NEAR) return false
        val up = ry * c + rz * s
        ppm = focal / depth
        sx = cx + rx * ppm
        sy = cy - up * ppm
        return true
    }

    /** The screen height of the horizon. */
    fun horizon() = cy - focal * kotlin.math.tan(pitch)

    /** The nearest ground z the camera can see without points falling behind it. */
    fun nearGroundZ(): Float = z + (NEAR + 0.2f - y * sin(pitch)) / cos(pitch)

    fun set(o: Camera) {
        x = o.x; y = o.y; z = o.z; pitch = o.pitch; focal = o.focal; cx = o.cx; cy = o.cy
    }

    companion object {
        const val NEAR = 0.3f
    }
}

/** A puff of dirt or a bit of confetti. */
class Particle(
    var x: Float, var y: Float, var z: Float,
    var vx: Float, var vy: Float, var vz: Float,
    var life: Float, val color: Int, val size: Float, val heavy: Boolean,
)
