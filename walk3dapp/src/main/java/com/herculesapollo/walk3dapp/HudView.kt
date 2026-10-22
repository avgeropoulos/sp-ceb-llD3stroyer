package com.herculesapollo.walk3dapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.MotionEvent
import android.view.View
import com.herculesapollo.walk3d.Controls3D
import com.herculesapollo.walk3d.Game3D
import com.herculesapollo.walk3d.Hud3D
import com.herculesapollo.walk3d.VIEW_H
import com.herculesapollo.walkshared.CanvasGfx

/** A see-through view on top of the 3D one: draws the HUD and controls, and takes the touches. */
@SuppressLint("ViewConstructor")
class HudView(context: Context, private val game: Game3D) : View(context) {
    private val gfx = CanvasGfx()
    private val touchX = FloatArray(10)
    private val touchY = FloatArray(10)
    private val touchId = IntArray(10)

    override fun onDraw(canvas: Canvas) {
        val scale = height / VIEW_H
        if (scale > 0f) {
            gfx.begin(canvas, scale)
            synchronized(game) { Hud3D.render(game, gfx) }
        }
        postInvalidateOnAnimation()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val scale = height / VIEW_H
        if (scale <= 0f) return true
        val action = e.actionMasked
        var n = 0
        if (action != MotionEvent.ACTION_CANCEL && action != MotionEvent.ACTION_UP) {
            for (i in 0 until e.pointerCount) {
                if (action == MotionEvent.ACTION_POINTER_UP && i == e.actionIndex) continue
                if (n >= touchX.size) break
                touchId[n] = e.getPointerId(i)
                touchX[n] = e.getX(i) / scale
                touchY[n] = e.getY(i) / scale
                n++
            }
        }
        val input = game.input
        Controls3D.apply(input, touchId, touchX, touchY, n, width / scale)
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            input.tapX = e.getX(e.actionIndex) / scale
            input.tap = true
        }
        return true
    }
}
