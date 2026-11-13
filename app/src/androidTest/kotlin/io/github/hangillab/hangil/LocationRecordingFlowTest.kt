package io.github.hangillab.hangil

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Point
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.Display
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
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
import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.ForegroundLocationRecordingController
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.LocationRecordingBeginResult
import io.github.hangillab.hangil.core.domain.LocationRecordingEndResult
import io.github.hangillab.hangil.core.domain.LocationRecordingEvent
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
import io.github.hangillab.hangil.core.domain.NextPracticeSeed
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.RecordingController
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionDeletionResult
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionReviewRoad
import io.github.hangillab.hangil.core.domain.SessionReviewRoadRepository
import io.github.hangillab.hangil.core.domain.SessionReviewRoadWriteResult
import io.github.hangillab.hangil.core.domain.SessionReviewSample
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
import io.github.hangillab.hangil.feature.home.SessionReviewRoadRoute
import io.github.hangillab.hangil.feature.home.SessionReviewRoadViewModel
import io.github.hangillab.hangil.feature.home.SessionViewModel
import io.github.hangillab.hangil.feature.map.StoredLocationMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.MapLibre
import org.maplibre.android.RenderingEngine
import org.maplibre.android.WellKnownTileServer
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.IdentityHashMap
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import io.github.hangillab.hangil.feature.home.R as HomeR
import io.github.hangillab.hangil.feature.map.R as MapR
import org.maplibre.geojson.Point as GeoJsonPoint

/**
 * Actual product Routes, controller and UUID-owned Room, with controlled vehicle/UX/location inputs.
 * The Activity stays RESUMED. An injected LifecycleRegistry controls PAUSE/STOP Main-host policy;
 * this is not actual OS Main STOP, SDK callback receipt, a permission grant or an FGS test.
 * Every ViewModel is explicitly preseeded: this fixture never uses the production Hilt graph/DB.
 * Raw detail assertions and optional visuals use controlled inputs without Pleos SDK, permission or provider mutation.
 * The opt-in stored-run check uses actual MapLibre rendering and external tiles for public landmark fixtures.
 * An explicit resource scope changes this instrumented process before composition and restores shared resources.
 * It does not prove OS setting transitions or the real SDK Main in dark mode.
 */
