package io.github.hangillab.hangil

import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationSource
import io.github.hangillab.hangil.core.domain.VehicleReading
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Actual SDK to Room validation on the dedicated debug app, selected with -e manualDDS true.
 * An approved external runner changes DDS only at the named phases and restores the original
 * vehicle state and settings even after failure. No permission or SDK state is replaced here.
 * The new session is finished and retained on success; existing preparation and rows are read only.
 * On failure, the runner must preserve evidence and finish only this test's new session when safe.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundVehicleRecordingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun actualSdkSnapshotsStopAndResumeWithForegroundSession() {
        assumeTrue(
            "실제 DDS 제어와 외부 복구를 준비한 명시적 단일 실행에서만 검증합니다",
            InstrumentationRegistry.getArguments().getString("manualDDS") == "true",
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(
            "전용 debug 앱에서만 기존 자료를 보존하며 검증합니다",
            instrumentation.targetContext.packageName == "io.github.hangillab.hangil.debug",
        )
        val graph =
            EntryPointAccessors.fromApplication(
                instrumentation.targetContext.applicationContext,
                RecordingTestEntryPoint::class.java,
            )
        runBlocking {
            val before = baseline(graph)
            assertTrue("기존 활성 연습이 있으면 새 연습을 만들지 않습니다", before.sessions.none { it.isActive })
            assertTrue("저장된 준비가 있어야 실제 UI로 시작할 수 있습니다", before.preparation != null)

            // Keep a real SDK subscription solely to observe the externally supplied background P.
            // This is not evidence that the product releases every SDK subscriber while stopped.
            val sdkObserver = launch { graph.vehicle().state.collect {} }
            try {
                awaitGear(graph, GearPosition.PARK)
                waitFor("session_start")
                touch("session_start")
                val active =
                    withTimeout(20_000) {
                        graph
                            .sessions()
                            .observeSessions()
                            .first { rows ->
                                rows.count { it.isActive } == 1 && rows.any { it.isActive && it.sessionId !in before.sessionIds }
                            }.single { it.isActive }
                    }
                awaitStoredGear(graph, active.sessionId, GearPosition.PARK)
                waitFor("vehicle_recording_status")
                capture("recording-active", "vehicle_recording_status")

                phase("RECORDING_AWAIT_D")
                awaitGear(graph, GearPosition.DRIVE)
                awaitStoredGear(graph, active.sessionId, GearPosition.DRIVE)
                waitFor("home_locked_title")
                compose.onNodeWithTag("session_finish").assertDoesNotExist()
                capture("recording-driving", "home_locked_title")

                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                instrumentation.waitForIdleSync()
                val stopped = stableObservations(graph, active.sessionId)
                phase("RECORDING_AWAIT_P_BACKGROUND")
                awaitGear(graph, GearPosition.PARK)
                assertNoAppend(graph, active.sessionId, stopped)

                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                awaitStoredGear(graph, active.sessionId, GearPosition.PARK, afterId = stopped.lastOrNull()?.id ?: 0)
                waitFor("session_finish")
                capture("recording-resumed", "vehicle_recording_status")
                compose.activityRule.scenario.recreate()
                waitFor("session_finish")
                assertTrue(
                    "재생성 뒤에도 같은 활성 세션 하나만 유지해야 합니다",
                    graph
                        .sessions()
                        .observeSessions()
                        .first()
                        .filter { it.isActive }
                        .map { it.sessionId } == listOf(active.sessionId),
                )
                capture("recording-recreated", "vehicle_recording_status")

                touch("session_finish")
                withTimeout(20_000) {
                    graph.sessions().observeSessions().first { rows ->
                        rows.any { it.sessionId == active.sessionId && !it.isActive }
                    }
                }
                val finished = stableObservations(graph, active.sessionId)
                val previousSignal =
                    graph
                        .vehicle()
                        .state.value.turnSignal
                phase("RECORDING_AWAIT_SIGNAL_AFTER_FINISH")
                withTimeout(90_000) {
                    graph.vehicle().state.first { state ->
                        state.connection == ConnectionState.CONNECTED &&
                            state.gear == VehicleReading.Available(GearPosition.PARK) &&
                            state.turnSignal is VehicleReading.Available &&
                            state.turnSignal != previousSignal
                    }
                }
                assertNoAppend(graph, active.sessionId, finished)
                phase("RECORDING_AWAIT_SIGNAL_RESET")
                withTimeout(90_000) {
                    graph.vehicle().state.first { state ->
                        state.connection == ConnectionState.CONNECTED &&
                            state.gear == VehicleReading.Available(GearPosition.PARK) &&
                            state.turnSignal == previousSignal
                    }
                }
                assertNoAppend(graph, active.sessionId, finished)
                assertTrue("실제 SDK에서 관찰한 상태만 기록 출처로 남겨야 합니다", finished.isNotEmpty())
                assertTrue(
                    "SDK 관찰 출처와 양수 앱 관찰 시각을 보존해야 합니다",
                    finished.all {
                        it.observation.source == VehicleObservationSource.SDK_STATE_OBSERVATION &&
                            it.observation.observedAtEpochMillis > 0
                    },
                )
                assertOriginalRowsPreserved(graph, before, active.sessionId)
                waitFor("session_latest_finished")
                capture("recording-finished", "session_latest_finished")
                phase("RECORDING_COMPLETED")
            } finally {
                sdkObserver.cancelAndJoin()
            }
        }
    }

    private suspend fun baseline(graph: RecordingTestEntryPoint): ExistingData {
        val sessions = graph.sessions().observeSessions().first()
        return ExistingData(
            graph.preparation().observeSelection().first(),
            sessions,
            sessions.associate { it.sessionId to graph.observations().observe(it.sessionId).first() },
        )
    }

    private suspend fun assertOriginalRowsPreserved(
        graph: RecordingTestEntryPoint,
        before: ExistingData,
        newSessionId: String,
    ) {
        val after = graph.sessions().observeSessions().first()
        assertTrue("기존 준비의 모든 필드는 그대로여야 합니다", graph.preparation().observeSelection().first() == before.preparation)
        assertTrue("기존 세션의 모든 필드와 순서를 보존해야 합니다", after.filter { it.sessionId in before.sessionIds } == before.sessions)
        assertTrue("이 검사가 만든 종료 세션 하나만 추가되어야 합니다", after.size == before.sessions.size + 1)
        assertTrue("새 세션은 안전한 UI 경로로 종료되어야 합니다", after.single { it.sessionId == newSessionId }.endedAtEpochMillis != null)
        assertTrue("새 활성 세션을 남기면 안 됩니다", after.none { it.isActive })
        for ((sessionId, rows) in before.observations) {
            assertTrue("기존 차량 관찰 행의 모든 필드를 보존해야 합니다", graph.observations().observe(sessionId).first() == rows)
        }
    }

    private suspend fun awaitGear(
        graph: RecordingTestEntryPoint,
        expected: GearPosition,
    ) {
        withTimeout(90_000) {
            graph.vehicle().state.first {
                it.connection == ConnectionState.CONNECTED && it.gear == VehicleReading.Available(expected)
            }
        }
    }

    private suspend fun awaitStoredGear(
        graph: RecordingTestEntryPoint,
        sessionId: String,
        expected: GearPosition,
        afterId: Long = 0,
    ) {
        withTimeout(90_000) {
            graph.observations().observe(sessionId).first { rows ->
                rows.any {
                    it.id > afterId && it.observation.state.connection == ConnectionState.CONNECTED &&
                        it.observation.state.gear == VehicleReading.Available(expected)
                }
            }
        }
    }

    /** A bounded quiet window lets any pre-stop write drain before externally changing gear. */
    private suspend fun stableObservations(
        graph: RecordingTestEntryPoint,
        sessionId: String,
    ): List<SessionVehicleObservation> =
        withTimeout(10_000) {
            var rows = graph.observations().observe(sessionId).first()
            var quietUntil = SystemClock.uptimeMillis() + 1_000
            while (SystemClock.uptimeMillis() < quietUntil) {
                delay(100)
                val next = graph.observations().observe(sessionId).first()
                if (next != rows) {
                    rows = next
                    quietUntil = SystemClock.uptimeMillis() + 1_000
                }
            }
            rows
        }

    private suspend fun assertNoAppend(
        graph: RecordingTestEntryPoint,
        sessionId: String,
        expected: List<SessionVehicleObservation>,
    ) {
        val changed =
            withTimeoutOrNull(1_500) {
                graph.observations().observe(sessionId).first { it != expected }
            }
        assertTrue("명시적 중지/종료 뒤 SDK 변화가 와도 관찰 행이 추가되면 안 됩니다", changed == null)
    }

    private fun touch(tag: String) {
        compose
            .onNodeWithTag(tag)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
    }

    private fun waitFor(tag: String) {
        var count = 0
        try {
            compose.waitUntil(20_000) {
                count =
                    compose
                        .onAllNodes(
                            androidx.compose.ui.test
                                .hasTestTag(tag),
                        ).fetchSemanticsNodes(atLeastOneRootRequired = false)
                        .size
                count == 1
            }
        } catch (failure: Throwable) {
            val markers =
                listOf("home_step_repeat", "home_step_active", "home_locked_title", "session_load_failed", "session_latest_finished")
            val counts =
                markers.joinToString { marker ->
                    val found =
                        compose
                            .onAllNodes(
                                androidx.compose.ui.test
                                    .hasTestTag(marker),
                                useUnmergedTree = true,
                            ).fetchSemanticsNodes(atLeastOneRootRequired = false)
                            .size
                    "$marker=$found"
                }
            InstrumentationRegistry.getInstrumentation().sendStatus(
                2,
                Bundle().apply {
                    putString("stream", "RECORDING_UI_WAIT_FAILURE target=$tag merged_count=$count $counts\n")
                },
            )
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            if (bitmap != null) {
                val directory = File(instrumentation.targetContext.cacheDir, "screenshots").apply { mkdirs() }
                File(directory, "recording-wait-diagnostic.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            throw failure
        }
    }

    private fun phase(name: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "$name\n") })
    }

    private fun capture(
        name: String,
        visibleTag: String,
    ) {
        compose.onNodeWithTag(visibleTag).performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val expectedPackage = instrumentation.targetContext.packageName
        compose.waitUntil(20_000) { automation.rootInActiveWindow?.packageName?.toString() == expectedPackage }
        automation.waitForIdle(500, 5_000)
        assertTrue("실제 앱 전면을 확인한 뒤 캡처해야 합니다", automation.rootInActiveWindow?.packageName?.toString() == expectedPackage)
        val screenshot = checkNotNull(automation.takeScreenshot())
        val directory = File(instrumentation.targetContext.cacheDir, "screenshots").apply { mkdirs() }
        try {
            File(directory, "$name.png").outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            screenshot.recycle()
        }
    }

    private data class ExistingData(
        val preparation: PracticeSelection?,
        val sessions: List<PracticeSession>,
        val observations: Map<String, List<SessionVehicleObservation>>,
    ) {
        val sessionIds get() = sessions.map { it.sessionId }.toSet()
    }
}
