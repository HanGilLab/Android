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
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
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
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger
import io.github.hangillab.hangil.core.designsystem.R as DesignR

/**
 * Controlled native receipt boundary, not actual DDS/SDK/Room data or a performance measurement.
 * The real Route, ViewModel and disclosure receive explicitly synthetic, non-emitting state.
 * ComponentCatalogActivity does not launch MainActivity or access the real application graph.
 */
@RunWith(AndroidJUnit4::class)
class SessionHistoryDisclosureBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()

    @Test fun staleDisclosureCallbackCannotExpandAfterSilentLiveDOrRememberDeniedIntent() {
        assumeTrue(
            "명시적인 제어 입력 native 경계 실행에서만 검사합니다",
            InstrumentationRegistry.getArguments().getString("manualHistoryDisclosureBoundary") == "true",
        )
        val vehicleState = SilentLiveState(parked())
        val restrictionState = SilentLiveState(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))
        val repository = FixtureSessions()
        val vehicle =
            object : VehicleGateway {
                override val state: StateFlow<VehicleState> = vehicleState
            }
        val restrictions =
            object : DrivingRestrictionsProvider {
                override val restrictions: StateFlow<DrivingRestrictions> = restrictionState
            }
        lateinit var model: SessionHistoryViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val factory =
                object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        assertTrue("실제 History ViewModel만 직접 생성해야 합니다", modelClass == SessionHistoryViewModel::class.java)
                        @Suppress("UNCHECKED_CAST")
                        return SessionHistoryViewModel(vehicle, restrictions, repository) as T
                    }
                }
            model = ViewModelProvider(compose.activity, factory)[SessionHistoryViewModel::class.java]
        }
        val visible = mutableStateOf(true)
        val parentProbe = mutableIntStateOf(0)
        val closeCalls = AtomicInteger(0)
        var primaryFailure: Throwable? = null
        try {
            compose.setContent {
                HanGilTheme {
                    if (visible.value) {
                        // Changing this callback identity re-runs the real parent without a key
                        // or disposing the disclosure's remembered expanded state.
                        val probe = parentProbe.intValue
                        val onClose =
                            remember(probe) {
                                {
                                    closeCalls.incrementAndGet()
                                    Unit
                                }
                            }
                        SessionHistoryRoute(onClose = onClose, viewModel = model)
                    }
                }
            }
            compose.waitUntil(20_000) {
                model.uiState.value is SessionHistoryUiState.Ready && nodes("history_summary_tasks").size == 1
            }
            compose.onNodeWithTag("history_summary_tasks", useUnmergedTree = true).performScrollTo()
            compose.waitForIdle()
            val originalReady = model.uiState.value as SessionHistoryUiState.Ready
            val button = nodes("history_summary_tasks").single()
            val collapsed = text(DesignR.string.parked_detail_collapsed)
            assertEquals(collapsed, button.config.getOrNull(SemanticsProperties.StateDescription))
            assertTrue("제어 전 summary 상세는 접혀 있어야 합니다", detailsAbsent())
            val oldAction = checkNotNull(button.config.getOrNull(SemanticsActions.OnClick)?.action)
            compose.runOnIdle {
                vehicleState.setWithoutEmission(parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE)))
                assertTrue("fixture의 실제 live value는 D여야 합니다", !model.canRead())
                assertEquals("무방출 변경 뒤 아직 옛 Ready가 남아야 경계를 재현합니다", originalReady, model.uiState.value)
                assertEquals(
                    "같은 UI turn에서 콜백 전에 접힌 표시를 확인합니다",
                    collapsed,
                    button.config.getOrNull(SemanticsProperties.StateDescription),
                )
                oldAction.invoke()
            }
            compose.waitForIdle()
            val deniedDetailsAbsent = detailsAbsent()
            val deniedButton = nodes("history_summary_tasks").singleOrNull()
            val deniedStateCollapsed =
                deniedButton == null ||
                    deniedButton.config.getOrNull(SemanticsProperties.StateDescription) == collapsed
            compose.runOnIdle {
                vehicleState.setWithoutEmission(parked())
                parentProbe.intValue++
            }
            compose.waitUntil(20_000) { nodes("history_summary_tasks").size == 1 }
            compose.waitForIdle()
            val returnedDetailsAbsent = detailsAbsent()
            val returnedStateCollapsed =
                nodes("history_summary_tasks")
                    .single()
                    .config
                    .getOrNull(SemanticsProperties.StateDescription) == collapsed
            assertTrue("P로 돌아온 fixture는 실제 live gate가 허용해야 합니다", model.canRead())
            assertTrue("옛 P 표시의 콜백은 최신 D에서 상세를 만들면 안 됩니다", deniedDetailsAbsent && deniedStateCollapsed)
            assertTrue("거부한 펼침 의도는 P 복귀 후 자동으로 살아나면 안 됩니다", returnedDetailsAbsent && returnedStateCollapsed)
            // Positive control: a fresh explicit allowed click still opens the real component.
            compose.onNodeWithTag("history_summary_tasks", useUnmergedTree = true).performScrollTo().performClick()
            compose.waitUntil(20_000) { nodes("history_summary_scope").size == 1 && nodes("history_summary_task_0").size == 1 }
            assertTrue(
                "허용된 명시 클릭은 fixture 과제를 그대로 표시해야 합니다",
                nodeTexts(nodes("history_summary_task_0").single()).contains(FIXTURE_TASK_TITLE),
            )
            assertEquals("fixture 경계는 세션 저장을 호출하면 안 됩니다", 0, repository.writes.get())
            assertEquals("제어용 콜백 세대 변경은 실제 닫기를 호출하지 않습니다", 0, closeCalls.get())
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            try {
                compose.runOnIdle {
                    vehicleState.setWithoutEmission(parked())
                    visible.value = false
                }
                compose.waitForIdle()
                compose.waitUntil(20_000) { repository.active.get() == 0 }
                assertEquals("모든 fixture 저장 호출은0이어야 합니다", 0, repository.writes.get())
                assertEquals("명시적인 합성 원본 목록을 바꾸면 안 됩니다", listOf(fixtureSession()), repository.values.value)
            } catch (cleanupFailure: Throwable) {
                val original = primaryFailure
                if (original == null) throw cleanupFailure
                original.addSuppressed(cleanupFailure)
            }
        }
    }

    private fun detailsAbsent(): Boolean =
        nodes("history_summary_scope").isEmpty() && nodes("history_summary_task_0").isEmpty() &&
            nodes("history_summary_task_count_0").isEmpty()

    private fun nodeTexts(node: SemanticsNode): List<String> =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text } + node.children.flatMap(::nodeTexts)

    private fun nodes(tag: String): List<SemanticsNode> =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun text(resource: Int): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resource)

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
    }

    private class FixtureSessions : PracticeSessionRepository {
        val values = MutableStateFlow(listOf(fixtureSession()))
        val writes = AtomicInteger(0)
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
        ): SessionStartResult {
            writes.incrementAndGet()
            error("Synthetic read-only history must not start a session")
        }

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult {
            writes.incrementAndGet()
            error("Synthetic read-only history must not finish a session")
        }
    }

    private companion object {
        const val FIXTURE_TASK_TITLE = "제어 입력 과제"

        fun parked(): VehicleState =
            VehicleState(
                connection = ConnectionState.CONNECTED,
                gear = VehicleReading.Available(GearPosition.PARK),
            )

        fun fixtureSession(): PracticeSession =
            PracticeSession(
                sessionId = "controlled_history_source",
                selection = PracticeSelection("controlled_goal", "controlled_course", 1, 1, "controlled_task"),
                goalTitle = "제어 입력 목표",
                courseTitle = "제어 입력 코스",
                taskTitle = FIXTURE_TASK_TITLE,
                courseReviewStatus = CourseReviewStatus.DEMO,
                startedAtEpochMillis = 1_000,
                endedAtEpochMillis = 2_000,
            )
    }
}
