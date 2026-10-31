package io.github.hangillab.hangil

import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
import io.github.hangillab.hangil.core.domain.BackgroundRecordingState
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.RecordingController
import io.github.hangillab.hangil.core.domain.VehicleRecordingState
import io.github.hangillab.hangil.feature.home.BackgroundRecordingRoute
import io.github.hangillab.hangil.feature.home.BackgroundRecordingViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import io.github.hangillab.hangil.feature.home.R as HomeR

/**
 * Controlled real Compose/lifecycle regression checks with a synthetic controller, source and
 * live gate. No real SDK, service, Room, permission flow or screenshot is used. A true synthetic
 * request result means only that this fixture recorded the call, never native recording success.
 * These checks cover the current implementation; they do not claim a prior RED reproduction.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundRecordingBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()

    @Test fun capturedEnableCannotReplayAcrossPauseResumeAndStop() =
        fixture { f ->
            val paused = clickAction(ENABLE)
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            invoke(paused)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitTag(ENABLE)
            invoke(paused)
            assertEquals(0, f.controller.requests.size)

            val stopped = clickAction(ENABLE)
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            invoke(stopped)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitTag(ENABLE)
            invoke(stopped)
            assertEquals(0, f.controller.requests.size)

            click(ENABLE)
            assertEquals(1, f.controller.requests.size)
            assertEquals(
                f.source.value,
                f.controller.requests
                    .single()
                    .source,
            )
            assertTrue(guard(f.controller.requests.single()))
            assertEquals(0, f.controller.stops.size)
            emit(f, BackgroundRecordingState.Active("controlled-lifecycle-run", requireNotNull(f.source.value).sessionId))
            waitTag(STOP)
            val pausedStop = clickAction(STOP)
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            invoke(pausedStop)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitTag(STOP)
            invoke(pausedStop)
            assertEquals(0, f.controller.stops.size)
            val stoppedStop = clickAction(STOP)
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            invoke(stoppedStop)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitTag(STOP)
            invoke(stoppedStop)
            assertEquals(0, f.controller.stops.size)
            click(STOP)
            assertEquals(listOf("controlled-lifecycle-run"), f.controller.stops.toList())
        }

    @Test fun sourceReplacementAndDisposalRetireCapturedEnableAndPendingGuard() =
        fixture { f ->
            val oldSource = clickAction(ENABLE)
            click(ENABLE)
            assertEquals(1, f.controller.requests.size)
            val sourcePending = f.controller.requests.single()
            assertTrue(guard(sourcePending))
            val replacement = source("controlled-source-b")
            compose.runOnIdle { f.source.value = replacement }
            waitTag(ENABLE)
            invoke(oldSource)
            assertFalse(guard(sourcePending))
            assertEquals(1, f.controller.requests.size)

            click(ENABLE)
            assertEquals(2, f.controller.requests.size)
            val pending = f.controller.requests.last()
            assertEquals(replacement, pending.source)
            assertTrue(guard(pending))
            val beforeDispose = clickAction(ENABLE)
            compose.runOnIdle { f.visible.value = false }
            compose.waitForIdle()
            assertFalse(guard(pending))
            invoke(beforeDispose)
            assertEquals(2, f.controller.requests.size)

            compose.runOnIdle { f.visible.value = true }
            waitTag(ENABLE)
            invoke(oldSource)
            invoke(beforeDispose)
            assertFalse(guard(pending))
            assertEquals(2, f.controller.requests.size)
            click(ENABLE)
            assertEquals(3, f.controller.requests.size)
            assertEquals(
                replacement,
                f.controller.requests
                    .last()
                    .source,
            )
            assertTrue(guard(f.controller.requests.last()))
        }

    @Test fun displayReceiptRejectsDisabledStartingActiveDisabledAbaAndOldStop() =
        fixture { f ->
            val disabled = clickAction(ENABLE)
            emit(f, BackgroundRecordingState.Starting("controlled-run-a", requireNotNull(f.source.value).sessionId))
            waitTag(STOP)
            invoke(disabled)
            val startingStop = clickAction(STOP)
            emit(f, BackgroundRecordingState.Active("controlled-run-a", requireNotNull(f.source.value).sessionId))
            waitTag(STOP)
            invoke(disabled)
            invoke(startingStop)
            assertEquals(0, f.controller.requests.size)
            assertEquals(0, f.controller.stops.size)
            val activeStop = clickAction(STOP)

            emit(f, BackgroundRecordingState.Disabled)
            waitTag(ENABLE)
            invoke(disabled)
            invoke(startingStop)
            invoke(activeStop)
            assertEquals(0, f.controller.requests.size)
            assertEquals(0, f.controller.stops.size)
            click(ENABLE)
            assertEquals(1, f.controller.requests.size)

            emit(f, BackgroundRecordingState.Starting("controlled-run-b", requireNotNull(f.source.value).sessionId))
            emit(f, BackgroundRecordingState.Active("controlled-run-b", requireNotNull(f.source.value).sessionId))
            waitTag(STOP)
            invoke(startingStop)
            invoke(activeStop)
            assertEquals(0, f.controller.stops.size)
            val currentStop = clickAction(STOP)
            click(STOP)
            assertEquals(listOf("controlled-run-b"), f.controller.stops.toList())
            emit(f, BackgroundRecordingState.Disabled)
            waitTag(ENABLE)
            invoke(currentStop)
            assertEquals(listOf("controlled-run-b"), f.controller.stops.toList())
            assertFalse(guard(f.controller.requests.single()))
        }

    @Test fun pendingStartGuardSurvivesStartingAcknowledgementButChecksLiveGateAndHost() =
        fixture { f ->
            click(ENABLE)
            assertEquals(1, f.controller.requests.size)
            val pending = f.controller.requests.single()
            assertTrue(guard(pending))
            emit(f, BackgroundRecordingState.Starting("controlled-pending-run", requireNotNull(f.source.value).sessionId))
            waitTag(STOP)
            assertTrue("같은 시작 요청은 Starting ACK의 새 표시 receipt만으로 무효가 되면 안 됩니다", guard(pending))

            compose.runOnIdle { f.allowed.set(false) }
            assertFalse("재구성 없이 바뀐 실제 gate도 전달한 canStart가 다시 확인해야 합니다", guard(pending))
            compose.runOnIdle { f.allowed.set(true) }
            assertTrue(guard(pending))
            emit(f, BackgroundRecordingState.Active("controlled-pending-run", requireNotNull(f.source.value).sessionId))
            assertTrue(guard(pending))
            assertEquals(1, f.controller.requests.size)

            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            assertFalse(guard(pending))
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            waitTag(STOP)
            assertFalse("복귀한 호스트는 이전 canStart에 새 RESUMED 소유권을 빌려주면 안 됩니다", guard(pending))
            assertEquals(1, f.controller.requests.size)
        }

    @Test fun foreignSessionAllowsOnlyFreshExactCleanupStopAcrossSourceAndDisposal() =
        fixture { f ->
            emit(f, BackgroundRecordingState.Active("controlled-foreign-run-a", "controlled-foreign-session"))
            waitTag(STOP)
            assertTrue(nodes(ENABLE).isEmpty())
            val oldSourceStop = clickAction(STOP)
            compose.runOnIdle { f.source.value = source("controlled-source-b") }
            waitTag(STOP)
            invoke(oldSourceStop)
            assertEquals(0, f.controller.stops.size)

            val disposedStop = clickAction(STOP)
            compose.runOnIdle { f.visible.value = false }
            compose.waitForIdle()
            invoke(disposedStop)
            compose.runOnIdle { f.visible.value = true }
            waitTag(STOP)
            invoke(disposedStop)
            assertEquals(0, f.controller.stops.size)
            assertTrue(nodes(ENABLE).isEmpty())
            val foreignA = clickAction(STOP)
            click(STOP)
            assertEquals(listOf("controlled-foreign-run-a"), f.controller.stops.toList())

            emit(f, BackgroundRecordingState.Active("controlled-foreign-run-b", "controlled-foreign-session"))
            waitTag(STOP)
            invoke(foreignA)
            assertEquals(listOf("controlled-foreign-run-a"), f.controller.stops.toList())
            assertTrue(nodes(ENABLE).isEmpty())
            click(STOP)
            assertEquals(listOf("controlled-foreign-run-a", "controlled-foreign-run-b"), f.controller.stops.toList())
            emit(f, BackgroundRecordingState.Interrupted("controlled-foreign-session"))
            assertTrue(nodes(ENABLE).isEmpty())
            assertTrue(nodes(STOP).isEmpty())
            assertEquals(0, f.controller.requests.size)
        }

    @Test fun nullSourceKeepsExactOwnedRunCleanupWithoutEnableAcrossGateAndHostChanges() =
        fixture { f ->
            val original = requireNotNull(f.source.value)
            val retiredEnable = clickAction(ENABLE)
            compose.runOnIdle { f.source.value = null }
            compose.waitForIdle()
            invoke(retiredEnable)
            assertTrue("활성 연습이 없으면 새 기록을 켤 수 없어야 합니다", nodes(ENABLE).isEmpty())
            assertEquals(0, f.controller.requests.size)
            val staleStops = mutableListOf<() -> Boolean>()
            val expectedStops = mutableListOf<String>()
            val ownedPhases =
                listOf<BackgroundRecordingState>(
                    BackgroundRecordingState.Active("controlled-null-active-run", original.sessionId),
                    BackgroundRecordingState.Failed(
                        "controlled-null-failed-run",
                        original.sessionId,
                        BackgroundRecordingState.Reason.PERSISTENCE,
                    ),
                )
            for (phase in ownedPhases) {
                val exactRun =
                    when (phase) {
                        is BackgroundRecordingState.Active -> phase.runId
                        is BackgroundRecordingState.Failed -> requireNotNull(phase.runId)
                        else -> error("Controlled null-source fixture accepts only owned Active or Failed")
                    }
                emit(f, phase)
                waitTag(STOP)
                assertTrue("소유 run 정리 화면에서 새 기록을 켜면 안 됩니다", nodes(ENABLE).isEmpty())
                assertEquals(
                    InstrumentationRegistry.getInstrumentation().targetContext.getString(HomeR.string.background_recording_cleanup),
                    nodes("background_recording_state")
                        .single()
                        .config[SemanticsProperties.Text]
                        .single()
                        .text,
                )
                invoke(retiredEnable)
                assertEquals(0, f.controller.requests.size)
                val oldStop = clickAction(STOP)
                staleStops.add(oldStop)
                compose.runOnIdle { f.allowed.set(false) }
                invoke(oldStop)
                assertEquals(expectedStops, f.controller.stops.toList())
                compose.runOnIdle { f.allowed.set(true) }
                compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
                invoke(oldStop)
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                waitTag(STOP)
                invoke(oldStop)
                assertEquals("복귀한 호스트는 이전 정리 콜백을 되살리면 안 됩니다", expectedStops, f.controller.stops.toList())
                click(STOP)
                expectedStops.add(exactRun)
                assertEquals("새로 표시된 정리 버튼만 정확한 소유 run을 중지해야 합니다", expectedStops, f.controller.stops.toList())
                assertEquals(0, f.controller.requests.size)
            }
            emit(f, BackgroundRecordingState.Disabled)
            assertTrue("소유 run 정리가 끝난 null source에는 새 시작을 표시하면 안 됩니다", nodes(ENABLE).isEmpty())
            assertTrue(nodes(STOP).isEmpty())
            invoke(retiredEnable)
            staleStops.forEach(::invoke)
            assertEquals(0, f.controller.requests.size)
            assertEquals(expectedStops, f.controller.stops.toList())
        }

    private fun fixture(block: (Fixture) -> Unit) {
        assumeTrue(
            "실제 SDK를 호출하지 않는 controlled native 경계 검사만 명시적으로 실행합니다",
            InstrumentationRegistry.getArguments().getString("manualBackgroundRecordingBoundary") == "true",
        )
        val f = Fixture()
        val activity = compose.activity
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val factory =
                object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T {
                        check(modelClass == BackgroundRecordingViewModel::class.java)
                        @Suppress("UNCHECKED_CAST")
                        return BackgroundRecordingViewModel(f.controller) as T
                    }
                }
            f.model = ViewModelProvider(activity, factory)[BackgroundRecordingViewModel::class.java]
        }
        var primary: Throwable? = null
        try {
            compose.setContent {
                HanGilTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        if (f.visible.value) {
                            BackgroundRecordingRoute(source = f.source.value, canPresent = { f.allowed.get() }, viewModel = f.model)
                        }
                    }
                }
            }
            waitTag(ENABLE)
            assertEquals(0, f.controller.requests.size)
            assertEquals(0, f.controller.stops.size)
            block(f)
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply { putString("stream", "BG_RECORDING_CONTROLLED_BOUNDARY_COMPLETED\n") },
            )
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            try {
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                compose.runOnIdle { f.visible.value = false }
                compose.waitForIdle()
            } catch (cleanup: Throwable) {
                val original = primary
                if (original == null) throw cleanup else original.addSuppressed(cleanup)
            }
        }
    }

    private fun emit(
        f: Fixture,
        state: BackgroundRecordingState,
    ) {
        val oldRevision = f.model.state.value.revision
        compose.runOnIdle { f.controller.background.value = state }
        compose.waitUntil(20_000) { f.model.state.value.revision > oldRevision && f.model.state.value.background == state }
        compose.waitForIdle()
    }

    private fun guard(request: SyntheticController.Request): Boolean {
        var allowed = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync { allowed = request.canStart() }
        return allowed
    }

    private fun nodes(tag: String) =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun waitTag(tag: String) = compose.waitUntil(20_000) { nodes(tag).size == 1 }

    private fun click(tag: String) {
        waitTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun clickAction(tag: String): () -> Boolean {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
        return checkNotNull(nodes(tag).single().config[SemanticsActions.OnClick].action)
    }

    private fun invoke(action: () -> Boolean) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }
        compose.waitForIdle()
    }

    private class Fixture {
        val source = mutableStateOf<PracticeSession?>(source("controlled-source-a"))
        val visible = mutableStateOf(true)
        val allowed = AtomicBoolean(true)
        val controller = SyntheticController()
        lateinit var model: BackgroundRecordingViewModel
    }

    private class SyntheticController : RecordingController {
        override val state = MutableStateFlow<VehicleRecordingState>(VehicleRecordingState.Paused)
        override val background = MutableStateFlow<BackgroundRecordingState>(BackgroundRecordingState.Disabled)
        val requests = CopyOnWriteArrayList<Request>()
        val stops = CopyOnWriteArrayList<String>()

        override fun requestBackground(
            expected: PracticeSession,
            canStart: () -> Boolean,
        ): Boolean {
            requests.add(Request(expected, canStart))
            return true
        }

        override fun stopBackground(expectedRunId: String): Boolean {
            stops.add(expectedRunId)
            return true
        }

        override suspend fun recordWhileStarted(): Unit = error("Controlled fixture must not start real foreground recording")

        override fun retry(): Boolean = error("Controlled fixture must not retry real recording")

        override suspend fun recordInService(
            runId: String,
            sessionId: String,
        ): Unit = error("Controlled fixture must not run a native service")

        override fun startFailed(runId: String): Unit = error("Controlled fixture must not report a real native service result")

        data class Request(
            val source: PracticeSession,
            val canStart: () -> Boolean,
        )
    }

    private companion object {
        const val ENABLE = "background_recording_enable"
        const val STOP = "background_recording_stop"

        fun source(id: String) =
            PracticeSession(
                sessionId = id,
                selection = PracticeSelection("controlled-goal", "controlled-course", 1, 1, "controlled-task"),
                goalTitle = "검사 목표",
                courseTitle = "검사 코스",
                taskTitle = "검사 과제",
                courseReviewStatus = CourseReviewStatus.DEMO,
                startedAtEpochMillis = 1,
                endedAtEpochMillis = null,
            )
    }
}
