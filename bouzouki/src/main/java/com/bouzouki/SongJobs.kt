package com.bouzouki

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.bouzouki.core.Instrument
import com.bouzouki.core.song.BasicPitch
import com.bouzouki.core.song.MdxSeparator
import com.bouzouki.core.song.SongAnalysis
import com.bouzouki.core.song.SongAnalyzer
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Prepares songs in the background (like Chordify): downloads the audio, removes the voice,
 * transcribes the bouzouki line and saves it, so it opens instantly the next time.
 * One song at a time; the song screen watches [state].
 */
object SongJobs {
    sealed interface State {
        data object Idle : State
        data class Working(val id: String, val step: String, val fraction: Float) : State
        data class Done(val song: SongAnalysis) : State
        data class Failed(val id: String, val message: String) : State
    }

    @Volatile var state: State = State.Idle
        private set
    private val listeners = LinkedHashSet<(State) -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancel = false
    private var worker: Thread? = null

    fun listen(l: (State) -> Unit) { listeners += l; l(state) }
    fun unlisten(l: (State) -> Unit) { listeners -= l }

    private fun publish(s: State) {
        state = s
        main.post { for (l in listeners.toList()) l(s) }
    }

    // --- the saved songs ---

    private fun songsDir(context: Context) = File(context.filesDir, "songs").apply { mkdirs() }

    fun cached(context: Context, id: String): SongAnalysis? =
        File(songsDir(context), "$id.json").takeIf { it.exists() }?.readText()?.let { SongAnalysis.fromJson(it) }

    /** Saved songs, newest first. */
    fun recent(context: Context): List<SongAnalysis> =
        (songsDir(context).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .mapNotNull { SongAnalysis.fromJson(it.readText()) }

    fun delete(context: Context, id: String) { File(songsDir(context), "$id.json").delete() }

    private fun save(context: Context, song: SongAnalysis) = File(songsDir(context), "${song.id}.json").writeText(song.toJson())

    // --- running a job ---

    sealed interface Source {
        data class YouTube(val videoId: String) : Source
        data class AudioFile(val uri: Uri, val name: String) : Source
    }

    fun isBusy() = worker?.isAlive == true

    /** Starts preparing [source]; the work runs in [PrepareService] so it survives leaving the app. */
    fun start(context: Context, source: Source, instrument: Instrument) {
        if (isBusy()) return
        cancel = false
        val app = context.applicationContext
        val id = when (source) { is Source.YouTube -> source.videoId; is Source.AudioFile -> "file-" + Integer.toHexString(source.uri.toString().hashCode()) }
        publish(State.Working(id, "Starting…", 0f))
        val service = Intent(app, PrepareService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(service) else app.startService(service)
        worker = thread(name = "song-prep") {
            try {
                val song = prepare(app, id, source, instrument) { step, f -> publish(State.Working(id, step, f)) }
                save(app, song)
                publish(State.Done(song))
            } catch (_: InterruptedException) {
                publish(State.Idle)
            } catch (e: Throwable) {
                publish(State.Failed(id, friendly(e)))
            } finally {
                app.stopService(service)
            }
        }
    }

    fun cancel() { cancel = true }

    private fun friendly(e: Throwable): String = when {
        e is OutOfMemoryError -> "The song is too long for the phone's memory."
        e.javaClass.name.contains("ContentNotAvailable") || e.javaClass.name.contains("AgeRestricted") ->
            "YouTube won't let this video be read (private, age-restricted or blocked)."
        e.javaClass.name.contains("ReCaptcha") -> "YouTube is asking for a captcha. Try again later, or pick the song as an audio file."
        e is IOException -> e.message ?: "Network problem"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun prepare(context: Context, id: String, source: Source, instrument: Instrument, report: (String, Float) -> Unit): SongAnalysis {
        val cancelled = { cancel }
        val mdxFile = ensureModel(context) { report("Getting the voice-removal model (once, 67 MB)", it) }

        val title: String
        val audio: AudioDecoder.Stereo
        when (source) {
            is Source.YouTube -> {
                report("Downloading the song's audio", 0f)
                val song = YouTubeAudio.download(source.videoId, context.cacheDir, { report("Downloading the song's audio", it) }, cancelled)
                title = song.title
                try {
                    audio = AudioDecoder.decode(song.file, { report("Reading the audio", it) }, cancelled)
                } finally {
                    song.file.delete() // only the notes are kept
                }
            }
            is Source.AudioFile -> {
                title = source.name
                audio = AudioDecoder.decode(context, source.uri, { report("Reading the audio", it) }, cancelled)
            }
        }

        val env = OrtEnvironment.getEnvironment()
        val threads = Runtime.getRuntime().availableProcessors().coerceIn(2, 8)
        val options = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(threads)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val bpBytes = context.assets.open("basic_pitch.onnx").use { it.readBytes() }
        return env.createSession(mdxFile.path, options).use { mdx ->
            env.createSession(bpBytes, options).use { bp ->
                SongAnalyzer(MdxSeparator(env, mdx), BasicPitch(env, bp)).analyze(
                    id, title, audio.left, audio.right, instrument,
                    progress = { stage, f -> report(stage.label, f) },
                    cancelled = cancelled,
                )
            }
        }
    }

    /** The vocal-removal model, downloaded once into the app's files. */
    private fun ensureModel(context: Context, progress: (Float) -> Unit): File {
        val file = File(context.filesDir, "mdx_inst_hq3.onnx")
        if (file.exists() && file.length() == MdxSeparator.MODEL_BYTES) return file
        val part = File(context.filesDir, "mdx_inst_hq3.onnx.part")
        val conn = URL(MdxSeparator.MODEL_URL).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        if (conn.responseCode !in 200..299) throw IOException("Couldn't download the voice-removal model (${conn.responseCode})")
        val total = conn.contentLengthLong.takeIf { it > 0 } ?: MdxSeparator.MODEL_BYTES
        conn.inputStream.use { input ->
            part.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    if (cancel) throw InterruptedException()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    progress(done.toFloat() / total)
                }
            }
        }
        if (part.length() != MdxSeparator.MODEL_BYTES) { part.delete(); throw IOException("The model download was incomplete. Try again.") }
        part.renameTo(file)
        return file
    }
}

/**
 * Keeps the app alive while a song is being prepared, with a progress notification,
 * so you can leave the app during the few minutes it takes.
 */
class PrepareService : Service() {
    private val listener: (SongJobs.State) -> Unit = { update(it) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val n = notification("Preparing song…", 0f)
        when {
            Build.VERSION.SDK_INT >= 35 -> startForeground(ID, n, MEDIA_PROCESSING)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else -> startForeground(ID, n)
        }
        SongJobs.listen(listener)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        SongJobs.unlisten(listener)
        super.onDestroy()
    }

    private fun update(s: SongJobs.State) {
        if (s is SongJobs.State.Working) {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(ID, notification(s.step, s.fraction))
        }
    }

    private fun notification(text: String, fraction: Float): Notification {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Preparing songs", NotificationManager.IMPORTANCE_LOW))
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, SongActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        @Suppress("DEPRECATION")
        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) Notification.Builder(this, CHANNEL) else Notification.Builder(this)
        return b.setContentTitle("Μπουζούκι · preparing song")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, (fraction * 100).toInt(), false)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "prepare"
        private const val ID = 11
        /** ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING (Android 15). */
        private const val MEDIA_PROCESSING = 0x00002000
    }
}
