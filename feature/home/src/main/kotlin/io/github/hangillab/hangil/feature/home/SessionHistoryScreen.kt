package io.github.hangillab.hangil.feature.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
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
import io.github.hangillab.hangil.core.domain.SessionDeletionTarget
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

@Composable
fun SessionHistoryRoute(
    onClose: () -> Unit,
    viewModel: SessionHistoryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val deletion by viewModel.deletionState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var startedOwner by remember(viewModel, lifecycle) { mutableStateOf(AtomicBoolean(false) to AtomicLong(0)) }
    DisposableEffect(viewModel, lifecycle) {
        fun stopOwner() {
            val current = startedOwner
            current.first.set(false)
            val nonce = current.second.getAndSet(0)
            if (nonce > 0) viewModel.dismissDeletion(nonce)
        }

        fun startOwner() {
            stopOwner()
            startedOwner = AtomicBoolean(true) to AtomicLong(0)
        }
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> startOwner()
                    Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> stopOwner()
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !startedOwner.first.get()) startOwner()
        onDispose {
            stopOwner()
            lifecycle.removeObserver(observer)
        }
    }
    val clickOwner = startedOwner
    val close: () -> Unit = {
        clickOwner.first.set(false)
        val nonce = clickOwner.second.getAndSet(0)
        if (nonce > 0) viewModel.dismissDeletion(nonce)
        onClose()
    }
    val nonce = deletion.nonce
    val locallyOwned = nonce != null && clickOwner.second.get() == nonce && clickOwner.first.get()
    val canPresent = locallyOwned && viewModel.canPresentDeletion(checkNotNull(nonce))
    val feedback = deletion is SessionDeletionUiState.Failed || deletion is SessionDeletionUiState.Deleted
    BackHandler(onBack = close)
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
                    onClick = close,
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
                            canRevealSummary = { viewModel.canRevealSummary(current.session.sessionId, current.generation) },
                            observations = {
                                key(current.session, current.generation, clickOwner.first) {
                                    val canKeepOpen =
                                        remember(clickOwner.first, lifecycle) {
                                            {
                                                clickOwner.first.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                                            }
                                        }
                                    ParkedPanel {
                                        ParkedDisclosure(
                                            stringResource(R.string.review_title),
                                            {
                                                canKeepOpen() &&
                                                    viewModel.canRevealStoredObservations(current.session.sessionId, current.generation)
                                            },
                                            Modifier.testTag("history_observations"),
                                        ) {
                                            HistoryStoredObservationRoute(
                                                current.session,
                                                current.generation,
                                                canKeepOpen,
                                                viewModel,
                                            )
                                        }
                                    }
                                }
                            },
                            memo = {
                                key(current.session, current.generation, clickOwner.first) {
                                    val canKeepOpen =
                                        remember(clickOwner.first, lifecycle) {
                                            { clickOwner.first.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
                                        }
                                    ParkedPanel {
                                        ParkedDisclosure(
                                            stringResource(R.string.memo_heading),
                                            {
                                                canKeepOpen() &&
                                                    viewModel.canRevealStoredMemo(
                                                        current.session.sessionId,
                                                        current.generation,
                                                    )
                                            },
                                            Modifier.testTag("history_memo"),
                                        ) {
                                            HistoryStoredMemoRoute(current.session, current.generation, canKeepOpen, viewModel)
                                        }
                                    }
                                }
                            },
                            onDelete = {
                                if (clickOwner.first.get() &&
                                    viewModel.requestDeletion(current.session.sessionId, current.generation) { clickOwner.first.get() }
                                ) {
                                    viewModel.deletionState.value.nonce
                                        ?.let(clickOwner.second::set)
                                }
                            },
                        )
                }
            }
            if (canPresent && feedback) {
                key(nonce) {
                    HistoryDeletionContent(
                        deletion,
                        confirm = { viewModel.confirmDeletion(checkNotNull(nonce)) },
                        dismiss = { viewModel.dismissDeletion(checkNotNull(nonce)) },
                    )
                }
            }
        }
    }
    if (canPresent && !feedback) {
        key(nonce) {
            HistoryDeletionContent(
                deletion,
                confirm = { viewModel.confirmDeletion(checkNotNull(nonce)) },
                dismiss = { viewModel.dismissDeletion(checkNotNull(nonce)) },
            )
        }
    }
    SideEffect {
        // Rejected intent must not reopen when P returns or another Activity starts.
        if (locallyOwned && !canPresent) viewModel.dismissDeletion(checkNotNull(nonce))
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
    canRevealSummary: () -> Boolean,
    observations: @Composable () -> Unit,
    memo: @Composable () -> Unit,
    onDelete: () -> Unit,
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
        observations()
        memo()
        HistoryCourseSummary(state, canRevealSummary)
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
        ParkedButton(
            onClick = onDelete,
            prominent = false,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                    .testTag("history_delete")
                    .frozenDeletionClick(onDelete),
        ) { Text(stringResource(R.string.history_delete), style = MaterialTheme.typography.titleLarge) }
    }
}

