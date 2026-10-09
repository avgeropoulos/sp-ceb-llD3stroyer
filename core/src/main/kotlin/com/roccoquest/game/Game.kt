package com.roccoquest.game

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign
import kotlin.math.sin

/**
 * Rocco's Quest: the whole game simulation and its rendering, independent of Android.
 * Call [update] at a fixed 60 Hz and [render] once per frame.
 */
class Game(
    val input: Input,
    private val sound: SoundSink = SoundSink {},
    private val storage: Storage = Storage.Memory(),
) {
    enum class State { TITLE, INTRO, PLAYING, DYING, FLAG, DOOR, AXE, WORLD_CLEAR, GAME_OVER, VICTORY, ALL_CLEAR }

    var state = State.TITLE
        private set
    var viewW = 400f
    var time = 0f
        private set
    private var stateTime = 0f
    var paused = false
        private set

    var levelIndex = 0
        private set
    var level = Level(Levels.all[0])
        private set
    var hero = Hero(0f, 0f)
        private set
    val entities = ArrayList<Entity>()
    private val pending = ArrayList<Entity>()
    private val particles = ArrayList<Particle>()
    private val bumps = ArrayList<Bump>()

    var score = 0
        private set
    var coins = 0
        private set
    var lives = 3
        private set
    var camX = 0f
        private set
    var bossDefeated = false
        private set
    private var bossBannerTime = 0f
    private var bannerTitle = ""
    private var bannerSub = ""
    /** While Krag Jr. is attacking, the camera is locked so the fight stays on one screen. */
    private var camLock: Float? = null

    /** Wonder mode: the level goes wild until Rocco finds the Wonder Seed. */
    var wonderMode = false
        private set
    private var spikeRain = 0f
    private var coinRain = 0f
    private var flyerRain = 0f

    val bestScore get() = storage.load("best")
    val wonderUnlocked get() = storage.load("wonder") == 1
    private val bonusStart get() = Levels.all.indexOfFirst { it.world == Levels.BONUS_WORLD }

    private fun saveBest() {
        if (score > bestScore) storage.save("best", score)
    }

    private var flagX = -1f
    private var flagTopY = 0f
    private var flagBaseY = 0f
    private var flagY = 0f
    private var castleX = -1f
    private var castleBase = 0f
    private var collapse = ArrayList<Int>()
    private var collapseTimer = 0f

    private var prevJump = false
    private var prevFire = false
    private var prevPause = false
    private var prevDown = false
    private var prevDir = 0
    private var lastTapDir = 0
    private var lastTapTime = -10f
    private var prevMusicToggle = false
    var musicOn = true
        private set
    private var currentMusic = Music.NONE
    private var heroPrevBottom = 0f

    private class Bump(val tx: Int, val ty: Int) { var t = 0f }

    fun sfx(s: Sound) = sound.play(s)
    fun spawn(e: Entity) { pending += e }
    fun puff(x: Float, y: Float) { particles += Puff(x, y) }
    fun explosion(x: Float, y: Float) {
        particles += Explosion(x, y)
        sfx(Sound.BREAK)
    }
    fun debris(x: Float, y: Float, c: Int) {
        particles += Debris(x, y, -60f, -320f, c)
        particles += Debris(x + 8, y, 60f, -320f, c)
        particles += Debris(x, y + 8, -60f, -220f, c)
        particles += Debris(x + 8, y + 8, 60f, -220f, c)
    }
    fun addScore(n: Int, x: Float, y: Float) {
        score += n
        particles += FloatText(x, y, n.toString())
    }
    fun isOnScreen(b: Body) = b.x + b.w > camX && b.x < camX + viewW

    // ==================================================================== Flow

    fun newGame(fromLevel: Int = 0) {
        score = 0
        coins = 0
        lives = 3
        hero = Hero(0f, 0f)
        startLevel(fromLevel)
    }

    /** Index of the first level in the current world (where a continue restarts). */
    private fun worldStart(): Int = Levels.all.indexOfFirst { it.world == level.def.world }

    private fun setState(s: State) {
        state = s
        stateTime = 0f
    }

    fun startLevel(index: Int) {
        levelIndex = index
        level = Level(Levels.all[index])
        val power = hero.power
        entities.clear(); pending.clear(); particles.clear(); bumps.clear(); collapse.clear()
        flagX = -1f
        castleX = -1f
        bossDefeated = false
        bossBannerTime = 0f
        camLock = null
        wonderMode = false
        camX = 0f
        for (ty in 0 until level.h) for (tx in 0 until level.w) {
            val px = tx * TILE.toFloat()
            val py = ty * TILE.toFloat()
            val bottom = py + TILE
            when (level.def.rows[ty][tx]) {
                '@' -> hero = Hero(px + 2, bottom - Hero.SMALL_H).also { it.power = power }
                'g' -> entities += Grumbler(px, bottom)
                'w' -> entities += Grumbler(px, bottom, winged = true)
                'M' -> entities += Pip(px, bottom)
                'k' -> entities += Shellback(px, bottom)
                'c' -> entities += Coin(px, py)
                'p' -> entities += Podoboo(px, py)
                'K' -> entities += Krag(px, bottom, level.def.bossHp)
                'J' -> entities += KragJr(px, bottom)
                'W' -> entities += WonderFlower(px, bottom)
                'Z' -> entities += WonderSeed(px, bottom)
                'R' -> entities += Princess(px, bottom)
                'A' -> entities += Axe(px, bottom)
                'E' -> entities += Door(px, bottom)
                'F' -> {
                    flagX = px; flagTopY = py
                    var by = ty
                    while (by < level.h && !level.solid(tx, by)) by++
                    flagBaseY = by * TILE.toFloat()
                    flagY = flagTopY + 10
                }
                'C' -> { castleX = px; castleBase = bottom }
            }
        }
        setState(State.INTRO)
    }

    /** Goes to the next level, or to the final screen after the last one. */
    private fun advance() {
        if (levelIndex + 1 >= Levels.all.size) {
            saveBest()
            setState(State.ALL_CLEAR)
        } else {
            startLevel(levelIndex + 1)
        }
    }

    private fun loseLife() {
        lives--
        if (lives <= 0) {
            saveBest()
            setState(State.GAME_OVER)
        } else {
            hero.power = Power.SMALL
            startLevel(levelIndex)
        }
    }

    // ==================================================================== Update

    fun update(dt: Float) {
        time += dt
        val jumpPressed = input.jump && !prevJump
        val firePressed = input.fire && !prevFire
        val pausePressed = input.pause && !prevPause
        val downPressed = input.down && !prevDown
        prevDown = input.down
        if (input.musicToggle && !prevMusicToggle) musicOn = !musicOn
        prevMusicToggle = input.musicToggle
        prevJump = input.jump
        prevFire = input.fire
        prevPause = input.pause
        val tap = input.tap
        input.tap = false
        val tapX = input.tapX

        val inPlay = state == State.PLAYING || state == State.DYING || state == State.FLAG ||
            state == State.DOOR || state == State.AXE
        if (inPlay && pausePressed) paused = !paused
        if (paused) { updateMusic(); return }
        stateTime += dt

        when (state) {
            State.TITLE -> if (tap && stateTime > 0.3f) {
                newGame(if (wonderUnlocked && tapX > viewW / 2) bonusStart else 0)
            }
            State.INTRO -> if (stateTime > 2.2f) setState(State.PLAYING)
            State.PLAYING -> {
                updateHero(dt, jumpPressed, firePressed, downPressed)
                if (wonderMode) updateWonder(dt)
                updateWorld(dt)
                if (state == State.PLAYING) interact(dt)
                updateCamera()
            }
            State.DYING -> {
                if (stateTime > 0.5f) {
                    hero.vy += 900f * dt
                    hero.y += hero.vy * dt
                }
                if (stateTime > 3f) loseLife()
            }
            State.FLAG -> updateFlag(dt)
            State.DOOR -> {
                hero.visible = stateTime < 0.4f
                updateParticles(dt)
                if (stateTime > 1.6f) advance()
            }
            State.AXE -> updateAxe(dt)
            State.WORLD_CLEAR -> {
                updateParticles(dt)
                if (stateTime > 6f || (stateTime > 2f && tap)) advance()
            }
            State.GAME_OVER -> if (stateTime > 2f && tap) newGame(worldStart())
            State.VICTORY -> {
                updateParticles(dt)
                if (stateTime > 3f && tap) startLevel(bonusStart) // on to the bonus Wonder World
            }
            State.ALL_CLEAR -> if (stateTime > 3f && tap) {
                hero = Hero(0f, 0f); level = Level(Levels.all[0]); camX = 0f; setState(State.TITLE)
            }
        }
        if (bossBannerTime > 0) bossBannerTime -= dt
        updateMusic()
    }

    private fun updateHero(dt: Float, jumpPressed: Boolean, firePressed: Boolean, downPressed: Boolean) {
        val h = hero
        val mini = h.power == Power.MINI
        var dir = (if (input.right) 1 else 0) - (if (input.left) 1 else 0)

        // Double-tap a direction (and keep holding it) to run.
        if (dir != 0 && dir != prevDir) {
            h.running = dir == lastTapDir && time - lastTapTime < 0.35f
            lastTapDir = dir
            lastTapTime = time
        }
        if (dir == 0) h.running = false
        prevDir = dir
        val running = h.running || input.run
        h.shellDash = h.power == Power.SHELL && running && h.onGround && abs(h.vx) > 140f && !h.crouching

        // Hold down on the ground to crouch (big Rocco stays down under low ceilings).
        val wantCrouch = input.down && h.onGround && !h.slamming
        if (wantCrouch && !h.crouching) h.crouching = true
        if (!wantCrouch && h.crouching && !(h.power.isBig && ceilingAbove(h))) h.crouching = false
        if (h.crouching && h.onGround) dir = 0

        // Swipe down in mid-air for a butt slam.
        if (downPressed && !h.onGround && !h.slamming) {
            h.slamming = true
            h.slamPause = 0.18f
            h.crouching = false
            h.vx = 0f
            h.vy = 0f
        }

        val slippery = level.def.theme.slippery && h.onGround
        if (h.slamming) {
            h.vx = 0f
        } else if (dir != 0) {
            val acc = if (!h.onGround) 420f else if (slippery) 260f else 600f
            h.vx += dir * acc * dt
            if (h.onGround && h.vx * dir < 0) h.vx += dir * acc * dt // skid
            h.facing = dir
        } else {
            val friction = when {
                !h.onGround -> 120f
                slippery -> 110f
                h.crouching -> 300f
                else -> 520f
            }
            h.vx = approach(h.vx, 0f, friction * dt)
        }
        val maxV = if (running) Hero.RUN_SPEED else Hero.MAX_SPEED
        if (abs(h.vx) > maxV) h.vx = approach(h.vx, sign(h.vx) * maxV, 400f * dt)

        h.coyote = if (h.onGround) 0.1f else h.coyote - dt
        h.jumpBuffer = if (jumpPressed) 0.12f else h.jumpBuffer - dt
        if (h.jumpBuffer > 0 && h.coyote > 0 && !h.slamming) {
            h.vy = (if (mini) -300f else Hero.JUMP_V) - abs(h.vx) * 0.15f
            h.jumpBuffer = 0f
            h.coyote = 0f
            h.onGround = false
            sfx(Sound.JUMP)
        }
        h.jumpHeld = input.jump
        if (h.slamming) {
            if (h.slamPause > 0) { h.slamPause -= dt; h.vy = 0f } else h.vy = 480f
        } else {
            var grav = if (h.vy < 0 && h.jumpHeld) 600f else 1500f
            var maxFall = 420f
            if (mini) { grav *= 0.55f; maxFall = 200f } // Mini Rocco floats
            if (wonderMode) grav *= 0.75f
            h.vy = min(h.vy + grav * dt, maxFall)
        }

        heroPrevBottom = h.bottom
        val vxBefore = h.vx
        if (level.moveX(h, h.vx * dt)) {
            // A spinning Blue Shell smashes the bricks it runs into.
            h.vx = if (h.shellDash && breakSide(h, sign(vxBefore).toInt())) vxBefore else 0f
        }
        if (h.x < camX) { h.x = camX; if (h.vx < 0) h.vx = 0f }
        if (camLock != null && h.x + h.w > camX + viewW) { h.x = camX + viewW - h.w; if (h.vx > 0) h.vx = 0f }
        val ceilX = level.moveY(h, h.vy * dt)
        if (ceilX != null && !h.slamming) {
            h.vy = 30f
            hitBlock(ceilX, tileOf(h.y - 1))
        }
        landOnFrozenBlocks(h)
        if (h.slamming && h.onGround) slamImpact(h)
        h.walkAnim += abs(h.vx) * dt / 7f

        if (firePressed && h.power.canThrow && !h.crouching && !h.slamming) {
            val fx = if (h.facing > 0) h.x + h.w else h.x - 8
            val shots = entities.count { it is Fireball } + pending.count { it is Fireball }
            fun count(pred: (Entity) -> Boolean) = entities.count(pred) + pending.count(pred)
            when (h.power) {
                Power.BOOM -> if (count { it is Boomerang } == 0) { spawn(Boomerang(fx, h.y + 5, h.facing)); sfx(Sound.KICK) }
                Power.SHELL -> if (count { it is HomingShell } == 0) { spawn(HomingShell(fx, h.y, h.facing)); sfx(Sound.KICK) }
                Power.CANNON -> if (count { it is BlasterBullet } < 2 && h.throwAnim <= 0) {
                    spawn(BlasterBullet(if (h.facing > 0) h.x + h.w else h.x - 16, h.y + 4, h.facing))
                    sfx(Sound.BOSS_HIT)
                    h.throwAnim = 0.45f
                }
                else -> if (shots < 2) {
                    spawn(Fireball(fx, h.y + 6, h.facing, ice = h.power == Power.ICE))
                    sfx(Sound.FIRE)
                }
            }
            if (h.power != Power.CANNON) h.throwAnim = 0.15f
        }
        h.throwAnim -= dt
        h.invuln -= dt
        h.flash -= dt
        h.starTime -= dt

        if (level.isLavaAt(h.cx, h.bottom - 3) || h.y > level.h * TILE + 8) die()
    }

    /** Breaks bricks directly beside Rocco; returns true if any broke. */
    private fun breakSide(h: Hero, dir: Int): Boolean {
        val tx = if (dir > 0) tileOf(h.x + h.w + 1) else tileOf(h.x - 1)
        var broke = false
        for (ty in tileOf(h.y)..tileOf(h.y + h.h - 0.01f)) {
            if (level[tx, ty] == T.BRICK) { breakBrick(tx, ty); broke = true }
            else if (level[tx, ty] == T.QCOIN || level[tx, ty] == T.QPOWER) hitBlock(tx, ty, fromAbove = true)
        }
        return broke
    }

    /** True if there is no room for big Rocco to stand up. */
    private fun ceilingAbove(h: Hero): Boolean {
        val top = tileOf(h.bottom - Hero.BIG_H)
        val bot = tileOf(h.y - 0.01f)
        for (ty in top..bot) for (tx in tileOf(h.x)..tileOf(h.x + h.w - 0.01f)) if (level.solid(tx, ty)) return true
        return false
    }

    /** Frozen enemies are platforms; a butt slam shatters them. */
    private fun landOnFrozenBlocks(h: Hero) {
        if (h.vy < 0) return
        for (e in entities) {
            if (e !is FrozenBlock || e.removed) continue
            if (h.x + h.w <= e.x || h.x >= e.x + e.w || heroPrevBottom > e.y + 0.5f || h.bottom < e.y) continue
            if (h.slamming && h.slamPause <= 0) {
                e.shatter(this)
                addScore(100, e.cx, e.y)
                continue
            }
            h.y = e.y - h.h
            h.vy = 0f
            h.onGround = true
        }
    }

    /** A butt slam smashes bricks (and keeps going), opens ? blocks and shakes enemies loose. */
    private fun slamImpact(h: Hero) {
        val row = tileOf(h.bottom + 1)
        var broke = false
        for (tx in tileOf(h.x)..tileOf(h.x + h.w - 0.01f)) {
            when (level[tx, row]) {
                T.BRICK -> if (h.power != Power.MINI) { breakBrick(tx, row); broke = true }
                T.QCOIN, T.QPOWER -> hitBlock(tx, row, fromAbove = true)
            }
        }
        if (broke) {
            h.onGround = false
            return
        }
        h.slamming = false
        puff(h.cx - 8, h.bottom - 2)
        puff(h.cx + 8, h.bottom - 2)
        sfx(Sound.STOMP)
        for (e in entities) {
            if (e is Enemy && !e.dying && e.active && !e.spiky && e !is KragJr &&
                abs(e.bottom - h.bottom) < 4f && abs(e.cx - h.cx) < 44f
            ) {
                e.knockOut(this)
                addScore(100, e.cx, e.y)
            }
        }
    }

    private fun breakBrick(tx: Int, ty: Int) {
        level[tx, ty] = T.EMPTY
        val c = if (level.def.theme == Theme.UNDERGROUND) 0xFF2C64B8.toInt() else 0xFFC84C0C.toInt()
        debris(tx * TILE.toFloat(), ty * TILE.toFloat(), c)
        score += 50
        sfx(Sound.BREAK)
    }

    private fun hitBlock(tx: Int, ty: Int, fromAbove: Boolean = false) {
        when (level[tx, ty]) {
            T.QCOIN -> {
                level[tx, ty] = T.USED
                bumps += Bump(tx, ty)
                particles += CoinPop(tx * TILE.toFloat(), ty * TILE - 16f)
                collectCoin(tx * TILE + 8f, ty * TILE - 20f)
            }
            T.QPOWER -> {
                val item = level.itemAt(tx, ty)
                level[tx, ty] = T.USED
                bumps += Bump(tx, ty)
                spawn(PowerItem(tx * TILE.toFloat(), ty * TILE.toFloat(), item))
                sfx(Sound.BUMP)
            }
            T.BRICK -> {
                if (hero.power.isBig) {
                    breakBrick(tx, ty)
                } else {
                    bumps += Bump(tx, ty)
                    sfx(Sound.BUMP)
                }
            }
            else -> sfx(Sound.BUMP)
        }
        if (fromAbove) return
        // Enemies standing on a bumped block get knocked out.
        val top = ty * TILE.toFloat()
        for (e in entities) {
            if (e is Enemy && !e.dying && e.active && abs(e.bottom - top) < 3f &&
                e.x + e.w > tx * TILE && e.x < (tx + 1) * TILE
            ) {
                e.knockOut(this)
                addScore(100, e.cx, e.y)
            }
        }
    }

    fun collectCoin(x: Float, y: Float) {
        coins++
        score += 200
        if (coins >= 100) {
            coins -= 100
            lives++
            sfx(Sound.ONEUP)
            particles += FloatText(x, y, "1UP")
        } else {
            sfx(Sound.COIN)
        }
    }

    private fun updateWorld(dt: Float) {
        for (e in entities) {
            if (!e.active && e.x < camX + viewW + 48 && e.x + e.w > camX - 48) e.active = true
            if (e.active && !e.removed) e.update(this, dt)
            if (e is Enemy && e !is Krag && e !is KragJr && e.x + e.w < camX - 96) e.removed = true
        }
        entities += pending
        pending.clear()
        entities.removeAll { it.removed }
        updateParticles(dt)
    }

    private fun updateParticles(dt: Float) {
        for (p in particles) p.update(dt)
        particles.removeAll { it.removed }
        for (b in bumps) b.t += dt
        bumps.removeAll { it.t > 0.2f }
    }

    private fun interact(dt: Float) {
        val h = hero
        for (e in entities) {
            if (!e.active || e.removed || !e.overlaps(h)) continue
            when (e) {
                is Enemy -> {
                    if (e.dying || (e is Grumbler && e.isSquashed)) continue
                    if (h.starTime > 0) { e.starHit(this); continue }
                    if (h.shellDash && !e.spiky && e !is KragJr) {
                        e.knockOut(this)
                        addScore(200, e.cx, e.y)
                        continue
                    }
                    val fromAbove = h.vy > 0 && heroPrevBottom <= e.y + 6
                    if (fromAbove && h.slamming && !e.spiky) {
                        if (e is KragJr) {
                            e.damage(this, 3)
                            h.slamming = false
                            h.vy = -320f
                            h.y = e.y - h.h
                        } else {
                            // The slam ploughs straight through.
                            e.knockOut(this)
                            addScore(200, e.cx, e.y)
                        }
                    } else if (fromAbove && e.stompable) {
                        e.stomp(this)
                        h.slamming = false
                        h.vy = if (input.jump) -340f else -210f
                        h.y = e.y - h.h
                    } else {
                        e.touchHero(this)
                    }
                }
                is PowerItem -> if (e.ready) {
                    e.removed = true
                    collectItem(e)
                }
                is Coin -> { e.removed = true; collectCoin(e.cx, e.y) }
                is WonderFlower -> { e.removed = true; startWonder() }
                is WonderSeed -> {
                    e.removed = true
                    endWonder()
                    addScore(5000, e.cx, e.y)
                }
                is Axe -> { e.removed = true; startCollapse() }
                is Pip -> if (bossDefeated) {
                    h.vx = 0f
                    sfx(Sound.CLEAR)
                    setState(State.WORLD_CLEAR)
                }
                is Princess -> if (bossDefeated) {
                    h.vx = 0f
                    h.facing = if (e.cx > h.cx) 1 else -1
                    sfx(Sound.VICTORY)
                    storage.save("wonder", 1)
                    saveBest()
                    setState(State.VICTORY)
                }
                is Door -> if (h.onGround) {
                    sfx(Sound.CLEAR)
                    setState(State.DOOR)
                }
                else -> {}
            }
            if (state != State.PLAYING) return
        }
        if (flagX >= 0 && h.x + h.w >= flagX - 1 && h.y < flagBaseY + TILE) {
            val height = flagBaseY - h.bottom
            val bonus = when {
                height > 120 -> 5000
                height > 90 -> 2000
                height > 60 -> 800
                height > 30 -> 400
                else -> 100
            }
            addScore(bonus, flagX + 8, h.y)
            h.x = flagX + 7 - h.w
            h.vx = 0f; h.vy = 0f
            h.facing = 1
            sfx(Sound.CLEAR)
            setState(State.FLAG)
        }
    }

    private fun collectItem(item: PowerItem) {
        val h = hero
        when (item.kind) {
            Item.MUSHROOM -> if (!h.power.isBig) h.power = Power.BIG
            Item.FIRE -> h.power = Power.FIRE
            Item.ICE -> h.power = Power.ICE
            Item.BOOM -> h.power = Power.BOOM
            Item.MINI -> h.power = Power.MINI
            Item.STAR -> h.starTime = Hero.STAR_TIME
            Item.SHELL -> h.power = Power.SHELL
            Item.CANNON -> h.power = Power.CANNON
        }
        h.flash = 0.6f
        addScore(1000, item.cx, item.y)
        sfx(Sound.POWERUP)
    }

    /** Flower powers drop back to big Rocco, big Rocco to small; small or mini Rocco loses a life. */
    fun hurtHero() {
        val h = hero
        if (h.invuln > 0 || h.starTime > 0 || state != State.PLAYING) return
        when {
            h.power.canThrow -> h.power = Power.BIG
            h.power == Power.BIG -> h.power = Power.SMALL
            else -> { die(); return }
        }
        h.invuln = 2f
        sfx(Sound.HURT)
    }

    private fun die() {
        if (state != State.PLAYING) return
        hero.crouching = false
        hero.slamming = false
        hero.starTime = 0f
        hero.power = Power.SMALL
        hero.vy = -330f
        hero.invuln = 0f
        sfx(Sound.DIE)
        setState(State.DYING)
    }

    private fun startWonder() {
        wonderMode = true
        spikeRain = 2.5f
        coinRain = 0f
        flyerRain = 4f
        bannerTitle = "WONDER!"
        bannerSub = "Find the Wonder Seed!"
        bossBannerTime = 2.5f
        sfx(Sound.POWERUP)
        for (e in entities) if (e is Grumbler && isOnScreen(e)) e.giveWings()
    }

    private fun endWonder() {
        wonderMode = false
        for (e in entities) if (e is SpikeBall) e.knockOut(this)
        bannerTitle = "WONDER SEED GET!"
        bannerSub = "+5000"
        bossBannerTime = 2.5f
        sfx(Sound.ONEUP)
    }

    /** While in Wonder mode, spike balls, coins and flyers rain down. */
    private fun updateWonder(dt: Float) {
        spikeRain -= dt
        coinRain -= dt
        flyerRain -= dt
        val rx = camX + 24 + kotlin.random.Random.nextFloat() * (viewW - 48)
        if (spikeRain <= 0) { spawn(SpikeBall(rx, -14f).also { it.active = true }); spikeRain = 1.5f }
        if (coinRain <= 0) { spawn(Coin(rx, -16f, falling = true)); coinRain = 0.4f }
        if (flyerRain <= 0) {
            spawn(Grumbler(camX + viewW + 4, 80f, winged = true).also { it.active = true })
            flyerRain = 3.5f
        }
    }

    fun onJrEngaged() {
        camLock = camX
    }

    fun onJrDefeated() {
        camLock = null
        for (e in entities) if (e is SpikeBall) e.knockOut(this)
        bannerTitle = "KRAG JR. RETREATS!"
        bannerSub = "Onward, Rocco!"
        bossBannerTime = 3f
        sfx(Sound.CLEAR)
    }

    fun onBossDefeated() {
        if (bossDefeated) return
        bossDefeated = true
        bannerTitle = "KING KRAG IS DEFEATED!"
        bannerSub = if (level.def.rows.any { 'R' in it }) "Go to Princess Rosalie!" else "Go see what Pip has to say!"
        bossBannerTime = 3.5f
        for (e in entities) if (e is KragFlame) e.removed = true
        sfx(Sound.CLEAR)
    }

    private fun desiredMusic(): Music {
        if (!musicOn || paused) return Music.NONE
        return when (state) {
            State.TITLE -> Music.OVERWORLD
            State.PLAYING, State.AXE -> {
                val krag = entities.firstOrNull { it is Krag }
                when {
                    hero.starTime > 0 -> Music.STAR
                    wonderMode -> Music.WONDER
                    entities.any { it is KragJr && it.engaged } -> Music.BOSS
                    krag != null && krag.active && !bossDefeated -> Music.BOSS
                    else -> level.def.theme.music
                }
            }
            else -> Music.NONE
        }
    }

    private fun updateMusic() {
        val m = desiredMusic()
        if (m != currentMusic) {
            currentMusic = m
            sound.music(m)
        }
    }

    private fun updateCamera() {
        val target = hero.cx - viewW * 0.4f
        var maxCam = max(0f, level.pixelW - viewW)
        camLock?.let { maxCam = min(maxCam, it) }
        camX = max(camX, target).coerceIn(0f, maxCam)
        camX = camX.toInt().toFloat()
    }

    private fun updateFlag(dt: Float) {
        val h = hero
        val slideDone = h.bottom >= flagBaseY - 0.5f
        if (!slideDone) h.y = min(h.y + 130f * dt, flagBaseY - h.h)
        flagY = min(flagY + 130f * dt, flagBaseY - 18f)
        if (slideDone && stateTime > 0.9f) {
            h.vx = 70f
            h.walkAnim += 70f * dt / 7f
            level.moveX(h, h.vx * dt)
            h.vy = min(h.vy + 1500f * dt, 420f)
            level.moveY(h, h.vy * dt)
            val door = castleX + 2.5f * TILE
            if (castleX >= 0 && h.cx >= door) h.visible = false
        } else {
            h.vx = 0f
        }
        updateParticles(dt)
        updateCamera()
        if (stateTime > 5f) startLevel(levelIndex + 1)
    }

    private fun startCollapse() {
        collapse.clear()
        for (tx in level.w - 1 downTo 0) for (ty in 0 until level.h) {
            if (level[tx, ty] == T.BRIDGE) collapse += tx * 1000 + ty
        }
        collapseTimer = 0.4f
        hero.vx = 0f
        setState(State.AXE)
    }

    private fun updateAxe(dt: Float) {
        collapseTimer -= dt
        if (collapseTimer <= 0 && collapse.isNotEmpty()) {
            val c = collapse.removeAt(0)
            level[c / 1000, c % 1000] = T.EMPTY
            if (collapse.size % 3 == 0) sfx(Sound.BREAK)
            collapseTimer = 0.05f
        }
        updateWorld(dt)
        if (collapse.isEmpty() && (bossDefeated || stateTime > 4f)) {
            if (!bossDefeated) onBossDefeated()
            setState(State.PLAYING)
        }
    }

    // ==================================================================== Render

    fun render(gfx: Gfx) {
        when (state) {
            State.TITLE -> renderTitle(gfx)
            State.INTRO -> renderIntro(gfx)
            else -> {
                renderWorld(gfx)
                renderHud(gfx)
                when (state) {
                    State.GAME_OVER -> renderGameOver(gfx)
                    State.VICTORY -> renderVictory(gfx)
                    State.ALL_CLEAR -> renderAllClear(gfx)
                    State.WORLD_CLEAR -> renderWorldClear(gfx)
                    else -> {
                        if (state == State.PLAYING || state == State.AXE || state == State.DYING) {
                            Controls.draw(gfx, input, viewW, hero.power.canThrow)
                        }
                        Controls.drawPause(gfx, viewW, musicOn)
                    }
                }
                if (bossBannerTime > 0) {
                    gfx.shadowText(bannerTitle, viewW / 2, 80f, 14f, 0xFFFFD21F.toInt(), 1)
                    gfx.shadowText(bannerSub, viewW / 2, 98f, 10f, 0xFFFFB8DC.toInt(), 1)
                }
                if (paused) {
                    gfx.rect(0f, 0f, viewW, VIEW_H, 0x99000000.toInt())
                    gfx.shadowText("PAUSED", viewW / 2, 110f, 20f, 0xFFFFFFFF.toInt(), 1)
                    gfx.shadowText("Tap the pause button to resume", viewW / 2, 132f, 9f, 0xFFDDDDDD.toInt(), 1)
                    Controls.drawPause(gfx, viewW, musicOn)
                }
            }
        }
    }

    private fun renderBackground(gfx: Gfx, cam: Float) {
        when (level.def.theme) {
            Theme.OVERWORLD -> {
                gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF6FB6FF.toInt())
                gfx.rect(0f, 150f, viewW, 90f, 0xFF8CC8FF.toInt())
                // Hills (parallax 0.5)
                val hp = cam * 0.5f
                var i = (hp / 300f).toInt() - 1
                while (i * 300f - hp < viewW + 300f) {
                    val hx = i * 300f - hp
                    gfx.oval(hx - 2, 136f, 184f, 144f, 0xFF2E7D1F.toInt())
                    gfx.oval(hx, 138f, 180f, 140f, 0xFF5CC84A.toInt())
                    gfx.oval(hx + 60, 152f, 10f, 18f, 0xFF2E7D1F.toInt())
                    gfx.oval(hx + 100, 160f, 10f, 18f, 0xFF2E7D1F.toInt())
                    gfx.oval(hx + 170, 172f, 120f, 80f, 0xFF4CB531.toInt())
                    i++
                }
                // Clouds (parallax 0.3)
                val cp = cam * 0.3f
                i = (cp / 230f).toInt() - 1
                while (i * 230f - cp < viewW + 230f) {
                    val cx = i * 230f - cp
                    val cy = 40f + (i * 37 % 3) * 18f
                    cloud(gfx, cx, cy)
                    i++
                }
            }
            Theme.SNOW -> {
                gfx.rect(0f, 0f, viewW, VIEW_H, 0xFFBFE1FF.toInt())
                gfx.rect(0f, 120f, viewW, 120f, 0xFFDDEEFF.toInt())
                layer(cam, 0.25f, 300f) { i, x ->
                    val hgt = 90f + (i * 17 % 3) * 25f
                    gfx.poly(floatArrayOf(x, x + 110, x + 220), floatArrayOf(210f, 210f - hgt, 210f), 0xFF8FA8C8.toInt())
                    gfx.poly(
                        floatArrayOf(x + 110 - 30, x + 110, x + 110 + 30, x + 110 + 10, x + 110 - 8),
                        floatArrayOf(210f - hgt + 30, 210f - hgt, 210f - hgt + 30, 210f - hgt + 24, 210f - hgt + 32),
                        0xFFFFFFFF.toInt(),
                    )
                }
                layer(cam, 0.5f, 180f) { i, x ->
                    gfx.poly(floatArrayOf(x + 20, x + 35, x + 50), floatArrayOf(208f, 160f, 208f), 0xFF2E6E4A.toInt())
                    gfx.poly(floatArrayOf(x + 24, x + 35, x + 46), floatArrayOf(178f, 156f, 178f), 0xFFFFFFFF.toInt())
                }
                // Falling snow
                for (k in 0 until 40) {
                    val sx = ((k * 97f + time * 12f + sin(time + k) * 10f) % (viewW + 20f) + viewW + 20f) % (viewW + 20f) - 10f
                    val sy = (k * 53f + time * (20f + k % 5 * 6f)) % VIEW_H
                    gfx.rect(sx, sy, 2f, 2f, 0xDDFFFFFF.toInt())
                }
            }
            Theme.SKY -> {
                gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF8FD0FF.toInt())
                gfx.rect(0f, 120f, viewW, 120f, 0xFFB4E2FF.toInt())
                layer(cam, 0.2f, 260f) { i, x -> bigCloud(gfx, x, 150f + (i * 29 % 3) * 14f, 0xFFE6F5FF.toInt()) }
                layer(cam, 0.35f, 340f) { i, x -> island(gfx, x + 40, 90f + (i * 31 % 3) * 26f) }
                layer(cam, 0.5f, 200f) { i, x -> cloud(gfx, x, 30f + (i * 37 % 4) * 22f) }
            }
            Theme.NIGHT -> {
                gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF0E1433.toInt())
                gfx.rect(0f, 140f, viewW, 100f, 0xFF1A2350.toInt())
                layer(cam, 0.05f, 37f) { i, x ->
                    val y = 30f + (i * 71 % 11) * 13f
                    val tw = if (sin(time * 3 + i * 1.7f) > 0.6f) 2f else 1f
                    gfx.rect(x, y, tw, tw, 0xFFFFFFE0.toInt())
                }
                val mx = viewW * 0.8f - cam * 0.02f
                gfx.oval(mx, 34f, 30f, 30f, 0xFFFFF4C0.toInt())
                gfx.oval(mx + 9, 30f, 26f, 26f, 0xFF0E1433.toInt())
                layer(cam, 0.35f, 340f) { i, x -> island(gfx, x + 40, 100f + (i * 31 % 3) * 26f) }
                layer(cam, 0.5f, 240f) { i, x -> bigCloud(gfx, x, 60f + (i * 37 % 4) * 24f, 0x55AFC0FF) }
            }
            Theme.DESERT -> {
                gfx.rect(0f, 0f, viewW, VIEW_H, 0xFFFF9E5E.toInt())
                gfx.rect(0f, 60f, viewW, 60f, 0xFFFFB877.toInt())
                gfx.rect(0f, 120f, viewW, 120f, 0xFFFFD39A.toInt())
                gfx.oval(viewW * 0.7f - cam * 0.03f, 60f, 54f, 54f, 0xFFFFF0B0.toInt())
                layer(cam, 0.25f, 380f) { i, x ->
                    val h = 70f + (i * 13 % 3) * 20f
                    gfx.poly(floatArrayOf(x, x + h, x + h * 2), floatArrayOf(208f, 208f - h, 208f), 0xFFE0A060.toInt())
                    gfx.poly(floatArrayOf(x + h, x + h * 2, x + h * 1.3f), floatArrayOf(208f - h, 208f, 208f), 0xFFC48446.toInt())
                }
                layer(cam, 0.5f, 260f) { i, x ->
                    gfx.oval(x, 170f, 220f, 90f, 0xFFF2C46E.toInt())
                    val cx = x + 70 + (i * 41 % 3) * 30
                    gfx.rect(cx, 160f, 6f, 30f, 0xFF3E9A3A.toInt())
                    gfx.rect(cx - 7, 168f, 6f, 4f, 0xFF3E9A3A.toInt())
                    gfx.rect(cx - 7, 160f, 4f, 10f, 0xFF3E9A3A.toInt())
                    gfx.rect(cx + 6, 172f, 6f, 4f, 0xFF3E9A3A.toInt())
                    gfx.rect(cx + 8, 164f, 4f, 10f, 0xFF3E9A3A.toInt())
                }
            }
            Theme.UNDERGROUND -> {
                gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF0B0B1E.toInt())
                val cp = cam * 0.4f
                var i = (cp / 120f).toInt() - 1
                while (i * 120f - cp < viewW + 120f) {
                    val x = i * 120f - cp
                    val y = 70f + (i * 53 % 5) * 20f
                    val c = argb(70 + (sin(time * 2 + i) * 40).toInt(), 0x7FE8FF)
                    gfx.poly(floatArrayOf(x, x + 5, x + 10, x + 5), floatArrayOf(y, y - 10, y, y + 10), c)
                    i++
                }
            }
            Theme.CASTLE -> {
                gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF1C1018.toInt())
                val wp = cam * 0.5f
                var i = (wp / 160f).toInt() - 1
                while (i * 160f - wp < viewW + 160f) {
                    val x = i * 160f - wp
                    gfx.rect(x + 40, 70f, 18f, 30f, 0xFF2E1A28.toInt())
                    gfx.oval(x + 40, 62f, 18f, 18f, 0xFF2E1A28.toInt())
                    val flick = (sin(time * 9 + i * 2) * 2)
                    gfx.rect(x + 108, 96f, 4f, 14f, 0xFF5A3A20.toInt())
                    gfx.oval(x + 105, 86f - flick, 10f, 12f + flick, 0xFFFF8A00.toInt())
                    gfx.oval(x + 107, 90f - flick, 6f, 7f, 0xFFFFE14D.toInt())
                    i++
                }
                gfx.rect(0f, 200f, viewW, 40f, 0x40FF5000)
            }
        }
    }

    /** Calls [draw] for each repeat of a parallax layer that is on screen. */
    private inline fun layer(cam: Float, factor: Float, spacing: Float, draw: (Int, Float) -> Unit) {
        val p = cam * factor
        var i = (p / spacing).toInt() - 1
        while (i * spacing - p < viewW + spacing) {
            draw(i, i * spacing - p)
            i++
        }
    }

    private fun bigCloud(gfx: Gfx, x: Float, y: Float, c: Int) {
        gfx.oval(x, y, 70f, 30f, c)
        gfx.oval(x + 30, y - 14, 60f, 40f, c)
        gfx.oval(x + 70, y - 2, 60f, 30f, c)
    }

    private fun island(gfx: Gfx, x: Float, y: Float) {
        gfx.poly(floatArrayOf(x, x + 60, x + 30), floatArrayOf(y + 8, y + 8, y + 40), 0xFF9A6A3A.toInt())
        gfx.oval(x - 4, y, 68f, 14f, 0xFF4CB531.toInt())
        gfx.oval(x + 12, y - 14, 18f, 18f, 0xFF2E7D1F.toInt())
        gfx.rect(x + 19, y - 2, 4f, 6f, 0xFF6A3A12.toInt())
    }

    private fun cloud(gfx: Gfx, x: Float, y: Float) {
        val c = 0xFFFFFFFF.toInt()
        gfx.oval(x, y + 6, 30f, 18f, c)
        gfx.oval(x + 14, y - 4, 30f, 26f, c)
        gfx.oval(x + 34, y + 4, 30f, 20f, c)
        gfx.rect(x + 10, y + 14, 46f, 10f, c)
    }

    private fun tileSprite(t: Int, tx: Int, ty: Int): Sprite? {
        val theme = level.def.theme
        return when (t) {
            T.GROUND -> when (theme) {
                Theme.OVERWORLD, Theme.SKY, Theme.NIGHT -> if (!level.solid(tx, ty - 1)) Sprites.grassTop else Sprites.dirt
                Theme.DESERT -> if (!level.solid(tx, ty - 1)) Sprites.sandTop else Sprites.sandstone
                Theme.SNOW -> if (!level.solid(tx, ty - 1)) Sprites.snowTop else Sprites.frozenDirt
                Theme.UNDERGROUND -> Sprites.hardBlue
                Theme.CASTLE -> Sprites.stone
            }
            T.BRICK -> if (theme == Theme.UNDERGROUND) Sprites.brickBlue else Sprites.brick
            T.QCOIN, T.QPOWER -> if ((time * 3).toInt() % 3 == 0) Sprites.question2 else Sprites.question1
            T.USED -> Sprites.used
            T.HARD -> when (theme) {
                Theme.UNDERGROUND -> Sprites.hardBlue
                Theme.CASTLE -> Sprites.hardGrey
                else -> Sprites.hard
            }
            T.PIPE_TL -> Sprites.pipeTL
            T.PIPE_TR -> Sprites.pipeTR
            T.PIPE_L -> Sprites.pipeL
            T.PIPE_R -> Sprites.pipeR
            T.LAVA -> if (level[tx, ty - 1] == T.LAVA) Sprites.lava else Sprites.lavaTop
            T.BRIDGE -> Sprites.bridge
            T.CLOUD -> Sprites.cloud
            else -> null
        }
    }

    private fun renderTiles(gfx: Gfx, cam: Float, firstRow: Int = 0) {
        val x0 = tileOf(cam)
        val x1 = tileOf(cam + viewW)
        val lavaFlip = (time * 2).toInt() % 2 == 0
        for (ty in firstRow until level.h) for (tx in x0..x1) {
            val t = level[tx, ty]
            if (t == T.EMPTY || tx < 0 || tx >= level.w) continue
            val s = tileSprite(t, tx, ty) ?: continue
            var dy = if (wonderMode) sin(time * 5f + tx * 0.7f) * 2f else 0f
            for (b in bumps) if (b.tx == tx && b.ty == ty) dy = -sin(b.t / 0.2f * kotlin.math.PI.toFloat()) * 5f
            gfx.sprite(s, tx * TILE - cam, ty * TILE + dy, flipX = t == T.LAVA && lavaFlip)
        }
    }

    private fun renderFlagAndCastle(gfx: Gfx, ox: Float) {
        if (flagX >= 0) {
            val px = flagX + ox
            gfx.rect(px + 7, flagTopY + 6, 2f, flagBaseY - flagTopY - 6, 0xFFB8F0A0.toInt())
            gfx.oval(px + 4, flagTopY, 8f, 8f, 0xFF2EA043.toInt())
            gfx.poly(floatArrayOf(px + 7, px - 13, px + 7), floatArrayOf(flagY, flagY + 8, flagY + 16), 0xFFFFFFFF.toInt())
            gfx.oval(px - 4, flagY + 5, 7f, 7f, 0xFFE52521.toInt())
        }
        if (castleX >= 0) {
            val x = castleX + ox
            val b = castleBase
            for (r in 1..3) for (c in 0 until 5) gfx.sprite(Sprites.brick, x + c * TILE, b - r * TILE)
            for (c in 0 until 5) gfx.rect(x + c * TILE + 2, b - 3 * TILE - 7, 10f, 7f, 0xFFC84C0C.toInt())
            for (r in 4..5) for (c in 1..3) gfx.sprite(Sprites.brick, x + c * TILE, b - r * TILE)
            for (c in 1..3) gfx.rect(x + c * TILE + 3, b - 5 * TILE - 7, 10f, 7f, 0xFFC84C0C.toInt())
            gfx.rect(x + 2 * TILE, b - 20, 16f, 20f, 0xFF101010.toInt())
            gfx.oval(x + 2 * TILE, b - 28, 16f, 16f, 0xFF101010.toInt())
            gfx.rect(x + 1.5f * TILE, b - 4.5f * TILE, 6f, 10f, 0xFF101010.toInt())
            gfx.rect(x + 3.1f * TILE, b - 4.5f * TILE, 6f, 10f, 0xFF101010.toInt())
            // Little pink pennant
            gfx.rect(x + 2.5f * TILE - 1, b - 5 * TILE - 24, 2f, 18f, 0xFF606060.toInt())
            gfx.poly(
                floatArrayOf(x + 2.5f * TILE + 1, x + 2.5f * TILE + 13, x + 2.5f * TILE + 1),
                floatArrayOf(b - 5 * TILE - 24, b - 5 * TILE - 20, b - 5 * TILE - 16), 0xFFFF6FB5.toInt(),
            )
        }
    }

    private fun renderWorld(gfx: Gfx) {
        val cam = camX
        renderBackground(gfx, cam)
        if (wonderMode) {
            for (i in 0 until 8) {
                val by = (i * 30f + time * 25f) % VIEW_H - 30f
                gfx.rect(0f, by, viewW, 30f, argb(55, rainbow(time, i / 8f)))
            }
            for (k in 0 until 14) {
                val sx = (k * 61f + time * 30f) % viewW
                val sy = (k * 37f + sin(time * 2 + k) * 30f + 120f) % VIEW_H
                gfx.text("\u2726", sx, sy, 8f, argb(200, rainbow(time, k / 14f)), 1)
            }
        }
        for (e in entities) if (e is PowerItem && !e.ready) e.draw(this, gfx, -cam)
        renderFlagAndCastle(gfx, -cam)
        renderTiles(gfx, cam)
        for (e in entities) {
            if (e is PowerItem && !e.ready) continue
            if (e.x + e.w < cam - 32 || e.x > cam + viewW + 32) continue
            e.draw(this, gfx, -cam)
        }
        if (state != State.DOOR || hero.visible) hero.draw(gfx, -cam, state == State.DYING, time)
        for (p in particles) p.draw(this, gfx, -cam)
    }

    private fun renderHud(gfx: Gfx) {
        val white = 0xFFFFFFFF.toInt()
        gfx.shadowText("ROCCO", 14f, 13f, 9f, white)
        gfx.shadowText(score.toString().padStart(6, '0'), 14f, 25f, 9f, white)
        val cx = viewW * 0.30f
        gfx.sprite(Sprites.coin, cx - 4, 10f, w = 12f, h = 12f)
        gfx.shadowText("x" + coins.toString().padStart(2, '0'), cx + 8, 20f, 9f, white)
        val wx = viewW * 0.52f
        gfx.shadowText("WORLD", wx, 13f, 9f, white, 1)
        gfx.shadowText(level.def.label, wx, 25f, 9f, white, 1)
        val lx = viewW * 0.72f
        gfx.sprite(Sprites.smallStand, lx - 6, 8f, w = 12f, h = 12f)
        gfx.shadowText("x$lives", lx + 8, 20f, 9f, white)

        val krag = entities.firstOrNull { it is Krag } as Krag?
        if (krag != null && krag.active && !krag.dying && !bossDefeated) {
            val bw = 120f
            val bx = viewW / 2 - bw / 2
            gfx.shadowText("KING KRAG", viewW / 2, 40f, 8f, 0xFFFFB0A0.toInt(), 1)
            gfx.rect(bx - 1, 43f, bw + 2, 7f, 0xFF000000.toInt())
            gfx.rect(bx, 44f, bw * krag.hp / krag.maxHp, 5f, 0xFFE53A1E.toInt())
        }
    }

    private fun renderTitle(gfx: Gfx) {
        if (level.def !== Levels.all[0]) level = Level(Levels.all[0])
        camX = 0f
        renderBackground(gfx, time * 20f)
        renderTiles(gfx, 0f, firstRow = 13)
        val cx = viewW / 2
        gfx.rect(cx - 150, 34f, 300f, 74f, 0xCC3A1A08.toInt())
        gfx.rect(cx - 147, 37f, 294f, 68f, 0xCCC84C0C.toInt())
        gfx.shadowText("ROCCO'S QUEST", cx, 72f, 28f, 0xFFFFD21F.toInt(), 1)
        gfx.shadowText("and the Blaze Blossom", cx, 96f, 11f, 0xFFFFFFFF.toInt(), 1)

        // Cast of characters standing on the ground.
        val ground = 13 * TILE.toFloat()
        val bob = if ((time * 3).toInt() % 2 == 0) 0f else 1f
        gfx.sprite(Sprites.fireStand, cx - 120, ground - 24 - bob)
        gfx.sprite(Sprites.blossom1, cx - 92, ground - 16)
        titleKrag.draw(this, gfx, cx - 18 - titleKrag.x)
        gfx.sprite(Sprites.princess, cx + 100, ground - 24, flipX = true)
        gfx.text("♥", cx + 108, ground - 30 - (time * 10) % 10, 10f, 0xFFFF6FB5.toInt(), 1)

        gfx.shadowText("Save Princess Rosalie from King Krag!", cx, 130f, 10f, 0xFFFFFFFF.toInt(), 1)
        if (wonderUnlocked) {
            gfx.rect(cx - 150, 136f, 140f, 24f, 0xCC3A1A08.toInt())
            gfx.shadowText("\u25B6 START", cx - 80, 153f, 12f, 0xFFFFFFFF.toInt(), 1)
            gfx.rect(cx + 10, 136f, 140f, 24f, argb(210, rainbow(time)))
            gfx.shadowText("\u2605 WONDER WORLD", cx + 80, 153f, 11f, 0xFFFFFFFF.toInt(), 1)
        } else if ((time * 2).toInt() % 2 == 0) {
            gfx.shadowText("TAP TO START", cx, 152f, 14f, 0xFFFFFFFF.toInt(), 1)
        }
        if (bestScore > 0) gfx.shadowText("BEST ${bestScore.toString().padStart(6, '0')}", cx, 174f, 8f, 0xFFFFD21F.toInt(), 1)
        gfx.shadowText("Left side: move (double-tap to run)   Right side: JUMP / FIRE", cx, 222f, 7f, 0xFFFFFFFF.toInt(), 1)
        gfx.shadowText("Swipe down: crouch, or butt slam in mid-air", cx, 233f, 7f, 0xFFFFFFFF.toInt(), 1)
    }

    private val titleKrag = Krag(0f, 13f * TILE).also { it.onGround = true }

    private fun renderIntro(gfx: Gfx) {
        gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF000000.toInt())
        renderHud(gfx)
        val cx = viewW / 2
        gfx.text("WORLD ${level.def.label}", cx, 96f, 16f, 0xFFFFFFFF.toInt(), 1)
        if (level.def.world == Levels.BONUS_WORLD) {
            gfx.text("BONUS: WONDER WORLD", cx, 70f, 11f, argb(255, rainbow(time)), 1)
        }
        gfx.text(level.def.name, cx, 116f, 11f, 0xFFFFD21F.toInt(), 1)
        val s = when (hero.power) {
            Power.FIRE -> Sprites.fireStand
            Power.ICE -> Sprites.iceStand
            Power.BOOM -> Sprites.boomStand
            Power.SHELL -> Sprites.shellStand
            Power.CANNON -> Sprites.cannonStand
            Power.BIG -> Sprites.bigStand
            else -> Sprites.smallStand
        }
        gfx.sprite(s, cx - 28, 150f - s.h)
        gfx.text("x  $lives", cx + 4, 146f, 12f, 0xFFFFFFFF.toInt())
        if (level.def.rows.any { 'J' in it }) {
            gfx.text("Watch the skies... Krag Jr. is coming!", cx, 196f, 9f, 0xFFFFD93D.toInt(), 1)
        }
        if (level.def.rows.any { 'K' in it }) {
            gfx.text("King Krag awaits... Use the Blaze Blossom's fire!", cx, 180f, 9f, 0xFFFF8A8A.toInt(), 1)
        }
    }

    private fun renderGameOver(gfx: Gfx) {
        gfx.rect(0f, 0f, viewW, VIEW_H, 0xCC000000.toInt())
        gfx.shadowText("GAME OVER", viewW / 2, 110f, 22f, 0xFFFFFFFF.toInt(), 1)
        gfx.shadowText("Score: $score", viewW / 2, 134f, 11f, 0xFFFFD21F.toInt(), 1)
        if (stateTime > 2f) {
            gfx.shadowText("Tap to continue from WORLD ${level.def.worldLabel}-1", viewW / 2, 160f, 10f, 0xFFFFFFFF.toInt(), 1)
        }
    }

    private fun renderWorldClear(gfx: Gfx) {
        gfx.rect(0f, 54f, viewW, 104f, 0xAA000000.toInt())
        gfx.sprite(Sprites.pip, viewW / 2 - 8, 60f)
        gfx.shadowText("THANK YOU, ROCCO!", viewW / 2, 94f, 16f, 0xFFFFD93D.toInt(), 1)
        gfx.shadowText("But Princess Rosalie isn't here...", viewW / 2, 112f, 10f, 0xFFFFFFFF.toInt(), 1)
        val where = if (level.def.world == 1) "Krag's minions took her to KRAG'S VOLCANO!"
        else "Krag fled with her to his LAST STAND!"
        gfx.shadowText(where, viewW / 2, 128f, 10f, 0xFFFFB8DC.toInt(), 1)
        if (stateTime > 2f) {
            gfx.shadowText("Tap to continue to WORLD ${level.def.world + 1}", viewW / 2, 148f, 9f, 0xFFFFFFFF.toInt(), 1)
        }
    }

    private fun renderAllClear(gfx: Gfx) {
        gfx.rect(0f, 54f, viewW, 104f, 0xAA000000.toInt())
        gfx.shadowText("WONDER WORLD CLEARED!", viewW / 2, 86f, 18f, argb(255, rainbow(time)), 1)
        gfx.shadowText("Rocco is a true superstar!", viewW / 2, 106f, 11f, 0xFFFFFFFF.toInt(), 1)
        gfx.shadowText("Final score: $score   Best: $bestScore", viewW / 2, 126f, 10f, 0xFFFFD21F.toInt(), 1)
        if (stateTime > 3f) gfx.shadowText("Tap to return to the title", viewW / 2, 146f, 9f, 0xFFFFFFFF.toInt(), 1)
    }

    private fun renderVictory(gfx: Gfx) {
        gfx.rect(0f, 54f, viewW, 100f, 0xAA000000.toInt())
        for (i in 0 until 12) {
            val t = time * 0.6f + i * 0.37f
            val x = (i * 97f + sin(t * 3) * 10f) % viewW
            val y = VIEW_H - ((t * 40f + i * 23f) % VIEW_H)
            gfx.text("♥", x, y, 12f, argb(180, 0xFF6FB5), 1)
        }
        gfx.shadowText("THANK YOU, ROCCO!", viewW / 2, 84f, 18f, 0xFFFF6FB5.toInt(), 1)
        gfx.shadowText("You saved Princess Rosalie!", viewW / 2, 104f, 11f, 0xFFFFFFFF.toInt(), 1)
        gfx.shadowText("Final score: $score", viewW / 2, 124f, 11f, 0xFFFFD21F.toInt(), 1)
        if (stateTime > 3f) {
            gfx.shadowText("WONDER WORLD unlocked! Tap for the bonus levels", viewW / 2, 144f, 9f, argb(255, rainbow(time)), 1)
        }
    }
}
