package com.javelinstrike.game

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/* The heads-up display and menus, laid out for a phone held upright. */

private const val PANEL = 0xE6201510.toInt()
private const val TERRACOTTA = 0xFFB5482E.toInt()
private const val CREAM = 0xFFF3E6C4.toInt()
private const val INK = 0xFF1A120B.toInt()

internal fun Game.drawInset(g: Gfx) {
    val b = insetBox()
    insetCam.apply {
        x = target.x
        y = 13f
        z = target.z - 11f
        pitch = 0.85f
        focal = 100f
        cx = b[0] + b[2] / 2f
        cy = b[1] + b[3] / 2f + 8f
    }
    g.rect(b[0] - 3f, b[1] - 3f, b[2] + 6f, b[3] + 6f, Game.GOLD)
    g.rect(b[0] - 1f, b[1] - 1f, b[2] + 2f, b[3] + 2f, INK)
    g.clip(b[0], b[1], b[2], b[3])
    drawWorld(g, insetCam, main = false, b[0], b[1], b[2], b[3])
    g.unclip()
    g.rect(b[0], b[1], b[2], 13f, 0xB0000000.toInt())
    g.text("TARGET  ${target.z.roundToInt()} m", b[0] + 4f, b[1] + 10f, 9f, Game.WHITE)
    if (target.moving) g.text("MOVING", b[0] + b[2] - 4f, b[1] + 10f, 8f, Game.ORANGE, 2)
}

internal fun Game.drawHud(g: Gfx) {
    g.rect(0f, -safeTop, viewW, 76f + safeTop, 0x50000000)
    g.shadowText("LEVEL $levelNum", 10f, 22f, 16f, Game.GOLD, serif = true)
    g.shadowText(spec.name.uppercase(), 10f, 37f, 9f, Game.WHITE)
    for (i in 0 until lives) wreath(g, 18f + i * 22f, 54f)

    // Clock in the middle
    val secs = ceil(timeLeft).toInt()
    val clockColor = if (secs <= 10 && (secs > 5 || (clock * 4f).toInt() % 2 == 0)) Game.RED else Game.WHITE
    g.shadowText("${secs / 60}:${(secs % 60).toString().padStart(2, '0')}", viewW / 2f + 16f, 30f, 26f, clockColor, 1)
    val pip = 13f
    val startX = viewW / 2f + 16f - (spec.hits * pip) / 2f
    for (i in 0 until spec.hits) {
        g.oval(startX + i * pip + 1f, 39f, pip - 3f, pip - 3f, INK)
        g.oval(startX + i * pip + 2f, 40f, pip - 5f, pip - 5f, if (i < hits) Game.GOLD else 0x80FFFFFF.toInt())
    }

    // Pause button and score on the right
    val p = pauseButton()
    g.rect(p[0], p[1], p[2], p[3], 0xA0000000.toInt())
    g.rect(p[0] + 12f, p[1] + 8f, 4f, 16f, Game.WHITE)
    g.rect(p[0] + 20f, p[1] + 8f, 4f, 16f, Game.WHITE)
    g.shadowText("$score", viewW - 52f, 24f, 14f, Game.WHITE, 2)
    g.shadowText("SCORE", viewW - 52f, 36f, 8f, Game.GOLD, 2)

    // Wind, on the left under the lives
    if (wind != 0f) {
        val wy = 92f
        g.shadowText("WIND", 10f, wy, 10f, Game.WHITE)
        val len = 8f + abs(wind) * 4f
        val ax = 44f + if (wind > 0) 0f else len + 6f
        val dir = if (wind > 0) 1f else -1f
        g.line(ax, wy - 4f, ax + dir * len, wy - 4f, 3f, Game.CYAN)
        g.poly(
            floatArrayOf(ax + dir * (len + 6f), ax + dir * len, ax + dir * len),
            floatArrayOf(wy - 4f, wy - 9f, wy + 1f), Game.CYAN,
        )
        g.shadowText("${oneDecimal(abs(wind))} m/s", 44f + len + 14f, wy, 10f, Game.CYAN)
    }
    if (lastDistance > 0f) {
        g.shadowText("LAST ${oneDecimal(lastDistance)} m", 10f, 110f, 10f, Game.WHITE)
        g.shadowText("POWER ${(lastPower * 100).roundToInt()}%", 10f, 124f, 10f, Game.WHITE)
    }

    if (screen == Game.Screen.PLAY && !paused) drawThrowGauges(g)

    if (msgT > 0f) {
        val a = min(1f, msgT * 3f)
        val y = viewH * 0.3f + 20f
        g.text(msg, viewW / 2f + 2f, y + 2f, 30f, argb((a * 200).toInt(), 0), 1, serif = true)
        g.text(msg, viewW / 2f, y, 30f, argb((a * 255).toInt(), msgColor), 1, serif = true)
        if (msgSub.isNotEmpty()) g.shadowText(msgSub, viewW / 2f, y + 22f, 14f, argb((a * 255).toInt(), Game.WHITE), 1)
    }
}

