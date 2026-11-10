package io.github.hangillab.hangil.feature.course

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilBrand
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.designsystem.ParkedBadge
import io.github.hangillab.hangil.core.designsystem.ParkedButton
import io.github.hangillab.hangil.core.designsystem.ParkedChoice
import io.github.hangillab.hangil.core.designsystem.ParkedDisclosure
import io.github.hangillab.hangil.core.designsystem.ParkedLockedNotice
import io.github.hangillab.hangil.core.designsystem.ParkedPage
import io.github.hangillab.hangil.core.designsystem.ParkedPanel

@Composable
fun CourseRoute(
    onClose: () -> Unit,
    viewModel: CourseViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnClose by rememberUpdatedState(onClose)
    var closing by remember(viewModel) { mutableStateOf(false) }
    val close = {
        if (!closing) {
            closing = true
            viewModel.leave()
            currentOnClose()
        }
    }
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { viewModel.saved.collect { close() } }
    }
    val displayed = state
    val back = { if (!viewModel.back(displayed.actionGeneration)) close() }
    BackHandler(onBack = back)
    CourseScreen(
        displayed,
        { viewModel.selectGoal(it, displayed.actionGeneration) },
        { viewModel.selectCourse(it, displayed.actionGeneration) },
        { viewModel.selectTask(it, displayed.actionGeneration) },
        { viewModel.next(displayed.actionGeneration) },
        back,
        onRetryPreparationRead = { viewModel.retryPreparationRead(displayed.actionGeneration) },
    ) {
        viewModel.canRevealDetails(displayed)
    }
}

