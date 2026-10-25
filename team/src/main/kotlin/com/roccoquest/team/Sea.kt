package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.Sprite
import com.roccoquest.game.argb
import com.roccoquest.game.rainbow
import com.roccoquest.game.shadowText
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

// ============================================================================ Metal Rocco

/** The Metal Cap: Rocco turns to shiny metal. Nothing hurts him, and he sinks and walks underwater. */
class MetalItem(x: Float, y: Float, popOut: Boolean = true) : Pickup(x, y, false) {
    init { if (!popOut) { vx = 0f; vy = 0f } }
    override fun collect(g: TeamGame) = g.startMetal()
    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox
        val shine = if ((t * 3).toInt() % 2 == 0) 0xFFFFFFFF.toInt() else 0xFFDDE4EE.toInt()
        // A shiny silver cap with a big "M".
        gfx.oval(px - 1, y + 1, 14f, 10f, 0xFF7A8494.toInt())
        gfx.oval(px, y + 1, 12f, 8f, 0xFFB8C2D0.toInt())
        gfx.rect(px - 2, y + 8, 16f, 3f, 0xFF8A94A4.toInt())
        gfx.text("M", px + 6, y + 8, 6f, 0xFF4A5464.toInt(), 1)
        gfx.oval(px + 2, y + 2, 3f, 2f, shine)
    }
}

object Metal {
    private val cache = HashMap<Sprite, Sprite>()

    /** A silvery copy of a sprite: every color turns to shiny grey. */
    fun of(s: Sprite): Sprite = cache.getOrPut(s) {
        Sprite(s.w, s.h, IntArray(s.pixels.size) { i ->
            val c = s.pixels[i]
            val a = (c ushr 24) and 255
            if (a == 0) c else {
                val lum = (((c shr 16) and 255) * 0.3f + ((c shr 8) and 255) * 0.59f + (c and 255) * 0.11f)
                val y = i / s.w
                val v = (lum * 0.7f + 70f + (if (y < s.h / 3) 25f else 0f)).toInt().coerceIn(40, 245)
                argb(a, (v shl 16) or (v shl 8) or minOf(255, v + 18))
            }
        })
    }
}

// ============================================================================ Ships

/** A ship's cannon. It turns toward you and fires cannonballs. Hit it enough and it breaks. */
class Cannon(x: Float, bottom: Float) : Foe(x, bottom - 14, 18f, 14f, 8) {
    override val stompable = false
    private var reload = 1.5f + Random.nextFloat() * 1.5f
    private var facing = -1
    private var recoil = 0f

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        val l = g.leader
        facing = if (l.cx > cx) 1 else -1
        if (recoil > 0) recoil -= dt
        reload -= dt
        if (reload <= 0f && abs(l.cx - cx) < 260f && abs(l.cy - cy) < 90f) {
            reload = 2.6f + Random.nextFloat()
            recoil = 0.25f
            g.spawn(CannonBall(if (facing > 0) x + w else x - 9, y + 2, facing))
            g.sfx(Sound.BOSS_HIT)
            g.puff(if (facing > 0) x + w + 2 else x - 2, y + 5)
        }
    }

    override fun touch(g: TeamGame) { g.hurt(this) }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val px = x + ox - facing * recoil * 6f
        // Wooden carriage with two wheels and a black barrel.
        gfx.rect(px + 1, y + 7, 16f, 5f, 0xFF7A4A20.toInt())
        gfx.oval(px + 1, y + 9, 6f, 6f, 0xFF3A2410.toInt())
        gfx.oval(px + 11, y + 9, 6f, 6f, 0xFF3A2410.toInt())
        val bx = if (facing > 0) px + 4 else px - 2
        gfx.rect(bx, y + 1, 16f, 7f, 0xFF2A2A30.toInt())
        gfx.oval(if (facing > 0) bx + 13 else bx - 2, y, 5f, 9f, 0xFF1A1A20.toInt())
        gfx.rect(bx + 3, y + 2, 8f, 1.5f, 0x55FFFFFF)
        drawFlash(gfx, ox)
    }
}

