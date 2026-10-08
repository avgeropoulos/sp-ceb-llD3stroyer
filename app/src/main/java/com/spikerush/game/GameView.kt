package com.spikerush.game

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.min

/** Hosts the game loop thread, scales the 1280x720 world to the screen and maps touches to buttons. */
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    private val synth = Synth()
    private val game = Game(synth, context.getSharedPreferences("spike_rush", Context.MODE_PRIVATE))

    @Volatile private var running = false
    private var loopThread: Thread? = null
    private var surfaceReady = false
    private var resumed = false

    // Active touch pointers: id -> (x, y). Only touched on the UI thread, read as a snapshot.
    private val touches = HashMap<Int, FloatArray>()
    private var keyLeft = false
    private var keyRight = false
    private var keyJump = false
    private var keyAction = false

    private val btnFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val btnRing = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val arrow = Path()

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
    }

    fun resume() {
        resumed = true
        synth.start()
        startLoop()
    }

    fun pause() {
        resumed = false
        game.onPause()
        stopLoop()
        synth.stop()
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

    private fun startLoop() {
        if (running || !surfaceReady || !resumed) return
        running = true
        loopThread = Thread({ runLoop() }, "game-loop").also { it.start() }
    }

    private fun stopLoop() {
        running = false
        loopThread?.join(1000)
        loopThread = null
    }

    private fun runLoop() {
        val step = 1f / 120f
        var last = System.nanoTime()
        var acc = 0f
        while (running) {
            val now = System.nanoTime()
            acc += min((now - last) / 1e9f, 0.1f)
            last = now
            while (acc >= step) {
                game.update(step)
                acc -= step
            }
            val c: Canvas = try {
                (if (Build.VERSION.SDK_INT >= 26) holder.lockHardwareCanvas() else holder.lockCanvas()) ?: continue
            } catch (e: Exception) {
                continue
            }
            try {
                drawFrame(c)
            } finally {
                holder.unlockCanvasAndPost(c)
            }
        }
    }

    private fun layout(w: Int, h: Int): FloatArray {
        val scale = min(w / WORLD_W, h / WORLD_H)
        return floatArrayOf(scale, (w - WORLD_W * scale) / 2f, (h - WORLD_H * scale) / 2f)
    }

    private fun buttonRadius(w: Int, h: Int) = min(w, h) * 0.105f
    private fun leftCenterX(r: Float) = r * 1.35f
    private fun rightCenterX(r: Float) = r * 3.75f
    private fun actionCenterX(w: Int, r: Float) = w - r * 3.9f

    private fun drawFrame(c: Canvas) {
        val w = c.width
        val h = c.height
        c.drawColor(Color.rgb(15, 6, 22))
        val (scale, ox, oy) = layout(w, h)
        c.save()
        c.translate(ox, oy)
        c.scale(scale, scale)
        c.clipRect(0f, 0f, WORLD_W, WORLD_H)
        game.render(c)
        c.restore()

        if (game.showControls) {
            val r = buttonRadius(w, h)
            val cy = h - r * 1.35f
            drawButton(c, leftCenterX(r), cy, r, game.inLeft, 0)
            drawButton(c, rightCenterX(r), cy, r, game.inRight, 1)
            drawButton(c, w - r * 1.45f, cy, r * 1.1f, game.inJump, 2)
            if (game.actionAvailable) drawButton(c, actionCenterX(w, r), cy, r * 0.9f, game.inAction, 3)
        }
    }

    private fun drawButton(c: Canvas, x: Float, y: Float, r: Float, pressed: Boolean, kind: Int) {
        btnFill.color = if (pressed) Color.argb(150, 255, 255, 255) else Color.argb(70, 255, 255, 255)
        c.drawCircle(x, y, r, btnFill)
        btnRing.color = Color.argb(160, 255, 255, 255)
        btnRing.strokeWidth = r * 0.06f
        c.drawCircle(x, y, r, btnRing)
        btnFill.color = Color.argb(220, 40, 20, 50)
        arrow.reset()
        val s = r * 0.42f
        when (kind) {
            0 -> { arrow.moveTo(x - s, y); arrow.lineTo(x + s * 0.6f, y - s); arrow.lineTo(x + s * 0.6f, y + s) }
            1 -> { arrow.moveTo(x + s, y); arrow.lineTo(x - s * 0.6f, y - s); arrow.lineTo(x - s * 0.6f, y + s) }
            2 -> { arrow.moveTo(x, y - s); arrow.lineTo(x + s, y + s * 0.6f); arrow.lineTo(x - s, y + s * 0.6f) }
            else -> {
                // action button: a burst/star shape
                for (i in 0 until 10) {
                    val a = -1.5708f + i * 0.6283f
                    val rr = if (i % 2 == 0) s * 1.1f else s * 0.5f
                    val px = x + kotlin.math.cos(a) * rr
                    val py = y + kotlin.math.sin(a) * rr
                    if (i == 0) arrow.moveTo(px, py) else arrow.lineTo(px, py)
                }
            }
        }
        arrow.close()
        c.drawPath(arrow, btnFill)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                touches[e.getPointerId(i)] = floatArrayOf(e.getX(i), e.getY(i))
                // Remember where the tap landed in world coordinates (for on-screen buttons).
                val (scale, ox, oy) = layout(width, height)
                game.tapX = (e.getX(i) - ox) / scale
                game.tapY = (e.getY(i) - oy) / scale
                game.tapped = true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.pointerCount) {
                    touches[e.getPointerId(i)]?.let { it[0] = e.getX(i); it[1] = e.getY(i) }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> touches.clear()
            MotionEvent.ACTION_POINTER_UP -> touches.remove(e.getPointerId(e.actionIndex))
        }
        updateInput()
        return true
    }

    /** Left half of the screen = left/right pad (split between the two buttons), right half = jump. */
    private fun updateInput() {
        var l = keyLeft
        var r = keyRight
        var j = keyJump
        var a = keyAction
        if (width > 0) {
            val rad = buttonRadius(width, height)
            val split = (leftCenterX(rad) + rightCenterX(rad)) / 2f
            // With a power-up, the right half splits: inner part = action, outer part = jump.
            val actionSplit = if (game.actionAvailable) (actionCenterX(width, rad) + width - rad * 1.45f) / 2f else -1f
            for (t in touches.values) {
                when {
                    t[0] >= width / 2f && t[0] < actionSplit -> a = true
                    t[0] >= width / 2f -> j = true
                    t[0] < split -> l = true
                    else -> r = true
                }
            }
        }
        game.inLeft = l
        game.inRight = r
        game.inJump = j
        game.inAction = a
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!setKey(keyCode, true)) return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) {
            game.tapX = -1f
            game.tapY = -1f
            game.tapped = true
        }
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        setKey(keyCode, false) || super.onKeyUp(keyCode, event)

    private fun setKey(keyCode: Int, down: Boolean): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> keyLeft = down
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> keyRight = down
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_DPAD_CENTER -> keyJump = down
            KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X -> keyAction = down
            else -> return false
        }
        updateInput()
        return true
    }
}
