package io.github.hangillab.hangil.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException

class NextPracticeSeedTest {
    @Test fun `ended snapshot resolves its exact current practice without changing source`() {
        val fixture = Fixture()
        val seed = checkNotNull(NextPracticeSeed.resolve(fixture.catalog, fixture.source))
        assertEquals(fixture.source, seed.source)
        assertEquals(fixture.goal, seed.practice.goal)
        assertEquals(fixture.course, seed.practice.course)
        assertEquals(fixture.tasks.first(), seed.practice.task)
        assertEquals(CourseReviewStatus.DEMO, seed.source.courseReviewStatus)
    }

    @Test fun `active nonpositive or inverted button times never become a next practice seed`() {
        val fixture = Fixture()
        listOf(
            fixture.source.copy(endedAtEpochMillis = null),
            fixture.source.copy(startedAtEpochMillis = 0),
            fixture.source.copy(startedAtEpochMillis = -1),
            fixture.source.copy(endedAtEpochMillis = 999),
        ).forEach { assertNull(NextPracticeSeed.resolve(fixture.catalog, it)) }
    }

    @Test fun `equal positive start and finish times remain valid stored button times`() {
        val fixture = Fixture()
        assertTrue(NextPracticeSeed.resolve(fixture.catalog, fixture.source.copy(endedAtEpochMillis = 1_000)) != null)
    }

    @Test fun `blank identities titles and nonpositive versions are unavailable without repair`() {
        val fixture = Fixture()
        val source = fixture.source
        listOf(
            source.copy(sessionId = " "),
            source.copy(selection = source.selection.copy(goalId = "")),
            source.copy(selection = source.selection.copy(courseId = "")),
            source.copy(selection = source.selection.copy(taskId = "")),
            source.copy(selection = source.selection.copy(courseVersion = 0)),
            source.copy(selection = source.selection.copy(briefingVersion = -1)),
            source.copy(goalTitle = " "),
            source.copy(courseTitle = ""),
            source.copy(taskTitle = ""),
        ).forEach { assertNull(NextPracticeSeed.resolve(fixture.catalog, it)) }
    }

    @Test fun `missing IDs changed versions and stored titles never fall back to another catalog item`() {
        val fixture = Fixture()
        val source = fixture.source
        listOf(
            source.copy(selection = source.selection.copy(goalId = "missing")),
            source.copy(selection = source.selection.copy(courseId = "missing")),
            source.copy(selection = source.selection.copy(taskId = "missing")),
            source.copy(selection = source.selection.copy(courseVersion = 9)),
            source.copy(selection = source.selection.copy(briefingVersion = 9)),
            source.copy(goalTitle = "old goal"),
            source.copy(courseTitle = "old course"),
            source.copy(taskTitle = "old task"),
        ).forEach { assertNull(NextPracticeSeed.resolve(fixture.catalog, it)) }
    }

    @Test fun `a goal no longer supported by the course makes the seed unavailable`() {
        val fixture = Fixture()
        fixture.catalog.courses = listOf(fixture.course.copy(goalIds = setOf("other")))
        assertNull(NextPracticeSeed.resolve(fixture.catalog, fixture.source))
    }

    @Test fun `ambiguous goal course or task identities are rejected instead of choosing the first`() {
        for (kind in 0..2) {
            val fixture = Fixture()
            when (kind) {
                0 -> fixture.catalog.goals = listOf(fixture.goal, fixture.goal.copy(description = "ambiguous"))
                1 -> fixture.catalog.courses = listOf(fixture.course, fixture.course.copy(summary = "ambiguous"))
                2 -> fixture.catalog.courses = listOf(fixture.course.copy(tasks = fixture.tasks + fixture.tasks.first()))
            }
            assertNull(NextPracticeSeed.resolve(fixture.catalog, fixture.source))
        }
    }

