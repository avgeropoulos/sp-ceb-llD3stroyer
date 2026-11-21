package com.mixdeck.engine

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.roundToLong
import kotlin.math.sin

/**
 * MIX: blends the playing song into the one waiting on the other deck, the way a DJ would. It
 * starts the new song from its cue (the first downbeat, a phrase start) on one of the old song's
 * bars, chosen so the old song's phrase ends as the blend does when there's room, in time
 * ([Deck.startAt]) and at the same loudness (the new deck's trim). Then it blends over [bars]
 * bars in the chosen [style]:
 *
 * - [Style.SMOOTH]: an equal-power crossfade on an S-curve; the new song's bass held back, then
 *   swapped with the old one's over about a bar around the middle (never both basses full); the
 *   new song's mids and highs open up in the first half and the old one's ease down in the second.
 * - [Style.ECHO]: the new song comes in under the old one, the basses swap over two beats, then
 *   at the end of the old song's phrase it leaves on an echo that rings out over the new one.
 * - [Style.FILTER]: the new song comes in during the first half, the basses swap in the middle,
 *   and the old song sweeps out through a rising high-pass filter in the second half.
 * - [Style.QUICK]: a smooth swap over 4 beats, starting on the next bar.
 *
 * When the tempos differ, the new song starts matched to the old one and glides back to its own
 * tempo during the second half (the old deck follows, so the beats stay together) or after the
 * blend, at most 1% per bar. Every gain, knob and tempo change glides (here per block, and per
 * sample or 16 samples in [Deck] and [Mixer]), so nothing clicks or steps.
 *
 * Touching a control MIX is moving (the crossfader, a volume fader, an EQ knob it turns, the
 * filter or echo it uses, a tempo it glides), or starting / stopping a deck, hands control back
 * at once and leaves every control where it is ("take-over"); the channel gains glide from MIX's
 * curve to the crossfader's own over [HAND_BACK_SEC], so nothing jumps. Before the new song has
 * started, nothing was heard yet: cancelling then puts back what MIX set up. When a deck stops,
 * ends or changes mid-blend while nobody holds the controls, MIX finishes onto the song still
 * playing (or goes back to the old one), never leaving it bassless and half faded.
 *
 * [Mixer.render] steps it under [Mixer.lock] before each block, so it keeps going while the
 * screen shows something else. [pressed] is called under the lock too. The console reads the
 * public fields without the lock (a stale value lasts one frame) and shows [lastMessage] whenever
 * its [Message.seq] changes.
 */
class AutoMix internal constructor(private val mixer: Mixer) {
    enum class State { IDLE, ARMED, RUNNING }

    /** How the blend is measured: whole bars, beats (a short mix) or seconds (no beat to follow). */
    enum class Kind { BARS, BEATS, TIMED }

    /** How MIX hands over (Settings → MIX style). Near a song's end or without a beat it blends [SMOOTH]ly. */
    enum class Style {
        SMOOTH, ECHO, FILTER, QUICK;

        companion object {
            /** The style saved under [name] (as hosts store it), or [SMOOTH]. */
            fun of(name: String?) = values().firstOrNull { it.name == name } ?: SMOOTH
        }
    }

    /**
     * A message for the console's notice line, edged in [deck]'s colour (-1: neutral).
     * [withUpNext] is shown instead when a song already waits to load on the deck MIX stopped
     * ([from]): the finish message then leaves out its "tap NEXT" advice.
     */
    class Message(val text: String, val deck: Int, val seq: Int, val withUpNext: String = text)

    var state = State.IDLE; private set
    var kind = Kind.BARS; private set
    /** The deck being mixed out ([from]) and the one being mixed in ([to]). */
    var from = 0; private set
    var to = 1; private set

    /** MIX length in bars: 4, 8, 16 or 32 (the console's hold on MIX cycles them). [Style.QUICK] always takes 4 beats. */
    var bars = 8
        set(v) { field = if (v in LENGTHS) v else 8 }

    /** How MIX blends (a setting, saved by the host). */
    var style = Style.SMOOTH

    /** The style the current (or last) blend uses: [style], or [Style.SMOOTH] for a short or timed fade. */
    var runStyle = Style.SMOOTH; private set

    /** The running blend's length in bars ([Kind.BARS] only; shorter than [bars] near a song's end). */
    var runBars = 0; private set
    /** The blend's length: beats, or seconds for [Kind.TIMED]. */
    var length = 0.0; private set
    /**
     * How far the blend has got, in the units of [length]. It counts elapsed time at the outgoing
     * deck's tempo (not its beat index), so it never jumps back when that deck loops or is scratched.
     */
    var done = 0.0; private set
    /** 0..1 through the blend. */
    val progress: Double get() = if (length > 0) (done / length).coerceIn(0.0, 1.0) else 0.0

    @Volatile var lastMessage: Message? = null; private set
    val message: String? get() = lastMessage?.text
    val messageSeq: Int get() = lastMessage?.seq ?: 0

