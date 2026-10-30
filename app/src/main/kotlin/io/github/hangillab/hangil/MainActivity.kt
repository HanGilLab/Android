package io.github.hangillab.hangil

import android.content.pm.ApplicationInfo
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import io.github.hangillab.hangil.core.designsystem.HanGilTheme
import io.github.hangillab.hangil.feature.course.CourseRoute
import io.github.hangillab.hangil.feature.home.HomeRoute
import io.github.hangillab.hangil.feature.home.SessionRecordingViewModel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val recording: SessionRecordingViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) { recording.recordWhileStarted() }
        }
        val showDiagnostics = applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        setContent {
            HanGilTheme {
                val recordingState by recording.state.collectAsStateWithLifecycle()
                val navigation = rememberNavController()
                NavHost(navigation, startDestination = "home") {
                    composable("home") {
                        HomeRoute(
                            onPrepare = { navigation.navigate("preparation") { launchSingleTop = true } },
                            onMapPreview = { navigation.navigate("map") { launchSingleTop = true } },
                            showDiagnostics = showDiagnostics,
                            recordingState = recordingState,
                            onRetryRecording = { recording.retry() },
                        )
                    }
                    composable("preparation") { CourseRoute(onClose = { navigation.popBackStack() }) }
                    composable("map") { MapDestination(onClose = { navigation.popBackStack() }, showDiagnostics = showDiagnostics) }
                }
            }
        }
    }
}
