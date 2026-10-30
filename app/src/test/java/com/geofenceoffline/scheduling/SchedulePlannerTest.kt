package com.geofenceoffline.scheduling

import com.geofenceoffline.data.Schedule
import com.geofenceoffline.data.ScheduleType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class SchedulePlannerTest {
    private val utc = ZoneId.of("UTC")
    private val monday = LocalDate.of(2026, 10, 26)

    private fun schedule(
        type: ScheduleType = ScheduleType.REPEATING_WINDOW,
        times: List<Int> = emptyList(),
        weekdays: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY),
        start: Int = 16 * 60,
        end: Int = 17 * 60,
        interval: Int = 10,
        enabled: Boolean = true
    ) = Schedule(
        id = "schedule-1",
        name = "Test schedule",
        type = type,
        timesOfDayMinutes = times,
        weekdays = weekdays,
        windowStartMinutes = start,
        windowEndMinutes = end,
        intervalMinutes = interval,
        geofenceIds = listOf("fence-1"),
        enabled = enabled,
        createdAt = 0L
    )

    private fun minuteOfDay(epochMillis: Long, zone: ZoneId = utc): Int =
        Instant.ofEpochMilli(epochMillis).atZone(zone).let { it.hour * 60 + it.minute }

    private fun dateOf(epochMillis: Long, zone: ZoneId = utc): LocalDate =
        Instant.ofEpochMilli(epochMillis).atZone(zone).toLocalDate()

    @Test
    fun windowIncludesBothStartAndAlignedEnd() {
        val minutes = SchedulePlanner.windowMinutes(
            schedule(start = 16 * 60, end = 17 * 60, interval = 10)
        )
        assertEquals(
            listOf(960, 970, 980, 990, 1000, 1010, 1020),
            minutes
        )
    }

    @Test
    fun windowStopsAtLastAlignedTimeBeforeUnAlignedEnd() {
        val minutes = SchedulePlanner.windowMinutes(
            schedule(start = 16 * 60, end = 17 * 60 + 5, interval = 10)
        )
        assertEquals(960, minutes.first())
        assertEquals(1020, minutes.last())
        assertFalse(minutes.contains(1030))
    }

    @Test
    fun occurrencesRespectSelectedWeekday() {
        val occurrences = SchedulePlanner.occurrencesOnDate(
            schedule(weekdays = setOf(DayOfWeek.MONDAY)), monday, utc
        )
        assertEquals(7, occurrences.size)
        assertTrue(
            SchedulePlanner.occurrencesOnDate(
                schedule(weekdays = setOf(DayOfWeek.TUESDAY)), monday, utc
            ).isEmpty()
        )
    }

    @Test
    fun specificTimesAreDeduplicatedAndSorted() {
        val times = SchedulePlanner.occurrencesOnDate(
            schedule(
                type = ScheduleType.SPECIFIC_TIMES,
                times = listOf(16 * 60 + 30, 9 * 60, 16 * 60 + 30)
            ),
            monday,
            utc
        )
        assertEquals(listOf(9 * 60, 16 * 60 + 30), times.map { minuteOfDay(it) })
    }

    @Test
    fun nextOccurrenceIsStrictlyAfterTheGivenInstant() {
        val after = ZonedDateTime.of(monday, java.time.LocalTime.of(16, 5), utc)
            .toInstant().toEpochMilli()
        val next = SchedulePlanner.nextOccurrence(schedule(), after, utc)
        assertNotNull(next)
        assertEquals(16 * 60 + 10, minuteOfDay(next!!))
        assertEquals(monday, dateOf(next))
    }

    @Test
    fun nextOccurrenceRollsToTheNextSelectedWeek() {
        val after = ZonedDateTime.of(monday, java.time.LocalTime.of(18, 0), utc)
            .toInstant().toEpochMilli()
        val next = SchedulePlanner.nextOccurrence(schedule(), after, utc)
        assertNotNull(next)
        assertEquals(monday.plusWeeks(1), dateOf(next!!))
        assertEquals(16 * 60, minuteOfDay(next))
    }

    @Test
    fun disabledOrInvalidScheduleHasNoNextOccurrence() {
        assertNull(
            SchedulePlanner.nextOccurrence(
                schedule(enabled = false), 0L, utc
            )
        )
        assertNull(
            SchedulePlanner.nextOccurrence(
                schedule(interval = 0), 0L, utc
            )
        )
        assertNull(
            SchedulePlanner.nextOccurrence(
                schedule(weekdays = emptySet()), 0L, utc
            )
        )
    }

    @Test
    fun occurrencesBetweenIsInclusiveAndSorted() {
        val from = ZonedDateTime.of(monday, java.time.LocalTime.of(16, 10), utc)
            .toInstant().toEpochMilli()
        val to = ZonedDateTime.of(monday, java.time.LocalTime.of(16, 30), utc)
            .toInstant().toEpochMilli()
        val occurrences = SchedulePlanner.occurrencesBetween(schedule(), from, to, utc)
        assertEquals(listOf(970, 980, 990), occurrences.map { minuteOfDay(it) })
    }

    @Test
    fun validationRejectsIntervalLongerThanWindowAndEmptyTimes() {
        assertNotNull(
            SchedulePlanner.validate(
                schedule(interval = 61)
            )
        )
        assertNotNull(
            SchedulePlanner.validate(
                schedule(type = ScheduleType.SPECIFIC_TIMES, times = emptyList())
            )
        )
    }

    @Test
    fun localDstGapDoesNotProduceDuplicateOccurrences() {
        val zone = ZoneId.of("America/New_York")
        val springForward = LocalDate.of(2026, 3, 8)
        val occurrences = SchedulePlanner.occurrencesOnDate(
            schedule(
                type = ScheduleType.SPECIFIC_TIMES,
                times = listOf(2 * 60, 2 * 60 + 30, 3 * 60),
                weekdays = setOf(DayOfWeek.SUNDAY)
            ),
            springForward,
            zone
        )
        assertEquals("no duplicate instant for a nonexistent local time", 2, occurrences.size)
    }
}
