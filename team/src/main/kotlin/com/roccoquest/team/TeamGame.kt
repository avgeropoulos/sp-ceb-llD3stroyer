package com.roccoquest.team

import com.roccoquest.game.Body
import com.roccoquest.game.Gfx
import com.roccoquest.game.Music
import com.roccoquest.game.Sound
import com.roccoquest.game.SoundSink
import com.roccoquest.game.Sprites
import com.roccoquest.game.Storage
import com.roccoquest.game.TILE
import com.roccoquest.game.VIEW_H
import com.roccoquest.game.argb
import com.roccoquest.game.rainbow
import com.roccoquest.game.shadowText
import com.roccoquest.game.tileOf
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Rocco & Blocky: Team Up!
 *
 * Rocco the plumber and Blocky the miner team up (with Sparky the electric mouse) to stop
 * King Krag. You control one hero at a time and the other follows; SWAP switches. Blocky
 * mines ore, and the crafting table turns ore into flower powers and better pickaxes.
 */
class TeamGame(private val input: TeamInput, private val sound: SoundSink, private val storage: Storage = Storage.Memory()) {
    enum class State { TITLE, INTRO, PLAY, CRAFT, OUCH, CLEAR, VICTORY }

    companion object {
        const val MAX_HEARTS = 5
        const val COST = 3
        const val HEART_COST = 5
    }

    var viewW = 427f
    var state = State.TITLE
        private set
    var time = 0f
        private set
    private var stateT = 0f

    var levelIndex = 0
        private set
    var level = Level(Levels.all[0])
        private set

    val rocco = Hero(Who.ROCCO, 0f, 0f)
    val blocky = Hero(Who.BLOCKY, 0f, 0f)
    var leaderWho = Who.ROCCO
        private set
    val leader get() = if (leaderWho == Who.ROCCO) rocco else blocky
    val follower get() = if (leaderWho == Who.ROCCO) blocky else rocco

    val foes = ArrayList<Foe>()
    private val things = ArrayList<Thing>()
    private val pending = ArrayList<Thing>()
    private val particles = ArrayList<Particle>()
    private val frogSpots = ArrayList<Int>()

    // Team stats
    var hearts = MAX_HEARTS
        private set
    var armor = 0
        private set
    var invuln = 0f
        private set
    var coins = 0
    var score = 0
        private set
    val inventory = IntArray(Ore.entries.size)
    var pickTier = 1
        private set
    var power = Power.NONE
        private set
    val flowerLevel = IntArray(Power.entries.size)

    // Sparky
    var sparkyX = 0f
        private set
    var sparkyY = 0f
        private set
    private var sparkyFacing = 1
    private var zapTimer = 3f
    private var zapFlash = 0f

    // Camera, bosses and Wonder
    var camX = 0f
        private set
    private var camLock: Float? = null
    var shake = 0f
    var boss: Foe? = null
        private set
    private var bossDone = false
    private var bossDoneT = 0f
    var wonder = false
        private set
    private var bannerT = 0f
    private var bannerTitle = ""
    private var bannerSub = ""
    private var frog: Frog? = null

    private var respawnX = 0f
    private var respawnY = 0f
    private var safeX = 0f
    private var safeY = 0f
    private var goal: Goal? = null

    var digDown = false
        private set
    private var toast = ""
    private var toastT = 0f
    private var musicOn = true
    var paused = false
        private set
    private var prev = BooleanArray(6)

    fun sfx(s: Sound) = sound.play(s)
    fun spawn(t: Thing) { pending += t }
    fun puff(x: Float, y: Float) { particles += Puff(x, y) }
    fun say(x: Float, y: Float, text: String, dur: Float = 1.8f) { particles += Speech(x, y, text, dur) }
    fun addScore(n: Int, x: Float, y: Float) { score += n; particles += FloatText(x, y, n.toString()) }
    private fun toast(s: String) { toast = s; toastT = 2.2f }

    fun iceBurst(x: Float, y: Float) {
        repeat(8) {
            val a = it * 0.785f
            particles += Sparkle(x, y, kotlin.math.cos(a) * 70f, sin(a) * 70f, 0xFFBFF4FF.toInt())
        }
    }

    private fun debris(tx: Int, ty: Int, color: Int, n: Int = 4) {
        val x = tx * TILE + 8f
        val y = ty * TILE + 8f
        repeat(n) { particles += Bit(x, y, Random.nextFloat() * 160 - 80, -Random.nextFloat() * 220 - 60, color) }
    }

    // ======================================================================== Saving

    val savedLevel get() = storage.load("team_level") - 1

    private fun save() {
        storage.save("team_level", levelIndex + 1)
        Ore.entries.forEach { storage.save("team_ore_${it.name}", inventory[it.ordinal]) }
        Power.entries.forEach { storage.save("team_flower_${it.name}", flowerLevel[it.ordinal]) }
        storage.save("team_pick", pickTier)
        storage.save("team_power", power.ordinal)
        storage.save("team_coins", coins)
    }

    private fun loadSave() {
        Ore.entries.forEach { inventory[it.ordinal] = storage.load("team_ore_${it.name}") }
        Power.entries.forEach { flowerLevel[it.ordinal] = storage.load("team_flower_${it.name}") }
        pickTier = storage.load("team_pick").coerceIn(1, 4)
        power = Power.entries.getOrElse(storage.load("team_power")) { Power.NONE }
        if (flowerLevel[power.ordinal] == 0) power = Power.NONE
        coins = storage.load("team_coins")
    }

    fun newGame() {
        inventory.fill(0)
        flowerLevel.fill(0)
        pickTier = 1
        power = Power.NONE
        coins = 0
        score = 0
        startLevel(0)
    }

    fun continueGame() {
        loadSave()
        score = 0
        startLevel(savedLevel.coerceIn(0, Levels.all.lastIndex))
    }

    // ======================================================================== Levels

    private fun setState(s: State) { state = s; stateT = 0f }

