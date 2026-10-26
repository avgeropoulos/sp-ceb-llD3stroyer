package com.roccoquest.team3d.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.roccoquest.game.Autotune
import com.roccoquest.game.Chiptune
import com.roccoquest.game.Songs
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Makes the phone's own voice sing the TEAM UP! theme song: every word of the song is spoken
 * once (near its note) into a little sound file, then [Autotune] tunes it to the exact note and
 * fits it to the beat. When all the words are ready, [onReady] gets them, one per sung note.
 * If the phone has no voice, nothing happens and the song keeps its built-in "ooh-aah" singer.
 */
class ThemeSinger(context: Context, private val onReady: (List<FloatArray?>) -> Unit) {
    private val dir = File(context.cacheDir, "sing").apply { mkdirs() }
    private var tts: TextToSpeech? = null
    private val done = ConcurrentHashMap<String, CountDownLatch>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) thread(name = "theme-singer", priority = Thread.MIN_PRIORITY) {
                try { record() } catch (_: Throwable) {}
                tts?.shutdown()
            }
        }
    }

    private fun record() {
        val t = tts ?: return
        if (t.setLanguage(Locale.US) < TextToSpeech.LANG_AVAILABLE) t.setLanguage(Locale.ENGLISH)
        t.setSpeechRate(1.15f)
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { done[id]?.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { done[id]?.countDown() }
            override fun onError(id: String, errorCode: Int) { done[id]?.countDown() }
        })
        // How high does this voice speak normally? Then each word is asked for near its note.
        t.setPitch(1f)
        val probe = say("la", "probe") ?: return
        val base = Autotune.pitch(probe.first, probe.second).takeIf { it > 0f } ?: 160f

        val notes = Chiptune.sungNotes(Songs.theme)
        val words = ArrayList<FloatArray?>()
        for ((i, spoken) in Songs.themeSpoken.withIndex()) {
            val (freq, seconds) = notes.getOrNull(i) ?: break
            // Sung two octaves below the tune, where a speaking voice sounds natural.
            val target = freq / 4f
            t.setPitch((target / base).coerceIn(0.6f, 2f))
            val rec = say(spoken, "w$i")
            words += rec?.let { Autotune.fit(it.first, it.second, target, seconds) }
        }
        if (words.count { it != null } >= words.size / 2) onReady(words)
    }

    /** Speaks [text] into a file and reads it back: (samples, sample rate), or null. */
    private fun say(text: String, id: String): Pair<FloatArray, Int>? {
        val t = tts ?: return null
        val file = File(dir, "$id.wav")
        val latch = CountDownLatch(1)
        done[id] = latch
        if (t.synthesizeToFile(text, null, file, id) != TextToSpeech.SUCCESS) return null
        latch.await(5, TimeUnit.SECONDS)
        done.remove(id)
        return try { readWav(file) } catch (_: Exception) { null } finally { file.delete() }
    }

    /** Reads a 16-bit PCM WAV file (mono, or the first channel). */
    private fun readWav(f: File): Pair<FloatArray, Int>? {
        val bytes = f.readBytes()
        if (bytes.size < 44) return null
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var pos = 12
        var rate = 0
        var channels = 1
        var bits = 16
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = b.getInt(pos + 4)
            if (id == "fmt ") {
                channels = b.getShort(pos + 10).toInt()
                rate = b.getInt(pos + 12)
                bits = b.getShort(pos + 22).toInt()
            } else if (id == "data") {
                if (bits != 16 || rate <= 0) return null
                val end = minOf(bytes.size, pos + 8 + size)
                val frames = (end - pos - 8) / (2 * channels)
                val out = FloatArray(frames) { b.getShort(pos + 8 + it * 2 * channels) / 32768f }
                return out to rate
            }
            pos += 8 + size + (size and 1)
        }
        return null
    }
}
