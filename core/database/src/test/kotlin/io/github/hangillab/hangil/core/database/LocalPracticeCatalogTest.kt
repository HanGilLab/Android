package io.github.hangillab.hangil.core.database

import io.github.hangillab.hangil.core.domain.CoursePreviewPoint
import io.github.hangillab.hangil.core.domain.CoursePreviewSource
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.Preparation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalPracticeCatalogTest {
    @Test fun `all shipped demo choices have a resolvable version and task`() {
        val catalog = LocalPracticeCatalog()
        assertEquals(2, catalog.goals.size)
        assertEquals(2, catalog.courses.size)
        assertEquals(
            catalog.courses.size,
            catalog.courses
                .map { it.id }
                .distinct()
                .size,
        )
        catalog.goals.forEach { goal ->
            val courses = catalog.courses.filter { goal.id in it.goalIds }
            assertTrue(courses.isNotEmpty())
            courses.forEach { course ->
                assertEquals(CourseReviewStatus.DEMO, course.reviewStatus)
                assertTrue(course.tasks.isNotEmpty())
                assertEquals(
                    course.tasks.size,
                    course.tasks
                        .map { it.id }
                        .distinct()
                        .size,
                )
                course.tasks.forEach { task ->
                    assertTrue(
                        catalog.resolve(
                            PracticeSelection(goal.id, course.id, course.version, course.briefingVersion, task.id),
                        ) is Preparation.Ready,
                    )
                }
            }
        }
    }

    @Test fun `current landmark previews preserve existing preparation identities and versions`() {
        val courses = LocalPracticeCatalog().courses
        assertEquals(listOf("neighborhood", "intersections"), courses.map { it.id })
        assertEquals(listOf("생활권 한 바퀴", "교차로 흐름 익히기"), courses.map { it.title })
        assertEquals(listOf(1, 1), courses.map { it.version })
        assertEquals(listOf(1, 1), courses.map { it.briefingVersion })
        assertEquals(listOf(setOf("commute", "shopping"), setOf("commute", "shopping")), courses.map { it.goalIds })
        assertEquals(listOf(listOf("sequence", "arrival"), listOf("scene", "review")), courses.map { course -> course.tasks.map { it.id } })
        assertEquals(
            listOf(
                listOf("출발 준비", "동네 길 살펴보기", "목적지 도착", "돌아와 주차"),
                listOf("출발 준비", "교차로가 있는 구간", "목적지 접근", "도착 후 정리"),
            ),
            courses.map { it.sections },
        )
    }

    @Test fun `each shipped course has its own ordered fixed landmark preview`() {
        val previews = LocalPracticeCatalog().courses.map { requireNotNull(it.mapPreview) }
        assertEquals(listOf("seoul-city-hall-landmarks", "gwanghwamun-landmarks"), previews.map { it.id })
        assertEquals(listOf("서울 시청 주변", "광화문 주변"), previews.map { it.title })
        assertEquals(listOf(1, 1), previews.map { it.version })
        assertEquals(listOf(3, 3), previews.map { it.points.size })
        assertTrue(previews.all { it.source == CoursePreviewSource.FIXED_OSM_BOUNDING_BOX_CENTERS })
        assertEquals(
            6,
            previews
                .flatMap { it.points }
                .map { it.id }
                .distinct()
                .size,
        )
        assertEquals(
            listOf(
                listOf(
                    CoursePreviewPoint("seoul-city-hall", "서울특별시청", 37.5666904, 126.9784222, 198561926L, 28),
                    CoursePreviewPoint("seoul-plaza", "서울광장", 37.5656223, 126.9779906, 370019676L, 8),
                    CoursePreviewPoint("daehanmun", "대한문", 37.5650316, 126.9765496, 377289797L, 8),
                ),
                listOf(
                    CoursePreviewPoint("gwanghwamun", "광화문", 37.5759283, 126.9768176, 170199440L, 22),
                    CoursePreviewPoint("sejong-center", "세종문화회관", 37.5723190, 126.9756030, 543729863L, 14),
                    CoursePreviewPoint("government-seoul-complex", "정부서울청사", 37.5748313, 126.9749411, 196076051L, 18),
                ),
            ),
            previews.map { it.points },
        )
    }
}
