package io.github.hangillab.hangil.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.designsystem.PracticePanel
import io.github.hangillab.hangil.core.domain.LocationReading

@Composable
internal fun LocationProbeRoute(
    requestPermission: ((LocationPermissionResult) -> Unit) -> Unit,
    viewModel: LocationProbeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    DisposableEffect(viewModel) { onDispose { viewModel.onHidden() } }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onHidden() }
    PracticePanel(Modifier.testTag("location_probe")) {
        Text(stringResource(R.string.location_probe_title), style = MaterialTheme.typography.titleLarge)
        Text(
            stringResource(R.string.location_probe_scope),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val result = (state as? LocationProbeState.Finished)?.reading
        val status =
            when (state) {
                LocationProbeState.Idle -> R.string.location_probe_idle
                LocationProbeState.Loading -> R.string.location_probe_loading
                LocationProbeState.AwaitingPermission -> R.string.location_probe_permission_pending
                is LocationProbeState.Finished ->
                    when (result) {
                        is LocationReading.LastKnown -> R.string.location_probe_last_known
                        LocationReading.Empty -> R.string.location_probe_empty
                        LocationReading.Unsupported -> R.string.location_probe_unsupported
                        LocationReading.PermissionDenied -> R.string.location_probe_permission_denied
                        LocationReading.TimedOut -> R.string.location_probe_timed_out
                        LocationReading.Error, null -> R.string.location_probe_error
                    }
            }
        Text(stringResource(status), Modifier.testTag("location_status"), style = MaterialTheme.typography.titleLarge)
        if (result is LocationReading.LastKnown) {
            Text(
                stringResource(R.string.location_probe_uncertain),
                Modifier.testTag("location_last_known"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val actions: @Composable () -> Unit = {
            Button(
                onClick = viewModel::readLastKnown,
                enabled = state != LocationProbeState.Loading && state != LocationProbeState.AwaitingPermission,
                modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight).testTag("location_read"),
            ) { Text(stringResource(R.string.location_probe_read)) }
            if (result == LocationReading.PermissionDenied) {
                Button(
                    onClick = {
                        viewModel.beginPermissionRequest()?.let { token ->
                            requestPermission { response -> viewModel.onPermissionResult(token, response) }
                        }
                    },
                    modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight).testTag("location_permission"),
                ) { Text(stringResource(R.string.location_probe_permission)) }
            }
        }
        val fontScale = LocalDensity.current.fontScale
        BoxWithConstraints {
            if (maxWidth >= HanGilDimensions.parkedActionMaxWidth * fontScale) {
                Row(horizontalArrangement = Arrangement.spacedBy(HanGilSpacing.md)) { actions() }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.md)) { actions() }
            }
        }
    }
}
