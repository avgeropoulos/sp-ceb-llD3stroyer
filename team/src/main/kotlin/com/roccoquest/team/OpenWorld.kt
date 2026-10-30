package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.Voice
import com.roccoquest.game.argb
import com.roccoquest.game.mixColor
import com.roccoquest.game.shadowText
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * 3D OPEN WORLDS: one big 3D land to explore for each world (the 3D WORLD button on the map). Run around with
 * Rocco's parkour (jump, vault, wall jumps, flips), collect the 8 SHINE STARS (on towers, between tall walls,
 * on floating islands reached by moving platforms), grab coins, stomp bad guys and find wild Pokemon.
 * Each world is built from a seed, so it's always the same. Collected stars are saved per file (`ow<world>`).
 */
class OpenWorld(private val g: TeamGame) {
    companion object {
        const val SIZE = 64f
        const val STARS = 8
        private const val G3 = 22f
        private const val JUMP_V = 7.6f
        private const val NEAR = 0.08f
        /** Pixels per block for the 2D pictures (bad guys, Rocco, trees) standing in the 3D world. */
        private const val PPB = 16f
        private const val VIEW_DIST = 30f
    }

    /** Colors and stuff for each world. [tree]: 0 round trees, 1 pine, 2 cactus, 3 palm, 4 mushroom, 5 crystal, 6 dead tree, 7 lollipop. */
    class Theme(val name: String, val skyTop: Int, val skyBottom: Int, val ground: Int, val ground2: Int, val box: Int, val boxTop: Int,
                val hill: Int, val tree: Int, val gravity: Float = 1f, val night: Boolean = false)

    private val themes = listOf(
        Theme("GRASSY LAND", 0xFF4AA4F0.toInt(), 0xFFD8F0FF.toInt(), 0xFF5AB84A.toInt(), 0xFF4EA840.toInt(), 0xFFB98A52.toInt(), 0xFF6AC85A.toInt(), 0xFF7AC86A.toInt(), 0),
        Theme("NIGHT LAND", 0xFF120A30.toInt(), 0xFF4A3A7A.toInt(), 0xFF3A6A4A.toInt(), 0xFF2E5A3E.toInt(), 0xFF5A4A7A.toInt(), 0xFF6A5A9A.toInt(), 0xFF2A3A5A.toInt(), 6, night = true),
        Theme("DESERT LAND", 0xFF6AB8F0.toInt(), 0xFFFCE8B8.toInt(), 0xFFE8C87A.toInt(), 0xFFD8B86A.toInt(), 0xFFC8904A.toInt(), 0xFFF0D08A.toInt(), 0xFFE0B070.toInt(), 2),
        Theme("ISLAND LAND", 0xFF3AA8F0.toInt(), 0xFFC8F0FF.toInt(), 0xFFF4E0A0.toInt(), 0xFFE8D090.toInt(), 0xFF8A6A4A.toInt(), 0xFF5AC86A.toInt(), 0xFF4AB8C8.toInt(), 3),
        Theme("SEA LAND", 0xFF2A8AD8.toInt(), 0xFFBFE8FF.toInt(), 0xFF3AB8C8.toInt(), 0xFF2EA8B8.toInt(), 0xFFA87A4A.toInt(), 0xFFF4E0A0.toInt(), 0xFF2A7AC8.toInt(), 3),
        Theme("SPOOKY LAND", 0xFF0A0418.toInt(), 0xFF3A1A4A.toInt(), 0xFF2A2A3A.toInt(), 0xFF222232.toInt(), 0xFF4A3A44.toInt(), 0xFF6A5A7A.toInt(), 0xFF1A1428.toInt(), 6, night = true),
        Theme("SNOW LAND", 0xFF8AC8F0.toInt(), 0xFFF0FAFF.toInt(), 0xFFF4F8FF.toInt(), 0xFFE0ECF8.toInt(), 0xFF8AA8C8.toInt(), 0xFFFFFFFF.toInt(), 0xFFD0E4F4.toInt(), 1),
        Theme("TOY LAND", 0xFF6AC8FF.toInt(), 0xFFFFF0F8.toInt(), 0xFFF4C83A.toInt(), 0xFFE8B82A.toInt(), 0xFFE84A4A.toInt(), 0xFF3A6AD8.toInt(), 0xFFFF8A8A.toInt(), 7),
        Theme("MOON LAND", 0xFF02020A.toInt(), 0xFF1A1A30.toInt(), 0xFF9A9AA8.toInt(), 0xFF8A8A98.toInt(), 0xFF6A6A7A.toInt(), 0xFFB8B8C8.toInt(), 0xFF5A5A6A.toInt(), 5, gravity = 0.6f, night = true),
        Theme("VOLCANO LAND", 0xFF3A1A1A.toInt(), 0xFFE86A2A.toInt(), 0xFF4A3A38.toInt(), 0xFF3A2A28.toInt(), 0xFF2A1A1A.toInt(), 0xFFE8581A.toInt(), 0xFF6A2A1A.toInt(), 6),
        Theme("DINO LAND", 0xFFF0A860.toInt(), 0xFFFCE8B8.toInt(), 0xFF5AA84A.toInt(), 0xFF4A983A.toInt(), 0xFFA8704A.toInt(), 0xFF6AB85A.toInt(), 0xFF8A9A6A.toInt(), 4),
        Theme("CRYSTAL PLANET", 0xFF4A2A8A.toInt(), 0xFFE0C8FF.toInt(), 0xFFB88AF0.toInt(), 0xFFA87AE0.toInt(), 0xFF6A4AA8.toInt(), 0xFFE0C8FF.toInt(), 0xFF8A5AD8.toInt(), 5, gravity = 0.8f),
    )

