package com.bouzoukihero.core

import kotlin.math.hypot

/**
 * Platform-neutral drawing surface. Coordinates are virtual pixels: the screen is
 * always [VIEW_W] wide and as tall as its aspect ratio makes it.
 */
interface Gfx {
    fun rect(x: Float, y: Float, w: Float, h: Float, color: Int)
    fun oval(x: Float, y: Float, w: Float, h: Float, color: Int)
    fun poly(xs: FloatArray, ys: FloatArray, color: Int)

    /** [y] is the text baseline. [align]: 0 = left, 1 = center, 2 = right. */
    fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int = 0)
}

const val VIEW_W = 360f

fun Gfx.line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int) {
    val len = hypot(x2 - x1, y2 - y1)
    if (len < 0.01f) return
    val nx = -(y2 - y1) / len * width / 2
    val ny = (x2 - x1) / len * width / 2
    poly(floatArrayOf(x1 + nx, x2 + nx, x2 - nx, x1 - nx), floatArrayOf(y1 + ny, y2 + ny, y2 - ny, y1 - ny), color)
}

fun Gfx.circle(cx: Float, cy: Float, r: Float, color: Int) = oval(cx - r, cy - r, 2 * r, 2 * r, color)

fun Gfx.shadowText(s: String, x: Float, y: Float, size: Float, color: Int, align: Int = 0) {
    text(s, x + 1.5f, y + 1.5f, size, 0xC0000000.toInt(), align)
    text(s, x, y, size, color, align)
}

/** A rounded button-like box. */
fun Gfx.panel(x: Float, y: Float, w: Float, h: Float, color: Int, r: Float = 10f) {
    rect(x + r, y, w - 2 * r, h, color)
    rect(x, y + r, r, h - 2 * r, color)
    rect(x + w - r, y + r, r, h - 2 * r, color)
    oval(x, y, 2 * r, 2 * r, color)
    oval(x + w - 2 * r, y, 2 * r, 2 * r, color)
    oval(x, y + h - 2 * r, 2 * r, 2 * r, color)
    oval(x + w - 2 * r, y + h - 2 * r, 2 * r, 2 * r, color)
}

fun argb(a: Int, rgb: Int): Int = (a.coerceIn(0, 255) shl 24) or (rgb and 0xFFFFFF)

/** Mixes [a] toward [b] by [t] (0..1), alpha included. */
fun mix(a: Int, b: Int, t: Float): Int {
    val k = t.coerceIn(0f, 1f)
    fun ch(s: Int) = (((a ushr s) and 0xFF) + (((b ushr s) and 0xFF) - ((a ushr s) and 0xFF)) * k).toInt() shl s
    return ch(24) or ch(16) or ch(8) or ch(0)
}