/** A laurel wreath: one life. */
private fun wreath(g: Gfx, x: Float, y: Float) {
    for (side in intArrayOf(-1, 1)) {
        for (k in 0 until 5) {
            val a = 0.5f + k * 0.55f
            val lx = x + side * sin(a) * 7f
            val ly = y + cos(a) * 7f
            g.oval(lx - 2.2f, ly - 1.6f, 4.4f, 3.2f, 0xFF7C9A3A.toInt())
        }
    }
    g.oval(x - 1.5f, y + 5f, 3f, 3f, Game.GOLD)
}

internal fun Game.drawPrompts(g: Gfx) {
    if (throwState != Game.ThrowState.READY || timeLeft <= 0f) return
    val draw = drawPose()
    val mh = 150f
    val mx = 12f
    val my = viewH - 70f - mh
    g.rect(mx - 2f, my - 2f, 18f, mh + 4f, 0xA0000000.toInt())
    val armed = controls == ControlMode.MOTION && detector.phase >= ThrowDetector.Phase.DRAWN ||
        controls == ControlMode.TOUCH && flickPower() > 0f
    val fill = mh * draw
    g.rect(mx, my + mh - fill, 14f, fill, if (armed) Game.GREEN else Game.GOLD)
    g.shadowText("DRAW", mx + 7f, my + mh + 14f, 8f, Game.WHITE, 1)

    val (line, sub) = if (controls == ControlMode.MOTION) {
        when (detector.phase) {
            ThrowDetector.Phase.IDLE -> "HOLD THE SCREEN" to
                (if (runUp) "Hold to run, then throw before the line" else if (hasTilt()) "Tilt to aim left and right" else "")
            ThrowDetector.Phase.GRIP -> "PULL YOUR ARM BACK" to "Keep holding the screen"
            ThrowDetector.Phase.DRAWN -> "NOW THROW!" to "Let go early, leaning back, to throw high"
            ThrowDetector.Phase.SWING -> "!!!" to ""
        }
    } else if (flickActive()) {
        (if (armed) "LET GO TO THROW" else "PULL BACK") to "Pull to one side to aim the other way"
    } else if (heightDragging) {
        "HEIGHT" to "Slide up to throw higher, down to throw flatter"
    } else {
        "PRESS ANYWHERE AND PULL BACK" to "Further = harder. Height: slider on the right"
    }
    // The run-up: speed and how far to the foul line
    if (runUp) {
        val toLine = -runnerZ
        val near = toLine < 3f
        g.shadowText(
            "RUN ${oneDecimal(runSpeed)} m/s   LINE ${oneDecimal(toLine)} m",
            46f, viewH - 176f, 10f, if (near) Game.RED else Game.WHITE,
        )
    }
    // The left/right aim, in numbers
    val side = if (aimYaw > 0.009f) "R" else if (aimYaw < -0.009f) "L" else ""
    val aimDeg = abs(aimYaw * 57.3f).roundToInt()
    g.shadowText("AIM ${if (aimDeg == 0) "STRAIGHT" else "$aimDeg° $side"}", 46f, viewH - 160f, 11f, Game.CYAN)
    if (msgT <= 0f) {
        val color = if (armed) Game.GREEN else Game.WHITE
        g.shadowText(line, viewW / 2f, viewH - 40f, 18f, color, 1)
        if (sub.isNotEmpty()) g.shadowText(sub, viewW / 2f, viewH - 22f, 11f, 0xFFEEE4CC.toInt(), 1)
    }
    if (target.moving && msgT <= 0f && lastDistance == 0f) {
        g.shadowText("It moves! Aim where it will be.", viewW / 2f, viewH - 62f, 10f, Game.ORANGE, 1)
    }
}