/** A cannonball flying straight across. A shield knocks it away. */
class CannonBall(x: Float, y: Float, private val dir: Int) : Foe(x, y, 9f, 9f, 1), Projectile {
    override val stompable = false
    override val showBar = false
    private var life = 4f
    init { active = true }

    override fun update(g: TeamGame, dt: Float) {
        if (updateDying(g, dt)) return
        life -= dt
        x += dir * 150f * dt
        if (life <= 0 || g.level.solidAt(cx + dir * 5f, cy)) { removed = true; g.puff(cx, cy) }
    }

    override fun hit(g: TeamGame, dmg: Int): Boolean { removed = true; g.puff(cx, cy); return true }

    override fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        gfx.oval(x + ox, y, w, h, 0xFF1A1A20.toInt())
        gfx.oval(x + ox + 2, y + 2, 3f, 2f, 0x88FFFFFF.toInt())
    }
}

// ============================================================================ Tiles and skies

object SeaArt {
    /** One plank tile as a ready-made picture (drawing it shape by shape made ship levels slow). */
    private fun plankSprite(deck: Boolean, odd: Boolean): Sprite {
        val line = 0xFF5A3410.toInt()
        val px = IntArray(16 * 16) { if (deck) 0xFFA8703A.toInt() else 0xFF7A4A20.toInt() }
        for (x in 0 until 16) { px[7 * 16 + x] = line; px[15 * 16 + x] = line }
        val seam = if (odd) 11 else 4
        for (y in 0 until 7) px[y * 16 + seam] = line
        if (deck) for (y in 0 until 2) for (x in 0 until 16) px[y * 16 + x] = 0xFFC8904A.toInt()
        px[10 * 16 + 2] = 0xFF3A2410.toInt()
        return Sprite(16, 16, px)
    }
    private val planks = Array(4) { plankSprite(it >= 2, it % 2 == 1) }

    /** Ship deck and hull planks. */
    fun plank(gfx: Gfx, x: Float, y: Float, deck: Boolean, tx: Int) {
        gfx.sprite(planks[(if (deck) 2 else 0) + (tx and 1)], x, y)
    }

    /** The mast: a tall pole. The top piece has a pirate flag, the next ones carry a big sail. */
    fun mast(gfx: Gfx, x: Float, y: Float, top: Boolean, sailRow: Boolean, t: Float) {
        gfx.rect(x + 6, y, 4f, 16f, 0xFF6A3A1A.toInt())
        if (top) {
            val wave = sin(t * 5) * 1.5f
            gfx.poly(floatArrayOf(x + 10, x + 26, x + 24 + wave, x + 10), floatArrayOf(y, y + 1 + wave, y + 9, y + 10), 0xFF1A1A1A.toInt())
            gfx.oval(x + 14, y + 2, 5f, 5f, 0xFFFFFFFF.toInt())
            gfx.rect(x + 15, y + 6, 3f, 1.5f, 0xFFFFFFFF.toInt())
        }
        if (sailRow) {
            val puff = sin(t * 2) * 2f
            gfx.poly(
                floatArrayOf(x - 14, x + 30, x + 32 + puff, x - 16 + puff),
                floatArrayOf(y + 2, y + 2, y + 40, y + 40), 0xFFF4EED8.toInt(),
            )
            gfx.rect(x - 15, y + 1, 46f, 2f, 0xFF6A3A1A.toInt())
            gfx.rect(x + 6, y, 4f, 44f, 0xFF6A3A1A.toInt())
        }
    }

    /** Rainbow Road: glowing stripes that shimmer. */
    private const val RAINBOW_FRAMES = 48

    /** Ready-made rainbow tiles, one per shade, so a long Rainbow Road is quick to draw. */
    private val rainbowTiles = Array(RAINBOW_FRAMES) { k ->
        val px = IntArray(16 * 16)
        for (y in 0 until 16) {
            val c = rainbow(k * 4f / RAINBOW_FRAMES, (y / 4) / 4f)
            val (r, g, b) = Triple((c shr 16) and 255, (c shr 8) and 255, c and 255)
            // The top row is lighter: the shine along the edge.
            fun lit(v: Int) = if (y == 0) v + (255 - v) * 6 / 10 else v
            for (x in 0 until 16) px[y * 16 + x] = argb(if (y == 0) 245 else 235, (lit(r) shl 16) or (lit(g) shl 8) or lit(b))
        }
        Sprite(16, 16, px)
    }

