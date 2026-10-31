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
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
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

/** Controlled catalog-host UI with synthetic sessions/P/UX/notes, never the SDK or original Room. */
@RunWith(AndroidJUnit4::class)
class SessionHistoryMemoBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()

    @Test fun silentDRejectsFrozenExpansionAndRetryAfterSilentP() =
        withFixture { f ->
            val oldExpansion = clickAction("history_memo")
            val before = f.memos.opens.get()
            denyAndRecover(f, oldExpansion)
            assertEquals(before, f.memos.opens.get())
            invoke(oldExpansion)
            assertClosed(f)
            open(f, "A original\nsecond line")
            compose.runOnIdle { f.memos.failed.value = true }
            waitFor("history_memo_retry")
            val retry = clickAction("history_memo_retry")
            compose.runOnIdle { f.memos.failed.value = false }
            val calls = f.memos.opens.get()
            denyAndRecover(f, retry)
            invoke(retry)
            assertClosed(f)
            assertEquals(calls, f.memos.opens.get())
            open(f, "A original\nsecond line")
            assertEquals(calls + 1, f.memos.opens.get())
        }

    @Test fun sourceAndSelectionChangesRetireFrozenRetryWithoutBorrowingAnotherNote() =
        withFixture { f ->
            open(f, "A original\nsecond line")
            compose.runOnIdle { f.memos.failed.value = true }
            waitFor("history_memo_retry")
            val retry = clickAction("history_memo_retry")
            compose.runOnIdle {
                f.memos.failed.value = false
                f.sessions.values.value = listOf(source("a").copy(taskTitle = "changed metadata"), source("b"))
            }
            waitReady(f)
            assertClosed(f)
            val calls = f.memos.opens.get()
            invoke(retry)
            assertClosed(f)
            assertEquals(calls, f.memos.opens.get())
            open(f, "A original\nsecond line")
            touch("history_older")
            compose.waitUntil(TIMEOUT) { (f.model.uiState.value as? SessionHistoryUiState.Ready)?.session?.sessionId == "b" }
            assertClosed(f)
            invoke(retry)
            assertClosed(f)
            open(f, "B original")
            assertText("history_memo_text", "B original")
        }

    @Test fun stoppedAndClosedHostsCannotReplayOldMemoActions() =
        withFixture { f ->
            val expansion = clickAction("history_memo")
            open(f, "A original\nsecond line")
            compose.runOnIdle { f.memos.failed.value = true }
            waitFor("history_memo_retry")
            val retry = clickAction("history_memo_retry")
            compose.runOnIdle { f.memos.failed.value = false }
            val calls = f.memos.opens.get()
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            compose.waitUntil(TIMEOUT) { f.memos.active.get() == 0 }
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                expansion()
                retry()
            }
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitReady(f)
            invoke(expansion)
            invoke(retry)
            assertClosed(f)
            assertEquals(calls, f.memos.opens.get())
            open(f, "A original\nsecond line")
            touch("history_close")
            compose.waitUntil(TIMEOUT) { f.memos.active.get() == 0 }
            invoke(expansion)
            invoke(retry)
            compose.runOnIdle { f.visible.value = true }
            waitReady(f)
            assertClosed(f)
            open(f, "A original\nsecond line")
        }

    @Test fun aRealNullReceiptHasAnEmptyPanelAndNeverAnEditorOrSdkSource() =
        withFixture { f ->
            compose.runOnIdle { f.memos.notes.value = mapOf("a" to null, "b" to SessionMemo("b", "B original", "b1")) }
            touch("history_memo")
            waitFor("history_memo_empty")
            assertTrue(nodes("history_memo_text").isEmpty())
            assertTrue(nodes("history_memo_source").isEmpty())
            assertTrue(nodes("memo_textfield").isEmpty())
            assertTrue(nodes("memo_save").isEmpty())
            touch("history_older")
            compose.waitUntil(TIMEOUT) { (f.model.uiState.value as? SessionHistoryUiState.Ready)?.session?.sessionId == "b" }
            open(f, "B original")
        }

    private fun withFixture(block: (Fixture) -> Unit) {
        assumeTrue(
            "합성 입력 native 메모 경계만 명시적으로 실행합니다",
            InstrumentationRegistry.getArguments().getString("manualHistoryMemoBoundary") == "true",
        )
        val f = Fixture()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val factory =
                object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        assertEquals(SessionHistoryViewModel::class.java, modelClass)
                        @Suppress("UNCHECKED_CAST")
                        return SessionHistoryViewModel(
                            object : VehicleGateway {
                                override val state: StateFlow<VehicleState> = f.live
                            },
                            object : DrivingRestrictionsProvider {
                                override val restrictions =
                                    MutableStateFlow(
                                        DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED),
                                    )
                            },
                            f.sessions,
                            object : SessionDeletionRepository {
                                override suspend fun prepare(
                                    expected: PracticeSession,
                                    canRead: () -> Boolean,
                                ): SessionDeletionPreparation = error("Read-only memo history must not prepare deletion")

                                override suspend fun delete(
                                    target: SessionDeletionTarget,
                                    canCommit: () -> Boolean,
                                ): SessionDeletionResult = error("Read-only memo history must not delete sessions")
                            },
                            object : VehicleObservationRepository {
                                override fun observe(sessionId: String): Flow<List<SessionVehicleObservation>> =
                                    error("Collapsed vehicle observations must not query")

                                override suspend fun append(
                                    sessionId: String,
                                    observation: VehicleObservation,
                                ): VehicleObservationAppendResult = error("Read-only history must not append observations")
                            },
                            f.memos,
                            UnexpectedHistoryRecordingRunRepository,
                            UnexpectedHistoryCourseCompletionRepository,
                        ) as T
                    }
                }
            f.model = ViewModelProvider(compose.activity, factory)[SessionHistoryViewModel::class.java]
        }
        try {
            compose.setContent { HanGilTheme { if (f.visible.value) SessionHistoryRoute({ f.visible.value = false }, f.model) } }
            waitReady(f)
            assertClosed(f)
            block(f)
        } finally {
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.runOnIdle {
                f.live.emitValue(parked())
                f.visible.value = false
            }
            compose.waitUntil(TIMEOUT) { f.memos.active.get() == 0 }
            assertEquals("메모 열람은 쓰기/삭제를 호출하면 안 됩니다", 0, f.memos.writes.get())
            assertEquals("메모 열람은 세션을 만들거나 끝내면 안 됩니다", 0, f.sessions.writes.get())
        }
    }

    private fun denyAndRecover(
        f: Fixture,
        action: () -> Boolean,
    ) {
        val old = (f.model.uiState.value as SessionHistoryUiState.Ready).generation
        compose.runOnIdle {
            f.live.setWithoutEmission(parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE)))
            action()
            f.live.setWithoutEmission(parked())
        }
        compose.waitForIdle()
        invoke(action)
        assertTrue(nodes("history_memo_text").isEmpty())
        compose.waitUntil(TIMEOUT) { f.memos.active.get() == 0 }
        compose.runOnIdle { f.live.emitValue(parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))) }
        compose.waitUntil(TIMEOUT) { f.model.uiState.value == SessionHistoryUiState.Locked }
        compose.runOnIdle { f.live.emitValue(parked()) }
        waitReady(f)
        assertTrue((f.model.uiState.value as SessionHistoryUiState.Ready).generation != old)
        assertClosed(f)
    }

    private fun open(
        f: Fixture,
        value: String,
    ) {
        waitReady(f)
        touch("history_memo")
        waitFor("history_memo_text")
        assertText("history_memo_text", value)
        assertText("history_memo_source", text(HomeR.string.memo_source))
        assertTrue(nodes("memo_textfield").isEmpty())
        assertTrue(nodes("memo_save").isEmpty())
        assertTrue(nodes("memo_delete").isEmpty())
    }

    private fun waitReady(f: Fixture) {
        compose.waitUntil(TIMEOUT) { f.model.uiState.value is SessionHistoryUiState.Ready }
        waitFor("history_ready")
    }

    private fun assertClosed(f: Fixture) {
        scroll("history_memo")
        assertEquals(
            text(DesignR.string.parked_detail_collapsed),
            nodes("history_memo").single().config.getOrNull(SemanticsProperties.StateDescription),
        )
        assertTrue(nodes("history_memo_text").isEmpty())
        assertTrue(nodes("history_memo_retry").isEmpty())
        assertEquals(0, f.memos.active.get())
    }

    private fun invoke(action: () -> Boolean) {
        compose.runOnIdle { action() }
        compose.waitForIdle()
    }

    private fun clickAction(tag: String): () -> Boolean {
        scroll(tag)
        return checkNotNull(
            nodes(tag)
                .single()
                .config
                .getOrNull(SemanticsActions.OnClick)
                ?.action,
        )
    }

    private fun assertText(
        tag: String,
        expected: String,
    ) {
        scroll(tag)
        assertTrue(nodeTexts(nodes(tag).single()).contains(expected))
    }

    private fun touch(tag: String) {
        scroll(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    private fun scroll(tag: String) {
        waitFor(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
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

    private fun text(resource: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resource)

    private class Fixture {
        val live = SilentLiveState(parked())
        val sessions = Sessions()
        val memos = Memos()
        val visible = mutableStateOf(true)
        lateinit var model: SessionHistoryViewModel
    }

    private class Sessions : PracticeSessionRepository {
        val values = MutableStateFlow(listOf(source("a"), source("b")))
        val writes = AtomicInteger(0)

        override fun observeSessions(): Flow<List<PracticeSession>> = values

        override suspend fun start(
            practice: PreparedPractice,
            startedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionStartResult {
            writes.incrementAndGet()
            error("Read-only")
        }

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult {
            writes.incrementAndGet()
            error("Read-only")
        }
    }

    private class Memos : SessionMemoRepository {
        val notes =
            MutableStateFlow<Map<String, SessionMemo?>>(
                mapOf("a" to SessionMemo("a", "A original\nsecond line", "a1"), "b" to SessionMemo("b", "B original", "b1")),
            )
        val failed = MutableStateFlow(false)
        val opens = AtomicInteger(0)
        val active = AtomicInteger(0)
        val writes = AtomicInteger(0)

        override fun observe(sessionId: String): Flow<SessionMemo?> =
            flow {
                opens.incrementAndGet()
                active.incrementAndGet()
                try {
                    emitAll(
                        combine(notes, failed) { values, failure ->
                            if (failure) error("Controlled read failure")
                            values[sessionId]
                        },
                    )
                } finally {
                    active.decrementAndGet()
                }
            }

        override suspend fun save(
            sessionId: String,
            text: String,
            expectedVersion: String?,
            canCommit: () -> Boolean,
        ): SessionMemoWriteResult {
            writes.incrementAndGet()
            error("Read-only")
        }

        override suspend fun delete(
            sessionId: String,
            expectedVersion: String,
            canCommit: () -> Boolean,
        ): SessionMemoWriteResult {
            writes.incrementAndGet()
            error("Read-only")
        }
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

    private companion object {
        const val TIMEOUT = 20_000L

        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        fun source(id: String) =
            PracticeSession(
                id,
                PracticeSelection("goal", "course", 1, 1, "task"),
                "stored goal",
                "stored course",
                "stored task",
                CourseReviewStatus.DEMO,
                100,
                200,
            )
    }
}
