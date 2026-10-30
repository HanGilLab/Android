package io.github.hangillab.hangil.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class SessionCourseSummaryTest {
    @Test fun `one ended source has one stored record without inferred execution or completion`() {
        val source = session("source")
        val summary = checkNotNull(SessionCourseSummary.from(source, listOf(source)))
        assertEquals(source, summary.source)
        assertEquals(1, summary.sessionCount)
        assertEquals(listOf(SessionTaskCount("task", "저장 과제", 1)), summary.tasks)
    }

    @Test fun `only ended records in the exact historical course context contribute`() {
        val source = session("source")
        val otherTask = session("other-task").copy(selection = source.selection.copy(taskId = "other"), taskTitle = "다른 과제")
        val active = session("active").copy(endedAtEpochMillis = null)
        val otherCourse = session("other-course").copy(selection = source.selection.copy(courseId = "elsewhere"))
        val records = listOf(active, source, otherCourse, otherTask, session("repeat"))
        val summary = checkNotNull(SessionCourseSummary.from(source, records))
        assertEquals(3, summary.sessionCount)
        assertEquals(
            listOf(SessionTaskCount("task", "저장 과제", 2), SessionTaskCount("other", "다른 과제", 1)),
            summary.tasks,
        )
        assertEquals(CourseReviewStatus.DEMO, summary.source.courseReviewStatus)
    }

    @Test fun `different identities versions and historical course titles never merge`() {
        val source = session("source")
        val other = session("other")
        val changes =
            listOf(
                other.copy(selection = other.selection.copy(goalId = "other-goal")),
                other.copy(selection = other.selection.copy(courseId = "other-course")),
                other.copy(selection = other.selection.copy(courseVersion = 2)),
                other.copy(selection = other.selection.copy(briefingVersion = 2)),
                other.copy(goalTitle = "다른 당시 목표"),
                other.copy(courseTitle = "다른 당시 코스"),
            )
        for (changed in changes) {
            val summary = checkNotNull(SessionCourseSummary.from(source, listOf(changed, source)))
            assertEquals(1, summary.sessionCount)
            assertEquals(listOf(SessionTaskCount("task", "저장 과제", 1)), summary.tasks)
        }
    }

    @Test fun `task groups follow first received appearance despite regressing button clocks`() {
        val source = session("source").copy(startedAtEpochMillis = 100, endedAtEpochMillis = 200)
        val newerClock =
            session("newer-clock").copy(
                selection = source.selection.copy(taskId = "second"),
                taskTitle = "먼저 받은 과제",
                startedAtEpochMillis = 4_000,
                endedAtEpochMillis = 5_000,
            )
        val records = listOf(newerClock, source, session("repeat").copy(startedAtEpochMillis = 300, endedAtEpochMillis = 400))
        val summary = checkNotNull(SessionCourseSummary.from(source, records))
        assertEquals(
            listOf(SessionTaskCount("second", "먼저 받은 과제", 1), SessionTaskCount("task", "저장 과제", 2)),
            summary.tasks,
        )
    }

    @Test fun `same task ID with a different stored title remains a separate task group`() {
        val source = session("source")
        val renamed = session("renamed").copy(taskTitle = "바뀐 당시 과제")
        val renamedAgain = renamed.copy(sessionId = "renamed-again")
        val summary = checkNotNull(SessionCourseSummary.from(source, listOf(renamed, source, renamedAgain)))
        assertEquals(3, summary.sessionCount)
        assertEquals(
            listOf(SessionTaskCount("task", "바뀐 당시 과제", 2), SessionTaskCount("task", "저장 과제", 1)),
            summary.tasks,
        )
    }

    @Test fun `different task IDs with the same stored title remain separate task groups`() {
        val source = session("source")
        val other = session("other").copy(selection = source.selection.copy(taskId = "other"))
        val summary = checkNotNull(SessionCourseSummary.from(source, listOf(source, other)))
        assertEquals(
            listOf(SessionTaskCount("task", "저장 과제", 1), SessionTaskCount("other", "저장 과제", 1)),
            summary.tasks,
        )
    }

    @Test fun `missing active and changed full source snapshots never produce a summary`() {
        val source = session("source")
        assertNull(SessionCourseSummary.from(source, emptyList()))
        assertNull(SessionCourseSummary.from(source, listOf(session("different"))))
        val active = source.copy(endedAtEpochMillis = null)
        assertNull(SessionCourseSummary.from(active, listOf(active)))
        val changedSnapshots =
            listOf(
                source.copy(taskTitle = "변경된 과제"),
                source.copy(selection = source.selection.copy(taskId = "changed")),
                source.copy(startedAtEpochMillis = 999),
                source.copy(endedAtEpochMillis = 2_001),
            )
        for (changed in changedSnapshots) assertNull(SessionCourseSummary.from(source, listOf(changed)))
    }

    @Test fun `duplicate IDs including an active or different course duplicate are rejected`() {
        val source = session("source")
        val duplicates =
            listOf(
                source,
                source.copy(endedAtEpochMillis = null),
                source.copy(selection = source.selection.copy(courseId = "elsewhere")),
            )
        for (duplicate in duplicates) assertNull(SessionCourseSummary.from(source, listOf(source, duplicate)))
    }

    @Test fun `invalid records fail the whole snapshot instead of being skipped or repaired`() {
        val source = session("source")
        val other = session("other")
        val invalid =
            listOf(
                other.copy(sessionId = " "),
                other.copy(selection = other.selection.copy(goalId = "")),
                other.copy(selection = other.selection.copy(courseId = " ")),
                other.copy(selection = other.selection.copy(taskId = "")),
                other.copy(selection = other.selection.copy(courseVersion = 0)),
                other.copy(selection = other.selection.copy(briefingVersion = -1)),
                other.copy(goalTitle = ""),
                other.copy(courseTitle = " "),
                other.copy(taskTitle = ""),
                other.copy(startedAtEpochMillis = 0),
                other.copy(startedAtEpochMillis = -1, endedAtEpochMillis = null),
                other.copy(endedAtEpochMillis = 999),
            )
        for (record in invalid) assertNull(SessionCourseSummary.from(source, listOf(source, record)))
        assertNull(SessionCourseSummary.from(source.copy(goalTitle = ""), listOf(source.copy(goalTitle = ""))))
    }

    @Test fun `equal positive start and finish times remain legitimate stored button records`() {
        val source = session("source").copy(endedAtEpochMillis = 1_000)
        val summary = checkNotNull(SessionCourseSummary.from(source, listOf(source)))
        assertEquals(1, summary.sessionCount)
        assertEquals(source, summary.source)
    }

    @Test fun `later input list edits cannot alter a completed summary`() {
        val source = session("source")
        val records = mutableListOf(source, session("repeat"))
        val summary = checkNotNull(SessionCourseSummary.from(source, records))
        records.clear()
        records += session("new").copy(taskTitle = "나중 자료")
        assertEquals(source, summary.source)
        assertEquals(2, summary.sessionCount)
        assertEquals(listOf(SessionTaskCount("task", "저장 과제", 2)), summary.tasks)
    }

    @Test fun `the completed Kotlin task list rejects structural mutation`() {
        val source = session("source")
        val summary = checkNotNull(SessionCourseSummary.from(source, listOf(source, session("repeat"))))
        val exposed = summary.tasks as MutableList<SessionTaskCount>
        expectUnsupported { exposed.clear() }
        expectUnsupported { exposed += SessionTaskCount("injected", "주입 과제", 1) }
        expectUnsupported { exposed[0] = SessionTaskCount("replaced", "변경 과제", 1) }
        assertEquals(listOf(SessionTaskCount("task", "저장 과제", 2)), summary.tasks)
    }

    @Test fun `equivalent snapshots retain value equality while different source snapshots differ`() {
        val source = session("source")
        val first = checkNotNull(SessionCourseSummary.from(source, listOf(source, session("repeat"))))
        val second = checkNotNull(SessionCourseSummary.from(source.copy(), listOf(source.copy(), session("repeat"))))
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        val otherSource = session("repeat")
        assertNotEquals(first, SessionCourseSummary.from(otherSource, listOf(source, otherSource)))
    }

    @Test fun `task count objects reject blank identities titles and nonpositive counts`() {
        val invalid =
            listOf<() -> SessionTaskCount>(
                { SessionTaskCount("", "title", 1) },
                { SessionTaskCount("id", " ", 1) },
                { SessionTaskCount("id", "title", 0) },
                { SessionTaskCount("id", "title", -1) },
            )
        for (create in invalid) {
            try {
                create()
                fail("Invalid counts must not be usable summary rows")
            } catch (_: IllegalArgumentException) {
                // The domain object cannot represent an ordinary zero or malformed task group.
            }
        }
    }

    private fun expectUnsupported(action: () -> Unit) {
        try {
            action()
            fail("The completed task list must reject mutation")
        } catch (_: UnsupportedOperationException) {
            // Only explicit mutation refusal satisfies this check.
        }
    }

    private fun session(id: String) =
        PracticeSession(
            id,
            PracticeSelection("goal", "course", 1, 1, "task"),
            "저장 목표",
            "저장 코스",
            "저장 과제",
            CourseReviewStatus.DEMO,
            1_000,
            2_000,
        )
}
