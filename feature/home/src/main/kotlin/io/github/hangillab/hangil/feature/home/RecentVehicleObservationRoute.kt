package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.designsystem.ParkedBadge
import io.github.hangillab.hangil.core.designsystem.ParkedButton
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.TurnSignalState

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
                val row = rows[index]
                Column(Modifier.fillMaxWidth().testTag("review_ready"), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
                    Column(Modifier.testTag("review_source")) { ParkedBadge(stringResource(R.string.review_source)) }
                    Text(
                        stringResource(R.string.review_position, index + 1, rows.size),
                        Modifier.testTag("review_position"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(R.string.review_observed_at, displayTime(row.observation.observedAtEpochMillis)),
                        Modifier.testTag("review_observed_at"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(R.string.review_connection, connectionLabel(row.observation.state.connection)),
                        Modifier.testTag("review_connection"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(
                            R.string.vehicle_gear,
                            readingLabel(row.observation.state.gear) {
                                when (it) {
                                    GearPosition.PARK -> "P"
                                    GearPosition.REVERSE -> "R"
                                    GearPosition.NEUTRAL -> "N"
                                    GearPosition.DRIVE -> "D"
                                    GearPosition.OTHER -> stringResource(R.string.gear_other)
                                }
                            },
                        ),
                        Modifier.testTag("review_gear"),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        stringResource(
                            R.string.vehicle_turn_signal,
                            readingLabel(row.observation.state.turnSignal) {
                                stringResource(
                                    when (it) {
                                        TurnSignalState.OFF -> R.string.turn_signal_off
                                        TurnSignalState.LEFT -> R.string.turn_signal_left
                                        TurnSignalState.RIGHT -> R.string.turn_signal_right
                                    },
                                )
                            },
                        ),
                        Modifier.testTag("review_turn"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        stringResource(
                            R.string.vehicle_brake,
                            readingLabel(row.observation.state.brakePressed) {
                                stringResource(if (it) R.string.brake_pressed else R.string.brake_released)
                            },
                        ),
                        Modifier.testTag("review_brake"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    ReviewNavigation(
                        index > 0,
                        index < rows.lastIndex,
                        { if (viewModel.canRead() && index > 0) selectedId = rows[index - 1].id },
                        { if (viewModel.canRead() && index < rows.lastIndex) selectedId = rows[index + 1].id },
                    )
                    Text(
                        stringResource(R.string.review_scope),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun ReviewNavigation(
    hasPrevious: Boolean,
    hasNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val previous: @Composable () -> Unit = {
        ParkedButton(
            onClick = onPrevious,
            enabled = hasPrevious,
            prominent = false,
            modifier = Modifier.fillMaxWidth().heightIn(min = HanGilDimensions.secondaryActionMinHeight).testTag("review_previous"),
        ) { Text(stringResource(R.string.review_previous), style = MaterialTheme.typography.titleMedium) }
    }
    val next: @Composable () -> Unit = {
        ParkedButton(
            onClick = onNext,
            enabled = hasNext,
            prominent = false,
            modifier = Modifier.fillMaxWidth().heightIn(min = HanGilDimensions.secondaryActionMinHeight).testTag("review_next"),
        ) { Text(stringResource(R.string.review_next), style = MaterialTheme.typography.titleMedium) }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 520.dp || LocalDensity.current.fontScale > 1.2f) {
            Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
                previous()
                next()
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
                Column(Modifier.weight(1f)) { previous() }
                Column(Modifier.weight(1f)) { next() }
            }
        }
    }
}
