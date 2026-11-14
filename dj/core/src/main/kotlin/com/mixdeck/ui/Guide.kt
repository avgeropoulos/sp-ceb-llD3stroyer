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
    val teal = 0xFF1DE9B6.toInt()

    /**
     * One row of the guide. [computerText] (and [computerChip]) replace the phone's words on a
     * computer; without them the computer gets the phone's text with "tap" said as "click".
     * [phoneOnly] rows (e.g. Share) aren't shown on a computer.
     */
    class Item(
        val chip: String, val color: Int, val text: String,
        val computerText: String? = null, val phoneOnly: Boolean = false, val computerChip: String? = null,
    )
    class Section(val title: String, val color: Int, val items: List<Item>)

    /** The guide written for the device it's read on: a [computer] (mouse, keyboard) or a phone. */
    fun sections(computer: Boolean): List<Section> = if (!computer) all else all.map { s ->
        Section(s.title, s.color, s.items.filter { !it.phoneOnly }.map {
            Item(it.computerChip ?: it.chip, it.color, it.computerText ?: clickify(it.text))
        })
    }

    /** "Your first mix" steps, written for a [computer] or a phone. */
    fun firstMix(computer: Boolean): List<String> = if (computer) firstMixPhone.map(::clickify) else firstMixPhone

    /** Says the phone's "tap" as "click", for a computer. */
    internal fun clickify(s: String) = s
        .replace(Regex("\\bDouble-tap\\b"), "Double-click").replace(Regex("\\bdouble-tap\\b"), "double-click")
        .replace(Regex("\\bTap\\b"), "Click").replace(Regex("\\btap\\b"), "click")
        .replace(Regex("\\btaps\\b"), "clicks").replace(Regex("\\btapping\\b"), "clicking")

    private val all = listOf(
        Section("Getting songs", blue, listOf(
            Item("LOAD", blue, "Pick a song for that deck. Left LOAD is deck A (blue), right LOAD is deck B (orange).",
                computerText = "Pick a song for that deck. Left LOAD is deck A (blue), right LOAD is deck B (orange). The A and B keys do the same."),
            Item("Suggested", teal, "Songs on your phone that fit after the one on the other deck, best first. NEXT opens this list for the free deck.",
                computerText = "Songs on this computer that fit after the song on the other deck, best first. NEXT opens this list for the free deck."),
            Item("YouTube", blue, "Search for songs. \"Songs\" searches YouTube Music; switch to \"Videos\" for remixes, live sets and mashups. You can paste a YouTube link too."),
            Item("Spotify", blue, "Paste a Spotify playlist, album or song link (in Spotify: Share → Copy link). Tap a song and MixDeck finds its audio on YouTube Music."),
            Item("Share", blue, "In the YouTube or Spotify app, tap Share → MixDeck to send a song straight here.", phoneOnly = true),
            Item("My songs", blue, "Everything you've loaded before. These load instantly and work offline. Hold one to delete it.",
                computerText = "Everything you've loaded before. These load instantly and work offline. Select one and click Delete to remove it."),
            Item("Phone files", blue, "Load an MP3, M4A, FLAC, WAV or other audio file from your phone.",
                computerChip = "Files", computerText = "Load an MP3, M4A, FLAC, WAV, OGG or other audio file from this computer."),
        )),
        Section("The screen, top to bottom", grey, listOf(
            Item("Title", grey, "Song, artist, time left (flashes red in the last 30 seconds), BPM, key and tempo change. Tap it for BPM fixes and Eject."),
            Item("Overview", grey, "The whole song as a strip. The white line is where you are. Tap anywhere to jump there."),
            Item("Waveform", grey, "A close-up that scrolls past the white centre line. Tall coloured parts are bass (kick drums), white is mids, light blue is treble. The tick marks are beats; long ticks are every 4th beat. When two songs are in time, their ticks line up."),
            Item("Markers", amber, "Orange C = your cue point. Coloured 1-4 = hot cues. A green band = the active loop."),
        )),
        Section("What to play next", teal, listOf(
            Item("NEXT", teal, "Suggests songs that fit after the one that's playing, best first, and loads your pick onto the free deck, already synced. Then tap MIX, or press ▶ when the moment's right. It blinks when a song is near its end (about a minute plus the MIX time) if nothing new is waiting."),
            Item("MIX", teal, "Blends the playing song into the one waiting on the other deck: starts it on the next bar in time, brings it in with the crossfader over 8 bars, swaps the basses halfway and stops the old deck. Touch the crossfader, a LOW knob or a volume fader to take over. Hold MIX to change the length (4, 8, 16 or 32 bars).",
                computerText = "Blends the playing song into the one waiting on the other deck: starts it on the next bar in time, brings it in with the crossfader over 8 bars, swaps the basses halfway and stops the old deck. Move the crossfader, a LOW knob or a volume fader to take over. Hold MIX to change the length (4, 8, 16 or 32 bars)."),
            Item("8A", teal, "A song's key on the key wheel (DJs call it the Camelot wheel). Keys mix smoothly when they have the same number, a number next door (8A → 9A), or the same number with the other letter (8A → 8B). Next-door keys also get similar colours."),
            Item("↑ ↓ ~", teal, "These decks work like vinyl: speeding a song up by 6% also raises it by a semitone (one piano key), so its key changes. The key shown is the one you hear: ↑ or ↓ means the tempo slider moved it, ~ means it's between two keys."),
            Item("★★★★★", yellow, "How well a song fits: key, tempo change, energy and sound. Tap it to see the reasons. Big tempo changes cost stars, because on vinyl-style decks voices start to sound too high or too low."),
            Item("Direction", teal, "Keep the vibe (same energy), Build up (a bit more) or Cool down (a bit less). Energy goes from 1 (chilled) to 10 (peak time)."),
            Item("Find new", teal, "\"Find new songs like this\" asks YouTube for songs that go with the playing one, downloads a few (about 5 MB each) and checks which fit. They're marked NEW and saved in My songs."),
        )),
        Section("Each deck", blue, listOf(
            Item("▶ / ❚❚", green, "Play and pause. While the other deck plays in time (after SYNC), ▶ waits for its next beat so the two start together (Quantize, in Settings)."),
            Item("CUE", amber, "Your starting point. Stopped: tap to set it here. Hold to listen from it, let go to jump back. Playing: tap to jump back and stop."),
            Item("Jog wheel", blue, "With VINYL on, touch the middle and move your finger to scratch like a record. Turn the outer edge to speed up or slow down for a moment and line up the beats by ear. The middle shows the BPM and key, and the four dots count the beats.",
                computerText = "With VINYL on, drag the middle to scratch like a record. Drag round the outer edge, or scroll over the wheel, to speed up or slow down for a moment and line up the beats by ear. The middle shows the BPM and key, and the four dots count the beats."),
            Item("VINYL", blue, "On: the middle of the jog wheel scratches. Off: the whole wheel nudges the tempo."),
            Item("SYNC", blue, "Matches this deck's tempo and beat to the other deck in one tap. It lights up while the two are in time and blinks if the beats drift apart: tap it again to line them up."),
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
            Item("🎧", amber, "Pre-listen. With headphones in, tap 🎧 and say yes to split cue: your right ear hears the decks marked 🎧, your left ear hears the mix."),
            Item("Crossfader", grey, "The bottom slider blends from deck A (left) to deck B (right). In ☰ → Settings choose Sharp for scratching."),
            Item("Sampler", pink, "HORN, SIREN, LASER, DROP: tap to fire a sound effect over the mix, tap again to stop it."),
        )),
        Section("Recording & mixes", red, listOf(
            Item("REC", red, "Starts recording everything you hear. Hold REC to stop: the mix is saved straight away in My mixes, where you can rename it."),
            Item("My mixes", red, "☰ → My mixes: play your mixes back, share them, rename or delete them. They're saved in Music/MixDeck as M4A files.",
                computerText = "☰ → My mixes: play your mixes back, show them in their folder, rename or delete them. They're saved in Music/MixDeck as M4A files."),
        )),
        Section("Menu & help", yellow, listOf(
            Item("?", yellow, "Labels every control on the DJ screen. Tap anywhere to close the labels.",
                computerText = "Labels every control and shows its keyboard key. Press H or Esc, or click anywhere, to close."),
            Item("☰", grey, "My mixes, Settings (volumes, crossfader curve, Quantize, MIX length, split cue), MIDI controller setup, this guide, the tutorial, and updates."),
            Item("MIDI controller", grey, "Plug a DJ controller or MIDI keyboard into the phone with USB (most phones need a USB-C adapter). " +
                "In ☰ → MIDI controller, tap a row, then move that knob, fader or button on the controller. After that it drives MixDeck.",
                computerText = "Plug a DJ controller or MIDI keyboard into the computer. In ☰ → MIDI controller, click a row, then move that knob, " +
                    "fader or button on the controller. After that it drives MixDeck."),
            Item("⬆ Update", yellow, "A green dot on ☰ means a new MixDeck is out. ☰ → ⬆ Update downloads it while you keep mixing; then tap Install. " +
                "MixDeck closes while Android installs it; open it again when it's done (with notifications on, a notification brings you back).",
                computerText = "A green dot on ☰ means a new MixDeck is out. ☰ → ⬆ Update downloads it while you keep mixing; then choose " +
                    "Restart now or When I quit. Restart now opens MixDeck again by itself; When I quit puts the update in as MixDeck closes."),
            Item("Title tap", grey, "If the BPM looks wrong (half or double), fix it from the deck menu. \"Put the first beat here\" re-aligns the beat ticks.",
                computerChip = "Title click"),
            Item("Knobs", grey, "Drag up or down. Double-tap any knob or slider to reset it.",
                computerText = "Drag up or down, or scroll the mouse wheel over them. Double-click to reset."),
        )),
    )

    private val firstMixPhone = listOf(
        "Tap the left LOAD and pick a song for deck A. Press ▶. Push the crossfader all the way left.",
        "Tap the right LOAD and pick a song for deck B, or tap NEXT for songs that fit after deck A's. Don't play it yet.",
        "On deck B, tap SYNC so it matches deck A's tempo.",
        "Turn deck B's LOW knob all the way left (no bass), so the two kick drums don't clash.",
        "Wait for a new section to start on deck A (the long beat ticks help), then press ▶ on deck B: it starts exactly on the beat. If SYNC blinks later, tap it to line the beats up again.",
        "Slowly slide the crossfader to the middle. Now both songs are playing together.",
        "On a strong beat, turn deck A's LOW all the way left and deck B's LOW back to the middle: the bass has switched songs.",
        "Slide the crossfader all the way right. Deck B is now playing on its own: you've done your first mix!",
        "Tip: tap REC before you start, and the whole set is saved to My mixes.",
    )

}
