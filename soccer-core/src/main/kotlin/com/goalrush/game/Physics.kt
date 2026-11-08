package com.goalrush.game

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt

const val GRAVITY = 9.81f
const val BALL_R = 0.11f
const val GOAL_HALF_W = 3.66f
const val GOAL_H = 2.44f
const val POST_R = 0.06f
const val NET_DEPTH = 2.0f
const val KEEPER_Z = -0.35f
const val WALL_DIST = 9.15f

class Ball {
    var pos = Vec3(0f, BALL_R, -11f)
    var vel = Vec3.ZERO
    /** Sideways acceleration from spin (the "curl" the player swiped). */
    var spin = Vec3.ZERO
    var wind = 0f
    /** Visual roll angle, in radians. */
    var roll = 0f
    var rollAxis = Vec3(1f, 0f, 0f)
    var bounces = 0

    fun reset(p: Vec3) {
        pos = p; vel = Vec3.ZERO; spin = Vec3.ZERO; roll = 0f; bounces = 0
    }

    fun step(dt: Float) {
        vel = Vec3(
            vel.x + (spin.x + wind) * dt,
            vel.y - GRAVITY * dt,
            vel.z + spin.z * dt,
        )
        pos += vel * dt
        if (pos.y < BALL_R) {
            pos = Vec3(pos.x, BALL_R, pos.z)
            if (vel.y < 0f) {
                val bounceV = -vel.y * 0.5f
                vel = Vec3(vel.x * 0.86f, if (bounceV < 0.6f) 0f else bounceV, vel.z * 0.86f)
                spin = Vec3.ZERO
                bounces++
            }
            if (vel.y == 0f) vel = Vec3(vel.x * (1f - 1.2f * dt), 0f, vel.z * (1f - 1.2f * dt))
        }
        val hs = sqrt(vel.x * vel.x + vel.z * vel.z)
        if (hs > 0.01f) rollAxis = Vec3(vel.z, 0f, -vel.x) * (1f / hs)
        roll += vel.len() / BALL_R * dt * 0.35f
    }

    /** Bounces the ball off a sphere at [c] with radius [r]. Returns true on contact. */
    fun collideSphere(c: Vec3, r: Float, restitution: Float): Boolean {
        val d = pos - c
        val dist = d.len()
        val minD = r + BALL_R
        if (dist >= minD || dist < 1e-5f) return false
        val n = d * (1f / dist)
        pos = c + n * minD
        val vn = vel dot n
        if (vn < 0f) {
            val tangential = vel - n * vn
            vel = tangential * 0.7f - n * (vn * restitution)
        }
        spin = Vec3.ZERO
        return true
    }

    /** Bounces off a capsule from [a] to [b]. */
    fun collideSegment(a: Vec3, b: Vec3, r: Float, restitution: Float): Boolean =
        collideSphere(closestOnSegment(pos, a, b), r, restitution)
}

fun closestOnSegment(p: Vec3, a: Vec3, b: Vec3): Vec3 {
    val ab = b - a
    val l2 = ab dot ab
    if (l2 < 1e-8f) return a
    val t = (((p - a) dot ab) / l2).coerceIn(0f, 1f)
    return a + ab * t
}

/** Joint positions of a player figure, used both to draw it and for collisions. */
class Body {
    var pelvis = Vec3.ZERO
    var neck = Vec3.ZERO
    var head = Vec3.ZERO
    var shL = Vec3.ZERO; var shR = Vec3.ZERO
    var elL = Vec3.ZERO; var elR = Vec3.ZERO
    var handL = Vec3.ZERO; var handR = Vec3.ZERO
    var hipL = Vec3.ZERO; var hipR = Vec3.ZERO
    var kneeL = Vec3.ZERO; var kneeR = Vec3.ZERO
    var footL = Vec3.ZERO; var footR = Vec3.ZERO

