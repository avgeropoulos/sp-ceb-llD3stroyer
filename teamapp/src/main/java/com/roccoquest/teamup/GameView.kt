package com.roccoquest.teamup

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.input.InputManager
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.roccoquest.game.Storage
import com.roccoquest.game.VIEW_H
import com.roccoquest.team.FrameClock
import com.roccoquest.team.PadSticks
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
        override fun keys(): Set<String> = prefs.all.keys.toSet()
    })
    private val gfx = CanvasGfx()

    @Volatile private var running = false
    /** Bumped every time a loop starts, so an old loop that wakes up late knows to stop. */
    @Volatile private var loopId = 0
    @Volatile private var loopLooper: android.os.Looper? = null
    private var resumed = false
    private var surfaceReady = false
    private var thread: Thread? = null

    /** The title screen's UPDATE button: gets the newest version from GitHub. */
    private val updater = Updater(context, game, "TeamUp.apk", BuildConfig.BRANCH, BuildConfig.VERSION_CODE - 100)

    private val touchX = FloatArray(10)

    init {
        game.onUpdate = { updater.start() }
    }

    private val touchY = FloatArray(10)
    private val touchId = IntArray(10)

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        // Deliver stick moves the moment they happen instead of saving them up for the next frame.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK) } catch (_: Exception) {}
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
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

    fun release() {
        sound.release()
        updater.release()
        try { inputManager?.unregisterInputDeviceListener(deviceListener) } catch (_: Exception) {}
    }

    private fun startLoop() {
        if (!resumed || !surfaceReady || running) return
        running = true
        val id = ++loopId
        thread = Thread({ runLoop(id) }, "game-loop").also { it.start() }
    }

    private fun stopLoop() {
        running = false
        // Wake the loop up right away (with the screen off it would never get another frame).
        loopLooper?.quit()
        thread?.let {
            try { it.join(500) } catch (_: InterruptedException) {}
        }
        thread = null
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // Pixel art needs no more than 3 physical pixels per game pixel. In particular,
        // water tint and space glows should not blend a full 1440p phone buffer each frame.
        if (w > 0 && h > 0) {
            val targetH = minOf(h, (VIEW_H * 3).toInt())
            holder.setFixedSize((w.toLong() * targetH / h).toInt().coerceAtLeast(1), targetH)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { holder.surface.setFrameRate(60f, android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) } catch (_: Exception) {}
        }
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

    /**
     * The game thread draws exactly one frame per screen refresh (using the [Choreographer]), right
     * after reading the newest input. Drawing faster than the screen just piles finished frames up in
     * a queue, and every press then shows up a few frames late.
     */
    override fun run() = runLoop(loopId)

    private fun runLoop(myId: Int) {
        try {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (_: Exception) {}
        android.os.Looper.prepare()
        val looper = android.os.Looper.myLooper()!!
        loopLooper = looper
        // stopLoop() may have happened while this thread was starting.
        if (!running || myId != loopId) return
        val choreographer = android.view.Choreographer.getInstance()
        val clock = FrameClock()
        var frames = 0
        var fpsStart = System.nanoTime()
        val step = 1f / 60f
        val callback = object : android.view.Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (!running || myId != loopId) {
                    looper.quit()
                    return
                }
                val now = System.nanoTime()
                val steps = clock.steps(frameTimeNanos)
                repeat(steps) {
                    try { game.update(step) } catch (t: Throwable) { recover(t) }
                }
                if (now - fpsStart >= 1_000_000_000L) {
                    game.fps = (frames * 1_000_000_000L / (now - fpsStart)).toInt()
                    frames = 0
                    fpsStart = now
                }
                if (steps > 0) {
                    frames++
                    // The screen can disappear mid-draw (phone locked, app closed): just skip that frame.
                    try { draw() } catch (_: Exception) {}
                }
                choreographer.postFrameCallback(this)
            }
        }
        choreographer.postFrameCallback(callback)
        android.os.Looper.loop()
        if (loopLooper === looper) loopLooper = null
    }

    private fun draw() {
        val canvas = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) holder.lockHardwareCanvas() else holder.lockCanvas()
        } catch (_: Exception) { null } ?: return
        try {
            val scale = canvas.height / VIEW_H
            game.viewW = canvas.width / scale
            gfx.begin(canvas, scale)
            try { game.render(gfx) } catch (t: Throwable) { recover(t) }
        } finally {
            holder.unlockCanvasAndPost(canvas)
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
        // Get the rest of this touch straight away instead of once per frame.
        if (action == MotionEvent.ACTION_DOWN) try { requestUnbufferedDispatch(e) } catch (_: Exception) {}
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

    /**
     * Every way of pressing a direction (keyboard, D-pad buttons, the D-pad "hat", the analog stick), for
     * player 1 and player 2. Which controller is which player is [TeamGame.padSlots]' job (plain Kotlin).
     */
    private val sticks = arrayOf(PadSticks(input), PadSticks(game.input2))

    /** Player 1's or player 2's buttons. */
    private fun inputOf(player: Int): TeamInput = if (player == 0) input else game.input2

    /** An unplugged controller frees its player's place and lets go of everything it held. */
    private val inputManager = context.getSystemService(Context.INPUT_SERVICE) as? InputManager
    private val deviceListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) {}
        override fun onInputDeviceChanged(deviceId: Int) {}
        override fun onInputDeviceRemoved(deviceId: Int) {
            val p = game.padSlots.removed(deviceId)
            if (p >= 0) {
                sticks[p].clear()
                inputOf(p).releaseAll()
            }
        }
    }

    init {
        try { inputManager?.registerInputDeviceListener(deviceListener, null) } catch (_: Exception) {}
    }

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

    /**
     * The activity hands every key straight to us (so the controller works even if Android thinks
     * something else has "focus"). Returns true if the game used the key.
     */
    fun handleKey(event: KeyEvent): Boolean = when (event.action) {
        KeyEvent.ACTION_DOWN -> onKeyDown(event.keyCode, event)
        KeyEvent.ACTION_UP -> onKeyUp(event.keyCode, event)
        else -> false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (isController(event)) {
            game.padConnected = true
            // The first controller used is player 1, a second one is player 2 (LUGO).
            val player = game.padSlots.playerFor(event.deviceId)
            val pin = inputOf(player)
            if (event.repeatCount == 0) game.padSaw(buttonName(keyCode))
            // The CONTROLLER screen is waiting for a button: remember it.
            if (player == 0 && game.state == TeamGame.State.CONTROLS && game.padStep >= 0 && KeyEvent.isGamepadButton(keyCode) &&
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
            if (!setPad(keyCode, true, player)) return super.onKeyDown(keyCode, event)
            // Menus treat any button like a tap (at no particular spot).
            pin.tapX = -1f
            pin.tapY = -1f
            pin.tap = true
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
        if (isController(event)) return setPad(keyCode, false, game.padSlots.playerFor(event.deviceId)) || super.onKeyUp(keyCode, event)
        return setKey(keyCode, false) || super.onKeyUp(keyCode, event)
    }

    /**
     * A game controller (like a Switch Pro Controller): A jump, B throw, X switch, Y super (or whatever
     * was set on the CONTROLLER screen), + pause, - craft, L TNT, R long jump, ZL Black Flower,
     * ZR Poseidon, and pressing the left stick in to sprint.
     */
    private fun setPad(keyCode: Int, down: Boolean, player: Int): Boolean {
        val input = inputOf(player)
        val s = sticks[player]
        when (keyCode) {
            padKeys[0] -> input.jump = down
            padKeys[1] -> input.action = down
            padKeys[2] -> input.swap = down
            padKeys[3] -> input.superMove = down
            KeyEvent.KEYCODE_DPAD_LEFT -> { s.padL = down; s.refresh() }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { s.padR = down; s.refresh() }
            KeyEvent.KEYCODE_DPAD_DOWN -> { s.padD = down; s.refresh() }
            KeyEvent.KEYCODE_DPAD_UP -> { s.padU = down; s.refresh() }
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

    /**
     * The analog stick and the D-pad "hat" on controllers that send it that way (see [PadSticks.stick]),
     * for whichever player that controller belongs to.
     */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_CLASS_JOYSTICK) && event.actionMasked == MotionEvent.ACTION_MOVE) {
            game.padConnected = true
            val x = event.getAxisValue(MotionEvent.AXIS_X)
            val y = event.getAxisValue(MotionEvent.AXIS_Y)
            val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
            val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
            game.stickX = if (kotlin.math.abs(hx) > 0.5f) hx else x
            game.stickY = if (kotlin.math.abs(hy) > 0.5f) hy else y
            sticks[game.padSlots.playerFor(event.deviceId)].stick(x, y, hx, hy)
            return true
        }
        return super.onGenericMotionEvent(event)
    }

    /** Keyboard support. */
    private fun setKey(keyCode: Int, down: Boolean): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> { sticks[0].keyL = down; sticks[0].refresh() }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> { sticks[0].keyR = down; sticks[0].refresh() }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> { sticks[0].keyD = down; sticks[0].refresh() }
            KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_TAB -> input.swap = down
            KeyEvent.KEYCODE_C -> input.craft = down
            KeyEvent.KEYCODE_T -> input.tnt = down
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT -> input.run = down
            KeyEvent.KEYCODE_L, KeyEvent.KEYCODE_Z -> input.longJump = down
            KeyEvent.KEYCODE_G -> input.poseidon = down
            KeyEvent.KEYCODE_B -> input.blackFlower = down
            KeyEvent.KEYCODE_E, KeyEvent.KEYCODE_V -> input.superMove = down
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { input.jump = down; sticks[0].keyU = down; sticks[0].refresh() }
            KeyEvent.KEYCODE_X, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_CTRL_LEFT -> input.action = down
            KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_ESCAPE -> input.pause = down
            KeyEvent.KEYCODE_M -> input.musicToggle = down
            else -> return false
        }
        return true
    }
}
