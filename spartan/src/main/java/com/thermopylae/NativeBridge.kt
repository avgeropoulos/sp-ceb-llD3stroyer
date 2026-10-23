package com.thermopylae

import android.annotation.TargetApi
import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.Display
import android.webkit.JavascriptInterface
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * `window.SpartanNative`: motion sensors, haptics, text-to-speech and orientation for the game.
 *
 * JavaScript calls arrive on the WebView's JavaBridge thread, sensor events on the "sensors"
 * HandlerThread, and lifecycle calls ([onPause], [onResume], [onNewPage], [release]) on the
 * UI thread. Every `@JavascriptInterface` body catches everything, so a bad call can never
 * take the app down.
 */
class NativeBridge(private val activity: Activity) {
    private val app: Context = activity.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())

    // Sensors. The four listeners all run on one dedicated thread.
    private val sensorThread = HandlerThread("sensors").apply { start() }
    private val sensorHandler = Handler(sensorThread.looper)
    private val sensorManager = app.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val gyro: Sensor? = defaultSensor(Sensor.TYPE_GYROSCOPE)
    private val linear: Sensor? = defaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravity: Sensor? = defaultSensor(Sensor.TYPE_GRAVITY)
    private val accel: Sensor? = defaultSensor(Sensor.TYPE_ACCELEROMETER)

    // Sample-and-hold values, touched only on the sensor thread.
    private val heldLinear = FloatArray(3) { Float.NaN }
    private val heldAccel = FloatArray(3) { Float.NaN }
    private val heldGravity = FloatArray(3) { Float.NaN }
    private val line = StringBuilder(192)
    private var lastGyroNs = 0L
    private var meanInterval = 0.0

    @Volatile private var rateHz = 0.0
    @Volatile private var sensing = false

    private val lock = Any()
    private val ring = HostLogic.LineRing(HostLogic.RING_LINES)

    // Wanted/registered state; changed from the JavaBridge and UI threads.
    private val stateLock = Any()
    private var wanted = false
    private var resumed = false
    private var registered = false
    @Volatile private var released = false

    /** Display rotation in degrees (0, 90, 180, 270), cached for the sensor thread. */
    @Volatile var rotationDeg = 0
        private set

    private val displayManager = app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == Display.DEFAULT_DISPLAY) updateRotation()
        }
    }

    private val vibrator: Vibrator? = findVibrator()
    private val amplitudeControl: Boolean = hasAmplitudeControl(vibrator)

    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    private val utterances = AtomicInteger()

    private val sensorListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = onSensor(event)
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}
    }

    init {
        updateRotation()
        try {
            displayManager?.registerDisplayListener(displayListener, mainHandler)
        } catch (e: Throwable) {
            Log.w(TAG, e)
        }
        initTts()
    }

    // ---- JavaScript interface ----

    /** True if the phone has a gyroscope. */
    @JavascriptInterface
    fun motionAvailable(): Boolean {
        try {
            return gyro != null
        } catch (e: Throwable) {
            Log.w(TAG, e)
            return false
        }
    }

    /** Starts streaming sensor lines. The wish survives pause/resume. */
    @JavascriptInterface
    fun startMotion() {
        try {
            synchronized(stateLock) {
                if (released) return
                if (!wanted) {
                    wanted = true
                    clearRing()
                }
                if (resumed) registerSensors()
            }
        } catch (e: Throwable) {
            Log.w(TAG, e)
        }
    }

    @JavascriptInterface
    fun stopMotion() {
        try {
            synchronized(stateLock) {
                wanted = false
                unregisterSensors()
            }
        } catch (e: Throwable) {
            Log.w(TAG, e)
        }
    }

    /** Every line since the last poll, oldest first, each ending in '\n'; "" if none. */
    @JavascriptInterface
    fun pollMotion(): String {
        try {
            synchronized(lock) { return ring.drain() }
        } catch (e: Throwable) {
            Log.w(TAG, e)
            return ""
        }
    }

    /** `{"gyroRange":rad/s,"accRange":m/s²,"rateHz":measured}`; unknown ranges are null. */
    @JavascriptInterface
    fun motionInfo(): String {
        try {
            return HostLogic.motionInfoJson(
                HostLogic.gyroRange(gyro?.maximumRange),
                HostLogic.accRange(accel?.maximumRange),
                rateHz,
            )
        } catch (e: Throwable) {
            Log.w(TAG, e)
            return "{\"gyroRange\":null,\"accRange\":null,\"rateHz\":0}"
        }
    }

    /** One pulse. Skips ms ≤ 0 or amplitude ≤ 0; clamps to 1..400 ms and 1..255. */
    @JavascriptInterface
    fun vibrate(ms: Int, amplitude: Int) {
        try {
            if (ms <= 0 || amplitude <= 0 || released) return
            val v = vibrator ?: return
            val duration = HostLogic.vibrationMs(ms)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amp = if (amplitudeControl) HostLogic.vibrationAmplitude(amplitude) else Api26.DEFAULT_AMPLITUDE
                Api26.oneShot(v, duration, amp)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(duration)
            }
        } catch (e: Throwable) {
            Log.w(TAG, e)
        }
    }

    /** A pattern "delay,on,off,on,…" in ms: at most 16 values of ≤ 400 ms each, no repeat. */
    @JavascriptInterface
    fun vibratePattern(csv: String?) {
        try {
            if (released) return
            val v = vibrator ?: return
            val pattern = HostLogic.parsePattern(csv) ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Api26.waveform(v, pattern)
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, -1)
            }
        } catch (e: Throwable) {
            Log.w(TAG, e)
        }
    }

    /** Speaks [text] at once (QUEUE_FLUSH). Dropped before TTS is ready or if no English voice exists. */
    @JavascriptInterface
    fun say(text: String?, pitch: Float, rate: Float) {
        try {
            val t = tts
            if (!ttsReady || t == null || text.isNullOrBlank()) return
            t.setPitch(if (pitch.isFinite() && pitch > 0f) pitch.coerceIn(0.25f, 4f) else 1f)
            t.setSpeechRate(if (rate.isFinite() && rate > 0f) rate.coerceIn(0.25f, 4f) else 1f)
            val max = TextToSpeech.getMaxSpeechInputLength()
            val s = if (text.length > max) text.substring(0, max) else text
            t.speak(s, TextToSpeech.QUEUE_FLUSH, null, "spartan-" + utterances.incrementAndGet())
        } catch (e: Throwable) {
            Log.w(TAG, e)
        }
    }

    @JavascriptInterface
    fun stopSpeech() {
        try {
            if (ttsReady) tts?.stop()
        } catch (e: Throwable) {
            Log.w(TAG, e)
        }
    }

    /** Display rotation in degrees. */
    @JavascriptInterface
    fun rotation(): Int {
        try {
            return rotationDeg
        } catch (e: Throwable) {
            Log.w(TAG, e)
            return 0
        }
    }

    /** Locks the current landscape side (during motion play), or allows both again. */
    @JavascriptInterface
    fun lockOrientation(lock: Boolean) {
        try {
            activity.runOnUiThread { setOrientationLocked(lock) }
        } catch (e: Throwable) {
            Log.w(TAG, e)
        }
    }

    /** Set by [MainActivity]; null until then. */
    @Volatile var updater: AppUpdater? = null

    /** App update state as JSON (see [AppUpdater.stateJson]), or "" if there is no updater. */
    @JavascriptInterface
    fun updateState(): String = try {
        updater?.stateJson() ?: ""
    } catch (e: Throwable) {
        Log.w(TAG, e)
        ""
    }

    /** Looks for a newer version of the app; the answer arrives as an `appupdate` event. */
    @JavascriptInterface
    fun checkForUpdate() {
        try {
            updater?.check()
        } catch (e: Throwable) {
            Log.w(TAG, e)
        }
    }

    /** Downloads the available update and opens Android's installer for the player to confirm. */
    @JavascriptInterface
    fun installUpdate() {
        try {
            updater?.install()
        } catch (e: Throwable) {
            Log.w(TAG, e)
        }
    }

    @JavascriptInterface
    fun exit() {
        try {
            activity.runOnUiThread { if (!activity.isFinishing) activity.finish() }
        } catch (e: Throwable) {
            Log.w(TAG, e)
        }
    }

    // ---- Lifecycle (UI thread) ----

    /** Unregisters sensors and silences speech and haptics. */
    fun onPause() {
        synchronized(stateLock) {
            resumed = false
            unregisterSensors()
        }
        try {
            if (ttsReady) tts?.stop()
            vibrator?.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, e)
        }
    }

    /** Refreshes the rotation, clears stale lines and re-registers sensors if they're wanted. */
    fun onResume() {
        updateRotation()
        synchronized(stateLock) {
            if (released) return
            resumed = true
            clearRing()
            if (wanted) registerSensors()
        }
    }

    /** A new document is loading (first load, reload or renderer recovery): drop the old page's state. */
    fun onNewPage() {
        synchronized(stateLock) {
            wanted = false
            unregisterSensors()
            clearRing()
        }
        try {
            if (ttsReady) tts?.stop()
            vibrator?.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, e)
        }
        setOrientationLocked(false)
    }

    /** Frees everything that outlives the Activity: sensors, display listener, TTS, the sensor thread. */
    fun release() {
        synchronized(stateLock) {
            released = true
            resumed = false
            wanted = false
            unregisterSensors()
        }
        try {
            displayManager?.unregisterDisplayListener(displayListener)
        } catch (e: Throwable) {
            Log.w(TAG, e)
        }
        ttsReady = false
        val t = tts
        tts = null
        try {
            t?.stop()
            t?.shutdown()
        } catch (e: Throwable) {
            Log.w(TAG, e)
        }
        try {
            vibrator?.cancel()
        } catch (e: Throwable) {
            Log.w(TAG, e)
        }
        sensorThread.quitSafely()
        mainHandler.removeCallbacksAndMessages(null)
        clearRing()
    }

    /** Re-reads the display rotation (DisplayListener on the main looper, onResume, config changes). */
    fun updateRotation() {
        val r = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Api30.rotation(activity) ?: displayManager?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            } else {
                @Suppress("DEPRECATION")
                activity.windowManager?.defaultDisplay?.rotation
            }
        } catch (e: Throwable) {
            Log.w(TAG, e)
            null
        }
        if (r != null) rotationDeg = (r and 3) * 90
    }

    // ---- Internals ----

    private fun defaultSensor(type: Int): Sensor? = try {
        sensorManager?.getDefaultSensor(type)
    } catch (e: Throwable) {
        Log.w(TAG, e)
        null
    }

    /** The phone's vibrator, or null if it has none. */
    private fun findVibrator(): Vibrator? = try {
        val v = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Api31.defaultVibrator(app)
        } else {
            @Suppress("DEPRECATION")
            app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (v != null && v.hasVibrator()) v else null
    } catch (e: Throwable) {
        Log.w(TAG, e)
        null
    }

    private fun hasAmplitudeControl(v: Vibrator?): Boolean = try {
        v != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Api26.hasAmplitudeControl(v)
    } catch (e: Throwable) {
        Log.w(TAG, e)
        false
    }

    private fun setOrientationLocked(lock: Boolean) {
        if (activity.isFinishing || activity.isDestroyed) return
        activity.requestedOrientation =
            if (lock) ActivityInfo.SCREEN_ORIENTATION_LOCKED else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
    }

    private fun clearRing() {
        synchronized(lock) { ring.clear() }
    }

    /** Caller holds [stateLock]. Gravity and accelerometers go first so the first gyro line has them. */
    private fun registerSensors() {
        if (registered || released) return
        val sm = sensorManager ?: return
        val g = gyro ?: return
        sensorHandler.post {
            heldLinear.fill(Float.NaN)
            heldAccel.fill(Float.NaN)
            heldGravity.fill(Float.NaN)
            lastGyroNs = 0L
        }
        sensing = true
        registered = true
        for (s in arrayOf(gravity, linear, accel, g)) {
            if (s == null) continue
            try {
                // 5000 µs = 200 Hz; never SENSOR_DELAY_FASTEST.
                if (!sm.registerListener(sensorListener, s, SAMPLING_US, 0, sensorHandler)) {
                    Log.w(TAG, "could not register sensor " + s.type)
                }
            } catch (e: Throwable) {
                Log.w(TAG, e)
            }
        }
    }

    /** Caller holds [stateLock]. */
    private fun unregisterSensors() {
        sensing = false
        if (!registered) return
        registered = false
        try {
            sensorManager?.unregisterListener(sensorListener)
        } catch (e: Throwable) {
            Log.w(TAG, e)
        }
    }

    /** Sensor thread. One line per gyroscope event; the other sensors are sample-and-hold. */
    private fun onSensor(event: SensorEvent) {
        if (!sensing) return
        val values = event.values ?: return
        when (event.sensor?.type) {
            Sensor.TYPE_GYROSCOPE -> {
                if (values.size < 3) return
                val ns = event.timestamp
                if (baseNs == Long.MIN_VALUE) baseNs = ns
                measureRate(ns)
                line.setLength(0)
                HostLogic.appendSensorLine(line, (ns - baseNs) / 1e9, values, heldLinear, heldAccel, heldGravity, rotationDeg)
                val s = line.toString()
                synchronized(lock) { ring.push(s) }
            }
            Sensor.TYPE_LINEAR_ACCELERATION -> HostLogic.copy3(values, heldLinear)
            Sensor.TYPE_ACCELEROMETER -> HostLogic.copy3(values, heldAccel)
            Sensor.TYPE_GRAVITY -> HostLogic.copy3(values, heldGravity)
        }
    }

    /** Smoothed gyro event rate; gaps (re-registration, stalls) are ignored. */
    private fun measureRate(ns: Long) {
        val last = lastGyroNs
        lastGyroNs = ns
        if (last == 0L) return
        val dt = (ns - last) / 1e9
        if (dt <= 0.0 || dt > 0.25) return
        meanInterval = if (meanInterval == 0.0) dt else meanInterval + 0.02 * (dt - meanInterval)
        rateHz = 1.0 / meanInterval
    }

    private fun initTts() {
        try {
            tts = TextToSpeech(app) { status -> onTtsInit(status) }
        } catch (e: Throwable) {
            Log.w(TAG, e)
            tts = null
        }
    }

    /** Main thread. British English, else American; with neither the game shows subtitles only. */
    private fun onTtsInit(status: Int) {
        val t = tts ?: return
        if (released || status != TextToSpeech.SUCCESS) return
        try {
            var result = t.setLanguage(Locale.UK)
            if (result < TextToSpeech.LANG_AVAILABLE) result = t.setLanguage(Locale.US)
            if (result < TextToSpeech.LANG_AVAILABLE) {
                Log.i(TAG, "no English TTS voice; subtitles only")
                return
            }
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            ttsReady = !released
        } catch (e: Throwable) {
            Log.w(TAG, e)
        }
    }

    @TargetApi(Build.VERSION_CODES.O)
    private object Api26 {
        const val DEFAULT_AMPLITUDE = VibrationEffect.DEFAULT_AMPLITUDE

        fun hasAmplitudeControl(v: Vibrator): Boolean = v.hasAmplitudeControl()

        fun oneShot(v: Vibrator, ms: Long, amplitude: Int) {
            v.vibrate(VibrationEffect.createOneShot(ms, amplitude))
        }

        fun waveform(v: Vibrator, timings: LongArray) {
            v.vibrate(VibrationEffect.createWaveform(timings, -1))
        }
    }

    @TargetApi(Build.VERSION_CODES.R)
    private object Api30 {
        fun rotation(activity: Activity): Int? = activity.display?.rotation
    }

    @TargetApi(Build.VERSION_CODES.S)
    private object Api31 {
        fun defaultVibrator(context: Context): Vibrator? =
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    }

    private companion object {
        const val TAG = "Spartan"
        const val SAMPLING_US = 5000

        /** Timestamp of the first gyro event since process start; t = 0 there, across stop/start. */
        @Volatile var baseNs = Long.MIN_VALUE
    }
}
