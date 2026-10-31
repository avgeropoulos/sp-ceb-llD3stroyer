package com.javelinstrike.game

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * The other events of the ancient Olympiad: discus, the stadion sprint and the long jump.
 * Each runs inside the same stadium as the javelin and shares its menus and controls.
 */

/** The events on the menu, in order. */
enum class OlympicEventKind(val title: String, val bestKey: String) {
    JAVELIN("JAVELIN", ""),
    DISCUS("DISCUS", "best_discus"),
    SPRINT("STADION RACE", "best_sprint"),
    LONG_JUMP("LONG JUMP", "best_longjump"),
}

/** What every event shares: an intro card, results, a best score and a quit button. */
internal abstract class OlympicEvent(val game: Game, val kind: OlympicEventKind) {
    enum class Stage { INTRO, ACTIVE, RESULTS }

    var stage = Stage.INTRO
        protected set
    /** Set when the player leaves; the game goes back to the events menu. */
    var done = false
        private set
    protected var stageT = 0f
    protected val cam = Camera()
    protected var msg = ""
    protected var msgColor = Game.WHITE
    protected var msgT = 0f
    protected val motionMode get() = game.controls == ControlMode.MOTION

    abstract val instructions: List<String>
    /** The result of this go, as stored (higher is better unless [lowerIsBetter]); 0 for none. */
    abstract val result: Int
    open val lowerIsBetter = false
    abstract fun format(value: Int): String

    /** Runs the event itself while [stage] is ACTIVE. */
    protected abstract fun play(dt: Float, touches: List<Input.Touch>)
    protected abstract fun drawScene(g: Gfx)
    protected abstract fun drawPlayHud(g: Gfx)
    protected open fun start() {}
    protected open fun resultLines(): List<String> = emptyList()

    val best get() = game.store.load(kind.bestKey)

    fun update(dt: Float, touches: List<Input.Touch>) {
        stageT += dt
        if (msgT > 0f) msgT -= dt
        for (t in touches) {
            if ((t.type == Input.DOWN || t.type == Input.TAP) && inside(quitButton(), t.x, t.y)) {
                game.sound.play(Sound.CLICK)
                done = true
                return
            }
        }
        when (stage) {
            Stage.INTRO -> if (stageT > 0.5f && touches.any { it.type == Input.DOWN || it.type == Input.TAP }) {
                game.sound.play(Sound.CLICK)
                stage = Stage.ACTIVE
                stageT = 0f
                game.motion.takeSteps()
                start()
            }
            Stage.ACTIVE -> play(dt, touches)
            Stage.RESULTS -> if (stageT > 1.2f && touches.any { it.type == Input.DOWN || it.type == Input.TAP }) {
                game.sound.play(Sound.CLICK)
                done = true
            }
        }
    }

    /** Ends the event and saves a new best. */
    protected fun finish() {
        val r = result
        val old = best
        newBest = r > 0 && (old == 0 || if (lowerIsBetter) r < old else r > old)
        if (newBest) game.store.save(kind.bestKey, r)
        game.sound.play(if (r > 0) Sound.CLEAR else Sound.FAIL)
        stage = Stage.RESULTS
        stageT = 0f
    }

    protected var newBest = false

    protected fun say(text: String, color: Int, time: Float = 1.6f) {
        msg = text
        msgColor = color
        msgT = time
    }

    fun render(g: Gfx) {
        drawScene(g)
        val w = game.viewW
        g.rect(0f, 0f, w, 50f, 0x50000000)
        g.shadowText(kind.title, 10f, 20f, 12f, Game.GOLD, serif = true)
        if (best > 0) g.shadowText("BEST ${format(best)}", 10f, 38f, 10f, Game.WHITE)
        val q = quitButton()
        g.rect(q[0], q[1], q[2], q[3], 0xA0000000.toInt())
        g.shadowText("X", q[0] + q[2] / 2f, q[1] + 22f, 18f, Game.WHITE, 1)
        when (stage) {
            Stage.INTRO -> drawIntroCard(g)
            Stage.ACTIVE -> drawPlayHud(g)
            Stage.RESULTS -> drawResultsCard(g)
        }
        if (msgT > 0f && stage == Stage.ACTIVE) {
            val a = min(1f, msgT * 3f)
            val y = game.viewH * 0.3f + 20f
            g.text(msg, w / 2f + 2f, y + 2f, 26f, argb((a * 200).toInt(), 0), 1, serif = true)
            g.text(msg, w / 2f, y, 26f, argb((a * 255).toInt(), msgColor), 1, serif = true)
        }
    }

