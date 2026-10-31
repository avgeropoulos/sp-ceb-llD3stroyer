package com.roccoquest.game

/**
 * Picks real-sounding voices for the talking characters from the voices the phone has.
 *
 * Big pitch and speed changes (pitch 1.9, 2.0...) are what made the old voices sound like robots
 * and chipmunks, so now every character gets its own natural voice (a different man or woman,
 * American, British or Australian, when the phone has several) and only small pitch and speed
 * nudges on top. It's plain Kotlin so it can be tested; the Android apps turn the phone's
 * `TextToSpeech.getVoices()` into [Info]s and use the [Persona]s it hands back.
 */
object VoiceCast {
    // The same numbers as android.speech.tts.Voice.QUALITY_*.
    const val QUALITY_VERY_LOW = 100
    const val QUALITY_LOW = 200
    const val QUALITY_NORMAL = 300
    const val QUALITY_HIGH = 400
    const val QUALITY_VERY_HIGH = 500

    /** How far pitch and speed may be nudged and still sound like a person. */
    const val MIN_TWEAK = 0.85f
    const val MAX_TWEAK = 1.2f

    enum class Gender { MALE, FEMALE, ANY }

    /** One of the phone's voices, in plain words. */
    data class Info(
        val name: String,
        /** "en", "it", "el"... */
        val language: String,
        /** "US", "GB", "AU"... (can be empty). */
        val country: String = "",
        val quality: Int = QUALITY_NORMAL,
        /** Only works online (the phone streams it from the internet). */
        val needsNetwork: Boolean = false,
        /** Listed, but not downloaded onto the phone yet: it can't speak. */
        val notInstalled: Boolean = false,
        /** The phone's own default voice: whoever set up the phone picked it, so it's a safe favourite. */
        val isDefault: Boolean = false,
    )

    /**
     * How one character talks: the phone voice to use ([voice] = null means "whatever voice the
     * phone has for [language]") plus a gentle pitch and speed.
     */
    data class Persona(val voice: String?, val language: String, val country: String, val pitch: Float, val rate: Float)

    /** What a character would like to sound like. */
    class Part(
        val language: String,
        val country: String,
        val gender: Gender,
        /** How much the right gender matters (a boss really should be a man's deep voice). */
        val genderWeight: Int,
        /** Accents in the order the character likes them. */
        val accents: List<String>,
        val pitch: Float,
        val rate: Float,
    )

    val parts: Map<Voice, Part> = mapOf(
        // Rocco: an Italian guy (an Italian voice reading English sounds just like him).
        Voice.HERO to Part("it", "IT", Gender.MALE, 120, listOf("IT"), 1.0f, 0.96f),
        // Poseidon and the bosses: a deep man's voice, a little slower.
        Voice.GOD to Part("en", "GB", Gender.MALE, 200, listOf("GB", "US", "AU"), 0.86f, 0.9f),
        // Pokemon: a bright, happy voice saying their names.
        Voice.MON to Part("en", "US", Gender.FEMALE, 80, listOf("US", "AU", "GB"), 1.18f, 1.06f),
        // Villagers: a friendly, calm grown-up.
        Voice.VILLAGER to Part("en", "AU", Gender.MALE, 60, listOf("AU", "GB", "US"), 0.95f, 0.94f),
        // Spotty (and the witch, the birdie...): a quick, squeaky-ish voice, but still a person.
        Voice.TOAD to Part("en", "AU", Gender.FEMALE, 60, listOf("AU", "GB", "US"), 1.18f, 1.12f),
        // Ring-Ding Frog and the announcer ("Meteor shower!"): upbeat and clear.
        Voice.FROG to Part("en", "US", Gender.ANY, 0, listOf("US", "GB", "AU"), 1.12f, 1.08f),
    )

    /** Rocco in English, when the phone has no Italian voice. */
    val heroEnglish = Part("en", "US", Gender.MALE, 120, listOf("US", "GB", "AU"), 1.0f, 0.96f)

    /** Poseidon's Greek line. */
    val greekPart = Part("el", "GR", Gender.MALE, 60, listOf("GR"), 0.88f, 0.9f)

    /** Who picks first: the ones that most need a particular kind of voice. */
    private val order = listOf(Voice.HERO, Voice.GOD, Voice.MON, Voice.VILLAGER, Voice.TOAD, Voice.FROG)

    /** A voice somebody else already has only wins if every other one is clearly worse. */
    private const val REUSE = 120

    /** Words in voice names that mean "this one sounds lifelike" / "this one sounds like a robot". */
    private val LIFELIKE = listOf("neural", "wavenet", "natural", "enhanced", "premium", "studio")
    private val ROBOTIC = listOf("espeak", "pico", "robot", "flite", "festival")

