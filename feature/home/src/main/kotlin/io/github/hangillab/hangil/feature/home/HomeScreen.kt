package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilBrand
import io.github.hangillab.hangil.core.designsystem.PracticeBadge
import io.github.hangillab.hangil.core.designsystem.PracticeColumns
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
        { if (viewModel.canPrepare()) onPrepare() },
        viewModel::retryLoading,
        { if (viewModel.canViewMap()) onMapPreview() },
        { SessionRoute() },
    )
}

@Composable
internal fun HomeScreen(
    state: HomeUiState,
    showDiagnostics: Boolean,
    onPrepare: () -> Unit,
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
                HanGilBrand()
                PracticeBadge(stringResource(R.string.parked_badge))
                Text(stringResource(R.string.preparation_heading), style = MaterialTheme.typography.displaySmall)
                Text(stringResource(R.string.home_tagline), style = MaterialTheme.typography.headlineSmall)
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
                                modifier = Modifier.heightIn(min = 72.dp),
                            ) { Text(stringResource(R.string.retry)) }
                        }
                    is PreparationLoadState.Loaded -> {
                        val ready = (load.preparation as? Preparation.Ready)?.practice
                        PracticeColumns(first = {
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
                                Button(
                                    onClick = onPrepare,
                                    modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).testTag("home_prepare"),
                                ) {
                                    Text(
                                        stringResource(
                                            if (ready ==
                                                null
                                            ) {
                                                R.string.prepare_start
                                            } else {
                                                R.string.prepare_change
                                            },
                                        ),
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                }
                            }
                        }, second = {
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
                        })
                    }
                }
                sessionContent()
                TextButton(onClick = onMapPreview, modifier = Modifier.heightIn(min = 72.dp).testTag("home_map")) {
                    Text(stringResource(R.string.map_preview), style = MaterialTheme.typography.titleLarge)
                }
                if (showDiagnostics) {
                    TextButton(onClick = { diagnosticsExpanded = !diagnosticsExpanded }, modifier = Modifier.heightIn(min = 64.dp)) {
                        Text(stringResource(if (diagnosticsExpanded) R.string.diagnostics_hide else R.string.diagnostics_show))
                    }
                    if (diagnosticsExpanded) VehicleDiagnostics(state)
                }
            }
        }
    }
}
