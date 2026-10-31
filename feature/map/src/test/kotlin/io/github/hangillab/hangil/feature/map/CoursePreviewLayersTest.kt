package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.CourseMapPreview
import io.github.hangillab.hangil.core.domain.CoursePreviewPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.maplibre.geojson.Point

class CoursePreviewLayersTest {
    private val points =
        listOf(
            CoursePreviewPoint("city-hall", "서울특별시청", 37.5666904, 126.9784222, 198561926, 28),
            CoursePreviewPoint("plaza", "서울광장", 37.5656223, 126.9779906, 370019676, 8),
        )

    @Test fun `GeoJSON preserves longitude latitude order and fixed preview identity`() {
        val preview = CourseMapPreview("public-places", 1, "서울 시청 주변", points)
        val features = checkNotNull(CoursePreviewLayers.features(preview).features())
        assertEquals(2, features.size)
        features.forEachIndexed { index, feature ->
            assertTrue(feature.geometry() is Point)
            val geometry = feature.geometry() as Point
            assertEquals(points[index].longitude, geometry.longitude(), 0.0)
            assertEquals(points[index].latitude, geometry.latitude(), 0.0)
            assertEquals(points[index].id, feature.getStringProperty("point_id"))
            assertEquals(index + 1, feature.getNumberProperty("order").toInt())
            assertEquals(points[index].title, feature.getStringProperty("title"))
            assertEquals(preview.id, feature.getStringProperty("preview_id"))
            assertEquals(preview.version, feature.getNumberProperty("preview_version").toInt())
        }
    }

    @Test fun `clearing the selection emits a literal false filter without choosing a default point`() {
        val filter = CoursePreviewLayers.selectionFilter(null).toArray()
        assertTrue(arrayOf<Any>("literal", false).contentDeepEquals(filter))
        val preview = CourseMapPreview("public-places", 1, "서울 시청 주변", points)
        val features = checkNotNull(CoursePreviewLayers.features(preview).features())
        assertEquals(listOf("city-hall", "plaza"), features.map { it.getStringProperty("point_id") })
    }

    @Test fun `selection compares the exact point ID as a literal instead of title order or parsed expression`() {
        val identities = listOf("city-hall", "plaza", "literal\"[point]\n")
        for (identity in identities) {
            val filter = CoursePreviewLayers.selectionFilter(identity).toArray()
            assertEquals(3, filter.size)
            assertEquals("==", filter[0])
            assertTrue(filter[1] is Array<*>)
            assertTrue(arrayOf<Any>("get", "point_id").contentDeepEquals(filter[1] as Array<*>))
            assertEquals(identity, filter[2])
        }
    }

    @Test fun `new preview creates only its own point features without retaining previous content`() {
        CoursePreviewLayers.features(CourseMapPreview("old", 1, "이전", points))
        val next = CourseMapPreview("new", 2, "새 미리보기", points.reversed())
        val features = checkNotNull(CoursePreviewLayers.features(next).features())
        assertEquals(listOf("plaza", "city-hall"), features.map { it.getStringProperty("point_id") })
        assertTrue(features.all { it.getStringProperty("preview_id") == "new" && it.getNumberProperty("preview_version").toInt() == 2 })
    }
}
