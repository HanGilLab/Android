package io.github.hangillab.hangil

import android.accessibilityservice.AccessibilityServiceInfo
import android.os.SystemClock
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
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
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
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
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

/** Real Compose with controlled lifecycle/source/controller gates. No actual SDK or production writes. */
@RunWith(AndroidJUnit4::class)
class ManualCourseCompletionBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()

    @Test fun cancelledAndDeniedConsentCannotBorrowARecoveredGate() =
        fixture { f, _, repo ->
            f.open()
            f.touch("history_course_completion_request")
            f.confirmation(source(A))
            val cancelled = f.action("history_course_completion_confirm")
            f.touch("history_course_completion_cancel")
            f.invoke(cancelled)
            assertEquals(0, repo.writes.get())
            f.touch("history_course_completion_request")
            val old = f.action("history_course_completion_confirm")
            compose.runOnIdle { f.live.silent(driving()) }
            f.invoke(old)
            assertEquals(0, repo.writes.get())
            compose.runOnIdle { f.live.silent(parked()) }
            f.invoke(old, cancelled)
            assertEquals(0, repo.writes.get())
            f.publishParked()
            f.ready(source(A))
            f.open()
            f.touch("history_course_completion_request")
            f.touch("history_course_completion_confirm")
            f.waitTag("history_course_completion_failed")
            assertEquals("fresh consent reaches only the controlled rejection", 1, repo.writes.get())
            assertTrue("Saving acknowledgement keeps the same fresh commit guard", repo.acceptedGuard.get())
        }

    @Test fun sourceSelectionStopAndDisposalRetireCapturedActions() =
        fixture { f, sessions, repo ->
            f.open()
            f.touch("history_course_completion_request")
            val original = f.action("history_course_completion_confirm")
            val changed = source(A).copy(courseTitle = "제어 source 변경")
            compose.runOnIdle { sessions.values.value = listOf(changed, source(B)) }
            f.ready(changed)
            f.invoke(original)
            assertEquals(0, repo.writes.get())
            f.open()
            f.touch("history_course_completion_request")
            val selected = f.action("history_course_completion_confirm")
            val ready = f.model.uiState.value as SessionHistoryUiState.Ready
            compose.runOnIdle { assertTrue(f.model.older(ready.session.sessionId, ready.generation)) }
            f.ready(source(B))
            f.invoke(original, selected)
            assertEquals(0, repo.writes.get())
            f.open()
            f.touch("history_course_completion_request")
            val stopped = f.action("history_course_completion_confirm")
            f.move(Lifecycle.State.CREATED)
            f.invoke(stopped)
            f.move(Lifecycle.State.RESUMED)
            f.ready(source(B))
            f.invoke(stopped, selected)
            assertEquals(0, repo.writes.get())
            f.open()
            f.touch("history_course_completion_request")
            val disposed = f.action("history_course_completion_confirm")
            f.dispose()
            f.invoke(disposed, stopped, original)
            assertEquals(0, repo.writes.get())
        }

    @Test fun errorsFiniteStreamsAndReceiptAbaRequireFreshReads() =
        fixture { f, _, repo ->
            f.open()
            val oldRequest = f.action("history_course_completion_request")
            compose.runOnIdle { repo.mode.value = 1 }
            f.waitTag("history_course_completion_failed")
            val oldRetry = f.action("history_course_completion_retry")
            val opens = repo.reads.get()
            compose.runOnIdle { repo.mode.value = 0 }
            compose.waitForIdle()
            assertEquals("terminal error cannot silently retry", opens, repo.reads.get())
            f.invoke(oldRequest)
            assertEquals(0, repo.writes.get())
            f.touch("history_course_completion_retry")
            f.waitTag("history_course_completion_absent")
            val newerReads = repo.reads.get()
            f.invoke(oldRetry, oldRequest)
            assertEquals("old receipt cannot replace the current reader", newerReads, repo.reads.get())
            assertEquals(0, repo.writes.get())
            compose.runOnIdle { repo.mode.value = 2 }
            f.waitTag("history_course_completion_failed")
            val beforeFinite = repo.reads.get()
            f.touch("history_course_completion_retry")
            compose.waitUntil(TIMEOUT) {
                repo.reads.get() == beforeFinite + 1 && f.nodes("history_course_completion_failed").size == 1
            }
            assertEquals("finite stream is failure, not absent", beforeFinite + 1, repo.reads.get())
            compose.runOnIdle { repo.mode.value = 0 }
            f.touch("history_course_completion_retry")
            f.waitTag("history_course_completion_absent")
            val beforeStored = f.action("history_course_completion_request")
            compose.runOnIdle { repo.rows.value = mapOf(A to declaration(source(A))) }
            f.waitTag("history_course_completion_ready")
            compose.runOnIdle { repo.rows.value = emptyMap() }
            f.waitTag("history_course_completion_failed")
            f.invoke(beforeStored, oldRetry)
            assertEquals("known declaration becoming absent is not fresh consent", 0, repo.writes.get())
        }

    @Test fun activeAndUnknownStateDenyWritingButStoredDeclarationCanStillBeRead() =
        fixture { f, sessions, repo ->
            f.open()
            val oldRequest = f.action("history_course_completion_request")
            compose.runOnIdle {
                sessions.values.value = listOf(source("controlled-active").copy(endedAtEpochMillis = null), source(A), source(B))
            }
            f.ready(source(A))
            f.open()
            f.waitTag("history_course_completion_active")
            assertTrue(
                f
                    .nodes("history_course_completion_request")
                    .single()
                    .config
                    .contains(SemanticsProperties.Disabled),
            )
            f.invoke(oldRequest)
            assertEquals(0, repo.writes.get())
            compose.runOnIdle { repo.rows.value = mapOf(A to declaration(source(A))) }
            f.waitTag("history_course_completion_ready")
            f.assertText("history_course_completion_source", f.text(HomeR.string.course_completion_source))
            compose.runOnIdle { f.restrictionValues.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNKNOWN) }
            f.waitTag("history_locked")
            f.invoke(oldRequest)
            assertEquals(0, repo.writes.get())
            compose.runOnIdle { f.restrictionValues.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED) }
            f.ready(source(A))
            compose.runOnIdle { sessions.failed.value = true }
            f.waitTag("history_failed")
            f.invoke(oldRequest)
            assertEquals("failed parent cannot mean no active session", 0, repo.writes.get())
        }

    private fun fixture(block: (Driver, Sessions, Completion) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("manualCourseCompletionBoundary") == "true")
        val sessions = Sessions()
        val completion = Completion()
        val f = Driver(compose, sessions, completion)
        var primary: Throwable? = null
        try {
            f.mount()
            f.ready(source(A))
            assertEquals("collapsed new fold starts no declaration read", 0, completion.reads.get())
            block(f, sessions, completion)
            if (!f.isStopped) f.requireHost()
        } catch (failure: Throwable) {
            primary = failure
            f.diagnose(failure)
            throw failure
        } finally {
            try {
                f.dispose()
            } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
        }
    }

    internal class Driver(
        val compose: AndroidComposeTestRule<ActivityScenarioRule<ComponentCatalogActivity>, ComponentCatalogActivity>,
        private val sessions: PracticeSessionRepository,
        private val completion: CourseCompletionRepository,
        private val deletions: SessionDeletionRepository = noDeletion(),
        private val observations: VehicleObservationRepository = noObservations(),
        private val memos: SessionMemoRepository = UnexpectedHistoryMemoRepository,
    ) {
        val live = SilentState(parked())
        val restrictionValues = MutableStateFlow(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))
        private val instrument = InstrumentationRegistry.getInstrumentation()
        private val visible = mutableStateOf(true)
        private lateinit var owner: ControlledOwner
        private val modelStore = ViewModelStore()
        private val activeReads = AtomicInteger(0)
        private val activeWrites = AtomicInteger(0)
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
        val isStopped: Boolean get() = modelCleared && activeReads.get() == 0 && activeWrites.get() == 0
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
                                    deletions,
                                    observations,
                                    memos,
                                    UnexpectedHistoryRecordingRunRepository,
                                    object : CourseCompletionRepository {
                                        override fun observe(sessionId: String): Flow<CourseCompletionDeclaration?> =
                                            counted(completion.observe(sessionId))

                                        override suspend fun declare(
                                            expectedSource: PracticeSession,
                                            declaredAtEpochMillis: Long,
                                            canCommit: () -> Boolean,
                                        ): CourseCompletionDeclarationResult {
                                            activeWrites.incrementAndGet()
                                            try {
                                                return completion.declare(expectedSource, declaredAtEpochMillis, canCommit)
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
                (model.uiState.value as? SessionHistoryUiState.Ready)?.session == expected && nodes("history_course_completion").size == 1
            }

        fun open() {
            touch("history_course_completion")
            waitTag("history_course_completion_absent")
        }

        private fun positionedAction(tag: String): SemanticsNodeInteraction {
            val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
            // AlertDialog footer actions are already visible and have no scroll parent. The
            // history body keeps its required real scroll path; a missing scroll action there fails.
            if (tag != "history_course_completion_confirm" && tag != "history_course_completion_cancel") {
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

        fun confirmation(source: PracticeSession) {
            waitTag("history_course_completion_dialog")
            assertText("history_course_completion_confirm_goal", source.goalTitle)
            assertText("history_course_completion_confirm_course", source.courseTitle)
            assertText("history_course_completion_confirm_task", source.taskTitle)
            assertText("history_course_completion_confirm_demo", text(HomeR.string.demo_course))
            assertText("history_course_completion_confirm_scope", text(HomeR.string.course_completion_confirm_scope))
            val content = nodes("history_course_completion_dialog").flatMap(::texts).joinToString(" ")
            assertFalse("raw source identity is not user copy", content.contains(source.sessionId))
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
                val folder = File(instrument.targetContext.cacheDir, "manual-course-completion-controlled/${UUID.randomUUID()}")
                check(folder.mkdirs())
                val proof =
                    JSONObject()
                        .put("kind", "controlled-compose-policy")
                        .put("exception", original.javaClass.name)
                        .put("activeReads", activeReads.get())
                        .put("activeWrites", activeWrites.get())
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
            attempt { compose.waitUntil(TIMEOUT) { activeReads.get() == 0 && activeWrites.get() == 0 } }
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
        val values = MutableStateFlow(listOf(source(A), source(B)))
        val failed = MutableStateFlow(false)

        override fun observeSessions(): Flow<List<PracticeSession>> =
            combine(values, failed) { list, error ->
                check(!error)
                list
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

    private class Completion : CourseCompletionRepository {
        val reads = AtomicInteger(0)
        val writes = AtomicInteger(0)
        val acceptedGuard = AtomicBoolean(false)
        val mode = MutableStateFlow(0)
        val rows = MutableStateFlow<Map<String, CourseCompletionDeclaration>>(emptyMap())

        override fun observe(sessionId: String): Flow<CourseCompletionDeclaration?> =
            flow {
                reads.incrementAndGet()
                if (mode.value == 2) return@flow
                emitAll(
                    combine(mode, rows) { state, values ->
                        if (state == 1) error("controlled read failure")
                        if (state == 2) throw CancellationException("controlled independent stream cancellation")
                        values[sessionId]
                    },
                )
            }

        override suspend fun declare(
            expectedSource: PracticeSession,
            declaredAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): CourseCompletionDeclarationResult {
            writes.incrementAndGet()
            val allowed = canCommit()
            acceptedGuard.set(allowed)
            check(allowed) { "fresh controlled guard must still be valid after Saving acknowledgement" }
            return CourseCompletionDeclarationResult.Blocked
        }
    }

    internal companion object {
        const val TIMEOUT = 20_000L
        const val A = "controlled-completion-session-a"
        const val B = "controlled-completion-session-b"

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

        fun declaration(source: PracticeSession) =
            CourseCompletionDeclaration(source, "controlled-original-version", 500, CourseCompletionOrigin.USER_DECLARATION)

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
