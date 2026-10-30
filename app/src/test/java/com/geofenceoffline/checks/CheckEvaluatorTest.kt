package com.geofenceoffline.checks

import com.geofenceoffline.data.CheckReason
import com.geofenceoffline.data.CheckStatus
import com.geofenceoffline.data.Geofence
import com.geofenceoffline.data.GeofenceType
import com.geofenceoffline.data.Schedule
import com.geofenceoffline.data.ScheduleType
import com.geofenceoffline.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class CheckEvaluatorTest {
    private val center = GeoPoint(37.7749, -122.4194)
    private val square = listOf(
        GeoPoint(37.7740, -122.4200),
        GeoPoint(37.7740, -122.4180),
        GeoPoint(37.7760, -122.4180),
        GeoPoint(37.7760, -122.4200)
    )
    private val circle = Geofence(
        id = "circle",
        name = "Home",
        type = GeofenceType.CIRCLE,
        center = center,
        radiusMeters = 100.0,
        polygon = emptyList(),
        enabled = true,
        createdAt = 0,
        updatedAt = 0
    )
    private val polygon = Geofence(
        id = "polygon",
        name = "Block",
        type = GeofenceType.POLYGON,
        center = null,
        radiusMeters = null,
        polygon = square,
        enabled = true,
        createdAt = 0,
        updatedAt = 0
    )
    private val schedule = Schedule(
        id = "schedule",
        name = "Weekday checks",
        type = ScheduleType.REPEATING_WINDOW,
        timesOfDayMinutes = emptyList(),
        weekdays = setOf(DayOfWeek.MONDAY),
        windowStartMinutes = 960,
        windowEndMinutes = 1020,
        intervalMinutes = 10,
        geofenceIds = listOf("circle", "polygon"),
        enabled = true,
        createdAt = 0
    )

    @Test
    fun evaluateProducesOneBinaryResultPerGeofence() {
        val results = CheckEvaluator.evaluate(
            schedule,
            listOf(circle, polygon),
            center,
            scheduledAt = 1_000,
            executedAt = 2_000
        )
        assertEquals(2, results.size)
        assertTrue(results.all { it.status == CheckStatus.INSIDE })
        assertTrue(results.all { it.reason == CheckReason.NONE })
        assertEquals(setOf("circle", "polygon"), results.map { it.geofenceId }.toSet())
    }

    @Test
    fun pointOutsideProducesOutsideOnly() {
        val farAway = GeoPoint(37.7800, -122.4300)
        val results = CheckEvaluator.evaluate(
            schedule,
            listOf(circle, polygon),
            farAway,
            1_000,
            2_000
        )
        assertTrue(results.all { it.status == CheckStatus.OUTSIDE })
    }

    @Test
    fun unavailableNeverProducesBinaryStatus() {
        val results = CheckEvaluator.unavailable(
            schedule,
            listOf(circle, polygon),
            CheckReason.INACCURATE_FIX,
            1_000,
            2_000
        )
        assertEquals(2, results.size)
        assertTrue(results.all { it.status == CheckStatus.UNKNOWN })
        assertTrue(results.all { it.reason == CheckReason.INACCURATE_FIX })
    }

    @Test
    fun noFencesIsUnknownWithNoGeofenceReference() {
        val results = CheckEvaluator.evaluate(schedule, emptyList(), center, 1_000, 2_000)
        assertEquals(1, results.size)
        val result = results.single()
        assertEquals(CheckStatus.UNKNOWN, result.status)
        assertEquals(CheckReason.NO_GEOFENCES, result.reason)
        assertEquals(null, result.geofenceId)
    }

    @Test
    fun graceBoundaryIsDocumentedAndConsistent() {
        val grace = 15
        val base = 1_000_000L
        assertFalse(CheckEvaluator.isBeyondGrace(base, base + grace * 60_000L, grace))
        assertTrue(CheckEvaluator.isBeyondGrace(base, base + grace * 60_000L + 1, grace))
    }
}
