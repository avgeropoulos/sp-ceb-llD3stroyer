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
import com.bouzouki.core.song.AnalysisData
import com.bouzouki.core.song.MdxSeparator
import com.bouzouki.core.song.NoSeparation
import com.bouzouki.core.song.PostConfig
import com.bouzouki.core.song.Separator
import com.bouzouki.core.song.SongAnalysis
import com.bouzouki.core.song.SongAnalyzer
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Prepares songs in the background, all on the phone: reads the audio (a file of the player's), separates
 * the instruments, transcribes the bouzouki line and saves it, so it opens instantly the next time.
 * One song at a time; the song screen watches [state].
 * A song is kept in filesDir/songs as <id>.json (its line), <id>.data (what the line was derived
 * from: [AnalysisData], for more or fewer notes or lining up a tab, see [SongLines]) and, once a
 * tab is pasted or opened for it, the tab as it came: <id>.tab.txt (pasted, or a text file) or
 * <id>.tab.pdf (one or the other). Only the notes stay on the phone, never the audio, and nothing is uploaded:
 * any temporary audio lives in the app's cache only while a song is prepared ([sweep] clears what a crash left).
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

    /** Saved songs, newest first, those saved by an older version of the analysis too (not [SongAnalysis.Saved.current]). */
    fun recent(context: Context): List<SongAnalysis.Saved> =
        (songsDir(context).listFiles { f -> f.name.endsWith(".json") } ?: emptyArray())
            .sortedByDescending { it.lastModified() }
            .mapNotNull { SongAnalysis.saved(it.readText()) }

    /** Forgets a saved song: its line, what it was derived from, its tab and its settings. */
    fun delete(context: Context, id: String) {
        val dir = songsDir(context)
        for (name in listOf("$id.json", "$id.data", "$id.tab.txt", "$id.tab.pdf")) {
            File(dir, name).delete()
            File(dir, "$name.tmp").delete()
        }
        Settings(context).forgetSong(id)
    }

    /**
     * Saves a prepared song: what its line was derived from first, so a song file never comes
     * without its data file unless that one couldn't be written (the song then simply works
     * without detail levels and tabs). Each file is replaced whole, never left half written.
     */
    fun save(context: Context, song: SongAnalysis, data: AnalysisData?) {
        val dir = songsDir(context)
        val dataFile = File(dir, "${song.id}.data")
        if (data != null) {
            try { writeAtomically(dataFile) { data.write(it) } } catch (_: IOException) { dataFile.delete() }
        } else {
            dataFile.delete() // an older song's data doesn't belong to the new line
        }
        writeAtomically(File(dir, "${song.id}.json")) { it.write(song.toJson().toByteArray(Charsets.UTF_8)) }
    }

    /** What song [id]'s line was derived from, or null if it was prepared before that was kept (or it's damaged). */
    fun data(context: Context, id: String): AnalysisData? = try {
        File(songsDir(context), "$id.data").takeIf { it.exists() }?.let { f -> f.inputStream().use { AnalysisData.read(it.buffered()) } }
    } catch (_: IOException) {
        null
    }

    /**
     * Deletes song [id]'s data file, which couldn't be read, if it's still the one [stamp] names (not one
     * written since): the song then asks to be prepared again for tabs and detail levels.
     */
    fun dropData(context: Context, id: String, stamp: Long) {
        if (stamp != 0L && dataStamp(context, id) == stamp) File(songsDir(context), "$id.data").delete()
    }

    /**
     * [song] (as saved) with its line derived again from its data when an older version of the analysis derived it
     * ([SongAnalysis.isLineOld]), saved in its place, so the song gets the newer line without preparing it again; null
     * if its line is current or it has no data. Reads the data and follows the line again: call it off the main thread.
     */
    fun withCurrentLine(context: Context, song: SongAnalysis): SongAnalysis? {
        if (!SongAnalysis.isLineOld(song)) return null
        val data = data(context, song.id) ?: return null
        val fresh = try { SongAnalyzer.rebuild(song, data) } catch (_: Exception) { return null }
        try {
            writeAtomically(File(songsDir(context), "${song.id}.json")) { it.write(fresh.toJson().toByteArray(Charsets.UTF_8)) }
        } catch (_: IOException) {
            // shown all the same; derived again next time
        }
        return fresh
    }

    /** Whether song [id] has its [data] file: songs prepared before it was kept need preparing again for tabs and detail levels. */
    fun hasData(context: Context, id: String) = File(songsDir(context), "$id.data").exists()

    /** Which data file song [id] has (when it was written, and its size; 0 if there is none), to tell lines derived from an older one. */
    fun dataStamp(context: Context, id: String): Long = stamp(File(songsDir(context), "$id.data"))

    /** Which version of [file] it is: when it was written, and its size (0 if there is no such file). */
    fun stamp(file: File): Long {
        val time = file.lastModified()
        return if (time == 0L) 0L else time * 1_000_003 + file.length()
    }

    /**
     * The tab kept for song [id], or null: its text (read here: a tab's text is a few KB), or its PDF (only
     * found here: it's read when its line is worked out, off the main thread).
     */
    fun tab(context: Context, id: String): SongLines.TabSource? {
        val dir = songsDir(context)
        val pdf = File(dir, "$id.tab.pdf").takeIf { it.isFile }
        val txt = File(dir, "$id.tab.txt").takeIf { it.isFile }
        // One is kept at a time; should both be there (cut off while replacing one), the newer one.
        if (pdf != null && (txt == null || pdf.lastModified() >= txt.lastModified())) return SongLines.TabSource.Pdf(pdf)
        return txt?.let { try { SongLines.TabSource.Text(it.readText()) } catch (_: IOException) { null } }
    }

    /** Whether a tab is kept for song [id] (text or PDF). */
    fun hasTab(context: Context, id: String): Boolean {
        val dir = songsDir(context)
        return File(dir, "$id.tab.txt").isFile || File(dir, "$id.tab.pdf").isFile
    }

    /** Keeps tab [text] for song [id], in place of the tab kept before (text or PDF). */
    fun saveTab(context: Context, id: String, text: String) {
        val dir = songsDir(context)
        writeAtomically(File(dir, "$id.tab.txt")) { it.write(text.toByteArray(Charsets.UTF_8)) }
        File(dir, "$id.tab.pdf").delete()
    }

    /**
     * Keeps the PDF tab in [file] (one that came in, see [TabFiles]) for song [id], in place of the tab kept
     * before (text or PDF): it's moved there whole, keeping its [stamp]. Returns where it's kept.
     */
    fun keepTabPdf(context: Context, id: String, file: File): File {
        val dir = songsDir(context)
        val kept = File(dir, "$id.tab.pdf")
        if (file.canonicalPath != kept.canonicalPath) {
            if (!file.isFile) throw IOException("the PDF isn't on the phone any more")
            // A rename (the files are side by side in the app's files) replaces the old one at once.
            if (!file.renameTo(kept)) {
                val time = file.lastModified()
                writeAtomically(kept) { out -> file.inputStream().use { it.copyTo(out) } }
                kept.setLastModified(time)
                file.delete()
            }
        }
        File(dir, "$id.tab.txt").delete()
        return kept
    }

    /** Removes the tab kept for song [id] (text or PDF). */
    fun removeTab(context: Context, id: String) {
        val dir = songsDir(context)
        File(dir, "$id.tab.txt").delete()
        File(dir, "$id.tab.pdf").delete()
    }

    /** Writes [file] through a temporary file renamed over it at the end, so a crash never leaves it half written. */
    private fun writeAtomically(file: File, write: (OutputStream) -> Unit) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        try {
            FileOutputStream(tmp).use { f ->
                // Buffered, and left open when [write] closes it, so it can be synced before the rename.
                val out = object : BufferedOutputStream(f, 64 * 1024) { override fun close() = flush() }
                write(out)
                out.flush()
                f.fd.sync()
            }
            if (!tmp.renameTo(file)) { file.delete(); if (!tmp.renameTo(file)) throw IOException("Couldn't save ${file.name}") }
        } finally {
            tmp.delete()
        }
    }

    // --- running a job ---

    /** What a song is prepared from; [solo]: the bouzouki alone (a practice take), so nothing is separated. */
    sealed interface Source {
        val solo: Boolean
        data class AudioFile(val uri: Uri, val name: String, override val solo: Boolean = false) : Source
    }

    /** The song id for [source]. */
    fun idFor(source: Source): String = when (source) {
        is Source.AudioFile -> "file-" + Integer.toHexString(source.uri.toString().hashCode())
    }

    /** Where temporary audio goes while a song is prepared (the app's cache; never anywhere else). */
    fun tempDir(context: Context) = File(context.cacheDir, "audio").apply { mkdirs() }

    /**
     * Deletes temporary audio a crash or a killed app left behind (none is ever meant to stay): all of it, unless a
     * song is being prepared now.
     */
    fun sweep(context: Context) {
        if (isBusy()) return
        tempDir(context).listFiles()?.forEach { it.delete() }
        // What older versions left in the cache itself (a downloaded song's audio).
        context.cacheDir.listFiles { f -> f.isFile && f.extension.lowercase() in setOf("m4a", "webm", "opus", "mp4", "wav", "pcm") }?.forEach { it.delete() }
    }

    fun isBusy() = worker?.isAlive == true

    /** Starts preparing [source]; the work runs in [PrepareService] so it survives leaving the app. */
    fun start(context: Context, source: Source, instrument: Instrument) {
        if (isBusy()) return
        cancel = false
        val app = context.applicationContext
        val id = idFor(source)
        publish(State.Working(id, "Starting…", 0f))
        val service = Intent(app, PrepareService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) app.startForegroundService(service) else app.startService(service)
        worker = thread(name = "song-prep") {
            try {
                val (song, data) = prepare(app, id, source, instrument) { step, f -> publish(State.Working(id, step, f)) }
                publish(State.Working(id, "Saving", 1f))
                save(app, song, data)
                publish(State.Done(song))
            } catch (_: InterruptedException) {
                publish(State.Idle)
            } catch (e: Throwable) {
                publish(State.Failed(id, friendly(e)))
            }
            // PrepareService stops itself when it sees the job end (stopping it from here could
            // race its startForeground() and crash the app on a quick failure).
        }
    }

    fun cancel() { cancel = true }

    private fun friendly(e: Throwable): String = when {
        e is OutOfMemoryError -> "The song is too long for the phone's memory."
        e is IOException -> e.message ?: "Network problem"
        else -> e.message ?: e.javaClass.simpleName
    }

    private fun prepare(
        context: Context, id: String, source: Source, instrument: Instrument, report: (String, Float) -> Unit,
    ): Pair<SongAnalysis, AnalysisData> {
        val cancelled = { cancel }
        // A band's song is separated first (its model downloaded once); the bouzouki alone needs nothing of the kind.
        val mdxFile = if (source.solo) null else ensureModel(context) { report("Getting the voice-removal model (once, 67 MB)", it) }

        val title: String
        val audio: AudioDecoder.Stereo
        when (source) {
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
        val mdx = mdxFile?.let { env.createSession(it.path, options) }
        try {
            val separator: Separator = if (mdx != null) MdxSeparator(env, mdx) else NoSeparation
            return PitchEngines.open(context).use { bp ->
                SongAnalyzer(separator, bp.pitch, PostConfig(solo = source.solo)).analyzeWithData(
                    id, title, audio.left, audio.right, instrument,
                    progress = { stage, f -> report(if (source.solo && stage == SongAnalyzer.Stage.SEPARATING) "Reading the bouzouki" else stage.label, f) },
                    cancelled = cancelled,
                )
            }
        } finally {
            mdx?.close()
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
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
            stopSelf()
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
