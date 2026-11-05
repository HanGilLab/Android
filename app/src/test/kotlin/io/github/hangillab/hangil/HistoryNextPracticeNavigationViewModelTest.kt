package io.github.hangillab.hangil

import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryNextPracticeNavigationViewModelTest {
    @Test fun oldTokenCannotBorrowOrClearTheNextWholeSource() {
        val model = HistoryNextPracticeNavigationViewModel()
        val first = source("first")
        val oldToken = model.open(first)
        assertEquals(first, model.source(oldToken, first.sessionId))
        val next = first.copy(courseTitle = "changed whole source")
        val newToken = model.open(next)
        assertNotEquals(oldToken, newToken)
        assertNull(model.source(oldToken, first.sessionId))
        model.clear(oldToken)
        assertEquals(next, model.source(newToken, next.sessionId))
        model.clear(newToken)
        assertNull(model.source(newToken, next.sessionId))
    }

    @Test fun missingOrWrongTokenAndSourceIdNeverResolveByIdAlone() {
        val model = HistoryNextPracticeNavigationViewModel()
        val source = source("selected")
        val token = model.open(source)
        assertNull(model.source(null, source.sessionId))
        assertNull(model.source("wrong", source.sessionId))
        assertNull(model.source(token, "wrong"))
        assertNull(model.source(token, null))
        assertEquals(source, model.source(token, source.sessionId))
    }

    @Test fun activeSourceCannotReplaceAValidEndedEntry() {
        val model = HistoryNextPracticeNavigationViewModel()
        val source = source("selected")
        val token = model.open(source)
        assertNull(model.open(source.copy(endedAtEpochMillis = null)))
        assertEquals(source, model.source(token, source.sessionId))
    }

    @Test fun newProcessMemoryNeverRecoversTheSourceFromSavedRouteIds() {
        val source = source("selected")
        val token = HistoryNextPracticeNavigationViewModel().open(source)
        assertNull(HistoryNextPracticeNavigationViewModel().source(token, source.sessionId))
    }

    private fun source(id: String) =
        PracticeSession(id, PracticeSelection("goal", "course", 1, 1, "task"), "goal", "course", "task", CourseReviewStatus.DEMO, 100, 200)
}
