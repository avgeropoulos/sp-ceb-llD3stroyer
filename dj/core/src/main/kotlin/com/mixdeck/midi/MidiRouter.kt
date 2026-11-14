package com.mixdeck.midi

import com.mixdeck.engine.Mixer
import com.mixdeck.ui.Console

/**
 * Makes a MIDI controller drive the console: every [MidiEvent] is looked up in the [map] and done
 * to the [mixer], exactly as the on-screen control would. While [learning] is set, the next
 * control that suits that action is linked to it instead.
 *
 * Events come in on the controller's own thread; things that open a window ([openLibrary],
 * [next], [record]) are handed to the host, which runs them on its screen thread.
 */
class MidiRouter(
    private val mixer: Mixer,
    private val console: Console,
    private val openLibrary: (deck: Int) -> Unit,
    private val next: () -> Unit,
    private val record: () -> Unit,
) {
    @Volatile var map = MidiMap()

    /** The action waiting for a control to be moved (setup screen), or null. */
    @Volatile var learning: MidiAction? = null

    /** Called (on the controller's thread) when [learning] got its control. */
    @Volatile var onLearned: ((MidiBinding) -> Unit)? = null

    /** When the last message came in, for the "it works" light on the setup screen (System.nanoTime ms). */
    @Volatile var lastEventMs = 0L; private set

    private val held = HashSet<MidiControl>()

    private inline fun act(crossinline f: Mixer.() -> Unit) = synchronized(mixer.lock) { mixer.f() }

    @Synchronized fun handle(e: MidiEvent) {
        lastEventMs = System.nanoTime() / 1_000_000
        learning?.let { a ->
            val b = learn(a, e) ?: return
            map.bind(b)
            learning = null
            if (e.on) held += e.control      // its release must not fire anything
            onLearned?.invoke(b)
            return
        }
        val b = map[e.control] ?: return
        val a = b.action
        if (a.key != null && !console.allows(a.key)) return
        when (a.type) {
            MidiType.BUTTON, MidiType.HOLD -> {
                // A button sends "down" once; a knob used as a button passes the middle once.
                val was = e.control in held
                if (e.on == was) return
                if (e.on) held += e.control else held -= e.control
                if (e.on) press(a) else if (a.type == MidiType.HOLD) release(a)
            }
            MidiType.KNOB -> turn(a, e.value)
            MidiType.CENTER -> turn(a, centered(e))
            MidiType.WHEEL -> {
                val steps = if (b.wheel == WheelMode.CENTERED) e.raw - 64 else if (e.raw < 64) e.raw else e.raw - 128
                if (steps != 0) act { decks[a.deck].jogMove(steps / STEPS_PER_TURN) }
            }
        }
    }

    /** The binding [e] makes for [a], or null when that control doesn't suit it (e.g. a pad for a fader). */
    private fun learn(a: MidiAction, e: MidiEvent): MidiBinding? = when (a.type) {
        MidiType.BUTTON, MidiType.HOLD ->
            if (e.control.kind == MidiControl.Kind.NOTE && e.on || e.control.kind == MidiControl.Kind.CC && e.raw == 127) MidiBinding(e.control, a) else null
        MidiType.KNOB, MidiType.CENTER -> if (e.control.kind != MidiControl.Kind.NOTE) MidiBinding(e.control, a) else null
        MidiType.WHEEL ->
            if (e.control.kind == MidiControl.Kind.CC) MidiBinding(e.control, a, if (e.raw in 56..72) WheelMode.CENTERED else WheelMode.TWOS) else null
    }

    /** -1..1 with the control's middle exactly 0. */
    private fun centered(e: MidiEvent): Float =
        if (e.control.kind == MidiControl.Kind.BEND) (e.value * 16383f - 8192f).let { if (it < 0) it / 8192f else it / 8191f }
        else (e.raw - 64).let { if (it < 0) it / 64f else it / 63f }

    private fun press(a: MidiAction) {
        val i = a.deck
        val id = if (i >= 0) a.id.dropLast(1) else a.id
        when (id) {
            "play" -> if (mixer.decks[i].loaded) act { playPressed(i) } else openLibrary(i)
            "cue" -> act { decks[i].cueDown(quantize) }
            "sync" -> act { sync(i) }
            "load" -> openLibrary(i)
            "touch" -> act { decks[i].jogTouch(true) }
            "phones" -> act { decks[i].headphone = !decks[i].headphone }
            "hc0_", "hc1_", "hc2_", "hc3_" -> act { decks[i].hotCue(id[2] - '0', quantize) }
            "loop1_", "loop2_", "loop4_", "loop8_" -> act { decks[i].autoLoop((id[4] - '0').toDouble(), quantize) }
            "half" -> act { decks[i].loopResize(0.5) }
            "double" -> act { decks[i].loopResize(2.0) }
            "fx" -> act { decks[i].fx.on = !decks[i].fx.on }
            "mix" -> console.mixTapped()
            "next" -> next()
            "rec" -> record()
            "pad0", "pad1", "pad2", "pad3" -> { val n = id[3] - '0'; act { if (sampler.isPlaying(n)) sampler.stop(n) else sampler.trigger(n) } }
        }
    }

    private fun release(a: MidiAction) {
        val i = a.deck
        when (a.id.dropLast(1)) {
            "cue" -> act { decks[i].cueUp() }
            "touch" -> act { decks[i].jogTouch(false) }
        }
    }

    private fun turn(a: MidiAction, v: Float) {
        val i = a.deck
        when (if (i >= 0) a.id.dropLast(1) else a.id) {
            "vol" -> act { decks[i].volume = v }
            "pitch" -> act { decks[i].pitch = v * decks[i].pitchRange }
            "hi" -> act { decks[i].eqHigh = v }
            "mid" -> act { decks[i].eqMid = v }
            "low" -> act { decks[i].eqLow = v }
            "filter" -> act { decks[i].filter = v }
            "trim" -> act { decks[i].trimDb = v * 12.0 }
            "amt" -> act { decks[i].fx.amount = v }
            "xfader" -> act { crossfader = v }
            "master" -> act { master = v }
        }
    }

    /** Lets go of everything held (the controller was unplugged mid-press). */
    @Synchronized fun releaseAll() {
        for (c in held) map[c]?.action?.let { if (it.type == MidiType.HOLD) release(it) }
        held.clear()
    }

    companion object {
        /** Jog steps for one full turn: a middle value for common controllers. */
        const val STEPS_PER_TURN = 300.0

        // Setup screen texts, shared by the phone and the computer.
        const val MENU = "MIDI controller…"
        const val TITLE = "MIDI controller"
        const val NONE = "No controller found. Plug one in with USB; it shows up here by itself."
        fun connected(names: List<String>) = "Connected: ${names.joinToString(", ")}"
        const val HOW = "Pick something from the list, then move that knob, fader or button on your controller. " +
            "Do it once for each control you want to use."
        fun waiting(a: MidiAction) = "Now move the control for: ${a.label}"
        fun learned(b: MidiBinding) = "${b.action.label} ← ${b.control.label}"
        fun row(a: MidiAction, c: MidiControl?) = if (c == null) a.label else "${a.label}   ←   ${c.label}"
        const val CLEAR_ONE = "Remove"
        const val CLEAR_ALL = "Remove all"
        const val CLEAR_ASK = "Remove every link between your controller and MixDeck?"
        fun plugged(name: String, mapped: Boolean) =
            if (mapped) "MIDI controller connected: $name" else "MIDI controller connected: $name · set it up in ☰ → MIDI controller"
    }
}
