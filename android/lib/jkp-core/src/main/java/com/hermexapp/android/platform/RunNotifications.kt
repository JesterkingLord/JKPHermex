package com.hermexapp.android.platform

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicInteger

/**
 * Phase 9: "response complete" notifications — posted only when the app is in
 * the background (foreground activity count is tracked by [AppVisibility]),
 * mirroring the iOS response-completion notifications. Requires the
 * POST_NOTIFICATIONS runtime permission on Android 13+; when not granted the
 * post is silently skipped (the permission ask lives in the chat screen).
 */
class RunNotifications(private val context: Context) {

    private val nextId = AtomicInteger(1)

    fun notifyRunComplete(sessionTitle: String?, sessionId: String?) {
        if (AppVisibility.isForeground) return
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Run completions",
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )

        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply {
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                sessionId?.let { putExtra(EXTRA_SESSION_ID, it) }
            }
        val contentIntent = launch?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = Notification.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(sessionTitle ?: "Run finished")
            .setContentText("The agent finished responding.")
            .setAutoCancel(true)
            .apply { contentIntent?.let(::setContentIntent) }
            .build()

        manager.notify(nextId.getAndIncrement(), notification)
    }

    companion object {
        private const val CHANNEL_ID = "run_completions"
        const val EXTRA_SESSION_ID = "session_id"
    }
}

/** Foreground tracking via activity lifecycle callbacks — no extra dependency. */
object AppVisibility {
    @Volatile
    var foregroundActivities: Int = 0

    val isForeground: Boolean get() = foregroundActivities > 0
}
