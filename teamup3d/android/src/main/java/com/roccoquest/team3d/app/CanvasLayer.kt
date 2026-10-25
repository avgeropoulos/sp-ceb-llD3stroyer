package com.roccoquest.team3d.app

import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A full-screen picture painted with Android's normal (GPU-accelerated) Canvas and shown
 * as an OpenGL texture. Team Up!'s own 2D drawing code paints the sky behind the 3D level
 * and the HUD, buttons and menus in front of it, so they look exactly like Team Up!.
 */
class CanvasLayer {
    private var texture = 0
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null
    private val texMatrix = FloatArray(16)
    var width = 0
        private set
    var height = 0
        private set

    /** Must be called on the GL thread, with a current GL context. */
    fun create(w: Int, h: Int) {
        release()
        width = w
        height = h
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texture = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        val st = SurfaceTexture(texture)
        st.setDefaultBufferSize(w, h)
        surfaceTexture = st
        surface = Surface(st)
    }

    /** Paints a new picture (cleared to see-through first). */
    fun paint(block: (Canvas) -> Unit) {
        val s = surface ?: return
        val c = try { s.lockHardwareCanvas() } catch (_: Exception) { null } ?: return
        try {
            c.drawColor(0, PorterDuff.Mode.CLEAR)
            block(c)
        } finally {
            s.unlockCanvasAndPost(c)
        }
    }

    /** Shows the newest painted picture over the whole screen. */
    fun draw(blend: Boolean) {
        val st = surfaceTexture ?: return
        try { st.updateTexImage() } catch (_: Exception) { return }
        st.getTransformMatrix(texMatrix)
        if (program == 0) setUp()
        GLES20.glUseProgram(program)
        GLES20.glDisable(GLES20.GL_DEPTH_TEST)
        if (blend) {
            // Canvas pictures have premultiplied alpha.
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        } else {
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniform1i(uTex, 0)
        GLES20.glUniformMatrix4fv(uTexMatrix, 1, false, texMatrix, 0)
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aUv, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aUv)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(aPos)
        GLES20.glDisableVertexAttribArray(aUv)
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    fun release() {
        surface?.release()
        surfaceTexture?.release()
        surface = null
        surfaceTexture = null
        if (texture != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
            texture = 0
        }
    }

    /** Forgets GL objects after the GL context was lost (they are gone already). */
    fun contextLost() {
        try { surface?.release() } catch (_: Exception) {}
        try { surfaceTexture?.release() } catch (_: Exception) {}
        surface = null
        surfaceTexture = null
        texture = 0
        program = 0
    }

    private var program = 0
    private var aPos = 0
    private var aUv = 0
    private var uTex = 0
    private var uTexMatrix = 0

    private fun setUp() {
        program = Gl.program(VERTEX, FRAGMENT)
        aPos = GLES20.glGetAttribLocation(program, "aPos")
        aUv = GLES20.glGetAttribLocation(program, "aUv")
        uTex = GLES20.glGetUniformLocation(program, "uTex")
        uTexMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
    }

    private companion object {
        /** A full-screen strip: x, y, u, v. Texture v = 0 is the bottom of the picture. */
        val quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f))
            position(0)
        }

        const val VERTEX = """
            uniform mat4 uTexMatrix;
            attribute vec2 aPos;
            attribute vec2 aUv;
            varying vec2 vUv;
            void main() {
                vUv = (uTexMatrix * vec4(aUv, 0.0, 1.0)).xy;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
        """

        const val FRAGMENT = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES uTex;
            varying vec2 vUv;
            void main() {
                gl_FragColor = texture2D(uTex, vUv);
            }
        """
    }
}