@Composable
internal fun CourseScreen(
    state: CourseUiState,
    onGoal: (String) -> Unit,
    onCourse: (String) -> Unit,
    onTask: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
    onRetryPreparationRead: () -> Unit,
    canExpandDetails: () -> Boolean,
) {
    key(state.allowsInteraction, state.step) {
        ParkedPage {
            PreparationHeader(state, onBack)
            if (!state.allowsInteraction) {
                ParkedLockedNotice()
            } else if (state.preparationRead == PreparationReadState.LOADING) {
                ParkedPanel(Modifier.testTag("preparation_read_loading")) {
                    Text(stringResource(R.string.preparation_read_loading), style = MaterialTheme.typography.titleLarge)
                }
            } else if (state.preparationRead == PreparationReadState.FAILED) {
                ParkedPanel(Modifier.testTag("preparation_read_failed")) {
                    Text(stringResource(R.string.preparation_read_failed), style = MaterialTheme.typography.titleLarge)
                    ParkedButton(
                        onClick = onRetryPreparationRead,
                        modifier =
                            Modifier.fillMaxWidth().testTag("preparation_read_retry").semantics {
                                onClick {
                                    onRetryPreparationRead()
                                    true
                                }
                            },
                    ) {
                        Text(stringResource(R.string.preparation_read_retry), style = MaterialTheme.typography.titleLarge)
                    }
                }
            } else {
                if (state.preparationRead == PreparationReadState.OUTDATED) {
                    Text(
                        stringResource(R.string.preparation_read_outdated),
                        Modifier.testTag("preparation_read_outdated"),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.plannedRoad?.let { plan ->
                    Text(
                        stringResource(R.string.preparation_planned_road_title, plan.road.title),
                        Modifier.testTag("preparation_planned_road"),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(stringResource(R.string.preparation_planned_road_scope), style = MaterialTheme.typography.bodyLarge)
                }
                if (state.plannedRoadCleared) {
                    Text(
                        stringResource(R.string.preparation_planned_road_cleared),
                        Modifier.testTag("preparation_planned_road_cleared"),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                when (state.step) {
                    PreparationStep.GOAL -> {
                        PreparationChoices(state.goals.size, Modifier.testTag("goal_choices")) { index, modifier ->
                            val goal = state.goals[index]
                            val selected = state.goal?.id == goal.id
                            val chooseGoal = { onGoal(goal.id) }
                            ParkedChoice(
                                goal.title,
                                goal.description,
                                selected,
                                chooseGoal,
                                modifier.testTag("goal_${goal.id}").frozenPreparationClick(chooseGoal, !state.saving),
                                showDescription = selected,
                                enabled = !state.saving,
                            )
                        }
                    }
                    PreparationStep.COURSE -> {
                        Column(Modifier.testTag("course_context")) { DemoNotice() }
                        PreparationChoices(state.courses.size, Modifier.testTag("course_choices")) { index, modifier ->
                            val course = state.courses[index]
                            val selected = state.course?.id == course.id
                            val chooseCourse = { onCourse(course.id) }
                            ParkedChoice(
                                course.title,
                                course.summary,
                                selected,
                                chooseCourse,
                                modifier.testTag("course_${course.id}").frozenPreparationClick(chooseCourse, !state.saving),
                                showDescription = selected,
                                enabled = !state.saving,
                            )
                        }
                        DemoDetails(canExpandDetails)
                    }
                    PreparationStep.BRIEFING -> {
                        DemoNotice()
                        state.course?.let { course ->
                            PreparationChoices(course.tasks.size, Modifier.testTag("briefing_tasks")) { index, modifier ->
                                val task = course.tasks[index]
                                val selected = state.taskId == task.id
                                val chooseTask = { onTask(task.id) }
                                ParkedChoice(
                                    task.title,
                                    task.description,
                                    selected,
                                    chooseTask,
                                    modifier.testTag("task_${task.id}").frozenPreparationClick(chooseTask, !state.saving),
                                    showDescription = selected,
                                    enabled = !state.saving,
                                )
                            }
                            Column(Modifier.fillMaxWidth().testTag("briefing_summary")) {
                                ParkedDisclosure(
                                    stringResource(R.string.course_details, course.title),
                                    canExpandDetails,
                                    Modifier.testTag("briefing_course_details"),
                                ) {
                                    Text(
                                        course.summary,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                    course.sections.forEachIndexed { index, section ->
                                        Row(
                                            Modifier.padding(vertical = HanGilSpacing.xs),
                                            horizontalArrangement = Arrangement.spacedBy(HanGilSpacing.md),
                                        ) {
                                            Surface(
                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                shape = MaterialTheme.shapes.small,
                                            ) {
                                                Text(
                                                    "0${index + 1}",
                                                    Modifier.padding(horizontal = HanGilSpacing.sm, vertical = HanGilSpacing.xs),
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                )
                                            }
                                            Text(
                                                section,
                                                Modifier.testTag("briefing_section_$index"),
                                                style = MaterialTheme.typography.titleLarge,
                                            )
                                        }
                                    }
                                }
                            }
                            DemoDetails(canExpandDetails)
                        }
                    }
                }
                if (state.saveFailed) {
                    Text(
                        stringResource(R.string.save_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    ParkedButton(
                        onClick = onRetryPreparationRead,
                        prominent = false,
                        modifier =
                            Modifier.fillMaxWidth().testTag("preparation_reload_saved").semantics {
                                onClick {
                                    onRetryPreparationRead()
                                    true
                                }
                            },
                    ) {
                        Text(stringResource(R.string.preparation_reload_saved), style = MaterialTheme.typography.titleLarge)
                    }
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    ParkedButton(
                        onClick = onNext,
                        enabled = state.canContinue,
                        modifier =
                            Modifier
                                .widthIn(
                                    max = HanGilDimensions.parkedActionMaxWidth,
                                ).fillMaxWidth()
                                .testTag("preparation_next")
                                .semantics {
                                    if (state.canContinue) {
                                        onClick {
                                            onNext()
                                            true
                                        }
                                    }
                                },
                    ) {
                        Text(
                            stringResource(
                                when {
                                    state.saving -> R.string.saving
                                    state.step == PreparationStep.BRIEFING -> R.string.save_preparation
                                    else -> R.string.next
                                },
                            ),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PreparationHeader(
    state: CourseUiState,
    onBack: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.sm)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.allowsInteraction) {
                HanGilBrand(Modifier.weight(1f), markSize = HanGilDimensions.parkedBrandMarkSize)
            } else {
                Text(
                    stringResource(R.string.course_brand),
                    Modifier.weight(1f).testTag("preparation_locked_title"),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            ParkedButton(
                onClick = onBack,
                enabled = !state.saving,
                prominent = false,
                modifier = Modifier.frozenPreparationClick(onBack, !state.saving),
            ) {
                Text(stringResource(R.string.course_back), style = MaterialTheme.typography.titleMedium)
            }
        }
        if (state.allowsInteraction && state.preparationRead.allowsEditing) {
            Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.xs)) {
                Text(
                    stringResource(R.string.course_steps, state.step.ordinal + 1),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    stringResource(
                        when (state.step) {
                            PreparationStep.GOAL -> R.string.goal_title
                            PreparationStep.COURSE -> R.string.course_title
                            PreparationStep.BRIEFING -> R.string.briefing_title
                        },
                    ),
                    Modifier.testTag("preparation_title"),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun PreparationChoices(
    count: Int,
    modifier: Modifier = Modifier,
    choice: @Composable (Int, Modifier) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier.fillMaxWidth().selectableGroup()) {
        val wide = maxWidth >= HanGilDimensions.parkedColumnBreakpoint * fontScale
        Column(
            Modifier.fillMaxWidth().testTag(if (wide) "preparation_choices_wide" else "preparation_choices_stacked"),
            verticalArrangement = Arrangement.spacedBy(28.dp),
        ) {
            if (wide) {
                (0 until count).chunked(2).forEach { indices ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        indices.forEach { index -> choice(index, Modifier.weight(1f)) }
                    }
                }
            } else {
                repeat(count) { index -> choice(index, Modifier.fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun DemoNotice() {
    Column(Modifier.fillMaxWidth().testTag("preparation_demo_notice")) {
        ParkedBadge(stringResource(R.string.demo_badge))
    }
}

@Composable
private fun DemoDetails(canExpandDetails: () -> Boolean) {
    ParkedDisclosure(stringResource(R.string.demo_details), canExpandDetails, Modifier.testTag("preparation_demo_details")) {
        Text(
            stringResource(R.string.demo_notice),
            Modifier.testTag("preparation_demo_description"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun Modifier.frozenPreparationClick(
    action: () -> Unit,
    enabled: Boolean,
): Modifier =
    semantics {
        if (enabled) {
            onClick {
                action()
                true
            }
        }
    }
