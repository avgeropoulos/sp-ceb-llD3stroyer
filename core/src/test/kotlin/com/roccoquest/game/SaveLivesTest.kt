package com.roccoquest.game

import kotlin.test.Test
import kotlin.test.assertEquals

class SaveLivesTest {
    @Test fun savedLivesAreRestoredWithoutGivingExtraLives() {
        for (remainingLives in 1..3) {
            val storage = Storage.Memory()
            storage.save("slot1_level", 1)
            storage.save("slot1_score", 1200)
            storage.save("slot1_lives", remainingLives)

            val game = Game(Input(), storage = storage)
            game.viewW = 427f
            repeat(30) { game.update(1f / 60f) }
            game.input.tapX = -1f // keyboard-style selection always chooses the first save slot
            game.input.tap = true
            game.update(1f / 60f)

            assertEquals(remainingLives, game.lives, "Reloading should preserve $remainingLives lives")
            assertEquals(1200, game.score)
        }
    }
}
