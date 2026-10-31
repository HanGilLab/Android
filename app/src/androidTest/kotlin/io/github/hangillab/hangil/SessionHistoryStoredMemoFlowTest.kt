package io.github.hangillab.hangil

import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.util.AtomicFile
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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
import kotlinx.coroutines.delay
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
 * Opt-in actual Main/SDK/Room validation. First seed creates only two explicit test-owned sessions
 * and one generic user-note-shaped memo through the existing UI after comparison measurements.
 * All original data is retained; the owned additions and increased sequence are never described
 * as byte-identical original DB state. Later light/dark runs only read the resulting fixed data.
 * Synthetic failures and frozen callback replay belong to separate controlled/JVM tests.
 */
@RunWith(AndroidJUnit4::class)
class SessionHistoryStoredMemoFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val evidenceDirectory by lazy {
        File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "screenshots/history-stored-memo-${UUID.randomUUID()}",
        ).apply { check(mkdirs()) }
    }

    @Test fun createOnlyOwnedPastMemoAfterComparisonMeasurements() {
        assumeTrue(
            "검사 소유 추가와 전후 계측을 준비한 실행에서만 자료를 만듭니다",
            InstrumentationRegistry.getArguments().getString("manualHistoryMemoSeed") == "true",
        )
        assertTrue(
            "두 전후 계측 완료를 외부 runner가 먼저 확인해야 합니다",
            InstrumentationRegistry.getArguments().getString("measurementComparisonCompleted") == "true",
        )
        assertEnvironment()
        val graph = graph()
        runBlocking {
            awaitAllowed(graph)
            val file = ownerFile()
            if (file.exists()) {
                val saved = JSONObject(file.readText())
                assertTrue(
                    "이미 완료한 같은 검사 소유 자료만 재사용하며 중복 생성하지 않습니다",
                    saved.optString("producer") == "SessionHistoryStoredMemoFlowTest" && saved.optString("phase") == "ready",
                )
                assertSeedPreserved(snapshot(graph), saved)
                return@runBlocking
            }
            val before = snapshot(graph)
            assertTrue("활성 연습이 있으면 새 자료를 만들지 않습니다", before.sessions.none { it.isActive })
            assertTrue("실제 저장된 준비가 있어야 합니다", before.preparation != null)
            val owner =
                JSONObject()
                    .put("producer", "SessionHistoryStoredMemoFlowTest")
                    .put("phase", "starting_first")
                    .put("baseline", baselineJson(before))
                    .put("owned_ids", JSONArray())
            assertTrue("같은 소유 기록을 덮어쓰지 않습니다", file.createNewFile())
            writeOwner(owner)
            val sdkObserver = launch { graph.vehicle().state.collect {} }
            try {
                val first = createOwnedSession(graph, before.sessions.map { it.sessionId }.toSet(), owner)
                owner.put("memo_session_id", first.sessionId).put("phase", "writing_owned_memo")
                writeOwner(owner)
                waitFor("home_recent_memo")
                touch("home_recent_memo")
                waitFor("memo_textfield")
                touch("memo_textfield")
                compose.onNodeWithTag("memo_textfield").performTextInput(OWNED_NOTE)
                touch("memo_save")
                val memo = withTimeout(20_000) { graph.memos().observe(first.sessionId).first { it?.text == OWNED_NOTE } }
                assertTrue("검사 소유 메모에는 실제 저장 버전이 있어야 합니다", memo != null && memo.version.isNotBlank())
                owner.put("owned_memo", checkNotNull(memo).toString()).put("phase", "memo_saved")
                writeOwner(owner)
                waitFor("memo_saved_text")
                assertTextVisible("memo_saved_text", OWNED_NOTE)
                assertTextVisible("memo_source", text(HomeR.string.memo_source))
                touch("home_recent_memo")
                val second = createOwnedSession(graph, before.sessions.map { it.sessionId }.toSet() + first.sessionId, owner)
                assertTrue("메모 세션은 이제 최신 세션이 아닌 과거 기록이어야 합니다", second.sessionId != first.sessionId)
                owner.put("owned_data", baselineJson(snapshot(graph))).put("phase", "ready")
                writeOwner(owner)
                assertSeedPreserved(snapshot(graph), owner)
            } finally {
                sdkObserver.cancelAndJoin()
                assertSeedPreserved(snapshot(graph), owner)
            }
            phase("HISTORY_MEMO_SEED_COMPLETED")
        }
    }

    @Test fun storedPastMemoRemainsReadOnlyAcrossSelectionLifecycleAndActualDrivingLock() {
        assumeTrue(
            "원래 자료/소유 기록과 실제 DDS 복구를 준비한 실행만 검사합니다",
            InstrumentationRegistry.getArguments().getString("manualHistoryStoredMemo") == "true",
        )
        assertEnvironment()
        val graph = graph()
        val owner = JSONObject(ownerFile().readText())
        assertTrue(
            "같은 검사에서 완료한 양성 자료만 사용합니다",
            owner.optString("producer") == "SessionHistoryStoredMemoFlowTest" && owner.optString("phase") == "ready",
        )
        runBlocking {
            awaitAllowed(graph)
            val before = snapshot(graph)
            assertSeedPreserved(before, owner)
            val ended = before.sessions
            assertTrue("읽기 검증 중 활성 연습은 없어야 합니다", ended.none { it.isActive })
            val pastIndex = ended.indexOfFirst { it.sessionId == owner.getString("memo_session_id") }
            assertTrue("양성 메모가 실제 과거 기록이어야 합니다", pastIndex > 0)
            val note = checkNotNull(before.memos[ended[pastIndex].sessionId])
            assertTrue("최초 저장 원문과 버전을 유지해야 합니다", note.toString() == owner.getString("owned_memo"))
            val sdkObserver =
                launch {
                    combine(graph.vehicle().state, graph.restrictions().restrictions) { a, b -> a to b }.collect {}
                }
            var primaryFailure: Throwable? = null
            try {
                waitFor("home_history")
                touch("home_history")
                var positive = false
                var absent = false
                for (index in ended.indices) {
                    if (index > 0) touch("history_older")
                    assertHistoryRecord(ended[index], index, ended.size)
                    assertDisclosureCollapsed("history_memo")
                    touch("history_memo")
                    val stored = before.memos.getValue(ended[index].sessionId)
                    if (stored == null) {
                        assertTextVisible("history_memo_empty", text(HomeR.string.history_memo_empty))
                        assertAbsent("history_memo_text")
                        assertAbsent("history_memo_source")
                        if (!absent) {
                            capture("history-memo-empty", "history_memo_empty")
                            absent = true
                        }
                    } else {
                        assertMemo(stored)
                        if (!positive) {
                            capture("history-memo-stored", "history_memo_text")
                            positive = true
                        }
                    }
                    assertNoEditor()
                    touch("history_memo")
                    assertDisclosureCollapsed("history_memo")
                }
                assertTrue("실제 Room의 메모와 부재를 모두 확인해야 합니다", positive && absent)
                touch("history_close")
                waitFor("home_history")
                touch("home_history")
                selectOlder(ended, pastIndex)
                touch("history_memo")
                assertMemo(note)
                compose.activityRule.scenario.recreate()
                awaitAllowed(graph)
                assertHistoryRecord(ended[pastIndex], pastIndex, ended.size)
                assertDisclosureCollapsed("history_memo")
                touch("history_memo")
                assertMemo(note)
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                awaitAllowed(graph)
                assertHistoryRecord(ended[pastIndex], pastIndex, ended.size)
                assertDisclosureCollapsed("history_memo")
                touch("history_memo")
                assertMemo(note)
                phase("HISTORY_MEMO_AWAIT_D")
                awaitDrive(graph)
                waitFor("history_locked")
                assertMemoContentAbsent()
                capture("history-memo-driving", "history_locked")
                phase("HISTORY_MEMO_AWAIT_P")
                awaitAllowed(graph)
                assertHistoryRecord(ended[pastIndex], pastIndex, ended.size)
                assertDisclosureCollapsed("history_memo")
                touch("history_memo")
                assertMemo(note)
                capture("history-memo-returned", "history_memo_text")
                touch("history_close")
                waitFor("home_history")
                touch("home_history")
                assertHistoryRecord(ended.first(), 0, ended.size)
                assertDisclosureCollapsed("history_memo")
                selectOlder(ended, pastIndex)
                touch("history_memo")
                assertMemo(note)
                capture("history-memo-reentered", "history_memo_text")
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                try {
                    sdkObserver.cancelAndJoin()
                } finally {
                    try {
                        val after = snapshot(graph)
                        assertTrue("읽기 시나리오는 준비/세션/관찰/메모를 변경하면 안 됩니다", after == before)
                        assertSeedPreserved(after, owner)
                    } catch (failure: Throwable) {
                        if (primaryFailure == null) throw failure
                        checkNotNull(primaryFailure).addSuppressed(failure)
                    }
                }
            }
            phase("HISTORY_MEMO_COMPLETED")
        }
    }

    private fun graph(): RecordingTestEntryPoint =
        EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            RecordingTestEntryPoint::class.java,
        )

    private fun ownerFile() = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "history-stored-memo-owner.json")

    private fun writeOwner(owner: JSONObject) {
        val atomic = AtomicFile(ownerFile())
        val stream = atomic.startWrite()
        try {
            stream.write(owner.toString().toByteArray())
            atomic.finishWrite(stream)
        } catch (
            failure: Throwable,
        ) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    private fun baselineJson(value: ExistingData): JSONObject =
        JSONObject()
            .put("preparation", value.preparation.toString())
            .put("session_order", JSONArray(value.sessions.map { it.sessionId }))
            .put("sessions", JSONObject().apply { value.sessions.forEach { put(it.sessionId, it.toString()) } })
            .put("observations", JSONObject().apply { value.observations.forEach { (id, rows) -> put(id, rows.toString()) } })
            .put("memos", JSONObject().apply { value.memos.forEach { (id, memo) -> put(id, memo.toString()) } })

    private fun assertSeedPreserved(
        after: ExistingData,
        owner: JSONObject,
    ) {
        val before = owner.getJSONObject("baseline")
        val original = before.getJSONObject("sessions")
        val order = before.getJSONArray("session_order")
        val ids = (0 until order.length()).map { order.getString(it) }
        assertTrue("모든 원래 준비 필드를 보존해야 합니다", after.preparation.toString() == before.getString("preparation"))
        assertTrue(
            "원래 세션의 수/순서와 모든 필드를 보존해야 합니다",
            after.sessions.filter { it.sessionId in ids }.map { it.sessionId } == ids,
        )
        for (session in after.sessions.filter { it.sessionId in ids }) {
            assertTrue("원래 세션 원문을 바꾸면 안 됩니다", session.toString() == original.getString(session.sessionId))
            assertTrue(
                "원래 관찰 전체/순서를 보존해야 합니다",
                after.observations.getValue(session.sessionId).toString() ==
                    before.getJSONObject("observations").getString(session.sessionId),
            )
            assertTrue(
                "원래 메모와 버전을 바꾸면 안 됩니다",
                after.memos.getValue(session.sessionId).toString() == before.getJSONObject("memos").getString(session.sessionId),
            )
        }
        val owned = owner.getJSONArray("owned_ids")
        val ownIds = (0 until owned.length()).map { owned.getString(it) }
        assertTrue(
            "이 검사의 명시적인 소유 세션 외에는 새 자료가 없어야 합니다",
            after.sessions
                .filter { it.sessionId !in ids }
                .map { it.sessionId }
                .toSet() == ownIds.toSet(),
        )
        assertTrue("검사 소유 자료는 두 세션을 넘기면 안 됩니다", ownIds.size <= 2)
        if (owner.optString("phase") == "ready") {
            assertTrue("완료한 소유 자료는 활성 연습을 남기면 안 됩니다", ownIds.size == 2 && after.sessions.none { it.isActive })
            assertTrue(
                "최초 저장한 소유 세션/관찰/메모/준비도 그대로여야 합니다",
                baselineJson(after).toString() == owner.getJSONObject("owned_data").toString(),
            )
        }
    }

    private suspend fun createOwnedSession(
        graph: RecordingTestEntryPoint,
        priorIds: Set<String>,
        owner: JSONObject,
    ): PracticeSession {
        waitFor("session_start")
        touch("session_start")
        val active =
            withTimeout(20_000) {
                graph
                    .sessions()
                    .observeSessions()
                    .first { rows -> rows.any { it.isActive && it.sessionId !in priorIds } }
                    .single { it.isActive && it.sessionId !in priorIds }
            }
        owner.getJSONArray("owned_ids").put(active.sessionId)
        owner.put("phase", "owned_active")
        writeOwner(owner)
        withTimeout(30_000) {
            graph.observations().observe(active.sessionId).first { rows ->
                rows.any {
                    it.observation.source == VehicleObservationSource.SDK_STATE_OBSERVATION &&
                        it.observation.state.gear == VehicleReading.Available(GearPosition.PARK)
                }
            }
        }
        waitFor("session_finish")
        touch("session_finish")
        val finished =
            withTimeout(20_000) {
                graph
                    .sessions()
                    .observeSessions()
                    .first { rows -> rows.any { it.sessionId == active.sessionId && !it.isActive } }
                    .single { it.sessionId == active.sessionId }
            }
        var previous: List<SessionVehicleObservation>? = null
        withTimeout(20_000) {
            while (true) {
                val rows = graph.observations().observe(finished.sessionId).first()
                if (rows.isNotEmpty() && rows == previous) break
                previous = rows
                delay(400)
            }
        }
        owner.put("phase", "owned_finished")
        writeOwner(owner)
        return finished
    }

    private fun assertMemo(note: SessionMemo) {
        assertTextVisible("history_memo_text", note.text)
        assertTextVisible("history_memo_source", text(HomeR.string.memo_source))
        assertAbsent("history_memo_empty")
        assertAbsent("history_memo_failed")
        assertNoEditor()
    }

    private fun assertNoEditor() {
        listOf("memo_textfield", "memo_save", "memo_edit", "memo_delete", "memo_confirm_delete").forEach(::assertAbsent)
    }

    private fun assertMemoContentAbsent() {
        listOf(
            "history_memo_text",
            "history_memo_source",
            "history_memo_empty",
            "history_memo_failed",
            "history_memo_retry",
        ).forEach(::assertAbsent)
    }

    private fun assertDisclosureCollapsed(tag: String) {
        scrollVisible(tag)
        assertTrue(
            "다음 펼침은 새 소유권의 명시적 행동이어야 합니다",
            nodes(tag).single().config.getOrNull(SemanticsProperties.StateDescription) == text(DesignR.string.parked_detail_collapsed),
        )
        assertMemoContentAbsent()
    }

    private fun selectOlder(
        ended: List<PracticeSession>,
        target: Int,
    ) {
        for (index in 1..target) {
            touch("history_older")
            assertHistoryRecord(ended[index], index, ended.size)
            assertDisclosureCollapsed("history_memo")
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
        assertTrue("잠금이나 접힘 상태에서 저장 메모 상세를 남기면 안 됩니다 ($tag)", nodes(tag).isEmpty())
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

    private companion object {
        const val OWNED_NOTE = "검사 메모: 출발 전 설명을 다시 읽기\n다음에는 같은 과제를 천천히 준비하기"
    }
}
