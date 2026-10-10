package com.spotless.game

import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** The play area of a room on screen. */
const val SCENE_TOP = 120f
const val SCENE_BOTTOM = 1050f
const val FLOOR_Y = 610f

enum class Tool(val label: String, val color: Int) {
    BROOM("Broom", rgb(0xF2, 0xA6, 0x3B)),
    SPONGE("Sponge", rgb(0x4F, 0xC3, 0xF7)),
    HAND("Pick Up", rgb(0x8B, 0xD1, 0x6A)),
    DUSTER("Duster", rgb(0xC5, 0x8B, 0xF2)),
}

enum class MessType(val tool: Tool, val toughness: Float, val label: String) {
    DUST(Tool.BROOM, 1f, "dust"),
    CRUMBS(Tool.BROOM, 0.8f, "crumbs"),
    SPILL(Tool.SPONGE, 1.3f, "spill"),
    MUD(Tool.SPONGE, 1.1f, "muddy prints"),
    SMUDGE(Tool.SPONGE, 1.1f, "smudge"),
    CLOTHES(Tool.HAND, 0f, "clothes"),
    TRASH(Tool.HAND, 0f, "trash"),
    COBWEB(Tool.DUSTER, 1f, "cobweb"),
}

class Mess(val type: MessType, val x: Float, val y: Float, val size: Float, val rot: Float, val variant: Int) {
    /** 1 = filthy, 0 = clean. */
    var dirt = 1f
    var cleaned = false
    /** For picked-up items: 0..1 progress of flying into the basket. */
    var fly = -1f
}

enum class RoomKind(val title: String, val wall: Int, val floor: Int) {
    LIVING("Living Room", rgb(0xFF, 0xE3, 0xCF), rgb(0xC8, 0x8E, 0x5C)),
    KITCHEN("Kitchen", rgb(0xD8, 0xF3, 0xE6), rgb(0xF4, 0xF4, 0xF4)),
    BEDROOM("Bedroom", rgb(0xE8, 0xDD, 0xFF), rgb(0xD9, 0xB8, 0xC9)),
    BATHROOM("Bathroom", rgb(0xD6, 0xEE, 0xFF), rgb(0xE0, 0xE8, 0xF0)),
    STUDY("Study", rgb(0xE4, 0xF0, 0xC8), rgb(0xA8, 0x74, 0x48)),
}

object Rooms {
    /** The basket that picked-up things fly into. */
    const val BASKET_X = 640f
    const val BASKET_Y = 1000f

    /** The window (or mirror) where smudges appear: x, y, w, h. */
    fun glass(kind: RoomKind): FloatArray = when (kind) {
        RoomKind.KITCHEN -> floatArrayOf(250f, 190f, 220f, 190f)
        RoomKind.BATHROOM -> floatArrayOf(400f, 200f, 170f, 200f)
        RoomKind.BEDROOM -> floatArrayOf(470f, 180f, 180f, 220f)
        else -> floatArrayOf(80f, 180f, 200f, 230f)
    }

