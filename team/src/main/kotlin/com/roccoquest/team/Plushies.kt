package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.argb
import com.roccoquest.game.rainbow

/**
 * One of the 350 plushies. [price] is how many coins it costs in the shop at home.
 * Character plushies use their sticker picture; animal plushies are drawn from a [species].
 */
class Plushie(
    val id: Int, val name: String, val price: Int, val sticker: String? = null,
    val species: Species? = null, val color: Int = 0, val rainbowFur: Boolean = false, val baby: Boolean = false,
    /** A Pokemon plushie, or one of the World 6 friends and bosses (1 King Boo-Hoo, 2 Witch Wanda, 3 Spotty). */
    val mon: Mon? = null, val custom: Int = 0,
) {
    val boss get() = price >= 80
}

/** The kinds of stuffed animal, and how their ears, faces and extras look. */
enum class Species(val label: String) {
    BEAR("BEAR"), BUNNY("BUNNY"), CAT("CAT"), DOG("PUPPY"), FOX("FOX"), PANDA("PANDA"), PIG("PIGGY"),
    MOUSE("MOUSE"), OWL("OWL"), PENGUIN("PENGUIN"), DUCK("DUCKY"), FROGGY("FROGGY"), LION("LION"),
    ELEPHANT("ELEPHANT"), UNICORN("UNICORN"), DRAGON("DRAGON"),
    KOALA("KOALA"), SHEEP("SHEEP"), MONKEY("MONKEY"), TURTLE("TURTLE"), HAMSTER("HAMSTER"), OCTOPUS("OCTOPUS"),
    TIGER("TIGER"), ZEBRA("ZEBRA"), GIRAFFE("GIRAFFE"), DEER("DEER"), HEDGEHOG("HEDGEHOG"), SEAL("SEAL"), CHICK("CHICK"),
    AXOLOTL("AXOLOTL"), SLOTH("SLOTH"), OTTER("OTTER"), RACCOON("RACCOON"), HIPPO("HIPPO"), BAT("BAT"), SHARK("SHARK"),
    DINO("DINO"), WHALE("WHALE"), CRAB("CRAB"), SNAIL("SNAIL"), COW("COW"), HORSE("HORSE"), LLAMA("LLAMA"),
    SQUIRREL("SQUIRREL"), CHICKEN("CHICKEN"), BEE("BEE"),
}

object Plushies {
    private val colorNames = mapOf(
        "BROWN" to 0xFFA8703A, "PINK" to 0xFFFF9AC8, "BLUE" to 0xFF5A9AF0, "PURPLE" to 0xFFAA7AE8,
        "WHITE" to 0xFFF4F4F4, "YELLOW" to 0xFFF4D84A, "GREEN" to 0xFF5AC86A, "BLACK" to 0xFF3A3A44,
        "RED" to 0xFFE84A4A, "ORANGE" to 0xFFF08A3A, "GOLD" to 0xFFE8B82A, "GREY" to 0xFFA8A8B0,
        "MINT" to 0xFF8AE8C8, "RAINBOW" to 0xFFFFFFFF,
    ).mapValues { it.value.toInt() }

    /** Five colors for each kind of animal: the usual one first (cheapest), then fun ones, then a rare one. */
    private val animalColors = listOf(
        Species.BEAR to listOf("BROWN", "PINK", "BLUE", "WHITE", "RAINBOW"),
        Species.BUNNY to listOf("WHITE", "PINK", "BROWN", "PURPLE", "GOLD"),
        Species.CAT to listOf("ORANGE", "BLACK", "GREY", "PINK", "RAINBOW"),
        Species.DOG to listOf("BROWN", "WHITE", "BLACK", "BLUE", "GOLD"),
        Species.FOX to listOf("ORANGE", "WHITE", "PINK", "PURPLE", "GOLD"),
        Species.PANDA to listOf("WHITE", "PINK", "BLUE", "GREEN", "RAINBOW"),
        Species.PIG to listOf("PINK", "BROWN", "BLUE", "MINT", "GOLD"),
        Species.MOUSE to listOf("GREY", "WHITE", "PINK", "YELLOW", "RAINBOW"),
        Species.OWL to listOf("BROWN", "WHITE", "PURPLE", "BLUE", "GOLD"),
        Species.PENGUIN to listOf("BLACK", "BLUE", "PINK", "PURPLE", "RAINBOW"),
        Species.DUCK to listOf("YELLOW", "WHITE", "BLUE", "MINT", "GOLD"),
        Species.FROGGY to listOf("GREEN", "BLUE", "PINK", "YELLOW", "RAINBOW"),
        Species.LION to listOf("GOLD", "WHITE", "PINK", "BLUE", "RAINBOW"),
        Species.ELEPHANT to listOf("GREY", "PINK", "BLUE", "PURPLE", "GOLD"),
        Species.UNICORN to listOf("WHITE", "PINK", "BLUE", "PURPLE", "RAINBOW"),
        Species.DRAGON to listOf("GREEN", "RED", "BLUE", "PURPLE"),
    )

