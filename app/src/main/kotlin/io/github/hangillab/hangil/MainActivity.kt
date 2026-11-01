package io.github.hangillab.hangil

import android.content.pm.ApplicationInfo
import android.net.Uri
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
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import io.github.hangillab.hangil.core.designsystem.HanGilTheme
import io.github.hangillab.hangil.feature.course.CourseRoute
import io.github.hangillab.hangil.feature.course.NextPracticeRoute
import io.github.hangillab.hangil.feature.home.FinishedSessionReviewRoute
import io.github.hangillab.hangil.feature.home.HomeRoute
import io.github.hangillab.hangil.feature.home.SessionHistoryRoute
import io.github.hangillab.hangil.feature.home.SessionRecordingViewModel
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val recording: SessionRecordingViewModel by viewModels()
    private val finishedReviews: FinishedReviewNavigationViewModel by viewModels()

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
                val finishedReviewEntry by finishedReviews.entry.collectAsStateWithLifecycle()
                val navigation = rememberNavController()
                NavHost(navigation, startDestination = "home") {
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
                        )
                    }
                    composable("history") { SessionHistoryRoute(onClose = { navigation.popBackStack() }) }
                    composable(
                        "finished-review/{finishedReviewToken}",
                        arguments = listOf(navArgument("finishedReviewToken") { type = NavType.StringType }),
                    ) { reviewEntry ->
                        val token = reviewEntry.arguments?.getString("finishedReviewToken")
                        val expected = finishedReviewEntry?.takeIf { it.token == token }?.source
                        FinishedSessionReviewRoute(
                            expectedSource = expected,
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
