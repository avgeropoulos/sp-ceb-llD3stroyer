package com.bouzouki

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.media.ExifInterface
import com.bouzouki.core.Instrument
import com.bouzouki.core.song.DigitGlyph
import com.bouzouki.core.song.GrayImage
import com.bouzouki.core.song.ImageTab
import com.bouzouki.core.song.ParsedTab
import com.bouzouki.core.song.PdfPicture
import com.bouzouki.core.song.PdfPictures
import com.bouzouki.core.song.PdfTab
import com.bouzouki.core.song.pageImages
import java.io.ByteArrayInputStream

/**
 * Tabs that are pictures: a screenshot or a saved image of a tab (made into a one-page PDF when it comes in, so it
 * goes the way a PDF goes: [toPdf]), and PDFs whose pages are pictures (read with [ImageTab] when [PdfTab] finds
 * no text in them: [readPictures]). Pictures are decoded by the phone (BitmapFactory), made gray, and kept big
 * enough for the tab's digits to stay readable. Photos of paper and handwritten tabs can't be read.
 */
object TabPictures {
    /** Pictures are made smaller (by halves) only while they stay at least this wide: a page's digits stay ~14 px tall. */
    private const val KEEP_WIDTH = 2000

    /** The most pixels a picture is decoded to (memory). */
    private const val MAX_PIXELS = 16_000_000L

    /** Whether [bytes] (with MIME [mime], file [name]) are a picture the phone can decode. */
    fun isPicture(bytes: ByteArray, mime: String?, name: String): Boolean {
        fun at(i: Int, vararg b: Int) = bytes.size >= i + b.size && b.indices.all { bytes[i + it] == b[it].toByte() }
        fun ascii(i: Int, s: String) = at(i, *s.map { it.code }.toIntArray())
        return at(0, 0xFF, 0xD8, 0xFF) || at(0, 0x89, 0x50, 0x4E, 0x47) || ascii(0, "GIF8") || (ascii(0, "RIFF") && ascii(8, "WEBP")) ||
            ascii(0, "BM") || (ascii(4, "ftyp") && (ascii(8, "heic") || ascii(8, "heix") || ascii(8, "mif1") || ascii(8, "avif"))) ||
            (mime?.startsWith("image/") == true && !TabFiles.isPdf(bytes)) ||
            Regex(""".*\.(jpe?g|png|webp|gif|bmp|heic|heif)$""", RegexOption.IGNORE_CASE).matches(name)
    }

    /** The picture in [bytes] as a one-page PDF of its gray levels, or null if the phone can't decode it. */
    fun toPdf(bytes: ByteArray): ByteArray? {
        val g = decode(bytes, exif = true) ?: return null
        return PdfPictures.wrap(g.gray, g.width, g.height)
    }

    /**
     * The tab in the pictures [pdf]'s pages are made of, read for [instrument]; null if it has none. Off the main
     * thread only: decoding and reading the pages takes a few seconds.
     */
    fun readPictures(pdf: ByteArray, instrument: Instrument): ParsedTab? {
        val pics = PdfTab.pageImages(pdf)
        if (pics.isEmpty()) return null
        val r = ImageTab.read(pics.size, { k -> page(pics[k]) }, instrument, ::digits)
        return r.tab
    }

    /** A PDF page's picture as gray levels, turned as the page shows it; null if it's in a format the phone can't open. */
    private fun page(p: PdfPicture): GrayImage? {
        val g = when (p.kind) {
            PdfPicture.Kind.JPEG -> decode(p.bytes, exif = false)
            PdfPicture.Kind.SAMPLES -> p.gray()?.let { GrayImage(it, p.width, p.height) }?.let { shrink(it) }
            else -> null // JPEG 2000, fax and JBIG2 pictures: not decoded here
        } ?: return null
        return turn(g, p.quarterTurns)
    }

