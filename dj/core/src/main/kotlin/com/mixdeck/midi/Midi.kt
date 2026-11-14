package com.mixdeck.midi

/**
 * One physical control on a MIDI controller: a pad or button (NOTE), a knob, fader or wheel (CC),
 * or a pitch-bend fader (BEND), on a MIDI [channel] (0..15). DJ controllers use the channel to
 * tell the left deck's controls from the right deck's.
 */
data class MidiControl(val kind: Kind, val channel: Int, val number: Int) {
    enum class Kind(val code: Char) { NOTE('n'), CC('c'), BEND('b') }

    /** Short text for saving, e.g. "c.0.23". */
    val code get() = "${kind.code}.$channel.$number"

    /** What the setup screen shows, e.g. "knob 23 (ch 1)". */
    val label get() = when (kind) {
        Kind.NOTE -> "button $number (ch ${channel + 1})"
        Kind.CC -> "knob $number (ch ${channel + 1})"
        Kind.BEND -> "pitch fader (ch ${channel + 1})"
    }

    companion object {
        fun parse(code: String): MidiControl? {
            val p = code.split('.')
            if (p.size != 3 || p[0].length != 1) return null
            val kind = Kind.values().firstOrNull { it.code == p[0][0] } ?: return null
            val ch = p[1].toIntOrNull() ?: return null
            val n = p[2].toIntOrNull() ?: return null
            if (ch !in 0..15 || n !in 0..127) return null
            return MidiControl(kind, ch, n)
        }
    }
}

/**
 * A control moved. [value] is 0..1 for knobs and faders (and how hard a pad was hit), [raw] the
 * 7-bit number the controller sent (jog wheels send steps, not positions), and [on] says whether
 * a button went down (true) or up.
 */
data class MidiEvent(val control: MidiControl, val value: Float, val raw: Int, val on: Boolean)

/**
 * Turns the bytes a controller sends into [MidiEvent]s. Handles several messages in one packet,
 * running status (the status byte left out when it repeats) and skips clock, sysex and other
 * messages MixDeck has no use for. Not thread-safe: feed it from one thread per device.
 */
class MidiParser(private val out: (MidiEvent) -> Unit) {
    private var status = 0
    private var d1 = -1
    private var inSysex = false

    fun feed(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size - offset) {
        for (i in offset until offset + length) feed(bytes[i].toInt() and 0xFF)
    }

    fun feed(b: Int) {
        if (b >= 0xF8) return                      // real-time (clock, active sensing): can come anywhere
        if (b >= 0x80) {
            inSysex = b == 0xF0
            status = if (b < 0xF0) b else 0        // system common messages cancel running status
            d1 = -1
            return
        }
        if (inSysex || status == 0) return
        val type = status and 0xF0
        val ch = status and 0x0F
        if (type == 0xC0 || type == 0xD0) return   // program change, channel pressure: one data byte, unused
        if (d1 < 0) { d1 = b; return }
        val a = d1; d1 = -1
        when (type) {
            0x90 -> out(MidiEvent(MidiControl(MidiControl.Kind.NOTE, ch, a), b / 127f, b, b > 0))
            0x80 -> out(MidiEvent(MidiControl(MidiControl.Kind.NOTE, ch, a), 0f, 0, false))
            0xB0 -> out(MidiEvent(MidiControl(MidiControl.Kind.CC, ch, a), b / 127f, b, b >= 64))
            0xE0 -> { val v = (b shl 7) or a; out(MidiEvent(MidiControl(MidiControl.Kind.BEND, ch, 0), v / 16383f, b, v >= 8192)) }
        }
    }
}