    fun startLevel(index: Int, fromCheckpoint: Boolean = false) {
        val keepX = respawnX
        val keepY = respawnY
        levelIndex = index
        level = Level(Levels.all[index])
        foes.clear(); things.clear(); pending.clear(); particles.clear(); frogSpots.clear()
        boss = null; bossDone = false; wonder = false; camLock = null; frog = null; goal = null
        hearts = MAX_HEARTS
        if (power == Power.GRASS) armor = max(armor, 2)
        invuln = 0f
        leaderWho = Who.ROCCO
        for (ty in 0 until ROWS) for (tx in 0 until level.w) {
            val px = tx * TILE.toFloat()
            val bottom = (ty + 1) * TILE.toFloat()
            when (level.def.rows[ty][tx]) {
                '@' -> { respawnX = px; respawnY = bottom }
                'g' -> foes += Grumbler(px, bottom, false)
                'w' -> foes += Grumbler(px, bottom, true)
                'k' -> foes += Shellback(px, bottom)
                'J' -> foes += KragJr(px, bottom)
                'K' -> foes += KingKrag(px, bottom)
                'c' -> things += Coin(px, ty * TILE.toFloat())
                '|' -> things += Checkpoint(px, bottom)
                'F' -> things += Goal(px, bottom).also { goal = it }
                'r' -> frogSpots += tx
            }
        }
        if (fromCheckpoint) {
            respawnX = keepX
            respawnY = keepY
            things.filterIsInstance<Checkpoint>().forEach { if (it.x <= keepX + 1) it.reached = true }
        }
        placeTeam(respawnX, respawnY)
        camX = (leader.cx - viewW * 0.4f).coerceIn(0f, max(0f, level.w * TILE - viewW))
        save()
        setState(State.INTRO)
    }

    private fun placeTeam(x: Float, bottom: Float) {
        rocco.x = x + 2; rocco.y = bottom - rocco.h; rocco.vx = 0f; rocco.vy = 0f; rocco.facing = 1
        blocky.x = x - 18; blocky.y = bottom - blocky.h; blocky.vx = 0f; blocky.vy = 0f; blocky.facing = 1
        if (blocky.x < 0) blocky.x = x + 2
        safeX = x; safeY = bottom
        sparkyX = x - 30; sparkyY = bottom - 50
    }

    // ======================================================================== Update

    private fun edge(i: Int, now: Boolean): Boolean {
        val e = now && !prev[i]
        prev[i] = now
        return e
    }

    fun update(dt: Float) {
        time += dt
        stateT += dt
        val jumpE = edge(0, input.jump)
        val swapE = edge(1, input.swap)
        val craftE = edge(2, input.craft)
        val pauseE = edge(3, input.pause)
        if (edge(4, input.musicToggle)) musicOn = !musicOn
        val tapped = input.tap
        input.tap = false
        val num = input.numberKey
        input.numberKey = 0

        when (state) {
            State.TITLE -> if (tapped && stateT > 0.4f) {
                // Keyboard presses (tapX < 0) continue a saved game.
                if (savedLevel > 0 && (input.tapX > viewW / 2 || input.tapX < 0)) continueGame() else newGame()
            }
            State.INTRO -> if (stateT > 3f || (tapped && stateT > 0.6f)) setState(State.PLAY)
            State.PLAY -> {
                if (pauseE) paused = !paused
                if (!paused) {
                    if (craftE) { setState(State.CRAFT); sfx(Sound.BUMP) } else play(dt, jumpE, swapE)
                }
            }
            State.CRAFT -> updateCraft(tapped, craftE || pauseE, num)
            State.OUCH -> {
                updateParticles(dt)
                if (stateT > 2.2f) startLevel(levelIndex, fromCheckpoint = true)
            }
            State.CLEAR -> {
                updateParticles(dt)
                goal?.let { it.flagY = min(it.h - 30f, it.flagY + 120f * dt) }
                if (stateT > 3.2f) {
                    if (levelIndex + 1 < Levels.all.size) startLevel(levelIndex + 1) else setState(State.VICTORY)
                }
            }
            State.VICTORY -> {
                updateParticles(dt)
                if (Random.nextFloat() < dt * 12) {
                    particles += Sparkle(Random.nextFloat() * viewW + camX, -4f, 0f, 60f + Random.nextFloat() * 60, solidRainbow(time, Random.nextFloat()))
                }
                if (tapped && stateT > 3f) { save(); setState(State.TITLE) }
            }
        }
        updateMusic()
    }

    private fun play(dt: Float, jumpE: Boolean, swapE: Boolean) {
        if (invuln > 0) invuln -= dt
        if (toastT > 0) toastT -= dt
        if (bannerT > 0) bannerT -= dt
        if (shake > 0) shake -= dt
        if (swapE) swap()

        val grav = if (wonder) 0.75f else 1f
        updateLeader(leader, dt, jumpE, grav)
        updateFollower(follower, dt, grav)
        if (input.action && leader.cooldown <= 0f) action(leader)
        for (h in arrayOf(rocco, blocky)) {
            h.anim += dt
            if (h.swing > 0) h.swing -= dt
            if (h.cooldown > 0) h.cooldown -= dt
            if (h.jumpCooldown > 0) h.jumpCooldown -= dt
        }

        things += pending.filter { it !is Foe }
        foes += pending.filterIsInstance<Foe>()
        pending.clear()
        for (f in foes) {
            if (!f.active && f.x < camX + viewW + 48 && f.x + f.w > camX - 48) f.active = true
            if (f.active) f.update(this, dt)
        }
        for (t in things) if (t.active || (t.x < camX + viewW + 32)) { t.active = true; t.update(this, dt) }
        foes.removeAll { it.removed }
        things.removeAll { it.removed }
        updateParticles(dt)

        interact()
        updateSparky(dt)
        updateFrog()
        updateBoss(dt)
        updateCamera(dt)
        if (state == State.PLAY) checkFalls()
    }

    private fun swap() {
        val old = leader
        leaderWho = if (leaderWho == Who.ROCCO) Who.BLOCKY else Who.ROCCO
        val h = leader
        if (hypot(h.cx - old.cx, h.cy - old.cy) > 140f || !h.onGround && h.y > level.h * TILE) {
            teleport(h, old)
        }
        h.vx = 0f
        sfx(Sound.KICK)
        particles += FloatText(h.cx, h.y - 6, h.who.label + "!", if (h.who == Who.ROCCO) 0xFFFF8A80.toInt() else 0xFF7FF4F0.toInt())
    }

    private fun teleport(h: Hero, to: Hero) {
        puff(h.cx, h.cy)
        h.x = to.x - to.facing * 4
        h.y = to.y
        h.vx = 0f
        h.vy = 0f
        h.stuck = 0f
        puff(h.cx, h.cy)
    }

    private fun updateLeader(h: Hero, dt: Float, jumpE: Boolean, grav: Float) {
        val dir = (if (input.right) 1 else 0) - (if (input.left) 1 else 0)
        if (dir != 0) h.facing = dir
        h.vx = approach(h.vx, dir * h.speed, (if (h.onGround) 800f else 520f) * dt)
        if (jumpE && h.onGround) {
            h.vy = h.jumpV
            sfx(Sound.JUMP)
        }
        // Let go of JUMP early for a short hop.
        if (!input.jump && h.vy < -130f) h.vy = -130f
        h.vy = min(h.vy + GRAVITY * grav * dt, MAX_FALL)
        moveHero(h, dt)
        if (h.onGround && level.solidAt(h.x + 1, h.bottom + 1) && level.solidAt(h.x + h.w - 1, h.bottom + 1)) {
            safeX = h.x
            safeY = h.bottom
        }
    }

