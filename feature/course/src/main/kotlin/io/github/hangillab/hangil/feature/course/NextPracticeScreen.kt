package io.github.hangillab.hangil.feature.course

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.designsystem.ParkedBadge
import io.github.hangillab.hangil.core.designsystem.ParkedButton
import io.github.hangillab.hangil.core.designsystem.ParkedChoice
import io.github.hangillab.hangil.core.designsystem.ParkedDisclosure
import io.github.hangillab.hangil.core.designsystem.ParkedLockedNotice
import io.github.hangillab.hangil.core.designsystem.ParkedPage
import io.github.hangillab.hangil.core.designsystem.ParkedPanel
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.PracticeSession
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicBoolean

@Composable
fun NextPracticeRoute(
    onClose: () -> Unit,
    onPrepareNormally: () -> Unit,
    viewModel: NextPracticeViewModel = hiltViewModel(),
    expectedHistorySource: PracticeSession? = null,
    historyEntryRequired: Boolean = false,
    isHistoryEntryCurrent: () -> Boolean = { false },
    reviewRoad: (@Composable (PracticeSession, () -> Boolean) -> Unit)? = null,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val historyReceipt =
        remember(viewModel, lifecycle, expectedHistorySource, isHistoryEntryCurrent) {
            mutableStateOf<AtomicBoolean?>(null)
        }
    val state by if (historyEntryRequired) {
        val observation =
            remember(viewModel, lifecycle, expectedHistorySource, isHistoryEntryCurrent) {
                flow {
                    val resumed = AtomicBoolean(true)
                    historyReceipt.value = resumed
                    try {
                        emitAll(
                            viewModel.observeHistory(
                                expectedSource = expectedHistorySource,
                                isEntryCurrent = isHistoryEntryCurrent,
                                canKeepOpen = { resumed.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) },
                            ),
                        )
                    } finally {
                        resumed.set(false)
                        if (historyReceipt.value === resumed) historyReceipt.value = null
                    }
                }
            }
        observation.collectAsStateWithLifecycle(
            initialValue = NextPracticeUiState.Loading,
            minActiveState = Lifecycle.State.RESUMED,
        )
    } else {
        viewModel.uiState.collectAsStateWithLifecycle()
    }
    val currentOnClose by rememberUpdatedState(onClose)
    val currentOnPrepareNormally by rememberUpdatedState(onPrepareNormally)
    var closing by remember(viewModel) { mutableStateOf(false) }
    val closeReceipt = historyReceipt.value
    val close: () -> Unit = {
        val historyCanClose =
            !historyEntryRequired ||
                (
                    closeReceipt?.get() == true && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        runCatching(isHistoryEntryCurrent).getOrDefault(false)
                )
        if (!closing && historyCanClose) {
            closing = true
            viewModel.leave()
            currentOnClose()
        }
    }
    val currentClose by rememberUpdatedState(close)
    val normal: (Long) -> Unit = { generation ->
        if (!closing && viewModel.leaveForNormalPreparation(generation)) {
            closing = true
            currentOnPrepareNormally()
        }
    }
    LaunchedEffect(viewModel, lifecycle, historyEntryRequired) {
        val minimumState = if (historyEntryRequired) Lifecycle.State.RESUMED else Lifecycle.State.STARTED
        lifecycle.repeatOnLifecycle(minimumState) {
            viewModel.saved.collect { token -> if (viewModel.consumeSaved(token)) currentClose() }
        }
    }
    BackHandler(onBack = close)
    key((state as? NextPracticeUiState.Ready)?.seed?.source?.sessionId) {
        ParkedPage {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(HanGilSpacing.lg),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stringResource(R.string.next_practice_title),
                    Modifier.weight(1f).testTag("next_practice_title"),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                TextButton(
                    onClick = close,
                    modifier = Modifier.heightIn(min = HanGilDimensions.secondaryActionMinHeight).testTag("next_practice_close"),
                ) { Text(stringResource(R.string.next_practice_close), style = MaterialTheme.typography.titleMedium) }
            }
            if (!viewModel.canInteract() || state == NextPracticeUiState.Locked) {
                Box(Modifier.testTag("next_practice_locked")) { ParkedLockedNotice() }
            } else {
                when (val current = state) {
                    NextPracticeUiState.Locked -> Unit
                    NextPracticeUiState.Loading -> NextPracticeMessage(R.string.next_practice_loading, "next_practice_loading")
                    is NextPracticeUiState.SourceChanged -> {
                        NextPracticeMessage(R.string.next_practice_changed, "next_practice_changed")
                        GeneralPreparationButton { normal(current.generation) }
                    }
                    is NextPracticeUiState.Unavailable -> {
                        NextPracticeMessage(R.string.next_practice_unavailable, "next_practice_unavailable")
                        GeneralPreparationButton { normal(current.generation) }
                    }
                    is NextPracticeUiState.Failed -> {
                        NextPracticeMessage(R.string.next_practice_failed, "next_practice_failed")
                        ParkedButton(
                            onClick = { viewModel.retry() },
                            prominent = false,
                            modifier = Modifier.testTag("next_practice_retry"),
                        ) { Text(stringResource(R.string.next_practice_retry), style = MaterialTheme.typography.titleLarge) }
                        GeneralPreparationButton { normal(current.generation) }
                    }
                    is NextPracticeUiState.Ready -> NextPracticeChoices(current, viewModel, historyEntryRequired, reviewRoad)
                }
            }
        }
    }
}