    fun rainbowTile(gfx: Gfx, x: Float, y: Float, t: Float, tx: Int) {
        val u = (((t * 0.6f + tx * 0.05f) / 4f) % 1f + 1f) % 1f
        gfx.sprite(rainbowTiles[(u * RAINBOW_FRAMES).toInt() % RAINBOW_FRAMES], x, y)
        if (((t * 6).toInt() + tx) % 9 == 0) gfx.oval(x + 6, y + 3, 4f, 4f, 0xDDFFFFFF.toInt())
    }

    /** A sunny sky over the sea, with islands far away and waves along the bottom. */
    fun seaSky(gfx: Gfx, viewW: Float, viewH: Float, cam: Float, t: Float) {
        gfx.gradient(0f, 0f, viewW, viewH * 0.7f, 0xFF4AA4F0.toInt(), 0xFFC8ECFF.toInt())
        gfx.oval(viewW * 0.75f - cam * 0.02f, 30f, 40f, 40f, 0xFFFFE87A.toInt())
        var x = -((cam * 0.15f) % 300f)
        while (x < viewW + 300f) {
            gfx.oval(x + 40, viewH * 0.6f, 120f, 40f, 0xFF5AA85A.toInt())
            gfx.rect(x + 95, viewH * 0.6f - 18, 3f, 20f, 0xFF7A4A20.toInt())
            gfx.oval(x + 86, viewH * 0.6f - 24, 20f, 8f, 0xFF3C9A3A.toInt())
            x += 300f
        }
        gfx.gradient(0f, viewH * 0.68f, viewW, viewH * 0.32f, 0xFF3A8AD8.toInt(), 0xFF1A4A8A.toInt())
        var wx = -((cam * 0.5f + t * 20f) % 40f)
        while (wx < viewW) {
            gfx.oval(wx, viewH * 0.68f - 3 + sin(t * 2 + wx * 0.05f) * 2f, 30f, 8f, 0xFF4A9AE0.toInt())
            wx += 40f
        }
        // The sea down below the ships.
        gfx.rect(0f, viewH - 22f, viewW, 22f, 0xFF1E5AA8.toInt())
        wx = -((cam + t * 30f) % 24f)
        while (wx < viewW) { gfx.oval(wx, viewH - 26f + sin(t * 3 + wx) * 1.5f, 18f, 8f, 0xFFDDEEFF.toInt()); wx += 24f }
    }

    /** Space, for Rainbow Road: stars, a big planet and colorful clouds. */
    fun space(gfx: Gfx, viewW: Float, viewH: Float, cam: Float, t: Float) {
        gfx.gradient(0f, 0f, viewW, viewH, 0xFF04020E.toInt(), 0xFF1A0E3A.toInt())
        gfx.oval(viewW * 0.1f - cam * 0.03f, 20f, 260f, 120f, 0x332A3AC8)
        gfx.oval(viewW * 0.55f - cam * 0.04f, 90f, 300f, 110f, 0x33C82A9A)
        for (i in 0 until 40) {
            val sx = ((i * 97f - cam * (0.05f + (i % 3) * 0.03f)) % viewW + viewW) % viewW
            val tw = if (((t * 3 + i) % 4) < 2) 0xFFFFFFFF.toInt() else 0x88FFFFFF.toInt()
            gfx.rect(sx, (i * 53f) % (viewH - 20f), 1.5f, 1.5f, tw)
        }
        val px = viewW * 0.8f - cam * 0.02f
        gfx.oval(px - 30, 30f, 60f, 60f, 0xFFE8A84A.toInt())
        gfx.oval(px - 44, 56f, 88f, 10f, 0x88FFD8A0.toInt())
        // Shooting star
        val k = (t * 0.4f) % 1f
        gfx.rect(viewW * (1 - k), 40f + k * 60f, 14f, 1.5f, 0xCCFFFFFF.toInt())
    }
}