/** The power bar and the angle dial, shown all through a javelin level. */
internal fun Game.drawThrowGauges(g: Gfx) {
    drawPowerScale(g)
    if (controls == ControlMode.TOUCH) {
        if (throwState == Game.ThrowState.READY) drawFlightWindow(g)
        if (throwState == Game.ThrowState.READY) drawHeightSlider(g)
        if (flickActive()) drawRubberBand(g)
    } else {
        drawAngleDial(g)
    }
}

private val pathX = FloatArray(80)
private val pathY = FloatArray(80)
private val pathZ = FloatArray(80)

/**
 * The Angry Birds view: the flight seen from the side, from the thrower on the left to the
 * target on the right, with the dotted arc of the throw you're pulling back. On later levels
 * only the start of the arc shows.
 */
private fun Game.drawFlightWindow(g: Gfx) {
    val left = 40f
    val right = viewW - 12f
    val top = viewH - 300f
    val bottom = viewH - 190f
    val w = right - left
    g.rect(left - 2f, top - 2f, w + 4f, bottom - top + 4f, 0xC0201510.toInt())
    g.clip(left, top, w, bottom - top)
    g.rect(left, top, w, bottom - top, 0x803F86C8.toInt())
    g.rect(left, bottom - 8f, w, 8f, 0xFFD3AF73.toInt())
    val zMax = 150f
    val scale = (w - 16f) / (zMax - RUN_START)
    fun sx(z: Float) = left + 8f + (z - RUN_START) * scale
    fun sy(y: Float) = bottom - 8f - y * scale
    // Distance ticks
    if (hintLevel() < 2) {
        var d = 0
        while (d <= 150) {
            g.rect(sx(d.toFloat()) - 0.5f, bottom - 8f, 1f, 4f, 0xFF4A3420.toInt())
            if (d % 50 == 0) g.text("$d", sx(d.toFloat()), bottom - 1f, 7f, 0xFF4A3420.toInt(), 1)
            d += 10
        }
    }
    // Foul line, target and thrower
    g.rect(sx(0f) - 1f, bottom - 10f, 2f, 6f, 0xFFF0EADB.toInt())
    val tz = kotlin.math.hypot(target.x, target.z)
    g.rect(sx(tz - target.radius), bottom - 9f, target.radius * 2f * scale, 3f, 0xFFC1502E.toInt())
    g.line(sx(tz), bottom - 8f, sx(tz), sy(3.6f), 1.5f, 0xFF7A5230.toInt())
    g.oval(sx(tz) - 3.5f, sy(3.6f) - 3.5f, 7f, 7f, 0xFFCC8F35.toInt())
    val ax = sx(runnerZ)
    g.line(ax, bottom - 8f, ax, sy(1.8f), 3f, 0xFFC98B5B.toInt())
    g.oval(ax - 2.5f, sy(1.9f) - 2.5f, 5f, 5f, 0xFF2B1A10.toInt())
    // The arc for the pull you're making now
    if (flickActive()) {
        val p = flickPower()
        val n = flightPath(p, pathX, pathY, pathZ, 0.08f)
        val shown = kotlin.math.max(2, (n * arcShown()).toInt())
        for (i in 0 until kotlin.math.min(n, shown)) {
            val r = if (i % 3 == 0) 2.2f else 1.6f
            g.oval(sx(pathZ[i]) - r, sy(pathY[i]) - r, r * 2f, r * 2f, 0xF0FFFFFF.toInt())
        }
        if (shown >= n && hintLevel() < 2) {
            val lz = pathZ[n - 1]
            g.shadowText("${lz.roundToInt()} m", sx(lz), bottom - 12f, 8f, Game.GOLD, 1)
        }
    }
    g.unclip()
    val deg = (aimElev * 57.3f).roundToInt()
    g.shadowText("ANGLE $deg°", left + 4f, top + 11f, 9f, if (flickActive()) Game.GREEN else Game.WHITE)
    g.shadowText("SIDE VIEW", right - 4f, top + 11f, 8f, CREAM, 2)
}