    // Where the new song starts: [from]'s beat position (bar lines are multiples of 4).
    private var target = 0.0
    private var armedBeats = 0.0
    /** Beats MIX planned to wait for [target]; waiting much longer (a short loop on [from]) starts on the next beat. */
    private var armWait = 0.0
    /** Started waiting too long (e.g. a short loop on [from]): start on its next whole beat instead. */
    private var onNextBeat = false
    private var prevFrac = 0.0

    // The blend's shape, in the units of [length].
    private var swapMid = 0.0
    private var swapWidth = 0.0
    private var swapped = false
    /** [Style.ECHO]: the echo tail at the end; the old song's own sound stops at [length] - [tail]. */
    private var tail = 0.0
    private var echoOn = false
    private var dryCut = false
    private var xStart = 0f
    private var xEnd = 0f
    // Where things were when the blend began (the curves start from them, so nothing jumps).
    private var gOut0 = 1f
    private var gIn0 = 0f
    private var outLow0 = 0f; private var outMid0 = 0f; private var outHigh0 = 0f; private var outFilter0 = 0f
    private var inLow0 = 0f; private var inMid0 = 0f; private var inHigh0 = 0f

    /** One control MIX moves or watches: what it last wrote or found, so any other change is noticed as a take-over. */
    private inner class Ctl(val tol: Float, val read: () -> Float, val write: (Float) -> Unit) {
        var set = 0f
        var owned = false
        fun own() { set = read(); owned = true }
        fun put(v: Float) { write(v); set = read() }
        val moved: Boolean get() = owned && abs(read() - set) > tol
    }

    private val xf = Ctl(0.02f, { mixer.crossfader }, { mixer.crossfader = it })
    private val volFrom = Ctl(0.001f, { deck(from).volume }, { deck(from).volume = it })
    private val volTo = Ctl(0.001f, { deck(to).volume }, { deck(to).volume = it })
    private val lowFrom = Ctl(0.02f, { deck(from).eqLow }, { deck(from).eqLow = it })
    private val lowTo = Ctl(0.02f, { deck(to).eqLow }, { deck(to).eqLow = it })
    private val midFrom = Ctl(0.02f, { deck(from).eqMid }, { deck(from).eqMid = it })
    private val midTo = Ctl(0.02f, { deck(to).eqMid }, { deck(to).eqMid = it })
    private val highFrom = Ctl(0.02f, { deck(from).eqHigh }, { deck(from).eqHigh = it })
    private val highTo = Ctl(0.02f, { deck(to).eqHigh }, { deck(to).eqHigh = it })
    private val filterFrom = Ctl(0.02f, { deck(from).filter }, { deck(from).filter = it })
    private val ctls = listOf(xf, volFrom, volTo, lowFrom, lowTo, midFrom, midTo, highFrom, highTo, filterFrom)
    private val fromCtls = listOf(volFrom, lowFrom, midFrom, highFrom, filterFrom)
    private val toCtls = listOf(volTo, lowTo, midTo, highTo)

    private var expectFrom = true
    private var expectTo = false
    private var fromPcm: Pcm? = null
    private var toPcm: Pcm? = null
    // How things were before MIX changed them, to put back when it stops early.
    private var cfBefore = 0f
    private var lowFromBefore = 0f; private var midFromBefore = 0f; private var highFromBefore = 0f; private var filterFromBefore = 0f
    private var lowToBefore = 0f; private var midToBefore = 0f; private var highToBefore = 0f
    private var trimToBefore = 0.0
    private var trimToSet = 0.0
    private var toTitle = ""
    // The old deck's effect before an echo out, put back once it has rung out.
    private var fxType = FxType.ECHO
    private var fxAmount = 0f
    private var fxBeats = 0.0
    /** Frames rendered since the last [step] (audio time only moves while the mixer renders). */
    private var lastFrames = 0

    // The channel gains MIX wants (by deck), and the hand-back: what's added to the crossfader's own
    // gains, with a weight that is 1 while MIX blends and glides to 0 after it stops.
    private val want = FloatArray(2)
    private val handOffset = FloatArray(2)
    private var handWeight = 0f

    // The tempo glide of [glideDeck] (-1: none) from pitch [glideP0] to 0 over [glideSec] seconds.
    private var glideDeck = -1
    private var glidePcm: Pcm? = null
    private var glideP0 = 0.0
    private var glideSec = 0.0
    private var glideT = 0.0
    private var glidePitchSet = 0.0
    /** The old deck follows the glide while the blend runs (tempo ratio [glideK]: 1, or 2 / 0.5 for double time). */
    private var glideFollow = false
    private var glideK = 1.0
    private var followSet = 0.0
    private var glideChecked = false
    /** The glide waits for the end of the blend (the old deck can't follow it within its tempo range). */
    private var glideAtFinish = false
    /** After a take-over during an echo out: seconds until the paused old deck's sound is put back on. */
    private var dryRestore = -1.0

