package io.github.hangillab.hangil

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import io.github.hangillab.hangil.core.designsystem.HanGilTheme
import io.github.hangillab.hangil.core.designsystem.ParkedPage
import io.github.hangillab.hangil.core.domain.LocationRecordingController
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionReviewSample
import io.github.hangillab.hangil.core.vehicle.FusedLocationPermission
import io.github.hangillab.hangil.feature.course.CourseRoute
import io.github.hangillab.hangil.feature.course.NextPracticeRoute
import io.github.hangillab.hangil.feature.home.FinishedSessionReviewRoute
import io.github.hangillab.hangil.feature.home.HomeRoute
import io.github.hangillab.hangil.feature.home.LocationHistoryRoute
import io.github.hangillab.hangil.feature.home.LocationRecordingRoute
import io.github.hangillab.hangil.feature.home.LocationRecordingStatusRow
import io.github.hangillab.hangil.feature.home.LocationRecordingViewModel
import io.github.hangillab.hangil.feature.home.SessionHistoryRoute
import io.github.hangillab.hangil.feature.home.SessionRecordingViewModel
import io.github.hangillab.hangil.feature.home.SessionReviewRoadRoute
import io.github.hangillab.hangil.feature.map.SessionReviewRoadMap
import io.github.hangillab.hangil.feature.map.StoredLocationMap
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val recording: SessionRecordingViewModel by viewModels()
    private val locationControls: LocationRecordingViewModel by viewModels()

    @Inject lateinit var locationRecording: LocationRecordingController
    private val finishedReviews: ExactSessionNavigationViewModel by viewModels()
    private val locationHistory by lazy {
        ViewModelProvider(this)[ExactSessionNavigationViewModel.LOCATION_HISTORY_KEY, ExactSessionNavigationViewModel::class.java]
    }
    private val historyNextPractice: HistoryNextPracticeNavigationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { recording.recordWhileStarted() }
                launch { locationRecording.recordWhileStarted() }
            }
        }
        val showDiagnostics = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        setContent {
            HanGilTheme {
                val recordingState by recording.state.collectAsStateWithLifecycle()
                val finishedReviewEntry by finishedReviews.entry.collectAsStateWithLifecycle()
                val locationHistoryEntry by locationHistory.entry.collectAsStateWithLifecycle()
                val historyNextEntry by historyNextPractice.entry.collectAsStateWithLifecycle()
                val locationState by locationRecording.state.collectAsStateWithLifecycle()
                val navigation = rememberNavController()

                fun openLocationHistory(source: PracticeSession) {
                    val token = locationHistory.open(source) ?: return
                    navigation.navigate("location-history/$token") { launchSingleTop = true }
                }

                fun closeLocationHistory(
                    entry: NavBackStackEntry,
                    token: String? = null,
                ) {
                    if (navigation.currentBackStackEntry === entry &&
                        lifecycle.currentState == Lifecycle.State.RESUMED &&
                        entry.lifecycle.currentState == Lifecycle.State.RESUMED
                    ) {
                        locationHistory.clear(token)
                        navigation.popBackStack()
                    }
                }
                Column(Modifier.fillMaxSize()) {
                    LocationRecordingStatusRow(locationState) { locationRecording.stop(it) }
                    NavHost(navigation, startDestination = "home", modifier = Modifier.weight(1f)) {
                        composable("home") { homeEntry ->
                            HomeRoute(
                                onPrepare = { navigation.navigate("preparation") { launchSingleTop = true } },
                                onMapPreview = { navigation.navigate("map") { launchSingleTop = true } },
                                onHistory = { navigation.navigate("history") { launchSingleTop = true } },
                                onPrepareNext = { id ->
                                    navigation.navigate("preparation/next/${Uri.encode(id)}") { launchSingleTop = true }
                                },
                                onFinishedReview = openReview@{ source ->
                                    if (navigation.currentBackStackEntry !== homeEntry ||
                                        !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                                        !homeEntry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                                    ) {
                                        return@openReview
                                    }
                                    val token = finishedReviews.open(source) ?: return@openReview
                                    navigation.navigate("finished-review/$token") { launchSingleTop = true }
                                },
                                showDiagnostics = showDiagnostics,
                                recordingState = recordingState,
                                onRetryRecording = { recording.retry() },
                                onLocationRecording = { navigation.navigate("location-recording") { launchSingleTop = true } },
                            )
                        }
                        composable("history") { historyEntry ->
                            SessionHistoryRoute(
                                reviewRoad = { source, allowed ->
                                    SessionReviewRoadRoute(source, allowed, roadMap = { snapshot, roads, selected, canPresent, changes ->
                                        SessionReviewRoadMap(snapshot, roads, selected, canPresent, changes)
                                    })
                                },
                                onClose = { navigation.popBackStack() },
                                onOpenLocations = openLocations@{ source ->
                                    if (navigation.currentBackStackEntry !== historyEntry ||
                                        lifecycle.currentState != Lifecycle.State.RESUMED ||
                                        historyEntry.lifecycle.currentState != Lifecycle.State.RESUMED
                                    ) {
                                        return@openLocations
                                    }
                                    openLocationHistory(source)
                                },
                                onPrepareNext = openNext@{ source ->
                                    if (navigation.currentBackStackEntry !== historyEntry ||
                                        lifecycle.currentState != Lifecycle.State.RESUMED ||
                                        historyEntry.lifecycle.currentState != Lifecycle.State.RESUMED
                                    ) {
                                        return@openNext
                                    }
                                    val token = historyNextPractice.open(source) ?: return@openNext
                                    navigation.navigate("preparation/history-next/$token/${Uri.encode(source.sessionId)}") {
                                        launchSingleTop = true
                                    }
                                },
                            )
                        }
                        composable(
                            "finished-review/{finishedReviewToken}",
                            arguments = listOf(navArgument("finishedReviewToken") { type = NavType.StringType }),
                        ) { reviewEntry ->
                            val token = reviewEntry.arguments?.getString("finishedReviewToken")
                            val expected = finishedReviewEntry?.source(token)
                            FinishedSessionReviewRoute(
                                reviewRoad = { source, allowed ->
                                    SessionReviewRoadRoute(source, allowed, roadMap = { snapshot, roads, selected, canPresent, changes ->
                                        SessionReviewRoadMap(snapshot, roads, selected, canPresent, changes)
                                    })
                                },
                                expectedSource = expected,
                                onPrepareNext = openNext@{ source ->
                                    if (navigation.currentBackStackEntry !== reviewEntry ||
                                        lifecycle.currentState != Lifecycle.State.RESUMED ||
                                        reviewEntry.lifecycle.currentState != Lifecycle.State.RESUMED ||
                                        expected != source ||
                                        finishedReviews.source(token) != source
                                    ) {
                                        return@openNext
                                    }
                                    val nextToken = historyNextPractice.open(source) ?: return@openNext
                                    navigation.navigate("preparation/history-next/$nextToken/${Uri.encode(source.sessionId)}") {
                                        launchSingleTop = true
                                    }
                                },
                                onOpenLocations = openLocations@{ source ->
                                    if (navigation.currentBackStackEntry !== reviewEntry ||
                                        !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                                        !reviewEntry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) ||
                                        expected != source ||
                                        finishedReviews.source(token) != source
                                    ) {
                                        return@openLocations
                                    }
                                    openLocationHistory(source)
                                },
                                onClose = {
                                    if (navigation.currentBackStackEntry === reviewEntry &&
                                        lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                        reviewEntry.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
                                    ) {
                                        finishedReviews.clear(token)
                                        navigation.popBackStack()
                                    }
                                },
                            )
                        }
                        composable("location-recording") {
                            val permission =
                                rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
                                    locationControls.refreshPermission()
                                }
                            LocationRecordingRoute(
                                onClose = { navigation.popBackStack() },
                                onRequestPermission = { permission.launch(FusedLocationPermission.NAME) },
                                viewModel = locationControls,
                            )
                        }
                        // The prior route can still be restored without an in-memory source.
                        composable("location-history") { legacyEntry ->
                            val close = { closeLocationHistory(legacyEntry) }
                            BackHandler(onBack = close)
                            LocationHistoryReopen(onClose = close)
                        }
                        composable(
                            "location-history/{locationHistoryToken}",
                            arguments = listOf(navArgument("locationHistoryToken") { type = NavType.StringType }),
                        ) { locationEntry ->
                            val token = locationEntry.arguments?.getString("locationHistoryToken")
                            val expected = locationHistoryEntry?.source(token)
                            val close = { closeLocationHistory(locationEntry, token) }
                            BackHandler(onBack = close)
                            if (expected == null) {
                                LocationHistoryReopen(onClose = close)
                            } else {
                                LocationHistoryRoute(
                                    expectedSource = expected,
                                    onClose = close,
                                    storedPointMap = { source, run, events, selectedEvent, canRead, openLinkedSample ->
                                        val candidate =
                                            selectedEvent?.takeIf { it.reading is LocationUpdateReading.Sample }?.let {
                                                SessionReviewSample.from(run, it)
                                            }
                                        SessionReviewRoadRoute(
                                            expectedSource = source,
                                            canKeepOpen = canRead,
                                            readOnly = true,
                                            sampleCandidate = candidate,
                                            onOpenLinkedSample = openLinkedSample,
                                            comparisonMap = { saved, allowed, changes ->
                                                StoredLocationMap(
                                                    run,
                                                    events,
                                                    selectedEvent,
                                                    allowed,
                                                    reviewRoad = saved,
                                                    inputChanges = changes,
                                                )
                                            },
                                        )
                                    },
                                )
                            }
                        }
                        composable("preparation") { CourseRoute(onClose = { navigation.popBackStack() }) }
                        composable(
                            "preparation/next/{nextPracticeSourceId}",
                            arguments = listOf(navArgument("nextPracticeSourceId") { type = NavType.StringType }),
                        ) {
                            NextPracticeRoute(
                                reviewRoad = { source, allowed ->
                                    SessionReviewRoadRoute(source, allowed, readOnly = true, roadMap = {
                                        snapshot,
                                        roads,
                                        selected,
                                        canPresent,
                                        changes,
                                        ->
                                        SessionReviewRoadMap(snapshot, roads, selected, canPresent, changes)
                                    })
                                },
                                onClose = { navigation.popBackStack() },
                                onPrepareNormally = {
                                    navigation.popBackStack()
                                    navigation.navigate("preparation") { launchSingleTop = true }
                                },
                            )
                        }
                        composable(
                            "preparation/history-next/{historyNextToken}/{nextPracticeSourceId}",
                            arguments =
                                listOf(
                                    navArgument("historyNextToken") { type = NavType.StringType },
                                    navArgument("nextPracticeSourceId") { type = NavType.StringType },
                                ),
                        ) { nextEntry ->
                            val token = nextEntry.arguments?.getString("historyNextToken")
                            val sourceId = nextEntry.arguments?.getString("nextPracticeSourceId")
                            val expected = historyNextEntry?.takeIf { it.token == token && it.source.sessionId == sourceId }?.source
                            val currentEntry: () -> Boolean =
                                remember(nextEntry, token, sourceId, expected) {
                                    {
                                        navigation.currentBackStackEntry === nextEntry &&
                                            lifecycle.currentState == Lifecycle.State.RESUMED &&
                                            nextEntry.lifecycle.currentState == Lifecycle.State.RESUMED &&
                                            historyNextPractice.source(token, sourceId) == expected && expected != null
                                    }
                                }
                            val canNavigate: () -> Boolean =
                                remember(nextEntry) {
                                    {
                                        navigation.currentBackStackEntry === nextEntry &&
                                            lifecycle.currentState == Lifecycle.State.RESUMED &&
                                            nextEntry.lifecycle.currentState == Lifecycle.State.RESUMED
                                    }
                                }
                            if (expected == null) {
                                ParkedPage {
                                    Text(getString(R.string.history_next_reopen))
                                    TextButton(onClick = {
                                        if (canNavigate()) {
                                            historyNextPractice.clear(token)
                                            navigation.popBackStack()
                                        }
                                    }) { Text(getString(R.string.history_next_back)) }
                                }
                            } else {
                                NextPracticeRoute(
                                    reviewRoad = { source, allowed ->
                                        SessionReviewRoadRoute(source, allowed, readOnly = true, roadMap = {
                                            snapshot,
                                            roads,
                                            selected,
                                            canPresent,
                                            changes,
                                            ->
                                            SessionReviewRoadMap(snapshot, roads, selected, canPresent, changes)
                                        })
                                    },
                                    expectedHistorySource = expected,
                                    historyEntryRequired = true,
                                    isHistoryEntryCurrent = currentEntry,
                                    onClose = {
                                        if (canNavigate() && currentEntry()) {
                                            historyNextPractice.clear(token)
                                            navigation.popBackStack()
                                        }
                                    },
                                    onPrepareNormally = {
                                        if (canNavigate() && currentEntry()) {
                                            historyNextPractice.clear(token)
                                            navigation.popBackStack()
                                            navigation.navigate("preparation") { launchSingleTop = true }
                                        }
                                    },
                                )
                            }
                        }
                        composable("map") { MapDestination(onClose = { navigation.popBackStack() }, showDiagnostics = showDiagnostics) }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocationHistoryReopen(onClose: () -> Unit) {
    ParkedPage {
        Text(stringResource(R.string.location_history_reopen))
        TextButton(onClick = onClose) { Text(stringResource(R.string.location_history_missing_back)) }
    }
}