    private fun drawIntroCard(g: Gfx) {
        val lines = instructions.flatMap { wrap(it, 40) }
        val y = game.panel(g, 110f + lines.size * 16f)
        val cx = game.viewW / 2f
        g.shadowText(kind.title, cx, y + 44f, 26f, Game.GOLD, 1, serif = true)
        var yy = y + 72f
        for (l in lines) {
            g.shadowText(l, cx, yy, 11f, Game.WHITE, 1)
            yy += 16f
        }
        if (stageT > 0.5f) g.shadowText("TAP TO START", cx, yy + 14f, 12f, game.blink(), 1)
    }

    private fun drawResultsCard(g: Gfx) {
        val extra = resultLines()
        val y = game.panel(g, 150f + extra.size * 18f)
        val cx = game.viewW / 2f
        val r = result
        g.shadowText(if (r > 0) format(r) else "NO MARK", cx, y + 50f, 30f, if (r > 0) Game.GOLD else Game.RED, 1, serif = true)
        var yy = y + 78f
        for (l in extra) {
            g.shadowText(l, cx, yy, 12f, Game.WHITE, 1)
            yy += 18f
        }
        if (newBest) g.shadowText("NEW PERSONAL BEST!", cx, yy + 4f, 13f, Game.GREEN, 1)
        else if (best > 0) g.shadowText("Best ${format(best)}", cx, yy + 4f, 12f, Game.CYAN, 1)
        if (stageT > 1.2f) g.shadowText("TAP TO CONTINUE", cx, yy + 36f, 12f, game.blink(), 1)
    }

    protected fun quitButton() = floatArrayOf(game.viewW - 44f, 8f, 36f, 32f)

    protected fun inside(r: FloatArray, x: Float, y: Float) = x >= r[0] && x <= r[0] + r[2] && y >= r[1] && y <= r[1] + r[3]

    /** Eases the camera towards a spot. [cy] keeps the horizon about a third of the way down. */
    protected fun moveCam(dt: Float, x: Float, y: Float, z: Float, pitch: Float, speed: Float = 4f) {
        val k = 1f - exp(-dt * speed)
        cam.x += (x - cam.x) * k
        cam.y += (y - cam.y) * k
        cam.z += (z - cam.z) * k
        cam.pitch += (pitch - cam.pitch) * k
        cam.focal = Game.FOCAL
        cam.cx = game.viewW / 2f
        cam.cy = game.viewH * 0.33f + Game.FOCAL * tan(cam.pitch)
    }

    protected fun placeCam(x: Float, y: Float, z: Float, pitch: Float) {
        cam.x = x; cam.y = y; cam.z = z; cam.pitch = pitch
        moveCam(0f, x, y, z, pitch)
    }

    protected fun prompt(g: Gfx, line: String, sub: String, color: Int = Game.WHITE) {
        if (msgT > 0f) return
        val h = game.viewH
        g.shadowText(line, game.viewW / 2f, h - 40f, 18f, color, 1)
        if (sub.isNotEmpty()) g.shadowText(sub, game.viewW / 2f, h - 22f, 11f, 0xFFEEE4CC.toInt(), 1)
    }

    protected fun metres(cm: Int) = "${cm / 100}.${(cm % 100).toString().padStart(2, '0')} m"
}

// ==================================================================== discus

/**
 * Discus: hold the screen and spin round with the phone, then let go when you're facing
 * the field again. The faster you're turning when you let go, the further it flies; let go
 * facing the wrong way and it lands outside the sector, a foul.
 */
internal class DiscusEvent(game: Game) : OlympicEvent(game, OlympicEventKind.DISCUS) {
    private enum class State { READY, SPIN, FLIGHT, LANDED }

    private var state = State.READY
    private var attempt = 0
    private val marks = IntArray(ATTEMPTS)
    private var spinStart = 0f
    private var yaw = 0f
    private var holdT = 0f
    private var x = 0f
    private var y = 0f
    private var z = 0f
    private var vx = 0f
    private var vy = 0f
    private var vz = 0f
    private val landed = ArrayList<Pair<Float, Float>>()

    // Touch mode: circling a finger round the middle of the screen.
    private var touchAngle = 0f
    private var touchRate = 0f
    private var lastTouchA = 0f
    private var lastTouchT = 0f

    override val instructions get() = if (motionMode) listOf(
        "Hold the screen to grip the discus.",
        "Spin round on the spot, at least one full turn. Keep a tight grip!",
        "Let go when you're facing the field again. Faster spin, longer throw.",
        "Best of 3 throws.",
    ) else listOf(
        "Press and circle your finger round and round the screen.",
        "Lift it when the arrow points up the field. Faster circles, longer throw.",
        "Best of 3 throws.",
    )