    /** A box: a block of land from [bottom] up to [top] (floating islands have a bottom above 0). */
    class Box(val x0: Float, val z0: Float, val x1: Float, val z1: Float, val top: Float, val bottom: Float = 0f, val color: Int, val topColor: Int)
    class Spot(var x: Float, var y: Float, var z: Float) { var taken = false }
    class Foe3(var x: Float, var z: Float, val look: Foe, val homeX: Float, val homeZ: Float) {
        var feet = 0f; var vy = 0f; var dir = 0f; var t = 0f; var alive = true; var squashT = 0f
    }
    class Wild(var x: Float, var z: Float, val mon: Mon) { var found = false; var t = 0f }
    class Mover(val x0: Float, val z0: Float, val top: Float, val alongX: Boolean, val range: Float) {
        var o = 0f; var dx = 0f; var dz = 0f
        val x get() = x0 + (if (alongX) o else 0f)
        val z get() = z0 + (if (alongX) 0f else o)
    }

    var world = 0
        private set
    val theme get() = themes[world.coerceIn(0, themes.lastIndex)]
    val boxes = ArrayList<Box>()
    val coins = ArrayList<Spot>()
    val stars = ArrayList<Spot>()
    val foes = ArrayList<Foe3>()
    val wilds = ArrayList<Wild>()
    val movers = ArrayList<Mover>()
    private val trees = ArrayList<Spot>()

    // The player (Rocco)
    var x = 32f; var z = 32f; var feet = 0f; var vy = 0f
    var onGround = true
        private set
    var yaw = -Math.PI.toFloat() / 2
    var pitch = -0.18f
    var hearts = 5
        private set
    private var invuln = 0f
    var flipT = 0f
        private set
    private var flipDur = 0.5f
    var vaultT = 0f
        private set
    var rollT = 0f
        private set
    private var kickX = 0f
    private var kickZ = 0f
    private var jumps = 0
    private var walking = false
    private var faceDir = 1
    private var punchT = 0f
    private var t = 0f
    private var monX = 31f
    private var monZ = 33f
    var starsGot = 0
        private set
    private var bannerT = 0f
    private var banner = ""

    // ---------------------------------------------------------------- Building a world

    fun start(w: Int) {
        world = w
        build()
        x = SIZE / 2; z = SIZE - 4f; feet = 0f; vy = 0f; onGround = true
        yaw = -Math.PI.toFloat() / 2; pitch = -0.18f
        hearts = 5; invuln = 0f; kickX = 0f; kickZ = 0f
        monX = x - 1f; monZ = z + 1f
        val bits = g.slotLoad("ow$w")
        for ((i, s) in stars.withIndex()) s.taken = bits and (1 shl i) != 0
        starsGot = stars.count { it.taken }
        showBanner("${theme.name}!  Find the 8 SHINE STARS!")
    }

    private fun showBanner(s: String) { banner = s; bannerT = 3f }

