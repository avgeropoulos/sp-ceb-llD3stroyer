package com.bouzoukihero

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.bouzoukihero.core.Samples
import com.bouzoukihero.core.Synth
import kotlin.concurrent.thread

/** Receives what the app hears, as mono audio at [Synth.RATE]. */
fun interface AudioSink {
    fun onAudio(pcm: FloatArray)
}

/** Reads 16-bit audio from an [AudioRecord] on its own thread and passes it on as mono at [Synth.RATE]. */
private class Reader(private val rec: AudioRecord, private val rate: Int, private val channels: Int, private val sink: AudioSink) {
    @Volatile var running = true

    fun start() = thread(name = "listen", priority = Thread.MAX_PRIORITY) {
        val buf = ShortArray(2048 * channels)
        try {
            rec.startRecording()
            while (running) {
                val n = rec.read(buf, 0, buf.size)
                if (n <= 0) { Thread.sleep(5); continue }
                val frames = n / channels
                val mono = FloatArray(frames)
                for (f in 0 until frames) {
                    var s = 0f
                    for (c in 0 until channels) s += buf[f * channels + c] / 32768f
                    mono[f] = s / channels
                }
                sink.onAudio(if (rate == Synth.RATE) mono else Samples.resample(mono, rate, Synth.RATE))
            }
        } catch (_: Exception) {
        } finally {
            try { rec.stop() } catch (_: Exception) {}
            rec.release()
        }
    }
}

/** Listens through the microphone, for phones that can't capture the app's own sound. */
class MicListener(sink: AudioSink) {
    private val reader: Reader?

    init {
        val rate = 44100
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // VOICE_RECOGNITION skips echo cancellation, which would remove the music.
        @SuppressLint("MissingPermission")
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 2))
        } catch (_: Exception) {
            null
        }
        reader = if (rec != null && rec.state == AudioRecord.STATE_INITIALIZED) Reader(rec, rate, 1, sink).also { it.start() } else {
            rec?.release()
            null
        }
    }

    val ok get() = reader != null

    fun stop() { reader?.running = false }
}

/**
 * Captures the sound this app is playing (the YouTube video) straight from Android's
 * audio system: clean, digital, no microphone. Android requires a screen-capture
 * permission prompt and a foreground service for it (Android 10 and newer).
 */
class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var reader: Reader? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || intent == null) {
            fail("Capturing the app's sound needs Android 10 or newer.")
            return START_NOT_STICKY
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Listening to songs", NotificationManager.IMPORTANCE_LOW))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle("Bouzouki Hero is listening")
            .setContentText("Finding the bouzouki notes in the song")
            .setSmallIcon(R.drawable.ic_launcher)
            .build()
        try {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            val data: Intent? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
            else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_DATA)
            val mpm = getSystemService(MediaProjectionManager::class.java)
            val p = mpm.getMediaProjection(intent.getIntExtra(EXTRA_CODE, 0), data ?: throw IllegalStateException("no permission"))
                ?: throw IllegalStateException("no projection")
            projection = p
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, Handler(Looper.getMainLooper()))
            val config = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val rate = 44100
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(rate)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()
            @SuppressLint("MissingPermission")
            val rec = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(rate)
                .setAudioPlaybackCaptureConfig(config)
                .build()
            val s = sink ?: throw IllegalStateException("nobody listening")
            reader = Reader(rec, rate, 1, s).also { it.start() }
            Handler(Looper.getMainLooper()).post { started?.invoke() }
        } catch (e: Exception) {
            fail("Couldn't capture the app's sound (${e.message ?: e.javaClass.simpleName}).")
        }
        return START_NOT_STICKY
    }

    private fun fail(msg: String) {
        val f = failed
        Handler(Looper.getMainLooper()).post { f?.invoke(msg) }
        stopSelf()
    }

    override fun onDestroy() {
        reader?.running = false
        reader = null
        projection?.stop()
        projection = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
        private const val CHANNEL = "listen"

        /** Set by the screen that wants the audio, before starting the service. */
        @Volatile var sink: AudioSink? = null
        @Volatile var started: (() -> Unit)? = null
        @Volatile var failed: ((String) -> Unit)? = null
    }
}
