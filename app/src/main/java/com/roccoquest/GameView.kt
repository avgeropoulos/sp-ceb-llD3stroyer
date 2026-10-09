package com.roccoquest

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.roccoquest.game.Controls
import com.roccoquest.game.Game
import com.roccoquest.game.Input
import com.roccoquest.game.VIEW_H

/** Hosts the game loop on its own thread and feeds it touch and key input. */
@SuppressLint("ViewConstructor")
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private val input = Input()
    private val sound = SoundFx(context)
    private val game = Game(input, sound)
    private val gfx = CanvasGfx()

    @Volatile private var running = false
    private var resumed = false
    private var surfaceReady = false
    private var thread: Thread? = null

    private val touchX = FloatArray(10)
    private val touchY = FloatArray(10)

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun resume() {
        resumed = true
        startLoop()
    }

    fun pause() {
        resumed = false
        stopLoop()
    }

    fun release() = sound.release()

    private fun startLoop() {
        if (!resumed || !surfaceReady || running) return
        running = true
        thread = Thread(this, "game-loop").also { it.start() }
    }

    private fun stopLoop() {
        running = false
        thread?.let {
            try { it.join(500) } catch (_: InterruptedException) {}
        }
        thread = null
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        startLoop()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (height > 0) game.viewW = width * VIEW_H / height
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stopLoop()
    }

    override fun run() {
        val step = 1f / 60f
        var last = System.nanoTime()
        var acc = 0f
        while (running) {
            val now = System.nanoTime()
            acc += ((now - last) / 1e9f).coerceAtMost(0.25f)
            last = now
            while (acc >= step) {
                game.update(step)
                acc -= step
            }
            val canvas = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.lockHardwareCanvas() else holder.lockCanvas()
            } catch (_: Exception) { null }
            if (canvas == null) {
                Thread.sleep(16)
                continue
            }
            try {
                val scale = canvas.height / VIEW_H
                game.viewW = canvas.width / scale
                gfx.begin(canvas, scale)
                game.render(gfx)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            val elapsed = (System.nanoTime() - now) / 1_000_000L
            if (elapsed < 8) Thread.sleep(8 - elapsed)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val scale = height / VIEW_H
        if (scale <= 0f) return true
        val action = e.actionMasked
        var n = 0
        if (action != MotionEvent.ACTION_CANCEL && action != MotionEvent.ACTION_UP) {
            for (i in 0 until e.pointerCount) {
                if (action == MotionEvent.ACTION_POINTER_UP && i == e.actionIndex) continue
                if (n >= touchX.size) break
                touchX[n] = e.getX(i) / scale
                touchY[n] = e.getY(i) / scale
                n++
            }
        }
        Controls.apply(input, touchX, touchY, n, game.viewW)
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) input.tap = true
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!setKey(keyCode, true)) return super.onKeyDown(keyCode, event)
        input.tap = true
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        setKey(keyCode, false) || super.onKeyUp(keyCode, event)

    /** Keyboard / game-controller support. */
    private fun setKey(keyCode: Int, down: Boolean): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> input.left = down
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> input.right = down
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> input.jump = down
            KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_CTRL_LEFT -> input.fire = down
            KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ESCAPE -> input.pause = down
            else -> return false
        }
        return true
    }
}
