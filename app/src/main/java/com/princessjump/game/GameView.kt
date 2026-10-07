package com.princessjump.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * The whole game: a princess in a pink dress runs through a fairytale meadow
 * and jumps over rocks, thorn bushes, pumpkins and giant mushrooms.
 *
 * The world is drawn in "units" where 1 unit = 1% of the screen height, so the
 * game looks and plays the same on every phone. Tap to jump, tap again while
 * in the air to double jump.
 *
 * Every level is a new land with its own colours, and gets a little faster
 * and trickier. The top five scores are saved on the phone.
 */
class GameView(context: Context, private val sound: Sound) : View(context) {

    private enum class State { READY, PLAYING, GAME_OVER }

    private class Obstacle(var x: Float, val type: Int, val w: Float, val h: Float)
    private class Star(var x: Float, val y: Float)
    private class Sparkle(var x: Float, var y: Float, val vx: Float, val vy: Float, var life: Float, val color: Int)
    private class Cloud(var x: Float, val y: Float, val size: Float)
    private class Entry(val score: Int, val level: Int)

    /** The look of one land. Colours are ARGB. */
    private class Theme(
        val name: String,
        val skyTop: Long, val skyMid: Long, val skyBottom: Long,
        val hill: Long, val castle: Long,
        val grass: Long, val grassDark: Long, val dirt: Long,
        val night: Boolean,
    )

    private companion object {
        const val GROUND = 80f
        const val PRINCESS_X = 25f
        const val GRAVITY = 300f
        const val JUMP_VELOCITY = 105f
        const val START_SPEED = 55f
        const val MAX_SPEED = 130f
        const val SPEED_GAIN = 1.4f

        const val ROCK = 0
        const val BUSH = 1
        const val PUMPKIN = 2
        const val MUSHROOM = 3

        // Distance needed to finish one level (about 190 points)
        const val LEVEL_LENGTH = 1500f
        const val TOP_SCORES = 5

        val THEMES = arrayOf(
            Theme("Flower Meadow", 0xFFA9D8FF, 0xFFE7D4FF, 0xFFFFD6EC,
                0xFFB9E6B0, 0xFFD9C2F0, 0xFF7BCB72, 0xFF5DAE57, 0xFFE2B98F, false),
            Theme("Enchanted Forest", 0xFF8FD3C9, 0xFFC4EBD3, 0xFFF1F8D8,
                0xFF6FB57E, 0xFFB8D8C8, 0xFF4E9F59, 0xFF3D8648, 0xFFB98C66, false),
            Theme("Sunset Hills", 0xFFFF9F80, 0xFFFFC594, 0xFFFFE6B8,
                0xFFE9B784, 0xFFF2B8C6, 0xFF9CC96B, 0xFF7FAE55, 0xFFD9A273, false),
            Theme("Candy Clouds", 0xFFFFB8DD, 0xFFFFD6EE, 0xFFFFF2FA,
                0xFFF9C8E2, 0xFFFFFFFF, 0xFFF59AC8, 0xFFE57FB4, 0xFFFFE0EF, false),
            Theme("Starry Night", 0xFF1C1846, 0xFF3A2D78, 0xFF7957A8,
                0xFF3F3878, 0xFF5A4E96, 0xFF3E7C5A, 0xFF2E6447, 0xFF6A5040, true),
        )

        fun themeFor(level: Int) = THEMES[(level - 1) % THEMES.size]

        fun lerpColor(a: Long, b: Long, t: Float): Int {
            fun ch(c: Long, shift: Int) = ((c shr shift) and 0xFFL).toFloat()
            fun mix(shift: Int) = (ch(a, shift) + (ch(b, shift) - ch(a, shift)) * t).toInt() shl shift
            return mix(24) or mix(16) or mix(8) or mix(0)
        }
    }

    private val prefs = context.getSharedPreferences("princess_jump", Context.MODE_PRIVATE)
    private val topScores = loadTopScores()
    private var highScore = topScores.firstOrNull()?.score ?: 0
    private var newBest = false
    private var newEntry: Entry? = null

    // Levels and the colour change between lands
    private var level = 1
    private var themeFrom = THEMES[0]
    private var themeTo = THEMES[0]
    private var themeBlend = 1f
    private var appliedBlend = -1f
    private var night = 0f
    private var levelBanner = 0f
    private val skyDots = ArrayList<FloatArray>()

    private var state = State.READY
    private var unit = 1f
    private var worldW = 200f

    // Princess: height of her feet above the ground (up is positive)
    private var jumpHeight = 0f
    private var velocity = 0f
    private var jumpsUsed = 0
    private var runPhase = 0f

    private var speed = START_SPEED
    private var playTime = 0f
    private var time = 0f
    private var distance = 0f
    private var bonus = 0
    private var nextSpawn = 60f
    private var deadTimer = 0f

    private var groundScroll = 0f
    private var hillScroll = 0f

    private val obstacles = ArrayList<Obstacle>()
    private val stars = ArrayList<Star>()
    private val sparkles = ArrayList<Sparkle>()
    private val clouds = ArrayList<Cloud>()

    private var lastFrame = 0L
    private var running = false
    private var paused = false

