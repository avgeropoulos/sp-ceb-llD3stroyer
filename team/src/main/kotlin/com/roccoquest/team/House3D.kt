package com.roccoquest.team

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sound
import com.roccoquest.game.argb
import com.roccoquest.game.shadowText
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** The kinds of block you can build with in the 3D house. */
enum class Block3D(val label: String, val color: Int) {
    PLANKS("PLANKS", 0xFFB98A52.toInt()),
    STONE("STONE", 0xFF8A8A8A.toInt()),
    BRICK("BRICK", 0xFFB0503A.toInt()),
    GLASS("GLASS", 0x88BFE8FF.toInt()),
    GRASS("GRASS", 0xFF5AB84A.toInt()),
    RED("RED WOOL", 0xFFE84A4A.toInt()),
    BLUE("BLUE WOOL", 0xFF3A6AE8.toInt()),
    WHITE("WHITE WOOL", 0xFFF4F4F4.toInt()),
    GOLD("GOLD", 0xFFFFD21F.toInt()),
    DIAMOND("DIAMOND", 0xFF7FF4F0.toInt()),
}

/**
 * The house in 3D, like a block game: walk around a floor of your house in first person, see your
 * furniture and plushies standing in the room, and build with blocks (PLACE and BREAK).
 * The backyard and the roof (with the city all around it) work in 3D too.
 *
 * The room is [W] x [H] x [D] blocks. One block is 30 pixels of the 2D room, so the 2D drawings
 * (furniture, the shop, the bed...) are shown standing up in the room at the right size.
 */
class House3D(private val g: TeamGame) {
    companion object {
        const val W = 30
        const val D = 10
        const val H = 6
        const val PX = 30f
        const val MAX_BLOCKS = 300
        const val MAX_MOVERS = 12
        private const val G3 = 22f
        private const val JUMP_V = 7.6f
        /** The palette slot after the blocks: a moving platform. */
        val MOVER_SLOT = Block3D.entries.size
        /** Outside you can build higher. */
        const val OUT_H = 8
        private const val NEAR = 0.08f
    }

    var x = 3f
    var z = 7f
    /** Feet height (blocks): you can jump and stand on blocks now. */
    var feet = 0f
    var vy = 0f
    var onGround = true
        private set
    /** Third person: the camera is behind Rocco so you can see him (and his parkour). */
    var thirdPerson = true
    // Parkour in 3D: the push off a wall, flips, a vault, a landing roll.
    private var kickX = 0f
    private var kickZ = 0f
    var flipT = 0f
        private set
    private var flipDur = 0.5f
    var vaultT = 0f
        private set
    var rollT = 0f
        private set
    private var jumps = 0
    private var walking = false
    private var faceDir = 1
    /** Moving platforms built here (MOVER in the palette): they slide 3 blocks along x or z and back. */
    class Mover(val bx: Int, val by: Int, val bz: Int, val alongX: Boolean) {
        var ox = 0f
        var oz = 0f
        var dx = 0f
        var dz = 0f
        val x0 get() = bx + ox - 0.5f
        val z0 get() = bz + oz - 0.5f
        val top get() = by + 1f
    }
    val movers = ArrayList<Mover>()
    /** The camera (it's where your eyes are, or behind Rocco in third person). */
    private var ex = 3f
    private var ey = 1.6f
    private var ez = 7f
    var yaw = -Math.PI.toFloat() / 2
    var pitch = -0.15f
    var selected = 0
    /** Blocks on the floor that's loaded: (x, y, z) packed by [key] -> block type. */
    val blocks = HashMap<Int, Int>()
    /** Which place is loaded: a floor (0-9), [Decor.YARD] or [Decor.ROOF]. */
    private var loadedArea = -1
    private var monX = 4f
    private var monZ = 6f
    private var t = 0f

    fun key(bx: Int, by: Int, bz: Int) = bx or (by shl 7) or (bz shl 10)
    private fun kx(k: Int) = k and 127
    private fun ky(k: Int) = (k shr 7) and 7
    private fun kz(k: Int) = (k shr 10) and 15

    /** Where you are at home right now. */
    fun area() = when { g.inGarden -> Decor.YARD; g.onRoof -> Decor.ROOF; else -> g.homeFloor }
    val outside get() = Decor.outside(loadedArea)
    /** How many blocks across the place is (the backyard is long). */
    val width get() = if (loadedArea == Decor.YARD) (Garden.WIDTH / PX).toInt() else W
    private val height get() = if (outside) OUT_H else H

    fun enter() {
        loadFloor(area())
        feet = 0f; vy = 0f; onGround = true; kickX = 0f; kickZ = 0f
        x = (g.homeX / PX).coerceIn(1.5f, width - 1.5f)
        z = 7f
        yaw = -Math.PI.toFloat() / 2
        pitch = -0.15f
        monX = x + 1f
        monZ = z - 1f
    }

    /** Save names: floors keep their old ones (and old packing: x 5 bits); outside has room for a longer x. */
    private fun saveName(area: Int) = when (area) { Decor.YARD -> "b3dyard"; Decor.ROOF -> "b3droof"; else -> "b3d$area" }

    fun loadFloor(area: Int) {
        blocks.clear()
        loadedArea = area
        val name = saveName(area)
        val n = g.slotLoad("${name}n").coerceIn(0, MAX_BLOCKS)
        for (i in 0 until n) {
            val v = g.slotLoad("${name}_$i") - 1
            if (v < 0) continue
            val (k, type) = if (outside) key(v and 127, (v shr 7) and 7, (v shr 10) and 15) to ((v shr 14) and 0xF)
                else key(v and 31, (v shr 5) and 7, (v shr 8) and 15) to ((v shr 12) and 0xF)
            if (type in Block3D.entries.indices && inRoom(kx(k), ky(k), kz(k))) blocks[k] = type
        }
        movers.clear()
        for (i in 0 until g.slotLoad("${name}mvn").coerceIn(0, MAX_MOVERS)) {
            val v = g.slotLoad("${name}mv_$i") - 1
            if (v >= 0) movers += Mover(v and 127, (v shr 7) and 7, (v shr 10) and 15, (v shr 14) and 1 == 1)
        }
    }

