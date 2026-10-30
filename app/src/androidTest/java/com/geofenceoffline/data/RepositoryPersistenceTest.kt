package com.geofenceoffline.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.geofenceoffline.geo.GeoPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek

@RunWith(AndroidJUnit4::class)
class RepositoryPersistenceTest {
    private lateinit var context: android.content.Context
    private lateinit var maps: MapRepository
    private lateinit var geofences: GeofenceRepository
    private lateinit var schedules: ScheduleRepository
    private lateinit var history: CheckHistoryRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        maps = MapRepository(context)
        geofences = GeofenceRepository(context)
        schedules = ScheduleRepository(context)
        history = CheckHistoryRepository(context)
        clearAll()
    }

    @After
    fun tearDown() {
        clearAll()
    }

    private fun clearAll() {
        history.deleteAll()
        schedules.listAll().forEach { schedules.delete(it.id) }
        geofences.listAll().forEach { geofences.delete(it.id) }
        maps.deleteAll()
    }

    private fun fence(id: String = "fence-1", enabled: Boolean = true) = Geofence(
        id = id,
        name = "Home",
        type = GeofenceType.POLYGON,
        center = null,
        radiusMeters = null,
        polygon = listOf(
            GeoPoint(37.7740, -122.4200),
            GeoPoint(37.7740, -122.4180),
            GeoPoint(37.7760, -122.4180),
            GeoPoint(37.7760, -122.4200)
        ),
        enabled = enabled,
        createdAt = 1,
        updatedAt = 1
    )

    private fun schedule(id: String = "schedule-1", fenceIds: List<String> = listOf("fence-1")) = Schedule(
        id = id,
        name = "Weekday checks",
        type = ScheduleType.REPEATING_WINDOW,
        timesOfDayMinutes = emptyList(),
        weekdays = setOf(DayOfWeek.MONDAY),
        windowStartMinutes = 960,
        windowEndMinutes = 1020,
        intervalMinutes = 10,
        geofenceIds = fenceIds,
        enabled = true,
        createdAt = 1
    )

    @Test
    fun geofencesSurviveReopenAndCanBeDisabled() {
        geofences.save(fence())
        assertEquals(1, GeofenceRepository(context).listAll().size)
        geofences.setEnabled("fence-1", false)
        assertFalse(GeofenceRepository(context).get("fence-1")!!.enabled)
        assertEquals(0, GeofenceRepository(context).listEnabled().size)
    }

    @Test
    fun schedulesPersistLinkedFenceIds() {
        geofences.save(fence())
        geofences.save(fence("fence-2"))
        schedules.save(schedule(fenceIds = listOf("fence-1", "fence-2")))
        val restored = ScheduleRepository(context).get("schedule-1")!!
        assertEquals(setOf("fence-1", "fence-2"), restored.geofenceIds.toSet())
        assertTrue(restored.enabled)
    }

    @Test
    fun deletingGeofenceRemovesScheduleLinkButKeepsSchedule() {
        geofences.save(fence())
        schedules.save(schedule())
        geofences.delete("fence-1")
        val restored = ScheduleRepository(context).get("schedule-1")
        assertEquals(emptyList<String>(), restored!!.geofenceIds)
    }

    @Test
    fun duplicateOccurrenceIsInsertedOnlyOnce() {
        geofences.save(fence())
        schedules.save(schedule())
        val result = CheckResult(
            scheduleId = "schedule-1",
            scheduleName = "Weekday checks",
            geofenceId = "fence-1",
            geofenceName = "Home",
            scheduledAt = 1_000_000,
            executedAt = 1_000_500,
            status = CheckStatus.INSIDE,
            reason = CheckReason.NONE
        )
        assertEquals(1, history.insertAll(listOf(result)))
        assertEquals(0, history.insertAll(listOf(result)))
        assertEquals(1, history.count())
        assertTrue(history.hasOccurrence("schedule-1", 1_000_000))
        assertFalse(history.hasOccurrence("schedule-1", 1_000_001))
    }

    @Test
    fun differentGeofencesForOneOccurrenceAreSeparateRows() {
        val base = CheckResult(
            scheduleId = "schedule-1",
            scheduleName = "Weekday checks",
            geofenceId = "fence-1",
            geofenceName = "Home",
            scheduledAt = 2_000_000,
            executedAt = 2_000_500,
            status = CheckStatus.INSIDE,
            reason = CheckReason.NONE
        )
        val other = base.copy(geofenceId = "fence-2", geofenceName = "Work", status = CheckStatus.OUTSIDE)
        assertEquals(2, history.insertAll(listOf(base, other)))
        assertEquals(2, history.count())
    }

    @Test
    fun checkResultsSchemaCannotStoreRawLocationFields() {
        val cursor = AppDatabase.get(context).readableDatabase.rawQuery(
            "PRAGMA table_info(check_results)", null
        )
        val columns = buildSet {
            cursor.use {
                while (it.moveToNext()) add(it.getString(it.getColumnIndexOrThrow("name")))
            }
        }
        listOf("lat", "latitude", "lon", "longitude", "accuracy", "provider", "altitude").forEach {
            assertFalse("unexpected column $it", columns.contains(it))
        }
        assertTrue(columns.containsAll(setOf("scheduled_at", "executed_at", "status", "reason")))
    }

    @Test
    fun historyFilterAndDeleteWork() {
        val base = CheckResult(
            scheduleId = "schedule-1",
            scheduleName = "Weekday checks",
            geofenceId = "fence-1",
            geofenceName = "Home",
            scheduledAt = 3_000_000,
            executedAt = 3_000_500,
            status = CheckStatus.UNKNOWN,
            reason = CheckReason.NO_FIX
        )
        history.insertAll(listOf(base, base.copy(scheduledAt = 3_100_000, status = CheckStatus.OUTSIDE, reason = CheckReason.NONE)))
        assertEquals(1, history.list(CheckStatus.UNKNOWN).size)
        assertEquals(1, history.list(CheckStatus.OUTSIDE).size)
        assertNotNull(history.get(history.list().first().id))
        history.delete(history.list().first().id)
        assertEquals(1, history.count())
    }
}
