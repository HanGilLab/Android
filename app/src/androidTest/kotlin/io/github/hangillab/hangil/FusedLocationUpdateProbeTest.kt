package io.github.hangillab.hangil

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import android.view.Display
import android.view.ViewTreeObserver
import android.view.WindowInsets
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import io.github.hangillab.hangil.core.vehicle.FusedLocationPermission
import io.github.hangillab.hangil.core.vehicle.FusedLocationUpdateProbe
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Opt-in actual Main/SDK update-registration probe. No permission request, grant, location
 * injection, last-known request, UI replacement or production database write is performed.
 * A returned register call and a completed harness are not evidence of an SDK update receipt.
 * Returned cleanup calls are local outcomes, not a remote-provider stop acknowledgement.
 * SDK Binder null updates may never reach onChanged; timeout remains notReceived, never Empty.
 * The external runner owns fresh consent, original whole-environment backup and restoration.
 * The target SDK blocks app reads of enabled_input_methods. The external runner must read and
 * compare its actual before/after value; this harness neither observes nor substitutes it.
 */
@RunWith(AndroidJUnit4::class)
class FusedLocationUpdateProbeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val h by lazy { SessionHistoryRecordingRunFlowTest.Harness(compose) }
    private val namespace = UUID.randomUUID().toString()
    private val directory by lazy {
        File(h.instrument.targetContext.cacheDir, "screenshots/fused-location-update-probe-$namespace")
            .apply { check(mkdirs()) }
    }

    @Test fun missingPermissionNeverRegistersAnUpdateAndPreservesOriginalData() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("manualFusedLocationUpdateProbe") == "true")
        runCase(alreadyGranted = false)
    }

    /** This flag acknowledges a separately approved, externally completed grant. It grants nothing. */
    @Test fun approvedExistingGrantObservesActualSdkOutcomeAndOneFreshInstanceRetry() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("manualFusedLocationUpdateProbe") == "true")
        assumeTrue(args.getString("allowTemporaryFusedLocationGrant") == "true")
        runCase(alreadyGranted = true)
    }

    private fun runCase(alreadyGranted: Boolean) =
        runBlocking {
            val report =
                JSONObject()
                    .put("schemaVersion", 1)
                    .put("harnessStatus", "started")
                    .put("locationUpdateAdapterSource", "real-FusedLocation-1.1.3-debug-probe")
                    .put("namespace", namespace)
                    .put("expectedAlreadyGranted", alreadyGranted)
                    .put(
                        "temporaryGrantAcknowledged",
                        InstrumentationRegistry.getArguments().getString("allowTemporaryFusedLocationGrant") == "true",
                    ).put("permissionGrantedByHarness", false)
                    .put("locationInjectedByHarness", false)
                    .put("lastKnownRequestedByHarness", false)
                    .put("productionWritesByHarness", false)
                    .put("detailedRuntimePermissionFlagsVerifiedByHarness", false)
                    .put("enabledInputMethodsObservedByHarness", false)
                    .put("enabledInputMethodsExternalBeforeAfterReadbackRequired", true)
                    .put("enabledInputMethodsObservationReason", "app-read-blocked-by-target-sdk-policy-no-substitute-value")
                    .put("externalRunnerRequired", "whole-runtime-grant-flags-and-whole-environment-before-after-exact-preservation")
                    .put(
                        "sameListenerBasis",
                        "debug-adapter-single-listener-identity-contract-native-observes-local-unregister-return-only",
                    ).put("callbackWindowMillis", 20_000)
                    .put("postStartObservationBoundMillis", 23_000)
                    .put("maximumInstances", if (alreadyGranted) 2 else 1)
                    .put("timingsAreDiagnosticNotPerformanceComparisons", true)
                    .put("cleanupMeaning", "local-call-returns-and-all-required-cleanup-attempts-not-remote-provider-stop-ACK")
                    .put("nullBinderUpdateCanBeSilent", true)
                    .put("timeoutMeaning", "no-observed-listener-event-never-inferred-Empty-or-unsupported")
                    .put("sdkErrorMeaning", "class-only-no-message-based-permission-or-support-inference")
                    .put("unsupportedMeaning", "observed-isSupported-false-exact-unavailability-cause-unknown")
                    .put("callbackWindowIsNotSynchronousBinderInterruption", true)
                    .put("guardBasis", "atomic-real-Main-lifecycle-display-window-focus-and-current-SDK-P-UX")
                    .put("focusedWindowBasis", "single-focused-window-on-actual-Main-display-outside-callback")
                    .put("DAndHostInvalidationProof", "not-exercised-here-controlled-debug-tests-are-separate-no-SDK-callback-injection")
                    .put("coordinatesSdkTimestampConfidenceAndRawErrorsRecorded", false)
                    .put("sdkTechnicalVerdict", "notReceived-incomplete")
                    .put("instances", JSONArray())
                    .put("failures", JSONArray())
            val instances = report.getJSONArray("instances")
            val failures = report.getJSONArray("failures")
            var primary: Throwable? = null
            var before: Original? = null
            var sdkObserver: Job? = null
            var guard: HostGuard? = null
            val guardReference = AtomicReference<HostGuard?>(null)
            var originalInteractiveFlag: Boolean? = null
            val owners = mutableListOf<FusedLocationUpdateProbe>()
            var actualSdkEvent = false
            var actualLocation = false
            var permissionDeniedVerified = false

            fun preserve(
                stage: String,
                failure: Throwable,
            ) {
                failures.put(JSONObject().put("stage", stage).put("class", failure.javaClass.name))
                val original = primary
                if (original == null) {
                    primary = failure
                } else if (original !== failure) {
                    original.addSuppressed(failure)
                }
            }

            suspend fun attempt(
                stage: String,
                block: suspend () -> Unit,
            ) {
                try {
                    block()
                } catch (failure: Throwable) {
                    preserve(stage, failure)
                }
            }
            try {
                originalInteractiveFlag =
                    h.instrument.uiAutomation.serviceInfo.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0
                h.assertEnvironment()
                check(permissionGranted() == alreadyGranted) { "actual permission does not match the explicitly selected scenario" }
                sdkObserver =
                    launch {
                        combine(h.graph.vehicle().state, h.graph.restrictions().restrictions) { vehicle, ux ->
                            allowsPracticeInteraction(vehicle, ux)
                        }.collect { allowed -> if (!allowed) guardReference.get()?.retire() }
                    }
                h.awaitAllowed()
                h.requireForeground()
                report.put("initialLiveGateAllowed", h.liveAllowed())
                check(h.liveAllowed())
                before = original()
                report.put("original", checkNotNull(before).json)
                guard = HostGuard()
                guardReference.set(guard)
                checkNotNull(guard).attach()
                val count = if (alreadyGranted) 2 else 1
                repeat(count) { index ->
                    val activeGuard = checkNotNull(guard)
                    activeGuard.observeFocusedWindow()
                    val events = ConcurrentLinkedQueue<JSONObject>()
                    val probe =
                        FusedLocationUpdateProbe(
                            h.instrument.targetContext,
                            canContinue = activeGuard::isAllowed,
                            onEvent = { events.add(eventJson(it)) },
                            timeoutMillis = 20_000L,
                        )
                    check(owners.none { it === probe })
                    owners.add(probe)
                    val row = JSONObject().put("ordinal", index + 1).put("freshInstance", true).put("callbacks", JSONArray())
                    instances.put(row)
                    phase("FUSED_LOCATION_UPDATE_INSTANCE_${index + 1}_START")
                    val started = SystemClock.elapsedRealtimeNanos()
                    val returned = probe.start()
                    row.put("startReturnedAfterNs", SystemClock.elapsedRealtimeNanos() - started)
                    row.put("startSnapshot", snapshotJson(returned))
                    var snapshot = returned
                    val deadline = started + 23_000_000_000L
                    while ((!snapshot.closed || !snapshot.cleanupComplete) && SystemClock.elapsedRealtimeNanos() < deadline) {
                        activeGuard.observeFocusedWindow()
                        snapshot = probe.snapshot()
                        if (!snapshot.closed || !snapshot.cleanupComplete) delay(50)
                    }
                    row.put("observedAfterNs", SystemClock.elapsedRealtimeNanos() - started)
                    check(snapshot.closed && snapshot.cleanupComplete) { "bounded probe did not finish its callback window and cleanup" }
                    check(SystemClock.elapsedRealtimeNanos() < deadline) {
                        "observed callback-window and cleanup bound exceeded; a blocking SDK call is not forcibly interruptible"
                    }
                    val closed = probe.close()
                    row.put("finalSnapshot", snapshotJson(closed))
                    row.put("callbacks", JSONArray(events.toList()))
                    row.put("sdkCallbackReceived", events.isNotEmpty())
                    row.put("registerReturnIsCallbackSuccess", false)
                    row.put("sdkEventOutcome", sdkEventOutcome(closed))
                    actualSdkEvent = actualSdkEvent || events.isNotEmpty()
                    actualLocation = actualLocation || closed.events.any { it.kind.name == "LOCATION_CALLBACK" }
                    assertCleanup(closed)
                    if (!alreadyGranted) {
                        check(closed.termination?.name == "PERMISSION_DENIED")
                        check(closed.permissionGranted == false)
                        check(!closed.initializeAttempted && !closed.initializeReturned)
                        check(!closed.registerAttempted && !closed.registerReturned && events.isEmpty())
                        check(!closed.releaseRequired && !closed.releaseAttempted)
                        permissionDeniedVerified = true
                    }
                    check(permissionGranted() == alreadyGranted)
                    activeGuard.observeFocusedWindow()
                    capture("instance-${index + 1}", diagnostic = false)
                    phase("FUSED_LOCATION_UPDATE_INSTANCE_${index + 1}_CLOSED")
                }
            } catch (failure: Throwable) {
                preserve("body", failure)
            } finally {
                withContext(NonCancellable) {
                    owners.forEachIndexed { index, owner ->
                        attempt("close-instance-${index + 1}") { owner.close() }
                        attempt("snapshot-and-cleanup-instance-${index + 1}") {
                            val closed = owner.snapshot()
                            if (index < instances.length()) {
                                instances
                                    .getJSONObject(
                                        index,
                                    ).put("cleanupSnapshot", snapshotJson(closed))
                                    .put("sdkEventOutcome", sdkEventOutcome(closed))
                            }
                            actualSdkEvent = actualSdkEvent || closed.events.isNotEmpty()
                            actualLocation = actualLocation || closed.events.any { it.kind.name == "LOCATION_CALLBACK" }
                            assertCleanup(closed)
                        }
                    }
                    attempt("actual-Main-final") { h.requireForeground() }
                    attempt("detach-host-guard") {
                        report.put("lastFocusedWindowReceipt", guard?.lastReceipt() ?: JSONObject.NULL)
                        guard?.close()
                    }
                    attempt("preserve-domain") {
                        before?.let { h.assertPreserved(h.graph, it.domain) }
                        check(before != null)
                        report.put("domainPreserved", true)
                    }
                    attempt("preserve-physical-database") {
                        h.requireSameJson(
                            checkNotNull(before).json.getJSONObject("physicalDatabase"),
                            physicalDatabase(),
                            "all typed SQLite fields/order/sequences",
                        )
                        report.put("physicalDatabasePreserved", true)
                    }
                    attempt("preserve-catalog") {
                        h.requireSameJson(checkNotNull(before).json.getJSONObject("catalog"), h.catalogJson(h.graph), "catalog")
                        report.put("catalogPreserved", true)
                    }
                    attempt("preserve-environment") {
                        h.requireSameJson(checkNotNull(before).json.getJSONObject("environment"), h.environmentJson(), "environment")
                        report.put("environmentPreserved", true)
                    }
                    attempt("preserve-public-permissions-settings") {
                        h.requireSameJson(
                            checkNotNull(before).json.getJSONObject("platform"),
                            platformJson(),
                            "public-grants/IME/font/night/proxy",
                        )
                        report.put("publicPlatformStatePreserved", true)
                    }
                    attempt("cancel-SDK-gate-observer") {
                        sdkObserver?.cancelAndJoin()
                        report.put("sdkGateObserverClosed", sdkObserver != null)
                    }
                    attempt("final-private-diagnostic") { capture("final", diagnostic = primary != null) }
                    attempt("restore-window-flag") {
                        h.restoreAutomationFlag()
                        val originalFlag = checkNotNull(originalInteractiveFlag)
                        val restoredFlags = h.instrument.uiAutomation.serviceInfo.flags
                        val restoredBit = restoredFlags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0
                        check(restoredBit == originalFlag)
                        report.put("windowFlagRestored", true)
                    }
                    report.put(
                        "locationUpdateSdkInitializeOrRegisterAttempted",
                        owners.map { it.snapshot() }.any { it.initializeAttempted || it.registerAttempted },
                    )
                    report.put("sdkCallbackReceived", actualSdkEvent)
                    report.put("locationCallbackReceived", actualLocation)
                    report.put("permissionDeniedWithoutRegistrationVerified", permissionDeniedVerified)
                    report.put(
                        "sdkTechnicalVerdict",
                        when {
                            !alreadyGranted && permissionDeniedVerified -> "permissionDenied-no-update-registration"
                            !alreadyGranted -> "denied-scenario-unverified"
                            actualLocation -> "actual-location-callback-received-not-position-or-continuity-proof"
                            actualSdkEvent -> "actual-non-location-SDK-event-no-usable-location-success-claim"
                            else -> "notReceived-incomplete"
                        },
                    )
                    report.put("harnessStatus", if (primary == null) "completed" else "failed")
                    attempt("write-private-report") { writeReport(report) }
                }
            }
            primary?.let { throw it }
            phase("FUSED_LOCATION_UPDATE_PROBE_HARNESS_COMPLETED")
        }

    private fun eventJson(event: FusedLocationUpdateProbe.Event): JSONObject =
        JSONObject()
            .put("kind", event.kind.name)
            .put("source", event.metadata.source.name)
            .put("metadataAvailable", event.metadata.available)
            .put("validCoordinates", event.metadata.validCoordinates ?: JSONObject.NULL)
            .put("appReceiptElapsedRealtimeMillis", event.metadata.appReceiptElapsedRealtimeMillis)
            .put("errorClassName", event.errorClassName ?: JSONObject.NULL)

    private fun snapshotJson(snapshot: FusedLocationUpdateProbe.Snapshot): JSONObject =
        JSONObject()
            .put("started", snapshot.started)
            .put("closed", snapshot.closed)
            .put("supported", snapshot.supported ?: JSONObject.NULL)
            .put("permissionGranted", snapshot.permissionGranted ?: JSONObject.NULL)
            .put("termination", snapshot.termination?.name ?: JSONObject.NULL)
            .put("cleanupComplete", snapshot.cleanupComplete)
            .put("initializeAttempted", snapshot.initializeAttempted)
            .put("initializeReturned", snapshot.initializeReturned)
            .put("registerAttempted", snapshot.registerAttempted)
            .put("registerReturned", snapshot.registerReturned)
            .put("unregisterAttempted", snapshot.unregisterAttempted)
            .put("unregisterReturned", snapshot.unregisterReturned)
            .put("bindingCloseAttempted", snapshot.bindingCloseAttempted)
            .put("bindingCloseReturned", snapshot.bindingCloseReturned)
            .put("releaseRequired", snapshot.releaseRequired)
            .put("releaseAttempted", snapshot.releaseAttempted)
            .put("releaseReturned", snapshot.releaseReturned)
            .put("acceptedCallbackCount", snapshot.acceptedCallbackCount)
            .put("ignoredCallbackCount", snapshot.ignoredCallbackCount)
            .put("events", JSONArray(snapshot.events.map(::eventJson)))
            .put(
                "failures",
                JSONArray(
                    snapshot.failures.map {
                        JSONObject().put("stage", it.stage.name).put("class", it.exceptionClassName).put("primary", it.primary)
                    },
                ),
            )

    private fun sdkEventOutcome(snapshot: FusedLocationUpdateProbe.Snapshot): String =
        when {
            snapshot.events.any { it.kind.name == "LOCATION_CALLBACK" && it.metadata.validCoordinates == true } ->
                "locationCallback-structurally-valid-not-accuracy-or-current-position-proof"
            snapshot.events.any { it.kind.name == "LOCATION_CALLBACK" } -> "locationCallback-invalid-structure-no-usable-location-claim"
            snapshot.events.any { it.kind.name == "EMPTY_CALLBACK" } -> "emptyCallback-no-location"
            snapshot.events.any { it.kind.name == "SDK_ERROR" } -> "sdkErrorCallback-no-location-success"
            else -> "notReceived-incomplete"
        }

    private fun assertCleanup(snapshot: FusedLocationUpdateProbe.Snapshot) {
        check(snapshot.closed && snapshot.cleanupComplete)
        if (snapshot.permissionGranted != null || snapshot.supported != null || snapshot.bindingCloseAttempted) {
            check(snapshot.bindingCloseAttempted && snapshot.bindingCloseReturned)
        }
        if (snapshot.registerAttempted) {
            check(snapshot.unregisterAttempted && snapshot.unregisterReturned)
        }
        if (snapshot.releaseRequired) check(snapshot.releaseAttempted && snapshot.releaseReturned)
        val failedCleanup =
            snapshot.failures.any {
                it.stage.name in listOf("UNREGISTER", "BINDING_CLOSE", "RELEASE", "TIMER_CANCEL")
            }
        check(!failedCleanup) { "SDK cleanup failure is recorded separately from the received SDK outcome" }
    }

    /** Atomic-only SDK guard. Real focus is sampled outside the callback, never through Compose sync on Main. */
    private inner class HostGuard : AutoCloseable {
        private val owner = AtomicBoolean(true)
        private val host = AtomicBoolean(false)
        private val focused = AtomicBoolean(false)
        private val focusedPackage = AtomicReference<String?>(null)
        private var focusedSampleElapsedMillis: Long? = null
        private var display: Int? = null
        private var attachedActivity: MainActivity? = null
        private val lifecycle = LifecycleEventObserver { _, _ -> refreshOnMain() }
        private val focus = ViewTreeObserver.OnWindowFocusChangeListener { refreshOnMain() }

        fun attach() {
            h.instrument.runOnMainSync {
                val activity = compose.activity
                attachedActivity = activity
                val actual = checkNotNull(activity.display)
                check(actual.isValid)
                display = actual.displayId
                activity.lifecycle.addObserver(lifecycle)
                activity.window.decorView.viewTreeObserver
                    .addOnWindowFocusChangeListener(focus)
                refreshOnMain()
            }
            observeFocusedWindow()
        }

        private fun refreshOnMain() {
            val activity = checkNotNull(attachedActivity)
            val actual = activity.display
            val current =
                activity.lifecycle.currentState == Lifecycle.State.RESUMED && actual != null && actual.isValid &&
                    actual.displayId == display && activity.hasWindowFocus()
            host.set(current)
            if (!current) retire()
        }

        fun observeFocusedWindow() {
            h.instrument.runOnMainSync { refreshOnMain() }
            val actual = h.actualPackage()
            focusedPackage.set(actual)
            focusedSampleElapsedMillis = SystemClock.elapsedRealtime()
            focused.set(actual == h.instrument.targetContext.packageName)
            if (!focused.get()) retire()
            check(actual != null) { "actual Main focused-window package is unknown" }
            check(focused.get()) { "a known foreign focused window must not be treated as Main" }
            check(host.get()) { "actual Main lifecycle/display/window-focus receipt is invalid" }
            check(isAllowed()) { "current actual P and UX gate is denied or unknown" }
        }

        fun lastReceipt(): JSONObject =
            JSONObject()
                .put("actualPackage", focusedPackage.get() ?: JSONObject.NULL)
                .put("sampleElapsedRealtimeMillis", focusedSampleElapsedMillis ?: JSONObject.NULL)
                .put("known", focusedPackage.get() != null)
                .put("basis", "single-focused-window-on-actual-Main-display")

        fun isAllowed(): Boolean {
            val allowed = owner.get() && host.get() && focused.get() && h.liveAllowed()
            if (!allowed) retire()
            return allowed
        }

        fun retire() {
            owner.set(false)
        }

        override fun close() {
            retire()
            focused.set(false)
            h.instrument.runOnMainSync {
                val activity = attachedActivity
                try {
                    activity?.lifecycle?.removeObserver(lifecycle)
                } finally {
                    activity
                        ?.window
                        ?.decorView
                        ?.viewTreeObserver
                        ?.removeOnWindowFocusChangeListener(focus)
                    host.set(false)
                }
            }
        }
    }

    private data class Original(
        val domain: SessionHistoryRecordingRunFlowTest.Harness.ExistingData,
        val json: JSONObject,
    )

    private suspend fun original(): Original {
        val data = h.snapshot(h.graph)
        check(data.sessions.none { it.isActive })
        val physical = physicalDatabase()
        return Original(
            data,
            JSONObject()
                .put("data", h.dataJson(data))
                .put("physicalDatabase", physical)
                .put("catalog", h.catalogJson(h.graph))
                .put("environment", h.environmentJson())
                .put("platform", platformJson()),
        )
    }

    private fun permissionGranted(): Boolean =
        h.instrument.targetContext.checkSelfPermission(FusedLocationPermission.NAME) == PackageManager.PERMISSION_GRANTED

    // Public app-readable state only. enabled_input_methods needs the external runner's actual
    // shell before/after readback; no permission adoption or fabricated successful value is used.
    private fun platformJson(): JSONObject {
        val context = h.instrument.targetContext
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        val names = checkNotNull(info.requestedPermissions)
        val flags = checkNotNull(info.requestedPermissionsFlags)
        check(names.size == flags.size && FusedLocationPermission.NAME in names)
        val permissions = JSONArray()
        names.indices.forEach { index ->
            permissions.put(
                JSONObject()
                    .put("name", names[index])
                    .put("requestedFlags", flags[index])
                    .put("actualGranted", context.checkSelfPermission(names[index]) == PackageManager.PERMISSION_GRANTED),
            )
        }
        val resolver = context.contentResolver
        var imeVisible: Boolean? = null
        h.instrument.runOnMainSync {
            imeVisible =
                compose.activity.window.decorView.rootWindowInsets
                    ?.isVisible(WindowInsets.Type.ime())
        }
        check(imeVisible != null)
        return JSONObject()
            .put("requestedPermissionsInManifestOrder", permissions)
            .put("fusedLocationGranted", permissionGranted())
            .put("defaultInputMethod", Settings.Secure.getString(resolver, Settings.Secure.DEFAULT_INPUT_METHOD) ?: JSONObject.NULL)
            .put("showImeWithHardKeyboard", Settings.Secure.getString(resolver, "show_ime_with_hard_keyboard") ?: JSONObject.NULL)
            .put("actualImeVisible", imeVisible)
            .put("fontScaleSetting", Settings.System.getString(resolver, Settings.System.FONT_SCALE) ?: JSONObject.NULL)
            .put("nightModeSetting", Settings.Secure.getString(resolver, "ui_night_mode") ?: JSONObject.NULL)
            .put(
                "fontScaleActual",
                context.resources.configuration.fontScale
                    .toDouble(),
            ).put("uiModeActual", context.resources.configuration.uiMode)
            .put("httpProxy", Settings.Global.getString(resolver, "http_proxy") ?: JSONObject.NULL)
            .put("httpProxyHost", Settings.Global.getString(resolver, "global_http_proxy_host") ?: JSONObject.NULL)
            .put("httpProxyPort", Settings.Global.getString(resolver, "global_http_proxy_port") ?: JSONObject.NULL)
            .put("httpProxyExclusions", Settings.Global.getString(resolver, "global_http_proxy_exclusion_list") ?: JSONObject.NULL)
    }

    private fun physicalDatabase(): JSONObject {
        val file = h.instrument.targetContext.getDatabasePath("hangil.db")
        check(file.isFile)
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            check(sqlite.version == 6)
            val tables = JSONArray()
            sqlite.rawQuery("SELECT name, sql FROM sqlite_master WHERE type='table' ORDER BY name", null).use { names ->
                while (names.moveToNext()) {
                    val name = names.getString(0)
                    val quoted = "\"" + name.replace("\"", "\"\"") + "\""
                    val rows = JSONArray()
                    val columns = JSONArray()
                    sqlite.rawQuery("SELECT rowid AS __probe_rowid__, * FROM $quoted ORDER BY rowid", null).use { cursor ->
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
                                        Cursor.FIELD_TYPE_FLOAT -> JSONObject().put("type", "FLOAT").put("value", cursor.getDouble(column))
                                        Cursor.FIELD_TYPE_STRING ->
                                            JSONObject()
                                                .put(
                                                    "type",
                                                    "STRING",
                                                ).put("value", cursor.getString(column))
                                        Cursor.FIELD_TYPE_BLOB ->
                                            JSONObject().put("type", "BLOB_HEX").put(
                                                "value",
                                                cursor.getBlob(column).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) },
                                            )
                                        else -> error("unknown actual SQLite field type")
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
            sqlite.rawQuery("SELECT COUNT(*) FROM course_completion_declaration", null).use {
                check(it.moveToFirst() && it.getLong(0) == 0L)
            }
            sqlite.rawQuery("PRAGMA foreign_key_check", null).use { check(it.count == 0) }
            JSONObject().put("schemaVersion", sqlite.version).put("tablesInNameOrder", tables).put("foreignKeyViolations", 0)
        }
    }

    private fun capture(
        stage: String,
        diagnostic: Boolean,
    ) {
        var failure: Throwable? = null
        var foreground = false
        var imeVisible: Boolean? = null
        var mainDisplay: Int? = null
        var displayValid = false
        var mainState: String? = null
        try {
            h.requireForeground()
            foreground = true
        } catch (problem: Throwable) {
            failure = problem
        }
        try {
            h.instrument.runOnMainSync {
                val activity = compose.activity
                imeVisible =
                    activity.window.decorView.rootWindowInsets
                        ?.isVisible(WindowInsets.Type.ime())
                val display = activity.display
                mainDisplay = display?.displayId
                displayValid = display != null && display.isValid
                mainState = activity.lifecycle.currentState.name
            }
        } catch (problem: Throwable) {
            val first = failure
            if (first == null) {
                failure = problem
            } else if (first !== problem) {
                first.addSuppressed(problem)
            }
        }
        val metadata =
            JSONObject()
                .put("locationUpdateAdapterSource", "real-FusedLocation-1.1.3-debug-probe")
                .put("foregroundConfirmed", foreground)
                .put("actualImeVisible", imeVisible ?: JSONObject.NULL)
                .put("mainDisplayKnown", mainDisplay != null)
                .put("mainDisplay", mainDisplay ?: JSONObject.NULL)
                .put("mainDisplayValid", displayValid)
                .put("mainState", mainState ?: JSONObject.NULL)
                .put("captureScope", if (diagnostic) "raw-default-display-diagnostic" else "confirmed-Main-default-display")
                .put("pngSaved", false)
        try {
            if (!diagnostic) {
                check(foreground && mainDisplay == Display.DEFAULT_DISPLAY && displayValid && mainState == Lifecycle.State.RESUMED.name)
            }
            val image = checkNotNull(h.instrument.uiAutomation.takeScreenshot())
            try {
                File(directory, "$stage.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                image.recycle()
            }
            metadata.put("pngSaved", true)
        } catch (problem: Throwable) {
            metadata.put("pngFailureClass", problem.javaClass.name)
            val first = failure
            if (first == null) {
                failure = problem
            } else if (first !== problem) {
                first.addSuppressed(problem)
            }
        }
        try {
            check(!File(directory, "$stage.json").exists())
            File(directory, "$stage.json").writeText(metadata.toString(2))
        } catch (problem: Throwable) {
            val first = failure
            if (first == null) {
                failure = problem
            } else if (first !== problem) {
                first.addSuppressed(problem)
            }
        }
        failure?.let { throw it }
    }

    private fun writeReport(report: JSONObject) {
        val reports = File(h.instrument.targetContext.cacheDir, "measurements")
        check(reports.isDirectory || reports.mkdirs())
        val target = File(reports, "fused-location-update-probe-$namespace.json")
        check(!target.exists() && !File(target.path + ".bak").exists() && !File(target.path + ".new").exists())
        val atomic = AtomicFile(target)
        val stream = atomic.startWrite()
        try {
            stream.write(report.toString(2).toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    private fun phase(value: String) = h.instrument.sendStatus(2, Bundle().apply { putString("stream", value + System.lineSeparator()) })
}
