package com.javelinstrike.game

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random

enum class ControlMode(val label: String) { MOTION("MOTION"), TOUCH("TOUCH") }

/** How hard you have to swing for full power. */
enum class ArmStrength(val label: String, val sensitivity: Float) {
    GENTLE("GENTLE", 1.8f),
    NORMAL("NORMAL", 1f),
    STRONG("STRONG", 0.75f),
}

/**
 * Javelin Strike: hit the target before the clock runs out, with as many throws as you like.
 * The game runs at a fixed 60 updates a second; [render] draws the current frame.
 */
class Game(
    private val input: Input,
    private val sound: SoundSink,
    private val store: Storage,
    seed: Long = System.nanoTime(),
) {
    enum class Screen { TITLE, HOWTO, INTRO, PLAY, CLEAR, TIME_UP, GAME_OVER }
    enum class ThrowState { READY, FLIGHT, LANDED }

    /** Size of the view in virtual pixels. The width is fixed; the height follows the screen's shape. */
    var viewW = VIEW_W
    var viewH = 760f
    val detector = ThrowDetector()

    /** False when the phone has no gyroscope; touch controls are used instead. */
    @Volatile var motionAvailable = true
        set(v) {
            field = v
            if (!v) controls = ControlMode.TOUCH
        }

    var controls = ControlMode.entries.getOrElse(store.load(KEY_CONTROLS)) { ControlMode.MOTION }
        private set
    // Stored as ordinal + 1 so that 0 (nothing saved yet) means NORMAL.
    var arm = ArmStrength.entries.getOrElse(store.load(KEY_ARM) - 1) { ArmStrength.NORMAL }
        private set

    var screen = Screen.TITLE
        private set
    var paused = false
        private set

    internal val rng = Random(seed)
    internal var levelNum = 1
    internal var spec = levelSpec(1)
    internal var score = 0
    private var scoreAtLevelStart = 0
    internal var lives = START_LIVES
    internal var hits = 0
    internal var timeLeft = 0f
    internal var wind = 0f
    internal var target = Target(0f, 40f, 5f)
    internal val stuck = ArrayList<Stuck>()
    internal var javelin: Javelin? = null
    internal var throwState = ThrowState.READY
    private var throwWind = 0f
    private var holdT = 0f
    private var moveTargetAfterHold = false
    internal var screenT = 0f
    internal var clock = 0f
    internal var aimYaw = 0f
    internal var lastPower = -1f
    internal var lastDistance = 0f
    internal var sinceRelease = 99f
    internal var cheerT = 0f
    internal var levelBonus = 0
    internal var gotExtraLife = false
    internal val particles = ArrayList<Particle>()

    internal var msg = ""
    internal var msgSub = ""
    internal var msgColor = WHITE
    internal var msgT = 0f

    internal val cam = Camera()
    private val restCam = Camera()
    internal val insetCam = Camera()

    internal val highScores = IntArray(5) { store.load("hs$it") }
    internal val highLevels = IntArray(5) { store.load("hl$it") }
    internal var newHighRank = -1

    private val flick = FlickThrow()
    private var fingerDown = false
    private var fingerX = 0f
    private var fingerY = 0f
    private var fingerT = 0f
    private var wasReady = false
    internal var playAfterHowTo = false
        private set
    private var lastTickSecond = -1
    private val touches = ArrayList<Input.Touch>()

    init {
        restCam.apply { x = -0.6f; y = 3.4f; z = -4.5f; pitch = REST_PITCH; focal = FOCAL }
        cam.set(restCam)
        showTitle()
    }

    // ---------------------------------------------------------------- update

    fun update(dt: Float) {
        clock += dt
        screenT += dt
        if (msgT > 0f) msgT -= dt
        if (cheerT > 0f) cheerT -= dt
        sinceRelease += dt
        detector.sensitivity = arm.sensitivity

        if (input.back) {
            input.back = false
            onBack()
        }
        touches.clear()
        input.drain(touches)
        for (t in touches) onTouch(t)

        val ready = screen == Screen.PLAY && !paused && throwState == ThrowState.READY && timeLeft > 0f
        detector.enabled = ready && controls == ControlMode.MOTION
        if (!ready) flick.cancel()
        if (ready && !wasReady && fingerDown) startGrip(fingerX, fingerY, fingerT)
        wasReady = ready

        if (detector.takeDrawn()) {
            sound.play(Sound.DRAWN)
            sound.vibrate(15)
        }
        detector.takeThrow()?.let { if (ready) throwJavelin(it, aimYaw) }
        if (detector.takeCancel()) showMessage("HOLD ON!", "Keep your thumb down until you throw", ORANGE, 2f)

        if (ready && controls == ControlMode.MOTION && detector.phase == ThrowDetector.Phase.IDLE) {
            val want = if (input.hasTilt) (input.tilt * 0.7f).coerceIn(-MAX_AIM, MAX_AIM) else 0f
            aimYaw += (want - aimYaw) * min(1f, dt * 8f)
        }
        if (flick.active) aimYaw = flick.previewYaw.coerceIn(-MAX_AIM, MAX_AIM)

        if (screen == Screen.PLAY && !paused) updatePlay(dt)
        if (screen == Screen.TITLE) target.update(dt)
        if (!paused) updateParticles(dt)
        updateCamera(dt)
    }

    private fun updatePlay(dt: Float) {
        target.update(dt)
        if (timeLeft > 0f) {
            timeLeft = max(0f, timeLeft - dt)
            val sec = ceil(timeLeft).toInt()
            if (sec != lastTickSecond) {
                lastTickSecond = sec
                if (sec in 1..5) sound.play(Sound.TICK)
            }
        }
        when (throwState) {
            ThrowState.READY -> if (timeLeft <= 0f) timeUp()
            ThrowState.FLIGHT -> {
                val j = javelin ?: return
                val sub = dt * FLIGHT_SPEED / 4f
                repeat(4) { if (j.step(sub, throwWind)) { landed(j); return } }
            }
            ThrowState.LANDED -> {
                holdT -= dt
                if (holdT <= 0f) afterHold()
            }
        }
    }

    internal fun throwJavelin(power: Float, yaw: Float) {
        val p = power.coerceIn(0f, 1f)
        lastPower = p
        javelin = Javelin.launch(p, yaw.coerceIn(-MAX_AIM, MAX_AIM))
        throwWind = wind * (0.85f + rng.nextFloat() * 0.3f)
        throwState = ThrowState.FLIGHT
        sinceRelease = 0f
        aimYaw = yaw
        sound.play(Sound.WHOOSH)
        sound.vibrate(35)
    }

    private fun landed(j: Javelin) {
        val sp = j.speed.coerceAtLeast(0.01f)
        val ring = target.ring(j.x, j.z)
        stuck += Stuck(j.x, j.z, j.vx / sp, j.vy / sp, j.vz / sp, ring > 0)
        if (stuck.size > MAX_STUCK) stuck.removeAt(0)
        lastDistance = hypot(j.x, j.z)
        javelin = null
        throwState = ThrowState.LANDED
        holdT = LAND_HOLD
        dirt(j.x, j.z)
        sound.play(Sound.THUD)
        if (ring > 0) {
            val pts = RING_POINTS[ring] * levelNum
            score += pts
            hits++
            cheerT = 2.5f
            confetti(target.x, target.z)
            sound.play(if (ring == 3) Sound.BULLSEYE else Sound.HIT)
            sound.play(Sound.CHEER)
            sound.vibrate(if (ring == 3) 120 else 60)
            val title = when (ring) { 3 -> "BULLSEYE!"; 2 -> "GREAT HIT!"; else -> "HIT!" }
            showMessage(title, "+$pts", if (ring == 3) GOLD else GREEN, LAND_HOLD + 0.3f)
            moveTargetAfterHold = hits < spec.hits
        } else {
            val dz = j.z - target.z
            val dx = j.x - target.x
            val text = if (abs(dz) >= abs(dx)) {
                "${abs(dz).roundToInt()} m " + if (dz < 0) "SHORT" else "LONG"
            } else {
                "${abs(dx).roundToInt()} m " + if (dx < 0) "LEFT" else "RIGHT"
            }
            showMessage(text, "${oneDecimal(lastDistance)} m throw", ORANGE, LAND_HOLD + 0.3f)
        }
    }

    private fun afterHold() {
        if (hits >= spec.hits) {
            levelCleared()
            return
        }
        if (moveTargetAfterHold) {
            moveTargetAfterHold = false
            placeTarget(first = false)
        }
        throwState = ThrowState.READY
        if (timeLeft <= 0f) timeUp()
    }

    private fun levelCleared() {
        levelBonus = ceil(timeLeft).toInt() * 10 * levelNum
        score += levelBonus
        gotExtraLife = levelNum % 3 == 0 && lives < MAX_LIVES
        if (gotExtraLife) lives++
        if (levelNum > store.load(KEY_BEST_LEVEL)) store.save(KEY_BEST_LEVEL, levelNum)
        sound.play(Sound.CLEAR)
        go(Screen.CLEAR)
    }

    private fun timeUp() {
        lives--
        if (lives <= 0) {
            recordHighScore()
            sound.play(Sound.GAME_OVER)
            go(Screen.GAME_OVER)
        } else {
            sound.play(Sound.FAIL)
            go(Screen.TIME_UP)
        }
    }

    private fun recordHighScore() {
        newHighRank = -1
        val rank = highScores.indexOfFirst { score > it }
        if (rank < 0 || score <= 0) return
        for (i in highScores.lastIndex downTo rank + 1) {
            highScores[i] = highScores[i - 1]
            highLevels[i] = highLevels[i - 1]
        }
        highScores[rank] = score
        highLevels[rank] = levelNum
        newHighRank = rank
        for (i in highScores.indices) {
            store.save("hs$i", highScores[i])
            store.save("hl$i", highLevels[i])
        }
    }

    internal fun startGame() {
        score = 0
        lives = START_LIVES
        startLevel(1)
    }

    internal fun startLevel(n: Int) {
        levelNum = n
        spec = levelSpec(n)
        scoreAtLevelStart = score
        hits = 0
        timeLeft = spec.time
        lastTickSecond = -1
        stuck.clear()
        particles.clear()
        javelin = null
        throwState = ThrowState.READY
        lastPower = -1f
        lastDistance = 0f
        msgT = 0f
        moveTargetAfterHold = false
        wind = if (spec.wind > 0f) {
            val strength = spec.wind * (0.5f + rng.nextFloat() * 0.5f)
            if (rng.nextBoolean()) strength else -strength
        } else 0f
        placeTarget(first = true)
        go(Screen.INTRO)
    }

    private fun placeTarget(first: Boolean) {
        val z = if (first) spec.distance else spec.distance * (0.9f + rng.nextFloat() * 0.2f)
        // Keep the whole sway within reach of the furthest aim, and on the field.
        val reach = max(0f, min((z - spec.swayZ) * 0.4f, FIELD_EDGE - spec.radius) - spec.sway)
        val x = ((rng.nextFloat() * 2f - 1f) * spec.spread).coerceIn(-reach, reach)
        target = Target(x, z, spec.radius, spec.sway, spec.swayZ, spec.swaySpeed, phase = rng.nextFloat() * 6.28f)
    }

    private fun showTitle() {
        paused = false
        target = Target(3f, 42f, 5f, sway = 4f, speed = 0.5f)
        stuck.clear()
        stuck += Stuck(5f, 43f, 0.05f, -0.6f, 0.8f, true)
        stuck += Stuck(1.5f, 38f, -0.02f, -0.55f, 0.83f, false)
        stuck += Stuck(7.5f, 47f, 0.1f, -0.62f, 0.78f, false)
        javelin = null
        particles.clear()
        throwState = ThrowState.READY
        wind = 2f
        go(Screen.TITLE)
    }

    private fun go(s: Screen) {
        screen = s
        screenT = 0f
        if (s != Screen.PLAY) paused = false
    }

    private fun showMessage(title: String, sub: String, color: Int, time: Float) {
        msg = title
        msgSub = sub
        msgColor = color
        msgT = time
    }

    private fun onBack() {
        when (screen) {
            Screen.PLAY -> paused = !paused
            Screen.TITLE -> {}
            else -> showTitle()
        }
    }

    // ---------------------------------------------------------------- input

    private fun onTouch(t: Input.Touch) {
        when (t.type) {
            Input.DOWN -> { fingerDown = true; fingerX = t.x; fingerY = t.y; fingerT = t.time }
            Input.MOVE -> { fingerX = t.x; fingerY = t.y; fingerT = t.time }
            Input.UP -> fingerDown = false
        }
        val press = t.type == Input.DOWN || t.type == Input.TAP
        when (screen) {
            Screen.TITLE -> if (press) titleTap(t.x, t.y)
            Screen.HOWTO -> if (press && screenT > 0.4f) {
                sound.play(Sound.CLICK)
                if (playAfterHowTo) startGame() else go(Screen.TITLE)
            }
            Screen.INTRO -> if (press && screenT > 0.4f) go(Screen.PLAY)
            Screen.PLAY -> playTouch(t)
            Screen.CLEAR -> if (press && screenT > 1f) {
                sound.play(Sound.CLICK)
                startLevel(levelNum + 1)
            }
            Screen.TIME_UP -> if (press && screenT > 1f) {
                sound.play(Sound.CLICK)
                score = scoreAtLevelStart
                startLevel(levelNum)
            }
            Screen.GAME_OVER -> if (press && screenT > 1.5f) {
                sound.play(Sound.CLICK)
                showTitle()
            }
        }
    }

    private fun playTouch(t: Input.Touch) {
        val press = t.type == Input.DOWN || t.type == Input.TAP
        if (paused) {
            if (!press) return
            if (inside(resumeButton(), t.x, t.y)) { sound.play(Sound.CLICK); paused = false }
            else if (inside(quitButton(), t.x, t.y)) { sound.play(Sound.CLICK); showTitle() }
            return
        }
        if (press && inside(pauseButton(), t.x, t.y)) {
            sound.play(Sound.CLICK)
            paused = true
            fingerDown = false
            return
        }
        when (t.type) {
            Input.DOWN -> startGrip(t.x, t.y, t.time)
            Input.MOVE -> flick.move(t.x, t.y, t.time)
            Input.UP -> {
                detector.grip(false)
                val f = flick.up(t.x, t.y, t.time, arm.sensitivity)
                if (f != null && throwState == ThrowState.READY && controls == ControlMode.TOUCH) {
                    throwJavelin(f.first, f.second)
                }
            }
        }
    }

    private fun startGrip(x: Float, y: Float, time: Float) {
        if (screen != Screen.PLAY || paused || throwState != ThrowState.READY) return
        if (controls == ControlMode.MOTION) {
            detector.grip(true)
            sound.play(Sound.GRIP)
        } else {
            flick.down(x, y, time)
        }
    }

    private fun titleTap(x: Float, y: Float) {
        when {
            inside(titleButton(0), x, y) -> {
                sound.play(Sound.CLICK)
                if (store.load(KEY_SEEN_HOWTO) == 0) {
                    store.save(KEY_SEEN_HOWTO, 1)
                    playAfterHowTo = true
                    go(Screen.HOWTO)
                } else {
                    startGame()
                }
            }
            inside(titleButton(1), x, y) -> {
                sound.play(Sound.CLICK)
                if (motionAvailable) {
                    controls = if (controls == ControlMode.MOTION) ControlMode.TOUCH else ControlMode.MOTION
                    store.save(KEY_CONTROLS, controls.ordinal)
                }
            }
            inside(titleButton(2), x, y) -> {
                sound.play(Sound.CLICK)
                arm = ArmStrength.entries[(arm.ordinal + 1) % ArmStrength.entries.size]
                store.save(KEY_ARM, arm.ordinal + 1)
            }
            inside(titleButton(3), x, y) -> {
                sound.play(Sound.CLICK)
                playAfterHowTo = false
                go(Screen.HOWTO)
            }
        }
    }

    // ---------------------------------------------------------------- effects

    private fun dirt(x: Float, z: Float) {
        repeat(10) {
            particles += Particle(
                x, 0.1f, z,
                (rng.nextFloat() - 0.5f) * 3f, 1.5f + rng.nextFloat() * 2.5f, (rng.nextFloat() - 0.5f) * 3f,
                0.8f + rng.nextFloat() * 0.4f, if (it % 2 == 0) 0xFF7A5230.toInt() else 0xFF9C7448.toInt(), 0.25f, true,
            )
        }
    }

    private fun confetti(x: Float, z: Float) {
        repeat(40) {
            particles += Particle(
                x + (rng.nextFloat() - 0.5f) * 3f, 2f + rng.nextFloat() * 3f, z + (rng.nextFloat() - 0.5f) * 3f,
                (rng.nextFloat() - 0.5f) * 6f, 4f + rng.nextFloat() * 6f, (rng.nextFloat() - 0.5f) * 6f,
                2f + rng.nextFloat(), CONFETTI[it % CONFETTI.size], 0.35f, false,
            )
        }
    }

    private fun updateParticles(dt: Float) {
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) { it.remove(); continue }
            if (p.heavy) {
                p.vy -= GRAVITY * dt
            } else {
                p.vy = max(p.vy - GRAVITY * dt, -1.2f)
                p.vx *= 1f - dt
                p.vz *= 1f - dt
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.z += p.vz * dt
            if (p.y < 0f) { p.y = 0f; p.vx = 0f; p.vy = 0f; p.vz = 0f }
        }
    }

    private fun updateCamera(dt: Float) {
        restCam.cx = viewW / 2f
        val j = javelin
        val follow: Pair<Float, Float>? = when {
            j != null && throwState == ThrowState.FLIGHT -> j.x to j.z
            throwState == ThrowState.LANDED && stuck.isNotEmpty() -> stuck.last().x to stuck.last().z
            else -> null
        }
        val k: Float
        if (follow != null && follow.second > 6f) {
            val (fx, fz) = follow
            val far = min(1f, fz / 60f)
            k = 1f - exp(-dt * 3.5f)
            cam.x += (fx * 0.8f + restCam.x * (1f - far) - cam.x) * k
            cam.y += (3f + far * 5f - cam.y) * k
            cam.z += (max(restCam.z, fz - 16f) - cam.z) * k
            cam.pitch += (REST_PITCH + far * 0.12f - cam.pitch) * k
        } else {
            k = 1f - exp(-dt * if (screen == Screen.PLAY) 5f else 2f)
            val sway = if (screen == Screen.TITLE) sin(clock * 0.3f) * 1.2f else 0f
            cam.x += (restCam.x + sway - cam.x) * k
            cam.y += (restCam.y - cam.y) * k
            cam.z += (restCam.z - cam.z) * k
            cam.pitch += (restCam.pitch - cam.pitch) * k
        }
        cam.cx = viewW / 2f
        // Puts the horizon about a third of the way down the screen.
        restCam.cy = viewH * 0.33f + FOCAL * kotlin.math.tan(REST_PITCH)
        cam.cy = restCam.cy
        cam.focal = restCam.focal
    }

    // ---------------------------------------------------------------- layout (portrait)

    internal fun titleButton(i: Int) = floatArrayOf(viewW / 2f - 130f, viewH * 0.40f + i * 52f, 260f, 42f)
    internal fun pauseButton() = floatArrayOf(viewW - 44f, 8f, 36f, 32f)
    internal fun resumeButton() = floatArrayOf(viewW / 2f - 110f, viewH * 0.45f, 220f, 44f)
    internal fun quitButton() = floatArrayOf(viewW / 2f - 110f, viewH * 0.45f + 58f, 220f, 44f)
    internal fun insetBox() = floatArrayOf(viewW - 150f, 84f, 142f, 112f)

    private fun inside(r: FloatArray, x: Float, y: Float) = x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]

    // ---------------------------------------------------------------- render

    fun render(g: Gfx) {
        drawWorld(g, cam, main = true, 0f, 0f, viewW, viewH)
        when (screen) {
            Screen.TITLE -> drawTitle(g)
            Screen.HOWTO -> drawHowTo(g)
            else -> {
                drawInset(g)
                drawHud(g)
                when (screen) {
                    Screen.INTRO -> drawIntro(g)
                    Screen.PLAY -> if (paused) drawPause(g) else drawPrompts(g)
                    Screen.CLEAR -> drawClear(g)
                    Screen.TIME_UP -> drawTimeUp(g)
                    Screen.GAME_OVER -> drawGameOver(g)
                    else -> {}
                }
            }
        }
    }

    /** How far back the athlete's arm is, for drawing. */
    internal fun drawPose(): Float = when {
        screen != Screen.PLAY -> 0f
        controls == ControlMode.MOTION -> detector.drawAmount
        else -> flick.drawAmount
    }

    internal fun flickActive() = flick.active

    internal fun holdingJavelin() = throwState == ThrowState.READY && sinceRelease > 0.5f

    internal fun bestLevel() = store.load(KEY_BEST_LEVEL)

    internal fun hasTilt() = input.hasTilt

    companion object {
        const val START_LIVES = 3
        const val FOCAL = 420f
        const val REST_PITCH = 0.3f
        const val MAX_LIVES = 5
        const val MAX_STUCK = 12
        /** Javelins fly faster than real time so you can throw again sooner. */
        const val FLIGHT_SPEED = 1.5f
        /** Seconds to look at where the javelin landed. */
        const val LAND_HOLD = 1.1f
        val RING_POINTS = intArrayOf(0, 50, 100, 250)

        const val KEY_CONTROLS = "controls"
        const val KEY_ARM = "arm"
        const val KEY_SEEN_HOWTO = "howto"
        const val KEY_BEST_LEVEL = "bestLevel"

        const val WHITE = 0xFFFFFFFF.toInt()
        const val GOLD = 0xFFF2C14E.toInt()
        const val RED = 0xFFFF4B3E.toInt()
        const val GREEN = 0xFF5BE36B.toInt()
        const val ORANGE = 0xFFFF9A3C.toInt()
        const val CYAN = 0xFF9FE0F0.toInt()
        /** Olive leaves and flower petals thrown by the crowd. */
        val CONFETTI = intArrayOf(0xFF7C9A3A.toInt(), 0xFF5E7A2A.toInt(), 0xFFFFD34D.toInt(), 0xFFFFFFFF.toInt(), 0xFFD9443A.toInt())
    }
}