    /** The hero you're not controlling follows along, hopping over walls and gaps. */
    private fun updateFollower(f: Hero, dt: Float, grav: Float) {
        val l = leader
        val targetX = l.cx - l.facing * 22f
        val dx = targetX - f.cx
        val want = if (abs(dx) > 12f) (if (dx > 0) 1f else -1f) * min(f.speed * 1.15f, abs(dx) * 4f) else 0f
        f.facing = when {
            want > 0f -> 1
            want < 0f -> -1
            abs(l.cx - f.cx) > 4 -> if (l.cx > f.cx) 1 else -1
            else -> f.facing
        }
        f.vx = approach(f.vx, want, 900f * dt)
        if (f.onGround && f.jumpCooldown <= 0f) {
            val ahead = f.cx + f.facing * 12f
            val blocked = want != 0f && (level.solidAt(ahead, f.bottom - 4) || level.solidAt(ahead, f.y + 4))
            val gap = want != 0f && !level.solidAt(ahead, f.bottom + 4) && level[tileOf(ahead), tileOf(f.bottom + 4)] != Tile.CLOUD
            val leaderAbove = l.bottom < f.bottom - 20f && abs(l.cx - f.cx) < 70f && l.onGround
            if (blocked || gap || leaderAbove) {
                f.vy = -345f
                f.jumpCooldown = 0.35f
            }
        }
        f.vy = min(f.vy + GRAVITY * grav * dt, MAX_FALL)
        val oldX = f.x
        moveHero(f, dt)
        f.stuck = if (want != 0f && abs(f.x - oldX) < 0.2f) f.stuck + dt else 0f
        val far = hypot(l.cx - f.cx, l.cy - f.cy) > 230f
        if (far || f.stuck > 1.2f || f.y > level.h * TILE || level.inLava(f)) teleport(f, l)
    }

    private fun moveHero(h: Hero, dt: Float) {
        level.moveX(h, h.vx * dt)
        camLock?.let { lock -> h.x = h.x.coerceIn(lock, lock + viewW - h.w) }
        h.x = h.x.coerceIn(0f, level.w * TILE - h.w)
        if (level.moveY(h, h.vy * dt) == -1) bump(level.bumpX, level.bumpY)
    }

    private fun bump(tx: Int, ty: Int) {
        when (level[tx, ty]) {
            Tile.QCOIN -> {
                level[tx, ty] = Tile.USED
                spawn(Coin(tx * TILE.toFloat(), ty * TILE - 16f, popping = true))
                sfx(Sound.COIN)
                score += 100
            }
            Tile.QHEART -> {
                level[tx, ty] = Tile.USED
                spawn(HeartItem(tx * TILE.toFloat(), ty * TILE - 14f))
                sfx(Sound.POWERUP)
            }
            Tile.BRICK -> {
                level[tx, ty] = Tile.EMPTY
                debris(tx, ty, 0xFFB4501C.toInt())
                sfx(Sound.BREAK)
                score += 50
            }
            else -> sfx(Sound.BUMP)
        }
        // Bonk foes standing on the block.
        for (f in foes) {
            if (!f.dying && !f.boss && abs(f.bottom - ty * TILE) < 2 && f.cx > tx * TILE - 4 && f.cx < tx * TILE + TILE + 4) f.hit(this, 1)
        }
    }

    // ======================================================================== Mining and throwing

    private fun action(h: Hero) {
        if (h.who == Who.BLOCKY) swingPick(h) else throwPower(h)
    }

    private fun swingPick(h: Hero) {
        h.swing = 0.22f
        h.cooldown = 0.24f
        digDown = input.down
        // The pickaxe is a weapon too: stronger pickaxes hit harder.
        val reach = Body(if (h.facing > 0) h.x + h.w - 2 else h.x - 16, h.y - 4, 18f, h.h + 4)
        var hitFoe = false
        for (f in foes) if (!f.dying && f.active && f.overlaps(reach)) { if (f.hit(this, pickTier)) hitFoe = true }
        if (hitFoe) return
        val (tx, ty) = pickTarget(h) ?: run { sfx(Sound.FIRE); return }
        mine(tx, ty)
    }

    private fun pickTarget(h: Hero): Pair<Int, Int>? {
        fun ok(tx: Int, ty: Int): Boolean {
            val t = level[tx, ty]
            return Tile.solid(t) && t != Tile.USED
        }
        if (digDown) {
            val ty = tileOf(h.bottom + 2)
            val tx = tileOf(h.cx)
            return if (ok(tx, ty)) tx to ty else null
        }
        val tx = tileOf(if (h.facing > 0) h.x + h.w + 3 else h.x - 3)
        val low = tileOf(h.bottom - 4)
        val high = tileOf(h.y + 4)
        return when {
            ok(tx, low) -> tx to low
            ok(tx, high) -> tx to high
            ok(tileOf(h.cx), tileOf(h.y - 4)) -> tileOf(h.cx) to tileOf(h.y - 4)
            else -> null
        }
    }

    fun mine(tx: Int, ty: Int) {
        val t = level[tx, ty]
        if (t == Tile.QCOIN || t == Tile.QHEART) { bump(tx, ty); return }
        val tough = level.toughness(tx, ty)
        if (tough == 0) {
            sfx(Sound.BUMP)
            if (Tile.solid(t)) particles += FloatText(tx * TILE + 8f, ty * TILE.toFloat(), "CLANK!")
            return
        }
        if (t == Tile.DIAMOND && pickTier < 2) {
            sfx(Sound.BUMP)
            toast("Diamond is too hard! Craft an IRON PICKAXE.")
            return
        }
        val key = ty * level.w + tx
        val dmg = (level.cracks[key] ?: 0) + pickTier
        val ore = Tile.ore(t)
        val color = ore?.dark ?: 0xFF8A5A2A.toInt()
        if (dmg >= tough) {
            level[tx, ty] = Tile.EMPTY
            debris(tx, ty, color, 6)
            sfx(Sound.BREAK)
            score += 20
            if (ore != null) {
                inventory[ore.ordinal]++
                particles += FloatText(tx * TILE + 8f, ty * TILE.toFloat(), "+1 ${ore.label}", ore.color)
                if (ore != Ore.STONE) sfx(Sound.COIN)
            }
        } else {
            level.cracks[key] = dmg
            debris(tx, ty, color, 2)
            sfx(Sound.BUMP)
        }
    }

