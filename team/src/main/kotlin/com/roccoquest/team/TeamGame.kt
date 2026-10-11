package com.roccoquest.team

import com.roccoquest.game.Body
import com.roccoquest.game.Gfx
import com.roccoquest.game.Music
import com.roccoquest.game.Sound
import com.roccoquest.game.SoundSink
import com.roccoquest.game.Sprites
import com.roccoquest.game.Storage
import com.roccoquest.game.Voice
import com.roccoquest.game.TILE
import com.roccoquest.game.VIEW_H
import com.roccoquest.game.argb
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
 * Rocco the plumber, Blocky the miner and a Pokemon partner team up to stop King Krag.
 * You control one of them and the others follow; SWAP switches. Blocky mines ore, the
 * crafting table turns ore into flower powers, pickaxes, supplies, a house and a bed,
 * and sleeping in the bed saves the game into one of three save slots.
 */
class TeamGame(private val input: TeamInput, private val sound: SoundSink, private val storage: Storage = Storage.Memory()) {
    enum class State { TITLE, SLOTS, PICK, INTRO, PLAY, CRAFT, TRADE, SLEEP, OUCH, CLEAR, STICKER, BOOK, VICTORY }

    companion object {
        const val MAX_HEARTS = 5
        const val MAX_ARMOR = 5
        const val COST = 3
        const val SLOTS = 3
        /** Seconds for the Pokemon's SUPER move to recharge. */
        const val SUPER_TIME = 25f
        const val STAR_TIME = 10f
        /** Seconds of air underwater before the team starts losing hearts. */
        const val AIR_MAX = 30f
        const val POSEIDON_COST = 50
        const val SCORE_MULT = 3
        val TABS = listOf("FLOWERS", "SUPPLIES", "HOME", "SWORDS")
    }

    var viewW = 427f
    var state = State.TITLE
        private set
    var time = 0f
        private set
    private var stateT = 0f

    var slot = 0
        private set
    var levelIndex = 0
        private set
    var level = Level(Levels.all[0])
        private set

    val rocco = Hero(Who.ROCCO, 0f, 0f)
    val blocky = Hero(Who.BLOCKY, 0f, 0f)
    val partner = Hero(Who.MON, 0f, 0f)
    val team = listOf(rocco, blocky, partner)
    var mon: Mon
        get() = partner.mon
        private set(value) { partner.mon = value }
    var leaderWho = Who.ROCCO
        private set
    val leader get() = team.first { it.who == leaderWho }
    val followers get() = team.filter { it.who != leaderWho }

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
    var starT = 0f
        private set
    var coins = 0
    var score = 0
        private set
    val inventory = IntArray(Ore.entries.size)
    var pickTier = 1
        private set
    /** Blocky's equipped sword, and which swords he's made (one bit each). */
    var sword = Sword.NONE
        private set
    private var swordsOwned = 0
    fun hasSword(s: Sword) = swordsOwned and (1 shl s.ordinal) != 0
    var power = Power.NONE
        private set
    val flowerLevel = IntArray(Power.entries.size)

    // Pokemon SUPER move
    var superCharge = 0f
        private set
    var superFlash = 0f
        private set
    private var followZap = 3f

    // House and bed, for the current level only
    private var house: House? = null
    private var bed: Bed? = null

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
    private var clearNote = ""

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
    private var craftTab = 0
    private var eraseArmed = -1
    private var eraseT = 0f
    private var pickChoice = Mon.SPARKY
    private val prev = BooleanArray(11)
    private var downE = false
    private var longE = false
    // Double-tap a direction to sprint.
    private var prevDir = 0
    private var lastTapDir = 0
    private var lastTapTime = -1f
    val sprinting get() = leader.sprinting

    // TNT, Poseidon, swimming, minecarts, villagers and Dennis
    var tnt = 0
    var air = AIR_MAX
        private set
    var riding: Minecart? = null
        private set
    var floodT = 0f
        private set
    private var trader: Villager? = null
    private var dogT = 10f
    private val ventT = HashMap<Int, Float>()
    private var spikeT = 0f

    // Stickers: one bit per sticker you own, the three on offer, and what comes next.
    private var stickerBits = 0L
    var stickerChoices: List<Int> = emptyList()
        private set
    var stickerPicked = -1
        private set
    private var afterSticker = 0
    fun hasSticker(i: Int) = stickerBits and (1L shl i) != 0L
    val stickerCount get() = Stickers.all.indices.count { hasSticker(it) }
    /** Seconds left of Mini Rocco. */
    var miniT = 0f
        private set

    fun sfx(s: Sound) = sound.play(s)
    fun spawn(t: Thing) { pending += t }
    fun addParticle(p: Particle) { particles += p }
    fun puff(x: Float, y: Float) { particles += Puff(x, y) }
    fun say(x: Float, y: Float, text: String, dur: Float = 1.8f) { particles += Speech(x, y, text, dur) }
    /** Every point is worth [SCORE_MULT] times as much: lots of points! */
    fun addScore(n: Int, x: Float, y: Float) {
        score += n * SCORE_MULT
        particles += FloatText(x, y, (n * SCORE_MULT).toString(), 0xFFFFE14D.toInt())
    }
    private fun points(n: Int) { score += n * SCORE_MULT }

    /** A bad guy was knocked out: it drops coins (tough ones drop more). */
    fun onFoeDefeated(f: Foe) {
        if (f is SpikeBall || f is Flame || f is SonicBoom || f is DragonBall || f is BombBomb) return
        val n = when {
            f.maxHp >= 10 -> 5
            f.maxHp >= 4 -> 3
            else -> 1
        }
        repeat(n) { i -> spawn(Coin(f.cx - 8 + (i - (n - 1) / 2f) * 7f, f.y - 4, popping = true)) }
    }

    /** Stomping bad guys one after another without landing scores more and more. */
    private fun stompCombo(h: Hero, f: Foe) {
        h.combo++
        val bonus = min(8000, 100 shl (h.combo - 1))
        addScore(bonus, f.cx, f.y - 8)
        if (h.combo >= 2) particles += FloatText(f.cx, f.y - 18, "COMBO x${h.combo}!", solidRainbow(time))
        if (h.combo == 5) {
            healHeart()
            toast("5 STOMPS IN A ROW! +1 HEART")
        }
    }
    private fun toast(s: String) { toast = s; toastT = 2.4f }

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

    // ======================================================================== Save slots

    private fun key(name: String, s: Int = slot) = "slot${s}_$name"

    /** The level a save slot is on, or -1 if it's empty. */
    fun slotLevel(s: Int) = storage.load(key("level", s)) - 1
    fun slotMon(s: Int): Mon = Mon.entries.getOrElse(storage.load(key("mon", s)) - 1) { Mon.SPARKY }
    fun slotSlept(s: Int) = storage.load(key("bedLevel", s)) > 0

    /** Kept for older callers: the level of the most recently played slot. */
    val savedLevel get() = slotLevel(slot)

    private fun save() {
        storage.save(key("level"), levelIndex + 1)
        storage.save(key("mon"), mon.ordinal + 1)
        Ore.entries.forEach { storage.save(key("ore_${it.name}"), inventory[it.ordinal]) }
        Power.entries.forEach { storage.save(key("flower_${it.name}"), flowerLevel[it.ordinal]) }
        storage.save(key("pick"), pickTier)
        storage.save(key("power"), power.ordinal)
        storage.save(key("coins"), coins)
        storage.save(key("armor"), armor)
        storage.save(key("tnt"), tnt)
        storage.save(key("stickersLo"), (stickerBits and 0xFFFFFFFFL).toInt())
        storage.save(key("stickersHi"), (stickerBits ushr 32).toInt())
        storage.save(key("swords"), swordsOwned)
        storage.save(key("sword"), sword.ordinal)
        // The house (and bed) belong to the level they were built in.
        val h = house
        storage.save(key("houseLevel"), if (h != null) levelIndex + 1 else 0)
        storage.save(key("houseX"), h?.x?.toInt() ?: 0)
        storage.save(key("houseY"), h?.bottom?.toInt() ?: 0)
        storage.save(key("bed"), if (bed != null) 1 else 0)
    }

    /** Sleeping in the bed remembers exactly where you were. */
    private fun saveAtBed() {
        save()
        storage.save(key("bedLevel"), levelIndex + 1)
        storage.save(key("spotLevel"), 0)
    }

    /** Shows "SAVED!" on the pause screen for a moment. */
    private var savedFlash = 0f

    /** The SAVE GAME button on the pause screen: saves everything, right where the team is standing. */
    fun saveHere() {
        save()
        storage.save(key("bedLevel"), 0)
        storage.save(key("spotLevel"), levelIndex + 1)
        storage.save(key("spotX"), safeX.toInt())
        storage.save(key("spotY"), safeY.toInt())
        respawnX = safeX
        respawnY = safeY
        savedFlash = 2f
        sfx(Sound.ONEUP)
    }

    /** True when the file was saved with SAVE GAME (not in a bed). */
    fun slotSpotSaved(s: Int) = storage.load(key("spotLevel", s)) > 0

    private fun loadSlot() {
        Ore.entries.forEach { inventory[it.ordinal] = storage.load(key("ore_${it.name}")) }
        Power.entries.forEach { flowerLevel[it.ordinal] = storage.load(key("flower_${it.name}")) }
        pickTier = storage.load(key("pick")).coerceIn(1, 5)
        power = Power.entries.getOrElse(storage.load(key("power"))) { Power.NONE }
        if (flowerLevel[power.ordinal] == 0) power = Power.NONE
        coins = storage.load(key("coins"))
        armor = storage.load(key("armor"))
        tnt = storage.load(key("tnt"))
        stickerBits = (storage.load(key("stickersLo")).toLong() and 0xFFFFFFFFL) or (storage.load(key("stickersHi")).toLong() shl 32)
        swordsOwned = storage.load(key("swords"))
        sword = Sword.entries.getOrElse(storage.load(key("sword"))) { Sword.NONE }
        if (!hasSword(sword)) sword = Sword.NONE
        mon = slotMon(slot)
    }

    fun eraseSlot(s: Int) {
        for (name in listOf("level", "mon", "pick", "power", "coins", "armor", "tnt", "swords", "sword", "stickersLo", "stickersHi", "houseLevel", "houseX", "houseY", "bed", "bedLevel", "spotLevel", "spotX", "spotY")) {
            storage.save(key(name, s), 0)
        }
        Ore.entries.forEach { storage.save(key("ore_${it.name}", s), 0) }
        Power.entries.forEach { storage.save(key("flower_${it.name}", s), 0) }
    }

    fun newGame(slot: Int = 0, mon: Mon = Mon.SPARKY) {
        this.slot = slot
        eraseSlot(slot)
        this.mon = mon
        inventory.fill(0)
        flowerLevel.fill(0)
        pickTier = 1
        power = Power.NONE
        coins = 0
        score = 0
        armor = 0
        tnt = 0
        stickerBits = 0L
        swordsOwned = 0
        sword = Sword.NONE
        startLevel(0)
    }

    fun continueGame(slot: Int = this.slot) {
        this.slot = slot
        loadSlot()
        score = 0
        val lv = slotLevel(slot).coerceIn(0, Levels.all.lastIndex)
        startLevel(lv, restoreHome = true)
    }

    // ======================================================================== Levels

    private fun setState(s: State) { state = s; stateT = 0f }

