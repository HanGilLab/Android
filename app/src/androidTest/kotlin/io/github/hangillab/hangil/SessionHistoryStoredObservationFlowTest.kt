package io.github.hangillab.hangil

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.TurnSignalState
import io.github.hangillab.hangil.core.domain.VehicleObservationSource
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
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
import java.util.UUID
import kotlin.math.abs
import io.github.hangillab.hangil.core.designsystem.R as DesignR
import io.github.hangillab.hangil.feature.home.R as HomeR

/**
 * Opt-in read-only validation of the real MainActivity, SDK gates and stored Room records.
 * Use manualHistoryStoredObservations=true, expectedFontScale=1.0|1.3 and expectedDark=false|true.
 * Before Activity launch the external runner must verify no active session, preserve the entire
 * database/settings/permissions and own DDS D/P restoration even after failure. This test never
 * creates records, edits data, deletes data, grants permissions or changes device configuration.
 * Existing empty records and stored observations are compared without printing their raw fields.
 * Captures are private, app-only evidence; they are not eligible for publication without review.
 * Old callbacks, late results, synthetic failures and independent UX combinations belong to the
 * controlled/JVM tests and are not claimed as actual SDK scenarios by this class.
 */
@RunWith(AndroidJUnit4::class)
class SessionHistoryStoredObservationFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val evidenceDirectory by lazy {
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "screenshots/history-stored-observations-${UUID.randomUUID()}",
        ).apply { check(mkdirs()) }
    }

    @Test fun allStoredHistoryObservationsRemainReadOnlyAcrossSelectionLifecycleAndDrivingLock() {
        assumeTrue(
            "기존 자료와 DDS 복구를 준비한 명시적 실행에서만 검증합니다",
            InstrumentationRegistry.getArguments().getString("manualHistoryStoredObservations") == "true",
        )
        assertEnvironment()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val graph =
            EntryPointAccessors.fromApplication(
                instrumentation.targetContext.applicationContext,
                RecordingTestEntryPoint::class.java,
            )
        runBlocking {
            val before = snapshot(graph)
            val ended = before.sessions.filterNot { it.isActive }
            assertOriginalPreconditions(before, ended)
            val pastIndex = ended.indices.drop(1).first { before.observations.getValue(ended[it].sessionId).isNotEmpty() }
            val past = ended[pastIndex]
            val pastRows = before.observations.getValue(past.sessionId)
            // Retained solely to observe the external DDS phases while the Activity is stopped.
            // It does not establish release of every SDK subscriber or background collection.
            val sdkObserver =
                launch {
                    combine(graph.vehicle().state, graph.restrictions().restrictions) { vehicle, restrictions ->
                        vehicle to restrictions
                    }.collect {}
                }
            var primaryFailure: Throwable? = null
            try {
                awaitAllowed(graph)
                waitFor("home_history")
                assertObservationContentAbsent()
                touch("home_history")
                var capturedNonempty = false
                var capturedEmpty = false
                for (index in ended.indices) {
                    if (index > 0) touch("history_older")
                    val record = ended[index]
                    assertHistoryRecord(record, index, ended.size)
                    assertDisclosureCollapsed("history_observations")
                    val rows = before.observations.getValue(record.sessionId)
                    touch("history_observations")
                    if (rows.isEmpty()) {
                        assertEmptyObservations()
                        if (!capturedEmpty) {
                            capture("history-observations-empty", "review_empty")
                            capturedEmpty = true
                        }
                    } else {
                        assertEveryStoredRow(rows)
                        if (!capturedNonempty) {
                            capture("history-observations-stored", "review_gear")
                            capturedNonempty = true
                        }
                    }
                    touch("history_observations")
                    assertDisclosureCollapsed("history_observations")
                }
                assertTrue("실제 빈 기록과 저장 관찰을 각각 확인해야 합니다", capturedEmpty && capturedNonempty)
                assertPreserved(graph, before)

                for (index in ended.lastIndex - 1 downTo 0) {
                    touch("history_newer")
                    assertHistoryRecord(ended[index], index, ended.size)
                    assertDisclosureCollapsed("history_observations")
                }
                selectOlder(ended, pastIndex)
                openStoredObservations(pastRows)
                compose.activityRule.scenario.recreate()
                awaitAllowed(graph)
                assertHistoryRecord(past, pastIndex, ended.size)
                assertDisclosureCollapsed("history_observations")
                openStoredObservations(pastRows)
                assertPreserved(graph, before)

                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                instrumentation.waitForIdleSync()
                assertPreserved(graph, before)
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                awaitAllowed(graph)
                assertHistoryRecord(past, pastIndex, ended.size)
                assertDisclosureCollapsed("history_observations")
                openStoredObservations(pastRows)
                assertPreserved(graph, before)

                touch("history_close")
                waitFor("home_history")
                assertObservationContentAbsent()
                touch("home_history")
                assertHistoryRecord(ended.first(), 0, ended.size)
                assertDisclosureCollapsed("history_observations")
                selectOlder(ended, pastIndex)
                openStoredObservations(pastRows)

                phase("HISTORY_OBSERVATION_AWAIT_D")
                awaitDrive(graph)
                waitFor("history_locked")
                listOf("history_ready", "history_observations", "history_course", "history_goal", "history_task")
                    .forEach(::assertAbsent)
                assertObservationContentAbsent()
                capture("history-observations-driving", "history_locked")
                assertPreserved(graph, before)

                phase("HISTORY_OBSERVATION_AWAIT_P")
                awaitAllowed(graph)
                assertHistoryRecord(past, pastIndex, ended.size)
                assertDisclosureCollapsed("history_observations")
                openStoredObservations(pastRows)
                capture("history-observations-returned", "review_gear")
                assertPreserved(graph, before)

                touch("history_close")
                waitFor("home_recent_details")
                assertObservationContentAbsent()
                assertTextVisible("session_latest_course", ended.first().courseTitle)
                assertDisclosureCollapsed("home_recent_details")
                touch("home_recent_details")
                val latestRows = before.observations.getValue(ended.first().sessionId)
                waitFor("review_ready")
                assertStoredRow(latestRows.last(), latestRows.lastIndex, latestRows.size)
                capture("home-latest-stored-observation", "review_gear")
                touch("home_recent_details")
                assertDisclosureCollapsed("home_recent_details")
                assertPreserved(graph, before)
            } catch (failure: Throwable) {
                primaryFailure = failure
                try {
                    capture("failure-current-app", null)
                } catch (captureFailure: Throwable) {
                    failure.addSuppressed(captureFailure)
                }
                throw failure
            } finally {
                try {
                    sdkObserver.cancelAndJoin()
                } catch (cleanupFailure: Throwable) {
                    val original = primaryFailure
                    if (original == null) throw cleanupFailure
                    original.addSuppressed(cleanupFailure)
                } finally {
                    try {
                        assertPreserved(graph, before)
                    } catch (preservationFailure: Throwable) {
                        val original = primaryFailure
                        if (original == null) throw preservationFailure
                        original.addSuppressed(preservationFailure)
                    }
                }
            }
            phase("HISTORY_OBSERVATION_COMPLETED")
        }
    }

    private fun assertEnvironment() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        assertTrue("전용 debug 앱에서만 원래 자료를 읽습니다", instrumentation.targetContext.packageName == "io.github.hangillab.hangil.debug")
        assertTrue("지정한 API34 환경에서만 검증합니다", Build.VERSION.SDK_INT == 34)
        val scaleArgument = arguments.getString("expectedFontScale")
        assertTrue("기본 또는 확대 글꼴의 명시적인 예상값이 필요합니다", scaleArgument == "1.0" || scaleArgument == "1.3")
        val darkArgument = arguments.getString("expectedDark")
        assertTrue("라이트 또는 다크의 명시적인 예상값이 필요합니다", darkArgument == "false" || darkArgument == "true")
        val configuration = compose.activity.resources.configuration
        assertTrue("실제 글꼴 설정이 실행 전제와 일치해야 합니다", abs(configuration.fontScale - checkNotNull(scaleArgument).toFloat()) <= 0.01f)
        val dark = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK
        assertTrue(
            "실제 테마 설정이 실행 전제와 일치해야 합니다",
            dark == if (darkArgument == "true") Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
        )
    }

    private fun assertOriginalPreconditions(
        before: ExistingData,
        ended: List<PracticeSession>,
    ) {
        assertTrue("기존 활성 연습이 있으면 검증을 진행하지 않습니다", before.sessions.none { it.isActive })
        assertTrue("이전 기록을 검증할 종료 세션이 두 개 이상 필요합니다", ended.size >= 2)
        assertTrue("저장된 세션 ID는 서로 달라야 합니다", ended.map { it.sessionId }.distinct().size == ended.size)
        assertTrue("과거 종료 세션에 이미 저장된 관찰이 필요합니다", ended.drop(1).any { before.observations.getValue(it.sessionId).isNotEmpty() })
        assertTrue("실제 빈 관찰 기록을 열람할 종료 세션이 필요합니다", ended.any { before.observations.getValue(it.sessionId).isEmpty() })
        assertTrue("실제 페이지 이동을 검증할 복수 관찰이 필요합니다", ended.any { before.observations.getValue(it.sessionId).size >= 2 })
        assertTrue("공통 본문 회귀 확인을 위해 최근 종료 세션에 저장 관찰이 필요합니다", before.observations.getValue(ended.first().sessionId).isNotEmpty())
        for (record in ended) {
            val rows = before.observations.getValue(record.sessionId)
            assertTrue("관찰은 해당 종료 세션에 연결돼 있어야 합니다", rows.all { it.sessionId == record.sessionId })
            assertTrue(
                "원래 저장 순서는 양수 ID의 증가 순서여야 합니다",
                rows.all { it.id > 0 } && rows.zipWithNext().all { (first, second) -> first.id < second.id },
            )
            assertTrue(
                "실제 저장 출처와 양수 앱 관찰 시각을 보존해야 합니다",
                rows.all {
                    it.observation.source == VehicleObservationSource.SDK_STATE_OBSERVATION &&
                        it.observation.observedAtEpochMillis > 0
                },
            )
        }
    }

    private suspend fun snapshot(graph: RecordingTestEntryPoint): ExistingData =
        withTimeout(20_000) {
            val sessions = graph.sessions().observeSessions().first()
            ExistingData(
                graph.preparation().observeSelection().first(),
                sessions,
                sessions.associate { it.sessionId to graph.observations().observe(it.sessionId).first() },
                sessions.associate { it.sessionId to graph.memos().observe(it.sessionId).first() },
            )
        }

    private suspend fun assertPreserved(
        graph: RecordingTestEntryPoint,
        before: ExistingData,
    ) {
        val after = snapshot(graph)
        // Fixed messages intentionally exclude row IDs, user text, field values and timestamps.
        assertTrue("원래 준비의 모든 필드를 보존해야 합니다", after.preparation == before.preparation)
        assertTrue("모든 종료 세션의 필드와 저장 생성 순서를 보존해야 합니다", after.sessions == before.sessions)
        assertTrue("모든 세션의 관찰 ID, 필드와 순서를 보존해야 합니다", after.observations == before.observations)
        assertTrue("모든 사용자 메모의 원문과 버전을 보존해야 합니다", after.memos == before.memos)
        assertTrue("읽기 전용 검사 뒤 활성 세션이 생기면 안 됩니다", after.sessions.none { it.isActive })
    }

    private suspend fun awaitAllowed(graph: RecordingTestEntryPoint) =
        withTimeout(90_000) {
            combine(graph.vehicle().state, graph.restrictions().restrictions) { vehicle, restrictions ->
                allowsPracticeInteraction(vehicle, restrictions)
            }.first { it }
        }

    private suspend fun awaitDrive(graph: RecordingTestEntryPoint) =
        withTimeout(90_000) {
            combine(graph.vehicle().state, graph.restrictions().restrictions) { vehicle, restrictions ->
                vehicle.connection == ConnectionState.CONNECTED &&
                    vehicle.gear == VehicleReading.Available(GearPosition.DRIVE) && !allowsPracticeInteraction(vehicle, restrictions)
            }.first { it }
        }

    /** Every move uses the actual UI and verifies its selected snapshot before moving again. */
    private fun selectOlder(
        ended: List<PracticeSession>,
        target: Int,
    ) {
        for (index in 1..target) {
            touch("history_older")
            assertHistoryRecord(ended[index], index, ended.size)
            assertDisclosureCollapsed("history_observations")
        }
    }

    private fun assertHistoryRecord(
        record: PracticeSession,
        index: Int,
        count: Int,
    ) {
        assertTrue("활성 연습을 종료 이력으로 해석하면 안 됩니다", !record.isActive)
        waitFor("history_ready")
        assertTextVisible("history_position", text(HomeR.string.history_position, index + 1, count))
        assertTextVisible("history_course", record.courseTitle)
        assertTextVisible("history_goal", record.goalTitle)
        assertTextVisible("history_task", record.taskTitle)
        assertTextVisible("history_started", text(HomeR.string.session_started_at, displayTime(record.startedAtEpochMillis)))
        assertTextVisible("history_finished", text(HomeR.string.session_finished_at, displayTime(checkNotNull(record.endedAtEpochMillis))))
        if (record.courseReviewStatus == CourseReviewStatus.DEMO) {
            assertTextVisible("history_demo", text(HomeR.string.demo_course))
        } else {
            assertAbsent("history_demo")
        }
        assertControlEnabled("history_newer", index > 0)
        assertControlEnabled("history_older", index < count - 1)
        listOf("history_empty", "history_failed", "history_loading", "history_selection_changed", "history_locked")
            .forEach(::assertAbsent)
    }

    private fun openStoredObservations(rows: List<SessionVehicleObservation>) {
        assertDisclosureCollapsed("history_observations")
        touch("history_observations")
        waitFor("review_ready")
        assertStoredRow(rows.last(), rows.lastIndex, rows.size)
    }

    private fun assertEveryStoredRow(rows: List<SessionVehicleObservation>) {
        waitFor("review_ready")
        assertStoredRow(rows.last(), rows.lastIndex, rows.size)
        assertControlEnabled("review_next", false)
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
    }

    private fun assertStoredRow(
        row: SessionVehicleObservation,
        index: Int,
        count: Int,
    ) {
        assertTextVisible("review_position", text(HomeR.string.review_position, index + 1, count))
        assertTextVisible("review_source", text(HomeR.string.review_source))
        assertTextVisible(
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
        assertTextVisible(
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
        assertTextVisible(
            "review_brake",
            text(
                HomeR.string.vehicle_brake,
                reading(row.observation.state.brakePressed) {
                    text(if (it) HomeR.string.brake_pressed else HomeR.string.brake_released)
                },
            ),
        )
        assertTextVisible(
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
        assertTextVisible("review_observed_at", text(HomeR.string.review_observed_at, displayTime(row.observation.observedAtEpochMillis)))
        assertControlEnabled("review_previous", index > 0)
        assertControlEnabled("review_next", index < count - 1)
        listOf("review_empty", "review_failed", "review_retry", "review_unavailable", "review_loading").forEach(::assertAbsent)
    }

    private fun assertEmptyObservations() {
        assertTextVisible("review_empty", text(HomeR.string.review_empty))
        listOf("review_ready", "review_failed", "review_retry", "review_loading", "review_unavailable", "review_previous", "review_next")
            .forEach(::assertAbsent)
    }

    private fun assertDisclosureCollapsed(tag: String) {
        scrollVisible(tag)
        assertTrue(
            "접힌 상세는 명시적인 새 펼침을 기다려야 합니다",
            nodes(tag).single().config.getOrNull(SemanticsProperties.StateDescription) == text(DesignR.string.parked_detail_collapsed),
        )
        assertObservationContentAbsent()
    }

    private fun assertObservationContentAbsent() {
        listOf(
            "history_observation_content",
            "review_ready",
            "review_empty",
            "review_failed",
            "review_loading",
            "review_unavailable",
            "review_source",
            "review_position",
            "review_gear",
            "review_connection",
            "review_turn",
            "review_brake",
            "review_observed_at",
            "review_previous",
            "review_next",
            "review_retry",
        ).forEach(::assertAbsent)
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

    private fun displayTime(time: Long): String =
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(time))

    private fun text(
        resource: Int,
        vararg arguments: Any,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resource, *arguments)

    private fun assertTextVisible(
        tag: String,
        expected: String,
    ) {
        scrollVisible(tag)
        val root = nodes(tag).single()
        assertTrue("표시 필드는 실제 저장 원문과 일치해야 합니다 ($tag)", nodeTexts(root).contains(expected))
        var found = false
        var fits = false
        val dimensions = JSONArray()
        compose.runOnIdle {
            val layouts = mutableListOf<TextLayoutResult>()
            descendants(root)
                .filter { node ->
                    node.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .any { it.text == expected }
                }.forEach { node ->
                    node.config
                        .getOrNull(SemanticsActions.GetTextLayoutResult)
                        ?.action
                        ?.invoke(layouts)
                }
            val matching = layouts.filter { it.layoutInput.text.text == expected }
            found = matching.isNotEmpty()
            fits = found && matching.all { textFits(it, expected) }
            matching.forEach { layout ->
                dimensions.put(
                    JSONObject().put("width", layout.size.width).put("height", layout.size.height).put("lines", layout.lineCount),
                )
            }
        }
        if (!found || !fits) {
            File(evidenceDirectory, "layout-failure-$tag.json").writeText(
                JSONObject().put("tag", tag).put("layouts", dimensions).toString(2),
            )
        }
        assertTrue("실제 원문 텍스트의 레이아웃을 확인해야 합니다 ($tag)", found)
        assertTrue("저장 원문을 말줄임이나 잘림 없이 읽을 수 있어야 합니다 ($tag)", fits)
    }

    /**
     * Same coverage/painted-box policy as SessionHistoryFlowTest. Compose semantics can report
     * didOverflowWidth for a wide paragraph container with a tight valid layoutSize, so that flag
     * alone is not the criterion. Native text geometry and the independent viewport must fit.
     */
    private fun textFits(
        layout: TextLayoutResult,
        expected: String,
    ): Boolean {
        if (expected.isEmpty() || layout.size.width <= 0 || layout.size.height <= 0 || layout.lineCount <= 0 ||
            layout.multiParagraph.didExceedMaxLines
        ) {
            return false
        }
        var covered = 0
        for (line in 0 until layout.lineCount) {
            val start = layout.getLineStart(line)
            val end = layout.getLineEnd(line, visibleEnd = false)
            if (start != covered || end < start || end > expected.length || layout.isLineEllipsized(line)) return false
            if (!inside(
                    layout.getLineLeft(line),
                    layout.getLineTop(line),
                    layout.getLineRight(line),
                    layout.getLineBottom(line),
                    layout.size,
                )
            ) {
                return false
            }
            covered = end
        }
        if (covered != expected.length) return false
        for (offset in expected.indices) {
            if (expected[offset].isWhitespace()) continue
            val bounds = layout.getBoundingBox(offset)
            if (!inside(bounds.left, bounds.top, bounds.right, bounds.bottom, layout.size)) return false
        }
        return true
    }

    private fun inside(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        size: IntSize,
    ): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() && left <= right && top <= bottom &&
            left >= -1f && top >= -1f && right <= size.width + 1f && bottom <= size.height + 1f

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)

    private fun nodeTexts(node: SemanticsNode): List<String> =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text } + node.children.flatMap(::nodeTexts)

    private fun nodes(tag: String): List<SemanticsNode> =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun waitFor(tag: String) = compose.waitUntil(20_000) { nodes(tag).size == 1 }

    private fun assertAbsent(tag: String) {
        assertTrue("잠금이나 접힘 상태에서 저장 관찰 상세를 남기면 안 됩니다 ($tag)", nodes(tag).isEmpty())
    }

    private fun assertControlEnabled(
        tag: String,
        enabled: Boolean,
    ) {
        scrollVisible(tag)
        assertTrue("기록 경계의 이동 가능 여부가 저장 순서와 일치해야 합니다 ($tag)", nodes(tag).single().config.contains(SemanticsProperties.Disabled) != enabled)
    }

    private fun touch(tag: String) {
        scrollVisible(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
        compose.waitForIdle()
    }

    private fun scrollVisible(tag: String) {
        waitFor(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
        compose.waitForIdle()
        compose.waitUntil(15_000) {
            val viewport = appViewport()
            val node = nodes(tag).singleOrNull()
            if (viewport == null || node == null || node.size.width <= 0 || node.size.height <= 0) {
                false
            } else {
                val bounds = node.boundsInWindow
                bounds.width >= node.size.width - 1 && bounds.height >= node.size.height - 1 &&
                    bounds.left + viewport.originX >= viewport.available.left - 1 &&
                    bounds.top + viewport.originY >= viewport.available.top - 1 &&
                    bounds.right + viewport.originX <= viewport.available.right + 1 &&
                    bounds.bottom + viewport.originY <= viewport.available.bottom + 1
            }
        }
    }

    private fun appViewport(): AppViewport? {
        var result: AppViewport? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val decor = compose.activity.window.decorView
            val screen = IntArray(2)
            val window = IntArray(2)
            decor.getLocationOnScreen(screen)
            decor.getLocationInWindow(window)
            val available = Rect(screen[0], screen[1], screen[0] + decor.width, screen[1] + decor.height)
            val visible = Rect()
            decor.getWindowVisibleDisplayFrame(visible)
            if (available.intersect(visible) && available.width() > 0 && available.height() > 0) {
                result = AppViewport(available, screen[0] - window[0], screen[1] - window[1])
            }
        }
        return result
    }

    /** Fixed state names and dimensions only; PNGs stay private and contain the actual app UI. */
    private fun capture(
        name: String,
        visibleTag: String?,
    ) {
        visibleTag?.let(::scrollVisible)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val expectedPackage = instrumentation.targetContext.packageName
        compose.waitUntil(20_000) { automation.rootInActiveWindow?.packageName?.toString() == expectedPackage }
        automation.waitForIdle(500, 5_000)
        assertTrue("실제 앱 전면을 확인한 뒤 캡처해야 합니다", automation.rootInActiveWindow?.packageName?.toString() == expectedPackage)
        val viewport = checkNotNull(appViewport())
        val screenshot = checkNotNull(automation.takeScreenshot())
        var cropped: Bitmap? = null
        try {
            val area = viewport.available
            assertTrue(
                "앱 영역만 캡처할 수 있어야 합니다",
                area.left >= 0 && area.top >= 0 && area.right <= screenshot.width && area.bottom <= screenshot.height,
            )
            val appImage = Bitmap.createBitmap(screenshot, area.left, area.top, area.width(), area.height())
            cropped = appImage
            File(evidenceDirectory, "$name.png").outputStream().use { check(appImage.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            File(evidenceDirectory, "$name.json").writeText(
                JSONObject()
                    .put("name", name)
                    .put("width", appImage.width)
                    .put("height", appImage.height)
                    .toString(2),
            )
        } finally {
            if (cropped !== screenshot) cropped?.recycle()
            screenshot.recycle()
        }
    }

    private fun phase(name: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "$name\n") })
    }

    private data class AppViewport(
        val available: Rect,
        val originX: Int,
        val originY: Int,
    )

    private data class ExistingData(
        val preparation: PracticeSelection?,
        val sessions: List<PracticeSession>,
        val observations: Map<String, List<SessionVehicleObservation>>,
        val memos: Map<String, SessionMemo?>,
    )
}
