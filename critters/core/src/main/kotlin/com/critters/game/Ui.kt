package com.critters.game

/** A box or screen drawn over the current scene; only the top one gets input. */
abstract class Widget {
    var done = false
    abstract fun update(g: Game)
    abstract fun draw(g: Game, s: Screen)
}

/** Splits text into lines of at most [width] characters, breaking at spaces and '\n'. */
fun wrap(text: String, width: Int = 24): List<String> {
    val out = ArrayList<String>()
    for (para in text.split('\n')) {
        var line = ""
        for (word in para.split(' ').filter { it.isNotEmpty() }) {
            var w = word
            while (w.length > width) {
                if (line.isNotEmpty()) { out += line; line = "" }
                out += w.take(width)
                w = w.drop(width)
            }
            line = if (line.isEmpty()) w else if (line.length + 1 + w.length <= width) "$line $w" else { out += line; w }
        }
        out += line
    }
    return out
}

/** The text box at the bottom of the screen, typed out letter by letter. */
class Dialog(
    text: String,
    private val ask: Boolean = false,
    private val hold: Boolean = false,
    private val onDone: (Boolean) -> Unit,
) : Widget() {
    private val pages = wrap(text).chunked(2)
    private var page = if (hold) pages.lastIndex else 0
    private var shown = if (hold) 999 else 0
    private var asked = false
    private var blink = 0

    private fun pageLen() = pages[page].sumOf { it.length } + 1

    override fun update(g: Game) {
        blink++
        if (shown < pageLen()) {
            shown += if (g.down(Btn.A) || g.down(Btn.B)) 3 else 1
            if (g.pressed(Btn.A)) shown = pageLen()
            return
        }
        if (page == pages.lastIndex && ask && !asked) {
            asked = true
            g.push(Menu(listOf("YES", "NO"), x = 112, y = 56, w = 48, cancelable = true) { i ->
                done = true
                onDone(i == 0)
            })
            return
        }
        if (hold) return
        if (g.pressed(Btn.A) || g.pressed(Btn.B)) {
            g.sfx(Sfx.SELECT)
            if (page < pages.lastIndex) {
                page++
                shown = 0
            } else {
                done = true
                onDone(false)
            }
        }
    }

    override fun draw(g: Game, s: Screen) {
        s.box(0, 96, SW, 48)
        var left = shown
        pages[page].forEachIndexed { i, line ->
            val vis = line.take(left.coerceAtLeast(0))
            left -= line.length + 1
            s.text(vis, 8, 106 + i * 16)
        }
        if (shown >= pageLen() && !ask && !hold && blink / 16 % 2 == 0) s.text("^", 146, 133)
    }
}

/** A list of choices in a box with a cursor. [onPick] gets -1 if cancelled with B. */
class Menu(
    private val options: List<String>,
    private val x: Int = 96,
    private val y: Int = 0,
    w: Int = 0,
    private val maxRows: Int = 7,
    private val cancelable: Boolean = true,
    private val title: String? = null,
    private val keepOpenOnPick: Boolean = false,
    private val onPick: (Int) -> Unit,
) : Widget() {
    private val width = if (w > 0) w else (options.maxOfOrNull { it.length } ?: 4).coerceAtLeast(title?.length ?: 0) * 6 + 22
    private var cursor = 0
    private var top = 0
    private val rows = minOf(options.size, maxRows)
    private val head = if (title != null) 12 else 0

    override fun update(g: Game) {
        if (options.isEmpty()) {
            done = true
            onPick(-1)
            return
        }
        if (g.repeat(Btn.UP)) { cursor = (cursor - 1 + options.size) % options.size; g.sfx(Sfx.SELECT) }
        if (g.repeat(Btn.DOWN)) { cursor = (cursor + 1) % options.size; g.sfx(Sfx.SELECT) }
        if (cursor < top) top = cursor
        if (cursor >= top + rows) top = cursor - rows + 1
        if (g.pressed(Btn.A)) {
            g.sfx(Sfx.SELECT)
            if (!keepOpenOnPick) done = true
            onPick(cursor)
        } else if (cancelable && g.pressed(Btn.B)) {
            done = true
            onPick(-1)
        }
    }

    override fun draw(g: Game, s: Screen) {
        val h = rows * 12 + 12 + head
        s.box(x, y, width, h)
        title?.let { s.text(it, x + 8, y + 7) }
        for (i in 0 until rows) {
            val idx = top + i
            s.text(options[idx], x + 14, y + 8 + head + i * 12)
            if (idx == cursor) s.text(">", x + 6, y + 8 + head + i * 12)
        }
        if (top > 0) s.text("^", x + width - 12, y + 4).also { }
        if (top + rows < options.size && (g.tick / 16) % 2 == 0) s.text("^", x + width - 12, y + h - 10)
    }
}

