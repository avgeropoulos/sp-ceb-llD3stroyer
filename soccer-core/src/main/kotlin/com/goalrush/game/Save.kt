package com.goalrush.game

/** One entry in the all-time high score table. */
data class HighScore(val score: Int, val level: Int, val slot: Int)

/** A level that was being played when the game was saved, so it can be resumed. */
data class Session(
    val level: Int,
    val shot: Int,
    val goals: Int,
    val score: Int,
    val streak: Int,
)

/**
 * Saved games. There are [SLOTS] save slots. Each one keeps unlocked levels, stars, best
 * score per level and an unfinished level to resume. High scores are shared by all slots.
 */
class SaveData(private val store: Storage) {
    private fun k(slot: Int, key: String) = "s${slot}_$key"

    fun exists(slot: Int) = store.getInt(k(slot, "used")) == 1

    fun create(slot: Int) {
        clear(slot)
        store.putInt(k(slot, "used"), 1)
        store.putInt(k(slot, "unlocked"), 0)
    }

    fun clear(slot: Int) {
        store.putInt(k(slot, "used"), 0)
        store.putInt(k(slot, "unlocked"), 0)
        for (i in Levels.all.indices) {
            store.putInt(k(slot, "stars$i"), 0)
            store.putInt(k(slot, "best$i"), 0)
        }
        store.putInt(k(slot, "goals"), 0)
        store.putInt(k(slot, "shots"), 0)
        clearSession(slot)
    }

    /** Index of the highest level the player may pick. */
    fun unlocked(slot: Int) = store.getInt(k(slot, "unlocked")).coerceIn(0, Levels.all.lastIndex)

    fun stars(slot: Int, level: Int) = store.getInt(k(slot, "stars$level"))
    fun best(slot: Int, level: Int) = store.getInt(k(slot, "best$level"))
    fun totalStars(slot: Int) = Levels.all.indices.sumOf { stars(slot, it) }
    fun totalBest(slot: Int) = Levels.all.indices.sumOf { best(slot, it) }
    fun careerGoals(slot: Int) = store.getInt(k(slot, "goals"))
    fun careerShots(slot: Int) = store.getInt(k(slot, "shots"))

    fun recordShot(slot: Int, goal: Boolean) {
        store.putInt(k(slot, "shots"), careerShots(slot) + 1)
        if (goal) store.putInt(k(slot, "goals"), careerGoals(slot) + 1)
    }

    /**
     * Stores a finished level. Returns true if [score] is a new best for this level.
     */
    fun finishLevel(slot: Int, level: Int, score: Int, stars: Int, passed: Boolean): Boolean {
        val newBest = score > best(slot, level)
        if (newBest) store.putInt(k(slot, "best$level"), score)
        if (stars > stars(slot, level)) store.putInt(k(slot, "stars$level"), stars)
        if (passed && level + 1 > store.getInt(k(slot, "unlocked")) && level + 1 < Levels.all.size) {
            store.putInt(k(slot, "unlocked"), level + 1)
        }
        if (passed && level == Levels.all.lastIndex) store.putInt(k(slot, "champion"), 1)
        clearSession(slot)
        addHighScore(HighScore(score, level, slot))
        return newBest
    }

    fun champion(slot: Int) = store.getInt(k(slot, "champion")) == 1

    fun saveSession(slot: Int, s: Session) {
        store.putString(k(slot, "session"), "${s.level},${s.shot},${s.goals},${s.score},${s.streak}")
    }

    fun session(slot: Int): Session? {
        val parts = store.getString(k(slot, "session")).split(',')
        if (parts.size != 5) return null
        val n = parts.map { it.toIntOrNull() ?: return null }
        if (n[0] !in Levels.all.indices || n[1] !in 0 until Levels.all[n[0]].shots) return null
        return Session(n[0], n[1], n[2], n[3], n[4])
    }

    fun clearSession(slot: Int) = store.putString(k(slot, "session"), "")

    fun highScores(): List<HighScore> =
        store.getString("highscores").split(';').mapNotNull { e ->
            val p = e.split(',')
            if (p.size != 3) null else {
                val n = p.map { it.toIntOrNull() ?: return@mapNotNull null }
                HighScore(n[0], n[1], n[2])
            }
        }

    /** Adds a score to the top-[TOP] table. Returns its rank (0-based), or -1 if it missed out. */
    fun addHighScore(h: HighScore): Int {
        if (h.score <= 0) return -1
        val list = (highScores() + h).sortedByDescending { it.score }.take(TOP)
        store.putString("highscores", list.joinToString(";") { "${it.score},${it.level},${it.slot}" })
        return list.indexOfFirst { it === h }
    }

    var soundOn: Boolean
        get() = store.getInt("sound", 1) == 1
        set(v) = store.putInt("sound", if (v) 1 else 0)

    var lastSlot: Int
        get() = store.getInt("lastSlot", 0)
        set(v) = store.putInt("lastSlot", v)

    companion object {
        const val SLOTS = 3
        const val TOP = 10
    }
}
