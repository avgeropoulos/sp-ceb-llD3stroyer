package com.spotless.game

import kotlin.math.sin

/** How Bella looks at the moment she is drawn. */
class Pose(
    /** 0..1: how into you she is. Drives blush and smile. */
    var mood: Float = 0f,
    /** Seconds; drives breathing, sway, blinking and sparkle twinkles. */
    var time: Float = 0f,
    var talking: Boolean = false,
    /** 0 = normal, 1 = delighted (eyes closed), 2 = pouty, 3 = wink, 4 = blowing a kiss. */
    var face: Int = 0,
    var wave: Boolean = false,
    /** A confident hand on her hip. */
    var hip: Boolean = false,
)

/**
 * Bella, drawn entirely with vector shapes. In local units her head starts at y=0 and her
 * feet touch y=890; x=0 is her centre line.
 */
object Bella {
    private val SKIN = rgb(0xFF, 0xDD, 0xC6)
    private val SKIN_SHADE = rgb(0xF0, 0xB8, 0x9C)
    private val HAIR = rgb(0xF6, 0xD4, 0x7A)
    private val HAIR_LIGHT = rgb(0xFF, 0xEC, 0xB0)
    private val HAIR_DARK = rgb(0xD6, 0xA9, 0x4C)
    private val BROW = rgb(0xB8, 0x8A, 0x3E)
    private val EYE = rgb(0x2E, 0xB0, 0x5C)
    private val LASH = rgb(0x3A, 0x26, 0x28)

    const val HEIGHT = 890f

    /** Draws Bella with her feet centred at (x, footY), [scale] times her natural size. */
    fun draw(p: Painter, o: Outfit, x: Float, footY: Float, scale: Float, pose: Pose) {
        val t = pose.time
        p.save()
        p.translate(x, footY)
        p.rotate(sin(t * 1.1f) * 1.2f)
        val breathe = 1f + sin(t * 2.2f) * 0.006f
        p.scale(scale, scale * breathe)
        p.translate(0f, -HEIGHT)

        backHair(p, o, t)
        if (o.cut == Cut.HOODIE) hood(p, o)
        legs(p, o)
        // Neck and torso skin (shows above low necklines).
        p.rect(-17f, 150f, 34f, 70f, SKIN)
        p.fill(shape { moveTo(-17f, 175f); quadTo(0f, 196f, 17f, 175f); lineTo(17f, 160f); lineTo(-17f, 160f); close() }, SKIN_SHADE)
        sym(p, SKIN) { s -> torso(this, s) }
        bottoms(p, o)
        top(p, o)
        if (o.cut == Cut.SUNDRESS || o.cut == Cut.COCKTAIL || o.cut == Cut.GOWN || o.cut == Cut.GALA) skirt(p, o, t)
        chest(p, o)
        if (o.necklace) necklace(p, o, t)
        arms(p, o, pose)
        head(p, o, pose)
        frontHair(p, o)
        if (o.sparkles) twinkles(p, o, t)
        p.restore()
    }

    /** Fills a left/right symmetric shape: [build] draws the half on side s (-1 or 1). */
    private inline fun sym(p: Painter, color: Int, build: Shape.(Float) -> Unit) {
        p.fill(Shape().apply { build(-1f) }, color)
        p.fill(Shape().apply { build(1f) }, color)
    }

    private fun torso(sh: Shape, s: Float) = sh.apply {
        moveTo(-s * 2f, 190f)
        lineTo(s * 18f, 190f)
        quadTo(s * 28f, 212f, s * 76f, 216f)
        quadTo(s * 92f, 222f, s * 90f, 254f)
        quadTo(s * 102f, 306f, s * 66f, 334f)
        quadTo(s * 54f, 350f, s * 54f, 376f)
        quadTo(s * 58f, 422f, s * 92f, 466f)
        quadTo(s * 98f, 500f, s * 86f, 528f)
        lineTo(-s * 2f, 528f)
        close()
    }

    // ---------------------------------------------------------------- hair

    private fun backHair(p: Painter, o: Outfit, t: Float) {
        when (o.hair) {
            Hair.DOWN, Hair.WAVY -> {
                val wavy = o.hair == Hair.WAVY
                sym(p, HAIR_DARK) { s ->
                    moveTo(-s * 2f, -2f)
                    quadTo(s * 80f, -8f, s * 84f, 100f)
                    if (wavy) {
                        quadTo(s * 104f, 170f, s * 92f, 230f)
                        quadTo(s * 82f, 290f, s * 104f, 350f)
                        quadTo(s * 116f, 400f, s * 92f, 430f)
                    } else {
                        quadTo(s * 98f, 260f, s * 102f, 420f)
                    }
                    quadTo(s * 50f, 440f, -s * 2f, 420f)
                    close()
                }
            }
            Hair.PONYTAIL -> {
                val swing = sin(t * 2.4f) * 6f
                p.fill(shape {
                    moveTo(30f, 20f)
                    quadTo(128f + swing, 40f, 118f + swing, 190f)
                    quadTo(112f + swing, 280f, 80f + swing * 1.5f, 330f)
                    quadTo(96f + swing, 220f, 60f, 110f)
                    close()
                }, HAIR_DARK)
                p.stroke(shape { moveTo(70f, 60f); quadTo(115f + swing, 120f, 100f + swing, 250f) }, HAIR, 6f)
            }
            Hair.BUN -> {
                p.oval(0f, 4f, 40f, 36f, HAIR_DARK)
                p.oval(-4f, 0f, 34f, 30f, HAIR)
                p.stroke(shape { moveTo(-26f, 4f); quadTo(0f, -26f, 26f, 0f) }, HAIR_DARK, 4f)
                p.stroke(shape { moveTo(-20f, 16f); quadTo(4f, -8f, 30f, 14f) }, HAIR_LIGHT, 3f)
                p.stroke(shape { moveTo(30f, -10f); quadTo(48f, -26f, 44f, -40f) }, HAIR_DARK, 3f)
            }
            Hair.UPDO -> {
                p.oval(0f, 2f, 52f, 40f, HAIR_DARK)
                p.oval(-2f, -4f, 44f, 32f, HAIR)
                p.stroke(shape { moveTo(-34f, 0f); quadTo(0f, -32f, 34f, -2f) }, HAIR_DARK, 4f)
                p.stroke(shape { moveTo(-26f, 14f); quadTo(6f, -12f, 36f, 12f) }, HAIR_LIGHT, 4f)
            }
        }
    }

