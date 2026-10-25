package com.roccoquest.team

import com.roccoquest.game.Body
import com.roccoquest.game.Gfx
import com.roccoquest.game.argb
import com.roccoquest.game.rainbow
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

const val GRAVITY = 900f
const val MAX_FALL = 420f
const val MAX_FLOWER_LV = 5

/**
 * Flower powers the team can craft. The metal sets the strength: iron is the weakest,
 * then gold, then diamond. Emerald makes the Grass Flower, which gives green armor.
 * Copper and amethyst are found in the later levels.
 */
enum class Power(val label: String, val ore: Ore?, val damage: Int) {
    NONE("", null, 0),
    BOOM("BOOMERANG FLOWER", Ore.IRON, 1),
    FIRE("FIRE FLOWER", Ore.GOLD, 2),
    ICE("ICE FLOWER", Ore.DIAMOND, 3),
    GRASS("GRASS FLOWER", Ore.EMERALD, 1),
    THUNDER("THUNDER FLOWER", Ore.COPPER, 2),
    CRYSTAL("CRYSTAL FLOWER", Ore.AMETHYST, 2),
    /** Rocco wears a blue shell: homing shells to throw, and a spin dash when sprinting. */
    SHELL("BLUE SHELL", Ore.DIAMOND, 2),
    /** Cappy: throw Rocco's cap to capture a bad guy and use its power. */
    CAPPY("CAPPY", Ore.SAPPHIRE, 2),
    /** Gold Flower: golden fireballs that turn hits into coins. */
    GOLD("GOLD FLOWER", Ore.GOLD, 3),
}

/**
 * The Pokemon partners. Each has a weak normal attack and one huge SUPER move that
 * takes a long time to charge, so it's a boost rather than something to spam.
 */
