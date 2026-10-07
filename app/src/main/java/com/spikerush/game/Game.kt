package com.spikerush.game

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

const val WORLD_W = 1280f
const val WORLD_H = 720f
const val GROUND_Y = 620f

private const val GRAVITY = 2300f
private const val SPIKE_GRAVITY = 1500f
private const val RUN_SPEED = 430f
private const val JUMP_V = 1090f
private const val BOSS_HIGH_Y = 185f
private const val BOSS_LOW_Y = 515f
private const val ICE_HALF = 24f
private const val DRILL_MAX = 3f
private const val STAR_TIME = 8f

class Platform(val x: Float, val y: Float, val w: Float, val ground: Boolean = false) {
    fun under(px: Float, margin: Float = 0f) = px >= x - margin && px <= x + w + margin
}

class Hero {
    var x = WORLD_W / 2
    var y = GROUND_Y
    var vx = 0f
    var vy = 0f
    var onGround = true
    var facing = 1
    var invuln = 0f
    var knock = 0f
    var anim = 0f
    var coyote = 0f
    var jumpBuffer = 0f
    var dead = false
    var power: Power? = null
    var star = 0f
    var drilling = false
    var drillTime = 0f
    var drillCooldown = 0f
    var shootCooldown = 0f
    val halfW = 17f
    val height = 62f

    fun reset() {
        x = 200f; y = GROUND_Y; vx = 0f; vy = 0f; onGround = true; facing = 1
        invuln = 0f; knock = 0f; dead = false; coyote = 0f; jumpBuffer = 0f
        power = null; star = 0f; drilling = false; drillTime = 0f; drillCooldown = 0f; shootCooldown = 0f
    }
}

enum class SpikeState { FLYING, ROLLING, PLANTED }

class Spike(var x: Float, var y: Float, var vx: Float, var vy: Float, val rolls: Boolean = false) {
    val r = 18f
    var state = SpikeState.FLYING
    var life = 0f
    var maxLife = 0f
    var rollTime = 0f
    var spin = 0f
    var markX = Float.NaN
    var markY = 0f
    var frozen = false
    var dead = false
}

/** Power-ups. FIRE, ICE and DRILL are held until you get hit; STAR is a timed invincibility. */
enum class Power(val label: String) { FIRE("FIRE FLOWER"), ICE("ICE FLOWER"), DRILL("DRILL"), STAR("STAR") }

class Item(var x: Float, var y: Float, val type: Power) {
    var vx = 0f
    var vy = -420f
    var life = 11f
    var t = 0f
    var dead = false
}

class Shot(var x: Float, var y: Float, var vx: Float, var vy: Float, val ice: Boolean) {
    var life = 1.6f
    var spin = 0f
    var dead = false
}

class Coin(val x: Float, val y: Float) {
    var life = 8f
    var t = 0f
    var dead = false
}

class Particle(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    var life: Float, val color: Int, val size: Float, val gravity: Float, val star: Boolean = false,
) {
    val maxLife = life
}

class FloatText(val text: String, var x: Float, var y: Float, var life: Float, val color: Int)

enum class BossState { ENTER, HOVER, CARPET_PREP, CARPET, SWOOP_DOWN, LOW, SWOOP_UP, HURT, DEFEATED }

class Boss {
    var x = WORLD_W / 2
    var y = -160f
    var state = BossState.ENTER
    var timer = 0f
    var hp = 3
    var maxHp = 3
    var targetX = WORLD_W / 2
    var retarget = 0f
    var attackTimer = 2f
    var attacks = 0
    var throwAnim = 0f
    var facing = -1
    var spin = 0f
    var carpetDir = 1
    var carpetDrops = ArrayList<Float>()
    var bob = 0f
    var vy = 0f
    var frozen = 0f
    var burn = 0
    var flash = 0f
}

class Game(private val audio: Synth, private val prefs: SharedPreferences) {

    enum class State { TITLE, PLAYING, CLEAR, GAMEOVER }

    var state = State.TITLE
        private set
    var paused = false
        private set

    // Input (written by the view thread, read here).
    @Volatile var inLeft = false
    @Volatile var inRight = false
    @Volatile var inJump = false
    @Volatile var inAction = false
    @Volatile var tapped = false
    private var prevJump = false
    private var prevAction = false

    private val rnd = Random(System.nanoTime())
    private val hero = Hero()
    private val boss = Boss()
    private val spikes = ArrayList<Spike>()
    private val coins = ArrayList<Coin>()
    private val particles = ArrayList<Particle>()
    private val texts = ArrayList<FloatText>()
    private val items = ArrayList<Item>()
    private val shots = ArrayList<Shot>()
    private var powerTimer = 9f

    val platforms = listOf(
        Platform(0f, GROUND_Y, WORLD_W, ground = true),
        Platform(140f, 455f, 250f),
        Platform(890f, 455f, 250f),
        Platform(530f, 315f, 220f),
    )

    private var score = 0
    private var hiScore = prefs.getInt("hi", 0)
    private var lives = 3
    private var level = 1
    private var stateTimer = 0f
    private var time = 0f
    private var scoreClock = 0f
    private var coinTimer = 5f
    private var shake = 0f
    private var newHi = false

    val showControls get() = state == State.PLAYING || state == State.CLEAR

    /** True when the hero holds a power that uses the action button. */
    val actionAvailable get() = showControls && hero.power != null

    init {
        audio.playSong(Music.main, 0.92f)
    }

    // ------------------------------------------------------------------ lifecycle

    fun onPause() {
        if (state == State.PLAYING) paused = true
    }

    private fun startGame() {
        score = 0; lives = 3; level = 1; newHi = false
        hero.reset()
        spikes.clear(); coins.clear(); particles.clear(); texts.clear(); items.clear(); shots.clear()
        powerTimer = 9f
        setupBoss()
        state = State.PLAYING
        paused = false
        coinTimer = 5f
        audio.playSong(Music.main, tempoForLevel())
    }

    private fun tempoForLevel(lv: Int = level) = min(1.25f, 1f + (lv - 1) * 0.05f)

    private fun setupBoss() {
        boss.x = WORLD_W / 2
        boss.y = -170f
        boss.state = BossState.ENTER
        boss.maxHp = min(6, 3 + (level - 1) / 2)
        boss.hp = boss.maxHp
        boss.attacks = 0
        boss.attackTimer = 1.6f
        boss.spin = 0f
        boss.targetX = WORLD_W / 2
        boss.frozen = 0f
        boss.burn = 0
    }

    private fun nextRound() {
        level++
        spikes.forEach { poof(it.x, it.y) }
        spikes.clear()
        shots.clear()
        setupBoss()
        state = State.PLAYING
    }

    private fun gameOver() {
        state = State.GAMEOVER
        stateTimer = 0f
        hero.dead = true
        hero.vy = -950f
        hero.vx = 0f
        if (score > hiScore) {
            hiScore = score
            newHi = true
            prefs.edit().putInt("hi", hiScore).apply()
        }
        audio.playSong(Music.gameOver)
    }

    // ------------------------------------------------------------------ update

    fun update(dt: Float) {
        time += dt
        if (shake > 0f) shake -= dt
        val tap = tapped
        tapped = false
        val jumpHeld = inJump
        val jumpEdge = jumpHeld && !prevJump
        prevJump = jumpHeld
        val actionHeld = inAction
        val actionEdge = actionHeld && !prevAction
        prevAction = actionHeld

        updateParticles(dt)

        when (state) {
            State.TITLE -> {
                boss.x = WORLD_W / 2 + sin(time * 0.9f) * 330f
                boss.y = 330f + sin(time * 2.1f) * 14f
                boss.facing = if (cos(time * 0.9f) > 0f) 1 else -1
                boss.spin = 0f
                if (tap) startGame()
            }
            State.PLAYING -> {
                if (paused) {
                    if (tap) paused = false
                    return
                }
                updateHero(dt, jumpEdge, jumpHeld, actionEdge)
                updateBoss(dt)
                updateSpikes(dt)
                updateShots(dt)
                updateCoins(dt)
                updateItems(dt)
                checkCollisions()
                scoreClock += dt
                while (scoreClock >= 1f) { scoreClock -= 1f; score += 10 }
            }
            State.CLEAR -> {
                updateHero(dt, jumpEdge, jumpHeld, actionEdge)
                updateBoss(dt)
                updateShots(dt)
                updateCoins(dt)
                stateTimer -= dt
                if (stateTimer <= 0f) nextRound()
            }
            State.GAMEOVER -> {
                stateTimer += dt
                if (stateTimer > 0.6f) {
                    hero.vy += GRAVITY * 0.8f * dt
                    hero.y += hero.vy * dt
                }
                updateBoss(dt)
                updateSpikes(dt)
                if (tap && stateTimer > 1.5f) {
                    state = State.TITLE
                    spikes.clear(); coins.clear(); items.clear(); shots.clear()
                    audio.playSong(Music.main, 0.92f)
                }
            }
        }
    }

