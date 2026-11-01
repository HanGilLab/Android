package io.github.hangillab.hangil

import android.accessibilityservice.AccessibilityServiceInfo
import android.graphics.Bitmap
import android.os.Looper
import android.os.SystemClock
import android.util.AtomicFile
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.designsystem.HanGilTheme
import io.github.hangillab.hangil.core.domain.BackgroundRecordingState
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclaration
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclarationResult
import io.github.hangillab.hangil.core.domain.CourseCompletionRepository
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.HistorySessionMemoWriter
import io.github.hangillab.hangil.core.domain.NavigationGateway
import io.github.hangillab.hangil.core.domain.NavigationScreenResult
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeCourse
import io.github.hangillab.hangil.core.domain.PracticeGoal
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.PracticeTask
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.RecordingController
import io.github.hangillab.hangil.core.domain.RecordingRunOpenResult
import io.github.hangillab.hangil.core.domain.RecordingRunRepository
import io.github.hangillab.hangil.core.domain.RecordingRunStatus
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionDeletionRepository
import io.github.hangillab.hangil.core.domain.SessionDeletionResult
import io.github.hangillab.hangil.core.domain.SessionDeletionTarget
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionMemo
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
import io.github.hangillab.hangil.core.domain.VehicleRecordingRun
import io.github.hangillab.hangil.core.domain.VehicleRecordingState
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.feature.home.BackgroundRecordingViewModel
import io.github.hangillab.hangil.feature.home.FinishedSessionReviewRoute
import io.github.hangillab.hangil.feature.home.HomeRoute
import io.github.hangillab.hangil.feature.home.HomeViewModel
import io.github.hangillab.hangil.feature.home.NavigationScreenViewModel
import io.github.hangillab.hangil.feature.home.RecentVehicleObservationViewModel
import io.github.hangillab.hangil.feature.home.SessionFinishReceipt
import io.github.hangillab.hangil.feature.home.SessionHistoryViewModel
import io.github.hangillab.hangil.feature.home.SessionMemoViewModel
import io.github.hangillab.hangil.feature.home.SessionViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import io.github.hangillab.hangil.feature.home.R as HomeR

