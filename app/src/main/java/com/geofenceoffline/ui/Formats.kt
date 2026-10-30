package com.geofenceoffline.ui

import com.geofenceoffline.data.CheckStatus
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

object Formats {
    private val dateTime = DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a", Locale.getDefault())
    private val timeOnly = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
    private val dateOnly = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.getDefault())
    private val weekdayDate = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())

    fun dateTime(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateTime)

    fun time(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(timeOnly)

    fun date(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(dateOnly)

    fun weekdayDate(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).format(weekdayDate)

    fun bytes(bytes: Long): String = when {
        bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }

    fun area(km2: Double): String = when {
        km2 >= 1.0 -> String.format(Locale.US, "%.2f km²", km2)
        km2 >= 0.01 -> String.format(Locale.US, "%.1f ha", km2 * 100.0)
        else -> String.format(Locale.US, "%.0f m²", km2 * 1_000_000.0)
    }

    fun statusLabel(status: CheckStatus): String = when (status) {
        CheckStatus.INSIDE -> "INSIDE"
        CheckStatus.OUTSIDE -> "OUTSIDE"
        CheckStatus.UNKNOWN -> "UNKNOWN"
    }

    fun countLabel(count: Int, singular: String, plural: String = singular + "s"): String =
        if (count == 1) "1 $singular" else "$count $plural"
}