    private fun saveFloor() {
        var i = 0
        val name = saveName(loadedArea)
        for ((k, type) in blocks) {
            val v = if (outside) k or (type shl 14) else kx(k) or (ky(k) shl 5) or (kz(k) shl 8) or (type shl 12)
            g.slotSave("${name}_$i", v + 1); i++
        }
        g.slotSave("${name}n", i)
        movers.forEachIndexed { j, m -> g.slotSave("${name}mv_$j", (m.bx or (m.by shl 7) or (m.bz shl 10) or ((if (m.alongX) 1 else 0) shl 14)) + 1) }
        g.slotSave("${name}mvn", movers.size)
    }

    // ---------------------------------------------------------------- Moving and building

    private fun block(bx: Int, by: Int, bz: Int) = by >= 0 && blocks.containsKey(key(bx, by, bz))

    /** True if the body (feet at [f], 1.7 tall) would bump into a block at [px], [pz]. */
    private fun solidAt(px: Float, pz: Float, f: Float = feet): Boolean {
        // Rocco is about 0.4 blocks wide: check his four corners.
        for (ox in floatArrayOf(-0.2f, 0.2f)) for (oz in floatArrayOf(-0.2f, 0.2f)) {
            val bx = floor(px + ox).toInt()
            val bz = floor(pz + oz).toInt()
            for (by in floor(f + 0.05f).toInt()..floor(f + 1.6f).toInt()) if (block(bx, by, bz)) return true
        }
        return false
    }

    /** The highest thing to stand on under [px], [pz] at or below [f] (blocks and moving platforms; 0 is the floor). */
    private fun groundUnder(px: Float, pz: Float, f: Float): Float {
        var best = 0f
        for (ox in floatArrayOf(-0.18f, 0.18f)) for (oz in floatArrayOf(-0.18f, 0.18f)) {
            val bx = floor(px + ox).toInt(); val bz = floor(pz + oz).toInt()
            // (Only blocks whose top is at or a hair above your feet: higher ones are walls.)
            var by = floor(f + 0.3f - 1f).toInt()
            while (by >= 0) { if (block(bx, by, bz)) { best = maxOf(best, by + 1f); break }; by-- }
        }
        for (m in movers) if (px > m.x0 - 0.2f && px < m.x0 + 1.2f && pz > m.z0 - 0.2f && pz < m.z0 + 1.2f && f >= m.top - 0.35f) best = maxOf(best, m.top)
        return best
    }

    /** The mover you're standing on, if any. */
    private fun riding(): Mover? = movers.firstOrNull { m -> onGround && abs(feet - m.top) < 0.05f && x > m.x0 - 0.2f && x < m.x0 + 1.2f && z > m.z0 - 0.2f && z < m.z0 + 1.2f }

    fun update(dt: Float, input: TeamInput, place: Boolean, breakIt: Boolean, hop: Boolean = false) {
        t += dt
        if (loadedArea != area()) { loadFloor(area()); x = 3f; z = 7f; feet = 0f; vy = 0f }
        if (input.left) { yaw -= 2.2f * dt; faceDir = -1 }
        if (input.right) { yaw += 2.2f * dt; faceDir = 1 }
        if (input.lookUp) pitch = (pitch + 1.2f * dt).coerceAtMost(0.9f)
        if (input.lookDown) pitch = (pitch - 1.2f * dt).coerceAtLeast(-1.2f)
        if (flipT > 0f) flipT -= dt
        if (vaultT > 0f) vaultT -= dt
        if (rollT > 0f) rollT -= dt
        // Moving platforms slide back and forth, carrying you along.
        val ride = riding()
        for ((i, m) in movers.withIndex()) {
            val o = 1.5f - 1.5f * cos(t * 1.4f + i * 0.7f)
            val nx = if (m.alongX) o else 0f
            val nz = if (m.alongX) 0f else o
            m.dx = nx - m.ox; m.dz = nz - m.oz
            m.ox = nx; m.oz = nz
        }
        ride?.let { m -> tryMove(x + m.dx, z + m.dz) }
        val move = (if (input.up) 1f else 0f) - (if (input.down) 1f else 0f)
        walking = move != 0f
        var blockedAhead = false
        if (move != 0f) {
            val sp = 3.2f * move * dt
            blockedAhead = !tryMove(x + cos(yaw) * sp, z + sin(yaw) * sp)
            // PARKOUR: walking into a block one high: VAULT up onto it.
            if (blockedAhead && onGround && move > 0f && vaultT <= 0f) {
                val fx = x + cos(yaw) * 0.6f; val fz = z + sin(yaw) * 0.6f
                val by = floor(feet + 0.05f).toInt()
                if (block(floor(fx).toInt(), by, floor(fz).toInt()) && !solidAt(fx, fz, by + 1f) && !solidAt(x, z, feet + 1f)) {
                    vy = 7.4f; onGround = false; vaultT = 0.4f; g.sfx(Sound.JUMP)
                }
            }
        }
        // The push off a wall carries on for a moment.
        if (abs(kickX) + abs(kickZ) > 0.01f) {
            tryMove(x + kickX * dt, z + kickZ * dt)
            kickX *= 0.9f; kickZ *= 0.9f
        }
        // JUMP (and parkour: a wall jump when you're in the air against a wall).
        if (hop) {
            if (onGround) {
                vy = JUMP_V; onGround = false; g.sfx(Sound.JUMP)
                if (walking && ++jumps % 3 == 0) { flipT = 0.55f; flipDur = 0.55f; g.toastFrom3D("FLIP!") }
            } else if (blockedAhead || solidAt(x + cos(yaw) * 0.45f, z + sin(yaw) * 0.45f)) {
                vy = JUMP_V; kickX = -cos(yaw) * 6f; kickZ = -sin(yaw) * 6f
                flipT = 0.5f; flipDur = 0.5f
                g.sfx(Sound.JUMP)
                g.toastFrom3D("WALL JUMP!")
            }
        }
        // Falling, landing, and sliding down walls.
        val wasGround = onGround
        val fall = vy
        if (!onGround && vy < 0f && blockedAhead) vy = maxOf(vy, -2.5f)
        vy -= G3 * dt
        var nf = feet + vy * dt
        val ground = groundUnder(x, z, feet)
        if (nf <= ground) { nf = ground; vy = 0f; onGround = true } else onGround = false
        // Bumping your head on a block above.
        if (vy > 0f && block(floor(x).toInt(), floor(nf + 1.75f).toInt(), floor(z).toInt())) vy = 0f
        feet = nf.coerceIn(0f, height.toFloat())
        if (!wasGround && onGround && fall < -10f && walking) { rollT = 0.45f; g.toastFrom3D("ROLL!") }
        if (!wasGround && onGround && fall < -8f) g.sfx(Sound.KICK)
        // The Pokemon follows you around.
        val dx = x - monX
        val dz = z - monZ
        val d = kotlin.math.hypot(dx, dz)
        if (d > 1.6f) { monX += dx / d * 2.8f * dt; monZ += dz / d * 2.8f * dt }
        if (place || breakIt) {
            val hit = target()
            if (breakIt && hit.block != null) {
                blocks.remove(hit.block)
                g.sfx(Sound.BREAK)
                saveFloor()
            } else if (breakIt && hit.mover != null) {
                movers.remove(hit.mover)
                g.sfx(Sound.BREAK)
                saveFloor()
            } else if (place && hit.place != null && selected == MOVER_SLOT) {
                val (bx, by, bz) = hit.place
                when {
                    movers.size >= MAX_MOVERS -> { g.sfx(Sound.BUMP); g.toastFrom3D("That's a lot of moving platforms!") }
                    else -> {
                        // It slides away from you, along whichever way you're looking most.
                        movers += Mover(bx, by, bz, abs(cos(yaw)) > abs(sin(yaw)))
                        g.sfx(Sound.KICK); saveFloor()
                        g.toastFrom3D("MOVING PLATFORM! Jump on and ride it.")
                    }
                }
            } else if (place && hit.place != null) {
                val (bx, by, bz) = hit.place
                val standing = floor(x).toInt() == bx && floor(z).toInt() == bz && by in floor(feet).toInt()..floor(feet + 1.6f).toInt()
                when {
                    standing -> g.sfx(Sound.BUMP)
                    blocks.size >= MAX_BLOCKS -> { g.sfx(Sound.BUMP); g.toastFrom3D("That's a lot of blocks! Break some to build more.") }
                    else -> { blocks[key(bx, by, bz)] = selected; g.sfx(Sound.KICK); saveFloor() }
                }
            }
        }
    }