    private fun deck(i: Int) = mixer.decks[i]
    private fun live(i: Int) = deck(i).let { it.playing && !it.previewing }
    /** The crossfader position that plays only deck [i]. */
    private fun end(i: Int) = if (i == 0) -1f else 1f

    /** Real seconds left on deck [i] at its current tempo. */
    fun secondsLeft(i: Int): Double {
        val d = deck(i)
        return (d.durationSec - d.positionSec).coerceAtLeast(0.0) / (1 + d.pitch)
    }

    /** How long a MIX out of deck [i] takes, in seconds: [bars] (or 4 beats when quick) at its tempo, or the 10 s fade without a beat. */
    fun mixSeconds(i: Int): Double {
        val bpm = deck(i).effectiveBpm
        val beats = if (style == Style.QUICK) QUICK_BEATS else bars * 4.0
        return if (bpm > 0) beats * 60.0 / bpm else NO_BEAT_FADE
    }

    /**
     * The deck MIX would mix out of and the one it would mix into, or null when nothing plays.
     * One deck playing: out of it, into the other. Both playing: out of the deck the crossfader
     * favours, or at the centre the one that ends first.
     */
    fun pick(): Pair<Int, Int>? {
        val a = live(0); val b = live(1)
        val f = when {
            a && b -> when {
                mixer.crossfader < -0.05f -> 0
                mixer.crossfader > 0.05f -> 1
                secondsLeft(0) <= secondsLeft(1) -> 0
                else -> 1
            }
            a -> 0
            b -> 1
            else -> return null
        }
        return f to 1 - f
    }

    /**
     * Real seconds until MIX stops [from] (it is waiting to start or blending), or null when it is
     * idle. The library's countdown and "Load when A ends" use it: during a MIX, the old song ends
     * when MIX finishes, not at its own end.
     */
    fun secondsToFinish(): Double? {
        val bpm = deck(from).effectiveBpm
        val beats = when (state) {
            State.IDLE -> return null
            State.ARMED -> (if (onNextBeat) 1.0 else (target - deck(from).beatPosition()).coerceAtLeast(0.0)) + length
            State.RUNNING -> if (kind == Kind.TIMED) return (length - done).coerceAtLeast(0.0) else length - done
        }
        return if (bpm > 0) beats.coerceAtLeast(0.0) * 60 / bpm else null
    }

    /** Beats until the new song starts, while [State.ARMED] (at least 1). */
    fun beatsToStart(): Int {
        if (state != State.ARMED) return 0
        if (onNextBeat) return 1
        return ceil(target - deck(from).beatPosition()).toInt().coerceAtLeast(1)
    }

    /** The bar (1-based) or beat the blend is in now. */
    fun current(): Int = when (kind) {
        Kind.BARS -> (floor(done / 4).toInt() + 1).coerceIn(1, maxOf(1, runBars))
        else -> (floor(done).toInt() + 1).coerceIn(1, maxOf(1, length.toInt()))
    }

    /**
     * The channel gains for decks A and B, given the crossfader curve's [curve] gains: MIX's own
     * while it blends, then gliding back to the crossfader's over [HAND_BACK_SEC]. Called by
     * [Mixer.render] under the lock.
     */
    internal fun channelGains(curve: Pair<Float, Float>): Pair<Float, Float> {
        if (handWeight <= 0f) return curve
        return (curve.first + handOffset[0] * handWeight).coerceIn(0f, 1f) to
            (curve.second + handOffset[1] * handWeight).coerceIn(0f, 1f)
    }