    /** Builds a messy room. Later days have more mess. */
    fun makeMess(kind: RoomKind, day: Int, rnd: Random): List<Mess> {
        val count = (6 + day).coerceAtMost(16)
        val weights = when (kind) {
            RoomKind.LIVING -> listOf(MessType.DUST to 3, MessType.CRUMBS to 2, MessType.SPILL to 2, MessType.CLOTHES to 2, MessType.TRASH to 3, MessType.COBWEB to 2, MessType.SMUDGE to 1, MessType.MUD to 1)
            RoomKind.KITCHEN -> listOf(MessType.CRUMBS to 3, MessType.SPILL to 4, MessType.TRASH to 3, MessType.DUST to 1, MessType.COBWEB to 1, MessType.SMUDGE to 1)
            RoomKind.BEDROOM -> listOf(MessType.CLOTHES to 5, MessType.DUST to 3, MessType.TRASH to 2, MessType.COBWEB to 2, MessType.SMUDGE to 1)
            RoomKind.BATHROOM -> listOf(MessType.SPILL to 3, MessType.SMUDGE to 2, MessType.CLOTHES to 2, MessType.TRASH to 2, MessType.MUD to 2, MessType.COBWEB to 1)
            RoomKind.STUDY -> listOf(MessType.DUST to 3, MessType.TRASH to 4, MessType.COBWEB to 2, MessType.CRUMBS to 2, MessType.MUD to 2, MessType.SMUDGE to 1)
        }
        val total = weights.sumOf { it.second }
        val out = ArrayList<Mess>()
        var tries = 0
        // Make sure every room needs at least two different tools.
        val forced = ArrayDeque(listOf(weights[0].first, weights[1].first))
        while (out.size < count && tries < 500) {
            tries++
            val type = forced.removeFirstOrNull() ?: run {
                var r = rnd.nextInt(total)
                weights.first { r -= it.second; r < 0 }.first
            }
            if (type == MessType.SMUDGE && out.count { it.type == MessType.SMUDGE } >= 2) continue
            if (type == MessType.COBWEB && out.count { it.type == MessType.COBWEB } >= 3) continue
            val size = when (type) {
                MessType.SPILL -> 46f + rnd.nextFloat() * 18f
                MessType.COBWEB -> 54f + rnd.nextFloat() * 14f
                MessType.SMUDGE -> 30f + rnd.nextFloat() * 8f
                else -> 36f + rnd.nextFloat() * 12f
            }
            val x: Float
            val y: Float
            when (type) {
                MessType.COBWEB -> {
                    x = 50f + rnd.nextFloat() * (VW - 100f)
                    y = SCENE_TOP + 40f + rnd.nextFloat() * 50f
                }
                MessType.SMUDGE -> {
                    val g = glass(kind)
                    x = g[0] + size + rnd.nextFloat() * (g[2] - size * 2)
                    y = g[1] + size + rnd.nextFloat() * (g[3] - size * 2)
                }
                else -> {
                    x = 60f + rnd.nextFloat() * (VW - 120f)
                    y = FLOOR_Y + 70f + rnd.nextFloat() * (SCENE_BOTTOM - FLOOR_Y - 120f)
                    if (x > BASKET_X - 80f && y > BASKET_Y - 90f) continue
                }
            }
            if (out.any { (it.x - x) * (it.x - x) + (it.y - y) * (it.y - y) < (it.size + size).let { s -> s * s * 0.8f } }) continue
            out.add(Mess(type, x, y, size, rnd.nextFloat() * 360f, rnd.nextInt(3)))
        }
        return out
    }

    // ------------------------------------------------------------ backgrounds

    fun drawRoom(p: Painter, kind: RoomKind, t: Float) {
        p.rect(0f, SCENE_TOP, VW, FLOOR_Y - SCENE_TOP, kind.wall)
        // Skirting board.
        p.rect(0f, FLOOR_Y - 14f, VW, 16f, WHITE)
        p.rect(0f, FLOOR_Y, VW, SCENE_BOTTOM - FLOOR_Y, kind.floor)
        when (kind) {
            RoomKind.LIVING -> living(p, t)
            RoomKind.KITCHEN -> kitchen(p, t)
            RoomKind.BEDROOM -> bedroom(p, t)
            RoomKind.BATHROOM -> bathroom(p, t)
            RoomKind.STUDY -> study(p, t)
        }
        basket(p)
        // Soft shadow under the ceiling.
        p.rect(0f, SCENE_TOP, VW, 10f, alpha(BLACK, 0.06f))
    }

    private fun window(p: Painter, g: FloatArray, curtain: Int?) {
        val (x, y, w, h) = listOf(g[0], g[1], g[2], g[3])
        p.roundRect(x - 10f, y - 10f, w + 20f, h + 20f, 6f, WHITE)
        p.rect(x, y, w, h, rgb(0xA8, 0xDC, 0xFF))
        p.oval(x + w * 0.3f, y + h * 0.35f, w * 0.16f, h * 0.06f, alpha(WHITE, 0.9f))
        p.oval(x + w * 0.42f, y + h * 0.32f, w * 0.12f, h * 0.07f, alpha(WHITE, 0.9f))
        p.rect(x, y + h * 0.75f, w, h * 0.25f, rgb(0x8C, 0xD4, 0x7A))
        p.line(x + w / 2, y, x + w / 2, y + h, WHITE, 8f)
        p.line(x, y + h / 2, x + w, y + h / 2, WHITE, 8f)
        p.fill(poly(x + 10f, y + h - 20f, x + 40f, y + 10f, x + 60f, y + 10f, x + 30f, y + h - 20f), alpha(WHITE, 0.25f))
        if (curtain != null) {
            p.fill(shape { moveTo(x - 30f, y - 20f); lineTo(x + 30f, y - 20f); quadTo(x + 10f, y + h * 0.5f, x + 24f, y + h + 30f); lineTo(x - 30f, y + h + 30f); close() }, curtain)
            p.fill(shape { moveTo(x + w + 30f, y - 20f); lineTo(x + w - 30f, y - 20f); quadTo(x + w - 10f, y + h * 0.5f, x + w - 24f, y + h + 30f); lineTo(x + w + 30f, y + h + 30f); close() }, curtain)
            p.rect(x - 40f, y - 28f, w + 80f, 10f, darker(curtain, 0.3f))
        }
    }