    private fun frontHair(p: Painter, o: Outfit) {
        // Strands falling over the shoulders.
        if (o.hair == Hair.DOWN || o.hair == Hair.WAVY) {
            val wavy = o.hair == Hair.WAVY
            sym(p, HAIR) { s ->
                moveTo(s * 60f, 100f)
                quadTo(s * 82f, 90f, s * 84f, 140f)
                if (wavy) {
                    quadTo(s * 100f, 210f, s * 90f, 260f)
                    quadTo(s * 82f, 310f, s * 100f, 350f)
                    quadTo(s * 92f, 372f, s * 80f, 380f)
                    quadTo(s * 86f, 350f, s * 70f, 320f)
                    quadTo(s * 60f, 280f, s * 74f, 230f)
                } else {
                    quadTo(s * 100f, 260f, s * 96f, 370f)
                    quadTo(s * 86f, 380f, s * 76f, 372f)
                    quadTo(s * 76f, 250f, s * 66f, 190f)
                }
                quadTo(s * 64f, 150f, s * 60f, 120f)
                close()
            }
            sym(p, HAIR_LIGHT) { s ->
                moveTo(s * 78f, 140f)
                quadTo(s * (if (wavy) 92f else 90f), 230f, s * 86f, 330f)
                quadTo(s * 82f, 240f, s * 74f, 150f)
                close()
            }
        }
        // The hair on top of the head, with a side-swept fringe.
        p.fill(shape {
            moveTo(-72f, 128f)
            cubicTo(-86f, -14f, 86f, -14f, 72f, 128f)
            lineTo(64f, 128f)
            cubicTo(66f, 82f, 52f, 46f, 12f, 44f)
            cubicTo(-22f, 48f, -50f, 66f, -60f, 96f)
            lineTo(-65f, 128f)
            close()
        }, HAIR)
        p.fill(shape {
            moveTo(12f, 44f)
            cubicTo(-22f, 48f, -50f, 66f, -60f, 96f)
            cubicTo(-48f, 74f, -24f, 60f, 12f, 54f)
            close()
        }, HAIR_DARK)
        p.stroke(shape { moveTo(-44f, 26f); quadTo(-10f, 6f, 30f, 14f) }, HAIR_LIGHT, 7f)
        p.stroke(shape { moveTo(40f, 30f); quadTo(58f, 50f, 62f, 90f) }, HAIR_LIGHT, 4f)
        if (o.hair == Hair.UPDO) {
            // Loose curls framing the face.
            p.stroke(shape { moveTo(-64f, 110f); quadTo(-74f, 150f, -62f, 175f); quadTo(-56f, 190f, -66f, 200f) }, HAIR, 5f)
            p.stroke(shape { moveTo(64f, 110f); quadTo(74f, 150f, 62f, 175f); quadTo(56f, 190f, 66f, 200f) }, HAIR, 5f)
        }
        if (o.hair == Hair.PONYTAIL) {
            p.oval(54f, 52f, 9f, 9f, o.shoes)
        }
        if (o.tiara) {
            p.fill(shape {
                moveTo(-44f, 22f)
                lineTo(-30f, 2f); lineTo(-18f, 14f); lineTo(0f, -16f); lineTo(18f, 14f); lineTo(30f, 2f); lineTo(44f, 22f)
                quadTo(0f, 6f, -44f, 22f)
                close()
            }, rgb(0xFF, 0xE0, 0x7A))
            p.oval(0f, 2f, 6f, 6f, rgb(0x6C, 0xC8, 0xFF))
            p.oval(-30f, 12f, 4f, 4f, rgb(0xFF, 0x6C, 0x9A))
            p.oval(30f, 12f, 4f, 4f, rgb(0xFF, 0x6C, 0x9A))
        }
    }

    // ---------------------------------------------------------------- head

