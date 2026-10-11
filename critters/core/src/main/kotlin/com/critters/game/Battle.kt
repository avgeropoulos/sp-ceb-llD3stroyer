package com.critters.game

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.random.Random

class Bag {
    val items = LinkedHashMap<Item, Int>()

    fun count(i: Item) = items[i] ?: 0

    fun add(i: Item, n: Int = 1) {
        items[i] = count(i) + n
    }

    fun take(i: Item): Boolean {
        val n = count(i)
        if (n <= 0) return false
        if (n == 1) items.remove(i) else items[i] = n - 1
        return true
    }

    fun list(): List<Pair<Item, Int>> = Item.entries.filter { count(it) > 0 }.map { it to count(it) }
}

/** Uses a healing item on [c]. Returns the message to show, or null if it would have no effect. */
fun applyItem(item: Item, c: Critter): String? = when (item) {
    Item.POTION, Item.SUPER_POTION -> {
        if (c.fainted || c.hp >= c.maxHp) null else {
            val before = c.hp
            c.hp = min(c.maxHp, c.hp + if (item == Item.POTION) 20 else 60)
            "${c.name} RECOVERED ${c.hp - before} HP!"
        }
    }
    Item.FULL_HEAL -> {
        if (c.fainted || c.status == Status.OK) null else {
            c.status = Status.OK
            "${c.name} IS HEALTHY AGAIN!"
        }
    }
    Item.REVIVE -> {
        if (!c.fainted) null else {
            c.hp = c.maxHp / 2
            c.status = Status.OK
            "${c.name} IS REVIVED!"
        }
    }
    else -> null
}

/** A trainer: who they are and what they bring. [party] is (species id, level). */
class Trainer(
    val title: String,
    val name: String,
    val party: List<Pair<Int, Int>>,
    val prize: Int,
    val lose: String,
    val potions: Int = 0,
    val boss: Boolean = false,
) {
    val label get() = if (title.isEmpty()) name else "$title $name"
}

/** Things that happen in battle, in order, for the battle screen to animate. */
sealed class Ev {
    class Text(val s: String) : Ev()
    class Send(val foe: Boolean, val c: Critter) : Ev() {
        val hp = c.hp
        val maxHp = c.maxHp
        val level = c.level
        val status = c.status
        val species = c.species
    }
    class Recall(val foe: Boolean) : Ev()
    /** [foe] is the side that got hit. */
    class Hit(val foe: Boolean, val mult: Float, val type: Type = Type.NORMAL) : Ev()
    class Hp(val foe: Boolean, val hp: Int) : Ev()
    class Stat(val foe: Boolean, val status: Status) : Ev()
    class Faint(val foe: Boolean) : Ev()
    class Xp(val c: Critter, val frac: Float, val reset: Boolean = false) : Ev()
    class Level(val c: Critter, val level: Int, val hp: Int, val maxHp: Int) : Ev()
    /** [c] wants to learn [move] but already knows four moves; the screen asks the player. */
    class Learn(val c: Critter, val move: Move) : Ev()
    class Throw(val shakes: Int, val caught: Boolean) : Ev()
    class Heal(val foe: Boolean) : Ev()
    object NeedSwitch : Ev()
    class End(val result: Battle.Result) : Ev()
}

sealed class Action {
    class Fight(val slot: Int) : Action()
    class Switch(val index: Int) : Action()
    class UseItem(val item: Item, val index: Int) : Action()
    class Throw(val item: Item) : Action()
    object Run : Action()
}