    private fun throwPower(h: Hero) {
        h.cooldown = 0.3f
        h.swing = 0.15f
        if (power == Power.NONE) {
            // No flower yet: Rocco can still punch.
            val reach = Body(if (h.facing > 0) h.x + h.w - 2 else h.x - 10, h.y + 4, 12f, 12f)
            for (f in foes) if (!f.dying && f.active && f.overlaps(reach)) f.hit(this, 1)
            sfx(Sound.KICK)
            if (toastT <= 0) toast("Mine ore with Blocky, then tap CRAFT!")
            return
        }
        val lvl = flowerLevel[power.ordinal].coerceAtLeast(1)
        val mine = things.count { it is Shot }
        if (mine >= 1 + lvl) return
        val dmg = power.damage + lvl - 1
        val sx = if (h.facing > 0) h.x + h.w else h.x - 9
        spawn(Shot(sx, h.y + 8, h.facing, power, dmg, h))
        if (lvl >= 3) spawn(Shot(sx, h.y - 1, h.facing, power, dmg, h))
        sfx(Sound.FIRE)
    }

    // ======================================================================== Crafting

    class Recipe(val title: String, val note: String, val ore: Ore, val cost: Int, val button: String, val enabled: Boolean, val icon: Power?)

    fun recipes(): List<Recipe> {
        val list = ArrayList<Recipe>()
        for (p in listOf(Power.BOOM, Power.FIRE, Power.ICE, Power.GRASS)) {
            val lvl = flowerLevel[p.ordinal]
            val ore = p.ore!!
            val note = when (p) {
                Power.BOOM -> "Iron: weakest. Comes back!"
                Power.FIRE -> "Gold: strong bouncing fire"
                Power.ICE -> "Diamond: the strongest!"
                else -> "Emerald: GREEN ARMOR + leaves"
            }
            val (button, enabled) = when {
                lvl == 0 -> "MAKE" to (inventory[ore.ordinal] >= COST)
                power != p -> "EQUIP" to true
                lvl >= 3 -> "MAX" to false
                else -> "Lv${lvl + 1}" to (inventory[ore.ordinal] >= COST)
            }
            val title = if (lvl > 0) "${p.label} Lv$lvl" else p.label
            list += Recipe(title, note, ore, if (button == "EQUIP" || button == "MAX") 0 else COST, button, enabled, p)
        }
        val next = pickTier + 1
        val pickOre = when (next) { 2 -> Ore.IRON; 3 -> Ore.GOLD; else -> Ore.DIAMOND }
        list += if (pickTier >= 4) {
            Recipe("DIAMOND PICKAXE", "The best pickaxe there is!", Ore.DIAMOND, 0, "MAX", false, null)
        } else {
            Recipe("${TeamArt.pickName(next)} PICKAXE", "Mines faster, hits harder", pickOre, COST, "MAKE", inventory[pickOre.ordinal] >= COST, null)
        }
        list += Recipe("HEART", "Heals one heart", Ore.STONE, HEART_COST, "MAKE", inventory[Ore.STONE.ordinal] >= HEART_COST && hearts < MAX_HEARTS, null)
        return list
    }

    /** True when the crafting table has something new to make (the CRAFT button glows). */
    val canCraftSomething get() = recipes().any { it.enabled && it.button != "EQUIP" && it.title != "HEART" }

    fun craft(i: Int) {
        val r = recipes().getOrNull(i) ?: return
        if (!r.enabled) {
            sfx(Sound.BUMP)
            toast(if (r.button == "MAX") "That's already the best!" else if (r.title == "HEART" && hearts >= MAX_HEARTS) "Hearts are full!" else "Need ${r.cost} ${r.ore.label}!")
            return
        }
        inventory[r.ore.ordinal] -= r.cost
        val p = r.icon
        when {
            p != null && r.button == "EQUIP" -> {
                power = p
                toast("${p.label} ready!")
            }
            p != null -> {
                flowerLevel[p.ordinal] = min(3, flowerLevel[p.ordinal] + 1)
                power = p
                if (p == Power.GRASS) armor = flowerLevel[p.ordinal] + 2
                toast("${p.label} Lv${flowerLevel[p.ordinal]}!")
            }
            r.title == "HEART" -> { hearts++; toast("+1 HEART") }
            else -> { pickTier++; toast("${TeamArt.pickName(pickTier)} PICKAXE!") }
        }
        sfx(Sound.POWERUP)
        for (h in arrayOf(rocco, blocky)) repeat(6) {
            particles += Sparkle(h.cx, h.cy, Random.nextFloat() * 120 - 60, -Random.nextFloat() * 90, r.ore.color)
        }
    }

    private fun panelX() = viewW / 2 - panelW() / 2
    private fun panelW() = min(viewW - 24f, 400f)
    private val panelTop = 32f
    private val rowH = 30f

    private fun updateCraft(tapped: Boolean, close: Boolean, num: Int) {
        if (toastT > 0) toastT -= 1f / 60f
        if (num in 1..6) craft(num - 1)
        if (close) { setState(State.PLAY); return }
        if (!tapped || stateT < 0.15f) return
        val x = input.tapX
        val y = input.tapY
        val px = panelX()
        val closeX = px + panelW() - 14
        if (hypot(x - closeX, y - (panelTop + 11)) < 14f || x < px || x > px + panelW() || y < panelTop) {
            setState(State.PLAY)
            return
        }
        val i = ((y - panelTop - 22f) / rowH).toInt()
        if (y >= panelTop + 22f && i in 0 until 6) craft(i)
    }

    // ======================================================================== Interactions

    private fun interact() {
        for (f in foes) {
            if (!f.active || f.dying || f.removed) continue
            for (h in arrayOf(leader, follower)) {
                if (!f.overlaps(h)) continue
                val falling = h.vy > 0 && h.bottom - f.y < 10f
                if (falling && f.stompable) {
                    f.stomp(this, h)
                    h.vy = if (h === leader && input.jump) -340f else -240f
                    break
                }
                if (h === leader) f.touch(this)
            }
        }
        for (t in things) {
            if (t.removed) continue
            val hit = t.overlaps(leader) || t.overlaps(follower)
            when (t) {
                is Coin -> if (hit) {
                    t.removed = true
                    collectCoin()
                }
                is HeartItem -> if (hit) {
                    t.removed = true
                    if (hearts < MAX_HEARTS) hearts++ else score += 1000
                    particles += FloatText(t.cx, t.y, "+1 HEART", 0xFFFF8080.toInt())
                    sfx(Sound.ONEUP)
                }
                is Checkpoint -> if (!t.reached && t.overlaps(leader)) {
                    t.reached = true
                    respawnX = t.x
                    respawnY = t.bottom
                    toast("CHECKPOINT!")
                    sfx(Sound.POWERUP)
                }
                is Goal -> if (leader.x + leader.w >= t.x && state == State.PLAY) {
                    setState(State.CLEAR)
                    score += 2000
                    sfx(Sound.CLEAR)
                }
            }
        }
    }

