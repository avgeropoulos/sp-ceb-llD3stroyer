package com.critters.game

import kotlin.test.Test
import kotlin.test.assertEquals

class ArtTest {
    @Test fun tilesAreAllSixteenSquare() {
        for (ch in "., #=s~*FSLRCMGHWwDnvyfXxTBPbYEZgQkKrAOli") {
            val t = Art.tile(ch, 0)
            assertEquals(16, t.w, "tile '$ch'")
            assertEquals(16, t.h, "tile '$ch'")
        }
        for (look in Art.Look.entries.filter { it.cap != 0L }) Art.person(look)
    }

    @Test fun critterSheet() {
        for (sp in Dex.all) {
            CritterArt.front(sp.id); CritterArt.back(sp.id); CritterArt.icon(sp.id)
        }
        Shots.critterSheet()
    }
}