    /** [bytes] decoded to gray levels, made smaller by halves while wide enough, turned by its EXIF orientation if [exif]. */
    private fun decode(bytes: ByteArray, exif: Boolean): GrayImage? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null
        // A JPEG has no transparency: two bytes a pixel do; other pictures may be see-through (white behind), at four.
        val jpeg = bounds.outMimeType == "image/jpeg"
        val most = if (jpeg) MAX_PIXELS else MAX_PIXELS / 2
        var sample = 1
        while (w / (2 * sample) >= KEEP_WIDTH && h / (2 * sample) >= KEEP_WIDTH / 2) sample *= 2
        while ((w / sample).toLong() * (h / sample) > most) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = if (jpeg) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        try {
            val g = gray(bmp)
            val turns = if (exif) exifTurns(bytes) else 0
            return turn(g, turns)
        } finally {
            bmp.recycle()
        }
    }

    /** [bmp]'s gray levels (see-through pixels on white), read a band of rows at a time. */
    private fun gray(bmp: Bitmap): GrayImage {
        val w = bmp.width
        val h = bmp.height
        val out = ByteArray(w * h)
        val rows = maxOf(1, 65_536 / w)
        val px = IntArray(w * rows)
        var y = 0
        while (y < h) {
            val n = minOf(rows, h - y)
            bmp.getPixels(px, 0, w, 0, y, w, n)
            for (i in 0 until w * n) {
                val c = px[i]
                val a = Color.alpha(c)
                val l = (299 * Color.red(c) + 587 * Color.green(c) + 114 * Color.blue(c)) / 1000
                out[y * w + i] = ((l * a + 255 * (255 - a)) / 255).toByte()
            }
            y += n
        }
        return GrayImage(out, w, h)
    }

    /** A picture's EXIF orientation as clockwise quarter turns (a photo taken sideways); 0 without one. */
    private fun exifTurns(bytes: ByteArray): Int = try {
        when (ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 1
            ExifInterface.ORIENTATION_ROTATE_180 -> 2
            ExifInterface.ORIENTATION_ROTATE_270 -> 3
            else -> 0
        }
    } catch (_: Exception) {
        0
    }

    /** [g] made smaller by halves while it stays wide enough (as [decode] does for a picture file). */
    private fun shrink(g: GrayImage): GrayImage {
        var cur = g
        while (cur.width / 2 >= KEEP_WIDTH && cur.height / 2 >= KEEP_WIDTH / 2 || cur.width.toLong() * cur.height > MAX_PIXELS) {
            val w = cur.width / 2
            val h = cur.height / 2
            val src = cur
            cur = GrayImage(ByteArray(w * h) { i ->
                val x = 2 * (i % w)
                val y = 2 * (i / w)
                fun v(xx: Int, yy: Int) = src.gray[yy * src.width + xx].toInt() and 0xFF
                ((v(x, y) + v(x + 1, y) + v(x, y + 1) + v(x + 1, y + 1)) / 4).toByte()
            }, w, h)
        }
        return cur
    }

    /** [g] turned [turns] quarter turns clockwise. */
    private fun turn(g: GrayImage, turns: Int): GrayImage {
        val t = turns.mod(4)
        if (t == 0) return g
        val w = g.width
        val h = g.height
        val nw = if (t == 2) w else h
        val nh = if (t == 2) h else w
        val out = ByteArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val (nx, ny) = when (t) {
                1 -> (h - 1 - y) to x
                2 -> (w - 1 - x) to (h - 1 - y)
                else -> y to (w - 1 - x)
            }
            out[ny * nw + nx] = g.gray[y * w + x]
        }
        return GrayImage(out, nw, nh)
    }

    /**
     * The digits 0-9 in the phone's fonts (sans-serif and serif, regular and bold), [height] pixels tall, for
     * [ImageTab] to compare a picture's numbers with.
     */
    fun digits(height: Int): List<DigitGlyph> {
        val out = ArrayList<DigitGlyph>()
        val faces = listOf(
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL), Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD),
            Typeface.create(Typeface.SERIF, Typeface.NORMAL), Typeface.create(Typeface.SERIF, Typeface.BOLD),
        )
        val size = 2 * height + 4
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val px = IntArray(size * size)
        val bounds = Rect()
        for ((k, face) in faces.withIndex()) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = face; color = Color.WHITE; textSize = 100f }
            paint.getTextBounds("0123456789", 0, 10, bounds)
            if (bounds.height() <= 0) continue
            paint.textSize = 100f * height / bounds.height()
            for (d in 0..9) {
                bmp.eraseColor(Color.TRANSPARENT)
                canvas.drawText("$d", height / 3f, height * 1.5f, paint)
                bmp.getPixels(px, 0, size, 0, 0, size, size)
                out += DigitGlyph(d, size, size, ByteArray(size * size) { Color.alpha(px[it]).toByte() }, "face $k")
            }
        }
        bmp.recycle()
        return out
    }
}
