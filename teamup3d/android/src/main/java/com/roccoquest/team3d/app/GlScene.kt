package com.roccoquest.team3d.app

import android.graphics.Bitmap
import android.opengl.GLES20
import android.opengl.GLUtils
import com.roccoquest.team3d.Atlas
import com.roccoquest.team3d.DrawList
import com.roccoquest.team3d.Mesh
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Draws a [DrawList] with OpenGL ES 2.0: per-pixel lighting, fog and translucency. */
class GlScene {
    private var program = 0
    private var aPos = 0
    private var aNormal = 0
    private var aColor = 0
    private var aUv = 0
    private var uViewProj = 0
    private var uModel = 0
    private var uNormal = 0
    private var uTint = 0
    private var uEmissive = 0
    private var uShine = 0
    private var uLight = 0
    private var uSun = 0
    private var uSkyAmb = 0
    private var uGroundAmb = 0
    private var uCam = 0
    private var uFog = 0
    private var uFogRange = 0
    private var uTex = 0
    private var uCutout = 0
    private var texture = 0
    /** The [Atlas.version] the GPU copy of the atlas matches. */
    private var atlasVersion = -1

    /** Meshes that currently have a GL buffer, so they can be reset if the context is lost. */
    private val uploaded = ArrayList<Mesh>()

    /** Called whenever a new GL context is created (first start, or after being paused). */
    fun init() {
        // Buffers from an old context are gone; upload again on first use.
        for (m in uploaded) m.handle = 0
        uploaded.clear()
        program = Gl.program(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aNormal = GLES20.glGetAttribLocation(program, "aNormal")
        aColor = GLES20.glGetAttribLocation(program, "aColor")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        fun u(n: String) = GLES20.glGetUniformLocation(program, n)
        uViewProj = u("uViewProj"); uModel = u("uModel"); uNormal = u("uNormal")
        uTint = u("uTint"); uEmissive = u("uEmissive"); uShine = u("uShine")
        uLight = u("uLight"); uSun = u("uSun"); uSkyAmb = u("uSkyAmb"); uGroundAmb = u("uGroundAmb")
        uCam = u("uCam"); uFog = u("uFog"); uFogRange = u("uFogRange"); uTex = u("uTex")
        uCutout = u("uCutout")

        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        // Crisp pixel art up close, smooth further away.
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_NEAREST)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        atlasVersion = -1
    }

