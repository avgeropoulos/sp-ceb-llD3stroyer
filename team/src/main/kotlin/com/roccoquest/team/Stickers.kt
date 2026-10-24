package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sprite
import com.roccoquest.game.Sprites
import com.roccoquest.game.argb

/** Draws everything scaled by [s] and moved to ([ox], [oy]), so any picture can fit on a sticker. */
class ScaledGfx(private val inner: Gfx, private val s: Float, private val ox: Float, private val oy: Float) : Gfx {
    private fun x(v: Float) = ox + v * s
    private fun y(v: Float) = oy + v * s

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) = inner.rect(x(x), y(y), w * s, h * s, color)
    override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) = inner.oval(x(x), y(y), w * s, h * s, color)
    override fun poly(xs: FloatArray, ys: FloatArray, color: Int) =
        inner.poly(FloatArray(xs.size) { x(xs[it]) }, FloatArray(ys.size) { y(ys[it]) }, color)
    override fun sprite(s: Sprite, x: Float, y: Float, flipX: Boolean, flipY: Boolean, w: Float, h: Float) =
        inner.sprite(s, x(x), y(y), flipX, flipY, w * this.s, h * this.s)
    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) =
        inner.text(s, x(x), y(y), size * this.s, color, align)
}

enum class Rarity(val label: String, val color: Int) {
    COMMON("COMMON", 0xFF5AA8E8.toInt()),
    RARE("RARE", 0xFFC0C8D8.toInt()),
    SHINY("SHINY!", 0xFFFFD21F.toInt()),
}

/**
 * A collectable sticker. [w] x [h] is the natural size of its picture; [draw] paints it
 * with its top-left corner at (0, 0).
 */
class Sticker(val name: String, val rarity: Rarity, val w: Float, val h: Float, val draw: (TeamGame, Gfx) -> Unit) {
    /** Which style of card this is: 0 normal, then silver, gold, rainbow and midnight. */
    var style = 0
}

/** Every sticker comes in five styles, so there are 5 x 60 = 300 to collect. */
enum class StickerStyle(val prefix: String, val card: Int) {
    NORMAL("", 0),
    SILVER("SILVER ", 0xFFC8D0DC.toInt()),
    GOLD("GOLD ", 0xFFFFC83A.toInt()),
    RAINBOW("RAINBOW ", 0),
    MIDNIGHT("MIDNIGHT ", 0xFF1A1A4A.toInt()),
}

object Stickers {
    private fun foe(make: () -> Foe, w: Float, h: Float, setup: (Foe) -> Unit = {}): (TeamGame, Gfx) -> Unit = { g, gfx ->
        val f = make()
        f.x = 0f
        f.y = 0f
        setup(f)
        f.draw(g, gfx, 0f)
    }

    private fun mon(m: Mon): Sticker =
        Sticker(m.label, Rarity.RARE, 32f, 34f) { g, gfx -> TeamArt.mon(gfx, m, 16f, 32f, g.time, 1, false) }

