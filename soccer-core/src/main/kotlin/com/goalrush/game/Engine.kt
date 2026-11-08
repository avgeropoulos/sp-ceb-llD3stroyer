package com.goalrush.game

import kotlin.math.sqrt

/**
 * Platform-neutral drawing surface. Coordinates are virtual pixels: the view is always
 * [VIEW_W] wide and [Game.viewH] tall. Implementations scale up to the real screen.
 */
interface Gfx {
    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int)
    fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int)
    fun oval(x: Float, y: Float, w: Float, h: Float, color: Int)
    fun poly(xs: FloatArray, ys: FloatArray, n: Int, color: Int)

    /** A line [width] virtual pixels thick, with round ends. */
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int)

    /** [y] is the text baseline. [align]: 0 = left, 1 = center, 2 = right. */
    fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int = 0)

    /** A vertical gradient filling a rectangle. */
    fun gradient(x: Float, y: Float, w: Float, h: Float, top: Int, bottom: Int)
}

const val VIEW_W = 400f

fun Gfx.circle(cx: Float, cy: Float, r: Float, color: Int) = oval(cx - r, cy - r, r * 2, r * 2, color)

fun Gfx.shadowText(s: String, x: Float, y: Float, size: Float, color: Int, align: Int = 1) {
    text(s, x + size * 0.07f, y + size * 0.07f, size, 0xAA000000.toInt(), align)
    text(s, x, y, size, color, align)
}

fun argb(a: Int, rgb: Int): Int = (a.coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

fun alpha(color: Int, a: Float): Int = argb(((color ushr 24) * a).toInt(), color)

fun mix(c1: Int, c2: Int, t: Float): Int {
    fun ch(c: Int, s: Int) = (c shr s) and 0xFF
    fun m(s: Int) = (ch(c1, s) + (ch(c2, s) - ch(c1, s)) * t).toInt().coerceIn(0, 255)
    return (m(24) shl 24) or (m(16) shl 16) or (m(8) shl 8) or m(0)
}

enum class Sound { KICK, GOAL, SAVE, POST, MISS, WHISTLE, CLICK, WIN, FAIL, BOUNCE }

interface SoundSink {
    fun play(s: Sound)

    /** Turns the looping stadium crowd noise on or off. */
    fun crowd(on: Boolean) {}

    object Silent : SoundSink {
        override fun play(s: Sound) {}
    }
}

/** Small key/value store so saves and high scores survive restarts. */
interface Storage {
    fun getInt(key: String, def: Int = 0): Int
    fun putInt(key: String, value: Int)
    fun getString(key: String, def: String = ""): String
    fun putString(key: String, value: String)

    class Memory : Storage {
        val ints = HashMap<String, Int>()
        val strings = HashMap<String, String>()
        override fun getInt(key: String, def: Int) = ints[key] ?: def
        override fun putInt(key: String, value: Int) { ints[key] = value }
        override fun getString(key: String, def: String) = strings[key] ?: def
        override fun putString(key: String, value: String) { strings[key] = value }
    }
}

/** In-game updates. The Android layer downloads the newest APK from the GitHub release. */
interface Updater {
    enum class Status { IDLE, CHECKING, UP_TO_DATE, AVAILABLE, DOWNLOADING, INSTALLING, ERROR, UNSUPPORTED }

    val currentVersion: String
    val status: Status
    val latestVersion: String
    val message: String
    /** 0..1 while [Status.DOWNLOADING]. */
    val progress: Float

    fun check()
    fun install()

    /** Used by tests and platforms without updates. */
    object None : Updater {
        override val currentVersion = "dev"
        override val status = Status.UNSUPPORTED
        override val latestVersion = ""
        override val message = "Updates are not available here."
        override val progress = 0f
        override fun check() {}
        override fun install() {}
    }
}

data class Vec3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(x * x + y * y + z * z)
    fun norm(): Vec3 { val l = len(); return if (l < 1e-6f) this else this * (1f / l) }

    companion object {
        val ZERO = Vec3(0f, 0f, 0f)
        val UP = Vec3(0f, 1f, 0f)
    }
}

/** Mutable projection result, reused to avoid garbage in the render loop. */
class Proj {
    var x = 0f
    var y = 0f
    /** Pixels per metre at this depth. */
    var s = 0f
    var depth = 0f
    var visible = false
}

/**
 * A pinhole camera. World axes: x to the right, y up, z towards the goal.
 * The goal line is at z = 0 and the ball starts at negative z.
 */
