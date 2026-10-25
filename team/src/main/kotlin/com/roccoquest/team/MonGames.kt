package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.Voice
import com.roccoquest.game.argb
import com.roccoquest.game.shadowText
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

/** The extra Pokemon mini games (from the Pokemon menu's MINI GAME button). */
enum class MonGame(val label: String, val blurb: String) {
    BERRY("BERRY CATCH", "Catch the falling berries!"),
    MEMORY("MEMORY MATCH", "Flip the cards and find the pairs"),
    BALLOON("BALLOON POP", "Tap the balloons before they fly away"),
    DANCE("DANCE PARTY", "Copy your Pokemon's dance moves"),
}

/** Plays MEMORY MATCH, BALLOON POP and DANCE PARTY (BERRY CATCH is the Pokemon Park). */
class MonGames(private val g: TeamGame) {
    var game = MonGame.MEMORY
        private set
    var done = false
        private set
    var score = 0
        private set
    private var t = 0f
    private var result = ""
    private var rewards = true
    private var cursor = 0

    // MEMORY MATCH
    private var cards = listOf<Mon>()
    private val flipped = BooleanArray(12)
    private val matched = BooleanArray(12)
    private val open = ArrayList<Int>()
    private var flipBackT = 0f
    var moves = 0
        private set

    // BALLOON POP
    private class Balloon(var x: Float, var y: Float, val kind: Int, val color: Int, val speed: Float) { var popped = -1f }
    private val balloons = ArrayList<Balloon>()
    private var spawnT = 0f
    private var zapX = 0f
    private var zapY = 0f
    private var zapT = 0f
    private val BALLOON_TIME = 30f

    // DANCE PARTY: 0 left, 1 up, 2 right, 3 down
    private val moves4 = ArrayList<Int>()
    private var showIdx = 0
    private var showT = 0f
    private var inputIdx = 0
    private var lit = -1
    private var litT = 0f
    private var watching = true

    fun start(which: MonGame, energy: Boolean) {
        game = which
        done = false
        score = 0
        t = 0f
        result = ""
        rewards = energy
        cursor = 0
        when (which) {
            MonGame.MEMORY -> {
                val pool = Mon.entries.filter { g.monUnlocked(it) }.shuffled().take(6).let { if (it.size < 6) (it + Mon.entries.take(6)).distinct().take(6) else it }
                cards = (pool + pool).shuffled()
                flipped.fill(false); matched.fill(false); open.clear(); moves = 0; flipBackT = 0f
            }
            MonGame.BALLOON -> { balloons.clear(); spawnT = 0f; zapT = 0f }
            MonGame.DANCE -> { moves4.clear(); repeat(3) { moves4 += Random.nextInt(4) }; startShow() }
            MonGame.BERRY -> {}
        }
    }

    private fun startShow() { watching = true; showIdx = 0; showT = 0.8f; inputIdx = 0; lit = -1 }

    // ------------------------------------------------------------------ Layout
    private fun cardW(viewW: Float) = minOf(52f, (viewW - 60f) / 4f - 6f)
    private fun cardX(i: Int, viewW: Float) = viewW / 2 - 2 * (cardW(viewW) + 6f) + (i % 4) * (cardW(viewW) + 6f) + 3f
    private fun cardY(i: Int) = 40f + (i / 4) * 60f
    private fun padX(k: Int, viewW: Float) = viewW / 2 + when (k) { 0 -> -70f; 2 -> 70f; else -> 0f } - 26f
    private fun padY(k: Int) = 132f + when (k) { 1 -> -40f; 3 -> 40f; else -> 0f } - 18f

    /** One frame. Returns true when it's time to go back home. */
    fun update(dt: Float, viewW: Float, tap: Boolean, tx: Float, ty: Float, left: Boolean, right: Boolean, up: Boolean, down: Boolean, a: Boolean, back: Boolean): Boolean {
        t += dt
        if (done) return (tap || a || back) && t > 1f
        if (back) { finish(); return false }
        when (game) {
            MonGame.MEMORY -> updateMemory(dt, viewW, tap, tx, ty, left, right, up, down, a)
            MonGame.BALLOON -> updateBalloons(dt, viewW, tap, tx, ty, left, right, a)
            MonGame.DANCE -> updateDance(dt, viewW, tap, tx, ty, left, right, up, down)
            MonGame.BERRY -> {}
        }
        return false
    }

