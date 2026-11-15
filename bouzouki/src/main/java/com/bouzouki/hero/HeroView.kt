package com.bouzouki.hero

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.bouzouki.ScreenTiming
import com.bouzoukihero.core.DisplayLead
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

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val verts = FloatArray(8)
    private val vertColors = IntArray(4)

    /** A real line: much cheaper for the GPU than a shape outlined as a path. */
    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int) {
        linePaint.color = color
        linePaint.strokeWidth = width * s
        canvas.drawLine(x1 * s, y1 * s, x2 * s, y2 * s, linePaint)
    }

    override fun poly(xs: FloatArray, ys: FloatArray, color: Int) {
        if (xs.size in 3..4 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Triangles and quads (nearly every shape in the game) as vertices: a path is
            // rasterized again every frame, which made the game stutter. (The GPU canvas
            // draws vertices from Android 10; older phones keep using paths.)
            for (i in xs.indices) {
                verts[2 * i] = xs[i] * s
                verts[2 * i + 1] = ys[i] * s
                vertColors[i] = color
            }
            paint.color = color
            canvas.drawVertices(
                Canvas.VertexMode.TRIANGLE_FAN, xs.size * 2, verts, 0, null, 0,
                vertColors, 0, null, 0, 0, paint,
            )
            return
        }
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
class HeroView(context: Context, private val host: Host) : SurfaceView(context), SurfaceHolder.Callback {
    interface Host {
        val session: Session
        val clock: SongClock?
        /** Seconds to subtract from the clock for audio output delay. */
        val latency: Double
        fun onAction(action: Session.Action)
    }

    private val gfx = CanvasGfx()
    private var surfaceReady = false
    private var resumed = false
    private var loop: Loop? = null
    private val pointerLane = HashMap<Int, Int>()

    /** How many times a second the screen refreshes: how far ahead the notes are drawn follows from it. */
    @Volatile private var refreshHz = DisplayLead.DEFAULT_HZ

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
        if (!resumed || !surfaceReady || loop != null) return
        readRefreshRate()
        loop = Loop().also { it.start() }
    }

    private fun stopLoop() {
        val l = loop ?: return
        loop = null
        l.end()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        startLoop()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        readRefreshRate()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        stopLoop()
    }

    /** Asks the screen how fast it refreshes (60 if it won't say). Safe from either thread. */
    private fun readRefreshRate(): Float {
        val hz = ScreenTiming.refreshRate(try { display } catch (_: Exception) { null })
        refreshHz = hz
        return hz
    }

    /** The song time right now. */
    fun songTime(): Double {
        val c = host.clock ?: return host.session.judgedTime
        return c.now() - host.latency
    }

    /**
     * The song time when a touch or key happened, not when it got here: events wait their turn
     * behind whatever the app is doing, and a tap is judged by when the finger went down.
     * [eventMillis] is the event's own time stamp; [inputDelay] is what its device takes to
     * notice it before stamping it.
     */
    private fun songTimeOf(eventMillis: Long, inputDelay: Double): Double {
        val age = (SystemClock.uptimeMillis() - eventMillis) / 1000.0
        // A stamp that can't be right (made-up events carry none) is left out: judged on arrival, as before.
        return songTime() - (if (age in 0.0..MAX_EVENT_AGE) age else 0.0) - inputDelay
    }

    /**
     * The game loop's thread. It draws exactly one frame for every frame the screen shows, woken
     * by the screen's own tick (vsync) through a Choreographer of its own. Drawing as fast as it
     * can, as it used to, made frames queue up behind the screen, each one seen a refresh later.
     */
    private inner class Loop : HandlerThread("hero-loop"), Choreographer.FrameCallback {
        /** False once the loop is told to end: nothing is drawn or asked for after that. */
        @Volatile private var live = true
        private var choreographer: Choreographer? = null
        private var timer: Handler? = null
        private var lastVsync = 0L
        private var frames = 0
        private var worst = 0f
        private var perfSince = 0L

        /** Runs on this thread once it can take callbacks; the Choreographer is this thread's own. */
        override fun onLooperPrepared() {
            choreographer = try { Choreographer.getInstance() } catch (_: Exception) { null }
            // No vsync to be had (never seen, but the game must still draw): a plain timer instead.
            if (choreographer == null) timer = Looper.myLooper()?.let { Handler(it) }
            perfSince = System.nanoTime()
            next()
        }

        private val tick = Runnable { doFrame(System.nanoTime()) }

        /** Asks for the next frame; only ever one at a time, so frames can't pile up. */
        private fun next() {
            if (!live) return
            val c = choreographer
            if (c != null) c.postFrameCallback(this)
            else timer?.postDelayed(tick, (1000f / refreshHz).toLong().coerceIn(4L, 50L))
        }

        /** [frameTimeNanos] is the vsync this frame is drawn on, on System.nanoTime()'s clock. */
        override fun doFrame(frameTimeNanos: Long) {
            if (!live) return
            draw(frameTimeNanos)
            next()
        }

        /** Stops the loop and waits for the frame in hand, so nothing touches the surface afterwards. */
        fun end() {
            live = false
            quit() // drops the callbacks still waiting; the thread then ends by itself
            try { join(500) } catch (_: InterruptedException) {}
        }

        private fun draw(vsync: Long) {
            val now = System.nanoTime()
            val dt = if (lastVsync == 0L) 0f else ((vsync - lastVsync) / 1e9f).coerceIn(0f, 0.1f)
            lastVsync = vsync
            val s = host.session
            val clock = host.clock
            if (s.state == Session.State.PLAYING && clock != null) {
                // This frame reaches the eye a fixed time after its vsync (the lead), and some of
                // that has gone by already. Draw the song as it will be at that moment: the
                // clock's reading now, plus what's left. Misses and holds go by the song as it is now.
                val lead = DisplayLead.seconds(refreshHz)
                val ahead = (lead - (now - vsync) / 1e9).coerceIn(0.0, lead)
                val t = songTime()
                s.update(t + ahead, t, dt)
                if (clock.ended) s.finish()
            } else {
                s.update(s.time, s.judgedTime, dt)
            }
            val canvas = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.lockHardwareCanvas() else holder.lockCanvas()
            } catch (_: Exception) { null }
            if (canvas == null) return // nothing to draw on just now: skip this frame
            try {
                val scale = canvas.width / VIEW_W
                s.viewH = canvas.height / scale
                gfx.begin(canvas, scale)
                s.render(gfx)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            // Smoothness: frames per second and the slowest frame, shown in the pause menu with
            // the screen's rate and how far ahead the notes are drawn for it.
            frames++
            worst = maxOf(worst, dt)
            if (now - perfSince > 1_000_000_000L) {
                val fps = Math.round(frames * 1e9 / (now - perfSince))
                val hz = readRefreshRate() // the screen can change its rate while the game is up
                s.perfText = "Smoothness: $fps fps, slowest frame ${(worst * 1000).toInt()} ms\n" +
                    "Screen ${Math.round(hz)} Hz, notes drawn ${Math.round(DisplayLead.seconds(hz) * 1000)} ms ahead"
                frames = 0
                worst = 0f
                perfSince = now
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val scale = width / VIEW_W
        if (scale <= 0f) return true
        val s = host.session
        val t = songTimeOf(e.eventTime, TOUCH_DELAY)
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
        if (event.repeatCount == 0) host.session.press(lane, songTimeOf(event.eventTime, 0.0))
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val lane = laneForKey(keyCode)
        if (lane < 0) return super.onKeyUp(keyCode, event)
        host.session.release(lane, songTimeOf(event.eventTime, 0.0))
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

    private companion object {
        /**
         * Seconds a touch panel takes to notice a finger (it scans for touches a hundred or so
         * times a second) before the touch gets its time stamp. Taken off the time a tap is
         * judged at; nothing to do with drawing.
         */
        const val TOUCH_DELAY = 0.010

        /** No event waits this long (seconds) to be handled: an older time stamp isn't believed. */
        const val MAX_EVENT_AGE = 0.25
    }
}
