package io.github.hangillab.hangil.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CourseMapPreviewTest {
    private val first = CoursePreviewPoint("first", "첫 장소", 37.5666904, 126.9784222, 198561926L, 28)
    private val second = CoursePreviewPoint("second", "둘째 장소", 37.5656223, 126.9779906, 370019676L, 8)
    private val preview = CourseMapPreview("preview", 1, "고정 시연 지점", listOf(first, second))

    @Test fun `preview preserves display order and its fixed source`() {
        assertEquals(listOf(first, second), preview.points)
        assertEquals(CoursePreviewSource.FIXED_OSM_BOUNDING_BOX_CENTERS, preview.source)
    }

    @Test fun `preview has independent identity title and positive version`() {
        listOf("", " ", "\n").forEach { blank ->
            assertThrows(IllegalArgumentException::class.java) { preview.copy(id = blank) }
            assertThrows(IllegalArgumentException::class.java) { preview.copy(title = blank) }
        }
        listOf(0, -1, Int.MIN_VALUE).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { preview.copy(version = invalid) }
        }
    }

    @Test fun `absent and single point lists do not make a supported preview`() {
        assertThrows(IllegalArgumentException::class.java) { preview.copy(points = emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { preview.copy(points = listOf(first)) }
    }

    @Test fun `duplicate point identities and duplicate coordinates are rejected`() {
        assertThrows(IllegalArgumentException::class.java) { preview.copy(points = listOf(first, second.copy(id = first.id))) }
        assertThrows(IllegalArgumentException::class.java) {
            preview.copy(points = listOf(first, second.copy(latitude = first.latitude, longitude = first.longitude)))
        }
    }

    @Test fun `signed zero cannot disguise duplicate geographic coordinates`() {
        assertThrows(IllegalArgumentException::class.java) {
            preview.copy(points = listOf(first.copy(latitude = 0.0, longitude = 0.0), second.copy(latitude = -0.0, longitude = -0.0)))
        }
    }

    @Test fun `course preview is optional and does not replace course or briefing versions`() {
        val task = PracticeTask("task", "과제", "설명")
        val original = PracticeCourse("course", 2, 3, "시연", "설명", listOf("구간"), listOf(task), setOf("goal"))
        val withPreview = original.copy(mapPreview = preview)
        assertNull(original.mapPreview)
        assertEquals(original, withPreview.copy(mapPreview = null))
        assertEquals(2, withPreview.version)
        assertEquals(3, withPreview.briefingVersion)
        assertEquals(CourseReviewStatus.DEMO, withPreview.reviewStatus)
    }
}