    override val result get() = marks.max()
    override fun format(value: Int) = metres(value)

    override fun start() {
        placeCam(-0.6f, 3.4f, -4.5f, Game.REST_PITCH)
    }

    init { placeCam(-0.6f, 3.4f, -4.5f, Game.REST_PITCH) }

    private fun angleNow() = if (motionMode) game.motion.spinAngle else touchAngle
    private fun rateNow() = if (motionMode) game.motion.spinRate else touchRate / TOUCH_FACTOR

    override fun play(dt: Float, touches: List<Input.Touch>) {
        for (t in touches) when (t.type) {
            Input.DOWN -> if (state == State.READY) {
                state = State.SPIN
                lastTouchA = touchAngleAt(t.x, t.y)
                lastTouchT = t.time
                touchRate = 0f
                spinStart = angleNow()
                game.sound.play(Sound.GRIP)
            }
            Input.MOVE -> if (state == State.SPIN && !motionMode) {
                val a = touchAngleAt(t.x, t.y)
                var d = a - lastTouchA
                if (d > PI) d -= 2f * PI.toFloat()
                if (d < -PI) d += 2f * PI.toFloat()
                touchAngle += abs(d)
                val tdt = (t.time - lastTouchT).coerceAtLeast(0.001f)
                touchRate += (abs(d) / tdt - touchRate) * min(1f, tdt * 10f)
                lastTouchA = a
                lastTouchT = t.time
            }
            Input.UP -> if (state == State.SPIN) release()
        }
        when (state) {
            State.READY -> yaw += (0f - yaw) * min(1f, dt * 4f)
            State.SPIN -> yaw = angleNow() - spinStart
            State.FLIGHT -> {
                val sub = dt * 1.4f / 4f
                repeat(4) {
                    vy -= GRAVITY * sub
                    x += vx * sub; y += vy * sub; z += vz * sub
                    if (y <= 0f) { y = 0f; land(); return@repeat }
                }
            }
            State.LANDED -> {
                holdT -= dt
                if (holdT <= 0f) {
                    attempt++
                    if (attempt >= ATTEMPTS) finish() else { state = State.READY; yaw = 0f }
                }
            }
        }
        when (state) {
            State.FLIGHT -> moveCam(dt, x * 0.8f, 3f + min(4f, z * 0.08f), max(-4.5f, z - 14f), Game.REST_PITCH + 0.1f, 3f)
            State.LANDED -> {}
            else -> moveCam(dt, -0.6f, 3.4f, -4.5f, Game.REST_PITCH)
        }
    }

    private fun touchAngleAt(tx: Float, ty: Float) = atan2(ty - game.viewH * 0.55f, tx - game.viewW / 2f)

    private fun release() {
        if (yaw < 2f * PI.toFloat() * 0.75f) {
            say("SPIN A FULL TURN!", Game.ORANGE)
            state = State.READY
            return
        }
        // How far off facing the field: 0 is straight up the middle.
        var err = yaw % (2f * PI.toFloat())
        if (err > PI) err -= 2f * PI.toFloat()
        val power = (rateNow() / (SPIN_FULL / game.arm.sensitivity)).coerceIn(0f, 1.1f)
        val dist = MIN_DIST + (MAX_DIST - MIN_DIST) * power.pow(1.3f)
        val v = sqrt(dist * GRAVITY / sin(2f * ANGLE))
        x = sin(err) * 0.9f; y = 1.5f; z = cos(err) * 0.9f
        vx = v * cos(ANGLE) * sin(err)
        vy = v * sin(ANGLE)
        vz = v * cos(ANGLE) * cos(err)
        state = State.FLIGHT
        game.sound.play(Sound.WHOOSH)
        game.sound.vibrate(35)
    }

    private fun land() {
        val err = atan2(x, z)
        val foul = abs(err) > SECTOR || z < 0f
        val d = hypot(x, z)
        marks[attempt] = if (foul) 0 else (d * 100f).roundToInt()
        landed += x to z
        game.dirt(x, z)
        game.sound.play(Sound.THUD)
        if (foul) {
            say("FOUL!", Game.RED)
            game.sound.play(Sound.FAIL)
        } else {
            say(metres(marks[attempt]), Game.GOLD)
            if (marks[attempt] >= marks.max()) game.cheerT = 2f
            game.sound.play(Sound.CHEER)
        }
        state = State.LANDED
        holdT = 2f
    }

    override fun resultLines() = marks.mapIndexed { i, m -> "Throw ${i + 1}:  ${if (m > 0) metres(m) else "foul"}" }

