package com.herculesapollo.walk3d

import com.herculesapollo.walk.Kind
import com.herculesapollo.walk.WalkAudio
import com.herculesapollo.walk.WalkSound
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

enum class State3 {
    /** Minding its own business. */
    IDLE,
    /** Running for it. */
    FLEE,
    /** Up a tree, down a hole, in a bin or off in the sky; it comes back out after a while. */
    SAFE,
    /** Caught! Running off for good. */
    CHASED,
    GONE,
}

/** A critter in the 3D world. ([x], [y], [z]) is its feet; [yaw] the way it faces. */
class Critter3(val spawn: Spawn) {
    val kind get() = spawn.kind
    var x = spawn.x
    var y = spawn.perch
    var z = spawn.z
    var yaw = 0f
    var state = State3.IDLE
    var t = 0f
    /** Counts down while SAFE; when it runs out the critter comes back out. */
    var timer = 0f
    var vx = 0f
    var vy = 0f
    var vz = 0f
    var frozenT = 0f
    var climbing = false
    var hidden = false
    var homeX = spawn.x
    var homeZ = spawn.z
    val phase = (spawn.x * 7.3f + spawn.z * 3.1f)

    val radius get() = when (kind) {
        Kind.RABBIT, Kind.CAT, Kind.RACCOON, Kind.SKUNK -> 0.32f
        else -> 0.24f
    }
    val height get() = when (kind) {
        Kind.CAT, Kind.RABBIT, Kind.RACCOON -> 0.4f
        Kind.SKUNK -> 0.35f
        Kind.CRAB -> 0.18f
        else -> 0.28f
    }
    val catchable get() = (state == State3.IDLE || state == State3.FLEE || (state == State3.SAFE && !hidden)) && kind != Kind.SKUNK
    val flying get() = (kind == Kind.PIGEON || kind == Kind.GULL) && y > 0.05f
}

/** Critter behavior: what each kind does when a dog comes near, and how it gets away. */
object CritterBrain {
    fun update(c: Critter3, dt: Float, dogX: Float, dogZ: Float, level: Level, audio: WalkAudio) {
        c.t += dt
        if (c.frozenT > 0f) {
            c.frozenT -= dt
            if (c.state == State3.IDLE || c.state == State3.FLEE) return
        }
        val dx = c.x - dogX
        val dz = c.z - dogZ
        val dist = hypot(dx, dz)
        when (c.state) {
            State3.IDLE -> idle(c, dt, dist, dx, dz, audio)
            State3.FLEE -> flee(c, dt, dx, dz, dist, level)
            State3.SAFE -> {
                c.timer -= dt
                if (c.kind == Kind.SQUIRREL && c.timer <= 0f) {
                    // Climb back down once the coast is clear.
                    c.climbing = true
                    c.y -= 2.2f * dt
                    if (c.y <= 0f) { c.y = 0f; c.climbing = false; c.state = State3.IDLE; c.t = 0f }
                } else if (c.timer <= 0f && dist > 5f) {
                    comeBack(c, level)
                }
            }
            State3.CHASED -> {
                c.x += c.vx * dt
                c.z += c.vz * dt
                if (c.kind == Kind.PIGEON || c.kind == Kind.GULL) c.vy += 2f * dt else c.vy -= 25f * dt
                c.y = (c.y + c.vy * dt).coerceAtLeast(0f)
                if (c.t > 1.6f) c.state = State3.GONE
            }
            State3.GONE -> {}
        }
    }

    private fun startFlee(c: Critter3) { c.state = State3.FLEE; c.t = 0f }

    private fun idle(c: Critter3, dt: Float, dist: Float, dx: Float, dz: Float, audio: WalkAudio) {
        when (c.kind) {
            Kind.SQUIRREL -> {
                // Hop about in a little circle near home.
                val a = c.t * 0.9f + c.phase
                val tx = c.homeX + 1.6f * cos(a)
                val tz = c.homeZ + 1.6f * sin(a)
                c.yaw = atan2(tx - c.x, tz - c.z)
                c.x = tx; c.z = tz
                c.y = 0f
                if (dist < 5f) startFlee(c)
            }
            Kind.PIGEON, Kind.GULL -> {
                c.yaw = c.phase + sin(c.t * 0.6f + c.phase) * 1.5f
                if (dist < 3.5f) {
                    startFlee(c)
                    val l = dist.coerceAtLeast(0.1f)
                    c.vx = dx / l * 3f; c.vz = dz / l * 3f; c.vy = 3.2f
                    c.yaw = atan2(c.vx, c.vz)
                }
            }
            Kind.RABBIT -> if (dist < 4.5f) startFlee(c)
            Kind.CAT -> {
                c.yaw = atan2(-dx, -dz)
                if (dist < 3.5f) { startFlee(c); audio.play(WalkSound.HISS) }
            }
            Kind.RACCOON -> {
                c.yaw = atan2(-dx, -dz)
                if (dist < 4.5f) startFlee(c)
            }
            Kind.CRAB -> {
                val a = sin(c.t * 1.8f + c.phase) * 1.1f
                c.x = c.homeX + a
                c.yaw = (Math.PI / 2).toFloat()
                if (dist < 4f) startFlee(c)
            }
            Kind.SKUNK -> {
                val a = c.t * 0.35f + c.phase
                val tx = c.homeX + 2f * cos(a)
                val tz = c.homeZ + 2f * sin(a)
                c.yaw = atan2(tx - c.x, tz - c.z)
                c.x = tx; c.z = tz
            }
        }
    }

