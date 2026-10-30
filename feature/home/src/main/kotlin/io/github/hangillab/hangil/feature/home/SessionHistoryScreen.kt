package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.designsystem.ParkedBadge
import io.github.hangillab.hangil.core.designsystem.ParkedButton
import io.github.hangillab.hangil.core.designsystem.ParkedDisclosure
import io.github.hangillab.hangil.core.designsystem.ParkedLockedNotice
import io.github.hangillab.hangil.core.designsystem.ParkedPage
import io.github.hangillab.hangil.core.designsystem.ParkedPanel
import io.github.hangillab.hangil.core.domain.CourseReviewStatus

@Composable
fun SessionHistoryRoute(
    onClose: () -> Unit,
    viewModel: SessionHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    key((state as? SessionHistoryUiState.Ready)?.session?.sessionId) {
        ParkedPage {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HanGilSpacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.history_title),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                )
                TextButton(
                    onClick = onClose,
                    modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight).testTag("history_close"),
                ) { Text(stringResource(R.string.history_close), style = MaterialTheme.typography.titleMedium) }
            }
            if (!viewModel.canRead() || state == SessionHistoryUiState.Locked) {
                Box(Modifier.testTag("history_locked")) { ParkedLockedNotice() }
            } else {
                when (val current = state) {
                    SessionHistoryUiState.Locked -> Unit
                    SessionHistoryUiState.Loading -> HistoryMessage(R.string.history_loading, "history_loading")
                    SessionHistoryUiState.Empty -> HistoryMessage(R.string.history_empty, "history_empty")
                    is SessionHistoryUiState.Failed -> {
                        HistoryMessage(R.string.history_failed, "history_failed")
                        ParkedButton(
                            onClick = { viewModel.retry() },
                            prominent = false,
                            modifier = Modifier.testTag("history_retry"),
                        ) { Text(stringResource(R.string.history_retry), style = MaterialTheme.typography.titleLarge) }
                    }
                    is SessionHistoryUiState.SelectionChanged -> {
                        HistoryMessage(R.string.history_selection_changed, "history_selection_changed")
                        ParkedButton(
                            onClick = { viewModel.resetToLatest(current.generation) },
                            prominent = false,
                            modifier = Modifier.testTag("history_reset"),
                        ) { Text(stringResource(R.string.history_reset), style = MaterialTheme.typography.titleLarge) }
                    }
                    is SessionHistoryUiState.Ready ->
                        HistoryRecord(
                            current,
                            onNewer = { viewModel.newer(current.session.sessionId, current.generation) },
                            onOlder = { viewModel.older(current.session.sessionId, current.generation) },
                        )
                }
            }
        }
    }
}

@Composable
private fun HistoryMessage(
    text: Int,
    tag: String,
) {
    ParkedPanel(Modifier.testTag(tag)) {
        Text(stringResource(text), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun HistoryRecord(
    state: SessionHistoryUiState.Ready,
    onNewer: () -> Unit,
    onOlder: () -> Unit,
) {
    val session = state.session
    val finished = checkNotNull(session.endedAtEpochMillis)
    Column(Modifier.testTag("history_ready"), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
        Text(
            stringResource(R.string.history_position, state.index + 1, state.total),
            Modifier.testTag("history_position"),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.history_scope),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
            ParkedButton(
                onClick = onNewer,
                enabled = state.canNewer,
                prominent = false,
                modifier = Modifier.weight(1f).testTag("history_newer"),
            ) { Text(stringResource(R.string.history_newer), style = MaterialTheme.typography.titleLarge) }
            ParkedButton(
                onClick = onOlder,
                enabled = state.canOlder,
                prominent = false,
                modifier = Modifier.weight(1f).testTag("history_older"),
            ) { Text(stringResource(R.string.history_older), style = MaterialTheme.typography.titleLarge) }
        }
        ParkedPanel {
            Text(
                session.courseTitle,
                Modifier.testTag("history_course"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (session.courseReviewStatus == CourseReviewStatus.DEMO) {
                Box(Modifier.testTag("history_demo")) { ParkedBadge(stringResource(R.string.demo_course)) }
            }
            Text(
                stringResource(R.string.history_goal_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(session.goalTitle, Modifier.testTag("history_goal"), style = MaterialTheme.typography.bodyLarge)
            Text(
                stringResource(R.string.history_task_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(session.taskTitle, Modifier.testTag("history_task"), style = MaterialTheme.typography.titleLarge)
        }
        HistoryCourseSummary(state)
        ParkedPanel {
            Text(
                stringResource(R.string.session_started_at, displayTime(session.startedAtEpochMillis)),
                Modifier.testTag("history_started"),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                stringResource(R.string.session_finished_at, displayTime(finished)),
                Modifier.testTag("history_finished"),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
    }
}

@Composable
private fun HistoryCourseSummary(state: SessionHistoryUiState.Ready) {
    val summary = state.summary
    ParkedPanel(Modifier.testTag("history_course_summary")) {
        Text(
            stringResource(R.string.history_summary_title),
            Modifier.testTag("history_summary_heading"),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            stringResource(R.string.history_summary_count, summary.sessionCount),
            Modifier.testTag("history_summary_count"),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        ParkedDisclosure(stringResource(R.string.history_summary_tasks), Modifier.testTag("history_summary_tasks")) {
            Text(
                stringResource(R.string.history_summary_scope),
                Modifier.testTag("history_summary_scope"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            summary.tasks.forEachIndexed { index, task ->
                Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.sm)) {
                    Text(
                        task.taskTitle,
                        Modifier.testTag("history_summary_task_$index"),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        stringResource(R.string.history_summary_count, task.sessionCount),
                        Modifier.testTag("history_summary_task_count_$index"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