    private fun collectCoin() {
        coins++
        score += 50
        sfx(Sound.COIN)
        if (coins % 50 == 0) {
            if (hearts < MAX_HEARTS) hearts++
            toast("50 COINS: +1 HEART!")
            sfx(Sound.ONEUP)
        }
    }

    fun hurt() {
        if (invuln > 0 || state != State.PLAY) return
        if (armor > 0) {
            armor--
            particles += FloatText(leader.cx, leader.y - 4, "ARMOR!", 0xFF3CE070.toInt())
        } else hearts--
        invuln = 1.6f
        sfx(Sound.HURT)
        leader.vy = -180f
        leader.vx = -leader.facing * 90f
        if (hearts <= 0) knockedOut()
    }

    /** For tests: nothing can hurt the team for a while. */
    internal fun hurtProof() {
        invuln = 10f
        hearts = MAX_HEARTS
    }

    private fun knockedOut() {
        hearts = 0
        setState(State.OUCH)
        sfx(Sound.DIE)
    }

    private fun checkFalls() {
        val h = leader
        if (h.y > level.h * TILE + 8 || level.inLava(h)) {
            hearts--
            sfx(Sound.HURT)
            if (hearts <= 0) { knockedOut(); return }
            toast(if (level.inLava(h)) "Hot hot hot! Watch out for lava!" else "Whoops! Mind the gap!")
            placeTeam(safeX - leader.facing * 8f, safeY)
            invuln = 1.6f
        }
    }

    // ======================================================================== Sparky, the frog and bosses

    private fun updateSparky(dt: Float) {
        val l = leader
        val tx = l.cx - l.facing * 36f - 8f
        val ty = l.y - 14f
        sparkyX += (tx - sparkyX) * min(1f, dt * 4f)
        sparkyY += (ty - sparkyY) * min(1f, dt * 4f)
        sparkyFacing = if (l.cx > sparkyX + 8) 1 else -1
        if (zapFlash > 0) zapFlash -= dt
        zapTimer -= dt
        if (zapTimer > 0) return
        val sx = sparkyX + 8
        val sy = sparkyY + 6
        val target = foes.filter {
            it.active && !it.dying && it.x + it.w > camX && it.x < camX + viewW &&
                (it !is KragJr || it.engaged) && (it !is KingKrag || it.awake)
        }.minByOrNull { hypot(it.cx - sx, it.cy - sy) }
        if (target == null || hypot(target.cx - sx, target.cy - sy) > 180f) {
            zapTimer = 0.4f
            return
        }
        particles += Bolt(sx, sy, target.cx, target.cy)
        target.hit(this, 1)
        sfx(Sound.ZAP)
        zapFlash = 0.4f
        zapTimer = if (boss != null && !bossDone) 3.5f else 4.5f
        if (Random.nextInt(3) == 0) say(sx, sparkyY - 12, listOf("Spark spark!", "Zzzap!", "Sparky!").random(), 1.2f)
    }

    private fun updateFrog() {
        val f = frog
        if (f != null && f.removed) frog = null
        val spot = frogSpots.firstOrNull { leader.cx > it * TILE } ?: return
        frogSpots.remove(spot)
        val fr = Frog(camX - 30f, leader.bottom - 26f)
        frog = fr
        spawn(fr)
        // Ring-Ding Frog sings out loud with the phone's voice.
        sound.say("Ring ding ding ding ding ding! Bing bong a dong!")
    }

    fun onBossEngaged(f: Foe) {
        if (boss != null) return
        boss = f
        val home = when (f) { is KragJr -> f.homeX; is KingKrag -> f.homeX; else -> f.x }
        camLock = (home + 16f - viewW / 2).coerceIn(0f, max(0f, level.w * TILE - viewW))
        bannerT = 3f
        if (f is KragJr) {
            wonder = true
            bannerTitle = "WONDER KRAG JR.!"
            bannerSub = "The sky goes Wonder! Stomp him or zap him!"
            sfx(Sound.POWERUP)
        } else {
            bannerTitle = "KING KRAG!"
            bannerSub = "Jump over the fire, hit him with everything!"
            sfx(Sound.BOSS_FIRE)
        }
    }

    fun onBossBeaten(f: Foe) {
        bossDone = true
        bossDoneT = 0f
        shake = 0.5f
        sfx(Sound.VICTORY)
        say(leader.cx, leader.y - 10, if (f is KragJr) "Teamwork!" else "We did it!!", 2f)
    }

    private fun updateBoss(dt: Float) {
        if (wonder && !bossDone) {
            // Wonder rain: rainbow sparkles fall all over the arena.
            if (Random.nextFloat() < dt * 10) {
                particles += Sparkle(camX + Random.nextFloat() * viewW, -4f, Random.nextFloat() * 20 - 10, 70f, solidRainbow(time, Random.nextFloat()))
            }
        }
        if (!bossDone) return
        bossDoneT += dt
        if (bossDoneT > 2f) {
            wonder = false
            camLock = null
            if (boss is KingKrag && state == State.PLAY && bossDoneT > 3f) {
                setState(State.VICTORY)
                sound.say("Ring ding ding ding! You did it! Ding ding!")
            }
        }
    }

    private fun updateCamera(dt: Float) {
        val maxCam = max(0f, level.w * TILE - viewW)
        val target = camLock ?: (leader.cx - viewW * 0.4f).coerceIn(0f, maxCam)
        val step = (if (camLock != null) 260f else 600f) * dt
        camX = if (abs(target - camX) < step) target else camX + (if (target > camX) step else -step)
        camX = camX.coerceIn(0f, maxCam)
    }

    private fun updateParticles(dt: Float) {
        for (p in particles) p.update(dt)
        particles.removeAll { it.life <= 0 }
    }

    private fun updateMusic() {
        val m = when {
            !musicOn -> Music.NONE
            state == State.TITLE -> Music.OVERWORLD
            state == State.VICTORY -> Music.FROG
            state == State.OUCH || state == State.CLEAR -> Music.NONE
            frog?.singing == true -> Music.FROG
            wonder -> Music.WONDER
            boss != null && !bossDone -> Music.BOSS
            else -> level.def.place.music
        }
        sound.music(m)
    }

