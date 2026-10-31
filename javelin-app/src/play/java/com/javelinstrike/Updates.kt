package com.javelinstrike

import android.app.Activity
import com.javelinstrike.game.Updater

/** The Play Store version is updated by the Play Store, so it has no update button. */
@Suppress("UNUSED_PARAMETER")
fun createUpdater(activity: Activity): Updater = Updater.None