    private fun updateHero(dt: Float, jumpEdge: Boolean, jumpHeld: Boolean, actionEdge: Boolean) {
        val h = hero
        if (h.knock > 0f) h.knock -= dt
        if (h.invuln > 0f) h.invuln -= dt
        if (h.star > 0f) {
            h.star -= dt
            if (rnd.nextFloat() < dt * 25f) {
                particles += Particle(h.x + rnd.nextFloat() * 40f - 20f, h.y - rnd.nextFloat() * 60f, 0f, -40f, 0.4f,
                    Color.HSVToColor(floatArrayOf(rnd.nextFloat() * 360f, 0.6f, 1f)), 5f, 0f, star = true)
            }
        }
        if (h.drillCooldown > 0f) h.drillCooldown -= dt
        if (h.shootCooldown > 0f) h.shootCooldown -= dt

        var move = 0
        if (inLeft) move -= 1
        if (inRight) move += 1

        if (h.drilling) {
            updateDrilling(dt, move, jumpEdge || actionEdge)
            return
        }
        if (actionEdge && h.knock <= 0f) usePower()

        if (h.knock <= 0f) {
            val target = move * RUN_SPEED * (if (h.star > 0f) 1.25f else 1f)
            val accel = if (h.onGround) 16f else 9f
            h.vx += (target - h.vx) * min(1f, dt * accel)
            if (move != 0) h.facing = move
        }

        if (jumpEdge) h.jumpBuffer = 0.13f else h.jumpBuffer -= dt
        if (h.onGround) h.coyote = 0.09f else h.coyote -= dt

        if (h.jumpBuffer > 0f && h.coyote > 0f && h.knock <= 0f) {
            h.vy = -JUMP_V
            h.onGround = false
            h.coyote = 0f
            h.jumpBuffer = 0f
            audio.play(Sfx.JUMP)
            dust(h.x, h.y, 5)
        }
        // Short hop when the jump button is released early.
        if (!jumpHeld && h.vy < -420f && h.knock <= 0f) h.vy = -420f

        h.vy = min(h.vy + GRAVITY * dt, 1500f)
        val prevY = h.y
        h.x = (h.x + h.vx * dt).coerceIn(h.halfW, WORLD_W - h.halfW)
        h.y += h.vy * dt

        val wasGround = h.onGround
        h.onGround = false
        if (h.vy >= 0f) {
            for (p in platforms) {
                if (h.x + h.halfW * 0.6f > p.x && h.x - h.halfW * 0.6f < p.x + p.w &&
                    prevY <= p.y + 0.5f && h.y >= p.y
                ) {
                    h.y = p.y
                    h.vy = 0f
                    h.onGround = true
                    if (!wasGround) dust(h.x, h.y, 3)
                    break
                }
            }
        }
        // Frozen spikes are ice blocks you can stand on.
        if (h.vy >= 0f && !h.onGround) {
            for (s in spikes) {
                if (!s.frozen || s.state != SpikeState.PLANTED) continue
                val top = s.y - ICE_HALF
                if (abs(h.x - s.x) < ICE_HALF + h.halfW * 0.6f && prevY <= top + 0.5f && h.y >= top) {
                    h.y = top
                    h.vy = 0f
                    h.onGround = true
                    break
                }
            }
        }
        if (h.onGround) h.anim += dt * abs(h.vx) / 28f
    }

    private fun usePower() {
        val h = hero
        when (h.power) {
            Power.FIRE, Power.ICE -> {
                val ice = h.power == Power.ICE
                if (h.shootCooldown > 0f || shots.size >= 3) return
                h.shootCooldown = 0.28f
                val dir = h.facing.toFloat()
                shots += if (ice) Shot(h.x + dir * 22f, h.y - 36f, dir * 560f, 60f, true)
                else Shot(h.x + dir * 22f, h.y - 36f, dir * 640f, 150f, false)
                audio.play(if (ice) Sfx.ICE_SHOT else Sfx.FIRE_SHOT)
            }
            Power.DRILL -> {
                if (!h.onGround || h.y < GROUND_Y - 0.5f || h.drillCooldown > 0f) return
                h.drilling = true
                h.drillTime = 0f
                h.vy = 0f
                audio.play(Sfx.DRILL)
                dirt(h.x, 10)
            }
            else -> {}
        }
    }

    private fun updateDrilling(dt: Float, move: Int, surfacePressed: Boolean) {
        val h = hero
        h.drillTime += dt
        val target = move * RUN_SPEED * 0.75f
        h.vx += (target - h.vx) * min(1f, dt * 12f)
        if (move != 0) h.facing = move
        h.x = (h.x + h.vx * dt).coerceIn(h.halfW, WORLD_W - h.halfW)
        h.y = GROUND_Y
        h.vy = 0f
        h.onGround = true
        if (rnd.nextFloat() < dt * (if (abs(h.vx) > 40f) 40f else 10f)) dirt(h.x, 1)
        if ((surfacePressed && h.drillTime > 0.25f) || h.drillTime > DRILL_MAX) surface()
    }

    /** Burst out of the ground, smashing whatever is right above. */
    private fun surface() {
        val h = hero
        h.drilling = false
        h.drillCooldown = 0.5f
        h.vy = -980f
        h.onGround = false
        h.invuln = maxOf(h.invuln, 0.45f)
        audio.play(Sfx.STOMP)
        dirt(h.x, 14)
        shake = 0.15f
        for (s in spikes) {
            if (abs(s.x - h.x) < 70f && s.y > GROUND_Y - 80f) smashSpike(s)
        }
        val b = boss
        if ((b.state == BossState.LOW || b.state == BossState.SWOOP_DOWN || b.state == BossState.SWOOP_UP) &&
            abs(b.x - h.x) < 95f && b.y > GROUND_Y - 220f
        ) {
            floatText("DRILL ATTACK!", b.x, b.y - 180f, Color.rgb(255, 200, 120))
            damageBoss(stomp = false)
        }
    }

    private fun updateBoss(dt: Float) {
        val b = boss
        if (b.flash > 0f) b.flash -= dt
        if (b.frozen > 0f) {
            b.frozen -= dt
            if (b.state != BossState.DEFEATED && b.state != BossState.HURT && b.state != BossState.ENTER) return
            b.frozen = 0f
        }
        b.bob += dt
        if (b.throwAnim > 0f) b.throwAnim -= dt
        if (b.state != BossState.DEFEATED && b.state != BossState.CARPET && b.state != BossState.CARPET_PREP) {
            b.facing = if (hero.x < b.x) -1 else 1
        }

        when (b.state) {
            BossState.ENTER -> {
                b.y += (BOSS_HIGH_Y - b.y) * min(1f, dt * 2.5f)
                if (abs(b.y - BOSS_HIGH_Y) < 4f) {
                    b.state = BossState.HOVER
                    b.attackTimer = 1.0f
                }
            }
            BossState.HOVER -> {
                b.retarget -= dt
                if (b.retarget <= 0f) {
                    b.retarget = 1.2f + rnd.nextFloat() * 1.4f
                    b.targetX = if (rnd.nextFloat() < 0.5f) hero.x + rnd.nextFloat() * 300f - 150f
                    else 150f + rnd.nextFloat() * (WORLD_W - 300f)
                    b.targetX = b.targetX.coerceIn(120f, WORLD_W - 120f)
                }
                b.x += (b.targetX - b.x) * min(1f, dt * 1.6f)
                b.y = BOSS_HIGH_Y + sin(b.bob * 2.2f) * 12f
                b.attackTimer -= dt
                if (b.attackTimer <= 0f) {
                    if (b.attacks >= 5) startSwoop() else attack()
                }
            }
            BossState.CARPET_PREP -> {
                val startX = if (b.carpetDir > 0) 90f else WORLD_W - 90f
                b.x += (startX - b.x) * min(1f, dt * 4f)
                b.y += (BOSS_HIGH_Y - 20f - b.y) * min(1f, dt * 4f)
                if (abs(b.x - startX) < 6f) b.state = BossState.CARPET
            }
            BossState.CARPET -> {
                b.facing = b.carpetDir
                b.x += b.carpetDir * (330f + level * 20f) * dt
                b.y = BOSS_HIGH_Y - 20f + sin(b.bob * 8f) * 4f
                val drops = b.carpetDrops.iterator()
                while (drops.hasNext()) {
                    val dx = drops.next()
                    if ((b.carpetDir > 0 && b.x >= dx) || (b.carpetDir < 0 && b.x <= dx)) {
                        drops.remove()
                        val s = Spike(dx, b.y + 50f, 0f, 60f)
                        predictLanding(s)
                        spikes += s
                        b.throwAnim = 0.15f
                        audio.play(Sfx.THROW)
                    }
                }
                if (b.x < 60f || b.x > WORLD_W - 60f) {
                    b.state = BossState.HOVER
                    b.attackTimer = attackInterval() + 0.6f
                }
            }
            BossState.SWOOP_DOWN -> {
                b.x += (b.targetX - b.x) * min(1f, dt * 3f)
                b.y += (BOSS_LOW_Y - b.y) * min(1f, dt * 3.2f)
                if (abs(b.y - BOSS_LOW_Y) < 6f) {
                    b.state = BossState.LOW
                    b.timer = 3.2f
                }
            }
            BossState.LOW -> {
                b.x += sign(hero.x - b.x) * min(abs(hero.x - b.x), 70f * dt)
                b.x = b.x.coerceIn(100f, WORLD_W - 100f)
                b.y = BOSS_LOW_Y + sin(b.bob * 3f) * 6f
                b.timer -= dt
                if (b.timer <= 0f) b.state = BossState.SWOOP_UP
            }
            BossState.SWOOP_UP -> {
                b.y += (BOSS_HIGH_Y - b.y) * min(1f, dt * 2.6f)
                if (abs(b.y - BOSS_HIGH_Y) < 8f) {
                    b.state = BossState.HOVER
                    b.attacks = 0
                    b.attackTimer = 0.8f
                }
            }
            BossState.HURT -> {
                b.timer -= dt
                b.spin += dt * 14f
                b.y += (BOSS_HIGH_Y - b.y) * min(1f, dt * 2.2f)
                if (b.timer <= 0f) {
                    b.spin = 0f
                    b.state = BossState.HOVER
                    b.attacks = 0
                    b.attackTimer = 0.5f
                }
            }
            BossState.DEFEATED -> {
                b.spin += dt * 18f
                b.vy += 900f * dt
                b.y += b.vy * dt
                b.x += 260f * dt * b.facing
            }
        }
    }