enum class Mon(val label: String, val color: Int, val superName: String, val blurb: String) {
    SPARKY("SPARKY", 0xFFFFD82A.toInt(), "THUNDERBOLT", "Electric mouse. Fast, high jumps. SUPER zaps every bad guy on screen!"),
    BLAZE("BLAZE", 0xFFFF8A3A.toInt(), "FLAMETHROWER", "Fire dragon. Glides when you hold JUMP. SUPER burns everything in front!"),
    HYDRO("HYDRO", 0xFF4A8AE8.toInt(), "HYDRO CANNON", "Water turtle with cannons. SUPER blasts both ways at once!"),
    BLOOM("BLOOM", 0xFF3CB878.toInt(), "SOLAR BEAM", "Flower dino. Strong vine whip. SUPER fires a giant beam across the screen!"),
    EMBER("EMBER", 0xFFFF9A4A.toInt(), "FIRE SPIN", "Little fire lizard. SUPER spins a ring of fire all around!"),
    SPLASHY("SPLASHY", 0xFF6AB8F0.toInt(), "BUBBLE BEAM", "Tiny turtle. Holds its breath longer underwater. SUPER blasts bubbles!"),
    MYSTIC("MYSTIC", 0xFFFF9AD0.toInt(), "PSYCHIC", "Pink mystery cat. Floats when you hold JUMP. SUPER hits everyone AND heals!"),
    PUNCHO("PUNCHO", 0xFF8A9AB8.toInt(), "DYNAMIC PUNCH", "Super strong. Punches hard up close. SUPER is the biggest hit in the game!"),
    SPOOKY("SPOOKY", 0xFF7A4AA8.toInt(), "NIGHT SHADE", "Floaty ghost. Glides when you hold JUMP. SUPER scares every bad guy on screen!"),
    LUGIA("LUGIA", 0xFFDCE6F5.toInt(), "AEROBLAST", "Legendary sea bird! Glides AND swims. SUPER blows a giant wind across the screen!"),
    // Evolutions (unlocked by levelling up their first form)
    SCORCH("SCORCH", 0xFFE8502A.toInt(), "FIRE FANG", "Ember evolved! A fiery lizard with a horn. SUPER: a bigger ring of fire!"),
    TIDAL("TIDAL", 0xFF3A6AD8.toInt(), "AQUA TAIL", "Splashy evolved! Fluffy ears, strong swimmer. SUPER: big bubble blast!"),
    SEEDY("SEEDY", 0xFF5AC8A8.toInt(), "LEAF BLADE", "A little seed dino. Evolves into Sprout, then Bloom!"),
    SPROUT("SPROUT", 0xFF4AB890.toInt(), "PETAL BEAM", "Seedy evolved! Its bud is about to bloom. SUPER: a beam across the screen!"),
    ZAPPER("ZAPPER", 0xFFF0942A.toInt(), "THUNDER", "Sparky evolved! Orange and super charged. SUPER zaps everything on screen!"),
    BRAWLER("BRAWLER", 0xFF7A8AA8.toInt(), "CROSS CHOP", "Puncho evolved! Stronger punches. SUPER: a huge chop!"),
    MEGAPUNCH("MEGAPUNCH", 0xFF6A7A98.toInt(), "MEGA FISTS", "Four arms! The strongest puncher. SUPER: the biggest hit of all!"),
    SHADE("SHADE", 0xFF6A3AA8.toInt(), "SHADOW HANDS", "Spooky evolved! Floating ghost hands. SUPER scares everyone!"),
    PHANTOM("PHANTOM", 0xFF5A2A88.toInt(), "SHADOW BALL", "The grinning ghost! Floats. SUPER: a giant shadow ball!"),
    PSYKO("PSYKO", 0xFFB8A0D8.toInt(), "PSYSTRIKE", "Mystic's powerful cousin! Floats. SUPER hits everyone AND heals!"),
    // 20 more (a new one joins every 3 levels you beat)
    EEVO("EEVO", 0xFFB8885A.toInt(), "SWIFT", "Fluffy brown fox pup. Fast! Evolves into Ribbon. SUPER zaps every bad guy!"),
    RIBBON("RIBBON", 0xFFFFB8D8.toInt(), "FAIRY WIND", "Eevo evolved! Pink ribbons. Floats. SUPER hits everyone AND heals!"),
    NIGHTFUR("NIGHTFUR", 0xFF2A2A3A.toInt(), "DARK PULSE", "Black fox with glowing rings. Floats. SUPER scares every bad guy!"),
    SNOOZER("SNOOZER", 0xFF3A6A7A.toInt(), "BODY SLAM", "Big sleepy blob. Super strong. SUPER is a giant body slam!"),
    PUFFLE("PUFFLE", 0xFFFFA8C8.toInt(), "SING", "Round pink singer. Floats. SUPER sings everyone to sleep AND heals!"),
    PSYQUACK("PSYQUACK", 0xFFF4D84A.toInt(), "CONFUSION", "Yellow duck with a headache. SUPER confuses everyone AND heals!"),
    LUKO("LUKO", 0xFF3A6AC8.toInt(), "AURA SPHERE", "Blue fighter dog. Strong punches. SUPER: a big aura blast!"),
    SHURIKEN("SHURIKEN", 0xFF2A4A8A.toInt(), "WATER SHURIKEN", "Ninja frog! Great swimmer. SUPER throws water stars!"),
    MIMIC("MIMIC", 0xFFE8D088.toInt(), "SHADOW CLAW", "Wears a Sparky costume! Floats. SUPER scares everyone!"),
    RAYZOR("RAYZOR", 0xFF2E9A4A.toInt(), "DRAGON ASCENT", "Legendary sky dragon! Glides AND swims. SUPER: a giant wind!"),
    DRAGOBUD("DRAGOBUD", 0xFFF0A040.toInt(), "HURRICANE", "Friendly orange dragon. Glides. SUPER breathes a big blast!"),
    FLOPFISH("FLOPFISH", 0xFFE8603A.toInt(), "SPLASH", "Floppy red fish. Evolves into Ragefish at level 5!"),
    RAGEFISH("RAGEFISH", 0xFF3A7AD8.toInt(), "HYPER BEAM", "Flopfish evolved! A giant sea dragon. SUPER blasts both ways!"),
    EGGBABY("EGGBABY", 0xFFF4F0D8.toInt(), "METRONOME", "Baby in an egg shell. Floats. SUPER: who knows! (It heals too.)"),
    MEOWZER("MEOWZER", 0xFFF0E0B0.toInt(), "PAY DAY", "Cat with a gold coin. Fast. SUPER zaps everyone on screen!"),
    FOXFIRE("FOXFIRE", 0xFFE8603A.toInt(), "FIRE SPIN", "Fox with six curly tails. SUPER spins fire all around!"),
    FIREPUP("FIREPUP", 0xFFF08A3A.toInt(), "FLARE BLITZ", "Striped fire puppy. SUPER: a ring of fire!"),
    BLOBBY("BLOBBY", 0xFFC8A0E8.toInt(), "TRANSFORM", "Purple goo that copies things. SUPER: a big squishy hit!"),
    SEASONG("SEASONG", 0xFF5AA0E8.toInt(), "ICE BEAM", "Gentle sea ride with a shell. Swims. SUPER blasts both ways!"),
    PENGI("PENGI", 0xFF4A8AD8.toInt(), "BUBBLE", "Little blue penguin. Swims great. SUPER blasts bubbles!"),
}

