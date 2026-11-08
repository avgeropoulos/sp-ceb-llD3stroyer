package com.goalrush.game

import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The whole game: menus, save slots, high scores, the update screen and the matches.
 * The platform calls [touch] from its UI thread and [update]/[render] from its game loop.
 */
class Game(
    private val sound: SoundSink = SoundSink.Silent,
    store: Storage = Storage.Memory(),
    val updater: Updater = Updater.None,
    private val rng: Random = Random.Default,
) {
    enum class Screen { TITLE, SLOTS, LEVELS, PLAY, PAUSE, RESULT, SCORES, UPDATE }

    var viewH = 760f
        set(v) {
            field = v
            match?.setupCamera(v)
            demo.setupCamera(v)
        }

    val save = SaveData(store)
    var screen = Screen.TITLE
        private set
    var slot = -1
        private set
    var match: Match? = null
        private set
    private val demo = Match(0)
    private val renderer = SceneRenderer()
    private var time = 0f
    private var screenT = 0f
    private var celebrate = 0f
    private var savedShot = -1
    private var checkedUpdates = false
    private var confirmDelete = -1
    private var updateBack = Screen.TITLE

    // Result screen.
    private var resStars = 0
    private var resNewBest = false
    private var resRank = -1
    private var resPassed = false

    private class Ev(val action: Int, val x: Float, val y: Float, val t: Float)
    private val events = ConcurrentLinkedQueue<Ev>()
    private var pressed: String? = null

    class Btn(val id: String, val x: Float, val y: Float, val w: Float, val h: Float, val label: String, val style: Int = 0) {
        fun hit(px: Float, py: Float) = px >= x && px <= x + w && py >= y && py <= y + h
    }

    // ---- input (any thread) ----

    fun touch(action: Int, x: Float, y: Float, t: Float) { events += Ev(action, x, y, t) }

    /** The Android back button. Returns false if the app should close. */
    fun back(): Boolean {
        events += Ev(BACK, 0f, 0f, 0f)
        return screen != Screen.TITLE
    }

    /** Called when the app goes to the background: keep the level so it can be resumed. */
    fun onPause() {
        val m = match ?: return
        if (screen == Screen.PLAY || screen == Screen.PAUSE) {
            if (m.phase != Match.Phase.DONE && slot >= 0) save.saveSession(slot, m.snapshot())
            if (screen == Screen.PLAY) go(Screen.PAUSE)
        }
    }

    // ---- logic ----

    private fun go(s: Screen) {
        screen = s
        screenT = 0f
        pressed = null
        confirmDelete = -1
        sound.crowd(s == Screen.PLAY || s == Screen.PAUSE)
    }

    fun update(dt: Float) {
        time += dt
        screenT += dt
        renderer.time = time
        if (!checkedUpdates) { checkedUpdates = true; updater.check() }
        while (true) handle(events.poll() ?: break)
        if (celebrate > 0f) celebrate = (celebrate - dt * 0.5f).coerceAtLeast(0f)

        when (screen) {
            Screen.PLAY -> updateMatch(dt)
            Screen.PAUSE, Screen.RESULT -> {}
            else -> demo.update(dt)
        }
    }

    private fun updateMatch(dt: Float) {
        val m = match ?: return
        val before = m.phase
        m.update(dt)
        if (before == Match.Phase.FLIGHT && m.phase == Match.Phase.OUTCOME) {
            if (m.result == Match.Result.GOAL) celebrate = 1f
            if (slot >= 0) save.recordShot(slot, m.result == Match.Result.GOAL)
        }
        // Autosave between shots so the level can be resumed later.
        if (m.phase == Match.Phase.AIM && savedShot != m.shot && slot >= 0) {
            save.saveSession(slot, m.snapshot())
            savedShot = m.shot
        }
        if (m.phase == Match.Phase.DONE && m.phaseT > 1.2f) finishMatch(m)
    }

    private fun finishMatch(m: Match) {
        resPassed = m.passed
        resStars = m.stars()
        resNewBest = if (slot >= 0) save.finishLevel(slot, m.levelIndex, m.score, resStars, m.passed) else false
        resRank = if (m.score <= 0) -1 else
            save.highScores().indexOfFirst { it.score == m.score && it.level == m.levelIndex && it.slot == slot }
        sound.play(if (m.passed) Sound.WIN else Sound.FAIL)
        go(Screen.RESULT)
    }

    private fun startLevel(index: Int, resume: Session? = null) {
        val m = Match(index, sound, rng, resume)
        m.setupCamera(viewH)
        match = m
        savedShot = if (resume != null) m.shot else -1
        celebrate = 0f
        go(Screen.PLAY)
        sound.play(Sound.WHISTLE)
    }

    private fun handle(e: Ev) {
        if (e.action == BACK) { onBack(); return }
        if (e.action == CANCEL) { pressed = null; match?.cancelSwipe(); return }
        val btns = buttons()
        when (e.action) {
            DOWN -> {
                pressed = btns.firstOrNull { it.hit(e.x, e.y) }?.id
                if (pressed == null && screen == Screen.PLAY) match?.touchDown(e.x, e.y, e.t)
            }
            MOVE -> if (pressed == null && screen == Screen.PLAY) match?.touchMove(e.x, e.y, e.t)
            UP -> {
                val p = pressed
                pressed = null
                if (p != null) {
                    if (btns.firstOrNull { it.hit(e.x, e.y) }?.id == p) {
                        sound.play(Sound.CLICK)
                        onButton(p)
                    }
                } else if (screen == Screen.PLAY) {
                    val m = match ?: return
                    m.touchUp(e.x, e.y, e.t)
                    if (m.phase == Match.Phase.OUTCOME) m.skip()
                }
            }
        }
    }

    private fun onBack() {
        when (screen) {
            Screen.TITLE -> {}
            Screen.SLOTS, Screen.SCORES -> go(Screen.TITLE)
            Screen.UPDATE -> go(updateBack)
            Screen.LEVELS -> go(Screen.SLOTS)
            Screen.PLAY -> go(Screen.PAUSE)
            Screen.PAUSE -> go(Screen.PLAY)
            Screen.RESULT -> go(Screen.LEVELS)
        }
    }

    private fun onButton(id: String) {
        when {
            id == "play" -> go(Screen.SLOTS)
            id == "scores" -> go(Screen.SCORES)
            id == "update" -> { updateBack = screen; go(Screen.UPDATE) }
            id == "sound" -> save.soundOn = !save.soundOn
            id == "back" -> onBack()
            id.startsWith("slot") -> {
                val s = id.removePrefix("slot").toInt()
                if (!save.exists(s)) save.create(s)
                slot = s
                save.lastSlot = s
                go(Screen.LEVELS)
            }
            id.startsWith("del") -> {
                val s = id.removePrefix("del").toInt()
                if (confirmDelete == s) { save.clear(s); confirmDelete = -1 } else confirmDelete = s
            }
            id.startsWith("lvl") -> startLevel(id.removePrefix("lvl").toInt())
            id == "resume" -> save.session(slot)?.let { startLevel(it.level, it) }
            id == "pause" -> go(Screen.PAUSE)
            id == "continue" -> go(Screen.PLAY)
            id == "restart" -> match?.let { save.clearSession(slot); startLevel(it.levelIndex) }
            id == "savequit" -> {
                match?.let { if (slot >= 0 && it.phase != Match.Phase.DONE) save.saveSession(slot, it.snapshot()) }
                match = null
                go(Screen.LEVELS)
            }
            id == "next" -> match?.let { startLevel(min(it.levelIndex + 1, Levels.all.lastIndex)) }
            id == "retry" -> match?.let { startLevel(it.levelIndex) }
            id == "levels" -> { match = null; go(Screen.LEVELS) }
            id == "check" -> updater.check()
            id == "install" -> updater.install()
        }
    }

    // ---- layout ----

    private fun centered(id: String, y: Float, label: String, style: Int = 0, w: Float = 250f, h: Float = 54f) =
        Btn(id, (VIEW_W - w) / 2, y, w, h, label, style)

    fun buttons(): List<Btn> {
        val h = viewH
        val list = ArrayList<Btn>()
        when (screen) {
            Screen.TITLE -> {
                val y = h * 0.5f
                list += centered("play", y, "PLAY", STYLE_GO)
                list += centered("scores", y + 70f, "HIGH SCORES")
                list += centered("update", y + 140f, "UPDATE GAME")
                list += Btn("sound", VIEW_W - 58f, 16f, 42f, 42f, "", STYLE_ICON)
            }
            Screen.SLOTS -> {
                for (i in 0 until SaveData.SLOTS) {
                    val y = 130f + i * 140f
                    list += Btn("slot$i", 30f, y, 340f, 120f, "", STYLE_CARD)
                    if (save.exists(i)) list += Btn("del$i", 270f, y + 8f, 92f, 32f, if (confirmDelete == i) "SURE?" else "DELETE", STYLE_DANGER)
                }
                list += centered("back", h - 90f, "BACK")
            }
            Screen.LEVELS -> {
                var top = 110f
                val sess = save.session(slot)
                if (sess != null) {
                    list += Btn("resume", 20f, top, 360f, 50f, "", STYLE_GO)
                    top += 62f
                }
                val rowH = min(60f, (h - top - 100f) / Levels.all.size)
                val unlocked = save.unlocked(slot)
                for (i in Levels.all.indices) {
                    if (i <= unlocked) list += Btn("lvl$i", 20f, top + i * rowH, 360f, rowH - 6f, "", STYLE_CARD)
                }
                list += centered("back", h - 80f, "BACK", w = 200f, h = 50f)
            }
            Screen.PLAY -> list += Btn("pause", VIEW_W - 54f, 12f, 42f, 42f, "", STYLE_ICON)
            Screen.PAUSE -> {
                val y = h * 0.38f
                list += centered("continue", y, "RESUME", STYLE_GO)
                list += centered("restart", y + 68f, "RESTART LEVEL")
                list += centered("savequit", y + 136f, "SAVE & QUIT")
                list += centered("sound", y + 204f, if (save.soundOn) "SOUND: ON" else "SOUND: OFF")
            }
            Screen.RESULT -> {
                val y = h * 0.66f
                val m = match
                if (resPassed && m != null && m.levelIndex < Levels.all.lastIndex) {
                    list += centered("next", y, "NEXT LEVEL", STYLE_GO)
                }
                list += centered("retry", y + 66f, "PLAY AGAIN")
                list += centered("levels", y + 132f, "LEVELS")
            }
            Screen.SCORES -> list += centered("back", h - 90f, "BACK")
            Screen.UPDATE -> {
                val y = h * 0.62f
                when (updater.status) {
                    Updater.Status.AVAILABLE -> list += centered("install", y, "INSTALL UPDATE", STYLE_GO)
                    Updater.Status.CHECKING, Updater.Status.DOWNLOADING, Updater.Status.INSTALLING,
                    Updater.Status.UNSUPPORTED -> {}
                    else -> list += centered("check", y, "CHECK FOR UPDATES")
                }
                list += centered("back", h - 90f, "BACK")
            }
        }
        return list
    }

    // ---- drawing ----

    fun render(g: Gfx) {
        val m = match
        val sceneMatch = if ((screen == Screen.PLAY || screen == Screen.PAUSE || screen == Screen.RESULT) && m != null) m else demo
        renderer.draw(g, sceneMatch, viewH, celebrate)
        when (screen) {
            Screen.TITLE -> drawTitle(g)
            Screen.SLOTS -> drawSlots(g)
            Screen.LEVELS -> drawLevels(g)
            Screen.PLAY -> drawHud(g, sceneMatch)
            Screen.PAUSE -> { drawHud(g, sceneMatch); dim(g); drawHeader(g, "PAUSED", viewH * 0.3f) }
            Screen.RESULT -> drawResult(g, sceneMatch)
            Screen.SCORES -> drawScores(g)
            Screen.UPDATE -> drawUpdate(g)
        }
        // Cards and label-less buttons are drawn by their screen, under their text.
        for (b in buttons()) if (b.style != STYLE_CARD && (b.label.isNotEmpty() || b.style == STYLE_ICON)) drawButton(g, b)
    }

    private fun dim(g: Gfx, a: Int = 0xA0) = g.rect(0f, 0f, VIEW_W, viewH, argb(a, 0x06101C))

    private fun drawHeader(g: Gfx, s: String, y: Float, size: Float = 34f) = g.shadowText(s, VIEW_W / 2, y, size, WHITE)

    private fun drawButton(g: Gfx, b: Btn) {
        val down = pressed == b.id
        val off = if (down) 2f else 0f
        when (b.style) {
            STYLE_ICON -> {
                g.circle(b.x + b.w / 2, b.y + b.h / 2 + off, b.w / 2, 0x88000000.toInt())
                val cx = b.x + b.w / 2; val cy = b.y + b.h / 2 + off
                if (b.id == "pause") {
                    g.rect(cx - 8f, cy - 10f, 6f, 20f, WHITE)
                    g.rect(cx + 2f, cy - 10f, 6f, 20f, WHITE)
                } else {
                    speaker(g, cx, cy, save.soundOn)
                }
                return
            }
            STYLE_CARD -> {
                g.roundRect(b.x, b.y + off, b.w, b.h, 12f, if (down) 0xEE1D3B5C.toInt() else 0xDD0E2238.toInt())
                return
            }
        }
        val (fill, edge, text) = when (b.style) {
            STYLE_GO -> Triple(0xFF19C25B.toInt(), 0xFF0E7A39.toInt(), WHITE)
            STYLE_DANGER -> Triple(0xFFD84343.toInt(), 0xFF8E1F1F.toInt(), WHITE)
            else -> Triple(0xFFF2F5F8.toInt(), 0xFF8F9BA8.toInt(), 0xFF14263A.toInt())
        }
        val r = min(14f, b.h / 2)
        g.roundRect(b.x, b.y + 4f, b.w, b.h, r, edge)
        g.roundRect(b.x, b.y + off, b.w, b.h, r, fill)
        g.text(b.label, b.x + b.w / 2, b.y + off + b.h / 2 + b.h * 0.15f, min(22f, b.h * 0.42f), text, 1)
        if (b.id == "update" && updater.status == Updater.Status.AVAILABLE) {
            g.roundRect(b.x + b.w - 50f, b.y - 10f, 58f, 24f, 12f, 0xFFFF3D3D.toInt())
            g.text("NEW!", b.x + b.w - 21f, b.y + 7f, 14f, WHITE, 1)
        }
    }

    private fun speaker(g: Gfx, cx: Float, cy: Float, on: Boolean) {
        g.rect(cx - 11f, cy - 5f, 6f, 10f, WHITE)
        g.poly(floatArrayOf(cx - 6f, cx + 3f, cx + 3f, cx - 6f), floatArrayOf(cy - 5f, cy - 11f, cy + 11f, cy + 5f), 4, WHITE)
        if (on) {
            g.line(cx + 7f, cy - 5f, cx + 9f, cy, 2.2f, WHITE); g.line(cx + 9f, cy, cx + 7f, cy + 5f, 2.2f, WHITE)
            g.line(cx + 11f, cy - 9f, cx + 14f, cy, 2.2f, WHITE); g.line(cx + 14f, cy, cx + 11f, cy + 9f, 2.2f, WHITE)
        } else {
            g.line(cx + 6f, cy - 6f, cx + 14f, cy + 6f, 2.5f, 0xFFFF5252.toInt())
            g.line(cx + 14f, cy - 6f, cx + 6f, cy + 6f, 2.5f, 0xFFFF5252.toInt())
        }
    }

    private fun star(g: Gfx, cx: Float, cy: Float, r: Float, color: Int) {
        val xs = FloatArray(10); val ys = FloatArray(10)
        for (i in 0 until 10) {
            val a = (-PI / 2 + i * PI / 5).toFloat()
            val rr = if (i % 2 == 0) r else r * 0.45f
            xs[i] = cx + cos(a) * rr; ys[i] = cy + sin(a) * rr
        }
        g.poly(xs, ys, 10, color)
    }

    private fun stars(g: Gfx, cx: Float, cy: Float, r: Float, n: Int) {
        for (i in 0 until 3) star(g, cx + (i - 1) * r * 2.3f, cy, r, if (i < n) GOLD else 0x55FFFFFF)
    }

    private fun drawTitle(g: Gfx) {
        dim(g, 0x70)
        val y = viewH * 0.22f
        val bob = sin(time * 3f) * 4f
        g.shadowText("GOAL", VIEW_W / 2, y, 72f, WHITE)
        g.shadowText("RUSH", VIEW_W / 2, y + 70f, 72f, GOLD)
        g.circle(VIEW_W / 2, y + 120f + bob, 20f, 0x44000000)
        drawIconBall(g, VIEW_W / 2, y + 112f + bob, 18f, time * 2f)
        g.text("Swipe to shoot. Curve it. Score!", VIEW_W / 2, y + 160f, 16f, 0xFFE0ECF8.toInt(), 1)
        g.text("Version ${updater.currentVersion}", VIEW_W / 2, viewH - 24f, 13f, 0xAAFFFFFF.toInt(), 1)
    }

    private fun drawIconBall(g: Gfx, cx: Float, cy: Float, r: Float, a: Float) {
        g.circle(cx, cy, r, 0xFFF7F7F7.toInt())
        for (i in 0 until 5) {
            val ang = a + i * 2 * PI.toFloat() / 5
            val d = r * 0.62f
            g.circle(cx + cos(ang) * d, cy + sin(ang) * d * 0.9f, r * 0.24f, 0xFF1C1C1C.toInt())
        }
        g.circle(cx, cy, r * 0.3f, 0xFF1C1C1C.toInt())
        g.circle(cx - r * 0.35f, cy - r * 0.4f, r * 0.3f, 0x55FFFFFF)
    }

    private fun drawSlots(g: Gfx) {
        dim(g)
        drawHeader(g, "SAVE SLOTS", 80f)
        g.text("Your progress is saved automatically.", VIEW_W / 2, 108f, 14f, 0xCCFFFFFF.toInt(), 1)
        for (b in buttons()) if (b.style == STYLE_CARD) drawButton(g, b)
        for (i in 0 until SaveData.SLOTS) {
            val y = 130f + i * 140f
            g.text("SLOT ${i + 1}", 50f, y + 34f, 22f, GOLD)
            if (save.exists(i)) {
                val unlocked = save.unlocked(i) + 1
                g.text("Level $unlocked of ${Levels.all.size}", 50f, y + 62f, 16f, WHITE)
                star(g, 58f, y + 82f, 8f, GOLD)
                g.text("${save.totalStars(i)}/${Levels.all.size * 3}", 72f, y + 88f, 16f, WHITE)
                g.text("Score ${fmt(save.totalBest(i))}", 160f, y + 88f, 16f, WHITE)
                val s = save.session(i)
                if (s != null) g.text("Resume: ${Levels.all[s.level].name}", 50f, y + 110f, 13f, 0xFF7CFFB0.toInt())
                else if (save.champion(i)) g.text("CUP WINNER!", 50f, y + 110f, 13f, GOLD)
            } else {
                g.text("Empty: tap to start a new game", 50f, y + 70f, 16f, 0xCCFFFFFF.toInt())
            }
        }
    }

    private fun drawLevels(g: Gfx) {
        dim(g)
        drawHeader(g, "SLOT ${slot + 1}", 56f, 30f)
        star(g, VIEW_W / 2 - 40f, 82f, 9f, GOLD)
        g.text("${save.totalStars(slot)}/${Levels.all.size * 3}   SCORE ${fmt(save.totalBest(slot))}", VIEW_W / 2 - 26f, 88f, 15f, WHITE)
        var top = 110f
        val sess = save.session(slot)
        val btns = buttons()
        if (sess != null) {
            btns.first { it.id == "resume" }.let { drawButton(g, it) }
            g.text("RESUME ${sess.level + 1}. ${Levels.all[sess.level].name}", 40f, top + 24f, 17f, WHITE)
            g.text("Shot ${sess.shot + 1}/${Levels.all[sess.level].shots}   Goals ${sess.goals}   Score ${fmt(sess.score)}", 40f, top + 42f, 12f, 0xEEFFFFFF.toInt())
            top += 62f
        }
        val rowH = min(60f, (viewH - top - 100f) / Levels.all.size)
        val unlocked = save.unlocked(slot)
        for ((i, lv) in Levels.all.withIndex()) {
            val y = top + i * rowH
            val locked = i > unlocked
            if (locked) g.roundRect(20f, y, 360f, rowH - 6f, 12f, 0x99101820.toInt())
            else btns.firstOrNull { it.id == "lvl$i" }?.let { drawButton(g, it) }
            val mid = y + (rowH - 6f) / 2
            val c = if (locked) 0x77FFFFFF else WHITE
            g.text("${i + 1}", 46f, mid + 7f, 20f, if (locked) c else GOLD, 1)
            g.text(lv.name, 70f, mid + 1f, 17f, c)
            if (locked) {
                lock(g, 350f, mid)
            } else {
                val best = save.best(slot, i)
                g.text(if (best > 0) "Best ${fmt(best)}" else "Score ${lv.need} of ${lv.shots}", 70f, mid + 17f, 12f, 0xCCFFFFFF.toInt())
                for (s in 0 until 3) star(g, 300f + s * 22f, mid, 8f, if (s < save.stars(slot, i)) GOLD else 0x44FFFFFF)
            }
        }
    }

    private fun lock(g: Gfx, cx: Float, cy: Float) {
        g.roundRect(cx - 7f, cy - 7f, 4f, 10f, 2f, 0x99FFFFFF.toInt())
        g.roundRect(cx + 3f, cy - 7f, 4f, 10f, 2f, 0x99FFFFFF.toInt())
        g.roundRect(cx - 7f, cy - 10f, 14f, 5f, 2.5f, 0x99FFFFFF.toInt())
        g.roundRect(cx - 9f, cy - 1f, 18f, 13f, 3f, 0x99FFFFFF.toInt())
    }

    private fun drawHud(g: Gfx, m: Match) {
        // Swipe trail.
        if (m.swiping && m.swN > 1) {
            for (i in 1 until m.swN) {
                val a = i.toFloat() / m.swN
                g.line(m.swX[i - 1], m.swY[i - 1], m.swX[i], m.swY[i], 4f + 8f * a, alpha(0xFFFFFFFF.toInt(), 0.15f + 0.5f * a))
            }
        }
        // Top bar.
        g.roundRect(10f, 10f, 330f, 50f, 12f, 0x99000000.toInt())
        g.text("${m.levelIndex + 1}. ${m.level.name.uppercase()}", 22f, 31f, 15f, WHITE)
        g.text("SCORE ${fmt(m.score)}", 22f, 51f, 15f, GOLD)
        val best = if (slot >= 0) save.best(slot, m.levelIndex) else 0
        if (best > 0) g.text("BEST ${fmt(best)}", 330f, 51f, 12f, 0xCCFFFFFF.toInt(), 2)
        // Shots: a row of dots.
        val n = m.level.shots
        for (i in 0 until n) {
            val x = 330f - (n - 1 - i) * 17f
            val y = 26f
            when {
                i < m.history.size && m.history[i] -> g.circle(x, y, 6.5f, 0xFF19C25B.toInt())
                i < m.history.size -> g.circle(x, y, 6.5f, 0xFFE53935.toInt())
                else -> { g.circle(x, y, 6.5f, 0xCCFFFFFF.toInt()); g.circle(x, y, 4.5f, 0x99000000.toInt()) }
            }
        }
        g.roundRect(10f, 66f, 128f, 26f, 10f, 0x99000000.toInt())
        g.text("GOALS ${m.goals}/${m.level.need}", 74f, 84f, 14f, if (m.passed) 0xFF7CFFB0.toInt() else WHITE, 1)
        if (m.level.wind != 0f) {
            g.roundRect(146f, 66f, 120f, 26f, 10f, 0x99000000.toInt())
            val dir = if (m.level.wind > 0) 1f else -1f
            val cx = 236f
            g.line(cx - 16f * dir, 79f, cx + 14f * dir, 79f, 3f, 0xFF80D8FF.toInt())
            g.poly(floatArrayOf(cx + 18f * dir, cx + 8f * dir, cx + 8f * dir), floatArrayOf(79f, 72f, 86f), 3, 0xFF80D8FF.toInt())
            g.text("WIND ${"%.1f".format(abs(m.level.wind))}", 156f, 84f, 13f, WHITE)
        }

        when (m.phase) {
            Match.Phase.INTRO -> {
                val y = viewH * 0.42f
                g.roundRect(30f, y - 70f, 340f, 160f, 18f, 0xDD0B1A2A.toInt())
                g.text("LEVEL ${m.levelIndex + 1}", VIEW_W / 2, y - 36f, 18f, GOLD, 1)
                g.shadowText(m.level.name.uppercase(), VIEW_W / 2, y, 28f, WHITE)
                g.text(m.level.blurb, VIEW_W / 2, y + 30f, 14f, 0xEEFFFFFF.toInt(), 1)
                g.text("Score ${m.level.need} of ${m.level.shots} to pass", VIEW_W / 2, y + 56f, 15f, 0xFF7CFFB0.toInt(), 1)
                g.text("TAP TO START", VIEW_W / 2, y + 80f, 12f, alpha(WHITE, 0.5f + 0.5f * sin(time * 5f)), 1)
            }
            Match.Phase.AIM -> if (m.shot == 0 && (m.levelIndex == 0 || m.levelIndex == 3) && !m.swiping) drawHint(g, m)
            Match.Phase.OUTCOME, Match.Phase.DONE -> drawOutcome(g, m)
            else -> {}
        }
    }

    private fun drawHint(g: Gfx, m: Match) {
        val bp = m.cam.project(m.ball.pos, Proj())
        val curve = m.levelIndex == 3
        val target = m.cam.project(if (curve) Vec3(2.8f, 1.9f, 0f) else Vec3(-2.8f, 1.6f, 0f), Proj())
        val t = (screenT % 1.6f) / 1.2f
        val steps = 18
        fun at(u: Float): Pair<Float, Float> {
            val bow = if (curve) -sin(u * PI.toFloat()) * 60f else 0f
            return (bp.x + (target.x - bp.x) * u + bow) to (bp.y + (target.y - bp.y) * u)
        }
        for (i in 0 until steps) {
            val u = i / steps.toFloat()
            if (u > t) break
            val (x, y) = at(u)
            g.circle(x, y, 3f, 0xAAFFFFFF.toInt())
        }
        val (hx, hy) = at(t.coerceAtMost(1f))
        g.circle(hx, hy, 14f, 0x66FFFFFF)
        g.circle(hx, hy, 8f, 0xCCFFFFFF.toInt())
        val msg = if (curve) "Bend your swipe to curl it around the wall!" else "Swipe from the ball to where you want it"
        g.roundRect(20f, viewH - 96f, 360f, 54f, 14f, 0xAA000000.toInt())
        g.text(msg, VIEW_W / 2, viewH - 74f, 14f, WHITE, 1)
        g.text("Faster swipe = harder shot", VIEW_W / 2, viewH - 54f, 13f, GOLD, 1)
    }

    private fun drawOutcome(g: Gfx, m: Match) {
        val (msg, color) = when (m.result) {
            Match.Result.GOAL -> "GOAL!" to GOLD
            Match.Result.SAVED -> "SAVED!" to 0xFFFF6E6E.toInt()
            Match.Result.BLOCKED -> "BLOCKED!" to 0xFFFF6E6E.toInt()
            Match.Result.WIDE -> "WIDE!" to 0xFFFFFFFF.toInt()
            Match.Result.OVER -> "OVER THE BAR!" to 0xFFFFFFFF.toInt()
            Match.Result.POST -> "OFF THE WOODWORK!" to 0xFFFFFFFF.toInt()
            Match.Result.NO_RING -> "MISSED THE RING!" to 0xFFFFB74D.toInt()
            Match.Result.WEAK -> "TOO WEAK!" to 0xFFFFFFFF.toInt()
            Match.Result.NONE -> return
        }
        val y = viewH * 0.7f
        val pop = 1f + 0.25f * (1f - min(1f, m.phaseT * 4f).let { if (m.phase == Match.Phase.DONE) 1f else it })
        g.shadowText(msg, VIEW_W / 2, y, (if (msg.length > 8) 36f else 56f) * pop, color)
        if (m.result == Match.Result.GOAL) {
            for ((i, line) in m.bonusLines.withIndex()) {
                g.shadowText(line, VIEW_W / 2, y + 34f + i * 22f, 17f, if (i == 0) WHITE else 0xFF7CFFB0.toInt())
            }
        }
    }

    private fun drawResult(g: Gfx, m: Match) {
        dim(g, 0xC8)
        val y = viewH * 0.18f
        val champion = resPassed && m.levelIndex == Levels.all.lastIndex
        drawHeader(g, if (champion) "CHAMPIONS!" else if (resPassed) "LEVEL COMPLETE" else "LEVEL FAILED", y, 32f)
        g.text("${m.levelIndex + 1}. ${m.level.name}", VIEW_W / 2, y + 30f, 16f, 0xDDFFFFFF.toInt(), 1)
        stars(g, VIEW_W / 2, y + 80f, 22f, resStars)
        g.text("Goals ${m.goals} of ${m.level.shots} (needed ${m.level.need})", VIEW_W / 2, y + 140f, 17f, WHITE, 1)
        g.shadowText(fmt(m.score), VIEW_W / 2, y + 196f, 46f, GOLD)
        g.text("POINTS", VIEW_W / 2, y + 218f, 13f, 0xCCFFFFFF.toInt(), 1)
        val pulse = 0.6f + 0.4f * sin(time * 6f)
        if (resNewBest && m.score > 0) g.text("NEW HIGH SCORE!", VIEW_W / 2, y + 252f, 20f, alpha(0xFF7CFFB0.toInt(), pulse), 1)
        else g.text("Best: ${fmt(save.best(slot, m.levelIndex))}", VIEW_W / 2, y + 252f, 16f, WHITE, 1)
        if (resRank in 0 until SaveData.TOP) g.text("#${resRank + 1} on the high score table", VIEW_W / 2, y + 276f, 14f, GOLD, 1)
        if (resPassed && m.levelIndex < Levels.all.lastIndex) g.text("Level ${m.levelIndex + 2} unlocked", VIEW_W / 2, y + 298f, 14f, 0xFF80D8FF.toInt(), 1)
    }

    private fun drawScores(g: Gfx) {
        dim(g, 0xC0)
        drawHeader(g, "HIGH SCORES", 80f)
        val list = save.highScores()
        if (list.isEmpty()) {
            g.text("No scores yet. Go and score some goals!", VIEW_W / 2, 160f, 16f, WHITE, 1)
        }
        for ((i, h) in list.withIndex()) {
            val y = 130f + i * 46f
            g.roundRect(24f, y, 352f, 40f, 10f, if (i == 0) 0xCC5A4500.toInt() else 0xAA0E2238.toInt())
            g.text("${i + 1}", 46f, y + 27f, 20f, if (i < 3) GOLD else WHITE, 1)
            g.text(fmt(h.score), 72f, y + 27f, 20f, WHITE)
            g.text(Levels.all.getOrNull(h.level)?.name ?: "?", 170f, y + 19f, 13f, 0xEEFFFFFF.toInt())
            g.text("Slot ${h.slot + 1}", 170f, y + 34f, 11f, 0xAAFFFFFF.toInt())
        }
    }

    private fun drawUpdate(g: Gfx) {
        dim(g, 0xC0)
        drawHeader(g, "UPDATE GAME", 90f)
        val y = viewH * 0.28f
        g.roundRect(30f, y, 340f, 190f, 16f, 0xCC0E2238.toInt())
        g.text("Installed version", 50f, y + 34f, 15f, 0xCCFFFFFF.toInt())
        g.text(updater.currentVersion, 350f, y + 34f, 15f, WHITE, 2)
        g.text("Latest version", 50f, y + 62f, 15f, 0xCCFFFFFF.toInt())
        g.text(updater.latestVersion.ifEmpty { "-" }, 350f, y + 62f, 15f, WHITE, 2)
        val (status, color) = when (updater.status) {
            Updater.Status.IDLE -> "Tap CHECK to look for a new version." to WHITE
            Updater.Status.CHECKING -> "Checking..." to WHITE
            Updater.Status.UP_TO_DATE -> "You have the latest version!" to 0xFF7CFFB0.toInt()
            Updater.Status.AVAILABLE -> "A new version is ready to install." to GOLD
            Updater.Status.DOWNLOADING -> "Downloading... ${(updater.progress * 100).toInt()}%" to WHITE
            Updater.Status.INSTALLING -> "Follow the prompts to install." to GOLD
            Updater.Status.ERROR -> "Something went wrong." to 0xFFFF6E6E.toInt()
            Updater.Status.UNSUPPORTED -> "Updates are not available here." to WHITE
        }
        g.text(status, VIEW_W / 2, y + 104f, 16f, color, 1)
        if (updater.message.isNotEmpty()) wrap(updater.message, 44).forEachIndexed { i, s ->
            g.text(s, VIEW_W / 2, y + 128f + i * 18f, 12f, 0xBBFFFFFF.toInt(), 1)
        }
        if (updater.status == Updater.Status.DOWNLOADING) {
            g.roundRect(60f, y + 160f, 280f, 12f, 6f, 0x55FFFFFF)
            g.roundRect(60f, y + 160f, 280f * updater.progress.coerceIn(0f, 1f), 12f, 6f, 0xFF19C25B.toInt())
        }
        g.text("Your saves and high scores are kept.", VIEW_W / 2, y + 220f, 13f, 0xAAFFFFFF.toInt(), 1)
    }

    private fun wrap(s: String, width: Int): List<String> {
        val out = ArrayList<String>()
        var line = ""
        for (w in s.split(' ')) {
            if (line.isNotEmpty() && line.length + 1 + w.length > width) { out += line; line = w }
            else line = if (line.isEmpty()) w else "$line $w"
        }
        if (line.isNotEmpty()) out += line
        return out.take(3)
    }

    companion object {
        const val DOWN = 0
        const val MOVE = 1
        const val UP = 2
        const val CANCEL = 3
        private const val BACK = 9

        const val STYLE_PLAIN = 0
        const val STYLE_GO = 1
        const val STYLE_DANGER = 2
        const val STYLE_ICON = 3
        const val STYLE_CARD = 4

        const val WHITE = 0xFFFFFFFF.toInt()
        const val GOLD = 0xFFFFC928.toInt()

        fun fmt(n: Int): String {
            val s = n.toString()
            val sb = StringBuilder()
            for ((i, c) in s.withIndex()) {
                if (i > 0 && (s.length - i) % 3 == 0) sb.append(',')
                sb.append(c)
            }
            return sb.toString()
        }
    }
}