    private fun attackInterval() = (2.3f - (level - 1) * 0.22f).coerceAtLeast(0.7f)
    private fun plantLife() = min(10f, 4.4f + level * 0.8f)

    private fun attack() {
        val b = boss
        b.attacks++
        b.attackTimer = attackInterval()
        val options = ArrayList<Int>()
        options += 0; options += 0; options += 1
        if (level >= 2) { options += 2; options += 3 }
        if (level >= 3) { options += 4; options += 1 }
        if (level >= 4) options += 2
        when (options[rnd.nextInt(options.size)]) {
            0 -> { // aimed lob
                val lead = hero.vx * 0.45f
                lob(hero.x + lead + rnd.nextFloat() * 80f - 40f, 1.05f - min(0.3f, level * 0.04f))
            }
            1 -> { // spread of three
                for (o in intArrayOf(-170, 0, 170)) lob(hero.x + o, 1.15f)
            }
            2 -> { // carpet: fly across laying a row of spikes with a safe gap
                b.carpetDir = if (b.x < WORLD_W / 2) 1 else -1
                b.carpetDrops.clear()
                val n = 11
                val gap = rnd.nextInt(n - 1)
                for (i in 0 until n) {
                    if (i == gap || i == gap + 1) continue
                    b.carpetDrops += 110f + i * 106f
                }
                if (b.carpetDir < 0) b.carpetDrops.reverse()
                b.state = BossState.CARPET_PREP
                b.attackTimer = 99f
            }
            3 -> { // roller: lands away from the hero and rolls toward them
                val side = if (hero.x < WORLD_W / 2) 1 else -1
                val tx = (hero.x + side * 380f).coerceIn(60f, WORLD_W - 60f)
                lob(tx, 0.9f, rolls = true, groundOnly = true)
            }
            4 -> { // spike rain
                repeat(4 + level / 2) {
                    val s = Spike(80f + rnd.nextFloat() * (WORLD_W - 160f), -40f - rnd.nextFloat() * 200f, 0f, 80f)
                    predictLanding(s)
                    spikes += s
                }
                audio.play(Sfx.THROW)
            }
        }
        b.throwAnim = 0.3f
    }

    /** Throws a spike ball in an arc so it reaches [tx] after [t] seconds. */
    private fun lob(tx0: Float, t: Float, rolls: Boolean = false, groundOnly: Boolean = false) {
        val b = boss
        val tx = tx0.coerceIn(30f, WORLD_W - 30f)
        val sx = b.x + b.facing * 30f
        val sy = b.y - 100f
        val surfaceY = if (groundOnly) GROUND_Y else supportY(hero.x, hero.y)
        val ty = surfaceY - 18f
        val vx = (tx - sx) / t
        val vy = (ty - sy - 0.5f * SPIKE_GRAVITY * t * t) / t
        val s = Spike(sx, sy, vx, vy, rolls)
        if (rolls) s.rollTime = 3.2f
        predictLanding(s, ignorePlatforms = groundOnly)
        spikes += s
        audio.play(Sfx.THROW)
    }

    private fun supportY(x: Float, y: Float): Float {
        var best = GROUND_Y
        for (p in platforms) if (p.under(x) && p.y >= y - 4f && p.y < best) best = p.y
        return best
    }

    /** Simulates the flight to place a warning marker where the spike will land. */
    private fun predictLanding(s: Spike, ignorePlatforms: Boolean = false) {
        var x = s.x; var y = s.y; var vx = s.vx; var vy = s.vy
        val dt = 1f / 120f
        repeat(600) {
            val py = y
            vy += SPIKE_GRAVITY * dt
            x += vx * dt
            y += vy * dt
            if (x < s.r || x > WORLD_W - s.r) vx = -vx * 0.6f
            for (p in platforms) {
                if (ignorePlatforms && !p.ground) continue
                if (vy > 0f && p.under(x) && py + s.r <= p.y + 0.5f && y + s.r >= p.y) {
                    s.markX = x
                    s.markY = p.y
                    return
                }
            }
        }
    }

    private fun updateSpikes(dt: Float) {
        for (s in spikes) {
            when (s.state) {
                SpikeState.FLYING -> {
                    val py = s.y
                    s.vy += SPIKE_GRAVITY * dt
                    s.x += s.vx * dt
                    s.y += s.vy * dt
                    s.spin += dt * 9f * sign(s.vx + 0.01f)
                    if (s.x < s.r) { s.x = s.r; s.vx = -s.vx * 0.6f }
                    if (s.x > WORLD_W - s.r) { s.x = WORLD_W - s.r; s.vx = -s.vx * 0.6f }
                    for (p in platforms) {
                        if (s.rolls && !p.ground) continue
                        if (s.vy > 0f && p.under(s.x) && py + s.r <= p.y + 0.5f && s.y + s.r >= p.y) {
                            s.y = p.y - s.r + 3f
                            s.vy = 0f
                            s.markX = Float.NaN
                            if (s.rolls) {
                                s.state = SpikeState.ROLLING
                                s.vx = sign(hero.x - s.x) * (270f + level * 18f)
                            } else {
                                plant(s)
                            }
                            audio.play(Sfx.PLANT)
                            dust(s.x, p.y, 4)
                            break
                        }
                    }
                    if (s.y > WORLD_H + 80f) s.dead = true
                }
                SpikeState.ROLLING -> {
                    if (s.frozen) { plant(s); continue }
                    s.x += s.vx * dt
                    s.spin += s.vx * dt / s.r
                    if (s.x < s.r || s.x > WORLD_W - s.r) {
                        s.x = s.x.coerceIn(s.r, WORLD_W - s.r)
                        s.vx = -s.vx
                    }
                    s.rollTime -= dt
                    if (s.rollTime <= 0f) plant(s)
                }
                SpikeState.PLANTED -> {
                    s.life -= dt
                    if (s.life <= 0f) {
                        s.dead = true
                        if (s.frozen) shatter(s.x, s.y) else poof(s.x, s.y)
                        if (state == State.PLAYING) score += 5
                    }
                }
            }
        }
        spikes.removeAll { it.dead }
    }

    private fun plant(s: Spike) {
        s.state = SpikeState.PLANTED
        s.vx = 0f
        s.maxLife = if (s.frozen) 6f else plantLife()
        s.life = s.maxLife
    }

    private fun freeze(s: Spike) {
        if (s.frozen) return
        s.frozen = true
        s.markX = Float.NaN
        if (s.state == SpikeState.PLANTED) { s.maxLife = 6f; s.life = 6f }
        if (s.state == SpikeState.FLYING) { s.vx *= 0.3f; s.vy = maxOf(s.vy, 0f) }
        if (s.state == SpikeState.ROLLING) plant(s)
        score += 10
        audio.play(Sfx.FREEZE)
    }

    private fun smashSpike(s: Spike) {
        if (s.dead) return
        s.dead = true
        if (s.frozen) shatter(s.x, s.y) else poof(s.x, s.y)
        score += 20
        floatText("+20", s.x, s.y - 30f, Color.WHITE)
    }