    /** The 60 pictures. The first 40 keep their old order so old saves still have the same stickers. */
    private val base: List<Sticker> = listOf(
        // Heroes and friends
        Sticker("ROCCO", Rarity.COMMON, 16f, 24f) { _, gfx -> gfx.sprite(TeamArt.rocco(Power.NONE, 2), 0f, 0f) },
        Sticker("BLOCKY", Rarity.COMMON, 26f, 26f) { _, gfx ->
            gfx.sprite(TeamArt.blocky(Power.NONE, false), 2f, 2f)
            TeamArt.sword(gfx, 15f, 15f, -1.2f, Sword.DIAMOND)
        },
    ) + Mon.entries.take(9).map(::mon) + listOf(
        Sticker("RING-DING FROG", Rarity.RARE, 30f, 58f) { g, gfx -> TeamArt.frog(gfx, 4f, 32f, g.time) },
        Sticker("DENNIS", Rarity.COMMON, 26f, 30f) { g, gfx -> Dennis(1f, 28f).draw(g, gfx, 0f) },
        Sticker("VILLAGER", Rarity.COMMON, 14f, 24f) { g, gfx -> Villager(-1f, 24f, 0).draw(g, gfx, 0f) },
        Sticker("POSEIDON", Rarity.SHINY, 80f, 170f) { g, gfx -> TeamArt.poseidon(gfx, 30f, 168f, g.time) },
        // Bad guys
        Sticker("GRUMBLER", Rarity.COMMON, 16f, 16f) { _, gfx -> gfx.sprite(Sprites.grumbler, 0f, 0f) },
        Sticker("SHELLBACK", Rarity.COMMON, 16f, 24f) { _, gfx -> gfx.sprite(Sprites.shellback1, 0f, 0f) },
        Sticker("BONEHEAD", Rarity.COMMON, 16f, 22f, foe({ Bonehead(0f, 22f) }, 16f, 22f) { it.x = 1f; it.y = 1f }),
        Sticker("FLAPPER", Rarity.COMMON, 26f, 16f, foe({ Flapper(0f, 12f) }, 26f, 16f) { it.x = 7f; it.y = 4f; (it as Flapper).wakeForSticker() }),
        Sticker("BOMB-BOMB", Rarity.COMMON, 20f, 20f, foe({ BombBomb(0f, 20f) }, 20f, 20f) { it.x = 4f; it.y = 6f }),
        Sticker("FISH", Rarity.COMMON, 20f, 12f, foe({ Fish(0f, 12f) }, 20f, 12f) { it.x = 4f; it.y = 1f }),
        Sticker("ENDERMAN", Rarity.RARE, 22f, 48f, foe({ Enderman(0f, 36f) }, 22f, 48f) { it.x = 5f; it.y = 13f }),
        // Bosses (all shiny!)
        Sticker("KRAG JR.", Rarity.SHINY, 32f, 46f, foe({ KragJr(0f, 34f) }, 32f, 46f) { it.x = 1f; it.y = 10f }),
        Sticker("WONDER KRAG JR.", Rarity.SHINY, 32f, 46f, foe({ KragJr(0f, 34f, wonderStyle = true) }, 32f, 46f) { it.x = 1f; it.y = 10f }),
        Sticker("KING KRAG", Rarity.SHINY, 50f, 52f, foe({ KingKrag(0f, 40f) }, 50f, 52f) { it.x = 8f; it.y = 11f }),
        Sticker("WONDER KING KRAG", Rarity.SHINY, 64f, 68f, foe({ KingKrag(0f, 54f, wonderStyle = true) }, 64f, 68f) { it.x = 9f; it.y = 14f }),
        Sticker("DRY BONES KRAG", Rarity.SHINY, 64f, 68f, foe({ KingKrag(0f, 54f, dry = true) }, 64f, 68f) { it.x = 9f; it.y = 14f }),
        Sticker("THE WARDEN", Rarity.SHINY, 44f, 56f, foe({ Warden(0f, 46f) }, 44f, 56f) { it.x = 6f; it.y = 9f }),
        Sticker("ENDER DRAGON", Rarity.SHINY, 96f, 56f, foe({ EnderDragon(0f, 0f) }, 96f, 56f) { it.x = 16f; it.y = 22f }),
        // Things
        Sticker("SUPER STAR", Rarity.RARE, 16f, 16f) { _, gfx -> gfx.sprite(Sprites.star1, 0f, 0f) },
        Sticker("FIRE FLOWER", Rarity.COMMON, 16f, 16f) { _, gfx -> gfx.sprite(TeamArt.flowerSprite(Power.FIRE), 0f, 0f) },
        Sticker("ICE FLOWER", Rarity.COMMON, 16f, 16f) { _, gfx -> gfx.sprite(TeamArt.flowerSprite(Power.ICE), 0f, 0f) },
        Sticker("BLUE SHELL", Rarity.RARE, 16f, 16f) { _, gfx -> gfx.sprite(Sprites.blueShell, 0f, 0f) },
        Sticker("MINI MUSHROOM", Rarity.COMMON, 16f, 16f) { _, gfx -> gfx.sprite(Sprites.miniMushroom, 0f, 0f) },
        Sticker("DIAMOND", Rarity.RARE, 16f, 16f) { _, gfx -> TeamArt.oreIcon(gfx, Ore.DIAMOND, 0f, 0f, 16f) },
        Sticker("GOLDEN APPLE", Rarity.RARE, 16f, 18f) { _, gfx ->
            gfx.oval(0f, 3f, 16f, 15f, 0xFFFFD21F.toInt())
            gfx.oval(3f, 6f, 4f, 4f, 0xAAFFFFFF.toInt())
            gfx.rect(7f, 0f, 2f, 5f, 0xFF7A4A20.toInt())
            gfx.oval(9f, 0f, 6f, 3f, 0xFF2EB84A.toInt())
        },
        Sticker("TNT", Rarity.COMMON, 16f, 16f) { _, gfx ->
            gfx.rect(0f, 0f, 16f, 16f, 0xFFE53A1E.toInt())
            gfx.rect(0f, 5f, 16f, 6f, 0xFFFFFFFF.toInt())
            gfx.text("TNT", 8f, 10f, 5f, 0xFF101010.toInt(), 1)
        },
        Sticker("DIAMOND SWORD", Rarity.RARE, 22f, 22f) { _, gfx -> TeamArt.sword(gfx, 3f, 19f, -0.8f, Sword.DIAMOND) },
        Sticker("HEART", Rarity.COMMON, 16f, 16f) { _, gfx -> TeamArt.heart(gfx, 1f, 1f, 14f, 0xFFE52521.toInt()) },
        Sticker("HOUSE", Rarity.COMMON, 84f, 80f) { g, gfx -> TeamArt.house(gfx, 6f, 78f, g.time) },
        // New ones
        mon(Mon.LUGIA).let { Sticker(it.name, Rarity.SHINY, it.w, it.h, it.draw) },
        Sticker("METAL ROCCO", Rarity.RARE, 16f, 24f) { _, gfx -> gfx.sprite(Metal.of(TeamArt.rocco(Power.NONE, 2)), 0f, 0f) },
        Sticker("TANOOKI ROCCO", Rarity.RARE, 26f, 26f) { g, gfx ->
            gfx.sprite(TeamArt.rocco(Power.NONE, 2), 5f, 2f)
            TeamArt.tanooki(gfx, 13f, 2f, 26f, 1, g.time, false, false)
        },
        Sticker("WING CAP ROCCO", Rarity.RARE, 26f, 30f) { g, gfx ->
            gfx.sprite(TeamArt.rocco(Power.NONE, 2), 5f, 6f)
            TeamArt.wingCap(gfx, 13f, 6f, g.time, false)
        },
        Sticker("SUPER LEAF", Rarity.COMMON, 16f, 16f) { g, gfx -> TeamArt.leaf(gfx, 0f, 0f, g.time) },
        Sticker("WING CAP", Rarity.RARE, 16f, 16f) { g, gfx -> TeamArt.wingCapItem(gfx, 0f, 0f, g.time) },
        Sticker("METAL CAP", Rarity.RARE, 18f, 14f) { g, gfx -> MetalItem(3f, 1f, popOut = false).draw(g, gfx, 0f) },
        Sticker("BLACK FLOWER", Rarity.SHINY, 16f, 16f) { g, gfx -> TeamArt.blackFlower(gfx, 0f, 0f, 16f, g.time) },
        Sticker("BOOMERANG FLOWER", Rarity.COMMON, 16f, 16f) { _, gfx -> gfx.sprite(TeamArt.flowerSprite(Power.BOOM), 0f, 0f) },
        Sticker("GRASS FLOWER", Rarity.COMMON, 16f, 16f) { _, gfx -> gfx.sprite(TeamArt.flowerSprite(Power.GRASS), 0f, 0f) },
        Sticker("THUNDER FLOWER", Rarity.RARE, 16f, 16f) { _, gfx -> gfx.sprite(TeamArt.flowerSprite(Power.THUNDER), 0f, 0f) },
        Sticker("CRYSTAL FLOWER", Rarity.RARE, 16f, 16f) { _, gfx -> gfx.sprite(TeamArt.flowerSprite(Power.CRYSTAL), 0f, 0f) },
        Sticker("GOLD SWORD", Rarity.COMMON, 22f, 22f) { _, gfx -> TeamArt.sword(gfx, 3f, 19f, -0.8f, Sword.GOLD) },
        Sticker("OBSIDIAN SWORD", Rarity.SHINY, 22f, 22f) { _, gfx -> TeamArt.sword(gfx, 3f, 19f, -0.8f, Sword.OBSIDIAN) },
        Sticker("IRON PICKAXE", Rarity.COMMON, 22f, 22f) { _, gfx -> TeamArt.pickaxe(gfx, 4f, 18f, -0.8f, 2) },
        Sticker("IGLOO", Rarity.COMMON, 84f, 62f) { g, gfx -> Homes.drawHouse(gfx, HouseType.IGLOO, 6f, 60f, g.time) },
        Sticker("MUSHROOM HOUSE", Rarity.RARE, 90f, 76f) { g, gfx -> Homes.drawHouse(gfx, HouseType.MUSHROOM, 9f, 74f, g.time) },
        Sticker("MINI CASTLE", Rarity.SHINY, 92f, 104f) { g, gfx -> Homes.drawHouse(gfx, HouseType.CASTLE, 10f, 102f, g.time) },
        Sticker("MINECART", Rarity.COMMON, 24f, 14f) { g, gfx -> Minecart(1f, 13f).draw(g, gfx, 0f) },
        Sticker("AMETHYST", Rarity.RARE, 16f, 16f) { _, gfx -> TeamArt.oreIcon(gfx, Ore.AMETHYST, 0f, 0f, 16f) },
    )