    // ======================================================================== Rendering

    fun render(gfx: Gfx) {
        when (state) {
            State.TITLE -> renderTitle(gfx)
            State.INTRO -> renderIntro(gfx)
            else -> {
                renderWorld(gfx)
                renderHud(gfx)
                when (state) {
                    State.PLAY -> {
                        TeamControls.draw(
                            gfx, input, viewW,
                            if (leaderWho == Who.BLOCKY) "MINE" else if (power == Power.NONE) "PUNCH" else "THROW",
                            follower.who.label,
                            if (follower.who == Who.ROCCO) 0xFFE52521.toInt() else 0xFF2AA7A7.toInt(),
                        )
                        TeamControls.drawTop(gfx, viewW, musicOn, canCraftSomething, time)
                    }
                    State.CRAFT -> renderCraft(gfx)
                    State.OUCH -> {
                        gfx.rect(0f, 0f, viewW, VIEW_H, 0x88000000.toInt())
                        gfx.shadowText("OH NO!", viewW / 2, 106f, 22f, 0xFFFF8080.toInt(), 1)
                        gfx.shadowText("Back to the checkpoint. You keep your ore!", viewW / 2, 128f, 9f, 0xFFFFFFFF.toInt(), 1)
                    }
                    State.CLEAR -> {
                        gfx.shadowText("LEVEL CLEAR!", viewW / 2, 96f, 22f, 0xFFFFD21F.toInt(), 1)
                        gfx.shadowText("SCORE $score", viewW / 2, 118f, 10f, 0xFFFFFFFF.toInt(), 1)
                    }
                    State.VICTORY -> renderVictory(gfx)
                    else -> {}
                }
                if (bannerT > 0 && state == State.PLAY) {
                    gfx.shadowText(bannerTitle, viewW / 2, 74f, 16f, if (wonder) solidRainbow(time) else 0xFFFF6A3A.toInt(), 1)
                    gfx.shadowText(bannerSub, viewW / 2, 90f, 8f, 0xFFFFFFFF.toInt(), 1)
                }
                if (toastT > 0 && state == State.PLAY) {
                    val w = toast.length * 5.4f + 16
                    gfx.rect(viewW / 2 - w / 2, 46f, w, 16f, 0xAA000000.toInt())
                    gfx.text(toast, viewW / 2, 58f, 9f, 0xFFFFFFFF.toInt(), 1)
                }
                if (paused && state == State.PLAY) {
                    gfx.rect(0f, 0f, viewW, VIEW_H, 0x99000000.toInt())
                    gfx.shadowText("PAUSED", viewW / 2, 110f, 20f, 0xFFFFFFFF.toInt(), 1)
                    gfx.shadowText("Tap the pause button to keep playing", viewW / 2, 132f, 9f, 0xFFDDDDDD.toInt(), 1)
                    TeamControls.drawTop(gfx, viewW, musicOn, false, time)
                }
            }
        }
    }

    private fun renderWorld(gfx: Gfx) {
        val sx = if (shake > 0) sin(time * 80) * 3f else 0f
        val cam = camX + sx
        renderBackground(gfx, cam)
        renderTiles(gfx, cam)
        val ox = -cam
        for (t in things) if (t !is Shot && t !is Frog) t.draw(this, gfx, ox)
        for (f in foes) if (f.x + f.w > cam - 40 && f.x < cam + viewW + 40) f.draw(this, gfx, ox)
        follower.draw(this, gfx, ox)
        leader.draw(this, gfx, ox)
        TeamArt.sparky(gfx, sparkyX + ox, sparkyY, time, sparkyFacing, zapFlash > 0)
        for (t in things) if (t is Shot || t is Frog) t.draw(this, gfx, ox)
        for (p in particles) p.draw(gfx, ox)
        // A little arrow over the hero you control
        val l = leader
        if (state == State.PLAY) {
            val ay = l.y - 10 + sin(time * 6) * 1.5f
            gfx.poly(floatArrayOf(l.cx + ox - 4, l.cx + ox + 4, l.cx + ox), floatArrayOf(ay, ay, ay + 5), 0xFFFFFFFF.toInt())
        }
    }