    private fun living(p: Painter, t: Float) {
        // Floorboards.
        for (i in 0..8) p.line(0f, FLOOR_Y + 50f * i, VW, FLOOR_Y + 50f * i, darker(RoomKind.LIVING.floor, 0.08f), 2f)
        window(p, glass(RoomKind.LIVING), rgb(0xF2, 0x7C, 0x9A))
        // Picture frame.
        p.rect(380f, 190f, 150f, 110f, rgb(0xB0, 0x7A, 0x40))
        p.rect(392f, 202f, 126f, 86f, rgb(0xFF, 0xF6, 0xE0))
        p.fill(heart(455f, 245f, 26f), PINK)
        // Lamp.
        p.line(620f, 420f, 620f, 596f, rgb(0x6A, 0x5A, 0x50), 6f)
        p.fill(poly(580f, 420f, 660f, 420f, 642f, 350f, 598f, 350f), rgb(0xFF, 0xE9, 0x9E))
        p.oval(620f, 598f, 30f, 8f, rgb(0x6A, 0x5A, 0x50))
        // Rug and sofa.
        p.oval(360f, 840f, 260f, 90f, rgb(0x7E, 0xB6, 0xE8))
        p.oval(360f, 840f, 220f, 70f, rgb(0xA6, 0xCE, 0xF2))
        val sofa = rgb(0x6C, 0x5C, 0xC8)
        p.roundRect(300f, 470f, 260f, 100f, 24f, darker(sofa, 0.1f))
        p.roundRect(280f, 530f, 300f, 90f, 20f, sofa)
        p.roundRect(268f, 500f, 44f, 120f, 18f, darker(sofa, 0.15f))
        p.roundRect(548f, 500f, 44f, 120f, 18f, darker(sofa, 0.15f))
        p.oval(350f, 520f, 30f, 26f, rgb(0xFF, 0xC4, 0x5E))
        p.rect(300f, 620f, 12f, 20f, rgb(0x4A, 0x3A, 0x30))
        p.rect(548f, 620f, 12f, 20f, rgb(0x4A, 0x3A, 0x30))
        plant(p, 60f, 600f, t)
    }

    private fun kitchen(p: Painter, t: Float) {
        // Checker floor.
        val dark = rgb(0xD7, 0xDD, 0xE6)
        var row = 0
        var y = FLOOR_Y
        while (y < SCENE_BOTTOM) {
            var x = if (row % 2 == 0) 0f else 60f
            while (x < VW) { p.rect(x, y, 60f, 60f, dark); x += 120f }
            y += 60f; row++
        }
        window(p, glass(RoomKind.KITCHEN), null)
        // Cabinets and counter.
        p.rect(0f, 440f, VW, 170f, rgb(0x5E, 0xA8, 0x8E))
        p.rect(0f, 424f, VW, 22f, rgb(0xEE, 0xEA, 0xE2))
        for (i in 0 until 6) {
            p.roundRect(14f + i * 118f, 462f, 104f, 130f, 6f, rgb(0x6F, 0xBC, 0xA0))
            p.roundRect(56f + i * 118f, 520f, 20f, 6f, 3f, rgb(0xE8, 0xC4, 0x5C))
        }
        // Sink and tap.
        p.rect(300f, 424f, 120f, 12f, rgb(0xB8, 0xC4, 0xD0))
        p.line(360f, 424f, 360f, 392f, rgb(0x9A, 0xA8, 0xB4), 8f)
        p.line(360f, 392f, 384f, 392f, rgb(0x9A, 0xA8, 0xB4), 8f)
        // Stove pot and fridge.
        p.roundRect(520f, 380f, 80f, 46f, 8f, rgb(0xE8, 0x5A, 0x4A))
        p.rect(510f, 386f, 100f, 8f, darker(rgb(0xE8, 0x5A, 0x4A), 0.2f))
        val steam = (t * 30f) % 40f
        p.oval(560f, 360f - steam, 10f, 8f, alpha(WHITE, 1f - steam / 40f))
        p.roundRect(40f, 170f, 150f, 250f, 14f, rgb(0xF2, 0xF6, 0xFA))
        p.line(40f, 270f, 190f, 270f, rgb(0xC8, 0xD0, 0xDA), 3f)
        p.line(170f, 200f, 170f, 250f, rgb(0xB0, 0xBA, 0xC6), 6f)
        p.line(170f, 290f, 170f, 360f, rgb(0xB0, 0xBA, 0xC6), 6f)
        p.fill(heart(90f, 220f, 14f), PINK)
    }