/** The first-form Pokemon whose moves (SUPER, speed, jumps) an evolution uses. */
val Mon.base: Mon get() = when (this) {
    Mon.SCORCH -> Mon.EMBER
    Mon.TIDAL -> Mon.SPLASHY
    Mon.SEEDY, Mon.SPROUT -> Mon.BLOOM
    Mon.ZAPPER -> Mon.SPARKY
    Mon.BRAWLER, Mon.MEGAPUNCH -> Mon.PUNCHO
    Mon.SHADE, Mon.PHANTOM -> Mon.SPOOKY
    Mon.PSYKO -> Mon.MYSTIC
    Mon.EEVO, Mon.MEOWZER -> Mon.SPARKY
    Mon.RIBBON, Mon.PUFFLE, Mon.PSYQUACK, Mon.EGGBABY -> Mon.MYSTIC
    Mon.NIGHTFUR, Mon.MIMIC -> Mon.SPOOKY
    Mon.SNOOZER, Mon.LUKO, Mon.BLOBBY -> Mon.PUNCHO
    Mon.SHURIKEN, Mon.FLOPFISH, Mon.PENGI -> Mon.SPLASHY
    Mon.RAYZOR -> Mon.LUGIA
    Mon.DRAGOBUD -> Mon.BLAZE
    Mon.RAGEFISH, Mon.SEASONG -> Mon.HYDRO
    Mon.FOXFIRE, Mon.FIREPUP -> Mon.EMBER
    else -> this
}

/** Evolution lines, first form first. All forms in a line share one level. */
val MON_LINES: List<List<Mon>> = listOf(
    listOf(Mon.EMBER, Mon.SCORCH, Mon.BLAZE),
    listOf(Mon.SPLASHY, Mon.TIDAL, Mon.HYDRO),
    listOf(Mon.SEEDY, Mon.SPROUT, Mon.BLOOM),
    listOf(Mon.SPARKY, Mon.ZAPPER),
    listOf(Mon.PUNCHO, Mon.BRAWLER, Mon.MEGAPUNCH),
    listOf(Mon.SPOOKY, Mon.SHADE, Mon.PHANTOM),
    listOf(Mon.MYSTIC, Mon.PSYKO),
    listOf(Mon.LUGIA),
    listOf(Mon.EEVO, Mon.RIBBON),
    listOf(Mon.FLOPFISH, Mon.RAGEFISH),
) + NEW_SINGLES.map { listOf(it) }

/** Newer Pokemon with no evolution. */
private val NEW_SINGLES get() = listOf(Mon.NIGHTFUR, Mon.SNOOZER, Mon.PUFFLE, Mon.PSYQUACK, Mon.LUKO, Mon.SHURIKEN, Mon.MIMIC,
    Mon.RAYZOR, Mon.DRAGOBUD, Mon.EGGBABY, Mon.MEOWZER, Mon.FOXFIRE, Mon.FIREPUP, Mon.BLOBBY, Mon.SEASONG, Mon.PENGI)

