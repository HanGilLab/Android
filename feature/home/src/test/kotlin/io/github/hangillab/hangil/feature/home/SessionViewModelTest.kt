package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeCourse
import io.github.hangillab.hangil.core.domain.PracticeGoal
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.PracticeTask
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class SessionViewModelTest {
    private val parked = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))
    private val unrestricted = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
    private val vehicle =
        object : VehicleGateway {
            override val state = MutableStateFlow(parked)
        }
    private val restrictions =
        object : DrivingRestrictionsProvider {
            override val restrictions = MutableStateFlow(unrestricted)
        }
    private val goal = PracticeGoal("goal", "연습 목표", "준비한 목표")
    private val task = PracticeTask("task", "오늘의 과제", "사용자가 선택한 과제")
    private val course = PracticeCourse("course", 2, 3, "시연 코스", "요약", listOf("출발 구간"), listOf(task), setOf(goal.id))
    private val selection = PracticeSelection(goal.id, course.id, course.version, course.briefingVersion, task.id)
    private val practice = PreparedPractice(goal, course, task)
    private val catalog =
        object : PracticeCatalog {
            override val goals = listOf(goal)
            override val courses = listOf(course)
        }
    private val selected = MutableStateFlow<PracticeSelection?>(selection)
    private var preparationReadFails = false
    private var preparationSource: Flow<PracticeSelection?> = selected
    private val preparationRepository =
        object : PracticeRepository {
            override fun observeSelection(): Flow<PracticeSelection?> =
                flow {
                    if (preparationReadFails) error("preparation read failure")
                    emitAll(preparationSource)
                }

            override suspend fun saveSelection(
                selection: PracticeSelection,
                canCommit: () -> Boolean,
            ): Boolean = error("not used")
        }
    private val records = MutableStateFlow<List<PracticeSession>>(emptyList())
    private var sessionReadFails = false
    private var recordsSource: Flow<List<PracticeSession>> = records
    private var sessionReads = 0
    private var startCalls = 0
    private var finishCalls = 0
    private var startAction: suspend (
        PreparedPractice,
        Long,
        () -> Boolean,
    ) -> SessionStartResult = { _, _, _ -> error("unexpected start") }
    private var finishAction: suspend (String, Long, () -> Boolean) -> SessionFinishResult = { _, _, _ -> error("unexpected finish") }
    private val repository =
        object : PracticeSessionRepository {
            override fun observeSessions(): Flow<List<PracticeSession>> =
                flow {
                    sessionReads += 1
                    if (sessionReadFails) error("session read failure")
                    emitAll(recordsSource)
                }

            override suspend fun start(
                practice: PreparedPractice,
                startedAtEpochMillis: Long,
                canCommit: () -> Boolean,
            ): SessionStartResult {
                startCalls += 1
                return startAction(practice, startedAtEpochMillis, canCommit)
            }

            override suspend fun finish(
                sessionId: String,
                endedAtEpochMillis: Long,
                canCommit: () -> Boolean,
            ): SessionFinishResult {
                finishCalls += 1
                return finishAction(sessionId, endedAtEpochMillis, canCommit)
            }
        }

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `live vehicle unknown blocks start before the UI recombines`() =
        runTest {
            val model = model()
            observe(model)
            assertTrue(model.uiState.value.canStart)
            vehicle.state.value = VehicleState()
            model.start()
            assertTrue(model.uiState.value.canStart)
            runCurrent()
            assertEquals(0, startCalls)
            assertFalse(model.uiState.value.allowsInteraction)
        }

    @Test fun `live UX restriction blocks finish before the UI recombines`() =
        runTest {
            records.value = listOf(session())
            val model = model()
            observe(model)
            assertTrue(model.uiState.value.canFinish)
            restrictions.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.RESTRICTED)
            model.finish("session")
            assertTrue(model.uiState.value.canFinish)
            runCurrent()
            assertEquals(0, finishCalls)
            assertFalse(model.uiState.value.canFinish)
        }

    @Test fun `transaction guard sees a vehicle change while start is suspended`() =
        runTest {
            val resume = CompletableDeferred<Unit>()
            startAction = { _, _, canCommit ->
                assertTrue(canCommit())
                resume.await()
                assertFalse(canCommit())
                SessionStartResult.Blocked
            }
            val model = model()
            observe(model)
            model.start()
            runCurrent()
            vehicle.state.value = VehicleState()
            resume.complete(Unit)
            runCurrent()
            assertFeedback(model, SessionFeedback.BLOCKED)
            assertEquals(emptyList<PracticeSession>(), records.value)
        }

    @Test fun `transaction guard sees UX lock while finish is suspended`() =
        runTest {
            val active = session()
            records.value = listOf(active)
            val resume = CompletableDeferred<Unit>()
            finishAction = { _, _, canCommit ->
                assertTrue(canCommit())
                resume.await()
                assertFalse(canCommit())
                SessionFinishResult.Blocked
            }
            val model = model()
            observe(model)
            model.finish(active.sessionId)
            runCurrent()
            restrictions.restrictions.value = DrivingRestrictions()
            resume.complete(Unit)
            runCurrent()
            assertFeedback(model, SessionFeedback.BLOCKED)
            assertEquals(listOf(active), records.value)
        }

    @Test fun `duplicate starts and finish clicks cannot enter a second mutation`() =
        runTest {
            val resume = CompletableDeferred<Unit>()
            startAction = { _, _, _ ->
                resume.await()
                SessionStartResult.Blocked
            }
            val model = model()
            observe(model)
            model.start()
            model.start()
            model.finish("session")
            runCurrent()
            assertEquals(1, startCalls)
            assertEquals(0, finishCalls)
            assertEquals(SessionOperationState.Saving, model.uiState.value.operation)
            resume.complete(Unit)
            runCurrent()
            assertFeedback(model, SessionFeedback.BLOCKED)
        }

    @Test fun `duplicate finishes enter the repository once`() =
        runTest {
            records.value = listOf(session())
            val resume = CompletableDeferred<Unit>()
            finishAction = { _, _, _ ->
                resume.await()
                SessionFinishResult.Blocked
            }
            val model = model()
            observe(model)
            model.finish("session")
            model.finish("session")
            runCurrent()
            assertEquals(1, finishCalls)
            resume.complete(Unit)
            runCurrent()
            assertFeedback(model, SessionFeedback.BLOCKED)
        }

    @Test fun `session read failure blocks writes and a parked retry reloads records`() =
        runTest {
            sessionReadFails = true
            val model = model()
            observe(model)
            assertEquals(SessionLoadState.Failed, model.uiState.value.records)
            model.start()
            model.finish("session")
            runCurrent()
            assertEquals(0, startCalls)
            assertEquals(0, finishCalls)
            sessionReadFails = false
            model.retryLoading()
            runCurrent()
            assertEquals(SessionLoadState.Loaded(emptyList()), model.uiState.value.records)
            assertTrue(model.uiState.value.canStart)
            assertEquals(2, sessionReads)
        }

    @Test fun `retry cannot read again while live UX becomes unknown`() =
        runTest {
            sessionReadFails = true
            val model = model()
            observe(model)
            sessionReadFails = false
            restrictions.restrictions.value = DrivingRestrictions()
            model.retryLoading()
            runCurrent()
            assertEquals(1, sessionReads)
            assertEquals(SessionLoadState.Failed, model.uiState.value.records)
            restrictions.restrictions.value = unrestricted
            model.retryLoading()
            runCurrent()
            assertEquals(2, sessionReads)
            assertTrue(model.uiState.value.canStart)
        }

    @Test fun `preparation failure blocks start but can be retried`() =
        runTest {
            preparationReadFails = true
            val model = model()
            observe(model)
            assertEquals(PreparationLoadState.Failed, model.uiState.value.preparation)
            model.start()
            runCurrent()
            assertEquals(0, startCalls)
            preparationReadFails = false
            model.retryLoading()
            runCurrent()
            assertEquals(PreparationLoadState.Loaded(Preparation.Ready(practice)), model.uiState.value.preparation)
            assertTrue(model.uiState.value.canStart)
        }

    @Test fun `missing and outdated preparation cannot start a session`() =
        runTest {
            selected.value = null
            val model = model()
            observe(model)
            assertFalse(model.uiState.value.canStart)
            model.start()
            runCurrent()
            selected.value = selection.copy(courseVersion = 999)
            runCurrent()
            assertEquals(PreparationLoadState.Loaded(Preparation.Outdated), model.uiState.value.preparation)
            model.start()
            runCurrent()
            assertEquals(0, startCalls)
        }

    @Test fun `write exception reports failure and releases the mutation for retry`() =
        runTest {
            startAction = { _, _, _ -> error("disk unavailable") }
            val model = model()
            observe(model)
            model.start()
            runCurrent()
            assertFeedback(model, SessionFeedback.SAVE_FAILED)
            assertTrue(model.uiState.value.canStart)
            val created = session()
            startAction = { _, _, _ ->
                records.value = listOf(created)
                SessionStartResult.Started(created)
            }
            model.start()
            runCurrent()
            assertFeedback(model, SessionFeedback.STARTED)
            assertEquals(2, startCalls)
            assertEquals(created, model.uiState.value.active)
            assertFalse(model.uiState.value.canStart)
        }

    @Test fun `start sends the selected snapshot and exact clock value`() =
        runTest {
            val created = session().copy(startedAtEpochMillis = 123_456_789L)
            startAction = { actualPractice, actualTime, canCommit ->
                assertEquals(practice, actualPractice)
                assertEquals(123_456_789L, actualTime)
                assertTrue(canCommit())
                records.value = listOf(created)
                SessionStartResult.Started(created)
            }
            val model = model(123_456_789L)
            observe(model)
            model.start()
            runCurrent()
            assertFeedback(model, SessionFeedback.STARTED)
            assertEquals(created, model.uiState.value.active)
        }

    @Test fun `invalid negative clock is passed unchanged and reported instead of normalized`() =
        runTest {
            startAction = { _, time, _ ->
                assertEquals(-17L, time)
                SessionStartResult.InvalidTime
            }
            val model = model(-17L)
            observe(model)
            model.start()
            runCurrent()
            assertFeedback(model, SessionFeedback.INVALID_TIME)
            assertEquals(emptyList<PracticeSession>(), records.value)
        }

    @Test fun `start preserves repository conflict and blocked feedback`() =
        runTest {
            val model = model()
            observe(model)
            for ((result, feedback) in listOf(
                SessionStartResult.AlreadyActive(session("other")) to SessionFeedback.ALREADY_ACTIVE,
                SessionStartResult.PreparationChanged to SessionFeedback.PREPARATION_CHANGED,
                SessionStartResult.Blocked to SessionFeedback.BLOCKED,
            )) {
                startAction = { _, _, _ -> result }
                model.start()
                runCurrent()
                assertFeedback(model, feedback)
            }
            assertEquals(3, startCalls)
        }

    @Test fun `finish preserves the requested ID and raw time for repository validation`() =
        runTest {
            records.value = listOf(session("current"))
            finishAction = { id, time, canCommit ->
                assertEquals("old-session-id", id)
                assertEquals(500L, time)
                assertTrue(canCommit())
                SessionFinishResult.InvalidTime
            }
            val model = model(500L)
            observe(model)
            model.finish("old-session-id")
            runCurrent()
            assertFeedback(model, SessionFeedback.INVALID_TIME)
            assertEquals(
                "current",
                model.uiState.value.active
                    ?.sessionId,
            )
        }

    @Test fun `finish reports a repository result without inventing an ended session`() =
        runTest {
            val active = session()
            records.value = listOf(active)
            val model = model()
            observe(model)
            for ((result, feedback) in listOf(
                SessionFinishResult.Missing to SessionFeedback.MISSING,
                SessionFinishResult.Blocked to SessionFeedback.BLOCKED,
                SessionFinishResult.AlreadyFinished(active.copy(endedAtEpochMillis = 2_000L)) to SessionFeedback.ALREADY_FINISHED,
            )) {
                finishAction = { _, _, _ -> result }
                model.finish(active.sessionId)
                runCurrent()
                assertFeedback(model, feedback)
                assertEquals(active, model.uiState.value.active)
            }
        }

    @Test fun `finished session comes from the observed repository and can end without current preparation`() =
        runTest {
            val active = session()
            val ended = active.copy(endedAtEpochMillis = 2_000L)
            selected.value = null
            records.value = listOf(active)
            finishAction = { id, time, _ ->
                assertEquals(active.sessionId, id)
                assertEquals(2_000L, time)
                records.value = listOf(ended)
                SessionFinishResult.Finished(ended)
            }
            val model = model(2_000L)
            observe(model)
            assertTrue(model.uiState.value.canFinish)
            model.finish(active.sessionId)
            runCurrent()
            assertFeedback(model, SessionFeedback.FINISHED)
            assertEquals(null, model.uiState.value.active)
            assertEquals(ended, model.uiState.value.latestFinished)
        }

    @Test fun `next practice entry follows received latest source and rejects live locks before UI recombination`() =
        runTest {
            val older = session("older").copy(endedAtEpochMillis = 1_400L)
            val recent = session("recent").copy(endedAtEpochMillis = 1_500L)
            records.value = listOf(recent, older)
            preparationReadFails = true
            val model = model()
            observe(model)
            assertTrue(model.canPrepareNext(recent.sessionId))
            assertFalse(model.canPrepareNext(older.sessionId))
            vehicle.state.value = VehicleState()
            assertFalse(model.canPrepareNext(recent.sessionId))
            vehicle.state.value = parked
            restrictions.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.RESTRICTED)
            assertFalse(model.canPrepareNext(recent.sessionId))
            restrictions.restrictions.value = unrestricted
            runCurrent()
            assertTrue(model.canPrepareNext(recent.sessionId))
            records.value = listOf(session("active"), recent, older)
            runCurrent()
            assertFalse(model.canPrepareNext(recent.sessionId))
            records.value = listOf(older)
            runCurrent()
            assertFalse(model.canPrepareNext(recent.sessionId))
            assertTrue(model.canPrepareNext(older.sessionId))
        }

    @Test fun `a pending session mutation prevents an old next practice button from opening a new draft`() =
        runTest {
            val recent = session("recent").copy(endedAtEpochMillis = 1_500L)
            records.value = listOf(recent)
            val resume = CompletableDeferred<Unit>()
            startAction = { _, _, _ ->
                resume.await()
                SessionStartResult.Blocked
            }
            val model = model()
            observe(model)
            assertTrue(model.canPrepareNext(recent.sessionId))
            model.start()
            assertFalse(model.canPrepareNext(recent.sessionId))
            runCurrent()
            assertFalse(model.canPrepareNext(recent.sessionId))
            resume.complete(Unit)
            runCurrent()
            assertTrue(model.canPrepareNext(recent.sessionId))
        }

    @Test fun `recent and guide expansion recheck live gear connection and UX before UI recombination`() =
        runTest {
            val recent = session("recent").copy(endedAtEpochMillis = 1_500L)
            records.value = listOf(recent)
            val model = model()
            observe(model)
            val displayedStep = model.uiState.value.nextStep
            assertTrue(model.canExpandGuide(displayedStep, null))
            assertTrue(model.canExpandRecent(recent))
            vehicle.state.value = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.DRIVE))
            assertTrue(model.uiState.value.allowsInteraction)
            assertFalse(model.canExpandGuide(displayedStep, null))
            assertFalse(model.canExpandRecent(recent))
            vehicle.state.value = parked
            restrictions.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.RESTRICTED)
            assertFalse(model.canExpandGuide(displayedStep, null))
            assertFalse(model.canExpandRecent(recent))
            restrictions.restrictions.value = DrivingRestrictions()
            assertFalse(model.canExpandGuide(displayedStep, null))
            assertFalse(model.canExpandRecent(recent))
            restrictions.restrictions.value = unrestricted
            vehicle.state.value = VehicleState()
            assertFalse(model.canExpandGuide(displayedStep, null))
            assertFalse(model.canExpandRecent(recent))
            assertEquals(1, sessionReads)
            assertEquals(0, startCalls)
            assertEquals(0, finishCalls)
        }

    @Test fun `a separate active session and pending write do not hide a valid parked recent record`() =
        runTest {
            val active = session("active")
            val recent = session("recent").copy(endedAtEpochMillis = 1_500L)
            records.value = listOf(active, recent)
            val resume = CompletableDeferred<Unit>()
            finishAction = { _, _, _ ->
                resume.await()
                SessionFinishResult.Blocked
            }
            val model = model()
            observe(model)
            assertTrue(model.canExpandGuide(HomeJourneyStep.ACTIVE, active))
            assertTrue(model.canExpandRecent(recent))
            assertFalse(model.canPrepareNext(recent.sessionId))
            model.finish(active.sessionId)
            runCurrent()
            assertEquals(SessionOperationState.Saving, model.uiState.value.operation)
            repeat(10) {
                assertTrue(model.canExpandGuide(HomeJourneyStep.ACTIVE, active))
                assertTrue(model.canExpandRecent(recent))
            }
            assertEquals(1, sessionReads)
            assertEquals(0, startCalls)
            assertEquals(1, finishCalls)
            resume.complete(Unit)
            runCurrent()
            assertEquals(listOf(active, recent), records.value)
        }

    @Test fun `received full active and recent snapshots reject old callbacks before UI publication`() =
        runTest {
            val active = session("active")
            val recent = session("recent").copy(endedAtEpochMillis = 1_500L)
            val replacementActive = active.copy(taskTitle = "수정된 활성 과제")
            val replacementRecent = recent.copy(goalTitle = "수정된 종료 목표")
            val advance = CompletableDeferred<Unit>()
            val checked = CompletableDeferred<Unit>()
            lateinit var model: SessionViewModel
            recordsSource =
                flow {
                    emit(listOf(active, recent))
                    advance.await()
                    backgroundScope.launch {
                        assertEquals(active, model.uiState.value.active)
                        assertEquals(recent, model.uiState.value.latestFinished)
                        assertFalse(model.canExpandGuide(HomeJourneyStep.ACTIVE, active))
                        assertTrue(model.canExpandGuide(HomeJourneyStep.ACTIVE, replacementActive))
                        assertFalse(model.canExpandRecent(recent))
                        assertTrue(model.canExpandRecent(replacementRecent))
                        checked.complete(Unit)
                    }
                    emit(listOf(replacementActive, replacementRecent))
                    awaitCancellation()
                }
            model = model()
            observe(model)
            assertTrue(model.canExpandGuide(HomeJourneyStep.ACTIVE, active))
            assertTrue(model.canExpandRecent(recent))
            advance.complete(Unit)
            runCurrent()
            assertTrue(checked.isCompleted)
            assertEquals(replacementActive, model.uiState.value.active)
            assertEquals(replacementRecent, model.uiState.value.latestFinished)
            assertEquals(1, sessionReads)
            assertEquals(0, startCalls)
            assertEquals(0, finishCalls)
        }

    @Test fun `guide uses the received preparation step instead of a delayed repeat display`() =
        runTest {
            val recent = session("recent").copy(endedAtEpochMillis = 1_500L)
            records.value = listOf(recent)
            val advance = CompletableDeferred<Unit>()
            val checked = CompletableDeferred<Unit>()
            lateinit var model: SessionViewModel
            preparationSource =
                flow {
                    emit(selection)
                    advance.await()
                    backgroundScope.launch {
                        assertEquals(HomeJourneyStep.REPEAT, model.uiState.value.nextStep)
                        assertFalse(model.canExpandGuide(HomeJourneyStep.REPEAT, null))
                        assertTrue(model.canExpandGuide(HomeJourneyStep.PREPARE, null))
                        assertTrue(model.canExpandRecent(recent))
                        checked.complete(Unit)
                    }
                    emit(null)
                    awaitCancellation()
                }
            model = model()
            observe(model)
            assertTrue(model.canExpandGuide(HomeJourneyStep.REPEAT, null))
            advance.complete(Unit)
            runCurrent()
            assertTrue(checked.isCompleted)
            assertEquals(HomeJourneyStep.PREPARE, model.uiState.value.nextStep)
            assertEquals(1, sessionReads)
            assertEquals(0, startCalls)
            assertEquals(0, finishCalls)
        }

    @Test fun `static loading guide stays available while the visible sources have no values`() =
        runTest {
            preparationSource = flow { awaitCancellation() }
            recordsSource = flow { awaitCancellation() }
            val model = model()
            observe(model)
            assertEquals(HomeJourneyStep.LOADING, model.uiState.value.nextStep)
            assertTrue(model.canExpandGuide(HomeJourneyStep.LOADING, null))
            assertFalse(model.canExpandGuide(HomeJourneyStep.READY, null))
            assertFalse(model.canExpandRecent(session().copy(endedAtEpochMillis = 1_500L)))
            assertEquals(1, sessionReads)
            assertEquals(0, startCalls)
            assertEquals(0, finishCalls)
        }

    @Test fun `read failure revokes old record callbacks but preserves the static recovery guide`() =
        runTest {
            val recent = session("recent").copy(endedAtEpochMillis = 1_500L)
            val failRead = CompletableDeferred<Unit>()
            val checked = CompletableDeferred<Unit>()
            lateinit var model: SessionViewModel
            recordsSource =
                flow {
                    emit(listOf(recent))
                    failRead.await()
                    backgroundScope.launch {
                        assertEquals(recent, model.uiState.value.latestFinished)
                        assertFalse(model.canExpandRecent(recent))
                        assertFalse(model.canExpandGuide(HomeJourneyStep.REPEAT, null))
                        assertTrue(model.canExpandGuide(HomeJourneyStep.RECOVER, null))
                        checked.complete(Unit)
                    }
                    error("fixture read failure")
                }
            model = model()
            observe(model)
            assertTrue(model.canExpandRecent(recent))
            failRead.complete(Unit)
            runCurrent()
            assertTrue(checked.isCompleted)
            assertEquals(SessionLoadState.Failed, model.uiState.value.records)
            assertTrue(model.canExpandGuide(HomeJourneyStep.RECOVER, null))
            assertFalse(model.canExpandRecent(recent))
            assertEquals(1, sessionReads)
            assertEquals(0, startCalls)
            assertEquals(0, finishCalls)
        }

    @Test fun `normally completed record streams publish failure instead of retaining session details`() =
        runTest {
            val recent = session("recent").copy(endedAtEpochMillis = 1_500L)
            recordsSource = flowOf(listOf(recent))
            val model = model()
            observe(model)
            assertEquals(SessionLoadState.Failed, model.uiState.value.records)
            assertEquals(null, model.uiState.value.latestFinished)
            assertFalse(model.canExpandRecent(recent))
            assertFalse(model.canExpandGuide(HomeJourneyStep.REPEAT, null))
            assertTrue(model.canExpandGuide(HomeJourneyStep.RECOVER, null))
            assertEquals(1, sessionReads)
            assertEquals(0, startCalls)
            assertEquals(0, finishCalls)
        }

    @Test fun `stopping the visible reader revokes guide and recent expansions until a fresh subscription`() =
        runTest {
            val recent = session("recent").copy(endedAtEpochMillis = 1_500L)
            records.value = listOf(recent)
            val model = model()
            assertFalse(model.canExpandGuide(HomeJourneyStep.LOADING, null))
            assertFalse(model.canExpandRecent(recent))
            val observation = observe(model)
            assertTrue(model.canExpandGuide(HomeJourneyStep.REPEAT, null))
            assertTrue(model.canExpandRecent(recent))
            observation.cancel()
            runCurrent()
            assertFalse(model.canExpandGuide(HomeJourneyStep.REPEAT, null))
            assertFalse(model.canExpandRecent(recent))
            observe(model)
            assertTrue(model.canExpandGuide(HomeJourneyStep.REPEAT, null))
            assertTrue(model.canExpandRecent(recent))
            assertEquals(2, sessionReads)
            assertEquals(0, startCalls)
            assertEquals(0, finishCalls)
        }

    @Test fun normalPreparationCompletionPublishesFailureButPreservesLiveRecentReading() =
        runTest {
            val recent = session("recent").copy(endedAtEpochMillis = 1_500L)
            records.value = listOf(recent)
            val complete = CompletableDeferred<Unit>()
            preparationSource =
                flow {
                    emit(selection)
                    complete.await()
                }
            val model = model()
            observe(model)
            assertEquals(PreparationLoadState.Loaded(Preparation.Ready(practice)), model.uiState.value.preparation)
            assertTrue(model.canExpandGuide(HomeJourneyStep.REPEAT, null))
            assertTrue(model.canExpandRecent(recent))
            complete.complete(Unit)
            runCurrent()
            assertEquals(PreparationLoadState.Failed, model.uiState.value.preparation)
            assertEquals(HomeJourneyStep.RECOVER, model.uiState.value.nextStep)
            assertFalse(model.canExpandGuide(HomeJourneyStep.REPEAT, null))
            assertTrue(model.canExpandGuide(HomeJourneyStep.RECOVER, null))
            assertEquals(SessionLoadState.Loaded(listOf(recent)), model.uiState.value.records)
            assertTrue(model.canExpandRecent(recent))
            val active = session("active")
            records.value = listOf(active, recent)
            runCurrent()
            assertTrue(model.canExpandGuide(HomeJourneyStep.ACTIVE, active))
            assertTrue(model.canExpandRecent(recent))
            assertEquals(1, sessionReads)
            assertEquals(0, startCalls)
            assertEquals(0, finishCalls)
        }

    @Test fun normalRecordsCompletionPublishesFailureAfterVisibleRecentDetails() =
        runTest {
            val recent = session("recent").copy(endedAtEpochMillis = 1_500L)
            val complete = CompletableDeferred<Unit>()
            recordsSource =
                flow {
                    emit(listOf(recent))
                    complete.await()
                }
            val model = model()
            observe(model)
            assertEquals(SessionLoadState.Loaded(listOf(recent)), model.uiState.value.records)
            assertEquals(recent, model.uiState.value.latestFinished)
            assertTrue(model.canExpandRecent(recent))
            assertTrue(model.canExpandGuide(HomeJourneyStep.REPEAT, null))
            complete.complete(Unit)
            runCurrent()
            assertEquals(SessionLoadState.Failed, model.uiState.value.records)
            assertEquals(HomeJourneyStep.RECOVER, model.uiState.value.nextStep)
            assertFalse(model.canExpandRecent(recent))
            assertFalse(model.canExpandGuide(HomeJourneyStep.REPEAT, null))
            assertTrue(model.canExpandGuide(HomeJourneyStep.RECOVER, null))
            assertEquals(1, sessionReads)
            assertEquals(0, startCalls)
            assertEquals(0, finishCalls)
        }

    @Test fun `retry cancellation restarts sources without publishing a false read failure`() =
        runTest {
            val recent = session("recent").copy(endedAtEpochMillis = 1_500L)
            records.value = listOf(recent)
            val model = model()
            val seen = mutableListOf<SessionUiState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect { seen += it } }
            runCurrent()
            assertTrue(model.canExpandRecent(recent))
            model.retryLoading()
            runCurrent()
            assertEquals(PreparationLoadState.Loaded(Preparation.Ready(practice)), model.uiState.value.preparation)
            assertEquals(SessionLoadState.Loaded(listOf(recent)), model.uiState.value.records)
            assertTrue(model.canExpandRecent(recent))
            assertTrue(model.canExpandGuide(HomeJourneyStep.REPEAT, null))
            assertTrue(seen.none { it.preparation == PreparationLoadState.Failed || it.records == SessionLoadState.Failed })
            assertEquals(2, sessionReads)
            assertEquals(0, startCalls)
            assertEquals(0, finishCalls)
        }

    private fun model(time: Long = 2_000L) =
        SessionViewModel(
            vehicle,
            restrictions,
            preparationRepository,
            catalog,
            repository,
            Clock.fixed(Instant.ofEpochMilli(time), ZoneOffset.UTC),
        )

    private fun TestScope.observe(model: SessionViewModel): Job {
        val observation = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect {} }
        runCurrent()
        return observation
    }

    private fun assertFeedback(
        model: SessionViewModel,
        expected: SessionFeedback,
    ) {
        assertEquals(SessionOperationState.Feedback(expected), model.uiState.value.operation)
    }

    private fun session(id: String = "session") =
        PracticeSession(
            id,
            selection,
            goal.title,
            course.title,
            task.title,
            CourseReviewStatus.DEMO,
            1_000L,
            null,
        )
}