    fun startLevel(index: Int, fromCheckpoint: Boolean = false, restoreHome: Boolean = false) {
        val keepX = respawnX
        val keepY = respawnY
        val keepHouse = if (index == levelIndex) house else null
        val keepBed = if (index == levelIndex) bed else null
        levelIndex = index
        level = Level(Levels.all[index])
        foes.clear(); things.clear(); pending.clear(); particles.clear(); frogSpots.clear()
        boss = null; bossDone = false; wonder = false; camLock = null; frog = null; goal = null
        house = null; bed = null
        riding = null; trader = null; floodT = 0f; air = AIR_MAX; dogT = 10f; ventT.clear()
        if (rocco.mini) setMini(false)
        miniT = 0f
        hearts = MAX_HEARTS
        if (power == Power.GRASS) armor = max(armor, 2)
        invuln = 0f
        starT = 0f
        leaderWho = Who.ROCCO
        for (ty in 0 until ROWS) for (tx in 0 until level.w) {
            val px = tx * TILE.toFloat()
            val bottom = (ty + 1) * TILE.toFloat()
            when (level.def.rows[ty][tx]) {
                '@' -> { respawnX = px; respawnY = bottom }
                'g' -> foes += Grumbler(px, bottom, false)
                'w' -> foes += Grumbler(px, bottom, true)
                'k' -> foes += Shellback(px, bottom)
                'b' -> foes += Bonehead(px, bottom)
                'v' -> foes += Flapper(px, bottom)
                'J' -> foes += KragJr(px, bottom)
                'W' -> foes += KragJr(px, bottom, wonderStyle = true)
                'K' -> foes += KingKrag(px, bottom)
                'Q' -> foes += KingKrag(px, bottom, wonderStyle = true)
                'Y' -> foes += KingKrag(px, bottom, dry = true)
                'G' -> foes += Warden(px, bottom)
                'Z' -> foes += EnderDragon(px, ty * TILE.toFloat())
                'n' -> foes += Enderman(px, bottom)
                'f' -> foes += Fish(px, bottom)
                'p' -> foes += BombBomb(px, bottom)
                'C' -> foes += EndCrystal(px, bottom)
                'V' -> things += Villager(px, bottom, tx + index * 7)
                'M' -> things += Minecart(px, bottom)
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
            keepHouse?.let { house = House(it.x, it.bottom); things += house!! }
            keepBed?.let { bed = Bed(it.x, it.bottom); things += bed!! }
        }
        if (restoreHome && storage.load(key("houseLevel")) == index + 1) {
            val hx = storage.load(key("houseX")).toFloat()
            val hb = storage.load(key("houseY")).toFloat()
            house = House(hx, hb).also { things += it }
            if (storage.load(key("bed")) == 1) bed = Bed(hx + 34, hb).also { things += it }
            // Wake up next to the bed if that's where you saved.
            if (storage.load(key("bedLevel")) == index + 1 && bed != null) {
                respawnX = hx + 30
                respawnY = hb
                things.filterIsInstance<Checkpoint>().forEach { if (it.x <= respawnX) it.reached = true }
            }
        }
        // Pick up right where SAVE GAME was pressed.
        val spotSaved = restoreHome && storage.load(key("spotLevel")) == index + 1
        if (spotSaved) {
            respawnX = storage.load(key("spotX")).toFloat()
            respawnY = storage.load(key("spotY")).toFloat()
            things.filterIsInstance<Checkpoint>().forEach { if (it.x <= respawnX) it.reached = true }
        }
        placeTeam(respawnX, respawnY)
        camX = (leader.cx - viewW * 0.4f).coerceIn(0f, max(0f, level.w * TILE - viewW))
        if (!fromCheckpoint) {
            storage.save(key("spotLevel"), if (spotSaved) index + 1 else 0)
            storage.save(key("bedLevel"), if (restoreHome && storage.load(key("bedLevel")) == index + 1) index + 1 else 0)
            save()
        }
        setState(State.INTRO)
    }

    private fun placeTeam(x: Float, bottom: Float) {
        for ((i, h) in team.withIndex()) {
            h.x = (x + 2 - i * 18f).coerceAtLeast(2f)
            h.y = bottom - h.h
            h.vx = 0f; h.vy = 0f; h.facing = 1; h.stuck = 0f
        }
        safeX = x; safeY = bottom
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
        val superE = edge(5, input.superMove)
        val actionE = edge(6, input.action)
        val tntE = edge(7, input.tnt)
        val poseidonE = edge(8, input.poseidon)
        downE = edge(9, input.down)
        longE = edge(10, input.longJump)
        val tapped = input.tap
        input.tap = false
        val num = input.numberKey
        input.numberKey = 0
        if (toastT > 0) toastT -= dt
        if (eraseT > 0) eraseT -= dt else eraseArmed = -1

        when (state) {
            State.TITLE -> if ((tapped || num > 0) && stateT > 0.4f) setState(State.SLOTS)
            State.SLOTS -> updateSlots(tapped, num)
            State.PICK -> updatePick(tapped, num)
            State.INTRO -> if (stateT > 3f || (tapped && stateT > 0.6f)) setState(State.PLAY)
            State.PLAY -> {
                if (pauseE) paused = !paused
                // The STICKER BOOK button on the pause screen.
                if (paused && tapped && input.tapY in 150f..178f && abs(input.tapX - viewW / 2) < 70f) { setState(State.BOOK); return }
                // ...and the SAVE GAME button under it.
                if (paused && tapped && input.tapY in 184f..212f && abs(input.tapX - viewW / 2) < 70f) saveHere()
                if (savedFlash > 0) savedFlash -= dt
                if (!paused) {
                    if (craftE) { setState(State.CRAFT); sfx(Sound.BUMP) } else {
                        if (tntE) throwTnt()
                        if (poseidonE) callPoseidon()
                        play(dt, jumpE, swapE, superE, actionE)
                    }
                }
            }
            State.CRAFT -> updateCraft(tapped, craftE || pauseE, num)
            State.TRADE -> updateTrade(tapped, pauseE || craftE, num)
            State.SLEEP -> {
                updateParticles(dt)
                if (stateT > 2.6f) {
                    hearts = MAX_HEARTS
                    bed?.let { respawnX = it.x - 4; respawnY = it.bottom }
                    saveAtBed()
                    toast("Good morning! Game saved in SLOT ${slot + 1}.")
                    sfx(Sound.ONEUP)
                    setState(State.PLAY)
                }
            }
            State.OUCH -> {
                updateParticles(dt)
                if (stateT > 2.2f) startLevel(levelIndex, fromCheckpoint = true)
            }
            State.CLEAR -> {
                updateParticles(dt)
                goal?.let { it.flagY = min(it.h - 30f, it.flagY + 120f * dt) }
                if (stateT > 3.4f) {
                    clearNote = ""
                    offerStickers(if (levelIndex + 1 < Levels.all.size) levelIndex + 1 else -1)
                }
            }
            State.STICKER -> updateStickers(tapped, num)
            State.BOOK -> if ((tapped || pauseE || num > 0) && stateT > 0.3f) setState(State.PLAY)
            State.VICTORY -> {
                updateParticles(dt)
                if (Random.nextFloat() < dt * 12) {
                    particles += Sparkle(Random.nextFloat() * viewW + camX, -4f, 0f, 60f + Random.nextFloat() * 60, solidRainbow(time, Random.nextFloat()))
                }
                if (tapped && stateT > 3f) setState(State.TITLE)
            }
        }
        updateMusic()
    }

    // ---------------------------------------------------------------- Stickers

    /** After a level: offer three stickers to pick from. [next] is the next level, or -1 for the ending. */
    private fun offerStickers(next: Int) {
        afterSticker = next
        stickerPicked = -1
        val missing = Stickers.all.indices.filter { !hasSticker(it) }.shuffled()
        val pool = if (missing.size >= 3) missing else (missing + Stickers.all.indices.shuffled()).distinct()
        // Beating a boss always offers that boss's shiny sticker.
        val bossChar = level.def.rows.firstNotNullOfOrNull { r -> r.firstOrNull { it in "JWKQGYZ" } }
        val bossSticker = bossChar?.let { Stickers.forBoss(it) }?.takeIf { it >= 0 && !hasSticker(it) }
        stickerChoices = (listOfNotNull(bossSticker) + pool).distinct().take(3).shuffled()
        setState(State.STICKER)
        sfx(Sound.ONEUP)
    }

    fun pickSticker(n: Int) {
        if (stickerPicked >= 0 || n !in stickerChoices.indices) return
        val i = stickerChoices[n]
        stickerPicked = i
        stickerBits = stickerBits or (1L shl i)
        stateT = 0f
        sfx(Sound.POWERUP)
        sound.sayAs("You got the ${Stickers.all[i].name.lowercase()} sticker!", Voice.HERO)
        save()
    }

    private fun stickerCardW() = min(110f, (viewW - 60f) / 3)
    private fun stickerCardX(n: Int) = viewW / 2 - 3 * (stickerCardW() + 14f) / 2 + n * (stickerCardW() + 14f) + 7f

    private fun updateStickers(tapped: Boolean, num: Int) {
        if (stickerPicked < 0) {
            if (num in 1..3) pickSticker(num - 1)
            if (tapped && stateT > 0.5f) {
                for (n in stickerChoices.indices) {
                    val x = stickerCardX(n)
                    if (input.tapX in x..(x + stickerCardW()) && input.tapY in 56f..196f) pickSticker(n)
                }
            }
            return
        }
        if (stateT > 2.5f || ((tapped || num > 0) && stateT > 0.8f)) {
            if (afterSticker >= 0) startLevel(afterSticker) else {
                setState(State.VICTORY)
                sound.say("Ring ding ding ding! You did it! Ding ding!")
            }
        }
    }

    private fun renderStickers(gfx: Gfx) {
        gfx.rect(0f, 0f, viewW, VIEW_H, 0xEE1A1438.toInt())
        for (i in 0 until 30) {
            val sx = (i * 61f + time * 20f) % viewW
            gfx.oval(sx, (i * 37f) % VIEW_H, 2f, 2f, 0x66FFFFFF)
        }
        val title = if (stickerPicked < 0) "PICK A STICKER!" else "NEW STICKER!"
        gfx.shadowText(title, viewW / 2, 32f, 16f, solidRainbow(time * 0.4f), 1)
        gfx.shadowText("Sticker book: $stickerCount / ${Stickers.all.size}", viewW / 2, 47f, 8f, 0xFFDDDDDD.toInt(), 1)
        val w = stickerCardW()
        stickerChoices.forEachIndexed { n, i ->
            val picked = stickerPicked == i
            if (stickerPicked >= 0 && !picked) {
                // The ones you didn't pick fade away.
                val x = stickerCardX(n)
                gfx.rect(x, 62f, w, 130f, 0x33FFFFFF)
                return@forEachIndexed
            }
            val bob = if (stickerPicked < 0) sin(time * 3 + n) * 3f else 0f
            val grow = if (picked) min(1f, stateT * 3) * 10f else 0f
            val x = stickerCardX(n) - grow / 2
            Stickers.drawCard(this, gfx, i, x, 62f + bob - grow / 2, w + grow, 130f + grow)
            val st = Stickers.all[i]
            gfx.shadowText(st.rarity.label, x + (w + grow) / 2, 206f + bob, 7f, argb(255, st.rarity.color), 1)
            if (stickerPicked < 0) gfx.text("${n + 1}", x + 6, 74f + bob, 7f, 0xAAFFFFFF.toInt())
        }
        val hint = if (stickerPicked < 0) "Tap the one you want!" else "Added to your sticker book! Tap to keep going."
        gfx.shadowText(hint, viewW / 2, 228f, 9f, white, 1)
    }

    private fun renderBook(gfx: Gfx) {
        gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF2A1A10.toInt())
        gfx.rect(6f, 6f, viewW - 12f, VIEW_H - 12f, 0xFFE8D8B0.toInt())
        gfx.shadowText("STICKER BOOK  $stickerCount / ${Stickers.all.size}", viewW / 2, 22f, 11f, 0xFFFFD21F.toInt(), 1)
        val cols = 8
        val cw = min(46f, (viewW - 24f) / cols)
        val ch = 50f
        val gx = viewW / 2 - cols * cw / 2
        Stickers.all.indices.forEach { i ->
            val c = i % cols
            val r = i / cols
            Stickers.drawCard(this, gfx, i, gx + c * cw + 2, 28f + r * (ch - 8f), cw - 4, ch - 12, hasSticker(i))
        }
        gfx.text("Tap to close", viewW - 12f, 22f, 6f, 0xFF4A3018.toInt(), 2)
    }

    // ---------------------------------------------------------------- Title menus

    private fun slotCardW() = min(130f, (viewW - 40f) / SLOTS)
    private fun slotCardX(i: Int) = viewW / 2 - SLOTS * (slotCardW() + 8f) / 2 + i * (slotCardW() + 8f) + 4f

    private fun updateSlots(tapped: Boolean, num: Int) {
        if (num in 1..SLOTS) { chooseSlot(num - 1); return }
        if (!tapped || stateT < 0.3f) return
        val x = input.tapX
        val y = input.tapY
        for (i in 0 until SLOTS) {
            val cx = slotCardX(i)
            if (x < cx || x > cx + slotCardW()) continue
            // The ERASE strip at the bottom of a used card.
            if (slotLevel(i) >= 0 && y > 176f && y < 200f) {
                if (eraseArmed == i) {
                    eraseSlot(i)
                    eraseArmed = -1
                    sfx(Sound.BREAK)
                } else {
                    eraseArmed = i
                    eraseT = 3f
                    sfx(Sound.BUMP)
                }
                return
            }
            if (y in 60f..176f) chooseSlot(i)
        }
    }

    private fun chooseSlot(i: Int) {
        slot = i
        sfx(Sound.COIN)
        if (slotLevel(i) >= 0) continueGame(i) else { pickChoice = Mon.SPARKY; setState(State.PICK) }
    }

    private val pickRows = listOf(0..4, 5..8)
    private fun pickCardW() = min(78f, (viewW - 30f) / 5 - 6f)
    private val pickCardH = 74f
    private fun pickCardX(i: Int): Float {
        val row = if (i < 5) 0 else 1
        val n = pickRows[row].count()
        val k = i - pickRows[row].first
        return viewW / 2 - n * (pickCardW() + 6f) / 2 + k * (pickCardW() + 6f) + 3f
    }
    private fun pickCardY(i: Int) = if (i < 5) 46f else 46f + pickCardH + 6f
    private fun goButtonX() = viewW - 70f

    private fun updatePick(tapped: Boolean, num: Int) {
        if (num in 1..Mon.entries.size) { choose(Mon.entries[num - 1]); return }
        if (!tapped || stateT < 0.3f) return
        val x = input.tapX
        val y = input.tapY
        if (x > goButtonX() - 30 && y > 206f) { choose(pickChoice); return }
        for (i in Mon.entries.indices) {
            val cx = pickCardX(i)
            val cy = pickCardY(i)
            if (x in cx..(cx + pickCardW()) && y in cy..(cy + pickCardH)) {
                val m = Mon.entries[i]
                // Tap once to look, tap the same one again (or GO) to pick it.
                if (pickChoice == m && stateT > 0.6f) { choose(m); return }
                pickChoice = m
                sound.sayAs(m.label, Voice.MON)
                sfx(Sound.KICK)
                return
            }
        }
    }

    private fun choose(m: Mon) {
        sound.sayAs("${m.label}! I choose you!", Voice.HERO)
        newGame(slot, m)
    }

    // ---------------------------------------------------------------- Playing