// ============================================================================ World 5: the Sea World

/**
 * World 5 (levels 85-105): water levels, pirate ships with cannons, and Rainbow Road.
 * Built with fixed seeds, like Worlds 3 and 4.
 */
object World5 {
    private class Plan(val name: String, val kind: Char, val tip: String, val boss: String? = null)
    // kind: 'w' water, 's' ship, 'r' rainbow road

    private val plans = listOf(
        Plan("SHIPWRECK BAY", 'w', "WORLD 5: the Sea World! Find the METAL CAP and walk on the sea floor!"),
        Plan("PIRATE SHIP", 's', "All aboard! Jump from ship to ship. Watch out for CANNONS!"),
        Plan("CORAL CASTLE", 'w', "Swim through the coral castle."),
        Plan("CANNON COVE", 's', "So many cannons... use a SHIELD to knock cannonballs away!"),
        Plan("KELP FOREST", 'w', "Tall kelp and lots of fish."),
        Plan("STORMY SEAS", 's', "A storm! Ships bobbing on the waves."),
        Plan("SKY SHIP", 's', "KRAG JR. is attacking the ship!", boss = "J"),
        Plan("SUNKEN TREASURE", 'w', "Treasure on the sea floor!"),
        Plan("GHOST SHIP", 's', "A spooky pirate ship full of ghosts..."),
        Plan("DEEP BLUE", 'w', "The deepest water yet."),
        Plan("PIRATE FLEET", 's', "A whole fleet of pirate ships!"),
        Plan("BUBBLE REEF", 'w', "Bubble vents everywhere."),
        Plan("STAR BRIDGE", 'r', "A shining bridge of rainbows over the stars!"),
        Plan("KRAG'S FLAGSHIP", 's', "KING KRAG's own pirate ship!", boss = "K"),
        Plan("OCEAN TRENCH", 'w', "Way down in the ocean trench."),
        Plan("TREASURE ISLAND", 's', "Sail to Treasure Island!"),
        Plan("MERMAID LAGOON", 'w', "A sparkly lagoon."),
        Plan("THE ARMADA", 's', "The biggest pirate fleet ever."),
        Plan("STARLIGHT SEA", 'w', "Swimming under the stars."),
        Plan("RAINBOW ROAD", 'r', "RAINBOW ROAD! Don't fall off into space!"),
        Plan("RAINBOW SHOWDOWN", 'r', "The final battle on Rainbow Road: Wonder Krag Jr., Wonder King Krag and the Ender Dragon!", boss = "WQZ"),
    )

    val all: List<LevelDef> by lazy {
        plans.mapIndexed { i, p ->
            val seed = 5000 + i * 31
            when {
                p.boss == null && p.kind == 'w' -> water(p, seed)
                p.boss == null && p.kind == 's' -> ships(p, seed)
                p.boss == null -> rainbowRoad(p, seed)
                p.boss == "J" -> shipKragJr(p)
                p.boss == "K" -> flagship(p)
                else -> rainbowShowdown(p)
            }
        }
    }

    private val ores = listOf(arrayOf("oDo", "sss"), arrayOf("eRe", "sss"), arrayOf("DxD", "sss"), arrayOf("iEi", "sss"))

    /** Underwater: swim from bubble vent to bubble vent, between coral pillars, to the shore. */
    private fun water(p: Plan, seed: Int): LevelDef {
        val r = Random(seed)
        val w = 180 + r.nextInt(30)
        val shore = w - 22
        val b = Builder(w).ground(0, w - 1).at(3, 12, '@')
        b.rock(8, 12, *ores[r.nextInt(ores.size)])
        if (r.nextInt(2) == 0 || p.name == "SHIPWRECK BAY") b.at(13, 8, '$')
        var x = 16
        var mid = false
        while (x < shore - 8) {
            b.at(x, 12, '~')
            for (k in 0 until 2 + r.nextInt(2)) b.at(x + 3 + k * 4, 4 + r.nextInt(8), 'f')
            when (r.nextInt(4)) {
                0 -> b.fill(x + 8, x + 9, 0, 3 + r.nextInt(3), 'X')
                1 -> b.fill(x + 8, x + 9, 9 + r.nextInt(2), 12, 's').row(x + 8, 9, "DD".take(1 + r.nextInt(2)))
                2 -> b.row(x + 6, 5 + r.nextInt(4), "cccc")
                else -> b.rock(x + 7, 12, *ores[r.nextInt(ores.size)])
            }
            if (!mid && x > shore / 2) { b.at(x + 2, 12, '|'); mid = true }
            x += 14 + r.nextInt(5)
        }
        b.at(shore, 0, 'S').fill(shore, w - 1, 11, 12, '#')
        b.at(shore + 3, 10, 'r').at(w - 10, 10, 'F')
        return b.build(p.name, Place.WATER, p.tip)
    }