/** The newer first forms in the order they join the team (one every [NEW_MON_EVERY] levels beaten). */
val NEW_MONS: List<Mon> = listOf(Mon.EEVO, Mon.PUFFLE, Mon.MEOWZER, Mon.FLOPFISH, Mon.FIREPUP, Mon.PSYQUACK, Mon.SNOOZER,
    Mon.PENGI, Mon.EGGBABY, Mon.LUKO, Mon.FOXFIRE, Mon.SHURIKEN, Mon.NIGHTFUR, Mon.BLOBBY, Mon.DRAGOBUD, Mon.MIMIC,
    Mon.SEASONG, Mon.RAYZOR)
const val NEW_MON_EVERY = 3

val Mon.line: List<Mon> get() = MON_LINES.first { this in it }
/** The first form of this Pokemon's line (its level is kept there). */
val Mon.root: Mon get() = line.first()
/** 1 for a first form, 2 and 3 for evolutions. */
val Mon.stage: Int get() = line.indexOf(this) + 1
/** The level a line has to reach before this form can be used (the first 10 Pokemon are always there). */
val Mon.evolveLevel: Int get() = when (this) {
    Mon.SCORCH, Mon.TIDAL, Mon.SPROUT, Mon.BRAWLER, Mon.SHADE -> 4
    Mon.ZAPPER, Mon.RIBBON, Mon.RAGEFISH -> 5
    Mon.PSYKO -> 6
    Mon.MEGAPUNCH, Mon.PHANTOM -> 7
    else -> 1
}
/** The Pokemon this one can evolve into next, if any. */
val Mon.nextForm: Mon? get() = line.getOrNull(line.indexOf(this) + 1)

/** Pokemon that are happy in the water: the team's air lasts twice as long. */
val Mon.swimmer get() = base == Mon.HYDRO || base == Mon.SPLASHY || this == Mon.LUGIA || this == Mon.TIDAL

/** Pokemon that float down slowly while JUMP is held. */
val Mon.floaty get() = base == Mon.BLAZE || base == Mon.MYSTIC || base == Mon.SPOOKY || this == Mon.LUGIA

/**
 * Blocky's swords. Each one is crafted from its material and hits harder than the last.
 * Gold swings fast, diamond and amethyst sweep every bad guy in reach, and amethyst
 * also shoots a magic slash.
 */
enum class Sword(
    val label: String, val ore: Ore?, val cost: Int, val damage: Int, val reach: Float, val cooldown: Float, val blade: Int,
    val note: String, val durability: Int,
) {
    NONE("NO SWORD", null, 0, 0, 16f, 0.24f, 0, "", 0),
    STONE("STONE SWORD", Ore.STONE, 8, 2, 22f, 0.26f, 0xFF9A9A9A.toInt(), "A good start: 2 damage", 25),
    IRON("IRON SWORD", Ore.IRON, 5, 3, 24f, 0.24f, 0xFFE0E0E8.toInt(), "Strong and long: 3 damage", 40),
    GOLD("GOLD SWORD", Ore.GOLD, 5, 4, 24f, 0.14f, 0xFFFFD82A.toInt(), "Super fast but wears out fast: 4", 30),
    DIAMOND("DIAMOND SWORD", Ore.DIAMOND, 5, 6, 28f, 0.24f, 0xFF7FF4F0.toInt(), "Sweeps every bad guy in reach: 6", 60),
    AMETHYST("AMETHYST SWORD", Ore.AMETHYST, 5, 8, 30f, 0.24f, 0xFFC890FF.toInt(), "Sweeps AND shoots magic slashes: 8", 70),
    RUBY("RUBY SWORD", Ore.RUBY, 6, 7, 26f, 0.12f, 0xFFFF3A5A.toInt(), "The fastest sword of all: 7", 50),
    OBSIDIAN("OBSIDIAN SWORD", Ore.OBSIDIAN, 6, 11, 32f, 0.26f, 0xFF7A5AB0.toInt(), "Heavy! Sweeps everything: 11", 90),
    ;

    val sweeps get() = this == DIAMOND || this == AMETHYST || this == OBSIDIAN
}

/**
 * Armor the team can wear. Every hit wears it down instead of costing a heart, and it
 * breaks when it's used up. It takes a lot of ore to make, so it's a real prize.
 */
