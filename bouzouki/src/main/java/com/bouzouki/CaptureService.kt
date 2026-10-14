package com.bouzouki

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import com.bouzouki.core.NoteTracker

/**
 * Listens to the sound the YouTube video is playing inside the app (Android 10+), straight
 * from the audio system rather than through the microphone, so the notes come out cleaner.
 * Android only allows this from a foreground service, after you agree to it on screen.
 */
@TargetApi(Build.VERSION_CODES.Q)
class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var listener: PitchListener? = null

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }

        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = manager.getMediaProjection(resultCode, data) ?: throw IllegalStateException("no projection")
            projection = p
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
            }, Handler(Looper.getMainLooper()))
            val config = AudioPlaybackCaptureConfiguration.Builder(p)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
            val record = AudioRecord.Builder()
                .setAudioPlaybackCaptureConfig(config)
                .setAudioFormat(PitchListener.format)
                .setBufferSizeInBytes(PitchListener.bufferBytes())
                .build()
            listener = PitchListener(record) { e, tally -> onEvent?.invoke(e, tally) }.also { it.start() }
            running = true
            onStateChanged?.invoke()
        } catch (e: Exception) {
            onError?.invoke(e.message ?: e.javaClass.simpleName)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        listener?.stop()
        listener = null
        projection?.stop()
        projection = null
        running = false
        onStateChanged?.invoke()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Listening to songs", NotificationManager.IMPORTANCE_LOW))
        return Notification.Builder(this, CHANNEL)
            .setContentTitle("Μπουζούκι is listening")
            .setContentText("Showing the song's notes on the bouzouki")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL = "listening"
        private const val NOTIFICATION_ID = 7
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"

        /** Set by the song screen while it's showing; called on the main thread. */
        var onEvent: ((NoteTracker.Event, DoubleArray) -> Unit)? = null
        var onStateChanged: (() -> Unit)? = null
        var onError: ((String) -> Unit)? = null

        @Volatile var running = false
            private set
    }
}
