package com.critters.game

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** The battle screen. It plays [Battle]'s events one at a time and asks the player what to do. */
class BattleScene(private val g: Game, val battle: Battle, private val onFinish: (Battle) -> Unit) {

    /** What one side of the screen currently shows (it lags behind the battle while animating). */
    private class Side {
        var critter: Critter? = null
        var species: Species? = null
        var name = ""
        var level = 0
        var hp = 0f
        var targetHp = 0
        var maxHp = 1
        var status = Status.OK
        var visible = false
        var xp = 0f
        var targetXp = 0f
        var scale = 1f
        var sink = 0
        var blink = false
        var dx = 0

        fun set(e: Ev.Send) {
            critter = e.c
            species = e.species
            name = e.c.name
            level = e.level
            hp = e.hp.toFloat()
            targetHp = e.hp
            maxHp = e.maxHp
            status = e.status
            visible = true
            sink = 0
            scale = 1f
            xp = e.c.xpFrac
            targetXp = xp
        }
    }

    private class Anim(val len: Int, val tick: (Int) -> Unit = {}, val end: () -> Unit = {}) {
        var t = 0
    }

    private enum class Mode { EVENTS, ACTION, MOVES }

    private val foe = Side()
    private val me = Side()
    private val queue = ArrayDeque<Ev>()
    private var mode = Mode.EVENTS
    private var anim: Anim? = null
    private var text: String? = null
    private var shown = 0
    private var textWait = 0
    private var actionCursor = 0
    private var moveCursor = 0
    private var introT = 0
    private var finished = false
    private var finishWait = 0
    private var firstFoeSend = true
    private var t = 0

    // Capsule animation state.
    private var ballX = -100
    private var ballY = -100
    private var ballVisible = false
    private var stars = 0
    private var hitType: Type? = null
    private var hitFoe = true
    private var hitT = 0
    private var healT = 0

    init {
        queue.addAll(battle.start())
    }

    fun update() {
        t++
        if (introT < 48) {
            introT++
            return
        }
        stepBars()
        if (hitT > 0) hitT--
        if (healT > 0) healT--
        anim?.let { a ->
            a.t++
            a.tick(a.t)
            if (a.t >= a.len) {
                anim = null
                a.end()
            }
            return
        }
        if (text != null) {
            updateText()
            return
        }
        if (barsMoving()) return
        if (queue.isNotEmpty()) {
            process(queue.removeFirst())
            return
        }
        if (battle.result != null) {
            if (!finished) {
                finished = true
                finishWait = 30
            }
            if (--finishWait == 0) onFinish(battle)
            return
        }
        when (mode) {
            Mode.EVENTS -> { mode = Mode.ACTION; g.swallowInput() }
            Mode.ACTION -> updateAction()
            Mode.MOVES -> updateMoves()
        }
    }

    private fun updateText() {
        val s = text ?: return
        if (shown < s.length) {
            shown += if (g.down(Btn.A) || g.down(Btn.B)) 3 else 1
            return
        }
        textWait++
        if (textWait > 50 || (textWait > 6 && (g.pressed(Btn.A) || g.pressed(Btn.B)))) {
            text = null
        }
    }

    private fun stepBars() {
        for (s in listOf(foe, me)) {
            val d = s.targetHp - s.hp
            if (abs(d) > 0.01f) {
                val step = maxOf(0.25f, s.maxHp / 48f)
                s.hp = if (abs(d) <= step) s.targetHp.toFloat() else s.hp + step * if (d > 0) 1 else -1
            }
        }
        val dx = me.targetXp - me.xp
        if (abs(dx) > 0.001f) me.xp = if (abs(dx) < 0.02f) me.targetXp else me.xp + 0.02f * if (dx > 0) 1 else -1
    }

    private fun barsMoving() = abs(foe.hp - foe.targetHp) > 0.01f || abs(me.hp - me.targetHp) > 0.01f || abs(me.xp - me.targetXp) > 0.001f

    private fun side(isFoe: Boolean) = if (isFoe) foe else me

    private fun say(s: String) {
        text = g.text(s)
        shown = 0
        textWait = 0
    }