enum class Armor(val label: String, val ore: Ore?, val cost: Int, val durability: Int, val color: Int) {
    NONE("NO ARMOR", null, 0, 0, 0),
    IRON("IRON ARMOR", Ore.IRON, 12, 3, 0xFFD8D8E0.toInt()),
    GOLD("GOLD ARMOR", Ore.GOLD, 12, 3, 0xFFFFD82A.toInt()),
    DIAMOND("DIAMOND ARMOR", Ore.DIAMOND, 12, 4, 0xFF7FF4F0.toInt()),
    AMETHYST("AMETHYST ARMOR", Ore.AMETHYST, 12, 5, 0xFFC890FF.toInt()),
    SAPPHIRE("SAPPHIRE ARMOR", Ore.SAPPHIRE, 12, 6, 0xFF4A7AFF.toInt()),
    OBSIDIAN("OBSIDIAN ARMOR", Ore.OBSIDIAN, 12, 8, 0xFF5A3A8A.toInt()),
}

/**
 * A shield for Rocco or Blocky. It blocks any hit coming from the way you're facing
 * (arrows, fire, bad guys bumping into you) and wears down a little each time.
 */
enum class Shield(val label: String, val costs: List<Pair<Ore, Int>>, val durability: Int, val color: Int, val rim: Int) {
    NONE("NO SHIELD", emptyList(), 0, 0, 0),
    STONE("STONE SHIELD", listOf(Ore.STONE to 12), 2, 0xFF9A9A9A.toInt(), 0xFF5A5A5A.toInt()),
    IRON("IRON SHIELD", listOf(Ore.IRON to 8), 3, 0xFFE0E0E8.toInt(), 0xFF8A8A9A.toInt()),
    RUBY("RUBY SHIELD", listOf(Ore.RUBY to 6, Ore.IRON to 4), 4, 0xFFFF3A5A.toInt(), 0xFFFFD82A.toInt()),
    SAPPHIRE("SAPPHIRE SHIELD", listOf(Ore.SAPPHIRE to 7, Ore.GOLD to 4), 5, 0xFF4A7AFF.toInt(), 0xFFFFD82A.toInt()),
    OBSIDIAN("OBSIDIAN SHIELD", listOf(Ore.OBSIDIAN to 7, Ore.DIAMOND to 3), 6, 0xFF3A2460.toInt(), 0xFFC890FF.toInt()),
}

enum class Who { ROCCO, BLOCKY, MON }

abstract class Thing(x: Float, y: Float, w: Float, h: Float) : Body(x, y, w, h) {
    var removed = false
    var active = false
    abstract fun update(g: TeamGame, dt: Float)
    abstract fun draw(g: TeamGame, gfx: Gfx, ox: Float)
}

internal fun approach(v: Float, target: Float, step: Float) =
    if (v < target) min(v + step, target) else max(v - step, target)

internal fun signOf(v: Float) = sign(v).toInt()

/** [rainbow] gives a bare RGB color; this adds full opacity. */
fun solidRainbow(t: Float, offset: Float = 0f) = argb(255, rainbow(t, offset))

// ============================================================================ Heroes