    override fun drawScene(g: Gfx) {
        game.drawWorld(g, cam, main = true, 0f, 0f, game.viewW, game.viewH, javelinScene = false)
        // The sector and the throwing circle
        groundLine(g, cam, 0f, 0f, sin(SECTOR) * 90f, cos(SECTOR) * 90f, 0.1f, 0xC0FFFFFF.toInt())
        groundLine(g, cam, 0f, 0f, -sin(SECTOR) * 90f, cos(SECTOR) * 90f, 0.1f, 0xC0FFFFFF.toInt())
        groundCircle(g, cam, 0f, 0f, 1.35f, 0xFFF0EADB.toInt())
        groundCircle(g, cam, 0f, 0f, 1.2f, 0xFFD9C9A3.toInt())
        for ((lx, lz) in landed) {
            if (cam.project(lx, 0.02f, lz)) {
                val r = max(2f, 0.3f * cam.ppm)
                g.oval(cam.sx - r, cam.sy - r * 0.4f, r * 2f, r * 0.8f, 0xFF8A5A22.toInt())
            }
        }
        if (state == State.FLIGHT || state == State.LANDED) {
            if (cam.project(x, 0.02f, z)) {
                val r = max(1.5f, 0.15f * cam.ppm)
                g.oval(cam.sx - r, cam.sy - r * 0.4f, r * 2f, r * 0.8f, 0x50000000)
            }
            if (state == State.FLIGHT && cam.project(x, y, z)) {
                val r = max(2.5f, 0.16f * cam.ppm)
                g.oval(cam.sx - r, cam.sy - r * 0.55f, r * 2f, r * 1.1f, 0xFF2A1A0E.toInt())
                g.oval(cam.sx - r * 0.85f, cam.sy - r * 0.45f, r * 1.7f, r * 0.9f, 0xFFCC8F35.toInt())
            }
        }
        val holding = state == State.READY || state == State.SPIN
        drawFigure(g, cam, 0f, 0f, 0f, yaw, 0f, 0f, discus = holding)
    }

    override fun drawPlayHud(g: Gfx) {
        val w = game.viewW
        g.shadowText("THROW ${min(attempt + 1, ATTEMPTS)} OF $ATTEMPTS", w / 2f, 30f, 14f, Game.WHITE, 1)
        for (i in 0 until ATTEMPTS) {
            val m = marks[i]
            val text = when {
                i < attempt || (i == attempt && state == State.LANDED) -> if (m > 0) metres(m) else "foul"
                else -> "-"
            }
            g.shadowText(text, 10f, 66f + i * 15f, 10f, if (m > 0) Game.GOLD else Game.WHITE)
        }
        if (state == State.READY || state == State.SPIN) {
            // A dial shows which way the athlete faces; let go in the green wedge.
            val cx = w / 2f
            val cy = game.viewH - 120f
            val r = 34f
            g.oval(cx - r - 3f, cy - r - 3f, (r + 3f) * 2f, (r + 3f) * 2f, 0xA0000000.toInt())
            val n = 10
            val wx = FloatArray(n + 2)
            val wy = FloatArray(n + 2)
            wx[0] = cx; wy[0] = cy
            for (i in 0..n) {
                val a = -PI.toFloat() / 2f - SECTOR + 2f * SECTOR * i / n
                wx[i + 1] = cx + cos(a) * r
                wy[i + 1] = cy + sin(a) * r
            }
            g.poly(wx, wy, 0xA05BE36B.toInt())
            val a = -PI.toFloat() / 2f + yaw
            g.line(cx, cy, cx + cos(a) * r, cy + sin(a) * r, 4f, Game.GOLD)
            val spd = (rateNow() / (SPIN_FULL / game.arm.sensitivity)).coerceIn(0f, 1f)
            g.rect(cx - 50f, cy + r + 10f, 100f, 8f, 0xA0000000.toInt())
            g.rect(cx - 50f, cy + r + 10f, 100f * spd, 8f, if (spd > 0.7f) Game.GREEN else Game.GOLD)
        }
        when (state) {
            State.READY -> prompt(g, if (motionMode) "HOLD THE SCREEN" else "PRESS AND CIRCLE", if (motionMode) "Then spin round with the phone" else "Circle your finger fast")
            State.SPIN -> {
                val turned = yaw >= 2f * PI.toFloat() * 0.75f
                prompt(g, if (turned) "LET GO FACING THE FIELD!" else "KEEP SPINNING", "Release in the green", if (turned) Game.GREEN else Game.WHITE)
            }
            else -> {}
        }
    }

