package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sprite
import com.roccoquest.game.Sprites
import com.roccoquest.game.mixColor

/** What Construction Rocco can build with, and what each block costs. */
enum class BuildBlock(val label: String, val ore: Ore?, val coins: Int) {
    BRICK("BRICK", Ore.STONE, 0),
    WOOD("WOOD", null, 2),
    STONE("STONE", Ore.STONE, 0),
    GLASS("GLASS", null, 3),
    GOLD("GOLD", Ore.GOLD, 0),
    DIAMOND("DIAMOND", Ore.DIAMOND, 0),
}

/**
 * CONSTRUCTION ROCCO: build with blocks on the walls of any floor. The room is a grid of 16x16 cells
 * ([COLS] across, [ROWS] up from the floor). Blocks are saved per floor as `bld<floor>n` + `bld<floor>_<i>`.
 */
object Build {
    const val CELL = 16f
    val COLS = (Homes.ROOM_W / CELL).toInt()
    const val ROWS = 9
    /** Most blocks on one floor. */
    const val MAX = 400

    fun cellX(c: Int) = c * CELL
    fun cellY(r: Int) = Homes.FLOOR_Y - (r + 1) * CELL

    /** The cell at room position ([x], [y]), or -1. */
    fun cellAt(x: Float, y: Float): Int {
        val c = (x / CELL).toInt()
        val r = ((Homes.FLOOR_Y - y) / CELL).toInt()
        return if (x < 0 || c !in 0 until COLS || y > Homes.FLOOR_Y || r !in 0 until ROWS) -1 else r * COLS + c
    }

    private val wood: Sprite by lazy {
        Sprite(16, 16, IntArray(256) { i ->
            val x = i % 16; val y = i / 16
            when {
                y % 4 == 3 -> 0xFF7A4A20.toInt()
                (x + y / 4 * 5) % 9 == 0 -> 0xFF8A5A2A.toInt()
                else -> 0xFFB8844A.toInt()
            }
        })
    }

    fun draw(gfx: Gfx, b: BuildBlock, x: Float, y: Float, t: Float) {
        when (b) {
            BuildBlock.BRICK -> gfx.sprite(Sprites.brick, x, y)
            BuildBlock.STONE -> gfx.sprite(Sprites.stone, x, y)
            BuildBlock.WOOD -> gfx.sprite(wood, x, y)
            BuildBlock.GLASS -> {
                gfx.rect(x, y, 16f, 16f, 0x668AD8FF)
                gfx.rect(x, y, 16f, 1f, 0xCCFFFFFF.toInt()); gfx.rect(x, y, 1f, 16f, 0xCCFFFFFF.toInt())
                gfx.rect(x + 3, y + 3, 2f, 7f, 0x99FFFFFF.toInt())
            }
            BuildBlock.GOLD, BuildBlock.DIAMOND -> {
                val c = if (b == BuildBlock.GOLD) 0xFFE8C83A.toInt() else 0xFF6AE8F0.toInt()
                gfx.rect(x, y, 16f, 16f, mixColor(c, 0xFF000000.toInt(), 0.25f))
                gfx.rect(x + 1, y + 1, 14f, 14f, c)
                gfx.rect(x + 2, y + 2, 12f, 2f, mixColor(c, 0xFFFFFFFF.toInt(), 0.5f))
                if (((t * 2).toInt() + (x / 16).toInt()) % 5 == 0) gfx.rect(x + 10, y + 5, 2f, 2f, 0xFFFFFFFF.toInt())
            }
        }
    }

    /** Construction Rocco's yellow hard hat, on a head at ([cx], [top]). */
    fun hardHat(gfx: Gfx, cx: Float, top: Float) {
        gfx.oval(cx - 7, top - 2, 14f, 9f, 0xFFF4C83A.toInt())
        gfx.rect(cx - 8.5f, top + 4, 17f, 2f, 0xFFE8A82A.toInt())
        gfx.rect(cx - 1, top - 1, 2f, 6f, 0xFFFFE07A.toInt())
    }
}
