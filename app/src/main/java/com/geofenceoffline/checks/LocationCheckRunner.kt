package com.geofenceoffline.checks

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.geofenceoffline.data.CheckHistoryRepository
import com.geofenceoffline.data.CheckReason
import com.geofenceoffline.data.CheckResult
import com.geofenceoffline.data.GeofenceRepository
import com.geofenceoffline.data.ScheduleRepository
import com.geofenceoffline.data.SettingsRepository
import kotlinx.coroutines.CancellationException

data class CheckRunOutcome(
    val results: List<CheckResult>,
    val insertedCount: Int
)

/**
 * Coordinates one scheduled check: policy checks, one location acquisition, geofence evaluation and
 * persistence. Coordinates live only in the local [FixOutcome.Success] object and are discarded.
 */
class LocationCheckRunner(
    context: Context,
    private val fixSource: FixSource = LocationFixSource(context),
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val appContext = context.applicationContext
    private val scheduleRepository = ScheduleRepository(appContext)
    private val geofenceRepository = GeofenceRepository(appContext)
    private val historyRepository = CheckHistoryRepository(appContext)
    private val settingsRepository = SettingsRepository(appContext)

    suspend fun run(scheduleId: String, scheduledAt: Long): CheckRunOutcome {
        val schedule = scheduleRepository.get(scheduleId) ?: return CheckRunOutcome(emptyList(), 0)
        if (!schedule.enabled) return CheckRunOutcome(emptyList(), 0)
        // Alarms can be re-delivered after a process restart. Never acquire another fix for an
        // occurrence that already has persisted results.
        if (historyRepository.hasOccurrence(scheduleId, scheduledAt)) {
            return CheckRunOutcome(emptyList(), 0)
        }
        val fences = schedule.geofenceIds
            .mapNotNull { geofenceRepository.get(it) }
            .filter { it.enabled }
        val settings = settingsRepository.load()
        val now = clock()

        if (CheckEvaluator.isBeyondGrace(scheduledAt, now, settings.lateGraceMinutes)) {
            return persist(
                CheckEvaluator.unavailable(
                    schedule, fences, CheckReason.CHECK_DELAYED, scheduledAt, now
                )
            )
        }

        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return persist(
                CheckEvaluator.unavailable(
                    schedule, fences, CheckReason.PERMISSION_DENIED, scheduledAt, clock()
                )
            )
        }

        val outcome = try {
            fixSource.acquire(settings.accuracyThresholdMeters, settings.locationTimeoutSeconds)
        } catch (e: CancellationException) {
            throw e
        } catch (_: SecurityException) {
            FixOutcome.PermissionDenied
        } catch (_: Exception) {
            FixOutcome.NoFix
        }

        val executedAt = clock()
        val results = when (outcome) {
            is FixOutcome.Success -> CheckEvaluator.evaluate(
                schedule, fences, com.geofenceoffline.geo.GeoPoint(
                    outcome.location.latitude, outcome.location.longitude
                ),
                scheduledAt, executedAt
            )
            FixOutcome.PermissionDenied -> CheckEvaluator.unavailable(
                schedule, fences, CheckReason.PERMISSION_DENIED, scheduledAt, executedAt
            )
            FixOutcome.Disabled -> CheckEvaluator.unavailable(
                schedule, fences, CheckReason.LOCATION_DISABLED, scheduledAt, executedAt
            )
            FixOutcome.NoFix -> CheckEvaluator.unavailable(
                schedule, fences, CheckReason.NO_FIX, scheduledAt, executedAt
            )
            is FixOutcome.Inaccurate -> CheckEvaluator.unavailable(
                schedule, fences, CheckReason.INACCURATE_FIX, scheduledAt, executedAt
            )
        }
        return persist(results)
    }

    /** Records an UNKNOWN outcome for an execution that failed before location could be attempted. */
    fun recordFailure(scheduleId: String, scheduledAt: Long, reason: CheckReason) {
        val schedule = scheduleRepository.get(scheduleId) ?: return
        if (!schedule.enabled) return
        val fences = schedule.geofenceIds
            .mapNotNull { geofenceRepository.get(it) }
            .filter { it.enabled }
        persist(
            CheckEvaluator.unavailable(schedule, fences, reason, scheduledAt, clock())
        )
    }

    private fun persist(results: List<CheckResult>): CheckRunOutcome {
        val inserted = historyRepository.insertAll(results)
        return CheckRunOutcome(results, inserted)
    }
}