    private val score get() = (distance / 8f).toInt() + bonus

    // ---------- Paints ----------
    private fun fill(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.FILL
    }

    private fun stroke(color: Int, width: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val skyPaint = Paint()
    private val sunPaint = fill(0xFFFFF3B0.toInt())
    private val sunGlowPaint = fill(0x55FFF3B0)
    private val cloudPaint = fill(Color.WHITE)
    private val hillPaint = fill(0xFFB9E6B0.toInt())
    private val castlePaint = fill(0xFFD9C2F0.toInt())
    private val castleRoofPaint = fill(0xFFF29BC9.toInt())
    private val castleWindowPaint = fill(0xFFB79AD9.toInt())
    private val grassPaint = fill(0xFF7BCB72.toInt())
    private val grassDarkPaint = fill(0xFF5DAE57.toInt())
    private val dirtPaint = fill(0xFFE2B98F.toInt())
    private val flowerPinkPaint = fill(0xFFFF8FC8.toInt())
    private val flowerWhitePaint = fill(Color.WHITE)
    private val flowerCenterPaint = fill(0xFFFFD54F.toInt())

    private val rockPaint = fill(0xFF9E9AA8.toInt())
    private val rockLightPaint = fill(0xFFC4C0CC.toInt())
    private val bushPaint = fill(0xFF3E8E4A.toInt())
    private val bushLightPaint = fill(0xFF56AD5F.toInt())
    private val thornPaint = fill(0xFF2C5E33.toInt())
    private val berryPaint = fill(0xFFE53950.toInt())
    private val pumpkinPaint = fill(0xFFFF9A2E.toInt())
    private val pumpkinLinePaint = stroke(0xFFD9741A.toInt(), 0.5f)
    private val stemPaint = fill(0xFF5E8C31.toInt())
    private val mushStemPaint = fill(0xFFFFF4DF.toInt())
    private val mushCapPaint = fill(0xFFE53950.toInt())
    private val mushDotPaint = fill(Color.WHITE)

    private val starPaint = fill(0xFFFFD54F.toInt())
    private val starEdgePaint = stroke(0xFFF2A900.toInt(), 0.35f)
    private val sparklePaint = fill(Color.WHITE)

    private val dressPaint = fill(0xFFFF6FB5.toInt())
    private val dressLightPaint = fill(0xFFFFA8D5.toInt())
    private val dressFoldPaint = stroke(0xFFE8559F.toInt(), 0.3f)
    private val bodicePaint = fill(0xFFF0579F.toInt())
    private val sashPaint = fill(0xFFFFD6EA.toInt())
    private val skinPaint = fill(0xFFFFDCC4.toInt())
    private val limbPaint = stroke(0xFFFFDCC4.toInt(), 1.1f)
    private val shoePaint = fill(0xFFC2185B.toInt())
    private val hairPaint = fill(0xFFFFCB45.toInt())
    private val hairDarkPaint = fill(0xFFF0B020.toInt())
    private val eyePaint = fill(0xFF3B2A4A.toInt())
    private val eyeLinePaint = stroke(0xFF3B2A4A.toInt(), 0.3f)
    private val cheekPaint = fill(0x66FF6F9F)
    private val crownPaint = fill(0xFFFFD54F.toInt())
    private val crownEdgePaint = stroke(0xFFE6A800.toInt(), 0.2f)
    private val jewelPaint = fill(0xFFE91E63.toInt())
    private val ribbonPaint = fill(0xFFE91E63.toInt())

    private val shadowPaint = fill(0x33000000)
    private val overlayPaint = fill(0x88FFFFFF.toInt())
    private val moonPaint = fill(0xFFFFF6D5.toInt())
    private val craterPaint = fill(0xFFEDE0B8.toInt())
    private val skyDotPaint = fill(Color.WHITE)
    private val buttonPaint = fill(0x99FFFFFF.toInt())
    private val notePaint = fill(0xFFE0408F.toInt())
    private val noteStemPaint = stroke(0xFFE0408F.toInt(), 0.5f)
    private val noteBeamPaint = stroke(0xFFE0408F.toInt(), 0.9f)
    private val mutePaint = stroke(0xFF9C3D6B.toInt(), 0.7f)

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE0408F.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(6f, 0f, 3f, 0x66FFFFFF)
    }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF6A3D7A.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    private val hudLeftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f, 0f, 2f, 0x88B0306F.toInt())
    }
    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.RIGHT
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        setShadowLayer(4f, 0f, 2f, 0x88B0306F.toInt())
    }

    private val path = Path()
    private val rect = RectF()
    private val starPath = Path().apply {
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) 1f else 0.45f
            val a = Math.PI / 5 * i - Math.PI / 2
            val x = (cos(a) * r).toFloat()
            val y = (sin(a) * r).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    init {
        isFocusable = true
        isClickable = true
    }

    // ---------- Lifecycle ----------
    fun resume() {
        running = true
        lastFrame = 0L
        invalidate()
    }

    fun pause() {
        running = false
        if (state == State.PLAYING) paused = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        unit = h / 100f
        worldW = w / unit
        titlePaint.textSize = 12f * unit
        textPaint.textSize = 5f * unit
        scorePaint.textSize = 6f * unit
        hudLeftPaint.textSize = 4.5f * unit
        appliedBlend = -1f
        skyDots.clear()
        repeat(45) {
            skyDots.add(floatArrayOf(Random.nextFloat() * worldW, 2f + Random.nextFloat() * 50f, Random.nextFloat() * 6f))
        }
        if (clouds.isEmpty()) {
            var x = 10f
            while (x < worldW + 40f) {
                clouds.add(Cloud(x, Random.nextFloat() * 25f + 8f, Random.nextFloat() * 0.6f + 0.8f))
                x += Random.nextFloat() * 40f + 35f
            }
        }
    }

    // ---------- Input ----------
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (event.x < 14f * unit && event.y < 15f * unit) {
                sound.toggleMute()
                return true
            }
            when (state) {
                State.READY -> {
                    resetGame()
                    state = State.PLAYING
                    jump()
                }
                State.PLAYING -> if (paused) paused = false else jump()
                State.GAME_OVER -> if (deadTimer > 0.7f) {
                    resetGame()
                    state = State.PLAYING
                }
            }
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun jump() {
        if (jumpsUsed >= 2) return
        velocity = if (jumpsUsed == 0) JUMP_VELOCITY else JUMP_VELOCITY * 0.85f
        if (jumpsUsed == 1) burst(PRINCESS_X, GROUND - jumpHeight, 8, 0xFFFFC1E3.toInt())
        sound.play(if (jumpsUsed == 0) Sound.Sfx.JUMP else Sound.Sfx.DOUBLE_JUMP)
        jumpsUsed++
    }

    private fun resetGame() {
        obstacles.clear()
        stars.clear()
        sparkles.clear()
        jumpHeight = 0f
        velocity = 0f
        jumpsUsed = 0
        speed = START_SPEED
        playTime = 0f
        distance = 0f
        bonus = 0
        nextSpawn = 60f
        newBest = false
        newEntry = null
        paused = false
        level = 1
        levelBanner = 2.5f
        changeTheme(THEMES[0])
    }

    private fun changeTheme(target: Theme) {
        if (target === themeTo) return
        themeFrom = themeTo
        themeTo = target
        themeBlend = 0f
    }

    // ---------- Game loop ----------
    private fun update(dt: Float) {
        time += dt
        if (state == State.PLAYING && paused) return

        val scroll = when (state) {
            State.PLAYING -> speed
            State.READY -> 25f
            State.GAME_OVER -> 0f
        }
        themeBlend = min(1f, themeBlend + dt / 1.5f)
        levelBanner -= dt
        groundScroll += scroll * dt
        hillScroll += scroll * 0.25f * dt
        for (c in clouds) {
            c.x -= (scroll * 0.08f + 2f) * dt
            if (c.x < -30f) c.x += worldW + 60f
        }
        updateSparkles(dt)

        when (state) {
            State.READY -> runPhase += dt * 6f
            State.GAME_OVER -> deadTimer += dt
            State.PLAYING -> updatePlaying(dt)
        }
    }

    private fun updatePlaying(dt: Float) {
        playTime += dt
        speed = min(MAX_SPEED, START_SPEED + (level - 1) * 6f + playTime * SPEED_GAIN)
        val move = speed * dt
        distance += move

        val reached = 1 + (distance / LEVEL_LENGTH).toInt()
        if (reached > level) {
            level = reached
            levelBanner = 2.5f
            changeTheme(themeFor(level))
            sound.play(Sound.Sfx.LEVEL_UP)
            burst(PRINCESS_X, GROUND - jumpHeight - 10f, 24, 0xFFFFE27A.toInt())
        }

        // Princess physics
        velocity -= GRAVITY * dt
        jumpHeight += velocity * dt
        if (jumpHeight <= 0f) {
            jumpHeight = 0f
            velocity = 0f
            jumpsUsed = 0
            runPhase += dt * speed * 0.16f
        }

        // Move world
        for (o in obstacles) o.x -= move
        obstacles.removeAll { it.x + it.w < -10f }
        for (s in stars) s.x -= move
        stars.removeAll { it.x < -10f }

        nextSpawn -= move
        if (nextSpawn <= 0f) spawnObstacle()

        // Collisions (hitboxes a little smaller than the drawings, to be fair)
        val feet = GROUND - jumpHeight
        val pl = PRINCESS_X - 3.2f
        val pr = PRINCESS_X + 3.2f
        val pt = feet - 17.5f
        for (o in obstacles) {
            val inset = o.w * 0.15f
            val ol = o.x + inset
            val oRight = o.x + o.w - inset
            val ot = GROUND - o.h + 1.2f
            if (pr > ol && pl < oRight && feet > ot && pt < GROUND) {
                crash()
                return
            }
        }
        val starIter = stars.iterator()
        while (starIter.hasNext()) {
            val s = starIter.next()
            if (s.x + 3f > pl && s.x - 3f < pr && s.y + 3f > pt && s.y - 3f < feet) {
                starIter.remove()
                bonus += 10
                sound.play(Sound.Sfx.STAR)
                burst(s.x, s.y, 14, 0xFFFFE27A.toInt())
            }
        }
    }

    private fun spawnObstacle() {
        val type = when {
            level >= 2 && Random.nextFloat() < 0.15f + 0.05f * min(level, 5) -> MUSHROOM
            else -> Random.nextInt(3)
        }
        val (w, h) = when (type) {
            ROCK -> Pair(7f + Random.nextFloat() * 4f, 5f + Random.nextFloat() * 3f)
            BUSH -> Pair(8f + Random.nextFloat() * 4f, 7f + Random.nextFloat() * 3f)
            PUMPKIN -> Pair(9f + Random.nextFloat() * 2f, 7.5f + Random.nextFloat() * 1.5f)
            else -> Pair(8f, 12f + Random.nextFloat() * 2.5f)
        }
        val x = worldW + 5f
        obstacles.add(Obstacle(x, type, w, h))

        // Sometimes a twinkling star floats above the obstacle
        when {
            Random.nextFloat() < 0.4f -> stars.add(Star(x + w / 2f, GROUND - h - 14f))
            Random.nextFloat() < 0.15f -> stars.add(Star(x + w / 2f + 10f, GROUND - 40f))
        }

        // Occasionally a pair of obstacles close together
        if (level >= 3 && type != MUSHROOM && Random.nextFloat() < 0.2f) {
            val w2 = 6f + Random.nextFloat() * 2f
            obstacles.add(Obstacle(x + w + 4f, ROCK, w2, 4.5f + Random.nextFloat() * 1.5f))
        }

        val minGap = speed * 0.85f + 18f
        nextSpawn = minGap + Random.nextFloat() * (50f - 5f * min(level - 1, 5))
    }

    private fun crash() {
        state = State.GAME_OVER
        deadTimer = 0f
        burst(PRINCESS_X, GROUND - jumpHeight - 10f, 20, 0xFFFF8FC8.toInt())
        sound.play(Sound.Sfx.CRASH)
        newBest = score > highScore
        recordScore(Entry(score, level))
    }

    // ---------- High scores ----------
    private fun loadTopScores(): MutableList<Entry> {
        val list = ArrayList<Entry>()
        prefs.getString("top_scores", null)?.split(",")?.forEach { item ->
            val parts = item.split(":")
            val sc = parts.getOrNull(0)?.toIntOrNull()
            val lv = parts.getOrNull(1)?.toIntOrNull()
            if (sc != null && lv != null) list.add(Entry(sc, lv))
        }
        // Keep the best score from the first version of the game
        val old = prefs.getInt("high_score", 0)
        if (list.isEmpty() && old > 0) list.add(Entry(old, 0))
        return list
    }

    private fun recordScore(entry: Entry) {
        if (entry.score <= 0) return
        topScores.add(entry)
        topScores.sortByDescending { it.score }
        while (topScores.size > TOP_SCORES) topScores.removeAt(topScores.size - 1)
        if (entry in topScores) newEntry = entry
        highScore = topScores.first().score
        prefs.edit()
            .putString("top_scores", topScores.joinToString(",") { "${it.score}:${it.level}" })
            .putInt("high_score", highScore)
            .apply()
    }

    private fun burst(x: Float, y: Float, count: Int, color: Int) {
        repeat(count) {
            val a = Random.nextFloat() * Math.PI.toFloat() * 2f
            val s = 10f + Random.nextFloat() * 25f
            sparkles.add(Sparkle(x, y, cos(a) * s, sin(a) * s, 0.5f + Random.nextFloat() * 0.4f, color))
        }
    }

    private fun updateSparkles(dt: Float) {
        val sparkIter = sparkles.iterator()
        while (sparkIter.hasNext()) {
            val s = sparkIter.next()
            s.x += s.vx * dt
            s.y += s.vy * dt
            s.life -= dt
            if (s.life <= 0f) sparkIter.remove()
        }
    }

    // ---------- Drawing ----------
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = System.nanoTime()
        val dt = if (lastFrame == 0L) 0f else min((now - lastFrame) / 1_000_000_000f, 0.05f)
        lastFrame = now
        update(dt)

        canvas.save()
        canvas.scale(unit, unit)
        drawBackground(canvas)
        for (o in obstacles) drawObstacle(canvas, o)
        for (s in stars) drawStar(canvas, s.x, s.y + sin(time * 4f + s.x) * 0.8f, 2.8f, time * 90f)
        drawPrincess(canvas)
        for (s in sparkles) {
            sparklePaint.color = s.color
            sparklePaint.alpha = (min(1f, s.life * 2f) * 255).toInt()
            canvas.drawCircle(s.x, s.y, 0.6f, sparklePaint)
        }
        canvas.restore()

        drawHud(canvas)

        if (running) postInvalidateOnAnimation()
    }

    private fun applyTheme() {
        if (themeBlend == appliedBlend) return
        appliedBlend = themeBlend
        val a = themeFrom
        val b = themeTo
        val t = themeBlend
        skyPaint.shader = LinearGradient(
            0f, 0f, 0f, GROUND,
            intArrayOf(lerpColor(a.skyTop, b.skyTop, t), lerpColor(a.skyMid, b.skyMid, t), lerpColor(a.skyBottom, b.skyBottom, t)),
            floatArrayOf(0f, 0.6f, 1f),
            Shader.TileMode.CLAMP
        )
        hillPaint.color = lerpColor(a.hill, b.hill, t)
        castlePaint.color = lerpColor(a.castle, b.castle, t)
        grassPaint.color = lerpColor(a.grass, b.grass, t)
        grassDarkPaint.color = lerpColor(a.grassDark, b.grassDark, t)
        dirtPaint.color = lerpColor(a.dirt, b.dirt, t)
        night = (if (a.night) 1f else 0f) * (1f - t) + (if (b.night) 1f else 0f) * t
        // castle windows light up at night
        castleWindowPaint.color = lerpColor(0xFFB79AD9, 0xFFFFE38A, night)
        sunPaint.alpha = ((1f - night) * 255).toInt()
        sunGlowPaint.alpha = ((1f - night) * 0x55).toInt()
        moonPaint.alpha = (night * 255).toInt()
        craterPaint.alpha = (night * 255).toInt()
        cloudPaint.alpha = ((1f - 0.7f * night) * 255).toInt()
    }

    private fun drawBackground(c: Canvas) {
        applyTheme()
        c.drawRect(0f, 0f, worldW, GROUND, skyPaint)

        val sunX = worldW * 0.82f
        if (night < 1f) {
            c.drawCircle(sunX, 16f, 10f + sin(time * 2f) * 0.6f, sunGlowPaint)
            c.drawCircle(sunX, 16f, 7f, sunPaint)
        }
        if (night > 0f) {
            for (d in skyDots) {
                val twinkle = 0.55f + 0.45f * sin(time * 3f + d[2])
                skyDotPaint.alpha = (night * twinkle * 255).toInt()
                c.drawCircle(d[0], d[1], 0.35f, skyDotPaint)
            }
            c.drawCircle(sunX, 16f, 6f, moonPaint)
            c.drawCircle(sunX - 2f, 14.5f, 1.2f, craterPaint)
            c.drawCircle(sunX + 1.8f, 17.5f, 0.9f, craterPaint)
        }

        for (cl in clouds) {
            val s = cl.size
            c.drawCircle(cl.x, cl.y, 4f * s, cloudPaint)
            c.drawCircle(cl.x + 4.5f * s, cl.y - 1.5f * s, 5f * s, cloudPaint)
            c.drawCircle(cl.x + 9.5f * s, cl.y, 4f * s, cloudPaint)
            c.drawRect(cl.x, cl.y, cl.x + 9.5f * s, cl.y + 4f * s, cloudPaint)
        }

        // Rolling hills with castles far away
        val tile = 90f
        var index = floor(hillScroll / tile).toInt() - 1
        var x = -(hillScroll - floor(hillScroll / tile) * tile) - tile
        while (x < worldW + tile) {
            val cx = x + tile / 2f
            if (Math.floorMod(index, 3) == 0) drawCastle(c, cx, GROUND - 17f)
            c.drawCircle(cx, GROUND + 28f, 46f, hillPaint)
            x += tile
            index++
        }

        // Ground
        c.drawRect(0f, GROUND, worldW, 101f, dirtPaint)
        c.drawRect(0f, GROUND, worldW, GROUND + 4f, grassPaint)
        val step = 6f
        var gx = -(groundScroll % step)
        while (gx < worldW + step) {
            c.drawCircle(gx, GROUND + 4f, 1.5f, grassDarkPaint)
            gx += step
        }
        val fStep = 23f
        var fx = -(groundScroll % fStep)
        var fi = floor(groundScroll / fStep).toInt()
        while (fx < worldW + fStep) {
            val fy = GROUND + 9f + Math.floorMod(fi * 7, 9).toFloat()
            val petal = if (fi % 2 == 0) flowerPinkPaint else flowerWhitePaint
            c.drawCircle(fx - 0.9f, fy, 0.9f, petal)
            c.drawCircle(fx + 0.9f, fy, 0.9f, petal)
            c.drawCircle(fx, fy - 0.9f, 0.9f, petal)
            c.drawCircle(fx, fy + 0.9f, 0.9f, petal)
            c.drawCircle(fx, fy, 0.6f, flowerCenterPaint)
            fx += fStep
            fi++
        }
    }

    private fun drawCastle(c: Canvas, cx: Float, base: Float) {
        // main keep
        c.drawRect(cx - 9f, base - 14f, cx + 9f, base + 6f, castlePaint)
        // towers
        c.drawRect(cx - 14f, base - 20f, cx - 8f, base + 6f, castlePaint)
        c.drawRect(cx + 8f, base - 20f, cx + 14f, base + 6f, castlePaint)
        c.drawRect(cx - 3f, base - 26f, cx + 3f, base - 10f, castlePaint)
        drawRoof(c, cx - 11f, base - 20f, 4f, 8f)
        drawRoof(c, cx + 11f, base - 20f, 4f, 8f)
        drawRoof(c, cx, base - 26f, 4f, 9f)
        // windows & door
        c.drawCircle(cx, base - 20f, 1.2f, castleWindowPaint)
        c.drawCircle(cx - 11f, base - 14f, 1f, castleWindowPaint)
        c.drawCircle(cx + 11f, base - 14f, 1f, castleWindowPaint)
        rect.set(cx - 2.5f, base - 5f, cx + 2.5f, base + 6f)
        c.drawRoundRect(rect, 2.5f, 2.5f, castleWindowPaint)
        // flag
        val wave = sin(time * 5f) * 0.6f
        path.reset()
        path.moveTo(cx, base - 35f)
        path.lineTo(cx + 4.5f, base - 34f + wave)
        path.lineTo(cx, base - 33f)
        path.close()
        c.drawPath(path, castleRoofPaint)
    }

    private fun drawRoof(c: Canvas, cx: Float, base: Float, halfW: Float, h: Float) {
        path.reset()
        path.moveTo(cx - halfW, base)
        path.lineTo(cx + halfW, base)
        path.lineTo(cx, base - h)
        path.close()
        c.drawPath(path, castleRoofPaint)
    }

    private fun drawObstacle(c: Canvas, o: Obstacle) {
        val l = o.x
        val r = o.x + o.w
        val t = GROUND - o.h
        val cx = o.x + o.w / 2f
        rect.set(l - 0.5f, GROUND - 0.8f, r + 0.5f, GROUND + 1.2f)
        c.drawOval(rect, shadowPaint)
        when (o.type) {
            ROCK -> {
                rect.set(l, t, r, GROUND + 0.5f)
                c.drawRoundRect(rect, o.w * 0.45f, o.h * 0.6f, rockPaint)
                rect.set(l + o.w * 0.2f, t + o.h * 0.15f, l + o.w * 0.55f, t + o.h * 0.45f)
                c.drawOval(rect, rockLightPaint)
            }
            BUSH -> {
                // thorns
                path.reset()
                val spikes = 5
                for (i in 0..spikes) {
                    val sx = l + o.w * i / spikes
                    path.moveTo(sx - 1f, t + 3f)
                    path.lineTo(sx, t - 1.2f)
                    path.lineTo(sx + 1f, t + 3f)
                }
                c.drawPath(path, thornPaint)
                c.drawCircle(cx - o.w * 0.22f, GROUND - o.h * 0.4f, o.h * 0.42f, bushPaint)
                c.drawCircle(cx + o.w * 0.22f, GROUND - o.h * 0.4f, o.h * 0.42f, bushPaint)
                c.drawCircle(cx, t + o.h * 0.42f, o.h * 0.42f, bushPaint)
                c.drawCircle(cx - o.w * 0.12f, t + o.h * 0.35f, o.h * 0.2f, bushLightPaint)
                c.drawCircle(cx - o.w * 0.25f, GROUND - o.h * 0.45f, 0.7f, berryPaint)
                c.drawCircle(cx + o.w * 0.18f, t + o.h * 0.5f, 0.7f, berryPaint)
                c.drawCircle(cx + o.w * 0.3f, GROUND - o.h * 0.25f, 0.7f, berryPaint)
            }
            PUMPKIN -> {
                rect.set(cx - 1f, t - 1.5f, cx + 1f, t + 1f)
                c.drawRect(rect, stemPaint)
                rect.set(l, t, r, GROUND + 0.3f)
                c.drawOval(rect, pumpkinPaint)
                rect.set(cx - o.w * 0.22f, t + 0.3f, cx + o.w * 0.22f, GROUND)
                c.drawOval(rect, pumpkinLinePaint)
                c.drawLine(cx, t + 0.5f, cx, GROUND - 0.3f, pumpkinLinePaint)
            }
            MUSHROOM -> {
                val capH = o.h * 0.45f
                rect.set(cx - o.w * 0.2f, t + capH * 0.6f, cx + o.w * 0.2f, GROUND)
                c.drawRoundRect(rect, 1f, 1f, mushStemPaint)
                rect.set(l, t, r, t + capH * 2f)
                c.drawArc(rect, 180f, 180f, true, mushCapPaint)
                c.drawCircle(cx, t + capH * 0.4f, 1.1f, mushDotPaint)
                c.drawCircle(cx - o.w * 0.28f, t + capH * 0.75f, 0.8f, mushDotPaint)
                c.drawCircle(cx + o.w * 0.28f, t + capH * 0.75f, 0.8f, mushDotPaint)
            }
        }
    }

    private fun drawStar(c: Canvas, x: Float, y: Float, size: Float, rotation: Float) {
        c.save()
        c.translate(x, y)
        c.rotate(rotation)
        c.scale(size, size)
        c.drawPath(starPath, starPaint)
        starEdgePaint.strokeWidth = 0.35f / size
        c.drawPath(starPath, starEdgePaint)
        c.restore()
    }

    private fun drawPrincess(c: Canvas) {
        val feet = GROUND - jumpHeight
        // shadow on the ground shrinks as she jumps higher
        val shadowW = 5f * (1f - min(jumpHeight / 40f, 0.6f))
        rect.set(PRINCESS_X - shadowW, GROUND - 0.7f, PRINCESS_X + shadowW, GROUND + 1f)
        c.drawOval(rect, shadowPaint)

        c.save()
        c.translate(PRINCESS_X, feet)
        val inAir = jumpHeight > 0.01f
        val moving = state != State.GAME_OVER
        val swing = if (!inAir && moving) sin(runPhase) else 0f
        val bob = if (!inAir && moving) abs(swing) * 0.5f else 0f
        c.translate(0f, -bob)

        // flowing ponytail behind her
        val flow = sin(time * 12f) * 0.7f
        path.reset()
        path.moveTo(-1.8f, -19f)
        path.quadTo(-6.5f, -18.5f + flow, -7.2f, -14f + flow)
        path.quadTo(-4.5f, -15.5f, -2.2f, -15.8f)
        path.close()
        c.drawPath(path, hairDarkPaint)

        // legs & shoes
        if (inAir) {
            c.drawLine(-0.9f, -3f, -2f, -1.2f, limbPaint)
            c.drawLine(0.9f, -3f, 1.6f, -0.6f, limbPaint)
            c.drawCircle(-2.1f, -0.9f, 0.8f, shoePaint)
            c.drawCircle(1.8f, -0.4f, 0.8f, shoePaint)
        } else {
            val l1 = -0.8f + swing * 2f
            val l2 = 0.8f - swing * 2f
            c.drawLine(-0.8f, -3f, l1, -0.4f + bob, limbPaint)
            c.drawLine(0.8f, -3f, l2, -0.4f + bob, limbPaint)
            rect.set(l1 - 0.8f, -0.9f + bob, l1 + 1.1f, 0.2f + bob)
            c.drawOval(rect, shoePaint)
            rect.set(l2 - 0.8f, -0.9f + bob, l2 + 1.1f, 0.2f + bob)
            c.drawOval(rect, shoePaint)
        }

        // the pink dress: a flared skirt with a scalloped hem
        val flare = if (inAir) 1.2f else abs(swing) * 0.4f
        val hemL = -5f - flare
        val hemR = 5f + flare
        val hemY = -2.5f
        path.reset()
        path.moveTo(-1.9f, -11f)
        path.lineTo(1.9f, -11f)
        path.lineTo(hemR, hemY)
        val scallops = 5
        val sw = (hemR - hemL) / scallops
        for (i in 0 until scallops) {
            val sx = hemR - sw * i
            path.quadTo(sx - sw / 2f, hemY + 1.6f, sx - sw, hemY)
        }
        path.close()
        c.drawPath(path, dressPaint)
        // lighter panel down the middle
        path.reset()
        path.moveTo(-0.6f, -11f)
        path.lineTo(0.6f, -11f)
        path.lineTo(1.6f, hemY + 0.6f)
        path.lineTo(-1.6f, hemY + 0.6f)
        path.close()
        c.drawPath(path, dressLightPaint)
        // folds
        c.drawLine(-1.2f, -9.5f, -3.2f, hemY + 0.3f, dressFoldPaint)
        c.drawLine(1.2f, -9.5f, 3.2f, hemY + 0.3f, dressFoldPaint)

        // bodice & sash with a bow
        rect.set(-1.9f, -14.6f, 1.9f, -10.6f)
        c.drawRoundRect(rect, 0.8f, 0.8f, bodicePaint)
        rect.set(-2.1f, -11.6f, 2.1f, -10.6f)
        c.drawRect(rect, sashPaint)
        c.drawCircle(-2.3f, -11.1f, 0.6f, sashPaint)

        // arms: swinging while running, raised happily while jumping
        if (inAir) {
            c.drawLine(-1.6f, -13.8f, -3.4f, -16.8f, limbPaint)
            c.drawLine(1.6f, -13.8f, 3.4f, -16.8f, limbPaint)
        } else {
            c.drawLine(-1.6f, -13.8f, -1.6f - swing * 1.6f, -10.6f, limbPaint)
            c.drawLine(1.6f, -13.8f, 1.6f + swing * 1.6f, -10.6f, limbPaint)
        }
        // puffy sleeves
        c.drawCircle(-1.8f, -13.9f, 0.9f, dressLightPaint)
        c.drawCircle(1.8f, -13.9f, 0.9f, dressLightPaint)

        // head
        c.drawRect(-0.5f, -15.4f, 0.5f, -14.4f, skinPaint)
        c.drawCircle(-0.5f, -17.8f, 2.8f, hairPaint)
        c.drawCircle(0.4f, -17.3f, 2.3f, skinPaint)
        // bangs
        path.reset()
        path.moveTo(-1.6f, -19.4f)
        path.quadTo(0.8f, -20.6f, 2.6f, -18.4f)
        path.quadTo(0.8f, -19.0f, -0.6f, -18.2f)
        path.close()
        c.drawPath(path, hairPaint)
        // hair ribbon
        c.drawCircle(-3f, -18.6f, 0.7f, ribbonPaint)
        c.drawCircle(-3f, -17.4f, 0.7f, ribbonPaint)

        // face (looking to the right, where she's running)
        if (state == State.GAME_OVER) {
            c.drawLine(0.8f, -17.8f, 1.6f, -17f, eyeLinePaint)
            c.drawLine(0.8f, -17f, 1.6f, -17.8f, eyeLinePaint)
        } else {
            c.drawCircle(1.3f, -17.4f, 0.38f, eyePaint)
        }
        c.drawCircle(1.6f, -16.3f, 0.55f, cheekPaint)
        rect.set(1.3f, -16.6f, 2.3f, -15.7f)
        c.drawArc(rect, 20f, 140f, false, eyeLinePaint)

        // golden crown
        path.reset()
        path.moveTo(-1.6f, -19.8f)
        path.lineTo(-1.8f, -21.8f)
        path.lineTo(-0.8f, -20.8f)
        path.lineTo(0f, -22.4f)
        path.lineTo(0.8f, -20.8f)
        path.lineTo(1.8f, -21.8f)
        path.lineTo(1.6f, -19.8f)
        path.close()
        c.drawPath(path, crownPaint)
        c.drawPath(path, crownEdgePaint)
        c.drawCircle(0f, -20.5f, 0.38f, jewelPaint)

        c.restore()
    }

    private fun drawHud(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val u = unit

        drawMuteButton(c)
        if (state != State.READY) {
            c.drawText("$score", w - 4f * u, 9f * u, scorePaint)
            c.drawText("Level $level", 14f * u, 8.5f * u, hudLeftPaint)
        }
        if (highScore > 0) {
            val old = scorePaint.textSize
            scorePaint.textSize = 4f * u
            c.drawText("Best $highScore", w - 4f * u, 15f * u, scorePaint)
            scorePaint.textSize = old
        }

        when (state) {
            State.READY -> {
                c.drawRect(0f, 0f, w, h, overlayPaint)
                c.drawText("Princess Jump", w / 2f, h * 0.36f, titlePaint)
                c.drawText("Tap to jump  •  Tap again in the air to double jump", w / 2f, h * 0.5f, textPaint)
                c.drawText("Catch the stars and travel through ${THEMES.size} magical lands!", w / 2f, h * 0.58f, textPaint)
                if ((time * 2f).toInt() % 2 == 0) {
                    c.drawText("Tap to start", w / 2f, h * 0.7f, titlePaint.withSize(7f * u))
                }
            }
            State.GAME_OVER -> {
                c.drawRect(0f, 0f, w, h, overlayPaint)
                val left = w * 0.3f
                val right = w * 0.7f
                c.drawText("Oh no!", left, h * 0.3f, titlePaint)
                c.drawText("Score: $score", left, h * 0.44f, textPaint)
                c.drawText("Level $level: ${themeFor(level).name}", left, h * 0.52f, textPaint)
                if (newBest) {
                    c.drawText("New best score!", left, h * 0.63f, titlePaint.withSize(6f * u))
                }

                c.drawText("Top Scores", right, h * 0.24f, titlePaint.withSize(6.5f * u))
                topScores.forEachIndexed { i, e ->
                    val line = "${i + 1}.  ${e.score}" + if (e.level > 0) "   (Level ${e.level})" else ""
                    val y = h * (0.35f + i * 0.08f)
                    if (e === newEntry) {
                        c.drawText(line, right, y, titlePaint.withSize(5f * u))
                    } else {
                        c.drawText(line, right, y, textPaint)
                    }
                }

                if (deadTimer > 0.7f) {
                    c.drawText("Tap to try again", w / 2f, h * 0.88f, textPaint)
                }
            }
            State.PLAYING -> if (paused) {
                c.drawRect(0f, 0f, w, h, overlayPaint)
                c.drawText("Paused", w / 2f, h * 0.45f, titlePaint)
                c.drawText("Tap to continue", w / 2f, h * 0.58f, textPaint)
            } else if (levelBanner > 0f) {
                val alpha = (min(1f, levelBanner / 0.5f) * 255).toInt()
                val big = titlePaint.withSize(11f * u)
                big.alpha = alpha
                c.drawText("Level $level", w / 2f, h * 0.3f, big)
                val small = textPaint.withSize(5.5f * u)
                small.alpha = alpha
                c.drawText(themeFor(level).name, w / 2f, h * 0.4f, small)
            }
        }
    }

    /** A music-note button in the top-left corner; a line through it means muted. */
    private fun drawMuteButton(c: Canvas) {
        c.save()
        c.scale(unit, unit)
        c.drawCircle(6.5f, 7f, 4.5f, buttonPaint)
        c.drawOval(3.9f, 8.4f, 5.7f, 9.8f, notePaint)
        c.drawOval(6.9f, 7.6f, 8.7f, 9.0f, notePaint)
        c.drawLine(5.5f, 9f, 5.5f, 4.6f, noteStemPaint)
        c.drawLine(8.5f, 8.2f, 8.5f, 3.8f, noteStemPaint)
        c.drawLine(5.5f, 4.6f, 8.5f, 3.8f, noteBeamPaint)
        if (sound.muted) c.drawLine(3.5f, 4f, 9.5f, 10f, mutePaint)
        c.restore()
    }

    private val sizedPaint = Paint()
    private fun Paint.withSize(size: Float): Paint {
        sizedPaint.set(this)
        sizedPaint.textSize = size
        return sizedPaint
    }
}