    private fun build() {
        val r = Random(5000 + world * 97)
        val th = theme
        boxes.clear(); coins.clear(); stars.clear(); foes.clear(); wilds.clear(); movers.clear(); trees.clear()
        fun box(x0: Float, z0: Float, w: Float, d: Float, top: Float, bottom: Float = 0f, c: Int = th.box, tc: Int = th.boxTop) {
            boxes += Box(x0, z0, x0 + w, z0 + d, top, bottom, c, tc)
        }
        fun clearOfBoxes(px: Float, pz: Float, pad: Float) = boxes.none { b -> px > b.x0 - pad && px < b.x1 + pad && pz > b.z0 - pad && pz < b.z1 + pad }
        fun freeSpot(pad: Float): Pair<Float, Float> {
            repeat(60) {
                val px = 4f + r.nextFloat() * (SIZE - 8f); val pz = 4f + r.nextFloat() * (SIZE - 12f)
                if (clearOfBoxes(px, pz, pad) && hypot(px - SIZE / 2, pz - (SIZE - 4f)) > 6f) return px to pz
            }
            return 8f + r.nextFloat() * 48f to 8f + r.nextFloat() * 40f
        }
        // 1-2) Stepped towers: climb the steps (vault!) to a star on top.
        repeat(2) {
            val (cx, cz) = freeSpot(6f)
            val h = 4 + r.nextInt(3)
            for (k in 0 until h) {
                val s = 5f - k * 0.7f
                box(cx - s / 2, cz - s / 2, s, s, k + 1f, c = mixColor(th.box, 0xFF000000.toInt(), k * 0.04f))
            }
            stars += Spot(cx, h + 0.8f, cz)
        }
        // 3) A wall-jump chimney: two tall walls close together, a star up at the top.
        run {
            val (cx, cz) = freeSpot(5f)
            box(cx - 2.6f, cz - 2f, 1f, 4f, 7f, c = th.hill, tc = th.boxTop)
            box(cx + 1.6f, cz - 2f, 1f, 4f, 7f, c = th.hill, tc = th.boxTop)
            box(cx - 1.6f, cz + 1.6f, 3.2f, 0.4f, 0.6f)
            stars += Spot(cx, 7.6f, cz)
        }
        // 4-5) Floating islands, reached by riding a moving platform.
        repeat(2) {
            val (cx, cz) = freeSpot(7f)
            val ih = 3.5f + r.nextInt(2)
            box(cx + 2f, cz - 2f, 4f, 4f, ih, ih - 1f)
            val alongX = true
            movers += Mover(cx - 4f, cz - 1f, ih - 0.6f, alongX, 4.5f)
            box(cx - 6.5f, cz - 1.5f, 2.2f, 3f, ih - 1.4f)
            stars += Spot(cx + 4f, ih + 0.8f, cz)
            for (k in 0 until 5) coins += Spot(cx - 3f + k * 1.2f, ih + 0.2f, cz)
        }
        // 6) Up on a big hill (rings of steps).
        run {
            val (cx, cz) = freeSpot(8f)
            for (k in 0 until 3) box(cx - 6f + k * 1.5f, cz - 6f + k * 1.5f, 12f - k * 3f, 12f - k * 3f, k + 1f, c = th.hill, tc = mixColor(th.ground, 0xFFFFFFFF.toInt(), 0.1f * k))
            stars += Spot(cx, 3.8f, cz)
        }
        // 7) Hidden behind a big block maze.
        run {
            val (cx, cz) = freeSpot(6f)
            box(cx - 4f, cz - 4f, 8f, 1f, 2.4f); box(cx - 4f, cz + 3f, 8f, 1f, 2.4f); box(cx - 4f, cz - 3f, 1f, 6f, 2.4f)
            box(cx + 3f, cz - 3f, 1f, 2f, 2.4f); box(cx + 3f, cz + 1f, 1f, 2f, 2.4f)
            stars += Spot(cx, 0.9f, cz)
        }
        // 8) A star floating high above the middle: jump from the top of a tall pillar.
        run {
            val (cx, cz) = freeSpot(5f)
            for (k in 0 until 4) box(cx - 3f + k * 1.6f, cz - 0.6f, 1.2f, 1.2f, k + 1.5f)
            stars += Spot(cx + 4f, 6.8f, cz)
        }
        // Extra blocks to play on, coins, trees.
        repeat(14) {
            val (px, pz) = freeSpot(3f)
            val w = 1f + r.nextInt(3); val d = 1f + r.nextInt(3)
            val h = 1f + r.nextInt(2)
            box(px, pz, w, d, h)
            if (r.nextBoolean()) coins += Spot(px + w / 2, h + 0.7f, pz + d / 2)
        }
        repeat(8) {
            val (cx, cz) = freeSpot(3f)
            for (k in 0 until 6) coins += Spot(cx + cos(k * 1.05f) * 2f, 0.7f, cz + sin(k * 1.05f) * 2f)
        }
        repeat(26) { val (px, pz) = freeSpot(1.5f); trees += Spot(px, 0f, pz) }
        // Bad guys and wild Pokemon.
        repeat(10) {
            val (px, pz) = freeSpot(2f)
            foes += Foe3(px, pz, foeLook(r.nextInt(4)), px, pz)
        }
        val pool = Mon.entries.filter { it.stage == 1 && it != Mon.LUGIA }
        repeat(3) {
            val (px, pz) = freeSpot(2f)
            wilds += Wild(px, pz, pool[(world * 7 + it * 13 + r.nextInt(pool.size)) % pool.size])
        }
    }

    /** The bad guys each world uses (drawn with their normal 2D pictures). */
    private fun foeLook(k: Int): Foe = when (world) {
        6 -> listOf(Snowman(0f, 0f), Penguin(0f, 0f), Grumbler(0f, 0f, false), Hopper(0f, 0f))[k]
        7 -> listOf(ToySoldier(0f, 0f), Gummy(0f, 0f), Grumbler(0f, 0f, false), Hopper(0f, 0f))[k]
        8, 11 -> listOf(MoonHopper(0f, 0f), ToySoldier(0f, 0f), Grumbler(0f, 0f, false), MoonHopper(0f, 0f))[k]
        10 -> listOf(Raptor(0f, 0f), BabyRex(0f, 0f), Triceratops(0f, 0f), Raptor(0f, 0f))[k]
        1, 5 -> listOf(Bonehead(0f, 0f), Grumbler(0f, 0f, false), Hopper(0f, 0f), Gloop(0f, 0f))[k]
        else -> listOf(Grumbler(0f, 0f, false), Shellback(0f, 0f), Hopper(0f, 0f), Gloop(0f, 0f))[k]
    }

    // ---------------------------------------------------------------- Moving around (the same parkour as the 3D house)

    private fun inBox(b: Box, px: Float, pz: Float, pad: Float) = px > b.x0 - pad && px < b.x1 + pad && pz > b.z0 - pad && pz < b.z1 + pad

    /** True if the body (feet at [f], 1.7 tall, 0.4 wide) would bump into a box at [px], [pz]. */
    private fun solidAt(px: Float, pz: Float, f: Float = feet): Boolean =
        boxes.any { b -> inBox(b, px, pz, 0.2f) && f + 0.05f < b.top && f + 1.6f > b.bottom }

