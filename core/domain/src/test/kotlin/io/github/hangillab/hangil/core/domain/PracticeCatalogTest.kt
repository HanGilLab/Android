package io.github.hangillab.hangil.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeCatalogTest {
    private val course =
        PracticeCourse("course", 2, 3, "시연", "설명", listOf("출발", "도착"), listOf(PracticeTask("task", "과제", "설명")), setOf("goal"))
    private val catalog =
        object : PracticeCatalog {
            override val goals = listOf(PracticeGoal("goal", "목표", "설명"), PracticeGoal("other", "다른 목표", "설명"))
            override val courses = listOf(course)
        }
    private val selection = PracticeSelection("goal", "course", 2, 3, "task")

    @Test fun `valid selection resolves its one task`() {
        val ready = catalog.resolve(selection) as Preparation.Ready
        assertEquals("task", ready.practice.task.id)
        assertEquals(CourseReviewStatus.DEMO, ready.practice.course.reviewStatus)
    }

    @Test fun `missing selection is empty`() {
        assertEquals(Preparation.Empty, catalog.resolve(null))
    }

    @Test fun `changed course or briefing must be selected again`() {
        assertEquals(Preparation.Outdated, catalog.resolve(selection.copy(courseVersion = 1)))
        assertEquals(Preparation.Outdated, catalog.resolve(selection.copy(briefingVersion = 2)))
    }

    @Test fun `missing or incompatible references cannot silently fall back`() {
        listOf(
            selection.copy(goalId = "deleted"),
            selection.copy(goalId = "other"),
            selection.copy(courseId = "deleted"),
            selection.copy(taskId = "deleted"),
        ).forEach {
            assertTrue(catalog.resolve(it) is Preparation.Outdated)
        }
    }
}