    private fun play(dt: Float, jumpE: Boolean, swapE: Boolean, superE: Boolean, actionE: Boolean) {
        if (invuln > 0) invuln -= dt
        if (bannerT > 0) bannerT -= dt
        if (shake > 0) shake -= dt
        if (superFlash > 0) superFlash -= dt
        if (starT > 0) {
            starT -= dt
            invuln = max(invuln, 0.1f)
        }
        superCharge = min(1f, superCharge + dt / SUPER_TIME)
        if (swapE) swap()
        if (superE) useSuper()

        if (floodT > 0) floodT -= dt
        if (miniT > 0) {
            miniT -= dt
            // Grow back once there's room above Rocco.
            if (miniT <= 0 && !level.solidAt(rocco.cx, rocco.bottom - 24f)) endMini() else if (miniT <= 0) miniT = 0.1f
        }
        val grav = if (wonder) 0.75f else 1f
        val cart = riding
        if (cart != null) {
            // Riding a minecart: the leader sits in the cart, JUMP hops out.
            val l = leader
            l.x = cart.cx - l.w / 2
            l.y = cart.y - l.h + 6
            l.vx = cart.vx; l.vy = 0f; l.onGround = true
            if (jumpE) { leaveCart(cart, false); l.vy = l.jumpV; sfx(Sound.JUMP) }
        } else updateLeader(leader, dt, jumpE, grav)
        for ((i, f) in followers.withIndex()) updateFollower(f, dt, grav, i)
        val l = leader
        if (actionE && riding == null) {
            val v = things.firstOrNull { it is Villager && abs(it.cx - l.cx) < 22f && abs(it.bottom - l.bottom) < 20f } as Villager?
            val mc = things.firstOrNull { it is Minecart && it.rider == null && abs(it.cx - l.cx) < 24f && abs(it.bottom - l.bottom) < 20f } as Minecart?
            when {
                bed?.overlaps(l) == true && l.onGround -> { sleep(); return }
                v != null -> { startTrade(v); return }
                mc != null -> { rideCart(mc); return }
            }
        }
        if (input.action && l.cooldown <= 0f && riding == null) action(l)
        updateWater(dt)
        updateSpikes(dt)
        updateDog(dt)
        for (h in team) {
            if (h.onGround) h.combo = 0
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
        updatePartnerZap(dt)
        updateFrog()
        updateBoss(dt)
        updateCamera(dt)
        if (state == State.PLAY) checkFalls()
    }

    private fun swap() {
        val old = leader
        old.crouching = false; old.slamming = false; old.longJumping = false; old.sprinting = false
        leaderWho = when (leaderWho) { Who.ROCCO -> Who.BLOCKY; Who.BLOCKY -> Who.MON; Who.MON -> Who.ROCCO }
        val h = leader
        if (hypot(h.cx - old.cx, h.cy - old.cy) > 140f || h.y > level.h * TILE) teleport(h, old)
        h.vx = 0f
        sfx(Sound.KICK)
        particles += FloatText(h.cx, h.y - 6, h.label + "!", h.color)
    }

    private fun teleport(h: Hero, to: Hero) {
        puff(h.cx, h.cy)
        h.x = to.x - to.facing * 4
        h.y = to.bottom - h.h
        h.vx = 0f
        h.vy = 0f
        h.stuck = 0f
        puff(h.cx, h.cy)
    }

    /** Underwater levels: everything left of the shore is water. */
    fun inWater(h: Body) = level.def.shoreX >= 0 && h.cx < level.def.shoreX * TILE

    private fun updateLeader(h: Hero, dt: Float, jumpE: Boolean, grav: Float) {
        val dir = (if (input.right) 1 else 0) - (if (input.left) 1 else 0)
        if (dir != 0) h.facing = dir
        if (inWater(h)) {
            // Swimming: slow sinking, and every tap of JUMP is a swim stroke.
            h.vx = approach(h.vx, dir * h.speed * 0.85f, 500f * dt)
            if (jumpE) { h.vy = -170f; sfx(Sound.SPLASH) }
            if (input.down) h.vy = approach(h.vy, 120f, 400f * dt)
            h.vy = min(h.vy + GRAVITY * 0.3f * dt, 80f)
            moveHero(h, dt)
            if (h.y < 34f) { h.y = 34f; h.vy = max(h.vy, 0f) }
            if (h.onGround) { safeX = h.x; safeY = h.bottom }
            return
        }
        // Double-tap a direction (and keep holding it) to sprint.
        if (dir != 0 && dir != prevDir) {
            h.sprinting = dir == lastTapDir && time - lastTapTime < 0.35f
            lastTapDir = dir
            lastTapTime = time
        }
        if (dir == 0) h.sprinting = false
        prevDir = dir
        val sprint = (h.sprinting || input.run) && dir != 0
        h.sprinting = sprint

        val rocco = h.who == Who.ROCCO
        // Rocco's moves: squat, butt slam and long jump.
        if (rocco) {
            if (h.onGround) h.doubleJumped = false
            if (h.flipT > 0) h.flipT -= dt
            // Double jump: press JUMP again in mid-air.
            if (jumpE && !h.onGround && !h.doubleJumped && !h.slamming) {
                h.doubleJumped = true
                h.longJumping = false
                h.vy = h.jumpV * (if (h.mini) 0.75f else 0.9f)
                h.flipT = 0.35f
                sfx(Sound.JUMP)
                for (i in 0 until 6) particles += Sparkle(h.cx, h.bottom, (i - 2.5f) * 30f, 40f, 0xFFFFFFFF.toInt())
                moveHero(h, dt)
                return
            }
            h.crouching = input.down && h.onGround && !h.slamming
            if (downE && !h.onGround && !h.slamming) {
                h.slamming = true
                h.slamPause = 0.18f
                h.longJumping = false
                h.vx = 0f
                h.vy = 0f
                sfx(Sound.KICK)
            }
            val longJump = h.onGround && !h.slamming && (longE || (jumpE && h.crouching && abs(h.vx) > 40f))
            if (longJump) {
                h.longJumping = true
                h.crouching = false
                h.vx = h.facing * 270f
                h.vy = -250f
                sfx(Sound.JUMP)
                particles += FloatText(h.cx, h.y - 4, "LONG JUMP!", 0xFFFFD21F.toInt())
                moveHero(h, dt)
                return
            }
        } else {
            h.crouching = false
            h.slamming = false
            h.longJumping = false
        }
        if (h.slamming) {
            if (h.slamPause > 0) {
                h.slamPause -= dt
                h.vx = 0f
                h.vy = 0f
            } else h.vy = 560f
            moveHero(h, dt)
            if (h.onGround) slamImpact(h)
            return
        }
        if (h.longJumping) {
            // Keep flying forward until we land.
            h.vy = min(h.vy + GRAVITY * grav * 0.85f * dt, MAX_FALL)
            moveHero(h, dt)
            if (h.onGround) h.longJumping = false
            return
        }
        val top = h.speed * (if (sprint) 1.5f else 1f)
        val want = if (h.crouching) 0f else dir * top
        h.vx = approach(h.vx, want, (if (h.onGround) 800f else 520f) * dt)
        // Blue Shell: sprinting on the ground turns Rocco into a spinning shell.
        h.shellSpin = rocco && power == Power.SHELL && sprint && h.onGround && !h.mini && abs(h.vx) > 120f
        if (jumpE && h.onGround) {
            h.vy = h.jumpV * (if (sprint) 1.06f else 1f)
            sfx(Sound.JUMP)
        }
        if (!input.jump && h.vy < -130f) h.vy = -130f
        // Mini Rocco is light as a feather: he floats way up high.
        val g2 = if (h.mini) grav * 0.5f else grav
        h.vy = min(h.vy + GRAVITY * g2 * dt, if (h.mini) 160f else MAX_FALL)
        if (h.shellSpin) {
            // Smash through bricks in front.
            val tx = tileOf(if (h.facing > 0) h.x + h.w + 2 else h.x - 2)
            for (ty in tileOf(h.y + 2)..tileOf(h.bottom - 2)) if (level[tx, ty] == Tile.BRICK) bump(tx, ty)
        }
        // Floaty Pokemon (Blaze, Mystic, Spooky) glide when you hold JUMP on the way down.
        if (h.who == Who.MON && mon.floaty && input.jump && h.vy > 55f) h.vy = 55f
        moveHero(h, dt)
        if (h.onGround && level.solidAt(h.x + 1, h.bottom + 1) && level.solidAt(h.x + h.w - 1, h.bottom + 1)) {
            safeX = h.x
            safeY = h.bottom
        }
    }

    /** Rocco lands a butt slam: breaks the blocks under him and knocks out bad guys nearby. */
    private fun slamImpact(h: Hero) {
        h.slamming = false
        shake = 0.25f
        sfx(Sound.STOMP)
        val ty = tileOf(h.bottom + 2)
        for (tx in tileOf(h.x + 1)..tileOf(h.x + h.w - 1)) {
            when (level[tx, ty]) {
                Tile.BRICK, Tile.QCOIN, Tile.QHEART, Tile.QSTAR, Tile.QMYSTERY -> bump(tx, ty)
            }
        }
        // A shockwave along the ground.
        val zone = Body(h.cx - 40f, h.bottom - 20f, 80f, 22f)
        for (f in foes) if (f.active && !f.dying && f.overlaps(zone)) f.hit(this, if (f.boss) 2 else 3)
        for (i in 0 until 6) {
            particles += Puff(h.cx + (i - 2.5f) * 10f, h.bottom - 2)
        }
    }

    /** The heroes you're not controlling follow along, hopping over walls and gaps. */
    private fun updateFollower(f: Hero, dt: Float, grav: Float, place: Int) {
        val l = leader
        val targetX = l.cx - l.facing * (22f + place * 20f)
        if (inWater(f)) {
            // Followers just swim after the leader.
            val ty = l.y - place * 6f
            f.vx = approach(f.vx, (targetX - f.cx).coerceIn(-110f, 110f), 400f * dt)
            f.vy = approach(f.vy, (ty - f.y).coerceIn(-90f, 90f), 400f * dt)
            f.facing = if (l.cx > f.cx) 1 else -1
            moveHero(f, dt)
            if (hypot(l.cx - f.cx, l.cy - f.cy) > 200f) teleport(f, l)
            return
        }
        val dx = targetX - f.cx
        val cap = max(f.speed * 1.15f, abs(l.vx) * 1.05f)
        val want = if (abs(dx) > 12f) (if (dx > 0) 1f else -1f) * min(cap, abs(dx) * 4f) else 0f
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
        val px = tx * TILE.toFloat()
        val above = ty * TILE - 14f
        when (level[tx, ty]) {
            Tile.QCOIN -> {
                level[tx, ty] = Tile.USED
                spawn(Coin(px, ty * TILE - 16f, popping = true))
                sfx(Sound.COIN)
                points(100)
            }
            Tile.QHEART -> {
                level[tx, ty] = Tile.USED
                spawn(HeartItem(px, above))
                sfx(Sound.POWERUP)
            }
            Tile.QSTAR -> {
                level[tx, ty] = Tile.USED
                spawn(StarItem(px, above))
                sfx(Sound.POWERUP)
            }
            Tile.QMYSTERY -> {
                level[tx, ty] = Tile.USED
                sfx(Sound.POWERUP)
                // A surprise every time!
                when (Random.nextInt(6)) {
                    0 -> spawn(StarItem(px, above))
                    5 -> spawn(MiniItem(px, above))
                    1 -> spawn(HeartItem(px, above))
                    2 -> repeat(5) { i -> spawn(Coin(px + (i - 2) * 6f, ty * TILE - 16f, popping = true)) }
                    else -> spawn(GiftBox(px, above))
                }
                particles += FloatText(px + 8, above, "SURPRISE!", solidRainbow(time))
            }
            Tile.BRICK -> {
                level[tx, ty] = Tile.EMPTY
                debris(tx, ty, 0xFFB4501C.toInt())
                sfx(Sound.BREAK)
                points(50)
            }
            else -> sfx(Sound.BUMP)
        }
        for (f in foes) {
            if (!f.dying && !f.boss && abs(f.bottom - ty * TILE) < 2 && f.cx > tx * TILE - 4 && f.cx < tx * TILE + TILE + 4) f.hit(this, 99)
        }
    }

    // ======================================================================== Mining, throwing and Pokemon moves

    private fun action(h: Hero) {
        when (h.who) {
            Who.BLOCKY -> swingPick(h)
            Who.ROCCO -> throwPower(h)
            Who.MON -> monAttack(h)
        }
    }

    private fun swingPick(h: Hero) {
        h.swing = 0.22f
        h.cooldown = 0.24f
        h.swordSwing = false
        digDown = input.down
        val sw = sword
        val r = sw.reach
        val reach = Body(if (h.facing > 0) h.x + h.w - 2 else h.x - r + 2, h.y - 4, r, h.h + 4)
        val inReach = foes.filter { !it.dying && it.active && it.overlaps(reach) }
        if (inReach.isNotEmpty()) {
            if (sw == Sword.NONE) {
                for (f in inReach) f.hit(this, pickTier)
            } else {
                // Sword swing: the strong swords hit everyone in reach, the others the closest one.
                h.swordSwing = true
                h.cooldown = sw.cooldown
                val targets = if (sw.sweeps) inReach else listOf(inReach.minBy { abs(it.cx - h.cx) })
                for (f in targets) f.hit(this, sw.damage)
                sfx(Sound.KICK)
                if (sw == Sword.AMETHYST) spawn(SwordBeam(if (h.facing > 0) h.x + h.w else h.x - 12, h.y + 2, h.facing))
            }
            return
        }
        val target = pickTarget(h)
        if (target == null) {
            // Nothing to dig: swing the sword at the air (the amethyst one shoots a slash).
            if (sw != Sword.NONE) {
                h.swordSwing = true
                h.cooldown = sw.cooldown
                if (sw == Sword.AMETHYST) spawn(SwordBeam(if (h.facing > 0) h.x + h.w else h.x - 12, h.y + 2, h.facing))
            }
            sfx(Sound.FIRE)
            return
        }
        mine(target.first, target.second)
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
        if (t == Tile.QCOIN || t == Tile.QHEART || t == Tile.QSTAR || t == Tile.QMYSTERY) { bump(tx, ty); return }
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
            points(20)
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
            val reach = Body(if (h.facing > 0) h.x + h.w - 2 else h.x - 10, h.y + 4, 12f, 12f)
            for (f in foes) if (!f.dying && f.active && f.overlaps(reach)) f.hit(this, 1)
            sfx(Sound.KICK)
            if (toastT <= 0) toast("Mine ore with Blocky, then tap CRAFT!")
            return
        }
        val lvl = flowerLevel[power.ordinal].coerceAtLeast(1)
        val mine = things.count { it is Shot }
        if (mine >= 1 + lvl) return
        // Every level adds 1 damage. Lv3+ throws two, Lv5 throws three, Lv4+ go through bad guys.
        val dmg = power.damage + lvl - 1
        val pierce = if (lvl >= 4) 2 else 0
        val sx = if (h.facing > 0) h.x + h.w else h.x - 9
        if (power == Power.SHELL) {
            if (things.count { it is HomingShell } >= lvl) return
            spawn(HomingShell(sx, h.y + 2, h.facing, dmg))
            if (lvl >= 3) spawn(HomingShell(sx, h.y - 10, h.facing, dmg))
            sfx(Sound.FIRE)
            return
        }
        val spread = if (power == Power.CRYSTAL) 70f else 0f
        spawn(Shot(sx, h.y + 8, h.facing, power, dmg, h, pierce))
        if (power == Power.CRYSTAL) {
            // Crystal always fans out three shards.
            spawn(Shot(sx, h.y + 8, h.facing, power, dmg, h, pierce, -spread))
            spawn(Shot(sx, h.y + 8, h.facing, power, dmg, h, pierce, spread))
        } else {
            if (lvl >= 3) spawn(Shot(sx, h.y - 1, h.facing, power, dmg, h, pierce))
            if (lvl >= 5) spawn(Shot(sx, h.y + 16, h.facing, power, dmg, h, pierce))
        }
        sfx(if (power == Power.THUNDER) Sound.ZAP else Sound.FIRE)
    }

    private fun monAttack(h: Hero) {
        h.cooldown = 0.45f
        h.swing = 0.2f
        val sx = if (h.facing > 0) h.x + h.w else h.x - 8
        if (mon == Mon.PUNCHO) {
            // A strong punch right in front.
            val reach = Body(if (h.facing > 0) h.x + h.w else h.x - 22, h.y, 22f, h.h)
            for (f in foes) if (!f.dying && f.active && f.overlaps(reach)) f.hit(this, 3)
            particles += FloatText(h.cx + h.facing * 16, h.y, "POW", 0xFFFFD21F.toInt())
            sfx(Sound.KICK)
            return
        }
        if (mon == Mon.BLOOM) {
            // Vine whip: a short-range lash that hits harder.
            particles += Vine(h.cx, h.y + 8, h.facing)
            val reach = Body(if (h.facing > 0) h.x + h.w else h.x - 34, h.y - 2, 34f, h.h + 4)
            for (f in foes) if (!f.dying && f.active && f.overlaps(reach)) f.hit(this, 2)
            sfx(Sound.KICK)
            return
        }
        spawn(MonShot(sx, h.y + 4, h.facing, mon))
        sfx(if (mon == Mon.SPARKY) Sound.ZAP else Sound.FIRE)
    }

