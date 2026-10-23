package com.bouzoukihero.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Sounds and results the platform layer handles. */
interface SessionListener {
    fun miss() {}
    fun tick() {}

    /** Called once when the song ends (or the player taps DONE while recording). */
    fun finished(session: Session) {}
}

/**
 * One play of one song: notes coming down the neck, taps, scoring, and the screens
 * drawn on top (pause, results). In [record] mode there are no notes; the player taps
 * along to the video and the taps become the song's chart.
 *
 * Times are song seconds (the audio's or the video's own clock). The host calls
 * [update] every frame with the current song time, and [press]/[release] when a
 * finger goes down or comes up, with the song time of that moment.
 */
class Session(
    val title: String,
    val difficulty: Difficulty,
    val gems: List<Gem>,
    val record: Boolean = false,
    val hasVideo: Boolean = false,
    val tempo: Double = 0.0,
    val bestScore: Int = 0,
    var listener: SessionListener = object : SessionListener {},
) {
    enum class State { LOADING, PLAYING, PAUSED, FINISHED, ERROR }
    enum class Action { NONE, PAUSE, RESUME, RESTART, QUIT, OPA, DONE, PLAY_CHART }

    class RecNote(val time: Double, val lane: Int) {
        var end = -1.0
    }

    val lanes = if (record) 5 else difficulty.lanes

    /** Virtual screen height; the width is always [VIEW_W]. */
    var viewH = 640f

    /** Height of the video at the top of the screen, in virtual pixels. */
    var topInset = 0f

    @Volatile var state = State.PLAYING
    @Volatile var message = ""

    var time = 0.0
        private set
    var score = 0
        private set
    var combo = 0
        private set
    var maxCombo = 0
        private set
    val counts = IntArray(Judgement.entries.size)

    /** Fills as you hit notes; spend it on OPA! for double points. */
    var opaMeter = 0f
        private set
    private var opaUntil = -1.0
    val opaActive get() = time < opaUntil

    /** False after a miss, until the next hit: the built-in songs mute the bouzouki. */
    @Volatile var leadAudible = true
        private set

    val recorded = ArrayList<RecNote>()
    private val pressed = BooleanArray(5)
    private val pressFlash = FloatArray(5)
    private var holdPoints = 0.0
    private var lastJudgement: Judgement? = null
    private var judgementAge = 99f
    private var streakText = ""
    private var streakAge = 99f
    private var opaAge = 99f
    private var anim = 0f
    private var nextMiss = 0
    private val lastTime = Charts.lastTime(gems)
    private val rnd = Random(7)

    private class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        var life: Float, val maxLife: Float, val color: Int, val size: Float, val plate: Boolean,
    )
    private val particles = ArrayList<Particle>()

    private class Button(val x: Float, val y: Float, val w: Float, val h: Float, val action: Action)
    private val buttons = ArrayList<Button>()

    val multiplier get() = (1 + min(3, combo / 10)) * (if (opaActive) 2 else 1)

    val judgedCount get() = counts.sum()

    /** 0..1: points earned against a perfect run. */
    val accuracy: Double
        get() {
            if (gems.isEmpty()) return 0.0
            val pts = Judgement.entries.sumOf { it.points * counts[it.ordinal] }
            return pts / (100.0 * gems.size)
        }

    val stars: Int
        get() = when {
            accuracy >= 0.9 -> 5
            accuracy >= 0.78 -> 4
            accuracy >= 0.6 -> 3
            accuracy >= 0.4 -> 2
            else -> 1
        }

    @Synchronized fun restart() {
        for (g in gems) {
            g.judged = null; g.holding = false; g.holdBroken = false; g.holdDone = false; g.holdTo = 0.0
        }
        score = 0; combo = 0; maxCombo = 0; counts.fill(0); opaMeter = 0f; opaUntil = -1.0
        holdPoints = 0.0; leadAudible = true; recorded.clear(); particles.clear()
        lastJudgement = null; nextMiss = 0; time = 0.0
        state = State.PLAYING
    }

    // ---- Input ----

    /** Which lane a touch at (x, y) presses, or -1 if it's not on the buttons. */
    fun laneAt(x: Float, y: Float): Int {
        val l = layout()
        if (y < l.hitY - 80 || state != State.PLAYING) return -1
        val i = floor((x - (VIEW_W / 2 - l.width / 2)) / l.laneW).toInt()
        return i.coerceIn(0, lanes - 1)
    }

    @Synchronized fun press(lane: Int, t: Double) {
        if (state != State.PLAYING || lane !in 0 until lanes) return
        pressed[lane] = true
        pressFlash[lane] = 1f
        if (record) {
            recorded.add(RecNote(t, lane))
            listener.tick()
            return
        }
        val w = difficulty.window
        var best: Gem? = null
        for (i in nextMiss until gems.size) {
            val g = gems[i]
            if (g.time > t + w) break
            if (g.lane != lane || g.judged != null) continue
            if (abs(g.time - t) <= w) { best = g; break }
        }
        val g = best ?: return
        val d = abs(g.time - t)
        val j = when {
            d <= w * 0.4 -> Judgement.PERFECT
            d <= w * 0.7 -> Judgement.GREAT
            else -> Judgement.GOOD
        }
        judge(g, j)
        if (g.isHold) {
            g.holding = true
            g.holdTo = t
        }
    }

    @Synchronized fun release(lane: Int, t: Double) {
        if (lane !in 0 until 5) return
        pressed[lane] = false
        if (record) {
            recorded.lastOrNull { it.lane == lane && it.end < 0 }?.end = t
            return
        }
        for (g in gems) {
            if (g.lane == lane && g.holding) {
                g.holding = false
                g.holdTo = t
                if (t >= g.end - 0.1) completeHold(g) else g.holdBroken = true
            }
        }
    }

    /** Lets go of everything, e.g. when the game is paused. */
    @Synchronized fun releaseAll(t: Double) {
        for (l in 0 until 5) if (pressed[l]) release(l, t)
    }

    @Synchronized fun tap(x: Float, y: Float): Action {
        for (b in buttons) {
            if (x >= b.x && x <= b.x + b.w && y >= b.y && y <= b.y + b.h) {
                if (b.action == Action.OPA) activateOpa()
                return b.action
            }
        }
        return Action.NONE
    }

    private fun activateOpa() {
        if (opaMeter < 0.5f || opaActive || state != State.PLAYING) return
        opaUntil = time + opaMeter * OPA_SECONDS
        opaAge = 0f
        // Smash some plates!
        for (i in 0 until 14) {
            val fromLeft = i % 2 == 0
            particles.add(
                Particle(
                    if (fromLeft) -10f else VIEW_W + 10f, layout().hitY - 120 - rnd.nextFloat() * 160,
                    (if (fromLeft) 1 else -1) * (140f + rnd.nextFloat() * 160), -120f - rnd.nextFloat() * 160,
                    1.4f, 1.4f, 0xFFFFFFFF.toInt(), 9f + rnd.nextFloat() * 6, plate = true,
                )
            )
        }
    }

    private fun judge(g: Gem, j: Judgement) {
        g.judged = j
        counts[j.ordinal]++
        lastJudgement = j
        judgementAge = 0f
        if (j == Judgement.MISS) {
            if (combo >= 10) {
                streakText = "STREAK LOST"
                streakAge = 0f
            }
            combo = 0
            leadAudible = false
            listener.miss()
            return
        }
        combo++
        maxCombo = max(maxCombo, combo)
        leadAudible = true
        score += j.points * multiplier
        if (!opaActive) opaMeter = min(1f, opaMeter + if (j == Judgement.PERFECT) 0.022f else 0.012f)
        if (combo % 50 == 0) {
            streakText = "$combo NOTE STREAK!"
            streakAge = 0f
        }
        sparks(g.lane, laneColor(g.lane), if (j == Judgement.PERFECT) 12 else 7)
    }

    private fun completeHold(g: Gem) {
        if (g.holdDone) return
        g.holdDone = true
        g.holding = false
        g.holdTo = g.end
        score += 50 * multiplier
        if (!opaActive) opaMeter = min(1f, opaMeter + 0.03f)
        sparks(g.lane, 0xFFFFFFFF.toInt(), 10)
    }

    // ---- Simulation ----

    @Synchronized fun update(t: Double, dt: Float) {
        anim += dt
        judgementAge += dt
        streakAge += dt
        opaAge += dt
        for (i in 0 until 5) pressFlash[i] = max(0f, pressFlash[i] - dt * 4)
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0) {
                if (p.plate) shatter(p)
                it.remove()
                continue
            }
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.vy += (if (p.plate) 420f else 300f) * dt
        }
        if (state != State.PLAYING) return
        val dtSong = (t - time).coerceIn(0.0, 0.25)
        time = t

        if (opaActive) opaMeter = ((opaUntil - t) / OPA_SECONDS).toFloat().coerceIn(0f, 1f)
        else if (opaUntil > 0) { opaUntil = -1.0; opaMeter = 0f }

        if (record) return

        val w = difficulty.window
        while (nextMiss < gems.size && (gems[nextMiss].judged != null || gems[nextMiss].time < t - w)) {
            val g = gems[nextMiss]
            if (g.judged == null) judge(g, Judgement.MISS)
            nextMiss++
        }
        for (i in max(0, nextMiss - 40) until gems.size) {
            val g = gems[i]
            if (g.time > t) break
            if (!g.holding) continue
            if (t >= g.end) completeHold(g)
            else {
                g.holdTo = t
                holdPoints += dtSong * 30 * multiplier
                val whole = holdPoints.toInt()
                score += whole
                holdPoints -= whole
                if (rnd.nextFloat() < dt * 25) sparks(g.lane, laneColor(g.lane), 1)
            }
        }
        if (gems.isNotEmpty() && t > lastTime + 2.0) finish()
    }

    @Synchronized fun finish() {
        if (state == State.FINISHED) return
        if (record) {
            for (r in recorded) if (r.end < 0) r.end = time
        }
        releaseAll(time)
        state = State.FINISHED
        listener.finished(this)
    }

    /** The recorded taps as a melody (each lane stands in for a pitch). */
    fun recordedNotes(): List<SourceNote> = recorded.sortedBy { it.time }.map {
        val held = it.end - it.time
        SourceNote(it.time, if (held >= 0.35) held else 0.1, it.lane)
    }

    private fun sparks(lane: Int, color: Int, n: Int) {
        val l = layout()
        val x = l.laneX(lane, 1f)
        repeat(n) {
            val a = rnd.nextFloat() * PI.toFloat()
            val s = 80f + rnd.nextFloat() * 160
            particles.add(Particle(x, l.hitY, cos(a) * s, -sin(a) * s, 0.45f, 0.45f, color, 2.5f + rnd.nextFloat() * 2, false))
        }
    }

    private fun shatter(p: Particle) {
        repeat(6) {
            val a = rnd.nextFloat() * 2 * PI.toFloat()
            particles.add(
                Particle(p.x, p.y, cos(a) * 120 + p.vx * 0.3f, sin(a) * 120 - 60, 0.6f, 0.6f,
                    if (it % 2 == 0) 0xFFFFFFFF.toInt() else 0xFF2F6FD6.toInt(), 3f, false)
            )
        }
    }

    // ---- Layout ----

    private inner class Layout {
        val top = topInset
        val hitY = viewH - 128f
        val laneW = min(96f, 340f / lanes)
        val width = laneW * lanes
        val farY = top + 58f
        /** Vanishing point: where the neck would meet the horizon. */
        val vy = (farY - hitY * P_FAR) / (1 - P_FAR)

        fun yAt(p: Float) = vy + (hitY - vy) * p
        fun laneX(lane: Int, p: Float) = VIEW_W / 2 + ((lane + 0.5f) * laneW - width / 2) * p
        fun edgeX(side: Float, p: Float) = VIEW_W / 2 + side * width / 2 * p
        fun pOf(u: Double): Float = (1.0 / (1.0 + (1 / P_FAR - 1) * u)).toFloat()
    }

    private fun layout() = Layout()

    // ---- Drawing ----

    @Synchronized fun render(g: Gfx) {
        buttons.clear()
        val l = layout()
        background(g, l)
        neck(g, l)
        if (record) recordedTaps(g, l) else notes(g, l)
        targets(g, l)
        pads(g, l)
        effects(g, l)
        hud(g, l)
        when (state) {
            State.PAUSED -> pauseScreen(g, l)
            State.FINISHED -> if (record) recordDone(g, l) else results(g, l)
            State.LOADING -> notice(g, l, message, false)
            State.ERROR -> notice(g, l, message, true)
            State.PLAYING -> {}
        }
    }

    private fun background(g: Gfx, l: Layout) {
        g.rect(0f, 0f, VIEW_W, viewH, 0xFF0A1530.toInt())
        val bands = 24
        val h = (viewH - l.top) / bands
        for (i in 0 until bands) {
            g.rect(0f, l.top + i * h, VIEW_W, h + 1, mix(0xFF0B1D45.toInt(), 0xFF22103A.toInt(), i / (bands - 1f)))
        }
        if (!hasVideo) {
            // Taverna string lights.
            for (row in 0..1) {
                val baseY = 18f + row * 34
                for (i in 0..12) {
                    val x = i * 30f + row * 15f
                    val sag = sin(i / 12f * PI.toFloat()) * 12
                    val c = LIGHTS[(i + row) % LIGHTS.size]
                    val tw = 0.6f + 0.4f * sin(anim * 2f + i * 1.7f + row)
                    g.circle(x, baseY + sag, 6f, argb((50 * tw).toInt(), c))
                    g.circle(x, baseY + sag, 2.5f, argb(255, c))
                }
            }
        }
        if (opaActive) {
            val a = (90 + 60 * sin(anim * 10)).toInt()
            g.poly(
                floatArrayOf(l.edgeX(-1f, P_FAR) - 6, l.edgeX(-1f, P_FAR), l.edgeX(-1f, 1.1f), l.edgeX(-1f, 1.1f) - 22),
                floatArrayOf(l.farY, l.farY, l.yAt(1.1f), l.yAt(1.1f)), argb(a, 0x56A8FF),
            )
            g.poly(
                floatArrayOf(l.edgeX(1f, P_FAR), l.edgeX(1f, P_FAR) + 6, l.edgeX(1f, 1.1f) + 22, l.edgeX(1f, 1.1f)),
                floatArrayOf(l.farY, l.farY, l.yAt(1.1f), l.yAt(1.1f)), argb(a, 0x56A8FF),
            )
        }
    }

    private fun neck(g: Gfx, l: Layout) {
        val pNear = 1.12f
        val yNear = l.yAt(pNear)
        // Rosewood fretboard with bone binding.
        g.poly(
            floatArrayOf(l.edgeX(-1f, P_FAR) - 3, l.edgeX(1f, P_FAR) + 3, l.edgeX(1f, pNear) + 6, l.edgeX(-1f, pNear) - 6),
            floatArrayOf(l.farY, l.farY, yNear, yNear), 0xFFE9DFC6.toInt(),
        )
        g.poly(
            floatArrayOf(l.edgeX(-1f, P_FAR), l.edgeX(1f, P_FAR), l.edgeX(1f, pNear), l.edgeX(-1f, pNear)),
            floatArrayOf(l.farY, l.farY, yNear, yNear), 0xFF3A2216.toInt(),
        )
        // Frets, one per beat, sliding toward you.
        val beat = if (tempo in 50.0..240.0) 60.0 / tempo else 0.5
        val la = difficulty.lookahead
        var k = ceil((time - 0.1 * la) / beat).toLong()
        while (k * beat <= time + la) {
            val u = (k * beat - time) / la
            val p = l.pOf(u)
            if (p <= pNear) {
                val y = l.yAt(p)
                g.line(l.edgeX(-1f, p), y, l.edgeX(1f, p), y, 1f + 2.2f * p, argb((90 + 120 * p).toInt().coerceAtMost(255), 0xC9CDD6))
                if (Math.floorMod(k, 4L) == 2L) {
                    val p2 = l.pOf(u + beat / la / 2)
                    g.circle(VIEW_W / 2, l.yAt((p + p2) / 2), 5.5f * p, argb((140 * p).toInt().coerceAtMost(255), 0xF2EEE2))
                }
            }
            k++
        }
        // A pair of strings down every lane, like a bouzouki's paired courses.
        for (lane in 0 until lanes) {
            val lit = pressed[lane] || pressFlash[lane] > 0
            val c = if (lit) laneColor(lane) else 0xFFCFCFD4.toInt()
            for (s in -1..1 step 2) {
                val off = s * 2.6f
                g.line(l.laneX(lane, P_FAR) + off * P_FAR, l.farY, l.laneX(lane, pNear) + off * pNear, yNear,
                    if (lit) 1.8f else 1.1f, argb(if (lit) 230 else 130, c))
            }
        }
        // Fade the far end into the night.
        for (i in 0 until 6) {
            val p = P_FAR + i * 0.02f
            val p2 = p + 0.02f
            g.poly(
                floatArrayOf(l.edgeX(-1f, p) - 4, l.edgeX(1f, p) + 4, l.edgeX(1f, p2) + 4, l.edgeX(-1f, p2) - 4),
                floatArrayOf(l.yAt(p), l.yAt(p), l.yAt(p2), l.yAt(p2)), argb(200 - i * 34, 0x0B1D45),
            )
        }
    }

    private fun targets(g: Gfx, l: Layout) {
        g.rect(l.edgeX(-1f, 1f) - 4, l.hitY - 2, l.width + 8, 4f, 0x99FFFFFF.toInt())
        for (lane in 0 until lanes) {
            val x = l.laneX(lane, 1f)
            val r = l.laneW * 0.36f
            val c = laneColor(lane)
            val on = pressed[lane]
            g.circle(x, l.hitY + 2, r + 3, 0xFF000000.toInt())
            g.circle(x, l.hitY, r + 2, c)
            g.circle(x, l.hitY, r - 3, if (on) mix(c, 0xFFFFFFFF.toInt(), 0.45f) else 0xFF141018.toInt())
            g.circle(x, l.hitY, r * 0.35f, if (on) 0xFFFFFFFF.toInt() else argb(110, c))
        }
    }

    private fun pads(g: Gfx, l: Layout) {
        val y = l.hitY + l.laneW * 0.36f + 12
        val h = viewH - y - 10
        if (h < 20) return
        for (lane in 0 until lanes) {
            val x = VIEW_W / 2 - l.width / 2 + lane * l.laneW
            val c = laneColor(lane)
            val on = pressed[lane]
            g.panel(x + 4, y + 3, l.laneW - 8, h, 0xFF000000.toInt(), 12f)
            g.panel(x + 4, y, l.laneW - 8, h, if (on) mix(c, 0xFFFFFFFF.toInt(), 0.3f) else mix(c, 0xFF000000.toInt(), 0.45f), 12f)
            val face = if (on) mix(c, 0xFFFFFFFF.toInt(), 0.3f) else mix(c, 0xFF000000.toInt(), 0.45f)
            g.panel(x + 10, y + 6, l.laneW - 20, h * 0.3f, mix(face, 0xFFFFFFFF.toInt(), if (on) 0.4f else 0.15f), 8f)
        }
    }

    private fun notes(g: Gfx, l: Layout) {
        val la = difficulty.lookahead
        // Far notes first, so nearer ones are drawn on top.
        var last = nextMiss
        while (last < gems.size && gems[last].time <= time + la) last++
        for (i in last - 1 downTo max(0, nextMiss - 30)) {
            val gem = gems[i]
            val hit = gem.judged != null && gem.judged != Judgement.MISS
            if (gem.isHold) tail(g, l, gem)
            if (hit) continue
            val u = (gem.time - time) / la
            if (u < -0.25 || u > 1) continue
            val p = l.pOf(u)
            val x = l.laneX(gem.lane, p)
            val y = l.yAt(p)
            pick(g, x, y, l.laneW * 0.34f * p, laneColor(gem.lane), gem.label, gem.judged == Judgement.MISS)
        }
    }

    private fun tail(g: Gfx, l: Layout, gem: Gem) {
        val la = difficulty.lookahead
        val from = when {
            gem.holding || gem.holdBroken || gem.holdDone -> gem.holdTo
            else -> gem.time
        }
        if (gem.holdDone) return
        val t0 = max(from, time - 0.05)
        val t1 = min(gem.end, time + la)
        if (t1 <= t0) return
        val c = when {
            gem.holdBroken || gem.judged == Judgement.MISS -> 0xFF5A5560.toInt()
            gem.holding -> mix(laneColor(gem.lane), 0xFFFFFFFF.toInt(), 0.35f)
            else -> laneColor(gem.lane)
        }
        val steps = max(1, ((t1 - t0) / 0.05).toInt())
        for (s in 0 until steps) {
            val a = t0 + (t1 - t0) * s / steps
            val b = t0 + (t1 - t0) * (s + 1) / steps
            val pa = l.pOf((a - time) / la)
            val pb = l.pOf((b - time) / la)
            // Tremolo notes get a wavy tail, like the shake of fast picking.
            val wa = if (gem.tremolo) sin((a * 40 + anim * 30).toFloat()) * 4f else 0f
            val wb = if (gem.tremolo) sin((b * 40 + anim * 30).toFloat()) * 4f else 0f
            val xa = l.laneX(gem.lane, pa) + wa * pa
            val xb = l.laneX(gem.lane, pb) + wb * pb
            val ha = l.laneW * 0.1f * pa
            val hb = l.laneW * 0.1f * pb
            g.poly(floatArrayOf(xa - ha, xa + ha, xb + hb, xb - hb), floatArrayOf(l.yAt(pa), l.yAt(pa), l.yAt(pb), l.yAt(pb)), c)
        }
    }

    /** A note, drawn as a plectrum (a pena) with the note's name on it. */
    private fun pick(g: Gfx, x: Float, y: Float, r: Float, color: Int, label: String, missed: Boolean) {
        val c = if (missed) 0xFF4A4550.toInt() else color
        val ry = r * 0.78f
        fun shape(dx: Float, dy: Float, grow: Float, col: Int) {
            val rr = r + grow
            val rry = ry + grow
            g.oval(x - rr + dx, y - rry + dy, 2 * rr, 2 * rry, col)
            g.poly(
                floatArrayOf(x - rr * 0.78f + dx, x + rr * 0.78f + dx, x + dx),
                floatArrayOf(y + rry * 0.25f + dy, y + rry * 0.25f + dy, y + rry * 1.75f + dy),
                col,
            )
        }
        shape(0f, r * 0.12f, 1.6f, 0xFF000000.toInt())
        shape(0f, 0f, 0f, c)
        g.oval(x - r * 0.55f, y - ry * 0.75f, r * 0.8f, ry * 0.6f, argb(if (missed) 40 else 120, 0xFFFFFF))
        if (label.isNotEmpty() && r > 7) {
            g.text(label, x, y + r * 0.42f, r * (if (label.length > 1) 0.95f else 1.15f), if (missed) 0xFF8A8590.toInt() else 0xFF1A1020.toInt(), 1)
        }
    }

    private fun recordedTaps(g: Gfx, l: Layout) {
        val la = 1.6
        for (r in recorded) {
            val age = time - r.time
            if (age < 0 || age > la) continue
            val p = l.pOf(age / la)
            pick(g, l.laneX(r.lane, p), l.yAt(p), l.laneW * 0.32f * p, laneColor(r.lane), "", false)
        }
    }

    private fun effects(g: Gfx, l: Layout) {
        for (p in particles) {
            val a = (255 * min(1f, p.life / p.maxLife * 1.5f)).toInt()
            if (p.plate) {
                val squash = 0.45f + 0.4f * abs(sin(p.life * 9))
                g.oval(p.x - p.size, p.y - p.size * squash, p.size * 2, p.size * 2 * squash, 0xFF2F6FD6.toInt())
                g.oval(p.x - p.size * 0.8f, p.y - p.size * 0.8f * squash, p.size * 1.6f, p.size * 1.6f * squash, 0xFFFFFFFF.toInt())
            } else {
                g.circle(p.x, p.y, p.size, argb(a, p.color))
            }
        }
        val j = lastJudgement
        if (j != null && judgementAge < 0.6f && !record) {
            val a = (255 * (1 - judgementAge / 0.6f)).toInt()
            g.shadowText(j.label, VIEW_W / 2, l.hitY - 70 - judgementAge * 30, 22f, argb(a, j.color), 1)
        }
        if (streakAge < 1.6f) {
            val a = (255 * min(1f, (1.6f - streakAge) * 2)).toInt()
            g.shadowText(streakText, VIEW_W / 2, l.top + 110, 20f, argb(a, 0xFFE15A), 1)
        }
        if (opaAge < 1.4f) {
            val s = 40f + opaAge * 30
            val a = (255 * min(1f, (1.4f - opaAge) * 2)).toInt()
            g.shadowText("OPA!", VIEW_W / 2, l.top + 170, s, argb(a, 0xFFFFFF), 1)
        }
    }

    private fun hud(g: Gfx, l: Layout) {
        val y = l.top + 8
        g.rect(0f, l.top, VIEW_W, 46f, 0x66000000)
        if (record) {
            val blink = sin(anim * 6) > 0
            g.circle(18f, y + 15, 7f, if (blink) 0xFFFF3B3B.toInt() else 0xFF7A1A1A.toInt())
            g.shadowText("REC", 32f, y + 21, 16f, 0xFFFFFFFF.toInt())
            g.shadowText("${recorded.size} notes", 76f, y + 21, 13f, 0xFFCCD6F6.toInt())
            button(g, VIEW_W - 136, y, 82f, 30f, "DONE", 0xFF2E9B57.toInt(), Action.DONE)
        } else {
            g.shadowText("%,d".format(score), 10f, y + 20, 20f, 0xFFFFFFFF.toInt())
            g.shadowText(if (combo > 0) "$combo combo" else difficulty.label, 10f, y + 36, 11f, 0xFFB9C6EE.toInt())
            // Multiplier badge.
            val m = multiplier
            val mc = when {
                opaActive -> 0xFF56A8FF.toInt()
                m >= 4 -> 0xFFB46CFF.toInt()
                m == 3 -> 0xFF46D07A.toInt()
                m == 2 -> 0xFFFFC23D.toInt()
                else -> 0xFF808AA8.toInt()
            }
            g.circle(150f, y + 15, 16f, 0xFF000000.toInt())
            g.circle(150f, y + 15, 14f, mc)
            g.text("x$m", 150f, y + 20, 13f, 0xFF10101A.toInt(), 1)
            // OPA meter: also the button that sets it off.
            val mx = 176f
            val mw = 98f
            val ready = opaMeter >= 0.5f && !opaActive
            g.panel(mx, y + 4, mw, 24f, 0xFF000000.toInt(), 8f)
            if (opaMeter > 0.02f) {
                val fill = if (opaActive) 0xFF56A8FF.toInt() else if (ready) mix(0xFF56A8FF.toInt(), 0xFFFFFFFF.toInt(), 0.5f + 0.5f * sin(anim * 8)) else 0xFF2F6FD6.toInt()
                g.panel(mx + 2, y + 6, max(16f, (mw - 4) * opaMeter), 20f, fill, 7f)
            }
            g.text(if (ready) "TAP: OPA!" else "OPA", mx + mw / 2, y + 21, 12f, 0xFFFFFFFF.toInt(), 1)
            buttons.add(Button(mx, y, mw, 32f, Action.OPA))
        }
        button(g, VIEW_W - 46, y, 38f, 30f, "II", 0xFF39406A.toInt(), Action.PAUSE)
    }

    private fun button(g: Gfx, x: Float, y: Float, w: Float, h: Float, label: String, color: Int, action: Action) {
        g.panel(x, y + 2, w, h, 0xFF000000.toInt(), 9f)
        g.panel(x, y, w, h, color, 9f)
        g.text(label, x + w / 2, y + h / 2 + 5, 14f, 0xFFFFFFFF.toInt(), 1)
        buttons.add(Button(x, y, w, h, action))
    }

    private fun dim(g: Gfx, l: Layout) {
        buttons.clear()
        g.rect(0f, l.top, VIEW_W, viewH - l.top, 0xC0050814.toInt())
    }

    private fun pauseScreen(g: Gfx, l: Layout) {
        dim(g, l)
        val cy = (l.top + viewH) / 2
        g.shadowText("PAUSED", VIEW_W / 2, cy - 70, 30f, 0xFFFFFFFF.toInt(), 1)
        button(g, 90f, cy - 40, 180f, 40f, "RESUME", 0xFF2E9B57.toInt(), Action.RESUME)
        button(g, 90f, cy + 12, 180f, 40f, "RESTART", 0xFF2F6FD6.toInt(), Action.RESTART)
        button(g, 90f, cy + 64, 180f, 40f, "QUIT", 0xFF8A2F3A.toInt(), Action.QUIT)
    }

    private fun notice(g: Gfx, l: Layout, text: String, error: Boolean) {
        if (error) dim(g, l)
        val cy = (l.top + viewH) / 2 - 40
        g.panel(24f, cy - 40, VIEW_W - 48, 92f, 0xE0101830.toInt(), 14f)
        var yy = cy - 12
        for (line in wrap(text, 34)) {
            g.text(line, VIEW_W / 2, yy, 13f, 0xFFFFFFFF.toInt(), 1)
            yy += 18
        }
        if (error) button(g, 110f, cy + 66, 140f, 40f, "BACK", 0xFF8A2F3A.toInt(), Action.QUIT)
    }

    private fun results(g: Gfx, l: Layout) {
        dim(g, l)
        val x = 24f
        val w = VIEW_W - 48
        val y0 = max(l.top + 12, (l.top + viewH) / 2 - 230)
        g.panel(x, y0, w, 440f, 0xF0121A3A.toInt(), 16f)
        g.text(if (title.length > 28) title.take(27) + "…" else title, VIEW_W / 2, y0 + 30, 15f, 0xFFFFFFFF.toInt(), 1)
        g.text(difficulty.label.uppercase(), VIEW_W / 2, y0 + 48, 11f, 0xFF9FB0E0.toInt(), 1)
        for (i in 0 until 5) {
            star(g, VIEW_W / 2 + (i - 2) * 46, y0 + 86, 19f, if (i < stars) 0xFFFFD23F.toInt() else 0xFF3A4060.toInt())
        }
        g.shadowText("%,d".format(score), VIEW_W / 2, y0 + 148, 32f, 0xFFFFFFFF.toInt(), 1)
        if (score > bestScore && score > 0) {
            g.text(if (bestScore > 0) "NEW BEST!" else "FIRST CLEAR!", VIEW_W / 2, y0 + 170, 13f, 0xFFFFD23F.toInt(), 1)
        } else if (bestScore > 0) {
            g.text("Best: %,d".format(bestScore), VIEW_W / 2, y0 + 170, 12f, 0xFF9FB0E0.toInt(), 1)
        }
        val rows = listOf(
            "Accuracy" to "${(accuracy * 100).toInt()}%",
            "Best streak" to "$maxCombo",
            "Perfect" to "${counts[Judgement.PERFECT.ordinal]}",
            "Great" to "${counts[Judgement.GREAT.ordinal]}",
            "Good" to "${counts[Judgement.GOOD.ordinal]}",
            "Missed" to "${counts[Judgement.MISS.ordinal]}",
        )
        var yy = y0 + 202
        for ((k, v) in rows) {
            g.text(k, x + 30, yy, 14f, 0xFFB9C6EE.toInt())
            g.text(v, x + w - 30, yy, 14f, 0xFFFFFFFF.toInt(), 2)
            yy += 24
        }
        button(g, x + 20, y0 + 380, w / 2 - 30, 42f, "AGAIN", 0xFF2F6FD6.toInt(), Action.RESTART)
        button(g, x + w / 2 + 10, y0 + 380, w / 2 - 30, 42f, "SONGS", 0xFF2E9B57.toInt(), Action.QUIT)
    }

    private fun recordDone(g: Gfx, l: Layout) {
        dim(g, l)
        val y0 = (l.top + viewH) / 2 - 120
        g.panel(24f, y0, VIEW_W - 48, 230f, 0xF0121A3A.toInt(), 16f)
        g.shadowText("CHART SAVED", VIEW_W / 2, y0 + 44, 22f, 0xFFFFFFFF.toInt(), 1)
        g.text("You tapped ${recorded.size} notes.", VIEW_W / 2, y0 + 80, 13f, 0xFFB9C6EE.toInt(), 1)
        g.text("Play it on any difficulty.", VIEW_W / 2, y0 + 100, 13f, 0xFFB9C6EE.toInt(), 1)
        button(g, 48f, y0 + 150, 120f, 42f, "PLAY IT", 0xFF2E9B57.toInt(), Action.PLAY_CHART)
        button(g, 192f, y0 + 150, 120f, 42f, "SONGS", 0xFF2F6FD6.toInt(), Action.QUIT)
    }

    private fun star(g: Gfx, cx: Float, cy: Float, r: Float, color: Int) {
        val xs = FloatArray(10)
        val ys = FloatArray(10)
        for (i in 0 until 10) {
            val a = -PI / 2 + i * PI / 5
            val rr = if (i % 2 == 0) r else r * 0.45f
            xs[i] = cx + (cos(a) * rr).toFloat()
            ys[i] = cy + (sin(a) * rr).toFloat()
        }
        g.poly(xs.map { it + 1.5f }.toFloatArray(), ys.map { it + 2f }.toFloatArray(), 0xFF000000.toInt())
        g.poly(xs, ys, color)
    }

    private fun wrap(text: String, width: Int): List<String> {
        val out = ArrayList<String>()
        for (para in text.split('\n')) {
            var line = ""
            for (word in para.split(' ')) {
                if (line.isEmpty()) line = word
                else if (line.length + 1 + word.length <= width) line += " $word"
                else { out.add(line); line = word }
            }
            out.add(line)
        }
        return out
    }

    companion object {
        /** How long a full OPA! meter lasts. */
        const val OPA_SECONDS = 14.0
        private const val P_FAR = 0.26f

        private val LANE_COLORS = intArrayOf(
            0xFF3FD46B.toInt(), // green
            0xFFF0443F.toInt(), // red
            0xFFFFD23F.toInt(), // yellow
            0xFF3F8CF0.toInt(), // blue
            0xFFFF8E2E.toInt(), // orange
        )
        private val LIGHTS = intArrayOf(0xFFE27A, 0xFF8A5A, 0x9AD8FF, 0xFFFFFF, 0xB9FF8A)

        fun laneColor(lane: Int) = LANE_COLORS[lane.coerceIn(0, 4)]
    }
}