    private fun head(p: Painter, o: Outfit, pose: Pose) {
        val m = pose.mood
        p.oval(-64f, 108f, 9f, 15f, SKIN_SHADE)
        p.oval(64f, 108f, 9f, 15f, SKIN_SHADE)
        p.fill(shape {
            moveTo(-63f, 90f)
            cubicTo(-63f, 20f, 63f, 20f, 63f, 90f)
            cubicTo(63f, 134f, 32f, 170f, 0f, 174f)
            cubicTo(-32f, 170f, -63f, 134f, -63f, 90f)
            close()
        }, SKIN)
        if (o.earrings) {
            val c = if (o.cut == Cut.GOWN) rgb(0xD8, 0xF4, 0xFF) else rgb(0xFF, 0xE6, 0x8A)
            p.line(-64f, 122f, -64f, 136f, rgb(0xE8, 0xC0, 0x5A), 2.5f)
            p.line(64f, 122f, 64f, 136f, rgb(0xE8, 0xC0, 0x5A), 2.5f)
            p.oval(-64f, 142f, 6f, 8f, c)
            p.oval(64f, 142f, 6f, 8f, c)
        }
        // Blush deepens as she falls for you.
        val blush = alpha(rgb(0xFF, 0x6E, 0x8E), 0.18f + 0.42f * m)
        p.oval(-38f, 132f, 15f, 8f, blush)
        p.oval(38f, 132f, 15f, 8f, blush)

        val blink = (pose.time % 3.7f) < 0.13f
        for (s in floatArrayOf(-1f, 1f)) {
            val ex = s * 26f
            val ey = 106f
            // Brows: raised and soft when happy, angled when pouty.
            val browTilt = if (pose.face == 2) 6f else -2f
            p.stroke(shape { moveTo(ex - s * 16f, ey - 26f + browTilt); quadTo(ex, ey - 34f, ex + s * 16f, ey - 26f - browTilt * 0.3f) }, BROW, 4f)
            if (m > 0.3f) {
                // Smoky eyeshadow once she starts dressing up for you.
                p.oval(ex + s * 3f, ey - 10f, 19f, 11f, alpha(rgb(0xB0, 0x5A, 0x8A), 0.12f + 0.3f * m))
            }
            val winking = (pose.face == 3 || pose.face == 4) && s > 0
            if (blink || pose.face == 1 || winking) {
                // Closed, smiling eyes.
                p.stroke(shape { moveTo(ex - 15f, ey + 2f); quadTo(ex, ey - 12f, ex + 15f, ey + 2f) }, LASH, 4.5f)
                p.line(ex + s * 15f, ey + 1f, ex + s * 21f, ey - 4f, LASH, 3f)
                continue
            }
            p.oval(ex, ey, 15f, 17f, WHITE)
            p.oval(ex, ey + 2f, 11f, 14f, EYE)
            p.oval(ex, ey + 6f, 9f, 8f, darker(EYE, 0.25f))
            p.oval(ex, ey + 3f, 5.5f, 7f, rgb(0x1E, 0x2A, 0x44))
            p.oval(ex + 4f, ey - 4f, 4f, 4.5f, WHITE)
            p.oval(ex - 4f, ey + 8f, 2f, 2f, alpha(WHITE, 0.8f))
            if (m > 0.3f) {
                // Heavy-lidded, sultry look.
                p.fill(shape { moveTo(ex - 17f, ey - 4f); quadTo(ex, ey - 26f, ex + 17f, ey - 4f); quadTo(ex, ey - 8f + 4f * m, ex - 17f, ey - 4f); close() }, SKIN)
            }
            // Upper lash line with a little flick at the outer corner.
            p.stroke(shape { moveTo(ex - 16f, ey - 3f); quadTo(ex, ey - 22f, ex + 16f, ey - 3f) }, LASH, 4.5f)
            p.fill(shape {
                moveTo(ex + s * 13f, ey - 9f)
                quadTo(ex + s * 22f, ey - 12f, ex + s * 25f, ey - 18f)
                quadTo(ex + s * 22f, ey - 5f, ex + s * 15f, ey - 3f)
                close()
            }, LASH)
            // Long lashes.
            for (k in 0..2) p.line(ex + s * (4f + k * 5f), ey - 13f + k * 2f, ex + s * (7f + k * 7f), ey - 21f + k * 2f, LASH, 2.5f)
            if (m > 0.55f) {
                // Hearts in her eyes once she's really into you.
                p.fill(heart(ex + 5f, ey - 4f, 4f), alpha(rgb(0xFF, 0x9E, 0xC0), (m - 0.55f) * 2.2f))
            }
        }
        // Nose.
        p.stroke(shape { moveTo(-2f, 120f); quadTo(4f, 130f, -3f, 133f) }, SKIN_SHADE, 3f)

        // Mouth.
        val lipColor = o.lips
        when {
            pose.talking -> {
                p.oval(0f, 151f, 10f, 8f, rgb(0x9A, 0x2E, 0x40))
                p.oval(0f, 155f, 6f, 3f, rgb(0xFF, 0x8A, 0x9A))
                p.stroke(shape { moveTo(-11f, 148f); quadTo(0f, 141f, 11f, 148f) }, lipColor, 3.5f)
            }
            pose.face == 4 -> {
                // Puckered up, blowing a kiss.
                p.oval(4f, 150f, 7f, 7f, lipColor)
                p.oval(4f, 150f, 2.5f, 3f, darker(lipColor, 0.35f))
            }
            pose.face == 3 -> {
                // A playful smirk.
                p.fill(shape { moveTo(-12f, 147f); quadTo(4f, 156f, 16f, 142f); quadTo(4f, 151f, -12f, 147f); close() }, lipColor)
            }
            pose.face == 2 -> {
                p.fill(shape { moveTo(-8f, 152f); quadTo(0f, 146f, 8f, 152f); quadTo(0f, 156f, -8f, 152f); close() }, lipColor)
            }
            else -> {
                val w = 13f + m * 6f + (if (pose.face == 1) 4f else 0f)
                val curve = 6f + m * 5f + (if (pose.face == 1) 5f else 0f)
                p.fill(shape {
                    moveTo(-w, 146f)
                    quadTo(0f, 146f + curve * 2f, w, 146f)
                    quadTo(0f, 148f + curve, -w, 146f)
                    close()
                }, lipColor)
                if (pose.face == 1 || m > 0.4f) {
                    p.fill(shape { moveTo(-w + 4f, 148f); quadTo(0f, 146f + curve * 1.6f, w - 4f, 148f); close() }, WHITE)
                }
            }
        }
    }

