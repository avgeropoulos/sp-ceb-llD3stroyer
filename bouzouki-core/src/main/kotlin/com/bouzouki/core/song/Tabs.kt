package com.bouzouki.core.song

import com.bouzouki.core.Instrument
import java.text.Normalizer

/**
 * One note of a tab a player pasted in: its course and fret, its column (position across the tab,
 * left to right, used as a rough rhythm), and how it's played. A chord or a drone written in one column is
 * one note, its top (melody) note, with the others in [stack] (course to fret, highest first). [system] is the
 * block of tab lines it's written in, counted from 0.
 */
data class TabNote(
    val course: Int,
    val fret: Int,
    val column: Int,
    val articulation: Articulation = Articulation.PICK,
    val stack: List<Pair<Int, Int>> = emptyList(),
    val system: Int = 0,
)

/**
 * A part of a tab played as a unit: notes [from] until [to] of [ParsedTab.notes], [times] in a row (marked x2,
 * or between repeat bars |: :|), [name] from a heading such as "Chorus" (lowercase, null without one).
 */
data class TabSection(val from: Int, val to: Int, val times: Int = 1, val name: String? = null)

/**
 * A tab read from text: its notes in playing order, and anything that couldn't be read. [instrument] is the
 * bouzouki the tab is written for, told by its number of lines (or string names); [asked] the one the player
 * plays. [sections] are the tab's parts in the order it says to play them: a part repeats when it is marked
 * (x2, |: :|), and a heading that names an earlier part without tab of its own ("Chorus" again) plays that part.
 */
data class ParsedTab(
    val instrument: Instrument,
    val notes: List<TabNote>,
    val warnings: List<String> = emptyList(),
    val sections: List<TabSection> = if (notes.isEmpty()) emptyList() else listOf(TabSection(0, notes.size)),
    val asked: Instrument = instrument,
) {
    /** Whether the tab is written for the other bouzouki than the player's. */
    val forOtherBouzouki: Boolean get() = instrument != asked

    /** The MIDI pitch of note [i] on the bouzouki the tab is written for. */
    fun midi(i: Int): Int = instrument.midiAt(notes[i].course, notes[i].fret)
}

/**
 * Reads bouzouki tabs as people post them online: plain-text systems of tab lines, one line per course, the
 * highest course on top (string names such as d| A| D|, Ρε| Λα| Φα| Ντο|, "D-" or 1| 2| 3| say otherwise),
 * with dashes (or, on named lines with bars, spaces) and bar lines between 1-2 digit frets; h (hammer-on),
 * p (pull-off), / \ s (slide), ~ (tremolo); repeat marks (x2, (x3), 3 times, twice, 2 φορές, "repeat" lines,
 * |: :| bars, also across systems, a count between bars); headings (Intro, Verse 2, Chorus, "Repeat verse and
 * chorus", Κουπλέ, Ρεφρέν ...); chord names and lyrics between systems, dividers, and words after a tab line
 * (left out). A system of 3 lines is a trichordo tab, 4 lines a tetrachordo tab. Notes in one column are a
 * chord: the highest is kept as the melody (with the rest in [TabNote.stack]). Anything left out is said in
 * [ParsedTab.warnings].
 */