    private fun groundUnder(px: Float, pz: Float, f: Float): Float {
        var best = 0f
        for (b in boxes) if (inBox(b, px, pz, 0.15f) && b.top <= f + 0.3f && b.top > best) best = b.top
        for (m in movers) if (px > m.x - 0.2f && px < m.x + 2.2f && pz > m.z - 0.2f && pz < m.z + 2.2f && f >= m.top - 0.35f && m.top > best) best = m.top
        return best
    }

    private fun riding(): Mover? = movers.firstOrNull { m -> onGround && abs(feet - m.top) < 0.05f && x > m.x - 0.2f && x < m.x + 2.2f && z > m.z - 0.2f && z < m.z + 2.2f }

    private fun tryMove(nx0: Float, nz0: Float): Boolean {
        val nx = nx0.coerceIn(0.5f, SIZE - 0.5f)
        val nz = nz0.coerceIn(0.5f, SIZE - 0.5f)
        var ok = true
        if (!solidAt(nx, z)) x = nx else ok = false
        if (!solidAt(x, nz)) z = nz else ok = false
        return ok
    }

    /** One frame. [hop] = JUMP pressed, [punch] = PUNCH pressed, [spin] = SPIN pressed. Returns true to leave. */
    fun update(dt: Float, input: TeamInput, hop: Boolean, punch: Boolean, spin: Boolean): Boolean {
        t += dt
        if (bannerT > 0f) bannerT -= dt
        if (invuln > 0f) invuln -= dt
        if (flipT > 0f) flipT -= dt
        if (vaultT > 0f) vaultT -= dt
        if (rollT > 0f) rollT -= dt
        if (punchT > 0f) punchT -= dt
        if (input.left) { yaw -= 2.2f * dt; faceDir = -1 }
        if (input.right) { yaw += 2.2f * dt; faceDir = 1 }
        if (input.lookUp) pitch = (pitch + 1.2f * dt).coerceAtMost(0.7f)
        if (input.lookDown) pitch = (pitch - 1.2f * dt).coerceAtLeast(-0.9f)
        // Moving platforms
        val ride = riding()
        for ((i, m) in movers.withIndex()) {
            val o = m.range / 2f - m.range / 2f * cos(t * 0.9f + i)
            val before = m.o
            m.o = o
            m.dx = if (m.alongX) o - before else 0f; m.dz = if (m.alongX) 0f else o - before
        }
        ride?.let { tryMove(x + it.dx, z + it.dz) }
        val move = (if (input.up) 1f else 0f) - (if (input.down) 1f else 0f)
        walking = move != 0f
        var blockedAhead = false
        if (move != 0f) {
            val sp = 4.2f * move * dt
            blockedAhead = !tryMove(x + cos(yaw) * sp, z + sin(yaw) * sp)
            // VAULT up onto anything about one block high.
            if (blockedAhead && onGround && move > 0f && vaultT <= 0f) {
                val fx = x + cos(yaw) * 0.6f; val fz = z + sin(yaw) * 0.6f
                val step = boxes.filter { inBox(it, fx, fz, 0.2f) && it.top > feet + 0.05f }.minOfOrNull { it.top } ?: 99f
                if (step - feet <= 1.05f && !solidAt(fx, fz, step)) { vy = 7.4f; onGround = false; vaultT = 0.4f; g.sfx(Sound.JUMP) }
            }
        }
        if (abs(kickX) + abs(kickZ) > 0.01f) { tryMove(x + kickX * dt, z + kickZ * dt); kickX *= 0.9f; kickZ *= 0.9f }
        val grav = G3 * theme.gravity
        if (hop) {
            if (onGround) {
                vy = JUMP_V * (if (theme.gravity < 1f) 0.95f else 1f); onGround = false; g.sfx(Sound.JUMP)
                if (walking && ++jumps % 3 == 0) { flipT = 0.55f; flipDur = 0.55f; showBanner("FLIP!") }
            } else if (blockedAhead || solidAt(x + cos(yaw) * 0.45f, z + sin(yaw) * 0.45f)) {
                vy = JUMP_V; kickX = -cos(yaw) * 6f; kickZ = -sin(yaw) * 6f
                flipT = 0.5f; flipDur = 0.5f
                g.sfx(Sound.JUMP)
                showBanner("WALL JUMP!")
            }
        }
        val wasGround = onGround
        val fall = vy
        if (!onGround && vy < 0f && blockedAhead) vy = maxOf(vy, -2.5f)
        vy -= grav * dt
        var nf = feet + vy * dt
        val ground = groundUnder(x, z, feet)
        if (nf <= ground) { nf = ground; vy = 0f; onGround = true } else onGround = false
        if (vy > 0f && boxes.any { b -> inBox(b, x, z, 0f) && nf + 1.75f > b.bottom && feet + 1.7f <= b.bottom }) vy = 0f
        feet = nf
        if (!wasGround && onGround && fall < -10f && walking) { rollT = 0.45f; showBanner("ROLL!") }
        // The Pokemon follows.
        val dx = x - monX; val dz = z - monZ; val d = hypot(dx, dz)
        if (d > 1.8f) { monX += dx / d * 3.6f * dt; monZ += dz / d * 3.6f * dt }
        if (d > 12f) { monX = x - 1f; monZ = z - 1f }
        updateThings(dt, punch, spin)
        return false
    }

