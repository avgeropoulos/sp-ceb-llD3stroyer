package com.superpritzman

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.util.Locale

/**
 * Super Pritz Man is an HTML5 canvas game (assets/game.html). This activity shows it in a
 * full-screen WebView and lends it the phone's text-to-speech so the blonde lady can yell.
 */
class MainActivity : Activity() {
    private lateinit var webView: WebView
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        tts = TextToSpeech(applicationContext) { status ->
            val t = tts ?: return@TextToSpeech
            if (status == TextToSpeech.SUCCESS) {
                t.setLanguage(Locale.US)
                t.setPitch(1.7f)
                t.setSpeechRate(1.15f)
                ttsReady = true
            }
        }

        webView = WebView(this)
        webView.setBackgroundColor(Color.parseColor("#1B1340"))
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.addJavascriptInterface(Voice(), "PritzAndroid")
        setContentView(webView)

        val game = assets.open("game.html").bufferedReader().use { it.readText() }
        val page = "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1," +
            "user-scalable=no,viewport-fit=cover\"></head><body>" + game + "</body></html>"
        webView.loadDataWithBaseURL("file:///android_asset/", page, "text/html", "utf-8", null)
    }

    /** Called from the game's JavaScript as `PritzAndroid.say("Stop that!")`. */
    inner class Voice {
        @JavascriptInterface
        fun say(text: String) {
            if (ttsReady) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "lady")
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUi()
        webView.onResume()
    }

    override fun onPause() {
        webView.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        webView.destroy()
        super.onDestroy()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            // The game pauses or goes back to its menu; only leave the app from the title screen.
            webView.evaluateJavascript("window.SuperPritzMan ? window.SuperPritzMan.back() : false") { handled ->
                if (handled != "true") finish()
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
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