    private fun renderBackground(gfx: Gfx, cam: Float) {
        if (wonder || state == State.VICTORY) {
            val bands = 7
            for (i in 0 until bands) {
                gfx.rect(0f, i * VIEW_H / bands, viewW, VIEW_H / bands + 1, argb(255, rainbow(time * 0.2f, i / bands.toFloat())))
            }
            gfx.rect(0f, 0f, viewW, VIEW_H, 0x66FFFFFF)
            return
        }
        when (level.def.place) {
            Place.HILLS -> {
                gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF6FB6FF.toInt())
                layer(cam, 0.2f, 260f) { i, x ->
                    gfx.oval(x, 150f - (i % 3) * 18f, 220f, 160f, 0xFF7FCB6A.toInt())
                }
                layer(cam, 0.35f, 180f) { i, x ->
                    // Blocky little trees in the distance
                    gfx.rect(x + 40, 170f, 10f, 40f, 0xFF6B4423.toInt())
                    gfx.rect(x + 25, 140f - (i % 2) * 10, 40f, 34f, 0xFF3E9A3A.toInt())
                }
                layer(cam, 0.1f, 200f) { i, x -> cloud(gfx, x + 30, 30f + (i % 3) * 22f) }
            }
            Place.CAVE -> {
                gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF1E1A2E.toInt())
                layer(cam, 0.3f, 90f) { i, x ->
                    gfx.poly(floatArrayOf(x, x + 18, x + 9), floatArrayOf(32f, 32f, 60f + (i % 4) * 14f), 0xFF2E2840.toInt())
                    if (i % 3 == 0) gfx.oval(x + 40, 120f + (i % 5) * 12f, 3f, 3f, Ore.entries[i % 5].color)
                }
            }
            Place.SKY -> {
                gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF8ED0FF.toInt())
                gfx.rect(0f, 160f, viewW, 80f, 0xFFB8E4FF.toInt())
                layer(cam, 0.15f, 150f) { i, x -> cloud(gfx, x, 40f + (i % 4) * 30f) }
                layer(cam, 0.3f, 210f) { i, x -> cloud(gfx, x + 20, 170f + (i % 2) * 20f) }
            }
            Place.CASTLE -> {
                gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF2A0E12.toInt())
                layer(cam, 0.3f, 120f) { i, x ->
                    gfx.rect(x + 20, 60f, 30f, 60f, 0xFF3A1A1E.toInt())
                    gfx.rect(x + 30, 75f, 10f, 16f, if (i % 2 == 0) 0xFFFF8A00.toInt() else 0xFF5A2A20.toInt())
                }
            }
        }
    }

    private inline fun layer(cam: Float, factor: Float, spacing: Float, draw: (Int, Float) -> Unit) {
        val off = cam * factor
        val first = (off / spacing).toInt() - 1
        val last = ((off + viewW) / spacing).toInt() + 1
        for (i in first..last) draw(i and 0xFFFF, i * spacing - off)
    }

    private fun cloud(gfx: Gfx, x: Float, y: Float) {
        gfx.oval(x, y, 50f, 20f, 0xFFFFFFFF.toInt())
        gfx.oval(x + 12, y - 10, 28f, 22f, 0xFFFFFFFF.toInt())
    }

    private fun renderTiles(gfx: Gfx, cam: Float) {
        val first = (cam / TILE).toInt() - 1
        val last = ((cam + viewW) / TILE).toInt() + 1
        val place = level.def.place
        for (ty in 0 until ROWS) for (tx in first..last) {
            if (tx < 0 || tx >= level.w) continue
            val t = level[tx, ty]
            if (t == Tile.EMPTY) continue
            val s = when (t) {
                Tile.GROUND -> when (place) {
                    Place.CAVE -> Sprites.stone
                    Place.CASTLE -> Sprites.hardGrey
                    else -> if (level[tx, ty - 1].let { it == Tile.GROUND || it == Tile.DIRT }) Sprites.dirt else Sprites.grassTop
                }
                Tile.DIRT -> Sprites.dirt
                Tile.BRICK -> if (place == Place.CAVE) Sprites.brickBlue else Sprites.brick
                Tile.QCOIN, Tile.QHEART -> if ((time * 3).toInt() % 3 == 0) Sprites.question2 else Sprites.question1
                Tile.USED -> Sprites.used
                Tile.HARD -> if (place == Place.CASTLE) Sprites.hardGrey else Sprites.hard
                Tile.LAVA -> if (level[tx, ty - 1] == Tile.LAVA) Sprites.lava else Sprites.lavaTop
                Tile.CLOUD -> Sprites.cloud
                else -> Tile.ore(t)?.let { TeamArt.ores[it] }
            } ?: continue
            val wob = if (wonder) sin(time * 4 + tx * 0.6f) * 2f else 0f
            val px = tx * TILE - cam
            val py = ty * TILE + wob
            gfx.sprite(s, px, py)
            val crack = level.cracks[ty * level.w + tx]
            if (crack != null) {
                val f = crack.toFloat() / Tile.toughness(t).coerceAtLeast(1)
                val c = 0xCC101010.toInt()
                gfx.rect(px + 7, py + 2, 2f, 12f * f, c)
                gfx.rect(px + 3, py + 6, 8f * f, 2f, c)
                if (f > 0.5f) { gfx.rect(px + 10, py + 9, 4f, 2f, c); gfx.rect(px + 2, py + 11, 2f, 4f, c) }
            }
        }
    }

    private fun renderHud(gfx: Gfx) {
        val white = 0xFFFFFFFF.toInt()
        gfx.rect(0f, 0f, viewW, 30f, 0x55000000)
        for (i in 0 until MAX_HEARTS) {
            TeamArt.heart(gfx, 8f + i * 13f, 4f, 11f, if (i < hearts) 0xFFE52521.toInt() else 0x66000000)
        }
        for (i in 0 until armor) TeamArt.shield(gfx, 8f + MAX_HEARTS * 13f + i * 11f, 3f, 10f)
        gfx.sprite(Sprites.coin, 8f, 17f, w = 8f, h = 11f)
        gfx.shadowText("x$coins", 18f, 26f, 8f, white)
        // Ore bag
        var x = max(118f, viewW * 0.26f)
        for (o in Ore.entries) {
            TeamArt.oreIcon(gfx, o, x, 5f, 11f)
            gfx.shadowText(inventory[o.ordinal].toString(), x + 14, 14f, 8f, white)
            x += 30f
        }
        val powerText = if (power == Power.NONE) "NO FLOWER" else "${power.label} Lv${flowerLevel[power.ordinal]}"
        gfx.shadowText("${TeamArt.pickName(pickTier)} PICK  •  $powerText", max(118f, viewW * 0.26f), 26f, 7f, 0xFFDDEEFF.toInt())

        val b = boss
        if (b != null && !bossDone && (b !is KragJr || b.engaged)) {
            val (hp, max, name) = when (b) {
                is KragJr -> Triple(b.hp, b.maxHp, "WONDER KRAG JR.")
                is KingKrag -> Triple(b.hp, b.maxHp, "KING KRAG")
                else -> Triple(0, 1, "")
            }
            val bw = 120f
            val bx = viewW / 2 - bw / 2
            gfx.shadowText(name, viewW / 2, 40f, 8f, 0xFFFFB0A0.toInt(), 1)
            gfx.rect(bx - 1, 43f, bw + 2, 7f, 0xFF000000.toInt())
            gfx.rect(bx, 44f, bw * hp / max, 5f, if (b is KragJr) solidRainbow(time) else 0xFFE53A1E.toInt())
        }
    }

    private fun renderCraft(gfx: Gfx) {
        gfx.rect(0f, 30f, viewW, VIEW_H - 30f, 0x88000000.toInt())
        val px = panelX()
        val pw = panelW()
        gfx.rect(px - 2, panelTop - 2, pw + 4, 22f + rowH * 6 + 6, 0xFF3A2410.toInt())
        gfx.rect(px, panelTop, pw, 22f + rowH * 6 + 2, 0xFFB98A52.toInt())
        // Wood-grain lines, like a crafting table
        for (i in 0 until 6) gfx.rect(px, panelTop + 22 + i * rowH + rowH - 1, pw, 1f, 0x55000000)
        gfx.shadowText("CRAFTING TABLE", px + pw / 2, panelTop + 15, 11f, 0xFFFFF2C8.toInt(), 1)
        gfx.oval(px + pw - 24, panelTop + 2, 18f, 18f, 0xFF8A1010.toInt())
        gfx.text("X", px + pw - 15, panelTop + 15, 10f, white(), 1)
        recipes().forEachIndexed { i, r ->
            val y = panelTop + 22 + i * rowH
            // Icon
            when {
                r.icon != null -> gfx.sprite(TeamArt.flowerSprite(r.icon), px + 6, y + 6, w = 18f, h = 18f)
                r.title == "HEART" -> TeamArt.heart(gfx, px + 8, y + 8, 14f, 0xFFE52521.toInt())
                else -> TeamArt.pickaxe(gfx, px + 9, y + 23, -0.9f, min(4, pickTier + 1))
            }
            gfx.text(r.title, px + 30, y + 13, 8f, 0xFF2A1808.toInt())
            gfx.text(r.note, px + 30, y + 23, 6f, 0xFF4A3018.toInt())
            // Cost
            if (r.cost > 0) {
                val cx = px + pw - 104
                TeamArt.oreIcon(gfx, r.ore, cx, y + 9, 11f)
                val have = inventory[r.ore.ordinal]
                gfx.text("${r.cost}", cx + 14, y + 18, 8f, if (have >= r.cost) 0xFF1E5A1E.toInt() else 0xFF8A1010.toInt())
                gfx.text("(${have})", cx + 23, y + 18, 6f, 0xFF4A3018.toInt())
            }
            val bx = px + pw - 52
            val color = when {
                !r.enabled -> 0xFF8A7A6A.toInt()
                r.button == "EQUIP" -> 0xFF2E6BE6.toInt()
                else -> 0xFF2EB84A.toInt()
            }
            gfx.rect(bx, y + 5, 46f, 20f, 0xFF2A1808.toInt())
            gfx.rect(bx + 1.5f, y + 6.5f, 43f, 17f, color)
            gfx.text(r.button, bx + 23, y + 18, 8f, white(), 1)
            gfx.text("${i + 1}", px + 3, y + 10, 5f, 0x88000000.toInt())
        }
        if (toastT > 0) {
            val w = toast.length * 5.4f + 16
            gfx.rect(viewW / 2 - w / 2, VIEW_H - 18, w, 16f, 0xDD000000.toInt())
            gfx.text(toast, viewW / 2, VIEW_H - 6, 9f, 0xFFFFFFFF.toInt(), 1)
        }
    }

    private fun white() = 0xFFFFFFFF.toInt()

    private fun renderTitle(gfx: Gfx) {
        if (level.def !== Levels.all[0]) level = Level(Levels.all[0])
        camX = time * 20f % (level.w * TILE - viewW)
        renderBackground(gfx, camX)
        renderTiles(gfx, camX)
        gfx.rect(0f, 0f, viewW, VIEW_H, 0x44000000)
        gfx.shadowText("ROCCO & BLOCKY", viewW / 2, 52f, 24f, 0xFFFFD21F.toInt(), 1)
        gfx.shadowText("TEAM UP!", viewW / 2, 78f, 18f, solidRainbow(time * 0.3f), 1)
        gfx.shadowText("Mine ore • Craft flowers • Stop King Krag", viewW / 2, 94f, 8f, 0xFFFFFFFF.toInt(), 1)
        val bob = abs(sin(time * 4)) * 3f
        gfx.sprite(TeamArt.rocco(Power.NONE, 0), viewW / 2 - 40, 186f - 24 - bob)
        gfx.sprite(TeamArt.blocky(Power.NONE, false), viewW / 2 - 12, 186f - 24)
        TeamArt.pickaxe(gfx, viewW / 2 - 4 + 2, 186f - 11, -2.3f, 4)
        TeamArt.sparky(gfx, viewW / 2 + 14, 186f - 18, time, -1, false)
        TeamArt.frog(gfx, viewW / 2 + 40, 186f - 26 - abs(sin(time * 9)) * 4, time)
        val hasSave = savedLevel > 0
        if (hasSave) {
            button(gfx, viewW * 0.25f, 214f, "NEW GAME", 0xFF2E6BE6.toInt())
            button(gfx, viewW * 0.75f, 214f, "CONTINUE ${savedLevel + 1}", 0xFF2EB84A.toInt())
        } else if ((time * 2).toInt() % 2 == 0) {
            gfx.shadowText("TAP TO START", viewW / 2, 218f, 12f, 0xFFFFFFFF.toInt(), 1)
        }
    }

    private fun button(gfx: Gfx, cx: Float, cy: Float, label: String, color: Int) {
        val w = 120f
        gfx.rect(cx - w / 2 - 2, cy - 14, w + 4, 26f, 0xFF101010.toInt())
        gfx.rect(cx - w / 2, cy - 12, w, 22f, color)
        gfx.text(label, cx, cy + 3, 10f, 0xFFFFFFFF.toInt(), 1)
    }

    private fun renderIntro(gfx: Gfx) {
        gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF101018.toInt())
        gfx.shadowText("LEVEL ${levelIndex + 1}", viewW / 2, 70f, 12f, 0xFFDDDDDD.toInt(), 1)
        gfx.shadowText(level.def.name, viewW / 2, 96f, 20f, 0xFFFFD21F.toInt(), 1)
        gfx.sprite(TeamArt.rocco(power, 0), viewW / 2 - 30, 112f)
        gfx.sprite(TeamArt.blocky(power, false), viewW / 2 - 8, 112f)
        TeamArt.sparky(gfx, viewW / 2 + 16, 118f, time, -1, false)
        gfx.shadowText(level.def.tip, viewW / 2, 162f, 8f, 0xFFFFFFFF.toInt(), 1)
        gfx.shadowText("Hearts: $MAX_HEARTS   Pickaxe: ${TeamArt.pickName(pickTier)}", viewW / 2, 180f, 8f, 0xFFAADDFF.toInt(), 1)
    }

    private fun renderVictory(gfx: Gfx) {
        gfx.rect(0f, 0f, viewW, VIEW_H, 0x55000000)
        gfx.shadowText("YOU BEAT KING KRAG!", viewW / 2, 70f, 18f, solidRainbow(time * 0.5f), 1)
        gfx.shadowText("Rocco + Blocky + Sparky = BEST TEAM EVER!", viewW / 2, 90f, 9f, 0xFFFFFFFF.toInt(), 1)
        gfx.shadowText("SCORE $score", viewW / 2, 106f, 10f, 0xFFFFD21F.toInt(), 1)
        val dance = abs(sin(time * 6)) * 6f
        val base = 170f
        gfx.sprite(TeamArt.rocco(power, 2), viewW / 2 - 60, base - 24 - dance)
        gfx.sprite(TeamArt.blocky(power, (time * 4).toInt() % 2 == 0), viewW / 2 - 30, base - 24 - (6 - dance))
        TeamArt.sparky(gfx, viewW / 2, base - 18 - dance, time, 1, true)
        TeamArt.frog(gfx, viewW / 2 + 30, base - 26 - (6 - dance), time)
        if (stateT > 3f) gfx.shadowText("Tap to play again", viewW / 2, 206f, 9f, 0xFFDDDDDD.toInt(), 1)
    }
}