    // ---------------------------------------------------------------- body

    private fun hood(p: Painter, o: Outfit) {
        p.fill(shape {
            moveTo(-90f, 230f)
            quadTo(-100f, 150f, -60f, 168f)
            quadTo(0f, 196f, 60f, 168f)
            quadTo(100f, 150f, 90f, 230f)
            close()
        }, darker(o.main, 0.15f))
    }

    private fun legs(p: Painter, o: Outfit) {
        val skinLegs = o.cut != Cut.HOODIE && o.cut != Cut.GOWN && o.cut != Cut.GALA
        if (skinLegs) {
            sym(p, SKIN) { s ->
                moveTo(s * 2f, 500f)
                lineTo(s * 86f, 470f)
                quadTo(s * 88f, 600f, s * 60f, 690f)
                quadTo(s * 64f, 770f, s * 42f, 856f)
                lineTo(s * 22f, 856f)
                quadTo(s * 14f, 770f, s * 18f, 690f)
                quadTo(s * 8f, 600f, s * 6f, 548f)
                close()
            }
            // Knee hint.
            p.stroke(shape { moveTo(-46f, 700f); quadTo(-38f, 708f, -30f, 700f) }, SKIN_SHADE, 2.5f)
            p.stroke(shape { moveTo(46f, 700f); quadTo(38f, 708f, 30f, 700f) }, SKIN_SHADE, 2.5f)
        }
        // Shoes.
        for (s in floatArrayOf(-1f, 1f)) {
            val fx = s * 34f
            when (o.cut) {
                Cut.HOODIE -> {
                    p.roundRect(fx - 26f, 852f, 52f, 34f, 14f, o.shoes)
                    p.rect(fx - 26f, 876f, 52f, 8f, rgb(0xC8, 0xC8, 0xD0))
                    p.line(fx - 10f, 862f, fx + 10f, 862f, rgb(0xB0, 0xB0, 0xBA), 3f)
                }
                Cut.TEE_JEANS -> {
                    p.roundRect(fx - 25f, 852f, 50f, 34f, 14f, o.shoes)
                    p.rect(fx - 25f, 877f, 50f, 8f, WHITE)
                }
                Cut.SUNDRESS, Cut.BIKINI -> {
                    p.oval(fx, 872f, 16f, 15f, SKIN)
                    p.roundRect(fx - 18f, 880f, 36f, 8f, 4f, darker(o.shoes, 0.2f))
                    p.line(fx - 14f, 866f, fx + 14f, 866f, o.shoes, 5f)
                }
                Cut.GOWN, Cut.GALA -> {}
                else -> {
                    // Heels.
                    p.fill(shape {
                        moveTo(fx - 13f, 850f)
                        lineTo(fx + 13f, 850f)
                        quadTo(fx + 16f, 876f, fx + 4f, 892f)
                        lineTo(fx - 4f, 892f)
                        quadTo(fx - 16f, 876f, fx - 13f, 850f)
                        close()
                    }, o.shoes)
                    p.oval(fx, 852f, 12f, 6f, SKIN)
                    p.line(fx - s * 10f, 884f, fx - s * 14f, 892f, darker(o.shoes, 0.3f), 4f)
                }
            }
        }
    }

