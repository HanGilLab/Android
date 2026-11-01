package io.github.hangillab.hangil

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.view.Display
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.database.OwnedPracticeTestStore
import io.github.hangillab.hangil.core.designsystem.HanGilTheme
import io.github.hangillab.hangil.core.domain.BackgroundRecordingState
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.ForegroundLocationRecordingController
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.LocationRecordingBeginResult
import io.github.hangillab.hangil.core.domain.LocationRecordingEndResult
import io.github.hangillab.hangil.core.domain.LocationRecordingRun
import io.github.hangillab.hangil.core.domain.LocationRecordingRunStatus
import io.github.hangillab.hangil.core.domain.LocationRecordingSnapshot
import io.github.hangillab.hangil.core.domain.LocationRecordingState
import io.github.hangillab.hangil.core.domain.LocationRecordingStopReason
import io.github.hangillab.hangil.core.domain.LocationUpdateErrorOrigin
import io.github.hangillab.hangil.core.domain.LocationUpdateGateway
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import io.github.hangillab.hangil.core.domain.LocationUpdateSource
import io.github.hangillab.hangil.core.domain.NavigationGateway
import io.github.hangillab.hangil.core.domain.NavigationScreenResult
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.RecordingController
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleRecordingState
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.feature.home.BackgroundRecordingViewModel
import io.github.hangillab.hangil.feature.home.HomeRoute
import io.github.hangillab.hangil.feature.home.HomeViewModel
import io.github.hangillab.hangil.feature.home.LocationHistoryRoute
import io.github.hangillab.hangil.feature.home.LocationHistoryViewModel
import io.github.hangillab.hangil.feature.home.LocationRecordingRoute
import io.github.hangillab.hangil.feature.home.LocationRecordingUiState
import io.github.hangillab.hangil.feature.home.LocationRecordingViewModel
import io.github.hangillab.hangil.feature.home.NavigationScreenViewModel
import io.github.hangillab.hangil.feature.home.RecentVehicleObservationViewModel
import io.github.hangillab.hangil.feature.home.SessionHistoryRoute
import io.github.hangillab.hangil.feature.home.SessionHistoryUiState
import io.github.hangillab.hangil.feature.home.SessionHistoryViewModel
import io.github.hangillab.hangil.feature.home.SessionMemoViewModel
import io.github.hangillab.hangil.feature.home.SessionViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import io.github.hangillab.hangil.feature.home.R as HomeR