/** Shows one critter's details and moves. */
class SummaryScreen(private val c: Critter) : Widget() {
    override fun update(g: Game) {
        if (g.pressed(Btn.A) || g.pressed(Btn.B)) done = true
    }

    override fun draw(g: Game, s: Screen) {
        s.fill(PAPER)
        s.rect(0, 0, SW, 56, c.species.types[0].color)
        s.rect(0, 54, SW, 2, INK)
        s.blit(CritterArt.front(c.species.id), 4, 4)
        s.text(c.name, 58, 6, PAPER)
        s.text("NO.${c.species.id.toString().padStart(3, '0')}", 58, 16, PAPER)
        s.text(":L${c.level}", 118, 16, PAPER)
        c.species.types.forEachIndexed { i, t -> typeTag(s, t, 58 + i * 50, 28) }
        s.text("HP", 58, 42, PAPER)
        s.hpBar(72, 43, 48, c.hp.toFloat() / c.maxHp)
        s.textRight("${c.hp}/${c.maxHp}", 158, 42, PAPER)
        s.text("ATK ${c.atk}", 6, 62)
        s.text("DEF ${c.def}", 82, 62)
        s.text("SPD ${c.spd}", 6, 72)
        s.text("STATUS ${if (c.fainted) "FNT" else if (c.status == Status.OK) "OK" else c.status.tag}", 82, 72)
        val next = if (c.level >= Critter.MAX_LEVEL) 0 else Critter.xpFor(c.level + 1) - c.xp
        s.text("XP ${c.xp}", 6, 82)
        s.text("NEXT ${next}", 82, 82)
        s.rect(4, 92, 152, 1, INK)
        c.moves.forEachIndexed { i, m ->
            s.text(m.move.label, 6, 97 + i * 11)
            s.textRight("${m.pp}/${m.move.pp}", 156, 97 + i * 11)
        }
    }

    companion object {
        fun typeTag(s: Screen, t: Type, x: Int, y: Int) {
            s.rect(x, y, 46, 10, INK)
            s.rect(x + 1, y + 1, 44, 8, t.color)
            s.textCenter(t.name, x + 23, y + 2, PAPER)
        }
    }
}

/** The party list. What picking a critter does depends on [mode]. */
class PartyScreen(
    private val mode: Mode,
    private val prompt: String = "CHOOSE A CRITTER.",
    private val onPick: (Int) -> Unit,
) : Widget() {
    enum class Mode { MENU, BATTLE, FORCED, ITEM }

    private var cursor = 0
    private var moving = -1
    private var msg = prompt

    override fun update(g: Game) {
        val n = g.party.size
        if (g.repeat(Btn.UP)) { cursor = (cursor - 1 + n) % n; g.sfx(Sfx.SELECT) }
        if (g.repeat(Btn.DOWN)) { cursor = (cursor + 1) % n; g.sfx(Sfx.SELECT) }
        if (g.pressed(Btn.B) && mode != Mode.FORCED) {
            if (moving >= 0) { moving = -1; msg = prompt; return }
            done = true
            onPick(-1)
            return
        }
        if (!g.pressed(Btn.A)) return
        g.sfx(Sfx.SELECT)
        val c = g.party[cursor]
        when (mode) {
            Mode.MENU -> {
                if (moving >= 0) {
                    val a = g.party[moving]
                    g.party[moving] = g.party[cursor]
                    g.party[cursor] = a
                    moving = -1
                    msg = prompt
                    return
                }
                g.push(Menu(listOf("SUMMARY", "SWITCH", "CANCEL"), x = 96, y = 72, w = 64) { i ->
                    when (i) {
                        0 -> g.push(SummaryScreen(c))
                        1 -> { moving = cursor; msg = "MOVE TO WHERE?" }
                    }
                })
            }
            Mode.BATTLE, Mode.FORCED -> {
                val opts = listOf("SWITCH", "SUMMARY", "CANCEL")
                g.push(Menu(opts, x = 96, y = 72, w = 64) { i ->
                    when (i) {
                        0 -> when {
                            c.fainted -> g.push(Dialog("THERE'S NO WILL TO BATTLE!") {})
                            g.battleScene?.battle?.me === c -> g.push(Dialog("${c.name} IS ALREADY OUT!") {})
                            else -> { done = true; onPick(cursor) }
                        }
                        1 -> g.push(SummaryScreen(c))
                    }
                })
            }
            Mode.ITEM -> {
                done = true
                onPick(cursor)
            }
        }
    }

    override fun draw(g: Game, s: Screen) {
        s.fill(PAPER)
        g.party.forEachIndexed { i, c ->
            val y = i * 18 + 2
            if (i == cursor) s.rect(0, y - 1, SW, 18, 0xFFD8E8FF.toInt())
            if (i == moving) s.rect(0, y - 1, SW, 18, 0xFFF8E0A0.toInt())
            val bob = if (i == cursor && (g.tick / 8) % 2 == 0) -1 else 0
            s.blit(CritterArt.icon(c.species.id), 10, y + bob)
            if (i == cursor) s.text(">", 2, y + 5)
            s.text(c.name, 30, y + 1)
            val tag = if (c.fainted) "FNT" else c.status.tag
            if (tag.isNotEmpty()) s.text(tag, 94, y + 1, 0xFFC83030.toInt())
            s.textRight(":L${c.level}", 158, y + 1)
            s.text("HP", 30, y + 9)
            s.hpBar(44, y + 10, 48, c.hp.toFloat() / c.maxHp)
            s.textRight("${c.hp}/${c.maxHp}", 158, y + 9)
        }
        s.box(0, 110, SW, 34)
        s.text(msg, 8, 123)
    }
}