/** The touch height slider: drag the bronze handle up for a higher throw, down for a flatter one. */
private fun Game.drawHeightSlider(g: Gfx) {
    val r = heightSlider()
    val cx = r[0] + r[2] / 2f
    g.rect(r[0] - 3f, r[1] - 16f, r[2] + 6f, r[3] + 32f, 0xA0000000.toInt())
    g.rect(cx - 2f, r[1], 4f, r[3], 0x80FFFFFF.toInt())
    for (deg in intArrayOf(15, 30, 45, 60, 70)) {
        val f = (deg * 0.017453f - MIN_ELEVATION) / (MAX_ELEVATION - MIN_ELEVATION)
        val y = r[1] + r[3] * (1f - f)
        g.rect(cx - 7f, y - 0.5f, 14f, 1f, 0xB0FFFFFF.toInt())
    }
    val f = (touchElev - MIN_ELEVATION) / (MAX_ELEVATION - MIN_ELEVATION)
    val hy = r[1] + r[3] * (1f - f)
    val color = if (heightDragging) Game.GREEN else Game.GOLD
    g.oval(cx - 11f, hy - 11f, 22f, 22f, 0xFF2A1A0E.toInt())
    g.oval(cx - 9f, hy - 9f, 18f, 18f, color)
    g.text("${(touchElev * 57.3f).roundToInt()}°", cx, hy + 3.5f, 9f, 0xFF2A1A0E.toInt(), 1)
    g.shadowText("HIGH", cx, r[1] - 5f, 7f, Game.WHITE, 1)
    g.shadowText("FLAT", cx, r[1] + r[3] + 12f, 7f, Game.WHITE, 1)
}

/** The slingshot's rubber band from where you pressed to your finger. */
private fun Game.drawRubberBand(g: Gfx) {
    val b = flickBand()
    val color = if (flickPower() > 0f) 0xE06B3A1C.toInt() else 0x906B3A1C.toInt()
    g.line(b[0] - 10f, b[1], b[2], b[3], 3f, color)
    g.line(b[0] + 10f, b[1], b[2], b[3], 3f, color)
    g.oval(b[0] - 13f, b[1] - 3f, 6f, 6f, 0xFF4A3420.toInt())
    g.oval(b[0] + 7f, b[1] - 3f, 6f, 6f, 0xFF4A3420.toInt())
    g.oval(b[2] - 7f, b[3] - 7f, 14f, 14f, 0xFF2A1A0E.toInt())
    g.oval(b[2] - 5f, b[3] - 5f, 10f, 10f, 0xFFCC8F35.toInt())
}

/**
 * How hard to throw: a 0-100% scale with the distance each power reaches at the current aim
 * and angle. It fills live as you swing and freezes at your release power. A gold mark shows
 * the power that reaches the target and a blue one your last throw.
 */
private fun Game.drawPowerScale(g: Gfx) {
    val x0 = 46f
    val x1 = viewW - 20f
    val y = viewH - 122f
    g.rect(x0 - 6f, y - 22f, x1 - x0 + 12f, 40f, 0x90000000.toInt())
    g.shadowText("POWER", x0, y - 10f, 8f, Game.WHITE)
    g.rect(x0, y - 3f, x1 - x0, 6f, 0x60FFFFFF)
    // Live fill: how hard you're throwing right now, then the power you let go with.
    val swinging = detector.phase == ThrowDetector.Phase.SWING || flickActive()
    val fill = when {
        flickActive() -> flickPower()
        swinging -> detector.livePower
        throwState != Game.ThrowState.READY -> lastPower
        else -> -1f
    }
    val hints = hintLevel()
    if (fill >= 0f) {
        g.rect(x0, y - 4f, (x1 - x0) * fill, 8f, if (swinging) Game.GREEN else Game.GOLD)
        val label = "${if (swinging) "NOW" else "THROWN"} ${(fill * 100).roundToInt()}%"
        g.shadowText(label, x1, y - 10f, 9f, if (swinging) Game.GREEN else Game.GOLD, 2)
    }
    for (i in 0..4) {
        val x = x0 + (x1 - x0) * i / 4f
        g.rect(x - 1f, y - 6f, 2f, 12f, Game.WHITE)
        // Later touch levels hide how far each power goes.
        val label = if (hints >= 2) "${i * 25}%" else "${kotlin.math.hypot(ruler[i * 2], ruler[i * 2 + 1]).roundToInt()}m"
        g.shadowText(label, x, y + 15f, 8f, 0xFFDDDDDD.toInt(), 1)
    }
    if (hints == 0) {
        val need = powerForTarget()
        val nx = x0 + (x1 - x0) * need
        g.poly(floatArrayOf(nx - 6f, nx + 6f, nx), floatArrayOf(y - 14f, y - 14f, y - 4f), Game.GOLD, 3)
        g.shadowText("TARGET ~${(need * 100).roundToInt()}%", nx.coerceIn(x0 + 70f, x1 - 40f), y - 16f, 9f, Game.GOLD, 1)
    }
    if (lastPower >= 0f && fill < 0f && hints < 3) {
        val lx = x0 + (x1 - x0) * lastPower
        g.rect(lx - 1.5f, y - 7f, 3f, 14f, Game.CYAN)
    }
}

