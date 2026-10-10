package com.spotless.game

import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** Persistent key/value storage for the save game. */
interface Storage {
    fun load(key: String): Int
    fun save(key: String, value: Int)
}

enum class Screen { TITLE, HOME, CLEAN, RESULT, REACT, CHANGE, REVEAL, SHOP, WARDROBE }

const val TOUCH_DOWN = 0
const val TOUCH_MOVE = 1
const val TOUCH_UP = 2

private class Particle(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    var life: Float, val maxLife: Float, val kind: Int, val color: Int, val size: Float,
)

private class FloatText(val text: String, var x: Float, var y: Float, var life: Float, val color: Int)

private class Upgrade(val name: String, val desc: String, val prices: IntArray)

private class Gift(val name: String, val hearts: Int, val price: Int, val line: String)

/**
 * The whole game: clean rooms against the clock, earn coins and hearts, and watch Bella
 * warm up to you. All coordinates are in the virtual [VW] x [VH] portrait screen.
 */
class Game(private val sound: SoundSink, private val store: Storage) {
    var screen = Screen.TITLE
        private set
    private var t = 0f
    private var screenT = 0f

    // ---------------------------------------------------------------- save game
    var hearts = 0
        private set
    var coins = 0
        private set
    var day = 0
        private set
    private var gifts = 0
    private val upgrades = IntArray(3)
    private var outfitChoice = -1
    private var seenLevel = 0

    val level get() = Story.levelFor(hearts)
    private val outfit get() = Story.levels[if (outfitChoice in 0..level) outfitChoice else level].outfit
    private val mood get() = level / Story.maxLevel.toFloat()

    // ---------------------------------------------------------------- cleaning
    var room = RoomKind.LIVING
        private set
    var messes: List<Mess> = emptyList()
        private set
    var tool = Tool.BROOM
        private set
    private var timeLimit = 60f
    var timeLeft = 60f
        private set
    private var readyT = 0f
    private var combo = 0
    private var comboT = 0f
    private var earned = 0
    var finished = false
        private set
    private var finishT = 0f
    var stars = 0
        private set
    private var heartsGain = 0
    private var coinsGain = 0
    private var hint = ""
    private var hintTool: Tool? = null
    private var hintT = 0f
    private var wrongCooldown = 0f
    private var scrubSoundT = 0f
    private var lastTick = 0

    // ---------------------------------------------------------------- touch & UI
    private var pDown = false
    private var px = 0f
    private var py = 0f
    private val buttons = LinkedHashMap<String, FloatArray>()
    private var pressed: String? = null

    // ---------------------------------------------------------------- Bella's talking
    private var speech = ""
    private var speechT = 0f
    private var face = 0
    private var waveT = 0f
    private var levelBefore = 0
    private var resetArmed = 0f
    private var toast = ""
    private var toastT = 0f

    private val particles = ArrayList<Particle>()
    private val floats = ArrayList<FloatText>()
    private val rnd = Random(System.nanoTime())

    private val upgradeList = listOf(
        Upgrade("Bigger Broom", "Clean a wider area", intArrayOf(80, 160, 300)),
        Upgrade("Elbow Grease", "Scrub 25% faster", intArrayOf(100, 200, 350)),
        Upgrade("Stopwatch", "+6 seconds in every room", intArrayOf(90, 180, 320)),
    )
    private val giftList = listOf(
        Gift("Bouquet of Roses", 1, 150, "Roses? For me?! You're so sweet!"),
        Gift("Box of Chocolates", 3, 400, "Chocolates! You really know the way to my heart."),
    )

    init { load() }

    // ================================================================ save / load

    private fun load() {
        hearts = store.load("hearts")
        coins = store.load("coins")
        day = store.load("day")
        gifts = store.load("gifts")
        for (i in upgrades.indices) upgrades[i] = store.load("up$i").coerceIn(0, 3)
        outfitChoice = store.load("outfit") - 1
        seenLevel = store.load("seen").coerceAtMost(level)
    }

    private fun save() {
        store.save("hearts", hearts)
        store.save("coins", coins)
        store.save("day", day)
        store.save("gifts", gifts)
        for (i in upgrades.indices) store.save("up$i", upgrades[i])
        store.save("outfit", outfitChoice + 1)
        store.save("seen", seenLevel)
    }

    private fun resetProgress() {
        hearts = 0; coins = 0; day = 0; gifts = 0
        upgrades.fill(0)
        outfitChoice = -1
        seenLevel = 0
        save()
    }

    // ================================================================ perks

    private val brushRadius get() = 30f + 14f * upgrades[0]
    private val cleanPower get() = (1f + 0.25f * upgrades[1]) * (if (level >= 3) 1.2f else 1f)
    private val coinMultiplier get() = when {
        level >= 6 -> 2f
        level >= 5 -> 1.75f
        level >= 2 -> 1.25f
        else -> 1f
    }

    // ================================================================ flow

    private fun go(s: Screen) {
        screen = s
        screenT = 0f
        buttons.clear()
        pressed = null
    }

    fun startRoom() {
        room = RoomKind.entries[day % RoomKind.entries.size]
        messes = Rooms.makeMess(room, day, Random(day * 7919L + 17))
        val per = messes.sumOf { (if (it.type.tool == Tool.HAND) 2.4 else 4.2) * it.size / 42.0 }.toFloat()
        val squeeze = (1f - day * 0.02f).coerceAtLeast(0.72f)
        timeLimit = 12f + per * squeeze + 6f * upgrades[2] + (if (level >= 1) 5f else 0f)
        timeLeft = timeLimit
        readyT = 1.4f
        combo = 0; comboT = 0f; earned = 0
        finished = false; finishT = 0f
        hint = ""; hintT = 0f; hintTool = null
        lastTick = 0
        particles.clear(); floats.clear()
        // Start with the tool the room needs most.
        tool = messes.groupingBy { it.type.tool }.eachCount().maxByOrNull { it.value }?.key ?: Tool.BROOM
        go(Screen.CLEAN)
    }