    /** MIX was tapped (or M pressed). Call it under [Mixer.lock]. */
    fun pressed() {
        if (state != State.IDLE) { takeOver(); return }
        val (f, t) = pick() ?: return say("Start a song first: press ▶ on deck A or B", -1)
        val fd = deck(f); val td = deck(t)
        if (!td.loaded) return say("Load the next song first: tap NEXT", t)
        stopGlide()
        restoreDry()
        from = f; to = t
        val bothLive = live(t)
        cfBefore = mixer.crossfader; toTitle = td.title
        lowFromBefore = fd.eqLow; midFromBefore = fd.eqMid; highFromBefore = fd.eqHigh; filterFromBefore = fd.filter
        lowToBefore = td.eqLow; midToBefore = td.eqMid; highToBefore = td.eqHigh; trimToBefore = td.trimDb
        if (!bothLive) {
            if (td.previewing) td.cueUp()
            td.pendingStart = false
            if (fd.bpm > 0 && td.bpm > 0) td.syncTo(fd)
            td.volume = 1f; td.filter = 0f; td.fx.on = false
            // Bass held back, mids and highs a little low: they open up as it comes in.
            td.eqLow = -1f; td.eqMid = IN_MID; td.eqHigh = IN_HIGH
            td.trimDb = matchedTrim(fd, td)
            if (td.loopOn) td.loopToggle()
            // On the outgoing deck's half (or the centre): all the way to its end. That's silent,
            // as both curves keep a deck at full volume on its own half.
            if (mixer.crossfader * end(f) > -0.05f) mixer.crossfader = end(f)
        }
        trimToSet = td.trimDb
        xStart = mixer.crossfader; xEnd = end(t)
        ctls.forEach { it.owned = false }
        listOf(xf, volFrom, volTo, lowFrom, lowTo, midTo, highTo).forEach { it.own() }
        fromPcm = fd.pcm; toPcm = td.pcm
        swapped = false; echoOn = false; dryCut = false; glideChecked = false; glideAtFinish = false
        done = 0.0; armedBeats = 0.0; onNextBeat = false; lastFrames = 0; runBars = 0; tail = 0.0

        if (fd.bpm <= 0 || td.bpm <= 0) {
            timed(NO_BEAT_FADE, bothLive)
            return say("No beat to follow: fading over 10 seconds")
        }
        val fb = fd.beatPosition()
        val beatsLeft = ((fd.pcm?.frames ?: 0) - fd.position) / fd.framesPerBeat
        val bar = if (bothLive) fb else 4 * floor(fb / 4) + 4
        if (style == Style.QUICK && beatsLeft - (bar - fb) - END_MARGIN >= QUICK_BEATS) {
            kind = Kind.BEATS; length = QUICK_BEATS
            shapeFor(Style.QUICK)
            if (bothLive) { run(0.0); return say(mixingInto()) }
            arm(bar, fb)
            return say(startsIn())
        }
        // The blend ends a moment before the old song does, so MIX (not the song's end) stops it.
        val rb = minOf(bars, floor((beatsLeft - (bar - fb) - END_MARGIN) / 4).toInt())
        if (style != Style.QUICK && rb >= 2) {
            kind = Kind.BARS; runBars = rb; length = 4.0 * rb
            shapeFor(style)
            if (bothLive) { run(0.0); return say(mixingInto()) }
            val start = if (fd.loopOn) null else phraseStart(fd, fb, bar, beatsLeft)
            arm(start ?: bar, fb)
            return say(startsIn())
        }
        // Not enough left for 2 bars (or 4 beats): start on the next beat and blend over the beats that are left.
        val n = floor(beatsLeft).toInt()
        val short = if (n >= 1) "Short mix: $n beat${if (n == 1) "" else "s"} left in ${fd.title}"
            else "Short mix: ${fd.title} is ending"
        if (beatsLeft < 2) { timed(1.0, bothLive); return say(short) }
        val beat = if (bothLive) fb else floor(fb) + 1
        kind = Kind.BEATS
        length = maxOf(2.0, floor(beatsLeft - (beat - fb) - END_MARGIN))
        shapeFor(Style.SMOOTH)
        if (bothLive) run(0.0) else arm(beat, fb)
        say(short)
    }

    /**
     * The trim that brings [td] in at the loudness [fd] plays at: its measured loudness after
     * auto gain and trim, and its volume fader (MIX puts the new deck's fader up).
     */
    private fun matchedTrim(fd: Deck, td: Deck): Double {
        val fader = if (fd.volume >= 0.3f) 40 * log10(fd.volume.toDouble()) else 0.0
        val out = fd.loudnessDb + fd.autoGainDb + fd.trimDb + fader
        val trim = (out - (td.loudnessDb + td.autoGainDb)).coerceIn(-9.0, 6.0)
        return Math.round(trim * 10) / 10.0
    }

    /**
     * Where on [fd]'s beat grid the new song should start so the old song's phrase (32 beats for a
     * blend of 8 bars or more, else 16, counted from its first downbeat) ends as the blend does (as
     * the echo starts, for an echo out): the first such bar line from [bar] on, at most 3 bars
     * later, with enough song left. Null when there isn't one (MIX then starts on the next bar).
     */
    private fun phraseStart(fd: Deck, fb: Double, bar: Double, beatsLeft: Double): Double? {
        val a = fd.analysis ?: return null
        if (a.bpm <= 0) return null
        val anchor = ((a.startCueSec() - a.firstBeatSec) * a.bpm / 60).roundToLong()
        val endAt = (length - tail).roundToLong()
        val phrases = if (length >= 32) listOf(32L, 16L) else listOf(16L)
        for (p in phrases) for (extra in 0..12 step 4) {
            val s = bar + extra
            if (s - fb + length + END_MARGIN > beatsLeft) break
            if (Math.floorMod(s.roundToLong() + endAt - anchor, p) == 0L) return s
        }
        return null
    }

    /** Sets the swap window and the echo tail for a blend of [length] in style [s]. */
    private fun shapeFor(s: Style) {
        runStyle = s
        tail = if (s == Style.ECHO) minOf(4.0, length / 4) else 0.0
        val beat = if (kind == Kind.TIMED) 0.5 else 1.0 // a "beat" of a timed fade: half a second
        when (s) {
            // The old bass is gone before the echo goes on, a beat before its sound stops: the echoes carry no bass.
            Style.ECHO -> { swapWidth = minOf(2 * beat, (length - tail - 1) / 2); swapMid = length - tail - 1 - swapWidth / 2 }
            Style.QUICK -> { swapWidth = 2 * beat; swapMid = length / 2 }
            else -> { swapWidth = minOf(4 * beat, length / 2); swapMid = length / 2 }
        }
    }

