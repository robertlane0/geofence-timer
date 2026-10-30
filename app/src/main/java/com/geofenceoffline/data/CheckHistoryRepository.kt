package com.geofenceoffline.data

import android.content.Context
import android.database.Cursor

class CheckHistoryRepository(context: Context) {
    private val helper = AppDatabase.get(context)

    /**
     * Inserts records, ignoring any (schedule, occurrence, geofence) tuple that already exists.
     * Returns the number of new rows. This is the duplicate-check guard used by alarm recovery.
     */
    fun insertAll(results: List<CheckResult>): Int {
        if (results.isEmpty()) return 0
        val db = helper.writableDatabase
        var inserted = 0
        db.beginTransaction()
        try {
            results.forEach { result ->
                val key = occurrenceKey(result)
                val rowId = db.insertWithOnConflict(
                    "check_results",
                    null,
                    AppDatabase.contentValuesOf(
                        "schedule_id" to result.scheduleId,
                        "schedule_name" to result.scheduleName,
                        "geofence_id" to result.geofenceId,
                        "geofence_name" to result.geofenceName,
                        "scheduled_at" to result.scheduledAt,
                        "executed_at" to result.executedAt,
                        "status" to result.status.name,
                        "reason" to result.reason.name,
                        "occurrence_key" to key
                    ),
                    android.database.sqlite.SQLiteDatabase.CONFLICT_IGNORE
                )
                if (rowId != -1L) inserted++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        return inserted
    }

    fun list(status: CheckStatus? = null, limit: Int = 500): List<CheckResult> {
        val selection = if (status != null) "status = ?" else null
        val args = if (status != null) arrayOf(status.name) else null
        val result = ArrayList<CheckResult>()
        helper.readableDatabase.query(
            "check_results", null, selection, args, null, null, "scheduled_at DESC, id DESC",
            limit.toString()
        ).use { cursor ->
            while (cursor.moveToNext()) result.add(cursor.toCheckResult())
        }
        return result
    }

    fun get(id: Long): CheckResult? =
        helper.readableDatabase.query(
            "check_results", null, "id = ?", arrayOf(id.toString()), null, null, null
        ).use { cursor -> if (cursor.moveToFirst()) cursor.toCheckResult() else null }

    fun delete(id: Long) {
        helper.writableDatabase.delete("check_results", "id = ?", arrayOf(id.toString()))
    }

    fun deleteAll() {
        helper.writableDatabase.delete("check_results", null, null)
    }

    fun count(): Int =
        helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM check_results", null).use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }

    /** True when this scheduled occurrence has already been evaluated (successfully or not). */
    fun hasOccurrence(scheduleId: String, scheduledAt: Long): Boolean =
        helper.readableDatabase.query(
            "check_results", arrayOf("id"),
            "schedule_id = ? AND scheduled_at = ?",
            arrayOf(scheduleId, scheduledAt.toString()),
            null, null, null, "1"
        ).use { it.moveToFirst() }

    fun countsByStatus(): Map<CheckStatus, Int> {
        val map = HashMap<CheckStatus, Int>()
        helper.readableDatabase.rawQuery(
            "SELECT status, COUNT(*) FROM check_results GROUP BY status", null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                runCatching { CheckStatus.valueOf(cursor.getString(0)) }
                    .onSuccess { map[it] = cursor.getInt(1) }
            }
        }
        return map
    }

    private fun occurrenceKey(result: CheckResult): String =
        "${result.scheduleId ?: "manual"}:${result.scheduledAt}:${result.geofenceId ?: "none"}"
}

private fun Cursor.toCheckResult(): CheckResult = CheckResult(
    id = getLong(getColumnIndexOrThrow("id")),
    scheduleId = getString(getColumnIndexOrThrow("schedule_id")),
    scheduleName = getString(getColumnIndexOrThrow("schedule_name")),
    geofenceId = getString(getColumnIndexOrThrow("geofence_id")),
    geofenceName = getString(getColumnIndexOrThrow("geofence_name")),
    scheduledAt = getLong(getColumnIndexOrThrow("scheduled_at")),
    executedAt = getLong(getColumnIndexOrThrow("executed_at")),
    status = runCatching {
        CheckStatus.valueOf(getString(getColumnIndexOrThrow("status")))
    }.getOrDefault(CheckStatus.UNKNOWN),
    reason = runCatching {
        CheckReason.valueOf(getString(getColumnIndexOrThrow("reason")))
    }.getOrDefault(CheckReason.INTERNAL_ERROR)
)
