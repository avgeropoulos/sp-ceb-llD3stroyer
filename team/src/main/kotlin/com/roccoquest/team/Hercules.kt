package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sprite
import com.roccoquest.game.pixelArt

/**
 * Dennis the dog looks just like HERCULES from the Hercules and Apollo game: a black curly doodle
 * with a white beard, a red collar with a gold tag and a curly tail. The rows and colours are the
 * same as in that game (its WalkSprites), so he's the very same dog in both games. Faces right.
 */
object HerculesArt {
    /*
     * B body, b body shadow, c curl highlight, H head, h head highlight, E floppy ear,
     * M muzzle, W beard, N nose, I eye, g eye glint, C collar, Y tag, T tail, t tail curl.
     */
    private val top = arrayOf(
        "...............hHHHh",
        ".............HHHHHHHHH",
        "............HHHHhHHHHHH",
        "..TT.......HHHHHHHHHHHHH",
        ".TTtT.....EEEHHHHHHgIHHH",
        "TTTTtT...EEEEEHHHHHIIHMMM",
        "TtTTTT...EEEEEEHHHHHMMMNN",
        ".TTtTT...EEEEEEHHHHMMMMNN",
        "..TTTT..BEEEEEEhHHWWWWW",
        "...TTTBBBEEEEEECCCWWWW",
        "....BBBBcBEEEEEBCCYWW",
        "...BBcBBBBcBEEBBBB",
        "...BBBBBcBBBBBBcBBB",
        "...BcBBBBBBcBBBBBBB",
        "....BBbbbbbbbbbBBB",
    )
    private val standLegs = arrayOf(
        "....BBB.BB...BBB.BB",
        "....BBB.BB...BBB.BB",
        "....bbb.bbb..bbb.bbb",
    )
    private val walkLegs = arrayOf(
        "....BBB..BB..BB..BBB",
        "...BBB....BB.BB...BBB",
        "..bbb.....bbbbb....bbb",
    )
    private val leapLegs = arrayOf(
        "..BBB...........BBB",
        ".BB...............BBB",
        "bb...................bb",
    )

    const val BODY = 0xFF1E1A1C.toInt()
    const val COLLAR = 0xFFE52521.toInt()
    private val pal = mapOf(
        'B' to BODY, 'b' to 0xFF0E0C0D.toInt(), 'c' to 0xFF3B3538.toInt(), 'H' to 0xFF221E20.toInt(),
        'h' to 0xFF403A3D.toInt(), 'E' to 0xFF141113.toInt(), 'M' to 0xFF2A2527.toInt(), 'W' to 0xFFE8E4DC.toInt(),
        'N' to 0xFF050505.toInt(), 'I' to 0xFF050505.toInt(), 'g' to 0xFFFFFFFF.toInt(), 'C' to COLLAR,
        'Y' to 0xFFFFD21F.toInt(), 'T' to 0xFF1A1618.toInt(), 't' to 0xFF3B3538.toInt(),
    )

    /**
     * The size of the art itself. The sprites have a see-through pixel on the top, left and right for
     * the outline, but not under his paws: they stand right on the ground, like Rocco's boots.
     */
    const val W = 25
    const val H = 18

    val stand: Sprite = sprite(*top, *standLegs)
    val walk: Sprite = sprite(*top, *walkLegs)
    val leap: Sprite = sprite(*top, *leapLegs)
    /** Standing with his tail flicked up a pixel: swapping with [stand] makes it wag. */
    val wag: Sprite = sprite(*tailUp(top), *standLegs)

    /** Where his mouth is (art pixels from the top-left when facing right): Dennis carries things here. */
    const val MOUTH_X = 22f
    const val MOUTH_Y = 8f

    /**
     * Draws [pose] with the art's top-left at ([x], [y]). Facing left mirrors him in his own box,
     * so his feet stay in the same spot either way.
     */
    fun draw(gfx: Gfx, pose: Sprite, x: Float, y: Float, facing: Int) =
        gfx.sprite(pose, x - 1, y - 1, flipX = facing < 0)

    /** Pads the rows to [W], adds the border pixels and gives him the same dark outline as everyone else. */
    private fun sprite(vararg rows: String): Sprite {
        require(rows.size == H && rows.all { it.length <= W })
        val padded = arrayOf(".".repeat(W + 2)) + rows.map { ".$it".padEnd(W + 2, '.') }
        return Outline.of(pixelArt(pal, *padded))
    }

    /** The same rows with every tail pixel moved up one row. */
    private fun tailUp(rows: Array<String>): Array<String> {
        fun tail(c: Char) = c == 'T' || c == 't'
        fun at(y: Int, x: Int) = rows.getOrNull(y)?.getOrNull(x) ?: '.'
        return Array(rows.size) { y ->
            String(CharArray(maxOf(rows[y].length, rows.getOrNull(y + 1)?.length ?: 0)) { x ->
                val below = at(y + 1, x)
                when {
                    tail(below) -> below
                    tail(at(y, x)) -> '.'
                    else -> at(y, x)
                }
            })
        }
    }
}