@Composable
private fun NextPracticeChoices(
    state: NextPracticeUiState.Ready,
    viewModel: NextPracticeViewModel,
    historyEntry: Boolean,
    reviewRoad: (@Composable (PracticeSession, () -> Boolean) -> Unit)?,
) {
    val source = state.seed.source
    Column(Modifier.testTag("next_practice_ready"), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
        Text(stringResource(R.string.next_practice_scope), style = MaterialTheme.typography.bodyLarge)
        ParkedPanel(Modifier.testTag("next_practice_source")) {
            Text(
                stringResource(if (historyEntry) R.string.next_practice_history_source_label else R.string.next_practice_source_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                source.courseTitle,
                Modifier.testTag("next_practice_course"),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (source.courseReviewStatus == CourseReviewStatus.DEMO) {
                Box(Modifier.testTag("next_practice_demo")) { ParkedBadge(stringResource(R.string.demo_badge)) }
            }
            ParkedDisclosure(
                title = stringResource(R.string.next_practice_source_details),
                canExpand = { viewModel.canRevealSource(source.sessionId, state.generation) },
                modifier = Modifier.testTag("next_practice_source_details"),
            ) {
                Text(
                    stringResource(R.string.next_practice_source_goal, source.goalTitle),
                    Modifier.testTag("next_practice_goal"),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.next_practice_previous_task, source.taskTitle),
                    Modifier.testTag("next_practice_previous_task"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        if (reviewRoad != null) {
            key(source, state.generation) {
                val lifecycle = LocalLifecycleOwner.current.lifecycle
                var referenceOwner by remember(lifecycle) {
                    mutableStateOf(AtomicBoolean(lifecycle.currentState == Lifecycle.State.RESUMED))
                }
                DisposableEffect(lifecycle) {
                    val observer =
                        LifecycleEventObserver { _, event ->
                            when (event) {
                                Lifecycle.Event.ON_RESUME -> {
                                    referenceOwner.set(false)
                                    referenceOwner = AtomicBoolean(true)
                                }
                                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> {
                                    referenceOwner.set(false)
                                    referenceOwner = AtomicBoolean(false)
                                }
                                else -> Unit
                            }
                        }
                    lifecycle.addObserver(observer)
                    onDispose {
                        referenceOwner.set(false)
                        lifecycle.removeObserver(observer)
                    }
                }
                // Each closure retains the displayed source, task generation and resumed owner.
                val capturedOwner = referenceOwner
                key(capturedOwner) {
                    val canKeepOpen =
                        remember(source, state.generation, viewModel, lifecycle, capturedOwner) {
                            {
                                capturedOwner.get() && lifecycle.currentState == Lifecycle.State.RESUMED &&
                                    viewModel.canRevealSource(source.sessionId, state.generation)
                            }
                        }
                    ParkedPanel {
                        ParkedDisclosure(
                            title = stringResource(R.string.next_practice_review_road),
                            canExpand = canKeepOpen,
                            modifier = Modifier.testTag("next_practice_review_road"),
                        ) {
                            reviewRoad(source, canKeepOpen)
                        }
                    }
                }
            }
        }
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
            state.seed.practice.course.tasks.forEach { task ->
                val count =
                    state.summary.tasks
                        .singleOrNull { it.taskId == task.id && it.taskTitle == task.title }
                        ?.sessionCount ?: 0
                ParkedChoice(
                    task.title,
                    stringResource(R.string.next_practice_task_history, task.description, count),
                    selected = state.taskId == task.id,
                    onClick = { viewModel.selectTask(task.id, source.sessionId, state.generation) },
                    modifier = Modifier.fillMaxWidth().testTag("next_task_${task.id}"),
                    enabled = !state.saving,
                )
            }
        }
        if (state.taskId == null) {
            Text(
                stringResource(R.string.next_practice_choose_first),
                Modifier.testTag("next_practice_choose_first"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.saveFailed) {
            Text(
                stringResource(R.string.next_practice_save_failed),
                Modifier.testTag("next_practice_save_failed"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
        ParkedButton(
            onClick = { viewModel.save(source.sessionId, state.generation) },
            enabled = state.canSave,
            modifier = Modifier.fillMaxWidth().testTag("next_practice_save"),
        ) {
            Text(
                stringResource(if (state.saving) R.string.next_practice_saving else R.string.next_practice_save),
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

@Composable
private fun NextPracticeMessage(
    resource: Int,
    tag: String,
) {
    ParkedPanel(Modifier.testTag(tag)) { Text(stringResource(resource), style = MaterialTheme.typography.bodyLarge) }
}

@Composable
private fun GeneralPreparationButton(onClick: () -> Unit) {
    ParkedButton(onClick = onClick, prominent = false, modifier = Modifier.testTag("next_practice_normal")) {
        Text(stringResource(R.string.next_practice_normal), style = MaterialTheme.typography.titleLarge)
    }
}
