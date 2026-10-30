package com.geofenceoffline.scheduling

import com.geofenceoffline.data.Schedule
import com.geofenceoffline.data.ScheduleType
import com.geofenceoffline.data.shortLabel
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Deterministic expansion of weekly schedules. All calculations use the device's current time zone.
 *
 * Boundary policy:
 *  - A window of 16:00-17:00 every 10 minutes produces 16:00, 16:10, ..., 17:00 (both ends inclusive).
 *  - If the end does not align with the interval, the last aligned time before the end is used.
 *  - A specific time is a single occurrence.
 */
object SchedulePlanner {
    const val MAX_OCCURRENCES_PER_DAY = 2_000

    fun validate(schedule: Schedule): String? {
        if (schedule.name.isBlank()) return "Enter a schedule name"
        if (schedule.weekdays.isEmpty()) return "Select at least one day"
        if (schedule.type == ScheduleType.SPECIFIC_TIMES) {
            if (schedule.timesOfDayMinutes.isEmpty()) return "Add at least one time"
            if (schedule.timesOfDayMinutes.any { it !in 0..1439 }) return "Times must be between 00:00 and 23:59"
        } else {
            if (schedule.windowStartMinutes !in 0..1439) return "Start time is invalid"
            if (schedule.windowEndMinutes !in 0..1439) return "End time is invalid"
            if (schedule.windowEndMinutes <= schedule.windowStartMinutes) {
                return "End time must be after start time"
            }
            if (schedule.intervalMinutes !in 1..1439) return "Interval must be between 1 and 1439 minutes"
            if (schedule.intervalMinutes > schedule.windowEndMinutes - schedule.windowStartMinutes) {
                return "Interval is longer than the time window"
            }
        }
        return null
    }

    fun occurrencesOnDate(schedule: Schedule, date: LocalDate, zone: ZoneId): List<Long> {
        if (date.dayOfWeek !in schedule.weekdays) return emptyList()
        val minutes = when (schedule.type) {
            ScheduleType.SPECIFIC_TIMES -> schedule.timesOfDayMinutes.distinct().sorted()
            ScheduleType.REPEATING_WINDOW -> windowMinutes(schedule)
        }
        return minutes.take(MAX_OCCURRENCES_PER_DAY).mapNotNull { minute ->
            val time = LocalTime.of(minute / 60, minute % 60)
            ZonedDateTime.of(date, time, zone).toInstant().toEpochMilli()
        }.distinct()
    }

    fun windowMinutes(schedule: Schedule): List<Int> {
        if (schedule.windowEndMinutes < schedule.windowStartMinutes) return emptyList()
        if (schedule.intervalMinutes <= 0) return emptyList()
        val result = ArrayList<Int>()
        var minute = schedule.windowStartMinutes
        while (minute <= schedule.windowEndMinutes && result.size < MAX_OCCURRENCES_PER_DAY) {
            result.add(minute)
            minute += schedule.intervalMinutes
        }
        return result
    }

    /**
     * First scheduled occurrence strictly after [afterMillis]. Returns null for invalid or disabled
     * schedules. Searches a bounded 10-day horizon, which covers every weekly pattern.
     */
    fun nextOccurrence(schedule: Schedule, afterMillis: Long, zone: ZoneId): Long? {
        if (!schedule.enabled) return null
        if (validate(schedule) != null) return null
        val startDate = Instant.ofEpochMilli(afterMillis).atZone(zone).toLocalDate()
        for (dayOffset in 0..9) {
            val date = startDate.plusDays(dayOffset.toLong())
            for (occurrence in occurrencesOnDate(schedule, date, zone)) {
                if (occurrence > afterMillis) return occurrence
            }
        }
        return null
    }

    fun occurrencesBetween(
        schedule: Schedule,
        fromInclusive: Long,
        toInclusive: Long,
        zone: ZoneId
    ): List<Long> {
        if (!schedule.enabled || toInclusive < fromInclusive) return emptyList()
        if (validate(schedule) != null) return emptyList()
        val result = ArrayList<Long>()
        var date = Instant.ofEpochMilli(fromInclusive).atZone(zone).toLocalDate().minusDays(1)
        val lastDate = Instant.ofEpochMilli(toInclusive).atZone(zone).toLocalDate()
        while (!date.isAfter(lastDate)) {
            for (occurrence in occurrencesOnDate(schedule, date, zone)) {
                if (occurrence in fromInclusive..toInclusive) result.add(occurrence)
            }
            date = date.plusDays(1)
        }
        return result.distinct().sorted()
    }

    fun occurrenceKey(scheduleId: String, scheduledAt: Long, geofenceId: String): String =
        "$scheduleId:$scheduledAt:$geofenceId"

    /**
     * A stable request code for one schedule's pending alarm. Only the next occurrence is registered
     * per schedule, so replacing the pending intent is safe and avoids duplicate alarms.
     */
    fun alarmRequestCode(scheduleId: String): Int = scheduleId.hashCode() and 0x7FFFFFFF

    fun nextOccurrenceDescription(schedule: Schedule, afterMillis: Long, zone: ZoneId): String {
        val next = nextOccurrence(schedule, afterMillis, zone) ?: return "No upcoming check"
        val dateTime = Instant.ofEpochMilli(next).atZone(zone)
        val day = dateTime.dayOfWeek.shortLabel()
        val time = Schedule.formatMinute(dateTime.hour * 60 + dateTime.minute)
        return "$day, ${dateTime.monthValue}/${dateTime.dayOfMonth} at $time"
    }
}
