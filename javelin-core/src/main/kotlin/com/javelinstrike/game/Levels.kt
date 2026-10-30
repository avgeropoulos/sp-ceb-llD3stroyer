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
 * @param swayZ a moving target also drifts this far nearer and further away.
 * @param swaySpeed how fast a moving target moves (higher is faster).
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
    val swayZ: Float = 0f,
    val swaySpeed: Float = 0f,
)

/**
 * The hand-made levels, easiest first. Change these numbers, or add lines, to tune the game.
 * The longest throw at full power is about 140 m, so keep distance + swayZ under about 120.
 */
val LEVELS = listOf(
    LevelSpec("Olympia Trials", distance = 25f, spread = 0f, radius = 6f, time = 45f, hits = 1),
    LevelSpec("First Steps", distance = 30f, spread = 4f, radius = 5.5f, time = 45f, hits = 1, sway = 4f, swaySpeed = 0.6f),
    LevelSpec("Hermes Runs", distance = 38f, spread = 6f, radius = 5f, time = 45f, hits = 2, sway = 7f, swaySpeed = 0.8f),
    LevelSpec("Breath of Zephyr", distance = 45f, spread = 6f, radius = 5f, time = 45f, hits = 2, wind = 3f, sway = 5f, swaySpeed = 0.8f),
    LevelSpec("The Long Field", distance = 58f, spread = 8f, radius = 4.5f, time = 45f, hits = 2, wind = 3f, sway = 6f, swaySpeed = 0.8f),
    LevelSpec("Artemis Hunts", distance = 52f, spread = 8f, radius = 4.5f, time = 45f, hits = 2, sway = 9f, swaySpeed = 1.1f),
    LevelSpec("Tides of Poseidon", distance = 62f, spread = 6f, radius = 4.5f, time = 50f, hits = 2, wind = 3f, sway = 4f, swayZ = 10f, swaySpeed = 0.9f),
    LevelSpec("Boreas Blows", distance = 68f, spread = 10f, radius = 4f, time = 50f, hits = 2, wind = 6f, sway = 7f, swaySpeed = 0.9f),
    LevelSpec("Chariot Race", distance = 72f, spread = 6f, radius = 4f, time = 50f, hits = 2, sway = 13f, swaySpeed = 1.3f),
    LevelSpec("Apollo's Mark", distance = 82f, spread = 8f, radius = 3.5f, time = 50f, hits = 3, wind = 4f, sway = 8f, swayZ = 8f, swaySpeed = 1f),
    LevelSpec("Labours of Heracles", distance = 88f, spread = 10f, radius = 3.5f, time = 55f, hits = 3, wind = 6f, sway = 10f, swaySpeed = 1.2f),
    LevelSpec("Storm of Zeus", distance = 96f, spread = 8f, radius = 3.2f, time = 55f, hits = 3, wind = 7f, sway = 10f, swayZ = 10f, swaySpeed = 1.3f),
    LevelSpec("Olympian Glory", distance = 104f, spread = 8f, radius = 3f, time = 55f, hits = 3, wind = 7f, sway = 12f, swayZ = 8f, swaySpeed = 1.4f),
)

/** The level with this number (starting at 1). After the hand-made ones, levels keep getting harder forever. */
fun levelSpec(number: Int): LevelSpec {
    if (number <= LEVELS.size) return LEVELS[max(number, 1) - 1]
    val k = number - LEVELS.size
    return LevelSpec(
        name = "Endless Games $k",
        distance = 60f + (k * 23) % 45,
        spread = min(14f, 8f + k),
        radius = max(1.8f, 3f - k * 0.1f),
        time = max(35f, 55f - k),
        hits = 3,
        wind = min(10f, 6f + k * 0.4f),
        sway = min(16f, 9f + k * 0.5f),
        swayZ = if (k % 2 == 0) min(12f, 6f + k * 0.5f) else 0f,
        swaySpeed = min(2.2f, 1.2f + k * 0.06f),
    )
}