/** Whether using [item] on [c] would do anything. */
fun itemWorks(item: Item, c: Critter) = when (item) {
    Item.POTION, Item.SUPER_POTION -> !c.fainted && c.hp < c.maxHp
    Item.FULL_HEAL -> !c.fainted && c.status != Status.OK
    Item.REVIVE -> c.fainted
    else -> false
}

/**
 * The bag. In battle, [onChoose] gets the item and target critter (-1 for capsules)
 * and the battle does the rest; outside battle, healing items are used right here.
 */
class BagScreen(private val inBattle: Boolean, private val onChoose: (Item, Int) -> Unit) : Widget() {
    private var cursor = 0

    override fun update(g: Game) {
        val items = g.bag.list()
        val n = items.size + 1
        if (g.repeat(Btn.UP)) { cursor = (cursor - 1 + n) % n; g.sfx(Sfx.SELECT) }
        if (g.repeat(Btn.DOWN)) { cursor = (cursor + 1) % n; g.sfx(Sfx.SELECT) }
        if (g.pressed(Btn.B) || (g.pressed(Btn.A) && cursor == items.size)) {
            done = true
            if (inBattle) onChoose(Item.POTION, -2)
            return
        }
        if (!g.pressed(Btn.A)) return
        g.sfx(Sfx.SELECT)
        val item = items[cursor].first
        if (item.isBall) {
            if (!inBattle) {
                g.push(Dialog("YOU CAN'T USE THAT HERE.") {})
            } else {
                done = true
                onChoose(item, -1)
            }
            return
        }
        g.push(PartyScreen(PartyScreen.Mode.ITEM, "USE ON WHICH CRITTER?") { i ->
            if (i < 0) return@PartyScreen
            val c = g.party[i]
            if (!itemWorks(item, c)) {
                g.push(Dialog("IT WON'T HAVE ANY EFFECT.") {})
                return@PartyScreen
            }
            if (inBattle) {
                done = true
                onChoose(item, i)
            } else {
                g.bag.take(item)
                val msg = applyItem(item, c) ?: ""
                g.sfx(Sfx.HEAL)
                g.push(Dialog(msg) {})
                if (cursor >= g.bag.list().size) cursor = g.bag.list().size
            }
        })
    }

    override fun draw(g: Game, s: Screen) {
        s.fill(0xFFF0D8A0.toInt())
        s.rect(0, 0, SW, 14, 0xFFB86830.toInt())
        s.text("BAG", 8, 4, PAPER)
        s.textRight("$${g.money}", 154, 4, PAPER)
        val items = g.bag.list()
        val top = (cursor - 6).coerceAtLeast(0)
        for (i in top until minOf(items.size + 1, top + 7)) {
            val y = 20 + (i - top) * 12
            if (i == cursor) s.text(">", 6, y)
            if (i == items.size) {
                s.text("CANCEL", 14, y)
            } else {
                s.text(items[i].first.label, 14, y)
                s.textRight("X${items[i].second}", 154, y)
            }
        }
        s.box(0, 108, SW, 36)
        val desc = if (cursor < items.size) items[cursor].first.desc else "CLOSE THE BAG."
        wrap(desc, 24).take(2).forEachIndexed { i, l -> s.text(l, 8, 117 + i * 10) }
    }
}

/** The Mart counter: pick an item, pick how many, pay. */
class ShopScreen(private val stock: List<Item>, private val onClose: () -> Unit) : Widget() {
    private var cursor = 0
    private var qty = 0

