package io.github.hangillab.hangil.core.database

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
}