/**
 * The throwing angle as a quarter-circle dial, from level (0°) to straight up (90°), with the
 * usable 15-70° shaded. The needle shows the angle you'd throw at if you let go now, from how
 * far the phone leans back: white before you grip, green while gripping and swinging (it drops
 * as the phone comes forward), and gold for the angle you actually threw at.
 */
private fun Game.drawAngleDial(g: Gfx) {
    val r = 48f
    val cx = viewW - 20f - r
    val cy = viewH - 160f
    fun fan(from: Float, to: Float, radius: Float, color: Int) {
        val n = 12
        val xs = FloatArray(n + 2)
        val ys = FloatArray(n + 2)
        xs[0] = cx; ys[0] = cy
        for (i in 0..n) {
            val a = from + (to - from) * i / n
            xs[i + 1] = cx + cos(a) * radius
            ys[i + 1] = cy - sin(a) * radius
        }
        g.poly(xs, ys, color, n + 2)
    }
    val deg = 0.017453f
    fan(0f, 90f * deg, r + 4f, 0xA0000000.toInt())
    fan(MIN_ELEVATION, MAX_ELEVATION, r, 0x405BE36B)
    for (d in 0..90 step 15) {
        val a = d * deg
        g.line(cx + cos(a) * (r - 6f), cy - sin(a) * (r - 6f), cx + cos(a) * r, cy - sin(a) * r, 1.5f, 0xB0FFFFFF.toInt())
    }
    val gripping = detector.phase != ThrowDetector.Phase.IDLE
    val live = if (gripping) detector.liveAngle else null
    val (angle, color, label) = when {
        live != null -> Triple(live, Game.GREEN, "NOW")
        throwState != Game.ThrowState.READY && lastElev >= 0f ->
            Triple(lastElev, Game.GOLD, if (releaseMeasured) "THROWN" else "GUIDE")
        else -> Triple(aimElev, Game.WHITE, "GUIDE")
    }
    // Last throw's angle as a gold tick while you line up the next one
    if (throwState == Game.ThrowState.READY && lastElev >= 0f) {
        val a = lastElev
        g.line(cx + cos(a) * (r - 12f), cy - sin(a) * (r - 12f), cx + cos(a) * (r + 3f), cy - sin(a) * (r + 3f), 3f, Game.GOLD)
    }
    val a = angle.coerceIn(0f, 90f * deg)
    g.line(cx, cy, cx + cos(a) * (r - 2f), cy - sin(a) * (r - 2f), 3.5f, color)
    g.oval(cx - 3f, cy - 3f, 6f, 6f, color)
    g.shadowText("$label ${(angle / deg).roundToInt()}°", cx + r / 2f, cy + 12f, 9f, color, 1)
    g.shadowText("ANGLE", cx - 8f, cy - r + 4f, 8f, Game.WHITE, 2)
}

/** A dark panel with a gold Greek key border. Returns its top. */
internal fun Game.panel(g: Gfx, h: Float): Float {
    val w = viewW - 32f
    val x = 16f
    val y = viewH * 0.42f - h / 2f
    g.rect(x, y, w, h, PANEL)
    meander(g, x + 4f, y + 4f, w - 8f)
    meander(g, x + 4f, y + h - 12f, w - 8f)
    return y
}

