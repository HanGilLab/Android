package io.github.hangillab.hangil.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.PracticeBadge
import io.github.hangillab.hangil.core.designsystem.PracticeLockedNotice
import io.github.hangillab.hangil.core.designsystem.PracticePage
import io.github.hangillab.hangil.core.designsystem.PracticePanel

@Composable
fun MapRoute(
    onClose: () -> Unit,
    viewModel: MapViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    PracticePage {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.map_title), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onClose, modifier = Modifier.heightIn(min = 72.dp).testTag("map_close")) {
                Text(stringResource(R.string.map_close))
            }
        }
        if (!state.allowsDetailedInteraction) {
            PracticeLockedNotice()
        } else {
            PracticeBadge(stringResource(R.string.map_demo_badge))
            Text(stringResource(R.string.map_demo_region), style = MaterialTheme.typography.bodyLarge)
            Box(Modifier.fillMaxWidth().height(420.dp).testTag(if (state.load == MapLoadState.READY) "map_ready" else "map_container")) {
                if (state.load == MapLoadState.FAILED) {
                    PracticePanel(Modifier.testTag("map_error")) {
                        Text(stringResource(R.string.map_failed), style = MaterialTheme.typography.bodyLarge)
                        Button(onClick = viewModel::retry, modifier = Modifier.heightIn(min = 72.dp).testTag("map_retry")) {
                            Text(stringResource(R.string.map_retry))
                        }
                    }
                } else {
                    key(state.attempt) {
                        DemoMap(
                            attempt = state.attempt,
                            contentDescription = stringResource(R.string.map_content_description),
                            onLoading = viewModel::onMapLoading,
                            onRendered = viewModel::onMapRendered,
                            onFailed = viewModel::onMapFailed,
                            modifier = Modifier.fillMaxSize().testTag("map_view"),
                        )
                    }
                    if (state.load == MapLoadState.LOADING) {
                        Surface(
                            Modifier.align(Alignment.TopCenter).padding(16.dp).testTag("map_loading"),
                            shape = MaterialTheme.shapes.medium,
                            color = MaterialTheme.colorScheme.surface,
                        ) {
                            Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                CircularProgressIndicator()
                                Text(stringResource(R.string.map_loading), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.map_sources), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