object TabParser {
    fun parse(text: String, instrument: Instrument): ParsedTab {
        val raw = text.lines()
        val src = raw.map { normalize(it) }
        val warn = LinkedHashSet<String>()
        val items = classify(raw, src, warn)
        val blocks = items.filterIsInstance<Block>()
        if (blocks.isEmpty()) {
            warn += "No tab lines found: a tab has one line of dashes and frets for each course, such as D|--5--7--|"
            return ParsedTab(instrument, emptyList(), warn.toList(), emptyList(), instrument)
        }
        val tabInstrument = whichBouzouki(blocks, instrument)
        if (tabInstrument != instrument) {
            warn += "This tab is written for the ${tabInstrument.englishName.substringBefore(" ·").lowercase()} " +
                "(${tabInstrument.courses.size} lines): its notes are read on it and moved to your ${
                    instrument.englishName.substringBefore(" ·").lowercase()}"
        }
        val reader = Reader(tabInstrument, warn)
        for (item in items) reader.add(item)
        return reader.finish(instrument)
    }

    // ---------------------------------------------------------------- lines

    /**
     * A line of tab: [label] as written (null if none), the [body] from raw index [start], a repeat mark's [times] at its
     * end. A [weak] line has no name, bar or fret (an empty course, or a divider); [rule] if it's drawn with = or _.
     * [note]: words written after the tab, left out.
     */
    private class TabLine(
        val number: Int, val label: String?, val start: Int, val body: String, val times: Int, val weak: Boolean,
        val bars: Int, val rule: Boolean = false, val note: String? = null,
    )

    private sealed class Item
    private class Text(val number: Int, val text: String) : Item()
    private class Block(val lines: List<TabLine>) : Item()

    private const val MARKS = "-|:hpbrstvxHPBRSTVX/\\~()[]<>^*.,'+ "

    private fun normalize(line: String): String = buildString(line.length) {
        for (c in line) {
            if (c == '\t') { do append(' ') while (length % 8 != 0); continue } // tab stops keep the columns lined up
            append(
                when (c) {
                    '–', '—', '―', '−', '─', '━', '‐', '‑', '‒', '=', '_' -> '-'
                    '｜', '¦', '│', '┃', '‖' -> '|'
                    '\u00A0', '\u2009', '\u202F' -> ' '
                    '×' -> 'x'
                    '∼', '〜', '～' -> '~'
                    else -> c
                },
            )
        }
    }.trimEnd()

    private val LABEL = Regex("""^\s*([A-Za-zΑ-Ωα-ωΆ-Ώά-ώ]{1,3}[#♯♭]?\d?'?|[1-6](?=\s*[|)]))\s*(?=[|:)\]-])""")
    // Repeat marks, on lowercase text without accents: x2, 2x, *2, 3 times, 2 φορές, twice, δις; "repeat" with or without one.
    private const val COUNT = """(?:x\s*(\d{1,2})|\*\s*(\d{1,2})|(\d{1,2})\s*(?:x|times|φορες)(?![\p{L}])|twice|δις)"""
    private const val REPEAT = """(?:repeat|rep\.?|επαναληψη)(?![\p{L}])[\s:]*(?:$COUNT|(\d{1,2}))?"""
    private val REPEAT_PAREN = Regex("""[(\[]\s*(?:$REPEAT|$COUNT)\s*[)\]]\s*$""")
    private val REPEAT_BARE = Regex("""(?:(?<=[\s|:])|^)(?:$REPEAT|$COUNT)\s*$""")
    private val REPEAT_GLUED = Regex("""(?<=-)x\s?(\d{1,2})\s*$""") // --5--x2
    private val REPEAT_LINE = Regex("""^[\s(\[*]*(?:$REPEAT|$COUNT)[\s)\].!:]*$""")
    /** A repeat count between bars inside a line: | x2 | (the stretch before it plays twice). */
    private val REPEAT_MID = Regex("""(?<=[\s|])[xX]\s?(\d{1,2})(?=[\s|])""")
    private val HEADER = Regex(
        """^[\s\[(*#<-]*(?:(?:repeat|play|again|επαναληψη|ξανα)\s+(?:the\s+|το\s+|τη\s+|την\s+)?)?""" +
            """(intro|verse|chorus|refrain|bridge|outro|solo|interlude|ending|coda|instrumental|""" +
            """εισαγωγη|κουπλε|στροφη|ρεφρεν|γεφυρα|σολο|τελος|ταξιμι|ενδιαμεσο|οργανικο|γυρισμα)(?![\p{L}])\s*(\d*)(.*)$""",
        RegexOption.IGNORE_CASE,
    )
    private val TIMES_IN = Regex("""(?:x\s*(\d{1,2})|(\d{1,2})\s*(?:x|times|φορες))(?![\p{L}\d])|twice|δις|repeat|επαναληψη""")
    /** A part a heading names: its word and number ("verse 2"), not a repeat count ("chorus 3 times"). */
    private val PART = Regex(
        """(intro|verse|chorus|refrain|bridge|outro|solo|interlude|ending|coda|instrumental|εισαγωγη|κουπλε|στροφη|ρεφρεν|""" +
            """γεφυρα|σολο|τελος|ταξιμι|ενδιαμεσο|οργανικο|γυρισμα)(?![\p{L}])(?:\s*(\d{1,2})(?!\s*(?:x|times|φορες)(?![\p{L}])|\d))?""",
    )
    /** Words a heading may hold besides part names and counts ("Chorus (same as above)", "Repeat verse and chorus"). */
    private val FILLER = setOf(
        "same", "above", "before", "again", "like", "the", "then", "and", "all", "once", "more", "play", "from", "top",
        "repeat", "rep", "και", "ιδιο", "ιδια", "οπως", "πριν", "ξανα", "παλι", "απο", "πανω", "την", "του", "της",
    )

    /** [s] without accents, lowercase (Greek headings and note names are written with or without them). */
    private fun plain(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

    /** [plain] keeping each character in its place (so a match's indices are [s]'s). */
    private fun folded(s: String) = buildString(s.length) {
        for (c in s) {
            val d = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
            append((d.firstOrNull { Character.getType(it) != Character.NON_SPACING_MARK.toInt() } ?: c).lowercaseChar())
        }
    }

    /** The repeat count a match of the REPEAT regexes carries (2 when it names none). */
    private fun count(m: MatchResult) = m.groupValues.drop(1).firstOrNull { it.isNotEmpty() }?.toInt()?.coerceIn(1, 16) ?: 2

    /** The line as tab ([raw]: as written, before [normalize]), or null if it isn't one. */
    private fun tabLine(s: String, raw: String, number: Int): TabLine? {
        if (s.isBlank()) return null
        var end = s.length
        var times = 1
        val f = folded(s)
        val tail = REPEAT_PAREN.find(f) ?: REPEAT_BARE.find(f) ?: REPEAT_GLUED.find(f)
        if (tail != null && tail.range.first > 0) { times = count(tail); end = tail.range.first }
        // Words after the tab ("let ring", a chord name, the string's name again after its last bar): left out.
        var note: String? = null
        val last = (end - 1 downTo 0).firstOrNull { s[it] == '|' || s[it] == '-' || s[it] == '~' || s[it].isDigit() }
        if (last != null && last + 1 < end) {
            val after = s.substring(last + 1, end)
            val words = after.trim()
            val name = s[last] == '|' && words.length <= 3 && labelPitch(words) != null
            val kept = s.substring(0, last + 1)
            if (words.any { it.isLetter() } && (after.first() == ' ' || name) && HEADER.find(plain(words)) == null &&
                (kept.any { it.isDigit() } || kept.contains('|'))
            ) {
                end = last + 1
                if (!name) note = words
            }
        }
        val head = LABEL.find(s)
        val label: String?
        var start: Int
        if (head != null && head.range.last < end) {
            label = head.groupValues[1]
            start = head.range.last + 1
        } else {
            label = null
            start = s.indexOfFirst { it != ' ' }
        }
        while (end > start && s[end - 1] == ' ') end--
        if (start >= end) return null
        val body = s.substring(start, end)
        var dashes = 0
        var digits = 0
        for (c in body) when {
            c == '-' -> dashes++
            c.isDigit() -> digits++
            c in MARKS -> {}
            else -> return null // a letter or sign tabs don't use: lyrics, chord names, a heading
        }
        val bars = body.count { it == '|' }
        // Spaces may stand for the dashes on a named line with bars: D|  5  7  8  |
        if (dashes < 2 && !(label != null && bars > 0 && (digits > 0 || body.all { it == ' ' || it == '|' }))) return null
        if (label == null && bars == 0 && dashes < 4) return null
        val weak = digits == 0 && label == null && bars == 0
        val rule = weak && raw.any { it == '=' || it == '_' } && raw.none { it == '-' }
        return TabLine(number, label, start, body, times, weak, bars, rule, note)
    }

    /** Lines that look like tab (dashes and frets) but couldn't be read, to warn about. */
    private fun looksLikeTab(s: String) = s.count { it == '-' } >= 6 && s.any { it.isDigit() } && HEADER.find(plain(s)) == null

    private fun classify(raw: List<String>, src: List<String>, warn: MutableSet<String>): List<Item> {
        val items = ArrayList<Item>()
        val block = ArrayList<TabLine>()
        fun flush() {
            if (block.isEmpty()) return
            // A row of dashes alone above or below the tab is a divider rather than an empty course: drawn with = or _, of
            // another length, or plain where the tab's lines have names or bars.
            val real = block.filter { !it.weak }
            if (real.isNotEmpty()) {
                val len = real.map { it.body.length }.sorted()[real.size / 2]
                val marked = real.any { it.label != null || it.bars > 0 }
                fun divider(l: TabLine) = l.weak && (l.rule || marked || kotlin.math.abs(l.body.length - len) > len / 5)
                while (block.isNotEmpty() && divider(block.first())) block.removeAt(0)
                while (block.isNotEmpty() && divider(block.last())) block.removeAt(block.size - 1)
                items += Block(block.toList())
            }
            block.clear()
        }
        for ((n, s) in src.withIndex()) {
            val t = tabLine(s, raw[n], n + 1)
            if (t != null) {
                block += t
                if (t.note != null) warn += "Left out \"${t.note.take(30)}\" written after the tab on line ${n + 1}"
                continue
            }
            flush()
            items += Text(n + 1, s)
            if (looksLikeTab(s)) warn += "Line ${n + 1} looks like tab but couldn't be read: \"${s.trim().take(40)}\""
        }
        flush()
        return items
    }

    // ---------------------------------------------------------------- systems and courses

    /** The pitch class a string name stands for (D, d, Re, Ρε, Λα ...), or null. */
    private fun labelPitch(label: String): Int? {
        val l = plain(label).trimEnd('\'').trimEnd { it.isDigit() }
        if (l.isEmpty()) return null
        val base = when (l.trimEnd('#', '♯', '♭')) {
            "c", "do", "ντο" -> 0; "d", "re", "ρε" -> 2; "e", "mi", "μι" -> 4; "f", "fa", "φα" -> 5
            "g", "sol", "σολ" -> 7; "a", "la", "λα" -> 9; "b", "h", "si", "σι" -> 11
            else -> return null
        }
        val acc = when { l.endsWith('#') || l.endsWith('♯') -> 1; l.endsWith('♭') -> -1; else -> 0 }
        return Math.floorMod(base + acc, 12)
    }

    private fun stringNumber(label: String?) = label?.toIntOrNull()?.takeIf { it in 1..6 }

    /** The pitch classes of [lines]' labels when every line has one; null otherwise. */
    private fun pitches(lines: List<TabLine>): List<Int>? {
        val p = lines.map { l -> l.label?.let { labelPitch(it) } }
        return if (p.all { it != null }) p.filterNotNull() else null
    }

    /** Whether the labels name a tetrachordo's F or C course. */
    private fun namesFourCourse(lines: List<TabLine>) = lines.any { l -> l.label?.let { labelPitch(it) } in setOf(0, 5) }

    /**
     * Cuts a block of tab lines written without a blank line between systems into systems of [size] lines (or
     * the other bouzouki's, when the string names repeat that way). Six unnamed lines are one system: a guitar tab.
     */
    private fun systems(block: Block, size: Int): Pair<List<List<TabLine>>, List<TabLine>> {
        val l = block.lines
        if (l.size <= 4) return listOf(l) to emptyList()
        val names = l.map { it.label?.let { x -> plain(x) } }
        val repeating = listOf(size, 7 - size).firstOrNull { s ->
            l.size % s == 0 && names.all { it != null } && names.chunked(s).all { it == names.take(s) }
        }
        val guitarNames = l.any { t -> t.label?.let { labelPitch(it) } in setOf(4, 7, 11) }
        val s = repeating ?: when {
            l.size == 6 && ((names.all { it == null } && size == 4) || guitarNames) -> return listOf(l) to emptyList()
            l.size % size == 0 -> size
            l.size % (7 - size) == 0 && l.size != 6 -> 7 - size
            else -> size
        }
        val whole = l.size / s * s
        return l.take(whole).chunked(s) to l.drop(whole)
    }

    /** The bouzouki a tab is written for: by its systems' number of lines, and string names (F or C: tetrachordo). */
    private fun whichBouzouki(blocks: List<Block>, asked: Instrument): Instrument {
        var three = 0
        var four = 0
        for (b in blocks) {
            val n = b.lines.size
            // String names repeating every 3 (or 4) lines: systems written without a blank line between them.
            val names = b.lines.map { it.label?.let { x -> plain(x) } }
            fun repeats(k: Int) = n > 4 && n % k == 0 && names.all { it != null } && names.chunked(k).all { it == names.take(k) }
            when {
                namesFourCourse(b.lines) -> four++
                repeats(3) && !repeats(4) -> three++
                repeats(4) -> four++
                n == 4 || (n > 4 && n % 4 == 0 && n % 3 != 0) -> four++
                n == 3 || (n > 4 && n % 3 == 0 && n % 4 != 0 && n != 6) -> three++
                n == 6 && pitches(b.lines)?.let { it.take(3) == it.drop(3) } == true -> three++
            }
        }
        return when {
            four > three -> Instrument.TETRACHORDO
            three > four -> Instrument.TRICHORDO
            else -> asked
        }
    }

    /** Builds the notes and sections from the classified lines, item by item. */
    private class Reader(val inst: Instrument, val warn: MutableSet<String>) {
        val notes = ArrayList<TabNote>()
        val sections = ArrayList<TabSection>()
        var offset = 0
        var system = 0

        // The heading being read: its name, repeat count, where its sections start, and the parts read before.
        var name: String? = null
        var nameTimes = 1
        var nameFrom = 0
        val named = ArrayList<Triple<String, Int, Int>>() // name ("verse 2"), from, to
        var lastSystem: IntRange? = null // sections index range of the last system since the heading
        var pendingOpen: Int? = null // a |: not closed yet: the note it's at, and the first of the sections since
        var pendingSection = 0
        var muted = 0
        var bends = 0
        val tooHigh = ArrayList<Int>()

        fun add(item: Item) {
            when (item) {
                is Text -> text(item)
                is Block -> block(item)
            }
        }

        private fun text(t: Text) {
            val p = plain(t.text).trim()
            if (p.isEmpty()) return
            if (HEADER.find(p) != null && p.length <= 40) {
                // The parts it names ("Verse 2", "Verse and chorus"), a repeat count, and words such as "same as above".
                val parts = PART.findAll(p).map { m -> canonical(m.groupValues[1]) + (if (m.groupValues[2].isNotEmpty()) " " + m.groupValues[2] else "") }.toList()
                val rest = PART.replace(p, " ")
                val other = TIMES_IN.replace(rest, " ").split(Regex("[^\\p{L}]+")).count { it.length > 2 && it !in FILLER && !it.startsWith("rep") }
                val after = PART.find(p)?.let { PART.replace(p.substring(it.range.last + 1), " ") } ?: "" // "Repeat chorus" plays it once
                if (other <= 1 && parts.isNotEmpty()) {
                    // Each part but the last is played as written before; the last one's tab may follow.
                    for (part in parts.dropLast(1)) { closeHeading(); name = part; closeHeading() }
                    closeHeading()
                    name = parts.last()
                    nameTimes = TIMES_IN.find(after)?.let { count(it) } ?: 1
                    nameFrom = sections.size
                    lastSystem = null
                    return
                }
                warn += "Read line ${t.number} as text, not as a part's heading: \"${t.text.trim().take(40)}\""
                return
            }
            val r = REPEAT_LINE.find(p) ?: return
            val n = count(r)
            val last = lastSystem
            if (last != null) repeat(last, n)
            else if (name != null) nameTimes = n
            else warn += "The repeat mark on line ${t.number} has no tab before it"
        }

        /** Marks the sections [range] (one system's) to play [n] times. */
        private fun repeat(range: IntRange, n: Int) {
            if (range.first == range.last) {
                sections[range.first] = sections[range.first].copy(times = n)
            } else {
                val from = sections[range.first].from
                val to = sections[range.last].to
                val nm = sections[range.first].name
                for (i in range.last downTo range.first) sections.removeAt(i)
                sections.add(range.first, TabSection(from, to, n, nm))
                lastSystem = range.first..range.first
            }
        }

        private fun canonical(word: String) = when (word) {
            "refrain", "ρεφρεν" -> "chorus"
            "κουπλε", "στροφη" -> "verse"
            "εισαγωγη" -> "intro"
            "γεφυρα" -> "bridge"
            "σολο" -> "solo"
            "τελος", "ending", "coda" -> "outro"
            "ενδιαμεσο", "interlude", "οργανικο", "instrumental", "γυρισμα" -> "interlude"
            "ταξιμι" -> "taximi"
            else -> word
        }

        private fun closeHeading() {
            val nm = name
            val own = sections.size - nameFrom
            if (own > 0) {
                if (nameTimes > 1) {
                    if (own == 1) sections[nameFrom] = sections[nameFrom].copy(times = maxOf(nameTimes, sections[nameFrom].times))
                    else {
                        if (sections.subList(nameFrom, sections.size).any { it.times > 1 }) warn += "Repeats inside a repeated part are read as one repeat"
                        val s = TabSection(sections[nameFrom].from, sections.last().to, nameTimes, nm)
                        while (sections.size > nameFrom) sections.removeAt(sections.size - 1)
                        sections += s
                    }
                }
                if (nm != null) named += Triple(nm, sections[nameFrom].from, sections.last().to)
            } else if (nm != null) {
                // A heading without tab plays the part of that name written before.
                val word = nm.substringBefore(' ')
                val before = named.lastOrNull { it.first == nm } ?: named.lastOrNull { it.first.substringBefore(' ') == word }
                if (before != null) sections += TabSection(before.second, before.third, nameTimes, nm)
                else warn += "\"$nm\" has no tab, and no earlier $word to repeat"
            }
            name = null
            nameTimes = 1
            nameFrom = sections.size
        }

        private fun block(b: Block) {
            val (systems, left) = systems(b, inst.courses.size)
            if (left.isNotEmpty()) warn += "Left out ${left.size} tab line(s) from line ${left.first().number} that don't make a whole system"
            for (sys in systems) readSystem(sys)
        }

        private fun readSystem(lines: List<TabLine>) {
            val first = lines.first().number
            if (lines.size > inst.courses.size) {
                val p = pitches(lines)
                val guitar = lines.size == 6 || (p != null && p.any { it == 4 || it == 7 || it == 11 })
                warn += if (guitar) "Left out the ${lines.size}-line system at line $first: it looks like a guitar tab"
                else "Left out the ${lines.size}-line system at line $first: a ${inst.englishName.substringBefore(" ·").lowercase()} has ${inst.courses.size} courses"
                return
            }
            val courses = courses(lines)
            // Columns: by raw position when the lines start together or carry no names, else from each line's body.
            val starts = lines.map { it.start }
            val byRaw = starts.distinct().size == 1 || lines.none { it.label != null }
            val minStart = starts.min()
            val shift = IntArray(lines.size) { if (byRaw) lines[it].start - minStart else 0 }
            val raw = ArrayList<RawNote>()
            for ((li, l) in lines.withIndex()) scan(l, li, shift[li], raw)
            val width = lines.indices.maxOf { shift[it] + lines[it].body.length }
            // Repeat bars: |: starts a repeated stretch, :| (or a count between bars: | x3 |) ends it. A :| repeats from
            // the last |:, even one in an earlier system, else from the system's start or the last :|.
            val opens = HashSet<Int>()
            val closes = HashMap<Int, Int>() // column, and the count written there (0: none)
            for ((li, l) in lines.withIndex()) {
                for (i in 0 until l.body.length - 1) {
                    if (l.body[i] == '|' && l.body[i + 1] == ':') opens += i + 1 + shift[li]
                    if (l.body[i] == ':' && l.body[i + 1] == '|') closes.putIfAbsent(i + shift[li], 0)
                }
                for (m in REPEAT_MID.findAll(l.body)) closes[m.range.first + shift[li]] = m.groupValues[1].toInt().coerceIn(1, 16)
            }
            val sysFrom = notes.size
            val events = events(raw, courses)
            for (e in events) notes += e.copy(column = e.column + offset, system = system)
            val times = lines.maxOf { it.times }
            var firstSection = sections.size
            val closed = dedupe(closes)
            val marks = (dedupe(opens.associateWith { -1 }) + closed).sortedBy { it.first }
            fun noteAt(col: Int) = (sysFrom until notes.size).firstOrNull { notes[it].column - offset >= col } ?: notes.size
            var at = sysFrom // the notes before it are in sections
            var from = pendingOpen ?: sysFrom // where a :| repeats from
            for ((col, count) in marks) {
                val k = noteAt(col)
                if (count < 0) {
                    if (k > at) sections += TabSection(at, k, 1, name)
                    at = maxOf(at, k)
                    from = at
                    pendingOpen = at
                    pendingSection = sections.size
                    continue
                }
                val opened = pendingOpen
                if (opened != null && opened < sysFrom) {
                    // Opened in an earlier system: the parts read since then are the repeated part.
                    while (sections.size > pendingSection) sections.removeAt(sections.size - 1)
                    firstSection = minOf(firstSection, sections.size)
                    nameFrom = minOf(nameFrom, sections.size)
                }
                val n = if (count > 0) count else if (closed.size == 1 && times > 1) times else 2
                if (k > from) sections += TabSection(from, k, n, name)
                at = maxOf(at, k)
                from = at
                pendingOpen = null
            }
            if (notes.size > at) sections += TabSection(at, notes.size, if (marks.isEmpty()) times else 1, name)
            lastSystem = if (sections.size > firstSection) firstSection until sections.size else null
            offset += width + 1
            system++
        }

        /** Marks at [cols] (column to count), those within two columns of each other as one (the larger count). */
        private fun dedupe(cols: Map<Int, Int>): List<Pair<Int, Int>> {
            val out = ArrayList<Pair<Int, Int>>()
            for (c in cols.keys.sorted()) {
                val last = out.lastOrNull()
                if (last != null && c - last.first <= 2) out[out.size - 1] = last.first to maxOf(last.second, cols.getValue(c))
                else out += c to cols.getValue(c)
            }
            return out
        }

        /** The course each line is for: by string names when they fit the bouzouki, else the top line is the highest course. */
        private fun courses(lines: List<TabLine>): IntArray {
            val n = inst.courses.size
            val k = lines.size
            val positional = IntArray(k) { n - 1 - it }
            if (k < n && lines.none { it.label != null }) warn += "Read the ${k}-line system at line ${lines.first().number} as the top $k courses"
            val numbers = lines.map { stringNumber(it.label) }
            if (numbers.all { it != null }) {
                val out = IntArray(k) { n - numbers[it]!! }
                if (out.all { it in 0 until n } && out.distinct().size == k) return out
            }
            val p = pitches(lines) ?: return positional
            val out = IntArray(k) { -1 }
            val multi = ArrayList<Int>()
            for (i in 0 until k) {
                val c = inst.courses.indices.filter { inst.courses[it].openMidi % 12 == p[i] }
                when (c.size) {
                    0 -> {
                        warn += "The string names at line ${lines.first().number} (${lines.joinToString(" ") { it.label ?: "" }}) aren't a ${
                            inst.englishName.substringBefore(" ·").lowercase()}'s: read top to bottom as its courses from the highest"
                        return positional
                    }
                    1 -> out[i] = c[0]
                    else -> multi += i
                }
            }
            if (multi.isNotEmpty()) {
                // Two courses share the name (D on the trichordo): lowercase names the higher, else the upper line is.
                val free = inst.courses.indices.filter { c -> inst.courses[c].openMidi % 12 == p[multi[0]] && c !in out }.sortedDescending()
                val order = multi.sortedWith(compareBy<Int>({ if (lines[it].label!!.first().isLowerCase()) 0 else 1 }, { it }))
                for ((j, i) in order.withIndex()) out[i] = free.getOrElse(j) { -1 }
            }
            return if (out.all { it >= 0 } && out.distinct().size == k) out else positional
        }

        private class RawNote(val line: Int, val col: Int, val end: Int, val fret: Int, val before: String, var after: String = "")

        /** The frets on one line, with the marks before and after each. */
        private fun scan(l: TabLine, li: Int, shift: Int, out: MutableList<RawNote>) {
            val b = l.body
            val mine = ArrayList<RawNote>()
            val counts = REPEAT_MID.findAll(b).associate { it.range.first to it.range.last } // | x2 |: not notes
            var prevEnd = 0
            var i = 0
            while (i < b.length) {
                val c = b[i]
                val countEnd = counts[i]
                if (countEnd != null) {
                    i = countEnd + 1
                    prevEnd = i
                } else if (c.isDigit()) {
                    var j = i
                    while (j < b.length && b[j].isDigit()) j++
                    for ((off, len) in splitFrets(b.substring(i, j), l.number)) {
                        val at = i + off
                        mine += RawNote(li, at + shift, at + len - 1 + shift, b.substring(at, at + len).toInt(), b.substring(prevEnd, at))
                        prevEnd = at + len
                    }
                    i = j
                } else {
                    if ((c == 'x' || c == 'X') && (i == 0 || !b[i - 1].isDigit())) muted++
                    i++
                }
            }
            for ((k, n) in mine.withIndex()) {
                val next = if (k + 1 < mine.size) mine[k + 1].col - shift else b.length
                n.after = b.substring(n.end - shift + 1, next)
            }
            out += mine
        }

        /** A run of digits as frets: one fret up to 27, else split (warned). */
        private fun splitFrets(run: String, line: Int): List<Pair<Int, Int>> {
            if (run.length == 1 || (run.length == 2 && run.toInt() <= MAX_FRET)) return listOf(0 to run.length)
            val out = ArrayList<Pair<Int, Int>>()
            var i = 0
            while (i < run.length) {
                val len = if (i + 2 <= run.length && run[i] != '0' && run.substring(i, i + 2).toInt() <= MAX_FRET) 2 else 1
                out += i to len
                i += len
            }
            warn += "Read \"$run\" on line $line as frets ${out.joinToString(" and ") { run.substring(it.first, it.first + it.second) }}"
            return out
        }

        /** The notes of a system in column order, a chord (notes sharing a column) as its top note. */
        private fun events(raw: List<RawNote>, courses: IntArray): List<TabNote> {
            val kept = raw.filter { n ->
                val bend = n.before.any { it == 'b' || it == 'B' || it == 'r' || it == 'R' } && n.before.none { it == '-' || it == '|' }
                if (bend) bends++
                val ok = n.fret <= inst.frets
                if (!ok && !bend) tooHigh += n.fret
                !bend && ok
            }.sortedBy { it.col }
            val out = ArrayList<TabNote>()
            var i = 0
            while (i < kept.size) {
                var j = i + 1
                var end = kept[i].end
                while (j < kept.size && kept[j].col <= end) { end = maxOf(end, kept[j].end); j++ }
                // Notes of one column; a line's later note can't share it (a column holds one note per course).
                val group = kept.subList(i, j).distinctBy { it.line }
                val byPitch = group.sortedWith(compareByDescending<RawNote> { inst.midiAt(courses[it.line], it.fret) }.thenByDescending { courses[it.line] })
                val top = byPitch[0]
                out += TabNote(
                    course = courses[top.line],
                    fret = top.fret,
                    column = group.minOf { it.col },
                    articulation = articulation(top),
                    stack = byPitch.drop(1).map { courses[it.line] to it.fret },
                )
                i = j
            }
            return out
        }

        /** How the note is played, as its marks say (legato is checked against the note before in [finish]). */
        private fun articulation(n: RawNote): Articulation {
            val mark = n.before.lastOrNull { it in "hHpP/\\sS" }
            return when (mark) {
                'h', 'H' -> Articulation.HAMMER
                'p', 'P' -> Articulation.PULL
                '/', '\\', 's', 'S' -> Articulation.SLIDE
                else -> if (n.after.take(2).contains('~')) Articulation.TREMOLO else Articulation.PICK
            }
        }

        fun finish(asked: Instrument): ParsedTab {
            closeHeading()
            // Legato needs the note before on the same course: h and p follow the pitch's direction, the rest is picked.
            for (k in notes.indices) {
                val n = notes[k]
                if (!n.articulation.isLegato) continue
                val prev = notes.getOrNull(k - 1)
                val d = if (prev != null) inst.midiAt(n.course, n.fret) - inst.midiAt(prev.course, prev.fret) else 0
                notes[k] = when {
                    prev == null || prev.course != n.course || d == 0 -> n.copy(articulation = Articulation.PICK)
                    n.articulation == Articulation.SLIDE -> n
                    else -> n.copy(articulation = if (d > 0) Articulation.HAMMER else Articulation.PULL)
                }
            }
            if (muted > 0) warn += "Left out $muted muted note(s) (x)"
            if (bends > 0) warn += "Read $bends bend(s) as the note bent from"
            if (tooHigh.isNotEmpty()) warn += "Left out ${tooHigh.size} note(s) past the ${inst.frets}th fret (${tooHigh.distinct().joinToString()})"
            if (notes.isEmpty()) warn += "The tab lines hold no frets"
            // Neighbouring parts that play once under one name are one part.
            val merged = ArrayList<TabSection>()
            for (s in sections) {
                val last = merged.lastOrNull()
                if (last != null && last.times == 1 && s.times == 1 && last.to == s.from && last.name == s.name) merged[merged.size - 1] = last.copy(to = s.to)
                else merged += s
            }
            return ParsedTab(inst, notes.toList(), warn.toList(), merged, asked)
        }
    }

    /** The highest fret a tab may hold (the tetrachordo's 27th). */
    private const val MAX_FRET = 27
}
