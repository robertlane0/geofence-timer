package com.geofenceoffline.data

import com.geofenceoffline.geo.BoundingBox
import com.geofenceoffline.geo.GeoPoint
import java.time.DayOfWeek

enum class GeofenceType { CIRCLE, POLYGON }

/**
 * A user-created geofence. Circle geofences have [center] and [radiusMeters]; polygon geofences
 * have [polygon]. Disabled fences remain visible on the map but are never evaluated.
 */
data class Geofence(
    val id: String,
    val name: String,
    val type: GeofenceType,
    val center: GeoPoint?,
    val radiusMeters: Double?,
    val polygon: List<GeoPoint>,
    val enabled: Boolean,
    val createdAt: Long,
    val updatedAt: Long
) {
    fun description(): String = when (type) {
        GeofenceType.CIRCLE -> "${formatRadius(radiusMeters ?: 0.0)} circle"
        GeofenceType.POLYGON -> "${polygon.size} vertices"
    }

    fun contains(point: GeoPoint): Boolean = when (type) {
        GeofenceType.CIRCLE -> center != null && radiusMeters != null &&
            com.geofenceoffline.geo.GeoMath.pointInCircle(center, radiusMeters, point)
        GeofenceType.POLYGON -> com.geofenceoffline.geo.GeoMath.pointInPolygon(polygon, point)
    }

    fun bounds(): BoundingBox? = when (type) {
        GeofenceType.CIRCLE -> {
            if (center == null || radiusMeters == null) null
            else BoundingBox(
                center.lat - radiusMeters / 110_574.0,
                center.lon - radiusMeters / (111_320.0 * kotlin.math.cos(Math.toRadians(center.lat))),
                center.lat + radiusMeters / 110_574.0,
                center.lon + radiusMeters / (111_320.0 * kotlin.math.cos(Math.toRadians(center.lat)))
            )
        }
        GeofenceType.POLYGON -> {
            if (polygon.isEmpty()) null
            else BoundingBox(
                polygon.minOf { it.lat }, polygon.minOf { it.lon },
                polygon.maxOf { it.lat }, polygon.maxOf { it.lon }
            )
        }
    }

    private fun formatRadius(meters: Double): String =
        if (meters >= 1000) String.format(java.util.Locale.US, "%.1f km", meters / 1000)
        else "${meters.toInt()} m"
}

enum class ScheduleType { SPECIFIC_TIMES, REPEATING_WINDOW }

/**
 * A weekly schedule. Times of day are stored as minutes after local midnight. Weekday values use
 * java.time.DayOfWeek (1 = Monday ... 7 = Sunday). A window schedule runs at start, start+interval,
 * ... while the generated time is <= end. The end is therefore included when it aligns to the interval.
 */
data class Schedule(
    val id: String,
    val name: String,
    val type: ScheduleType,
    val timesOfDayMinutes: List<Int>,
    val weekdays: Set<DayOfWeek>,
    val windowStartMinutes: Int,
    val windowEndMinutes: Int,
    val intervalMinutes: Int,
    val geofenceIds: List<String>,
    val enabled: Boolean,
    val createdAt: Long
) {
    fun summary(): String {
        val dayText = when {
            weekdays.size == 7 -> "Every day"
            weekdays == setOf(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY
            ) -> "Mon–Fri"
            weekdays == setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) -> "Sat, Sun"
            else -> weekdays.sortedBy { it.value }.joinToString(", ") { it.shortLabel() }
        }
        val timeText = when (type) {
            ScheduleType.SPECIFIC_TIMES ->
                timesOfDayMinutes.sorted().distinct().joinToString(", ") { formatMinute(it) }
            ScheduleType.REPEATING_WINDOW ->
                "Every ${intervalMinutes} min · ${formatMinute(windowStartMinutes)}–${formatMinute(windowEndMinutes)}"
        }
        return "$dayText\n$timeText"
    }

    companion object {
        fun formatMinute(minutes: Int): String {
            val h = (minutes / 60) % 24
            val m = minutes % 60
            val suffix = if (h < 12) "AM" else "PM"
            val h12 = when {
                h == 0 -> 12
                h > 12 -> h - 12
                else -> h
            }
            return String.format(java.util.Locale.US, "%d:%02d %s", h12, m, suffix)
        }
    }
}

fun DayOfWeek.shortLabel(): String = when (this) {
    DayOfWeek.MONDAY -> "Mon"
    DayOfWeek.TUESDAY -> "Tue"
    DayOfWeek.WEDNESDAY -> "Wed"
    DayOfWeek.THURSDAY -> "Thu"
    DayOfWeek.FRIDAY -> "Fri"
    DayOfWeek.SATURDAY -> "Sat"
    DayOfWeek.SUNDAY -> "Sun"
}

enum class CheckStatus { INSIDE, OUTSIDE, UNKNOWN }

enum class CheckReason {
    NONE,
    PERMISSION_DENIED,
    LOCATION_DISABLED,
    NO_FIX,
    INACCURATE_FIX,
    CHECK_DELAYED,
    NO_GEOFENCES,
    INTERNAL_ERROR;

    fun label(): String = when (this) {
        NONE -> "Completed"
        PERMISSION_DENIED -> "Location permission missing"
        LOCATION_DISABLED -> "Location services are off"
        NO_FIX -> "No location fix"
        INACCURATE_FIX -> "Location fix not accurate enough"
        CHECK_DELAYED -> "Check ran too late"
        NO_GEOFENCES -> "No geofences selected"
        INTERNAL_ERROR -> "Internal error"
    }
}

/**
 * A privacy-preserving check record. It deliberately contains no latitude, longitude, accuracy,
 * provider, or any other raw location data. Geofence/schedule names are snapshotted so history
 * remains readable after deletion.
 */
data class CheckResult(
    val id: Long = 0,
    val scheduleId: String?,
    val scheduleName: String,
    val geofenceId: String?,
    val geofenceName: String,
    val scheduledAt: Long,
    val executedAt: Long,
    val status: CheckStatus,
    val reason: CheckReason
)

data class MapArea(
    val id: String,
    val name: String,
    val bounds: BoundingBox,
    val sizeBytes: Long,
    val featureCount: Int,
    val nodeCount: Int,
    val downloadedAt: Long,
    val fileName: String
) {
    fun areaLabel(): String = when {
        bounds.areaKm2 >= 1.0 -> String.format(java.util.Locale.US, "%.2f km²", bounds.areaKm2)
        bounds.areaKm2 >= 0.01 -> String.format(java.util.Locale.US, "%.1f ha", bounds.areaKm2 * 100.0)
        else -> String.format(java.util.Locale.US, "%.0f m²", bounds.areaKm2 * 1_000_000.0)
    }
}

data class AppSettings(
    val accuracyThresholdMeters: Int,
    val locationTimeoutSeconds: Int,
    val lateGraceMinutes: Int,
    val notifyResults: Boolean
)

data class PermissionStatus(
    val fineLocation: Boolean,
    val backgroundLocation: Boolean,
    val notifications: Boolean,
    val exactAlarms: Boolean,
    val batteryOptimized: Boolean
) {
    fun isReadyForScheduledChecks(): Boolean =
        fineLocation && backgroundLocation && exactAlarms
}
