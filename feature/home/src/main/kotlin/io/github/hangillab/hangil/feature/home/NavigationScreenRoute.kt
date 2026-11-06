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
import io.github.hangillab.hangil.core.domain.PreparedPractice
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun NavigationScreenRoute(
    viewModel: NavigationScreenViewModel = hiltViewModel(),
    prepared: PreparedPractice? = null,
    preparationRevision: Long = 0,
    canUsePreparedRoute: (PreparedPractice, Long) -> Boolean = { _, _ -> false },
) {
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
        if (displayed.routePreparationRevision == null) {
            displayed.result?.let { NavigationRequestResult(it, courseRoute = false) }
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
    val route = prepared?.course?.navigationRoute
    if (prepared != null && route != null && canUsePreparedRoute(prepared, preparationRevision)) {
        ParkedPanel(Modifier.testTag("navigation_course_route_panel")) {
            Text(stringResource(R.string.navigation_route_title), style = MaterialTheme.typography.titleLarge)
            Text(
                stringResource(R.string.navigation_route_destination, route.destination.name),
                Modifier.testTag("navigation_course_route_destination"),
                style = MaterialTheme.typography.bodyLarge,
            )
            route.waypoints.forEachIndexed { index, point ->
                Text(
                    stringResource(R.string.navigation_route_waypoint, index + 1, point.name),
                    Modifier.testTag("navigation_course_route_waypoint"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Text(stringResource(R.string.navigation_route_handoff), style = MaterialTheme.typography.bodyLarge)
            if (displayed.routePreparationRevision == preparationRevision) {
                displayed.result?.let { NavigationRequestResult(it, courseRoute = true) }
            }
            key(displayed, preparationRevision) {
                val start: () -> Unit = {
                    viewModel.startRoute(displayed, route, preparationRevision) {
                        canUsePreparedRoute(prepared, preparationRevision)
                    }
                }
                ParkedButton(
                    onClick = start,
                    enabled = displayed.phase != NavigationScreenUiState.Phase.OPENING,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("navigation_course_route_start")
                            .frozenObservationClick(start),
                ) { Text(stringResource(R.string.navigation_route_start), style = MaterialTheme.typography.titleLarge) }
            }
        }
    }
}

@Composable
private fun NavigationRequestResult(
    result: NavigationScreenResult,
    courseRoute: Boolean,
) {
    val resource =
        when (result) {
            NavigationScreenResult.REQUESTED ->
                if (courseRoute) R.string.navigation_route_guidance_unconfirmed else R.string.navigation_screen_requested
            NavigationScreenResult.BLOCKED ->
                if (courseRoute) R.string.navigation_route_blocked else R.string.navigation_screen_blocked
            NavigationScreenResult.UNSUPPORTED -> R.string.navigation_screen_unsupported
            NavigationScreenResult.PERMISSION_DENIED ->
                if (courseRoute) R.string.navigation_route_permission else R.string.navigation_screen_permission
            NavigationScreenResult.TIMED_OUT -> R.string.navigation_screen_timeout
            NavigationScreenResult.ERROR ->
                if (courseRoute) R.string.navigation_route_guidance_unconfirmed else R.string.navigation_screen_error
            NavigationScreenResult.GUIDANCE_STARTED -> R.string.navigation_route_guidance_started
            NavigationScreenResult.GUIDANCE_UNCONFIRMED -> R.string.navigation_route_guidance_unconfirmed
            NavigationScreenResult.ACTIVE_ROUTE -> R.string.navigation_route_active
        }
    Text(stringResource(resource), Modifier.testTag("navigation_screen_result"), style = MaterialTheme.typography.bodyLarge)
}
