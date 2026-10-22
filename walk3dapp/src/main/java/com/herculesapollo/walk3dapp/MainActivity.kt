package com.herculesapollo.walk3dapp

import android.app.Activity
import android.content.Context
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import com.herculesapollo.walk3d.Game3D
import com.herculesapollo.walk3d.Input3D
import com.herculesapollo.walkshared.WalkSoundFx
import com.roccoquest.game.Storage

class MainActivity : Activity() {
    private lateinit var glView: GLSurfaceView
    private lateinit var sound: WalkSoundFx
    private lateinit var game: Game3D

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        sound = WalkSoundFx(this)
        val prefs = getSharedPreferences("walk3d", Context.MODE_PRIVATE)
        game = Game3D(Input3D(), sound, object : Storage {
            override fun load(key: String) = prefs.getInt(key, 0)
            override fun save(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
        })
        glView = GLSurfaceView(this).apply {
            setEGLContextClientVersion(2)
            preserveEGLContextOnPause = true
            setRenderer(GlRenderer(game))
        }
        val root = FrameLayout(this)
        root.addView(glView)
        root.addView(HudView(this, game))
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        glView.onResume()
        sound.setForeground(true)
    }

    override fun onPause() {
        glView.onPause()
        sound.setForeground(false)
        super.onPause()
    }

    /** Back pauses the game (or goes back to the title); on the title screen it leaves the app. */
    @Deprecated("Still called for the Back button while the app doesn't opt in to predictive back.")
    override fun onBackPressed() {
        @Suppress("DEPRECATION")
        if (game.atTitle) super.onBackPressed() else game.input.back = true
    }

    override fun onDestroy() {
        sound.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!setKey(keyCode, true)) return super.onKeyDown(keyCode, event)
        game.input.tapX = -1f
        game.input.tapY = -1f
        game.input.tap = true
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        setKey(keyCode, false) || super.onKeyUp(keyCode, event)

    /** Keyboard / game-controller support. */
    private fun setKey(keyCode: Int, down: Boolean): Boolean {
        val input = game.input
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_A -> input.keyLeft = down
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_D -> input.keyRight = down
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_W -> input.keyUp = down
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_S -> input.keyDown = down
            KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_ENTER -> input.jump = down
            KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_Z, KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_B -> input.action = down
            KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_ESCAPE -> input.pause = down
            KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_BUTTON_SELECT -> input.musicToggle = down
            else -> return false
        }
        return true
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }
}
