package io.github.hangillab.hangil

import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
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
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionDeletionRepository
import io.github.hangillab.hangil.core.domain.SessionDeletionResult
import io.github.hangillab.hangil.core.domain.SessionDeletionTarget
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.TurnSignalState
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import io.github.hangillab.hangil.core.domain.VehicleObservationRepository
import io.github.hangillab.hangil.core.domain.VehicleReading
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import io.github.hangillab.hangil.core.designsystem.R as DesignR
import io.github.hangillab.hangil.feature.home.R as HomeR

/**
 * Explicit controlled native UI validation with manualHistoryObservationBoundary=true.
 * Real History Route, ViewModel, lifecycle and native semantics receive synthetic inputs only.
 * This debug catalog host never opens the application graph, SDK, Room or real user data.
 * Silent live changes model a delayed state delivery boundary, not an actual DDS timing trace.
 * Fresh synthetic D/P notifications are emitted separately after the silent denial assertions;
 * a value-only return to P is not treated as a delivered SDK recovery event.
 * Passing this test does not prove SDK collection, a physical drive or navigation integration.
 */
@RunWith(AndroidJUnit4::class)
class SessionHistoryObservationBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()

    @Test fun silentLiveDenialDiscardsFrozenExpansionPagingAndRetryIntents() =
        withFixture { fixture ->
            val oldExpansion = clickAction("history_observations")
            val beforeExpansion = fixture.observations.opens.get()
            denyAndReturn(fixture, oldExpansion)
            assertEquals("거부된 펼침은 관찰 조회를 만들면 안 됩니다", beforeExpansion, fixture.observations.opens.get())
            invokeFrozen(oldExpansion)
            assertClosed(fixture)

            open(fixture, SESSION_A)
            selectMiddle()
            val oldPrevious = clickAction("review_previous")
            val beforePrevious = fixture.observations.opens.get()
            denyAndReturn(fixture, oldPrevious)
            assertEquals("거부된 페이지 이동은 조회를 다시 열면 안 됩니다", beforePrevious, fixture.observations.opens.get())
            invokeFrozen(oldPrevious)
            assertClosed(fixture)

            open(fixture, SESSION_A)
            selectMiddle()
            val oldNext = clickAction("review_next")
            val beforeNext = fixture.observations.opens.get()
            denyAndReturn(fixture, oldNext)
            invokeFrozen(oldNext)
            assertClosed(fixture)
            assertEquals("옛 다음 행동은 P 복귀 후 조회를 다시 만들면 안 됩니다", beforeNext, fixture.observations.opens.get())

            open(fixture, SESSION_A)
            compose.runOnIdle { fixture.observations.failReads.value = true }
            waitFor("review_failed")
            compose.waitUntil(TIMEOUT) { fixture.observations.active.get() == 0 }
            val oldRetry = clickAction("review_retry")
            compose.runOnIdle { fixture.observations.failReads.value = false }
            val beforeRetry = fixture.observations.opens.get()
            denyAndReturn(fixture, oldRetry)
            invokeFrozen(oldRetry)
            assertClosed(fixture)
            assertEquals("P 복귀 후에도 옛 재시도는 새 조회를 만들면 안 됩니다", beforeRetry, fixture.observations.opens.get())

            open(fixture, SESSION_A)
            assertEquals("새로운 허용 클릭만 관찰 조회를 하나 열어야 합니다", beforeRetry + 1, fixture.observations.opens.get())
            selectMiddle()
        }

    @Test fun changedSessionSnapshotAndHistorySelectionRetireOldNativeActions() =
        withFixture { fixture ->
            val originalExpansion = clickAction("history_observations")
            open(fixture, SESSION_A)
            selectMiddle()
            val originalPrevious = clickAction("review_previous")
            val changed = fixtureSession(SESSION_A).copy(courseTitle = "제어 입력 수정 코스")
            compose.runOnIdle { fixture.sessions.values.value = listOf(changed, fixtureSession(SESSION_B)) }
            waitReady(fixture, changed)
            assertClosed(fixture)
            val afterMetadata = fixture.observations.opens.get()
            invokeFrozen(originalExpansion, originalPrevious)
            assertClosed(fixture)
            assertEquals("같은 ID의 옛 메타데이터 콜백은 새 조회를 열면 안 됩니다", afterMetadata, fixture.observations.opens.get())

            open(fixture, SESSION_A)
            selectMiddle()
            val selectedExpansion = clickAction("history_observations")
            val selectedNext = clickAction("review_next")
            touch("history_older")
            waitReady(fixture, fixtureSession(SESSION_B))
            assertClosed(fixture)
            val afterSelection = fixture.observations.opens.get()
            invokeFrozen(selectedExpansion, selectedNext)
            assertClosed(fixture)
            assertEquals("다른 세션 선택 후 옛 콜백은 조회를 열면 안 됩니다", afterSelection, fixture.observations.opens.get())

            open(fixture, SESSION_B)
            selectMiddle()
            val position = nodeTexts(nodes("review_position").single())
            val gear = nodeTexts(nodes("review_gear").single())
            invokeFrozen(originalPrevious, selectedNext)
            waitFor("review_ready")
            assertEquals("옛 페이지 콜백은 새 세션의 선택을 바꾸면 안 됩니다", position, nodeTexts(nodes("review_position").single()))
            assertEquals("옛 세션의 상태가 새 선택에 섞이면 안 됩니다", gear, nodeTexts(nodes("review_gear").single()))
            assertEquals("옛 토큰은 새 세션의 구독을 취소하면 안 됩니다", 1, fixture.observations.activeFor(SESSION_B))
            assertEquals("이전 세션 구독은 끝나 있어야 합니다", 0, fixture.observations.activeFor(SESSION_A))
            assertEquals("옛 콜백은 새 구독을 중복 생성하면 안 됩니다", afterSelection + 1, fixture.observations.opens.get())
        }

    @Test fun stoppedHostDiscardsFrozenExpansionPagingAndFailedRetryBeforeFreshStart() =
        withFixture { fixture ->
            val oldExpansion = clickAction("history_observations")
            open(fixture, SESSION_A)
            selectMiddle()
            val oldPrevious = clickAction("review_previous")
            val beforeStop = fixture.observations.opens.get()
            stopAndInvoke(fixture, oldExpansion, oldPrevious)
            assertEquals("정지 중 옛 콜백은 관찰 조회를 만들면 안 됩니다", beforeStop, fixture.observations.opens.get())
            resume(fixture)
            invokeFrozen(oldExpansion, oldPrevious)
            assertClosed(fixture)
            assertEquals("재시작 후 옛 수명 콜백은 조회를 만들면 안 됩니다", beforeStop, fixture.observations.opens.get())

            open(fixture, SESSION_A)
            compose.runOnIdle { fixture.observations.failReads.value = true }
            waitFor("review_failed")
            compose.waitUntil(TIMEOUT) { fixture.observations.active.get() == 0 }
            val oldRetry = clickAction("review_retry")
            compose.runOnIdle { fixture.observations.failReads.value = false }
            val beforeRetry = fixture.observations.opens.get()
            stopAndInvoke(fixture, oldRetry)
            resume(fixture)
            invokeFrozen(oldRetry)
            assertClosed(fixture)
            assertEquals("정지 전에 캡처한 재시도는 다음 수명에 전달되면 안 됩니다", beforeRetry, fixture.observations.opens.get())

            open(fixture, SESSION_A)
            assertEquals("현재 수명의 새 클릭은 정상 조회를 열어야 합니다", beforeRetry + 1, fixture.observations.opens.get())
            selectMiddle()
        }

    private fun withFixture(block: (Fixture) -> Unit) {
        assumeTrue(
            "명시적인 제어 입력 native 관찰 경계 실행에서만 검사합니다",
            InstrumentationRegistry.getArguments().getString("manualHistoryObservationBoundary") == "true",
        )
        val fixture = Fixture()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val factory =
                object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        assertTrue("실제 History ViewModel만 생성해야 합니다", modelClass == SessionHistoryViewModel::class.java)
                        @Suppress("UNCHECKED_CAST")
                        return SessionHistoryViewModel(
                            fixture.vehicle,
                            fixture.restrictions,
                            fixture.sessions,
                            fixture.deletions,
                            fixture.observations,
                        ) as T
                    }
                }
            fixture.model = ViewModelProvider(compose.activity, factory)[SessionHistoryViewModel::class.java]
        }
        var primaryFailure: Throwable? = null
        try {
            compose.setContent {
                HanGilTheme {
                    if (fixture.visible.value) {
                        val probe = fixture.parentProbe.intValue
                        val close =
                            remember(probe) {
                                {
                                    fixture.closeCalls.incrementAndGet()
                                    Unit
                                }
                            }
                        SessionHistoryRoute(onClose = close, viewModel = fixture.model)
                    }
                }
            }
            waitReady(fixture, fixtureSession(SESSION_A))
            assertClosed(fixture)
            block(fixture)
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                compose.runOnIdle {
                    fixture.liveVehicle.setWithoutEmission(parked())
                    fixture.visible.value = false
                }
                compose.waitForIdle()
                compose.waitUntil(TIMEOUT) { fixture.sessions.active.get() == 0 && fixture.observations.active.get() == 0 }
                assertEquals("세션 저장 호출은 없어야 합니다", 0, fixture.sessions.writes.get())
                assertEquals("관찰 저장 호출은 없어야 합니다", 0, fixture.observations.writes.get())
                assertEquals("삭제 준비나 실행은 없어야 합니다", 0, fixture.deletions.calls.get())
                assertEquals("부모 재구성은 닫기 행동을 호출하면 안 됩니다", 0, fixture.closeCalls.get())
            } catch (cleanupFailure: Throwable) {
                val original = primaryFailure
                if (original == null) throw cleanupFailure
                original.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun denyAndReturn(
        fixture: Fixture,
        action: () -> Boolean,
    ) {
        lateinit var original: SessionHistoryUiState.Ready
        val beforeOpens = fixture.observations.opens.get()
        compose.runOnIdle {
            val current = fixture.model.uiState.value
            assertTrue("옛 Ready 표시가 남은 경계에서 시작해야 합니다", current is SessionHistoryUiState.Ready)
            original = current as SessionHistoryUiState.Ready
            fixture.liveVehicle.setWithoutEmission(parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE)))
            assertFalse("방출하지 않아도 실제 live 값은 D여야 합니다", fixture.model.canRead())
            assertEquals("SDK 표시 지연을 합성한 상태여야 합니다", original, fixture.model.uiState.value)
            action()
            // No frame or upstream emission is allowed between denial and return to P.
            fixture.liveVehicle.setWithoutEmission(parked())
            fixture.parentProbe.intValue++
        }
        compose.waitForIdle()
        compose.waitUntil(TIMEOUT) { fixture.observations.active.get() == 0 }
        compose.runOnIdle {
            assertFalse(
                "값만 P로 바꿔도 거부된 옛 원본 세대가 다시 허용되면 안 됩니다",
                fixture.model.canRevealStoredObservations(original.session.sessionId, original.generation),
            )
            action()
        }
        compose.waitForIdle()
        assertObservationDetailsAbsent()
        assertEquals("새 알림 전 옛 행동은 관찰 조회를 열면 안 됩니다", beforeOpens, fixture.observations.opens.get())
        assertEquals("거부한 관찰 구독은 정리돼 있어야 합니다", 0, fixture.observations.active.get())

        // The backing StateFlow still holds P. Deliver distinct fixture D and P notifications
        // in separate idle turns, rather than assuming a silent P value can wake a collector.
        compose.runOnIdle {
            fixture.liveVehicle.emitValue(parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE)))
        }
        compose.waitForIdle()
        compose.waitUntil(TIMEOUT) { fixture.model.uiState.value == SessionHistoryUiState.Locked }
        assertObservationDetailsAbsent()
        assertEquals("새 합성 D 알림에서도 관찰 구독은 없어야 합니다", 0, fixture.observations.active.get())
        compose.runOnIdle { fixture.liveVehicle.emitValue(parked()) }
        compose.waitForIdle()
        waitReady(fixture, original.session)
        assertTrue(
            "새 합성 P 알림은 거부된 세대와 다른 원본 세대를 만들어야 합니다",
            (fixture.model.uiState.value as SessionHistoryUiState.Ready).generation != original.generation,
        )
        assertClosed(fixture)
    }

    private fun stopAndInvoke(
        fixture: Fixture,
        vararg actions: () -> Boolean,
    ) {
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        compose.waitUntil(TIMEOUT) { fixture.sessions.active.get() == 0 && fixture.observations.active.get() == 0 }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { actions.forEach { it() } }
        assertEquals("정지한 상세는 구독을 유지하면 안 됩니다", 0, fixture.observations.active.get())
        assertEquals("정지한 이력은 세션 구독을 유지하면 안 됩니다", 0, fixture.sessions.active.get())
    }

    private fun resume(fixture: Fixture) {
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitReady(fixture, fixtureSession(SESSION_A))
        assertClosed(fixture)
    }

    private fun waitReady(
        fixture: Fixture,
        session: PracticeSession,
    ) {
        compose.waitUntil(TIMEOUT) {
            (fixture.model.uiState.value as? SessionHistoryUiState.Ready)?.session == session &&
                nodes("history_observations").size == 1
        }
        assertTrue("현재 선택의 제목을 표시해야 합니다", nodeTexts(nodes("history_course").single()).contains(session.courseTitle))
    }

    private fun open(
        fixture: Fixture,
        sessionId: String,
    ) {
        touch("history_observations")
        waitFor("review_ready")
        compose.waitUntil(TIMEOUT) { fixture.observations.active.get() == 1 && fixture.observations.activeFor(sessionId) == 1 }
    }

    private fun selectMiddle() {
        val current = nodeTexts(nodes("review_position").single())
        when {
            current.contains(text(HomeR.string.review_position, 1, 3)) -> touch("review_next")
            current.contains(text(HomeR.string.review_position, 3, 3)) -> touch("review_previous")
            else -> assertTrue("세 개의 합성 관찰 중 하나가 선택돼야 합니다", current.contains(text(HomeR.string.review_position, 2, 3)))
        }
        compose.waitUntil(TIMEOUT) {
            nodes("review_position").singleOrNull()?.let(::nodeTexts)?.contains(text(HomeR.string.review_position, 2, 3)) ==
                true
        }
        assertTrue("중간 관찰은 이전과 다음 행동을 모두 제공해야 합니다", nodes("review_previous").size == 1 && nodes("review_next").size == 1)
        assertTrue("중간 관찰의 이전 행동은 허용돼야 합니다", nodes("review_previous").single().config.getOrNull(SemanticsProperties.Disabled) == null)
        assertTrue("중간 관찰의 다음 행동은 허용돼야 합니다", nodes("review_next").single().config.getOrNull(SemanticsProperties.Disabled) == null)
    }

    private fun assertClosed(fixture: Fixture) {
        val collapsed = text(DesignR.string.parked_detail_collapsed)
        compose.waitUntil(TIMEOUT) {
            val disclosure = nodes("history_observations").singleOrNull()
            disclosure?.config?.getOrNull(SemanticsProperties.StateDescription) == collapsed &&
                nodes("review_ready").isEmpty() && nodes("review_failed").isEmpty() &&
                nodes("review_empty").isEmpty() && nodes("review_loading").isEmpty() && fixture.observations.active.get() == 0
        }
    }

    private fun assertObservationDetailsAbsent() {
        listOf(
            "history_observation_content",
            "review_ready",
            "review_failed",
            "review_empty",
            "review_loading",
            "review_previous",
            "review_next",
            "review_retry",
        ).forEach { tag -> assertTrue("거부된 상세 행동은 화면에 남으면 안 됩니다 ($tag)", nodes(tag).isEmpty()) }
    }

    private fun invokeFrozen(vararg actions: () -> Boolean) {
        compose.runOnIdle { actions.forEach { it() } }
        compose.waitForIdle()
    }

    private fun clickAction(tag: String): () -> Boolean {
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

    private fun touch(tag: String) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun waitFor(tag: String) = compose.waitUntil(TIMEOUT) { nodes(tag).size == 1 }

    private fun nodes(tag: String): List<SemanticsNode> =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun nodeTexts(node: SemanticsNode): List<String> =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text } + node.children.flatMap(::nodeTexts)

    private fun text(
        resource: Int,
        vararg arguments: Any,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resource, *arguments)

    private class Fixture {
        val liveVehicle = SilentLiveState(parked())
        val vehicle =
            object : VehicleGateway {
                override val state: StateFlow<VehicleState> = liveVehicle
            }
        val restrictions =
            object : DrivingRestrictionsProvider {
                override val restrictions = MutableStateFlow(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))
            }
        val sessions = FixtureSessions()
        val observations = FixtureObservations()
        val deletions = FixtureDeletions()
        val visible = mutableStateOf(true)
        val parentProbe = mutableIntStateOf(0)
        val closeCalls = AtomicInteger(0)
        lateinit var model: SessionHistoryViewModel
    }

    @OptIn(InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
    private class SilentLiveState<T>(
        initial: T,
        private val emitted: MutableStateFlow<T> = MutableStateFlow(initial),
    ) : StateFlow<T> by emitted {
        @Volatile private var liveValue = initial
        override val value: T get() = liveValue

        fun setWithoutEmission(value: T) {
            liveValue = value
        }

        fun emitValue(value: T) {
            liveValue = value
            emitted.value = value
        }
    }

    private class FixtureSessions : PracticeSessionRepository {
        val values = MutableStateFlow(listOf(fixtureSession(SESSION_A), fixtureSession(SESSION_B)))
        val active = AtomicInteger(0)
        val writes = AtomicInteger(0)

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
        ): SessionStartResult {
            writes.incrementAndGet()
            error("Controlled observation history must not start a session")
        }

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult {
            writes.incrementAndGet()
            error("Controlled observation history must not finish a session")
        }
    }

    private class FixtureObservations : VehicleObservationRepository {
        val failReads = MutableStateFlow(false)
        val opens = AtomicInteger(0)
        val active = AtomicInteger(0)
        val writes = AtomicInteger(0)
        private val activeBySession = ConcurrentHashMap<String, AtomicInteger>()
        private val values =
            mapOf(
                SESSION_A to MutableStateFlow(fixtureRows(SESSION_A, 100L)),
                SESSION_B to MutableStateFlow(fixtureRows(SESSION_B, 200L)),
            )

        fun activeFor(sessionId: String): Int = activeBySession[sessionId]?.get() ?: 0

        override fun observe(sessionId: String): Flow<List<SessionVehicleObservation>> {
            val rows = checkNotNull(values[sessionId]) { "Unexpected controlled observation session" }
            opens.incrementAndGet()
            return flow {
                val perSession = activeBySession.getOrPut(sessionId) { AtomicInteger(0) }
                active.incrementAndGet()
                perSession.incrementAndGet()
                try {
                    emitAll(
                        combine(rows, failReads) { current, failed ->
                            check(!failed) { "Controlled observation read failure" }
                            current
                        },
                    )
                } finally {
                    perSession.decrementAndGet()
                    active.decrementAndGet()
                }
            }
        }

        override suspend fun append(
            sessionId: String,
            observation: VehicleObservation,
        ): VehicleObservationAppendResult {
            writes.incrementAndGet()
            error("Controlled observation history must not append a snapshot")
        }
    }

    private class FixtureDeletions : SessionDeletionRepository {
        val calls = AtomicInteger(0)

        override suspend fun prepare(
            expected: PracticeSession,
            canRead: () -> Boolean,
        ): SessionDeletionPreparation {
            calls.incrementAndGet()
            error("Controlled observation history must not prepare deletion")
        }

        override suspend fun delete(
            target: SessionDeletionTarget,
            canCommit: () -> Boolean,
        ): SessionDeletionResult {
            calls.incrementAndGet()
            error("Controlled observation history must not delete data")
        }
    }

    private companion object {
        const val TIMEOUT = 20_000L
        const val SESSION_A = "controlled_history_observation_a"
        const val SESSION_B = "controlled_history_observation_b"

        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        fun fixtureSession(sessionId: String): PracticeSession =
            PracticeSession(
                sessionId,
                PracticeSelection("controlled_goal", "controlled_course", 1, 1, "controlled_task"),
                goalTitle = "제어 입력 목표",
                courseTitle = if (sessionId == SESSION_A) "제어 입력 최근 코스" else "제어 입력 이전 코스",
                taskTitle = "제어 입력 과제",
                courseReviewStatus = CourseReviewStatus.DEMO,
                startedAtEpochMillis = if (sessionId == SESSION_A) 3_000L else 1_000L,
                endedAtEpochMillis = if (sessionId == SESSION_A) 4_000L else 2_000L,
            )

        fun fixtureRows(
            sessionId: String,
            idBase: Long,
        ): List<SessionVehicleObservation> =
            listOf(GearPosition.PARK, GearPosition.NEUTRAL, GearPosition.DRIVE).mapIndexed { index, gear ->
                SessionVehicleObservation(
                    idBase + index + 1,
                    sessionId,
                    VehicleObservation(
                        observedAtEpochMillis = 5_000L - index * 500L,
                        state =
                            VehicleState(
                                ConnectionState.CONNECTED,
                                VehicleReading.Available(if (sessionId == SESSION_B) GearPosition.REVERSE else gear),
                                VehicleReading.Available(TurnSignalState.OFF),
                                VehicleReading.Available(false),
                            ),
                    ),
                )
            }
    }
}