    /** The fourth batch: 10 more animals. */
    private val batch4Animals = listOf(
        Species.DINO to listOf("GREEN", "BLUE", "PURPLE", "ORANGE", "RAINBOW"),
        Species.WHALE to listOf("BLUE", "GREY", "PINK", "MINT", "GOLD"),
        Species.CRAB to listOf("RED", "ORANGE", "PINK", "BLUE", "RAINBOW"),
        Species.SNAIL to listOf("BROWN", "PINK", "MINT", "YELLOW", "GOLD"),
        Species.COW to listOf("WHITE", "BROWN", "PINK", "BLACK", "RAINBOW"),
        Species.HORSE to listOf("BROWN", "WHITE", "BLACK", "PURPLE", "GOLD"),
        Species.LLAMA to listOf("WHITE", "BROWN", "PINK", "MINT", "RAINBOW"),
        Species.SQUIRREL to listOf("ORANGE", "BROWN", "GREY", "PINK", "GOLD"),
        Species.CHICKEN to listOf("WHITE", "YELLOW", "BROWN", "PINK", "RAINBOW"),
        Species.BEE to listOf("YELLOW", "ORANGE", "PINK", "BLUE", "GOLD"),
    )

    /** The third batch: 14 more animals. */
    private val evenMoreAnimals = listOf(
        Species.TIGER to listOf("ORANGE", "WHITE", "PINK", "BLUE", "RAINBOW"),
        Species.ZEBRA to listOf("WHITE", "PINK", "MINT", "PURPLE", "GOLD"),
        Species.GIRAFFE to listOf("YELLOW", "ORANGE", "PINK", "BLUE", "RAINBOW"),
        Species.DEER to listOf("BROWN", "WHITE", "PINK", "MINT", "GOLD"),
        Species.HEDGEHOG to listOf("BROWN", "GREY", "PINK", "BLUE", "RAINBOW"),
        Species.SEAL to listOf("GREY", "WHITE", "BLUE", "PINK", "GOLD"),
        Species.CHICK to listOf("YELLOW", "WHITE", "PINK", "MINT", "RAINBOW"),
        Species.AXOLOTL to listOf("PINK", "WHITE", "BLUE", "YELLOW", "GOLD"),
        Species.SLOTH to listOf("BROWN", "GREY", "MINT", "PURPLE", "RAINBOW"),
        Species.OTTER to listOf("BROWN", "GREY", "WHITE", "BLUE", "GOLD"),
        Species.RACCOON to listOf("GREY", "BROWN", "PINK", "PURPLE", "RAINBOW"),
        Species.HIPPO to listOf("PURPLE", "GREY", "PINK", "BLUE", "GOLD"),
        Species.BAT to listOf("BLACK", "PURPLE", "PINK", "WHITE", "RAINBOW"),
        Species.SHARK to listOf("BLUE", "GREY", "PINK", "MINT", "GOLD"),
    )

    /** The second batch of plushies (added later, so they go at the end and old saves keep theirs). */
    private val moreAnimals = listOf(
        Species.KOALA to listOf("GREY", "WHITE", "PINK", "BLUE", "RAINBOW"),
        Species.SHEEP to listOf("WHITE", "BLACK", "PINK", "MINT", "GOLD"),
        Species.MONKEY to listOf("BROWN", "YELLOW", "PURPLE", "BLUE", "RAINBOW"),
        Species.TURTLE to listOf("GREEN", "BLUE", "ORANGE", "PINK", "GOLD"),
        Species.HAMSTER to listOf("ORANGE", "WHITE", "GREY", "PINK", "RAINBOW"),
        Species.OCTOPUS to listOf("PURPLE", "PINK", "RED", "MINT", "RAINBOW"),
    )

