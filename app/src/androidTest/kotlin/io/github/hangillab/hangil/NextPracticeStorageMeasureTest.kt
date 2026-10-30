package io.github.hangillab.hangil

import android.content.res.Configuration
import android.database.sqlite.SQLiteDatabase
import android.os.Build
import android.os.Bundle
import android.util.AtomicFile
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
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
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID

/**
 * Opt-in baseline of the existing real Room API, before the next-practice product implementation.
 * No Activity is opened. The external runner owns the dedicated idle Pleos device, actual P/UX,
 * fresh app process, JDK17/debug APK provenance and original settings/data preservation evidence.
 * Raw inputs and timings are private cache evidence only. Restoration and validation are outside
 * the timed API call. No new API, fake repository/clock, permission grant or setting change is used.
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