    /** The Pokemon's huge SUPER move. It takes [SUPER_TIME] seconds to charge again. */
    private fun useSuper() {
        if (superCharge < 1f) {
            sfx(Sound.BUMP)
            toast("${mon.superName} is still charging...")
            return
        }
        superCharge = 0f
        superFlash = 1f
        val m = partner
        // The Pokemon jumps in next to whoever you're playing to do its move.
        if (m !== leader) { teleport(m, leader); m.facing = leader.facing }
        val dir = m.facing
        val mx = m.cx
        val my = m.cy
        sound.sayAs("${mon.label}! ${mon.superName.lowercase()}!", Voice.MON)
        // Water Pokemon refill the team's air too.
        if (mon.swimmer) air = AIR_MAX
        bannerTitle = mon.superName + "!"
        bannerSub = ""
        bannerT = 1.2f
        shake = 0.4f
        fun onScreen(f: Foe) = f.active && !f.dying && f.x + f.w > camX && f.x < camX + viewW
        when (mon) {
            Mon.SPARKY -> {
                particles += SuperFx(mx, my, mon, dir, 0f)
                for (f in foes.filter(::onScreen)) {
                    particles += Bolt(f.cx, -10f, f.cx, f.cy, 0xFFFFF27A.toInt(), 3f)
                    f.hit(this, 6)
                }
                sfx(Sound.ZAP)
            }
            Mon.BLAZE -> {
                val w = 190f
                particles += SuperFx(mx, my, mon, dir, w)
                val zone = Body(if (dir > 0) mx else mx - w, my - 30f, w, 60f)
                for (f in foes.filter { onScreen(it) && it.overlaps(zone) }) f.hit(this, 9)
                sfx(Sound.BOSS_FIRE)
            }
            Mon.HYDRO -> {
                val w = viewW * 0.6f
                particles += SuperFx(mx, my, mon, dir, w)
                val zone = Body(mx - w, my - 26f, w * 2, 52f)
                for (f in foes.filter { onScreen(it) && it.overlaps(zone) }) {
                    f.hit(this, 8)
                    f.vx += if (f.cx > mx) 120f else -120f
                }
                sfx(Sound.BREAK)
            }
            Mon.BLOOM -> {
                val w = viewW
                particles += SuperFx(mx, my, mon, dir, w)
                val zone = Body(if (dir > 0) mx else mx - w, my - 20f, w, 40f)
                for (f in foes.filter { onScreen(it) && it.overlaps(zone) }) f.hit(this, 10)
                sfx(Sound.BOSS_HIT)
            }
            Mon.EMBER -> {
                particles += SuperFx(mx, my, mon, dir, 0f)
                for (f in foes.filter { onScreen(it) && hypot(it.cx - mx, it.cy - my) < 140f }) f.hit(this, 9)
                sfx(Sound.BOSS_FIRE)
            }
            Mon.SPLASHY -> {
                val w = 230f
                particles += SuperFx(mx, my, mon, dir, w)
                val zone = Body(if (dir > 0) mx else mx - w, my - 30f, w, 60f)
                for (f in foes.filter { onScreen(it) && it.overlaps(zone) }) f.hit(this, 8)
                sfx(Sound.SPLASH)
            }
            Mon.MYSTIC -> {
                particles += SuperFx(mx, my, mon, dir, 0f)
                for (f in foes.filter(::onScreen)) f.hit(this, 6)
                hearts = min(MAX_HEARTS, hearts + 2)
                particles += FloatText(mx, my - 20, "+2 HEARTS", 0xFFFF9AD0.toInt())
                sfx(Sound.ONEUP)
            }
            Mon.PUNCHO -> {
                val w = 110f
                particles += SuperFx(mx, my, mon, dir, w)
                val zone = Body(if (dir > 0) mx else mx - w, my - 30f, w, 60f)
                for (f in foes.filter { onScreen(it) && it.overlaps(zone) }) f.hit(this, 16)
                sfx(Sound.BOSS_HIT)
            }
            Mon.SPOOKY -> {
                particles += SuperFx(mx, my, mon, dir, 0f)
                for (f in foes.filter(::onScreen)) f.hit(this, 6)
                sfx(Sound.ZAP)
            }
        }
    }

    // ======================================================================== Crafting

    enum class Kind { FLOWER, PICK, HEART, APPLE, TNT, TRAP, STAR, MINI, HOUSE, BED, SWORD }

    class Recipe(
        val title: String, val note: String, val costs: List<Pair<Ore, Int>>, val coinCost: Int,
        val button: String, val enabled: Boolean, val icon: Power?, val kind: Kind, val sword: Sword = Sword.NONE,
    )

    private fun has(costs: List<Pair<Ore, Int>>, coinCost: Int) =
        costs.all { (o, n) -> inventory[o.ordinal] >= n } && coins >= coinCost

    /** The recipes on one tab of the crafting table. */
    fun recipes(tab: Int = craftTab): List<Recipe> = when (tab) {
        0 -> listOf(Power.BOOM, Power.FIRE, Power.ICE, Power.GRASS, Power.THUNDER, Power.CRYSTAL, Power.SHELL).map { p ->
            val lvl = flowerLevel[p.ordinal]
            val ore = p.ore!!
            val note = when (p) {
                Power.BOOM -> "Iron: weakest. Comes back!"
                Power.FIRE -> "Gold: strong bouncing fire"
                Power.ICE -> "Diamond: the strongest!"
                Power.GRASS -> "Emerald: GREEN ARMOR + leaves"
                Power.THUNDER -> "Copper: zaps jump to more bad guys"
                Power.SHELL -> "Diamond: homing shells + sprint to spin!"
                else -> "Amethyst: three crystal shards"
            }
            val costs = listOf(ore to COST)
            val (button, enabled) = when {
                lvl == 0 -> "MAKE" to has(costs, 0)
                power != p -> "EQUIP" to true
                lvl >= MAX_FLOWER_LV -> "MAX" to false
                else -> "Lv${lvl + 1}" to has(costs, 0)
            }
            val title = if (lvl > 0) "${p.label} Lv$lvl" else p.label
            Recipe(title, note, if (button == "EQUIP" || button == "MAX") emptyList() else costs, 0, button, enabled, p, Kind.FLOWER)
        }
        1 -> {
            val next = pickTier + 1
            val pickOre = when (next) { 2 -> Ore.IRON; 3 -> Ore.GOLD; 4 -> Ore.DIAMOND; else -> Ore.AMETHYST }
            val pick = if (pickTier >= 5) {
                Recipe("AMETHYST PICKAXE", "The best pickaxe there is!", emptyList(), 0, "MAX", false, null, Kind.PICK)
            } else {
                val c = listOf(pickOre to COST)
                Recipe("${TeamArt.pickName(next)} PICKAXE", "Mines faster, hits harder", c, 0, "MAKE", has(c, 0), null, Kind.PICK)
            }
            val heart = listOf(Ore.STONE to 5)
            val apple = listOf(Ore.GOLD to 2)
            val tntCost = listOf(Ore.STONE to 6, Ore.IRON to 2)
            val trapCost = listOf(Ore.IRON to 3)
            val star = listOf(Ore.AMETHYST to 2, Ore.DIAMOND to 1)
            val miniCost = listOf(Ore.EMERALD to 1, Ore.STONE to 3)
            listOf(
                pick,
                Recipe("HEART", "Heals one heart", heart, 0, "MAKE", has(heart, 0) && hearts < MAX_HEARTS, null, Kind.HEART),
                Recipe("GOLDEN APPLE", "All hearts back + 1 armor", apple, 0, "MAKE", has(apple, 0), null, Kind.APPLE),
                Recipe("TNT x2", "Throw it! Blows up a 5x5 area. You have $tnt", tntCost, 0, "MAKE", has(tntCost, 0), null, Kind.TNT),
                Recipe("SPIKE TRAP", "Set it down: bad guys walk into it", trapCost, 0, "MAKE", has(trapCost, 0) && leader.onGround, null, Kind.TRAP),
                Recipe("SUPER STAR", "10 seconds of invincibility!", star, 0, "MAKE", has(star, 0), null, Kind.STAR),
                Recipe("MINI MUSHROOM", "Rocco goes tiny and floats up high", miniCost, 0, "MAKE", has(miniCost, 0), null, Kind.MINI),
            )
        }
        3 -> Sword.entries.filter { it != Sword.NONE }.map { sw ->
            val costs = listOf(sw.ore!! to sw.cost)
            val (button, enabled) = when {
                sword == sw -> "READY" to false
                hasSword(sw) -> "EQUIP" to true
                else -> "MAKE" to has(costs, 0)
            }
            Recipe(sw.label, sw.note, if (hasSword(sw)) emptyList() else costs, 0, button, enabled, null, Kind.SWORD, sw)
        }
        else -> {
            val houseCost = listOf(Ore.STONE to 10, Ore.IRON to 2)
            val bedCost = listOf(Ore.STONE to 4)
            val l = leader
            val canBuild = l.onGround && boss == null && level.solidAt(l.cx - 34, l.bottom + 2) && level.solidAt(l.cx + 34, l.bottom + 2)
            listOf(
                Recipe(
                    if (house == null) "HOUSE" else "MOVE HOUSE HERE", "Built right where you stand",
                    houseCost, 0, "BUILD", has(houseCost, 0) && canBuild, null, Kind.HOUSE,
                ),
                Recipe(
                    "BED", if (house == null) "Build a house first!" else "Sleep in it to SAVE the game",
                    bedCost, 10, if (bed == null) "BUILD" else "DONE", has(bedCost, 10) && house != null && bed == null, null, Kind.BED,
                ),
            )
        }
    }

    /** True when the crafting table has something new to make (the CRAFT button glows). */
    val canCraftSomething get() =
        recipes(0).any { it.enabled && it.button != "EQUIP" } || recipes(1).first().enabled

    fun craft(i: Int, tab: Int = craftTab) {
        val r = recipes(tab).getOrNull(i) ?: return
        if (!r.enabled) {
            sfx(Sound.BUMP)
            toast(
                when {
                    r.button == "MAX" -> "That's already the best!"
                    r.button == "READY" -> "Blocky is already holding that sword!"
                    r.kind == Kind.HEART && hearts >= MAX_HEARTS -> "Hearts are full!"
                    r.kind == Kind.BED && house == null -> "Build a HOUSE first!"
                    r.kind == Kind.BED && bed != null -> "You already have a bed here."
                    r.kind == Kind.HOUSE && has(r.costs, 0) -> "Stand on flat ground (not in a boss fight)."
                    else -> "Need " + (r.costs.map { "${it.second} ${it.first.label}" } +
                        (if (r.coinCost > 0) listOf("${r.coinCost} COINS") else emptyList())).joinToString(" + ") + "!"
                },
            )
            return
        }
        for ((o, n) in r.costs) inventory[o.ordinal] -= n
        coins -= r.coinCost
        val p = r.icon
        var color = r.costs.firstOrNull()?.first?.color ?: 0xFFFFFFFF.toInt()
        when (r.kind) {
            Kind.FLOWER -> if (r.button == "EQUIP") {
                power = p!!
                toast("${p.label} ready!")
            } else {
                flowerLevel[p!!.ordinal] = min(MAX_FLOWER_LV, flowerLevel[p.ordinal] + 1)
                power = p
                if (p == Power.GRASS) armor = min(MAX_ARMOR, flowerLevel[p.ordinal] + 2)
                toast("${p.label} Lv${flowerLevel[p.ordinal]}!")
            }
            Kind.PICK -> { pickTier++; toast("${TeamArt.pickName(pickTier)} PICKAXE!") }
            Kind.HEART -> { hearts++; toast("+1 HEART") }
            Kind.APPLE -> {
                hearts = MAX_HEARTS
                armor = min(MAX_ARMOR, armor + 1)
                toast("Crunch! All hearts back!")
            }
            Kind.TNT -> { tnt += 2; toast("You have $tnt TNT. Press the TNT button to throw!") }
            Kind.TRAP -> {
                val l = leader
                things += SpikeTrap(if (l.facing > 0) l.x + l.w + 4 else l.x - 20, l.bottom)
                toast("Spike trap set!")
            }
            Kind.STAR -> { startStar(); setState(State.PLAY); return }
            Kind.MINI -> { startMini(); setState(State.PLAY); return }
            Kind.SWORD -> {
                swordsOwned = swordsOwned or (1 shl r.sword.ordinal)
                sword = r.sword
                toast(if (r.button == "EQUIP") "Blocky grabs the ${r.sword.label}!" else "${r.sword.label}! Blocky swings it at bad guys.")
                color = r.sword.blade
            }
            Kind.HOUSE -> {
                house?.let { it.removed = true }
                bed?.let { it.removed = true }
                bed = null
                val hx = leader.cx - 36
                house = House(hx, leader.bottom).also { things += it }
                toast("Home sweet home! Now build a BED.")
                color = 0xFFD8603A.toInt()
            }
            Kind.BED -> {
                val h = house!!
                bed = Bed(h.x + 34, h.bottom).also { things += it }
                toast("Stand on the bed and press the action button to sleep and SAVE.")
                color = 0xFFE52521.toInt()
            }
        }
        sfx(Sound.POWERUP)
        for (h in team) repeat(6) {
            particles += Sparkle(h.cx, h.cy, Random.nextFloat() * 120 - 60, -Random.nextFloat() * 90, color)
        }
    }

    // ======================================================================== TNT, Poseidon, water, spikes, Dennis

    private fun throwTnt() {
        if (tnt <= 0 || riding != null) return
        tnt--
        val l = leader
        spawn(ThrownTnt(l.cx - 5, l.y, l.facing))
        sfx(Sound.KICK)
    }

    /** The TNT goes off: every block (except the very bottom) and every bad guy in the grid is hit. */
    fun tntBlast(tx: Int, ty: Int) {
        val r = ThrownTnt.RADIUS
        shake = 0.6f
        sfx(Sound.BOSS_FIRE)
        sfx(Sound.BREAK)
        for (dy in -r..r) for (dx in -r..r) {
            val x = tx + dx
            val y = ty + dy
            if (Random.nextInt(3) == 0) particles += Boom(x * TILE + 8f, y * TILE + 8f, 16f + Random.nextFloat() * 10f)
            if (level.toughness(x, y) > 0) {
                val ore = Tile.ore(level[x, y])
                level[x, y] = Tile.EMPTY
                debris(x, y, ore?.dark ?: 0xFF8A5A2A.toInt(), 2)
                if (ore != null) inventory[ore.ordinal]++
            }
        }
        val zone = Body((tx - r) * TILE.toFloat(), (ty - r) * TILE.toFloat(), (2 * r + 1) * TILE.toFloat(), (2 * r + 1) * TILE.toFloat())
        for (f in foes) if (!f.dying && f.active && f.overlaps(zone)) f.hit(this, 8)
    }

    /** A bomb-bomb goes off: bad guys nearby get hit, bricks and stone crumble, and the team gets hurt if they're too close. */
    fun bombBlast(x: Float, y: Float, hurtsTeam: Boolean) {
        particles += Boom(x, y, 34f)
        shake = 0.4f
        sfx(Sound.BOSS_FIRE)
        val tx0 = tileOf(x)
        val ty0 = tileOf(y)
        for (ty in ty0 - 1..ty0 + 1) for (tx in tx0 - 1..tx0 + 1) {
            val t = level[tx, ty]
            if (t == Tile.BRICK || (level.toughness(tx, ty) > 0 && t != Tile.GROUND)) {
                val ore = Tile.ore(t)
                level[tx, ty] = Tile.EMPTY
                debris(tx, ty, ore?.dark ?: 0xFFB4501C.toInt(), 3)
                if (ore != null) inventory[ore.ordinal]++
            }
        }
        for (f in foes) if (f.active && !f.dying && f !is BombBomb && hypot(f.cx - x, f.cy - y) < 44f) f.hit(this, 6)
        // Chain reaction!
        for (f in foes) if (f is BombBomb && !f.dying && !f.removed && hypot(f.cx - x, f.cy - y) < 44f) f.hit(this, 1)
        if (hurtsTeam && hypot(leader.cx - x, leader.cy - y) < 32f) hurt()
    }

