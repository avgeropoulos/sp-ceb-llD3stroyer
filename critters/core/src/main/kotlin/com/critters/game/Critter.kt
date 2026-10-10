package com.critters.game

import kotlin.random.Random

class MoveSlot(val move: Move, var pp: Int = move.pp)

/** One critter: a species at a level, with its own HP, moves and status. */
class Critter(var species: Species, level: Int, rng: Random = Random) {
    var level = level
        private set

    /** A little individual variety (0..15) added to every stat, like the original's DVs. */
    var iv = rng.nextInt(16)
    var xp = xpFor(level)
    val moves = species.movesAt(level).map { MoveSlot(it) }.toMutableList()
    var status = Status.OK
    var hp = 0

    init { hp = maxHp }

    val name get() = species.name
    val maxHp get() = (2 * species.hp + iv) * level / 100 + level + 10
    val atk get() = stat(species.atk)
    val def get() = stat(species.def)
    val spd get() = stat(species.spd)
    val fainted get() = hp <= 0

    private fun stat(base: Int) = (2 * base + iv) * level / 100 + 5

    /** Progress through the current level, 0..1, for the XP bar. */
    val xpFrac: Float
        get() {
            if (level >= MAX_LEVEL) return 1f
            val lo = xpFor(level)
            return (xp - lo).toFloat() / (xpFor(level + 1) - lo)
        }

    fun heal() {
        hp = maxHp
        status = Status.OK
        moves.forEach { it.pp = it.move.pp }
    }

    /** Raises the level by one, keeping the same amount of missing HP. Returns new moves to learn. */
    fun levelUp(): List<Move> {
        val oldMax = maxHp
        level++
        if (hp > 0) hp += maxHp - oldMax
        return species.learn.filter { it.first == level }.map { it.second }
    }

    fun knows(m: Move) = moves.any { it.move == m }

    /** Learns [m] if there's a free slot. Returns false when four moves are already known. */
    fun learn(m: Move): Boolean {
        if (knows(m)) return true
        if (moves.size >= 4) return false
        moves += MoveSlot(m)
        return true
    }

    fun replaceMove(slot: Int, m: Move) {
        moves[slot] = MoveSlot(m)
    }

    fun canEvolve() = species.evolveTo != 0 && level >= species.evolveLevel

    /** Turns into the evolved form. Returns any moves the new form learns at this level. */
    fun evolve(): List<Move> {
        val oldMax = maxHp
        species = Dex[species.evolveTo]
        hp += maxHp - oldMax
        return species.learn.filter { it.first == level }.map { it.second }
    }

    fun encode(): String = listOf(
        species.id, level, iv, xp, hp, status.ordinal,
        moves.joinToString("/") { "${it.move.ordinal}.${it.pp}" },
    ).joinToString(":")

    companion object {
        const val MAX_LEVEL = 60

        /** A medium-fast experience curve: level cubed. */
        fun xpFor(level: Int) = level * level * level

        fun decode(s: String): Critter? = try {
            val p = s.split(":")
            Critter(Dex[p[0].toInt()], p[1].toInt()).apply {
                iv = p[2].toInt()
                xp = p[3].toInt()
                status = Status.entries[p[5].toInt()]
                moves.clear()
                if (p[6].isNotEmpty()) {
                    for (m in p[6].split("/")) {
                        val (mi, pp) = m.split(".")
                        moves += MoveSlot(Move.entries[mi.toInt()], pp.toInt())
                    }
                }
                hp = p[4].toInt().coerceIn(0, maxHp)
            }
        } catch (_: Exception) {
            null
        }
    }
}
