package com.roccoquest.openworld

import android.app.Activity
import android.os.Bundle
import android.view.*

class MainActivity : Activity() {
    private lateinit var game: OpenWorldView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        game=OpenWorldView(this)
        setContentView(game)
    }
    @Suppress("DEPRECATION")
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if(hasFocus) window.decorView.systemUiVisibility=5894
        else if(::game.isInitialized) game.pauseGame()
    }
    override fun onResume() { super.onResume(); game.onResume() }
    override fun onPause() { game.pauseGame(); game.onPause(); super.onPause() }
    override fun dispatchKeyEvent(event: KeyEvent) = game.key(event) || super.dispatchKeyEvent(event)
    override fun dispatchGenericMotionEvent(event: MotionEvent) = game.pad(event) || super.dispatchGenericMotionEvent(event)
    @Deprecated("Android back compatibility")
    override fun onBackPressed() { game.pauseGame() }
}