    /**
     * Poses the body. [phi] tilts it sideways (0 = upright, +90° = lying with the head to the
     * right). [arms] blends from arms out low (0) to arms stretched past the head (1).
     * [crouch] bends the knees.
     */
    fun pose(p: Vec3, phi: Float, arms: Float, crouch: Float, spread: Float = 0f) {
        val u = Vec3(sin(phi), cos(phi), 0f)
        val s = Vec3(cos(phi), -sin(phi), 0f)
        val fwd = Vec3(0f, 0f, -1f)
        pelvis = p
        neck = p + u * 0.55f
        head = p + u * 0.76f
        shL = neck - s * 0.2f
        shR = neck + s * 0.2f
        val restL = shL - s * (0.38f + spread * 0.2f) - u * (0.3f - spread * 0.3f) + fwd * 0.15f
        val restR = shR + s * (0.38f + spread * 0.2f) - u * (0.3f - spread * 0.3f) + fwd * 0.15f
        val upL = neck + u * 0.72f - s * 0.13f
        val upR = neck + u * 0.72f + s * 0.13f
        handL = restL + (upL - restL) * arms
        handR = restR + (upR - restR) * arms
        elL = (shL + handL) * 0.5f - s * 0.08f * (1f - arms)
        elR = (shR + handR) * 0.5f + s * 0.08f * (1f - arms)
        hipL = p - s * 0.12f
        hipR = p + s * 0.12f
        val legLen = 0.92f - crouch * 0.22f
        footL = p - u * legLen - s * (0.2f + spread * 0.15f)
        footR = p - u * legLen + s * (0.2f + spread * 0.15f)
        kneeL = (hipL + footL) * 0.5f + fwd * (0.06f + crouch * 0.2f) - s * crouch * 0.08f
        kneeR = (hipR + footR) * 0.5f + fwd * (0.06f + crouch * 0.2f) + s * crouch * 0.08f
    }

    /** Lets the ball bounce off this body. Returns true if it touched. */
    fun collide(ball: Ball, restitution: Float, glove: Float = 0.12f): Boolean {
        var hit = false
        hit = ball.collideSphere(handL, glove, restitution) || hit
        hit = ball.collideSphere(handR, glove, restitution) || hit
        hit = ball.collideSphere(head, 0.13f, restitution) || hit
        hit = ball.collideSegment(pelvis, neck, 0.19f, restitution) || hit
        hit = ball.collideSegment(shL, handL, 0.08f, restitution) || hit
        hit = ball.collideSegment(shR, handR, 0.08f, restitution) || hit
        hit = ball.collideSegment(hipL, footL, 0.1f, restitution) || hit
        hit = ball.collideSegment(hipR, footR, 0.1f, restitution) || hit
        return hit
    }
}

class Keeper {
    enum class State { READY, STAND, DIVE, FALL }

    var state = State.READY
    var homeX = 0f
    var x = 0f
    var y = 0.95f
    var phi = 0f
    var arms = 0f
    var crouch = 0.4f
    val body = Body()

    private var t = 0f
    private var tx = 0f
    private var ty = 0.95f
    private var tPhi = 0f
    private var tArms = 0f
    private var speed = 4f
    private var vy = 0f
    private var planDelay = -1f
    private var planX = 0f
    private var planY = 0f

    fun reset(home: Float) {
        homeX = home; x = home; y = 0.92f; phi = 0f; arms = 0f; crouch = 0.4f
        state = State.READY; t = 0f; planDelay = -1f; vy = 0f
        pose()
    }

    /** After [delay] seconds, go for the ball crossing the line at ([px], [py]). */
    fun plan(px: Float, py: Float, delay: Float, speed: Float) {
        planX = px; planY = py; planDelay = delay; this.speed = speed
    }

    private fun react() {
        val dx = planX - x
        if (abs(dx) < 0.55f && planY < 2.35f) {
            state = State.STAND
            tx = planX
            ty = if (planY > 1.7f) 0.95f + (planY - 1.7f) else if (planY < 0.5f) 0.7f else 0.95f
            tPhi = 0f
            tArms = if (planY > 1.25f) 1f else 0f
        } else {
            state = State.DIVE
            val dy = planY - 0.95f
            tPhi = atan2(dx, dy.coerceAtLeast(-0.4f)).coerceIn(-1.8f, 1.8f)
            val ux = sin(tPhi); val uy = cos(tPhi)
            tx = planX - ux * 1.2f
            ty = (planY - uy * 1.2f).coerceIn(0.3f, 1.9f)
            tArms = 1f
        }
        t = 0f
    }

