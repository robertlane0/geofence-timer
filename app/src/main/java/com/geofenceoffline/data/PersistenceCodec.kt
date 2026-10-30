package com.geofenceoffline.data

import com.geofenceoffline.geo.GeoPoint
import java.time.DayOfWeek
import java.util.Locale

/** Compact, human-readable encodings used in SQLite text columns. */
object PersistenceCodec {
    fun encodePolygon(points: List<GeoPoint>): String =
        points.joinToString(";") { String.format(Locale.US, "%.7f,%.7f", it.lat, it.lon) }

    fun decodePolygon(value: String): List<GeoPoint> {
        if (value.isBlank()) return emptyList()
        return value.split(';').mapNotNull { pair ->
            val parts = pair.split(',')
            if (parts.size != 2) return@mapNotNull null
            val lat = parts[0].toDoubleOrNull() ?: return@mapNotNull null
            val lon = parts[1].toDoubleOrNull() ?: return@mapNotNull null
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
            GeoPoint(lat, lon)
        }
    }

    fun encodeMinutes(values: List<Int>): String = values.distinct().sorted().joinToString(",")

    fun decodeMinutes(value: String): List<Int> =
        value.split(',').mapNotNull { it.trim().toIntOrNull() }.filter { it in 0..1439 }.distinct()

    fun encodeWeekdays(values: Set<DayOfWeek>): String =
        values.sortedBy { it.value }.joinToString(",") { it.value.toString() }

    fun decodeWeekdays(value: String): Set<DayOfWeek> =
        value.split(',').mapNotNull { part ->
            part.trim().toIntOrNull()?.let { number ->
                runCatching { DayOfWeek.of(number) }.getOrNull()
            }
        }.toSet()

    fun encodeWeekdaysMinutes(values: List<Int>): String = encodeMinutes(values)
}
