package com.geofenceoffline.geo

import java.util.Locale
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** A WGS84 coordinate. Coordinates of geofences and downloaded map bounds are stored; device fixes are not. */
data class GeoPoint(val lat: Double, val lon: Double) {
    init {
        require(lat.isFinite() && lat in -90.0..90.0) { "Latitude out of range" }
        require(lon.isFinite() && lon in -180.0..180.0) { "Longitude out of range" }
    }

    fun formatted(): String = String.format(Locale.US, "%.6f, %.6f", lat, lon)
}

/** A north-up geographic bounding box. */
data class BoundingBox(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double
) {
    init {
        require(south.isFinite() && north.isFinite() && west.isFinite() && east.isFinite()) {
            "Bounding box coordinates must be finite"
        }
        require(south in -90.0..90.0 && north in -90.0..90.0) { "Latitude out of range" }
        require(west in -180.0..180.0 && east in -180.0..180.0) { "Longitude out of range" }
        require(south <= north) { "South must not exceed north" }
        require(west <= east) { "West must not exceed east" }
    }

    val center: GeoPoint
        get() = GeoPoint((south + north) / 2.0, (west + east) / 2.0)

    val widthMeters: Double
        get() {
            val midLat = Math.toRadians(center.lat)
            return (east - west) * 111_320.0 * abs(cos(midLat))
        }

    val heightMeters: Double
        get() = (north - south) * 110_574.0

    /** Approximate area in square kilometres, adequate for UI and size estimates. */
    val areaKm2: Double
        get() = (widthMeters / 1000.0) * (heightMeters / 1000.0)

    fun contains(point: GeoPoint): Boolean =
        point.lat in south..north && point.lon in west..east

    fun intersects(other: BoundingBox): Boolean =
        south <= other.north && north >= other.south && west <= other.east && east >= other.west

    fun expandedBy(meters: Double): BoundingBox {
        val latDelta = meters / 110_574.0
        val lonDelta = meters / (111_320.0 * max(0.01, abs(cos(Math.toRadians(center.lat)))))
        return BoundingBox(
            max(-90.0, south - latDelta),
            max(-180.0, west - lonDelta),
            min(90.0, north + latDelta),
            min(180.0, east + lonDelta)
        )
    }

    /** Overpass bbox order: south,west,north,east. */
    fun overpassFragment(): String = String.format(
        Locale.US, "%.6f,%.6f,%.6f,%.6f", south, west, north, east
    )

    fun formatted(): String =
        String.format(Locale.US, "%.5f, %.5f to %.5f, %.5f", south, west, north, east)
}

object GeoMath {
    const val EARTH_RADIUS_METERS = 6_371_008.8

    /** Points within this distance of a boundary count as INSIDE, avoiding unstable boundary results. */
    const val BOUNDARY_TOLERANCE_METERS = 0.5

    fun haversineMeters(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_METERS * asin(min(1.0, sqrt(h)))
    }

    /** Geodesic destination point, used to draw circles as true-radius polygons. */
    fun destination(start: GeoPoint, bearingDegrees: Double, distanceMeters: Double): GeoPoint {
        val bearing = Math.toRadians(bearingDegrees)
        val lat1 = Math.toRadians(start.lat)
        val lon1 = Math.toRadians(start.lon)
        val angular = distanceMeters / EARTH_RADIUS_METERS
        val lat2 = asin(
            sin(lat1) * cos(angular) + cos(lat1) * sin(angular) * cos(bearing)
        )
        val lon2 = lon1 + atan2(
            sin(bearing) * sin(angular) * cos(lat1),
            cos(angular) - sin(lat1) * sin(lat2)
        )
        val lonNorm = ((Math.toDegrees(lon2) + 540.0) % 360.0) - 180.0
        return GeoPoint(Math.toDegrees(lat2), lonNorm)
    }

    fun circlePolygon(center: GeoPoint, radiusMeters: Double, segments: Int = 96): List<GeoPoint> =
        (0 until segments).map { i ->
            destination(center, i * 360.0 / segments, radiusMeters)
        }

    fun pointInCircle(center: GeoPoint, radiusMeters: Double, point: GeoPoint): Boolean =
        haversineMeters(center, point) <= radiusMeters + BOUNDARY_TOLERANCE_METERS

    /**
     * Even-odd ray casting in lon/lat space. Points exactly on, or within a small tolerance of,
     * an edge are treated as inside. Coordinates are localised to metres for the tolerance test.
     */
    fun pointInPolygon(polygon: List<GeoPoint>, point: GeoPoint): Boolean {
        if (polygon.size < 3) return false
        if (isOnPolygonBoundary(polygon, point)) return true

        var inside = false
        var j = polygon.lastIndex
        for (i in polygon.indices) {
            val pi = polygon[i]
            val pj = polygon[j]
            val intersects = (pi.lat > point.lat) != (pj.lat > point.lat) &&
                point.lon < (pj.lon - pi.lon) * (point.lat - pi.lat) / (pj.lat - pi.lat) + pi.lon
            if (intersects) inside = !inside
            j = i
        }
        return inside
    }

