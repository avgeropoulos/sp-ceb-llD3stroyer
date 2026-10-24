package com.javelinstrike.game

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
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

    /** Width of the view in virtual pixels; set by the platform from the screen shape. */
    var viewW = 640f
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

    /** True while a level is under way, so the phone can lock its screen rotation. */
    val inMatch get() = screen != Screen.TITLE && screen != Screen.HOWTO

    internal val rng = Random(seed)
    internal var levelNum = 1
    internal var spec = levelSpec(1)
    internal var score = 0
    private var scoreAtLevelStart = 0
    internal var lives = START_LIVES
    internal var hits = 0
    internal var timeLeft = 0f
    internal var wind = 0f
    internal var target = Target(0f, 40f, 5f, 0f, 0f)
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
    private var playAfterHowTo = false
    private var lastTickSecond = -1
    private val touches = ArrayList<Input.Touch>()

    init {
        restCam.apply { x = -1.4f; y = 3f; z = -7f; pitch = 0.14f; focal = 420f }
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
        // Keep the whole sway within reach of the furthest aim.
        val reach = max(0f, z * 0.4f - spec.sway)
        val x = ((rng.nextFloat() * 2f - 1f) * spec.spread).coerceIn(-reach, reach)
        target = Target(x, z, spec.radius, spec.sway, spec.swaySpeed)
    }

    private fun showTitle() {
        paused = false
        target = Target(4f, 42f, 5f, 0f, 0f)
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
            cam.y += (3f + far * 4f - cam.y) * k
            cam.z += (max(restCam.z, fz - 14f) - cam.z) * k
            cam.pitch += (0.14f + far * 0.16f - cam.pitch) * k
        } else {
            k = 1f - exp(-dt * if (screen == Screen.PLAY) 5f else 2f)
            val sway = if (screen == Screen.TITLE) sin(clock * 0.3f) * 1.2f else 0f
            cam.x += (restCam.x + sway - cam.x) * k
            cam.y += (restCam.y - cam.y) * k
            cam.z += (restCam.z - cam.z) * k
            cam.pitch += (restCam.pitch - cam.pitch) * k
        }
        cam.cx = viewW / 2f
        cam.cy = VIEW_H / 2f
        cam.focal = restCam.focal
    }

    // ---------------------------------------------------------------- layout

    internal fun titleButton(i: Int) = floatArrayOf(viewW * 0.30f - 120f, 132f + i * 50f, 240f, 40f)
    internal fun pauseButton() = floatArrayOf(viewW - 46f, 8f, 38f, 32f)
    internal fun resumeButton() = floatArrayOf(viewW / 2f - 110f, 160f, 220f, 42f)
    internal fun quitButton() = floatArrayOf(viewW / 2f - 110f, 214f, 220f, 42f)
    internal fun insetBox() = floatArrayOf(viewW - 168f, 48f, 160f, 104f)

    private fun inside(r: FloatArray, x: Float, y: Float) = x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]

    // ---------------------------------------------------------------- render

    fun render(g: Gfx) {
        drawWorld(g, cam, main = true, 0f, 0f, viewW, VIEW_H)
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

    internal fun holdingJavelin() = throwState == ThrowState.READY && sinceRelease > 0.5f

    private fun drawInset(g: Gfx) {
        val b = insetBox()
        insetCam.apply {
            x = target.x
            y = 13f
            z = target.z - 11f
            pitch = 0.85f
            focal = 110f
            cx = b[0] + b[2] / 2f
            cy = b[1] + b[3] / 2f + 6f
        }
        g.rect(b[0] - 2f, b[1] - 2f, b[2] + 4f, b[3] + 4f, 0xFFFFFFFF.toInt())
        g.clip(b[0], b[1], b[2], b[3])
        drawWorld(g, insetCam, main = false, b[0], b[1], b[2], b[3])
        g.unclip()
        g.rect(b[0], b[1], b[2], 13f, 0xA0000000.toInt())
        g.text("TARGET CAM  ${target.z.roundToInt()} m", b[0] + 4f, b[1] + 10f, 9f, WHITE)
    }

    private fun drawHud(g: Gfx) {
        g.shadowText("LEVEL $levelNum", 10f, 22f, 16f, GOLD)
        g.shadowText(spec.name.uppercase(), 10f, 38f, 10f, WHITE)
        for (i in 0 until lives) heart(g, 14f + i * 16f, 50f)

        // Clock
        val secs = ceil(timeLeft).toInt()
        val clockColor = if (secs <= 10 && (secs > 5 || (clock * 4f).toInt() % 2 == 0)) RED else WHITE
        g.shadowText("${secs / 60}:${(secs % 60).toString().padStart(2, '0')}", viewW / 2f, 30f, 26f, clockColor, 1)
        // Hits needed
        val pip = 12f
        val startX = viewW / 2f - (spec.hits * pip) / 2f
        for (i in 0 until spec.hits) {
            val c = if (i < hits) GOLD else 0x80FFFFFF.toInt()
            g.oval(startX + i * pip + 1f, 38f, pip - 3f, pip - 3f, 0xFF000000.toInt())
            g.oval(startX + i * pip + 2f, 39f, pip - 5f, pip - 5f, c)
        }

        g.shadowText("SCORE $score", viewW - 54f, 22f, 13f, WHITE, 2)

        // Pause button
        val p = pauseButton()
        g.rect(p[0], p[1], p[2], p[3], 0x90000000.toInt())
        g.rect(p[0] + 13f, p[1] + 8f, 4f, 16f, WHITE)
        g.rect(p[0] + 21f, p[1] + 8f, 4f, 16f, WHITE)

        // Wind
        if (wind != 0f) {
            val wy = 78f
            g.shadowText("WIND", 10f, wy, 10f, WHITE)
            val len = 8f + abs(wind) * 4f
            val ax = 46f + if (wind > 0) 0f else len
            val dir = if (wind > 0) 1f else -1f
            g.line(ax, wy - 4f, ax + dir * len, wy - 4f, 3f, CYAN)
            g.poly(
                floatArrayOf(ax + dir * (len + 6f), ax + dir * len, ax + dir * len),
                floatArrayOf(wy - 4f, wy - 9f, wy + 1f), CYAN,
            )
            g.shadowText("${oneDecimal(abs(wind))} m/s", 46f + len + 12f, wy, 10f, CYAN)
        }

        if (lastDistance > 0f) {
            g.shadowText(
                "LAST: ${oneDecimal(lastDistance)} m  POWER ${(lastPower * 100).roundToInt()}%",
                viewW - 10f, VIEW_H - 10f, 10f, WHITE, 2,
            )
        }

        if (msgT > 0f) {
            val a = min(1f, msgT * 3f)
            val c = argb((a * 255).toInt(), msgColor)
            g.text(msg, viewW / 2f + 2f, 112f, 30f, argb((a * 200).toInt(), 0), 1)
            g.text(msg, viewW / 2f, 110f, 30f, c, 1)
            if (msgSub.isNotEmpty()) g.shadowText(msgSub, viewW / 2f, 132f, 14f, argb((a * 255).toInt(), WHITE), 1)
        }
    }

    private fun heart(g: Gfx, x: Float, y: Float) {
        g.oval(x - 6f, y - 4f, 7f, 7f, RED)
        g.oval(x - 1f, y - 4f, 7f, 7f, RED)
        g.poly(floatArrayOf(x - 6f, x + 6f, x), floatArrayOf(y, y, y + 7f), RED)
    }

    private fun drawPrompts(g: Gfx) {
        if (throwState != ThrowState.READY || timeLeft <= 0f) return
        // Draw meter on the left.
        val draw = drawPose()
        val mx = 12f
        val my = 190f
        val mh = 120f
        g.rect(mx - 2f, my - 2f, 18f, mh + 4f, 0xA0000000.toInt())
        val fill = mh * draw
        val armed = controls == ControlMode.MOTION && detector.phase >= ThrowDetector.Phase.DRAWN ||
            controls == ControlMode.TOUCH && draw >= FlickThrow.PULL_MIN / FlickThrow.PULL_FULL
        g.rect(mx, my + mh - fill, 14f, fill, if (armed) GREEN else GOLD)
        g.shadowText("DRAW", mx + 7f, my + mh + 14f, 8f, WHITE, 1)

        val (line, sub) = if (controls == ControlMode.MOTION) {
            when (detector.phase) {
                ThrowDetector.Phase.IDLE -> "HOLD THE SCREEN" to (if (input.hasTilt) "Tilt the phone to aim" else "")
                ThrowDetector.Phase.GRIP -> "PULL YOUR ARM BACK" to "Keep holding the screen"
                ThrowDetector.Phase.DRAWN -> "NOW THROW!" to "Whip it forward"
                ThrowDetector.Phase.SWING -> "!!!" to ""
            }
        } else {
            if (flick.active) {
                (if (armed) "FLICK UP!" else "PULL DOWN") to "Flick sideways to aim"
            } else {
                "PRESS, PULL DOWN, FLICK UP" to "Faster flick = longer throw"
            }
        }
        if (msgT <= 0f) {
            val pulse = if (detector.phase == ThrowDetector.Phase.DRAWN || (flick.active && armed)) GREEN else WHITE
            g.shadowText(line, viewW / 2f, VIEW_H - 34f, 18f, pulse, 1)
            if (sub.isNotEmpty()) g.shadowText(sub, viewW / 2f, VIEW_H - 18f, 10f, 0xFFDDDDDD.toInt(), 1)
        }
    }

    private fun panel(g: Gfx, h: Float): Float {
        val w = min(420f, viewW - 40f)
        val x = viewW / 2f - w / 2f
        val y = VIEW_H / 2f - h / 2f + 10f
        g.rect(x, y, w, h, 0xD0101828.toInt())
        g.rect(x, y, w, 3f, GOLD)
        return y
    }

    private fun drawIntro(g: Gfx) {
        val y = panel(g, 150f)
        g.shadowText("LEVEL $levelNum", viewW / 2f, y + 34f, 26f, GOLD, 1)
        g.shadowText(spec.name.uppercase(), viewW / 2f, y + 54f, 14f, WHITE, 1)
        val needs = if (spec.hits == 1) "Hit the target" else "Hit the target ${spec.hits} times"
        g.shadowText("$needs in ${spec.time.roundToInt()} seconds", viewW / 2f, y + 80f, 13f, WHITE, 1)
        val extras = buildList {
            add("${target.z.roundToInt()} m away")
            if (wind != 0f) add("wind ${oneDecimal(abs(wind))} m/s")
            if (spec.sway > 0f) add("moving target")
        }.joinToString("  ·  ")
        g.shadowText(extras, viewW / 2f, y + 100f, 11f, CYAN, 1)
        if (screenT > 0.4f) g.shadowText("TAP TO START", viewW / 2f, y + 132f, 12f, blink(), 1)
    }

    private fun drawClear(g: Gfx) {
        val y = panel(g, 150f)
        g.shadowText("LEVEL CLEAR!", viewW / 2f, y + 36f, 26f, GREEN, 1)
        g.shadowText("Time bonus  +$levelBonus", viewW / 2f, y + 64f, 13f, WHITE, 1)
        g.shadowText("Score  $score", viewW / 2f, y + 84f, 13f, GOLD, 1)
        if (gotExtraLife) g.shadowText("EXTRA LIFE!", viewW / 2f, y + 104f, 12f, RED, 1)
        if (screenT > 1f) g.shadowText("TAP FOR LEVEL ${levelNum + 1}", viewW / 2f, y + 132f, 12f, blink(), 1)
    }

    private fun drawTimeUp(g: Gfx) {
        val y = panel(g, 130f)
        g.shadowText("TIME'S UP!", viewW / 2f, y + 38f, 26f, RED, 1)
        g.shadowText("$hits of ${spec.hits} hits", viewW / 2f, y + 62f, 13f, WHITE, 1)
        g.shadowText("$lives ${if (lives == 1) "life" else "lives"} left", viewW / 2f, y + 82f, 13f, WHITE, 1)
        if (screenT > 1f) g.shadowText("TAP TO TRY AGAIN", viewW / 2f, y + 114f, 12f, blink(), 1)
    }

    private fun drawGameOver(g: Gfx) {
        val y = panel(g, 150f)
        g.shadowText("GAME OVER", viewW / 2f, y + 38f, 28f, RED, 1)
        g.shadowText("You reached level $levelNum", viewW / 2f, y + 62f, 13f, WHITE, 1)
        g.shadowText("Score  $score", viewW / 2f, y + 84f, 16f, GOLD, 1)
        if (newHighRank >= 0) {
            g.shadowText(if (newHighRank == 0) "NEW HIGH SCORE!" else "TOP 5 SCORE! #${newHighRank + 1}", viewW / 2f, y + 106f, 13f, GREEN, 1)
        }
        if (screenT > 1.5f) g.shadowText("TAP TO CONTINUE", viewW / 2f, y + 134f, 12f, blink(), 1)
    }

    private fun drawPause(g: Gfx) {
        g.rect(0f, 0f, viewW, VIEW_H, 0x90000000.toInt())
        g.shadowText("PAUSED", viewW / 2f, 130f, 30f, WHITE, 1)
        button(g, resumeButton(), "RESUME", GREEN)
        button(g, quitButton(), "QUIT TO MENU", WHITE)
    }

    private fun drawTitle(g: Gfx) {
        g.rect(0f, 0f, viewW, VIEW_H, 0x50000000)
        val tx = viewW * 0.30f
        g.text("JAVELIN", tx + 3f, 63f, 44f, 0xFF000000.toInt(), 1)
        g.text("JAVELIN", tx, 60f, 44f, GOLD, 1)
        g.text("STRIKE", tx + 3f, 105f, 40f, 0xFF000000.toInt(), 1)
        g.text("STRIKE", tx, 102f, 40f, RED, 1)
        button(g, titleButton(0), "PLAY", GREEN)
        val ctl = if (motionAvailable) controls.label else "TOUCH (NO GYRO)"
        button(g, titleButton(1), "CONTROLS: $ctl", WHITE)
        button(g, titleButton(2), "ARM: ${arm.label}", WHITE)
        button(g, titleButton(3), "HOW TO PLAY", WHITE)

        val hx = viewW * 0.74f
        val pw = 190f
        g.rect(hx - pw / 2f, 120f, pw, 160f, 0xB0101828.toInt())
        g.shadowText("HIGH SCORES", hx, 140f, 14f, GOLD, 1)
        for (i in 0 until 5) {
            val yy = 164f + i * 22f
            val s = highScores[i]
            g.shadowText("${i + 1}.", hx - 78f, yy, 12f, WHITE)
            g.shadowText(if (s > 0) "$s" else "---", hx + 30f, yy, 12f, WHITE, 2)
            if (s > 0) g.shadowText("L${highLevels[i]}", hx + 78f, yy, 11f, CYAN, 2)
        }
        val best = store.load(KEY_BEST_LEVEL)
        if (best > 0) g.shadowText("Best level cleared: $best", viewW / 2f, VIEW_H - 14f, 10f, WHITE, 1)
    }

    private fun drawHowTo(g: Gfx) {
        g.rect(0f, 0f, viewW, VIEW_H, 0xE0101828.toInt())
        val x = 30f
        var y = 36f
        g.shadowText("HOW TO PLAY", viewW / 2f, y, 22f, GOLD, 1)
        y += 26f
        val lines = if (controls == ControlMode.MOTION) listOf(
            "1. Hold the phone in your throwing hand, screen facing you.",
            "2. Tilt it like a steering wheel to aim left or right.",
            "3. Press and HOLD the screen with your thumb to grip the javelin.",
            "4. Pull your arm back. The DRAW bar turns green when you're ready.",
            "5. Throw your arm forward! The faster the swing, the further it flies.",
            "   The javelin lets go at the end of your swing.",
        ) else listOf(
            "1. Press anywhere on the screen to grip the javelin.",
            "2. Pull your finger down to draw back.",
            "3. Flick up to throw. A faster flick throws further.",
            "4. Flick a little left or right to aim.",
        )
        for (l in lines) {
            g.shadowText(l, x, y, 11f, WHITE)
            y += 18f
        }
        y += 6f
        for (l in listOf(
            "Hit the target before time runs out. You have unlimited javelins.",
            "Watch the wind arrow, and the TARGET CAM to see where you landed.",
            "Too strong or too weak? Change ARM on the title screen.",
        )) {
            g.shadowText(l, x, y, 11f, CYAN)
            y += 18f
        }
        y += 6f
        g.shadowText("SAFETY: grip the phone tightly (a wrist strap or phone grip helps)", x, y, 11f, ORANGE)
        g.shadowText("and make sure nobody and nothing breakable is around you.", x, y + 16f, 11f, ORANGE)
        if (screenT > 0.4f) g.shadowText("TAP TO ${if (playAfterHowTo) "PLAY" else "GO BACK"}", viewW / 2f, VIEW_H - 14f, 12f, blink(), 1)
    }

    private fun button(g: Gfx, r: FloatArray, label: String, color: Int) {
        g.rect(r[0], r[1], r[2], r[3], argb(0xFF, color))
        g.rect(r[0] + 2f, r[1] + 2f, r[2] - 4f, r[3] - 4f, 0xE0182838.toInt())
        g.shadowText(label, r[0] + r[2] / 2f, r[1] + r[3] / 2f + 5f, 14f, color, 1)
    }

    private fun blink() = if ((clock * 2f).toInt() % 2 == 0) WHITE else 0xFFFFE066.toInt()

    /** Distance from the thrower to the target, for tests and the HUD. */
    internal fun targetDistance() = sqrt(target.x * target.x + target.z * target.z)

    companion object {
        const val START_LIVES = 3
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
        const val GOLD = 0xFFFFC928.toInt()
        const val RED = 0xFFFF4B3E.toInt()
        const val GREEN = 0xFF5BE36B.toInt()
        const val ORANGE = 0xFFFF9A3C.toInt()
        const val CYAN = 0xFF7FE3FF.toInt()
        val CONFETTI = intArrayOf(0xFFFF4B3E.toInt(), 0xFFFFC928.toInt(), 0xFF5BE36B.toInt(), 0xFF4FA3FF.toInt(), 0xFFFF7AD9.toInt(), 0xFFFFFFFF.toInt())
    }
}