    private fun updateThings(dt: Float, punch: Boolean, spin: Boolean) {
        // Coins and stars
        for (c in coins) if (!c.taken && abs(c.x - x) < 0.7f && abs(c.z - z) < 0.7f && c.y > feet - 0.4f && c.y < feet + 1.9f) {
            c.taken = true; g.coins += TeamGame.COIN_VALUE; g.sfx(Sound.COIN)
        }
        for ((i, s) in stars.withIndex()) if (!s.taken && abs(s.x - x) < 0.9f && abs(s.z - z) < 0.9f && s.y > feet - 0.5f && s.y < feet + 2.2f) {
            s.taken = true
            starsGot = stars.count { it.taken }
            g.slotSave("ow$world", g.slotLoad("ow$world") or (1 shl i))
            g.coins += 50
            g.sfx(Sound.ONEUP)
            g.sayAs(if (starsGot == STARS) "All the shine stars! Wahoo!" else "A shine star!", Voice.HERO)
            showBanner(if (starsGot == STARS) "ALL 8 SHINE STARS! +500 COINS!" else "SHINE STAR $starsGot / $STARS!  +50 coins")
            if (starsGot == STARS) { g.coins += 500; g.giveOpenWorldPrize() }
            g.saveFromOpenWorld()
        }
        // Wild Pokemon: walk up to one to make friends (XP for your partner).
        for (w in wilds) {
            w.t += dt
            if (!w.found && hypot(w.x - x, w.z - z) < 1.2f && feet < 1f) {
                w.found = true
                g.gainXp(6)
                g.sfx(Sound.ONEUP)
                showBanner("A wild ${w.mon.label} says hi! ${g.mon.label} made a friend: +6 XP")
            }
        }
        // Bad guys: they wander, chase you when you're close, and get stomped from above.
        val attack = punch || spin
        if (attack) { punchT = 0.25f; g.sfx(Sound.KICK) }
        for (f in foes) {
            if (!f.alive) { if (f.squashT > 0f) f.squashT -= dt; continue }
            f.t += dt
            val dx = x - f.x; val dz = z - f.z; val d = hypot(dx, dz)
            val speed = if (d < 7f && abs(feet - f.feet) < 2f) 2.2f else 0.9f
            val tx: Float; val tz: Float
            if (d < 0.05f) { tx = 0f; tz = 0f } else if (d < 7f) { tx = dx / d; tz = dz / d } else { val a = f.t * 0.7f + f.homeX; tx = cos(a); tz = sin(a) }
            val nx = (f.x + tx * speed * dt).coerceIn(1f, SIZE - 1f)
            val nz = (f.z + tz * speed * dt).coerceIn(1f, SIZE - 1f)
            if (boxes.none { inBox(it, nx, nz, 0.3f) && it.top > f.feet + 0.5f && it.bottom < f.feet + 1f }) { f.x = nx; f.z = nz }
            f.dir = tx
            // Touching Rocco
            if (abs(f.x - x) < 0.8f && abs(f.z - z) < 0.8f && abs(feet - f.feet) < 1.2f) {
                when {
                    vy < -1f && feet > f.feet + 0.4f -> { stomp(f); vy = 6.5f }
                    attack || punchT > 0f -> stomp(f)
                    invuln <= 0f -> hurt()
                }
            } else if (attack && hypot(f.x - x, f.z - z) < (if (spin) 2f else 1.5f) && abs(feet - f.feet) < 1.5f) stomp(f)
        }
    }

    private fun stomp(f: Foe3) {
        f.alive = false; f.squashT = 0.5f
        g.sfx(Sound.STOMP)
        repeat(3) { k -> coins += Spot(f.x + (k - 1) * 0.6f, f.feet + 0.6f, f.z) }
        g.gainXp(1)
    }

    private fun hurt() {
        hearts--
        invuln = 1.5f
        g.sfx(Sound.HURT)
        kickX = -cos(yaw) * 5f; kickZ = -sin(yaw) * 5f; vy = 5f
        if (hearts <= 0) {
            hearts = 5
            x = SIZE / 2; z = SIZE - 4f; feet = 0f; vy = 0f
            showBanner("Ouch! Back to the start (you keep your stars).")
        }
    }

    // ---------------------------------------------------------------- Drawing

    private var cyw = 1f; private var syw = 0f; private var cp = 1f; private var sp = 0f
    private var focal = 200f; private var midX = 200f; private var midY = 120f
    private var ex = 0f; private var ey = 0f; private var ez = 0f

    private fun cam(wx: Float, wy: Float, wz: Float): FloatArray {
        val dx = wx - ex; val dy = wy - ey; val dz = wz - ez
        val fX = cyw * cp; val fY = sp; val fZ = syw * cp
        val rX = -syw; val rZ = cyw
        val uX = -cyw * sp; val uY = cp; val uZ = -syw * sp
        return floatArrayOf(dx * rX + dz * rZ, dx * uX + dy * uY + dz * uZ, dx * fX + dy * fY + dz * fZ)
    }

    private fun poly(gfx: Gfx, pts: List<FloatArray>, color: Int) {
        val c = pts.map { cam(it[0], it[1], it[2]) }
        val out = ArrayList<FloatArray>(6)
        for (i in c.indices) {
            val a = c[i]; val b = c[(i + 1) % c.size]
            val ain = a[2] >= NEAR; val bin = b[2] >= NEAR
            if (ain) out += a
            if (ain != bin) { val k = (NEAR - a[2]) / (b[2] - a[2]); out += floatArrayOf(a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, NEAR) }
        }
        if (out.size < 3) return
        gfx.poly(FloatArray(out.size) { midX + out[it][0] / out[it][2] * focal }, FloatArray(out.size) { midY - out[it][1] / out[it][2] * focal }, color)
    }

