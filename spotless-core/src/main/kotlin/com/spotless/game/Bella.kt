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
    fun draw(painter: Painter, o: Outfit, x: Float, footY: Float, scale: Float, pose: Pose) {
        val t = pose.time
        painter.save()
        painter.translate(x, footY)
        painter.rotate(sin(t * 1.1f) * 1.2f)
        val breathe = 1f + sin(t * 2.2f) * 0.006f
        // She is drawn a little smaller than her natural size: petite, not tall.
        painter.scale(scale * 0.9f, scale * 0.9f * breathe)
        painter.translate(0f, -HEIGHT)
        val p = Warp(painter)

        p.head = true
        backHair(p, o, t)
        p.head = false
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
        curves(p, o)
        if (o.necklace) necklace(p, o, t)
        if (o.choker) choker(p, o)
        arms(p, o, pose)
        p.head = true
        head(p, o, pose)
        frontHair(p, o)
        if (pose.mood > 0.6f) longing(p, t)
        p.head = false
        if (o.sparkles) twinkles(p, o, t)
        painter.restore()
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
            Hair.PIGTAILS -> {
                val swing = sin(t * 2.6f) * 7f
                sym(p, HAIR_DARK) { s ->
                    moveTo(s * 46f, 24f)
                    quadTo(s * 128f + swing, 30f, s * 124f + swing, 170f)
                    quadTo(s * 120f + swing, 260f, s * 92f + swing * 1.4f, 318f)
                    quadTo(s * 104f + swing, 210f, s * 62f, 88f)
                    close()
                }
                for (sd in floatArrayOf(-1f, 1f)) {
                    p.stroke(shape { moveTo(sd * 76f, 50f); quadTo(sd * 118f + swing, 110f, sd * 106f + swing, 260f) }, HAIR, 7f)
                    p.stroke(shape { moveTo(sd * 84f, 60f); quadTo(sd * 112f + swing, 130f, sd * 112f + swing, 200f) }, HAIR_LIGHT, 3f)
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
        // The hair on top of the head, with a spiky anime fringe.
        p.fill(shape {
            moveTo(-72f, 128f)
            cubicTo(-86f, -14f, 86f, -14f, 72f, 128f)
            lineTo(66f, 128f)
            quadTo(64f, 112f, 60f, 104f)
            quadTo(56f, 72f, 42f, 56f)
            quadTo(36f, 74f, 26f, 86f)
            quadTo(22f, 64f, 12f, 52f)
            quadTo(8f, 70f, 0f, 82f)
            quadTo(-6f, 64f, -14f, 52f)
            quadTo(-20f, 74f, -30f, 88f)
            quadTo(-34f, 68f, -44f, 58f)
            quadTo(-54f, 80f, -58f, 106f)
            quadTo(-62f, 116f, -66f, 128f)
            close()
        }, HAIR)
        // Strand lines and a glossy shine band.
        val strand = alpha(HAIR_DARK, 0.8f)
        for (x in floatArrayOf(-44f, -14f, 12f, 42f)) p.stroke(shape { moveTo(x, 54f); quadTo(x * 0.7f, 30f, x * 0.4f, 8f) }, strand, 2.5f)
        p.stroke(shape { moveTo(-54f, 34f); quadTo(-36f, 20f, -14f, 17f) }, alpha(HAIR_LIGHT, 0.85f), 5f)
        p.stroke(shape { moveTo(14f, 17f); quadTo(34f, 20f, 48f, 32f) }, alpha(HAIR_LIGHT, 0.85f), 5f)
        p.stroke(shape { moveTo(-56f, 36f); quadTo(-40f, 26f, -26f, 22f) }, alpha(WHITE, 0.7f), 3f)
        if (o.hair == Hair.UPDO) {
            // Loose curls framing the face.
            p.stroke(shape { moveTo(-64f, 110f); quadTo(-74f, 150f, -62f, 175f); quadTo(-56f, 190f, -66f, 200f) }, HAIR, 5f)
            p.stroke(shape { moveTo(64f, 110f); quadTo(74f, 150f, 62f, 175f); quadTo(56f, 190f, 66f, 200f) }, HAIR, 5f)
        }
        if (o.hair == Hair.PIGTAILS) {
            // Bows in team colours.
            for (sd in floatArrayOf(-1f, 1f)) {
                val bx = sd * 62f
                p.fill(poly(bx, 40f, bx - 18f, 26f, bx - 16f, 54f), o.main)
                p.fill(poly(bx, 40f, bx + 18f, 26f, bx + 16f, 54f), o.main)
                p.oval(bx, 40f, 6f, 6f, o.second)
            }
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
        p.oval(-63f, 108f, 9f, 15f, SKIN_SHADE)
        p.oval(63f, 108f, 9f, 15f, SKIN_SHADE)
        // Anime face: wide cheeks tapering to a small pointed chin.
        p.fill(shape {
            moveTo(-63f, 90f)
            cubicTo(-63f, 20f, 63f, 20f, 63f, 90f)
            cubicTo(63f, 128f, 26f, 168f, 0f, 178f)
            cubicTo(-26f, 168f, -63f, 128f, -63f, 90f)
            close()
        }, SKIN)
        // Shadow cast by the fringe.
        p.fill(shape { moveTo(-60f, 94f); quadTo(0f, 76f, 60f, 94f); quadTo(0f, 86f, -60f, 94f); close() }, alpha(SKIN_SHADE, 0.3f))
        if (o.earrings) {
            val c = if (o.cut == Cut.GOWN) rgb(0xD8, 0xF4, 0xFF) else rgb(0xFF, 0xE6, 0x8A)
            p.line(-63f, 122f, -63f, 136f, rgb(0xE8, 0xC0, 0x5A), 2.5f)
            p.line(63f, 122f, 63f, 136f, rgb(0xE8, 0xC0, 0x5A), 2.5f)
            p.oval(-63f, 142f, 6f, 8f, c)
            p.oval(63f, 142f, 6f, 8f, c)
        }
        // Blush, with anime hatching, deepens as she falls for you.
        val blush = alpha(rgb(0xFF, 0x5E, 0x84), 0.2f + 0.45f * m)
        for (s in floatArrayOf(-1f, 1f)) {
            p.oval(s * 38f, 134f, 17f, 9f, blush)
            if (m > 0.15f) for (k in 0..2) {
                val hx = s * 38f + (k - 1) * 9f
                p.line(hx + 3f, 129f, hx - 3f, 139f, alpha(rgb(0xE8, 0x40, 0x6A), 0.25f + 0.5f * m), 2f)
            }
        }

        val blink = (pose.time % 3.7f) < 0.13f
        // She looks at you through half-closed eyes once she wants you.
        val lid = if (m > 0.3f) 3f + 9f * m else 0f
        val smitten = m > 0.6f
        for (s in floatArrayOf(-1f, 1f)) {
            val ex = s * 27f
            val ey = 108f
            // Thin brows: soft and raised, or angled when pouty.
            val browTilt = if (pose.face == 2) 6f else -3f
            p.stroke(shape { moveTo(ex - s * 15f, ey - 30f + browTilt); quadTo(ex, ey - 38f, ex + s * 17f, ey - 31f - browTilt * 0.3f) }, BROW, 3f)
            if (m > 0.3f) {
                // Smoky eyeshadow.
                p.oval(ex + s * 3f, ey - 12f, 21f, 12f, alpha(rgb(0xB0, 0x4A, 0x8A), 0.12f + 0.3f * m))
            }
            val winking = (pose.face == 3 || pose.face == 4) && s > 0
            if (blink || pose.face == 1 || winking) {
                // Closed, happy eyes: a curved lash line.
                p.stroke(shape { moveTo(ex - 17f, ey + 2f); quadTo(ex, ey - 14f, ex + 17f, ey + 2f) }, LASH, 5f)
                p.line(ex + s * 17f, ey + 1f, ex + s * 24f, ey - 5f, LASH, 3f)
                continue
            }
            // Big, glossy anime eyes.
            p.oval(ex, ey + 2f, 17f, 21f, WHITE)
            p.oval(ex, ey + 4f, 13.5f, 18.5f, darker(EYE, 0.5f))
            p.oval(ex, ey + 5f, 12f, 17f, EYE)
            p.oval(ex, ey - 6f, 12f, 9f, darker(EYE, 0.35f))
            p.oval(ex, ey + 14f, 8f, 6f, lighter(EYE, 0.45f))
            if (smitten) {
                p.fill(heart(ex, ey + 5f, 8f), rgb(0xE8, 0x2A, 0x6A))
            } else {
                p.oval(ex, ey + 5f, 5f, 8f, rgb(0x14, 0x30, 0x22))
            }
            p.oval(ex + 5f, ey - 3f, 5.5f, 6.5f, WHITE)
            p.oval(ex - 6f, ey + 13f, 2.5f, 2.5f, WHITE)
            p.fill(sparkle(ex - 6f, ey - 6f, 4f), alpha(WHITE, 0.9f))
            // Upper lid and heavy lash line.
            val lashTop = ey - 24f + lid * 1.4f
            if (lid > 0f) {
                p.fill(shape {
                    moveTo(ex - 19f, ey)
                    quadTo(ex, ey - 34f, ex + 19f, ey)
                    quadTo(ex, lashTop, ex - 19f, ey)
                    close()
                }, SKIN)
            }
            p.stroke(shape { moveTo(ex - 18f, ey); quadTo(ex, lashTop, ex + 18f, ey) }, LASH, 5.5f)
            p.fill(shape {
                moveTo(ex + s * 14f, ey - 8f + lid * 0.4f)
                quadTo(ex + s * 24f, ey - 10f, ex + s * 29f, ey - 19f)
                quadTo(ex + s * 25f, ey - 3f, ex + s * 16f, ey)
                close()
            }, LASH)
            for (k in 0..2) p.line(ex + s * (6f + k * 5f), ey - 12f + lid * 0.6f + k * 2f, ex + s * (9f + k * 7f), ey - 20f + lid * 0.6f + k * 2f, LASH, 2.5f)
            // Lower lashes.
            p.stroke(shape { moveTo(ex + s * 6f, ey + 23f); quadTo(ex + s * 14f, ey + 21f, ex + s * 17f, ey + 14f) }, alpha(LASH, 0.7f), 2f)
        }
        // A tiny anime nose.
        p.line(3f, 130f, 0f, 134f, SKIN_SHADE, 2.5f)

        // Mouth.
        val lipColor = o.lips
        when {
            pose.talking -> {
                p.fill(shape { moveTo(-9f, 150f); quadTo(0f, 147f, 9f, 150f); quadTo(0f, 164f, -9f, 150f); close() }, rgb(0x8A, 0x22, 0x3A))
                p.oval(0f, 157f, 4f, 2.5f, rgb(0xFF, 0x8A, 0x9A))
            }
            pose.face == 4 -> {
                // Puckered up, blowing a kiss.
                p.oval(4f, 153f, 6f, 6f, lipColor)
                p.oval(4f, 153f, 2f, 2.5f, darker(lipColor, 0.35f))
            }
            pose.face == 3 -> {
                // A playful smirk.
                p.fill(shape { moveTo(-11f, 151f); quadTo(4f, 159f, 14f, 146f); quadTo(4f, 155f, -11f, 151f); close() }, lipColor)
            }
            pose.face == 2 -> {
                p.fill(shape { moveTo(-7f, 155f); quadTo(0f, 150f, 7f, 155f); quadTo(0f, 158f, -7f, 155f); close() }, lipColor)
            }
            smitten && pose.face == 0 -> {
                // Lips parted, a little breathless.
                p.fill(shape { moveTo(-10f, 150f); quadTo(0f, 146f, 10f, 150f); quadTo(0f, 162f, -10f, 150f); close() }, rgb(0x8A, 0x22, 0x3A))
                p.fill(shape { moveTo(-10f, 150f); quadTo(0f, 146f, 10f, 150f); quadTo(0f, 151f, -10f, 150f); close() }, lipColor)
                p.fill(shape { moveTo(-8f, 154f); quadTo(0f, 164f, 8f, 154f); quadTo(0f, 158f, -8f, 154f); close() }, lipColor)
                p.oval(-2f, 157f, 3f, 1.2f, alpha(WHITE, 0.6f))
            }
            else -> {
                val w = 11f + m * 4f + (if (pose.face == 1) 4f else 0f)
                val curve = 5f + m * 4f + (if (pose.face == 1) 5f else 0f)
                p.fill(shape {
                    moveTo(-w, 150f)
                    quadTo(0f, 150f + curve * 2f, w, 150f)
                    quadTo(0f, 152f + curve, -w, 150f)
                    close()
                }, if (pose.face == 1) rgb(0x8A, 0x22, 0x3A) else lipColor)
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
            // Shading and shine that round out her thighs and calves.
            val shade = alpha(SKIN_SHADE, 0.55f)
            sym(p, shade) { s ->
                moveTo(s * 86f, 476f)
                quadTo(s * 90f, 600f, s * 60f, 690f)
                lineTo(s * 54f, 688f)
                quadTo(s * 76f, 600f, s * 76f, 500f)
                close()
            }
            sym(p, shade) { s ->
                moveTo(s * 60f, 712f)
                quadTo(s * 66f, 770f, s * 42f, 852f)
                lineTo(s * 40f, 850f)
                quadTo(s * 56f, 780f, s * 54f, 716f)
                close()
            }
            p.stroke(shape { moveTo(-8f, 560f); quadTo(-14f, 630f, -20f, 680f) }, shade, 2.5f)
            p.stroke(shape { moveTo(8f, 560f); quadTo(14f, 630f, 20f, 680f) }, shade, 2.5f)
            p.oval(-46f, 610f, 9f, 46f, alpha(WHITE, 0.22f))
            p.oval(46f, 610f, 9f, 46f, alpha(WHITE, 0.22f))
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
                Cut.TEE_JEANS, Cut.CHEER -> {
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
            Cut.CHEER -> {
                // A pleated cheer skirt with a white trim.
                sym(p, o.main) { s ->
                    moveTo(-s * 2f, 398f)
                    lineTo(s * 66f, 398f)
                    quadTo(s * 86f, 450f, s * 112f, 540f)
                    lineTo(-s * 2f, 548f)
                    close()
                }
                p.line(-108f, 526f, 108f, 526f, o.second, 7f)
                for (k in -4..4) {
                    p.line(k * 12f, 410f, k * 26f, 544f, darker(o.main, 0.22f), 2.5f)
                }
                p.rect(-66f, 396f, 132f, 14f, o.second)
                p.oval(0f, 380f, 2.5f, 4f, SKIN_SHADE)
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
            Cut.CHEER -> {
                sym(p, c) { s ->
                    moveTo(s * 2f, 296f)
                    lineTo(s * 30f, 202f)
                    quadTo(s * 46f, 206f, s * 62f, 210f)
                    quadTo(s * 66f, 246f, s * 86f, 262f)
                    quadTo(s * 92f, 304f, s * 66f, 330f)
                    quadTo(s * 62f, 340f, s * 60f, 346f)
                    lineTo(s * 2f, 350f)
                    close()
                }
                // White piping along the V and the hem.
                p.line(-30f, 202f, -2f, 292f, o.second, 5f)
                p.line(30f, 202f, 2f, 292f, o.second, 5f)
                p.line(-60f, 344f, 60f, 344f, o.second, 6f)
            }
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
                val sway = sin(t * 1.8f) * 6f
                fun sq(y: Float) = 372f + (y - 372f) * 0.68f
                p.fill(shape {
                    moveTo(-56f, 372f)
                    lineTo(56f, 372f)
                    quadTo(110f, sq(500f), 150f + sway, sq(630f))
                    quadTo(120f, sq(650f), 90f + sway, sq(638f))
                    quadTo(60f, sq(656f), 30f + sway, sq(640f))
                    quadTo(0f, sq(656f), -30f + sway, sq(640f))
                    quadTo(-60f, sq(656f), -90f + sway, sq(638f))
                    quadTo(-120f, sq(650f), -150f + sway, sq(630f))
                    quadTo(-110f, sq(500f), -56f, sq(372f))
                    close()
                }, c)
                p.rect(-56f, 366f, 112f, 14f, o.second)
                // Little white flowers.
                val spots = floatArrayOf(-80f, 460f, -30f, 430f, 30f, 470f, 80f, 440f, -100f, 570f, -50f, 530f, 0f, 580f, 60f, 545f, 110f, 590f, -10f, 500f)
                var i = 0
                while (i < spots.size) {
                    val fx = spots[i]; val fy = sq(spots[i + 1])
                    for (k in 0 until 5) {
                        val a = k * 1.2566f
                        p.oval(fx + kotlin.math.cos(a) * 6f, fy + kotlin.math.sin(a) * 6f, 4f, 4f, o.second)
                    }
                    p.oval(fx, fy, 3f, 3f, rgb(0xFF, 0xA0, 0x30))
                    i += 2
                }
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
            Cut.CHEER -> 276f
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
        p.oval(-35f, y + 18f, 39f, 33f, SKIN)
        p.oval(35f, y + 18f, 39f, 33f, SKIN)
        p.stroke(shape { moveTo(-4f, y - 18f); quadTo(2f, y - 2f, 0f, y + 14f) }, SKIN_SHADE, 3f)
        p.stroke(shape { moveTo(-30f, y - 14f); quadTo(-12f, y - 20f, -4f, y - 10f) }, alpha(SKIN_SHADE, 0.6f), 2f)
        p.stroke(shape { moveTo(30f, y - 14f); quadTo(12f, y - 20f, 4f, y - 10f) }, alpha(SKIN_SHADE, 0.6f), 2f)
        // ...and the fabric cups below it.
        sym(p, c) { s ->
            moveTo(s * 2f, y + 12f)
            quadTo(s * 34f, y - 6f, s * 78f, y + 4f)
            quadTo(s * 88f, y + 48f, s * 64f, y + 68f)
            quadTo(s * 28f, y + 80f, s * 2f, y + 58f)
            close()
        }
        p.stroke(shape { moveTo(-74f, y + 44f); quadTo(-40f, y + 84f, -4f, y + 56f) }, shade, 3f)
        p.stroke(shape { moveTo(74f, y + 44f); quadTo(40f, y + 84f, 4f, y + 56f) }, shade, 3f)
        p.oval(-42f, y + 18f, 16f, 8f, shine)
        p.oval(42f, y + 18f, 16f, 8f, shine)
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
            Cut.CHEER -> p.fill(star(-46f, y + 36f, 12f), o.second)
            else -> {}
        }
    }

    /** Cel shading that defines her waist, hips and collarbones. */
    private fun curves(p: Painter, o: Outfit) {
        if (o.cut == Cut.HOODIE) return
        // Shadows hugging the waist and flaring out over the hips, over skin or fabric alike.
        val tint = alpha(rgb(0x8A, 0x2A, 0x40), 0.16f)
        sym(p, tint) { s ->
            moveTo(s * 70f, 336f)
            quadTo(s * 52f, 380f, s * 60f, 420f)
            quadTo(s * 72f, 452f, s * 90f, 474f)
            lineTo(s * 94f, 474f)
            quadTo(s * 62f, 410f, s * 72f, 336f)
            close()
        }
        val bareCollar = o.cut != Cut.TEE_JEANS
        if (bareCollar) {
            val bone = alpha(SKIN_SHADE, 0.8f)
            p.stroke(shape { moveTo(-8f, 210f); quadTo(-28f, 200f, -50f, 204f) }, bone, 2.5f)
            p.stroke(shape { moveTo(8f, 210f); quadTo(28f, 200f, 50f, 204f) }, bone, 2.5f)
        }
        val bareMidriff = o.cut == Cut.TEE_JEANS || o.cut == Cut.BLOUSE_SKIRT || o.cut == Cut.BIKINI || o.cut == Cut.CHEER
        if (bareMidriff) {
            val soft = alpha(SKIN_SHADE, 0.45f)
            p.stroke(shape { moveTo(0f, 352f); quadTo(-2f, 372f, 0f, 392f) }, soft, 2f)
            if (o.cut == Cut.BIKINI) {
                p.stroke(shape { moveTo(-62f, 440f); quadTo(-52f, 460f, -48f, 478f) }, soft, 2.5f)
                p.stroke(shape { moveTo(62f, 440f); quadTo(52f, 460f, 48f, 478f) }, soft, 2.5f)
            }
        }
    }

    /** A black velvet choker with a little heart charm. */
    private fun choker(p: Painter, o: Outfit) {
        p.fill(shape { moveTo(-19f, 184f); quadTo(0f, 194f, 19f, 184f); lineTo(19f, 194f); quadTo(0f, 204f, -19f, 194f); close() }, rgb(0x1E, 0x14, 0x22))
        p.fill(heart(0f, 206f, 6f), o.main)
    }

    /** Little hearts drifting up around her once she's smitten. */
    private fun longing(p: Painter, t: Float) {
        for (i in 0 until 3) {
            val ph = (t * 0.45f + i / 3f) % 1f
            val x = (if (i % 2 == 0) -1f else 1f) * (96f + 18f * sin(ph * 6.28f + i))
            val y = 120f - ph * 150f
            p.fill(heart(x, y, 9f + 5f * (1f - ph)), alpha(rgb(0xFF, 0x4F, 0x8B), (1f - ph) * 0.85f))
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
            if (o.cut == Cut.CHEER && !(pose.hip && s < 0) && !(pose.face == 4 && s > 0)) {
                val py = hy + (if (raised) -8f else 18f)
                for (k in 0 until 14) {
                    val a = k * 0.449f + s
                    val r = 22f + (k % 3) * 6f
                    p.oval(hx + kotlin.math.cos(a) * r, py + kotlin.math.sin(a) * r, 15f, 15f, if (k % 2 == 0) o.main else o.second)
                }
                p.oval(hx, py, 18f, 18f, o.main)
            }
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

/**
 * Reshapes Bella's body as it is drawn: a smaller head (adult proportions), a shorter torso,
 * longer legs and a narrower waist. The art is authored in the original coordinates and every
 * point goes through [mapX]/[mapY] on its way to the real painter.
 */
private class Warp(private val p: Painter) : Painter {
    /** True while drawing the head and hair, which are also scaled down around the chin. */
    var head = false

    private fun hx(x: Float) = if (head) x * HEAD else x
    private fun hy(y: Float) = if (head) CHIN + (y - CHIN) * HEAD else y

    fun mapY(y0: Float): Float {
        val y = hy(y0)
        if (y <= KY[0]) return y
        for (i in 1 until KY.size) {
            if (y <= KY[i]) {
                val t = (y - KY[i - 1]) / (KY[i] - KY[i - 1])
                return NY[i - 1] + (NY[i] - NY[i - 1]) * t
            }
        }
        return NY.last() + (y - KY.last())
    }

    /** Vertical stretch at y, for scaling heights. */
    private fun slope(y0: Float): Float = (mapY(y0 + 1f) - mapY(y0 - 1f)) / 2f

    /** How much narrower the body is at this height: the waist is pulled in. */
    private fun pinch(y0: Float): Float {
        val y = hy(y0)
        val d = (y - 372f) / 58f
        return 1f - 0.08f * kotlin.math.exp(-d * d)
    }

    fun mapX(x0: Float, y0: Float) = hx(x0) * pinch(y0)

    private fun warp(s: Shape): Shape {
        val out = Shape()
        s.forEach(
            { x, y -> out.moveTo(mapX(x, y), mapY(y)) },
            { x, y -> out.lineTo(mapX(x, y), mapY(y)) },
            { a, b, x, y -> out.quadTo(mapX(a, b), mapY(b), mapX(x, y), mapY(y)) },
            { a, b, c, d, x, y -> out.cubicTo(mapX(a, b), mapY(b), mapX(c, d), mapY(d), mapX(x, y), mapY(y)) },
            { out.close() },
        )
        return out
    }

    private val k get() = if (head) HEAD else 1f

    override fun rect(x: Float, y: Float, w: Float, h: Float, color: Int) =
        fill(poly(x, y, x + w, y, x + w, y + h, x, y + h), color)

    override fun roundRect(x: Float, y: Float, w: Float, h: Float, r: Float, color: Int) {
        val l = mapX(x, y + h / 2); val rr = mapX(x + w, y + h / 2)
        val t = mapY(y); val b = mapY(y + h)
        p.roundRect(l, t, rr - l, b - t, r * k, color)
    }

    override fun oval(cx: Float, cy: Float, rx: Float, ry: Float, color: Int) =
        p.oval(mapX(cx, cy), mapY(cy), rx * k * pinch(cy), ry * k * slope(cy), color)

    override fun fill(p: Shape, color: Int) = this.p.fill(warp(p), color)
    override fun stroke(p: Shape, color: Int, width: Float) = this.p.stroke(warp(p), color, width * k)
    override fun line(x1: Float, y1: Float, x2: Float, y2: Float, color: Int, width: Float) =
        p.line(mapX(x1, y1), mapY(y1), mapX(x2, y2), mapY(y2), color, width * k)
    override fun text(s: String, x: Float, y: Float, size: Float, color: Int, align: Int) =
        p.text(s, mapX(x, y), mapY(y), size * k, color, align)
    override fun textWidth(s: String, size: Float) = p.textWidth(s, size * k)
    override fun save() = p.save()
    override fun restore() = p.restore()
    override fun translate(x: Float, y: Float) = p.translate(x, y)
    override fun scale(sx: Float, sy: Float) = p.scale(sx, sy)
    override fun rotate(degrees: Float) = p.rotate(degrees)

    companion object {
        const val HEAD = 0.86f
        const val CHIN = 176f
        // Original heights of chin, shoulders, bust, waist, hips, crotch, knees, ankles and soles,
        // and where they end up.
        val KY = floatArrayOf(178f, 216f, 290f, 376f, 466f, 540f, 690f, 856f, 892f)
        val NY = floatArrayOf(178f, 212f, 278f, 348f, 424f, 492f, 692f, 860f, 892f)
    }
}