    /** One pirate ship: a deck to walk on, a hull below, masts with sails, and a crow's nest. */
    private fun ship(b: Builder, x: Int, len: Int, deck: Int, r: Random, foes: String, ghosts: Boolean) {
        for (row in deck..14) {
            val inset = maxOf(0, row - (deck + 2))
            b.fill(x + inset, x + len - 1 - inset, row, row, 'I')
        }
        val masts = if (len >= 30) listOf(x + len / 3, x + 2 * len / 3) else listOf(x + len / 2)
        for (m in masts) {
            b.fill(m, m, deck - 8, deck - 1, ':')
            if (r.nextBoolean()) b.row(m - 1, deck - 5, "III").at(m + 1, deck - 6, 'c')
        }
        b.at(x + 2, deck - 1, 'N')
        if (len >= 28) b.at(x + len - 3, deck - 1, 'N')
        repeat(1 + len / 14) { k -> b.at(x + 6 + k * 7, deck - 1, foes[r.nextInt(foes.length)]) }
        if (ghosts) b.at(x + len / 2, deck - 5, 'q')
        if (r.nextInt(2) == 0) b.row(x + len / 2 - 4, deck - 4, "?B?")
        b.rock(x + len - 9, deck - 1, *ores[r.nextInt(ores.size)])
    }

    /** Ship to ship to ship, from one dock to another. */
    private fun ships(p: Plan, seed: Int): LevelDef {
        val r = Random(seed)
        val w = 200 + r.nextInt(30)
        val b = Builder(w)
        // The dock, with steps up to the first ship.
        b.ground(0, 12).at(3, 12, '@').fill(10, 10, 12, 12, 'X').fill(11, 12, 11, 12, 'X')
        val foes = if (p.name == "GHOST SHIP") "bbqA" else "gkAHbj"
        var x = 13
        var n = 0
        while (x < w - 40) {
            val len = 24 + r.nextInt(14)
            val deck = 10 - r.nextInt(2)
            ship(b, x, len, deck, r, foes, p.name == "GHOST SHIP")
            if (n == 0) b.at(x + 4, deck - 1, 'r')
            if (n == 1 || n == 3) b.at(x + 4, deck - 1, '|')
            if (n == 1) b.at(x + len / 2, deck - 3, '$')
            x += len + 2 + r.nextInt(2)
            n++
        }
        // The far dock with the flag.
        b.fill(x, x, 11, 12, 'X').ground(x, w - 1)
        b.at(w - 10, 12, 'F')
        return b.build(p.name, Place.SHIP, p.tip)
    }

    /** Krag Jr. swoops down over one long ship. */
    private fun shipKragJr(p: Plan): LevelDef {
        val b = Builder(170)
        b.ground(0, 12).at(3, 12, '@').fill(10, 10, 12, 12, 'X').fill(11, 12, 11, 12, 'X')
        ship(b, 13, 60, 10, Random(77), "gkA", false)
        b.at(18, 9, 'r').at(30, 9, '|')
        for (row in 10..14) b.fill(75, 169, row, row, 'I')
        b.fill(95, 95, 2, 9, ':').fill(140, 140, 2, 9, ':')
        b.row(100, 7, "IIII").row(126, 7, "IIII").row(113, 4, "IIII").row(115, 3, "h")
        b.at(118, 6, 'J')
        b.at(155, 9, 'F')
        return b.build(p.name, Place.SHIP, p.tip)
    }

