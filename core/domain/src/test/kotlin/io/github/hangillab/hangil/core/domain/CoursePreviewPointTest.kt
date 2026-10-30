package io.github.hangillab.hangil.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CoursePreviewPointTest {
    private val point = CoursePreviewPoint("city-hall", "공개 장소", 37.5666904, 126.9784222, 198561926L, 28)

    @Test fun `frozen official source identifies the exact way version`() {
        assertEquals("https://api.openstreetmap.org/api/0.6/way/198561926/28.json", point.sourceUrl)
        assertEquals("https://api.openstreetmap.org/api/0.6/way/198561926/29.json", point.copy(osmVersion = 29).sourceUrl)
    }

    @Test fun `coordinate boundaries are accepted without changing the input`() {
        listOf(-90.0 to -180.0, 90.0 to 180.0, 0.0 to 0.0).forEach { (latitude, longitude) ->
            val boundary = point.copy(latitude = latitude, longitude = longitude)
            assertEquals(latitude, boundary.latitude, 0.0)
            assertEquals(longitude, boundary.longitude, 0.0)
        }
    }

    @Test fun `blank point identity and title are rejected`() {
        listOf("", " ", "\n").forEach { blank ->
            assertThrows(IllegalArgumentException::class.java) { point.copy(id = blank) }
            assertThrows(IllegalArgumentException::class.java) { point.copy(title = blank) }
        }
    }

    @Test fun `nonfinite and out of range coordinates are rejected rather than normalized`() {
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -90.00001, 90.00001).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { point.copy(latitude = invalid) }
        }
        listOf(Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, -180.00001, 180.00001).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { point.copy(longitude = invalid) }
        }
    }

    @Test fun `way identity and version must be positive`() {
        listOf(0L, -1L, Long.MIN_VALUE).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { point.copy(osmWayId = invalid) }
        }
        listOf(0, -1, Int.MIN_VALUE).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { point.copy(osmVersion = invalid) }
        }
    }
}
