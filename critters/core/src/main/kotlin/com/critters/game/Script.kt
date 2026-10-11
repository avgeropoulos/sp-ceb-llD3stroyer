package com.critters.game

enum class Dir(val dx: Int, val dy: Int) {
    DOWN(0, 1), UP(0, -1), LEFT(-1, 0), RIGHT(1, 0);

    val opposite get() = when (this) { DOWN -> UP; UP -> DOWN; LEFT -> RIGHT; RIGHT -> LEFT }
}

/**
 * One step of an event script. Scripts are Kotlin sequences that yield these;
 * the game runs each one (showing text, starting a battle...) and only asks for
 * the next once it has finished, so scripts read top to bottom.
 */
sealed class Cmd {
    class Say(val text: String) : Cmd()
    /** Shows [text] with a YES / NO choice; the answer lands in [Ctx.yes]. */
    class Ask(val text: String) : Cmd()
    /** A menu of choices; the pick lands in [Ctx.choice] (-1 if cancelled). */
    class Choose(val text: String, val options: List<String>) : Cmd()
    /** A trainer battle. With [canLose], losing doesn't black out; [Ctx.won] says how it went. */
    class Fight(val trainer: Trainer, val canLose: Boolean = false, val tune: Tune = Tune.BATTLE) : Cmd()
    class Wild(val species: Int, val level: Int) : Cmd()
    object Heal : Cmd()
    class GiveItem(val item: Item, val count: Int = 1) : Cmd()
    class GiveCritter(val species: Int, val level: Int) : Cmd()
    class Shop(val items: List<Item>) : Cmd()
    /** Walks NPC [npc] ("" = the player) along [path], a string of U / D / L / R. */
    class Walk(val npc: String, val path: String) : Cmd()
    class Face(val npc: String, val dir: Dir) : Cmd()
    class FacePlayer(val npc: String) : Cmd()
    class Exclaim(val npc: String) : Cmd()
    class Music(val tune: Tune) : Cmd()
    class Sound(val sfx: Sfx) : Cmd()
    class Wait(val frames: Int) : Cmd()
    class SetFlag(val flag: String) : Cmd()
    /** Re-reads which NPCs should be shown (after flags change). */
    object Refresh : Cmd()
    class Warp(val map: String, val x: Int, val y: Int, val face: Dir = Dir.DOWN) : Cmd()
    object Credits : Cmd()
}

/** What a running script can see: the game, the NPC it belongs to, and answers from the player. */
class Ctx(val game: Game, val npc: String?) {
    var yes = false
    var choice = -1
    var won = false

    fun flag(f: String) = game.flags.contains(f)
    val party get() = game.party
    val badges get() = game.badges
    val playerName get() = game.playerName
    val rivalStarter get() = game.rivalStarter
}

typealias Script = suspend SequenceScope<Cmd>.(Ctx) -> Unit

fun script(block: Script): Script = block

suspend fun SequenceScope<Cmd>.say(vararg lines: String) {
    for (l in lines) yield(Cmd.Say(l))
}
