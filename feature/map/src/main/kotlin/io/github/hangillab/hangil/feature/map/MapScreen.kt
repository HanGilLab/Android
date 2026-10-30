package io.github.hangillab.hangil.feature.map

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.designsystem.PracticeBadge
import io.github.hangillab.hangil.core.designsystem.PracticeLockedNotice
import io.github.hangillab.hangil.core.designsystem.PracticePage
import io.github.hangillab.hangil.core.designsystem.PracticePanel

@Composable
fun MapRoute(
    onClose: () -> Unit,
    showDiagnostics: Boolean = false,
    onRequestLocationPermission: ((LocationPermissionResult) -> Unit) -> Unit = { it(LocationPermissionResult.FAILED) },
    viewModel: MapViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ready = state.preparation as? MapPreparationState.Ready
    PracticePage {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (state.allowsDetailedInteraction && ready != null) ready.prepared.course.title else stringResource(R.string.map_title),
                Modifier.testTag(if (state.allowsDetailedInteraction && ready != null) "map_course_title" else "map_title"),
                style = MaterialTheme.typography.titleLarge,
            )
            TextButton(
                onClick = onClose,
                modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight).testTag("map_close"),
            ) { Text(stringResource(R.string.map_close), style = MaterialTheme.typography.titleMedium) }
        }
        if (!state.allowsDetailedInteraction) {
            Box(Modifier.testTag("map_locked")) { PracticeLockedNotice() }
        } else {
            when (val preparation = state.preparation) {
                is MapPreparationState.Ready -> PreparedMap(state, preparation, viewModel)
                else -> MapPreparationMessage(preparation, viewModel::retryPreparation, onClose)
            }
            if (showDiagnostics) LocationProbeRoute(onRequestLocationPermission)
        }
    }
}

@Composable
private fun PreparedMap(
    state: MapUiState,
    preparation: MapPreparationState.Ready,
    viewModel: MapViewModel,
) {
    val preview = preparation.preview
    val dark = isSystemInDarkTheme()
    val scale = LocalDensity.current.fontScale
    Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.md)) {
        Text(preview.title, Modifier.testTag("map_preview_title"), style = MaterialTheme.typography.bodyLarge)
        Column(Modifier.testTag("map_demo_badge")) { PracticeBadge(stringResource(R.string.map_demo_badge)) }
        Text(
            stringResource(R.string.map_demo_region),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(HanGilDimensions.mapPreviewHeight)
            .testTag(if (state.load == MapLoadState.READY) "map_ready" else "map_container"),
    ) {
        if (state.load == MapLoadState.FAILED) {
            PracticePanel(Modifier.testTag("map_error")) {
                Text(stringResource(R.string.map_failed), style = MaterialTheme.typography.bodyLarge)
                Button(
                    onClick = viewModel::retry,
                    modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight).testTag("map_retry"),
                ) { Text(stringResource(R.string.map_retry), style = MaterialTheme.typography.titleMedium) }
            }
        } else {
            key(state.attempt, dark, scale) {
                DemoMap(
                    attempt = state.attempt,
                    preview = preview,
                    dark = dark,
                    fontScale = scale,
                    pointFill = MaterialTheme.colorScheme.primary.toArgb(),
                    pointText = MaterialTheme.colorScheme.onPrimary.toArgb(),
                    pointBorder = MaterialTheme.colorScheme.surface.toArgb(),
                    contentDescription = stringResource(R.string.map_content_description, preparation.prepared.course.title),
                    onLoading = viewModel::onMapLoading,
                    canInteract = viewModel::canInteractWithMap,
                    inputChanges = viewModel.mapInputChanges,
                    onRendered = viewModel::onMapRendered,
                    onFailed = viewModel::onMapFailed,
                    modifier = Modifier.fillMaxSize().testTag("map_view"),
                )
            }
            if (state.load == MapLoadState.LOADING) {
                Surface(
                    Modifier.align(Alignment.TopCenter).padding(HanGilSpacing.md).testTag("map_loading"),
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        Modifier.padding(HanGilSpacing.md),
                        horizontalArrangement = Arrangement.spacedBy(HanGilSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(HanGilDimensions.loadingIndicatorSize))
                        Text(stringResource(R.string.map_loading), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
    }
    PracticePanel {
        preview.points.forEachIndexed { index, point ->
            Text("${index + 1}. ${point.title}", Modifier.testTag("map_point_$index"), style = MaterialTheme.typography.bodyLarge)
        }
    }
    Text(stringResource(R.string.map_sources), style = MaterialTheme.typography.bodyMedium)
}

@Composable
private fun MapPreparationMessage(
    preparation: MapPreparationState,
    onRetry: () -> Boolean,
    onClose: () -> Unit,
) {
    val (resource, tag) =
        when (preparation) {
            MapPreparationState.Loading, MapPreparationState.Locked -> R.string.map_preparation_loading to "map_preparation_loading"
            MapPreparationState.Empty -> R.string.map_preparation_empty to "map_preparation_empty"
            MapPreparationState.Outdated -> R.string.map_preparation_outdated to "map_preparation_outdated"
            is MapPreparationState.Unavailable -> R.string.map_preparation_unavailable to "map_preparation_unavailable"
            is MapPreparationState.Failed -> R.string.map_preparation_failed to "map_preparation_failed"
            is MapPreparationState.Ready -> return
        }
    PracticePanel(Modifier.testTag(tag)) {
        Text(stringResource(resource), style = MaterialTheme.typography.bodyLarge)
        if (preparation is MapPreparationState.Failed) {
            Button(
                onClick = { onRetry() },
                modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight).testTag("map_preparation_retry"),
            ) { Text(stringResource(R.string.map_preparation_retry), style = MaterialTheme.typography.titleMedium) }
        } else if (preparation != MapPreparationState.Loading && preparation != MapPreparationState.Locked) {
            Button(onClick = onClose, modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight)) {
                Text(stringResource(R.string.map_return_home), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
