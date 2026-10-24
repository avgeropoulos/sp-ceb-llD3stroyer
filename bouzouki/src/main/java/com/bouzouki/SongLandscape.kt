package com.bouzouki

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import com.bouzouki.core.BouzoukiSynth
import com.bouzouki.core.Cell

/**
 * The song screen turned sideways: the whole bouzouki ([BouzoukiView]) edge to edge, the song's
 * video moved into the free room above its neck (for an audio file, its title and a play button
 * there instead), a status line under the neck with the note now and the time, and ⤺ to go
 * back upright. [SongActivity] makes one, lets it follow the screen ([follow]) and drives its
 * bouzouki with the same clock as the neck ([live], [next], [status]).
 *
 * While it shows, the upright screen stays underneath, hidden and untouched, so turning back
 * finds it as it was. The video moves without ever leaving the window ([Dock]), so it plays on.
 * The status bar is hidden while sideways (a swipe brings it back for a moment); the instrument
 * keeps clear of the navigation bar and of cut-outs. Back turns the screen upright, as ⤺ does
 * ([back]), instead of closing the song.
 */
internal class SongLandscape(
    private val activity: Activity,
    synth: BouzoukiSynth,
    settings: Settings,
    /** The song's video (the YouTube player), in a [Dock] on the upright screen. */
    private val video: View,
    private val title: () -> CharSequence,
    /** The upright screen's status message, shown in the status line while no song is ready. */
    private val message: () -> CharSequence,
    /** Whether an audio file is open (no video: its title and a play button go in the video's place). */
    private val fileOpen: () -> Boolean,
    private val filePlaying: () -> Boolean,
    private val playPause: () -> Unit,
) {
    private val ui = Ui(activity)
    private val dp = ui.dp
    private val main = Handler(Looper.getMainLooper())
    /** The instrument, set up as the neck is; its dromos and instrument follow the neck's. */
    val bouzouki = BouzoukiView(activity, synth).apply {
        instrument = settings.instrument
        dromos = settings.dromos
        rootPc = settings.rootPc
        names = settings.names
        showAllNotes = settings.showAllNotes
    }
    /** Whether the sideways screen shows. */
    var shown = false
        private set

    private val videoDock = Dock(activity).apply { setBackgroundColor(0xFF000000.toInt()) }
    private val titleText = TextView(activity).apply {
        setTextColor(0xFFF2E6C8.toInt())
        textSize = 16f
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        gravity = Gravity.CENTER
        maxLines = 3
        ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(3 * dp, 0f, dp, 0xC0000000.toInt())
    }
    private val playButton = ui.chip("▶ Play") { playPause(); refresh() }
    private val titleBox = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply { cornerRadius = 10 * dp; setColor(0x66000000); setStroke(ui.px(1), 0x40F2E6C8) }
        setPadding(ui.px(10), ui.px(6), ui.px(10), ui.px(6))
        addView(titleText)
        addView(playButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.px(6) })
    }
    private val statusText = TextView(activity).apply {
        setTextColor(0xFFF2E6C8.toInt())
        textSize = 14f
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        gravity = Gravity.CENTER_VERTICAL
        setPadding(ui.px(10), 0, ui.px(10), 0)
        background = GradientDrawable().apply { cornerRadius = 14 * dp; setColor(0x73000000) }
        setShadowLayer(2 * dp, 0f, dp, 0xC0000000.toInt())
        if (Build.VERSION.SDK_INT >= 26) setAutoSizeTextTypeUniformWithConfiguration(9, 14, 1, TypedValue.COMPLEX_UNIT_SP)
    }
    private val back = ui.chip("⤺") { turnUpright() }.apply {
        textSize = 20f
        contentDescription = "Back to the upright screen"
    }
    private val root = Frame(activity)

    // Where the video lives on the upright screen, to put it back there.
    private var videoHome: ViewGroup? = null
    private var videoHomeParams: ViewGroup.LayoutParams? = null
    // The upright screen's views hidden meanwhile, and its scroll positions.
    private val hidden = ArrayList<View>()
    private val scrolls = ArrayList<Pair<ScrollView, Int>>()
    private var cutoutMode = 0
    private var note: CharSequence? = null
    private var media = 0.0
    private var playing = false
    private var liveTremolo = false
    private var shownText: String? = null
    /** Whether the activity is on screen (started): the once-a-second [ticker] runs only then. */
    private var started = false
    /** Back while sideways, with Android 13's own back handling (when the app opts in to it): turns upright. */
    private val backCallback: Any? = if (Build.VERSION.SDK_INT >= 33) OnBackInvokedCallback { turnUpright() } else null
    /** Memory running short, or the app out of sight: the painting kept for turning back is let go. */
    private val memory = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) { if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) bouzouki.dropKept() }
        @Deprecated("Deprecated in Java")
        override fun onLowMemory() = bouzouki.dropKept()
        override fun onConfigurationChanged(newConfig: Configuration) {}
    }

    init {
        root.setBackgroundColor(0xFF22170F.toInt())
        root.addView(bouzouki)
        root.addView(videoDock)
        root.addView(titleBox)
        root.addView(statusText)
        root.addView(back)
        root.setOnApplyWindowInsetsListener { _, insets ->
            // Kept clear of the navigation bar, cut-outs and a window's caption bar; the status bar is hidden while
            // sideways (a swipe shows it over the screen for a moment), so it doesn't count.
            val (l, t, r, b) = if (Build.VERSION.SDK_INT >= 30) {
                val i = insets.getInsets(WindowInsets.Type.navigationBars() or WindowInsets.Type.displayCutout() or WindowInsets.Type.captionBar())
                intArrayOf(i.left, i.top, i.right, i.bottom)
            } else {
                @Suppress("DEPRECATION")
                intArrayOf(insets.systemWindowInsetLeft, 0, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            bouzouki.setPadding(l, t, r, b)
            root.requestLayout()
            insets
        }
        activity.application.registerComponentCallbacks(memory)
        activity.application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) {
                if (a !== activity) return
                started = true
                watchSensor()
                if (shown) { main.removeCallbacks(ticker); ticker.run() }
            }
            override fun onActivityStopped(a: Activity) {
                if (a !== activity) return
                started = false
                sensor.disable()
                main.removeCallbacks(ticker)
            }
            override fun onActivityDestroyed(a: Activity) {
                if (a !== activity) return
                started = false
                sensor.disable()
                main.removeCallbacks(ticker)
                unregisterBack()
                shown = false
                a.application.unregisterActivityLifecycleCallbacks(this)
                a.application.unregisterComponentCallbacks(memory)
                // The song screen is closed (not just made again for a new look): its painting needn't wait for it.
                if (!a.isChangingConfigurations) bouzouki.dropKept()
            }
            override fun onActivityCreated(a: Activity, state: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, state: Bundle) {}
        })
    }

    /** Shows the sideways screen when the screen is turned so (wide enough for the whole instrument), else the upright one. */
    fun follow(config: Configuration) {
        val wide = config.orientation == Configuration.ORIENTATION_LANDSCAPE && config.screenWidthDp >= config.screenHeightDp * 1.3f
        if (wide) show() else hide()
    }

    /** The note now (hammered, pulled or slid [from] another on its course, or picked again and again as a [tremolo]), or none. */
    fun live(from: Cell?, cell: Cell?, tremolo: Boolean) {
        liveTremolo = tremolo && cell != null
        bouzouki.legatoFrom = from
        bouzouki.tremolo = liveTremolo && playing
        bouzouki.liveCell = cell
    }

    fun next(cell: Cell?) {
        bouzouki.nextCell = cell
    }

    /**
     * The status line: the [note] now (null while no song is ready: the upright screen's message then) and the
     * time [media]. A tremolo note's string shimmers only while the song is [playing].
     */
    fun status(note: CharSequence?, media: Double, playing: Boolean) {
        this.playing = playing
        bouzouki.tremolo = liveTremolo && playing
        val same = TextUtils.equals(note, this.note) && media.toInt() == this.media.toInt()
        this.note = note
        this.media = media
        if (shown && !same) refresh()
    }

    /**
     * Once a second while sideways and on screen, for what changes without a frame to show it (a song being prepared,
     * a file's player). Stopped while the activity is stopped, and for good once it's destroyed.
     */
    private val ticker = object : Runnable {
        override fun run() {
            if (!shown || !started) return
            refresh()
            main.postDelayed(this, 1000)
        }
    }

    /** 🪕: turns the screen sideways now; it follows the phone again once the phone is turned sideways too. */
    fun turnSideways() = lock(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)

    /** ⤺: turns the screen upright now; it follows the phone again once the phone is held upright too. */
    fun turnUpright() = lock(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)

    /** Back: while sideways it turns the screen upright (as ⤺ does) and returns true; upright, false (the song screen closes). */
    fun back(): Boolean {
        if (!shown) return false
        turnUpright()
        return true
    }

    private fun show() {
        if (shown) return
        shown = true
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        hidden.clear()
        scrolls.clear()
        for (k in 0 until content.childCount) content.getChildAt(k).takeIf { it !== root && it.visibility == View.VISIBLE }?.let {
            hidden.add(it)
            keepScrolls(it)
        }
        if (root.parent == null) content.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.visibility = View.VISIBLE
        for (v in hidden) v.visibility = View.GONE
        fullScreen(true)
        (video.parent as? ViewGroup)?.let { home ->
            if (home !== videoDock) { videoHome = home; videoHomeParams = video.layoutParams }
        }
        videoDock.take(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.requestApplyInsets()
        if (Build.VERSION.SDK_INT >= 33) activity.onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT, backCallback as OnBackInvokedCallback)
        main.removeCallbacks(ticker)
        if (started) ticker.run() else refresh()
    }

    private fun hide() {
        if (!shown) return
        shown = false
        main.removeCallbacks(ticker)
        unregisterBack()
        val home = videoHome
        val params = videoHomeParams
        if (home is Dock && params != null) home.take(video, params)
        else if (home != null && params != null) { videoDock.removeView(video); home.addView(video, params) }
        for (v in hidden) v.visibility = View.VISIBLE
        hidden.clear()
        fullScreen(false)
        // Gone from the window, the bouzouki lets its painting go; it's kept for turning back until memory runs
        // short, the app is out of sight or the song screen closes ([memory]).
        (root.parent as? ViewGroup)?.removeView(root)
        restoreScrolls()
    }

    private fun unregisterBack() {
        if (Build.VERSION.SDK_INT >= 33) activity.onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback as OnBackInvokedCallback)
    }

    /**
     * Scrolls the upright screen back to where it was, once it's laid out at its upright size (the window may
     * still be sideways for a moment after the turn is announced; laid out then, its scroll would be cut short).
     */
    private fun restoreScrolls() {
        val kept = ArrayList(scrolls)
        scrolls.clear()
        if (kept.isEmpty()) return
        val decor = activity.window.decorView
        decor.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                val config = activity.resources.configuration
                if (!shown && (decor.width > decor.height) != (config.screenWidthDp > config.screenHeightDp)) return
                decor.viewTreeObserver.removeOnGlobalLayoutListener(this)
                if (!shown) for ((v, y) in kept) v.scrollTo(v.scrollX, y) // (turned sideways again meanwhile: nothing to do)
            }
        })
        decor.requestLayout()
    }

    /** Remembers where the scrolling views under [v] are scrolled to, to scroll them back there on the way back. */
    private fun keepScrolls(v: View) {
        if (v is ScrollView) scrolls.add(v to v.scrollY)
        if (v is ViewGroup) for (k in 0 until v.childCount) keepScrolls(v.getChildAt(k))
    }

    /** What the screen shows of the song: the video or the title (and a play button), and the status line. */
    private fun refresh() {
        val hasVideo = video.visibility == View.VISIBLE
        videoDock.visibility = if (hasVideo) View.VISIBLE else View.GONE
        titleBox.visibility = if (hasVideo) View.GONE else View.VISIBLE
        if (!hasVideo) {
            val t = title()
            if (titleText.text.toString() != t.toString()) titleText.text = t
            val file = fileOpen()
            playButton.visibility = if (file) View.VISIBLE else View.GONE
            if (file) {
                val label = if (filePlaying()) "⏸ Pause" else "▶ Play"
                if (playButton.text.toString() != label) playButton.text = label
            }
        }
        val n = note
        val text = when {
            n != null -> {
                val sec = media.coerceAtLeast(0.0).toInt()
                "$n   ·   %d:%02d".format(sec / 60, sec % 60)
            }
            // Nothing open: a song is opened on the upright screen (its link, saved songs, files), not here.
            !hasVideo && !fileOpen() -> "Turn the phone upright (or ⤺) to open a song: a YouTube link, a saved song or an audio file."
            else -> message().toString().lineSequence().firstOrNull().orEmpty()
        }
        if (text != shownText) { shownText = text; statusText.text = text }
    }

    /** Hides the status bar (and lets the instrument run into a cut-out's edge) while sideways. */
    private fun fullScreen(on: Boolean) {
        val w = activity.window
        if (Build.VERSION.SDK_INT >= 28) {
            val a = w.attributes
            if (on) { cutoutMode = a.layoutInDisplayCutoutMode; a.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
            else a.layoutInDisplayCutoutMode = cutoutMode
            w.attributes = a
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val c = w.insetsController ?: return
            if (on) {
                c.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                c.hide(WindowInsets.Type.statusBars())
            } else c.show(WindowInsets.Type.statusBars())
        } else {
            @Suppress("DEPRECATION")
            if (on) w.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN) else w.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }
    }

    // ---- Turning: a button turns the screen at once; it follows the phone's own turning again once the phone agrees. ----

    /**
     * The orientation a button asked for, kept until the phone is held that way too (or for good with auto-rotate off).
     * The system keeps it on the activity when the screen is made again (dark mode or font size changed, or the app
     * was closed meanwhile), so a new screen picks it up and follows the phone from there.
     */
    private var locked = activity.requestedOrientation.takeIf {
        it == ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE || it == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    } ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    /** Whether the device's own upright position is landscape (most tablets): the sensor counts degrees from there. */
    private var naturalLandscape = false

    private val sensor = object : OrientationEventListener(activity) {
        override fun onOrientationChanged(deg: Int) {
            if (deg == ORIENTATION_UNKNOWN) return // lying flat (on a music stand, say): the screen stays as asked
            val d = if (naturalLandscape) (deg + 90) % 360 else deg // degrees from holding it as a phone, upright
            val sideways = d in 60..120 || d in 240..300
            val upright = d <= 30 || d >= 330 || d in 150..210
            val agrees = if (locked == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) upright else sideways
            if (agrees && autoRotate()) unlock()
        }
    }

    private fun lock(orientation: Int) {
        locked = orientation
        activity.requestedOrientation = orientation
        // Without a way to tell how the phone is held, upright is where it goes back to by itself anyway.
        if (!sensor.canDetectOrientation()) { if (orientation == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) main.post { unlock() } }
        else watchSensor()
    }

    private fun unlock() {
        if (locked == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED) return
        locked = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        sensor.disable()
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER
    }

    init {
        // A lock kept from before the screen was made again: without a sensor, upright is where it goes back to anyway.
        if (locked == ActivityInfo.SCREEN_ORIENTATION_PORTRAIT && !sensor.canDetectOrientation()) main.post { unlock() }
    }

    private fun watchSensor() {
        if (locked == ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED || !sensor.canDetectOrientation()) return
        naturalLandscape = naturalLandscape()
        sensor.enable()
    }

    /** Whether the display, turned as it is now, would be landscape unturned: then the device's natural position is landscape. */
    private fun naturalLandscape(): Boolean {
        @Suppress("DEPRECATION")
        fun windowRotation() = try { activity.windowManager.defaultDisplay.rotation } catch (_: Exception) { Surface.ROTATION_0 }
        val rotation = if (Build.VERSION.SDK_INT < 30) windowRotation()
        else try { activity.display?.rotation ?: windowRotation() } catch (_: Exception) { windowRotation() }
        val turned = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270
        val landscape = activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        return landscape != turned
    }

    /** Whether the phone turns the screen by itself (auto-rotate on); with it off, a button's choice stays. */
    private fun autoRotate() = try {
        android.provider.Settings.System.getInt(activity.contentResolver, android.provider.Settings.System.ACCELEROMETER_ROTATION, 0) == 1
    } catch (_: Exception) { false }

    /**
     * Lays the bouzouki over all of it and the rest in the room the instrument leaves free: the video (or the
     * title) in its video slot, the status line in its status slot, ⤺ in a top corner.
     */
    private inner class Frame(context: Context) : ViewGroup(context) {
        private val videoRect = Rect()
        private val statusRect = Rect()
        private val backRect = Rect()

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            val h = MeasureSpec.getSize(heightMeasureSpec)
            setMeasuredDimension(w, h)
            bouzouki.measure(exactly(w), exactly(h))
            val sh = bouzouki.shapeFor(w, h)
            if (sh == null) { videoRect.setEmpty(); statusRect.setEmpty(); backRect.setEmpty() }
            else {
                sh.video.roundOut(videoRect)
                sh.status.roundOut(statusRect)
                val m = (8 * dp).toInt()
                val size = ui.px(44)
                val right = w - sh.pad.right - m
                backRect.set(right - size, sh.pad.top + m, right, sh.pad.top + m + size)
                if (Rect.intersects(backRect, videoRect)) backRect.offsetTo(sh.pad.left + m, backRect.top)
            }
            for ((v, r) in listOf(videoDock to videoRect, titleBox to videoRect, back to backRect)) v.measure(exactly(r.width()), exactly(r.height()))
            // The status line is as long as its text, up to its slot's length.
            statusText.measure(MeasureSpec.makeMeasureSpec(statusRect.width(), MeasureSpec.AT_MOST), exactly(statusRect.height()))
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
            bouzouki.layout(0, 0, r - l, b - t)
            videoDock.layout(videoRect.left, videoRect.top, videoRect.right, videoRect.bottom)
            titleBox.layout(videoRect.left, videoRect.top, videoRect.right, videoRect.bottom)
            statusText.layout(statusRect.left, statusRect.top, statusRect.left + statusText.measuredWidth, statusRect.bottom)
            back.layout(backRect.left, backRect.top, backRect.right, backRect.bottom)
        }

        private fun exactly(size: Int) = MeasureSpec.makeMeasureSpec(size.coerceAtLeast(0), MeasureSpec.EXACTLY)
    }
}

/**
 * A frame a playing view (the song's video) can move into from another [Dock] without leaving the
 * window: it is never detached on the way, so a WebView neither reloads nor stops. (Between frames
 * not both in the window it is simply removed and added.)
 */
internal class Dock(context: Context) : FrameLayout(context) {
    /** Makes [v] this frame's child, laid out with [params]. */
    fun take(v: View, params: ViewGroup.LayoutParams) {
        val from = v.parent
        if (from === this) { v.layoutParams = params; return }
        if (from is Dock && from.isAttachedToWindow && isAttachedToWindow) {
            if (v.hasFocus()) v.clearFocus()
            from.letGo(v)
            attachViewToParent(v, -1, params)
            v.requestLayout()
            requestLayout()
            invalidate()
        } else {
            (from as? ViewGroup)?.removeView(v)
            addView(v, params)
        }
    }

    private fun letGo(v: View) {
        detachViewFromParent(v)
        requestLayout()
        invalidate()
    }
}