    private fun startsIn(): String {
        val n = beatsToStart()
        return "${deck(to).title} starts on deck ${name(to)} in $n beat${if (n == 1) "" else "s"} · tap MIX to cancel"
    }

    /** Takes the controls back from MIX (a second tap, or the user moved something). */
    private fun takeOver() {
        val text = when {
            state == State.ARMED -> "MIX cancelled"
            kind == Kind.BARS -> "You've got it: MIX stopped at bar ${current()} of $runBars"
            kind == Kind.BEATS -> "You've got it: MIX stopped at beat ${current()} of ${length.toInt()}"
            else -> "You've got it: MIX stopped"
        }
        if (state == State.ARMED) {
            // Nothing was heard yet: put back what MIX set up, wherever the user hasn't moved it since.
            val td = deck(to)
            if (!xf.moved) mixer.crossfader = cfBefore
            if (td.pcm === toPcm) {
                if (!lowTo.moved) td.eqLow = lowToBefore
                if (!midTo.moved) td.eqMid = midToBefore
                if (!highTo.moved) td.eqHigh = highToBefore
                if (td.trimDb == trimToSet) td.trimDb = trimToBefore
            }
        } else {
            release()
            if (dryCut) {
                // The old song's own sound was going: it stays gone (stopped), its sound back on once it's still.
                val fd = deck(from)
                if (fd.pcm === fromPcm && fd.playing) fd.pause()
                dryRestore = DRY_RESTORE_SEC
            }
        }
        stopGlide()
        state = State.IDLE
        say(text)
    }

    /**
     * Mid-blend, a deck stopped, ended or changed while the crossfader and the other deck's
     * controls are still where MIX put them (nobody holds them). Old song gone: finish, so the new
     * one plays alone with its bass. New song gone: back to the old one as it was before MIX.
     * Returns true when it handled it; anything else is a take-over.
     */
    private fun deckLeft(): Boolean {
        if (state != State.RUNNING || xf.moved) return false
        val fd = deck(from); val td = deck(to)
        val fromGone = fd.pcm !== fromPcm || !fd.playing
        val toGone = td.pcm !== toPcm || (!td.playing && !td.pendingStart)
        val fromHeld = fd.pcm === fromPcm && live(from) && fromCtls.none { it.moved } && !fxMoved()
        val toHeld = td.pcm === toPcm && live(to) && !td.pendingStart && toCtls.none { it.moved }
        when {
            fromGone && toHeld -> finish()
            toGone && fromHeld -> {
                mixer.crossfader = xStart
                release()
                fd.eqLow = lowFromBefore; fd.eqMid = midFromBefore; fd.eqHigh = highFromBefore; fd.filter = filterFromBefore
                if (echoOn) { fd.fx.on = false; restoreFx(fd) }
                fd.dryLevel = 1f
                if (td.pcm === toPcm && !lowTo.moved) td.eqLow = lowToBefore
                stopGlide()
                state = State.IDLE
                say("MIX stopped: $toTitle isn't playing", from)
            }
            else -> return false
        }
        return true
    }

    private fun arm(beat: Double, fb: Double) {
        target = beat
        armWait = beat - fb
        state = State.ARMED; expectFrom = true; expectTo = false
    }

    /** A fade timed in seconds, starting now (no beat to follow, or under 2 beats left). */
    private fun timed(seconds: Double, bothLive: Boolean) {
        kind = Kind.TIMED; length = seconds
        shapeFor(Style.SMOOTH)
        if (!bothLive) deck(to).startAt(deck(to).cuePoint)
        run(0.0)
    }

    /** The blend starts [already] units in: the curves start from where everything is now. */
    private fun run(already: Double) {
        done = already
        state = State.RUNNING; expectFrom = true; expectTo = true
        val fd = deck(from); val td = deck(to)
        val g = mixer.crossfaderGains()
        gOut0 = if (from == 0) g.first else g.second
        gIn0 = if (to == 0) g.first else g.second
        outLow0 = fd.eqLow; outMid0 = fd.eqMid; outHigh0 = fd.eqHigh; outFilter0 = fd.filter
        inLow0 = td.eqLow; inMid0 = td.eqMid; inHigh0 = td.eqHigh
        midFrom.owned = false; highFrom.owned = false; filterFrom.owned = false
        when (runStyle) {
            Style.FILTER -> filterFrom.own()
            Style.ECHO -> {}
            else -> { midFrom.own(); highFrom.own() }
        }
        fxType = fd.fx.type; fxAmount = fd.fx.amount; fxBeats = fd.fx.beats
        want[from] = gOut0; want[to] = gIn0
    }

