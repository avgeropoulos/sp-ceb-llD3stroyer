package com.javelinstrike

import android.app.Activity
import com.javelinstrike.game.Updater

/** The GitHub version updates itself from the repository's releases. */
fun createUpdater(activity: Activity): Updater = AppUpdater(activity)
