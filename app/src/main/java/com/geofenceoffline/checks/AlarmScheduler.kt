package com.geofenceoffline.checks

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.geofenceoffline.data.Schedule
import com.geofenceoffline.data.ScheduleRepository
import com.geofenceoffline.scheduling.SchedulePlanner
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

class AlarmScheduler(context: Context) {
    private val appContext = context.applicationContext
    private val alarmManager = appContext.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val scheduleRepository = ScheduleRepository(appContext)

    data class NextAlarm(val scheduleId: String, val triggerAtMillis: Long, val exact: Boolean)

    fun canScheduleExactAlarms(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) alarmManager.canScheduleExactAlarms()
        else true

    fun scheduleNext(schedule: Schedule, afterMillis: Long = System.currentTimeMillis()): NextAlarm? {
        if (!schedule.enabled) {
            cancel(schedule.id)
            return null
        }
        val next = SchedulePlanner.nextOccurrence(schedule, afterMillis, ZoneId.systemDefault())
        if (next == null) {
            cancel(schedule.id)
            return null
        }
        val pending = pendingIntent(schedule.id, next, PendingIntent.FLAG_UPDATE_CURRENT)
            ?: return null
        val exact = canScheduleExactAlarms()
        if (exact) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
        } else {
            // Inexact delivery is the documented fallback when the exact-alarm special access is denied.
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next, pending)
        }
        return NextAlarm(schedule.id, next, exact)
    }

    fun scheduleNext(scheduleId: String, afterMillis: Long = System.currentTimeMillis()): NextAlarm? {
        val schedule = scheduleRepository.get(scheduleId) ?: run {
            cancel(scheduleId)
            return null
        }
        return scheduleNext(schedule, afterMillis)
    }

    fun rescheduleAll(): List<NextAlarm> {
        val alarms = ArrayList<NextAlarm>()
        scheduleRepository.listAll().forEach { schedule ->
            if (schedule.enabled) {
                scheduleNext(schedule)?.let(alarms::add)
            } else {
                cancel(schedule.id)
            }
        }
        return alarms
    }

    fun cancel(scheduleId: String) {
        val pending = pendingIntent(scheduleId, 0L, PendingIntent.FLAG_NO_CREATE)
        pending?.let { alarmManager.cancel(it) }
    }

    fun nextAlarmDescription(schedule: Schedule, now: Long = System.currentTimeMillis()): String {
        val next = SchedulePlanner.nextOccurrence(schedule, now, ZoneId.systemDefault())
            ?: return "No future occurrence"
        val dateTime = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(next), ZoneId.systemDefault())
        return dateTime.format(DateTimeFormatter.ofPattern("EEE, MMM d 'at' h:mm a"))
    }

    private fun pendingIntent(scheduleId: String, scheduledAt: Long, flags: Int): PendingIntent? {
        val intent = Intent(appContext, CheckAlarmReceiver::class.java).apply {
            action = CheckAlarmReceiver.ACTION_RUN_CHECK
            putExtra(CheckAlarmReceiver.EXTRA_SCHEDULE_ID, scheduleId)
            putExtra(CheckAlarmReceiver.EXTRA_SCHEDULED_AT, scheduledAt)
        }
        val baseFlags = flags or PendingIntent.FLAG_IMMUTABLE
        return PendingIntent.getBroadcast(
            appContext,
            SchedulePlanner.alarmRequestCode(scheduleId),
            intent,
            baseFlags
        )
    }
}
