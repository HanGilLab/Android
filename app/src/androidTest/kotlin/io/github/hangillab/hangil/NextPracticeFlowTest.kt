package io.github.hangillab.hangil

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.Process
import android.util.AtomicFile
import android.view.KeyEvent
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
import io.github.hangillab.hangil.core.domain.NextPracticeSeed
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.Preparation
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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.ceil
import io.github.hangillab.hangil.core.designsystem.R as DesignR
import io.github.hangillab.hangil.feature.course.R as CourseR

/**
 * Explicit real UI flow with -e manualNextPracticeFlow true, on the owned debug Pleos app only.
 * The external runner must check active sessions before Activity launch, preserve original data
 * and display/permission settings and restore actual DDS P/D/P even when this test fails.
 * No session, observation or memo is created/edited/deleted. Preparation changes only through
 * explicit UI save and a guarded restoration of this test's fixed target. Raw source/data and
 * screenshots stay in private cache. Injected source/catalog errors and independent UX are not
 * tested here. Real SDK observers mean this test does not prove release of all SDK subscribers.
 */
@RunWith(AndroidJUnit4::class)
class NextPracticeFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private var layoutPolicyFixturesVerified = false

    @Test fun nextTaskRequiresExplicitChoiceAndPreservesDataAcrossLifecycleAndDrivingLock() {
        assumeTrue(
            "실제 다음 준비 UI와 외부 DDS 복구를 준비한 명시적 실행에서만 검사합니다",
            InstrumentationRegistry.getArguments().getString("manualNextPracticeFlow") == "true",
        )
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(
            "전용 debug 앱에서만 원래 준비를 일시 변경합니다",
            instrumentation.targetContext.packageName == "io.github.hangillab.hangil.debug",
        )
        val graph =
            EntryPointAccessors.fromApplication(
                instrumentation.targetContext.applicationContext,
                RecordingTestEntryPoint::class.java,
            )
        val ownerFile = File(instrumentation.targetContext.cacheDir, "next-practice-flow-owner.json")
        assertTrue("미완료 검사 소유 기록을 덮거나 중복 실행하면 안 됩니다", !ownerEvidenceExists(ownerFile))
        runBlocking {
            val before = snapshot(graph)
            assertTrue("기존 활성 연습이 있으면 시작하지 않습니다", before.sessions.none { it.isActive })
            val original = checkNotNull(before.preparation) { "원래 준비가 있어야 검사 뒤 복구할 수 있습니다" }
            val originalPractice =
                checkNotNull((graph.catalog().resolve(original) as? Preparation.Ready)?.practice) {
                    "원래 준비가 현재 카탈로그에서 유효해야 합니다"
                }
            val source = checkNotNull(before.sessions.firstOrNull { !it.isActive }) { "기존 종료 연습이 필요합니다" }
            val seed = checkNotNull(NextPracticeSeed.resolve(graph.catalog(), source)) { "최근 종료 source를 확인해야 합니다" }
            val task =
                checkNotNull(
                    seed.practice.course.tasks
                        .firstOrNull { it.id != originalPractice.task.id },
                ) {
                    "원래 준비와 다른 같은 코스의 실제 과제가 필요합니다"
                }
            val target = source.selection.copy(taskId = task.id)
            assertTrue("수동 target은 원래 준비와 달라야 합니다", target != original)
            val nonce = UUID.randomUUID().toString()
            val owner =
                JSONObject()
                    .put("schema", 1)
                    .put("producer", "NextPracticeFlowTest")
                    .put("runNonce", nonce)
                    .put("ownerPid", Process.myPid())
                    .put("phase", "started")
                    .put("originalPreparation", selectionJson(original))
                    .put("targetPreparation", selectionJson(target))
                    .put("source", sessionJson(source))
                    .put("baselineModelValues", before.privateJson())
            reserveOwner(ownerFile)
            writeOwner(ownerFile, owner)
            val sdkObserver =
                launch {
                    combine(graph.vehicle().state, graph.restrictions().restrictions) { vehicle, restrictions ->
                        vehicle to restrictions
                    }.collect {}
                }
            var primaryFailure: Throwable? = null
            var cleanupFailure: Throwable? = null
            var saveAttempted = false
            var scenarioPassed = false
            try {
                awaitAllowed(graph)
                assertDataUnchanged(graph, before, original)
                verifyNormalPreparationAndCancel(original)
                assertDataUnchanged(graph, before, original)

                touch("home_next_practice")
                assertReady(seed, selectedTaskId = null)
                assertAbsent("next_practice_goal")
                assertAbsent("next_practice_previous_task")
                capture(
                    "next-practice-ready",
                    "next_practice_course",
                    listOf("next_practice_course") +
                        if (seed.source.courseReviewStatus == CourseReviewStatus.DEMO) listOf("next_practice_demo") else emptyList(),
                )
                openSourceDetails(seed)
                capture(
                    "next-practice-source",
                    "next_practice_previous_task",
                    listOf("next_practice_goal", "next_practice_previous_task"),
                )
                touch("next_practice_source_details")
                assertAbsent("next_practice_goal")
                assertAbsent("next_practice_previous_task")
                touch("next_task_${task.id}")
                assertReady(seed, task.id)
                assertDataUnchanged(graph, before, original)
                touch("next_practice_close")
                waitFor("home_next_practice")
                assertDataUnchanged(graph, before, original)
                owner.put("phase", "selection_cancelled")
                writeOwner(ownerFile, owner)

                touch("home_next_practice")
                assertReady(seed, selectedTaskId = null)
                touch("next_task_${task.id}")
                assertReady(seed, task.id)
                owner.put("phase", "selected_without_saving")
                writeOwner(ownerFile, owner)
                compose.activityRule.scenario.recreate()
                awaitAllowed(graph)
                assertReady(seed, task.id)
                assertDataUnchanged(graph, before, original)
                compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                instrumentation.waitForIdleSync()
                assertDataUnchanged(graph, before, original)
                compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                awaitAllowed(graph)
                assertReady(seed, task.id)
                assertDataUnchanged(graph, before, original)

                phase("NEXT_AWAIT_D")
                awaitDrive(graph)
                waitFor("next_practice_locked")
                detailTags.forEach(::assertAbsent)
                seed.practice.course.tasks
                    .forEach { assertAbsent("next_task_${it.id}") }
                assertControlEnabled("next_practice_close", true)
                capture("next-practice-locked", "next_practice_locked", listOf("next_practice_locked"))
                assertDataUnchanged(graph, before, original)
                phase("NEXT_AWAIT_P")
                awaitAllowed(graph)
                assertReady(seed, task.id)
                assertDataUnchanged(graph, before, original)
                openSourceDetails(seed)
                touch("next_practice_source_details")
                assertReady(seed, task.id)

                owner.put("phase", "before_explicit_ui_save")
                writeOwner(ownerFile, owner)
                saveAttempted = true
                touch("next_practice_save")
                withTimeout(20_000) { graph.preparation().observeSelection().first { it == target } }
                waitFor("home_next_practice")
                assertTextVisible("saved_course", seed.practice.course.title)
                assertTextVisible("saved_task", task.title)
                assertDataUnchanged(graph, before, target)
                capture("next-practice-saved", "saved_task", listOf("saved_course", "saved_task"))
                owner.put("phase", "ui_saved")
                writeOwner(ownerFile, owner)
                scenarioPassed = true
            } catch (failure: Throwable) {
                primaryFailure = failure
                throw failure
            } finally {
                withContext(NonCancellable) {
                    try {
                        restoreOriginal(graph, before, source, original, target, saveAttempted)
                        assertDataUnchanged(graph, before, original)
                        owner
                            .put("phase", if (scenarioPassed && primaryFailure == null) "restored" else "restored_after_failure")
                            .put("restoredOriginal", true)
                            .put("preservedOtherData", true)
                        writeOwner(ownerFile, owner)
                    } catch (failure: Throwable) {
                        cleanupFailure = failure
                        owner.put("phase", "restoration_failed").put("cleanupFailureClass", failure.javaClass.simpleName)
                        try {
                            writeOwner(ownerFile, owner)
                        } catch (checkpointFailure: Throwable) {
                            failure.addSuppressed(checkpointFailure)
                        }
                        primaryFailure?.addSuppressed(failure)
                    } finally {
                        sdkObserver.cancelAndJoin()
                    }
                    if (scenarioPassed && primaryFailure == null && cleanupFailure == null) {
                        assertTrue("성공한 본인 검사 소유 기록만 정리해야 합니다", readOwner(ownerFile).getString("runNonce") == nonce)
                        AtomicFile(ownerFile).delete()
                        assertTrue("성공한 검사 소유 기록의 정리가 끝나야 합니다", !ownerEvidenceExists(ownerFile))
                    }
                }
                if (primaryFailure == null && cleanupFailure != null) throw checkNotNull(cleanupFailure)
            }
            phase("NEXT_PRACTICE_FLOW_COMPLETED")
        }
    }

    private fun assertReady(
        seed: NextPracticeSeed,
        selectedTaskId: String?,
    ) {
        waitFor("next_practice_ready")
        assertTextVisible("next_practice_course", seed.source.courseTitle)
        if (seed.source.courseReviewStatus == CourseReviewStatus.DEMO) {
            assertTextVisible("next_practice_demo", text(CourseR.string.demo_badge))
        } else {
            assertAbsent("next_practice_demo")
        }
        for (task in seed.practice.course.tasks) {
            assertTextVisible("next_task_${task.id}", task.title)
            assertTextVisible("next_task_${task.id}", task.description)
            assertSelected("next_task_${task.id}", task.id == selectedTaskId)
        }
        if (selectedTaskId == null) {
            assertTextVisible("next_practice_choose_first", text(CourseR.string.next_practice_choose_first))
        } else {
            assertAbsent("next_practice_choose_first")
        }
        assertControlEnabled("next_practice_save", selectedTaskId != null)
        listOf(
            "next_practice_loading",
            "next_practice_changed",
            "next_practice_unavailable",
            "next_practice_failed",
            "next_practice_save_failed",
        ).forEach(::assertAbsent)
    }

    private fun openSourceDetails(seed: NextPracticeSeed) {
        waitFor("next_practice_source_details")
        val description = nodes("next_practice_source_details").single().config.getOrNull(SemanticsProperties.StateDescription)
        if (description == text(DesignR.string.parked_detail_collapsed)) {
            touch("next_practice_source_details")
        } else {
            assertTrue("펼침 상태를 로딩과 혼동하면 안 됩니다", description == text(DesignR.string.parked_detail_expanded))
        }
        assertTextVisible("next_practice_goal", text(CourseR.string.next_practice_source_goal, seed.source.goalTitle))
        assertTextVisible("next_practice_previous_task", text(CourseR.string.next_practice_previous_task, seed.source.taskTitle))
    }

    private fun assertSelected(
        tag: String,
        selected: Boolean,
    ) {
        scrollVisible(tag)
        assertTrue("과제 선택은 실제 명시한 값과 일치해야 합니다", nodes(tag).single().config.getOrNull(SemanticsProperties.Selected) == selected)
    }

    private fun verifyNormalPreparationAndCancel(original: PracticeSelection) {
        touch("home_prepare")
        waitFor("goal_choices")
        assertTextVisible("preparation_title", text(CourseR.string.goal_title))
        touch("goal_${original.goalId}")
        touch("preparation_next")
        waitFor("course_choices")
        assertTextVisible("preparation_title", text(CourseR.string.course_title))
        touch("course_${original.courseId}")
        touch("preparation_next")
        waitFor("briefing_tasks")
        assertTextVisible("preparation_title", text(CourseR.string.briefing_title))
        assertSelected("task_${original.taskId}", false)
        pressBack()
        waitFor("course_choices")
        pressBack()
        waitFor("goal_choices")
        pressBack()
        waitFor("home_next_practice")
    }

    private fun pressBack() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertTrue(
            "실제 앱 전면에서만 Android 뒤로가기를 보냅니다",
            instrumentation.uiAutomation.rootInActiveWindow
                ?.packageName
                ?.toString() == instrumentation.targetContext.packageName,
        )
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
    }

    private suspend fun restoreOriginal(
        graph: RecordingTestEntryPoint,
        before: ExistingData,
        source: PracticeSession,
        original: PracticeSelection,
        target: PracticeSelection,
        saveAttempted: Boolean,
    ) = withTimeout(90_000) {
        val current = graph.preparation().observeSelection().first()
        if (current == original) return@withTimeout
        assertTrue("본인 UI 저장 target이 아닌 준비를 덮으면 안 됩니다", saveAttempted && current == target)
        val rows = graph.sessions().observeSessions().first()
        assertTrue(
            "세션이나 source가 바뀌면 원래 준비를 임의 복구하지 않습니다",
            rows == before.sessions &&
                rows.none { it.isActive } && rows.firstOrNull { !it.isActive } == source,
        )
        awaitAllowed(graph)
        assertTrue(
            "실제 P와 UX 허용에서만 본인 준비를 복구합니다",
            graph.preparation().saveSelection(original) {
                allowsPracticeInteraction(graph.vehicle().state.value, graph.restrictions().restrictions.value)
            },
        )
        assertTrue("복구 결과가 원래 준비와 같아야 합니다", graph.preparation().observeSelection().first() == original)
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

    private suspend fun assertDataUnchanged(
        graph: RecordingTestEntryPoint,
        before: ExistingData,
        expected: PracticeSelection,
    ) {
        val after = snapshot(graph)
        assertTrue("준비는 허용된 원래 값 또는 검사 target이어야 합니다", after.preparation == expected)
        assertTrue("모든 기존 세션의 필드와 순서를 보존해야 합니다", after.sessions == before.sessions && after.sessions.none { it.isActive })
        assertTrue("모든 기존 관찰의 필드와 순서를 보존해야 합니다", after.observations == before.observations)
        assertTrue("모든 기존 메모의 원문과 변경 토큰을 보존해야 합니다", after.memos == before.memos)
    }

    private fun ownerEvidenceExists(file: File): Boolean =
        file.exists() || File(file.path + ".bak").exists() || File(file.path + ".new").exists()

    private fun reserveOwner(file: File) {
        assertTrue("미완료 소유 기록을 덮지 않아야 합니다", !ownerEvidenceExists(file))
        assertTrue("본인 소유 기록만 새로 예약해야 합니다", file.createNewFile())
    }

    private fun writeOwner(
        file: File,
        owner: JSONObject,
    ) {
        val atomic = AtomicFile(file)
        val stream = atomic.startWrite()
        try {
            stream.write(owner.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    private fun readOwner(file: File): JSONObject =
        try {
            JSONObject(file.readText())
        } catch (_: Exception) {
            throw AssertionError("비공개 검사 소유 기록을 읽지 못했습니다")
        }

    private fun selectionJson(selection: PracticeSelection): JSONObject =
        JSONObject()
            .put("goalId", selection.goalId)
            .put("courseId", selection.courseId)
            .put("courseVersion", selection.courseVersion)
            .put("briefingVersion", selection.briefingVersion)
            .put("taskId", selection.taskId)

    private fun sessionJson(source: PracticeSession): JSONObject =
        JSONObject()
            .put("sessionId", source.sessionId)
            .put("selection", selectionJson(source.selection))
            .put("goalTitle", source.goalTitle)
            .put("courseTitle", source.courseTitle)
            .put("taskTitle", source.taskTitle)
            .put("courseReviewStatus", source.courseReviewStatus.name)
            .put("startedAtEpochMillis", source.startedAtEpochMillis)
            .put("endedAtEpochMillis", source.endedAtEpochMillis ?: JSONObject.NULL)

    private fun text(
        resource: Int,
        vararg arguments: Any,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resource, *arguments)

    private fun assertTextVisible(
        tag: String,
        expected: String,
    ) {
        scrollVisible(tag)
        try {
            compose.waitUntil(20_000) { nodes(tag).singleOrNull()?.let { nodeTexts(it).contains(expected) } == true }
        } catch (failure: Throwable) {
            preserveLayoutFailure(tag, JSONArray(), includeWaitDiagnostics = true)
            throw failure
        }
        assertTrue("다음 준비의 표시값은 저장 원문과 일치해야 합니다 ($tag)", nodeTexts(nodes(tag).single()).contains(expected))
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
        if (fixturesVerifiedNow) phase("NEXT_LAYOUT_POLICY_FIXTURES_PASSED")
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

    /** Real native paragraphs with fixed fixture text; these are not stored source or SDK evidence. */
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
                    File(directory, "next-practice-layout-failure.png").outputStream().use {
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
                File(directory, "next-practice-layout-failure.json").writeText(details.toString(2))
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
                "next_practice_ready",
                "next_practice_loading",
                "next_practice_changed",
                "next_practice_failed",
                "next_practice_unavailable",
                "next_practice_locked",
                "next_practice_course",
                "next_practice_goal",
                "next_practice_previous_task",
                "next_practice_demo",
                "next_practice_save",
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
        assertTrue("잠금 또는 이탈 상태에 다음 준비 상세를 남기면 안 됩니다 ($tag)", nodes(tag).isEmpty())
    }

    private fun assertControlEnabled(
        tag: String,
        enabled: Boolean,
    ) {
        scrollVisible(tag)
        assertTrue(
            "조작 허용 상태가 현재 선택과 일치해야 합니다 ($tag)",
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
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply {
                putString("stream", value + System.lineSeparator())
            },
        )
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
    ) {
        fun privateJson(): JSONObject =
            JSONObject()
                .put("preparation", preparation?.toString() ?: JSONObject.NULL)
                .put("sessions", sessions.toString())
                .put("observations", observations.toString())
                .put("memos", memos.toString())
    }

    private companion object {
        val detailTags =
            listOf(
                "next_practice_ready",
                "next_practice_source",
                "next_practice_source_details",
                "next_practice_course",
                "next_practice_demo",
                "next_practice_goal",
                "next_practice_previous_task",
                "next_practice_save",
                "next_practice_choose_first",
            )
    }
}