    private fun bottoms(p: Painter, o: Outfit) {
        when (o.cut) {
            Cut.HOODIE -> {
                // Loose sweatpants with cuffs.
                sym(p, o.second) { s ->
                    moveTo(-s * 2f, 380f)
                    lineTo(s * 82f, 380f)
                    quadTo(s * 104f, 500f, s * 90f, 690f)
                    quadTo(s * 82f, 800f, s * 66f, 846f)
                    lineTo(s * 12f, 846f)
                    quadTo(s * 6f, 700f, s * 4f, 560f)
                    lineTo(-s * 2f, 540f)
                    close()
                }
                p.roundRect(-70f, 834f, 60f, 24f, 10f, darker(o.second, 0.12f))
                p.roundRect(10f, 834f, 60f, 24f, 10f, darker(o.second, 0.12f))
                p.line(-8f, 560f, -12f, 820f, darker(o.second, 0.15f), 2f)
                p.line(8f, 560f, 12f, 820f, darker(o.second, 0.15f), 2f)
            }
            Cut.TEE_JEANS -> {
                // Denim cut-off shorts.
                sym(p, o.second) { s ->
                    moveTo(-s * 2f, 404f)
                    lineTo(s * 66f, 404f)
                    quadTo(s * 74f, 432f, s * 92f, 466f)
                    quadTo(s * 96f, 510f, s * 90f, 546f)
                    lineTo(s * 14f, 552f)
                    lineTo(s * 4f, 526f)
                    lineTo(-s * 2f, 524f)
                    close()
                }
                val fray = lighter(o.second, 0.45f)
                for (k in 0 until 6) {
                    p.line(-86f + k * 13f, 546f, -84f + k * 13f, 556f, fray, 2.5f)
                    p.line(20f + k * 13f, 548f, 22f + k * 13f, 558f, fray, 2.5f)
                }
                p.line(0f, 420f, 0f, 520f, darker(o.second, 0.2f), 2f)
                p.stroke(shape { moveTo(-60f, 420f); quadTo(-40f, 440f, -24f, 420f) }, darker(o.second, 0.2f), 2f)
                p.stroke(shape { moveTo(60f, 420f); quadTo(40f, 440f, 24f, 420f) }, darker(o.second, 0.2f), 2f)
                // Belt.
                p.rect(-68f, 404f, 136f, 14f, rgb(0x6B, 0x45, 0x2A))
                p.roundRect(-10f, 402f, 20f, 18f, 3f, rgb(0xE8, 0xC4, 0x5C))
                p.oval(0f, 384f, 2.5f, 4f, SKIN_SHADE)
            }
            Cut.BLOUSE_SKIRT -> {
                // A tight mini skirt sitting low on the hips.
                sym(p, o.second) { s ->
                    moveTo(-s * 2f, 400f)
                    lineTo(s * 64f, 400f)
                    quadTo(s * 76f, 432f, s * 94f, 468f)
                    quadTo(s * 100f, 510f, s * 96f, 548f)
                    lineTo(-s * 2f, 552f)
                    close()
                }
                p.rect(-64f, 400f, 128f, 14f, darker(o.second, 0.3f))
                p.oval(0f, 384f, 2.5f, 4f, SKIN_SHADE)
            }
            Cut.BIKINI -> {
                // Bikini bottoms with side ties.
                p.fill(shape {
                    moveTo(-84f, 476f)
                    quadTo(0f, 492f, 84f, 476f)
                    quadTo(60f, 508f, 16f, 540f)
                    lineTo(-16f, 540f)
                    quadTo(-60f, 508f, -84f, 476f)
                    close()
                }, o.main)
                for (sd in floatArrayOf(-1f, 1f)) {
                    p.line(sd * 84f, 478f, sd * 96f, 470f, o.main, 4f)
                    p.line(sd * 90f, 478f, sd * 96f, 500f, o.main, 3f)
                }
                p.oval(0f, 410f, 2.5f, 4f, SKIN_SHADE)
                // A sheer sarong knotted on one hip.
                p.fill(shape {
                    moveTo(70f, 470f)
                    quadTo(120f, 560f, 110f, 660f)
                    lineTo(30f, 640f)
                    quadTo(40f, 560f, 70f, 470f)
                    close()
                }, alpha(o.second, 0.55f))
                p.oval(78f, 474f, 10f, 9f, o.second)
            }
            else -> {}
        }
    }

    private fun top(p: Painter, o: Outfit) {
        val c = o.main
        when (o.cut) {
            Cut.HOODIE -> {
                sym(p, c) { s ->
                    moveTo(-s * 2f, 194f)
                    lineTo(s * 24f, 190f)
                    quadTo(s * 34f, 212f, s * 80f, 214f)
                    quadTo(s * 100f, 222f, s * 98f, 270f)
                    quadTo(s * 98f, 400f, s * 100f, 540f)
                    lineTo(-s * 2f, 540f)
                    close()
                }
                p.rect(-100f, 512f, 200f, 28f, darker(c, 0.1f))
                // Kangaroo pocket and drawstrings.
                p.fill(shape {
                    moveTo(-60f, 440f); lineTo(60f, 440f); lineTo(76f, 512f); lineTo(-76f, 512f); close()
                }, darker(c, 0.07f))
                p.stroke(shape { moveTo(-60f, 440f); lineTo(60f, 440f); lineTo(76f, 512f); lineTo(-76f, 512f); close() }, darker(c, 0.18f), 2f)
                p.text("SPOTLESS", 0f, 380f, 20f, darker(c, 0.18f), ALIGN_CENTER)
            }
            Cut.TEE_JEANS -> {
                sym(p, c) { s ->
                    moveTo(-s * 2f, 256f)
                    quadTo(s * 30f, 256f, s * 38f, 200f)
                    quadTo(s * 46f, 214f, s * 76f, 216f)
                    quadTo(s * 92f, 222f, s * 88f, 254f)
                    quadTo(s * 96f, 300f, s * 66f, 326f)
                    quadTo(s * 58f, 340f, s * 58f, 352f)
                    lineTo(-s * 2f, 356f)
                    close()
                }
            }
            Cut.SUNDRESS -> {
                bodice(p, c, 248f, straps = true, strapW = 9f)
            }
            Cut.BLOUSE_SKIRT -> {
                // Unbuttoned and knotted under the bust.
                sym(p, c) { s ->
                    moveTo(s * 6f, 330f)
                    lineTo(s * 34f, 200f)
                    quadTo(s * 42f, 214f, s * 76f, 216f)
                    quadTo(s * 92f, 222f, s * 88f, 254f)
                    quadTo(s * 96f, 300f, s * 66f, 326f)
                    quadTo(s * 60f, 340f, s * 58f, 346f)
                    lineTo(s * 6f, 340f)
                    close()
                }
                p.fill(poly(-34f, 200f, -20f, 248f, -30f, 236f, -50f, 214f), lighter(c, 0.3f))
                p.fill(poly(34f, 200f, 20f, 248f, 30f, 236f, 50f, 214f), lighter(c, 0.3f))
            }
            Cut.COCKTAIL -> bodice(p, c, 268f, straps = false, strapW = 0f)
            Cut.GOWN -> bodice(p, c, 262f, straps = true, strapW = 4f)
            Cut.BIKINI -> {}
            Cut.GALA -> {
                bodice(p, c, 256f, straps = false, strapW = 0f)
                // Off-shoulder puff sleeves.
                p.oval(-88f, 248f, 26f, 18f, o.second)
                p.oval(88f, 248f, 26f, 18f, o.second)
            }
        }
    }

