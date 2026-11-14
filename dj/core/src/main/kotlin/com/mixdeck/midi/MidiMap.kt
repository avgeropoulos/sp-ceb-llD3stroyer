package com.mixdeck.midi

/** What kind of control suits a [MidiAction]. */
enum class MidiType { BUTTON, HOLD, KNOB, CENTER, WHEEL }

/**
 * Something on the MixDeck console a controller can drive. [id] is saved in the mapping; [key] is
 * the console control it stands for (so the tutorial can hold it back like the on-screen one);
 * [deck] is 0, 1 or -1 for the middle of the console.
 */
data class MidiAction(val id: String, val label: String, val type: MidiType, val deck: Int, val key: String?) {
    companion object {
        /** Every action, in the order the setup screen lists them. */
        val ALL: List<MidiAction> = buildList {
            for (i in 0..1) {
                val d = if (i == 0) "A" else "B"
                fun add(id: String, label: String, type: MidiType, key: String? = null) = add(MidiAction("$id$i", "Deck $d · $label", type, i, key))
                add("play", "▶ Play / pause", MidiType.BUTTON, "play$i")
                add("cue", "CUE", MidiType.HOLD, "cue$i")
                add("sync", "SYNC", MidiType.BUTTON, "sync$i")
                add("load", "LOAD (pick a song)", MidiType.BUTTON, "load$i")
                add("vol", "Volume fader", MidiType.KNOB)
                add("pitch", "Tempo fader", MidiType.CENTER)
                add("jog", "Jog wheel (turn)", MidiType.WHEEL)
                add("touch", "Jog wheel (touch, to scratch)", MidiType.HOLD)
                add("hi", "HI", MidiType.CENTER)
                add("mid", "MID", MidiType.CENTER)
                add("low", "LOW", MidiType.CENTER)
                add("filter", "FILTER", MidiType.CENTER)
                add("trim", "TRIM", MidiType.CENTER)
                add("phones", "🎧 Pre-listen", MidiType.BUTTON)
                for (n in 0..3) add("hc${n}_", "Hot cue ${n + 1}", MidiType.BUTTON, "hc${i}_$n")
                for (b in listOf(1, 2, 4, 8)) add("loop${b}_", "Loop $b beat${if (b == 1) "" else "s"}", MidiType.BUTTON, "loop${i}_$b")
                add("half", "Loop ½", MidiType.BUTTON)
                add("double", "Loop ×2", MidiType.BUTTON)
                add("fx", "FX on / off", MidiType.BUTTON, "fx$i")
                add("amt", "FX amount", MidiType.KNOB)
            }
            add(MidiAction("xfader", "Crossfader", MidiType.CENTER, -1, "xfader"))
            add(MidiAction("master", "Master volume", MidiType.KNOB, -1, null))
            add(MidiAction("mix", "MIX (blend into the next song)", MidiType.BUTTON, -1, "mix"))
            add(MidiAction("next", "NEXT (suggest a song)", MidiType.BUTTON, -1, "next"))
            add(MidiAction("rec", "REC start / stop", MidiType.BUTTON, -1, null))
            for ((n, name) in listOf("HORN", "SIREN", "LASER", "DROP").withIndex())
                add(MidiAction("pad$n", "Sampler · $name", MidiType.BUTTON, -1, "pad$n"))
        }
        private val byId = ALL.associateBy { it.id }
        fun of(id: String) = byId[id]
    }
}

/** How a jog wheel counts: most send small steps as 1,2,3… one way and 127,126… the other; some send them around 64. */
enum class WheelMode { TWOS, CENTERED }

/** One learned link: this [control] drives that [action]. */
data class MidiBinding(val control: MidiControl, val action: MidiAction, val wheel: WheelMode = WheelMode.TWOS)

/**
 * Which control drives which console action. One control drives one action and one action has one
 * control: learning a control again moves it. Saved as one line of text ([encode] / [decode]), so
 * it fits in the apps' settings. Thread-safe.
 */
class MidiMap {
    @Volatile private var byControl: Map<MidiControl, MidiBinding> = emptyMap()

    val bindings: Collection<MidiBinding> get() = byControl.values
    val isEmpty get() = byControl.isEmpty()

    operator fun get(c: MidiControl) = byControl[c]
    fun controlFor(a: MidiAction) = byControl.values.firstOrNull { it.action == a }

    @Synchronized fun bind(b: MidiBinding) {
        byControl = byControl.filterValues { it.action != b.action && it.control != b.control } + (b.control to b)
    }

    @Synchronized fun unbind(a: MidiAction) { byControl = byControl.filterValues { it.action != a } }
    @Synchronized fun clear() { byControl = emptyMap() }

    fun encode(): String = byControl.values.joinToString(";") {
        "${it.control.code}=${it.action.id}" + if (it.wheel == WheelMode.CENTERED) "~" else ""
    }

    companion object {
        /** Reads [encode]'s text; anything it doesn't understand (a newer version's action) is skipped. */
        fun decode(text: String?): MidiMap {
            val m = MidiMap()
            for (part in text.orEmpty().split(';')) {
                val eq = part.indexOf('=')
                if (eq < 0) continue
                val control = MidiControl.parse(part.substring(0, eq)) ?: continue
                val rest = part.substring(eq + 1)
                val action = MidiAction.of(rest.removeSuffix("~")) ?: continue
                m.bind(MidiBinding(control, action, if (rest.endsWith("~")) WheelMode.CENTERED else WheelMode.TWOS))
            }
            return m
        }
    }
}
