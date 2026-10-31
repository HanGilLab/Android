package io.github.hangillab.hangil

import android.os.SystemClock
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.designsystem.HanGilTheme
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.RecordingRunOpenResult
import io.github.hangillab.hangil.core.domain.RecordingRunRepository
import io.github.hangillab.hangil.core.domain.RecordingRunStatus
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionDeletionRepository
import io.github.hangillab.hangil.core.domain.SessionDeletionResult
import io.github.hangillab.hangil.core.domain.SessionDeletionTarget
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import io.github.hangillab.hangil.core.domain.VehicleObservationRepository
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleRecordingRun
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.feature.home.SessionHistoryRoute
import io.github.hangillab.hangil.feature.home.SessionHistoryUiState
import io.github.hangillab.hangil.feature.home.SessionHistoryViewModel
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import io.github.hangillab.hangil.core.designsystem.R as DesignR
import io.github.hangillab.hangil.feature.home.R as HomeR

/**
 * Real Compose with explicitly controlled lifecycle/source/repositories/gates only. The real
 * catalog Activity stays RESUMED. Injected LifecycleRegistry STOP/RESUME is policy input, not
 * proof of OS Main STOP. No application graph, real SDK, Room, FGS or screenshots are used.
 */
@RunWith(AndroidJUnit4::class)
class SessionHistoryRecordingRunBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()

    @Test fun allStoredStatesKeepCreationOrderUnknownTimesAndHiddenIdentities() =
        fixture { f ->
            open(f)
            for (index in f.runs.rowsA.lastIndex downTo 0) {
                val run = f.runs.rowsA[index]
                val position = text(HomeR.string.history_recording_run_position, index + 1, f.runs.rowsA.size)
                assertText("history_recording_run_position", position)
                assertText("history_recording_run_status", text(statusResource(run.status)))
                if (run.endedAtEpochMillis == null) {
                    assertText("history_recording_run_ended", text(HomeR.string.history_recording_run_ended_unknown))
                }
                assertTrue("과거 중단은 최신 실행이 달라도 경고해야 합니다", nodes("history_recording_run_interrupted").size == 1)
                val visible = nodes("history_recording_run_content").flatMap(::texts).joinToString(" ")
                f.runs.rowsA.forEach { row ->
                    assertFalse("실행 식별자를 표시하면 안 됩니다", visible.contains(row.runId))
                    assertFalse("프로세스 식별자를 표시하면 안 됩니다", visible.contains(row.processId))
                    assertFalse("세션 식별자를 표시하면 안 됩니다", visible.contains(row.sessionId))
                }
                if (index > 0) touch("history_recording_run_previous")
            }
            assertEquals("페이지는 저장소를 다시 조회하면 안 됩니다", 1, f.runs.opens.get())
        }

    @Test fun frozenExpansionAndPagingCannotBorrowSilentGateRecoveryOrNewLifecycle() =
        fixture { f ->
            val oldExpansion = action("history_recording_runs")
            denyAndRecover(f, oldExpansion)
            invoke(oldExpansion)
            assertClosed(f)
            assertEquals(0, f.runs.opens.get())
            open(f)
            val oldPrevious = action("history_recording_run_previous")
            val before = f.runs.opens.get()
            lifecycle(f, Lifecycle.State.CREATED)
            invoke(oldPrevious, oldExpansion)
            compose.waitUntil(TIMEOUT) { f.runs.active.get() == 0 }
            lifecycle(f, Lifecycle.State.RESUMED)
            ready(
                f,
                f.sessions.values.value
                    .first(),
            )
            invoke(oldPrevious, oldExpansion)
            assertClosed(f)
            assertEquals(before, f.runs.opens.get())
            open(f)
            assertEquals(before + 1, f.runs.opens.get())
        }

    @Test fun sourceReplacementSelectionAndDisposalRetireCapturedActions() =
        fixture { f ->
            val oldExpansion = action("history_recording_runs")
            open(f)
            val oldPrevious = action("history_recording_run_previous")
            val changed = source(A).copy(courseTitle = "제어 수정 코스")
            compose.runOnIdle { f.sessions.values.value = listOf(changed, source(B)) }
            ready(f, changed)
            val before = f.runs.opens.get()
            invoke(oldExpansion, oldPrevious)
            assertClosed(f)
            assertEquals(before, f.runs.opens.get())
            open(f)
            val retired = action("history_recording_run_previous")
            touch("history_older")
            ready(f, source(B))
            invoke(retired)
            assertClosed(f)
            open(f, empty = true)
            val after = f.runs.opens.get()
            compose.runOnIdle { f.visible.value = false }
            compose.waitUntil(TIMEOUT) { f.runs.active.get() == 0 }
            invoke(retired, oldExpansion)
            assertEquals(after, f.runs.opens.get())
        }

    @Test fun oldFailureRetryCannotReopenAfterGateDenialOrReplaceAnotherReader() =
        fixture { f ->
            open(f)
            touch("history_observations")
            waitTag("review_ready")
            compose.runOnIdle { f.runs.failed.value = true }
            waitTag("history_recording_run_failed")
            assertTrue("실행 조회 실패가 기존 SDK 관찰을 지우면 안 됩니다", nodes("review_ready").size == 1)
            compose.waitUntil(TIMEOUT) { f.runs.active.get() == 0 }
            val retry = action("history_recording_run_retry")
            compose.runOnIdle { f.runs.failed.value = false }
            val before = f.runs.opens.get()
            denyAndRecover(f, retry)
            invoke(retry)
            assertClosed(f)
            assertEquals(before, f.runs.opens.get())
            open(f)
            val currentPosition = texts(nodes("history_recording_run_position").single())
            invoke(retry)
            assertEquals(currentPosition, texts(nodes("history_recording_run_position").single()))
            assertEquals(before + 1, f.runs.opens.get())
            assertEquals(1, f.runs.active.get())
        }

    @Test fun unknownUxRejectsTheCapturedExpansionUntilAFreshAllowedReader() =
        fixture { f ->
            val oldExpansion = action("history_recording_runs")
            compose.runOnIdle { f.restrictionValues.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNKNOWN) }
            compose.waitUntil(TIMEOUT) { f.model.uiState.value == SessionHistoryUiState.Locked }
            invoke(oldExpansion)
            assertEquals(0, f.runs.opens.get())
            compose.runOnIdle { f.restrictionValues.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED) }
            ready(f, source(A))
            invoke(oldExpansion)
            assertClosed(f)
            assertEquals(0, f.runs.opens.get())
            open(f)
            assertEquals(1, f.runs.opens.get())
        }

    @Test fun existingObservationFoldDoesNotReadRunsAndRunlessSessionCanKeepSdkRows() =
        fixture { f ->
            assertEquals(0, f.runs.opens.get())
            touch("history_observations")
            waitTag("review_ready")
            assertEquals(0, f.runs.opens.get())
            assertText("review_source", text(HomeR.string.review_source))
            touch("history_observations")
            touch("history_summary_tasks")
            waitTag("history_summary_scope")
            assertEquals(0, f.runs.opens.get())
            touch("history_summary_tasks")
            touch("history_memo")
            waitTag("history_memo_empty")
            assertEquals(0, f.runs.opens.get())
            touch("history_memo")
            touch("history_older")
            ready(f, source(B))
            open(f, empty = true)
            assertText("history_recording_run_empty", text(HomeR.string.history_recording_run_empty))
            touch("history_recording_runs")
            touch("history_observations")
            waitTag("review_ready")
            assertText("review_source", text(HomeR.string.review_source))
        }

    private fun fixture(block: (Fixture) -> Unit) {
        assumeTrue(
            "controlled 실행에서만 검사합니다",
            InstrumentationRegistry.getArguments().getString("manualHistoryRecordingRunBoundary") == "true",
        )
        val f = Fixture()
        val instrument = InstrumentationRegistry.getInstrumentation()
        assertHost()
        val hostLeftResumed = AtomicBoolean(false)
        val observer =
            LifecycleEventObserver { _, event ->
                if (event in
                    listOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY)
                ) {
                    hostLeftResumed.set(true)
                }
            }
        instrument.runOnMainSync {
            compose.activity.lifecycle.addObserver(observer)
            f.lifecycle.move(Lifecycle.State.RESUMED)
            f.model =
                ViewModelProvider(
                    compose.activity,
                    object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            check(modelClass == SessionHistoryViewModel::class.java)
                            @Suppress("UNCHECKED_CAST")
                            return SessionHistoryViewModel(
                                f.vehicle,
                                f.restrictions,
                                f.sessions,
                                f.deletions,
                                f.observations,
                                f.memos,
                                f.runs,
                                UnexpectedHistoryCourseCompletionRepository,
                            ) as T
                        }
                    },
                )[SessionHistoryViewModel::class.java]
        }
        var primary: Throwable? = null
        try {
            compose.setContent {
                HanGilTheme {
                    CompositionLocalProvider(LocalLifecycleOwner provides f.lifecycle) {
                        if (f.visible.value) SessionHistoryRoute(onClose = { f.visible.value = false }, viewModel = f.model)
                    }
                }
            }
            ready(f, source(A))
            assertClosed(f)
            block(f)
            assertFalse("실제 호스트 개입은 controlled 정책 성공으로 세면 안 됩니다", hostLeftResumed.get())
            assertHost()
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                instrument.runOnMainSync {
                    f.lifecycle.move(Lifecycle.State.DESTROYED)
                    compose.activity.lifecycle.removeObserver(observer)
                }
                compose.runOnIdle { f.visible.value = false }
                compose.waitForIdle()
                compose.waitUntil(TIMEOUT) { f.runs.active.get() == 0 && f.sessions.active.get() == 0 }
                assertEquals(0, f.runs.writes.get())
            } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
        }
    }

    private fun denyAndRecover(
        f: Fixture,
        old: () -> Boolean,
    ) {
        val original = f.model.uiState.value as SessionHistoryUiState.Ready
        compose.runOnIdle {
            f.live.setWithoutEmission(parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE)))
            assertFalse(f.model.canRead())
            old()
            f.live.setWithoutEmission(parked())
        }
        compose.waitForIdle()
        compose.waitUntil(TIMEOUT) { f.runs.active.get() == 0 }
        compose.runOnIdle { assertFalse(f.model.canRevealStoredRecordingRuns(original.session.sessionId, original.generation)) }
        compose.runOnIdle { f.live.emit(parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))) }
        compose.waitUntil(TIMEOUT) { f.model.uiState.value == SessionHistoryUiState.Locked }
        compose.runOnIdle { f.live.emit(parked()) }
        ready(f, original.session)
        assertClosed(f)
    }

    private fun lifecycle(
        f: Fixture,
        state: Lifecycle.State,
    ) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { f.lifecycle.move(state) }
        compose.waitForIdle()
    }

    private fun assertHost() {
        val i = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + TIMEOUT
        var resumed = false
        while (SystemClock.elapsedRealtime() < deadline) {
            i.runOnMainSync { resumed = compose.activity.lifecycle.currentState == Lifecycle.State.RESUMED }
            if (resumed && i.uiAutomation.rootInActiveWindow
                    ?.packageName
                    ?.toString() == i.targetContext.packageName
            ) {
                return
            }
            SystemClock.sleep(50)
        }
        error("실제 catalog 호스트 전면/RESUMED가 미확인입니다")
    }

    private fun ready(
        f: Fixture,
        expected: PracticeSession,
    ) = compose.waitUntil(TIMEOUT) {
        (f.model.uiState.value as? SessionHistoryUiState.Ready)?.session == expected && nodes("history_recording_runs").size == 1
    }

    private fun open(
        f: Fixture,
        empty: Boolean = false,
    ) {
        touch("history_recording_runs")
        waitTag(if (empty) "history_recording_run_empty" else "history_recording_run_ready")
        compose.waitUntil(TIMEOUT) { f.runs.active.get() == 1 }
    }

    private fun assertClosed(f: Fixture) =
        compose.waitUntil(TIMEOUT) {
            nodes("history_recording_runs").singleOrNull()?.config?.getOrNull(SemanticsProperties.StateDescription) ==
                text(DesignR.string.parked_detail_collapsed) &&
                nodes("history_recording_run_content").isEmpty() && f.runs.active.get() == 0
        }

    private fun action(tag: String): () -> Boolean {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
        compose.waitForIdle()
        return checkNotNull(
            nodes(tag)
                .single()
                .config
                .getOrNull(SemanticsActions.OnClick)
                ?.action,
        )
    }

    private fun invoke(vararg actions: () -> Boolean) {
        compose.runOnIdle { actions.forEach { it() } }
        compose.waitForIdle()
    }

    private fun touch(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun waitTag(tag: String) = compose.waitUntil(TIMEOUT) { nodes(tag).size == 1 }

    private fun nodes(tag: String): List<SemanticsNode> =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun texts(node: SemanticsNode): List<String> =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text } + node.children.flatMap(::texts)

    private fun assertText(
        tag: String,
        expected: String,
    ) {
        waitTag(tag)
        assertTrue("저장 상태의 정확한 문구가 필요합니다", texts(nodes(tag).single()).contains(expected))
    }

    private fun text(
        id: Int,
        vararg args: Any,
    ) = InstrumentationRegistry.getInstrumentation().targetContext.getString(id, *args)

    private class Fixture {
        val live = SilentState(parked())
        val vehicle =
            object : VehicleGateway {
                override val state: StateFlow<VehicleState> = live
            }
        val restrictionValues = MutableStateFlow(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))
        val restrictions =
            object : DrivingRestrictionsProvider {
                override val restrictions: StateFlow<DrivingRestrictions> = restrictionValues
            }
        val sessions = Sessions()
        val runs = Runs()
        val visible = mutableStateOf(true)
        val lifecycle = ControlledOwner()
        val deletions =
            object : SessionDeletionRepository {
                override suspend fun prepare(
                    expected: PracticeSession,
                    canRead: () -> Boolean,
                ): SessionDeletionPreparation = error("controlled read cannot prepare deletion")

                override suspend fun delete(
                    target: SessionDeletionTarget,
                    canCommit: () -> Boolean,
                ): SessionDeletionResult = error("controlled read cannot delete")
            }
        val memos =
            object : SessionMemoRepository {
                override fun observe(sessionId: String): Flow<SessionMemo?> = MutableStateFlow(null)

                override suspend fun save(
                    sessionId: String,
                    text: String,
                    expectedVersion: String?,
                    canCommit: () -> Boolean,
                ): SessionMemoWriteResult = error("controlled read cannot save memo")

                override suspend fun delete(
                    sessionId: String,
                    expectedVersion: String,
                    canCommit: () -> Boolean,
                ): SessionMemoWriteResult = error("controlled read cannot delete memo")
            }
        val observations =
            object : VehicleObservationRepository {
                override fun observe(sessionId: String): Flow<List<SessionVehicleObservation>> =
                    MutableStateFlow(listOf(SessionVehicleObservation(1, sessionId, VehicleObservation(100, parked()))))

                override suspend fun append(
                    sessionId: String,
                    observation: VehicleObservation,
                ): VehicleObservationAppendResult = error("controlled read cannot append")
            }
        lateinit var model: SessionHistoryViewModel
    }

    private class ControlledOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun move(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    @OptIn(InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
    private class SilentState<T>(
        initial: T,
        private val emitted: MutableStateFlow<T> = MutableStateFlow(initial),
    ) : StateFlow<T> by emitted {
        @Volatile private var live = initial
        override val value: T get() = live

        fun setWithoutEmission(value: T) {
            live = value
        }

        fun emit(value: T) {
            live = value
            emitted.value = value
        }
    }

    private class Sessions : PracticeSessionRepository {
        val values = MutableStateFlow(listOf(source(A), source(B)))
        val active = AtomicInteger(0)

        override fun observeSessions(): Flow<List<PracticeSession>> =
            flow {
                active.incrementAndGet()
                try {
                    emitAll(values)
                } finally {
                    active.decrementAndGet()
                }
            }

        override suspend fun start(
            practice: PreparedPractice,
            startedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionStartResult = error("controlled read cannot start")

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult = error("controlled read cannot finish")
    }

    private class Runs : RecordingRunRepository {
        val rowsA =
            listOf(
                VehicleRecordingRun("controlled-interrupted-id", A, "controlled-process-1", 4_000, null, RecordingRunStatus.INTERRUPTED),
                VehicleRecordingRun("controlled-failed-id", A, "controlled-process-2", 3_500, 4_500, RecordingRunStatus.FAILED),
                VehicleRecordingRun("controlled-stopped-id", A, "controlled-process-3", 3_000, null, RecordingRunStatus.STOPPED),
                VehicleRecordingRun("controlled-open-id", A, "controlled-process-4", 2_500, null, RecordingRunStatus.OPEN),
            )
        val failed = MutableStateFlow(false)
        val opens = AtomicInteger(0)
        val active = AtomicInteger(0)
        val writes = AtomicInteger(0)

        override fun observe(sessionId: String): Flow<List<VehicleRecordingRun>> =
            flow {
                opens.incrementAndGet()
                active.incrementAndGet()
                try {
                    emitAll(
                        combine(
                            MutableStateFlow(
                                if (sessionId ==
                                    A
                                ) {
                                    rowsA
                                } else {
                                    emptyList()
                                },
                            ),
                            failed,
                        ) { rows, bad ->
                            check(!bad) { "controlled read failure" }
                            rows
                        },
                    )
                } finally {
                    active.decrementAndGet()
                }
            }

        override suspend fun open(
            source: PracticeSession,
            runId: String,
            processId: String,
            startedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): RecordingRunOpenResult {
            writes.incrementAndGet()
            error("controlled read cannot open")
        }

        override suspend fun finish(
            runId: String,
            processId: String,
            status: RecordingRunStatus,
            endedAtEpochMillis: Long?,
        ): Boolean {
            writes.incrementAndGet()
            error("controlled read cannot finish")
        }

        override suspend fun recoverInterrupted(processId: String) {
            writes.incrementAndGet()
            error("controlled read cannot recover")
        }
    }

    private companion object {
        const val TIMEOUT = 20_000L
        const val A = "controlled-run-history-session-a"
        const val B = "controlled-run-history-session-b"

        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        fun source(id: String) =
            PracticeSession(
                id,
                PracticeSelection("controlled-goal", "controlled-course", 1, 1, "controlled-task"),
                "제어 목표",
                if (id ==
                    A
                ) {
                    "제어 최근 코스"
                } else {
                    "제어 이전 코스"
                },
                "제어 과제",
                CourseReviewStatus.DEMO,
                1_000,
                6_000,
            )

        fun statusResource(status: RecordingRunStatus) =
            when (status) {
                RecordingRunStatus.STOPPED -> HomeR.string.history_recording_run_stopped
                RecordingRunStatus.FAILED -> HomeR.string.history_recording_run_failed_status
                RecordingRunStatus.INTERRUPTED -> HomeR.string.history_recording_run_interrupted_status
                RecordingRunStatus.OPEN -> HomeR.string.history_recording_run_open
            }
    }
}
