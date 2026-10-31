package io.github.hangillab.hangil

import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
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
import io.github.hangillab.hangil.core.designsystem.R as DesignR
import io.github.hangillab.hangil.feature.home.R as HomeR

/**
 * Opt-in real UI disclosure measurement. The same method/path serves baseline and comparison.
 * Before the Activity rule launches, the external runner must preserve original SQLite/settings,
 * confirm no active session and own the real SDK P/UX and fresh-process/debug APK provenance.
 * Click dispatch, Compose synchronization and semantics checks are included in this UI metric.
 * It is not an app-only frame/GPU/SDK timer or a user-speed claim. No capture, setting change,
 * grant or domain write occurs. Frozen raw inputs, environment and samples remain private.
 */
@RunWith(AndroidJUnit4::class)
class SessionHistoryDisclosureMeasureTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun measureHistoryDisclosureAndPreserveOriginalData() {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(
            "실제 이력 상세 펼침 계측과 외부 원본 보존을 준비한 실행에서만 측정합니다",
            arguments.getString("manualHistoryDisclosureMeasure") == "true",
        )
        val stage = checkNotNull(arguments.getString("measurementStage")) { "기준 또는 비교 단계를 명시해야 합니다" }
        assertTrue("정의한 기준/비교 단계만 실행해야 합니다", stage == "baseline" || stage == "comparison")
        val measurementSeries = arguments.getString("measurementSeries")
        val measurementPrefix =
            when (measurementSeries) {
                null -> "history-disclosure"
                "history-recording-runs-tasks-v1" -> "history-disclosure-history-recording-runs-tasks-v1"
                else -> throw AssertionError("정의한 실험 계열만 실행해야 합니다")
            }
        val productCommit = checkNotNull(arguments.getString("declaredProductCommit")) { "제품 원본 선언이 필요합니다" }
        val harnessRevision = checkNotNull(arguments.getString("declaredHarnessRevision")) { "계측 원본 선언이 필요합니다" }
        assertTrue("제품 원본은 완전한 SHA여야 합니다", productCommit.matches(Regex("[0-9a-fA-F]{40}")))
        assertTrue("계측 원본은 완전한 SHA여야 합니다", harnessRevision.matches(Regex("[0-9a-fA-F]{40}")))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue("전용 debug 앱에서만 실제 UI를 측정합니다", context.packageName == "io.github.hangillab.hangil.debug")
        assertTrue("동일한 API34 환경에서 측정해야 합니다", Build.VERSION.SDK_INT == 34)
        assertTrue("debug 빌드 조건을 유지해야 합니다", context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        val configuration = context.resources.configuration
        assertTrue("기준과 비교는 글꼴1.0이어야 합니다", kotlin.math.abs(configuration.fontScale - 1f) < 0.001f)
        assertTrue(
            "기준과 비교는 라이트여야 합니다",
            configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_NO,
        )
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, RecordingTestEntryPoint::class.java)
        val baselineFile = File(context.cacheDir, "measurements/$measurementPrefix-baseline.json")
        val baselineHash = if (stage == "comparison") completedBaselineHash(baselineFile) else null
        val baseline =
            if (stage == "comparison") {
                readBaseline(baselineFile).also {
                    validateBaseline(it)
                    assertTrue(
                        "기준과 비교는 같은 실험 계열이어야 합니다",
                        if (measurementSeries == null) {
                            !it.has("measurementSeries")
                        } else {
                            it.optString("measurementSeries") == measurementSeries
                        },
                    )
                }
            } else {
                null
            }
        runBlocking {
            val before = snapshot(graph)
            assertTrue("기존 활성 연습이 있으면 계측을 시작하지 않습니다", before.sessions.none { it.isActive })
            assertTrue("원래 준비가 있어야 동일 입력을 확인할 수 있습니다", before.preparation != null)
            val source = checkNotNull(before.sessions.firstOrNull { !it.isActive }) { "기존 종료 source가 필요합니다" }
            assertTrue("종료 source의 원문을 확인해야 합니다", source.courseTitle.isNotBlank() && source.endedAtEpochMillis != null)
            assertTrue(
                "기존 세션 ID는 서로 달라야 합니다",
                before.sessions
                    .map { it.sessionId }
                    .distinct()
                    .size == before.sessions.size,
            )
            val originalData = dataJson(before)
            val frozenCatalog = catalogJson(graph)
            val environment = environmentJson()
            val contract = measurementContract()
            if (baseline != null) {
                requireSameJson(baseline.getJSONObject("originalData"), originalData, "originalData")
                requireSameJson(baseline.getJSONObject("fixedSource"), sessionJson(source), "fixedSource")
                requireSameJson(baseline.getJSONObject("catalog"), frozenCatalog, "catalog")
                requireSameJson(baseline.getJSONObject("environment"), environment, "environment")
                requireSameJson(baseline.getJSONObject("measurementContract"), contract, "measurementContract")
            }
            val output = File(context.cacheDir, "measurements/$measurementPrefix-$stage.json")
            reserve(output)
            val warmups = JSONArray()
            val samples = JSONArray()
            val report =
                JSONObject()
                    .put("schema", 1)
                    .put("kind", "session_history_disclosure")
                    .put("measurementStage", stage)
                    .put("runNonce", UUID.randomUUID().toString())
                    .put("status", "started")
                    .put("declaredProductCommit", productCommit.lowercase(Locale.ROOT))
                    .put("declaredHarnessRevision", harnessRevision.lowercase(Locale.ROOT))
                    .put("revisionDeclarationSource", "external_runner_not_inferred_on_device")
                    .put("productApkSha256", sha256(File(context.applicationInfo.sourceDir)))
                    .put("instrumentationApkSha256", sha256(File(instrumentation.context.applicationInfo.sourceDir)))
                    .put("originalData", originalData)
                    .put("fixedSource", sessionJson(source))
                    .put("catalog", frozenCatalog)
                    .put("environment", environment)
                    .put("measurementContract", contract)
                    .put("plannedWarmups", WARMUPS)
                    .put("plannedSamples", SAMPLES)
                    .put("warmups", warmups)
                    .put("samples", samples)
                    .put("preservedOriginalData", false)
                    .put("returnedHome", false)
                    .put("excludedSamples", 0)
                    .put("causalPerformanceClaim", false)
            if (measurementSeries != null) report.put("measurementSeries", measurementSeries)
            if (baseline != null) {
                report
                    .put("baselineSha256", baselineHash)
                    .put("baselineProductCommit", baseline.getString("declaredProductCommit"))
                    .put("baselineHarnessRevision", baseline.getString("declaredHarnessRevision"))
                    .put("baselineUnchanged", false)
            }
            write(output, report)
            val sdkObserver =
                launch {
                    combine(graph.vehicle().state, graph.restrictions().restrictions) { vehicle, restrictions ->
                        vehicle to restrictions
                    }.collect {}
                }
            var primaryFailure: Throwable? = null
            var cleanupFailure: Throwable? = null
            var measurementComplete = false
            try {
                awaitAllowed(graph)
                report.put("initialLiveGateAllowed", liveAllowed(graph))
                for (index in 0 until WARMUPS + SAMPLES) {
                    assertPreserved(graph, before)
                    requireSameJson(frozenCatalog, catalogJson(graph), "catalogBeforeCall")
                    requireSameJson(environment, environmentJson(), "environmentBeforeCall")
                    assertTrue("실제 P와 UX 허용에서만 이력을 열어야 합니다", liveAllowed(graph))
                    assertHomeSource(source)
                    scrollVisible("home_history")
                    compose.onNodeWithTag("home_history", useUnmergedTree = true).performClick()
                    waitFor("history_ready")
                    assertHistorySource(source, before.sessions.count { !it.isActive })
                    assertCollapsedSummary(source, before.sessions)
                    // Leave room for new scope content without timing any entry or pre-scroll.
                    scrollVisible("history_finished")
                    compose.waitForIdle()
                    requireForeground()
                    val viewport = checkNotNull(appViewport()) { "실제 앱 가용 영역이 필요합니다" }
                    assertTrue(
                        "펼침 버튼은 사전 스크롤 뒤 실제 화면 안에 있어야 합니다",
                        nodes("history_summary_tasks").singleOrNull()?.let { fullyVisible(it, viewport) } == true,
                    )
                    assertTrue("클릭 직전에도 실제 P와 UX가 허용돼야 합니다", liveAllowed(graph))
                    val array = if (index < WARMUPS) warmups else samples
                    val sample =
                        JSONObject()
                            .put("index", if (index < WARMUPS) index + 1 else index - WARMUPS + 1)
                            .put("status", "started")
                            .put("elapsedNs", JSONObject.NULL)
                            .put("elapsedMs", JSONObject.NULL)
                            .put("endpointConfirmed", false)
                            .put("sourceMatched", false)
                            .put("returnedHome", false)
                    array.put(sample)
                    val started = SystemClock.elapsedRealtimeNanos()
                    try {
                        compose.onNodeWithTag("history_summary_tasks", useUnmergedTree = true).performClick()
                        awaitDisclosureEndpoint(started, viewport)
                        val elapsed = SystemClock.elapsedRealtimeNanos() - started
                        sample
                            .put("elapsedNs", elapsed)
                            .put("elapsedMs", elapsed / NANOS_PER_MS)
                            .put("endpointConfirmed", true)
                        assertTrue("실제 endpoint는 클릭 시작부터 20초 안에 확인돼야 합니다", elapsed in 0..ENDPOINT_TIMEOUT_NS)
                        requireForeground()
                        requireSameJson(environment, environmentJson(), "environmentAfterCall")
                        assertTrue("표시 뒤에도 실제 P와 UX 허용을 유지해야 합니다", liveAllowed(graph))
                        assertHistorySource(source, before.sessions.count { !it.isActive })
                        assertExpandedFirstTask(source, before.sessions)
                        sample.put("sourceMatched", true)
                        assertPreserved(graph, before)
                        closeHistory()
                        assertHomeSource(source)
                        assertPreserved(graph, before)
                        sample.put("returnedHome", true).put("status", "success")
                    } catch (failure: Throwable) {
                        if (sample.isNull("elapsedNs")) {
                            val elapsed = SystemClock.elapsedRealtimeNanos() - started
                            sample.put("elapsedNs", elapsed).put("elapsedMs", elapsed / NANOS_PER_MS)
                        }
                        sample.put("status", "failed").put("failureClass", failure.javaClass.simpleName)
                        throw failure
                    }
                    if (index == WARMUPS - 1) {
                        report.put("status", "warmups_completed")
                        write(output, report)
                    }
                }
                assertTrue("워밍업을 모두 실제로 수행해야 합니다", successful(warmups) == WARMUPS)
                assertTrue("모든 원값을 실제로 수집해야 합니다", successful(samples) == SAMPLES)
                val measured = statistics(samples)
                report.put("statistics", measured)
                if (baseline != null) {
                    val previous = baseline.getJSONObject("statistics")
                    val beforeMedian = previous.getDouble("median")
                    val afterMedian = measured.getDouble("median")
                    report.put(
                        "comparison",
                        JSONObject()
                            .put("beforeStatistics", previous)
                            .put("afterStatistics", measured)
                            .put("medianChangeMs", afterMedian - beforeMedian)
                            .put(
                                "relativeMedianChangePercent",
                                if (beforeMedian == 0.0) {
                                    JSONObject.NULL
                                } else {
                                    (afterMedian - beforeMedian) / beforeMedian * 100.0
                                },
                            ).put("direction", "comparison minus baseline; positive is slower")
                            .put("causalPerformanceClaim", false)
                            .put("sequentialRunLimitation", "environment, cache, thermal and execution-order drift are not eliminated"),
                    )
                }
                measurementComplete = true
            } catch (failure: Throwable) {
                primaryFailure = failure
                report.put("failureClass", failure.javaClass.simpleName)
                throw failure
            } finally {
                withContext(NonCancellable) {
                    try {
                        if (nodes("history_close").size == 1) closeHistory()
                        assertTrue("최종 화면은 원래 홈이어야 합니다", nodes("home_history").size == 1 && nodes("history_ready").isEmpty())
                        assertHomeSource(source)
                        report.put("returnedHome", true)
                        assertPreserved(graph, before)
                        report.put("preservedOriginalData", true)
                        requireSameJson(frozenCatalog, catalogJson(graph), "catalogAfterRun")
                        requireSameJson(environment, environmentJson(), "environmentAfterRun")
                        if (baselineHash != null) {
                            assertTrue("완료된 기준 파일은 변경하면 안 됩니다", sha256(baselineFile) == baselineHash)
                            report.put("baselineUnchanged", true)
                        }
                    } catch (failure: Throwable) {
                        cleanupFailure = failure
                        report.put("cleanupFailureClass", failure.javaClass.simpleName)
                        primaryFailure?.addSuppressed(failure)
                    } finally {
                        sdkObserver.cancelAndJoin()
                        report
                            .put("warmupCounts", counts(warmups, WARMUPS))
                            .put("sampleCounts", counts(samples, SAMPLES))
                            .put("excludedSamples", 0)
                            .put(
                                "status",
                                if (measurementComplete && primaryFailure == null && cleanupFailure == null) "completed" else "failed",
                            )
                        try {
                            write(output, report)
                        } catch (failure: Throwable) {
                            val primary = primaryFailure
                            val cleanup = cleanupFailure
                            if (primary != null) {
                                primary.addSuppressed(failure)
                            } else if (cleanup != null) {
                                cleanup.addSuppressed(failure)
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
                    putString("stream", "HISTORY_DISCLOSURE_MEASUREMENT_SUCCESS stage=$stage warmups=5 samples=30" + System.lineSeparator())
                },
            )
        }
    }

    /** Same endpoint in both versions; all Compose blocking/synchronization remains included. */
    private fun awaitDisclosureEndpoint(
        started: Long,
        viewport: AppViewport,
    ) {
        val deadline = started + ENDPOINT_TIMEOUT_NS
        val remaining = deadline - SystemClock.elapsedRealtimeNanos()
        assertTrue("클릭 전달 뒤 실제 endpoint 제한 시간이 남아 있어야 합니다", remaining > 0)
        compose.waitUntil((remaining + 999_999) / 1_000_000) {
            nodes("history_ready").size == 1 &&
                nodes("history_summary_scope").singleOrNull()?.let { fullyVisible(it, viewport) } == true
        }
        compose.waitForIdle()
        assertTrue(
            "idle 뒤에도 같은 실제 endpoint가 보여야 합니다",
            nodes("history_ready").size == 1 &&
                nodes("history_summary_scope").singleOrNull()?.let { fullyVisible(it, viewport) } == true,
        )
        assertTrue(
            "Compose 자체 대기 제한과 별개로 실제 20초 endpoint를 지켜야 합니다",
            SystemClock.elapsedRealtimeNanos() <= deadline,
        )
    }

    private suspend fun awaitAllowed(graph: RecordingTestEntryPoint) =
        withTimeout(90_000) {
            combine(graph.vehicle().state, graph.restrictions().restrictions) { vehicle, restrictions ->
                allowsPracticeInteraction(vehicle, restrictions)
            }.first { it }
        }

    private fun liveAllowed(graph: RecordingTestEntryPoint): Boolean =
        allowsPracticeInteraction(graph.vehicle().state.value, graph.restrictions().restrictions.value)

    private fun requireForeground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(
            "실제 앱 전면에서만 측정해야 합니다",
            instrumentation.uiAutomation.rootInActiveWindow
                ?.packageName
                ?.toString() == instrumentation.targetContext.packageName,
        )
    }

    private fun assertHomeSource(source: PracticeSession) {
        waitFor("home_history")
        assertTrue("이력 페이지를 닫은 홈이어야 합니다", nodes("history_ready").isEmpty())
        assertText("session_latest_course", source.courseTitle)
        assertText("session_latest_finished", text(HomeR.string.session_finished_at, displayTime(checkNotNull(source.endedAtEpochMillis))))
    }

    private fun assertHistorySource(
        source: PracticeSession,
        total: Int,
    ) {
        assertText("history_course", source.courseTitle)
        assertText("history_goal", source.goalTitle)
        assertText("history_task", source.taskTitle)
        assertText("history_started", text(HomeR.string.session_started_at, displayTime(source.startedAtEpochMillis)))
        assertText("history_finished", text(HomeR.string.session_finished_at, displayTime(checkNotNull(source.endedAtEpochMillis))))
        assertText("history_position", text(HomeR.string.history_position, 1, total))
        if (source.courseReviewStatus == CourseReviewStatus.DEMO) {
            assertText("history_demo", text(HomeR.string.demo_course))
        } else {
            assertTrue("시연 출처를 만들면 안 됩니다", nodes("history_demo").isEmpty())
        }
        for (tag in listOf("history_empty", "history_failed", "history_loading", "history_selection_changed", "history_locked")) {
            assertTrue("오류와 잠금을 정상 endpoint로 바꾸면 안 됩니다", nodes(tag).isEmpty())
        }
        assertTrue(
            "최신 기록보다 최근으로 이동할 수 없어야 합니다",
            nodes("history_newer").single().config.contains(SemanticsProperties.Disabled),
        )
    }

    /** Frozen historical input projection, independent of the product's summary factory. */
    private fun sameCourseRows(
        source: PracticeSession,
        records: List<PracticeSession>,
    ): List<PracticeSession> =
        records.filter { row ->
            val sameGoal = row.selection.goalId == source.selection.goalId && row.goalTitle == source.goalTitle
            val sameCourse = row.selection.courseId == source.selection.courseId && row.courseTitle == source.courseTitle
            val sameCourseVersion = row.selection.courseVersion == source.selection.courseVersion
            val sameBriefingVersion = row.selection.briefingVersion == source.selection.briefingVersion
            val sameReview = row.courseReviewStatus == source.courseReviewStatus
            !row.isActive && sameGoal && sameCourse && sameCourseVersion && sameBriefingVersion && sameReview
        }

    private fun assertCollapsedSummary(
        source: PracticeSession,
        records: List<PracticeSession>,
    ) {
        assertText("history_summary_heading", text(HomeR.string.history_summary_title))
        assertText("history_summary_count", text(HomeR.string.history_summary_count, sameCourseRows(source, records).size))
        assertText("history_summary_tasks", text(HomeR.string.history_summary_tasks))
        val button = nodes("history_summary_tasks").single()
        assertTrue(
            "새 이력은 실제 접힌 상태에서 시작해야 합니다",
            button.config.getOrNull(SemanticsProperties.StateDescription) == text(DesignR.string.parked_detail_collapsed),
        )
        assertTrue("주차 중 펼침 버튼은 허용되어야 합니다", !button.config.contains(SemanticsProperties.Disabled))
        assertTrue(
            "타이머 전에 scope/첫 과제를 미리 만들면 안 됩니다",
            nodes("history_summary_scope").isEmpty() && nodes("history_summary_task_0").isEmpty() &&
                nodes("history_summary_task_count_0").isEmpty(),
        )
    }

    private fun assertExpandedFirstTask(
        source: PracticeSession,
        records: List<PracticeSession>,
    ) {
        val group = sameCourseRows(source, records)
        val first = checkNotNull(group.firstOrNull()) { "실제 source의 과제별 기록이 필요합니다" }
        val count = group.count { it.selection.taskId == first.selection.taskId && it.taskTitle == first.taskTitle }
        assertText("history_summary_scope", text(HomeR.string.history_summary_scope))
        assertTrue(
            "타이머 뒤 실제 펼침 상태를 대조해야 합니다",
            nodes("history_summary_tasks").single().config.getOrNull(SemanticsProperties.StateDescription) ==
                text(DesignR.string.parked_detail_expanded),
        )
        scrollVisible("history_summary_task_0")
        assertText("history_summary_task_0", first.taskTitle)
        scrollVisible("history_summary_task_count_0")
        assertText("history_summary_task_count_0", text(HomeR.string.history_summary_count, count))
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
        assertTrue("원래 최신 source의 원문과 일치해야 합니다", nodeTexts(nodes(tag).single()).contains(expected))
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
            )
        }

