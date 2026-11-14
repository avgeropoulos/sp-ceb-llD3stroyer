package com.mixdeck.engine

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/**
 * MIX: blends the playing song into the one waiting on the other deck, the way a DJ would. It
 * starts the new song on the next bar, in time ([Deck.startAt]), brings it in with the crossfader
 * over [bars] bars, swaps the basses halfway and stops the old deck.
 *
 * Touching the crossfader, a LOW knob or a volume fader, or starting / stopping a deck, hands
 * control back at once and leaves every control where it is ("take-over"). Before the new song
 * has started, nothing was heard yet: cancelling then puts back the crossfader and LOW knob MIX
 * moved. When a deck stops, ends or changes mid-blend while nobody holds the controls, MIX
 * finishes onto the song still playing (or goes back to the old one), never leaving it bassless
 * and half faded.
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

    /** MIX length in bars: 4, 8, 16 or 32 (the console's hold on MIX cycles them). */
    var bars = 8
        set(v) { field = if (v in LENGTHS) v else 8 }

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
    /** Started waiting too long (e.g. a short loop on [from]): start on its next whole beat instead. */
    private var onNextBeat = false
    private var prevFrac = 0.0
    private var swapAt = 0.0
    private var swapped = false
    private var xStart = 0f
    private var xEnd = 0f
    // What MIX last wrote or found, so any other change is noticed as a take-over.
    private var xfSet = 0f
    private var lowFromSet = 0f
    private var lowToSet = 0f
    private var volFromSet = 1f
    private var volToSet = 1f
    private var expectFrom = true
    private var expectTo = false
    private var fromPcm: Pcm? = null
    private var toPcm: Pcm? = null
    // How things were before MIX changed them, to put back when it stops early.
    private var cfBefore = 0f
    private var lowFromBefore = 0f
    private var lowToBefore = 0f
    private var toTitle = ""
    /** Frames rendered since the last [step] (audio time only moves while the mixer renders). */
    private var lastFrames = 0

    private fun deck(i: Int) = mixer.decks[i]
    private fun live(i: Int) = deck(i).let { it.playing && !it.previewing }
    /** The crossfader position that plays only deck [i]. */
    private fun end(i: Int) = if (i == 0) -1f else 1f

    /** Real seconds left on deck [i] at its current tempo. */
    fun secondsLeft(i: Int): Double {
        val d = deck(i)
        return (d.durationSec - d.positionSec).coerceAtLeast(0.0) / (1 + d.pitch)
    }

    /** How long a MIX out of deck [i] takes, in seconds: [bars] at its tempo, or the 10 s fade without a beat. */
    fun mixSeconds(i: Int): Double {
        val bpm = deck(i).effectiveBpm
        return if (bpm > 0) bars * 4 * 60.0 / bpm else NO_BEAT_FADE
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

    /** MIX was tapped (or M pressed). Call it under [Mixer.lock]. */
    fun pressed() {
        if (state != State.IDLE) { takeOver(); return }
        val (f, t) = pick() ?: return say("Start a song first: press ▶ on deck A or B", -1)
        val fd = deck(f); val td = deck(t)
        if (!td.loaded) return say("Load the next song first: tap NEXT", t)
        from = f; to = t
        val bothLive = live(t)
        cfBefore = mixer.crossfader; lowFromBefore = fd.eqLow; lowToBefore = td.eqLow; toTitle = td.title
        if (!bothLive) {
            if (td.previewing) td.cueUp()
            td.pendingStart = false
            if (fd.bpm > 0 && td.bpm > 0) td.syncTo(fd)
            td.volume = 1f; td.eqLow = -1f; td.filter = 0f; td.fx.on = false
            if (td.loopOn) td.loopToggle()
            // On the outgoing deck's half (or the centre): all the way to its end. That's silent,
            // as both curves keep a deck at full volume on its own half.
            if (mixer.crossfader * end(f) > -0.05f) mixer.crossfader = end(f)
        }
        xStart = mixer.crossfader; xEnd = end(t)
        xfSet = xStart; lowFromSet = fd.eqLow; lowToSet = td.eqLow; volFromSet = fd.volume; volToSet = td.volume
        fromPcm = fd.pcm; toPcm = td.pcm
        swapped = false; done = 0.0; armedBeats = 0.0; onNextBeat = false; lastFrames = 0; runBars = 0

        if (fd.bpm <= 0 || td.bpm <= 0) {
            timed(NO_BEAT_FADE, bothLive)
            return say("No beat to follow: fading over 10 seconds")
        }
        val fb = fd.beatPosition()
        val beatsLeft = ((fd.pcm?.frames ?: 0) - fd.position) / fd.framesPerBeat
        val bar = if (bothLive) fb else 4 * floor(fb / 4) + 4
        // The blend ends a moment before the old song does, so MIX (not the song's end) stops it.
        val rb = minOf(bars, floor((beatsLeft - (bar - fb) - END_MARGIN) / 4).toInt())
        if (rb >= 2) {
            kind = Kind.BARS; runBars = rb; length = 4.0 * rb
            // Halfway, on a bar line.
            swapAt = 4.0 * (rb / 2)
            if (bothLive) { run(0.0); return say(mixingInto()) }
            arm(bar)
            val n = beatsToStart()
            return say("${td.title} starts on deck ${name(t)} in $n beat${if (n == 1) "" else "s"} · tap MIX to cancel")
        }
        // Not enough left for 2 bars: start on the next beat and blend over the beats that are left.
        val n = floor(beatsLeft).toInt()
        val short = if (n >= 1) "Short mix: $n beat${if (n == 1) "" else "s"} left in ${fd.title}"
            else "Short mix: ${fd.title} is ending"
        if (beatsLeft < 2) { timed(1.0, bothLive); return say(short) }
        val beat = if (bothLive) fb else floor(fb) + 1
        kind = Kind.BEATS
        length = maxOf(2.0, floor(beatsLeft - (beat - fb) - END_MARGIN))
        swapAt = maxOf(1.0, floor(length / 2))
        if (bothLive) run(0.0) else arm(beat)
        say(short)
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
            if (abs(mixer.crossfader - xfSet) <= 0.02f) mixer.crossfader = cfBefore
            if (td.pcm === toPcm && abs(td.eqLow - lowToSet) <= 0.02f) td.eqLow = lowToBefore
        }
        state = State.IDLE
        say(text)
    }

    /**
     * Mid-blend, a deck stopped, ended or changed while the crossfader and the other deck's volume
     * and LOW are still where MIX put them (nobody holds them). Old song gone: finish, so the new
     * one plays alone with its bass. New song gone: back to the old one as it was before MIX.
     * Returns true when it handled it; anything else is a take-over.
     */
    private fun deckLeft(): Boolean {
        if (state != State.RUNNING || abs(mixer.crossfader - xfSet) > 0.02f) return false
        val fd = deck(from); val td = deck(to)
        val fromGone = fd.pcm !== fromPcm || !fd.playing
        val toGone = td.pcm !== toPcm || (!td.playing && !td.pendingStart)
        val fromHeld = fd.pcm === fromPcm && live(from) &&
            abs(fd.volume - volFromSet) <= 0.001f && abs(fd.eqLow - lowFromSet) <= 0.02f
        val toHeld = td.pcm === toPcm && live(to) && !td.pendingStart &&
            abs(td.volume - volToSet) <= 0.001f && abs(td.eqLow - lowToSet) <= 0.02f
        when {
            fromGone && toHeld -> finish()
            toGone && fromHeld -> {
                mixer.crossfader = xStart
                fd.eqLow = lowFromBefore
                if (td.pcm === toPcm && abs(td.eqLow - lowToSet) <= 0.02f) td.eqLow = lowToBefore
                state = State.IDLE
                say("MIX stopped: $toTitle isn't playing", from)
            }
            else -> return false
        }
        return true
    }

    private fun arm(beat: Double) {
        target = beat
        state = State.ARMED; expectFrom = true; expectTo = false
    }

    /** A fade timed in seconds, starting now (no beat to follow, or under 2 beats left). */
    private fun timed(seconds: Double, bothLive: Boolean) {
        kind = Kind.TIMED; length = seconds; swapAt = seconds / 2
        if (!bothLive) deck(to).startAt(deck(to).cuePoint)
        run(0.0)
    }

    private fun run(already: Double) {
        done = already
        state = State.RUNNING; expectFrom = true; expectTo = true
    }

    /** Called by [Mixer.render] before each block of [frames] frames, under the lock. */
    internal fun step(frames: Int) {
        if (state == State.IDLE) { lastFrames = frames; return }
        val fd = deck(from); val td = deck(to)
        val elapsedSec = lastFrames.toDouble() / mixer.sampleRate
        lastFrames = frames
        // The old song ran out during the blend: that's the end of the mix, not a take-over.
        val p = fd.pcm
        if (state == State.RUNNING && p != null && p === fromPcm && !fd.playing && fd.position >= p.frames) {
            finish(); return
        }
        if (tookOver()) { if (!deckLeft()) takeOver(); return }
        when (state) {
            State.ARMED -> {
                val fb = fd.beatPosition()
                armedBeats += elapsedSec * fd.effectiveBpm / 60
                if (!onNextBeat && armedBeats > 4.5) { onNextBeat = true; prevFrac = fb - floor(fb) }
                val offset: Double? = if (onNextBeat) {
                    val fr = fb - floor(fb)
                    (if (fr < prevFrac) fr else null).also { prevFrac = fr }
                } else if (fb >= target) {
                    if (fb - target < 1) fb - target else {
                        // The old song jumped ahead (a seek): aim at its next bar (or beat) from here.
                        target = if (kind == Kind.BARS) 4 * floor(fb / 4) + 4 else floor(fb) + 1
                        null
                    }
                } else null
                if (offset != null) start(offset)
            }
            State.RUNNING -> {
                done += if (kind == Kind.TIMED) elapsedSec else elapsedSec * fd.effectiveBpm / 60
                val t = progress.toFloat()
                mixer.crossfader = xStart + (xEnd - xStart) * t
                xfSet = mixer.crossfader
                if (!swapped && done >= swapAt) swapBass()
                if (t >= 1f) finish()
            }
            State.IDLE -> {}
        }
    }

    /** Starts the new song [offset] beats past its cue, as [from] is that far past the start line. */
    private fun start(offset: Double) {
        val fd = deck(from); val td = deck(to)
        if (fd.bpm > 0 && td.bpm > 0) td.syncTo(fd) // tempo only: it's stopped
        val cue = if (td.bpm > 0) td.nearestBeat(td.cuePoint) else td.cuePoint
        td.startAt(cue + offset * td.framesPerBeat)
        run(offset)
        if (kind == Kind.BARS) say(mixingInto())
    }

    private fun mixingInto() = "Mixing into ${deck(to).title} over $runBars bars · touch the crossfader to take over"

    private fun swapBass() {
        swapped = true
        deck(from).eqLow = -1f; deck(to).eqLow = 0f
        lowFromSet = -1f; lowToSet = 0f
        say("Bass swapped")
    }

    /** The blend is done: the new song plays alone and the old deck is stopped on a clean channel. */
    private fun finish() {
        val fd = deck(from); val td = deck(to)
        mixer.crossfader = xEnd
        if (!swapped) td.eqLow = 0f
        fd.pause()
        fd.eqHigh = 0f; fd.eqMid = 0f; fd.eqLow = 0f; fd.filter = 0f; fd.trimDb = 0.0; fd.fx.on = false
        if (fd.loopOn) fd.loopToggle()
        state = State.IDLE
        say("Now playing ${td.title} · tap NEXT for what fits after it", withUpNext = "Now playing ${td.title}")
    }

    /** Something other than MIX changed what MIX controls. */
    private fun tookOver(): Boolean {
        val fd = deck(from); val td = deck(to)
        return !fd.loaded || !td.loaded || fd.pcm !== fromPcm || td.pcm !== toPcm ||
            abs(mixer.crossfader - xfSet) > 0.02f ||
            abs(fd.eqLow - lowFromSet) > 0.02f || abs(td.eqLow - lowToSet) > 0.02f ||
            abs(fd.volume - volFromSet) > 0.001f || abs(td.volume - volToSet) > 0.001f ||
            fd.playing != expectFrom || td.playing != expectTo || td.pendingStart ||
            fd.previewing || td.previewing
    }

    private fun say(text: String, deck: Int = to, withUpNext: String = text) {
        lastMessage = Message(text, deck, messageSeq + 1, withUpNext)
    }

    private fun name(i: Int) = if (i == 0) "A" else "B"

    companion object {
        val LENGTHS = listOf(4, 8, 16, 32)
        const val NO_BEAT_FADE = 10.0
        /** Beats of the old song left over when a blend that's as long as the song allows ends. */
        private const val END_MARGIN = 0.25
    }
}