/** A strip of the Greek key pattern, 8 px tall. */
internal fun meander(g: Gfx, x: Float, y: Float, w: Float) {
    val c = Game.GOLD
    val u = 8f
    val t = 1.5f
    g.rect(x, y + u - t, w, t, c)
    var px = x
    while (px + u <= x + w) {
        g.rect(px, y, u - t, t, c)
        g.rect(px + u - 2 * t, y, t, u - t, c)
        g.rect(px, y, t, u * 0.7f, c)
        g.rect(px, y + u * 0.7f - t, u * 0.5f, t, c)
        g.rect(px + u * 0.5f - t, y + u * 0.35f, t, u * 0.35f, c)
        px += u
    }
}

internal fun Game.drawIntro(g: Gfx) {
    val y = panel(g, 270f)
    g.shadowText("LEVEL $levelNum", viewW / 2f, y + 46f, 28f, Game.GOLD, 1, serif = true)
    g.shadowText(spec.name.uppercase(), viewW / 2f, y + 68f, 14f, Game.WHITE, 1)
    val needs = if (spec.hits == 1) "Hit the target" else "Hit the target ${spec.hits} times"
    if (controls == ControlMode.TOUCH) {
        val guide = when (hintLevel()) {
            0 -> "Guides: all on"
            1 -> "Guides: no target power"
            2 -> "Guides: no distances or markers"
            else -> "Guides: off. Trust your pull!"
        }
        g.shadowText(guide, viewW / 2f, y + 170f, 10f, CREAM, 1)
    }
    g.shadowText(needs, viewW / 2f, y + 96f, 13f, Game.WHITE, 1)
    g.shadowText("in ${spec.time.roundToInt()} seconds", viewW / 2f, y + 113f, 13f, Game.WHITE, 1)
    val extras = buildList {
        add("${target.z.roundToInt()} m away")
        if (wind != 0f) add("wind ${oneDecimal(abs(wind))} m/s")
    }.joinToString("  ·  ")
    g.shadowText(extras, viewW / 2f, y + 136f, 11f, Game.CYAN, 1)
    if (target.moving) g.shadowText("MOVING TARGET", viewW / 2f, y + 152f, 11f, Game.ORANGE, 1)
    button(g, runUpButton(), if (runUp) "RUN-UP: ON" else "RUN-UP: OFF", if (runUp) Game.GREEN else CREAM)
    if (screenT > 0.4f) g.shadowText("TAP ANYWHERE ELSE TO START", viewW / 2f, y + 246f, 11f, blink(), 1)
}

internal fun Game.drawClear(g: Gfx) {
    val y = panel(g, 190f)
    g.shadowText("VICTORY!", viewW / 2f, y + 48f, 30f, Game.GREEN, 1, serif = true)
    g.shadowText("Time bonus  +$levelBonus", viewW / 2f, y + 80f, 13f, Game.WHITE, 1)
    g.shadowText("Score  $score", viewW / 2f, y + 100f, 14f, Game.GOLD, 1)
    if (gotExtraLife) g.shadowText("+1 LAUREL WREATH (LIFE)", viewW / 2f, y + 122f, 11f, Game.GREEN, 1)
    if (screenT > 1f) g.shadowText("TAP FOR LEVEL ${levelNum + 1}", viewW / 2f, y + 168f, 12f, blink(), 1)
}

internal fun Game.drawTimeUp(g: Gfx) {
    val y = panel(g, 170f)
    g.shadowText("TIME'S UP!", viewW / 2f, y + 48f, 28f, Game.RED, 1, serif = true)
    g.shadowText("$hits of ${spec.hits} hits", viewW / 2f, y + 78f, 13f, Game.WHITE, 1)
    g.shadowText("$lives ${if (lives == 1) "life" else "lives"} left", viewW / 2f, y + 98f, 13f, Game.WHITE, 1)
    if (screenT > 1f) g.shadowText("TAP TO TRY AGAIN", viewW / 2f, y + 148f, 12f, blink(), 1)
}

internal fun Game.drawGameOver(g: Gfx) {
    val y = panel(g, 200f)
    g.shadowText("GAME OVER", viewW / 2f, y + 48f, 30f, Game.RED, 1, serif = true)
    g.shadowText("You reached level $levelNum", viewW / 2f, y + 78f, 13f, Game.WHITE, 1)
    g.shadowText("Score  $score", viewW / 2f, y + 102f, 16f, Game.GOLD, 1)
    if (newHighRank >= 0) {
        val text = if (newHighRank == 0) "NEW HIGH SCORE!" else "TOP 5 SCORE! #${newHighRank + 1}"
        g.shadowText(text, viewW / 2f, y + 128f, 13f, Game.GREEN, 1)
    }
    if (screenT > 1.5f) g.shadowText("TAP TO CONTINUE", viewW / 2f, y + 178f, 12f, blink(), 1)
}

