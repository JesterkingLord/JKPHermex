package com.hermexapp.android.platform

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder

/**
 * Ongoing-run foreground service (the Android counterpart of the iOS Live
 * Activity). While a chat run is streaming, this shows an ongoing notification
 * so the run — and its network stream — survives the app going to the
 * background without being killed. Started/stopped by the chat flow via
 * [start]/[stop]; it holds no run logic itself.
 */
class ActiveRunService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "Hermes is working…"
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Active runs",
                NotificationManager.IMPORTANCE_LOW,
            ),
        )

        val contentIntent = packageManager.getLaunchIntentForPackage(packageName)?.let {
            PendingIntent.getActivity(
                this,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText("Tap to return to the chat.")
            .setOngoing(true)
            .apply { contentIntent?.let(::setContentIntent) }
            .build()

        startForeground(NOTIFICATION_ID, notification)
        return START_NOT_STICKY
    }

    companion object {
        private const val CHANNEL_ID = "active_runs"
        private const val NOTIFICATION_ID = 42
        private const val EXTRA_TITLE = "title"

        fun start(context: Context, title: String?) {
            val intent = Intent(context, ActiveRunService::class.java).apply {
                putExtra(EXTRA_TITLE, title)
            }
            // Only meaningful in the background; a foreground-start restriction
            // throw is swallowed since the ongoing notification is best-effort.
            runCatching { context.startForegroundService(intent) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, ActiveRunService::class.java)) }
        }
    }
}
