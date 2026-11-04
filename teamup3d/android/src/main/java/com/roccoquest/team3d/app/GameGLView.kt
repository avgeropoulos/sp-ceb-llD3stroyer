package com.roccoquest.team3d.app

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.input.InputManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import com.roccoquest.game.Storage
import com.roccoquest.game.VIEW_H
import com.roccoquest.team.FrameClock
import com.roccoquest.team.PadSticks
import com.roccoquest.team.TeamControls
import com.roccoquest.team.TeamGame
import com.roccoquest.team.TeamInput
import com.roccoquest.team.Armor
import com.roccoquest.team.Shield
import com.roccoquest.team.Sword
import com.roccoquest.team.WORLD_SIZE
import com.roccoquest.team3d.TeamScene
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Team Up! 3D's screen. It runs the normal Team Up! game. In a level (and on the title screen)
 * each frame is the painted sky in the back, the level in real 3D with OpenGL, and the HUD and
 * buttons on top. Other screens (map, house, menus, sticker book) are Team Up!'s crisp 2D picture.
 * If 3D ever goes wrong, that moment is drawn in plain 2D instead.
 * Touch, keyboard and controller handling is the same as Team Up!'s GameView.
 */
@SuppressLint("ViewConstructor")
class GameGLView(context: Context) : GLSurfaceView(context), GLSurfaceView.Renderer {
    private val input = TeamInput()
    private val sound = SoundFx(context)
    private val prefs = context.getSharedPreferences("teamup", Context.MODE_PRIVATE)
    private val game = TeamGame(input, sound, object : Storage {
        override fun load(key: String) = prefs.getInt(key, 0)
        override fun save(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
        override fun keys(): Set<String> = prefs.all.keys.toSet()
    })

    init {
        // File 1 picks up about where the 2D game was: level 9, with coins and supplies.
        game.headStart = HEAD_START
        // The title screen sings the TEAM UP! theme song.
        game.titleMusic = com.roccoquest.game.Music.THEME
    }

    private val scene = TeamScene()
    private val glScene = GlScene()
    private val sky = CanvasLayer()
    private val front = CanvasLayer()
    private val gfx = CanvasGfx()
    private val clock = FrameClock()
    private var frames = 0
    private var fpsStart = System.nanoTime()
    private var surfaceW = 0
    private var surfaceH = 0

    /** The title screen's UPDATE button: gets the newest version from GitHub. */
    private val updater = Updater(context, game, "TeamUp3D.apk", com.roccoquest.team3d.BuildConfig.BRANCH, com.roccoquest.team3d.BuildConfig.VERSION_CODE - 100)

    private val touchX = FloatArray(10)

    init {
        game.onUpdate = { updater.start() }
    }

    private val touchY = FloatArray(10)
    private val touchId = IntArray(10)

    init {
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(this)
        // Frames are asked for by [vsync] once per screen refresh, with the refresh's own time,
        // so the game moves the same amount every frame (smooth even when sprinting).
        renderMode = RENDERMODE_WHEN_DIRTY
        isFocusable = true
        isFocusableInTouchMode = true
        holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(h: SurfaceHolder) {
                // One frame per 60 Hz tick is all the game needs, even on 120 Hz screens.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    try { h.surface.setFrameRate(60f, android.view.Surface.FRAME_RATE_COMPATIBILITY_DEFAULT) } catch (_: Exception) {}
                }
            }
            override fun surfaceChanged(h: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(h: SurfaceHolder) {}
        })
        // Deliver stick moves the moment they happen instead of saving them up for the next frame.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try { requestUnbufferedDispatch(InputDevice.SOURCE_CLASS_JOYSTICK) } catch (_: Exception) {}
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
    }

    @Volatile private var ticking = false
    /**
     * Game updates owed. The [clock] counts them on the main thread at each screen refresh (with the
     * refresh's own time), and [onDrawFrame] runs them on the GL thread.
     */
    private val pendingSteps = java.util.concurrent.atomic.AtomicInteger(0)

    private val vsync = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!ticking) return
            val steps = clock.steps(frameTimeNanos)
            // On a 120 Hz screen every other refresh has no new update: don't draw the same picture twice.
            if (steps > 0) {
                pendingSteps.addAndGet(steps)
                requestRender()
            }
            android.view.Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun resume() {
        sound.setForeground(true)
        onResume()
        if (!ticking) {
            ticking = true
            // The first refresh back is one normal update, not a jump to catch up on the pause.
            clock.reset()
            pendingSteps.set(0)
            android.view.Choreographer.getInstance().postFrameCallback(vsync)
        }
    }

    fun pause() {
        sound.setForeground(false)
        ticking = false
        android.view.Choreographer.getInstance().removeFrameCallback(vsync)
        onPause()
    }

    fun release() {
        sound.release()
        updater.release()
        try { inputManager?.unregisterInputDeviceListener(deviceListener) } catch (_: Exception) {}
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // At most 3 screen pixels per game pixel, like Team Up!: sharp, without the extra work of
        // drawing every pixel of a big tablet screen.
        if (w > 0 && h > 0) {
            val targetH = minOf(h, (VIEW_H * 3).toInt())
            holder.setFixedSize((w.toLong() * targetH / h).toInt().coerceAtLeast(1), targetH)
        }
    }

    // ------------------------------------------------------------------ Drawing (GL thread)

    override fun onSurfaceCreated(unused: GL10?, config: EGLConfig?) {
        // A new GL context: everything from an old one is gone.
        sky.contextLost()
        front.contextLost()
        glScene.init()
        surfaceW = 0
        surfaceH = 0
    }

    override fun onSurfaceChanged(unused: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        if (height > 0) game.viewW = width * VIEW_H / height
        if (width != surfaceW || height != surfaceH) {
            surfaceW = width
            surfaceH = height
            sky.create(width, height)
            front.create(width, height)
        }
    }

    override fun onDrawFrame(unused: GL10?) {
        val now = System.nanoTime()
        // A slow frame can't pile up more than a few updates (the same limit as the clock's).
        val steps = minOf(pendingSteps.getAndSet(0), MAX_STEPS_PER_DRAW)
        repeat(steps) {
            try { game.update(1f / 60f) } catch (t: Throwable) { recover(t) }
        }
        frames++
        if (now - fpsStart >= 1_000_000_000L) {
            game.fps = (frames * 1_000_000_000L / (now - fpsStart)).toInt()
            frames = 0
            fpsStart = now
        }
        if (surfaceH <= 0) return
        val scale = surfaceH / VIEW_H
        game.viewW = surfaceW / scale
        try {
            drawFrame(scale)
        } catch (t: Throwable) {
            recover(t)
        }
    }

    private fun drawFrame(scale: Float) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val in3d = game.showsLevel || game.state == TeamGame.State.TITLE
        if (!in3d || failed3d > 0) {
            // Map, house, menus, sticker book...: the crisp normal Team Up! picture (fast).
            // Also used for a moment if 3D ever has a problem.
            if (failed3d > 0) failed3d--
            getReady()
            draw2d(scale)
            return
        }
        val list = try {
            scene.build(game)
        } catch (t: Throwable) {
            android.util.Log.e("TeamUp3D", "3D scene failed, drawing in 2D", t)
            failed3d = 30
            draw2d(scale)
            return
        }
        // With 3D scenery (hills, trees, mountains) behind the level, the painted backdrop is only the sky.
        val paintedScenery = !scene.drawsScenery
        sky.paint { c -> gfx.begin(c, scale); game.renderLevelBackdrop(gfx, paintedScenery) }
        front.paint { c ->
            gfx.begin(c, scale)
            // Words floating in the level (scores, names) were placed by the 3D scene.
            for (i in 0 until list.labelCount) {
                val l = list.label(i)
                gfx.text(l.text, l.x, l.y, l.size, l.color, l.align)
            }
            try { game.render(gfx, drawWorld = false) } catch (t: Throwable) { recover(t) }
        }
        sky.draw(blend = false)
        GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
        try {
            glScene.render(list)
        } catch (t: Throwable) {
            android.util.Log.e("TeamUp3D", "3D drawing failed", t)
            failed3d = 30
        }
        front.draw(blend = true)
    }

    /** Frames left to draw in plain 2D after the 3D scene had a problem. */
    private var failed3d = 0

    /**
     * A level's INTRO card is drawn in 2D: meanwhile the 3D scene builds the level's scenery and a couple of its
     * meshes go to the GPU each frame, so the level's first 3D frame has nothing new to make (no stutter).
     */
    private fun getReady() {
        try {
            scene.prepare(game)
            glScene.preload(scene.scenery?.meshes)
        } catch (t: Throwable) {
            // Not ready yet is fine: the first 3D frame builds whatever is missing.
            android.util.Log.e("TeamUp3D", "Getting the 3D scenery ready failed", t)
        }
    }

    private fun draw2d(scale: Float) {
        front.paint { c ->
            gfx.begin(c, scale)
            try { game.render(gfx) } catch (t: Throwable) { recover(t) }
        }
        front.draw(blend = false)
    }

    private var errors = 0

    private fun recover(t: Throwable) {
        android.util.Log.e("TeamUp3D", "Game error, going back to the title", t)
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
    private companion object {
        /** World 9-1, with plenty to pick up where the 2D game was. */
        val HEAD_START = TeamGame.HeadStart(
            level = WORLD_SIZE * 8, coins = 5000, ore = 99, pickTier = 4, tnt = 30,
            sword = Sword.DIAMOND, armor = Armor.DIAMOND, shield = Shield.SAPPHIRE, key = "start3d_world9",
            message = "WORLD 9! 5000 COINS, 99 OF EVERY ORE, DIAMOND GEAR AND 30 TNT!",
        )
        /** At most this many game updates before one picture (a slow phone mustn't fall further behind). */
        const val MAX_STEPS_PER_DRAW = 3
    }
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

    /** [com.roccoquest.team.PadSlots.generation] last seen: when who-is-who starts over, held directions are let go. */
    private var padGeneration = -1

    /**
     * Which player a controller's button or stick is for: 0, 1, or -1 (nobody: a second controller that hasn't
     * pressed a button yet). With 1 PLAYER it's always 0, just like before there was a player 2. [button]: a
     * button went down (only that can make a controller player 2). A PlayStation controller's touchpad and
     * motion sensor, the volume keys and on-screen keyboards are never "real" controllers, and every part of
     * one controller has the same descriptor (so it's never mistaken for a second one).
     */
    private fun playerOf(event: android.view.InputEvent, button: Boolean): Int {
        val slots = game.padSlots
        val generation = slots.generation
        if (generation != padGeneration) {
            padGeneration = generation
            for (s in sticks) s.clear()
        }
        val dev = event.device
        val src = dev?.sources ?: 0
        val real = dev != null && !dev.isVirtual && ((src and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (src and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
        val descriptor = try { dev?.descriptor ?: "" } catch (_: Exception) { "" }
        return slots.playerFor(event.deviceId, descriptor, real, button)
    }

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
            if (event.repeatCount == 0) game.padSaw(buttonName(keyCode))
            // 1 PLAYER: every controller is player 1. 2 PLAYERS: a second controller is player 2 (LUGO).
            val player = playerOf(event, KeyEvent.isGamepadButton(keyCode) && event.repeatCount == 0)
            if (player < 0) return true
            val pin = inputOf(player)
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
        if (isController(event)) {
            val player = playerOf(event, false)
            if (player < 0) return true
            return setPad(keyCode, false, player) || super.onKeyUp(keyCode, event)
        }
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
            val player = playerOf(event, false)
            if (player >= 0) sticks[player].stick(x, y, hx, hy)
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
