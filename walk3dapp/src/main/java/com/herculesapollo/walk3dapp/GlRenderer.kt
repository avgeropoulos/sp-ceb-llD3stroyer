package com.herculesapollo.walk3dapp

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import com.herculesapollo.walk3d.Frame
import com.herculesapollo.walk3d.Game3D
import com.herculesapollo.walk3d.Mat4
import com.herculesapollo.walk3d.Mesh
import com.herculesapollo.walk3d.Meshes
import com.herculesapollo.walk3d.VIEW_H
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Runs the game loop on the GL thread and draws the frame the game builds: flat
 * colored meshes with sunlight and distance fog, over a sky gradient.
 */
class GlRenderer(private val game: Game3D) : GLSurfaceView.Renderer {
    private var program = 0
    private var skyProgram = 0
    private var aPos = 0
    private var aNormal = 0
    private var aColor = 0
    private var uMvp = 0
    private var uMv = 0
    private var uNormal = 0
    private var uColor = 0
    private var uLight = 0
    private var uAmbient = 0
    private var uSun = 0
    private var uFog = 0
    private var uFogRange = 0
    private var skyPos = 0
    private var skyTop = 0
    private var skyBottom = 0
    private var skyBuffer = 0
    private var aspect = 1f
    private var last = 0L

    private val mvp = FloatArray(16)
    private val mv = FloatArray(16)
    private val nrm = FloatArray(9)
    /** The per-level meshes (terrain, scenery) on the GPU, freed when a level stops using them. */
    private val levelMeshes = ArrayList<Mesh>()
    private val used: MutableSet<Mesh> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // A new GL context: anything uploaded to the old one is gone.
        for (m in Meshes.all) m.handle = null
        for (m in levelMeshes) m.handle = null
        levelMeshes.clear()
        program = link(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        aColor = GLES20.glGetAttribLocation(program, "aColor")
        uMvp = GLES20.glGetUniformLocation(program, "uMvp")
        uMv = GLES20.glGetUniformLocation(program, "uMv")
        uNormal = GLES20.glGetUniformLocation(program, "uNormal")
        uColor = GLES20.glGetUniformLocation(program, "uColor")
        uLight = GLES20.glGetUniformLocation(program, "uLight")
        uAmbient = GLES20.glGetUniformLocation(program, "uAmbient")
        uSun = GLES20.glGetUniformLocation(program, "uSun")
        uFog = GLES20.glGetUniformLocation(program, "uFog")
        uFogRange = GLES20.glGetUniformLocation(program, "uFogRange")

        skyProgram = link(SKY_VERTEX, SKY_FRAGMENT)
        skyPos = GLES20.glGetAttribLocation(skyProgram, "aPos")
        skyTop = GLES20.glGetUniformLocation(skyProgram, "uTop")
        skyBottom = GLES20.glGetUniformLocation(skyProgram, "uBottom")
        skyBuffer = upload(floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f))

        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        last = System.nanoTime()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.coerceAtLeast(1)
        synchronized(game) { game.viewW = VIEW_H * aspect }
    }

    override fun onDrawFrame(gl: GL10?) {
        val now = System.nanoTime()
        val elapsed = ((now - last) / 1e9f).coerceIn(0f, 0.1f)
        last = now
        val frame: Frame
        synchronized(game) {
            // Advance by exactly the time this frame covers, in small steps, so motion stays
            // smooth on 60, 90 and 120 Hz screens alike (fixed 1/60 s steps judder on fast screens).
            val steps = kotlin.math.ceil(elapsed / MAX_STEP).toInt()
            repeat(steps) { game.update(elapsed / steps) }
            frame = game.buildFrame(aspect)
        }
        draw(frame)
    }

    private fun draw(f: Frame) {
        GLES20.glClearColor(r(f.fogColor), g(f.fogColor), b(f.fogColor), 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Sky: a gradient behind everything.
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        GLES20.glUseProgram(skyProgram)
        GLES20.glUniform3f(skyTop, r(f.skyTop), g(f.skyTop), b(f.skyTop))
        GLES20.glUniform3f(skyBottom, r(f.fogColor), g(f.fogColor), b(f.fogColor))
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, skyBuffer)
        GLES20.glEnableVertexAttribArray(skyPos)
        GLES20.glVertexAttribPointer(skyPos, 2, GLES20.GL_FLOAT, false, 8, 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6)
        GLES20.glDisableVertexAttribArray(skyPos)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)

        GLES20.glUseProgram(program)
        GLES20.glUniform3f(uLight, f.light[0], f.light[1], f.light[2])
        GLES20.glUniform1f(uAmbient, f.ambient)
        GLES20.glUniform1f(uSun, f.sun)
        GLES20.glUniform3f(uFog, r(f.fogColor), g(f.fogColor), b(f.fogColor))
        GLES20.glUniform2f(uFogRange, f.fogNear, f.fogFar)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glEnableVertexAttribArray(aColor)
        var bound: Mesh? = null
        used.clear()
        for (item in f.items) {
            val mesh = item.mesh
            if (mesh !== bound) {
                // Push the terrain back a touch so paths and shadows lying on it never flicker.
                if (mesh.ground) {
                    GLES20.glEnable(GLES20.GL_POLYGON_OFFSET_FILL)
                    GLES20.glPolygonOffset(2f, 4f)
                } else if (bound?.ground == true) {
                    GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
                }
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, buffer(mesh))
                GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, STRIDE_BYTES, 0)
                GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, STRIDE_BYTES, 12)
                GLES20.glVertexAttribPointer(aColor, 3, GLES20.GL_FLOAT, false, STRIDE_BYTES, 24)
                bound = mesh
                used += mesh
            }
            Mat4.multiply(f.viewProj, item.model, mvp)
            Mat4.multiply(f.view, item.model, mv)
            Mat4.normalMatrix(item.model, nrm)
            GLES20.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES20.glUniformMatrix4fv(uMv, 1, false, mv, 0)
            GLES20.glUniformMatrix3fv(uNormal, 1, false, nrm, 0)
            GLES20.glUniform3f(uColor, r(item.color), g(item.color), b(item.color))
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount)
        }
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aNormal)
        GLES20.glDisableVertexAttribArray(aColor)
        GLES20.glDisable(GLES20.GL_POLYGON_OFFSET_FILL)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        // Free the last level's terrain and scenery once they're no longer drawn.
        val it = levelMeshes.iterator()
        while (it.hasNext()) {
            val m = it.next()
            if (m in used) continue
            (m.handle as? Int)?.let { GLES20.glDeleteBuffers(1, intArrayOf(it), 0) }
            m.handle = null
            it.remove()
        }
    }

    /** Uploads [mesh] to the GPU the first time it's drawn. */
    private fun buffer(mesh: Mesh): Int = (mesh.handle as? Int) ?: upload(mesh.data).also {
        mesh.handle = it
        if (Meshes.all.none { m -> m === mesh }) levelMeshes += mesh
    }

    private fun upload(data: FloatArray): Int {
        val ids = IntArray(1)
        GLES20.glGenBuffers(1, ids, 0)
        val fb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fb.put(data).position(0)
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, ids[0])
        GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, data.size * 4, fb, GLES20.GL_STATIC_DRAW)
        return ids[0]
    }

    private fun link(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, compile(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, compile(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Shader link failed: " + GLES20.glGetProgramInfoLog(p) }
        return p
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "Shader compile failed: " + GLES20.glGetShaderInfoLog(s) }
        return s
    }

    private fun r(c: Int) = (c shr 16 and 0xFF) / 255f
    private fun g(c: Int) = (c shr 8 and 0xFF) / 255f
    private fun b(c: Int) = (c and 0xFF) / 255f

    private companion object {
        const val MAX_STEP = 1f / 120f
        const val STRIDE_BYTES = Mesh.STRIDE * 4

        const val VERTEX = """
            uniform mat4 uMvp;
            uniform mat4 uMv;
            uniform mat3 uNormal;
            uniform vec3 uColor;
            uniform vec3 uLight;
            uniform float uAmbient;
            uniform float uSun;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            attribute vec3 aColor;
            varying vec3 vColor;
            varying float vDist;
            void main() {
                vec3 n = normalize(uNormal * aNormal);
                float l = min(uAmbient + uSun * max(dot(n, uLight), 0.0), 1.15);
                vColor = min(uColor * aColor * l, vec3(1.0));
                vDist = length((uMv * vec4(aPos, 1.0)).xyz);
                gl_Position = uMvp * vec4(aPos, 1.0);
            }
        """

        const val FRAGMENT = """
            precision mediump float;
            uniform vec3 uFog;
            uniform vec2 uFogRange;
            varying vec3 vColor;
            varying float vDist;
            void main() {
                float f = clamp((vDist - uFogRange.x) / (uFogRange.y - uFogRange.x), 0.0, 1.0);
                gl_FragColor = vec4(mix(vColor, uFog, f), 1.0);
            }
        """

        const val SKY_VERTEX = """
            attribute vec2 aPos;
            varying float vY;
            void main() {
                vY = aPos.y;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        const val SKY_FRAGMENT = """
            precision mediump float;
            uniform vec3 uTop;
            uniform vec3 uBottom;
            varying float vY;
            void main() {
                gl_FragColor = vec4(mix(uBottom, uTop, clamp(vY, 0.0, 1.0)), 1.0);
            }
        """
    }
}
