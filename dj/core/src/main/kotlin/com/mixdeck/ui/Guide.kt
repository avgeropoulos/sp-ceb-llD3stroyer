package com.mixdeck.ui

/** The help guide's content, shared by the phone and desktop apps. */
object Guide {
    val blue = 0xFF29B6F6.toInt()
    val orange = 0xFFFF7043.toInt()
    val green = 0xFF00E676.toInt()
    val amber = 0xFFFFA000.toInt()
    val loopGreen = 0xFF32CD32.toInt()
    val purple = 0xFFB388FF.toInt()
    val pink = 0xFFFF4081.toInt()
    val red = 0xFFFF1744.toInt()
    val grey = 0xFF8A8A96.toInt()
    val yellow = 0xFFFFEB3B.toInt()

    class Item(val chip: String, val color: Int, val text: String)
    class Section(val title: String, val color: Int, val items: List<Item>)

    val sections = listOf(
        Section("Getting songs", blue, listOf(
            Item("LOAD", blue, "Pick a song for that deck. Left LOAD is deck A (blue), right LOAD is deck B (orange)."),
            Item("YouTube", blue, "Search for songs. \"Songs\" searches YouTube Music; switch to \"Videos\" for remixes, live sets and mashups. You can paste a YouTube link too."),
            Item("Spotify", blue, "Paste a Spotify playlist, album or song link (in Spotify: Share → Copy link). Tap a song and MixDeck finds its audio on YouTube Music."),
            Item("Share", blue, "In the YouTube or Spotify app, tap Share → MixDeck to send a song straight here."),
            Item("My songs", blue, "Everything you've loaded before. These load instantly and work offline. Hold one to delete it."),
            Item("Phone files", blue, "Load an MP3, M4A, FLAC, WAV or other audio file from your phone."),
        )),
        Section("The screen, top to bottom", grey, listOf(
            Item("Title", grey, "Song, artist, time left (flashes red in the last 30 seconds), BPM and tempo change. Tap it for BPM fixes and Eject."),
            Item("Overview", grey, "The whole song as a strip. The white line is where you are. Tap anywhere to jump there."),
            Item("Waveform", grey, "A close-up that scrolls past the white centre line. Tall coloured parts are bass (kick drums), white is mids, light blue is treble. The tick marks are beats; long ticks are every 4th beat. When two songs are in time, their ticks line up."),
            Item("Markers", amber, "Orange C = your cue point. Coloured 1-4 = hot cues. A green band = the active loop."),
        )),
        Section("Each deck", blue, listOf(
            Item("▶ / ❚❚", green, "Play and pause."),
            Item("CUE", amber, "Your starting point. Stopped: tap to set it here. Hold to listen from it, let go to jump back. Playing: tap to jump back and stop."),
            Item("Jog wheel", blue, "With VINYL on, touch the middle and move your finger to scratch like a record. Turn the outer edge to speed up or slow down for a moment and line up the beats by ear. The middle shows the BPM and the four dots count the beats."),
            Item("VINYL", blue, "On: the middle of the jog wheel scratches. Off: the whole wheel nudges the tempo."),
            Item("SYNC", blue, "Matches this deck's tempo and beat to the other deck in one tap. It lights up while the two are in sync."),
            Item("Tempo", blue, "The slider next to the jog wheel. Down = faster, up = slower (like club decks). ±8 changes the range to ±16 or ±50%. Double-tap the slider to go back to 0%."),
        )),
        Section("Hot cues", red, listOf(
            Item("1 2 3 4", red, "Tap an empty pad to save a spot in the song. Tap it again any time to jump straight there. Hold a lit pad to clear it. Great for jumping to the drop or the chorus."),
        )),
        Section("Loops", loopGreen, listOf(
            Item("1 2 4 8", loopGreen, "Loop that many beats, starting on the beat. Tap again to leave the loop. Use it to make an intro longer while you get the next song ready."),
            Item("½  ×2", loopGreen, "Make the loop half or twice as long. Halving a few times builds up tension."),
            Item("IN  OUT", loopGreen, "Make your own loop: IN marks the start, OUT marks the end. Tap OUT again to leave the loop, and once more to go back into it."),
        )),
        Section("Effects", purple, listOf(
            Item("ECHO…", purple, "Tap to choose the effect: ECHO, FLANGER (jet sweep), REVERB (big room), CRUSH (lo-fi)."),
            Item("FX", purple, "Turns the effect on and off. Echo and reverb fade out naturally when you turn them off, which is a classic way to end a song."),
            Item("AMT", purple, "How strong the effect is. Effects follow the beat. Change the echo timing from the deck menu (tap the song title)."),
        )),
        Section("Mixer (middle)", orange, listOf(
            Item("TRIM", orange, "Loudness of the song. Songs are evened out automatically; use this to fine-tune."),
            Item("HI MID LOW", orange, "Treble, mids and bass. All the way left cuts that part completely. Tip: cut the bass of the incoming song, then swap the basses on a downbeat."),
            Item("FILTER", purple, "Left = muffled (low-pass), right = thin (high-pass), middle = off. Sweep it for build-ups."),
            Item("Fader", orange, "The tall sliders are each deck's volume. The bars next to them show its level; keep them out of the red."),
            Item("🎧", amber, "Pre-listen. With split cue on (☰ menu) and headphones in, your right ear hears the decks marked 🎧 and your left ear hears the mix."),
            Item("Crossfader", grey, "The bottom slider blends from deck A (left) to deck B (right). In the ☰ menu choose \"sharp\" for scratching."),
            Item("Sampler", pink, "HORN, SIREN, LASER, DROP: tap to fire a sound effect over the mix, tap again to stop it."),
        )),
        Section("Recording & mixes", red, listOf(
            Item("REC", red, "Starts recording everything you hear. Tap again to stop, then name your mix and save it."),
            Item("My mixes", red, "☰ → My mixes: play your mixes back, share them, rename or delete them. They're saved in Music/MixDeck as M4A files."),
        )),
        Section("Menu & help", yellow, listOf(
            Item("?", yellow, "Shows a key on the DJ screen that labels every control. Tap anywhere to close it."),
            Item("☰", grey, "My mixes, this guide, master and sampler volume, crossfader curve, Quantize (cues and loops snap to the beat), split cue, and Check for updates."),
            Item("Title tap", grey, "If the BPM looks wrong (half or double), fix it from the deck menu. \"Put the first beat here\" re-aligns the beat ticks."),
            Item("Knobs", grey, "Drag up or down. Double-tap any knob or slider to reset it."),
        )),
    )

    val firstMix = listOf(
        "Tap the left LOAD and pick a song for deck A. Press ▶. Push the crossfader all the way left.",
        "Tap the right LOAD and pick a song for deck B. Don't play it yet.",
        "On deck B, tap SYNC so it matches deck A's tempo.",
        "Turn deck B's LOW knob all the way left (no bass), so the two kick drums don't clash.",
        "Wait for a new section to start on deck A (the long beat ticks help), then press ▶ on deck B. Tap SYNC again if the beats drift.",
        "Slowly slide the crossfader to the middle. Now both songs are playing together.",
        "On a strong beat, turn deck A's LOW all the way left and deck B's LOW back to the middle: the bass has switched songs.",
        "Slide the crossfader all the way right. Deck B is now playing on its own: you've done your first mix!",
        "Tip: tap REC before you start, and the whole set is saved to My mixes.",
    )

}