    /** Moves to ([nx], [nz]) if nothing's in the way (one axis at a time). Returns false if a block stopped you. */
    private fun tryMove(nx0: Float, nz0: Float): Boolean {
        val nx = nx0.coerceIn(0.3f, width - 0.3f)
        val nz = nz0.coerceIn(0.3f, D - 0.3f)
        var ok = true
        if (!solidAt(nx, z)) x = nx else ok = false
        if (!solidAt(x, nz)) z = nz else ok = false
        return ok
    }

    class Hit(val block: Int?, val place: Triple<Int, Int, Int>?, val mover: Mover? = null)

    private fun dir(): Triple<Float, Float, Float> = Triple(cos(yaw) * cos(pitch), sin(pitch), sin(yaw) * cos(pitch))

    /** What you're looking at: a block to break, and the empty spot in front of it to put a new one. */
    fun target(): Hit {
        val (fx, fy, fz) = dir()
        var prev: Triple<Int, Int, Int>? = null
        var s = 0.2f
        while (s < 6f) {
            val px = x + fx * s
            val py = feet + 1.6f + fy * s
            val pz = z + fz * s
            movers.firstOrNull { m -> px > m.x0 && px < m.x0 + 1f && pz > m.z0 && pz < m.z0 + 1f && py > m.top - 0.3f && py < m.top }?.let { return Hit(null, prev, it) }
            val bx = floor(px).toInt()
            val by = floor(py).toInt()
            val bz = floor(pz).toInt()
            if (by < 0) return Hit(null, prev ?: Triple(bx, 0, bz).takeIf { inRoom(bx, 0, bz) })
            if (!inRoom(bx, by, bz)) return Hit(null, prev)
            val k = key(bx, by, bz)
            if (blocks.containsKey(k)) return Hit(k, prev)
            prev = Triple(bx, by, bz)
            s += 0.04f
        }
        return Hit(null, null)
    }

    private fun inRoom(bx: Int, by: Int, bz: Int) = bx in 0 until width && by in 0 until height && bz in 0 until D

    // ---------------------------------------------------------------- Drawing

    private var cyw = 1f; private var syw = 0f; private var cp = 1f; private var sp = 0f
    private var focal = 200f; private var midX = 200f; private var midY = 120f

    /** Camera-space coordinates of a world point: right, up, forward. */
    private fun cam(wx: Float, wy: Float, wz: Float): FloatArray {
        val dx = wx - ex; val dy = wy - ey; val dz = wz - ez
        // Forward, right and up vectors of the camera.
        val fX = cyw * cp; val fY = sp; val fZ = syw * cp
        val rX = -syw; val rZ = cyw
        val uX = -cyw * sp; val uY = cp; val uZ = -syw * sp
        return floatArrayOf(dx * rX + dz * rZ, dx * uX + dy * uY + dz * uZ, dx * fX + dy * fY + dz * fZ)
    }

    /** Draws a flat polygon given its corners in the world, cut off where it goes behind you. */
    private fun poly(gfx: Gfx, pts: List<FloatArray>, color: Int) {
        val c = pts.map { cam(it[0], it[1], it[2]) }
        // Clip against the near plane.
        val out = ArrayList<FloatArray>()
        for (i in c.indices) {
            val a = c[i]
            val b = c[(i + 1) % c.size]
            val ain = a[2] >= NEAR
            val bin = b[2] >= NEAR
            if (ain) out += a
            if (ain != bin) {
                val k = (NEAR - a[2]) / (b[2] - a[2])
                out += floatArrayOf(a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, NEAR)
            }
        }
        if (out.size < 3) return
        val xs = FloatArray(out.size) { midX + out[it][0] / out[it][2] * focal }
        val ys = FloatArray(out.size) { midY - out[it][1] / out[it][2] * focal }
        gfx.poly(xs, ys, color)
    }

    private fun shade(c: Int, k: Float): Int {
        val a = (c ushr 24) and 255
        fun ch(v: Int) = (v * k).toInt().coerceIn(0, 255)
        return argb(a, (ch((c shr 16) and 255) shl 16) or (ch((c shr 8) and 255) shl 8) or ch(c and 255))
    }

    /** Something to draw, sorted far to near so near things cover far ones. */
    private class Item(val dist: Float, val draw: () -> Unit)