    private fun updateShots(dt: Float) {
        val b = boss
        for (sh in shots) {
            sh.life -= dt
            sh.spin += dt * 18f
            val py = sh.y
            sh.vy += (if (sh.ice) 900f else 1900f) * dt
            sh.x += sh.vx * dt
            sh.y += sh.vy * dt
            if (sh.life <= 0f || sh.x < -20f || sh.x > WORLD_W + 20f || sh.y > WORLD_H) {
                sh.dead = true
                if (sh.ice) shatter(sh.x.coerceIn(0f, WORLD_W), sh.y, 4)
            }
            if (!sh.ice && rnd.nextFloat() < dt * 40f) {
                particles += Particle(sh.x, sh.y, 0f, -30f, 0.25f, Color.rgb(255, 160, 40), 5f, 0f)
            }
            for (p in platforms) {
                if (sh.vy > 0f && p.under(sh.x) && py + 9f <= p.y + 0.5f && sh.y + 9f >= p.y) {
                    sh.y = p.y - 9f
                    // Fireballs bounce low enough to hit spikes on the floor; ice balls skate along it.
                    sh.vy = if (sh.ice) 0f else -330f
                }
            }
            if (sh.dead) continue
            for (s in spikes) {
                if (s.dead) continue
                val dx = s.x - sh.x
                val dy = s.y - sh.y
                if (dx * dx + dy * dy < (s.r + 16f) * (s.r + 16f)) {
                    if (sh.ice) { if (s.frozen) continue; freeze(s) } else smashSpike(s)
                    sh.dead = true
                    break
                }
            }
            if (sh.dead) continue
            if (b.state == BossState.HURT || b.state == BossState.DEFEATED || b.state == BossState.ENTER) continue
            val hit = (abs(sh.x - b.x) < 74f && sh.y > b.y - 40f && sh.y < b.y + 50f) ||
                (abs(sh.x - b.x) < 36f && sh.y > b.y - 140f && sh.y <= b.y - 40f)
            if (hit) {
                sh.dead = true
                if (sh.ice) {
                    b.frozen = 2.6f
                    audio.play(Sfx.FREEZE)
                    floatText("FROZEN!", b.x, b.y - 225f, Color.rgb(170, 230, 255))
                } else {
                    b.burn++
                    b.flash = 0.18f
                    audio.play(Sfx.PLANT)
                    if (b.burn >= 4) {
                        b.burn = 0
                        floatText("BURNED!", b.x, b.y - 175f, Color.rgb(255, 160, 60))
                        damageBoss(stomp = false)
                    }
                }
            }
        }
        shots.removeAll { it.dead }
    }

    private fun updateItems(dt: Float) {
        powerTimer -= dt
        if (powerTimer <= 0f && items.isEmpty()) {
            powerTimer = 13f + rnd.nextFloat() * 8f
            val roll = rnd.nextFloat()
            val type = when {
                roll < 0.28f -> Power.FIRE
                roll < 0.56f -> Power.ICE
                roll < 0.84f -> Power.DRILL
                else -> Power.STAR
            }
            val p = platforms[rnd.nextInt(platforms.size)]
            val it = Item(p.x + 40f + rnd.nextFloat() * (p.w - 80f), p.y - 20f, type)
            if (type == Power.STAR) it.vx = if (rnd.nextBoolean()) 170f else -170f
            items += it
            sparkle(it.x, it.y)
            audio.play(Sfx.ITEM_APPEAR)
        }
        val h = hero
        for (it in items) {
            it.t += dt
            it.life -= dt
            if (it.life <= 0f) it.dead = true
            val py = it.y
            it.vy = min(it.vy + 1400f * dt, 900f)
            it.x += it.vx * dt
            it.y += it.vy * dt
            if (it.x < 20f || it.x > WORLD_W - 20f) { it.x = it.x.coerceIn(20f, WORLD_W - 20f); it.vx = -it.vx }
            for (p in platforms) {
                if (it.vy > 0f && p.under(it.x) && py <= p.y + 0.5f && it.y >= p.y) {
                    it.y = p.y
                    it.vy = if (it.type == Power.STAR) -720f else 0f
                }
            }
            if (it.y > WORLD_H + 40f) it.dead = true
            if (!it.dead && abs(it.x - h.x) < 36f && it.y > h.y - h.height - 10f && it.y - 40f < h.y) {
                it.dead = true
                collect(it.type)
            }
        }
        items.removeAll { it.dead }
    }

    private fun collect(type: Power) {
        val h = hero
        score += 200
        sparkle(h.x, h.y - 40f)
        floatText(type.label + "!", h.x, h.y - 90f, Color.rgb(255, 240, 120))
        if (type == Power.STAR) {
            h.star = STAR_TIME
            audio.playSong(Music.star, 1f, then = Music.main, thenTempo = tempoForLevel())
        } else {
            if (h.drilling && type != Power.DRILL) surface()
            h.power = type
            audio.play(Sfx.POWER_UP)
        }
    }

    private fun updateCoins(dt: Float) {
        coinTimer -= dt
        if (coinTimer <= 0f && state == State.PLAYING) {
            coinTimer = 6f + rnd.nextFloat() * 4f
            val p = platforms[rnd.nextInt(platforms.size)]
            val cx = p.x + 30f + rnd.nextFloat() * (p.w - 60f)
            coins += Coin(cx, p.y - 46f)
        }
        for (c in coins) {
            c.t += dt
            c.life -= dt
            if (c.life <= 0f) c.dead = true
            if (!c.dead && abs(c.x - hero.x) < 34f && c.y > hero.y - hero.height - 20f && c.y < hero.y + 10f) {
                c.dead = true
                score += 100
                audio.play(Sfx.COIN)
                floatText("+100", c.x, c.y - 20f, Color.rgb(255, 220, 60))
                sparkle(c.x, c.y)
            }
        }
        coins.removeAll { it.dead }
    }

    private fun checkCollisions() {
        val h = hero
        val left = h.x - h.halfW * 0.8f
        val right = h.x + h.halfW * 0.8f
        val top = h.y - h.height + 8f
        val bottom = h.y
        if (h.drilling) return
        val starred = h.star > 0f

        if (h.invuln <= 0f || starred) {
            for (s in spikes) {
                if (s.frozen || s.dead) continue
                val rr = s.r * 0.72f
                val cx = s.x.coerceIn(left, right)
                val cy = s.y.coerceIn(top, bottom)
                val dx = s.x - cx
                val dy = s.y - cy
                if (dx * dx + dy * dy < rr * rr) {
                    if (starred) { smashSpike(s); continue }
                    hurtHero(s.x)
                    break
                }
            }
        }

        val b = boss
        if (b.state == BossState.HURT || b.state == BossState.DEFEATED || b.state == BossState.ENTER) return
        val headTop = b.y - 130f
        if (h.vy > 0f && abs(h.x - b.x) < 58f && h.y >= headTop - 10f && h.y <= headTop + 30f) {
            h.vy = -880f
            h.y = b.y - 132f
            audio.play(Sfx.STOMP)
            damageBoss(stomp = true)
            return
        }
        if (h.invuln > 0f && !starred) return
        val hitPod = right > b.x - 66f && left < b.x + 66f && bottom > b.y - 38f && top < b.y + 56f
        val hitBody = right > b.x - 30f && left < b.x + 30f && bottom > b.y - 118f && top < b.y - 38f
        if (hitPod || hitBody) {
            if (starred) {
                h.vx = (if (h.x >= b.x) 1f else -1f) * 420f
                h.vy = -500f
                floatText("STAR POWER!", b.x, b.y - 175f, Color.rgb(255, 240, 120))
                damageBoss(stomp = false)
            } else {
                hurtHero(b.x)
            }
        }
    }

    private fun hurtHero(fromX: Float) {
        val h = hero
        if (h.invuln > 0f || h.star > 0f || h.drilling) return
        if (h.power != null) {
            // Like the classics: a hit costs you the power-up, not a life.
            h.power = null
            h.invuln = 2f
            h.knock = 0.3f
            h.vx = (if (h.x >= fromX) 1f else -1f) * 320f
            h.vy = -480f
            h.onGround = false
            shake = 0.25f
            audio.play(Sfx.POWER_DOWN)
            floatText("POWER LOST", h.x, h.y - 90f, Color.rgb(255, 150, 150))
            return
        }
        lives--
        h.invuln = 2f
        h.knock = 0.35f
        val dir = if (h.x >= fromX) 1f else -1f
        h.vx = dir * 380f
        h.vy = -560f
        h.onGround = false
        shake = 0.35f
        audio.play(Sfx.HURT)
        repeat(10) {
            particles += Particle(h.x, h.y - 30f, rnd.nextFloat() * 400f - 200f, -rnd.nextFloat() * 300f,
                0.5f, Color.rgb(255, 80, 60), 5f, 600f)
        }
        if (lives <= 0) gameOver()
    }

    private fun damageBoss(stomp: Boolean) {
        val b = boss
        val h = hero
        if (b.state == BossState.HURT || b.state == BossState.DEFEATED) return
        b.hp--
        b.frozen = 0f
        b.burn = 0
        score += if (stomp) 500 else 300
        shake = 0.25f
        audio.play(Sfx.BOSS_HIT)
        floatText(if (stomp) "+500" else "+300", b.x, b.y - 150f, Color.WHITE)
        repeat(8) {
            val a = it / 8f * 6.283f
            particles += Particle(b.x, b.y - 120f, cos(a) * 260f, sin(a) * 260f, 0.6f,
                Color.rgb(255, 235, 80), 9f, 0f, star = true)
        }
        if (b.hp <= 0) {
            b.state = BossState.DEFEATED
            b.vy = -700f
            val bonus = 2000 * level
            score += bonus
            floatText("ROUND BONUS +$bonus", WORLD_W / 2, 260f, Color.rgb(255, 220, 60))
            spikes.forEach { poof(it.x, it.y) }
            spikes.clear()
            state = State.CLEAR
            stateTimer = 4.2f
            if (lives < 5) {
                lives++
                audio.play(Sfx.LIFE)
                floatText("1UP!", h.x, h.y - 90f, Color.rgb(120, 255, 120))
            }
            audio.playSong(Music.clear, 1f, then = Music.main, thenTempo = tempoForLevel(level + 1))
        } else {
            b.state = BossState.HURT
            b.timer = 0.9f
        }
    }

