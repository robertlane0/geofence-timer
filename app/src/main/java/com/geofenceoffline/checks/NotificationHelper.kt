package com.geofenceoffline.checks

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.geofenceoffline.R
import com.geofenceoffline.data.CheckResult
import com.geofenceoffline.data.CheckStatus

object NotificationHelper {
    const val CHANNEL_CHECKS = "scheduled_checks"
    const val CHANNEL_RESULTS = "check_results"
    const val ONGOING_ID = 4101
    const val RESULT_ID = 4102
    const val EXTRA_OPEN_HISTORY = "open_history"

    fun createChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val checks = NotificationChannel(
            CHANNEL_CHECKS,
            "Scheduled checks",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown briefly while a scheduled location check is running."
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        val results = NotificationChannel(
            CHANNEL_RESULTS,
            "Check results",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "A summary after a scheduled check finishes. Location coordinates are never included."
            setShowBadge(true)
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        manager.createNotificationChannels(listOf(checks, results))
    }

    fun ongoingNotification(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_CHECKS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Location check")
            .setContentText("Checking your location privately…")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun notifyResults(context: Context, results: List<CheckResult>) {
        if (results.isEmpty()) return
        if (!canPostNotifications(context)) return
        val inside = results.count { it.status == CheckStatus.INSIDE }
        val outside = results.count { it.status == CheckStatus.OUTSIDE }
        val unknown = results.count { it.status == CheckStatus.UNKNOWN }
        val title = if (inside + outside == 0) "Location check could not complete" else "Location check completed"
        val summary = buildList {
            if (inside > 0) add("$inside inside")
            if (outside > 0) add("$outside outside")
            if (unknown > 0) add("$unknown unknown")
        }.joinToString(" · ")
        val reasons = results.filter { it.status == CheckStatus.UNKNOWN }
            .map { it.reason.label() }
            .distinct()
            .joinToString("\n")
        val bigText = if (reasons.isBlank()) {
            "Result: $summary.\nOnly inside/outside results are stored; your coordinates are not saved."
        } else {
            "Result: $summary.\n$reasons\nNo inside/outside result was recorded for failed checks."
        }
        val contentIntent = (context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?: Intent()).apply {
            putExtra(EXTRA_OPEN_HISTORY, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context,
            99,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_RESULTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(bigText))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .build()
        runCatching {
            NotificationManagerCompat.from(context).notify(RESULT_ID, notification)
        }
    }

    fun canPostNotifications(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
