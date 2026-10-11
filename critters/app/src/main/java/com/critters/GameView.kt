package com.critters

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.critters.game.Btn
import com.critters.game.Game
import com.critters.game.SaveStore
import com.critters.game.Shell
import kotlin.math.floor
import kotlin.math.min

/** Runs the game loop on its own thread and turns touches and keys into button presses. */
@SuppressLint("ViewConstructor")
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private val sound = SoundFx(context)
    private val prefs = context.getSharedPreferences("critters", Context.MODE_PRIVATE)
    private val game = Game(audio = sound, store = object : SaveStore {
        override fun read(): String? = prefs.getString("save", null)
        override fun write(data: String) { prefs.edit().putString("save", data).apply() }
    })
    private val draw = CanvasDraw()

    @Volatile private var running = false
    private var resumed = false
    private var surfaceReady = false
    private var thread: Thread? = null

    @Volatile private var scale = 1f
    @Volatile private var viewW = Shell.MIN_W
    @Volatile private var viewH = Shell.MIN_H

    private val touchX = FloatArray(10)
    private val touchY = FloatArray(10)

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun resume() {
        resumed = true
        sound.setForeground(true)
        startLoop()
    }

    fun pause() {
        resumed = false
        sound.setForeground(false)
        stopLoop()
        game.autosave()
    }

    fun release() = sound.release()

    /** The phone's back button works as B. */
    fun tapB() {
        game.pad.key(Btn.B, true)
        postDelayed({ game.pad.key(Btn.B, false) }, 90)
    }

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
        fitToSurface(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stopLoop()
    }

    /** Picks a whole-number scale when possible so every game pixel is the same size. */
    private fun fitToSurface(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        val fit = min(width / Shell.MIN_W, height / Shell.MIN_H)
        scale = if (fit >= 2f) floor(fit) else fit
        viewW = width / scale
        viewH = height / scale
    }

    override fun run() {
        val step = 1_000_000_000L / 60
        var last = System.nanoTime()
        var acc = 0L
        while (running) {
            val now = System.nanoTime()
            acc += (now - last).coerceAtMost(250_000_000L)
            last = now
            var updates = 0
            while (acc >= step && updates < 4) {
                synchronized(game) { game.update() }
                acc -= step
                updates++
            }
            if (updates == 4) acc = 0
            val canvas = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.lockHardwareCanvas() else holder.lockCanvas()
            } catch (_: Exception) { null }
            if (canvas == null) {
                Thread.sleep(16)
                continue
            }
            try {
                fitToSurface(canvas.width, canvas.height)
                draw.begin(canvas, scale)
                synchronized(game) { game.draw(draw, viewW, viewH) }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            val elapsed = (System.nanoTime() - now) / 1_000_000L
            if (elapsed < 6) Thread.sleep(6 - elapsed)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
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
        val before = game.pad.touchBits
        val bits = Shell.touch(touchX, touchY, n, viewW, viewH)
        game.pad.touchBits = bits
        // A little buzz when a new button goes down, like pressing a real one.
        if (bits and before.inv() != 0) performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
        return true
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        setKey(keyCode, true) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        setKey(keyCode, false) || super.onKeyUp(keyCode, event)

    /** Keyboard and game controller support. */
    private fun setKey(keyCode: Int, down: Boolean): Boolean {
        val b = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> Btn.UP
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> Btn.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> Btn.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> Btn.RIGHT
            KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_J,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> Btn.A
            KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_BUTTON_B -> Btn.B
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_BUTTON_START -> Btn.START
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT, KeyEvent.KEYCODE_BUTTON_SELECT -> Btn.SELECT
            else -> return false
        }
        game.pad.key(b, down)
        return true
    }
}
