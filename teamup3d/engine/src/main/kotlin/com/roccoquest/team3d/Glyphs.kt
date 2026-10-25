package com.roccoquest.team3d

/**
 * Text for the 3D screens. Each character is drawn once (by the platform, in the same bold
 * monospace font the 2D game uses) into its own texture, and words become a row of small
 * textured squares that can be placed anywhere in 3D.
 */
object Glyphs {
    /** Draws one character in white on a transparent background, [RASTER] pixels tall. */
    fun interface Rasterizer {
        fun draw(c: Char): Bitmap
    }

    /**
     * A character's picture: [pixels] are ARGB, the baseline is [ascent] pixels from the top,
     * the picture starts [left] pixels before the pen, and the pen then moves [advance] pixels.
     */
    class Bitmap(val w: Int, val h: Int, val pixels: IntArray, val ascent: Float, val left: Float, val advance: Float)

    /** Font size the characters are drawn at; text is scaled from this. */
    const val RASTER = 48f
    const val SIZE = 1024

    /** Set by the platform before any text is drawn. Without one, text is skipped. */
    @Volatile var rasterizer: Rasterizer? = null

    val pixels = IntArray(SIZE * SIZE)
    @Volatile var version = 0
        private set

    private class Glyph(val region: Region, val w: Float, val h: Float, val ascent: Float, val left: Float, val advance: Float)

    private val glyphs = HashMap<Char, Glyph>()
    private var penX = 0
    private var penY = 0
    private var rowH = 0

    private fun glyph(c: Char): Glyph? {
        glyphs[c]?.let { return it }
        val r = rasterizer ?: return null
        val b = r.draw(c)
        if (penX + b.w + 2 > SIZE) { penX = 0; penY += rowH + 2; rowH = 0 }
        if (penY + b.h + 2 > SIZE) {
            // Full (many unusual characters): start over. Old words get rebuilt as needed.
            pixels.fill(0)
            glyphs.clear()
            words.values.forEach { it.mesh?.release() }
            words.clear()
            penX = 0; penY = 0; rowH = 0
        }
        val ox = penX + 1; val oy = penY + 1
        for (y in 0 until b.h) System.arraycopy(b.pixels, y * b.w, pixels, (oy + y) * SIZE + ox, b.w)
        penX += b.w + 2
        rowH = maxOf(rowH, b.h)
        version++
        val g = Glyph(
            Region(ox.toFloat() / SIZE, oy.toFloat() / SIZE, (ox + b.w).toFloat() / SIZE, (oy + b.h).toFloat() / SIZE),
            b.w.toFloat(), b.h.toFloat(), b.ascent, b.left, b.advance,
        )
        glyphs[c] = g
        return g
    }

    /** A word's squares, built around its baseline in [RASTER]-sized units, and its width. */
    class Word(val mesh: Mesh?, val width: Float)

    private val words = LinkedHashMap<String, Word>(256, 0.75f, true)

    fun word(s: String): Word {
        words[s]?.let { return it }
        val b = MeshBuilder()
        var pen = 0f
        for (c in s) {
            val g = glyph(c) ?: continue
            if (c != ' ') {
                val x0 = pen - g.left
                val x1 = x0 + g.w
                val y0 = g.ascent - g.h
                val y1 = g.ascent
                val r = g.region
                // A flat square facing the camera, with the character's picture on it.
                b.vertex(x0, y0, 0f, 0f, 0f, 1f, -1, r.u0, r.v1)
                b.vertex(x1, y0, 0f, 0f, 0f, 1f, -1, r.u1, r.v1)
                b.vertex(x1, y1, 0f, 0f, 0f, 1f, -1, r.u1, r.v0)
                b.vertex(x0, y0, 0f, 0f, 0f, 1f, -1, r.u0, r.v1)
                b.vertex(x1, y1, 0f, 0f, 0f, 1f, -1, r.u1, r.v0)
                b.vertex(x0, y1, 0f, 0f, 0f, 1f, -1, r.u0, r.v0)
            }
            pen += g.advance
        }
        val w = Word(if (b.isEmpty) null else b.build(), pen)
        words[s] = w
        if (words.size > 900) {
            val it = words.entries.iterator()
            it.next().value.mesh?.release()
            it.remove()
        }
        return w
    }
}
