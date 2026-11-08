package com.goalrush

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** Receives progress from Android's package installer during an in-game update. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                // Android wants the player to confirm the update.
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try { context.startActivity(confirm) } catch (_: Exception) {}
                }
                listener?.invoke(true, "Tap Update when Android asks. The game will restart.")
            }
            PackageInstaller.STATUS_SUCCESS -> listener?.invoke(true, "Updated!")
            PackageInstaller.STATUS_FAILURE_ABORTED -> listener?.invoke(false, "The update was cancelled.")
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                listener?.invoke(false, "Android could not install the update ($status). ${msg ?: ""}".trim())
            }
        }
    }

    companion object {
        @Volatile var listener: ((Boolean, String) -> Unit)? = null
    }
}