    fun render(gfx: Gfx, viewW: Float, viewH: Float) {
        cyw = cos(yaw); syw = sin(yaw); cp = cos(pitch); sp = sin(pitch)
        // Where the camera is: your eyes, or (third person) a bit behind and above Rocco.
        if (thirdPerson) {
            var back = 3.3f
            // Don't go through the walls of the room.
            while (back > 0.6f && (x - cyw * back !in 0.15f..(width - 0.15f) || z - syw * back !in 0.15f..(D - 0.15f))) back -= 0.2f
            ex = x - cyw * back; ez = z - syw * back; ey = feet + 2.3f
        } else { ex = x; ey = feet + 1.6f; ez = z }
        focal = viewH * 0.85f
        midX = viewW / 2
        midY = viewH / 2
        val h = H.toFloat()
        val w = width.toFloat()
        val d = D.toFloat()
        val items = ArrayList<Item>()
        if (outside) renderOutside(gfx, viewW, viewH, items) else renderRoom(gfx, items, h, w, d)
        drawBlocksAndRest(gfx, viewW, viewH, items)
    }

    private fun renderRoom(gfx: Gfx, items: ArrayList<Item>, h: Float, w: Float, d: Float) {
        val (wall, stripe, floorC) = Homes.roomColors(g.houseType, g.housePaint)
        gfx.rect(0f, 0f, 2000f, 2000f, 0xFF101018.toInt())
        // The room: ceiling, four walls and a striped floor.
        poly(gfx, listOf(floatArrayOf(0f, h, 0f), floatArrayOf(w, h, 0f), floatArrayOf(w, h, d), floatArrayOf(0f, h, d)), shade(wall, 0.75f))
        poly(gfx, listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(w, 0f, 0f), floatArrayOf(w, h, 0f), floatArrayOf(0f, h, 0f)), wall)
        poly(gfx, listOf(floatArrayOf(0f, 0f, d), floatArrayOf(w, 0f, d), floatArrayOf(w, h, d), floatArrayOf(0f, h, d)), shade(wall, 0.85f))
        poly(gfx, listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, d), floatArrayOf(0f, h, d), floatArrayOf(0f, h, 0f)), shade(wall, 0.9f))
        poly(gfx, listOf(floatArrayOf(w, 0f, 0f), floatArrayOf(w, 0f, d), floatArrayOf(w, h, d), floatArrayOf(w, h, 0f)), shade(wall, 0.9f))
        // Wallpaper stripes on the back wall.
        var sx = 0.3f
        while (sx < w) { poly(gfx, listOf(floatArrayOf(sx, 0f, 0.01f), floatArrayOf(sx + 0.3f, 0f, 0.01f), floatArrayOf(sx + 0.3f, h, 0.01f), floatArrayOf(sx, h, 0.01f)), stripe); sx += 1.13f }
        for (i in 0 until W / 2) {
            poly(gfx, listOf(floatArrayOf(i * 2f, 0f, 0f), floatArrayOf(i * 2f + 2f, 0f, 0f), floatArrayOf(i * 2f + 2f, 0f, d), floatArrayOf(i * 2f, 0f, d)), if (i % 2 == 0) floorC else shade(floorC, 0.88f))
        }
        // The 2D pictures (furniture, shop, bed, shelves...) stand up in the room, facing you.
        fun picture(cx2: Float, wz: Float, draw: (Gfx) -> Unit) = picture(gfx, items, cx2, wz, draw = draw)
        val fl = loadedArea
        val time = g.time
        picture(Homes.BED_X + 26f, 0.05f) { Homes.drawWindow(it, Homes.BED_X - 2f, 40f, time, g.homePlanet) }
        if (fl == 0) {
            picture(Homes.DOOR_X + 15f, 0.05f) { Homes.drawDoor(it, Homes.DOOR_X) }
            picture(Homes.SHOP_X + 33f, 1.2f) { Homes.drawShop(g, it, Homes.SHOP_X, time) }
            picture(Homes.MON_X + 22f, 1.5f) { Homes.drawMonMat(it, 0f, time) }
            if (g.hasBed()) picture(Homes.BED_X + 28f, 1.3f) { Homes.drawBed(g.painted(it, g.bedColor), g.bedStyle, Homes.BED_X) }
        } else picture(Homes.DOOR_X + 12f, 0.3f) { Homes.drawStairs(it, Homes.DOOR_X - 6f, false) }
        if (g.houseFloors > fl + 1) picture(Homes.STAIRS_X + 12f, 0.3f) { Homes.drawStairs(it, Homes.STAIRS_X - 4f, true) }
        else if (!g.hasElevator) picture(Homes.STAIRS_X + 9f, 0.3f) { Roof.drawLadderUp(it, Homes.STAIRS_X - 4f, Homes.FLOOR_Y) }
        picture(Homes.SHELF_X + 104f, 0.1f) { g.drawShelvesWithPlushies(it, 0f) }
        for (f in Furniture.entries) {
            if (!g.hasFurniture(f) || g.furnFloor(f) != fl) continue
            val fx = g.furnX(f)
            val depth = when (Homes.layer(f)) { 1 -> 0.15f; 2 -> 2.2f; else -> 1.4f }
            if (f == Furniture.STAR_LIGHTS) {
                // The lights go all the way along the back wall: draw them in pieces so they stay in place.
                for (p in 0 until 6) picture(p * 150f + 75f, 0.1f) { sg ->
                    for (k in 0 until 5) {
                        val lx = p * 150f + k * 30f + 13f
                        sg.oval(lx, 31f, 5f, 5f, if (((time * 3).toInt() + k + p) % 3 != 0) solidRainbow((p * 5 + k) * 0.13f, 0f) else 0xFF5A5A5A.toInt())
                    }
                }
                continue
            }
            picture(fx + Homes.width(f) / 2, depth) { g.drawFurnitureAt(it, f, fx) }
        }
        // Decorations from the DECOR shop.
        for (d in g.decor) {
            if (d.floor != fl) continue
            val depth = when (d.item.layer) { 1 -> 0.15f; 2 -> 2.2f; else -> 1.4f }
            picture(d.x + d.item.w / 2, depth) { Decor.draw(g.painted(it, d.color), d.item, d.x, Homes.FLOOR_Y, time) }
        }
        if (g.hasElevator) picture(Homes.STAIRS_X + 10f, 0.3f) { Homes.drawElevator(it, Homes.STAIRS_X - 8f, time) }
    }

    /** A 2D drawing standing up at room x [cx2] (2D pixels) and depth [wz], facing you. */
    private fun picture(gfx: Gfx, items: ArrayList<Item>, cx2: Float, wz: Float, wy: Float = 0f, draw: (Gfx) -> Unit) {
        val wx = cx2 / PX
        val c = cam(wx, wy, wz)
        if (c[2] < 0.3f) return
        items += Item(c[2]) {
            val k = focal / c[2] / PX
            val ax = midX + c[0] / c[2] * focal
            val ay = midY - c[1] / c[2] * focal
            draw(ScaledGfx(gfx, k, ax - cx2 * k, ay - Homes.FLOOR_Y * k))
        }
    }

    /** Where the horizon is on screen (it moves when you look up and down). */
    private fun horizonY() = midY + kotlin.math.tan(pitch) * focal

    /** Something far away all around you (hills, clouds, the far city), at compass angle [a]. Returns screen x or null. */
    private fun around(a: Float): Float? {
        var da = a - yaw
        while (da > Math.PI) da -= (2 * Math.PI).toFloat()
        while (da < -Math.PI) da += (2 * Math.PI).toFloat()
        if (abs(da) > 1.3f) return null
        return midX + kotlin.math.tan(da) * focal
    }

    /** The backyard or the roof: sky all around, the lawn or the roof deck, and (on the roof) the city below. */
    private fun renderOutside(gfx: Gfx, viewW: Float, viewH: Float, items: ArrayList<Item>) {
        val roof = loadedArea == Decor.ROOF
        val time = g.time
        val hy = horizonY()
        val w = width.toFloat(); val d = D.toFloat()
        if (roof) gfx.gradient(0f, minOf(0f, hy - 400f), viewW, maxOf(hy, 1f) - minOf(0f, hy - 400f), 0xFF2A3A8A.toInt(), 0xFFF4A878.toInt())
        else gfx.gradient(0f, minOf(0f, hy - 400f), viewW, maxOf(hy, 1f) - minOf(0f, hy - 400f), g.homePlanet.skyTop, g.homePlanet.skyBottom)
        gfx.rect(0f, hy, viewW, maxOf(0f, viewH - hy), if (roof) 0xFF22223A.toInt() else g.homePlanet.hill2)
        // Far away things that turn with you
        if (roof) {
            for (k in 0 until 8) {
                val sx = around(k * 0.785f) ?: continue
                gfx.sprite(Roof.far, sx - 120f, hy - 60f, w = 240f, h = 60f)
            }
            around(-1.2f)?.let { gfx.oval(it - 12f, hy - 150f, 24f, 24f, 0xFFFFF4D0.toInt()) }
        } else {
            for (k in 0 until 10) {
                val sx = around(k * 0.628f + 0.3f) ?: continue
                gfx.oval(sx - 90f, hy - 26f - (k % 3) * 8f, 180f, 60f + (k % 3) * 16f, if (k % 2 == 0) g.homePlanet.hill1 else g.homePlanet.hill2)
            }
            for (k in 0 until 6) {
                val sx = around(k * 1.05f + time * 0.01f) ?: continue
                gfx.oval(sx - 30f, hy - 110f - (k % 3) * 18f, 60f, 18f, 0xEEFFFFFF.toInt())
            }
            around(-1.0f)?.let { gfx.oval(it - 16f, hy - 170f, 32f, 32f, g.homePlanet.sun) }
        }
        // The ground you stand on
        if (roof) {
            // The city goes first: it's all below or beside the roof, so the roof deck covers it where it should.
            cityBlocks(gfx)
            poly(gfx, listOf(floatArrayOf(-0.5f, 0f, -0.5f), floatArrayOf(w + 0.5f, 0f, -0.5f), floatArrayOf(w + 0.5f, 0f, d + 0.5f), floatArrayOf(-0.5f, 0f, d + 0.5f)), 0xFF7A7A88.toInt())
            for (i in 0 until W / 3) poly(gfx, listOf(floatArrayOf(i * 3f, 0.002f, -0.5f), floatArrayOf(i * 3f + 0.06f, 0.002f, -0.5f), floatArrayOf(i * 3f + 0.06f, 0.002f, d + 0.5f), floatArrayOf(i * 3f, 0.002f, d + 0.5f)), 0xFF6A6A78.toInt())
            // The railing all around the edge
            val rail = 0xFFD8D8E0.toInt()
            for ((a, b) in listOf(floatArrayOf(-0.5f, -0.5f) to floatArrayOf(w + 0.5f, -0.5f), floatArrayOf(w + 0.5f, -0.5f) to floatArrayOf(w + 0.5f, d + 0.5f),
                    floatArrayOf(w + 0.5f, d + 0.5f) to floatArrayOf(-0.5f, d + 0.5f), floatArrayOf(-0.5f, d + 0.5f) to floatArrayOf(-0.5f, -0.5f))) {
                val mx = (a[0] + b[0]) / 2; val mz = (a[1] + b[1]) / 2
                val c = cam(mx, 1f, mz)
                items += Item(c[2] + 3f) {
                    poly(gfx, listOf(floatArrayOf(a[0], 1.05f, a[1]), floatArrayOf(b[0], 1.05f, b[1]), floatArrayOf(b[0], 1.15f, b[1]), floatArrayOf(a[0], 1.15f, a[1])), rail)
                    poly(gfx, listOf(floatArrayOf(a[0], 0.55f, a[1]), floatArrayOf(b[0], 0.55f, b[1]), floatArrayOf(b[0], 0.6f, b[1]), floatArrayOf(a[0], 0.6f, a[1])), shade(rail, 0.85f))
                }
            }
            picture(gfx, items, Roof.SCOPE_X + 12f, 1.5f) { sg -> Roof.drawTelescope(sg, 0f) }
            picture(gfx, items, Homes.STAIRS_X + 7f, 1.5f) { sg -> Roof.drawHatch(sg, Homes.STAIRS_X - 6f, Homes.FLOOR_Y) }
        } else {
            poly(gfx, listOf(floatArrayOf(-60f, 0f, -60f), floatArrayOf(w + 60f, 0f, -60f), floatArrayOf(w + 60f, 0f, d + 60f), floatArrayOf(-60f, 0f, d + 60f)), g.homePlanet.ground)
            // The white fence along the back, and the house's back wall at the start
            poly(gfx, listOf(floatArrayOf(0f, 0.9f, 0.02f), floatArrayOf(w, 0.9f, 0.02f), floatArrayOf(w, 1.0f, 0.02f), floatArrayOf(0f, 1.0f, 0.02f)), 0xFFE4E4E4.toInt())
            poly(gfx, listOf(floatArrayOf(0f, 0.45f, 0.02f), floatArrayOf(w, 0.45f, 0.02f), floatArrayOf(w, 0.55f, 0.02f), floatArrayOf(0f, 0.55f, 0.02f)), 0xFFE4E4E4.toInt())
            var fx = 0.1f
            while (fx < w) {
                poly(gfx, listOf(floatArrayOf(fx, 0f, 0.01f), floatArrayOf(fx + 0.25f, 0f, 0.01f), floatArrayOf(fx + 0.25f, 1.2f, 0.01f), floatArrayOf(fx, 1.2f, 0.01f)), 0xFFF4F4F4.toInt())
                fx += 1f
            }
            poly(gfx, listOf(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 0f, d), floatArrayOf(0f, 3f, d), floatArrayOf(0f, 3f, 0f)), 0xFFC8A070.toInt())
            val gd = g.garden
            picture(gfx, items, Garden.DOOR_X + 20f, 0.3f) { gd.drawBackDoor(it, 0f) }
            picture(gfx, items, Garden.SEEDS_X + 35f, 1.0f) { gd.drawSeedStand(it, 0f) }
            for (i in 0 until Garden.PLOTS) picture(gfx, items, Garden.plotX(i) + Garden.PLOT_W / 2, 3f) { gd.drawPlot(it, i, 0f, time) }
            picture(gfx, items, Garden.YARD_X - 14f, 1.0f) { gd.drawYardSign(it, 0f) }
            picture(gfx, items, Garden.PICNIC_X + 30f, 4f) { gd.drawPicnic(it, 0f) }
            picture(gfx, items, Pumpkins.TABLE_X + Pumpkins.TABLE_W / 2, 1.2f) { Pumpkins.drawTable(it, Pumpkins.TABLE_X, Homes.FLOOR_Y) }
            for (l in g.lanterns) picture(gfx, items, l.x + Pumpkins.LANTERN_W / 2, 5.5f) { Pumpkins.draw(it, l, l.x, Homes.FLOOR_Y, time) }
        }
        for (dd in g.decor) {
            if (dd.floor != loadedArea) continue
            val depth = when (dd.item.layer) { 1 -> 0.4f; 2 -> 4.5f; else -> 2.6f }
            picture(gfx, items, dd.x + dd.item.w / 2, depth) { Decor.draw(g.painted(it, dd.color), dd.item, dd.x, Homes.FLOOR_Y, time) }
        }
    }

    /** The city below the roof: tall buildings all around (made once). */
    private class Tower(val x0: Float, val z0: Float, val x1: Float, val z1: Float, val top: Float, val color: Int)
    private var towers: List<Tower> = emptyList()
    private var towersFor = -1

    private fun cityBlocks(gfx: Gfx) {
        val items = ArrayList<Item>()
        val floors = g.houseFloors
        if (towersFor != floors) {
            towersFor = floors
            val r = kotlin.random.Random(9)
            val list = ArrayList<Tower>()
            val colors = intArrayOf(0xFF4A4A6A.toInt(), 0xFF5A6A8A.toInt(), 0xFF6A5A7A.toInt(), 0xFF3A4A5A.toInt(), 0xFF7A6A6A.toInt())
            for (gx in -6..8) for (gz in -6..5) {
                val cx = gx * 7f; val cz = gz * 7f
                if (cx > -14f && cx < W + 8f && cz > -14f && cz < D + 8f) continue
                if (r.nextInt(5) == 0) continue
                val bw = 3f + r.nextInt(3); val bd = 3f + r.nextInt(3)
                list += Tower(cx, cz, cx + bw, cz + bd, -floors * 3f + 2f + r.nextInt(floors * 3 + 8), colors[r.nextInt(colors.size)])
            }
            towers = list
        }
        val ground = -g.houseFloors * 3f - 4f
        for (t in towers) {
            val c = cam((t.x0 + t.x1) / 2, t.top, (t.z0 + t.z1) / 2)
            if (c[2] < -4f) continue
            // Off to the side, out of view: skip it (keeps the 3D roof fast on phones).
            if (c[2] > 0f && abs(c[0]) > c[2] * midX / focal + 5f) continue
            val windows = if (c[2] < 22f) 3 else if (c[2] < 40f) 1 else 0
            items += Item(c[2]) {
                val col = t.color
                fun side(a: FloatArray, b: FloatArray, k: Float) {
                    poly(gfx, listOf(floatArrayOf(a[0], ground, a[1]), floatArrayOf(b[0], ground, b[1]), floatArrayOf(b[0], t.top, b[1]), floatArrayOf(a[0], t.top, a[1])), shade(col, k))
                    // Rows of lit windows
                    var wy = t.top - 1f
                    var n = 0
                    while (wy > maxOf(ground, t.top - 9f) && n < windows) {
                        poly(gfx, listOf(floatArrayOf(a[0] + (b[0] - a[0]) * 0.1f, wy, a[1] + (b[1] - a[1]) * 0.1f), floatArrayOf(a[0] + (b[0] - a[0]) * 0.9f, wy, a[1] + (b[1] - a[1]) * 0.9f),
                            floatArrayOf(a[0] + (b[0] - a[0]) * 0.9f, wy + 0.45f, a[1] + (b[1] - a[1]) * 0.9f), floatArrayOf(a[0] + (b[0] - a[0]) * 0.1f, wy + 0.45f, a[1] + (b[1] - a[1]) * 0.1f)), 0xFFFFE07A.toInt())
                        wy -= 1.6f; n++
                    }
                }
                if (ez < t.z0) side(floatArrayOf(t.x0, t.z0), floatArrayOf(t.x1, t.z0), 0.95f)
                if (ez > t.z1) side(floatArrayOf(t.x1, t.z1), floatArrayOf(t.x0, t.z1), 0.95f)
                if (ex < t.x0) side(floatArrayOf(t.x0, t.z1), floatArrayOf(t.x0, t.z0), 0.8f)
                if (ex > t.x1) side(floatArrayOf(t.x1, t.z0), floatArrayOf(t.x1, t.z1), 0.8f)
                if (ey > t.top) poly(gfx, listOf(floatArrayOf(t.x0, t.top, t.z0), floatArrayOf(t.x1, t.top, t.z0), floatArrayOf(t.x1, t.top, t.z1), floatArrayOf(t.x0, t.top, t.z1)), shade(col, 1.15f))
            }
        }
        items.sortByDescending { it.dist }
        for (it in items) it.draw()
    }

    private fun drawBlocksAndRest(gfx: Gfx, viewW: Float, viewH: Float, items: ArrayList<Item>) {
        val time = g.time
        // Your Pokemon.
        run {
            val c = cam(monX, 0f, monZ)
            if (c[2] > 0.3f) items += Item(c[2]) {
                val k = focal / c[2] / PX
                val ax = midX + c[0] / c[2] * focal
                val ay = midY - c[1] / c[2] * focal
                TeamArt.mon(ScaledGfx(gfx, k * 1.4f, ax, ay), g.mon, 0f, 0f, time, 1, false, abs(x - monX) + abs(z - monZ) > 1.7f)
            }
        }
        // Blocks: only the sides facing you, and not the ones hidden against another block.
        for ((k, type) in blocks) {
            val bx = kx(k)
            val by = ky(k)
            val bz = kz(k)
            val color = Block3D.entries.getOrElse(type) { Block3D.PLANKS }.color
            val glass = type == Block3D.GLASS.ordinal
            fun open(nx: Int, ny: Int, nz: Int): Boolean {
                val n = blocks[key(nx, ny, nz)] ?: return true
                return n == Block3D.GLASS.ordinal && !glass
            }
            val x0 = bx.toFloat(); val y0 = by.toFloat(); val z0 = bz.toFloat()
            val x1 = x0 + 1; val y1 = y0 + 1; val z1 = z0 + 1
            fun face(cx: Float, cy: Float, cz: Float, pts: List<FloatArray>, k2: Float) {
                val c = cam(cx, cy, cz)
                if (c[2] < -1f) return
                items += Item(c[2]) {
                    poly(gfx, pts, shade(color, k2))
                    // A darker edge so blocks look like blocks.
                    poly(gfx, listOf(pts[0], pts[1], lerp(pts[1], pts[2], 0.06f), lerp(pts[0], pts[3], 0.06f)), shade(color, k2 * 0.7f))
                }
            }
            if (ey > y1 && open(bx, by + 1, bz)) face(x0 + 0.5f, y1, z0 + 0.5f, listOf(floatArrayOf(x0, y1, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1)), 1.1f)
            if (ey < y0 && open(bx, by - 1, bz)) face(x0 + 0.5f, y0, z0 + 0.5f, listOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y0, z1), floatArrayOf(x0, y0, z1)), 0.6f)
            if (ex < x0 && open(bx - 1, by, bz)) face(x0, y0 + 0.5f, z0 + 0.5f, listOf(floatArrayOf(x0, y0, z0), floatArrayOf(x0, y0, z1), floatArrayOf(x0, y1, z1), floatArrayOf(x0, y1, z0)), 0.85f)
            if (ex > x1 && open(bx + 1, by, bz)) face(x1, y0 + 0.5f, z0 + 0.5f, listOf(floatArrayOf(x1, y0, z0), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x1, y1, z0)), 0.85f)
            if (ez < z0 && open(bx, by, bz - 1)) face(x0 + 0.5f, y0 + 0.5f, z0, listOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0)), 0.95f)
            if (ez > z1 && open(bx, by, bz + 1)) face(x0 + 0.5f, y0 + 0.5f, z1, listOf(floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1)), 0.95f)
        }
        // Moving platforms: yellow slabs with dark stripes.
        for (m in movers) {
            val c = cam(m.x0 + 0.5f, m.top, m.z0 + 0.5f)
            if (c[2] < -1f) continue
            items += Item(c[2]) {
                val x0 = m.x0; val z0 = m.z0; val y1 = m.top; val y0 = y1 - 0.25f
                poly(gfx, listOf(floatArrayOf(x0, y0, z0), floatArrayOf(x0 + 1, y0, z0), floatArrayOf(x0 + 1, y1, z0), floatArrayOf(x0, y1, z0)), 0xFFB8781A.toInt())
                poly(gfx, listOf(floatArrayOf(x0, y0, z0 + 1), floatArrayOf(x0 + 1, y0, z0 + 1), floatArrayOf(x0 + 1, y1, z0 + 1), floatArrayOf(x0, y1, z0 + 1)), 0xFFB8781A.toInt())
                poly(gfx, listOf(floatArrayOf(x0, y1, z0), floatArrayOf(x0 + 1, y1, z0), floatArrayOf(x0 + 1, y1, z0 + 1), floatArrayOf(x0, y1, z0 + 1)), 0xFFE8A82A.toInt())
                for (k in 0 until 2) poly(gfx, listOf(floatArrayOf(x0 + 0.2f + k * 0.45f, y1 + 0.005f, z0), floatArrayOf(x0 + 0.4f + k * 0.45f, y1 + 0.005f, z0), floatArrayOf(x0 + 0.4f + k * 0.45f, y1 + 0.005f, z0 + 1), floatArrayOf(x0 + 0.2f + k * 0.45f, y1 + 0.005f, z0 + 1)), 0xFF2A2A34.toInt())
            }
        }
        // Rocco himself (third person), doing his parkour.
        if (thirdPerson) {
            val c = cam(x, feet, z)
            // (A bit nearer than his spot, so a platform he's standing on doesn't cover his feet.)
            if (c[2] > 0.3f) items += Item(c[2] - 0.6f) {
                val k = focal / c[2] / PX
                val ax = midX + c[0] / c[2] * focal
                val ay = midY - c[1] / c[2] * focal
                val sg = ScaledGfx(gfx, k * 1.6f, ax, ay)
                // A shadow on the ground under him.
                val sh = cam(x, groundUnder(x, z, feet), z)
                if (sh[2] > 0.3f) gfx.oval(midX + sh[0] / sh[2] * focal - 12f * focal / sh[2] / PX * 1.6f, midY - sh[1] / sh[2] * focal - 3f * focal / sh[2] / PX * 1.6f, 24f * focal / sh[2] / PX * 1.6f, 6f * focal / sh[2] / PX * 1.6f, 0x55000000)
                val frame = if (!onGround) 2 else if (walking && (t * 8).toInt() % 2 == 0) 1 else 0
                val spr = Outline.of(TeamArt.rocco(g.power, frame))
                val flipX = faceDir < 0
                when {
                    flipT > 0f -> sg.spriteRotated(spr, -8f, -24f, 16f, 24f, 360f * (1f - flipT / flipDur) * faceDir, flipX)
                    rollT > 0f -> sg.spriteRotated(spr, -8f, -18f, 16f, 18f, 360f * (1f - rollT / 0.45f) * faceDir, flipX)
                    vaultT > 0f -> sg.spriteRotated(spr, -8f, -24f, 16f, 24f, 60f * faceDir, flipX)
                    else -> sg.sprite(spr, -8f, -24f, flipX)
                }
            }
        }
        items.sortByDescending { it.dist }
        for (it in items) it.draw()
        // Where a new block would go.
        target().place?.let { (bx, by, bz) ->
            val c = (if (selected == MOVER_SLOT) 0xE8A82A else Block3D.entries[selected].color and 0xFFFFFF) or 0x55000000
            val x0 = bx.toFloat(); val y0 = by.toFloat(); val z0 = bz.toFloat()
            poly(gfx, listOf(floatArrayOf(x0, y0 + 0.01f, z0), floatArrayOf(x0 + 1, y0 + 0.01f, z0), floatArrayOf(x0 + 1, y0 + 0.01f, z0 + 1), floatArrayOf(x0, y0 + 0.01f, z0 + 1)), c)
            poly(gfx, listOf(floatArrayOf(x0, y0, z0 + 1), floatArrayOf(x0 + 1, y0, z0 + 1), floatArrayOf(x0 + 1, y0 + 1, z0 + 1), floatArrayOf(x0, y0 + 1, z0 + 1)), c)
        }
        // Crosshair
        gfx.rect(midX - 6, midY - 0.75f, 12f, 1.5f, 0xCCFFFFFF.toInt())
        gfx.rect(midX - 0.75f, midY - 6, 1.5f, 12f, 0xCCFFFFFF.toInt())
        drawUi(gfx, viewW, viewH)
    }

    private fun lerp(a: FloatArray, b: FloatArray, k: Float) = floatArrayOf(a[0] + (b[0] - a[0]) * k, a[1] + (b[1] - a[1]) * k, a[2] + (b[2] - a[2]) * k)

    // ---------------------------------------------------------------- Buttons

    fun paletteX(i: Int, viewW: Float) = viewW / 2 - (Block3D.entries.size + 1) * 11f + i * 22f

    private fun drawUi(gfx: Gfx, viewW: Float, viewH: Float) {
        gfx.rect(0f, 0f, viewW, 22f, 0xCC1A1008.toInt())
        gfx.shadowText(when (loadedArea) { Decor.YARD -> "3D GARDEN"; Decor.ROOF -> "3D ROOF"; else -> "3D HOUSE  ${g.homeFloor + 1}F" }, 6f, 15f, 8f, 0xFFFFD21F.toInt())
        for ((i, b) in Block3D.entries.withIndex()) {
            val px = paletteX(i, viewW)
            if (i == selected) gfx.rect(px - 2, 1f, 20f, 20f, 0xFFFFD21F.toInt())
            gfx.rect(px, 3f, 16f, 16f, b.color or 0xFF000000.toInt())
            gfx.rect(px, 3f, 16f, 4f, 0x33FFFFFF)
        }
        // The MOVER: a moving platform
        run {
            val px = paletteX(MOVER_SLOT, viewW)
            if (selected == MOVER_SLOT) gfx.rect(px - 2, 1f, 20f, 20f, 0xFFFFD21F.toInt())
            gfx.rect(px, 8f, 16f, 6f, 0xFFE8A82A.toInt())
            gfx.rect(px + 4, 8f, 3f, 6f, 0xFF2A2A34.toInt()); gfx.rect(px + 10, 8f, 3f, 6f, 0xFF2A2A34.toInt())
            gfx.poly(floatArrayOf(px + 1f, px + 5f, px + 5f), floatArrayOf(17f, 15f, 19f), 0xFFFFFFFF.toInt())
            gfx.poly(floatArrayOf(px + 15f, px + 11f, px + 11f), floatArrayOf(17f, 15f, 19f), 0xFFFFFFFF.toInt())
        }
        gfx.shadowText(if (selected == MOVER_SLOT) "MOVING PLATFORM" else Block3D.entries[selected].label, viewW / 2, 31f, 6f, 0xFFFFFFFF.toInt(), 1)
        // See Rocco (third person) or look through his eyes.
        gfx.rect(4f, 26f, 54f, 16f, 0xCC1A1008.toInt())
        gfx.text(if (thirdPerson) "VIEW: ROCCO" else "VIEW: EYES", 31f, 37f, 5.5f, 0xFFFFFFFF.toInt(), 1)
        // 2D button, and floor buttons
        gfx.rect(viewW - 36f, 2f, 32f, 18f, 0xFF2EB84A.toInt())
        gfx.text("2D", viewW - 20f, 15f, 9f, 0xFFFFFFFF.toInt(), 1)
        if (!outside) {
            gfx.rect(viewW - 74f, 2f, 34f, 18f, 0xFF3A6AC8.toInt())
            gfx.text(if (g.houseFloors > g.homeFloor + 1) "UP" else "ROOF", viewW - 57f, 15f, 7f, 0xFFFFFFFF.toInt(), 1)
        }
        if ((!outside && g.homeFloor > 0) || loadedArea == Decor.ROOF) { gfx.rect(viewW - 112f, 2f, 34f, 18f, 0xFF3A6AC8.toInt()); gfx.text("DOWN", viewW - 95f, 15f, 7f, 0xFFFFFFFF.toInt(), 1) }
        TeamControls.draw3d(gfx, g.inputForDraw(), viewW, viewH)
    }

    /** Taps on the top bar: pick a block, go up or down, or back to 2D. Returns true if it was used. */
    fun tap(tx: Float, ty: Float, viewW: Float): Boolean {
        if (tx < 60f && ty in 24f..44f) { thirdPerson = !thirdPerson; g.sfx(Sound.KICK); return true }
        if (ty > 24f) return false
        for (i in 0..MOVER_SLOT) if (tx in paletteX(i, viewW) - 2..paletteX(i, viewW) + 18) { selected = i; g.sfx(Sound.KICK); return true }
        when {
            tx > viewW - 38f -> g.exit3d()
            tx in (viewW - 76f)..(viewW - 38f) && !outside && g.houseFloors > g.homeFloor + 1 -> { saveFloor(); g.changeFloor(g.homeFloor + 1); enter() }
            tx in (viewW - 76f)..(viewW - 38f) && !outside -> { saveFloor(); g.enterRoof(); enter() }
            tx in (viewW - 114f)..(viewW - 76f) && loadedArea == Decor.ROOF -> { saveFloor(); g.leaveRoof(); enter() }
            tx in (viewW - 114f)..(viewW - 76f) && !outside && g.homeFloor > 0 -> { saveFloor(); g.changeFloor(g.homeFloor - 1); enter() }
            else -> return false
        }
        return true
    }
}
