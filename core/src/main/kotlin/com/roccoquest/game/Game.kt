package com.roccoquest.game

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Rocco's Quest: the whole game simulation and its rendering, independent of Android.
 * Call [update] at a fixed 60 Hz and [render] once per frame.
 */
class Game(val input: Input, private val sound: SoundSink = SoundSink {}) {
    enum class State { TITLE, INTRO, PLAYING, DYING, FLAG, DOOR, AXE, WORLD_CLEAR, GAME_OVER, VICTORY }

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
    private var prevMusicToggle = false
    var musicOn = true
        private set
    private var currentMusic = Music.NONE
    private var heroPrevBottom = 0f

    private class Bump(val tx: Int, val ty: Int) { var t = 0f }

    fun sfx(s: Sound) = sound.play(s)
    fun spawn(e: Entity) { pending += e }
    fun puff(x: Float, y: Float) { particles += Puff(x, y) }
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

    private fun loseLife() {
        lives--
        if (lives <= 0) {
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
        if (input.musicToggle && !prevMusicToggle) musicOn = !musicOn
        prevMusicToggle = input.musicToggle
        prevJump = input.jump
        prevFire = input.fire
        prevPause = input.pause
        val tap = input.tap
        input.tap = false

        val inPlay = state == State.PLAYING || state == State.DYING || state == State.FLAG ||
            state == State.DOOR || state == State.AXE
        if (inPlay && pausePressed) paused = !paused
        if (paused) { updateMusic(); return }
        stateTime += dt

        when (state) {
            State.TITLE -> if (tap && stateTime > 0.3f) newGame()
            State.INTRO -> if (stateTime > 2.2f) setState(State.PLAYING)
            State.PLAYING -> {
                updateHero(dt, jumpPressed, firePressed)
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
                if (stateTime > 1.6f) startLevel(levelIndex + 1)
            }
            State.AXE -> updateAxe(dt)
            State.WORLD_CLEAR -> {
                updateParticles(dt)
                if (stateTime > 6f || (stateTime > 2f && tap)) startLevel(levelIndex + 1)
            }
            State.GAME_OVER -> if (stateTime > 2f && tap) newGame(worldStart())
            State.VICTORY -> {
                updateParticles(dt)
                if (stateTime > 3f && tap) { hero = Hero(0f, 0f); level = Level(Levels.all[0]); camX = 0f; setState(State.TITLE) }
            }
        }
        if (bossBannerTime > 0) bossBannerTime -= dt
        updateMusic()
    }

    private fun updateHero(dt: Float, jumpPressed: Boolean, firePressed: Boolean) {
        val h = hero
        val dir = (if (input.right) 1 else 0) - (if (input.left) 1 else 0)
        if (dir != 0) {
            val acc = if (h.onGround) 600f else 420f
            h.vx += dir * acc * dt
            if (h.onGround && h.vx * dir < 0) h.vx += dir * acc * dt // skid
            h.facing = dir
        } else {
            h.vx = approach(h.vx, 0f, (if (h.onGround) 520f else 120f) * dt)
        }
        h.vx = h.vx.coerceIn(-Hero.MAX_SPEED, Hero.MAX_SPEED)

        h.coyote = if (h.onGround) 0.1f else h.coyote - dt
        h.jumpBuffer = if (jumpPressed) 0.12f else h.jumpBuffer - dt
        if (h.jumpBuffer > 0 && h.coyote > 0) {
            h.vy = Hero.JUMP_V - abs(h.vx) * 0.15f
            h.jumpBuffer = 0f
            h.coyote = 0f
            h.onGround = false
            sfx(Sound.JUMP)
        }
        h.jumpHeld = input.jump
        val grav = if (h.vy < 0 && h.jumpHeld) 600f else 1500f
        h.vy = min(h.vy + grav * dt, 420f)

        heroPrevBottom = h.bottom
        if (level.moveX(h, h.vx * dt)) h.vx = 0f
        if (h.x < camX) { h.x = camX; if (h.vx < 0) h.vx = 0f }
        val ceilX = level.moveY(h, h.vy * dt)
        if (ceilX != null) {
            h.vy = 30f
            hitBlock(ceilX, tileOf(h.y - 1))
        }
        h.walkAnim += abs(h.vx) * dt / 7f

        if (firePressed && h.power == Power.FIRE && entities.count { it is Fireball } + pending.count { it is Fireball } < 2) {
            val fx = if (h.facing > 0) h.x + h.w else h.x - 8
            spawn(Fireball(fx, h.y + 6, h.facing))
            h.throwAnim = 0.15f
            sfx(Sound.FIRE)
        }
        h.throwAnim -= dt
        h.invuln -= dt
        h.flash -= dt

        if (level.isLavaAt(h.cx, h.bottom - 3) || h.y > level.h * TILE + 8) die()
    }

    private fun hitBlock(tx: Int, ty: Int) {
        when (level[tx, ty]) {
            T.QCOIN -> {
                level[tx, ty] = T.USED
                bumps += Bump(tx, ty)
                particles += CoinPop(tx * TILE.toFloat(), ty * TILE - 16f)
                collectCoin(tx * TILE + 8f, ty * TILE - 20f)
            }
            T.QPOWER -> {
                level[tx, ty] = T.USED
                bumps += Bump(tx, ty)
                spawn(Blossom(tx * TILE.toFloat(), ty * TILE.toFloat()))
                sfx(Sound.BUMP)
            }
            T.BRICK -> {
                if (hero.power != Power.SMALL) {
                    level[tx, ty] = T.EMPTY
                    val bx = tx * TILE.toFloat()
                    val by = ty * TILE.toFloat()
                    val c = if (level.def.theme == Theme.UNDERGROUND) 0xFF2C64B8.toInt() else 0xFFC84C0C.toInt()
                    particles += Debris(bx, by, -60f, -320f, c)
                    particles += Debris(bx + 8, by, 60f, -320f, c)
                    particles += Debris(bx, by + 8, -60f, -220f, c)
                    particles += Debris(bx + 8, by + 8, 60f, -220f, c)
                    score += 50
                    sfx(Sound.BREAK)
                } else {
                    bumps += Bump(tx, ty)
                    sfx(Sound.BUMP)
                }
            }
            else -> sfx(Sound.BUMP)
        }
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

    private fun collectCoin(x: Float, y: Float) {
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
            if (e is Enemy && e !is Krag && e.x + e.w < camX - 96) e.removed = true
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
                    if (e.stompable && h.vy > 0 && heroPrevBottom <= e.y + 6) {
                        e.stomp(this)
                        h.vy = if (input.jump) -340f else -210f
                        h.y = e.y - h.h
                    } else {
                        e.touchHero(this)
                    }
                }
                is Blossom -> if (e.ready) {
                    e.removed = true
                    if (h.power == Power.SMALL) h.power = Power.FIRE
                    h.flash = 0.6f
                    addScore(1000, e.cx, e.y)
                    sfx(Sound.POWERUP)
                }
                is Coin -> { e.removed = true; collectCoin(e.cx, e.y) }
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

    fun hurtHero() {
        val h = hero
        if (h.invuln > 0 || state != State.PLAYING) return
        if (h.power == Power.FIRE) {
            h.power = Power.SMALL
            h.invuln = 2f
            sfx(Sound.HURT)
        } else {
            die()
        }
    }

    private fun die() {
        if (state != State.PLAYING) return
        hero.power = Power.SMALL
        hero.vy = -330f
        hero.invuln = 0f
        sfx(Sound.DIE)
        setState(State.DYING)
    }

    fun onBossDefeated() {
        if (bossDefeated) return
        bossDefeated = true
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
                if (krag != null && krag.active && !bossDefeated) Music.BOSS else level.def.theme.music
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
        camX = max(camX, target).coerceIn(0f, max(0f, level.pixelW - viewW))
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
                    State.WORLD_CLEAR -> renderWorldClear(gfx)
                    else -> {
                        if (state == State.PLAYING || state == State.AXE || state == State.DYING) {
                            Controls.draw(gfx, input, viewW, hero.power == Power.FIRE)
                        }
                        Controls.drawPause(gfx, viewW, musicOn)
                    }
                }
                if (bossBannerTime > 0) {
                    gfx.shadowText("KING KRAG IS DEFEATED!", viewW / 2, 80f, 14f, 0xFFFFD21F.toInt(), 1)
                    gfx.shadowText("Go to Princess Rosalie!", viewW / 2, 98f, 10f, 0xFFFFB8DC.toInt(), 1)
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
            var dy = 0f
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
        for (e in entities) if (e is Blossom && !e.ready) e.draw(this, gfx, -cam)
        renderFlagAndCastle(gfx, -cam)
        renderTiles(gfx, cam)
        for (e in entities) {
            if (e is Blossom && !e.ready) continue
            if (e.x + e.w < cam - 32 || e.x > cam + viewW + 32) continue
            e.draw(this, gfx, -cam)
        }
        if (state != State.DOOR || hero.visible) hero.draw(gfx, -cam, state == State.DYING)
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
        gfx.shadowText("${level.def.world}-${level.def.num}", wx, 25f, 9f, white, 1)
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
        if ((time * 2).toInt() % 2 == 0) gfx.shadowText("TAP TO START", cx, 152f, 14f, 0xFFFFFFFF.toInt(), 1)
        gfx.shadowText("Left side: move   Right side: JUMP / FIRE", cx, 232f, 8f, 0xFFFFFFFF.toInt(), 1)
    }

    private val titleKrag = Krag(0f, 13f * TILE).also { it.onGround = true }

    private fun renderIntro(gfx: Gfx) {
        gfx.rect(0f, 0f, viewW, VIEW_H, 0xFF000000.toInt())
        renderHud(gfx)
        val cx = viewW / 2
        gfx.text("WORLD ${level.def.world}-${level.def.num}", cx, 96f, 16f, 0xFFFFFFFF.toInt(), 1)
        gfx.text(level.def.name, cx, 116f, 11f, 0xFFFFD21F.toInt(), 1)
        val s = if (hero.power == Power.FIRE) Sprites.fireStand else Sprites.smallStand
        gfx.sprite(s, cx - 28, 150f - s.h)
        gfx.text("x  $lives", cx + 4, 146f, 12f, 0xFFFFFFFF.toInt())
        if (level.def.rows.any { 'K' in it }) {
            gfx.text("King Krag awaits... Use the Blaze Blossom's fire!", cx, 180f, 9f, 0xFFFF8A8A.toInt(), 1)
        }
    }

    private fun renderGameOver(gfx: Gfx) {
        gfx.rect(0f, 0f, viewW, VIEW_H, 0xCC000000.toInt())
        gfx.shadowText("GAME OVER", viewW / 2, 110f, 22f, 0xFFFFFFFF.toInt(), 1)
        gfx.shadowText("Score: $score", viewW / 2, 134f, 11f, 0xFFFFD21F.toInt(), 1)
        if (stateTime > 2f) {
            gfx.shadowText("Tap to continue from WORLD ${level.def.world}-1", viewW / 2, 160f, 10f, 0xFFFFFFFF.toInt(), 1)
        }
    }

    private fun renderWorldClear(gfx: Gfx) {
        gfx.rect(0f, 54f, viewW, 104f, 0xAA000000.toInt())
        gfx.sprite(Sprites.pip, viewW / 2 - 8, 60f)
        gfx.shadowText("THANK YOU, ROCCO!", viewW / 2, 94f, 16f, 0xFFFFD93D.toInt(), 1)
        gfx.shadowText("But Princess Rosalie isn't here...", viewW / 2, 112f, 10f, 0xFFFFFFFF.toInt(), 1)
        gfx.shadowText("Krag's minions took her to KRAG'S VOLCANO!", viewW / 2, 128f, 10f, 0xFFFFB8DC.toInt(), 1)
        if (stateTime > 2f) gfx.shadowText("Tap to continue to WORLD 2", viewW / 2, 148f, 9f, 0xFFFFFFFF.toInt(), 1)
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
        if (stateTime > 3f) gfx.shadowText("Tap to play again", viewW / 2, 144f, 9f, 0xFFFFFFFF.toInt(), 1)
    }
}
