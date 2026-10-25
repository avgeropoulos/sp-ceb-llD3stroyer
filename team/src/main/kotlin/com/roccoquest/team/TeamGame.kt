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
import com.roccoquest.game.gradientStops
import com.roccoquest.game.mixColor
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
    enum class State { TITLE, SLOTS, PICK, INTRO, PLAY, CRAFT, TRADE, SLEEP, OUCH, CLEAR, STICKER, BOOK, VICTORY, HOME, MAP, CONTROLS, PARK, SCENE }

    companion object {
        const val MAX_HEARTS = 5
        /** How fast a BlastCannon shoots you out. */
        const val CANNON_SPEED = 520f
        const val MAX_ARMOR = 5
        const val COST = 5
        /** How many blocks each pickaxe can mine before it breaks (the wooden one never breaks). */
        fun pickDurability(tier: Int) = when (tier) { 2 -> 80; 3 -> 60; 4 -> 150; 5 -> 200; else -> 0 }
        const val SLOTS = 3
        /** Seconds for the Pokemon's SUPER move to recharge. */
        const val SUPER_TIME = 25f
        /** Pokemon can grow up to this level (a little stronger each time, never too strong). */
        const val MON_MAX_LEVEL = 10
        val STICKER_KEYS = (Stickers.all.size + 29) / 30
        val PLUSH_KEYS = (Plushies.all.size + 24) / 25
        val PLUSH_PAGES = (Plushies.all.size + 24) / 25
        const val STICKERS_PER_PAGE = 40
        const val STICKER_PACK_COST = 30
        const val PAINT_COST = 25
        const val STAR_TIME = 10f
        /** Seconds of air underwater before the team starts losing hearts. */
        const val AIR_MAX = 30f
        const val POSEIDON_COST = 50
        const val WAVE_TIME = 2.4f
        const val BONUS_TIME = 30f
        const val QUAKE_WARN = 2.2f
        const val CLAW_COST = 30
        const val POSEIDON_LINE = "Είμαι ο Ποσειδώνας! Είμαι ο πιο δυνατός της θάλασσας!"
        const val SCORE_MULT = 3
        const val MAX_PARTICLES = 240
        const val MAX_FLOAT_TEXTS = 12
        /** Levels to beat before Cappy can be made. */
        const val CAPPY_LEVELS = 15
        /** After getting hurt, nothing can hurt you again for this long (a bit longer than before). */
        const val HURT_SAFE_TIME = 2.1f
        /** Houses, beds and furniture are bought in the SHOP at home now, not crafted. */
        val TABS = listOf("FLOWERS", "SUPPLIES", "WEAPONS", "BOWS", "ARMOR", "SHIELDS")
        /** How close bad guys have to be for the Black Flower to get them. */
        const val BLACK_RANGE = 170f
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
    /** Everybody, in and out of the world. */
    val allHeroes = listOf(rocco, blocky, partner)
    /** Only one of Rocco or Blocky is out at a time, together with the Pokemon. */
    var heroOut = Who.ROCCO
        private set
    /** The team that's actually out in the world: Rocco OR Blocky, plus the Pokemon. */
    val team get() = listOf(if (heroOut == Who.BLOCKY) blocky else rocco, partner)
    var mon: Mon
        get() = partner.mon
        private set(value) { partner.mon = value }
    var leaderWho = Who.ROCCO
        private set
    val leader get() = allHeroes.first { it.who == leaderWho }
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
    /** A Long, so even huge scores never wrap around to negative numbers. */
    var score = 0L
        private set
    val inventory = IntArray(Ore.entries.size)
    var pickTier = 1
        private set
    /** Blocky's equipped sword, and which swords he's made (one bit each). */
    var sword = Sword.NONE
        private set
    private var swordsOwned = 0
    /** The kind of house the team builds, the furniture inside, and the plushies collected (one bit each). */
    var houseType = HouseType.COTTAGE
        private set
    var furnitureBits = 0L
        private set
    /** Which of the 100 plushies you have. */
    val plushOwned = BooleanArray(Plushies.all.size)
    /** Beds you own (one bit each) and the one in your room. */
    var bedsOwned = 0
        private set
    var bedStyle = BedStyle.RED
        private set
    fun hasBed() = bedsOwned != 0
    fun ownsBed(b: BedStyle) = bedsOwned and (1 shl b.ordinal) != 0
    fun hasFurniture(f: Furniture) = furnitureBits and (1L shl f.ordinal) != 0L
    fun hasPlushie(p: Plushie) = plushOwned[p.id]
    val plushieCount get() = plushOwned.count { it }
    /** Inside the house: is the shop open, and which tab? */
    var homeShop = false
        private set
    /** The Pokemon menu at home: PET, PLAY BALL, TRAIN and SWITCH POKEMON. */
    var monMenu = false
    /** Showing the list of Pokemon to switch to. */
    var monSwitch = false
    private var monCursor = 0
    /** How many more times the Pokemon wants to play before it needs a rest (it's refilled by playing a level). */
    var monEnergy = 5
    /** What the Pokemon is doing at home right now ("PET", "BALL", "TRAIN"), and for how long. */
    private var monPlay = ""
    private var monPlayT = 0f
    /** Each Pokemon's experience points (levels 1 to 10). */
    val monXp = IntArray(Mon.entries.size)
    /** The color the house is painted (an index into [Homes.PAINTS]; 0 is its normal look). */
    var houseColor = 0
    /** How many floors the house has (1 to [Homes.MAX_FLOORS]), and which one you're on. */
    var houseFloors = 1
    var homeFloor = 0
        private set
    /** Bouncing on the trampoline. */
    private var homeHopT = 0f
    val housePaint get() = Homes.PAINTS.getOrElse(houseColor) { 0 }
    var shopTab = 0
        private set
    private var shopPage = 0
    /** Inside the house: the plushie collection book, and where you're standing. */
    var plushBook = false
        private set
    private var bookPage = 0
    private var stickerPage = 0
    var homeX = 120f
        private set
    private var homeFacing = 1
    private var homeAnim = 0f
    /** The garden behind the house (the GARDEN button at home). */
    val garden = Garden(this)
    var inGarden = false
        private set
    /** The BABY MACHINE turned everyone into babies (toggle it back at the machine). */
    var babyMode = false
    /** The house has an elevator (it goes to every floor and the backyard). */
    var hasElevator = false
    private var elevatorMenu = false
    private var babyZapT = 0f

    /** World map: levels unlocked so far, levels beaten, which world page and which spot is picked. */
    var unlocked = 1
        private set
    private val cleared = BooleanArray(Levels.all.size)
    fun isCleared(i: Int) = cleared[i]
    var mapWorld = 0
        private set
    /** The selected spot on the map: a level index, or -1 for home. */
    var mapSel = 0
        private set

    /** The shield Rocco or Blocky carries, and how many more hits it can block. */
    var shieldType = Shield.NONE
        private set
    var shieldHp = 0
        private set
    fun hasSword(s: Sword) = swordsOwned and (1 shl s.ordinal) != 0
    /** Swings left before the sword breaks, and blocks left before the pickaxe breaks. */
    var swordHp = 0
        private set
    var pickHp = 0
        private set
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

    /** While the team is in a sunset bonus room, this remembers the level they came from. */
    private class Away(
        val level: Level, val foes: List<Foe>, val things: List<Thing>, val frogSpots: List<Int>, val goal: Goal?,
        val house: House?, val bed: Bed?, val respawnX: Float, val respawnY: Float, val backX: Float, val backY: Float,
    )
    private var away: Away? = null
    val inBonus get() = away != null
    /** Seconds left in the bonus room before the sun goes down. */
    var bonusT = 0f
        private set
    private var portalHit: Portal? = null

    /** Dash the dino, while Rocco is riding him. */
    var dino: Dino? = null
        private set
    private var flutterT = 0f

    /** Earthquake levels: every so often the ground cracks, then boulders smash it into pits. */
    var quakeLevel = false
        private set
    private var quakeT = 0f
    /** Seconds until the cracked ground caves in, or -1 when there's no quake going on. */
    var quakeWarn = -1f
        private set
    val quakeCols = ArrayList<Int>()
    /** For tests: lets the bot run levels without earthquakes. */
    internal var quakesOn = true

    /** Poseidon's giant wave: where its front is on the screen, and which way it rolls. */
    private var waveS = 0f
    private var waveDir = 1
    private val waveHit = HashSet<Foe>()

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
    private val prev = BooleanArray(16)

    /** Menu moves from a controller (or keyboard) this frame. */
    private class Nav { var left = false; var right = false; var up = false; var down = false; var a = false; var b = false; var lb = false; var rb = false }
    private val nav = Nav()
    private var slotCursor = 0
    private var stickerCursor = 0
    private var craftCursor = 0
    private var tradeCursor = 0
    private var pauseCursor = 0
    private var shopCursor = 0

    /** Controller setup: the buttons for JUMP, THROW, SWITCH and SUPER (names come from the phone). */
    val padActions = listOf("JUMP", "THROW", "SWITCH", "SUPER")
    val padNames = arrayOf("A", "B", "X", "Y")
    /** Which action the setup screen is waiting for, or -1 when it's showing the summary. */
    var padStep = -1
        private set
    /** Set by the phone when a controller button or stick is used. */
    @Volatile var padConnected = false
    /** For the controller tester: the last button the phone saw, where the stick is, and frames per second. */
    @Volatile var padLastButton = ""
    @Volatile var padLastTime = -10f
    @Volatile var stickX = 0f
    @Volatile var stickY = 0f
    @Volatile var fps = 0
    /** The phone reports a button press for the tester. */
    fun padSaw(name: String) { padLastButton = name; padLastTime = time }
    /** The phone puts the standard buttons back when this is called. */
    var onPadReset: (() -> Unit)? = null

    /** The phone tells us which button was pressed for the action the setup screen asked for. */
    fun padPressed(name: String) {
        if (padStep !in padActions.indices) return
        padNames[padStep] = name
        sfx(Sound.COIN)
        padStep++
        if (padStep >= padActions.size) { padStep = -1; sfx(Sound.ONEUP); toast("Controller ready!") }
    }

    fun openControllerSetup() { padStep = -1; setState(State.CONTROLS) }

    private fun updateControls(tapped: Boolean) {
        if (padStep >= 0) {
            // Waiting for a button: tapping the screen cancels.
            if (tapped && input.tapX >= 0f && stateT > 0.3f) padStep = -1
            return
        }
        if (stateT < 0.3f) return
        if (nav.a || nav.b) { setState(State.TITLE); return }
        if (!tapped || input.tapX < 0f) return
        val y = input.tapY
        when {
            y in 168f..192f && input.tapX < viewW / 2 -> { padStep = 0; sfx(Sound.KICK) }
            y in 168f..192f -> { onPadReset?.invoke(); sfx(Sound.KICK); toast("Standard buttons: A jump, B throw, X switch, Y super") }
            y in 200f..226f -> setState(State.TITLE)
        }
    }

    private fun renderControls(gfx: Gfx) {
        gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF1A1A2E.toInt())
        gfx.shadowText("CONTROLLER", viewW / 2, 26f, 16f, 0xFFFFD21F.toInt(), 1)
        gfx.shadowText(if (padConnected) "Controller found!" else "Connect a controller (like a Switch Pro Controller) with Bluetooth", viewW / 2, 42f, 7f, if (padConnected) 0xFF7FE07F.toInt() else 0xFFDDDDDD.toInt(), 1)
        if (padStep >= 0) {
            gfx.shadowText("Press the button you want for", viewW / 2, 96f, 10f, white, 1)
            gfx.shadowText(padActions[padStep], viewW / 2, 126f, 22f, solidRainbow(time * 0.5f), 1)
            gfx.shadowText("(tap the screen to stop)", viewW / 2, 150f, 7f, 0xFFAAAAAA.toInt(), 1)
            return
        }
        val rows = padActions.indices.map { "${padActions[it]}" to padNames[it] } + listOf(
            "MOVE" to "D-PAD or STICK", "DUCK / SLAM" to "DOWN", "SPRINT" to "DOUBLE-TAP SIDEWAYS",
            "PAUSE / CRAFT" to "+ / -", "TNT / LONG JUMP" to "L / R", "BLACK FLOWER / POSEIDON" to "ZL / ZR",
        )
        rows.forEachIndexed { i, (a, b) ->
            val y = 52f + i * 10.5f
            gfx.text(a, viewW / 2 - 8, y + 8, 7f, 0xFFDDEEFF.toInt(), 2)
            gfx.text(b, viewW / 2 + 8, y + 8, 7f, 0xFFFFD21F.toInt(), 0)
        }
        gfx.rect(viewW / 2 - 150, 168f, 140f, 24f, 0xFF2E6BE6.toInt())
        gfx.text("SET MY BUTTONS", viewW / 2 - 80, 184f, 8f, white, 1)
        gfx.rect(viewW / 2 + 10, 168f, 140f, 24f, 0xFF6A6A7A.toInt())
        gfx.text("STANDARD BUTTONS", viewW / 2 + 80, 184f, 8f, white, 1)
        gfx.rect(viewW / 2 - 60, 200f, 120f, 24f, 0xFF2EB84A.toInt())
        gfx.text("DONE", viewW / 2, 216f, 9f, white, 1)
        if (toastT > 0) gfx.shadowText(toast, viewW / 2, 160f, 7f, 0xFF7FE07F.toInt(), 1)
        renderPadTester(gfx)
    }

    /** A little live tester: press a button or move the stick and see what the game gets. */
    private fun renderPadTester(gfx: Gfx) {
        val w = 92f
        val x = viewW - w - 8f
        val y = 50f
        gfx.rect(x, y, w, 108f, 0xFF262640.toInt())
        gfx.text("TEST IT", x + w / 2, y + 10, 7f, 0xFFFFD21F.toInt(), 1)
        // The stick: the dot follows it.
        val cx = x + w / 2
        val cy = y + 34
        gfx.oval(cx - 18, cy - 18, 36f, 36f, 0xFF3C3C5C.toInt())
        val held = input.left || input.right || input.up || input.down
        gfx.oval(cx + stickX.coerceIn(-1f, 1f) * 14 - 5, cy + stickY.coerceIn(-1f, 1f) * 14 - 5, 10f, 10f,
            if (held) 0xFF7FE07F.toInt() else 0xFFDDDDDD.toInt())
        val dir = buildString {
            if (input.left) append("LEFT ")
            if (input.right) append("RIGHT ")
            if (input.up) append("UP ")
            if (input.down) append("DOWN")
        }.trim()
        gfx.text(dir.ifEmpty { "move the stick" }, cx, y + 64, 6f, 0xFFDDDDDD.toInt(), 1)
        val fresh = time - padLastTime < 1.5f
        val action = padNames.indexOf(padLastButton).let { if (it >= 0) padActions[it] else "" }
        gfx.text(if (padLastButton.isEmpty()) "press a button" else padLastButton, cx, y + 80, 9f,
            if (fresh) 0xFF7FE07F.toInt() else 0xFFDDDDDD.toInt(), 1)
        if (action.isNotEmpty()) gfx.text("= $action", cx, y + 90, 6f, 0xFFFFD21F.toInt(), 1)
        if (fps > 0) gfx.text("$fps frames/sec", cx, y + 103, 6f, if (fps in 1..45) 0xFFE53A1E.toInt() else 0xFFAAAAAA.toInt(), 1)
    }

    /** The armor the team is wearing, and how many more hits it can take. */
    var armorType = Armor.NONE
        private set
    var armorHp = 0
        private set
    /** Black Flowers in the bag. */
    var blackFlowers = 0
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
    /** Sitting in a BlastCannon, waiting to fire. */
    var inCannon: BlastCannon? = null
        private set
    /** Flying out of a cannon (no steering until you land). */
    var cannonFlyT = 0f
        private set
    /** Holding King Krag by the tail (the TAIL SPIN boss). */
    var grabbing: TailKrag? = null
    /** ACTION was pressed this frame (for things like grabbing a tail). */
    var actionTap = false
        private set
    var floodT = 0f
        private set
    private var trader: Villager? = null
    private var dogT = 10f
    private val ventT = HashMap<Int, Float>()
    private var spikeT = 0f

    // Stickers: one bit per sticker you own, the three on offer, and what comes next.
    private val stickerOwned = BooleanArray(Stickers.all.size)
    var stickerChoices: List<Int> = emptyList()
        private set
    var stickerPicked = -1
        private set
    private var afterSticker = 0
    fun hasSticker(i: Int) = stickerOwned.getOrElse(i) { false }
    val stickerCount get() = Stickers.all.indices.count { hasSticker(it) }
    /** Seconds left of Tanooki Rocco and of the Wing Cap. */
    var tanookiT = 0f
        private set
    /** Metal Rocco: can't be hurt, and he's so heavy he walks on the sea floor. */
    var metalT = 0f
    /** A bad guy Cappy captured: Rocco is that bad guy (with his cap on) for a while. */
    var capture: Foe? = null
        private set
    var captureKind: CaptureKind? = null
        private set
    private var captureT = 0f
    /** Shellback capture: dashing in a spinning shell. */
    private var captureDashT = 0f
    /** Mega Mushroom: giant Rocco. */
    var megaT = 0f
    /** Propeller Mushroom, and whether the spin-up was used in this jump. */
    var propT = 0f
    private var propUsed = false

    /** Rocco can't be hurt while spinning in the blue shell (and just after). */
    private var shellSafeT = 0f
    /** Blocky's bow (made at the crafting table, BOWS tab). */
    var bow = Bow.NONE
    private var bowCooldown = 0f
    /** Crazy mushrooms (in boss fights): giant fireballs or ice balls for a while. */
    var crazyFireT = 0f
    var crazyIceT = 0f
        private set
    val metal get() = metalT > 0f
    var wingT = 0f
        private set
    /** Seconds left of Mini Rocco. */
    var miniT = 0f
        private set

    fun sfx(s: Sound) = sound.play(s)
    fun spawn(t: Thing) { pending += t }
    fun puff(x: Float, y: Float) { particles += Puff(x, y) }
    fun addParticle(p: Particle) { particles += p }
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
        gainXp(if (f.maxHp >= 10) 3 else 1)
    }

    /** Stomping bad guys one after another without landing scores more and more. */
    private fun stompCombo(h: Hero, f: Foe) {
        h.combo++
        // Doubles each stomp up to 8000 (shifting further would overflow and go negative).
        val bonus = if (h.combo >= 8) 8000 else min(8000, 100 shl (h.combo - 1))
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
        // 30 stickers per number: "stk0", "stk1"... (older saves used "stickersLo"/"stickersHi")
        for (k in 0 until STICKER_KEYS) storage.save(key("stk$k"), (0 until 30).sumOf { b -> if (hasSticker(k * 30 + b)) 1 shl b else 0 })
        storage.save(key("stickersLo"), 0)
        storage.save(key("stickersHi"), 0)
        for (m in Mon.entries) storage.save(key("monXp${m.ordinal}"), monXp[m.ordinal])
        storage.save(key("houseColor"), houseColor)
        storage.save(key("floors"), houseFloors)
        storage.save(key("baby"), if (babyMode) 1 else 0)
        storage.save(key("elevator"), if (hasElevator) 1 else 0)
        for (f in 0 until Homes.MAX_FLOORS) {
            storage.save(key("bld${f}n"), blocks[f].size)
            blocks[f].entries.forEachIndexed { i, (c, b) -> storage.save(key("bld${f}_$i"), 1 + c + b.ordinal * 100_000) }
        }
        storage.save(key("decorN"), decor.size)
        decor.forEachIndexed { i, d -> storage.save(key("decor_$i"), Decor.pack(d)) }
        garden.save()
        storage.save(key("bow"), bow.ordinal)
        for (f in Furniture.entries) storage.save(key("fpos${f.ordinal}"), furnPos[f.ordinal])
        storage.save(key("swords"), swordsOwned)
        storage.save(key("swordHp"), swordHp)
        storage.save(key("pickHp"), pickHp)
        storage.save(key("armorType"), armorType.ordinal)
        storage.save(key("armorHp"), armorHp)
        storage.save(key("blackFlowers"), blackFlowers)
        storage.save(key("sword"), sword.ordinal)
        storage.save(key("shieldType"), shieldType.ordinal)
        storage.save(key("houseType"), houseType.ordinal)
        storage.save(key("furniture"), (furnitureBits and 0xFFFFFFFFL).toInt())
        storage.save(key("furniture2"), (furnitureBits ushr 32).toInt())
        for (k in 0 until PLUSH_KEYS) storage.save(key("plush$k"), (0 until 25).sumOf { b -> val i = k * 25 + b; if (i < plushOwned.size && plushOwned[i]) 1 shl b else 0 })
        storage.save(key("beds"), bedsOwned)
        storage.save(key("bedStyle"), bedStyle.ordinal)
        storage.save(key("unlocked"), unlocked)
        // One number per world (21 levels each): "clearedLo", "clearedHi", then "cleared2", "cleared3"...
        for (w in 0 until worldCount()) {
            val bits = (0 until WORLD_SIZE).sumOf { k -> val i = w * WORLD_SIZE + k; if (i < cleared.size && cleared[i]) 1 shl k else 0 }
            storage.save(key(clearedKey(w)), bits)
        }
        storage.save(key("shieldHp"), shieldHp)
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
        if (away != null) { toast("Finish the bonus room first!"); return }
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
        val oldBits = (storage.load(key("stickersLo")).toLong() and 0xFFFFFFFFL) or (storage.load(key("stickersHi")).toLong() shl 32)
        stickerOwned.fill(false)
        for (i in 0 until 64) if (oldBits and (1L shl i) != 0L && i < stickerOwned.size) stickerOwned[i] = true
        for (k in 0 until STICKER_KEYS) {
            val bits = storage.load(key("stk$k"))
            for (b in 0 until 30) if (bits and (1 shl b) != 0 && k * 30 + b < stickerOwned.size) stickerOwned[k * 30 + b] = true
        }
        for (m in Mon.entries) monXp[m.ordinal] = storage.load(key("monXp${m.ordinal}"))
        // Each evolution line shares one level (kept on its first form). Older saves kept it per Pokemon.
        for (line in MON_LINES) monXp[line[0].ordinal] = line.maxOf { monXp[it.ordinal] }
        houseColor = storage.load(key("houseColor")).coerceIn(0, Homes.PAINTS.lastIndex)
        houseFloors = storage.load(key("floors")).coerceIn(1, Homes.MAX_FLOORS)
        bow = Bow.entries.getOrElse(storage.load(key("bow"))) { Bow.NONE }
        for (f in Furniture.entries) furnPos[f.ordinal] = storage.load(key("fpos${f.ordinal}"))
        monEnergy = 5
        swordsOwned = storage.load(key("swords"))
        armorType = Armor.entries.getOrElse(storage.load(key("armorType"))) { Armor.NONE }
        armorHp = storage.load(key("armorHp"))
        if (armorHp <= 0) armorType = Armor.NONE
        blackFlowers = storage.load(key("blackFlowers"))
        sword = Sword.entries.getOrElse(storage.load(key("sword"))) { Sword.NONE }
        if (!hasSword(sword)) sword = Sword.NONE
        // Older saves didn't track wear: their tools start out brand new.
        swordHp = storage.load(key("swordHp")).let { if (it <= 0) sword.durability else it }
        pickHp = storage.load(key("pickHp")).let { if (it <= 0) pickDurability(pickTier) else it }
        shieldType = Shield.entries.getOrElse(storage.load(key("shieldType"))) { Shield.NONE }
        houseType = HouseType.entries.getOrElse(storage.load(key("houseType"))) { HouseType.COTTAGE }
        furnitureBits = (storage.load(key("furniture")).toLong() and 0xFFFFFFFFL) or (storage.load(key("furniture2")).toLong() shl 32)
        plushOwned.fill(false)
        for (k in 0 until PLUSH_KEYS) {
            // Older saves kept their first 20 plushies in "plushies": the same ones come first now.
            val bits = storage.load(key("plush$k")) or (if (k == 0) storage.load(key("plushies")) else 0)
            for (b in 0 until 25) if (bits and (1 shl b) != 0 && k * 25 + b < plushOwned.size) plushOwned[k * 25 + b] = true
        }
        bedsOwned = storage.load(key("beds"))
        bedStyle = BedStyle.entries.getOrElse(storage.load(key("bedStyle"))) { BedStyle.RED }
        for (w in 0 until worldCount()) {
            val bits = storage.load(key(clearedKey(w)))
            for (k in 0 until WORLD_SIZE) { val i = w * WORLD_SIZE + k; if (i < cleared.size) cleared[i] = bits and (1 shl k) != 0 }
        }
        // Older saves: everything up to the saved level is open (and the ones before it beaten).
        val lv = storage.load(key("level"))
        unlocked = maxOf(storage.load(key("unlocked")), lv, 1).coerceAtMost(Levels.all.size)
        for (i in 0 until lv - 1) cleared[i] = true
        shieldHp = storage.load(key("shieldHp"))
        if (shieldHp <= 0) shieldType = Shield.NONE
        mon = slotMon(slot)
        garden.load()
        babyMode = storage.load(key("baby")) == 1
        hasElevator = storage.load(key("elevator")) == 1
        for (f in 0 until Homes.MAX_FLOORS) {
            blocks[f].clear()
            for (i in 0 until storage.load(key("bld${f}n")).coerceIn(0, Build.MAX)) {
                val v = storage.load(key("bld${f}_$i")) - 1
                val b = BuildBlock.entries.getOrNull(v / 100_000) ?: continue
                if (v >= 0 && v % 100_000 < Build.COLS * Build.ROWS) blocks[f][v % 100_000] = b
            }
        }
        decor.clear()
        for (i in 0 until storage.load(key("decorN")).coerceIn(0, Decor.MAX)) Decor.unpack(storage.load(key("decor_$i")))?.let { decor += it }
    }

    fun eraseSlot(s: Int) {
        for (name in listOf("level", "mon", "pick", "power", "coins", "armor", "tnt", "swords", "sword", "armorType", "armorHp", "blackFlowers", "shieldType", "shieldHp", "swordHp", "pickHp", "houseType", "furniture", "furniture2", "plushies", "plush0", "plush1", "plush2", "plush3", "beds", "bedStyle", "unlocked", "clearedLo", "clearedHi", "cleared2", "cleared3", "cleared4", "cleared5", "cleared6", "stickersLo", "stickersHi", "houseColor", "floors", "scenes", "bow", "houseLevel", "houseX", "houseY", "bed", "bedLevel", "spotLevel", "spotX", "spotY", "baby", "seed", "elevator", "decorN")) {
            storage.save(key(name, s), 0)
        }
        Ore.entries.forEach { storage.save(key("ore_${it.name}", s), 0) }
        Power.entries.forEach { storage.save(key("flower_${it.name}", s), 0) }
        for (k in 0 until STICKER_KEYS) storage.save(key("stk$k", s), 0)
        for (k in 0 until PLUSH_KEYS) storage.save(key("plush$k", s), 0)
        for (m in Mon.entries) storage.save(key("monXp${m.ordinal}", s), 0)
        for (f in Furniture.entries) storage.save(key("fpos${f.ordinal}", s), 0)
        // Old block entries are ignored once the per-floor count is cleared.
        for (floor in 0 until Homes.MAX_FLOORS) storage.save(key("b3d${floor}n", s), 0)
        // Every world's beaten levels (the list above only knew the first ones).
        for (w in 0 until worldCount()) storage.save(key(clearedKey(w), s), 0)
        for (f in 0 until Homes.MAX_FLOORS) storage.save(key("bld${f}n", s), 0)
        for (i in 0 until Garden.PLOTS) { storage.save(key("plot$i", s), 0); storage.save(key("grow$i", s), 0) }
        for (c in Crop.entries) storage.save(key("food${c.ordinal}", s), 0)
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
        stickerOwned.fill(false)
        monXp.fill(0)
        houseColor = 0
        houseFloors = 1
        bow = Bow.NONE
        furnPos.fill(0)
        monEnergy = 5
        swordsOwned = 0
        swordHp = 0
        pickHp = 0
        armorType = Armor.NONE
        armorHp = 0
        blackFlowers = 0
        sword = Sword.NONE
        shieldType = Shield.NONE
        shieldHp = 0
        houseType = HouseType.COTTAGE
        furnitureBits = 0L
        garden.load()
        babyMode = false
        hasElevator = false
        decor.clear()
        carryingDecor = null
        blocks.forEach { it.clear() }
        building = false
        inGarden = false
        plushOwned.fill(false)
        bedsOwned = 0
        bedStyle = BedStyle.RED
        unlocked = 1
        cleared.fill(false)
        mapWorld = 0
        mapSel = 0
        startLevel(0)
    }

    fun continueGame(slot: Int = this.slot) {
        this.slot = slot
        loadSlot()
        score = 0
        val lv = slotLevel(slot).coerceIn(0, Levels.all.lastIndex)
        levelIndex = lv
        // Back to the world map, standing on the level you were playing.
        goToMap(lv)
    }

    // ======================================================================== Levels

    private fun setState(s: State) {
        // Coming back to the title picks the next title song, so it changes every now and then.
        if (s == State.TITLE && state != State.TITLE) nextTitleSong()
        // Leaving the house switches the 3D view (and its touch buttons) off.
        if (s != State.HOME && home3d) { home3d = false; TeamControls.mode3d = false }
        state = s
        stateT = 0f
    }

    /** The title songs take turns (remembered between games). */
    private val TITLE_SONGS = listOf(Music.OVERWORLD, Music.FANFARE, Music.DREAMY, Music.ADVENTURE, Music.HEROIC, Music.PARADE)
    var titleSong = TITLE_SONGS[(storage.load("titleSong") % TITLE_SONGS.size + TITLE_SONGS.size) % TITLE_SONGS.size]
        private set
    private fun nextTitleSong() {
        val n = storage.load("titleSong") + 1
        storage.save("titleSong", n)
        titleSong = TITLE_SONGS[n % TITLE_SONGS.size]
    }

    fun startLevel(index: Int, fromCheckpoint: Boolean = false, restoreHome: Boolean = false) {
        // Knocked out in a bonus room? Go back to the real level's checkpoint.
        away?.let { respawnX = it.respawnX; respawnY = it.respawnY }
        away = null
        bonusT = 0f
        portalHit = null
        jumpBufT = 0f; coyoteT = 0f; actionBufT = 0f; jumpHoldT = 0f
        val keepX = respawnX
        val keepY = respawnY
        val keepHouse = if (index == levelIndex) house else null
        val keepBed = if (index == levelIndex) bed else null
        levelIndex = index
        level = Level(Levels.all[index])
        foes.clear(); things.clear(); pending.clear(); particles.clear(); frogSpots.clear()
        boss = null; bossDone = false; wonder = false; camLock = null; frog = null; goal = null
        house = null; bed = null
        if (dino != null) dismount()
        riding = null; inCannon = null; cannonFlyT = 0f; grabbing = null; trader = null; floodT = 0f; air = AIR_MAX; dogT = 10f; ventT.clear(); planSpotty(index)
        if (rocco.mini) setMini(false)
        miniT = 0f
        tanookiT = 0f
        metalT = 0f
        wingT = 0f
        megaT = 0f
        propT = 0f
        capture = null; captureKind = null; captureDashT = 0f
        hearts = MAX_HEARTS
        if (power == Power.GRASS) armor = max(armor, 2)
        invuln = 0f
        starT = 0f
        leaderWho = Who.ROCCO
        heroOut = Who.ROCCO
        for (ty in 0 until ROWS) for (tx in 0 until level.w) {
            val px = tx * TILE.toFloat()
            val bottom = (ty + 1) * TILE.toFloat()
            when (val c = level.def.rows[ty][tx]) {
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
                '5' -> foes += Warden(px, bottom, hp = 55)
                '3' -> foes += KingBooHoo(px, bottom)
                '4' -> foes += WitchBoss(px, bottom)
                '2' -> foes += Witch(px, bottom)
                '6' -> foes += Snowman(px, bottom)
                '7' -> foes += Penguin(px, bottom)
                '8' -> foes += HammerHank(px, bottom)
                '9' -> foes += Chompy(px, bottom)
                '0' -> foes += ZoomerLauncher(px, bottom)
                '1' -> foes += Gummy(px, bottom)
                '!' -> foes += RockStarKrag(px, bottom)
                '&' -> foes += TailKrag(px, bottom)
                '{' -> foes += MechaKrag(px, bottom)
                '}' -> foes += GalaxyKrag(px, bottom)
                '+' -> things += SpikeBomb(px, bottom)
                '>' -> things += BlastCannon(px, bottom)
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
                'P' -> things += Portal(px, bottom)
                '$' -> things += MetalItem(px, ty * TILE.toFloat(), popOut = false)
                'y' -> things += Dino(px, bottom)
                else -> spawnMonster(c, px, bottom)?.let { foes += it }
            }
        }
        quakeLevel = isQuakeLevel(index)
        quakeT = 14f
        quakeWarn = -1f
        quakeCols.clear()
        // Bad guys are tough, and later worlds a little tougher, but never too much
        // (it used to keep climbing and got too hard).
        val tough = minOf(1.4f + 0.1f * (index / WORLD_SIZE), 1.7f)
        for (f in foes) if (!f.boss) f.toughen(tough)
        thinCrowds()
        if (fromCheckpoint) {
            respawnX = keepX
            respawnY = keepY
            things.filterIsInstance<Checkpoint>().forEach { if (it.x <= keepX + 1) it.reached = true }
            keepHouse?.let { house = House(it.x, it.bottom, it.type); things += house!! }
            keepBed?.let { bed = Bed(it.x, it.bottom); things += bed!! }
        }
        // Pick up right where SAVE GAME was pressed.
        val spotSaved = restoreHome && storage.load(key("spotLevel")) == index + 1
        if (spotSaved) {
            respawnX = storage.load(key("spotX")).toFloat()
            respawnY = storage.load(key("spotY")).toFloat()
            things.filterIsInstance<Checkpoint>().forEach { if (it.x <= respawnX) it.reached = true }
        }
        // In a boss rush, a boss behind the checkpoint was already beaten: don't bring it back.
        foes.removeAll { it.boss && it.x + it.w < respawnX }
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

    /** A button that went down since the last frame (even a tap too quick to still be held). */
    private fun edge(i: Int, now: Boolean): Boolean {
        val e = input.wasPressed(i) || (now && !prev[i])
        prev[i] = now
        return e
    }

    /** JUMP pressed a moment before landing still jumps, and so does JUMP just after running off a ledge. */
    private var jumpBufT = 0f
    private var coyoteT = 0f
    /** THROW / MINE pressed while still cooling down happens as soon as it's ready. */
    private var actionBufT = 0f
    /** Every jump goes at least a little way up, even from a very quick tap of the button. */
    private var jumpHoldT = 0f

    fun update(dt: Float) {
        time += dt
        stateT += dt
        garden.grow(dt)
        if (babyZapT > 0f) babyZapT -= dt
        val jumpE = edge(0, input.jump)
        val swapE = edge(1, input.swap)
        val craftE = edge(2, input.craft)
        val pauseE = edge(3, input.pause)
        if (edge(4, input.musicToggle)) musicOn = !musicOn
        val superE = edge(5, input.superMove)
        val actionE = edge(6, input.action)
        actionTap = actionE
        val tntE = edge(7, input.tnt)
        val poseidonE = edge(8, input.poseidon)
        val blackE = edge(11, input.blackFlower)
        downE = edge(9, input.down)
        longE = edge(10, input.longJump)
        val tapped = input.tap
        input.tap = false
        val num = input.numberKey
        input.numberKey = 0
        // Controller / keyboard menu moves. A finger on the screen never counts, because every
        // touch also lands on one of the (invisible in menus) game buttons.
        val pad = !input.touching && !(tapped && input.tapX >= 0f)
        nav.left = edge(12, input.left) && pad
        nav.right = edge(13, input.right) && pad
        nav.up = edge(14, input.up) && pad
        nav.down = downE && pad
        nav.a = jumpE && pad
        nav.b = actionE && pad
        nav.lb = tntE && pad
        nav.rb = longE && pad
        if (toastT > 0) toastT -= dt
        if (eraseT > 0) eraseT -= dt else eraseArmed = -1

        when (state) {
            State.TITLE -> when {
                stateT < 0.4f -> {}
                craftE || (tapped && input.tapX > viewW - 112f && input.tapY < 28f) -> openControllerSetup()
                tapped || num > 0 -> setState(State.SLOTS)
            }
            State.CONTROLS -> updateControls(tapped)
            State.SLOTS -> updateSlots(tapped, num)
            State.PICK -> updatePick(tapped, num)
            State.INTRO -> if (stateT > 3f || (tapped && stateT > 0.6f)) setState(State.PLAY)
            State.PLAY -> {
                if (pauseE) { paused = !paused; pauseCursor = 0 }
                // Controller on the pause screen: up/down picks a button, A presses it, B resumes.
                if (paused) {
                    if (nav.up) pauseCursor = (pauseCursor + 2) % 3
                    if (nav.down) pauseCursor = (pauseCursor + 1) % 3
                    if (nav.b) paused = false
                    if (nav.a) when (pauseCursor) {
                        0 -> { setState(State.BOOK); return }
                        1 -> saveHere()
                        else -> if (away == null) { saveAndExitToMap(); return } else toast("Finish the bonus room first!")
                    }
                }
                // The STICKER BOOK button on the pause screen...
                if (paused && tapped && input.tapY in 136f..162f && abs(input.tapX - viewW / 2) < 80f) { setState(State.BOOK); return }
                // ...SAVE GAME under it...
                if (paused && tapped && input.tapY in 168f..194f && abs(input.tapX - viewW / 2) < 80f) saveHere()
                // ...and SAVE & EXIT TO MAP at the bottom.
                if (paused && tapped && input.tapY in 200f..226f && abs(input.tapX - viewW / 2) < 80f) {
                    if (away == null) { saveAndExitToMap(); return } else toast("Finish the bonus room first!")
                }
                if (savedFlash > 0) savedFlash -= dt
                if (!paused) {
                    if (craftE) { setState(State.CRAFT); sfx(Sound.BUMP) } else {
                        if (tntE) throwTnt()
                        if (poseidonE) callPoseidon()
                        if (blackE) useBlackFlower()
                        play(dt, jumpE, swapE, superE, actionE)
                    }
                }
            }
            State.CRAFT -> {
                val rows = recipes().size
                if (nav.up) craftCursor = (craftCursor + rows - 1) % maxOf(1, rows)
                if (nav.down) craftCursor = (craftCursor + 1) % maxOf(1, rows)
                if (nav.left || nav.lb) { craftTab = (craftTab + TABS.size - 1) % TABS.size; craftCursor = 0 }
                if (nav.right || nav.rb) { craftTab = (craftTab + 1) % TABS.size; craftCursor = 0 }
                craftCursor = craftCursor.coerceIn(0, maxOf(0, recipes().size - 1))
                if (nav.a && stateT > 0.2f) craft(craftCursor)
                updateCraft(tapped, craftE || pauseE || nav.b, num)
            }
            State.TRADE -> {
                trader?.let { v ->
                    val n = tradesOf(v).size
                    if (nav.up) tradeCursor = (tradeCursor + n - 1) % maxOf(1, n)
                    if (nav.down) tradeCursor = (tradeCursor + 1) % maxOf(1, n)
                    if (nav.a && stateT > 0.2f && tradeCursor < n) doTrade(tradesOf(v)[tradeCursor])
                }
                updateTrade(tapped, pauseE || craftE || nav.b, num)
            }
            State.HOME -> { updateParticles(dt); jumpE3d = jumpE; updateHome(dt, tapped, pauseE || craftE, actionE) }
            State.PARK -> { updateParticles(dt); updatePark(dt, tapped, jumpE || actionE, pauseE || craftE) }
            State.SCENE -> updateScene(tapped, pauseE || nav.a || nav.b)
            State.MAP -> updateMap(tapped, pauseE, num)
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
            State.BOOK -> {
                val pages = (Stickers.all.size + STICKERS_PER_PAGE - 1) / STICKERS_PER_PAGE
                when {
                    nav.left -> stickerPage = (stickerPage + pages - 1) % pages
                    nav.right -> stickerPage = (stickerPage + 1) % pages
                    tapped && input.tapX >= 0f && input.tapY > VIEW_H - 24f && stateT > 0.2f ->
                        stickerPage = if (input.tapX < viewW / 2) (stickerPage + pages - 1) % pages else (stickerPage + 1) % pages
                    (tapped || pauseE || num > 0) && stateT > 0.3f -> setState(State.PLAY)
                }
            }
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
        if (away == null) {
            val before = clearedCount()
            cleared[levelIndex] = true
            // A new Pokemon joins every few levels beaten.
            NEW_MONS.firstOrNull { newMonNeeds(it) in (before + 1)..clearedCount() }?.let {
                toast("A wild ${it.label} joined your team! Switch at home.")
                sound.sayAs("A wild ${it.label} joined your team!", Voice.HERO)
            }
            unlocked = maxOf(unlocked, minOf(Levels.all.size, levelIndex + 2))
        }
        stickerPicked = -1
        // Normal stickers come up most; silver, gold, rainbow and midnight ones get rarer and rarer.
        val missing = Stickers.all.indices.filter { !hasSticker(it) }.map { it to Stickers.all[it].style + Random.nextFloat() * 2.5f }.sortedBy { it.second }.map { it.first }
        val pool = if (missing.size >= 3) missing else (missing + Stickers.all.indices.shuffled()).distinct()
        // Beating a boss always offers that boss's shiny sticker.
        val bossChar = level.def.rows.firstNotNullOfOrNull { r -> r.firstOrNull { it in BOSS_CHARS } }
        val bossSticker = bossChar?.let { Stickers.forBoss(it) }?.takeIf { it >= 0 && !hasSticker(it) }
        stickerChoices = (listOfNotNull(bossSticker) + pool).distinct().take(3).shuffled()
        // The Pokemon learns from every level (and lots from a boss), then it's rested and ready to play.
        if (away == null) gainXp(if (bossChar != null) 12 else 4)
        monEnergy = 5
        setState(State.STICKER)
        sfx(Sound.ONEUP)
    }

    fun pickSticker(n: Int) {
        if (stickerPicked >= 0 || n !in stickerChoices.indices) return
        val i = stickerChoices[n]
        stickerPicked = i
        stickerOwned[i] = true
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
            if (nav.left) { stickerCursor = (stickerCursor + 2) % 3; sfx(Sound.KICK) }
            if (nav.right) { stickerCursor = (stickerCursor + 1) % 3; sfx(Sound.KICK) }
            if (nav.a && stateT > 0.5f) pickSticker(stickerCursor.coerceAtMost(stickerChoices.lastIndex))
            if (tapped && stateT > 0.5f) {
                for (n in stickerChoices.indices) {
                    val x = stickerCardX(n)
                    if (input.tapX in x..(x + stickerCardW()) && input.tapY in 56f..196f) pickSticker(n)
                }
            }
            return
        }
        if (stateT > 2.5f || ((tapped || num > 0) && stateT > 0.8f)) {
            if (afterSticker >= 0) {
                save()
                // Every couple of levels: a short funny scene before the map.
                if (levelIndex % 2 == 1 && funnyScenes) startScene(afterSticker) else goToMap(afterSticker)
            } else {
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
        if (padConnected && stickerPicked < 0) padFrame(gfx, stickerCardX(stickerCursor) - 4, 56f, w + 8, 142f)
        val hint = if (stickerPicked < 0) "Tap the one you want!" else "Added to your sticker book! Tap to keep going."
        gfx.shadowText(hint, viewW / 2, 228f, 9f, white, 1)
    }

    private fun renderBook(gfx: Gfx) {
        gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF2A1A10.toInt())
        gfx.rect(6f, 6f, viewW - 12f, VIEW_H - 12f, 0xFFE8D8B0.toInt())
        gfx.shadowText("STICKER BOOK  $stickerCount / ${Stickers.all.size}", viewW / 2, 22f, 11f, 0xFFFFD21F.toInt(), 1)
        val cols = 8
        val cw = min(46f, (viewW - 24f) / cols)
        val gx = viewW / 2 - cols * cw / 2
        val pages = (Stickers.all.size + STICKERS_PER_PAGE - 1) / STICKERS_PER_PAGE
        for (k in 0 until STICKERS_PER_PAGE) {
            val i = stickerPage * STICKERS_PER_PAGE + k
            if (i >= Stickers.all.size) break
            Stickers.drawCard(this, gfx, i, gx + (k % cols) * cw + 2, 27f + (k / cols) * 40f, cw - 4, 37f, hasSticker(i))
        }
        gfx.shadowText("< PREV      PAGE ${stickerPage + 1}/$pages      NEXT >", viewW / 2, VIEW_H - 9f, 8f, 0xFFFFD21F.toInt(), 1)
        gfx.text("Tap to close", viewW - 12f, 22f, 6f, 0xFF4A3018.toInt(), 2)
    }

    // ---------------------------------------------------------------- Title menus

    private fun slotCardW() = min(130f, (viewW - 40f) / SLOTS)
    private fun slotCardX(i: Int) = viewW / 2 - SLOTS * (slotCardW() + 8f) / 2 + i * (slotCardW() + 8f) + 4f

    private fun updateSlots(tapped: Boolean, num: Int) {
        if (num in 1..SLOTS) { chooseSlot(num - 1); return }
        if (nav.left) { slotCursor = (slotCursor + SLOTS - 1) % SLOTS; sfx(Sound.KICK) }
        if (nav.right) { slotCursor = (slotCursor + 1) % SLOTS; sfx(Sound.KICK) }
        if (nav.a && stateT > 0.3f) { chooseSlot(slotCursor); return }
        if (nav.b && stateT > 0.3f) { setState(State.TITLE); return }
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

    private val pickRows = listOf(0..4, 5..9)
    /** Lugia is a legendary: it joins after the 3rd level is beaten (switch to it at home). */
    fun monUnlocked(m: Mon) = when {
        m == Mon.LUGIA -> isCleared(2)
        m.root in NEW_MONS && clearedCount() < newMonNeeds(m.root) -> false
        // Evolutions: the line has to reach the right level first.
        else -> monLevel(m.root) >= m.evolveLevel
    }

    /** Levels to beat before one of the 20 newer Pokemon joins. */
    fun newMonNeeds(m: Mon): Int {
        val i = NEW_MONS.indexOf(m)
        return if (i < 18) NEW_MON_EVERY * (i + 1) else 18 * NEW_MON_EVERY + 2 * (i - 17)
    }

    /** The Pokemon you can pick when starting a new game (evolutions come later, by levelling up). */
    private val STARTERS = Mon.entries.take(10)
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
        if (num in 1..STARTERS.size) { choose(STARTERS[num - 1]); return }
        val n = STARTERS.size
        var i = pickChoice.ordinal
        if (nav.left) i = (i + n - 1) % n
        if (nav.right) i = (i + 1) % n
        if (nav.down && i < 5) i = minOf(i + 5, n - 1)
        if (nav.up && i >= 5) i -= 5
        if (i != pickChoice.ordinal) { pickChoice = STARTERS[i]; sound.sayAs(pickChoice.label, Voice.MON); sfx(Sound.KICK) }
        if (nav.a && stateT > 0.4f) { choose(pickChoice); return }
        if (nav.b && stateT > 0.3f) { setState(State.SLOTS); return }
        if (!tapped || stateT < 0.3f) return
        val x = input.tapX
        val y = input.tapY
        if (x > goButtonX() - 30 && y > 206f) { choose(pickChoice); return }
        for (i in STARTERS.indices) {
            val cx = pickCardX(i)
            val cy = pickCardY(i)
            if (x in cx..(cx + pickCardW()) && y in cy..(cy + pickCardH)) {
                val m = STARTERS[i]
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
        if (!monUnlocked(m)) {
            sfx(Sound.BUMP)
            toast("LUGIA is a legendary! Beat level 3, then switch to it at home.")
            return
        }
        sound.sayAs("${m.label}! I choose you!", Voice.HERO)
        newGame(slot, m)
    }

    // ---------------------------------------------------------------- Playing

    private fun play(dt: Float, jumpE: Boolean, swapE: Boolean, superE: Boolean, actionE: Boolean) {
        // Early presses are remembered for a moment (counted down every frame, even in a minecart).
        if (jumpE) jumpBufT = 0.13f else if (jumpBufT > 0f) jumpBufT -= dt
        if (actionE) actionBufT = 0.2f else if (actionBufT > 0f) actionBufT -= dt
        if (jumpHoldT > 0f) jumpHoldT -= dt
        if (invuln > 0) invuln -= dt
        if (bannerT > 0) bannerT -= dt
        if (shake > 0) shake -= dt
        if (superFlash > 0) superFlash -= dt
        if (starT > 0) {
            starT -= dt
            invuln = max(invuln, 0.1f)
        }
        superCharge = min(1f, superCharge + dt / superTime())
        if (swapE) { if (capture != null) releaseCapture() else swap() }
        if (superE) useSuper()

        if (floodT > 0) { floodT -= dt; updateWave(dt) }
        if (quakeLevel && quakesOn && away == null && camLock == null && boss == null) updateQuake(dt)
        if (away != null) {
            val before = bonusT
            bonusT -= dt
            if (before > 5f && bonusT <= 5f) toast("The sun is going down! 5 seconds!")
            if (bonusT <= 0f) { exitBonus(); return }
        }
        if (tanookiT > 0) tanookiT -= dt
        if (metalT > 0) { metalT -= dt; if (metalT <= 0f) toast("Metal Rocco wore off!") }
        if (crazyFireT > 0) crazyFireT -= dt
        if (megaT > 0) { megaT -= dt; if (megaT <= 0f) { toast("Back to normal size."); sfx(Sound.KICK) } }
        if (propT > 0) { propT -= dt; if (propT <= 0f) toast("The propeller wore off!") }
        if (captureDashT > 0) captureDashT -= dt
        if (capture != null) { captureT -= dt; if (captureT <= 0f) releaseCapture() }
        if (crazyIceT > 0) crazyIceT -= dt
        if (bowCooldown > 0) bowCooldown -= dt
        if (wingT > 0) {
            wingT -= dt
            if (wingT in 0f..3f && (wingT * 4).toInt() != ((wingT + dt) * 4).toInt()) sfx(Sound.BUMP)
        }
        if (miniT > 0) {
            miniT -= dt
            // Grow back once there's room above Rocco.
            if (miniT <= 0 && !level.solidAt(rocco.cx, rocco.bottom - 24f)) endMini() else if (miniT <= 0) miniT = 0.1f
        }
        // On the Moon everyone floats: way higher jumps.
        val grav = if (wonder) 0.75f else if (level.def.place == Place.MOON) 0.6f else 1f
        val cart = riding
        val cn = inCannon
        val tail = grabbing
        if (tail != null && (tail.mode != 1 || tail.dying)) grabbing = null
        // Busy in a cannon or holding a tail: the usual buttons do something else.
        val busy = cn != null || grabbing != null
        if (cn != null) {
            // Inside a cannon: wait for the barrel to point where you want, then JUMP to blast off.
            val l = leader
            l.x = cn.cx - l.w / 2; l.y = cn.y - l.h + 8
            l.vx = 0f; l.vy = 0f; l.onGround = true
            if ((jumpE || actionE) && cn.loadT > 0.3f) fireCannon(cn)
        } else if (grabbing != null) {
            // Swinging King Krag round and round: stay put (he does the moving).
            val l = leader
            l.vx = 0f
            l.vy = minOf(l.vy + GRAVITY * grav * dt, MAX_FALL)
            moveHero(l, dt)
        } else if (cart != null) {
            // Riding a minecart: the leader sits in the cart, JUMP hops out.
            val l = leader
            l.x = cart.cx - l.w / 2
            l.y = cart.y - l.h + 6
            l.vx = cart.vx; l.vy = 0f; l.onGround = true
            if (jumpE) { leaveCart(cart, false); l.vy = l.jumpV; sfx(Sound.JUMP) }
        } else updateLeader(leader, dt, jumpE, grav)
        for ((i, f) in followers.withIndex()) updateFollower(f, dt, grav, i)
        val l = leader
        if (actionE && riding == null && cannonFlyT <= 0f && !busy) {
            val v = things.firstOrNull { it is Villager && abs(it.cx - l.cx) < 22f && abs(it.bottom - l.bottom) < 20f } as Villager?
            val bc = things.firstOrNull { it is BlastCannon && abs(it.cx - l.cx) < 22f && abs(it.bottom - l.bottom) < 20f } as BlastCannon?
            val mc = things.firstOrNull { it is Minecart && it.rider == null && abs(it.cx - l.cx) < 24f && abs(it.bottom - l.bottom) < 20f } as Minecart?
            when {
                bed?.overlaps(l) == true && l.onGround -> { sleep(); return }
                house?.atDoor(l) == true && l.onGround -> { enterHome(); return }
                v != null -> { startTrade(v); return }
                mc != null -> { rideCart(mc); return }
                bc != null && l.onGround -> { enterCannon(bc); return }
            }
        }
        // Hop onto Dash: land on his back, or press ACTION next to him.
        if (l === rocco && dino == null && riding == null && !rocco.mini && !busy) {
            val d = things.firstOrNull { it is Dino && it.rider == null && it.mountCooldown <= 0f && it.scaredT <= 0f } as Dino?
            if (d != null) {
                val landed = l.vy > 0f && l.overlaps(d) && l.bottom < d.cy
                val asked = actionE && abs(d.cx - l.cx) < 22f && abs(d.bottom - l.bottom) < 16f
                if (landed || asked) { mount(d); return }
            }
        }
        dino?.let { d -> d.x = rocco.cx - d.w / 2; d.y = rocco.bottom - d.h }
        if ((input.action || actionBufT > 0f) && l.cooldown <= 0f && riding == null && !busy && inCannon == null && foes.none { it is TailKrag && it.mode == 2 }) {
            actionBufT = 0f
            action(l)
        }
        // Blocky's bow uses the button Rocco uses for his long jump.
        if (longE && l.who == Who.BLOCKY && bow != Bow.NONE && bowCooldown <= 0f && riding == null && !busy) shootBow(l)
        updateWater(dt)
        updateSpikes(dt)
        updateDog(dt)
        updateSpotty(dt)
        for (h in team) {
            if (h.onGround) h.combo = 0
            h.anim += dt
            if (h.swing > 0) h.swing -= dt
            if (h.squash > 0) h.squash -= dt
            if (h.cooldown > 0) h.cooldown -= dt
            if (h.jumpCooldown > 0) h.jumpCooldown -= dt
        }

        things += pending.filter { it !is Foe }
        foes += pending.filterIsInstance<Foe>()
        pending.clear()
        for (f in foes) {
            if (!f.active && f.x < camX + viewW + 48 && f.x + f.w > camX - 48) f.active = true
            if (f.frozenT > 0f) { f.frozenT -= dt; continue }
            if (f.active) f.update(this, dt)
        }
        for (t in things) if (t.active || (t.x < camX + viewW + 32)) { t.active = true; t.update(this, dt) }
        foes.removeAll { it.removed }
        things.removeAll { it.removed }
        updateParticles(dt)

        interact()
        portalHit?.let { p ->
            portalHit = null
            if (p.exit) exitBonus() else enterPortal(p)
            return
        }
        updatePartnerZap(dt)
        updateFrog()
        updateBoss(dt)
        updateCamera(dt)
        if (state == State.PLAY) checkFalls()
    }

    private fun swap() = switchTo(nextWho())

    /** Puts [who] in charge. Rocco and Blocky trade places, since only one of them is out at a time. */
    internal fun switchTo(who: Who) {
        if (who == leaderWho) return
        if (dino != null) dismount()
        val old = leader
        old.crouching = false; old.slamming = false; old.longJumping = false; old.sprinting = false
        leaderWho = who
        val h = leader
        if (h.who != Who.MON && h.who != heroOut) {
            // The new one pops out right where the other one was.
            val was = if (heroOut == Who.BLOCKY) blocky else rocco
            heroOut = h.who
            if (was === rocco && rocco.mini) { miniT = 0f; setMini(false) }
            teleport(h, was)
            h.facing = was.facing
            h.vy = was.vy
        } else if (hypot(h.cx - old.cx, h.cy - old.cy) > 140f || h.y > level.h * TILE) teleport(h, old)
        h.vx = 0f
        sfx(Sound.KICK)
        particles += FloatText(h.cx, h.y - 6, h.label + "!", h.color)
    }

    /** Rocco hops on Dash's back: he gets taller (Rocco sits on top). */
    private fun mount(d: Dino) {
        val r = rocco
        if (r.mini || dino != null) return
        dino = d
        d.rider = r
        val b = r.bottom
        r.x = d.cx - 7f
        r.w = 14f
        r.h = 34f
        r.y = b - r.h
        r.vy = min(r.vy, 0f)
        sfx(Sound.POWERUP)
        say(r.cx, r.y - 8, "YAHOO!", 1.2f)
        toast("Riding DASH! TONGUE eats bad guys. JUMP in the air = hop off.")
    }

    /** Rocco gets off Dash. [jumpOff]: a big leap up; [scared]: Dash got hit and runs away. */
    fun dismount(jumpOff: Boolean = false, scared: Boolean = false) {
        val d = dino ?: return
        val r = rocco
        dino = null
        d.rider = null
        d.x = r.cx - d.w / 2
        d.y = r.bottom - d.h
        d.vx = 0f
        d.vy = if (jumpOff) 120f else 0f
        d.mountCooldown = 1f
        d.facing = r.facing
        val b = r.bottom
        r.w = 12f
        r.h = 23f
        r.y = (if (jumpOff) b - 12f else b) - r.h
        r.x = r.cx - 6f
        if (jumpOff) { r.vy = r.jumpV * 1.05f; r.doubleJumped = true; sfx(Sound.JUMP) }
        if (scared) { d.scaredT = 2.5f; d.mountCooldown = 2.5f; say(d.cx, d.y - 8, "Eek!", 1f) }
    }

    /** Dash's long tongue gulps the closest bad guy in front of him. */
    private fun dinoTongue(h: Hero) {
        h.cooldown = 0.45f
        h.swing = 0.25f
        val reach = Body(if (h.facing > 0) h.cx else h.cx - 52f, h.bottom - 30f, 52f, 22f)
        val f = foes.filter { !it.dying && it.active && it.overlaps(reach) }.minByOrNull { abs(it.cx - h.cx) }
        particles += Tongue(h.cx + h.facing * 10f, h.bottom - 26f, h.facing, if (f != null) abs(f.cx - h.cx) else 44f)
        if (f != null) {
            f.hit(this, if (f.boss) 3 else 99)
            if (!f.boss) particles += FloatText(f.cx, f.y, "GULP!", 0xFF7FE07F.toInt())
            sfx(Sound.CHOMP)
        } else sfx(Sound.KICK)
    }

    /** Who SWAP goes to next: Rocco, then Blocky, then the Pokemon, then back to Rocco. */
    private fun nextWho() = when (leaderWho) { Who.ROCCO -> Who.BLOCKY; Who.BLOCKY -> Who.MON; Who.MON -> Who.ROCCO }

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
        if (cannonFlyT > 0f && h === leader) { cannonFlight(h, dt, grav); return }
        val dir = (if (input.right) 1 else 0) - (if (input.left) 1 else 0)
        if (dir != 0) h.facing = dir
        if (h.onGround) coyoteT = 0.1f else if (coyoteT > 0f) coyoteT -= dt
        if (inWater(h) && !(metal && h === rocco)) {
            // Swimming: slow sinking, and every tap of JUMP is a swim stroke.
            // (Metal Rocco is too heavy to swim: he walks along the bottom instead.)
            h.vx = approach(h.vx, dir * h.speed * 0.85f, 500f * dt)
            if (jumpE) { h.vy = -170f; jumpBufT = 0f; sfx(Sound.SPLASH) }
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
        val d = dino
        if (rocco && d != null) {
            // Riding Dash: faster, higher jumps, a flutter when you hold JUMP, and JUMP in mid-air leaps off.
            if (h.onGround) flutterT = 0.9f
            if (jumpE && !h.onGround) { dismount(jumpOff = true); return }
            val want = dir * h.speed * (if (sprint) 1.6f else 1.3f)
            h.vx = approach(h.vx, want, (if (h.onGround) 900f else 600f) * dt)
            if (jumpE && h.onGround) { h.vy = h.jumpV * 1.12f; jumpBufT = 0f; jumpHoldT = 0.12f; sfx(Sound.JUMP) }
            if (input.jump && !h.onGround && h.vy > 0f && flutterT > 0f) {
                flutterT -= dt
                h.vy = approach(h.vy, -40f, 1800f * dt)
            }
            if (!input.jump && jumpHoldT <= 0f && h.vy < -150f) h.vy = -150f
            h.vy = min(h.vy + GRAVITY * grav * dt, MAX_FALL)
            moveHero(h, dt)
            d.facing = h.facing
            if (h.onGround) { safeX = h.x; safeY = h.bottom }
            return
        }
        // Rocco's moves: squat, butt slam and long jump.
        if (rocco) {
            if (h.onGround) h.doubleJumped = false
            if (h.flipT > 0) h.flipT -= dt
            if (h.onGround) propUsed = false
            // Propeller: JUMP in mid-air spins Rocco way up high.
            if (propT > 0f && jumpE && !h.onGround && coyoteT <= 0f && !propUsed && !h.slamming) {
                jumpBufT = 0f
                propUsed = true
                h.doubleJumped = true
                h.vy = -520f
                h.flipT = 0.6f
                sfx(Sound.JUMP)
                for (i in 0 until 8) particles += Sparkle(h.cx, h.y, (i - 3.5f) * 30f, 60f, 0xFFFFD21F.toInt())
                moveHero(h, dt)
                return
            }
            // Double jump: press JUMP again in mid-air.
            if (jumpE && !h.onGround && coyoteT <= 0f && !h.doubleJumped && !h.slamming) {
                jumpBufT = 0f
                h.doubleJumped = true
                h.longJumping = false
                h.vy = h.jumpV * (if (h.mini) 0.75f else 0.9f)
                h.flipT = 0.35f
                jumpHoldT = 0.1f
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
                jumpBufT = 0f
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
        val top = h.speed * (if (sprint) 1.5f else 1f) * (if (rocco) (captureKind?.speed ?: 1f) * (if (megaT > 0f) 1.2f else 1f) else 1f) *
            (if (rocco && captureDashT > 0f) 2.2f else 1f)
        val want = if (h.crouching) 0f else dir * top
        h.vx = approach(h.vx, want, (if (h.onGround) 800f else 520f) * dt)
        // Blue Shell: sprinting on the ground turns Rocco into a spinning shell.
        h.shellSpin = rocco && power == Power.SHELL && sprint && h.onGround && !h.mini && abs(h.vx) > 120f
        // Still safe for a moment after a spin (jumping out of it, bumping into a wall).
        if (h.shellSpin) shellSafeT = 0.5f else if (rocco && shellSafeT > 0f) shellSafeT -= dt
        if (jumpBufT > 0f && (h.onGround || (coyoteT > 0f && h.vy >= 0f))) {
            jumpBufT = 0f
            coyoteT = 0f
            jumpHoldT = 0.12f
            h.vy = h.jumpV * (if (sprint) 1.06f else 1f) * (if (metal && h === this.rocco && inWater(h)) 0.8f else 1f) *
                (if (rocco) captureKind?.jump ?: 1f else 1f)
            sfx(Sound.JUMP)
        }
        if (!input.jump && jumpHoldT <= 0f && h.vy < -130f) h.vy = -130f
        // Mini Rocco is light as a feather: he floats way up high.
        val g2 = if (h.mini) grav * 0.5f else grav
        h.vy = min(h.vy + GRAVITY * g2 * dt, if (h.mini) 160f else MAX_FALL)
        if (h.shellSpin) {
            // Smash through bricks in front.
            val tx = tileOf(if (h.facing > 0) h.x + h.w + 2 else h.x - 2)
            for (ty in tileOf(h.y + 2)..tileOf(h.bottom - 2)) if (level[tx, ty] == Tile.BRICK) bump(tx, ty)
        }
        if (rocco && !h.mini) {
            // Wing Cap (or a captured Flapper): hold JUMP to fly up into the sky.
            if ((wingT > 0 || captureKind == CaptureKind.FLAPPER) && input.jump && !jumpE) h.vy = approach(h.vy, -170f, 1400f * dt)
            // Propeller (or a captured ghost): hold JUMP to float down.
            else if ((propT > 0 || captureKind == CaptureKind.GHOST) && input.jump && h.vy > 50f) h.vy = 50f
            // Tanooki: hold JUMP to float down slowly, wagging the tail.
            else if (tanookiT > 0 && input.jump && h.vy > 45f) h.vy = 45f
            if (h.y < 34f) { h.y = 34f; h.vy = max(h.vy, 0f) }
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
                // A butt slam mines the block under Rocco (but not plain ground).
                Tile.GROUND, Tile.DIRT -> {}
                else -> if (level.toughness(tx, ty) > 0) mine(tx, ty, pickTier, rocco = true)
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
        val wasGround = h.onGround
        val fall = h.vy
        if (level.moveY(h, h.vy * dt) == -1) bump(level.bumpX, level.bumpY, h)
        // Landing: a little squash and a puff of dust.
        if (!wasGround && h.onGround && fall > 180f) {
            h.squash = 0.14f
            particles += Dust(h.cx - 6, h.bottom, -1)
            particles += Dust(h.cx + 6, h.bottom, 1)
        }
        // Running fast kicks up dust now and then.
        if (h.onGround && abs(h.vx) > 150f && h === leader) {
            h.dustT -= dt
            if (h.dustT <= 0f) { h.dustT = 0.15f; particles += Dust(h.cx - (if (h.vx > 0) 6 else -6), h.bottom, if (h.vx > 0) -1 else 1) }
        }
    }

    private fun bump(tx: Int, ty: Int, by: Hero? = null) {
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
                // Most of the time it's just a couple of coins: the good stuff is rare.
                when (Random.nextInt(16)) {
                    14 -> spawn(MegaItem(px, above))
                    15 -> spawn(PropellerItem(px, above))
                    8 -> spawn(if (Plushies.all.any { !hasPlushie(it) && !it.boss }) PlushieItem(px, above) else GiftBox(px, above))
                    in 9..13 -> repeat(2) { i -> spawn(Coin(px + (i - 1) * 6f, ty * TILE - 16f, popping = true)) }
                    0 -> spawn(StarItem(px, above))
                    5 -> spawn(MiniItem(px, above))
                    6 -> spawn(LeafItem(px, above))
                    7 -> spawn(WingCapItem(px, above))
                    1 -> spawn(HeartItem(px, above))
                    2 -> repeat(5) { i -> spawn(Coin(px + (i - 2) * 6f, ty * TILE - 16f, popping = true)) }
                    4 -> spawn(MetalItem(px, above))
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
            // Rocco can bonk blocks from below to mine them.
            else -> if (by?.who == Who.ROCCO && level.toughness(tx, ty) > 0) mine(tx, ty, pickTier, rocco = true) else sfx(Sound.BUMP)
        }
        for (f in foes) {
            if (!f.dying && !f.boss && abs(f.bottom - ty * TILE) < 2 && f.cx > tx * TILE - 4 && f.cx < tx * TILE + TILE + 4) f.hit(this, 99)
        }
    }

    // ======================================================================== Mining, throwing and Pokemon moves

    private fun action(h: Hero) {
        if (h.who == Who.ROCCO && dino != null) { dinoTongue(h); return }
        if (h.who == Who.ROCCO && capture != null) { captureAction(h); return }
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
                wearSword()
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
        wearPick()
    }

    /** Every sword hit wears the sword down a little. When it's used up, it breaks! */
    private fun wearSword() {
        if (sword == Sword.NONE) return
        swordHp--
        if (swordHp == 10) toast("Your ${sword.label} is wearing out!")
        if (swordHp <= 0) {
            toast("Your ${sword.label} BROKE! Craft a new one.")
            particles += FloatText(leader.cx, leader.y - 8, "SWORD BROKE!", 0xFFFF6060.toInt())
            sfx(Sound.BREAK)
            sword = Sword.NONE
            swordsOwned = 0
            swordHp = 0
        }
    }

    /** Every block mined wears the pickaxe. When it breaks you go back down one pickaxe. */
    private fun wearPick() {
        if (pickTier <= 1) return
        pickHp--
        if (pickHp == 15) toast("Your ${TeamArt.pickName(pickTier)} PICKAXE is wearing out!")
        if (pickHp <= 0) {
            val old = TeamArt.pickName(pickTier)
            pickTier--
            pickHp = pickDurability(pickTier)
            toast("Your $old PICKAXE BROKE! Back to the ${TeamArt.pickName(pickTier)} one.")
            particles += FloatText(leader.cx, leader.y - 8, "PICKAXE BROKE!", 0xFFFF6060.toInt())
            sfx(Sound.BREAK)
        }
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

    /**
     * Hits block ([tx], [ty]) with [strength] pickaxe points. Rocco's head-bonks and butt slams
     * mine too ([rocco] = true) with the same strength as Blocky's pickaxe, so a block takes the
     * same number of hits from either of them. Rocco just can't break plain ground.
     */
    fun mine(tx: Int, ty: Int, strength: Int = pickTier, rocco: Boolean = false) {
        val t = level[tx, ty]
        if (rocco && (t == Tile.GROUND || t == Tile.DIRT)) { sfx(Sound.BUMP); return }
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
        val dmg = (level.cracks[key] ?: 0) + strength
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

    private fun shootBow(h: Hero) {
        bowCooldown = bow.cooldown
        h.swing = 0.15f
        val sx = if (h.facing > 0) h.x + h.w else h.x - 12
        val sy = h.y + 9
        if (bow == Bow.ENCHANTED) for (k in -1..1) spawn(HeroArrow(sx, sy, h.facing, bow, k * 70f))
        else spawn(HeroArrow(sx, sy, h.facing, bow))
        sfx(Sound.KICK)
    }

    // ---------------------------------------------------------------- Cappy, Mega and Propeller

    /** Cappy caught a bad guy: Rocco turns into it for 20 seconds. */
    fun capture(f: Foe) {
        val kind = CaptureKind.of(f) ?: return
        if (capture != null) releaseCapture()
        if (riding == null && dino == null) switchTo(Who.ROCCO)
        foes.remove(f)
        capture = f
        captureKind = kind
        captureT = 12f
        f.vx = 0f
        sfx(Sound.PORTAL)
        sound.sayAs("Capture! I'm a ${kind.label.lowercase()}!", Voice.HERO)
        toast("CAPTURED a ${kind.label}! ${kind.how}  (SWAP lets go)")
        for (i in 0 until 8) particles += Sparkle(rocco.cx, rocco.cy, (i - 3.5f) * 40f, -60f, 0xFFE52521.toInt())
    }

    /** Rocco pops back out (the bad guy runs off in a puff of smoke). */
    fun releaseCapture() {
        if (capture == null) return
        capture = null
        captureKind = null
        captureDashT = 0f
        puff(rocco.cx, rocco.cy)
        sfx(Sound.KICK)
    }

    private fun captureAction(h: Hero) {
        h.cooldown = 0.4f
        when (captureKind) {
            CaptureKind.SHELLBACK -> { captureDashT = 0.8f; sfx(Sound.KICK) }
            CaptureKind.BOMB -> {
                // KA-BOOM: every bad guy nearby, then Rocco pops out.
                shake = 0.5f
                sfx(Sound.BREAK)
                for (i in 0 until 14) particles += Sparkle(h.cx, h.cy, kotlin.math.cos(i * 0.45f) * 150f, sin(i * 0.45f) * 150f, 0xFFFF8A2A.toInt())
                for (f in foes) if (!f.dying && f.active && hypot(f.cx - h.cx, f.cy - h.cy) < 90f) f.hit(this, if (f.boss) 6 else 99)
                releaseCapture()
            }
            CaptureKind.ENDERMAN -> {
                val nx = h.x + h.facing * 80f
                if (!level.solidAt(nx + h.w / 2, h.cy)) { puff(h.cx, h.cy); h.x = nx; sfx(Sound.PORTAL) } else sfx(Sound.BUMP)
            }
            CaptureKind.WITCH -> {
                val tx = h.cx + h.facing * 90f
                spawn(HeroPotion(h.cx - 5, h.y - 2, tx))
                sfx(Sound.FIRE)
            }
            else -> throwPowerPlain(h)
        }
    }

    /** Captured bad guys without a special move still punch. */
    private fun throwPowerPlain(h: Hero) {
        val reach = Body(if (h.facing > 0) h.x + h.w - 2 else h.x - 10, h.y + 4, 12f, 12f)
        for (f in foes) if (!f.dying && f.active && f.overlaps(reach)) f.hit(this, 2)
        sfx(Sound.KICK)
    }

    /** Draws Rocco as the bad guy he captured, wearing his cap. */
    fun drawCapture(gfx: Gfx, h: Hero, ox: Float) {
        val f = capture ?: return
        f.x = h.cx - f.w / 2
        f.y = h.bottom - f.h
        f.vx = h.facing * 30f
        if (captureDashT > 0f) gfx.oval(f.x + ox - 4, f.y - 4, f.w + 8, f.h + 8, 0x66FFFFFF)
        f.draw(this, gfx, ox)
        CappyHat.drawCap(gfx, h.cx - 6 + ox, f.y - 6, 0f, false)
    }

    fun startMega() {
        if (dino != null) dismount()
        if (riding == null) switchTo(Who.ROCCO)
        if (rocco.mini) { miniT = 0f; setMini(false) }
        megaT = 12f
        sfx(Sound.POWERUP)
        sound.sayAs("Mega Rocco! Stomp stomp!", Voice.HERO)
        toast("MEGA MUSHROOM! Giant Rocco squashes everything!")
        shake = 0.6f
    }

    fun startPropeller() {
        if (riding == null) switchTo(Who.ROCCO)
        propT = 20f
        sfx(Sound.POWERUP)
        sound.sayAs("Propeller! Wheee!", Voice.HERO)
        toast("PROPELLER! Press JUMP in the air to spin up high, hold JUMP to float down.")
    }

    /** A crazy mushroom: giant fireballs (or ice balls) for 20 seconds. */
    fun startCrazy(fire: Boolean) {
        if (riding == null && dino == null) switchTo(Who.ROCCO)
        // A little weaker than it was: 12 seconds, two balls at a time.
        if (fire) { crazyFireT = 12f; crazyIceT = 0f } else { crazyIceT = 12f; crazyFireT = 0f }
        sfx(Sound.POWERUP)
        sound.sayAs(if (fire) "Crazy fire! Wahoo!" else "Crazy ice! Brrr!", Voice.HERO)
        toast(if (fire) "CRAZY FIRE MUSHROOM! Giant bouncing fireballs for 12 seconds!" else "CRAZY ICE MUSHROOM! Giant ice balls that freeze bad guys, for 12 seconds!")
    }

    private fun throwCrazy(h: Hero) {
        val fire = crazyFireT > 0f
        h.cooldown = 0.35f
        h.cooldown = 0.5f
        if (things.count { it is CrazyBall } >= 4) return
        val sx = if (h.facing > 0) h.x + h.w else h.x - 16
        for (k in 0 until 2) spawn(CrazyBall(sx, h.y + 4, h.facing, fire, -20f - k * 140f))
        sfx(if (fire) Sound.BOSS_FIRE else Sound.FIRE)
    }

    private fun throwPower(h: Hero) {
        h.cooldown = 0.3f
        h.swing = 0.15f
        if ((crazyFireT > 0f || crazyIceT > 0f) && !h.mini) { throwCrazy(h); return }
        if (power == Power.CAPPY) {
            if (things.none { it is CappyHat }) { spawn(CappyHat(if (h.facing > 0) h.x + h.w else h.x - 12, h.y + 4, h.facing, h)); sfx(Sound.KICK) }
            return
        }
        if (tanookiT > 0 && !h.mini) { tailSpin(h); return }
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
            // Toned down: one shell at a time (two at Lv5), and a slower throw.
            if (things.count { it is HomingShell } >= (if (lvl >= 5) 2 else 1)) return
            h.cooldown = 0.7f
            spawn(HomingShell(sx, h.y + 2, h.facing, dmg))
            if (lvl >= 5) spawn(HomingShell(sx, h.y - 10, h.facing, dmg))
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
        if (mon.base == Mon.PUNCHO) {
            // A strong punch right in front.
            val reach = Body(if (h.facing > 0) h.x + h.w else h.x - 22, h.y, 22f, h.h)
            for (f in foes) if (!f.dying && f.active && f.overlaps(reach)) f.hit(this, 3)
            particles += FloatText(h.cx + h.facing * 16, h.y, "POW", 0xFFFFD21F.toInt())
            sfx(Sound.KICK)
            return
        }
        if (mon.base == Mon.BLOOM) {
            // Vine whip: a short-range lash that hits harder.
            particles += Vine(h.cx, h.y + 8, h.facing)
            val reach = Body(if (h.facing > 0) h.x + h.w else h.x - 34, h.y - 2, 34f, h.h + 4)
            for (f in foes) if (!f.dying && f.active && f.overlaps(reach)) f.hit(this, 2)
            sfx(Sound.KICK)
            return
        }
        spawn(MonShot(sx, h.y + 4, h.facing, mon))
        sfx(if (mon.base == Mon.SPARKY) Sound.ZAP else Sound.FIRE)
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
        when (mon.base) {
            Mon.SPARKY -> {
                particles += SuperFx(mx, my, mon, dir, 0f)
                for (f in foes.filter(::onScreen)) {
                    particles += Bolt(f.cx, -10f, f.cx, f.cy, 0xFFFFF27A.toInt(), 3f)
                    f.hit(this, superDmg(6))
                }
                sfx(Sound.ZAP)
            }
            Mon.BLAZE -> {
                val w = 190f
                particles += SuperFx(mx, my, mon, dir, w)
                val zone = Body(if (dir > 0) mx else mx - w, my - 30f, w, 60f)
                for (f in foes.filter { onScreen(it) && it.overlaps(zone) }) f.hit(this, superDmg(9))
                sfx(Sound.BOSS_FIRE)
            }
            Mon.HYDRO -> {
                val w = viewW * 0.6f
                particles += SuperFx(mx, my, mon, dir, w)
                val zone = Body(mx - w, my - 26f, w * 2, 52f)
                for (f in foes.filter { onScreen(it) && it.overlaps(zone) }) {
                    f.hit(this, superDmg(8))
                    f.vx += if (f.cx > mx) 120f else -120f
                }
                sfx(Sound.BREAK)
            }
            Mon.BLOOM -> {
                val w = viewW
                particles += SuperFx(mx, my, mon, dir, w)
                val zone = Body(if (dir > 0) mx else mx - w, my - 20f, w, 40f)
                for (f in foes.filter { onScreen(it) && it.overlaps(zone) }) f.hit(this, superDmg(10))
                sfx(Sound.BOSS_HIT)
            }
            Mon.EMBER -> {
                particles += SuperFx(mx, my, mon, dir, 0f)
                for (f in foes.filter { onScreen(it) && hypot(it.cx - mx, it.cy - my) < 140f }) f.hit(this, superDmg(9))
                sfx(Sound.BOSS_FIRE)
            }
            Mon.SPLASHY -> {
                val w = 230f
                particles += SuperFx(mx, my, mon, dir, w)
                val zone = Body(if (dir > 0) mx else mx - w, my - 30f, w, 60f)
                for (f in foes.filter { onScreen(it) && it.overlaps(zone) }) f.hit(this, superDmg(8))
                sfx(Sound.SPLASH)
            }
            Mon.MYSTIC -> {
                particles += SuperFx(mx, my, mon, dir, 0f)
                for (f in foes.filter(::onScreen)) f.hit(this, superDmg(6))
                hearts = min(MAX_HEARTS, hearts + 2)
                particles += FloatText(mx, my - 20, "+2 HEARTS", 0xFFFF9AD0.toInt())
                sfx(Sound.ONEUP)
            }
            Mon.PUNCHO -> {
                val w = 110f
                particles += SuperFx(mx, my, mon, dir, w)
                val zone = Body(if (dir > 0) mx else mx - w, my - 30f, w, 60f)
                for (f in foes.filter { onScreen(it) && it.overlaps(zone) }) f.hit(this, superDmg(16))
                sfx(Sound.BOSS_HIT)
            }
            Mon.SPOOKY -> {
                particles += SuperFx(mx, my, mon, dir, 0f)
                for (f in foes.filter(::onScreen)) f.hit(this, superDmg(6))
                sfx(Sound.ZAP)
            }
            Mon.LUGIA -> {
                val w = viewW
                particles += SuperFx(mx, my, mon, dir, w)
                val zone = Body(if (dir > 0) mx else mx - w, my - 50f, w, 100f)
                for (f in foes.filter { onScreen(it) && it.overlaps(zone) }) {
                    f.hit(this, superDmg(8))
                    f.vx += dir * 160f
                }
                if (mon.swimmer) air = AIR_MAX
                sfx(Sound.BOSS_FIRE)
            }
            else -> {}
        }
    }

    // ---------------------------------------------------------------- Pokemon levels

    /** Experience needed to go from level [lv] to the next one. */
    fun xpToNext(lv: Int) = 10 + 8 * lv

    /** A Pokemon's level (1 to [MON_MAX_LEVEL]) from its experience points. */
    fun monLevel(m: Mon = mon): Int {
        var xp = monXp[m.root.ordinal]
        var lv = 1
        while (lv < MON_MAX_LEVEL && xp >= xpToNext(lv)) { xp -= xpToNext(lv); lv++ }
        return lv
    }

    /** How far the Pokemon is to its next level, from 0 to 1. */
    fun monXpFraction(m: Mon = mon): Float {
        var xp = monXp[m.root.ordinal]
        var lv = 1
        while (lv < MON_MAX_LEVEL && xp >= xpToNext(lv)) { xp -= xpToNext(lv); lv++ }
        return if (lv >= MON_MAX_LEVEL) 1f else xp / xpToNext(lv).toFloat()
    }

    /** The Pokemon learns something: at a new level it gets a little stronger. */
    fun gainXp(n: Int, m: Mon = mon) {
        if (monLevel(m) >= MON_MAX_LEVEL) return
        val before = monLevel(m)
        monXp[m.root.ordinal] += n
        val after = monLevel(m)
        if (after > before) {
            sfx(Sound.ONEUP)
            val evo = m.line.firstOrNull { it.evolveLevel in (before + 1)..after && it != Mon.LUGIA && it.stage > 1 }
            if (evo != null) {
                toast("${m.label} can EVOLVE into ${evo.label}! Go to the Pokemon mat at home.")
                sound.sayAs("What? ${m.label} can evolve!", Voice.HERO)
            } else {
                toast("${m.label} grew to LEVEL $after!" + if (after == MON_MAX_LEVEL) " (That's the top level!)" else "")
                sound.sayAs("${m.label} grew to level $after!", Voice.MON)
            }
        }
    }

    /** SUPER moves charge a bit faster as the Pokemon grows: 25 seconds at level 1, about 20 at level 10. */
    fun superTime() = SUPER_TIME * (1f - 0.024f * (monLevel() - 1))

    /** SUPER moves hit 4% harder per level (at most about a third harder at level 10). */
    fun superDmg(n: Int) = n + (n * (monLevel() - 1) * 4 + 50) / 100 + (mon.stage - 1) * (n + 4) / 8

    /** The Pokemon's normal attack: one more damage from level 6. */
    val monAttack get() = if (monLevel() >= 6) 2 else 1

    // ======================================================================== Crafting

    enum class Kind { FLOWER, PICK, HEART, APPLE, TNT, TRAP, STAR, MINI, HOUSE, BED, SWORD, ARMOR, BLACK, SHIELD, BOW }

    class Recipe(
        val title: String, val note: String, val costs: List<Pair<Ore, Int>>, val coinCost: Int,
        val button: String, val enabled: Boolean, val icon: Power?, val kind: Kind, val sword: Sword = Sword.NONE,
        val armor: Armor = Armor.NONE, val shield: Shield = Shield.NONE, val house: HouseType = HouseType.COTTAGE,
        val bow: Bow = Bow.NONE,
    )

    private fun has(costs: List<Pair<Ore, Int>>, coinCost: Int) =
        costs.all { (o, n) -> inventory[o.ordinal] >= n } && coins >= coinCost

    /** The recipes on one tab of the crafting table. */
    fun recipes(tab: Int = craftTab): List<Recipe> = when (TABS.getOrNull(tab)) {
        "FLOWERS" -> listOf(Power.BOOM, Power.FIRE, Power.ICE, Power.GRASS, Power.THUNDER, Power.CRYSTAL, Power.SHELL, Power.CAPPY, Power.GOLD).map { p ->
            val lvl = flowerLevel[p.ordinal]
            val ore = p.ore!!
            val note = when (p) {
                Power.BOOM -> "Iron: weakest. Comes back!"
                Power.FIRE -> "Gold: strong bouncing fire"
                Power.ICE -> "Diamond: the strongest!"
                Power.GRASS -> "Emerald: GREEN ARMOR + leaves"
                Power.THUNDER -> "Copper: zaps jump to more bad guys"
                Power.SHELL -> "Diamond: homing shells + sprint to spin!"
                Power.CAPPY -> if (clearedCount() < CAPPY_LEVELS) "LOCKED: beat $CAPPY_LEVELS levels (${clearedCount()} so far)" else "Throw Cappy: BECOME a bad guy and use its power!"
                Power.GOLD -> "Golden fireballs: every hit makes coins!"
                else -> "Amethyst: three crystal shards"
            }
            // Cappy is special: it costs a lot more, and only after beating 15 levels.
            val costs = if (p == Power.CAPPY) listOf(Ore.SAPPHIRE to 8, Ore.DIAMOND to 4, Ore.AMETHYST to 3) else listOf(ore to COST)
            val cappyLocked = p == Power.CAPPY && lvl == 0 && clearedCount() < CAPPY_LEVELS
            val (button, enabled) = when {
                cappyLocked -> "LOCKED" to false
                lvl == 0 -> "MAKE" to has(costs, 0)
                power != p -> "EQUIP" to true
                lvl >= MAX_FLOWER_LV -> "MAX" to false
                else -> "Lv${lvl + 1}" to has(costs, 0)
            }
            val title = if (lvl > 0) "${p.label} Lv$lvl" else p.label
            Recipe(title, note, if (button == "EQUIP" || button == "MAX") emptyList() else costs, 0, button, enabled, p, Kind.FLOWER)
        }
        "SUPPLIES" -> {
            val next = pickTier + 1
            val pickOre = when (next) { 2 -> Ore.IRON; 3 -> Ore.GOLD; 4 -> Ore.DIAMOND; else -> Ore.AMETHYST }
            val pick = if (pickTier >= 5) {
                val c = listOf(Ore.AMETHYST to COST)
                val worn = pickHp < pickDurability(5)
                Recipe("AMETHYST PICKAXE", "The best! ${pickHp}/${pickDurability(5)} left. Make again to fix it", c, 0, if (worn) "FIX" else "MAX", worn && has(c, 0), null, Kind.PICK)
            } else {
                val c = listOf(pickOre to COST)
                Recipe("${TeamArt.pickName(next)} PICKAXE", "Mines faster. Lasts ${pickDurability(next)} blocks", c, 0, "MAKE", has(c, 0), null, Kind.PICK)
            }
            val heart = listOf(Ore.STONE to 5)
            val apple = listOf(Ore.GOLD to 4)
            val tntCost = listOf(Ore.STONE to 8, Ore.IRON to 3)
            val trapCost = listOf(Ore.IRON to 4)
            val star = listOf(Ore.AMETHYST to 3, Ore.DIAMOND to 2)
            val miniCost = listOf(Ore.EMERALD to 2, Ore.STONE to 4)
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
        "WEAPONS" -> Sword.entries.filter { it != Sword.NONE }.map { sw ->
            val costs = listOf(sw.ore!! to sw.cost)
            val (button, enabled) = when {
                sword == sw -> (if (swordHp < sw.durability) "FIX" else "READY") to (swordHp < sw.durability && has(costs, 0))
                sword != Sword.NONE && sw.damage < sword.damage -> "BETTER" to false
                else -> "MAKE" to has(costs, 0)
            }
            val note = if (sword == sw) "Holding it: $swordHp/${sw.durability} swings left" else "${sw.note}. Lasts ${sw.durability} hits"
            Recipe(sw.label, note, costs, 0, button, enabled, null, Kind.SWORD, sw)
        } + run {
            // Rare and pricey: the one-time Black Flower.
            val c = listOf(Ore.DIAMOND to 3, Ore.AMETHYST to 3, Ore.EMERALD to 3)
            listOf(Recipe("BLACK FLOWER", "One use: gets EVERY bad guy near you! Have $blackFlowers", c, 20, "MAKE", has(c, 20), null, Kind.BLACK))
        }
        "BOWS" -> Bow.entries.filter { it != Bow.NONE }.map { b ->
            val (button, enabled) = when {
                bow == b -> "READY" to false
                bow != Bow.NONE && b.ordinal < bow.ordinal -> "BETTER" to false
                else -> "MAKE" to has(b.costs, b.coins)
            }
            val note = if (bow == b) "Blocky's SHOOT button fires it" else "${b.note}. Blocky only"
            Recipe(b.label, note, b.costs, b.coins, button, enabled, null, Kind.BOW, bow = b)
        }
        "ARMOR" -> Armor.entries.filter { it != Armor.NONE }.map { a ->
            val c = listOf(a.ore!! to a.cost)
            val wearing = armorType == a
            val note = if (wearing) "Wearing it: $armorHp/${a.durability} left. Make again to fix it" else "Takes ${a.durability} hits, then breaks"
            val weaker = armorType != Armor.NONE && a.ordinal < armorType.ordinal
            Recipe(a.label, note, c, 25, if (wearing) "FIX" else if (weaker) "BETTER" else "MAKE", !weaker && has(c, 25) && !(wearing && armorHp >= a.durability), null, Kind.ARMOR, armor = a)
        }
        "SHIELDS" -> Shield.entries.filter { it != Shield.NONE }.map { sh ->
            val holding = shieldType == sh
            val note = if (holding) "Holding it: $shieldHp/${sh.durability} left. Make again to fix it" else "Blocks ${sh.durability} hits from the front"
            val weaker = shieldType != Shield.NONE && sh.ordinal < shieldType.ordinal
            Recipe(sh.label, note, sh.costs, 10, if (holding) "FIX" else if (weaker) "BETTER" else "MAKE", !weaker && has(sh.costs, 10) && !(holding && shieldHp >= sh.durability), null, Kind.SHIELD, shield = sh)
        }
        else -> emptyList()
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
                    r.button == "LOCKED" -> "Cappy unlocks after beating $CAPPY_LEVELS levels."
                    r.button == "READY" -> "Blocky is already holding that sword!"
                    r.button == "BETTER" -> "You already have a better one! Making this would waste your stuff."
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
            Kind.PICK -> {
                if (pickTier < 5) pickTier++
                pickHp = pickDurability(pickTier)
                toast("${TeamArt.pickName(pickTier)} PICKAXE! It can mine $pickHp blocks.")
            }
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
            Kind.ARMOR -> {
                armorType = r.armor
                armorHp = r.armor.durability
                toast("${r.armor.label}! It wears down a little every time you're hit.")
                color = r.armor.color
            }
            Kind.SHIELD -> {
                shieldType = r.shield
                shieldHp = r.shield.durability
                toast("${r.shield.label}! Face the bad guys and it blocks their hits.")
                color = r.shield.color
            }
            Kind.BLACK -> {
                blackFlowers++
                toast("A BLACK FLOWER! Tap its button to use it (just once).")
                color = 0xFFC060FF.toInt()
            }
            Kind.BOW -> {
                bow = r.bow
                toast("${r.bow.label}! Switch to Blocky and tap SHOOT.")
                color = r.bow.color
            }
            Kind.SWORD -> {
                swordsOwned = 1 shl r.sword.ordinal
                sword = r.sword
                swordHp = r.sword.durability
                toast(if (r.button == "FIX") "${r.sword.label} fixed!" else "${r.sword.label}! Blocky swings it at bad guys.")
                color = r.sword.blade
            }
            Kind.HOUSE -> {
                val moving = house != null && houseType == r.house
                val hadBed = bed != null
                house?.let { it.removed = true }
                bed?.let { it.removed = true }
                bed = null
                houseType = r.house
                val hx = leader.cx - 36
                house = House(hx, leader.bottom, houseType).also { things += it }
                if (moving && hadBed) bed = Bed(hx + 34, leader.bottom).also { things += it }
                toast(if (moving) "Your ${houseType.label} moved here!" else "Your new ${houseType.label}! Walk to the door and press ENTER.")
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

    /** The Black Flower: pulls in every bad guy near the team and gets rid of them. One use! */
    private fun useBlackFlower() {
        if (blackFlowers <= 0) return
        blackFlowers--
        val l = leader
        shake = 0.6f
        sfx(Sound.BOSS_FIRE)
        sound.sayAs("Black flower!", Voice.HERO)
        bannerTitle = "BLACK FLOWER!"
        bannerSub = ""
        bannerT = 1.5f
        particles += BlackBloom(l.cx, l.cy)
        for (f in foes) {
            if (!f.active || f.dying || hypot(f.cx - l.cx, f.cy - l.cy) > BLACK_RANGE) continue
            particles += Bolt(f.cx, f.cy, l.cx, l.cy, 0xFFC060FF.toInt())
            if (f.boss) f.hit(this, 12) else f.hit(this, 999)
        }
    }

    private fun callPoseidon() {
        if (coins < POSEIDON_COST) {
            toast("Poseidon needs $POSEIDON_COST coins. You have $coins.")
            return
        }
        coins -= POSEIDON_COST
        // A giant wave rolls in from behind the team and crashes forward across the screen.
        floodT = WAVE_TIME
        waveDir = leader.facing
        waveS = if (waveDir > 0) -80f else viewW + 80f
        waveHit.clear()
        shake = 1f
        sfx(Sound.WAVE)
        // Poseidon speaks Greek: "I am Poseidon!"
        sound.sayAs(POSEIDON_LINE, Voice.GOD)
        bannerTitle = "POSEIDON!"
        bannerSub = "A GIANT WAVE crashes over the bad guys!"
        bannerT = 2.5f
    }

    /** The wave's front sweeps across the screen, hitting and washing away every bad guy it passes. */
    private fun updateWave(dt: Float) {
        waveS += waveDir * 380f * dt
        val waveX = camX + waveS
        if (floodT > 0.4f && Random.nextFloat() < dt * 30) {
            particles += Sparkle(waveX + waveDir * Random.nextFloat() * 20f, 60f + Random.nextFloat() * 120f, waveDir * (60f + Random.nextFloat() * 80f), -60f - Random.nextFloat() * 60f, 0xFFDDF4FF.toInt())
        }
        for (f in foes) {
            if (!f.active || f.dying || f in waveHit) continue
            if (f.x + f.w < camX - 40 || f.x > camX + viewW + 40) continue
            val passed = if (waveDir > 0) f.cx < waveX else f.cx > waveX
            if (!passed) continue
            waveHit += f
            f.hit(this, if (f.boss) 6 else 99)
            if (!f.boss) { f.vx = waveDir * 220f; f.vy = -240f }
            repeat(4) { particles += Sparkle(f.cx, f.cy, waveDir * (40f + it * 30f), -80f - it * 20f, 0xFFBFE8FF.toInt()) }
            shake = max(shake, 0.25f)
        }
    }

    private fun updateWater(dt: Float) {
        if (level.def.shoreX < 0) return
        val l = leader
        if (metal && l === rocco) { air = AIR_MAX }
        else if (inWater(l)) {
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
        for (k in level.vents) {
            val tx = k % level.w
            if (tx !in first..last) continue
            val ty = k / level.w
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

    // ---------------------------------------------------------------- Spotty and the funny scenes

    fun sayAs(text: String, voice: Voice) = sound.sayAs(text, voice)

    /** Counts down to Spotty showing up (negative: not in this level). */
    private var spottyT = -1f

    /** Spotty pops up in some levels (about 4 in 10), a little while after the start. */
    private fun planSpotty(index: Int) {
        spottyT = if (Random.nextFloat() < 0.4f && !Levels.all[index].isBossLevel) 8f + Random.nextFloat() * 20f else -1f
    }

    private fun updateSpotty(dt: Float) {
        if (spottyT < 0f || boss != null || level.def.shoreX >= 0) return
        spottyT -= dt
        if (spottyT > 0f) return
        val l = leader
        if (!l.onGround) { spottyT = 1f; return }
        spottyT = -1f
        spawn(Spotty(camX + viewW + 20f, l.bottom, Spotty.LINES.random()))
    }

    internal fun spottyForTest() { spottyT = 0.01f }

    /** The funny scenes can be turned off (for tests). */
    var funnyScenes = true
    var sceneKind = 0
        private set
    private var sceneNext = 0
    private var sceneCue = 0

    fun startScene(next: Int) {
        // They take turns, so each one is a surprise.
        val n = storage.load(key("scenes"))
        storage.save(key("scenes"), n + 1)
        sceneKind = n % Scenes.titles.size
        sceneNext = next
        sceneCue = 0
        setState(State.SCENE)
    }

    private fun updateScene(tapped: Boolean, skip: Boolean) {
        val cues = Scenes.cues(sceneKind, mon.label)
        while (sceneCue < cues.size && stateT >= cues[sceneCue].at) {
            val c = cues[sceneCue++]
            sound.sayAs(c.text, c.voice)
            c.sound?.let { sfx(it) }
        }
        if (stateT > Scenes.LENGTH || ((tapped || skip) && stateT > 0.6f)) goToMap(sceneNext)
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

    private fun enterCannon(c: BlastCannon) {
        inCannon = c
        c.loaded = true
        actionBufT = 0f
        sfx(Sound.KICK)
        toast("In the cannon! JUMP to BLAST OFF when it points where you want!")
    }

    private fun fireCannon(c: BlastCannon) {
        inCannon = null
        c.fired()
        val l = leader
        val a = c.aim
        l.x = c.muzzleX() - l.w / 2
        l.y = c.muzzleY() - l.h / 2
        l.vx = kotlin.math.cos(a) * CANNON_SPEED
        l.vy = -kotlin.math.sin(a) * CANNON_SPEED
        l.onGround = false
        cannonFlyT = 3f
        jumpBufT = 0f
        shake = 0.25f
        sfx(Sound.BOSS_FIRE)
        say(l.cx, l.y - 12, "WAHOOOO!", 1.2f)
    }

    /** Flying out of a cannon: smash through bad guys, and land wherever you land. */
    private fun cannonFlight(h: Hero, dt: Float, grav: Float) {
        cannonFlyT -= dt
        h.vy = min(h.vy + GRAVITY * grav * 0.8f * dt, MAX_FALL)
        val before = h.x
        moveHero(h, dt)
        invuln = max(invuln, 0.2f)
        for (f in foes) if (f.active && !f.dying && f !is TailKrag && f.overlaps(h)) f.hit(this, if (f.boss) 3 else 99)
        if ((time * 30).toInt() % 2 == 0) particles += Sparkle(h.cx, h.cy, -h.vx * 0.1f, 20f, 0xFFFFE14D.toInt())
        // Bumped into a wall, or landed: back to normal.
        if (abs(h.x - before) < abs(h.vx * dt) * 0.3f) h.vx = 0f
        if ((h.onGround && h.vy >= 0f) || h.vx == 0f) { cannonFlyT = 0f; h.vx *= 0.3f }
    }

    /** Which way LEFT/RIGHT are held (-1, 0 or 1). */
    fun heldDir() = (if (input.right) 1 else 0) - (if (input.left) 1 else 0)
    internal fun thingsForBosses(): List<Thing> = things
    fun toastFromGame(s: String) = toast(s)

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
        if (!tapped || stateT < 0.2f || input.tapX < 0f) return
        val px = panelX()
        val pw = panelW()
        val x = input.tapX
        val y = input.tapY
        if (hypot(x - (px + pw - 14), y - (panelTop + 10)) < 14f || x < px || x > px + pw || y < panelTop) { endTrade(); return }
        val i = ((y - rowsTop) / rowH).toInt()
        if (y >= rowsTop && i in trades.indices && confirmTap("trade:$i", "This trade", "make")) doTrade(trades[i])
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

    /** Super Leaf: Tanooki Rocco for 25 seconds (tail spin + slow float). */
    fun startTanooki() {
        if (riding == null) switchTo(Who.ROCCO)
        tanookiT = 25f
        sfx(Sound.POWERUP)
        toast("TANOOKI ROCCO! ACTION = tail spin, hold JUMP to float.")
    }

    /** Wing Cap: Rocco can fly for 15 seconds. */
    fun startWingCap() {
        if (riding == null) switchTo(Who.ROCCO)
        wingT = 15f
        sfx(Sound.POWERUP)
        sound.sayAs("Wing cap!", Voice.HERO)
        toast("WING CAP! Hold JUMP to fly!")
    }

    /** Tanooki tail spin: whacks bad guys and breaks bricks on both sides. */
    private fun tailSpin(h: Hero) {
        h.swing = 0.25f
        h.cooldown = 0.3f
        sfx(Sound.KICK)
        val zone = Body(h.x - 20f, h.y + 6f, h.w + 40f, h.h - 4f)
        for (f in foes) if (f.active && !f.dying && f.overlaps(zone)) f.hit(this, 3)
        for (tx in intArrayOf(tileOf(h.x - 6), tileOf(h.x + h.w + 6))) {
            for (ty in tileOf(h.y + 8)..tileOf(h.bottom - 2)) if (level[tx, ty] == Tile.BRICK) bump(tx, ty)
        }
        for (i in 0 until 4) particles += Puff(h.cx + (i - 1.5f) * 9f, h.bottom - 6)
    }

    /** Mini Mushroom: Rocco shrinks for 25 seconds. */
    fun startMini() {
        if (dino != null) dismount()
        if (riding == null) switchTo(Who.ROCCO)
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

    /** The Metal Cap: 20 seconds of shiny, unstoppable, heavy Metal Rocco. */
    fun startMetal() {
        if (dino != null) dismount()
        if (riding == null) switchTo(Who.ROCCO)
        if (rocco.mini) { miniT = 0f; setMini(false) }
        metalT = 20f
        sfx(Sound.POWERUP)
        sound.sayAs("Metal Rocco!", Voice.HERO)
        toast("METAL ROCCO! Nothing can hurt you, and you walk on the sea floor!")
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
        val o = pool.random()
        inventory[o.ordinal]++
        particles += FloatText(x, y, "+1 ${o.label}", o.color)
        inventory[Ore.STONE.ordinal] += 2
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
        if (!tapped || stateT < 0.15f || state != State.CRAFT || input.tapX < 0f) return
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
        if (y >= rowsTop && i in recipes().indices && confirmTap("craft:$craftTab:$i", recipes()[i].title, "make")) craft(i)
    }

    /**
     * Menus don't buy on the first tap: the first tap picks something (it gets a yellow frame), and tapping
     * the same thing again buys or makes it. Returns true on that second tap.
     */
    private var pendingPick: String? = null

    private fun confirmTap(key: String, name: String, verb: String): Boolean {
        if (pendingPick == key) { pendingPick = null; return true }
        pendingPick = key
        sfx(Sound.KICK)
        toast("$name: tap it again to $verb it!")
        return false
    }

    private fun picked(key: String) = pendingPick == key

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
                    if (h.who == Who.MON && mon.base == Mon.HYDRO && f.boss) f.hit(this, 2)
                    // A butt slam lands extra hard.
                    if (h.slamming && f.boss) f.hit(this, 3)
                    h.vy = if (h === leader && input.jump) -340f else -240f
                    break
                }
                if (h === leader) {
                    when {
                        starT > 0 -> f.hit(this, 3)
                        h === rocco && megaT > 0f -> { f.hit(this, if (f.boss) 3 else 99); shake = 0.15f }
                        h === rocco && captureDashT > 0f -> f.hit(this, if (f.boss) 2 else 6)
                        h === rocco && captureKind == CaptureKind.BONEHEAD && !f.boss -> {}
                        metal && h === rocco -> f.hit(this, 4)
                        h.shellSpin && !f.boss -> { f.hit(this, 1 + flowerLevel[Power.SHELL.ordinal] / 2); h.vx = -h.vx * 0.5f }
                        f.frozenT > 0f -> {}
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
                is Portal -> if (!t.used && t.overlaps(leader) && riding == null && portalHit == null) portalHit = t
                is Chest -> if (hit && !t.open) openChest(t)
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

    /**
     * Bad guys standing in a big bunch made some levels too wild: they get spread out, so no two
     * stand closer than a little gap (bosses always stay). From World 2 on, the gap is a bit bigger.
     */
    private fun thinCrowds() {
        val gap = if (levelIndex < WORLD_SIZE) 32f else 48f
        var lastX = -1000f
        val gone = HashSet<Foe>()
        for (f in foes.filter { !it.boss }.sortedBy { it.x }) {
            if (f.x - lastX < gap) gone += f else lastX = f.x
        }
        foes.removeAll(gone)
    }

    fun hurt(from: Body? = null) {
        if (invuln > 0 || starT > 0 || state != State.PLAY) return
        val l = leader
        if (metal && l === rocco) { invuln = 0.5f; sfx(Sound.BUMP); return }
        // Giant Rocco can't be hurt; a captured bad guy just pops Rocco back out.
        if (l === rocco && megaT > 0f) return
        if (l === rocco && capture != null) { releaseCapture(); invuln = 1.5f; sfx(Sound.HURT); return }
        if (l === rocco && captureDashT > 0f) return
        // Spinning in the blue shell: nothing can hurt Rocco.
        if (l === rocco && (rocco.shellSpin || shellSafeT > 0f)) return
        if (dino != null && l === rocco) {
            dismount(scared = true)
            invuln = HURT_SAFE_TIME
            l.vy = -200f
            sfx(Sound.HURT)
            toast("Ouch! You fell off Dash. Catch him!")
            return
        }
        if (from != null && shieldType != Shield.NONE && shieldHp > 0 && (from.cx - l.cx) * l.facing > -2f) {
            // The shield takes it! Blocked hits only push you back a little.
            shieldHp--
            invuln = 0.7f
            sfx(Sound.BUMP)
            particles += FloatText(l.cx, l.y - 4, "BLOCKED!", shieldType.color)
            for (i in 0 until 5) particles += Sparkle(l.cx + l.facing * 10f, l.cy, l.facing * (20f + i * 15f), -40f + i * 15f, 0xFFFFF2C8.toInt())
            l.vx = -l.facing * 70f
            if (from is Projectile) (from as Thing).removed = true
            if (shieldHp <= 0) {
                toast("Your ${shieldType.label} broke! Craft a new one.")
                shieldType = Shield.NONE
                sfx(Sound.BREAK)
            }
            return
        }
        if (leader === rocco && rocco.mini) {
            // Mini Rocco just pops back to normal size instead of losing a heart.
            endMini()
            invuln = HURT_SAFE_TIME
            sfx(Sound.HURT)
            return
        }
        when {
            armor > 0 -> {
                armor--
                particles += FloatText(leader.cx, leader.y - 4, "SHIELD!", 0xFF3CE070.toInt())
            }
            armorHp > 0 -> {
                // Armor takes the hit and wears down.
                armorHp--
                if (armorHp <= 0) {
                    particles += FloatText(leader.cx, leader.y - 4, "ARMOR BROKE!", 0xFFFF6060.toInt())
                    toast("Your ${armorType.label} broke! Craft a new one.")
                    armorType = Armor.NONE
                    sfx(Sound.BREAK)
                } else particles += FloatText(leader.cx, leader.y - 4, "ARMOR $armorHp", armorType.color)
            }
            else -> hearts--
        }
        invuln = HURT_SAFE_TIME
        sfx(Sound.HURT)
        leader.vy = -180f
        leader.vx = -leader.facing * 90f
        if (hearts <= 0) knockedOut()
    }

    /** Something went wrong: tidy up and go back to the title screen instead of closing the game. */
    fun recover() {
        paused = false
        away = null
        homeShop = false
        plushBook = false
        riding = null
        particles.clear()
        pending.clear()
        input.tap = false
        setState(State.TITLE)
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
    internal fun spawnForTest(t: Thing) = spawn(t)
    internal fun pickStickerScreenForTest() = offerStickers(levelIndex + 1)
    internal fun giveStickerForTest(i: Int) { stickerOwned[i] = true }
    internal fun openBookForTest() = setState(State.BOOK)
    internal fun bedForTest() = bed
    internal fun houseForTest() = house
    internal fun unlockForTest(n: Int) { unlocked = n }
    internal fun clearForTest(i: Int) { cleared[i] = true }
    internal fun noInvulnForTest() { invuln = 0f }
    internal fun openCraftForTest(tab: Int) { craftTab = tab; setState(State.CRAFT) }
    internal fun closeCraftForTest() = setState(State.PLAY)
    internal fun shopTabForTest(t: Int) { shopTab = t; homeShop = true }
    internal fun monButtonForTest(i: Int) = monButton(i)
    internal fun isQuakeLevelForTest(i: Int) = isQuakeLevel(i)
    internal fun quakeNowForTest() { quakeT = 0f }
    internal fun safeXForTest() = safeX
    internal fun safeYForTest() = safeY

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
            if (starT <= 0 && !metal) hearts--
            sfx(Sound.HURT)
            if (hearts <= 0) { knockedOut(); return }
            toast(if (level.inLava(h)) "Hot hot hot! Watch out for lava!" else "Whoops! Mind the gap!")
            placeTeam(safeX - leader.facing * 8f, safeY)
            invuln = HURT_SAFE_TIME
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
                (it !is KragJr || it.engaged) && (it !is KingKrag || it.awake) && it !is TailKrag &&
                (it !is MechaKrag || it.awake) && (it !is GalaxyKrag || it.awake) && (it !is RockStarKrag || it.awake) &&
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
        // Two crazy mushrooms pop out to help: fire and ice.
        // (Not for the tail spin: only spike bombs hurt that King Krag.)
        if (f !is TailKrag) {
            spawn(CrazyMushroom(leader.cx + 40f, leader.y - 20f, true))
            spawn(CrazyMushroom(leader.cx + 80f, leader.y - 20f, false))
        }
        val home = when (f) {
            is KragJr -> f.homeX
            is KingKrag -> f.homeX
            is Warden -> f.homeX
            is WitchBoss -> f.homeX
            is KingBooHoo -> f.homeX
            is RockStarKrag -> f.homeX
            is TailKrag -> f.homeX
            is MechaKrag -> f.homeX
            is GalaxyKrag -> f.homeX
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
            f is Warden && f.maxHp < 100 -> {
                bannerTitle = "THE WARDEN TWINS!"
                bannerSub = "TWO Wardens! Walk slowly and dodge the sonic booms!"
                sfx(Sound.BOSS_FIRE)
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
            f is KingBooHoo -> {
                bannerTitle = "KING BOO-HOO!"
                bannerSub = "He hides his face when you look at him: that's when you hit him!"
                sfx(Sound.BOSS_FIRE)
            }
            f is RockStarKrag -> {
                bannerTitle = "ROCK STAR KRAG!"
                bannerSub = "Dodge the music notes and JUMP over the power chord waves!"
                sfx(Sound.BOSS_FIRE)
            }
            f is MechaKrag -> {
                bannerTitle = "MECHA KRAG!"
                bannerSub = "A giant wind-up robot! Dodge the missiles and JUMP over the stomp waves!"
                sfx(Sound.BOSS_FIRE)
            }
            f is GalaxyKrag -> {
                bannerTitle = "GALAXY KRAG!"
                bannerSub = "The final battle! Dodge the stars, and jump on him when he SWOOPS down!"
                sfx(Sound.BOSS_FIRE)
            }
            f is TailKrag -> {
                bannerTitle = "KING KRAG: TAIL SPIN!"
                bannerSub = "Sneak BEHIND him, grab his TAIL (ACTION), spin him round and throw him into a SPIKE BOMB!"
                sfx(Sound.BOSS_FIRE)
            }
            f is WitchBoss -> {
                bannerTitle = "WITCH WANDA!"
                bannerSub = "Dodge the potions! Jump on her when she swoops down low!"
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
        inventory[Ore.DIAMOND.ordinal] += 1
        inventory[Ore.GOLD.ordinal] += 1
        givePlushie(
            when (f) {
                is KragJr -> Plushies.KRAG_JR
                is KingKrag, is TailKrag, is MechaKrag, is GalaxyKrag -> Plushies.KING_KRAG
                is Warden -> Plushies.WARDEN
                is EnderDragon -> Plushies.DRAGON
                else -> null
            },
        )
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
                val nextBoss = foes.firstOrNull { it.boss && it.hp > 0 && it !== b }
                if (nextBoss != null) {
                    // A boss rush: the next boss is waiting further on. Patch up the team and keep going.
                    boss = null
                    bossDone = false
                    hearts = MAX_HEARTS
                    toast(if (nextBoss is EnderDragon) "Hearts refilled! Now... THE ENDER DRAGON!" else "Hearts refilled! One more boss!")
                    sfx(Sound.ONEUP)
                } else if (levelIndex == Levels.all.lastIndex) {
                    offerStickers(-1)
                } else {
                    heartBonus()
                    val nextName = Levels.all[levelIndex + 1].name
                    clearNote = when {
                        (levelIndex + 1) % WORLD_SIZE == 0 -> "World ${levelIndex / WORLD_SIZE + 1} is beaten... WORLD ${levelIndex / WORLD_SIZE + 2} is waiting!"
                        levelIndex >= WORLD_SIZE -> "Boss beaten! Next up: $nextName!"
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
        // Too many floating numbers and sparkles slow the phone down: the oldest ones go first.
        if (particles.size > MAX_PARTICLES) particles.subList(0, particles.size - MAX_PARTICLES).clear()
        // Lots of floating numbers at once (big stomp combos) are slow to draw and hard to read: keep the newest.
        var texts = 0
        for (i in particles.indices.reversed()) if (particles[i] is FloatText && ++texts > MAX_FLOAT_TEXTS) particles[i].life = 0f
    }

    private fun updateMusic() {
        val m = when {
            !musicOn -> Music.NONE
            state == State.TITLE || state == State.SLOTS || state == State.PICK -> titleSong
            state == State.VICTORY -> Music.FROG
            state == State.STICKER -> Music.STAR
            state == State.HOME -> Music.COZY
            state == State.PARK -> Music.PARK
            state == State.SCENE -> Music.SILLY
            state == State.MAP -> Music.ADVENTURE
            state == State.OUCH || state == State.CLEAR || state == State.SLEEP -> Music.NONE
            starT > 0 -> Music.STAR
            // The Warden has his creepy heartbeat; other bosses take turns with the scary songs.
            boss is Warden && !bossDone -> Music.WARDEN
            boss is WitchBoss && !bossDone -> Music.WITCHY
            boss is KingBooHoo && !bossDone -> Music.SPOOKY
            boss is RockStarKrag && !bossDone -> Music.RACE
            boss is MechaKrag && !bossDone -> Music.ROBOT
            boss is GalaxyKrag && !bossDone -> Music.GALAXY
            wonder -> Music.WONDER
            boss != null && !bossDone -> Tunes.bossMusic(levelIndex, fighting = true)
            frog?.singing == true -> Music.FROG
            away != null -> level.def.place.music
            Levels.all[levelIndex].isBossLevel -> Tunes.bossMusic(levelIndex, fighting = false)
            else -> Tunes.levelMusic(levelIndex)
        }
        sound.music(m)
    }

    object Tunes {
        /** Each kind of place has three or four tunes; levels take turns so the music keeps changing. */
        private fun tunes(p: Place): List<Music> = when (p) {
            Place.HILLS -> listOf(Music.OVERWORLD, Music.MEADOW, Music.HEROIC, Music.FUNKY)
            Place.CAVE -> listOf(Music.UNDERGROUND, Music.CRYSTAL, Music.NIGHT, Music.FUNKY)
            Place.SKY -> listOf(Music.SKY, Music.HEROIC, Music.RACE, Music.MEADOW, Music.SPACE)
            Place.CASTLE -> listOf(Music.CASTLE, Music.DOOM, Music.HEROIC)
            Place.DESERT -> listOf(Music.DESERT, Music.FUNKY, Music.RACE, Music.OVERWORLD, Music.ADVENTURE)
            Place.SNOW -> listOf(Music.SNOW, Music.CRYSTAL, Music.SKY)
            Place.JUNGLE -> listOf(Music.JUNGLE, Music.TROPICAL, Music.MEADOW, Music.ADVENTURE)
            Place.VOLCANO -> listOf(Music.DESERT, Music.HEROIC, Music.CASTLE)
            Place.WATER -> listOf(Music.BEACH, Music.TROPICAL, Music.SKY)
            Place.NIGHT -> listOf(Music.NIGHT, Music.SPOOKY, Music.FUNKY, Music.UNDERGROUND, Music.SPACE)
            Place.DEEP -> listOf(Music.WARDEN, Music.CRYSTAL, Music.NIGHT)
            Place.END -> listOf(Music.SPACE, Music.NIGHT, Music.DOOM)
            Place.SUNSET -> listOf(Music.STAR, Music.TROPICAL, Music.ADVENTURE)
            Place.SHIP -> listOf(Music.SHANTY, Music.BEACH, Music.TROPICAL, Music.ADVENTURE)
            Place.RAINBOW -> listOf(Music.RACE, Music.STAR, Music.SPACE, Music.WONDER)
            Place.BLIZZARD -> listOf(Music.SNOW, Music.HEROIC, Music.CRYSTAL)
            Place.AURORA -> listOf(Music.DREAMY, Music.CRYSTAL, Music.SNOW)
            Place.CANDY -> listOf(Music.SILLY, Music.PARK, Music.FUNKY)
            Place.TOYS -> listOf(Music.TOYBOX, Music.PARADE, Music.SILLY, Music.FUNKY)
            Place.FUNGI -> listOf(Music.FUNGI, Music.DREAMY, Music.JUNGLE, Music.MEADOW)
            Place.MOON -> listOf(Music.MOON, Music.SPACE, Music.DREAMY, Music.CRYSTAL)
            Place.STATION -> listOf(Music.SPACE, Music.RACE, Music.HEROIC, Music.MOON)
        }

        /** Every level's tune, worked out once in order so it's never the same as the level before. */
        private val levelTunes: List<Music> by lazy {
            val out = ArrayList<Music>()
            for (i in Levels.all.indices) {
                val opts = tunes(Levels.all[i].place)
                val pick = opts[i % opts.size]
                val prev = if (i == 0 || Levels.all[i - 1].isBossLevel) null else out[i - 1]
                out += if (pick == prev) opts[(i + 1) % opts.size] else pick
            }
            out
        }

        /** The tune for a normal level, never the same as the level right before it. */
        fun levelMusic(i: Int): Music = levelTunes[i]

        /** How many boss levels come before each level (worked out once). */
        private val bossesBefore: IntArray by lazy {
            var n = 0
            IntArray(Levels.all.size) { i -> n.also { if (Levels.all[i].isBossLevel) n++ } }
        }

        /** Boss levels take turns between the scary songs. */
        fun bossMusic(i: Int, fighting: Boolean): Music {
            val n = bossesBefore[i]
            return if (fighting) listOf(Music.KRAG, Music.DOOM, Music.BOSS)[n % 3]
            else listOf(Music.DOOM, Music.CASTLE, Music.NIGHT)[n % 3]
        }
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
            State.HOME -> renderHome(gfx)
            State.PARK -> renderPark(gfx)
            State.SCENE -> Scenes.draw(this, gfx, sceneKind, stateT, viewW, VIEW_H, mon)
            State.MAP -> renderMap(gfx)
            State.CONTROLS -> renderControls(gfx)
            else -> {
                renderWorld(gfx)
                renderHud(gfx)
                when (state) {
                    State.PLAY -> {
                        val l = leader
                        val label = when {
                            riding != null -> "RIDING"
                            bed?.overlaps(l) == true -> "SLEEP"
                            house?.atDoor(l) == true -> "ENTER"
                            things.any { it is Villager && abs(it.cx - l.cx) < 22f && abs(it.bottom - l.bottom) < 20f } -> "TALK"
                            things.any { it is Minecart && abs(it.cx - l.cx) < 24f && abs(it.bottom - l.bottom) < 20f } -> "RIDE"
                            dino != null && l === rocco -> "TONGUE"
                            l === rocco && things.any { it is Dino && it.rider == null && abs(it.cx - l.cx) < 22f } -> "RIDE"
                            l.who == Who.MON && mon.base == Mon.PUNCHO -> "PUNCH"
                            l.who == Who.BLOCKY -> "MINE"
                            l.who == Who.MON -> if (mon.base == Mon.BLOOM) "VINE" else "ATTACK"
                            crazyFireT > 0f -> "FIRE!"
                            crazyIceT > 0f -> "ICE!"
                            power == Power.NONE -> "PUNCH"
                            else -> "THROW"
                        }
                        val next = allHeroes.first { it.who == nextWho() }
                        TeamControls.draw(
                            gfx, input, viewW, label, next.label, next.color, superCharge, mon.color, time,
                            tnt, coins >= POSEIDON_COST, (l.who == Who.ROCCO || (l.who == Who.BLOCKY && bow != Bow.NONE)) && riding == null,
                            l.sprinting, blackFlowers, if (l.who == Who.BLOCKY) "SHOOT" to bow.short else "LONG" to "JUMP",
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
                    gfx.shadowText("PAUSED", viewW / 2, 100f, 20f, white, 1)
                    gfx.shadowText("Tap the pause button to keep playing", viewW / 2, 120f, 9f, 0xFFDDDDDD.toInt(), 1)
                    gfx.rect(viewW / 2 - 80, 136f, 160f, 26f, 0xFF7A4A20.toInt())
                    gfx.rect(viewW / 2 - 78, 138f, 156f, 22f, 0xFFB98A52.toInt())
                    gfx.text("STICKER BOOK  $stickerCount/${Stickers.all.size}", viewW / 2, 153f, 9f, white, 1)
                    gfx.rect(viewW / 2 - 80, 168f, 160f, 26f, 0xFF1E5A1E.toInt())
                    gfx.rect(viewW / 2 - 78, 170f, 156f, 22f, if (savedFlash > 0) 0xFF3CE070.toInt() else 0xFF2EB84A.toInt())
                    gfx.text(if (savedFlash > 0) "SAVED! (FILE ${slot + 1})" else "SAVE GAME", viewW / 2, 185f, 9f, white, 1)
                    gfx.rect(viewW / 2 - 80, 200f, 160f, 26f, 0xFF1A2A6A.toInt())
                    gfx.rect(viewW / 2 - 78, 202f, 156f, 22f, 0xFF2E6BE6.toInt())
                    gfx.text("SAVE & EXIT TO MAP", viewW / 2, 217f, 9f, white, 1)
                    if (padConnected) {
                        val cy = 136f + pauseCursor * 32f
                        gfx.rect(viewW / 2 - 84, cy - 3, 4f, 32f, 0xFFFFD21F.toInt())
                        gfx.rect(viewW / 2 + 80, cy - 3, 4f, 32f, 0xFFFFD21F.toInt())
                    }
                    TeamControls.drawTop(gfx, viewW, musicOn, false, time)
                }
            }
        }
    }

    // Generous margin includes glows, flags, roofs and projectile trails beyond collision bounds.
    private fun visibleThing(t: Thing, cam: Float) = !t.removed && t.x + t.w >= cam - 128f && t.x <= cam + viewW + 128f

    private fun renderWorld(gfx: Gfx) {
        val sx = if (shake > 0) sin(time * 80) * 3f else 0f
        val cam = camX + sx
        renderBackground(gfx, cam)
        renderTiles(gfx, cam)
        if (quakeWarn >= 0f) drawQuake(gfx, -cam)
        val ox = -cam
        for (t in things) if (t is House && visibleThing(t, cam)) t.draw(this, gfx, ox)
        for (t in things) if (t !is Shot && t !is MonShot && t !is Frog && t !is House && visibleThing(t, cam)) t.draw(this, gfx, ox)
        for (f in foes) if (f.x + f.w > cam - 40 && f.x < cam + viewW + 40) {
            if (f.onGround && !f.dying) softShadow(gfx, f.cx + ox, f.bottom, f.w)
            f.draw(this, gfx, ox)
            if (f.frozenT > 0f) gfx.rect(f.x + ox - 2, f.y - 2, f.w + 4, f.h + 4, 0x887FD8FF.toInt())
            f.drawHealth(gfx, ox)
        }
        for (h in team) if (h.onGround) softShadow(gfx, h.cx + ox, h.bottom, if (h === rocco && megaT > 0f) h.w * 2 else h.w)
        for (h in followers) h.draw(this, gfx, ox)
        leader.draw(this, gfx, ox)
        if (shieldType != Shield.NONE) {
            val hh = if (heroOut == Who.BLOCKY) blocky else rocco
            if (!hh.mini) TeamArt.shieldIcon(gfx, hh.cx + ox + hh.facing * 6f - 4f, hh.y + hh.h * 0.3f, 11f, shieldType)
        }
        for (t in things) if ((t is Shot || t is MonShot || t is Frog) && visibleThing(t, cam)) t.draw(this, gfx, ox)
        for (p in particles) p.draw(gfx, ox)
        // Underwater tint up to the shore, with a wavy surface line.
        if (level.def.shoreX >= 0) {
            val sx0 = level.def.shoreX * TILE + ox
            if (sx0 > 0) {
                val wEnd = min(viewW, sx0)
                gfx.rect(0f, 32f, wEnd, VIEW_H - 32f, 0x442E8AC8)
                var wx = 0f
                while (wx < wEnd) {
                    gfx.rect(wx, 32f + sin(time * 3 + (wx - ox) * 0.08f) * 2f, 16f, 2f, 0xAABFE8FF.toInt())
                    wx += 16f
                }
            }
        }
        if (floodT > 0) drawWave(gfx, ox)
        val l = leader
        if (state == State.PLAY) {
            val ay = l.y - 10 + sin(time * 6) * 1.5f
            gfx.poly(floatArrayOf(l.cx + ox - 4, l.cx + ox + 4, l.cx + ox), floatArrayOf(ay, ay, ay + 5), white)
        }
    }

    /** Poseidon riding his giant curling wave as it crashes forward. */
    private fun drawWave(gfx: Gfx, ox: Float) {
        val front = waveS
        val d = waveDir.toFloat()
        val p = WAVE_TIME - floodT
        val rise = min(1f, p / 0.4f) * min(1f, floodT / 0.5f)
        val top = VIEW_H - rise * (VIEW_H - 46f)
        val back = if (d > 0) -20f else viewW + 20f
        val x0 = min(back, front)
        val x1 = max(back, front)
        // The big body of water behind the front, darker at the bottom.
        gfx.rect(x0, top + 14, x1 - x0, VIEW_H - top, 0xCC1E5AA8.toInt())
        gfx.rect(x0, top + 40, x1 - x0, VIEW_H - top, 0x661A3A7A)
        // Poseidon rides the wave, trident first.
        TeamArt.poseidon(gfx, front - d * 70f, top + 130f, time)
        // The curling crest leaning forward over the front.
        gfx.oval(front - 44, top - 4, 88f, 60f, 0xEE2E7AC8.toInt())
        gfx.oval(front - 28 + d * 10, top + 4, 56f, 38f, 0xFF4A9AE0.toInt())
        gfx.oval(front - 14 + d * 22, top + 18, 26f, 22f, 0xCC1E5AA8.toInt())
        // Foam along the top and spilling off the crest.
        var fx = x0
        while (fx < x1) {
            gfx.oval(fx - 8, top + 8 + sin(time * 9 + fx * 0.08f) * 3f, 22f, 12f, 0xEEEAF8FF.toInt())
            fx += 14f
        }
        for (i in 0 until 6) {
            val k = (time * 3 + i * 0.37f) % 1f
            gfx.oval(front + d * (10f + k * 40f) - 4, top + 6 + k * k * 120f, 8f - k * 4, 8f - k * 4, 0xDDFFFFFF.toInt())
        }
        gfx.oval(front - 30 + d * 18, top - 2, 46f, 14f, 0xFFFFFFFF.toInt())
    }

    // ======================================================================== World map

    /** Goes to the world map, with [sel] picked (a level, or -1 for home). */
    fun goToMap(sel: Int = mapSel) {
        if (dino != null) dismount()
        paused = false
        away = null
        homeShop = false
        plushBook = false
        mapSel = sel.coerceIn(-1, unlocked - 1)
        if (mapSel >= 0) mapWorld = mapSel / WORLD_SIZE
        setState(State.MAP)
    }

    /** Pause menu: save and go back to the map. */
    fun saveAndExitToMap() {
        save()
        toast("Game saved!")
        goToMap(levelIndex)
    }

    /** Where map spot [i] sits on screen (World page [mapWorld]). Home is spot -1. */
    fun mapSpot(i: Int): Pair<Float, Float> {
        if (i < 0) return 34f to 196f
        val k = i - mapWorld * WORLD_SIZE
        val row = k / 7
        val col = if (row % 2 == 0) k % 7 else 6 - k % 7
        val left = 82f
        val step = (viewW - left - 36f) / 6f
        return (left + col * step) to (70f + row * 58f)
    }

    private fun mapLevels() = (mapWorld * WORLD_SIZE) until minOf(Levels.all.size, (mapWorld + 1) * WORLD_SIZE)

    /** "1-3" style names for the map. */
    fun levelLabel(i: Int) = "${i / WORLD_SIZE + 1}-${i % WORLD_SIZE + 1}"

    /** Worlds you can visit so far. */
    private fun openWorlds() = (unlocked - 1) / WORLD_SIZE + 1

    /** The world button shows the next world you can go to (and wraps back to World 1). */
    private fun nextWorld() = (mapWorld + 1) % openWorlds()

    private fun mapButtons(): List<Pair<String, Float>> = buildList {
        add("TITLE" to (viewW - 58f))
        if (openWorlds() > 1) add("WORLD ${nextWorld() + 1}" to (viewW - 120f))
    }

    private fun updateMap(tapped: Boolean, pauseE: Boolean, num: Int) {
        // On a touch screen every tap also lands on an (invisible) game button, like JUMP or the
        // arrows. The map only listens to those from a controller or keyboard ([nav]), never from
        // a finger, otherwise tapping a level could start whatever was picked before (like the house).
        val leftE = nav.left
        val rightE = nav.right
        val jumpE = nav.a
        if (pauseE && !input.touching) { save(); setState(State.TITLE); return }
        if ((nav.lb || nav.rb) && openWorlds() > 1) {
            mapWorld = (mapWorld + (if (nav.rb) 1 else openWorlds() - 1)) % openWorlds()
            mapSel = mapLevels().first.coerceAtMost(unlocked - 1)
            sfx(Sound.KICK)
        }
        if (leftE) mapSel = (mapSel - 1).coerceAtLeast(-1)
        if (rightE) mapSel = (mapSel + 1).coerceAtMost(unlocked - 1)
        if (leftE || rightE) { if (mapSel >= 0) mapWorld = mapSel / WORLD_SIZE; sfx(Sound.KICK) }
        if ((jumpE || num > 0) && stateT > 0.3f) { playSelected(); return }
        if (!tapped || stateT < 0.3f) return
        val x = input.tapX
        val y = input.tapY
        if (y < 24f) {
            for ((n, bx) in mapButtons()) if (x in bx..(bx + 54f)) {
                if (n == "TITLE") { save(); setState(State.TITLE) } else { mapWorld = nextWorld(); mapSel = mapLevels().first.coerceAtMost(unlocked - 1); sfx(Sound.KICK) }
                return
            }
            return
        }
        // The PLAY button on the info strip at the bottom.
        if (y > VIEW_H - 26f && x > viewW - 90f) { playSelected(); return }
        val spots = listOf(-1) + mapLevels().toList()
        for (i in spots) {
            if (i >= unlocked) continue
            val (sx, sy) = mapSpot(i)
            if (abs(x - sx) < 16f && abs(y - sy) < 16f) {
                if (mapSel == i) playSelected() else { mapSel = i; sfx(Sound.KICK) }
                return
            }
        }
    }

    private fun playSelected() {
        if (mapSel < 0) { enterHome(); return }
        sfx(Sound.COIN)
        startLevel(mapSel, restoreHome = true)
    }

    private fun renderMap(gfx: Gfx) {
        val w2 = mapWorld == 1 || mapWorld == 3
        // Each world has its own colors: green, purple night, sandy orange, icy blue.
        val (sea, land, land2) = when (mapWorld) {
            0 -> Triple(0xFF4AA8E8, 0xFF6AC85A, 0xFF7FD86A)
            1 -> Triple(0xFF2A1E48, 0xFF4A3A6A, 0xFF5A4A7A)
            2 -> Triple(0xFF3A8AC8, 0xFFD8A85A, 0xFFE8BA6A)
            4 -> Triple(0xFF1A4A8A, 0xFF3AA8C8, 0xFF5AC8E0)
            // Spooky World: dark purple. Winter World: snowy white.
            5 -> Triple(0xFF120A20, 0xFF3A2A4A, 0xFF4A3A5A)
            6 -> Triple(0xFF3A5A8A, 0xFFE8F0FF, 0xFFFFFFFF)
            // Toy World: bright toy colors. Galaxy World: deep space and grey moon rock.
            7 -> Triple(0xFF6AC8FF, 0xFFF4C83A, 0xFFFF8A8A)
            8 -> Triple(0xFF060418, 0xFF8A8A9A, 0xFFB8B8C8)
            else -> Triple(0xFF1A2A4A, 0xFF8AB8D8, 0xFFA8D0E8)
        }
        gfx.gradient(0f, 0f, viewW, VIEW_H, mixColor(sea.toInt(), 0xFFFFFFFF.toInt(), 0.15f), mixColor(sea.toInt(), 0xFF000000.toInt(), 0.3f))
        // Land
        gfx.oval(-40f, 30f, viewW + 80f, 230f, land.toInt())
        gfx.oval(-20f, 44f, viewW + 40f, 204f, land2.toInt())
        if (w2 || mapWorld == 5 || mapWorld == 8) for (k in 0 until 20) gfx.rect((k * 71f) % viewW, (k * 37f) % 30f + 4f, 1.5f, 1.5f, 0xFFFFFFFF.toInt())
        // Snow falling on the Winter World map.
        if (mapWorld == 6) for (k in 0 until 40) gfx.oval((k * 61f + time * 8f) % viewW, (k * 43f + time * 25f) % VIEW_H, 2.5f, 2.5f, 0xDDFFFFFF.toInt())
        // The path between levels
        val levels = mapLevels().toList()
        var prev = if (mapWorld == 0) mapSpot(-1) else null
        for (i in levels) {
            val p = mapSpot(i)
            if (prev != null) {
                val n = 8
                for (k in 1 until n) {
                    val px = prev.first + (p.first - prev.first) * k / n
                    val py = prev.second + (p.second - prev.second) * k / n
                    gfx.oval(px - 2, py - 2, 4f, 4f, if (i < unlocked) 0xFFF4E8C0.toInt() else 0x66F4E8C0)
                }
            }
            prev = p
        }
        // Home
        val (hx, hy) = mapSpot(-1)
        Homes.drawHouse(ScaledGfx(gfx, 0.36f, hx - 14f, hy - 22f), houseType, 0f, 56f, time, housePaint, houseFloors)
        gfx.shadowText("HOME", hx, hy + 14, 7f, 0xFFFFF2C8.toInt(), 1)
        // Level spots
        for (i in levels) {
            val (sx, sy) = mapSpot(i)
            val open = i < unlocked
            val boss = Levels.all[i].isBossLevel
            val c = when {
                !open -> 0xFF6A6A7A.toInt()
                cleared[i] -> 0xFFFFD21F.toInt()
                boss -> 0xFFE53A1E.toInt()
                else -> 0xFFFFFFFF.toInt()
            }
            gfx.oval(sx - 12, sy - 12, 24f, 24f, 0xFF2A1A10.toInt())
            gfx.oval(sx - 10, sy - 10, 20f, 20f, c)
            gfx.text(if (open) levelLabel(i) else "?", sx, sy + 3.5f, 7f, if (open && !cleared[i] && !boss) 0xFF2A1A10.toInt() else 0xFF1A1A1A.toInt(), 1)
            if (cleared[i]) {
                gfx.rect(sx + 7, sy - 18, 1.5f, 10f, 0xFF3A3A3A.toInt())
                gfx.poly(floatArrayOf(sx + 8.5f, sx + 15, sx + 8.5f), floatArrayOf(sy - 18, sy - 15, sy - 12), 0xFF2EB84A.toInt())
            }
            if (boss && open) gfx.text("!", sx - 10, sy - 10, 8f, 0xFFFF4040.toInt(), 1)
        }
        // The team stands on the picked spot.
        val (px, py) = mapSpot(mapSel)
        if (mapSel < 0 || mapSel in levels) {
            val bob = abs(sin(time * 5)) * 3f
            val hero = if (heroOut == Who.BLOCKY) TeamArt.blocky(power, false) else TeamArt.rocco(power, 0)
            gfx.sprite(hero, px - 6, py - 34 - bob, w = 12f, h = 18f)
        }
        // Top bar
        gfx.rect(0f, 0f, viewW, 24f, 0xCC1A1008.toInt())
        gfx.shadowText("WORLD ${mapWorld + 1}", 8f, 16f, 10f, 0xFFFFD21F.toInt())
        gfx.sprite(com.roccoquest.game.Sprites.coin, 80f, 6f, w = 8f, h = 11f)
        gfx.shadowText("x$coins", 90f, 16f, 8f, white)
        gfx.shadowText("PLUSHIES $plushieCount/${Plushies.all.size}", 130f, 16f, 8f, 0xFFFF9AD0.toInt())
        for ((n, bx) in mapButtons()) {
            gfx.rect(bx, 3f, 54f, 18f, 0xFF2A1808.toInt())
            gfx.rect(bx + 1.5f, 4.5f, 51f, 15f, if (n == "TITLE") 0xFF8A1010.toInt() else 0xFF2E6BE6.toInt())
            gfx.text(n, bx + 27, 15.5f, 7f, white, 1)
        }
        // Info strip
        gfx.rect(0f, VIEW_H - 26f, viewW, 26f, 0xCC1A1008.toInt())
        val info = if (mapSel < 0) "YOUR ${houseType.label}: shop, sleep and your plushies" else
            "${levelLabel(mapSel)}  ${Levels.all[mapSel].name}" + (if (cleared[mapSel]) "  (beaten)" else "")
        gfx.shadowText(info, 8f, VIEW_H - 9f, 8f, white)
        gfx.rect(viewW - 86f, VIEW_H - 23f, 80f, 20f, 0xFF1E5A1E.toInt())
        gfx.rect(viewW - 84.5f, VIEW_H - 21.5f, 77f, 17f, 0xFF2EB84A.toInt())
        gfx.text(if (mapSel < 0) "GO HOME" else "PLAY!", viewW - 46f, VIEW_H - 9f, 9f, white, 1)
        gfx.shadowText("Tap a level, then tap it again (or PLAY!) to go", viewW / 2, 38f, 7f, 0xFFFFF2C8.toInt(), 1)
        if (toastT > 0) {
            gfx.rect(20f, VIEW_H - 48, viewW - 40, 18f, 0xDD101828.toInt())
            gfx.text(toast, viewW / 2, VIEW_H - 35, 8f, white, 1)
        }
    }

    // ======================================================================== Home sweet home

    /** Gives a plushie you don't have yet ([pref] if you're missing that one). Bosses' plushies only come from bosses. */
    fun givePlushie(pref: Plushie? = null): Plushie? {
        val missing = Plushies.all.filter { !hasPlushie(it) && (it == pref || !it.boss) }
        if (missing.isEmpty()) { coins += 5; return null }
        val p = if (pref != null && pref in missing) pref else missing.random()
        plushOwned[p.id] = true
        sfx(Sound.ONEUP)
        toast("NEW PLUSHIE: ${p.name}! ($plushieCount/${Plushies.all.size}) It's on your shelf at home.")
        sound.sayAs("A ${p.name.lowercase()} plushie!", Voice.HERO)
        return p
    }

    /** Where the house view starts, so it follows you around the room. */
    private fun homeCam(): Float {
        val w = if (inGarden) Garden.WIDTH else Homes.ROOM_W
        return if (viewW >= w) -(viewW - w) / 2 else (homeX - viewW / 2).coerceIn(0f, w - viewW)
    }

    fun enterHome() {
        homeFloor = 0
        decorating = false
        carrying = null
        homeShop = false
        monMenu = false
        plushBook = false
        homeX = Homes.DOOR_X + 40f
        homeFacing = 1
        mapSel = -1
        setState(State.HOME)
        sfx(Sound.PORTAL)
        sound.sayAs("Home sweet home!", Voice.HERO)
    }

    private fun leaveHome() {
        homeShop = false
        monMenu = false
        plushBook = false
        particles.removeAll { it is FloatText }
        sfx(Sound.KICK)
        // Out the door: you're standing on the next level to play, ready to go.
        goToMap(unlocked - 1)
    }

    /** What's in front of you in the house, for the action button. */
    private fun homeTarget(): String? {
        val x = homeX
        val upstairs = houseFloors > homeFloor + 1 && x >= Homes.STAIRS_X + 2f
        fun near(f: Furniture) = hasFurniture(f) && furnFloor(f) == homeFloor && x in (furnX(f) - 8f)..(furnX(f) + Homes.width(f) + 8f)
        if (x < Homes.DOOR_X + 36f) return if (homeFloor == 0) (if (carrying != null) null else "EXIT") else "DOWNSTAIRS"
        if (hasElevator && !decorating && x >= Homes.STAIRS_X + 2f) return "ELEVATOR"
        if (upstairs) return "UPSTAIRS"
        if (!decorating && houseFloors < Homes.MAX_FLOORS && x >= Homes.STAIRS_X + 2f) return "BUILD STAIRS"
        if (decorating) return if (carrying != null || carryingDecor != null) "PLACE" else if (furnitureAt(x) != null || decorAt(x) != null) "MOVE" else null
        if (homeFloor == 0) when {
            x in (Homes.SHOP_X - 4f)..(Homes.SHOP_X + 72f) -> return "SHOP"
            x in (Homes.MON_X - 12f)..(Homes.MON_X + 50f) -> return "POKEMON"
            hasBed() && x in (Homes.BED_X - 6f)..(Homes.BED_X + 62f) -> return "SLEEP"
        }
        return when {
            near(Furniture.CLAW_MACHINE) -> "PLAY"
            near(Furniture.ARCADE) -> "GAME"
            near(Furniture.TRAMPOLINE) -> "BOUNCE"
            near(Furniture.PIANO) -> "MUSIC"
            near(Furniture.BABY_MACHINE) -> "BABY"
            near(Furniture.DRUM_SET) -> "DRUMS"
            near(Furniture.CANDY_MACHINE) -> "CANDY"
            x in Homes.SHELF_X..(Homes.SHELF_X + 210f) && !near(Furniture.TV) -> "LOOK"
            else -> null
        }
    }

    /**
     * The plushie shelves on this floor: the newest plushies downstairs (the rest are in the PLUSHIE BOOK),
     * and the 36 before those in the playroom.
     */
    /** Draws furniture [f] at [x] in the room (and the plushies, if it's a plushie shelf). */
    fun drawFurnitureAt(gfx: Gfx, f: Furniture, x: Float, ox: Float = 0f) {
        Homes.drawFurniture(gfx, f, ox + x - Homes.spot(f), time)
        val j = f.plushShelf
        if (j < 0) return
        // These come after the plushies on the three floors' wall shelves.
        Plushies.all.filter(::hasPlushie).dropLast(Homes.SHELF_SLOTS * 3 + 12 * j).takeLast(12).forEachIndexed { k, p ->
            val (px, py) = Homes.plushShelfSpot(x, k)
            Plushies.draw(this, gfx, p, px + ox, py, 9f)
        }
    }

    fun drawShelvesWithPlushies(gfx: Gfx, ox: Float) {
        Homes.drawShelves(gfx, ox)
        val owned = Plushies.all.filter(::hasPlushie)
        val shown = owned.dropLast(Homes.SHELF_SLOTS * homeFloor).takeLast(Homes.SHELF_SLOTS)
        shown.forEachIndexed { k, p ->
            val (px, py) = Homes.shelfSpot(k)
            Plushies.draw(this, gfx, p, px + ox, py, 14f)
        }
    }

    // ---------------------------------------------------------------- The house in 3D

    /** JUMP this frame (BREAK in the 3D house). */
    private var jumpE3d = false

    /** Showing the house in 3D (walk around and build with blocks). */
    var home3d = false
        private set
    val house3d = House3D(this)

    fun enter3d() {
        if (decorating) toggleDecorate()
        home3d = true
        house3d.enter()
        TeamControls.mode3d = true
        sfx(Sound.PORTAL)
        toast("3D HOUSE! Arrows walk and turn, PLACE and BREAK blocks. Tap 2D to go back.")
    }

    fun exit3d() {
        home3d = false
        TeamControls.mode3d = false
        homeX = (house3d.x * House3D.PX).coerceIn(Homes.DOOR_X + 40f, Homes.ROOM_W - 30f)
        sfx(Sound.KICK)
    }

    internal fun slotLoad(name: String) = storage.load(key(name))
    internal fun slotSave(name: String, v: Int) = storage.save(key(name), v)
    internal fun toastFrom3D(s: String) = toast(s)
    internal fun inputForDraw() = input

    // ---------------------------------------------------------------- Decorating: move furniture anywhere

    /** Moving furniture around (the DECORATE button at home). */
    var decorating = false
        private set
    /** CONSTRUCTION ROCCO: placing blocks on the walls (the BUILD button at home). */
    var building = false
        private set
    var buildBlock = BuildBlock.BRICK
    /** The blocks built on each floor: cell (row * COLS + column) to block. */
    val blocks = Array(Homes.MAX_FLOORS) { HashMap<Int, BuildBlock>() }

    /** Decorations put down around the house and backyard (you can have lots of each). */
    val decor = ArrayList<Placed>()
    /** The decoration you're carrying while decorating. */
    var carryingDecor: Placed? = null
        private set

    /** The piece of furniture you're carrying while decorating. */
    var carrying: Furniture? = null
        private set
    /** Where each piece of furniture is: 0 for its usual spot, else (x + 1) + floor * 10000. */
    private val furnPos = IntArray(Furniture.entries.size)

    fun furnX(f: Furniture): Float = if (furnPos[f.ordinal] == 0) Homes.spot(f) else ((furnPos[f.ordinal] - 1) % 10000).toFloat()
    fun furnFloor(f: Furniture): Int = if (furnPos[f.ordinal] == 0) f.floor else (furnPos[f.ordinal] - 1) / 10000

    /** The piece of furniture you're standing in front of on this floor (if any). */
    private fun furnitureAt(x: Float): Furniture? = Furniture.entries
        .filter { hasFurniture(it) && Homes.movable(it) && furnFloor(it) == homeFloor && x in furnX(it)..(furnX(it) + Homes.width(it)) }
        .minByOrNull { Homes.layer(it) }

    /** True if [f] has room at [x] on [floor]: not on the stairs, doors, shop or bed, and not on other furniture. */
    fun fits(f: Furniture, x: Float, floor: Int): Boolean {
        val w = Homes.width(f)
        val layer = Homes.layer(f)
        if (x < 0f || x + w > Homes.ROOM_W) return false
        if (Homes.blocked(floor, layer, hasBed(), houseFloors > floor + 1).any { x + w > it.start && x < it.endInclusive }) return false
        return Furniture.entries.none { g ->
            g != f && hasFurniture(g) && Homes.movable(g) && furnFloor(g) == floor && Homes.layer(g) == layer &&
                x + w - 2f > furnX(g) && x + 2f < furnX(g) + Homes.width(g)
        }
    }

    fun toggleDecorate() {
        decorating = !decorating
        carrying = null
        carryingDecor = null
        sfx(Sound.KICK)
        toast(if (decorating) "DECORATE: walk to furniture and tap MOVE, then walk somewhere and tap PLACE!" else "All done decorating!")
    }

    private fun pickUp() {
        val f = furnitureAt(homeX) ?: return pickUpDecor()
        carrying = f
        sfx(Sound.KICK)
        toast("Carrying the ${f.label}. Walk anywhere (even up the stairs) and tap PLACE.")
    }

    /** Puts what you're carrying down right where you're standing, if there's room. */
    fun placeCarried(at: Float = homeX) {
        if (carryingDecor != null) { placeDecor(at); return }
        val f = carrying ?: return
        val x = (at - Homes.width(f) / 2).coerceAtLeast(0f).toInt().toFloat()
        if (!fits(f, x, homeFloor)) { sfx(Sound.BUMP); toast("No room for the ${f.label} here! Try another spot."); return }
        furnPos[f.ordinal] = (x.toInt() + 1) + homeFloor * 10000
        carrying = null
        sfx(Sound.POWERUP)
        toast("${f.label} placed!")
        save()
    }

    internal fun pickUpForTest(f: Furniture) { carrying = f }

    // ---------------------------------------------------------------- Construction Rocco (the BUILD button)

    fun toggleBuild() {
        building = !building
        if (building && decorating) toggleDecorate()
        sfx(Sound.KICK)
        if (building) {
            sound.sayAs("Let's-a build!", Voice.HERO)
            toast("CONSTRUCTION ROCCO! Tap the wall to build!")
        } else toast("All done building!")
    }

    private fun paletteX(i: Int) = viewW / 2 - BuildBlock.entries.size * 26f / 2 + i * 26f

    fun canAfford(b: BuildBlock) = coins >= b.coins && (b.ore == null || inventory[b.ore.ordinal] >= 1)

    /** Puts a [buildBlock] in [cell] on this floor (it costs one material, or a few coins). */
    fun placeBlock(cell: Int): Boolean {
        val here = blocks[homeFloor]
        if (cell < 0 || here.containsKey(cell)) return false
        if (here.size >= Build.MAX) { toast("That's a lot of blocks! Take some down first."); return false }
        val b = buildBlock
        if (!canAfford(b)) { needToast(listOfNotNull(b.ore?.let { it to 1 }), b.coins); return false }
        b.ore?.let { inventory[it.ordinal] -= 1 }
        coins -= b.coins
        here[cell] = b
        sfx(Sound.BUMP)
        return true
    }

    /** Takes a block back down (you get its material back). */
    fun removeBlock(cell: Int): Boolean {
        val b = blocks[homeFloor].remove(cell) ?: return false
        b.ore?.let { inventory[it.ordinal] += 1 }
        coins += b.coins
        sfx(Sound.BREAK)
        return true
    }

    /** The cell in front of Rocco, on top of whatever is already built there. */
    private fun frontCell(): Int {
        val col = ((homeX + homeFacing * 14f) / Build.CELL).toInt().coerceIn(0, Build.COLS - 1)
        val row = (0 until Build.ROWS).firstOrNull { !blocks[homeFloor].containsKey(it * Build.COLS + col) } ?: return -1
        return row * Build.COLS + col
    }

    private fun updateBuild(dt: Float, tapped: Boolean, actionE: Boolean) {
        // (B is the ACTION button on a controller, so it builds too: PAUSE or DONE stops building.)
        if (nav.up || nav.down) {
            val i = (buildBlock.ordinal + (if (nav.down) 1 else BuildBlock.entries.size - 1)) % BuildBlock.entries.size
            buildBlock = BuildBlock.entries[i]; sfx(Sound.KICK)
        }
        val speed = 110f
        if (input.left) { homeX -= speed * dt; homeFacing = -1; homeAnim += dt }
        if (input.right) { homeX += speed * dt; homeFacing = 1; homeAnim += dt }
        homeX = homeX.coerceIn(Homes.DOOR_X + 4f, Homes.ROOM_W - 20f)
        if (actionE || nav.a) { if (placeBlock(frontCell())) save(); return }
        if (!tapped || stateT < 0.2f || input.tapX < 0f) return
        val x = input.tapX
        val y = input.tapY
        // The block picker along the bottom.
        if (y in 24f..56f && x in paletteX(0)..(paletteX(BuildBlock.entries.size))) {
            BuildBlock.entries.forEachIndexed { i, b -> if (x in paletteX(i)..(paletteX(i) + 24f)) { buildBlock = b; sfx(Sound.KICK); toast("${b.label} blocks"); return } }
        }
        val cell = Build.cellAt(x + homeCam(), y)
        if (cell < 0) return
        if (removeBlock(cell) || placeBlock(cell)) save()
    }

    private fun drawBlocks(gfx: Gfx, ox: Float) {
        for ((cell, b) in blocks[homeFloor]) {
            val x = Build.cellX(cell % Build.COLS) + ox
            if (x < -16f || x > viewW) continue
            Build.draw(gfx, b, x, Build.cellY(cell / Build.COLS), time)
        }
        if (!building) return
        // Building: a faint grid, and the block that ACTION would place.
        var gx = (-ox / Build.CELL).toInt() * Build.CELL + ox
        while (gx < viewW) { gfx.rect(gx, Build.cellY(Build.ROWS - 1), 0.6f, Build.ROWS * Build.CELL, 0x22000000); gx += Build.CELL }
        for (r in 0..Build.ROWS) gfx.rect(0f, Build.cellY(r - 1), viewW, 0.6f, 0x22000000)
        val fc = frontCell()
        if (fc >= 0) {
            val fx = Build.cellX(fc % Build.COLS) + ox
            val fy = Build.cellY(fc / Build.COLS)
            gfx.rect(fx, fy, 16f, 16f, 0x55FFFFFF)
            gfx.rect(fx, fy, 16f, 1.5f, 0xFFFFE14D.toInt()); gfx.rect(fx, fy + 14.5f, 16f, 1.5f, 0xFFFFE14D.toInt())
            gfx.rect(fx, fy, 1.5f, 16f, 0xFFFFE14D.toInt()); gfx.rect(fx + 14.5f, fy, 1.5f, 16f, 0xFFFFE14D.toInt())
        }
    }

    private fun renderBuildPalette(gfx: Gfx) {
        val w = BuildBlock.entries.size * 26f + 8f
        // The block picker sits at the top, under the buttons (out of Rocco's way).
        gfx.rect(viewW / 2 - w / 2, 24f, w, 34f, 0xCC1A1008.toInt())
        BuildBlock.entries.forEachIndexed { i, b ->
            val x = paletteX(i)
            if (b == buildBlock) gfx.rect(x - 1, 26f, 26f, 22f, 0xFFFFD21F.toInt())
            Build.draw(gfx, b, x + 4, 28f, time)
            gfx.text(if (b.ore != null) "1 ${b.ore.label.take(5)}" else "${b.coins}c", x + 12, 55f, 4.5f, if (canAfford(b)) white else 0xFFFF8A8A.toInt(), 1)
        }
        gfx.shadowText("Tap the wall to build, tap a block to take it back. ACTION builds in front of you!", viewW / 2, 66f, 6f, 0xFFFFE14D.toInt(), 1)
    }

    // ---------------------------------------------------------------- Decorations (DECOR and YARD shop tabs)

    /** Where you are: a floor of the house, or the backyard. */
    private fun hereFloor() = if (inGarden) Decor.YARD else homeFloor

    private fun decorAt(x: Float): Placed? = decor
        .filter { it !== carryingDecor && it.floor == hereFloor() && x in it.x..(it.x + it.item.w) }
        .minByOrNull { it.item.layer }

    /** True if [d] has room at [x] on [floor] (in the yard: past the garden; inside: not on doors, stairs or furniture). */
    fun decorFits(d: DecorItem, x: Float, floor: Int, ignore: Placed? = null): Boolean {
        if (floor == Decor.YARD) {
            if (x < Garden.YARD_X || x + d.w > Garden.WIDTH - 10f) return false
        } else {
            if (x < 0f || x + d.w > Homes.ROOM_W) return false
            if (Homes.blocked(floor, d.layer, hasBed(), houseFloors > floor + 1 || hasElevator).any { x + d.w > it.start && x < it.endInclusive }) return false
            if (Furniture.entries.any { g -> hasFurniture(g) && Homes.movable(g) && furnFloor(g) == floor && Homes.layer(g) == d.layer && x + d.w - 2f > furnX(g) && x + 2f < furnX(g) + Homes.width(g) }) return false
        }
        return decor.none { o -> o !== ignore && o.floor == floor && o.item.layer == d.layer && x + d.w - 2f > o.x && x + 2f < o.x + o.item.w }
    }

    /** The nearest free spot to [near] (or null if this floor is full). */
    private fun freeSpot(d: DecorItem, floor: Int, near: Float): Float? {
        for (step in 0 until 200) for (sgn in intArrayOf(1, -1)) {
            val x = (near - d.w / 2 + sgn * step * 10f)
            if (decorFits(d, x, floor)) return x
        }
        return null
    }

    fun buyDecor(d: DecorItem) {
        if (coins < d.coins) { needToast(emptyList(), d.coins); return }
        if (decor.size >= Decor.MAX) { sfx(Sound.BUMP); toast("That's a LOT of decorations! Move some around instead."); return }
        val floor = if (d.yard) Decor.YARD else if (inGarden) 0 else homeFloor
        val near = if (floor == hereFloor()) homeX else if (floor == Decor.YARD) Garden.YARD_X + 60f else 300f
        val x = freeSpot(d, floor, near)
        if (x == null) { sfx(Sound.BUMP); toast("No room for the ${d.label} ${if (floor == Decor.YARD) "in the backyard" else "on this floor"}!"); return }
        coins -= d.coins
        decor += Placed(d, x, floor)
        sfx(Sound.POWERUP)
        toast(when {
            floor == Decor.YARD && !inGarden -> "New ${d.label}! It's out in the BACKYARD (GARDEN button, then walk right)."
            floor == Decor.YARD -> "New ${d.label} in the backyard!"
            else -> "New ${d.label}! Use DECORATE to move it anywhere."
        })
        save()
    }

    private fun pickUpDecor() {
        val d = decorAt(homeX) ?: return
        carryingDecor = d
        sfx(Sound.KICK)
        toast("Carrying the ${d.item.label}. Walk anywhere and tap PLACE.")
    }

    private fun placeDecor(at: Float) {
        val d = carryingDecor ?: return
        if (d.item.yard != (hereFloor() == Decor.YARD)) {
            sfx(Sound.BUMP); toast(if (d.item.yard) "The ${d.item.label} goes outside in the backyard!" else "The ${d.item.label} goes inside the house!"); return
        }
        val x = (at - d.item.w / 2).toInt().toFloat()
        if (!decorFits(d.item, x, hereFloor(), d)) { sfx(Sound.BUMP); toast("No room for the ${d.item.label} here! Try another spot."); return }
        d.x = x
        d.floor = hereFloor()
        carryingDecor = null
        sfx(Sound.POWERUP)
        toast("${d.item.label} placed!")
        save()
    }

    /** Draws the decorations on [floor] (flat ones first), and the one you're carrying. */
    private fun drawDecor(gfx: Gfx, ox: Float, floor: Int) {
        for (layerPass in intArrayOf(2, 1, 0)) for (d in decor) {
            if (d.floor != floor || d.item.layer != layerPass || d === carryingDecor) continue
            if (d.x + d.item.w + ox < -60f || d.x + ox > viewW + 60f) continue
            Decor.draw(gfx, d.item, d.x + ox, Homes.FLOOR_Y, time)
        }
        carryingDecor?.let { d ->
            val fx = homeX - d.item.w / 2
            Decor.draw(gfx, d.item, fx + ox, Homes.FLOOR_Y, time)
            val ok = d.item.yard == (floor == Decor.YARD) && decorFits(d.item, fx.toInt().toFloat(), floor, d)
            gfx.rect(fx + ox, Homes.FLOOR_Y - 4f, d.item.w, 4f, if (ok) 0xCC3CE070.toInt() else 0xCCFF4A4A.toInt())
        }
    }

    /** Fun things to do with backyard decorations. */
    private fun yardTarget(x: Float): String? {
        val d = decor.firstOrNull { it.floor == Decor.YARD && x in (it.x - 6f)..(it.x + it.item.w + 6f) } ?: return null
        return when (d.item) {
            DecorItem.KIDDIE_POOL, DecorItem.ROUND_POOL, DecorItem.BIG_POOL, DecorItem.SLIDE_POOL, DecorItem.LAZY_RIVER, DecorItem.POND -> "SWIM"
            DecorItem.HOT_SPRING -> "RELAX"
            DecorItem.BIG_TRAMPOLINE, DecorItem.BOUNCY_HOUSE -> "BOUNCE"
            DecorItem.SWING_SET -> "SWING"
            DecorItem.SLIDE_TOWER -> "SLIDE"
            DecorItem.BBQ -> "COOK"
            else -> null
        }
    }

    private fun yardAction(what: String) {
        when (what) {
            "SWIM" -> { sfx(Sound.SPLASH); homeHopT = 0.6f; toast("SPLASH! ${mon.label} jumps in too!") }
            "RELAX" -> { sfx(Sound.ONEUP); toast("Ahhh... nice and warm. (+1 Pokemon play energy)"); monEnergy = minOf(5, monEnergy + 1) }
            "BOUNCE", "SLIDE", "SWING" -> if (homeHopT <= 0f) { homeHopT = 0.8f; sfx(Sound.JUMP); sound.sayAs(if (what == "SLIDE") "Wheee!" else "Boing!", Voice.HERO) }
            "COOK" -> { sfx(Sound.COIN); sound.sayAs("Burgers for everyone!", Voice.HERO); toast("Yum! ${mon.label} loves the BBQ.") }
        }
    }

    private fun homeAction(what: String?) {
        when (what) {
            "EXIT" -> leaveHome()
            "SHOP" -> { homeShop = true; sfx(Sound.KICK) }
            "SLEEP" -> sleepAtHome()
            "LOOK" -> { plushBook = true; sfx(Sound.KICK) }
            "PLAY" -> playClaw()
            "BUILD STAIRS" -> openFloorShop()
            "UPSTAIRS" -> changeFloor(homeFloor + 1)
            "ELEVATOR" -> { elevatorMenu = true; sfx(Sound.COIN); sound.say("Ding!") }
            "DOWNSTAIRS" -> changeFloor(homeFloor - 1)
            "GAME" -> startPark()
            "BOUNCE" -> if (homeHopT <= 0f) { homeHopT = 0.8f; sfx(Sound.JUMP) }
            "MUSIC" -> { sfx(Sound.ONEUP); sound.say("Do re mi fa so la ti do!") }
            "BABY" -> babyMachine()
            "DRUMS" -> { sfx(Sound.STOMP); sound.say("Boom boom bap! Boom boom bap!") }
            "CANDY" -> { sfx(Sound.COIN); sound.sayAs("Yummy candy!", Voice.HERO); toast("A gumball! Yum!") }
            "MOVE" -> pickUp()
            "PLACE" -> placeCarried()
            "POKEMON" -> { monMenu = true; monSwitch = false; monCursor = 0; sfx(Sound.KICK); sound.sayAs(mon.label, Voice.MON) }
        }
    }

    // ---------------------------------------------------------------- Pokemon Park: BERRY CATCH

    /** Something falling in the park: 0 a berry, 1 a golden berry, 2 a rock. */
    private class Drop(var x: Float, var y: Float, val kind: Int, val vy: Float)

    private val drops = ArrayList<Drop>()
    var parkTime = 0f
        private set
    var parkBerries = 0
        private set
    var parkLives = 3
        private set
    private var parkX = 0f
    private var parkY = 0f
    private var parkVy = 0f
    private var parkSpawn = 0f
    private var parkFacing = 1
    var parkDone = false
        private set
    private var parkResult = ""
    /** True while the Pokemon still has energy, so this game earns XP and coins. */
    private var parkRewards = true
    private val PARK_GROUND = 190f
    private val PARK_LENGTH = 40f

    /** Off to the Pokemon Park for a game of BERRY CATCH. */
    fun startPark() {
        monMenu = false
        drops.clear()
        particles.clear()
        parkTime = PARK_LENGTH
        parkBerries = 0
        parkLives = 3
        parkX = viewW / 2
        parkY = 0f
        parkVy = 0f
        parkSpawn = 0.8f
        parkDone = false
        parkRewards = monEnergy > 0
        if (parkRewards) monEnergy--
        setState(State.PARK)
        sfx(Sound.PORTAL)
        sound.sayAs("${mon.label}! Let's catch berries!", Voice.HERO)
        toast(if (parkRewards) "Catch the berries! Gold ones are worth 5. Dodge the rocks!" else "${mon.label} is sleepy: this game is just for fun (no prizes).")
    }

    private fun updatePark(dt: Float, tapped: Boolean, jumpE: Boolean, close: Boolean) {
        if (parkDone) {
            if ((tapped || jumpE || close || nav.a || nav.b) && stateT > 1f) { setState(State.HOME); sfx(Sound.KICK) }
            return
        }
        if (close) { finishPark(); return }
        // Run left and right, jump to reach the high berries.
        val speed = 150f + if (mon.base == Mon.SPARKY || mon.base == Mon.EMBER) 20f else 0f
        if (input.left) { parkX -= speed * dt; parkFacing = -1 }
        if (input.right) { parkX += speed * dt; parkFacing = 1 }
        parkX = parkX.coerceIn(16f, viewW - 16f)
        if (jumpE && parkY >= 0f) { parkVy = -300f; sfx(Sound.JUMP) }
        parkVy += 800f * dt
        parkY = minOf(0f, parkY + parkVy * dt)
        if (parkY >= 0f) parkVy = 0f
        // Things fall faster and more often as time runs out.
        val k = 1f - parkTime / PARK_LENGTH
        parkSpawn -= dt
        if (parkSpawn <= 0f) {
            parkSpawn = 0.75f - 0.4f * k
            val roll = Random.nextFloat()
            val kind = when { roll < 0.14f -> 1; roll < 0.14f + 0.12f + 0.15f * k && parkTime < PARK_LENGTH - 4f -> 2; else -> 0 }
            drops += Drop(20f + Random.nextFloat() * (viewW - 40f), 20f, kind, (if (kind == 1) 95f else 70f) + 70f * k + Random.nextFloat() * 30f)
        }
        val my = PARK_GROUND - 14f + parkY
        val it = drops.iterator()
        while (it.hasNext()) {
            val d = it.next()
            d.y += d.vy * dt
            if (abs(d.x - parkX) < 17f && abs(d.y - my) < 17f) {
                it.remove()
                when (d.kind) {
                    0 -> { parkBerries++; sfx(Sound.COIN); particles += FloatText(d.x, d.y - 10, "+1", 0xFFFF6A9A.toInt()) }
                    1 -> { parkBerries += 5; sfx(Sound.ONEUP); particles += FloatText(d.x, d.y - 10, "+5!", 0xFFFFD21F.toInt()) }
                    else -> {
                        parkLives--
                        shake = 0.3f
                        sfx(Sound.BUMP)
                        particles += FloatText(d.x, d.y - 10, "OUCH!", 0xFFFF4A4A.toInt())
                        if (parkLives <= 0) { finishPark(); return }
                    }
                }
            } else if (d.y > PARK_GROUND + 10f) it.remove()
        }
        if (shake > 0) shake -= dt
        parkTime -= dt
        if (parkTime <= 0f) finishPark()
    }

    /** Game over: count the berries and hand out the prizes. */
    private fun finishPark() {
        parkDone = true
        stateT = 0f
        drops.clear()
        val xp = minOf(20, parkBerries / 2)
        val prize = minOf(40, parkBerries / 2)
        parkResult = if (!parkRewards) "Just for fun! (Play a level so ${mon.label} has energy again.)" else "+$xp XP for ${mon.label}   +$prize coins"
        if (parkRewards) {
            coins += prize
            gainXp(xp)
            if (parkBerries >= 40) giveNewStickers(1).firstOrNull()?.let { parkResult += "   + a ${Stickers.all[it].name} sticker!" }
            save()
        }
        sfx(Sound.CLEAR)
        sound.sayAs("${parkBerries} berries! Yummy!", Voice.MON)
    }

    private fun renderPark(gfx: Gfx) {
        val sx = if (shake > 0) sin(time * 80) * 3f else 0f
        // Sky, sun, hills and trees
        gfx.gradient(0f, 0f, viewW, VIEW_H, 0xFF4AA4F0.toInt(), 0xFFD0F0FF.toInt())
        gfx.oval(viewW - 70, 30f, 34f, 34f, 0xFFFFE87A.toInt())
        for (i in 0 until 3) {
            val cx = ((time * 8 + i * 160f) % (viewW + 80f)) - 40f
            gfx.oval(cx, 40f + i * 18f, 46f, 14f, 0xEEFFFFFF.toInt())
        }
        gfx.oval(-60f, PARK_GROUND - 50f, viewW * 0.7f, 120f, 0xFF6ACB5A.toInt())
        gfx.oval(viewW * 0.4f, PARK_GROUND - 40f, viewW * 0.8f, 110f, 0xFF5AB84A.toInt())
        for (tx in listOf(26f, viewW - 40f)) {
            gfx.rect(tx + 8, PARK_GROUND - 50f, 10f, 50f, 0xFF7A4A20.toInt())
            gfx.oval(tx - 10, PARK_GROUND - 92f, 46f, 50f, 0xFF2E9A3A.toInt())
            for (b in 0 until 4) gfx.oval(tx - 2 + b * 9f, PARK_GROUND - 78f + (b % 2) * 12f, 6f, 6f, 0xFFE84A6A.toInt())
        }
        gfx.rect(0f, PARK_GROUND, viewW, VIEW_H - PARK_GROUND, 0xFF4AA83A.toInt())
        for (i in 0 until (viewW / 16).toInt() + 1) gfx.rect(i * 16f + 4, PARK_GROUND + 2, 2f, 5f, 0xFF3A8A2A.toInt())
        // Falling things
        for (d in drops) {
            val x = d.x + sx
            when (d.kind) {
                0 -> {
                    gfx.oval(x - 6, d.y - 6, 12f, 12f, 0xFFE84A6A.toInt())
                    gfx.oval(x - 3, d.y - 4, 4f, 3f, 0x99FFFFFF.toInt())
                    gfx.oval(x - 1, d.y - 10, 6f, 4f, 0xFF3CB84A.toInt())
                }
                1 -> {
                    gfx.oval(x - 8, d.y - 8, 16f, 16f, argb(90, 0xFFF27A))
                    gfx.oval(x - 6, d.y - 6, 12f, 12f, 0xFFFFD21F.toInt())
                    gfx.oval(x - 3, d.y - 4, 4f, 3f, 0xCCFFFFFF.toInt())
                    gfx.oval(x - 1, d.y - 10, 6f, 4f, 0xFF3CB84A.toInt())
                }
                else -> {
                    gfx.oval(x - 8, d.y - 7, 16f, 14f, 0xFF6A6A72.toInt())
                    gfx.oval(x - 5, d.y - 5, 6f, 4f, 0xFF8A8A92.toInt())
                }
            }
        }
        TeamArt.mon(ScaledGfx(gfx, 1.4f, parkX + sx, PARK_GROUND + parkY), mon, 0f, 0f, time, parkFacing, false, input.left || input.right)
        for (p in particles) p.draw(gfx, 0f)
        // Top bar
        gfx.rect(0f, 0f, viewW, 24f, 0xCC10301A.toInt())
        gfx.shadowText("POKEMON PARK: BERRY CATCH", 8f, 15f, 8f, 0xFFFFD21F.toInt())
        gfx.shadowText("BERRIES $parkBerries", viewW / 2 + 10, 15f, 8f, 0xFFFF9AC8.toInt())
        gfx.shadowText("TIME ${maxOf(0, kotlin.math.ceil(parkTime).toInt())}", viewW - 120f, 15f, 8f, white)
        for (i in 0 until 3) TeamArt.heart(gfx, viewW - 56f + i * 12f, 6f, 10f, if (i < parkLives) 0xFFE52521.toInt() else 0x66000000)
        if (parkDone) {
            gfx.rect(viewW / 2 - 150, 60f, 300f, 100f, 0xEE10301A.toInt())
            gfx.shadowText("${mon.label} caught $parkBerries berries!", viewW / 2, 90f, 12f, 0xFFFFD21F.toInt(), 1)
            gfx.text(parkResult, viewW / 2, 115f, 7f, white, 1)
            gfx.text("Tap to go home", viewW / 2, 145f, 7f, 0xFFB8E8B8.toInt(), 1)
        } else {
            TeamControls.layout(viewW)
            TeamControls.drawHome(gfx, input, "JUMP", time)
            if (stateT < 3f && toastT > 0) gfx.shadowText(toast, viewW / 2, 40f, 7f, white, 1)
        }
    }

    /** Up or down the stairs: you come out at the other end of the new floor. */
    fun changeFloor(f: Int) {
        if (f !in 0 until houseFloors) return
        val up = f > homeFloor
        homeFloor = f
        homeX = if (up) Homes.DOOR_X + 42f else Homes.STAIRS_X - 12f
        homeFacing = if (up) 1 else -1
        sfx(Sound.KICK)
        toast(if (f == 0) "Back downstairs." else "FLOOR ${f + 1}: " + when (f) { 1 -> "the playroom!"; 2 -> "the big room!"; else -> "the sky room!" })
    }

    /** The BABY MACHINE: ZAP! Everyone is a baby (zap again to grow back up). */
    fun babyMachine() {
        babyMode = !babyMode
        babyZapT = 1.2f
        sfx(Sound.ZAP)
        if (babyMode) {
            sound.sayAs("Goo goo ga ga!", Voice.HERO)
            toast("ZAP! Everybody is a BABY! (Use the machine again to grow back up.)")
        } else {
            sound.sayAs("We're big again!", Voice.HERO)
            toast("ZAP! Back to normal size.")
        }
        save()
    }

    /** How many levels you've beaten (furniture unlocks as this goes up). */
    fun clearedCount() = cleared.count { it }

    /** Opens the priced upgrade card; building always requires an explicit purchase. */
    fun openFloorShop() {
        homeShop = true
        shopTab = 3
        shopPage = 0
        shopCursor = HOUSES_PER_PAGE
        toast("Build an upstairs room + stairs for your ${houseType.label}.")
    }

    /** Adding a floor to the house: a playroom first, then a top floor. */
    fun floorCost(): Pair<List<Pair<Ore, Int>>, Int> =
        when (houseFloors) {
            1 -> listOf(Ore.STONE to 12, Ore.IRON to 4) to 150
            2 -> listOf(Ore.STONE to 20, Ore.GOLD to 5) to 300
            else -> listOf(Ore.STONE to 20, Ore.DIAMOND to 2) to 500
        }

    fun buyFloor() {
        if (houseFloors >= Homes.MAX_FLOORS) { toast("Your house is as tall as it can be!"); return }
        val (costs, c) = floorCost()
        if (!has(costs, c)) { needToast(costs, c); return }
        for ((o, n) in costs) inventory[o.ordinal] -= n
        coins -= c
        houseFloors++
        sfx(Sound.POWERUP)
        toast("FLOOR $houseFloors built! Take the stairs (on the right) up to decorate it.")
        save()
    }

    private val ELEVATOR_ORES = listOf(Ore.IRON to 6, Ore.GOLD to 2)
    private val ELEVATOR_COST = 400

    fun buyElevator() {
        if (hasElevator) { toast("You already have an elevator. Ding!"); return }
        if (!has(ELEVATOR_ORES, ELEVATOR_COST)) { needToast(ELEVATOR_ORES, ELEVATOR_COST); return }
        for ((o, n) in ELEVATOR_ORES) inventory[o.ordinal] -= n
        coins -= ELEVATOR_COST
        hasElevator = true
        sfx(Sound.POWERUP)
        toast("ELEVATOR! Walk to the right end of any floor and ride it anywhere (even the backyard).")
        save()
    }

    /** The elevator's buttons: every floor, the backyard, and BUILD if the house can get taller. */
    private fun elevatorStops(): List<Pair<String, () -> Unit>> =
        (0 until houseFloors).map { f -> "${f + 1}F" to { elevatorMenu = false; if (f != homeFloor) { changeFloor(f); homeX = Homes.STAIRS_X - 12f; homeFacing = -1 } } } +
            listOf("YARD" to { elevatorMenu = false; enterGarden() }) +
            (if (houseFloors < Homes.MAX_FLOORS) listOf("BUILD" to { elevatorMenu = false; openFloorShop() }) else emptyList())

    private fun elevatorButtonX(i: Int, n: Int) = viewW / 2 - n * 48f / 2 + i * 48f + 4f

    private fun updateElevator(tapped: Boolean) {
        val stops = elevatorStops()
        if (nav.left) shopCursor = (shopCursor + stops.size - 1) % stops.size
        if (nav.right) shopCursor = (shopCursor + 1) % stops.size
        if (nav.a) { stops.getOrNull(shopCursor)?.second?.invoke(); sfx(Sound.COIN); return }
        if (nav.b) { elevatorMenu = false; return }
        if (!tapped || input.tapX < 0f) return
        for (i in stops.indices) {
            val bx = elevatorButtonX(i, stops.size)
            if (input.tapX in bx..(bx + 40f) && input.tapY in 92f..126f) { stops[i].second(); sfx(Sound.COIN); return }
        }
        elevatorMenu = false
    }

    private fun renderElevator(gfx: Gfx) {
        val stops = elevatorStops()
        val w = stops.size * 48f + 16f
        gfx.rect(viewW / 2 - w / 2, 62f, w, 74f, 0xEE2A2A34.toInt())
        gfx.rect(viewW / 2 - w / 2 + 2, 64f, w - 4, 70f, 0xFF4A4A58.toInt())
        gfx.shadowText("ELEVATOR: WHERE TO?", viewW / 2, 80f, 9f, 0xFFFFD21F.toInt(), 1)
        stops.forEachIndexed { i, (label, _) ->
            val bx = elevatorButtonX(i, stops.size)
            val here = label == "${homeFloor + 1}F"
            gfx.oval(bx, 92f, 40f, 34f, if (here) 0xFFFFD21F.toInt() else 0xFFE8E8F0.toInt())
            gfx.text(label, bx + 20f, 113f, if (label.length > 3) 7f else 10f, 0xFF2A2A34.toInt(), 1)
            if (padConnected && shopCursor == i) padFrame(gfx, bx - 2, 90f, 44f, 38f)
        }
    }

    /** Sleeping in your bed at home saves the game. */
    fun sleepAtHome() {
        if (!hasBed()) return
        hearts = MAX_HEARTS
        save()
        storage.save(key("spotLevel"), 0)
        sfx(Sound.CLEAR)
        sound.say("Good night!")
        toast("Zzz... Good morning! Game saved in FILE ${slot + 1}.")
        savedFlash = 2f
    }

    fun buyFurniture(f: Furniture) {
        if (hasFurniture(f)) { toast("You already have the ${f.label}."); return }
        if (f.floor >= houseFloors) { sfx(Sound.BUMP); toast("The ${f.label} goes on FLOOR ${f.floor + 1}: build it first (HOUSES tab)."); return }
        if (clearedCount() < f.unlock) { sfx(Sound.BUMP); toast("LOCKED! Beat ${f.unlock} levels to unlock the ${f.label} (${clearedCount()} so far)."); return }
        if (!has(f.costs, f.coins)) { needToast(f.costs, f.coins); return }
        for ((o, n) in f.costs) inventory[o.ordinal] -= n
        coins -= f.coins
        furnitureBits = furnitureBits or (1L shl f.ordinal)
        sfx(Sound.POWERUP)
        toast(if (f == Furniture.CLAW_MACHINE) "CLAW MACHINE! Walk to it and press PLAY ($CLAW_COST coins a try)." else "New ${f.label}!")
        save()
    }

    fun buyBed(b: BedStyle) {
        if (ownsBed(b)) { bedStyle = b; toast("The ${b.label} is in your room now."); save(); return }
        if (coins < b.coins) { needToast(emptyList(), b.coins); return }
        coins -= b.coins
        bedsOwned = bedsOwned or (1 shl b.ordinal)
        bedStyle = b
        sfx(Sound.POWERUP)
        toast("A ${b.label}! Walk to it and press SLEEP to save.")
        save()
    }

    fun buyPlushie(p: Plushie) {
        if (hasPlushie(p)) { toast("You already have the ${p.name} plushie."); return }
        if (coins < p.price) { needToast(emptyList(), p.price); return }
        coins -= p.price
        plushOwned[p.id] = true
        sfx(Sound.ONEUP)
        toast("${p.name} plushie! ($plushieCount/${Plushies.all.size})")
        sound.sayAs("A ${p.name.lowercase()} plushie!", Voice.HERO)
        save()
    }

    fun buyHouse(t: HouseType) {
        if (houseType == t) { toast("You already live in the ${t.label}!"); return }
        if (!has(t.costs, t.coins)) { needToast(t.costs, t.coins); return }
        for ((o, n) in t.costs) inventory[o.ordinal] -= n
        coins -= t.coins
        houseType = t
        sfx(Sound.POWERUP)
        toast("Welcome to your new ${t.label}! (Your furniture and plushies moved in too.)")
        save()
    }

    private fun needToast(costs: List<Pair<Ore, Int>>, coinsNeeded: Int) {
        sfx(Sound.BUMP)
        toast("Need " + (costs.map { "${it.second} ${it.first.label}" } + (if (coinsNeeded > 0) listOf("$coinsNeeded COINS") else emptyList())).joinToString(" + ") + "!")
    }

    /** The claw machine always wins (it's a nice one): a new plushie for some coins. */
    fun playClaw() {
        when {
            !hasFurniture(Furniture.CLAW_MACHINE) -> return
            Plushies.all.none { !hasPlushie(it) && !it.boss } -> toast("The claw machine is empty: you have them all!")
            coins < CLAW_COST -> { sfx(Sound.BUMP); toast("The claw machine needs $CLAW_COST coins.") }
            else -> { coins -= CLAW_COST; givePlushie(); save() }
        }
    }

    val SHOP_TABS = listOf("FURNITURE", "BEDS", "PLUSHIES", "HOUSES", "COLORS", "STICKERS", "DECOR", "YARD")
    private fun decorTab(tab: Int) = DecorItem.entries.filter { it.yard == (tab == 7) }
    private fun decorPage() = decorTab(shopTab).drop(shopPage * PLUSH_PER_PAGE).take(PLUSH_PER_PAGE)
    private val PLUSH_PER_PAGE = 12

    /** One card in the shop grid (3 across, 4 down). */
    private fun shopCard(i: Int): Pair<Float, Float> {
        val w = min(130f, (viewW - 40f) / 3f)
        val left = viewW / 2 - (w * 3 + 8f) / 2
        return (left + (i % 3) * (w + 4f)) to (52f + (i / 3) * 44f)
    }
    private fun shopCardW() = min(130f, (viewW - 40f) / 3f)

    /** The things for sale on the current shop tab, each with what tapping it does. */
    private fun shopItems(): List<Pair<String, () -> Unit>> = when (shopTab) {
        0 -> furniturePage().map { f -> f.label to { buyFurniture(f) } }
        1 -> BedStyle.entries.map { b -> b.label to { buyBed(b) } }
        2 -> Plushies.all.drop(shopPage * PLUSH_PER_PAGE).take(PLUSH_PER_PAGE).map { p -> p.name to { buyPlushie(p) } }
        // (The ADD A FLOOR card always sits in the same spot: the middle of the bottom row.)
        3 -> housePage().map { t -> t.label to { buyHouse(t) } }.let { it + List(HOUSES_PER_PAGE - it.size) { "" to {} } } + listOf("ADD A FLOOR" to { buyFloor() }, "ELEVATOR" to { buyElevator() })
        4 -> Homes.PAINTS.indices.map { i -> Homes.PAINT_NAMES[i] to { paintHouse(i) } }
        6, 7 -> decorPage().map { d -> d.label to { buyDecor(d) } }
        else -> listOf("STICKER PACK" to { buyStickerPack() })
    }

    /** Painting the house costs a few coins (going back to its normal look is free). */
    fun paintHouse(i: Int) {
        if (houseColor == i) { toast("Your house is already ${Homes.PAINT_NAMES[i].lowercase()}!"); return }
        val cost = if (i == 0) 0 else PAINT_COST
        if (coins < cost) { needToast(emptyList(), cost); return }
        coins -= cost
        houseColor = i
        sfx(Sound.POWERUP)
        toast(if (i == 0) "Your house looks like new again!" else "Your house is painted ${Homes.PAINT_NAMES[i]}!")
        save()
    }

    /** Three stickers you don't have yet (normal ones first). Returns how many you got. */
    private fun giveNewStickers(n: Int): List<Int> {
        val got = Stickers.all.indices.filter { !hasSticker(it) }.map { it to Stickers.all[it].style + Random.nextFloat() * 2.5f }.sortedBy { it.second }.map { it.first }.take(n)
        for (i in got) stickerOwned[i] = true
        return got
    }

    fun buyStickerPack() {
        if (stickerCount >= Stickers.all.size) { toast("You have every sticker! WOW!"); return }
        if (coins < STICKER_PACK_COST) { needToast(emptyList(), STICKER_PACK_COST); return }
        coins -= STICKER_PACK_COST
        val got = giveNewStickers(3)
        sfx(Sound.ONEUP)
        toast("New stickers: " + got.joinToString(", ") { Stickers.all[it].name } + " ($stickerCount/${Stickers.all.size})")
        save()
    }

    // ---------------------------------------------------------------- Playing with the Pokemon at home

    private val MON_BUTTONS = listOf("PET", "PLAY BALL", "TRAIN", "MINI GAME", "SWITCH")
    private fun monPanelX() = viewW / 2 - 150f
    private fun monButtonX(i: Int) = monPanelX() + 6f + i * 58f

    /** PET, PLAY BALL or TRAIN with the Pokemon: a little show, then it learns a bit. */
    fun playWithMon(what: String) {
        if (monPlayT > 0f) return
        if (monEnergy <= 0) { sfx(Sound.BUMP); toast("${mon.label} is sleepy. Play a level, then come back!"); return }
        if (monLevel() >= MON_MAX_LEVEL && what == "TRAIN") { toast("${mon.label} is already the top level! It still loves to play."); }
        monPlay = what
        monPlayT = 1.6f
        monEnergy--
        sfx(if (what == "TRAIN") Sound.BOSS_HIT else Sound.KICK)
        sound.sayAs(mon.label + "!", Voice.MON)
    }

    /** The next form this Pokemon can evolve into right now, if any. */
    fun evolveReady(): Mon? = mon.nextForm?.takeIf { monUnlocked(it) }

    /** Evolving: the Pokemon flashes white, then it's the next form! (SWITCH can always go back.) */
    fun evolve() {
        val next = evolveReady() ?: return
        if (monPlayT > 0f) return
        monPlay = "EVOLVE"
        monPlayT = 1.6f
        evolveTo = next
        sfx(Sound.POWERUP)
        sound.sayAs("What? ${mon.label} is evolving!", Voice.HERO)
    }
    private var evolveTo: Mon? = null

    private fun finishMonPlay() {
        if (monPlay == "EVOLVE") {
            evolveTo?.let { n ->
                val old = mon.label
                mon = n
                sfx(Sound.ONEUP)
                sound.sayAs("$old evolved into ${n.label}!", Voice.HERO)
                toast("$old evolved into ${n.label}! (SWITCH can change back any time.)")
                save()
            }
            evolveTo = null
            monPlay = ""
            return
        }
        val (xp, msg) = when (monPlay) {
            "PET" -> 1 to "${mon.label} loves you!"
            "BALL" -> 3 to "Great catch, ${mon.label}!"
            else -> 4 to "${mon.label} trained hard!"
        }
        toast(msg)
        gainXp(xp)
        // Sometimes the Pokemon finds a sticker while playing.
        if (monPlay != "PET" && Random.nextInt(5) == 0) {
            giveNewStickers(1).firstOrNull()?.let { toast("${mon.label} found a sticker: ${Stickers.all[it].name}!"); sfx(Sound.ONEUP) }
        }
        monPlay = ""
        save()
    }

    fun switchMon(m: Mon) {
        if (!monUnlocked(m)) {
            sfx(Sound.BUMP)
            toast(if (m == Mon.LUGIA) "LUGIA is a legendary! Beat level 3 to meet it."
                else if (m.root in NEW_MONS && clearedCount() < newMonNeeds(m.root)) "Beat ${newMonNeeds(m.root)} levels and ${m.root.label} joins you!"
                else "${m.root.label} has to reach LEVEL ${m.evolveLevel} to evolve into ${m.label}.")
            return
        }
        if (m == mon) { monSwitch = false; return }
        mon = m
        monSwitch = false
        sfx(Sound.POWERUP)
        sound.sayAs("${m.label}! I choose you!", Voice.HERO)
        toast("${m.label} is your partner now! (Lv${monLevel(m)})")
        save()
    }

    private fun monSwitchCard(i: Int): Pair<Float, Float> { val j = i % PAGE_MONS; return (monPanelX() + 6f + (j % 7) * 41.5f) to (62f + (j / 7) * 45f) }
    private val SWITCH_CARD = 39f
    /** The switch grid shows 21 Pokemon per page; the page follows the cursor. */
    private val PAGE_MONS = 21
    private fun monPage() = monCursor.coerceIn(0, Mon.entries.size - 1) / PAGE_MONS
    private fun monPages() = (Mon.entries.size + PAGE_MONS - 1) / PAGE_MONS

    private fun updateMonMenu(dt: Float, tapped: Boolean) {
        if (monPlayT > 0f) { monPlayT -= dt; if (monPlayT <= 0f) finishMonPlay(); return }
        if (monSwitch) {
            val n = Mon.entries.size
            if (nav.left) monCursor = (monCursor + n - 1) % n
            if (nav.right) monCursor = (monCursor + 1) % n
            if (nav.up) monCursor = (monCursor + n - 7).mod(n)
            if (nav.down) monCursor = (monCursor + 7) % n
            if (nav.a && stateT > 0.2f) { switchMon(Mon.entries[monCursor]); return }
            if (nav.b) { monSwitch = false; monCursor = 5; return }
            if (!tapped || input.tapX < 0f) return
            if (input.tapY in 38f..62f && input.tapX in monPanelX()..(monPanelX() + 300f)) {
                // The page arrows in the title row.
                val dir = if (input.tapX < monPanelX() + 150f) -1 else 1
                val page = (monPage() + dir + monPages()) % monPages()
                monCursor = minOf(page * PAGE_MONS, Mon.entries.size - 1)
                sfx(Sound.KICK)
                return
            }
            val first = monPage() * PAGE_MONS
            for (i in first until minOf(first + PAGE_MONS, Mon.entries.size)) {
                val (cx, cy) = monSwitchCard(i)
                if (input.tapX in cx..(cx + SWITCH_CARD) && input.tapY in cy..(cy + 43f)) { switchMon(Mon.entries[i]); return }
            }
            monSwitch = false
            return
        }
        if (nav.left) monCursor = (monCursor + MON_BUTTONS.size - 1) % MON_BUTTONS.size
        if (nav.right) monCursor = (monCursor + 1) % MON_BUTTONS.size
        if (nav.a && stateT > 0.2f) { monButton(monCursor); return }
        if (nav.b) { monMenu = false; return }
        if (nav.up && evolveReady() != null) { evolve(); return }
        if (!tapped || input.tapX < 0f) return
        if (evolveReady() != null && input.tapX in (monPanelX() + 206f)..(monPanelX() + 294f) && input.tapY in 80f..104f) { evolve(); return }
        for (i in MON_BUTTONS.indices) {
            val bx = monButtonX(i)
            if (input.tapX in bx..(bx + 54f) && input.tapY in 168f..194f) { monButton(i); return }
        }
        if (input.tapX !in monPanelX()..(monPanelX() + 300f) || input.tapY !in 40f..200f) monMenu = false
    }

    private fun monButton(i: Int) {
        when (i) {
            0 -> playWithMon("PET")
            1 -> playWithMon("BALL")
            2 -> playWithMon("TRAIN")
            3 -> startPark()
            else -> { monSwitch = true; monCursor = mon.ordinal; sfx(Sound.KICK) }
        }
    }

    private fun renderMonMenu(gfx: Gfx) {
        val px = monPanelX()
        gfx.rect(0f, 22f, viewW, VIEW_H - 22, 0x88000000.toInt())
        gfx.rect(px - 2, 38f, 304f, 164f, 0xFF1A3A2A.toInt())
        gfx.rect(px, 40f, 300f, 160f, 0xFFE8F4E0.toInt())
        if (monSwitch) {
            gfx.shadowText("SWITCH POKEMON", px + 150, 56f, 10f, 0xFFFFD21F.toInt(), 1)
            val cw = SWITCH_CARD
            if (monPages() > 1) {
                gfx.shadowText("◀", px + 20, 56f, 12f, white, 1)
                gfx.shadowText("▶", px + 280, 56f, 12f, white, 1)
                gfx.text("page ${monPage() + 1}/${monPages()}", px + 240, 56f, 5f, 0xFF2A3A2A.toInt(), 2)
            }
            val first = monPage() * PAGE_MONS
            Mon.entries.forEachIndexed { i, m ->
                if (i !in first until first + PAGE_MONS) return@forEachIndexed
                val (cx, cy) = monSwitchCard(i)
                val open = monUnlocked(m)
                gfx.rect(cx, cy, cw, 43f, if (m == mon) 0xFFFFD21F.toInt() else argb(255, m.color))
                gfx.rect(cx + 1.5f, cy + 1.5f, cw - 3, 40f, 0xEEFFFFFF.toInt())
                TeamArt.mon(ScaledGfx(gfx, 0.8f, cx + cw / 2, cy + 29f), m, 0f, 0f, time + i, 1, false)
                gfx.text(m.label, cx + cw / 2, cy + 35f, minOf(5f, (cw - 3) / (m.label.length * 0.62f)), 0xFF202020.toInt(), 1)
                gfx.text("Lv${monLevel(m)}" + if (m.stage > 1) " ★".repeat(m.stage - 1) else "", cx + cw / 2, cy + 41f, 4.5f, 0xFF1E5A1E.toInt(), 1)
                if (!open) {
                    gfx.rect(cx, cy, cw, 43f, 0xAA101018.toInt())
                    gfx.shadowText("LOCKED", cx + cw / 2, cy + 20f, 6f, 0xFFFFD21F.toInt(), 1)
                    gfx.text(when {
                        m == Mon.LUGIA -> "beat level 3"
                        m.root in NEW_MONS && clearedCount() < newMonNeeds(m.root) -> "beat ${newMonNeeds(m.root)} lvls"
                        else -> "${m.root.label} Lv${m.evolveLevel}"
                    }, cx + cw / 2, cy + 29f, 4.5f, white, 1)
                }
                if (padConnected && monCursor == i) padFrame(gfx, cx - 2, cy - 2, cw + 4, 47f)
            }
            return
        }
        val lv = monLevel()
        gfx.shadowText("${mon.label}   LEVEL $lv" + if (lv >= MON_MAX_LEVEL) " (TOP!)" else "", px + 150, 56f, 10f, argb(255, mon.color) and 0xFF7F7F7F.toInt() or 0xFF000000.toInt(), 1)
        // Experience bar
        gfx.rect(px + 60, 62f, 180f, 7f, 0xFF3A3A48.toInt())
        gfx.rect(px + 60, 62f, 180f * monXpFraction(), 7f, 0xFF4AD8FF.toInt())
        gfx.text("SUPER: ${superTime().toInt()}s charge, ${superDmg(10) * 10}% power", px + 292, 160f, 6f, 0xFF2A3A2A.toInt(), 2)
        gfx.text("Play energy:", px + 10, 160f, 6.5f, 0xFF2A3A2A.toInt())
        for (k in 0 until 5) TeamArt.heart(gfx, px + 62 + k * 10f, 153f, 8f, if (k < monEnergy) 0xFFFF6A9A.toInt() else 0xFF8A8A9A.toInt())
        // The Pokemon, big, doing whatever you're playing.
        val k = 1.6f - monPlayT
        val hop = when (monPlay) { "BALL" -> -abs(sin(k * 6f)) * 14f; "TRAIN" -> sin(k * 30f) * 2f; else -> sin(time * 3) * 1.5f }
        val mx = px + 150f + (if (monPlay == "TRAIN") sin(k * 10f) * 6f else 0f)
        TeamArt.mon(ScaledGfx(gfx, 2f, mx, 146f + hop), mon, 0f, 0f, time, 1, monPlay.isNotEmpty(), monPlay == "TRAIN")
        if (monPlay == "EVOLVE") {
            // Flashing white, and the new form showing through more and more.
            if ((k * 10).toInt() % 2 == 0) gfx.oval(mx - 34, 90f, 68f, 60f, argb((k / 1.6f * 255).toInt().coerceIn(0, 255), 0xFFFFFF))
            if (k > 0.8f) evolveTo?.let { TeamArt.mon(ScaledGfx(gfx, 2f, mx, 146f), it, 0f, 0f, time, 1, true) }
        }
        evolveReady()?.let { n ->
            if (monPlay.isEmpty()) {
                val pulse = (sin(time * 6) * 30).toInt()
                gfx.rect(px + 206, 80f, 88f, 24f, argb(200 + pulse, 0xFF6AA8))
                gfx.shadowText("EVOLVE!", px + 250, 92f, 9f, 0xFFFFFFFF.toInt(), 1)
                gfx.text("into ${n.label}", px + 250, 101f, 5.5f, 0xFFFFFFFF.toInt(), 1)
            }
        }
        when (monPlay) {
            "PET" -> for (i in 0 until 4) TeamArt.heart(gfx, mx - 30 + i * 18f, 120f - k * 30f - (i % 2) * 8f, 9f, 0xFFFF6A9A.toInt())
            "BALL" -> {
                val bx = mx - 60f + (k / 1.6f) * 120f
                val by = 96f - sin(k / 1.6f * 3.14f) * 14f
                gfx.oval(bx - 6, by - 6, 12f, 12f, 0xFFE84A4A.toInt())
                gfx.rect(bx - 6, by - 0.5f, 12f, 1.5f, 0xFF202020.toInt())
                gfx.oval(bx - 6, by, 12f, 6f, 0xFFF4F4F4.toInt())
                gfx.oval(bx - 2, by - 2, 4f, 4f, 0xFFFFFFFF.toInt())
            }
            "TRAIN" -> {
                val tx = px + 230f
                gfx.rect(tx, 112f, 4f, 34f, 0xFF7A4A20.toInt())
                gfx.oval(tx - 12 + sin(k * 25f) * 2f, 98f, 28f, 28f, 0xFFE84A4A.toInt())
                gfx.oval(tx - 6 + sin(k * 25f) * 2f, 104f, 16f, 16f, 0xFFFFFFFF.toInt())
                gfx.oval(tx - 1 + sin(k * 25f) * 2f, 109f, 6f, 6f, 0xFFE84A4A.toInt())
                if ((k * 4).toInt() % 2 == 0) gfx.shadowText("POW!", tx, 94f, 10f, 0xFFFFD21F.toInt(), 1)
            }
        }
        MON_BUTTONS.forEachIndexed { i, b ->
            val bx = monButtonX(i)
            val c = when { i == 4 -> 0xFF3A6AC8.toInt(); i == 3 -> 0xFFFF8A2A.toInt(); monEnergy > 0 -> 0xFF2EB84A.toInt(); else -> 0xFF8A8A9A.toInt() }
            gfx.rect(bx, 168f, 54f, 26f, 0xFF0A1A10.toInt())
            gfx.rect(bx + 2, 170f, 50f, 22f, c)
            gfx.text(b, bx + 27f, 185f, 6.5f, white, 1)
            if (padConnected && monCursor == i) padFrame(gfx, bx - 2, 166f, 58f, 30f)
        }
    }

    private fun updateHome(dt: Float, tapped: Boolean, close: Boolean, actionE: Boolean) {
        if (close) {
            when { home3d -> exit3d(); elevatorMenu -> elevatorMenu = false; building -> toggleBuild(); inGarden -> leaveGarden(); monMenu && monSwitch -> monSwitch = false; monMenu -> monMenu = false; homeShop -> homeShop = false; plushBook -> plushBook = false; else -> leaveHome() }
            return
        }
        if (homeHopT > 0f) homeHopT -= dt
        if (monMenu) { updateMonMenu(dt, tapped); return }
        if (elevatorMenu) { updateElevator(tapped); return }
        if (homeShop) { padShop(); updateShop(tapped); return }
        if (plushBook) {
            if (nav.left) bookPage = (bookPage + PLUSH_PAGES - 1) % PLUSH_PAGES
            if (nav.right) bookPage = (bookPage + 1) % PLUSH_PAGES
            if (nav.a || nav.b) { plushBook = false; return }
            updateBook(tapped); return
        }
        if (home3d) {
            if (tapped && input.tapX >= 0f && house3d.tap(input.tapX, input.tapY, viewW)) return
            house3d.update(dt, input, actionE, jumpE3d)
            return
        }
        if (inGarden) { updateGarden(dt, tapped, actionE); return }
        if (nav.up) { enterGarden(); return }
        if (nav.rb) { toggleDecorate(); return }
        if (nav.lb) { enter3d(); return }
        if (tapped && input.tapX >= 0f && input.tapY < 22f && stateT > 0.2f) {
            if (input.tapX > viewW - 84f) { toggleDecorate(); return }
            if (input.tapX in (viewW - 122f)..(viewW - 88f)) { enter3d(); return }
            if (input.tapX in (viewW - 176f)..(viewW - 126f)) { enterGarden(); return }
            if (input.tapX in (viewW - 214f)..(viewW - 180f)) { homeShop = true; sfx(Sound.KICK); return }
            if (input.tapX in (viewW - 254f)..(viewW - 216f)) { toggleBuild(); return }
        }
        if (building) { updateBuild(dt, tapped, actionE); return }
        if (nav.a) { homeAction(homeTarget()); return }
        // Walk around the room.
        val speed = 110f
        if (input.left) { homeX -= speed * dt; homeFacing = -1; homeAnim += dt }
        if (input.right) { homeX += speed * dt; homeFacing = 1; homeAnim += dt }
        homeX = homeX.coerceIn(Homes.DOOR_X + 4f, Homes.ROOM_W - 20f)
        if (actionE) { homeAction(homeTarget()); return }
        if (!tapped || stateT < 0.3f) return
        val x = input.tapX
        val y = input.tapY
        // Tap a spot in the room to use it (walks you there).
        if (y in 30f..(Homes.FLOOR_Y + 4f)) {
            val rx = x + homeCam()
            val old = homeX
            homeX = rx.coerceIn(Homes.DOOR_X + 4f, Homes.ROOM_W - 20f)
            val t = homeTarget()
            if (t != null && !(input.left || input.right)) homeAction(t) else homeX = old
        }
    }

    fun enterGarden() {
        if (decorating) toggleDecorate()
        if (building) toggleBuild()
        inGarden = true
        homeFloor = 0
        homeX = Garden.DOOR_X + 44f
        homeFacing = 1
        sfx(Sound.KICK)
        toast("The GARDEN! Get SEEDS, PLANT them in the dirt, WATER them, then PICK!")
    }

    fun leaveGarden() {
        inGarden = false
        homeX = Homes.DOOR_X + 50f
        homeFacing = 1
        sfx(Sound.KICK)
    }

    /** What the action button does where you're standing (in the house or the garden). */
    private fun hereTarget(): String? = if (inGarden) gardenTarget() else homeTarget()

    /** What the action button does in the garden or backyard. */
    private fun gardenTarget(): String? = when {
        decorating -> if (carryingDecor != null) "PLACE" else if (decorAt(homeX) != null) "MOVE" else null
        homeX >= Garden.YARD_X -> yardTarget(homeX)
        else -> garden.target(homeX)
    }

    private fun gardenAction(t: String?) {
        when (t) {
            null -> {}
            "INSIDE" -> leaveGarden()
            "MOVE" -> pickUpDecor()
            "PLACE" -> placeDecor(homeX)
            "SWIM", "RELAX", "BOUNCE", "SLIDE", "SWING", "COOK" -> yardAction(t)
            else -> garden.action(t, homeX)
        }
    }

    private fun updateGarden(dt: Float, tapped: Boolean, actionE: Boolean) {
        if (nav.up) { leaveGarden(); return }
        if (nav.rb) { toggleDecorate(); return }
        if (tapped && input.tapX >= 0f && input.tapY < 22f && stateT > 0.2f) {
            if (input.tapX in (viewW - 176f)..(viewW - 126f)) { leaveGarden(); return }
            if (input.tapX > viewW - 84f) { toggleDecorate(); return }
            if (input.tapX in (viewW - 214f)..(viewW - 180f)) { homeShop = true; shopTab = SHOP_TABS.indexOf("YARD"); shopPage = 0; sfx(Sound.KICK); return }
        }
        if (nav.a) { gardenAction(gardenTarget()); return }
        val speed = 110f
        if (input.left) { homeX -= speed * dt; homeFacing = -1; homeAnim += dt }
        if (input.right) { homeX += speed * dt; homeFacing = 1; homeAnim += dt }
        homeX = homeX.coerceIn(Garden.DOOR_X + 4f, Garden.WIDTH - 20f)
        if (actionE) { gardenAction(gardenTarget()); return }
        if (!tapped || stateT < 0.3f || input.tapX < 0f) return
        // Tap a spot in the garden to use it (walks you there).
        if (input.tapY in 30f..(Homes.FLOOR_Y + 4f)) {
            val old = homeX
            homeX = (input.tapX + homeCam()).coerceIn(Garden.DOOR_X + 4f, Garden.WIDTH - 20f)
            val t = gardenTarget()
            if (t != null && !(input.left || input.right)) gardenAction(t) else homeX = old
        }
    }

    /** Shopping with a controller: move around the cards, A buys, B closes, L/R change tabs. */
    private fun padShop() {
        val n = shopItems().size
        if (nav.lb || nav.rb) { shopTab = (shopTab + (if (nav.rb) 1 else SHOP_TABS.size - 1)) % SHOP_TABS.size; shopPage = 0; shopCursor = 0; sfx(Sound.KICK) }
        val pages = shopPages()
        val paged = shopTab == 0 || shopTab == 2 || shopTab == 3 || shopTab >= 6
        when {
            nav.left && shopCursor % 3 == 0 && paged -> { shopPage = (shopPage + pages - 1) % pages; shopCursor += 2 }
            nav.right && (shopCursor % 3 == 2 || shopCursor == n - 1) && paged -> { shopPage = (shopPage + 1) % pages; shopCursor -= shopCursor % 3 }
            nav.left -> shopCursor -= 1
            nav.right -> shopCursor += 1
            nav.up -> shopCursor -= 3
            nav.down -> shopCursor += 3
        }
        shopCursor = shopCursor.coerceIn(0, maxOf(0, shopItems().size - 1))
        if (nav.a && stateT > 0.2f) shopItems().getOrNull(shopCursor)?.second?.invoke()
        if (nav.b) homeShop = false
    }

    /** The FURNITURE tab: each floor's furniture, 12 to a page. */
    private fun furniturePages(): List<List<Furniture>> =
        (0 until houseFloors).flatMap { fl -> Furniture.entries.filter { it.floor == fl }.chunked(PLUSH_PER_PAGE) }
    private fun furniturePage() = furniturePages().getOrElse(shopPage) { emptyList() }

    /** The FURNITURE tab has pages for each floor; PLUSHIES has 12 to a page. */
    private fun shopPages() = when (shopTab) {
        0 -> furniturePages().size
        3 -> (HouseType.entries.size + HOUSES_PER_PAGE - 1) / HOUSES_PER_PAGE
        6, 7 -> (decorTab(shopTab).size + PLUSH_PER_PAGE - 1) / PLUSH_PER_PAGE
        else -> (Plushies.all.size + PLUSH_PER_PAGE - 1) / PLUSH_PER_PAGE
    }

    /** The HOUSES tab: 10 kinds of house to a page, then the ADD A FLOOR card. */
    private val HOUSES_PER_PAGE = 10
    private fun housePage() = HouseType.entries.drop(shopPage * HOUSES_PER_PAGE).take(HOUSES_PER_PAGE)

    private fun updateShop(tapped: Boolean) {
        // (A controller button is a "tap" at no spot: only real taps count here.)
        if (!tapped || stateT < 0.2f || input.tapX < 0f) return
        val x = input.tapX
        val y = input.tapY
        val tw = shopCardW() * 3 / SHOP_TABS.size
        val (left, _) = shopCard(0)
        if (y in 30f..46f) {
            val t = ((x - left) / tw).toInt()
            if (t in SHOP_TABS.indices) { shopTab = t; shopPage = 0; sfx(Sound.KICK) }
            return
        }
        if ((shopTab == 2 || shopTab == 0 || shopTab == 3 || shopTab >= 6) && y > VIEW_H - 22f) {
            val pages = shopPages()
            shopPage = if (x < viewW / 2) (shopPage + pages - 1) % pages else (shopPage + 1) % pages
            sfx(Sound.KICK)
            return
        }
        val items = shopItems()
        for (i in items.indices) {
            val (cx, cy) = shopCard(i)
            val wide = if (shopTab == 5) shopCardW() * 3 + 8f else shopCardW()
            if (x in cx..(cx + wide) && y in cy..(cy + (if (shopTab == 5) 84f else 40f))) {
                if (items[i].first.isEmpty()) return
                if (confirmTap("shop:$shopTab:$shopPage:$i", items[i].first, "buy")) items[i].second()
                return
            }
        }
        homeShop = false
    }

    private fun updateBook(tapped: Boolean) {
        if (!tapped || stateT < 0.2f || input.tapX < 0f) return
        val x = input.tapX
        val y = input.tapY
        if (y > VIEW_H - 22f) {
            bookPage = if (x < viewW / 2) (bookPage + PLUSH_PAGES - 1) % PLUSH_PAGES else (bookPage + 1) % PLUSH_PAGES
            sfx(Sound.KICK)
            return
        }
        for (k in 0 until 25) {
            val id = bookPage * 25 + k
            if (id >= Plushies.all.size) break
            val (bx, by) = bookCard(k)
            if (x in bx..(bx + bookW()) && y in by..(by + 34f)) {
                val p = Plushies.all[id]
                if (hasPlushie(p)) sound.sayAs(p.name.lowercase(), Voice.MON)
                toast(if (hasPlushie(p)) p.name else "??? (find it, or buy it in the SHOP for ${p.price} coins${if (p.boss) " - or beat its boss" else ""})")
                return
            }
        }
        plushBook = false
    }

    private fun bookW() = min(76f, (viewW - 30f) / 5f - 4f)
    private fun bookCard(k: Int): Pair<Float, Float> {
        val w = bookW()
        val left = viewW / 2 - (w + 4f) * 5 / 2
        return (left + (k % 5) * (w + 4f)) to (30f + (k / 5) * 37f)
    }

    private fun renderHome(gfx: Gfx) {
        if (home3d) {
            house3d.render(gfx, viewW, VIEW_H)
            if (toastT > 0) {
                gfx.rect(20f, 40f, viewW - 40, 16f, 0xDD101828.toInt())
                gfx.text(toast, viewW / 2, 51f, 7f, white, 1)
            }
            return
        }
        val cam = homeCam()
        val ox = -cam
        if (inGarden) { garden.draw(gfx, ox, viewW, VIEW_H, time); drawDecor(gfx, ox, Decor.YARD) } else drawRoomInside(gfx, ox)
        // You (and your Pokemon) walking around.
        drawHomeHero(gfx, ox)
        val target = hereTarget()
        if (target != null && !homeShop && !plushBook && !monMenu) {
            val what = if (inGarden && target == "PLANT") "PLANT ${garden.seed.label}" else target
            gfx.shadowText(what + "?", homeX + ox, Homes.FLOOR_Y + 22 + sin(time * 5) * 1.5f, 8f, 0xFFFFF2C8.toInt(), 1)
        }
        // Top bar
        gfx.rect(0f, 0f, viewW, 22f, 0xCC1A1008.toInt())
        gfx.shadowText(if (inGarden) "MY GARDEN" else "MY ${houseType.label}" + if (houseFloors > 1) " ${homeFloor + 1}F" else "", 8f, 15f, 9f, 0xFFFFD21F.toInt())
        gfx.sprite(com.roccoquest.game.Sprites.coin, 120f, 5f, w = 8f, h = 11f)
        gfx.shadowText("x$coins", 130f, 15f, 8f, white)
        if (!inGarden) {
            gfx.rect(viewW - 254f, 2f, 38f, 18f, if (building) 0xFFF4C83A.toInt() else 0xFF8A6A3A.toInt())
            gfx.text(if (building) "DONE" else "BUILD", viewW - 235f, 15f, 7f, if (building) 0xFF2A1808.toInt() else white, 1)
        }
        // SHOP (works on every floor), GARDEN, DECORATE and 3D buttons
        gfx.rect(viewW - 214f, 2f, 34f, 18f, 0xFFE8A82A.toInt())
        gfx.text("SHOP", viewW - 197f, 15f, 7f, white, 1)
        if (inGarden) {
            gfx.rect(viewW - 84f, 2f, 80f, 18f, if (decorating) 0xFFE8A82A.toInt() else 0xFF7A4A20.toInt())
            gfx.text(if (decorating) "DONE" else "DECORATE", viewW - 44f, 15f, 7.5f, white, 1)
        }
        gfx.rect(viewW - 176f, 2f, 50f, 18f, 0xFF5AB84A.toInt())
        gfx.text(if (inGarden) "INSIDE" else "GARDEN", viewW - 151f, 15f, 7f, white, 1)
        if (!inGarden) {
            gfx.rect(viewW - 84f, 2f, 80f, 18f, if (decorating) 0xFFE8A82A.toInt() else 0xFF7A4A20.toInt())
            gfx.text(if (decorating) "DONE" else "DECORATE", viewW - 44f, 15f, 7.5f, white, 1)
            gfx.rect(viewW - 122f, 2f, 34f, 18f, 0xFF2EB84A.toInt())
            gfx.text("3D", viewW - 105f, 15f, 9f, white, 1)
        }
        if (babyZapT > 0f) {
            // The Baby Machine's zap: a flash of pink sparkles.
            gfx.rect(0f, 0f, viewW, VIEW_H, argb((babyZapT / 1.2f * 160).toInt().coerceIn(0, 255), 0xFFB8E0))
            for (k in 0 until 12) gfx.shadowText("★", homeX + ox + sin(k * 1.7f + time * 4) * (40f + k * 3), 120f + kotlin.math.cos(k * 2.3f) * 40f, 8f, 0xFFFFE14D.toInt(), 1)
        }
        // Controls: walk left/right, and the action button.
        TeamControls.layout(viewW)
        if (!homeShop && !plushBook && !monMenu && !elevatorMenu) TeamControls.drawHome(gfx, input, if (building) "BUILD" else target ?: "", time)
        if (building && !homeShop) renderBuildPalette(gfx)
        if (homeShop) renderShop(gfx)
        if (monMenu) renderMonMenu(gfx)
        if (plushBook) renderPlushBook(gfx, true)
        if (elevatorMenu) renderElevator(gfx)
        renderHomeToast(gfx)
    }

    private fun drawRoomInside(gfx: Gfx, ox: Float) {
        Homes.drawRoom(gfx, houseType, ox, viewW, VIEW_H, housePaint)
        Homes.drawWindow(gfx, ox + Homes.BED_X - 2f, 40f, time)
        if (homeFloor == 0) Homes.drawDoor(gfx, ox + Homes.DOOR_X) else Homes.drawStairs(gfx, ox + Homes.DOOR_X - 6f, false)
        drawBlocks(gfx, ox)
        if (hasElevator) Homes.drawElevator(gfx, ox + Homes.STAIRS_X - 8f, time)
        else if (houseFloors > homeFloor + 1) Homes.drawStairs(gfx, ox + Homes.STAIRS_X - 4f, true)
        else if (houseFloors < Homes.MAX_FLOORS) Homes.drawBuildSign(gfx, ox + Homes.STAIRS_X)
        if (homeFloor == 0) Homes.drawShop(this, gfx, ox + Homes.SHOP_X, time)
        // Rugs first, then everything else, each wherever it was put.
        for (layerPass in listOf(2, 1, 0)) for (f in Furniture.entries) {
            if (Homes.layer(f) != layerPass || !hasFurniture(f) || furnFloor(f) != homeFloor || f == carrying) continue
            drawFurnitureAt(gfx, f, furnX(f), ox)
        }
        drawDecor(gfx, ox, homeFloor)
        if (homeFloor == 0) {
            Homes.drawMonMat(gfx, ox, time)
            if (hasBed()) Homes.drawBed(gfx, bedStyle, ox + Homes.BED_X)
            drawShelvesWithPlushies(gfx, ox)
        } else {
            drawShelvesWithPlushies(gfx, ox)
            if (Furniture.entries.none { furnFloor(it) == homeFloor && hasFurniture(it) }) {
                gfx.shadowText("An empty floor! Buy furniture for it in the SHOP downstairs.", viewW / 2, 100f, 8f, 0xFFFFF2C8.toInt(), 1)
            }
        }
        // The furniture you're carrying (green: it fits here, red: no room).
        carrying?.let { f ->
            val fx = homeX - Homes.width(f) / 2
            Homes.drawFurniture(gfx, f, ox + fx - Homes.spot(f), time)
            val ok = fits(f, fx.coerceAtLeast(0f).toInt().toFloat(), homeFloor)
            gfx.rect(fx + ox, Homes.FLOOR_Y - 4f, Homes.width(f), 4f, if (ok) 0xCC3CE070.toInt() else 0xCCFF4A4A.toInt())
        }
        if (decorating) gfx.shadowText(if (carrying != null || carryingDecor != null) "Walk to a spot and tap PLACE" else "DECORATING: walk to furniture and tap MOVE", viewW / 2, 34f, 7f, 0xFFFFE14D.toInt(), 1)
    }

    private fun drawHomeHero(gfx: Gfx, ox: Float) {
        val step = if ((homeAnim * 8).toInt() % 2 == 0) 0 else 1
        val hero = if (heroOut == Who.BLOCKY) TeamArt.blocky(power, step == 1) else TeamArt.rocco(power, if (input.left || input.right) 1 + step else 0)
        val hop = if (homeHopT > 0f) -sin(homeHopT / 0.8f * 3.1416f) * 40f else 0f
        val feet = Homes.FLOOR_Y + 54
        val hx = homeX + ox
        val mx = homeX + ox - homeFacing * 26f
        val hg = if (babyMode) Baby.shrink(gfx, hx, feet + hop) else gfx
        val sc = if (heroOut == Who.BLOCKY) 1f else ROCCO_SCALE
        hg.sprite(hero, hx - 8 * sc, feet + hop - 24 * sc, flipX = homeFacing < 0, w = 16f * sc, h = 24f * sc)
        if (babyMode) Baby.dress(gfx, hx, feet + hop, homeFacing, false)
        // Construction Rocco wears a hard hat.
        if (building) Build.hardHat(gfx, hx, feet + hop - 24f * sc - (if (babyMode) -7f else 0f))
        TeamArt.mon(if (babyMode) Baby.shrink(gfx, mx, feet) else gfx, mon, mx, feet, time, homeFacing, false)
        if (babyMode) Baby.dress(gfx, mx, feet, homeFacing, true)
    }

    private fun renderHomeToast(gfx: Gfx) {
        if (toastT > 0) {
            gfx.rect(20f, VIEW_H - 46, viewW - 40, 18f, 0xDD101828.toInt())
            gfx.text(toast, viewW / 2, VIEW_H - 33, 8f, white, 1)
        }
    }

    /** A blinking yellow frame showing what the controller is pointing at. */
    private fun padFrame(gfx: Gfx, x: Float, y: Float, w: Float, h: Float) {
        val c = if ((time * 4).toInt() % 2 == 0) 0xFFFFD21F.toInt() else 0xFFFFF2A0.toInt()
        gfx.rect(x, y, w, 2f, c); gfx.rect(x, y + h - 2, w, 2f, c)
        gfx.rect(x, y, 2f, h, c); gfx.rect(x + w - 2, y, 2f, h, c)
    }

    private fun renderShop(gfx: Gfx) {
        gfx.rect(0f, 22f, viewW, VIEW_H - 22, 0xAA000000.toInt())
        val cw = shopCardW()
        val (left, _) = shopCard(0)
        gfx.rect(left - 6, 26f, cw * 3 + 20f, VIEW_H - 28f, 0xFF3A2410.toInt())
        gfx.rect(left - 4, 28f, cw * 3 + 16f, VIEW_H - 32f, 0xFFB98A52.toInt())
        val tw = cw * 3 / SHOP_TABS.size
        SHOP_TABS.forEachIndexed { i, n ->
            val tx = left + i * tw
            gfx.rect(tx + 1, 30f, tw - 2, 16f, if (i == shopTab) 0xFF7A4A20.toInt() else 0xFF9A6A3A.toInt())
            gfx.text(n, tx + tw / 2, 41f, 7f, if (i == shopTab) 0xFFFFF2C8.toInt() else 0xFFE0C8A0.toInt(), 1)
        }
        when (shopTab) {
            0 -> furniturePage().forEachIndexed { i, f ->
                val (cx, cy) = shopCard(i)
                val locked = clearedCount() < f.unlock
                shopCardBg(gfx, cx, cy, hasFurniture(f), !locked && !hasFurniture(f) && has(f.costs, f.coins))
                gfx.text(f.label, cx + 4, cy + 11, 7f, 0xFF2A1808.toInt())
                when {
                    hasFurniture(f) -> gfx.text("OWNED!", cx + 4, cy + 30, 8f, 0xFF1E4A1E.toInt())
                    locked -> gfx.text("LOCKED: beat ${f.unlock} levels", cx + 4, cy + 30, 5.5f, 0xFF8A1010.toInt())
                    else -> priceTag(gfx, cx + 4, cy + 21, f.costs, f.coins)
                }
                val dy = when (f) {
                    Furniture.RUG, Furniture.TOY_TRAIN -> 22f
                    Furniture.DISCO_BALL, Furniture.CHANDELIER, Furniture.WALL_CLOCK -> 52f
                    else -> 38f
                }
                if (f == Furniture.STAR_LIGHTS) {
                    for (k in 0 until 5) gfx.oval(cx + cw - 44f + k * 8f, cy + 20f + (k % 2) * 3f, 4f, 4f, solidRainbow(time + k * 0.2f, 0f))
                } else {
                    val pv = ScaledGfx(gfx, 0.3f, cx + cw - 36f - Homes.spot(f) * 0.3f, cy + dy - Homes.FLOOR_Y * 0.3f)
                    Homes.drawFurniture(pv, f, 0f, time)
                }
            }.also {
                val fl = furniturePage().firstOrNull()?.floor ?: 0
                gfx.shadowText("< PREV    ${if (fl == 0) "DOWNSTAIRS" else "FLOOR ${fl + 1}"}  (${shopPage + 1}/${shopPages()})    NEXT >", viewW / 2, VIEW_H - 8f, 8f, white, 1)
                if (houseFloors == 1) gfx.shadowText("Build more floors (HOUSES tab) for even more furniture!", viewW / 2, VIEW_H - 20f, 6f, 0xFFFFF2C8.toInt(), 1)
            }
            6, 7 -> decorPage().forEachIndexed { i, d ->
                val (cx, cy) = shopCard(i)
                val n = decor.count { it.item == d }
                shopCardBg(gfx, cx, cy, false, coins >= d.coins)
                gfx.text(d.label, cx + 4, cy + 11, if (d.label.length > 14) 5.5f else 7f, 0xFF2A1808.toInt())
                priceTag(gfx, cx + 4, cy + 21, emptyList(), d.coins)
                if (n > 0) gfx.text("you have $n", cx + 4, cy + 37, 5f, 0xFF1E4A1E.toInt())
                // A little picture: scaled to fit the card.
                val k = minOf(0.32f, 40f / d.w)
                val oy = when (d.layer) {
                    1 -> cy + 4f - 40f * k // wall things hang from y = 40
                    2 -> cy + 22f - Homes.FLOOR_Y * k // flat things lie just below the floor line
                    else -> cy + 37f - Homes.FLOOR_Y * k
                }
                Decor.draw(ScaledGfx(gfx, k, cx + cw - 6f - d.w * k, oy), d, 0f, Homes.FLOOR_Y, time)
            }.also { gfx.shadowText("< PREV      PAGE ${shopPage + 1}/${shopPages()}      NEXT >", viewW / 2, VIEW_H - 8f, 8f, white, 1) }
            1 -> BedStyle.entries.forEachIndexed { i, b ->
                val (cx, cy) = shopCard(i)
                val owned = ownsBed(b)
                shopCardBg(gfx, cx, cy, owned, !owned && coins >= b.coins)
                gfx.text(b.label, cx + 4, cy + 11, 7f, 0xFF2A1808.toInt())
                when {
                    owned && bedStyle == b && hasBed() -> gfx.text("IN YOUR ROOM", cx + 4, cy + 30, 7f, 0xFF1E4A1E.toInt())
                    owned -> gfx.text("TAP TO USE", cx + 4, cy + 30, 7f, 0xFF1E4A1E.toInt())
                    else -> priceTag(gfx, cx + 4, cy + 21, emptyList(), b.coins)
                }
                Homes.drawBed(ScaledGfx(gfx, 0.5f, cx + cw - 34f, cy + 38f - Homes.FLOOR_Y * 0.5f), b, 0f)
            }
            2 -> {
                Plushies.all.drop(shopPage * PLUSH_PER_PAGE).take(PLUSH_PER_PAGE).forEachIndexed { i, p ->
                    val (cx, cy) = shopCard(i)
                    val owned = hasPlushie(p)
                    shopCardBg(gfx, cx, cy, owned, !owned && coins >= p.price)
                    gfx.text(p.name, cx + 4, cy + 11, if (p.name.length > 14) 5.5f else 7f, 0xFF2A1808.toInt())
                    if (owned) gfx.text("GOT IT!", cx + 4, cy + 30, 8f, 0xFF1E4A1E.toInt()) else priceTag(gfx, cx + 4, cy + 21, emptyList(), p.price)
                    Plushies.draw(this, gfx, p, cx + cw - 16f, cy + 38f, 22f)
                }
                gfx.shadowText("< PREV      PAGE ${shopPage + 1}/${shopPages()}      NEXT >", viewW / 2, VIEW_H - 8f, 8f, white, 1)
            }
            4 -> Homes.PAINTS.forEachIndexed { i, p ->
                val (cx, cy) = shopCard(i)
                val mine = houseColor == i
                shopCardBg(gfx, cx, cy, mine, !mine && coins >= PAINT_COST)
                gfx.text(Homes.PAINT_NAMES[i], cx + 4, cy + 11, 7f, 0xFF2A1808.toInt())
                when {
                    mine -> gfx.text("YOUR COLOR", cx + 4, cy + 30, 7f, 0xFF1E4A1E.toInt())
                    i == 0 -> gfx.text("FREE", cx + 4, cy + 30, 7f, 0xFF1E4A1E.toInt())
                    else -> priceTag(gfx, cx + 4, cy + 21, emptyList(), PAINT_COST)
                }
                Homes.drawHouse(ScaledGfx(gfx, 0.32f, cx + cw - 30f, cy + 6f), houseType, 0f, 72f, time, p)
            }
            5 -> {
                val (cx, cy) = shopCard(0)
                gfx.rect(cx, cy, cw * 3 + 8f, 84f, if (coins >= STICKER_PACK_COST) 0xFFE8C88A.toInt() else 0xFFA8885A.toInt())
                gfx.text("STICKER PACK: 3 new stickers!", cx + 6, cy + 14, 8f, 0xFF2A1808.toInt())
                priceTag(gfx, cx + 6, cy + 22, emptyList(), STICKER_PACK_COST)
                gfx.text("Your sticker book: $stickerCount / ${Stickers.all.size}", cx + 6, cy + 46, 7f, 0xFF2A1808.toInt())
                gfx.text("Every sticker comes in NORMAL, SILVER, GOLD, RAINBOW and MIDNIGHT!", cx + 6, cy + 58, 5.5f, 0xFF2A1808.toInt())
                gfx.text("Tap here to buy a pack.", cx + 6, cy + 72, 6f, 0xFF1E4A1E.toInt())
                for (k in 0 until 3) Stickers.drawCard(this, gfx, Stickers.BASE * (k + 1) + k * 7, cx + cw * 3 - 86f + k * 30f, cy + 6f, 26f, 34f, true, false)
            }
            else -> housePage().forEachIndexed { i, t ->
                val (cx, cy) = shopCard(i)
                val mine = houseType == t
                shopCardBg(gfx, cx, cy, mine, !mine && has(t.costs, t.coins))
                gfx.text(t.label, cx + 4, cy + 11, 7f, 0xFF2A1808.toInt())
                if (mine) gfx.text("YOU LIVE HERE", cx + 4, cy + 30, 7f, 0xFF1E4A1E.toInt()) else priceTag(gfx, cx + 4, cy + 21, t.costs, t.coins)
                Homes.drawHouse(ScaledGfx(gfx, 0.32f, cx + cw - 30f, cy + 6f), t, 0f, 72f, time)
            }.also {
                gfx.shadowText("< PREV      PAGE ${shopPage + 1}/${shopPages()}      NEXT >", viewW / 2, VIEW_H - 8f, 8f, white, 1)
                val (cx, cy) = shopCard(HOUSES_PER_PAGE)
                val full = houseFloors >= Homes.MAX_FLOORS
                val (costs, c) = floorCost()
                shopCardBg(gfx, cx, cy, full, !full && has(costs, c))
                gfx.text("UPSTAIRS + STAIRS", cx + 4, cy + 11, 6.5f, 0xFF2A1808.toInt())
                if (full) gfx.text("SO TALL!", cx + 4, cy + 30, 7f, 0xFF1E4A1E.toInt()) else priceTag(gfx, cx + 4, cy + 21, costs, c)
                Homes.drawHouse(ScaledGfx(gfx, 0.22f, cx + cw - 22f, cy + 6f), houseType, 0f, 150f, time, housePaint, minOf(houseFloors + 1, Homes.MAX_FLOORS))
                val (ex, ey) = shopCard(HOUSES_PER_PAGE + 1)
                shopCardBg(gfx, ex, ey, hasElevator, !hasElevator && has(ELEVATOR_ORES, ELEVATOR_COST))
                gfx.text("ELEVATOR", ex + 4, ey + 11, 7f, 0xFF2A1808.toInt())
                if (hasElevator) gfx.text("DING! GOT IT", ex + 4, ey + 30, 7f, 0xFF1E4A1E.toInt()) else priceTag(gfx, ex + 4, ey + 21, ELEVATOR_ORES, ELEVATOR_COST)
                Homes.drawElevator(ScaledGfx(gfx, 0.4f, ex + cw - 18f, ey + 2f), 0f, time, 70f)
            }
        }
        if (padConnected) { val (fx, fy) = shopCard(shopCursor); padFrame(gfx, fx - 2, fy - 2, shopCardW() + 4, 44f) }
        for (i in shopItems().indices) if (picked("shop:$shopTab:$shopPage:$i")) {
            val (fx, fy) = shopCard(i)
            padFrame(gfx, fx - 2, fy - 2, (if (shopTab == 5) shopCardW() * 3 + 8f else shopCardW()) + 4, if (shopTab == 5) 88f else 44f)
            gfx.shadowText("TAP AGAIN TO BUY", fx + shopCardW() / 2, fy + 46f, 6f, 0xFFFFE14D.toInt(), 1)
        }
    }

    private fun shopCardBg(gfx: Gfx, cx: Float, cy: Float, owned: Boolean, can: Boolean) {
        gfx.rect(cx, cy, shopCardW(), 40f, if (owned) 0xFF7A9A5A.toInt() else if (can) 0xFFE8C88A.toInt() else 0xFFA8885A.toInt())
    }

    private fun priceTag(gfx: Gfx, x0: Float, y: Float, costs: List<Pair<Ore, Int>>, coinsNeeded: Int) {
        var x = x0
        if (coinsNeeded > 0) {
            gfx.sprite(com.roccoquest.game.Sprites.coin, x, y, w = 7f, h = 10f)
            gfx.text("$coinsNeeded", x + 9, y + 8, 7f, if (coins >= coinsNeeded) 0xFF1E5A1E.toInt() else 0xFF8A1010.toInt())
            x += 26f
        }
        for ((o, n) in costs) {
            TeamArt.oreIcon(gfx, o, x, y, 9f)
            gfx.text("$n", x + 11, y + 8, 7f, if (inventory[o.ordinal] >= n) 0xFF1E5A1E.toInt() else 0xFF8A1010.toInt())
            x += 24f
        }
    }

    /** The PLUSHIE BOOK: all the plushies, 25 to a page. */
    private fun renderPlushBook(gfx: Gfx, overlay: Boolean) {
        gfx.rect(0f, if (overlay) 22f else 0f, viewW, VIEW_H, 0xEE2A1A30.toInt())
        for (k in 0 until 25) {
            val id = bookPage * 25 + k
            if (id >= Plushies.all.size) break
            val p = Plushies.all[id]
            val (bx, by) = bookCard(k)
            val owned = hasPlushie(p)
            gfx.rect(bx, by, bookW(), 34f, if (owned) 0xFFFFE8F2.toInt() else 0xFF4A3A5A.toInt())
            if (owned) Plushies.draw(this, gfx, p, bx + bookW() / 2, by + 26f, 20f)
            else gfx.text("?", bx + bookW() / 2, by + 22f, 12f, 0x66FFFFFF, 1)
            gfx.text(if (owned) p.name else "#${id + 1}", bx + bookW() / 2, by + 32f, if (owned && p.name.length > 12) 4f else 5f, if (owned) 0xFF5A2A4A.toInt() else 0x99FFFFFF.toInt(), 1)
        }
        gfx.shadowText("< PREV     PLUSHIE BOOK  page ${bookPage + 1}/$PLUSH_PAGES  ($plushieCount/${Plushies.all.size})     NEXT >", viewW / 2, VIEW_H - 8f, 8f, 0xFFFF9AD0.toInt(), 1)
    }

    // ======================================================================== Earthquakes

    /** Every fourth level shakes (but not boss fights, underwater or up in the clouds). */
    private fun isQuakeLevel(index: Int): Boolean {
        val d = Levels.all[index]
        // Every fourth level (it used to be every other one, which got too wild).
        return index % 4 == 1 && d.shoreX < 0 && d.place != Place.SKY && d.rows.none { r -> r.any { it in BOSS_CHARS } }
    }

    private fun updateQuake(dt: Float) {
        if (quakeWarn < 0f) {
            quakeT -= dt
            if (quakeT <= 0f) startQuake()
            return
        }
        quakeWarn -= dt
        shake = max(shake, 0.12f)
        if (quakeWarn <= 0f) {
            collapse()
            quakeWarn = -1f
            quakeT = 20f + Random.nextFloat() * 10f
        }
    }

    /** The top of the ground in column [tx], or -1 if it isn't plain ground the quake can break. */
    private fun surfaceRow(tx: Int): Int {
        if (tx < 1 || tx >= level.w - 1) return -1
        val y = (3 until ROWS).firstOrNull { Tile.solid(level[tx, it]) || level[tx, it] == Tile.RAIL || level[tx, it] == Tile.SPIKES } ?: return -1
        val t = level[tx, y]
        if (t != Tile.GROUND && t != Tile.DIRT) return -1
        return y
    }

    private fun isPit(tx: Int) = (3 until ROWS).none { Tile.solid(level[tx, it]) }

    private fun startQuake() {
        val blocked = things.filter { it is Checkpoint || it is Goal || it is House || it is Bed || it is Portal || it is Villager || it is Minecart }
            .map { tileOf(it.cx) }
        fun ok(c: Int): Boolean {
            if (c !in 2 until level.w - 3) return false
            if (abs(c - tileOf(respawnX)) < 3) return false
            if (blocked.any { abs(it - c) <= 3 }) return false
            if (surfaceRow(c) < 0 || surfaceRow(c + 1) < 0) return false
            return !isPit(c - 1) && !isPit(c + 2) && !isPit(c - 2) && !isPit(c + 3)
        }
        quakeCols.clear()
        val first = tileOf(camX)
        val last = tileOf(camX + viewW) - 2
        // One crack right under the team (so you have to move!) and a couple more nearby.
        val starts = ArrayList<Int>()
        val under = tileOf(leader.cx)
        if (ok(under)) starts += under
        repeat(12) {
            if (starts.size >= 3 || last <= first) return@repeat
            val c = first + Random.nextInt(last - first + 1)
            if (ok(c) && starts.none { abs(it - c) < 5 }) starts += c
        }
        for (c in starts) { quakeCols += c; quakeCols += c + 1 }
        if (quakeCols.isEmpty()) { quakeT = 6f; return }
        quakeWarn = QUAKE_WARN
        shake = 0.6f
        sfx(Sound.BOSS_FIRE)
        bannerTitle = "EARTHQUAKE!"
        bannerSub = "Get off the red cracks before the ground caves in!"
        bannerT = 1.8f
    }

    private fun collapse() {
        for (c in quakeCols) {
            val top = surfaceRow(c)
            if (top < 0) continue
            for (y in top until ROWS) {
                level[c, y] = Tile.EMPTY
                if (y < top + 2) debris(c, y, 0xFF8A5A2A.toInt(), 3)
            }
        }
        sfx(Sound.BREAK)
        shake = 0.8f
        // Don't respawn anyone over a brand new pit.
        fun solidX(x: Float, bottom: Float): Float {
            val row = tileOf(bottom + 2)
            val c = tileOf(x + 6)
            if (Tile.solid(level[c, row])) return x
            for (d in 1..30) for (cc in listOf(c - d, c + d)) {
                if (cc in 1 until level.w - 1 && Tile.solid(level[cc, row]) && !Tile.solid(level[cc, row - 1])) return cc * TILE + 2f
            }
            return x
        }
        safeX = solidX(safeX, safeY)
        respawnX = solidX(respawnX, respawnY)
        quakeCols.clear()
    }

    /** Red flashing cracks where the ground is about to go, and boulders crashing down at the end. */
    private fun drawQuake(gfx: Gfx, ox: Float) {
        val flash = (time * 8).toInt() % 2 == 0
        for (c in quakeCols) {
            val top = surfaceRow(c)
            if (top < 0) continue
            val x = c * TILE + ox
            val y = top * TILE.toFloat()
            gfx.rect(x, y, TILE.toFloat(), TILE.toFloat(), if (flash) 0x88FF2020.toInt() else 0x44FF2020)
            gfx.poly(floatArrayOf(x + 3, x + 8, x + 6, x + 12, x + 9, x + 4), floatArrayOf(y, y + 6, y + 9, y + 16, y + 9, y + 7), 0xFF2A0A0A.toInt())
            if (c % 2 == 0) {
                gfx.shadowText("!", x + TILE, y - 6 + sin(time * 10) * 2, 10f, 0xFFFF3030.toInt(), 1)
                // A boulder falls during the last moment.
                val p = 1f - (quakeWarn / 0.9f)
                if (p in 0f..1f) {
                    val by = -20f + p * (y + 20f) - 14f
                    gfx.oval(x + 2, by, 28f, 24f, 0xFF4A3A2A.toInt())
                    gfx.oval(x + 6, by + 3, 18f, 14f, 0xFF7A6A5A.toInt())
                }
            }
        }
    }

    // ======================================================================== Sunset bonus rooms

    private fun enterPortal(p: Portal) {
        if (dino != null) dismount()
        p.used = true
        away = Away(
            level, ArrayList(foes), ArrayList(things), ArrayList(frogSpots), goal, house, bed,
            respawnX, respawnY, p.cx - 6f, p.bottom,
        )
        level = Level(Levels.bonusRoom)
        foes.clear(); things.clear(); pending.clear(); frogSpots.clear()
        goal = null; house = null; bed = null; frog = null; camLock = null
        for (ty in 0 until ROWS) for (tx in 0 until level.w) {
            val px = tx * TILE.toFloat()
            val bottom = (ty + 1) * TILE.toFloat()
            when (level.def.rows[ty][tx]) {
                '@' -> { respawnX = px; respawnY = bottom }
                'c' -> things += Coin(px, ty * TILE.toFloat())
                't' -> things += Chest(px, bottom)
                'P' -> things += Portal(px, bottom, exit = true)
            }
        }
        placeTeam(respawnX, respawnY)
        camX = 0f
        bonusT = BONUS_TIME
        sfx(Sound.PORTAL)
        bannerTitle = "SUNSET BONUS!"
        bannerSub = "Grab the coins, mine the new materials, open the chest!"
        bannerT = 2.5f
        sound.sayAs("Whoa! A secret bonus room!", Voice.HERO)
    }

    private fun exitBonus() {
        val a = away ?: return
        away = null
        bonusT = 0f
        level = a.level
        foes.clear(); foes += a.foes
        things.clear(); things += a.things.filter { !(it is Portal && it.used) }
        pending.clear()
        frogSpots.clear(); frogSpots += a.frogSpots
        goal = a.goal; house = a.house; bed = a.bed
        respawnX = a.respawnX; respawnY = a.respawnY
        placeTeam(a.backX, a.backY)
        camX = (leader.cx - viewW * 0.4f).coerceIn(0f, max(0f, level.w * TILE - viewW))
        sfx(Sound.PORTAL)
        toast("Back to ${level.def.name}!")
    }

    /** The treasure chest: some of every new material, plus one special surprise. */
    private fun openChest(c: Chest) {
        c.open = true
        sfx(Sound.ONEUP)
        for (o in listOf(Ore.RUBY, Ore.SAPPHIRE, Ore.OBSIDIAN)) inventory[o.ordinal] += 1
        repeat(10) { particles += Sparkle(c.cx, c.y, Random.nextFloat() * 120f - 60f, -100f - Random.nextFloat() * 80f, Portal.sunset(Random.nextFloat())) }
        val prize = when (Random.nextInt(6)) {
            0 -> { startStar(); "a SUPER STAR" }
            1 -> { tnt += 3; "3 TNT" }
            2 -> { coins += 10; "10 COINS" }
            3 -> { blackFlowers++; "a BLACK FLOWER" }
            4 -> { hearts = MAX_HEARTS; armor = min(MAX_ARMOR, armor + 2); "full hearts and 2 armor" }
            else -> { for (o in listOf(Ore.RUBY, Ore.SAPPHIRE, Ore.OBSIDIAN)) inventory[o.ordinal] += 1; "EXTRA materials" }
        }
        toast("TREASURE! Ruby, sapphire, obsidian AND $prize!")
        if (Random.nextInt(2) == 0) givePlushie()
        sound.sayAs("Treasure! You got $prize!", Voice.HERO)
    }

    /** A soft shadow on the ground under someone's feet. */
    private fun softShadow(gfx: Gfx, cx: Float, bottom: Float, w: Float) {
        gfx.oval(cx - w * 0.6f, bottom - 2f, w * 1.2f, 4f, 0x33000000)
    }

    /** A smooth sky: [top] color at the top fading to [bottom] at the horizon (a real gradient, no stripes). */
    private fun sky(gfx: Gfx, top: Int, bottom: Int) = gfx.gradient(0f, 0f, viewW, VIEW_H, top, bottom)

    private fun renderBackground(gfx: Gfx, cam: Float) {
        if (wonder || state == State.VICTORY) {
            gfx.gradientStops(0f, 0f, viewW, VIEW_H, *IntArray(7) { solidRainbow(time * 0.2f, it / 7f) })
            // Wonder bosses tint the sky purple and green.
            val b = boss
            val wonderBoss = (b is KragJr && b.wonderStyle) || (b is KingKrag && b.wonderStyle)
            gfx.rect(0f, 0f, viewW, VIEW_H, if (wonderBoss) argb(90, if ((time * 2).toInt() % 2 == 0) 0x8A3AD8 else 0x2EB84A) else 0x66FFFFFF)
            return
        }
        when (level.def.place) {
            Place.HILLS, Place.JUNGLE -> {
                val jungle = level.def.place == Place.JUNGLE
                if (jungle) sky(gfx, 0xFF3EA878.toInt(), 0xFFC8F0C8.toInt()) else sky(gfx, 0xFF3A86F0.toInt(), 0xFFC8ECFF.toInt())
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
                sky(gfx, 0xFFFF7A4A.toInt(), 0xFFFFE4A8.toInt())
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
                sky(gfx, 0xFF6A9CE0.toInt(), 0xFFEAF4FF.toInt())
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
                sky(gfx, 0xFF0C0A16.toInt(), 0xFF2E2844.toInt())
                layer(cam, 0.3f, 90f) { i, x ->
                    gfx.poly(floatArrayOf(x, x + 18, x + 9), floatArrayOf(32f, 32f, 60f + (i % 4) * 14f), 0xFF2E2840.toInt())
                    if (i % 3 == 0) gfx.oval(x + 40, 120f + (i % 5) * 12f, 3f, 3f, Ore.entries[i % Ore.entries.size].color)
                }
            }
            Place.SKY -> {
                sky(gfx, 0xFF4AA4FF.toInt(), 0xFFE0F4FF.toInt())
                layer(cam, 0.15f, 150f) { i, x -> cloud(gfx, x, 40f + (i % 4) * 30f) }
                layer(cam, 0.3f, 210f) { i, x -> cloud(gfx, x + 20, 170f + (i % 2) * 20f) }
            }
            Place.WATER -> {
                gfx.gradient(0f, 0f, viewW, 32f, 0xFF6AB8F0.toInt(), 0xFFB8E4FF.toInt())
                gfx.gradientStops(0f, 32f, viewW, VIEW_H - 32f, 0xFF2A78C0.toInt(), 0xFF1E5A9A.toInt(), 0xFF123C6E.toInt())
                // Sunbeams shining down through the water
                for (k in 0 until 4) {
                    val bx = (k * 150f - cam * 0.1f).mod(viewW + 100f) - 50f
                    gfx.poly(floatArrayOf(bx, bx + 24f, bx + 70f, bx + 30f), floatArrayOf(32f, 32f, 220f, 220f), 0x14FFFFFF)
                }
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
                sky(gfx, 0xFF060A22.toInt(), 0xFF2E2A5C.toInt())
                for (i in 0 until 40) gfx.rect((i * 97f - cam * 0.05f).mod(viewW), (i * 53f) % 150f, 1.5f, 1.5f, white)
                gfx.oval(viewW * 0.75f, 24f, 28f, 28f, 0xFFF0F0D0.toInt())
                layer(cam, 0.25f, 200f) { i, x ->
                    gfx.oval(x, 160f - (i % 3) * 12f, 220f, 140f, 0xFF1E2A4A.toInt())
                }
            }
            Place.DEEP -> {
                sky(gfx, 0xFF02060A.toInt(), 0xFF0C2630.toInt())
                layer(cam, 0.3f, 80f) { i, x ->
                    // Glowing sculk
                    val glow = (120 + sin(time * 2 + i) * 80).toInt().coerceIn(0, 255)
                    gfx.oval(x + 10, 190f - (i % 3) * 30f, 10f, 6f, argb(glow, 0x2AE8E0))
                    gfx.rect(x + 40, 40f, 2f, 30f + (i % 4) * 10, 0xFF0E2A34.toInt())
                }
            }
            Place.SUNSET -> Sunset.drawSky(gfx, viewW, VIEW_H, cam, time)
            Place.BLIZZARD -> {
                sky(gfx, 0xFF56688A.toInt(), 0xFFC0CEDE.toInt())
                layer(cam, 0.2f, 220f) { i, x ->
                    gfx.poly(floatArrayOf(x, x + 90, x + 180), floatArrayOf(210f, 100f - (i % 3) * 15, 210f), 0xFF6A7C94.toInt())
                    gfx.poly(floatArrayOf(x + 70, x + 90, x + 110), floatArrayOf(120f - (i % 3) * 15, 100f - (i % 3) * 15, 120f - (i % 3) * 15), white)
                }
                // Snow blowing sideways in the wind.
                for (i in 0 until 45) {
                    val fx = (i * 73f - time * 140f - cam * 0.6f).mod(viewW + 20f) - 10f
                    val fy = (i * 37f + time * 40f + i * i) % VIEW_H
                    gfx.rect(fx, fy, 6f, 1.5f, 0xCCFFFFFF.toInt())
                }
            }
            Place.AURORA -> {
                sky(gfx, 0xFF040A18.toInt(), 0xFF1E3458.toInt())
                for (i in 0 until 30) gfx.rect((i * 97f - cam * 0.05f).mod(viewW), (i * 53f) % 120f, 1.5f, 1.5f, white)
                // The northern lights: wavy green and purple curtains.
                for (band in 0 until 3) {
                    val n = 12
                    val xs = FloatArray(n * 2)
                    val ys = FloatArray(n * 2)
                    for (k in 0 until n) {
                        val x = k * viewW / (n - 1)
                        val y = 40f + band * 18f + sin(time * 0.8f + k * 0.6f + band) * 10f
                        xs[k] = x; ys[k] = y
                        xs[n * 2 - 1 - k] = x; ys[n * 2 - 1 - k] = y + 26f
                    }
                    gfx.poly(xs, ys, if (band == 1) 0x44C870FF else 0x553CF0A0)
                }
                layer(cam, 0.25f, 200f) { i, x -> gfx.oval(x, 165f - (i % 3) * 12f, 220f, 140f, 0xFFDDEEFF.toInt()) }
            }
            Place.CANDY -> {
                sky(gfx, 0xFFFF9AC8.toInt(), 0xFFFFF0F8.toInt())
                layer(cam, 0.15f, 170f) { i, x -> cloud(gfx, x, 30f + (i % 3) * 25f) }
                layer(cam, 0.3f, 130f) { i, x ->
                    // Gumdrop hills and lollipop trees
                    gfx.oval(x, 170f - (i % 2) * 14f, 120f, 90f, if (i % 2 == 0) 0xFFB8F0C8.toInt() else 0xFFFFE0A0.toInt())
                    gfx.rect(x + 60, 110f, 4f, 70f, 0xFFF4F4F4.toInt())
                    gfx.oval(x + 46, 86f, 32f, 32f, listOf(0xFFFF6A9A, 0xFF6AC8FF, 0xFFFFC82A)[i % 3].toInt())
                    gfx.oval(x + 54, 94f, 16f, 16f, 0xFFFFFFFF.toInt())
                    gfx.oval(x + 58, 98f, 8f, 8f, listOf(0xFFFF6A9A, 0xFF6AC8FF, 0xFFFFC82A)[i % 3].toInt())
                }
            }
            Place.SHIP -> SeaArt.seaSky(gfx, viewW, VIEW_H, cam, time)
            Place.TOYS, Place.FUNGI, Place.MOON, Place.STATION -> GalaxyArt.drawSky(gfx, level.def.place, viewW, VIEW_H, cam, time)
            Place.RAINBOW -> SeaArt.space(gfx, viewW, VIEW_H, cam, time)
            Place.END -> {
                sky(gfx, 0xFF04020A.toInt(), 0xFF1E1030.toInt())
                for (i in 0 until 30) gfx.rect((i * 89f - cam * 0.03f).mod(viewW), (i * 41f) % 200f, 1.5f, 1.5f, 0xFFE0D0FF.toInt())
                layer(cam, 0.2f, 160f) { i, x ->
                    gfx.rect(x + 40, 90f - (i % 3) * 20f, 24f, 160f, 0xFF1A1028.toInt())
                }
            }
            Place.CASTLE, Place.VOLCANO -> {
                val volcano = level.def.place == Place.VOLCANO
                if (volcano) gfx.gradient(0f, 0f, viewW, VIEW_H, 0xFF1E0804.toInt(), 0xFF6A2410.toInt())
                else gfx.gradient(0f, 0f, viewW, VIEW_H, 0xFF14060A.toInt(), 0xFF3A141A.toInt())
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
                    Place.SNOW, Place.BLIZZARD, Place.AURORA -> if (under) Sprites.frozenDirt else Sprites.snowTop
                    Place.CANDY -> if (under) TeamArt.candyDirt else TeamArt.candyTop
                    Place.TOYS, Place.FUNGI, Place.MOON, Place.STATION -> GalaxyArt.ground(place, under)
                    else -> if (under) Sprites.dirt else Sprites.grassTop
                }
                Tile.DIRT -> Sprites.dirt
                Tile.BRICK -> if (place == Place.CAVE) Sprites.brickBlue else Sprites.brick
                Tile.QCOIN, Tile.QHEART, Tile.QSTAR -> if ((time * 3).toInt() % 3 == 0) Sprites.question2 else Sprites.question1
                Tile.QMYSTERY -> Sprites.question1
                Tile.USED -> Sprites.used
                Tile.HARD -> when (place) {
                    Place.CASTLE, Place.VOLCANO, Place.STATION -> Sprites.hardGrey
                    Place.END, Place.DEEP -> TeamArt.obsidian
                    else -> Sprites.hard
                }
                Tile.RAIL -> { TeamArt.rail(gfx, tx * TILE - cam, (ty + 1) * TILE.toFloat()); null }
                Tile.SPIKES -> { TeamArt.spikes(gfx, tx * TILE - cam, (ty + 1) * TILE.toFloat()); null }
                Tile.VENT -> TeamArt.vent
                Tile.PLANK -> { SeaArt.plank(gfx, tx * TILE - cam, ty * TILE.toFloat(), level[tx, ty - 1] != Tile.PLANK, tx); null }
                Tile.MAST -> {
                    val top = level[tx, ty - 1] != Tile.MAST
                    val sail = !top && level[tx, ty - 2] != Tile.MAST
                    SeaArt.mast(gfx, tx * TILE - cam, ty * TILE.toFloat(), top, sail, time)
                    null
                }
                Tile.RAINBOW -> { SeaArt.rainbowTile(gfx, tx * TILE - cam, ty * TILE.toFloat(), time, tx); null }
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
        if (armorType != Armor.NONE) {
            // Armor durability bar under the hearts.
            val bw = MAX_HEARTS * 12f - 2
            gfx.rect(6f, 15f, bw, 3f, 0x88000000.toInt())
            gfx.rect(6f, 15f, bw * armorHp / armorType.durability, 3f, armorType.color)
        }
        gfx.sprite(Sprites.coin, 6f, 17f, w = 8f, h = 11f)
        gfx.shadowText("x$coins", 16f, 26f, 8f, white)
        gfx.shadowText("\u2605$score", 42f, 26f, 7f, 0xFFFFE14D.toInt())
        var x = max(112f, viewW * 0.25f)
        // Ten materials now: squeeze them in so they stop before the CRAFT button.
        // Only the materials you actually have, so the top of the screen stays tidy.
        val have = Ore.entries.filter { inventory[it.ordinal] > 0 }
        val step = min(25f, (viewW - 106f - x) / maxOf(1, have.size))
        // When there are lots of materials, shrink the icons and numbers so they never run into each other.
        val tight = step < 23f
        val icon = if (tight) 7f else 10f
        for (o in have) {
            val n = inventory[o.ordinal]
            val label = if (tight && n > 99) "99+" else n.toString()
            TeamArt.oreIcon(gfx, o, x, if (tight) 6.5f else 5f, icon)
            gfx.shadowText(label, x + icon + 1, 13f, if (tight) minOf(6f, (step - icon - 2) / (label.length * 0.62f)) else 7f, white)
            x += step
        }
        if (shieldType != Shield.NONE) {
            TeamArt.shieldIcon(gfx, 84f, 17f, 10f, shieldType)
            gfx.shadowText("$shieldHp", 94f, 26f, 7f, white)
        }
        val powerText = if (power == Power.NONE) "" else "${power.label} Lv${flowerLevel[power.ordinal]}"
        val starText = (if (away != null) "  •  BONUS ${bonusT.toInt() + 1}" else "") +
            (if (starT > 0) "  •  STAR ${starT.toInt() + 1}" else "") +
            (if (miniT > 0) "  •  MINI ${miniT.toInt() + 1}" else "") +
            (if (tanookiT > 0) "  •  TANOOKI ${tanookiT.toInt() + 1}" else "") +
            (if (metalT > 0) "  •  METAL ${metalT.toInt() + 1}" else "") +
            (if (wingT > 0) "  •  WINGS ${wingT.toInt() + 1}" else "") +
            (if (megaT > 0) "  •  MEGA ${megaT.toInt() + 1}" else "") +
            (if (propT > 0) "  •  PROPELLER ${propT.toInt() + 1}" else "") +
            (captureKind?.let { "  •  ${it.label} ${captureT.toInt() + 1}" } ?: "")
        val swordText = if (sword != Sword.NONE) "  •  ${sword.label.removeSuffix(" SWORD")} SWORD ${swordHp * 100 / sword.durability}%" else ""
        val pickText = if (pickTier > 1) " ${pickHp * 100 / pickDurability(pickTier)}%" else ""
        gfx.shadowText("${TeamArt.pickName(pickTier)} PICK$pickText$swordText" + (if (powerText.isEmpty()) "" else "  •  $powerText") + "  •  ${mon.label} Lv${monLevel()}", max(112f, viewW * 0.25f), 26f, 7f, 0xFFDDEEFF.toInt())
        // Power-up timers get their own line so they never run under the buttons.
        if (starText.isNotEmpty()) gfx.shadowText(starText.removePrefix("  •  "), 6f, if (level.def.shoreX >= 0 && inWater(leader)) 54f else 41f, 7f, 0xFFFFE14D.toInt())

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
                b is Warden && b.maxHp < 100 -> "WARDEN TWIN"
                b is Warden -> "THE WARDEN"
                b is EnderDragon -> "ENDER DRAGON"
                b is KingBooHoo -> "KING BOO-HOO"
                b is WitchBoss -> "WITCH WANDA"
                b is RockStarKrag -> "ROCK STAR KRAG"
                b is MechaKrag -> "MECHA KRAG"
                b is GalaxyKrag -> "GALAXY KRAG"
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
        if (padConnected && rows.isNotEmpty()) padFrame(gfx, px + 1, rowsTop + craftCursor.coerceAtMost(rows.lastIndex) * rh, pw - 2, rh)
        for (i in rows.indices) if (picked("craft:$craftTab:$i")) padFrame(gfx, px + 1, rowsTop + i * rh, pw - 2, rh)
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
                r.kind == Kind.HOUSE -> Homes.drawHouse(ScaledGfx(gfx, 0.28f, px + 3, y + 3), r.house, 4f, 72f, time)
                r.kind == Kind.BED -> TeamArt.bed(gfx, px + 2, y + 20)
                r.kind == Kind.SWORD -> TeamArt.sword(gfx, px + 7, y + 20, -0.8f, r.sword)
                r.kind == Kind.BOW -> drawBowIcon(gfx, px + 6, y + 4, r.bow)
                r.kind == Kind.BLACK -> TeamArt.blackFlower(gfx, px + 5, y + 3, 19f, time)
                r.kind == Kind.SHIELD -> TeamArt.shieldIcon(gfx, px + 7, y + 4, 15f, r.shield)
                r.kind == Kind.ARMOR -> {
                    val c = r.armor.color
                    gfx.rect(px + 8, y + 4, 12f, 5f, c)
                    gfx.rect(px + 6, y + 10, 16f, 9f, c)
                    gfx.rect(px + 9, y + 12, 3f, 2f, 0x88FFFFFF.toInt())
                }
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
        Mon.entries.take(9).forEachIndexed { i, m -> TeamArt.mon(gfx, m, viewW / 2 - 18 + i * 24f, base, time + i, -1, false) }
        TeamArt.frog(gfx, viewW / 2 + 82, base - 26 - abs(sin(time * 9)) * 4, time)
        if ((time * 2).toInt() % 2 == 0) gfx.shadowText(if (padConnected) "PRESS A TO START" else "TAP TO START", viewW / 2, 222f, 12f, white, 1)
        // Controller setup button (or press - on a controller).
        gfx.rect(viewW - 110f, 6f, 102f, 18f, 0xCC1A1A2E.toInt())
        gfx.text("CONTROLLER", viewW - 59f, 18.5f, 7f, if (padConnected) 0xFF7FE07F.toInt() else white, 1)
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
        if (padConnected) padFrame(gfx, slotCardX(slotCursor) - 4, 56f, slotCardW() + 8, 148f)
        gfx.shadowText(if (padConnected) "Pick a file and press A" else "Tap a file to play", viewW / 2, 222f, 9f, 0xFFDDDDDD.toInt(), 1)
    }

    private fun renderPick(gfx: Gfx) {
        gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF1A3A2A.toInt())
        gfx.shadowText("CHOOSE YOUR POKEMON!", viewW / 2, 24f, 13f, 0xFFFFD21F.toInt(), 1)
        gfx.shadowText("FILE ${slot + 1}  \u2022  tap one to see it, tap again to pick", viewW / 2, 37f, 7f, 0xFFDDDDDD.toInt(), 1)
        STARTERS.forEachIndexed { i, m ->
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
            if (!monUnlocked(m)) {
                gfx.rect(x, y, w, pickCardH, 0xAA101018.toInt())
                gfx.shadowText("LOCKED", x + w / 2, y + 34, 8f, 0xFFFFD21F.toInt(), 1)
                gfx.text("beat level 3", x + w / 2, y + 46, 5.5f, white, 1)
            }
        }
        if (toastT > 0) {
            gfx.rect(20f, 186f, viewW - 40, 16f, 0xDD101828.toInt())
            gfx.text(toast, viewW / 2, 197f, 7f, white, 1)
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
        if (padConnected && trades.isNotEmpty()) padFrame(gfx, px + 1, rowsTop + tradeCursor.coerceAtMost(trades.lastIndex) * rowH, pw - 2, rowH)
        for (i in trades.indices) if (picked("trade:$i")) padFrame(gfx, px + 1, rowsTop + i * rowH, pw - 2, rowH)
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
        if (levelIndex >= WORLD_SIZE) gfx.shadowText("WORLD ${levelIndex / WORLD_SIZE + 1}", viewW / 2, 52f, 12f, solidRainbow(time * 0.5f), 1)
        gfx.shadowText("LEVEL ${levelIndex + 1} of ${Levels.all.size}", viewW / 2, 70f, 12f, 0xFFDDDDDD.toInt(), 1)
        gfx.shadowText(level.def.name, viewW / 2, 96f, 20f, 0xFFFFD21F.toInt(), 1)
        gfx.sprite(TeamArt.rocco(power, 0), viewW / 2 - 36, 112f)
        gfx.sprite(TeamArt.blocky(power, false), viewW / 2 - 14, 112f)
        TeamArt.mon(gfx, mon, viewW / 2 + 18, 136f, time, -1, false)
        gfx.shadowText(level.def.tip, viewW / 2, 162f, 8f, white, 1)
        if (isQuakeLevel(levelIndex)) gfx.shadowText("EARTHQUAKE LEVEL! When the ground cracks red, MOVE!", viewW / 2, 196f, 8f, 0xFFFF6A4A.toInt(), 1)
        gfx.shadowText("FILE ${slot + 1}   Hearts: $MAX_HEARTS   Pickaxe: ${TeamArt.pickName(pickTier)}", viewW / 2, 180f, 8f, 0xFFAADDFF.toInt(), 1)
    }

    private fun renderVictory(gfx: Gfx) {
        gfx.rect(0f, 0f, viewW, VIEW_H, 0x55000000)
        gfx.shadowText("YOU BEAT ALL ${worldCount()} WORLDS!", viewW / 2, 70f, 16f, solidRainbow(time * 0.5f), 1)
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
