package com.herculesapollo.walk

import com.roccoquest.game.Gfx
import com.roccoquest.game.Storage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** How far a dog can get from the walker before the leash goes tight. */
const val LEASH = 190f

enum class DogMode { FOLLOW, DASH, RETURN }

class Dog(val name: String, val herc: Boolean) {
    var x = 0f
    var y = GROUND
    var vx = 0f
    var vy = 0f
    var onGround = true
    var facing = 1
    var anim = 0f
    var mode = DogMode.FOLLOW
    var dashT = 0f
    var target: Critter? = null
    /** Seconds left smelling of skunk; a stinky dog won't chase anything. */
    var stinkT = 0f
    var cooldown = 0f
    /** Game time of this dog's last catch, for team bonuses. */
    var lastCatch = -10f
    var whoaShown = false

    /** Hitbox size; Hercules is the big one. */
    val w = if (herc) 22f else 17f
    val h = if (herc) 17f else 13f
    /** Where the dog trots, ahead of the walker. */
    val offset = if (herc) 30f else 54f
    /** Hercules is strong (jumps high), Apollo is quick (runs fast). */
    val dashSpeed = if (herc) 175f else 265f
    val jumpV = if (herc) 300f else 200f

    val ready get() = stinkT <= 0f
}

class Walker {
    var x = 40f
    var y = GROUND
    var vy = 0f
    var onGround = true
    var facing = 1
    var moving = false
    var step = 0f
    var hurtT = 0f
    /** Set while a dog is pulling the walker along by the leash. */
    var dragT = 0f
    val w = 12f
    val h = 23f
}

class Popup(val text: String, val x: Float, val y: Float, val color: Int, val size: Float = 8f) {
    var t = 0f
}

/**
 * Hercules & Apollo's Walk: take the two dogs out on five walks and let them chase
 * squirrels, birds, bunnies, cats, raccoons and crabs. Jump over bikes, grab the
 * treats, and whatever you do, don't chase the skunk.
 */