    val all: List<Plushie> = buildList {
        // The team and friends
        val chars = listOf(
            "ROCCO", "BLOCKY", "SPARKY", "BLAZE", "HYDRO", "BLOOM", "EMBER", "SPLASHY", "MYSTIC", "PUNCHO", "SPOOKY",
            "RING-DING FROG", "DENNIS", "GRUMBLER", "GLOOP", "ENDERMAN",
        )
        for (n in chars) add(Plushie(size, n, 40, sticker = if (n == "GLOOP") null else n))
        // Bosses (only bosses drop them, or save up lots of coins!)
        for (n in listOf("KRAG JR.", "KING KRAG", "THE WARDEN", "ENDER DRAGON")) add(Plushie(size, n, 80, sticker = n))
        add(Plushie(size, "BABY FOX", 25, species = Species.FOX, color = colorNames.getValue("ORANGE"), baby = true))
        for ((sp, cols) in animalColors) cols.forEachIndexed { i, c ->
            val price = when { c == "RAINBOW" || c == "GOLD" -> 60; i == 0 -> 15; else -> 30 }
            add(Plushie(size, "$c ${sp.label}", price, species = sp, color = colorNames.getValue(c), rainbowFur = c == "RAINBOW"))
        }
        // ---- Batch 2 (50 more) ----
        for (n in listOf("LUGIA", "METAL ROCCO", "VILLAGER", "SHELLBACK", "BONEHEAD", "BOMB-BOMB", "FLAPPER", "FISH")) add(Plushie(size, n, 40, sticker = n))
        add(Plushie(size, "POSEIDON", 70, sticker = "POSEIDON"))
        add(Plushie(size, "GOLD DRAGON", 60, species = Species.DRAGON, color = colorNames.getValue("GOLD")))
        add(Plushie(size, "RAINBOW DRAGON", 60, species = Species.DRAGON, color = colorNames.getValue("RAINBOW"), rainbowFur = true))
        for ((sp, c) in listOf(Species.BUNNY to "WHITE", Species.PANDA to "WHITE", Species.CAT to "ORANGE", Species.DOG to "BROWN",
            Species.PENGUIN to "BLACK", Species.UNICORN to "PINK", Species.DRAGON to "GREEN", Species.OWL to "BROWN", Species.LION to "GOLD")) {
            add(Plushie(size, "BABY ${sp.label}", 25, species = sp, color = colorNames.getValue(c), baby = true))
        }
        for ((sp, cols) in moreAnimals) cols.forEachIndexed { i, c ->
            val price = when { c == "RAINBOW" || c == "GOLD" -> 60; i == 0 -> 15; else -> 30 }
            add(Plushie(size, "$c ${sp.label}", price, species = sp, color = colorNames.getValue(c), rainbowFur = c == "RAINBOW"))
        }
        // ---- Batch 3 (100 more) ----
        for (n in listOf("SUPER STAR", "FIRE FLOWER", "ICE FLOWER", "MINI MUSHROOM", "GOLDEN APPLE", "HEART", "TANOOKI ROCCO",
            "WING CAP ROCCO", "SUPER LEAF", "BLUE SHELL", "TNT", "DIAMOND")) add(Plushie(size, n, 40, sticker = n))
        for (n in listOf("WONDER KRAG JR.", "WONDER KING KRAG", "DRY BONES KRAG")) add(Plushie(size, n, 80, sticker = n))
        for ((sp, c) in listOf(Species.KOALA to "GREY", Species.SHEEP to "WHITE", Species.MONKEY to "BROWN", Species.TURTLE to "GREEN",
            Species.HAMSTER to "ORANGE", Species.OCTOPUS to "PURPLE", Species.BEAR to "BROWN", Species.ELEPHANT to "GREY",
            Species.PIG to "PINK", Species.MOUSE to "GREY", Species.DUCK to "YELLOW", Species.FROGGY to "GREEN", Species.TIGER to "ORANGE")) {
            add(Plushie(size, "BABY ${sp.label}", 25, species = sp, color = colorNames.getValue(c), baby = true))
        }
        add(Plushie(size, "GOLD UNICORN", 60, species = Species.UNICORN, color = colorNames.getValue("GOLD")))
        add(Plushie(size, "BLACK DRAGON", 60, species = Species.DRAGON, color = colorNames.getValue("BLACK")))
        for ((sp, cols) in evenMoreAnimals) cols.forEachIndexed { i, c ->
            val price = when { c == "RAINBOW" || c == "GOLD" -> 60; i == 0 -> 15; else -> 30 }
            add(Plushie(size, "$c ${sp.label}", price, species = sp, color = colorNames.getValue(c), rainbowFur = c == "RAINBOW"))
        }
        // ---- Batch 4 (100 more) ----
        for (m in listOf(Mon.SCORCH, Mon.TIDAL, Mon.SEEDY, Mon.SPROUT, Mon.ZAPPER, Mon.BRAWLER, Mon.MEGAPUNCH, Mon.SHADE, Mon.PHANTOM, Mon.PSYKO)) {
            add(Plushie(size, m.label, 45, mon = m))
        }
        add(Plushie(size, "SPOTTY", 40, custom = 3))
        add(Plushie(size, "KING BOO-HOO", 80, custom = 1))
        add(Plushie(size, "WITCH WANDA", 80, custom = 2))
        for ((sp, cols) in batch4Animals) cols.forEachIndexed { i, c ->
            val price = when { c == "RAINBOW" || c == "GOLD" -> 60; i == 0 -> 15; else -> 30 }
            add(Plushie(size, "$c ${sp.label}", price, species = sp, color = colorNames.getValue(c), rainbowFur = c == "RAINBOW"))
        }
        for (sp in batch4Animals.map { it.first } + listOf(Species.ZEBRA, Species.GIRAFFE, Species.SEAL, Species.CHICK, Species.AXOLOTL,
            Species.OTTER, Species.HEDGEHOG, Species.HIPPO, Species.SHARK, Species.RACCOON, Species.BAT)) {
            add(Plushie(size, "BABY ${sp.label}", 25, species = sp, color = colorNames.getValue(batch4Animals.firstOrNull { it.first == sp }?.second?.first() ?: evenMoreAnimals.firstOrNull { it.first == sp }?.second?.first() ?: "WHITE"), baby = true))
        }
        // Then gold and rainbow ones of animals that didn't have them yet, until there are 350.
        val names = map { it.name }.toMutableSet()
        loop@ for ((sp, _) in animalColors + moreAnimals + evenMoreAnimals + batch4Animals) for (c in listOf("GOLD", "RAINBOW", "MINT", "PURPLE")) {
            if (size >= 350) break@loop
            val n = "$c ${sp.label}"
            if (n in names) continue
            names += n
            add(Plushie(size, n, if (c == "GOLD" || c == "RAINBOW") 60 else 30, species = sp, color = colorNames.getValue(c), rainbowFur = c == "RAINBOW"))
        }
    }