    private fun process(e: Ev) {
        when (e) {
            is Ev.Text -> say(e.s)
            is Ev.Send -> {
                val s = side(e.foe)
                s.set(e)
                if (e.foe) g.seen += e.species.id
                if (e.foe && firstFoeSend && battle.wild) {
                    firstFoeSend = false
                    g.audio.cry(e.species.id)
                    return
                }
                firstFoeSend = false
                s.scale = 0f
                anim = Anim(16, { s.scale = it / 16f }, { s.scale = 1f; g.audio.cry(e.species.id) })
            }
            is Ev.Recall -> {
                val s = side(e.foe)
                anim = Anim(12, { s.scale = 1f - it / 12f }, { s.visible = false; s.scale = 1f })
            }
            is Ev.Hit -> {
                val s = side(e.foe)
                g.sfx(if (e.mult > 1f) Sfx.HIT_SUPER else if (e.mult < 1f) Sfx.HIT_WEAK else Sfx.HIT)
                hitType = e.type
                hitFoe = e.foe
                hitT = 18
                anim = Anim(26, {
                    s.blink = (it / 4) % 2 == 1
                    s.dx = if (!e.foe && it < 12) (if ((it / 2) % 2 == 0) 2 else -2) else 0
                }, { s.blink = false; s.dx = 0 })
            }
            is Ev.Hp -> side(e.foe).targetHp = e.hp
            is Ev.Stat -> side(e.foe).status = e.status
            is Ev.Faint -> {
                val s = side(e.foe)
                s.species?.let { g.audio.cry(it.id) }
                if (e.foe && battle.result == Battle.Result.WIN) g.playMusic(Tune.VICTORY)
                anim = Anim(24, { s.sink = it * 2 }, { s.visible = false; g.sfx(Sfx.FAINT) })
            }
            is Ev.Xp -> if (me.critter === e.c) {
                if (e.reset) { me.xp = 0f; me.targetXp = 0f } else me.targetXp = e.frac
            }
            is Ev.Level -> if (me.critter === e.c) {
                me.level = e.level
                me.maxHp = e.maxHp
                me.hp = e.hp.toFloat()
                me.targetHp = e.hp
                g.sfx(Sfx.LEVEL_UP)
            } else g.sfx(Sfx.LEVEL_UP)
            is Ev.Learn -> g.learnFlow(e.c, e.move) {}
            is Ev.Throw -> throwAnim(e)
            is Ev.Heal -> {
                g.sfx(Sfx.HEAL)
                healT = 40
                anim = Anim(40)
            }
            Ev.NeedSwitch -> g.push(PartyScreen(PartyScreen.Mode.FORCED, "BRING OUT WHICH CRITTER?") { i ->
                if (i >= 0) queue.addAll(battle.replace(i))
            })
            is Ev.End -> if (e.result == Battle.Result.RUN) g.sfx(Sfx.RUN)
        }
    }

    private fun throwAnim(e: Ev.Throw) {
        g.sfx(Sfx.THROW)
        val sx = 40; val sy = 80; val tx = 124; val ty = 30
        val arc = 24
        if (e.shakes < 0) {
            // Blocked: the capsule bounces off.
            anim = Anim(arc + 16, { k ->
                ballVisible = true
                if (k <= arc) {
                    val f = k / arc.toFloat()
                    ballX = (sx + (tx - sx) * f).toInt(); ballY = (sy + (ty - sy) * f - 40 * sin(f * Math.PI).toFloat()).toInt()
                } else {
                    ballX += 3; ballY += (k - arc) / 2
                }
            }, { ballVisible = false })
            return
        }
        val absorb = 14
        val drop = 10
        val shakeLen = 30
        val total = arc + absorb + drop + e.shakes * shakeLen + 24
        anim = Anim(total, { k ->
            ballVisible = true
            when {
                k <= arc -> {
                    val f = k / arc.toFloat()
                    ballX = (sx + (tx - sx) * f).toInt()
                    ballY = (sy + (ty - sy) * f - 40 * sin(f * Math.PI).toFloat()).toInt()
                }
                k <= arc + absorb -> foe.scale = 1f - (k - arc) / absorb.toFloat()
                k <= arc + absorb + drop -> {
                    foe.visible = false
                    ballY = ty + (k - arc - absorb) * 2
                }
                k <= arc + absorb + drop + e.shakes * shakeLen -> {
                    val kk = k - arc - absorb - drop
                    if (kk % shakeLen == 1) g.sfx(Sfx.SHAKE)
                    val p = kk % shakeLen
                    ballX = tx + if (p < 12) (sin(p / 12f * Math.PI * 2).toFloat() * 3).toInt() else 0
                }
                else -> {
                    val kk = k - (arc + absorb + drop + e.shakes * shakeLen)
                    ballX = tx
                    if (e.caught) {
                        if (kk == 1) g.sfx(Sfx.CAUGHT)
                        stars = kk
                    } else {
                        ballVisible = false
                        foe.visible = true
                        foe.scale = (kk / 12f).coerceAtMost(1f)
                    }
                }
            }
        }, {
            stars = 0
            if (!e.caught) {
                ballVisible = false
                foe.visible = true
                foe.scale = 1f
            }
        })
    }

    // ---------------------------------------------------------------- choosing actions

