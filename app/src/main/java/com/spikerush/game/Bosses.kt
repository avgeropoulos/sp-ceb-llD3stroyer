package com.spikerush.game

import android.graphics.Color

enum class HairStyle { TUFT, MOHAWK, RAINBOW, BOW, SHADES, WILD }

/** Everything that changes in how a boss is drawn (and the arena tint for its Wonder form). */
class BossLook(
    val skin: Int,
    val belly: Int,
    val shell: Int,
    val hair: Int,
    val hairStyle: HairStyle,
    val bib: Int?,
    val pod: Int,
    val rim: Int,
    val mouth: Int,
    val iris: Int? = null,
    val aura: Int? = null,
    val sky: Int = Color.rgb(170, 60, 40),
    val moon: Int = Color.argb(230, 255, 230, 180),
)

enum class Signature { NONE, DOUBLE_LOB, BOUNCERS, RINGS, QUAKE, SONATA }

/**
 * The boss roster: the bratty prince plus five siblings, each with a signature attack.
 * Every boss gets one normal round and then one "Wonder" round, where it changes color and fights harder.
 */
enum class BossKind(val title: String, val signature: Signature, val normal: BossLook, val wonder: BossLook) {
    PRINCE(
        "THE BRAT PRINCE", Signature.NONE,
        BossLook(
            Color.rgb(250, 205, 80), Color.rgb(255, 240, 180), Color.rgb(40, 150, 60), Color.rgb(240, 90, 30),
            HairStyle.TUFT, Color.WHITE, Color.rgb(235, 235, 245), Color.rgb(160, 160, 175), Color.rgb(220, 40, 50),
        ),
        BossLook(
            Color.rgb(160, 95, 215), Color.rgb(215, 180, 245), Color.rgb(55, 30, 80), Color.rgb(120, 40, 160),
            HairStyle.TUFT, Color.rgb(40, 190, 90), Color.rgb(165, 110, 215), Color.rgb(95, 55, 140),
            Color.rgb(40, 190, 90), iris = Color.rgb(60, 220, 90), aura = Color.argb(60, 170, 80, 255),
            sky = Color.rgb(95, 25, 110), moon = Color.argb(230, 140, 255, 160),
        ),
    ),
    LARKIN(
        "LARKIN", Signature.DOUBLE_LOB,
        BossLook(
            Color.rgb(200, 220, 90), Color.rgb(250, 245, 190), Color.rgb(80, 190, 235), Color.rgb(40, 110, 230),
            HairStyle.MOHAWK, null, Color.rgb(225, 240, 250), Color.rgb(90, 160, 210), Color.rgb(40, 110, 230),
        ),
        BossLook( // Storm Larkin
            Color.rgb(50, 110, 120), Color.rgb(150, 220, 220), Color.rgb(255, 225, 40), Color.rgb(245, 250, 255),
            HairStyle.MOHAWK, null, Color.rgb(70, 90, 120), Color.rgb(255, 225, 40), Color.rgb(255, 225, 40),
            iris = Color.rgb(255, 240, 80), aura = Color.argb(70, 80, 220, 255),
            sky = Color.rgb(20, 80, 120), moon = Color.argb(230, 255, 245, 120),
        ),
    ),
    LEMMO(
        "LEMMO", Signature.BOUNCERS,
        BossLook(
            Color.rgb(250, 215, 90), Color.rgb(255, 245, 200), Color.rgb(250, 170, 30), Color.rgb(255, 80, 80),
            HairStyle.RAINBOW, null, Color.rgb(255, 245, 210), Color.rgb(240, 120, 160), Color.rgb(240, 120, 40),
        ),
        BossLook( // Neon Lemmo
            Color.rgb(40, 30, 60), Color.rgb(90, 80, 120), Color.rgb(60, 255, 140), Color.rgb(255, 60, 220),
            HairStyle.RAINBOW, null, Color.rgb(50, 40, 70), Color.rgb(60, 255, 140), Color.rgb(255, 60, 220),
            iris = Color.rgb(60, 255, 140), aura = Color.argb(70, 255, 60, 220),
            sky = Color.rgb(110, 20, 90), moon = Color.argb(230, 120, 255, 200),
        ),
    ),
    WANDA(
        "WANDA", Signature.RINGS,
        BossLook(
            Color.rgb(250, 200, 110), Color.rgb(255, 240, 210), Color.rgb(240, 100, 170), Color.rgb(250, 90, 160),
            HairStyle.BOW, null, Color.rgb(255, 220, 240), Color.rgb(230, 120, 180), Color.rgb(250, 90, 160),
        ),
        BossLook( // Frost Queen Wanda
            Color.rgb(200, 210, 245), Color.rgb(240, 245, 255), Color.rgb(140, 220, 255), Color.rgb(90, 170, 255),
            HairStyle.BOW, null, Color.rgb(220, 240, 255), Color.rgb(120, 180, 240), Color.rgb(90, 140, 230),
            iris = Color.rgb(90, 200, 255), aura = Color.argb(80, 220, 245, 255),
            sky = Color.rgb(50, 90, 160), moon = Color.argb(230, 220, 250, 255),
        ),
    ),
    ROYCE(
        "ROYCE", Signature.QUAKE,
        BossLook(
            Color.rgb(190, 205, 90), Color.rgb(245, 240, 190), Color.rgb(220, 80, 170), Color.rgb(250, 120, 200),
            HairStyle.SHADES, null, Color.rgb(170, 170, 185), Color.rgb(90, 90, 105), Color.rgb(200, 60, 150),
        ),
        BossLook( // Molten Royce
            Color.rgb(220, 70, 30), Color.rgb(255, 190, 90), Color.rgb(35, 25, 25), Color.rgb(255, 200, 40),
            HairStyle.SHADES, null, Color.rgb(60, 40, 35), Color.rgb(255, 120, 20), Color.rgb(255, 150, 20),
            aura = Color.argb(80, 255, 110, 20),
            sky = Color.rgb(150, 40, 10), moon = Color.argb(230, 255, 160, 60),
        ),
    ),
    LUDO(
        "LUDO", Signature.SONATA,
        BossLook(
            Color.rgb(190, 215, 100), Color.rgb(245, 245, 200), Color.rgb(40, 70, 170), Color.rgb(120, 190, 255),
            HairStyle.WILD, null, Color.rgb(230, 230, 250), Color.rgb(60, 80, 160), Color.rgb(40, 70, 170),
        ),
        BossLook( // Phantom Ludo
            Color.rgb(140, 150, 185), Color.rgb(210, 215, 235), Color.rgb(60, 20, 90), Color.rgb(245, 245, 255),
            HairStyle.WILD, null, Color.rgb(70, 50, 100), Color.rgb(180, 120, 255), Color.rgb(180, 120, 255),
            iris = Color.rgb(200, 140, 255), aura = Color.argb(80, 160, 100, 255),
            sky = Color.rgb(60, 30, 110), moon = Color.argb(230, 220, 190, 255),
        ),
    );

    companion object {
        private val ORDER = listOf(LARKIN, LEMMO, WANDA, ROYCE, LUDO, PRINCE)

        /** Rounds 1-3: the prince (3 = his Wonder form). Then each sibling gets a normal round + a Wonder round. */
        fun forLevel(level: Int): Pair<BossKind, Boolean> {
            if (level <= 3) return PRINCE to (level == 3)
            val k = level - 4
            return ORDER[(k / 2) % ORDER.size] to (k % 2 == 1)
        }
    }
}