    private fun bedroom(p: Painter, t: Float) {
        window(p, glass(RoomKind.BEDROOM), rgb(0xFF, 0xF4, 0xB8))
        // Bed.
        p.roundRect(60f, 380f, 30f, 240f, 10f, rgb(0xB0, 0x7A, 0x50))
        p.roundRect(60f, 400f, 340f, 40f, 12f, rgb(0xB0, 0x7A, 0x50))
        p.roundRect(80f, 470f, 360f, 120f, 18f, rgb(0xFF, 0x9E, 0xC4))
        p.roundRect(80f, 470f, 360f, 30f, 14f, rgb(0xFF, 0xF0, 0xF6))
        p.oval(150f, 470f, 50f, 24f, WHITE)
        p.rect(80f, 590f, 18f, 30f, rgb(0x8A, 0x5A, 0x38))
        p.rect(420f, 590f, 18f, 30f, rgb(0x8A, 0x5A, 0x38))
        p.fill(heart(330f, 540f, 22f), rgb(0xFF, 0x6E, 0xA8))
        // Nightstand and teddy.
        p.roundRect(470f, 490f, 110f, 120f, 8f, rgb(0xC8, 0x92, 0x60))
        p.line(490f, 550f, 560f, 550f, rgb(0xA0, 0x6E, 0x44), 3f)
        p.oval(525f, 470f, 26f, 22f, rgb(0xC8, 0x8E, 0x5A))
        p.oval(525f, 444f, 18f, 16f, rgb(0xC8, 0x8E, 0x5A))
        p.oval(512f, 430f, 6f, 6f, rgb(0xC8, 0x8E, 0x5A))
        p.oval(538f, 430f, 6f, 6f, rgb(0xC8, 0x8E, 0x5A))
        // Carpet.
        p.roundRect(140f, 700f, 440f, 240f, 40f, rgb(0xC6, 0xA0, 0xE8))
        p.roundRect(160f, 720f, 400f, 200f, 30f, rgb(0xD8, 0xBC, 0xF2))
        plant(p, 660f, 600f, t)
    }

    private fun bathroom(p: Painter, t: Float) {
        for (i in 0..12) p.line(i * 60f, SCENE_TOP, i * 60f, FLOOR_Y, rgb(0xC0, 0xDC, 0xF0), 2f)
        for (j in 0..8) p.line(0f, SCENE_TOP + j * 60f, VW, SCENE_TOP + j * 60f, rgb(0xC0, 0xDC, 0xF0), 2f)
        for (i in 0..12) p.line(i * 70f, FLOOR_Y, i * 70f, SCENE_BOTTOM, rgb(0xC8, 0xD2, 0xDE), 2f)
        for (j in 0..7) p.line(0f, FLOOR_Y + j * 70f, VW, FLOOR_Y + j * 70f, rgb(0xC8, 0xD2, 0xDE), 2f)
        // Mirror (the smudge glass) over the sink.
        val g = glass(RoomKind.BATHROOM)
        p.roundRect(g[0] - 12f, g[1] - 12f, g[2] + 24f, g[3] + 24f, 20f, rgb(0xE8, 0xC4, 0x5C))
        p.roundRect(g[0], g[1], g[2], g[3], 12f, rgb(0xDD, 0xF2, 0xFF))
        p.fill(poly(g[0] + 20f, g[1] + 120f, g[0] + 80f, g[1] + 20f, g[0] + 110f, g[1] + 20f, g[0] + 50f, g[1] + 120f), alpha(WHITE, 0.6f))
        p.roundRect(410f, 440f, 150f, 40f, 12f, WHITE)
        p.rect(470f, 480f, 30f, 120f, WHITE)
        // Bathtub with bubbles.
        p.roundRect(30f, 450f, 300f, 140f, 40f, WHITE)
        p.rect(30f, 450f, 300f, 20f, rgb(0xEE, 0xF4, 0xFA))
        for (i in 0 until 7) {
            val bob = sin(t * 2f + i) * 4f
            p.oval(70f + i * 38f, 446f + bob, 24f, 20f, rgb(0xF4, 0xFA, 0xFF))
        }
        p.oval(50f, 600f, 10f, 10f, rgb(0xC8, 0xD0, 0xDA))
        p.oval(310f, 600f, 10f, 10f, rgb(0xC8, 0xD0, 0xDA))
        // Rubber duck.
        p.oval(250f, 432f, 18f, 14f, rgb(0xFF, 0xD8, 0x3A))
        p.oval(262f, 418f, 10f, 10f, rgb(0xFF, 0xD8, 0x3A))
        p.fill(poly(270f, 418f, 282f, 421f, 270f, 424f), rgb(0xFF, 0x8A, 0x2A))
        // Bath mat.
        p.roundRect(80f, 680f, 220f, 110f, 30f, rgb(0x9E, 0xE0, 0xC8))
    }