class Hero(val who: Who, x: Float, y: Float) :
    Body(x, y, if (who == Who.MON) 14f else 12f, if (who == Who.MON) 18f else 23f) {
    var facing = 1
    var anim = 0f
    /** Counts down while the pickaxe or an attack animation is playing. */
    var swing = 0f
    var cooldown = 0f
    var jumpCooldown = 0f
    var stuck = 0f
    var crouching = false
    /** Butt slam: a short pause in the air, then a fast drop. */
    var slamming = false
    var slamPause = 0f
    var longJumping = false
    /** Rocco can jump once more in mid-air; this is set until he lands. */
    var doubleJumped = false
    /** Stomps in a row without touching the ground. */
    var combo = 0
    /** Counts down while the double-jump flip is showing. */
    var flipT = 0f
    /** Squash-and-stretch: counts down after landing. */
    var squash = 0f
    var dustT = 0f
    var sprinting = false
    /** Rocco's blue shell spin dash. */
    var shellSpin = false
    /** Blocky's last swing was with his sword (not the pickaxe). */
    var swordSwing = false
    /** Rocco ate a Mini Mushroom: tiny and floaty. */
    var mini = false
    /** Which Pokemon this is, when [who] is [Who.MON]. */
    var mon = Mon.SPARKY

    val label get() = when (who) {
        Who.ROCCO -> "ROCCO"
        Who.BLOCKY -> "BLOCKY"
        Who.MON -> mon.label
    }

    val color get() = when (who) {
        Who.ROCCO -> 0xFFE52521.toInt()
        Who.BLOCKY -> 0xFF2AA7A7.toInt()
        Who.MON -> mon.color
    }

    val speed get() = when (who) {
        Who.ROCCO -> 120f
        Who.BLOCKY -> 108f
        Who.MON -> when (mon.base) { Mon.SPARKY, Mon.EMBER -> 128f; Mon.BLAZE, Mon.MYSTIC, Mon.SPOOKY, Mon.LUGIA -> 115f; else -> 100f }
    }

    /** Rocco jumps higher; Blocky is the strong one. */
    val jumpV get() = when (who) {
        Who.ROCCO -> -335f
        Who.BLOCKY -> -305f
        Who.MON -> when (mon.base) { Mon.SPARKY, Mon.EMBER, Mon.MYSTIC -> -340f; Mon.BLAZE, Mon.PUNCHO, Mon.LUGIA -> -310f; else -> -295f }
    }

    fun draw(g: TeamGame, gfx: Gfx, ox: Float) {
        val flicker = this === g.leader && g.invuln > 0 && g.starT <= 0 && (g.invuln * 20).toInt() % 2 == 0
        if (flicker) return
        val walking = onGround && abs(vx) > 10 && (anim * 10).toInt() % 2 == 0
        val px = x - 2 + ox
        val py = bottom - 24
        if (g.starT > 0) {
            gfx.oval(px - 3, py - 3, w + 10, h + 8, argb(110, rainbow(g.time * 2)))
        }
        when (who) {
            Who.ROCCO -> {
                // Metal Rocco is all shiny silver.
                fun rs(f: Int) = if (g.metal) Metal.of(TeamArt.rocco(g.power, f)) else TeamArt.rocco(g.power, f)
                val frame = if (!onGround) 2 else if (walking) 1 else 0
                if (g.capture != null) { g.drawCapture(gfx, this, ox); return }
                if (g.megaT > 0f) {
                    // MEGA: twice as big, growing up from the feet.
                    val flash = g.megaT < 2f && (g.megaT * 8).toInt() % 2 == 0
                    gfx.sprite(rs(frame), px - 8, bottom - 48, flipX = facing < 0, w = 32f, h = 48f)
                    if (flash) gfx.rect(px - 8, bottom - 48, 32f, 48f, 0x55FFFFFF)
                    return
                }
                if (g.propT > 0f && !mini) PropellerItem.drawPropeller(gfx, cx + ox, bottom - 24, g.time)
                if (g.dino != null) {
                    // Riding Dash: Dash runs along below, Rocco sits in the saddle.
                    Dino.drawBody(gfx, cx + ox, bottom, facing, anim, walking || !onGround)
                    gfx.sprite(rs(if (onGround) 0 else 2), px + 1 - facing * 3, bottom - 42, flipX = facing < 0)
                    return
                }
                when {
                    mini -> {
                        val s = if (!onGround) com.roccoquest.game.Sprites.smallJump else if (walking) com.roccoquest.game.Sprites.smallWalk else com.roccoquest.game.Sprites.smallStand
                        gfx.sprite(s, x - 1.5f + ox, bottom - 10, flipX = facing < 0, w = 10f, h = 10f)
                    }
                    shellSpin -> {
                        // Tucked into the blue shell, spinning along.
                        val spin = (anim * 24).toInt() % 2 == 0
                        gfx.sprite(com.roccoquest.game.Sprites.blueShell, px, bottom - 16, flipX = spin)
                        gfx.rect(px - facing * 8 + 4, bottom - 9, 7f, 1.5f, 0x99FFFFFF.toInt())
                    }
                    // Squatting: squash the sprite down.
                    crouching -> gfx.sprite(rs(0), px, bottom - 15, flipX = facing < 0, h = 15f)
                    // Butt slam: tucked in, spinning, with speed lines.
                    slamming -> {
                        gfx.sprite(rs(2), px, py + 4, flipX = (anim * 20).toInt() % 2 == 0, h = 20f)
                        if (slamPause <= 0) for (i in 0 until 3) gfx.rect(px + 2 + i * 5, py - 10, 1.5f, 10f, 0xAAFFFFFF.toInt())
                    }
                    // Double jump: a quick spinning flip.
                    flipT > 0 && !onGround -> {
                        val spin = (flipT * 24).toInt() % 4
                        gfx.sprite(rs(2), px, py, flipX = spin >= 2, flipY = spin == 1 || spin == 2)
                    }
                    // Long jump: stretched out flat like a superhero.
                    longJumping && !onGround -> {
                        gfx.sprite(rs(2), px - 3, py + 6, flipX = facing < 0, w = 22f, h = 18f)
                        gfx.rect(px - facing * 10 + 4, py + 14, 8f, 1.5f, 0x99FFFFFF.toInt())
                    }
                    else -> {
                        // Squash when landing, stretch when jumping up fast (like the newer games).
                        val k = if (squash > 0f) squash * 1.6f else if (!onGround && vy < -150f) -minOf(0.12f, -vy / 3000f) else 0f
                        gfx.sprite(rs(frame), px - 8f * k, py + 24f * k, flipX = facing < 0, w = 16f * (1 + k), h = 24f * (1 - k))
                    }
                }
                if (!mini && !shellSpin) {
                    if (g.tanookiT > 0) TeamArt.tanooki(gfx, cx + ox, py, bottom, facing, anim, swing > 0, crouching)
                    if (g.wingT > 0) TeamArt.wingCap(gfx, cx + ox, if (crouching) bottom - 16 else py, anim, !onGround)
                }
                if (swing > 0 && g.tanookiT <= 0) {
                    val hx = cx + facing * 8 + ox
                    gfx.oval(hx - 2, y + 11, 4f, 4f, 0xFFFFC08A.toInt())
                }
            }
            Who.BLOCKY -> {
                gfx.sprite(TeamArt.blocky(g.power, walking || !onGround), px, py, flipX = facing < 0)
                val p = if (swing > 0) 1f - swing / 0.22f else 0f
                val base = if (g.digDown && swing > 0) 0.6f else 0f
                val a = if (swing > 0) -1.9f + p * 2.6f + base else -2.3f
                val hx = cx + facing * 4 + ox
                val angle = if (facing > 0) a else (Math.PI.toFloat() - a)
                if (swordSwing && swing > 0) TeamArt.sword(gfx, hx, y + 13, angle, g.sword)
                else TeamArt.pickaxe(gfx, hx, y + 13, angle, g.pickTier)
                // His sword hangs on his back when he isn't using it.
                if (g.sword != Sword.NONE && !(swordSwing && swing > 0)) {
                    TeamArt.sword(gfx, cx - facing * 3 + ox, y + 20, if (facing > 0) -1.2f else (Math.PI.toFloat() + 1.2f), g.sword)
                }
            }
            Who.MON -> TeamArt.mon(gfx, mon, cx + ox, bottom, g.time + x * 0.01f, facing, swing > 0 || g.superFlash > 0, walking)
        }
        // Worn armor: a helmet and a chest plate in the armor's color.
        if (g.armorType != Armor.NONE && who != Who.MON && !mini && !shellSpin && !crouching) {
            val c = (g.armorType.color and 0x00FFFFFF) or 0xE0000000.toInt()
            gfx.rect(px + 4, py - 1, 9f, 3f, c)
            gfx.rect(px + 3, py + 1, 2f, 4f, c)
            gfx.rect(px + 4, py + 9, 9f, 5f, c)
            gfx.rect(px + 5, py + 10, 2f, 1f, 0x88FFFFFF.toInt())
        }
        if (g.armor > 0 && who != Who.MON && (g.time * 4).toInt() % 2 == 0) {
            gfx.oval(px - 1, py - 1, 18f, 26f, argb(40, 0x2EB84A))
        }
    }
}