    private fun updateMemory(dt: Float, viewW: Float, tap: Boolean, tx: Float, ty: Float, left: Boolean, right: Boolean, up: Boolean, down: Boolean, a: Boolean) {
        if (flipBackT > 0f) {
            flipBackT -= dt
            if (flipBackT <= 0f) { for (i in open) flipped[i] = false; open.clear() }
            return
        }
        if (left) cursor = (cursor + 11) % 12
        if (right) cursor = (cursor + 1) % 12
        if (up) cursor = (cursor + 8) % 12
        if (down) cursor = (cursor + 4) % 12
        var pick = if (a) cursor else -1
        if (tap && tx >= 0f) for (i in 0 until 12) if (tx in cardX(i, viewW)..(cardX(i, viewW) + cardW(viewW)) && ty in cardY(i)..(cardY(i) + 54f)) pick = i
        flipCard(pick)
    }

    internal fun cardsForTest() = cards

    /** Turns card [pick] over (and checks for a pair when two are up). */
    internal fun flipCard(pick: Int) {
        if (game != MonGame.MEMORY || done || flipBackT > 0f) return
        if (pick !in 0 until 12 || flipped[pick] || matched[pick]) return
        flipped[pick] = true
        open += pick
        g.sfx(Sound.KICK)
        if (open.size == 2) {
            moves++
            val (p, q) = open
            if (cards[p] == cards[q]) {
                matched[p] = true; matched[q] = true; open.clear()
                g.sfx(Sound.COIN)
                g.sayAs(cards[p].label + "!", Voice.MON)
                if (matched.all { it }) { score = maxOf(1, 20 - moves); finish() }
            } else flipBackT = 0.8f
        }
    }

    private fun updateBalloons(dt: Float, viewW: Float, tap: Boolean, tx: Float, ty: Float, left: Boolean, right: Boolean, a: Boolean) {
        if (t >= BALLOON_TIME) { finish(); return }
        spawnT -= dt
        if (spawnT <= 0f) {
            spawnT = 0.55f - minOf(0.25f, t / 120f)
            val r = Random.nextFloat()
            val kind = when { r < 0.1f -> 1; r < 0.2f -> 2; else -> 0 } // 1 golden, 2 a spiky bomb
            val color = when (kind) { 1 -> 0xFFFFD21F.toInt(); 2 -> 0xFF2A2A34.toInt(); else -> listOf(0xFFE84A4A, 0xFF3A7AE8, 0xFF4AB85A, 0xFFFF8AC0, 0xFF8A6AE8)[Random.nextInt(5)].toInt() }
            balloons += Balloon(30f + Random.nextFloat() * (viewW - 60f), 250f, kind, color, 40f + Random.nextFloat() * 30f + t * 1.2f)
        }
        for (b in balloons) { if (b.popped < 0f) b.y -= b.speed * dt else b.popped += dt }
        balloons.removeAll { it.y < -30f || it.popped > 0.4f }
        if (left) cursor = maxOf(0, cursor - 1)
        if (right) cursor += 1
        var target: Balloon? = null
        if (tap && tx >= 0f) target = balloons.filter { it.popped < 0f && abs(it.x - tx) < 16f && abs(it.y - ty) < 20f }.minByOrNull { abs(it.y - ty) }
        // With a controller, A pops the lowest balloon.
        if (a) target = balloons.filter { it.popped < 0f }.maxByOrNull { it.y }
        if (target != null) {
            target.popped = 0f
            zapX = target.x; zapY = target.y; zapT = 0.2f
            when (target.kind) {
                1 -> { score += 3; g.sfx(Sound.ONEUP) }
                2 -> { score = maxOf(0, score - 2); g.sfx(Sound.HURT) }
                else -> { score += 1; g.sfx(Sound.ZAP) }
            }
        }
        if (zapT > 0f) zapT -= dt
    }

