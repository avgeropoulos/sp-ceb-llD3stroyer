package com.bouzouki

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * 🎧 Listen: records a song playing on the phone, in memory only, to transcribe it. With Android's playback capture
 * (Android 10+, after Android's own consent prompt) it hears what other apps play, as they play it; where that is
 * blocked (the playing app doesn't allow capture, so only silence comes) or not available, it listens with the
 * microphone instead. Recording starts with the sound (waiting up to [Request.wait] seconds) and lasts
 * [Request.seconds]. The clip goes straight to [SongJobs] (never to a file) unless it's a [Request.test].
 * [Report] says how it went, for 🔧 Lab tools.
 */
object Listen {
    enum class Mode { PLAYBACK, MICROPHONE }

    data class Request(val mode: Mode, val seconds: Double, val wait: Double, val solo: Boolean, val test: Boolean = false)

    /** How a listen went: which source, whether playback capture worked, the levels heard. */
    data class Report(
        val requested: Mode,
        val used: Mode?,
        val captureAvailable: Boolean,
        val captureSilent: Boolean?,
        val seconds: Double,
        val peakDb: Double,
        val rmsDb: Double,
        val playing: List<String>,
        val error: String? = null,
    ) {
        override fun toString() = buildString {
            append("Asked for: ${if (requested == Mode.PLAYBACK) "the phone's playback" else "the microphone"}\n")
            append("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}), ${Build.MANUFACTURER} ${Build.MODEL}\n")
            append("Playback capture available: ${if (captureAvailable) "yes" else "no (needs Android 10)"}\n")
            when (captureSilent) {
                true -> append("Playback capture: only silence came — the playing app doesn't allow capture (or nothing played)\n")
                false -> append("Playback capture: WORKS — sound captured from what plays\n")
                null -> {}
            }
            append("Used: ${when (used) { Mode.PLAYBACK -> "playback capture"; Mode.MICROPHONE -> "the microphone"; null -> "nothing" }}\n")
            append(String.format(Locale.US, "Captured %.1f s, peak %.0f dBFS, level %.0f dBFS\n", seconds, peakDb, rmsDb))
            append("Playing on the phone then: ${if (playing.isEmpty()) "nothing Android reports" else playing.joinToString(", ")}\n")
            if (error != null) append("Error: $error\n")
        }
    }

    sealed interface State {
        data object Idle : State
        data class Waiting(val mode: Mode, val leftToWait: Double) : State
        data class Recording(val mode: Mode, val done: Double, val total: Double) : State
        data class Finished(val report: Report, val songId: String?) : State
    }

    @Volatile var state: State = State.Idle
        private set
    @Volatile internal var cancel = false
    private val listeners = LinkedHashSet<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun listen(l: (State) -> Unit) { listeners += l; l(state) }
    fun unlisten(l: (State) -> Unit) { listeners -= l }
    internal fun publish(s: State) { state = s; main.post { for (l in listeners.toList()) l(s) } }

    fun busy() = state is State.Waiting || state is State.Recording

    /** Android's consent prompt for playback capture; its result goes to [start] (with [Mode.PLAYBACK]). */
    fun consentIntent(context: Context): Intent =
        (context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).createScreenCaptureIntent()

    /** Starts listening (in [ListenService], so it goes on while another app plays the song). */
    fun start(activity: Activity, request: Request, consentResult: Int = Activity.RESULT_CANCELED, consent: Intent? = null) {
        cancel = false
        publish(State.Waiting(request.mode, request.wait))
        val i = Intent(activity, ListenService::class.java)
            .putExtra(EXTRA_MODE, request.mode.name).putExtra(EXTRA_SECONDS, request.seconds).putExtra(EXTRA_WAIT, request.wait)
            .putExtra(EXTRA_SOLO, request.solo).putExtra(EXTRA_TEST, request.test)
            .putExtra(EXTRA_RESULT, consentResult)
        if (consent != null) i.putExtra(EXTRA_CONSENT, consent)
        activity.startForegroundService(i)
    }

    fun stop() { cancel = true }

    /** What the phone reports playing now (usages only: Android doesn't tell which app). */
    fun playing(context: Context): List<String> =
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).activePlaybackConfigurations.map { c ->
            when (c.audioAttributes.usage) {
                AudioAttributes.USAGE_MEDIA -> "media"
                AudioAttributes.USAGE_GAME -> "game"
                AudioAttributes.USAGE_UNKNOWN -> "unknown"
                AudioAttributes.USAGE_NOTIFICATION -> "notification"
                AudioAttributes.USAGE_VOICE_COMMUNICATION -> "call"
                else -> "usage ${c.audioAttributes.usage}"
            }
        }

    internal const val EXTRA_MODE = "mode"
    internal const val EXTRA_SECONDS = "seconds"
    internal const val EXTRA_WAIT = "wait"
    internal const val EXTRA_SOLO = "solo"
    internal const val EXTRA_TEST = "test"
    internal const val EXTRA_RESULT = "result"
    internal const val EXTRA_CONSENT = "consent"
    /** Sound above this (dBFS) starts the recording. */
    internal const val START_DB = -50.0
    const val RATE = 44100
}

/** The foreground service that does the listening ([Listen]): playback capture and/or the microphone. */
class ListenService : Service() {
    private var projection: MediaProjection? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val mode = Listen.Mode.valueOf(intent?.getStringExtra(Listen.EXTRA_MODE) ?: Listen.Mode.MICROPHONE.name)
        val seconds = intent?.getDoubleExtra(Listen.EXTRA_SECONDS, 60.0) ?: 60.0
        val wait = intent?.getDoubleExtra(Listen.EXTRA_WAIT, 30.0) ?: 30.0
        val solo = intent?.getBooleanExtra(Listen.EXTRA_SOLO, false) ?: false
        val test = intent?.getBooleanExtra(Listen.EXTRA_TEST, false) ?: false
        val result = intent?.getIntExtra(Listen.EXTRA_RESULT, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val consent: Intent? = if (Build.VERSION.SDK_INT >= 33) intent?.getParcelableExtra(Listen.EXTRA_CONSENT, Intent::class.java)
            else intent?.getParcelableExtra(Listen.EXTRA_CONSENT)
        val capture = mode == Listen.Mode.PLAYBACK && Build.VERSION.SDK_INT >= 29 && consent != null && result == Activity.RESULT_OK
        // The service is in the foreground (a notification) before the capture starts, as Android requires.
        val n = notification("Listening… play the song now", 0)
        // (The microphone type exists from Android 11; on 10 a capture is typed as such and the microphone needs none.)
        val mic = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
        val type = (if (capture) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0) or mic
        if (Build.VERSION.SDK_INT >= 29 && type != 0) startForeground(ID, n, type) else startForeground(ID, n)
        if (capture) projection = (getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager).getMediaProjection(result, consent!!)
        thread(name = "listen") { run(mode, seconds, wait, solo, test) }
        return START_NOT_STICKY
    }

    private fun run(mode: Listen.Mode, seconds: Double, wait: Double, solo: Boolean, test: Boolean) {
        val playing = Listen.playing(this)
        var silent: Boolean? = null
        var used: Listen.Mode? = null
        var audio: Array<FloatArray>? = null
        var error: String? = null
        try {
            val p = projection
            if (p != null) {
                val got = record(playbackRecord(p), seconds, wait, Listen.Mode.PLAYBACK)
                silent = got == null
                if (got != null) { audio = got; used = Listen.Mode.PLAYBACK }
            }
            if (audio == null && !Listen.cancel) {
                // No capture (refused, Android 9, or only silence came): the microphone.
                audio = record(micRecord(), seconds, if (silent == true) 2.0 else wait, Listen.Mode.MICROPHONE)?.also { used = Listen.Mode.MICROPHONE }
            }
        } catch (t: Throwable) {
            error = "${t.javaClass.simpleName}: ${t.message}"
        }
        val a = audio
        val (peak, rms) = levels(a)
        val report = Listen.Report(mode, used, Build.VERSION.SDK_INT >= 29, silent, (a?.get(0)?.size ?: 0).toDouble() / Listen.RATE,
            peak, rms, playing, error)
        var id: String? = null
        if (a != null && !test && !Listen.cancel) {
            val time = SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date())
            val source = SongJobs.Source.Clip("clip-" + System.currentTimeMillis(), "Heard $time", a[0], a[1], solo)
            id = SongJobs.idFor(source)
            if (!SongJobs.isBusy()) SongJobs.start(this, source, Settings(this).instrument) else error = "another song is being prepared"
        }
        Listen.publish(Listen.State.Finished(report.copy(error = error), id))
        projection?.stop()
        projection = null
        Handler(Looper.getMainLooper()).post {
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
        }
    }

    @SuppressLint("MissingPermission", "NewApi")
    private fun playbackRecord(p: MediaProjection): AudioRecord {
        val config = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .build()
        return AudioRecord.Builder()
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(Listen.RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
            .setBufferSizeInBytes(Listen.RATE * 2 * 4)
            .setAudioPlaybackCaptureConfig(config)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun micRecord(): AudioRecord {
        val source = if (MicRecorder.unprocessed(this)) android.media.MediaRecorder.AudioSource.UNPROCESSED else android.media.MediaRecorder.AudioSource.MIC
        return AudioRecord.Builder()
            .setAudioSource(source)
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_FLOAT).setSampleRate(Listen.RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build())
            .setBufferSizeInBytes(Listen.RATE * 2 * 4)
            .build()
    }

    /**
     * Waits up to [wait] seconds for sound, then records [seconds] of it from [rec] (stereo, kept in memory); null if
     * only silence came (or it was cancelled before any sound).
     */
    private fun record(rec: AudioRecord, seconds: Double, wait: Double, mode: Listen.Mode): Array<FloatArray>? {
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); throw IllegalStateException("${mode.name.lowercase()} recording isn't available") }
        val frames = (seconds * Listen.RATE).toInt()
        val l = FloatArray(frames); val r = FloatArray(frames)
        val buf = FloatArray(Listen.RATE / 10 * 2)
        var n = 0
        var waited = 0
        val maxWait = (wait * Listen.RATE).toInt()
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        try {
            rec.startRecording()
            while (n < frames && !Listen.cancel) {
                val got = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (got <= 0) break
                val pairs = got / 2
                if (n == 0) {
                    var s = 0.0
                    for (i in 0 until got) s += buf[i] * buf[i]
                    val db = 10 * log10(s / got + 1e-20)
                    if (db < Listen.START_DB) {
                        waited += pairs
                        Listen.publish(Listen.State.Waiting(mode, (maxWait - waited).toDouble() / Listen.RATE))
                        if (waited >= maxWait) return null
                        continue
                    }
                }
                val take = minOf(pairs, frames - n)
                for (i in 0 until take) { l[n + i] = buf[2 * i]; r[n + i] = buf[2 * i + 1] }
                n += take
                Listen.publish(Listen.State.Recording(mode, n.toDouble() / Listen.RATE, seconds))
                if (n % (Listen.RATE) < pairs) nm.notify(ID, notification("Listening: ${n / Listen.RATE} of ${seconds.toInt()} s", (100L * n / frames).toInt()))
            }
        } finally {
            try { rec.stop() } catch (_: IllegalStateException) {}
            rec.release()
        }
        if (n == 0) return null
        return if (n == frames) arrayOf(l, r) else arrayOf(l.copyOf(n), r.copyOf(n))
    }

    private fun levels(a: Array<FloatArray>?): Pair<Double, Double> {
        if (a == null || a[0].isEmpty()) return -120.0 to -120.0
        var peak = 0f; var s = 0.0
        for (ch in a) for (x in ch) { peak = max(peak, kotlin.math.abs(x)); s += x * x }
        return 20 * log10(peak + 1e-9) to 20 * log10(sqrt(s / (2 * a[0].size)) + 1e-9)
    }

    private fun notification(text: String, percent: Int): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Listening", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, SongActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Μπουζούκι Lab · listening (on the phone only)")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setProgress(100, percent, false)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        Listen.cancel = true
        projection?.stop()
        super.onDestroy()
    }

    private companion object {
        const val CHANNEL = "listen"
        const val ID = 12
    }
}