    /** A fitted bodice with a sweetheart neckline. */
    private fun bodice(p: Painter, c: Int, neckY: Float, straps: Boolean, strapW: Float) {
        if (straps) {
            p.line(-42f, neckY - 4f, -46f, 212f, c, strapW)
            p.line(42f, neckY - 4f, 46f, 212f, c, strapW)
        }
        sym(p, c) { s ->
            moveTo(-s * 2f, neckY + 12f)
            quadTo(s * 18f, neckY - 10f, s * 42f, neckY - 6f)
            quadTo(s * 70f, neckY - 2f, s * 84f, neckY + 6f)
            quadTo(s * 92f, 300f, s * 66f, 326f)
            quadTo(s * 54f, 345f, s * 56f, 380f)
            lineTo(-s * 2f, 380f)
            close()
        }
    }

    private fun skirt(p: Painter, o: Outfit, t: Float) {
        val c = o.main
        when (o.cut) {
            Cut.SUNDRESS -> {
                p.save()
                p.translate(0f, 372f)
                p.scale(1f, 0.68f)
                p.translate(0f, -372f)
                val sway = sin(t * 1.8f) * 6f
                p.fill(shape {
                    moveTo(-56f, 372f)
                    lineTo(56f, 372f)
                    quadTo(110f, 500f, 150f + sway, 630f)
                    quadTo(120f, 650f, 90f + sway, 638f)
                    quadTo(60f, 656f, 30f + sway, 640f)
                    quadTo(0f, 656f, -30f + sway, 640f)
                    quadTo(-60f, 656f, -90f + sway, 638f)
                    quadTo(-120f, 650f, -150f + sway, 630f)
                    quadTo(-110f, 500f, -56f, 372f)
                    close()
                }, c)
                p.rect(-56f, 366f, 112f, 14f, o.second)
                // Little white flowers.
                val spots = floatArrayOf(-80f, 460f, -30f, 430f, 30f, 470f, 80f, 440f, -100f, 570f, -50f, 530f, 0f, 580f, 60f, 545f, 110f, 590f, -10f, 500f)
                var i = 0
                while (i < spots.size) {
                    val fx = spots[i]; val fy = spots[i + 1]
                    for (k in 0 until 5) {
                        val a = k * 1.2566f
                        p.oval(fx + kotlin.math.cos(a) * 6f, fy + kotlin.math.sin(a) * 6f, 4f, 4f, o.second)
                    }
                    p.oval(fx, fy, 3f, 3f, rgb(0xFF, 0xA0, 0x30))
                    i += 2
                }
                p.restore()
            }
            Cut.COCKTAIL -> {
                p.fill(shape {
                    moveTo(-56f, 372f)
                    lineTo(56f, 372f)
                    quadTo(80f, 420f, 96f, 470f)
                    quadTo(102f, 500f, 96f, 532f)
                    quadTo(0f, 548f, -96f, 532f)
                    quadTo(-102f, 500f, -96f, 470f)
                    quadTo(-80f, 420f, -56f, 372f)
                    close()
                }, c)
                val ruche = darker(c, 0.2f)
                p.stroke(shape { moveTo(-50f, 390f); quadTo(0f, 420f, 60f, 400f) }, ruche, 3f)
                p.stroke(shape { moveTo(-70f, 440f); quadTo(0f, 470f, 76f, 444f) }, ruche, 3f)
                p.stroke(shape { moveTo(-70f, 490f); quadTo(0f, 516f, 74f, 494f) }, ruche, 3f)
                // Waist bow.
                p.fill(poly(0f, 378f, -26f, 364f, -26f, 394f), o.second)
                p.fill(poly(0f, 378f, 26f, 364f, 26f, 394f), o.second)
                p.oval(0f, 378f, 7f, 7f, lighter(c, 0.1f))
            }
            Cut.GOWN -> {
                p.fill(shape {
                    moveTo(-56f, 372f)
                    lineTo(56f, 372f)
                    quadTo(86f, 420f, 94f, 470f)
                    quadTo(96f, 600f, 72f, 700f)
                    quadTo(110f, 820f, 170f, 892f)
                    quadTo(0f, 908f, -170f, 892f)
                    quadTo(-110f, 820f, -72f, 700f)
                    quadTo(-96f, 600f, -94f, 470f)
                    quadTo(-86f, 420f, -56f, 372f)
                    close()
                }, c)
                val fold = o.second
                p.stroke(shape { moveTo(-20f, 500f); quadTo(-30f, 720f, -90f, 890f) }, fold, 4f)
                p.stroke(shape { moveTo(24f, 520f); quadTo(36f, 720f, 70f, 890f) }, fold, 4f)
                p.stroke(shape { moveTo(0f, 640f); quadTo(4f, 760f, 0f, 896f) }, fold, 3f)
                // A high thigh slit, with her leg stepping out of it.
                p.fill(shape { moveTo(44f, 560f); quadTo(70f, 720f, 92f, 892f); lineTo(26f, 896f); quadTo(26f, 720f, 44f, 560f); close() }, darker(c, 0.3f))
                p.fill(shape { moveTo(46f, 600f); quadTo(66f, 720f, 62f, 856f); lineTo(36f, 856f); quadTo(30f, 720f, 46f, 600f); close() }, SKIN)
                p.fill(shape { moveTo(32f, 852f); lineTo(66f, 852f); quadTo(70f, 878f, 54f, 894f); lineTo(44f, 894f); quadTo(30f, 878f, 32f, 852f); close() }, o.shoes)
                p.rect(-56f, 368f, 112f, 12f, rgb(0xE8, 0xC8, 0x6A))
            }
            Cut.GALA -> {
                p.fill(shape {
                    moveTo(-56f, 372f)
                    lineTo(56f, 372f)
                    quadTo(170f, 520f, 250f, 870f)
                    quadTo(254f, 896f, 220f, 900f)
                    quadTo(0f, 916f, -220f, 900f)
                    quadTo(-254f, 896f, -250f, 870f)
                    quadTo(-170f, 520f, -56f, 372f)
                    close()
                }, c)
                // Tulle layers.
                val layer = o.second
                p.fill(shape {
                    moveTo(-40f, 420f)
                    quadTo(-150f, 560f, -200f, 760f)
                    quadTo(-120f, 790f, -40f, 760f)
                    quadTo(40f, 790f, 120f, 760f)
                    quadTo(170f, 600f, 40f, 420f)
                    close()
                }, alpha(layer, 0.55f))
                p.stroke(shape { moveTo(-250f, 870f); quadTo(-160f, 840f, -80f, 880f); quadTo(0f, 840f, 80f, 880f); quadTo(160f, 840f, 250f, 870f) }, darker(c, 0.12f), 4f)
                p.rect(-56f, 366f, 112f, 14f, rgb(0xE0, 0xB0, 0x4C))
                p.fill(sparkle(0f, 373f, 12f), WHITE)
            }
            else -> {}
        }
    }