    private fun shade(c: Int, k: Float): Int {
        fun ch(v: Int) = (v * k).toInt().coerceIn(0, 255)
        return argb((c ushr 24) and 255, (ch((c shr 16) and 255) shl 16) or (ch((c shr 8) and 255) shl 8) or ch(c and 255))
    }

    private class Item(val dist: Float, val draw: () -> Unit)

    /** A 2D picture standing at world ([wx], [wy], [wz]), facing the camera; [draw] gets a Gfx where 16 px = 1 block, feet at (0, 0). */
    private fun billboard(gfx: Gfx, items: ArrayList<Item>, wx: Float, wy: Float, wz: Float, bias: Float = 0f, draw: (Gfx, Float) -> Unit) {
        val c = cam(wx, wy, wz)
        if (c[2] < 0.4f || c[2] > VIEW_DIST) return
        if (abs(c[0]) > c[2] * midX / focal + 3f) return
        items += Item(c[2] - bias) {
            val k = focal / c[2] / PPB
            draw(ScaledGfx(gfx, k, midX + c[0] / c[2] * focal, midY - c[1] / c[2] * focal), c[2])
        }
    }

    fun render(gfx: Gfx, viewW: Float, viewH: Float) {
        cyw = cos(yaw); syw = sin(yaw); cp = cos(pitch); sp = sin(pitch)
        focal = viewH * 0.85f; midX = viewW / 2; midY = viewH / 2
        ex = x - cyw * 4.2f; ez = z - syw * 4.2f; ey = feet + 2.6f
        val th = theme
        val hy = midY + kotlin.math.tan(pitch) * focal
        gfx.gradient(0f, minOf(0f, hy - 400f), viewW, maxOf(hy, 1f) - minOf(0f, hy - 400f), th.skyTop, th.skyBottom)
        if (th.night) for (k in 0 until 30) gfx.rect((k * 89f + yaw * -60f).mod(viewW), (k * 37f) % maxOf(10f, hy - 20f), 1.5f, 1.5f, 0xCCFFFFFF.toInt())
        gfx.rect(0f, hy, viewW, maxOf(0f, viewH - hy), th.hill)
        // Faraway hills all around (they turn with you).
        for (k in 0 until 10) {
            var da = k * 0.628f - yaw
            while (da > Math.PI) da -= (2 * Math.PI).toFloat()
            while (da < -Math.PI) da += (2 * Math.PI).toFloat()
            if (abs(da) > 1.3f) continue
            val sx = midX + kotlin.math.tan(da) * focal
            gfx.oval(sx - 90f, hy - 26f - (k % 3) * 9f, 180f, 60f + (k % 3) * 18f, if (k % 2 == 0) th.hill else mixColor(th.hill, 0xFF000000.toInt(), 0.12f))
        }
        // The ground, in big checkered patches near you.
        val items = ArrayList<Item>()
        val p = 8f
        for (i in 0 until (SIZE / p).toInt()) for (j in 0 until (SIZE / p).toInt()) {
            val cx = i * p + p / 2; val cz = j * p + p / 2
            if (hypot(cx - x, cz - z) > VIEW_DIST + 6f) continue
            poly(gfx, listOf(floatArrayOf(i * p, 0f, j * p), floatArrayOf(i * p + p, 0f, j * p), floatArrayOf(i * p + p, 0f, j * p + p), floatArrayOf(i * p, 0f, j * p + p)), if ((i + j) % 2 == 0) th.ground else th.ground2)
        }
        // Boxes: only the sides that face the camera.
        for (b in boxes) {
            val c = cam((b.x0 + b.x1) / 2, b.top, (b.z0 + b.z1) / 2)
            if (c[2] < -3f || c[2] > VIEW_DIST + 4f) continue
            if (c[2] > 0f && abs(c[0]) > c[2] * midX / focal + 5f) continue
            items += Item(c[2] + 0.3f) {
                fun side(ax: Float, az: Float, bx: Float, bz: Float, k: Float) =
                    poly(gfx, listOf(floatArrayOf(ax, b.bottom, az), floatArrayOf(bx, b.bottom, bz), floatArrayOf(bx, b.top, bz), floatArrayOf(ax, b.top, az)), shade(b.color, k))
                if (ez < b.z0) side(b.x0, b.z0, b.x1, b.z0, 0.92f)
                if (ez > b.z1) side(b.x1, b.z1, b.x0, b.z1, 0.92f)
                if (ex < b.x0) side(b.x0, b.z1, b.x0, b.z0, 0.8f)
                if (ex > b.x1) side(b.x1, b.z0, b.x1, b.z1, 0.8f)
                if (ey > b.top) poly(gfx, listOf(floatArrayOf(b.x0, b.top, b.z0), floatArrayOf(b.x1, b.top, b.z0), floatArrayOf(b.x1, b.top, b.z1), floatArrayOf(b.x0, b.top, b.z1)), b.topColor)
                if (ey < b.bottom && b.bottom > 0f) poly(gfx, listOf(floatArrayOf(b.x0, b.bottom, b.z0), floatArrayOf(b.x1, b.bottom, b.z0), floatArrayOf(b.x1, b.bottom, b.z1), floatArrayOf(b.x0, b.bottom, b.z1)), shade(b.color, 0.6f))
            }
        }
        // Moving platforms
        for (m in movers) {
            val c = cam(m.x + 1f, m.top, m.z + 1f)
            if (c[2] < -2f || c[2] > VIEW_DIST) continue
            items += Item(c[2] + 0.2f) {
                val x0 = m.x; val z0 = m.z; val y1 = m.top; val y0 = y1 - 0.3f
                poly(gfx, listOf(floatArrayOf(x0, y0, z0 + 2f), floatArrayOf(x0 + 2f, y0, z0 + 2f), floatArrayOf(x0 + 2f, y1, z0 + 2f), floatArrayOf(x0, y1, z0 + 2f)), 0xFFB8781A.toInt())
                poly(gfx, listOf(floatArrayOf(x0, y1, z0), floatArrayOf(x0 + 2f, y1, z0), floatArrayOf(x0 + 2f, y1, z0 + 2f), floatArrayOf(x0, y1, z0 + 2f)), 0xFFE8A82A.toInt())
                for (k in 0 until 3) poly(gfx, listOf(floatArrayOf(x0 + 0.3f + k * 0.6f, y1 + 0.01f, z0), floatArrayOf(x0 + 0.55f + k * 0.6f, y1 + 0.01f, z0), floatArrayOf(x0 + 0.55f + k * 0.6f, y1 + 0.01f, z0 + 2f), floatArrayOf(x0 + 0.3f + k * 0.6f, y1 + 0.01f, z0 + 2f)), 0xFF2A2A34.toInt())
            }
        }
        // Trees
        for (tr in trees) billboard(gfx, items, tr.x, 0f, tr.z) { sg, _ -> drawTree(sg, th.tree) }
        // Coins (spinning) and stars
        for (c in coins) if (!c.taken) billboard(gfx, items, c.x, c.y - 0.35f, c.z) { sg, _ ->
            val w = 6f * abs(cos(t * 4f + c.x))
            sg.oval(-w / 2 - 0.5f, -12.5f, w + 1f, 11f, 0xFF8A6A10.toInt()); sg.oval(-w / 2, -12f, w, 10f, 0xFFFFD21F.toInt())
        }
        for (s in stars) if (!s.taken) billboard(gfx, items, s.x, s.y - 0.6f, s.z) { sg, _ -> drawStar(sg, t) }
        // Bad guys, using their usual pictures.
        for (f in foes) {
            if (!f.alive && f.squashT <= 0f) continue
            billboard(gfx, items, f.x, f.feet, f.z) { sg, _ ->
                val look = f.look
                look.x = -look.w / 2; look.y = -look.h
                look.vx = if (f.dir < 0f) -20f else 20f
                if (f.alive) look.draw(g, sg, 0f)
                else sg.oval(-look.w / 2, -3f, look.w, 3f, 0xFF3A3A3A.toInt())
            }
        }
        // Wild Pokemon (hop around until you meet them).
        for (w in wilds) billboard(gfx, items, w.x, 0f, w.z) { sg, _ ->
            val hop = if (w.found) 0f else abs(sin(w.t * 4f)) * 4f
            TeamArt.mon(sg, w.mon, 0f, -hop, t, 1, false)
            if (!w.found) sg.shadowText("!", 0f, -26f - hop, 8f, 0xFFFFE14D.toInt(), 1)
        }
        // Your Pokemon and Rocco
        billboard(gfx, items, monX, groundUnder(monX, monZ, 9f).coerceAtMost(feet + 0.5f), monZ) { sg, _ -> TeamArt.mon(sg, g.mon, 0f, 0f, t, faceDir, false) }
        billboard(gfx, items, x, groundUnder(x, z, feet), z, bias = 0.5f) { sg, _ -> sg.oval(-6f, -1.5f, 12f, 3f, 0x55000000) }
        billboard(gfx, items, x, feet, z, bias = 0.6f) { sg, _ ->
            if (invuln > 0f && (invuln * 16).toInt() % 2 == 0) return@billboard
            val frame = if (!onGround) 2 else if (walking && (t * 9).toInt() % 2 == 0) 1 else 0
            val spr = Outline.of(TeamArt.rocco(g.power, frame))
            val flip = faceDir < 0
            when {
                flipT > 0f -> sg.spriteRotated(spr, -8f, -24f, 16f, 24f, 360f * (1f - flipT / flipDur) * faceDir, flip)
                rollT > 0f -> sg.spriteRotated(spr, -8f, -18f, 16f, 18f, 360f * (1f - rollT / 0.45f) * faceDir, flip)
                vaultT > 0f -> sg.spriteRotated(spr, -8f, -24f, 16f, 24f, 60f * faceDir, flip)
                punchT > 0f -> { sg.sprite(spr, -8f, -24f, flip); sg.oval(if (flip) -16f else 8f, -16f, 8f, 8f, 0xCCFFFFFF.toInt()) }
                else -> sg.sprite(spr, -8f, -24f, flip)
            }
        }
        items.sortByDescending { it.dist }
        for (it in items) it.draw()
        drawUi(gfx, viewW, viewH)
    }