    /** Called by [Mixer.render] before each block of [frames] frames, under the lock. */
    internal fun step(frames: Int) {
        val elapsedSec = lastFrames.toDouble() / mixer.sampleRate
        lastFrames = frames
        if (state != State.RUNNING && handWeight > 0f) handWeight = maxOf(0f, handWeight - (elapsedSec / HAND_BACK_SEC).toFloat())
        if (dryRestore >= 0) {
            dryRestore -= elapsedSec
            val fd = deck(from)
            if (dryRestore < 0 || fd.playing) { fd.dryLevel = 1f; dryRestore = -1.0 }
        }
        if (state == State.IDLE) { stepGlide(elapsedSec); return }
        val fd = deck(from)
        // The old song ran out during the blend: that's the end of the mix, not a take-over.
        val p = fd.pcm
        if (state == State.RUNNING && p != null && p === fromPcm && !fd.playing && fd.position >= p.frames) {
            finish(); stepGlide(elapsedSec); return
        }
        if (tookOver()) { if (!deckLeft()) takeOver(); stepGlide(elapsedSec); return }
        when (state) {
            State.ARMED -> {
                val fb = fd.beatPosition()
                armedBeats += elapsedSec * fd.effectiveBpm / 60
                if (!onNextBeat && armedBeats > maxOf(4.5, armWait + 0.5)) { onNextBeat = true; prevFrac = fb - floor(fb) }
                val offset: Double? = if (onNextBeat) {
                    val fr = fb - floor(fb)
                    (if (fr < prevFrac) fr else null).also { prevFrac = fr }
                } else if (fb >= target) {
                    if (fb - target < 1) fb - target else {
                        // The old song jumped ahead (a seek): aim at its next bar (or beat) from here.
                        target = if (kind == Kind.BARS || kind == Kind.BEATS && runStyle == Style.QUICK) 4 * floor(fb / 4) + 4 else floor(fb) + 1
                        armWait = target - fb; armedBeats = 0.0
                        null
                    }
                } else null
                if (offset != null) start(offset)
            }
            State.RUNNING -> {
                done += if (kind == Kind.TIMED) elapsedSec else elapsedSec * fd.effectiveBpm / 60
                shape()
                if (state == State.RUNNING && progress >= 1.0) finish()
            }
            State.IDLE -> {}
        }
        stepGlide(elapsedSec)
    }

    /** Starts the new song [offset] beats past its cue, as [from] is that far past the start line. */
    private fun start(offset: Double) {
        val fd = deck(from); val td = deck(to)
        if (fd.bpm > 0 && td.bpm > 0) td.syncTo(fd) // tempo only: it's stopped
        val cue = if (td.bpm > 0) td.nearestBeat(td.cuePoint) else td.cuePoint
        td.startAt(cue + offset * td.framesPerBeat)
        run(offset)
        say(mixingInto())
    }

    private fun mixingInto(): String {
        val title = deck(to).title
        val how = when {
            runStyle == Style.QUICK -> "Quick mix into $title"
            kind != Kind.BARS -> "Mixing into $title"
            runStyle == Style.ECHO -> "Mixing into $title over $runBars bars with an echo out"
            runStyle == Style.FILTER -> "Mixing into $title over $runBars bars with a filter sweep"
            else -> "Mixing into $title over $runBars bars"
        }
        return "$how · touch the crossfader to take over"
    }