internal fun Game.drawPause(g: Gfx) {
    g.rect(0f, -safeTop, viewW, fullH(), 0x90000000.toInt())
    g.shadowText("PAUSED", viewW / 2f, viewH * 0.45f - 30f, 32f, Game.WHITE, 1, serif = true)
    button(g, resumeButton(), "RESUME", Game.GREEN)
    button(g, quitButton(), "QUIT TO MENU", CREAM)
}

internal fun Game.drawTitle(g: Gfx) {
    g.rect(0f, -safeTop, viewW, fullH(), 0x30000000)
    val cx = viewW / 2f
    g.text("JAVELIN", cx + 3f, 93f, 50f, INK, 1, serif = true)
    g.text("JAVELIN", cx, 90f, 50f, Game.GOLD, 1, serif = true)
    g.text("STRIKE", cx + 3f, 141f, 46f, INK, 1, serif = true)
    g.text("STRIKE", cx, 138f, 46f, TERRACOTTA, 1, serif = true)
    meander(g, cx - 120f, 152f, 240f)
    g.shadowText("THE GAMES OF OLYMPIA", cx, 180f, 12f, CREAM, 1, serif = true)

    button(g, titleButton(0), "PLAY", Game.GREEN)
    val ctl = if (motionAvailable) controls.label else "TOUCH (NO GYRO)"
    button(g, titleButton(1), "CONTROLS: $ctl", CREAM)
    button(g, titleButton(2), "ARM: ${arm.label}", CREAM)
    button(g, titleButton(3), "HOW TO PLAY", CREAM)
    // The update button (only in the version installed from GitHub)
    val up = titleButton(if (updater === Updater.None) 3 else 4)
    if (updater !== Updater.None) {
        val pulse = updater.available && (clock * 2f).toInt() % 2 == 0
        button(g, up, updater.label, if (updater.available) (if (pulse) Game.GOLD else Game.GREEN) else 0xFFD9C9A3.toInt())
        updater.detail?.let { d ->
            for ((i, l) in wrap(d, 46).take(2).withIndex()) g.shadowText(l, cx, up[1] + up[3] + 13f + i * 12f, 9f, CREAM, 1)
        }
    }

    val top = up[1] + up[3] + 34f
    val pw = 220f
    g.rect(cx - pw / 2f, top, pw, 150f, PANEL)
    meander(g, cx - pw / 2f + 4f, top + 4f, pw - 8f)
    g.shadowText("HALL OF CHAMPIONS", cx, top + 30f, 12f, Game.GOLD, 1, serif = true)
    for (i in 0 until 5) {
        val yy = top + 52f + i * 20f
        val s = highScores[i]
        g.shadowText("${i + 1}.", cx - 90f, yy, 12f, Game.WHITE)
        g.shadowText(if (s > 0) "$s" else "---", cx + 30f, yy, 12f, Game.WHITE, 2)
        if (s > 0) g.shadowText("L${highLevels[i]}", cx + 90f, yy, 11f, Game.CYAN, 2)
    }
    val best = bestLevel()
    if (best > 0) g.shadowText("Best level cleared: $best", cx, viewH - 14f, 10f, Game.WHITE, 1)
}

/** The menu of events, each with its best result. */
internal fun Game.drawEvents(g: Gfx) {
    g.rect(0f, -safeTop, viewW, fullH(), 0x60000000)
    val cx = viewW / 2f
    g.shadowText("THE EVENTS", cx, 70f, 30f, Game.GOLD, 1, serif = true)
    meander(g, cx - 110f, 82f, 220f)
    g.shadowText("Choose your contest", cx, 112f, 11f, CREAM, 1)
    for ((i, kind) in OlympicEventKind.entries.withIndex()) {
        val r = eventButton(i)
        button(g, r, kind.title, if (i == 0) Game.GREEN else CREAM)
        val best = when (kind) {
            OlympicEventKind.JAVELIN -> highScores[0].let { if (it > 0) "High score $it" else "" }
            else -> store.load(kind.bestKey).let { v ->
                if (v <= 0) "" else if (kind == OlympicEventKind.SPRINT) "Best ${v / 1000}.${((v % 1000) / 10).toString().padStart(2, '0')} s"
                else "Best ${v / 100}.${(v % 100).toString().padStart(2, '0')} m"
            }
        }
        g.shadowText(best.ifEmpty { "Not yet competed" }, cx, r[1] + r[3] + 14f, 10f, if (best.isEmpty()) 0xB0FFFFFF.toInt() else Game.CYAN, 1)
    }
    button(g, backButton(), "BACK", CREAM)
}

