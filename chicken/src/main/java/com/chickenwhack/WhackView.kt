package com.chickenwhack

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.roccoquest.chicken.WHACK_W
import com.roccoquest.chicken.WhackGame
import com.roccoquest.game.Storage

/** Runs the game loop on its own thread and turns every finger press into a whack. */
@SuppressLint("ViewConstructor")
class WhackView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private val sound = WhackSounds(context)
    private val prefs = context.getSharedPreferences("chicken_whack", Context.MODE_PRIVATE)
    private val game = WhackGame(sound, object : Storage {
        override fun load(key: String) = prefs.getInt(key, 0)
        override fun save(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    })
    private val gfx = CanvasGfx()

    @Volatile private var running = false
    private var resumed = false
    private var surfaceReady = false
    private var thread: Thread? = null

    init {
        holder.addCallback(this)
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
        if (width > 0) game.viewH = height * WHACK_W / width
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
                val scale = canvas.width / WHACK_W
                game.viewH = canvas.height / scale
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
        val scale = width / WHACK_W
        if (scale <= 0f) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                game.tap(e.getX(i) / scale, e.getY(i) / scale)
            }
        }
        return true
    }
}
