package com.roccoquest.teamup

import android.annotation.SuppressLint
import android.content.Context
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.roccoquest.game.Storage
import com.roccoquest.game.VIEW_H
import com.roccoquest.team.TeamControls
import com.roccoquest.team.TeamGame
import com.roccoquest.team.TeamInput

/** Hosts the game loop on its own thread and feeds it touch and key input. */
@SuppressLint("ViewConstructor")
class GameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private val input = TeamInput()
    private val sound = SoundFx(context)
    private val prefs = context.getSharedPreferences("teamup", Context.MODE_PRIVATE)
    private val game = TeamGame(input, sound, object : Storage {
        override fun load(key: String) = prefs.getInt(key, 0)
        override fun save(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    })
    private val gfx = CanvasGfx()

    @Volatile private var running = false
    private var resumed = false
    private var surfaceReady = false
    private var thread: Thread? = null

    private val touchX = FloatArray(10)
    private val touchY = FloatArray(10)
    private val touchId = IntArray(10)

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
                // Never let one bad frame close the whole game: recover to the title screen instead.
                try { game.update(step) } catch (t: Throwable) { recover(t) }
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
                try { game.render(gfx) } catch (t: Throwable) { recover(t) }
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            val elapsed = (System.nanoTime() - now) / 1_000_000L
            if (elapsed < 8) Thread.sleep(8 - elapsed)
        }
    }

    private var errors = 0

    private fun recover(t: Throwable) {
        android.util.Log.e("TeamUp", "Game error, going back to the title", t)
        errors++
        try { game.recover() } catch (_: Throwable) {}
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
                touchId[n] = e.getPointerId(i)
                touchX[n] = e.getX(i) / scale
                touchY[n] = e.getY(i) / scale
                n++
            }
        }
        TeamControls.apply(input, touchId, touchX, touchY, n, game.viewW)
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            input.tapX = e.getX(e.actionIndex) / scale
            input.tapY = e.getY(e.actionIndex) / scale
            input.tap = true
        }
        return true
    }

    // ------------------------------------------------------------------ Keyboard and game controllers

    /** Controller buttons for JUMP, THROW, SWITCH and SUPER (changeable on the CONTROLLER screen). */
    private val padKeys = IntArray(4)
    private val defaultPadKeys = intArrayOf(
        KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
    )

    // Every way of pressing a direction: keyboard, D-pad buttons, the D-pad "hat", and the analog stick.
    private var keyL = false; private var keyR = false; private var keyD = false; private var keyU = false
    private var padL = false; private var padR = false; private var padD = false; private var padU = false
    private var hatL = false; private var hatR = false; private var hatD = false; private var hatU = false
    private var stickL = false; private var stickR = false; private var stickD = false; private var stickU = false

    init {
        for (k in 0 until 4) padKeys[k] = prefs.getInt("pad$k", defaultPadKeys[k]).takeIf { it != 0 } ?: defaultPadKeys[k]
        showPadNames()
        game.onPadReset = {
            for (k in 0 until 4) padKeys[k] = defaultPadKeys[k]
            savePadKeys()
            showPadNames()
        }
    }

    private fun savePadKeys() {
        val e = prefs.edit()
        for (k in 0 until 4) e.putInt("pad$k", padKeys[k])
        e.apply()
    }

    private fun showPadNames() {
        for (k in 0 until 4) game.padNames[k] = buttonName(padKeys[k])
    }

    private fun buttonName(code: Int): String =
        KeyEvent.keyCodeToString(code).removePrefix("KEYCODE_").removePrefix("BUTTON_").replace('_', ' ')

    private fun isController(event: android.view.InputEvent): Boolean {
        val src = event.device?.sources ?: event.source
        return (src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }

    private fun refreshDirections() {
        input.left = keyL || padL || hatL || stickL
        input.right = keyR || padR || hatR || stickR
        input.down = keyD || padD || hatD || stickD
        input.up = keyU || padU || hatU || stickU
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isController(event)) {
            game.padConnected = true
            // The CONTROLLER screen is waiting for a button: remember it.
            if (game.state == TeamGame.State.CONTROLS && game.padStep >= 0 && KeyEvent.isGamepadButton(keyCode) &&
                keyCode != KeyEvent.KEYCODE_BUTTON_START && keyCode != KeyEvent.KEYCODE_BUTTON_SELECT
            ) {
                if (event.repeatCount == 0) {
                    padKeys[game.padStep] = keyCode
                    savePadKeys()
                    game.padPressed(buttonName(keyCode))
                }
                return true
            }
            if (event.repeatCount > 0) return true
            if (!setPad(keyCode, true)) return super.onKeyDown(keyCode, event)
            // Menus treat any button like a tap (at no particular spot).
            input.tapX = -1f
            input.tapY = -1f
            input.tap = true
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_0) {
            input.numberKey = 10
            return true
        }
        if (keyCode in KeyEvent.KEYCODE_1..KeyEvent.KEYCODE_9) {
            input.numberKey = keyCode - KeyEvent.KEYCODE_0
            return true
        }
        if (!setKey(keyCode, true)) return super.onKeyDown(keyCode, event)
        input.tapX = -1f
        input.tapY = -1f
        input.tap = true
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (isController(event)) return setPad(keyCode, false) || super.onKeyUp(keyCode, event)
        return setKey(keyCode, false) || super.onKeyUp(keyCode, event)
    }

    /**
     * A game controller (like a Switch Pro Controller): A jump, B throw, X switch, Y super (or whatever
     * was set on the CONTROLLER screen), + pause, - craft, L TNT, R long jump, ZL Black Flower,
     * ZR Poseidon, and pressing the left stick in to sprint.
     */
    private fun setPad(keyCode: Int, down: Boolean): Boolean {
        when (keyCode) {
            padKeys[0] -> input.jump = down
            padKeys[1] -> input.action = down
            padKeys[2] -> input.swap = down
            padKeys[3] -> input.superMove = down
            KeyEvent.KEYCODE_DPAD_LEFT -> { padL = down; refreshDirections() }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { padR = down; refreshDirections() }
            KeyEvent.KEYCODE_DPAD_DOWN -> { padD = down; refreshDirections() }
            KeyEvent.KEYCODE_DPAD_UP -> { padU = down; refreshDirections() }
            KeyEvent.KEYCODE_BUTTON_START -> input.pause = down
            KeyEvent.KEYCODE_BUTTON_SELECT -> input.craft = down
            KeyEvent.KEYCODE_BUTTON_L1 -> input.tnt = down
            KeyEvent.KEYCODE_BUTTON_R1 -> input.longJump = down
            KeyEvent.KEYCODE_BUTTON_L2 -> input.blackFlower = down
            KeyEvent.KEYCODE_BUTTON_R2 -> input.poseidon = down
            KeyEvent.KEYCODE_BUTTON_THUMBL -> input.run = down
            KeyEvent.KEYCODE_BUTTON_MODE -> input.musicToggle = down
            else -> return false
        }
        return true
    }

    /** The analog stick and the D-pad "hat" on controllers that send it that way. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK &&
            event.action == MotionEvent.ACTION_MOVE
        ) {
            game.padConnected = true
            val dev = event.device
            fun axis(a: Int): Float {
                val v = event.getAxisValue(a)
                val flat = dev?.getMotionRange(a, event.source)?.flat ?: 0f
                return if (kotlin.math.abs(v) > flat) v else 0f
            }
            val x = axis(MotionEvent.AXIS_X)
            val y = axis(MotionEvent.AXIS_Y)
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            val dead = 0.45f
            stickL = x < -dead; stickR = x > dead
            stickU = y < -0.6f; stickD = y > 0.6f
            hatL = hx < -0.5f; hatR = hx > 0.5f
            hatU = hy < -0.5f; hatD = hy > 0.5f
            refreshDirections()
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    /** Keyboard support. */
    private fun setKey(keyCode: Int, down: Boolean): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> { keyL = down; refreshDirections() }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> { keyR = down; refreshDirections() }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> { keyD = down; refreshDirections() }
            KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_TAB -> input.swap = down
            KeyEvent.KEYCODE_C -> input.craft = down
            KeyEvent.KEYCODE_T -> input.tnt = down
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> input.run = down
            KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_Z -> input.longJump = down
            KeyEvent.KEYCODE_G -> input.poseidon = down
            KeyEvent.KEYCODE_B -> input.blackFlower = down
            KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_V -> input.superMove = down
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { input.jump = down; keyU = down; refreshDirections() }
            KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_CTRL_LEFT -> input.action = down
            KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_ESCAPE -> input.pause = down
            KeyEvent.KEYCODE_M -> input.musicToggle = down
            else -> return false
        }
        return true
    }
}
