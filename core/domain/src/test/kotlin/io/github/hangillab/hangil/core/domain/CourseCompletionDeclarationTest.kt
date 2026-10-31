package io.github.hangillab.hangil.core.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CourseCompletionDeclarationTest {
    @Test fun `a declaration preserves the entire ended source and user origin despite clock reversal`() {
        val source = source()
        val declaration = CourseCompletionDeclaration(source, "version-fixture", 1, CourseCompletionOrigin.USER_DECLARATION)
        assertEquals(source, declaration.source)
        assertEquals(1L, declaration.declaredAtEpochMillis)
        assertEquals(CourseCompletionOrigin.USER_DECLARATION, declaration.origin)
        assertFalse(declaration.source.isActive)
    }

    @Test fun `active incomplete invalid or blank sources cannot become declarations`() {
        val source = source()
        for (invalid in listOf(
            source.copy(endedAtEpochMillis = null),
            source.copy(endedAtEpochMillis = 1),
            source.copy(startedAtEpochMillis = 0),
            source.copy(sessionId = ""),
            source.copy(goalTitle = ""),
            source.copy(courseTitle = ""),
            source.copy(taskTitle = ""),
            source.copy(selection = source.selection.copy(goalId = "")),
            source.copy(selection = source.selection.copy(courseVersion = 0)),
        )) {
            assertThrows(IllegalArgumentException::class.java) {
                CourseCompletionDeclaration(invalid, "version-fixture", 3_000, CourseCompletionOrigin.USER_DECLARATION)
            }
        }
    }

    @Test fun `unknown time and blank versions are rejected without inventing a value`() {
        assertThrows(IllegalArgumentException::class.java) {
            CourseCompletionDeclaration(source(), "", 3_000, CourseCompletionOrigin.USER_DECLARATION)
        }
        for (time in listOf(0L, -1L, Long.MIN_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) {
                CourseCompletionDeclaration(source(), "version-fixture", time, CourseCompletionOrigin.USER_DECLARATION)
            }
        }
    }

    @Test fun `deletion receipt distinguishes absence and every declaration value`() {
        val source = source()
        val declaration = CourseCompletionDeclaration(source, "version-fixture", 3_000, CourseCompletionOrigin.USER_DECLARATION)
        val absent = SessionDeletionTarget(source, 0, null)
        val present = absent.copy(completionDeclaration = declaration)
        assertNotEquals(absent, present)
        assertNotEquals(present, present.copy(completionDeclaration = declaration.copy(version = "next-fixture")))
        assertNotEquals(present, present.copy(completionDeclaration = declaration.copy(declaredAtEpochMillis = 1)))
        assertNotEquals(present, present.copy(completionDeclaration = declaration.copy(source = source.copy(taskTitle = "other"))))
    }

    private fun source() =
        PracticeSession(
            "source-fixture",
            PracticeSelection("commute", "neighborhood", 1, 1, "sequence"),
            "Fixture goal",
            "Fixture course",
            "Fixture task",
            CourseReviewStatus.DEMO,
            1_000,
            2_000,
        )
}
