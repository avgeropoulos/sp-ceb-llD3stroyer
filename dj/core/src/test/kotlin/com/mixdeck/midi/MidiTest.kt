package com.mixdeck.midi

import com.mixdeck.engine.Mixer
import com.mixdeck.ui.Console
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MidiTest {
    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    @Test fun parserReadsNotesKnobsAndBend() {
        val got = ArrayList<MidiEvent>()
        val p = MidiParser { got += it }
        p.feed(bytes(0x90, 12, 100, 0x80, 12, 0, 0xB1, 23, 64, 0xE0, 0, 64))
        assertEquals(4, got.size)
        assertEquals(MidiControl(MidiControl.Kind.NOTE, 0, 12), got[0].control); assertTrue(got[0].on)
        assertFalse(got[1].on)
        assertEquals(MidiControl(MidiControl.Kind.CC, 1, 23), got[2].control); assertEquals(64, got[2].raw)
        assertEquals(MidiControl.Kind.BEND, got[3].control.kind); assertEquals(8192f / 16383f, got[3].value, 1e-6f)
    }

    @Test fun parserHandlesRunningStatusClockAndSysex() {
        val got = ArrayList<MidiEvent>()
        val p = MidiParser { got += it }
        // Clock in the middle of a message, running status, note-on with velocity 0 = up, sysex skipped.
        p.feed(bytes(0xB0, 7, 0xF8, 10, 7, 20, 0xF0, 1, 2, 3, 0xF7, 0x90, 5, 0, 0xC0, 9, 5, 5))
        assertEquals(listOf(10, 20, 0), got.map { it.raw })
        assertFalse(got[2].on)
    }

    @Test fun mapSavesAndLoads() {
        val m = MidiMap()
        m.bind(MidiBinding(MidiControl(MidiControl.Kind.CC, 0, 23), MidiAction.of("vol0")!!))
        m.bind(MidiBinding(MidiControl(MidiControl.Kind.CC, 1, 33), MidiAction.of("jog1")!!, WheelMode.CENTERED))
        m.bind(MidiBinding(MidiControl(MidiControl.Kind.NOTE, 0, 1), MidiAction.of("play0")!!))
        val back = MidiMap.decode(m.encode() + ";x.1.2=play0;c.0.1=nothing;junk")
        assertEquals(m.bindings.toSet(), back.bindings.toSet())
        // One control per action and one action per control.
        back.bind(MidiBinding(MidiControl(MidiControl.Kind.NOTE, 0, 2), MidiAction.of("play0")!!))
        assertEquals(3, back.bindings.size)
        assertNull(back[MidiControl(MidiControl.Kind.NOTE, 0, 1)])
        assertEquals(MidiAction.ALL.size, MidiAction.ALL.map { it.id }.toSet().size)
    }

    private class NoHost : Console.Host {
        override fun openLibrary(deck: Int) {}
        override fun deckMenu(deck: Int) {}
        override fun mainMenu() {}
        override fun toggleRecord() {}
        override fun recording() = false
        override fun recordSeconds() = 0.0
        override fun loadingText(deck: Int): String? = null
        override fun savingText(): String? = null
        override fun suggestNext(next: Console.NextPlan) {}
    }

    private fun rig(): Triple<Mixer, MidiRouter, ArrayList<String>> {
        val mixer = Mixer(44100)
        val log = ArrayList<String>()
        val r = MidiRouter(mixer, Console(mixer, NoHost()), { log += "load$it" }, { log += "next" }, { log += "rec" })
        return Triple(mixer, r, log)
    }

    private fun cc(n: Int, v: Int, ch: Int = 0) = MidiEvent(MidiControl(MidiControl.Kind.CC, ch, n), v / 127f, v, v >= 64)
    private fun note(n: Int, v: Int) = MidiEvent(MidiControl(MidiControl.Kind.NOTE, 0, n), v / 127f, v, v > 0)

    @Test fun learnThenDrive() {
        val (mixer, r, log) = rig()
        var learned: MidiBinding? = null
        r.onLearned = { learned = it }

        // A fader: a pad doesn't suit it, a knob does.
        r.learning = MidiAction.of("xfader")
        r.handle(note(40, 127)); assertNull(learned)
        r.handle(cc(8, 30)); assertEquals("xfader", learned!!.action.id); assertNull(r.learning)
        r.handle(cc(8, 0)); assertEquals(-1f, mixer.crossfader)
        r.handle(cc(8, 64)); assertEquals(0f, mixer.crossfader)
        r.handle(cc(8, 127)); assertEquals(1f, mixer.crossfader)

        // A button: a moving fader doesn't get linked by accident; the press that links it does nothing.
        r.learning = MidiAction.of("load1")
        r.handle(cc(8, 90)); assertEquals("load1", r.learning!!.id)
        r.handle(note(41, 127)); r.handle(note(41, 0)); assertTrue(log.isEmpty())
        r.handle(note(41, 127)); r.handle(note(41, 0)); assertEquals(listOf("load1"), log)

        // ▶ on an empty deck picks a song; volume and EQ follow their knobs.
        r.learning = MidiAction.of("play0"); r.handle(note(1, 100)); r.handle(note(1, 0))
        r.handle(note(1, 100)); assertEquals(listOf("load1", "load0"), log)
        r.learning = MidiAction.of("vol1"); r.handle(cc(9, 5)); r.handle(cc(9, 127)); r.handle(cc(9, 0))
        assertEquals(0f, mixer.decks[1].volume)
        r.learning = MidiAction.of("low0"); r.handle(cc(10, 5)); r.handle(cc(10, 0)); assertEquals(-1f, mixer.decks[0].eqLow)
        r.learning = MidiAction.of("pitch0"); r.handle(MidiEvent(MidiControl(MidiControl.Kind.BEND, 0, 0), 1f, 127, true))
        r.handle(MidiEvent(MidiControl(MidiControl.Kind.BEND, 0, 0), 1f, 127, true))
        assertEquals(mixer.decks[0].pitchRange, mixer.decks[0].pitch, 1e-9)
    }

    @Test fun wheelsCountBothWays() {
        val (_, r, _) = rig()
        var b: MidiBinding? = null
        r.onLearned = { b = it }
        r.learning = MidiAction.of("jog0"); r.handle(cc(33, 1)); assertEquals(WheelMode.TWOS, b!!.wheel)
        r.learning = MidiAction.of("jog1"); r.handle(cc(34, 65)); assertEquals(WheelMode.CENTERED, b!!.wheel)
    }

    @Test fun aKnobUsedAsAButtonFiresOnce() {
        val (_, r, log) = rig()
        r.learning = MidiAction.of("next"); r.handle(cc(50, 127)); r.handle(cc(50, 0))
        r.handle(cc(50, 127)); r.handle(cc(50, 127)); r.handle(cc(50, 0)); r.handle(cc(50, 127))
        assertEquals(listOf("next", "next"), log)
    }
}
