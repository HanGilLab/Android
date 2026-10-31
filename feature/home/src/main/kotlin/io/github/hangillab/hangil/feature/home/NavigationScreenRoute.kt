package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.ParkedButton
import io.github.hangillab.hangil.core.designsystem.ParkedPanel
import io.github.hangillab.hangil.core.domain.NavigationScreenResult
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun NavigationScreenRoute(viewModel: NavigationScreenViewModel = hiltViewModel()) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var lease by remember(viewModel, lifecycle) { mutableStateOf(0L) }
    DisposableEffect(viewModel, lifecycle) {
        var alive = AtomicBoolean(false)

        fun stop() {
            alive.set(false)
            viewModel.endHost(lease)
            lease = 0
        }

        fun start() {
            stop()
            val owner = AtomicBoolean(true)
            alive = owner
            lease = viewModel.beginHost { owner.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        }
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> start()
                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> stop()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) start()
        onDispose {
            stop()
            lifecycle.removeObserver(observer)
        }
    }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val displayed = state
    if (displayed.host != lease || displayed.phase == NavigationScreenUiState.Phase.CLOSED ||
        displayed.phase == NavigationScreenUiState.Phase.LOCKED
    ) {
        return
    }
    ParkedPanel(Modifier.testTag("navigation_screen_panel")) {
        Text(stringResource(R.string.navigation_screen_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.navigation_screen_scope),
            Modifier.testTag("navigation_screen_scope"),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (displayed.phase == NavigationScreenUiState.Phase.OPENING) {
            Text(
                stringResource(R.string.navigation_screen_connecting),
                Modifier.testTag("navigation_screen_connecting"),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        displayed.result?.let { result ->
            val resource =
                when (result) {
                    NavigationScreenResult.REQUESTED -> R.string.navigation_screen_requested
                    NavigationScreenResult.BLOCKED -> R.string.navigation_screen_blocked
                    NavigationScreenResult.UNSUPPORTED -> R.string.navigation_screen_unsupported
                    NavigationScreenResult.PERMISSION_DENIED -> R.string.navigation_screen_permission
                    NavigationScreenResult.TIMED_OUT -> R.string.navigation_screen_timeout
                    NavigationScreenResult.ERROR -> R.string.navigation_screen_error
                }
            Text(stringResource(resource), Modifier.testTag("navigation_screen_result"), style = MaterialTheme.typography.bodyLarge)
        }
        key(displayed) {
            val open: () -> Unit = { viewModel.open(displayed) }
            ParkedButton(
                onClick = open,
                enabled = displayed.phase != NavigationScreenUiState.Phase.OPENING,
                prominent = false,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                        .testTag("navigation_screen_open")
                        .frozenObservationClick(open),
            ) { Text(stringResource(R.string.navigation_screen_open), style = MaterialTheme.typography.titleLarge) }
        }
    }
}
