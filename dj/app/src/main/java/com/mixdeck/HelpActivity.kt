package com.mixdeck

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** The full guide: a "your first mix" walkthrough and a key for every control. */
class HelpActivity : Activity() {
    private val blue = 0xFF29B6F6.toInt()
    private val orange = 0xFFFF7043.toInt()
    private val green = 0xFF00E676.toInt()
    private val amber = 0xFFFFA000.toInt()
    private val loopGreen = 0xFF32CD32.toInt()
    private val purple = 0xFFB388FF.toInt()
    private val pink = 0xFFFF4081.toInt()
    private val red = 0xFFFF1744.toInt()
    private val grey = 0xFF8A8A96.toInt()
    private val yellow = 0xFFFFEB3B.toInt()

    private class Item(val chip: String, val color: Int, val text: String)
    private class Section(val title: String, val color: Int, val items: List<Item>)

    private val sections = listOf(
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

    private val firstMix = listOf(
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(24)) }

        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "How MixDeck works"; setTextColor(yellow); textSize = 22f; typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(Button(this).apply {
            text = "▶ Nelly tutorial"; isAllCaps = false; textSize = 14f; setTextColor(Color.BLACK)
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xFF00E676.toInt()) }
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { setResult(RESULT_TUTORIAL); finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(8) })
        header.addView(Button(this).apply {
            text = "Show the key"; isAllCaps = false; textSize = 14f; setTextColor(Color.BLACK)
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(yellow) }
            setPadding(dp(12), 0, dp(12), 0)
            setOnClickListener { setResult(RESULT_SHOW_KEY); finish() }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(40)).apply { marginEnd = dp(8) })
        header.addView(Button(this).apply {
            text = "✕"; textSize = 16f; setTextColor(Color.WHITE)
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0xFF2A2A31.toInt()) }
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(48), dp(40)))
        col.addView(header)

        // Interactive tutorial.
        col.addView(heading("Try it hands-on", green))
        col.addView(row("▶", green, "Tap \"▶ Nelly tutorial\" (top right, or ☰ → Tutorial) for a guided mix of Nelly's " +
            "\"Air Force Ones\" into \"Grillz\". It loads both songs, lights up each button you need, " +
            "and moves on when you've done each step.", round = true))

        // Your first mix.
        col.addView(heading("Your first mix in 8 steps", green))
        firstMix.forEachIndexed { i, step ->
            val tip = step.startsWith("Tip")
            col.addView(row(if (tip) "★" else "${i + 1}", if (tip) yellow else green, step, round = true))
        }

        for (s in sections) {
            col.addView(heading(s.title, s.color))
            for (it in s.items) col.addView(row(it.chip, it.color, it.text))
        }

        col.addView(TextView(this).apply {
            text = "MixDeck ${BuildConfig.VERSION_NAME} · updates install from inside the app (☰ → Check for updates). " +
                "YouTube downloads are for personal use only."
            setTextColor(0xFF77778A.toInt()); textSize = 12f; setPadding(0, dp(20), 0, 0)
        })

        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF0E0E11.toInt()); addView(col) })
    }

    private fun heading(t: String, color: Int) = TextView(this).apply {
        text = t; setTextColor(color); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(18), 0, dp(6))
    }

    /** One line of the key: a chip that looks like the control, then what it does. */
    private fun row(chip: String, color: Int, body: String, round: Boolean = false): LinearLayout {
        val r = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(5), 0, dp(5)) }
        r.addView(TextView(this).apply {
            text = chip; gravity = Gravity.CENTER; textSize = 13f; typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE); setPadding(dp(6), dp(6), dp(6), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(if (round) 16 else 7).toFloat()
                setColor(0xFF2A2A31.toInt()); setStroke(dp(2), color)
            }
            minWidth = dp(if (round) 32 else 104)
        }, LinearLayout.LayoutParams(if (round) dp(32) else dp(104), ViewGroup.LayoutParams.WRAP_CONTENT))
        r.addView(TextView(this).apply {
            text = body; setTextColor(0xFFE6E6EC.toInt()); textSize = 15f; setLineSpacing(0f, 1.15f)
            setPadding(dp(14), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        return r
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    companion object {
        const val RESULT_SHOW_KEY = 7
        const val RESULT_TUTORIAL = 8
    }
}