    companion object {
        const val ATTEMPTS = 3
        /** Spin rate (rad/s) for the longest throw at normal ARM strength. */
        const val SPIN_FULL = 11f
        /** Circling a finger is quicker than spinning round, so it counts for less. */
        const val TOUCH_FACTOR = 2.2f
        const val MIN_DIST = 10f
        const val MAX_DIST = 65f
        const val ANGLE = 0.62f
        /** Half the width of the landing sector, in radians (about 35°). */
        const val SECTOR = 0.6f
    }
}

// ==================================================================== stadion sprint

/** A runner in the stadion race: the player or a rival. */
internal class Runner(val name: String, val x: Float, val look: Look, val topSpeed: Float) {
    var z = 0f
    var speed = 0f
    var phase = 0f
    var finishTime = 0f
    val finished get() = finishTime > 0f
}

/**
 * The stadion: one length of the stadium, 192 m, the oldest event of the Games. Pump your
 * arms like a sprinter (or tap left and right in turn) to run. Don't move before "APITE!"
 * (the ancient "go!") or it's a false start.
 */
internal class SprintEvent(game: Game) : OlympicEvent(game, OlympicEventKind.SPRINT) {
    private enum class State { MARKS, RACE, DONE }

    private var state = State.MARKS
    private var raceT = 0f
    private var countdown = 0f
    private val stride = Stride(TOP_SPEED)
    private var lastSide = 0
    val player = Runner("YOU", 0f, PLAYER_LOOK, TOP_SPEED)
    val runners = listOf(
        player,
        Runner("SPARTAN", -2.4f, Look(cloth = 0xFFB5482E.toInt(), band = 0xFF2A1A0E.toInt(), wreath = false), 9.05f),
        Runner("ATHENIAN", 2.4f, Look(skin = 0xFFDDA578.toInt(), cloth = 0xFF3F5E9A.toInt(), wreath = false), 8.6f),
        Runner("CORINTHIAN", 4.8f, Look(skin = 0xFF9A6239.toInt(), cloth = 0xFF7A8F3A.toInt(), wreath = false), 8.1f),
    )

    override val instructions get() = listOf(
        if (motionMode) "Pump your arms hard like a sprinter, phone in hand. Every swing is a step."
        else "Tap the left and right halves of the screen in turn, as fast as you can.",
        "Race 192 m against three rivals. Faster steps, faster running.",
        "Wait for APITE! (GO!) or it's a false start.",
    )

    override val result get() = if (player.finished) (player.finishTime * 1000f).roundToInt() else 0
    override val lowerIsBetter = true
    override fun format(value: Int) = "${value / 1000}.${((value % 1000) / 10).toString().padStart(2, '0')} s"

    init { placeCam(0.4f, 2.6f, -6f, 0.2f) }

    override fun start() {
        newRace()
    }

    private fun newRace() {
        state = State.MARKS
        countdown = 3.2f
        raceT = 0f
        stride.reset()
        lastSide = 0
        for (r in runners) { r.z = 0f; r.speed = 0f; r.finishTime = 0f; r.phase = 0f }
        game.motion.takeSteps()
    }

    /** Steps since last frame, from arm pumping or alternating taps. */
    private fun steps(touches: List<Input.Touch>): Int {
        var n = if (motionMode) game.motion.takeSteps() else 0
        if (!motionMode) for (t in touches) {
            if (t.type != Input.DOWN && t.type != Input.TAP) continue
            val side = if (t.x < game.viewW / 2f) 1 else 2
            if (side != lastSide) { n++; lastSide = side }
        }
        return n
    }

    override fun play(dt: Float, touches: List<Input.Touch>) {
        val n = steps(touches)
        when (state) {
            State.MARKS -> {
                val before = countdown
                countdown -= dt
                if (n > 0 && countdown < 1.6f) {
                    say("FALSE START!", Game.RED, 1.8f)
                    game.sound.play(Sound.FAIL)
                    newRace()
                    return
                }
                if (before > 1.6f && countdown <= 1.6f) game.sound.play(Sound.TICK)
                if (countdown <= 0f) {
                    state = State.RACE
                    say("APITE!", Game.GREEN, 1f)
                    game.sound.play(Sound.WHOOSH)
                }
            }
            State.RACE, State.DONE -> {
                raceT += dt
                repeat(n) { stride.step(raceT) }
                stride.update(raceT, dt)
                player.speed = stride.speed
                for (r in runners) {
                    if (r !== player) r.speed = r.topSpeed * (1f - exp(-raceT / 0.9f)) * if (r.z > 140f) 0.985f else 1f
                    r.z += r.speed * dt
                    r.phase += r.speed * dt * 1.5f
                    if (!r.finished && r.z >= DISTANCE) {
                        r.finishTime = raceT - (r.z - DISTANCE) / max(0.1f, r.speed)
                        if (r === player) {
                            game.sound.play(if (place() == 1) Sound.BULLSEYE else Sound.HIT)
                            if (place() == 1) { game.cheerT = 3f; game.confetti(r.x, DISTANCE + 4f) }
                            state = State.DONE
                            stageT = 0f
                        }
                    }
                }
                if (state == State.DONE && stageT > 2.5f) finish()
            }
        }
        moveCam(dt, player.x + 0.4f, 2.6f, player.z - 6f, 0.2f, 6f)
    }

