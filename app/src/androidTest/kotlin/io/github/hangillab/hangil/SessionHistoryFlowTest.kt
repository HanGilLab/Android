package io.github.hangillab.hangil

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.MultiParagraph
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
import kotlin.math.ceil
import io.github.hangillab.hangil.feature.home.R as HomeR

/**
 * Explicit read-only validation of existing ended snapshots with -e manualSessionHistory true.
 * Before launching the Activity, the external runner must verify there is no active session,
 * preserve original data/settings and own DDS P/D/P recovery even after failure. Run separately
 * at basic/light and enlarged/dark settings; this test never changes settings or permissions.
 * No session, observation, memo or preparation is created, edited or deleted. Original values
 * are compared privately without printing user text, IDs, times or other identifying data.
 * Empty/error injection, independent UX combinations and SDK subscription release are not claimed.
 */
@RunWith(AndroidJUnit4::class)
class SessionHistoryFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var layoutPolicyFixturesVerified = false

    @Test fun endedHistoryKeepsStoredSnapshotsAndSelectionAcrossLifecycleAndDrivingLock() {
        assumeTrue(
            "실제 이력과 외부 DDS 복구를 준비한 명시적 실행에서만 검사합니다",
            InstrumentationRegistry.getArguments().getString("manualSessionHistory") == "true",
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(
            "전용 debug 앱에서만 기존 종료 기록을 열람합니다",
            instrumentation.targetContext.packageName == "io.github.hangillab.hangil.debug",
        )
        val graph =
            EntryPointAccessors.fromApplication(
                instrumentation.targetContext.applicationContext,
                RecordingTestEntryPoint::class.java,
            )
        runBlocking {
            val before = snapshot(graph)
            assertTrue("기존 활성 연습이 있으면 검사를 진행하지 않습니다", before.sessions.none { it.isActive })
            val ended = before.sessions.filterNot { it.isActive }
            assertTrue("기존 종료 이력이 두 개 이상 있어야 이전 기록을 검사할 수 있습니다", ended.size >= 2)
            assertTrue("기존 종료 이력의 ID는 서로 달라야 합니다", ended.map { it.sessionId }.distinct().size == ended.size)
            // Preserve repository creation order. Button clock values must never reorder history.
            val olderIndex = 1
            val older = ended[olderIndex]
            // Keep real SDK streams available to the external DDS phases, including while stopped.
            val sdkObserver =
                launch {
                    combine(graph.vehicle().state, graph.restrictions().restrictions) { vehicle, restrictions ->
                        vehicle to restrictions
                    }.collect {}
                }
            var primaryFailure: Throwable? = null
            try {
                awaitAllowed(graph)
                touch("home_history")
                assertRecord(ended[0], 0, ended.size)
                assertControlEnabled("history_newer", false)
                assertControlEnabled("history_older", true)
                capture("history-latest", "history_course", listOf("history_course"))

                for (index in 1..ended.lastIndex) {
                    touch("history_older")
                    assertRecord(ended[index], index, ended.size)
                }
                assertControlEnabled("history_older", false)
                assertControlEnabled("history_newer", true)
                for (index in ended.lastIndex - 1 downTo olderIndex) {
                    touch("history_newer")
                    assertPosition(index, ended.size)
                }
                assertRecord(older, olderIndex, ended.size)
                capture("history-older", "history_course", listOf("history_course"))
                assertPreserved(graph, before)

                compose.activityRule.scenario.recreate()
                awaitAllowed(graph)
                assertRecord(older, olderIndex, ended.size)
                assertPreserved(graph, before)

                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                instrumentation.waitForIdleSync()
                assertPreserved(graph, before)
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                awaitAllowed(graph)
                assertRecord(older, olderIndex, ended.size)
                assertPreserved(graph, before)

                phase("HISTORY_AWAIT_D")
                awaitDrive(graph)
                waitFor("history_locked")
                detailTags.forEach(::assertAbsent)
                capture("history-driving", "history_locked", listOf("history_locked"))
                assertPreserved(graph, before)

                phase("HISTORY_AWAIT_P")
                awaitAllowed(graph)
                assertRecord(older, olderIndex, ended.size)
                capture("history-returned", "history_course", listOf("history_course"))
                assertPreserved(graph, before)

                touch("history_close")
                waitFor("home_history")
                detailTags.forEach(::assertAbsent)
                touch("home_history")
                assertRecord(ended[0], 0, ended.size)
                assertControlEnabled("history_newer", false)
                assertControlEnabled("history_older", true)
                assertPreserved(graph, before)
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                try {
                    sdkObserver.cancelAndJoin()
                } finally {
                    try {
                        assertPreserved(graph, before)
                    } catch (preservationFailure: Throwable) {
                        if (primaryFailure == null) throw preservationFailure
                        primaryFailure.addSuppressed(preservationFailure)
                    }
                }
            }
            phase("HISTORY_COMPLETED")
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
        assertTrue("원래 준비의 모든 필드를 보존해야 합니다", before.preparation == after.preparation)
        assertTrue("모든 세션의 필드와 저장 생성 순서를 보존해야 합니다", before.sessions == after.sessions)
        assertTrue("모든 차량 관찰의 ID, 필드와 순서를 보존해야 합니다", before.observations == after.observations)
        assertTrue("모든 메모의 원문과 변경 토큰을 보존해야 합니다", before.memos == after.memos)
        assertTrue("읽기 전용 검사 뒤 활성 연습이 생기면 안 됩니다", after.sessions.none { it.isActive })
    }

    private suspend fun awaitAllowed(graph: RecordingTestEntryPoint) =
        withTimeout(90_000) {
            combine(graph.vehicle().state, graph.restrictions().restrictions) { vehicle, restrictions ->
                allowsPracticeInteraction(vehicle, restrictions)
            }.first { it }
        }

    private suspend fun awaitDrive(graph: RecordingTestEntryPoint) =
        withTimeout(90_000) {
            graph.vehicle().state.first {
                it.connection == ConnectionState.CONNECTED && it.gear == VehicleReading.Available(GearPosition.DRIVE)
            }
        }

    private fun assertRecord(
        record: PracticeSession,
        index: Int,
        total: Int,
    ) {
        assertTrue("활성 기록을 종료 이력으로 표시하면 안 됩니다", record.endedAtEpochMillis != null)
        waitFor("history_ready")
        assertPosition(index, total)
        assertTextVisible("history_course", record.courseTitle)
        assertTextVisible("history_goal", record.goalTitle)
        assertTextVisible("history_task", record.taskTitle)
        assertTextVisible("history_started", text(HomeR.string.session_started_at, displayTime(record.startedAtEpochMillis)))
        assertTextVisible(
            "history_finished",
            text(HomeR.string.session_finished_at, displayTime(checkNotNull(record.endedAtEpochMillis))),
        )
        if (record.courseReviewStatus == CourseReviewStatus.DEMO) {
            assertTextVisible("history_demo", text(HomeR.string.demo_course))
        } else {
            assertAbsent("history_demo")
        }
        listOf("history_empty", "history_failed", "history_loading", "history_selection_changed", "history_locked")
            .forEach(::assertAbsent)
        assertControlEnabled("history_newer", index > 0)
        assertControlEnabled("history_older", index < total - 1)
    }

    private fun assertPosition(
        index: Int,
        total: Int,
    ) {
        assertTextVisible("history_position", text(HomeR.string.history_position, index + 1, total))
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
        assertTrue("종료 기록의 표시값은 저장 원문과 일치해야 합니다 ($tag)", nodeTexts(nodes(tag).single()).contains(expected))
        val textNodes =
            descendants(nodes(tag).single()).filter { node ->
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .any { it.text == expected }
            }
        var hasMatchingLayouts = false
        var layoutsFit = false
        val metrics = JSONArray()
        var fixturesVerifiedNow = false
        compose.runOnIdle {
            val layouts = mutableListOf<TextLayoutResult>()
            for (node in textNodes) {
                node.config
                    .getOrNull(SemanticsActions.GetTextLayoutResult)
                    ?.action
                    ?.invoke(layouts)
            }
            val matching = layouts.filter { it.layoutInput.text.text == expected }
            hasMatchingLayouts = matching.isNotEmpty()
            if (hasMatchingLayouts && !layoutPolicyFixturesVerified) {
                verifyLayoutPolicyFixtures(matching.first())
                layoutPolicyFixturesVerified = true
                fixturesVerifiedNow = true
            }
            layoutsFit = hasMatchingLayouts && matching.all { textGeometryFits(it, expected) }
            matching.forEach { metrics.put(layoutMetrics(it)) }
        }
        if (fixturesVerifiedNow) phase("HISTORY_LAYOUT_POLICY_FIXTURES_PASSED")
        // UI automation runs off Main and before the assertion lets ActivityScenario tear down.
        if (!hasMatchingLayouts || !layoutsFit) preserveLayoutFailure(tag, metrics)
        assertTrue("원문 글자의 레이아웃을 실제로 확인해야 합니다 ($tag)", hasMatchingLayouts)
        assertTrue("저장 원문을 말줄임이나 글자 잘림 없이 읽을 수 있어야 합니다 ($tag)", layoutsFit)
    }

    /**
     * Compose 1.9.4 ParagraphLayoutCache.slowCreateTextLayoutResultOrNull reconstructs semantics
     * with prevConstraints.copyMaxDimensions(), but retains the tight layoutSize. Its resulting
     * didOverflowWidth compares that size with the paragraph container, not painted line bounds.
     * Check complete logical coverage and actual line/character boxes instead. Both the text
     * layout and the independent physical node viewport must fit; only 1 px rounding is allowed.
     */
    private fun textGeometryFits(
        layout: TextLayoutResult,
        expected: String,
    ): Boolean {
        if (layout.layoutInput.text.text != expected || expected.isEmpty() || layout.size.width <= 0 || layout.size.height <= 0 ||
            layout.lineCount <= 0 || layout.multiParagraph.didExceedMaxLines
        ) {
            return false
        }
        val tolerance = 1f
        var coveredUntil = 0
        for (line in 0 until layout.lineCount) {
            val start = layout.getLineStart(line)
            val end = layout.getLineEnd(line, visibleEnd = false)
            if (start != coveredUntil || end < start || end > expected.length || layout.isLineEllipsized(line)) return false
            if (!insideLayout(
                    layout.getLineLeft(line),
                    layout.getLineTop(line),
                    layout.getLineRight(line),
                    layout.getLineBottom(line),
                    layout.size,
                    tolerance,
                )
            ) {
                return false
            }
            coveredUntil = end
        }
        if (coveredUntil != expected.length) return false
        for (offset in expected.indices) {
            if (expected[offset].isWhitespace()) continue
            val box = layout.getBoundingBox(offset)
            if (!insideLayout(box.left, box.top, box.right, box.bottom, layout.size, tolerance)) return false
        }
        return true
    }

    private fun insideLayout(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        size: IntSize,
        tolerance: Float,
    ): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() &&
            left <= right && top <= bottom && left >= -tolerance && top >= -tolerance &&
            right <= size.width + tolerance && bottom <= size.height + tolerance

    /** Real native paragraphs with fixed fixture text; these are not stored history or SDK evidence. */
    private fun verifyLayoutPolicyFixtures(sample: TextLayoutResult) {
        val input = sample.layoutInput
        val measurer = TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection, cacheSize = 0)
        val fixtureText = "Layout fixture"
        val style = input.style.copy(textAlign = TextAlign.Left)
        val measured =
            measurer.measure(
                AnnotatedString(fixtureText),
                style = style,
                constraints = Constraints(maxWidth = 1504),
                skipCache = true,
            )
        val wide =
            MultiParagraph(
                measured.multiParagraph.intrinsics,
                Constraints(maxWidth = maxOf(1504, measured.size.width + 128)),
            )
        val tight =
            IntSize(
                ceil((0 until wide.lineCount).maxOf { wide.getLineRight(it) }.toDouble()).toInt(),
                ceil(wide.height.toDouble()).toInt(),
            )
        val semantics = TextLayoutResult(measured.layoutInput, wide, tight)
        assertTrue("문단 컨테이너 폭 오탐을 실제 Compose 문단으로 재현해야 합니다", semantics.didOverflowWidth)
        assertTrue("전체 글자가 맞는 타이트한 semantics 레이아웃은 허용해야 합니다", textGeometryFits(semantics, fixtureText))
        val horizontalClip = TextLayoutResult(measured.layoutInput, wide, IntSize(maxOf(1, tight.width / 2), tight.height))
        assertTrue("실제 가로 글자 잘림은 계속 거부해야 합니다", !textGeometryFits(horizontalClip, fixtureText))
        val verticalClip = TextLayoutResult(measured.layoutInput, wide, IntSize(tight.width, maxOf(1, tight.height / 2)))
        assertTrue("실제 세로 글자 잘림은 계속 거부해야 합니다", !textGeometryFits(verticalClip, fixtureText))
        val longText = "Layout fixture ".repeat(30)
        val ellipsized =
            measurer.measure(
                AnnotatedString(longText),
                style = style,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                constraints = Constraints(maxWidth = maxOf(64, tight.width / 2)),
                skipCache = true,
            )
        assertTrue("말줄임 음성 표본은 실제 말줄임을 포함해야 합니다", (0 until ellipsized.lineCount).any { ellipsized.isLineEllipsized(it) })
        assertTrue("말줄임으로 누락된 글자는 거부해야 합니다", !textGeometryFits(ellipsized, longText))
        val prefix = measurer.measure(AnnotatedString("Layout"), style = style, skipCache = true)
        val missingSuffix = TextLayoutResult(measured.layoutInput, prefix.multiParagraph, prefix.size)
        assertTrue("입력 원문의 뒷부분이 배치되지 않으면 거부해야 합니다", !textGeometryFits(missingSuffix, fixtureText))
    }

    /** Anonymous dimensions only: never serialize text, session identity or observation times. */
    private fun layoutMetrics(layout: TextLayoutResult): JSONObject {
        val lineBottoms = JSONArray()
        val ellipsis = JSONArray()
        val lines = JSONArray()
        for (line in 0 until layout.lineCount) {
            lineBottoms.put(layout.getLineBottom(line).toDouble())
            ellipsis.put(layout.isLineEllipsized(line))
            lines.put(
                JSONObject()
                    .put("left", layout.getLineLeft(line).toDouble())
                    .put("right", layout.getLineRight(line).toDouble())
                    .put("top", layout.getLineTop(line).toDouble())
                    .put("bottom", layout.getLineBottom(line).toDouble())
                    .put("start", layout.getLineStart(line))
                    .put("end", layout.getLineEnd(line, visibleEnd = false))
                    .put("visibleEnd", layout.getLineEnd(line, visibleEnd = true))
                    .put("ellipsis", layout.isLineEllipsized(line)),
            )
        }
        val constraints = layout.layoutInput.constraints
        return JSONObject()
            .put("size", JSONObject().put("width", layout.size.width).put("height", layout.size.height))
            .put(
                "multiParagraph",
                JSONObject().put("width", layout.multiParagraph.width.toDouble()).put("height", layout.multiParagraph.height.toDouble()),
            ).put("didOverflowWidth", layout.didOverflowWidth)
            .put("didOverflowHeight", layout.didOverflowHeight)
            .put("hasVisualOverflow", layout.hasVisualOverflow)
            .put("lineCount", layout.lineCount)
            .put("didExceedMaxLines", layout.multiParagraph.didExceedMaxLines)
            .put("lines", lines)
            .put("lineBottoms", lineBottoms)
            .put("ellipsis", ellipsis)
            .put(
                "constraints",
                JSONObject()
                    .put("minWidth", constraints.minWidth)
                    .put("maxWidth", constraints.maxWidth)
                    .put("minHeight", constraints.minHeight)
                    .put("maxHeight", constraints.maxHeight),
            )
    }

    private fun preserveLayoutFailure(
        tag: String,
        layouts: JSONArray,
        includeWaitDiagnostics: Boolean = false,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val details =
            JSONObject()
                .put("schema", 1)
                .put("tag", tag)
                .put("matchingLayouts", layouts.length())
                .put("layouts", layouts)
                .put("frontMatchesExpected", false)
                .put("foregroundAvailable", false)
                .put("screenshotCaptured", false)
        val directory = File(instrumentation.targetContext.cacheDir, "screenshots")
        var screenshot: Bitmap? = null
        try {
            val automation = instrumentation.uiAutomation
            val foreground = automation.rootInActiveWindow?.packageName?.toString()
            details.put("foregroundAvailable", foreground != null)
            details.put("frontMatchesExpected", foreground == instrumentation.targetContext.packageName)
            val captured = automation.takeScreenshot()
            screenshot = captured
            if (captured != null && (directory.isDirectory || directory.mkdirs())) {
                val stored =
                    File(directory, "history-layout-failure.png").outputStream().use {
                        captured.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                details.put("screenshotCaptured", stored)
            }
        } catch (_: Exception) {
            // Diagnostic failure must never replace the original text layout assertion failure.
        } finally {
            screenshot?.recycle()
        }
        if (includeWaitDiagnostics) details.put("waitDiagnostics", waitDiagnostics(tag))
        try {
            if (directory.isDirectory || directory.mkdirs()) {
                File(directory, "history-layout-failure.json").writeText(details.toString(2))
            }
        } catch (_: Exception) {
            // Keep the original failure even if private evidence cannot be written.
        }
    }

    /** Counts only, collected after the failure screenshot; never dump semantics text or IDs. */
    private fun waitDiagnostics(tag: String): JSONObject {
        val diagnostics = JSONObject().put("inspectionComplete", false)
        try {
            diagnostics.put("targetMatches", nodes(tag).size)
            val states = JSONObject()
            listOf(
                "history_ready",
                "history_loading",
                "history_empty",
                "history_failed",
                "history_selection_changed",
                "history_locked",
                "history_course",
                "history_goal",
                "history_task",
                "history_started",
                "history_finished",
                "history_demo",
                "history_position",
            ).forEach { states.put(it, nodes(it).size) }
            diagnostics.put("stateTagCounts", states)
            diagnostics.put(
                "semanticsRoots",
                compose
                    .onAllNodes(isRoot(), useUnmergedTree = true)
                    .fetchSemanticsNodes(atLeastOneRootRequired = false)
                    .size,
            )
            diagnostics.put("inspectionComplete", true)
        } catch (_: Throwable) {
            // Missing diagnostics stay missing rather than becoming invented zero counts.
        }
        return diagnostics
    }

    private fun nodeTexts(node: SemanticsNode): List<String> =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text } + node.children.flatMap(::nodeTexts)

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)

    private fun nodes(tag: String) =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun exists(tag: String): Boolean = nodes(tag).size == 1

    private fun waitFor(tag: String) {
        try {
            compose.waitUntil(20_000) { exists(tag) }
        } catch (failure: Throwable) {
            try {
                preserveLayoutFailure(tag, JSONArray(), includeWaitDiagnostics = true)
            } catch (_: Throwable) {
                // Evidence collection must not replace the original wait condition or failure.
            }
            throw failure
        }
    }

    private fun assertAbsent(tag: String) {
        assertTrue("잠금 또는 이탈 상태에 종료 기록 상세를 남기면 안 됩니다 ($tag)", nodes(tag).isEmpty())
    }

    private fun assertControlEnabled(
        tag: String,
        enabled: Boolean,
    ) {
        scrollVisible(tag)
        assertTrue(
            "끝 기록의 이동 가능 여부가 저장 순서와 일치해야 합니다 ($tag)",
            nodes(tag).single().config.contains(SemanticsProperties.Disabled) != enabled,
        )
    }

    private fun touch(tag: String) {
        scrollVisible(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
    }

    private fun scrollVisible(tag: String) {
        waitFor(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
        assertFullyVisible(listOf(tag))
    }

    private fun appViewport(): AppViewport? {
        var viewport: AppViewport? = null
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
                viewport = AppViewport(available, screen[0] - window[0], screen[1] - window[1])
            }
        }
        return viewport
    }

    private fun assertFullyVisible(tags: List<String>) {
        compose.waitUntil(15_000) {
            val viewport = appViewport()
            viewport != null &&
                tags.all { tag ->
                    val node = nodes(tag).singleOrNull()
                    if (node == null || node.size.width <= 0 || node.size.height <= 0) {
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
    }

    private fun capture(
        name: String,
        scrollTarget: String,
        visibleTags: List<String>,
    ) {
        scrollVisible(scrollTarget)
        assertFullyVisible(visibleTags)
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val expectedPackage = instrumentation.targetContext.packageName
        compose.waitUntil(20_000) { automation.rootInActiveWindow?.packageName?.toString() == expectedPackage }
        automation.waitForIdle(500, 5_000)
        assertTrue("실제 앱 전면을 확인한 뒤 캡처해야 합니다", automation.rootInActiveWindow?.packageName?.toString() == expectedPackage)
        val viewport = checkNotNull(appViewport())
        val screenshot = checkNotNull(automation.takeScreenshot())
        var appImage: Bitmap? = null
        try {
            val bounds = Rect(viewport.available)
            assertTrue("실제 앱 영역이 캡처 안에 있어야 합니다", bounds.intersect(0, 0, screenshot.width, screenshot.height))
            val capturedApp = Bitmap.createBitmap(screenshot, bounds.left, bounds.top, bounds.width(), bounds.height())
            appImage = capturedApp
            val directory = File(instrumentation.targetContext.cacheDir, "screenshots").apply { mkdirs() }
            File(directory, "$name.png").outputStream().use { check(capturedApp.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            if (appImage !== screenshot) appImage?.recycle()
            screenshot.recycle()
        }
    }

    private fun phase(value: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "$value\n") })
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

    private companion object {
        val detailTags =
            listOf(
                "history_ready",
                "history_course",
                "history_goal",
                "history_task",
                "history_started",
                "history_finished",
                "history_demo",
                "history_position",
                "history_older",
                "history_newer",
            )
    }
}
