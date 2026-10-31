package io.github.hangillab.hangil

import android.os.SystemClock
import android.util.AtomicFile
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.util.Locale
import java.util.UUID

/**
 * M2 after-only UI wall time. The new action has no before counterpart: no zero baseline,
 * improvement ratio or M0/M1 latency comparison is produced. Exactly five warmups and thirty
 * samples read the same private frozen Main/Room fixture. Timing includes the new fold click,
 * ready/source/value checks, fixed scroll to the end marker, Compose idle and recheck.
 * Preparation scroll, original-data checks, JSON, close and process startup remain outside.
 * SDK gating observation does not establish background-service or road support.
 */
@RunWith(AndroidJUnit4::class)
class SessionHistoryRecordingRunMeasureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun measureNewStoredRunDisclosureAfterOnlyAndPreserveFrozenData() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("원본 보존을 준비한 명시적 M2 실행만 측정합니다", args.getString("manualHistoryRecordingRunMeasure") == "true")
        assertTrue("새 동작의 변경 후 단계만 허용합니다", args.getString("measurementStage") == "after")
        assertTrue("M2는 동일한 기본 라이트 조건만 측정합니다", args.getString("expectedFontScale") == "1.0" && args.getString("expectedDark") == "false")
        val product = checkNotNull(args.getString("declaredProductCommit"))
        val harness = checkNotNull(args.getString("declaredHarnessRevision"))
        assertTrue("제품과 새 하네스 원본 SHA가 완전해야 합니다", product.matches(Regex("[0-9a-fA-F]{40}")) && harness.matches(Regex("[0-9a-fA-F]{40}")))
        val h = SessionHistoryRecordingRunFlowTest.Harness(compose)
        var primary: Throwable? = null
        try {
            h.assertEnvironment()
            runBlocking {
                val before = h.snapshot(h.graph)
                h.assertFrozenBaseline(before, exactTheme = true)
                val source = checkNotNull(before.sessions.firstOrNull { !it.isActive })
                val runs = before.recordingRuns.getValue(source.sessionId)
                assertTrue("고정 source의 실제 실행 목록이 필요합니다", runs.isNotEmpty())
                val originalData = h.dataJson(before)
                val catalog = h.catalogJson(h.graph)
                val environment = h.environmentJson()
                val output = File(h.instrument.targetContext.cacheDir, "measurements/history-recording-runs-after-only-v1.json")
                reserve(output)
                val warmups = JSONArray()
                val samples = JSONArray()
                val report = JSONObject()
                    .put("schema", 1)
                    .put("kind", "history_recording_runs_after_only")
                    .put("measurementStage", "after")
                    .put("runNonce", UUID.randomUUID().toString())
                    .put("status", "started")
                    .put("declaredProductCommit", product.lowercase(Locale.ROOT))
                    .put("declaredHarnessRevision", harness.lowercase(Locale.ROOT))
                    .put("revisionDeclarationSource", "external_runner_not_inferred_on_device")
                    .put("productApkSha256", h.sha256(File(h.instrument.targetContext.applicationInfo.sourceDir)))
                    .put("instrumentationApkSha256", h.sha256(File(h.instrument.context.applicationInfo.sourceDir)))
                    .put("frozenInputBaselineHashes", h.baselineHashesJson())
                    .put("frozenInputBaselineProvenance", h.baselineProvenanceJson())
                    .put("originalData", originalData)
                    .put("fixedSource", h.sessionJson(source))
                    .put("catalog", catalog)
                    .put("environment", environment)
                    .put("measurementContract", measurementContract())
                    .put("plannedWarmups", WARMUPS)
                    .put("plannedSamples", SAMPLES)
                    .put("warmups", warmups)
                    .put("samples", samples)
                    .put("preservedOriginalData", false)
                    .put("fixedSourcePreserved", false)
                    .put("returnedHome", false)
                    .put("frozenInputBaselinesUnchanged", false)
                    .put("environmentPreserved", false)
                    .put("catalogPreserved", false)
                    .put("sdkObserverCancelled", false)
                    .put("automationFlagRestored", false)
                    .put("excludedSamples", 0)
                    .put("causalPerformanceClaim", false)
                write(output, report)
                val sdkObserver = launch { combine(h.graph.vehicle().state, h.graph.restrictions().restrictions) { vehicle, restrictions -> vehicle to restrictions }.collect {} }
                var bodyFailure: Throwable? = null
                var cleanupFailure: Throwable? = null
                fun preserveCleanup(problem: Throwable) {
                    val first = cleanupFailure
                    if (first == null) cleanupFailure = problem else if (first !== problem) first.addSuppressed(problem)
                    bodyFailure?.let { if (it !== problem) it.addSuppressed(problem) }
                }
                var measured = false
                try {
                    h.awaitAllowed()
                    for (index in 0 until WARMUPS + SAMPLES) {
                        h.assertPreserved(h.graph, before)
                        h.assertBaselineUnchanged()
                        h.requireSameJson(environment, h.environmentJson(), "environmentBeforeCall")
                        h.requireSameJson(catalog, h.catalogJson(h.graph), "catalogBeforeCall")
                        assertTrue("현재 실제 P+UX가 허용돼야 합니다", h.liveAllowed())
                        h.touch("home_history")
                        h.assertSource(source, 0, before.sessions.count { !it.isActive })
                        listOf("history_observations", "history_summary_tasks", "history_memo", "history_recording_runs").forEach(h::assertCollapsed)
                        h.scrollVisible("history_recording_runs")
                        h.requireForeground()
                        val sample = JSONObject()
                            .put("index", if (index < WARMUPS) index + 1 else index - WARMUPS + 1)
                            .put("status", "started")
                            .put("elapsedNs", JSONObject.NULL)
                            .put("elapsedMs", JSONObject.NULL)
                            .put("elapsedWaitNs", JSONObject.NULL)
                            .put("observedEndpointElapsedNs", JSONObject.NULL)
                            .put("endpointConfirmed", false)
                            .put("sourceMatched", false)
                            .put("returnedHome", false)
                        (if (index < WARMUPS) warmups else samples).put(sample)
                        val started = SystemClock.elapsedRealtimeNanos()
                        var observedEndpointElapsedNs: Long? = null
                        try {
                            compose.onNodeWithTag("history_recording_runs", useUnmergedTree = true).performClick()
                            val remaining = ENDPOINT_TIMEOUT_NS - (SystemClock.elapsedRealtimeNanos() - started)
                            assertTrue("클릭 뒤 실제 endpoint 시간이 남아 있어야 합니다", remaining > 0)
                            compose.waitUntil((remaining + 999_999) / 1_000_000) { h.nodes("history_recording_run_ready").size == 1 }
                            h.assertRunText(runs, runs.lastIndex)
                            h.scrollVisible("history_recording_run_ended")
                            compose.waitForIdle()
                            h.assertRunText(runs, runs.lastIndex)
                            assertTrue("같은 실제 종료 값 endpoint가 보여야 합니다", h.endpointVisible("history_recording_run_ended"))
                            h.assertTextFits("history_recording_run_ended")
                            h.requireForeground()
                            assertTrue("endpoint 현재 P+UX도 허용돼야 합니다", h.liveAllowed())
                            val elapsed = SystemClock.elapsedRealtimeNanos() - started
                            sample.put("elapsedWaitNs", elapsed)
                            assertTrue("blocking UI 시간을 포함해 실제 20초 이내여야 합니다", elapsed in 1L..ENDPOINT_TIMEOUT_NS)
                            observedEndpointElapsedNs = elapsed
                            sample.put("elapsedNs", elapsed).put("elapsedMs", elapsed / NANOS_PER_MS).put("endpointConfirmed", true)
                            h.assertSource(source, 0, before.sessions.count { !it.isActive })
                            sample.put("sourceMatched", true)
                            h.closeHistory()
                            sample.put("returnedHome", true)
                            h.assertPreserved(h.graph, before)
                            sample.put("status", "success")
                        } catch (failure: Throwable) {
                            sample
                                .put("elapsedWaitNs", SystemClock.elapsedRealtimeNanos() - started)
                                .put("observedEndpointElapsedNs", observedEndpointElapsedNs ?: JSONObject.NULL)
                                .put("elapsedNs", JSONObject.NULL)
                                .put("elapsedMs", JSONObject.NULL)
                                .put("endpointConfirmed", false)
                                .put("status", "failed")
                                .put("failureClass", failure.javaClass.simpleName)
                            throw failure
                        }
                        if (index == WARMUPS - 1) { report.put("status", "warmups_completed"); write(output, report) }
                    }
                    assertTrue("모든 실제 원값이 성공이어야 합니다", successful(warmups) == WARMUPS && successful(samples) == SAMPLES)
                    report.put("statistics", statistics(samples))
                    measured = true
                } catch (failure: Throwable) {
                    bodyFailure = failure
                    report.put("failureClass", failure.javaClass.simpleName)
                    try { h.capture("m2-failure-current-app", null) } catch (capture: Throwable) { failure.addSuppressed(capture) }
                    throw failure
                } finally {
                    withContext(NonCancellable) {
                        try { if (h.nodes("history_close").size == 1) h.closeHistory() } catch (failure: Throwable) { preserveCleanup(failure) }
                        try {
                            assertTrue("마지막 화면은 같은 홈이어야 합니다", h.nodes("home_history").size == 1 && h.nodes("history_ready").isEmpty())
                            report.put("returnedHome", true)
                        } catch (failure: Throwable) { preserveCleanup(failure) }
                        try {
                            val actualSource = withTimeout(30_000) {
                                h.graph.sessions().observeSessions().first().singleOrNull { it.sessionId == source.sessionId }
                            }
                            assertTrue("종료 후에도 같은 고정 source 전 필드를 보존해야 합니다", actualSource == source)
                            report.put("fixedSourcePreserved", true)
                        } catch (failure: Throwable) { preserveCleanup(failure) }
                        try {
                            h.assertPreserved(h.graph, before)
                            report.put("preservedOriginalData", true)
                        } catch (failure: Throwable) { preserveCleanup(failure) }
                        try {
                            h.assertBaselineUnchanged()
                            report.put("frozenInputBaselinesUnchanged", true)
                        } catch (failure: Throwable) { preserveCleanup(failure) }
                        try {
                            h.requireSameJson(environment, h.environmentJson(), "environmentAfterRun")
                            report.put("environmentPreserved", true)
                        } catch (failure: Throwable) { preserveCleanup(failure) }
                        try {
                            h.requireSameJson(catalog, h.catalogJson(h.graph), "catalogAfterRun")
                            report.put("catalogPreserved", true)
                        } catch (failure: Throwable) { preserveCleanup(failure) }
                        try {
                            sdkObserver.cancelAndJoin()
                            report.put("sdkObserverCancelled", true)
                        } catch (failure: Throwable) { preserveCleanup(failure) }
                        try {
                            h.restoreAutomationFlag()
                            report.put("automationFlagRestored", true)
                        } catch (failure: Throwable) { preserveCleanup(failure) }
                        report.put("cleanupFailureClass", cleanupFailure?.javaClass?.simpleName ?: JSONObject.NULL)
                        report.put("warmupCounts", counts(warmups, WARMUPS)).put("sampleCounts", counts(samples, SAMPLES)).put("status", if (measured && bodyFailure == null && cleanupFailure == null) "completed" else "failed")
                        try { write(output, report) } catch (failure: Throwable) {
                            val original = bodyFailure ?: cleanupFailure
                            if (original == null) throw failure else original.addSuppressed(failure)
                        }
                    }
                    if (bodyFailure == null && cleanupFailure != null) throw checkNotNull(cleanupFailure)
                }
            }
        } catch (failure: Throwable) { primary = failure; throw failure }
        finally { try { h.restoreAutomationFlag() } catch (cleanup: Throwable) { if (primary == null) throw cleanup else primary.addSuppressed(cleanup) } }
        h.phase("HISTORY_RECORDING_RUN_M2_AFTER_COMPLETED")
    }

    private fun measurementContract(): JSONObject = JSONObject()
        .put("version", 1)
        .put("method", "SessionHistoryRecordingRunMeasureTest.measureNewStoredRunDisclosureAfterOnlyAndPreserveFrozenData")
        .put("clock", "SystemClock.elapsedRealtimeNanos")
        .put("unit", "ms")
        .put("warmups", WARMUPS)
        .put("samples", SAMPLES)
        .put("start", "directly before history_recording_runs performClick")
        .put("endpoint", "exact latest run values then fixed scroll to visible history_recording_run_ended, Compose idle and same receipt/value recheck")
        .put("endpointTimeoutNs", ENDPOINT_TIMEOUT_NS)
        .put("endpointTimeoutIncludesClickAndComposeBlocking", true)
        .put("composeBlockingTimeoutIsNotAnInterruptibleDeadline", true)
        .put("included", JSONArray(listOf("click dispatch", "stored run read and exact displayed-value/receipt checks", "fixed end-marker scroll", "Compose synchronization/idle", "semantics/geometry checks", "received SDK-state live P/UX recheck", "focused-window/Main lifecycle/display receipt checks")))
        .put("excluded", JSONArray(listOf("history opening", "pre-scroll to the new disclosure", "bulk original Room snapshot/source/catalog/environment comparisons outside the timer", "result JSON persistence", "history close and preservation/cleanup checks", "Activity/process startup")))
        .put("failedSampleFields", "valid elapsedNs/elapsedMs and endpointConfirmed stay null/null/false; elapsedWaitNs records time to failure; a deadline-valid endpoint before a later check failure is retained separately as observedEndpointElapsedNs")
        .put("cacheCondition", "fresh externally started process and Activity; five warmups; no cache clearing")
        .put("order", "five warmups then thirty new run disclosures; same frozen latest ended session; new collapsed history each time")
        .put("build", "same API34 debug/font1.0/light and frozen catalog/data/environment")
        .put("performanceScope", "after-only instrumented new UI wall time, no before counterpart or speed claim")

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

    private companion object {
        const val WARMUPS = 5
        const val SAMPLES = 30
        const val NANOS_PER_MS = 1_000_000.0
        const val ENDPOINT_TIMEOUT_NS = 20_000_000_000L
    }
}
