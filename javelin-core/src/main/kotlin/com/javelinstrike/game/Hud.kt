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
    g.rect(0f, 0f, viewW, 76f, 0x50000000)
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
        controls == ControlMode.TOUCH && draw >= FlickThrow.PULL_MIN / FlickThrow.PULL_FULL
    val fill = mh * draw
    g.rect(mx, my + mh - fill, 14f, fill, if (armed) Game.GREEN else Game.GOLD)
    g.shadowText("DRAW", mx + 7f, my + mh + 14f, 8f, Game.WHITE, 1)

    val (line, sub) = if (controls == ControlMode.MOTION) {
        when (detector.phase) {
            ThrowDetector.Phase.IDLE -> "HOLD THE SCREEN" to (if (hasTilt()) "Tilt the phone to aim" else "")
            ThrowDetector.Phase.GRIP -> "PULL YOUR ARM BACK" to "Keep holding the screen"
            ThrowDetector.Phase.DRAWN -> "NOW THROW!" to "Whip it forward"
            ThrowDetector.Phase.SWING -> "!!!" to ""
        }
    } else if (flickActive()) {
        (if (armed) "FLICK UP!" else "PULL DOWN") to "Flick sideways to aim"
    } else {
        "PRESS, PULL DOWN, FLICK UP" to "Faster flick = longer throw"
    }
    if (msgT <= 0f) {
        val color = if (armed) Game.GREEN else Game.WHITE
        g.shadowText(line, viewW / 2f, viewH - 40f, 18f, color, 1)
        if (sub.isNotEmpty()) g.shadowText(sub, viewW / 2f, viewH - 22f, 11f, 0xFFEEE4CC.toInt(), 1)
    }
    if (target.moving && msgT <= 0f && lastDistance == 0f) {
        g.shadowText("It moves! Aim where it will be.", viewW / 2f, viewH - 62f, 10f, Game.ORANGE, 1)
    }
}

/** A dark panel with a gold Greek key border. Returns its top. */
private fun Game.panel(g: Gfx, h: Float): Float {
    val w = viewW - 32f
    val x = 16f
    val y = viewH * 0.42f - h / 2f
    g.rect(x, y, w, h, PANEL)
    meander(g, x + 4f, y + 4f, w - 8f)
    meander(g, x + 4f, y + h - 12f, w - 8f)
    return y
}

/** A strip of the Greek key pattern, 8 px tall. */
private fun meander(g: Gfx, x: Float, y: Float, w: Float) {
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
    val y = panel(g, 200f)
    g.shadowText("LEVEL $levelNum", viewW / 2f, y + 46f, 28f, Game.GOLD, 1, serif = true)
    g.shadowText(spec.name.uppercase(), viewW / 2f, y + 68f, 14f, Game.WHITE, 1)
    val needs = if (spec.hits == 1) "Hit the target" else "Hit the target ${spec.hits} times"
    g.shadowText(needs, viewW / 2f, y + 96f, 13f, Game.WHITE, 1)
    g.shadowText("in ${spec.time.roundToInt()} seconds", viewW / 2f, y + 113f, 13f, Game.WHITE, 1)
    val extras = buildList {
        add("${target.z.roundToInt()} m away")
        if (wind != 0f) add("wind ${oneDecimal(abs(wind))} m/s")
    }.joinToString("  ·  ")
    g.shadowText(extras, viewW / 2f, y + 136f, 11f, Game.CYAN, 1)
    if (target.moving) g.shadowText("MOVING TARGET", viewW / 2f, y + 152f, 11f, Game.ORANGE, 1)
    if (screenT > 0.4f) g.shadowText("TAP TO START", viewW / 2f, y + 180f, 12f, blink(), 1)
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
    g.rect(0f, 0f, viewW, viewH, 0x90000000.toInt())
    g.shadowText("PAUSED", viewW / 2f, viewH * 0.45f - 30f, 32f, Game.WHITE, 1, serif = true)
    button(g, resumeButton(), "RESUME", Game.GREEN)
    button(g, quitButton(), "QUIT TO MENU", CREAM)
}

internal fun Game.drawTitle(g: Gfx) {
    g.rect(0f, 0f, viewW, viewH, 0x30000000)
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

    val top = titleButton(3)[1] + 62f
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

internal fun Game.drawHowTo(g: Gfx) {
    g.rect(0f, 0f, viewW, viewH, 0xF0201510.toInt())
    meander(g, 12f, 12f, viewW - 24f)
    var y = 52f
    g.shadowText("HOW TO PLAY", viewW / 2f, y, 24f, Game.GOLD, 1, serif = true)
    y += 30f
    val steps = if (controls == ControlMode.MOTION) listOf(
        "1. Hold the phone upright in your throwing hand, screen facing you.",
        "2. Tilt it like a steering wheel to aim left or right.",
        "3. Press and HOLD the screen with your thumb to grip the javelin.",
        "4. Pull your arm back. The DRAW bar turns green when you're ready.",
        "5. Throw your arm forward! The faster the swing, the further it flies. It lets go at the end of your swing.",
    ) else listOf(
        "1. Press anywhere on the screen to grip the javelin.",
        "2. Pull your finger down to draw back.",
        "3. Flick up to throw. A faster flick throws further.",
        "4. Flick a little left or right to aim.",
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

private fun button(g: Gfx, r: FloatArray, label: String, color: Int) {
    g.rect(r[0], r[1], r[2], r[3], INK)
    g.rect(r[0] + 2f, r[1] + 2f, r[2] - 4f, r[3] - 4f, TERRACOTTA)
    g.rect(r[0] + 4f, r[1] + 4f, r[2] - 8f, r[3] - 8f, 0xFF8E3420.toInt())
    g.shadowText(label, r[0] + r[2] / 2f, r[1] + r[3] / 2f + 5f, 14f, color, 1)
}

private fun Game.blink() = if ((clock * 2f).toInt() % 2 == 0) Game.WHITE else 0xFFFFE066.toInt()
