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

    @Test fun `new preview creates only its own point features without retaining previous content`() {
        CoursePreviewLayers.features(CourseMapPreview("old", 1, "이전", points))
        val next = CourseMapPreview("new", 2, "새 미리보기", points.reversed())
        val features = checkNotNull(CoursePreviewLayers.features(next).features())
        assertEquals(listOf("plaza", "city-hall"), features.map { it.getStringProperty("point_id") })
        assertTrue(features.all { it.getStringProperty("preview_id") == "new" && it.getNumberProperty("preview_version").toInt() == 2 })
    }
}
