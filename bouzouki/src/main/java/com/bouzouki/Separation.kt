package com.bouzouki

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.ActivityManager
import android.content.Context
import com.bouzouki.core.song.DemucsSeparator
import com.bouzouki.core.song.LightSeparator
import com.bouzouki.core.song.NoSeparation
import com.bouzouki.core.song.Separator
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * How a band's recording is separated before its bouzouki is transcribed, all on the phone:
 * - [Kind.DEMUCS]: Demucs v4 htdemucs_6s (Meta, MIT), its guitar and "other" stems (where the bouzouki lands): the
 *   best, and the heaviest (a segment of 7.8 s at a time, several GB of memory at its peak on a desktop);
 * - [Kind.DEMUCS_LITE], [Kind.DEMUCS_TINY]: the same network on 3.9 s or 2.0 s segments: about half and a third of the
 *   memory (on a desktop 1.2 and 0.75 GB at the peak, against 2.5 GB), with less context for the network;
 * - [Kind.LIGHT]: plain DSP (voice turned down, drums and strums out, the bouzouki's band kept), almost free;
 * - [Kind.NONE]: nothing (the bouzouki alone).
 * The models are downloaded once from the app's model release (and checked against its SHA-256 sums). [chosen] is the
 * player's choice (🔧 Lab tools), by default the best the phone's memory allows; should Demucs fail on the phone (out
 * of memory), the light separation is used instead and the label says so.
 */
object Separation {
    enum class Kind(val label: String) {
        DEMUCS("Separating the instruments (Demucs)"),
        DEMUCS_LITE("Separating the instruments (Demucs, 3.9 s segments)"),
        DEMUCS_TINY("Separating the instruments (Demucs, 2 s segments)"),
        LIGHT("Leaning the mix towards the bouzouki"),
        NONE("Reading the bouzouki"),
    }

    class Opened(val separator: Separator, label: String, private val close: () -> Unit) : Closeable {
        var label = label; internal set
        override fun close() = close.invoke()
    }

    private fun prefs(context: Context) = context.getSharedPreferences("bouzouki", Context.MODE_PRIVATE)

    /**
     * The player's choice, or (none made) by the phone's memory: full Demucs from 8 GB, its 3.9 s export from 6 GB,
     * the 2 s export from 4 GB, the light separation below.
     */
    fun chosen(context: Context): Kind {
        prefs(context).getString(PREF, null)?.let { n -> Kind.entries.firstOrNull { it.name == n }?.let { return it } }
        val mem = ActivityManager.MemoryInfo().also { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        val gb = mem.totalMem / 1e9
        return when { gb >= 7.5 -> Kind.DEMUCS; gb >= 5.5 -> Kind.DEMUCS_LITE; gb >= 3.5 -> Kind.DEMUCS_TINY; else -> Kind.LIGHT }
    }

    fun choose(context: Context, kind: Kind?) = prefs(context).edit().apply { if (kind == null) remove(PREF) else putString(PREF, kind.name) }.apply()

    fun modelFile(context: Context, kind: Kind): File? = when (kind) {
        Kind.DEMUCS -> File(context.filesDir, DemucsSeparator.MODEL_6S)
        Kind.DEMUCS_LITE -> File(context.filesDir, DemucsSeparator.MODEL_6S_SEG39)
        Kind.DEMUCS_TINY -> File(context.filesDir, DemucsSeparator.MODEL_6S_SEG20)
        else -> null
    }

    /** Opens [kind] (downloading its model once, with [report]), falling back to the light separation if that fails. */
    fun open(context: Context, kind: Kind, report: (String, Float) -> Unit, cancelled: () -> Boolean): Opened {
        if (kind == Kind.NONE) return Opened(NoSeparation, kind.label) {}
        if (kind == Kind.LIGHT) return Opened(light(), kind.label) {}
        val file = try {
            ensureModel(context, kind, report, cancelled)
        } catch (e: IOException) {
            return Opened(light(), Kind.LIGHT.label + " (couldn't get the Demucs model: ${e.message})") {}
        }
        val env = OrtEnvironment.getEnvironment()
        val options = DemucsSeparator.sessionOptions(Runtime.getRuntime().availableProcessors().coerceIn(2, 8))
        val session = try { env.createSession(file.path, options) } catch (t: Throwable) {
            options.close()
            return Opened(light(), Kind.LIGHT.label + " (Demucs couldn't start: ${t.message})") {}
        }
        val demucs = DemucsSeparator(env, session)
        var opened: Opened? = null
        // Demucs, or the light separation should it fail part way (memory).
        val sep = object : Separator {
            override val keepsBass = false
            override fun instrumentalMono(left: FloatArray, right: FloatArray, progress: (Float) -> Unit, cancelled: () -> Boolean): FloatArray = try {
                demucs.stemMono(left, right, demucs.defaultStems, progress, cancelled)
            } catch (e: InterruptedException) {
                throw e
            } catch (t: Throwable) {
                opened?.label = Kind.LIGHT.label + " (Demucs failed on this phone: ${t.javaClass.simpleName})"
                light().instrumentalMono(left, right, progress, cancelled)
            }
        }
        opened = Opened(sep, kind.label) { session.close(); options.close() }
        return opened
    }

    private fun light(): Separator = object : Separator {
        override fun instrumentalMono(left: FloatArray, right: FloatArray, progress: (Float) -> Unit, cancelled: () -> Boolean) =
            LightSeparator().bouzoukiMono(left, right, progress, cancelled)
    }

    /** [kind]'s model in the app's files, downloaded and checked once. */
    fun ensureModel(context: Context, kind: Kind, report: (String, Float) -> Unit, cancelled: () -> Boolean): File {
        val file = modelFile(context, kind) ?: throw IOException("no model for ${kind.name}")
        val sums = File(context.filesDir, "demucs.sha256")
        if (file.isFile && sums.isFile && DemucsSeparator.verify(file, file.name, DemucsSeparator.parseChecksums(sums.readText()))) return file
        val checksums = download(DemucsSeparator.url(DemucsSeparator.CHECKSUMS)) { input, _ -> input.readBytes() }.toString(Charsets.UTF_8)
        if (file.name !in DemucsSeparator.parseChecksums(checksums)) throw IOException("${file.name} isn't in the model release yet")
        val part = File(context.filesDir, file.name + ".part")
        download(DemucsSeparator.url(file.name)) { input, total ->
            part.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var done = 0L
                while (true) {
                    if (cancelled()) throw InterruptedException()
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    done += n
                    report("Getting the separation model (once, ${total / 1_000_000} MB)", if (total > 0) done.toFloat() / total else 0f)
                }
            }
        }
        if (!DemucsSeparator.verify(part, file.name, DemucsSeparator.parseChecksums(checksums))) {
            part.delete()
            throw IOException("the downloaded model is damaged; try again")
        }
        sums.writeText(checksums)
        if (!part.renameTo(file)) throw IOException("couldn't keep the model")
        return file
    }

    private fun <T> download(url: String, read: (java.io.InputStream, Long) -> T): T {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = true
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        if (conn.responseCode !in 200..299) throw IOException("the model release answered ${conn.responseCode}")
        return conn.inputStream.use { read(it, conn.contentLengthLong) }
    }

    private const val PREF = "separation"
}
