package com.thermopylae

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Color
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.FrameLayout
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * Hosts the game in a full-screen [WebView] served by [AssetServer], with [NativeBridge] exposed
 * as `window.SpartanNative`. Handles immersive mode, display cutouts, back, the WebView
 * version check and renderer crashes.
 */
class MainActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var bridge: NativeBridge
    private var webView: WebView? = null
    private var isResumedNow = false
    private var backCallback: Any? = null
    private var dialog: AlertDialog? = null

    /** Display-cutout safe insets in physical px: left, top, right, bottom. */
    private val cutout = IntArray(4)
    private val rendererDeaths = ArrayList<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        volumeControlStream = AudioManager.STREAM_MUSIC
        val attrs = window.attributes
        attrs.preferredRefreshRate = 60f
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Api28.cutoutShortEdges(attrs)
        window.attributes = attrs

        root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            root.setOnApplyWindowInsetsListener { v, insets ->
                onCutoutInsets(insets)
                v.onApplyWindowInsets(insets)
            }
        }
        setContentView(root)

        bridge = NativeBridge(this)
        try {
            WebView.setWebContentsDebuggingEnabled((applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0)
        } catch (e: Throwable) {
            Log.w(TAG, e)
        }
        if (createWebView(recovering = false)) checkEngine()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            backCallback = Api33.registerBack(this) { handleBack() }
        }
    }

    /** Builds the WebView, adds it to the layout and loads the game. False if WebView is unavailable. */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun createWebView(recovering: Boolean): Boolean {
        val wv = try {
            WebView(this)
        } catch (e: Throwable) {
            // No WebView provider installed or enabled, or it is being updated right now.
            Log.e(TAG, "WebView unavailable", e)
            showWebViewMissing()
            return false
        }
        wv.setBackgroundColor(Color.BLACK)
        wv.isLongClickable = false
        wv.setOnLongClickListener { true }
        wv.isHapticFeedbackEnabled = false
        wv.overScrollMode = View.OVER_SCROLL_NEVER
        wv.isVerticalScrollBarEnabled = false
        wv.isHorizontalScrollBarEnabled = false
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.allowFileAccess = false
        s.allowContentAccess = false
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.displayZoomControls = false
        s.textZoom = 100
        wv.webViewClient = AssetServer(
            assets,
            pageStarted = { bridge.onNewPage() },
            pageFinished = { pushCutoutInsets() },
            rendererGone = { view, crashed -> onRendererGone(view, crashed) },
        )
        root.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        webView = wv
        // WebView timers are process-wide: match them to our state, since a replacement can be
        // built while paused, or after onPause/onResume ran with no WebView at all.
        if (isResumedNow) {
            wv.resumeTimers()
        } else if (recovering) {
            wv.onPause()
            wv.pauseTimers()
        }
        wv.addJavascriptInterface(bridge, "SpartanNative")
        wv.loadUrl(HostLogic.START_URL)
        wv.requestFocus()
        return true
    }

    override fun onPause() {
        super.onPause()
        isResumedNow = false
        val wv = webView
        wv?.evaluateJavascript("window.dispatchEvent(new Event('nativepause'))", null)
        bridge.onPause()
        if (wv != null) {
            wv.onPause()
            wv.pauseTimers()
        }
    }

    override fun onResume() {
        super.onResume()
        isResumedNow = true
        val wv = webView
        if (wv != null) {
            wv.resumeTimers()
            wv.onResume()
        }
        bridge.onResume()
        applyImmersive()
        wv?.evaluateJavascript("window.dispatchEvent(new Event('nativeresume'))", null)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersive()
        } else if (isResumedNow) {
            // The notification shade, power menu or a system dialog took focus without pausing us.
            webView?.evaluateJavascript("window.dispatchEvent(new Event('nativeblur'))", null)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        bridge.updateRotation()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Api33.unregisterBack(this, backCallback)
        backCallback = null
        if (::bridge.isInitialized) bridge.release()
        val wv = webView
        webView = null
        if (wv != null) {
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        super.onDestroy()
    }

    // ---- Back ----

    /** API 24–32. On API 33+ back arrives through the OnBackInvokedCallback instead. */
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        handleBack()
    }

    /** Asks the game (`window.__onBack`) to handle back; anything but `true` closes the app. */
    private fun handleBack() {
        val wv = webView
        if (wv == null) {
            finish()
            return
        }
        wv.evaluateJavascript(BACK_SCRIPT) { result ->
            // Like a launcher Back on Android 12+: send the game to the background instead of
            // destroying it, so coming back is instant rather than a full reload.
            if (result != "true" && !isFinishing && !moveTaskToBack(true)) finish()
        }
    }

    // ---- Immersive mode and cutouts ----

    private fun applyImmersive() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Api30.hideSystemBars(window)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    /** API 28+. Stores the cutout's safe insets and sends them to the page when they change. */
    private fun onCutoutInsets(insets: WindowInsets) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val l = cutout[0]
        val t = cutout[1]
        val r = cutout[2]
        val b = cutout[3]
        Api28.safeInsets(insets, cutout)
        if (l != cutout[0] || t != cutout[1] || r != cutout[2] || b != cutout[3]) pushCutoutInsets()
    }

    /** Sets `--sai-left/top/right/bottom` (CSS px) on the page. Also runs in onPageFinished. */
    private fun pushCutoutInsets() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return
        val wv = webView ?: return
        val d = resources.displayMetrics.density.takeIf { it > 0f } ?: 1f
        val js = HostLogic.insetsScript(cutout[0] / d, cutout[1] / d, cutout[2] / d, cutout[3] / d)
        wv.evaluateJavascript(js, null)
    }

    // ---- WebView engine check and recovery ----

    /** Warns once at start if the WebView is older than Chrome 94 (no class static blocks, no import maps). */
    private fun checkEngine() {
        val ua = try {
            WebSettings.getDefaultUserAgent(this)
        } catch (e: Throwable) {
            Log.w(TAG, e)
            null
        }
        val major = HostLogic.chromeMajor(ua)
        if (major == 0 || major >= HostLogic.MIN_CHROME) return
        showDialog(
            "Update Android System WebView",
            "Thermopylae needs Android System WebView (or Chrome) version ${HostLogic.MIN_CHROME} or newer. " +
                "This phone has version $major.",
            "Update", { openStore(webViewPackage()) },
            "Not now", {},
        )
    }

    private fun showWebViewMissing() {
        showDialog(
            "Android System WebView needed",
            "Thermopylae runs in Android System WebView, which is missing, disabled or being updated. " +
                "Install or enable it, then start the game again.",
            "Open Play Store", {
                openStore(webViewPackage())
                finish()
            },
            "Close", { finish() },
            cancelable = false,
        )
    }

    /** Replaces a dead renderer's WebView with a fresh one; gives up after 3 deaths in a minute. */
    private fun onRendererGone(view: WebView, crashed: Boolean): Boolean {
        Log.e(TAG, "WebView renderer gone, crashed=$crashed")
        (view.parent as? ViewGroup)?.removeView(view)
        if (view === webView) webView = null
        view.destroy()
        if (isFinishing || isDestroyed) return true
        val now = SystemClock.elapsedRealtime()
        rendererDeaths.removeAll { now - it > 60_000L }
        rendererDeaths.add(now)
        if (rendererDeaths.size >= 3) {
            showDialog(
                "The battle was interrupted",
                "The game's renderer stopped several times. This can happen when the phone runs low on memory.",
                "Try again", {
                    rendererDeaths.clear()
                    createWebView(recovering = true)
                },
                "Close", { finish() },
                cancelable = false,
            )
        } else {
            createWebView(recovering = true)
        }
        return true
    }

    private fun webViewPackage(): String {
        val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Api26.webViewPackage() else null
        return current ?: "com.google.android.webview"
    }

    private fun openStore(pkg: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pkg")))
        } catch (e: ActivityNotFoundException) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$pkg")))
            } catch (e2: ActivityNotFoundException) {
                Log.w(TAG, "no store or browser", e2)
            }
        }
    }

    private fun showDialog(
        title: String,
        message: String,
        positive: String,
        onPositive: () -> Unit,
        negative: String,
        onNegative: () -> Unit,
        cancelable: Boolean = true,
    ) {
        if (isFinishing || isDestroyed) return
        dialog?.dismiss()
        dialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(positive) { _, _ -> onPositive() }
            .setNegativeButton(negative) { _, _ -> onNegative() }
            .setCancelable(cancelable)
            .show()
    }

    // ---- Version-specific calls, kept in their own classes so older runtimes never resolve them ----

    @TargetApi(Build.VERSION_CODES.O)
    private object Api26 {
        fun webViewPackage(): String? = WebView.getCurrentWebViewPackage()?.packageName
    }

    @TargetApi(Build.VERSION_CODES.P)
    private object Api28 {
        fun cutoutShortEdges(attrs: WindowManager.LayoutParams) {
            attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        fun safeInsets(insets: WindowInsets, out: IntArray) {
            val c = insets.displayCutout
            out[0] = c?.safeInsetLeft ?: 0
            out[1] = c?.safeInsetTop ?: 0
            out[2] = c?.safeInsetRight ?: 0
            out[3] = c?.safeInsetBottom ?: 0
        }
    }

    @TargetApi(Build.VERSION_CODES.R)
    private object Api30 {
        fun hideSystemBars(window: Window) {
            // Needed on API 30–34; API 35 makes targetSdk-35 apps edge-to-edge and deprecates it.
            @Suppress("DEPRECATION")
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let {
                it.hide(WindowInsets.Type.systemBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
    }

    @TargetApi(Build.VERSION_CODES.TIRAMISU)
    private object Api33 {
        fun registerBack(activity: Activity, onBack: () -> Unit): Any {
            val callback = OnBackInvokedCallback { onBack() }
            activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            return callback
        }

        fun unregisterBack(activity: Activity, callback: Any?) {
            if (callback is OnBackInvokedCallback) activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(callback)
        }
    }

    private companion object {
        const val TAG = "Spartan"
        const val BACK_SCRIPT =
            "(function(){try{return !!(window.__onBack&&window.__onBack())}catch(e){return false}})()"
    }
}