    @Test fun `seed owns independent course lists goal IDs and preview point collections`() {
        val fixture = Fixture()
        val seed = checkNotNull(NextPracticeSeed.resolve(fixture.catalog, fixture.source))
        fixture.sections += "later section"
        fixture.tasks += PracticeTask("later", "later task", "later description")
        fixture.goalIds += "later-goal"
        fixture.points += CoursePreviewPoint("c", "fixture C", 37.2, 127.2, 3, 1)
        assertEquals(listOf("depart", "arrive"), seed.practice.course.sections)
        assertEquals(
            listOf("first", "second"),
            seed.practice.course.tasks
                .map { it.id },
        )
        assertEquals(setOf("goal"), seed.practice.course.goalIds)
        assertEquals(
            listOf("a", "b"),
            seed.practice.course.mapPreview
                ?.points
                ?.map { it.id },
        )
    }

    @Test fun `unversioned catalog detail changes produce a different fresh complete seed`() {
        val edits: List<(PracticeCourse) -> PracticeCourse> =
            listOf(
                { it.copy(summary = "new summary") },
                { it.copy(sections = listOf("new section")) },
                { it.copy(tasks = it.tasks.map { task -> task.copy(description = "new description") }) },
                { it.copy(goalIds = it.goalIds + "another") },
                { it.copy(mapPreview = it.mapPreview?.copy(title = "new preview title")) },
                {
                    it.copy(
                        mapPreview =
                            it.mapPreview?.let { preview ->
                                preview.copy(points = preview.points.map { point -> point.copy(osmVersion = point.osmVersion + 1) })
                            },
                    )
                },
            )
        for (edit in edits) {
            val fixture = Fixture()
            val seed = checkNotNull(NextPracticeSeed.resolve(fixture.catalog, fixture.source))
            fixture.catalog.courses = listOf(edit(fixture.course))
            assertNotEquals(seed, NextPracticeSeed.resolve(fixture.catalog, fixture.source))
        }
        val fixture = Fixture()
        val seed = checkNotNull(NextPracticeSeed.resolve(fixture.catalog, fixture.source))
        fixture.catalog.goals = listOf(fixture.goal.copy(description = "new goal description"))
        assertNotEquals(seed, NextPracticeSeed.resolve(fixture.catalog, fixture.source))
    }

    @Test fun `catalog exceptions propagate without pretending the source is merely absent`() {
        val fixture = Fixture()
        val failure = IOException("fixture catalog failure")
        val broken =
            object : PracticeCatalog {
                override val goals: List<PracticeGoal> get() = throw failure
                override val courses = fixture.catalog.courses
            }
        try {
            NextPracticeSeed.resolve(broken, fixture.source)
            fail("Catalog exceptions must propagate")
        } catch (observed: IOException) {
            assertSame(failure, observed)
        }
    }

    private class MutableCatalog(
        override var goals: List<PracticeGoal>,
        override var courses: List<PracticeCourse>,
    ) : PracticeCatalog

    private class Fixture {
        val goal = PracticeGoal("goal", "fixture goal", "goal description")
        val sections = mutableListOf("depart", "arrive")
        val tasks =
            mutableListOf(
                PracticeTask("first", "fixture task", "first description"),
                PracticeTask("second", "second task", "description"),
            )
        val goalIds = mutableSetOf("goal")
        val points =
            mutableListOf(
                CoursePreviewPoint("a", "fixture A", 37.0, 127.0, 1, 1),
                CoursePreviewPoint("b", "fixture B", 37.1, 127.1, 2, 1),
            )
        val course =
            PracticeCourse(
                "course",
                2,
                3,
                "fixture course",
                "summary",
                sections,
                tasks,
                goalIds,
                mapPreview = CourseMapPreview("preview", 1, "fixture preview", points),
            )
        val catalog = MutableCatalog(listOf(goal), listOf(course))
        val source =
            PracticeSession(
                "fixture-session",
                PracticeSelection("goal", "course", 2, 3, "first"),
                goal.title,
                course.title,
                tasks.first().title,
                CourseReviewStatus.DEMO,
                1_000,
                2_000,
            )
    }
}
