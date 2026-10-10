package com.critters.game

import kotlin.random.Random

/** Where the save file lives. The platform supplies a real one; tests use [Memory]. */
interface SaveStore {
    fun read(): String?
    fun write(data: String)

    class Memory : SaveStore {
        var data: String? = null
        override fun read() = data
        override fun write(data: String) { this.data = data }
    }
}

/** An NPC on the current map, with its own position and walking state. */
class Npc(val def: NpcDef) {
    var x = def.x
    var y = def.y
    var face = def.face
    var moveT = 16
    var moveDir = Dir.DOWN
    val path = ArrayDeque<Dir>()
    var wanderIn = 60 + Random.nextInt(120)
    var bubble = 0
    var step = 0

    val moving get() = moveT < 16
    val id get() = def.id
}

class Game(
    val pad: Pad = Pad(),
    val audio: Audio = Audio.Silent,
    val store: SaveStore = SaveStore.Memory(),
    val rng: Random = Random,
) {
    enum class Scene { TITLE, INTRO, WORLD, BATTLE, EVOLVE, CREDITS }

    val screen = Screen()
    var tick = 0
        private set
    var scene = Scene.TITLE
        private set

    // ---------------------------------------------------------------- the player's progress
    var playerName = "ALEX"
    var rivalStarter = 3
    val party = mutableListOf<Critter>()
    val box = mutableListOf<Critter>()
    val bag = Bag()
    var money = 3000
    val flags = HashSet<String>()
    val seen = HashSet<Int>()
    val caught = HashSet<Int>()
    var musicOn = true
        private set
    var playFrames = 0L
    val badges get() = listOf("badge1", "badge2", "badge3").count { it in flags }

    fun dexCaught() = caught.size

    // ---------------------------------------------------------------- the overworld
    var map: MapDef = World["home"]
        private set
    var px = 3
        private set
    var py = 5
        private set
    var face = Dir.UP
        private set
    private var moveT = 16
    private var moveDir = Dir.DOWN
    private var jumping = false
    private var stepCount = 0
    private var turnHold = 0
    private var bumpCool = 0
    private var mapLabel = 0
    val npcs = ArrayList<Npc>()
    private val returnStack = ArrayList<Triple<String, Int, Int>>()
    private var respawn = Respawn("home", 3, 5, listOf(Triple("sprout", 4, 5)))
    private var currentTune = Tune.NONE

    class Respawn(val map: String, val x: Int, val y: Int, val back: List<Triple<String, Int, Int>>)

    val moving get() = moveT < 16

    // ---------------------------------------------------------------- overlays, scripts and scenes
    val ui = ArrayList<Widget>()
    private var battle: BattleScene? = null
    private var evolve: EvolveScene? = null
    private val evolveQueue = ArrayDeque<Critter>()
    private var afterEvolve: (() -> Unit)? = null
    private var trans: Transition? = null
    private var creditsT = 0

    private var scriptIt: Iterator<Cmd>? = null
    private var ctx: Ctx? = null
    private var waiting = false
    private var waitFrames = 0
    private var waitWalk = false
    private var scriptDone: (() -> Unit)? = null
    val scriptRunning get() = scriptIt != null

    init {
        playMusic(Tune.TITLE)
    }

    // ================================================================ input

    private var prevBits = 0
    private var curBits = 0
    private val held = IntArray(Btn.entries.size)

    fun pressed(b: Btn) = curBits and b.bit != 0 && prevBits and b.bit == 0
    fun down(b: Btn) = curBits and b.bit != 0

    /** True on a fresh press, then again every few frames while held (for menus). */
    fun repeat(b: Btn): Boolean {
        val h = held[b.ordinal]
        return h == 1 || (h > 18 && (h - 18) % 6 == 0)
    }

    private fun readInput() {
        prevBits = curBits
        curBits = pad.bits
        for (b in Btn.entries) held[b.ordinal] = if (curBits and b.bit != 0) held[b.ordinal] + 1 else 0
    }

    /** Forget held buttons so one press doesn't count twice when a screen changes. */
    fun swallowInput() {
        prevBits = curBits
        for (b in Btn.entries) if (held[b.ordinal] > 0) held[b.ordinal] = 99
    }

    // ================================================================ audio helpers

    fun playMusic(t: Tune) {
        currentTune = t
        audio.music(if (musicOn) t else Tune.NONE)
    }

    fun toggleMusic() {
        musicOn = !musicOn
        audio.music(if (musicOn) currentTune else Tune.NONE)
    }

    fun sfx(s: Sfx) = audio.sfx(s)

    fun text(s: String) = s.replace("{P}", playerName)

    // ================================================================ main loop

    fun update() {
        tick++
        readInput()
        if (pressed(Btn.SELECT)) toggleMusic()
        if (scene == Scene.WORLD || scene == Scene.BATTLE) playFrames++
        trans?.let {
            it.update()
            if (it.done) trans = null
            return
        }
        if (ui.isNotEmpty()) {
            val top = ui.last()
            top.update(this)
            ui.removeAll { it.done }
            return
        }
        when (scene) {
            Scene.TITLE -> updateTitle()
            Scene.INTRO -> {}
            Scene.WORLD -> updateWorld()
            Scene.BATTLE -> battle?.update()
            Scene.EVOLVE -> evolve?.update()
            Scene.CREDITS -> updateCredits()
        }
    }

    fun render() {
        when (scene) {
            Scene.TITLE -> drawTitle()
            Scene.INTRO -> drawIntro()
            Scene.WORLD -> drawWorld()
            Scene.BATTLE -> battle?.draw(screen)
            Scene.EVOLVE -> evolve?.draw(screen)
            Scene.CREDITS -> drawCredits()
        }
        for (w in ui) w.draw(this, screen)
        trans?.draw(screen)
    }

    /** Draws the whole console: the game screen inside the handheld shell. */
    fun draw(d: Draw, viewW: Float, viewH: Float) {
        render()
        Shell.draw(d, screen.px, pad.bits, viewW, viewH, musicOn)
    }

    fun push(w: Widget) {
        ui += w
        swallowInput()
    }

    // ================================================================ title screen and new game

    private var titleSpecies = 1

    private fun updateTitle() {
        if (tick % 180 == 0) {
            titleSpecies = listOf(1, 3, 5, 11, 7, 17, 21, 19, 13, 22)[(tick / 180) % 10]
        }
        if (pressed(Btn.A) || pressed(Btn.START)) {
            sfx(Sfx.SELECT)
            val hasSave = store.read() != null
            if (hasSave) {
                push(Menu(listOf("CONTINUE", "NEW GAME"), x = 48, y = 88, w = 72, cancelable = true) { i ->
                    if (i == 0) continueGame() else if (i == 1) newGame()
                })
            } else {
                newGame()
            }
        }
    }

    private fun drawTitle() {
        val s = screen
        s.fill(0xFF1838A0.toInt())
        for (i in 0 until 24) {
            val y = (i * 37 + tick / 2) % SH
            val x = (i * 53) % SW
            s.rect(x, y, 1, 1, 0xFFB8D0FF.toInt())
        }
        val colors = intArrayOf(0xFFF85858.toInt(), 0xFFF8A830.toInt(), 0xFFF8E040.toInt(), 0xFF58D058.toInt(), 0xFF58A8F8.toInt(), 0xFFC878F0.toInt())
        val title = "POCKET"
        for ((i, ch) in title.withIndex()) {
            val x = 44 + i * 12
            val y = 12 + if ((tick / 8 + i) % 12 == 0) -1 else 0
            bigChar(ch, x, y, colors[i % colors.size])
        }
        val t2 = "CRITTERS"
        for ((i, ch) in t2.withIndex()) bigChar(ch, 32 + i * 12, 30, 0xFFF8F8F0.toInt())
        s.rect(52, 50, 56, 11, 0xFF101828.toInt())
        "COLOR".forEachIndexed { i, ch -> Font.draw(s, ch, 65 + i * 6, 52, colors[(i + tick / 10) % colors.size]) }
        val bob = if (tick / 20 % 2 == 0) 0 else 1
        s.blit(CritterArt.front(titleSpecies), 56, 64 + bob)
        if (tick / 30 % 2 == 0) s.textCenter("PRESS START", 80, 120, PAPER)
        s.textCenter("(C) 2026 CRITTER CO.", 80, 134, 0xFF98A8D8.toInt())
    }

    /** Draws a letter at double size. */
    private fun bigChar(ch: Char, x: Int, y: Int, c: Int) {
        val tmp = Screen()
        Font.draw(tmp, ch, 0, 0, 1)
        for (yy in 0 until 7) for (xx in 0 until 5) {
            if (tmp.px[yy * SW + xx] == 1) {
                screen.rect(x + xx * 2 + 1, y + yy * 2 + 1, 2, 2, INK)
                screen.rect(x + xx * 2, y + yy * 2, 2, 2, c)
            }
        }
    }

    private fun newGame() {
        resetProgress()
        scene = Scene.INTRO
        playMusic(Tune.TOWN)
        runScript(script { c ->
            say(
                "HELLO THERE! WELCOME TO THE WORLD OF CRITTERS!",
                "MY NAME IS HAZEL. PEOPLE CALL ME THE CRITTER PROF!",
                "THIS WORLD IS FULL OF CREATURES CALLED CRITTERS. SOME PEOPLE KEEP THEM AS PETS. OTHERS BATTLE WITH THEM.",
                "AND YOU ARE...?",
            )
            yield(Cmd.Choose("WHAT IS YOUR NAME?", NAMES))
            c.game.playerName = NAMES[c.choice.coerceAtLeast(0)]
            say(
                "RIGHT! SO YOUR NAME IS {P}!",
                "THIS IS MY GRANDSON, ${World.RIVAL}. HE'S BEEN YOUR RIVAL SINCE YOU WERE BOTH LITTLE.",
                "{P}! YOUR VERY OWN CRITTER ADVENTURE IS ABOUT TO UNFOLD!",
                "A WORLD OF DREAMS AND ADVENTURES WITH CRITTERS AWAITS. LET'S GO!",
            )
        }, null) {
            transition {
                scene = Scene.WORLD
                loadMap("home", 4, 6, Dir.UP)
                returnStack.clear()
                returnStack += Triple("sprout", 4, 5)
            }
        }
    }

    private fun drawIntro() {
        screen.fill(PAPER)
        screen.blitScaled(Art.person(Art.Look.PROF).down, 24, 24, 48, 48)
        screen.blit(CritterArt.front(listOf(1, 3, 5)[tick / 150 % 3]), 92, 26)
    }

    private fun resetProgress() {
        party.clear(); box.clear(); bag.items.clear(); flags.clear(); seen.clear(); caught.clear()
        money = 3000
        playFrames = 0
        bag.add(Item.POTION, 1)
        respawn = Respawn("home", 3, 5, listOf(Triple("sprout", 4, 5)))
    }

    // ================================================================ map loading and transitions

    fun loadMap(id: String, x: Int, y: Int, f: Dir) {
        val old = map
        map = World[id]
        px = x; py = y; face = f
        moveT = 16
        jumping = false
        refreshNpcs(reset = true)
        if (map.tune != currentTune || old === map) playMusic(map.tune)
        mapLabel = if (!map.indoor && map.id != old.id) 120 else if (map.indoor) 0 else mapLabel
    }

    /** Rebuilds the NPC list from the map's definitions and the current flags. */
    fun refreshNpcs(reset: Boolean = false) {
        val keep = if (reset) emptyMap() else npcs.associateBy { it.id }
        npcs.clear()
        for (d in map.npcs) {
            if (!d.show(this)) continue
            npcs += keep[d.id] ?: Npc(d)
        }
    }

    private class Transition(val frames: Int, val mid: () -> Unit, val white: Boolean = false, val flashes: Boolean = false) {
        var t = 0
        val done get() = t >= frames * 2

        fun update() {
            t++
            if (t == frames) mid()
        }

        fun draw(s: Screen) {
            if (flashes) {
                // Battle intro: three flashes, then the screen closes in from both sides.
                if (t < frames) {
                    if ((t / 5) % 2 == 0) s.fade(0.85f, white = (t / 10) % 2 == 0)
                    if (t > frames * 6 / 10) {
                        val k = (t - frames * 6 / 10) * (SW / 2) / (frames * 4 / 10)
                        for (row in 0 until SH step 8) {
                            val dir = if ((row / 8) % 2 == 0) 1 else -1
                            if (dir > 0) s.rect(0, row, k * 2, 8, INK) else s.rect(SW - k * 2, row, k * 2, 8, INK)
                        }
                    }
                } else {
                    s.fade(1f - (t - frames).toFloat() / frames)
                }
                return
            }
            val k = if (t < frames) t.toFloat() / frames else 1f - (t - frames).toFloat() / frames
            s.fade(k, white)
        }
    }

    fun transition(frames: Int = 12, white: Boolean = false, mid: () -> Unit) {
        trans = Transition(frames, mid, white)
    }

    // ================================================================ the overworld

    private fun solidAt(x: Int, y: Int): Boolean {
        val ch = map.at(x, y)
        return ch in Art.SOLID || (ch == 'L')
    }

    fun npcAt(x: Int, y: Int): Npc? = npcs.firstOrNull { it.x == x && it.y == y }

    private fun updateWorld() {
        if (mapLabel > 0) mapLabel--
        if (bumpCool > 0) bumpCool--
        for (n in npcs) updateNpc(n)
        for (n in npcs) if (n.bubble > 0) n.bubble--

        if (moving) {
            moveT += if (jumping) 1 else if (down(Btn.B) && scriptIt == null) 2 else 1
            if (moveT >= 16) {
                moveT = 16
                if (jumping) {
                    jumping = false
                    // A ledge jump covers two tiles: the ledge and the one below.
                    if (!solidAt(px + moveDir.dx, py + moveDir.dy) && npcAt(px + moveDir.dx, py + moveDir.dy) == null) {
                        px += moveDir.dx; py += moveDir.dy
                        moveT = 0
                        jumpLanding = true
                        return
                    }
                }
                jumpLanding = false
                if (scriptIt == null) arrived() else if (waitWalk && playerPath.isEmpty() && npcs.none { it.moving || it.path.isNotEmpty() }) {
                    waitWalk = false; resume()
                } else if (playerPath.isNotEmpty()) startStep(playerPath.removeFirst(), scripted = true)
            }
            return
        }

        if (scriptIt != null) {
            if (playerPath.isNotEmpty()) {
                startStep(playerPath.removeFirst(), scripted = true)
                return
            }
            if (waitFrames > 0) {
                waitFrames--
                if (waitFrames == 0) resume()
                return
            }
            if (waitWalk && npcs.none { it.moving || it.path.isNotEmpty() }) {
                waitWalk = false
                resume()
            }
            return
        }

        if (pressed(Btn.START)) {
            sfx(Sfx.SELECT)
            openStartMenu()
            return
        }
        if (pressed(Btn.A)) {
            interact()
            return
        }
        val d = heldDir()
        if (d == null) {
            turnHold = 0
            return
        }
        if (d != face && held[btnOf(d).ordinal] < 6) {
            face = d
            turnHold = 1
            return
        }
        face = d
        tryMove(d)
    }

    private var jumpLanding = false
    private val playerPath = ArrayDeque<Dir>()

    private fun btnOf(d: Dir) = when (d) { Dir.UP -> Btn.UP; Dir.DOWN -> Btn.DOWN; Dir.LEFT -> Btn.LEFT; Dir.RIGHT -> Btn.RIGHT }

    private fun heldDir(): Dir? {
        // The most recently pressed direction wins.
        var best: Dir? = null
        var bestHeld = Int.MAX_VALUE
        for (d in Dir.entries) {
            val h = held[btnOf(d).ordinal]
            if (h in 1 until bestHeld) { best = d; bestHeld = h }
        }
        return best
    }

    private fun tryMove(d: Dir) {
        val here = map.at(px, py)
        if (here == 'E' && d == Dir.DOWN) {
            map.warps.firstOrNull { it.x == px && it.y == py }?.let { doWarp(it); return }
        }
        val nx = px + d.dx
        val ny = py + d.dy
        if (nx !in 0 until map.w || ny !in 0 until map.h) {
            val link = map.link(d)
            if (link != null) {
                edgeExit(d, link)
                return
            }
            bump()
            return
        }
        val ch = map.at(nx, ny)
        if (ch == 'L') {
            if (d == Dir.DOWN) {
                startStep(d)
                jumping = true
                sfx(Sfx.LEDGE)
            } else {
                bump()
            }
            return
        }
        if (solidAt(nx, ny) || npcAt(nx, ny) != null) {
            bump()
            return
        }
        startStep(d)
    }

    private fun startStep(d: Dir, scripted: Boolean = false) {
        face = d
        moveDir = d
        px += d.dx
        py += d.dy
        moveT = 0
        stepCount++
        if (scripted) jumping = false
    }

    private fun bump() {
        if (bumpCool == 0) {
            sfx(Sfx.BUMP)
            bumpCool = 18
        }
    }

    private fun edgeExit(d: Dir, link: Link) {
        val target = World[link.map]
        var nx = px
        var ny = py
        when (d) {
            Dir.UP -> { ny = target.h - 1; nx = px + link.offset }
            Dir.DOWN -> { ny = 0; nx = px + link.offset }
            Dir.LEFT -> { nx = target.w - 1; ny = py + link.offset }
            Dir.RIGHT -> { nx = 0; ny = py + link.offset }
        }
        transition(8) { loadMap(link.map, nx, ny, d) }
    }

    private fun doWarp(w: Warp) {
        sfx(Sfx.DOOR)
        if (w.map == "BACK") {
            val back = returnStack.removeLastOrNull() ?: Triple("sprout", 4, 5)
            transition { loadMap(back.first, back.second, back.third, Dir.DOWN) }
            return
        }
        val target = World[w.map]
        if (target.warps.any { it.map == "BACK" }) returnStack += Triple(map.id, px - moveDir.dx, py - moveDir.dy)
        transition { loadMap(w.map, w.tx, w.ty, w.face) }
    }

    /** Called when the player finishes a step on their own. */
    private fun arrived() {
        val ch = map.at(px, py)
        if (ch in Art.WARPS && !(ch == 'E' && moveDir != Dir.DOWN)) {
            map.warps.firstOrNull { it.x == px && it.y == py }?.let { doWarp(it); return }
        }
        for (t in map.triggers) {
            if (t.x == px && t.y == py && t.active(this)) {
                runScript(t.script, null)
                return
            }
        }
        if (checkTrainers()) return
        val grassy = ch == ',' || (map.cave && ch == 'k')
        if (grassy && map.wild.isNotEmpty() && party.any { !it.fainted } && rng.nextInt(10) == 0) {
            val total = map.wild.sumOf { it.weight }
            var r = rng.nextInt(total)
            val w = map.wild.first { r -= it.weight; r < 0 }
            val level = w.min + rng.nextInt(w.max - w.min + 1)
            startBattle(listOf(Critter(Dex[w.species], level, rng)), null, Tune.BATTLE, false) {}
        }
    }

    private fun beatFlag(n: Npc) = "beat_${map.id}_${n.id}"

    private fun checkTrainers(): Boolean {
        for (n in npcs) {
            if (n.def.sight <= 0 || beatFlag(n) in flags) continue
            if (n.def.trainer == null && n.def.spot == null) continue
            val d = n.face
            for (k in 1..n.def.sight) {
                val tx = n.x + d.dx * k
                val ty = n.y + d.dy * k
                if (tx == px && ty == py) {
                    runScript(trainerScript(n, spotted = true), n.id)
                    return true
                }
                if (solidAt(tx, ty) || npcAt(tx, ty) != null) break
            }
        }
        return false
    }

    private fun trainerScript(n: Npc, spotted: Boolean): Script {
        val flag = beatFlag(n)
        val dist = maxOf(kotlin.math.abs(n.x - px), kotlin.math.abs(n.y - py))
        val walk = n.face.let { d -> listOf(Dir.UP to "U", Dir.DOWN to "D", Dir.LEFT to "L", Dir.RIGHT to "R").first { it.first == d }.second }
        val spot = n.def.spot
        val t = n.def.trainer
        return script { c ->
            if (spotted) {
                face = n.face.opposite
                yield(Cmd.Exclaim(n.id))
                if (dist > 1) yield(Cmd.Walk(n.id, walk.repeat(dist - 1)))
            }
            yield(Cmd.FacePlayer(n.id))
            if (spot != null) {
                spot(c)
            } else if (t != null) {
                if (n.def.intro.isNotEmpty()) say(n.def.intro)
                yield(Cmd.Fight(t))
            }
            yield(Cmd.SetFlag(flag))
        }
    }

    private fun updateNpc(n: Npc) {
        if (n.moving) {
            n.moveT += 1
            return
        }
        if (n.path.isNotEmpty()) {
            val d = n.path.removeFirst()
            n.face = d
            n.moveDir = d
            n.x += d.dx
            n.y += d.dy
            n.moveT = 0
            n.step++
            return
        }
        if (!n.def.wander || scriptIt != null || ui.isNotEmpty()) return
        if (--n.wanderIn > 0) return
        n.wanderIn = 90 + rng.nextInt(150)
        val d = Dir.entries[rng.nextInt(4)]
        n.face = d
        val nx = n.x + d.dx
        val ny = n.y + d.dy
        // Wanderers stay within two tiles of home and never step on the player.
        if (kotlin.math.abs(nx - n.def.x) > 2 || kotlin.math.abs(ny - n.def.y) > 2) return
        if (nx !in 0 until map.w || ny !in 0 until map.h) return
        if (solidAt(nx, ny) || map.at(nx, ny) in Art.WARPS || npcAt(nx, ny) != null) return
        if ((nx == px && ny == py) || (moving && nx == px && ny == py)) return
        n.path += d
    }

    private fun interact() {
        val tx = px + face.dx
        val ty = py + face.dy
        var n = npcAt(tx, ty)
        if (n == null && map.at(tx, ty) == 'T') n = npcAt(tx + face.dx, ty + face.dy)
        if (n != null) {
            talkTo(n)
            return
        }
        map.signs[tx to ty]?.let { sign ->
            runScript(script { say(sign) }, null)
            return
        }
        when (map.at(tx, ty)) {
            'P' -> if (map.id == "center") runScript(pcScript, null) else runScript(script { say("IT'S A PC. IT'S HUMMING QUIETLY.") }, null)
            'B' -> runScript(script { say("IT'S CRAMMED FULL OF BOOKS ABOUT CRITTERS.") }, null)
            'b' -> runScript(script { say("A COMFY-LOOKING BED.") }, null)
            'Q' -> runScript(script { say("A CRITTER STATUE. THE PLAQUE READS: GYM LEADER CHAMPIONS. THERE'S ROOM FOR MORE NAMES...") }, null)
            '~' -> if (map.indoor) {} else runScript(script { say("THE WATER IS DEEP AND BLUE.") }, null)
            else -> {}
        }
    }

    private fun talkTo(n: Npc) {
        if (n.def.look != Art.Look.BALL && n.def.look != Art.Look.SOLARIS) n.face = face.opposite
        val d = n.def
        val beaten = beatFlag(n) in flags
        when {
            d.trainer != null && !beaten -> runScript(trainerScript(n, spotted = false), n.id)
            d.trainer != null -> runScript(script { say(d.after) }, n.id)
            d.spot != null && !beaten && d.sight > 0 -> runScript(trainerScript(n, spotted = false), n.id)
            d.talk != null -> runScript(d.talk, n.id)
            d.text.isNotEmpty() -> runScript(script { say(d.text) }, n.id)
        }
    }

    private val pcScript: Script = script { c ->
        val g = c.game
        say("{P} TURNED ON THE PC.")
        while (true) {
            yield(Cmd.Choose("WHAT WOULD YOU LIKE TO DO?", listOf("WITHDRAW", "DEPOSIT", "LOG OFF")))
            when (c.choice) {
                0 -> when {
                    g.box.isEmpty() -> say("THE BOX IS EMPTY.")
                    g.party.size >= 6 -> say("YOUR PARTY IS FULL!")
                    else -> {
                        yield(Cmd.Choose("WITHDRAW WHICH CRITTER?", g.box.map { "${it.name} :L${it.level}" }))
                        if (c.choice >= 0) {
                            val m = g.box.removeAt(c.choice)
                            g.party += m
                            say("${m.name} IS NOW IN YOUR PARTY.")
                        }
                    }
                }
                1 -> if (g.party.size <= 1) say("YOU CAN'T DEPOSIT YOUR LAST CRITTER!") else {
                    yield(Cmd.Choose("DEPOSIT WHICH CRITTER?", g.party.map { "${it.name} :L${it.level}" }))
                    if (c.choice >= 0) {
                        val m = g.party.removeAt(c.choice)
                        m.heal()
                        g.box += m
                        say("${m.name} WAS STORED IN THE BOX.")
                    }
                }
                else -> break
            }
        }
    }

    // ================================================================ scripts

    fun runScript(s: Script, npc: String?, onDone: (() -> Unit)? = null) {
        val c = Ctx(this, npc)
        ctx = c
        scriptIt = iterator { s(c) }
        scriptDone = onDone
        waiting = false
        advance()
    }

    private fun abortScript() {
        scriptIt = null
        waiting = false
        waitWalk = false
        waitFrames = 0
        playerPath.clear()
        scriptDone = null
    }

    fun resume() {
        waiting = false
        advance()
    }

    private fun advance() {
        while (true) {
            val it = scriptIt ?: return
            if (waiting) return
            if (!it.hasNext()) {
                scriptIt = null
                val done = scriptDone
                scriptDone = null
                done?.invoke()
                return
            }
            exec(it.next())
        }
    }

    private fun npcById(id: String) = npcs.firstOrNull { it.id == id }

    private fun exec(cmd: Cmd) {
        val c = ctx!!
        when (cmd) {
            is Cmd.Say -> {
                waiting = true
                push(Dialog(text(cmd.text)) { resume() })
            }
            is Cmd.Ask -> {
                waiting = true
                push(Dialog(text(cmd.text), ask = true) { c.yes = it; resume() })
            }
            is Cmd.Choose -> {
                waiting = true
                val d = Dialog(text(cmd.text), hold = true) {}
                push(d)
                val h = minOf(cmd.options.size, 5) * 12 + 12
                push(Menu(cmd.options, x = 160 - (cmd.options.maxOf { it.length } * 6 + 22).coerceAtMost(152), y = (96 - h).coerceAtLeast(0), maxRows = 5, cancelable = true) { i ->
                    d.done = true
                    c.choice = i
                    resume()
                })
            }
            is Cmd.Fight -> {
                waiting = true
                val foes = cmd.trainer.party.map { (sp, lv) -> Critter(Dex[sp], lv, rng).also { it.iv = 8 } }
                startBattle(foes, cmd.trainer, cmd.tune, cmd.canLose) { r ->
                    c.won = r == Battle.Result.WIN
                    resume()
                }
            }
            is Cmd.Wild -> {
                waiting = true
                startBattle(listOf(Critter(Dex[cmd.species], cmd.level, rng)), null, Tune.BOSS, false) { r ->
                    c.choice = r.ordinal
                    c.won = r == Battle.Result.WIN || r == Battle.Result.CAUGHT
                    resume()
                }
            }
            Cmd.Heal -> {
                party.forEach { it.heal() }
                sfx(Sfx.HEAL)
                waiting = true
                waitFrames = 110
            }
            is Cmd.GiveItem -> {
                bag.add(cmd.item, cmd.count)
                sfx(Sfx.ITEM)
                waiting = true
                val what = if (cmd.count > 1) "${cmd.count} ${cmd.item.label}S" else "A ${cmd.item.label}"
                push(Dialog(text("{P} GOT $what!")) { resume() })
            }
            is Cmd.GiveCritter -> {
                val m = Critter(Dex[cmd.species], cmd.level, rng)
                seen += cmd.species
                caught += cmd.species
                if (party.size < 6) party += m else box += m
                sfx(Sfx.CAUGHT)
                waiting = true
                push(Dialog(text("{P} RECEIVED ${m.name}!")) { resume() })
            }
            is Cmd.Shop -> {
                waiting = true
                push(ShopScreen(cmd.items) { resume() })
            }
            is Cmd.Walk -> {
                val dirs = cmd.path.map { ch -> when (ch) { 'U' -> Dir.UP; 'D' -> Dir.DOWN; 'L' -> Dir.LEFT; else -> Dir.RIGHT } }
                if (cmd.npc.isEmpty()) playerPath.addAll(dirs) else npcById(cmd.npc)?.path?.addAll(dirs)
                waiting = true
                waitWalk = true
            }
            is Cmd.Face -> npcById(cmd.npc)?.face = cmd.dir
            is Cmd.FacePlayer -> npcById(cmd.npc)?.let { n ->
                n.face = when {
                    px > n.x -> Dir.RIGHT
                    px < n.x -> Dir.LEFT
                    py < n.y -> Dir.UP
                    else -> Dir.DOWN
                }
                face = n.face.opposite
            }
            is Cmd.Exclaim -> {
                npcById(cmd.npc)?.bubble = 40
                sfx(Sfx.EXCLAIM)
                waiting = true
                waitFrames = 40
            }
            is Cmd.Music -> playMusic(cmd.tune)
            is Cmd.Sound -> sfx(cmd.sfx)
            is Cmd.Wait -> {
                waiting = true
                waitFrames = cmd.frames
            }
            is Cmd.SetFlag -> flags += cmd.flag
            Cmd.Refresh -> refreshNpcs()
            is Cmd.Warp -> {
                waiting = true
                transition { loadMap(cmd.map, cmd.x, cmd.y, cmd.face); resume() }
            }
            Cmd.Credits -> {
                waiting = true
                transition(30) {
                    scene = Scene.CREDITS
                    creditsT = 0
                    playMusic(Tune.ENDING)
                }
            }
        }
    }

    // ================================================================ battles

    private fun startBattle(foes: List<Critter>, trainer: Trainer?, tune: Tune, canLose: Boolean, onEnd: (Battle.Result) -> Unit) {
        playMusic(tune)
        for (f in foes.take(1)) seen += f.species.id
        trans = Transition(50, {
            scene = Scene.BATTLE
            battle = BattleScene(this, Battle(party, foes, trainer, bag, rng)) { b -> endBattle(b, canLose, onEnd) }
        }, flashes = true)
    }

    private fun endBattle(b: Battle, canLose: Boolean, onEnd: (Battle.Result) -> Unit) {
        val r = b.result ?: Battle.Result.RUN
        if (r == Battle.Result.WIN) money += b.prizeMoney
        val got = b.caught
        val messages = ArrayList<String>()
        if (got != null) {
            caught += got.species.id
            seen += got.species.id
            if (party.size < 6) party += got else {
                box += got
                messages += "${got.name} WAS SENT TO THE BOX ON THE CRITTER CENTER PC."
            }
        }
        if (r == Battle.Result.LOSE && !canLose) {
            blackout()
            return
        }
        for (p in party) if (!p.fainted && p.canEvolve()) evolveQueue += p
        val back = {
            transition {
                battle = null
                scene = Scene.WORLD
                playMusic(map.tune)
            }
            if (messages.isNotEmpty()) {
                push(Dialog(messages.joinToString(" ")) { onEnd(r) })
            } else {
                onEnd(r)
            }
        }
        if (evolveQueue.isNotEmpty()) {
            afterEvolve = back
            transition { nextEvolution() }
        } else {
            back()
        }
    }

    private fun nextEvolution() {
        val c = evolveQueue.removeFirstOrNull()
        if (c == null) {
            evolve = null
            val a = afterEvolve
            afterEvolve = null
            a?.invoke()
            return
        }
        scene = Scene.EVOLVE
        playMusic(Tune.NONE)
        evolve = EvolveScene(this, c) {
            caught += c.species.id
            seen += c.species.id
            nextEvolution()
        }
    }

    private fun blackout() {
        money /= 2
        party.forEach { it.heal() }
        abortScript()
        trans = Transition(30, {
            battle = null
            scene = Scene.WORLD
            returnStack.clear()
            returnStack += respawn.back
            loadMap(respawn.map, respawn.x, respawn.y, Dir.DOWN)
            push(Dialog(text("{P} HURRIED BACK TO SAFETY, PROTECTING THE EXHAUSTED CRITTERS FROM FURTHER HARM...")) {})
        })
    }

    fun setRespawnHere() {
        respawn = Respawn(map.id, px, py, returnStack.toList())
    }

    // ================================================================ learning moves (shared by battles and evolution)

    fun learnFlow(c: Critter, m: Move, done: () -> Unit) {
        fun ask() {
            push(Dialog("${c.name} IS TRYING TO LEARN ${m.label}. BUT ${c.name} CAN'T LEARN MORE THAN FOUR MOVES!") {
                push(Dialog("DELETE AN OLDER MOVE TO MAKE ROOM FOR ${m.label}?", ask = true) { yes ->
                    if (yes) {
                        push(Menu(c.moves.map { it.move.label }, x = 64, y = 36, cancelable = true, title = "FORGET WHICH?") { i ->
                            if (i < 0) {
                                ask()
                            } else {
                                val old = c.moves[i].move
                                c.replaceMove(i, m)
                                push(Dialog("1, 2 AND... POOF! ${c.name} FORGOT ${old.label}. AND... ${c.name} LEARNED ${m.label}!") { done() })
                            }
                        })
                    } else {
                        push(Dialog("STOP LEARNING ${m.label}?", ask = true) { stop ->
                            if (stop) push(Dialog("${c.name} DID NOT LEARN ${m.label}.") { done() }) else ask()
                        })
                    }
                })
            })
        }
        ask()
    }

    // ================================================================ start menu

    fun openStartMenu() {
        val opts = ArrayList<String>()
        val acts = ArrayList<() -> Unit>()
        if ("dex" in flags) { opts += "DEX"; acts += { push(DexScreen()) } }
        if (party.isNotEmpty()) { opts += "CRITTERS"; acts += { push(PartyScreen(PartyScreen.Mode.MENU) {}) } }
        opts += "BAG"; acts += { push(BagScreen(inBattle = false) { _, _ -> }) }
        opts += playerName; acts += { push(TrainerCard()) }
        opts += "SAVE"; acts += {
            push(Dialog("WOULD YOU LIKE TO SAVE THE GAME?", ask = true) { yes ->
                if (yes) {
                    save()
                    sfx(Sfx.SAVE)
                    push(Dialog(text("{P} SAVED THE GAME.")) {})
                }
            })
        }
        opts += if (musicOn) "MUSIC ON" else "MUSIC OFF"; acts += { toggleMusic() }
        opts += "EXIT"; acts += {}
        push(Menu(opts, x = 88, y = 0, w = 72, cancelable = true, keepOpenOnPick = false) { i -> if (i >= 0) acts[i]() })
    }

    // ================================================================ drawing the world

    private fun personSprite(look: Art.Look, f: Dir, walking: Boolean, step: Int): Pair<Sprite, Boolean> {
        val fr = Art.person(look)
        return when (f) {
            Dir.DOWN -> (if (walking) fr.downWalk else fr.down) to (walking && step % 2 == 1)
            Dir.UP -> (if (walking) fr.upWalk else fr.up) to (walking && step % 2 == 1)
            Dir.LEFT -> (if (walking) fr.leftWalk else fr.left) to false
            Dir.RIGHT -> (if (walking) fr.leftWalk else fr.left) to true
        }
    }

    private val grassFront by lazy {
        Sprite(16, 16, IntArray(256) { if (it / 16 >= 9) Art.tallGrass.px[it] else 0 })
    }

    private fun drawWorld() {
        val s = screen
        val frame = tick / 30
        val back = 16 - moveT
        val jumpLift = if (jumping || jumpLanding) {
            val t = if (jumping) moveT else 16 + moveT
            (kotlin.math.sin(t / 32f * Math.PI).toFloat() * 8f).toInt()
        } else 0
        val ppx = px * 16 - moveDir.dx * back
        val ppy = py * 16 - moveDir.dy * back
        val camX = ppx - 64
        val camY = ppy - 64
        val tx0 = Math.floorDiv(camX, 16)
        val ty0 = Math.floorDiv(camY, 16)
        for (ty in ty0..ty0 + 9) {
            for (tx in tx0..tx0 + 10) {
                s.blit(Art.tile(map.at(tx, ty), frame), tx * 16 - camX, ty * 16 - camY)
            }
        }
        // People, sorted so nearer ones overlap farther ones.
        class Draw(val y: Int, val f: () -> Unit)
        val list = ArrayList<Draw>()
        for (n in npcs) {
            val nb = 16 - n.moveT.coerceAtMost(16)
            val nx = n.x * 16 - n.moveDir.dx * nb - camX
            val ny = n.y * 16 - n.moveDir.dy * nb - camY
            list += Draw(ny) {
                when (n.def.look) {
                    Art.Look.BALL -> s.blit(Art.ball, nx, ny)
                    Art.Look.SOLARIS -> s.blitScaled(CritterArt.front(22), nx - 8, ny - 18 + if (tick / 20 % 2 == 0) 0 else 1, 32, 32)
                    else -> {
                        val (sp, flip) = personSprite(n.def.look, n.face, n.moveT in 4..12, n.step)
                        s.blit(sp, nx, ny - 4, flip)
                    }
                }
                if (n.bubble > 0) s.blit(Art.bubble, nx, ny - 20)
            }
        }
        list += Draw(ppy - camY) {
            val (sp, flip) = personSprite(Art.Look.PLAYER, face, moveT in 4..12, stepCount)
            if (jumpLift > 0) s.rect(64 + 3, 64 + 12, 10, 3, 0x60000000)
            s.blit(sp, 64, 60 - jumpLift, flip)
            if (map.at(px, py) == ',' && moveT >= 8) s.blit(grassFront, 64, 64)
        }
        list.sortBy { it.y }
        list.forEach { it.f() }

        if (mapLabel > 0) {
            val w = map.name.length * 6 + 16
            s.box(0, 0, w.coerceAtLeast(64), 24)
            s.text(map.name, 8, 8)
        }
    }

    // ================================================================ credits

    private val creditLines by lazy {
        listOf(
            "POCKET CRITTERS", "COLOR", "", "", "CONGRATULATIONS,", "{P}!", "",
            "YOU BEAT ALL THREE GYMS", "AND YOUR RIVAL ${World.RIVAL}.", "", "YOU ARE THE NEW", "CHAMPION!", "", "",
            "YOUR TEAM", "",
        ) + party.map { "${it.name}  :L${it.level}" } + listOf(
            "", "", "CRITTERS CAUGHT: ${caught.size} / ${Dex.all.size}", "", "",
            "THANKS TO PROF. HAZEL", "AND ALL THE TRAINERS", "YOU MET ALONG THE WAY.", "", "",
            "THANKS FOR PLAYING!", "", "", "", "", "THE END",
        )
    }

    private fun updateCredits() {
        creditsT++
        val endAt = creditLines.size * 12 + SH + 40
        if (creditsT > endAt && (pressed(Btn.A) || pressed(Btn.START))) {
            flags += "champion"
            transition(30) {
                scene = Scene.WORLD
                returnStack.clear()
                returnStack += Triple("sprout", 4, 5)
                loadMap("home", 3, 5, Dir.DOWN)
                setRespawnHere()
                push(Dialog(text("MOM: {P}! YOU'RE HOME! I SAW YOU ON TV. I'M SO PROUD OF YOU!")) { resume() })
            }
        }
    }

    private fun drawCredits() {
        val s = screen
        s.fill(INK)
        val top = SH - creditsT / 2
        creditLines.forEachIndexed { i, line ->
            val y = top + i * 12
            if (y in -8 until SH) s.textCenter(text(line), 80, y, if (i < 2) 0xFFF8D040.toInt() else PAPER)
        }
        val lastY = top + creditLines.lastIndex * 12
        if (lastY < 64) {
            s.fill(INK)
            s.textCenter("THE END", 80, 64, 0xFFF8D040.toInt())
            if (tick / 30 % 2 == 0) s.textCenter("PRESS A", 80, 100, PAPER)
            s.blit(CritterArt.front(22), 56, 12)
        }
    }

    // ================================================================ saving

    fun save() {
        val sb = StringBuilder()
        fun kv(k: String, v: Any) = sb.append(k).append('=').append(v).append('\n')
        kv("v", 1)
        kv("name", playerName)
        kv("rival", rivalStarter)
        kv("map", map.id); kv("x", px); kv("y", py); kv("face", face.name)
        kv("money", money)
        kv("frames", playFrames)
        kv("music", if (musicOn) 1 else 0)
        kv("flags", flags.joinToString(","))
        kv("party", party.joinToString(";") { it.encode() })
        kv("box", box.joinToString(";") { it.encode() })
        kv("bag", bag.list().joinToString(",") { "${it.first.name}*${it.second}" })
        kv("seen", seen.joinToString(","))
        kv("caught", caught.joinToString(","))
        kv("back", returnStack.joinToString(";") { "${it.first},${it.second},${it.third}" })
        kv("respawn", "${respawn.map},${respawn.x},${respawn.y}|" + respawn.back.joinToString(";") { "${it.first},${it.second},${it.third}" })
        store.write(sb.toString())
    }

    fun hasSave() = store.read() != null

    /** Saves when the app goes to the background, but only at a calm moment in the overworld. */
    fun autosave() {
        if (scene == Scene.WORLD && scriptIt == null && ui.isEmpty() && trans == null && !moving && party.isNotEmpty()) save()
    }

    private fun continueGame() {
        val data = store.read() ?: return newGame()
        val kv = data.lines().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        resetProgress()
        bag.items.clear()
        try {
            playerName = kv["name"] ?: "ALEX"
            rivalStarter = kv["rival"]?.toIntOrNull() ?: 3
            money = kv["money"]?.toIntOrNull() ?: 0
            playFrames = kv["frames"]?.toLongOrNull() ?: 0
            if (kv["music"] == "0" && musicOn) toggleMusic()
            kv["flags"]?.split(",")?.filter { it.isNotEmpty() }?.let { flags += it }
            kv["party"]?.split(";")?.mapNotNull { if (it.isEmpty()) null else Critter.decode(it) }?.let { party += it }
            kv["box"]?.split(";")?.mapNotNull { if (it.isEmpty()) null else Critter.decode(it) }?.let { box += it }
            kv["bag"]?.split(",")?.filter { '*' in it }?.forEach {
                val item = Item.valueOf(it.substringBefore('*'))
                bag.add(item, it.substringAfter('*').toInt())
            }
            kv["seen"]?.split(",")?.mapNotNull { it.toIntOrNull() }?.let { seen += it }
            kv["caught"]?.split(",")?.mapNotNull { it.toIntOrNull() }?.let { caught += it }
            fun triples(s: String?) = s?.split(";")?.filter { it.count { c -> c == ',' } == 2 }?.map {
                val p = it.split(","); Triple(p[0], p[1].toInt(), p[2].toInt())
            } ?: emptyList()
            returnStack.clear()
            returnStack += triples(kv["back"])
            kv["respawn"]?.let { r ->
                val head = r.substringBefore('|').split(",")
                respawn = Respawn(head[0], head[1].toInt(), head[2].toInt(), triples(r.substringAfter('|', "")))
            }
            val m = kv["map"]?.takeIf { World.maps.containsKey(it) } ?: "home"
            val f = runCatching { Dir.valueOf(kv["face"] ?: "DOWN") }.getOrDefault(Dir.DOWN)
            transition {
                scene = Scene.WORLD
                map = World["home"]
                loadMap(m, kv["x"]?.toIntOrNull() ?: 3, kv["y"]?.toIntOrNull() ?: 5, f)
                playMusic(map.tune)
            }
        } catch (_: Exception) {
            newGame()
        }
    }

    // ================================================================ test hooks

    /** Puts the player straight into the world (skipping the title and intro). */
    fun debugStart(mapId: String = "sprout", x: Int = 9, y: Int = 8, starter: Int = 1, level: Int = 5) {
        resetProgress()
        flags += listOf("starter", "dex", "took_$starter", "rival_left_lab")
        party += Critter(Dex[starter], level, rng)
        caught += starter; seen += starter
        bag.add(Item.CAPSULE, 5)
        scene = Scene.WORLD
        loadMap(mapId, x, y, Dir.DOWN)
    }

    fun debugBattle(foe: Int, level: Int, trainer: Trainer? = null) =
        startBattle(listOf(Critter(Dex[foe], level, rng)), trainer, Tune.BATTLE, true) {}

    val battleScene get() = battle

    companion object {
        val NAMES = listOf("ALEX", "SAM", "JO", "RIO", "MAX", "LILY", "KIM", "ZOE")
    }
}