    /** Her full bust, with cleavage showing above low necklines. */
    private fun chest(p: Painter, o: Outfit) {
        val c = o.main
        val shade = alpha(darker(c, 0.4f), 0.5f)
        val shine = alpha(lighter(c, 0.55f), 0.4f)
        val lowCut = when (o.cut) {
            Cut.HOODIE -> null
            Cut.TEE_JEANS -> 262f
            Cut.SUNDRESS -> 256f
            Cut.BLOUSE_SKIRT -> 270f
            Cut.COCKTAIL -> 270f
            Cut.BIKINI -> 268f
            else -> 266f
        }
        if (lowCut == null) {
            // Covered up, but still curvy.
            sym(p, c) { s -> moveTo(s * 2f, 252f); quadTo(s * 84f, 236f, s * 88f, 296f); quadTo(s * 80f, 342f, s * 2f, 326f); close() }
            p.stroke(shape { moveTo(-82f, 300f); quadTo(-46f, 346f, -6f, 318f) }, shade, 3f)
            p.stroke(shape { moveTo(82f, 300f); quadTo(46f, 346f, 6f, 318f) }, shade, 3f)
            p.oval(-44f, 272f, 20f, 12f, shine)
            p.oval(44f, 272f, 20f, 12f, shine)
            p.line(-14f, 200f, -18f, 300f, WHITE, 4f)
            p.line(14f, 200f, 18f, 300f, WHITE, 4f)
            return
        }
        val y = lowCut
        // Skin above the neckline...
        p.oval(-38f, y + 18f, 44f, 36f, SKIN)
        p.oval(38f, y + 18f, 44f, 36f, SKIN)
        p.stroke(shape { moveTo(-4f, y - 18f); quadTo(2f, y - 2f, 0f, y + 14f) }, SKIN_SHADE, 3f)
        p.stroke(shape { moveTo(-30f, y - 14f); quadTo(-12f, y - 20f, -4f, y - 10f) }, alpha(SKIN_SHADE, 0.6f), 2f)
        p.stroke(shape { moveTo(30f, y - 14f); quadTo(12f, y - 20f, 4f, y - 10f) }, alpha(SKIN_SHADE, 0.6f), 2f)
        // ...and the fabric cups below it.
        sym(p, c) { s ->
            moveTo(s * 2f, y + 12f)
            quadTo(s * 36f, y - 8f, s * 84f, y + 2f)
            quadTo(s * 96f, y + 50f, s * 70f, y + 70f)
            quadTo(s * 30f, y + 84f, s * 2f, y + 60f)
            close()
        }
        p.stroke(shape { moveTo(-80f, y + 46f); quadTo(-44f, y + 88f, -4f, y + 58f) }, shade, 3f)
        p.stroke(shape { moveTo(80f, y + 46f); quadTo(44f, y + 88f, 4f, y + 58f) }, shade, 3f)
        p.oval(-46f, y + 18f, 18f, 9f, shine)
        p.oval(46f, y + 18f, 18f, 9f, shine)
        when (o.cut) {
            Cut.BIKINI -> {
                // Halter strings and a centre ring.
                p.line(-40f, y - 4f, -16f, 196f, c, 3.5f)
                p.line(40f, y - 4f, 16f, 196f, c, 3.5f)
                p.line(-84f, y + 20f, -92f, y + 30f, c, 3.5f)
                p.line(84f, y + 20f, 92f, y + 30f, c, 3.5f)
                p.oval(0f, y + 24f, 7f, 7f, rgb(0xEC, 0xC8, 0x5C))
                p.oval(0f, y + 24f, 4f, 4f, SKIN)
            }
            Cut.BLOUSE_SKIRT -> {
                // The knot.
                p.fill(poly(0f, 340f, -26f, 330f, -20f, 356f), darker(c, 0.1f))
                p.fill(poly(0f, 340f, 26f, 330f, 20f, 356f), darker(c, 0.1f))
                p.oval(0f, 340f, 9f, 8f, c)
            }
            Cut.TEE_JEANS -> p.heartIcon(-46f, y + 40f, 9f, PINK)
            else -> {}
        }
    }

