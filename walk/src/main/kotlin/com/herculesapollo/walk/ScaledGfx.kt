package com.herculesapollo.walk

import com.roccoquest.game.Gfx
import com.roccoquest.game.Sprite

/** Draws through [inner] zoomed in by [zoom], shifted up by [top] world pixels: a closer camera. */
class ScaledGfx(private val inner: Gfx, private val zoom: Float, private val top: Float) : Gfx {
    private fun sx(x: Float) = x * zoom
    private fun sy(y: Float) = (y - top) * zoom

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) = inner.rect(sx(x), sy(y), w * zoom, h * zoom, color)

    override fun oval(x: Float, y: Float, w: Float, h: Float, color: Int) = inner.oval(sx(x), sy(y), w * zoom, h * zoom, color)

    override fun poly(xs: FloatArray, ys: FloatArray, color: Int) =
        inner.poly(FloatArray(xs.size) { sx(xs[it]) }, FloatArray(ys.size) { sy(ys[it]) }, color)

    override fun sprite(s: Sprite, x: Float, y: Float, flipX: Boolean, flipY: Boolean, w: Float, h: Float) =
        inner.sprite(s, sx(x), sy(y), flipX, flipY, w * zoom, h * zoom)

    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) =
        inner.text(s, sx(x), sy(y), size * zoom, color, align)
}