    private fun send(a: Action) {
        mode = Mode.EVENTS
        queue.addAll(battle.turn(a))
    }

    private fun updateAction() {
        if (g.pressed(Btn.LEFT) || g.pressed(Btn.RIGHT)) { actionCursor = actionCursor xor 1; g.sfx(Sfx.SELECT) }
        if (g.pressed(Btn.UP) || g.pressed(Btn.DOWN)) { actionCursor = actionCursor xor 2; g.sfx(Sfx.SELECT) }
        if (!g.pressed(Btn.A)) return
        g.sfx(Sfx.SELECT)
        when (actionCursor) {
            0 -> {
                if (battle.me.moves.none { it.pp > 0 }) {
                    send(Action.Fight(-1))
                } else {
                    mode = Mode.MOVES
                    moveCursor = moveCursor.coerceAtMost(battle.me.moves.lastIndex)
                }
            }
            1 -> g.push(PartyScreen(PartyScreen.Mode.BATTLE) { i -> if (i >= 0) send(Action.Switch(i)) })
            2 -> g.push(BagScreen(inBattle = true) { item, i ->
                when {
                    i == -2 -> {}
                    i == -1 -> send(Action.Throw(item))
                    else -> send(Action.UseItem(item, i))
                }
            })
            3 -> send(Action.Run)
        }
    }

    private fun updateMoves() {
        val moves = battle.me.moves
        if (g.repeat(Btn.UP)) { moveCursor = (moveCursor - 1 + moves.size) % moves.size; g.sfx(Sfx.SELECT) }
        if (g.repeat(Btn.DOWN)) { moveCursor = (moveCursor + 1) % moves.size; g.sfx(Sfx.SELECT) }
        if (g.pressed(Btn.B)) { mode = Mode.ACTION; return }
        if (g.pressed(Btn.A)) {
            g.sfx(Sfx.SELECT)
            if (moves[moveCursor].pp <= 0) g.push(Dialog("THERE'S NO PP LEFT FOR THIS MOVE!") {})
            else send(Action.Fight(moveCursor))
        }
    }

    // ---------------------------------------------------------------- drawing

    private fun ellipse(s: Screen, cx: Int, cy: Int, rx: Int, ry: Int, c: Int) {
        for (y in -ry..ry) {
            val half = (rx * kotlin.math.sqrt(1.0 - (y.toDouble() / ry) * (y.toDouble() / ry))).toInt()
            s.rect(cx - half, cy + y, half * 2, 1, c)
        }
    }

    fun draw(s: Screen) {
        s.fill(PAPER)
        val slide = ((48 - introT) * 4).coerceAtLeast(0)
        // Ground patches the critters stand on.
        ellipse(s, 128 - slide, 46, 28, 6, 0xFFD8E8C0.toInt())
        ellipse(s, 36 + slide, 92, 32, 6, 0xFFD8E8C0.toInt())

        // Foe side
        val foeX = 104 - slide
        if (foe.visible && foe.species != null && !foe.blink) {
            val sp = CritterArt.front(foe.species!!.id)
            if (foe.sink > 0) s.blitSink(sp, foeX, 50, foe.sink)
            else if (foe.scale < 1f) {
                val w = (48 * foe.scale).toInt()
                s.blitScaled(sp, foeX + 24 - w / 2, 50 - w, w, w)
            } else s.blit(sp, foeX, 2)
        } else if (!foe.visible && battle.trainer != null && firstFoeSend) {
            s.blitScaled(Art.person(trainerLook()).down, foeX, 2, 48, 48)
        }
        if (foe.visible || foe.sink > 0) drawFoePanel(s)

        // Player side
        val meX = 12 + slide + me.dx
        if (me.visible && me.species != null && !me.blink) {
            val sp = CritterArt.back(me.species!!.id)
            if (me.sink > 0) s.blitSink(sp, meX, 96, me.sink)
            else if (me.scale < 1f) {
                val w = (48 * me.scale).toInt()
                s.blitScaled(sp, meX + 24 - w / 2, 96 - w, w, w)
            } else s.blit(sp, meX, 48)
        } else if (!me.visible && me.critter == null) {
            s.blitScaled(Art.person(Art.Look.PLAYER).up, meX, 48, 48, 48)
        }
        if (me.visible) drawMyPanel(s)

        // Move effect: little bursts in the move's color.
        val ht = hitType
        if (hitT > 0 && ht != null) {
            val target = if (hitFoe) 128 to 26 else 36 to 72
            val r = (18 - hitT) * 2
            for (k in 0 until 6) {
                val a = k * Math.PI / 3 + t * 0.05
                val x = target.first + (cos(a) * r).toInt()
                val y = target.second + (sin(a) * r).toInt()
                s.rect(x - 2, y - 2, 4, 4, INK)
                s.rect(x - 1, y - 1, 2, 2, ht.color)
            }
        }
        if (healT > 0) {
            for (k in 0 until 5) {
                val x = 20 + k * 8 + (healT % 4)
                val y = 90 - (40 - healT) - (k % 2) * 6
                s.rect(x, y, 2, 2, 0xFF58D058.toInt())
            }
        }

        if (ballVisible) s.blit(Art.capsule, ballX - 4, ballY - 4)
        if (stars > 0) {
            for (k in 0 until 3) {
                val x = ballX - 12 + k * 12
                val y = ballY - 8 - (stars / 3).coerceAtMost(8)
                Font.draw(s, '*', x, y, 0xFFF8C020.toInt())
            }
        }

        // Text box and menus
        s.box(0, 96, SW, 48)
        val txt = text
        if (txt != null) {
            var left = shown
            wrap(txt).take(2).forEachIndexed { i, l ->
                s.text(l.take(left.coerceAtLeast(0)), 8, 106 + i * 16)
                left -= l.length + 1
            }
        } else if (queue.isEmpty() && anim == null && battle.result == null && !barsMoving() && introT >= 48) {
            when (mode) {
                Mode.ACTION -> drawActions(s)
                Mode.MOVES -> drawMoves(s)
                else -> {}
            }
        }
    }

