package com.roccoquest.team3d.app

import android.annotation.SuppressLint
import android.content.Context
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
import com.roccoquest.team.TeamControls
import com.roccoquest.team.TeamGame
import com.roccoquest.team.TeamInput
import com.roccoquest.team3d.FlatScreen
import com.roccoquest.team3d.Glyphs
import com.roccoquest.team3d.TeamScene
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Team Up! 3D's screen. It runs the normal Team Up! game and draws each frame with OpenGL:
 * in a level (and on the title screen) the painted sky goes in the back and the level is real
 * 3D; then everything else (HUD, buttons, menus, map, house, mini games) is drawn on top as
 * chunky lit 3D by [FlatScreen], in exactly the places the 2D game puts it, so taps still work.
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
    }

    private val scene = TeamScene()
    private val screen = FlatScreen()
    private val glScene = GlScene()
    private val sky = CanvasLayer()
    private val front = CanvasLayer()
    private val gfx = CanvasGfx()
    private val clock = FrameClock()
    private var frames = 0
    private var fpsStart = System.nanoTime()
    private var surfaceW = 0
    private var surfaceH = 0

    private val touchX = FloatArray(10)
    private val touchY = FloatArray(10)
    private val touchId = IntArray(10)

    init {
        Glyphs.rasterizer = AndroidGlyphs()
        setEGLContextClientVersion(2)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        setRenderer(this)
        renderMode = RENDERMODE_CONTINUOUSLY
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

    fun resume() {
        sound.setForeground(true)
        onResume()
    }

    fun pause() {
        sound.setForeground(false)
        onPause()
    }

    fun release() = sound.release()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 2 screen pixels per game pixel: still sharp for 3D, and less than half the drawing work
        // of full resolution on most phones, so the game stays smooth.
        if (w > 0 && h > 0) {
            val targetH = minOf(h, (VIEW_H * 2).toInt())
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
        val steps = clock.steps(now)
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
        if (failed3d > 0) {
            // 3D had a problem a moment ago: draw the normal Team Up! picture for a little while.
            failed3d--
            draw2d(scale)
            return
        }
        try {
            val world = scene.build(game)
            val top = screen.build(game, world)
            if (world.showWorld) {
                // The level (or the title screen): painted sky, then the 3D world.
                sky.paint { c -> gfx.begin(c, scale); game.renderLevelBackdrop(gfx) }
                sky.draw(blend = false)
                GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
                glScene.render(world)
            }
            // Everything else (HUD, buttons, menus, map, house...) as chunky 3D on top.
            GLES20.glClear(GLES20.GL_DEPTH_BUFFER_BIT)
            glScene.render(top)
        } catch (t: Throwable) {
            // Never let a 3D drawing problem stop the game: show this moment in 2D instead.
            android.util.Log.e("TeamUp3D", "3D drawing failed, drawing in 2D", t)
            failed3d = 30
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            draw2d(scale)
        }
    }

    /** Frames left to draw in plain 2D after the 3D scene had a problem. */
    private var failed3d = 0

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
        val HEAD_START = TeamGame.HeadStart(
            level = 8, coins = 1500, ore = 40, pickTier = 2, tnt = 10,
            message = "3D HEAD START: LEVEL 9, 1500 COINS, 40 OF EVERY ORE, IRON PICK, 10 TNT!",
        )
        const val STICK_ON = 0.35f
        const val STICK_OFF = 0.25f
    }
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

    /**
     * The analog stick and the D-pad "hat" on controllers that send it that way. A direction turns on
     * past [STICK_ON] and only turns off again below [STICK_OFF], so a stick resting near the edge
     * doesn't flicker on and off.
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
            stickL = if (stickL) x < -STICK_OFF else x < -STICK_ON
            stickR = if (stickR) x > STICK_OFF else x > STICK_ON
            // Up and down need a firmer push, so running doesn't make Rocco duck by accident.
            stickU = if (stickU) y < -STICK_OFF - 0.2f else y < -STICK_ON - 0.2f
            stickD = if (stickD) y > STICK_OFF + 0.2f else y > STICK_ON + 0.2f
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