    private fun flee(c: Critter3, dt: Float, dx: Float, dz: Float, dist: Float, level: Level) {
        val l = dist.coerceAtLeast(0.1f)
        when (c.kind) {
            Kind.SQUIRREL -> if (!c.climbing) {
                // Dash for the tree trunk, on the side away from the dog.
                val tx = c.spawn.hx
                val tz = c.spawn.hz
                val ax = c.x - tx; val az = c.z - tz
                val al = hypot(ax, az).coerceAtLeast(0.01f)
                val gx = tx + ax / al * 0.42f
                val gz = tz + az / al * 0.42f
                val mx = gx - c.x; val mz = gz - c.z
                val ml = hypot(mx, mz)
                val step = 6f * dt
                if (ml <= step) { c.x = gx; c.z = gz; c.climbing = true; c.yaw = atan2(-ax, -az) }
                else { c.x += mx / ml * step; c.z += mz / ml * step; c.yaw = atan2(mx, mz) }
            } else {
                c.y += 1.7f * dt
                if (c.y >= SQUIRREL_PERCH) {
                    c.y = SQUIRREL_PERCH
                    c.climbing = false
                    c.state = State3.SAFE
                    c.timer = 7f
                }
            }
            Kind.PIGEON, Kind.GULL -> {
                c.x += c.vx * dt; c.z += c.vz * dt; c.y += c.vy * dt
                if (c.y > 6f) { c.state = State3.SAFE; c.hidden = true; c.timer = 5f }
            }
            Kind.RABBIT -> {
                // Zigzag away from the dog, then down the burrow.
                val zig = sin(c.t * 7f) * 0.6f
                val ax = dx / l; val az = dz / l
                c.vx = (ax * cos(zig) - az * sin(zig)) * RABBIT_SPEED
                c.vz = (ax * sin(zig) + az * cos(zig)) * RABBIT_SPEED
                c.x = (c.x + c.vx * dt).coerceIn(-level.half + 0.5f, level.half - 0.5f)
                c.z = (c.z + c.vz * dt).coerceIn(-level.half + 0.5f, level.half - 0.5f)
                c.yaw = atan2(c.vx, c.vz)
                if (c.t > 2.6f) { c.state = State3.SAFE; c.hidden = true; c.timer = 4f }
            }
            Kind.CAT -> {
                c.yaw = atan2(-dx, -dz)
                if (dist > 6f) c.state = State3.IDLE
            }
            Kind.RACCOON -> if (c.t > 1.4f) { c.state = State3.SAFE; c.hidden = true; c.timer = 5f }
            Kind.CRAB -> {
                val mx = c.spawn.hx - c.x; val mz = c.spawn.hz - c.z
                val ml = hypot(mx, mz)
                val step = 2.4f * dt
                if (ml <= step) { c.x = c.spawn.hx; c.z = c.spawn.hz; c.state = State3.SAFE; c.hidden = true; c.timer = 4f }
                else { c.x += mx / ml * step; c.z += mz / ml * step }
            }
            Kind.SKUNK -> {}
        }
    }

    /** Pops back out of hiding (birds land somewhere new nearby). */
    private fun comeBack(c: Critter3, level: Level) {
        c.hidden = false
        c.state = State3.IDLE
        c.t = 0f
        when (c.kind) {
            Kind.PIGEON, Kind.GULL -> {
                val a = c.phase + c.timer * 3f
                c.x = (c.spawn.x + 4f * cos(a)).coerceIn(-level.half + 1f, level.half - 1f)
                c.z = (c.spawn.z + 4f * sin(a)).coerceIn(-level.half + 1f, level.half - 1f)
                c.y = 0f
            }
            Kind.RABBIT -> { c.homeX = c.x; c.homeZ = c.z }
            Kind.CRAB -> { c.homeX = c.spawn.hx - 1.5f; c.x = c.homeX; c.z = c.spawn.z }
            else -> {}
        }
    }

    const val SQUIRREL_PERCH = 3.1f
    const val RABBIT_SPEED = 6.4f
}
