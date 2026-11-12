package com.critters.game

/**
 * Battle pictures for every species, painted from simple shapes. Front views face
 * the player; back views (your own critter) leave out the face.
 */
object CritterArt {
    private fun c(v: Long) = v.toInt()

    private val ORANGE = c(0xFFF08838)
    private val CREAM = c(0xFFF8E0A8)
    private val RED = c(0xFFE83828)
    private val YELLOW = c(0xFFF8D038)
    private val BLUE = c(0xFF58A0F0)
    private val DEEP = c(0xFF4080D8)
    private val PALE = c(0xFFD0E8F8)
    private val GREEN = c(0xFF78C850)
    private val LEAF = c(0xFF48A040)
    private val PINK = c(0xFFF8A0B8)
    private val BROWN = c(0xFFC08850)
    private val TAN = c(0xFFF0D8A8)
    private val BEAK = c(0xFFF8A030)
    private val LAVENDER = c(0xFFB890D8)
    private val LILAC = c(0xFFE8D8F0)
    private val RAT = c(0xFFC8A070)
    private val BLACK = c(0xFF383040)
    private val ZAP = c(0xFF68C8E8)
    private val VOLT = c(0xFF4870D0)
    private val GRAY = c(0xFFA8A8A0)
    private val MOSS = c(0xFF70A050)
    private val GRUB = c(0xFF98D048)
    private val MOTH = c(0xFFE0C8F0)
    private val FUZZ = c(0xFFF0E8C8)
    private val GHOST = c(0xFFB8A8E8)
    private val SHADOW = c(0xFF7858B0)
    private val TEAL = c(0xFF48B8D8)
    private val FIN = c(0xFFF8B848)
    private val SHARK = c(0xFF6888B8)
    private val CAP = c(0xFFE84838)
    private val GOLD = c(0xFFF8C830)

    private val cache = HashMap<Int, Sprite>()

    fun front(id: Int): Sprite = cache.getOrPut(id) { paint(id, false) }

    fun back(id: Int): Sprite = cache.getOrPut(-id) { paint(id, true) }

    /** A 16x16 icon for menus. */
    fun icon(id: Int): Sprite = cache.getOrPut(1000 + id) {
        val f = front(id)
        Sprite(16, 16, IntArray(256) { i -> f.px[(i / 16 * 3) * 48 + (i % 16) * 3 + 1] })
    }

