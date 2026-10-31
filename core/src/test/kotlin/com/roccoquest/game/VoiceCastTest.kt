package com.roccoquest.game

import com.roccoquest.game.VoiceCast.Gender
import com.roccoquest.game.VoiceCast.Info
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VoiceCastTest {
    /** Voices like a phone with Google's speech engine has: each person both on the phone and online. */
    private fun googlePhone(): List<Info> {
        val out = ArrayList<Info>()
        fun add(lang: String, country: String, vararg codes: String, notInstalled: Boolean = false) {
            for (c in codes) for (net in listOf(false, true)) {
                out += Info(
                    "$lang-${country.lowercase()}-x-$c-${if (net) "network" else "local"}", lang, country,
                    VoiceCast.QUALITY_HIGH, needsNetwork = net, notInstalled = notInstalled && !net,
                    isDefault = c == "sfg",
                )
            }
        }
        add("en", "US", "iob", "iog", "iol", "iom", "sfg", "tpc", "tpd", "tpf")
        add("en", "GB", "gba", "gbb", "gbc", "gbd", "gbg", "rjs", "fis")
        add("en", "AU", "aua", "aub", "auc", "aud")
        add("en", "IN", "ena", "end", notInstalled = true)
        add("it", "IT", "itb", "itc", "itd", "kda")
        add("el", "GR", "vfz")
        // An old robotic engine's voice, too.
        out += Info("espeak-en-us", "en", "US", VoiceCast.QUALITY_VERY_HIGH)
        return out
    }

    @Test fun everyCharacterGetsTheirOwnHumanVoice() {
        for (online in listOf(false, true)) {
            val cast = VoiceCast.cast(googlePhone(), online)
            assertEquals(Voice.entries.toSet(), cast.keys)
            val names = cast.values.map { assertNotNull(it.voice) }
            // Six different people (the online and offline copies of one person count as one).
            assertEquals(names.size, names.map(VoiceCast::speaker).toSet().size, "$cast")
            assertTrue(names.none { "espeak" in it }, "no robots: $names")
            // Online, the internet voices are used; offline, never.
            assertTrue(names.all { it.endsWith(if (online) "-network" else "-local") }, "$names")
            // Rocco is an Italian man, the bosses a man, the Pokemon a woman's bright voice.
            val hero = cast.getValue(Voice.HERO)
            assertEquals("it", hero.language)
            assertEquals(Gender.MALE, VoiceCast.gender(hero.voice!!))
            assertEquals(Gender.MALE, VoiceCast.gender(cast.getValue(Voice.GOD).voice!!))
            assertEquals(Gender.FEMALE, VoiceCast.gender(cast.getValue(Voice.MON).voice!!))
            // Different accents too, so they're easy to tell apart.
            assertTrue(cast.values.filter { it.language == "en" }.map { it.country }.toSet().size >= 3)
        }
    }

    @Test fun pitchAndSpeedOnlyGetSmallNudges() {
        val all = VoiceCast.cast(googlePhone(), false).values + VoiceCast.cast(emptyList(), false).values +
            Voice.entries.map(VoiceCast::plain) + listOfNotNull(VoiceCast.greek(googlePhone(), false))
        for (p in all) {
            assertTrue(p.pitch in VoiceCast.MIN_TWEAK..VoiceCast.MAX_TWEAK, "pitch $p")
            assertTrue(p.rate in VoiceCast.MIN_TWEAK..VoiceCast.MAX_TWEAK, "speed $p")
        }
    }

    @Test fun theSameVoicesEveryTime() {
        val first = VoiceCast.cast(googlePhone(), true)
        repeat(5) { i -> assertEquals(first, VoiceCast.cast(googlePhone().shuffled(Random(i)), true)) }
    }

    @Test fun picksTheMostLifelikeVoice() {
        val voices = listOf(
            Info("basic", "en", "US", VoiceCast.QUALITY_NORMAL),
            Info("nice", "en", "US", VoiceCast.QUALITY_HIGH),
            Info("best-but-not-downloaded", "en", "US", VoiceCast.QUALITY_VERY_HIGH, notInstalled = true),
            Info("online-only", "en", "US", VoiceCast.QUALITY_HIGH, needsNetwork = true),
            Info("pico-en", "en", "US", VoiceCast.QUALITY_VERY_HIGH),
        )
        assertEquals("nice", VoiceCast.mostNatural(voices, online = false)?.name)
        assertEquals("online-only", VoiceCast.mostNatural(voices, online = true)?.name)
        assertNull(VoiceCast.naturalness(voices[2], online = true), "not downloaded can't speak")
        assertNull(VoiceCast.naturalness(voices[3], online = false), "offline can't use internet voices")
        assertEquals("Neural one", VoiceCast.mostNatural(voices + Info("Neural one", "eng", "USA", VoiceCast.QUALITY_HIGH), false)?.name)
        assertEquals("US", VoiceCast.country("USA"))
    }

    @Test fun oneVoiceIsSharedByEveryone() {
        val cast = VoiceCast.cast(listOf(Info("only-voice", "en", "US", VoiceCast.QUALITY_NORMAL)), online = false)
        assertTrue(cast.values.all { it.voice == "only-voice" }, "$cast")
        // ...but they still sound a bit different from each other.
        assertTrue(cast.values.map { it.pitch to it.rate }.toSet().size >= 4)
    }

    @Test fun roccoSpeaksEnglishWithoutAnItalianVoice() {
        val voices = googlePhone().filter { it.language != "it" }
        val hero = VoiceCast.cast(voices, false).getValue(Voice.HERO)
        assertEquals("en", hero.language)
        assertEquals(Gender.MALE, VoiceCast.gender(hero.voice!!))
        // A phone that lists no voices at all is still asked for Italian by language.
        val unknown = VoiceCast.cast(emptyList(), false)
        assertEquals(VoiceCast.Persona(null, "it", "IT", 1.0f, 0.96f), unknown.getValue(Voice.HERO))
        assertTrue(unknown.values.all { it.voice == null })
    }

    @Test fun poseidonsGreekVoice() {
        assertEquals("el-gr-x-vfz-local", VoiceCast.greek(googlePhone(), false)?.voice)
        assertEquals("el-gr-x-vfz-network", VoiceCast.greek(googlePhone(), true)?.voice)
        assertNull(VoiceCast.greek(googlePhone().filter { it.language != "el" }, false))
        assertTrue(SpeechText.isGreek(TEAM_POSEIDON))
        assertTrue(!SpeechText.isGreek("I am Poseidon!"))
    }

    @Test fun tellsMenAndWomenApart() {
        assertEquals(Gender.MALE, VoiceCast.gender("en-us-x-iol-network"))
        assertEquals(Gender.FEMALE, VoiceCast.gender("en-us-x-sfg-local"))
        assertEquals(Gender.FEMALE, VoiceCast.gender("en-us-x-sfg#female_2-local"))
        assertEquals(Gender.MALE, VoiceCast.gender("en-us-x-sfg#male_1-local"))
        assertEquals(Gender.MALE, VoiceCast.gender("it-it-x-itc-local"))
        assertEquals(Gender.ANY, VoiceCast.gender("en-US-language"))
        assertEquals("en-us-x-iol", VoiceCast.speaker("en-us-x-iol-network"))
        assertEquals(VoiceCast.speaker("en-us-x-iol-local"), VoiceCast.speaker("en-us-x-iol-network"))
    }

    @Test fun linesAreTidiedIntoNaturalSentences() {
        assertEquals("My hair! It's on fire!", SpeechText.tidy("My HAIR! It's on FIRE!"))
        assertEquals("Waaah!", SpeechText.tidy("WAAAAAH!"))
        assertEquals("Sparky! Thunderbolt!", SpeechText.tidy("SPARKY! thunderbolt!"))
        assertEquals("Sparky.", SpeechText.tidy("SPARKY"))
        assertEquals("Treasure! You got 3 TNT!", SpeechText.tidy("Treasure! You got 3 TNT!"))
        assertEquals("Hee hee hee! I'm Witch Wanda!", SpeechText.tidy("Hee hee hee! I'M Witch Wanda!"))
        assertEquals("Hey! I'm taking a bath in here!", SpeechText.tidy("HEY! I'm taking a BATH in here!"))
        // Spotty shouts in capitals: said as words, not spelled out.
        assertEquals("Oh rocco, you're crazy!!!", SpeechText.tidy("OH ROCCO, YOU'RE CRAZY!!!"))
        assertEquals("Whoa! I can see the moon.", SpeechText.tidy("Whoa! I can see the MOON"))
        assertEquals("Ah... Aaah...", SpeechText.tidy("Ah... AAAAAH..."))
        assertEquals("Ah-chooo!", SpeechText.tidy("AH-CHOOOOOO!"))
        assertEquals("Let's-a go!", SpeechText.tidy("  Let's-a   go!"))
        assertEquals(TEAM_POSEIDON, SpeechText.tidy(TEAM_POSEIDON))
        assertEquals("", SpeechText.tidy("   "))
        assertTrue(SpeechText.seconds("Hello, friend! Do you want to trade?", 1f) in 1.5..4.0)
        assertTrue(SpeechText.seconds("Hi!", 1.2f) < SpeechText.seconds("Hi!", 0.9f))
    }

    @Test fun linesWaitTheirTurnInsteadOfBeingCutOff() {
        val q = SpeechQueue()
        // Nothing playing: just say it.
        assertEquals(SpeechQueue.Mode.ADD, q.plan("Let's-a go!", 0.0))
        q.queued("1", "Let's-a go!", 1.0, 0.0, SpeechQueue.Mode.ADD)
        q.started("1", 0.1)
        // Something new while Rocco talks: it waits for him to finish.
        assertEquals(SpeechQueue.Mode.ADD, q.plan("Sparky!", 0.3))
        q.queued("2", "Sparky!", 0.6, 0.3, SpeechQueue.Mode.ADD)
        // The same line again while it's still coming: once is enough.
        assertEquals(SpeechQueue.Mode.SKIP, q.plan("Sparky!", 0.4))
        // A third line while one is still waiting: lines are piling up, so the newest goes now.
        assertEquals(SpeechQueue.Mode.FLUSH, q.plan("Mamma mia!", 0.5))
        q.queued("3", "Mamma mia!", 0.8, 0.5, SpeechQueue.Mode.FLUSH)
        assertEquals(1, q.count(0.5))
        // The phone says the old ones were stopped: that doesn't disturb the new line.
        q.finished("1", 0.5)
        q.finished("2", 0.5)
        assertEquals(1, q.count(0.6))
        q.finished("3", 1.3)
        assertEquals(0, q.count(1.3))
        assertEquals(SpeechQueue.Mode.ADD, q.plan("Mamma mia!", 1.4))
    }

    @Test fun aLongWaitIsSkipped() {
        val q = SpeechQueue()
        q.queued("1", "Welcome to our village! Take a look at my trades.", 4.0, 0.0, SpeechQueue.Mode.ADD)
        // The villager has a while left to talk: Rocco's "ouch" would come far too late, so it goes now.
        assertEquals(SpeechQueue.Mode.FLUSH, q.plan("Oh no!", 0.5))
        // Near the end of his line, it's fine to wait.
        assertEquals(SpeechQueue.Mode.ADD, q.plan("Oh no!", 2.5))
    }

    @Test fun aPhoneThatNeverSaysWhenItsDoneStillWorks() {
        val q = SpeechQueue()
        q.queued("1", "Hello!", 0.7, 0.0, SpeechQueue.Mode.ADD)
        q.queued("2", "Goodbye!", 0.8, 0.1, SpeechQueue.Mode.ADD)
        assertEquals(2, q.count(1.0))
        // Long after both should have finished, they're forgotten.
        assertEquals(0, q.count(10.0))
        assertNotEquals(SpeechQueue.Mode.SKIP, q.plan("Hello!", 10.0))
    }

    private companion object {
        const val TEAM_POSEIDON = "Είμαι ο Ποσειδώνας! Είμαι ο πιο δυνατός της θάλασσας!"
    }
}
