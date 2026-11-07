package io.github.hangillab.hangil

import android.app.KeyguardManager
import android.content.res.Configuration
import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.database.OwnedPracticeTestStore
import io.github.hangillab.hangil.core.designsystem.HanGilTheme
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.feature.course.NextPracticeRoute
import io.github.hangillab.hangil.feature.course.NextPracticeViewModel
import io.github.hangillab.hangil.feature.home.SessionHistoryRoute
import io.github.hangillab.hangil.feature.home.SessionHistoryViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

/** Explicit opt-in: real Compose and nonce-owned Room, entirely controlled vehicle inputs, no SDK clients. */
@RunWith(AndroidJUnit4::class)
class HistoryNextPracticeFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val hostLeft = AtomicBoolean(false)
    private var expectedFontScale = Float.NaN
    private var expectedNight: Boolean? = null

    @Test fun selectedOlderSourceManualTaskCancelLocksAndSavePreserveStoredSessions() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("manualHistoryNextControlled") == "true")
        val expectedScale = checkNotNull(args.getString("expectedHistoryNextFontScale")).toFloat()
        val expectedDark = checkNotNull(args.getString("expectedHistoryNextDark")).toBooleanStrict()
        check(expectedScale == 1f || expectedScale == 1.3f)
        expectedFontScale = expectedScale
        expectedNight = expectedDark
        assertHost()
        val hostLifecycle = compose.activity.lifecycle
        val configuration = compose.activity.resources.configuration
        assertTrue(abs(configuration.fontScale - expectedScale) < 0.01f)
        assertEquals(expectedDark, configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        val observer =
            LifecycleEventObserver { _, _ ->
                if (compose.activity.lifecycle.currentState != Lifecycle.State.RESUMED) hostLeft.set(true)
            }
        val focusObserver =
            android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
                if (!focused) hostLeft.set(true)
            }
        instrument.runOnMainSync {
            compose.activity.lifecycle.addObserver(observer)
            compose.activity.window.decorView.viewTreeObserver
                .addOnWindowFocusChangeListener(focusObserver)
        }
        val store = OwnedPracticeTestStore.create(instrument.targetContext)
        val trackedSessions = TrackedSessions(store.sessions)
        val models = Models()
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
        val memory = HistoryNextPracticeNavigationViewModel()
        val opened = mutableStateOf<Opened?>(null)
        val history =
            models.keep(
                "history",
                SessionHistoryViewModel(
                    vehicle,
                    restrictions,
                    trackedSessions,
                    store.deletions,
                    store.observations,
                    store.memos,
                    store.recordingRuns,
                    store.courseCompletions,
                    store.historyMemoWriter,
                ),
            )
        var primary: Throwable? = null
        try {
            val course = store.catalog.courses.first { it.tasks.size >= 2 && it.goalIds.isNotEmpty() }
            val goal = store.catalog.goals.first { it.id in course.goalIds }
            val previousPractice = PreparedPractice(goal, course, course.tasks.first())
            val targetTask = course.tasks.last()

            fun selection(taskId: String) = PracticeSelection(goal.id, course.id, course.version, course.briefingVersion, taskId)
            val older = runBlocking { seed(store, previousPractice, 1000) }
            runBlocking { seed(store, previousPractice.copy(task = targetTask), 3000) }
            val originalPreparation = selection(previousPractice.task.id)
            runBlocking { store.preparation.saveSelection(originalPreparation) { true } }
            val sessionsBefore = runBlocking { store.sessions.observeSessions().first() }
            val sequencesBefore = store.sequenceSnapshot()
            instrument.runOnMainSync {
                compose.activity.setContent {
                    HanGilTheme {
                        val current by opened
                        if (current == null) {
                            SessionHistoryRoute(
                                onClose = {},
                                viewModel = history,
                                onPrepareNext = { source ->
                                    val token = checkNotNull(memory.open(source))
                                    val model =
                                        models.keep(
                                            token,
                                            NextPracticeViewModel(
                                                vehicle,
                                                restrictions,
                                                trackedSessions,
                                                store.catalog,
                                                store.nextPractice,
                                                SavedStateHandle(
                                                    mapOf("historyNextToken" to token, "nextPracticeSourceId" to source.sessionId),
                                                ),
                                            ),
                                        )
                                    opened.value = Opened(token, source, model)
                                },
                            )
                        } else {
                            val entry = checkNotNull(current)
                            NextPracticeRoute(
                                onClose = {
                                    memory.clear(entry.token)
                                    opened.value = null
                                },
                                onPrepareNormally = { error("No fallback is expected for a valid selected fixture") },
                                viewModel = entry.model,
                                expectedHistorySource = entry.source,
                                historyEntryRequired = true,
                                isHistoryEntryCurrent = {
                                    opened.value === entry &&
                                        memory.source(entry.token, entry.source.sessionId) == entry.source &&
                                        // Room validation may call this under a VM lock; avoid the test rule's main-thread synchronization.
                                        hostLifecycle.currentState == Lifecycle.State.RESUMED
                                },
                            )
                        }
                    }
                }
            }
            waitTag("history_prepare_next")
            val staleNewest = clickAction("history_prepare_next")
            click("history_older")
            invoke(staleNewest)
            assertNull(opened.value)
            click("history_prepare_next")
            waitTag("next_practice_ready")
            assertEquals(older, opened.value?.source)
            assertHost()
            for (task in course.tasks) {
                val tag = "next_task_${task.id}"
                val expectedRecords = sessionsBefore.count { it.selection.taskId == task.id && it.taskTitle == task.title }
                compose.onNodeWithTag(tag).performScrollTo().assertTextContains("남긴 마무리 기록 ${expectedRecords}개", substring = true)
                assertTextFits(tag)
            }
            compose.onNodeWithTag("next_practice_save").performScrollTo().assertIsNotEnabled()
            click("next_practice_source_details")
            assertHost()
            compose.onNodeWithTag("next_practice_previous_task").performScrollTo().assertTextContains(older.taskTitle, substring = true)
            assertTextFits("next_practice_previous_task")
            click("next_task_${targetTask.id}")
            val staleSave = clickAction("next_practice_save")
            click("next_practice_close")
            waitTag("history_prepare_next")
            assertEquals(originalPreparation, runBlocking { store.preparation.observeSelection().first() })
            invoke(staleSave)
            assertEquals(originalPreparation, runBlocking { store.preparation.observeSelection().first() })
            click("history_prepare_next")
            waitTag("next_practice_choose_first")
            click("next_task_${targetTask.id}")
            val preUxSave = clickAction("next_practice_save")
            instrument.runOnMainSync { uxState.value = uxState.value.copy(uxRestrictionState = UxRestrictionState.RESTRICTED) }
            waitTag("next_practice_locked")
            invoke(preUxSave)
            instrument.runOnMainSync { uxState.value = uxState.value.copy(uxRestrictionState = UxRestrictionState.UNRESTRICTED) }
            waitTag("next_practice_ready")
            invoke(preUxSave)
            assertEquals(originalPreparation, runBlocking { store.preparation.observeSelection().first() })
            val preDriveSave = clickAction("next_practice_save")
            instrument.runOnMainSync { vehicleState.value = vehicleState.value.copy(gear = VehicleReading.Available(GearPosition.DRIVE)) }
            waitTag("next_practice_locked")
            invoke(preDriveSave)
            instrument.runOnMainSync { vehicleState.value = vehicleState.value.copy(gear = VehicleReading.Available(GearPosition.PARK)) }
            waitTag("next_practice_ready")
            invoke(preDriveSave)
            assertEquals(originalPreparation, runBlocking { store.preparation.observeSelection().first() })
            assertTextFits("next_practice_save")
            click("next_practice_save")
            waitTag("history_prepare_next")
            assertEquals(selection(targetTask.id), runBlocking { store.preparation.observeSelection().first() })
            assertEquals(sessionsBefore, runBlocking { store.sessions.observeSessions().first() })
            assertEquals(sequencesBefore, store.sequenceSnapshot())
            assertEquals(0, store.foreignKeyViolationCount())
            assertHost()
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            fun cleanup(block: () -> Unit) {
                try {
                    block()
                } catch (failure: Throwable) {
                    if (primary == null) primary = failure else primary?.addSuppressed(failure)
                }
            }
            cleanup {
                instrument.runOnMainSync {
                    compose.activity.lifecycle.removeObserver(observer)
                    compose.activity.window.decorView.viewTreeObserver
                        .removeOnWindowFocusChangeListener(focusObserver)
                    opened.value = null
                    compose.activity.setContent {}
                    models.viewModelStore.clear()
                }
            }
            cleanup { compose.waitForIdle() }
            var jobsStopped = false
            cleanup {
                runBlocking {
                    withTimeout(20000) {
                        models.awaitStopped()
                        trackedSessions.awaitStopped()
                    }
                }
                jobsStopped = true
            }
            if (jobsStopped) cleanup { store.close() }
            // If joining fails, retain this owned fixture instead of closing/deleting under a writer.
            primary?.let { throw it }
        }
    }

    private suspend fun seed(
        store: OwnedPracticeTestStore,
        practice: PreparedPractice,
        started: Long,
    ): PracticeSession {
        val selection =
            PracticeSelection(
                practice.goal.id,
                practice.course.id,
                practice.course.version,
                practice.course.briefingVersion,
                practice.task.id,
            )
        store.preparation.saveSelection(selection) { true }
        val source = (store.sessions.start(practice, started) { true } as SessionStartResult.Started).session
        return (store.sessions.finish(source.sessionId, started + 500) { true } as SessionFinishResult.Finished).session
    }

    private fun assertHost() {
        assertFalse("Actual host focus/lifecycle left the verified foreground", hostLeft.get())
        assertFalse(instrument.targetContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        compose.waitUntil(20000) { compose.activity.lifecycle.currentState == Lifecycle.State.RESUMED && compose.activity.hasWindowFocus() }
        assertTrue(compose.activity.display?.isValid == true)
        assertFalse(hostLeft.get())
        val actual = compose.activity.resources.configuration
        assertTrue(abs(actual.fontScale - expectedFontScale) < 0.01f)
        assertEquals(expectedNight, actual.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
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
    }

    private fun click(tag: String) {
        assertHost()
        waitTag(tag)
        compose
            .onNodeWithTag(tag)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
    }

    private fun clickAction(tag: String): (() -> Boolean)? {
        assertHost()
        return compose
            .onNodeWithTag(tag)
            .performScrollTo()
            .fetchSemanticsNode()
            .config[SemanticsActions.OnClick]
            .action
    }

    private fun invoke(action: (() -> Boolean)?) {
        assertHost()
        instrument.runOnMainSync { checkNotNull(action).invoke() }
        compose.waitForIdle()
    }

    private fun assertTextFits(tag: String) {
        assertHost()
        val subject = compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        // Only this controlled fixture is rendered; keep the actual window image in target-private cache.
        val mode = "${if (expectedNight == true) "dark" else "light"}-${expectedFontScale.toString().replace('.', '_')}"
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(instrument.targetContext.cacheDir, "history-next-$mode-$tag.png").outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        assertHost()
        val subjectBounds = subject.getUnclippedBoundsInRoot()
        val viewport = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue(
            "$tag must fit the actual viewport: subject=$subjectBounds, viewport=$viewport",
            subjectBounds.left >= viewport.left && subjectBounds.right <= viewport.right &&
                subjectBounds.top >= viewport.top && subjectBounds.bottom <= viewport.bottom,
        )
        val textNodes = mutableListOf<SemanticsNode>()

        fun read(current: SemanticsNode) {
            if (current.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null) textNodes += current
            current.children.forEach(::read)
        }
        read(subject.fetchSemanticsNode())
        assertTrue("Actual rendered text layout is required", textNodes.isNotEmpty())
        instrument.runOnMainSync {
            for (textNode in textNodes) {
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(checkNotNull(textNode.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts))
                assertTrue("Actual rendered text results are required", layouts.isNotEmpty())
                val bounds = textNode.boundsInRoot
                assertTrue("$tag text node must have visible area: $bounds", bounds.width > 0f && bounds.height > 0f)
                for (layout in layouts) {
                    val geometry =
                        "$tag node=${bounds.width}x${bounds.height}, size=${layout.size}, " +
                            "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                            "flags=${layout.didOverflowWidth}/${layout.didOverflowHeight}, " +
                            "lines=${layout.lineCount}, scale=${layout.layoutInput.density.fontScale}, " +
                            "font=${layout.layoutInput.style.fontSize}"
                    assertTrue(geometry, layout.lineCount > 0)
                    assertEquals(geometry, expectedFontScale, layout.layoutInput.density.fontScale, 0.01f)
                    assertTrue(geometry, layout.layoutInput.style.fontSize.value >= 24f)
                    // String semantics may rebuild a wider MultiParagraph while retaining the
                    // original natural size. Compare actual lines and remeasure that node instead.
                    assertFalse(geometry, layout.didOverflowHeight)
                    assertEquals(geometry, layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
                    for (line in 0 until layout.lineCount) {
                        val lineBounds =
                            "$geometry, line=$line, L/R=${layout.getLineLeft(line)}/${layout.getLineRight(line)}, " +
                                "T/B=${layout.getLineTop(line)}/${layout.getLineBottom(line)}"
                        assertFalse(lineBounds, layout.isLineEllipsized(line))
                        assertTrue(lineBounds, layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= bounds.width + 1f)
                        assertTrue(lineBounds, layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= bounds.height + 1f)
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
                    assertTrue(geometry, measured.lineCount > 0)
                    assertFalse("$geometry, remeasured=${measured.size}", measured.hasVisualOverflow)
                    assertEquals(geometry, input.text.length, measured.getLineEnd(measured.lineCount - 1))
                    for (line in 0 until measured.lineCount) assertFalse(geometry, measured.isLineEllipsized(line))
                }
            }
        }
    }

    /** Track the real Room collectors, including the history reader owned by Compose rather than the VM. */
    private class TrackedSessions(
        private val delegate: PracticeSessionRepository,
    ) : PracticeSessionRepository by delegate {
        private val jobs = ConcurrentHashMap.newKeySet<Job>()
        private val readers = AtomicInteger()

        override fun observeSessions(): Flow<List<PracticeSession>> =
            flow {
                val job = checkNotNull(currentCoroutineContext()[Job])
                jobs += job
                readers.incrementAndGet()
                try {
                    emitAll(delegate.observeSessions())
                } finally {
                    readers.decrementAndGet()
                }
            }

        suspend fun awaitStopped() {
            jobs.toList().forEach { it.join() }
            check(readers.get() == 0 && jobs.all { it.isCompleted }) {
                "Owned Room readers must finish before the fixture can close"
            }
        }
    }

    private class Models : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
        private val jobs = mutableSetOf<Job>()

        suspend fun awaitStopped() {
            jobs.toList().forEach { it.join() }
        }

        fun <T : ViewModel> keep(
            key: String,
            model: T,
        ): T =
            ViewModelProvider
                .create(
                    this,
                    object : ViewModelProvider.Factory {
                        override fun <V : ViewModel> create(modelClass: Class<V>): V = modelClass.cast(model)
                    },
                )[key, model.javaClass]
                .also { jobs += checkNotNull(it.viewModelScope.coroutineContext[Job]) }
    }

    private data class Opened(
        val token: String,
        val source: PracticeSession,
        val model: NextPracticeViewModel,
    )
}
