package io.github.hangillab.hangil.feature.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.designsystem.ParkedButton
import io.github.hangillab.hangil.core.designsystem.ParkedChoice
import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.PracticeSession
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.atomic.AtomicBoolean
import io.github.hangillab.hangil.feature.home.SessionReviewRoadUiState as RoadState

/** Only the expanded exact history host creates this reader; a paused receipt never reopens itself. */
@Composable
fun SessionReviewRoadRoute(
    expectedSource: PracticeSession,
    canKeepOpen: () -> Boolean,
    roadMap: (@Composable (PracticeSession, List<CourseRoadSnapshot>, String?, () -> Boolean, Flow<Unit>) -> Unit)? = null,
    viewModel: SessionReviewRoadViewModel = hiltViewModel(),
    readOnly: Boolean = false,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val receipt =
        remember(expectedSource, canKeepOpen, lifecycle, readOnly) {
            AtomicBoolean(lifecycle.currentState == Lifecycle.State.RESUMED)
        }
    val allowed =
        remember(expectedSource, canKeepOpen, lifecycle, receipt) {
            { receipt.get() && lifecycle.currentState == Lifecycle.State.RESUMED && canKeepOpen() }
        }
    DisposableEffect(lifecycle, receipt) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP || event == Lifecycle.Event.ON_DESTROY) {
                    receipt.set(false)
                }
            }
        lifecycle.addObserver(observer)
        onDispose {
            receipt.set(false)
            lifecycle.removeObserver(observer)
        }
    }
    val states = remember(expectedSource, allowed, viewModel) { viewModel.observe(expectedSource, allowed) }
    val state by states.collectAsStateWithLifecycle(
        RoadState.Loading(expectedSource, RoadState.Token(0, 0, 0)),
        minActiveState = Lifecycle.State.RESUMED,
    )
    val displayed = state
    if (displayed.source != expectedSource) return
    Column(
        Modifier.fillMaxWidth().testTag("review_road_content"),
        verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg),
    ) {
        if (!allowed() || (displayed.token.readerId != 0L && !viewModel.canRead(displayed.token))) {
            Text(
                stringResource(R.string.review_road_reopen),
                Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
            )
            return@Column
        }
        Text(
            stringResource(if (readOnly) R.string.review_road_reference_scope else R.string.review_road_scope),
            Modifier.fillMaxWidth().testTag(if (readOnly) "review_road_reference_scope" else "review_road_scope"),
            style = MaterialTheme.typography.bodyLarge,
        )
        when (displayed) {
            is RoadState.Loading ->
                Text(
                    stringResource(R.string.review_road_loading),
                    Modifier.fillMaxWidth().testTag("review_road_loading"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            is RoadState.Locked, is RoadState.Unavailable -> Unit
            is RoadState.Failed -> {
                Text(
                    stringResource(if (displayed.writeUnconfirmed) R.string.review_road_unconfirmed else R.string.review_road_failed),
                    Modifier.fillMaxWidth().testTag("review_road_failed"),
                    style = MaterialTheme.typography.bodyLarge,
                )
                displayed.retainedMemo?.let { draft ->
                    Text(
                        stringResource(R.string.review_road_memo_unsaved),
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(draft, Modifier.fillMaxWidth().testTag("review_road_memo_unsaved"), style = MaterialTheme.typography.bodyLarge)
                }
                val retry = {
                    viewModel.retry(displayed.token)
                    Unit
                }
                ParkedButton(
                    onClick = retry,
                    modifier = Modifier.fillMaxWidth().testTag("review_road_retry").frozenObservationClick(retry),
                ) { Text(stringResource(R.string.review_road_retry), style = MaterialTheme.typography.titleMedium) }
            }
            is RoadState.Ready -> {
                if (displayed.stored == null) {
                    Text(
                        stringResource(if (readOnly) R.string.review_road_reference_empty else R.string.review_road_empty),
                        Modifier.fillMaxWidth().testTag("review_road_empty"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    Text(
                        stringResource(if (readOnly) R.string.review_road_reference_source else R.string.review_road_source),
                        Modifier.fillMaxWidth().testTag("review_road_source"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        displayed.stored.road.title,
                        Modifier.fillMaxWidth().testTag("review_road_stored_title"),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                displayed.stored?.let { saved ->
                    Text(
                        stringResource(R.string.review_road_memo_title),
                        Modifier.fillMaxWidth(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        if (saved.memoText.isEmpty()) stringResource(R.string.review_road_memo_empty) else saved.memoText,
                        Modifier
                            .fillMaxWidth()
                            .testTag(if (saved.memoText.isEmpty()) "review_road_memo_empty" else "review_road_memo_text"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                if (readOnly) {
                    val saved = displayed.stored
                    if (saved != null && roadMap != null) {
                        key(displayed.token) {
                            roadMap(
                                expectedSource,
                                listOf(saved.road),
                                saved.road.id,
                                { allowed() && viewModel.canRead(displayed.token) },
                                viewModel.inputChanges,
                            )
                        }
                    }
                } else {
                    val edit = displayed.edit
                    if (edit != null) {
                        key(expectedSource, edit.expectedCurrent.road, edit.nonce) {
                            HistorySessionMemoEditor(
                                source = expectedSource,
                                draft = edit.text,
                                saving = displayed.busy,
                                onEdit = { viewModel.editMemo(displayed.token, edit.nonce, it) },
                                onSave = { viewModel.saveMemo(displayed.token, edit.nonce) },
                                onCancel = { viewModel.cancelMemo(displayed.token, edit.nonce) },
                                context = stringResource(R.string.review_road_memo_context, edit.expectedCurrent.road.title),
                                tagPrefix = "review_road_memo",
                                allowEmpty = true,
                            )
                        }
                    } else {
                        val retained = displayed.retainedMemo
                        if (retained != null) {
                            Text(
                                stringResource(R.string.review_road_memo_retained, retained.expectedCurrent.road.title),
                                Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            Text(
                                retained.text,
                                Modifier.fillMaxWidth().testTag("review_road_memo_unsaved"),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (displayed.stored?.road == retained.expectedCurrent.road) {
                                val resume = {
                                    viewModel.resumeMemo(displayed.token)
                                    Unit
                                }
                                ParkedButton(
                                    onClick = resume,
                                    enabled = displayed.canWrite,
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .testTag("review_road_memo_resume")
                                            .frozenObservationClick(resume, displayed.canWrite),
                                ) {
                                    Text(
                                        stringResource(R.string.review_road_memo_resume),
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                            }
                            val discard = {
                                viewModel.discardMemoDraft(displayed.token)
                                Unit
                            }
                            ParkedButton(
                                onClick = discard,
                                enabled = displayed.canWrite,
                                prominent = false,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .testTag("review_road_memo_discard_draft")
                                        .frozenObservationClick(discard, displayed.canWrite),
                            ) {
                                Text(stringResource(R.string.review_road_memo_discard_draft), style = MaterialTheme.typography.titleMedium)
                            }
                        }
                        val canChange = displayed.canWrite && displayed.confirmation == null && retained == null
                        displayed.feedback?.let {
                            val message =
                                when (it) {
                                    RoadState.Feedback.SAVED -> R.string.review_road_saved
                                    RoadState.Feedback.CLEARED -> R.string.review_road_cleared
                                    RoadState.Feedback.MEMO_SAVED -> R.string.review_road_memo_saved
                                    RoadState.Feedback.MEMO_CLEARED -> R.string.review_road_memo_cleared
                                }
                            Text(
                                stringResource(message),
                                Modifier.fillMaxWidth().testTag("review_road_feedback"),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        if (displayed.stored != null && displayed.draft == displayed.stored.road) {
                            val begin = {
                                viewModel.beginMemo(displayed.token)
                                Unit
                            }
                            ParkedButton(
                                onClick = begin,
                                enabled = canChange,
                                prominent = false,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .testTag("review_road_memo_edit")
                                        .frozenObservationClick(begin, canChange),
                            ) {
                                Text(
                                    stringResource(
                                        if (displayed.stored.memoText.isEmpty()) {
                                            R.string.review_road_memo_create
                                        } else {
                                            R.string.review_road_memo_edit
                                        },
                                    ),
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                        if (displayed.candidates.isEmpty()) {
                            Text(
                                stringResource(R.string.review_road_no_candidates),
                                Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        displayed.candidates.forEachIndexed { index, road ->
                            val select = {
                                viewModel.select(displayed.token, road.id)
                                Unit
                            }
                            ParkedChoice(
                                title = road.title,
                                description = "",
                                selected = displayed.draft == road,
                                enabled = canChange,
                                showDescription = false,
                                onClick = select,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .testTag("review_road_candidate_$index")
                                        .frozenObservationClick(select, canChange),
                            )
                        }
                        val roads = displayed.draft?.let(::listOf) ?: displayed.candidates
                        if (roadMap != null && roads.isNotEmpty()) {
                            key(displayed.token) {
                                roadMap(
                                    expectedSource,
                                    roads,
                                    displayed.draftRoadId,
                                    { allowed() && viewModel.canRead(displayed.token) },
                                    viewModel.inputChanges,
                                )
                            }
                        }
                        if (displayed.busy) {
                            Text(
                                stringResource(R.string.review_road_busy),
                                Modifier.fillMaxWidth().testTag("review_road_busy"),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        } else if (!displayed.canWrite) {
                            Text(
                                stringResource(R.string.review_road_active),
                                Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        val canSave = canChange && displayed.draft != null && displayed.candidates.any { it == displayed.draft }
                        val save = {
                            viewModel.save(displayed.token)
                            Unit
                        }
                        ParkedButton(
                            onClick = save,
                            enabled = canSave,
                            modifier = Modifier.fillMaxWidth().testTag("review_road_save").frozenObservationClick(save, canSave),
                        ) { Text(stringResource(R.string.review_road_save), style = MaterialTheme.typography.titleMedium) }
                        if (displayed.draft != displayed.stored?.road) {
                            val cancel = {
                                viewModel.cancel(displayed.token)
                                Unit
                            }
                            ParkedButton(
                                onClick = cancel,
                                enabled = canChange,
                                prominent = false,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .testTag("review_road_cancel")
                                        .frozenObservationClick(cancel, canChange),
                            ) { Text(stringResource(R.string.review_road_cancel), style = MaterialTheme.typography.titleMedium) }
                        }
                        if (displayed.stored != null) {
                            val clear = {
                                viewModel.clear(displayed.token)
                                Unit
                            }
                            ParkedButton(
                                onClick = clear,
                                enabled = canChange,
                                prominent = false,
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .testTag("review_road_clear")
                                        .frozenObservationClick(clear, canChange),
                            ) { Text(stringResource(R.string.review_road_clear), style = MaterialTheme.typography.titleMedium) }
                        }
                    }
                    displayed.confirmation?.let { pending ->
                        key(displayed.token, pending.nonce) {
                            val cancel = {
                                viewModel.cancelDiscard(displayed.token, pending.nonce)
                                Unit
                            }
                            val confirm = {
                                viewModel.confirmDiscard(displayed.token, pending.nonce)
                                Unit
                            }
                            AlertDialog(
                                onDismissRequest = cancel,
                                modifier = Modifier.testTag("review_road_memo_discard_dialog"),
                                title = {
                                    Text(
                                        stringResource(R.string.review_road_memo_discard_title),
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.md)) {
                                        Text(pending.expectedCurrent.road.title, style = MaterialTheme.typography.titleLarge)
                                        Text(
                                            stringResource(
                                                if (pending.target == null) {
                                                    R.string.review_road_memo_clear_warning
                                                } else {
                                                    R.string.review_road_memo_replace_warning
                                                },
                                            ),
                                            style = MaterialTheme.typography.bodyLarge,
                                        )
                                        pending.target?.let { Text(it.title, style = MaterialTheme.typography.titleLarge) }
                                    }
                                },
                                confirmButton = {
                                    ParkedButton(
                                        onClick = confirm,
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .testTag("review_road_memo_discard_confirm")
                                                .frozenObservationClick(confirm),
                                    ) {
                                        Text(
                                            stringResource(
                                                if (pending.target == null) {
                                                    R.string.review_road_memo_clear_confirm
                                                } else {
                                                    R.string.review_road_memo_replace_confirm
                                                },
                                            ),
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                    }
                                },
                                dismissButton = {
                                    ParkedButton(
                                        onClick = cancel,
                                        prominent = false,
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .testTag("review_road_memo_discard_cancel")
                                                .frozenObservationClick(cancel),
                                    ) {
                                        Text(
                                            stringResource(R.string.review_road_memo_keep),
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}
