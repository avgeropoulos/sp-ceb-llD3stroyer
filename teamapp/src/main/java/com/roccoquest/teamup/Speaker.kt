package com.roccoquest.teamup

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.roccoquest.game.SpeechQueue
import com.roccoquest.game.SpeechText
import com.roccoquest.game.Voice
import com.roccoquest.game.VoiceCast
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * The characters' voices: the phone's own text-to-speech, with a real-sounding voice for each
 * character (chosen by [VoiceCast]: the phone's most lifelike voices, a different person each,
 * with only small pitch and speed nudges) and a [SpeechQueue] so a new line waits for the one
 * being said instead of cutting it off (except [sayNow] lines: cutscenes and warnings, which must
 * keep up with the pictures, cut in). Talking to the speech engine can take a while (it's
 * another app), so it all happens on its own thread and never holds up the game.
 * (A copy of this file is in teamup3d/android.)
 */
class Speaker(context: Context) {
    private val app = context.applicationContext
    private val speech = Executors.newSingleThreadExecutor { r ->
        Thread(r, "speech").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
    }
    @Volatile private var ready = false
    private var tts: TextToSpeech? = null
    private val lines = SpeechQueue()
    /** Bumped by [hush], so lines asked for before it (still waiting for the speech thread) are dropped. */
    private val hushes = AtomicInteger()

    /** An internet voice failed (the phone went offline): use the voices on the phone from now on. */
    @Volatile private var networkTrouble = false

    // Only used on the speech thread.
    private val phoneVoices = HashMap<String, android.speech.tts.Voice>()
    private var infos: List<VoiceCast.Info> = emptyList()
    private var cast: Map<Voice, VoiceCast.Persona> = emptyMap()
    private var greek: VoiceCast.Persona? = null
    private var online = false
    private var onlineCheckedAt = 0L
    /** The voice the engine is set to now (null after asking by language), so it isn't set again for every line. */
    private var current: String? = null
    private var nextId = 0

