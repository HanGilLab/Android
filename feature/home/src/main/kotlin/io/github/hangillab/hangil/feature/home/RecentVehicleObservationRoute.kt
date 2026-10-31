package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing

/** Created only inside the expanded parked detail; no separate effect or retained DB subscription. */
@Composable
internal fun RecentVehicleObservationRoute(
    sessionId: String,
    viewModel: RecentVehicleObservationViewModel = hiltViewModel(),
) {
    val observations = remember(sessionId, viewModel) { viewModel.observe(sessionId) }
    val state by observations.collectAsStateWithLifecycle(RecentVehicleObservationUiState.Loading(sessionId))
    var selectedId by rememberSaveable(sessionId) { mutableStateOf<Long?>(null) }
    if (state.sessionId != sessionId || !viewModel.canRead()) return

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
        Text(stringResource(R.string.review_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        when (val current = state) {
            is RecentVehicleObservationUiState.Locked -> Unit
            is RecentVehicleObservationUiState.Loading ->
                Text(
                    stringResource(R.string.review_loading),
                    Modifier.testTag("review_loading"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            is RecentVehicleObservationUiState.SessionChanged ->
                Text(
                    stringResource(R.string.review_changed),
                    Modifier.testTag("review_unavailable"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            is RecentVehicleObservationUiState.Empty ->
                Text(
                    stringResource(R.string.review_empty),
                    Modifier.testTag("review_empty"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            is RecentVehicleObservationUiState.Failed -> {
                Text(
                    stringResource(R.string.review_failed),
                    Modifier.testTag("review_failed"),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.error,
                )
                TextButton(
                    onClick = { viewModel.retry() },
                    modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight).testTag("review_retry"),
                ) { Text(stringResource(R.string.retry), style = MaterialTheme.typography.titleMedium) }
            }
            is RecentVehicleObservationUiState.Ready -> {
                val rows = current.observations
                val index = rows.indexOfFirst { it.id == selectedId }.takeIf { it >= 0 } ?: rows.lastIndex
                StoredVehicleObservationContent(
                    rows,
                    index,
                    { if (viewModel.canRead() && index > 0) selectedId = rows[index - 1].id },
                    { if (viewModel.canRead() && index < rows.lastIndex) selectedId = rows[index + 1].id },
                )
            }
        }
    }
}
