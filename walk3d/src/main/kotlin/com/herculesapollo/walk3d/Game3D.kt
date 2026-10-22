package com.herculesapollo.walk3d

import com.herculesapollo.walk.Kind
import com.herculesapollo.walk.PauseMenu
import com.herculesapollo.walk.SaveSlots
import com.herculesapollo.walk.WalkAudio
import com.herculesapollo.walk.WalkSound
import com.herculesapollo.walk.WalkTune
import com.roccoquest.game.Storage
import kotlin.concurrent.Volatile
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

const val VIEW_H = 240f

/** Controls written by the platform layer, read by the game loop. */
class Input3D {
    /** The virtual stick: x is right, y is forward (away from the camera), each -1..1. */
    @Volatile var stickX = 0f
    @Volatile var stickY = 0f
    @Volatile var keyLeft = false
    @Volatile var keyRight = false
    @Volatile var keyUp = false
    @Volatile var keyDown = false
    @Volatile var jump = false
    /** Hercules dashes, Apollo barks. */
    @Volatile var action = false
    @Volatile var pause = false
    @Volatile var musicToggle = false
    /** Set on any new touch / key press; consumed by menus. */
    @Volatile var tap = false
    /** Where the last tap was (virtual pixels), for menu buttons; -1 for a key press. */
    @Volatile var tapX = -1f
    @Volatile var tapY = -1f
    /** The phone's Back button: pauses (or resumes) the game, or leaves for the title screen. */
    @Volatile var back = false
}

/** The dog you play. ([x], [y], [z]) is its feet; [yaw] is the way it faces (0 = +z). */
class Dog3(val herc: Boolean) {
    var x = 0f
    var y = 0f
    var z = 0f
    var vx = 0f
    var vy = 0f
    var vz = 0f
    var yaw = 0f
    var onGround = true
    var coyote = 0f
    var jumpBuffer = 0f
    var jumpHeld = false
    var stinkT = 0f
    var hurtT = 0f
    var dashT = 0f
    var barkT = 0f
    var cooldown = 0f
    /** Distance run, for the leg animation. */
    var stride = 0f

    /** Both doodles are about the same size. */
    val radius = 0.38f
    val height = 0.85f
    /** Hercules is fast; Apollo jumps high (up into the trees). */
    val maxSpeed = if (herc) 8f else 5.5f
    val jumpV = if (herc) 6.4f else 8.5f
    val name = if (herc) "Hercules" else "Apollo"
    val actionLabel = if (herc) "DASH" else "BARK"
    val speed get() = hypot(vx, vz)
}

class Bike3(var s: Float) {
    var x = 0f
    var z = 0f
    var yaw = 0f
    var bellT = 0f
    var t = 0f
}

class Treat3(val x: Float, val y: Float, val z: Float) {
    var taken = false
}

class Popup3(val text: String, val x: Float, val y: Float, val z: Float, val color: Int, val size: Float) {
    var t = 0f
}

/**
 * Hercules & Apollo 3D: run around four open 3D levels as one of the dogs, chasing
 * critters, then head home. The camera follows from behind; the controls are snappy.
 */
