package com.roccoquest.team3d

import com.roccoquest.game.VIEW_H
import kotlin.math.PI
import kotlin.math.sqrt
import kotlin.math.tan

/** Lighting and atmosphere for a frame. Colours are ARGB. */
class Env {
    var skyTop = 0xFF4A9BFF.toInt()
    var skyBottom = 0xFFBFE6FF.toInt()
    var fog = 0xFFBFE6FF.toInt()
    var fogNear = 500f
    var fogFar = 2200f
    /** Direction the sunlight travels *from* (normalised in [setLight]). */
    val light = floatArrayOf(-0.45f, 0.8f, 0.55f)
    var sun = 0xFFFFF4E0.toInt()
    var ambientSky = 0xFF8FA8C8.toInt()
    var ambientGround = 0xFF5A5040.toInt()

    fun setLight(x: Float, y: Float, z: Float) {
        val l = sqrt(x * x + y * y + z * z)
        light[0] = x / l; light[1] = y / l; light[2] = z / l
    }
}

/** One mesh drawn with one transform. */
class DrawCmd {
    var mesh: Mesh = Prims.cube
    val model = FloatArray(16)
    val normal = FloatArray(9)
    /** ARGB multiplier; alpha below 255 makes the draw translucent. */
    var tint = -1
    /** 0 = fully lit, 1 = shows its own colour regardless of lighting (lava, glows). */
    var emissive = 0f
    /** Strength of the specular highlight. */
    var shine = 0f
    /** Throw away see-through texels (for pixel-art blocks with holes, like clouds). */
    var cutout = false
    /** Distance from the camera, for sorting translucent draws. */
    var dist = 0f
    val translucent get() = (tint ushr 24) < 255
}

/** Text positioned in the 3D world, drawn by the 2D overlay after projection. */
class Label(var text: String, var x: Float, var y: Float, var size: Float, var color: Int, var align: Int)

/**
 * Everything needed to draw one frame: camera, environment and a list of draws.
 * A backend (OpenGL on the phone, a software rasteriser in tests) consumes it.
 */
class DrawList {
    val env = Env()
    val view = Mat4()
    val proj = Mat4()
    val viewProj = Mat4()
    val camPos = FloatArray(3)
    /** When false the backend clears to black and draws no 3D at all. */
    var showWorld = true
    var viewW = 400f

    private val pool = ArrayList<DrawCmd>()
    var count = 0
        private set
    /** Draw order after [finish]: opaque first, then translucent far to near. */
    var order = IntArray(256)
        private set

    private val labelPool = ArrayList<Label>()
    var labelCount = 0
        private set

    fun cmd(i: Int): DrawCmd = pool[order[i]]
    fun label(i: Int): Label = labelPool[i]

    fun clear() {
        count = 0
        labelCount = 0
    }

    fun add(mesh: Mesh, model: Mat4, tint: Int = -1, emissive: Float = 0f, shine: Float = 0f): DrawCmd {
        if (count == pool.size) pool += DrawCmd()
        val c = pool[count++]
        c.mesh = mesh
        model.m.copyInto(c.model)
        model.normalMatrix(c.normal)
        c.tint = tint
        c.emissive = emissive
        c.shine = shine
        c.cutout = false
        val dx = c.model[12] - camPos[0]; val dy = c.model[13] - camPos[1]; val dz = c.model[14] - camPos[2]
        c.dist = dx * dx + dy * dy + dz * dz
        return c
    }

    fun addLabel(text: String, x: Float, y: Float, size: Float, color: Int, align: Int) {
        if (labelCount == labelPool.size) labelPool += Label("", 0f, 0f, 0f, 0, 0)
        val l = labelPool[labelCount++]
        l.text = text; l.x = x; l.y = y; l.size = size; l.color = color; l.align = align
    }

    fun finish() {
        if (order.size < count) order = IntArray(count * 2)
        var n = 0
        for (i in 0 until count) if (!pool[i].translucent) order[n++] = i
        val firstClear = n
        for (i in 0 until count) if (pool[i].translucent) order[n++] = i
        // Insertion sort is plenty for the handful of translucent draws.
        for (i in firstClear + 1 until n) {
            val v = order[i]
            var j = i - 1
            while (j >= firstClear && pool[order[j]].dist < pool[v].dist) { order[j + 1] = order[j]; j-- }
            order[j + 1] = v
        }
    }

    // ---------------------------------------------------------------- camera
    private val tmp = FloatArray(3)

    /**
     * Points the camera at the 2D game's view: the plane z = 0 is framed so that its
     * visible part matches the original [viewW] x [VIEW_H] window starting at [camX],
     * which keeps gameplay (what's on screen, when enemies wake up) identical.
     */
    fun setCamera(camX: Float, viewW: Float, yaw: Float = 0f, pitch: Float = 0.16f, lift: Float = 0f) {
        this.viewW = viewW
        val aspect = viewW / VIEW_H
        val dist = (VIEW_H / 2f) / tan(FOV / 2f)
        val tx = camX + viewW / 2f
        val ty = -VIEW_H / 2f + lift
        val ex = tx + kotlin.math.sin(yaw) * dist
        val ey = ty + kotlin.math.sin(pitch) * dist
        val ez = kotlin.math.cos(yaw) * kotlin.math.cos(pitch) * dist
        view.lookAt(ex, ey, ez, tx, ty - 4f, 0f)
        proj.perspective(FOV, aspect, 20f, 4000f)
        viewProj.set(proj).mul(view)
        camPos[0] = ex; camPos[1] = ey; camPos[2] = ez
    }

    /** Projects a world point to virtual screen pixels; returns false when behind the camera. */
    fun project(x: Float, y: Float, z: Float, out: FloatArray): Boolean {
        val w = viewProj.transform(x, y, z, tmp)
        if (w <= 0.01f) return false
        out[0] = (tmp[0] / w * 0.5f + 0.5f) * viewW
        out[1] = (0.5f - tmp[1] / w * 0.5f) * VIEW_H
        return true
    }

    /** Half the visible width of the world at depth [z], with some margin. */
    fun halfWidthAt(z: Float): Float {
        val d = camPos[2] - z
        return d * tan(FOV / 2f) * (viewW / VIEW_H) + 48f
    }

    companion object {
        val FOV = (40.0 * PI / 180.0).toFloat()
    }
}