    fun update(dt: Float) {
        t += dt
        if (planDelay >= 0f) {
            planDelay -= dt
            if (planDelay < 0f) react()
        }
        when (state) {
            State.READY -> {
                // Bounce on the toes and shuffle a little while waiting.
                x = homeX + sin(t * 1.7f) * 0.18f
                y = 0.9f + abs(sin(t * 3.4f)) * 0.04f
                crouch = 0.4f
                arms = 0.05f
            }
            State.STAND, State.DIVE -> {
                val dx = tx - x; val dy = ty - y
                val d = sqrt(dx * dx + dy * dy)
                val step = speed * dt
                if (d > step) { x += dx / d * step; y += dy / d * step } else { x = tx; y = ty }
                phi = approach(phi, tPhi, 7.5f * dt)
                arms = approach(arms, tArms, 7f * dt)
                crouch = approach(crouch, if (state == State.DIVE) 0.1f else 0.25f, 4f * dt)
                if (state == State.DIVE && (t > 0.75f || (d < 0.02f && abs(phi - tPhi) < 0.05f && t > 0.45f))) {
                    state = State.FALL; vy = 0f
                }
            }
            State.FALL -> {
                vy -= GRAVITY * dt
                y = (y + vy * dt).coerceAtLeast(0.25f)
                phi = approach(phi, sign(phi) * 1.57f, 3f * dt)
            }
        }
        pose()
    }

    private fun pose() = body.pose(Vec3(x, y, KEEPER_Z), phi, arms, crouch, spread = if (state == State.READY) 1f else 0.3f)

    private fun approach(v: Float, target: Float, step: Float) =
        if (abs(target - v) <= step) target else v + sign(target - v) * step
}

class WallMan(val x: Float, val z: Float, val facing: Float) {
    var jump = 0f
    private var vy = 0f
    private var jumpIn = -1f
    val body = Body()

    init { pose() }

    fun startJump(delay: Float) { jumpIn = delay }

    fun update(dt: Float) {
        if (jumpIn >= 0f) {
            jumpIn -= dt
            if (jumpIn < 0f) vy = 3.3f
        }
        if (vy != 0f || jump > 0f) {
            vy -= GRAVITY * dt
            jump += vy * dt
            if (jump <= 0f) { jump = 0f; vy = 0f }
        }
        pose()
    }

    private fun pose() {
        body.pose(Vec3(x, 0.95f + jump, z), 0f, 0f, 0f, spread = -0.6f)
        // Hands crossed in front, like a real wall.
        val front = Vec3(-sin(facing), 0f, -cos(facing)) * 0.16f
        body.handL = body.pelvis + front + Vec3(0.06f, 0.02f, 0f)
        body.handR = body.pelvis + front + Vec3(-0.06f, 0.02f, 0f)
        body.elL = (body.shL + body.handL) * 0.5f
        body.elR = (body.shR + body.handR) * 0.5f
    }
}

/** The initial conditions of a kick, solved from a swipe. */
data class Kick(
    val velocity: Vec3,
    val spin: Vec3,
    /** Where the swipe asked the ball to cross the goal line. */
    val target: Vec3,
    /** Flight time to the target, in seconds. */
    val time: Float,
    /** How much the swipe curved, -1..1. */
    val curl: Float,
    val power: Float,
)

object ShotSolver {
    const val MIN_SPEED = 12f
    const val MAX_SPEED = 34f

    /**
     * Velocity and spin so a ball at [from] passes exactly through [target] after [time]
     * seconds, bowing sideways by [bow] metres (positive = to the right) halfway there.
     */
    fun solve(from: Vec3, target: Vec3, time: Float, bow: Float): Pair<Vec3, Vec3> {
        val dx = target.x - from.x
        val dz = target.z - from.z
        val d = sqrt(dx * dx + dz * dz).coerceAtLeast(0.01f)
        val right = Vec3(dz / d, 0f, -dx / d)
        val a = (-8f * bow / (time * time)).coerceIn(-16f, 16f)
        val spin = right * a
        val vx = dx / time - 0.5f * spin.x * time
        val vz = dz / time - 0.5f * spin.z * time
        val vy = (target.y - from.y + 0.5f * GRAVITY * time * time) / time
        return Vec3(vx, vy, vz) to spin
    }

