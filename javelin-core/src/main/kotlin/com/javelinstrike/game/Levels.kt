package com.javelinstrike.game

import kotlin.math.max
import kotlin.math.min

/**
 * The rules for one level. Distances are in metres and times in seconds.
 *
 * @param distance how far away the target is (each new target varies by ±10%).
 * @param spread the target can sit up to this far left or right of the middle.
 * @param radius size of the target's outer ring; the middle and bullseye are 2/3 and 1/3 of it.
 * @param time the time limit. You have unlimited javelins until it runs out.
 * @param hits how many hits clear the level. After each hit the target moves.
 * @param wind strongest crosswind in m/s. Each level picks a direction and strength up to this.
 * @param sway a moving target slides this far either side of its spot.
 * @param swaySpeed how fast a moving target slides (higher is faster).
 */
data class LevelSpec(
    val name: String,
    val distance: Float,
    val spread: Float,
    val radius: Float,
    val time: Float,
    val hits: Int,
    val wind: Float = 0f,
    val sway: Float = 0f,
    val swaySpeed: Float = 0f,
)

/**
 * The hand-made levels, easiest first. Change these numbers, or add lines, to tune the game.
 * The longest throw at full power is about 130 m, so keep distances under about 110.
 */
val LEVELS = listOf(
    LevelSpec("Warm-Up", distance = 20f, spread = 0f, radius = 6f, time = 45f, hits = 1),
    LevelSpec("Getting Loose", distance = 28f, spread = 4f, radius = 5.5f, time = 40f, hits = 1),
    LevelSpec("Double Up", distance = 35f, spread = 8f, radius = 5f, time = 45f, hits = 2),
    LevelSpec("Breezy", distance = 40f, spread = 8f, radius = 5f, time = 45f, hits = 2, wind = 3f),
    LevelSpec("Long Shot", distance = 55f, spread = 10f, radius = 4.5f, time = 45f, hits = 2, wind = 3f),
    LevelSpec("On the Move", distance = 45f, spread = 6f, radius = 4.5f, time = 45f, hits = 2, sway = 6f, swaySpeed = 0.8f),
    LevelSpec("Gusty", distance = 60f, spread = 12f, radius = 4f, time = 45f, hits = 2, wind = 5f),
    LevelSpec("Hat Trick", distance = 50f, spread = 14f, radius = 4f, time = 50f, hits = 3, wind = 4f),
    LevelSpec("Slider", distance = 65f, spread = 10f, radius = 3.5f, time = 45f, hits = 2, wind = 4f, sway = 9f, swaySpeed = 1f),
    LevelSpec("Stadium Record", distance = 80f, spread = 12f, radius = 3.5f, time = 45f, hits = 2, wind = 5f),
    LevelSpec("Storm Front", distance = 75f, spread = 16f, radius = 3f, time = 50f, hits = 3, wind = 7f, sway = 6f, swaySpeed = 1.1f),
    LevelSpec("Olympian", distance = 95f, spread = 16f, radius = 3f, time = 50f, hits = 3, wind = 7f, sway = 10f, swaySpeed = 1.2f),
)

/** The level with this number (starting at 1). After the hand-made ones, levels keep getting harder forever. */
fun levelSpec(number: Int): LevelSpec {
    if (number <= LEVELS.size) return LEVELS[max(number, 1) - 1]
    val k = number - LEVELS.size
    return LevelSpec(
        name = "Endless $k",
        distance = 55f + (k * 23) % 50,
        spread = min(25f, 14f + k),
        radius = max(1.8f, 3f - k * 0.1f),
        time = max(30f, 50f - k),
        hits = 3,
        wind = min(10f, 6f + k * 0.4f),
        sway = if (k % 2 == 0) min(14f, 6f + k * 0.5f) else 0f,
        swaySpeed = min(2f, 1f + k * 0.06f),
    )
}