    /** King Krag on the deck of his own flagship. */
    private fun flagship(p: Plan): LevelDef {
        val b = Builder(160)
        for (row in 11..14) b.fill(0, 159, row, row, 'I')
        b.at(3, 10, '@').at(8, 10, 'N').row(12, 7, "?h?").at(20, 10, 'A').at(26, 10, 'k')
        b.fill(30, 30, 2, 10, ':').at(36, 10, 'N').rock(40, 10, "oDo", "sss").at(48, 10, 'r').at(52, 10, '|')
        b.at(58, 10, 'j').fill(64, 64, 2, 10, ':').at(70, 10, 'N').at(80, 10, '|')
        b.row(104, 6, "h").row(122, 6, "h")
        b.fill(133, 133, 2, 10, 'X')
        b.at(122, 10, 'K')
        return b.build(p.name, Place.SHIP, p.tip)
    }

    /** Rainbow Road: a glowing path floating in space. Gaps, ups and downs, and star coins. */
    private fun rainbowRoad(p: Plan, seed: Int): LevelDef {
        val r = Random(seed)
        val w = 210 + r.nextInt(20)
        val b = Builder(w)
        b.fill(0, 14, 12, 12, '%').at(3, 11, '@')
        b.rock(9, 11, *ores[r.nextInt(ores.size)])
        var x = 15
        var row = 12
        var cp = 0
        var frog = false
        while (x < w - 24) {
            val gapW = 2 + r.nextInt(2)
            x += gapW
            row = (row + listOf(-1, 0, 1)[r.nextInt(3)]).coerceIn(10, 12)
            val len = 8 + r.nextInt(8)
            b.fill(x, x + len - 1, row, row, '%')
            b.row(x + 1, row - 3, "c".repeat(minOf(5, len - 2)))
            if (r.nextInt(3) == 0) b.at(x + len / 2, row - 1, "qHgq"[r.nextInt(4)])
            if (r.nextInt(4) == 0) b.at(x + 3, 3, 'l')
            if (!frog && x > 40) { b.at(x + 1, row - 1, 'r'); frog = true }
            if (cp < 2 && x > w * (cp + 1) / 3) { b.at(x + 2, row - 1, '|'); cp++ }
            if (r.nextInt(5) == 0) b.row(x + len / 2 - 1, row - 4, "?m?")
            x += len
        }
        b.fill(x + 2, w - 1, 12, 12, '%')
        b.at(w - 9, 11, 'F')
        return b.build(p.name, Place.RAINBOW, p.tip)
    }

    /** The very end: three bosses one after another, on a long rainbow. */
    private fun rainbowShowdown(p: Plan): LevelDef {
        val gap = 100
        val bosses = "WQZ"
        val w = 70 + bosses.length * gap + 20
        val b = Builder(w)
        b.fill(0, w - 1, 13, 13, '%')
        b.at(3, 12, '@').rock(7, 12, "DxD", "sss").row(13, 9, "?h?").at(20, 12, 'r').at(30, 12, '|')
        b.rock(40, 12, "oeo", "sss")
        bosses.forEachIndexed { k, c ->
            val bx = 72 + k * gap
            b.row(bx - 14, 9, "h").row(bx + 8, 9, "h")
            when (c) {
                'Z' -> {
                    b.fill(bx - 14, bx - 13, 10, 12, '%').at(bx - 14, 9, 'C')
                    b.fill(bx + 14, bx + 15, 10, 12, '%').at(bx + 14, 9, 'C')
                    b.at(bx, 4, 'Z')
                }
                'W', 'J' -> { b.row(bx - 8, 8, "%%%%").row(bx + 6, 8, "%%%%").row(bx - 2, 5, "%%%%"); b.at(bx, 6, c) }
                else -> b.at(bx, 12, c)
            }
            if (k < bosses.length - 1) b.at(bx + 32, 12, '|').rock(bx + 36, 12, "aaD", "sss")
        }
        b.fill(w - 9, w - 9, 2, 12, 'X')
        return b.build(p.name, Place.RAINBOW, p.tip)
    }
}
