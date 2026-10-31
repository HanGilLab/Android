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
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilBrand
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.designsystem.ParkedBadge
import io.github.hangillab.hangil.core.designsystem.ParkedButton
import io.github.hangillab.hangil.core.designsystem.ParkedDisclosure
import io.github.hangillab.hangil.core.designsystem.ParkedLockedNotice
import io.github.hangillab.hangil.core.designsystem.ParkedPage
import io.github.hangillab.hangil.core.designsystem.ParkedPanel
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.VehicleRecordingState

@Composable
fun HomeRoute(
    onPrepare: () -> Unit,
    onMapPreview: () -> Unit,
    onHistory: () -> Unit,
    onPrepareNext: (String) -> Unit,
    showDiagnostics: Boolean = false,
    recordingState: VehicleRecordingState = VehicleRecordingState.Idle,
    onRetryRecording: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(
        state,
        showDiagnostics,
        viewModel::retryLoading,
        { if (viewModel.canViewMap()) onMapPreview() },
        viewModel::canExpandPreparation,
        viewModel::canViewMap,
        {
            SessionRoute(
                onPrepare = { if (viewModel.canPrepare()) onPrepare() },
                onPrepareNext = onPrepareNext,
                canPrepare = state.preparation is PreparationLoadState.Loaded,
                recordingState = recordingState,
                onRetryRecording = onRetryRecording,
            )
            NavigationScreenRoute()
            ParkedButton(
                onClick = { if (viewModel.canViewHistory()) onHistory() },
                prominent = false,
                modifier = Modifier.fillMaxWidth().heightIn(min = HanGilDimensions.secondaryActionMinHeight).testTag("home_history"),
            ) {
                Text(stringResource(R.string.history_open), style = MaterialTheme.typography.titleLarge)
            }
        },
    )
}

@Composable
internal fun HomeScreen(
    state: HomeUiState,
    showDiagnostics: Boolean,
    onRetry: () -> Unit,
    onMapPreview: () -> Unit,
    canExpandPreparation: (PreparedPractice) -> Boolean,
    canExpandDiagnostics: () -> Boolean,
    sessionContent: @Composable () -> Unit,
) {
    key(state.allowsDetailedInteraction) {
        ParkedPage {
            if (!state.allowsDetailedInteraction) {
                Text(
                    stringResource(R.string.home_title),
                    Modifier.testTag("home_locked_title"),
                    style = MaterialTheme.typography.titleLarge,
                )
                ParkedLockedNotice()
            } else {
                HomeHeader()
                HomeDashboard(first = sessionContent, second = {
                    when (val load = state.preparation) {
                        PreparationLoadState.Loading ->
                            Text(
                                stringResource(R.string.loading_preparation),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        PreparationLoadState.Failed ->
                            ParkedPanel {
                                Text(stringResource(R.string.load_failed), style = MaterialTheme.typography.bodyLarge)
                                TextButton(
                                    onClick = onRetry,
                                    modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight),
                                ) { Text(stringResource(R.string.retry)) }
                            }
                        is PreparationLoadState.Loaded -> {
                            val ready = (load.preparation as? Preparation.Ready)?.practice
                            ParkedPanel {
                                Text(
                                    stringResource(if (ready == null) R.string.first_preparation else R.string.saved_preparation),
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                if (ready == null) {
                                    Text(
                                        stringResource(
                                            if (load.preparation ==
                                                Preparation.Outdated
                                            ) {
                                                R.string.preparation_outdated
                                            } else {
                                                R.string.preparation_intro
                                            },
                                        ),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                } else {
                                    Text(
                                        ready.course.title,
                                        Modifier.testTag("saved_course"),
                                        style = MaterialTheme.typography.headlineSmall,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    ParkedBadge(stringResource(R.string.demo_course))
                                    Text(
                                        stringResource(R.string.today_one),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    Text(ready.task.title, Modifier.testTag("saved_task"), style = MaterialTheme.typography.titleLarge)
                                    key(ready) {
                                        ParkedDisclosure(
                                            stringResource(R.string.preparation_details),
                                            { canExpandPreparation(ready) },
                                            Modifier.testTag("home_preparation_details"),
                                        ) {
                                            Text(
                                                ready.goal.title,
                                                Modifier.testTag("saved_goal"),
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                            Text(ready.course.summary, style = MaterialTheme.typography.bodyLarge)
                                            Text(ready.task.description, style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    ParkedButton(
                        onClick = onMapPreview,
                        prominent = false,
                        modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight).testTag("home_map"),
                    ) {
                        Text(stringResource(R.string.map_preview), style = MaterialTheme.typography.titleLarge)
                    }
                })
                if (showDiagnostics) {
                    ParkedDisclosure(
                        title = stringResource(R.string.vehicle_diagnostics),
                        canExpand = canExpandDiagnostics,
                        modifier = Modifier.testTag("home_diagnostics_details"),
                    ) {
                        Column(Modifier.testTag("home_diagnostics_content")) { VehicleDiagnostics(state) }
                    }
                }
            }
        }
    }
}

@Composable
private fun HomeHeader() {
    val fontScale = LocalDensity.current.fontScale
    Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.sm)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= HanGilDimensions.wideBreakpoint / 2 * fontScale) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HanGilBrand(markSize = HanGilDimensions.parkedBrandMarkSize)
                    ParkedBadge(stringResource(R.string.parked_badge), emphasized = true)
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.sm)) {
                    HanGilBrand(markSize = HanGilDimensions.parkedBrandMarkSize)
                    ParkedBadge(stringResource(R.string.parked_badge), emphasized = true)
                }
            }
        }
    }
}

@Composable
private fun HomeDashboard(
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("home_dashboard")) {
        if (maxWidth >= HanGilDimensions.parkedColumnBreakpoint * fontScale) {
            Row(
                Modifier.fillMaxWidth().testTag("home_dashboard_wide"),
                horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(32.dp)) { first() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(32.dp)) { second() }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().testTag("home_dashboard_stacked"),
                verticalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                first()
                second()
            }
        }
    }
}