    private fun updateDance(dt: Float, viewW: Float, tap: Boolean, tx: Float, ty: Float, left: Boolean, right: Boolean, up: Boolean, down: Boolean) {
        if (litT > 0f) { litT -= dt; if (litT <= 0f) lit = -1 }
        if (watching) {
            showT -= dt
            if (showT <= 0f) {
                if (showIdx < moves4.size) {
                    lit = moves4[showIdx]; litT = 0.45f; showIdx++; showT = 0.65f
                    g.sfx(listOf(Sound.JUMP, Sound.COIN, Sound.KICK, Sound.STOMP)[lit])
                } else { watching = false; inputIdx = 0 }
            }
            return
        }
        var k = when { left -> 0; up -> 1; right -> 2; down -> 3; else -> -1 }
        if (tap && tx >= 0f) for (p in 0 until 4) if (tx in padX(p, viewW)..(padX(p, viewW) + 52f) && ty in padY(p)..(padY(p) + 36f)) k = p
        if (k < 0) return
        lit = k; litT = 0.25f
        if (moves4[inputIdx] == k) {
            g.sfx(listOf(Sound.JUMP, Sound.COIN, Sound.KICK, Sound.STOMP)[k])
            inputIdx++
            if (inputIdx == moves4.size) {
                score = moves4.size - 2
                g.sayAs(listOf("Groovy!", "Nice moves!", "Dance dance!", "Woo hoo!")[Random.nextInt(4)], Voice.MON)
                moves4 += Random.nextInt(4)
                startShow(); showT = 1.1f
            }
        } else {
            g.sfx(Sound.BUMP)
            finish()
        }
    }

    private fun finish() {
        done = true
        t = 0f
        val xp = when (game) { MonGame.MEMORY -> score / 2 + 3; MonGame.BALLOON -> minOf(20, score / 3); else -> minOf(20, score * 2) }
        val prize = when (game) { MonGame.MEMORY -> score + 5; MonGame.BALLOON -> minOf(40, score); else -> minOf(40, score * 4) }
        result = if (!rewards) "Just for fun! (Play a level so ${g.mon.label} has energy again.)" else "+$xp XP for ${g.mon.label}   +$prize coins"
        if (rewards) g.rewardMonGame(xp, prize)
        g.sfx(Sound.CLEAR)
    }

