package com.geofenceoffline.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    private val square = listOf(
        GeoPoint(0.0000, 0.0000),
        GeoPoint(0.0000, 0.0010),
        GeoPoint(0.0010, 0.0010),
        GeoPoint(0.0010, 0.0000)
    )

    @Test
    fun haversineMatchesOneDegreeOfLatitude() {
        val distance = GeoMath.haversineMeters(
            GeoPoint(37.0, -122.0),
            GeoPoint(38.0, -122.0)
        )
        assertTrue("distance was $distance", distance in 111_000.0..111_400.0)
    }

    @Test
    fun circleMembershipHandlesInsideOutsideAndBoundary() {
        val center = GeoPoint(37.7749, -122.4194)
        val radius = 120.0
        assertTrue(GeoMath.pointInCircle(center, radius, center))
        assertTrue(
            GeoMath.pointInCircle(
                center,
                radius,
                GeoMath.destination(center, 90.0, 60.0)
            )
        )
        assertFalse(
            GeoMath.pointInCircle(
                center,
                radius,
                GeoMath.destination(center, 45.0, 500.0)
            )
        )
        // A point on the mathematical boundary is inside because of the documented 0.5 m tolerance.
        assertTrue(
            GeoMath.pointInCircle(
                center,
                radius,
                GeoMath.destination(center, 180.0, radius)
            )
        )
    }

    @Test
    fun polygonMembershipHandlesInsideOutsideAndBoundary() {
        assertTrue(GeoMath.pointInPolygon(square, GeoPoint(0.0005, 0.0005)))
        assertFalse(GeoMath.pointInPolygon(square, GeoPoint(0.0020, 0.0005)))
        assertFalse(GeoMath.pointInPolygon(square, GeoPoint(0.0005, -0.0005)))
        assertTrue(GeoMath.pointInPolygon(square, GeoPoint(0.0, 0.0005)))
        // Roughly 0.1 m outside the southern edge, within the 0.5 m tolerance.
        assertTrue(GeoMath.pointInPolygon(square, GeoPoint(-0.000001, 0.0005)))
        // Roughly 11 m outside must be OUTSIDE.
        assertFalse(GeoMath.pointInPolygon(square, GeoPoint(-0.0001, 0.0005)))
    }

    @Test
    fun simplePolygonRejectsCrossingEdgesAndAcceptsSquare() {
        assertTrue(GeoMath.isSimplePolygon(square))
        val bowTie = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(1.0, 1.0),
            GeoPoint(1.0, 0.0),
            GeoPoint(0.0, 1.0)
        )
        assertFalse(GeoMath.isSimplePolygon(bowTie))
    }

    @Test
    fun polygonAreaIsApproximatelyCorrect() {
        // 0.001 degrees of latitude is about 110.6 m; of longitude at the equator about 111.3 m.
        val area = GeoMath.polygonAreaKm2(square)
        assertTrue("area was $area", area in 0.0120..0.0126)
    }

    @Test
    fun destinationWrapsLongitudeAndKeepsLatitudeValid() {
        val point = GeoMath.destination(GeoPoint(0.0, 179.999), 90.0, 1_000.0)
        assertTrue(point.lon in -180.0..180.0)
        assertTrue(point.lat in -90.0..90.0)
    }
}