    /**
     * Google's voices are named by a 3-letter code ("en-us-x-iol-local"), not "man" or "woman".
     * These are the ones we know.
     */
    private val GOOGLE_GENDER: Map<String, Gender> = buildMap {
        for (c in listOf("iob", "iog", "sfg", "tpc", "tpf", "gba", "gbc", "gbg", "fis", "aua", "auc", "ena", "enc", "itb", "kda")) put(c, Gender.FEMALE)
        for (c in listOf("iol", "iom", "tpd", "gbb", "gbd", "rjs", "aub", "aud", "end", "ene", "itc", "itd")) put(c, Gender.MALE)
    }
    private val googleCode = Regex("-x-([a-z]{3})")

    /** The person behind a voice: "en-us-x-iol-local" and "en-us-x-iol-network" are the same one. */
    fun speaker(name: String): String = name.lowercase().removeSuffix("-local").removeSuffix("-network")

    /** A man's or a woman's voice, from its name (ANY when the name doesn't say). */
    fun gender(name: String): Gender {
        val n = name.lowercase()
        if ("female" in n || "woman" in n) return Gender.FEMALE
        if ("male" in n || "man" in n.split('-', '_', '#', ' ')) return Gender.MALE
        val code = googleCode.find(n)?.groupValues?.get(1) ?: return Gender.ANY
        return GOOGLE_GENDER[code] ?: Gender.ANY
    }

    /** "eng" and "en" are both English (some speech engines use the 3-letter codes). */
    fun language(code: String): String = when (val c = code.lowercase()) {
        "eng" -> "en"
        "ita" -> "it"
        "ell", "gre" -> "el"
        else -> c
    }

    /** "USA" and "US" are the same country, too. */
    fun country(code: String): String = when (val c = code.uppercase()) {
        "USA" -> "US"
        "GBR" -> "GB"
        "AUS" -> "AU"
        "IND" -> "IN"
        "ITA" -> "IT"
        "GRC" -> "GR"
        "CAN" -> "CA"
        "IRL" -> "IE"
        "NZL" -> "NZ"
        else -> c
    }

    /**
     * How lifelike a voice sounds (bigger is better), or null if it can't speak right now
     * (not downloaded, or it needs the internet and the phone is offline).
     */
    fun naturalness(v: Info, online: Boolean): Int? {
        if (v.notInstalled) return null
        if (v.needsNetwork && !online) return null
        val n = v.name.lowercase()
        var s = v.quality
        // Online, the internet voices are usually the newest, most lifelike ones.
        if (v.needsNetwork) s += 50
        if (LIFELIKE.any { it in n }) s += 80
        if (ROBOTIC.any { it in n }) s -= 250
        if (v.isDefault) s += 12
        return s
    }

    /** How well [v] fits [part], or null if it can't (wrong language, or it can't speak). */
    fun score(v: Info, part: Part, online: Boolean, used: Set<String> = emptySet()): Int? {
        if (language(v.language) != part.language) return null
        var s = naturalness(v, online) ?: return null
        val g = gender(v.name)
        if (part.gender != Gender.ANY && g != Gender.ANY) s += if (g == part.gender) part.genderWeight else -part.genderWeight / 2
        val accent = part.accents.indexOf(country(v.country))
        s += if (accent >= 0) 30 - accent * 10 else -10
        if (speaker(v.name) in used) s -= REUSE
        return s
    }

    /** The voice that suits [part] best. Ties go to the first name A-Z, so it's the same every time. */
    fun best(voices: Collection<Info>, part: Part, online: Boolean, used: Set<String> = emptySet()): Info? {
        var pick: Info? = null
        var top = Int.MIN_VALUE
        for (v in voices.sortedBy { it.name }) {
            val s = score(v, part, online, used) ?: continue
            if (s > top) { top = s; pick = v }
        }
        return pick
    }

    /**
     * Gives every character a voice. Each one gets a different person when the phone has enough
     * voices; with just one voice they all share it (and the small pitch nudges tell them apart).
     */
    fun cast(voices: Collection<Info>, online: Boolean): Map<Voice, Persona> {
        val used = HashSet<String>()
        val out = LinkedHashMap<Voice, Persona>()
        for (who in order + Voice.entries.filter { it !in order }) {
            var part = parts[who] ?: parts.getValue(Voice.FROG)
            var pick = best(voices, part, online, used)
            // No Italian voice on this phone: Rocco speaks English (if the phone didn't list its
            // voices at all, the app still tries asking for Italian by language).
            if (pick == null && who == Voice.HERO && voices.isNotEmpty()) {
                part = heroEnglish
                pick = best(voices, part, online, used)
            }
            if (pick != null) used += speaker(pick.name)
            out[who] = persona(pick, part)
        }
        return out
    }

    /** Poseidon's Greek voice, or null if the phone has none. */
    fun greek(voices: Collection<Info>, online: Boolean): Persona? =
        best(voices, greekPart, online)?.let { persona(it, greekPart) }

    /** The most lifelike English voice of all (the theme song's singer). */
    fun mostNatural(voices: Collection<Info>, online: Boolean): Info? =
        best(voices, Part("en", "US", Gender.ANY, 0, listOf("US", "GB", "AU"), 1f, 1f), online)

    /** An English persona for [who] that doesn't need any particular voice (the last fallback). */
    fun plain(who: Voice): Persona {
        val part = if (who == Voice.HERO) heroEnglish else parts[who] ?: parts.getValue(Voice.FROG)
        return Persona(null, "en", "US", part.pitch, part.rate)
    }

