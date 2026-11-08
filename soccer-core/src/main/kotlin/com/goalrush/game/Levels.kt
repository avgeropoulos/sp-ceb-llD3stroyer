package com.goalrush.game

enum class Sky { DAY, SUNSET, NIGHT }

/** A ring hanging in the goal mouth. Shots through it score a bonus. */
data class Target(val x: Float, val y: Float, val r: Float = 0.55f)

data class Level(
    val name: String,
    val blurb: String,
    /** Distance from the ball to the goal line, in metres. */
    val dist: Float,
    /** Sideways position of the ball (negative = left of the goal). */
    val ballX: Float,
    val shots: Int,
    val need: Int,
    /** How fast the goalkeeper moves when diving, in metres per second. */
    val keeperSpeed: Float,
    /** Seconds before the goalkeeper reacts to the kick. */
    val reaction: Float,
    /** How far off the goalkeeper's read of the shot can be, in metres. */
    val keeperError: Float,
    /** Chance the goalkeeper guesses a side before the kick. */
    val guess: Float = 0.25f,
    val wall: Int = 0,
    val wallJumps: Boolean = false,
    /** Sideways wind acceleration in m/s² (positive pushes the ball to the right). */
    val wind: Float = 0f,
    val targets: List<Target> = emptyList(),
    /** Only shots through a target count as goals. */
    val targetsOnly: Boolean = false,
    val sky: Sky = Sky.DAY,
) {
    val isFreeKick get() = wall > 0
}

object Levels {
    private val corners = listOf(
        Target(-3.0f, 1.85f), Target(3.0f, 1.85f), Target(-3.0f, 0.6f), Target(3.0f, 0.6f),
    )

    val all = listOf(
        Level(
            "Penalty Spot", "Beat the keeper from 11 metres.",
            dist = 11f, ballX = 0f, shots = 5, need = 3,
            keeperSpeed = 3.4f, reaction = 0.32f, keeperError = 0.7f, guess = 0.35f,
        ),
        Level(
            "Shootout", "A sharper keeper. Aim for the corners.",
            dist = 11f, ballX = 0f, shots = 5, need = 3,
            keeperSpeed = 4.3f, reaction = 0.26f, keeperError = 0.5f, guess = 0.35f,
        ),
        Level(
            "Edge of the Box", "Shoot from the edge of the penalty area.",
            dist = 17.5f, ballX = 1.5f, shots = 5, need = 3,
            keeperSpeed = 4.4f, reaction = 0.24f, keeperError = 0.45f,
        ),
        Level(
            "The Wall", "Curl it around the wall: bend your swipe!",
            dist = 20f, ballX = -2f, shots = 5, need = 3,
            keeperSpeed = 4.4f, reaction = 0.24f, keeperError = 0.4f, wall = 3,
        ),
        Level(
            "Target Practice", "Only shots through the rings count.",
            dist = 15f, ballX = 0f, shots = 6, need = 3,
            keeperSpeed = 3.8f, reaction = 0.3f, keeperError = 0.5f,
            targets = corners, targetsOnly = true,
        ),
        Level(
            "Tight Angle", "A free kick from out wide.",
            dist = 17f, ballX = -11f, shots = 5, need = 3,
            keeperSpeed = 4.8f, reaction = 0.22f, keeperError = 0.4f, wall = 3,
        ),
        Level(
            "Windy Day", "Watch the wind and aim into it.",
            dist = 21f, ballX = 4f, shots = 6, need = 3,
            keeperSpeed = 4.8f, reaction = 0.22f, keeperError = 0.4f, wall = 4, wind = -2.4f,
            sky = Sky.SUNSET,
        ),
        Level(
            "Long Range", "A screamer from 28 metres.",
            dist = 28f, ballX = 0f, shots = 6, need = 3,
            keeperSpeed = 5.0f, reaction = 0.2f, keeperError = 0.35f, wall = 4,
        ),
        Level(
            "Night Derby", "The wall jumps. Go low or bend it.",
            dist = 22f, ballX = -6f, shots = 6, need = 4,
            keeperSpeed = 5.4f, reaction = 0.2f, keeperError = 0.3f, wall = 5, wallJumps = true,
            sky = Sky.NIGHT, targets = listOf(Target(-3.0f, 1.85f), Target(3.0f, 1.85f)),
        ),
        Level(
            "Cup Final", "The best keeper in the league. Win the cup!",
            dist = 24f, ballX = 3f, shots = 7, need = 4,
            keeperSpeed = 5.8f, reaction = 0.18f, keeperError = 0.28f, wall = 5, wallJumps = true,
            wind = 1.5f, sky = Sky.NIGHT,
        ),
    )
}
