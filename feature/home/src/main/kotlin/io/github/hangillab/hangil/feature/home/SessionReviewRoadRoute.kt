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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.designsystem.ParkedButton
import io.github.hangillab.hangil.core.designsystem.ParkedChoice
import io.github.hangillab.hangil.core.designsystem.ParkedDisclosure
import io.github.hangillab.hangil.core.domain.CourseRoadSection
import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionReviewRoad
import io.github.hangillab.hangil.core.domain.SessionReviewSample
import kotlinx.coroutines.flow.Flow
import java.util.concurrent.atomic.AtomicBoolean
import io.github.hangillab.hangil.feature.home.SessionReviewRoadUiState as RoadState

/** Only the expanded exact history host creates this reader; a paused receipt never reopens itself. */
@Composable
fun SessionReviewRoadRoute(
    expectedSource: PracticeSession,
    canKeepOpen: () -> Boolean,
    roadMap: (@Composable (PracticeSession, List<CourseRoadSnapshot>, String?, CourseRoadSection?, () -> Boolean, Flow<Unit>) -> Unit)? =
        null,
    viewModel: SessionReviewRoadViewModel = hiltViewModel(),
    readOnly: Boolean = false,
    comparisonMap: (
        @Composable (
            SessionReviewRoad?,
            List<CourseRoadSnapshot>,
            ((String) -> Unit)?,
            ((CourseRoadSection?) -> Unit)?,
            () -> Boolean,
            Flow<Unit>,
        ) -> Unit
    )? = null,
    sampleCandidate: SessionReviewSample? = null,
    onOpenLinkedSample: ((SessionReviewSample) -> Boolean)? = null,
) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val receipt =
        remember(expectedSource, canKeepOpen, lifecycle, readOnly, sampleCandidate) {
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
    val comparing = readOnly && comparisonMap != null
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
            stringResource(
                when {
                    comparing -> R.string.review_road_comparison_scope
                    readOnly -> R.string.review_road_reference_scope
                    else -> R.string.review_road_scope
                },
            ),
            Modifier.fillMaxWidth().testTag(
                when {
                    comparing -> "review_road_comparison_scope"
                    readOnly -> "review_road_reference_scope"
                    else -> "review_road_scope"
                },
            ),
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
                        stringResource(
                            when {
                                comparing -> R.string.review_road_comparison_empty
                                readOnly -> R.string.review_road_reference_empty
                                else -> R.string.review_road_empty
                            },
                        ),
                        Modifier.fillMaxWidth().testTag("review_road_empty"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                } else {
                    Text(
                        stringResource(
                            when {
                                comparing -> R.string.review_road_comparison_source
                                readOnly -> R.string.review_road_reference_source
                                else -> R.string.review_road_source
                            },
                        ),
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
                displayed.stored?.takeIf { !comparing || it.memoText.isNotEmpty() }?.let { saved ->
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
                val anchor = displayed.stored?.sampleAnchor
                if (!comparing && anchor != null) {
                    Text(
                        stringResource(
                            if (anchor.roadSection == null) R.string.review_road_sample_present else R.string.review_road_section_present,
                        ),
                        Modifier.fillMaxWidth().testTag("review_road_sample_present"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    if (!readOnly && !displayed.busy && displayed.edit == null && displayed.confirmation == null) {
                        key(displayed.token, anchor) {
                            ParkedDisclosure(
                                title = stringResource(R.string.review_road_sample_details),
                                canExpand = { allowed() && viewModel.canRead(displayed.token) },
                                modifier = Modifier.testTag("review_road_sample_details"),
                            ) {
                                Column(
                                    Modifier.fillMaxWidth().testTag("review_road_sample_details_content"),
                                    verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg),
                                ) {
                                    Text(
                                        stringResource(R.string.review_road_sample_observation, anchor.event.ordinal),
                                        Modifier.fillMaxWidth().testTag("review_road_sample_observation"),
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                    Text(
                                        stringResource(
                                            R.string.location_history_record_time,
                                            locationHistoryTime(anchor.event.observedAtEpochMillis),
                                        ),
                                        Modifier.fillMaxWidth().testTag("review_road_sample_observed_time"),
                                        style = MaterialTheme.typography.bodyLarge,
                                    )
                                    LocationSampleRawFields(anchor.sample)
                                }
                            }
                        }
                    }
                }
                if (readOnly) {
                    val saved = displayed.stored
                    val candidate = sampleCandidate?.takeIf { it.event.sessionId == expectedSource.sessionId }
                    if (comparing && saved != null) {
                        ReviewSampleControls(displayed, candidate, viewModel, onOpenLinkedSample)
                    }
                    if (comparisonMap != null) {
                        val saveCourseRoad: ((String) -> Unit)? =
                            if (saved == null && candidate != null && displayed.canChangeSample) {
                                { roadId -> viewModel.saveFirstRoadAndSample(displayed.token, roadId, candidate) }
                            } else {
                                null
                            }
                        val onSetRoadSection: ((CourseRoadSection?) -> Unit)? =
                            if (candidate != null && anchor?.sameCallback(candidate) == true && displayed.canChangeSample) {
                                { section ->
                                    if (allowed() && viewModel.canRead(displayed.token)) {
                                        viewModel.setSample(displayed.token, candidate.withRoadSection(section))
                                    }
                                }
                            } else {
                                null
                            }
                        if (saved == null && candidate != null && displayed.candidates.isNotEmpty() && !displayed.canWrite) {
                            Text(
                                stringResource(if (displayed.busy) R.string.review_road_busy else R.string.review_road_sample_active),
                                Modifier.fillMaxWidth(),
                                style = MaterialTheme.typography.bodyLarge,
                            )
                        }
                        key(displayed.token) {
                            comparisonMap(
                                saved,
                                displayed.candidates,
                                saveCourseRoad,
                                onSetRoadSection,
                                { allowed() && viewModel.canRead(displayed.token) },
                                viewModel.inputChanges,
                            )
                        }
                    } else if (saved != null && roadMap != null) {
                        key(displayed.token) {
                            roadMap(
                                expectedSource,
                                listOf(saved.road),
                                saved.road.id,
                                saved.sampleAnchor?.roadSection,
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
                                    RoadState.Feedback.SAMPLE_SAVED -> R.string.review_road_sample_saved
                                    RoadState.Feedback.SAMPLE_CLEARED -> R.string.review_road_sample_cleared
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
                                    displayed.stored
                                        ?.takeIf { it.road == displayed.draft }
                                        ?.sampleAnchor
                                        ?.roadSection,
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
                                        stringResource(
                                            if (pending.expectedCurrent.sampleAnchor !=
                                                null
                                            ) {
                                                R.string.review_road_sample_discard_title
                                            } else {
                                                R.string.review_road_memo_discard_title
                                            },
                                        ),
                                        style = MaterialTheme.typography.titleLarge,
                                    )
                                },
                                text = {
                                    Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.md)) {
                                        Text(pending.expectedCurrent.road.title, style = MaterialTheme.typography.titleLarge)
                                        if (pending.expectedCurrent.memoText.isNotEmpty()) {
                                            Text(
                                                stringResource(
                                                    if (pending.target ==
                                                        null
                                                    ) {
                                                        R.string.review_road_memo_clear_warning
                                                    } else {
                                                        R.string.review_road_memo_replace_warning
                                                    },
                                                ),
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                        }
                                        if (pending.expectedCurrent.sampleAnchor != null) {
                                            Text(
                                                stringResource(R.string.review_road_sample_discard_warning),
                                                Modifier.testTag("review_road_sample_discard_warning"),
                                                style = MaterialTheme.typography.bodyLarge,
                                            )
                                        }
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

/** Only the explicitly expanded stored-sample comparison opts into this limited write surface. */
@Composable
private fun ReviewSampleControls(
    state: RoadState.Ready,
    candidate: SessionReviewSample?,
    viewModel: SessionReviewRoadViewModel,
    onOpenLinkedSample: ((SessionReviewSample) -> Boolean)?,
) {
    val stored = checkNotNull(state.stored)
    val anchor = stored.sampleAnchor
    val isCurrentSample = candidate != null && anchor?.sameCallback(candidate) == true
    val status =
        when {
            anchor == null -> R.string.review_road_sample_absent to "review_road_sample_absent"
            isCurrentSample -> R.string.review_road_sample_current to "review_road_sample_current"
            else -> R.string.review_road_sample_other to "review_road_sample_other"
        }
    Text(stringResource(status.first), Modifier.fillMaxWidth().testTag(status.second), style = MaterialTheme.typography.titleLarge)
    Text(
        stringResource(R.string.review_road_sample_scope),
        Modifier.fillMaxWidth().testTag("review_road_sample_scope"),
        style = MaterialTheme.typography.bodyLarge,
    )
    when (state.feedback) {
        RoadState.Feedback.SAMPLE_SAVED, RoadState.Feedback.SAMPLE_CLEARED ->
            Text(
                stringResource(
                    if (state.feedback ==
                        RoadState.Feedback.SAMPLE_SAVED
                    ) {
                        R.string.review_road_sample_saved
                    } else {
                        R.string.review_road_sample_cleared
                    },
                ),
                Modifier.fillMaxWidth().testTag("review_road_sample_feedback"),
                style = MaterialTheme.typography.bodyLarge,
            )
        else -> Unit
    }
    if (anchor != null && !isCurrentSample && onOpenLinkedSample != null) {
        var openFailed by remember(state.token, anchor) { mutableStateOf(false) }
        val open = {
            openFailed = !viewModel.canRead(state.token) || !onOpenLinkedSample(anchor)
        }
        ParkedButton(
            onClick = open,
            enabled = !state.busy,
            prominent = false,
            modifier = Modifier.fillMaxWidth().testTag("review_road_sample_open").frozenObservationClick(open, !state.busy),
        ) {
            Text(
                stringResource(R.string.review_road_sample_open),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.titleMedium,
            )
        }
        if (openFailed) {
            Text(
                stringResource(R.string.review_road_sample_open_failed),
                Modifier.fillMaxWidth().testTag("review_road_sample_open_failed"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
    val canChange = state.canChangeSample
    if (state.busy) {
        Text(
            stringResource(R.string.review_road_busy),
            Modifier.fillMaxWidth().testTag("review_road_busy"),
            style = MaterialTheme.typography.bodyLarge,
        )
    } else if (!state.canWrite) {
        Text(stringResource(R.string.review_road_sample_active), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge)
    }
    if (candidate != null && !isCurrentSample) {
        val link = {
            viewModel.setSample(state.token, candidate)
            Unit
        }
        ParkedButton(
            onClick = link,
            enabled = canChange,
            modifier = Modifier.fillMaxWidth().testTag("review_road_sample_link").frozenObservationClick(link, canChange),
        ) {
            Text(
                stringResource(
                    if (anchor ==
                        null
                    ) {
                        R.string.review_road_sample_link
                    } else {
                        R.string.review_road_sample_replace
                    },
                ),
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
    if (anchor != null) {
        val clear = {
            viewModel.setSample(state.token, null)
            Unit
        }
        ParkedButton(
            onClick = clear,
            enabled = canChange,
            prominent = false,
            modifier = Modifier.fillMaxWidth().testTag("review_road_sample_clear").frozenObservationClick(clear, canChange),
        ) {
            Text(stringResource(R.string.review_road_sample_clear), style = MaterialTheme.typography.titleMedium)
        }
    }
}
