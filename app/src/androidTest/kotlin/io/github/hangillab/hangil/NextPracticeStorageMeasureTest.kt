package io.github.hangillab.hangil

import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Bundle
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.github.hangillab.hangil.core.domain.NextPracticeRepository
import io.github.hangillab.hangil.core.domain.NextPracticeSaveResult
import io.github.hangillab.hangil.core.domain.NextPracticeSeed
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.PreparedPractice
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
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.math.BigDecimal
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Opt-in real Room baseline and paired old/new cost measurements; select one method explicitly.
 * The baseline predates the product change. The paired method validates that immutable evidence
 * before measuring the old API and the new source-validated API with the same fixed input.
 * No Activity is opened. The external runner owns actual P/UX, fresh process, JDK17/debug APK
 * provenance and original settings/data preservation. Raw inputs and timings stay private.
 * Restoration and validation are outside the timed call; no fake clock/repository or setting change.
 */
@RunWith(AndroidJUnit4::class)
class NextPracticeStorageMeasureTest {
    @Test fun measureExistingPreparationSaveBaselineAndRestoreOriginalData() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "실제 저장 기준 측정을 준비한 명시적 실행에서만 실행합니다",
            arguments.getString("manualNextPracticeMeasure") == "true",
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("전용 debug 앱에서만 실제 저장 지연을 측정합니다", context.packageName == "io.github.hangillab.hangil.debug")
        assertTrue("동일한 API34 기준 환경이어야 합니다", Build.VERSION.SDK_INT == 34)
        val configuration = context.resources.configuration
        assertTrue("기준 글꼴 배율은 1.0이어야 합니다", kotlin.math.abs(configuration.fontScale - 1f) < 0.001f)
        assertTrue(
            "기준 테마는 라이트여야 합니다",
            configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_NO,
        )
        val productCommit = checkNotNull(arguments.getString("baselineProductCommit")) { "제품 기준 커밋 선언이 필요합니다" }
        assertTrue("제품 커밋은 완전한 SHA여야 합니다", productCommit.matches(Regex("[0-9a-fA-F]{40}")))
        val harnessRevision = checkNotNull(arguments.getString("baselineHarnessRevision")) { "계측 원본 식별 선언이 필요합니다" }
        assertTrue("계측 원본 식별 선언이 비어 있으면 안 됩니다", harnessRevision.isNotBlank())
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, RecordingTestEntryPoint::class.java)
        val repository = graph.preparation()
        val vehicle = graph.vehicle()
        val restrictions = graph.restrictions()
        val liveGuard = { allowsPracticeInteraction(vehicle.state.value, restrictions.restrictions.value) }
        runBlocking {
            val before = snapshot(graph)
            assertTrue("기존 활성 연습이 있으면 측정을 시작하지 않습니다", before.sessions.none { it.isActive })
            val original = checkNotNull(before.preparation) { "원래 준비가 있어야 복구 가능한 기준 측정입니다" }
            val originalPrepared = resolve(graph, original)
            val source = checkNotNull(before.sessions.firstOrNull { !it.isActive }) { "기존 종료 연습이 필요합니다" }
            val preparedSource = validatedSource(graph, source)
            val task =
                checkNotNull(preparedSource.course.tasks.firstOrNull { it.id != originalPrepared.task.id }) {
                    "동일 입력 비교를 위해 원래 준비와 다른 과제가 필요합니다"
                }
            val target = source.selection.copy(taskId = task.id)
            val preparedTarget = resolve(graph, target)
            assertTrue("고정 target은 원래 준비와 달라야 합니다", target != original)
            assertTrue(
                "고정 target은 source의 같은 목표와 코스 버전이어야 합니다",
                target.copy(taskId = source.selection.taskId) == source.selection,
            )
            val output = File(context.cacheDir, "measurements/next-practice-baseline.json")
            reserve(output)
            val warmups = JSONArray()
            val samples = JSONArray()
            val report =
                JSONObject()
                    .put("schema", 1)
                    .put("runNonce", UUID.randomUUID().toString())
                    .put("kind", "existing_preparation_save_baseline")
                    .put("status", "started")
                    .put("declaredProductCommit", productCommit.lowercase(Locale.ROOT))
                    .put("declaredHarnessRevision", harnessRevision)
                    .put("revisionDeclarationSource", "external_runner_not_inferred_on_device")
                    .put("productApkSha256", sha256(File(context.applicationInfo.sourceDir)))
                    .put("instrumentationApkSha256", sha256(File(instrumentation.context.applicationInfo.sourceDir)))
                    .put("fixedSource", sessionJson(source))
                    .put("fixedOriginal", selectionJson(original))
                    .put("fixedTarget", selectionJson(target))
                    .put("resolvedOriginal", preparedJson(originalPrepared))
                    .put("resolvedSource", preparedJson(preparedSource))
                    .put("resolvedTarget", preparedJson(preparedTarget))
                    .put("originalData", dataJson(before))
                    .put("environment", environmentJson())
                    .put("sqliteReadOnlyProbe", sqliteReadOnlyProbe())
                    .put("plannedWarmups", WARMUPS)
                    .put("plannedSamples", SAMPLES)
                    .put("timing", "System.nanoTime directly around PracticeRepository.saveSelection")
                    .put("timingUnit", "ms")
                    .put("excludedFromTiming", JSONArray(listOf("SDK checks", "restoration", "reads", "UI", "JUnit duration")))
                    .put("cacheCondition", "fresh externally started process; warm DB connection after five warmups; no cache clearing")
                    .put("checkpointPolicy", "before first write, after warmups and in final cleanup; never inside timed calls")
                    .put("resetPolicy", "one real original-selection save before every warmup and sample; excluded from timing")
                    .put("warmups", warmups)
                    .put("samples", samples)
                    .put("restoredOriginal", false)
                    .put("preservedOtherData", false)
            write(output, report)
            val sdkObserver =
                launch {
                    combine(vehicle.state, restrictions.restrictions) { currentVehicle, currentRestrictions ->
                        currentVehicle to currentRestrictions
                    }.collect {}
                }
            var primaryFailure: Throwable? = null
            var cleanupFailure: Throwable? = null
            var measurementComplete = false
            var attemptedTargetWrite = false
            try {
                awaitAllowed(graph)
                report.put(
                    "initialLiveGate",
                    JSONObject()
                        .put("vehicleConnection", vehicle.state.value.connection.name)
                        .put("gear", readingJson(vehicle.state.value.gear))
                        .put("parkingState", restrictions.restrictions.value.parkingState.name)
                        .put("uxRestrictionState", restrictions.restrictions.value.uxRestrictionState.name)
                        .put("allowed", liveGuard()),
                )
                for (index in 0 until WARMUPS + SAMPLES) {
                    requireStableSource(graph, source, preparedSource, before.sessions)
                    assertTrue("측정 전 실제 P와 UX 허용이 필요합니다", liveGuard())
                    restoreOriginal(
                        graph,
                        original,
                        target,
                        source,
                        before.sessions,
                        attemptedTargetWrite,
                        liveGuard,
                        alwaysWrite = true,
                    )
                    assertTrue("측정 전 원래 준비의 전체 값이 같아야 합니다", repository.observeSelection().first() == original)
                    requireStableSource(graph, source, preparedSource, before.sessions)
                    assertTrue("호출 직전 실제 P와 UX 허용이 필요합니다", liveGuard())
                    val array = if (index < WARMUPS) warmups else samples
                    val sample =
                        JSONObject()
                            .put("index", if (index < WARMUPS) index + 1 else index - WARMUPS + 1)
                            .put("status", "started")
                            .put("elapsedNs", JSONObject.NULL)
                            .put("elapsedMs", JSONObject.NULL)
                            .put("returned", JSONObject.NULL)
                            .put("targetMatched", JSONObject.NULL)
                    array.put(sample)
                    attemptedTargetWrite = true
                    try {
                        withTimeout(30_000) {
                            val started = System.nanoTime()
                            val accepted: Boolean
                            val elapsed: Long
                            try {
                                accepted = repository.saveSelection(target, liveGuard)
                                elapsed = System.nanoTime() - started
                            } catch (failure: Throwable) {
                                val failedElapsed = System.nanoTime() - started
                                sample
                                    .put("elapsedNs", failedElapsed)
                                    .put("elapsedMs", failedElapsed / NANOS_PER_MS)
                                    .put("status", "failed")
                                    .put("failureClass", failure.javaClass.simpleName)
                                throw failure
                            }
                            sample.put("elapsedNs", elapsed).put("elapsedMs", elapsed / NANOS_PER_MS).put("returned", accepted)
                            assertTrue("단조 시각의 측정 구간이 유효해야 합니다", elapsed >= 0)
                            if (!accepted) {
                                sample.put("status", "failed").put("failureClass", "save_returned_false")
                                throw AssertionError("실제 기준 저장 호출이 거부되었습니다")
                            }
                            val matched = repository.observeSelection().first() == target
                            sample.put("targetMatched", matched)
                            if (!matched) {
                                sample.put("status", "failed").put("failureClass", "target_mismatch")
                                throw AssertionError("실제 저장값이 고정 target과 일치하지 않습니다")
                            }
                            assertTrue("반환 뒤에도 동일한 실제 P와 UX 조건이어야 합니다", liveGuard())
                            sample.put("status", "success")
                        }
                    } catch (failure: Throwable) {
                        if (sample.optString("status") != "failed") {
                            sample.put("status", "failed").put("failureClass", failure.javaClass.simpleName)
                        }
                        throw failure
                    }
                    if (index == WARMUPS - 1) {
                        report.put("status", "warmups_completed")
                        write(output, report)
                    }
                }
                assertTrue("워밍업 표본을 모두 실제로 수집해야 합니다", successful(warmups) == WARMUPS)
                assertTrue("측정 표본을 모두 실제로 수집해야 합니다", successful(samples) == SAMPLES)
                report.put("statistics", statistics(samples))
                measurementComplete = true
            } catch (failure: Throwable) {
                primaryFailure = failure
                report.put("failureClass", failure.javaClass.simpleName)
                throw failure
            } finally {
                withContext(NonCancellable) {
                    try {
                        restoreOriginal(graph, original, target, source, before.sessions, attemptedTargetWrite, liveGuard)
                        assertTrue("최종 원래 준비의 전체 값이 같아야 합니다", repository.observeSelection().first() == original)
                        report.put("restoredOriginal", true)
                        assertPreserved(graph, before)
                        report.put("preservedOtherData", true)
                    } catch (failure: Throwable) {
                        cleanupFailure = failure
                        report.put("cleanupFailureClass", failure.javaClass.simpleName)
                        primaryFailure?.addSuppressed(failure)
                    } finally {
                        sdkObserver.cancelAndJoin()
                        report.put("warmupCounts", counts(warmups, WARMUPS)).put("sampleCounts", counts(samples, SAMPLES))
                        report.put("excludedSamples", 0)
                        val completed = measurementComplete && primaryFailure == null && cleanupFailure == null
                        report.put("status", if (completed) "completed" else "failed")
                        try {
                            write(output, report)
                        } catch (failure: Throwable) {
                            val primary = primaryFailure
                            val cleanup = cleanupFailure
                            if (primary != null) {
                                if (primary !== failure) primary.addSuppressed(failure)
                            } else if (cleanup != null) {
                                if (cleanup !== failure) cleanup.addSuppressed(failure)
                            } else {
                                throw failure
                            }
                        }
                    }
                }
                if (primaryFailure == null && cleanupFailure != null) throw checkNotNull(cleanupFailure)
            }
            instrumentation.sendStatus(2, Bundle().apply { putString("stream", "NEXT_PRACTICE_BASELINE_SUCCESS warmups=5 samples=30\n") })
        }
    }

    @Test fun measurePairedPreparationSaveCostsAndRestoreOriginalData() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "기준 원문과 실제 두 저장 API 비교를 준비한 명시적 실행에서만 실행합니다",
            arguments.getString("manualNextPracticeMeasure") == "true",
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("전용 debug 앱에서만 같은 저장 입력을 비교합니다", context.packageName == "io.github.hangillab.hangil.debug")
        val productCommit = checkNotNull(arguments.getString("pairedProductCommit")) { "비교 제품 커밋 선언이 필요합니다" }
        assertTrue("비교 제품 커밋은 완전한 SHA여야 합니다", productCommit.matches(Regex("[0-9a-fA-F]{40}")))
        val harnessRevision = checkNotNull(arguments.getString("pairedHarnessRevision")) { "비교 계측 원본 식별 선언이 필요합니다" }
        assertTrue("비교 계측 원본 식별 선언이 비어 있으면 안 됩니다", harnessRevision.isNotBlank())
        val baselineFile = File(context.cacheDir, "measurements/next-practice-baseline.json")
        assertTrue(
            "완료된 기준 원문과 원자 저장의 미완료 sidecar를 구분해야 합니다",
            baselineFile.isFile && !File(baselineFile.path + ".bak").exists() && !File(baselineFile.path + ".new").exists(),
        )
        val baselineHash = sha256(baselineFile)
        val baseline = readPrivateBaseline(baselineFile)
        validateBaselineReport(baseline)
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, RecordingTestEntryPoint::class.java)
        val oldRepository = graph.preparation()
        val nextRepository = graph.nextPractice()
        val vehicle = graph.vehicle()
        val restrictions = graph.restrictions()
        val liveGuard = { allowsPracticeInteraction(vehicle.state.value, restrictions.restrictions.value) }
        runBlocking {
            val before = snapshot(graph)
            val original = checkNotNull(before.preparation) { "기준과 같은 원래 준비가 필요합니다" }
            val source = checkNotNull(before.sessions.firstOrNull { !it.isActive }) { "기준과 같은 최근 종료 연습이 필요합니다" }
            assertTrue("활성 연습이 생기면 같은 조건 비교를 시작하지 않습니다", before.sessions.none { it.isActive })
            val target = selectionFromJson(baseline.getJSONObject("fixedTarget"))
            val preparedSource = validatedSource(graph, source)
            val preparedOriginal = resolve(graph, original)
            val preparedTarget = resolve(graph, target)
            val seed = checkNotNull(NextPracticeSeed.resolve(graph.catalog(), source)) { "새 저장 API의 실제 source seed가 필요합니다" }
            assertTrue("고정 target은 같은 목표와 코스 버전을 유지해야 합니다", target.copy(taskId = source.selection.taskId) == source.selection)
            assertTrue("고정 target은 기준처럼 원래 준비와 달라야 합니다", target != original)
            requireSameJson(baseline.getJSONObject("fixedOriginal"), selectionJson(original), "fixedOriginal")
            requireSameJson(baseline.getJSONObject("fixedTarget"), selectionJson(target), "fixedTarget")
            requireSameJson(baseline.getJSONObject("fixedSource"), sessionJson(source), "fixedSource")
            requireSameJson(baseline.getJSONObject("resolvedOriginal"), preparedJson(preparedOriginal), "resolvedOriginal")
            requireSameJson(baseline.getJSONObject("resolvedSource"), preparedJson(preparedSource), "resolvedSource")
            requireSameJson(baseline.getJSONObject("resolvedSource"), preparedJson(seed.practice), "seedCatalog")
            requireSameJson(baseline.getJSONObject("resolvedTarget"), preparedJson(preparedTarget), "resolvedTarget")
            requireSameJson(baseline.getJSONObject("originalData"), dataJson(before), "originalData")
            val environment = environmentJson()
            val sqlite = sqliteReadOnlyProbe()
            requireSameJson(baseline.getJSONObject("environment"), environment, "environment")
            requireSameJson(baseline.getJSONObject("sqliteReadOnlyProbe"), sqlite, "sqliteReadOnlyProbe")
            val output = File(context.cacheDir, "measurements/next-practice-paired.json")
            reserve(output)
            val oldWarmups = JSONArray()
            val newWarmups = JSONArray()
            val oldSamples = JSONArray()
            val newSamples = JSONArray()
            val pairs = JSONArray()
            val report =
                JSONObject()
                    .put("schema", 1)
                    .put("runNonce", UUID.randomUUID().toString())
                    .put("kind", "existing_and_atomic_next_preparation_paired")
                    .put("status", "started")
                    .put("declaredProductCommit", productCommit.lowercase(Locale.ROOT))
                    .put("declaredHarnessRevision", harnessRevision)
                    .put("revisionDeclarationSource", "external_runner_not_inferred_on_device")
                    .put("productApkSha256", sha256(File(context.applicationInfo.sourceDir)))
                    .put("instrumentationApkSha256", sha256(File(instrumentation.context.applicationInfo.sourceDir)))
                    .put("baselineSha256", baselineHash)
                    .put("baselineProductCommit", baseline.getString("declaredProductCommit"))
                    .put("baselineHarnessRevision", baseline.getString("declaredHarnessRevision"))
                    .put("baselineProductApkSha256", baseline.getString("productApkSha256"))
                    .put("baselineInstrumentationApkSha256", baseline.getString("instrumentationApkSha256"))
                    .put("fixedSource", sessionJson(source))
                    .put("fixedOriginal", selectionJson(original))
                    .put("fixedTarget", selectionJson(target))
                    .put("resolvedOriginal", preparedJson(preparedOriginal))
                    .put("resolvedSource", preparedJson(preparedSource))
                    .put("resolvedTarget", preparedJson(preparedTarget))
                    .put("originalData", dataJson(before))
                    .put("environment", environment)
                    .put("sqliteReadOnlyProbe", sqlite)
                    .put("plannedWarmupsPerApi", WARMUPS)
                    .put("plannedPairs", SAMPLES)
                    .put("order", "odd pairs old then new; even pairs new then old")
                    .put("timing", "System.nanoTime directly around each real repository saveSelection call")
                    .put("timingUnit", "ms")
                    .put("deltaDirection", "new minus old; positive is additional latency")
                    .put("resetPolicy", baseline.getString("resetPolicy"))
                    .put("excludedFromTiming", baseline.getJSONArray("excludedFromTiming"))
                    .put("cacheCondition", "fresh externally started process; warm DB after five warmups per API; no cache clearing")
                    .put("checkpointPolicy", "before first write, after both warmup sets and in final cleanup; outside timed calls")
                    .put("oldWarmups", oldWarmups)
                    .put("newWarmups", newWarmups)
                    .put("oldSamples", oldSamples)
                    .put("newSamples", newSamples)
                    .put("pairs", pairs)
                    .put("restoredOriginal", false)
                    .put("preservedOtherData", false)
                    .put("baselineUnchanged", false)
                    .put("causalPerformanceClaim", false)
            write(output, report)
            val sdkObserver =
                launch {
                    combine(vehicle.state, restrictions.restrictions) { currentVehicle, currentRestrictions ->
                        currentVehicle to currentRestrictions
                    }.collect {}
                }
            var primaryFailure: Throwable? = null
            var cleanupFailure: Throwable? = null
            var measurementComplete = false
            var attemptedTargetWrite = false

            suspend fun call(
                api: String,
                index: Int,
                phase: String,
                destination: JSONArray,
            ): JSONObject {
                requireStableSource(graph, source, preparedSource, before.sessions)
                assertTrue("두 API 호출 전 실제 P와 UX 허용이 필요합니다", liveGuard())
                restoreOriginal(
                    graph,
                    original,
                    target,
                    source,
                    before.sessions,
                    attemptedTargetWrite,
                    liveGuard,
                    alwaysWrite = true,
                )
                assertTrue("각 호출 전 같은 원래 준비를 확인해야 합니다", oldRepository.observeSelection().first() == original)
                requireStableSource(graph, source, preparedSource, before.sessions)
                assertTrue("각 호출 직전 실제 P와 UX 허용이 필요합니다", liveGuard())
                val sample =
                    JSONObject()
                        .put("api", api)
                        .put("index", index)
                        .put("phase", phase)
                        .put("status", "started")
                        .put("elapsedNs", JSONObject.NULL)
                        .put("elapsedMs", JSONObject.NULL)
                        .put("returned", JSONObject.NULL)
                        .put("targetMatched", JSONObject.NULL)
                destination.put(sample)
                attemptedTargetWrite = true
                try {
                    withTimeout(30_000) {
                        val (result, elapsed) =
                            when (api) {
                                "old" -> timedExistingSave(oldRepository, target, liveGuard, sample)
                                "new" -> timedNextSave(nextRepository, seed, target, liveGuard, sample)
                                else -> throw AssertionError("정의하지 않은 API를 비교할 수 없습니다")
                            }
                        sample.put("elapsedNs", elapsed).put("elapsedMs", elapsed / NANOS_PER_MS)
                        val accepted = if (api == "old") result == true else result == NextPracticeSaveResult.Saved
                        sample.put("returned", if (api == "old") result else result.javaClass.simpleName)
                        assertTrue("각 호출의 단조 시각 구간이 유효해야 합니다", elapsed >= 0)
                        if (!accepted) {
                            sample.put("status", "failed").put("failureClass", "save_rejected")
                            throw AssertionError("실제 비교 저장 호출이 거부되었습니다")
                        }
                        val matched = oldRepository.observeSelection().first() == target
                        sample.put("targetMatched", matched)
                        if (!matched) {
                            sample.put("status", "failed").put("failureClass", "target_mismatch")
                            throw AssertionError("비교 저장값이 고정 target과 일치하지 않습니다")
                        }
                        assertTrue("반환 뒤에도 동일한 실제 P와 UX 조건이어야 합니다", liveGuard())
                        sample.put("status", "success")
                    }
                } catch (failure: Throwable) {
                    if (sample.optString("status") != "failed") {
                        sample.put("status", "failed").put("failureClass", failure.javaClass.simpleName)
                    }
                    throw failure
                }
                return sample
            }
            try {
                awaitAllowed(graph)
                report.put(
                    "initialLiveGate",
                    JSONObject()
                        .put("vehicleConnection", vehicle.state.value.connection.name)
                        .put("gear", readingJson(vehicle.state.value.gear))
                        .put("parkingState", restrictions.restrictions.value.parkingState.name)
                        .put("uxRestrictionState", restrictions.restrictions.value.uxRestrictionState.name)
                        .put("allowed", liveGuard()),
                )
                for (index in 1..WARMUPS) {
                    val order = if (index % 2 == 1) listOf("old", "new") else listOf("new", "old")
                    for (api in order) call(api, index, "warmup", if (api == "old") oldWarmups else newWarmups)
                }
                assertTrue(
                    "두 API의 실제 워밍업을 모두 완료해야 합니다",
                    successful(oldWarmups) == WARMUPS && successful(newWarmups) == WARMUPS,
                )
                report.put("status", "warmups_completed")
                write(output, report)
                for (index in 1..SAMPLES) {
                    val order = if (index % 2 == 1) listOf("old", "new") else listOf("new", "old")
                    val pair =
                        JSONObject()
                            .put("index", index)
                            .put("order", JSONArray(order))
                            .put("status", "started")
                            .put("deltaNs", JSONObject.NULL)
                            .put("deltaMs", JSONObject.NULL)
                            .put("relativeDeltaPercent", JSONObject.NULL)
                    pairs.put(pair)
                    try {
                        for (api in order) call(api, index, "measurement", if (api == "old") oldSamples else newSamples)
                        val oldNs = oldSamples.getJSONObject(index - 1).getLong("elapsedNs")
                        val newNs = newSamples.getJSONObject(index - 1).getLong("elapsedNs")
                        val delta = newNs - oldNs
                        pair
                            .put("oldNs", oldNs)
                            .put("newNs", newNs)
                            .put("deltaNs", delta)
                            .put("deltaMs", delta / NANOS_PER_MS)
                            .put("relativeDeltaPercent", if (oldNs == 0L) JSONObject.NULL else delta.toDouble() / oldNs * 100.0)
                            .put("status", "success")
                    } catch (failure: Throwable) {
                        pair.put("status", "failed").put("failureClass", failure.javaClass.simpleName)
                        throw failure
                    }
                }
                assertTrue(
                    "두 API의 모든 원값과 교차 쌍을 수집해야 합니다",
                    successful(oldSamples) == SAMPLES &&
                        successful(newSamples) == SAMPLES && successful(pairs) == SAMPLES,
                )
                val oldStatistics = statistics(oldSamples)
                val newStatistics = statistics(newSamples)
                report
                    .put("oldStatistics", oldStatistics)
                    .put("newStatistics", newStatistics)
                    .put("oldInterquartileRangeMs", oldStatistics.getDouble("q3") - oldStatistics.getDouble("q1"))
                    .put("newInterquartileRangeMs", newStatistics.getDouble("q3") - newStatistics.getDouble("q1"))
                report.put("pairedDeltaStatistics", pairedDeltaStatistics(pairs))
                report.put(
                    "baselineOldReference",
                    JSONObject()
                        .put("beforeStatistics", baseline.getJSONObject("statistics"))
                        .put("afterOldStatistics", oldStatistics)
                        .put("baselineRawSamples", baseline.getJSONArray("samples"))
                        .put("medianShiftMs", oldStatistics.getDouble("median") - baseline.getJSONObject("statistics").getDouble("median"))
                        .put(
                            "relativeMedianShiftPercent",
                            relativeChange(
                                baseline.getJSONObject("statistics").getDouble("median"),
                                oldStatistics.getDouble("median"),
                            ),
                        ).put("causalPerformanceClaim", false)
                        .put(
                            "interpretation",
                            "Reference only; examine before versus after-old drift before interpreting paired latency costs",
                        ),
                )
                measurementComplete = true
            } catch (failure: Throwable) {
                primaryFailure = failure
                report.put("failureClass", failure.javaClass.simpleName)
                throw failure
            } finally {
                withContext(NonCancellable) {
                    try {
                        restoreOriginal(graph, original, target, source, before.sessions, attemptedTargetWrite, liveGuard)
                        assertTrue("비교 뒤 원래 준비의 전체 값이 같아야 합니다", oldRepository.observeSelection().first() == original)
                        report.put("restoredOriginal", true)
                        assertPreserved(graph, before)
                        report.put("preservedOtherData", true)
                        assertTrue("원래 기준 측정 파일을 변경하면 안 됩니다", sha256(baselineFile) == baselineHash)
                        report.put("baselineUnchanged", true)
                    } catch (failure: Throwable) {
                        cleanupFailure = failure
                        report.put("cleanupFailureClass", failure.javaClass.simpleName)
                        primaryFailure?.addSuppressed(failure)
                    } finally {
                        sdkObserver.cancelAndJoin()
                        report
                            .put("oldWarmupCounts", counts(oldWarmups, WARMUPS))
                            .put("newWarmupCounts", counts(newWarmups, WARMUPS))
                            .put("oldSampleCounts", counts(oldSamples, SAMPLES))
                            .put("newSampleCounts", counts(newSamples, SAMPLES))
                            .put("pairCounts", pairCounts(pairs))
                            .put("excludedSamples", 0)
                        val complete = measurementComplete && primaryFailure == null && cleanupFailure == null
                        report.put("status", if (complete) "completed" else "failed")
                        try {
                            write(output, report)
                        } catch (failure: Throwable) {
                            val primary = primaryFailure
                            val cleanup = cleanupFailure
                            if (primary != null) {
                                if (primary !== failure) primary.addSuppressed(failure)
                            } else if (cleanup != null) {
                                if (cleanup !== failure) cleanup.addSuppressed(failure)
                            } else {
                                throw failure
                            }
                        }
                    }
                }
                if (primaryFailure == null && cleanupFailure != null) throw checkNotNull(cleanupFailure)
            }
            instrumentation.sendStatus(
                2,
                Bundle().apply {
                    putString("stream", "NEXT_PRACTICE_PAIRED_SUCCESS warmupsPerApi=5 pairs=30\n")
                },
            )
        }
    }

    private suspend fun timedExistingSave(
        repository: PracticeRepository,
        target: PracticeSelection,
        liveGuard: () -> Boolean,
        sample: JSONObject,
    ): Pair<Any, Long> {
        val started = System.nanoTime()
        try {
            val result = repository.saveSelection(target, liveGuard)
            val elapsed = System.nanoTime() - started
            return result to elapsed
        } catch (failure: Throwable) {
            val elapsed = System.nanoTime() - started
            sample
                .put("elapsedNs", elapsed)
                .put("elapsedMs", elapsed / NANOS_PER_MS)
                .put("status", "failed")
                .put("failureClass", failure.javaClass.simpleName)
            throw failure
        }
    }

    private suspend fun timedNextSave(
        repository: NextPracticeRepository,
        seed: NextPracticeSeed,
        target: PracticeSelection,
        liveGuard: () -> Boolean,
        sample: JSONObject,
    ): Pair<Any, Long> {
        val started = System.nanoTime()
        try {
            val result = repository.saveSelection(seed, target, liveGuard)
            val elapsed = System.nanoTime() - started
            return result to elapsed
        } catch (failure: Throwable) {
            val elapsed = System.nanoTime() - started
            sample
                .put("elapsedNs", elapsed)
                .put("elapsedMs", elapsed / NANOS_PER_MS)
                .put("status", "failed")
                .put("failureClass", failure.javaClass.simpleName)
            throw failure
        }
    }

    private fun readPrivateBaseline(file: File): JSONObject =
        try {
            JSONObject(file.readText())
        } catch (_: Exception) {
            throw AssertionError("비공개 기준 측정 원문을 읽지 못했습니다")
        }

    private fun validateBaselineReport(baseline: JSONObject) {
        assertTrue(
            "완료된 기존 API 기준 원문이어야 합니다",
            baseline.getString("kind") == "existing_preparation_save_baseline" && baseline.getString("status") == "completed" &&
                baseline.getBoolean("restoredOriginal") && baseline.getBoolean("preservedOtherData"),
        )
        assertTrue(
            "같은 기준 타이머와 복구 절차를 사용해야 합니다",
            baseline.getString("timingUnit") == "ms" &&
                baseline.getString("resetPolicy") ==
                "one real original-selection save before every warmup and sample; excluded from timing",
        )
        assertTrue(
            "기준의 반복 조건을 그대로 사용해야 합니다",
            baseline.getInt("plannedWarmups") == WARMUPS &&
                baseline.getInt("plannedSamples") == SAMPLES && baseline.getInt("excludedSamples") == 0,
        )
        for ((name, size) in listOf("warmups" to WARMUPS, "samples" to SAMPLES)) {
            val values = baseline.getJSONArray(name)
            assertTrue("기준의 완전한 실제 표본을 확인해야 합니다", values.length() == size && successful(values) == size)
            for (index in 0 until size) {
                val sample = values.getJSONObject(index)
                assertTrue(
                    "기준은 빠진 시각이나 실패/target 불일치가 없어야 합니다",
                    !sample.isNull("elapsedNs") &&
                        sample.getLong("elapsedNs") >= 0 && sample.getBoolean("returned") && sample.getBoolean("targetMatched"),
                )
                assertTrue(
                    "기준 원값의 순서와 단위가 일치해야 합니다",
                    sample.getInt("index") == index + 1 &&
                        sample.getDouble("elapsedMs") == sample.getLong("elapsedNs") / NANOS_PER_MS,
                )
            }
            requireSameJson(baseline.getJSONObject(if (name == "warmups") "warmupCounts" else "sampleCounts"), counts(values, size), name)
        }
        requireSameJson(baseline.getJSONObject("statistics"), statistics(baseline.getJSONArray("samples")), "baselineStatistics")
    }

    private fun selectionFromJson(value: JSONObject): PracticeSelection =
        PracticeSelection(
            value.getString("goalId"),
            value.getString("courseId"),
            value.getInt("courseVersion"),
            value.getInt("briefingVersion"),
            value.getString("taskId"),
        )

    private fun requireSameJson(
        expected: Any?,
        actual: Any?,
        field: String,
    ) {
        assertTrue("기준과 비교의 고정 조건이 일치해야 합니다 ($field)", sameJson(expected, actual))
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

    private fun pairCounts(pairs: JSONArray): JSONObject =
        JSONObject()
            .put("successful", successful(pairs))
            .put("failed", (0 until pairs.length()).count { pairs.getJSONObject(it).optString("status") == "failed" })
            .put("missingDelta", (0 until pairs.length()).count { pairs.getJSONObject(it).isNull("deltaNs") })
            .put("notRun", SAMPLES - pairs.length())

    private fun relativeChange(
        before: Double,
        after: Double,
    ): Any = if (before == 0.0) JSONObject.NULL else (after - before) / before * 100.0

    private fun pairedDeltaStatistics(pairs: JSONArray): JSONObject {
        val deltas = (0 until pairs.length()).map { pairs.getJSONObject(it).getDouble("deltaMs") }
        val relative =
            (0 until pairs.length()).mapNotNull {
                pairs.getJSONObject(it).takeUnless { value -> value.isNull("relativeDeltaPercent") }?.getDouble("relativeDeltaPercent")
            }
        return JSONObject()
            .put("absolute", signedDistribution(deltas, "ms"))
            .put("relative", signedDistribution(relative, "percent_of_the_same_pair_old"))
            .put("zeroOldDenominatorPairs", pairs.length() - relative.size)
            .put("direction", "new minus old; no forced improvement interpretation")
    }

    private fun signedDistribution(
        values: List<Double>,
        unit: String,
    ): JSONObject {
        val sorted = values.sorted()
        if (sorted.isEmpty()) return JSONObject().put("status", "unavailable").put("sampleCount", 0).put("unit", unit)

        fun median(part: List<Double>): Double =
            if (part.size % 2 == 0) {
                (part[part.size / 2 - 1] + part[part.size / 2]) / 2.0
            } else {
                part[part.size / 2]
            }
        val result =
            JSONObject()
                .put("status", "calculated")
                .put("unit", unit)
                .put("sampleCount", sorted.size)
                .put("median", median(sorted))
                .put("min", sorted.first())
                .put("max", sorted.last())
        if (sorted.size > 1) {
            val q1 = median(sorted.take(sorted.size / 2))
            val q3 = median(sorted.drop((sorted.size + 1) / 2))
            result.put("q1", q1).put("q3", q3).put("interquartileRange", q3 - q1)
        }
        return result.put("quartileMethod", "median_of_sorted_halves_excluding_middle_when_odd")
    }

    private suspend fun awaitAllowed(graph: RecordingTestEntryPoint) =
        withTimeout(90_000) {
            combine(graph.vehicle().state, graph.restrictions().restrictions) { vehicle, restrictions ->
                allowsPracticeInteraction(vehicle, restrictions)
            }.first { it }
        }

    private fun resolve(
        graph: RecordingTestEntryPoint,
        selection: PracticeSelection,
    ): PreparedPractice = checkNotNull((graph.catalog().resolve(selection) as? Preparation.Ready)?.practice) { "고정 입력의 현재 카탈로그 확인이 필요합니다" }

    private fun validatedSource(
        graph: RecordingTestEntryPoint,
        source: PracticeSession,
    ): PreparedPractice {
        val prepared = resolve(graph, source.selection)
        assertTrue(
            "최근 종료 source의 전체 표시 snapshot이 현재 카탈로그와 같아야 합니다",
            !source.isActive && source.goalTitle == prepared.goal.title && source.courseTitle == prepared.course.title &&
                source.taskTitle == prepared.task.title && source.courseReviewStatus == prepared.course.reviewStatus,
        )
        return prepared
    }

    private suspend fun requireStableSource(
        graph: RecordingTestEntryPoint,
        source: PracticeSession,
        prepared: PreparedPractice,
        originalSessions: List<PracticeSession>,
    ) {
        val current = graph.sessions().observeSessions().first()
        assertTrue("측정 중 모든 세션과 저장 순서를 보존해야 합니다", current == originalSessions)
        assertTrue("측정 중 활성 세션이 생기면 안 됩니다", current.none { it.isActive })
        assertTrue("측정의 최근 종료 source가 고정되어야 합니다", current.firstOrNull { !it.isActive } == source)
        assertTrue("측정의 현재 카탈로그 내용이 고정되어야 합니다", validatedSource(graph, source) == prepared)
    }

    private suspend fun restoreOriginal(
        graph: RecordingTestEntryPoint,
        original: PracticeSelection,
        target: PracticeSelection,
        source: PracticeSession,
        originalSessions: List<PracticeSession>,
        attemptedWrite: Boolean,
        liveGuard: () -> Boolean,
        alwaysWrite: Boolean = false,
    ) = withTimeout(90_000) {
        val current = graph.preparation().observeSelection().first()
        if (current == original && !alwaysWrite) return@withTimeout
        assertTrue("측정이 쓰지 않은 새 준비를 덮으면 안 됩니다", current == original || attemptedWrite && current == target)
        val sessions = graph.sessions().observeSessions().first()
        assertTrue("원래 세션이 달라지면 준비를 임의 복구하지 않습니다", sessions == originalSessions)
        assertTrue("활성 연습 중에는 원래 준비를 임의 복구하지 않습니다", sessions.none { it.isActive })
        assertTrue("복구 전 최근 종료 source가 같아야 합니다", sessions.firstOrNull { !it.isActive } == source)
        awaitAllowed(graph)
        assertTrue("실제 허용 상태에서 원래 준비를 복구해야 합니다", graph.preparation().saveSelection(original, liveGuard))
        assertTrue("복구 결과가 원래 준비와 같아야 합니다", graph.preparation().observeSelection().first() == original)
    }

    private suspend fun snapshot(graph: RecordingTestEntryPoint): ExistingData =
        withTimeout(30_000) {
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
        assertTrue("준비를 원래 값으로 보존해야 합니다", after.preparation == before.preparation)
        assertTrue("모든 세션의 전체 필드와 순서를 보존해야 합니다", after.sessions == before.sessions)
        assertTrue("모든 관찰의 전체 필드와 순서를 보존해야 합니다", after.observations == before.observations)
        assertTrue("모든 메모의 원문과 변경 토큰을 보존해야 합니다", after.memos == before.memos)
    }

    private fun reserve(file: File) {
        assertTrue(
            "기존 기준 측정이나 미완료 증거를 덮으면 안 됩니다",
            !file.exists() && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists(),
        )
        assertTrue("비공개 측정 폴더를 준비해야 합니다", file.parentFile?.let { it.isDirectory || it.mkdirs() } == true)
        assertTrue("새 측정의 소유 기록만 예약해야 합니다", file.createNewFile())
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
            .put("activityStartedByHarness", false)
    }

    private fun sqliteReadOnlyProbe(): JSONObject {
        val file = InstrumentationRegistry.getInstrumentation().targetContext.getDatabasePath("hangil.db")
        val result =
            JSONObject()
                .put("connection", "separate_read_only_connection")
                .put("walAutoCheckpointIsConnectionLocal", true)
                .put("roomConnectionWalAutoCheckpoint", "not_exposed_by_domain_graph")
        return try {
            check(file.isFile)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { database ->
                result.put("readOnly", database.isReadOnly)
                for (name in listOf("journal_mode", "page_size", "wal_autocheckpoint", "user_version")) {
                    database.rawQuery("PRAGMA $name", null).use { cursor ->
                        check(cursor.moveToFirst())
                        result.put(name, if (name == "journal_mode") cursor.getString(0) else cursor.getLong(0))
                    }
                }
            }
            result.put("status", "read")
        } catch (failure: Exception) {
            result.put("status", "unavailable").put("failureClass", failure.javaClass.simpleName)
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
        val values = (0 until samples.length()).map { samples.getJSONObject(it).getLong("elapsedNs") / NANOS_PER_MS }.sorted()
        assertTrue("완전한 실제 표본만 통계로 집계해야 합니다", values.size == SAMPLES && successful(samples) == SAMPLES)

        fun median(sorted: List<Double>): Double =
            if (sorted.size % 2 == 0) {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
            } else {
                sorted[sorted.size / 2]
            }
        return JSONObject()
            .put("unit", "ms")
            .put("sampleCount", values.size)
            .put("median", median(values))
            .put("q1", median(values.take(values.size / 2)))
            .put("q3", median(values.drop(values.size / 2)))
            .put("min", values.first())
            .put("max", values.last())
            .put("quartileMethod", "median_of_each_sorted_half")
            .put("zeroDurationSamples", values.count { it == 0.0 })
            .put("causalPerformanceClaim", false)
    }

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

    private fun preparedJson(prepared: PreparedPractice): JSONObject =
        JSONObject()
            .put(
                "goal",
                JSONObject()
                    .put("id", prepared.goal.id)
                    .put("title", prepared.goal.title)
                    .put("description", prepared.goal.description),
            ).put(
                "course",
                JSONObject()
                    .put("id", prepared.course.id)
                    .put("version", prepared.course.version)
                    .put("briefingVersion", prepared.course.briefingVersion)
                    .put("title", prepared.course.title)
                    .put("summary", prepared.course.summary)
                    .put("sections", JSONArray(prepared.course.sections))
                    .put("reviewStatus", prepared.course.reviewStatus.name)
                    .put("goalIds", JSONArray(prepared.course.goalIds.sorted()))
                    .put(
                        "tasks",
                        JSONArray(
                            prepared.course.tasks.map { task ->
                                JSONObject().put("id", task.id).put("title", task.title).put("description", task.description)
                            },
                        ),
                    ).put(
                        "mapPreview",
                        prepared.course.mapPreview?.let { preview ->
                            JSONObject()
                                .put("id", preview.id)
                                .put("version", preview.version)
                                .put("title", preview.title)
                                .put("source", preview.source.name)
                                .put(
                                    "points",
                                    JSONArray(
                                        preview.points.map { point ->
                                            JSONObject()
                                                .put("id", point.id)
                                                .put("title", point.title)
                                                .put("latitude", point.latitude)
                                                .put("longitude", point.longitude)
                                                .put("osmWayId", point.osmWayId)
                                                .put("osmVersion", point.osmVersion)
                                                .put("sourceUrl", point.sourceUrl)
                                        },
                                    ),
                                )
                        } ?: JSONObject.NULL,
                    ),
            ).put(
                "task",
                JSONObject()
                    .put("id", prepared.task.id)
                    .put("title", prepared.task.title)
                    .put("description", prepared.task.description),
            )

    private fun dataJson(data: ExistingData): JSONObject =
        JSONObject()
            .put("preparation", data.preparation?.let(::selectionJson) ?: JSONObject.NULL)
            .put("sessions", JSONArray(data.sessions.map(::sessionJson)))
            .put(
                "observations",
                JSONArray(
                    data.observations.flatMap { (_, rows) ->
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
                        }
                    },
                ),
            ).put(
                "memos",
                JSONArray(
                    data.memos.map { (sessionId, memo) ->
                        JSONObject().put("sessionId", sessionId).put(
                            "memo",
                            memo?.let {
                                JSONObject().put("sessionId", it.sessionId).put("text", it.text).put("version", it.version)
                            } ?: JSONObject.NULL,
                        )
                    },
                ),
            )

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

    private data class ExistingData(
        val preparation: PracticeSelection?,
        val sessions: List<PracticeSession>,
        val observations: Map<String, List<SessionVehicleObservation>>,
        val memos: Map<String, SessionMemo?>,
    )

    private companion object {
        const val WARMUPS = 5
        const val SAMPLES = 30
        const val NANOS_PER_MS = 1_000_000.0
    }
}
