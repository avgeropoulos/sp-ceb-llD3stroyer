package com.javelinstrike

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import com.javelinstrike.game.Game
import com.javelinstrike.game.Input
import com.javelinstrike.game.Storage
import com.javelinstrike.game.VIEW_W
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Hosts the game loop on its own thread and feeds it touches and motion sensors:
 * the gyroscope and linear accelerometer for throwing, and gravity for tilt-to-aim.
 */
@SuppressLint("ViewConstructor")
class GameView(private val activity: Activity) : SurfaceView(activity), SurfaceHolder.Callback, Runnable,
    SensorEventListener {
    private val input = Input()
    private val sound = SoundFx(activity)
    private val prefs = activity.getSharedPreferences("javelin", Context.MODE_PRIVATE)
    private val game = Game(input, sound, object : Storage {
        override fun load(key: String) = prefs.getInt(key, 0)
        override fun save(key: String, value: Int) { prefs.edit().putInt(key, value).apply() }
    }, AppUpdater(activity))
    private val gfx = CanvasGfx()

    private val sensors = activity.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val gyro: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val linear: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val gravity: Sensor? = sensors.getDefaultSensor(Sensor.TYPE_GRAVITY)
        ?: sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gx = FloatArray(3)
    private var displayRotation = Surface.ROTATION_0

    @Volatile private var running = false
    private var resumed = false
    private var surfaceReady = false
    private var thread: Thread? = null
    private val startMs = SystemClock.uptimeMillis()

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        game.motionAvailable = gyro != null
    }

    fun resume() {
        resumed = true
        listen(gyro, FAST_SENSOR_US)
        listen(linear, FAST_SENSOR_US)
        listen(gravity, SensorManager.SENSOR_DELAY_GAME)
        startLoop()
    }

    /**
     * 200 Hz is the fastest rate Android 12+ allows without an extra permission; asking for
     * SENSOR_DELAY_FASTEST there throws and crashes the app on launch.
     */
    private fun listen(sensor: Sensor?, periodUs: Int) {
        if (sensor == null) return
        try {
            sensors.registerListener(this, sensor, periodUs)
        } catch (_: SecurityException) {
            sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
        }
    }

    fun pause() {
        resumed = false
        sensors.unregisterListener(this)
        stopLoop()
    }

    fun release() = sound.release()

    /** The back button: pauses or leaves a level. Returns false on the title screen so the app closes. */
    fun onBack(): Boolean {
        if (game.screen == Game.Screen.TITLE) return false
        input.back = true
        return true
    }

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
        if (width > 0) game.viewH = height * VIEW_W / width
        display?.let { displayRotation = it.rotation }
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
                game.update(step)
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
                val scale = canvas.width / VIEW_W
                game.viewH = canvas.height / scale
                gfx.begin(canvas, scale)
                game.render(gfx)
            } finally {
                holder.unlockCanvasAndPost(canvas)
            }
            val elapsed = (System.nanoTime() - now) / 1_000_000L
            if (elapsed < 8) Thread.sleep(8 - elapsed)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val scale = width / VIEW_W
        if (scale <= 0f) return true
        val t = (e.eventTime - startMs) / 1000f
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> input.push(Input.DOWN, e.x / scale, e.y / scale, t)
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex
                input.push(Input.TAP, e.getX(i) / scale, e.getY(i) / scale, t)
            }
            MotionEvent.ACTION_MOVE -> input.push(Input.MOVE, e.x / scale, e.y / scale, t)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> input.push(Input.UP, e.x / scale, e.y / scale, t)
        }
        return true
    }

    override fun onSensorChanged(e: SensorEvent) {
        val v = e.values
        when (e.sensor.type) {
            Sensor.TYPE_GYROSCOPE -> game.detector.gyro(e.timestamp, v[0], v[1], v[2])
            Sensor.TYPE_LINEAR_ACCELERATION -> game.detector.push(v[0], v[1], v[2])
            Sensor.TYPE_GRAVITY -> updateTilt(v[0], v[1])
            Sensor.TYPE_ACCELEROMETER -> {
                // No gravity sensor: smooth the raw accelerometer to find which way is down.
                for (i in 0..2) gx[i] += (v[i] - gx[i]) * 0.1f
                updateTilt(gx[0], gx[1])
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {}

    /** Works out the steering-wheel tilt from gravity, in screen coordinates. */
    private fun updateTilt(dx: Float, dy: Float) {
        val (sx, sy) = when (displayRotation) {
            Surface.ROTATION_0 -> dx to dy
            Surface.ROTATION_90 -> -dy to dx
            Surface.ROTATION_180 -> -dx to -dy
            else -> dy to -dx
        }
        // Lying flat there is no clear "down" on the screen, so keep the last aim.
        if (hypot(sx, sy) < 3f) return
        input.tilt = atan2(-sx, sy)
        input.hasTilt = true
    }

    private companion object {
        const val FAST_SENSOR_US = 5_000
    }
}
