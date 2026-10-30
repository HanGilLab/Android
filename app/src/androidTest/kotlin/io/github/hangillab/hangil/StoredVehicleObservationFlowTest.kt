package io.github.hangillab.hangil

import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
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
import io.github.hangillab.hangil.core.domain.TurnSignalState
import io.github.hangillab.hangil.core.domain.VehicleObservationSource
import io.github.hangillab.hangil.core.domain.VehicleReading
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import io.github.hangillab.hangil.core.designsystem.R as DesignR
import io.github.hangillab.hangil.feature.home.R as HomeR

/**
 * Read-only validation of already stored SDK snapshots, selected with -e manualStoredReview true.
 * The external runner must first verify the dedicated debug app has no active session and has a
 * completed session with real P and D observations. It changes only DDS gear at the named phases
 * and restores the original gear/settings even after failure. This test never creates fixtures,
 * starts/finishes a session, appends observations or changes permissions. Existing rows are read
 * and compared in full; their identifiers, timestamps and values are never emitted as diagnostics.
 * Empty/error fixtures and unsupported independent UX combinations are not claimed by this test.
 */
@RunWith(AndroidJUnit4::class)
class StoredVehicleObservationFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun storedSdkSnapshotsAreReadOnlyAcrossDetailsLifecycleAndDrivingLock() {
        assumeTrue(
            "기존 자료와 외부 DDS 복구를 준비한 명시적 단일 실행에서만 검증합니다",
            InstrumentationRegistry.getArguments().getString("manualStoredReview") == "true",
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(
            "전용 debug 앱에서만 기존 기록을 읽기 전용으로 검증합니다",
            instrumentation.targetContext.packageName == "io.github.hangillab.hangil.debug",
        )
        val graph =
            EntryPointAccessors.fromApplication(
                instrumentation.targetContext.applicationContext,
                RecordingTestEntryPoint::class.java,
            )
        runBlocking {
            val before = snapshot(graph)
            assertTrue("기존 활성 연습이 있으면 검증을 진행하지 않습니다", before.sessions.none { it.isActive })
            val latest = before.sessions.firstOrNull { !it.isActive }
            assertTrue("최근 종료 세션이 있어야 저장 기록을 검증할 수 있습니다", latest != null)
            val rows = before.observations.getValue(checkNotNull(latest).sessionId)
            assertTrue("기존 저장 관찰이 있어야 하며 검증용 행을 새로 만들지 않습니다", rows.size >= 2)
            assertTrue(
                "저장된 P와 D 관찰을 각각 대조할 수 있어야 합니다",
                rows.any { it.observation.state.gear == VehicleReading.Available(GearPosition.PARK) } &&
                    rows.any { it.observation.state.gear == VehicleReading.Available(GearPosition.DRIVE) },
            )
            assertTrue("실제 SDK 관찰 출처의 저장 행만 검증해야 합니다", rows.all { it.observation.source == VehicleObservationSource.SDK_STATE_OBSERVATION })
            assertTrue("저장 순서는 행 ID 증가 순서이며 시각으로 재정렬하면 안 됩니다", rows.zipWithNext().all { (first, second) -> first.id < second.id })

            // This real SDK observer only confirms externally selected gear; it does not prove
            // the product releases every SDK subscriber while its Activity is stopped.
            val sdkObserver = launch { graph.vehicle().state.collect {} }
            try {
                awaitGear(graph, GearPosition.PARK)
                waitFor("home_recent_details")
                assertAbsent("review_ready")
                touch("home_recent_details")
                waitFor("review_ready")
                assertStoredRow(rows.last(), rows.lastIndex, rows.size)
                assertControlEnabled("review_next", false)
                assertControlEnabled("review_previous", true)
                capture("stored-review", "review_next")

                for (index in rows.lastIndex - 1 downTo 0) {
                    touch("review_previous")
                    assertStoredRow(rows[index], index, rows.size)
                }
                assertControlEnabled("review_previous", false)
                for (index in 1..rows.lastIndex) {
                    touch("review_next")
                    assertStoredRow(rows[index], index, rows.size)
                }
                assertControlEnabled("review_next", false)

                touch("home_recent_details")
                assertAbsent("review_ready")
                touch("home_recent_details")
                waitFor("review_ready")
                assertStoredRow(rows.last(), rows.lastIndex, rows.size)
                assertPreserved(graph, before)

                compose.activityRule.scenario.recreate()
                waitFor("home_recent_details")
                expandDetailsIfCollapsed()
                waitFor("review_ready")
                assertStoredRow(rows.last(), rows.lastIndex, rows.size)
                assertPreserved(graph, before)

                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                instrumentation.waitForIdleSync()
                assertPreserved(graph, before)
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                awaitGear(graph, GearPosition.PARK)
                waitFor("home_recent_details")
                expandDetailsIfCollapsed()
                waitFor("review_ready")
                assertStoredRow(rows.last(), rows.lastIndex, rows.size)
                assertPreserved(graph, before)

                phase("STORED_REVIEW_AWAIT_D")
                awaitGear(graph, GearPosition.DRIVE)
                waitFor("home_locked_title")
                assertAbsent("review_ready")
                assertAbsent("home_recent_details")
                assertAbsent("review_previous")
                assertAbsent("review_next")
                capture("stored-review-driving", "home_locked_title")
                assertPreserved(graph, before)

                phase("STORED_REVIEW_AWAIT_P")
                awaitGear(graph, GearPosition.PARK)
                waitFor("home_recent_details")
                assertAbsent("review_ready")
                touch("home_recent_details")
                waitFor("review_ready")
                assertStoredRow(rows.last(), rows.lastIndex, rows.size)
                capture("stored-review-returned", "review_next")
                assertPreserved(graph, before)
                phase("STORED_REVIEW_COMPLETED")
            } finally {
                sdkObserver.cancelAndJoin()
                assertPreserved(graph, before)
            }
        }
    }

