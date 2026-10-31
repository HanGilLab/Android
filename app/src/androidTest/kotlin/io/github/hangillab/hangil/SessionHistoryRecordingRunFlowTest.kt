package io.github.hangillab.hangil

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.RecordingRunStatus
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleRecordingRun
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
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
 * Actual Main, SDK gates and Room reads only. The external runner owns full SQLite/settings
 * preservation and DDS recovery before Activity launch. No writes, service/recovery request,
 * permission flow or Navigation call is made. Images/JSON stay in private cache. Marker times
 * never establish continuous SDK capture, actual service termination or road intervals.
 */
@RunWith(AndroidJUnit4::class)
class SessionHistoryRecordingRunFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun savedRunHistoryRemainsReadOnlyAcrossSelectionRecreationAndDrivingLock() {
        assumeTrue("실제 원본 보존과 DDS 복구를 준비한 실행만 검사합니다", InstrumentationRegistry.getArguments().getString("manualHistoryRecordingRunFlow") == "true")
        val h = Harness(compose)
        var primary: Throwable? = null
        try {
            h.assertEnvironment()
            runBlocking {
                val before = h.snapshot(h.graph)
                h.assertFrozenBaseline(before, exactTheme = false)
                val ended = before.sessions.filterNot { it.isActive }
                val latestRuns = before.recordingRuns.getValue(ended.first().sessionId)
                assertTrue("고정 source에 실제 STOPPED와 미확인 종료 근거가 필요합니다", latestRuns.any { it.status == RecordingRunStatus.STOPPED } && latestRuns.any { it.status == RecordingRunStatus.INTERRUPTED && it.endedAtEpochMillis == null })
                assertTrue("구형 무마커 자료와 SDK 관찰의 공존 source가 필요합니다", ended.any { before.recordingRuns.getValue(it.sessionId).isEmpty() && before.observations.getValue(it.sessionId).isNotEmpty() })
                val sdkObserver = launch { combine(h.graph.vehicle().state, h.graph.restrictions().restrictions) { vehicle, restrictions -> vehicle to restrictions }.collect {} }
                var bodyFailure: Throwable? = null
                try {
                    h.awaitAllowed()
                    h.touch("home_history")
                    for ((index, source) in ended.withIndex()) {
                        if (index > 0) h.touch("history_older")
                        h.assertSource(source, index, ended.size)
                        listOf("history_observations", "history_summary_tasks", "history_memo", "history_recording_runs").forEach(h::assertCollapsed)
                        h.touch("history_recording_runs")
                        val runs = before.recordingRuns.getValue(source.sessionId)
                        h.assertEveryRun(runs)
                        h.capture("stored-run-history-$index", if (runs.isEmpty()) "history_recording_run_empty" else "history_recording_run_status")
                        h.touch("history_recording_runs")
                        h.assertCollapsed("history_recording_runs")
                        if (runs.isEmpty() && before.observations.getValue(source.sessionId).isNotEmpty()) {
                            h.touch("history_observations")
                            val rows = before.observations.getValue(source.sessionId)
                            h.assertText("review_source", h.text(HomeR.string.review_source))
                            h.assertText("review_observed_at", h.text(HomeR.string.review_observed_at, h.displayTime(rows.last().observation.observedAtEpochMillis)))
                            h.touch("history_observations")
                        }
                        h.assertPreserved(h.graph, before)
                    }
                    h.closeHistory()
                    h.touch("home_history")
                    h.assertSource(ended.first(), 0, ended.size)
                    h.touch("history_recording_runs")
                    h.assertEveryRun(latestRuns)
                    compose.activityRule.scenario.recreate()
                    h.assertEnvironment()
                    h.awaitAllowed()
                    h.assertSource(ended.first(), 0, ended.size)
                    h.assertCollapsed("history_recording_runs")
                    h.touch("history_recording_runs")
                    h.assertEveryRun(latestRuns)
                    h.assertPreserved(h.graph, before)
                    h.phase("HISTORY_RECORDING_RUN_AWAIT_D")
                    h.awaitDrive()
                    h.waitFor("history_locked")
                    h.assertRunContentAbsent()
                    h.capture("stored-runs-driving", "history_locked")
                    h.assertPreserved(h.graph, before)
                    h.phase("HISTORY_RECORDING_RUN_AWAIT_P")
                    h.awaitAllowed()
                    h.assertSource(ended.first(), 0, ended.size)
                    h.assertCollapsed("history_recording_runs")
                    h.touch("history_recording_runs")
                    h.assertEveryRun(latestRuns)
                    h.capture("stored-runs-returned", "history_recording_run_status")
                    h.closeHistory()
                    h.assertPreserved(h.graph, before)
                    h.assertBaselineUnchanged()
                } catch (failure: Throwable) {
                    bodyFailure = failure
                    try { h.capture("failure-current-app", null) } catch (capture: Throwable) { failure.addSuppressed(capture) }
                    throw failure
                } finally {
                    var cleanupFailure: Throwable? = null
                    fun preserveCleanup(problem: Throwable) {
                        val first = cleanupFailure
                        if (first == null) cleanupFailure = problem else if (first !== problem) first.addSuppressed(problem)
                        bodyFailure?.let { if (it !== problem) it.addSuppressed(problem) }
                    }
                    withContext(NonCancellable) {
                        try { sdkObserver.cancelAndJoin() } catch (cleanup: Throwable) { preserveCleanup(cleanup) }
                        try { h.assertPreserved(h.graph, before) } catch (cleanup: Throwable) { preserveCleanup(cleanup) }
                        try { h.assertBaselineUnchanged() } catch (cleanup: Throwable) { preserveCleanup(cleanup) }
                    }
                    if (bodyFailure == null) cleanupFailure?.let { throw it }
                }
            }
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally { try { h.restoreAutomationFlag() } catch (cleanup: Throwable) { if (primary == null) throw cleanup else primary.addSuppressed(cleanup) } }
        h.phase("HISTORY_RECORDING_RUN_COMPLETED")
    }

