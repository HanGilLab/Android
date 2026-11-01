package io.github.hangillab.hangil

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.Display
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.designsystem.HanGilTheme
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclaration
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclarationResult
import io.github.hangillab.hangil.core.domain.CourseCompletionOrigin
import io.github.hangillab.hangil.core.domain.CourseCompletionRepository
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.HistorySessionMemoWriter
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionDeletionRepository
import io.github.hangillab.hangil.core.domain.SessionDeletionResult
import io.github.hangillab.hangil.core.domain.SessionDeletionTarget
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoPolicy
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import io.github.hangillab.hangil.core.domain.VehicleObservationRepository
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.feature.home.SessionHistoryRoute
import io.github.hangillab.hangil.feature.home.SessionHistoryUiState
import io.github.hangillab.hangil.feature.home.SessionHistoryViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject
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
import io.github.hangillab.hangil.feature.home.R as HomeR

/** Real Compose with controlled source/gates/repositories. No production SDK, Room or public images. */
@RunWith(AndroidJUnit4::class)
class HistoryMemoEditBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()

    @Test fun repeatedReceiptsKeepDraftAndCapturedSaveCannotBorrowLaterTextInEitherAckOrder() {
        for (mode in listOf(Writer.Mode.ACK_FIRST, Writer.Mode.FLOW_FIRST)) {
            fixture { f, _, repo, writer ->
                writer.mode = mode
                val original = repo.stored.getValue(A)
                f.beginEdit()
                f.replace("first draft")
                val oldSave = f.action("history_memo_save")
                val exact = "  controlled edit 😀\nsecond line  "
                f.replace(exact)
                compose.runOnIdle { repo.repeatReceipt() }
                f.assertDraft(exact)
                f.invoke(oldSave)
                assertEquals(0, writer.calls.get())
                f.assertDraft(exact)
                f.touch("history_memo_save")
                f.assertText("history_memo_text", exact)
                assertEquals(1, writer.calls.get())
                assertTrue(writer.acceptedGuard.get())
                assertEquals(exact, repo.stored.getValue(A).text)
                assertTrue(repo.stored.getValue(A).version != original.version)
                f.invoke(oldSave)
                assertEquals(1, writer.calls.get())
            }
        }
    }

    @Test fun sourceSelectionLifecycleAndSilentGateRetireInputsSaveAndCancel() =
        fixture { f, sessions, repo, writer ->
            f.beginEdit()
            f.replace("discarded source draft")
            val input = f.inputAction()
            val save = f.action("history_memo_save")
            val cancel = f.action("history_memo_cancel_edit")
            val changed = source(A).copy(taskTitle = "changed controlled snapshot")
            compose.runOnIdle { sessions.values.value = listOf(source(NEWER), changed) }
            f.ready(changed)
            f.invoke(save, cancel)
            compose.runOnIdle { input(AnnotatedString("stale input")) }
            assertTrue(f.nodes("history_memo_textfield").isEmpty())
            assertEquals(0, writer.calls.get())
            f.openMemo(repo.stored.getValue(A).text)
            f.beginEdit()
            f.replace("discarded stopped draft")
            val stopped = f.action("history_memo_save")
            f.move(Lifecycle.State.CREATED)
            f.invoke(stopped)
            f.move(Lifecycle.State.RESUMED)
            f.ready(changed)
            f.invoke(stopped)
            assertEquals(0, writer.calls.get())
            f.openMemo(repo.stored.getValue(A).text)
            f.beginEdit()
            f.replace("discarded driving draft")
            val denied = f.action("history_memo_save")
            compose.runOnIdle {
                f.live.silent(driving())
                denied()
                f.live.silent(parked())
            }
            f.invoke(denied)
            assertEquals(0, writer.calls.get())
            f.publishParked()
            f.ready(changed)
            f.openMemo(repo.stored.getValue(A).text)
            f.touch("history_newer")
            f.ready(source(NEWER))
            f.invoke(denied, stopped, save, cancel)
            assertEquals(0, writer.calls.get())
            assertTrue(f.nodes("history_memo_textfield").isEmpty())
        }

    @Test fun conflictingMemoAndCancelledResultNeedExplicitReloadWithoutAutomaticResave() =
        fixture { f, _, repo, writer ->
            f.beginEdit()
            f.replace("unsaved conflict draft")
            val retired = f.action("history_memo_save")
            val external = SessionMemo(A, "controlled external version", "external-version")
            compose.runOnIdle { repo.publish(external) }
            f.waitTag("history_memo_failed")
            f.invoke(retired)
            assertEquals(0, writer.calls.get())
            f.touch("history_memo_retry")
            f.assertText("history_memo_text", external.text)
            writer.mode = Writer.Mode.CANCELLED
            f.beginEdit()
            f.replace("unknown response draft")
            f.touch("history_memo_save")
            f.waitTag("history_memo_failed")
            f.assertText("history_memo_unsaved_text", "unknown response draft")
            f.assertText("history_memo_entered_label", f.text(HomeR.string.history_memo_entered_text))
            assertTrue(!f.nodes("history_memo_content").flatMap(f::texts).contains(f.text(HomeR.string.memo_unsaved)))
            f.invoke(retired)
            assertEquals(1, writer.calls.get())
            f.touch("history_memo_retry")
            f.assertText("history_memo_text", external.text)
            assertEquals(1, writer.calls.get())
            assertEquals(external, repo.stored.getValue(A))
        }

    @Test fun editAndDeletionPreparationExcludeEachOtherAndSafeCancellationRepairsOnlyTheMemoReader() {
        for (mode in listOf(Deletion.Mode.READY, Deletion.Mode.BLOCKED, Deletion.Mode.ERROR)) {
            val deletion = Deletion(mode)
            fixture(deletion) { f, sessions, repo, writer ->
                val delete = f.action("history_delete")
                f.beginEdit()
                f.replace("cancel before deletion")
                f.invoke(delete)
                assertEquals(0, deletion.prepares.get())
                assertTrue(f.nodes("history_delete_dialog").isEmpty())
                f.touch("history_memo_cancel_edit")
                f.assertText("history_memo_text", repo.stored.getValue(A).text)
                val oldEdit = f.action("history_memo_edit")
                val parent = f.model.uiState.value
                val parentReads = sessions.reads.get()
                f.touch("history_delete")
                if (mode == Deletion.Mode.READY) {
                    f.waitTag("history_delete_completion_status")
                    f.invoke(oldEdit)
                    assertTrue(f.nodes("history_memo_textfield").isEmpty())
                    f.touch("history_delete_cancel")
                    f.absent("history_delete_dialog")
                } else {
                    f.waitTag("history_delete_error")
                }
                f.assertText("history_memo_text", repo.stored.getValue(A).text)
                f.invoke(oldEdit)
                assertTrue(f.nodes("history_memo_textfield").isEmpty())
                val dismiss = if (mode == Deletion.Mode.READY) null else f.action("history_delete_cancel")
                f.beginEdit()
                f.replace("fresh after deletion")
                dismiss?.let {
                    f.invoke(it)
                    f.absent("history_delete_error")
                    f.assertDraft("fresh after deletion")
                }
                f.touch("history_memo_cancel_edit")
                assertEquals(parent, f.model.uiState.value)
                assertEquals(parentReads, sessions.reads.get())
                assertEquals(0, writer.calls.get())
                assertEquals(1, deletion.prepares.get())
                assertEquals(0, deletion.deletes.get())
            }
        }
    }

    @Test fun activeUnknownAndInvalidDraftNeverGrantWriteAuthority() =
        fixture { f, sessions, repo, writer ->
            val oldEdit = f.action("history_memo_edit")
            compose.runOnIdle { sessions.values.value = listOf(source("active").copy(endedAtEpochMillis = null), source(NEWER), source(A)) }
            f.ready(source(A))
            f.openMemo(repo.stored.getValue(A).text)
            assertTrue(
                f
                    .nodes("history_memo_edit")
                    .single()
                    .config
                    .contains(SemanticsProperties.Disabled),
            )
            f.invoke(oldEdit)
            assertEquals(0, writer.calls.get())
            compose.runOnIdle { sessions.values.value = listOf(source(NEWER), source(A)) }
            f.ready(source(A))
            f.openMemo(repo.stored.getValue(A).text)
            f.beginEdit()
            f.replace("x".repeat(SessionMemoPolicy.MAX_CODE_POINTS + 1))
            assertTrue(
                f
                    .nodes("history_memo_save")
                    .single()
                    .config
                    .contains(SemanticsProperties.Disabled),
            )
            f.touch("history_memo_cancel_edit")
            compose.runOnIdle { f.restrictionValues.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNKNOWN) }
            f.waitTag("history_locked")
            f.invoke(oldEdit)
            assertEquals(0, writer.calls.get())
        }

    private fun fixture(
        deletions: SessionDeletionRepository? = null,
        block: (Driver, Sessions, Memos, Writer) -> Unit,
    ) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("manualHistoryMemoEditBoundary") == "true")
        val sessions = Sessions()
        val memos = Memos()
        val writer = Writer(sessions, memos)
        if (deletions is Deletion) deletions.originalMemo = memos.stored.getValue(A)
        val f = if (deletions == null) Driver(compose, sessions, memos, writer) else Driver(compose, sessions, memos, writer, deletions)
        var primary: Throwable? = null
        try {
            f.mount()
            f.ready(source(NEWER))
            assertEquals(0, memos.reads.get())
            f.touch("history_older")
            f.ready(source(A))
            f.openMemo(memos.stored.getValue(A).text)
            block(f, sessions, memos, writer)
            if (!f.isStopped) f.requireHost()
        } catch (
            failure: Throwable,
        ) {
            primary = failure
            f.diagnose(failure)
            throw failure
        } finally {
            try {
                f.dispose()
            } catch (
                cleanup: Throwable,
            ) {
                if (primary ==
                    null
                ) {
                    throw cleanup
                } else {
                    primary.addSuppressed(cleanup)
                }
            }
        }
    }

    internal class Driver(
        val compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentCatalogActivity>, ComponentCatalogActivity>,
        private val sessions: PracticeSessionRepository,
        private val memos: SessionMemoRepository,
        private val writer: HistorySessionMemoWriter,
        private val deletions: SessionDeletionRepository = noDeletion(),
    ) {
        val live = SilentState(parked())
        val restrictionValues = MutableStateFlow(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))
        private val instrument = InstrumentationRegistry.getInstrumentation()
        private val visible = mutableStateOf(true)
        private lateinit var owner: ControlledOwner
        private val modelStore = ViewModelStore()
        private val activeReads = AtomicInteger(0)
        private val activeWrites = AtomicInteger(0)
        private val activeDeletionPreparations = AtomicInteger(0)
        private val hostIntervened = AtomicBoolean(false)

        @Volatile private var lastHostEvent: String? = null
        private var setupFocusConfirmed = false
        private var setupWaitMillis: Long? = null
        private var lastWindowObservation = JSONObject()
        private val hostObserver =
            LifecycleEventObserver { _, event ->
                if (event in listOf(Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY)) {
                    hostIntervened.set(true)
                    lastHostEvent = event.name
                }
            }
        private var displayId: Int? = null
        private var flagBefore: Boolean? = null
        private var disposed = false
        private var modelCleared = false
        val isStopped: Boolean get() =
            modelCleared && activeReads.get() == 0 && activeWrites.get() == 0 &&
                activeDeletionPreparations.get() == 0
        lateinit var model: SessionHistoryViewModel

        private fun awaitInitialCatalogFocus() {
            val started = SystemClock.elapsedRealtime()
            val deadline = started + TIMEOUT
            while (SystemClock.elapsedRealtime() < deadline) {
                var ready = false
                instrument.runOnMainSync {
                    val activity = compose.activity
                    val display = activity.display
                    ready = activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                        display != null && display.isValid && activity.hasWindowFocus()
                }
                setupWaitMillis = SystemClock.elapsedRealtime() - started
                if (ready) {
                    check(SystemClock.elapsedRealtime() < deadline) { "initial Catalog focus exceeded setup deadline" }
                    return
                }
                SystemClock.sleep(50)
            }
            error("initial Catalog RESUMED/display/window focus was not confirmed")
        }

        fun mount() {
            // ActivityScenario RESUMED can precede input-window attachment. This bounded gate
            // happens before fixture ownership; no later foreign-package result is retried here.
            awaitInitialCatalogFocus()
            instrument.runOnMainSync {
                val activity = compose.activity
                val display = checkNotNull(activity.display)
                check(activity.lifecycle.currentState == Lifecycle.State.RESUMED && display.isValid && activity.hasWindowFocus())
                displayId = display.displayId
                setupFocusConfirmed = true
                activity.lifecycle.addObserver(hostObserver)
                owner = ControlledOwner()
                owner.move(Lifecycle.State.RESUMED)
                model =
                    ViewModelProvider(
                        modelStore,
                        object : ViewModelProvider.Factory {
                            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                                check(modelClass == SessionHistoryViewModel::class.java)
                                @Suppress("UNCHECKED_CAST")
                                return SessionHistoryViewModel(
                                    object : VehicleGateway {
                                        override val state: StateFlow<VehicleState> = live
                                    },
                                    object : DrivingRestrictionsProvider {
                                        override val restrictions: StateFlow<DrivingRestrictions> = restrictionValues
                                    },
                                    object : PracticeSessionRepository by sessions {
                                        override fun observeSessions(): Flow<List<PracticeSession>> = counted(sessions.observeSessions())
                                    },
                                    object : SessionDeletionRepository by deletions {
                                        override suspend fun prepare(
                                            expected: PracticeSession,
                                            canRead: () -> Boolean,
                                        ): SessionDeletionPreparation {
                                            activeDeletionPreparations.incrementAndGet()
                                            try {
                                                return deletions.prepare(expected, canRead)
                                            } finally {
                                                activeDeletionPreparations.decrementAndGet()
                                            }
                                        }

                                        override suspend fun delete(
                                            target: SessionDeletionTarget,
                                            canCommit: () -> Boolean,
                                        ): SessionDeletionResult {
                                            activeWrites.incrementAndGet()
                                            try {
                                                return deletions.delete(target, canCommit)
                                            } finally {
                                                activeWrites.decrementAndGet()
                                            }
                                        }
                                    },
                                    noObservations(),
                                    object : SessionMemoRepository by memos {
                                        override fun observe(sessionId: String): Flow<SessionMemo?> = counted(memos.observe(sessionId))

                                        override suspend fun save(
                                            sessionId: String,
                                            text: String,
                                            expectedVersion: String?,
                                            canCommit: () -> Boolean,
                                        ): SessionMemoWriteResult = error("history must use the update-only writer")

                                        override suspend fun delete(
                                            sessionId: String,
                                            expectedVersion: String,
                                            canCommit: () -> Boolean,
                                        ): SessionMemoWriteResult = error("history must not delete a memo")
                                    },
                                    UnexpectedHistoryRecordingRunRepository,
                                    UnexpectedHistoryCourseCompletionRepository,
                                    object : HistorySessionMemoWriter {
                                        override suspend fun update(
                                            expectedSource: PracticeSession,
                                            expectedMemo: SessionMemo,
                                            text: String,
                                            canCommit: () -> Boolean,
                                        ): SessionMemoWriteResult {
                                            activeWrites.incrementAndGet()
                                            try {
                                                return writer.update(expectedSource, expectedMemo, text, canCommit)
                                            } finally {
                                                activeWrites.decrementAndGet()
                                            }
                                        }
                                    },
                                ) as T
                            }
                        },
                    )[SessionHistoryViewModel::class.java]
                compose.activity.setContent {
                    HanGilTheme {
                        CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                            if (visible.value) SessionHistoryRoute(onClose = { visible.value = false }, viewModel = model)
                        }
                    }
                }
            }
            requireHost()
            compose.waitForIdle()
        }

        private fun <T> counted(upstream: Flow<T>): Flow<T> =
            flow {
                activeReads.incrementAndGet()
                try {
                    emitAll(upstream)
                } finally {
                    activeReads.decrementAndGet()
                }
            }

        fun ready(expected: PracticeSession) =
            compose.waitUntil(TIMEOUT) {
                (model.uiState.value as? SessionHistoryUiState.Ready)?.session == expected && nodes("history_memo").size == 1
            }

        fun openMemo(expected: String) {
            touch("history_memo")
            assertText("history_memo_text", expected)
        }

        fun beginEdit() {
            touch("history_memo_edit")
            waitTag("history_memo_textfield")
        }

        fun replace(text: String) {
            compose.onNodeWithTag("history_memo_textfield", useUnmergedTree = true).performScrollTo().performTextReplacement(text)
            compose.waitForIdle()
            assertDraft(text)
        }

        private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)

        fun inputAction(): (AnnotatedString) -> Boolean =
            checkNotNull(
                descendants(nodes("history_memo_textfield").single())
                    .first { it.config.getOrNull(SemanticsActions.SetText)?.action != null }
                    .config
                    .getOrNull(SemanticsActions.SetText)
                    ?.action,
            )

        fun assertDraft(expected: String) {
            waitTag("history_memo_textfield")
            check(
                descendants(nodes("history_memo_textfield").single()).any {
                    it.config.getOrNull(SemanticsProperties.EditableText)?.text == expected
                },
            ) { "the exact draft must remain unchanged" }
        }

        private fun positionedAction(tag: String): SemanticsNodeInteraction {
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
            // AlertDialog footer actions are already visible and have no scroll parent. The
            // history body keeps its required real scroll path; a missing scroll action there fails.
            val deletionDialogFooter = tag == "history_delete_cancel" && nodes("history_delete_dialog").size == 1
            if (!deletionDialogFooter) {
                node.performScrollTo()
            }
            return node.assertIsDisplayed()
        }

        fun touch(tag: String) {
            requireHost()
            positionedAction(tag).performClick()
            compose.waitForIdle()
        }

        fun action(tag: String): () -> Boolean {
            requireHost()
            positionedAction(tag)
            compose.waitForIdle()
            return checkNotNull(
                nodes(tag)
                    .single()
                    .config
                    .getOrNull(SemanticsActions.OnClick)
                    ?.action,
            )
        }

        fun invoke(vararg actions: () -> Boolean) {
            instrument.runOnMainSync { actions.forEach { it() } }
            compose.waitForIdle()
        }

        fun move(state: Lifecycle.State) {
            instrument.runOnMainSync { owner.move(state) }
            compose.waitForIdle()
        }

        fun publishParked() {
            compose.runOnIdle { live.publish(driving()) }
            waitTag("history_locked")
            compose.runOnIdle { live.publish(parked()) }
        }

        fun assertExpectedTheme() {
            val args = InstrumentationRegistry.getArguments()
            val font = checkNotNull(args.getString("expectedFontScale"))
            val dark = checkNotNull(args.getString("expectedDark"))
            check(font in listOf("1.0", "1.3") && dark in listOf("false", "true"))
            val config = compose.activity.resources.configuration
            check(kotlin.math.abs(config.fontScale - font.toFloat()) < 0.01f)
            check(
                config.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                    if (dark == "true") Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO,
            )
        }

        fun imeVisible(): Boolean? {
            var result: Boolean? = null
            instrument.runOnMainSync {
                result =
                    compose.activity.window.decorView.rootWindowInsets
                        ?.isVisible(WindowInsets.Type.ime())
            }
            return result
        }

        fun awaitIme(visible: Boolean) = compose.waitUntil(TIMEOUT) { imeVisible() == visible }

        fun showIme() {
            touch("history_memo_textfield")
            awaitIme(true)
        }

        fun assertVisibleText(tag: String) {
            requireHost()
            compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            val node = nodes(tag).single()
            val available = availableFrame()
            val bounds = node.boundsInWindow
            val origin = node.positionOnScreen - node.positionInWindow
            check(
                node.size.width > 0 && node.size.height > 0 && bounds.width >= node.size.width - 1 &&
                    bounds.height >= node.size.height - 1 && bounds.left + origin.x >= available.left - 1 &&
                    bounds.top + origin.y >= available.top - 1 && bounds.right + origin.x <= available.right + 1 &&
                    bounds.bottom + origin.y <= available.bottom + 1,
            )
            val layouts = mutableListOf<TextLayoutResult>()
            compose.runOnIdle {
                descendants(node).forEach {
                    it.config
                        .getOrNull(SemanticsActions.GetTextLayoutResult)
                        ?.action
                        ?.invoke(layouts)
                }
            }
            check(layouts.isNotEmpty() && layouts.all(::textGeometryFits)) { "actual glyphs/lines must fit: $tag" }
        }

        private fun availableFrame(): Rect {
            val available = Rect()
            instrument.runOnMainSync {
                val activity = compose.activity
                activity.window.decorView.getWindowVisibleDisplayFrame(available)
                val insets = checkNotNull(activity.window.decorView.rootWindowInsets)
                if (insets.isVisible(WindowInsets.Type.ime())) {
                    val bottom = insets.getInsets(WindowInsets.Type.ime()).bottom
                    check(bottom > 0)
                    available.bottom = minOf(available.bottom, activity.windowManager.currentWindowMetrics.bounds.bottom - bottom)
                }
                check(available.width() > 0 && available.height() > 0)
            }
            return available
        }

        private fun textGeometryFits(layout: TextLayoutResult): Boolean {
            val text = layout.layoutInput.text.text
            if (text.isEmpty() || layout.size.width <= 0 || layout.size.height <= 0 || layout.lineCount <= 0 ||
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
                if (layout.getLineStart(line) != covered || end < covered || end > text.length || layout.isLineEllipsized(line) ||
                    !inside(layout.getLineLeft(line), layout.getLineTop(line), layout.getLineRight(line), layout.getLineBottom(line))
                ) {
                    return false
                }
                covered = end
            }
            return covered == text.length &&
                text.indices.filterNot { text[it].isWhitespace() }.all {
                    val box = layout.getBoundingBox(it)
                    inside(box.left, box.top, box.right, box.bottom)
                }
        }

        fun capture(
            stage: String,
            kind: String,
            diagnostic: Boolean = false,
        ) {
            val folder = File(instrument.targetContext.cacheDir, "screenshots/history-memo-edit-owned-${UUID.randomUUID()}")
            check(folder.mkdirs())
            var first: Throwable? = null

            fun preserve(failure: Throwable) {
                if (first ==
                    null
                ) {
                    first = failure
                } else if (first !== failure) {
                    first!!.addSuppressed(failure)
                }
            }
            var confirmed = false
            val rect = Rect()
            try {
                requireHost()
                instrument.runOnMainSync {
                    val display = checkNotNull(compose.activity.display)
                    check(display.isValid && display.displayId == Display.DEFAULT_DISPLAY)
                    compose.activity.window.decorView
                        .getWindowVisibleDisplayFrame(rect)
                    check(rect.width() > 0 && rect.height() > 0)
                }
                confirmed = true
            } catch (failure: Throwable) {
                preserve(failure)
            }
            var observedIme: Boolean? = null
            var observedFont: Float? = null
            var observedNight: Int? = null
            try {
                observedIme = imeVisible()
            } catch (failure: Throwable) {
                preserve(failure)
            }
            try {
                val config = compose.activity.resources.configuration
                observedFont = config.fontScale
                observedNight = config.uiMode and Configuration.UI_MODE_NIGHT_MASK
            } catch (failure: Throwable) {
                preserve(failure)
            }
            val proof =
                JSONObject()
                    .put("kind", kind)
                    .put("fixture_kind", "synthetic-UUID-owned")
                    .put("actual_sdk", false)
                    .put("stage", stage)
                    .put("foreground_confirmed", confirmed)
                    .put(
                        "capture_scope",
                        if (diagnostic || !confirmed) {
                            "raw-default-display-diagnostic-not-app-only"
                        } else if (observedIme == true) {
                            "confirmed-Catalog-default-display-including-system-IME"
                        } else {
                            "confirmed-Catalog-available-window"
                        },
                    ).put("font_scale", observedFont ?: JSONObject.NULL)
                    .put("night_mode", observedNight ?: JSONObject.NULL)
                    .put("ime_visible", observedIme ?: JSONObject.NULL)
                    .put("png_saved", false)
            try {
                File(folder, "proof.json").writeText(proof.toString(2))
            } catch (failure: Throwable) {
                preserve(failure)
            }
            try {
                if (!diagnostic) check(confirmed && observedIme != null)
                val image = checkNotNull(instrument.uiAutomation.takeScreenshot())
                try {
                    val output =
                        if (diagnostic || !confirmed || observedIme == true) {
                            image
                        } else {
                            check(rect.left >= 0 && rect.top >= 0 && rect.right <= image.width && rect.bottom <= image.height)
                            Bitmap.createBitmap(image, rect.left, rect.top, rect.width(), rect.height())
                        }
                    try {
                        File(folder, "app.png").outputStream().use { check(output.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    } finally {
                        if (output !== image) output.recycle()
                    }
                    proof.put("png_saved", true)
                } finally {
                    image.recycle()
                }
            } catch (failure: Throwable) {
                proof.put("png_failure", failure.javaClass.name)
                preserve(failure)
            }
            try {
                File(folder, "proof.json").writeText(proof.toString(2))
            } catch (failure: Throwable) {
                preserve(failure)
            }
            first?.let { throw it }
        }

        fun waitTag(tag: String) = compose.waitUntil(TIMEOUT) { nodes(tag).size == 1 }

        fun absent(tag: String) = compose.waitUntil(TIMEOUT) { nodes(tag).isEmpty() }

        fun nodes(tag: String): List<SemanticsNode> =
            compose
                .onAllNodes(hasTestTag(tag), useUnmergedTree = true)
                .fetchSemanticsNodes(atLeastOneRootRequired = false)

        fun texts(node: SemanticsNode): List<String> =
            node.config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .map { it.text } + node.children.flatMap(::texts)

        fun assertText(
            tag: String,
            expected: String,
        ) {
            waitTag(tag)
            assertTrue("exact saved text", texts(nodes(tag).single()).contains(expected))
        }

        fun text(
            id: Int,
            vararg args: Any,
        ): String = instrument.targetContext.getString(id, *args)

        fun requireHost() {
            if (flagBefore == null) {
                val info = instrument.uiAutomation.serviceInfo
                flagBefore = info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0
                info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                instrument.uiAutomation.serviceInfo = info
            }
            check(instrument.uiAutomation.serviceInfo.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0)
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT
            while (SystemClock.elapsedRealtime() < deadline) {
                var resumed = false
                instrument.runOnMainSync {
                    val display = compose.activity.display
                    resumed = compose.activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                        display != null && display.isValid && display.displayId == displayId
                }
                check(resumed && !hostIntervened.get()) { "actual catalog host/display must remain RESUMED" }
                val actual = actualPackage()
                if (actual != null) {
                    check(actual == instrument.targetContext.packageName) { "known foreign focused package" }
                    return
                }
                SystemClock.sleep(50)
            }
            error("actual focused package remains unknown")
        }

        private fun actualPackage(): String? {
            val observed =
                JSONObject()
                    .put("basis", "single-focused-window-on-captured-Catalog-display")
                    .put("captured_display_known", displayId != null)
                    .put("window_count", JSONObject.NULL)
                    .put("focused_count", JSONObject.NULL)
                    .put("root_present", false)
                    .put("same_display", JSONObject.NULL)
                    .put("package_available", false)
                    .put("package_matches", JSONObject.NULL)
                    .put("unknown_reason", JSONObject.NULL)
            lastWindowObservation = observed

            fun unknown(reason: String): String? {
                observed.put("unknown_reason", reason)
                return null
            }
            val captured = displayId ?: return unknown("captured_display_unknown")
            val windows = mutableListOf<AccessibilityWindowInfo>()
            var root: AccessibilityNodeInfo? = null
            try {
                val all = instrument.uiAutomation.windowsOnAllDisplays
                for (index in 0 until all.size()) windows.addAll(all.valueAt(index))
                observed.put(
                    "interactive_windows_flag",
                    instrument.uiAutomation.serviceInfo.flags and
                        AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0,
                )
                val current = all[captured] ?: return unknown("display_windows_unavailable")
                observed.put("window_count", current.size)
                val sameDisplay = current.all { it.displayId == captured }
                observed.put("same_display", sameDisplay)
                if (!sameDisplay) return unknown("window_display_mismatch")
                val focused = current.filter { it.isFocused }
                observed.put("focused_count", focused.size)
                if (focused.size != 1) return unknown("focused_window_count_not_one")
                root = focused.single().root ?: return unknown("focused_root_unavailable")
                observed.put("root_present", true)
                val actual = root?.packageName?.toString() ?: return unknown("focused_package_unavailable")
                observed.put("package_available", true).put("package_matches", actual == instrument.targetContext.packageName)
                return actual
            } catch (failure: Throwable) {
                observed.put("unknown_reason", "observer_exception").put("exception", failure.javaClass.name)
                throw failure
            } finally {
                root?.recycle()
                windows.forEach { it.recycle() }
            }
        }

        fun diagnose(original: Throwable) {
            // Controlled metadata only; no raw package names or synthetic SDK screenshot claim.
            try {
                val folder = File(instrument.targetContext.cacheDir, "history-memo-edit-controlled/${UUID.randomUUID()}")
                check(folder.mkdirs())
                val proof =
                    JSONObject()
                        .put("kind", "controlled-history-memo-edit")
                        .put("exception", original.javaClass.name)
                        .put("activeReads", activeReads.get())
                        .put("activeWrites", activeWrites.get())
                        .put("activeDeletionPreparations", activeDeletionPreparations.get())
                        .put("hostIntervened", hostIntervened.get())
                        .put("last_host_event", lastHostEvent ?: JSONObject.NULL)
                        .put("setup_focus_confirmed", setupFocusConfirmed)
                        .put("setup_wait_ms", setupWaitMillis ?: JSONObject.NULL)
                        .put("last_window_observation", lastWindowObservation)
                try {
                    instrument.runOnMainSync {
                        val activity = compose.activity
                        val display = activity.display
                        proof
                            .put("host_state", activity.lifecycle.currentState.name)
                            .put("host_has_window_focus", activity.hasWindowFocus())
                            .put("display_known", display != null)
                            .put("display_valid", display?.isValid ?: JSONObject.NULL)
                            .put(
                                "same_captured_display",
                                if (display == null || displayId == null) {
                                    JSONObject.NULL
                                } else {
                                    display.displayId == displayId
                                },
                            )
                    }
                } catch (probe: Throwable) {
                    proof.put("host_probe_failure", probe.javaClass.name)
                    original.addSuppressed(probe)
                }
                try {
                    proof.put(
                        "interactive_windows_flag",
                        instrument.uiAutomation.serviceInfo.flags and
                            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0,
                    )
                } catch (probe: Throwable) {
                    proof.put("flag_probe_failure", probe.javaClass.name)
                    original.addSuppressed(probe)
                }
                File(folder, "failure.json").writeText(proof.toString(2))
            } catch (diagnostic: Throwable) {
                original.addSuppressed(diagnostic)
            }
        }

        fun dispose() {
            if (disposed) return
            var first: Throwable? = null

            fun attempt(action: () -> Unit) {
                try {
                    action()
                } catch (failure: Throwable) {
                    if (first == null) first = failure else first!!.addSuppressed(failure)
                }
            }
            // Actual host validation is independent of the controlled owner and never skipped
            // merely because the body explicitly disposes the UI before its last assertion.
            attempt { if (displayId != null) requireHost() }
            attempt {
                instrument.runOnMainSync {
                    if (::owner.isInitialized && owner.lifecycle.currentState != Lifecycle.State.INITIALIZED) {
                        owner.move(Lifecycle.State.DESTROYED)
                    }
                }
            }
            attempt { instrument.runOnMainSync { visible.value = false } }
            attempt {
                instrument.runOnMainSync {
                    modelStore.clear()
                    modelCleared = true
                }
            }
            attempt { instrument.runOnMainSync { compose.activity.lifecycle.removeObserver(hostObserver) } }
            attempt { compose.waitForIdle() }
            attempt {
                compose.waitUntil(TIMEOUT) {
                    activeReads.get() == 0 && activeWrites.get() == 0 && activeDeletionPreparations.get() == 0
                }
            }
            attempt {
                flagBefore?.let { old ->
                    val info = instrument.uiAutomation.serviceInfo
                    info.flags =
                        if (old) {
                            info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        } else {
                            info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS.inv()
                        }
                    instrument.uiAutomation.serviceInfo = info
                    val actual =
                        instrument.uiAutomation.serviceInfo.flags and
                            AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0
                    check(actual == old)
                }
            }
            if (first == null) disposed = true
            first?.let { throw it }
        }
    }

    internal class ControlledOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun move(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    @OptIn(InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
    internal class SilentState<T>(
        initial: T,
        private val emitted: MutableStateFlow<T> = MutableStateFlow(initial),
    ) : StateFlow<T> by emitted {
        @Volatile private var current = initial
        override val value: T get() = current

        fun silent(value: T) {
            current = value
        }

        fun publish(value: T) {
            current = value
            emitted.value = value
        }
    }

    private class Sessions : PracticeSessionRepository {
        val values = MutableStateFlow(listOf(source(NEWER), source(A)))
        val reads = AtomicInteger(0)

        override fun observeSessions(): Flow<List<PracticeSession>> =
            flow {
                reads.incrementAndGet()
                emitAll(values)
            }

        override suspend fun start(
            practice: PreparedPractice,
            startedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionStartResult = error("unexpected start")

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult = error("unexpected finish")
    }

    private class Memos : SessionMemoRepository {
        @Volatile var stored =
            mapOf(
                A to SessionMemo(A, "  controlled original\nsecond line  ", "original-version"),
                NEWER to SessionMemo(NEWER, "newest untouched", "newest-version"),
            )
        private val signals = MutableStateFlow(0)
        val reads = AtomicInteger(0)

        fun repeatReceipt() {
            signals.value += 1
        }

        fun publish(memo: SessionMemo) {
            store(memo)
            repeatReceipt()
        }

        fun store(memo: SessionMemo) {
            stored = stored + (memo.sessionId to memo)
        }

        override fun observe(sessionId: String): Flow<SessionMemo?> =
            flow {
                reads.incrementAndGet()
                emitAll(signals.map { stored[sessionId] })
            }

        override suspend fun save(
            sessionId: String,
            text: String,
            expectedVersion: String?,
            canCommit: () -> Boolean,
        ): SessionMemoWriteResult = error("must not use latest save")

        override suspend fun delete(
            sessionId: String,
            expectedVersion: String,
            canCommit: () -> Boolean,
        ): SessionMemoWriteResult = error("must not delete")
    }

    private class Writer(
        private val sessions: Sessions,
        private val memos: Memos,
    ) : HistorySessionMemoWriter {
        enum class Mode { BLOCKED, ACK_FIRST, FLOW_FIRST, CANCELLED }

        var mode = Mode.BLOCKED
        val calls = AtomicInteger(0)
        val acceptedGuard = AtomicBoolean(false)

        override suspend fun update(
            expectedSource: PracticeSession,
            expectedMemo: SessionMemo,
            text: String,
            canCommit: () -> Boolean,
        ): SessionMemoWriteResult {
            val call = calls.incrementAndGet()
            check(sessions.values.value.single { it.sessionId == expectedSource.sessionId } == expectedSource)
            check(memos.stored[expectedSource.sessionId] == expectedMemo && SessionMemoPolicy.isValidText(text))
            val allowed = canCommit()
            acceptedGuard.set(allowed)
            check(allowed)
            if (mode == Mode.BLOCKED) return SessionMemoWriteResult.Blocked
            if (mode == Mode.CANCELLED) throw kotlinx.coroutines.CancellationException("controlled independent result cancellation")
            val saved = SessionMemo(expectedMemo.sessionId, text, "controlled-saved-$call")
            if (mode == Mode.FLOW_FIRST) {
                memos.publish(saved)
                awaitCancellation()
            }
            memos.store(saved)
            return SessionMemoWriteResult.Saved(saved)
        }
    }

    private class Deletion(
        private val mode: Mode,
    ) : SessionDeletionRepository {
        lateinit var originalMemo: SessionMemo

        enum class Mode { READY, BLOCKED, ERROR }

        val prepares = AtomicInteger(0)
        val deletes = AtomicInteger(0)

        override suspend fun prepare(
            expected: PracticeSession,
            canRead: () -> Boolean,
        ): SessionDeletionPreparation {
            prepares.incrementAndGet()
            check(expected == source(A) && canRead())
            return when (mode) {
                Mode.READY -> SessionDeletionPreparation.Ready(SessionDeletionTarget(expected, 0, originalMemo.version))
                Mode.BLOCKED -> SessionDeletionPreparation.Blocked
                Mode.ERROR -> error("controlled deletion preparation failure")
            }
        }

        override suspend fun delete(
            target: SessionDeletionTarget,
            canCommit: () -> Boolean,
        ): SessionDeletionResult {
            deletes.incrementAndGet()
            error("never confirm deletion")
        }
    }

    internal companion object {
        const val TIMEOUT = 20_000L
        const val A = "controlled-historical-memo"
        const val NEWER = "controlled-newest-session"

        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        fun driving() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.DRIVE))

        fun source(id: String) =
            PracticeSession(
                id,
                PracticeSelection("controlled-goal", "controlled-course", 1, 1, "controlled-task"),
                "제어 목표",
                "제어 코스",
                "제어 과제",
                CourseReviewStatus.DEMO,
                1_000,
                2_000,
            )

        private fun noDeletion() =
            object : SessionDeletionRepository {
                override suspend fun prepare(
                    expected: PracticeSession,
                    canRead: () -> Boolean,
                ): SessionDeletionPreparation = error("unexpected deletion preparation")

                override suspend fun delete(
                    target: SessionDeletionTarget,
                    canCommit: () -> Boolean,
                ): SessionDeletionResult = error("unexpected deletion")
            }

        private fun noObservations() =
            object : VehicleObservationRepository {
                override fun observe(sessionId: String): Flow<List<SessionVehicleObservation>> = error("unexpected observation read")

                override suspend fun append(
                    sessionId: String,
                    observation: VehicleObservation,
                ): VehicleObservationAppendResult = error("unexpected append")
            }
    }
}
