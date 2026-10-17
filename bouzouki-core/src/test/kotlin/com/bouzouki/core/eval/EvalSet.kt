package com.bouzouki.core.eval

import com.bouzouki.core.Instrument
import com.bouzouki.core.song.Articulation
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale

/**
 * A note of a recording's reference (what was really played): when, which pitch, and where on the neck if
 * known ([course] and [fret], -1 if not), and how it was sounded.
 */
data class RefNote(
    val start: Double,
    val end: Double,
    val midi: Int,
    val course: Int = -1,
    val fret: Int = -1,
    val articulation: Articulation = Articulation.PICK,
)

/**
 * One recording of an evaluation set: its audio file, what was played in it, and for which bouzouki. [kind] is
 * "solo" (the bouzouki alone: transcribed without separation) or "mix" (with a band: separated first); [group]
 * names what it tests (open courses, every fret, chromatic runs, random patterns, tremolo, songs…).
 */
data class EvalItem(
    val name: String,
    val audio: File,
    val ref: List<RefNote>,
    val instrument: Instrument,
    val kind: String,
    val group: String,
    /** How far the recording is really tuned from A = 440 Hz (cents), when known (header `# cents=`). */
    val cents: Double? = null,
)

/**
 * An evaluation set: a folder of recordings, each `<name>.wav` with its reference beside it, either
 *
 * - `<name>.ref.csv`: the notes in seconds, one per line `start,end,midi[,course,fret[,articulation]]`
 *   (articulation PICK, HAMMER, PULL, SLIDE or TREMOLO), or
 * - `<name>.plan.csv`: the notes as planned to a click, in beats: `beat,beats,course,fret[,articulation]`, with
 *   header lines `# bpm=<tempo>` and optionally `# offset=<seconds of beat 0 in the recording>`. Without the offset
 *   (a take recorded to a click in headphones), it is found by lining the transcription up with the plan
 *   ([Metrics.bestOffset]).
 *
 * Header lines `# instrument=TETRACHORDO|TRICHORDO`, `# kind=solo|mix` and `# group=<what it tests>` may be in either
 * file; else the folder's `set.properties` (same keys) says, else tetrachordo, solo, and the name's first word.
 * See docs/eval-recordings.md for how to record a set.
 */
object EvalSet {
    fun load(dir: File): List<EvalItem> {
        val defaults = File(dir, "set.properties").takeIf { it.isFile }?.readLines()?.let(::header) ?: emptyMap()
        return (dir.listFiles { f -> f.extension.lowercase() == "wav" } ?: emptyArray()).sortedBy { it.name }.mapNotNull { wav ->
            val base = wav.nameWithoutExtension
            val refFile = File(dir, "$base.ref.csv").takeIf { it.isFile }
            val planFile = File(dir, "$base.plan.csv").takeIf { it.isFile }
            val lines = (refFile ?: planFile)?.readLines() ?: return@mapNotNull null
            val h = defaults + header(lines)
            val instrument = Instrument.valueOf(h["instrument"]?.uppercase(Locale.ROOT) ?: "TETRACHORDO")
            val ref = if (refFile != null) refNotes(lines) else planNotes(lines, instrument)
            EvalItem(base, wav, ref, instrument, h["kind"] ?: "solo", h["group"] ?: base.substringBefore('-').substringBefore('_'), h["cents"]?.toDoubleOrNull())
        }
    }

    private fun header(lines: List<String>): Map<String, String> = lines
        .map { it.trim().removePrefix("#").trim() }
        .filter { '=' in it && !it.first().isDigit() }
        .associate { it.substringBefore('=').trim().lowercase(Locale.ROOT) to it.substringAfter('=').trim() }

    private fun rows(lines: List<String>) = lines.map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") && it.first().let { c -> c.isDigit() || c == '-' || c == '.' } }
        .map { it.split(',').map(String::trim) }

    private fun articulation(s: String?) = s?.let { a -> Articulation.entries.firstOrNull { it.name.equals(a, true) } } ?: Articulation.PICK

    fun refNotes(lines: List<String>): List<RefNote> = rows(lines).map { c ->
        RefNote(c[0].toDouble(), c[1].toDouble(), c[2].toInt(), c.getOrNull(3)?.toIntOrNull() ?: -1, c.getOrNull(4)?.toIntOrNull() ?: -1, articulation(c.getOrNull(5)))
    }.sortedBy { it.start }

