package com.geofenceoffline.map

import com.geofenceoffline.geo.BoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OsmParserTest {
    private val fixture: String by lazy {
        checkNotNull(javaClass.getResourceAsStream("/sf-fixture.json")) {
            "Missing test fixture"
        }.bufferedReader().use { it.readText() }
    }

    @Test
    fun parsesRealOverpassExtract() {
        val data = OsmParser.parse(fixture, fixture.toByteArray().size.toLong())
        assertTrue("expected several features", data.featureCount > 20)
        assertTrue("expected nodes", data.nodeCount > 100)
        assertTrue(data.features.any { it.isRoad })
        assertTrue(data.features.any { it.building != null })
        assertEquals(fixture.toByteArray().size.toLong(), data.sourceBytes)
    }

    @Test
    fun featureNodesAreAvailableForRendering() {
        val data = OsmParser.parse(fixture)
        data.features.forEach { feature ->
            assertTrue("feature ${feature.id} has fewer than 2 points", feature.points.size >= 2)
            assertNotNull(feature.bounds)
        }
    }

    @Test
    fun malformedJsonIsRejectedWithMapParseException() {
        val error = runCatching { OsmParser.parse("{not json") }.exceptionOrNull()
        assertTrue(error is MapParseException)
    }

    @Test
    fun emptyExtractIsRejected() {
        val error = runCatching {
            OsmParser.parse("""{"version":0.6,"elements":[]}""")
        }.exceptionOrNull()
        assertTrue(error is MapParseException)
        assertTrue(error?.message?.contains("No map features") == true)
    }

    @Test
    fun overpassRemarkIsSurfacedWithoutFullResponse() {
        val error = runCatching {
            OsmParser.parse("""{"elements":[],"remark":"runtime error: query timed out"}""")
        }.exceptionOrNull()
        assertTrue(error is MapParseException)
        assertTrue(error?.message?.contains("runtime error") == true)
    }

    @Test
    fun queryContainsSelectedBoundingBoxAndDependencies() {
        val bounds = BoundingBox(
            south = 37.7740,
            west = -122.4200,
            north = 37.7760,
            east = -122.4170
        )
        val query = OverpassQuery.build(bounds)
        assertTrue(query.contains("37.774000,-122.420000,37.776000,-122.417000"))
        assertTrue(query.contains("way[\"highway\"]"))
        assertTrue(query.contains("way[\"building\"]"))
        assertTrue(query.contains("node[\"place\"]"))
        // `>;` recursively emits every node referenced by the selected ways.
        assertTrue(query.contains(">;"))
        assertFalse(query.contains("http"))
    }
}