    private fun necklace(p: Painter, o: Outfit, t: Float) {
        val gold = rgb(0xEC, 0xC8, 0x5C)
        p.stroke(shape { moveTo(-26f, 196f); quadTo(0f, 238f, 26f, 196f) }, gold, 2.5f)
        val gem = when (o.cut) {
            Cut.GOWN -> rgb(0xB8, 0xEC, 0xFF)
            Cut.GALA -> rgb(0x9C, 0xE4, 0xFF)
            else -> rgb(0xFF, 0x40, 0x70)
        }
        p.fill(heart(0f, 226f, 9f), gem)
        if ((t % 2.5f) < 0.6f) p.fill(sparkle(5f, 220f, 8f), WHITE)
    }

    private fun arms(p: Painter, o: Outfit, pose: Pose) {
        val sleeve = when (o.cut) {
            Cut.HOODIE -> 2 // long
            Cut.TEE_JEANS -> 1 // short
            Cut.BLOUSE_SKIRT -> 3 // three-quarter
            else -> 0
        }
        val sleeveColor = o.main
        val width = if (o.cut == Cut.HOODIE) 40f else 30f
        for (s in floatArrayOf(-1f, 1f)) {
            val sx = s * 80f
            val sy = 232f
            val ex: Float; val ey: Float; val hx: Float; val hy: Float
            if (pose.wave && s > 0) {
                val w = sin(pose.time * 8f) * 14f
                ex = 150f; ey = 260f
                hx = 158f + w; hy = 140f
            } else if (pose.hip && s < 0) {
                ex = -138f; ey = 330f
                hx = -78f; hy = 400f
            } else if (pose.face == 4 && s > 0) {
                // Hand raised to her lips for a blown kiss.
                ex = 120f; ey = 330f
                hx = 30f; hy = 170f
            } else {
                ex = s * 104f; ey = 368f
                hx = s * 112f; hy = 486f
            }
            p.line(sx, sy, ex, ey, SKIN, width)
            p.line(ex, ey, hx, hy, SKIN, width - 5f)
            when (sleeve) {
                2 -> {
                    p.line(sx, sy, ex, ey, sleeveColor, width + 6f)
                    p.line(ex, ey, hx, hy, sleeveColor, width + 2f)
                    // Cuff.
                    p.line(hx + (ex - hx) * 0.12f, hy + (ey - hy) * 0.12f, hx, hy, darker(sleeveColor, 0.1f), width + 2f)
                }
                1 -> p.line(sx, sy, sx + (ex - sx) * 0.45f, sy + (ey - sy) * 0.45f, sleeveColor, width + 8f)
                3 -> {
                    p.line(sx, sy, ex, ey, sleeveColor, width + 6f)
                    p.line(ex, ey, ex + (hx - ex) * 0.5f, ey + (hy - ey) * 0.5f, sleeveColor, width + 4f)
                }
            }
            // Hand.
            val raised = (pose.wave && s > 0) || (pose.face == 4 && s > 0) || (pose.hip && s < 0)
            p.oval(hx, hy + (if (raised) -8f else 14f), 15f, 19f, SKIN)
            if (o.cut == Cut.GALA || o.cut == Cut.GOWN) {
                // A bracelet.
                p.line(hx - 12f, hy - 6f, hx + 12f, hy - 6f, rgb(0xEC, 0xC8, 0x5C), 5f)
            }
        }
    }

    private fun twinkles(p: Painter, o: Outfit, t: Float) {
        val pts = if (o.cut == Cut.GALA)
            floatArrayOf(-150f, 700f, 120f, 640f, -60f, 520f, 40f, 460f, 180f, 820f, -200f, 840f, 0f, 780f, -30f, 300f, 60f, 340f)
        else
            floatArrayOf(-40f, 300f, 50f, 330f, -70f, 520f, 60f, 600f, -110f, 840f, 120f, 860f, 0f, 720f, 20f, 440f)
        var i = 0
        while (i < pts.size) {
            val ph = (t * 1.6f + i * 0.37f) % 2f
            if (ph < 1f) {
                val r = 10f * sin(ph * Math.PI.toFloat())
                p.fill(sparkle(pts[i], pts[i + 1], r), alpha(WHITE, 0.9f))
            }
            i += 2
        }
    }
}
