package com.geofenceoffline.map

import com.geofenceoffline.geo.BoundingBox
import com.geofenceoffline.geo.GeoPoint
import org.json.JSONObject

/**
 * Parses the Overpass JSON response into a render model. Relations are intentionally not assembled;
 * the query includes ways, which is sufficient for the small, street-level extracts this app targets.
 * The original response is kept on disk, so parsing can be repeated or improved later.
 */
object OsmParser {
    private val retainedTags = setOf(
        "name", "highway", "railway", "waterway", "natural", "landuse", "leisure",
        "building", "amenity", "place", "boundary", "tunnel", "bridge", "layer",
        "area", "oneway", "ref", "surface", "access", "service", "admin_level",
        "population", "capital"
    )

    fun parse(json: String, sourceBytes: Long = json.toByteArray().size.toLong()): OsmMapData {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            throw MapParseException("The map data could not be read.", e)
        }
        val elements = root.optJSONArray("elements")
            ?: throw MapParseException(
                root.optString("remark").take(180).ifBlank { "The server returned no map data." }
            )

        val nodes = HashMap<Long, GeoPoint>(elements.length().coerceAtMost(200_000))
        val ways = ArrayList<OsmFeature>()
        val places = ArrayList<PlaceLabel>()
        var nodeCount = 0

        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            if (element.optString("type") != "node") continue
            val lat = element.optDouble("lat", Double.NaN)
            val lon = element.optDouble("lon", Double.NaN)
            if (!lat.isFinite() || !lon.isFinite()) continue
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            val point = GeoPoint(lat, lon)
            nodes[element.optLong("id")] = point
            nodeCount++
            val tags = element.optJSONObject("tags") ?: continue
            val place = tags.optString("place")
            val name = tags.optString("name")
            if (place.isNotBlank() && name.isNotBlank()) {
                places.add(PlaceLabel(point, name, place))
            }
        }

        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            if (element.optString("type") != "way") continue
            val rawTags = element.optJSONObject("tags") ?: continue
            val tagMap = HashMap<String, String>()
            retainedTags.forEach { key ->
                if (rawTags.has(key)) {
                    val value = rawTags.optString(key)
                    if (value.isNotBlank()) tagMap[key] = value
                }
            }
            if (tagMap.isEmpty()) continue
            val nodeIds = element.optJSONArray("nodes") ?: continue
            val points = ArrayList<GeoPoint>(nodeIds.length())
            var firstId: Long? = null
            var lastId: Long? = null
            for (j in 0 until nodeIds.length()) {
                val nodeId = nodeIds.optLong(j)
                if (firstId == null) firstId = nodeId
                lastId = nodeId
                nodes[nodeId]?.let { points.add(it) }
            }
            if (points.size < 2) continue
            val closed = nodeIds.length() >= 4 && firstId == lastId &&
                points.first() == points.last()
            val bounds = BoundingBox(
                south = points.minOf { it.lat },
                west = points.minOf { it.lon },
                north = points.maxOf { it.lat },
                east = points.maxOf { it.lon }
            )
            ways.add(OsmFeature(element.optLong("id"), points, closed, OsmTags(tagMap), bounds))
        }

        if (ways.isEmpty() && places.isEmpty()) {
            val remark = root.optString("remark")
            throw MapParseException(
                if (remark.isNotBlank()) "The map server reported: ${remark.take(160)}"
                else "No map features were found in this area. Try a slightly larger selection."
            )
        }

        return OsmMapData(
            features = ways,
            places = places.sortedBy { placeRank(it.place) },
            nodeCount = nodeCount,
            sourceBytes = sourceBytes
        )
    }

    private fun placeRank(place: String): Int = when (place) {
        "city" -> 0
        "town" -> 1
        "village" -> 2
        "suburb" -> 3
        "neighbourhood" -> 4
        "quarter" -> 5
        "locality" -> 6
        else -> 10
    }
}
