package com.herculesapollo.walk

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/** How the critters behave: mind their business, bolt for safety when a dog gets close, run off when caught. */
object CritterAi {
    /** How high a lizard climbs the fence: above where Hercules can reach, below Apollo's jump. */
    const val LIZARD_PERCH = GROUND - 74f

    /**
     * [chasers] are the x positions of the dogs; [lookX] is who cats and raccoons keep an
     * eye on. Shared by both game modes.
     */
    fun update(c: Critter, dt: Float, chasers: List<Float>, lookX: Float, scene: WalkScene, audio: WalkAudio) {
        c.t += dt
        if (c.frozenT > 0f) {
            c.frozenT -= dt
            if (c.catchable) return
        }
        when (c.mood) {
            Mood.IDLE -> idle(c, chasers, lookX, audio)
            Mood.FLEE -> flee(c, dt)
            Mood.CHASED -> {
                c.x += c.vx * dt
                if (c.kind == Kind.PIGEON || c.kind == Kind.GULL) {
                    c.vy -= 60f * dt
                } else {
                    c.vy += 900f * dt
                }
                c.y = min(GROUND, c.y + c.vy * dt)
                if (c.x < scene.camX - 60f || c.x > scene.camX + scene.viewW + 60f || c.y < -30f) c.mood = Mood.GONE
            }
            Mood.SAFE, Mood.GONE -> {}
        }
    }

    private fun idle(c: Critter, chasers: List<Float>, lookX: Float, audio: WalkAudio) {
        val near = chasers.minOf { abs(it - c.x) }
        when (c.kind) {
            Kind.SQUIRREL -> {
                val nx = c.startX + sin(c.t * 1.3f) * 14f
                if (abs(nx - c.x) > 0.01f) c.facing = if (nx > c.x) 1 else -1
                c.x = nx
                if (near < 75f) { c.mood = Mood.FLEE; c.facing = if (c.home > c.x) 1 else -1 }
            }
            Kind.PIGEON, Kind.GULL -> {
                c.x = c.startX + sin(c.t * 0.7f) * 6f
                c.facing = if (sin(c.t * 0.35f) > 0) 1 else -1
                if (near < 42f) {
                    c.mood = Mood.FLEE
                    val dogX = chasers.minBy { abs(it - c.x) }
                    c.vx = if (c.x >= dogX) 50f else -50f
                    c.vy = -115f
                    c.facing = if (c.vx > 0) 1 else -1
                }
            }
            Kind.RABBIT -> if (near < 60f) { c.mood = Mood.FLEE; c.t = 0f }
            Kind.CAT -> if (near < 70f) { c.mood = Mood.FLEE; audio.play(WalkSound.HISS) }
            Kind.RACCOON -> if (near < 85f) { c.mood = Mood.FLEE; c.t = 0f }
            Kind.CRAB -> {
                c.x = c.startX + sin(c.t * 2f) * 14f
                if (near < 70f) c.mood = Mood.FLEE
            }
            Kind.LIZARD -> if (near < 65f) { c.mood = Mood.FLEE; c.facing = if (c.home > c.x) 1 else -1 }
            Kind.FROG -> if (near < 55f) { c.mood = Mood.FLEE; c.t = 0f }
            Kind.SKUNK -> {
                val nx = c.startX + sin(c.t * 0.5f) * 20f
                if (abs(nx - c.x) > 0.01f) c.facing = if (nx > c.x) 1 else -1
                c.x = nx
            }
        }
        if (c.kind == Kind.CAT || c.kind == Kind.RACCOON) {
            c.facing = if (lookX > c.x) -1 else 1
        }
    }

    private fun flee(c: Critter, dt: Float) {
        when (c.kind) {
            Kind.SQUIRREL -> if (!c.climbing) {
                val trunk = c.home - 7f
                val step = 100f * dt
                if (abs(trunk - c.x) <= step) { c.x = trunk; c.climbing = true }
                else c.x += if (trunk > c.x) step else -step
            } else {
                c.y -= 55f * dt
                if (c.y <= GROUND - 82f) {
                    c.climbing = false
                    c.mood = Mood.SAFE
                    c.x = c.home + 12f
                    c.y = GROUND - 84f
                    c.facing = -1
                }
            }
            Kind.PIGEON, Kind.GULL -> {
                c.x += c.vx * dt
                c.vy -= 30f * dt
                c.y += c.vy * dt
                if (c.y < GROUND - 130f) c.mood = Mood.GONE
            }
            Kind.RABBIT, Kind.CRAB -> {
                val speed = if (c.kind == Kind.RABBIT) 170f else 60f
                val step = speed * dt
                c.facing = if (c.home > c.x) 1 else -1
                if (abs(c.home - c.x) <= step) { c.x = c.home; c.hidden = true; c.mood = Mood.SAFE }
                else c.x += c.facing * step
            }
            Kind.RACCOON -> if (c.t > 1.1f) { c.hidden = true; c.mood = Mood.SAFE }
            Kind.LIZARD -> if (!c.climbing) {
                // Dart to the fence, then straight up it.
                val step = 160f * dt
                if (abs(c.home - c.x) <= step) { c.x = c.home; c.climbing = true }
                else c.x += if (c.home > c.x) step else -step
            } else {
                c.y -= 70f * dt
                if (c.y <= LIZARD_PERCH) { c.y = LIZARD_PERCH; c.mood = Mood.SAFE }
            }
            Kind.FROG -> {
                // Big hops toward the pond, then a splash.
                val dir = if (c.home > c.x) 1 else -1
                c.facing = dir
                if (c.y >= GROUND) {
                    if (abs(c.home - c.x) < 8f) { c.x = c.home; c.hidden = true; c.mood = Mood.SAFE; return }
                    c.vy = -190f
                }
                c.vy += 700f * dt
                c.y = min(GROUND, c.y + c.vy * dt)
                c.x += dir * 110f * dt
            }
            Kind.CAT, Kind.SKUNK -> {}
        }
    }
}