class Camera {
    var pos = Vec3(0f, 1.5f, -14f)
    var right = Vec3(1f, 0f, 0f)
    var up = Vec3.UP
    var fwd = Vec3(0f, 0f, 1f)
    /** Focal length in virtual pixels. */
    var f = 420f
    var cx = VIEW_W / 2
    var cy = 300f

    fun lookAt(eye: Vec3, target: Vec3) {
        pos = eye
        fwd = (target - eye).norm()
        right = (Vec3.UP cross fwd).norm()
        up = fwd cross right
    }

    fun toCam(x: Float, y: Float, z: Float, out: FloatArray) {
        val vx = x - pos.x; val vy = y - pos.y; val vz = z - pos.z
        out[0] = vx * right.x + vy * right.y + vz * right.z
        out[1] = vx * up.x + vy * up.y + vz * up.z
        out[2] = vx * fwd.x + vy * fwd.y + vz * fwd.z
    }

    private val tmp = FloatArray(3)

    fun project(p: Vec3, out: Proj): Proj = project(p.x, p.y, p.z, out)

    fun project(x: Float, y: Float, z: Float, out: Proj): Proj {
        val vx = x - pos.x; val vy = y - pos.y; val vz = z - pos.z
        val c0 = vx * right.x + vy * right.y + vz * right.z
        val c1 = vx * up.x + vy * up.y + vz * up.z
        val c2 = vx * fwd.x + vy * fwd.y + vz * fwd.z
        out.depth = c2
        out.visible = c2 > NEAR
        val d = if (c2 > NEAR) c2 else NEAR
        out.s = f / d
        out.x = cx + c0 * out.s
        out.y = cy - c1 * out.s
        return out
    }

    /** The world-space ray through a screen point. */
    fun rayDir(sx: Float, sy: Float): Vec3 =
        (fwd + right * ((sx - cx) / f) + up * (-(sy - cy) / f)).norm()

    /** Where the ray through a screen point meets the plane z = [z]; null if it never does. */
    fun unprojectToZ(sx: Float, sy: Float, z: Float): Vec3? {
        val d = rayDir(sx, sy)
        if (d.z <= 1e-4f) return null
        val t = (z - pos.z) / d.z
        return pos + d * t
    }

    /** Where the ray through a screen point meets the horizontal plane y = [y]. */
    fun unprojectToY(sx: Float, sy: Float, y: Float): Vec3? {
        val d = rayDir(sx, sy)
        if (d.y >= -1e-4f) return null
        val t = (y - pos.y) / d.y
        return pos + d * t
    }

    // Polygon clipping against the near plane, for ground quads that reach behind the camera.
    private val inX = FloatArray(MAX_POLY); private val inY = FloatArray(MAX_POLY); private val inZ = FloatArray(MAX_POLY)
    private val clX = FloatArray(MAX_POLY); private val clY = FloatArray(MAX_POLY); private val clZ = FloatArray(MAX_POLY)
    val outX = FloatArray(MAX_POLY)
    val outY = FloatArray(MAX_POLY)

    /**
     * Projects a convex world polygon, clipping it to the near plane. Returns the number of
     * screen points written to [outX]/[outY] (0 when entirely behind the camera).
     */
    fun projectPoly(xs: FloatArray, ys: FloatArray, zs: FloatArray, n: Int): Int {
        for (i in 0 until n) {
            toCam(xs[i], ys[i], zs[i], tmp)
            inX[i] = tmp[0]; inY[i] = tmp[1]; inZ[i] = tmp[2]
        }
        var m = 0
        for (i in 0 until n) {
            val j = (i + 1) % n
            val ai = inZ[i] > CLIP; val aj = inZ[j] > CLIP
            if (ai) { clX[m] = inX[i]; clY[m] = inY[i]; clZ[m] = inZ[i]; m++ }
            if (ai != aj && m < MAX_POLY - 1) {
                val t = (CLIP - inZ[i]) / (inZ[j] - inZ[i])
                clX[m] = inX[i] + (inX[j] - inX[i]) * t
                clY[m] = inY[i] + (inY[j] - inY[i]) * t
                clZ[m] = CLIP
                m++
            }
        }
        for (i in 0 until m) {
            val s = f / clZ[i]
            outX[i] = cx + clX[i] * s
            outY[i] = cy - clY[i] * s
        }
        return m
    }

    companion object {
        const val NEAR = 0.05f
        const val CLIP = 0.3f
        const val MAX_POLY = 48
    }
}