    private suspend fun assertPreserved(
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

    private fun measurementContract(): JSONObject =
        JSONObject()
            .put("version", 1)
            .put("method", "SessionHistoryDisclosureMeasureTest.measureHistoryDisclosureAndPreserveOriginalData")
            .put("clock", "SystemClock.elapsedRealtimeNanos")
            .put("unit", "ms")
            .put("warmups", WARMUPS)
            .put("samples", SAMPLES)
            .put("start", "directly before history_summary_tasks performClick")
            .put("endpoint", "history_summary_scope physically fully visible then Compose idle and endpoint recheck")
            .put("endpointTimeoutNs", ENDPOINT_TIMEOUT_NS)
            .put("endpointTimeoutIncludesClickAndComposeBlocking", true)
            .put("composeBlockingTimeoutIsNotAnInterruptibleDeadline", true)
            .put("included", JSONArray(listOf("click dispatch", "Compose synchronization/idle", "semantics queries")))
            .put(
                "excluded",
                JSONArray(
                    listOf(
                        "history opening",
                        "history_finished pre-scroll",
                        "initial collapsed checks",
                        "SDK/source/data checks",
                        "first-task source comparison",
                        "JSON",
                        "close",
                        "Activity/process startup",
                    ),
                ),
            ).put("cacheCondition", "fresh externally started process and Activity; five warmups; no cache clearing")
            .put("order", "five warmups then thirty disclosures; open a new collapsed history from same home/source each time")
            .put("build", "same API34 debug/font1.0/light and frozen catalog/data/environment")
            .put("harnessSourceProvenance", "external declared full SHA; runner must freeze this source across both versions")
            .put("performanceScope", "instrumented UI disclosure wall time; not pure render/frame/GPU/SDK/user speed")

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

    private fun validateBaseline(baseline: JSONObject) {
        assertTrue(
            "완료된 같은 이력 상세 펼침 기준이어야 합니다",
            baseline.getInt("schema") == 1 && baseline.getString("kind") == "session_history_disclosure" &&
                baseline.getString("measurementStage") == "baseline" && baseline.getString("status") == "completed" &&
                baseline.getBoolean("preservedOriginalData") && baseline.getBoolean("returnedHome") &&
                baseline.getInt("excludedSamples") == 0,
        )
        assertTrue(
            "기준 원본의 선언이 완전해야 합니다",
            baseline.getString("declaredProductCommit").matches(Regex("[0-9a-f]{40}")) &&
                baseline.getString("declaredHarnessRevision").matches(Regex("[0-9a-f]{40}")),
        )
        assertTrue(
            "기준 반복 횟수가 같아야 합니다",
            baseline.getInt("plannedWarmups") == WARMUPS && baseline.getInt("plannedSamples") == SAMPLES,
        )
        requireSameJson(baseline.getJSONObject("measurementContract"), measurementContract(), "baselineContract")
        for ((name, planned) in listOf("warmups" to WARMUPS, "samples" to SAMPLES)) {
            val values = baseline.getJSONArray(name)
            assertTrue("기준 원값이 모두 성공이어야 합니다", values.length() == planned && successful(values) == planned)
            for (index in 0 until values.length()) {
                val value = values.getJSONObject(index)
                assertTrue("기준 원값과 순서가 빠지면 안 됩니다", value.getInt("index") == index + 1 && !value.isNull("elapsedNs"))
                assertTrue(
                    "기준 시간과 단위가 실제 범위여야 합니다",
                    value.getLong("elapsedNs") in 0..ENDPOINT_TIMEOUT_NS &&
                        value.getDouble("elapsedMs") == value.getLong("elapsedNs") / NANOS_PER_MS,
                )
                assertTrue(
                    "기준 endpoint와 선택/홈 복귀를 확인해야 합니다",
                    value.getBoolean("endpointConfirmed") && value.getBoolean("sourceMatched") && value.getBoolean("returnedHome"),
                )
            }
            requireSameJson(
                baseline.getJSONObject(if (name == "warmups") "warmupCounts" else "sampleCounts"),
                counts(values, planned),
                name,
            )
        }
        requireSameJson(baseline.getJSONObject("statistics"), statistics(baseline.getJSONArray("samples")), "baselineStatistics")
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
            )

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
    )

    private companion object {
        const val WARMUPS = 5
        const val SAMPLES = 30
        const val NANOS_PER_MS = 1_000_000.0
        const val ENDPOINT_TIMEOUT_NS = 20_000_000_000L
    }
}
