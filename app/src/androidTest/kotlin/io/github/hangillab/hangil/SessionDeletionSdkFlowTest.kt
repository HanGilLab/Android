package io.github.hangillab.hangil

import android.content.res.Configuration
import android.graphics.Rect
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import io.github.hangillab.hangil.feature.home.R as HomeR

/**
 * Explicit actual-SDK read-only confirmation/cancel/P-D-P flow. It never invokes or captures the
 * confirm-delete action, and never creates/edits/deletes original sessions, observations or memos.
 * The external owner preserves all original SQLite/settings and controls real DDS. No screenshots
 * or raw source output are produced. Isolated synthetic deletion checks live in a separate class.
 * This graph snapshot compares domain fields/order only; the external owner checks the complete
 * original raw SQLite data/types/sequences independently before and after actual-SDK execution.
 */
@RunWith(AndroidJUnit4::class)
class SessionDeletionSdkFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun actualSdkConfirmationCancelAndDrivingLockNeverDeleteOriginalData() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "실제 SDK와 외부 원본 보존을 준비한 명시 실행에서만 확인창을 조회합니다",
            arguments.getString("manualSessionDeletionActualSdk") == "true",
        )
        verifyConfiguration()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue("전용 debug 앱에서만 조회합니다", context.packageName == "io.github.hangillab.hangil.debug")
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, RecordingTestEntryPoint::class.java)
        runBlocking {
            val before = snapshot(graph)
            assertTrue("기존 활성 연습이 있으면 원본 확인 검사를 시작하지 않습니다", before.sessions.none { it.isActive })
            val source = checkNotNull(before.sessions.firstOrNull { !it.isActive }) { "기존 종료 기록이 필요합니다" }
            val observer =
                launch {
                    combine(graph.vehicle().state, graph.restrictions().restrictions) { vehicle, restrictions ->
                        vehicle to restrictions
                    }.collect {}
                }
            var primary: Throwable? = null
            try {
                awaitAllowed(graph)
                requireForeground()
                touch("home_history")
                assertSource(source)
                openAndReadConfirmation(before, source)
                touch("history_delete_cancel")
                waitAbsent("history_delete_dialog")
                assertPreserved(graph, before)
                openAndReadConfirmation(before, source)
                phase("SESSION_DELETE_ACTUAL_AWAIT_D")
                withTimeout(90_000) {
                    graph.vehicle().state.first {
                        it.connection == ConnectionState.CONNECTED && it.gear == VehicleReading.Available(GearPosition.DRIVE)
                    }
                }
                waitFor("history_locked")
                waitAbsent("history_delete_dialog")
                listOf("history_course", "history_goal", "history_task", "history_delete_confirm")
                    .forEach { tag -> assertTrue("주행 잠금에 상세나 확인 조작을 남기면 안 됩니다", nodes(tag).isEmpty()) }
                bringIntoView("history_close")
                assertPreserved(graph, before)
                phase("SESSION_DELETE_ACTUAL_AWAIT_P")
                awaitAllowed(graph)
                assertSource(source)
                assertTrue("P 복귀에 이전 확인창이 자동 복원되면 안 됩니다", nodes("history_delete_dialog").isEmpty())
                openAndReadConfirmation(before, source)
                touch("history_delete_cancel")
                waitAbsent("history_delete_dialog")
                touch("history_close")
                waitFor("home_history")
                assertPreserved(graph, before)
            } catch (failure: Throwable) {
                primary = failure
                throw failure
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (nodes("history_delete_cancel").size == 1) touch("history_delete_cancel")
                        if (nodes("history_close").size == 1) touch("history_close")
                        assertPreserved(graph, before)
                    } catch (cleanup: Throwable) {
                        val original = primary
                        if (original == null) throw cleanup
                        original.addSuppressed(cleanup)
                    } finally {
                        observer.cancelAndJoin()
                    }
                }
            }
            phase("SESSION_DELETE_ACTUAL_COMPLETED")
        }
    }

    private fun openAndReadConfirmation(
        before: ExistingData,
        source: PracticeSession,
    ) {
        requireForeground()
        touch("history_delete")
        waitFor("history_delete_dialog")
        waitFor("history_delete_confirm")
        assertDialogReadable()
        assertText("history_delete_course", source.courseTitle)
        assertText("history_delete_task", source.taskTitle)
        assertText(
            "history_delete_finished",
            text(HomeR.string.session_finished_at, displayTime(checkNotNull(source.endedAtEpochMillis))),
        )
        assertText(
            "history_delete_observation_count",
            text(HomeR.string.history_delete_observation_count, before.observations.getValue(source.sessionId).size),
        )
        assertText(
            "history_delete_memo_status",
            text(
                if (before.memos.getValue(source.sessionId) == null) {
                    HomeR.string.history_delete_memo_absent
                } else {
                    HomeR.string.history_delete_memo_present
                },
            ),
        )
        assertText("history_delete_warning", text(HomeR.string.history_delete_warning))
        bringIntoView("history_delete_cancel")
        assertTrue("취소 버튼이 사용 가능해야 합니다", !nodes("history_delete_cancel").single().config.contains(SemanticsProperties.Disabled))
        // Read label/geometry only. Never obtain or invoke SemanticsActions.OnClick for confirmation.
        bringIntoView("history_delete_confirm")
        assertTrue("확인 버튼은 경고와 함께 표시되어야 합니다", nodes("history_delete_confirm").size == 1)
    }

    private fun assertSource(source: PracticeSession) {
        waitFor("history_ready")
        assertText("history_course", source.courseTitle)
        assertText("history_goal", source.goalTitle)
        assertText("history_task", source.taskTitle)
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
        assertTrue("원래 준비/세션/관찰/메모의 domain 필드와 순서를 보존해야 합니다", before == after)
        assertTrue("읽기 검사로 활성 연습이 생기면 안 됩니다", after.sessions.none { it.isActive })
    }

    private suspend fun awaitAllowed(graph: RecordingTestEntryPoint) =
        withTimeout(90_000) {
            combine(graph.vehicle().state, graph.restrictions().restrictions) { vehicle, restrictions ->
                allowsPracticeInteraction(vehicle, restrictions)
            }.first { it }
        }

    private fun verifyConfiguration() {
        val arguments = InstrumentationRegistry.getArguments()
        val expectedScale =
            arguments.getString("expectedFontScale")?.let { checkNotNull(it.toFloatOrNull()) { "글꼴 선언이 잘못됐습니다" } } ?: 1f
        val expectedDark =
            arguments.getString("expectedDark")?.let { checkNotNull(it.toBooleanStrictOrNull()) { "야간 선언이 잘못됐습니다" } } ?: false
        assertTrue("정의한 글꼴 조건만 검사합니다", expectedScale == 1f || expectedScale == 1.3f)
        val configuration =
            InstrumentationRegistry
                .getInstrumentation()
                .targetContext.resources.configuration
        assertTrue("실제 글꼴 조건이 선언과 같아야 합니다", kotlin.math.abs(configuration.fontScale - expectedScale) < 0.001f)
        assertTrue(
            "실제 야간 조건이 선언과 같아야 합니다",
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) == expectedDark,
        )
    }

    private fun assertDialogReadable() {
        val original =
            descendants(nodes("history_delete_dialog").single())
                .filter {
                    it.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .isNotEmpty()
                }
        assertTrue("실제 확인창의 경고 본문을 확인해야 합니다", original.isNotEmpty())
        for (node in original) {
            val expected =
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .joinToString("") { it.text }
            val matcher = SemanticsMatcher("익명 확인창 텍스트 노드") { it.id == node.id }
            if (!fullyVisible(node)) compose.onNode(matcher, useUnmergedTree = true).performScrollTo()
            compose.waitForIdle()
            val current = compose.onNode(matcher, useUnmergedTree = true).fetchSemanticsNode()
            assertTrue("확인창 원문 전체가 실제 화면에 보여야 합니다", fullyVisible(current))
            assertLayout(current, expected)
        }
    }

    private fun assertText(
        tag: String,
        expected: String,
    ) {
        bringIntoView(tag)
        val target = nodes(tag).single()
        val matches =
            descendants(target).filter {
                it.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .any { value -> value.text == expected }
            }
        assertTrue("표시값은 저장 원문 또는 실제 연결 수와 일치해야 합니다", matches.isNotEmpty())
        matches.forEach { assertLayout(it, expected) }
    }

    private fun assertLayout(
        node: SemanticsNode,
        expected: String,
    ) {
        var valid = false
        compose.runOnIdle {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config
                .getOrNull(SemanticsActions.GetTextLayoutResult)
                ?.action
                ?.invoke(layouts)
            val matching = layouts.filter { it.layoutInput.text.text == expected }
            valid = matching.isNotEmpty() && matching.all { textGeometryFits(it, expected) }
        }
        assertTrue("전체 글자와 줄이 잘리거나 말줄임되지 않아야 합니다", valid)
    }

    /** Paragraph container width can exceed tight semantics size; check real lines and glyph coverage. */
    private fun textGeometryFits(
        layout: TextLayoutResult,
        expected: String,
    ): Boolean {
        if (layout.layoutInput.text.text != expected || expected.isEmpty() || layout.size.width <= 0 || layout.size.height <= 0 ||
            layout.lineCount <= 0 || layout.multiParagraph.didExceedMaxLines
        ) {
            return false
        }
        var covered = 0
        for (line in 0 until layout.lineCount) {
            val end = layout.getLineEnd(line, visibleEnd = false)
            if (layout.getLineStart(line) != covered || end < covered || end > expected.length || layout.isLineEllipsized(line)) {
                return false
            }
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
        for (index in expected.indices) {
            if (expected[index].isWhitespace()) continue
            val box = layout.getBoundingBox(index)
            if (!inside(box.left, box.top, box.right, box.bottom, layout.size)) return false
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

    private fun nodes(tag: String): List<SemanticsNode> =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)

    private fun waitFor(tag: String) = compose.waitUntil(20_000) { nodes(tag).size == 1 }

    private fun waitAbsent(tag: String) = compose.waitUntil(20_000) { nodes(tag).isEmpty() }

    private fun touch(tag: String) {
        check(tag != "history_delete_confirm") { "원래 데이터의 삭제 확인은 호출할 수 없습니다" }
        bringIntoView(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
    }

    private fun bringIntoView(tag: String) {
        waitFor(tag)
        if (!fullyVisible(nodes(tag).single())) compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
        compose.waitUntil(15_000) { nodes(tag).singleOrNull()?.let(::fullyVisible) == true }
    }

    private fun fullyVisible(node: SemanticsNode): Boolean {
        val available = Rect()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.window.decorView
                .getWindowVisibleDisplayFrame(available)
        }
        val bounds = node.boundsInWindow
        val origin = node.positionOnScreen - node.positionInWindow
        return node.size.width > 0 && node.size.height > 0 &&
            bounds.width >= node.size.width - 1 && bounds.height >= node.size.height - 1 &&
            bounds.left + origin.x >= available.left - 1 && bounds.top + origin.y >= available.top - 1 &&
            bounds.right + origin.x <= available.right + 1 && bounds.bottom + origin.y <= available.bottom + 1
    }

    private fun requireForeground() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val expected = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        compose.waitUntil(20_000) { automation.rootInActiveWindow?.packageName?.toString() == expected }
        assertTrue("실제 앱 전면에서만 확인창을 읽어야 합니다", automation.rootInActiveWindow?.packageName?.toString() == expected)
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

    private fun phase(value: String) =
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "$value\n") })

    private data class ExistingData(
        val preparation: PracticeSelection?,
        val sessions: List<PracticeSession>,
        val observations: Map<String, List<SessionVehicleObservation>>,
        val memos: Map<String, SessionMemo?>,
    )
}