    /** The player's place, 1 to 4, counting runners already over the line. */
    fun place(): Int {
        val mine = if (player.finished) player.finishTime else Float.MAX_VALUE
        return 1 + runners.count { it !== player && (if (it.finished) it.finishTime < mine else it.z > player.z) }
    }

    override fun resultLines() = listOf(
        "${ordinal(place())} place",
        if (place() == 1) "The olive wreath is yours!" else "Only the winner is crowned at Olympia",
    )

    private fun ordinal(n: Int) = "$n" + when (n) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }

    override fun drawScene(g: Gfx) {
        game.drawWorld(g, cam, main = true, 0f, 0f, game.viewW, game.viewH, javelinScene = false)
        // Stone start and finish lines with a turning post for each runner
        for (lineZ in floatArrayOf(0f, DISTANCE)) {
            if (lineZ + 1f > cam.nearGroundZ()) groundQuad(g, cam, -4f, 7f, max(lineZ, cam.nearGroundZ()), lineZ + 0.6f, 0xFFF0EADB.toInt())
        }
        for (r in runners) seg(g, cam, r.x, 0f, DISTANCE + 1.5f, r.x, 1.6f, DISTANCE + 1.5f, 0.25f, 0xFFF0EADB.toInt(), 1.5f)
        for (r in runners.sortedByDescending { it.z }) {
            val running = r.speed > 0.3f
            drawFigure(
                g, cam, r.x, if (running) abs(sin(r.phase)) * 0.08f else 0f, r.z, 0f,
                if (running) sin(r.phase) else 0f, if (running) sin(r.phase) else 0f, r.look,
                crouch = if (state == State.MARKS) 0.6f else 0f, lean = 0.1f + min(0.15f, r.speed * 0.015f),
            )
            if (r !== player && cam.project(r.x, 2.4f, r.z)) {
                g.text(r.name, cam.sx, cam.sy, (cam.ppm * 0.9f).coerceIn(6f, 10f), 0xD0FFFFFF.toInt(), 1)
            }
        }
    }

    override fun drawPlayHud(g: Gfx) {
        val w = game.viewW
        if (state == State.MARKS) {
            val word = when {
                countdown > 1.6f -> "ON YOUR MARKS"
                else -> "SET..."
            }
            g.shadowText(word, w / 2f, game.viewH * 0.3f, 26f, Game.GOLD, 1, serif = true)
            prompt(g, "WAIT FOR APITE!", if (motionMode) "Then pump your arms!" else "Then tap left, right, left...")
            return
        }
        g.shadowText("${oneDecimal(raceT)} s", w / 2f, 30f, 22f, Game.WHITE, 1)
        val left = max(0f, DISTANCE - player.z)
        g.shadowText("${left.roundToInt()} m TO GO", w / 2f, 46f, 10f, Game.GOLD, 1)
        g.shadowText(ordinal(place()), 10f, 70f, 18f, if (place() == 1) Game.GREEN else Game.WHITE, serif = true)
        // Speed bar
        val bh = 150f
        val by = game.viewH - 70f - bh
        g.rect(10f, by - 2f, 18f, bh + 4f, 0xA0000000.toInt())
        val f = (player.speed / TOP_SPEED).coerceIn(0f, 1f)
        g.rect(12f, by + bh * (1f - f), 14f, bh * f, if (f > 0.85f) Game.GREEN else Game.GOLD)
        g.shadowText("SPEED", 19f, by + bh + 14f, 8f, Game.WHITE, 1)
        if (state == State.RACE && player.speed < 3f) {
            prompt(g, if (motionMode) "PUMP YOUR ARMS!" else "TAP LEFT, RIGHT, LEFT...", "Faster steps, faster running")
        }
    }

    companion object {
        /** One stadion, the length of the stadium at Olympia. */
        const val DISTANCE = 192f
        const val TOP_SPEED = 10f
    }
}

// ==================================================================== long jump

/**
 * The long jump, done with stone weights (halteres) as at Olympia. Pump your arms (or tap
 * left and right) to sprint down the runway, then tap the screen to jump just before the
 * board. Faster run-up, longer jump; past the board is a foul.
 */