    private fun finishRoom() {
        finished = true
        finishT = 0f
        val total = messes.size
        val done = messes.count { it.cleaned }
        stars = if (done == total) {
            if (timeLeft / timeLimit >= 0.3f) 3 else 2
        } else if (done >= total * 0.75f) 1 else 0
        val timeBonus = if (done == total) (timeLeft * 3f).toInt() else 0
        coinsGain = ((earned + timeBonus) * coinMultiplier).toInt()
        heartsGain = stars + (if (stars == 3 && level >= 4) 1 else 0)
        sound.play(if (stars > 0) Sound.CHIME else Sound.WRONG)
    }

    private fun collectRewards() {
        levelBefore = level
        coins += coinsGain
        hearts += heartsGain
        if (stars > 0) day++
        save()
        val lv = Story.levels[levelBefore]
        say(if (stars >= 2) lv.thanks.random(rnd) else lv.meh.random(rnd))
        face = if (stars >= 2) 1 else if (stars == 0) 2 else 0
        go(Screen.REACT)
        repeat(heartsGain) { i -> spawnHeart(360f + (i - heartsGain / 2f) * 60f, 700f, delay = i * 0.25f) }
        if (heartsGain > 0) sound.play(Sound.HEART)
    }

    private fun afterReact() {
        if (level > seenLevel) {
            go(Screen.CHANGE)
            sound.play(Sound.SPARKLE)
        } else {
            face = 0
            say(Story.levels[level].chat.random(rnd))
            go(Screen.HOME)
        }
    }

    private fun say(s: String) {
        speech = s
        speechT = 0f
    }

    // ================================================================ input

    /** Feeds a touch event, in virtual coordinates. */
    fun touch(action: Int, x: Float, y: Float) {
        when (action) {
            TOUCH_DOWN -> {
                pDown = true
                px = x; py = y
                pressed = buttonAt(x, y)
                if (pressed != null) sound.play(Sound.CLICK)
                if (pressed == null && screen == Screen.CLEAN && playing()) cleanDown(x, y)
            }
            TOUCH_MOVE -> {
                if (!pDown) return
                if (screen == Screen.CLEAN && playing() && pressed == null) scrub(px, py, x, y)
                px = x; py = y
            }
            TOUCH_UP -> {
                if (!pDown) return
                pDown = false
                val id = buttonAt(x, y)
                val was = pressed
                pressed = null
                if (id != null && id == was) click(id) else if (was == null) tap(x, y)
            }
        }
    }

    /** The Android back button. Returns false when the app should close. */
    fun back(): Boolean {
        when (screen) {
            Screen.TITLE -> return false
            Screen.HOME -> go(Screen.TITLE)
            Screen.CLEAN -> { go(Screen.HOME); say("Taking a break? That's fine.") }
            Screen.RESULT -> collectRewards()
            Screen.REACT -> afterReact()
            Screen.CHANGE -> go(Screen.REVEAL)
            Screen.REVEAL -> click("aww")
            Screen.SHOP, Screen.WARDROBE -> go(Screen.HOME)
        }
        return true
    }

    private fun buttonAt(x: Float, y: Float): String? =
        buttons.entries.lastOrNull { (_, r) -> x >= r[0] && y >= r[1] && x <= r[0] + r[2] && y <= r[1] + r[3] }?.key

    private fun tap(x: Float, y: Float) {
        when (screen) {
            Screen.TITLE -> {
                go(Screen.HOME)
                face = 0
                say(Story.levels[level].chat.random(rnd))
            }
            Screen.HOME -> if (x in 200f..520f && y in 330f..1060f) pokeBella(x, y)
            Screen.REACT -> if (screenT > 0.6f) afterReact()
            Screen.CHANGE -> if (screenT > 0.8f) go(Screen.REVEAL)
            else -> {}
        }
    }

    private fun pokeBella(x: Float, y: Float) {
        val lines = Story.levels[level].chat
        var line = lines.random(rnd)
        if (line == speech && lines.size > 1) line = lines.first { it != speech }
        say(line)
        face = if (level >= 2) 1 else 0
        waveT = 1.6f
        spawnHeart(x, y, delay = 0f, small = true)
        sound.play(Sound.HEART)
    }

    private fun click(id: String) {
        when {
            id == "clean" -> startRoom()
            id == "shop" -> go(Screen.SHOP)
            id == "wardrobe" -> go(Screen.WARDROBE)
            id == "home" -> { go(Screen.HOME); face = 0 }
            id == "quit" -> { go(Screen.HOME); say("Taking a break? That's fine.") }
            id == "bella" -> collectRewards()
            id == "react" -> afterReact()
            id == "aww" -> {
                seenLevel = level
                save()
                face = 1
                say(Story.levels[level].chat.random(rnd))
                go(Screen.HOME)
            }
            id == "reset" -> {
                if (resetArmed > 0f) {
                    resetProgress()
                    resetArmed = 0f
                    showToast("Fresh start!")
                } else {
                    resetArmed = 3f
                    showToast("Tap again to erase your progress")
                }
            }
            id.startsWith("tool") -> {
                tool = Tool.entries[id.removePrefix("tool").toInt()]
                if (hintTool == tool) hintT = minOf(hintT, 0.3f)
            }
            id.startsWith("up") -> buyUpgrade(id.removePrefix("up").toInt())
            id.startsWith("gift") -> buyGift(id.removePrefix("gift").toInt())
            id.startsWith("wear") -> {
                val i = id.removePrefix("wear").toInt()
                if (i <= level) {
                    outfitChoice = if (i == level) -1 else i
                    save()
                    showToast("Bella is wearing the ${Story.levels[i].outfit.name}")
                    sound.play(Sound.SPARKLE)
                } else {
                    showToast("Earn ${Story.levels[i].hearts} hearts to unlock")
                    sound.play(Sound.WRONG)
                }
            }
        }
    }

    private fun buyUpgrade(i: Int) {
        val lv = upgrades[i]
        if (lv >= 3) return
        val price = upgradeList[i].prices[lv]
        if (coins < price) { showToast("Not enough coins yet!"); sound.play(Sound.WRONG); return }
        coins -= price
        upgrades[i]++
        save()
        sound.play(Sound.COIN)
        showToast("${upgradeList[i].name} upgraded!")
    }

