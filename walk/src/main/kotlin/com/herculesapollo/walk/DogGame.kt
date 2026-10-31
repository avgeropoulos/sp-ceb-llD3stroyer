package com.herculesapollo.walk

import com.roccoquest.game.Storage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** The dog you play as in the adventure. [x] is the center, [y] the feet. */
class Player(val herc: Boolean) {
    var x = 40f
    var y = GROUND
    var vx = 0f
    var vy = 0f
    var onGround = true
    var facing = 1
    var anim = 0f
    /** Grace period for jumping just after running off a ledge. */
    var coyote = 0f
    /** A jump pressed just before landing still counts. */
    var jumpBuffer = 0f
    var jumpHeld = false
    var stinkT = 0f
    var hurtT = 0f
    /** Hercules: seconds left in a dash. */
    var dashT = 0f
    /** Apollo: seconds left of the bark's shock wave (for drawing it). */
    var barkT = 0f
    var cooldown = 0f

    val w = 20f
    val h = 15f
    /** Hercules is quick; Apollo is springy and jumps high. */
    val maxSpeed = if (herc) 205f else 140f
    /** Gives about 56 pixels of jump for Hercules (onto fences) and 96 for Apollo (onto tree branches). */
    val jumpV = if (herc) 290f else 380f
    val name = if (herc) "Hercules" else "Apollo"
    val actionLabel = if (herc) "DASH" else "BARK"
}

/** Something to stand on: a fence top, a bin lid, a branch. Solid from above only. */
class Ledge(val x0: Float, val x1: Float, val top: Float, val bouncy: Boolean = false)

/**
 * The dog's-eye adventure: play as Hercules or Apollo, platformer style, on the same five
 * walks. The dogs have slipped their leashes, so chase everything on the way home.
 * Movement is snappy: quick acceleration, instant turns, jump buffering, coyote time and
 * a higher jump the longer you hold the button.
 */