internal class LongJumpEvent(game: Game) : OlympicEvent(game, OlympicEventKind.LONG_JUMP) {
    private enum class State { READY, RUN, AIR, LANDED }

    private var state = State.READY
    private var attempt = 0
    private val marks = IntArray(ATTEMPTS)
    private val stride = Stride(10f)
    private var lastSide = 0
    private var runT = 0f
    var z = RUNUP_START
        private set
    private var y = 0f
    private var speed = 0f
    private var phase = 0f
    private var takeoffZ = 0f
    private var airT = 0f
    private var airTotal = 0f
    private var jumpLen = 0f
    private var holdT = 0f
    private val prints = ArrayList<Float>()

    override val instructions get() = listOf(
        if (motionMode) "Pump your arms like a sprinter to run down the runway."
        else "Tap the left and right halves of the screen in turn to run.",
        if (motionMode) "Tap the screen to jump, just before the white board."
        else "Hit the JUMP button just before the white board.",
        "Faster run-up, longer jump. Past the board is a foul. Best of 3 jumps.",
    )

    override val result get() = marks.max()
    override fun format(value: Int) = metres(value)

    init { placeCam(0.5f, 2.4f, RUNUP_START - 5f, 0.18f) }

    override fun start() = reset()

    private fun reset() {
        state = State.READY
        z = RUNUP_START
        y = 0f
        speed = 0f
        stride.reset()
        lastSide = 0
        runT = 0f
        game.motion.takeSteps()
    }

    private fun jumpButton() = floatArrayOf(game.viewW / 2f - 70f, game.viewH - 140f, 140f, 56f)

    override fun play(dt: Float, touches: List<Input.Touch>) {
        var steps = if (motionMode) game.motion.takeSteps() else 0
        var jump = false
        for (t in touches) {
            if (t.type != Input.DOWN && t.type != Input.TAP) continue
            if (motionMode) { jump = true; continue }
            if (inside(jumpButton(), t.x, t.y)) { jump = true; continue }
            val side = if (t.x < game.viewW / 2f) 1 else 2
            if (side != lastSide) { steps++; lastSide = side }
        }
        when (state) {
            State.READY -> if (steps > 0) {
                state = State.RUN
                repeat(steps) { stride.step(runT) }
            }
            State.RUN -> {
                runT += dt
                repeat(steps) { stride.step(runT) }
                stride.update(runT, dt)
                speed = stride.speed
                z += speed * dt
                phase += speed * dt * 1.5f
                if (jump) takeOff()
                else if (z > BOARD_END) foul("OVER THE BOARD!")
                else if (runT > 12f && speed < 0.5f) foul("TOO SLOW!")
            }
            State.AIR -> {
                airT += dt
                val f = (airT / airTotal).coerceIn(0f, 1f)
                z = takeoffZ + jumpLen * f
                y = 4f * f * (1f - f) * (0.5f + speed * 0.06f)
                if (f >= 1f) landJump()
            }
            State.LANDED -> {
                holdT -= dt
                if (holdT <= 0f) {
                    attempt++
                    if (attempt >= ATTEMPTS) finish() else reset()
                }
            }
        }
        val camZ = if (state == State.AIR || state == State.LANDED) z - 6f else z - 5f
        moveCam(dt, 0.5f, 2.4f + if (state == State.AIR) 0.5f else 0f, camZ, 0.18f, 6f)
    }

    private fun takeOff() {
        takeoffZ = min(z, BOARD_END)
        // Measured from the front of the board, so taking off early loses distance.
        jumpLen = 0.4f + speed * 0.78f
        airTotal = 0.35f + speed * 0.06f
        airT = 0f
        state = State.AIR
        game.sound.play(Sound.WHOOSH)
        game.sound.vibrate(30)
    }

    private fun landJump() {
        y = 0f
        val mark = z - BOARD_END
        marks[attempt] = max(0, (mark * 100f).roundToInt())
        prints += z
        game.dirt(0f, z)
        game.sound.play(Sound.THUD)
        if (marks[attempt] > 0) {
            say(metres(marks[attempt]), Game.GOLD)
            game.sound.play(Sound.CHEER)
            if (marks[attempt] >= marks.max()) game.cheerT = 2f
        } else {
            say("NO MARK", Game.RED)
        }
        state = State.LANDED
        holdT = 2f
    }

    private fun foul(why: String) {
        marks[attempt] = 0
        say(why, Game.RED)
        game.sound.play(Sound.FAIL)
        state = State.LANDED
        holdT = 2f
        speed = 0f
    }

