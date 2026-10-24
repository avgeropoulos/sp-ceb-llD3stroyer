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
        private const val NEAR = 0.08f
    }

    var x = 3f
    var z = 7f
    var yaw = -Math.PI.toFloat() / 2
    var pitch = -0.15f
    var selected = 0
    /** Blocks on the floor that's loaded: (x, y, z) packed by [key] -> block type. */
    val blocks = HashMap<Int, Int>()
    private var loadedFloor = -1
    private var monX = 4f
    private var monZ = 6f
    private var t = 0f

    fun key(bx: Int, by: Int, bz: Int) = bx or (by shl 5) or (bz shl 8)

    fun enter() {
        loadFloor(g.homeFloor)
        x = (g.homeX / PX).coerceIn(1.5f, W - 1.5f)
        z = 7f
        yaw = -Math.PI.toFloat() / 2
        pitch = -0.15f
        monX = x + 1f
        monZ = z - 1f
    }

    fun loadFloor(floor: Int) {
        blocks.clear()
        val n = g.slotLoad("b3d${floor}n").coerceIn(0, MAX_BLOCKS)
        for (i in 0 until n) {
            val v = g.slotLoad("b3d${floor}_$i") - 1
            if (v >= 0) blocks[v and 0xFFF] = (v shr 12) and 0xF
        }
        loadedFloor = floor
    }

    private fun saveFloor() {
        var i = 0
        for ((k, type) in blocks) { g.slotSave("b3d${loadedFloor}_$i", (k or (type shl 12)) + 1); i++ }
        g.slotSave("b3d${loadedFloor}n", i)
    }

    // ---------------------------------------------------------------- Moving and building

    private fun solidAt(px: Float, pz: Float): Boolean {
        val bx = floor(px).toInt()
        val bz = floor(pz).toInt()
        return blocks.containsKey(key(bx, 0, bz)) || blocks.containsKey(key(bx, 1, bz))
    }

    fun update(dt: Float, input: TeamInput, place: Boolean, breakIt: Boolean) {
        t += dt
        if (loadedFloor != g.homeFloor) { loadFloor(g.homeFloor); x = 3f; z = 7f }
        if (input.left) yaw -= 2.2f * dt
        if (input.right) yaw += 2.2f * dt
        if (input.lookUp) pitch = (pitch + 1.2f * dt).coerceAtMost(0.9f)
        if (input.lookDown) pitch = (pitch - 1.2f * dt).coerceAtLeast(-1.2f)
        val move = (if (input.up) 1f else 0f) - (if (input.down) 1f else 0f)
        if (move != 0f) {
            val sp = 3.2f * move * dt
            val nx = (x + cos(yaw) * sp).coerceIn(0.3f, W - 0.3f)
            val nz = (z + sin(yaw) * sp).coerceIn(0.3f, D - 0.3f)
            if (!solidAt(nx, z)) x = nx
            if (!solidAt(x, nz)) z = nz
        }
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
            } else if (place && hit.place != null) {
                val (bx, by, bz) = hit.place
                val standing = floor(x).toInt() == bx && floor(z).toInt() == bz && by <= 1
                when {
                    standing -> g.sfx(Sound.BUMP)
                    blocks.size >= MAX_BLOCKS -> { g.sfx(Sound.BUMP); g.toastFrom3D("That's a lot of blocks! Break some to build more.") }
                    else -> { blocks[key(bx, by, bz)] = selected; g.sfx(Sound.KICK); saveFloor() }
                }
            }
        }
    }

    class Hit(val block: Int?, val place: Triple<Int, Int, Int>?)

    private fun dir(): Triple<Float, Float, Float> = Triple(cos(yaw) * cos(pitch), sin(pitch), sin(yaw) * cos(pitch))

    /** What you're looking at: a block to break, and the empty spot in front of it to put a new one. */
    fun target(): Hit {
        val (fx, fy, fz) = dir()
        var prev: Triple<Int, Int, Int>? = null
        var s = 0.2f
        while (s < 6f) {
            val px = x + fx * s
            val py = 1.6f + fy * s
            val pz = z + fz * s
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

    private fun inRoom(bx: Int, by: Int, bz: Int) = bx in 0 until W && by in 0 until H && bz in 0 until D

    // ---------------------------------------------------------------- Drawing

    private var cyw = 1f; private var syw = 0f; private var cp = 1f; private var sp = 0f
    private var focal = 200f; private var midX = 200f; private var midY = 120f

    /** Camera-space coordinates of a world point: right, up, forward. */
    private fun cam(wx: Float, wy: Float, wz: Float): FloatArray {
        val dx = wx - x; val dy = wy - 1.6f; val dz = wz - z
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
        focal = viewH * 0.85f
        midX = viewW / 2
        midY = viewH / 2
        val (wall, stripe, floorC) = Homes.roomColors(g.houseType, g.housePaint)
        gfx.rect(0f, 0f, viewW, viewH, 0xFF101018.toInt())
        val h = H.toFloat()
        val w = W.toFloat()
        val d = D.toFloat()
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
        val items = ArrayList<Item>()
        // The 2D pictures (furniture, shop, bed, shelves...) stand up in the room, facing you.
        fun picture(cx2: Float, wz: Float, draw: (Gfx) -> Unit) {
            val wx = cx2 / PX
            val c = cam(wx, 0f, wz)
            if (c[2] < 0.3f) return
            items += Item(c[2]) {
                val k = focal / c[2] / PX
                val ax = midX + c[0] / c[2] * focal
                val ay = midY - c[1] / c[2] * focal
                draw(ScaledGfx(gfx, k, ax - cx2 * k, ay - Homes.FLOOR_Y * k))
            }
        }
        val fl = g.homeFloor
        val time = g.time
        picture(Homes.BED_X + 26f, 0.05f) { Homes.drawWindow(it, Homes.BED_X - 2f, 40f, time) }
        if (fl == 0) {
            picture(Homes.DOOR_X + 15f, 0.05f) { Homes.drawDoor(it, Homes.DOOR_X) }
            picture(Homes.SHOP_X + 33f, 1.2f) { Homes.drawShop(g, it, Homes.SHOP_X, time) }
            picture(Homes.MON_X + 22f, 1.5f) { Homes.drawMonMat(it, 0f, time) }
            if (g.hasBed()) picture(Homes.BED_X + 28f, 1.3f) { Homes.drawBed(it, g.bedStyle, Homes.BED_X) }
        } else picture(Homes.DOOR_X + 12f, 0.3f) { Homes.drawStairs(it, Homes.DOOR_X - 6f, false) }
        if (g.houseFloors > fl + 1) picture(Homes.STAIRS_X + 12f, 0.3f) { Homes.drawStairs(it, Homes.STAIRS_X - 4f, true) }
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
            val bx = k and 31
            val by = (k shr 5) and 7
            val bz = (k shr 8) and 15
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
            if (1.6f > y1 && open(bx, by + 1, bz)) face(x0 + 0.5f, y1, z0 + 0.5f, listOf(floatArrayOf(x0, y1, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1)), 1.1f)
            if (1.6f < y0 && open(bx, by - 1, bz)) face(x0 + 0.5f, y0, z0 + 0.5f, listOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y0, z1), floatArrayOf(x0, y0, z1)), 0.6f)
            if (x < x0 && open(bx - 1, by, bz)) face(x0, y0 + 0.5f, z0 + 0.5f, listOf(floatArrayOf(x0, y0, z0), floatArrayOf(x0, y0, z1), floatArrayOf(x0, y1, z1), floatArrayOf(x0, y1, z0)), 0.85f)
            if (x > x1 && open(bx + 1, by, bz)) face(x1, y0 + 0.5f, z0 + 0.5f, listOf(floatArrayOf(x1, y0, z0), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x1, y1, z0)), 0.85f)
            if (z < z0 && open(bx, by, bz - 1)) face(x0 + 0.5f, y0 + 0.5f, z0, listOf(floatArrayOf(x0, y0, z0), floatArrayOf(x1, y0, z0), floatArrayOf(x1, y1, z0), floatArrayOf(x0, y1, z0)), 0.95f)
            if (z > z1 && open(bx, by, bz + 1)) face(x0 + 0.5f, y0 + 0.5f, z1, listOf(floatArrayOf(x0, y0, z1), floatArrayOf(x1, y0, z1), floatArrayOf(x1, y1, z1), floatArrayOf(x0, y1, z1)), 0.95f)
        }
        items.sortByDescending { it.dist }
        for (it in items) it.draw()
        // Where a new block would go.
        target().place?.let { (bx, by, bz) ->
            val c = (Block3D.entries[selected].color and 0xFFFFFF) or 0x55000000
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

    fun paletteX(i: Int, viewW: Float) = viewW / 2 - Block3D.entries.size * 11f + i * 22f

    private fun drawUi(gfx: Gfx, viewW: Float, viewH: Float) {
        gfx.rect(0f, 0f, viewW, 22f, 0xCC1A1008.toInt())
        gfx.shadowText("3D HOUSE  ${g.homeFloor + 1}F", 6f, 15f, 8f, 0xFFFFD21F.toInt())
        for ((i, b) in Block3D.entries.withIndex()) {
            val px = paletteX(i, viewW)
            if (i == selected) gfx.rect(px - 2, 1f, 20f, 20f, 0xFFFFD21F.toInt())
            gfx.rect(px, 3f, 16f, 16f, b.color or 0xFF000000.toInt())
            gfx.rect(px, 3f, 16f, 4f, 0x33FFFFFF)
        }
        gfx.shadowText(Block3D.entries[selected].label, viewW / 2, 31f, 6f, 0xFFFFFFFF.toInt(), 1)
        // 2D button, and floor buttons
        gfx.rect(viewW - 36f, 2f, 32f, 18f, 0xFF2EB84A.toInt())
        gfx.text("2D", viewW - 20f, 15f, 9f, 0xFFFFFFFF.toInt(), 1)
        if (g.houseFloors > g.homeFloor + 1) { gfx.rect(viewW - 74f, 2f, 34f, 18f, 0xFF3A6AC8.toInt()); gfx.text("UP", viewW - 57f, 15f, 8f, 0xFFFFFFFF.toInt(), 1) }
        if (g.homeFloor > 0) { gfx.rect(viewW - 112f, 2f, 34f, 18f, 0xFF3A6AC8.toInt()); gfx.text("DOWN", viewW - 95f, 15f, 7f, 0xFFFFFFFF.toInt(), 1) }
        TeamControls.draw3d(gfx, g.inputForDraw(), viewW, viewH)
    }

    /** Taps on the top bar: pick a block, go up or down, or back to 2D. Returns true if it was used. */
    fun tap(tx: Float, ty: Float, viewW: Float): Boolean {
        if (ty > 24f) return false
        for (i in Block3D.entries.indices) if (tx in paletteX(i, viewW) - 2..paletteX(i, viewW) + 18) { selected = i; g.sfx(Sound.KICK); return true }
        when {
            tx > viewW - 38f -> g.exit3d()
            tx in (viewW - 76f)..(viewW - 38f) && g.houseFloors > g.homeFloor + 1 -> { saveFloor(); g.changeFloor(g.homeFloor + 1); enter() }
            tx in (viewW - 114f)..(viewW - 76f) && g.homeFloor > 0 -> { saveFloor(); g.changeFloor(g.homeFloor - 1); enter() }
            else -> return false
        }
        return true
    }
}