/** Real production Compose with controlled source/lifecycle/gates, never actual SDK or production Room. */
@RunWith(AndroidJUnit4::class)
class FinishSourceReviewBoundaryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()

    @Test fun validatedAcknowledgementsRequireOneExplicitReviewAndNeverBorrowTheLatestRecord() {
        assumeManual()
        for (kind in SessionFinishReceipt.Kind.entries) {
            val sessions = Sessions()
            val children = Children()
            sessions.onFinish = { expected, time, allowed ->
                check(allowed())
                val ended = expected.copy(endedAtEpochMillis = if (kind == SessionFinishReceipt.Kind.FINISHED) time else TIME + 30_000)
                sessions.rows.value = listOf(source(B, TIME + 150_000), ended)
                if (kind ==
                    SessionFinishReceipt.Kind.FINISHED
                ) {
                    SessionFinishResult.Finished(ended)
                } else {
                    SessionFinishResult.AlreadyFinished(ended)
                }
            }
            fixture(sessions, children) { f ->
                f.touch("session_finish")
                f.waitReceipt()
                val receipt = checkNotNull(f.session.finishReceipt.value)
                check(receipt.kind == kind && receipt.source.sessionId == A)
                check(f.navigation.isEmpty()) { "A finish acknowledgement must not navigate automatically" }
                check(children.totalReads() == 0) { "Closed details must not start child reads" }
                val oldReview = f.action("session_finish_review")
                f.touch("session_finish_review")
                f.ready(receipt.source)
                f.invoke(oldReview)
                check(
                    f.navigation.size == 1 && f.navigation.single() == receipt.source,
                ) { "Only the captured acknowledgement source may navigate once" }
                check(f.nodes("history_newer").isEmpty() && f.nodes("history_older").isEmpty())
                f.touch("history_memo")
                f.waitTag("history_memo_empty")
                check(children.memoQueries.toList() == listOf(A)) { "An exact entry must never read the newer record's memo" }
                check(children.observationQueries.isEmpty() && children.runQueries.isEmpty() && children.completionQueries.isEmpty())
            }
        }
    }

    @Test fun capturedFinishAndReviewActionsCannotBorrowRecoveredHostsOrLiveParkedValues() {
        assumeManual()
        val sessions = Sessions()
        sessions.onFinish = { expected, time, allowed ->
            check(allowed())
            val ended = expected.copy(endedAtEpochMillis = time)
            sessions.rows.value = listOf(ended)
            SessionFinishResult.Finished(ended)
        }
        fixture(sessions, Children()) { f ->
            val oldFinish = f.action("session_finish")
            f.move(Lifecycle.State.STARTED)
            f.invoke(oldFinish)
            f.move(Lifecycle.State.RESUMED)
            f.waitTag("session_finish")
            f.invoke(oldFinish)
            check(sessions.finishes.get() == 0)
            val parkedAction = f.action("session_finish")
            compose.runOnIdle { f.live.silent(driving()) }
            f.invoke(parkedAction)
            compose.runOnIdle { f.live.silent(parked()) }
            f.invoke(parkedAction)
            check(sessions.finishes.get() == 0) { "An observed denial must not revive the same finish intent" }
            f.publishDriving()
            f.publishParked()
            val beforeReplacement = f.action("session_finish")
            val replacement = source(A).copy(taskTitle = "replacement controlled source")
            compose.runOnIdle { sessions.rows.value = listOf(replacement) }
            compose.waitUntil(TIMEOUT) {
                f.session.finishActions.value
                    ?.source == replacement
            }
            f.invoke(beforeReplacement)
            compose.runOnIdle { sessions.rows.value = listOf(source(A)) }
            compose.waitUntil(TIMEOUT) {
                f.session.finishActions.value
                    ?.source == source(A)
            }
            f.invoke(beforeReplacement)
            check(sessions.finishes.get() == 0) { "A replaced source must not lend a new token to an old action" }
            f.touch("session_finish")
            f.waitReceipt()
            val oldReview = f.action("session_finish_review")
            f.move(Lifecycle.State.CREATED)
            f.invoke(oldReview)
            f.move(Lifecycle.State.RESUMED)
            f.invoke(oldReview)
            check(f.navigation.isEmpty())
            f.dispose()
            f.invoke(oldReview, oldFinish)
            check(f.navigation.isEmpty() && sessions.finishes.get() == 1)
        }
    }

    @Test fun invalidOrUnconfirmedAcknowledgementsNeverCreateAReviewReceipt() {
        assumeManual()
        val outcomes: List<(PracticeSession, Long) -> SessionFinishResult> =
            listOf(
                { active, _ -> SessionFinishResult.Finished(active) },
                { active, time -> SessionFinishResult.Finished(active.copy(sessionId = B, endedAtEpochMillis = time)) },
                {
                    active,
                    time,
                    ->
                    SessionFinishResult.AlreadyFinished(active.copy(taskTitle = "changed controlled source", endedAtEpochMillis = time))
                },
                { _, _ -> SessionFinishResult.Missing },
                { _, _ -> SessionFinishResult.Blocked },
                { _, _ -> SessionFinishResult.InvalidTime },
                { _, _ -> SessionFinishResult.SourceChanged },
                { _, _ -> throw CancellationException("controlled unconfirmed response") },
                { _, _ -> throw IllegalStateException("controlled read/write failure") },
            )
        for (outcome in outcomes) {
            val sessions = Sessions()
            sessions.onFinish = { expected, time, _ -> outcome(expected, time) }
            fixture(sessions, Children()) { f ->
                f.touch("session_finish")
                compose.waitUntil(TIMEOUT) { sessions.completed.get() == 1 }
                compose.waitForIdle()
                check(f.session.finishReceipt.value == null && f.nodes("session_finish_review").isEmpty())
                check(f.navigation.isEmpty() && sessions.finishes.get() == 1 && sessions.legacyFinishes.get() == 0)
            }
        }
    }

    @Test fun coldExactEntryDistinguishesMissingChangedAndFailedWithoutLatestOrEmptyFallback() {
        assumeManual()
        val sessions = Sessions().apply { rows.value = listOf(source(B, TIME + 150_000), source(A, TIME + 90_000)) }
        val children = Children()
        fixture(sessions, children) { f ->
            val expected = source(A, TIME + 90_000)
            f.openExact(expected)
            f.ready(expected)
            val oldMemo = f.action("history_memo")
            compose.runOnIdle {
                sessions.rows.value =
                    listOf(source(B, TIME + 150_000), expected.copy(taskTitle = "changed controlled source"))
            }
            f.waitTag("finished_review_unavailable")
            f.invoke(oldMemo)
            check(children.memoQueries.isEmpty() && f.nodes("history_ready").isEmpty())
            compose.runOnIdle { sessions.rows.value = listOf(source(B, TIME + 150_000)) }
            f.openExact(expected)
            f.waitTag("finished_review_unavailable")
            check(f.nodes("history_empty").isEmpty() && f.nodes("history_ready").isEmpty())
            val beforeNull = f.historyReads.get()
            f.openExact(null)
            f.waitTag("finished_review_unavailable")
            check(f.historyReads.get() == beforeNull) { "A lost exact source must not query general history" }
            for (mode in listOf(1, 2, 3)) {
                f.openExact(null)
                f.waitTag("finished_review_unavailable")
                sessions.mode = mode
                f.openExact(expected)
                f.waitTag("history_failed")
                check(f.nodes("history_empty").isEmpty() && f.nodes("history_ready").isEmpty())
            }
        }
    }

    private fun fixture(
        sessions: Sessions,
        children: Children,
        body: (Driver) -> Unit,
    ) {
        val f =
            Driver(
                compose,
                catalog(),
                preparation(),
                sessions,
                children.memos,
                children.observations,
                children.runs,
                children.completions,
                noDeletion(),
                noHistoryWriter(),
            )
        var primary: Throwable? = null
        try {
            f.mount()
            f.waitTag("home_journey")
            body(f)
        } catch (failure: Throwable) {
            primary = failure
            try {
                f.captureFailure(failure)
            } catch (_: Throwable) {
                failure.addSuppressed(IllegalStateException("Private boundary diagnostic capture was incomplete"))
            }
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
        private val catalog: PracticeCatalog,
        private val preparation: PracticeRepository,
        private val sessions: PracticeSessionRepository,
        private val memos: SessionMemoRepository,
        private val observations: VehicleObservationRepository,
        private val runs: RecordingRunRepository,
        private val completions: CourseCompletionRepository,
        private val deletions: SessionDeletionRepository,
        private val writer: HistorySessionMemoWriter,
    ) {
        val live = SilentVehicle(parked())
        val restrictions = MutableStateFlow(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))
        val navigation = mutableListOf<PracticeSession>()
        val historyReads = AtomicInteger(0)
        private val activeReads = AtomicInteger(0)
        private val activeWrites = AtomicInteger(0)
        private val instrument = InstrumentationRegistry.getInstrumentation()
        private val store = ViewModelStore()
        private val owner = Owner(store)
        private val visible = mutableStateOf(true)
        private val exact = mutableStateOf(false)
        private val target = mutableStateOf<PracticeSession?>(null)
        private val entryEpoch = mutableStateOf(0)
        private var displayId: Int? = null
        private var originalFlag: Boolean? = null
        private var disposed = false
        private var failureCaptured = false
        private var phase = "created"
        private var lastAction: String? = null
        private var mountStage = "not-started"
        private var lastGuard: HostReceipt? = null
        private var lastFocusKnown: Boolean? = null
        private var lastFocusExpected: Boolean? = null
        private val phaseHistory = mutableListOf<JSONObject>()
        var isStopped = false
            private set
        lateinit var session: SessionViewModel
        private val vehicle =
            object : VehicleGateway {
                override val state: StateFlow<VehicleState> = live
            }
        private val ux =
            object : DrivingRestrictionsProvider {
                override val restrictions: StateFlow<DrivingRestrictions> = this@Driver.restrictions
            }

        fun mount() {
            mark("mount:await-actual-host")
            mountStage = "awaiting-actual-host"
            awaitHost()
            mountStage = "actual-host-confirmed"
            mark("mount:create-fixture")
            instrument.runOnMainSync {
                owner.move(Lifecycle.State.RESUMED)
                val watched =
                    object : PracticeSessionRepository by sessions {
                        override fun observeSessions(): Flow<List<PracticeSession>> = counted(sessions.observeSessions())

                        override suspend fun finish(
                            expectedSource: PracticeSession,
                            endedAtEpochMillis: Long,
                            canCommit: () -> Boolean,
                        ): SessionFinishResult {
                            activeWrites.incrementAndGet()
                            try {
                                return sessions.finish(expectedSource, endedAtEpochMillis, canCommit)
                            } finally {
                                activeWrites.decrementAndGet()
                            }
                        }
                    }
                val historySessions =
                    object : PracticeSessionRepository by sessions {
                        override fun observeSessions(): Flow<List<PracticeSession>> =
                            flow {
                                historyReads.incrementAndGet()
                                emitAll(counted(sessions.observeSessions()))
                            }
                    }
                val watchedPreparation =
                    object : PracticeRepository by preparation {
                        override fun observeSelection(): Flow<PracticeSelection?> = counted(preparation.observeSelection())
                    }
                val watchedMemos =
                    object : SessionMemoRepository by memos {
                        override fun observe(sessionId: String): Flow<SessionMemo?> = counted(memos.observe(sessionId))
                    }
                val watchedObservations =
                    object : VehicleObservationRepository by observations {
                        override fun observe(sessionId: String): Flow<List<SessionVehicleObservation>> =
                            counted(observations.observe(sessionId))
                    }
                val watchedRuns =
                    object : RecordingRunRepository by runs {
                        override fun observe(sessionId: String): Flow<List<VehicleRecordingRun>> = counted(runs.observe(sessionId))
                    }
                val watchedCompletions =
                    object : CourseCompletionRepository by completions {
                        override fun observe(sessionId: String): Flow<CourseCompletionDeclaration?> =
                            counted(completions.observe(sessionId))
                    }
                session =
                    SessionViewModel(
                        vehicle,
                        ux,
                        watchedPreparation,
                        catalog,
                        watched,
                        Clock.fixed(Instant.ofEpochMilli(TIME + 90_000), ZoneOffset.UTC),
                    )
                val history =
                    SessionHistoryViewModel(
                        vehicle,
                        ux,
                        historySessions,
                        deletions,
                        watchedObservations,
                        watchedMemos,
                        watchedRuns,
                        watchedCompletions,
                        writer,
                    )
                val models =
                    mapOf<Class<out ViewModel>, ViewModel>(
                        HomeViewModel::class.java to HomeViewModel(vehicle, noNavigation(), ux, watchedPreparation, catalog),
                        SessionViewModel::class.java to session,
                        BackgroundRecordingViewModel::class.java to BackgroundRecordingViewModel(noRecording()),
                        NavigationScreenViewModel::class.java to NavigationScreenViewModel(vehicle, ux, noNavigation()),
                        RecentVehicleObservationViewModel::class.java to
                            RecentVehicleObservationViewModel(vehicle, ux, watched, watchedObservations),
                        SessionMemoViewModel::class.java to SessionMemoViewModel(vehicle, ux, watched, watchedMemos),
                        SessionHistoryViewModel::class.java to history,
                    )
                val factory =
                    object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = modelClass.cast(checkNotNull(models[modelClass]))
                    }
                val provider = ViewModelProvider(owner, factory)
                models.keys.forEach { provider[it] }
                mountStage = "models-created"
                compose.activity.setContent {
                    CompositionLocalProvider(LocalLifecycleOwner provides owner, LocalViewModelStoreOwner provides owner) {
                        HanGilTheme {
                            if (visible.value) {
                                key(entryEpoch.value) {
                                    if (exact.value) {
                                        FinishedSessionReviewRoute(target.value, { exact.value = false }, history)
                                    } else {
                                        HomeRoute(
                                            onPrepare = { error("Unexpected preparation navigation") },
                                            onMapPreview = { error("Unexpected map navigation") },
                                            onHistory = { error("Unexpected general history") },
                                            onPrepareNext = { error("Unexpected next-task navigation") },
                                            onFinishedReview = {
                                                navigation += it
                                                target.value = it
                                                exact.value = true
                                                entryEpoch.value++
                                            },
                                            viewModel = checkNotNull(models[HomeViewModel::class.java]) as HomeViewModel,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                mountStage = "UI-installed"
            }
            mark("mount:return")
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

        fun openExact(source: PracticeSession?) {
            mark(if (source == null) "entry:null-exact" else "entry:ended-exact")
            compose.runOnIdle {
                target.value = source
                exact.value = true
                entryEpoch.value++
            }
        }

        fun waitReceipt() {
            mark("wait:finish-receipt")
            compose.waitUntil(TIMEOUT) { session.finishReceipt.value != null }
            waitTag("session_finish_review")
        }

        fun ready(source: PracticeSession) {
            mark("assert:exact-ready")
            waitTag("history_ready")
            check(
                text("history_course") == source.courseTitle && text("history_goal") == source.goalTitle &&
                    text("history_task") == source.taskTitle,
            ) {
                "Displayed full-source context differs"
            }
            val formatter =
                DateTimeFormatter
                    .ofLocalizedDateTime(
                        FormatStyle.MEDIUM,
                    ).withLocale(Locale.getDefault())
                    .withZone(ZoneId.systemDefault())
            check(
                text("history_started") ==
                    compose.activity.getString(
                        HomeR.string.session_started_at,
                        formatter.format(Instant.ofEpochMilli(source.startedAtEpochMillis)),
                    ),
            )
            check(
                text("history_finished") ==
                    compose.activity.getString(
                        HomeR.string.session_finished_at,
                        formatter.format(Instant.ofEpochMilli(checkNotNull(source.endedAtEpochMillis))),
                    ),
            )
            requireHost()
        }

        fun nodes(tag: String): List<SemanticsNode> = compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes(false)

        fun waitTag(tag: String) {
            mark("wait-tag:" + tag)
            compose.waitUntil(TIMEOUT) { nodes(tag).size == 1 }
            requireHost()
        }

        fun text(tag: String): String =
            nodes(tag)
                .single()
                .config
                .getOrNull(SemanticsProperties.Text)
                ?.joinToString("") { it.text }
                .orEmpty()

        fun touch(tag: String) {
            lastAction = "touch:" + tag
            mark(lastAction!!)
            requireHost()
            compose
                .onNodeWithTag(tag, useUnmergedTree = true)
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
        }

        fun action(tag: String): () -> Boolean {
            lastAction = "capture:" + tag
            mark(lastAction!!)
            return checkNotNull(
                nodes(tag)
                    .single()
                    .config
                    .getOrNull(SemanticsActions.OnClick)
                    ?.action,
            )
        }

        fun invoke(vararg actions: () -> Boolean) {
            lastAction = "replay-captured-action"
            mark(lastAction!!)
            compose.runOnIdle { actions.forEach { it() } }
        }

        fun move(state: Lifecycle.State) {
            mark("controlled-lifecycle:" + state.name)
            compose.runOnIdle { owner.move(state) }
            compose.waitForIdle()
            requireHost()
        }

        fun publishDriving() {
            mark("controlled-vehicle:driving")
            compose.runOnIdle { live.publish(driving()) }
            waitTag("home_locked_title")
        }

        fun publishParked() {
            mark("controlled-vehicle:parked")
            compose.runOnIdle { live.publish(parked()) }
            waitTag("home_journey")
        }

        private fun awaitHost() {
            val info = instrument.uiAutomation.serviceInfo
            originalFlag = info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0
            info.flags = info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
            instrument.uiAutomation.serviceInfo = info
            check(instrument.uiAutomation.serviceInfo.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0)
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT
            var candidate: Int? = null
            var candidateAt: Long? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                var current: Int? = null
                instrument.runOnMainSync {
                    val activity = compose.activity
                    val display = activity.display
                    if (activity.lifecycle.currentState == Lifecycle.State.RESUMED && activity.hasWindowFocus() && display != null &&
                        display.isValid
                    ) {
                        current = display.displayId
                    }
                }
                if (current != null) {
                    displayId = current
                    val actual = focusedPackage()
                    if (actual != null) {
                        check(actual == instrument.targetContext.packageName) { "Initial actual focused window belongs to another app" }
                        val now = SystemClock.elapsedRealtime()
                        if (candidate == current && candidateAt != null && now - checkNotNull(candidateAt) >= 100) return
                        if (candidate != current) {
                            candidate = current
                            candidateAt = now
                        }
                    } else {
                        candidate = null
                        candidateAt = null
                    }
                } else {
                    candidate = null
                    candidateAt = null
                }
                SystemClock.sleep(50)
            }
            error("Actual Catalog initial RESUMED/display/focus was not confirmed")
        }

        fun requireHost() {
            lastFocusKnown = null
            lastFocusExpected = null
            val actual = hostReceipt()
            lastGuard = actual
            check(actual.resumed) { "Actual Catalog host is not RESUMED" }
            check(actual.windowFocus) { "Actual Catalog host has no window focus" }
            check(actual.displayKnown) { "Actual Catalog display is unknown" }
            check(actual.displayValid) { "Actual Catalog display is invalid" }
            check(actual.sameDisplay) { "Actual Catalog display differs from the captured display" }
            val focused = focusedPackage()
            lastFocusKnown = focused != null
            lastFocusExpected = focused == instrument.targetContext.packageName
            check(lastFocusExpected == true) { "Current actual focused window is unknown or foreign" }
        }

        private fun hostReceipt(): HostReceipt {
            var actual: HostReceipt? = null
            instrument.runOnMainSync {
                val activity = compose.activity
                val display = activity.display
                actual =
                    HostReceipt(
                        activity.lifecycle.currentState.name,
                        activity.lifecycle.currentState == Lifecycle.State.RESUMED,
                        activity.hasWindowFocus(),
                        display != null,
                        display?.isValid == true,
                        display != null && displayId != null && display.displayId == displayId,
                        owner.lifecycle.currentState.name,
                        entryEpoch.value,
                    )
            }
            return checkNotNull(actual)
        }

        private fun mark(value: String) {
            phase = value
            phaseHistory += JSONObject().put("phase", value).put("appUptimeMillis", SystemClock.uptimeMillis())
        }

        /** A single failure observation, never a retry or a replacement for the original assertion. */
        fun captureFailure(original: Throwable) {
            if (failureCaptured) return
            failureCaptured = true
            val failedPhase = phase
            val failures = JSONArray()
            val report =
                JSONObject()
                    .put("kind", "real-Compose-controlled-finish-source-review-boundary-diagnostic")
                    .put("status", "failed")
                    .put("failureClass", original.javaClass.name)
                    .put("phase", failedPhase)
                    .put("lastAction", lastAction ?: JSONObject.NULL)
                    .put("mountStage", mountStage)
                    .put("entryEpoch", entryEpoch.value)
                    .put("phaseHistory", JSONArray(phaseHistory))
                    .put("lastGuard", lastGuard?.json() ?: JSONObject.NULL)
                    .put("lastFocusedRootKnown", lastFocusKnown ?: JSONObject.NULL)
                    .put("lastFocusedRootExpectedApp", lastFocusExpected ?: JSONObject.NULL)
                    .put("observationOnlyNoRetry", true)
                    .put("diagnosticFailures", failures)
            var directory: File? = null

            fun attempt(
                stage: String,
                action: () -> Unit,
            ) {
                try {
                    action()
                } catch (failure: Throwable) {
                    failures.put(JSONObject().put("stage", stage).put("class", failure.javaClass.name))
                }
            }
            attempt("private-directory") {
                directory = File(instrument.targetContext.cacheDir, "screenshots/finish-source-review-boundary-" + UUID.randomUUID())
                check(directory!!.mkdirs())
            }
            attempt("actual-and-controlled-lifecycle") {
                report.put("failureObservation", hostReceipt().json())
            }
            attempt("actual-interactive-windows-flag") {
                report.put(
                    "observedInteractiveWindowsFlag",
                    instrument.uiAutomation.serviceInfo.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0,
                )
            }
            attempt("history-tags-before-UI-disposal") {
                check(Looper.myLooper() != Looper.getMainLooper()) { "Semantics diagnostics require the instrumentation thread" }
                val roots = compose.onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes(false)

                fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
                val tags = roots.flatMap(::descendants).mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }.toSet()
                val known =
                    listOf(
                        "history_loading",
                        "history_locked",
                        "history_empty",
                        "history_failed",
                        "history_ready",
                        "finished_review_unavailable",
                        "history_close",
                        "history_memo",
                        "history_memo_empty",
                        "home_journey",
                        "home_locked_title",
                        "session_finish",
                        "session_finish_review",
                    )
                report.put("historyTags", JSONObject().apply { known.forEach { put(it, it in tags) } })
                report.put("semanticsRootCount", roots.size)
            }
            attempt("private-PNG") {
                val bitmap = checkNotNull(instrument.uiAutomation.takeScreenshot())
                try {
                    val image = File(checkNotNull(directory), "failure-before-cleanup.png")
                    FileOutputStream(image).use { output -> check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
                    report.put("privateScreenshotCaptured", true)
                } finally {
                    bitmap.recycle()
                }
            }
            attempt("private-JSON") {
                val output = AtomicFile(File(checkNotNull(directory), "failure-before-cleanup.json"))
                val stream = output.startWrite()
                try {
                    stream.write(report.toString(2).toByteArray(Charsets.UTF_8))
                    output.finishWrite(stream)
                } catch (failure: Throwable) {
                    output.failWrite(stream)
                    throw failure
                }
            }
            if (failures.length() > 0) {
                // File exceptions can contain a private path. Never attach their raw message/cause to JUnit.
                original.addSuppressed(IllegalStateException("Private boundary diagnostic capture was incomplete"))
            }
        }

        private data class HostReceipt(
            val lifecycle: String,
            val resumed: Boolean,
            val windowFocus: Boolean,
            val displayKnown: Boolean,
            val displayValid: Boolean,
            val sameDisplay: Boolean,
            val controlledLifecycle: String,
            val entryEpoch: Int,
        ) {
            fun json(): JSONObject =
                JSONObject()
                    .put("actualLifecycle", lifecycle)
                    .put("actualResumed", resumed)
                    .put("actualWindowFocus", windowFocus)
                    .put("displayKnown", displayKnown)
                    .put("displayValid", displayValid)
                    .put("sameDisplay", sameDisplay)
                    .put("controlledLifecycle", controlledLifecycle)
                    .put("entryEpoch", entryEpoch)
        }

        private fun focusedPackage(): String? {
            val captured = displayId ?: return null
            val all = instrument.uiAutomation.windowsOnAllDisplays
            val windows = mutableListOf<AccessibilityWindowInfo>()
            var root: AccessibilityNodeInfo? = null
            try {
                for (index in 0 until all.size()) windows.addAll(all.valueAt(index))
                val current = all[captured] ?: return null
                if (current.any { it.displayId != captured }) return null
                val focused = current.filter { it.isFocused }
                if (focused.size != 1) return null
                root = focused.single().root ?: return null
                return root?.packageName?.toString()
            } finally {
                root?.recycle()
                windows.forEach { it.recycle() }
            }
        }

        fun dispose() {
            if (disposed) return
            mark("dispose:begin")
            disposed = true
            var failure: Throwable? = null

            fun attempt(action: () -> Unit) {
                try {
                    action()
                } catch (problem: Throwable) {
                    if (failure == null) failure = problem else failure!!.addSuppressed(problem)
                }
            }
            var lifecycleRetired = false
            var uiDisposed = false
            var modelsCleared = false
            attempt {
                instrument.runOnMainSync {
                    if (owner.lifecycle.currentState != Lifecycle.State.INITIALIZED) owner.move(Lifecycle.State.DESTROYED)
                }
                lifecycleRetired = true
                mark("dispose:controlled-lifecycle-retired")
            }
            attempt {
                instrument.runOnMainSync {
                    visible.value = false
                    compose.activity.setContent { }
                }
                uiDisposed = true
                mark("dispose:UI-disposed")
            }
            attempt {
                instrument.runOnMainSync { store.clear() }
                modelsCleared = true
                mark("dispose:models-cleared")
            }
            attempt {
                compose.waitUntil(TIMEOUT) { activeReads.get() == 0 && activeWrites.get() == 0 }
                check(lifecycleRetired && uiDisposed && modelsCleared)
                isStopped = true
                mark("dispose:owned-reads-and-writes-stopped")
            }
            attempt {
                originalFlag?.let { before ->
                    val info = instrument.uiAutomation.serviceInfo
                    info.flags =
                        if (before) {
                            info.flags or AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                        } else {
                            info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS.inv()
                        }
                    instrument.uiAutomation.serviceInfo = info
                    check(
                        (instrument.uiAutomation.serviceInfo.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0) ==
                            before,
                    )
                }
            }
            mark("dispose:after-flag-restoration")
            failure?.let { throw it }
            mark("dispose:completed")
        }
    }

    internal class Sessions : PracticeSessionRepository {
        val rows = MutableStateFlow(listOf(source(A)))
        val finishes = AtomicInteger(0)
        val legacyFinishes = AtomicInteger(0)
        val completed = AtomicInteger(0)

        @Volatile var mode = 0
        var onFinish: suspend (
            PracticeSession,
            Long,
            () -> Boolean,
        ) -> SessionFinishResult = { _, _, _ -> error("Unconfigured controlled strict finish") }

        override fun observeSessions(): Flow<List<PracticeSession>> =
            flow {
                when (mode) {
                    1 -> error("Controlled source read failure")
                    2 -> emit(rows.value)
                    3 -> throw CancellationException("Controlled source cancellation")
                    else -> emitAll(rows)
                }
            }

        override suspend fun start(
            practice: PreparedPractice,
            startedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionStartResult = error("Unexpected practice start")

        override suspend fun finish(
            expectedSource: PracticeSession,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult {
            finishes.incrementAndGet()
            try {
                return onFinish(expectedSource, endedAtEpochMillis, canCommit)
            } finally {
                completed.incrementAndGet()
            }
        }

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult {
            legacyFinishes.incrementAndGet()
            throw AssertionError("The legacy ID writer must never be called")
        }
    }

    internal class Children {
        val memoQueries = ConcurrentLinkedQueue<String>()
        val observationQueries = ConcurrentLinkedQueue<String>()
        val runQueries = ConcurrentLinkedQueue<String>()
        val completionQueries = ConcurrentLinkedQueue<String>()
        val memoValues = MutableStateFlow<Map<String, SessionMemo?>>(emptyMap())
        val memos =
            object : SessionMemoRepository {
                override fun observe(sessionId: String): Flow<SessionMemo?> {
                    memoQueries.add(sessionId)
                    return memoValues.map { it[sessionId] }
                }

                override suspend fun save(
                    sessionId: String,
                    text: String,
                    expectedVersion: String?,
                    canCommit: () -> Boolean,
                ): SessionMemoWriteResult = throw AssertionError("Unexpected latest memo write")

                override suspend fun delete(
                    sessionId: String,
                    expectedVersion: String,
                    canCommit: () -> Boolean,
                ): SessionMemoWriteResult = throw AssertionError("Unexpected latest memo delete")
            }
        val observations =
            object : VehicleObservationRepository {
                private val values = MutableStateFlow<List<SessionVehicleObservation>>(emptyList())

                override fun observe(sessionId: String): Flow<List<SessionVehicleObservation>> {
                    observationQueries.add(sessionId)
                    return values
                }

                override suspend fun append(
                    sessionId: String,
                    observation: VehicleObservation,
                ): VehicleObservationAppendResult = throw AssertionError("Unexpected observation append")
            }
        val runs =
            object : RecordingRunRepository {
                private val values = MutableStateFlow<List<VehicleRecordingRun>>(emptyList())

                override fun observe(sessionId: String): Flow<List<VehicleRecordingRun>> {
                    runQueries.add(sessionId)
                    return values
                }

                override suspend fun open(
                    source: PracticeSession,
                    runId: String,
                    processId: String,
                    startedAtEpochMillis: Long,
                    canCommit: () -> Boolean,
                ): RecordingRunOpenResult = throw AssertionError("Unexpected run open")

                override suspend fun finish(
                    runId: String,
                    processId: String,
                    status: RecordingRunStatus,
                    endedAtEpochMillis: Long?,
                ): Boolean = throw AssertionError("Unexpected run finish")

                override suspend fun recoverInterrupted(processId: String): Unit = throw AssertionError("Unexpected recovery")
            }
        val completions =
            object : CourseCompletionRepository {
                private val values = MutableStateFlow<CourseCompletionDeclaration?>(null)

                override fun observe(sessionId: String): Flow<CourseCompletionDeclaration?> {
                    completionQueries.add(sessionId)
                    return values
                }

                override suspend fun declare(
                    expectedSource: PracticeSession,
                    declaredAtEpochMillis: Long,
                    canCommit: () -> Boolean,
                ): CourseCompletionDeclarationResult = throw AssertionError("Unexpected declaration write")
            }

        fun totalReads(): Int = memoQueries.size + observationQueries.size + runQueries.size + completionQueries.size
    }

    internal class Owner(
        override val viewModelStore: ViewModelStore,
    ) : LifecycleOwner,
        ViewModelStoreOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun move(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    @OptIn(InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
    internal class SilentVehicle(
        initial: VehicleState,
    ) : StateFlow<VehicleState> {
        private val published = MutableStateFlow(initial)

        @Volatile private var current = initial
        override val value: VehicleState get() = current
        override val replayCache: List<VehicleState> get() = listOf(current)

        override suspend fun collect(collector: FlowCollector<VehicleState>): Nothing = published.collect(collector)

        fun silent(value: VehicleState) {
            current = value
        }

        fun publish(value: VehicleState) {
            current = value
            published.value = value
        }
    }

    companion object {
        internal const val TIMEOUT = 20_000L
        internal const val TIME = 1_700_000_000_000L
        internal const val A = "controlled-finish-a"
        internal const val B = "controlled-finish-b"

        internal fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        internal fun driving() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.DRIVE))

        internal fun source(
            id: String,
            end: Long? = null,
        ) = PracticeSession(
            id,
            selection(),
            "Controlled goal",
            "Controlled demo course",
            if (id == B) "Controlled newer task" else "Controlled finish task",
            CourseReviewStatus.DEMO,
            TIME + 1_000,
            end,
        )

        internal fun catalog(): PracticeCatalog =
            object : PracticeCatalog {
                override val goals = listOf(PracticeGoal("goal", "Controlled goal", "Controlled preparation"))
                override val courses =
                    listOf(
                        PracticeCourse(
                            "course",
                            1,
                            1,
                            "Controlled demo course",
                            "Controlled summary",
                            listOf("Controlled briefing"),
                            listOf(PracticeTask("task", "Controlled finish task", "Controlled description")),
                            setOf("goal"),
                        ),
                    )
            }

        internal fun selection() = PracticeSelection("goal", "course", 1, 1, "task")

        internal fun preparation(): PracticeRepository =
            object : PracticeRepository {
                private val value = MutableStateFlow<PracticeSelection?>(selection())

                override fun observeSelection(): Flow<PracticeSelection?> = value

                override suspend fun saveSelection(
                    selection: PracticeSelection,
                    canCommit: () -> Boolean,
                ): Boolean = throw AssertionError("Unexpected preparation write")
            }

        internal fun noHistoryWriter(): HistorySessionMemoWriter =
            object : HistorySessionMemoWriter {
                override suspend fun update(
                    expectedSource: PracticeSession,
                    expectedMemo: SessionMemo,
                    text: String,
                    canCommit: () -> Boolean,
                ): SessionMemoWriteResult = throw AssertionError("Unexpected historical memo write")
            }

        internal fun noDeletion(): SessionDeletionRepository =
            object : SessionDeletionRepository {
                override suspend fun prepare(
                    expected: PracticeSession,
                    canRead: () -> Boolean,
                ): SessionDeletionPreparation = throw AssertionError("Unexpected deletion prepare")

                override suspend fun delete(
                    target: SessionDeletionTarget,
                    canCommit: () -> Boolean,
                ): SessionDeletionResult = throw AssertionError("Unexpected deletion write")
            }

        private fun noNavigation(): NavigationGateway =
            object : NavigationGateway {
                override val connection = MutableStateFlow(ConnectionState.DISCONNECTED)

                override suspend fun requestForeground(canDispatch: () -> Boolean): NavigationScreenResult =
                    throw AssertionError("Unexpected SDK navigation")
            }

        private fun noRecording(): RecordingController =
            object : RecordingController {
                override val state = MutableStateFlow<VehicleRecordingState>(VehicleRecordingState.Paused)
                override val background = MutableStateFlow<BackgroundRecordingState>(BackgroundRecordingState.Disabled)

                override suspend fun recordWhileStarted(): Unit = throw AssertionError("Unexpected SDK recorder")

                override fun retry(): Boolean = throw AssertionError("Unexpected recorder retry")

                override fun requestBackground(
                    expected: PracticeSession,
                    canStart: () -> Boolean,
                ): Boolean = throw AssertionError("Unexpected FGS start")

                override fun stopBackground(expectedRunId: String): Boolean = throw AssertionError("Unexpected FGS stop")

                override suspend fun recordInService(
                    runId: String,
                    sessionId: String,
                ): Unit = throw AssertionError("Unexpected FGS recording")

                override fun startFailed(runId: String): Unit = throw AssertionError("Unexpected FGS callback")
            }

        private fun assumeManual() =
            assumeTrue(InstrumentationRegistry.getArguments().getString("manualFinishSourceReviewBoundary") == "true")
    }
}
