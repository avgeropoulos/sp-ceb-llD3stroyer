package com.roccoquest.team3d.app

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.roccoquest.game.Autotune
import com.roccoquest.game.Chiptune
import com.roccoquest.game.Songs
import com.roccoquest.game.VoiceCast
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
 * The singer is the phone's most lifelike English voice (the same pick as the talking voices,
 * [VoiceCast.mostNatural]), only ever nudged a little in pitch, and it sings in the octave nearest
 * its own voice, so it sounds like a person singing instead of a chipmunk.
 * If the phone has no voice, nothing happens and the song keeps its built-in "ooh-aah" singer.
 */
class ThemeSinger(context: Context, private val onReady: (List<FloatArray?>) -> Unit) {
    private val app = context.applicationContext
    private val dir = File(context.cacheDir, "sing").apply { mkdirs() }
    private var tts: TextToSpeech? = null
    private val done = ConcurrentHashMap<String, CountDownLatch>()

    init {
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) thread(name = "theme-singer", priority = Thread.MIN_PRIORITY) {
                try { record() } catch (_: Throwable) {}
                tts?.shutdown()
            }
        }
    }

    private fun record() {
        val t = tts ?: return
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) {}
            override fun onDone(id: String) { done[id]?.countDown() }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String) { done[id]?.countDown() }
            override fun onError(id: String, errorCode: Int) { done[id]?.countDown() }
        })
        val phone = HashMap<String, android.speech.tts.Voice>()
        val list = try { t.voices } catch (_: Exception) { null }
        if (list != null) for (v in list) { if (v != null) phone[v.name] = v }
        val infos = phone.values.map { Speaker.info(it) }

        // How high does this voice speak normally? Then each word is asked for near its note.
        val online = Speaker.isOnline(app)
        val first = VoiceCast.mostNatural(infos, online)
        var probe = start(t, phone, first)
        // An internet voice that doesn't answer: the best voice on the phone sings instead.
        if (probe == null && first?.needsNetwork == true) probe = start(t, phone, VoiceCast.mostNatural(infos, false))
        val heard = probe ?: return
        val base = Autotune.pitch(heard.first, heard.second).takeIf { it > 0f } ?: 160f

        val notes = Chiptune.sungNotes(Songs.theme)
        // Whole octaves below the tune, whichever is nearest this voice (a man sings lower than a woman).
        val down = Autotune.octavesDown(notes.map { it.first }, base)
        val words = ArrayList<FloatArray?>()
        for ((i, spoken) in Songs.themeSpoken.withIndex()) {
            val (freq, seconds) = notes.getOrNull(i) ?: break
            val target = freq / down
            // Only a gentle nudge (big ones sound robotic); Autotune tunes the rest of the way.
            t.setPitch((target / base).coerceIn(VoiceCast.MIN_TWEAK, VoiceCast.MAX_TWEAK))
            val rec = say(spoken, "w$i")
            words += rec?.let { Autotune.fit(it.first, it.second, target, seconds) }
        }
        if (words.count { it != null } >= words.size / 2) onReady(words)
    }

    /** Switches to [pick] (or plain US English) at normal pitch, and records a test "la" with it. */
    private fun start(t: TextToSpeech, phone: Map<String, android.speech.tts.Voice>, pick: VoiceCast.Info?): Pair<FloatArray, Int>? {
        val v = pick?.let { phone[it.name] }
        if (v == null || t.setVoice(v) != TextToSpeech.SUCCESS) {
            if (t.setLanguage(Locale.US) < TextToSpeech.LANG_AVAILABLE) t.setLanguage(Locale.ENGLISH)
        }
        t.setSpeechRate(SING_RATE)
        t.setPitch(1f)
        return say("la", "probe")
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

    private companion object {
        /** A touch quicker than talking, so the words fit the song's short notes. */
        const val SING_RATE = 1.05f
    }
}
