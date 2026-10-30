package com.roccoquest.team

import com.roccoquest.team.Pokedex.Body
import com.roccoquest.team.Pokedex.Ears
import com.roccoquest.team.Pokedex.Spec
import com.roccoquest.team.Pokedex.Tail
import com.roccoquest.team.Pokedex.X

/** The 40 newest Pokemon, built from the same parts as [Pokedex] (plus some new ones: bugs, rock giants, wings...). */
internal val MORE_SPECS: Map<Mon, Spec> = mapOf(
    // Fire bears
    Mon.CINDERCUB to Spec(0xFFC8502A.toInt(), 0xFFF4E0B0.toInt(), 0xFFFF8A2A.toInt(), Body.QUAD, Ears.ROUND, Tail.NONE, setOf(X.MUZZLE, X.FLAMEHAT), Mon.EMBER),
    Mon.CINDERBEAR to Spec(0xFFB8402A.toInt(), 0xFFF4D8A0.toInt(), 0xFFFF8A2A.toInt(), Body.BIPED, Ears.ROUND, Tail.NONE, setOf(X.MUZZLE, X.BELLY, X.CLAWS, X.BACKFIRE), Mon.EMBER),
    Mon.VOLCABEAR to Spec(0xFF6E3428.toInt(), 0xFFFF8A3A.toInt(), 0xFF3A2A2A.toInt(), Body.GOLEM, Ears.ROUND, Tail.NONE, setOf(X.MUZZLE, X.ROCKS, X.BACKFIRE, X.CLAWS), Mon.EMBER),
    // Water frogs
    Mon.TADBLUB to Spec(0xFF3A8AD8.toInt(), 0xFFF4F4F4.toInt(), 0xFF8AC8F8.toInt(), Body.BLOB, Ears.NONE, Tail.TADPOLE, setOf(X.SWIRL), Mon.SPLASHY),
    Mon.FROGGLE to Spec(0xFF3AA8B8.toInt(), 0xFFF4E89A.toInt(), 0xFF1E6A88.toInt(), Body.BIPED, Ears.NONE, Tail.NONE, setOf(X.FROGEYES, X.SPOTS, X.BELLY), Mon.SPLASHY),
    Mon.TORRENTOAD to Spec(0xFF2A6AA8.toInt(), 0xFFF4E89A.toInt(), 0xFF5AD8E8.toInt(), Body.GOLEM, Ears.NONE, Tail.NONE, setOf(X.FROGEYES, X.SPOTS, X.CROWN), Mon.HYDRO),
    // Dragons
    Mon.DRAKLET to Spec(0xFF8A5AD8.toInt(), 0xFFF4E8C8.toInt(), 0xFFFFD24A.toInt(), Body.DINO, Ears.NONE, Tail.SPADE, setOf(X.HORNS), Mon.BLAZE),
    Mon.DRAKOON to Spec(0xFF7A4AC8.toInt(), 0xFFF4E8C8.toInt(), 0xFFFFC83A.toInt(), Body.DINO, Ears.NONE, Tail.SPADE, setOf(X.HORNS, X.SAIL, X.CLAWS), Mon.BLAZE),
    Mon.DRAKONIX to Spec(0xFF6A3AB8.toInt(), 0xFFF4E0B8.toInt(), 0xFFFF6A4A.toInt(), Body.DINO, Ears.NONE, Tail.SPADE, setOf(X.HORNS, X.SAIL, X.BATWINGS, X.CLAWS), Mon.BLAZE),
    // Forest squirrel and deer
    Mon.ACORNY to Spec(0xFFA8783A.toInt(), 0xFFF4E4C0.toInt(), 0xFF6AB84A.toInt(), Body.QUAD, Ears.ROUND, Tail.FLUFFY, setOf(X.ACORN), Mon.BLOOM),
    Mon.OAKHORN to Spec(0xFF8A6A3A.toInt(), 0xFFF4E4C0.toInt(), 0xFF4AB84A.toInt(), Body.QUAD, Ears.POINTY, Tail.CURL, setOf(X.ANTLERS, X.LEAFBACK, X.SPOTS), Mon.BLOOM),
    // Electric bugs
    Mon.VOLTBUG to Spec(0xFF3A9AE8.toInt(), 0xFF2A3A6A.toInt(), 0xFFFFE04A.toInt(), Body.BUG, Ears.NONE, Tail.NONE, setOf(X.ANTENNAE, X.SPOTS), Mon.SPARKY),
    Mon.ZAPWING to Spec(0xFF2A7AD8.toInt(), 0xFF2A3A6A.toInt(), 0xFFFFE04A.toInt(), Body.BUG, Ears.NONE, Tail.STINGER, setOf(X.ANTENNAE, X.SPOTS, X.BUGWINGS, X.NOSEHORN), Mon.SPARKY),
    // Ice cats
    Mon.FROSTKIT to Spec(0xFFB8E0F4.toInt(), 0xFFF8FCFF.toInt(), 0xFF4AA8E8.toInt(), Body.QUAD, Ears.CAT, Tail.FLUFFY, setOf(X.SNOWFLAKE), Mon.HYDRO),
    Mon.BLIZZACAT to Spec(0xFF8AC8F0.toInt(), 0xFFF8FCFF.toInt(), 0xFF2A7AC8.toInt(), Body.QUAD, Ears.BIG, Tail.LONG, setOf(X.ICICLES, X.MANE, X.SNOWFLAKE), Mon.HYDRO),
    // Rocks
    Mon.PEBBLO to Spec(0xFF9A8A7A.toInt(), 0xFF9A8A7A.toInt(), 0xFF6A5A4A.toInt(), Body.BLOB, Ears.NONE, Tail.NONE, setOf(X.ROCKS, X.BROW), Mon.PUNCHO),
    Mon.BOULDERON to Spec(0xFF8A7A6A.toInt(), 0xFFA89888.toInt(), 0xFF5AD8C8.toInt(), Body.GOLEM, Ears.NONE, Tail.NONE, setOf(X.ROCKS, X.BROW, X.CRYSTALS), Mon.PUNCHO),
    // Ghost lights
    Mon.WISPY to Spec(0xFFB8A8F0.toInt(), 0xFFB8A8F0.toInt(), 0xFF5AD8F8.toInt(), Body.BLOB, Ears.NONE, Tail.NONE, setOf(X.GHOST, X.WISPFLAME), Mon.SPOOKY),
    Mon.LANTERNO to Spec(0xFF4A3A6A.toInt(), 0xFF8AF0F8.toInt(), 0xFF2A2A3A.toInt(), Body.BLOB, Ears.NONE, Tail.NONE, setOf(X.GHOST, X.LANTERN), Mon.SPOOKY),
    // Caterpillar and butterfly
    Mon.CATERPUFF to Spec(0xFF9AD84A.toInt(), 0xFFF4F0A0.toInt(), 0xFFFF8A3A.toInt(), Body.SERPENT, Ears.NONE, Tail.NONE, setOf(X.ANTENNAE, X.SPOTS), Mon.BLOOM),
    Mon.FLUTTERBY to Spec(0xFF5A4A8A.toInt(), 0xFF8AE0F8.toInt(), 0xFFFF7AC0.toInt(), Body.BLOB, Ears.NONE, Tail.NONE, setOf(X.ANTENNAE, X.BUTTERFLY), Mon.MYSTIC),
    // Stars
    Mon.TWINKLE to Spec(0xFFFFE0F0.toInt(), 0xFFFFE0F0.toInt(), 0xFFFFC83A.toInt(), Body.BLOB, Ears.NONE, Tail.NONE, setOf(X.STAR, X.CHEEKS), Mon.MYSTIC),
    Mon.STARFAE to Spec(0xFFFFC8E8.toInt(), 0xFFFFF0F8.toInt(), 0xFFFFC83A.toInt(), Body.BIPED, Ears.NONE, Tail.NONE, setOf(X.STAR, X.BUGWINGS, X.CHEEKS), Mon.MYSTIC),
    // Steel
    Mon.COGGY to Spec(0xFFA8B0C0.toInt(), 0xFFA8B0C0.toInt(), 0xFFE8A03A.toInt(), Body.BLOB, Ears.NONE, Tail.NONE, setOf(X.GEAR, X.BOLTS), Mon.PUNCHO),
    Mon.GEARGANT to Spec(0xFF8A94A8.toInt(), 0xFFC8D0DC.toInt(), 0xFFE8A03A.toInt(), Body.GOLEM, Ears.NONE, Tail.NONE, setOf(X.GEAR, X.BOLTS, X.VISOR), Mon.PUNCHO),
    // Sky birds
    Mon.GUSTLING to Spec(0xFF8AC8F0.toInt(), 0xFFF4F8FF.toInt(), 0xFF3A6AC8.toInt(), Body.BIRD, Ears.CREST, Tail.NONE, setOf(X.BEAKYELLOW), Mon.LUGIA),
    Mon.GALEHAWK to Spec(0xFF3A6AC8.toInt(), 0xFFF4F8FF.toInt(), 0xFFF4F4F4.toInt(), Body.BIRD, Ears.CREST, Tail.NONE, setOf(X.BEAKYELLOW, X.BIRDWINGS, X.BROW), Mon.LUGIA),
    // Horned dinos
    Mon.TRIKEY to Spec(0xFF6AB89A.toInt(), 0xFFF4E8C8.toInt(), 0xFFF0A040.toInt(), Body.QUAD, Ears.NONE, Tail.THIN, setOf(X.FRILL, X.NOSEHORN), Mon.PUNCHO),
    Mon.TRIKERATOR to Spec(0xFF4A9A7A.toInt(), 0xFFF4E8C8.toInt(), 0xFFE8803A.toInt(), Body.QUAD, Ears.NONE, Tail.THIN, setOf(X.FRILL, X.NOSEHORN, X.HORNS, X.SPOTS), Mon.PUNCHO),
    // Singles
    Mon.SPARKLEEL to Spec(0xFF2A3A6A.toInt(), 0xFF4A6AA8.toInt(), 0xFFFFE04A.toInt(), Body.SERPENT, Ears.FIN, Tail.BOLT, setOf(X.SPOTS), Mon.SPARKY),
    Mon.MAGMOLE to Spec(0xFF5A3A2A.toInt(), 0xFF8A5A3A.toInt(), 0xFFFF7A2A.toInt(), Body.QUAD, Ears.NONE, Tail.NONE, setOf(X.SNOUT, X.CLAWS, X.BACKFIRE), Mon.EMBER),
    Mon.CHILLPUP to Spec(0xFFC8E8F8.toInt(), 0xFFF8FCFF.toInt(), 0xFFE83A3A.toInt(), Body.QUAD, Ears.FLOP, Tail.FLUFFY, setOf(X.SCARF, X.SNOWFLAKE), Mon.HYDRO),
    Mon.CORALLO to Spec(0xFFFF8A9A.toInt(), 0xFFFFE0E4.toInt(), 0xFFFF5A3A.toInt(), Body.BLOB, Ears.NONE, Tail.NONE, setOf(X.CORAL, X.BELLY), Mon.SPLASHY),
    Mon.BEETLEBOP to Spec(0xFF5A6A8A.toInt(), 0xFF2A2A3A.toInt(), 0xFFC8D0DC.toInt(), Body.BUG, Ears.NONE, Tail.NONE, setOf(X.NOSEHORN, X.BIGHORN), Mon.PUNCHO),
    Mon.MUSHBOO to Spec(0xFFF0E0C0.toInt(), 0xFFF0E0C0.toInt(), 0xFFE83A3A.toInt(), Body.BIPED, Ears.NONE, Tail.NONE, setOf(X.MUSHCAP), Mon.BLOOM),
    Mon.HONEYBUN to Spec(0xFFF4C83A.toInt(), 0xFFF4C83A.toInt(), 0xFF2A2A30.toInt(), Body.BLOB, Ears.NONE, Tail.STINGER, setOf(X.BEESTRIPES, X.BUGWINGS, X.ANTENNAE), Mon.MYSTIC),
    Mon.SNAILO to Spec(0xFF8AC8E8.toInt(), 0xFFC8E8F8.toInt(), 0xFFE8A04A.toInt(), Body.SLUG, Ears.NONE, Tail.NONE, setOf(X.SPIRAL, X.ANTENNAE), Mon.SPLASHY),
    Mon.BATTY to Spec(0xFF4A3A7A.toInt(), 0xFF8A6AB8.toInt(), 0xFF3A2A5A.toInt(), Body.BLOB, Ears.POINTY, Tail.NONE, setOf(X.BATWINGS, X.TEETH, X.BELLY), Mon.SPOOKY),
    // Legends
    Mon.STORMWING to Spec(0xFFF4D84A.toInt(), 0xFFF4E88A.toInt(), 0xFF2A2A30.toInt(), Body.BIRD, Ears.CREST, Tail.BOLT, setOf(X.BEAKRED, X.BIRDWINGS, X.CROWN, X.BROW), Mon.SPARKY),
    Mon.SOLARION to Spec(0xFFF4C86A.toInt(), 0xFFF4ECD0.toInt(), 0xFFFF6A2A.toInt(), Body.QUAD, Ears.CAT, Tail.FLAME, setOf(X.FIREMANE, X.CROWN, X.MUZZLE), Mon.BLAZE),
)
