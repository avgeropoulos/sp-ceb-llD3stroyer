package com.bouzouki

import android.app.Activity
import android.content.Context
import android.os.Build
import android.view.Display
import com.bouzoukihero.core.DisplayLead
import com.bouzoukihero.core.SyncStore

/**
 * What the song screen and the game need to know of this phone's screen to draw the notes on
 * time: how often it refreshes, and from that how long a drawn frame takes to be seen
 * ([DisplayLead]). Nothing here may stop a song from opening: whatever can't be read falls back
 * to a 60 Hz screen.
 */
object ScreenTiming {
    /** How many times a second [display] refreshes; 60 when it won't say. */
    fun refreshRate(display: Display?): Float = DisplayLead.hz(
        try { display?.refreshRate ?: 0f } catch (_: Exception) { 0f }
    )

    /** The refresh rate of the screen [activity] is on. */
    @Suppress("DEPRECATION")
    fun refreshRate(activity: Activity): Float {
        val own = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) activity.display else null
        } catch (_: Exception) {
            null
        }
        return refreshRate(own ?: try { activity.windowManager.defaultDisplay } catch (_: Exception) { null })
    }

    /** Seconds between a frame's vsync and seeing it on [activity]'s screen. */
    fun lead(activity: Activity): Double = DisplayLead.seconds(refreshRate(activity))

    /**
     * Once, restates the syncs tuned while the notes were drawn a fixed 25 ms ahead, so each
     * YouTube song's notes stay where its player put them now that the lead follows the screen
     * (see [SyncStore.restated] for that and for what becomes of the rest). Both screens that
     * read a sync call this first; the flag and the new values are written together, so it
     * can't half-happen or happen twice.
     */
    fun restateSyncs(activity: Activity) {
        try {
            val prefs = activity.getSharedPreferences("bouzouki", Context.MODE_PRIVATE)
            if (prefs.getBoolean(SyncStore.RESTATED, false)) return
            val edit = prefs.edit()
            for ((key, value) in SyncStore.restated(prefs.all, refreshRate(activity))) {
                if (value == null) edit.remove(key) else edit.putFloat(key, value)
            }
            edit.putBoolean(SyncStore.RESTATED, true).apply()
        } catch (_: Exception) {
            // The syncs stay as they were (a little off, and tunable); tried again next time.
        }
    }
}
