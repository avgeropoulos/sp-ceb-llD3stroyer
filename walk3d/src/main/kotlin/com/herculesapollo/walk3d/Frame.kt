package com.herculesapollo.walk3d

/** One mesh to draw with a model matrix and a flat color. */
class DrawItem {
    lateinit var mesh: Mesh
    val model = FloatArray(16)
    var color = 0
}

/**
 * Everything needed to draw one 3D frame: the camera, the light, fog and a list of
 * [DrawItem]s. Items are pooled, so building a frame doesn't make garbage.
 */
class Frame {
    val view = FloatArray(16)
    val proj = FloatArray(16)
    val viewProj = FloatArray(16)
    var eyeX = 0f
    var eyeY = 0f
    var eyeZ = 0f
    /** Direction toward the sun (normalized). */
    val light = floatArrayOf(0.35f, 0.85f, 0.4f)
    var ambient = 0.55f
    /** How bright direct sunlight is on top of [ambient]. */
    var sun = 0.5f
    var skyTop = 0
    /** Horizon color; also what the fog fades into. */
    var fogColor = 0
    var fogNear = 18f
    var fogFar = 48f

    private val pool = ArrayList<DrawItem>()
    var count = 0
        private set

    val items: List<DrawItem> get() = pool.subList(0, count)

    fun clear() { count = 0 }

    /** Adds an item with an identity model matrix; returns it so the caller can transform it. */
    fun add(mesh: Mesh, color: Int): DrawItem {
        if (count == pool.size) pool += DrawItem()
        val it = pool[count++]
        it.mesh = mesh
        it.color = color
        Mat4.identity(it.model)
        return it
    }

    /** Adds an item positioned by a copy of [base]. */
    fun add(mesh: Mesh, color: Int, base: FloatArray): DrawItem {
        val it = add(mesh, color)
        base.copyInto(it.model)
        return it
    }

    fun setCamera(ex: Float, ey: Float, ez: Float, cx: Float, cy: Float, cz: Float, aspect: Float) {
        eyeX = ex; eyeY = ey; eyeZ = ez
        Mat4.lookAt(view, ex, ey, ez, cx, cy, cz)
        Mat4.perspective(proj, FOV, aspect, 0.3f, 120f)
        Mat4.multiply(proj, view, viewProj)
    }

    private val tmp = FloatArray(4)

    /**
     * Projects a world point onto the 2D screen ([viewW] x [viewH] virtual pixels).
     * Returns false if it's behind the camera.
     */
    fun project(x: Float, y: Float, z: Float, viewW: Float, viewH: Float, out: FloatArray): Boolean {
        Mat4.transform(viewProj, x, y, z, tmp)
        if (tmp[3] <= 0.05f) return false
        out[0] = (tmp[0] / tmp[3] * 0.5f + 0.5f) * viewW
        out[1] = (0.5f - tmp[1] / tmp[3] * 0.5f) * viewH
        return true
    }

    companion object {
        const val FOV = 1.05f
    }
}