    private suspend fun snapshot(graph: RecordingTestEntryPoint): ExistingData =
        withTimeout(20_000) {
            val sessions = graph.sessions().observeSessions().first()
            ExistingData(
                graph.preparation().observeSelection().first(),
                sessions,
                sessions.associate { it.sessionId to graph.observations().observe(it.sessionId).first() },
            )
        }

    private suspend fun assertPreserved(
        graph: RecordingTestEntryPoint,
        before: ExistingData,
    ) {
        val after = snapshot(graph)
        assertTrue("기존 준비의 모든 필드가 유지되어야 합니다", after.preparation == before.preparation)
        assertTrue("모든 종료 세션의 필드와 저장 순서가 유지되어야 합니다", after.sessions == before.sessions)
        assertTrue("모든 세션의 관찰 행과 전체 필드가 추가, 삭제, 변경 없이 유지되어야 합니다", after.observations == before.observations)
        assertTrue("읽기 전용 검증 뒤 활성 세션이 생기면 안 됩니다", after.sessions.none { it.isActive })
    }

    private suspend fun awaitGear(
        graph: RecordingTestEntryPoint,
        expected: GearPosition,
    ) {
        withTimeout(90_000) {
            graph.vehicle().state.first { it.connection == ConnectionState.CONNECTED && it.gear == VehicleReading.Available(expected) }
        }
    }

    private fun expandDetailsIfCollapsed() {
        waitFor("home_recent_details")
        val description = nodes("home_recent_details").single().config.getOrNull(SemanticsProperties.StateDescription)
        if (description == text(DesignR.string.parked_detail_collapsed)) {
            touch("home_recent_details")
        } else {
            assertTrue("로딩과 펼침 상태를 혼동해 상세를 다시 접으면 안 됩니다", description == text(DesignR.string.parked_detail_expanded))
        }
    }