    private fun trainerLook(): Art.Look {
        val t = battle.trainer ?: return Art.Look.BOY
        return when {
            t.name == World.RIVAL -> Art.Look.RIVAL
            t.name == "GRANITA" -> Art.Look.GRANITA
            t.name == "MARINA" -> Art.Look.MARINA
            t.name == "VOLTA" -> Art.Look.VOLTA
            t.title == "HIKER" -> Art.Look.HIKER
            t.title == "BUG KID" -> Art.Look.BUGKID
            t.title == "SWIMMER" -> Art.Look.SWIMMER
            t.title == "MEDIUM" -> Art.Look.OLDMAN
            t.title == "ENGINEER" -> Art.Look.CLERK
            t.title in listOf("LASS", "PICNICKER") || t.name in listOf("STELLA") -> Art.Look.GIRL
            else -> Art.Look.BOY
        }
    }

    private fun drawFoePanel(s: Screen) {
        s.text(foe.name, 6, 3)
        if (foe.status != Status.OK) s.text(foe.status.tag, 6, 12, 0xFFC83030.toInt())
        s.textRight(":L${foe.level}", 78, 12)
        s.text("HP", 6, 20)
        s.hpBar(20, 21, 54, foe.hp / foe.maxHp)
        s.rect(2, 14, 1, 15, INK)
        s.rect(2, 28, 76, 1, INK)
        if (battle.wild && foe.species?.id in g.caught) s.blit(Art.capsule, 70, 2)
    }

    private fun drawMyPanel(s: Screen) {
        s.text(me.name, 84, 54)
        if (me.status != Status.OK) s.text(me.status.tag, 84, 63, 0xFFC83030.toInt())
        s.textRight(":L${me.level}", 158, 63)
        s.text("HP", 84, 72)
        s.hpBar(98, 73, 56, me.hp / me.maxHp)
        s.textRight("${me.hp.toInt()}/${me.maxHp}", 158, 81)
        s.rect(98, 91, 58, 3, INK)
        s.rect(99, 92, (56 * me.xp.coerceIn(0f, 1f)).toInt(), 1, 0xFF58A8F8.toInt())
        s.rect(157, 62, 1, 32, INK)
    }

    private fun drawActions(s: Screen) {
        s.text("WHAT WILL", 8, 102)
        s.text(battle.me.name, 8, 114)
        s.text("DO?", 8, 126)
        s.box(70, 96, 90, 48)
        val labels = listOf("FIGHT", "CRIT", "BAG", "RUN")
        labels.forEachIndexed { i, l ->
            val x = 86 + (i % 2) * 38
            val y = 108 + (i / 2) * 16
            s.text(l, x, y)
            if (i == actionCursor) s.text(">", x - 8, y)
        }
    }

    private fun drawMoves(s: Screen) {
        val moves = battle.me.moves
        s.box(32, 96, 128, 48)
        moves.forEachIndexed { i, m ->
            val y = 103 + i * 9
            s.text(m.move.label, 46, y)
            if (i == moveCursor) s.text(">", 38, y)
        }
        val m = moves[moveCursor]
        s.box(0, 56, 76, 42)
        s.text("TYPE/", 6, 63)
        s.text(m.move.type.name, 14, 73, m.move.type.color.let { shade(it, 0.7f) })
        s.text("PP ${m.pp}/${m.move.pp}", 6, 84)
    }
}