@RunWith(AndroidJUnit4::class)
@OptIn(InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
class LocationRecordingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()
    private val instrument get() = InstrumentationRegistry.getInstrumentation()
    private var actualHost: ComponentCatalogActivity? = null
    private var actualHostDisplay: Int? = null
    private val activityNumbers = IdentityHashMap<ComponentCatalogActivity, Int>()
    private var originalGuardObservation: JSONObject? = null
    private var catalogResourceOverrideRequested = false
    private val catalogResourceOverrideApplied = AtomicBoolean(false)
    private val catalogResourceOverrideRestored = AtomicBoolean(false)
    private val catalogResourceOverride = AtomicReference<CatalogResourceOverride?>(null)
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
            val args = InstrumentationRegistry.getArguments()
            val configuration = compose.activity.resources.configuration
            val dark = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            args.getString("expectedDark")?.let { assertEquals(it.toBooleanStrict(), dark) }
            args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(), configuration.fontScale, 0.01f) }

            val homeStoppedTag = "home_location_recording_host_stopped"
            val detailStoppedTag = "location_recording_host_stopped"
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
            click("location_recording_back")
            assertText("navigation_screen_location_recording_stop", HomeR.string.navigation_location_recording_stop)
            assertTrue(nodes(homeStoppedTag).isEmpty())
            assertEquals(firstRun, (f.controller.state.value as LocationRecordingState.Recording).run)
            f.move(Lifecycle.State.CREATED)
            f.awaitTerminal(firstSource, LocationRecordingStopReason.HOST_STOPPED)
            assertEquals(0, f.gateway.active.get())
            f.move(Lifecycle.State.RESUMED)
            f.awaitSource(firstSource)
            assertText(homeStoppedTag, HomeR.string.location_recording_stopped_on_return)
            assertEquals(listOf(firstStream), f.gateway.streams.toList())
            assertEquals(0, f.gateway.active.get())
            f.drive(true)
            waitTag("home_locked_title")
            assertTrue(nodes(homeStoppedTag).isEmpty())
            assertTrue(nodes("home_location_recording").isEmpty())
            f.drive(false)
            assertText(homeStoppedTag, HomeR.string.location_recording_stopped_on_return)
            visual("host-stopped-home", homeStoppedTag)

            click("home_location_recording")
            assertText(detailStoppedTag, HomeR.string.location_recording_stopped_on_return)
            waitTag("location_recording_start")
            compose
                .onNodeWithTag("location_recording_start")
                .assertTextContains(instrument.targetContext.getString(HomeR.string.location_recording_restart))
            visual("host-stopped-detail", detailStoppedTag, "location_recording_start")
            invoke(pausedStart)
            assertEquals(1, f.snapshot(firstSource).runs.size)
            assertEquals(listOf(firstStream), f.gateway.streams.toList())
            assertEquals(0, f.gateway.active.get())
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
            assertTrue(nodes(detailStoppedTag).isEmpty())
            invoke(retiredEnable)
            assertTrue(f.snapshot(replacement).runs.isEmpty())
            assertTrue(f.snapshot(newest).runs.isEmpty())
            assertEquals(0, f.gateway.active.get())
            f.emit(f.sample(32.5), f.gateway.streams.last())
            assertTrue(f.snapshot(newest).events.isEmpty())
            click("location_recording_back")
            waitTag("home_location_recording")
            assertTrue(nodes(homeStoppedTag).isEmpty())
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

    @Test fun storedStopReasonsRemainDistinctAcrossSelectionLocksAndReadOnlyHistory() =
        fixture { f ->
            val expected =
                listOf(
                    LocationRecordingStopReason.USER_STOP to HomeR.string.location_history_reason_user_stop,
                    LocationRecordingStopReason.HOST_STOPPED to HomeR.string.location_history_reason_host_stopped,
                    LocationRecordingStopReason.SESSION_FINISHED to HomeR.string.location_history_reason_session_finished,
                    LocationRecordingStopReason.SOURCE_CHANGED to HomeR.string.location_history_reason_source_changed,
                    LocationRecordingStopReason.PERMISSION_DENIED to HomeR.string.location_history_reason_permission_denied,
                    LocationRecordingStopReason.UNSUPPORTED to HomeR.string.location_history_reason_unsupported,
                    LocationRecordingStopReason.NO_UPDATE to HomeR.string.location_history_reason_no_update,
                    LocationRecordingStopReason.SDK_ERROR to HomeR.string.location_history_reason_sdk_error,
                    LocationRecordingStopReason.ADAPTER_ERROR to HomeR.string.location_history_reason_adapter_error,
                    LocationRecordingStopReason.WRITE_ERROR to HomeR.string.location_history_reason_write_error,
                    LocationRecordingStopReason.LIMIT_REACHED to HomeR.string.location_history_reason_limit_reached,
                    LocationRecordingStopReason.PROCESS_INTERRUPTED to HomeR.string.location_history_reason_process_interrupted,
                )
            assertEquals(LocationRecordingStopReason.entries.toSet(), expected.map { it.first }.toSet())
            val labels =
                (expected.map { it.second } + HomeR.string.location_history_reason_unrecorded).map {
                    instrument.targetContext.getString(it)
                }
            assertEquals(
                "Every stored reason and unrecorded state must have its own displayed meaning",
                labels.size,
                labels.distinct().size,
            )
            val source = f.startOwned()
            f.awaitSource(source)
            val open = f.seedStopReasonHistory(source, expected.map { it.first })
            val ended = f.finishOwned(source)
            val before = f.snapshot(ended)
            val sessionsBefore =
                runBlocking {
                    f.store.sessions
                        .observeSessions()
                        .first()
                }
            val preparationBefore =
                runBlocking {
                    f.store.preparation
                        .observeSelection()
                        .first()
                }
            val sequencesBefore = f.store.sequenceSnapshot()
            assertEquals(expected.map { it.first }, before.runs.dropLast(1).map { it.stopReason })
            assertEquals(open, before.runs.last())
            assertEquals(LocationRecordingRunStatus.OPEN, open.status)
            assertEquals(null, open.stopReason)
            assertEquals(null, open.endedAtEpochMillis)
            f.gateway.permission.set(false) // Reading these stored fixtures must not request location access.
            val permissionBefore = f.gateway.permission.get()
            compose.runOnIdle { f.screen.value = Screen.History }
            waitTag("history_location_recording")
            click("history_location_recording")
            waitTag("location_history_content")
            assertEquals(ended, f.selectedHistory)
            assertText("location_history_run_state", HomeR.string.location_history_open)
            assertText("location_history_stop_reason", HomeR.string.location_history_reason_unrecorded)
            waitTag("location_history_unknown_end")
            visual("stored-reason-unrecorded", "location_history_stop_reason", "location_history_unknown_end")

            val stalePrevious = clickAction("location_history_run_previous")
            click("location_history_run_previous")
            assertText("location_history_stop_reason", HomeR.string.location_history_reason_process_interrupted)
            invoke(stalePrevious)
            assertText("location_history_stop_reason", HomeR.string.location_history_reason_process_interrupted)
            expected.asReversed().forEachIndexed { index, (reason, resource) ->
                assertText("location_history_stop_reason", resource)
                if (reason == LocationRecordingStopReason.PROCESS_INTERRUPTED) waitTag("location_history_unknown_end")
                if (reason ==
                    LocationRecordingStopReason.LIMIT_REACHED
                ) {
                    assertTrue(hasTextNode(instrument.targetContext.getString(HomeR.string.location_history_limit)))
                }
                visual("stored-reason-${reason.name.lowercase()}", "location_history_stop_reason")
                assertEquals("Selection is a stored read, never a source mutation", before, f.snapshot(ended))
                if (index < expected.lastIndex) click("location_history_run_previous")
            }
            val staleDriveNext = clickAction("location_history_run_next")
            f.drive(true)
            waitTag("location_history_locked")
            assertTrue(nodes("location_history_stop_reason").isEmpty())
            invoke(staleDriveNext)
            f.drive(false)
            waitTag("location_history_locked")
            invoke(staleDriveNext)
            assertTrue("A retired D/P callback cannot reveal a stored reason", nodes("location_history_stop_reason").isEmpty())
            click("location_history_back")
            click("history_location_recording")
            waitTag("location_history_content")
            assertText("location_history_stop_reason", HomeR.string.location_history_reason_unrecorded)

            val staleUxPrevious = clickAction("location_history_run_previous")
            instrument.runOnMainSync { f.ux.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.RESTRICTED) }
            compose.waitForIdle()
            waitTag("location_history_locked")
            assertTrue(nodes("location_history_stop_reason").isEmpty())
            invoke(staleUxPrevious)
            instrument.runOnMainSync { f.ux.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED) }
            compose.waitForIdle()
            waitTag("location_history_locked")
            invoke(staleUxPrevious)
            assertTrue("UX becoming allowed cannot revive an old reason callback", nodes("location_history_stop_reason").isEmpty())
            click("location_history_back")
            click("history_location_recording")
            waitTag("location_history_content")
            assertText("location_history_stop_reason", HomeR.string.location_history_reason_unrecorded)
            waitTag("location_history_unknown_end")
            assertEquals(before, f.snapshot(ended))
            assertEquals(
                sessionsBefore,
                runBlocking {
                    f.store.sessions
                        .observeSessions()
                        .first()
                },
            )
            assertEquals(
                preparationBefore,
                runBlocking {
                    f.store.preparation
                        .observeSelection()
                        .first()
                },
            )
            assertEquals(sequencesBefore, f.store.sequenceSnapshot())
            assertEquals(0, f.store.foreignKeyViolationCount())
            assertEquals(permissionBefore, f.gateway.permission.get())
            assertTrue("Stored reads must never create a location update stream", f.gateway.streams.isEmpty())
            assertEquals(0, f.gateway.active.get())
            f.assertNoPlatformActions()
            phase("CONTROLLED_STORED_STOP_REASONS_OPEN_LOCKS_AND_PRESERVATION")
        }

    @Test fun storedPointOffersCourseRoadsAndSavesTheChosenRoadWithTheExactSample() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("manualStoredLocationMap") == "true")
        fixture(mapEnabled = true) { f ->
            click("session_start")
            val source = f.active()
            f.awaitSource(source)
            click("home_location_recording")
            click("location_recording_start")
            f.awaitRecording()
            // The public test points go through the controlled update gateway and isolated Room database.
            val first = LocationUpdateSample(37.566735, 126.9759, -2.0, 421f, -5f, -42, f.elapsed.incrementAndGet())
            f.emit(LocationUpdateReading.Sample(first))
            for (longitude in listOf(126.9761, 126.9763)) {
                f.emit(
                    LocationUpdateReading.Sample(
                        first.copy(longitude = longitude, receivedAtElapsedRealtimeMillis = f.elapsed.incrementAndGet()),
                    ),
                )
            }
            f.emit(LocationUpdateReading.Empty(f.elapsed.incrementAndGet()))
            f.awaitEvents(source, 4)
            click("location_recording_stop")
            f.awaitTerminal(source, LocationRecordingStopReason.USER_STOP)
            click("location_recording_back")
            click("session_finish")
            val ended = f.ended(source.sessionId)
            val recorded = f.snapshot(ended)
            val expectedSample = SessionReviewSample.from(recorded.runs.single(), recorded.events[recorded.events.lastIndex - 1])
            val roads = checkNotNull(NextPracticeSeed.resolve(f.store.catalog, ended)).practice.course.roadSnapshots
            val suggestionTag = "stored_location_course_road_suggestion"
            val previewLegend = "stored_location_course_road_preview_legend"
            val nearerPreview = "stored_location_course_road_preview_0"
            val fartherPreview = "stored_location_course_road_preview_1"
            val sessionsBefore =
                runBlocking {
                    f.store.sessions
                        .observeSessions()
                        .first()
                }
            val preparationBefore =
                runBlocking {
                    f.store.preparation
                        .observeSelection()
                        .first()
                }
            val memoBefore =
                runBlocking {
                    f.store.memos
                        .observe(ended.sessionId)
                        .first()
                }
            f.gateway.permission.set(false)

            fun savedRoad() =
                runBlocking {
                    f.store.reviewRoads
                        .observe(ended.sessionId)
                        .first()
                }

            fun openSample() {
                click("history_location_recording")
                waitTag("location_history_content")
                click("location_history_event_previous")
                click("location_history_point_map")
            }

            fun previewRoad(
                tag: String,
                road: CourseRoadSnapshot,
            ): MapView {
                click(tag)
                waitTag(previewLegend)
                compose.onNodeWithTag(previewLegend, useUnmergedTree = true).assertTextContains(road.title, substring = true)
                return awaitStoredMap(recorded.runs.single(), recorded.events, expectedSample.event, road, expectedPreview = true)
            }

            fun assertPreviewDidNotWrite() {
                assertNull(savedRoad())
                assertEquals(recorded, f.snapshot(ended))
                assertEquals(
                    sessionsBefore,
                    runBlocking {
                        f.store.sessions
                            .observeSessions()
                            .first()
                    },
                )
                assertEquals(
                    preparationBefore,
                    runBlocking {
                        f.store.preparation
                            .observeSelection()
                            .first()
                    },
                )
                assertEquals(
                    memoBefore,
                    runBlocking {
                        f.store.memos
                            .observe(ended.sessionId)
                            .first()
                    },
                )
            }

            click("home_history")
            click("history_location_recording")
            waitTag("location_history_content")
            assertText("location_history_event_state", HomeR.string.location_history_callback_empty)
            click("location_history_point_map")
            waitTag("stored_location_map_selected")
            assertTrue(nodes("stored_location_course_roads").isEmpty())
            assertTrue(nodes(suggestionTag).isEmpty())
            assertNull(savedRoad())
            click("location_history_event_previous")
            click("location_history_point_map")
            val nearestFirst = listOf(roads[1] to 14, roads[0] to 88)
            for ((index, comparison) in nearestFirst.withIndex()) {
                val (road, meters) = comparison
                compose
                    .onNodeWithTag("stored_location_course_road_$index", useUnmergedTree = true)
                    .performScrollTo()
                    .assertIsDisplayed()
                    .assertTextContains(instrument.targetContext.getString(MapR.string.stored_map_course_road_meters, road.title, meters))
            }
            assertText(suggestionTag, MapR.string.stored_map_course_road_suggestion)
            compose.onNodeWithTag(suggestionTag, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            val nearerRoadTag = "stored_location_course_road_0"
            val fartherRoadTag = "stored_location_course_road_1"
            val comparisonTags =
                compose
                    .onAllNodes(
                        hasTestTag(nearerRoadTag) or hasTestTag(suggestionTag) or hasTestTag(fartherRoadTag),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes()
                    .map { it.config[SemanticsProperties.TestTag] }
            assertEquals(listOf(nearerRoadTag, suggestionTag, fartherRoadTag), comparisonTags)
            assertTrue(nodes(previewLegend).isEmpty())
            val nearerMap = previewRoad(nearerPreview, roads[1])
            assertPreviewDidNotWrite()
            val fartherMap = previewRoad(fartherPreview, roads[0])
            instrument.runOnMainSync {
                assertTrue("Switching local road previews should reuse the attached map", nearerMap === fartherMap)
                assertTrue(fartherMap.isAttachedToWindow)
            }
            assertPreviewDidNotWrite()
            click(fartherPreview)
            assertTrue(nodes(previewLegend).isEmpty())
            val normalMap = awaitStoredMap(recorded.runs.single(), recorded.events, expectedSample.event)
            instrument.runOnMainSync {
                assertFalse("Closing the preview should detach its renderer", fartherMap.isAttachedToWindow)
                assertTrue(normalMap.isAttachedToWindow)
            }
            assertPreviewDidNotWrite()

            val lockedMap = previewRoad(nearerPreview, roads[1])
            val saveTag = "stored_location_course_road_save_1"
            val stale = clickAction(saveTag)
            val stalePreview = clickAction(fartherPreview)
            f.drive(true)
            waitTag("location_history_locked")
            assertTrue(nodes(suggestionTag).isEmpty())
            assertTrue(nodes(previewLegend).isEmpty())
            assertNoStoredMap(lockedMap)
            invoke(stale)
            invoke(stalePreview)
            f.drive(false)
            waitTag("location_history_locked")
            assertTrue(nodes(suggestionTag).isEmpty())
            invoke(stale)
            invoke(stalePreview)
            assertPreviewDidNotWrite()
            click("location_history_back")
            openSample()
            waitTag(saveTag)
            invoke(stale)
            invoke(stalePreview)
            assertTrue(nodes(previewLegend).isEmpty())
            assertPreviewDidNotWrite()
            click(saveTag)
            waitTag("review_road_sample_current")
            val linked = checkNotNull(savedRoad())
            assertEquals(roads.first(), linked.road)
            assertEquals(expectedSample, linked.sampleAnchor)
            assertTrue(nodes("stored_location_course_roads").isEmpty())
            assertTrue(nodes(suggestionTag).isEmpty())
            assertEquals(recorded, f.snapshot(ended))
            visual("course-road-saved-with-selected-sample", "review_road_sample_current", "stored_location_map")
            val configuration = compose.activity.resources.configuration
            val dark = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            args.getString("expectedDark")?.let { assertEquals(it.toBooleanStrict(), dark) }
            args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(), configuration.fontScale, 0.01f) }
            f.assertNoPlatformActions()
        }
    }

    @Test fun savedRoadSectionFollowsLinkedSampleAcrossReopen() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("manualStoredLocationMap") == "true")
        fixture(mapEnabled = true) { f ->
            click("session_start")
            val source = f.active()
            f.awaitSource(source)
            click("home_location_recording")
            click("location_recording_start")
            f.awaitRecording()
            val first = LocationUpdateSample(37.566735, 126.9759, -2.0, 421f, -5f, -42, f.elapsed.incrementAndGet())
            f.emit(LocationUpdateReading.Sample(first))
            for (longitude in listOf(126.97608, 126.9763)) {
                f.emit(
                    LocationUpdateReading.Sample(
                        first.copy(longitude = longitude, receivedAtElapsedRealtimeMillis = f.elapsed.incrementAndGet()),
                    ),
                )
            }
            f.emit(LocationUpdateReading.Empty(f.elapsed.incrementAndGet()))
            f.awaitEvents(source, 4)
            click("location_recording_stop")
            f.awaitTerminal(source, LocationRecordingStopReason.USER_STOP)
            click("location_recording_back")
            click("session_finish")
            val ended = f.ended(source.sessionId)
            val recorded = f.snapshot(ended)
            val run = recorded.runs.single()
            val selected = recorded.events[2]
            val road = checkNotNull(NextPracticeSeed.resolve(f.store.catalog, ended)).practice.course.roadSnapshots[1]
            val bounds = first.longitude..((selected.reading as LocationUpdateReading.Sample).sample.longitude)
            f.gateway.permission.set(false)

            fun openLinkedSample() {
                click("history_location_recording")
                click("location_history_event_previous")
                click("location_history_point_map")
            }

            fun awaitSection(): MapView {
                val map = awaitStoredMap(run, recorded.events, selected, road, expectedSectionBounds = bounds)
                waitTag("stored_location_map_road_section")
                assertText("stored_location_map_road_section", MapR.string.stored_map_road_section)
                return map
            }

            click("home_history")
            openLinkedSample()
            assertText("stored_location_course_road_suggestion", MapR.string.stored_map_course_road_suggestion)
            click("stored_location_course_road_save_0")
            waitTag("review_road_sample_current")
            val saved =
                runBlocking {
                    checkNotNull(
                        f.store.reviewRoads
                            .observe(ended.sessionId)
                            .first(),
                    )
                }
            assertEquals(road, saved.road)
            assertEquals(SessionReviewSample.from(run, selected), saved.sampleAnchor)
            awaitSection()
            waitTag("stored_location_map_public_road_section_preview")
            click("stored_location_map_use_road_section")
            val withSection =
                runBlocking {
                    withTimeout(20_000) {
                        checkNotNull(
                            f.store.reviewRoads
                                .observe(ended.sessionId)
                                .first { it?.sampleAnchor?.roadSection != null },
                        )
                    }
                }
            val chosen = checkNotNull(withSection.sampleAnchor?.roadSection)
            assertEquals(road, withSection.road)
            assertEquals(saved.sampleAnchor?.withRoadSection(chosen), withSection.sampleAnchor)
            assertTrue(chosen.isValidFor(road))
            assertTrue(chosen.nodesOf(road).size < road.nodes.size)
            val firstMap = awaitSection()
            waitTag("stored_location_map_public_road_section_saved")
            click("location_history_back")
            assertNoStoredMap(firstMap)
            openLinkedSample()
            awaitSection()
            waitTag("stored_location_map_public_road_section_saved")
            click("stored_location_map_use_whole_road")
            waitTag("stored_location_map_use_road_section")
            val wholeRoad =
                runBlocking {
                    checkNotNull(
                        f.store.reviewRoads
                            .observe(ended.sessionId)
                            .first(),
                    )
                }
            assertEquals(saved.sampleAnchor, wholeRoad.sampleAnchor)
            click("stored_location_map_use_road_section")
            val reopened = awaitSection()
            waitTag("stored_location_map_public_road_section_saved")
            val savedAgain =
                runBlocking {
                    checkNotNull(
                        f.store.reviewRoads
                            .observe(ended.sessionId)
                            .first(),
                    )
                }
            assertEquals(chosen, savedAgain.sampleAnchor?.roadSection)

            click("location_history_event_previous")
            assertNoStoredMap(reopened)
            click("location_history_point_map")
            assertTrue(nodes("stored_location_map_road_section").isEmpty())
            assertTrue(nodes("stored_location_map_use_road_section").isEmpty())
            assertTrue(nodes("stored_location_map_use_whole_road").isEmpty())
            val otherSample = awaitStoredMap(run, recorded.events, recorded.events[1], road)
            f.drive(true)
            waitTag("location_history_locked")
            assertNoStoredMap(otherSample)
            assertEquals(
                savedAgain,
                runBlocking {
                    f.store.reviewRoads
                        .observe(ended.sessionId)
                        .first()
                },
            )
            assertEquals(recorded, f.snapshot(ended))
            val configuration = compose.activity.resources.configuration
            val dark = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            args.getString("expectedDark")?.let { assertEquals(it.toBooleanStrict(), dark) }
            args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(), configuration.fontScale, 0.01f) }
            f.assertNoPlatformActions()
        }
    }

    @Test fun storedReviewMapKeepsRoadAndSamplesWhenTheBasemapFails() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("manualStoredLocationOffline") == "true")
        val requests = AtomicInteger()
        val basemapAvailable = AtomicBoolean(false)
        val client =
            OkHttpClient
                .Builder()
                .addInterceptor { chain ->
                    val request = chain.request()
                    requests.incrementAndGet()
                    check(request.url.host == "tiles.openfreemap.org" && request.url.encodedPath.startsWith("/styles/"))
                    val available = basemapAvailable.get()
                    val json = """{"version":8,"sources":{},"layers":[{"id":"test-remote-background","type":"background"}]}"""
                    Response
                        .Builder()
                        .request(request)
                        .protocol(Protocol.HTTP_1_1)
                        .code(if (available) 200 else 404)
                        .message(if (available) "OK" else "Not Found")
                        .header("Cache-Control", "no-store, must-revalidate")
                        .body((if (available) json else "").toResponseBody())
                        .build()
                }.build()
        instrument.runOnMainSync {
            MapLibre.getInstance(
                instrument.targetContext.applicationContext,
                null,
                WellKnownTileServer.MapLibre,
                RenderingEngine.Type.OPENGL,
            )
        }
        HttpRequestUtil.setOkHttpClient(client)
        try {
            fixture(mapEnabled = true) { f ->
                click("session_start")
                val source = f.active()
                f.awaitSource(source)
                click("home_location_recording")
                click("location_recording_start")
                f.awaitRecording()
                val first = LocationUpdateSample(37.566735, 126.9759, -2.0, 421f, -5f, -42, f.elapsed.incrementAndGet())
                f.emit(LocationUpdateReading.Sample(first))
                f.emit(
                    LocationUpdateReading.Sample(
                        first.copy(longitude = 126.9763, receivedAtElapsedRealtimeMillis = f.elapsed.incrementAndGet()),
                    ),
                )
                f.emit(LocationUpdateReading.Empty(f.elapsed.incrementAndGet()))
                f.awaitEvents(source, 3)
                click("location_recording_stop")
                f.awaitTerminal(source, LocationRecordingStopReason.USER_STOP)
                click("location_recording_back")
                click("session_finish")
                val ended = f.ended(source.sessionId)
                val recorded = f.snapshot(ended)
                val run = recorded.runs.single()
                val (before, selected) = recorded.events
                val trace = before to selected
                val expectedSample = SessionReviewSample.from(run, selected)
                val expectedRoad = checkNotNull(NextPracticeSeed.resolve(f.store.catalog, ended)).practice.course.roadSnapshots[1]
                f.gateway.permission.set(false)

                fun openSample() {
                    click("history_location_recording")
                    click("location_history_event_previous")
                    click("location_history_point_map")
                }

                fun storedRoad() =
                    runBlocking {
                        f.store.reviewRoads
                            .observe(ended.sessionId)
                            .first()
                    }

                fun awaitLocal(road: CourseRoadSnapshot? = null): MapView =
                    awaitStoredMap(run, recorded.events, selected, road, trace, expectedOffline = true)

                click("home_history")
                openSample()
                val original = awaitLocal()
                waitTag("stored_location_map_show_background")
                assertText("stored_location_map_background_notice", MapR.string.stored_map_background_notice)
                assertEquals("Opening the stored map must not request an external style", 0, requests.get())
                assertNull(storedRoad())
                click("stored_location_course_road_save_0")
                waitTag("review_road_sample_current")
                val linked = checkNotNull(storedRoad())
                assertEquals(expectedRoad, linked.road)
                assertEquals(expectedSample, linked.sampleAnchor)
                val afterSave = awaitLocal(expectedRoad)
                instrument.runOnMainSync { assertFalse(original.isAttachedToWindow) }
                click("location_history_back")
                assertNoStoredMap(afterSave)
                openSample()
                val reopened = awaitLocal(expectedRoad)
                assertEquals("Saving or reopening the stored map must not opt in", 0, requests.get())
                click("stored_location_map_show_background")
                waitTag("stored_location_map_without_background")
                assertText("stored_location_map_without_background", MapR.string.stored_map_without_background)
                assertTrue(nodes("stored_location_map_failed").isEmpty())
                val fallback = awaitLocal(expectedRoad)
                val beforeRetry = requests.get()
                assertTrue("Only the explicit action should request the failing style", beforeRetry > 0)
                instrument.runOnMainSync { assertFalse(reopened.isAttachedToWindow) }

                val staleRetry = clickAction("stored_location_map_retry_background")
                basemapAvailable.set(true)
                click("stored_location_map_retry_background")
                compose.waitUntil(20_000) {
                    requests.get() > beforeRetry && nodes("stored_location_map_without_background").isEmpty() &&
                        nodes("stored_location_map_ready").size == 1
                }
                val restored = awaitStoredMap(run, recorded.events, selected, expectedRoad, trace)
                compose
                    .onNodeWithText(compose.activity.getString(MapR.string.map_sources), useUnmergedTree = true)
                    .performScrollTo()
                    .assertIsDisplayed()
                val configuration = compose.activity.resources.configuration
                val dark = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                if (dark) {
                    compose
                        .onNodeWithTag("map_dark_design_credit", useUnmergedTree = true)
                        .performScrollTo()
                        .assertIsDisplayed()
                        .assertTextContains("CartoDB Basemaps", substring = true)
                } else {
                    assertTrue(nodes("map_dark_design_credit").isEmpty())
                }
                instrument.runOnMainSync { assertFalse(fallback.isAttachedToWindow) }
                val staleHide = clickAction("stored_location_map_hide_background")
                click("stored_location_map_hide_background")
                val hidden = awaitLocal(expectedRoad)
                instrument.runOnMainSync { assertFalse(restored.isAttachedToWindow) }
                val afterHide = requests.get()
                invoke(staleHide)
                compose.waitForIdle()
                assertTrue(nodes("stored_location_map_show_background").size == 1)
                assertEquals("A retained hide action must not enable the external style", afterHide, requests.get())
                val staleShow = clickAction("stored_location_map_show_background")
                f.drive(true)
                waitTag("location_history_locked")
                assertNoStoredMap(hidden)
                invoke(staleRetry)
                invoke(staleShow)
                assertNoStoredMap()
                assertEquals("Retired controls must not request another style", afterHide, requests.get())
                assertEquals(linked, storedRoad())
                assertEquals(recorded, f.snapshot(ended))
                args.getString("expectedDark")?.let { assertEquals(it.toBooleanStrict(), dark) }
                args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(), configuration.fontScale, 0.01f) }
                f.assertNoPlatformActions()
            }
        } finally {
            HttpRequestUtil.setOkHttpClient(null)
        }
    }

    @Test fun storedPointMapOpensOnlyForTheSelectedSampleAndNeverReopensAfterSelectionOrAccessChanges() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("manualStoredLocationMap") == "true")
        fixture(mapEnabled = true) { f ->
            click("session_start")
            val source = f.active()
            f.awaitSource(source)
            click("home_location_recording")
            click("location_recording_start")
            f.awaitRecording()
            // Public landmark fixtures enter through the controlled update gateway, not a device location.
            val cityHall = LocationUpdateSample(37.5666904, 126.9784222, -2.0, 421f, -5f, -42, f.elapsed.incrementAndGet())
            // Equal coordinates still represent two separate stored callbacks with distinct IDs and ordinals.
            val samePlaceLater =
                cityHall.copy(
                    headingRaw = -17f,
                    receivedAtElapsedRealtimeMillis = f.elapsed.incrementAndGet(),
                )
            f.emit(LocationUpdateReading.Sample(cityHall))
            f.emit(LocationUpdateReading.Sample(samePlaceLater))
            f.emit(LocationUpdateReading.Empty(f.elapsed.incrementAndGet()))
            f.awaitEvents(source, 3)
            click("location_recording_stop")
            f.awaitTerminal(source, LocationRecordingStopReason.USER_STOP)
            click("location_recording_back")
            click("session_finish")
            val ended = f.ended(source.sessionId)
            val memoText = "테스트용 연습 전체 메모\n표본을 보며 함께 읽어요."
            val savedMemo = runBlocking { f.store.memos.save(ended.sessionId, memoText, null) { true } }
            assertTrue(savedMemo is io.github.hangillab.hangil.core.domain.SessionMemoWriteResult.Saved)
            val memoBefore =
                runBlocking {
                    f.store.memos
                        .observe(ended.sessionId)
                        .first()
                }
            val roads = checkNotNull(NextPracticeSeed.resolve(f.store.catalog, ended)).practice.course.roadSnapshots
            val roadMemo = "테스트용 선택 도로 메모\n실제 이동 매칭이 아니에요."
            val firstSaved =
                runBlocking {
                    f.store.reviewRoads.save(ended, roads[0], null, memoText = roadMemo) { true }
                } as SessionReviewRoadWriteResult.Saved
            assertEquals(ended, firstSaved.review.source)
            f.gateway.permission.set(false)
            val sessionsBefore =
                runBlocking {
                    f.store.sessions
                        .observeSessions()
                        .first()
                }
            val preparationBefore =
                runBlocking {
                    f.store.preparation
                        .observeSelection()
                        .first()
                }
            val storedBefore = f.snapshot(ended)
            val storedRun = storedBefore.runs.single()
            val firstSample =
                SessionReviewSample.from(
                    storedRun,
                    storedBefore.events.single {
                        (it.reading as? LocationUpdateReading.Sample)?.sample ==
                            cityHall
                    },
                )
            val secondSample =
                SessionReviewSample.from(
                    storedRun,
                    storedBefore.events.single {
                        (it.reading as? LocationUpdateReading.Sample)?.sample ==
                            samePlaceLater
                    },
                )

            fun awaitRunMap(
                selected: LocationRecordingEvent?,
                road: CourseRoadSnapshot? = null,
            ): MapView = awaitStoredMap(storedRun, storedBefore.events, selected, road)

            fun storedRoad(): SessionReviewRoad =
                runBlocking {
                    checkNotNull(
                        f.store.reviewRoads
                            .observe(ended.sessionId)
                            .first(),
                    )
                }
            var sequencesBefore = f.store.sequenceSnapshot()
            val streamsBefore = f.gateway.streams.size

            click("home_history")
            click("history_location_recording")
            waitTag("location_history_content")
            assertEquals(ended, f.selectedHistory)
            assertText("location_history_event_state", HomeR.string.location_history_callback_empty)
            waitTag("location_history_point_map")
            assertNoStoredMap()
            assertEquals("A collapsed run map must not start a road query", 0, f.reviewReadAttempts.get())
            click("location_history_point_map")
            val emptySelectedMap = awaitRunMap(storedBefore.events.last(), firstSaved.review.road)
            assertTrue(nodes("stored_location_map_road_distance").isEmpty())
            assertTrue("A callback without a coordinate cannot offer a sample association", nodes("review_road_sample_link").isEmpty())
            visual("stored-run-map-with-empty-selected-callback", "stored_location_map")
            val readsAfterEmptyMap = f.reviewReadAttempts.get()

            click("location_history_event_previous")
            waitTag("location_history_point_map_notice")
            assertText("location_history_point_map_notice", HomeR.string.location_history_point_map_notice)
            assertNoStoredMap(emptySelectedMap)
            click("location_history_event_previous")
            assertText("location_history_source", HomeR.string.location_history_source)
            assertText("location_history_source_warning", HomeR.string.location_history_uncertain)
            click("location_history_raw_details")
            waitTag("location_history_raw_content")
            assertTrue(hasTextNode(instrument.targetContext.getString(HomeR.string.location_history_sdk_time, "-42")))
            assertTrue(hasTextNode(instrument.targetContext.getString(HomeR.string.location_history_confidence, "-5.0")))
            assertNoStoredMap()
            assertEquals("Changing a collapsed run selection must not start a road query", readsAfterEmptyMap, f.reviewReadAttempts.get())
            val staleOpen = clickAction("location_history_point_map")
            click("location_history_point_map")
            val originalRoadMap = awaitRunMap(firstSample.event, firstSaved.review.road)
            compose
                .onNodeWithTag("stored_location_map_road_distance", useUnmergedTree = true)
                .performScrollTo()
                .assertIsDisplayed()
                .assertTextContains(instrument.targetContext.getString(MapR.string.stored_map_road_distance_meters, 35))
            assertText("review_road_comparison_scope", HomeR.string.review_road_comparison_scope)
            assertText("review_road_source", HomeR.string.review_road_comparison_source)
            assertTrue(hasTextNode(roadMemo))
            assertTrue(nodes("review_road_save").isEmpty() && nodes("review_road_clear").isEmpty())
            assertTrue(nodes("review_road_memo_edit").isEmpty() && nodes("review_road_memo_create").isEmpty())
            visual("stored-point-and-user-selected-road", "location_history_point_map_notice", "stored_location_map")
            compose
                .onNodeWithText(compose.activity.getString(MapR.string.map_road_attribution), useUnmergedTree = true)
                .performScrollTo()
                .assertIsDisplayed()
            assertFalse(hasTextNode(compose.activity.getString(MapR.string.map_sources)))
            assertTrue(nodes("map_dark_design_credit").isEmpty())
            val configuration = compose.activity.resources.configuration
            val dark = configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            args.getString("expectedDark")?.let { assertEquals(it.toBooleanStrict(), dark) }
            args.getString("expectedFontScale")?.let { assertEquals(it.toFloat(), configuration.fontScale, 0.01f) }

            waitTag("review_road_sample_absent")
            val staleFirstLink = clickAction("review_road_sample_link")
            f.sampleWriteFailure.set(1)
            click("review_road_sample_link")
            waitTag("review_road_failed")
            assertNoStoredMap(originalRoadMap)
            assertTrue(nodes("stored_location_map_road_distance").isEmpty())
            assertTrue(nodes("review_road_sample_feedback").isEmpty())
            assertEquals(firstSaved.review, storedRoad())
            assertEquals(1, f.sampleWriteAttempts.get())
            click("review_road_retry")
            waitTag("review_road_sample_absent")
            awaitRunMap(firstSample.event, firstSaved.review.road)
            assertEquals("A retry only reads; it must not replay the failed write", 1, f.sampleWriteAttempts.get())

            f.sampleWriteFailure.set(2)
            click("review_road_sample_link")
            waitTag("review_road_failed")
            assertNoStoredMap()
            assertTrue(nodes("review_road_sample_feedback").isEmpty())
            val committedWithoutReply = storedRoad()
            assertEquals(firstSample, committedWithoutReply.sampleAnchor)
            assertEquals(roadMemo, committedWithoutReply.memoText)
            assertEquals(2, f.sampleWriteAttempts.get())
            click("review_road_retry")
            waitTag("review_road_sample_current")
            awaitRunMap(firstSample.event, committedWithoutReply.road)
            assertEquals("Reading a committed result must not write it a second time", 2, f.sampleWriteAttempts.get())
            visual("explicit-current-sample-association", "review_road_sample_current", "stored_location_map")

            val memoChanged =
                runBlocking {
                    f.store.reviewRoads.save(
                        ended,
                        committedWithoutReply.road,
                        committedWithoutReply,
                        memoText = roadMemo + "\n원래 연결 유지",
                    ) { true }
                } as SessionReviewRoadWriteResult.Saved
            assertEquals(firstSample, memoChanged.review.sampleAnchor)
            waitTag("review_road_sample_current")
            awaitRunMap(firstSample.event, memoChanged.review.road)
            click("location_history_event_next")
            assertNoStoredMap()
            click("location_history_point_map")
            waitTag("review_road_sample_other")
            awaitRunMap(secondSample.event, memoChanged.review.road)
            val anotherActive = f.startOwned()
            compose.waitUntil(20_000) {
                nodes("review_road_sample_link").singleOrNull()?.config?.getOrNull(SemanticsProperties.Disabled) != null
            }
            compose.onNodeWithTag("review_road_sample_open", useUnmergedTree = true).assertIsEnabled()
            val mapBeforeJump = awaitRunMap(secondSample.event, memoChanged.review.road)
            val jumpSessions =
                runBlocking {
                    f.store.sessions
                        .observeSessions()
                        .first()
                }
            val jumpSequence = f.store.sequenceSnapshot()
            val jumpWrites = f.sampleWriteAttempts.get()
            val oldJump = clickAction("review_road_sample_open")
            visual("linked-sample-open-action", "review_road_sample_open")
            click("review_road_sample_open")
            assertLinkedEventVisible(firstSample.event.ordinal, storedBefore.events.size)
            assertNoStoredMap(mapBeforeJump)
            visual("linked-sample-opened-event", "location_history_event_heading")
            assertEquals(
                jumpSessions,
                runBlocking {
                    f.store.sessions
                        .observeSessions()
                        .first()
                },
            )
            assertEquals(
                preparationBefore,
                runBlocking {
                    f.store.preparation
                        .observeSelection()
                        .first()
                },
            )
            assertEquals(storedBefore, f.snapshot(ended))
            assertEquals(memoChanged.review, storedRoad())
            assertEquals(jumpSequence, f.store.sequenceSnapshot())
            assertEquals(jumpWrites, f.sampleWriteAttempts.get())
            assertEquals(streamsBefore, f.gateway.streams.size)
            assertTrue(f.snapshot(anotherActive).runs.isEmpty())
            invoke(oldJump)
            assertLinkedEventVisible(firstSample.event.ordinal, storedBefore.events.size)
            assertNoStoredMap()
            click("location_history_point_map")
            waitTag("review_road_sample_current")
            awaitRunMap(firstSample.event, memoChanged.review.road)
            click("location_history_event_next")
            assertNoStoredMap()
            click("location_history_point_map")
            waitTag("review_road_sample_other")
            val returnedMap = awaitRunMap(secondSample.event, memoChanged.review.road)
            invoke(oldJump)
            assertTrue(
                "An old jump must not borrow a later visit to the original event",
                awaitRunMap(secondSample.event, memoChanged.review.road) === returnedMap,
            )
            assertEquals(jumpWrites, f.sampleWriteAttempts.get())
            val anotherFinish =
                runBlocking { f.store.sessions.finish(anotherActive, maxOf(f.clock.millis(), anotherActive.startedAtEpochMillis)) { true } }
            check(anotherFinish is SessionFinishResult.Finished)
            val anotherEnded = anotherFinish.session
            val temporaryDeletion = runBlocking { f.store.deletions.prepare(anotherEnded) { true } }
            check(temporaryDeletion is SessionDeletionPreparation.Ready)
            assertEquals(anotherEnded, temporaryDeletion.target.session)
            assertTrue(temporaryDeletion.target.session.sessionId != ended.sessionId)
            assertEquals(SessionDeletionResult.Deleted, runBlocking { f.store.deletions.delete(temporaryDeletion.target) { true } })
            assertEquals(
                sessionsBefore,
                runBlocking {
                    f.store.sessions
                        .observeSessions()
                        .first()
                },
            )
            assertEquals(jumpSequence, f.store.sequenceSnapshot())
            // Only the intentional temporary fixture insert advanced counters; the jump did not.
            sequencesBefore = jumpSequence
            waitTag("review_road_sample_other")
            compose.waitUntil(20_000) {
                nodes("review_road_sample_link").singleOrNull()?.let { node ->
                    node.config.getOrNull(SemanticsProperties.Disabled) == null
                } == true
            }
            phase("LINKED_SAMPLE_JUMP_KEPT_OTHER_ACTIVE_SESSION_AND_FOCUSED_EVENT_WITHOUT_SCROLL_HELP")
            val writesBeforeStale = f.sampleWriteAttempts.get()
            invoke(staleFirstLink)
            assertEquals(writesBeforeStale, f.sampleWriteAttempts.get())
            assertEquals(firstSample, storedRoad().sampleAnchor)
            click("review_road_sample_link")
            waitTag("review_road_sample_current")
            awaitRunMap(secondSample.event, memoChanged.review.road)
            assertEquals(secondSample, storedRoad().sampleAnchor)
            assertEquals(memoChanged.review.memoText, storedRoad().memoText)
            click("location_history_back")
            click("history_location_recording")
            waitTag("location_history_content")
            click("location_history_event_previous")
            click("location_history_point_map")
            waitTag("review_road_sample_current")
            awaitRunMap(secondSample.event, memoChanged.review.road)
            click("review_road_sample_clear")
            waitTag("review_road_sample_absent")
            assertNull(storedRoad().sampleAnchor)
            assertEquals(memoChanged.review.memoText, storedRoad().memoText)
            click("review_road_sample_link")
            waitTag("review_road_sample_current")
            val beforeUxSampleClear = clickAction("review_road_sample_clear")
            val writesBeforeUx = f.sampleWriteAttempts.get()
            instrument.runOnMainSync { f.ux.value = f.ux.value.copy(uxRestrictionState = UxRestrictionState.RESTRICTED) }
            waitTag("location_history_locked")
            assertNoStoredMap()
            invoke(beforeUxSampleClear)
            instrument.runOnMainSync { f.ux.value = f.ux.value.copy(uxRestrictionState = UxRestrictionState.UNRESTRICTED) }
            waitTag("location_history_locked")
            invoke(beforeUxSampleClear)
            assertEquals(writesBeforeUx, f.sampleWriteAttempts.get())
            assertEquals(secondSample, storedRoad().sampleAnchor)
            click("location_history_back")
            click("history_location_recording")
            waitTag("location_history_content")
            click("location_history_event_previous")
            click("location_history_point_map")
            waitTag("review_road_sample_current")
            val beforeDriveSampleClear = clickAction("review_road_sample_clear")
            val writesBeforeDrive = f.sampleWriteAttempts.get()
            f.drive(true)
            waitTag("location_history_locked")
            invoke(beforeDriveSampleClear)
            f.drive(false)
            waitTag("location_history_locked")
            invoke(beforeDriveSampleClear)
            assertEquals(writesBeforeDrive, f.sampleWriteAttempts.get())
            assertEquals(secondSample, storedRoad().sampleAnchor)
            click("location_history_back")
            click("history_location_recording")
            waitTag("location_history_content")
            click("location_history_event_previous")
            click("location_history_point_map")
            waitTag("review_road_sample_current")
            val beforeStopSampleClear = clickAction("review_road_sample_clear")
            val writesBeforeStop = f.sampleWriteAttempts.get()
            f.move(Lifecycle.State.CREATED)
            assertNoStoredMap()
            f.move(Lifecycle.State.RESUMED)
            waitTag("location_history_content")
            invoke(beforeStopSampleClear)
            assertEquals(writesBeforeStop, f.sampleWriteAttempts.get())
            assertEquals(secondSample, storedRoad().sampleAnchor)

            // Read the saved anchor inside the editor that also owns road replacement.
            click("location_history_back")
            click("history_review_road")
            waitTag("review_road_sample_present")
            val beforeReplace = storedRoad()
            val readsBeforeDetails = f.reviewReadAttempts.get()
            val writesBeforeDetails = f.sampleWriteAttempts.get()
            val historyOpensBeforeDetails = f.historyOpens.get()
            val detailsAction = clickAction("review_road_sample_details")
            assertTrue(nodes("review_road_sample_details_content").isEmpty())
            visual("inline-linked-sample-closed", "review_road_sample_details")
            click("review_road_sample_details")
            waitTag("review_road_sample_details_content")
            val detailsText =
                descendants(nodes("review_road_sample_details_content").single()).flatMap { node ->
                    node.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .map { it.text }
                }
            val resources = instrument.targetContext.resources
            val linked = secondSample.sample
            val recordTime =
                Instant.ofEpochMilli(secondSample.event.observedAtEpochMillis).atZone(ZoneId.systemDefault()).format(
                    DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm:ss"),
                )
            val expectedDetails =
                listOf(
                    resources.getString(HomeR.string.review_road_sample_observation, secondSample.event.ordinal),
                    resources.getString(HomeR.string.location_history_record_time, recordTime),
                    resources.getString(HomeR.string.location_history_raw_warning),
                    resources.getString(HomeR.string.location_history_latitude, linked.latitude.toString()),
                    resources.getString(HomeR.string.location_history_longitude, linked.longitude.toString()),
                    resources.getString(HomeR.string.location_history_altitude, linked.altitudeRaw.toString()),
                    resources.getString(HomeR.string.location_history_heading, linked.headingRaw.toString()),
                    resources.getString(HomeR.string.location_history_confidence, linked.confidenceRaw.toString()),
                    resources.getString(HomeR.string.location_history_sdk_time, linked.sdkTimestampMillis.toString()),
                )
            assertTrue("The road card must show the exact linked callback's original values", detailsText.containsAll(expectedDetails))
            assertFalse(detailsText.contains(resources.getString(HomeR.string.location_history_heading, cityHall.headingRaw.toString())))
            assertNoStoredMap()
            visual("inline-linked-sample", "review_road_sample_details", "review_road_sample_observation")
            assertEquals("An inline reveal uses the already read anchor", readsBeforeDetails, f.reviewReadAttempts.get())
            assertEquals(writesBeforeDetails, f.sampleWriteAttempts.get())
            assertEquals(historyOpensBeforeDetails, f.historyOpens.get())
            assertEquals(streamsBefore, f.gateway.streams.size)
            assertEquals(beforeReplace, storedRoad())

            f.drive(true)
            waitTag("history_locked")
            invoke(detailsAction)
            assertTrue(nodes("review_road_sample_details_content").isEmpty())
            f.drive(false)
            waitTag("history_ready")
            click("history_review_road")
            waitTag("review_road_sample_present")
            invoke(detailsAction)
            assertTrue("An old reveal must not reopen after a parking lock", nodes("review_road_sample_details_content").isEmpty())
            val beforeReplaceDetails = clickAction("review_road_sample_details")
            click("review_road_sample_details")
            waitTag("review_road_sample_details_content")
            click("review_road_candidate_1")
            openRoadDiscardDialog()
            val cancelledReplacement = roadDiscardConfirmAction()
            dismissRoadDiscardDialog(confirm = false)
            invoke(cancelledReplacement)
            assertEquals(beforeReplace, storedRoad())
            openRoadDiscardDialog()
            dismissRoadDiscardDialog(confirm = true)
            runBlocking {
                withTimeout(20000) {
                    f.store.reviewRoads.observe(ended.sessionId).first {
                        it != null && it.road == roads[1] &&
                            it.sampleAnchor == null &&
                            it.memoText.isEmpty()
                    }
                }
            }
            val replaced = storedRoad()
            assertEquals(roads[1], replaced.road)
            assertNull(replaced.sampleAnchor)
            compose.waitUntil(20_000) {
                val title = nodes("review_road_stored_title").singleOrNull()
                title
                    ?.config
                    ?.getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .any { it.text == replaced.road.title } &&
                    nodes("review_road_sample_present").isEmpty()
            }
            invoke(beforeReplaceDetails)
            assertTrue("A replaced road must not reveal the old anchor", nodes("review_road_sample_details_content").isEmpty())
            assertTrue(nodes("review_road_sample_details").isEmpty())
            assertNoStoredMap()
            click("history_location_recording")
            waitTag("location_history_content")
            click("location_history_event_previous")
            click("location_history_event_previous")
            click("location_history_point_map")
            waitTag("review_road_sample_absent")
            val replacementMap = awaitRunMap(firstSample.event, replaced.road)
            val writesBeforeOldRoad = f.sampleWriteAttempts.get()
            invoke(staleFirstLink)
            assertEquals(writesBeforeOldRoad, f.sampleWriteAttempts.get())
            assertNull(storedRoad().sampleAnchor)
            assertTrue("A changed saved road owns a fresh map", replacementMap !== originalRoadMap)
            instrument.runOnMainSync { assertFalse(originalRoadMap.isAttachedToWindow) }
            assertEquals(
                roads[1].title,
                nodes("review_road_stored_title")
                    .single()
                    .config[SemanticsProperties.Text]
                    .single()
                    .text,
            )
            assertTrue(nodes("review_road_memo_text").isEmpty())

            // Failure must remove the stale composite, not silently render point-only absence.
            f.reviewReadFailure.value = true
            waitTag("review_road_failed")
            assertNoStoredMap(replacementMap)
            assertTrue(nodes("review_road_empty").isEmpty())
            val failedReadAttempts = f.reviewReadAttempts.get()
            f.reviewReadFailure.value = false
            assertNoStoredMap()
            assertEquals("Recovery waits for explicit retry", failedReadAttempts, f.reviewReadAttempts.get())
            click("review_road_retry")
            val recoveredMap = awaitRunMap(firstSample.event, replaced.road)
            assertTrue(f.reviewReadAttempts.get() > failedReadAttempts && recoveredMap !== replacementMap)
            assertEquals(
                replaced,
                runBlocking {
                    f.store.reviewRoads
                        .observe(ended.sessionId)
                        .first()
                },
            )

            assertEquals(
                SessionReviewRoadWriteResult.Cleared,
                runBlocking { f.store.reviewRoads.clear(ended, replaced) { true } },
            )
            waitTag("review_road_empty")
            assertText("review_road_empty", HomeR.string.review_road_comparison_empty)
            val firstMap = awaitRunMap(firstSample.event)
            assertTrue("Actual road absence preserves all original run points", firstMap !== recoveredMap)
            instrument.runOnMainSync { assertFalse(recoveredMap.isAttachedToWindow) }
            assertTrue(nodes("review_road_stored_title").isEmpty() && nodes("review_road_memo_text").isEmpty())
            visual("stored-city-hall-point-map", "location_history_point_map_notice", "stored_location_map")
            assertTrue(nodes("location_history_memo_content").isEmpty())
            val retiredMemoOpen = clickAction("location_history_memo")
            click("location_history_memo")
            waitTag("location_history_memo_text")
            assertTrue(
                nodes("location_history_memo_text")
                    .single()
                    .config[SemanticsProperties.Text]
                    .single()
                    .text == memoText,
            )
            waitTag("location_history_memo_source")
            assertTrue(nodes("history_memo_edit").isEmpty() && nodes("history_memo_create").isEmpty())
            assertTrue("Reading a whole-session memo keeps the selected run's native map", awaitRunMap(firstSample.event) === firstMap)
            visual("stored-point-session-memo", "location_history_memo_content")

            click("location_history_event_next")
            waitTag("location_history_memo_text")
            assertTrue(
                nodes("location_history_memo_text")
                    .single()
                    .config[SemanticsProperties.Text]
                    .single()
                    .text == memoText,
            )
            assertNoStoredMap(firstMap)
            invoke(staleOpen)
            assertNoStoredMap()
            click("location_history_event_next")
            assertText("location_history_event_state", HomeR.string.location_history_callback_empty)
            waitTag("location_history_point_map")
            assertNoStoredMap(firstMap)
            click("location_history_point_map")
            val emptyOnlyMap = awaitRunMap(storedBefore.events.last())
            assertTrue(nodes("review_road_sample_link").isEmpty())
            waitTag("location_history_memo_text")
            assertTrue(
                nodes("location_history_memo_text")
                    .single()
                    .config[SemanticsProperties.Text]
                    .single()
                    .text == memoText,
            )

            click("location_history_event_previous")
            assertNoStoredMap(emptyOnlyMap)
            click("location_history_point_map")
            val beforeDriving = awaitRunMap(secondSample.event)
            assertTrue("Each selected stored event owns a fresh native map", beforeDriving !== firstMap)
            val retiredForDriving = clickAction("location_history_point_map")
            f.drive(true)
            waitTag("location_history_locked")
            assertNoStoredMap(beforeDriving)
            assertTrue(nodes("location_history_memo_content").isEmpty())
            invoke(retiredMemoOpen)
            assertTrue(nodes("location_history_memo_content").isEmpty())
            f.drive(false)
            waitTag("location_history_locked")
            invoke(retiredForDriving)
            assertNoStoredMap()
            click("location_history_back")
            click("history_location_recording")
            waitTag("location_history_content")
            assertText("location_history_event_state", HomeR.string.location_history_callback_empty)
            assertNoStoredMap()
            click("location_history_event_previous")
            waitTag("location_history_point_map")
            assertNoStoredMap()

            click("location_history_point_map")
            val beforeStop = awaitRunMap(secondSample.event)
            assertTrue(nodes("location_history_memo_content").isEmpty())
            invoke(retiredMemoOpen)
            assertTrue(nodes("location_history_memo_content").isEmpty())
            val retiredMemoForStop = clickAction("location_history_memo")
            click("location_history_memo")
            waitTag("location_history_memo_text")
            val retiredForStop = clickAction("location_history_point_map")
            f.move(Lifecycle.State.CREATED)
            assertNoStoredMap(beforeStop)
            assertTrue(nodes("location_history_memo_content").isEmpty())
            f.move(Lifecycle.State.RESUMED)
            waitTag("location_history_content")
            assertText("location_history_event_state", HomeR.string.location_history_callback_empty)
            invoke(retiredForStop)
            assertNoStoredMap()
            invoke(retiredMemoForStop)
            assertTrue(nodes("location_history_memo_content").isEmpty())
            click("location_history_event_previous")
            waitTag("location_history_point_map")
            assertNoStoredMap()

            assertEquals(storedBefore, f.snapshot(ended))
            assertEquals(
                memoBefore,
                runBlocking {
                    f.store.memos
                        .observe(ended.sessionId)
                        .first()
                },
            )
            assertEquals(
                sessionsBefore,
                runBlocking {
                    f.store.sessions
                        .observeSessions()
                        .first()
                },
            )
            assertEquals(
                preparationBefore,
                runBlocking {
                    f.store.preparation
                        .observeSelection()
                        .first()
                },
            )
            assertEquals(
                null,
                runBlocking {
                    f.store.reviewRoads
                        .observe(ended.sessionId)
                        .first()
                },
            )
            assertEquals(sequencesBefore, f.store.sequenceSnapshot())
            assertEquals(0, f.store.foreignKeyViolationCount())
            assertEquals(streamsBefore, f.gateway.streams.size)
            assertEquals(0, f.gateway.active.get())
            assertFalse(f.gateway.permission.get())
            f.assertNoPlatformActions()
            phase("CONTROLLED_RUN_POINT_MAP_EMPTY_SELECTION_EXACT_HIGHLIGHT_ASSOCIATION_JUMP_AND_PRESERVATION")
        }
    }

    @Test fun storedRunTraceStopsAtMissingAndTimedOutReadings() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("manualStoredLocationMap") == "true")
        val config = compose.activity.resources.configuration
        assertEquals(checkNotNull(args.getString("expectedFontScale")).toFloat(), config.fontScale, 0.01f)
        assertEquals(
            checkNotNull(args.getString("expectedDark")).toBooleanStrict(),
            config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES,
        )
        fixture(mapEnabled = true) { f ->
            click("session_start")
            val source = f.active()
            f.awaitSource(source)
            click("home_location_recording")
            click("location_recording_start")
            f.awaitRecording()
            val first = LocationUpdateSample(37.56669, 126.97842, -2.0, 421f, -5f, -42, f.elapsed.incrementAndGet())
            f.emit(LocationUpdateReading.Sample(first))
            f.emit(
                LocationUpdateReading.Sample(
                    first.copy(longitude = 126.97916, receivedAtElapsedRealtimeMillis = f.elapsed.incrementAndGet()),
                ),
            )
            f.emit(LocationUpdateReading.Empty(f.elapsed.incrementAndGet()))
            f.emit(
                LocationUpdateReading.Sample(
                    first.copy(latitude = 37.56721, receivedAtElapsedRealtimeMillis = f.elapsed.incrementAndGet()),
                ),
            )
            f.emit(
                LocationUpdateReading.Sample(
                    first.copy(
                        latitude = 37.56738,
                        receivedAtElapsedRealtimeMillis = f.elapsed.addAndGet(LocationUpdateGateway.NO_UPDATE_TIMEOUT_MILLIS),
                    ),
                ),
            )
            f.awaitEvents(source, 5)
            click("location_recording_stop")
            f.awaitTerminal(source, LocationRecordingStopReason.USER_STOP)
            click("location_recording_back")
            click("session_finish")
            val ended = f.ended(source.sessionId)
            val stored = f.snapshot(ended)
            val beforeStreams = f.gateway.streams.size
            val run = stored.runs.single()
            click("home_history")
            click("history_location_recording")
            waitTag("location_history_content")
            click("location_history_point_map")
            val trace = stored.events[0] to stored.events[1]
            val map = awaitStoredMap(run, stored.events, stored.events.last(), expectedTrace = trace)
            assertText("stored_location_map_trace_scope", MapR.string.stored_map_trace_scope)
            f.drive(true)
            waitTag("location_history_locked")
            assertNoStoredMap(map)
            assertTrue(nodes("stored_location_map_trace_scope").isEmpty())
            f.drive(false)
            waitTag("location_history_locked")
            assertNoStoredMap()
            assertEquals(stored, f.snapshot(ended))
            assertEquals(beforeStreams, f.gateway.streams.size)
            f.assertNoPlatformActions()
        }
    }

    /** This assertion must precede any scroll helper after a linked-sample jump. */
    private fun assertLinkedEventVisible(
        ordinal: Int,
        count: Int,
    ) {
        waitTag("location_history_event_heading")
        compose.waitForIdle()
        assertActualHost()
        val heading = nodes("location_history_event_heading").single()
        assertTrue(
            heading.config[SemanticsProperties.Text].any {
                it.text == instrument.targetContext.getString(HomeR.string.location_history_event_position, ordinal, count)
            },
        )
        assertText("location_history_event_state", HomeR.string.location_history_sample)
        val viewport = visualViewport()
        for (tag in listOf("location_history_event_heading", "location_history_event_state")) {
            compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
            val node = nodes(tag).single()
            val bounds = node.boundsInWindow
            val origin = node.positionOnScreen - node.positionInWindow
            assertTrue("The linked event $tag must have visible area", node.size.width > 0 && node.size.height > 0)
            assertTrue(
                "The linked event $tag must not be clipped",
                bounds.width >= node.size.width - 1 && bounds.height >= node.size.height - 1,
            )
            assertTrue(
                "The linked event $tag must be in the actual viewport without manual scrolling",
                bounds.left + origin.x >= viewport.left - 1 && bounds.top + origin.y >= viewport.top - 1 &&
                    bounds.right + origin.x <= viewport.right + 1 && bounds.bottom + origin.y <= viewport.bottom + 1,
            )
        }
    }

    /** Every native source, projection and rendered-feature query remains on its live UI-thread View. */
    private fun awaitStoredMap(
        expectedRun: LocationRecordingRun,
        expectedEvents: List<LocationRecordingEvent>,
        selectedEvent: LocationRecordingEvent?,
        expectedRoad: CourseRoadSnapshot? = null,
        expectedTrace: Pair<LocationRecordingEvent, LocationRecordingEvent>? = null,
        expectedPreview: Boolean = false,
        expectedOffline: Boolean = false,
        expectedSectionBounds: ClosedFloatingPointRange<Double>? = null,
    ): MapView =
        runBlocking {
            val recordedPoints = expectedEvents.filter { it.reading is LocationUpdateReading.Sample }
            check(
                recordedPoints.isNotEmpty() &&
                    expectedEvents.all { it.runId == expectedRun.runId && it.sessionId == expectedRun.source.sessionId },
            )
            val selectedPoint = selectedEvent?.takeIf { it.reading is LocationUpdateReading.Sample }
            val visiblePoints = if (expectedPreview) listOf(checkNotNull(selectedPoint)) else recordedPoints
            val expectedPoints = visiblePoints.associateBy { it.id.toString() }
            waitTag("stored_location_map_counts")
            val countText =
                instrument.targetContext.getString(
                    MapR.string.stored_map_counts,
                    recordedPoints.size,
                    expectedEvents.size - recordedPoints.size,
                )
            assertTrue(nodes("stored_location_map_counts").single().config[SemanticsProperties.Text].any { it.text == countText })
            waitTag("stored_location_map_selected")
            val selectionText =
                if (selectedPoint == null) {
                    instrument.targetContext.getString(MapR.string.stored_map_selected_without_coordinates)
                } else {
                    instrument.targetContext.getString(MapR.string.stored_map_selected_sample, selectedPoint.ordinal)
                }
            assertTrue(nodes("stored_location_map_selected").single().config[SemanticsProperties.Text].any { it.text == selectionText })
            waitTag("stored_location_map_view")
            compose.onNodeWithTag("stored_location_map_view", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            waitTag("stored_location_map_ready")
            val lastObservation = AtomicReference("{\"reason\":\"NO_NATIVE_OBSERVATION\"}")
            try {
                withTimeout(25_000) {
                    var result: MapView? = null
                    while (result == null) {
                        assertActualHost()
                        val reply = CompletableDeferred<MapView?>()
                        instrument.runOnMainSync {
                            val attached = storedMapViews(compose.activity.window.decorView).filter { it.isAttachedToWindow }
                            val view = attached.singleOrNull()
                            if (view == null || view.width <= 0 || view.height <= 0) {
                                lastObservation.set(
                                    JSONObject()
                                        .put("reason", if (view == null) "VIEW_NOT_UNIQUE" else "VIEW_NO_SIZE")
                                        .put("nativeViewCount", attached.size)
                                        .toString(),
                                )
                                reply.complete(null)
                            } else {
                                view.getMapAsync { map ->
                                    if (!view.isAttachedToWindow ||
                                        storedMapViews(compose.activity.window.decorView).singleOrNull { it.isAttachedToWindow } !== view
                                    ) {
                                        lastObservation.set(JSONObject().put("reason", "VIEW_RETIRED").toString())
                                        reply.complete(null)
                                    } else {
                                        val observation = JSONObject().put("reason", "INSPECTION_STARTED")
                                        try {
                                            val style = map.style
                                            val source = style?.getSource(STORED_POINT_SOURCE) as? GeoJsonSource
                                            val circle = style?.getLayerAs<CircleLayer>(STORED_POINT_LAYER)
                                            val selectedLayer = style?.getLayerAs<CircleLayer>(STORED_POINT_SELECTION)
                                            val viewport = android.graphics.RectF(0f, 0f, view.width.toFloat(), view.height.toFloat())

                                            // The renderer exposes tile-converted copies. Pure map tests check exact GeoJSON serialization;
                                            // Native queries preserve event identity at duplicate coordinates, within one physical pixel.
                                            fun matchesStoredPoint(feature: org.maplibre.geojson.Feature): Boolean {
                                                val expected = expectedPoints[feature.getStringProperty("event_id")] ?: return false
                                                val sample = (expected.reading as LocationUpdateReading.Sample).sample
                                                val point = feature.geometry() as? GeoJsonPoint ?: return false
                                                val expectedScreen =
                                                    map.projection.toScreenLocation(
                                                        LatLng(sample.latitude, sample.longitude),
                                                    )
                                                val actualScreen =
                                                    map.projection.toScreenLocation(
                                                        LatLng(point.latitude(), point.longitude()),
                                                    )
                                                return feature.getStringProperty("source") == LocationUpdateSource.SDK_UPDATE.name &&
                                                    feature.getStringProperty("run_id") == expectedRun.runId &&
                                                    feature.getNumberProperty("ordinal")?.toInt() == expected.ordinal &&
                                                    abs(actualScreen.x - expectedScreen.x) <= 1f &&
                                                    abs(actualScreen.y - expectedScreen.y) <= 1f
                                            }
                                            val allPointsInside =
                                                expectedPoints.values.all { event ->
                                                    val sample = (event.reading as LocationUpdateReading.Sample).sample
                                                    val point = map.projection.toScreenLocation(LatLng(sample.latitude, sample.longitude))
                                                    point.x > 0 && point.y > 0 && point.x < view.width && point.y < view.height
                                                }
                                            val storedFeatures = source?.querySourceFeatures(null).orEmpty()
                                            val sourceEventIds = storedFeatures.map { it.getStringProperty("event_id") }.toSet()
                                            val sourceMatches =
                                                sourceEventIds == expectedPoints.keys && storedFeatures.all(::matchesStoredPoint)
                                            val rendered = map.queryRenderedFeatures(viewport, STORED_POINT_LAYER)
                                            val renderedEventIds = rendered.map { it.getStringProperty("event_id") }.toSet()
                                            val renderedMatches =
                                                renderedEventIds == expectedPoints.keys && rendered.all(::matchesStoredPoint)
                                            val selectedFilter =
                                                if (selectedPoint == null) {
                                                    Expression.literal(false)
                                                } else {
                                                    Expression.eq(
                                                        Expression.get("event_id"),
                                                        Expression.literal(selectedPoint.id.toString()),
                                                    )
                                                }
                                            val selectedRendered = map.queryRenderedFeatures(viewport, STORED_POINT_SELECTION)
                                            val selectedFeaturesMatch =
                                                if (selectedPoint == null) {
                                                    selectedRendered.isEmpty()
                                                } else {
                                                    selectedRendered.size == 1 &&
                                                        selectedRendered.single().getStringProperty(
                                                            "event_id",
                                                        ) == selectedPoint.id.toString() &&
                                                        matchesStoredPoint(selectedRendered.single())
                                                }
                                            val selectionMatches =
                                                selectedLayer != null && selectedLayer.sourceId == STORED_POINT_SOURCE &&
                                                    selectedLayer.visibility.value != "none" &&
                                                    selectedLayer.filter?.toArray()?.contentDeepEquals(selectedFilter.toArray()) == true &&
                                                    selectedFeaturesMatch
                                            val noSdkLine =
                                                style?.layers?.filterIsInstance<LineLayer>()?.none { it.sourceId == STORED_POINT_SOURCE } ==
                                                    true
                                            observation.put("expectedPointCount", expectedPoints.size)
                                            observation.put("sourceQueryCount", storedFeatures.size)
                                            observation.put("sourceUniqueEventCount", sourceEventIds.size)
                                            observation.put("renderedPointCount", rendered.size)
                                            observation.put("renderedUniqueEventCount", renderedEventIds.size)
                                            observation.put("selectedRenderedCount", selectedRendered.size)
                                            observation.put("allPointsInside", allPointsInside)
                                            observation.put("sourceMatches", sourceMatches)
                                            observation.put("renderedMatches", renderedMatches)
                                            observation.put("selectionMatches", selectionMatches)
                                            observation.put("noSdkLine", noSdkLine)
                                            val roadSource = style?.getSourceAs<GeoJsonSource>("hangil-course-preview-roads")
                                            val roadLayer = style?.getLayerAs<LineLayer>("hangil-course-preview-road-selection")
                                            val roadFeatures = roadSource?.querySourceFeatures(null).orEmpty()
                                            val roadMatches =
                                                if (expectedRoad == null) {
                                                    roadSource == null && roadLayer == null && roadFeatures.isEmpty()
                                                } else {
                                                    val original =
                                                        expectedRoad.nodes.map { node ->
                                                            map.projection.toScreenLocation(LatLng(node.latitude, node.longitude))
                                                        }
                                                    val paths =
                                                        roadFeatures.mapNotNull { feature ->
                                                            (feature.geometry() as? LineString)?.coordinates()?.map { point ->
                                                                map.projection.toScreenLocation(LatLng(point.latitude(), point.longitude()))
                                                            }
                                                        }
                                                    val filter =
                                                        Expression.eq(
                                                            Expression.get("road_id"),
                                                            Expression.literal(expectedRoad.id),
                                                        )
                                                    val renderedRoad =
                                                        map.queryRenderedFeatures(
                                                            android.graphics.RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()),
                                                            "hangil-course-preview-road-selection",
                                                        )
                                                    roadLayer != null && roadLayer.sourceId == "hangil-course-preview-roads" &&
                                                        roadLayer.visibility.value != "none" &&
                                                        roadLayer.filter?.toArray()?.contentDeepEquals(filter.toArray()) == true &&
                                                        roadFeatures.isNotEmpty() && renderedRoad.isNotEmpty() &&
                                                        roadFeatures.all { feature ->
                                                            feature.geometry() is LineString &&
                                                                feature.getStringProperty("road_source") == expectedRoad.sourceCode &&
                                                                feature.getStringProperty("road_id") == expectedRoad.id &&
                                                                feature.getStringProperty(
                                                                    "osm_way_id",
                                                                ) == expectedRoad.osmWayId.toString() &&
                                                                feature.getNumberProperty("osm_way_version").toInt() ==
                                                                expectedRoad.osmWayVersion
                                                        } &&
                                                        original.all { point ->
                                                            point.x > 0 && point.y > 0 && point.x < view.width && point.y < view.height
                                                        } &&
                                                        paths.isNotEmpty() &&
                                                        paths.all { path ->
                                                            path.size >= 2 && path.all { onStoredRoadPath(it, original) }
                                                        } &&
                                                        original.all { point -> paths.any { onStoredRoadPath(point, it) } }
                                                }
                                            observation.put("roadSourceCount", roadFeatures.size)
                                            observation.put("roadMatches", roadMatches)
                                            val checks =
                                                linkedMapOf(
                                                    "SAVED_ROAD_OR_ACTUAL_ABSENCE_MATCHES" to roadMatches,
                                                    "SOURCE_PRESENT" to (source != null),
                                                    "LAYER_PRESENT" to (circle != null),
                                                    "LAYER_SOURCE_MATCHES" to (circle != null && circle.sourceId == STORED_POINT_SOURCE),
                                                    "LAYER_VISIBLE" to (circle != null && circle.visibility.value != "none"),
                                                    "ALL_ORIGINAL_POINTS_INSIDE" to allPointsInside,
                                                    "SOURCE_ALL_EVENT_IDENTITIES_AND_RAW_PLACEMENTS_MATCH" to sourceMatches,
                                                    "RENDERED_ALL_EVENT_IDENTITIES_AND_RAW_PLACEMENTS_MATCH" to renderedMatches,
                                                    "EXACT_SELECTED_EVENT_OR_NO_SELECTION" to selectionMatches,
                                                    "SDK_SOURCE_HAS_NO_LINE_LAYER" to noSdkLine,
                                                )
                                            if (expectedTrace != null) {
                                                val (from, to) = expectedTrace
                                                checks["TRACE_MATCHES_ONLY_EXPECTED_RECEIPT_PAIR"] = matchesStoredTrace(map, from, to)
                                            }
                                            if (expectedPreview) {
                                                checks.putAll(
                                                    courseRoadPreviewChecks(
                                                        map,
                                                        checkNotNull(expectedRoad),
                                                        checkNotNull(selectedPoint),
                                                        viewport,
                                                    ),
                                                )
                                            }
                                            if (expectedOffline) {
                                                checks["OFFLINE_STYLE_HAS_NO_REMOTE_RESOURCES"] =
                                                    style != null && style.uri.isNullOrBlank() &&
                                                    JSONObject(style.json).let { json ->
                                                        !json.has("sprite") && !json.has("glyphs") &&
                                                            json.getJSONObject("sources").length() == 0
                                                    }
                                                checks["OFFLINE_STYLE_HAS_ONLY_LOCAL_DATA_SOURCES"] =
                                                    style?.sources?.all { it.id == "org.maplibre.annotations" || it is GeoJsonSource } ==
                                                    true
                                            }
                                            checks["LINKED_ROAD_SECTION_MATCHES"] =
                                                matchesRoadSection(map, viewport, expectedRoad, expectedSectionBounds)
                                            val failed = checks.entries.firstOrNull { !it.value }
                                            observation.put("checks", JSONObject(checks))
                                            observation.put("reason", failed?.let { "FAILED_" + it.key } ?: "MATCHED")
                                            lastObservation.set(observation.toString())
                                            reply.complete(if (failed == null) view else null)
                                        } catch (failure: Throwable) {
                                            observation.put("reason", "NATIVE_INSPECTION_EXCEPTION")
                                            observation.put("exceptionType", failure.javaClass.simpleName)
                                            lastObservation.set(observation.toString())
                                            reply.completeExceptionally(
                                                AssertionError("Stored point native map inspection failed: " + lastObservation.get()),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        result = withTimeout(5_000) { reply.await() }
                        if (result == null) delay(150)
                    }
                    checkNotNull(result)
                }
            } catch (_: TimeoutCancellationException) {
                throw AssertionError("Stored point native map inspection timed out: " + lastObservation.get())
            }
        }

    private fun courseRoadPreviewChecks(
        map: org.maplibre.android.maps.MapLibreMap,
        road: CourseRoadSnapshot,
        selectedEvent: LocationRecordingEvent,
        viewport: android.graphics.RectF,
    ): Map<String, Boolean> {
        val style = map.style ?: return mapOf("PREVIEW_STYLE_LOADED" to false)
        val previewSource = "hangil-course-road-nearest-source"
        val previewLayer = "hangil-course-road-nearest"
        val expectedSources = setOf(STORED_POINT_SOURCE, "hangil-course-preview-roads", previewSource)
        val annotationSource = "org.maplibre.annotations"
        val json = JSONObject(style.json)
        val jsonSources = json.getJSONObject("sources")
        val appSources = style.sources.filterNot { it.id == annotationSource }
        val source = style.getSourceAs<GeoJsonSource>(previewSource)
        val layer = style.getLayerAs<CircleLayer>(previewLayer)
        val stored = (selectedEvent.reading as LocationUpdateReading.Sample).sample
        val samplePoint = map.projection.toScreenLocation(LatLng(stored.latitude, stored.longitude))
        val roadPath = road.nodes.map { map.projection.toScreenLocation(LatLng(it.latitude, it.longitude)) }
        val sourcePoints = source?.querySourceFeatures(null).orEmpty()
        val rendered = if (layer != null) map.queryRenderedFeatures(viewport, previewLayer) else emptyList()

        fun matchesPoint(feature: org.maplibre.geojson.Feature): Boolean {
            val geometry = feature.geometry() as? GeoJsonPoint ?: return false
            val point = map.projection.toScreenLocation(LatLng(geometry.latitude(), geometry.longitude()))
            val distinctFromSample = abs(point.x - samplePoint.x) > 1f || abs(point.y - samplePoint.y) > 1f
            return viewport.contains(point.x, point.y) && distinctFromSample && onStoredRoadPath(point, roadPath)
        }

        return linkedMapOf(
            "PREVIEW_NO_STYLE_RESOURCE_URLS" to (style.uri.isNullOrBlank() && !json.has("sprite") && !json.has("glyphs")),
            "PREVIEW_NO_EXTERNAL_STYLE_SOURCES" to (jsonSources.length() == 0),
            // MapLibre itself creates an internal annotation source after a style loads.
            "PREVIEW_ONLY_EXPECTED_APP_SOURCES" to (appSources.map { it.id }.toSet() == expectedSources),
            "PREVIEW_APP_SOURCES_ARE_GEOJSON" to appSources.all { it is GeoJsonSource },
            "PREVIEW_PROJECTED_LAYER_VISIBLE" to (layer != null && layer.sourceId == source?.id && layer.visibility.value != "none"),
            "PREVIEW_PROJECTED_SOURCE_POINT_MATCHES" to (sourcePoints.isNotEmpty() && sourcePoints.all(::matchesPoint)),
            "PREVIEW_PROJECTED_RENDERED_POINT_MATCHES" to (rendered.isNotEmpty() && rendered.all(::matchesPoint)),
        )
    }

    private fun matchesRoadSection(
        map: org.maplibre.android.maps.MapLibreMap,
        viewport: android.graphics.RectF,
        road: CourseRoadSnapshot?,
        bounds: ClosedFloatingPointRange<Double>?,
    ): Boolean {
        val style = map.style ?: return false
        val source = style.getSourceAs<GeoJsonSource>("hangil-stored-location-road-section")
        val layer = style.getLayerAs<LineLayer>("hangil-stored-location-road-section-line")
        if (bounds == null) return source == null && layer == null
        if (source == null || layer == null || layer.sourceId != source.id || layer.visibility.value == "none" || road == null) return false
        val lines = source.querySourceFeatures(null).map { it.geometry() as? LineString ?: return false }
        if (lines.isEmpty() || map.queryRenderedFeatures(viewport, layer.id).isEmpty()) return false
        val points = lines.flatMap { it.coordinates() }
        val path = road.nodes.map { map.projection.toScreenLocation(LatLng(it.latitude, it.longitude)) }
        val tolerance = 0.00008
        return points.size >= 2 &&
            abs(points.minOf { it.longitude() } - bounds.start) < tolerance &&
            abs(points.maxOf { it.longitude() } - bounds.endInclusive) < tolerance &&
            points.all { onStoredRoadPath(map.projection.toScreenLocation(LatLng(it.latitude(), it.longitude())), path) }
    }

    private fun matchesStoredTrace(
        map: org.maplibre.android.maps.MapLibreMap,
        from: LocationRecordingEvent,
        to: LocationRecordingEvent,
    ): Boolean {
        val style = map.style ?: return false
        val traceLayer = style.getLayerAs<LineLayer>("hangil-stored-location-trace-line") ?: return false
        val traceSource = style.getSourceAs<GeoJsonSource>("hangil-stored-location-trace") ?: return false
        if (traceLayer.sourceId != traceSource.id || traceLayer.visibility.value == "none") return false
        val endpoints =
            listOf(from, to).map { event ->
                val sample = (event.reading as LocationUpdateReading.Sample).sample
                map.projection.toScreenLocation(LatLng(sample.latitude, sample.longitude))
            }
        val paths =
            traceSource.querySourceFeatures(null).map { feature ->
                val geometry = feature.geometry() as? LineString ?: return false
                if (feature.getNumberProperty("from_ordinal").toInt() != from.ordinal ||
                    feature.getNumberProperty("to_ordinal").toInt() != to.ordinal
                ) {
                    return false
                }
                geometry.coordinates().map { point -> map.projection.toScreenLocation(LatLng(point.latitude(), point.longitude())) }
            }
        return paths.isNotEmpty() &&
            paths.all { path -> path.size >= 2 && path.all { onStoredRoadPath(it, endpoints) } } &&
            endpoints.all { point -> paths.any { onStoredRoadPath(point, it) } }
    }

    private fun onStoredRoadPath(
        point: android.graphics.PointF,
        path: List<android.graphics.PointF>,
    ): Boolean =
        path.zipWithNext().any { (start, end) ->
            val dx = end.x - start.x
            val dy = end.y - start.y
            val length = dx * dx + dy * dy
            val dot = (point.x - start.x) * dx + (point.y - start.y) * dy
            val fraction = if (length == 0f) 0f else (dot / length).coerceIn(0f, 1f)
            val x = point.x - (start.x + fraction * dx)
            val y = point.y - (start.y + fraction * dy)
            x * x + y * y <= 1f
        }

    private fun assertNoStoredMap(previous: MapView? = null) {
        compose.waitForIdle()
        assertTrue("The map stays absent until an explicit current disclosure", nodes("stored_location_map_view").isEmpty())
        instrument.runOnMainSync {
            assertTrue("No native map remains attached after its owner closes", storedMapViews(compose.activity.window.decorView).isEmpty())
            if (previous != null) assertFalse("A retired event must detach its native map", previous.isAttachedToWindow)
        }
    }

    private fun storedMapViews(view: View): List<MapView> =
        when (view) {
            is MapView -> listOf(view)
            is ViewGroup -> (0 until view.childCount).flatMap { storedMapViews(view.getChildAt(it)) }
            else -> emptyList()
        }

    private fun fixture(
        mapEnabled: Boolean = false,
        block: (Fixture) -> Unit,
    ) {
        catalogResourceOverrideRequested = requestedCatalogResourceOverride()
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
            if (catalogResourceOverrideRequested) applyCatalogResourceOverride()
            prepareActualHost()
            val store = OwnedPracticeTestStore.create(instrument.targetContext)
            ownedStore = store
            val owned = Fixture(store, mapEnabled)
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
            recordHostFailure("fixture-failure-before-cleanup", failure)
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
            cleanup { restoreCatalogResourceOverride() }
            cleanup { writeCatalogResourceOverrideResult() }
            cleanup {
                val info = instrument.uiAutomation.serviceInfo
                info.flags = if (originalFlags and retrieve != 0) info.flags or retrieve else info.flags and retrieve.inv()
                instrument.uiAutomation.serviceInfo = info
                assertEquals(originalFlags and retrieve, instrument.uiAutomation.serviceInfo.flags and retrieve)
            }
            primary?.let { throw it }
        }
    }

    private fun requestedCatalogResourceOverride(): Boolean {
        val args = InstrumentationRegistry.getArguments()
        val requested = args.getString("manualCatalogConfigurationOverride")
        check(requested == null || requested == "false" || requested == "true") { "Explicit resource override boolean required" }
        if (requested != "true") return false
        check(args.getString("manualLocationRecordingControlled") == "true" && args.getString("manualLocationRecordingVisual") == "true") {
            "Resource override requires explicit controlled visual validation"
        }
        check(args.getString("expectedFontScale") in setOf("1.0", "1.3")) { "Explicit resource font scale required" }
        check(args.getString("expectedDark") in setOf("false", "true")) { "Explicit resource theme required" }
        return true
    }

    private fun catalogConfigurationScope(): String =
        if (catalogResourceOverrideRequested) {
            "actual-Android-resource-scoped-controlled-UI"
        } else {
            "actual-Activity-configuration-controlled-UI"
        }

    private class ResourceState(
        val resources: Resources,
    ) {
        val configuration = Configuration(resources.configuration)
        val metrics = DisplayMetrics().apply { setTo(resources.displayMetrics) }
    }

    private class ThemeState(
        val theme: Resources.Theme,
        val attributes: List<Int>,
    )

    private class CatalogResourceOverride(
        val activity: ComponentCatalogActivity,
        val target: ResourceState,
        val related: List<ResourceState>,
        val themes: List<ThemeState>,
    )

    /** Selected cache checks, not a claim that every framework theme attribute is covered. */
    private fun themeAttributes(theme: Resources.Theme): List<Int> =
        listOf(android.R.attr.colorBackground, android.R.attr.textColorPrimary, android.R.attr.windowBackground).flatMap { attribute ->
            val value = TypedValue()
            val resolved = theme.resolveAttribute(attribute, value, true)
            listOf(if (resolved) 1 else 0, value.type, value.data, value.resourceId)
        }

    @Suppress("DEPRECATION")
    private fun applyCatalogResourceOverride() {
        check(catalogResourceOverrideRequested && !catalogResourceOverrideApplied.get() && catalogResourceOverride.get() == null)
        check(actualHost == null && actualHostDisplay == null) { "Resource setup must precede host ownership" }
        val args = InstrumentationRegistry.getArguments()
        val scale = checkNotNull(args.getString("expectedFontScale")).toFloat()
        val dark = args.getString("expectedDark") == "true"
        instrument.runOnMainSync {
            val activity = compose.activity
            val contexts = listOf(activity, instrument.targetContext, activity.applicationContext, instrument.context)
            val resources = IdentityHashMap<Resources, ResourceState>()
            contexts.forEach { context -> resources.getOrPut(context.resources) { ResourceState(context.resources) } }
            resources.getOrPut(Resources.getSystem()) { ResourceState(Resources.getSystem()) }
            val themes = IdentityHashMap<Resources.Theme, ThemeState>()
            contexts.forEach { context ->
                val theme = context.theme
                if (theme != null) themes.getOrPut(theme) { ThemeState(theme, themeAttributes(theme)) }
            }
            val target = checkNotNull(resources[activity.resources])
            val saved = CatalogResourceOverride(activity, target, resources.values.toList(), themes.values.toList())
            // Retain the originals before any mutation so finally can restore a partial application.
            check(catalogResourceOverride.compareAndSet(null, saved)) { "Resource override is single-owner" }
            val configuration =
                Configuration(target.configuration).apply {
                    fontScale = scale
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
            target.resources.updateConfiguration(configuration, DisplayMetrics().apply { setTo(target.metrics) })
            saved.themes.forEach { it.theme.rebase() }
            check(activity.resources === target.resources) { "Original Activity resources must remain owned" }
            check(configurationMatches(activity.resources.configuration, scale, dark)) { "Actual resource override was not applied" }
            check(
                activity.resources.displayMetrics.widthPixels == target.metrics.widthPixels &&
                    activity.resources.displayMetrics.heightPixels == target.metrics.heightPixels &&
                    activity.resources.displayMetrics.densityDpi == target.metrics.densityDpi,
            ) { "Physical display metrics must not change" }
            check(catalogResourceOverrideApplied.compareAndSet(false, true)) { "Resource override cannot be replayed" }
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreCatalogResourceOverride() {
        val saved = catalogResourceOverride.get() ?: return
        instrument.runOnMainSync {
            check(catalogResourceOverride.get() === saved) { "Only the captured resource owner may restore" }
            saved.target.resources.updateConfiguration(
                Configuration(saved.target.configuration),
                DisplayMetrics().apply { setTo(saved.target.metrics) },
            )
            check(saved.activity.resources === saved.target.resources) { "Original Activity resources must retain identity after restore" }
            saved.themes.forEach { it.theme.rebase() }
            saved.related.forEach { before ->
                check(before.resources.configuration == before.configuration && before.resources.displayMetrics == before.metrics) {
                    "Related context resources must be restored exactly"
                }
            }
            saved.themes.forEach { check(themeAttributes(it.theme) == it.attributes) { "Selected theme attributes must be restored" } }
            check(catalogResourceOverride.compareAndSet(saved, null)) { "Resource cleanup must not retire another owner" }
            catalogResourceOverrideRestored.set(true)
        }
    }

    private fun writeCatalogResourceOverrideResult() {
        if (!catalogResourceOverrideRequested) return
        val proof =
            JSONObject()
                .put("schema", 1)
                .put("kind", "controlled-resource-override-restoration")
                .put("configurationScope", catalogConfigurationScope())
                .put("applied", catalogResourceOverrideApplied.get())
                .put("restored", catalogResourceOverrideRestored.get())
                .put("ownerReleased", catalogResourceOverride.get() == null)
                .put("globalOsSettingsWrittenByFixture", false)
                .put("actualSdkLocation", false)
                .put("selectedThemeAttributesChecked", JSONArray(listOf("colorBackground", "textColorPrimary", "windowBackground")))
        val output = File(visualDirectory, "resource-override-restoration.json")
        check(!output.exists()) { "Resource restoration proof must not overwrite evidence" }
        output.writeText(proof.toString(2))
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

    /** Only this known production dialog may own focus between its verified open and dismissal. */
    private fun openRoadDiscardDialog() {
        waitTag("review_road_save")
        assertActualHost()
        val save = compose.onNodeWithTag("review_road_save", useUnmergedTree = true)
        save.performScrollTo().assertIsDisplayed()
        assertActualHost()
        save.performClick()
        compose.waitForIdle()
        waitTag("review_road_memo_discard_dialog")
        assertOwnedRoadDiscardDialog()
    }

    private fun assertOwnedRoadDiscardDialog() {
        instrument.runOnMainSync {
            val captured = checkNotNull(actualHost)
            assertTrue("The owned road dialog must retain the captured Activity", compose.activity === captured)
            assertEquals(Lifecycle.State.RESUMED, captured.lifecycle.currentState)
            val display = checkNotNull(captured.display)
            assertTrue(
                "The owned road dialog must retain the original valid display",
                display.isValid && display.displayId == actualHostDisplay,
            )
        }
        assertEquals(
            "The focused window on the captured display must still belong to this app",
            instrument.targetContext.packageName,
            actualFocusedPackage(),
        )
        val active = checkNotNull(instrument.uiAutomation.rootInActiveWindow)
        try {
            assertEquals(instrument.targetContext.packageName, active.packageName?.toString())
        } finally {
            active.recycle()
        }
        assertEquals(1, nodes("review_road_memo_discard_dialog").size)
        compose.onNodeWithTag("review_road_memo_discard_dialog", useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithTag("review_road_sample_discard_warning", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun roadDiscardConfirmAction(): () -> Boolean {
        assertOwnedRoadDiscardDialog()
        compose.onNodeWithTag("review_road_memo_discard_confirm", useUnmergedTree = true).assertIsDisplayed()
        return checkNotNull(nodes("review_road_memo_discard_confirm").single().config[SemanticsActions.OnClick].action)
    }

    private fun dismissRoadDiscardDialog(confirm: Boolean) {
        assertOwnedRoadDiscardDialog()
        val action =
            compose
                .onNodeWithTag(
                    if (confirm) "review_road_memo_discard_confirm" else "review_road_memo_discard_cancel",
                    useUnmergedTree = true,
                ).assertIsDisplayed()
        assertOwnedRoadDiscardDialog()
        action.performClick()
        compose.waitForIdle()
        compose.waitUntil(20_000) {
            assertEquals(instrument.targetContext.packageName, actualFocusedPackage())
            nodes("review_road_memo_discard_dialog").isEmpty() && checkNotNull(actualHost).hasWindowFocus()
        }
        assertActualHost()
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
                    .put("configurationScope", catalogConfigurationScope())
                    .put("catalogResourceOverrideRequested", catalogResourceOverrideRequested)
                    .put("catalogResourceOverrideApplied", catalogResourceOverrideApplied.get())
                    .put("globalOsSettingsWrittenByFixture", false)
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
                check(layouts.isNotEmpty() && layouts.all(::visualTextFits)) {
                    "Large text must retain its complete actual layout: " +
                        layouts.map { layout ->
                            val text = layout.layoutInput.text.text
                            val glyphs = text.indices.filterNot { text[it].isWhitespace() }.map(layout::getBoundingBox)
                            mapOf(
                                "fontSp" to layout.layoutInput.style.fontSize.value,
                                "overflow" to layout.hasVisualOverflow,
                                "overflowWidth" to layout.didOverflowWidth,
                                "overflowHeight" to layout.didOverflowHeight,
                                "size" to layout.size,
                                "lines" to layout.lineCount,
                                "characters" to text.length,
                                "covered" to if (layout.lineCount > 0) layout.getLineEnd(layout.lineCount - 1, false) else 0,
                                "minGlyphTop" to glyphs.minOfOrNull { it.top },
                                "maxGlyphBottom" to glyphs.maxOfOrNull { it.bottom },
                                "minGlyphLeft" to glyphs.minOfOrNull { it.left },
                                "maxGlyphRight" to glyphs.maxOfOrNull { it.right },
                            )
                        }
                }
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
        var phase = "initial-stable-candidate-preparation"
        try {
            check(actualHost == null && actualHostDisplay == null)
            phase = "initial-same-Catalog-resume-request"
            val initialActivity = compose.activity
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            check(compose.activity === initialActivity) { "Preparation must retain the originally created Activity" }
            phase = "initial-stable-candidate-preparation"
            val args = InstrumentationRegistry.getArguments()
            val expectedFont = args.getString("expectedFontScale")
            val expectedDark = args.getString("expectedDark")
            check(expectedFont == "1.0" || expectedFont == "1.3")
            check(expectedDark == "false" || expectedDark == "true")
            val fontScale = checkNotNull(expectedFont).toFloat()
            val dark = expectedDark == "true"
            val deadline = SystemClock.elapsedRealtime() + 20_000
            var previous: ComponentCatalogActivity? = null
            var previousDisplay: Int? = null
            var previousConfiguration: Configuration? = null
            var firstValidAt: Long? = null
            while (SystemClock.elapsedRealtime() < deadline) {
                phase = "initial-provisional-candidate"
                var candidate: ComponentCatalogActivity? = null
                var displayId: Int? = null
                var configuration: Configuration? = null
                var prepared = false
                instrument.runOnMainSync {
                    val activity = compose.activity
                    val display = activity.display
                    val lifecycle = activity.lifecycle.currentState
                    val focused = activity.hasWindowFocus()
                    val displayValid = display?.isValid
                    val displayReadId = display?.displayId
                    val config = Configuration(activity.resources.configuration)
                    val configMatches = configurationMatches(config, fontScale, dark)
                    prepared = lifecycle == Lifecycle.State.RESUMED && focused && display != null && displayValid == true && configMatches
                    candidate = activity
                    displayId = displayReadId
                    configuration = config
                    originalGuardObservation =
                        hostGuardObservation(phase, activity, activity, lifecycle, focused, displayReadId, displayValid, config, prepared)
                }
                var valid = false
                if (prepared) {
                    phase = "initial-provisional-AX-query"
                    val observed = actualFocusedPackage(checkNotNull(displayId))
                    originalGuardObservation
                        ?.put("focusedPackageRead", observed ?: JSONObject.NULL)
                        ?.put("focusedPackageReadAtElapsedRealtimeMillis", SystemClock.elapsedRealtime())
                    if (observed != null) {
                        assertTrue("Known foreign initial focus cannot be substituted", observed == instrument.targetContext.packageName)
                        phase = "initial-provisional-post-AX-recheck"
                        instrument.runOnMainSync {
                            val current = compose.activity
                            val display = current.display
                            val lifecycle = current.lifecycle.currentState
                            val focused = current.hasWindowFocus()
                            val displayValid = display?.isValid
                            val displayReadId = display?.displayId
                            val config = Configuration(current.resources.configuration)
                            valid = current === candidate && lifecycle == Lifecycle.State.RESUMED && focused && display != null &&
                                displayValid == true && displayReadId == displayId && config == configuration &&
                                configurationMatches(config, fontScale, dark)
                            originalGuardObservation =
                                hostGuardObservation(
                                    phase,
                                    candidate,
                                    current,
                                    lifecycle,
                                    focused,
                                    displayReadId,
                                    displayValid,
                                    config,
                                    valid,
                                )?.put("precedingFocusedPackageRead", observed)
                            val observedAt = SystemClock.elapsedRealtime()
                            val earlier = firstValidAt
                            if (valid && previous === candidate && previousDisplay == displayId && previousConfiguration == config &&
                                earlier != null && observedAt - earlier >= 100 && observedAt < deadline
                            ) {
                                // The owner does not exist until both complete candidate observations agree.
                                actualHost = current
                                actualHostDisplay = checkNotNull(displayReadId)
                            }
                        }
                        if (actualHost != null) return
                    }
                }
                if (valid) {
                    if (previous !== candidate || previousDisplay != displayId || previousConfiguration != configuration) {
                        previous = candidate
                        previousDisplay = displayId
                        previousConfiguration = configuration
                        firstValidAt = SystemClock.elapsedRealtime()
                    }
                } else {
                    previous = null
                    previousDisplay = null
                    previousConfiguration = null
                    firstValidAt = null
                }
                SystemClock.sleep(50)
            }
            throw AssertionError("Initial real Compose host needs two agreeing observations at least 100ms apart within 20s")
        } catch (failure: Throwable) {
            recordHostFailure(phase, failure)
            throw failure
        }
    }

    private fun configurationMatches(
        config: Configuration,
        fontScale: Float,
        dark: Boolean,
    ): Boolean =
        abs(config.fontScale - fontScale) <= 0.01f &&
            (config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) == dark

    /** Owned actions never wait for a foreign/unknown window to return or recapture a display. */
    private fun assertActualHost() {
        var phase = "strict-owned-host-lifecycle"
        try {
            assertActualHostLifecycle()
            phase = "strict-owned-host-AX-query"
            val observed = actualFocusedPackage()
            originalGuardObservation
                ?.put("focusedPackageRead", observed ?: JSONObject.NULL)
                ?.put("focusedPackageReadAtElapsedRealtimeMillis", SystemClock.elapsedRealtime())
            assertTrue("Actual focused package is unknown at an owned action boundary", observed != null)
            assertTrue(
                "Known foreign focus at an owned action boundary must fail immediately",
                observed == instrument.targetContext.packageName,
            )
        } catch (failure: Throwable) {
            recordHostFailure(phase, failure)
            throw failure
        }
    }

    /** Diagnostics never issue a receipt, retry an action, substitute a package or change the original failure. */
    private fun recordHostFailure(
        phase: String,
        primary: Throwable,
    ) {
        try {
            writeHostFailure(phase, primary)
        } catch (failure: Throwable) {
            if (failure !== primary) primary.addSuppressed(failure)
        }
    }

    private fun writeHostFailure(
        phase: String,
        primary: Throwable,
    ) {
        val proof =
            JSONObject()
                .put("schema", 1)
                .put("kind", "controlled-host-failure-diagnostic")
                .put("status", "failure-diagnostic-unverified")
                .put("phase", phase)
                .put("observedAtElapsedRealtimeMillis", SystemClock.elapsedRealtime())
                .put("observationBasis", "fresh-after-original-guard-failure-not-an-atomic-replay-of-the-failed-condition")
                .put("primaryFailureClass", primary.javaClass.name)
                .put("foregroundSuccessEvidence", false)
                .put("configurationScope", catalogConfigurationScope())
                .put("catalogResourceOverrideRequested", catalogResourceOverrideRequested)
                .put("catalogResourceOverrideApplied", catalogResourceOverrideApplied.get())
                .put("globalOsSettingsWrittenByFixture", false)
                .put("actualSdkLocation", false)
                .put("permissionChanged", false)
                .put("pngStatus", "not-attempted")
        val errors = JSONArray()
        proof
            .put("diagnosticFailures", errors)
            .put("originalGuardObservation", originalGuardObservation?.let { JSONObject(it.toString()) } ?: JSONObject.NULL)

        fun attempt(
            stage: String,
            action: () -> Unit,
        ) {
            try {
                action()
            } catch (failure: Throwable) {
                errors.put(JSONObject().put("stage", stage).put("class", failure.javaClass.name))
                if (stage == "actual-window-surface-diagnostic" && proof.optString("pngStatus") != "unsupported-current-display") {
                    proof.put("pngStatus", "failed-unverified-window-copy")
                }
                if (failure !== primary) primary.addSuppressed(failure)
            }
        }
        var activity: ComponentCatalogActivity? = null
        var currentDisplay: Int? = null
        var defaultDisplay = false
        val directory =
            File(instrument.targetContext.cacheDir, "screenshots/foreground-location-controlled-host-failure-${UUID.randomUUID()}")
        attempt("actual-activities") {
            instrument.runOnMainSync {
                val current = compose.activity
                activity = current
                val display = current.display
                currentDisplay = display?.displayId
                defaultDisplay = display != null && display.isValid && display.displayId == Display.DEFAULT_DISPLAY
                val config = current.resources.configuration

                fun identity(value: ComponentCatalogActivity?): Any =
                    value?.let { activityNumbers.getOrPut(it) { activityNumbers.size + 1 } }
                        ?: JSONObject.NULL
                proof
                    .put("capturedActivitySequence", identity(actualHost))
                    .put("currentActivitySequence", identity(current))
                    .put("sameActivity", actualHost?.let { it === current } ?: JSONObject.NULL)
                    .put("currentLifecycle", current.lifecycle.currentState.name)
                    .put("currentHasWindowFocus", current.hasWindowFocus())
                    .put("currentDisplayKnown", display != null)
                    .put("currentDisplayValid", display?.isValid ?: JSONObject.NULL)
                    .put("currentDisplayId", display?.displayId ?: JSONObject.NULL)
                    .put("capturedDisplayId", actualHostDisplay ?: JSONObject.NULL)
                    .put("sameDisplay", actualHostDisplay?.let { it == display?.displayId } ?: JSONObject.NULL)
                    .put("currentFontScale", config.fontScale.toDouble())
                    .put("currentDark", config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
                val captured = actualHost
                proof
                    .put("capturedLifecycle", captured?.lifecycle?.currentState?.name ?: JSONObject.NULL)
                    .put("capturedHasWindowFocus", captured?.hasWindowFocus() ?: JSONObject.NULL)
                    .put("capturedDisplayCurrentlyValid", captured?.display?.isValid ?: JSONObject.NULL)
                    .put(
                        "capturedFontScale",
                        captured
                            ?.resources
                            ?.configuration
                            ?.fontScale
                            ?.toDouble() ?: JSONObject.NULL,
                    ).put(
                        "capturedDark",
                        captured?.resources?.configuration?.let {
                            it.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                        } ?: JSONObject.NULL,
                    )
            }
        }
        attempt("expected-configuration") {
            val args = InstrumentationRegistry.getArguments()
            proof
                .put("expectedFontScale", args.getString("expectedFontScale") ?: JSONObject.NULL)
                .put("expectedDark", args.getString("expectedDark") ?: JSONObject.NULL)
        }
        attempt("actual-focused-windows") {
            val flags = instrument.uiAutomation.serviceInfo.flags
            proof
                .put("automationFlags", flags)
                .put("interactiveWindowFlag", flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0)
            val scopedDisplay = actualHostDisplay ?: currentDisplay
            proof
                .put("focusedWindowDisplayId", scopedDisplay ?: JSONObject.NULL)
                .put("focusedWindowDisplayBasis", if (actualHostDisplay != null) "captured" else "current-provisional-not-a-receipt")
            val allDisplays = instrument.uiAutomation.windowsOnAllDisplays
            val allWindows = buildList { for (index in 0 until allDisplays.size()) addAll(allDisplays.valueAt(index)) }
            try {
                val windows = scopedDisplay?.let { allDisplays[it] }
                proof
                    .put("scopedWindowsKnown", windows != null)
                    .put("sameDisplayConsistent", windows?.all { it.displayId == scopedDisplay } ?: JSONObject.NULL)
                    .put("focusedWindowCount", windows?.count { it.isFocused } ?: JSONObject.NULL)
                val focused = JSONArray()
                windows?.filter { it.isFocused }?.forEach { window ->
                    val root = window.root
                    try {
                        focused.put(
                            JSONObject()
                                .put("displayId", window.displayId)
                                .put("type", window.type)
                                .put("focused", window.isFocused)
                                .put("active", window.isActive)
                                .put("rootKnown", root != null)
                                .put("package", root?.packageName?.toString() ?: JSONObject.NULL),
                        )
                    } finally {
                        root?.recycle()
                    }
                }
                proof.put("focusedWindows", focused)
            } finally {
                allWindows.forEach { it.recycle() }
            }
        }
        attempt("private-directory") { check(directory.mkdirs()) }
        attempt("actual-window-surface-diagnostic") {
            if (!defaultDisplay) proof.put("pngStatus", "unsupported-current-display")
            check(defaultDisplay) { "Window diagnostic is unsupported off the known actual default display" }
            proof.put("pngStatus", "pending-unverified-window-copy")
            val target = checkNotNull(activity)
            val size = Point()
            instrument.runOnMainSync {
                val display = checkNotNull(target.display)
                check(display.isValid && display.displayId == Display.DEFAULT_DISPLAY)
                size.set(target.window.decorView.width, target.window.decorView.height)
            }
            check(size.x > 0 && size.y > 0)
            val bitmap = Bitmap.createBitmap(size.x, size.y, Bitmap.Config.ARGB_8888)
            val complete = CountDownLatch(1)
            val result = AtomicInteger(-1)
            val releaseWhenComplete = AtomicBoolean(false)
            val recycled = AtomicBoolean(false)
            var submitted = false

            fun release() {
                if (recycled.compareAndSet(false, true)) bitmap.recycle()
            }
            try {
                PixelCopy.request(target.window, bitmap, { outcome ->
                    result.set(outcome)
                    complete.countDown()
                    if (releaseWhenComplete.get()) release()
                }, Handler(Looper.getMainLooper()))
                submitted = true
                check(complete.await(5, TimeUnit.SECONDS)) { "Actual window PixelCopy did not finish" }
                proof.put("pixelCopyResult", result.get())
                check(result.get() == PixelCopy.SUCCESS) { "Actual window surface was unavailable" }
                File(directory, "failure-diagnostic-unverified.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
                }
                proof
                    .put("pngStatus", "saved-failure-diagnostic-unverified")
                    .put("captureBasis", "PixelCopy-actual-test-Activity-window-surface-not-foreground-proof")
                    .put("pngWidth", bitmap.width)
                    .put("pngHeight", bitmap.height)
            } finally {
                releaseWhenComplete.set(true)
                if (!submitted || complete.count == 0L) release()
            }
        }
        attempt("save-private-diagnostic") { File(directory, "host-failure.json").writeText(proof.toString(2)) }
    }

    private fun assertActualHostLifecycle() {
        var allowed = false
        instrument.runOnMainSync {
            val activity = actualHost
            val current = compose.activity
            val display = activity?.display
            val lifecycle = activity?.lifecycle?.currentState
            val focused = activity?.hasWindowFocus()
            val config = activity?.resources?.configuration
            val displayReadId = display?.displayId
            val displayValid = display?.isValid
            allowed = activity != null && current === activity && lifecycle == Lifecycle.State.RESUMED && focused == true &&
                display != null && displayValid == true && displayReadId == actualHostDisplay
            originalGuardObservation =
                hostGuardObservation(
                    "strict-owned-host-condition",
                    activity,
                    current,
                    lifecycle,
                    focused,
                    displayReadId,
                    displayValid,
                    config,
                    allowed,
                )
        }
        assertTrue("The captured real Compose host must remain RESUMED and focused on the same valid display", allowed)
    }

    private fun hostGuardObservation(
        phase: String,
        expected: ComponentCatalogActivity?,
        current: ComponentCatalogActivity,
        lifecycle: Lifecycle.State?,
        focused: Boolean?,
        displayId: Int?,
        displayValid: Boolean?,
        config: Configuration?,
        allowed: Boolean,
    ): JSONObject? =
        try {
            fun identity(value: ComponentCatalogActivity?): Any =
                value?.let { activityNumbers.getOrPut(it) { activityNumbers.size + 1 } } ?: JSONObject.NULL
            JSONObject()
                .put("basis", "values-read-inside-the-original-main-thread-guard")
                .put("phase", phase)
                .put("observedAtElapsedRealtimeMillis", SystemClock.elapsedRealtime())
                .put("expectedActivitySequence", identity(expected))
                .put("currentActivitySequence", identity(current))
                .put("sameActivity", expected?.let { it === current } ?: JSONObject.NULL)
                .put("lifecycleRead", lifecycle?.name ?: JSONObject.NULL)
                .put("windowFocusRead", focused ?: JSONObject.NULL)
                .put("displayKnown", displayId != null)
                .put("displayValidRead", displayValid ?: JSONObject.NULL)
                .put("displayIdRead", displayId ?: JSONObject.NULL)
                .put("capturedDisplayId", actualHostDisplay ?: JSONObject.NULL)
                .put("fontScaleRead", config?.fontScale?.toDouble() ?: JSONObject.NULL)
                .put(
                    "darkRead",
                    config?.let { it.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES }
                        ?: JSONObject.NULL,
                ).put("guardAllowed", allowed)
        } catch (_: Throwable) {
            null
        }

    private fun actualFocusedPackage(display: Int = checkNotNull(actualHostDisplay)): String? {
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
        private val mapEnabled: Boolean = false,
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
        lateinit var reviewRoadModel: SessionReviewRoadViewModel
        val reviewReadFailure = MutableStateFlow(false)
        val reviewReadAttempts = AtomicInteger()
        val sampleWriteFailure = AtomicInteger()
        val sampleWriteAttempts = AtomicInteger()
        private val reviewReads =
            object : SessionReviewRoadRepository by store.reviewRoads {
                override fun observe(sessionId: String): Flow<SessionReviewRoad?> =
                    flow {
                        reviewReadAttempts.incrementAndGet()
                        emitAll(
                            combine(reviewReadFailure, store.reviewRoads.observe(sessionId)) { failed, stored ->
                                if (failed) throw IOException("Controlled read failure in the owned review store")
                                stored
                            },
                        )
                    }

                override suspend fun setSample(
                    expectedSource: PracticeSession,
                    expectedCurrent: SessionReviewRoad,
                    sample: SessionReviewSample?,
                    canCommit: () -> Boolean,
                ): SessionReviewRoadWriteResult {
                    sampleWriteAttempts.incrementAndGet()
                    val failure = sampleWriteFailure.getAndSet(0)
                    if (failure == 1) throw IOException("Controlled failure before the owned sample association write")
                    val result = store.reviewRoads.setSample(expectedSource, expectedCurrent, sample, canCommit)
                    if (failure == 2 && result is SessionReviewRoadWriteResult.Saved) {
                        throw IOException("Controlled acknowledgement loss after the owned sample association commit")
                    }
                    return result
                }
            }
        private val navigationCalls = AtomicInteger()
        private val backgroundCalls = AtomicInteger()
        private var historyOpenRunForCleanup: LocationRecordingRun? = null

        suspend fun prepare() {
            store.preparation.saveSelection(selection()) { true }
        }

        fun mount() {
            val pointMap: (
                @Composable (
                    PracticeSession,
                    LocationRecordingRun,
                    List<LocationRecordingEvent>,
                    LocationRecordingEvent?,
                    () -> Boolean,
                    (SessionReviewSample) -> Boolean,
                ) -> Unit
            )? =
                if (mapEnabled) {
                    { source, run, events, selectedEvent, canRead, openLinked ->
                        val candidate =
                            selectedEvent?.takeIf { it.reading is LocationUpdateReading.Sample }?.let {
                                SessionReviewSample
                                    .from(
                                        run,
                                        it,
                                    )
                            }
                        SessionReviewRoadRoute(
                            source,
                            canRead,
                            viewModel = reviewRoadModel,
                            readOnly = true,
                            comparisonMap = { saved, courseRoads, saveCourseRoad, setRoadSection, allowed, changes ->
                                StoredLocationMap(
                                    run,
                                    events,
                                    selectedEvent,
                                    allowed,
                                    reviewRoad = saved,
                                    inputChanges = changes,
                                    courseRoads = courseRoads,
                                    onSaveCourseRoad = saveCourseRoad,
                                    onSetRoadSection = setRoadSection,
                                )
                            },
                            sampleCandidate = candidate,
                            onOpenLinkedSample = openLinked,
                        )
                    }
                } else {
                    null
                }
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
            locationHistoryModel =
                LocationHistoryViewModel(store.sessions, store.locationRecordings, vehicleGateway, restrictions, store.memos)
            reviewRoadModel = SessionReviewRoadViewModel(vehicleGateway, restrictions, store.sessions, reviewReads, store.catalog)
            // Add explicitly passed models so final ViewModelStore.clear closes all their scopes too.
            val additional = listOf<ViewModel>(recordingModel, historyModel, locationHistoryModel, reviewRoadModel)
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
                                        locationRecording = controller.state,
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
                                        reviewRoad =
                                            if (mapEnabled) {
                                                { source, allowed -> SessionReviewRoadRoute(source, allowed, viewModel = reviewRoadModel) }
                                            } else {
                                                null
                                            },
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
                                        storedPointMap = pointMap,
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

        fun seedStopReasonHistory(
            source: PracticeSession,
            reasons: List<LocationRecordingStopReason>,
        ): LocationRecordingRun =
            runBlocking {
                compose.waitUntil(20_000) { controller.state.value == LocationRecordingState.Idle }

                suspend fun begin(owner: String) =
                    (
                        store.locationRecordings.begin(
                            source,
                            UUID.randomUUID().toString(),
                            owner,
                            clock.millis(),
                            elapsed.incrementAndGet(),
                        ) { true } as LocationRecordingBeginResult.Begun
                    ).run

                for (reason in reasons) {
                    val run =
                        begin(
                            if (reason ==
                                LocationRecordingStopReason.PROCESS_INTERRUPTED
                            ) {
                                UUID.randomUUID().toString()
                            } else {
                                processId
                            },
                        )
                    val reading =
                        if (reason ==
                            LocationRecordingStopReason.USER_STOP
                        ) {
                            sample(12.5)
                        } else {
                            LocationUpdateReading.Empty(elapsed.incrementAndGet())
                        }
                    assertTrue(
                        store.locationRecordings.append(source, run, reading, clock.millis()) { true } is
                            io.github.hangillab.hangil.core.domain.LocationRecordingAppendResult.Appended,
                    )
                    if (reason == LocationRecordingStopReason.PROCESS_INTERRUPTED) {
                        // Interrupted is recovery metadata, never a fabricated ordinary end time.
                        store.locationRecordings.recoverInterrupted(processId)
                    } else {
                        assertTrue(
                            store.locationRecordings.end(
                                run,
                                reason.status,
                                reason,
                                clock.millis(),
                                elapsed.incrementAndGet(),
                            ) is LocationRecordingEndResult.Ended,
                        )
                    }
                    val stored =
                        store.locationRecordings
                            .observe(source.sessionId)
                            .first()
                            .runs
                            .last()
                    assertEquals(reason, stored.stopReason)
                    assertEquals(reason.status, stored.status)
                }
                // Keep this owned OPEN immutable during all read assertions; close it only during fixture disposal.
                begin(processId).also { historyOpenRunForCleanup = it }
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
                historyOpenRunForCleanup?.let { run ->
                    check(
                        store.locationRecordings.end(
                            run,
                            LocationRecordingRunStatus.STOPPED,
                            LocationRecordingStopReason.USER_STOP,
                            clock.millis(),
                            elapsed.incrementAndGet(),
                        ) is LocationRecordingEndResult.Ended,
                    ) { "The read-only OPEN fixture must close only through its exact stored owner" }
                    historyOpenRunForCleanup = null
                }
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
        const val STORED_POINT_SOURCE = "hangil-stored-location-point"
        const val STORED_POINT_LAYER = "hangil-stored-location-circle"
        const val STORED_POINT_SELECTION = "hangil-stored-location-selection"

        fun selection() = PracticeSelection("commute", "neighborhood", 1, 1, "sequence")

        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))
    }
}