@Composable
private fun HistoryCourseSummary(
    state: SessionHistoryUiState.Ready,
    canRevealSummary: () -> Boolean,
) {
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
        ParkedDisclosure(
            title = stringResource(R.string.history_summary_tasks),
            canExpand = canRevealSummary,
            modifier = Modifier.testTag("history_summary_tasks"),
        ) {
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

@Composable
private fun HistoryDeletionContent(
    state: SessionDeletionUiState,
    confirm: () -> Unit,
    dismiss: () -> Unit,
) {
    when (state) {
        SessionDeletionUiState.Idle -> Unit
        is SessionDeletionUiState.Failed ->
            ParkedPanel(Modifier.testTag("history_delete_error")) {
                Text(stringResource(deletionFailureText(state.reason)), style = MaterialTheme.typography.bodyLarge)
                TextButton(
                    onClick = dismiss,
                    modifier =
                        Modifier
                            .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                            .testTag("history_delete_cancel")
                            .frozenDeletionClick(dismiss),
                ) { Text(stringResource(R.string.history_delete_done), style = MaterialTheme.typography.titleLarge) }
            }
        is SessionDeletionUiState.Deleted ->
            ParkedPanel(Modifier.testTag("history_delete_success")) {
                Text(stringResource(R.string.history_delete_success), style = MaterialTheme.typography.bodyLarge)
                TextButton(
                    onClick = dismiss,
                    modifier =
                        Modifier
                            .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                            .testTag("history_delete_done")
                            .frozenDeletionClick(dismiss),
                ) { Text(stringResource(R.string.history_delete_done), style = MaterialTheme.typography.titleLarge) }
            }
        else -> {
            val preparing = state is SessionDeletionUiState.Preparing
            val deleting = state is SessionDeletionUiState.Deleting
            val target =
                when (state) {
                    is SessionDeletionUiState.Confirming -> state.target
                    is SessionDeletionUiState.Deleting -> state.target
                    else -> null
                }
            val session = (state as? SessionDeletionUiState.Preparing)?.session ?: checkNotNull(target).session
            val maximumTextHeight = LocalConfiguration.current.screenHeightDp.dp * 0.45f
            AlertDialog(
                onDismissRequest = dismiss,
                modifier = Modifier.testTag("history_delete_dialog"),
                title = { Text(stringResource(R.string.history_delete_title), style = MaterialTheme.typography.titleLarge) },
                text = {
                    Column(
                        Modifier.heightIn(max = maximumTextHeight).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(HanGilSpacing.md),
                    ) {
                        Text(session.courseTitle, Modifier.testTag("history_delete_course"), style = MaterialTheme.typography.titleLarge)
                        Text(session.taskTitle, Modifier.testTag("history_delete_task"), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.session_finished_at, displayTime(checkNotNull(session.endedAtEpochMillis))),
                            Modifier.testTag("history_delete_finished"),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            stringResource(R.string.history_delete_warning),
                            Modifier.testTag("history_delete_warning"),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (target != null) HistoryDeletionTarget(target)
                        if (preparing || deleting) {
                            Row(
                                Modifier.testTag("history_delete_loading"),
                                horizontalArrangement = Arrangement.spacedBy(HanGilSpacing.md),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator()
                                Text(
                                    stringResource(if (preparing) R.string.history_delete_preparing else R.string.history_deleting),
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                            }
                        }
                    }
                },
                confirmButton = {
                    if (state is SessionDeletionUiState.Confirming) {
                        TextButton(
                            onClick = confirm,
                            modifier =
                                Modifier
                                    .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                                    .testTag("history_delete_confirm")
                                    .frozenDeletionClick(confirm),
                        ) { Text(stringResource(R.string.history_delete_confirm), style = MaterialTheme.typography.titleLarge) }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = dismiss,
                        enabled = !deleting,
                        modifier =
                            Modifier
                                .heightIn(min = HanGilDimensions.secondaryActionMinHeight)
                                .testTag("history_delete_cancel")
                                .frozenDeletionClick(dismiss, !deleting),
                    ) { Text(stringResource(R.string.history_delete_cancel), style = MaterialTheme.typography.titleLarge) }
                },
            )
        }
    }
}

@Composable
private fun HistoryDeletionTarget(target: SessionDeletionTarget) {
    Text(
        stringResource(R.string.history_delete_observation_count, target.observationCount),
        Modifier.testTag("history_delete_observation_count"),
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        stringResource(if (target.memoVersion == null) R.string.history_delete_memo_absent else R.string.history_delete_memo_present),
        Modifier.testTag("history_delete_memo_status"),
        style = MaterialTheme.typography.bodyLarge,
    )
}

private fun Modifier.frozenDeletionClick(
    action: () -> Unit,
    enabled: Boolean = true,
): Modifier =
    if (!enabled) {
        this
    } else {
        semantics {
            onClick {
                action()
                true
            }
        }
    }

private fun deletionFailureText(reason: SessionDeletionUiState.FailureReason): Int =
    when (reason) {
        SessionDeletionUiState.FailureReason.MISSING -> R.string.history_delete_missing
        SessionDeletionUiState.FailureReason.ACTIVE -> R.string.history_delete_active
        SessionDeletionUiState.FailureReason.CHANGED -> R.string.history_delete_changed
        SessionDeletionUiState.FailureReason.BLOCKED -> R.string.history_delete_blocked
        SessionDeletionUiState.FailureReason.PREPARE_FAILED -> R.string.history_delete_prepare_failed
        SessionDeletionUiState.FailureReason.DELETE_FAILED -> R.string.history_delete_failed
    }