    private fun callPoseidon() {
        if (coins < POSEIDON_COST) {
            toast("Poseidon needs $POSEIDON_COST coins. You have $coins.")
            return
        }
        coins -= POSEIDON_COST
        floodT = 3f
        shake = 0.8f
        sfx(Sound.SPLASH)
        sound.sayAs("I am Poseidon! Waves, wash them away!", Voice.GOD)
        bannerTitle = "POSEIDON!"
        bannerSub = "The sea washes the bad guys away!"
        bannerT = 2.5f
        for (f in foes) if (f.active && !f.dying && f.x + f.w > camX && f.x < camX + viewW) f.hit(this, if (f.boss) 6 else 99)
    }

    private fun updateWater(dt: Float) {
        if (level.def.shoreX < 0) return
        val l = leader
        if (inWater(l)) {
            air -= dt * (if (mon.swimmer) 0.5f else 1f)
            if (air <= 0f) {
                air = 10f
                if (starT <= 0) hearts--
                sfx(Sound.HURT)
                toast("Out of air! Find the BUBBLES!")
                if (hearts <= 0) { knockedOut(); return }
            }
        } else air = AIR_MAX
        // Bubble vents puff out a bubble every so often.
        val first = (camX / TILE).toInt() - 1
        val last = ((camX + viewW) / TILE).toInt() + 1
        for (ty in 0 until ROWS) for (tx in first..last) {
            if (level[tx, ty] != Tile.VENT) continue
            val k = ty * level.w + tx
            val t = (ventT[k] ?: Random.nextFloat()) - dt
            if (t <= 0) {
                spawn(Bubble(tx * TILE + 3f, ty * TILE - 10f))
                ventT[k] = 1.6f
            } else ventT[k] = t
        }
    }

    private fun updateSpikes(dt: Float) {
        if (spikeT > 0) spikeT -= dt
        val l = leader
        val ty = tileOf(l.bottom - 2)
        if (level[tileOf(l.x + 2), ty] == Tile.SPIKES || level[tileOf(l.x + l.w - 2), ty] == Tile.SPIKES) {
            hurt()
            l.vy = -260f
        }
        // Spike traps hurt bad guys too.
        if (spikeT <= 0) {
            spikeT = 0.5f
            for (f in foes) {
                if (f.dying || f.boss || !f.active) continue
                val fy = tileOf(f.bottom - 2)
                if (level[tileOf(f.cx), fy] == Tile.SPIKES) f.hit(this, 1)
            }
        }
    }

    private fun updateDog(dt: Float) {
        if (boss != null || level.def.shoreX >= 0 || things.any { it is Dennis }) return
        dogT -= dt
        if (dogT > 0) return
        dogT = 40f + Random.nextFloat() * 15f
        val l = leader
        if (!l.onGround) { dogT = 2f; return }
        spawn(Dennis(camX - 30f, l.bottom))
    }

    private fun rideCart(c: Minecart) {
        c.rider = leader
        riding = c
        c.vx = 60f
        sfx(Sound.POWERUP)
        toast("Wheee! JUMP to hop out.")
    }

    fun leaveCart(c: Minecart, end: Boolean) {
        c.rider = null
        c.vx = 0f
        if (riding === c) {
            riding = null
            val l = leader
            l.y = c.y - l.h - 2
            l.vy = if (end) -220f else l.vy
        }
    }

    // ======================================================================== Villagers

    private class Trade(
        val title: String, val costs: List<Pair<Ore, Int>>, val coinCost: Int,
        val thanks: String, val give: TeamGame.() -> Unit,
    )

    private val allTrades: List<Trade> = listOf(
        Trade("2 IRON", emptyList(), 5, "Here is some iron. Thank you!") { inventory[Ore.IRON.ordinal] += 2 },
        Trade("1 DIAMOND", emptyList(), 8, "A shiny diamond, just for you!") { inventory[Ore.DIAMOND.ordinal] += 1 },
        Trade("1 EMERALD", listOf(Ore.STONE to 6), 0, "Thank you for the stone. Here is an emerald!") { inventory[Ore.EMERALD.ordinal] += 1 },
        Trade("2 TNT", listOf(Ore.EMERALD to 2), 0, "Two TNT! Be careful with those!") { tnt += 2 },
        Trade("2 GOLD", listOf(Ore.IRON to 3), 0, "Gold for iron. A good trade!") { inventory[Ore.GOLD.ordinal] += 2 },
        Trade("2 AMETHYST", emptyList(), 10, "Purple amethyst. So pretty!") { inventory[Ore.AMETHYST.ordinal] += 2 },
        Trade("GOLDEN APPLE", listOf(Ore.EMERALD to 1), 4, "A golden apple. Eat it to feel great!") {
            hearts = MAX_HEARTS; armor = min(MAX_ARMOR, armor + 1)
        },
        Trade("3 COPPER", listOf(Ore.STONE to 4), 0, "Copper for your thunder flower!") { inventory[Ore.COPPER.ordinal] += 3 },
        Trade("SUPER STAR", listOf(Ore.EMERALD to 2), 5, "A super star! Now go and win!") { startStar() },
    )

    private val greetings = listOf(
        "Hello, friend! Do you want to trade?",
        "Welcome to our village! Take a look at my trades.",
        "Hi there! I have some good stuff for you.",
        "Oh, hello! Are you here to stop King Krag?",
    )

    private fun tradesOf(v: Villager) = (0 until 4).map { allTrades[(v.seed + it * 3) % allTrades.size] }.distinctBy { it.title }

    private fun startTrade(v: Villager) {
        trader = v
        setState(State.TRADE)
        sound.sayAs(greetings[v.seed % greetings.size], Voice.VILLAGER)
        sfx(Sound.COIN)
    }

    private fun updateTrade(tapped: Boolean, close: Boolean, num: Int) {
        val v = trader ?: run { setState(State.PLAY); return }
        val trades = tradesOf(v)
        if (num in 1..trades.size) doTrade(trades[num - 1])
        if (close) { endTrade(); return }
        if (!tapped || stateT < 0.2f) return
        val px = panelX()
        val pw = panelW()
        val x = input.tapX
        val y = input.tapY
        if (hypot(x - (px + pw - 14), y - (panelTop + 10)) < 14f || x < px || x > px + pw || y < panelTop) { endTrade(); return }
        val i = ((y - rowsTop) / rowH).toInt()
        if (y >= rowsTop && i in trades.indices) doTrade(trades[i])
    }

    /** For tests and keyboards: trade with whoever we're talking to. */
    internal fun tradeForTest(i: Int) = trader?.let { doTrade(tradesOf(it)[i]) }

    private fun doTrade(t: Trade) {
        if (!has(t.costs, t.coinCost)) {
            sfx(Sound.BUMP)
            sound.sayAs("Hmm. You don't have enough for that one.", Voice.VILLAGER)
            toast("Need " + (t.costs.map { "${it.second} ${it.first.label}" } +
                (if (t.coinCost > 0) listOf("${t.coinCost} COINS") else emptyList())).joinToString(" + ") + "!")
            return
        }
        for ((o, n) in t.costs) inventory[o.ordinal] -= n
        coins -= t.coinCost
        t.give(this)
        sfx(Sound.POWERUP)
        sound.sayAs(t.thanks, Voice.VILLAGER)
        toast("Got ${t.title}!")
    }

    private fun endTrade() {
        sound.sayAs("Goodbye! Come back any time!", Voice.VILLAGER)
        trader = null
        setState(State.PLAY)
    }

    /** Mini Mushroom: Rocco shrinks for 25 seconds. */
    fun startMini() {
        if (!rocco.mini) setMini(true)
        miniT = 25f
        sfx(Sound.POWERUP)
        toast("MINI ROCCO! Tiny and floaty!")
    }

    private fun endMini() {
        miniT = 0f
        if (rocco.mini) setMini(false)
        sfx(Sound.POWERUP)
    }

    /** Resizes Rocco, keeping his feet and middle in the same place. */
    private fun setMini(on: Boolean) {
        val r = rocco
        val cx = r.cx
        val b = r.bottom
        r.mini = on
        r.w = if (on) 7f else 12f
        r.h = if (on) 8f else 23f
        r.x = cx - r.w / 2
        r.y = b - r.h
        puff(cx, b - 6)
    }

    fun startStar() {
        starT = STAR_TIME
        sfx(Sound.ONEUP)
        toast("SUPER STAR! You can't be hurt!")
    }

    fun healHeart() {
        if (hearts < MAX_HEARTS) hearts++ else points(1000)
        particles += FloatText(leader.cx, leader.y, "+1 HEART", 0xFFFF8080.toInt())
        sfx(Sound.ONEUP)
    }

    /** A gift box holds a handful of random ore. */
    fun openGift(x: Float, y: Float) {
        val pool = Ore.entries.filter { it != Ore.STONE }
        repeat(2) {
            val o = pool.random()
            inventory[o.ordinal]++
            particles += FloatText(x, y - it * 9f, "+1 ${o.label}", o.color)
        }
        inventory[Ore.STONE.ordinal] += 3
        sfx(Sound.POWERUP)
    }

    private fun panelX() = viewW / 2 - panelW() / 2
    private fun panelW() = min(viewW - 24f, 410f)
    private val panelTop = 32f
    private val tabTop = 52f
    private val rowsTop = 72f
    private val rowH = 27f
    /** Rows squeeze together a little when a tab has more than six recipes. */
    private fun craftRowH() = min(rowH, (VIEW_H - 4f - rowsTop) / recipes().size)

    private fun updateCraft(tapped: Boolean, close: Boolean, num: Int) {
        when (num) {
            in 1..6 -> craft(num - 1)
            7 -> craftTab = (craftTab + TABS.size - 1) % TABS.size
            8, 9 -> craftTab = (craftTab + 1) % TABS.size
            10 -> craft(6)
        }
        if (close) { setState(State.PLAY); return }
        if (!tapped || stateT < 0.15f || state != State.CRAFT) return
        val x = input.tapX
        val y = input.tapY
        val px = panelX()
        val pw = panelW()
        val closeX = px + pw - 14
        if (hypot(x - closeX, y - (panelTop + 10)) < 14f || x < px || x > px + pw || y < panelTop) {
            setState(State.PLAY)
            return
        }
        if (y in tabTop..(tabTop + 18f)) {
            craftTab = ((x - px) / (pw / TABS.size)).toInt().coerceIn(0, TABS.lastIndex)
            sfx(Sound.KICK)
            return
        }
        val i = ((y - rowsTop) / craftRowH()).toInt()
        if (y >= rowsTop && i in recipes().indices) craft(i)
    }

    // ======================================================================== Interactions

    private fun interact() {
        for (f in foes) {
            if (!f.active || f.dying || f.removed) continue
            for (h in team) {
                if (!f.overlaps(h)) continue
                val falling = h.vy > 0 && h.bottom - f.y < 10f
                if (falling && f.stompable) {
                    f.stomp(this, h)
                    stompCombo(h, f)
                    // Hydro is heavy: his stomps hurt bosses more.
                    if (h.who == Who.MON && mon == Mon.HYDRO && f.boss) f.hit(this, 2)
                    // A butt slam lands extra hard.
                    if (h.slamming && f.boss) f.hit(this, 3)
                    h.vy = if (h === leader && input.jump) -340f else -240f
                    break
                }
                if (h === leader) {
                    when {
                        starT > 0 -> f.hit(this, 3)
                        h.shellSpin -> { f.hit(this, 3 + flowerLevel[Power.SHELL.ordinal]); h.vx = -h.vx * 0.3f }
                        else -> f.touch(this)
                    }
                }
            }
        }
        for (t in things) {
            if (t.removed) continue
            val hit = team.any { t.overlaps(it) }
            when (t) {
                is Coin -> if (hit) {
                    t.removed = true
                    collectCoin()
                }
                is Bubble -> if (hit) {
                    t.removed = true
                    air = min(AIR_MAX, air + 10f)
                    particles += FloatText(t.cx, t.y, "+AIR", 0xFFBFE8FF.toInt())
                    sfx(Sound.COIN)
                }
                is Pickup -> if (hit) {
                    t.removed = true
                    t.collect(this)
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
                    heartBonus()
                    points(2000)
                    sfx(Sound.CLEAR)
                }
            }
        }
    }

    private var lastBonus = 0

    /** Finishing a level: 1000 points (times the multiplier) for every heart left. */
    private fun heartBonus() {
        lastBonus = hearts * 1000 * SCORE_MULT
        score += lastBonus
    }

    private fun collectCoin() {
        coins++
        points(50)
        sfx(Sound.COIN)
        if (coins == POSEIDON_COST) toast("50 COINS! Tap the trident to call POSEIDON!")
    }