    private fun assertStoredRow(
        row: SessionVehicleObservation,
        index: Int,
        count: Int,
    ) {
        assertNodeText("review_position", text(HomeR.string.review_position, index + 1, count))
        assertNodeText("review_source", text(HomeR.string.review_source))
        assertNodeText(
            "review_gear",
            text(
                HomeR.string.vehicle_gear,
                reading(row.observation.state.gear) {
                    when (it) {
                        GearPosition.PARK -> "P"
                        GearPosition.REVERSE -> "R"
                        GearPosition.NEUTRAL -> "N"
                        GearPosition.DRIVE -> "D"
                        GearPosition.OTHER -> text(HomeR.string.gear_other)
                    }
                },
            ),
        )
        assertNodeText(
            "review_turn",
            text(
                HomeR.string.vehicle_turn_signal,
                reading(row.observation.state.turnSignal) {
                    text(
                        when (it) {
                            TurnSignalState.OFF -> HomeR.string.turn_signal_off
                            TurnSignalState.LEFT -> HomeR.string.turn_signal_left
                            TurnSignalState.RIGHT -> HomeR.string.turn_signal_right
                        },
                    )
                },
            ),
        )
        assertNodeText(
            "review_brake",
            text(
                HomeR.string.vehicle_brake,
                reading(row.observation.state.brakePressed) {
                    text(if (it) HomeR.string.brake_pressed else HomeR.string.brake_released)
                },
            ),
        )
        assertNodeText(
            "review_connection",
            text(
                HomeR.string.review_connection,
                text(
                    when (row.observation.state.connection) {
                        ConnectionState.DISCONNECTED -> HomeR.string.connection_disconnected
                        ConnectionState.CONNECTING -> HomeR.string.connection_connecting
                        ConnectionState.CONNECTED -> HomeR.string.connection_connected
                        ConnectionState.UNAVAILABLE -> HomeR.string.connection_unavailable
                        ConnectionState.PERMISSION_DENIED -> HomeR.string.connection_permission_denied
                        ConnectionState.ERROR -> HomeR.string.connection_error
                    },
                ),
            ),
        )
        val observedAt =
            DateTimeFormatter
                .ofLocalizedDateTime(
                    FormatStyle.MEDIUM,
                ).withLocale(
                    Locale.getDefault(),
                ).withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(row.observation.observedAtEpochMillis))
        assertNodeText("review_observed_at", text(HomeR.string.review_observed_at, observedAt))
        listOf("review_empty", "review_failed", "review_retry", "review_unavailable").forEach(::assertAbsent)
    }

    private fun <T : Any> reading(
        value: VehicleReading<T>,
        label: (T) -> String,
    ): String =
        when (value) {
            is VehicleReading.Available -> label(value.value)
            VehicleReading.Unknown -> text(HomeR.string.reading_unknown)
            VehicleReading.Unsupported -> text(HomeR.string.reading_unsupported)
            VehicleReading.PermissionDenied -> text(HomeR.string.connection_permission_denied)
            VehicleReading.Error -> text(HomeR.string.connection_error)
        }

    private fun text(
        resource: Int,
        vararg arguments: Any,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resource, *arguments)

    /** Compare privately with fixed assertion messages: no row values or timestamps in reports. */
    private fun assertNodeText(
        tag: String,
        expected: String,
    ) {
        waitFor(tag)
        val node = nodes(tag).single()
        assertTrue("표시된 저장 필드가 실제 저장 원값과 일치해야 합니다 ($tag)", nodeTexts(node).contains(expected))
    }

    private fun nodeTexts(node: SemanticsNode): List<String> =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text } + node.children.flatMap(::nodeTexts)

    private fun assertControlEnabled(
        tag: String,
        enabled: Boolean,
    ) {
        waitFor(tag)
        assertTrue("행 경계의 이전/다음 조작 가능 여부가 맞아야 합니다", nodes(tag).single().config.contains(SemanticsProperties.Disabled) != enabled)
    }

    private fun nodes(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun exists(tag: String): Boolean = nodes(tag).size == 1

    private fun assertAbsent(tag: String) {
        assertTrue("잠금 또는 접힘 상태에서 상세 조작을 숨겨야 합니다 ($tag)", nodes(tag).isEmpty())
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(20_000) { exists(tag) }
    }

    private fun touch(tag: String) {
        waitFor(tag)
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }

    private fun phase(name: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "$name\n") })
    }

    private fun capture(
        name: String,
        visibleTag: String,
    ) {
        compose.onNodeWithTag(visibleTag).performScrollTo()
        compose.waitForIdle()
        if (visibleTag == "review_next") {
            listOf("review_source", "review_position", "review_gear", "review_turn", "review_brake").forEach {
                compose.onNodeWithTag(it).assertIsDisplayed()
            }
        }
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
    )
}