    private fun paint(id: Int, back: Boolean): Sprite {
        val p = Painter()
        val face = !back
        when (id) {
            1 -> { // EMBIT
                p.ell(17f, 44f, 4f, 2.5f, ORANGE)
                p.ell(24f, 35f, 10f, 9f, ORANGE)
                if (face) p.ell(24f, 37f, 6f, 6f, CREAM)
                p.ell(14f, 33f, 3f, 3.5f, ORANGE)
                p.poly(13f, 16f, 9f, 3f, 20f, 11f, c = ORANGE)
                p.poly(13f, 13f, 11f, 6f, 16f, 11f, c = PINK, flat = true)
                p.ell(24f, 20f, 12f, 10f, ORANGE)
                if (face) {
                    p.ell(24f, 25f, 5f, 3f, CREAM)
                    p.eye(19f, 19f, 2.2f)
                    p.line(22, 26, 23, 27, OUTLINE)
                    p.ell(14f, 23f, 2f, 1.2f, PINK, flat = true)
                }
                p.mirror()
                if (back) {
                    p.ell(31f, 43f, 6f, 3f, ORANGE)
                    p.ell(38f, 34f, 4f, 6f, RED); p.ell(38f, 36f, 2.5f, 4f, YELLOW)
                } else {
                    p.under { ell(37f, 40f, 6f, 3f, ORANGE); ell(41f, 30f, 4f, 6f, RED); ell(41f, 32f, 2.5f, 4f, YELLOW) }
                }
            }
            2 -> { // BLAZOR
                p.ell(16f, 43f, 5f, 4f, ORANGE)
                p.ell(24f, 32f, 12f, 11f, ORANGE)
                if (face) p.ell(24f, 34f, 7f, 8f, CREAM)
                p.ell(12f, 30f, 4f, 6f, ORANGE)
                p.ell(11f, 36f, 2.5f, 1.5f, CREAM)
                p.poly(15f, 13f, 6f, 2f, 18f, 7f, c = RED)
                p.poly(19f, 8f, 17f, -1f, 23f, 5f, c = RED)
                p.poly(15f, 12f, 9f, 5f, 17f, 8f, c = YELLOW, flat = true)
                p.poly(13f, 20f, 4f, 15f, 14f, 15f, c = RED)
                p.ell(24f, 15f, 10f, 9f, ORANGE)
                p.poly(18f, 9f, 15f, 2f, 21f, 7f, c = CREAM)
                if (face) {
                    p.ell(24f, 20f, 4.5f, 2.5f, CREAM)
                    p.eye(20f, 15f, 2f)
                    p.line(17, 11, 21, 13, OUTLINE)
                    p.line(22, 22, 23, 22, OUTLINE)
                }
                p.mirror()
                if (back) {
                    p.ell(36f, 42f, 6f, 3f, ORANGE); p.ell(41f, 34f, 4f, 7f, RED); p.ell(41f, 36f, 2.5f, 4.5f, YELLOW)
                } else {
                    p.under { ell(39f, 38f, 5f, 3f, ORANGE); ell(43f, 27f, 4f, 7f, RED); ell(43f, 29f, 2.5f, 4.5f, YELLOW) }
                }
            }
            3 -> { // DRIZZLET
                p.ell(17f, 44f, 4f, 2.5f, BLUE)
                p.ell(24f, 35f, 10f, 9f, BLUE)
                if (face) p.ell(24f, 37f, 6f, 6f, PALE)
                p.ell(15f, 33f, 3f, 3f, BLUE)
                p.ell(15f, 13f, 3.5f, 3.5f, BLUE)
                p.ell(15f, 13f, 1.8f, 1.8f, PINK, flat = true)
                p.ell(24f, 21f, 11f, 9f, BLUE)
                p.poly(24f, 9f, 21f, 15f, 27f, 15f, c = PALE, flat = true)
                if (face) {
                    p.ell(24f, 25f, 6f, 3.5f, PALE)
                    p.ell(24f, 23f, 1.6f, 1.1f, INK, flat = true)
                    p.eye(19f, 19f, 2.2f)
                    p.line(14, 25, 10, 24, OUTLINE)
                    p.line(14, 27, 10, 28, OUTLINE)
                }
                p.mirror()
                if (back) p.ell(24f, 44f, 8f, 3.5f, DEEP) else p.under { ell(38f, 38f, 8f, 4f, DEEP) }
            }
            4 -> { // TIDALON
                p.ell(17f, 44f, 5f, 3f, DEEP)
                p.ell(24f, 32f, 13f, 12f, DEEP)
                if (face) p.ell(24f, 35f, 8f, 8f, PALE)
                p.ell(10f, 34f, 4f, 7f, DEEP)
                p.poly(14f, 13f, 6f, 6f, 15f, 18f, c = PALE)
                p.poly(24f, 1f, 19f, 10f, 29f, 10f, c = PALE)
                p.ell(24f, 16f, 10f, 8.5f, DEEP)
                if (face) {
                    p.ell(24f, 20f, 5f, 3f, PALE)
                    p.ell(24f, 18.5f, 1.5f, 1f, INK, flat = true)
                    p.eye(19f, 15f, 2f)
                    p.line(16, 12, 21, 13, OUTLINE)
                }
                p.mirror()
                if (back) p.ell(24f, 45f, 9f, 3f, PALE) else p.under { ell(40f, 41f, 7f, 4f, DEEP) }
            }
            5 -> { // SPROUTLE
                p.ell(16f, 44f, 4f, 2.5f, GREEN)
                p.rect(23, 9, 1, 10, LEAF)
                p.poly(23f, 14f, 10f, 9f, 15f, 4f, c = LEAF)
                p.ell(24f, 32f, 13f, 11f, GREEN)
                p.ell(15f, 28f, 2f, 2f, lighten(GREEN, 0.4f), flat = true)
                if (face) {
                    p.eye(18f, 30f, 2.5f)
                    p.line(22, 36, 23, 37, OUTLINE)
                    p.ell(14f, 35f, 2f, 1.2f, PINK, flat = true)
                }
                p.mirror()
            }
            6 -> { // THORNOX
                p.ell(14f, 42f, 5f, 5f, GREEN)
                p.ell(17f, 9f, 5f, 4f, PINK); p.ell(24f, 5f, 5f, 4f, PINK)
                p.ell(24f, 11f, 4f, 3.5f, YELLOW)
                p.ell(24f, 33f, 15f, 10f, GREEN)
                p.poly(10f, 30f, 4f, 27f, 9f, 35f, c = LEAF)
                p.poly(16f, 23f, 5f, 15f, 15f, 28f, c = LEAF)
                p.ell(24f, 25f, 9f, 8f, GREEN)
                if (face) {
                    p.eye(20f, 25f, 2f)
                    p.line(17, 21, 21, 23, OUTLINE)
                    p.line(22, 30, 23, 30, OUTLINE)
                }
                p.mirror()
            }
            7 -> { // PIPWING
                p.line(20, 43, 20, 46, BEAK); p.line(19, 46, 21, 46, BEAK)
                p.ell(13f, 32f, 4f, 8f, shade(BROWN, 0.85f))
                p.ell(24f, 32f, 11f, 12f, BROWN)
                if (face) p.ell(24f, 35f, 7f, 8f, TAN)
                p.poly(22f, 16f, 18f, 8f, 25f, 13f, c = BROWN)
                p.ell(24f, 22f, 9f, 8f, BROWN)
                if (face) {
                    p.ell(24f, 24f, 6f, 5f, TAN)
                    p.poly(22f, 25f, 26f, 25f, 24f, 29f, c = BEAK, flat = true)
                    p.eye(20f, 22f, 2f)
                }
                p.mirror()
            }
            8 -> { // GALEHAWK
                p.poly(18f, 24f, 1f, 12f, 3f, 30f, 16f, 37f, c = shade(BROWN, 0.85f))
                p.poly(2f, 13f, 1f, 22f, 6f, 17f, c = TAN, flat = true)
                p.ell(19f, 44f, 3f, 2f, BEAK)
                p.ell(24f, 30f, 10f, 13f, BROWN)
                if (face) p.ell(24f, 33f, 6f, 9f, TAN)
                p.poly(22f, 9f, 13f, 2f, 24f, 6f, c = RED)
                p.ell(24f, 14f, 8f, 7f, BROWN)
                if (face) {
                    p.poly(21f, 16f, 27f, 16f, 24f, 22f, c = BEAK, flat = true)
                    p.eye(20f, 13f, 1.8f)
                    p.line(17, 10, 21, 12, OUTLINE)
                }
                p.mirror()
            }
            9 -> { // NIBBIT
                p.ell(17f, 44f, 4f, 2f, PINK)
                p.ell(24f, 36f, 10f, 8f, LAVENDER)
                if (face) p.ell(24f, 38f, 6f, 5f, LILAC)
                p.ell(13f, 13f, 6f, 6f, LAVENDER)
                p.ell(13f, 13f, 3.5f, 3.5f, PINK, flat = true)
                p.ell(24f, 24f, 11f, 9f, LAVENDER)
                if (face) {
                    p.rect(23, 29, 1, 3, PAPER)
                    p.ell(24f, 26.5f, 1.5f, 1f, PINK, flat = true)
                    p.eye(19f, 22f, 2.2f)
                    p.line(13, 27, 8, 26, OUTLINE)
                }
                p.mirror()
                if (back) p.poly(24f, 40f, 30f, 42f, 38f, 30f, 40f, 31f, 32f, 45f, c = PINK)
                else p.under { poly(30f, 40f, 40f, 33f, 44f, 26f, 46f, 27f, 42f, 36f, 32f, 43f, c = PINK) }
            }
            10 -> { // GNAWLER
                p.ell(16f, 44f, 5f, 2.5f, RAT)
                p.ell(24f, 32f, 13f, 11f, RAT)
                if (face) p.ell(24f, 35f, 8f, 7f, TAN)
                p.ell(12f, 32f, 3f, 5f, RAT)
                p.ell(14f, 11f, 5f, 5f, RAT)
                p.ell(14f, 11f, 2.5f, 2.5f, PINK, flat = true)
                p.ell(24f, 20f, 10f, 8f, RAT)
                if (face) {
                    p.rect(22, 26, 2, 5, PAPER)
                    p.ell(24f, 23f, 1.5f, 1f, INK, flat = true)
                    p.eye(19f, 18f, 2f)
                    p.line(16, 15, 21, 17, OUTLINE)
                }
                p.mirror()
                if (back) p.poly(24f, 42f, 30f, 44f, 44f, 34f, 46f, 36f, 32f, 47f, c = PINK)
                else p.under { poly(34f, 40f, 46f, 30f, 47f, 32f, 36f, 43f, c = PINK) }
            }
            11 -> { // ZAPPUP
                p.ell(17f, 44f, 4f, 2.5f, ZAP)
                p.ell(24f, 36f, 10f, 8f, ZAP)
                if (face) p.ell(24f, 38f, 5f, 5f, PALE)
                p.poly(14f, 18f, 9f, 6f, 19f, 13f, c = ZAP)
                p.poly(10.5f, 10f, 9f, 6f, 13f, 8.5f, c = YELLOW, flat = true)
                p.ell(24f, 24f, 11f, 9f, ZAP)
                p.poly(24f, 14f, 21f, 19f, 24f, 18f, 22f, 22f, 27f, 16f, 24f, 17f, c = YELLOW, flat = true)
                if (face) {
                    p.eye(19f, 24f, 2.2f)
                    p.ell(24f, 28f, 1.4f, 1f, INK, flat = true)
                    p.line(22, 30, 23, 31, OUTLINE)
                }
                p.mirror()
                val bolt = floatArrayOf(34f, 40f, 42f, 30f, 39f, 30f, 47f, 19f, 37f, 32f, 40f, 32f, 32f, 42f)
                if (back) p.poly(*bolt.mapIndexed { i, v -> if (i % 2 == 0) v - 22f else v + 2f }.toFloatArray(), c = YELLOW)
                else p.under { poly(*bolt, c = YELLOW) }
            }
            12 -> { // VOLTHOUND
                p.ell(16f, 42f, 4f, 6f, VOLT)
                p.ell(24f, 34f, 13f, 9f, VOLT)
                p.rect(13, 31, 2, 6, YELLOW)
                if (face) p.ell(24f, 31f, 6f, 6f, PAPER)
                p.poly(16f, 24f, 7f, 22f, 13f, 28f, c = YELLOW)
                p.poly(16f, 14f, 9f, 1f, 20f, 10f, c = VOLT)
                p.poly(11f, 5f, 9f, 1f, 13.5f, 4f, c = YELLOW, flat = true)
                p.ell(24f, 18f, 9f, 8f, VOLT)
                if (face) {
                    p.ell(24f, 22f, 4f, 3f, lighten(VOLT, 0.45f))
                    p.ell(24f, 20.5f, 1.5f, 1f, INK, flat = true)
                    p.eye(20f, 17f, 2f)
                    p.line(17, 14, 21, 16, OUTLINE)
                }
                p.mirror()
                val bolt = floatArrayOf(34f, 36f, 42f, 26f, 38f, 26f, 47f, 14f, 36f, 29f, 40f, 29f, 33f, 40f)
                if (back) p.poly(*bolt.mapIndexed { i, v -> if (i % 2 == 0) v - 24f else v + 4f }.toFloatArray(), c = YELLOW)
                else p.under { poly(*bolt, c = YELLOW) }
            }
            13 -> { // PEBBLIT
                p.ell(9f, 36f, 4f, 3f, GRAY)
                p.ell(24f, 34f, 15f, 12f, GRAY)
                p.ell(14f, 27f, 5f, 4f, GRAY)
                p.line(13, 38, 16, 41, OUTLINE)
                if (face) {
                    p.eye(19f, 32f, 2.5f)
                    p.line(15, 28, 21, 29, OUTLINE)
                    p.line(21, 39, 23, 39, OUTLINE)
                }
                p.mirror()
            }
            14 -> { // BOULDRON
                p.ell(15f, 43f, 6f, 4f, GRAY)
                p.ell(24f, 30f, 14f, 13f, GRAY)
                p.ell(7f, 31f, 6f, 9f, GRAY)
                p.ell(7f, 40f, 5f, 4f, shade(GRAY, 0.9f))
                p.line(16, 26, 19, 31, OUTLINE)
                p.ell(24f, 14f, 8f, 7f, GRAY)
                p.ell(24f, 7f, 5f, 2f, MOSS)
                if (face) {
                    p.eye(21f, 14f, 1.6f)
                    p.line(18, 11, 22, 12, OUTLINE)
                    p.line(22, 18, 23, 18, OUTLINE)
                }
                p.mirror()
            }
            15 -> { // GRUBBLE (side view, faces left)
                p.ell(41f, 40f, 4.5f, 4.5f, GRUB)
                p.ell(34f, 38f, 6f, 6f, GRUB)
                p.ell(25f, 36f, 7f, 7f, GRUB)
                p.ell(34f, 37f, 1.5f, 1.5f, YELLOW, flat = true)
                p.ell(25f, 34f, 1.8f, 1.8f, YELLOW, flat = true)
                for (x in intArrayOf(24, 33, 41)) p.rect(x, 44, 2, 2, shade(GRUB, 0.7f))
                p.line(10, 22, 6, 14, OUTLINE)
                p.ell(6f, 13f, 2f, 2f, RED)
                p.ell(13f, 30f, 9f, 8f, GRUB)
                p.eye(10f, 29f, 2f)
                p.line(5, 34, 7, 34, OUTLINE)
            }
            16 -> { // LUMOTH
                p.ell(12f, 20f, 11f, 9f, MOTH)
                p.ell(10f, 19f, 3.5f, 3.5f, PINK, flat = true)
                p.ell(10f, 19f, 1.5f, 1.5f, SHADOW, flat = true)
                p.ell(14f, 34f, 8f, 7f, MOTH)
                p.ell(13f, 35f, 2f, 2f, c(0xFF88C8F8), flat = true)
                p.ell(24f, 28f, 5f, 12f, FUZZ)
                p.line(21, 12, 16, 3, OUTLINE)
                p.ell(16f, 3f, 2f, 1.5f, YELLOW)
                p.ell(24f, 16f, 6f, 5f, FUZZ)
                if (face) p.eye(21.5f, 16f, 1.8f)
                p.mirror()
            }
            17 -> { // SPOOKIT
                p.ell(8f, 28f, 3f, 2f, GHOST)
                p.poly(10f, 22f, 24f, 22f, 24f, 40f, 10f, 40f, c = GHOST)
                p.ell(12f, 40f, 3f, 3f, GHOST); p.ell(19f, 41f, 3f, 3f, GHOST)
                p.ell(24f, 40f, 2f, 2f, GHOST)
                p.ell(24f, 22f, 14f, 14f, GHOST)
                if (face) {
                    p.ell(18f, 22f, 3f, 4.5f, INK, flat = true)
                    p.set(17, 19, PAPER)
                    p.ell(24f, 31f, 5f, 3f, INK, flat = true)
                    p.ell(24f, 33f, 3f, 2.5f, PINK, flat = true)
                }
                p.mirror()
            }
            18 -> { // GLOOMBRA
                p.poly(16f, 9f, 13f, 1f, 20f, 7f, c = GOLD)
                p.poly(24f, 7f, 24f, -1f, 21f, 7f, c = GOLD)
                p.ell(7f, 28f, 4f, 6f, SHADOW)
                p.poly(5f, 33f, 3f, 37f, 8f, 34f, c = PAPER, flat = true)
                p.poly(9f, 22f, 24f, 22f, 24f, 42f, 9f, 42f, c = SHADOW)
                p.ell(11f, 42f, 3f, 3f, SHADOW); p.ell(18f, 43f, 3f, 3f, SHADOW); p.ell(24f, 42f, 2f, 2f, SHADOW)
                p.ell(24f, 21f, 15f, 14f, SHADOW)
                if (face) {
                    p.poly(13f, 18f, 21f, 21f, 14f, 24f, c = RED, flat = true)
                    p.ell(24f, 30f, 7f, 3f, INK, flat = true)
                    p.poly(19f, 28f, 21f, 28f, 20f, 31f, c = PAPER, flat = true)
                }
                p.mirror()
            }
            19 -> { // FINNOW (faces left)
                p.poly(36f, 31f, 46f, 20f, 46f, 42f, c = FIN)
                p.poly(18f, 23f, 28f, 14f, 30f, 24f, c = FIN)
                p.ell(22f, 31f, 15f, 10f, TEAL)
                p.ell(20f, 35f, 10f, 4f, PALE)
                p.poly(22f, 34f, 30f, 41f, 24f, 41f, c = FIN)
                p.bigEye(13f, 28f, 3f)
                p.line(7, 33, 9, 33, OUTLINE)
            }
            20 -> { // SHARKLE (faces left)
                p.poly(40f, 28f, 47f, 15f, 47f, 43f, c = SHARK)
                p.poly(22f, 21f, 32f, 5f, 35f, 22f, c = SHARK)
                p.ell(24f, 30f, 19f, 11f, SHARK)
                p.ell(22f, 35f, 14f, 5f, PAPER)
                p.poly(20f, 36f, 30f, 46f, 27f, 36f, c = shade(SHARK, 0.8f))
                p.line(6, 34, 16, 36, OUTLINE)
                for (x in intArrayOf(8, 11, 14)) p.set(x, 35, PAPER)
                p.eye(12f, 27f, 2f)
                p.line(9, 24, 14, 25, OUTLINE)
            }
            21 -> { // SHROOMP
                p.ell(18f, 44f, 4f, 2.5f, shade(CREAM, 0.85f))
                p.ell(24f, 34f, 9f, 9f, CREAM)
                p.ell(24f, 18f, 17f, 11f, CAP)
                p.ell(14f, 15f, 3f, 2.5f, PAPER, flat = true)
                p.ell(22f, 10f, 3f, 2f, PAPER, flat = true)
                p.ell(9f, 22f, 2f, 1.5f, PAPER, flat = true)
                if (face) {
                    p.eye(20f, 33f, 2f)
                    p.line(23, 38, 23, 38, OUTLINE)
                    p.ell(16f, 36f, 1.8f, 1f, PINK, flat = true)
                }
                p.mirror()
            }
            22 -> { // SOLARIS
                p.ell(24f, 16f, 15f, 15f, c(0xFFFFF0B0), flat = true)
                p.poly(20f, 22f, 0f, 6f, 1f, 20f, 4f, 30f, 18f, 34f, c = GOLD)
                p.poly(0f, 6f, 2f, 15f, 6f, 10f, c = RED, flat = true)
                p.poly(1f, 20f, 3f, 27f, 7f, 22f, c = RED, flat = true)
                p.poly(22f, 36f, 15f, 47f, 24f, 44f, c = RED)
                p.ell(24f, 30f, 8f, 11f, ORANGE)
                if (face) p.ell(24f, 32f, 5f, 7f, GOLD)
                p.poly(24f, 0f, 20f, 8f, 24f, 8f, c = RED)
                p.poly(19f, 9f, 12f, 1f, 22f, 6f, c = RED)
                p.ell(24f, 14f, 7f, 7f, GOLD)
                if (face) {
                    p.poly(22f, 16f, 26f, 16f, 24f, 21f, c = ORANGE, flat = true)
                    p.eye(20.5f, 13f, 1.8f)
                    p.line(17, 10, 21, 11, OUTLINE)
                }
                p.mirror()
            }
        }
        p.outline()
        val s = p.sprite()
        // Side-view critters just turn around for the back view.
        return if (back && id in listOf(15, 19, 20)) s.flipped() else s
    }

    private inline fun Painter.under(block: Painter.() -> Unit) {
        underMode = true
        block()
        underMode = false
    }
}