    // ------------------------------------------------------------------ Drawing
    fun render(gfx: Gfx, viewW: Float, viewH: Float, time: Float) {
        val bg = when (game) { MonGame.MEMORY -> 0xFF3A6AC8.toInt() to 0xFF8AC8F0.toInt(); MonGame.BALLOON -> 0xFF4AA4F0.toInt() to 0xFFD0F0FF.toInt(); else -> 0xFF2A1A4A.toInt() to 0xFF8A3AD8.toInt() }
        gfx.gradient(0f, 0f, viewW, viewH, bg.first, bg.second)
        gfx.shadowText(game.label, viewW / 2, 18f, 12f, 0xFFFFD21F.toInt(), 1)
        when (game) {
            MonGame.MEMORY -> {
                for (i in 0 until 12) {
                    val x = cardX(i, viewW); val y = cardY(i); val w = cardW(viewW)
                    val up = flipped[i] || matched[i]
                    gfx.rect(x - 1, y - 1, w + 2, 56f, 0xFF1A1420.toInt())
                    gfx.rect(x, y, w, 54f, if (up) (if (matched[i]) 0xFFDFF8D8.toInt() else 0xFFFFFFFF.toInt()) else 0xFFE84A4A.toInt())
                    if (up) TeamArt.mon(gfx, cards[i], x + w / 2, y + 46f, time + i, 1, false)
                    else { gfx.oval(x + w / 2 - 10, y + 17, 20f, 20f, 0xFFFFFFFF.toInt()); gfx.rect(x + w / 2 - 10, y + 26, 20f, 2f, 0xFF1A1420.toInt()); gfx.oval(x + w / 2 - 3, y + 24, 6f, 6f, 0xFF1A1420.toInt()) }
                    if (g.padConnectedForGames && cursor == i && !done) { gfx.rect(x - 3, y - 3, w + 6, 2f, 0xFFFFD21F.toInt()); gfx.rect(x - 3, y + 55, w + 6, 2f, 0xFFFFD21F.toInt()) }
                }
                gfx.shadowText("Turns: $moves", viewW / 2, 228f, 8f, 0xFFFFFFFF.toInt(), 1)
            }
            MonGame.BALLOON -> {
                for (b in balloons) {
                    if (b.popped >= 0f) { for (k in 0 until 6) gfx.oval(b.x + sin(k * 1.05f) * b.popped * 40f - 2, b.y + kotlin.math.cos(k * 1.05f) * b.popped * 40f - 2, 4f, 4f, b.color); continue }
                    gfx.rect(b.x - 0.5f, b.y + 12, 1f, 14f, 0xFF5A5A64.toInt())
                    gfx.oval(b.x - 11, b.y - 13, 22f, 26f, 0xFF1A1420.toInt())
                    gfx.oval(b.x - 10, b.y - 12, 20f, 24f, b.color)
                    gfx.oval(b.x - 6, b.y - 8, 5f, 7f, 0x88FFFFFF.toInt())
                    if (b.kind == 1) gfx.shadowText("★", b.x, b.y + 4, 9f, 0xFFFFFFFF.toInt(), 1)
                    if (b.kind == 2) for (k in 0 until 6) gfx.poly(floatArrayOf(b.x + sin(k * 1.05f) * 10, b.x + sin(k * 1.05f + 0.2f) * 15, b.x + sin(k * 1.05f + 0.4f) * 10), floatArrayOf(b.y + kotlin.math.cos(k * 1.05f) * 12, b.y + kotlin.math.cos(k * 1.05f + 0.2f) * 16, b.y + kotlin.math.cos(k * 1.05f + 0.4f) * 12), 0xFFB8C0CC.toInt())
                }
                val mx = viewW / 2
                TeamArt.mon(ScaledGfx(gfx, 2f, -mx, -232f), g.mon, mx, 232f, time, 1, zapT > 0f)
                if (zapT > 0f) {
                    gfx.poly(floatArrayOf(mx - 2, mx + 2, zapX + 2, zapX - 2), floatArrayOf(200f, 200f, zapY, zapY), 0xFFFFE14D.toInt())
                }
                gfx.shadowText("SCORE $score", 50f, 34f, 9f, 0xFFFFFFFF.toInt(), 1)
                gfx.shadowText("${maxOf(0, (BALLOON_TIME - t).toInt())}s", viewW - 30f, 34f, 9f, 0xFFFFFFFF.toInt(), 1)
                gfx.shadowText("Gold = 3 points. Don't pop the spiky ones!", viewW / 2, 48f, 6f, 0xFFFFF2C8.toInt(), 1)
            }
            MonGame.DANCE -> {
                // Disco lights
                for (k in 0 until 6) gfx.oval(viewW * k / 6f + sin(time + k) * 20f, 30f, 40f, 40f, argb(50, solidRainbow(time * 0.3f + k * 0.15f, 0f) and 0xFFFFFF))
                val arrows = listOf("◀", "▲", "▶", "▼")
                for (p in 0 until 4) {
                    val x = padX(p, viewW); val y = padY(p)
                    val c = listOf(0xFFE84A4A, 0xFF4AB85A, 0xFF3A7AE8, 0xFFF4D84A)[p].toInt()
                    gfx.rect(x - 1, y - 1, 54f, 38f, 0xFF1A1420.toInt())
                    gfx.rect(x, y, 52f, 36f, if (lit == p) 0xFFFFFFFF.toInt() else c)
                    gfx.shadowText(arrows[p], x + 26f, y + 25f, 16f, if (lit == p) c else 0xFFFFFFFF.toInt(), 1)
                }
                // The Pokemon dances the move that's lit.
                val hop = if (lit >= 0) -8f else -abs(sin(time * 6)) * 3f
                val dx = when (lit) { 0 -> -10f; 2 -> 10f; else -> 0f }
                val my = 150f + hop + (if (lit == 3) 6f else 0f)
                TeamArt.mon(ScaledGfx(gfx, 2f, -(viewW / 2 + dx), -my), g.mon, viewW / 2 + dx, my, time, if (lit == 0) -1 else 1, lit >= 0)
                gfx.shadowText(if (watching) "WATCH..." else "YOUR TURN! (${inputIdx}/${moves4.size})", viewW / 2, 40f, 9f, 0xFFFFFFFF.toInt(), 1)
                gfx.shadowText("Dances in a row: $score", viewW / 2, 228f, 8f, 0xFFFFD21F.toInt(), 1)
            }
            MonGame.BERRY -> {}
        }
        if (done) {
            gfx.rect(viewW / 2 - 150f, 80f, 300f, 80f, 0xEE1A1420.toInt())
            gfx.shadowText(if (game == MonGame.MEMORY) "ALL PAIRS FOUND!" else "GREAT GAME!", viewW / 2, 104f, 12f, 0xFFFFD21F.toInt(), 1)
            gfx.text(result, viewW / 2, 126f, 7f, 0xFFFFFFFF.toInt(), 1)
            if (t > 1f) gfx.text("Tap to go back home", viewW / 2, 148f, 7f, 0xFFDDDDDD.toInt(), 1)
        }
    }
}
