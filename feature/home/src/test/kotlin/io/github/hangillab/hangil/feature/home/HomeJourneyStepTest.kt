package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.PracticeCourse
import io.github.hangillab.hangil.core.domain.PracticeGoal
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeTask
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.PreparedPractice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeJourneyStepTest {
    private val goal = PracticeGoal("goal", "선택한 목표", "목표 설명")
    private val task = PracticeTask("task", "오늘의 과제", "과제 설명")
    private val course = PracticeCourse("course", 1, 1, "시연 코스", "코스 설명", listOf("출발"), listOf(task), setOf(goal.id))
    private val practice = PreparedPractice(goal, course, task)
    private val ready = PreparationLoadState.Loaded(Preparation.Ready(practice))
    private val empty = PreparationLoadState.Loaded(Preparation.Empty)
    private val outdated = PreparationLoadState.Loaded(Preparation.Outdated)
    private val active =
        PracticeSession(
            "active",
            PracticeSelection(goal.id, course.id, course.version, course.briefingVersion, task.id),
            "시작할 때의 목표",
            "시작할 때의 코스",
            "시작할 때의 과제",
            CourseReviewStatus.DEMO,
            1_000L,
            null,
        )
    private val finished = active.copy(sessionId = "finished", endedAtEpochMillis = 2_000L)

    @Test fun `lock hides guidance even with an active session or unread records`() {
        for (records in listOf(SessionLoadState.Loading, SessionLoadState.Failed, SessionLoadState.Loaded(listOf(active)))) {
            val state = SessionUiState(allowsInteraction = false, preparation = ready, records = records)
            assertEquals(HomeJourneyStep.LOCKED, state.nextStep)
            assertFalse(state.canStart)
            assertFalse(state.canFinish)
        }
    }

    @Test fun `unread records do not imply a first practice even when preparation is empty`() {
        for (preparation in listOf(empty, ready, PreparationLoadState.Failed)) {
            val state = SessionUiState(true, preparation, SessionLoadState.Loading)
            assertEquals(HomeJourneyStep.LOADING, state.nextStep)
        }
    }

    @Test fun `failed records need recovery before preparation can select a next action`() {
        for (preparation in listOf(empty, ready, PreparationLoadState.Loading)) {
            val state = SessionUiState(true, preparation, SessionLoadState.Failed)
            assertEquals(HomeJourneyStep.RECOVER, state.nextStep)
        }
    }

    @Test fun `active practice remains the next action despite unavailable or changed preparation`() {
        for (preparation in listOf(PreparationLoadState.Loading, PreparationLoadState.Failed, empty, outdated, ready)) {
            val state = SessionUiState(true, preparation, SessionLoadState.Loaded(listOf(finished, active)))
            assertEquals(HomeJourneyStep.ACTIVE, state.nextStep)
            assertEquals(active, state.active)
        }
    }

    @Test fun `active snapshot is kept distinct from the currently selected preparation`() {
        val state = SessionUiState(true, ready, SessionLoadState.Loaded(listOf(active, finished)))
        assertEquals(HomeJourneyStep.ACTIVE, state.nextStep)
        assertEquals("시작할 때의 목표", state.active?.goalTitle)
        assertEquals("선택한 목표", state.ready?.goal?.title)
    }

    @Test fun `unread preparation does not imply a first practice after records finish loading`() {
        for (sessions in listOf(emptyList(), listOf(finished))) {
            val state = SessionUiState(true, PreparationLoadState.Loading, SessionLoadState.Loaded(sessions))
            assertEquals(HomeJourneyStep.LOADING, state.nextStep)
        }
    }

    @Test fun `preparation read failure is recoverable rather than a missing selection`() {
        val state = SessionUiState(true, PreparationLoadState.Failed, SessionLoadState.Loaded(listOf(finished)))
        assertEquals(HomeJourneyStep.RECOVER, state.nextStep)
        assertFalse(state.canStart)
    }

    @Test fun `finished history does not make missing or outdated preparation ready to repeat`() {
        for (preparation in listOf(empty, outdated)) {
            for (sessions in listOf(emptyList(), listOf(finished))) {
                val state = SessionUiState(true, preparation, SessionLoadState.Loaded(sessions))
                assertEquals(HomeJourneyStep.PREPARE, state.nextStep)
                assertFalse(state.canStart)
            }
        }
    }

    @Test fun `a valid preparation without history is ready for its first start`() {
        val state = SessionUiState(true, ready, SessionLoadState.Loaded(emptyList()))
        assertEquals(HomeJourneyStep.READY, state.nextStep)
        assertEquals(null, state.latestFinished)
    }

    @Test fun `a valid preparation and finished history guide a repeat without changing records`() {
        val records = SessionLoadState.Loaded(listOf(finished))
        val state = SessionUiState(true, ready, records)
        assertEquals(HomeJourneyStep.REPEAT, state.nextStep)
        assertEquals(finished, state.latestFinished)
        assertEquals(records, state.records)
    }

    @Test fun `next step guidance does not enable a second action while saving`() {
        val savingStart = SessionUiState(true, ready, SessionLoadState.Loaded(emptyList()), SessionOperationState.Saving)
        val savingFinish = SessionUiState(true, ready, SessionLoadState.Loaded(listOf(active)), SessionOperationState.Saving)
        assertEquals(HomeJourneyStep.READY, savingStart.nextStep)
        assertFalse(savingStart.canStart)
        assertEquals(HomeJourneyStep.ACTIVE, savingFinish.nextStep)
        assertFalse(savingFinish.canFinish)
    }
}