    override fun update(g: Game) {
        if (qty > 0) {
            val item = stock[cursor]
            if (g.repeat(Btn.UP)) qty = if (qty >= 99) 1 else qty + 1
            if (g.repeat(Btn.DOWN)) qty = if (qty <= 1) 99 else qty - 1
            if (g.repeat(Btn.RIGHT)) qty = minOf(99, qty + 10)
            if (g.repeat(Btn.LEFT)) qty = maxOf(1, qty - 10)
            if (g.pressed(Btn.B)) qty = 0
            if (g.pressed(Btn.A)) {
                val cost = item.price * qty
                if (cost > g.money) {
                    g.push(Dialog("YOU DON'T HAVE ENOUGH MONEY.") {})
                } else {
                    g.money -= cost
                    g.bag.add(item, qty)
                    g.sfx(Sfx.BUY)
                    g.push(Dialog("HERE YOU ARE! THANK YOU!") {})
                }
                qty = 0
            }
            return
        }
        val n = stock.size + 1
        if (g.repeat(Btn.UP)) { cursor = (cursor - 1 + n) % n; g.sfx(Sfx.SELECT) }
        if (g.repeat(Btn.DOWN)) { cursor = (cursor + 1) % n; g.sfx(Sfx.SELECT) }
        if (g.pressed(Btn.B) || (g.pressed(Btn.A) && cursor == stock.size)) {
            done = true
            onClose()
            return
        }
        if (g.pressed(Btn.A)) {
            g.sfx(Sfx.SELECT)
            qty = 1
        }
    }

    override fun draw(g: Game, s: Screen) {
        s.box(0, 0, SW, 96)
        s.text("MART", 8, 8)
        s.textRight("$${g.money}", 152, 8)
        for (i in 0..stock.size) {
            val y = 22 + i * 11
            if (i == cursor) s.text(">", 6, y)
            if (i == stock.size) s.text("CANCEL", 14, y) else {
                s.text(stock[i].label, 14, y)
                s.textRight("$${stock[i].price}", 152, y)
            }
        }
        s.box(0, 96, SW, 48)
        if (qty > 0) {
            val item = stock[cursor]
            s.text("HOW MANY?  X${qty.toString().padStart(2, '0')}", 8, 106)
            s.text("TOTAL $${item.price * qty}", 8, 122)
            s.text("^", 140, 106)
        } else {
            val desc = if (cursor < stock.size) "${stock[cursor].desc} (YOU HAVE ${g.bag.count(stock[cursor])})" else "SEE YOU AGAIN!"
            wrap(desc, 24).take(2).forEachIndexed { i, l -> s.text(l, 8, 106 + i * 16) }
        }
    }
}

/** The Critterdex: every species you've seen, and details for the ones you've caught. */
class DexScreen : Widget() {
    private var cursor = 0
    private var entry = false

    override fun update(g: Game) {
        if (entry) {
            if (g.pressed(Btn.A) || g.pressed(Btn.B)) entry = false
            if (g.repeat(Btn.UP) || g.repeat(Btn.DOWN)) {
                cursor = (cursor + if (g.repeat(Btn.UP)) Dex.all.size - 1 else 1) % Dex.all.size
                if (Dex.all[cursor].id in g.seen) g.audio.cry(Dex.all[cursor].id)
            }
            return
        }
        val n = Dex.all.size
        if (g.repeat(Btn.UP)) { cursor = (cursor - 1 + n) % n; g.sfx(Sfx.SELECT) }
        if (g.repeat(Btn.DOWN)) { cursor = (cursor + 1) % n; g.sfx(Sfx.SELECT) }
        if (g.repeat(Btn.LEFT)) cursor = (cursor - 8).coerceAtLeast(0)
        if (g.repeat(Btn.RIGHT)) cursor = (cursor + 8).coerceAtMost(n - 1)
        if (g.pressed(Btn.B)) done = true
        if (g.pressed(Btn.A) && Dex.all[cursor].id in g.seen) {
            entry = true
            g.audio.cry(Dex.all[cursor].id)
        }
    }