    val BASE get() = base.size

    /** All 300: the 60 normal ones first, then silver, gold, rainbow and midnight copies. */
    val all: List<Sticker> = StickerStyle.entries.flatMap { st ->
        if (st == StickerStyle.NORMAL) base
        else base.map { b -> Sticker(st.prefix + b.name, b.rarity, b.w, b.h, b.draw).also { it.style = st.ordinal } }
    }

    /** The sticker you're sure to be offered for beating each boss (by its level character). */
    fun forBoss(c: Char): Int = all.indexOfFirst {
        it.name == when (c) {
            'J' -> "KRAG JR."
            'W' -> "WONDER KRAG JR."
            'K' -> "KING KRAG"
            'Q' -> "WONDER KING KRAG"
            'Y' -> "DRY BONES KRAG"
            'G' -> "THE WARDEN"
            'Z' -> "ENDER DRAGON"
            else -> ""
        }
    }

    /** Draws sticker [i]'s picture centered in a box of [size] at ([cx], [cy]). */
    fun drawPicture(g: TeamGame, gfx: Gfx, i: Int, cx: Float, cy: Float, size: Float) {
        val st = all[i]
        val s = size / maxOf(st.w, st.h)
        st.draw(g, ScaledGfx(gfx, s, cx - st.w * s / 2, cy - st.h * s / 2))
    }