    fun isOnPolygonBoundary(polygon: List<GeoPoint>, point: GeoPoint): Boolean {
        if (polygon.size < 2) return false
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            if (distanceToSegmentMeters(point, a, b) <= BOUNDARY_TOLERANCE_METERS) return true
        }
        return false
    }

    fun distanceToSegmentMeters(p: GeoPoint, a: GeoPoint, b: GeoPoint): Double {
        val metersPerDegLat = 110_574.0
        val metersPerDegLon = 111_320.0 * cos(Math.toRadians(p.lat))
        val ax = (a.lon - p.lon) * metersPerDegLon
        val ay = (a.lat - p.lat) * metersPerDegLat
        val bx = (b.lon - p.lon) * metersPerDegLon
        val by = (b.lat - p.lat) * metersPerDegLat
        val dx = bx - ax
        val dy = by - ay
        val lengthSquared = dx * dx + dy * dy
        if (lengthSquared <= 1e-12) return sqrt(ax * ax + ay * ay)
        val t = ((-ax * dx) + (-ay * dy)) / lengthSquared
        val clamped = t.coerceIn(0.0, 1.0)
        val cx = ax + clamped * dx
        val cy = ay + clamped * dy
        return sqrt(cx * cx + cy * cy)
    }

    /** Returns true when no pair of non-adjacent edges crosses. */
    fun isSimplePolygon(polygon: List<GeoPoint>): Boolean {
        if (polygon.size < 3) return false
        val n = polygon.size
        for (i in 0 until n) {
            val a1 = polygon[i]
            val a2 = polygon[(i + 1) % n]
            for (j in i + 1 until n) {
                if (j == i || (j + 1) % n == i || j == (i + 1) % n) continue
                val b1 = polygon[j]
                val b2 = polygon[(j + 1) % n]
                if (segmentsIntersect(a1, a2, b1, b2)) return false
            }
        }
        return true
    }

    private fun segmentsIntersect(a: GeoPoint, b: GeoPoint, c: GeoPoint, d: GeoPoint): Boolean {
        val o1 = orientation(a, b, c)
        val o2 = orientation(a, b, d)
        val o3 = orientation(c, d, a)
        val o4 = orientation(c, d, b)
        if (oppositeSigns(o1, o2) && oppositeSigns(o3, o4)) return true
        // Non-adjacent edges that touch or overlap also make a polygon self-intersecting.
        return (isZero(o1) && onSegment(a, b, c)) ||
            (isZero(o2) && onSegment(a, b, d)) ||
            (isZero(o3) && onSegment(c, d, a)) ||
            (isZero(o4) && onSegment(c, d, b))
    }

    private fun orientation(a: GeoPoint, b: GeoPoint, c: GeoPoint): Double =
        (b.lon - a.lon) * (c.lat - a.lat) - (b.lat - a.lat) * (c.lon - a.lon)

    private fun isZero(value: Double): Boolean = abs(value) <= 1e-14

    private fun oppositeSigns(a: Double, b: Double): Boolean =
        (a > 1e-14 && b < -1e-14) || (a < -1e-14 && b > 1e-14)

    private fun onSegment(a: GeoPoint, b: GeoPoint, p: GeoPoint): Boolean =
        p.lon in min(a.lon, b.lon)..max(a.lon, b.lon) &&
            p.lat in min(a.lat, b.lat)..max(a.lat, b.lat)

    fun polygonAreaKm2(polygon: List<GeoPoint>): Double {
        if (polygon.size < 3) return 0.0
        val meanLat = polygon.sumOf { it.lat } / polygon.size
        val metersPerDegLat = 110_574.0
        val metersPerDegLon = 111_320.0 * cos(Math.toRadians(meanLat))
        var sum = 0.0
        for (i in polygon.indices) {
            val a = polygon[i]
            val b = polygon[(i + 1) % polygon.size]
            val ax = a.lon * metersPerDegLon
            val ay = a.lat * metersPerDegLat
            val bx = b.lon * metersPerDegLon
            val by = b.lat * metersPerDegLat
            sum += ax * by - bx * ay
        }
        return abs(sum) / 2.0 / 1_000_000.0
    }

    fun clampLatitude(lat: Double): Double = lat.coerceIn(-85.05112878, 85.05112878)

    fun clampLongitude(lon: Double): Double {
        var value = lon
        while (value < -180.0) value += 360.0
        while (value > 180.0) value -= 360.0
        return value
    }

    /** Approximate number of tile zoom levels spanned, for UI hints. */
    fun metersPerPixel(lat: Double, zoom: Double): Double =
        156_543.03392 * cos(Math.toRadians(lat)) / Math.pow(2.0, zoom)

    fun requiredZoomForWidth(widthMeters: Double, pixels: Int): Double {
        if (widthMeters <= 0 || pixels <= 0) return 15.0
        var zoom = 20.0
        val target = widthMeters / pixels
        while (zoom > 1 && metersPerPixel(0.0, zoom) < target) zoom -= 0.25
        return zoom
    }

    fun ceilTo(value: Double, step: Double): Double = ceil(value / step) * step
}