    private fun study(p: Painter, t: Float) {
        for (i in 0..8) p.line(0f, FLOOR_Y + 50f * i, VW, FLOOR_Y + 50f * i, darker(RoomKind.STUDY.floor, 0.08f), 2f)
        window(p, glass(RoomKind.STUDY), rgb(0x6C, 0xA8, 0x6A))
        // Bookshelf.
        p.rect(470f, 170f, 200f, 440f, rgb(0x8A, 0x5A, 0x38))
        val books = intArrayOf(rgb(0xE8, 0x5A, 0x4A), rgb(0x4F, 0x9D, 0xE8), rgb(0xF2, 0xC2, 0x3B), rgb(0x6C, 0xC0, 0x7A), rgb(0xB2, 0x7A, 0xE0))
        for (shelf in 0 until 4) {
            val sy = 190f + shelf * 104f
            p.rect(480f, sy + 84f, 180f, 10f, rgb(0x6A, 0x42, 0x26))
            var bx = 486f
            var k = shelf
            while (bx < 640f) {
                val bw = 18f + (k * 7 % 12)
                p.rect(bx, sy + 14f + (k % 3) * 6f, bw, 70f - (k % 3) * 6f, books[k % books.size])
                bx += bw + 3f; k++
            }
        }
        // Desk with laptop.
        p.rect(60f, 470f, 330f, 24f, rgb(0xB0, 0x7A, 0x50))
        p.rect(80f, 494f, 20f, 116f, rgb(0x8A, 0x5A, 0x38))
        p.rect(350f, 494f, 20f, 116f, rgb(0x8A, 0x5A, 0x38))
        p.fill(poly(150f, 470f, 290f, 470f, 280f, 390f, 160f, 390f), rgb(0x50, 0x58, 0x6A))
        p.rect(166f, 398f, 108f, 64f, if ((t % 2f) < 1f) rgb(0x7A, 0xC8, 0xFF) else rgb(0x8A, 0xD4, 0xFF))
        p.fill(heart(220f, 430f, 14f), PINK)
        // Chair.
        p.roundRect(200f, 520f, 90f, 30f, 10f, rgb(0xE8, 0x6A, 0x5A))
        p.line(245f, 550f, 245f, 600f, rgb(0x50, 0x50, 0x58), 6f)
        p.line(215f, 604f, 275f, 604f, rgb(0x50, 0x50, 0x58), 6f)
        p.roundRect(60f, 700f, 300f, 160f, 20f, rgb(0xE8, 0xD0, 0x8C))
    }

    private fun plant(p: Painter, x: Float, baseY: Float, t: Float) {
        val sway = sin(t * 1.3f) * 3f
        val leaf = rgb(0x4C, 0xB0, 0x5E)
        for (i in -2..2) {
            p.fill(shape {
                moveTo(x, baseY - 60f)
                quadTo(x + i * 22f + sway, baseY - 120f, x + i * 30f + sway, baseY - 150f + kotlin.math.abs(i) * 18f)
                quadTo(x + i * 10f, baseY - 100f, x, baseY - 60f)
                close()
            }, if (i % 2 == 0) leaf else darker(leaf, 0.15f))
        }
        p.fill(poly(x - 34f, baseY - 64f, x + 34f, baseY - 64f, x + 26f, baseY, x - 26f, baseY), rgb(0xE0, 0x7A, 0x4E))
    }

    private fun basket(p: Painter) {
        val x = BASKET_X
        val y = BASKET_Y
        p.oval(x, y + 34f, 58f, 10f, alpha(BLACK, 0.12f))
        p.fill(poly(x - 54f, y - 30f, x + 54f, y - 30f, x + 44f, y + 36f, x - 44f, y + 36f), rgb(0xD8, 0xA8, 0x6A))
        for (i in 0..3) p.line(x - 50f + i * 3f, y - 14f + i * 16f, x + 50f - i * 3f, y - 14f + i * 16f, rgb(0xB8, 0x86, 0x4C), 3f)
        p.roundRect(x - 60f, y - 38f, 120f, 14f, 7f, rgb(0xC0, 0x8C, 0x50))
    }

    // ------------------------------------------------------------ messes