    override fun draw(g: Game, s: Screen) {
        s.fill(0xFFE84850.toInt())
        if (entry) {
            val sp = Dex.all[cursor]
            val got = sp.id in g.caught
            s.rect(4, 4, 152, 136, PAPER)
            s.rect(8, 8, 52, 52, 0xFFD8E8F0.toInt())
            s.blit(CritterArt.front(sp.id), 10, 10)
            s.text(sp.name, 66, 10)
            s.text("NO.${sp.id.toString().padStart(3, '0')}", 66, 22)
            s.text(if (got) "${sp.kind} CRITTER" else "???", 66, 34)
            if (got) sp.types.forEachIndexed { i, t -> SummaryScreen.typeTag(s, t, 66 + i * 48, 46) }
            s.rect(8, 64, 144, 1, INK)
            val desc = if (got) sp.dex else "CATCH ONE TO LEARN MORE ABOUT IT."
            wrap(desc, 23).take(6).forEachIndexed { i, l -> s.text(l, 10, 72 + i * 11) }
            return
        }
        s.rect(4, 4, 152, 136, PAPER)
        s.text("CRITTERDEX", 10, 8)
        s.text("SEEN ${g.seen.size}  OWN ${g.caught.size}", 10, 18)
        val top = (cursor - 4).coerceIn(0, Dex.all.size - 9)
        for (i in top until top + 9) {
            val sp = Dex.all[i]
            val y = 32 + (i - top) * 12
            if (i == cursor) s.text(">", 8, y)
            s.text(sp.id.toString().padStart(3, '0'), 16, y)
            s.text(if (sp.id in g.seen) sp.name else "----------", 40, y)
            if (sp.id in g.caught) s.blit(Art.capsule, 140, y - 1)
        }
        val sp = Dex.all[cursor]
        if (sp.id in g.seen) s.blitScaled(CritterArt.front(sp.id), 112, 28, 32, 32)
    }
}

class TrainerCard : Widget() {
    override fun update(g: Game) {
        if (g.pressed(Btn.A) || g.pressed(Btn.B)) done = true
    }

    override fun draw(g: Game, s: Screen) {
        s.fill(0xFF4878E0.toInt())
        s.box(4, 8, 152, 128)
        s.text("TRAINER CARD", 12, 16)
        s.blitScaled(Art.person(Art.Look.PLAYER).down, 112, 22, 32, 32)
        s.text("NAME  ${g.playerName}", 12, 32)
        s.text("MONEY $${g.money}", 12, 44)
        s.text("DEX   ${g.caught.size}", 12, 56)
        val secs = g.playFrames / 60
        s.text("TIME  ${secs / 3600}:${(secs / 60 % 60).toString().padStart(2, '0')}", 12, 68)
        s.text("BADGES", 12, 86)
        val names = listOf("badge1" to 0xFFB89868.toInt(), "badge2" to 0xFF58A8F8.toInt(), "badge3" to 0xFFF8D040.toInt())
        names.forEachIndexed { i, (f, col) ->
            val x = 24 + i * 40
            s.rect(x, 100, 24, 24, 0xFFD0D0D8.toInt())
            if (f in g.flags) {
                s.rect(x + 3, 103, 18, 18, INK)
                s.rect(x + 4, 104, 16, 16, col)
                Font.draw(s, '%', x + 10, 109, PAPER)
            }
        }
    }
}

/** A critter evolving: its picture flickers between the two forms, faster and faster. */
class EvolveScene(private val g: Game, private val c: Critter, private val onDone: () -> Unit) {
    private val from = c.species
    private val to = Dex[c.species.evolveTo]
    private var t = -1
    private var finished = false

    init {
        g.audio.cry(from.id)
        g.push(Dialog("WHAT? ${c.name} IS EVOLVING!") { t = 0 })
    }

    fun update() {
        if (t < 0 || finished) return
        t++
        if (t == 240) {
            finished = true
            val oldName = c.name
            val moves = c.evolve()
            g.audio.cry(to.id)
            g.sfx(Sfx.LEVEL_UP)
            g.push(Dialog("CONGRATULATIONS! YOUR $oldName EVOLVED INTO ${to.name}!") { learnNext(moves.toMutableList()) })
        }
    }

    private fun learnNext(left: MutableList<Move>) {
        val m = left.removeFirstOrNull() ?: return onDone()
        if (c.knows(m)) return learnNext(left)
        if (c.learn(m)) g.push(Dialog("${c.name} LEARNED ${m.label}!") { learnNext(left) })
        else g.learnFlow(c, m) { learnNext(left) }
    }

    fun draw(s: Screen) {
        s.fill(PAPER)
        val showNew = finished || (t > 0 && run {
            val period = (40 - t / 7).coerceAtLeast(3)
            (t / period) % 2 == 1
        })
        val sp = CritterArt.front(if (showNew) to.id else from.id)
        val tint = if (!finished && t > 0) 0xFF404050.toInt() else 0
        s.blit(sp, 56, 30, tint = tint)
        if (t in 200..239) s.fade((t - 200) / 40f, white = true)
    }
}