    /** A click plan's notes in seconds (from beat 0 at the `offset`, or at 0 till [Metrics.bestOffset] places it). */
    fun planNotes(lines: List<String>, instrument: Instrument): List<RefNote> {
        val h = header(lines)
        val beat = 60.0 / (h["bpm"]?.toDouble() ?: error("a plan needs # bpm=<tempo>"))
        val offset = h["offset"]?.toDouble() ?: 0.0
        return rows(lines).map { c ->
            val course = c[2].toInt()
            val fret = c[3].toInt()
            val start = offset + c[0].toDouble() * beat
            RefNote(start, start + c[1].toDouble() * beat, instrument.midiAt(course, fret), course, fret, articulation(c.getOrNull(4)))
        }.sortedBy { it.start }
    }

    /** Whether the plan in [lines] says where beat 0 is (else it is found). */
    fun hasOffset(lines: List<String>) = "offset" in header(lines)

    fun writeRef(file: File, notes: List<RefNote>, header: Map<String, String> = emptyMap()) {
        file.bufferedWriter().use { w ->
            for ((k, v) in header) w.write("# $k=$v\n")
            w.write("# start,end,midi,course,fret,articulation\n")
            for (n in notes) w.write(String.format(Locale.US, "%.4f,%.4f,%d,%d,%d,%s\n", n.start, n.end, n.midi, n.course, n.fret, n.articulation.name))
        }
    }
}

/** WAV files: 16-bit, 24-bit, 32-bit integer or 32-bit float PCM, any channels and rate. */
object Wav {
    class Audio(val channels: Array<FloatArray>, val rate: Int)

    fun read(f: File): Audio {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        require(b.getInt(0) == 0x46464952 && b.getInt(8) == 0x45564157) { "${f.name} isn't a WAV file" }
        var pos = 12
        var format = 1; var channels = 1; var rate = 44100; var bits = 16
        var data = -1; var size = 0
        while (pos + 8 <= b.limit()) {
            val id = b.getInt(pos); val len = b.getInt(pos + 4)
            if (id == 0x20746d66) { // "fmt "
                format = b.getShort(pos + 8).toInt() and 0xFFFF
                channels = b.getShort(pos + 10).toInt()
                rate = b.getInt(pos + 12)
                bits = b.getShort(pos + 22).toInt()
                if (format == 0xFFFE) format = b.getShort(pos + 32).toInt() and 0xFFFF // WAVE_FORMAT_EXTENSIBLE
            } else if (id == 0x61746164) { // "data"
                data = pos + 8; size = minOf(len, b.limit() - data); break
            }
            pos += 8 + len + (len and 1)
        }
        require(data >= 0) { "${f.name} has no audio" }
        val bytes = bits / 8
        val frames = size / (bytes * channels)
        val out = Array(channels) { FloatArray(frames) }
        for (i in 0 until frames) for (c in 0 until channels) {
            val at = data + (i * channels + c) * bytes
            out[c][i] = when {
                format == 3 && bits == 32 -> b.getFloat(at)
                bits == 16 -> b.getShort(at) / 32768f
                bits == 24 -> ((b.get(at).toInt() and 0xFF) or ((b.get(at + 1).toInt() and 0xFF) shl 8) or (b.get(at + 2).toInt() shl 16)) / 8388608f
                bits == 32 -> b.getInt(at) / 2147483648f
                else -> error("${f.name}: $bits-bit audio isn't read")
            }
        }
        return Audio(out, rate)
    }

    /** Writes 16-bit PCM, scaled down only if it would clip. */
    fun write16(f: File, channels: Array<FloatArray>, rate: Int) {
        val n = channels.minOf { it.size }
        val peak = channels.maxOf { ch -> ch.maxOfOrNull { kotlin.math.abs(it) } ?: 0f }
        val gain = if (peak > 0.999f) 0.999f / peak else 1f
        val c = channels.size
        DataOutputStream(f.outputStream().buffered()).use { out ->
            val head = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
            head.putInt(0x46464952).putInt(36 + n * 2 * c).putInt(0x45564157)
            head.putInt(0x20746d66).putInt(16).putShort(1).putShort(c.toShort()).putInt(rate).putInt(rate * 2 * c).putShort((2 * c).toShort()).putShort(16)
            head.putInt(0x61746164).putInt(n * 2 * c)
            out.write(head.array())
            val buf = ByteBuffer.allocate(n * 2 * c).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) for (ch in channels) buf.putShort((ch[i] * gain * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            out.write(buf.array())
        }
    }
}
