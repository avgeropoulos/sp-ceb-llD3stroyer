package com.spotless

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.spotless.game.Game
import com.spotless.game.Storage
import com.spotless.game.TOUCH_DOWN
import com.spotless.game.TOUCH_MOVE
import com.spotless.game.TOUCH_UP
import com.spotless.game.VH
import com.spotless.game.VW

/** Hosts the game loop on its own thread and feeds it touches. */
@SuppressLint("ViewConstructor")
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private val sound = SoundFx(context)
    private val prefs = context.getSharedPreferences("spotless", Context.MODE_PRIVATE)
    private val game = Game(sound, object : Storage {
        override fun load(key: String) = prefs.getInt(key, 0)
        override fun save(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    })
    private val painter = CanvasPainter()

    @Volatile private var running = false
    private var resumed = false
    private var surfaceReady = false
    private var thread: Thread? = null

    // Virtual-to-screen mapping: the 720x1280 game is scaled to fit and centred.
    @Volatile private var scale = 1f
    @Volatile private var offX = 0f
    @Volatile private var offY = 0f

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

    /** The back button. Returns false when the app should close. */
    fun back(): Boolean = synchronized(game) { game.back() }

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
        layout(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stopLoop()
    }

    private fun layout(w: Int, h: Int) {
        if (w <= 0 || h <= 0) return
        scale = minOf(w / VW, h / VH)
        offX = (w - VW * scale) / 2f
        offY = (h - VH * scale) / 2f
    }

    override fun run() {
        val step = 1f / 60f
        var last = System.nanoTime()
        var acc = 0f
        while (running) {
            val now = System.nanoTime()
            acc += ((now - last) / 1e9f).coerceAtMost(0.25f)
            last = now
            synchronized(game) {
                while (acc >= step) {
                    game.update(step)
                    acc -= step
                }
            }
            val canvas = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.lockHardwareCanvas() else holder.lockCanvas()
            } catch (_: Exception) { null }
            if (canvas == null) {
                Thread.sleep(16)
                continue
            }
            try {
                layout(canvas.width, canvas.height)
                // Fill the letterbox bars with a soft pink.
                canvas.drawColor(0xFFFFE4EE.toInt())
                canvas.save()
                canvas.translate(offX, offY)
                canvas.scale(scale, scale)
                canvas.clipRect(0f, 0f, VW, VH)
                painter.begin(canvas)
                synchronized(game) { game.render(painter) }
                canvas.restore()
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            val elapsed = (System.nanoTime() - now) / 1_000_000L
            if (elapsed < 8) Thread.sleep(8 - elapsed)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (scale <= 0f) return true
        // Only the first finger cleans; extra fingers are ignored.
        val x = (e.getX(0) - offX) / scale
        val y = (e.getY(0) - offY) / scale
        val action = when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> TOUCH_DOWN
            MotionEvent.ACTION_MOVE -> TOUCH_MOVE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> TOUCH_UP
            else -> return true
        }
        synchronized(game) {
            if (action == TOUCH_MOVE) {
                // Replay the in-between points so fast swipes still scrub everything they cross.
                for (h in 0 until e.historySize) {
                    game.touch(TOUCH_MOVE, (e.getHistoricalX(0, h) - offX) / scale, (e.getHistoricalY(0, h) - offY) / scale)
                }
            }
            game.touch(action, x, y)
        }
        return true
    }
}