    fun drawMess(p: Painter, m: Mess) {
        val a = if (m.type.tool == Tool.HAND) 1f else (0.15f + 0.85f * m.dirt)
        var x = m.x
        var y = m.y
        var sc = 1f
        if (m.fly >= 0f) {
            // Arc into the basket, shrinking as it goes.
            val k = m.fly
            x = m.x + (BASKET_X - m.x) * k
            y = m.y + (BASKET_Y - 20f - m.y) * k - sin(k * Math.PI.toFloat()) * 160f
            sc = 1f - 0.5f * k
        }
        p.save()
        p.translate(x, y)
        p.scale(sc, sc)
        val s = m.size
        when (m.type) {
            MessType.DUST -> {
                val c = alpha(rgb(0x8C, 0x86, 0x80), a)
                val c2 = alpha(rgb(0xA8, 0xA2, 0x9C), a)
                p.oval(0f, s * 0.2f, s, s * 0.45f, alpha(rgb(0x7A, 0x74, 0x6E), a * 0.5f))
                for (i in 0 until 7) {
                    val ang = m.rot + i * 51f
                    val r = s * (0.35f + (i % 3) * 0.12f)
                    p.oval(cosd(ang) * r, sind(ang) * r * 0.5f, s * 0.32f, s * 0.24f, if (i % 2 == 0) c else c2)
                }
                p.oval(0f, 0f, s * 0.42f, s * 0.3f, c2)
                if (m.dirt > 0.5f) {
                    p.oval(-s * 0.12f, -s * 0.05f, 3f, 3f, alpha(BLACK, a))
                    p.oval(s * 0.12f, -s * 0.05f, 3f, 3f, alpha(BLACK, a))
                }
            }
            MessType.CRUMBS -> {
                val rnd = Random(m.variant * 100 + m.size.toInt())
                for (i in 0 until 16) {
                    val ang = rnd.nextFloat() * 360f
                    val r = rnd.nextFloat() * s
                    val c = if (i % 3 == 0) rgb(0xE8, 0xB4, 0x6A) else rgb(0xB0, 0x76, 0x3C)
                    p.oval(cosd(ang) * r, sind(ang) * r * 0.55f, 4f + rnd.nextFloat() * 5f, 3f + rnd.nextFloat() * 4f, alpha(c, a))
                }
            }
            MessType.SPILL -> {
                val c = when (m.variant) {
                    0 -> rgb(0xF2, 0x9B, 0x28)
                    1 -> rgb(0x8E, 0x5A, 0x34)
                    else -> rgb(0x9C, 0x4C, 0xC8)
                }
                p.fill(shape {
                    moveTo(-s, 0f)
                    quadTo(-s, -s * 0.5f, -s * 0.3f, -s * 0.45f)
                    quadTo(s * 0.2f, -s * 0.7f, s * 0.7f, -s * 0.35f)
                    quadTo(s * 1.15f, -s * 0.1f, s * 0.8f, s * 0.25f)
                    quadTo(s * 0.4f, s * 0.6f, -s * 0.2f, s * 0.42f)
                    quadTo(-s * 1.05f, s * 0.45f, -s, 0f)
                    close()
                }, alpha(c, a * 0.9f))
                p.oval(s * 1.05f, s * 0.42f, s * 0.12f, s * 0.08f, alpha(c, a * 0.9f))
                p.oval(-s * 0.3f, -s * 0.18f, s * 0.25f, s * 0.08f, alpha(WHITE, a * 0.45f))
            }
            MessType.MUD -> {
                val c = alpha(rgb(0x6E, 0x4A, 0x2C), a)
                for (k in 0 until 2) {
                    val fx = if (k == 0) -s * 0.4f else s * 0.4f
                    val fy = if (k == 0) s * 0.15f else -s * 0.25f
                    p.oval(fx, fy, s * 0.22f, s * 0.36f, c)
                    p.oval(fx, fy - s * 0.48f, s * 0.16f, s * 0.12f, c)
                }
            }
            MessType.SMUDGE -> {
                val c = alpha(rgb(0x7E, 0x8A, 0x96), a * 0.55f)
                p.oval(0f, 0f, s * 0.5f, s * 0.6f, c)
                for (i in 0 until 4) {
                    val fx = -s * 0.42f + i * s * 0.28f
                    p.roundRect(fx - s * 0.1f, -s * 1.15f + (if (i == 0 || i == 3) s * 0.2f else 0f), s * 0.2f, s * 0.7f, s * 0.1f, c)
                }
                p.roundRect(s * 0.4f, -s * 0.25f, s * 0.5f, s * 0.18f, s * 0.09f, c)
            }
            MessType.CLOTHES -> {
                p.rotate(m.rot * 0.15f - 25f)
                val c = when (m.variant) {
                    0 -> rgb(0xE8, 0x4A, 0x6A)
                    1 -> rgb(0x4A, 0x8A, 0xE8)
                    else -> rgb(0x6C, 0xC0, 0x7A)
                }
                if (m.variant == 2) {
                    // A sock.
                    p.fill(shape { moveTo(-s * 0.2f, -s * 0.7f); lineTo(s * 0.2f, -s * 0.7f); lineTo(s * 0.2f, s * 0.2f); quadTo(s * 0.9f, s * 0.2f, s * 0.8f, s * 0.55f); quadTo(s * 0.3f, s * 0.7f, -s * 0.2f, s * 0.45f); close() }, c)
                    p.rect(-s * 0.2f, -s * 0.7f, s * 0.4f, s * 0.15f, WHITE)
                } else {
                    // A crumpled T-shirt.
                    p.fill(poly(-s * 0.5f, -s * 0.5f, -s * 0.15f, -s * 0.6f, 0f, -s * 0.45f, s * 0.15f, -s * 0.6f, s * 0.5f, -s * 0.5f,
                        s * 0.8f, -s * 0.15f, s * 0.5f, 0f, s * 0.45f, s * 0.6f, -s * 0.45f, s * 0.6f, -s * 0.5f, 0f, -s * 0.8f, -s * 0.15f), c)
                    p.stroke(shape { moveTo(-s * 0.2f, s * 0.1f); quadTo(0f, s * 0.3f, s * 0.25f, s * 0.05f) }, darker(c, 0.2f), 3f)
                }
            }
            MessType.TRASH -> {
                when (m.variant) {
                    0 -> {
                        p.fill(poly(-s * 0.5f, -s * 0.2f, -s * 0.2f, -s * 0.55f, s * 0.3f, -s * 0.45f, s * 0.55f, -s * 0.05f,
                            s * 0.35f, s * 0.45f, -s * 0.15f, s * 0.5f, -s * 0.55f, s * 0.2f), rgb(0xF4, 0xF4, 0xF0))
                        p.line(-s * 0.3f, -s * 0.1f, s * 0.2f, s * 0.1f, rgb(0xC8, 0xC8, 0xC4), 2f)
                        p.line(-s * 0.1f, -s * 0.4f, 0f, s * 0.3f, rgb(0xC8, 0xC8, 0xC4), 2f)
                    }
                    1 -> {
                        p.rotate(70f)
                        p.roundRect(-s * 0.3f, -s * 0.55f, s * 0.6f, s * 1.1f, 6f, rgb(0xE8, 0x3A, 0x3A))
                        p.rect(-s * 0.3f, -s * 0.15f, s * 0.6f, s * 0.3f, WHITE)
                        p.roundRect(-s * 0.26f, -s * 0.6f, s * 0.52f, s * 0.1f, 3f, rgb(0xC8, 0xC8, 0xCC))
                    }
                    else -> {
                        // A banana peel.
                        val y = rgb(0xFF, 0xD8, 0x3A)
                        p.fill(shape { moveTo(0f, -s * 0.2f); quadTo(-s * 0.7f, -s * 0.1f, -s * 0.8f, s * 0.4f); quadTo(-s * 0.4f, s * 0.1f, 0f, s * 0.1f); close() }, y)
                        p.fill(shape { moveTo(0f, -s * 0.2f); quadTo(s * 0.7f, -s * 0.1f, s * 0.8f, s * 0.4f); quadTo(s * 0.4f, s * 0.1f, 0f, s * 0.1f); close() }, y)
                        p.fill(shape { moveTo(-s * 0.15f, -s * 0.2f); quadTo(0f, -s * 0.9f, s * 0.15f, -s * 0.2f); close() }, darker(y, 0.1f))
                        p.oval(0f, -s * 0.75f, 4f, 5f, rgb(0x6A, 0x4A, 0x2A))
                    }
                }
            }
            MessType.COBWEB -> {
                val c = alpha(rgb(0x70, 0x70, 0x78), a * 0.85f)
                for (i in 0 until 8) {
                    val ang = i * 45f + m.rot * 0.1f
                    p.line(0f, 0f, cosd(ang) * s, sind(ang) * s, c, 2f)
                }
                for (ring in 1..3) {
                    val r = s * ring / 3.2f
                    val sh = Shape()
                    for (i in 0..8) {
                        val ang = i * 45f + m.rot * 0.1f
                        val px = cosd(ang) * r
                        val py = sind(ang) * r
                        if (i == 0) sh.moveTo(px, py) else {
                            val mid = ang - 22.5f
                            sh.quadTo(cosd(mid) * r * 0.82f, sind(mid) * r * 0.82f, px, py)
                        }
                    }
                    p.stroke(sh, c, 1.8f)
                }
                if (m.dirt > 0.3f) {
                    // The spider.
                    p.line(0f, -s, 0f, s * 0.35f, c, 1.5f)
                    p.oval(0f, s * 0.42f, 9f, 8f, alpha(rgb(0x2A, 0x22, 0x2E), a))
                    p.oval(-3f, s * 0.4f, 2f, 2f, alpha(WHITE, a))
                    p.oval(3f, s * 0.4f, 2f, 2f, alpha(WHITE, a))
                }
            }
        }
        p.restore()
    }

