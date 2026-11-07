package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.LineString

class RoadPreviewLayersTest {
    private val nodes =
        listOf(
            CourseRoadSnapshot.Node(11, 3, 37.2, 127.2),
            CourseRoadSnapshot.Node(12, 2, 37.1, 127.1),
            CourseRoadSnapshot.Node(13, 4, 37.3, 127.3),
        )
    private val road = CourseRoadSnapshot("road", "Fixture", "residential", 99, 7, 1000, nodes)

    @Test fun originalNodeOrderIsALineWithSeparateRoadIdentityAndProvenance() {
        val feature = checkNotNull(RoadPreviewLayers.features(listOf(road)).features()).single()
        assertTrue(feature.geometry() is LineString)
        val line = feature.geometry() as LineString
        assertEquals(nodes.map { it.longitude to it.latitude }, line.coordinates().map { it.longitude() to it.latitude() })
        assertEquals("road", feature.getStringProperty("road_id"))
        assertEquals("99", feature.getStringProperty("osm_way_id"))
        assertEquals(7, feature.getNumberProperty("osm_way_version").toInt())
        assertEquals("OSM_WAY_NODES", feature.getStringProperty("road_source"))
        assertNotEquals(CoursePreviewLayers.SOURCE, RoadPreviewLayers.SOURCE)
    }

    @Test fun roadFilterSelectsExactIdentityAndExplicitClearHasNoFallback() {
        val selected = RoadPreviewLayers.selectionFilter("road").toArray()
        assertEquals("==", selected[0])
        assertTrue(arrayOf<Any>("get", "road_id").contentDeepEquals(selected[1] as Array<*>))
        assertEquals("road", selected[2])
        assertTrue(arrayOf<Any>("literal", false).contentDeepEquals(RoadPreviewLayers.selectionFilter(null).toArray()))
    }
}
