package io.github.hangillab.hangil

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.view.Display
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.NextPracticeSeed
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.VehicleObservationSource
import io.github.hangillab.hangil.core.domain.VehicleReading
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import io.github.hangillab.hangil.feature.home.R as HomeR

/**
 * Actual Hilt Main, SDK P/UX and production Room. Preparation, start and finish are real UI
 * actions; repositories are read only from this harness. This creates one owned session and
 * its SDK observations. The root runner must restore the canonical schema6 DB, sequences,
 * settings and DDS even after failure. The protected schema7 device is never a test target.
 * This is correctness/UI QA, not a performance measurement or stale-callback simulation.
 */
@RunWith(AndroidJUnit4::class)
class FinishedSessionReviewFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val h by lazy { SessionHistoryRecordingRunFlowTest.Harness(compose) }
    private val directory by lazy {
        File(h.instrument.targetContext.cacheDir, "screenshots/finished-source-review-${UUID.randomUUID()}").apply { check(mkdirs()) }
    }
    private val report = JSONObject().put("kind", "actual-Main-SDK-UI-finish-explicit-review").put("status", "started")
    private var actualHost: MainActivity? = null
    private var actualDisplay: Int? = null
    private var owned: PracticeSession? = null
    private var expectedPreparation: PracticeSelection? = null
    private var baselineHash: String? = null
    private val baselineFile by lazy {
        File(h.instrument.targetContext.cacheDir, "measurements/history-memo-read-history-memo-edit-read-v1-baseline.json")
    }

    @Test fun actualUiFinishExplicitExactReviewAndDrivingLockPreserveOriginals() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("manualFinishedSessionReviewFlow") == "true")
        var primary: Throwable? = null
        var sdkObserver: Job? = null
        var before: SessionHistoryRecordingRunFlowTest.Harness.ExistingData? = null
        var physical: JSONObject? = null
        var environment: JSONObject? = null
        var catalog: JSONObject? = null
        try {
            h.assertEnvironment()
            captureHostReceipt()
            runBlocking {
                try {
                    val original = h.snapshot(h.graph)
                    before = original
                    check(original.sessions.none { it.isActive } && original.preparation != null)
                    expectedPreparation = original.preparation
                    val originalPhysical = physicalDatabase()
                    physical = originalPhysical
                    assertBaseline(original, originalPhysical)
                    environment = h.environmentJson()
                    catalog = h.catalogJson(h.graph)
                    report
                        .put("originalData", frozenDataJson(original, originalPhysical))
                        .put("physicalBefore", originalPhysical)
                        .put("environment", environment)
                        .put("catalog", catalog)
                        .put("rootCanonicalAndSequenceRestoreRequired", true)
                        .put("nativeRepositoryWrites", 0)
                    persist()
                    sdkObserver =
                        launch {
                            combine(h.graph.vehicle().state, h.graph.restrictions().restrictions) { v, r -> v to r }.collect { }
                        }
                    initialAllowedAdmission()
                    assertOriginals(original, originalPhysical)
                    requireHost()
                    prepareThroughUiIfRequired(original)
                    report.put("uiStartRequested", true)
                    persist()
                    touch("session_start")
                    val started =
                        withTimeout(20_000) {
                            h.graph
                                .sessions()
                                .observeSessions()
                                .first { rows ->
                                    rows.count { it.isActive } == 1 &&
                                        rows.count { it.sessionId !in original.sessions.map { it.sessionId } } == 1
                                }.single { it.isActive }
                        }
                    check(started.sessionId !in original.sessions.map { it.sessionId })
                    check(started.selection == expectedPreparation)
                    owned = started
                    report.put("ownedActiveSource", h.sessionJson(started)).put("status", "owned-active")
                    persist()
                    withTimeout(20_000) {
                        h.graph.observations().observe(started.sessionId).first { rows ->
                            rows.any {
                                it.observation.source == VehicleObservationSource.SDK_STATE_OBSERVATION &&
                                    it.observation.state.gear == VehicleReading.Available(GearPosition.PARK)
                            }
                        }
                    }
                    assertOriginals(original, originalPhysical)
                    check(h.nodes("history_ready").isEmpty())
                    touch("session_finish")
                    val ended =
                        withTimeout(20_000) {
                            h.graph
                                .sessions()
                                .observeSessions()
                                .first { rows ->
                                    rows.singleOrNull { it.sessionId == started.sessionId }?.isActive == false
                                }.single { it.sessionId == started.sessionId }
                        }
                    check(ended == started.copy(endedAtEpochMillis = checkNotNull(ended.endedAtEpochMillis)))
                    owned = ended
                    report.put("ownedEndedSource", h.sessionJson(ended)).put("status", "owned-ended")
                    persist()
                    requireHost()
                    h.waitFor("session_finish_review")
                    check(h.nodes("history_ready").isEmpty()) { "finish must not navigate automatically" }
                    visibleText("session_finish_review")
                    capture("explicit-review-action", false)
                    touch("session_finish_review")
                    assertExact(ended)
                    assertCold()
                    visibleText("history_course")
                    visibleText("history_task")
                    visibleText("history_finished")
                    capture("exact-review", false)
                    compose.activityRule.scenario.recreate()
                    h.assertEnvironment()
                    captureHostReceipt()
                    h.awaitAllowed()
                    assertExact(ended)
                    assertCold()
                    touch("history_observations")
                    h.waitFor("review_ready")
                    h.assertText("review_source", h.text(HomeR.string.review_source))
                    visibleText("review_source")
                    capture("stored-actual-SDK-observation", false)
                    phase("FINISH_SOURCE_REVIEW_AWAIT_D")
                    h.awaitDrive()
                    requireHost()
                    h.waitFor("history_locked")
                    compose.waitUntil(20_000) {
                        listOf("history_ready", "history_observation_content", "history_memo_content").all { h.nodes(it).isEmpty() }
                    }
                    check(!h.liveAllowed())
                    capture("actual-D-locked", false)
                    assertOriginals(original, originalPhysical)
                    phase("FINISH_SOURCE_REVIEW_AWAIT_P")
                    h.awaitAllowed()
                    requireHost()
                    assertExact(ended)
                    assertCold()
                    check(h.nodes("review_ready").isEmpty()) { "D/P recovery must not revive an old detail disclosure" }
                    touch("history_observations")
                    h.waitFor("review_ready")
                    h.assertText("review_source", h.text(HomeR.string.review_source))
                    capture("fresh-P-exact-review", false)
                    touch("history_close")
                    h.waitFor("home_history")
                    check(h.nodes("history_ready").isEmpty() && h.nodes("session_finish_review").isEmpty())
                    requireHost()
                    assertOriginals(original, originalPhysical)
                    report
                        .put("status", "body-complete")
                        .put("actualUiStart", true)
                        .put("actualUiFinish", true)
                        .put("explicitSameEndedSourceReview", true)
                        .put("retainedAcrossRecreation", true)
                        .put("actualSDKDrivingLockAndFreshParkReopen", true)
                    persist()
                    sdkObserver?.cancelAndJoin()
                    sdkObserver = null
                } catch (failure: Throwable) {
                    captureFailureBeforeCleanup(failure, "body-before-coroutine-unwind", sdkObserver?.isActive)
                    throw failure
                }
            }
        } catch (failure: Throwable) {
            primary = failure
            captureFailureBeforeCleanup(failure, "initialization-before-independent-cleanup", sdkObserver?.isActive)
            report.put("status", "failed").put("failureClass", failure.javaClass.name)
            try {
                persist()
            } catch (diagnostic: Throwable) {
                failure.addSuppressed(diagnostic)
            }
            try {
                capture("failure-diagnostic-unverified", true)
            } catch (diagnostic: Throwable) {
                failure.addSuppressed(diagnostic)
            }
            throw failure
        } finally {
            var first: Throwable? = null

            fun attempt(
                name: String,
                action: () -> Unit,
            ) {
                try {
                    action()
                    report.put(name, true)
                } catch (failure: Throwable) {
                    report.put(name, false)
                    if (first == null) {
                        first = failure
                    } else if (first !== failure) {
                        first!!.addSuppressed(failure)
                    }
                    primary?.let { if (it !== failure) it.addSuppressed(failure) }
                }
            }
            runBlocking {
                withContext(NonCancellable) {
                    attempt("originalDomainAndTypedRowsPreserved") {
                        runBlocking { assertOriginals(checkNotNull(before), checkNotNull(physical)) }
                    }
                    attempt("baselineFilePreserved") { check(sha256(baselineFile) == checkNotNull(baselineHash)) }
                    attempt("environmentPreserved") { h.requireSameJson(checkNotNull(environment), h.environmentJson(), "environment") }
                    attempt("catalogPreserved") { h.requireSameJson(checkNotNull(catalog), h.catalogJson(h.graph), "catalog") }
                    attempt("sdkObserverCancelled") { runBlocking { sdkObserver?.cancelAndJoin() } }
                    attempt("automationFlagRestored") { h.restoreAutomationFlag() }
                    if (first != null) report.put("cleanupFailureClass", first!!.javaClass.name).put("status", "failed")
                    attempt("privateReportSaved") { persist() }
                }
            }
            if (primary == null) first?.let { throw it }
        }
        report.put("status", "completed")
        persist()
        phase("FINISH_SOURCE_REVIEW_COMPLETED")
    }

    private fun captureFailureBeforeCleanup(
        original: Throwable,
        phase: String,
        observerActive: Boolean?,
    ) {
        if (report.has("failureBeforeCleanupCaptureAttempted")) return
        report.put("failureBeforeCleanupCaptureAttempted", true)
        try {
            recordFailureBeforeCleanup(phase, observerActive)
        } catch (diagnostic: Throwable) {
            if (diagnostic !== original) original.addSuppressed(diagnostic)
        }
    }

    /** The body catch runs before runBlocking cancels its child observer during exception unwind. */
    private fun recordFailureBeforeCleanup(
        phase: String,
        observerActive: Boolean?,
    ) {
        val state =
            JSONObject()
                .put("scope", "current-receipts-before-native-cleanup-not-a-causal-verdict")
                .put("observed_at_elapsed_realtime_nanos", SystemClock.elapsedRealtimeNanos())
                .put("capture_phase", phase)
                .put("sdk_observer_active_at_capture", observerActive ?: JSONObject.NULL)
        var first: Throwable? = null

        fun attempt(
            name: String,
            action: () -> Unit,
        ) {
            try {
                action()
            } catch (failure: Throwable) {
                state.put(name + "_failure_class", failure.javaClass.name)
                if (first == null) {
                    first = failure
                } else if (first !== failure) {
                    first!!.addSuppressed(failure)
                }
            }
        }

        fun reading(value: VehicleReading<*>): JSONObject =
            when (value) {
                is VehicleReading.Available ->
                    JSONObject()
                        .put("kind", "AVAILABLE")
                        .put("enum_value", (value.value as? Enum<*>)?.name ?: JSONObject.NULL)
                        .put("boolean_value", (value.value as? Boolean) ?: JSONObject.NULL)
                VehicleReading.Unknown -> JSONObject().put("kind", "UNKNOWN")
                VehicleReading.Unsupported -> JSONObject().put("kind", "UNSUPPORTED")
                VehicleReading.PermissionDenied -> JSONObject().put("kind", "PERMISSION_DENIED")
                VehicleReading.Error -> JSONObject().put("kind", "ERROR")
            }
        attempt("vehicle_and_UX") {
            val vehicle =
                h.graph
                    .vehicle()
                    .state.value
            val restrictions =
                h.graph
                    .restrictions()
                    .restrictions.value
            state
                .put("vehicle_connection", vehicle.connection.name)
                .put("vehicle_gear", reading(vehicle.gear))
                .put("vehicle_turn_signal", reading(vehicle.turnSignal))
                .put("vehicle_brake", reading(vehicle.brakePressed))
                .put("parking_state", restrictions.parkingState.name)
                .put("UX_restriction_state", restrictions.uxRestrictionState.name)
                .put("current_live_allowed", h.liveAllowed())
                .put("state_reads_are_sequential", true)
        }
        attempt("actual_host") {
            h.instrument.runOnMainSync {
                val host = compose.activity
                val display = host.display
                state
                    .put("same_captured_host", host === actualHost)
                    .put("main_lifecycle", host.lifecycle.currentState.name)
                    .put("has_window_focus", host.hasWindowFocus())
                    .put("display_known", display != null)
                    .put("display_valid", display?.isValid ?: JSONObject.NULL)
                    .put(
                        "same_captured_display",
                        if (display != null &&
                            actualDisplay != null
                        ) {
                            display.displayId == actualDisplay
                        } else {
                            JSONObject.NULL
                        },
                    )
            }
        }
        attempt("actual_focused_window") {
            val actual = h.actualPackage()
            state
                .put("focused_package_known", actual != null)
                .put("focused_package_is_own", if (actual != null) actual == h.instrument.targetContext.packageName else JSONObject.NULL)
        }
        report.put("failureBeforeCleanupState", state)
        try {
            persist()
        } catch (failure: Throwable) {
            if (first == null) {
                first = failure
            } else if (first !== failure) {
                first!!.addSuppressed(failure)
            }
        }
        first?.let { throw it }
    }

    /** No UI input is admitted until all current preconditions hold on the originally captured host. */
    private suspend fun initialAllowedAdmission() {
        check(owned == null && !report.has("initialAllowedAdmission"))
        val expectedHost = checkNotNull(actualHost)
        val expectedDisplay = checkNotNull(actualDisplay)
        val started = SystemClock.elapsedRealtime()
        val deadline = started + 90_000L
        val admission =
            JSONObject()
                .put("scope", "pre-input-acquisition-not-continuous-foreground-proof")
                .put("state", "acquiring")
                .put("timeout_millis", 90_000)
                .put("poll_interval_millis", 100)
        report.put("initialAllowedAdmission", admission)
        var polls = 0
        var unknownPackageSamples = 0
        var knownOtherPackageSamples = 0
        var hostNotReadySamples = 0
        var gateNotAllowedSamples = 0
        var primary: Throwable? = null
        var stableSince = -1L
        var stabilityRejectedSampleResets = 0
        var stablePolls = 0
        var maximumObservedStableMillis = 0L
        var lastObservedStableMillis = 0L
        admission
            .put("required_observed_stability_millis", 1000)
            .put("stability_basis", "all-100ms-polled-current-conditions-with-no-rejected-or-unknown-sample")
        try {
            withTimeout(90_000) {
                while (true) {
                    check(SystemClock.elapsedRealtime() < deadline) { "initial admission exceeded its absolute deadline" }
                    var sameHost = false
                    var resumed = false
                    var windowFocus = false
                    var validDisplay = false
                    var sameDisplay = false
                    h.instrument.runOnMainSync {
                        val host = compose.activity
                        val display = host.display
                        sameHost = host === expectedHost
                        resumed = host.lifecycle.currentState == Lifecycle.State.RESUMED
                        windowFocus = host.hasWindowFocus()
                        validDisplay = display != null && display.isValid
                        sameDisplay = display?.displayId == expectedDisplay
                    }
                    val actualPackage = h.actualPackage()
                    val allowed = h.liveAllowed()
                    polls++
                    if (actualPackage == null) {
                        unknownPackageSamples++
                    } else if (actualPackage != h.instrument.targetContext.packageName) {
                        knownOtherPackageSamples++
                    }
                    val hostReady = sameHost && resumed && windowFocus && validDisplay && sameDisplay
                    if (!hostReady) hostNotReadySamples++
                    if (!allowed) gateNotAllowedSamples++
                    admission
                        .put("last_same_captured_host", sameHost)
                        .put("last_resumed", resumed)
                        .put("last_window_focus", windowFocus)
                        .put("last_display_valid", validDisplay)
                        .put("last_same_captured_display", sameDisplay)
                        .put("last_package_known", actualPackage != null)
                        .put("last_package_own", actualPackage == h.instrument.targetContext.packageName)
                        .put("last_received_P_UX_allowed", allowed)
                    check(SystemClock.elapsedRealtime() < deadline) { "initial admission observation exceeded its absolute deadline" }
                    val observedAt = SystemClock.elapsedRealtime()
                    if (hostReady && allowed && actualPackage == h.instrument.targetContext.packageName) {
                        if (stableSince < 0) stableSince = observedAt
                        stablePolls++
                        lastObservedStableMillis = observedAt - stableSince
                        maximumObservedStableMillis = maxOf(maximumObservedStableMillis, lastObservedStableMillis)
                        if (lastObservedStableMillis >= 1000) {
                            requireHost()
                            check(h.liveAllowed())
                            check(SystemClock.elapsedRealtime() < deadline) { "final initial admission exceeded its absolute deadline" }
                            admission
                                .put("state", "admitted")
                                .put("admitted_at_elapsed_realtime_millis", SystemClock.elapsedRealtime())
                                .put("admitted_observed_stability_millis", lastObservedStableMillis)
                            return@withTimeout
                        }
                    } else {
                        stableSince = -1L
                        stablePolls = 0
                        lastObservedStableMillis = 0L
                        stabilityRejectedSampleResets++
                    }
                    delay(100)
                }
            }
        } catch (failure: Throwable) {
            primary = failure
            admission.put("state", "failed").put("failure_class", failure.javaClass.name)
            throw failure
        } finally {
            admission
                .put("elapsed_wait_millis", SystemClock.elapsedRealtime() - started)
                .put("polls", polls)
                .put("unknown_package_samples", unknownPackageSamples)
                .put("known_other_package_samples", knownOtherPackageSamples)
                .put("host_not_ready_samples", hostNotReadySamples)
                .put("received_gate_not_allowed_samples", gateNotAllowedSamples)
                .put("stability_rejected_sample_resets", stabilityRejectedSampleResets)
                .put("current_stable_polls", stablePolls)
                .put("last_observed_stability_millis", lastObservedStableMillis)
                .put("maximum_observed_stability_millis", maximumObservedStableMillis)
                .put("stable_since_elapsed_realtime_millis", if (stableSince >= 0) stableSince else JSONObject.NULL)
            try {
                persist()
            } catch (diagnostic: Throwable) {
                if (primary == null) throw diagnostic else primary.addSuppressed(diagnostic)
            }
        }
    }

    private suspend fun prepareThroughUiIfRequired(before: SessionHistoryRecordingRunFlowTest.Harness.ExistingData) {
        compose.waitUntil(20_000) {
            h.nodes("session_start").size == 1 || h.nodes("home_next_practice").size == 1
        }
        val start = h.nodes("session_start").singleOrNull()
        if (start != null && !start.config.contains(SemanticsProperties.Disabled)) return
        val latest = before.sessions.first { !it.isActive }
        val seed =
            checkNotNull(NextPracticeSeed.resolve(h.graph.catalog(), latest)) { "actual UI preparation is unavailable; no seed write" }
        val task =
            seed.practice.course.tasks
                .firstOrNull { it.id != before.preparation?.taskId } ?: seed.practice.course.tasks
                .first()
        val selection =
            PracticeSelection(
                seed.practice.goal.id,
                seed.practice.course.id,
                seed.practice.course.version,
                seed.practice.course.briefingVersion,
                task.id,
            )
        touch("home_next_practice")
        h.waitFor("next_practice_ready")
        h.assertText("next_practice_course", latest.courseTitle)
        touch("next_task_${task.id}")
        touch("next_practice_save")
        withTimeout(20_000) {
            h.graph
                .preparation()
                .observeSelection()
                .first { it == selection }
        }
        expectedPreparation = selection
        report.put("intentionalUiPreparationSelection", h.selectionJson(selection))
        persist()
        h.waitFor("session_start")
        check(
            !h
                .nodes("session_start")
                .single()
                .config
                .contains(SemanticsProperties.Disabled),
        ) { "actual UI start remains blocked" }
    }

    private fun captureHostReceipt() {
        h.instrument.runOnMainSync {
            val host = compose.activity
            val display = checkNotNull(host.display)
            check(host.lifecycle.currentState == Lifecycle.State.RESUMED && host.hasWindowFocus() && display.isValid)
            actualHost = host
            actualDisplay = display.displayId
        }
        requireHost()
    }

    private fun requireHost() {
        h.instrument.runOnMainSync {
            val host = compose.activity
            val display = host.display
            check(host === actualHost && host.lifecycle.currentState == Lifecycle.State.RESUMED && host.hasWindowFocus())
            check(display != null && display.isValid && display.displayId == actualDisplay)
        }
        h.requireForeground()
    }

    private fun touch(tag: String) {
        requireHost()
        check(h.liveAllowed())
        h.touch(tag)
        requireHost()
    }

    private suspend fun assertExact(source: PracticeSession) {
        requireHost()
        check(h.liveAllowed())
        h.waitFor("history_ready")
        val actual =
            h.graph
                .sessions()
                .observeSessions()
                .first()
        check(actual.single { it.sessionId == source.sessionId } == source && !source.isActive)
        val ended = actual.filterNot { it.isActive }
        h.assertSource(source, ended.indexOf(source), ended.size)
        for (tag in listOf("history_newer", "history_older", "history_reset", "finished_review_unavailable", "history_failed")) {
            check(h.nodes(tag).isEmpty())
        }
    }

    private fun assertCold() {
        for (tag in listOf(
            "history_observations",
            "history_memo",
            "history_summary_tasks",
            "history_recording_runs",
            "history_course_completion",
        )) {
            h.assertCollapsed(tag)
        }
        for (tag in listOf(
            "history_observation_content",
            "history_memo_content",
            "history_recording_run_content",
            "history_course_completion_content",
        )) {
            check(h.nodes(tag).isEmpty())
        }
    }

    private fun visibleText(tag: String) {
        requireHost()
        h.scrollVisible(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
        val receipts = recordTextLayout(tag)
        check(receipts.isNotEmpty()) { "actual text layout nodes are required" }
        for (receipt in receipts) {
            check(receipt.getterReturned && receipt.layouts.isNotEmpty()) { "actual text layout getter must return nonempty results" }
            check(receipt.textValues.isNotEmpty() && receipt.textValues.none(String::isEmpty)) { "actual semantics text is required" }
            check(
                receipt.layouts.all { layout ->
                    layout.layoutInput.text.text in receipt.textValues && textGeometryFits(layout, layout.layoutInput.text.text)
                },
            ) { "every actual layout must match displayed semantics and fit its measured text bounds" }
            check(receipt.textValues.all { expected -> receipt.layouts.any { it.layoutInput.text.text == expected } }) {
                "no displayed semantics text may be omitted from the geometry check"
            }
        }
        requireHost()
    }

    private fun recordTextLayout(tag: String): List<TextLayoutReceipt> {
        fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)
        val anchor = h.nodes(tag).single()
        val nodes = descendants(anchor).filter { it.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action != null }
        val receipts = mutableListOf<TextLayoutReceipt>()
        compose.runOnIdle {
            for (node in nodes) {
                val layouts = mutableListOf<TextLayoutResult>()
                val returned = checkNotNull(node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action).invoke(layouts)
                val values =
                    node.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .map { it.text }
                receipts += TextLayoutReceipt(values, returned, layouts)
            }
        }
        val rows = JSONArray()
        for ((receiptIndex, receipt) in receipts.withIndex()) {
            for (layout in receipt.layouts) {
                val input = layout.layoutInput
                val lines = JSONArray()
                for (line in 0 until layout.lineCount) {
                    lines.put(
                        JSONObject()
                            .put("line", line)
                            .put("left", layout.getLineLeft(line))
                            .put("right", layout.getLineRight(line))
                            .put("top", layout.getLineTop(line))
                            .put("bottom", layout.getLineBottom(line))
                            .put("baseline", layout.getLineBaseline(line))
                            .put("ellipsized", layout.isLineEllipsized(line))
                            .put("start_utf16", layout.getLineStart(line))
                            .put("end_utf16", layout.getLineEnd(line))
                            .put("visible_end_utf16", layout.getLineEnd(line, visibleEnd = true)),
                    )
                }
                val boxes = JSONArray()
                for (offset in input.text.text.indices) {
                    val box = layout.getBoundingBox(offset)
                    boxes.put(
                        JSONObject()
                            .put("utf16_offset", offset)
                            .put("left", box.left)
                            .put("right", box.right)
                            .put("top", box.top)
                            .put("bottom", box.bottom),
                    )
                }
                rows.put(
                    JSONObject()
                        .put("width_px", layout.size.width)
                        .put("height_px", layout.size.height)
                        .put("paragraph_width_px", layout.multiParagraph.width)
                        .put("paragraph_height_px", layout.multiParagraph.height)
                        .put("max_intrinsic_width_px", layout.multiParagraph.maxIntrinsicWidth)
                        .put("min_intrinsic_width_px", layout.multiParagraph.minIntrinsicWidth)
                        .put("did_exceed_max_lines", layout.multiParagraph.didExceedMaxLines)
                        .put("overflow_width", layout.didOverflowWidth)
                        .put("overflow_height", layout.didOverflowHeight)
                        .put("first_baseline_px", layout.firstBaseline)
                        .put("last_baseline_px", layout.lastBaseline)
                        .put("font_size_sp", input.style.fontSize.value)
                        .put("line_height_sp", input.style.lineHeight.value)
                        .put("density", input.density.density)
                        .put("font_scale", input.density.fontScale)
                        .put("soft_wrap", input.softWrap)
                        .put("max_lines", input.maxLines)
                        .put("overflow_mode", input.overflow.toString())
                        .put("constraint_min_width", input.constraints.minWidth)
                        .put("constraint_max_width", input.constraints.maxWidth)
                        .put("constraint_min_height", input.constraints.minHeight)
                        .put("constraint_max_height", input.constraints.maxHeight)
                        .put("text_length_utf16", input.text.length)
                        .put("lines", lines)
                        .put("character_boxes", boxes)
                        .put("node_receipt_index", receiptIndex)
                        .put("layout_text_matches_semantics", input.text.text in receipt.textValues)
                        .put("line_and_character_geometry_fits", textGeometryFits(layout, input.text.text)),
                )
            }
        }
        val bounds = anchor.boundsInWindow
        val saved = report.optJSONArray("textLayoutReceipts") ?: JSONArray().also { report.put("textLayoutReceipts", it) }
        saved.put(
            JSONObject()
                .put("tag", tag)
                .put("observed_at_elapsed_realtime_nanos", SystemClock.elapsedRealtimeNanos())
                .put("node_left", bounds.left)
                .put("node_top", bounds.top)
                .put("node_right", bounds.right)
                .put("node_bottom", bounds.bottom)
                .put("getters_returned", JSONArray(receipts.map { it.getterReturned }))
                .put("semantics_text_counts", JSONArray(receipts.map { it.textValues.size }))
                .put("layout_counts", JSONArray(receipts.map { it.layouts.size }))
                .put("pixel_rounding_tolerance_px", 1)
                .put("layouts", rows)
                .put("scope", "actual-semantics-line-character-geometry-viewport-checked-separately"),
        )
        persist()
        return receipts
    }

    /** Same full-coverage rule as frozen M1; paragraph-container width is not a glyph boundary. */
    private fun textGeometryFits(
        layout: TextLayoutResult,
        expected: String,
    ): Boolean {
        if (expected.isEmpty() || layout.layoutInput.text.text != expected || layout.size.width <= 0 || layout.size.height <= 0 ||
            layout.lineCount <= 0 || layout.multiParagraph.didExceedMaxLines
        ) {
            return false
        }

        // One physical pixel only covers integral measured-size versus float text-coordinate rounding.
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
            if (layout.getLineStart(line) != covered || end < covered || end > expected.length || layout.isLineEllipsized(line) ||
                !inside(layout.getLineLeft(line), layout.getLineTop(line), layout.getLineRight(line), layout.getLineBottom(line))
            ) {
                return false
            }
            covered = end
        }
        return covered == expected.length &&
            expected.indices.filterNot { expected[it].isWhitespace() }.all {
                val box = layout.getBoundingBox(it)
                inside(box.left, box.top, box.right, box.bottom)
            }
    }

    private data class TextLayoutReceipt(
        val textValues: List<String>,
        val getterReturned: Boolean,
        val layouts: List<TextLayoutResult>,
    )

    private suspend fun assertOriginals(
        before: SessionHistoryRecordingRunFlowTest.Harness.ExistingData,
        originalPhysical: JSONObject,
    ) {
        val after = h.snapshot(h.graph)
        check(after.preparation == expectedPreparation)
        val ids = before.sessions.map { it.sessionId }.toSet()
        check(after.sessions.filter { it.sessionId in ids } == before.sessions)
        val extra = after.sessions.filter { it.sessionId !in ids }
        check(extra.size == if (owned == null) 0 else 1)
        owned?.let { check(extra.single() == it) }
        for (id in ids) {
            check(after.observations.getValue(id) == before.observations.getValue(id))
            check(after.memos.getValue(id) == before.memos.getValue(id))
            check(after.recordingRuns.getValue(id) == before.recordingRuns.getValue(id))
        }
        owned?.let {
            check(after.memos.getValue(it.sessionId) == null && after.recordingRuns.getValue(it.sessionId).isEmpty())
            check(
                after.observations.getValue(it.sessionId).all { row ->
                    row.observation.source == VehicleObservationSource.SDK_STATE_OBSERVATION && row.observation.observedAtEpochMillis > 0
                },
            )
        }
        val current = physicalDatabase()
        val oldTables = originalPhysical.getJSONArray("tablesInNameOrder")
        val newTables = current.getJSONArray("tablesInNameOrder")
        check(oldTables.length() == newTables.length())
        for (i in 0 until oldTables.length()) {
            val old = oldTables.getJSONObject(i)
            val fresh = newTables.getJSONObject(i)
            check(old.getString("table") == fresh.getString("table"))
            h.requireSameJson(old.get("columns"), fresh.get("columns"), "typed-column-order")
            h.requireSameJson(old.get("createSql"), fresh.get("createSql"), "table-schema")
            val table = old.getString("table")
            val rows = old.getJSONArray("rowsInRowIdOrder")
            val now = fresh.getJSONArray("rowsInRowIdOrder")
            when (table) {
                "preparation" -> {
                    check(now.length() == rows.length() && rows.length() == 1)
                    val selection = checkNotNull(expectedPreparation)
                    val fields =
                        mapOf(
                            "goalId" to selection.goalId,
                            "courseId" to selection.courseId,
                            "courseVersion" to selection.courseVersion,
                            "briefingVersion" to selection.briefingVersion,
                            "taskId" to selection.taskId,
                        )
                    val expected = JSONArray(rows.toString())
                    val columns = old.getJSONArray("columns")
                    for (column in 0 until columns.length()) {
                        fields[columns.getString(column)]?.let { expected.getJSONArray(0).getJSONObject(column).put("value", it) }
                    }
                    h.requireSameJson(expected, now, "only-explicit-UI-preparation-fields")
                }
                "sqlite_sequence" -> {
                    check(now.length() == rows.length())
                    for (row in 0 until rows.length()) {
                        val previous = rows.getJSONArray(row)
                        val freshRow = now.getJSONArray(row)
                        val name = previous.getJSONObject(1).getString("value")
                        if (name == "practice_session" || name == "vehicle_observation") {
                            h.requireSameJson(previous.get(0), freshRow.get(0), "sequence-rowid")
                            h.requireSameJson(previous.get(1), freshRow.get(1), "sequence-name")
                            check(freshRow.getJSONObject(2).getString("type") == "INTEGER")
                            check(freshRow.getJSONObject(2).getLong("value") >= previous.getJSONObject(2).getLong("value"))
                        } else {
                            h.requireSameJson(previous, freshRow, "unrelated-original-sequence")
                        }
                    }
                }
                "practice_session", "vehicle_observation" -> {
                    check(now.length() >= rows.length())
                    for (row in 0 until rows.length()) h.requireSameJson(rows.get(row), now.get(row), "original-typed-row")
                }
                else -> h.requireSameJson(rows, now, "original-typed-table")
            }
        }
        report.put("physicalAfter", current).put("currentData", frozenDataJson(after, current))
        persist()
    }

    private suspend fun assertBaseline(
        data: SessionHistoryRecordingRunFlowTest.Harness.ExistingData,
        physical: JSONObject,
    ) {
        check(baselineFile.isFile && !File(baselineFile.path + ".bak").exists() && !File(baselineFile.path + ".new").exists())
        val baselineBytes = baselineFile.readBytes()
        baselineHash = sha256(baselineBytes)
        val baseline = JSONObject(baselineBytes.toString(Charsets.UTF_8))
        check(baseline.getString("status") == "completed" && baseline.getBoolean("preservedOriginalData"))
        check(
            baseline.getString("measurementSeries") == "history-memo-edit-read-v1" && baseline.getString("measurementStage") == "baseline",
        )
        check(baseline.getInt("plannedWarmups") == 5 && baseline.getInt("plannedSamples") == 30)
        for ((key, count) in listOf("warmups" to 5, "samples" to 30)) {
            val values = baseline.getJSONArray(key)
            check(values.length() == count)
            for (index in 0 until count) check(values.getJSONObject(index).getString("status") == "success")
        }
        h.requireSameJson(baseline.getJSONObject("originalData"), frozenDataJson(data, physical), "canonical-all-seven-fields-baseline")
        h.requireSameJson(baseline.getJSONObject("catalog"), h.catalogJson(h.graph), "catalog-baseline")
        report.put("frozenBaselineSha256", baselineHash).put("baselineKind", "completed-frozen-M1-canonical-input-only")
    }

    /** Match frozen M1's complete schema using actual repository receipts, never inferred nulls. */
    private suspend fun frozenDataJson(
        data: SessionHistoryRecordingRunFlowTest.Harness.ExistingData,
        physical: JSONObject,
    ): JSONObject {
        val completions = JSONArray()
        for (session in data.sessions) {
            val declaration =
                h.graph
                    .courseCompletions()
                    .observe(session.sessionId)
                    .first()
            completions.put(
                JSONObject().put("sessionId", session.sessionId).put(
                    "declaration",
                    declaration?.let {
                        JSONObject()
                            .put("source", h.sessionJson(it.source))
                            .put("version", it.version)
                            .put("declaredAtEpochMillis", it.declaredAtEpochMillis)
                            .put("origin", it.origin.name)
                    } ?: JSONObject.NULL,
                ),
            )
        }
        return h.dataJson(data).put("courseCompletions", completions).put("physicalDatabase", physical)
    }

    private fun physicalDatabase(): JSONObject =
        SQLiteDatabase
            .openDatabase(
                h.instrument.targetContext
                    .getDatabasePath("hangil.db")
                    .path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                check(db.version == 6)
                val tables = JSONArray()
                db.rawQuery("SELECT name, sql FROM sqlite_master WHERE type='table' ORDER BY name", null).use { names ->
                    while (names.moveToNext()) {
                        val name = names.getString(0)
                        val quoted = "\"" + name.replace("\"", "\"\"") + "\""
                        val rows = JSONArray()
                        val columns = JSONArray()
                        db.rawQuery("SELECT rowid AS __measurement_rowid__, * FROM $quoted ORDER BY rowid", null).use { cursor ->
                            cursor.columnNames.forEach { columns.put(it) }
                            while (cursor.moveToNext()) {
                                val row = JSONArray()
                                for (column in 0 until cursor.columnCount) {
                                    val value =
                                        when (cursor.getType(column)) {
                                            Cursor.FIELD_TYPE_NULL -> JSONObject().put("type", "NULL").put("value", JSONObject.NULL)
                                            Cursor.FIELD_TYPE_INTEGER ->
                                                JSONObject()
                                                    .put(
                                                        "type",
                                                        "INTEGER",
                                                    ).put("value", cursor.getLong(column))
                                            Cursor.FIELD_TYPE_FLOAT ->
                                                JSONObject()
                                                    .put(
                                                        "type",
                                                        "FLOAT",
                                                    ).put("value", cursor.getDouble(column))
                                            Cursor.FIELD_TYPE_STRING ->
                                                JSONObject()
                                                    .put(
                                                        "type",
                                                        "STRING",
                                                    ).put("value", cursor.getString(column))
                                            Cursor.FIELD_TYPE_BLOB ->
                                                JSONObject().put("type", "BLOB_HEX").put(
                                                    "value",
                                                    cursor
                                                        .getBlob(
                                                            column,
                                                        ).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) },
                                                )
                                            else -> error("unknown SQLite field type")
                                        }
                                    row.put(value)
                                }
                                rows.put(row)
                            }
                        }
                        tables.put(
                            JSONObject()
                                .put("table", name)
                                .put("columns", columns)
                                .put("rowsInRowIdOrder", rows)
                                .put("createSql", if (names.isNull(1)) JSONObject.NULL else names.getString(1)),
                        )
                    }
                }
                db
                    .rawQuery(
                        "SELECT COUNT(*) FROM course_completion_declaration",
                        null,
                    ).use { check(it.moveToFirst() && it.getLong(0) == 0L) }
                db.rawQuery("PRAGMA foreign_key_check", null).use { check(it.count == 0) }
                JSONObject().put("schemaVersion", db.version).put("tablesInNameOrder", tables).put("foreignKeyViolations", 0)
            }

    private fun capture(
        stage: String,
        diagnostic: Boolean,
    ) {
        var first: Throwable? = null

        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                if (first == null) {
                    first = failure
                } else if (first !== failure) {
                    first!!.addSuppressed(failure)
                }
            }
        }
        val proof =
            JSONObject()
                .put("scope", if (diagnostic) "failure-diagnostic-unverified" else "actual-Main-SDK-confirmed")
                .put("captureDefaultDisplayOnly", true)
                .put("pngSaved", false)
        var display: Int? = null
        attempt {
            h.instrument.runOnMainSync {
                val host = compose.activity
                display = host.display?.displayId
                proof.put("mainLifecycle", host.lifecycle.currentState.name).put("windowFocus", host.hasWindowFocus())
            }
            proof.put("display", display ?: JSONObject.NULL).put("actualFocusedPackage", h.actualPackage() ?: JSONObject.NULL)
        }
        attempt {
            if (!diagnostic) requireHost()
            check(display == Display.DEFAULT_DISPLAY) { "default screenshot cannot prove a different display" }
            val image = checkNotNull(h.instrument.uiAutomation.takeScreenshot())
            try {
                File(directory, "$stage.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                image.recycle()
            }
            proof.put("pngSaved", true)
        }
        attempt { File(directory, "$stage.json").writeText(proof.toString(2)) }
        first?.let { throw it }
    }

    private fun persist() {
        val file = AtomicFile(File(directory, "owner-and-result.json"))
        val stream = file.startWrite()
        try {
            stream.write(report.toString(2).toByteArray())
            file.finishWrite(stream)
        } catch (
            failure: Throwable,
        ) {
            file.failWrite(stream)
            throw failure
        }
    }

    private fun sha256(file: File): String = sha256(file.readBytes())

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun phase(value: String) = h.instrument.sendStatus(2, Bundle().apply { putString("stream", value + System.lineSeparator()) })
}