class Game3D(
    val input: Input3D,
    private val audio: WalkAudio = WalkAudio {},
    private val storage: Storage = Storage.Memory(),
) {
    enum class State { TITLE, INTRO, PLAYING, HOME, RESULTS, OUCH, ALL_DONE }

    var state = State.TITLE
        private set
    var stateTime = 0f
        private set
    var viewW = 427f

    var dog = Dog3(true)
        private set
    var levelIndex = 0
        private set
    var level = Level(Levels.all[0])
        private set
    val critters = ArrayList<Critter3>()
    val bikes = ArrayList<Bike3>()
    val treats = ArrayList<Treat3>()
    val popups = ArrayList<Popup3>()

    var camYaw = 0f
    /**
     * The camera direction the stick is read against. It's caught when you start pushing
     * the stick and held until you let go, so the camera swinging round behind the dog
     * never changes the way you're steering (that feedback loop is what made it dizzy).
     */
    var controlYaw = 0f
        private set
    private var stickHeld = false
    /** The ground height the camera follows: it ignores jumps, so it doesn't bob up and down. */
    private var camFloor = 0f
    /** The height the camera looks at, just above the dog. */
    var lookY = 0.8f
        private set
    var camX = 0f
    var camY = 3f
    var camZ = 0f

    var score = 0
    private var levelStartScore = 0
    var hearts = 3
    var chased = 0
        private set
    var treatsGot = 0
        private set
    var bonus = 0
        private set
    var best = storage.load("best3d")
        private set
    /** Seconds spent on this level so far. */
    var levelTime = 0f
        private set
    /** Seconds since the game started, for animations. */
    var clock = 0f
        private set
    var paused = false
        private set
    var musicOn = storage.load("musicOff3d") == 0
        private set
    var tip: String? = null
        private set
    private var tipT = 0f
    private val tipsShown = HashSet<String>()
    private var combo = 0
    private var lastCatch = -10f
    private var nagT = 0f

    private var prevJump = false
    private var prevAction = false
    private var prevPause = false
    private var prevMusic = false

    val frame = Frame()
    /** This level's ground and its baked scenery. */
    var terrain = Mesh(FloatArray(0), ground = true)
        private set
    var decals = Mesh(FloatArray(0), decal = true)
        private set
    var scenery = Mesh(FloatArray(0))
        private set
    private var builtFor = -1

    /** Three save slots, each remembering both dogs' levels and scores. */
    val slots = SaveSlots(storage, prefix = "3d")

    init {
        // Progress saved before there were slots goes into slot 1.
        slots.migrate("level3dHerc", "herc")
        slots.migrate("level3dApollo", "apollo")
        loadLevel(0)
        applyMusic()
    }

    private fun mode(herc: Boolean) = if (herc) "herc" else "apollo"

    fun savedLevel(herc: Boolean) = slots.walk(mode(herc)).coerceIn(0, Levels.all.lastIndex)

    private fun setState(s: State) {
        state = s
        stateTime = 0f
    }

    /** Each dog has its own daytime stroll tune; the night level keeps the night tune. */
    private fun applyMusic() = audio.music(
        when {
            !musicOn -> WalkTune.NONE
            state == State.TITLE -> WalkTune.DAY
            level.def.look.tune == WalkTune.DAY -> if (dog.herc) WalkTune.HERC else WalkTune.APOLLO
            else -> level.def.look.tune
        },
    )

    private fun loadLevel(i: Int) {
        levelIndex = i
        level = Level(Levels.all[i])
        if (builtFor != i) {
            builtFor = i
            terrain = Terrain.build(level)
            decals = Terrain.buildDecals(level)
            scenery = Models.buildScenery(level)
        }
        critters.clear()
        level.spawns.mapTo(critters) { Critter3(it) }
        treats.clear()
        level.treats.mapTo(treats) { Treat3(it[0], it[1], it[2]) }
        bikes.clear()
        val loopLen = loopLength()
        for (b in 0 until level.bikes) bikes += Bike3(loopLen * b / level.bikes).also { placeBike(it) }
        with(dog) {
            x = level.startX; z = level.startZ; y = 0f; yaw = level.startYaw
            vx = 0f; vy = 0f; vz = 0f; onGround = true
            stinkT = 0f; hurtT = 0f; dashT = 0f; barkT = 0f; cooldown = 0f; jumpBuffer = 0f
        }
        camYaw = dog.yaw
        camX = dog.x - sin(camYaw) * CAM_DIST
        camZ = dog.z - cos(camYaw) * CAM_DIST
        camY = CAM_HEIGHT
        camFloor = 0f
        lookY = 0.8f
        controlYaw = camYaw
        stickHeld = false
        popups.clear()
        tip = null
    }

    fun startLevel(i: Int) {
        loadLevel(i)
        hearts = 3
        chased = 0
        treatsGot = 0
        bonus = 0
        combo = 0
        levelTime = 0f
        levelStartScore = score
        slots.save(mode(dog.herc), i, score)
        applyMusic()
        setState(State.INTRO)
    }

    fun choose(herc: Boolean) {
        dog = Dog3(herc)
        score = slots.score(mode(herc))
        audio.say(if (herc) "Go, Hercules!" else "Go, Apollo!")
        startLevel(savedLevel(herc))
    }

    fun update(dt: Float) {
        val tap = input.tap
        input.tap = false
        val backP = input.back
        input.back = false
        val jumpP = input.jump && !prevJump
        val actionP = input.action && !prevAction
        val pauseP = input.pause && !prevPause
        val musicP = input.musicToggle && !prevMusic
        prevJump = input.jump; prevAction = input.action; prevPause = input.pause; prevMusic = input.musicToggle

        if (musicP) {
            musicOn = !musicOn
            storage.save("musicOff3d", if (musicOn) 0 else 1)
            applyMusic()
        }
        if (backP) { back(); return }
        if (state == State.PLAYING && pauseP) { paused = !paused; return }
        if (paused) {
            if (tap && !input.pause && !input.musicToggle) {
                paused = false
                when (PauseMenu.hit(input.tapX, input.tapY, viewW, canSwitch = true)) {
                    PauseMenu.Action.QUIT -> saveAndQuit()
                    PauseMenu.Action.SWITCH -> switchDog()
                    else -> {}
                }
            }
            return
        }
        val menuTap = tap && !input.pause && !input.musicToggle
        stateTime += dt
        clock += dt
        when (state) {
            State.TITLE -> {
                camYaw += dt * 0.25f
                if (menuTap && stateTime > 0.4f) titleTap(input.tapX, input.tapY)
            }
            State.INTRO -> {
                followCamera(dt, 0f)
                if (stateTime > 2f) {
                    setState(State.PLAYING)
                    if (levelIndex == 0) showTip("Drag on the left to run. Hold JUMP to jump higher!")
                    if (level.def.look == Look.BEACH) showTip("Beach umbrellas are bouncy!")
                }
            }
            State.PLAYING -> play(dt, jumpP, actionP)
            State.HOME -> {
                dog.vx = 0f; dog.vz = 0f
                dog.yaw = turnToward(dog.yaw, 0f, dt * 6f)
                camYaw = turnToward(camYaw, Math.PI.toFloat(), dt * 2f)
                followCamera(dt, 0f)
                updatePopups(dt)
                if (stateTime > 2.5f) finishLevel()
            }
            State.RESULTS -> if (menuTap && stateTime > 1.5f) {
                if (levelIndex == Levels.all.lastIndex) {
                    slots.clear(mode(dog.herc))
                    audio.music(WalkTune.NONE)
                    setState(State.ALL_DONE)
                    audio.say("All done! Good dog, ${dog.name}!")
                } else {
                    startLevel(levelIndex + 1)
                }
            }
            State.OUCH -> if (menuTap && stateTime > 1.5f) {
                score = levelStartScore
                startLevel(levelIndex)
            }
            State.ALL_DONE -> if (menuTap && stateTime > 3f) {
                loadLevel(0)
                applyMusic()
                setState(State.TITLE)
            }
        }
    }

    /** The title screen: save-slot tabs, then a button for each dog. A key press picks Hercules. */
    private fun titleTap(x: Float, y: Float) {
        if (x >= 0f && y >= Controls3D.SLOT_TOP && y <= Controls3D.SLOT_TOP + Controls3D.SLOT_H) {
            Controls3D.slotAt(x, viewW)?.let { slots.current = it }
            return
        }
        if (x < 0f) choose(herc = true) else if (y >= Controls3D.CARD_TOP - 8f) choose(herc = x < viewW / 2)
    }

    /** True on the title screen, where the phone's Back button leaves the app. */
    val atTitle get() = state == State.TITLE

    /** The phone's Back button: pause or resume during play; anywhere else, back to the title. */
    private fun back() {
        when {
            paused -> paused = false
            state == State.PLAYING -> paused = true
            // Finished levels are already saved (as the next level), so don't save over them.
            state == State.RESULTS || state == State.ALL_DONE -> toTitle()
            state != State.TITLE -> saveAndQuit()
        }
    }

    /** SAVE & QUIT from the pause screen: keep this level (as it was when it started) and go back to the title. */
    private fun saveAndQuit() {
        save()
        toTitle()
    }

    /** SWITCH DOG from the pause screen: save this dog's game and carry on as the other dog. */
    private fun switchDog() {
        save()
        choose(herc = !dog.herc)
    }

    private fun save() {
        slots.save(mode(dog.herc), levelIndex, levelStartScore)
        best = max(best, score).also { storage.save("best3d", it) }
    }

    private fun toTitle() {
        loadLevel(0)
        setState(State.TITLE)
        applyMusic()
    }

    // ------------------------------------------------------------------ Playing

    private fun play(dt: Float, jumpP: Boolean, actionP: Boolean) {
        levelTime += dt
        if (tipT > 0f) { tipT -= dt; if (tipT <= 0f) tip = null }
        if (levelIndex == 0 && stateTime > 4.5f) {
            showTip(if (dog.herc) "Tap DASH to pounce!" else "Tap BARK to freeze critters in their tracks!")
        }
        val stickY = moveDog(dt, jumpP, actionP)
        followCamera(dt, stickY)
        for (c in critters) {
            CritterBrain.update(c, dt, dog.x, dog.z, level, audio)
            maybeTip(c)
        }
        checkCatches()
        updateBikes(dt)
        if (state != State.PLAYING) return
        collectTreats()
        updatePopups(dt)
        checkHome(dt)
    }

    /** Moves the dog from the stick; returns the stick's forward amount (for the camera). */
    private fun moveDog(dt: Float, jumpP: Boolean, actionP: Boolean): Float {
        val d = dog
        if (d.cooldown > 0f) d.cooldown -= dt
        if (d.stinkT > 0f) d.stinkT -= dt
        if (d.hurtT > 0f) d.hurtT -= dt
        if (d.barkT > 0f) d.barkT -= dt
        if (actionP && d.cooldown <= 0f) if (d.herc) dash() else bark()

        var sx = input.stickX + (if (input.keyRight) 1f else 0f) - (if (input.keyLeft) 1f else 0f)
        var sy = input.stickY + (if (input.keyUp) 1f else 0f) - (if (input.keyDown) 1f else 0f)
        val sl = hypot(sx, sy)
        if (sl > 1f) { sx /= sl; sy /= sl }
        // Stick to world, against the camera direction caught when the stick was first pushed.
        val pushingStick = hypot(sx, sy) > 0.05f
        if (pushingStick && !stickHeld) controlYaw = camYaw
        stickHeld = pushingStick
        val fx = sin(controlYaw); val fz = cos(controlYaw)
        val wx = fx * sy - fz * sx
        val wz = fz * sy + fx * sx
        val top = d.maxSpeed * (if (d.stinkT > 0f) 0.6f else 1f)

        if (d.dashT > 0f) {
            d.dashT -= dt
            d.vx = sin(d.yaw) * DASH_SPEED
            d.vz = cos(d.yaw) * DASH_SPEED
            d.vy = max(d.vy, 0f)
        } else {
            val tx = wx * top
            val tz = wz * top
            val pushing = hypot(wx, wz) > 0.05f
            // Reversing on the ground is instant.
            if (d.onGround && pushing && d.vx * tx + d.vz * tz < 0f) { d.vx = 0f; d.vz = 0f }
            val accel = when {
                pushing -> if (d.onGround) GROUND_ACCEL else AIR_ACCEL
                else -> if (d.onGround) GROUND_FRICTION else AIR_FRICTION
            }
            val dvx = tx - d.vx; val dvz = tz - d.vz
            val dl = hypot(dvx, dvz)
            val step = accel * dt
            if (dl <= step) { d.vx = tx; d.vz = tz } else { d.vx += dvx / dl * step; d.vz += dvz / dl * step }
        }
        if (d.speed > 0.4f) d.yaw = turnToward(d.yaw, atan2(d.vx, d.vz), TURN_RATE * dt)

        d.coyote = if (d.onGround) COYOTE else d.coyote - dt
        d.jumpBuffer = if (jumpP) JUMP_BUFFER else d.jumpBuffer - dt
        if (d.jumpBuffer > 0f && d.coyote > 0f) {
            d.vy = d.jumpV
            d.jumpBuffer = 0f
            d.coyote = 0f
            d.onGround = false
            audio.play(WalkSound.JUMP)
        }
        // Letting go of JUMP on the way up cuts the jump short, for quick little hops.
        if (d.jumpHeld && !input.jump && d.vy > 0f) d.vy *= 0.5f
        d.jumpHeld = input.jump
        if (d.dashT <= 0f) {
            val g = if (d.vy > 0f && d.jumpHeld) HOLD_GRAVITY else GRAVITY
            d.vy = max(d.vy - g * dt, -MAX_FALL)
        }

        d.x += d.vx * dt
        d.z += d.vz * dt
        pushOut(d)
        val lim = level.half - d.radius
        d.x = d.x.coerceIn(-lim, lim)
        d.z = d.z.coerceIn(-lim, lim)

        val prevY = d.y
        d.y += d.vy * dt
        d.onGround = false
        if (d.vy <= 0f) {
            for (s in level.solids) {
                if (s.top > 30f) continue
                val r = d.radius * 0.5f
                if (d.x < s.x0 - r || d.x > s.x1 + r || d.z < s.z0 - r || d.z > s.z1 + r) continue
                if (prevY >= s.top - 0.02f && d.y <= s.top) {
                    d.y = s.top
                    if (s.bouncy) {
                        d.vy = BOUNCE_V
                        audio.play(WalkSound.JUMP)
                        popup("BOING!", d.x, d.y + 1.2f, d.z, WHITE, 8f)
                    } else {
                        d.vy = 0f
                        d.onGround = true
                    }
                    break
                }
            }
        }
        if (d.y <= 0f) { d.y = 0f; d.vy = 0f; d.onGround = true }
        d.stride += d.speed * dt
        return sy
    }

    /** Pushes the dog out of any solid it's walked into (unless it's up on top). */
    private fun pushOut(d: Dog3) {
        for (s in level.solids) {
            if (d.y >= s.top - 0.05f) continue
            val x0 = s.x0 - d.radius; val x1 = s.x1 + d.radius
            val z0 = s.z0 - d.radius; val z1 = s.z1 + d.radius
            if (d.x <= x0 || d.x >= x1 || d.z <= z0 || d.z >= z1) continue
            val pl = d.x - x0; val pr = x1 - d.x; val pb = d.z - z0; val pf = z1 - d.z
            val m = min(min(pl, pr), min(pb, pf))
            when (m) {
                pl -> { d.x = x0; d.vx = min(d.vx, 0f) }
                pr -> { d.x = x1; d.vx = max(d.vx, 0f) }
                pb -> { d.z = z0; d.vz = min(d.vz, 0f) }
                else -> { d.z = z1; d.vz = max(d.vz, 0f) }
            }
        }
    }

    private fun bark() {
        val d = dog
        // Longer than the freeze, so critters can't be kept frozen forever.
        d.cooldown = 2f
        d.barkT = 0.45f
        audio.play(WalkSound.ARP)
        popup("ARP ARP!", d.x, d.y + 1.3f, d.z, WHITE, 10f)
        for (c in critters) {
            if ((c.state == State3.IDLE || c.state == State3.FLEE) && hypot(c.x - d.x, c.z - d.z) < 7f) c.frozenT = 1.6f
        }
    }

    private fun dash() {
        dog.cooldown = 0.7f
        dog.dashT = 0.22f
        audio.play(WalkSound.GAV)
        popup("GAV!", dog.x, dog.y + 1.2f, dog.z, WHITE, 9f)
    }

    /** Swings the camera round behind the dog as it runs (but not when running toward the camera). */
    private fun followCamera(dt: Float, stickY: Float) {
        val d = dog
        // Ease round behind the dog while it runs away from (or across) the view, but never
        // when it runs toward the camera, and never quickly.
        val off = abs(wrapAngle(d.yaw - camYaw))
        if (state == State.PLAYING && d.speed > 1f && stickY > -0.3f && off < 1.9f) {
            camYaw = turnToward(camYaw, d.yaw, CAM_TURN * dt * min(1f, d.speed / d.maxSpeed))
        }
        // Follow the ground the dog stands on, not every jump.
        if (d.onGround) camFloor += (d.y - camFloor) * min(1f, dt * 5f)
        val k = 1f - exp(-dt * 7f)
        val tx = d.x - sin(camYaw) * CAM_DIST
        val tz = d.z - cos(camYaw) * CAM_DIST
        val ty = camFloor + CAM_HEIGHT + (d.y - camFloor) * 0.12f
        camX += (tx - camX) * k
        camZ += (tz - camZ) * k
        camY += (ty - camY) * k
        lookY += (camFloor + 0.8f + (d.y - camFloor) * 0.35f - lookY) * k
    }

    private fun showTip(text: String) {
        if (!tipsShown.add(text)) return
        tip = text
        tipT = 4f
    }

    private fun popup(text: String, x: Float, y: Float, z: Float, color: Int, size: Float = 8f) {
        popups += Popup3(text, x, y, z, color, size)
    }

    private fun updatePopups(dt: Float) {
        for (p in popups) p.t += dt
        popups.removeAll { it.t > 1.3f }
    }

    private fun maybeTip(c: Critter3) {
        if (c.state == State3.GONE || c.state == State3.CHASED) return
        if (hypot(c.x - dog.x, c.z - dog.z) > 9f) return
        val h = dog.herc
        when (c.kind) {
            Kind.CAT -> showTip("Jump up on the fence to get the cat!")
            Kind.RABBIT -> showTip(if (h) "Bunnies are fast! DASH to catch them." else "Bunnies are fast! BARK to freeze them.")
            Kind.RACCOON -> showTip("Quick! Raccoons hide in the bins.")
            Kind.SKUNK -> showTip("A skunk! Stay away from it!")
            Kind.CRAB -> showTip("Catch the crabs before they hide!")
            Kind.SQUIRREL -> if (c.state == State3.SAFE) {
                showTip(if (h) "Too high for Hercules! Catch them before they climb." else "Up the tree? Jump and grab it!")
            }
            else -> {}
        }
    }

    // ------------------------------------------------------------------ Catching

    private fun touching(c: Critter3): Boolean {
        val d = dog
        return hypot(c.x - d.x, c.z - d.z) < d.radius + c.radius + 0.1f &&
            d.y < c.y + c.height && c.y < d.y + d.height
    }

    private fun checkCatches() {
        val d = dog
        for (c in critters) {
            if (c.kind == Kind.SKUNK) {
                if (c.state == State3.IDLE && d.stinkT <= 0f && touching(c)) sprayed(c)
                continue
            }
            if (c.catchable && touching(c)) caught(c)
        }
    }

    private fun sprayed(c: Critter3) {
        val d = dog
        d.stinkT = 4f
        val l = hypot(d.x - c.x, d.z - c.z).coerceAtLeast(0.1f)
        d.vx = (d.x - c.x) / l * 6f
        d.vz = (d.z - c.z) / l * 6f
        d.vy = 5f
        d.dashT = 0f
        score = max(0, score - 100)
        popup("PEE-YEW!", d.x, d.y + 1.4f, d.z, STINK, 11f)
        audio.play(WalkSound.SKUNK)
    }

    private fun caught(c: Critter3) {
        val d = dog
        c.state = State3.CHASED
        c.t = 0f
        c.climbing = false
        c.hidden = false
        c.frozenT = 0f
        val l = hypot(c.x - d.x, c.z - d.z).coerceAtLeast(0.1f)
        c.vx = (c.x - d.x) / l * 8f
        c.vz = (c.z - d.z) / l * 8f
        c.vy = if (c.kind == Kind.PIGEON || c.kind == Kind.GULL) 4f else if (c.y > 0.1f) 3f else 0f
        c.yaw = atan2(c.vx, c.vz)
        chased++
        score += c.kind.points
        popup("+${c.kind.points}", c.x, c.y + 1f, c.z, YELLOW, 9f)
        audio.play(WalkSound.CATCH)
        combo = if (clock - lastCatch < 2.5f) combo + 1 else 1
        lastCatch = clock
        if (combo >= 2) {
            val extra = 50 * combo
            score += extra
            popup("COMBO x$combo +$extra", d.x, d.y + 1.8f, d.z, CYAN, 9f)
            audio.play(WalkSound.TEAM)
        }
        if (chased == level.target) {
            tipsShown.remove(HOME_TIP)
            showTip(HOME_TIP)
            audio.play(WalkSound.TEAM)
        }
    }

    // ------------------------------------------------------------------ Bikes and treats

    private fun loopLength(): Float {
        val pts = level.bikeLoop
        var len = 0f
        for (i in pts.indices) {
            val a = pts[i]; val b = pts[(i + 1) % pts.size]
            len += hypot(b[0] - a[0], b[1] - a[1])
        }
        return len
    }

    private fun placeBike(b: Bike3) {
        val pts = level.bikeLoop
        if (pts.size < 2) return
        val total = loopLength()
        var s = ((b.s % total) + total) % total
        for (i in pts.indices) {
            val a = pts[i]; val c = pts[(i + 1) % pts.size]
            val seg = hypot(c[0] - a[0], c[1] - a[1])
            if (s <= seg) {
                val t = if (seg == 0f) 0f else s / seg
                b.x = lerp(a[0], c[0], t)
                b.z = lerp(a[1], c[1], t)
                b.yaw = atan2(c[0] - a[0], c[1] - a[1])
                return
            }
            s -= seg
        }
    }

    private fun updateBikes(dt: Float) {
        val d = dog
        for (b in bikes) {
            b.s += BIKE_SPEED * dt
            b.t += dt
            placeBike(b)
            val dist = hypot(b.x - d.x, b.z - d.z)
            if (b.bellT > 0f) b.bellT -= dt
            if (dist < 10f && b.bellT <= 0f) {
                b.bellT = 6f
                audio.play(WalkSound.BELL)
                showTip("Ring ring! Watch out for the bikes!")
            }
            if (dist < 0.85f && d.y < 0.9f && d.hurtT <= 0f) {
                hearts--
                d.hurtT = 1.5f
                val l = dist.coerceAtLeast(0.1f)
                d.vx = (d.x - b.x) / l * 7f
                d.vz = (d.z - b.z) / l * 7f
                d.vy = 6f
                d.dashT = 0f
                audio.play(WalkSound.OUCH)
                popup("YELP!", d.x, d.y + 1.4f, d.z, 0xFFFF6B6B.toInt(), 11f)
                if (hearts <= 0) {
                    best = max(best, score).also { storage.save("best3d", it) }
                    setState(State.OUCH)
                    return
                }
            }
        }
    }

    private fun collectTreats() {
        val d = dog
        for (t in treats) {
            if (t.taken) continue
            val dy = t.y - (d.y + d.height / 2)
            if (sqrt((t.x - d.x) * (t.x - d.x) + dy * dy + (t.z - d.z) * (t.z - d.z)) < d.radius + 0.45f) {
                t.taken = true
                treatsGot++
                score += 25
                audio.play(WalkSound.TREAT)
                popup("+25", t.x, t.y + 0.6f, t.z, WHITE, 7f)
            }
        }
    }

    // ------------------------------------------------------------------ Home

    val goalReached get() = chased >= level.target

    private fun checkHome(dt: Float) {
        if (nagT > 0f) nagT -= dt
        val dist = hypot(dog.x - level.homeX, dog.z - level.homeZ)
        if (dist > 1.8f) return
        if (goalReached) {
            setState(State.HOME)
            tip = null
            audio.music(WalkTune.NONE)
            audio.play(WalkSound.HOME)
        } else if (nagT <= 0f) {
            nagT = 4f
            tip = "Chase ${level.target - chased} more first!"
            tipT = 2.5f
        }
    }

    private fun finishLevel() {
        val timeBonus = max(0, ((level.def.par - levelTime) * 10f).toInt())
        bonus = hearts * 100 + timeBonus + (if (chased == level.total) 1000 else 0)
        score += bonus
        best = max(best, score).also { storage.save("best3d", it) }
        if (levelIndex < Levels.all.lastIndex) slots.save(mode(dog.herc), levelIndex + 1, score)
        setState(State.RESULTS)
        audio.say(if (chased == level.total) "Wow! Super good dog!" else "Good dog, ${dog.name}!")
    }

    /** 1 to 3 stars, for how quickly you got home. */
    val stars get() = when {
        levelTime <= level.def.par -> 3
        levelTime <= level.def.par * 1.6f -> 2
        else -> 1
    }

    /** Where the compass arrow points: the nearest critter still out, or home once you've chased enough. */
    fun compassTarget(): FloatArray? {
        if (goalReached) return floatArrayOf(level.homeX, level.homeZ)
        val c = critters.filter { it.catchable && !it.hidden }.minByOrNull { hypot(it.x - dog.x, it.z - dog.z) } ?: return null
        return floatArrayOf(c.x, c.z)
    }

    /** Builds this frame's 3D scene for a screen of the given aspect ratio. */
    fun buildFrame(aspect: Float): Frame {
        Models.build(this, frame, aspect)
        return frame
    }

    companion object {
        const val GROUND_ACCEL = 60f
        const val AIR_ACCEL = 45f
        const val GROUND_FRICTION = 70f
        const val AIR_FRICTION = 30f
        const val TURN_RATE = 20f
        const val GRAVITY = 38f
        const val HOLD_GRAVITY = 14f
        const val MAX_FALL = 18f
        const val COYOTE = 0.08f
        const val JUMP_BUFFER = 0.12f
        const val DASH_SPEED = 15f
        const val BOUNCE_V = 13f
        const val BIKE_SPEED = 6f
        const val CAM_DIST = 5.6f
        const val CAM_HEIGHT = 2.7f
        const val CAM_TURN = 1.3f
        const val HOME_TIP = "That's plenty! Follow the arrow HOME!"
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val YELLOW = 0xFFFFE14D.toInt()
        private const val CYAN = 0xFF7DF9FF.toInt()
        private const val STINK = 0xFF9BE15D.toInt()
    }
}