    private fun drawTree(sg: Gfx, kind: Int) {
        when (kind) {
            1 -> { sg.rect(-1.5f, -8f, 3f, 8f, 0xFF6A4A20.toInt()); sg.poly(floatArrayOf(-10f, 0f, 10f), floatArrayOf(-6f, -34f, -6f), 0xFF2E7A4A.toInt()); sg.poly(floatArrayOf(-6f, 0f, 6f), floatArrayOf(-20f, -36f, -20f), 0xFFF4F8FF.toInt()) }
            2 -> { sg.rect(-3f, -26f, 6f, 26f, 0xFF3AA84A.toInt()); sg.rect(-10f, -18f, 4f, 8f, 0xFF3AA84A.toInt()); sg.rect(6f, -22f, 4f, 10f, 0xFF3AA84A.toInt()) }
            3 -> { sg.poly(floatArrayOf(-2f, 2f, 4f, 0f), floatArrayOf(0f, 0f, -30f, -30f), 0xFF8A6A3A.toInt()); for (k in 0 until 4) sg.oval(-12f + k * 6f, -36f + (k % 2) * 3f, 14f, 6f, 0xFF3AA84A.toInt()) }
            4 -> { sg.rect(-3f, -16f, 6f, 16f, 0xFFF4E8D0.toInt()); sg.oval(-12f, -28f, 24f, 14f, 0xFFE84A4A.toInt()); sg.oval(-6f, -26f, 4f, 4f, 0xFFFFFFFF.toInt()); sg.oval(3f, -24f, 4f, 4f, 0xFFFFFFFF.toInt()) }
            5 -> { sg.poly(floatArrayOf(-6f, 0f, 6f), floatArrayOf(0f, -30f, 0f), 0xFFC8A8FF.toInt()); sg.poly(floatArrayOf(-2f, 0f, 2f), floatArrayOf(-6f, -26f, -6f), 0x88FFFFFF.toInt()) }
            6 -> { sg.rect(-2f, -26f, 4f, 26f, 0xFF4A3A30.toInt()); sg.poly(floatArrayOf(0f, -12f, -10f), floatArrayOf(-18f, -26f, -28f), 0xFF4A3A30.toInt()); sg.poly(floatArrayOf(0f, 12f, 10f), floatArrayOf(-14f, -22f, -24f), 0xFF4A3A30.toInt()) }
            7 -> { sg.rect(-1f, -24f, 2f, 24f, 0xFFF4F4F4.toInt()); sg.oval(-8f, -36f, 16f, 16f, 0xFFFF6A9A.toInt()); sg.oval(-4f, -32f, 8f, 8f, 0xFFFFFFFF.toInt()) }
            else -> { sg.rect(-2f, -12f, 4f, 12f, 0xFF7A4A20.toInt()); sg.oval(-11f, -32f, 22f, 22f, 0xFF3AA84A.toInt()); sg.oval(-7f, -30f, 9f, 8f, 0xFF5AC86A.toInt()) }
        }
    }