    private fun startSwoop() {
        val b = boss
        b.state = BossState.SWOOP_DOWN
        b.targetX = hero.x.coerceIn(140f, WORLD_W - 140f)
    }

    // ------------------------------------------------------------------ particles

    private fun dust(x: Float, y: Float, n: Int) {
        repeat(n) {
            particles += Particle(x, y - 4f, rnd.nextFloat() * 160f - 80f, -rnd.nextFloat() * 90f,
                0.35f, Color.argb(200, 230, 210, 190), 4f + rnd.nextFloat() * 3f, 200f)
        }
    }

    private fun poof(x: Float, y: Float) {
        audio.play(Sfx.POOF)
        repeat(6) {
            particles += Particle(x, y, rnd.nextFloat() * 120f - 60f, -rnd.nextFloat() * 120f,
                0.45f, Color.argb(220, 200, 200, 210), 6f + rnd.nextFloat() * 4f, -60f)
        }
    }

    private fun dirt(x: Float, n: Int) {
        repeat(n) {
            particles += Particle(x + rnd.nextFloat() * 30f - 15f, GROUND_Y - 2f, rnd.nextFloat() * 300f - 150f,
                -150f - rnd.nextFloat() * 350f, 0.5f, Color.rgb(130, 75, 45), 4f + rnd.nextFloat() * 4f, 1400f)
        }
    }

    private fun shatter(x: Float, y: Float, n: Int = 8) {
        audio.play(Sfx.SHATTER)
        repeat(n) {
            particles += Particle(x, y, rnd.nextFloat() * 300f - 150f, -rnd.nextFloat() * 300f, 0.5f,
                Color.argb(230, 190, 235, 255), 4f + rnd.nextFloat() * 3f, 1200f)
        }
    }

    private fun sparkle(x: Float, y: Float) {
        repeat(6) {
            val a = it / 6f * 6.283f
            particles += Particle(x, y, cos(a) * 150f, sin(a) * 150f, 0.4f,
                Color.rgb(255, 240, 120), 6f, 0f, star = true)
        }
    }

    private fun floatText(t: String, x: Float, y: Float, color: Int) {
        texts += FloatText(t, x, y, 1.2f, color)
    }

    private fun updateParticles(dt: Float) {
        for (p in particles) {
            p.vy += p.gravity * dt
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.life -= dt
        }
        particles.removeAll { it.life <= 0f }
        for (t in texts) {
            t.y -= 50f * dt
            t.life -= dt
        }
        texts.removeAll { it.life <= 0f }
    }

