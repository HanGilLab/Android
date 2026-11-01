package io.github.hangillab.hangil

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclaration
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleRecordingRun
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.Job
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
import java.util.concurrent.atomic.AtomicBoolean
import io.github.hangillab.hangil.core.designsystem.R as DesignR
import io.github.hangillab.hangil.feature.home.R as HomeR

/**
 * Real Main/SDK/production Room READS only. A1/B/A2 use one externally frozen test APK and
 * unchanged schema6 originals. The actual pre-existing nonlatest memo is selected without a
 * fixture, edit, save, delete, permission or setting change. Version stays private and is checked
 * through the real repository, never a fabricated UI tag. Included re-read/Compose/focus/gate
 * costs make this a disclosure wall-time metric, not pure UI/DB/SDK or user-speed evidence.
 * The external runner additionally owns grants/runtime/settings and whole-environment recovery.
 */
@RunWith(AndroidJUnit4::class)
class SessionHistoryMemoReadMeasureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private var mainDisplay: Int? = null
    private var observedActivity: MainActivity? = null
    private var originalInteractiveFlag: Boolean? = null
    private val hostIntervened = AtomicBoolean(false)

    @Volatile private var lastHostEvent: String? = null
    private var lastWindow = JSONObject()
    private val hostObserver =
        LifecycleEventObserver { _, event ->
            if (event in listOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY)) {
                lastHostEvent = event.name
                hostIntervened.set(true)
            }
        }

    @Test fun measureStoredMemoOpeningAndPreserveOriginalData() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("manualHistoryMemoReadMeasure") == "true")
        val stage = checkNotNull(args.getString("measurementStage"))
        check(stage == "baseline" || stage == "comparison")
        val series = checkNotNull(args.getString("measurementSeries"))
        check(series in listOf("history-memo-edit-read-v1", "history-memo-edit-read-repeat-v1"))
        val product = checkNotNull(args.getString("declaredProductCommit")).lowercase(Locale.ROOT)
        val harness = checkNotNull(args.getString("declaredHarnessRevision")).lowercase(Locale.ROOT)
        check(product.matches(Regex("[0-9a-f]{40}")) && harness.matches(Regex("[0-9a-f]{40}")))
        val prefix = "history-memo-read-$series"
        val output = File(instrument.targetContext.cacheDir, "measurements/$prefix-$stage.json")
        val baselineFile = File(instrument.targetContext.cacheDir, "measurements/$prefix-baseline.json")
        val warmups = JSONArray()
        val samples = JSONArray()
        val report =
            JSONObject()
                .put("schema", 1)
                .put("kind", "session_history_memo_read")
                .put("measurementStage", stage)
                .put("measurementSeries", series)
                .put("runNonce", UUID.randomUUID().toString())
                .put("status", "started")
                .put("declaredProductCommit", product)
                .put("declaredHarnessRevision", harness)
                .put("revisionDeclarationSource", "external_runner_not_inferred_on_device")
                .put("sourceToBinaryProof", false)
                .put("plannedWarmups", WARMUPS)
                .put("plannedSamples", SAMPLES)
                .put("warmups", warmups)
                .put("samples", samples)
                .put("measurementContract", measurementContract())
                .put("originalData", JSONObject.NULL)
                .put("fixedSource", JSONObject.NULL)
                .put("fixedMemo", JSONObject.NULL)
                .put("catalog", JSONObject.NULL)
                .put("environment", JSONObject.NULL)
                .put("preservedOriginalData", false)
                .put("returnedHome", false)
                .put("catalogPreserved", false)
                .put("environmentPreserved", false)
                .put("sdkObserverCancelled", false)
                .put("automationFlagRestored", false)
                .put("excludedSamples", 0)
                .put("causalPerformanceClaim", false)
        reserve(output)
        runBlocking {
            var graph: RecordingTestEntryPoint? = null
            var before: ExistingData? = null
            var catalog: JSONObject? = null
            var environment: JSONObject? = null
            var baselineHash: String? = null
            var sdkObserver: Job? = null
            var primary: Throwable? = null
            var cleanupFailure: Throwable? = null
            var complete = false
            var phase = "initialization"
            try {
                write(output, report)
                report
                    .put("productApkSha256", sha256(File(instrument.targetContext.applicationInfo.sourceDir)))
                    .put("instrumentationApkSha256", sha256(File(instrument.context.applicationInfo.sourceDir)))
                assertEnvironmentAndInitialMain()
                val actualGraph =
                    EntryPointAccessors.fromApplication(
                        instrument.targetContext.applicationContext,
                        RecordingTestEntryPoint::class.java,
                    )
                graph = actualGraph
                val original = snapshot(actualGraph)
                before = original
                check(original.preparation != null && original.sessions.none { it.isActive })
                check(
                    original.sessions
                        .map { it.sessionId }
                        .distinct()
                        .size == original.sessions.size,
                )
                val ended = original.sessions.filterNot { it.isActive }
                val memoRows = ended.filter { original.memos.getValue(it.sessionId) != null }
                check(memoRows.size == 1) { "exactly one original memo-bearing ended source is required" }
                val source = memoRows.single()
                val memo = checkNotNull(original.memos.getValue(source.sessionId))
                val sourceIndex = ended.indexOf(source)
                check(sourceIndex > 0) { "the original memo must belong to a nonlatest ended source" }
                check(memo.sessionId == source.sessionId && memo.version.isNotBlank() && memo.text.isNotEmpty())
                check(original.completions.values.all { it == null })
                val frozenCatalog = catalogJson(actualGraph)
                catalog = frozenCatalog
                val frozenEnvironment = environmentJson()
                environment = frozenEnvironment
                report
                    .put("originalData", dataJson(original))
                    .put("fixedSource", sessionJson(source))
                    .put("fixedMemo", memoJson(memo))
                    .put("fixedSourceIndex", sourceIndex)
                    .put("catalog", frozenCatalog)
                    .put("environment", frozenEnvironment)
                val baseline =
                    if (stage == "comparison") {
                        baselineHash = completedBaselineHash(baselineFile)
                        readBaseline(baselineFile).also { previous ->
                            validateBaseline(previous, series)
                            requireSameJson(previous.getJSONObject("originalData"), dataJson(original), "originalData")
                            requireSameJson(previous.getJSONObject("fixedSource"), sessionJson(source), "fixedSource")
                            requireSameJson(previous.getJSONObject("fixedMemo"), memoJson(memo), "fixedMemo")
                            requireSameJson(previous.getJSONObject("catalog"), frozenCatalog, "catalog")
                            requireSameJson(previous.getJSONObject("environment"), frozenEnvironment, "environment")
                            check(previous.getString("declaredHarnessRevision") == harness)
                            check(previous.getString("instrumentationApkSha256") == report.getString("instrumentationApkSha256"))
                            report
                                .put("baselineSha256", baselineHash)
                                .put("baselineProductCommit", previous.getString("declaredProductCommit"))
                                .put("baselineHarnessRevision", previous.getString("declaredHarnessRevision"))
                                .put("baselineUnchanged", false)
                        }
                    } else {
                        null
                    }
                write(output, report)
                sdkObserver =
                    launch {
                        combine(actualGraph.vehicle().state, actualGraph.restrictions().restrictions) { vehicle, restrictions ->
                            vehicle to restrictions
                        }.collect { }
                    }
                awaitAllowed(actualGraph)
                requireForeground()
                report.put("initialLiveGateAllowed", liveAllowed(actualGraph))
                for (index in 0 until WARMUPS + SAMPLES) {
                    val array = if (index < WARMUPS) warmups else samples
                    val sample =
                        JSONObject()
                            .put("index", if (index < WARMUPS) index + 1 else index - WARMUPS + 1)
                            .put("status", "preparing")
                            .put("elapsedNs", JSONObject.NULL)
                            .put("elapsedMs", JSONObject.NULL)
                            .put("elapsedWaitNs", JSONObject.NULL)
                            .put("observedEndpointElapsedNs", JSONObject.NULL)
                            .put("endpointConfirmed", false)
                            .put("sourceMatched", false)
                            .put("returnedHome", false)
                    array.put(sample)
                    var started: Long? = null
                    var observedEndpoint: Long? = null
                    val preparationStarted = SystemClock.elapsedRealtimeNanos()
                    try {
                        phase = "preparation"
                        write(output, report)
                        assertPreserved(actualGraph, original)
                        requireSameJson(frozenCatalog, catalogJson(actualGraph), "catalogBeforeCall")
                        requireSameJson(frozenEnvironment, environmentJson(), "environmentBeforeCall")
                        if (baselineHash != null) check(completedBaselineHash(baselineFile) == baselineHash)
                        check(liveAllowed(actualGraph))
                        assertHomeSource(ended.first())
                        scrollVisible("home_history")
                        compose.onNodeWithTag("home_history", useUnmergedTree = true).performClick()
                        waitFor("history_ready")
                        assertHistorySource(ended.first(), 0, ended.size)
                        for (position in 1..sourceIndex) {
                            scrollVisible("history_older")
                            compose.onNodeWithTag("history_older", useUnmergedTree = true).performClick()
                            compose.waitForIdle()
                            assertHistorySource(ended[position], position, ended.size)
                        }
                        assertCollapsedMemo()
                        // Fixed following anchor leaves room for the unchanged original note.
                        scrollVisible("history_summary_tasks")
                        compose.waitForIdle()
                        val viewport = checkNotNull(appViewport())
                        check(nodes("history_memo").singleOrNull()?.let { fullyVisible(it, viewport) } == true)
                        requireForeground()
                        check(liveAllowed(actualGraph))
                        sample.put("preparationElapsedNs", SystemClock.elapsedRealtimeNanos() - preparationStarted)
                        phase = "endpoint"
                        val timerStart = SystemClock.elapsedRealtimeNanos()
                        started = timerStart
                        compose.onNodeWithTag("history_memo", useUnmergedTree = true).performClick()
                        awaitMemoEndpoint(timerStart, viewport)
                        assertHistorySource(source, sourceIndex, ended.size)
                        assertMemoText(memo, viewport)
                        withTimeout(remainingMillis(timerStart)) {
                            check(actualGraph.memos().observe(source.sessionId).first() == memo)
                            check(actualGraph.sessions().observeSessions().first() == original.sessions)
                        }
                        requireForeground()
                        check(liveAllowed(actualGraph))
                        compose.waitForIdle()
                        assertMemoText(memo, viewport)
                        requireForeground()
                        check(liveAllowed(actualGraph))
                        val elapsed = SystemClock.elapsedRealtimeNanos() - timerStart
                        sample.put("elapsedWaitNs", elapsed)
                        check(elapsed in 0..ENDPOINT_TIMEOUT_NS) { "memo endpoint exceeded twenty seconds" }
                        observedEndpoint = elapsed
                        sample
                            .put("elapsedNs", elapsed)
                            .put("elapsedMs", elapsed / NANOS_PER_MS)
                            .put("endpointConfirmed", true)
                            .put("sourceMatched", true)
                        phase = "post_endpoint_preservation"
                        assertPreserved(actualGraph, original)
                        requireSameJson(frozenEnvironment, environmentJson(), "environmentAfterCall")
                        closeHistory()
                        assertHomeSource(ended.first())
                        assertPreserved(actualGraph, original)
                        sample.put("returnedHome", true).put("status", "success")
                        phase = "report_persistence"
                        write(output, report)
                    } catch (failure: Throwable) {
                        sample
                            .put("status", "failed")
                            .put("failureClass", failure.javaClass.name)
                            .put("failurePhase", phase)
                            .put("elapsedNs", JSONObject.NULL)
                            .put("elapsedMs", JSONObject.NULL)
                            .put("endpointConfirmed", false)
                            .put("observedEndpointElapsedNs", observedEndpoint ?: JSONObject.NULL)
                            .put("elapsedWaitNs", started?.let { SystemClock.elapsedRealtimeNanos() - it } ?: JSONObject.NULL)
                            .put(
                                "preparationElapsedNs",
                                if (started == null) {
                                    SystemClock.elapsedRealtimeNanos() - preparationStarted
                                } else {
                                    sample.getLong("preparationElapsedNs")
                                },
                            )
                        diagnose(report, actualGraph, failure)
                        throw failure
                    }
                }
                check(successful(warmups) == WARMUPS && successful(samples) == SAMPLES)
                val measured = statistics(samples)
                report.put("statistics", measured)
                if (baseline != null) {
                    val previous = baseline.getJSONObject("statistics")
                    val oldMedian = previous.getDouble("median")
                    val newMedian = measured.getDouble("median")
                    report.put(
                        "comparison",
                        JSONObject()
                            .put("beforeStatistics", previous)
                            .put("afterStatistics", measured)
                            .put("medianChangeMs", newMedian - oldMedian)
                            .put(
                                "relativeMedianChangePercent",
                                if (oldMedian == 0.0) {
                                    JSONObject.NULL
                                } else {
                                    (newMedian - oldMedian) / oldMedian * 100
                                },
                            ).put("direction", "comparison minus baseline; positive is slower")
                            .put("causalPerformanceClaim", false)
                            .put("sequentialRunLimitation", "OS, cache, ART/JIT, thermal and execution-order drift are not eliminated"),
                    )
                }
                complete = true
            } catch (failure: Throwable) {
                primary = failure
                report.put("failureClass", failure.javaClass.name).put("failurePhase", phase)
                diagnose(report, graph, failure)
                throw failure
            } finally {
                fun preserve(problem: Throwable) {
                    if (cleanupFailure == null) {
                        cleanupFailure = problem
                    } else if (cleanupFailure !== problem) {
                        cleanupFailure!!.addSuppressed(problem)
                    }
                    primary?.let { if (it !== problem) it.addSuppressed(problem) }
                }
                withContext(NonCancellable) {
                    try {
                        if (nodes("history_close").size == 1) closeHistory()
                    } catch (failure: Throwable) {
                        preserve(failure)
                    }
                    try {
                        requireForeground()
                        check(nodes("home_history").size == 1 && nodes("history_ready").isEmpty())
                        before?.sessions?.firstOrNull { !it.isActive }?.let(::assertHomeSource)
                        report.put("returnedHome", true)
                    } catch (failure: Throwable) {
                        preserve(failure)
                    }
                    try {
                        val original = before
                        val actualGraph = graph
                        check(original != null && actualGraph != null)
                        assertPreserved(actualGraph, original)
                        report.put("preservedOriginalData", true)
                    } catch (failure: Throwable) {
                        preserve(failure)
                    }
                    try {
                        val frozen = catalog
                        val actualGraph = graph
                        check(frozen != null && actualGraph != null)
                        requireSameJson(frozen, catalogJson(actualGraph), "catalogAfterRun")
                        report.put("catalogPreserved", true)
                    } catch (failure: Throwable) {
                        preserve(failure)
                    }
                    try {
                        check(environment != null)
                        requireSameJson(environment, environmentJson(), "environmentAfterRun")
                        report.put("environmentPreserved", true)
                    } catch (failure: Throwable) {
                        preserve(failure)
                    }
                    try {
                        if (stage == "comparison") {
                            check(baselineHash != null && completedBaselineHash(baselineFile) == baselineHash)
                            report.put("baselineUnchanged", true)
                        }
                    } catch (failure: Throwable) {
                        preserve(failure)
                    }
                    try {
                        val observer = sdkObserver
                        if (observer != null) {
                            observer.cancelAndJoin()
                            report.put("sdkObserverCancelled", true)
                        } else {
                            report.put("sdkObserverNotStarted", true)
                        }
                    } catch (failure: Throwable) {
                        preserve(failure)
                    }
                    try {
                        val flagWasChanged = originalInteractiveFlag != null
                        restoreAutomation()
                        if (flagWasChanged) {
                            report.put("automationFlagRestored", true)
                        } else {
                            report.put("automationFlagUnmodified", true)
                        }
                    } catch (failure: Throwable) {
                        preserve(failure)
                    }
                    cleanupFailure?.let { if (primary == null) diagnose(report, graph, it) }
                    report
                        .put("cleanupFailureClass", cleanupFailure?.javaClass?.name ?: JSONObject.NULL)
                        .put("warmupCounts", counts(warmups, WARMUPS))
                        .put("sampleCounts", counts(samples, SAMPLES))
                        .put("status", if (complete && primary == null && cleanupFailure == null) "completed" else "failed")
                    try {
                        write(output, report)
                    } catch (failure: Throwable) {
                        val original = primary ?: cleanupFailure
                        if (original == null) {
                            throw failure
                        } else if (original !== failure) {
                            original.addSuppressed(failure)
                        }
                    }
                }
                if (primary == null) cleanupFailure?.let { throw it }
            }
            instrument.sendStatus(
                2,
                Bundle().apply {
                    putString("stream", "HISTORY_MEMO_READ_MEASUREMENT_SUCCESS stage=$stage warmups=5 samples=30" + System.lineSeparator())
                },
            )
        }
    }

    private fun assertEnvironmentAndInitialMain() {
        val context = instrument.targetContext
        check(context.packageName == "io.github.hangillab.hangil.debug" && Build.VERSION.SDK_INT == 34)
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val config = context.resources.configuration
        check(kotlin.math.abs(config.fontScale - 1f) < 0.001f)
        check(config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_NO)
        val deadline = SystemClock.elapsedRealtime() + 25_000
        var ready = false
        while (!ready && SystemClock.elapsedRealtime() < deadline) {
            instrument.runOnMainSync {
                val activity = compose.activity
                val display = activity.display
                ready = activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                    display != null && display.isValid && activity.hasWindowFocus()
            }
            if (!ready) SystemClock.sleep(50)
        }
        check(ready && SystemClock.elapsedRealtime() < deadline) { "initial real Main focus is unconfirmed" }
        instrument.runOnMainSync {
            val activity = compose.activity
            val display = checkNotNull(activity.display)
            check(activity.lifecycle.currentState == Lifecycle.State.RESUMED && display.isValid && activity.hasWindowFocus())
            mainDisplay = display.displayId
            observedActivity = activity
            activity.lifecycle.addObserver(hostObserver)
        }
        val info = instrument.uiAutomation.serviceInfo
        originalInteractiveFlag = info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        instrument.uiAutomation.serviceInfo = info
        check(instrument.uiAutomation.serviceInfo.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0)
        while (SystemClock.elapsedRealtime() < deadline) {
            requireActualHost()
            val actual = packageNow()
            if (actual != null) {
                check(actual == context.packageName) { "initial focused window belongs to another package" }
                check(SystemClock.elapsedRealtime() < deadline) { "initial Main observation exceeded its deadline" }
                return
            }
            SystemClock.sleep(50)
        }
        error("initial actual Main focused package is unknown")
    }

    private fun requireActualHost() {
        var valid = false
        instrument.runOnMainSync {
            val activity = compose.activity
            val display = activity.display
            valid = activity === observedActivity && activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                display != null && display.isValid && display.displayId == mainDisplay
        }
        check(valid && !hostIntervened.get()) { "real Main host/display was lost" }
    }

    private fun requireForeground() {
        requireActualHost()
        check(packageNow() == instrument.targetContext.packageName) { "actual focused Main package is unconfirmed" }
    }

    private fun packageNow(): String? {
        val proof =
            JSONObject()
                .put("basis", "single-focused-window-on-actual-Main-display")
                .put("mainDisplayKnown", mainDisplay != null)
                .put("windowCount", JSONObject.NULL)
                .put("focusedCount", JSONObject.NULL)
                .put("sameDisplay", JSONObject.NULL)
                .put("rootPresent", false)
                .put("actualPackage", JSONObject.NULL)
                .put("unknownReason", JSONObject.NULL)
        lastWindow = proof

        fun unknown(reason: String): String? {
            proof.put("unknownReason", reason)
            return null
        }
        val display = mainDisplay ?: return unknown("main_display_unknown")
        val windows = mutableListOf<AccessibilityWindowInfo>()
        var root: AccessibilityNodeInfo? = null
        try {
            val all = instrument.uiAutomation.windowsOnAllDisplays
            for (index in 0 until all.size()) windows.addAll(all.valueAt(index))
            val flagObserved =
                instrument.uiAutomation.serviceInfo.flags and
                    AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0
            proof.put("interactiveFlagObserved", flagObserved)
            check(flagObserved) { "interactive-window retrieval flag was lost" }
            val scoped = all[display] ?: return unknown("display_windows_unavailable")
            proof.put("windowCount", scoped.size).put("sameDisplay", scoped.all { it.displayId == display })
            if (scoped.any { it.displayId != display }) return unknown("window_display_mismatch")
            val focused = scoped.filter { it.isFocused }
            proof.put("focusedCount", focused.size)
            if (focused.size != 1) return unknown("focused_window_count_not_one")
            root = focused.single().root ?: return unknown("focused_root_unavailable")
            proof.put("rootPresent", true)
            val actual = root?.packageName?.toString() ?: return unknown("focused_package_unavailable")
            proof.put("actualPackage", actual)
            return actual
        } catch (failure: Throwable) {
            proof.put("unknownReason", "observer_exception").put("exception", failure.javaClass.name)
            throw failure
        } finally {
            root?.recycle()
            windows.forEach { it.recycle() }
        }
    }

    private fun restoreAutomation() {
        var first: Throwable? = null
        try {
            instrument.runOnMainSync { observedActivity?.lifecycle?.removeObserver(hostObserver) }
        } catch (
            failure: Throwable,
        ) {
            first = failure
        }
        try {
            originalInteractiveFlag?.let { before ->
                val info = instrument.uiAutomation.serviceInfo
                info.flags =
                    if (before) {
                        info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    } else {
                        info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS.inv()
                    }
                instrument.uiAutomation.serviceInfo = info
                val actual =
                    instrument.uiAutomation.serviceInfo.flags and
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0
                check(actual == before)
            }
        } catch (failure: Throwable) {
            if (first == null) {
                first = failure
            } else if (first !== failure) {
                first!!.addSuppressed(failure)
            }
        }
        first?.let { throw it }
    }

    private fun assertHistorySource(
        source: PracticeSession,
        index: Int,
        total: Int,
    ) {
        assertText("history_course", source.courseTitle)
        assertText("history_goal", source.goalTitle)
        assertText("history_task", source.taskTitle)
        assertText("history_position", text(HomeR.string.history_position, index + 1, total))
        assertText("history_started", text(HomeR.string.session_started_at, displayTime(source.startedAtEpochMillis)))
        assertText("history_finished", text(HomeR.string.session_finished_at, displayTime(checkNotNull(source.endedAtEpochMillis))))
        if (source.courseReviewStatus == CourseReviewStatus.DEMO) assertText("history_demo", text(HomeR.string.demo_course))
        for (tag in listOf("history_empty", "history_failed", "history_loading", "history_selection_changed", "history_locked")) {
            check(nodes(tag).isEmpty()) { "history failure/unknown is not the selected source" }
        }
    }

    private fun assertCollapsedMemo() {
        val node = nodes("history_memo").single()
        check(node.config.getOrNull(SemanticsProperties.StateDescription) == text(DesignR.string.parked_detail_collapsed))
        check(!node.config.contains(SemanticsProperties.Disabled))
        for (tag in listOf(
            "history_memo_content",
            "history_memo_source",
            "history_memo_text",
            "history_memo_loading",
            "history_memo_empty",
            "history_memo_failed",
        )) {
            check(nodes(tag).isEmpty())
        }
        for (tag in listOf("history_observations", "history_summary_tasks", "history_recording_runs", "history_course_completion")) {
            check(
                nodes(tag).single().config.getOrNull(SemanticsProperties.StateDescription) ==
                    text(DesignR.string.parked_detail_collapsed),
            )
        }
    }

    private fun remainingMillis(started: Long): Long {
        val remaining = started + ENDPOINT_TIMEOUT_NS - SystemClock.elapsedRealtimeNanos()
        check(remaining > 0) { "twenty-second endpoint budget exhausted" }
        return (remaining + 999_999) / 1_000_000
    }

    private fun awaitMemoEndpoint(
        started: Long,
        viewport: AppViewport,
    ) {
        compose.waitUntil(remainingMillis(started)) {
            listOf("history_memo_source", "history_memo_text").all { tag ->
                nodes(tag).singleOrNull()?.let { fullyVisible(it, viewport) } == true
            }
        }
        compose.waitForIdle()
        check(SystemClock.elapsedRealtimeNanos() - started <= ENDPOINT_TIMEOUT_NS)
    }

    private fun assertMemoText(
        memo: SessionMemo,
        viewport: AppViewport,
    ) {
        assertText("history_memo_source", text(HomeR.string.memo_source))
        assertText("history_memo_text", memo.text)
        for (tag in listOf("history_memo_source", "history_memo_text")) {
            val node = nodes(tag).single()
            check(fullyVisible(node, viewport))

            fun descendants(n: SemanticsNode): List<SemanticsNode> = listOf(n) + n.children.flatMap(::descendants)
            val layouts = mutableListOf<TextLayoutResult>()
            compose.runOnIdle {
                descendants(node).forEach {
                    it.config
                        .getOrNull(SemanticsActions.GetTextLayoutResult)
                        ?.action
                        ?.invoke(layouts)
                }
            }
            val expected = if (tag == "history_memo_text") memo.text else text(HomeR.string.memo_source)
            val matching = layouts.filter { it.layoutInput.text.text == expected }
            check(matching.isNotEmpty() && matching.all { textGeometryFits(it, expected) })
        }
        check(
            nodes("history_memo").single().config.getOrNull(SemanticsProperties.StateDescription) ==
                text(DesignR.string.parked_detail_expanded),
        )
        for (tag in listOf("history_memo_empty", "history_memo_loading", "history_memo_failed")) check(nodes(tag).isEmpty())
    }

    /** Check real lines/glyph bounds; tight semantics and paragraph-container widths can differ. */
    private fun textGeometryFits(
        layout: TextLayoutResult,
        expected: String,
    ): Boolean {
        if (expected.isEmpty() || layout.size.width <= 0 || layout.size.height <= 0 || layout.lineCount <= 0 ||
            layout.multiParagraph.didExceedMaxLines
        ) {
            return false
        }

        fun inside(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
        ): Boolean =
            left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() && left <= right && top <= bottom &&
                left >= -1f && top >= -1f && right <= layout.size.width + 1f && bottom <= layout.size.height + 1f
        var covered = 0
        for (line in 0 until layout.lineCount) {
            val end = layout.getLineEnd(line, visibleEnd = false)
            if (layout.getLineStart(line) != covered || end < covered || end > expected.length || layout.isLineEllipsized(line) ||
                !inside(layout.getLineLeft(line), layout.getLineTop(line), layout.getLineRight(line), layout.getLineBottom(line))
            ) {
                return false
            }
            covered = end
        }
        return covered == expected.length &&
            expected.indices.filterNot { expected[it].isWhitespace() }.all {
                val box = layout.getBoundingBox(it)
                inside(box.left, box.top, box.right, box.bottom)
            }
    }

    private fun diagnose(
        report: JSONObject,
        graph: RecordingTestEntryPoint?,
        original: Throwable,
    ) {
        val diagnostic =
            JSONObject()
                .put("hostIntervened", hostIntervened.get())
                .put("lastHostEvent", lastHostEvent ?: JSONObject.NULL)
                .put("lastWindowObservation", lastWindow)
        try {
            instrument.runOnMainSync {
                val activity = compose.activity
                val display = activity.display
                diagnostic
                    .put("mainState", activity.lifecycle.currentState.name)
                    .put("hasWindowFocus", activity.hasWindowFocus())
                    .put("displayKnown", display != null)
                    .put("displayValid", display?.isValid ?: JSONObject.NULL)
                    .put(
                        "sameCapturedDisplay",
                        if (display == null || mainDisplay == null) {
                            JSONObject.NULL
                        } else {
                            display.displayId == mainDisplay
                        },
                    )
            }
        } catch (failure: Throwable) {
            diagnostic.put("hostProbeFailure", failure.javaClass.name)
            original.addSuppressed(failure)
        }
        try {
            val tags = JSONObject()
            for (tag in listOf(
                "home_history",
                "history_ready",
                "history_locked",
                "history_failed",
                "history_memo_content",
                "history_memo_source",
                "history_memo_text",
                "history_memo_loading",
                "history_memo_empty",
                "history_memo_failed",
            )) {
                tags.put(tag, nodes(tag).size)
            }
            diagnostic.put("tagCounts", tags)
        } catch (failure: Throwable) {
            diagnostic.put("semanticsProbeFailure", failure.javaClass.name)
            original.addSuppressed(failure)
        }
        if (graph != null) {
            try {
                diagnostic
                    .put("liveAllowed", liveAllowed(graph))
                    .put(
                        "vehicle",
                        graph
                            .vehicle()
                            .state.value
                            .toString(),
                    ).put(
                        "restrictions",
                        graph
                            .restrictions()
                            .restrictions.value
                            .toString(),
                    )
            } catch (failure: Throwable) {
                diagnostic.put("sdkProbeFailure", failure.javaClass.name)
                original.addSuppressed(failure)
            }
        }
        report.put("diagnostic", diagnostic)
    }

    private suspend fun snapshot(graph: RecordingTestEntryPoint): ExistingData =
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
                sessions.associate { it.sessionId to graph.courseCompletions().observe(it.sessionId).first() },
                physicalDatabase(),
            )
        }

    private suspend fun assertPreserved(
        graph: RecordingTestEntryPoint,
        before: ExistingData,
    ) {
        val after = snapshot(graph)
        check(
            after.preparation == before.preparation && after.sessions == before.sessions && after.observations == before.observations &&
                after.memos == before.memos && after.recordingRuns == before.recordingRuns && after.completions == before.completions,
        )
        check(after.sessions.none { it.isActive } && after.completions.values.all { it == null })
        requireSameJson(before.physical, after.physical, "physicalDatabase")
    }

    private fun physicalDatabase(): JSONObject {
        val file = instrument.targetContext.getDatabasePath("hangil.db")
        check(file.isFile)
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            check(sqlite.version == 6) { "original schema6 is required" }
            val tables = JSONArray()
            sqlite.rawQuery("SELECT name, sql FROM sqlite_master WHERE type='table' ORDER BY name", null).use { names ->
                while (names.moveToNext()) {
                    val name = names.getString(0)
                    val quoted = "\"" + name.replace("\"", "\"\"") + "\""
                    val rows = JSONArray()
                    val columns = JSONArray()
                    sqlite.rawQuery("SELECT rowid AS __measurement_rowid__, * FROM $quoted ORDER BY rowid", null).use { cursor ->
                        cursor.columnNames.forEach { columns.put(it) }
                        while (cursor.moveToNext()) {
                            val row = JSONArray()
                            for (column in 0 until cursor.columnCount) {
                                val value =
                                    when (cursor.getType(column)) {
                                        Cursor.FIELD_TYPE_NULL -> JSONObject().put("type", "NULL").put("value", JSONObject.NULL)
                                        Cursor.FIELD_TYPE_INTEGER ->
                                            JSONObject()
                                                .put(
                                                    "type",
                                                    "INTEGER",
                                                ).put("value", cursor.getLong(column))
                                        Cursor.FIELD_TYPE_FLOAT -> JSONObject().put("type", "FLOAT").put("value", cursor.getDouble(column))
                                        Cursor.FIELD_TYPE_STRING ->
                                            JSONObject()
                                                .put(
                                                    "type",
                                                    "STRING",
                                                ).put("value", cursor.getString(column))
                                        Cursor.FIELD_TYPE_BLOB ->
                                            JSONObject().put("type", "BLOB_HEX").put(
                                                "value",
                                                cursor.getBlob(column).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) },
                                            )
                                        else -> error("unknown actual SQLite field type")
                                    }
                                row.put(value)
                            }
                            rows.put(row)
                        }
                    }
                    tables.put(
                        JSONObject()
                            .put("table", name)
                            .put("columns", columns)
                            .put("rowsInRowIdOrder", rows)
                            .put("createSql", if (names.isNull(1)) JSONObject.NULL else names.getString(1)),
                    )
                }
            }
            sqlite.rawQuery("SELECT COUNT(*) FROM course_completion_declaration", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getLong(0) == 0L) { "original declarations must stay absent" }
            }
            sqlite.rawQuery("PRAGMA foreign_key_check", null).use { check(it.count == 0) }
            JSONObject().put("schemaVersion", sqlite.version).put("tablesInNameOrder", tables).put("foreignKeyViolations", 0)
        }
    }

    private fun memoJson(memo: SessionMemo): JSONObject =
        JSONObject().put("sessionId", memo.sessionId).put("text", memo.text).put("version", memo.version)

    private fun completionJson(declaration: CourseCompletionDeclaration): JSONObject =
        JSONObject()
            .put("source", sessionJson(declaration.source))
            .put("version", declaration.version)
            .put("declaredAtEpochMillis", declaration.declaredAtEpochMillis)
            .put("origin", declaration.origin.name)

    private fun measurementContract(): JSONObject =
        JSONObject()
            .put("version", 1)
            .put(
                "method",
                "SessionHistoryMemoReadMeasureTest.measureStoredMemoOpeningAndPreserveO" +
                    "riginalData",
            ).put("clock", "SystemClock.elapsedRealtimeNanos")
            .put("unit", "ms")
            .put("warmups", WARMUPS)
            .put("samples", SAMPLES)
            .put(
                "start",
                "directly before history_memo performClick on the collapsed original " +
                    "nonlatest memo source",
            ).put(
                "endpoint",
                "source/text Ready-only tags fully visible, original/version " +
                    "repository recheck, idle and endpoint recheck",
            ).put("endpointTimeoutNs", ENDPOINT_TIMEOUT_NS)
            .put("endpointTimeoutIncludesClickAndComposeBlocking", true)
            .put("composeBlockingTimeoutIsNotAnInterruptibleDeadline", true)
            .put(
                "included",
                JSONArray(
                    listOf(
                        "click dispatch",
                        "Compose synchronization/idle",
                        "semantics/full-visibility/text-layout checks",
                        "full selected-source UI checks",
                        "real repository original memo text/version and session-source reread",
                        "received live SDK P+UX gate checks",
                        "actual focused-window/Main lifecycle/display checks",
                    ),
                ),
            ).put(
                "excluded",
                JSONArray(
                    listOf(
                        "history opening and source selection",
                        "history_summary_tasks fixed pre-scroll",
                        "initial collapsed checks",
                        "bulk Room/physical SQLite snapshot/catalog/environment checks outside timer",
                        "JSON persistence",
                        "history close",
                        "initial SDK connection wait",
                        "Activity/process startup",
                    ),
                ),
            ).put("noTimedScroll", true)
            .put("longMemoPolicy", "whole original must fit; no shrinking/truncation/relaxed visibility")
            .put(
                "versionVisibility",
                "private repository receipt equality; version is not fabricated as a " +
                    "public UI tag",
            ).put(
                "failurePolicy",
                "fail fast, preserve failed attempt and remaining notRun counts; never " +
                    "replace a slow/failed sample",
            ).put(
                "failedDurationPolicy",
                "elapsedNs/Ms null; elapsedWaitNs is time to failure; valid earlier " +
                    "endpoint stored separately",
            ).put(
                "order",
                "fresh externally started process/activity, five warmups then thirty " +
                    "repeated read-only disclosures",
            ).put(
                "build",
                "API34 debug font1.0 light; same schema6 original " +
                    "data/source/catalog/environment and frozen test APK",
            ).put(
                "harnessSourceProvenance",
                "declared SHA is runner metadata, not source-to-binary proof; actual " +
                    "APK hashes recorded",
            ).put(
                "performanceScope",
                "instrumented disclosure wall time including repository recheck, not " +
                    "pure UI/DB/SDK/user-speed",
            )

    private fun validateBaseline(
        baseline: JSONObject,
        series: String,
    ) {
        val environmentWasPreserved = baseline.getBoolean("environmentPreserved")
        check(baseline.getInt("schema") == 1)
        check(baseline.getString("kind") == "session_history_memo_read")
        check(baseline.getString("measurementStage") == "baseline")
        check(baseline.getString("measurementSeries") == series)
        check(baseline.getString("status") == "completed")
        check(baseline.getBoolean("preservedOriginalData"))
        check(baseline.getBoolean("returnedHome"))
        check(baseline.getBoolean("catalogPreserved"))
        check(environmentWasPreserved)
        check(baseline.getBoolean("sdkObserverCancelled"))
        check(baseline.getBoolean("automationFlagRestored"))
        check(baseline.getInt("excludedSamples") == 0)
        check(baseline.getString("declaredProductCommit").matches(Regex("[0-9a-f]{40}")))
        check(baseline.getString("declaredHarnessRevision").matches(Regex("[0-9a-f]{40}")))
        check(baseline.getInt("plannedWarmups") == WARMUPS)
        check(baseline.getInt("plannedSamples") == SAMPLES)
        requireSameJson(baseline.getJSONObject("measurementContract"), measurementContract(), "baselineContract")
        for ((name, planned) in listOf("warmups" to WARMUPS, "samples" to SAMPLES)) {
            val values = baseline.getJSONArray(name)
            check(values.length() == planned)
            check(successful(values) == planned)
            for (index in 0 until values.length()) {
                val sample = values.getJSONObject(index)
                check(sample.getInt("index") == index + 1)
                check(sample.getLong("elapsedNs") in 0..ENDPOINT_TIMEOUT_NS)
                check(sample.getDouble("elapsedMs") == sample.getLong("elapsedNs") / NANOS_PER_MS)
                check(sample.getBoolean("endpointConfirmed"))
                check(sample.getBoolean("sourceMatched"))
                check(sample.getBoolean("returnedHome"))
            }
            val countKey = if (name == "warmups") "warmupCounts" else "sampleCounts"
            requireSameJson(baseline.getJSONObject(countKey), counts(values, planned), name)
        }
        requireSameJson(baseline.getJSONObject("statistics"), statistics(baseline.getJSONArray("samples")), "baselineStatistics")
    }

    private suspend fun awaitAllowed(graph: RecordingTestEntryPoint) =
        withTimeout(90_000) {
            combine(graph.vehicle().state, graph.restrictions().restrictions) { vehicle, restrictions ->
                allowsPracticeInteraction(vehicle, restrictions)
            }.first { it }
        }

    private fun liveAllowed(graph: RecordingTestEntryPoint): Boolean =
        allowsPracticeInteraction(graph.vehicle().state.value, graph.restrictions().restrictions.value)

    private fun assertHomeSource(source: PracticeSession) {
        waitFor("home_history")
        assertTrue("이력 페이지를 닫은 홈이어야 합니다", nodes("history_ready").isEmpty())
        assertText("session_latest_course", source.courseTitle)
        assertText("session_latest_finished", text(HomeR.string.session_finished_at, displayTime(checkNotNull(source.endedAtEpochMillis))))
    }

    private fun closeHistory() {
        requireForeground()
        scrollVisible("history_close")
        compose.onNodeWithTag("history_close", useUnmergedTree = true).performClick()
        compose.waitUntil(20_000) { nodes("home_history").size == 1 && nodes("history_ready").isEmpty() }
    }

    private fun assertText(
        tag: String,
        expected: String,
    ) {
        waitFor(tag)
        assertTrue("원래 선택 source의 원문과 일치해야 합니다", nodeTexts(nodes(tag).single()).contains(expected))
    }

    private fun text(
        resource: Int,
        vararg arguments: Any,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resource, *arguments)

    private fun displayTime(epochMillis: Long): String =
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMillis))

    private fun nodeTexts(node: SemanticsNode): List<String> =
        node.config
            .getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .map { it.text } + node.children.flatMap(::nodeTexts)

    private fun nodes(tag: String): List<SemanticsNode> =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun waitFor(tag: String) {
        compose.waitUntil(20_000) { nodes(tag).size == 1 }
    }

    private fun scrollVisible(tag: String) {
        waitFor(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
        compose.waitForIdle()
        val viewport = checkNotNull(appViewport())
        assertTrue("조작 전에 실제 앱 가용 화면에 표시돼야 합니다", fullyVisible(nodes(tag).single(), viewport))
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

    private fun fullyVisible(
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

    private fun environmentJson(): JSONObject {
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

    private fun catalogJson(graph: RecordingTestEntryPoint): JSONObject =
        JSONObject()
            .put("goalsInOrder", JSONArray(graph.catalog().goals.map { it.toString() }))
            .put("coursesInOrder", JSONArray(graph.catalog().courses.map { it.toString() }))

    private fun completedBaselineHash(file: File): String {
        assertTrue(
            "완료 기준 원문과 미완료 sidecar를 구분해야 합니다",
            file.isFile && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists(),
        )
        return sha256(file)
    }

    private fun readBaseline(file: File): JSONObject =
        try {
            JSONObject(file.readText())
        } catch (_: Exception) {
            throw AssertionError("비공개 완료 기준 원문을 읽지 못했습니다")
        }

    private fun reserve(file: File) {
        assertTrue(
            "기존 근거와 미완료 측정 파일을 덮으면 안 됩니다",
            !file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists(),
        )
        assertTrue("비공개 측정 폴더를 준비해야 합니다", file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        assertTrue("이번 실행의 소유 파일만 예약해야 합니다", file.createNewFile())
    }

    private fun write(
        file: File,
        report: JSONObject,
    ) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(report.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    private fun sha256(file: File): String {
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

    private fun requireSameJson(
        expected: Any?,
        actual: Any?,
        field: String,
    ) {
        assertTrue("기준과 비교의 고정 조건이 같아야 합니다 ($field)", sameJson(expected, actual))
    }

    private fun sameJson(
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

    private fun successful(array: JSONArray): Int =
        (0 until array.length()).count { array.getJSONObject(it).optString("status") == "success" }

    private fun counts(
        array: JSONArray,
        planned: Int,
    ): JSONObject =
        JSONObject()
            .put("successful", successful(array))
            .put("failed", (0 until array.length()).count { array.getJSONObject(it).optString("status") == "failed" })
            .put("missingDuration", (0 until array.length()).count { array.getJSONObject(it).isNull("elapsedNs") })
            .put("notRun", planned - array.length())

    private fun statistics(samples: JSONArray): JSONObject {
        assertTrue("완전한 실제 원값만 집계해야 합니다", samples.length() == SAMPLES && successful(samples) == SAMPLES)
        val sorted = (0 until samples.length()).map { samples.getJSONObject(it).getLong("elapsedNs") / NANOS_PER_MS }.sorted()

        fun median(values: List<Double>): Double =
            if (values.size % 2 == 0) {
                (values[values.size / 2 - 1] + values[values.size / 2]) / 2.0
            } else {
                values[values.size / 2]
            }
        val q1 = median(sorted.take(sorted.size / 2))
        val q3 = median(sorted.drop(sorted.size / 2))
        return JSONObject()
            .put("unit", "ms")
            .put("sampleCount", sorted.size)
            .put("median", median(sorted))
            .put("q1", q1)
            .put("q3", q3)
            .put("interquartileRange", q3 - q1)
            .put("min", sorted.first())
            .put("max", sorted.last())
            .put("quartileMethod", "median_of_each_sorted_half")
            .put("zeroDurationSamples", sorted.count { it == 0.0 })
            .put("causalPerformanceClaim", false)
    }

    private fun dataJson(data: ExistingData): JSONObject =
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
            ).put(
                "courseCompletions",
                JSONArray(
                    data.completions.map { (sessionId, declaration) ->
                        JSONObject().put("sessionId", sessionId).put("declaration", declaration?.let(::completionJson) ?: JSONObject.NULL)
                    },
                ),
            ).put("physicalDatabase", data.physical)

    private fun recordingRunJson(run: VehicleRecordingRun): JSONObject =
        JSONObject()
            .put("runId", run.runId)
            .put("sessionId", run.sessionId)
            .put("processId", run.processId)
            .put("startedAtEpochMillis", run.startedAtEpochMillis)
            .put("endedAtEpochMillis", run.endedAtEpochMillis ?: JSONObject.NULL)
            .put("status", run.status.name)

    private fun selectionJson(selection: PracticeSelection): JSONObject =
        JSONObject()
            .put("goalId", selection.goalId)
            .put("courseId", selection.courseId)
            .put("courseVersion", selection.courseVersion)
            .put("briefingVersion", selection.briefingVersion)
            .put("taskId", selection.taskId)

    private fun sessionJson(session: PracticeSession): JSONObject =
        JSONObject()
            .put("sessionId", session.sessionId)
            .put("selection", selectionJson(session.selection))
            .put("goalTitle", session.goalTitle)
            .put("courseTitle", session.courseTitle)
            .put("taskTitle", session.taskTitle)
            .put("courseReviewStatus", session.courseReviewStatus.name)
            .put("startedAtEpochMillis", session.startedAtEpochMillis)
            .put("endedAtEpochMillis", session.endedAtEpochMillis ?: JSONObject.NULL)

    private fun <T : Any> readingJson(reading: VehicleReading<T>): JSONObject =
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
        val recordingRuns: Map<String, List<VehicleRecordingRun>>,
        val completions: Map<String, CourseCompletionDeclaration?>,
        val physical: JSONObject,
    )

    private companion object {
        const val WARMUPS = 5
        const val SAMPLES = 30
        const val NANOS_PER_MS = 1_000_000.0
        const val ENDPOINT_TIMEOUT_NS = 20_000_000_000L
    }
}
