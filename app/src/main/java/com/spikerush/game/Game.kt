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
private const val SLIDE_TIME = 2.2f
private const val SLIDE_SPEED = 720f

/** A floor or floating platform. Floating ones sway slowly on a sine path (scaled by [Game.platformAmp]). */
class Platform(
    val baseX: Float, val baseY: Float, val w: Float, val ground: Boolean = false,
    val swayX: Float = 0f, val swayY: Float = 0f, val period: Float = 10f, val phase: Float = 0f,
) {
    var x = baseX
    var y = baseY
    var dx = 0f
    var dy = 0f
    fun under(px: Float, margin: Float = 0f) = px >= x - margin && px <= x + w + margin
    fun xAt(t: Float, amp: Float) = baseX + swayX * amp * sin(t * 6.2832f / period + phase)
    fun yAt(t: Float, amp: Float) = baseY + swayY * amp * sin(t * 6.2832f / period + phase)
    fun move(t: Float, amp: Float) {
        val nx = xAt(t, amp)
        val ny = yAt(t, amp)
        dx = nx - x; dy = ny - y
        x = nx; y = ny
    }
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
    var shield = false
    var platform: Platform? = null
    var star = 0f
    var drilling = false
    var drillTime = 0f
    var drillCooldown = 0f
    var shootCooldown = 0f
    var sliding = 0f
    var slideCooldown = 0f
    val halfW = 17f
    val height = 62f

    fun reset() {
        x = 200f; y = GROUND_Y; vx = 0f; vy = 0f; onGround = true; facing = 1
        invuln = 0f; knock = 0f; dead = false; coyote = 0f; jumpBuffer = 0f
        power = null; star = 0f; drilling = false; drillTime = 0f; drillCooldown = 0f; shootCooldown = 0f
        sliding = 0f; slideCooldown = 0f; shield = false; platform = null
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
    var platform: Platform? = null
    var markPlat: Platform? = null
    var markOff = 0f
    var bounces = 0
    var quake = false
    var dead = false
}

/** Power-ups. FIRE, ICE and DRILL are held until you get hit; STAR is a timed invincibility. */
enum class Power(val label: String) {
    FIRE("FIRE FLOWER"), ICE("ICE FLOWER"), BOOMERANG("BOOMERANG FLOWER"), SHELL("BLUE SHELL"), DRILL("DRILL"), STAR("STAR"),
    ONE_UP("1-UP"), // the rare extra-life mushroom; never held as a power
}

class Shockwave(var x: Float, val dir: Float) {
    var dead = false
}

/** Fire falling from the sky (from round 8). Leaves a short-lived flame where it lands. */
class Meteor(var x: Float, var y: Float, val vx: Float, var vy: Float) {
    var markX = Float.NaN
    var markY = 0f
    var dead = false
}

class Flame(var x: Float, var y: Float, var life: Float, val platform: Platform?)

enum class AllyKind(val title: String, val joinLevel: Int, val color: Int) {
    GINO("GINO", 4, Color.rgb(90, 230, 90)),
    KINO("KINO", 8, Color.rgb(255, 130, 130)),
    ROSA("PRINCESS ROSA", 11, Color.rgb(255, 150, 210)),
}

/** A teammate who fights alongside the hero. Allies can't be beaten, only dazed for a moment. */
class Ally(val kind: AllyKind) {
    var x = 0f
    var y = GROUND_Y
    var vx = 0f
    var vy = 0f
    var onGround = true
    var facing = 1
    var anim = 0f
    var dazed = 0f
    var act = 3f
    var stompCd = 0f
    var t = 0f
    var blessingUsed = false
    var platform: Platform? = null
}

class Item(var x: Float, var y: Float, val type: Power) {
    var platform: Platform? = null
    var vx = 0f
    var vy = -420f
    var life = 11f
    var t = 0f
    var dead = false
}

class Shot(
    var x: Float, var y: Float, var vx: Float, var vy: Float, val ice: Boolean, val boomerang: Boolean = false,
    val turnip: Boolean = false,
) {
    var life = if (boomerang) 3.5f else 1.6f
    var t = 0f
    var returning = false
    val bossesHit = HashSet<Any>()
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
    var kind = BossKind.PRINCE
    var wonder = false
    var slot = 0
    val look get() = if (wonder) kind.wonder else kind.normal
    val active get() = state != BossState.HURT && state != BossState.DEFEATED && state != BossState.ENTER
    val low get() = state == BossState.LOW || state == BossState.SWOOP_DOWN || state == BossState.SWOOP_UP
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
    private val bosses = ArrayList<Boss>()
    private val titleBoss = Boss()
    private val spikes = ArrayList<Spike>()
    private val coins = ArrayList<Coin>()
    private val particles = ArrayList<Particle>()
    private val texts = ArrayList<FloatText>()
    private val items = ArrayList<Item>()
    private val shots = ArrayList<Shot>()
    private var powerTimer = 9f
    private val shockwaves = ArrayList<Shockwave>()
    private val meteors = ArrayList<Meteor>()
    private val flames = ArrayList<Flame>()
    private var meteorTimer = 6f
    private val allies = ArrayList<Ally>()
    private val allyBody = Hero()
    private var arenaT = 0f
    var platformAmp = 0f
        private set

    // Where the last tap landed, in world coordinates (-1 = keyboard/unknown).
    @Volatile var tapX = -1f
    @Volatile var tapY = -1f

    // Who we're fighting this round, and how hard.
    private var round = Rounds.forLevel(1)
    private val wonder get() = round.wonder
    private var diff = 1f
    private val look get() = bosses.firstOrNull()?.look ?: BossKind.PRINCE.normal

    // Per-round events: the green brother (every 3rd round) and the rare 1-UP mushroom.
    private var levelTime = 0f
    private var broUsed = false
    private var broActive = false
    private var broT = 0f
    private val bro = Hero()
    private var oneUpAt = -1f

    val platforms = listOf(
        Platform(0f, GROUND_Y, WORLD_W, ground = true),
        Platform(140f, 455f, 250f, swayY = 45f, period = 9f),
        Platform(890f, 455f, 250f, swayY = 45f, period = 9f, phase = 3.1416f),
        Platform(530f, 315f, 220f, swayX = 160f, period = 12f),
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
        shockwaves.clear()
        state = State.PLAYING
        paused = false
        coinTimer = 5f
        audio.playSong(Music.theme(themeTier(level)), tempoForLevel())
    }

    /** On top of the theme changes, the music picks up a little speed every round. */
    private fun tempoForLevel(lv: Int = level) = min(1.15f, 1f + (lv - 1) * 0.012f)

    private fun setupBoss() {
        round = Rounds.forLevel(level)
        // A slow, steady climb each round, plus a one-round spike for a Wonder round.
        diff = 1f + (level - 1) * 0.4f + (if (wonder) 2.0f else 0f)
        val n = round.kinds.size
        val hp = min(6, 3 + (level - 1) / 4) + (if (wonder) 1 else 0)
        bosses.clear()
        for ((i, kind) in round.kinds.withIndex()) {
            val b = Boss()
            b.kind = kind
            b.wonder = wonder
            b.slot = i
            b.x = WORLD_W * (i + 1) / (n + 1)
            b.y = -170f - i * 60f
            b.state = BossState.ENTER
            // In a team each sibling is a bit less sturdy.
            b.maxHp = if (n > 1) maxOf(2, hp - 1) else hp
            b.hp = b.maxHp
            b.attackTimer = 1.6f + i * 1.1f
            b.targetX = b.x
            bosses += b
        }
        levelTime = 0f
        broUsed = level != 3 // Gino's one-off dash, before he joins for good at round 4
        broActive = false
        // The 1-UP mushroom shows up sparingly: some rounds, at a random moment.
        oneUpAt = if (rnd.nextFloat() < (if (wonder) 0.6f else 0.3f)) 15f + rnd.nextFloat() * 25f else -1f
        shockwaves.clear()
        meteors.clear()
        flames.clear()
        meteorTimer = 6f
        platformAmp = if (level >= 2) 1f else 0f
        setupAllies()
    }

    private fun nextRound() {
        level++
        spikes.forEach { poof(it.x, it.y) }
        spikes.clear()
        shots.clear()
        items.removeAll { it.type == Power.ONE_UP }
        val oldTier = themeTier(level - 1)
        setupBoss()
        state = State.PLAYING
        announceBoss()
        if (themeTier(level) != oldTier) {
            floatText("~ " + Music.themeNames[themeTier(level)] + " ~", WORLD_W / 2, 360f, Color.rgb(200, 220, 255))
        }
    }

    private fun announceBoss() {
        val names = round.kinds.joinToString(" & ") { it.title.removePrefix("THE ") }
        if (round.team) {
            floatText((if (wonder) "WONDER " else "") + "TEAM-UP!", WORLD_W / 2, 270f, look.iris ?: Color.rgb(255, 160, 120))
            floatText(names, WORLD_W / 2, 310f, Color.rgb(255, 220, 140))
        } else if (wonder) {
            floatText("WONDER " + names + "!", WORLD_W / 2, 280f, look.iris ?: Color.rgb(255, 230, 120))
            floatText("Powered up for this round only!", WORLD_W / 2, 320f, Color.rgb(230, 230, 255))
        } else if (level > 1) {
            floatText(names + " APPEARS!", WORLD_W / 2, 290f, Color.rgb(255, 220, 140))
        }
        if (level == 8) floatText("Fire is falling from the sky!", WORLD_W / 2, 400f, Color.rgb(255, 150, 60))
        for (k in AllyKind.values()) {
            if (k.joinLevel == level) {
                floatText(k.title + " JOINS YOUR TEAM!", WORLD_W / 2, 440f, k.color)
                audio.play(Sfx.BRO)
            }
        }
    }

    /** Harder rounds get more ominous music; Wonder rounds always step it up. */
    private fun themeTier(lv: Int): Int {
        if (lv <= 1) return 0
        val r = Rounds.forLevel(lv)
        return if (r.wonder || r.team) (if (lv <= 5) 2 else 3) else (if (lv <= 6) 1 else 2)
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
                titleBoss.x = WORLD_W / 2 + sin(time * 0.9f) * 330f
                titleBoss.y = 330f + sin(time * 2.1f) * 14f
                titleBoss.facing = if (cos(time * 0.9f) > 0f) 1 else -1
                titleBoss.state = BossState.HOVER
                if (tap) startGame()
            }
            State.PLAYING -> {
                if (paused) {
                    if (tap) paused = false
                    return
                }
                updatePlatforms(dt)
                updateHero(dt, jumpEdge, jumpHeld, actionEdge)
                updateBoss(dt)
                updateSpikes(dt)
                updateMeteors(dt)
                updateAllies(dt)
                updateShots(dt)
                updateCoins(dt)
                updateItems(dt)
                updateRoundEvents(dt)
                updateShockwaves(dt)
                checkCollisions()
                scoreClock += dt
                while (scoreClock >= 1f) { scoreClock -= 1f; score += 10 }
            }
            State.CLEAR -> {
                updatePlatforms(dt)
                updateHero(dt, jumpEdge, jumpHeld, actionEdge)
                updateAllies(dt)
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
                    val tx = tapX
                    val ty = tapY
                    if (tx < 0f || (ty > 470f && ty < 610f && tx < WORLD_W / 2)) {
                        continueGame()
                    } else if (ty > 470f && ty < 610f) {
                        state = State.TITLE
                        spikes.clear(); coins.clear(); items.clear(); shots.clear(); allies.clear()
                        meteors.clear(); flames.clear()
                        audio.playSong(Music.main, 0.92f)
                    }
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
        if (h.slideCooldown > 0f) h.slideCooldown -= dt

        var move = 0
        if (inLeft) move -= 1
        if (inRight) move += 1

        if (h.drilling) {
            updateDrilling(dt, move, jumpEdge || actionEdge)
            return
        }
        if (h.sliding > 0f) {
            h.sliding -= dt
            if (actionEdge && h.sliding < SLIDE_TIME - 0.2f) h.sliding = 0f
            if (h.sliding <= 0f) {
                h.sliding = 0f
                h.slideCooldown = 0.6f
                h.vx *= 0.4f
            }
        } else if (actionEdge && h.knock <= 0f) {
            usePower()
        }

        if (h.sliding > 0f) {
            // Tucked in the blue shell: zoom along, steering only flips direction.
            if (move != 0) h.facing = move
            h.vx = h.facing * SLIDE_SPEED
            if (rnd.nextFloat() < dt * 30f && h.onGround) dust(h.x - h.facing * 20f, h.y, 1)
        } else if (h.knock <= 0f) {
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
        if (h.sliding > 0f && (h.x <= h.halfW + 0.5f || h.x >= WORLD_W - h.halfW - 0.5f)) {
            h.facing = -h.facing
            audio.play(Sfx.PLANT)
        }

        val wasGround = h.onGround
        h.onGround = false
        h.platform = null
        if (h.vy >= 0f) {
            for (p in platforms) {
                if (h.x + h.halfW * 0.6f > p.x && h.x - h.halfW * 0.6f < p.x + p.w &&
                    prevY <= p.y + 0.5f + abs(p.dy) && h.y >= p.y
                ) {
                    h.y = p.y
                    h.vy = 0f
                    h.onGround = true
                    h.platform = p
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
                if (h.shootCooldown > 0f || shots.count { !it.turnip } >= 3) return
                h.shootCooldown = 0.28f
                val dir = h.facing.toFloat()
                shots += if (ice) Shot(h.x + dir * 22f, h.y - 36f, dir * 560f, 60f, true)
                else Shot(h.x + dir * 22f, h.y - 36f, dir * 640f, 150f, false)
                audio.play(if (ice) Sfx.ICE_SHOT else Sfx.FIRE_SHOT)
            }
            Power.BOOMERANG -> {
                if (shots.any { it.boomerang }) return
                val dir = h.facing.toFloat()
                shots += Shot(h.x + dir * 20f, h.y - 36f, dir * 900f, 0f, ice = false, boomerang = true)
                audio.play(Sfx.BOOMERANG)
            }
            Power.SHELL -> {
                if (!h.onGround || h.slideCooldown > 0f) return
                h.sliding = SLIDE_TIME
                audio.play(Sfx.SHELL)
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
        for (b in bosses) {
            if (b.low && abs(b.x - h.x) < 95f && b.y > GROUND_Y - 220f) {
                floatText("DRILL ATTACK!", b.x, b.y - 180f, Color.rgb(255, 200, 120))
                damageBoss(b, stomp = false)
            }
        }
    }

    private fun updateBoss(dt: Float) {
        for (b in bosses) updateBoss(b, dt)
    }

    private fun updateBoss(b: Boss, dt: Float) {
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
                    val n = bosses.size
                    b.targetX = if (n > 1) {
                        // teammates keep to their own stretch of sky
                        val lane = WORLD_W / n
                        lane * b.slot + 90f + rnd.nextFloat() * (lane - 180f)
                    } else if (rnd.nextFloat() < 0.5f) hero.x + rnd.nextFloat() * 300f - 150f
                    else 150f + rnd.nextFloat() * (WORLD_W - 300f)
                    b.targetX = b.targetX.coerceIn(120f, WORLD_W - 120f)
                }
                b.x += (b.targetX - b.x) * min(1f, dt * 1.6f)
                b.y = BOSS_HIGH_Y + sin(b.bob * 2.2f) * 12f
                b.attackTimer -= dt
                if (b.attackTimer <= 0f) {
                    // Only one boss swoops down at a time.
                    if (b.attacks >= 4 && bosses.none { it !== b && it.low }) startSwoop(b) else attack(b)
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
                b.x += b.carpetDir * (330f + diff * 20f) * dt
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
                    b.attackTimer = attackInterval() * teamSlowdown() + 0.6f
                }
            }
            BossState.SWOOP_DOWN -> {
                b.x += (b.targetX - b.x) * min(1f, dt * 3f)
                b.y += (BOSS_LOW_Y - b.y) * min(1f, dt * 3.2f)
                if (abs(b.y - BOSS_LOW_Y) < 6f) {
                    b.state = BossState.LOW
                    b.timer = 3.7f
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

    private fun attackInterval() = (2.7f - (diff - 1f) * 0.2f).coerceAtLeast(0.9f)

    /** Each boss in a team throws less often, so the total pressure rises but doesn't double. */
    private fun teamSlowdown() = 1f + 0.65f * (bosses.size - 1)
    private fun plantLife() = min(8.5f, 3.8f + diff * 0.65f)

    private fun attack(b: Boss) {
        b.attacks++
        b.attackTimer = attackInterval() * teamSlowdown()
        val options = ArrayList<Int>()
        options += 0; options += 0; options += 1
        if (diff >= 1.4f) { options += 2; options += 3 }
        if (diff >= 2.8f) { options += 4; options += 1 }
        if (diff >= 3.5f) options += 2
        // Each sibling leans on its signature move, a Wonder boss even more so.
        val sig = if (b.kind.signature == Signature.NONE) 2 else 5
        repeat(if (b.wonder) 4 else if (b.kind.signature == Signature.NONE) 0 else 2) { options += sig }
        var pick = options[rnd.nextInt(options.size)]
        // only one carpet run at a time
        if (pick == 2 && bosses.any { it !== b && (it.state == BossState.CARPET || it.state == BossState.CARPET_PREP) }) pick = 0
        when (pick) {
            0 -> { // aimed lob
                val lead = hero.vx * 0.45f
                lob(b, hero.x + lead + rnd.nextFloat() * 80f - 40f, 1.15f - min(0.3f, diff * 0.04f))
            }
            5 -> signatureAttack(b)
            1 -> { // spread of three
                for (o in intArrayOf(-170, 0, 170)) lob(b, hero.x + o, 1.25f)
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
                lob(b, tx, 0.9f, rolls = true, groundOnly = true)
            }
            4 -> { // spike rain
                repeat(4 + (diff / 2f).toInt()) {
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
    private fun lob(b: Boss, tx0: Float, t: Float, rolls: Boolean = false, groundOnly: Boolean = false): Spike {
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
        return s
    }

    private fun signatureAttack(b: Boss) {
        when (b.kind.signature) {
            Signature.DOUBLE_LOB -> { // Larkin: a quick throw, then a second one aimed where you're running
                lob(b, hero.x, 0.95f)
                lob(b, hero.x + hero.vx * 0.9f + rnd.nextFloat() * 120f - 60f, 1.4f)
            }
            Signature.BOUNCERS -> { // Lemmo: circus balls that bounce before they stick
                for (o in intArrayOf(-140, 140)) lob(b, hero.x + o, 1.0f).bounces = if (b.wonder) 3 else 2
            }
            Signature.RINGS -> { // Wanda: spike rings rolling in from both sides
                lob(b, hero.x - 340f, 0.9f, rolls = true, groundOnly = true).rollTime = 4f
                lob(b, hero.x + 340f, 0.9f, rolls = true, groundOnly = true).rollTime = 4f
            }
            Signature.QUAKE -> { // Royce: a heavy ball whose landing sends shockwaves along the floor
                lob(b, hero.x + rnd.nextFloat() * 200f - 100f, 1.1f, groundOnly = true).quake = true
            }
            Signature.SONATA -> { // Ludo: a five-note fan of spikes
                for ((i, o) in intArrayOf(-320, -160, 0, 160, 320).withIndex()) lob(b, hero.x + o, 1.0f + i * 0.1f)
            }
            Signature.NONE -> {}
        }
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
        s.markPlat = null
        for (step in 1..600) {
            val py = y
            vy += SPIKE_GRAVITY * dt
            x += vx * dt
            y += vy * dt
            if (x < s.r || x > WORLD_W - s.r) vx = -vx * 0.6f
            val t = arenaT + step * dt // platforms keep moving while the spike flies
            for (p in platforms) {
                if (ignorePlatforms && !p.ground) continue
                val px = p.xAt(t, platformAmp)
                val ppy = p.yAt(t, platformAmp)
                if (vy > 0f && x >= px && x <= px + p.w && py + s.r <= ppy + 2f && y + s.r >= ppy) {
                    s.markX = x
                    s.markY = ppy
                    if (!p.ground) { s.markPlat = p; s.markOff = x - px }
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
                        if ((s.rolls || s.quake) && !p.ground) continue // these always aim for the floor
                        if (s.vy > 0f && p.under(s.x) && py + s.r <= p.y + 0.5f + abs(p.dy) && s.y + s.r >= p.y) {
                            s.y = p.y - s.r + 3f
                            s.platform = p
                            s.vy = 0f
                            s.markX = Float.NaN
                            if (s.bounces > 0 && !s.frozen) {
                                s.bounces--
                                s.y = p.y - s.r
                                s.vy = -620f
                                s.vx *= 0.8f
                                predictLanding(s)
                                audio.play(Sfx.PLANT)
                                break
                            }
                            if (s.quake && p.ground && !s.frozen) {
                                shockwaves += Shockwave(s.x, -1f)
                                shockwaves += Shockwave(s.x, 1f)
                                shake = 0.3f
                            }
                            if (s.rolls) {
                                s.state = SpikeState.ROLLING
                                s.vx = sign(hero.x - s.x) * (270f + diff * 18f)
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

    /** The boss (if any) whose pod or body covers (x, y); [below] extends the pod hitbox downward. */
    private fun bossAt(x: Float, y: Float, below: Float = 50f): Boss? = bosses.firstOrNull { b ->
        b.active && ((abs(x - b.x) < 74f && y > b.y - 40f && y < b.y + below) ||
            (abs(x - b.x) < 36f && y > b.y - 140f && y <= b.y - 40f))
    }

    private fun burnBoss(b: Boss, amount: Int, label: String, color: Int) {
        b.burn += amount
        b.flash = 0.18f
        audio.play(Sfx.PLANT)
        if (b.burn >= 4) {
            b.burn = 0
            floatText(label, b.x, b.y - 175f, color)
            damageBoss(b, stomp = false)
        }
    }

    private fun updateShots(dt: Float) {
        for (sh in shots) {
            sh.life -= dt
            sh.spin += dt * 18f
            if (sh.boomerang) {
                updateBoomerang(sh, dt)
                continue
            }
            val py = sh.y
            sh.vy += (if (sh.turnip) 1500f else if (sh.ice) 900f else 1900f) * dt
            sh.x += sh.vx * dt
            sh.y += sh.vy * dt
            if (sh.life <= 0f || sh.x < -20f || sh.x > WORLD_W + 20f || sh.y > WORLD_H) {
                sh.dead = true
                if (sh.ice) shatter(sh.x.coerceIn(0f, WORLD_W), sh.y, 4)
            }
            if (!sh.ice && !sh.turnip && rnd.nextFloat() < dt * 40f) {
                particles += Particle(sh.x, sh.y, 0f, -30f, 0.25f, Color.rgb(255, 160, 40), 5f, 0f)
            }
            for (p in platforms) {
                if (sh.turnip) break // turnips arc straight through to the boss
                if (sh.vy > 0f && p.under(sh.x) && py + 9f <= p.y + 0.5f + abs(p.dy) && sh.y + 9f >= p.y) {
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
            if (sh.ice) {
                // ice also puts out falling fire
                for (m in meteors) {
                    if (!m.dead && abs(m.x - sh.x) < 30f && abs(m.y - sh.y) < 30f) {
                        m.dead = true; sh.dead = true; score += 50
                        shatter(m.x, m.y); floatText("+50", m.x, m.y - 30f, Color.WHITE)
                    }
                }
                for (f in flames) {
                    if (f.life > 0f && abs(f.x - sh.x) < 34f && abs(f.y - sh.y) < 30f) { f.life = 0f; sh.dead = true; shatter(f.x, f.y, 4) }
                }
                if (sh.dead) continue
            }
            val b = bossAt(sh.x, sh.y) ?: continue
            sh.dead = true
            if (sh.ice) {
                b.frozen = 2.6f
                audio.play(Sfx.FREEZE)
                floatText("FROZEN!", b.x, b.y - 225f, Color.rgb(170, 230, 255))
            } else {
                burnBoss(b, 1, if (sh.turnip) "TURNIP TOSS!" else "BURNED!", Color.rgb(255, 160, 60))
            }
        }
        shots.removeAll { it.dead }
    }

    /** Flies out, slows, then homes back to the hero, slicing through every spike on the way. */
    private fun updateBoomerang(sh: Shot, dt: Float) {
        val h = hero
        sh.t += dt
        if (!sh.returning) {
            sh.vx *= (1f - 1.2f * dt)
            sh.x += sh.vx * dt
            if (sh.t > 0.5f || sh.x < 15f || sh.x > WORLD_W - 15f) sh.returning = true
        } else {
            val dx = h.x - sh.x
            val dy = (h.y - 36f) - sh.y
            val d = sqrt(dx * dx + dy * dy)
            if (d < 34f) { sh.dead = true; return }
            sh.vx = dx / d * 760f
            sh.vy = dy / d * 760f
            sh.x += sh.vx * dt
            sh.y += sh.vy * dt
        }
        if (sh.life <= 0f) { sh.dead = true; return }
        for (s in spikes) {
            if (s.dead) continue
            val dx = s.x - sh.x
            val dy = s.y - sh.y
            if (dx * dx + dy * dy < (s.r + 18f) * (s.r + 18f)) smashSpike(s)
        }
        for (m in meteors) {
            if (!m.dead && abs(m.x - sh.x) < 32f && abs(m.y - sh.y) < 32f) { m.dead = true; poof(m.x, m.y); score += 50 }
        }
        // Big and spinning: it also clips the propeller, so a throw from the floor hits a swooping boss.
        // It can hit each boss in a team once per throw.
        val b = bossAt(sh.x, sh.y, below = 80f) ?: return
        if (!sh.bossesHit.add(b)) return
        burnBoss(b, 2, "BONK!", Color.rgb(140, 200, 255))
    }

    private fun updateItems(dt: Float) {
        powerTimer -= dt
        if (powerTimer <= 0f && items.isEmpty()) {
            powerTimer = 11f + rnd.nextFloat() * 6f
            val roll = rnd.nextFloat()
            val type = when {
                roll < 0.18f -> Power.FIRE
                roll < 0.36f -> Power.ICE
                roll < 0.54f -> Power.BOOMERANG
                roll < 0.72f -> Power.SHELL
                roll < 0.88f -> Power.DRILL
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
                if (it.vy > 0f && p.under(it.x) && py <= p.y + 0.5f + abs(p.dy) && it.y >= p.y) {
                    it.y = p.y
                    it.platform = p
                    it.vy = if (it.type == Power.STAR) -720f else 0f
                }
            }
            if (it.y > WORLD_H + 40f) it.dead = true
            if (!it.dead && state == State.PLAYING && abs(it.x - h.x) < 36f && it.y > h.y - h.height - 10f && it.y - 40f < h.y) {
                it.dead = true
                collect(it.type)
            }
        }
        items.removeAll { it.dead }
    }

    private fun updateRoundEvents(dt: Float) {
        levelTime += dt
        // 1-UP mushroom: at most once a round, and not every round.
        if (oneUpAt >= 0f && levelTime >= oneUpAt) {
            oneUpAt = -1f
            val p = platforms[1 + rnd.nextInt(platforms.size - 1)]
            val m = Item(p.x + p.w / 2, p.y - 20f, Power.ONE_UP)
            m.vx = if (rnd.nextBoolean()) 150f else -150f
            m.life = 9f
            items += m
            sparkle(m.x, m.y)
            audio.play(Sfx.ITEM_APPEAR)
            floatText("1-UP MUSHROOM!", m.x, m.y - 60f, Color.rgb(120, 255, 120))
        }
        // The green brother: once on every 3rd round, when the floor is getting crowded.
        if (!broUsed) {
            val floorSpikes = spikes.count { it.state == SpikeState.PLANTED && it.y > GROUND_Y - 40f }
            if ((levelTime > 12f && floorSpikes >= 3) || levelTime > 35f) {
                broUsed = true
                broActive = true
                broT = 0f
                bro.reset()
                bro.x = -50f
                bro.facing = 1
                audio.play(Sfx.BRO)
                floatText("GINO TO THE RESCUE!", WORLD_W / 2, 260f, Color.rgb(90, 230, 90))
            }
        }
        if (broActive) {
            broT += dt
            bro.x += 520f * dt
            bro.vx = 520f
            // big floaty hops along the floor
            val hop = abs(sin(broT * 6f))
            bro.y = GROUND_Y - hop * 90f
            bro.onGround = hop < 0.15f
            bro.anim += dt * 18f
            for (s in spikes) {
                if (!s.dead && abs(s.x - bro.x) < 46f && s.y > GROUND_Y - 130f) {
                    smashSpike(s)
                    sparkle(s.x, s.y)
                }
            }
            if (bro.x > WORLD_W + 60f) broActive = false
        }
    }

    private fun updatePlatforms(dt: Float) {
        arenaT += dt
        for (p in platforms) if (!p.ground) p.move(arenaT, platformAmp)
        // Whatever rests on a platform rides along with it.
        hero.platform?.let { if (hero.onGround && !hero.drilling) { hero.x += it.dx; hero.y += it.dy } }
        for (s in spikes) {
            val p = s.platform ?: continue
            if (s.state != SpikeState.FLYING) { s.x += p.dx; s.y += p.dy }
        }
        for (it in items) {
            val p = it.platform ?: continue
            if (it.vy == 0f) { it.x += p.dx; it.y += p.dy }
        }
        for (a in allies) {
            val p = a.platform ?: continue
            if (a.onGround) { a.x += p.dx; a.y += p.dy }
        }
        for (f in flames) {
            val p = f.platform ?: continue
            f.x += p.dx; f.y += p.dy
        }
    }

    private fun updateMeteors(dt: Float) {
        if (level >= 8) {
            meteorTimer -= dt
            if (meteorTimer <= 0f) {
                meteorTimer = (9f - diff * 0.35f).coerceAtLeast(3.2f) + rnd.nextFloat() * 2f
                val count = (1f + (diff - 3f) / 2.5f).toInt().coerceIn(1, 4)
                repeat(count) { i ->
                    val x = if (rnd.nextFloat() < 0.5f) hero.x + rnd.nextFloat() * 300f - 150f
                    else 60f + rnd.nextFloat() * (WORLD_W - 120f)
                    val m = Meteor(x.coerceIn(40f, WORLD_W - 40f), -40f - i * 110f, rnd.nextFloat() * 80f - 40f, 240f)
                    predictMeteor(m)
                    meteors += m
                }
                audio.play(Sfx.FIRE_SHOT)
            }
        }
        for (m in meteors) {
            if (m.dead) continue
            val py = m.y
            m.vy += 420f * dt
            m.x += m.vx * dt
            m.y += m.vy * dt
            if (rnd.nextFloat() < dt * 60f) {
                particles += Particle(m.x, m.y - 10f, rnd.nextFloat() * 40f - 20f, -60f, 0.35f,
                    if (rnd.nextBoolean()) Color.rgb(255, 120, 30) else Color.rgb(255, 210, 60), 6f, 0f)
            }
            for (p in platforms) {
                if (m.vy > 0f && p.under(m.x) && py <= p.y + 0.5f + abs(p.dy) && m.y >= p.y) {
                    m.dead = true
                    flames += Flame(m.x, p.y, 1.6f, if (p.ground) null else p)
                    repeat(8) {
                        particles += Particle(m.x, p.y - 6f, rnd.nextFloat() * 300f - 150f, -rnd.nextFloat() * 250f, 0.45f,
                            Color.rgb(255, 150 + rnd.nextInt(80), 40), 5f, 700f)
                    }
                    audio.play(Sfx.PLANT)
                    break
                }
            }
            if (m.y > WORLD_H + 40f) m.dead = true
        }
        meteors.removeAll { it.dead }
        for (f in flames) {
            f.life -= dt
            if (f.life > 0f && rnd.nextFloat() < dt * 20f) {
                particles += Particle(f.x + rnd.nextFloat() * 30f - 15f, f.y - 10f, 0f, -90f, 0.4f,
                    Color.rgb(255, 170, 40), 4f, 0f)
            }
        }
        flames.removeAll { it.life <= 0f }
    }

    private fun predictMeteor(m: Meteor) {
        var x = m.x; var y = m.y; var vy = m.vy
        val dt = 1f / 60f
        for (step in 1..400) {
            val py = y
            vy += 420f * dt
            x += m.vx * dt
            y += vy * dt
            val t = arenaT + step * dt
            for (p in platforms) {
                val px = p.xAt(t, platformAmp)
                val ppy = p.yAt(t, platformAmp)
                if (x >= px && x <= px + p.w && py <= ppy + 2f && y >= ppy) {
                    m.markX = x; m.markY = ppy
                    return
                }
            }
        }
    }

    // ------------------------------------------------------------------ allies

    private fun setupAllies() {
        allies.clear()
        for (k in AllyKind.values()) {
            if (level < k.joinLevel) continue
            val a = Ally(k)
            when (k) {
                AllyKind.GINO -> a.x = 220f
                AllyKind.KINO -> a.x = 1060f
                AllyKind.ROSA -> { a.x = 640f; a.y = 300f; a.act = 6f }
            }
            allies += a
        }
    }

    private fun updateAllies(dt: Float) {
        val fighting = state == State.PLAYING
        for (a in allies) {
            a.t += dt
            if (a.stompCd > 0f) a.stompCd -= dt
            when (a.kind) {
                AllyKind.ROSA -> updateRosa(a, dt, fighting)
                AllyKind.GINO -> updateGino(a, dt, fighting)
                AllyKind.KINO -> updateKino(a, dt, fighting)
            }
        }
    }

    private fun allyPhysics(a: Ally, dt: Float, gravityScale: Float) {
        a.vy = min(a.vy + GRAVITY * gravityScale * dt, 1400f)
        val prevY = a.y
        a.x = (a.x + a.vx * dt).coerceIn(20f, WORLD_W - 20f)
        a.y += a.vy * dt
        a.onGround = false
        a.platform = null
        if (a.vy >= 0f) {
            for (p in platforms) {
                if (p.under(a.x, 6f) && prevY <= p.y + 0.5f + abs(p.dy) && a.y >= p.y) {
                    a.y = p.y; a.vy = 0f; a.onGround = true; a.platform = p
                    break
                }
            }
        }
        if (a.onGround) a.anim += dt * abs(a.vx) / 28f
        if (a.vx > 1f) a.facing = 1 else if (a.vx < -1f) a.facing = -1
    }

    /** Spikes, falling fire, flames and shockwaves knock an ally dizzy for a few seconds. */
    private fun allyHazards(a: Ally) {
        if (a.dazed > 0f) return
        var hit = false
        for (s in spikes) {
            if (!s.dead && !s.frozen && abs(s.x - a.x) < 24f && s.y > a.y - 60f && s.y < a.y + 10f) { hit = true; break }
        }
        if (!hit) hit = meteors.any { !it.dead && abs(it.x - a.x) < 26f && it.y > a.y - 60f && it.y < a.y }
        if (!hit) hit = flames.any { it.life > 0f && abs(it.x - a.x) < 24f && abs(it.y - a.y) < 12f }
        if (!hit && a.onGround && a.y >= GROUND_Y - 1f) hit = shockwaves.any { abs(it.x - a.x) < 24f }
        if (hit) {
            a.dazed = 3f
            a.vy = -380f
            a.onGround = false
            audio.play(Sfx.POOF)
            floatText("OUCH!", a.x, a.y - 90f, Color.rgb(255, 200, 200))
        }
    }

    /** Gino hunts floor spikes and jumps on them, and leaps onto a swooping boss's head. */
    private fun updateGino(a: Ally, dt: Float, fighting: Boolean) {
        if (a.dazed > 0f) {
            a.dazed -= dt
            a.vx *= 0.9f
            allyPhysics(a, dt, 0.75f)
            return
        }
        val lowBoss = if (fighting) bosses.firstOrNull { it.low && it.active } else null
        val spike = if (fighting) spikes.filter {
            it.state == SpikeState.PLANTED && !it.frozen && !it.dead && it.y > GROUND_Y - 40f
        }.minByOrNull { abs(it.x - a.x) } else null
        val tx = lowBoss?.x ?: spike?.x ?: (hero.x - 170f * hero.facing).coerceIn(80f, WORLD_W - 80f)
        val dx = tx - a.x
        a.vx = if (a.onGround) (if (abs(dx) > 14f) sign(dx) * 300f else 0f) else (dx * 2f).coerceIn(-300f, 300f)
        if (a.onGround && fighting) {
            if (lowBoss != null && abs(lowBoss.x - a.x) < 110f && a.stompCd <= 0f) {
                a.vy = -1250f
                audio.play(Sfx.JUMP)
            } else if (spike != null && abs(spike.x - a.x) < 80f) {
                a.vy = -820f
            }
        }
        allyPhysics(a, dt, 0.75f)
        if (a.vy > 0f && !a.onGround) {
            for (s in spikes) {
                if (!s.dead && !s.frozen && abs(s.x - a.x) < 32f && a.y >= s.y - s.r - 14f && a.y <= s.y + 8f) {
                    smashSpike(s)
                    a.vy = -600f
                }
            }
            if (a.stompCd <= 0f) {
                for (b in bosses) {
                    if (b.active && abs(a.x - b.x) < 60f && a.y >= b.y - 142f && a.y <= b.y - 98f) {
                        a.vy = -800f
                        a.stompCd = 5f
                        audio.play(Sfx.STOMP)
                        floatText("GINO STOMP!", b.x, b.y - 200f, AllyKind.GINO.color)
                        damageBoss(b, stomp = true)
                        break
                    }
                }
            }
        } else {
            allyHazards(a)
        }
    }

    /** Kino stays near the hero, hops over spikes and lobs turnips at the bosses. */
    private fun updateKino(a: Ally, dt: Float, fighting: Boolean) {
        if (a.dazed > 0f) {
            a.dazed -= dt
            a.vx *= 0.9f
            allyPhysics(a, dt, 1f)
            return
        }
        val tx = (hero.x + (if (hero.x < WORLD_W / 2) 170f else -170f)).coerceIn(60f, WORLD_W - 60f)
        val dx = tx - a.x
        a.vx = if (abs(dx) > 24f) sign(dx) * 360f else 0f
        if (a.onGround) {
            val dir = if (a.vx != 0f) sign(a.vx) else a.facing.toFloat()
            val ahead = spikes.any {
                !it.dead && !it.frozen && it.state != SpikeState.FLYING && (it.x - a.x) * dir in 0f..90f && abs(it.y - (a.y - 15f)) < 30f
            }
            if (ahead) a.vy = -950f
        }
        if (fighting) {
            a.act -= dt
            if (a.act <= 0f) {
                val target = bosses.filter { it.active }.minByOrNull { abs(it.x - a.x) }
                if (target != null) {
                    a.act = 3f
                    val t = 0.85f
                    val sx = a.x
                    val sy = a.y - 40f
                    val vx = (target.x - sx) / t
                    val vy = (target.y - 20f - sy - 0.5f * 1500f * t * t) / t
                    shots += Shot(sx, sy, vx, vy, ice = false, turnip = true)
                    a.facing = if (vx >= 0f) 1 else -1
                    audio.play(Sfx.THROW)
                }
            }
        }
        allyPhysics(a, dt, 1f)
        allyHazards(a)
    }

    /** Princess Rosa floats overhead: she shields the hero, and once a round saves them with an extra life. */
    private fun updateRosa(a: Ally, dt: Float, fighting: Boolean) {
        val tx = (hero.x + (if (hero.x < WORLD_W / 2) 130f else -130f)).coerceIn(80f, WORLD_W - 80f)
        a.x += (tx - a.x) * min(1f, dt * 1.3f)
        a.y = 290f + sin(a.t * 2f) * 16f
        a.facing = if (hero.x >= a.x) 1 else -1
        if (rnd.nextFloat() < dt * 6f) {
            particles += Particle(a.x + rnd.nextFloat() * 40f - 20f, a.y + 10f, 0f, 40f, 0.6f,
                Color.rgb(255, 170, 220), 5f, 0f, star = true)
        }
        if (!fighting) return
        a.act -= dt
        if (a.act <= 0f && !hero.shield) {
            a.act = 15f
            hero.shield = true
            audio.play(Sfx.POWER_UP)
            floatText("ROSA'S SHIELD!", hero.x, hero.y - 100f, AllyKind.ROSA.color)
            sparkle(hero.x, hero.y - 40f)
        }
        if (lives == 1 && !a.blessingUsed) {
            a.blessingUsed = true
            lives++
            audio.play(Sfx.LIFE)
            floatText("ROSA'S BLESSING! +1 LIFE", WORLD_W / 2, 250f, AllyKind.ROSA.color)
        }
    }

    private fun continueGame() {
        lives = 3
        score = 0
        newHi = false
        hero.reset()
        spikes.clear(); coins.clear(); particles.clear(); texts.clear(); items.clear(); shots.clear()
        powerTimer = 9f
        coinTimer = 5f
        setupBoss()
        state = State.PLAYING
        paused = false
        floatText("CONTINUE - ROUND $level", WORLD_W / 2, 230f, Color.rgb(140, 255, 140))
        announceBoss()
        audio.playSong(Music.theme(themeTier(level)), tempoForLevel())
    }

    private fun updateShockwaves(dt: Float) {
        val h = hero
        for (w in shockwaves) {
            w.x += w.dir * 470f * dt
            if (rnd.nextFloat() < dt * 40f) dust(w.x, GROUND_Y, 1)
            if (w.x < -40f || w.x > WORLD_W + 40f) w.dead = true
            if (!w.dead && h.onGround && h.y >= GROUND_Y - 1f && abs(h.x - w.x) < 24f && !h.drilling) {
                if (h.star > 0f || h.sliding > 0f) continue
                hurtHero(w.x)
            }
        }
        shockwaves.removeAll { it.dead }
    }

    private fun collect(type: Power) {
        val h = hero
        if (type == Power.ONE_UP) {
            lives = min(9, lives + 1)
            audio.play(Sfx.LIFE)
            floatText("1-UP!", h.x, h.y - 90f, Color.rgb(120, 255, 120))
            sparkle(h.x, h.y - 40f)
            return
        }
        score += 200
        sparkle(h.x, h.y - 40f)
        floatText(type.label + "!", h.x, h.y - 90f, Color.rgb(255, 240, 120))
        if (type == Power.STAR) {
            h.star = STAR_TIME
            audio.playSong(Music.star, 1f, then = Music.theme(themeTier(level)), thenTempo = tempoForLevel())
        } else {
            if (h.drilling && type != Power.DRILL) surface()
            if (type != Power.SHELL) h.sliding = 0f
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
        if (h.sliding > 0f) {
            for (s in spikes) {
                if (!s.dead && abs(s.x - h.x) < s.r + 26f && s.y > h.y - 50f && s.y < h.y + 10f) smashSpike(s)
            }
            for (b in bosses) {
                if (b.low && b.active && abs(b.x - h.x) < 85f && abs(b.y - h.y) < 140f) {
                    floatText("SHELL SMASH!", b.x, b.y - 180f, Color.rgb(120, 180, 255))
                    damageBoss(b, stomp = false)
                    h.facing = -h.facing
                }
            }
            return
        }
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

        for (m in meteors) {
            if (m.dead) continue
            val cx = m.x.coerceIn(left, right)
            val cy = m.y.coerceIn(top, bottom)
            if ((m.x - cx) * (m.x - cx) + (m.y - cy) * (m.y - cy) < 18f * 18f) {
                m.dead = true
                if (starred) poof(m.x, m.y) else hurtHero(m.x)
            }
        }
        if (!starred && h.invuln <= 0f) {
            for (f in flames) {
                if (f.life > 0f && abs(h.x - f.x) < 26f && h.y >= f.y - 14f && h.y <= f.y + 4f) { hurtHero(f.x); break }
            }
        }

        for (b in bosses) {
            if (!b.active) continue
            val headTop = b.y - 130f
            if (h.vy > 0f && abs(h.x - b.x) < 58f && h.y >= headTop - 10f && h.y <= headTop + 30f) {
                h.vy = -880f
                h.y = b.y - 132f
                audio.play(Sfx.STOMP)
                damageBoss(b, stomp = true)
                return
            }
            if (h.invuln > 0f && !starred) continue
            val hitPod = right > b.x - 66f && left < b.x + 66f && bottom > b.y - 38f && top < b.y + 56f
            val hitBody = right > b.x - 30f && left < b.x + 30f && bottom > b.y - 118f && top < b.y - 38f
            if (hitPod || hitBody) {
                if (starred) {
                    h.vx = (if (h.x >= b.x) 1f else -1f) * 420f
                    h.vy = -500f
                    floatText("STAR POWER!", b.x, b.y - 175f, Color.rgb(255, 240, 120))
                    damageBoss(b, stomp = false)
                } else {
                    hurtHero(b.x)
                }
                return
            }
        }
    }

    private fun hurtHero(fromX: Float) {
        val h = hero
        if (h.invuln > 0f || h.star > 0f || h.drilling || h.sliding > 0f) return
        if (h.shield) {
            h.shield = false
            h.invuln = 1.6f
            h.vy = -420f
            h.onGround = false
            shatter(h.x, h.y - 30f)
            floatText("SHIELD BLOCK!", h.x, h.y - 90f, Color.rgb(255, 170, 220))
            return
        }
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
        h.invuln = 2.5f
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

    private fun damageBoss(b: Boss, stomp: Boolean) {
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
            if (bosses.any { it.state != BossState.DEFEATED }) {
                floatText(b.kind.title.removePrefix("THE ") + " IS OUT!", b.x, b.y - 200f, Color.rgb(255, 220, 140))
                return
            }
            val bonus = 2000 * level * bosses.size
            score += bonus
            floatText("ROUND BONUS +$bonus", WORLD_W / 2, 260f, Color.rgb(255, 220, 60))
            spikes.forEach { poof(it.x, it.y) }
            spikes.clear()
            state = State.CLEAR
            stateTimer = 4.2f
            if (wonder && lives < 9) {
                lives++
                audio.play(Sfx.LIFE)
                floatText("1UP!", h.x, h.y - 90f, Color.rgb(120, 255, 120))
            }
            audio.playSong(Music.clear, 1f, then = Music.theme(themeTier(level + 1)), thenTempo = tempoForLevel(level + 1))
        } else {
            b.state = BossState.HURT
            b.timer = 0.9f
        }
    }

    private fun startSwoop(b: Boss) {
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
    private val background: Bitmap by lazy { buildBackground(false) }
    private var wonderBackground: Bitmap? = null
    private var wonderBackgroundLook: BossLook? = null

    /** Wonder rounds tint the whole arena in the boss's colors (built once per boss and reused). */
    private fun arenaBackground(): Bitmap {
        if (state == State.TITLE || !wonder) return background
        if (wonderBackgroundLook !== look) {
            wonderBackground?.recycle()
            wonderBackground = buildBackground(true, look)
            wonderBackgroundLook = look
        }
        return wonderBackground!!
    }

    fun render(c: Canvas) {
        c.save()
        if (shake > 0f) c.translate(rnd.nextFloat() * 12f - 6f, rnd.nextFloat() * 12f - 6f)
        c.drawBitmap(arenaBackground(), 0f, 0f, null)
        drawPlatforms(c)
        for (f in flames) drawFlame(c, f)

        for (s in spikes) {
            if (s.markX.isNaN()) continue
            // a marker on a moving platform rides along with it
            val mp = s.markPlat
            if (mp != null) drawMarker(c, mp.x + s.markOff, mp.y) else drawMarker(c, s.markX, s.markY)
        }
        for (m in meteors) if (!m.markX.isNaN()) drawMarker(c, m.markX, m.markY)
        for (co in coins) drawCoin(c, co)
        for (it in items) drawItem(c, it)
        for (s in spikes) if (s.state == SpikeState.PLANTED && !s.dead) drawSpike(c, s)

        if (state != State.TITLE) {
            if (hero.drilling) drawDrillMound(c, hero)
            else if (hero.sliding > 0f) drawSlidingHero(c, hero)
            else if (hero.star > 0f || !(hero.invuln > 0f && !hero.dead && (time * 14f).toInt() % 2 == 0)) drawHero(c, hero)
        }
        if (state == State.TITLE) drawBoss(c, titleBoss) else for (b in bosses) drawBoss(c, b)
        for (w in shockwaves) drawShockwave(c, w)
        if (broActive) drawHero(c, bro, greenBro = true)
        if (state != State.TITLE) for (a in allies) drawAlly(c, a)
        for (m in meteors) drawMeteor(c, m)
        if (state != State.TITLE && hero.shield && !hero.drilling) {
            val wob = sin(time * 6f) * 3f
            paint.color = Color.argb(60, 255, 150, 220)
            c.drawCircle(hero.x, hero.y - 32f, 46f + wob, paint)
            stroke.color = Color.argb(200, 255, 190, 235)
            stroke.strokeWidth = 3f
            c.drawCircle(hero.x, hero.y - 32f, 46f + wob, stroke)
        }
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
                if (stateTimer > 1.5f) {
                    drawButton(c, WORLD_W / 2 - 270f, 500f, 250f, "CONTINUE", "from round $level", Color.rgb(60, 150, 70))
                    drawButton(c, WORLD_W / 2 + 20f, 500f, 250f, "NEW GAME", "back to title", Color.rgb(90, 70, 140))
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
        var ix = WORLD_W / 2 - 250f
        for (p in Power.values().filter { it != Power.ONE_UP }) {
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

        if (lives <= 5) {
            for (i in 0 until lives) drawHeart(c, 330f + i * 40f, 36f, 15f)
        } else {
            drawHeart(c, 330f, 36f, 15f)
            text.textAlign = Paint.Align.LEFT
            textStroke.textAlign = Paint.Align.LEFT
            drawText(c, "x$lives", 352f, 48f, 32f, Color.WHITE)
            text.textAlign = Paint.Align.CENTER
            textStroke.textAlign = Paint.Align.CENTER
        }

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
        // One row per boss: name, health pips, and small dots for chip damage (fire/boomerang/turnips).
        for ((row, b) in bosses.withIndex()) {
            val y = 39f + row * 40f
            val pipsW = b.maxHp * 30f
            text.textAlign = Paint.Align.RIGHT
            textStroke.textAlign = Paint.Align.RIGHT
            val name = (if (b.wonder) "WONDER " else "") + b.kind.title.removePrefix("THE ")
            val alpha = if (b.state == BossState.DEFEATED) 0.35f else 1f
            drawText(c, name, WORLD_W - 34f - pipsW, y + 9f, 22f, b.look.iris ?: Color.rgb(255, 160, 120), alpha)
            text.textAlign = Paint.Align.CENTER
            textStroke.textAlign = Paint.Align.CENTER
            for (i in 0 until b.maxHp) {
                val x = WORLD_W - 30f - (b.maxHp - i) * 30f + 15f
                paint.color = Color.rgb(30, 10, 30)
                c.drawCircle(x, y, 12f, paint)
                paint.color = if (i < b.hp) Color.rgb(240, 70, 60) else Color.rgb(80, 60, 70)
                c.drawCircle(x, y, 9f, paint)
            }
            for (i in 0 until b.burn) {
                paint.color = Color.rgb(255, 160, 60)
                c.drawCircle(WORLD_W - 40f - i * 12f, y + 17f, 4f, paint)
            }
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
        if (sh.boomerang) {
            drawBoomerang(c, 1.2f)
        } else if (sh.turnip) {
            paint.color = Color.rgb(60, 170, 60)
            path.reset()
            path.moveTo(-3f, -8f); path.lineTo(-12f, -24f); path.lineTo(0f, -14f); path.lineTo(10f, -24f); path.lineTo(3f, -8f)
            path.close()
            c.drawPath(path, paint)
            stroke.color = Color.rgb(25, 15, 20); stroke.strokeWidth = 2.5f
            rect.set(-11f, -10f, 11f, 12f)
            c.drawOval(rect, stroke)
            paint.color = Color.rgb(250, 245, 235)
            c.drawOval(rect, paint)
            paint.color = Color.rgb(200, 120, 200)
            rect.set(-11f, -10f, 11f, 0f)
            c.drawArc(rect, 180f, 180f, true, paint)
        } else if (sh.ice) {
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

    /** A V-shaped boomerang centered on the canvas origin. */
    private fun drawBoomerang(c: Canvas, scale: Float) {
        path.reset()
        path.moveTo(-16f * scale, -10f * scale)
        path.lineTo(0f, 6f * scale)
        path.lineTo(16f * scale, -10f * scale)
        path.lineTo(16f * scale, -2f * scale)
        path.lineTo(0f, 14f * scale)
        path.lineTo(-16f * scale, -2f * scale)
        path.close()
        stroke.color = Color.rgb(25, 15, 20)
        stroke.strokeWidth = 2.5f
        c.drawPath(path, stroke)
        paint.color = Color.rgb(255, 200, 50)
        c.drawPath(path, paint)
        paint.color = Color.rgb(60, 110, 230)
        c.drawCircle(-12f * scale, -5f * scale, 2.5f * scale, paint)
        c.drawCircle(12f * scale, -5f * scale, 2.5f * scale, paint)
    }

    /** The blue spiny shell, as a dome sitting on y=0. [spin] scrolls its stripes while sliding. */
    private fun drawBlueShell(c: Canvas, w: Float, h: Float, spin: Float) {
        rect.set(-w, -h, w, h)
        stroke.color = Color.rgb(25, 15, 20)
        stroke.strokeWidth = 3f
        c.drawArc(rect, 180f, 180f, true, stroke)
        paint.color = Color.rgb(40, 90, 220)
        c.drawArc(rect, 180f, 180f, true, paint)
        // hexagon plates that scroll as it spins
        paint.color = Color.rgb(90, 150, 255)
        for (k in -2..2) {
            val px = ((k * 0.45f + spin) % 2.25f - 1.1f) * w * 0.9f
            if (abs(px) < w * 0.75f) {
                rect.set(px - w * 0.18f, -h * 0.75f, px + w * 0.18f, -h * 0.35f)
                c.drawOval(rect, paint)
            }
        }
        // white spikes on top
        paint.color = Color.WHITE
        for (k in -1..1) {
            val sx = k * w * 0.45f
            val base = -h * (if (k == 0) 0.98f else 0.8f)
            path.reset()
            path.moveTo(sx - 5f, base + 4f); path.lineTo(sx, base - 10f); path.lineTo(sx + 5f, base + 4f); path.close()
            c.drawPath(path, paint)
        }
        rect.set(-w - 2f, -h * 0.22f, w + 2f, 2f)
        c.drawRoundRect(rect, 4f, 4f, stroke)
        paint.color = Color.rgb(245, 240, 225)
        c.drawRoundRect(rect, 4f, 4f, paint)
    }

    private fun drawSlidingHero(c: Canvas, h: Hero) {
        c.save()
        c.translate(h.x, h.y)
        if (h.star > 0f) {
            paint.color = Color.argb(90, 255, 250, 160)
            c.drawCircle(0f, -20f, 40f, paint)
        }
        drawBlueShell(c, 26f, 34f, time * 8f * h.facing)
        // speed lines
        stroke.color = Color.argb(160, 255, 255, 255)
        stroke.strokeWidth = 3f
        for (k in 0 until 3) {
            val ly = -8f - k * 9f
            c.drawLine(-h.facing * 32f, ly, -h.facing * (46f + k * 6f), ly, stroke)
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
            Power.FIRE, Power.ICE, Power.BOOMERANG -> {
                val petal = when (type) {
                    Power.FIRE -> Color.rgb(255, 110, 30)
                    Power.ICE -> Color.rgb(110, 190, 255)
                    else -> Color.rgb(45, 85, 225)
                }
                val inner = when (type) {
                    Power.FIRE -> Color.rgb(255, 225, 60)
                    Power.ICE -> Color.rgb(235, 250, 255)
                    else -> Color.rgb(255, 255, 255)
                }
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
                if (type == Power.BOOMERANG) {
                    c.save()
                    c.translate(14f, -18f)
                    c.rotate(t * 200f)
                    drawBoomerang(c, 0.55f)
                    c.restore()
                }
            }
            Power.SHELL -> {
                c.translate(0f, 14f)
                drawBlueShell(c, 22f, 30f, t * 2f)
            }
            Power.ONE_UP -> {
                // green-spotted extra-life mushroom
                rect.set(-12f, -2f, 12f, 20f)
                c.drawRoundRect(rect, 6f, 6f, stroke)
                paint.color = Color.rgb(250, 235, 200)
                c.drawRoundRect(rect, 6f, 6f, paint)
                paint.color = Color.rgb(25, 15, 20)
                c.drawRect(-6f, 4f, -3f, 12f, paint)
                c.drawRect(3f, 4f, 6f, 12f, paint)
                rect.set(-24f, -24f, 24f, 16f)
                c.drawArc(rect, 180f, 180f, true, stroke)
                paint.color = Color.rgb(40, 190, 70)
                c.drawArc(rect, 180f, 180f, true, paint)
                paint.color = Color.WHITE
                c.drawCircle(0f, -14f, 6.5f, paint)
                c.drawCircle(-15f, -6f, 4.5f, paint)
                c.drawCircle(15f, -6f, 4.5f, paint)
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

    private fun drawBossHair(c: Canvas, lk: BossLook) {
        when (lk.hairStyle) {
            HairStyle.TUFT -> {
                paint.color = lk.hair
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
            }
            HairStyle.MOHAWK, HairStyle.RAINBOW -> {
                val rainbow = intArrayOf(
                    Color.rgb(255, 70, 70), Color.rgb(255, 160, 40), Color.rgb(255, 230, 60),
                    Color.rgb(80, 210, 90), Color.rgb(70, 140, 255),
                )
                for (i in 0 until 5) {
                    val bx = -22f + i * 10f
                    val tall = if (lk.hairStyle == HairStyle.MOHAWK) 44f - abs(i - 2) * 6f else 30f - abs(i - 2) * 3f
                    path.reset()
                    path.moveTo(bx - 7f, -126f)
                    path.lineTo(bx - 4f, -126f - tall)
                    path.lineTo(bx + 7f, -126f)
                    path.close()
                    paint.color = if (lk.hairStyle == HairStyle.RAINBOW) rainbow[i] else lk.hair
                    c.drawPath(path, stroke)
                    c.drawPath(path, paint)
                }
            }
            HairStyle.BOW -> {
                paint.color = lk.hair
                path.reset()
                path.moveTo(4f, -136f); path.lineTo(-24f, -158f); path.lineTo(-26f, -122f); path.close()
                c.drawPath(path, stroke); c.drawPath(path, paint)
                path.reset()
                path.moveTo(4f, -136f); path.lineTo(30f, -160f); path.lineTo(34f, -124f); path.close()
                c.drawPath(path, stroke); c.drawPath(path, paint)
                c.drawCircle(4f, -136f, 8f, stroke)
                c.drawCircle(4f, -136f, 8f, paint)
                paint.color = Color.WHITE
                c.drawCircle(-14f, -140f, 3.5f, paint)
                c.drawCircle(22f, -142f, 3.5f, paint)
                c.drawCircle(-18f, -130f, 2.5f, paint)
                c.drawCircle(26f, -132f, 2.5f, paint)
            }
            HairStyle.SHADES -> {} // bald and proud; the shades go on with the eyes
            HairStyle.WILD -> {
                paint.color = lk.hair
                // big swept-back conductor's mane
                path.reset()
                path.moveTo(-12f, -146f); path.lineTo(-62f, -158f); path.lineTo(-40f, -132f)
                path.lineTo(-70f, -126f); path.lineTo(-36f, -112f); path.close()
                c.drawPath(path, stroke)
                c.drawPath(path, paint)
                for (i in 0 until 6) {
                    val a = 3.3f + i * 0.3f
                    c.drawCircle(-4f + cos(a) * 32f, -122f + sin(a) * 26f, 19f - i * 1.5f, stroke)
                }
                for (i in 0 until 6) {
                    val a = 3.3f + i * 0.3f
                    c.drawCircle(-4f + cos(a) * 32f, -122f + sin(a) * 26f, 19f - i * 1.5f, paint)
                }
                c.drawPath(path, paint)
            }
        }
    }

    private fun drawButton(c: Canvas, x: Float, y: Float, w: Float, label: String, sub: String, color: Int) {
        rect.set(x, y, x + w, y + 90f)
        paint.color = color
        c.drawRoundRect(rect, 16f, 16f, paint)
        stroke.color = Color.WHITE
        stroke.strokeWidth = 4f
        c.drawRoundRect(rect, 16f, 16f, stroke)
        drawText(c, label, x + w / 2, y + 44f, 34f, Color.WHITE)
        drawText(c, sub, x + w / 2, y + 74f, 20f, Color.rgb(230, 230, 230))
    }

    private fun drawPlatforms(c: Canvas) {
        val p = paint
        // floating stone platforms
        for (pl in platforms) {
            if (pl.ground) continue
            p.color = Color.rgb(30, 15, 25)
            rect.set(pl.x - 3f, pl.y - 3f, pl.x + pl.w + 3f, pl.y + 27f)
            c.drawRoundRect(rect, 8f, 8f, p)
            p.color = Color.rgb(130, 120, 140)
            rect.set(pl.x, pl.y, pl.x + pl.w, pl.y + 24f)
            c.drawRoundRect(rect, 6f, 6f, p)
            p.color = Color.rgb(175, 165, 185)
            c.drawRect(pl.x + 4f, pl.y + 2f, pl.x + pl.w - 4f, pl.y + 7f, p)
            p.color = Color.rgb(90, 80, 100)
            var bx = pl.x + 40f
            while (bx < pl.x + pl.w) {
                c.drawRect(bx, pl.y + 8f, bx + 3f, pl.y + 24f, p)
                bx += 50f
            }
        }
    }

    private fun drawMeteor(c: Canvas, m: Meteor) {
        val flick = sin(time * 40f) * 2f
        paint.color = Color.argb(120, 255, 120, 30)
        c.drawCircle(m.x, m.y - 8f, 24f + flick, paint)
        paint.color = Color.rgb(230, 70, 20)
        c.drawCircle(m.x, m.y, 16f, paint)
        paint.color = Color.rgb(255, 170, 40)
        c.drawCircle(m.x - 2f, m.y + 2f, 10f, paint)
        paint.color = Color.rgb(255, 240, 160)
        c.drawCircle(m.x - 3f, m.y + 4f, 5f, paint)
    }

    private fun drawFlame(c: Canvas, f: Flame) {
        val a = (f.life / 1.6f).coerceIn(0f, 1f)
        for (k in -1..1) {
            val h = (28f + sin(time * 25f + k * 2f) * 8f) * (0.4f + 0.6f * a)
            path.reset()
            path.moveTo(f.x + k * 12f - 10f, f.y)
            path.lineTo(f.x + k * 12f, f.y - h)
            path.lineTo(f.x + k * 12f + 10f, f.y)
            path.close()
            paint.color = Color.argb((230 * a).toInt(), 255, 110 + (k + 1) * 50, 30)
            c.drawPath(path, paint)
        }
    }

    private fun drawAlly(c: Canvas, a: Ally) {
        when (a.kind) {
            AllyKind.GINO -> {
                val b = allyBody
                b.x = a.x; b.y = a.y; b.vx = a.vx; b.onGround = a.onGround; b.facing = a.facing; b.anim = a.anim
                drawHero(c, b, greenBro = true)
            }
            AllyKind.KINO -> drawKino(c, a)
            AllyKind.ROSA -> drawRosa(c, a)
        }
        if (a.dazed > 0f) {
            for (k in 0 until 3) {
                val ang = time * 6f + k * 2.09f
                paint.color = Color.rgb(255, 235, 90)
                drawStar(c, a.x + cos(ang) * 22f, a.y - 82f + sin(ang) * 6f, 7f, 3f)
            }
        }
    }

    /** A little mushroom-capped helper with a vest. */
    private fun drawKino(c: Canvas, a: Ally) {
        c.save()
        c.translate(a.x, a.y)
        c.scale(a.facing.toFloat(), 1f)
        val swing = if (a.onGround && abs(a.vx) > 30f) sin(a.anim) * 5f else 0f
        stroke.color = Color.rgb(25, 15, 20)
        stroke.strokeWidth = 3f
        paint.color = Color.rgb(110, 60, 20)
        rect.set(-12f - swing, -7f, 0f - swing, 0f); c.drawRoundRect(rect, 3f, 3f, paint)
        rect.set(2f + swing, -7f, 14f + swing, 0f); c.drawRoundRect(rect, 3f, 3f, paint)
        paint.color = Color.WHITE
        rect.set(-10f, -18f, 10f, -6f); c.drawRect(rect, paint)
        paint.color = Color.rgb(50, 90, 210)
        rect.set(-12f, -32f, 12f, -16f); c.drawRoundRect(rect, 5f, 5f, stroke); c.drawRoundRect(rect, 5f, 5f, paint)
        paint.color = Color.rgb(255, 215, 170)
        c.drawCircle(2f, -40f, 10f, stroke); c.drawCircle(2f, -40f, 10f, paint)
        paint.color = Color.rgb(25, 15, 20)
        c.drawRect(4f, -45f, 6.5f, -38f, paint); c.drawRect(9f, -45f, 11.5f, -38f, paint)
        // the cap
        rect.set(-22f, -70f, 26f, -36f)
        c.drawOval(rect, stroke)
        paint.color = Color.WHITE
        c.drawOval(rect, paint)
        paint.color = Color.rgb(220, 40, 50)
        c.drawCircle(2f, -62f, 7f, paint)
        c.drawCircle(-14f, -52f, 5f, paint)
        c.drawCircle(18f, -52f, 5f, paint)
        c.restore()
    }

    /** The floating princess: pink gown, golden hair and crown. */
    private fun drawRosa(c: Canvas, a: Ally) {
        c.save()
        c.translate(a.x, a.y)
        c.scale(a.facing.toFloat(), 1f)
        paint.color = Color.argb(70, 255, 180, 230)
        c.drawCircle(0f, -30f, 50f, paint)
        stroke.color = Color.rgb(25, 15, 20)
        stroke.strokeWidth = 3f
        // gown
        path.reset()
        path.moveTo(-8f, -38f); path.lineTo(8f, -38f); path.lineTo(26f, 10f); path.lineTo(-26f, 10f); path.close()
        c.drawPath(path, stroke)
        paint.color = Color.rgb(250, 130, 190)
        c.drawPath(path, paint)
        paint.color = Color.rgb(255, 180, 220)
        c.drawRect(-22f, 0f, 22f, 6f, paint)
        // hair, face, crown
        paint.color = Color.rgb(255, 220, 90)
        rect.set(-18f, -66f, 14f, -26f); c.drawOval(rect, paint)
        paint.color = Color.rgb(255, 220, 190)
        c.drawCircle(4f, -52f, 11f, stroke); c.drawCircle(4f, -52f, 11f, paint)
        paint.color = Color.rgb(60, 110, 220)
        c.drawCircle(8f, -54f, 2.5f, paint)
        paint.color = Color.rgb(255, 200, 40)
        path.reset()
        path.moveTo(-6f, -62f); path.lineTo(-6f, -74f); path.lineTo(-1f, -68f); path.lineTo(4f, -76f)
        path.lineTo(9f, -68f); path.lineTo(14f, -74f); path.lineTo(14f, -62f); path.close()
        c.drawPath(path, paint)
        paint.color = Color.rgb(60, 140, 255)
        c.drawCircle(4f, -66f, 2.5f, paint)
        c.restore()
    }

    private fun drawShockwave(c: Canvas, w: Shockwave) {
        val pulse = sin(time * 30f) * 3f
        paint.color = Color.argb(200, 255, 200, 120)
        rect.set(w.x - 22f, GROUND_Y - 26f - pulse, w.x + 22f, GROUND_Y + 6f)
        c.drawArc(rect, 180f, 180f, true, paint)
        paint.color = Color.argb(220, 255, 240, 200)
        rect.inset(8f, 8f)
        c.drawArc(rect, 180f, 180f, true, paint)
        stroke.color = Color.argb(200, 255, 160, 60)
        stroke.strokeWidth = 3f
        c.drawLine(w.x - w.dir * 30f, GROUND_Y - 6f, w.x - w.dir * 50f, GROUND_Y - 6f, stroke)
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
    private fun drawHero(c: Canvas, h: Hero, greenBro: Boolean = false) {
        c.save()
        c.translate(h.x, h.y)
        if (greenBro) c.scale(0.95f, 1.15f) // the taller, lankier brother
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
            greenBro -> Color.rgb(40, 170, 70)
            starring -> Color.HSVToColor(floatArrayOf((time * 600f) % 360f, 0.75f, 1f))
            h.power == Power.FIRE -> Color.rgb(250, 248, 240)
            h.power == Power.ICE -> Color.rgb(120, 200, 255)
            h.power == Power.BOOMERANG -> Color.rgb(45, 95, 230)
            else -> Color.rgb(225, 30, 40)
        }
        val blue = when {
            greenBro -> Color.rgb(30, 40, 130)
            starring -> Color.HSVToColor(floatArrayOf((time * 600f + 180f) % 360f, 0.75f, 0.9f))
            h.power == Power.FIRE -> Color.rgb(225, 30, 40)
            h.power == Power.ICE -> Color.rgb(30, 60, 160)
            h.power == Power.BOOMERANG -> Color.rgb(245, 245, 250)
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

        if (h.power == Power.SHELL) {
            c.save()
            c.translate(-13f, -16f)
            c.rotate(-15f)
            drawBlueShell(c, 15f, 24f, 0f)
            c.restore()
        }

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
        // Each sibling has its own colors; a Wonder boss swaps to its powered-up palette and glows.
        val lk = if (state == State.TITLE) BossKind.PRINCE.normal else b.look
        val skin = lk.skin
        val belly = lk.belly
        stroke.color = outline
        stroke.strokeWidth = 4f

        lk.aura?.let {
            paint.color = it
            c.drawCircle(0f, -40f, 110f + sin(time * 4f) * 6f, paint)
            if (rnd.nextFloat() < 0.15f) {
                particles += Particle(b.x + rnd.nextFloat() * 160f - 80f, b.y - rnd.nextFloat() * 160f, 0f, -60f, 0.6f,
                    Color.argb(220, Color.red(it), Color.green(it), Color.blue(it)), 5f, 0f, star = true)
            }
        }

        // propeller
        val blade = abs(cos(time * 30f)) * 46f + 6f
        paint.color = Color.rgb(90, 90, 100)
        c.drawRect(-4f, 38f, 4f, 52f, paint)
        rect.set(-blade, 48f, blade, 58f)
        paint.color = Color.rgb(170, 170, 185)
        c.drawOval(rect, paint)

        // shell on his back
        paint.color = lk.shell
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
        drawBossHair(c, lk)
        // eyes + angry brows
        paint.color = Color.WHITE
        rect.set(6f, -124f, 20f, -104f)
        c.drawOval(rect, paint)
        rect.set(20f, -122f, 32f, -104f)
        c.drawOval(rect, paint)
        lk.iris?.let {
            paint.color = it
            c.drawCircle(16f, -112f, 5.5f, paint)
            c.drawCircle(28f, -111f, 5.5f, paint)
        }
        paint.color = outline
        c.drawCircle(16f, -112f, 3.5f, paint)
        c.drawCircle(28f, -111f, 3.5f, paint)
        stroke.strokeWidth = 4f
        c.drawLine(4f, -130f, 20f, -122f, stroke)
        c.drawLine(34f, -128f, 22f, -122f, stroke)
        if (lk.hairStyle == HairStyle.BOW) {
            // lashes
            stroke.strokeWidth = 2.5f
            c.drawLine(8f, -122f, 3f, -127f, stroke)
            c.drawLine(31f, -120f, 36f, -124f, stroke)
            stroke.strokeWidth = 4f
        }
        if (lk.hairStyle == HairStyle.SHADES) {
            // cool pink shades
            paint.color = Color.rgb(25, 15, 20)
            c.drawRect(2f, -117f, 36f, -114f, paint)
            paint.color = lk.hair
            rect.set(5f, -121f, 19f, -105f); c.drawRoundRect(rect, 5f, 5f, paint)
            rect.set(21f, -120f, 35f, -105f); c.drawRoundRect(rect, 5f, 5f, paint)
            paint.color = Color.argb(200, 255, 255, 255)
            c.drawRect(7f, -118f, 10f, -114f, paint)
            c.drawRect(23f, -117f, 26f, -113f, paint)
        }
        lk.bib?.let { bibColor ->
            // bib (a green scarf on the dark prince) with a toothy grin doodled on it
            paint.color = bibColor
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
        }

        // pod bowl
        rect.set(-70f, -86f, 70f, 46f)
        c.drawArc(rect, 0f, 180f, true, stroke)
        paint.color = lk.pod
        c.drawArc(rect, 0f, 180f, true, paint)
        // painted angry face on the pod
        paint.color = outline
        path.reset()
        path.moveTo(-36f, -6f); path.lineTo(-12f, 0f); path.lineTo(-14f, 10f); path.lineTo(-34f, 6f); path.close()
        c.drawPath(path, paint)
        path.reset()
        path.moveTo(36f, -6f); path.lineTo(12f, 0f); path.lineTo(14f, 10f); path.lineTo(34f, 6f); path.close()
        c.drawPath(path, paint)
        paint.color = lk.mouth
        rect.set(-30f, 8f, 30f, 36f)
        c.drawArc(rect, 0f, 180f, true, paint)
        paint.color = Color.WHITE
        c.drawRect(-18f, 22f, -8f, 28f, paint)
        c.drawRect(8f, 22f, 18f, 28f, paint)
        // rim
        rect.set(-76f, -30f, 76f, -14f)
        c.drawRoundRect(rect, 8f, 8f, stroke)
        paint.color = lk.rim
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

    private fun buildBackground(dark: Boolean, lk: BossLook = BossKind.PRINCE.wonder): Bitmap {
        val bmp = Bitmap.createBitmap(WORLD_W.toInt(), WORLD_H.toInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(
            0f, 0f, 0f, GROUND_Y,
            if (dark) Color.rgb(12, 4, 28) else Color.rgb(30, 12, 55),
            if (dark) lk.sky else Color.rgb(170, 60, 40),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, WORLD_W, WORLD_H, p)
        p.shader = null

        val r = Random(7)
        p.color = Color.argb(200, 255, 255, 230)
        repeat(60) { c.drawCircle(r.nextFloat() * WORLD_W, r.nextFloat() * 300f, r.nextFloat() * 1.8f + 0.5f, p) }
        p.color = if (dark) lk.moon else Color.argb(230, 255, 230, 180)
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
        p.color = if (dark) lk.moon else Color.argb(180, 255, 170, 60)
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

        return bmp
    }
}