    /** Puts every control MIX moves where the blend is now ([done] of [length]). */
    private fun shape() {
        val fd = deck(from); val td = deck(to)
        val len = length
        val u = done.coerceIn(0.0, len)
        val p = u / len
        val dryAt = len - tail
        // How far the old song has gone out and the new one has come in, 0..1 (an S-curve in time).
        val eOut: Double; val eIn: Double
        when (runStyle) {
            Style.FILTER -> { eIn = ease(p / 0.55); eOut = ease((p - 0.6) / 0.4) }
            Style.ECHO -> {
                // Under the old song (to -3 dB) until the last two beats before its sound stops, then all the way.
                val pre = dryAt - minOf(2.0, dryAt / 2)
                eIn = if (u < pre) 0.5 * ease(u / pre) else 0.5 + 0.5 * ease((u - pre) / (dryAt - pre))
                eOut = ease((u - dryAt) / tail)
            }
            else -> { eIn = ease(p); eOut = eIn }
        }
        // Equal power: as one goes down the other comes up, so the loudness holds.
        val gOut = (gOut0 * cos(eOut * PI / 2)).toFloat().coerceIn(0f, 1f)
        val gIn = (gIn0 + (1 - gIn0) * sin(eIn * PI / 2)).toFloat().coerceIn(0f, 1f)
        want[from] = gOut; want[to] = gIn
        xf.put(if (p >= 1) xEnd else xStart + (xEnd - xStart) * ease(p).toFloat())
        val user = mixer.crossfaderGains()
        handOffset[0] = want[0] - user.first; handOffset[1] = want[1] - user.second; handWeight = 1f

        // The basses swap over [swapWidth] around [swapMid], equal power, so they're never both full.
        val w = ease((u - (swapMid - swapWidth / 2)) / swapWidth)
        lowFrom.put(if (w >= 1) -1f else gainToKnob(knobToGain(outLow0) * cos(w * PI / 2)))
        lowTo.put(if (w >= 1) 0f else gainToKnob(maxOf(knobToGain(inLow0), sin(w * PI / 2))))
        if (!swapped && w >= 1) { swapped = true; say("Bass swapped") }

        // The new song's mids and highs open up in the first half (by the echo for an echo out).
        val opens = if (runStyle == Style.ECHO) ease(u / dryAt) else ease((p - 0.1) / 0.45)
        midTo.put(if (opens >= 1) 0f else (inMid0 * (1 - opens)).toFloat())
        highTo.put(if (opens >= 1) 0f else (inHigh0 * (1 - opens)).toFloat())
        when (runStyle) {
            Style.FILTER -> {
                // The old song thins out through a rising high-pass, once its bass is out (the filter's
                // resonance would lift the bass as it went by).
                val sweep = ease((p - 0.55) / 0.45)
                filterFrom.put((outFilter0 + (maxOf(outFilter0, FILTER_END) - outFilter0) * sweep).toFloat())
            }
            Style.ECHO -> echoOut(fd, u, dryAt)
            else -> {
                // The old song's mids and highs ease down a little in the second half.
                val down = ease((p - 0.5) / 0.5)
                midFrom.put((outMid0 + ((outMid0 + OUT_MID).coerceAtLeast(-1f) - outMid0) * down).toFloat())
                highFrom.put((outHigh0 + ((outHigh0 + OUT_HIGH).coerceAtLeast(-1f) - outHigh0) * down).toFloat())
            }
        }
        // The tempo glide starts halfway (or waits for the end when the old deck can't follow it).
        if (!glideChecked && u >= len / 2) { glideChecked = true; planGlide(td, fd) }
    }

    /**
     * [Style.ECHO]: a beat before [dryAt] the old deck's echo goes on (3/4 beat, long feedback);
     * at [dryAt] its own sound fades out over a quarter beat, leaving the echoes, and the deck stops.
     */
    private fun echoOut(fd: Deck, u: Double, dryAt: Double) {
        if (!echoOn && u >= dryAt - 1) {
            echoOn = true
            fd.fx.type = FxType.ECHO; fd.fx.beats = 0.75; fd.fx.amount = 0.6f; fd.fx.on = true
        }
        if (u >= dryAt) {
            dryCut = true
            val dry = 1 - ease((u - dryAt) / 0.25)
            fd.dryLevel = dry.toFloat()
            if (dry <= 0 && fd.playing) {
                fd.fx.on = false // no more input: the echoes ring out
                fd.pause()
                expectFrom = false
            }
        }
    }

    private fun fxMoved() = echoOn && expectFrom && !deck(from).fx.on

    private fun restoreFx(fd: Deck) { fd.fx.type = fxType; fd.fx.amount = fxAmount; fd.fx.beats = fxBeats }

    /** MIX stops: the channel gains glide from MIX's last ones to the crossfader's own. */
    private fun release() {
        val user = mixer.crossfaderGains()
        handOffset[0] = want[0] - user.first; handOffset[1] = want[1] - user.second
        handWeight = 1f
    }

    private fun restoreDry() {
        if (dryRestore >= 0) { deck(from).dryLevel = 1f; dryRestore = -1.0 }
    }

    /**
     * Halfway: plans the new deck's glide from the old song's tempo back to its own, at most 1% per
     * bar and over at least the second half. The old deck follows while the blend runs, when its
     * tempo range allows; otherwise the glide waits for the blend to end.
     */
    private fun planGlide(td: Deck, fd: Deck) {
        if (kind == Kind.TIMED || td.bpm <= 0 || fd.bpm <= 0 || abs(td.pitch) < 0.0005 || !td.playing) return
        val k0 = td.effectiveBpm / fd.effectiveBpm
        val k = listOf(0.5, 1.0, 2.0).minByOrNull { abs(k0 / it - 1) }!!
        val inTime = abs(k0 / k - 1) < 0.002
        val secondHalf = (length - done) * 60 / fd.effectiveBpm
        val p0 = td.pitch
        glideSec = maxOf(secondHalf, abs(p0) / MAX_GLIDE_PER_BAR * 4 * 60 / td.bpm)
        glideDeck = to; glidePcm = td.pcm; glideP0 = p0; glideT = 0.0; glidePitchSet = td.pitch; glideK = k
        // The old deck's tempo when the blend ends, if it follows.
        val pEnd = p0 * (1 - ease(secondHalf / glideSec))
        val needed = td.bpm * (1 + pEnd) / k / fd.bpm - 1
        glideFollow = inTime && abs(needed) <= fd.pitchRange
        followSet = fd.pitch
        glideAtFinish = !glideFollow
        if (glideAtFinish) glideDeck = -1
    }