    // ------------------------------------------------------------ tools

    fun drawTool(p: Painter, tool: Tool, cx: Float, cy: Float, size: Float, active: Boolean = false) {
        val s = size
        p.save()
        p.translate(cx, cy)
        when (tool) {
            Tool.BROOM -> {
                p.rotate(-20f)
                p.line(0f, -s * 0.9f, 0f, s * 0.2f, rgb(0xA0, 0x6A, 0x3A), s * 0.1f)
                p.fill(poly(-s * 0.18f, s * 0.15f, s * 0.18f, s * 0.15f, s * 0.42f, s * 0.85f, -s * 0.42f, s * 0.85f), rgb(0xF2, 0xC8, 0x5A))
                p.rect(-s * 0.2f, s * 0.12f, s * 0.4f, s * 0.12f, rgb(0xE0, 0x5A, 0x4A))
                for (i in -2..2) p.line(i * s * 0.12f, s * 0.3f, i * s * 0.17f, s * 0.82f, rgb(0xD8, 0xA8, 0x3A), 2f)
            }
            Tool.SPONGE -> {
                p.rotate(-12f)
                p.roundRect(-s * 0.55f, -s * 0.2f, s * 1.1f, s * 0.5f, s * 0.1f, rgb(0xFF, 0xE0, 0x4A))
                p.roundRect(-s * 0.55f, -s * 0.38f, s * 1.1f, s * 0.22f, s * 0.08f, rgb(0x3E, 0xB8, 0x6A))
                for (i in 0 until 5) p.oval(-s * 0.35f + i * s * 0.18f, s * 0.04f + (i % 2) * s * 0.1f, s * 0.04f, s * 0.04f, rgb(0xE8, 0xC0, 0x30))
                p.oval(s * 0.5f, -s * 0.55f, s * 0.12f, s * 0.12f, alpha(rgb(0xBF, 0xEB, 0xFF), 0.9f))
                p.oval(s * 0.25f, -s * 0.7f, s * 0.08f, s * 0.08f, alpha(rgb(0xBF, 0xEB, 0xFF), 0.9f))
            }
            Tool.HAND -> {
                val glove = rgb(0xFF, 0x7A, 0xA8)
                p.roundRect(-s * 0.35f, -s * 0.1f, s * 0.7f, s * 0.7f, s * 0.2f, glove)
                for (i in 0 until 4) p.roundRect(-s * 0.35f + i * s * 0.18f, -s * 0.62f + (if (i == 0 || i == 3) s * 0.12f else 0f), s * 0.16f, s * 0.6f, s * 0.08f, glove)
                p.roundRect(s * 0.25f, -s * 0.05f, s * 0.4f, s * 0.18f, s * 0.09f, glove)
                p.rect(-s * 0.38f, s * 0.48f, s * 0.76f, s * 0.16f, darker(glove, 0.2f))
            }
            Tool.DUSTER -> {
                p.rotate(25f)
                p.line(0f, 0f, 0f, s * 0.9f, rgb(0x8A, 0x5A, 0x3A), s * 0.1f)
                val cols = intArrayOf(rgb(0xC5, 0x8B, 0xF2), rgb(0xFF, 0x9E, 0xC4), rgb(0x9E, 0xD8, 0xFF))
                for (i in -3..3) {
                    p.save()
                    p.rotate(i * 16f)
                    p.oval(0f, -s * 0.38f, s * 0.13f, s * 0.42f, cols[(i + 3) % 3])
                    p.restore()
                }
            }
        }
        p.restore()
        if (active) p.fill(sparkle(cx + size * 0.6f, cy - size * 0.6f, size * 0.18f), WHITE)
    }

    private fun cosd(deg: Float) = cos(Math.toRadians(deg.toDouble())).toFloat()
    private fun sind(deg: Float) = sin(Math.toRadians(deg.toDouble())).toFloat()
}
