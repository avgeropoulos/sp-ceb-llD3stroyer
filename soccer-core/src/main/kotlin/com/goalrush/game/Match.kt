package com.goalrush.game

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.exp
import kotlin.math.sign
import kotlin.math.sqrt
import kotlin.random.Random

/** One attempt at a level: a series of shots against the goalkeeper. */
class Match(
    val levelIndex: Int,
    private val sound: SoundSink = SoundSink.Silent,
    private val rng: Random = Random.Default,
    resume: Session? = null,
) {
    enum class Phase { INTRO, AIM, FLIGHT, OUTCOME, DONE }
    enum class Result { NONE, GOAL, SAVED, BLOCKED, WIDE, OVER, POST, NO_RING, WEAK }

    val level = Levels.all[levelIndex]
    val ball = Ball()
    val keeper = Keeper()
    val wall = ArrayList<WallMan>()
    val cam = Camera()
    val start = Vec3(level.ballX, BALL_R, -level.dist)

    var phase = Phase.INTRO
    var phaseT = 0f
    var shot = resume?.shot ?: 0
    var goals = resume?.goals ?: 0
    var score = resume?.score ?: 0
    var streak = resume?.streak ?: 0

    var result = Result.NONE
    /** Lines explaining the points for the last shot. */
    val bonusLines = ArrayList<String>()
    var lastPoints = 0
    var kick: Kick? = null
    var hitPost = false
    var touchedKeeper = false
    var crossed: Vec3? = null
    var scored = false
    var ringHit = -1
    /** Shot results so far, for the HUD. */
    val history = ArrayList<Boolean>()

    /** Net ripple after a goal. */
    var netHitX = 0f
    var netHitY = 0f
    var netBulge = 0f

    /** Recent ball positions, for the trail. */
    val trail = ArrayList<Vec3>()

    // The finger path of the swipe in progress, in screen coordinates.
    val swX = FloatArray(MAX_PTS)
    val swY = FloatArray(MAX_PTS)
    val swT = FloatArray(MAX_PTS)
    var swN = 0
    var swiping = false

    val shotsLeft get() = level.shots - shot
    val passed get() = goals >= level.need
    /** No longer possible to reach the target number of goals. */
    val hopeless get() = goals + shotsLeft < level.need

    init {
        for (i in 0 until goals) history += true
        for (i in goals until shot) history += false
        setupCamera(viewH = 700f)
        resetShot()
    }

    fun setupCamera(viewH: Float) {
        val toGoal = Vec3(-start.x, 0f, -start.z).norm()
        val eye = Vec3(start.x, 0f, start.z) - toGoal * 3.3f + Vec3(0f, 1.5f, 0f)
        cam.lookAt(eye, Vec3(0f, 0.6f, 0f))
        cam.f = VIEW_W * 1.25f + (level.dist - 11f) * 10f
        cam.cx = VIEW_W / 2
        cam.cy = viewH * 0.38f
    }

    private fun wallAim(): Float = when {
        start.x < -0.5f -> -1.9f
        start.x > 0.5f -> 1.9f
        else -> -1.7f
    }

    private fun resetShot() {
        ball.reset(start)
        ball.wind = level.wind
        trail.clear()
        result = Result.NONE
        hitPost = false
        touchedKeeper = false
        crossed = null
        scored = false
        ringHit = -1
        kick = null
        swN = 0
        swiping = false
        netBulge = 0f
        wall.clear()
        if (level.wall > 0) {
            val aim = Vec3(wallAim(), 0f, 0f)
            val dir = Vec3(aim.x - start.x, 0f, aim.z - start.z).norm()
            val center = Vec3(start.x, 0f, start.z) + dir * WALL_DIST
            val right = Vec3(dir.z, 0f, -dir.x)
            val facing = atan2(dir.x, dir.z)
            for (i in 0 until level.wall) {
                val p = center + right * ((i - (level.wall - 1) / 2f) * 0.56f)
                wall += WallMan(p.x, p.z, facing)
            }
            keeper.reset(-sign(aim.x) * 0.9f)
        } else {
            keeper.reset(start.x * 0.06f)
        }
    }

    // ---- input ----

    fun touchDown(x: Float, y: Float, t: Float) {
        if (phase == Phase.INTRO) { phase = Phase.AIM; phaseT = 0f; return }
        if (phase != Phase.AIM) return
        swiping = true
        swN = 0
        addPoint(x, y, t)
    }

    fun touchMove(x: Float, y: Float, t: Float) {
        if (swiping) addPoint(x, y, t)
    }

    fun touchUp(x: Float, y: Float, t: Float) {
        if (!swiping) return
        addPoint(x, y, t)
        swiping = false
        val k = ShotSolver.fromSwipe(cam, ball.pos, swX, swY, swT, swN)
        if (k != null) shoot(k)
        swN = 0
    }

    fun cancelSwipe() { swiping = false; swN = 0 }

    private fun addPoint(x: Float, y: Float, t: Float) {
        if (swN > 0 && swN < MAX_PTS) {
            val dx = x - swX[swN - 1]; val dy = y - swY[swN - 1]
            if (dx * dx + dy * dy < 4f && t - swT[swN - 1] < 0.05f) return
        }
        if (swN == MAX_PTS) {
            // Keep the start and drop the oldest middle sample.
            for (i in 1 until MAX_PTS - 1) { swX[i] = swX[i + 1]; swY[i] = swY[i + 1]; swT[i] = swT[i + 1] }
            swN--
        }
        swX[swN] = x; swY[swN] = y; swT[swN] = t
        swN++
    }

    // ---- shooting ----

    fun shoot(k: Kick) {
        kick = k
        ball.vel = k.velocity
        ball.spin = k.spin
        phase = Phase.FLIGHT
        phaseT = 0f
        sound.play(Sound.KICK)

        // The goalkeeper reads the shot, with some error, or gambles on a side.
        val cross = ShotSolver.crossing(ball.pos, k, level.wind)
        val gamble = rng.nextFloat() < level.guess
        if (gamble) {
            val side = if (rng.nextBoolean()) 1f else -1f
            keeper.plan(side * (1.4f + rng.nextFloat() * 1.8f), 0.4f + rng.nextFloat() * 1.6f, 0.1f, level.keeperSpeed)
        } else {
            val px = (cross?.x ?: k.target.x) + gauss() * level.keeperError
            val py = ((cross?.y ?: k.target.y) + gauss() * level.keeperError * 0.6f).coerceIn(0.1f, 2.6f)
            keeper.plan(px, py, level.reaction, level.keeperSpeed)
        }
        if (level.wallJumps) wall.forEachIndexed { i, w -> w.startJump(0.08f + i * 0.02f + rng.nextFloat() * 0.05f) }
    }

    private fun gauss(): Float {
        var s = 0f
        repeat(4) { s += rng.nextFloat() }
        return (s - 2f) * 1.7f
    }

    // ---- simulation ----

    fun update(dt: Float) {
        phaseT += dt
        when (phase) {
            Phase.INTRO -> {
                keeper.update(dt)
                if (phaseT > 2.4f) { phase = Phase.AIM; phaseT = 0f }
            }
            Phase.AIM -> keeper.update(dt)
            Phase.FLIGHT -> {
                val sub = 6
                repeat(sub) { stepFlight(dt / sub) }
                if (trail.isEmpty() || (trail.last() - ball.pos).len() > 0.35f) {
                    trail += ball.pos
                    if (trail.size > 60) trail.removeAt(0)
                }
                decide()
            }
            Phase.OUTCOME -> {
                repeat(4) { stepFlight(dt / 4) }
                if (phaseT > 2.0f) nextShot()
            }
            Phase.DONE -> {}
        }
        if (netBulge > 0f) netBulge = (netBulge - dt * 1.6f).coerceAtLeast(0f)
    }

    private fun stepFlight(dt: Float) {
        keeper.update(dt)
        for (w in wall) w.update(dt)
        val prev = ball.pos
        ball.step(dt)

        // Goal frame: two posts and the crossbar.
        val frameHit = ball.collideSegment(Vec3(-GOAL_HALF_W, 0f, 0f), Vec3(-GOAL_HALF_W, GOAL_H, 0f), POST_R, 0.7f) or
            ball.collideSegment(Vec3(GOAL_HALF_W, 0f, 0f), Vec3(GOAL_HALF_W, GOAL_H, 0f), POST_R, 0.7f) or
            ball.collideSegment(Vec3(-GOAL_HALF_W, GOAL_H, 0f), Vec3(GOAL_HALF_W, GOAL_H, 0f), POST_R, 0.7f)
        if (frameHit && !hitPost) { hitPost = true; sound.play(Sound.POST) }

        if (crossed == null) {
            if (keeper.body.collide(ball, 0.35f)) {
                if (!touchedKeeper) sound.play(Sound.SAVE)
                touchedKeeper = true
            }
            for (w in wall) if (w.body.collide(ball, 0.3f, glove = 0.08f)) {
                if (result == Result.NONE) result = Result.BLOCKED
            }
        }

        // Crossing the goal line.
        if (crossed == null && prev.z < 0f && ball.pos.z >= 0f) {
            val t = -prev.z / (ball.pos.z - prev.z)
            val c = prev + (ball.pos - prev) * t
            crossed = c
            if (abs(c.x) < GOAL_HALF_W - BALL_R * 0.5f && c.y < GOAL_H - BALL_R * 0.5f) {
                scored = true
                level.targets.forEachIndexed { i, tg ->
                    val dx = c.x - tg.x; val dy = c.y - tg.y
                    if (sqrt(dx * dx + dy * dy) < tg.r) ringHit = i
                }
            }
        }

        // The net catches the ball.
        if (scored && ball.pos.z > 0f) {
            val back = netBackZ(ball.pos.y) - BALL_R
            if (ball.pos.z > back) {
                if (netBulge == 0f && ball.vel.z > 2f) {
                    netHitX = ball.pos.x; netHitY = ball.pos.y
                    netBulge = (ball.vel.z / 25f).coerceIn(0.3f, 1f)
                }
                ball.pos = Vec3(ball.pos.x, ball.pos.y, back)
                if (ball.vel.z > 0f) ball.vel = Vec3(ball.vel.x * 0.3f, ball.vel.y * 0.3f, -ball.vel.z * 0.08f)
                ball.spin = Vec3.ZERO
            }
            val side = GOAL_HALF_W - BALL_R
            if (abs(ball.pos.x) > side) {
                ball.pos = Vec3(sign(ball.pos.x) * side, ball.pos.y, ball.pos.z)
                ball.vel = Vec3(-ball.vel.x * 0.1f, ball.vel.y, ball.vel.z * 0.5f)
            }
            val roof = GOAL_H - BALL_R
            if (ball.pos.y > roof) {
                ball.pos = Vec3(ball.pos.x, roof, ball.pos.z)
                if (ball.vel.y > 0f) ball.vel = Vec3(ball.vel.x * 0.5f, 0f, ball.vel.z * 0.5f)
            }
        }
        // Advertising boards behind the goal stop stray balls.
        if (ball.pos.z > BOARDS_Z - BALL_R) {
            ball.pos = Vec3(ball.pos.x, ball.pos.y, BOARDS_Z - BALL_R)
            if (ball.vel.z > 0f) ball.vel = Vec3(ball.vel.x * 0.5f, ball.vel.y * 0.5f, -ball.vel.z * 0.3f)
        }
    }

    /** The back of the net slopes: full depth at the bottom, shallower at the top. */
    fun netBackZ(y: Float): Float = NET_DEPTH - (y / GOAL_H).coerceIn(0f, 1f) * 0.9f

    private fun decide() {
        val c = crossed
        val t = phaseT
        val stopped = ball.vel.len() < 0.5f && ball.pos.y <= BALL_R + 0.01f
        val r: Result = when {
            scored && (t > 0.15f + (kick?.time ?: 0f) || ball.pos.z > 0.5f) ->
                if (level.targetsOnly && ringHit < 0) Result.NO_RING else Result.GOAL
            c != null && !scored && ball.pos.z > 0.4f ->
                when {
                    touchedKeeper -> Result.SAVED
                    result == Result.BLOCKED -> Result.BLOCKED
                    hitPost -> Result.POST
                    c.y >= GOAL_H -> Result.OVER
                    else -> Result.WIDE
                }
            touchedKeeper && (ball.vel.z < 0f || stopped || t > 2.2f) -> Result.SAVED
            result == Result.BLOCKED && (ball.vel.z < 1f || stopped || t > 2.2f) -> Result.BLOCKED
            stopped || t > 3.5f -> if (hitPost) Result.POST else Result.WEAK
            else -> Result.NONE
        }
        if (r != Result.NONE) finishShot(r)
    }

    private fun finishShot(r: Result) {
        result = r
        phase = Phase.OUTCOME
        phaseT = 0f
        bonusLines.clear()
        lastPoints = 0
        val goal = r == Result.GOAL
        history += goal
        if (goal) {
            goals++
            streak++
            val c = crossed ?: ball.pos
            var pts = 100
            bonusLines += "GOAL +100"
            val corner = ((abs(c.x) / GOAL_HALF_W) * 60f + (c.y / GOAL_H) * 40f).toInt().coerceIn(0, 100)
            if (corner >= 40) { pts += corner; bonusLines += "PLACEMENT +$corner" }
            if (abs(kick?.curl ?: 0f) > 0.3f) { pts += 75; bonusLines += "CURLER +75" }
            if (ringHit >= 0) { pts += 250; bonusLines += "RING +250" }
            if (hitPost) { pts += 50; bonusLines += "IN OFF THE POST +50" }
            if ((kick?.power ?: 0f) > 0.85f) { pts += 50; bonusLines += "ROCKET +50" }
            if (streak > 1) { val s = (streak - 1) * 50; pts += s; bonusLines += "STREAK x$streak +$s" }
            lastPoints = pts
            score += pts
            sound.play(Sound.GOAL)
        } else {
            streak = 0
            sound.play(Sound.MISS)
        }
        shot++
    }

    private fun nextShot() {
        if (shot >= level.shots || hopeless) {
            phase = Phase.DONE
            phaseT = 0f
            sound.play(Sound.WHISTLE)
            return
        }
        resetShot()
        phase = Phase.AIM
        phaseT = 0f
        sound.play(Sound.WHISTLE)
    }

    /** Skips the result message. */
    fun skip() { if (phase == Phase.OUTCOME && phaseT > 0.6f) nextShot() }

    fun snapshot() = Session(levelIndex, shot, goals, score, streak)

    /** Stars earned: 1 for passing, 2 for scoring most, 3 for a perfect round. */
    fun stars(): Int = when {
        !passed -> 0
        goals == level.shots -> 3
        goals >= level.need + (level.shots - level.need + 1) / 2 -> 2
        else -> 1
    }

    /** Net bulge at a point of the back net, in metres. */
    fun bulgeAt(x: Float, y: Float): Float {
        if (netBulge <= 0f) return 0f
        val dx = x - netHitX; val dy = y - netHitY
        return netBulge * 0.55f * exp(-(dx * dx + dy * dy) / 0.9f)
    }

    companion object {
        const val MAX_PTS = 64
        const val BOARDS_Z = 5.5f
    }
}