    init {
        tts = TextToSpeech(app) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // Listing and picking voices asks the engine a lot, so it's done on the speech thread too.
                try {
                    speech.execute {
                        try { setUp() } catch (_: Exception) {}
                        ready = true
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /** Says [text] in [voice]'s voice (dropped if the phone's speech isn't ready yet). */
    fun say(text: String, voice: Voice) = post { speakNow(text, voice, interrupt = false) }

    /**
     * Says [text] right away, cutting off whatever is being said: cutscene lines and warnings go with
     * what's on screen at that moment, so waiting their turn would make them late.
     */
    fun sayNow(text: String, voice: Voice) = post { speakNow(text, voice, interrupt = true) }

    /** Stops all talking, and drops lines that were handed over but not said yet (a skipped cutscene). */
    fun hush() {
        hushes.incrementAndGet()
        post {
            lines.clear()
            tts?.stop()
        }
    }

    /** Runs [job] on the speech thread (unless the phone's speech isn't ready yet, or [hush] came after it). */
    private fun post(job: () -> Unit) {
        if (tts == null || !ready) return
        val asked = hushes.get()
        try {
            speech.execute {
                if (asked == hushes.get()) try { job() } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    fun release() {
        speech.shutdownNow()
        tts?.shutdown()
    }

    private fun setUp() {
        val t = tts ?: return
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { utteranceId?.let { lines.started(it, now()) } }
            override fun onDone(utteranceId: String?) { utteranceId?.let { lines.finished(it, now()) } }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) { utteranceId?.let { lines.finished(it, now()) } }
            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceId?.let { lines.finished(it, now()) }
                if (errorCode == TextToSpeech.ERROR_NETWORK || errorCode == TextToSpeech.ERROR_NETWORK_TIMEOUT) networkTrouble = true
            }
            override fun onStop(utteranceId: String?, interrupted: Boolean) { utteranceId?.let { lines.finished(it, now()) } }
        })
        phoneVoices.clear()
        val list = try { t.voices } catch (_: Exception) { null }
        if (list != null) for (v in list) { if (v != null) phoneVoices[v.name] = v }
        val favourite = try { t.defaultVoice?.name?.let(VoiceCast::speaker) } catch (_: Exception) { null }
        infos = phoneVoices.values.map { info(it, favourite) }
        recast()
    }

    private fun recast() {
        online = !networkTrouble && isOnline(app)
        onlineCheckedAt = SystemClock.elapsedRealtime()
        cast = VoiceCast.cast(infos, online)
        greek = VoiceCast.greek(infos, online)
    }

    /** Hands [text] to the phone; [interrupt] = stop what's being said and say this now, without waiting its turn. */
    private fun speakNow(text: String, voice: Voice, interrupt: Boolean) {
        val t = tts ?: return
        // The phone can go online or offline while playing (internet voices need it): look again now and then.
        val clock = SystemClock.elapsedRealtime()
        if ((networkTrouble && online) || clock - onlineCheckedAt > 60_000L) {
            val was = online
            onlineCheckedAt = clock
            if ((!networkTrouble && isOnline(app)) != was) recast()
        }
        var say = text
        val persona: VoiceCast.Persona
        if (SpeechText.isGreek(text)) {
            val g = greek ?: VoiceCast.Persona(null, "el", "GR", VoiceCast.greekPart.pitch, VoiceCast.greekPart.rate)
            if (use(t, g)) persona = g
            else {
                // No Greek voice on this phone: say it in English instead.
                say = POSEIDON_ENGLISH
                persona = useFor(t, voice)
            }
        } else persona = useFor(t, voice)

        val line = SpeechText.tidy(say)
        if (line.isEmpty()) return
        val now = now()
        // (A cutscene's "Hic! ... Hic!" is said every time, even while the last one is still going.)
        val mode = if (interrupt) SpeechQueue.Mode.FLUSH else lines.plan(line, now)
        if (mode == SpeechQueue.Mode.SKIP) return
        val id = "line${nextId++}"
        lines.queued(id, line, SpeechText.seconds(line, persona.rate), now, mode)
        val queue = if (mode == SpeechQueue.Mode.FLUSH) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
        if (t.speak(line, queue, null, id) != TextToSpeech.SUCCESS) lines.finished(id, now())
    }

    /** Switches to [voice]'s persona (or, if that voice won't load, plain English). */
    private fun useFor(t: TextToSpeech, voice: Voice): VoiceCast.Persona {
        val p = cast[voice]
        if (p != null && use(t, p)) return p
        val plain = VoiceCast.plain(voice)
        use(t, plain)
        return plain
    }

    /** Sets the engine to [p]'s voice, pitch and speed. False if the phone can't do that voice. */
    private fun use(t: TextToSpeech, p: VoiceCast.Persona): Boolean {
        val name = p.voice
        if (name != null) {
            if (name != current) {
                val v = phoneVoices[name] ?: return false
                if (t.setVoice(v) != TextToSpeech.SUCCESS) return false
                current = name
            }
        } else {
            val r = t.setLanguage(Locale(p.language, p.country))
            if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) return false
            current = null
        }
        // The engine remembers these for every line it's handed after this, each line keeps its own.
        t.setPitch(p.pitch)
        t.setSpeechRate(p.rate)
        return true
    }

    companion object {
        private const val POSEIDON_ENGLISH = "I am Poseidon! I am the strongest guy of the sea!"

        private fun now() = SystemClock.elapsedRealtime() / 1000.0

        /** A phone voice in plain words for [VoiceCast] ([favourite] = the phone's default voice's person). */
        fun info(v: android.speech.tts.Voice, favourite: String? = null): VoiceCast.Info {
            val loc: Locale? = v.locale
            return VoiceCast.Info(
                name = v.name,
                language = loc?.language ?: "",
                country = loc?.country ?: "",
                quality = v.quality,
                needsNetwork = v.isNetworkConnectionRequired,
                notInstalled = v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) == true,
                isDefault = favourite != null && VoiceCast.speaker(v.name) == favourite,
            )
        }

        /** Is the phone on a working internet connection right now? */
        fun isOnline(context: Context): Boolean {
            return try {
                val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
                val net = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(net) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            } catch (_: Exception) { false }
        }
    }
}