    private fun drawStar(sg: Gfx, t: Float) {
        val s = 9f + sin(t * 4f) * 1f
        val xs = FloatArray(10); val ys = FloatArray(10)
        for (k in 0 until 10) {
            val a = -Math.PI.toFloat() / 2 + k * Math.PI.toFloat() / 5 + t * 1.5f
            val r = if (k % 2 == 0) s else s * 0.45f
            xs[k] = cos(a) * r; ys[k] = -12f + sin(a) * r
        }
        sg.oval(-s * 1.4f, -12f - s * 1.4f, s * 2.8f, s * 2.8f, 0x44FFE14D)
        sg.poly(xs, ys, 0xFFFFD21F.toInt())
        sg.oval(-2f, -14f, 2f, 3f, 0xFF1A1420.toInt()); sg.oval(1f, -14f, 2f, 3f, 0xFF1A1420.toInt())
    }

    private fun drawUi(gfx: Gfx, viewW: Float, viewH: Float) {
        gfx.rect(0f, 0f, viewW, 22f, 0xCC1A1008.toInt())
        gfx.shadowText("3D WORLD ${world + 1}", 6f, 15f, 8f, 0xFFFFD21F.toInt())
        gfx.shadowText("★ $starsGot/$STARS", 92f, 15f, 8f, 0xFFFFE14D.toInt())
        gfx.sprite(com.roccoquest.game.Sprites.coin, 150f, 5f, w = 8f, h = 11f)
        gfx.shadowText("x${g.coins}", 160f, 15f, 8f, 0xFFFFFFFF.toInt())
        for (i in 0 until 5) gfx.text("♥", 215f + i * 9f, 15f, 8f, if (i < hearts) 0xFFFF4A4A.toInt() else 0x66FFFFFF, 1)
        gfx.rect(viewW - 50f, 2f, 46f, 18f, 0xFF2E6BE6.toInt())
        gfx.text("MAP", viewW - 27f, 15f, 8f, 0xFFFFFFFF.toInt(), 1)
        if (bannerT > 0f) gfx.shadowText(banner, viewW / 2, 40f, 8f, 0xFFFFF2C8.toInt(), 1)
        TeamControls.draw3d(gfx, g.inputForDraw(), viewW, viewH)
    }

    /** Taps on the top bar: MAP leaves. */
    fun tapLeaves(tx: Float, ty: Float, viewW: Float) = ty < 24f && tx > viewW - 54f
}