    /** A whole sticker: a white-edged card with the picture. Shiny ones sparkle. */
    fun drawCard(g: TeamGame, gfx: Gfx, i: Int, x: Float, y: Float, w: Float, h: Float, owned: Boolean = true, label: Boolean = true) {
        val st = all[i]
        val style = StickerStyle.entries[st.style]
        val sparkly = (st.rarity == Rarity.SHINY || st.style >= 2) && owned
        val edge = if (sparkly) solidRainbow(g.time * 0.5f, i * 0.1f) else if (st.style == 1 && owned) 0xFFE8EEF8.toInt() else 0xFFFFFFFF.toInt()
        gfx.rect(x - 1.5f, y - 1.5f, w + 3, h + 3, 0x66000000)
        gfx.rect(x, y, w, h, edge)
        val card = when {
            !owned -> 0xFF3A3A48.toInt()
            style == StickerStyle.RAINBOW -> solidRainbow(g.time * 0.2f, i * 0.05f)
            style.card != 0 -> style.card
            else -> argb(255, st.rarity.color)
        }
        gfx.rect(x + 2, y + 2, w - 4, h - 4, card)
        if (owned && style == StickerStyle.MIDNIGHT) for (k in 0 until 6) gfx.oval(x + 3 + (k * 13f + i) % (w - 6), y + 3 + (k * 7f) % (h - 16), 1.5f, 1.5f, 0xFFFFF2A0.toInt())
        val picH = if (label) h - 14 else h - 6
        if (owned) {
            drawPicture(g, gfx, i, x + w / 2, y + 3 + picH / 2, minOf(w - 8, picH))
            if (sparkly) {
                val sx = x + 4 + ((g.time * 30 + i * 7) % (w - 8))
                gfx.oval(sx, y + 4, 3f, 3f, 0xCCFFFFFF.toInt())
            }
        } else {
            gfx.text("?", x + w / 2, y + 3 + picH / 2 + 5, 14f, 0x66FFFFFF, 1)
        }
        if (label) {
            gfx.rect(x + 2, y + h - 12, w - 4, 10f, 0xCC000000.toInt())
            gfx.text(if (owned) st.name else "???", x + w / 2, y + h - 4.5f, minOf(6f, (w - 6) / (st.name.length * 0.62f)), 0xFFFFFFFF.toInt(), 1)
        }
    }
}