    override fun resultLines() = marks.mapIndexed { i, m -> "Jump ${i + 1}:  ${if (m > 0) metres(m) else "foul"}" }

    override fun drawScene(g: Gfx) {
        game.drawWorld(g, cam, main = true, 0f, 0f, game.viewW, game.viewH, javelinScene = false)
        val near = cam.nearGroundZ()
        // Runway, board and the dug sand pit with a mark every metre
        if (BOARD_END > near) groundQuad(g, cam, -1f, 1f, max(RUNUP_START - 2f, near), BOARD_END - 0.2f, 0xFFC9A46A.toInt())
        if (BOARD_END + 0.01f > near) groundQuad(g, cam, -1.1f, 1.1f, max(BOARD_END - 0.2f, near), BOARD_END, 0xFFFFFFFF.toInt())
        if (PIT_END > near) groundQuad(g, cam, -1.6f, 1.6f, max(BOARD_END + 0.3f, near), PIT_END, 0xFFEBD3A0.toInt())
        for (m in 1..10) {
            val mz = BOARD_END + m
            groundLine(g, cam, -1.6f, mz, -1.2f, mz, 0.05f, 0x90704A20.toInt())
            if (m % 2 == 0 && cam.project(-1.9f, 0f, mz) && cam.ppm > 4f) {
                g.text("$m", cam.sx, cam.sy, min(12f, cam.ppm * 0.9f), 0xFF4A3420.toInt(), 1)
            }
        }
        for (p in prints) {
            if (cam.project(0f, 0.02f, p)) {
                val r = max(2f, 0.35f * cam.ppm)
                g.oval(cam.sx - r, cam.sy - r * 0.3f, r * 2f, r * 0.6f, 0x60704A20)
            }
        }
        val running = state == State.RUN
        val air = state == State.AIR
        drawFigure(
            g, cam, 0f, y, z, 0f,
            stride = if (running) sin(phase) else if (air) 0.6f else 0f,
            armSwing = if (running) sin(phase) else if (air) -1f else 0f,
            crouch = if (state == State.LANDED) 0.8f else 0f,
            lean = if (running) 0.2f else 0.1f,
        )
    }

    override fun drawPlayHud(g: Gfx) {
        val w = game.viewW
        g.shadowText("JUMP ${min(attempt + 1, ATTEMPTS)} OF $ATTEMPTS", w / 2f, 30f, 14f, Game.WHITE, 1)
        for (i in 0 until ATTEMPTS) {
            val m = marks[i]
            val shown = i < attempt || (i == attempt && state == State.LANDED)
            g.shadowText(if (shown) (if (m > 0) metres(m) else "foul") else "-", 10f, 66f + i * 15f, 10f, if (m > 0) Game.GOLD else Game.WHITE)
        }
        if (state == State.RUN || state == State.READY) {
            val bh = 150f
            val by = game.viewH - 70f - bh
            g.rect(10f, by - 2f, 18f, bh + 4f, 0xA0000000.toInt())
            val f = (speed / 10f).coerceIn(0f, 1f)
            g.rect(12f, by + bh * (1f - f), 14f, bh * f, if (f > 0.85f) Game.GREEN else Game.GOLD)
            g.shadowText("SPEED", 19f, by + bh + 14f, 8f, Game.WHITE, 1)
            val toBoard = BOARD_END - z
            g.shadowText("${toBoard.roundToInt()} m TO THE BOARD", w / 2f, 46f, 10f, if (toBoard < 3f) Game.GREEN else Game.GOLD, 1)
        }
        if (!motionMode && (state == State.RUN || state == State.READY)) {
            val b = jumpButton()
            g.rect(b[0], b[1], b[2], b[3], 0xFF1A120B.toInt())
            g.rect(b[0] + 2f, b[1] + 2f, b[2] - 4f, b[3] - 4f, 0xFFB5482E.toInt())
            g.shadowText("JUMP", b[0] + b[2] / 2f, b[1] + b[3] / 2f + 7f, 20f, Game.WHITE, 1, serif = true)
        }
        when (state) {
            State.READY -> prompt(g, if (motionMode) "PUMP YOUR ARMS TO RUN" else "TAP LEFT, RIGHT TO RUN", if (motionMode) "Tap the screen to jump" else "JUMP before the board")
            State.RUN -> if (BOARD_END - z < 4f) prompt(g, "JUMP!", "", Game.GREEN)
            else -> {}
        }
    }

    companion object {
        const val ATTEMPTS = 3
        const val RUNUP_START = -30f
        /** The front edge of the take-off board. Jumps are measured from here. */
        const val BOARD_END = 0f
        const val PIT_END = 11f
    }
}
