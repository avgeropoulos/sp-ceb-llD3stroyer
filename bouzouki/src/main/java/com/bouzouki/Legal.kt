package com.bouzouki

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.ScrollView
import android.widget.TextView

/**
 * The Terms of Use, what the app does with audio and tabs (privacy), and the third-party parts it is
 * built with. The Terms are accepted once, on first start ([requireTerms]); a new [TERMS_VERSION] asks again.
 */
object Legal {
    /** Bump when the Terms change: they are shown again to accept. */
    const val TERMS_VERSION = 1

    val TERMS = """
        Terms of Use

        1. Personal practice and study. Μπουζούκι Lab is for your own practice and study of the bouzouki: it helps you work out how music is played. It is not for making, publishing or selling transcriptions of other people's music.

        2. Your content, your responsibility. You choose what the app listens to or reads (a song playing on your phone, an audio file, a tab or a picture). You are responsible for having the right to analyse it, and for how you use what the app makes of it. Respect the rights of composers, performers and publishers.

        3. No sharing. The app has no public library, no search across users and no way to publish or send tabs. What you make stays with you.

        4. No warranty. The notes and tabs the app works out can be wrong. The app is provided "as is", without warranty of any kind, and its authors are not liable for any claim or damage arising from its use.

        5. Changes. These terms can change with an update of the app; you are asked to accept them again then.
    """.trimIndent()

    val PRIVACY = """
        Privacy

        • Audio is processed on this phone only. A song is transcribed when you ask, for you. Audio the app records (🎧 Listen) or reads is never uploaded and never kept as training data: a clip lives in the app's private cache only while its tab is made, and is deleted as soon as it is (anything a crash leaves behind is deleted the next time the app starts). An audio file you pick stays where it is; the app only reads it.

        • Tabs and songs are kept only in the app's private storage on this phone, and are left out of cloud backups and device transfers. 🕘 My songs → Delete removes them.

        • The app goes online only to check for and download its own updates and its models (the note and instrument-separation models), from the app's GitHub releases. It sends nothing about you or your music.

        • Listening needs your permission each time: Android asks before the app may capture what plays on the phone, and the microphone is used only while you listen or calibrate.
    """.trimIndent()

    val THIRD_PARTY = """
        Built with

        • Basic Pitch (note transcription model), Spotify. Apache License 2.0. github.com/spotify/basic-pitch
        • ONNX Runtime, Microsoft. MIT License. github.com/microsoft/onnxruntime
        • Demucs (htdemucs_6s instrument separation model), Meta Platforms. MIT License. github.com/facebookresearch/demucs
        • tuttut (fingering ideas: HMM and Viterbi over fretboard positions), Nathan Candre. MIT License. github.com/natecdr/tuttut
        • alphaTab (tablature display and playback), CoderLine. Mozilla Public License 2.0. github.com/CoderLine/alphaTab
          Its Bravura music font: Steinberg Media Technologies, SIL Open Font License 1.1.
          Its sonivox sound font: Apache License 2.0.
        • Kotlin standard library, JetBrains. Apache License 2.0.
    """.trimIndent()

    private fun prefs(context: Context) = context.getSharedPreferences("bouzouki", Context.MODE_PRIVATE)

    fun accepted(context: Context) = prefs(context).getInt(PREF_TERMS, 0) >= TERMS_VERSION

    /** Shows the Terms (and the privacy note) until they are accepted; declining closes the app. Then [then]. */
    fun requireTerms(activity: Activity, then: () -> Unit = {}) {
        if (accepted(activity)) { then(); return }
        AlertDialog.Builder(activity)
            .setTitle("Before you start")
            .setView(text(activity, TERMS + "\n\n" + PRIVACY))
            .setCancelable(false)
            .setPositiveButton("I accept") { _, _ ->
                prefs(activity).edit().putInt(PREF_TERMS, TERMS_VERSION).apply()
                then()
            }
            .setNegativeButton("Decline") { _, _ -> activity.finishAffinity() }
            .show()
    }

    /** ⓘ About: the Terms, privacy and the third-party parts. */
    fun showAbout(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("About Μπουζούκι Lab")
            .setView(text(activity, TERMS + "\n\n" + PRIVACY + "\n\n" + THIRD_PARTY))
            .setPositiveButton("Close", null)
            .show()
    }

    private fun text(activity: Activity, body: String) = ScrollView(activity).apply {
        val ui = Ui(activity)
        addView(TextView(activity).apply {
            text = body
            textSize = 14f
            setLineSpacing(0f, 1.2f)
            setPadding(ui.px(24), ui.px(8), ui.px(24), ui.px(8))
        })
    }

    private const val PREF_TERMS = "terms_accepted"
}
