package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.argb
import com.roccoquest.game.shadowText
import kotlin.math.sin

/** Things to grow in the garden. [kind] 0: fruit or veggie (picked into the basket), 1: flower, 2: tree (fruit grows back). */
enum class Crop(val label: String, val price: Int, val growSecs: Float, val color: Int, val kind: Int) {
    CARROT("CARROT", 5, 40f, 0xFFF08A2A.toInt(), 0),
    STRAWBERRY("STRAWBERRY", 8, 50f, 0xFFE8303A.toInt(), 0),
    TOMATO("TOMATO", 8, 50f, 0xFFE84A3A.toInt(), 0),
    CORN("CORN", 10, 60f, 0xFFF4D84A.toInt(), 0),
    PUMPKIN("PUMPKIN", 15, 90f, 0xFFF08A1A.toInt(), 0),
    WATERMELON("WATERMELON", 15, 90f, 0xFF3A9A3A.toInt(), 0),
    SUNFLOWER("SUNFLOWER", 5, 40f, 0xFFFFD21F.toInt(), 1),
    TULIP("TULIP", 5, 30f, 0xFFFF5A9A.toInt(), 1),
    ROSE("ROSE", 8, 40f, 0xFFD8203A.toInt(), 1),
    APPLE_TREE("APPLE TREE", 30, 120f, 0xFFE8303A.toInt(), 2),
}

/**
 * The garden behind the house: plant seeds in the dirt, water them, and pick what grows. Fruits and veggies go
 * in the basket and make a picnic for your Pokemon (XP!). Plants keep growing while you play levels.
 */
class Garden(private val g: TeamGame) {
    val kind = IntArray(PLOTS) { -1 }
    val growth = FloatArray(PLOTS)
    private val wetT = FloatArray(PLOTS)
    /** Picked fruits and veggies, by [Crop] ordinal. */
    val basket = IntArray(Crop.entries.size)
    /** The seed in your hand. */
    var seed = Crop.CARROT
    private var eatT = 0f

    fun load() {
        for (i in 0 until PLOTS) {
            kind[i] = g.slotLoad("plot$i") - 1
            if (kind[i] !in Crop.entries.indices) kind[i] = -1
            growth[i] = (g.slotLoad("grow$i") / 1000f).coerceIn(0f, 1f)
            wetT[i] = 0f
        }
        for (c in Crop.entries) basket[c.ordinal] = g.slotLoad("food${c.ordinal}").coerceAtLeast(0)
        seed = Crop.entries.getOrElse(g.slotLoad("seed")) { Crop.CARROT }
    }

    fun save() {
        for (i in 0 until PLOTS) { g.slotSave("plot$i", kind[i] + 1); g.slotSave("grow$i", (growth[i] * 1000).toInt()) }
        for (c in Crop.entries) g.slotSave("food${c.ordinal}", basket[c.ordinal])
        g.slotSave("seed", seed.ordinal)
    }

    /** Plants grow all the time (three times as fast for a while after watering). */
    fun grow(dt: Float) {
        for (i in 0 until PLOTS) {
            val k = kind[i]
            if (k < 0 || growth[i] >= 1f) continue
            val speed = if (wetT[i] > 0f) 3f else 1f
            if (wetT[i] > 0f) wetT[i] -= dt
            growth[i] = minOf(1f, growth[i] + dt * speed / Crop.entries[k].growSecs)
        }
        if (eatT > 0f) eatT -= dt
    }

    private fun plotAt(x: Float): Int = (0 until PLOTS).firstOrNull { x in (plotX(it) - 6f)..(plotX(it) + PLOT_W + 6f) } ?: -1

    /** What the action button does at [x]. */
    fun target(x: Float): String? {
        if (x < DOOR_X + 40f) return "INSIDE"
        if (x in SEEDS_X..(SEEDS_X + 70f)) return "SEEDS"
        if (x in PICNIC_X..(PICNIC_X + 60f)) return "PICNIC"
        val p = plotAt(x)
        if (p < 0) return null
        val k = kind[p]
        return when {
            k < 0 -> "PLANT"
            growth[p] >= 1f -> "PICK"
            wetT[p] <= 0f -> "WATER"
            else -> "CHECK"
        }
    }