    fun hurt() {
        if (invuln > 0 || starT > 0 || state != State.PLAY) return
        if (leader === rocco && rocco.mini) {
            // Mini Rocco just pops back to normal size instead of losing a heart.
            endMini()
            invuln = 1.6f
            sfx(Sound.HURT)
            return
        }
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

    /** For tests: the SUPER move is ready right now. */
    internal fun chargeSuper() { superCharge = 1f }

    internal fun slotCardXForTest(i: Int) = slotCardX(i)
    internal fun pickCardXForTest(i: Int) = pickCardX(i)
    internal fun pickCardYForTest(i: Int) = pickCardY(i)
    internal fun thingsForTest(): List<Thing> = things + pending
    internal fun dogForTest() { dogT = 0f }
    internal fun pickStickerScreenForTest() = offerStickers(levelIndex + 1)
    internal fun giveStickerForTest(i: Int) { stickerBits = stickerBits or (1L shl i) }
    internal fun openBookForTest() = setState(State.BOOK)
    internal fun bedForTest() = bed

    private fun knockedOut() {
        hearts = 0
        setState(State.OUCH)
        sfx(Sound.DIE)
    }

    private fun sleep() {
        setState(State.SLEEP)
        sfx(Sound.CLEAR)
        sound.say("Good night!")
    }

    private fun checkFalls() {
        val h = leader
        if (h.y > level.h * TILE + 8 || level.inLava(h)) {
            if (starT <= 0) hearts--
            sfx(Sound.HURT)
            if (hearts <= 0) { knockedOut(); return }
            toast(if (level.inLava(h)) "Hot hot hot! Watch out for lava!" else "Whoops! Mind the gap!")
            placeTeam(safeX - leader.facing * 8f, safeY)
            invuln = 1.6f
        }
    }

    // ======================================================================== Partner, frog and bosses

    /** While following, the Pokemon zaps a nearby bad guy every few seconds. */
    private fun updatePartnerZap(dt: Float) {
        if (partner === leader) return
        followZap -= dt
        if (followZap > 0) return
        val sx = partner.cx
        val sy = partner.cy
        val target = foes.filter {
            it.active && !it.dying && it.x + it.w > camX && it.x < camX + viewW &&
                (it !is KragJr || it.engaged) && (it !is KingKrag || it.awake) &&
                (it !is Warden || it.awake) && (it !is EnderDragon || it.engaged)
        }.minByOrNull { hypot(it.cx - sx, it.cy - sy) }
        if (target == null || hypot(target.cx - sx, target.cy - sy) > 180f) {
            followZap = 0.4f
            return
        }
        val color = mon.color
        particles += Bolt(sx, sy, target.cx, target.cy, color)
        target.hit(this, 1)
        sfx(Sound.ZAP)
        partner.swing = 0.4f
        followZap = 5f
        if (Random.nextInt(3) == 0) say(sx, partner.y - 12, "${mon.label}!", 1.1f)
    }

    private fun updateFrog() {
        val f = frog
        if (f != null && f.removed) frog = null
        val spot = frogSpots.firstOrNull { leader.cx > it * TILE } ?: return
        frogSpots.remove(spot)
        val fr = Frog(camX - 30f, leader.bottom - 26f)
        frog = fr
        spawn(fr)
        sound.say("Ring ding ding ding ding ding! Bing bong a dong!")
    }

    fun onBossEngaged(f: Foe) {
        if (boss != null) return
        boss = f
        val home = when (f) {
            is KragJr -> f.homeX
            is KingKrag -> f.homeX
            is Warden -> f.homeX
            is EnderDragon -> f.homeX + f.w / 2 - 16f
            else -> f.x
        }
        camLock = (home + 16f - viewW / 2).coerceIn(0f, max(0f, level.w * TILE - viewW))
        bannerT = 3f
        when {
            f is KragJr && f.wonderStyle -> {
                wonder = true
                bannerTitle = "WONDER KRAG JR.!"
                bannerSub = "Purple and green and twice as tough! The sky goes Wonder!"
                sfx(Sound.POWERUP)
            }
            f is KragJr -> {
                bannerTitle = "KRAG JR.!"
                bannerSub = "Stomp his clown car or hit him with your flowers!"
                sfx(Sound.KICK)
            }
            f is KingKrag && f.dry -> {
                bannerTitle = "DRY BONES KING KRAG!"
                bannerSub = "Knock him into a pile of bones... and then do it again!"
                sfx(Sound.BREAK)
            }
            f is Warden -> {
                bannerTitle = "THE WARDEN!"
                bannerSub = "Walk slowly, he follows noise! Dodge his sonic booms!"
                sfx(Sound.BOSS_FIRE)
            }
            f is EnderDragon -> {
                bannerTitle = "THE ENDER DRAGON!"
                bannerSub = "Break the crystals, then hit it hard when it lands!"
                sfx(Sound.BOSS_FIRE)
            }
            f is KingKrag && f.wonderStyle -> {
                wonder = true
                bannerTitle = "WONDER KING KRAG!"
                bannerSub = "He's HUGE! Rainbow fire and spike ball rain!"
                sfx(Sound.BOSS_FIRE)
            }
            else -> {
                bannerTitle = "KING KRAG!"
                bannerSub = "Jump over the fire, hit him with everything!"
                sfx(Sound.BOSS_FIRE)
            }
        }
    }

    fun onBossBeaten(f: Foe) {
        bossDone = true
        bossDoneT = 0f
        shake = 0.5f
        sfx(Sound.VICTORY)
        say(leader.cx, leader.y - 10, if (f is KragJr) "Teamwork!" else "We did it!!", 2f)
        // Defeated bosses leave a pile of treasure behind, and a shower of coins.
        repeat(15) { i -> spawn(Coin(f.cx - 40 + i * 6f, f.y, popping = true)) }
        inventory[Ore.DIAMOND.ordinal] += 2
        inventory[Ore.GOLD.ordinal] += 3
        tnt += 1
    }

    private fun updateBoss(dt: Float) {
        if (wonder && !bossDone && Random.nextFloat() < dt * 10) {
            particles += Sparkle(camX + Random.nextFloat() * viewW, -4f, Random.nextFloat() * 20 - 10, 70f, solidRainbow(time, Random.nextFloat()))
        }
        if (!bossDone) return
        bossDoneT += dt
        if (bossDoneT > 2f) {
            wonder = false
            camLock = null
            val b = boss
            // Boss levels without a flag end when the boss is beaten.
            if (goal == null && state == State.PLAY && bossDoneT > 3f) {
                if (levelIndex == Levels.all.lastIndex) {
                    offerStickers(-1)
                } else {
                    heartBonus()
                    clearNote = when {
                        b is KingKrag && b.dry -> "Only one enemy left... THE ENDER DRAGON!"
                        b is KingKrag && b.wonderStyle -> "...but something stirs in the DEEP DARK!"
                        b is KingKrag -> "...but King Krag found a WONDER FLOWER!"
                        b is Warden -> "The Warden is beaten! On to new lands!"
                        else -> "Great job, team!"
                    }
                    setState(State.CLEAR)
                    sfx(Sound.CLEAR)
                }
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
            state == State.TITLE || state == State.SLOTS || state == State.PICK -> Music.OVERWORLD
            state == State.VICTORY -> Music.FROG
            state == State.STICKER -> Music.STAR
            state == State.OUCH || state == State.CLEAR || state == State.SLEEP -> Music.NONE
            starT > 0 -> Music.STAR
            // King Krag gets his own crazy music, and the Warden his spooky one.
            boss is KingKrag && !bossDone -> Music.KRAG
            boss is Warden && !bossDone -> Music.WARDEN
            frog?.singing == true -> Music.FROG
            wonder -> Music.WONDER
            boss != null && !bossDone -> Music.BOSS
            level.def.place == Place.DEEP -> Music.WARDEN
            else -> level.def.place.music
        }
        sound.music(m)
    }

    // ======================================================================== Rendering

    private val white = 0xFFFFFFFF.toInt()

    fun render(gfx: Gfx) {
        when (state) {
            State.TITLE -> renderTitle(gfx)
            State.SLOTS -> renderSlots(gfx)
            State.PICK -> renderPick(gfx)
            State.INTRO -> renderIntro(gfx)
            State.STICKER -> renderStickers(gfx)
            State.BOOK -> renderBook(gfx)
            else -> {
                renderWorld(gfx)
                renderHud(gfx)
                when (state) {
                    State.PLAY -> {
                        val l = leader
                        val label = when {
                            riding != null -> "RIDING"
                            bed?.overlaps(l) == true -> "SLEEP"
                            things.any { it is Villager && abs(it.cx - l.cx) < 22f && abs(it.bottom - l.bottom) < 20f } -> "TALK"
                            things.any { it is Minecart && abs(it.cx - l.cx) < 24f && abs(it.bottom - l.bottom) < 20f } -> "RIDE"
                            l.who == Who.MON && mon == Mon.PUNCHO -> "PUNCH"
                            l.who == Who.BLOCKY -> "MINE"
                            l.who == Who.MON -> if (mon == Mon.BLOOM) "VINE" else "ATTACK"
                            power == Power.NONE -> "PUNCH"
                            else -> "THROW"
                        }
                        val next = team[(team.indexOf(l) + 1) % team.size]
                        TeamControls.draw(
                            gfx, input, viewW, label, next.label, next.color, superCharge, mon.color, time,
                            tnt, coins >= POSEIDON_COST, l.who == Who.ROCCO && riding == null, l.sprinting,
                        )
                        TeamControls.drawTop(gfx, viewW, musicOn, canCraftSomething, time)
                    }
                    State.CRAFT -> renderCraft(gfx)
                    State.TRADE -> renderTrade(gfx)
                    State.SLEEP -> {
                        val a = (minOf(1f, stateT / 0.6f, (2.6f - stateT) / 0.6f) * 230).toInt().coerceIn(0, 230)
                        gfx.rect(0f, 0f, viewW, VIEW_H, argb(a, 0x0A0A2A))
                        gfx.oval(viewW - 80, 30f, 30f, 30f, argb(a, 0xFFF2C8))
                        gfx.oval(viewW - 72, 26f, 30f, 30f, argb(a, 0x0A0A2A))
                        for (i in 0 until 3) {
                            val zt = (stateT * 0.8f + i * 0.33f) % 1f
                            gfx.text("Z", viewW / 2 + i * 14f + zt * 10, 120f - zt * 30, 10f + i * 4, argb((a * (1 - zt)).toInt(), 0xFFFFFF), 1)
                        }
                        gfx.shadowText("Sleeping... saving your game", viewW / 2, 160f, 10f, argb(a, 0xFFFFFF), 1)
                    }
                    State.OUCH -> {
                        gfx.rect(0f, 0f, viewW, VIEW_H, 0x88000000.toInt())
                        gfx.shadowText("OH NO!", viewW / 2, 106f, 22f, 0xFFFF8080.toInt(), 1)
                        gfx.shadowText("Back to the checkpoint. You keep your ore!", viewW / 2, 128f, 9f, white, 1)
                    }
                    State.CLEAR -> {
                        gfx.shadowText("LEVEL CLEAR!", viewW / 2, 96f, 22f, 0xFFFFD21F.toInt(), 1)
                        gfx.shadowText("SCORE $score", viewW / 2, 118f, 10f, white, 1)
                        if (lastBonus > 0) gfx.shadowText("HEART BONUS +$lastBonus", viewW / 2, 154f, 9f, 0xFFFF9AB0.toInt(), 1)
                        if (clearNote.isNotEmpty()) gfx.shadowText(clearNote, viewW / 2, 138f, 10f, solidRainbow(time), 1)
                    }
                    State.VICTORY -> renderVictory(gfx)
                    else -> {}
                }
                if (bannerT > 0 && state == State.PLAY) {
                    gfx.shadowText(bannerTitle, viewW / 2, 74f, 16f, if (wonder) solidRainbow(time) else 0xFFFF6A3A.toInt(), 1)
                    if (bannerSub.isNotEmpty()) gfx.shadowText(bannerSub, viewW / 2, 90f, 8f, white, 1)
                }
                if (toastT > 0 && (state == State.PLAY || state == State.CRAFT || state == State.TRADE)) {
                    val w = toast.length * 5.4f + 16
                    val ty = if (state != State.PLAY) VIEW_H - 18 else 46f
                    gfx.rect(viewW / 2 - w / 2, ty, w, 16f, 0xCC000000.toInt())
                    gfx.text(toast, viewW / 2, ty + 12, 9f, white, 1)
                }
                if (paused && state == State.PLAY) {
                    gfx.rect(0f, 0f, viewW, VIEW_H, 0x99000000.toInt())
                    gfx.shadowText("PAUSED", viewW / 2, 110f, 20f, white, 1)
                    gfx.shadowText("Tap the pause button to keep playing", viewW / 2, 132f, 9f, 0xFFDDDDDD.toInt(), 1)
                    gfx.rect(viewW / 2 - 70, 150f, 140f, 28f, 0xFF7A4A20.toInt())
                    gfx.rect(viewW / 2 - 68, 152f, 136f, 24f, 0xFFB98A52.toInt())
                    gfx.text("STICKER BOOK  $stickerCount/${Stickers.all.size}", viewW / 2, 168f, 9f, white, 1)
                    gfx.rect(viewW / 2 - 70, 184f, 140f, 28f, 0xFF1E5A1E.toInt())
                    gfx.rect(viewW / 2 - 68, 186f, 136f, 24f, if (savedFlash > 0) 0xFF3CE070.toInt() else 0xFF2EB84A.toInt())
                    gfx.text(if (savedFlash > 0) "SAVED! (FILE ${slot + 1})" else "SAVE GAME", viewW / 2, 202f, 9f, white, 1)
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
        for (t in things) if (t is House) t.draw(this, gfx, ox)
        for (t in things) if (t !is Shot && t !is MonShot && t !is Frog && t !is House) t.draw(this, gfx, ox)
        for (f in foes) if (f.x + f.w > cam - 40 && f.x < cam + viewW + 40) {
            f.draw(this, gfx, ox)
            f.drawHealth(gfx, ox)
        }
        for (h in followers) h.draw(this, gfx, ox)
        leader.draw(this, gfx, ox)
        for (t in things) if (t is Shot || t is MonShot || t is Frog) t.draw(this, gfx, ox)
        for (p in particles) p.draw(gfx, ox)
        // Underwater tint up to the shore, with a wavy surface line.
        if (level.def.shoreX >= 0) {
            val sx0 = level.def.shoreX * TILE + ox
            if (sx0 > 0) {
                val wEnd = min(viewW, sx0)
                gfx.rect(0f, 32f, wEnd, VIEW_H - 32f, 0x442E8AC8)
                var wx = 0f
                while (wx < wEnd) {
                    gfx.rect(wx, 32f + sin(time * 3 + (wx - ox) * 0.08f) * 2f, 8f, 2f, 0xAABFE8FF.toInt())
                    wx += 8f
                }
            }
        }
        if (floodT > 0) {
            // Poseidon's flood: the water rises over everything, then drains away.
            val p = 3f - floodT
            val rise = if (p < 1f) p else if (p < 2f) 1f else (3f - p)
            val top = VIEW_H - rise * (VIEW_H - 40f)
            gfx.rect(0f, top, viewW, VIEW_H - top, 0xAA2E7AC8.toInt())
            var wx = 0f
            while (wx < viewW) {
                gfx.oval(wx - 6, top - 6 + sin(time * 8 + wx * 0.1f) * 4f, 20f, 12f, 0xCCBFE8FF.toInt())
                wx += 16f
            }
            TeamArt.poseidon(gfx, viewW / 2, top + 150f, time)
        }
        val l = leader
        if (state == State.PLAY) {
            val ay = l.y - 10 + sin(time * 6) * 1.5f
            gfx.poly(floatArrayOf(l.cx + ox - 4, l.cx + ox + 4, l.cx + ox), floatArrayOf(ay, ay, ay + 5), white)
        }
    }

    private fun renderBackground(gfx: Gfx, cam: Float) {
        if (wonder || state == State.VICTORY) {
            val bands = 7
            for (i in 0 until bands) {
                gfx.rect(0f, i * VIEW_H / bands, viewW, VIEW_H / bands + 1, solidRainbow(time * 0.2f, i / bands.toFloat()))
            }
            // Wonder bosses tint the sky purple and green.
            val b = boss
            val wonderBoss = (b is KragJr && b.wonderStyle) || (b is KingKrag && b.wonderStyle)
            gfx.rect(0f, 0f, viewW, VIEW_H, if (wonderBoss) argb(90, if ((time * 2).toInt() % 2 == 0) 0x8A3AD8 else 0x2EB84A) else 0x66FFFFFF)
            return
        }
        when (level.def.place) {
            Place.HILLS, Place.JUNGLE -> {
                val jungle = level.def.place == Place.JUNGLE
                gfx.rect(0f, 0f, viewW, VIEW_H, if (jungle) 0xFF8FD8A0.toInt() else 0xFF6FB6FF.toInt())
                layer(cam, 0.2f, 260f) { i, x ->
                    gfx.oval(x, 150f - (i % 3) * 18f, 220f, 160f, if (jungle) 0xFF3E8A4A.toInt() else 0xFF7FCB6A.toInt())
                }
                layer(cam, 0.35f, 180f) { i, x ->
                    if (jungle) {
                        // Giant mushrooms
                        gfx.rect(x + 40, 150f, 12f, 60f, 0xFFF0E0C0.toInt())
                        gfx.oval(x + 16, 128f - (i % 2) * 12, 60f, 34f, if (i % 2 == 0) 0xFFE53A3A.toInt() else 0xFF8A3AD8.toInt())
                        gfx.oval(x + 30, 136f - (i % 2) * 12, 8f, 6f, white)
                    } else {
                        gfx.rect(x + 40, 170f, 10f, 40f, 0xFF6B4423.toInt())
                        gfx.rect(x + 25, 140f - (i % 2) * 10, 40f, 34f, 0xFF3E9A3A.toInt())
                    }
                }
                layer(cam, 0.1f, 200f) { i, x -> cloud(gfx, x + 30, 30f + (i % 3) * 22f) }
            }
            Place.DESERT -> {
                gfx.rect(0f, 0f, viewW, VIEW_H, 0xFFFFB870.toInt())
                gfx.oval(viewW * 0.7f, 30f, 40f, 40f, 0xFFFFF0A0.toInt())
                layer(cam, 0.2f, 240f) { i, x ->
                    gfx.poly(floatArrayOf(x, x + 70, x + 140), floatArrayOf(200f, 120f - (i % 2) * 20, 200f), 0xFFD88A4A.toInt())
                }
                layer(cam, 0.4f, 170f) { _, x ->
                    gfx.rect(x + 60, 160f, 8f, 50f, 0xFF3E9A3A.toInt())
                    gfx.rect(x + 52, 172f, 8f, 4f, 0xFF3E9A3A.toInt())
                    gfx.rect(x + 50, 160f, 4f, 16f, 0xFF3E9A3A.toInt())
                }
            }
            Place.SNOW -> {
                gfx.rect(0f, 0f, viewW, VIEW_H, 0xFFB8D8F8.toInt())
                layer(cam, 0.2f, 220f) { i, x ->
                    gfx.poly(floatArrayOf(x, x + 90, x + 180), floatArrayOf(210f, 90f - (i % 3) * 15, 210f), 0xFF8AA8C8.toInt())
                    gfx.poly(floatArrayOf(x + 70, x + 90, x + 110), floatArrayOf(110f - (i % 3) * 15, 90f - (i % 3) * 15, 110f - (i % 3) * 15), white)
                }
                for (i in 0 until 30) {
                    val fx = (i * 73f + time * 15f - cam * 0.5f) % viewW
                    val fy = (i * 37f + time * 30f) % VIEW_H
                    gfx.oval((fx + viewW) % viewW, fy, 2f, 2f, white)
                }
            }
            Place.CAVE -> {
                gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF1E1A2E.toInt())
                layer(cam, 0.3f, 90f) { i, x ->
                    gfx.poly(floatArrayOf(x, x + 18, x + 9), floatArrayOf(32f, 32f, 60f + (i % 4) * 14f), 0xFF2E2840.toInt())
                    if (i % 3 == 0) gfx.oval(x + 40, 120f + (i % 5) * 12f, 3f, 3f, Ore.entries[i % Ore.entries.size].color)
                }
            }
            Place.SKY -> {
                gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF8ED0FF.toInt())
                gfx.rect(0f, 160f, viewW, 80f, 0xFFB8E4FF.toInt())
                layer(cam, 0.15f, 150f) { i, x -> cloud(gfx, x, 40f + (i % 4) * 30f) }
                layer(cam, 0.3f, 210f) { i, x -> cloud(gfx, x + 20, 170f + (i % 2) * 20f) }
            }
            Place.WATER -> {
                gfx.rect(0f, 0f, viewW, 32f, 0xFF8ED0FF.toInt())
                gfx.rect(0f, 32f, viewW, VIEW_H - 32f, 0xFF1E5A9A.toInt())
                gfx.rect(0f, 140f, viewW, 100f, 0xFF184A82.toInt())
                layer(cam, 0.3f, 110f) { i, x ->
                    // Swaying seaweed and coral
                    for (k in 0 until 4) gfx.oval(x + 20 + sin(time * 2 + k + i) * 3, 200f - k * 12, 6f, 14f, 0xFF2E9E5A.toInt())
                    gfx.oval(x + 60, 196f, 22f, 16f, if (i % 2 == 0) 0xFFE86A8A.toInt() else 0xFFFF9A4A.toInt())
                }
                layer(cam, 0.15f, 70f) { i, x ->
                    val by = (200f - (time * 25f + i * 37f) % 170f)
                    gfx.oval(x, by, 4f, 4f, 0x66BFE8FF)
                }
            }
            Place.NIGHT -> {
                gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF141838.toInt())
                for (i in 0 until 40) gfx.rect((i * 97f - cam * 0.05f).mod(viewW), (i * 53f) % 150f, 1.5f, 1.5f, white)
                gfx.oval(viewW * 0.75f, 24f, 28f, 28f, 0xFFF0F0D0.toInt())
                layer(cam, 0.25f, 200f) { i, x ->
                    gfx.oval(x, 160f - (i % 3) * 12f, 220f, 140f, 0xFF1E2A4A.toInt())
                }
            }
            Place.DEEP -> {
                gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF06121A.toInt())
                layer(cam, 0.3f, 80f) { i, x ->
                    // Glowing sculk
                    val glow = (120 + sin(time * 2 + i) * 80).toInt().coerceIn(0, 255)
                    gfx.oval(x + 10, 190f - (i % 3) * 30f, 10f, 6f, argb(glow, 0x2AE8E0))
                    gfx.rect(x + 40, 40f, 2f, 30f + (i % 4) * 10, 0xFF0E2A34.toInt())
                }
            }
            Place.END -> {
                gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF0A0614.toInt())
                for (i in 0 until 50) gfx.rect((i * 89f - cam * 0.03f).mod(viewW), (i * 41f) % 200f, 1.5f, 1.5f, 0xFFE0D0FF.toInt())
                layer(cam, 0.2f, 160f) { i, x ->
                    gfx.rect(x + 40, 90f - (i % 3) * 20f, 24f, 160f, 0xFF1A1028.toInt())
                }
            }
            Place.CASTLE, Place.VOLCANO -> {
                val volcano = level.def.place == Place.VOLCANO
                gfx.rect(0f, 0f, viewW, VIEW_H, if (volcano) 0xFF3A1208.toInt() else 0xFF2A0E12.toInt())
                if (volcano) {
                    layer(cam, 0.2f, 260f) { _, x ->
                        gfx.poly(floatArrayOf(x, x + 100, x + 200), floatArrayOf(220f, 90f, 220f), 0xFF5A2A18.toInt())
                        gfx.oval(x + 88, 84f, 24f, 12f, 0xFFFF6A00.toInt())
                    }
                    gfx.rect(0f, 200f, viewW, 40f, 0x55FF6A00)
                } else {
                    layer(cam, 0.3f, 120f) { i, x ->
                        gfx.rect(x + 20, 60f, 30f, 60f, 0xFF3A1A1E.toInt())
                        gfx.rect(x + 30, 75f, 10f, 16f, if (i % 2 == 0) 0xFFFF8A00.toInt() else 0xFF5A2A20.toInt())
                    }
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
        gfx.oval(x, y, 50f, 20f, white)
        gfx.oval(x + 12, y - 10, 28f, 22f, white)
    }

    private fun renderTiles(gfx: Gfx, cam: Float) {
        val first = (cam / TILE).toInt() - 1
        val last = ((cam + viewW) / TILE).toInt() + 1
        val place = level.def.place
        for (ty in 0 until ROWS) for (tx in first..last) {
            if (tx < 0 || tx >= level.w) continue
            val t = level[tx, ty]
            if (t == Tile.EMPTY) continue
            val under = level[tx, ty - 1].let { it == Tile.GROUND || it == Tile.DIRT }
            val s = when (t) {
                Tile.GROUND -> when (place) {
                    Place.CAVE -> Sprites.stone
                    Place.CASTLE, Place.VOLCANO -> Sprites.hardGrey
                    Place.WATER -> if (under) Sprites.sandstone else Sprites.sandTop
                    Place.DEEP -> TeamArt.deepStone
                    Place.END -> TeamArt.endStone
                    Place.DESERT -> if (under) Sprites.sandstone else Sprites.sandTop
                    Place.SNOW -> if (under) Sprites.frozenDirt else Sprites.snowTop
                    else -> if (under) Sprites.dirt else Sprites.grassTop
                }
                Tile.DIRT -> Sprites.dirt
                Tile.BRICK -> if (place == Place.CAVE) Sprites.brickBlue else Sprites.brick
                Tile.QCOIN, Tile.QHEART, Tile.QSTAR -> if ((time * 3).toInt() % 3 == 0) Sprites.question2 else Sprites.question1
                Tile.QMYSTERY -> Sprites.question1
                Tile.USED -> Sprites.used
                Tile.HARD -> when (place) {
                    Place.CASTLE, Place.VOLCANO -> Sprites.hardGrey
                    Place.END, Place.DEEP -> TeamArt.obsidian
                    else -> Sprites.hard
                }
                Tile.RAIL -> { TeamArt.rail(gfx, tx * TILE - cam, (ty + 1) * TILE.toFloat()); null }
                Tile.SPIKES -> { TeamArt.spikes(gfx, tx * TILE - cam, (ty + 1) * TILE.toFloat()); null }
                Tile.VENT -> TeamArt.vent
                Tile.LAVA -> if (level[tx, ty - 1] == Tile.LAVA) Sprites.lava else Sprites.lavaTop
                Tile.CLOUD -> Sprites.cloud
                else -> Tile.ore(t)?.let { TeamArt.ores[it] }
            } ?: continue
            val wob = if (wonder) sin(time * 4 + tx * 0.6f) * 2f else 0f
            val px = tx * TILE - cam
            val py = ty * TILE + wob
            gfx.sprite(s, px, py)
            // Mystery blocks shimmer in rainbow colors.
            if (t == Tile.QMYSTERY) gfx.rect(px, py, 16f, 16f, argb(110, com.roccoquest.game.rainbow(time, tx * 0.1f)))
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
        gfx.rect(0f, 0f, viewW, 30f, 0x55000000)
        for (i in 0 until MAX_HEARTS) {
            TeamArt.heart(gfx, 6f + i * 12f, 4f, 10f, if (i < hearts) 0xFFE52521.toInt() else 0x66000000)
        }
        for (i in 0 until armor) TeamArt.shield(gfx, 6f + MAX_HEARTS * 12f + i * 9f, 3f, 8f)
        gfx.sprite(Sprites.coin, 6f, 17f, w = 8f, h = 11f)
        gfx.shadowText("x$coins", 16f, 26f, 8f, white)
        gfx.shadowText("\u2605$score", 42f, 26f, 7f, 0xFFFFE14D.toInt())
        var x = max(112f, viewW * 0.25f)
        for (o in Ore.entries) {
            TeamArt.oreIcon(gfx, o, x, 5f, 10f)
            gfx.shadowText(inventory[o.ordinal].toString(), x + 12, 13f, 7f, white)
            x += 25f
        }
        val powerText = if (power == Power.NONE) "NO FLOWER" else "${power.label} Lv${flowerLevel[power.ordinal]}"
        val starText = (if (starT > 0) "  •  STAR ${starT.toInt() + 1}" else "") +
            (if (miniT > 0) "  •  MINI ${miniT.toInt() + 1}" else "")
        val swordText = if (sword != Sword.NONE) "  •  ${sword.label.removeSuffix(" SWORD")} SWORD" else ""
        gfx.shadowText("${TeamArt.pickName(pickTier)} PICK$swordText  •  $powerText$starText", max(112f, viewW * 0.25f), 26f, 7f, 0xFFDDEEFF.toInt())

        if (level.def.shoreX >= 0 && inWater(leader)) {
            // Air meter: a row of bubbles under the HUD.
            val n = 10
            for (i in 0 until n) {
                val full = air / AIR_MAX * n > i
                gfx.oval(8f + i * 10f, 34f, 8f, 8f, if (full) 0xDDBFE8FF.toInt() else 0x44FFFFFF)
            }
            gfx.shadowText("AIR", 8f + n * 10f + 2, 41f, 7f, if (air < 8f && (time * 4).toInt() % 2 == 0) 0xFFFF6060.toInt() else white)
        }
        val b = boss
        if (b != null && !bossDone && (b !is KragJr || b.engaged)) {
            if (b is KingKrag && b.inBones) {
                gfx.shadowText("...he's getting back up!", viewW / 2, 58f, 8f, 0xFFFFE8A0.toInt(), 1)
            }
            val name = when {
                b is KragJr && b.wonderStyle -> "WONDER KRAG JR."
                b is KragJr -> "KRAG JR."
                b is KingKrag && b.wonderStyle -> "WONDER KING KRAG"
                b is KingKrag && b.dry -> "DRY BONES KING KRAG"
                b is Warden -> "THE WARDEN"
                b is EnderDragon -> "ENDER DRAGON"
                else -> "KING KRAG"
            }
            val wonderBoss = (b is KragJr && b.wonderStyle) || (b is KingKrag && b.wonderStyle)
            val bw = 140f
            val bx = viewW / 2 - bw / 2
            gfx.shadowText("$name  ${b.hp}/${b.maxHp}", viewW / 2, 40f, 8f, 0xFFFFB0A0.toInt(), 1)
            gfx.rect(bx - 1, 43f, bw + 2, 7f, 0xFF000000.toInt())
            gfx.rect(bx, 44f, bw * b.hp / b.maxHp, 5f, if (wonderBoss) solidRainbow(time) else 0xFFE53A1E.toInt())
        }
    }

    private fun renderCraft(gfx: Gfx) {
        gfx.rect(0f, 30f, viewW, VIEW_H - 30f, 0x88000000.toInt())
        val px = panelX()
        val pw = panelW()
        val rows = recipes()
        val rh = craftRowH()
        val ph = rowsTop - panelTop + rh * rows.size + 2
        gfx.rect(px - 2, panelTop - 2, pw + 4, ph + 4, 0xFF3A2410.toInt())
        gfx.rect(px, panelTop, pw, ph, 0xFFB98A52.toInt())
        gfx.shadowText("CRAFTING TABLE", px + pw / 2, panelTop + 14, 11f, 0xFFFFF2C8.toInt(), 1)
        gfx.oval(px + pw - 23, panelTop + 1, 18f, 18f, 0xFF8A1010.toInt())
        gfx.text("X", px + pw - 14, panelTop + 14, 10f, white, 1)
        // Tabs
        val tw = pw / TABS.size
        TABS.forEachIndexed { i, name ->
            val tx = px + i * tw
            gfx.rect(tx + 2, tabTop, tw - 4, 17f, if (i == craftTab) 0xFF7A4A20.toInt() else 0xFF9A6A3A.toInt())
            gfx.text(name, tx + tw / 2, tabTop + 12, 8f, if (i == craftTab) 0xFFFFF2C8.toInt() else 0xFFE0C8A0.toInt(), 1)
        }
        rows.forEachIndexed { i, r ->
            val y = rowsTop + i * rh
            gfx.rect(px, y + rh - 1, pw, 1f, 0x55000000)
            when {
                r.icon != null -> gfx.sprite(TeamArt.flowerSprite(r.icon), px + 6, y + 5, w = 17f, h = 17f)
                r.kind == Kind.HEART -> TeamArt.heart(gfx, px + 8, y + 7, 13f, 0xFFE52521.toInt())
                r.kind == Kind.APPLE -> {
                    gfx.oval(px + 7, y + 7, 14f, 14f, 0xFFFFD21F.toInt())
                    gfx.rect(px + 13, y + 3, 2f, 5f, 0xFF7A4A20.toInt())
                    gfx.oval(px + 15, y + 3, 5f, 3f, 0xFF2EB84A.toInt())
                }
                r.kind == Kind.TNT -> {
                    gfx.rect(px + 6, y + 6, 16f, 15f, 0xFFE53A1E.toInt())
                    gfx.rect(px + 6, y + 11, 16f, 5f, white)
                    gfx.text("TNT", px + 14, y + 15.5f, 5f, 0xFF101010.toInt(), 1)
                }
                r.kind == Kind.STAR -> gfx.sprite(Sprites.star1, px + 6, y + 5, w = 17f, h = 17f)
                r.kind == Kind.HOUSE -> {
                    gfx.poly(floatArrayOf(px + 4, px + 14, px + 24), floatArrayOf(y + 12, y + 3, y + 12), 0xFFD8603A.toInt())
                    gfx.rect(px + 7, y + 12, 14f, 10f, 0xFF8A8A8A.toInt())
                    gfx.rect(px + 12, y + 15, 4f, 7f, 0xFF7A4A20.toInt())
                }
                r.kind == Kind.BED -> TeamArt.bed(gfx, px + 2, y + 20)
                r.kind == Kind.SWORD -> TeamArt.sword(gfx, px + 7, y + 20, -0.8f, r.sword)
                r.kind == Kind.MINI -> gfx.sprite(Sprites.miniMushroom, px + 8, y + 7, w = 12f, h = 12f)
                r.kind == Kind.TRAP -> TeamArt.spikes(gfx, px + 6, y + 20)
                else -> TeamArt.pickaxe(gfx, px + 9, y + 22, -0.9f, min(5, pickTier + 1))
            }
            gfx.text(r.title, px + 30, y + 12, 8f, 0xFF2A1808.toInt())
            gfx.text(r.note, px + 30, y + 22, 6f, 0xFF4A3018.toInt())
            // Costs, right to left before the button
            var cx = px + pw - 60f
            if (r.coinCost > 0) {
                cx -= 30f
                gfx.sprite(Sprites.coin, cx, y + 8, w = 8f, h = 11f)
                gfx.text("${r.coinCost}", cx + 10, y + 17, 7f, if (coins >= r.coinCost) 0xFF1E5A1E.toInt() else 0xFF8A1010.toInt())
            }
            for ((o, n) in r.costs.reversed()) {
                cx -= 30f
                TeamArt.oreIcon(gfx, o, cx, y + 8, 10f)
                gfx.text("$n", cx + 12, y + 17, 7f, if (inventory[o.ordinal] >= n) 0xFF1E5A1E.toInt() else 0xFF8A1010.toInt())
            }
            val bx = px + pw - 52
            val color = when {
                !r.enabled -> 0xFF8A7A6A.toInt()
                r.button == "EQUIP" -> 0xFF2E6BE6.toInt()
                else -> 0xFF2EB84A.toInt()
            }
            gfx.rect(bx, y + 4, 46f, 19f, 0xFF2A1808.toInt())
            gfx.rect(bx + 1.5f, y + 5.5f, 43f, 16f, color)
            gfx.text(r.button, bx + 23, y + 17, 8f, white, 1)
            gfx.text("${i + 1}", px + 2, y + 9, 5f, 0x88000000.toInt())
        }
    }

    private fun renderTitle(gfx: Gfx) {
        if (level.def !== Levels.all[0]) level = Level(Levels.all[0])
        camX = time * 20f % (level.w * TILE - viewW)
        renderBackground(gfx, camX)
        renderTiles(gfx, camX)
        gfx.rect(0f, 0f, viewW, VIEW_H, 0x44000000)
        gfx.shadowText("ROCCO & BLOCKY", viewW / 2, 52f, 24f, 0xFFFFD21F.toInt(), 1)
        gfx.shadowText("TEAM UP!", viewW / 2, 78f, 18f, solidRainbow(time * 0.3f), 1)
        gfx.shadowText("Mine • Craft • Build • Stop Wonder King Krag", viewW / 2, 94f, 8f, white, 1)
        val bob = abs(sin(time * 4)) * 3f
        val base = 190f
        gfx.sprite(TeamArt.rocco(Power.NONE, 0), viewW / 2 - 84, base - 24 - bob)
        gfx.sprite(TeamArt.blocky(Power.NONE, false), viewW / 2 - 60, base - 24)
        TeamArt.pickaxe(gfx, viewW / 2 - 52, base - 11, -2.3f, 4)
        Mon.entries.forEachIndexed { i, m -> TeamArt.mon(gfx, m, viewW / 2 - 18 + i * 24f, base, time + i, -1, false) }
        TeamArt.frog(gfx, viewW / 2 + 82, base - 26 - abs(sin(time * 9)) * 4, time)
        if ((time * 2).toInt() % 2 == 0) gfx.shadowText("TAP TO START", viewW / 2, 222f, 12f, white, 1)
    }

    private fun renderSlots(gfx: Gfx) {
        gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF1A2A4A.toInt())
        gfx.shadowText("CHOOSE A SAVE FILE", viewW / 2, 40f, 14f, 0xFFFFD21F.toInt(), 1)
        for (i in 0 until SLOTS) {
            val x = slotCardX(i)
            val w = slotCardW()
            val lv = slotLevel(i)
            gfx.rect(x - 2, 58f, w + 4, 144f, 0xFF0A1428.toInt())
            gfx.rect(x, 60f, w, 140f, if (lv >= 0) 0xFF2E6BE6.toInt() else 0xFF3A4A6A.toInt())
            gfx.shadowText("FILE ${i + 1}", x + w / 2, 76f, 11f, white, 1)
            if (lv < 0) {
                gfx.shadowText("NEW GAME", x + w / 2, 130f, 10f, 0xFFDDEEFF.toInt(), 1)
                gfx.shadowText("+", x + w / 2, 160f, 22f, 0xFFDDEEFF.toInt(), 1)
            } else {
                val m = slotMon(i)
                gfx.shadowText("LEVEL ${lv + 1}", x + w / 2, 94f, 9f, white, 1)
                gfx.shadowText(Levels.all[lv].name, x + w / 2, 106f, 6f, 0xFFDDEEFF.toInt(), 1)
                TeamArt.mon(gfx, m, x + w / 2, 146f, time + i, 1, false)
                gfx.shadowText(m.label, x + w / 2, 160f, 7f, m.color, 1)
                if (slotSlept(i)) gfx.shadowText("Saved in bed ♥", x + w / 2, 170f, 6f, 0xFFFFC8D8.toInt(), 1)
                else if (slotSpotSaved(i)) gfx.shadowText("Saved mid-level", x + w / 2, 170f, 6f, 0xFFC8FFD8.toInt(), 1)
                val armed = eraseArmed == i
                gfx.rect(x + 8, 180f, w - 16, 16f, if (armed) 0xFFE53A1E.toInt() else 0x66000000)
                gfx.text(if (armed) "TAP AGAIN TO ERASE" else "ERASE", x + w / 2, 191f, 6f, white, 1)
            }
        }
        gfx.shadowText("Tap a file to play", viewW / 2, 222f, 9f, 0xFFDDDDDD.toInt(), 1)
    }

    private fun renderPick(gfx: Gfx) {
        gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF1A3A2A.toInt())
        gfx.shadowText("CHOOSE YOUR POKEMON!", viewW / 2, 24f, 13f, 0xFFFFD21F.toInt(), 1)
        gfx.shadowText("FILE ${slot + 1}  \u2022  tap one to see it, tap again to pick", viewW / 2, 37f, 7f, 0xFFDDDDDD.toInt(), 1)
        Mon.entries.forEachIndexed { i, m ->
            val x = pickCardX(i)
            val y = pickCardY(i)
            val w = pickCardW()
            val sel = m == pickChoice
            gfx.rect(x - 2, y - 2, w + 4, pickCardH + 4, if (sel) 0xFFFFD21F.toInt() else 0xFF0A1A10.toInt())
            gfx.rect(x, y, w, pickCardH, argb(255, m.color))
            gfx.rect(x + 2, y + 2, w - 4, pickCardH - 4, 0xEEFFFFFF.toInt())
            gfx.oval(x + w / 2 - 20, y + 6, 40f, 40f, argb(70, m.color))
            TeamArt.mon(gfx, m, x + w / 2, y + 40f, time * 1.5f + i, 1, sel, sel)
            gfx.text(m.label, x + w / 2, y + 56, 8f, 0xFF202020.toInt(), 1)
            gfx.text(m.superName, x + w / 2, y + 67, 5.5f, argb(255, m.color) and 0xFF7F7F7F.toInt() or 0xFF000000.toInt(), 1)
        }
        // The chosen one's description and a GO button
        val m = pickChoice
        gfx.rect(10f, 206f, viewW - 100f, 30f, 0xCC000000.toInt())
        gfx.text(m.label + ": SUPER " + m.superName, 16f, 218f, 7f, argb(255, m.color))
        gfx.text(m.blurb, 16f, 230f, 6f, white)
        val gx = goButtonX()
        gfx.rect(gx - 28, 206f, 84f, 30f, 0xFF0A1A10.toInt())
        gfx.rect(gx - 26, 208f, 80f, 26f, 0xFF2EB84A.toInt())
        gfx.text("GO!", gx + 14, 226f, 12f, white, 1)
    }

    private fun renderTrade(gfx: Gfx) {
        val v = trader ?: return
        gfx.rect(0f, 30f, viewW, VIEW_H - 30f, 0x88000000.toInt())
        val px = panelX()
        val pw = panelW()
        val trades = tradesOf(v)
        val ph = rowsTop - panelTop + rowH * trades.size + 4
        gfx.rect(px - 2, panelTop - 2, pw + 4, ph + 4, 0xFF1E4A2A.toInt())
        gfx.rect(px, panelTop, pw, ph, 0xFFE8D8B0.toInt())
        gfx.shadowText("VILLAGER TRADES", px + pw / 2, panelTop + 14, 11f, 0xFFFFF2C8.toInt(), 1)
        gfx.oval(px + pw - 23, panelTop + 1, 18f, 18f, 0xFF8A1010.toInt())
        gfx.text("X", px + pw - 14, panelTop + 14, 10f, white, 1)
        gfx.text("\"" + greetings[v.seed % greetings.size] + "\"", px + pw / 2, panelTop + 32, 7f, 0xFF2A1808.toInt(), 1)
        trades.forEachIndexed { i, t ->
            val y = rowsTop + i * rowH
            gfx.rect(px, y + rowH - 1, pw, 1f, 0x55000000)
            gfx.text("${i + 1}", px + 4, y + 16, 7f, 0x88000000.toInt())
            // What you pay
            var cx = px + 16f
            for ((o, n) in t.costs) {
                TeamArt.oreIcon(gfx, o, cx, y + 8, 10f)
                gfx.text("$n", cx + 12, y + 17, 8f, if (inventory[o.ordinal] >= n) 0xFF1E5A1E.toInt() else 0xFF8A1010.toInt())
                cx += 30f
            }
            if (t.coinCost > 0) {
                gfx.sprite(Sprites.coin, cx, y + 8, w = 8f, h = 11f)
                gfx.text("${t.coinCost}", cx + 10, y + 17, 8f, if (coins >= t.coinCost) 0xFF1E5A1E.toInt() else 0xFF8A1010.toInt())
                cx += 30f
            }
            gfx.text("\u2192", cx + 4, y + 17, 10f, 0xFF2A1808.toInt())
            gfx.text(t.title, cx + 22, y + 17, 8f, 0xFF2A1808.toInt())
            val ok = has(t.costs, t.coinCost)
            val bx = px + pw - 52
            gfx.rect(bx, y + 4, 46f, 19f, 0xFF2A1808.toInt())
            gfx.rect(bx + 1.5f, y + 5.5f, 43f, 16f, if (ok) 0xFF2EB84A.toInt() else 0xFF8A7A6A.toInt())
            gfx.text("TRADE", bx + 23, y + 17, 8f, white, 1)
        }
    }

    private fun renderIntro(gfx: Gfx) {
        gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF101018.toInt())
        gfx.shadowText("LEVEL ${levelIndex + 1} of ${Levels.all.size}", viewW / 2, 70f, 12f, 0xFFDDDDDD.toInt(), 1)
        gfx.shadowText(level.def.name, viewW / 2, 96f, 20f, 0xFFFFD21F.toInt(), 1)
        gfx.sprite(TeamArt.rocco(power, 0), viewW / 2 - 36, 112f)
        gfx.sprite(TeamArt.blocky(power, false), viewW / 2 - 14, 112f)
        TeamArt.mon(gfx, mon, viewW / 2 + 18, 136f, time, -1, false)
        gfx.shadowText(level.def.tip, viewW / 2, 162f, 8f, white, 1)
        gfx.shadowText("FILE ${slot + 1}   Hearts: $MAX_HEARTS   Pickaxe: ${TeamArt.pickName(pickTier)}", viewW / 2, 180f, 8f, 0xFFAADDFF.toInt(), 1)
    }

    private fun renderVictory(gfx: Gfx) {
        gfx.rect(0f, 0f, viewW, VIEW_H, 0x55000000)
        gfx.shadowText("YOU BEAT THE ENDER DRAGON!", viewW / 2, 70f, 16f, solidRainbow(time * 0.5f), 1)
        gfx.shadowText("Rocco + Blocky + ${mon.label} = BEST TEAM EVER!", viewW / 2, 90f, 9f, white, 1)
        gfx.shadowText("SCORE $score", viewW / 2, 106f, 10f, 0xFFFFD21F.toInt(), 1)
        val dance = abs(sin(time * 6)) * 6f
        val base = 170f
        gfx.sprite(TeamArt.rocco(power, 2), viewW / 2 - 60, base - 24 - dance)
        gfx.sprite(TeamArt.blocky(power, (time * 4).toInt() % 2 == 0), viewW / 2 - 30, base - 24 - (6 - dance))
        TeamArt.mon(gfx, mon, viewW / 2 + 8, base - dance, time, 1, true)
        TeamArt.frog(gfx, viewW / 2 + 30, base - 26 - (6 - dance), time)
        if (stateT > 3f) gfx.shadowText("Tap to play again", viewW / 2, 206f, 9f, 0xFFDDDDDD.toInt(), 1)
    }
}
