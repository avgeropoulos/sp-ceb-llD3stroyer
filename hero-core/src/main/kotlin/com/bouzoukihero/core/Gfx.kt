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

// Scratch arrays for the shapes below: drawing makes hundreds of them a frame, and new arrays
// each time kept the garbage collector busy, which showed as random hiccups. Drawing happens on
// one thread at a time and every Gfx uses the points straight away, so they can be reused.
private val qx = FloatArray(4)
private val qy = FloatArray(4)
private val tx = FloatArray(3)
private val ty = FloatArray(3)

/** A four-cornered shape, without allocating. */
fun Gfx.quad(
    x1: Float, x2: Float, x3: Float, x4: Float,
    y1: Float, y2: Float, y3: Float, y4: Float,
    color: Int,
) {
    qx[0] = x1; qx[1] = x2; qx[2] = x3; qx[3] = x4
    qy[0] = y1; qy[1] = y2; qy[2] = y3; qy[3] = y4
    poly(qx, qy, color)
}

/** A triangle, without allocating. */
fun Gfx.tri(x1: Float, x2: Float, x3: Float, y1: Float, y2: Float, y3: Float, color: Int) {
    tx[0] = x1; tx[1] = x2; tx[2] = x3
    ty[0] = y1; ty[1] = y2; ty[2] = y3
    poly(tx, ty, color)
}

fun Gfx.line(x1: Float, y1: Float, x2: Float, y2: Float, width: Float, color: Int) {
    val len = hypot(x2 - x1, y2 - y1)
    if (len < 0.01f) return
    val nx = -(y2 - y1) / len * width / 2
    val ny = (x2 - x1) / len * width / 2
    quad(x1 + nx, x2 + nx, x2 - nx, x1 - nx, y1 + ny, y2 + ny, y2 - ny, y1 - ny, color)
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