/** The rules of a battle. It never waits: every call returns the events it caused. */
class Battle(
    val party: MutableList<Critter>,
    val foes: List<Critter>,
    val trainer: Trainer?,
    val bag: Bag,
    private val rng: Random = Random,
) {
    enum class Result { WIN, LOSE, RUN, CAUGHT }

    class Side {
        var atk = 0
        var def = 0
        var spd = 0
        var sleep = 0
        fun reset() { atk = 0; def = 0; spd = 0 }
    }

    var mine = party.indexOfFirst { !it.fainted }.coerceAtLeast(0)
        private set
    var theirs = 0
        private set
    val me get() = party[mine]
    val foe get() = foes[theirs]
    private val meSide = Side()
    private val foeSide = Side()
    private val participants = LinkedHashSet<Critter>()
    var result: Result? = null
        private set
    var caught: Critter? = null
        private set
    var prizeMoney = 0
        private set
    private var escapes = 0
    private var potionsLeft = trainer?.potions ?: 0
    val wild get() = trainer == null

    private fun side(foe: Boolean) = if (foe) foeSide else meSide
    private fun crit(foe: Boolean) = if (foe) this.foe else me

    /** Name as the text box says it: "WILD NIBBIT", "FOE PEBBLIT" or just your critter's name. */
    fun nm(foe: Boolean) = if (foe) (if (wild) "WILD " else "FOE ") + this.foe.name else me.name

    fun start(): List<Ev> {
        val ev = ArrayList<Ev>()
        participants += me
        if (wild) {
            ev += Ev.Send(true, foe)
            ev += Ev.Text("A WILD ${foe.name} APPEARED!")
        } else {
            ev += Ev.Text("${trainer!!.label} WANTS TO BATTLE!")
            ev += Ev.Send(true, foe)
            ev += Ev.Text("${trainer.label} SENT OUT ${foe.name}!")
        }
        ev += Ev.Send(false, me)
        ev += Ev.Text("GO! ${me.name}!")
        return ev
    }

    fun turn(a: Action): List<Ev> {
        val ev = ArrayList<Ev>()
        if (result != null) return ev
        val foeHeals = potionsLeft > 0 && foe.hp < foe.maxHp / 4 && rng.nextInt(2) == 0
        val foeSlot = if (foeHeals) null else aiMove()
        when (a) {
            is Action.Run -> {
                if (!wild) {
                    ev += Ev.Text("NO! THERE'S NO RUNNING FROM A TRAINER BATTLE!")
                    return ev
                }
                escapes++
                val odds = me.spd * 128 / max(1, foe.spd) + 30 * (escapes - 1)
                if (odds > 255 || rng.nextInt(256) < odds) {
                    ev += Ev.Text("GOT AWAY SAFELY!")
                    result = Result.RUN
                    ev += Ev.End(Result.RUN)
                    return ev
                }
                ev += Ev.Text("CAN'T ESCAPE!")
            }
            is Action.Switch -> {
                ev += Ev.Recall(false)
                ev += Ev.Text("${me.name}, COME BACK!")
                mine = a.index
                meSide.reset()
                participants += me
                ev += Ev.Send(false, me)
                ev += Ev.Text("GO! ${me.name}!")
            }
            is Action.UseItem -> {
                val c = party[a.index]
                bag.take(a.item)
                ev += Ev.Text("YOU USED A ${a.item.label}!")
                val msg = applyItem(a.item, c)
                if (msg != null) {
                    if (c === me) {
                        ev += Ev.Heal(false)
                        ev += Ev.Hp(false, c.hp)
                        ev += Ev.Stat(false, c.status)
                    }
                    ev += Ev.Text(msg)
                }
            }
            is Action.Throw -> {
                bag.take(a.item)
                ev += Ev.Text("YOU THREW A ${a.item.label}!")
                if (!wild) {
                    ev += Ev.Throw(-1, false)
                    ev += Ev.Text("THE TRAINER BLOCKED IT! DON'T BE A THIEF!")
                } else if (capture(a.item, ev)) {
                    return ev
                }
            }
            is Action.Fight -> {
                val mySlot = me.moves.getOrNull(a.slot)?.takeIf { it.pp > 0 }
                if (foeHeals) {
                    foeHeal(ev)
                    attack(false, mySlot, ev)
                } else if (meFirst(mySlot, foeSlot)) {
                    attack(false, mySlot, ev)
                    if (!foe.fainted && !me.fainted) attack(true, foeSlot, ev)
                } else {
                    attack(true, foeSlot, ev)
                    if (!foe.fainted && !me.fainted) attack(false, mySlot, ev)
                }
                endTurn(ev)
                return ev
            }
        }
        if (foeHeals) foeHeal(ev) else attack(true, foeSlot, ev)
        endTurn(ev)
        return ev
    }

    /** Sends in a replacement after the active critter fainted. Costs no turn. */
    fun replace(index: Int): List<Ev> {
        mine = index
        meSide.reset()
        participants += me
        return listOf(Ev.Send(false, me), Ev.Text("GO! ${me.name}!"))
    }

    // ---------------------------------------------------------------- turn order and moves

    private fun speed(foe: Boolean): Float {
        val c = crit(foe)
        return c.spd * stageMul(side(foe).spd) * if (c.status == Status.PARALYZE) 0.25f else 1f
    }

    private fun meFirst(mine: MoveSlot?, theirs: MoveSlot?): Boolean {
        val pm = if (mine?.move?.effect == Effect.PRIORITY) 1 else 0
        val pt = if (theirs?.move?.effect == Effect.PRIORITY) 1 else 0
        if (pm != pt) return pm > pt
        val sm = speed(false)
        val st = speed(true)
        return if (sm == st) rng.nextBoolean() else sm > st
    }

    private fun stageMul(s: Int) = if (s >= 0) (2 + s) / 2f else 2f / (2 - s)

    private fun typeMult(m: Move, target: Critter) = target.species.types.fold(1f) { acc, t -> acc * Type.mult(m.type, t) }

    private fun aiMove(): MoveSlot? {
        val usable = foe.moves.filter { it.pp > 0 }
        if (usable.isEmpty()) return null
        if (wild) return usable[rng.nextInt(usable.size)]
        val scores = usable.map { s ->
            val m = s.move
            when {
                m.power > 0 -> m.power * typeMult(m, me) * (if (m.type in foe.species.types) 1.5f else 1f) * m.acc / 100f
                m.effect in listOf(Effect.SLEEP, Effect.PARALYZE, Effect.POISON, Effect.BURN) ->
                    if (me.status == Status.OK) 45f else 0f
                else -> 18f
            }
        }
        if (rng.nextInt(10) < 7) return usable[scores.indices.maxBy { scores[it] }]
        val total = scores.sum().coerceAtLeast(1f)
        var r = rng.nextFloat() * total
        for (i in usable.indices) {
            r -= scores[i]
            if (r <= 0f) return usable[i]
        }
        return usable.last()
    }

    private fun foeHeal(ev: MutableList<Ev>) {
        potionsLeft--
        foe.hp = min(foe.maxHp, foe.hp + 60)
        ev += Ev.Text("${trainer!!.label} USED A SUPER POTION!")
        ev += Ev.Heal(true)
        ev += Ev.Hp(true, foe.hp)
    }

    private fun attack(byFoe: Boolean, slot: MoveSlot?, ev: MutableList<Ev>) {
        val user = crit(byFoe)
        val target = crit(!byFoe)
        val us = side(byFoe)
        if (user.fainted) return
        when (user.status) {
            Status.SLEEP -> {
                us.sleep--
                if (us.sleep > 0) {
                    ev += Ev.Text("${nm(byFoe)} IS FAST ASLEEP!")
                    return
                }
                user.status = Status.OK
                ev += Ev.Stat(byFoe, Status.OK)
                ev += Ev.Text("${nm(byFoe)} WOKE UP!")
            }
            Status.PARALYZE -> if (rng.nextInt(4) == 0) {
                ev += Ev.Text("${nm(byFoe)} IS FULLY PARALYZED!")
                return
            }
            else -> {}
        }
        val move = slot?.move ?: Move.STRUGGLE
        if (slot != null) slot.pp--
        ev += Ev.Text("${nm(byFoe)} USED ${move.label}!")
        if (rng.nextInt(100) >= move.acc) {
            ev += Ev.Text("${nm(byFoe)}'S ATTACK MISSED!")
            return
        }
        if (move.power == 0) {
            applyEffect(move.effect, byFoe, true, ev)
            return
        }
        val mult = typeMult(move, target)
        if (mult == 0f) {
            ev += Ev.Text("IT DOESN'T AFFECT ${nm(!byFoe)}...")
            return
        }
        val crit = rng.nextInt(if (move.effect == Effect.HIGH_CRIT) 8 else 16) == 0
        val dmg = damage(user, target, move, us, side(!byFoe), mult, crit)
        target.hp = max(0, target.hp - dmg)
        ev += Ev.Hit(!byFoe, mult, move.type)
        ev += Ev.Hp(!byFoe, target.hp)
        if (crit) ev += Ev.Text("A CRITICAL HIT!")
        if (mult > 1f) ev += Ev.Text("IT'S SUPER EFFECTIVE!")
        if (mult < 1f) ev += Ev.Text("IT'S NOT VERY EFFECTIVE...")
        when (move.effect) {
            Effect.DRAIN -> if (user.hp < user.maxHp) {
                user.hp = min(user.maxHp, user.hp + max(1, dmg / 2))
                ev += Ev.Hp(byFoe, user.hp)
                ev += Ev.Text("${nm(!byFoe)} HAD ITS ENERGY DRAINED!")
            }
            Effect.RECOIL -> {
                user.hp = max(0, user.hp - max(1, dmg / 4))
                ev += Ev.Hp(byFoe, user.hp)
                ev += Ev.Text("${nm(byFoe)} IS HIT WITH RECOIL!")
            }
            Effect.NONE, Effect.HIGH_CRIT, Effect.PRIORITY -> {}
            else -> if (!target.fainted && rng.nextInt(100) < move.chance) applyEffect(move.effect, byFoe, false, ev)
        }
    }

    fun damage(user: Critter, target: Critter, move: Move, us: Side, them: Side, mult: Float, crit: Boolean): Int {
        val a = user.atk * stageMul(us.atk) * if (user.status == Status.BURN) 0.5f else 1f
        val d = target.def * stageMul(them.def)
        var dmg = ((2f * user.level / 5f + 2f) * move.power * a / d) / 50f + 2f
        if (move.type in user.species.types) dmg *= 1.5f
        dmg *= mult
        if (crit) dmg *= 1.5f
        dmg *= (217 + rng.nextInt(39)) / 255f
        return max(1, dmg.toInt())
    }

    /** [primary] is true for status moves, which say "but it failed" when nothing happens. */
    private fun applyEffect(e: Effect, byFoe: Boolean, primary: Boolean, ev: MutableList<Ev>) {
        val target = crit(!byFoe)
        val them = side(!byFoe)
        val us = side(byFoe)
        fun failed() { if (primary) ev += Ev.Text("BUT IT FAILED!") }
        fun inflict(s: Status, immuneType: Type?, msg: String) {
            if (target.status != Status.OK || (immuneType != null && immuneType in target.species.types)) return failed()
            target.status = s
            if (s == Status.SLEEP) them.sleep = 2 + rng.nextInt(3)
            ev += Ev.Stat(!byFoe, s)
            ev += Ev.Text("${nm(!byFoe)} $msg")
        }
        fun stage(self: Boolean, stat: String, delta: Int) {
            val sd = if (self) us else them
            val who = if (self) nm(byFoe) else nm(!byFoe)
            val cur = when (stat) { "ATTACK" -> sd.atk; "DEFENSE" -> sd.def; else -> sd.spd }
            val next = (cur + delta).coerceIn(-6, 6)
            if (next == cur) {
                if (primary) ev += Ev.Text("NOTHING HAPPENED!")
                return
            }
            when (stat) { "ATTACK" -> sd.atk = next; "DEFENSE" -> sd.def = next; else -> sd.spd = next }
            val how = when { delta >= 2 -> "ROSE SHARPLY!"; delta > 0 -> "ROSE!"; else -> "FELL!" }
            ev += Ev.Text("$who'S $stat $how")
        }
        when (e) {
            Effect.BURN -> inflict(Status.BURN, Type.FIRE, "WAS BURNED!")
            Effect.POISON -> inflict(Status.POISON, null, "WAS POISONED!")
            Effect.PARALYZE -> inflict(Status.PARALYZE, Type.ELECTRIC, "IS PARALYZED! IT MAY NOT ATTACK!")
            Effect.SLEEP -> inflict(Status.SLEEP, null, "FELL ASLEEP!")
            Effect.ATK_DOWN -> stage(false, "ATTACK", -1)
            Effect.DEF_DOWN -> stage(false, "DEFENSE", -1)
            Effect.SPD_DOWN -> stage(false, "SPEED", -1)
            Effect.ATK_UP -> stage(true, "ATTACK", 1)
            Effect.DEF_UP -> stage(true, "DEFENSE", 1)
            Effect.SPD_UP -> stage(true, "SPEED", 2)
            else -> failed()
        }
    }

    private fun endTurn(ev: MutableList<Ev>) {
        for (f in listOf(false, true)) {
            val c = crit(f)
            if (c.fainted) continue
            val (div, what) = when (c.status) {
                Status.BURN -> 16 to "ITS BURN"
                Status.POISON -> 8 to "POISON"
                else -> continue
            }
            c.hp = max(0, c.hp - max(1, c.maxHp / div))
            ev += Ev.Hit(f, 1f, if (c.status == Status.BURN) Type.FIRE else Type.BUG)
            ev += Ev.Hp(f, c.hp)
            ev += Ev.Text("${nm(f)} IS HURT BY $what!")
        }
        resolveFaints(ev)
    }

    private fun resolveFaints(ev: MutableList<Ev>) {
        if (foe.fainted) {
            ev += Ev.Faint(true)
            ev += Ev.Text("${nm(true)} FAINTED!")
            val earners = participants.filter { !it.fainted && it in party }
            val total = foe.species.baseExp * foe.level / 6 * (if (wild) 2 else 3) / 2
            for (p in earners) gainXp(p, max(1, total / earners.size), ev)
            participants.clear()
            if (!me.fainted) participants += me
            val next = foes.indexOfFirst { !it.fainted }
            if (trainer != null && next >= 0) {
                theirs = next
                foeSide.reset()
                ev += Ev.Text("${trainer.label} IS ABOUT TO SEND OUT ${foe.name}!")
                ev += Ev.Send(true, foe)
                ev += Ev.Text("${trainer.label} SENT OUT ${foe.name}!")
            } else {
                if (trainer != null) {
                    prizeMoney = trainer.prize * foes.maxOf { it.level }
                    ev += Ev.Text("YOU DEFEATED ${trainer.label}!")
                    ev += Ev.Text(trainer.lose)
                    ev += Ev.Text("YOU GOT $$prizeMoney FOR WINNING!")
                }
                result = Result.WIN
                if (me.fainted) {
                    ev += Ev.Faint(false)
                    ev += Ev.Text("${me.name} FAINTED!")
                }
                ev += Ev.End(Result.WIN)
                return
            }
        }
        if (me.fainted) {
            ev += Ev.Faint(false)
            ev += Ev.Text("${me.name} FAINTED!")
            participants.remove(me)
            if (party.any { !it.fainted }) {
                ev += Ev.NeedSwitch
            } else {
                ev += Ev.Text("YOU ARE OUT OF USABLE CRITTERS!")
                ev += Ev.Text("YOU BLACKED OUT!")
                result = Result.LOSE
                ev += Ev.End(Result.LOSE)
            }
        }
    }

    private fun gainXp(c: Critter, amount: Int, ev: MutableList<Ev>) {
        if (c.level >= Critter.MAX_LEVEL) return
        c.xp += amount
        ev += Ev.Text("${c.name} GAINED $amount XP!")
        while (c.level < Critter.MAX_LEVEL && c.xp >= Critter.xpFor(c.level + 1)) {
            ev += Ev.Xp(c, 1f)
            val newMoves = c.levelUp()
            ev += Ev.Level(c, c.level, c.hp, c.maxHp)
            ev += Ev.Xp(c, 0f, reset = true)
            ev += Ev.Text("${c.name} GREW TO LEVEL ${c.level}!")
            for (m in newMoves) {
                if (c.knows(m)) continue
                if (c.learn(m)) ev += Ev.Text("${c.name} LEARNED ${m.label}!") else ev += Ev.Learn(c, m)
            }
        }
        ev += Ev.Xp(c, c.xpFrac)
    }

    private fun capture(ball: Item, ev: MutableList<Ev>): Boolean {
        val c = foe
        val statusBonus = when (c.status) { Status.SLEEP -> 2f; Status.OK -> 1f; else -> 1.5f }
        val a = (3f * c.maxHp - 2f * c.hp) * c.species.catchRate * ball.ball / (3f * c.maxHp) * statusBonus
        var shakes = 0
        if (a >= 255f) {
            shakes = 4
        } else {
            val b = 1048560.0 / sqrt(sqrt(16711680.0 / a.coerceAtLeast(1f)))
            while (shakes < 4 && rng.nextInt(65536) < b) shakes++
        }
        val ok = shakes == 4
        ev += Ev.Throw(min(shakes, 3), ok)
        if (ok) {
            ev += Ev.Text("GOTCHA! ${c.name} WAS CAUGHT!")
            caught = c
            result = Result.CAUGHT
            ev += Ev.End(Result.CAUGHT)
            return true
        }
        ev += Ev.Text(
            listOf("OH NO! IT BROKE FREE!", "AWW! IT APPEARED TO BE CAUGHT!", "AARGH! ALMOST HAD IT!", "SHOOT! IT WAS SO CLOSE TOO!")[shakes]
        )
        return false
    }
}
