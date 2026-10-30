package com.geofenceoffline.map

import com.geofenceoffline.geo.BoundingBox
import com.geofenceoffline.geo.GeoPoint

/** Render-relevant tags retained from OSM. Keeping this small bounds dataset memory use. */
data class OsmTags(val values: Map<String, String>) {
    operator fun get(key: String): String? = values[key]
    fun has(key: String): Boolean = values.containsKey(key)
}

data class OsmFeature(
    val id: Long,
    val points: List<GeoPoint>,
    val closed: Boolean,
    val tags: OsmTags,
    val bounds: BoundingBox
) {
    val name: String? get() = tags["name"]
    val highway: String? get() = tags["highway"]
    val building: String? get() = tags["building"]
    val waterway: String? get() = tags["waterway"]
    val railway: String? get() = tags["railway"]
    val natural: String? get() = tags["natural"]
    val landuse: String? get() = tags["landuse"]
    val leisure: String? get() = tags["leisure"]
    val amenity: String? get() = tags["amenity"]

    val isArea: Boolean
        get() = closed && (building != null || natural != null || landuse != null ||
            leisure != null || waterway == "riverbank" || amenity != null || tags.has("area"))

    val isRoad: Boolean
        get() = highway != null && highway != "footway" && highway != "path" &&
            highway != "steps" && highway != "cycleway" && highway != "bridleway" &&
            highway != "corridor" && highway != "elevator"

    val isPath: Boolean
        get() = highway != null && !isRoad

    val isRail: Boolean get() = railway != null
    val isWaterLine: Boolean get() = waterway != null && !isArea
}

data class PlaceLabel(val point: GeoPoint, val name: String, val place: String)

data class OsmMapData(
    val features: List<OsmFeature>,
    val places: List<PlaceLabel>,
    val nodeCount: Int,
    val sourceBytes: Long
) {
    val featureCount: Int get() = features.size
}

class MapParseException(message: String, cause: Throwable? = null) : Exception(message, cause)
class OverpassException(message: String, val retryable: Boolean = false, cause: Throwable? = null) :
    Exception(message, cause)

object OverpassQuery {
    private val wayTags = listOf(
        "highway", "railway", "waterway", "natural", "landuse",
        "leisure", "building", "amenity", "place", "boundary"
    )

    fun build(bounds: BoundingBox): String {
        val bbox = bounds.overpassFragment()
        val clauses = wayTags.joinToString("\n") { "  way[\"$it\"]($bbox);" }
        return """
            [out:json][timeout:90];
            (
            $clauses
              node["place"]($bbox);
            );
            out body;
            >;
            out skel qt;
        """.trimIndent()
    }

    /** A lighter query for the on-screen picker: roads and context, but no building polygons. */
    fun buildPreview(bounds: BoundingBox): String {
        val bbox = bounds.overpassFragment()
        val keys = listOf("highway", "railway", "waterway", "natural", "landuse", "leisure", "place")
        val clauses = keys.joinToString("\n") { "  way[\"$it\"]($bbox);" }
        return """
            [out:json][timeout:60];
            (
            $clauses
              node["place"]($bbox);
            );
            out body;
            >;
            out skel qt;
        """.trimIndent()
    }
}