    fun action(what: String, x: Float) {
        val p = plotAt(x)
        when {
            what == "SEEDS" -> {
                seed = Crop.entries[(seed.ordinal + 1) % Crop.entries.size]
                g.sfx(Sound.KICK)
                g.toastFrom3D("${seed.label} seeds (${seed.price} coins). Walk to the dirt and PLANT!")
            }
            what == "PICNIC" -> picnic()
            what == "PLANT" && p >= 0 -> plant(p)
            what == "WATER" && p >= 0 -> { wetT[p] = 30f; g.sfx(Sound.SPLASH); g.toastFrom3D("Splish splash! It grows faster now.") }
            what.startsWith("PICK") && p >= 0 -> pick(p)
            what == "CHECK" -> g.toastFrom3D("Growing... ${(growth[maxOf(0, p)] * 100).toInt()}%. Play a level and come back!")
        }
    }

    fun plant(p: Int) {
        if (kind[p] >= 0) return
        if (g.coins < seed.price) { g.sfx(Sound.BUMP); g.toastFrom3D("${seed.label} seeds cost ${seed.price} coins."); return }
        g.coins -= seed.price
        kind[p] = seed.ordinal
        growth[p] = 0f
        wetT[p] = 0f
        g.sfx(Sound.COIN)
        g.toastFrom3D("Planted a ${seed.label}! Water it to make it grow faster.")
        save()
    }

    fun pick(p: Int) {
        val k = kind[p]
        if (k < 0 || growth[p] < 1f) return
        val c = Crop.entries[k]
        when (c.kind) {
            1 -> { g.coins += 3; kind[p] = -1; g.toastFrom3D("A pretty ${c.label}! (+3 coins)") }
            2 -> { basket[c.ordinal] += 3; growth[p] = 0.6f; g.toastFrom3D("3 APPLES! The tree will grow more.") }
            else -> { basket[c.ordinal] += 2; kind[p] = -1; g.toastFrom3D("You picked 2 ${c.label}S! Have a PICNIC with your Pokemon.") }
        }
        g.sfx(Sound.ONEUP)
        save()
    }

    /** Your Pokemon eats everything in the basket: 2 XP each. */
    fun picnic() {
        val n = basket.sum()
        if (n == 0) { g.sfx(Sound.BUMP); g.toastFrom3D("The basket is empty. Grow fruits and veggies first!"); return }
        basket.fill(0)
        g.gainXp(n * 2)
        eatT = 2f
        g.sfx(Sound.POWERUP)
        g.toastFrom3D("Nom nom nom! ${g.mon.label} ate $n yummy things (+${n * 2} XP).")
        save()
    }

    // ------------------------------------------------------------------ Drawing