class WalkGame(
    val input: WalkInput,
    private val audio: WalkAudio = WalkAudio {},
    private val storage: Storage = Storage.Memory(),
) : WalkSession {
    enum class State { TITLE, INTRO, PLAYING, HOME, RESULTS, OUCH, ALL_DONE }

    var state = State.TITLE
        private set
    override var stateTime = 0f
        private set
    override var viewW = 427f

    override var walkIndex = 0
        private set
    override var course = Course(Walks.all[0])
        private set
    val walker = Walker()
    val herc = Dog("Hercules", true)
    val apollo = Dog("Apollo", false)
    val dogs = listOf(herc, apollo)
    override var camX = 0f
    override var score = 0
    private var walkStartScore = 0
    override var hearts = 3
    override var chased = 0
        private set
    override var treatsGot = 0
        private set
    override var bonus = 0
        private set
    var paused = false
        private set
    var musicOn = storage.load("musicOff") == 0
        private set
    override var best = storage.load("best")
        private set
    /** Seconds of play, for animations and team bonuses. */
    override var time = 0f
        private set
    override val introLine get() = "Hercules & Apollo are ready to go!"
    override val doneLine get() = "Hercules & Apollo are tired and happy."

    /** The dog's-eye adventure (playing as one of the dogs), while it's running. */
    var dogGame: DogGame? = null
        private set
    /** True while the on-screen controls should use the adventure's layout. */
    val dogMode get() = dogGame != null
    val popups = ArrayList<Popup>()

    var tip: String? = null
        private set
    private var tipT = 0f
    private val tipsShown = HashSet<String>()

    private var prevJump = false
    private var prevHerc = false
    private var prevApollo = false
    private var prevPause = false
    private var prevMusic = false
    private var prevAction = false

    init {
        resetPositions()
        applyMusic()
    }

    private fun setState(s: State) {
        state = s
        stateTime = 0f
    }

    private fun applyMusic() {
        val dg = dogGame
        if (dg != null) dg.applyMusic() else audio.music(if (!musicOn) WalkTune.NONE else course.walk.theme.tune)
    }

    /** Where each dog's adventure picks up again. */
    fun adventureWalk(herc: Boolean) =
        storage.load(if (herc) "adventureHerc" else "adventureApollo").coerceIn(0, Walks.all.lastIndex)

    /** Furthest walk reached, for "continue" on the title screen. */
    val savedWalk get() = storage.load("walk").coerceIn(0, Walks.all.lastIndex)

    fun startWalk(i: Int) {
        walkIndex = i
        course = Course(Walks.all[i])
        resetPositions()
        hearts = 3
        chased = 0
        treatsGot = 0
        bonus = 0
        walkStartScore = score
        popups.clear()
        tip = null
        if (i > savedWalk) storage.save("walk", i)
        applyMusic()
        setState(State.INTRO)
    }

    private fun resetPositions() {
        walker.x = 40f; walker.y = GROUND; walker.vy = 0f; walker.onGround = true
        walker.hurtT = 0f; walker.facing = 1
        for (d in dogs) {
            d.x = walker.x + d.offset; d.y = GROUND; d.vx = 0f; d.vy = 0f; d.onGround = true
            d.mode = DogMode.FOLLOW; d.target = null; d.stinkT = 0f; d.cooldown = 0f; d.facing = 1
        }
        camX = 0f
    }

    fun update(dt: Float) {
        val tap = input.tap
        input.tap = false
        val jumpP = input.jump && !prevJump
        val hercP = input.herc && !prevHerc
        val apolloP = input.apollo && !prevApollo
        val pauseP = input.pause && !prevPause
        val musicP = input.musicToggle && !prevMusic
        val actionP = input.action && !prevAction
        prevJump = input.jump; prevHerc = input.herc; prevApollo = input.apollo
        prevPause = input.pause; prevMusic = input.musicToggle; prevAction = input.action

        if (musicP) {
            musicOn = !musicOn
            storage.save("musicOff", if (musicOn) 0 else 1)
            applyMusic()
        }
        val dg = dogGame
        val playing = if (dg != null) dg.state == DogGame.State.PLAYING else state == State.PLAYING
        if (playing && pauseP) { paused = !paused; return }
        if (paused) {
            if (tap && !input.pause && !input.musicToggle) paused = false
            if (paused) return
        }
        // Taps on the top-right buttons shouldn't also press menu buttons.
        val menuTap = tap && !input.pause && !input.musicToggle

        if (dg != null) {
            dg.viewW = viewW
            // On a keyboard, the HERC and APOLLO keys work as the dog's action button too.
            dg.update(dt, menuTap, jumpP, actionP || hercP || apolloP)
            if (dg.finished) {
                dogGame = null
                best = max(best, storage.load("best"))
                course = Course(Walks.all[0])
                resetPositions()
                applyMusic()
                setState(State.TITLE)
            }
            return
        }

        stateTime += dt
        when (state) {
            State.TITLE -> {
                updateTitle(dt)
                if (menuTap && stateTime > 0.4f) {
                    score = 0
                    // The title screen has three buttons side by side; a key press picks the walk.
                    when (if (input.tapX < 0f) 0 else (input.tapX / (viewW / 3f)).toInt()) {
                        0 -> {
                            audio.say("Let's go for a walk!")
                            startWalk(savedWalk)
                        }
                        1 -> startAdventure(herc = true)
                        else -> startAdventure(herc = false)
                    }
                }
            }
            State.INTRO -> {
                time += dt
                updateDogs(dt, false, false)
                updatePopups(dt)
                if (stateTime > 2.2f) {
                    setState(State.PLAYING)
                    if (walkIndex == 0) showTip("Tap HERC or APOLLO to chase!")
                }
            }
            State.PLAYING -> play(dt, jumpP, hercP, apolloP)
            State.HOME -> updateHome(dt)
            State.RESULTS -> if (menuTap && stateTime > 1.5f) {
                if (walkIndex == Walks.all.lastIndex) {
                    storage.save("walk", 0)
                    audio.music(WalkTune.NONE)
                    setState(State.ALL_DONE)
                    audio.say("All done! Good dogs, Hercules and Apollo!")
                } else {
                    startWalk(walkIndex + 1)
                }
            }
            State.OUCH -> if (menuTap && stateTime > 1.5f) {
                score = walkStartScore
                startWalk(walkIndex)
            }
            State.ALL_DONE -> {
                time += dt
                if (menuTap && stateTime > 3f) {
                    course = Course(Walks.all[0])
                    resetPositions()
                    applyMusic()
                    setState(State.TITLE)
                }
            }
        }
    }

    private fun startAdventure(herc: Boolean) {
        audio.say(if (herc) "Go, Hercules!" else "Go, Apollo!")
        dogGame = DogGame(input, audio, storage, herc) { musicOn }.also { it.viewW = viewW }
    }

    private fun updateTitle(dt: Float) {
        time += dt
        camX = (camX + 30f * dt) % (course.walk.length - viewW - 200f)
        walker.x = camX + viewW * 0.22f
        walker.moving = true
        walker.step += dt
        for (d in dogs) {
            d.x = walker.x + d.offset
            d.vx = 74f
            d.anim += 74f * dt
        }
    }

    // ------------------------------------------------------------------ Playing

    private fun play(dt: Float, jumpP: Boolean, hercP: Boolean, apolloP: Boolean) {
        time += dt
        if (tipT > 0f) { tipT -= dt; if (tipT <= 0f) tip = null }

        val dir = (if (input.right) 1 else 0) - (if (input.left) 1 else 0)
        walker.moving = dir != 0
        if (dir != 0) walker.facing = dir
        walker.x += dir * (if (dir > 0) 74f else 60f) * dt
        if (jumpP && walker.onGround) {
            walker.vy = -270f
            walker.onGround = false
            audio.play(WalkSound.JUMP)
            // The dogs hop along too.
            for (d in dogs) if (d.onGround && d.mode != DogMode.DASH) { d.vy = -min(d.jumpV, 230f); d.onGround = false }
        }
        walker.vy += 820f * dt
        walker.y += walker.vy * dt
        if (walker.y >= GROUND) { walker.y = GROUND; walker.vy = 0f; walker.onGround = true }
        if (walker.hurtT > 0f) walker.hurtT -= dt
        if (walker.dragT > 0f) walker.dragT -= dt
        if (walker.moving || walker.dragT > 0f) walker.step += dt * (if (walker.dragT > 0f) 2f else 1f)

        updateDogs(dt, hercP, apolloP)
        walker.x = walker.x.coerceIn(12f, course.homeX)

        updateCritters(dt)
        checkCatches()
        updateBikes(dt)
        if (state != State.PLAYING) return
        collectTreats()
        updatePopups(dt)
        followCamera(dt)

        if (walker.x >= course.homeX) enterHome()
    }

    private fun followCamera(dt: Float) {
        val target = walker.x - viewW * 0.3f
        camX += (target - camX) * min(1f, dt * 6f)
        camX = camX.coerceIn(0f, max(0f, course.walk.length - viewW))
    }

    private fun showTip(text: String) {
        if (!tipsShown.add(text)) return
        tip = text
        tipT = 3.5f
    }

    private fun popup(text: String, x: Float, y: Float, color: Int = 0xFFFFFFFF.toInt(), size: Float = 8f) {
        popups += Popup(text, x, y, color, size)
    }

    private fun updatePopups(dt: Float) {
        for (p in popups) p.t += dt
        popups.removeAll { it.t > 1.3f }
    }

    // ------------------------------------------------------------------ Dogs

    private fun updateDogs(dt: Float, hercP: Boolean, apolloP: Boolean) {
        for (d in dogs) updateDog(d, dt, if (d.herc) hercP else apolloP)
    }

    private fun updateDog(d: Dog, dt: Float, pressed: Boolean) {
        if (d.cooldown > 0f) d.cooldown -= dt
        if (d.stinkT > 0f) d.stinkT -= dt
        if (pressed) {
            if (d.stinkT > 0f) popup("P-U! *sniff*", d.x, d.y - d.h - 4, 0xFF9BE15D.toInt(), 7f)
            else if (d.cooldown <= 0f) startDash(d)
        }

        when (d.mode) {
            DogMode.FOLLOW, DogMode.RETURN -> {
                val tx = walker.x + d.offset
                val maxV = if (d.mode == DogMode.RETURN) 230f else 175f
                d.vx = ((tx - d.x) * 5f).coerceIn(-maxV, maxV)
                if (d.mode == DogMode.RETURN && abs(tx - d.x) < 8f) d.mode = DogMode.FOLLOW
            }
            DogMode.DASH -> {
                d.dashT += dt
                val t = d.target
                if (t == null) {
                    // Nothing to chase: a quick burst of zoomies.
                    d.vx = d.facing * d.dashSpeed * 0.8f
                    if (d.dashT > 0.45f) endDash(d, null)
                } else if (!t.catchable) {
                    endDash(d, t)
                } else {
                    val dx = t.x - d.x
                    d.vx = if (abs(dx) < 6f) dx * 8f else (if (dx > 0) 1 else -1) * d.dashSpeed
                    val up = GROUND - t.y
                    if (d.onGround && up > 6f && abs(dx) < 34f + up * 0.3f) {
                        d.vy = -d.jumpV
                        d.onGround = false
                    }
                    if (d.dashT > 2.2f) endDash(d, t)
                }
            }
        }

        d.x += d.vx * dt
        // The leash: a chasing dog drags the walker along; otherwise it just stops the dog.
        val lx = d.x - walker.x
        if (abs(lx) > LEASH) {
            val s = if (lx > 0) 1f else -1f
            if (d.mode == DogMode.DASH) {
                walker.x += (abs(lx) - LEASH) * 0.85f * s
                walker.dragT = 0.2f
                if (!d.whoaShown) {
                    d.whoaShown = true
                    popup("WHOA!", walker.x, walker.y - walker.h - 6, 0xFFFFE14D.toInt())
                    audio.play(WalkSound.WHOA)
                }
            }
            d.x = walker.x + s * LEASH
        }
        d.vy += 900f * dt
        d.y += d.vy * dt
        if (d.y >= GROUND) { d.y = GROUND; d.vy = 0f; d.onGround = true }
        if (abs(d.vx) > 5f) d.facing = if (d.vx > 0) 1 else -1
        d.anim += abs(d.vx) * dt
    }

    private fun startDash(d: Dog) {
        d.target = pickTarget(d)
        d.mode = DogMode.DASH
        d.dashT = 0f
        d.whoaShown = false
        d.cooldown = 0.35f
        d.target?.let { d.facing = if (it.x >= d.x) 1 else -1 }
        audio.play(if (d.herc) WalkSound.WOOF else WalkSound.YIP)
        popup(if (d.herc) "WOOF!" else "YIP!", d.x, d.y - d.h - 4, 0xFFFFFFFF.toInt(), 7f)
    }

    /** The nearest critter out in the open, preferring ones up ahead. */
    fun pickTarget(d: Dog): Critter? = course.critters
        .filter { it.catchable && it.x - d.x in -70f..200f && abs(it.x - walker.x) < LEASH + 40f }
        .minByOrNull { val dx = it.x - d.x; if (dx < 0) -dx * 1.6f else dx }

    private fun endDash(d: Dog, t: Critter?) {
        if (t != null && t.mood != Mood.CHASED) {
            val msg = when {
                d.herc && t.kind == Kind.RABBIT -> "TOO FAST!"
                !d.herc && (t.kind == Kind.CAT || t.kind == Kind.RACCOON) -> "TOO HIGH!"
                t.mood == Mood.SAFE || t.mood == Mood.GONE -> "GOT AWAY!"
                else -> null
            }
            if (msg != null) popup(msg, d.x, d.y - d.h - 6, 0xFFFFFFFF.toInt(), 7f)
        }
        d.mode = DogMode.RETURN
        d.target = null
    }

    // ------------------------------------------------------------------ Critters

    private fun updateCritters(dt: Float) {
        val chasers = dogs.map { it.x }
        for (c in course.critters) {
            if (c.mood == Mood.IDLE || c.mood == Mood.FLEE) maybeTip(c)
            CritterAi.update(c, dt, chasers, walker.x, this, audio)
        }
    }

    private fun maybeTip(c: Critter) {
        if (c.x > camX + viewW - 30f) return
        when (c.kind) {
            Kind.CAT -> showTip("Cats sit up high. HERCULES can jump that high!")
            Kind.RABBIT -> showTip("Bunnies are fast. Only APOLLO can keep up!")
            Kind.RACCOON -> showTip("Quick! Raccoons hide in the bins.")
            Kind.SKUNK -> showTip("Uh oh, a skunk! DON'T chase it!")
            Kind.CRAB -> showTip("Catch the crabs before they hide!")
            else -> {}
        }
    }

    private fun overlaps(d: Dog, c: Critter) =
        abs(d.x - c.x) < (d.w + c.w) / 2f && d.y - d.h < c.y && c.y - c.h < d.y

    private fun checkCatches() {
        for (d in dogs) {
            if (d.mode != DogMode.DASH) continue
            val c = course.critters.firstOrNull { it.catchable && overlaps(d, it) } ?: continue
            caught(d, c)
        }
    }

    private fun caught(d: Dog, c: Critter) {
        c.mood = Mood.CHASED
        c.climbing = false
        c.t = 0f
        val away = if (c.x >= d.x) 1f else -1f
        c.facing = away.toInt()
        if (c.kind == Kind.SKUNK) {
            c.vx = away * 30f
            c.vy = 0f
            d.stinkT = 5f
            score = max(0, score - 100)
            popup("PEE-YEW!", d.x, d.y - d.h - 6, 0xFF9BE15D.toInt(), 10f)
            audio.play(WalkSound.SKUNK)
            endDash(d, null)
            return
        }
        c.vx = away * 230f
        c.vy = if (c.kind == Kind.PIGEON || c.kind == Kind.GULL) -160f else if (c.y < GROUND - 4f) -60f else 0f
        chased++
        score += c.kind.points
        popup("+${c.kind.points}", c.x, c.y - c.h - 4, 0xFFFFE14D.toInt())
        audio.play(WalkSound.CATCH)
        val other = if (d.herc) apollo else herc
        if (time - other.lastCatch < 1.5f) {
            score += 100
            popup("TEAM CHASE! +100", (d.x + other.x) / 2f, GROUND - 60f, 0xFF7DF9FF.toInt(), 9f)
            audio.play(WalkSound.TEAM)
            other.lastCatch = -10f
        } else {
            d.lastCatch = time
        }
        // Keep going if another critter (like the rest of a flock) is right there.
        val next = pickTarget(d)
        if (next != null && abs(next.x - d.x) < 50f && next.kind != Kind.SKUNK) {
            d.target = next
            d.dashT = 0f
        } else {
            endDash(d, null)
        }
    }

    // ------------------------------------------------------------------ Bikes and treats

    private fun updateBikes(dt: Float) {
        for (b in course.bikes) {
            if (!b.active && b.x - camX < viewW + 20f) {
                b.active = true
                audio.play(WalkSound.BELL)
                showTip("Ring ring! JUMP over the bikes!")
            }
            if (!b.active) continue
            b.x -= 150f * dt
            b.t += dt
            if (!b.hit && walker.hurtT <= 0f && abs(b.x - walker.x) < 13f && walker.y > GROUND - 18f) {
                b.hit = true
                hearts--
                walker.hurtT = 1.5f
                audio.play(WalkSound.OUCH)
                popup("OOF!", walker.x, walker.y - walker.h - 6, 0xFFFF6B6B.toInt(), 10f)
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
        for (t in course.treats) {
            if (t.taken) continue
            val cx = t.x + 4.5f
            val cy = t.y + 2f
            val byWalker = abs(cx - walker.x) < 10f && cy > walker.y - walker.h - 3f && cy < walker.y + 2f
            val byDog = dogs.any { abs(cx - it.x) < it.w / 2f + 4f && cy > it.y - it.h - 3f && cy < it.y + 2f }
            if (byWalker || byDog) {
                t.taken = true
                treatsGot++
                score += 25
                audio.play(WalkSound.TREAT)
                popup("+25", cx, t.y - 4f, 0xFFFFFFFF.toInt(), 6f)
            }
        }
    }

    // ------------------------------------------------------------------ Coming home

    private fun enterHome() {
        setState(State.HOME)
        tip = null
        for (d in dogs) { d.mode = DogMode.FOLLOW; d.target = null }
        audio.music(WalkTune.NONE)
        audio.play(WalkSound.HOME)
    }

    private fun updateHome(dt: Float) {
        time += dt
        val door = course.homeX + 30f
        walker.moving = walker.x < door
        if (walker.moving) { walker.x = min(door, walker.x + 50f * dt); walker.step += dt }
        walker.vy += 820f * dt
        walker.y = min(GROUND, walker.y + walker.vy * dt)
        for (d in dogs) {
            val tx = door + (if (d.herc) -14f else 14f)
            d.vx = ((tx - d.x) * 4f).coerceIn(-150f, 150f)
            d.x += d.vx * dt
            if (abs(d.vx) > 5f) d.facing = if (d.vx > 0) 1 else -1
            d.anim += abs(d.vx) * dt
            d.vy += 900f * dt
            d.y = min(GROUND, d.y + d.vy * dt)
            if (d.y >= GROUND) { d.vy = 0f; d.onGround = true }
        }
        updatePopups(dt)
        followCamera(dt)
        if (stateTime > 3f) {
            bonus = hearts * 100 + (if (chased == course.total) 1000 else 0)
            score += bonus
            best = max(best, score).also { storage.save("best", it) }
            if (walkIndex + 1 > savedWalk && walkIndex < Walks.all.lastIndex) storage.save("walk", walkIndex + 1)
            setState(State.RESULTS)
            audio.say(if (chased == course.total) "Wow! Super good dogs!" else "Good dogs!")
        }
    }

    fun render(gfx: Gfx) {
        val dg = dogGame
        if (dg == null) {
            WalkRenderer.render(this, gfx)
            return
        }
        dg.viewW = viewW
        DogRenderer.render(dg, gfx)
        if (paused) WalkRenderer.drawPaused(gfx, viewW)
        WalkControls.drawPause(gfx, viewW, musicOn)
    }
}
