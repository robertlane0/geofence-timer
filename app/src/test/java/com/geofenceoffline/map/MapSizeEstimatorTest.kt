package com.geofenceoffline.map

import com.geofenceoffline.geo.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapSizeEstimatorTest {
    private val oneKm = BoundingBox(37.7700, -122.4200, 37.7790, -122.4083)

    @Test
    fun heuristicIsClampedForTinyAndHugeSelections() {
        val tiny = BoundingBox(37.7749, -122.4194, 37.7749001, -122.4193999)
        assertEquals(20_000L, MapSizeEstimator.estimateBytes(tiny))
        val huge = BoundingBox(37.0, -123.0, 38.0, -122.0)
        assertEquals(29_500_000L, MapSizeEstimator.estimateBytes(huge))
    }

    @Test
    fun previewScalingTracksSelectedArea() {
        val previewBounds = BoundingBox(37.7600, -122.4400, 37.7900, -122.4000)
        val previewBytes = 2_000_000L
        val halfTheArea = oneKm.copy(
            north = (oneKm.south + oneKm.north) / 2.0
        )
        val full = MapSizeEstimator.estimateBytes(oneKm, previewBytes, previewBounds)
        val half = MapSizeEstimator.estimateBytes(halfTheArea, previewBytes, previewBounds)
        assertTrue(half < full)
        assertTrue(full <= 29_500_000L)
    }

    @Test
    fun onlyBuildsWhenInsideLimits() {
        assertTrue(!MapLimits.exceeds(oneKm))
        val tooWide = BoundingBox(37.0, -122.2, 37.01, -122.0)
        assertTrue(MapLimits.exceeds(tooWide))
        val tooLarge = BoundingBox(36.0, -123.0, 37.0, -122.0)
        assertTrue(MapLimits.exceeds(tooLarge))
    }
}
