package io.github.hangillab.hangil

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.database.OwnedPracticeTestStore
import io.github.hangillab.hangil.core.designsystem.HanGilTheme
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.NextPracticeSaveResult
import io.github.hangillab.hangil.core.domain.NextPracticeSeed
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PlannedPracticeRoad
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionReviewRoadWriteResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.feature.course.CourseRoute
import io.github.hangillab.hangil.feature.course.CourseViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt
import io.github.hangillab.hangil.feature.course.R as CourseR

/** Real CourseRoute/VM and owned Room with controlled inputs; no Main route, SDK-backed injection or permission requests. */
@RunWith(AndroidJUnit4::class)
class PreparationEditFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val hostLeft = AtomicBoolean(false)
    private val modelJobs = mutableListOf<Job>()
    private var expectedScale = Float.NaN
    private var expectedDark = false
    private var imageIndex = 0
    private val captures by lazy {
        File(instrument.targetContext.cacheDir, "preparation-edit-${UUID.randomUUID()}").also { check(it.mkdirs()) }
    }

    @Test fun savedPreparationPrefillsCancelsAndExplicitlySavesThroughTheRealRoute() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("manualPreparationEditControlled") == "true")
        expectedScale = checkNotNull(args.getString("expectedPreparationEditFontScale")).toFloat()
        expectedDark = checkNotNull(args.getString("expectedPreparationEditDark")).toBooleanStrict()
        check(expectedScale == 1f || expectedScale == 1.3f)
        assertHost()
        val lifecycleObserver =
            LifecycleEventObserver { _, _ ->
                if (compose.activity.lifecycle.currentState != Lifecycle.State.RESUMED) hostLeft.set(true)
            }
        val focusObserver =
            android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
                if (!focused) hostLeft.set(true)
            }
        val store = OwnedPracticeTestStore.create(instrument.targetContext)
        val preparation = TrackedPreparation(store.preparation)
        val vehicleState = MutableStateFlow(VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK)))
        val uxState = MutableStateFlow(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))
        val vehicle =
            object : VehicleGateway {
                override val state = vehicleState
            }
        val restrictions =
            object : DrivingRestrictionsProvider {
                override val restrictions = uxState
            }
        val current = mutableStateOf<CourseViewModel?>(null)
        val closed = AtomicInteger()
        var primary: Throwable? = null
        try {
            instrument.runOnMainSync {
                compose.activity.lifecycle.addObserver(lifecycleObserver)
                compose.activity.window.decorView.viewTreeObserver
                    .addOnWindowFocusChangeListener(focusObserver)
            }
            val course = store.catalog.courses.first { it.tasks.size >= 2 && it.goalIds.size >= 2 && it.roadSnapshots.isNotEmpty() }
            val goals = store.catalog.goals.filter { it.id in course.goalIds }
            check(goals.size >= 2)
            val plain = PracticeSelection(goals.first().id, course.id, course.version, course.briefingVersion, course.tasks.first().id)
            val original =
                runBlocking {
                    check(store.preparation.saveSelection(plain) { true })
                    val sourcePractice = (store.catalog.resolve(plain) as Preparation.Ready).practice
                    val start = store.sessions.start(sourcePractice, 1000) { true }
                    check(start is SessionStartResult.Started)
                    val finish = store.sessions.finish(start.session, 1500) { true }
                    check(finish is SessionFinishResult.Finished)
                    val review = store.reviewRoads.save(finish.session, course.roadSnapshots.first(), null) { true }
                    check(review is SessionReviewRoadWriteResult.Saved)
                    val copied = plain.copy(plannedRoad = PlannedPracticeRoad.from(review.review))
                    assertEquals(
                        NextPracticeSaveResult.Saved,
                        store.nextPractice.saveSelection(
                            checkNotNull(NextPracticeSeed.resolve(store.catalog, finish.session)),
                            copied,
                            review.review,
                        ) { true },
                    )
                    copied
                }
            val sameCourseTask = original.copy(taskId = course.tasks.last().id)
            val target = sameCourseTask.copy(goalId = goals.last().id, plannedRoad = null)
            val sessions = runBlocking { store.sessions.observeSessions().first() }
            val sequences = store.sequenceSnapshot()
            instrument.runOnMainSync {
                compose.activity.setContent {
                    HanGilTheme {
                        val model by current
                        model?.let { owner ->
                            key(owner) {
                                CourseRoute(
                                    onClose = {
                                        if (current.value === owner) {
                                            closed.incrementAndGet()
                                            current.value = null
                                        }
                                    },
                                    viewModel = owner,
                                )
                            }
                        }
                    }
                }
                current.value = createModel(store, preparation, vehicle, restrictions)
            }
            phase("PREPARATION_EDIT_OPENED")
            waitTag("goal_choices")
            selected("goal_${original.goalId}")
            val goalNext = clickAction("preparation_next")
            click("preparation_next")
            waitTag("course_choices")
            invoke(goalNext)
            compose.onNodeWithTag("course_choices").assertExists()
            selected("course_${original.courseId}")
            val courseNext = clickAction("preparation_next")
            click("preparation_next")
            waitTag("briefing_tasks")
            invoke(courseNext)
            compose.onNodeWithTag("briefing_tasks").assertExists()
            action(compose.onNodeWithTag("preparation_next"))
            assertStored(store, original, 0, preparation)
            back()
            waitTag("course_choices")
            selected("course_${original.courseId}")
            invoke(courseNext)
            compose.onNodeWithTag("course_choices").assertExists()
            click("preparation_next")
            waitTag("briefing_tasks")
            selected("task_${original.taskId}")
            assertEquals(1, preparation.reads.get())
            assertStored(store, original, 0, preparation)
            phase("PREPARATION_EDIT_REJECTED_DUPLICATE_AND_RETURNED_NEXT")
            click("task_${target.taskId}")
            val cancelledSave = clickAction("preparation_next")
            closeFromBriefing(closed, 1)
            invoke(cancelledSave)
            assertStored(store, original, 0, preparation)
            phase("PREPARATION_EDIT_CANCELLED_WITHOUT_WRITE")

            instrument.runOnMainSync { current.value = createModel(store, preparation, vehicle, restrictions) }
            assertPrefilled(original)
            assertEquals(2, preparation.reads.get())
            invoke(cancelledSave)
            assertStored(store, original, 0, preparation)
            click("task_${sameCourseTask.taskId}")
            waitTag("preparation_planned_road")
            assertTextFits(compose.onNodeWithTag("preparation_planned_road", useUnmergedTree = true))
            click("preparation_next")
            runBlocking { withTimeout(20000) { store.preparation.observeSelection().first { it == sameCourseTask } } }
            compose.waitUntil(20000) { closed.get() == 2 }
            assertStored(store, sameCourseTask, 1, preparation)
            assertEquals(original.plannedRoad, sameCourseTask.plannedRoad)
            phase("PREPARATION_TASK_ONLY_EDIT_KEPT_THE_COPIED_TARGET")

            instrument.runOnMainSync { current.value = createModel(store, preparation, vehicle, restrictions) }
            assertPrefilled(sameCourseTask)
            assertEquals(3, preparation.reads.get())
            back()
            waitTag("course_choices")
            back()
            waitTag("goal_choices")
            click("goal_${target.goalId}")
            click("preparation_next")
            waitTag("course_choices")
            compose.onNodeWithTag("course_${course.id}").performScrollTo().assertIsNotSelected()
            click("course_${course.id}")
            click("preparation_next")
            waitTag("briefing_tasks")
            for (task in course.tasks) compose.onNodeWithTag("task_${task.id}").performScrollTo().assertIsNotSelected()
            compose
                .onNodeWithTag("preparation_next")
                .performScrollTo()
                .assertIsDisplayed()
                .assertIsNotEnabled()
            click("task_${target.taskId}")
            waitTag("preparation_planned_road_cleared")
            assertTextFits(compose.onNodeWithTag("preparation_planned_road_cleared", useUnmergedTree = true))
            val cancelledClear = clickAction("preparation_next")
            closeFromBriefing(closed, 3)
            invoke(cancelledClear)
            assertStored(store, sameCourseTask, 1, preparation)

            instrument.runOnMainSync { current.value = createModel(store, preparation, vehicle, restrictions) }
            assertPrefilled(sameCourseTask)
            assertEquals(4, preparation.reads.get())
            back()
            waitTag("course_choices")
            back()
            waitTag("goal_choices")
            click("goal_${target.goalId}")
            click("preparation_next")
            waitTag("course_choices")
            click("course_${course.id}")
            click("preparation_next")
            waitTag("briefing_tasks")
            click("task_${target.taskId}")
            waitTag("preparation_planned_road_cleared")
            preparation.rejectNext.set(true)
            click("preparation_next")
            val failureText = instrument.targetContext.getString(CourseR.string.save_failed)
            compose.waitUntil(20000) { compose.onAllNodes(hasText(failureText)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(failureText).performScrollTo().assertIsDisplayed()
            selected("task_${target.taskId}")
            assertStored(store, sameCourseTask, 1, preparation)
            val beforeDrive = clickAction("preparation_next")
            instrument.runOnMainSync { vehicleState.value = vehicleState.value.copy(gear = VehicleReading.Available(GearPosition.DRIVE)) }
            assertLocked()
            invoke(beforeDrive)
            assertStored(store, sameCourseTask, 1, preparation)
            instrument.runOnMainSync { vehicleState.value = vehicleState.value.copy(gear = VehicleReading.Available(GearPosition.PARK)) }
            waitTag("briefing_tasks")
            selected("task_${target.taskId}")
            val beforeUx = clickAction("preparation_next")
            instrument.runOnMainSync { uxState.value = uxState.value.copy(uxRestrictionState = UxRestrictionState.RESTRICTED) }
            assertLocked()
            invoke(beforeUx)
            assertStored(store, sameCourseTask, 1, preparation)
            instrument.runOnMainSync { uxState.value = uxState.value.copy(uxRestrictionState = UxRestrictionState.UNRESTRICTED) }
            waitTag("briefing_tasks")
            selected("task_${target.taskId}")
            phase("PREPARATION_EDIT_CONTROLLED_LOCKS_PRESERVED_DRAFT")
            click("preparation_next")
            runBlocking { withTimeout(20000) { store.preparation.observeSelection().first { it == target } } }
            compose.waitUntil(20000) { closed.get() == 4 }
            assertStored(store, target, 2, preparation)
            phase("PREPARATION_EDIT_EXPLICIT_SAVE_COMMITTED")

            instrument.runOnMainSync { current.value = createModel(store, preparation, vehicle, restrictions) }
            assertPrefilled(target)
            assertEquals(5, preparation.reads.get())
            closeFromBriefing(closed, 5)
            assertStored(store, target, 2, preparation)

            instrument.runOnMainSync { current.value = createModel(store, preparation, vehicle, restrictions) }
            assertPrefilled(target)
            assertEquals(6, preparation.reads.get())
            val oldBaselineSave = clickAction("preparation_next")
            val concurrent = target.copy(taskId = course.tasks.first().id)
            runBlocking { assertTrue(store.preparation.replaceSelection(target, concurrent) { true }) }
            click("preparation_next")
            waitTag("preparation_reload_saved")
            selected("task_${target.taskId}")
            assertStored(store, concurrent, 2, preparation)
            click("preparation_reload_saved")
            waitTag("goal_choices")
            invoke(oldBaselineSave)
            waitTag("goal_choices")
            assertPrefilled(concurrent)
            assertEquals(7, preparation.reads.get())
            invoke(oldBaselineSave)
            selected("task_${concurrent.taskId}")
            assertStored(store, concurrent, 2, preparation)
            closeFromBriefing(closed, 6)
            assertStored(store, concurrent, 2, preparation)
            phase("PREPARATION_STALE_BASELINE_REQUIRED_EXPLICIT_RELOAD_AND_REJECTED_OLD_SAVE")
            assertEquals(sessions, runBlocking { store.sessions.observeSessions().first() })
            assertEquals(sequences, store.sequenceSnapshot())
            assertEquals(0, store.foreignKeyViolationCount())
            assertHost()
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            fun cleanup(action: () -> Unit) {
                try {
                    action()
                } catch (failure: Throwable) {
                    if (primary == null) primary = failure else primary?.addSuppressed(failure)
                }
            }
            cleanup {
                instrument.runOnMainSync {
                    compose.activity.lifecycle.removeObserver(lifecycleObserver)
                    compose.activity.window.decorView.viewTreeObserver
                        .removeOnWindowFocusChangeListener(focusObserver)
                    current.value = null
                    compose.activity.setContent {}
                    compose.activity.viewModelStore.clear()
                }
            }
            var stopped = false
            cleanup {
                compose.waitForIdle()
                runBlocking { withTimeout(20000) { modelJobs.forEach { it.join() } } }
                stopped = true
            }
            if (stopped) cleanup { store.close() }
            // Preserve an owned fixture if its jobs cannot stop; never delete beneath an active write.
            if (primary == null && stopped) phase("PREPARATION_EDIT_CLEANUP_COMPLETED")
            primary?.let { throw it }
        }
    }

    /** Called only on the Activity's main thread; every entry gets a new saved-state owner. */
    private fun createModel(
        store: OwnedPracticeTestStore,
        preparation: PracticeRepository,
        vehicle: VehicleGateway,
        restrictions: DrivingRestrictionsProvider,
    ): CourseViewModel {
        val model =
            ViewModelProvider.create(
                compose.activity,
                object : ViewModelProvider.Factory {
                    override fun <T : ViewModel> create(modelClass: Class<T>): T =
                        modelClass.cast(CourseViewModel(store.catalog, preparation, vehicle, restrictions, SavedStateHandle()))
                },
            )["preparation-edit-${modelJobs.size}", CourseViewModel::class.java]
        modelJobs += checkNotNull(model.viewModelScope.coroutineContext[Job])
        return model
    }

    private fun assertPrefilled(selection: PracticeSelection) {
        waitTag("goal_choices")
        selected("goal_${selection.goalId}")
        click("preparation_next")
        waitTag("course_choices")
        selected("course_${selection.courseId}")
        click("preparation_next")
        waitTag("briefing_tasks")
        selected("task_${selection.taskId}")
        action(compose.onNodeWithTag("preparation_next"))
    }

    private fun closeFromBriefing(
        closed: AtomicInteger,
        expected: Int,
    ) {
        back()
        waitTag("course_choices")
        back()
        waitTag("goal_choices")
        back()
        compose.waitUntil(20000) { closed.get() == expected }
        compose.onNodeWithTag("goal_choices").assertDoesNotExist()
    }

    private fun assertStored(
        store: OwnedPracticeTestStore,
        expected: PracticeSelection,
        writes: Int,
        preparation: TrackedPreparation,
    ) {
        assertEquals(expected, runBlocking { store.preparation.observeSelection().first() })
        assertEquals(writes, preparation.writes.get())
    }

    private fun selected(tag: String) {
        assertHost()
        val node = compose.onNodeWithTag(tag)
        action(node).assertIsSelected()
        assertTextFits(compose.onNodeWithTag(tag, useUnmergedTree = true))
    }

    private fun click(tag: String) {
        assertHost()
        val node = action(compose.onNodeWithTag(tag))
        assertTextFits(compose.onNodeWithTag(tag, useUnmergedTree = true))
        assertHost()
        node.performClick()
        compose.waitForIdle()
    }

    private fun back() {
        assertHost()
        val label = instrument.targetContext.getString(CourseR.string.course_back)
        val node = action(compose.onNodeWithText(label))
        assertTextFits(compose.onNodeWithText(label, useUnmergedTree = true))
        assertHost()
        node.performClick()
        compose.waitForIdle()
    }

    private fun clickAction(tag: String): (() -> Boolean)? {
        assertHost()
        return action(compose.onNodeWithTag(tag)).fetchSemanticsNode().config[SemanticsActions.OnClick].action
    }

    private fun invoke(action: (() -> Boolean)?) {
        assertHost()
        instrument.runOnMainSync { checkNotNull(action).invoke() }
        compose.waitForIdle()
    }

    private fun assertLocked() {
        waitTag("preparation_locked_title")
        listOf(
            "goal_choices",
            "course_choices",
            "briefing_tasks",
            "preparation_next",
        ).forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
    }

    private fun waitTag(tag: String) {
        compose.waitUntil(20000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag(tag),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        assertHost()
    }

    private fun action(node: SemanticsNodeInteraction): SemanticsNodeInteraction {
        node
            .performScrollTo()
            .assertIsDisplayed()
            .assertIsEnabled()
            .assertHeightIsAtLeast(64.dp)
        val bounds = node.getUnclippedBoundsInRoot()
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue(
            "The entire action must fit the actual viewport",
            bounds.left >= root.left && bounds.right <= root.right && bounds.top >= root.top && bounds.bottom <= root.bottom,
        )
        return node
    }

    private fun assertTextFits(node: SemanticsNodeInteraction) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(captures, "screen-${imageIndex++}.png").outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        val root = node.fetchSemanticsNode()
        val textNodes = mutableListOf<SemanticsNode>()

        fun visit(current: SemanticsNode) {
            if (current.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null) textNodes += current
            current.children.forEach(::visit)
        }
        visit(root)
        assertTrue("Production text layouts must be present", textNodes.isNotEmpty())
        instrument.runOnMainSync {
            for (textNode in textNodes) {
                val results = mutableListOf<TextLayoutResult>()
                assertTrue(checkNotNull(textNode.config[SemanticsActions.GetTextLayoutResult].action).invoke(results))
                assertTrue(results.isNotEmpty())
                val bounds = textNode.boundsInRoot
                for (layout in results) {
                    assertFalse("Text height must fit", layout.didOverflowHeight)
                    assertEquals(expectedScale, layout.layoutInput.density.fontScale, 0.01f)
                    for (line in 0 until layout.lineCount) {
                        assertFalse("Text must not ellipsize", layout.isLineEllipsized(line))
                        assertTrue(
                            "Text width must fit its actual node",
                            layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= bounds.width + 1f,
                        )
                        assertTrue(
                            "Text height must fit its actual node",
                            layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= bounds.height + 1f,
                        )
                    }
                    val input = layout.layoutInput
                    val measured =
                        TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection, cacheSize = 0).measure(
                            text = input.text,
                            style = input.style,
                            overflow = input.overflow,
                            softWrap = input.softWrap,
                            maxLines = input.maxLines,
                            placeholders = input.placeholders,
                            constraints = Constraints(maxWidth = bounds.width.roundToInt(), maxHeight = bounds.height.roundToInt()),
                        )
                    assertFalse("The complete label must fit with the same font at the actual node size", measured.hasVisualOverflow)
                    assertEquals(input.text.length, measured.getLineEnd(measured.lineCount - 1))
                    for (line in 0 until measured.lineCount) assertFalse(measured.isLineEllipsized(line))
                }
            }
        }
    }

    private fun assertHost() {
        assertFalse("The verified foreground must remain current", hostLeft.get())
        compose.waitUntil(20000) { compose.activity.lifecycle.currentState == Lifecycle.State.RESUMED && compose.activity.hasWindowFocus() }
        assertTrue(compose.activity.display?.isValid == true)
        val actual = compose.activity.resources.configuration
        assertTrue(abs(actual.fontScale - expectedScale) < 0.01f)
        assertEquals(expectedDark, actual.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        assertFalse(hostLeft.get())
    }

    private fun phase(value: String) = instrument.sendStatus(2, Bundle().apply { putString("stream", value + System.lineSeparator()) })

    private class TrackedPreparation(
        private val delegate: PracticeRepository,
    ) : PracticeRepository {
        val reads = AtomicInteger()
        val writes = AtomicInteger()
        val rejectNext = AtomicBoolean(false)

        override fun observeSelection(): Flow<PracticeSelection?> =
            flow {
                reads.incrementAndGet()
                emitAll(delegate.observeSelection())
            }

        override suspend fun saveSelection(
            selection: PracticeSelection,
            canCommit: () -> Boolean,
        ): Boolean {
            val saved = delegate.saveSelection(selection, canCommit)
            if (saved) writes.incrementAndGet()
            return saved
        }

        override suspend fun replaceSelection(
            expectedCurrent: PracticeSelection?,
            selection: PracticeSelection,
            canCommit: () -> Boolean,
        ): Boolean {
            if (rejectNext.getAndSet(false)) return false
            val saved = delegate.replaceSelection(expectedCurrent, selection, canCommit)
            if (saved) writes.incrementAndGet()
            return saved
        }
    }
}
