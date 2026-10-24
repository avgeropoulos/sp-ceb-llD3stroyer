package com.bouzouki

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import com.bouzouki.core.NoteNames
import com.bouzouki.core.song.AnalysisData
import com.bouzouki.core.song.Articulation
import com.bouzouki.core.song.BasicPitch
import com.bouzouki.core.song.SongNote
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

/**
 * "Send song data" as a PDF, since chats take PDFs where they refuse zips: page 1 is a picture of the
 * song around the playback time (what the note detector heard, with the line's notes on top, and the
 * player's note), and the song's data zip rides along as a standard PDF attachment.
 */
internal object DataPdf {
    /** The roll's picture: [heading] lines on top, then [seconds] around [time] of [data] with [notes]. */
    fun roll(heading: List<String>, data: AnalysisData?, notes: List<SongNote>, time: Double, seconds: Double = 40.0): Bitmap {
        val w = 2400; val h = 1600
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(0xFF101820.toInt())
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f }
        val bold = Paint(text).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 44f }
        var y = 60f
        heading.forEachIndexed { i, line -> c.drawText(line.take(150), 30f, y, if (i == 0) bold else text); y += if (i == 0) 56f else 44f }

        val left = 90; val right = w - 20; val top = max(y.toInt() + 20, 260); val bottom = h - 70
        val lo = 45; val hi = 89 // MIDI A2 .. F6
        val keyH = (bottom - top).toFloat() / (hi - lo)
        val act = data?.act
        val frames = act?.frames
        val duration = frames?.let { if (it.isEmpty()) 0.0 else BasicPitch.frameTime(it.size - 1) } ?: (notes.maxOfOrNull { it.end } ?: 0.0)
        val t0 = (time - seconds / 2).coerceAtMost(duration - seconds).coerceAtLeast(0.0)
        val t1 = t0 + seconds
        val pps = (right - left) / seconds
        fun xAt(t: Double) = (left + (t - t0) * pps).toFloat()
        fun yAt(midi: Int) = bottom - (midi - lo + 1) * keyH

        // What was heard: the frame activations as grey, onsets in red.
        if (act != null && frames != null && frames.isNotEmpty()) {
            val onsets = act.onsets
            val rw = right - left; val rh = bottom - top
            val px = IntArray(rw * rh)
            var f = 0
            for (x in 0 until rw) {
                val t = t0 + x / pps
                while (f < frames.size - 1 && BasicPitch.frameTime(f + 1) <= t) f++
                if (BasicPitch.frameTime(f) > t || t > duration) continue
                for (row in 0 until rh) {
                    val midi = hi - 1 - (row / keyH).toInt()
                    val k = midi - BasicPitch.MIDI_OFFSET
                    if (k !in 0 until BasicPitch.N_KEYS) continue
                    val v = min(1f, frames[f][k] / 0.8f)
                    val o = min(1f, max(0f, (onsets[f][k] - 0.25f) / 0.6f))
                    val g = (16 + v * 200).toInt()
                    px[row * rw + x] = Color.rgb(min(255, g + (o * 180).toInt()), max(0, g - (o * 60).toInt()), max(0, g - (o * 80).toInt()))
                }
            }
            bmp.setPixels(px, 0, rw, left, top, rw, rh)
        }

        // The grid: a line at every C, and times every half second.
        val grid = Paint().apply { color = 0x40FFFFFF; strokeWidth = 1f }
        val small = Paint(text).apply { textSize = 22f; color = 0xFFB0C4DE.toInt() }
        for (m in lo until hi) {
            if (m % 12 == 0) c.drawLine(left.toFloat(), yAt(m) + keyH, right.toFloat(), yAt(m) + keyH, grid)
            if (m % 12 in intArrayOf(0, 2, 4, 5, 7, 9, 11)) c.drawText(NoteNames.WESTERN.name(m % 12) + (m / 12 - 1), 6f, yAt(m) + keyH * 0.8f, small)
        }
        var t = Math.ceil(t0 * 2) / 2
        while (t <= t1) {
            val x = xAt(t)
            val whole = Math.abs(t - Math.round(t)) < 1e-6
            grid.color = if (whole) 0x60FFFFFF else 0x28FFFFFF
            c.drawLine(x, top.toFloat(), x, bottom.toFloat(), grid)
            if (whole) c.drawText(SongExport.clock(t), x - 26, bottom + 34f, small)
            t += 0.5
        }

        // The line's notes on top, labelled with course and fret.
        val box = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 3f; color = 0xFF00E5FF.toInt() }
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 20f; color = 0xFF00E5FF.toInt(); typeface = Typeface.DEFAULT_BOLD }
        for (n in notes) {
            if (n.end < t0 || n.start > t1 || n.midi !in lo until hi) continue
            val x0 = xAt(n.start); val x1 = max(x0 + 4, xAt(n.end))
            c.drawRect(x0, yAt(n.midi) + 1, x1, yAt(n.midi) + keyH - 1, box)
            val art = when (n.articulation) { Articulation.PICK -> ""; Articulation.HAMMER -> "h"; Articulation.PULL -> "p"; Articulation.SLIDE -> "/"; Articulation.TREMOLO -> "~" }
            c.drawText("${n.course}:${n.fret}$art", x0 + 2, yAt(n.midi) - 3, label)
        }
        val now = Paint().apply { color = 0xFFFFC107.toInt(); strokeWidth = 3f }
        if (time in t0..t1) c.drawLine(xAt(time), top.toFloat(), xAt(time), bottom.toFloat(), now)
        c.drawText("grey = heard, red = note starts, cyan boxes = the app's notes (course:fret, 0 = lowest course), gold = playback time",
            left.toFloat(), h - 14f, small)
        return bmp
    }

    /**
     * Writes a PDF of [pages] (each a JPEG with its pixel size) with [attachment] attached as [name]
     * (an embedded file, listed in the reader's attachments panel).
     */
    fun write(out: OutputStream, pages: List<Triple<ByteArray, Int, Int>>, name: String, attachment: ByteArray, description: String) {
        val buf = ByteArrayOutputStream()
        val offsets = ArrayList<Int>()
        fun ascii(s: String) = buf.write(s.toByteArray(Charsets.ISO_8859_1))
        fun obj(dict: String, stream: ByteArray? = null) {
            offsets += buf.size()
            ascii("${offsets.size} 0 obj\n$dict\n")
            if (stream != null) { ascii("stream\n"); buf.write(stream); ascii("\nendstream\n") }
            ascii("endobj\n")
        }
        val safeName = name.filter { it in ' '..'~' && it != '(' && it != ')' && it != '\\' }
        val safeDesc = description.filter { it in ' '..'~' && it != '(' && it != ')' && it != '\\' }
        ascii("%PDF-1.7\n%âãÏÓ\n")
        // Objects: 1 catalog, 2 pages, 3 file spec, 4 embedded file, then per page: page, contents, image.
        val kids = pages.indices.joinToString(" ") { "${5 + it * 3} 0 R" }
        obj("<< /Type /Catalog /Pages 2 0 R /Names << /EmbeddedFiles << /Names [($safeName) 3 0 R] >> >> /PageMode /UseAttachments >>")
        obj("<< /Type /Pages /Kids [$kids] /Count ${pages.size} >>")
        obj("<< /Type /Filespec /F ($safeName) /UF ($safeName) /Desc ($safeDesc) /EF << /F 4 0 R >> >>")
        obj("<< /Type /EmbeddedFile /Subtype /application#2Fzip /Params << /Size ${attachment.size} >> /Length ${attachment.size} >>", attachment)
        pages.forEachIndexed { i, (jpeg, pw, ph) ->
            val pageW = 842.0; val pageH = pageW * ph / pw
            val n = 5 + i * 3
            val contents = "q ${fmt(pageW)} 0 0 ${fmt(pageH)} 0 0 cm /Im0 Do Q".toByteArray(Charsets.ISO_8859_1)
            obj("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${fmt(pageW)} ${fmt(pageH)}] /Resources << /XObject << /Im0 ${n + 2} 0 R >> >> /Contents ${n + 1} 0 R >>")
            obj("<< /Length ${contents.size} >>", contents)
            obj("<< /Type /XObject /Subtype /Image /Width $pw /Height $ph /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.size} >>", jpeg)
        }
        val xref = buf.size()
        ascii("xref\n0 ${offsets.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) ascii(String.format(java.util.Locale.ROOT, "%010d 00000 n \n", o))
        ascii("trailer\n<< /Size ${offsets.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        buf.writeTo(out)
    }

    private fun fmt(v: Double) = String.format(java.util.Locale.ROOT, "%.2f", v)
}