    // ================================================================== rendering

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }
    private val textStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
        style = Paint.Style.STROKE
        strokeWidth = 7f
        strokeJoin = Paint.Join.ROUND
        color = Color.rgb(30, 10, 40)
    }
    private val rect = RectF()
    private val path = Path()
    private val background: Bitmap by lazy { buildBackground() }

    fun render(c: Canvas) {
        c.save()
        if (shake > 0f) c.translate(rnd.nextFloat() * 12f - 6f, rnd.nextFloat() * 12f - 6f)
        c.drawBitmap(background, 0f, 0f, null)

        for (s in spikes) if (!s.markX.isNaN()) drawMarker(c, s.markX, s.markY)
        for (co in coins) drawCoin(c, co)
        for (it in items) drawItem(c, it)
        for (s in spikes) if (s.state == SpikeState.PLANTED && !s.dead) drawSpike(c, s)

        if (state != State.TITLE) {
            if (hero.drilling) drawDrillMound(c, hero)
            else if (hero.star > 0f || !(hero.invuln > 0f && !hero.dead && (time * 14f).toInt() % 2 == 0)) drawHero(c, hero)
        }
        drawBoss(c, boss)
        for (s in spikes) if (s.state != SpikeState.PLANTED && !s.dead) drawSpike(c, s)
        for (sh in shots) drawShot(c, sh)

        for (p in particles) {
            val a = (p.life / p.maxLife).coerceIn(0f, 1f)
            paint.color = p.color
            paint.alpha = (Color.alpha(p.color) * a).toInt()
            if (p.star) drawStar(c, p.x, p.y, p.size * (0.5f + a * 0.5f), p.size * 0.45f)
            else c.drawCircle(p.x, p.y, p.size * (0.4f + a * 0.6f), paint)
        }
        paint.alpha = 255
        for (t in texts) drawText(c, t.text, t.x, t.y, 30f, t.color, (t.life / 1.2f).coerceIn(0f, 1f))
        c.restore()

        when (state) {
            State.TITLE -> drawTitle(c)
            State.PLAYING -> {
                drawHud(c)
                if (paused) {
                    paint.color = Color.argb(150, 0, 0, 0)
                    c.drawRect(0f, 0f, WORLD_W, WORLD_H, paint)
                    drawText(c, "PAUSED", WORLD_W / 2, 330f, 80f, Color.WHITE)
                    drawText(c, "Tap to continue", WORLD_W / 2, 400f, 34f, Color.rgb(255, 220, 120))
                }
            }
            State.CLEAR -> {
                drawHud(c)
                drawText(c, "ROUND $level CLEAR!", WORLD_W / 2, 220f, 72f, Color.rgb(255, 220, 60))
            }
            State.GAMEOVER -> {
                drawHud(c)
                paint.color = Color.argb((min(1f, stateTimer) * 140).toInt(), 0, 0, 0)
                c.drawRect(0f, 0f, WORLD_W, WORLD_H, paint)
                drawText(c, "GAME OVER", WORLD_W / 2, 300f, 90f, Color.rgb(255, 80, 70))
                drawText(c, "Score  $score", WORLD_W / 2, 375f, 40f, Color.WHITE)
                if (newHi) drawText(c, "NEW HIGH SCORE!", WORLD_W / 2, 425f, 34f, Color.rgb(255, 220, 60))
                if (stateTimer > 1.5f && (time * 2f).toInt() % 2 == 0) {
                    drawText(c, "Tap to continue", WORLD_W / 2, 490f, 32f, Color.rgb(220, 220, 255))
                }
            }
        }
    }

    private fun drawText(c: Canvas, s: String, x: Float, y: Float, size: Float, color: Int, alpha: Float = 1f) {
        text.textSize = size
        textStroke.textSize = size
        textStroke.strokeWidth = size * 0.16f
        textStroke.alpha = (255 * alpha).toInt()
        text.color = color
        text.alpha = (255 * alpha).toInt()
        c.drawText(s, x, y, textStroke)
        c.drawText(s, x, y, text)
    }

    private fun drawTitle(c: Canvas) {
        val wob = sin(time * 3f) * 4f
        drawText(c, "SPIKE RUSH", WORLD_W / 2, 120f + wob, 96f, Color.rgb(255, 210, 50))
        drawText(c, "JR.", WORLD_W / 2 + 280f, 165f + wob, 54f, Color.rgb(255, 110, 70))
        drawText(c, "Dodge the spikes. Stomp the brat when he swoops low!", WORLD_W / 2, 440f, 30f, Color.WHITE)
        var ix = WORLD_W / 2 - 150f
        for (p in Power.values()) {
            drawPowerIcon(c, p, ix, 482f, 0.8f, time)
            ix += 100f
        }
        if ((time * 2f).toInt() % 2 == 0) drawText(c, "TAP TO START", WORLD_W / 2, 560f, 48f, Color.rgb(140, 255, 140))
        drawText(c, "HIGH SCORE  $hiScore", WORLD_W / 2, 604f, 30f, Color.rgb(255, 220, 120))
    }

    private fun drawHud(c: Canvas) {
        text.textAlign = Paint.Align.LEFT
        textStroke.textAlign = Paint.Align.LEFT
        drawText(c, "SCORE $score", 24f, 48f, 34f, Color.WHITE)
        drawText(c, "HI $hiScore", 24f, 84f, 24f, Color.rgb(255, 220, 120))
        text.textAlign = Paint.Align.CENTER
        textStroke.textAlign = Paint.Align.CENTER
        drawText(c, "ROUND $level", WORLD_W / 2, 44f, 30f, Color.rgb(200, 220, 255))

        for (i in 0 until lives) drawHeart(c, 330f + i * 40f, 36f, 15f)

        // Current power-up (and star timer) under the hearts.
        var px = 340f
        hero.power?.let {
            drawPowerIcon(c, it, px, 84f, 0.7f, time)
            px += 44f
        }
        if (hero.star > 0f) {
            drawPowerIcon(c, Power.STAR, px, 84f, 0.7f, time)
            paint.color = Color.argb(160, 0, 0, 0)
            c.drawRect(px + 22f, 78f, px + 102f, 90f, paint)
            paint.color = Color.rgb(255, 230, 80)
            c.drawRect(px + 23f, 79f, px + 23f + 78f * (hero.star / STAR_TIME), 89f, paint)
        }
        if (boss.burn > 0) {
            text.textAlign = Paint.Align.RIGHT
            textStroke.textAlign = Paint.Align.RIGHT
            drawText(c, "BURN " + "|".repeat(boss.burn) + ".".repeat(4 - boss.burn), WORLD_W - 30f, 84f, 22f,
                Color.rgb(255, 160, 60))
            text.textAlign = Paint.Align.CENTER
            textStroke.textAlign = Paint.Align.CENTER
        }

        // Boss health pips.
        text.textAlign = Paint.Align.RIGHT
        textStroke.textAlign = Paint.Align.RIGHT
        drawText(c, "BOSS", WORLD_W - 30f - boss.maxHp * 34f, 48f, 26f, Color.rgb(255, 160, 120))
        text.textAlign = Paint.Align.CENTER
        textStroke.textAlign = Paint.Align.CENTER
        for (i in 0 until boss.maxHp) {
            val x = WORLD_W - 30f - (boss.maxHp - i) * 34f + 17f
            paint.color = Color.rgb(30, 10, 30)
            c.drawCircle(x, 39f, 13f, paint)
            paint.color = if (i < boss.hp) Color.rgb(240, 70, 60) else Color.rgb(80, 60, 70)
            c.drawCircle(x, 39f, 10f, paint)
        }
    }

    private fun drawHeart(c: Canvas, x: Float, y: Float, s: Float) {
        path.reset()
        path.moveTo(x, y + s)
        path.cubicTo(x - s * 1.6f, y - s * 0.1f, x - s * 0.8f, y - s * 1.3f, x, y - s * 0.4f)
        path.cubicTo(x + s * 0.8f, y - s * 1.3f, x + s * 1.6f, y - s * 0.1f, x, y + s)
        stroke.color = Color.rgb(40, 10, 20)
        stroke.strokeWidth = 4f
        c.drawPath(path, stroke)
        paint.color = Color.rgb(240, 50, 70)
        c.drawPath(path, paint)
    }

    private fun drawStar(c: Canvas, x: Float, y: Float, r1: Float, r2: Float) {
        path.reset()
        for (i in 0 until 10) {
            val a = -1.5708f + i * 0.6283f
            val r = if (i % 2 == 0) r1 else r2
            val px = x + cos(a) * r
            val py = y + sin(a) * r
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        path.close()
        c.drawPath(path, paint)
    }

    private fun drawMarker(c: Canvas, x: Float, y: Float) {
        val pulse = 0.5f + 0.5f * sin(time * 18f)
        paint.color = Color.argb((90 + 110 * pulse).toInt(), 255, 40, 40)
        rect.set(x - 24f, y - 7f, x + 24f, y + 5f)
        c.drawOval(rect, paint)
        drawText(c, "!", x, y - 14f, 30f, Color.rgb(255, 90, 80), 0.6f + 0.4f * pulse)
    }

    private fun drawCoin(c: Canvas, co: Coin) {
        if (co.life < 2f && (co.t * 10f).toInt() % 2 == 0) return
        val w = abs(cos(co.t * 4f)) * 15f + 3f
        val y = co.y + sin(co.t * 3f) * 4f
        rect.set(co.x - w, y - 20f, co.x + w, y + 20f)
        paint.color = Color.rgb(180, 110, 10)
        c.drawOval(rect, paint)
        rect.inset(3f, 3f)
        paint.color = Color.rgb(255, 210, 40)
        c.drawOval(rect, paint)
        paint.color = Color.rgb(255, 245, 170)
        rect.set(co.x - w * 0.3f, y - 12f, co.x + w * 0.1f, y + 8f)
        c.drawOval(rect, paint)
    }

    private fun drawSpike(c: Canvas, s: Spike) {
        if (s.state == SpikeState.PLANTED && s.life < 1.5f && (s.life * 10f).toInt() % 2 == 0) return
        c.save()
        c.translate(s.x, s.y)
        c.rotate(s.spin * 57.3f)
        val r = s.r
        path.reset()
        for (i in 0 until 8) {
            val a = i * 0.785f
            val a1 = a - 0.28f
            val a2 = a + 0.28f
            path.moveTo(cos(a1) * r * 0.8f, sin(a1) * r * 0.8f)
            path.lineTo(cos(a) * r * 1.55f, sin(a) * r * 1.55f)
            path.lineTo(cos(a2) * r * 0.8f, sin(a2) * r * 0.8f)
            path.close()
        }
        stroke.color = Color.rgb(20, 20, 30)
        stroke.strokeWidth = 3f
        c.drawPath(path, stroke)
        paint.color = Color.rgb(225, 228, 240)
        c.drawPath(path, paint)
        paint.color = Color.rgb(20, 20, 30)
        c.drawCircle(0f, 0f, r + 1.5f, paint)
        paint.color = Color.rgb(70, 70, 85)
        c.drawCircle(0f, 0f, r - 1f, paint)
        paint.color = Color.rgb(150, 150, 170)
        c.drawCircle(-r * 0.35f, -r * 0.35f, r * 0.28f, paint)
        c.restore()
        if (s.frozen) {
            rect.set(s.x - ICE_HALF, s.y - ICE_HALF, s.x + ICE_HALF, s.y + ICE_HALF)
            paint.color = Color.argb(170, 150, 215, 255)
            c.drawRoundRect(rect, 6f, 6f, paint)
            stroke.color = Color.rgb(230, 250, 255)
            stroke.strokeWidth = 3f
            c.drawRoundRect(rect, 6f, 6f, stroke)
            c.drawLine(s.x - 14f, s.y - 16f, s.x - 4f, s.y - 16f, stroke)
            c.drawLine(s.x - 16f, s.y - 14f, s.x - 16f, s.y - 6f, stroke)
        }
    }

    private fun drawShot(c: Canvas, sh: Shot) {
        c.save()
        c.translate(sh.x, sh.y)
        c.rotate(sh.spin * 57.3f)
        if (sh.ice) {
            paint.color = Color.rgb(120, 200, 255)
            path.reset()
            path.moveTo(0f, -13f); path.lineTo(10f, 0f); path.lineTo(0f, 13f); path.lineTo(-10f, 0f); path.close()
            c.drawPath(path, paint)
            paint.color = Color.WHITE
            c.drawCircle(0f, 0f, 4f, paint)
        } else {
            paint.color = Color.rgb(230, 70, 20)
            c.drawCircle(0f, 0f, 11f, paint)
            paint.color = Color.rgb(255, 170, 40)
            c.drawCircle(2f, -2f, 7f, paint)
            paint.color = Color.rgb(255, 245, 160)
            c.drawCircle(3f, -3f, 3.5f, paint)
        }
        c.restore()
    }

    private fun drawItem(c: Canvas, it: Item) {
        if (it.life < 2.5f && (it.t * 10f).toInt() % 2 == 0) return
        val bob = if (it.type == Power.STAR) 0f else sin(it.t * 4f) * 3f
        drawPowerIcon(c, it.type, it.x, it.y - 22f + bob, 1f, it.t)
    }

    /** Draws a power-up icon centered at (x, y). */
    private fun drawPowerIcon(c: Canvas, type: Power, x: Float, y: Float, scale: Float, t: Float) {
        c.save()
        c.translate(x, y)
        c.scale(scale, scale)
        // soft glow so items stand out against the dusk
        paint.color = Color.argb(70, 255, 255, 200)
        c.drawCircle(0f, 0f, 30f, paint)
        stroke.color = Color.rgb(25, 15, 20)
        stroke.strokeWidth = 3f
        when (type) {
            Power.FIRE, Power.ICE -> {
                val petal = if (type == Power.FIRE) Color.rgb(255, 110, 30) else Color.rgb(110, 190, 255)
                val inner = if (type == Power.FIRE) Color.rgb(255, 225, 60) else Color.rgb(235, 250, 255)
                paint.color = Color.rgb(40, 160, 60)
                c.drawRect(-2.5f, 4f, 2.5f, 22f, paint)
                rect.set(-16f, 10f, -2f, 18f); c.drawOval(rect, paint)
                rect.set(2f, 10f, 16f, 18f); c.drawOval(rect, paint)
                rect.set(-20f, -14f, 20f, 10f)
                c.drawOval(rect, stroke)
                paint.color = petal
                c.drawOval(rect, paint)
                rect.set(-13f, -9f, 13f, 5f)
                paint.color = inner
                c.drawOval(rect, paint)
                paint.color = Color.rgb(25, 15, 20)
                c.drawRect(-5f, -6f, -2.5f, 1f, paint)
                c.drawRect(2.5f, -6f, 5f, 1f, paint)
            }
            Power.STAR -> {
                c.rotate(sin(t * 6f) * 12f)
                paint.color = Color.rgb(25, 15, 20)
                drawStar(c, 0f, 0f, 23f, 11f)
                paint.color = Color.HSVToColor(floatArrayOf(45f + sin(t * 8f) * 10f, 0.8f, 1f))
                drawStar(c, 0f, 0f, 19f, 9f)
                paint.color = Color.rgb(25, 15, 20)
                c.drawRect(-5f, -5f, -2.5f, 2f, paint)
                c.drawRect(2.5f, -5f, 5f, 2f, paint)
            }
            Power.DRILL -> {
                c.rotate(sin(t * 3f) * 8f)
                path.reset()
                path.moveTo(-15f, 6f); path.lineTo(15f, 6f); path.lineTo(0f, -24f); path.close()
                c.drawPath(path, stroke)
                paint.color = Color.rgb(190, 195, 210)
                c.drawPath(path, paint)
                stroke.strokeWidth = 2.5f
                val off = (t * 30f) % 8f
                for (k in 0 until 3) {
                    val yy = -16f + k * 8f + off * 0.5f
                    val hw = (yy + 24f) / 30f * 15f
                    c.drawLine(-hw, yy + 3f, hw, yy - 1f, stroke)
                }
                rect.set(-18f, 5f, 18f, 15f)
                paint.color = Color.rgb(220, 50, 50)
                c.drawRoundRect(rect, 4f, 4f, paint)
            }
        }
        c.restore()
    }

    private fun drawDrillMound(c: Canvas, h: Hero) {
        val wob = sin(time * 30f) * 2f
        rect.set(h.x - 34f, GROUND_Y - 22f + wob, h.x + 34f, GROUND_Y + 12f)
        paint.color = Color.rgb(90, 50, 30)
        c.drawArc(rect, 180f, 180f, true, paint)
        rect.inset(6f, 6f)
        paint.color = Color.rgb(140, 85, 50)
        c.drawArc(rect, 180f, 180f, true, paint)
        // spinning drill tip poking out
        val tip = h.x + h.facing * 6f
        path.reset()
        path.moveTo(tip - 10f, GROUND_Y - 16f)
        path.lineTo(tip + 10f, GROUND_Y - 16f)
        path.lineTo(tip, GROUND_Y - 44f + wob)
        path.close()
        paint.color = Color.rgb(200, 205, 220)
        c.drawPath(path, paint)
        stroke.color = Color.rgb(25, 15, 20)
        stroke.strokeWidth = 2.5f
        c.drawPath(path, stroke)
        val spiral = (time * 40f) % 7f
        c.drawLine(tip - 6f, GROUND_Y - 22f - spiral, tip + 6f, GROUND_Y - 26f - spiral, stroke)
        // remaining dig time
        val left = 1f - h.drillTime / DRILL_MAX
        paint.color = Color.argb(160, 0, 0, 0)
        c.drawRect(h.x - 26f, GROUND_Y + 18f, h.x + 26f, GROUND_Y + 26f, paint)
        paint.color = Color.rgb(255, 210, 80)
        c.drawRect(h.x - 25f, GROUND_Y + 19f, h.x - 25f + 50f * left, GROUND_Y + 25f, paint)
    }

    // ---- the plumber hero (drawn facing right; mirrored for left) ----
    private fun drawHero(c: Canvas, h: Hero) {
        c.save()
        c.translate(h.x, h.y)
        if (h.dead) c.scale(1f, -1f, 0f, -32f)
        c.scale(h.facing.toFloat(), 1f)
        val airborne = !h.onGround
        val swing = if (!airborne && abs(h.vx) > 30f) sin(h.anim) * 7f else 0f

        val skin = Color.rgb(255, 200, 150)
        val starring = h.star > 0f
        if (starring) {
            paint.color = Color.argb(90, 255, 250, 160)
            c.drawCircle(0f, -34f, 46f + sin(time * 20f) * 4f, paint)
        }
        val red = when {
            starring -> Color.HSVToColor(floatArrayOf((time * 600f) % 360f, 0.75f, 1f))
            h.power == Power.FIRE -> Color.rgb(250, 248, 240)
            h.power == Power.ICE -> Color.rgb(120, 200, 255)
            else -> Color.rgb(225, 30, 40)
        }
        val blue = when {
            starring -> Color.HSVToColor(floatArrayOf((time * 600f + 180f) % 360f, 0.75f, 0.9f))
            h.power == Power.FIRE -> Color.rgb(225, 30, 40)
            h.power == Power.ICE -> Color.rgb(30, 60, 160)
            else -> Color.rgb(40, 70, 200)
        }
        val brown = Color.rgb(110, 60, 20)
        val outline = Color.rgb(25, 15, 20)
        stroke.color = outline
        stroke.strokeWidth = 3f

        // legs + shoes
        fun leg(off: Float, lift: Float) {
            paint.color = blue
            rect.set(-6f + off, -20f - lift, 6f + off, -6f - lift)
            c.drawRect(rect, paint)
            rect.set(-8f + off, -8f - lift, 11f + off, 0f - lift)
            c.drawRoundRect(rect, 4f, 4f, stroke)
            paint.color = brown
            c.drawRoundRect(rect, 4f, 4f, paint)
        }
        if (airborne) { leg(-8f, 2f); leg(9f, 8f) } else { leg(-7f - swing, 0f); leg(7f + swing, 0f) }

        // back arm
        paint.color = red
        val backArmY = if (airborne) -44f else -32f + swing * 0.5f
        c.drawCircle(-15f, backArmY, 6f, paint)
        paint.color = Color.WHITE
        c.drawCircle(-17f, backArmY + 6f, 5f, paint)

        // shirt + overalls
        rect.set(-14f, -42f, 14f, -18f)
        c.drawRoundRect(rect, 7f, 7f, stroke)
        paint.color = red
        c.drawRoundRect(rect, 7f, 7f, paint)
        paint.color = blue
        rect.set(-13f, -30f, 13f, -14f)
        c.drawRoundRect(rect, 5f, 5f, paint)
        c.drawRect(-10f, -42f, -6f, -30f, paint)
        c.drawRect(6f, -42f, 10f, -30f, paint)
        paint.color = Color.rgb(255, 220, 60)
        c.drawCircle(-8f, -31f, 2.5f, paint)
        c.drawCircle(8f, -31f, 2.5f, paint)

        // front arm (raised when jumping)
        paint.color = red
        if (airborne) {
            c.drawCircle(15f, -46f, 6f, paint)
            paint.color = Color.WHITE
            c.drawCircle(18f, -56f, 6f, paint)
        } else {
            c.drawCircle(15f, -33f - swing * 0.5f, 6f, paint)
            paint.color = Color.WHITE
            c.drawCircle(17f, -26f - swing * 0.5f, 5.5f, paint)
        }

        // head
        c.drawCircle(2f, -52f, 13f, stroke)
        paint.color = skin
        c.drawCircle(2f, -52f, 13f, paint)
        paint.color = brown
        rect.set(-12f, -56f, -5f, -44f)
        c.drawRect(rect, paint)
        paint.color = skin
        c.drawCircle(-6f, -51f, 3.5f, paint) // ear
        // cap
        paint.color = red
        rect.set(-13f, -70f, 16f, -48f)
        c.drawArc(rect, 180f, 180f, true, paint)
        rect.set(3f, -57f, 24f, -52f)
        c.drawRoundRect(rect, 3f, 3f, paint)
        paint.color = Color.WHITE
        c.drawCircle(2f, -63f, 4f, paint)
        // eye, nose, mustache
        paint.color = outline
        rect.set(7f, -57f, 10.5f, -50f)
        c.drawOval(rect, paint)
        paint.color = Color.rgb(240, 170, 130)
        c.drawCircle(15f, -49f, 5f, paint)
        paint.color = Color.rgb(50, 25, 10)
        rect.set(4f, -46f, 18f, -41f)
        c.drawRoundRect(rect, 3f, 3f, paint)
        if (h.power == Power.DRILL) {
            // drill helmet
            path.reset()
            path.moveTo(-12f, -62f); path.lineTo(16f, -62f); path.lineTo(2f, -92f); path.close()
            stroke.strokeWidth = 2.5f
            c.drawPath(path, stroke)
            paint.color = Color.rgb(195, 200, 215)
            c.drawPath(path, paint)
            c.drawLine(-6f, -70f, 10f, -74f, stroke)
            c.drawLine(-2f, -78f, 7f, -81f, stroke)
        }
        c.restore()
    }

    // ---- the bratty boss prince in his propeller pod ----
    private fun drawBoss(c: Canvas, b: Boss) {
        if (b.state == BossState.HURT && (b.timer * 16f).toInt() % 2 == 0) return
        c.save()
        c.translate(b.x, b.y)
        if (b.spin != 0f) c.rotate(sin(b.spin) * 25f)
        c.scale(b.facing.toFloat(), 1f)

        val outline = Color.rgb(25, 15, 20)
        val skin = Color.rgb(250, 205, 80)
        val belly = Color.rgb(255, 240, 180)
        stroke.color = outline
        stroke.strokeWidth = 4f

        // propeller
        val blade = abs(cos(time * 30f)) * 46f + 6f
        paint.color = Color.rgb(90, 90, 100)
        c.drawRect(-4f, 38f, 4f, 52f, paint)
        rect.set(-blade, 48f, blade, 58f)
        paint.color = Color.rgb(170, 170, 185)
        c.drawOval(rect, paint)

        // shell on his back
        paint.color = Color.rgb(40, 150, 60)
        c.drawCircle(-20f, -68f, 30f, stroke)
        c.drawCircle(-20f, -68f, 30f, paint)
        paint.color = Color.WHITE
        for (i in 0 until 3) {
            val a = 3.6f + i * 0.75f
            val bx = -20f + cos(a) * 28f
            val by = -68f + sin(a) * 28f
            path.reset()
            path.moveTo(bx + cos(a - 0.35f) * 4f - cos(a) * 4f, by + sin(a - 0.35f) * 4f - sin(a) * 4f)
            path.lineTo(bx + cos(a) * 16f, by + sin(a) * 16f)
            path.lineTo(bx + cos(a + 0.35f) * 4f - cos(a) * 4f, by + sin(a + 0.35f) * 4f - sin(a) * 4f)
            path.close()
            c.drawPath(path, paint)
        }

        // torso
        rect.set(-24f, -86f, 24f, -30f)
        c.drawOval(rect, stroke)
        paint.color = skin
        c.drawOval(rect, paint)
        rect.set(-10f, -80f, 20f, -34f)
        paint.color = belly
        c.drawOval(rect, paint)

        // throwing arm
        paint.color = skin
        if (b.throwAnim > 0f) {
            c.drawCircle(22f, -112f, 9f, stroke)
            c.drawCircle(22f, -112f, 9f, paint)
        } else {
            c.drawCircle(26f, -62f, 9f, stroke)
            c.drawCircle(26f, -62f, 9f, paint)
        }

        // head
        c.drawCircle(4f, -104f, 30f, stroke)
        paint.color = skin
        c.drawCircle(4f, -104f, 30f, paint)
        // snout
        rect.set(8f, -104f, 44f, -80f)
        c.drawOval(rect, stroke)
        paint.color = belly
        c.drawOval(rect, paint)
        paint.color = outline
        c.drawCircle(36f, -96f, 2.5f, paint)
        // fiery hair tuft
        paint.color = Color.rgb(240, 90, 30)
        path.reset()
        path.moveTo(-20f, -122f)
        path.lineTo(-14f, -150f)
        path.lineTo(-4f, -132f)
        path.lineTo(4f, -156f)
        path.lineTo(10f, -132f)
        path.lineTo(20f, -146f)
        path.lineTo(20f, -124f)
        path.close()
        c.drawPath(path, stroke)
        c.drawPath(path, paint)
        // eyes + angry brows
        paint.color = Color.WHITE
        rect.set(6f, -124f, 20f, -104f)
        c.drawOval(rect, paint)
        rect.set(20f, -122f, 32f, -104f)
        c.drawOval(rect, paint)
        paint.color = outline
        c.drawCircle(16f, -112f, 3.5f, paint)
        c.drawCircle(28f, -111f, 3.5f, paint)
        stroke.strokeWidth = 4f
        c.drawLine(4f, -130f, 20f, -122f, stroke)
        c.drawLine(34f, -128f, 22f, -122f, stroke)
        // bib with a toothy grin doodled on it
        paint.color = Color.WHITE
        path.reset()
        path.moveTo(-22f, -82f)
        path.lineTo(30f, -82f)
        path.lineTo(4f, -54f)
        path.close()
        c.drawPath(path, stroke)
        c.drawPath(path, paint)
        stroke.strokeWidth = 2.5f
        path.reset()
        path.moveTo(-10f, -76f)
        for (i in 0..5) path.lineTo(-6f + i * 6f, if (i % 2 == 0) -70f else -76f)
        c.drawPath(path, stroke)
        stroke.strokeWidth = 4f

        // pod bowl
        rect.set(-70f, -86f, 70f, 46f)
        c.drawArc(rect, 0f, 180f, true, stroke)
        paint.color = Color.rgb(235, 235, 245)
        c.drawArc(rect, 0f, 180f, true, paint)
        // painted angry face on the pod
        paint.color = outline
        path.reset()
        path.moveTo(-36f, -6f); path.lineTo(-12f, 0f); path.lineTo(-14f, 10f); path.lineTo(-34f, 6f); path.close()
        c.drawPath(path, paint)
        path.reset()
        path.moveTo(36f, -6f); path.lineTo(12f, 0f); path.lineTo(14f, 10f); path.lineTo(34f, 6f); path.close()
        c.drawPath(path, paint)
        paint.color = Color.rgb(220, 40, 50)
        rect.set(-30f, 8f, 30f, 36f)
        c.drawArc(rect, 0f, 180f, true, paint)
        paint.color = Color.WHITE
        c.drawRect(-18f, 22f, -8f, 28f, paint)
        c.drawRect(8f, 22f, 18f, 28f, paint)
        // rim
        rect.set(-76f, -30f, 76f, -14f)
        c.drawRoundRect(rect, 8f, 8f, stroke)
        paint.color = Color.rgb(160, 160, 175)
        c.drawRoundRect(rect, 8f, 8f, paint)

        if (b.state == BossState.LOW) {
            // sweat drop to hint "now's your chance!"
            val dy = (time * 1.5f % 1f) * 10f
            paint.color = Color.rgb(120, 200, 255)
            c.drawCircle(-22f, -128f + dy, 6f, paint)
        }
        c.restore()

        if (b.frozen > 0f) {
            rect.set(b.x - 82f, b.y - 165f, b.x + 82f, b.y + 62f)
            paint.color = Color.argb(120, 140, 210, 255)
            c.drawRoundRect(rect, 16f, 16f, paint)
            stroke.color = Color.argb(220, 230, 250, 255)
            stroke.strokeWidth = 4f
            c.drawRoundRect(rect, 16f, 16f, stroke)
            c.drawLine(b.x - 60f, b.y - 140f, b.x - 30f, b.y - 140f, stroke)
            c.drawLine(b.x - 66f, b.y - 130f, b.x - 66f, b.y - 100f, stroke)
        }
        if (b.flash > 0f) {
            paint.color = Color.argb(110, 255, 140, 30)
            c.drawCircle(b.x, b.y - 50f, 95f, paint)
        }

        if (b.state == BossState.LOW && state == State.PLAYING) {
            val bounce = abs(sin(time * 6f)) * 10f
            drawText(c, "STOMP!", b.x, b.y - 180f - bounce, 30f, Color.rgb(255, 240, 90))
        }
    }

    private fun buildBackground(): Bitmap {
        val bmp = Bitmap.createBitmap(WORLD_W.toInt(), WORLD_H.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, GROUND_Y, Color.rgb(30, 12, 55), Color.rgb(170, 60, 40), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, WORLD_W, WORLD_H, p)
        p.shader = null

        val r = Random(7)
        p.color = Color.argb(200, 255, 255, 230)
        repeat(60) { c.drawCircle(r.nextFloat() * WORLD_W, r.nextFloat() * 300f, r.nextFloat() * 1.8f + 0.5f, p) }
        p.color = Color.argb(230, 255, 230, 180)
        c.drawCircle(1080f, 120f, 46f, p)

        // castle silhouette
        p.color = Color.rgb(45, 20, 50)
        val towers = floatArrayOf(40f, 230f, 420f, 700f, 980f, 1180f)
        for ((i, tx) in towers.withIndex()) {
            val th = 220f + (i % 3) * 60f
            c.drawRect(tx, GROUND_Y - th, tx + 90f, GROUND_Y, p)
            for (k in 0 until 4) c.drawRect(tx + k * 24f, GROUND_Y - th - 18f, tx + k * 24f + 14f, GROUND_Y - th, p)
        }
        c.drawRect(0f, GROUND_Y - 160f, WORLD_W, GROUND_Y, p)
        p.color = Color.argb(180, 255, 170, 60)
        for ((i, tx) in towers.withIndex()) {
            val th = 220f + (i % 3) * 60f
            c.drawRoundRect(RectF(tx + 35f, GROUND_Y - th + 40f, tx + 55f, GROUND_Y - th + 75f), 10f, 10f, p)
        }

        // ground bricks
        p.color = Color.rgb(120, 55, 35)
        c.drawRect(0f, GROUND_Y, WORLD_W, WORLD_H, p)
        p.color = Color.rgb(160, 80, 50)
        c.drawRect(0f, GROUND_Y, WORLD_W, GROUND_Y + 8f, p)
        p.color = Color.rgb(70, 30, 20)
        var row = 0
        var y = GROUND_Y + 8f
        while (y < WORLD_H) {
            c.drawRect(0f, y, WORLD_W, y + 3f, p)
            var x = if (row % 2 == 0) 0f else 32f
            while (x < WORLD_W) {
                c.drawRect(x, y, x + 3f, y + 32f, p)
                x += 64f
            }
            y += 32f
            row++
        }

        // floating stone platforms
        for (pl in platforms) {
            if (pl.ground) continue
            p.color = Color.rgb(30, 15, 25)
            c.drawRoundRect(RectF(pl.x - 3f, pl.y - 3f, pl.x + pl.w + 3f, pl.y + 27f), 8f, 8f, p)
            p.color = Color.rgb(130, 120, 140)
            c.drawRoundRect(RectF(pl.x, pl.y, pl.x + pl.w, pl.y + 24f), 6f, 6f, p)
            p.color = Color.rgb(175, 165, 185)
            c.drawRect(pl.x + 4f, pl.y + 2f, pl.x + pl.w - 4f, pl.y + 7f, p)
            p.color = Color.rgb(90, 80, 100)
            var bx = pl.x + 40f
            while (bx < pl.x + pl.w) {
                c.drawRect(bx, pl.y + 8f, bx + 3f, pl.y + 24f, p)
                bx += 50f
            }
        }
        return bmp
    }
}