    private fun buyGift(i: Int) {
        val g = giftList[i]
        if (coins < g.price) { showToast("Not enough coins yet!"); sound.play(Sound.WRONG); return }
        coins -= g.price
        levelBefore = level
        hearts += g.hearts
        gifts++
        save()
        sound.play(Sound.HEART)
        face = 1
        say(g.line)
        go(Screen.REACT)
        repeat(g.hearts) { k -> spawnHeart(360f + (k - g.hearts / 2f) * 60f, 700f, delay = k * 0.25f) }
    }

    private fun showToast(s: String) { toast = s; toastT = 2.2f }

    // ================================================================ cleaning

    private fun playing() = !finished && readyT <= 0f

    private fun inScene(y: Float) = y in SCENE_TOP..SCENE_BOTTOM

    private fun cleanDown(x: Float, y: Float) {
        if (!inScene(y)) return
        val target = messes.filter { !it.cleaned && it.fly < 0f }
            .minByOrNull { hypot(it.x - x, it.y - y) - it.size } ?: return
        val reach = target.size + brushRadius * 0.7f + 10f
        if (hypot(target.x - x, target.y - y) > reach) return
        if (target.type.tool == Tool.HAND) {
            if (tool == Tool.HAND) {
                target.fly = 0f
                target.cleaned = true
                cleaned(target)
                sound.play(Sound.PICK)
            } else wrongTool(target)
        } else if (tool == Tool.HAND) {
            wrongTool(target)
        }
    }

    private fun scrub(x0: Float, y0: Float, x1: Float, y1: Float) {
        if (tool == Tool.HAND || !inScene(y1)) return
        val d = hypot(x1 - x0, y1 - y0).coerceAtMost(90f)
        if (d < 0.5f) return
        val steps = ceil(d / 18f).toInt().coerceAtLeast(1)
        val per = d / steps
        var hitRight = false
        var hitWrong: Mess? = null
        for (k in 1..steps) {
            val x = x0 + (x1 - x0) * k / steps
            val y = y0 + (y1 - y0) * k / steps
            for (m in messes) {
                if (m.cleaned) continue
                if (hypot(m.x - x, m.y - y) > m.size + brushRadius) continue
                if (m.type.tool != tool) { hitWrong = m; continue }
                hitRight = true
                m.dirt -= per * cleanPower / (m.size * m.type.toughness * 4f)
                if (m.dirt <= 0f) {
                    m.dirt = 0f
                    m.cleaned = true
                    cleaned(m)
                    sound.play(Sound.POP)
                }
            }
        }
        if (hitRight) {
            if (rnd.nextFloat() < 0.6f) scrubParticle(x1, y1)
            if (scrubSoundT <= 0f) {
                sound.play(if (tool == Tool.SPONGE) Sound.SCRUB else Sound.SWISH)
                scrubSoundT = 0.13f
            }
        } else hitWrong?.let { wrongTool(it) }
    }

    private fun wrongTool(m: Mess) {
        if (wrongCooldown > 0f) return
        wrongCooldown = 1.2f
        hintTool = m.type.tool
        hint = "Use the ${m.type.tool.label.lowercase().let { if (m.type.tool == Tool.HAND) "hands" else it }} on the ${m.type.label}!"
        hintT = 2.2f
        sound.play(Sound.WRONG)
    }

    private fun cleaned(m: Mess) {
        combo = if (comboT > 0f) combo + 1 else 1
        comboT = 2.5f
        val gain = 10 + 5 * (combo - 1).coerceAtMost(5)
        earned += gain
        floats.add(FloatText("+$gain", m.x, m.y - m.size, 1.1f, GOLD))
        if (combo >= 3) floats.add(FloatText("Combo x$combo!", m.x, m.y - m.size - 40f, 1.1f, PINK))
        for (i in 0 until 10) {
            val a = rnd.nextFloat() * 6.283f
            val sp = 120f + rnd.nextFloat() * 180f
            particles.add(Particle(m.x, m.y, kotlin.math.cos(a) * sp, sin(a) * sp - 80f, 0.7f, 0.7f, 1,
                if (i % 2 == 0) WHITE else rgb(0xFF, 0xF1, 0x8A), 10f + rnd.nextFloat() * 8f))
        }
        if (messes.all { it.cleaned }) finishRoom()
    }

    private fun scrubParticle(x: Float, y: Float) {
        val (kind, color) = when (tool) {
            Tool.BROOM -> 0 to rgb(0xB8, 0xB0, 0xA8)
            Tool.SPONGE -> 3 to rgb(0xD8, 0xF2, 0xFF)
            else -> 1 to rgb(0xF2, 0xE0, 0xFF)
        }
        particles.add(Particle(x + rnd.nextFloat() * 30f - 15f, y + rnd.nextFloat() * 30f - 15f,
            rnd.nextFloat() * 80f - 40f, -40f - rnd.nextFloat() * 60f, 0.6f, 0.6f, kind, color, 8f + rnd.nextFloat() * 10f))
    }

    private fun spawnHeart(x: Float, y: Float, delay: Float, small: Boolean = false) {
        particles.add(Particle(x, y, rnd.nextFloat() * 40f - 20f, if (small) -120f else -200f,
            1.6f + delay, 1.6f, 2, PINK, if (small) 18f else 30f))
    }

    // ================================================================ update