class DogGame(
    val input: WalkInput,
    private val audio: WalkAudio,
    private val storage: Storage,
    private val slots: SaveSlots,
    herc: Boolean,
    private val musicOn: () -> Boolean,
) : WalkSession {
    enum class State { INTRO, PLAYING, HOME, RESULTS, OUCH, ALL_DONE }

    var state = State.INTRO
        private set
    override var stateTime = 0f
        private set
    /** Screen width in virtual pixels, for the HUD; the zoomed-in world shows [worldW] of it. */
    override var viewW = 427f
    val worldW get() = viewW / ZOOM
    /** The world as the closer camera sees it, for drawing the scenery and moving critters. */
    val world = object : WalkScene {
        override val course get() = this@DogGame.course
        override val camX get() = this@DogGame.camX
        override val viewW get() = worldW
        override val time get() = this@DogGame.time
    }
    /** Set once the player taps past the final screen; the host goes back to the title. */
    var finished = false
        private set

    val player = Player(herc)
    /** This dog's save-slot mode. */
    private val mode = if (herc) WalkGame.HERC else WalkGame.APOLLO

    override var walkIndex = slots.walk(mode).coerceIn(0, Walks.all.lastIndex)
        private set
    override var course = Course(Walks.all[walkIndex])
        private set
    var ledges = buildLedges(course)
        private set
    override var camX = 0f
    override var score = slots.score(mode)
    private var walkStartScore = 0
    override var hearts = 3
    override var chased = 0
        private set
    override var treatsGot = 0
        private set
    override var bonus = 0
        private set
    override var best = storage.load("best")
        private set
    override var time = 0f
        private set
    override val introLine get() = "You're ${player.name}! Chase everything on the way home."
    override val doneLine get() = "${player.name} is tired and happy."

    private var combo = 0
    private var lastCatch = -10f
    val popups = ArrayList<Popup>()
    var tip: String? = null
        private set
    private var tipT = 0f
    private val tipsShown = HashSet<String>()

    init {
        startWalk(walkIndex)
    }

    /** Each dog has its own daytime stroll tune; night walks keep the night tune. */
    fun applyMusic() = audio.music(
        when {
            !musicOn() -> WalkTune.NONE
            course.walk.theme.tune == WalkTune.DAY -> if (player.herc) WalkTune.HERC else WalkTune.APOLLO
            else -> course.walk.theme.tune
        },
    )

    private fun setState(s: State) {
        state = s
        stateTime = 0f
    }

    fun startWalk(i: Int) {
        walkIndex = i
        course = Course(Walks.all[i])
        ledges = buildLedges(course)
        with(player) {
            x = 40f; y = GROUND; vx = 0f; vy = 0f; onGround = true; facing = 1
            stinkT = 0f; hurtT = 0f; dashT = 0f; barkT = 0f; cooldown = 0f; jumpBuffer = 0f
        }
        camX = 0f
        hearts = 3
        chased = 0
        treatsGot = 0
        bonus = 0
        combo = 0
        walkStartScore = score
        popups.clear()
        tip = null
        slots.save(mode, i, score)
        applyMusic()
        setState(State.INTRO)
    }

    /** Leave for the title screen: saved, unless this walk is already finished (and saved as the next one). */
    fun quit() {
        if (state == State.RESULTS || state == State.ALL_DONE) finished = true else saveAndQuit()
    }

    /** SAVE & QUIT: keep this walk as it was when it started. */
    fun saveAndQuit() {
        slots.save(mode, walkIndex, walkStartScore)
        best = max(best, score).also { storage.save("best", it) }
        finished = true
    }

    fun update(dt: Float, tap: Boolean, jumpP: Boolean, actionP: Boolean) {
        stateTime += dt
        when (state) {
            State.INTRO -> {
                time += dt
                if (stateTime > 2f) {
                    setState(State.PLAYING)
                    if (walkIndex == 0) {
                        showTip(if (player.herc) "Hold JUMP to jump higher. DASH to pounce!" else "Hold JUMP to jump higher. BARK freezes critters!")
                    }
                }
            }
            State.PLAYING -> play(dt, jumpP, actionP)
            State.HOME -> updateHome(dt)
            State.RESULTS -> if (tap && stateTime > 1.5f) {
                if (walkIndex == Walks.all.lastIndex) {
                    slots.clear(mode)
                    audio.music(WalkTune.NONE)
                    setState(State.ALL_DONE)
                    audio.say("All done! Good dog, ${player.name}!")
                } else {
                    startWalk(walkIndex + 1)
                }
            }
            State.OUCH -> if (tap && stateTime > 1.5f) {
                score = walkStartScore
                startWalk(walkIndex)
            }
            State.ALL_DONE -> {
                time += dt
                if (tap && stateTime > 3f) finished = true
            }
        }
    }

    // ------------------------------------------------------------------ Playing

    private fun play(dt: Float, jumpP: Boolean, actionP: Boolean) {
        time += dt
        if (tipT > 0f) { tipT -= dt; if (tipT <= 0f) tip = null }
        movePlayer(dt, jumpP, actionP)

        val chasers = listOf(player.x)
        for (c in course.critters) {
            if (c.mood == Mood.IDLE || c.mood == Mood.FLEE || c.mood == Mood.SAFE) maybeTip(c)
            CritterAi.update(c, dt, chasers, player.x, world, audio)
        }
        checkCatches()
        updateBikes(dt)
        if (state != State.PLAYING) return
        collectTreats()
        for (p in popups) p.t += dt
        popups.removeAll { it.t > 1.3f }
        followCamera(dt)
        if (player.x >= course.homeX + 30f) enterHome()
    }

    private fun movePlayer(dt: Float, jumpP: Boolean, actionP: Boolean) {
        val p = player
        if (p.cooldown > 0f) p.cooldown -= dt
        if (p.stinkT > 0f) p.stinkT -= dt
        if (p.hurtT > 0f) p.hurtT -= dt
        if (p.barkT > 0f) p.barkT -= dt

        if (actionP && p.cooldown <= 0f) {
            if (p.herc) dash() else bark()
        }

        val dir = (if (input.right) 1 else 0) - (if (input.left) 1 else 0)
        val top = p.maxSpeed * (if (p.stinkT > 0f) 0.6f else 1f)
        if (p.dashT > 0f) {
            p.dashT -= dt
            p.vx = p.facing * 420f
            p.vy = min(p.vy, 0f)
        } else if (dir != 0) {
            // Turning around is instant on the ground, and quick in the air.
            if (p.vx * dir < 0f) p.vx = if (p.onGround) 0f else p.vx * 0.5f
            p.vx = approach(p.vx, dir * top, (if (p.onGround) GROUND_ACCEL else AIR_ACCEL) * dt)
            p.facing = dir
        } else {
            p.vx = approach(p.vx, 0f, (if (p.onGround) GROUND_FRICTION else AIR_FRICTION) * dt)
        }
        if (abs(p.vx) > top && p.dashT <= 0f) p.vx = approach(p.vx, p.vx.sign() * top, 1200f * dt)

        p.coyote = if (p.onGround) COYOTE else p.coyote - dt
        p.jumpBuffer = if (jumpP) JUMP_BUFFER else p.jumpBuffer - dt
        if (p.jumpBuffer > 0f && p.coyote > 0f) {
            p.vy = -p.jumpV
            p.jumpBuffer = 0f
            p.coyote = 0f
            p.onGround = false
            audio.play(WalkSound.JUMP)
        }
        p.jumpHeld = input.jump
        if (p.dashT <= 0f) {
            // Holding JUMP keeps gravity light on the way up; letting go cuts the jump short.
            val g = if (p.vy < 0f && p.jumpHeld) HOLD_GRAVITY else GRAVITY
            p.vy = min(p.vy + g * dt, MAX_FALL)
        }

        p.x = (p.x + p.vx * dt).coerceIn(8f, course.homeX + 60f)
        val prevFeet = p.y
        p.y += p.vy * dt
        p.onGround = false
        if (p.vy >= 0f) {
            for (l in ledges) {
                if (p.x + p.w * 0.3f < l.x0 || p.x - p.w * 0.3f > l.x1) continue
                if (prevFeet <= l.top + 0.5f && p.y >= l.top) {
                    p.y = l.top
                    if (l.bouncy) {
                        p.vy = -470f
                        audio.play(WalkSound.JUMP)
                        popup("BOING!", p.x, p.y - p.h - 6f, WHITE, 7f)
                    } else {
                        p.vy = 0f
                        p.onGround = true
                    }
                    break
                }
            }
        }
        if (p.y >= GROUND) { p.y = GROUND; p.vy = 0f; p.onGround = true }
        p.anim += abs(p.vx) * dt
    }

    private fun bark() {
        val p = player
        // Longer than the freeze, so critters can't be kept frozen forever.
        p.cooldown = 2f
        p.barkT = 0.4f
        audio.play(WalkSound.ARP_ARP)
        popup("ARP ARP!", p.x, p.y - p.h - 6f, WHITE, 9f)
        for (c in course.critters) {
            if (c.catchable && abs(c.x - p.x) < 120f && abs(c.y - p.y) < 100f) c.frozenT = 1.5f
        }
    }

    private fun dash() {
        val p = player
        p.cooldown = 0.7f
        p.dashT = 0.22f
        audio.play(WalkSound.GAV)
        popup("GAV!", p.x, p.y - p.h - 6f, WHITE, 8f)
    }

    private fun followCamera(dt: Float) {
        val target = player.x - worldW * 0.4f
        camX += (target - camX) * min(1f, dt * 10f)
        camX = camX.coerceIn(0f, max(0f, course.walk.length - worldW))
    }

    private fun showTip(text: String) {
        if (!tipsShown.add(text)) return
        tip = text
        tipT = 3.5f
    }

    private fun popup(text: String, x: Float, y: Float, color: Int = WHITE, size: Float = 8f) {
        popups += Popup(text, x, y, color, size)
    }

    private fun maybeTip(c: Critter) {
        if (c.x > camX + worldW - 30f) return
        when (c.kind) {
            Kind.CAT -> showTip("Jump up on the fence to get the cat!")
            Kind.RABBIT -> showTip(if (player.herc) "Bunnies are fast! DASH to catch them." else "Bunnies are fast! BARK to freeze them.")
            Kind.RACCOON -> showTip("Quick! Jump on the bin before the raccoon hides.")
            Kind.SKUNK -> showTip("A skunk! Jump over it, DON'T touch it!")
            Kind.CRAB -> showTip("Catch the crabs before they hide!")
            Kind.LIZARD -> showTip(
                if (player.herc) "Lizards dart up the fence. Catch them before they climb!" else "Lizard up the fence? Jump and grab it!",
            )
            Kind.FROG -> showTip("Frogs hop for the pond. Catch them mid-hop!")
            Kind.SQUIRREL -> if (c.mood == Mood.SAFE) {
                showTip(if (player.herc) "Too high for Hercules! Catch them before they climb." else "Up the tree? Jump onto the branch!")
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------------ Catching

    /** Squirrels up a tree and lizards up the fence can still be caught here, if you can jump that high. */
    private fun reachable(c: Critter) =
        c.catchable || ((c.kind == Kind.SQUIRREL || c.kind == Kind.LIZARD) && c.mood == Mood.SAFE)

    private fun overlaps(c: Critter): Boolean {
        val p = player
        return abs(p.x - c.x) < (p.w + c.w) / 2f && p.y - p.h < c.y && c.y - c.h < p.y
    }

    private fun checkCatches() {
        val c = course.critters.firstOrNull { reachable(it) && overlaps(it) } ?: return
        caught(c)
    }

    private fun caught(c: Critter) {
        val p = player
        c.mood = Mood.CHASED
        c.climbing = false
        c.frozenT = 0f
        c.t = 0f
        val away = if (c.x >= p.x) 1f else -1f
        c.facing = away.toInt()
        if (c.kind == Kind.SKUNK) {
            c.vx = away * 30f
            c.vy = 0f
            p.stinkT = 4f
            p.vx = -away * 140f
            p.vy = -200f
            p.dashT = 0f
            score = max(0, score - 100)
            popup("PEE-YEW!", p.x, p.y - p.h - 6f, STINK, 10f)
            audio.play(WalkSound.SKUNK)
            return
        }
        c.vx = away * 230f
        c.vy = if (c.kind == Kind.PIGEON || c.kind == Kind.GULL) -160f else if (c.y < GROUND - 4f) -60f else 0f
        chased++
        score += c.kind.points
        popup("+${c.kind.points}", c.x, c.y - c.h - 4f, YELLOW)
        audio.play(WalkSound.CATCH)
        // Catch critters one after another for a combo.
        combo = if (time - lastCatch < 2f) combo + 1 else 1
        lastCatch = time
        if (combo >= 2) {
            val extra = 50 * combo
            score += extra
            popup("COMBO x$combo +$extra", p.x, GROUND - 64f, 0xFF7DF9FF.toInt(), 9f)
            audio.play(WalkSound.TEAM)
        }
    }

    // ------------------------------------------------------------------ Bikes and treats

    private fun updateBikes(dt: Float) {
        val p = player
        for (b in course.bikes) {
            if (!b.active && b.x - camX < worldW + 20f) {
                b.active = true
                audio.play(WalkSound.BELL)
                showTip("Ring ring! JUMP over the bikes!")
            }
            if (!b.active) continue
            b.x -= 150f * dt
            b.t += dt
            if (!b.hit && p.hurtT <= 0f && abs(b.x - p.x) < 10f + p.w / 2f && p.y > GROUND - 18f) {
                b.hit = true
                hearts--
                p.hurtT = 1.5f
                p.vy = -220f
                p.vx = (if (p.x < b.x) -1f else 1f) * 160f
                p.dashT = 0f
                audio.play(WalkSound.OUCH)
                popup("YELP!", p.x, p.y - p.h - 6f, 0xFFFF6B6B.toInt(), 10f)
                if (hearts <= 0) {
                    best = max(best, score).also { storage.save("best", it) }
                    setState(State.OUCH)
                    return
                }
            }
        }
        course.bikes.removeAll { it.active && it.x < camX - 80f }
    }

    private fun collectTreats() {
        val p = player
        for (t in course.treats) {
            if (t.taken) continue
            val cx = t.x + 4.5f
            val cy = t.y + 2f
            if (abs(cx - p.x) < p.w / 2f + 4f && cy > p.y - p.h - 3f && cy < p.y + 2f) {
                t.taken = true
                treatsGot++
                score += 25
                audio.play(WalkSound.TREAT)
                popup("+25", cx, t.y - 4f, WHITE, 6f)
            }
        }
    }

    // ------------------------------------------------------------------ Coming home

    private fun enterHome() {
        setState(State.HOME)
        tip = null
        player.dashT = 0f
        audio.music(WalkTune.NONE)
        audio.play(WalkSound.HOME)
    }

    private fun updateHome(dt: Float) {
        time += dt
        val p = player
        val spot = course.homeX + 18f
        p.vx = ((spot - p.x) * 4f).coerceIn(-150f, 150f)
        p.x += p.vx * dt
        if (abs(p.vx) > 5f) p.facing = if (p.vx > 0) 1 else -1
        p.anim += abs(p.vx) * dt
        p.vy = min(p.vy + GRAVITY * dt, MAX_FALL)
        p.y = min(GROUND, p.y + p.vy * dt)
        if (p.y >= GROUND) { p.vy = 0f; p.onGround = true }
        for (pop in popups) pop.t += dt
        popups.removeAll { it.t > 1.3f }
        followCamera(dt)
        if (stateTime > 2.6f) {
            bonus = hearts * 100 + (if (chased == course.total) 1000 else 0)
            score += bonus
            best = max(best, score).also { storage.save("best", it) }
            if (walkIndex < Walks.all.lastIndex) slots.save(mode, walkIndex + 1, score)
            setState(State.RESULTS)
            audio.say(if (chased == course.total) "Wow! Super good dog!" else "Good dog, ${player.name}!")
        }
    }

    companion object {
        /** The adventure's camera is closer than the walk's. */
        const val ZOOM = 1.25f
        const val GROUND_ACCEL = 2200f
        const val AIR_ACCEL = 1500f
        const val GROUND_FRICTION = 2600f
        const val AIR_FRICTION = 900f
        const val GRAVITY = 2000f
        const val HOLD_GRAVITY = 750f
        const val MAX_FALL = 480f
        const val COYOTE = 0.08f
        const val JUMP_BUFFER = 0.12f
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val YELLOW = 0xFFFFE14D.toInt()
        private const val STINK = 0xFF9BE15D.toInt()

        fun buildLedges(course: Course): List<Ledge> = course.props.mapNotNull { p ->
            when (p.kind) {
                PropKind.FENCE -> Ledge(p.x - 2f, p.x + p.w + 7f, course.fenceTop)
                PropKind.BIN -> Ledge(p.x - 16f, p.x + 16f, course.binTop)
                PropKind.BENCH -> Ledge(p.x - 20f, p.x + 20f, GROUND - 22f)
                PropKind.TREE -> Ledge(p.x + 3f, p.x + 25f, GROUND - 84f)
                PropKind.SANDCASTLE -> Ledge(p.x - 16f, p.x + 16f, GROUND - 20f)
                PropKind.MAILBOX -> Ledge(p.x - 7f, p.x + 7f, GROUND - 34f)
                PropKind.HYDRANT -> Ledge(p.x - 6f, p.x + 6f, GROUND - 22f)
                PropKind.UMBRELLA -> Ledge(p.x - 24f, p.x + 24f, GROUND - 62f, bouncy = true)
                // The dog cot's mesh is springy.
                PropKind.COT -> Ledge(p.x - 32f, p.x + 32f, GROUND - 19f, bouncy = true)
                PropKind.COUCH -> Ledge(p.x - 30f, p.x + 30f, GROUND - 24f)
                PropKind.TOYCAR -> Ledge(p.x - 16f, p.x + 16f, GROUND - 37f)
                else -> null
            }
        }

        private fun approach(v: Float, target: Float, step: Float) =
            if (v < target) min(v + step, target) else max(v - step, target)

        private fun Float.sign() = if (this > 0f) 1f else if (this < 0f) -1f else 0f
    }
}