/**
 * Actual product Routes, controller and UUID-owned Room, with controlled vehicle/UX/location inputs.
 * The Activity stays RESUMED. An injected LifecycleRegistry controls PAUSE/STOP Main-host policy;
 * this is not actual OS Main STOP, SDK callback receipt, a permission grant or an FGS test.
 * Every ViewModel is explicitly preseeded: this fixture never uses the production Hilt graph/DB.
 * Raw detail assertions use synthetic coordinates only. No screenshot, SDK, permission or provider mutation.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
class LocationRecordingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private var actualHost: ComponentCatalogActivity? = null
    private var actualHostDisplay: Int? = null
    private val visualEnabled get() = InstrumentationRegistry.getArguments().getString("manualLocationRecordingVisual") == "true"
    private val visualDirectory by lazy {
        File(instrument.targetContext.cacheDir, "screenshots/foreground-location-controlled-${UUID.randomUUID()}").apply { check(mkdirs()) }
    }

    @Test fun explicitHomeEntryRecordsControlledDriveThenStopsAndReadsItsEndedHistory() =
        fixture { f ->
            click("session_start")
            val source = f.active()
            f.awaitSource(source)
            click("home_location_recording")
            val staleStart = clickAction("location_recording_start")
            visual("ready", "location_recording_start", "location_recording_scope", "location_recording_source_warning")
            click("location_recording_start")
            val run = f.awaitRecording()
            visual("recording", "location_recording_state", "location_recording_stop")
            invoke(staleStart)
            assertEquals(1, f.snapshot(source).runs.size)
            f.emit(f.sample(12.5))
            f.awaitEvents(source, 1)
            f.drive(true)
            waitTag("location_recording_locked")
            visual("locked-controlled-D", "location_recording_locked", "location_recording_stop")
            assertTrue(nodes("location_recording_start").isEmpty())
            assertEquals(run, (f.controller.state.value as LocationRecordingState.Recording).run)
            assertTrue(
                f.gateway.streams
                    .single()
                    .canContinue(),
            )
            f.emit(f.sample(22.5))
            f.awaitEvents(source, 2)
            assertEquals(
                LocationRecordingRunStatus.OPEN,
                f
                    .snapshot(source)
                    .runs
                    .single()
                    .status,
            )
            click("location_recording_stop")
            f.awaitTerminal(source, LocationRecordingStopReason.USER_STOP)
            assertTrue("Only the same owned session remains active", source == f.active())
            assertEquals(0, f.gateway.active.get())
            val before = f.snapshot(source)
            f.emit(f.sample(32.5), f.gateway.streams.single())
            assertEquals(before, f.snapshot(source))
            f.drive(false)
            waitTag("location_recording_start")
            assertEquals(1, f.snapshot(source).runs.size)
            click("location_recording_back")
            click("session_finish")
            val ended = f.ended(source.sessionId)
            click("home_history")
            click("history_location_recording")
            waitTag("location_history_content")
            assertTrue("History opens the exact captured ended source", ended == f.selectedHistory)
            assertTrue(nodes("location_history_raw_content").isEmpty())
            assertEquals(
                listOf(LocationUpdateSource.SDK_UPDATE, LocationUpdateSource.SDK_UPDATE),
                f.snapshot(ended).events.map { (it.reading as LocationUpdateReading.Sample).sample.source },
            )
            assertText("location_history_run_state", HomeR.string.location_history_stopped)
            visual("ended-history", "location_history_source_warning", "location_history_run_state")
            click("location_history_raw_details")
            waitTag("location_history_raw_content")
            assertFalse(hasTextNode(IllegalStateException::class.java.name))
            f.assertNoPlatformActions()
            phase("CONTROLLED_DRIVE_APPEND_EXPLICIT_STOP_ENDED_HISTORY")
        }

    @Test fun pauseStopAndSourceReplacementRetireOldActionsWithoutAutomaticRestart() =
        fixture { f ->
            click("session_start")
            val firstSource = f.active()
            f.awaitSource(firstSource)
            click("home_location_recording")
            val pausedStart = clickAction("location_recording_start")
            f.move(Lifecycle.State.STARTED)
            invoke(pausedStart)
            f.move(Lifecycle.State.RESUMED)
            waitTag("location_recording_start")
            invoke(pausedStart)
            assertTrue(f.snapshot(firstSource).runs.isEmpty())
            click("location_recording_start")
            val firstRun = f.awaitRecording()
            val oldStop = clickAction("location_recording_stop")
            val firstStream = f.gateway.streams.single()
            f.emit(f.sample(12.5))
            f.awaitEvents(firstSource, 1)
            f.move(Lifecycle.State.CREATED)
            f.awaitTerminal(firstSource, LocationRecordingStopReason.HOST_STOPPED)
            assertEquals(0, f.gateway.active.get())
            f.move(Lifecycle.State.RESUMED)
            f.awaitSource(firstSource)
            waitTag("location_recording_start")
            invoke(pausedStart)
            assertEquals(1, f.snapshot(firstSource).runs.size)
            click("location_recording_start")
            val secondRun = f.awaitRecording()
            assertTrue(firstRun.runId != secondRun.runId)
            invoke(oldStop)
            assertEquals(secondRun, (f.controller.state.value as LocationRecordingState.Recording).run)
            val before = f.snapshot(firstSource)
            f.emit(f.sample(22.5), firstStream)
            assertEquals(before, f.snapshot(firstSource))
            val replacement = f.replaceActive(firstSource)
            f.awaitTerminal(firstSource, LocationRecordingStopReason.SESSION_FINISHED)
            f.awaitDisplayedSource(replacement)
            val retiredEnable = clickAction("location_recording_start")
            val newest = f.replaceActive(replacement)
            f.awaitDisplayedSource(newest)
            invoke(retiredEnable)
            assertTrue(f.snapshot(replacement).runs.isEmpty())
            assertTrue(f.snapshot(newest).runs.isEmpty())
            assertEquals(0, f.gateway.active.get())
            f.emit(f.sample(32.5), f.gateway.streams.last())
            assertTrue(f.snapshot(newest).events.isEmpty())
            f.assertNoPlatformActions()
            phase("CONTROLLED_PAUSE_STOP_SOURCE_REPLACEMENT_RETIRED_ACTIONS")
        }

    @Test fun storedSamplesEmptyErrorsAndUnknownEndsKeepTheirMeaningsAndStaleHistoryActionsRetire() =
        fixture { f ->
            val source = f.startOwned()
            f.seedHistory(source)
            val older = f.finishOwned(source)
            val newer = f.finishOwned(f.startOwned())
            f.gateway.permission.set(false) // Stored reads do not require a new location runtime permission.
            compose.runOnIdle { f.screen.value = Screen.History }
            waitTag("history_location_recording")
            compose.waitUntil(20_000) { (f.historyModel.uiState.value as? SessionHistoryUiState.Ready)?.session == newer }
            val staleHistory = clickAction("history_location_recording")
            click("history_older")
            compose.waitUntil(20_000) { (f.historyModel.uiState.value as? SessionHistoryUiState.Ready)?.session == older }
            waitTag("history_location_recording")
            invoke(staleHistory)
            assertEquals(0, f.historyOpens.get())
            assertTrue("Retired history action must not navigate", f.screen.value == Screen.History)
            click("history_location_recording")
            waitTag("location_history_content")
            assertTrue("Fresh history action opens the selected older source", older == f.selectedHistory)
            assertTrue(older.sessionId != newer.sessionId)
            waitTag("location_history_interrupted")
            waitTag("location_history_unknown_end")
            waitTag("location_history_no_events")
            click("location_history_run_previous")
            assertText("location_history_event_state", HomeR.string.location_history_no_update)
            assertTrue(nodes("location_history_raw_details").isEmpty())
            click("location_history_run_previous")
            assertText("location_history_event_state", HomeR.string.location_history_error)
            waitTag("location_history_unknown_end")
            assertTrue(nodes("location_history_raw_details").isEmpty())
            assertFalse(hasTextNode(IllegalStateException::class.java.name))
            click("location_history_run_previous")
            assertText("location_history_event_state", HomeR.string.location_history_callback_empty)
            waitTag("location_history_interrupted")
            assertTrue(nodes("location_history_raw_details").isEmpty())
            click("location_history_event_previous")
            assertText("location_history_event_state", HomeR.string.location_history_sample)
            val oldRaw = clickAction("location_history_raw_details")
            click("location_history_event_previous")
            invoke(oldRaw)
            assertTrue(nodes("location_history_raw_content").isEmpty())
            click("location_history_raw_details")
            waitTag("location_history_raw_content")
            val expectedLatitude = instrument.targetContext.getString(HomeR.string.location_history_latitude, "12.5")
            assertTrue(hasTextNode(expectedLatitude))
            f.move(Lifecycle.State.STARTED)
            compose.waitForIdle()
            assertTrue(nodes("location_history_raw_content").isEmpty())
            invoke(oldRaw)
            f.move(Lifecycle.State.RESUMED)
            waitTag("location_history_content")
            assertTrue(nodes("location_history_raw_content").isEmpty())
            f.assertNoPlatformActions()
            phase("CONTROLLED_TYPED_HISTORY_NULL_STATES_UNKNOWN_END_AND_RAW_FENCING")
        }

    private fun fixture(block: (Fixture) -> Unit) {
        assumeTrue(InstrumentationRegistry.getArguments().getString("manualLocationRecordingControlled") == "true")
        val originalFlags = instrument.uiAutomation.serviceInfo.flags
        val retrieve = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        var f: Fixture? = null
        var ownedStore: OwnedPracticeTestStore? = null
        var primary: Throwable? = null
        val hostLeftResumed = AtomicBoolean(false)
        val actualObserver =
            LifecycleEventObserver { _, _ ->
                if (compose.activity.lifecycle.currentState != Lifecycle.State.RESUMED) hostLeftResumed.set(true)
            }
        try {
            instrument.uiAutomation.serviceInfo = instrument.uiAutomation.serviceInfo.apply { flags = flags or retrieve }
            assertTrue(instrument.uiAutomation.serviceInfo.flags and retrieve != 0)
            prepareActualHost()
            val store = OwnedPracticeTestStore.create(instrument.targetContext)
            ownedStore = store
            val owned = Fixture(store)
            f = owned
            runBlocking { owned.prepare() }
            instrument.runOnMainSync {
                compose.activity.lifecycle.addObserver(actualObserver)
                owned.mount()
            }
            waitTag("home_location_recording")
            block(owned)
            assertFalse("Actual Compose Activity must stay RESUMED during controlled policy checks", hostLeftResumed.get())
            assertActualHost()
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            fun cleanup(action: () -> Unit) {
                try {
                    action()
                } catch (failure: Throwable) {
                    val original = primary
                    if (original == null) {
                        primary = failure
                    } else if (original !== failure) {
                        original.addSuppressed(failure)
                    }
                }
            }
            cleanup {
                instrument.runOnMainSync {
                    f?.lifecycleOwner?.moveTo(Lifecycle.State.DESTROYED)
                    compose.activity.lifecycle.removeObserver(actualObserver)
                }
            }
            cleanup { f?.closeController() }
            cleanup {
                instrument.runOnMainSync {
                    f?.visible?.value = false
                    f?.viewModels?.clear()
                }
                compose.waitForIdle()
            }
            cleanup { ownedStore?.close() }
            cleanup {
                val info = instrument.uiAutomation.serviceInfo
                info.flags = if (originalFlags and retrieve != 0) info.flags or retrieve else info.flags and retrieve.inv()
                instrument.uiAutomation.serviceInfo = info
                assertEquals(originalFlags and retrieve, instrument.uiAutomation.serviceInfo.flags and retrieve)
            }
            primary?.let { throw it }
        }
    }

    private fun nodes(tag: String) =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun waitTag(tag: String) = compose.waitUntil(20_000) { nodes(tag).size == 1 }

    private fun click(tag: String) {
        waitTag(tag)
        assertActualHost()
        val target = compose.onNodeWithTag(tag, useUnmergedTree = true)
        target.performScrollTo()
        assertActualHost()
        target.performClick()
        compose.waitForIdle()
        assertActualHost()
    }

    private fun clickAction(tag: String): () -> Boolean {
        waitTag(tag)
        assertActualHost()
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
        assertActualHost()
        return checkNotNull(nodes(tag).single().config[SemanticsActions.OnClick].action)
    }

    private fun invoke(action: () -> Boolean) {
        assertActualHost()
        instrument.runOnMainSync { action() }
        compose.waitForIdle()
        assertActualHost()
    }

    private fun assertText(
        tag: String,
        resource: Int,
    ) {
        waitTag(tag)
        val expected = instrument.targetContext.getString(resource)
        assertTrue(
            nodes(tag).single().config[SemanticsProperties.Text].any {
                it.text ==
                    expected
            },
        )
    }

    private fun hasTextNode(text: String) =
        compose.onAllNodes(hasText(text), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()

    private fun phase(value: String) {
        instrument.sendStatus(2, Bundle().apply { putString("stream", "LOCATION_RECORDING_$value\n") })
    }

    /** Private QA of a real Compose surface with explicitly controlled inputs, never an SDK screenshot. */
    private fun visual(
        state: String,
        vararg tags: String,
    ) {
        if (!visualEnabled) return
        for (tag in tags) {
            val proof =
                JSONObject()
                    .put("schema", 1)
                    .put("fixture", "controlled-vehicle-UX-location-inputs-actual-Compose-and-UUID-owned-Room")
                    .put("publicArtifact", false)
                    .put("actualSdkLocation", false)
                    .put("productionDatabaseUsed", false)
                    .put("permissionChanged", false)
                    .put("state", state)
                    .put("anchor", tag)
                    .put("defaultDisplayConfirmed", false)
                    .put("textGeometryChecked", false)
                    .put("rasterGlyphAppearanceVerified", false)
                    .put("pngSaved", false)
            var primary: Throwable? = null

            fun attempt(action: () -> Unit) {
                try {
                    action()
                } catch (failure: Throwable) {
                    val first = primary
                    if (first == null) {
                        primary = failure
                    } else if (first !== failure) {
                        first.addSuppressed(failure)
                    }
                }
            }
            attempt {
                waitTag(tag)
                assertActualHost()
                compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                compose.waitForIdle()
                assertActualHost()
                val viewport = visualViewport()
                val node = nodes(tag).single()
                val bounds = node.boundsInWindow
                val origin = node.positionOnScreen - node.positionInWindow
                check(node.size.width > 0 && node.size.height > 0)
                check(bounds.width >= node.size.width - 1 && bounds.height >= node.size.height - 1)
                check(bounds.left + origin.x >= viewport.left - 1 && bounds.top + origin.y >= viewport.top - 1)
                check(bounds.right + origin.x <= viewport.right + 1 && bounds.bottom + origin.y <= viewport.bottom + 1)
                val getters = descendants(node).mapNotNull { it.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action }
                check(getters.isNotEmpty()) { "The actual Text layout must be observable" }
                val layouts = mutableListOf<TextLayoutResult>()
                instrument.runOnMainSync { getters.forEach { check(it(layouts)) } }
                check(layouts.isNotEmpty() && layouts.all(::visualTextFits)) { "Large text must retain its complete actual layout" }
                val actualScale = checkNotNull(actualHost).resources.configuration.fontScale
                check(layouts.all { abs(it.layoutInput.density.fontScale - actualScale) <= 0.01f })
                proof
                    .put("textGeometryChecked", true)
                    .put("textLayoutCount", layouts.size)
                    .put(
                        "actualComposeFontScales",
                        JSONArray(
                            layouts
                                .map {
                                    it.layoutInput.density.fontScale
                                        .toDouble()
                                }.distinct(),
                        ),
                    )
                proof.put("minimumFontSp", layouts.minOf { it.layoutInput.style.fontSize.value }.toDouble())
            }
            // Layout failure still attempts a real screenshot independently; no drawing or expected image is used.
            attempt {
                assertActualHost()
                check(actualHostDisplay == Display.DEFAULT_DISPLAY) { "Default-display capture is unsupported for this actual host" }
                val size = Point()
                instrument.runOnMainSync {
                    val display = checkNotNull(checkNotNull(actualHost).display)
                    check(display.isValid && display.displayId == actualHostDisplay)
                    display.getRealSize(size)
                }
                check(size.x > 0 && size.y > 0)
                val bitmap = checkNotNull(instrument.uiAutomation.takeScreenshot()) { "Actual screenshot was unavailable" }
                try {
                    check(bitmap.width == size.x && bitmap.height == size.y) { "Actual display and screenshot extents must agree" }
                    assertActualHost()
                    proof
                        .put("defaultDisplayConfirmed", true)
                        .put("captureScope", "raw-default-display-with-confirmed-controlled-fixture-foreground")
                        .put("width", bitmap.width)
                        .put("height", bitmap.height)
                    val output = File(visualDirectory, "$state-$tag.png")
                    check(!output.exists())
                    output.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                    proof.put("pngSaved", true)
                } finally {
                    bitmap.recycle()
                }
            }
            attempt {
                val config = checkNotNull(actualHost).resources.configuration
                val args = InstrumentationRegistry.getArguments()
                val font = args.getString("expectedFontScale")
                val dark = args.getString("expectedDark")
                check(font == "1.0" || font == "1.3")
                check(dark == "false" || dark == "true")
                check(abs(config.fontScale - checkNotNull(font).toFloat()) <= 0.01f)
                val isDark = config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                check(isDark == (dark == "true"))
                proof
                    .put("fontScale", config.fontScale.toDouble())
                    .put("dark", isDark)
                    .put("configurationBasis", "actual-Activity-resources-not-system-setting-assumption")
            }
            proof
                .put("status", if (primary == null) "passed" else "failed")
                .put("failureClass", primary?.javaClass?.name ?: JSONObject.NULL)
                .put("secondaryFailureClasses", JSONArray(primary?.suppressed?.map { it.javaClass.name }.orEmpty()))
            attempt {
                val output = File(visualDirectory, "$state-$tag.json")
                check(!output.exists())
                output.writeText(proof.toString(2))
            }
            primary?.let { throw it }
        }
    }

    private fun visualViewport(): Rect =
        Rect().also { viewport ->
            instrument.runOnMainSync { checkNotNull(actualHost).window.decorView.getWindowVisibleDisplayFrame(viewport) }
            check(viewport.width() > 0 && viewport.height() > 0)
        }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)

    private fun visualTextFits(layout: TextLayoutResult): Boolean {
        val text = layout.layoutInput.text.text
        val font = layout.layoutInput.style.fontSize
        if (text.isEmpty() || !font.isSp || font.value < 24f || layout.hasVisualOverflow || layout.lineCount == 0 ||
            layout.size.width <= 0 || layout.size.height <= 0
        ) {
            return false
        }
        var covered = 0
        for (line in 0 until layout.lineCount) {
            if (layout.isLineEllipsized(line) || layout.getLineStart(line) != covered) return false
            covered = layout.getLineEnd(line, visibleEnd = false)
            if (layout.getLineLeft(line) < -1 || layout.getLineRight(line) > layout.size.width + 1 ||
                layout.getLineTop(line) < -1 || layout.getLineBottom(line) > layout.size.height + 1
            ) {
                return false
            }
        }
        return covered == text.length &&
            text.indices.filterNot { text[it].isWhitespace() }.all {
                val box = layout.getBoundingBox(it)
                box.left.isFinite() && box.top.isFinite() && box.right.isFinite() && box.bottom.isFinite() &&
                    box.left >= -1 && box.top >= -1 && box.right <= layout.size.width + 1 && box.bottom <= layout.size.height + 1
            }
    }

    /** Setup may wait for ActivityRule focus before any owned fixture or policy owner exists. */
    private fun prepareActualHost() {
        check(actualHost == null && actualHostDisplay == null)
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (actualHost == null && SystemClock.elapsedRealtime() < deadline) {
            instrument.runOnMainSync {
                val activity = compose.activity
                val display = activity.display
                if (activity.lifecycle.currentState == Lifecycle.State.RESUMED && activity.hasWindowFocus() &&
                    display != null && display.isValid
                ) {
                    actualHost = activity
                    actualHostDisplay = display.displayId
                }
            }
            if (actualHost == null) SystemClock.sleep(50)
        }
        assertTrue("Initial real Compose host focus/display must become known", actualHost != null && actualHostDisplay != null)
        while (SystemClock.elapsedRealtime() < deadline) {
            assertActualHostLifecycle()
            val observed = actualFocusedPackage()
            if (observed != null) {
                assertTrue("Known foreign initial focus cannot be substituted", observed == instrument.targetContext.packageName)
                return
            }
            SystemClock.sleep(50)
        }
        throw AssertionError("Initial actual focused package remained unknown")
    }

    /** Owned actions never wait for a foreign/unknown window to return or recapture a display. */
    private fun assertActualHost() {
        assertActualHostLifecycle()
        val observed = actualFocusedPackage()
        assertTrue("Actual focused package is unknown at an owned action boundary", observed != null)
        assertTrue(
            "Known foreign focus at an owned action boundary must fail immediately",
            observed == instrument.targetContext.packageName,
        )
    }

    private fun assertActualHostLifecycle() {
        var allowed = false
        instrument.runOnMainSync {
            val activity = actualHost
            val display = activity?.display
            allowed = activity != null && compose.activity === activity && activity.lifecycle.currentState == Lifecycle.State.RESUMED &&
                activity.hasWindowFocus() && display != null && display.isValid && display.displayId == actualHostDisplay
        }
        assertTrue("The captured real Compose host must remain RESUMED and focused on the same valid display", allowed)
    }

    private fun actualFocusedPackage(): String? {
        val display = checkNotNull(actualHostDisplay)
        val windows = instrument.uiAutomation.windowsOnAllDisplays
        val all = buildList { for (index in 0 until windows.size()) addAll(windows.valueAt(index)) }
        try {
            val scoped = windows[display] ?: return null
            if (scoped.any { it.displayId != display }) return null
            val focused = scoped.filter { it.isFocused }
            if (focused.size != 1) return null
            val root = focused.single().root ?: return null
            try {
                return root.packageName?.toString()
            } finally {
                root.recycle()
            }
        } finally {
            all.forEach { it.recycle() }
        }
    }

    private inner class Fixture(
        val store: OwnedPracticeTestStore,
    ) {
        val lifecycleOwner = ControlledLifecycleOwner()
        val visible = mutableStateOf(true)
        val screen = mutableStateOf<Screen>(Screen.Home)
        val viewModels = ViewModelStore()
        val scopeJob = SupervisorJob()
        val scope = CoroutineScope(scopeJob + Dispatchers.Main.immediate)
        val elapsed = AtomicLong(10_000)
        val gateway = ControlledGateway()
        val vehicle = MutableStateFlow(parked())
        val ux = MutableStateFlow(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))
        val vehicleGateway =
            object : VehicleGateway {
                override val state = vehicle
            }
        val restrictions =
            object : DrivingRestrictionsProvider {
                override val restrictions = ux
            }
        val clock: Clock = Clock.systemUTC()
        val processId = UUID.randomUUID().toString()
        val sourceReceipt = AtomicReference<PracticeSession?>(null)
        private val controllerSessions =
            object : io.github.hangillab.hangil.core.domain.PracticeSessionRepository by store.sessions {
                override fun observeSessions(): Flow<List<PracticeSession>> =
                    flow {
                        sourceReceipt.set(null)
                        try {
                            store.sessions.observeSessions().collect { rows ->
                                emit(rows)
                                sourceReceipt.set(rows.singleOrNull { it.isActive })
                            }
                        } finally {
                            sourceReceipt.set(null)
                        }
                    }
            }
        val controller =
            ForegroundLocationRecordingController(
                gateway,
                controllerSessions,
                store.locationRecordings,
                vehicleGateway,
                restrictions,
                clock,
                elapsed::get,
                scope,
                { UUID.randomUUID().toString() },
                processId,
            )
        val platformPermissionRequests = AtomicInteger()
        val historyOpens = AtomicInteger()
        var selectedHistory: PracticeSession? = null
        var hostJob: Job? = null
        lateinit var recordingModel: LocationRecordingViewModel
        lateinit var historyModel: SessionHistoryViewModel
        lateinit var locationHistoryModel: LocationHistoryViewModel
        private val navigationCalls = AtomicInteger()
        private val backgroundCalls = AtomicInteger()

        suspend fun prepare() {
            store.preparation.saveSelection(selection()) { true }
        }

        fun mount() {
            lifecycleOwner.moveTo(Lifecycle.State.RESUMED)
            val navigation =
                object : NavigationGateway {
                    override val connection = MutableStateFlow(ConnectionState.CONNECTED)

                    override suspend fun requestForeground(canDispatch: () -> Boolean): NavigationScreenResult {
                        navigationCalls.incrementAndGet()
                        error("Unexpected native navigation")
                    }
                }
            val background =
                object : RecordingController {
                    override val state = MutableStateFlow<VehicleRecordingState>(VehicleRecordingState.Idle)
                    override val background = MutableStateFlow<BackgroundRecordingState>(BackgroundRecordingState.Disabled)

                    override suspend fun recordWhileStarted(): Unit = error("Unexpected vehicle recorder")

                    override fun retry(): Boolean = error("Unexpected vehicle retry")

                    override fun requestBackground(
                        expected: PracticeSession,
                        canStart: () -> Boolean,
                    ): Boolean {
                        backgroundCalls.incrementAndGet()
                        error("Unexpected FGS")
                    }

                    override fun stopBackground(expectedRunId: String): Boolean = error("Unexpected FGS stop")

                    override suspend fun recordInService(
                        runId: String,
                        sessionId: String,
                    ): Unit = error("Unexpected FGS")

                    override fun startFailed(runId: String): Unit = error("Unexpected FGS acknowledgement")
                }
            val models =
                listOf<ViewModel>(
                    HomeViewModel(vehicleGateway, navigation, restrictions, store.preparation, store.catalog),
                    SessionViewModel(vehicleGateway, restrictions, store.preparation, store.catalog, store.sessions, clock),
                    BackgroundRecordingViewModel(background),
                    NavigationScreenViewModel(vehicleGateway, restrictions, navigation),
                    RecentVehicleObservationViewModel(vehicleGateway, restrictions, store.sessions, store.observations),
                    SessionMemoViewModel(vehicleGateway, restrictions, store.sessions, store.memos),
                )
            val owner =
                object : ViewModelStoreOwner {
                    override val viewModelStore = viewModels
                }
            val provider =
                ViewModelProvider(
                    owner,
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T =
                            (models.singleOrNull { it.javaClass == modelClass } ?: error("Unregistered controlled ViewModel")) as T
                    },
                )
            models.forEach { provider[it.javaClass] }
            recordingModel = LocationRecordingViewModel(controller, store.sessions, gateway, vehicleGateway, restrictions)
            historyModel =
                SessionHistoryViewModel(
                    vehicleGateway,
                    restrictions,
                    store.sessions,
                    store.deletions,
                    store.observations,
                    store.memos,
                    UnexpectedHistoryRecordingRunRepository,
                    store.courseCompletions,
                    store.historyMemoWriter,
                )
            locationHistoryModel = LocationHistoryViewModel(store.sessions, store.locationRecordings, vehicleGateway, restrictions)
            // Add explicitly passed models so final ViewModelStore.clear closes all their scopes too.
            val additional = listOf<ViewModel>(recordingModel, historyModel, locationHistoryModel)
            val additionalProvider =
                ViewModelProvider(
                    owner,
                    object : ViewModelProvider.Factory {
                        @Suppress("UNCHECKED_CAST")
                        override fun <T : ViewModel> create(modelClass: Class<T>): T = additional.single { it.javaClass == modelClass } as T
                    },
                )
            additional.forEach { additionalProvider[it.javaClass] }
            hostJob =
                scope.launch { lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { controller.recordWhileStarted() } }
            compose.activity.setContent {
                if (visible.value) {
                    CompositionLocalProvider(
                        LocalLifecycleOwner provides lifecycleOwner,
                        LocalViewModelStoreOwner provides owner,
                    ) {
                        HanGilTheme {
                            when (val current = screen.value) {
                                Screen.Home ->
                                    HomeRoute(
                                        onPrepare = { error("Unexpected preparation navigation") },
                                        onMapPreview = { error("Unexpected map") },
                                        onHistory = { screen.value = Screen.History },
                                        onPrepareNext = { error("Unexpected preparation change") },
                                        onLocationRecording = { screen.value = Screen.Recording },
                                    )
                                Screen.Recording ->
                                    LocationRecordingRoute(
                                        onClose = { screen.value = Screen.Home },
                                        onRequestPermission = {
                                            platformPermissionRequests.incrementAndGet()
                                            error("Controlled flow must never request a real permission")
                                        },
                                        viewModel = recordingModel,
                                    )
                                Screen.History ->
                                    SessionHistoryRoute(
                                        onClose = { screen.value = Screen.Home },
                                        viewModel = historyModel,
                                        onOpenLocations = { source ->
                                            selectedHistory = source
                                            historyOpens.incrementAndGet()
                                            screen.value =
                                                Screen.Locations(source)
                                        },
                                    )
                                is Screen.Locations ->
                                    LocationHistoryRoute(
                                        current.source,
                                        { screen.value = Screen.History },
                                        locationHistoryModel,
                                    )
                            }
                        }
                    }
                }
            }
        }

        fun move(state: Lifecycle.State) {
            instrument.runOnMainSync {
                lifecycleOwner.moveTo(state)
                assertEquals(state, lifecycleOwner.lifecycle.currentState)
            }
            compose.waitForIdle()
        }

        fun drive(driving: Boolean) {
            instrument.runOnMainSync {
                vehicle.value =
                    if (driving) parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE)) else parked()
                ; ux.value =
                    DrivingRestrictions(if (driving) ParkingState.NOT_PARKED else ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
            }
            compose.waitForIdle()
        }

        fun sample(latitude: Double) =
            LocationUpdateReading.Sample(LocationUpdateSample(latitude, 34.5, -2.0, 421f, -5f, -42, elapsed.incrementAndGet()))

        fun emit(
            reading: LocationUpdateReading,
            stream: ControlledGateway.Stream = gateway.streams.last(),
        ) = runBlocking {
            if (stream.closed.get()) {
                // Deliberately noncooperative old delivery exercises the real controller's retired owner guard.
                withContext(NonCancellable + Dispatchers.Main.immediate) { stream.collector.emit(reading) }
            } else {
                val pending = ControlledGateway.Pending(reading, CompletableDeferred())
                stream.queue.send(pending)
                withTimeout(20_000) { pending.completed.await() }
            }
        }

        fun awaitSource(source: PracticeSession) {
            compose.waitUntil(20_000) { sourceReceipt.get() == source }
        }

        fun active(): PracticeSession =
            runBlocking {
                withTimeout(20_000) {
                    store.sessions
                        .observeSessions()
                        .first { rows -> rows.any { it.isActive } }
                        .single { it.isActive }
                }
            }

        fun awaitDisplayedSource(source: PracticeSession) {
            compose.waitUntil(20_000) { (recordingModel.uiState.value as? LocationRecordingUiState.Ready)?.source == source }
            waitTag("location_recording_start")
        }

        fun ended(id: String): PracticeSession {
            compose.waitUntil(20_000) {
                runBlocking {
                    store.sessions.observeSessions().first().any {
                        it.sessionId ==
                            id &&
                            !it.isActive
                    }
                }
            }
            return runBlocking {
                store.sessions
                    .observeSessions()
                    .first()
                    .single { it.sessionId == id }
            }
        }

        fun snapshot(source: PracticeSession): LocationRecordingSnapshot =
            runBlocking {
                withTimeout(20_000) { store.locationRecordings.observe(source.sessionId).first() }
            }

        fun awaitEvents(
            source: PracticeSession,
            count: Int,
        ) {
            compose.waitUntil(20_000) { snapshot(source).events.size == count }
        }

        fun awaitRecording(): LocationRecordingRun {
            compose.waitUntil(20_000) { controller.state.value is LocationRecordingState.Recording }
            return (controller.state.value as LocationRecordingState.Recording).run
        }

        fun awaitTerminal(
            source: PracticeSession,
            reason: LocationRecordingStopReason,
        ) {
            compose.waitUntil(20_000) {
                snapshot(source).runs.lastOrNull()?.let {
                    it.status !=
                        LocationRecordingRunStatus.OPEN &&
                        it.stopReason == reason
                } ==
                    true
            }
            compose.waitUntil(20_000) { gateway.active.get() == 0 }
        }

        fun startOwned(): PracticeSession =
            runBlocking {
                val practice = (store.catalog.resolve(selection()) as Preparation.Ready).practice
                (store.sessions.start(practice, clock.millis()) { true } as SessionStartResult.Started).session
            }

        fun finishOwned(source: PracticeSession): PracticeSession =
            runBlocking {
                (
                    store.sessions.finish(source.sessionId, maxOf(clock.millis(), source.startedAtEpochMillis)) {
                        true
                    } as SessionFinishResult.Finished
                ).session
            }

        fun replaceActive(source: PracticeSession): PracticeSession {
            finishOwned(source)
            return startOwned()
        }

        fun seedHistory(source: PracticeSession) =
            runBlocking {
                fun receipt() = elapsed.incrementAndGet()

                suspend fun begin(id: String) =
                    (
                        store.locationRecordings.begin(source, id, "controlled-old-process", clock.millis(), receipt()) {
                            true
                        } as LocationRecordingBeginResult.Begun
                    ).run

                suspend fun append(
                    run: LocationRecordingRun,
                    reading: LocationUpdateReading,
                ) {
                    assertTrue(
                        store.locationRecordings.append(source, run, reading, clock.millis()) {
                            true
                        } is io.github.hangillab.hangil.core.domain.LocationRecordingAppendResult.Appended,
                    )
                }

                suspend fun end(
                    run: LocationRecordingRun,
                    reason: LocationRecordingStopReason,
                    unknown: Boolean = false,
                ) {
                    assertTrue(
                        store.locationRecordings.end(
                            run,
                            reason.status,
                            reason,
                            if (unknown) null else clock.millis(),
                            if (unknown) null else receipt(),
                        ) is LocationRecordingEndResult.Ended,
                    )
                }
                compose.waitUntil(20_000) { controller.state.value == LocationRecordingState.Idle }
                val samples = begin("controlled-samples")
                append(samples, sample(12.5))
                append(samples, sample(22.5))
                append(samples, LocationUpdateReading.Empty(receipt()))
                end(samples, LocationRecordingStopReason.USER_STOP)
                val error = begin("controlled-error")
                append(
                    error,
                    LocationUpdateReading.Error(LocationUpdateErrorOrigin.SDK_CALLBACK, IllegalStateException::class.java.name, receipt()),
                )
                end(error, LocationRecordingStopReason.SDK_ERROR, unknown = true)
                val noUpdate = begin("controlled-no-update")
                append(noUpdate, LocationUpdateReading.NoUpdate(receipt()))
                end(noUpdate, LocationRecordingStopReason.NO_UPDATE)
                begin("controlled-interrupted")
                store.locationRecordings.recoverInterrupted(processId)
            }

        fun assertNoPlatformActions() {
            assertEquals(0, platformPermissionRequests.get())
            assertEquals(0, navigationCalls.get())
            assertEquals(0, backgroundCalls.get())
        }

        fun closeController() =
            runBlocking {
                hostJob?.cancelAndJoin()
                scopeJob.cancelAndJoin()
                check(gateway.active.get() == 0) { "Controlled location collector must close before owned DB cleanup" }
                store.sessions.observeSessions().first().forEach { source ->
                    check(
                        store.locationRecordings
                            .observe(source.sessionId)
                            .first()
                            .runs
                            .none { it.status == LocationRecordingRunStatus.OPEN },
                    ) {
                        "Owned location runs must be terminal before fixture cleanup"
                    }
                }
            }
    }

    private class ControlledLifecycleOwner : LifecycleOwner {
        private val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry

        fun moveTo(state: Lifecycle.State) {
            registry.currentState = state
        }
    }

    private class ControlledGateway : LocationUpdateGateway {
        val permission = AtomicBoolean(true)
        val active = AtomicInteger()
        val streams = CopyOnWriteArrayList<Stream>()

        override fun hasPermission(): Boolean = permission.get()

        override fun updates(
            canContinue: () -> Boolean,
            onRegistered: () -> Unit,
            onCleanupFailure: (Throwable) -> Unit,
        ): Flow<LocationUpdateReading> =
            object : Flow<LocationUpdateReading> {
                override suspend fun collect(collector: FlowCollector<LocationUpdateReading>) {
                    if (!canContinue()) return
                    val stream = Stream(collector, canContinue)
                    streams.add(stream)
                    active.incrementAndGet()
                    try {
                        onRegistered()
                        for (pending in stream.queue) {
                            try {
                                collector.emit(pending.reading)
                                pending.completed.complete(Unit)
                            } catch (
                                failure: Throwable,
                            ) {
                                pending.completed.completeExceptionally(failure)
                                throw failure
                            }
                        }
                    } finally {
                        stream.closed.set(true)
                        stream.queue.close()
                        active.decrementAndGet()
                    }
                }
            }

        class Stream(
            val collector: FlowCollector<LocationUpdateReading>,
            val canContinue: () -> Boolean,
            val closed: AtomicBoolean = AtomicBoolean(false),
        ) {
            val queue = Channel<Pending>(8)
        }

        class Pending(
            val reading: LocationUpdateReading,
            val completed: CompletableDeferred<Unit>,
        )
    }

    private sealed interface Screen {
        data object Home : Screen

        data object Recording : Screen

        data object History : Screen

        data class Locations(
            val source: PracticeSession,
        ) : Screen
    }

    private companion object {
        fun selection() = PracticeSelection("commute", "neighborhood", 1, 1, "sequence")

        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))
    }
}
