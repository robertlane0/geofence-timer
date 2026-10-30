package com.geofenceoffline.data

import android.content.Context
import android.database.Cursor
import java.time.DayOfWeek

class ScheduleRepository(context: Context) {
    private val helper = AppDatabase.get(context)

    fun listAll(): List<Schedule> {
        val result = ArrayList<Schedule>()
        helper.readableDatabase.query(
            "schedules", null, null, null, null, null, "created_at ASC"
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val schedule = cursor.toSchedule(emptyList())
                result.add(schedule)
            }
        }
        val links = loadLinks()
        return result.map { it.copy(geofenceIds = links[it.id] ?: emptyList()) }
    }

    fun listEnabled(): List<Schedule> = listAll().filter { it.enabled }

    fun get(id: String): Schedule? {
        val schedule = helper.readableDatabase.query(
            "schedules", null, "id = ?", arrayOf(id), null, null, null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toSchedule(emptyList()) else null }
            ?: return null
        return schedule.copy(geofenceIds = loadLinks()[id] ?: emptyList())
    }

    fun save(schedule: Schedule) {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val values = AppDatabase.contentValuesOf(
                "id" to schedule.id,
                "name" to schedule.name,
                "type" to schedule.type.name,
                "times" to PersistenceCodec.encodeMinutes(schedule.timesOfDayMinutes),
                "weekdays" to PersistenceCodec.encodeWeekdays(schedule.weekdays),
                "window_start" to schedule.windowStartMinutes,
                "window_end" to schedule.windowEndMinutes,
                "interval_minutes" to schedule.intervalMinutes,
                "enabled" to schedule.enabled,
                "created_at" to schedule.createdAt
            )
            val updated = db.update("schedules", values, "id = ?", arrayOf(schedule.id))
            if (updated == 0) db.insertOrThrow("schedules", null, values)
            db.delete("schedule_geofences", "schedule_id = ?", arrayOf(schedule.id))
            schedule.geofenceIds.distinct().forEach { geofenceId ->
                db.insertWithOnConflict(
                    "schedule_geofences",
                    null,
                    AppDatabase.contentValuesOf(
                        "schedule_id" to schedule.id,
                        "geofence_id" to geofenceId
                    ),
                    android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
                )
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        helper.writableDatabase.update(
            "schedules",
            AppDatabase.contentValuesOf("enabled" to enabled),
            "id = ?",
            arrayOf(id)
        )
    }

    fun delete(id: String) {
        helper.writableDatabase.delete("schedules", "id = ?", arrayOf(id))
    }

    fun count(): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM schedules", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    private fun loadLinks(): Map<String, List<String>> {
        val map = HashMap<String, MutableList<String>>()
        helper.readableDatabase.query(
            "schedule_geofences", null, null, null, null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val scheduleId = cursor.getString(cursor.getColumnIndexOrThrow("schedule_id"))
                val geofenceId = cursor.getString(cursor.getColumnIndexOrThrow("geofence_id"))
                map.getOrPut(scheduleId) { ArrayList() }.add(geofenceId)
            }
        }
        return map
    }
}

private fun Cursor.toSchedule(geofenceIds: List<String>): Schedule {
    val type = runCatching {
        ScheduleType.valueOf(getString(getColumnIndexOrThrow("type")))
    }.getOrDefault(ScheduleType.SPECIFIC_TIMES)
    return Schedule(
        id = getString(getColumnIndexOrThrow("id")),
        name = getString(getColumnIndexOrThrow("name")),
        type = type,
        timesOfDayMinutes = PersistenceCodec.decodeMinutes(getString(getColumnIndexOrThrow("times"))),
        weekdays = PersistenceCodec.decodeWeekdays(getString(getColumnIndexOrThrow("weekdays")))
            .ifEmpty { DayOfWeek.entries.toSet() },
        windowStartMinutes = getInt(getColumnIndexOrThrow("window_start")),
        windowEndMinutes = getInt(getColumnIndexOrThrow("window_end")),
        intervalMinutes = getInt(getColumnIndexOrThrow("interval_minutes")),
        geofenceIds = geofenceIds,
        enabled = getInt(getColumnIndexOrThrow("enabled")) != 0,
        createdAt = getLong(getColumnIndexOrThrow("created_at"))
    )
}
