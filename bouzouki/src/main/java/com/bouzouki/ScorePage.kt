@file:OptIn(kotlin.contracts.ExperimentalContracts::class) // alphaTab's API is declared with contracts
package com.bouzouki

import alphaTab.AlphaTabView
import alphaTab.LayoutMode
import alphaTab.PlayerMode
import alphaTab.StaveProfile
import android.app.Activity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.bouzouki.core.song.AlphaTex
import com.bouzouki.core.song.SongAnalysis

/**
 * 🎼 Score: the song's tab as alphaTab draws it (a tab staff, a string per course, bars at the song's tempo), with
 * alphaTab's own player (its built-in sound font) and cursor. Made when it's first shown; [show] gives it a song (the
 * line on screen, as edited).
 */
class ScorePage(private val activity: Activity, private val ui: Ui) : LinearLayout(activity) {
    private var view: AlphaTabView? = null
    private var shown: SongAnalysis? = null
    private var pending: SongAnalysis? = null
    private val playButton: Button
    private val speedButton: Button
    private val info: TextView
    private val speeds = listOf(0.5, 0.75, 1.0)
    private var speed = 2

    init {
        orientation = VERTICAL
        playButton = ui.chip("▶ Play the tab") { togglePlay() }
        speedButton = ui.chip("Speed 1×") { cycleSpeed() }
        info = TextView(activity).apply { textSize = 12f; setTextColor(0xFF9FB4CC.toInt()); setPadding(ui.px(6), 0, ui.px(6), 0) }
        addView(ui.row().apply {
            addView(playButton, ui.weighted(1.4f))
            addView(speedButton, ui.weighted(1f))
        })
        addView(info)
    }

    private fun cycleSpeed() {
        speed = (speed + 1) % speeds.size
        view?.api?.playbackSpeed = speeds[speed]
        speedButton.text = "Speed ${speeds[speed].toString().removeSuffix(".0")}×"
    }

    /** Shows [song] (null: none) once this page is on screen; again only when it changed. */
    fun show(song: SongAnalysis?) {
        pending = song
        if (isShown) load()
    }

    /** Called when the page comes on screen. */
    fun visible() {
        if (view == null) {
            view = AlphaTabView(activity, null).apply {
                settings.display.staveProfile = StaveProfile.Tab
                settings.display.layoutMode = LayoutMode.Page
                settings.player.playerMode = PlayerMode.EnabledSynthesizer
                settings.player.enableCursor = true
                settings.player.enableUserInteraction = true
            }
            addView(view, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            view!!.api.playerStateChanged.on { e ->
                activity.runOnUiThread { playButton.text = if (e.state == alphaTab.synth.PlayerState.Playing) "⏸ Pause" else "▶ Play the tab" }
            }
            view!!.api.playbackSpeed = speeds[speed]
        }
        load()
    }

    private fun load() {
        val v = view ?: return
        val s = pending
        if (s === shown) return
        shown = s
        if (s == null || s.notes.isEmpty()) { info.text = "Open a song to see its tab here."; return }
        val bpm = AlphaTex.bpm(s)
        info.text = "${s.notes.size} notes, bars of 4/4 at ${bpm.toInt()} BPM (the song's beat). Tap a note to play from there."
        try {
            v.api.tex(AlphaTex.of(s, bpm))
        } catch (t: Throwable) {
            info.text = "alphaTab couldn't show this tab (${t.message})."
        }
    }

    private fun togglePlay() {
        val v = view ?: return
        v.api.playPause()
    }

    /** Stops alphaTab's player (the screen is leaving, or another page shows). */
    fun stop() {
        try { view?.api?.pause() } catch (_: Throwable) {}
    }
}
