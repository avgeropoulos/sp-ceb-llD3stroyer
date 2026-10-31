package com.javelinstrike

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/**
 * Hears back from Android's installer during an in-app update. It shows the system's
 * "Update this app?" confirmation, and reports failures back to [AppUpdater].
 * On success Android replaces the app, so there's nothing to do.
 */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        context.startActivity(confirm)
                    } catch (e: Exception) {
                        onFailure?.invoke("Couldn't open the installer: ${e.message}")
                    }
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {}
            PackageInstaller.STATUS_FAILURE_ABORTED -> onFailure?.invoke("Update cancelled.")
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                onFailure?.invoke("This copy was installed from an older build. Uninstall it once, then install from the download link.")
            else -> onFailure?.invoke(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install failed (status $status).",
            )
        }
    }

    companion object {
        /** Set by [AppUpdater] to show what went wrong on the title screen. */
        @Volatile var onFailure: ((String) -> Unit)? = null
    }
}
