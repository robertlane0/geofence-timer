package com.geofenceoffline.checks

import com.geofenceoffline.data.CheckReason
import com.geofenceoffline.data.CheckResult
import com.geofenceoffline.data.CheckStatus
import com.geofenceoffline.data.Geofence
import com.geofenceoffline.data.Schedule
import com.geofenceoffline.geo.GeoPoint

/** Pure evaluation logic, kept separate from Android so it can be unit tested. */
object CheckEvaluator {
    fun evaluate(
        schedule: Schedule,
        fences: List<Geofence>,
        point: GeoPoint,
        scheduledAt: Long,
        executedAt: Long
    ): List<CheckResult> {
        if (fences.isEmpty()) {
            return listOf(noFenceResult(schedule, scheduledAt, executedAt))
        }
        return fences.map { fence ->
            CheckResult(
                scheduleId = schedule.id,
                scheduleName = schedule.name,
                geofenceId = fence.id,
                geofenceName = fence.name,
                scheduledAt = scheduledAt,
                executedAt = executedAt,
                status = if (fence.contains(point)) CheckStatus.INSIDE else CheckStatus.OUTSIDE,
                reason = CheckReason.NONE
            )
        }
    }

    /** Produces UNKNOWN records; never manufactures an inside/outside result. */
    fun unavailable(
        schedule: Schedule,
        fences: List<Geofence>,
        reason: CheckReason,
        scheduledAt: Long,
        executedAt: Long
    ): List<CheckResult> {
        require(reason != CheckReason.NONE)
        if (fences.isEmpty()) {
            return listOf(
                noFenceResult(schedule, scheduledAt, executedAt).copy(
                    status = CheckStatus.UNKNOWN,
                    reason = reason
                )
            )
        }
        return fences.map { fence ->
            CheckResult(
                scheduleId = schedule.id,
                scheduleName = schedule.name,
                geofenceId = fence.id,
                geofenceName = fence.name,
                scheduledAt = scheduledAt,
                executedAt = executedAt,
                status = CheckStatus.UNKNOWN,
                reason = reason
            )
        }
    }

    fun isBeyondGrace(scheduledAt: Long, now: Long, graceMinutes: Int): Boolean =
        now - scheduledAt > graceMinutes.coerceAtLeast(1) * 60_000L

    private fun noFenceResult(schedule: Schedule, scheduledAt: Long, executedAt: Long): CheckResult =
        CheckResult(
            scheduleId = schedule.id,
            scheduleName = schedule.name,
            geofenceId = null,
            geofenceName = "No enabled geofences",
            scheduledAt = scheduledAt,
            executedAt = executedAt,
            status = CheckStatus.UNKNOWN,
            reason = CheckReason.NO_GEOFENCES
        )
}