    /**
     * Turns a finger swipe (screen points relative to the ball) into a kick. The ball heads for
     * the point where the swipe ends, bends the way the swipe bends, and flies faster the faster
     * the swipe. Returns null if the swipe was not a shot.
     */
    fun fromSwipe(cam: Camera, ball: Vec3, xs: FloatArray, ys: FloatArray, ts: FloatArray, n: Int): Kick? {
        if (n < 2) return null
        val bp = cam.project(ball, Proj())
        val ox = bp.x - xs[0]
        val oy = bp.y - ys[0]
        val ex = xs[n - 1] + ox
        val ey = ys[n - 1] + oy
        val cdx = xs[n - 1] - xs[0]
        val cdy = ys[n - 1] - ys[0]
        val chord = sqrt(cdx * cdx + cdy * cdy)
        if (chord < 25f || cdy > -15f) return null

        var pathLen = 0f
        for (i in 1 until n) {
            val a = xs[i] - xs[i - 1]; val b = ys[i] - ys[i - 1]
            pathLen += sqrt(a * a + b * b)
        }
        // Only the fast part of the swipe counts: ignore a long pause before the flick.
        var start = 0
        for (i in n - 1 downTo 1) {
            if (ts[n - 1] - ts[i - 1] > 0.6f) { start = i; break }
        }
        var flickLen = 0f
        for (i in start + 1 until n) {
            val a = xs[i] - xs[i - 1]; val b = ys[i] - ys[i - 1]
            flickLen += sqrt(a * a + b * b)
        }
        val dur = (ts[n - 1] - ts[start]).coerceAtLeast(0.06f)
        val pxPerSec = flickLen / dur
        val power = ((pxPerSec - 250f) / (2000f - 250f)).coerceIn(0f, 1f)
        val speed = MIN_SPEED + (MAX_SPEED - MIN_SPEED) * power

        // Curl: the biggest sideways bulge of the finger path, relative to its length.
        val nx = -cdy / chord
        val ny = cdx / chord
        var dev = 0f
        for (i in 1 until n - 1) {
            val d = (xs[i] - xs[0]) * nx + (ys[i] - ys[0]) * ny
            if (abs(d) > abs(dev)) dev = d
        }
        var curl = dev / chord
        curl = if (abs(curl) < 0.04f) 0f else (curl - sign(curl) * 0.04f).coerceIn(-0.3f, 0.3f)

        // Where did the finger end up? On the goal plane if it reached that far up...
        var target = cam.unprojectToZ(ex, ey, 0f)
        if (target == null || target.y < BALL_R) {
            // ...otherwise it is a low shot along the line of the swipe.
            val g = cam.unprojectToY(ex, ey, BALL_R) ?: return null
            val hx = g.x - ball.x
            val hz = g.z - ball.z
            if (hz < 0.3f) return null
            val k = (0f - ball.z) / hz
            target = Vec3(ball.x + hx * k, BALL_R, 0f)
        }
        target = Vec3(target.x.coerceIn(-30f, 30f), target.y.coerceIn(BALL_R, 9f), 0f)

        val dx = target.x - ball.x
        val dz = target.z - ball.z
        val d = sqrt(dx * dx + dz * dz)
        val dist3 = sqrt(d * d + (target.y - ball.y) * (target.y - ball.y))
        val time = (dist3 / speed).coerceAtLeast(0.25f)
        val bow = curl * d * 0.6f
        val (v, spin) = solve(ball, target, time, bow)
        return Kick(v, spin, target, time, (curl / 0.26f).coerceIn(-1f, 1f), power)
    }

    /** Simulates a kick (no players) and returns where it crosses the goal line, or null. */
    fun crossing(from: Vec3, kick: Kick, wind: Float = 0f): Vec3? {
        val b = Ball()
        b.reset(from)
        b.vel = kick.velocity
        b.spin = kick.spin
        b.wind = wind
        val dt = 1f / 240f
        var prev = b.pos
        for (i in 0 until 240 * 5) {
            b.step(dt)
            if (prev.z < 0f && b.pos.z >= 0f) {
                val t = -prev.z / (b.pos.z - prev.z)
                return prev + (b.pos - prev) * t
            }
            prev = b.pos
            if (b.vel.len() < 0.3f && b.pos.y <= BALL_R + 1e-3f) return null
        }
        return null
    }
}
