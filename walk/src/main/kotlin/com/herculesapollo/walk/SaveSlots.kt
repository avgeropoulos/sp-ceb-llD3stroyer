package com.herculesapollo.walk

import com.roccoquest.game.Storage

/**
 * Three save slots, like Rocco's Quest. For each way of playing ([mode], e.g. "walk",
 * "herc" or "apollo") a slot remembers which walk or level you're on and your score.
 * Progress is saved at the start of every walk, when you finish one, and when you
 * pick SAVE & QUIT from the pause screen. [prefix] keeps different games apart.
 */
class SaveSlots(private val storage: Storage, private val prefix: String = "") {
    /** The slot being played, 1 to 3. */
    var current: Int
        get() = storage.load("${prefix}slot").coerceIn(1, COUNT)
        set(v) = storage.save("${prefix}slot", v.coerceIn(1, COUNT))

    private fun key(slot: Int, mode: String, what: String) = "${prefix}s${slot}_${mode}_$what"

    /** The walk to pick up from (0 for a new game). */
    fun walk(mode: String, slot: Int = current) = (storage.load(key(slot, mode, "walk")) - 1).coerceAtLeast(0)

    /** Has this slot been played in [mode] yet? */
    fun started(mode: String, slot: Int = current) = storage.load(key(slot, mode, "walk")) > 0

    fun score(mode: String, slot: Int = current) = storage.load(key(slot, mode, "score"))

    fun save(mode: String, walk: Int, score: Int, slot: Int = current) {
        storage.save(key(slot, mode, "walk"), walk + 1)
        storage.save(key(slot, mode, "score"), score)
    }

    /** Back to a new game, after finishing everything. */
    fun clear(mode: String, slot: Int = current) {
        storage.save(key(slot, mode, "walk"), 0)
        storage.save(key(slot, mode, "score"), 0)
    }

    /**
     * Moves progress saved before there were slots (under [oldKey]) into slot 1, once.
     */
    fun migrate(oldKey: String, mode: String) {
        val old = storage.load(oldKey)
        if (old > 0 && !started(mode, 1)) save(mode, old, 0, 1)
        if (old > 0) storage.save(oldKey, 0)
    }

    companion object {
        const val COUNT = 3
    }
}