    fun draw(gfx: Gfx, ox: Float, viewW: Float, viewH: Float, t: Float) {
        // Sky, sun, clouds
        gfx.gradient(0f, 0f, viewW, Homes.FLOOR_Y, 0xFF4AA4F0.toInt(), 0xFFD8F0FF.toInt())
        gfx.oval(viewW - 60f, 16f, 34f, 34f, 0xFFFFE14D.toInt())
        for (c in 0 until 4) {
            val cx = ((c * 260f + t * 6f) % (viewW + 120f)) - 60f
            gfx.oval(cx, 28f + c * 9f, 46f, 14f, 0xEEFFFFFF.toInt()); gfx.oval(cx + 14, 22f + c * 9f, 26f, 14f, 0xEEFFFFFF.toInt())
        }
        // Hills far away, then the lawn
        gfx.oval(ox * 0.3f - 100f, 120f, 500f, 120f, 0xFF7AC86A.toInt())
        gfx.oval(ox * 0.3f + 350f, 110f, 600f, 140f, 0xFF6AB85A.toInt())
        gfx.rect(0f, Homes.FLOOR_Y - 20f, viewW, viewH, 0xFF5AB84A.toInt())
        gfx.rect(0f, Homes.FLOOR_Y + 30f, viewW, viewH, 0xFF4AA83A.toInt())
        // The white fence along the back
        var fx = ox
        while (fx < ox + WIDTH) {
            if (fx > -12f && fx < viewW) {
                gfx.rect(fx, Homes.FLOOR_Y - 46f, 8f, 30f, 0xFFF4F4F4.toInt())
                gfx.poly(floatArrayOf(fx, fx + 4, fx + 8), floatArrayOf(Homes.FLOOR_Y - 46f, Homes.FLOOR_Y - 51f, Homes.FLOOR_Y - 46f), 0xFFF4F4F4.toInt())
            }
            fx += 16f
        }
        gfx.rect(ox, Homes.FLOOR_Y - 40f, WIDTH, 3f, 0xFFE4E4E4.toInt())
        gfx.rect(ox, Homes.FLOOR_Y - 26f, WIDTH, 3f, 0xFFE4E4E4.toInt())
        // The back door of the house
        gfx.rect(ox + DOOR_X - 10f, Homes.FLOOR_Y - 90f, 60f, 90f, 0xFFC8A070.toInt())
        gfx.rect(ox + DOOR_X + 4f, Homes.FLOOR_Y - 52f, 22f, 52f, 0xFF7A4A20.toInt())
        gfx.oval(ox + DOOR_X + 20f, Homes.FLOOR_Y - 28f, 3f, 3f, 0xFFFFD21F.toInt())
        // The seed stand
        val sx = ox + SEEDS_X
        gfx.rect(sx, Homes.FLOOR_Y - 30f, 70f, 30f, 0xFF8A5A2A.toInt())
        gfx.rect(sx - 4f, Homes.FLOOR_Y - 58f, 78f, 8f, 0xFFE84A4A.toInt())
        for (k in 0 until 5) gfx.rect(sx - 4f + k * 16f, Homes.FLOOR_Y - 58f, 8f, 8f, 0xFFF4F4F4.toInt())
        gfx.rect(sx + 2f, Homes.FLOOR_Y - 50f, 3f, 20f, 0xFF6A4A20.toInt()); gfx.rect(sx + 65f, Homes.FLOOR_Y - 50f, 3f, 20f, 0xFF6A4A20.toInt())
        for (k in 0 until 4) {
            val c = Crop.entries[(seed.ordinal + k) % Crop.entries.size]
            gfx.rect(sx + 6f + k * 15f, Homes.FLOOR_Y - 28f, 12f, 16f, 0xFFF4E8C8.toInt())
            gfx.oval(sx + 8f + k * 15f, Homes.FLOOR_Y - 24f, 8f, 8f, c.color)
        }
        gfx.shadowText("SEEDS", sx + 35f, Homes.FLOOR_Y - 34f, 7f, 0xFFFFF2C8.toInt(), 1)
        gfx.text("in hand: ${seed.label}", sx + 35f, Homes.FLOOR_Y + 8f, 5.5f, 0xFF1E3A1E.toInt(), 1)
        // The plots
        for (i in 0 until PLOTS) {
            val px = ox + plotX(i)
            if (px < -60f || px > viewW + 10f) continue
            gfx.oval(px - 2f, Homes.FLOOR_Y - 6f, PLOT_W + 4f, 14f, 0xFF5A3A1A.toInt())
            gfx.oval(px + 2f, Homes.FLOOR_Y - 4f, PLOT_W - 4f, 9f, if (wetT[i] > 0f) 0xFF3A2410.toInt() else 0xFF7A5030.toInt())
            val k = kind[i]
            if (k >= 0) drawPlant(gfx, Crop.entries[k], px + PLOT_W / 2, Homes.FLOOR_Y - 1f, growth[i], t + i)
            if (wetT[i] > 0f && (t * 3 + i).toInt() % 4 == 0) gfx.oval(px + PLOT_W / 2 + 6f, Homes.FLOOR_Y - 10f, 3f, 4f, 0xCC6AC8FF.toInt())
        }
        // The picnic blanket and basket
        // The sign for the backyard
        val sx2 = ox + YARD_X - 24f
        gfx.rect(sx2 + 8, Homes.FLOOR_Y - 34f, 3f, 34f, 0xFF7A4A20.toInt())
        gfx.rect(sx2 - 14, Homes.FLOOR_Y - 48f, 48f, 16f, 0xFFF4E8C8.toInt())
        gfx.text("BACKYARD >", sx2 + 10, Homes.FLOOR_Y - 37f, 5.5f, 0xFF2A1808.toInt(), 1)
        val bx = ox + PICNIC_X
        gfx.oval(bx - 6f, Homes.FLOOR_Y + 2f, 72f, 18f, 0xFFE84A4A.toInt())
        for (k in 0 until 4) gfx.rect(bx + 4f + k * 14f, Homes.FLOOR_Y + 4f, 6f, 14f, 0x88FFFFFF.toInt())
        gfx.rect(bx + 16f, Homes.FLOOR_Y - 14f, 26f, 16f, 0xFFC8904A.toInt())
        gfx.rect(bx + 18f, Homes.FLOOR_Y - 24f, 2f, 12f, 0xFF8A5A2A.toInt()); gfx.rect(bx + 38f, Homes.FLOOR_Y - 24f, 2f, 12f, 0xFF8A5A2A.toInt())
        gfx.rect(bx + 18f, Homes.FLOOR_Y - 25f, 22f, 2f, 0xFF8A5A2A.toInt())
        var shown = 0
        for (c in Crop.entries) if (basket[c.ordinal] > 0 && shown < 4) { gfx.oval(bx + 18f + shown * 5f, Homes.FLOOR_Y - 18f, 6f, 6f, c.color); shown++ }
        gfx.shadowText("PICNIC (${basket.sum()})", bx + 29f, Homes.FLOOR_Y - 32f, 6.5f, 0xFFFFF2C8.toInt(), 1)
        if (eatT > 0f) gfx.shadowText("NOM NOM!", bx + 29f, Homes.FLOOR_Y - 50f - (2f - eatT) * 8f, 9f, 0xFFFFE14D.toInt(), 1)
    }

