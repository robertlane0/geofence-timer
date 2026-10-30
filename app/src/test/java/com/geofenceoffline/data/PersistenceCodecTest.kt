package com.geofenceoffline.data

import com.geofenceoffline.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek

class PersistenceCodecTest {
    @Test
    fun polygonRoundTripsAtSevenDecimalPlaces() {
        val points = listOf(
            GeoPoint(37.7749001, -122.4194002),
            GeoPoint(37.7750003, -122.4190004),
            GeoPoint(37.7755005, -122.4185006)
        )
        val decoded = PersistenceCodec.decodePolygon(PersistenceCodec.encodePolygon(points))
        assertEquals(points.size, decoded.size)
        points.zip(decoded).forEach { (original, restored) ->
            assertEquals(original.lat, restored.lat, 1e-7)
            assertEquals(original.lon, restored.lon, 1e-7)
        }
    }

    @Test
    fun invalidPolygonPairsAreIgnoredButValidOnesRemain() {
        val decoded = PersistenceCodec.decodePolygon(
            "37.0,-122.0;broken;91.0,0.0;38.0,-121.0;1,2,3"
        )
        assertEquals(2, decoded.size)
        assertEquals(GeoPoint(38.0, -121.0), decoded.last())
    }

    @Test
    fun minutesAreDeduplicatedSortedAndFiltered() {
        assertEquals("540,960", PersistenceCodec.encodeMinutes(listOf(960, 540, 960)))
        assertEquals(
            listOf(9 * 60, 16 * 60),
            PersistenceCodec.decodeMinutes("540,960,1440,-1,garbage")
        )
    }

    @Test
    fun weekdaysRoundTrip() {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY, DayOfWeek.SUNDAY)
        val encoded = PersistenceCodec.encodeWeekdays(days)
        assertEquals("1,5,7", encoded)
        assertEquals(days, PersistenceCodec.decodeWeekdays(encoded))
    }

    @Test
    fun malformedWeekdaysAreDropped() {
        assertTrue(PersistenceCodec.decodeWeekdays("1,9,x,7").containsAll(setOf(DayOfWeek.MONDAY, DayOfWeek.SUNDAY)))
        assertEquals(2, PersistenceCodec.decodeWeekdays("1,9,x,7").size)
    }
}
