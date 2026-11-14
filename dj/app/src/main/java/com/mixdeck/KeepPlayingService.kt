package com.mixdeck

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * Keeps MixDeck running while you're in another app (copying a Spotify link, answering a message).
 * Without it Android may freeze or close an app in the background, and the music, REC and
 * downloads would stop. MainActivity keeps it on while any of them is going on ([keep]).
 */
class KeepPlayingService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        getSystemService(NotificationManager::class.java)
            ?.createNotificationChannel(NotificationChannel(CHANNEL, "Playing", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("MixDeck is running")
            .setContentText("Music, recording or downloads keep going · tap to go back")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(NOTIFICATION, n)
        // MixDeck stopped needing it before it got here: it has started properly, so it can stop now.
        if (!wanted) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); on = false }
        return START_NOT_STICKY
    }

    /** Swiped away from Recents: MixDeck closes (saving a recording), so there's nothing to keep. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        on = false
        stopSelf()
    }

    companion object {
        private const val CHANNEL = "playing"
        private const val NOTIFICATION = 2
        @Volatile private var on = false
        /** What MixDeck asked for last ([keep]'s busy). */
        @Volatile private var wanted = false
        private var startedAt = 0L

        /**
         * On while [busy], off otherwise. It can only start while MixDeck is in front; if it
         * can't, the next call tries again.
         */
        fun keep(ctx: Context, busy: Boolean) {
            wanted = busy
            if (busy == on) return
            val app = ctx.applicationContext
            val i = Intent(app, KeepPlayingService::class.java)
            if (busy) {
                runCatching { app.startForegroundService(i) }.onSuccess { on = true; startedAt = SystemClock.elapsedRealtime() }
                return
            }
            // In its first seconds Android wants a service it was asked to start to start first: stop it a bit later
            // (MixDeck may be closing, so nothing else would ask again).
            val wait = 5000 - (SystemClock.elapsedRealtime() - startedAt)
            val stop = { if (!wanted && on) { app.stopService(i); on = false } }
            if (wait <= 0) stop() else Handler(Looper.getMainLooper()).postDelayed(stop, wait)
        }
    }
}
