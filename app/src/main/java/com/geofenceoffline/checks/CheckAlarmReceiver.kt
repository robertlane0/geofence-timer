package com.geofenceoffline.checks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.ContextCompat
import com.geofenceoffline.data.CheckHistoryRepository
import com.geofenceoffline.data.CheckReason
import com.geofenceoffline.data.GeofenceRepository
import com.geofenceoffline.data.ScheduleRepository
import java.util.concurrent.Executors

/**
 * Alarm entry point. The next alarm is scheduled before the check starts, so a crash during the
 * location acquisition cannot silently break the weekly schedule. History insertion has a UNIQUE
 * occurrence key, so retries cannot create duplicate results.
 */
class CheckAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RUN_CHECK) return
        val scheduleId = intent.getStringExtra(EXTRA_SCHEDULE_ID) ?: return
        val scheduledAt = intent.getLongExtra(EXTRA_SCHEDULED_AT, 0L)
        if (scheduleId.isBlank() || scheduledAt <= 0L) return

        try {
            ContextCompat.startForegroundService(
                context,
                CheckService.createIntent(context, scheduleId, scheduledAt)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not start check service (${e.javaClass.simpleName})")
            recordStartFailure(context, scheduleId, scheduledAt)
        }

        val pendingResult = goAsync()
        EXECUTOR.execute {
            try {
                AlarmScheduler(context).scheduleNext(scheduleId, System.currentTimeMillis())
            } catch (e: Exception) {
                Log.w(TAG, "Could not schedule next occurrence (${e.javaClass.simpleName})")
            } finally {
                pendingResult.finish()
            }
        }
    }

    private fun recordStartFailure(context: Context, scheduleId: String, scheduledAt: Long) {
        try {
            val schedule = ScheduleRepository(context).get(scheduleId) ?: return
            if (!schedule.enabled) return
            val fences = schedule.geofenceIds.mapNotNull { GeofenceRepository(context).get(it) }
                .filter { it.enabled }
            val results = CheckEvaluator.unavailable(
                schedule, fences, CheckReason.INTERNAL_ERROR,
                scheduledAt, System.currentTimeMillis()
            )
            CheckHistoryRepository(context).insertAll(results)
        } catch (e: Exception) {
            Log.w(TAG, "Could not record failed check start (${e.javaClass.simpleName})")
        }
    }

    companion object {
        const val ACTION_RUN_CHECK = "com.geofenceoffline.action.RUN_CHECK"
        const val EXTRA_SCHEDULE_ID = "schedule_id"
        const val EXTRA_SCHEDULED_AT = "scheduled_at"
        private const val TAG = "CheckAlarmReceiver"
        private val EXECUTOR = Executors.newSingleThreadExecutor()
    }
}
