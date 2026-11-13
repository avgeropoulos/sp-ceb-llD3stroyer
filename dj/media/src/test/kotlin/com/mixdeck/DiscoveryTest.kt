package com.mixdeck

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Offline tests of Discovery's song-identity and junk helpers (no network). */
class DiscoveryTest {
    private fun yt(title: String, channel: String, sec: Long = 200) = YtResult("https://www.youtube.com/watch?v=xxxxxxxxxxx", "t" + title.hashCode(), title, channel, sec)

    @Test fun stripsDecorations() {
        assertEquals("Nelly - Grillz", Discovery.stripDecorations("Nelly - Grillz ft. Paul Wall, Ali & Gipp"))
        assertEquals("Daft Punk - One More Time", Discovery.stripDecorations("Daft Punk - One More Time (Official Video)"))
        assertEquals("Nelly - Air Force Ones", Discovery.stripDecorations("Nelly - Air Force Ones (Remastered) [Audio HQ]"))
        assertEquals("Γιώργος Νταλάρας - Ένα το χελιδόνι", Discovery.stripDecorations("Γιώργος Νταλάρας - Ένα το χελιδόνι - Official Audio Release"))
        assertEquals("Guru Josh Project - Infinity (Klaas Vocal Mix)", Discovery.stripDecorations("Guru Josh Project - Infinity (Klaas Vocal Mix) [Ultra Records]"))
        assertEquals("Jay-Z, Beyoncé - 03' Bonnie & Clyde", Discovery.stripDecorations("Jay-Z, Beyoncé - 03' Bonnie & Clyde / lyrics"))
        assertEquals("Sweet California - Hey Mickey", Discovery.stripDecorations("Sweet California - Hey Mickey (Videoclip Oficial)"))
        assertEquals("Πονώ δεν με λυπάσαι", Discovery.stripDecorations("Πονώ δεν με λυπάσαι.avi"))
        assertEquals("Ciara - Goodies", Discovery.stripDecorations("Ciara - Goodies (Lyrics) ft. Petey Pablo"))
    }

    @Test fun parsesArtistAndSong() {
        assertEquals(Discovery.SongId("Nelly", "Air Force Ones"), Discovery.songOf(yt("Nelly - Air Force Ones ft. Kyjuan, Ali, Murphy Lee (Official Music Video)", "NellyVEVO")))
        assertEquals(Discovery.SongId("Daft Punk", "One More Time"), Discovery.songOf(yt("One More Time", "Daft Punk")))
        assertEquals(Discovery.SongId("Στέλιος Καζαντζίδης", "Συννεφιασμένη Κυριακή").let { Discovery.key(it.artist) to Discovery.key(it.title) },
            Discovery.songOf(yt("ΣΥΝΝΕΦΙΑΣΜΕΝΗ ΚΥΡΙΑΚΗ-ΣΤΕΛΙΟΣ ΚΑΖΑΝΤΖΙΔΗΣ", "Stelios Kazantzidis")).let { Discovery.key(it.artist) to Discovery.key(it.title) })
        assertEquals(Discovery.SongId("KATSEYE (캣츠아이)", "Animal"), Discovery.songOf(yt("KATSEYE (캣츠아이) \"Animal\" Official MV", "HYBE LABELS")))
        assertEquals(Discovery.SongId("KPop Demon Hunters", "Golden"), Discovery.songOf(yt("“Golden” Official Lyric Video | KPop Demon Hunters | Sony Animation", "Sony Pictures Animation")))
        assertEquals(Discovery.SongId("Γιάννης Πουλόπουλος", "Γειτονάκι Μου"), Discovery.songOf(yt("Γιάννης Πουλόπουλος - Γειτονάκι Μου (Στίχοι-Lyrics) | Greek Lyric Videos", "Greek Lyric Videos")))
        assertEquals(Discovery.SongId("Scatman John", "Scatman (ski-ba-bop-ba-dop-bop)"), Discovery.songOf(yt("Scatman (ski-ba-bop-ba-dop-bop) Official Video HD - Scatman John", "Scatman John Official YouTube Channel")))
    }

    @Test fun comparesSongsAcrossScriptsAndUploads() {
        assertEquals(Discovery.key("Νταλάρας"), Discovery.key("Dalaras"))
        assertEquals(Discovery.key("Συννεφιασμένη Κυριακή"), Discovery.key("Sinnefiasmeni Kiriaki"))
        assertEquals(Discovery.key("Στέλιος Καζαντζίδης"), Discovery.key("Stelios Kazantzidis"))
        assertTrue(Discovery.sameSong(Discovery.SongId("Stelios Kazantzidis", "Synnefiasmeni Kyriaki"), Discovery.SongId("Σωτηρία Μπέλλου", "Συννεφιασμένη κυριακή")))
        assertTrue(Discovery.sameSong(Discovery.SongId("Daft Punk", "One More Time"), Discovery.SongId("Daft Punk", "One More Time (Radio Edit)")))
        assertFalse(Discovery.sameSong(Discovery.SongId("Nelly", "Grillz"), Discovery.SongId("Nelly", "Air Force Ones")))
        assertFalse(Discovery.sameSong(Discovery.SongId("Daft Punk", "Robot Rock"), Discovery.SongId("Daft Punk", "Around the World")))
    }