    /**
     * Copies the tile atlas to the GPU: all of it once, then only the rows where new tile
     * pictures were added, so a new kind of block never makes the game stutter.
     */
    private fun uploadAtlas() {
        if (atlasVersion == Atlas.version) return
        val rows = Atlas.takeDirtyRows()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        if (atlasVersion < 0 || rows == null || rows.first == 0 && rows.last == Atlas.SIZE - 1) {
            val bmp = Bitmap.createBitmap(Atlas.pixels, Atlas.SIZE, Atlas.SIZE, Bitmap.Config.ARGB_8888)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0)
            bmp.recycle()
        } else {
            val n = rows.last - rows.first + 1
            val bmp = Bitmap.createBitmap(Atlas.pixels, rows.first * Atlas.SIZE, Atlas.SIZE, Atlas.SIZE, n, Bitmap.Config.ARGB_8888)
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, rows.first, bmp)
            bmp.recycle()
        }
        atlasVersion = Atlas.version
    }

    fun render(list: DrawList) {
        freeReleased()
        if (!list.showWorld || list.count == 0) return
        val env = list.env
        GLES20.glUseProgram(program)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glDisable(GLES20.GL_CULL_FACE)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        uploadAtlas()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uViewProj, 1, false, list.viewProj.m, 0)
        GLES20.glUniform3f(uLight, env.light[0], env.light[1], env.light[2])
        rgb(uSun, env.sun); rgb(uSkyAmb, env.ambientSky); rgb(uGroundAmb, env.ambientGround); rgb(uFog, env.fog)
        GLES20.glUniform3f(uCam, list.camPos[0], list.camPos[1], list.camPos[2])
        GLES20.glUniform2f(uFogRange, env.fogNear, env.fogFar)
        GLES20.glEnableVertexAttribArray(aPos)
        GLES20.glEnableVertexAttribArray(aNormal)
        GLES20.glEnableVertexAttribArray(aColor)
        GLES20.glEnableVertexAttribArray(aUv)

        var blending = false
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDepthMask(true)
        for (i in 0 until list.count) {
            val c = list.cmd(i)
            val mesh = c.mesh
            if (mesh.released || mesh.vertexCount == 0) continue
            if (c.translucent && !blending) {
                // Translucent draws come last: blend them and keep the depth buffer as is.
                blending = true
                GLES20.glEnable(GLES20.GL_BLEND)
                GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
                GLES20.glDepthMask(false)
            }
            bind(mesh)
            GLES20.glUniformMatrix4fv(uModel, 1, false, c.model, 0)
            GLES20.glUniformMatrix3fv(uNormal, 1, false, c.normal, 0)
            val t = c.tint
            GLES20.glUniform4f(uTint, (t shr 16 and 255) / 255f, (t shr 8 and 255) / 255f, (t and 255) / 255f, (t ushr 24) / 255f)
            GLES20.glUniform1f(uEmissive, c.emissive)
            GLES20.glUniform1f(uShine, c.shine)
            GLES20.glUniform1f(uCutout, if (c.cutout) 1f else 0f)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, mesh.vertexCount)
        }
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aNormal)
        GLES20.glDisableVertexAttribArray(aColor)
        GLES20.glDisableVertexAttribArray(aUv)
        GLES20.glDepthMask(true)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
    }

    private fun rgb(loc: Int, c: Int) =
        GLES20.glUniform3f(loc, (c shr 16 and 255) / 255f, (c shr 8 and 255) / 255f, (c and 255) / 255f)

    private fun bind(mesh: Mesh) {
        if (mesh.handle == 0) {
            val ids = IntArray(1)
            GLES20.glGenBuffers(1, ids, 0)
            mesh.handle = ids[0]
            val buf = ByteBuffer.allocateDirect(mesh.data.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buf.put(mesh.data).position(0)
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.handle)
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, mesh.data.size * 4, buf, GLES20.GL_STATIC_DRAW)
            uploaded += mesh
        } else {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, mesh.handle)
        }
        val stride = Mesh.STRIDE * 4
        GLES20.glVertexAttribPointer(aPos, 3, GLES20.GL_FLOAT, false, stride, 0)
        GLES20.glVertexAttribPointer(aNormal, 3, GLES20.GL_FLOAT, false, stride, 12)
        GLES20.glVertexAttribPointer(aColor, 4, GLES20.GL_FLOAT, false, stride, 24)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, stride, 40)
    }

    /** Deletes the GL buffers of meshes the engine no longer needs (old levels and so on). */
    private fun freeReleased() {
        val dead = synchronized(Mesh.releasedQueue) {
            if (Mesh.releasedQueue.isEmpty()) return
            val copy = ArrayList(Mesh.releasedQueue)
            Mesh.releasedQueue.clear()
            copy
        }
        val ids = IntArray(1)
        for (m in dead) {
            if (m.handle != 0) {
                ids[0] = m.handle
                GLES20.glDeleteBuffers(1, ids, 0)
                m.handle = 0
            }
            uploaded.remove(m)
        }
    }

    private companion object {
        const val VERTEX = """
            uniform mat4 uViewProj;
            uniform mat4 uModel;
            uniform mat3 uNormal;
            attribute vec3 aPos;
            attribute vec3 aNormal;
            attribute vec4 aColor;
            attribute vec2 aUv;
            varying vec3 vNormal;
            varying vec3 vWorld;
            varying vec4 vColor;
            varying vec2 vUv;
            void main() {
                vec4 w = uModel * vec4(aPos, 1.0);
                vWorld = w.xyz;
                vNormal = uNormal * aNormal;
                vColor = aColor;
                vUv = aUv;
                gl_Position = uViewProj * w;
            }
        """

        const val FRAGMENT = """
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            uniform sampler2D uTex;
            uniform vec4 uTint;
            uniform float uEmissive;
            uniform float uShine;
            uniform vec3 uLight;
            uniform vec3 uSun;
            uniform vec3 uSkyAmb;
            uniform vec3 uGroundAmb;
            uniform vec3 uCam;
            uniform vec3 uFog;
            uniform vec2 uFogRange;
            uniform float uCutout;
            varying vec3 vNormal;
            varying vec3 vWorld;
            varying vec4 vColor;
            varying vec2 vUv;
            void main() {
                vec4 base = texture2D(uTex, vUv) * vColor * uTint;
                if (uCutout > 0.5 && base.a < 0.5) discard;
                vec3 n = normalize(vNormal);
                vec3 toCam = uCam - vWorld;
                float dist = length(toCam);
                vec3 v = toCam / dist;
                float diff = max(dot(n, uLight), 0.0);
                vec3 amb = mix(uGroundAmb, uSkyAmb, n.y * 0.5 + 0.5);
                vec3 h = normalize(uLight + v);
                float spec = pow(max(dot(n, h), 0.0), 28.0) * uShine;
                float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * 0.25;
                vec3 c = base.rgb * (amb * 0.75 + diff * uSun) + vec3(spec) + rim * uSkyAmb;
                c = mix(c, base.rgb, uEmissive);
                float f = clamp((dist - uFogRange.x) / (uFogRange.y - uFogRange.x), 0.0, 1.0) * 0.85;
                f *= 1.0 - step(0.99, uEmissive);
                gl_FragColor = vec4(mix(c, uFog, f), base.a);
            }
        """
    }
}

/** Small GL helpers shared by the scene and the overlay. */
object Gl {
    fun program(vs: String, fs: String): Int {
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) error("Shader link failed: " + GLES20.glGetProgramInfoLog(p))
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) error("Shader compile failed: " + GLES20.glGetShaderInfoLog(s))
        return s
    }
}