    fun update(dt: Float) {
        t += dt
        screenT += dt
        speechT += dt
        if (waveT > 0f) waveT -= dt
        if (resetArmed > 0f) resetArmed -= dt
        if (toastT > 0f) toastT -= dt
        if (screen == Screen.CLEAN) updateClean(dt)
        if (screen == Screen.CHANGE && screenT > 2.6f) go(Screen.REVEAL)
        if (screen == Screen.REVEAL && rnd.nextFloat() < dt * 6f) {
            particles.add(Particle(rnd.nextFloat() * VW, VH + 20f, rnd.nextFloat() * 40f - 20f, -150f - rnd.nextFloat() * 120f,
                5f, 5f, 2, if (rnd.nextBoolean()) PINK else rgb(0xFF, 0x9E, 0xC4), 14f + rnd.nextFloat() * 16f))
        }
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            p.life -= dt
            if (p.life <= 0f) { it.remove(); continue }
            if (p.life > p.maxLife) continue // still waiting to appear
            p.x += p.vx * dt
            p.y += p.vy * dt
            if (p.kind == 0 || p.kind == 1) p.vy += 300f * dt
            if (p.kind == 2) p.x += sin((p.life + p.y) * 4f) * 30f * dt
        }
        val ft = floats.iterator()
        while (ft.hasNext()) {
            val f = ft.next()
            f.life -= dt
            f.y -= 60f * dt
            if (f.life <= 0f) ft.remove()
        }
    }

    private fun updateClean(dt: Float) {
        if (scrubSoundT > 0f) scrubSoundT -= dt
        if (wrongCooldown > 0f) wrongCooldown -= dt
        if (hintT > 0f) hintT -= dt
        for (m in messes) if (m.fly >= 0f && m.fly < 1f) m.fly = (m.fly + dt * 2.2f).coerceAtMost(1f)
        if (finished) {
            finishT += dt
            if (finishT > 1.6f && screen == Screen.CLEAN) go(Screen.RESULT).also { sound.play(Sound.COIN) }
            return
        }
        if (readyT > 0f) { readyT -= dt; return }
        if (comboT > 0f) comboT -= dt
        timeLeft -= dt
        val sec = ceil(timeLeft).toInt()
        if (sec <= 5 && sec != lastTick && sec > 0) { lastTick = sec; sound.play(Sound.TICK) }
        if (timeLeft <= 0f) { timeLeft = 0f; finishRoom() }
    }

    // ================================================================ render

    fun render(p: Painter) {
        buttons.clear()
        when (screen) {
            Screen.TITLE -> drawTitle(p)
            Screen.HOME -> drawHome(p)
            Screen.CLEAN -> drawClean(p)
            Screen.RESULT -> { drawClean(p, hud = false); drawResult(p) }
            Screen.REACT -> drawReact(p)
            Screen.CHANGE -> drawChange(p)
            Screen.REVEAL -> drawReveal(p)
            Screen.SHOP -> drawShop(p)
            Screen.WARDROBE -> drawWardrobe(p)
        }
        drawParticles(p)
        if (toastT > 0f) {
            val a = (toastT / 0.3f).coerceAtMost(1f)
            val w = p.textWidth(toast, 28f) + 60f
            p.roundRect(VW / 2 - w / 2, 1000f, w, 64f, 32f, alpha(INK, 0.9f * a))
            p.text(toast, VW / 2, 1042f, 28f, alpha(WHITE, a), ALIGN_CENTER)
        }
    }

    private fun gradient(p: Painter, top: Int, bottom: Int) {
        val n = 24
        for (i in 0 until n) p.rect(0f, VH * i / n, VW, VH / n + 1f, mix(top, bottom, i / (n - 1f)))
    }

    private fun button(p: Painter, id: String, x: Float, y: Float, w: Float, h: Float, label: String, color: Int, size: Float = 34f) {
        val down = pressed == id && pDown
        val dy = if (down) 4f else 0f
        p.roundRect(x, y + 6f, w, h, h / 2.6f, darker(color, 0.25f))
        p.roundRect(x, y + dy, w, h, h / 2.6f, if (down) darker(color, 0.08f) else color)
        p.roundRect(x + 10f, y + dy + 6f, w - 20f, h * 0.32f, h / 5f, alpha(WHITE, 0.25f))
        p.text(label, x + w / 2, y + dy + h / 2 + size * 0.36f, size, WHITE, ALIGN_CENTER)
        buttons[id] = floatArrayOf(x, y, w, h + 6f)
    }

    private fun bubble(p: Painter, text: String, x: Float, y: Float, w: Float, tailX: Float) {
        val size = 30f
        val shown = text.take((speechT * 45f).toInt().coerceAtLeast(1))
        val lines = p.wrap(text, size, w - 50f)
        val shownLines = p.wrap(shown, size, w - 50f)
        val h = lines.size * 40f + 40f
        p.roundRect(x + 4f, y + 6f, w, h, 30f, alpha(BLACK, 0.12f))
        p.fill(poly(tailX - 20f, y + h - 2f, tailX + 20f, y + h - 2f, tailX + 4f, y + h + 34f), WHITE)
        p.roundRect(x, y, w, h, 30f, WHITE)
        shownLines.forEachIndexed { i, l -> p.text(l, x + 25f, y + 50f + i * 40f, size, INK) }
        val tag = Story.NAME
        val tw = p.textWidth(tag, 24f) + 30f
        p.roundRect(x + 20f, y - 18f, tw, 36f, 18f, PINK)
        p.text(tag, x + 20f + tw / 2, y + 9f, 24f, WHITE, ALIGN_CENTER)
    }

    private fun heartMeter(p: Painter, y: Float) {
        val lv = level
        val cur = Story.levels[lv]
        p.roundRect(20f, y, VW - 40f, 96f, 30f, alpha(WHITE, 0.85f))
        p.heartIcon(66f, y + 48f, 30f, PINK)
        p.text("$hearts", 66f, y + 58f, 24f, WHITE, ALIGN_CENTER)
        p.text("${Story.NAME}: ${cur.title}", 112f, y + 38f, 28f, INK)
        val bx = 112f
        val bw = VW - 40f - 112f - 150f
        val by = y + 54f
        p.roundRect(bx, by, bw, 24f, 12f, SOFT_PINK)
        val frac = if (lv >= Story.maxLevel) 1f else {
            val next = Story.levels[lv + 1].hearts
            (hearts - cur.hearts) / (next - cur.hearts).toFloat()
        }
        if (frac > 0f) p.roundRect(bx, by, (bw * frac).coerceAtLeast(24f), 24f, 12f, PINK)
        val label = if (lv >= Story.maxLevel) "MAX" else "${hearts - cur.hearts}/${Story.levels[lv + 1].hearts - cur.hearts}"
        p.text(label, bx + bw / 2, by + 20f, 20f, INK, ALIGN_CENTER)
        p.coinIcon(VW - 140f, y + 48f, 20f)
        p.text("$coins", VW - 112f, y + 58f, 28f, INK)
    }

    private fun pose(wave: Boolean = false, talking: Boolean = false) =
        Pose(mood = mood, time = t, talking = talking, face = face, wave = wave)

    private fun talking() = speechT < speech.length / 45f && (t * 8f).toInt() % 2 == 0

    private fun drawTitle(p: Painter) {
        gradient(p, rgb(0xFF, 0xC4, 0xDA), rgb(0xFF, 0xF0, 0xE0))
        for (i in 0 until 12) {
            val x = (i * 137f + t * 20f) % VW
            val y = 200f + (i * 211f) % 900f + sin(t + i) * 10f
            p.fill(heart(x, y, 14f + (i % 3) * 6f), alpha(WHITE, 0.5f))
        }
        Bella.draw(p, outfit, VW / 2, 1150f, 0.86f, pose(wave = true))
        p.outlinedText("Spotless", VW / 2, 150f, 110f, WHITE, PINK)
        p.fill(sparkle(VW / 2 + 230f, 70f, 26f + sin(t * 4f) * 6f), WHITE)
        p.fill(sparkle(VW / 2 - 240f, 140f, 18f + sin(t * 4f + 1f) * 5f), WHITE)
        p.text("Clean the house. Win her heart.", VW / 2, 220f, 32f, INK, ALIGN_CENTER)
        val a = 0.6f + 0.4f * sin(t * 4f)
        p.roundRect(160f, 1160f, 400f, 70f, 35f, alpha(PINK, 0.9f))
        p.text("Tap to play", VW / 2, 1206f, 36f, alpha(WHITE, a.coerceIn(0.3f, 1f)), ALIGN_CENTER)
        if (hearts > 0 || coins > 0 || day > 0) {
            button(p, "reset", 20f, 1240f, 150f, 34f, if (resetArmed > 0f) "Sure?" else "Start over", rgb(0xB0, 0x9C, 0xB4), 20f)
        }
    }

    private fun drawHome(p: Painter) {
        gradient(p, rgb(0xFF, 0xDE, 0xEA), rgb(0xFF, 0xF4, 0xEC))
        // A cosy backdrop: window and floor.
        p.rect(0f, 900f, VW, VH - 900f, rgb(0xE8, 0xC4, 0xA8))
        p.rect(0f, 892f, VW, 10f, WHITE)
        p.roundRect(470f, 330f, 200f, 240f, 10f, WHITE)
        p.rect(482f, 342f, 176f, 216f, rgb(0xB8, 0xE2, 0xFF))
        p.line(570f, 342f, 570f, 558f, WHITE, 8f)
        p.line(482f, 450f, 658f, 450f, WHITE, 8f)
        p.roundRect(50f, 420f, 120f, 90f, 6f, rgb(0xB0, 0x7A, 0x40))
        p.rect(60f, 430f, 100f, 70f, rgb(0xFF, 0xF6, 0xE0))
        p.fill(heart(110f, 466f, 22f), PINK)

        heartMeter(p, 24f)
        Bella.draw(p, outfit, VW / 2, 1070f, 0.82f, pose(wave = waveT > 0f, talking = talking()))
        if (speech.isNotEmpty()) bubble(p, speech, 40f, 156f, VW - 80f, VW / 2 + 40f)
        val r = RoomKind.entries[day % RoomKind.entries.size]
        button(p, "clean", 40f, 1080f, VW - 80f, 96f, "Clean the ${r.title}!", rgb(0x3E, 0xC0, 0x8A), 36f)
        button(p, "shop", 40f, 1192f, (VW - 100f) / 2, 72f, "Shop", rgb(0xF2, 0xA6, 0x3B), 30f)
        button(p, "wardrobe", 60f + (VW - 100f) / 2, 1192f, (VW - 100f) / 2, 72f, "Wardrobe", rgb(0xB2, 0x7A, 0xE0), 30f)
        p.text("Day ${day + 1}", VW - 40f, 1064f, 24f, alpha(INK, 0.6f), ALIGN_RIGHT)
    }

    private fun drawClean(p: Painter, hud: Boolean = true) {
        Rooms.drawRoom(p, room, t)
        for (m in messes) if (!m.cleaned || m.fly in 0f..0.999f || (m.type.tool != Tool.HAND && m.dirt > 0f)) Rooms.drawMess(p, m)
        // Cursor: the tool follows your finger.
        if (hud && pDown && pressed == null && inScene(py) && playing()) {
            p.oval(px, py, brushRadius, brushRadius, alpha(tool.color, 0.18f))
            if (tool != Tool.HAND) Rooms.drawTool(p, tool, px + 10f, py - 50f + sin(t * 30f) * 4f, 80f)
        }

        // Top bar.
        p.rect(0f, 0f, VW, SCENE_TOP, rgb(0xFF, 0x7E, 0xA8))
        p.rect(0f, SCENE_TOP - 6f, VW, 6f, darker(rgb(0xFF, 0x7E, 0xA8), 0.15f))
        p.text("Day ${day + 1}: ${room.title}", 24f, 46f, 30f, WHITE)
        val left = messes.count { !it.cleaned }
        p.text("$left left", VW - 110f, 46f, 26f, WHITE, ALIGN_RIGHT)
        val frac = (timeLeft / timeLimit).coerceIn(0f, 1f)
        val barW = VW - 150f
        p.roundRect(24f, 66f, barW, 30f, 15f, alpha(WHITE, 0.4f))
        val low = timeLeft < 6f && (t * 4f).toInt() % 2 == 0
        p.roundRect(24f, 66f, (barW * frac).coerceAtLeast(30f), 30f, 15f, if (low) rgb(0xFF, 0x3A, 0x3A) else WHITE)
        p.text("${ceil(timeLeft).toInt()}s", 24f + barW / 2, 90f, 24f, if (frac > 0.5f) rgb(0xFF, 0x7E, 0xA8) else INK, ALIGN_CENTER)
        if (hud && !finished) {
            button(p, "quit", VW - 92f, 20f, 72f, 72f, "X", rgb(0xE0, 0x4A, 0x7A), 32f)
        }

        // Toolbar.
        p.rect(0f, SCENE_BOTTOM, VW, VH - SCENE_BOTTOM, rgb(0xFF, 0xF0, 0xF5))
        p.rect(0f, SCENE_BOTTOM, VW, 6f, rgb(0xFF, 0xC4, 0xDA))
        for ((i, tl) in Tool.entries.withIndex()) {
            val x = 16f + i * 174f
            val y = SCENE_BOTTOM + 24f
            val sel = tl == tool
            val need = messes.count { !it.cleaned && it.type.tool == tl }
            val pulse = hintT > 0f && hintTool == tl && (t * 6f).toInt() % 2 == 0
            val bg = when {
                sel -> tl.color
                pulse -> lighter(tl.color, 0.4f)
                else -> WHITE
            }
            p.roundRect(x, y + 6f, 166f, 180f, 30f, darker(tl.color, 0.2f))
            p.roundRect(x, y - (if (sel) 8f else 0f), 166f, 180f, 30f, bg)
            Rooms.drawTool(p, tl, x + 83f, y + 70f - (if (sel) 8f else 0f), 90f, sel)
            p.text(tl.label, x + 83f, y + 158f - (if (sel) 8f else 0f), 26f, if (sel) WHITE else INK, ALIGN_CENTER)
            if (need > 0) {
                p.oval(x + 146f, y + 14f, 22f, 22f, PINK)
                p.text("$need", x + 146f, y + 23f, 24f, WHITE, ALIGN_CENTER)
            }
            buttons["tool$i"] = floatArrayOf(x, y - 8f, 166f, 194f)
        }

        if (!hud) return
        // Hints, banners and floating numbers.
        if (hintT > 0f && hint.isNotEmpty()) {
            val a = (hintT / 0.3f).coerceAtMost(1f)
            val w = p.textWidth(hint, 30f) + 120f
            p.roundRect(VW / 2 - w / 2, SCENE_TOP + 20f, w, 70f, 35f, alpha(INK, 0.88f * a))
            hintTool?.let { Rooms.drawTool(p, it, VW / 2 - w / 2 + 50f, SCENE_TOP + 55f, 48f) }
            p.text(hint, VW / 2 + 30f, SCENE_TOP + 66f, 30f, alpha(WHITE, a), ALIGN_CENTER)
        }
        for (f in floats) {
            val a = (f.life / 0.4f).coerceAtMost(1f)
            p.outlinedText(f.text, f.x, f.y, 34f, alpha(f.color, a), alpha(INK, a))
        }
        if (readyT > 0f) {
            p.rect(0f, SCENE_TOP, VW, SCENE_BOTTOM - SCENE_TOP, alpha(BLACK, 0.3f))
            val txt = if (readyT > 0.5f) "Ready?" else "Go!"
            p.outlinedText(txt, VW / 2, 560f, 100f, WHITE, PINK)
            if (readyT > 0.5f) {
                p.text("Pick a tool and scrub the mess away!", VW / 2, 650f, 30f, WHITE, ALIGN_CENTER)
                p.text("Tap clothes and trash with Pick Up.", VW / 2, 700f, 30f, WHITE, ALIGN_CENTER)
            }
        }
        if (finished) {
            val sc = (finishT * 4f).coerceAtMost(1f)
            p.save()
            p.translate(VW / 2, 560f)
            p.scale(sc, sc)
            p.outlinedText(if (messes.all { it.cleaned }) "Sparkling clean!" else "Time's up!", 0f, 0f, 76f, WHITE, PINK)
            p.restore()
        }
    }

    private fun drawResult(p: Painter) {
        p.rect(0f, 0f, VW, VH, alpha(INK, 0.55f))
        val x = 50f
        val y = 260f
        val w = VW - 100f
        val h = 660f
        p.roundRect(x, y + 8f, w, h, 40f, alpha(BLACK, 0.2f))
        p.roundRect(x, y, w, h, 40f, WHITE)
        p.text(if (stars == 3) "Perfect!" else if (stars > 0) "Nice work!" else "Oops...", VW / 2, y + 80f, 52f, PINK, ALIGN_CENTER)
        for (i in 0 until 3) {
            val appear = ((screenT - 0.2f - i * 0.3f) * 5f).coerceIn(0f, 1f)
            val sx = VW / 2 + (i - 1) * 130f
            val sy = y + 190f - (if (i == 1) 20f else 0f)
            p.fill(star(sx, sy, 56f), rgb(0xE8, 0xE0, 0xE8))
            if (i < stars && appear > 0f) {
                p.save(); p.translate(sx, sy); p.scale(appear, appear)
                p.fill(star(0f, 0f, 56f), GOLD)
                p.fill(star(-8f, -10f, 20f), alpha(WHITE, 0.5f))
                p.restore()
            }
        }
        val done = messes.count { it.cleaned }
        p.text("Cleaned $done of ${messes.size} messes", VW / 2, y + 320f, 32f, INK, ALIGN_CENTER)
        p.coinIcon(VW / 2 - 110f, y + 390f, 26f)
        p.text("+$coinsGain coins", VW / 2 - 70f, y + 402f, 36f, INK)
        p.heartIcon(VW / 2 - 110f, y + 470f, 28f)
        p.text(if (heartsGain > 0) "+$heartsGain hearts" else "No hearts", VW / 2 - 70f, y + 482f, 36f, INK)
        if (stars == 0) p.text("Clean at least 75% to move on.", VW / 2, y + 540f, 26f, alpha(INK, 0.7f), ALIGN_CENTER)
        if (screenT > 0.8f) button(p, "bella", x + 60f, y + h - 110f, w - 120f, 84f, "See ${Story.NAME}", PINK, 36f)
    }

    private fun drawReact(p: Painter) {
        gradient(p, rgb(0xFF, 0xD0, 0xE2), rgb(0xFF, 0xF2, 0xEA))
        p.rect(0f, 960f, VW, VH - 960f, rgb(0xF0, 0xCC, 0xB0))
        heartMeter(p, 24f)
        // Until she has changed, she is still in her old outfit.
        val shown = if (level > seenLevel) Story.levels[if (outfitChoice in 0..seenLevel) outfitChoice else seenLevel].outfit else outfit
        Bella.draw(p, shown, VW / 2, 1150f, 0.86f, pose(wave = face == 1 && screenT < 2f, talking = talking()))
        bubble(p, speech, 40f, 156f, VW - 80f, VW / 2 + 40f)
        if (screenT > 0.8f) button(p, "react", 140f, 1170f, VW - 280f, 84f, if (level > seenLevel) "Hmm?" else "Continue", PINK, 34f)
    }

    private fun drawChange(p: Painter) {
        gradient(p, rgb(0xC8, 0x6A, 0xC0), rgb(0xFF, 0xA8, 0xC8))
        // A folding dressing screen with Bella changing behind it.
        val sway = sin(t * 3f) * 3f
        p.oval(VW / 2 + sway * 4f, 420f, 70f, 76f, rgb(0xF6, 0xD4, 0x7A))
        for (i in 0 until 3) {
            val x = 130f + i * 156f
            p.roundRect(x, 440f, 150f, 600f, 20f, rgb(0xFF, 0xE8, 0xF0))
            p.roundRect(x + 14f, 460f, 122f, 560f, 14f, rgb(0xFF, 0xC4, 0xDA))
            p.fill(heart(x + 75f, 700f, 40f), alpha(WHITE, 0.7f))
        }
        // Clothes flying over the screen.
        val k = (screenT * 1.3f) % 1f
        p.save()
        p.translate(VW / 2 + 220f * k, 430f - sin(k * 3.14f) * 200f)
        p.rotate(k * 360f)
        p.fill(poly(-30f, -30f, 30f, -30f, 40f, 30f, -40f, 30f), Story.levels[seenLevel.coerceAtMost(level)].outfit.main)
        p.restore()
        val dots = ".".repeat(((screenT * 3f).toInt() % 3) + 1)
        p.outlinedText("${Story.NAME} is getting ready$dots", VW / 2, 220f, 44f, WHITE, rgb(0x9A, 0x3A, 0x90))
        p.text("She wants to look nice for you!", VW / 2, 290f, 30f, WHITE, ALIGN_CENTER)
        for (i in 0 until 8) {
            val ph = (t * 1.5f + i * 0.4f) % 2f
            if (ph < 1f) p.fill(sparkle(80f + i * 80f, 1100f + (i % 2) * 60f, 20f * sin(ph * 3.14f)), WHITE)
        }
    }

    private fun drawReveal(p: Painter) {
        gradient(p, rgb(0x3A, 0x1E, 0x4E), rgb(0xA8, 0x3E, 0x7A))
        // Spotlight.
        p.fill(poly(VW / 2 - 60f, 0f, VW / 2 + 60f, 0f, VW / 2 + 300f, 1150f, VW / 2 - 300f, 1150f), alpha(WHITE, 0.1f))
        p.oval(VW / 2, 1150f, 280f, 40f, alpha(WHITE, 0.15f))
        val lv = Story.levels[level]
        val pop = (screenT * 2.5f).coerceAtMost(1f)
        Bella.draw(p, lv.outfit, VW / 2, 1150f, 0.78f * (0.85f + 0.15f * pop), Pose(mood = mood, time = t, face = 1, wave = screenT < 2.5f))
        p.outlinedText(lv.title + "!", VW / 2, 80f, 60f, WHITE, PINK)
        p.text("New outfit: ${lv.outfit.name}", VW / 2, 130f, 30f, rgb(0xFF, 0xE0, 0xEC), ALIGN_CENTER)
        bubble(p, lv.revealLine, 40f, 170f, VW - 80f, VW / 2 + 40f)
        if (screenT > 1f) {
            p.roundRect(40f, 1000f, VW - 80f, 140f, 30f, alpha(WHITE, 0.92f))
            p.text("Reward: ${lv.reward}", VW / 2, 1056f, 30f, PINK, ALIGN_CENTER)
            p.text(lv.perk, VW / 2, 1104f, 28f, INK, ALIGN_CENTER)
            button(p, "aww", 160f, 1170f, VW - 320f, 84f, if (level == Story.maxLevel) "Forever!" else "Aww!", PINK, 36f)
        }
        if (level == Story.maxLevel) {
            for (i in 0 until 10) {
                val ph = (t * 0.8f + i * 0.31f) % 1f
                p.fill(star(40f + i * 70f, 980f - ph * 700f, 8f), alpha(GOLD, 1f - ph))
            }
        }
    }

    private fun drawShop(p: Painter) {
        gradient(p, rgb(0xFF, 0xE8, 0xC8), rgb(0xFF, 0xF6, 0xEC))
        p.outlinedText("Shop", VW / 2, 100f, 76f, WHITE, rgb(0xF2, 0xA6, 0x3B))
        p.coinIcon(VW / 2 - 60f, 160f, 22f)
        p.text("$coins", VW / 2 - 28f, 172f, 34f, INK)
        var y = 220f
        p.text("Cleaning gear", 40f, y, 30f, alpha(INK, 0.6f))
        y += 20f
        for ((i, u) in upgradeList.withIndex()) {
            val lv = upgrades[i]
            p.roundRect(30f, y, VW - 60f, 140f, 30f, WHITE)
            Rooms.drawTool(p, Tool.entries[if (i == 0) 0 else if (i == 1) 1 else 3], 100f, y + 70f, 80f)
            p.text(u.name, 170f, y + 52f, 32f, INK)
            p.text(u.desc, 170f, y + 92f, 24f, alpha(INK, 0.7f))
            for (k in 0 until 3) p.fill(star(186f + k * 34f, y + 118f, 13f), if (k < lv) GOLD else rgb(0xE0, 0xD8, 0xE0))
            if (lv < 3) {
                val price = u.prices[lv]
                button(p, "up$i", VW - 220f, y + 34f, 170f, 72f, "$price", if (coins >= price) rgb(0x3E, 0xC0, 0x8A) else rgb(0xB8, 0xB0, 0xB8), 32f)
                p.coinIcon(VW - 196f, y + 70f, 14f)
            } else p.text("MAX", VW - 135f, y + 82f, 32f, GOLD, ALIGN_CENTER)
            y += 160f
        }
        y += 30f
        p.text("Gifts for ${Story.NAME}", 40f, y, 30f, alpha(INK, 0.6f))
        y += 20f
        for ((i, g) in giftList.withIndex()) {
            p.roundRect(30f, y, VW - 60f, 140f, 30f, WHITE)
            if (i == 0) {
                for (k in -1..1) {
                    p.line(100f + k * 8f, y + 110f, 100f + k * 20f, y + 60f, rgb(0x3E, 0x9A, 0x4A), 4f)
                    p.oval(100f + k * 20f, y + 54f, 16f, 15f, rgb(0xE8, 0x2A, 0x4A))
                }
                p.fill(poly(84f, y + 90f, 116f, y + 90f, 106f, y + 125f, 94f, y + 125f), rgb(0xFF, 0xE8, 0xF0))
            } else {
                p.fill(heart(100f, y + 74f, 44f), rgb(0xC8, 0x2A, 0x4A))
                p.line(60f, y + 60f, 140f, y + 60f, GOLD, 6f)
            }
            p.text(g.name, 170f, y + 52f, 32f, INK)
            p.heartIcon(186f, y + 92f, 16f)
            p.text("+${g.hearts} heart${if (g.hearts > 1) "s" else ""}", 210f, y + 102f, 26f, PINK)
            button(p, "gift$i", VW - 220f, y + 34f, 170f, 72f, "${g.price}", if (coins >= g.price) PINK else rgb(0xB8, 0xB0, 0xB8), 32f)
            p.coinIcon(VW - 196f, y + 70f, 14f)
            y += 160f
        }
        button(p, "home", 200f, 1170f, VW - 400f, 80f, "Back", rgb(0xB2, 0x7A, 0xE0), 32f)
    }

    private fun drawWardrobe(p: Painter) {
        gradient(p, rgb(0xE8, 0xD8, 0xFF), rgb(0xFF, 0xEE, 0xF6))
        p.outlinedText("Wardrobe", VW / 2, 96f, 70f, WHITE, rgb(0xB2, 0x7A, 0xE0))
        p.text("Tap an outfit to choose what ${Story.NAME} wears", VW / 2, 146f, 26f, alpha(INK, 0.7f), ALIGN_CENTER)
        val current = if (outfitChoice in 0..level) outfitChoice else level
        for ((i, lv) in Story.levels.withIndex()) {
            val col = i % 3
            val row = i / 3
            val cw = 216f
            val ch = 318f
            // Centre the last, lonely card.
            val x = if (i == Story.levels.size - 1 && col == 0) VW / 2 - cw / 2 else 20f + col * (cw + 12f)
            val y = 176f + row * (ch + 12f)
            val unlocked = i <= level
            p.roundRect(x, y, cw, ch, 26f, if (unlocked) WHITE else rgb(0xD8, 0xD0, 0xE0))
            if (i == current) {
                p.stroke(shape {
                    moveTo(x + 26f, y); lineTo(x + cw - 26f, y); quadTo(x + cw, y, x + cw, y + 26f)
                    lineTo(x + cw, y + ch - 26f); quadTo(x + cw, y + ch, x + cw - 26f, y + ch)
                    lineTo(x + 26f, y + ch); quadTo(x, y + ch, x, y + ch - 26f)
                    lineTo(x, y + 26f); quadTo(x, y, x + 26f, y); close()
                }, PINK, 6f)
            }
            if (unlocked) {
                Bella.draw(p, lv.outfit, x + cw / 2, y + ch - 56f, 0.27f, Pose(mood = i / 6f, time = t + i))
                p.text(lv.outfit.name, x + cw / 2, y + ch - 20f, if (lv.outfit.name.length > 16) 17f else 20f, INK, ALIGN_CENTER)
            } else {
                p.oval(x + cw / 2, y + 110f, 40f, 44f, rgb(0xB8, 0xAE, 0xC4))
                p.fill(shape { moveTo(x + cw / 2 - 70f, y + 260f); quadTo(x + cw / 2, y + 120f, x + cw / 2 + 70f, y + 260f); close() }, rgb(0xB8, 0xAE, 0xC4))
                p.text("?", x + cw / 2, y + 128f, 50f, WHITE, ALIGN_CENTER)
                p.heartIcon(x + cw / 2 - 30f, y + ch - 30f, 14f)
                p.text("${lv.hearts}", x + cw / 2 - 8f, y + ch - 20f, 24f, INK)
            }
            buttons["wear$i"] = floatArrayOf(x, y, cw, ch)
        }
        button(p, "home", 200f, 1180f, VW - 400f, 80f, "Back", rgb(0xB2, 0x7A, 0xE0), 32f)
    }

    private fun drawParticles(p: Painter) {
        for (q in particles) {
            if (q.life > q.maxLife) continue
            val a = (q.life / q.maxLife).coerceIn(0f, 1f)
            when (q.kind) {
                0 -> p.oval(q.x, q.y, q.size, q.size * 0.8f, alpha(q.color, a * 0.8f))
                1 -> p.fill(sparkle(q.x, q.y, q.size * (0.5f + a * 0.5f)), alpha(q.color, a))
                2 -> p.heartIcon(q.x, q.y, q.size, alpha(q.color, (a * 2f).coerceAtMost(1f)))
                3 -> {
                    p.oval(q.x, q.y, q.size, q.size, alpha(q.color, a * 0.7f))
                    p.oval(q.x - q.size * 0.3f, q.y - q.size * 0.3f, q.size * 0.25f, q.size * 0.25f, alpha(WHITE, a))
                }
            }
        }
    }

    // ================================================================ test hooks

    /** Jumps straight to a screen; used by the screenshot tests. */
    internal fun debugScreen(s: Screen, setup: Game.() -> Unit = {}) {
        setup()
        go(s)
        screenT = 3f
        speechT = 10f
    }

    internal fun debugSetHearts(h: Int, c: Int = coins) { hearts = h; coins = c; seenLevel = level }
    internal fun debugSay(s: String) = say(s)
    internal fun debugFinish(dirtLeft: Boolean) {
        if (!dirtLeft) for (m in messes) { m.cleaned = true; m.dirt = 0f }
        timeLeft = if (dirtLeft) 0f else timeLimit * 0.6f
        finishRoom()
    }
}