    fun named(n: String) = all.first { it.name == n }
    val KRAG_JR get() = named("KRAG JR.")
    val KING_KRAG get() = named("KING KRAG")
    val WARDEN get() = named("THE WARDEN")
    val DRAGON get() = named("ENDER DRAGON")
    val BABY_FOX get() = named("BABY FOX")

    /** Draws plushie [p] sitting with its bottom at ([cx], [bottom]), about [size] tall. */
    fun draw(g: TeamGame, gfx: Gfx, p: Plushie, cx: Float, bottom: Float, size: Float) {
        gfx.oval(cx - size * 0.45f, bottom - 2, size * 0.9f, 4f, 0x44000000)
        when {
            p.species != null -> drawAnimal(gfx, p, cx, bottom, size, g.time)
            p.mon != null -> TeamArt.mon(ScaledGfx(gfx, size / 26f, cx, bottom), p.mon, 0f, 0f, g.time, 1, false)
            p.custom == 1 -> KingBooHoo(0f, 60f).draw(g, ScaledGfx(gfx, size / 48f, cx - 22f * size / 48f, bottom - 42f * size / 48f), 0f)
            p.custom == 2 -> Witch.drawWitch(gfx, cx - 7f * size / 22f, bottom - size + 2f, g.time, 1, size / 22f)
            p.custom == 3 -> Spotty.draw(ScaledGfx(gfx, size / 22f, cx - 7f * size / 22f, bottom - 21f * size / 22f), 0f, 0f, 0f, false, true)
            p.sticker == null -> {
                val s = size / 18f
                Gloop(0f, 16f).draw(g, ScaledGfx(gfx, s, cx - 9 * s, bottom - 17 * s), 0f)
            }
            else -> {
                val i = Stickers.all.indexOfFirst { it.name == p.sticker }
                if (i >= 0) Stickers.drawPicture(g, gfx, i, cx, bottom - size / 2, size)
            }
        }
        // A little heart tag: it's a plushie!
        gfx.oval(cx + size * 0.25f, bottom - 6, 5f, 5f, 0xFFFF6A9A.toInt())
    }