    @Test fun flagsJunk() {
        assertEquals("live", Discovery.junkReason(yt("Daft Punk - One More Time (Live)", "x")))
        assertEquals("live", Discovery.junkReason(yt("YLL LIMANI - AKOMA TE DU (LIVE FROM ARM STADIUM)", "x")))
        assertEquals("slowed/sped up/8D", Discovery.junkReason(yt("sabrina carpenter - espresso (slowed + reverb)", "x")))
        assertEquals("slowed/sped up/8D", Discovery.junkReason(yt("Usher Yeah! Chopped & Screwed", "x")))
        assertEquals("slowed/sped up/8D", Discovery.junkReason(yt("Nightcore - Shape of You", "x")))
        assertEquals("karaoke/instrumental", Discovery.junkReason(yt("Espresso (Karaoke Version)", "x")))
        assertEquals("cover", Discovery.junkReason(yt("Soda Pop (Huntrix Version) | 【Female Cover by MilkyyMelodies】", "x")))
        assertEquals("too long (mix/compilation)", Discovery.junkReason(yt("2000s Party Mix | Hip-Hop", "x", 1979)))
        assertEquals("too short", Discovery.junkReason(yt("Nelly-Air force ones AMV", "x", 35)))
        assertEquals("compilation", Discovery.junkReason(yt("50 Cent & MOLIY Just A Lil' Bit JAMES AKI MASH UP", "x")))
        assertEquals("mashup", Discovery.junkReason(yt("Starboy x I Feel It Coming", "x")))
        assertEquals("not a song", Discovery.junkReason(yt("RESCENE (리센느) 'APT.' Special Performance", "x")))
        assertNull(Discovery.junkReason(yt("T.I. - Live Your Life ft. Rihanna", "x")))
        assertNull(Discovery.junkReason(yt("Foo Fighters - Best Of You (Official Music Video)", "x")))
        assertNull(Discovery.junkReason(yt("Bruce Springsteen - Cover Me", "x")))
        assertNull(Discovery.junkReason(yt("Dafina Zeqiri x Franklin - ON GOD", "x")))
        assertNull(Discovery.junkReason(yt("Guru Josh Project - Infinity (Klaas Vocal Mix) [Ultra Records]", "x")))
    }
}

class DiscoveryMoreTest {
    private fun yt(title: String, channel: String, sec: Long = 200) = YtResult("https://www.youtube.com/watch?v=xxxxxxxxxxx", "u" + title.hashCode(), title, channel, sec)

    @Test fun soundtrackNotesAndQuotedSongs() {
        assertEquals("Shakira - Zoo", Discovery.stripDecorations("Shakira - Zoo (From \"Zootopia 2\") Official Audio"))
        assertEquals(Discovery.SongId("Shakira", "Zoo"), Discovery.songOf(yt("Shakira 'Zoo (from Zootopia 2)' Color Coded Lyrics | Lyritix", "Lyritix")))
        assertEquals(Discovery.SongId("Shaggy", "It Wasn't Me"), Discovery.songOf(yt("Shaggy - It Wasn't Me with Lyrics!", "adamnashley")))
        assertEquals(Discovery.SongId("Nelly", "Ride Wit Me"), Discovery.songOf(yt("Nelly  -  Ride Wit Me (Lyrics) (Dirty)", "Mr Lyrics")))
        assertEquals(Discovery.SongId("Eminem", "Lose Yourself."), Discovery.songOf(yt("Eminem - Lose Yourself[1080p].", "Shady Music")))
        assertEquals(Discovery.SongId("DMX", "Party Up In Here"), Discovery.songOf(yt("DMX -Party Up In Here Lyrics", "DudeWhatItDo")))
        assertEquals(Discovery.SongId("Jason Derulo", "Talk Dirty"), Discovery.songOf(yt("Jason Derulo feat. 2 Chainz - Talk Dirty | Lyrics", "Dark City Sounds")))
        assertFalse(Discovery.sameSong(Discovery.SongId("Usher", "Yeah!"), Discovery.SongId("50 Cent", "Yeah!")))
        assertTrue(Discovery.sameSong(Discovery.SongId("ROSÉ & Bruno Mars", "APT."), Discovery.SongId("bruno mars, rose", "APT")))
        assertEquals("kids", Discovery.junkReason(yt("Music BANANA! (Minions) | Minion song", "The What-If Button")))
        assertEquals(Discovery.SongId("Petey Pablo", "Freek A Leek"), Discovery.songOf(yt("Petey Pablo - Freek A Leek w lyrics", "56soccer41")))
        assertEquals(Discovery.SongId("Murphy Lee vs Jay E", "I'm Back"), Discovery.songOf(yt("\"I'm Back\" Murphy Lee vs Jay E ft Yak Boy Fresh", "Murphy Lee vs. Jay E")))
        assertFalse(Discovery.sameSong(Discovery.SongId("Alex Warren", "Headlights"), Discovery.SongId("Alok & Alan Walker", "Headlights")))
        assertTrue(Discovery.sameSong(Discovery.SongId("Ed Sheeran", "Shape of You"), Discovery.SongId("Some Singer", "Shape of You")))
        assertEquals("cover", Discovery.junkReason(yt("“Golden” From the KPop Demon Hunters Movie Fun Squad Music Video Cover | Fun Squad", "x")))
        assertEquals("Μάνα που ζω - Δημήτρης Μητροπάνος", Discovery.stripDecorations("Μάνα που ζω-Δημήτρης Μητροπάνος").let { t -> t.replace("-", " - ").replace("  ", " ") })
    }
}