    /** Shared real read-only inputs and UI checks for this flow and the after-only M2 test. */
    internal class Harness(internal val compose: AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>) {
        internal val instrument = InstrumentationRegistry.getInstrumentation()
        internal val graph = EntryPointAccessors.fromApplication(instrument.targetContext.applicationContext, RecordingTestEntryPoint::class.java)
        private val baselineHashes = linkedMapOf<File, String>()
        private val baselineProvenance = JSONObject()
        private var originalInteractiveFlag: Boolean? = null
        private var mainDisplay: Int? = null
        private val evidence by lazy { File(instrument.targetContext.cacheDir, "screenshots/history-recording-runs-${UUID.randomUUID()}").apply { check(mkdirs()) } }

        internal fun assertEnvironment() {
            val args = InstrumentationRegistry.getArguments()
            assertTrue("명시한 debug/API34 환경이 필요합니다", instrument.targetContext.packageName == "io.github.hangillab.hangil.debug" && Build.VERSION.SDK_INT == 34)
            val font = args.getString("expectedFontScale")
            val dark = args.getString("expectedDark")
            assertTrue("기본/확대 글꼴을 명시해야 합니다", font == "1.0" || font == "1.3")
            assertTrue("테마를 명시해야 합니다", dark == "false" || dark == "true")
            val config = compose.activity.resources.configuration
            assertTrue("실제 글꼴이 일치해야 합니다", abs(config.fontScale - checkNotNull(font).toFloat()) <= 0.01f)
            assertTrue("실제 테마가 일치해야 합니다", config.uiMode and Configuration.UI_MODE_NIGHT_MASK == if (dark == "true") Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
            instrument.runOnMainSync {
                assertTrue("실제 Main RESUMED가 필요합니다", compose.activity.lifecycle.currentState == Lifecycle.State.RESUMED)
                val display = checkNotNull(compose.activity.display)
                assertTrue("실제 Main display가 유효해야 합니다", display.isValid)
                val actual = display.displayId
                assertTrue("같은 Main display를 유지해야 합니다", mainDisplay == null || mainDisplay == actual)
                mainDisplay = actual
            }
            val info = instrument.uiAutomation.serviceInfo
            if (originalInteractiveFlag == null) originalInteractiveFlag = info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            instrument.uiAutomation.serviceInfo = info
            assertTrue("실제 window flag readback이 필요합니다", instrument.uiAutomation.serviceInfo.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0)
            requireForeground()
        }
        internal fun restoreAutomationFlag() {
            val original = originalInteractiveFlag ?: return
            val info = instrument.uiAutomation.serviceInfo
            info.flags = if (original) info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS else info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS.inv()
            instrument.uiAutomation.serviceInfo = info
            assertTrue("원래 window flag bit를 복원해야 합니다", (instrument.uiAutomation.serviceInfo.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0) == original)
            originalInteractiveFlag = null
        }
        internal fun actualPackage(): String? {
            val display = mainDisplay ?: return null
            val allDisplays = instrument.uiAutomation.windowsOnAllDisplays
            val observedWindows = mutableListOf<AccessibilityWindowInfo>()
            var root: AccessibilityNodeInfo? = null
            try {
                for (index in 0 until allDisplays.size()) observedWindows.addAll(allDisplays.valueAt(index))
                val windows = allDisplays[display] ?: return null
                if (windows.any { it.displayId != display }) return null
                val focused = windows.filter { it.isFocused }
                if (focused.size != 1) return null
                val observedRoot = focused.single().root ?: return null
                root = observedRoot
                return observedRoot.packageName?.toString()
            } finally {
                root?.recycle()
                observedWindows.forEach { it.recycle() }
            }
        }
        internal fun requireForeground() {
            var resumed = false
            var sameValidDisplay = false
            instrument.runOnMainSync {
                resumed = compose.activity.lifecycle.currentState == Lifecycle.State.RESUMED
                val display = compose.activity.display
                sameValidDisplay = display != null && display.isValid && mainDisplay != null && display.displayId == mainDisplay
            }
            assertTrue("현재 Main RESUMED와 유효한 동일 display가 필요합니다", resumed && sameValidDisplay)
            assertTrue("같은 Main display의 focused app을 실제로 확인해야 합니다", actualPackage() == instrument.targetContext.packageName)
        }
        internal suspend fun awaitAllowed() = withTimeout(90_000) {
            combine(graph.vehicle().state, graph.restrictions().restrictions) { vehicle, restrictions -> allowsPracticeInteraction(vehicle, restrictions) }.first { it }
            assertTrue("현재 SDK P+UX 허용이어야 합니다", liveAllowed())
            requireForeground()
        }
        internal suspend fun awaitDrive() = withTimeout(90_000) {
            graph.vehicle().state.first { it.connection == ConnectionState.CONNECTED && (it.gear as? VehicleReading.Available)?.value == GearPosition.DRIVE }
            assertTrue("현재 실제 D는 잠겨야 합니다", !liveAllowed())
        }
        internal fun liveAllowed() = allowsPracticeInteraction(graph.vehicle().state.value, graph.restrictions().restrictions.value)

        internal fun assertFrozenBaseline(data: ExistingData, exactTheme: Boolean) {
            assertTrue("활성 연습이 없는 읽기 fixture여야 합니다", data.sessions.none { it.isActive })
            val source = checkNotNull(data.sessions.firstOrNull { !it.isActive })
            val names = listOf("history-opening-history-recording-runs-entry-v1" to "history-recording-runs-entry-v1", "history-disclosure-history-recording-runs-tasks-v1" to "history-recording-runs-tasks-v1")
            var frozenProduct: String? = null
            var frozenHarness: String? = null
            for ((prefix, series) in names) {
                val file = File(instrument.targetContext.cacheDir, "measurements/$prefix-baseline.json")
                check(file.isFile && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists()) { "완료 기준 원문이 필요합니다" }
                val baseline = JSONObject(file.readText())
                assertTrue("같은 완료 기준 계열이어야 합니다", baseline.getInt("schema") == 1 && baseline.getString("measurementStage") == "baseline" && baseline.getString("measurementSeries") == series && baseline.getString("status") == "completed" && baseline.getBoolean("preservedOriginalData") && baseline.getBoolean("returnedHome") && baseline.getInt("excludedSamples") == 0)
                val product = baseline.getString("declaredProductCommit")
                val harness = baseline.getString("declaredHarnessRevision")
                assertTrue("고정 기준 원본 선언이 완전해야 합니다", product.matches(Regex("[0-9a-f]{40}")) && harness.matches(Regex("[0-9a-f]{40}")))
                assertTrue("M0/M1은 같은 동결 제품/하네스 원본이어야 합니다", (frozenProduct == null || frozenProduct == product) && (frozenHarness == null || frozenHarness == harness))
                assertTrue("동일 반복의 기준이어야 합니다", baseline.getInt("plannedWarmups") == 5 && baseline.getInt("plannedSamples") == 30)
                frozenProduct = product
                frozenHarness = harness
                baselineProvenance.put(file.name, JSONObject().put("declaredProductCommit", product).put("declaredHarnessRevision", harness).put("sha256", sha256(file)))
                for ((key, count) in listOf("warmups" to 5, "samples" to 30)) {
                    val values = baseline.getJSONArray(key)
                    assertTrue("기준 원값이 빠지면 안 됩니다", values.length() == count && (0 until count).all { values.getJSONObject(it).getString("status") == "success" })
                }
                requireSameJson(baseline.getJSONObject("originalData"), dataJson(data), "originalData")
                requireSameJson(baseline.getJSONObject("fixedSource"), sessionJson(source), "fixedSource")
                requireSameJson(baseline.getJSONObject("catalog"), catalogJson(graph), "catalog")
                val expected = baseline.getJSONObject("environment")
                val actual = environmentJson()
                if (exactTheme) requireSameJson(expected, actual, "environment") else {
                    for (key in listOf("sdkInt", "release", "buildFingerprint", "abis", "screenWidthDp", "screenHeightDp", "densityDpi", "locale", "timeZone", "debuggable", "productPackage")) requireSameJson(expected.get(key), actual.get(key), "environment-$key")
                }
                baselineHashes[file] = sha256(file)
            }
        }
        internal fun baselineHashesJson() = JSONObject().also { json -> baselineHashes.forEach { (file, hash) -> json.put(file.name, hash) } }
        internal fun baselineProvenanceJson() = JSONObject(baselineProvenance.toString())
        internal fun assertBaselineUnchanged() { baselineHashes.forEach { (file, hash) -> assertTrue("완료 기준 파일을 변경하면 안 됩니다", sha256(file) == hash) } }
        internal fun assertSource(source: PracticeSession, index: Int, count: Int) {
            assertText("history_course", source.courseTitle)
            assertText("history_goal", source.goalTitle)
            assertText("history_task", source.taskTitle)
            assertText("history_position", text(HomeR.string.history_position, index + 1, count))
            assertText("history_started", text(HomeR.string.session_started_at, displayTime(source.startedAtEpochMillis)))
            assertText("history_finished", text(HomeR.string.session_finished_at, displayTime(checkNotNull(source.endedAtEpochMillis))))
        }
        internal fun assertEveryRun(runs: List<VehicleRecordingRun>) {
            if (runs.isEmpty()) {
                assertText("history_recording_run_empty", text(HomeR.string.history_recording_run_empty))
                assertTrue("빈 실행을 가짜 행으로 만들면 안 됩니다", nodes("history_recording_run_ready").isEmpty())
                return
            }
            for (index in runs.lastIndex downTo 0) {
                assertRunText(runs, index)
                for (tag in listOf("history_recording_run_source", "history_recording_run_status", "history_recording_run_started", "history_recording_run_ended", "history_recording_run_scope")) { scrollVisible(tag); assertTextFits(tag) }
                if (index > 0) touch("history_recording_run_previous")
            }
        }
        internal fun assertRunText(runs: List<VehicleRecordingRun>, index: Int) {
            val run = runs[index]
            assertText("history_recording_run_source", text(HomeR.string.history_recording_run_source))
            assertText("history_recording_run_scope", text(HomeR.string.history_recording_run_scope))
            assertText("history_recording_run_position", text(HomeR.string.history_recording_run_position, index + 1, runs.size))
            assertText("history_recording_run_status", text(when (run.status) {
                RecordingRunStatus.STOPPED -> HomeR.string.history_recording_run_stopped
                RecordingRunStatus.FAILED -> HomeR.string.history_recording_run_failed_status
                RecordingRunStatus.INTERRUPTED -> HomeR.string.history_recording_run_interrupted_status
                RecordingRunStatus.OPEN -> HomeR.string.history_recording_run_open
            }))
            assertText("history_recording_run_started", text(HomeR.string.history_recording_run_started, displayTime(run.startedAtEpochMillis)))
            assertText("history_recording_run_ended", run.endedAtEpochMillis?.let { text(HomeR.string.history_recording_run_ended, displayTime(it)) } ?: text(HomeR.string.history_recording_run_ended_unknown))
            assertTrue("과거 중단 경고를 숨기거나 만들면 안 됩니다", nodes("history_recording_run_interrupted").size == if (runs.any { it.status == RecordingRunStatus.INTERRUPTED }) 1 else 0)
            val shown = nodes("history_recording_run_content").flatMap(::nodeTexts).joinToString(" ")
            for (stored in runs) assertTrue("원시 식별자를 UI에 노출하면 안 됩니다", !shown.contains(stored.runId) && !shown.contains(stored.processId) && !shown.contains(stored.sessionId))
        }
        internal fun assertRunContentAbsent() { for (tag in listOf("history_recording_run_content", "history_recording_run_ready", "history_recording_run_empty", "history_recording_run_failed", "history_recording_run_loading")) assertTrue("잠금 중 실행 상세를 표시하면 안 됩니다", nodes(tag).isEmpty()) }
        internal fun assertCollapsed(tag: String) { waitFor(tag); assertTrue("새로 열린 이력의 영역은 접혀 있어야 합니다", nodes(tag).single().config.getOrNull(SemanticsProperties.StateDescription) == text(DesignR.string.parked_detail_collapsed)) }
        internal fun closeHistory() { requireForeground(); touch("history_close"); waitFor("home_history"); assertTrue("이력을 닫아야 합니다", nodes("history_ready").isEmpty()) }
        internal fun touch(tag: String) { requireForeground(); scrollVisible(tag); compose.onNodeWithTag(tag, useUnmergedTree = true).performClick(); compose.waitForIdle() }
        internal fun waitFor(tag: String) = compose.waitUntil(20_000) { nodes(tag).size == 1 }
        internal fun nodes(tag: String): List<SemanticsNode> = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)
        internal fun nodeTexts(node: SemanticsNode): List<String> = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + node.children.flatMap(::nodeTexts)
        internal fun assertText(tag: String, expected: String) { waitFor(tag); assertTrue("저장된 원문과 같아야 합니다", nodeTexts(nodes(tag).single()).contains(expected)) }
        internal fun text(id: Int, vararg args: Any): String = instrument.targetContext.getString(id, *args)
        internal fun displayTime(time: Long): String = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(Locale.getDefault()).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time))
        internal fun scrollVisible(tag: String) { waitFor(tag); compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo(); compose.waitForIdle(); assertTrue("실제 가용 영역에 보여야 합니다", fullyVisible(nodes(tag).single(), checkNotNull(appViewport()))) }
        internal fun assertTextFits(tag: String) {
            fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
            val textNodes = descendants(nodes(tag).single()).filter { it.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action != null }
            assertTrue("실제 text layout이 있어야 합니다", textNodes.isNotEmpty())
            for (node in textNodes) {
                val layouts = mutableListOf<TextLayoutResult>()
                val getter = checkNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action)
                assertTrue("실제 text layout을 읽어야 합니다", getter(layouts) && layouts.isNotEmpty())
                assertTrue("새 실행 문구가 잘리면 안 됩니다", layouts.all { layout -> !layout.hasVisualOverflow && (0 until layout.lineCount).none(layout::isLineEllipsized) })
            }
        }
        internal fun endpointVisible(tag: String) = nodes(tag).singleOrNull()?.let { fullyVisible(it, checkNotNull(appViewport())) } == true
        internal fun phase(value: String) { instrument.sendStatus(2, Bundle().apply { putString("stream", value + System.lineSeparator()) }) }
        internal fun capture(name: String, anchor: String?) {
            if (anchor != null) scrollVisible(anchor)
            val diagnostic = anchor == null
            var state: String? = null
            var currentDisplayValid = false
            var lifecycleError: Throwable? = null
            try {
                instrument.runOnMainSync {
                    state = compose.activity.lifecycle.currentState.name
                    val display = compose.activity.display
                    currentDisplayValid = display != null && display.isValid && mainDisplay != null && display.displayId == mainDisplay
                }
            } catch (problem: Throwable) { lifecycleError = problem }
            var windowError: Throwable? = null
            val observedPackage = try { actualPackage() } catch (problem: Throwable) { windowError = problem; null }
            val safeApp = lifecycleError == null && windowError == null && currentDisplayValid && state == Lifecycle.State.RESUMED.name && observedPackage == instrument.targetContext.packageName
            var failure: Throwable? = lifecycleError ?: windowError
            fun preserve(problem: Throwable) {
                val first = failure
                if (first == null) failure = problem else if (first !== problem) first.addSuppressed(problem)
            }
            if (!safeApp && failure == null) failure = AssertionError("실제 Main 전면은 미확인입니다")
            val proof = JSONObject().put("kind", "history_recording_run_read_only")
                .put("foreground_basis", "single-focused-window-on-actual-Main-display")
                .put("main_display_known", mainDisplay != null)
                .put("current_main_display_valid", currentDisplayValid)
                .put("actual_package", observedPackage ?: JSONObject.NULL)
                .put("main_state", state ?: JSONObject.NULL)
                .put("anchor", anchor ?: JSONObject.NULL)
                .put("foreground_confirmed", safeApp)
                .put("lifecycle_probe_error", lifecycleError?.javaClass?.simpleName ?: JSONObject.NULL)
                .put("window_probe_error", windowError?.javaClass?.simpleName ?: JSONObject.NULL)
                .put("capture_scope", if (diagnostic) "raw-default-display-diagnostic-not-app-only" else "confirmed-Main-app-window-crop")
                .put("png_status", "pending")
            try { File(evidence, "$name.json").writeText(proof.toString(2)) } catch (problem: Throwable) { preserve(problem) }
            // Private failure diagnostics attempt a raw default-display screenshot independently
            // of failed window/lifecycle probes. It is never labeled as confirmed Main imagery.
            try {
                if (!diagnostic) {
                    assertTrue("정상 캡처는 기본 display의 실제 Main 전면이어야 합니다", safeApp && mainDisplay == Display.DEFAULT_DISPLAY)
                    requireForeground()
                }
                val screenshot = checkNotNull(instrument.uiAutomation.takeScreenshot()) { "실제 화면 캡처를 읽지 못했습니다" }
                try {
                    if (diagnostic) {
                        File(evidence, "$name.png").outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    } else {
                        val rect = checkNotNull(appViewport()).available
                        check(rect.left >= 0 && rect.top >= 0 && rect.right <= screenshot.width && rect.bottom <= screenshot.height)
                        val app = Bitmap.createBitmap(screenshot, rect.left, rect.top, rect.width(), rect.height())
                        try { File(evidence, "$name.png").outputStream().use { check(app.compress(Bitmap.CompressFormat.PNG, 100, it)) } } finally { if (app !== screenshot) app.recycle() }
                    }
                    proof.put("png_status", "saved")
                } finally { screenshot.recycle() }
            } catch (problem: Throwable) {
                proof.put("png_status", "failed").put("png_error", problem.javaClass.simpleName)
                preserve(problem)
            }
            try { File(evidence, "$name.json").writeText(proof.toString(2)) } catch (problem: Throwable) { preserve(problem) }
            failure?.let { throw it }
        }
        internal suspend fun snapshot(graph: RecordingTestEntryPoint): ExistingData =
            withTimeout(30_000) {
                val sessions = graph.sessions().observeSessions().first()
                ExistingData(
                    graph.preparation().observeSelection().first(),
                    sessions,
                    sessions.associate { it.sessionId to graph.observations().observe(it.sessionId).first() },
                    sessions.associate { it.sessionId to graph.memos().observe(it.sessionId).first() },
                    sessions.associate {
                        it.sessionId to
                            graph
                                .recordingRuns()
                                .observe(it.sessionId)
                                .first()
                                .toList()
                    },
                )
            }

        internal suspend fun assertPreserved(
            graph: RecordingTestEntryPoint,
            before: ExistingData,
        ) {
            val after = snapshot(graph)
            assertTrue("원래 준비의 모든 필드를 보존해야 합니다", after.preparation == before.preparation)
            assertTrue("모든 세션 필드와 저장 생성 순서를 보존해야 합니다", after.sessions == before.sessions)
            assertTrue("모든 관찰 필드와 순서를 보존해야 합니다", after.observations == before.observations)
            assertTrue("모든 메모 원문과 변경 토큰을 보존해야 합니다", after.memos == before.memos)
            assertTrue("모든 기록 실행 필드와 저장 생성 순서를 보존해야 합니다", after.recordingRuns == before.recordingRuns)
            assertTrue("읽기 계측으로 활성 연습이 생기면 안 됩니다", after.sessions.none { it.isActive })
        }

        internal fun environmentJson(): JSONObject {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val configuration = context.resources.configuration
            return JSONObject()
                .put("sdkInt", Build.VERSION.SDK_INT)
                .put("release", Build.VERSION.RELEASE)
                .put("buildFingerprint", Build.FINGERPRINT)
                .put("abis", JSONArray(Build.SUPPORTED_ABIS.toList()))
                .put("fontScale", configuration.fontScale.toDouble())
                .put("uiMode", configuration.uiMode)
                .put("screenWidthDp", configuration.screenWidthDp)
                .put("screenHeightDp", configuration.screenHeightDp)
                .put("densityDpi", configuration.densityDpi)
                .put("locale", Locale.getDefault().toLanguageTag())
                .put("timeZone", ZoneId.systemDefault().id)
                .put("debuggable", context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
                .put("productPackage", context.packageName)
                .put("activityOpenedByHarness", true)
                .put(
                    "appViewport",
                    checkNotNull(appViewport()).let { viewport ->
                        JSONObject()
                            .put("left", viewport.available.left)
                            .put("top", viewport.available.top)
                            .put("right", viewport.available.right)
                            .put("bottom", viewport.available.bottom)
                            .put("originX", viewport.originX)
                            .put("originY", viewport.originY)
                    },
                )
        }

        internal fun catalogJson(graph: RecordingTestEntryPoint): JSONObject =
            JSONObject()
                .put("goalsInOrder", JSONArray(graph.catalog().goals.map { it.toString() }))
                .put("coursesInOrder", JSONArray(graph.catalog().courses.map { it.toString() }))

        internal fun appViewport(): AppViewport? {
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

        internal fun fullyVisible(
            node: SemanticsNode,
            viewport: AppViewport,
        ): Boolean {
            if (node.size.width <= 0 || node.size.height <= 0) return false
            val bounds = node.boundsInWindow
            return bounds.width >= node.size.width - 1 && bounds.height >= node.size.height - 1 &&
                bounds.left + viewport.originX >= viewport.available.left - 1 &&
                bounds.top + viewport.originY >= viewport.available.top - 1 &&
                bounds.right + viewport.originX <= viewport.available.right + 1 &&
                bounds.bottom + viewport.originY <= viewport.available.bottom + 1
        }

        internal fun dataJson(data: ExistingData): JSONObject =
            JSONObject()
                .put("preparation", data.preparation?.let(::selectionJson) ?: JSONObject.NULL)
                .put("sessions", JSONArray(data.sessions.map(::sessionJson)))
                .put(
                    "recordingRuns",
                    JSONArray(
                        data.recordingRuns.map { (sessionId, rows) ->
                            JSONObject()
                                .put("sessionId", sessionId)
                                .put("rows", JSONArray(rows.map(::recordingRunJson)))
                        },
                    ),
                ).put(
                    "observations",
                    JSONArray(
                        data.observations.map { (sessionId, rows) ->
                            JSONObject()
                                .put("sessionId", sessionId)
                                .put(
                                    "rows",
                                    JSONArray(
                                        rows.map { row ->
                                            JSONObject()
                                                .put("id", row.id)
                                                .put("sessionId", row.sessionId)
                                                .put("observedAtEpochMillis", row.observation.observedAtEpochMillis)
                                                .put("source", row.observation.source.name)
                                                .put("connection", row.observation.state.connection.name)
                                                .put("gear", readingJson(row.observation.state.gear))
                                                .put("turnSignal", readingJson(row.observation.state.turnSignal))
                                                .put("brakePressed", readingJson(row.observation.state.brakePressed))
                                        },
                                    ),
                                )
                        },
                    ),
                ).put(
                    "memos",
                    JSONArray(
                        data.memos.map { (sessionId, memo) ->
                            JSONObject()
                                .put("sessionId", sessionId)
                                .put(
                                    "memo",
                                    memo?.let { JSONObject().put("sessionId", it.sessionId).put("text", it.text).put("version", it.version) }
                                        ?: JSONObject.NULL,
                                )
                        },
                    ),
                )

        internal fun recordingRunJson(run: VehicleRecordingRun): JSONObject =
            JSONObject()
                .put("runId", run.runId)
                .put("sessionId", run.sessionId)
                .put("processId", run.processId)
                .put("startedAtEpochMillis", run.startedAtEpochMillis)
                .put("endedAtEpochMillis", run.endedAtEpochMillis ?: JSONObject.NULL)
                .put("status", run.status.name)

        internal fun selectionJson(selection: PracticeSelection): JSONObject =
            JSONObject()
                .put("goalId", selection.goalId)
                .put("courseId", selection.courseId)
                .put("courseVersion", selection.courseVersion)
                .put("briefingVersion", selection.briefingVersion)
                .put("taskId", selection.taskId)

        internal fun sessionJson(session: PracticeSession): JSONObject =
            JSONObject()
                .put("sessionId", session.sessionId)
                .put("selection", selectionJson(session.selection))
                .put("goalTitle", session.goalTitle)
                .put("courseTitle", session.courseTitle)
                .put("taskTitle", session.taskTitle)
                .put("courseReviewStatus", session.courseReviewStatus.name)
                .put("startedAtEpochMillis", session.startedAtEpochMillis)
                .put("endedAtEpochMillis", session.endedAtEpochMillis ?: JSONObject.NULL)

        internal fun <T : Any> readingJson(reading: VehicleReading<T>): JSONObject =
            when (reading) {
                is VehicleReading.Available ->
                    JSONObject().put("status", "AVAILABLE").put(
                        "value",
                        when (val value = reading.value) {
                            is Enum<*> -> value.name
                            else -> value
                        },
                    )
                VehicleReading.Unknown -> JSONObject().put("status", "UNKNOWN")
                VehicleReading.Unsupported -> JSONObject().put("status", "UNSUPPORTED")
                VehicleReading.PermissionDenied -> JSONObject().put("status", "PERMISSION_DENIED")
                VehicleReading.Error -> JSONObject().put("status", "ERROR")
            }

        internal fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(65_536)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return digest.digest().joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }
        }

        internal fun requireSameJson(
            expected: Any?,
            actual: Any?,
            field: String,
        ) {
            assertTrue("기준과 비교의 고정 조건이 같아야 합니다 ($field)", sameJson(expected, actual))
        }

        internal fun sameJson(
            expected: Any?,
            actual: Any?,
        ): Boolean {
            if (expected == null || expected === JSONObject.NULL) return actual == null || actual === JSONObject.NULL
            if (actual == null || actual === JSONObject.NULL) return false
            if (expected is JSONObject && actual is JSONObject) {
                val expectedKeys = expected.keys().asSequence().toSet()
                val actualKeys = actual.keys().asSequence().toSet()
                return expectedKeys == actualKeys && expectedKeys.all { sameJson(expected.get(it), actual.get(it)) }
            }
            if (expected is JSONArray && actual is JSONArray) {
                return expected.length() == actual.length() && (0 until expected.length()).all { sameJson(expected.get(it), actual.get(it)) }
            }
            if (expected is Number && actual is Number) {
                return try {
                    BigDecimal(expected.toString()).compareTo(BigDecimal(actual.toString())) == 0
                } catch (_: NumberFormatException) {
                    false
                }
            }
            return expected == actual
        }

        internal data class ExistingData(
            val preparation: PracticeSelection?,
            val sessions: List<PracticeSession>,
            val observations: Map<String, List<SessionVehicleObservation>>,
            val memos: Map<String, SessionMemo?>,
            val recordingRuns: Map<String, List<VehicleRecordingRun>>,
        )
        internal data class AppViewport(val available: Rect, val originX: Int, val originY: Int)
    }
}
