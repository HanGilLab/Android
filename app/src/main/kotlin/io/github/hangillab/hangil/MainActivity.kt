package io.github.hangillab.hangil

import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import io.github.hangillab.hangil.core.designsystem.HanGilTheme
import io.github.hangillab.hangil.core.designsystem.ParkedPage
import io.github.hangillab.hangil.core.domain.LocationRecordingController
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.vehicle.FusedLocationPermission
import io.github.hangillab.hangil.feature.course.CourseRoute
import io.github.hangillab.hangil.feature.course.NextPracticeRoute
import io.github.hangillab.hangil.feature.home.HomeRoute
import io.github.hangillab.hangil.feature.home.LocationHistoryRoute
import io.github.hangillab.hangil.feature.home.LocationRecordingRoute
import io.github.hangillab.hangil.feature.home.LocationRecordingStatusRow
import io.github.hangillab.hangil.feature.home.LocationRecordingViewModel
import io.github.hangillab.hangil.feature.home.SessionHistoryRoute
import io.github.hangillab.hangil.feature.home.SessionRecordingViewModel
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val recording: SessionRecordingViewModel by viewModels()
    private val locationControls: LocationRecordingViewModel by viewModels()

    @Inject lateinit var locationRecording: LocationRecordingController

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
                val locationState by locationRecording.state.collectAsStateWithLifecycle()
                var locationHistorySource by remember { mutableStateOf<PracticeSession?>(null) }
                val navigation = rememberNavController()
                Column(Modifier.fillMaxSize()) {
                    LocationRecordingStatusRow(locationState) { locationRecording.stop(it) }
                    NavHost(navigation, startDestination = "home", modifier = Modifier.weight(1f)) {
                        composable("home") {
                            HomeRoute(
                                onPrepare = { navigation.navigate("preparation") { launchSingleTop = true } },
                                onMapPreview = { navigation.navigate("map") { launchSingleTop = true } },
                                onHistory = { navigation.navigate("history") { launchSingleTop = true } },
                                onPrepareNext = { id ->
                                    navigation.navigate("preparation/next/${Uri.encode(id)}") { launchSingleTop = true }
                                },
                                showDiagnostics = showDiagnostics,
                                recordingState = recordingState,
                                onRetryRecording = { recording.retry() },
                                onLocationRecording = { navigation.navigate("location-recording") { launchSingleTop = true } },
                            )
                        }
                        composable("history") {
                            SessionHistoryRoute(
                                onClose = { navigation.popBackStack() },
                                onOpenLocations = { expected ->
                                    locationHistorySource = expected
                                    navigation.navigate("location-history") { launchSingleTop = true }
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
                        composable("location-history") {
                            val expected = locationHistorySource
                            if (expected == null) {
                                ParkedPage {
                                    Text(getString(R.string.location_history_reopen))
                                    TextButton(
                                        onClick = { navigation.popBackStack() },
                                    ) { Text(getString(R.string.location_history_missing_back)) }
                                }
                            } else {
                                LocationHistoryRoute(expectedSource = expected, onClose = { navigation.popBackStack() })
                            }
                        }
                        composable("preparation") { CourseRoute(onClose = { navigation.popBackStack() }) }
                        composable(
                            "preparation/next/{nextPracticeSourceId}",
                            arguments = listOf(navArgument("nextPracticeSourceId") { type = NavType.StringType }),
                        ) {
                            NextPracticeRoute(
                                onClose = { navigation.popBackStack() },
                                onPrepareNormally = {
                                    navigation.popBackStack()
                                    navigation.navigate("preparation") { launchSingleTop = true }
                                },
                            )
                        }
                        composable("map") { MapDestination(onClose = { navigation.popBackStack() }, showDiagnostics = showDiagnostics) }
                    }
                }
            }
        }
    }
}
