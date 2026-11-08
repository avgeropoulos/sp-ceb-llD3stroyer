package com.goalrush

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.goalrush.game.Game
import com.goalrush.game.Storage
import com.goalrush.game.VIEW_W

/** Runs the game loop on its own thread and feeds it touches. */
@SuppressLint("ViewConstructor")
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private val prefs = context.getSharedPreferences("goalrush", Context.MODE_PRIVATE)
    private val sound = SoundFx(context)
    private val updater = AppUpdater(context)
    private val game = Game(sound, object : Storage {
        override fun getInt(key: String, def: Int) = prefs.getInt(key, def)
        override fun putInt(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
        override fun getString(key: String, def: String) = prefs.getString(key, def) ?: def
        override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    }, updater)
    private val gfx = CanvasGfx()

    @Volatile private var running = false
    private var resumed = false
    private var surfaceReady = false
    private var thread: Thread? = null

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
        stopLoop()
        game.onPause()
        sound.setForeground(false)
    }

    fun back(): Boolean = game.back()

    fun release() {
        sound.release()
        updater.release()
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

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stopLoop()
    }

    override fun run() {
        val frameNanos = 1_000_000_000L / 60L
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceIn(0f, 1f / 20f)
            last = now
            sound.enabled = game.save.soundOn
            game.update(dt)
            val canvas = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.lockHardwareCanvas() else holder.lockCanvas()
            } catch (_: Exception) { null }
            if (canvas == null) {
                Thread.sleep(16)
                continue
            }
            try {
                val scale = canvas.width / VIEW_W
                val h = canvas.height / scale
                if (h != game.viewH) game.viewH = h
                gfx.begin(canvas, scale)
                game.render(gfx)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            val remaining = frameNanos - (System.nanoTime() - now)
            if (remaining > 0) Thread.sleep(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val scale = width / VIEW_W
        if (scale <= 0f) return true
        // Times relative to the start of the gesture keep full float precision.
        val t = (e.eventTime - e.downTime) / 1000f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> game.touch(Game.DOWN, e.x / scale, e.y / scale, t)
            MotionEvent.ACTION_MOVE -> {
                // Feed in the in-between samples too, so fast flicks keep their shape.
                for (i in 0 until e.historySize) {
                    game.touch(Game.MOVE, e.getHistoricalX(0, i) / scale, e.getHistoricalY(0, i) / scale, (e.getHistoricalEventTime(i) - e.downTime) / 1000f)
                }
                game.touch(Game.MOVE, e.x / scale, e.y / scale, t)
            }
            MotionEvent.ACTION_UP -> game.touch(Game.UP, e.x / scale, e.y / scale, t)
            MotionEvent.ACTION_CANCEL -> game.touch(Game.CANCEL, 0f, 0f, t)
        }
        return true
    }
}
