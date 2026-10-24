package com.bouzouki.hero

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.bouzoukihero.core.Gfx
import com.bouzoukihero.core.Session
import com.bouzoukihero.core.VIEW_W

/** [Gfx] backed by an Android [Canvas]; scales virtual pixels up to the screen. */
class CanvasGfx : Gfx {
    private lateinit var canvas: Canvas
    private var s = 1f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val rectF = RectF()
    private val path = Path()

    fun begin(canvas: Canvas, scale: Float) {
        this.canvas = canvas
        s = scale
    }

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) {
        paint.color = color
        canvas.drawRect(x * s, y * s, (x + w) * s, (y + h) * s, paint)
    }

    override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) {
        paint.color = color
        rectF.set(x * s, y * s, (x + w) * s, (y + h) * s)
        canvas.drawOval(rectF, paint)
    }

    override fun poly(xs: FloatArray, ys: FloatArray, color: Int) {
        path.reset()
        path.moveTo(xs[0] * s, ys[0] * s)
        for (i in 1 until xs.size) path.lineTo(xs[i] * s, ys[i] * s)
        path.close()
        paint.color = color
        canvas.drawPath(path, paint)
    }

    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) {
        textPaint.color = color
        textPaint.textSize = size * this.s
        textPaint.textAlign = when (align) {
            1 -> Paint.Align.CENTER
            2 -> Paint.Align.RIGHT
            else -> Paint.Align.LEFT
        }
        canvas.drawText(s, x * this.s, y * this.s, textPaint)
    }
}

/**
 * Runs the game loop on its own thread and turns touches into button presses.
 * Each finger is tracked separately, so chords and sliding between buttons work.
 */
@SuppressLint("ViewConstructor")
class HeroView(context: Context, private val host: Host) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    interface Host {
        val session: Session
        val clock: SongClock?
        /** Seconds to subtract from the clock for audio output delay. */
        val latency: Double
        fun onAction(action: Session.Action)
    }

    private val gfx = CanvasGfx()
    @Volatile private var running = false
    private var surfaceReady = false
    private var resumed = false
    private var thread: Thread? = null
    private val pointerLane = HashMap<Int, Int>()

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

    private fun startLoop() {
        if (!resumed || !surfaceReady || running) return
        running = true
        thread = Thread(this, "hero-loop").also { it.start() }
    }

    private fun stopLoop() {
        running = false
        thread?.let { try { it.join(500) } catch (_: InterruptedException) {} }
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

    fun songTime(): Double {
        val c = host.clock ?: return host.session.time
        return c.now() - host.latency
    }

    override fun run() {
        var last = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceAtMost(0.1f)
            last = now
            val s = host.session
            val clock = host.clock
            if (s.state == Session.State.PLAYING && clock != null) {
                s.update(songTime(), dt)
                if (clock.ended) s.finish()
            } else {
                s.update(s.time, dt)
            }
            val canvas = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.lockHardwareCanvas() else holder.lockCanvas()
            } catch (_: Exception) { null }
            if (canvas == null) {
                Thread.sleep(16)
                continue
            }
            try {
                val scale = canvas.width / VIEW_W
                s.viewH = canvas.height / scale
                gfx.begin(canvas, scale)
                s.render(gfx)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            val elapsed = (System.nanoTime() - now) / 1_000_000L
            if (elapsed < 6) Thread.sleep(6 - elapsed)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val scale = width / VIEW_W
        if (scale <= 0f) return true
        val s = host.session
        val t = songTime()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                val x = e.getX(i) / scale
                val y = e.getY(i) / scale
                val action = s.tap(x, y)
                if (action != Session.Action.NONE) {
                    host.onAction(action)
                } else {
                    val lane = s.laneAt(x, y)
                    if (lane >= 0) {
                        pointerLane[e.getPointerId(i)] = lane
                        s.press(lane, t)
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // Sliding a finger onto the next button presses it.
                for (i in 0 until e.pointerCount) {
                    val id = e.getPointerId(i)
                    val old = pointerLane[id] ?: continue
                    val lane = s.laneAt(e.getX(i) / scale, e.getY(i) / scale)
                    if (lane >= 0 && lane != old) {
                        s.release(old, t)
                        pointerLane[id] = lane
                        s.press(lane, t)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                pointerLane.remove(id)?.let { lane ->
                    if (lane !in pointerLane.values) s.release(lane, t)
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                pointerLane.clear()
                s.releaseAll(t)
            }
        }
        return true
    }

    /** Keyboards and controllers: 1-5 or the F-row keys are the buttons. */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val lane = laneForKey(keyCode)
        if (lane < 0) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) host.session.press(lane, songTime())
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val lane = laneForKey(keyCode)
        if (lane < 0) return super.onKeyUp(keyCode, event)
        host.session.release(lane, songTime())
        return true
    }

    private fun laneForKey(k: Int) = when (k) {
        KeyEvent.KEYCODE_1, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_F1 -> 0
        KeyEvent.KEYCODE_2, KeyEvent.KEYCODE_S, KeyEvent.KEYCODE_F2 -> 1
        KeyEvent.KEYCODE_3, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_F3 -> 2
        KeyEvent.KEYCODE_4, KeyEvent.KEYCODE_F, KeyEvent.KEYCODE_F4 -> 3
        KeyEvent.KEYCODE_5, KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_F5 -> 4
        else -> -1
    }
}