internal fun Game.drawHowTo(g: Gfx) {
    g.rect(0f, -safeTop, viewW, fullH(), 0xF0201510.toInt())
    meander(g, 12f, 12f, viewW - 24f)
    var y = 52f
    g.shadowText("HOW TO PLAY", viewW / 2f, y, 24f, Game.GOLD, 1, serif = true)
    y += 30f
    val steps = if (controls == ControlMode.MOTION) listOf(
        "1. Hold the phone upright in your throwing hand, screen facing you.",
        "2. Tilt it like a steering wheel to aim left or right.",
        "3. Press and HOLD the screen with your thumb to grip the javelin. With RUN-UP on, the athlete runs while you hold, faster and faster. Throw before the stone line or it's a foul; the run adds to the throw.",
        "4. Pull your arm back. The DRAW bar turns green when you're ready.",
        "5. Throw your arm forward! It lets go at the end of your swing.",
        "HOW FAST you move the phone sets the power: the POWER bar fills as you swing. HOW FAR IT LEANS BACK when you let go sets the angle: lean back more, or let go earlier in the swing, for a high, shorter throw. The ANGLE dial shows the angle you'd throw at right now.",
        "The POWER bar also shows how far each power goes and roughly how hard to throw to reach the target.",
    ) else listOf(
        "1. Press anywhere on the screen to grip the javelin.",
        "2. Press anywhere and pull back like a slingshot. The further you pull, the harder the throw. Pull to one side to aim the other way, like a real slingshot.",
        "3. Set the height on the slider on the right edge: slide up for a high throw, down for a flat one. The window above the POWER bar shows the flight from the side with a dotted arc.",
        "4. Let go to throw. Pull back to where you started to cancel.",
        "Guides fade as you go: from level 4 they stop showing the target power, from level 7 the distances, and from level 10 your last throw.",
    )
    fun para(text: String, color: Int) {
        for ((i, l) in wrap(text, 44).withIndex()) {
            g.shadowText(if (i == 0) l else "   $l", 18f, y, 11f, color)
            y += 16f
        }
        y += 4f
    }
    for (s in steps) para(s, Game.WHITE)
    y += 8f
    para("Hit the target before time runs out. You have unlimited javelins.", Game.CYAN)
    para("Targets move! Aim where the target will be when your javelin lands.", Game.CYAN)
    para("Watch the wind ribbon, and the TARGET view to see where you landed.", Game.CYAN)
    para("Too strong or too weak? Change ARM on the title screen.", Game.CYAN)
    y += 8f
    para("SAFETY: grip the phone tightly (a wrist strap or phone grip helps) and make sure nobody and nothing breakable is around you.", Game.ORANGE)
    if (screenT > 0.4f) {
        g.shadowText("TAP TO ${if (playAfterHowTo) "PLAY" else "GO BACK"}", viewW / 2f, viewH - 24f, 13f, blink(), 1)
    }
}

internal fun button(g: Gfx, r: FloatArray, label: String, color: Int) {
    g.rect(r[0], r[1], r[2], r[3], INK)
    g.rect(r[0] + 2f, r[1] + 2f, r[2] - 4f, r[3] - 4f, TERRACOTTA)
    g.rect(r[0] + 4f, r[1] + 4f, r[2] - 8f, r[3] - 8f, 0xFF8E3420.toInt())
    g.shadowText(label, r[0] + r[2] / 2f, r[1] + r[3] / 2f + 5f, 14f, color, 1)
}

internal fun Game.blink() = if ((clock * 2f).toInt() % 2 == 0) Game.WHITE else 0xFFFFE066.toInt()