    private fun stepGlide(sec: Double) {
        if (glideDeck < 0) return
        val d = deck(glideDeck)
        if (d.pcm !== glidePcm || !d.playing || abs(d.pitch - glidePitchSet) > 1e-9) { stopGlide(); return }
        glideT += sec
        val s = ease(glideT / glideSec)
        d.pitch = if (s >= 1) 0.0 else glideP0 * (1 - s)
        glidePitchSet = d.pitch
        if (glideFollow && state == State.RUNNING && glideDeck == to) {
            val o = deck(from)
            o.pitch = d.bpm * (1 + d.pitch) / glideK / o.bpm - 1
            followSet = o.pitch
        }
        if (s >= 1) glideDeck = -1
    }

    private fun stopGlide() { glideDeck = -1; glideFollow = false }

    /** The blend is done: the new song plays alone and the old deck is stopped on a clean channel. */
    private fun finish() {
        val fd = deck(from); val td = deck(to)
        // From MIX's last gains (all the new song at the end of a full blend), gliding to the crossfader's.
        mixer.crossfader = xEnd
        release()
        td.eqLow = 0f; td.eqMid = 0f; td.eqHigh = 0f
        fd.pause()
        fd.eqHigh = 0f; fd.eqMid = 0f; fd.eqLow = 0f; fd.filter = 0f; fd.trimDb = 0.0; fd.fx.on = false
        if (echoOn) restoreFx(fd)
        fd.dryLevel = 1f
        if (fd.loopOn) fd.loopToggle()
        glideFollow = false
        if (glideAtFinish && td.playing && td.bpm > 0 && abs(td.pitch) >= 0.0005) {
            glideDeck = to; glidePcm = td.pcm; glideP0 = td.pitch; glideT = 0.0; glidePitchSet = td.pitch
            glideSec = abs(td.pitch) / MAX_GLIDE_PER_BAR * 4 * 60 / td.bpm
        }
        glideAtFinish = false
        state = State.IDLE
        say("Now playing ${td.title} · tap NEXT for what fits after it", withUpNext = "Now playing ${td.title}")
    }

    /** Something other than MIX changed what MIX controls. */
    private fun tookOver(): Boolean {
        val fd = deck(from); val td = deck(to)
        val gliding = glideDeck == to && state == State.RUNNING
        return !fd.loaded || !td.loaded || fd.pcm !== fromPcm || td.pcm !== toPcm ||
            ctls.any { it.moved } || fxMoved() ||
            fd.playing != expectFrom || td.playing != expectTo || td.pendingStart ||
            fd.previewing || td.previewing ||
            (gliding && abs(td.pitch - glidePitchSet) > 1e-9) ||
            (gliding && glideFollow && abs(fd.pitch - followSet) > 1e-9)
    }

    private fun say(text: String, deck: Int = to, withUpNext: String = text) {
        lastMessage = Message(text, deck, messageSeq + 1, withUpNext)
    }

    private fun name(i: Int) = if (i == 0) "A" else "B"

    companion object {
        val LENGTHS = listOf(4, 8, 16, 32)
        val STYLES = Style.values().toList()
        const val NO_BEAT_FADE = 10.0
        /** [Style.QUICK]'s length in beats. */
        const val QUICK_BEATS = 4.0
        /** Beats of the old song left over when a blend that's as long as the song allows ends. */
        private const val END_MARGIN = 0.25
        /** Seconds over which the channel gains glide back to the crossfader's own after MIX stops. */
        const val HAND_BACK_SEC = 1.5
        /** The fastest tempo glide: 1% per bar. */
        private const val MAX_GLIDE_PER_BAR = 0.01
        private const val DRY_RESTORE_SEC = 0.15
        // Where the new song's mids and highs start (-4 dB, -6 dB), and how far the old one's ease down.
        private const val IN_MID = -0.1f
        private const val IN_HIGH = -0.15f
        private const val OUT_MID = -0.1f
        private const val OUT_HIGH = -0.15f
        /** [Style.FILTER]: where the old song's high-pass ends (about 3 kHz). */
        private const val FILTER_END = 0.8f

        /** Smoothstep: 0 below 0, 1 above 1, an S-curve between (no sudden start or stop). */
        fun ease(x: Double): Double {
            val t = x.coerceIn(0.0, 1.0)
            return t * t * (3 - 2 * t)
        }

        /** An EQ knob (-1..+1) as a linear gain, the way [ThreeBandEq] hears it. */
        fun knobToGain(k: Float): Double = dbToGain(ThreeBandEq.knobToDb(k))

        /** The EQ knob position for a linear gain (0 → the kill at -1). */
        fun gainToKnob(g: Double): Float {
            if (g <= 0.0) return -1f
            val db = gainToDb(g)
            return (if (db >= 0) db / 6 else db / 40).toFloat().coerceIn(-1f, 1f)
        }
    }
}
