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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilBrand
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.designsystem.PracticeBadge
import io.github.hangillab.hangil.core.designsystem.PracticeLockedNotice
import io.github.hangillab.hangil.core.designsystem.PracticePage
import io.github.hangillab.hangil.core.designsystem.PracticePanel
import io.github.hangillab.hangil.core.domain.Preparation

@Composable
fun HomeRoute(
    onPrepare: () -> Unit,
    onMapPreview: () -> Unit,
    showDiagnostics: Boolean = false,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    HomeScreen(
        state,
        showDiagnostics,
        viewModel::retryLoading,
        { if (viewModel.canViewMap()) onMapPreview() },
        {
            SessionRoute(
                onPrepare = { if (viewModel.canPrepare()) onPrepare() },
                canPrepare = state.preparation is PreparationLoadState.Loaded,
            )
        },
    )
}

@Composable
internal fun HomeScreen(
    state: HomeUiState,
    showDiagnostics: Boolean,
    onRetry: () -> Unit,
    onMapPreview: () -> Unit,
    sessionContent: @Composable () -> Unit,
) {
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }
    key(state.allowsDetailedInteraction) {
        PracticePage {
            if (!state.allowsDetailedInteraction) {
                Text(
                    stringResource(R.string.home_title),
                    Modifier.testTag("home_locked_title"),
                    style = MaterialTheme.typography.titleLarge,
                )
                PracticeLockedNotice()
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
                            PracticePanel {
                                Text(stringResource(R.string.load_failed), style = MaterialTheme.typography.bodyLarge)
                                TextButton(
                                    onClick = onRetry,
                                    modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight),
                                ) { Text(stringResource(R.string.retry)) }
                            }
                        is PreparationLoadState.Loaded -> {
                            val ready = (load.preparation as? Preparation.Ready)?.practice
                            Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
                                PracticePanel {
                                    Text(
                                        stringResource(
                                            if (ready ==
                                                null
                                            ) {
                                                R.string.first_preparation
                                            } else {
                                                R.string.saved_preparation
                                            },
                                        ),
                                        style = MaterialTheme.typography.headlineMedium,
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
                                        Text(stringResource(R.string.preparation_flow), style = MaterialTheme.typography.titleLarge)
                                    } else {
                                        Text(ready.goal.title, style = MaterialTheme.typography.titleLarge)
                                        Text(
                                            ready.course.title,
                                            Modifier.testTag("saved_course"),
                                            style = MaterialTheme.typography.headlineSmall,
                                        )
                                        PracticeBadge(stringResource(R.string.demo_course))
                                        Text(ready.course.summary, style = MaterialTheme.typography.bodyLarge)
                                    }
                                }
                                PracticePanel {
                                    Text(stringResource(R.string.today_one), style = MaterialTheme.typography.headlineMedium)
                                    Text(
                                        ready?.task?.title ?: stringResource(R.string.one_task_intro),
                                        Modifier.testTag("saved_task"),
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                    Text(
                                        ready?.task?.description ?: stringResource(R.string.one_task_description),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                }
                                Text(stringResource(R.string.preparation_scope), style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                    TextButton(
                        onClick = onMapPreview,
                        modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight).testTag("home_map"),
                    ) {
                        Text(stringResource(R.string.map_preview), style = MaterialTheme.typography.titleLarge)
                    }
                })
                if (showDiagnostics) {
                    TextButton(
                        onClick = { diagnosticsExpanded = !diagnosticsExpanded },
                        modifier = Modifier.heightIn(min = HanGilDimensions.compactActionMinHeight),
                    ) {
                        Text(stringResource(if (diagnosticsExpanded) R.string.diagnostics_hide else R.string.diagnostics_show))
                    }
                    if (diagnosticsExpanded) VehicleDiagnostics(state)
                }
            }
        }
    }
}

@Composable
private fun HomeHeader() {
    val fontScale = LocalDensity.current.fontScale
    Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.md)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= HanGilDimensions.wideBreakpoint / 2 * fontScale) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HanGilBrand()
                    PracticeBadge(stringResource(R.string.parked_badge))
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.sm)) {
                    HanGilBrand()
                    PracticeBadge(stringResource(R.string.parked_badge))
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.xs)) {
            Text(stringResource(R.string.preparation_heading), style = MaterialTheme.typography.headlineLarge)
            Text(
                stringResource(R.string.home_tagline),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
        if (maxWidth >= HanGilDimensions.wideBreakpoint * fontScale) {
            Row(
                Modifier.fillMaxWidth().testTag("home_dashboard_wide"),
                horizontalArrangement = Arrangement.spacedBy(HanGilSpacing.xl),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) { first() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) { second() }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().testTag("home_dashboard_stacked"),
                verticalArrangement = Arrangement.spacedBy(HanGilSpacing.xl),
            ) {
                first()
                second()
            }
        }
    }
}
