package com.roccoquest.chicken

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.SoundSink
import com.roccoquest.game.Storage
import com.roccoquest.game.argb
import com.roccoquest.game.shadowText
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** The game is always [WHACK_W] virtual pixels wide; its height comes from the screen. */
const val WHACK_W = 240f

/**
 * Whack the Chicken Man: a whack-a-mole game. A man in a chicken suit pops out of
 * nine holes in a farm field. Bonk him before he ducks back down, and leave the
 * real chickens alone. Each round lasts [ROUND_TIME] seconds, and the top five
 * scores are kept in [Storage].
 */
class WhackGame(
    private val sound: SoundSink = SoundSink {},
    private val storage: Storage = Storage.Memory(),
    private val rng: Random = Random.Default,
) {
    enum class State { TITLE, PLAYING, OVER }

    enum class Kind { MAN, GOLDEN, CHICKEN }

    enum class Phase { RISING, UP, FALLING, HIT }

    class Popper(val hole: Int, val kind: Kind, val upTime: Float) {
        var phase = Phase.RISING
        var t = 0f

        /** 0 = hidden in the hole, 1 = all the way up. */
        var rise = 0f

        val hittable get() = (phase == Phase.RISING || phase == Phase.UP) && rise > 0.35f
    }

    private class FloatText(val text: String, val x: Float, var y: Float, val color: Int, val size: Float) {
        var life = 1f
    }

    private class Feather(var x: Float, var y: Float, var vx: Float, var vy: Float, val color: Int) {
        var life = 1f
    }

    /** Screen height in virtual pixels; set by the platform layer. */
    var viewH = 480f

    var state = State.TITLE
        private set
    var score = 0
        private set
    var timeLeft = ROUND_TIME
        private set
    var combo = 0
        private set
    var hits = 0
        private set
    var bestCombo = 0
        private set

    /** One slot per hole; null when the hole is empty. */
    val poppers = arrayOfNulls<Popper>(9)

    /** Top five scores, highest first. */
    val highScores = IntArray(SCORE_SLOTS) { storage.load("$KEY$it") }

    /** Where the last round's score landed in [highScores], or -1 if it didn't make the list. */
    var newRank = -1
        private set

    private val taps = ConcurrentLinkedQueue<FloatArray>()
    private val texts = ArrayList<FloatText>()
    private val feathers = ArrayList<Feather>()
    private var stateTime = 0f
    private var time = 0f
    private var spawnTimer = 0f
    private var lastTick = 0
    private var hammerX = 0f
    private var hammerY = 0f
    private var hammerT = 0f

    /** Called from the UI thread with a touch position in virtual pixels. */
    fun tap(x: Float, y: Float) {
        taps.add(floatArrayOf(x, y))
    }

    // ---- Layout ----

    private val rowGap get() = ((viewH - 140f) / 3f).coerceIn(80f, 120f)
    fun holeX(i: Int) = WHACK_W / 2f + (i % 3 - 1) * 78f
    fun holeY(i: Int) = viewH - 46f - (2 - i / 3) * rowGap
    private val horizon get() = holeY(0) - 80f

    /** Where to tap to hit whatever is in hole [i] (its face). */
    fun targetY(i: Int): Float {
        val p = poppers[i] ?: return holeY(i)
        return holeY(i) + 6f - p.rise * (heightOf(p.kind) + 6f) + 18f
    }

    private fun heightOf(k: Kind) = if (k == Kind.CHICKEN) 42f else 60f

    // ---- Update ----

    fun update(dt: Float) {
        time += dt
        stateTime += dt
        when (state) {
            State.TITLE -> handleTaps { if (stateTime > 0.3f) startRound() }
            State.PLAYING -> playing(dt)
            State.OVER -> handleTaps { if (stateTime > 1.2f) startRound() }
        }
        if (hammerT > 0f) hammerT -= dt
        texts.forEach { it.y -= 22f * dt; it.life -= dt * 0.9f }
        texts.removeAll { it.life <= 0f }
        feathers.forEach {
            it.x += it.vx * dt; it.y += it.vy * dt
            it.vy += 60f * dt; it.vx *= 0.97f
            it.life -= dt * 0.8f
        }
        feathers.removeAll { it.life <= 0f }
    }

    private inline fun handleTaps(action: (FloatArray) -> Unit) {
        while (true) action(taps.poll() ?: return)
    }

    fun startRound() {
        state = State.PLAYING
        stateTime = 0f
        score = 0
        combo = 0
        bestCombo = 0
        hits = 0
        timeLeft = ROUND_TIME
        spawnTimer = 0.6f
        lastTick = ROUND_TIME.toInt()
        newRank = -1
        poppers.fill(null)
        texts.clear()
        taps.clear()
        sound.play(Sound.POWERUP)
    }

    private fun playing(dt: Float) {
        handleTaps { whack(it[0], it[1]) }
        timeLeft -= dt
        val secs = timeLeft.toInt() + 1
        if (secs < lastTick) {
            lastTick = secs
            if (secs <= 5) sound.play(Sound.BUMP)
        }
        if (timeLeft <= 0f) {
            timeLeft = 0f
            endRound()
            return
        }

        val d = ((ROUND_TIME - timeLeft) / ROUND_TIME).coerceIn(0f, 1f)
        for (i in poppers.indices) {
            val p = poppers[i] ?: continue
            p.t += dt
            when (p.phase) {
                Phase.RISING -> {
                    p.rise = min(1f, p.t / RISE_TIME)
                    if (p.rise >= 1f) { p.phase = Phase.UP; p.t = 0f }
                }
                Phase.UP -> if (p.t >= p.upTime) { p.phase = Phase.FALLING; p.t = 0f }
                Phase.FALLING -> {
                    p.rise = 1f - p.t / RISE_TIME
                    if (p.rise <= 0f) {
                        poppers[i] = null
                        if (p.kind != Kind.CHICKEN && combo > 0) {
                            combo = 0
                            addText("TOO SLOW!", holeX(i), holeY(i) - 30f, 0xFFFFFFFF.toInt(), 9f)
                        }
                    }
                }
                Phase.HIT -> {
                    if (p.t > HIT_TIME) p.rise = 1f - (p.t - HIT_TIME) / 0.2f
                    if (p.rise <= 0f) poppers[i] = null
                }
            }
        }

        spawnTimer -= dt
        val maxUp = min(3, 1 + (d * 3f).toInt())
        if (spawnTimer <= 0f && poppers.count { it != null } < maxUp) {
            spawnTimer = lerp(0.85f, 0.38f, d) * (0.8f + rng.nextFloat() * 0.4f)
            spawn(d)
        }
    }

    private fun spawn(d: Float) {
        val free = poppers.indices.filter { poppers[it] == null }
        if (free.isEmpty()) return
        val hole = free[rng.nextInt(free.size)]
        val roll = rng.nextFloat()
        val elapsed = ROUND_TIME - timeLeft
        val kind = when {
            elapsed > 4f && roll < 0.15f + 0.1f * d -> Kind.CHICKEN
            roll > 0.93f -> Kind.GOLDEN
            else -> Kind.MAN
        }
        val up = lerp(1.25f, 0.6f, d) * if (kind == Kind.GOLDEN) 0.6f else 1f
        poppers[hole] = Popper(hole, kind, up)
    }

    private fun whack(x: Float, y: Float) {
        hammerX = x
        hammerY = y
        hammerT = HAMMER_TIME
        // Lower rows are drawn in front, so check them first.
        for (i in 8 downTo 0) {
            val p = poppers[i] ?: continue
            if (!p.hittable) continue
            val top = holeY(i) + 6f - p.rise * (heightOf(p.kind) + 6f)
            if (abs(x - holeX(i)) < 32f && y > top - 8f && y < holeY(i) + 14f) {
                hit(p)
                return
            }
        }
        if (combo > 0) addText("MISS", x, y - 10f, 0xFFDDDDDD.toInt(), 8f)
        combo = 0
    }

    private fun hit(p: Popper) {
        p.phase = Phase.HIT
        p.t = 0f
        val x = holeX(p.hole)
        val y = holeY(p.hole) - 50f
        repeat(8) {
            val a = rng.nextFloat() * 2f * PI.toFloat()
            val c = when (p.kind) {
                Kind.GOLDEN -> GOLD
                else -> 0xFFFFFFFF.toInt()
            }
            feathers += Feather(x, y + 20f, cos(a) * 70f, sin(a) * 50f - 40f, c)
        }
        if (p.kind == Kind.CHICKEN) {
            timeLeft = (timeLeft - CHICKEN_PENALTY).coerceAtLeast(0.01f)
            combo = 0
            sound.play(Sound.HURT)
            sound.play(Sound.BAWK)
            addText("REAL CHICKEN!", x, y, 0xFFFF5A4E.toInt(), 9f)
            addText("-${CHICKEN_PENALTY.toInt()} SEC", x, y + 12f, 0xFFFF5A4E.toInt(), 9f)
            return
        }
        hits++
        combo++
        bestCombo = maxOf(bestCombo, combo)
        val points = (if (p.kind == Kind.GOLDEN) 50 else 10) * multiplier
        score += points
        sound.play(if (p.kind == Kind.GOLDEN) Sound.COIN else Sound.STOMP)
        sound.play(Sound.BAWK)
        addText("+$points", x, y, if (p.kind == Kind.GOLDEN) GOLD else 0xFFFFFFFF.toInt(), 12f)
    }

    /** Every four hits in a row adds one to the multiplier, up to x5. */
    val multiplier get() = min(5, 1 + combo / 4)

    private fun endRound() {
        state = State.OVER
        stateTime = 0f
        poppers.fill(null)
        newRank = recordScore(score)
        sound.play(if (newRank == 0) Sound.VICTORY else Sound.CLEAR)
    }

    private fun recordScore(s: Int): Int {
        if (s <= 0) return -1
        val rank = highScores.indexOfFirst { s > it }
        if (rank < 0) return -1
        for (i in SCORE_SLOTS - 1 downTo rank + 1) highScores[i] = highScores[i - 1]
        highScores[rank] = s
        highScores.forEachIndexed { i, v -> storage.save("$KEY$i", v) }
        return rank
    }

    private fun addText(s: String, x: Float, y: Float, color: Int, size: Float) {
        texts += FloatText(s, x, y, color, size)
    }

    // ---- Rendering ----

    fun render(g: Gfx) {
        drawScene(g)
        when (state) {
            State.TITLE -> drawTitle(g)
            State.PLAYING -> drawHud(g)
            State.OVER -> drawGameOver(g)
        }
    }

    private fun drawScene(g: Gfx) {
        val hz = horizon
        g.rect(0f, 0f, WHACK_W, hz, 0xFF8FD3FF.toInt())
        g.rect(0f, hz * 0.55f, WHACK_W, hz * 0.45f, 0xFFB5E3FF.toInt())
        g.oval(182f, hz - 82f, 34f, 34f, 0xFFFFE066.toInt())
        for (k in 0 until 3) {
            val cx = (k * 97f + time * (6f + k * 2f)) % (WHACK_W + 80f) - 40f
            val cy = hz - 100f + k * 18f
            g.oval(cx, cy, 34f, 12f, 0xFFFFFFFF.toInt())
            g.oval(cx + 10f, cy - 6f, 20f, 14f, 0xFFFFFFFF.toInt())
        }
        g.oval(-40f, hz - 22f, 160f, 50f, 0xFF7CC95F.toInt())
        g.oval(110f, hz - 30f, 180f, 60f, 0xFF6CBB50.toInt())
        // The barn.
        g.rect(20f, hz - 42f, 50f, 42f, 0xFFB83A2E.toInt())
        g.poly(floatArrayOf(15f, 45f, 75f), floatArrayOf(hz - 40f, hz - 62f, hz - 40f), 0xFF8E2A22.toInt())
        g.rect(35f, hz - 24f, 20f, 24f, 0xFFF4EDE0.toInt())
        g.rect(37f, hz - 22f, 16f, 22f, 0xFFB83A2E.toInt())
        g.poly(floatArrayOf(37f, 39f, 53f, 51f), floatArrayOf(hz - 22f, hz - 22f, hz, hz), 0xFFF4EDE0.toInt())
        g.poly(floatArrayOf(51f, 53f, 39f, 37f), floatArrayOf(hz - 22f, hz - 22f, hz, hz), 0xFFF4EDE0.toInt())
        g.rect(0f, hz, WHACK_W, viewH - hz, GRASS)
        // Fence along the horizon.
        g.rect(0f, hz - 12f, WHACK_W, 3f, WOOD)
        g.rect(0f, hz - 5f, WHACK_W, 3f, WOOD)
        var fx = 90f
        while (fx < WHACK_W) { g.rect(fx, hz - 16f, 4f, 16f, WOOD_DARK); fx += 22f }

        for (i in 0 until 9) drawHole(g, i)

        for (f in feathers) {
            val a = (f.life.coerceIn(0f, 1f) * 255).toInt()
            g.oval(f.x - 3f, f.y - 1.5f, 6f, 3f, argb(a, f.color))
        }
        for (t in texts) {
            val a = (t.life.coerceIn(0f, 1f) * 255).toInt()
            g.text(t.text, t.x + 1f, t.y + 1f, t.size, argb(a, 0), 1)
            g.text(t.text, t.x, t.y, t.size, argb(a, t.color), 1)
        }
        if (hammerT > 0f && state == State.PLAYING) drawHammer(g)
    }

    private fun drawHole(g: Gfx, i: Int) {
        val cx = holeX(i)
        val y = holeY(i)
        g.oval(cx - 33f, y - 12f, 66f, 25f, DIRT)
        g.oval(cx - 25f, y - 8f, 50f, 15f, 0xFF2A1A0E.toInt())
        val p = if (state == State.TITLE) titlePopper(i) else poppers[i]
        if (p != null) {
            val h = heightOf(p.kind)
            val top = y + 6f - p.rise * (h + 6f)
            val wobble = if (p.phase == Phase.HIT) sin(p.t * 40f) * 2f * (1f - min(1f, p.t / HIT_TIME)) else 0f
            if (p.kind == Kind.CHICKEN) {
                drawChicken(g, cx + wobble, top, p.phase == Phase.HIT)
            } else {
                drawChickenMan(g, cx + wobble, top, p.kind == Kind.GOLDEN, p.phase == Phase.HIT)
            }
            // Hide whatever is still down in the hole, then draw the hole's front lip.
            g.rect(cx - 36f, y + 4f, 72f, h + 16f, GRASS)
            frontLip(g, cx, y)
            if (p.phase == Phase.HIT && p.kind != Kind.CHICKEN) drawStars(g, cx, top - 2f)
        }
    }

    /** The near half of the dirt ring, which covers the bottom of whatever pops up. */
    private fun frontLip(g: Gfx, cx: Float, y: Float) {
        val n = 12
        val xs = FloatArray(2 * n + 2)
        val ys = FloatArray(2 * n + 2)
        for (k in 0..n) {
            val a = PI.toFloat() * k / n
            xs[k] = cx + cos(a) * 33f
            ys[k] = y + 0.5f + sin(a) * 12.5f
            xs[2 * n + 1 - k] = cx + cos(a) * 25f
            ys[2 * n + 1 - k] = y - 0.5f + sin(a) * 7.5f
        }
        g.poly(xs, ys, DIRT)
    }

    private fun titlePopper(i: Int): Popper? {
        if (i != 4) return null
        val cycle = time % 2.4f
        return Popper(4, Kind.MAN, 1f).apply {
            phase = Phase.UP
            rise = when {
                cycle < 0.2f -> cycle / 0.2f
                cycle < 1.9f -> 1f
                cycle < 2.1f -> 1f - (cycle - 1.9f) / 0.2f
                else -> 0f
            }
        }
    }

    /** A man in a chicken suit: the suit's hood has a comb, googly eyes and a beak, and his face peeks out. */
    private fun drawChickenMan(g: Gfx, cx: Float, top: Float, golden: Boolean, dazed: Boolean) {
        val suit = if (golden) GOLD else 0xFFFAFAF5.toInt()
        val shade = if (golden) 0xFFE0A800.toInt() else 0xFFDCDCD2.toInt()
        val flap = if (dazed) 6f else sin(time * 14f) * 4f
        // Wings (his arms in the suit), flapping.
        g.poly(
            floatArrayOf(cx - 18f, cx - 32f, cx - 26f, cx - 16f),
            floatArrayOf(top + 34f, top + 28f + flap, top + 46f + flap, top + 52f), shade,
        )
        g.poly(
            floatArrayOf(cx + 18f, cx + 32f, cx + 26f, cx + 16f),
            floatArrayOf(top + 34f, top + 28f + flap, top + 46f + flap, top + 52f), shade,
        )
        g.oval(cx - 20f, top + 28f, 40f, 44f, suit)
        g.oval(cx - 12f, top + 40f, 24f, 22f, shade)
        g.oval(cx - 10f, top + 38f, 20f, 18f, suit)
        // Hood with a red comb.
        g.oval(cx - 9f, top - 4f, 8f, 9f, RED)
        g.oval(cx - 4f, top - 7f, 9f, 10f, RED)
        g.oval(cx + 2f, top - 4f, 8f, 9f, RED)
        g.oval(cx - 18f, top, 36f, 36f, suit)
        // The costume's googly eyes and beak, above his real face.
        g.oval(cx - 12f, top + 3f, 9f, 9f, 0xFFFFFFFF.toInt())
        g.oval(cx + 3f, top + 3f, 9f, 9f, 0xFFFFFFFF.toInt())
        val look = if (dazed) 0f else sin(time * 3f) * 1.5f
        g.oval(cx - 9.5f + look, top + 5.5f, 4f, 4f, 0xFF111111.toInt())
        g.oval(cx + 5.5f + look, top + 5.5f, 4f, 4f, 0xFF111111.toInt())
        // His face in the hood opening.
        g.oval(cx - 11f, top + 12f, 22f, 21f, SKIN)
        g.poly(floatArrayOf(cx - 6f, cx + 6f, cx), floatArrayOf(top + 10f, top + 10f, top + 17f), BEAK)
        if (dazed) {
            cross(g, cx - 5f, top + 21f)
            cross(g, cx + 5f, top + 21f)
            g.oval(cx - 2.5f, top + 27.5f, 5f, 5f, 0xFF5A1F1F.toInt())
        } else {
            g.rect(cx - 8f, top + 17.5f, 5f, 1.5f, HAIR)
            g.rect(cx + 3f, top + 17.5f, 5f, 1.5f, HAIR)
            g.oval(cx - 7f, top + 20f, 3f, 3f, 0xFF222222.toInt())
            g.oval(cx + 4f, top + 20f, 3f, 3f, 0xFF222222.toInt())
            g.oval(cx - 1.5f, top + 22f, 3f, 3f, 0xFFDDA27A.toInt())
            g.oval(cx - 4f, top + 28f, 8f, 3f, 0xFF7A2A2A.toInt())
        }
        // Big mustache, and the costume's red wattle under his chin.
        g.oval(cx - 8f, top + 25f, 8f, 3.5f, HAIR)
        g.oval(cx, top + 25f, 8f, 3.5f, HAIR)
        g.oval(cx - 3f, top + 32f, 6f, 7f, RED)
    }

    private fun drawChicken(g: Gfx, cx: Float, top: Float, ruffled: Boolean) {
        val white = 0xFFFFFFFF.toInt()
        g.poly(floatArrayOf(cx - 10f, cx - 20f, cx - 16f), floatArrayOf(top + 22f, top + 10f, top + 26f), 0xFFE8E8E8.toInt())
        g.oval(cx - 15f, top + 16f, 30f, 30f, white)
        g.oval(cx - 11f, top + 24f, 16f, 12f, 0xFFE8E8E8.toInt())
        g.oval(cx - 1f, top + 4f, 7f, 7f, RED)
        g.oval(cx + 4f, top + 3f, 7f, 8f, RED)
        g.oval(cx - 3f, top + 6f, 18f, 18f, white)
        g.poly(floatArrayOf(cx + 14f, cx + 22f, cx + 14f), floatArrayOf(top + 11f, top + 14f, top + 17f), BEAK)
        g.oval(cx + 11f, top + 17f, 4f, 6f, RED)
        if (ruffled) cross(g, cx + 8f, top + 12f, 0xFF111111.toInt())
        else g.oval(cx + 7f, top + 10f, 3.5f, 3.5f, 0xFF111111.toInt())
    }

    private fun cross(g: Gfx, x: Float, y: Float, color: Int = 0xFF222222.toInt()) {
        rotRect(g, x, y, 6f, 1.6f, 0.785f, color)
        rotRect(g, x, y, 6f, 1.6f, -0.785f, color)
    }

    private fun drawStars(g: Gfx, cx: Float, y: Float) {
        for (k in 0 until 3) {
            val a = time * 6f + k * 2.094f
            star(g, cx + cos(a) * 16f, y + sin(a) * 4f, 4f, 0xFFFFE14D.toInt())
        }
    }

    private fun star(g: Gfx, x: Float, y: Float, r: Float, color: Int) {
        val xs = FloatArray(10)
        val ys = FloatArray(10)
        for (k in 0 until 10) {
            val a = -PI.toFloat() / 2f + k * PI.toFloat() / 5f
            val rr = if (k % 2 == 0) r else r * 0.45f
            xs[k] = x + cos(a) * rr
            ys[k] = y + sin(a) * rr
        }
        g.poly(xs, ys, color)
    }

    /** A rubber mallet that swings down onto the tap. */
    private fun drawHammer(g: Gfx) {
        val swing = (hammerT / HAMMER_TIME).coerceIn(0f, 1f)
        val ang = -0.9f * (swing - 0.5f).coerceAtLeast(0f) * 2f - 0.15f
        // The handle pivots around a point below and to the right of the tap.
        val px = hammerX + 30f
        val py = hammerY + 34f
        val dx = hammerX - px
        val dy = hammerY - py
        val c = cos(ang)
        val s = sin(ang)
        val hx = px + dx * c - dy * s
        val hy = py + dx * s + dy * c
        val handleAng = kotlin.math.atan2(hy - py, hx - px)
        val mx = (px + hx) / 2f
        val my = (py + hy) / 2f
        rotRect(g, mx, my, 44f, 5f, handleAng, WOOD)
        rotRect(g, hx, hy, 14f, 26f, handleAng, 0xFFD8312A.toInt())
        rotRect(g, hx, hy, 4f, 26f, handleAng, 0xFF8E1E1A.toInt())
        if (swing < 0.5f) star(g, hammerX, hammerY, 10f * (1f - swing), argb(200, 0xFFFFFF))
    }

    private fun rotRect(g: Gfx, cx: Float, cy: Float, w: Float, h: Float, ang: Float, color: Int) {
        val c = cos(ang)
        val s = sin(ang)
        val hw = w / 2f
        val hh = h / 2f
        val xs = FloatArray(4)
        val ys = FloatArray(4)
        val ox = floatArrayOf(-hw, hw, hw, -hw)
        val oy = floatArrayOf(-hh, -hh, hh, hh)
        for (k in 0 until 4) {
            xs[k] = cx + ox[k] * c - oy[k] * s
            ys[k] = cy + ox[k] * s + oy[k] * c
        }
        g.poly(xs, ys, color)
    }

    private fun drawHud(g: Gfx) {
        g.rect(0f, 0f, WHACK_W, 34f, 0x66000000)
        g.shadowText("SCORE", 8f, 13f, 8f, 0xFFFFE14D.toInt())
        g.shadowText("$score", 8f, 28f, 14f, 0xFFFFFFFF.toInt())
        g.shadowText("BEST ${maxOf(highScores[0], score)}", WHACK_W / 2f, 13f, 8f, 0xFFFFE14D.toInt(), 1)
        if (multiplier > 1) g.shadowText("COMBO x$multiplier", WHACK_W / 2f, 28f, 11f, 0xFFFF9E3D.toInt(), 1)
        val secs = kotlin.math.ceil(timeLeft).toInt()
        val urgent = timeLeft <= 10f
        g.shadowText("TIME", WHACK_W - 8f, 13f, 8f, 0xFFFFE14D.toInt(), 2)
        val pulse = if (urgent && (time * 4f).toInt() % 2 == 0) 16f else 14f
        g.shadowText("$secs", WHACK_W - 8f, 28f, pulse, if (urgent) 0xFFFF5A4E.toInt() else 0xFFFFFFFF.toInt(), 2)
        // Time bar.
        g.rect(0f, 34f, WHACK_W, 3f, 0x55000000)
        g.rect(0f, 34f, WHACK_W * timeLeft / ROUND_TIME, 3f, if (urgent) 0xFFFF5A4E.toInt() else 0xFF7CE36B.toInt())
    }

    private fun panel(g: Gfx, y: Float, h: Float) {
        g.rect(14f, y, WHACK_W - 28f, h, 0xCC1B1030.toInt())
        g.rect(14f, y, WHACK_W - 28f, 2f, 0xFFFFE14D.toInt())
        g.rect(14f, y + h - 2f, WHACK_W - 28f, 2f, 0xFFFFE14D.toInt())
    }

    private fun drawScores(g: Gfx, y: Float, highlight: Int) {
        g.shadowText("HIGH SCORES", WHACK_W / 2f, y, 10f, 0xFFFFE14D.toInt(), 1)
        for (i in 0 until SCORE_SLOTS) {
            val yy = y + 16f + i * 13f
            val color = if (i == highlight && (time * 5f).toInt() % 2 == 0) 0xFFFF9E3D.toInt() else 0xFFFFFFFF.toInt()
            g.shadowText("${i + 1}.", 70f, yy, 10f, color)
            g.shadowText(if (highScores[i] > 0) "${highScores[i]}" else "---", 170f, yy, 10f, color, 2)
        }
    }

    private fun drawTitle(g: Gfx) {
        panel(g, 26f, 150f)
        g.shadowText("WHACK THE", WHACK_W / 2f, 52f, 16f, 0xFFFFFFFF.toInt(), 1)
        g.shadowText("CHICKEN MAN!", WHACK_W / 2f, 74f, 20f, 0xFFFFE14D.toInt(), 1)
        drawScores(g, 96f, -1)
        if ((time * 2f).toInt() % 2 == 0) {
            g.shadowText("TAP TO PLAY", WHACK_W / 2f, maxOf(horizon + 30f, 194f), 14f, 0xFFFFFFFF.toInt(), 1)
        }
        g.shadowText("Don't bonk the real chickens!", WHACK_W / 2f, viewH - 8f, 8f, 0xFFFFFFFF.toInt(), 1)
    }

    private fun drawGameOver(g: Gfx) {
        panel(g, 26f, 186f)
        g.shadowText("TIME'S UP!", WHACK_W / 2f, 50f, 18f, 0xFFFFFFFF.toInt(), 1)
        g.shadowText("$score", WHACK_W / 2f, 78f, 22f, 0xFFFFE14D.toInt(), 1)
        if (newRank == 0) {
            if ((time * 4f).toInt() % 2 == 0) {
                g.shadowText("NEW HIGH SCORE!", WHACK_W / 2f, 96f, 12f, 0xFFFF9E3D.toInt(), 1)
            }
        } else {
            g.shadowText("Bonks: $hits   Best combo: $bestCombo", WHACK_W / 2f, 96f, 8f, 0xFFDDDDDD.toInt(), 1)
        }
        drawScores(g, 118f, newRank)
        if (stateTime > 1.2f && (time * 2f).toInt() % 2 == 0) {
            g.shadowText("TAP TO PLAY AGAIN", WHACK_W / 2f, 202f, 12f, 0xFFFFFFFF.toInt(), 1)
        }
    }

    companion object {
        const val ROUND_TIME = 60f
        const val CHICKEN_PENALTY = 5f
        const val SCORE_SLOTS = 5
        private const val KEY = "whack_hs"
        private const val RISE_TIME = 0.12f
        private const val HIT_TIME = 0.45f
        private const val HAMMER_TIME = 0.18f

        private const val GRASS = 0xFF5DBB46.toInt()
        private const val DIRT = 0xFF8A5A33.toInt()
        private const val WOOD = 0xFFA0703F.toInt()
        private const val WOOD_DARK = 0xFF7A5230.toInt()
        private const val GOLD = 0xFFFFD23F.toInt()
        private const val RED = 0xFFE53935.toInt()
        private const val BEAK = 0xFFFF9800.toInt()
        private const val SKIN = 0xFFF2C29B.toInt()
        private const val HAIR = 0xFF4A2C14.toInt()

        private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
    }
}