    private fun persona(v: Info?, part: Part) = Persona(
        v?.name, v?.let { language(it.language) } ?: part.language,
        v?.country?.takeIf { it.isNotEmpty() }?.let(::country) ?: part.country, part.pitch, part.rate,
    )
}

/** Small helpers that make the game's lines sound like whole, natural sentences. */
object SpeechText {
    /** True if [text] has Greek letters in it (Poseidon speaks Greek). */
    fun isGreek(text: String) = text.any { it in 'Ͱ'..'Ͽ' || it in 'ἀ'..'῿' }

    /** Short words that really are said letter by letter. */
    private val ACRONYMS = setOf("TNT", "TV", "UFO", "XP", "HP", "BBQ", "DJ", "OK", "USA", "UK")
    private val longRun = Regex("([A-Za-z])\\1{3,}")
    private val word = Regex("[A-Za-z][A-Za-z']*")
    private val sentenceStart = Regex("(^|[.!?…]\\s+)([a-z])")

    /**
     * Turns a game line into something a voice reads naturally: words in CAPITALS (which some
     * voices spell out, "B-O-O!") are said normally, very long letter runs ("WAAAAAAH") are
     * shortened, sentences start with a capital, and every line ends with punctuation so the
     * voice finishes it like a sentence instead of trailing off.
     */
    fun tidy(text: String): String {
        var s = text.trim().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return s
        s = longRun.replace(s) { it.groupValues[1].repeat(3) }
        s = word.replace(s) { m ->
            val w = m.value
            val letters = w.filter { it.isLetter() }
            if (letters.length < 2 || letters.any { it.isLowerCase() } || w in ACRONYMS) w
            else {
                val low = w.lowercase()
                if (low == "i" || low.startsWith("i'")) "I" + low.substring(1) else low
            }
        }
        s = sentenceStart.replace(s) { it.groupValues[1] + it.groupValues[2].uppercase() }
        if (s.last().isLetterOrDigit()) s += "."
        return s
    }

    /** About how long a voice takes to say [text] at speed [rate] (a guess, used if the phone never says). */
    fun seconds(text: String, rate: Float): Double = (0.3 + text.length * 0.07) / rate.coerceAtLeast(0.3f)
}

/**
 * Keeps the talking tidy: a new line waits until the one being said is finished (so nobody gets
 * cut off mid-sentence), but if lines pile up, the old ones are dropped and the newest one is said
 * right away, so the voices never lag behind the game. The apps tell it when the phone starts and
 * finishes each line; if the phone never says, its own guess of each line's length is used.
 * Safe to use from several threads.
 */
class SpeechQueue(private val maxWaiting: Int = 1, private val maxDelay: Double = 2.5) {
    enum class Mode {
        /** Say it after the line(s) already playing. */
        ADD,
        /** Stop what's playing and say this now. */
        FLUSH,
        /** Already being said: don't say it twice. */
        SKIP,
    }

    private class Line(val id: String, val text: String, val seconds: Double, var ends: Double)
    private val lines = ArrayList<Line>()

    /** How [text] should be said if it's asked for at time [now] (in seconds). */
    @Synchronized fun plan(text: String, now: Double): Mode {
        forgetOld(now)
        if (lines.any { it.text == text }) return Mode.SKIP
        val last = lines.lastOrNull() ?: return Mode.ADD
        val waiting = lines.size - 1
        return if (waiting < maxWaiting && last.ends - now <= maxDelay) Mode.ADD else Mode.FLUSH
    }

    /** [text] (about [seconds] long) was handed to the phone as [id]. */
    @Synchronized fun queued(id: String, text: String, seconds: Double, now: Double, mode: Mode) {
        if (mode == Mode.FLUSH) lines.clear()
        val start = maxOf(now, lines.lastOrNull()?.ends ?: now)
        lines += Line(id, text, seconds, start + seconds)
    }

    /** The phone started saying [id]: every line before it is over. */
    @Synchronized fun started(id: String, now: Double) {
        val i = lines.indexOfFirst { it.id == id }
        if (i < 0) return
        repeat(i) { lines.removeAt(0) }
        retime(now)
    }

    /** The phone finished (or stopped, or failed) saying [id]. */
    @Synchronized fun finished(id: String, now: Double) {
        val i = lines.indexOfFirst { it.id == id }
        if (i < 0) return
        repeat(i + 1) { lines.removeAt(0) }
        retime(now)
    }

    /** How many lines are playing or waiting. */
    @Synchronized fun count(now: Double): Int { forgetOld(now); return lines.size }

    /** The first line left starts now, the rest follow one after another. */
    private fun retime(now: Double) {
        var t = now
        for (l in lines) { l.ends = t + l.seconds; t = l.ends }
    }

    /** If the phone never told us a line ended, it surely has a while after our guess. */
    private fun forgetOld(now: Double) { lines.removeAll { now > it.ends + GRACE } }

    private companion object {
        const val GRACE = 1.5
    }
}
