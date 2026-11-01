package io.github.hangillab.hangil

import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.ViewTreeObserver
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.repeatOnLifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.database.OwnedPracticeTestStore
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.ForegroundLocationRecordingController
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.LocationRecordingRunStatus
import io.github.hangillab.hangil.core.domain.LocationRecordingSnapshot
import io.github.hangillab.hangil.core.domain.LocationRecordingState
import io.github.hangillab.hangil.core.domain.LocationRecordingStopReason
import io.github.hangillab.hangil.core.domain.LocationUpdateGateway
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.vehicle.FusedLocationPermission
import io.github.hangillab.hangil.core.vehicle.PleosLocationUpdateGateway
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
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
import java.time.Clock
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Actual Hilt location/vehicle/UX gateways, production controller and UUID-owned actual Room.
 * Main's real lifecycle owns the isolated controller; Main UI and production Room are read-only.
 * No permission request/grant/revoke, DDS command, provider injection, screenshot or raw-location
 * output exists here. A separate runner owns fresh consent, the two-window budget and restoration.
 * These instrumented foreground observations do not prove currentness, actual roads, continuity,
 * remote provider stop acknowledgement or uninstrumented driving support.
 */
@RunWith(AndroidJUnit4::class)
class ForegroundLocationRecordingNativeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val h by lazy { SessionHistoryRecordingRunFlowTest.Harness(compose) }
    private val actualMain by lazy { compose.activity }
    private val unknownFocusSamples = AtomicInteger(0)
    private val directory by lazy {
        File(h.instrument.targetContext.cacheDir, "foreground-location-native/${UUID.randomUUID()}").apply { check(mkdirs()) }
    }

    @Test fun actualDeniedPermissionCreatesNoRunAndNeverCollectsTheUpdateGateway() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("manualForegroundLocationRecordingNative") == "true")
        runCase(approved = false, driving = false, validMode = true)
    }

    /** This argument acknowledges a NEW external approval and actual grant; it grants nothing. */
    @Test fun newlyApprovedTwoBoundedWindowsStoreSdkSamplesInOwnedRoomAndNeverRestartAutomatically() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("manualForegroundLocationRecordingNative") == "true")
        assumeTrue(args.getString("manualPermissionApproved") == "true")
        val mode = args.getString("foregroundLocationSecondWindowMode")
        runCase(approved = true, driving = mode == "driving", validMode = mode == "parked" || mode == "driving")
    }

    private fun runCase(
        approved: Boolean,
        driving: Boolean,
        validMode: Boolean,
    ) = runBlocking {
        val report =
            JSONObject()
                .put("schema", 1)
                .put("status", "started")
                .put("source", "actual-Hilt-SDK-production-controller-UUID-owned-Room")
                .put("productionWritesByHarness", false)
                .put("permissionChangedByHarness", false)
                .put("sdkLocationInjected", false)
                .put("ddsChangedByHarness", false)
                .put("coordinatesOrRawSdkValuesExportedByHarness", false)
                .put("screenshotsTakenByHarness", false)
                .put("maximumWindows", if (approved) 2 else 0)
                .put("maximumWindowMillis", WINDOW_MILLIS)
                .put("positiveApprovalArgument", approved)
                .put("drivingRequested", driving)
                .put("drivingVerified", false)
                .put("activeHostStopVerified", false)
                .put("localCleanupIsRemoteStopAck", false)
                .put("performanceComparison", false)
                .put("hostStopBasis", "real-Main-ActivityScenario-CREATED-and-actual-lifecycle-not-Navi-foreground-proof")
                .put("externalFullRuntimeFlagsAndEnabledInputMethodsReadbackRequired", true)
                .put("externalPermissionRevokeAndOriginalRestorationRequired", approved)
                .put("windows", JSONArray())
                .put("failures", JSONArray())
        var primary: Throwable? = null
        var original: Original? = null
        var fixture: Fixture? = null
        var ownedStore: OwnedPracticeTestStore? = null
        var fixtureScope: Job? = null
        var startHost: StartHost? = null
        var controllerJoined = false
        var storeClosed = false
        var productionPreserved = false
        var productionDomainPreserved = false
        var platformPreserved = false
        var automationReturned = false
        var stage = "initial-mode"

        fun preserve(
            stage: String,
            failure: Throwable,
        ) {
            val first = primary
            val site = failure.stackTrace.firstOrNull { it.className.startsWith("io.github.hangillab.hangil") }
            report.getJSONArray("failures").put(
                JSONObject()
                    .put("stage", stage)
                    .put("class", failure.javaClass.name)
                    .put("primary", first == null)
                    .put("sourceFile", site?.fileName ?: JSONObject.NULL)
                    .put("sourceLine", site?.lineNumber ?: JSONObject.NULL),
            )
            if (first == null) {
                primary = failure
            } else if (first !== failure) {
                first.addSuppressed(failure)
            }
        }

        suspend fun attempt(
            stage: String,
            action: suspend () -> Unit,
        ) {
            try {
                action()
            } catch (failure: Throwable) {
                preserve(stage, failure)
            }
        }
        try {
            check(validMode) { "The second-window mode must be explicit" }
            stage = "initial-environment"
            h.assertEnvironment()
            stage = "initial-permission"
            check(h.graph.locationUpdates() is PleosLocationUpdateGateway) { "The real Hilt SDK adapter is required" }
            check(frameworkPermission() == approved) { "Actual framework permission differs from the selected scenario" }
            check(h.graph.locationUpdates().hasPermission() == approved) { "The actual Hilt permission read must agree" }
            stage = "initial-live-P-UX"
            h.awaitAllowed()
            requireActualForeground()
            stage = "production-before"
            original = readOriginal()
            write("original-private-before.json", checkNotNull(original).json)
            startHost = StartHost().also { it.attach() }
            stage = "owned-store-and-fixture"
            val store = withContext(Dispatchers.IO) { OwnedPracticeTestStore.create(h.instrument.targetContext) }.also { ownedStore = it }
            val job = SupervisorJob().also { fixtureScope = it }
            val f = Fixture(store, job).also { fixture = it }
            f.seed(checkNotNull(startHost).receipt())
            f.attach()
            f.awaitSource()
            if (!approved) {
                stage = "denied-single-start"
                val accepted = f.controller.start(f.source, checkNotNull(startHost).receipt())
                check(!accepted) { "Denied permission must not accept a recording start" }
                val failed = f.controller.state.value as? LocationRecordingState.Failed
                check(failed?.reason == LocationRecordingState.Reason.PERMISSION_DENIED) { "Denial must be the actual rejection reason" }
                val stored = f.snapshot()
                check(stored.runs.isEmpty() && stored.events.isEmpty()) { "Denied start must create no owned location run or event" }
                check(f.gateway.collections.get() == 0 && f.generated.get() == 0) { "Denied start must never enter the SDK update flow" }
                report
                    .put("acceptedStarts", 0)
                    .put("ownedRuns", 0)
                    .put("updateFlowCollections", 0)
                    .put("localRegisterCallbacks", 0)
                    .put("sdkStartupRequestedByThisFixture", false)
                phase("FOREGROUND_LOCATION_DENIED_COMPLETED")
            } else {
                repeat(2) { index ->
                    stage = "window-${index + 1}-parked-start"
                    h.awaitAllowed()
                    requireActualForeground()
                    val window = f.newWindow()
                    val row = JSONObject().put("ordinal", index + 1).put("status", "started")
                    report.getJSONArray("windows").put(row)
                    check(f.controller.start(f.source, checkNotNull(startHost).receipt())) { "Fresh parked start must be accepted" }
                    stage = "window-${index + 1}-local-registration"
                    val began = withTimeout(WINDOW_MILLIS) { window.began.await() }
                    await(5_000) {
                        val state = f.controller.state.value
                        check(state !is LocationRecordingState.Failed) { "SDK setup or registration failed" }
                        state is LocationRecordingState.Recording && state.run.runId == window.runId
                    }
                    check(window.registered.get() == 1) { "Recording requires exactly one actual local registration callback" }
                    phase(
                        if (index == 1 &&
                            driving
                        ) {
                            "FOREGROUND_LOCATION_WINDOW_2_READY_FOR_D"
                        } else {
                            "FOREGROUND_LOCATION_PARKED_WINDOW_${index + 1}"
                        },
                    )
                    stage = "window-${index + 1}-foreground-observation"
                    val observeUntil = began + if (index == 1) HOST_STOP_AT_MILLIS else WINDOW_MILLIS
                    while (SystemClock.elapsedRealtime() < observeUntil) {
                        check(frameworkPermission()) { "The approved permission disappeared during the bounded window" }
                        requireActualForeground()
                        val isDrive = actualDrive()
                        if (index == 1 && driving && isDrive) {
                            window.firstDriveElapsed.compareAndSet(-1, SystemClock.elapsedRealtime())
                            check(!h.liveAllowed()) { "Actual D must deny detailed interaction" }
                            val locked = compose.onAllNodesWithTag("home_locked_title", useUnmergedTree = true).fetchSemanticsNodes(false)
                            if (locked.isNotEmpty()) {
                                window.lockComposed.set(true)
                                val visible =
                                    try {
                                        compose.onNodeWithTag("home_locked_title", useUnmergedTree = true).assertIsDisplayed()
                                        true
                                    } catch (_: AssertionError) {
                                        false
                                    }
                                if (visible) {
                                    requireActualForeground()
                                    if (actualDrive() && !h.liveAllowed()) window.detailLocked.set(true)
                                }
                            }
                            window.driveObserved.set(true)
                        } else {
                            check(h.liveAllowed()) { "Both windows start parked; unrequested driving is not a normal result" }
                        }
                        if (SystemClock.elapsedRealtime() >= observeUntil) break
                        check(
                            f.controller.state.value is LocationRecordingState.Recording,
                        ) { "A terminal SDK outcome is not positive sampling success" }
                        delay(100)
                    }
                    stage = "window-${index + 1}-exact-stop"
                    if (index == 0) {
                        f.controller.stop(window.runId) // The independent cap may already have requested this exact USER_STOP.
                        awaitClosed(f, window.runId, LocationRecordingStopReason.USER_STOP)
                    } else {
                        phase("FOREGROUND_LOCATION_REQUEST_ACTUAL_MAIN_STOP")
                        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                        check(mainState().isAtLeast(Lifecycle.State.STARTED).not()) { "Actual Main STOP must be observed" }
                        awaitClosed(f, window.runId, LocationRecordingStopReason.HOST_STOPPED)
                        report.put("activeHostStopVerified", true)
                    }
                    window.cap?.cancelAndJoin()
                    stage = "window-${index + 1}-typed-Room-compare"
                    val stored = f.snapshot()
                    assertStoredWindow(f, window, stored, index == 1 && driving, row)
                    val delivered = window.readings.size
                    delay(750)
                    check(
                        f.snapshot() == stored && window.readings.size == delivered,
                    ) { "No delivered event or DB row may appear after local cleanup" }
                    row
                        .put("lateDeliveredEvents", 0)
                        .put("lateStoredEvents", 0)
                        .put("lateObservationMillis", 750)
                        .put("status", "passed")
                    if (index == 1) {
                        stage = "window-2-Main-return-no-auto-resume"
                        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                        h.assertEnvironment()
                        phase("FOREGROUND_LOCATION_AWAIT_P_RESTORE")
                        h.awaitAllowed()
                        f.awaitSource()
                        delay(750)
                        check(f.snapshot() == stored && f.gateway.collections.get() == 2 && f.generated.get() == 2) {
                            "Returning Main or P must not start another stream or run"
                        }
                        report.put("returnDidNotRestartDuringObservation", true).put("noAutoResumeObservationMillis", 750)
                    }
                }
                report
                    .put("drivingVerified", driving)
                    .put("acceptedStarts", 2)
                    .put("updateFlowCollections", f.gateway.collections.get())
                    .put("ownedRuns", f.snapshot().runs.size)
                    .put("drivingScope", if (driving) "actual-D-SDK-sample-and-real-home-lock" else "unexecuted-two-parked-windows-only")
                phase("FOREGROUND_LOCATION_TWO_WINDOWS_COMPLETED")
            }
        } catch (failure: Throwable) {
            preserve(stage, failure)
        } finally {
            withContext(NonCancellable) {
                attempt("stop-and-join-owned-controller") {
                    val current = fixture
                    if (current != null) {
                        current.closeController()
                    } else {
                        withTimeout(15_000) { fixtureScope?.cancelAndJoin() }
                    }
                    controllerJoined = ownedStore != null
                }
                attempt("window-metadata-on-success-or-failure") {
                    fixture?.writeWindowMetadata(report.getJSONArray("windows"))
                }
                attempt("owned-terminal-readback") {
                    fixture?.let { current ->
                        check(controllerJoined) { "Owned collectors must finish before terminal inspection" }
                        check(
                            current.snapshot().runs.none { it.status == LocationRecordingRunStatus.OPEN },
                        ) { "Owned location runs must be terminal" }
                        check(current.store.foreignKeyViolationCount() == 0) { "Owned foreign keys must remain valid" }
                    }
                }
                attempt("restore-real-Main") {
                    compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
                    h.assertEnvironment()
                    if (driving) {
                        phase("FOREGROUND_LOCATION_AWAIT_P_RESTORE")
                        h.awaitAllowed()
                    }
                }
                attempt("detach-start-host") { startHost?.close() }
                attempt("close-only-owned-store") {
                    ownedStore?.let {
                        check(controllerJoined) { "Never delete an owned store while a controller may still write" }
                        withContext(Dispatchers.IO) { it.close() }
                        storeClosed = true
                    }
                }
                attempt("original-production-domain") {
                    val before = checkNotNull(original) { "No original snapshot was acquired" }
                    h.assertPreserved(h.graph, before.data)
                    check(locationSnapshots(before.data.sessions) == before.location) { "Original production location rows changed" }
                    productionDomainPreserved = true
                }
                attempt("original-production-typed-SQL") {
                    val before = checkNotNull(original)
                    check(physicalDatabase() == before.physical) { "Original table values, types, order or sequences changed" }
                    check(h.catalogJson(h.graph).toString() == before.catalog) { "Original catalog changed" }
                    productionPreserved = true
                }
                attempt("original-platform") {
                    val before = checkNotNull(original)
                    check(platformJson().toString() == before.platform) { "App-readable original platform values changed" }
                    check(frameworkPermission() == approved) { "The harness must not change permission" }
                    platformPreserved = true
                }
                attempt("original-private-after") { write("original-private-after.json", readOriginal().json) }
                attempt("restore-automation-bit") {
                    h.restoreAutomationFlag()
                    automationReturned = true
                }
                report
                    .put("ownedControllerJoined", controllerJoined)
                    .put("ownedStoreClosed", storeClosed)
                    .put("productionDomainPreserved", productionDomainPreserved)
                    .put("productionTypedPreserved", productionPreserved)
                    .put("appReadablePlatformPreserved", platformPreserved)
                    .put("automationRestoreReturned", automationReturned)
                    .put("unknownFocusedWindowSamples", unknownFocusSamples.get())
                    .put("focusBasis", "single-focused-window-on-actual-Main-display-no-expected-value-substitution")
                    .put("status", if (primary == null) "passed" else "failed")
                attempt("save-sanitized-report") { write("report.json", report) }
            }
        }
        // Original SDK errors can contain opaque messages. Only class/stage metadata leaves memory.
        if (primary !=
            null
        ) {
            throw AssertionError("Foreground location native verification failed; private stage/class metadata was preserved")
        }
    }

    private suspend fun assertStoredWindow(
        f: Fixture,
        window: Window,
        stored: LocationRecordingSnapshot,
        driving: Boolean,
        row: JSONObject,
    ) {
        val run = checkNotNull(stored.runs.singleOrNull { it.runId == window.runId })
        check(run.source == f.source && run.status == LocationRecordingRunStatus.STOPPED) { "Only the exact owned run may stop" }
        check(
            run.endedAtEpochMillis != null && run.endedAtElapsedRealtimeMillis != null,
        ) { "An observed stop has known local marker times" }
        val events = stored.events.filter { it.runId == window.runId }
        val observed = window.readings.toList()
        check(events.isNotEmpty() && events.map { it.reading } == observed.take(events.size).map { it.reading }) {
            "Typed DB readings must match an ordered prefix of actual gateway deliveries in memory"
        }
        check(
            events.indices.all {
                events[it].ordinal == it + 1 && events[it].sessionId == f.source.sessionId
            },
        ) { "Owned event identities must match" }
        check(events.any { it.reading is LocationUpdateReading.Sample }) { "At least one actual SDK sample must reach Room" }
        check(window.cleanupClasses.isEmpty()) { "A cleanup failure is not positive native success" }
        if (driving) {
            check(
                window.driveObserved.get() && window.detailLocked.get(),
            ) { "Actual D and the real detailed-UI lock must both be observed" }
            val confirmedD = window.firstDriveElapsed.get()
            check(
                confirmedD >= 0 &&
                    events.indices.any {
                        observed[it].drive && events[it].reading is LocationUpdateReading.Sample &&
                            events[it].reading.receivedAtElapsedRealtimeMillis >= confirmedD
                    },
            ) {
                "An actual SDK sample received while D must be present in Room"
            }
        }
        row
            .put("ownerMatches", true)
            .put("typedGatewayPrefixMatches", true)
            .put("eventCount", events.size)
            .put("sampleCount", events.count { it.reading is LocationUpdateReading.Sample })
            .put("emptyCount", events.count { it.reading is LocationUpdateReading.Empty })
            .put("statusMarker", run.status.name)
            .put("stopReason", run.stopReason?.name ?: JSONObject.NULL)
            .put("localRegisterCallbacks", window.registered.get())
            .put("cleanupFailureClasses", JSONArray(window.cleanupClasses.toList()))
            .put("windowMillisToLocalStopMarker", checkNotNull(run.endedAtElapsedRealtimeMillis) - window.began.await())
            .put("windowCapRequestedUserStop", window.capRequested.get())
            .put("DObserved", window.driveObserved.get())
            .put("homeLockNodeComposed", window.lockComposed.get())
            .put("realHomeLockObserved", window.detailLocked.get())
            .put("homeLockVisibilityBasis", "assertIsDisplayed-and-same-actual-Main-display-focused-RESUMED")
            .put("remoteStopAcknowledged", false)
            .put("deliveryTailNotAssertedCommitted", observed.size - events.size)
            .put(
                "sampleReceiptsInsideCap",
                events.all {
                    it.reading.receivedAtElapsedRealtimeMillis - window.startedElapsed.get() in 0L until WINDOW_MILLIS
                },
            )
        check(events.all { it.reading.receivedAtElapsedRealtimeMillis - window.startedElapsed.get() in 0L until WINDOW_MILLIS }) {
            "Every stored SDK receipt must be inside this window's cap"
        }
    }

    private suspend fun awaitClosed(
        fixture: Fixture,
        runId: String,
        reason: LocationRecordingStopReason,
    ) {
        await(8_000) {
            val state = fixture.controller.state.value
            check(state !is LocationRecordingState.Failed) { "Failed or unknown closure is not a successful stop" }
            state is LocationRecordingState.Stopped && state.run.runId == runId && state.reason == reason
        }
    }

    private inner class Fixture(
        val store: OwnedPracticeTestStore,
        private val scopeJob: Job,
    ) {
        private val scope = CoroutineScope(scopeJob + Dispatchers.Default)
        private val nextId = AtomicReference<String?>(null)
        val generated = AtomicInteger(0)
        val observedSessions = DeliveredSessions(store.sessions)
        val gateway = ObservedGateway(h.graph.locationUpdates())
        val controller =
            ForegroundLocationRecordingController(
                gateway,
                observedSessions,
                store.locationRecordings,
                h.graph.vehicle(),
                h.graph.restrictions(),
                Clock.systemUTC(),
                SystemClock::elapsedRealtime,
                scope,
                {
                    generated.incrementAndGet()
                    checkNotNull(nextId.getAndSet(null))
                },
                UUID.randomUUID().toString(),
            )
        lateinit var source: PracticeSession
        private var host: Job? = null
        private val windows = mutableListOf<Window>()

        suspend fun seed(allowed: () -> Boolean) {
            check(
                store.sessions
                    .observeSessions()
                    .first()
                    .isEmpty(),
            )
            val course = store.catalog.courses.first()
            val goal = store.catalog.goals.first { it.id in course.goalIds }
            val task = course.tasks.first()
            val selection = PracticeSelection(goal.id, course.id, course.version, course.briefingVersion, task.id)
            check(store.preparation.saveSelection(selection, allowed))
            val practice = (store.catalog.resolve(selection) as Preparation.Ready).practice
            val result = store.sessions.start(practice, System.currentTimeMillis(), allowed)
            check(result is SessionStartResult.Started) { "Only the new owned fixture session may be created" }
            source = result.session
        }

        fun attach() {
            host =
                scope.launch(Dispatchers.Main.immediate) {
                    actualMain.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { controller.recordWhileStarted() }
                }
        }

        suspend fun awaitSource() {
            await(10_000) { observedSessions.delivered.get()?.singleOrNull() == source }
        }

        fun writeWindowMetadata(rows: JSONArray) {
            windows.forEachIndexed { index, window ->
                if (index < rows.length()) {
                    val readings = window.readings.toList()
                    val started = window.startedElapsed.get()
                    val ended = window.closedElapsed.get()
                    rows
                        .getJSONObject(index)
                        .put("gatewayDeliveredCount", readings.size)
                        .put("sampleDeliveredCount", readings.count { it.reading is LocationUpdateReading.Sample })
                        .put("emptyDeliveredCount", readings.count { it.reading is LocationUpdateReading.Empty })
                        .put("terminalKinds", JSONArray(readings.map { kind(it.reading) }.filter { it != "SAMPLE" && it != "EMPTY" }))
                        .put(
                            "terminalDeliveredCount",
                            readings.count {
                                it.reading !is LocationUpdateReading.Sample && it.reading !is LocationUpdateReading.Empty
                            },
                        ).put("homeLockNodeComposed", window.lockComposed.get())
                        .put("realHomeLockObserved", window.detailLocked.get())
                        .put("localRegisterCallbacks", window.registered.get())
                        .put("cleanupFailureClasses", JSONArray(window.cleanupClasses.toList()))
                        .put("collectionDurationMillis", if (started >= 0 && ended >= started) ended - started else JSONObject.NULL)
                        .put("localCleanupMayExtendBeyondCallbackCap", true)
                        .put("capRequestedUserStop", window.capRequested.get())
                }
            }
        }

        fun newWindow(): Window {
            check(windows.size < 2)
            val window = Window(UUID.randomUUID().toString())
            windows += window
            nextId.set(window.runId)
            gateway.current.set(window)
            window.expire = { controller.stop(window.runId) }
            window.cap =
                scope.launch {
                    val start = window.began.await()
                    delay((start + WINDOW_MILLIS - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                    window.capRequested.set(true)
                    controller.stop(window.runId)
                }
            return window
        }

        suspend fun snapshot(): LocationRecordingSnapshot =
            withTimeout(5_000) { store.locationRecordings.observe(source.sessionId).first() }

        suspend fun closeController() {
            gateway.current
                .get()
                ?.expire
                ?.invoke()
            withTimeout(15_000) {
                host?.cancelAndJoin()
                windows.forEach { it.cap?.cancelAndJoin() }
                scopeJob.cancelAndJoin()
            }
        }
    }

    private inner class ObservedGateway(
        private val actual: LocationUpdateGateway,
    ) : LocationUpdateGateway {
        val current = AtomicReference<Window?>(null)
        val collections = AtomicInteger(0)

        override fun hasPermission(): Boolean = actual.hasPermission()

        override fun updates(
            canContinue: () -> Boolean,
            onRegistered: () -> Unit,
            onCleanupFailure: (Throwable) -> Unit,
        ): Flow<LocationUpdateReading> =
            flow {
                val window = checkNotNull(current.get())
                collections.incrementAndGet()
                val started = SystemClock.elapsedRealtime()
                window.startedElapsed.set(started)
                check(window.began.complete(started)) { "Each planned window owns one actual update collection" }
                try {
                    emitAll(
                        actual
                            .updates(
                                {
                                    if (SystemClock.elapsedRealtime() - started >= WINDOW_MILLIS) {
                                        window.capRequested.set(true)
                                        window.expire?.invoke()
                                        false
                                    } else {
                                        canContinue()
                                    }
                                },
                                {
                                    window.registered.incrementAndGet()
                                    onRegistered()
                                },
                                { failure ->
                                    window.cleanupClasses.add(failure.javaClass.name)
                                    onCleanupFailure(failure)
                                },
                            ).onEach { reading -> window.readings.add(Observed(reading, actualDrive())) },
                    )
                } finally {
                    window.closedElapsed.set(SystemClock.elapsedRealtime())
                }
            }
    }

    private class DeliveredSessions(
        private val actual: PracticeSessionRepository,
    ) : PracticeSessionRepository {
        val delivered = AtomicReference<List<PracticeSession>?>(null)

        override fun observeSessions(): Flow<List<PracticeSession>> =
            flow {
                try {
                    actual.observeSessions().collect { rows ->
                        emit(rows)
                        delivered.set(rows.toList()) // after the actual controller's collector has processed the real Room emission
                    }
                } finally {
                    delivered.set(null)
                }
            }

        override suspend fun start(
            practice: PreparedPractice,
            startedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionStartResult = error("Controller only observes owned fixture sessions")

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult = error("Controller cannot finish the fixture practice")
    }

    private class Window(
        val runId: String,
    ) {
        val began = CompletableDeferred<Long>()
        val startedElapsed = AtomicLong(-1)
        val closedElapsed = AtomicLong(-1)
        val firstDriveElapsed = AtomicLong(-1)
        val registered = AtomicInteger(0)
        val readings = ConcurrentLinkedQueue<Observed>()
        val cleanupClasses = ConcurrentLinkedQueue<String>()
        val capRequested = AtomicBoolean(false)
        val driveObserved = AtomicBoolean(false)
        val detailLocked = AtomicBoolean(false)
        val lockComposed = AtomicBoolean(false)
        var expire: (() -> Unit)? = null
        var cap: Job? = null
    }

    private data class Observed(
        val reading: LocationUpdateReading,
        val drive: Boolean,
    )

    private inner class StartHost : AutoCloseable {
        private val current = AtomicReference(AtomicBoolean(false))
        private val resumedFocused = AtomicBoolean(false)
        private var displayId: Int? = null
        private val observer = LifecycleEventObserver { _, _ -> refresh() }
        private val focus = ViewTreeObserver.OnWindowFocusChangeListener { refresh() }

        fun attach() {
            h.instrument.runOnMainSync {
                check(compose.activity === actualMain)
                val display = checkNotNull(actualMain.display)
                check(display.isValid)
                displayId = display.displayId
                actualMain.lifecycle.addObserver(observer)
                actualMain.window.decorView.viewTreeObserver
                    .addOnWindowFocusChangeListener(focus)
                refresh()
            }
        }

        private fun refresh() {
            val display = actualMain.display
            val allowed =
                actualMain.lifecycle.currentState == Lifecycle.State.RESUMED && actualMain.hasWindowFocus() &&
                    display != null && display.isValid && display.displayId == displayId
            resumedFocused.set(allowed)
            if (!allowed) current.get().set(false)
        }

        fun receipt(): () -> Boolean {
            h.requireForeground()
            h.instrument.runOnMainSync { refresh() }
            check(resumedFocused.get() && h.liveAllowed())
            val owner = AtomicBoolean(true)
            current.getAndSet(owner).set(false)
            return { owner.get() && resumedFocused.get() && h.liveAllowed() }
        }

        override fun close() {
            current.get().set(false)
            resumedFocused.set(false)
            h.instrument.runOnMainSync {
                actualMain.lifecycle.removeObserver(observer)
                actualMain.window.decorView.viewTreeObserver
                    .removeOnWindowFocusChangeListener(focus)
            }
        }
    }

    private data class Original(
        val data: SessionHistoryRecordingRunFlowTest.Harness.ExistingData,
        val location: List<LocationRecordingSnapshot>,
        val physical: String,
        val platform: String,
        val catalog: String,
        val json: JSONObject,
    )

    private suspend fun readOriginal(): Original {
        val data = h.snapshot(h.graph)
        check(data.sessions.none { it.isActive }) { "Production must have no active practice" }
        val locations = locationSnapshots(data.sessions)
        check(
            locations.all {
                it.runs.isEmpty() && it.events.isEmpty()
            },
        ) { "This candidate requires the original empty production location tables" }
        val physical = physicalDatabase()
        val platform = platformJson().toString()
        val catalog = h.catalogJson(h.graph).toString()
        return Original(
            data,
            locations,
            physical,
            platform,
            catalog,
            JSONObject()
                .put("data", h.dataJson(data))
                .put("typedPhysical", JSONObject(physical))
                .put("platform", JSONObject(platform))
                .put("catalog", JSONObject(catalog)),
        )
    }

    private suspend fun locationSnapshots(sessions: List<PracticeSession>): List<LocationRecordingSnapshot> =
        withTimeout(15_000) {
            sessions.map {
                h.graph
                    .locationRecordings()
                    .observe(it.sessionId)
                    .first()
            }
        }

    private fun physicalDatabase(): String {
        val file = h.instrument.targetContext.getDatabasePath("hangil.db")
        check(file.isFile) { "The existing production database is required" }
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            check(sqlite.version == 7) { "The native candidate requires schema7" }
            for (table in listOf("location_recording_run", "location_recording_event")) {
                sqlite.rawQuery("SELECT COUNT(*) FROM $table", null).use { check(it.moveToFirst() && it.getLong(0) == 0L) }
            }
            val tables = JSONArray()
            sqlite.rawQuery("SELECT name, sql FROM sqlite_master WHERE type='table' ORDER BY name", null).use { names ->
                while (names.moveToNext()) {
                    val name = names.getString(0)
                    val quoted = "\"" + name.replace("\"", "\"\"") + "\""
                    val rows = JSONArray()
                    val columns = JSONArray()
                    sqlite.rawQuery("SELECT rowid AS __native_rowid__, * FROM $quoted ORDER BY rowid", null).use { cursor ->
                        cursor.columnNames.forEach { columns.put(it) }
                        while (cursor.moveToNext()) {
                            val row = JSONArray()
                            for (index in 0 until cursor.columnCount) {
                                val type = cursor.getType(index)
                                val value: Any =
                                    when (type) {
                                        Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                                        Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(index)
                                        Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(index)
                                        Cursor.FIELD_TYPE_STRING -> cursor.getString(index)
                                        Cursor.FIELD_TYPE_BLOB ->
                                            cursor.getBlob(index).joinToString("") {
                                                "%02x".format(
                                                    Locale.ROOT,
                                                    it.toInt() and 255,
                                                )
                                            }
                                        else -> error("Unknown SQLite physical type")
                                    }
                                row.put(JSONObject().put("type", type).put("value", value))
                            }
                            rows.put(row)
                        }
                    }
                    tables.put(
                        JSONObject()
                            .put("table", name)
                            .put("columns", columns)
                            .put("rows", rows)
                            .put("sql", if (names.isNull(1)) JSONObject.NULL else names.getString(1)),
                    )
                }
            }
            sqlite.rawQuery("PRAGMA foreign_key_check", null).use { check(it.count == 0) }
            JSONObject().put("schema", 7).put("tables", tables).toString()
        }
    }

    private fun platformJson(): JSONObject {
        val context = h.instrument.targetContext
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val names = checkNotNull(info.requestedPermissions)
        val flags = checkNotNull(info.requestedPermissionsFlags)
        check(names.size == flags.size)
        val permissions = JSONArray()
        names.indices.forEach { index ->
            permissions.put(
                JSONObject()
                    .put("name", names[index])
                    .put("requestedFlags", flags[index])
                    .put("granted", context.checkSelfPermission(names[index]) == PackageManager.PERMISSION_GRANTED),
            )
        }
        val resolver = context.contentResolver
        return JSONObject()
            .put("requestedPermissions", permissions)
            .put("defaultIme", Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: JSONObject.NULL)
            .put("hardwareIme", Settings.Secure.getString(resolver, "show_ime_with_hard_keyboard") ?: JSONObject.NULL)
            .put("fontScale", Settings.System.getString(resolver, Settings.System.FONT_SCALE) ?: JSONObject.NULL)
            .put("night", Settings.Secure.getString(resolver, "ui_night_mode") ?: JSONObject.NULL)
            .put(
                "actualFont",
                context.resources.configuration.fontScale
                    .toDouble(),
            ).put("actualUiMode", context.resources.configuration.uiMode)
            .put("proxy", Settings.Global.getString(resolver, "http_proxy") ?: JSONObject.NULL)
            .put("proxyHost", Settings.Global.getString(resolver, "global_http_proxy_host") ?: JSONObject.NULL)
            .put("proxyPort", Settings.Global.getString(resolver, "global_http_proxy_port") ?: JSONObject.NULL)
            .put("proxyExclusions", Settings.Global.getString(resolver, "global_http_proxy_exclusion_list") ?: JSONObject.NULL)
    }

    private fun kind(reading: LocationUpdateReading): String =
        when (reading) {
            is LocationUpdateReading.Sample -> "SAMPLE"
            is LocationUpdateReading.Empty -> "EMPTY"
            is LocationUpdateReading.PermissionDenied -> "PERMISSION_DENIED"
            is LocationUpdateReading.Unsupported -> "UNSUPPORTED"
            is LocationUpdateReading.NoUpdate -> "NO_UPDATE"
            is LocationUpdateReading.Error -> "ERROR_${reading.origin.name}"
        }

    private suspend fun requireActualForeground() {
        val deadline = SystemClock.elapsedRealtime() + 1_000
        while (true) {
            check(mainState() == Lifecycle.State.RESUMED) { "The same actual Main must remain RESUMED" }
            var windowFocused: Boolean? = null
            h.instrument.runOnMainSync { windowFocused = actualMain.hasWindowFocus() }
            check(windowFocused == true) { "The actual Main window must retain focus" }
            val actual = h.actualPackage()
            if (actual != null) {
                check(actual == h.instrument.targetContext.packageName) { "A known foreign package is not Main foreground" }
                h.requireForeground()
                return
            }
            unknownFocusSamples.incrementAndGet()
            check(SystemClock.elapsedRealtime() < deadline) { "Actual Main focused package remained unknown" }
            delay(25)
        }
    }

    private fun frameworkPermission(): Boolean =
        h.instrument.targetContext.checkSelfPermission(FusedLocationPermission.NAME) == PackageManager.PERMISSION_GRANTED

    private fun actualDrive(): Boolean {
        val state =
            h.graph
                .vehicle()
                .state.value
        return state.connection == ConnectionState.CONNECTED && (state.gear as? VehicleReading.Available)?.value == GearPosition.DRIVE
    }

    private fun mainState(): Lifecycle.State {
        var state: Lifecycle.State? = null
        h.instrument.runOnMainSync {
            check(compose.activity === actualMain) { "Actual Main was unexpectedly replaced" }
            state = actualMain.lifecycle.currentState
        }
        return checkNotNull(state)
    }

    private suspend fun await(
        timeout: Long,
        condition: () -> Boolean,
    ) = withTimeout(timeout) {
        while (!condition()) delay(50)
    }

    private fun phase(value: String) = h.instrument.sendStatus(2, Bundle().apply { putString("stream", value + System.lineSeparator()) })

    private fun write(
        name: String,
        value: JSONObject,
    ) {
        val target = File(directory, name)
        check(!target.exists()) { "Native evidence must not overwrite an earlier case" }
        target.writeText(value.toString(2))
    }

    private companion object {
        const val WINDOW_MILLIS = 20_000L
        const val HOST_STOP_AT_MILLIS = 18_000L
    }
}