    companion object {
        const val PLOTS = 10
        /** The garden plus the big backyard after it (pools, trees and swings go from [YARD_X] on). */
        const val WIDTH = 2000f
        const val YARD_X = 900f
        const val DOOR_X = 10f
        const val SEEDS_X = 80f
        const val PICNIC_X = 810f
        const val PLOT_W = 44f
        fun plotX(i: Int) = 180f + i * 62f

        /** A plant in the ground at ([cx], [bottom]), from a tiny sprout (0) to ripe (1). */
        fun drawPlant(gfx: Gfx, c: Crop, cx: Float, bottom: Float, growth: Float, t: Float) {
            val green = 0xFF3AA83A.toInt()
            val dark = 0xFF2A7A2A.toInt()
            val sway = sin(t * 2f) * 1.5f
            if (growth < 0.25f) {
                // A little sprout
                gfx.rect(cx - 0.5f, bottom - 6f, 1.5f, 6f, green)
                gfx.oval(cx - 5f + sway, bottom - 9f, 5f, 3f, green); gfx.oval(cx + sway, bottom - 9f, 5f, 3f, green)
                return
            }
            val k = minOf(1f, growth)
            val ripe = growth >= 1f
            when (c) {
                Crop.APPLE_TREE -> {
                    val h = 30f + 40f * k
                    gfx.rect(cx - 3f, bottom - h, 6f, h, 0xFF7A4A20.toInt())
                    gfx.oval(cx - 18f - 8f * k + sway, bottom - h - 20f * k - 6f, 36f + 16f * k, 30f + 14f * k, green)
                    gfx.oval(cx - 10f + sway, bottom - h - 18f * k - 10f, 26f, 18f, 0xFF4AB84A.toInt())
                    if (ripe) for (a in 0 until 5) gfx.oval(cx - 16f + a * 7f + sway, bottom - h - 8f + (a % 2) * 8f, 6f, 6f, c.color)
                }
                Crop.SUNFLOWER, Crop.TULIP, Crop.ROSE -> {
                    val h = if (c == Crop.SUNFLOWER) 14f + 30f * k else 10f + 16f * k
                    gfx.rect(cx - 1f + sway * 0.5f, bottom - h, 2f, h, green)
                    gfx.oval(cx - 7f, bottom - h * 0.5f, 7f, 3f, green); gfx.oval(cx + 1f, bottom - h * 0.35f, 7f, 3f, green)
                    val top = bottom - h
                    val fx = cx + sway
                    if (!ripe) { gfx.oval(fx - 3f, top - 6f, 6f, 8f, dark); return }
                    when (c) {
                        Crop.SUNFLOWER -> {
                            for (a in 0 until 8) {
                                val ang = a * 0.785f + t * 0.2f
                                gfx.oval(fx - 4f + kotlin.math.cos(ang) * 8f, top - 4f + sin(ang) * 8f, 8f, 8f, c.color)
                            }
                            gfx.oval(fx - 6f, top - 6f, 12f, 12f, 0xFF7A4A20.toInt())
                        }
                        Crop.TULIP -> {
                            gfx.oval(fx - 5f, top - 9f, 10f, 11f, c.color)
                            gfx.poly(floatArrayOf(fx - 5f, fx - 2.5f, fx, fx + 2.5f, fx + 5f), floatArrayOf(top - 4f, top - 12f, top - 6f, top - 12f, top - 4f), c.color)
                        }
                        else -> {
                            gfx.oval(fx - 5f, top - 8f, 10f, 10f, c.color)
                            gfx.oval(fx - 3f, top - 6f, 6f, 6f, 0xFFA8102A.toInt())
                            gfx.oval(fx - 1.5f, top - 4.5f, 3f, 3f, c.color)
                        }
                    }
                }
                Crop.CORN -> {
                    val h = 12f + 30f * k
                    gfx.rect(cx - 1.5f, bottom - h, 3f, h, green)
                    for (a in 0 until 3) gfx.oval(cx - 10f + (a % 2) * 10f + sway, bottom - h * (0.3f + a * 0.22f), 10f, 3f, green)
                    if (ripe) {
                        gfx.oval(cx + 1f, bottom - h * 0.75f, 7f, 14f, c.color)
                        gfx.poly(floatArrayOf(cx, cx + 5f, cx + 8f), floatArrayOf(bottom - h * 0.75f + 14f, bottom - h * 0.75f - 2f, bottom - h * 0.75f + 14f), green)
                    }
                }
                Crop.PUMPKIN, Crop.WATERMELON -> {
                    // Vines on the ground, then a big round one.
                    gfx.rect(cx - 16f, bottom - 2f, 32f * k, 2f, green)
                    gfx.oval(cx - 14f + sway, bottom - 7f, 8f, 5f, green); gfx.oval(cx + 6f - sway, bottom - 6f, 8f, 5f, green)
                    val s = if (ripe) 1f else k * 0.6f
                    if (c == Crop.PUMPKIN) {
                        gfx.oval(cx - 11f * s, bottom - 16f * s, 22f * s, 16f * s, c.color)
                        gfx.oval(cx - 5f * s, bottom - 16f * s, 10f * s, 16f * s, 0xFFE87A10.toInt())
                        gfx.rect(cx - 1f, bottom - 19f * s, 2.5f, 4f * s, 0xFF5A7A2A.toInt())
                    } else {
                        gfx.oval(cx - 13f * s, bottom - 14f * s, 26f * s, 14f * s, c.color)
                        for (a in 0 until 3) gfx.rect(cx - 8f * s + a * 7f * s, bottom - 13f * s, 2f * s, 12f * s, 0xFF2A6A2A.toInt())
                    }
                }
                else -> {
                    // Leafy plants: carrot tops, strawberry and tomato bushes.
                    val h = 8f + 12f * k
                    for (a in -1..1) gfx.oval(cx - 3f + a * 5f + sway, bottom - h, 6f, h, if (a == 0) green else dark)
                    if (ripe) when (c) {
                        Crop.CARROT -> gfx.poly(floatArrayOf(cx - 4f, cx, cx + 4f), floatArrayOf(bottom - 3f, bottom + 5f, bottom - 3f), c.color)
                        else -> for (a in 0 until 3) gfx.oval(cx - 9f + a * 6f, bottom - h * 0.5f + (a % 2) * 3f, 6f, 6f, c.color)
                    }
                }
            }
        }
    }
}