    private fun lighter(c: Int, k: Float = 0.45f): Int {
        val r = (c shr 16) and 255; val gg = (c shr 8) and 255; val b = c and 255
        fun l(v: Int) = (v + (255 - v) * k).toInt()
        return argb(255, (l(r) shl 16) or (l(gg) shl 8) or l(b))
    }

    private fun darker(c: Int, k: Float = 0.35f): Int {
        val r = (c shr 16) and 255; val gg = (c shr 8) and 255; val b = c and 255
        fun d(v: Int) = (v * (1 - k)).toInt()
        return argb(255, (d(r) shl 16) or (d(gg) shl 8) or d(b))
    }

    /** A soft round stuffed animal: body, big head, ears and a cute face. Drawn in an 18-unit box. */
    private fun drawAnimal(out: Gfx, p: Plushie, cx: Float, bottom: Float, size: Float, t: Float) {
        val sz = if (p.baby) size * 0.8f else size
        val s = sz / 18f
        val gfx = ScaledGfx(out, s, cx - 9 * s, bottom - 18 * s)
        val sp = p.species!!
        val fur = if (p.rainbowFur) argb(255, rainbow(t * 0.3f)) else p.color
        val belly = lighter(fur)
        val dark = darker(fur)
        val black = 0xFF1A1A1A.toInt()
        val white = 0xFFFFFFFF.toInt()
        // Ears / horns behind the head
        when (sp) {
            Species.BEAR, Species.PANDA, Species.MOUSE, Species.LION -> {
                val big = if (sp == Species.MOUSE) 5.5f else 3.5f
                gfx.oval(2f, 0.5f, big, big, if (sp == Species.PANDA) black else fur)
                gfx.oval(16f - big, 0.5f, big, big, if (sp == Species.PANDA) black else fur)
            }
            Species.BUNNY -> {
                gfx.oval(4.5f, -5f, 3f, 8f, fur); gfx.oval(10.5f, -5f, 3f, 8f, fur)
                gfx.oval(5.3f, -4f, 1.4f, 6f, 0xFFFFB8D0.toInt()); gfx.oval(11.3f, -4f, 1.4f, 6f, 0xFFFFB8D0.toInt())
            }
            Species.CAT, Species.FOX -> {
                gfx.poly(floatArrayOf(3f, 4.5f, 7.5f), floatArrayOf(4f, -1f, 2f), fur)
                gfx.poly(floatArrayOf(15f, 13.5f, 10.5f), floatArrayOf(4f, -1f, 2f), fur)
            }
            Species.DOG -> { gfx.oval(1.5f, 2f, 4f, 7f, dark); gfx.oval(12.5f, 2f, 4f, 7f, dark) }
            Species.ELEPHANT -> { gfx.oval(0f, 2f, 6f, 7f, dark); gfx.oval(12f, 2f, 6f, 7f, dark) }
            Species.UNICORN -> {
                gfx.poly(floatArrayOf(7.8f, 9f, 10.2f), floatArrayOf(2f, -4f, 2f), 0xFFFFD82A.toInt())
                gfx.poly(floatArrayOf(3.5f, 5f, 7f), floatArrayOf(3f, 0f, 2f), fur)
                gfx.poly(floatArrayOf(14.5f, 13f, 11f), floatArrayOf(3f, 0f, 2f), fur)
            }
            Species.DRAGON -> {
                gfx.poly(floatArrayOf(4f, 5f, 6.5f), floatArrayOf(2.5f, -1.5f, 2f), dark)
                gfx.poly(floatArrayOf(14f, 13f, 11.5f), floatArrayOf(2.5f, -1.5f, 2f), dark)
                // little wings
                gfx.poly(floatArrayOf(2f, -2f, 3f), floatArrayOf(11f, 7f, 14f), dark)
                gfx.poly(floatArrayOf(16f, 20f, 15f), floatArrayOf(11f, 7f, 14f), dark)
            }
            Species.OWL -> {
                gfx.poly(floatArrayOf(3f, 4f, 6f), floatArrayOf(3f, 0f, 2f), dark)
                gfx.poly(floatArrayOf(15f, 14f, 12f), floatArrayOf(3f, 0f, 2f), dark)
            }
            Species.KOALA -> { gfx.oval(-0.5f, 0.5f, 7f, 7f, fur); gfx.oval(11.5f, 0.5f, 7f, 7f, fur); gfx.oval(1f, 2f, 4f, 4f, belly); gfx.oval(13f, 2f, 4f, 4f, belly) }
            Species.SHEEP -> for (k in 0 until 7) gfx.oval(1f + k * 2.3f, 0f + (k % 2) * 1.5f, 5f, 5f, belly)
            Species.MONKEY -> { gfx.oval(0.5f, 4f, 4.5f, 4.5f, fur); gfx.oval(13f, 4f, 4.5f, 4.5f, fur); gfx.oval(1.5f, 5f, 2.5f, 2.5f, belly); gfx.oval(14f, 5f, 2.5f, 2.5f, belly) }
            Species.TURTLE -> { gfx.oval(1f, 8f, 16f, 10f, dark); for (k in 0 until 3) gfx.oval(3.5f + k * 4f, 10f, 3f, 3f, belly) }
            Species.HAMSTER -> { gfx.oval(3f, 1f, 3f, 3f, belly); gfx.oval(12f, 1f, 3f, 3f, belly) }
            Species.OCTOPUS -> for (k in 0 until 5) gfx.oval(2f + k * 3f, 13f + (k % 2) * 1f, 3f, 5.5f, fur)
            Species.TIGER, Species.RACCOON, Species.OTTER, Species.HIPPO -> { gfx.oval(3f, 1f, 3.5f, 3.5f, fur); gfx.oval(11.5f, 1f, 3.5f, 3.5f, fur) }
            Species.ZEBRA -> { gfx.oval(3.5f, -2f, 3f, 6f, fur); gfx.oval(11.5f, -2f, 3f, 6f, fur); gfx.rect(8f, -1f, 2f, 4f, black) }
            Species.GIRAFFE -> {
                gfx.rect(6f, -2f, 1.2f, 4f, dark); gfx.rect(10.8f, -2f, 1.2f, 4f, dark)
                gfx.oval(5.5f, -3f, 2.2f, 2.2f, dark); gfx.oval(10.3f, -3f, 2.2f, 2.2f, dark)
                gfx.oval(2f, 3f, 3f, 2f, fur); gfx.oval(13f, 3f, 3f, 2f, fur)
            }
            Species.DEER -> {
                val antler = 0xFF8A5A2A.toInt()
                gfx.rect(5f, -3f, 1.2f, 5f, antler); gfx.rect(3f, -3f, 3f, 1.2f, antler)
                gfx.rect(11.8f, -3f, 1.2f, 5f, antler); gfx.rect(12f, -3f, 3f, 1.2f, antler)
                gfx.oval(1.5f, 3f, 4f, 2.5f, fur); gfx.oval(12.5f, 3f, 4f, 2.5f, fur)
            }
            Species.HEDGEHOG -> for (k in 0 until 7) gfx.poly(floatArrayOf(1f + k * 2.3f, 2.2f + k * 2.3f, 3.4f + k * 2.3f), floatArrayOf(5f, -1f + (k % 2), 5f), dark)
            Species.CHICK -> gfx.oval(8f, -1f, 2.5f, 3f, fur)
            Species.AXOLOTL -> for (sd in listOf(0f, 1f)) for (k in 0 until 3) {
                val gx = if (sd == 0f) 0.5f - k * 0.5f else 15f + k * 0.5f
                gfx.oval(gx, 1.5f + k * 2.5f, 3f, 1.6f, 0xFFFF6A9A.toInt())
            }
            Species.BAT -> {
                gfx.poly(floatArrayOf(3f, 3.5f, 7f), floatArrayOf(4f, -3f, 2f), fur)
                gfx.poly(floatArrayOf(15f, 14.5f, 11f), floatArrayOf(4f, -3f, 2f), fur)
                gfx.poly(floatArrayOf(3f, -3f, -1f, 3f), floatArrayOf(10f, 7f, 14f, 15f), dark)
                gfx.poly(floatArrayOf(15f, 21f, 19f, 15f), floatArrayOf(10f, 7f, 14f, 15f), dark)
            }
            Species.SHARK -> gfx.poly(floatArrayOf(7f, 10f, 11f), floatArrayOf(2f, -4f, 2f), dark)
            Species.SEAL -> { gfx.oval(1f, 12f, 4f, 3f, dark); gfx.oval(13f, 12f, 4f, 3f, dark) }
            Species.DINO -> for (k in 0 until 4) gfx.poly(floatArrayOf(3f + k * 3.5f, 4.7f + k * 3.5f, 6.4f + k * 3.5f), floatArrayOf(3f, -1.5f, 3f), dark)
            Species.WHALE -> { gfx.poly(floatArrayOf(14f, 20f, 19f), floatArrayOf(12f, 8f, 15f), dark); gfx.oval(7.5f, -3f, 3f, 4f, 0xAA8AD0FF.toInt()) }
            Species.CRAB -> { gfx.oval(-2f, 6f, 6f, 5f, fur); gfx.oval(14f, 6f, 6f, 5f, fur); gfx.rect(5f, -1f, 1f, 4f, dark); gfx.rect(12f, -1f, 1f, 4f, dark) }
            Species.SNAIL -> { gfx.oval(9f, 4f, 11f, 11f, dark); gfx.oval(11.5f, 6.5f, 6f, 6f, belly); gfx.rect(6f, -1f, 0.8f, 4f, dark); gfx.rect(11f, -1f, 0.8f, 4f, dark) }
            Species.COW -> { gfx.poly(floatArrayOf(3f, 2f, 5f), floatArrayOf(3f, -1f, 2f), 0xFFE8D8A0.toInt()); gfx.poly(floatArrayOf(15f, 16f, 13f), floatArrayOf(3f, -1f, 2f), 0xFFE8D8A0.toInt()) }
            Species.HORSE -> { gfx.oval(4f, -1f, 3f, 5f, fur); gfx.oval(11f, -1f, 3f, 5f, fur); gfx.oval(7f, -2f, 4f, 6f, dark) }
            Species.LLAMA -> { gfx.oval(4f, -5f, 3f, 8f, fur); gfx.oval(11f, -5f, 3f, 8f, fur) }
            Species.SQUIRREL -> { gfx.oval(12f, 2f, 8f, 14f, dark); gfx.oval(4f, 0f, 3f, 4f, fur); gfx.oval(11f, 0f, 3f, 4f, fur) }
            Species.CHICKEN -> { gfx.oval(7f, -2f, 2.5f, 4f, 0xFFE84A4A.toInt()); gfx.oval(9f, -2.5f, 2.5f, 4.5f, 0xFFE84A4A.toInt()) }
            Species.BEE -> { gfx.oval(0f, 3f, 6f, 5f, 0xAADDF4FF.toInt()); gfx.oval(12f, 3f, 6f, 5f, 0xAADDF4FF.toInt()); gfx.rect(6f, -1f, 0.8f, 4f, dark); gfx.rect(11f, -1f, 0.8f, 4f, dark) }
            else -> {}
        }
        if (sp == Species.LION) gfx.oval(1.5f, 0.5f, 15f, 13f, darker(fur, 0.25f))
        if (sp == Species.FOX && !p.baby) gfx.oval(13f, 11f, 6f, 4f, fur)
        // Body and head
        if (sp != Species.OCTOPUS) {
            gfx.oval(3.5f, 9f, 11f, 9f, fur)
            gfx.oval(6f, 11f, 6f, 6f, if (sp == Species.PENGUIN) white else belly)
        }
        gfx.oval(3f, 1.5f, 12f, 10.5f, fur)
        if (sp == Species.PENGUIN || sp == Species.OWL) gfx.oval(5f, 4f, 8f, 7.5f, if (sp == Species.PENGUIN) white else belly)
        if (sp == Species.PANDA) {
            gfx.oval(5f, 4.5f, 3.2f, 3.2f, black); gfx.oval(9.8f, 4.5f, 3.2f, 3.2f, black)
            gfx.oval(3.5f, 9.5f, 3f, 5f, black); gfx.oval(11.5f, 9.5f, 3f, 5f, black)
        }
        when (sp) {
            Species.TIGER, Species.ZEBRA -> for (k in 0 until 3) {
                val c = if (sp == Species.ZEBRA) black else dark
                gfx.rect(4f, 3f + k * 2.5f, 2.5f, 1f, c); gfx.rect(11.5f, 3f + k * 2.5f, 2.5f, 1f, c); gfx.rect(8.3f, 2f + k * 1.2f, 1.4f, 0.8f, c)
            }
            Species.GIRAFFE -> for (k in 0 until 4) gfx.oval(4f + k * 2.8f, 12f + (k % 2) * 2f, 2f, 2f, dark)
            Species.RACCOON -> gfx.oval(4.5f, 4.7f, 9f, 3.5f, black)
            Species.COW -> { gfx.oval(4f, 10f, 4f, 3f, black); gfx.oval(11f, 13f, 3f, 3f, black); gfx.oval(5f, 2.5f, 3f, 2.5f, black) }
            Species.BEE -> for (k in 0 until 2) gfx.rect(4f, 12f + k * 3f, 10f, 1.2f, black)
            Species.SLOTH -> { gfx.oval(5f, 4.5f, 3.5f, 3.5f, dark); gfx.oval(9.5f, 4.5f, 3.5f, 3.5f, dark) }
            else -> {}
        }
        // Face
        val eye = if (p.baby) 2.2f else 1.7f
        gfx.oval(6f, 5.5f, eye, eye + 0.3f, black)
        gfx.oval(10.3f, 5.5f, eye, eye + 0.3f, black)
        gfx.oval(6.4f, 5.7f, 0.7f, 0.7f, white)
        gfx.oval(10.7f, 5.7f, 0.7f, 0.7f, white)
        when (sp) {
            Species.OWL, Species.PENGUIN, Species.DUCK ->
                gfx.poly(floatArrayOf(8f, 10f, 9f), floatArrayOf(8f, 8f, if (sp == Species.DUCK) 10.5f else 9.8f), 0xFFF0A02A.toInt())
            Species.PIG -> { gfx.oval(7.2f, 7.8f, 3.6f, 2.6f, 0xFFFF7AA8.toInt()); gfx.rect(8f, 8.6f, 0.6f, 0.8f, dark); gfx.rect(9.4f, 8.6f, 0.6f, 0.8f, dark) }
            Species.ELEPHANT -> gfx.poly(floatArrayOf(8f, 10f, 9.6f, 8.6f), floatArrayOf(7.5f, 7.5f, 13f, 13f), fur)
            Species.FROGGY -> gfx.rect(7f, 9f, 4f, 0.8f, dark)
            Species.KOALA -> gfx.oval(7.5f, 7f, 3f, 3.5f, black)
            Species.CHICK, Species.CHICKEN -> gfx.poly(floatArrayOf(8f, 10f, 9f), floatArrayOf(8f, 8f, 9.8f), 0xFFF0A02A.toInt())
            Species.HORSE, Species.LLAMA, Species.COW -> { gfx.oval(6.5f, 7f, 5f, 3.5f, belly); gfx.oval(7.5f, 8f, 1f, 1f, dark); gfx.oval(9.5f, 8f, 1f, 1f, dark) }
            Species.HIPPO -> { gfx.oval(5.5f, 7f, 7f, 4.5f, belly); gfx.oval(7f, 8f, 1.2f, 1.2f, dark); gfx.oval(9.8f, 8f, 1.2f, 1.2f, dark) }
            Species.SEAL, Species.OTTER -> {
                gfx.oval(7.3f, 7.5f, 3.4f, 2.8f, belly); gfx.oval(8.3f, 7.8f, 1.4f, 1f, black)
                gfx.rect(4.5f, 8.5f, 3f, 0.4f, dark); gfx.rect(10.5f, 8.5f, 3f, 0.4f, dark)
            }
            Species.SHARK -> { gfx.rect(6.5f, 9f, 5f, 1f, white); for (k in 0 until 3) gfx.rect(7f + k * 1.6f, 9f, 0.4f, 1f, dark) }
            Species.MONKEY -> { gfx.oval(6.5f, 7f, 5f, 3.5f, belly); gfx.rect(8f, 9f, 2f, 0.6f, dark) }
            else -> {
                if (sp == Species.BEAR || sp == Species.DOG || sp == Species.LION || sp == Species.FOX) gfx.oval(7.3f, 7.5f, 3.4f, 2.8f, belly)
                gfx.oval(8.3f, 7.8f, 1.4f, 1f, if (sp == Species.UNICORN || sp == Species.BUNNY || sp == Species.MOUSE || sp == Species.CAT) 0xFFFF7AA8.toInt() else black)
            }
        }
        // Rosy cheeks
        gfx.oval(4.5f, 8f, 2f, 1.2f, 0x66FF6A9A)
        gfx.oval(11.5f, 8f, 2f, 1.2f, 0x66FF6A9A)
        if (sp == Species.UNICORN) gfx.oval(3f, 2f, 3f, 6f, if (p.rainbowFur) 0xFFFFD82A.toInt() else argb(255, rainbow(t * 0.3f)))
    }
}
