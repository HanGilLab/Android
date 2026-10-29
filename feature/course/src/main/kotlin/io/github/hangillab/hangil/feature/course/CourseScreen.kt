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
import androidx.compose.ui.text.font.FontWeight
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
    val back = { if (!viewModel.back()) close() }
    BackHandler(onBack = back)
    CourseScreen(state, viewModel::selectGoal, viewModel::selectCourse, viewModel::selectTask, viewModel::next, back)
}

@Composable
internal fun CourseScreen(
    state: CourseUiState,
    onGoal: (String) -> Unit,
    onCourse: (String) -> Unit,
    onTask: (String) -> Unit,
    onNext: () -> Unit,
    onBack: () -> Unit,
) {
    key(state.allowsInteraction, state.step) {
        ParkedPage {
            PreparationHeader(state, onBack)
            if (!state.allowsInteraction) {
                ParkedLockedNotice()
            } else {
                when (state.step) {
                    PreparationStep.GOAL -> {
                        PreparationChoices(state.goals.size, Modifier.testTag("goal_choices")) { index, modifier ->
                            val goal = state.goals[index]
                            val selected = state.goal?.id == goal.id
                            ParkedChoice(
                                goal.title,
                                goal.description,
                                selected,
                                { onGoal(goal.id) },
                                modifier.testTag("goal_${goal.id}"),
                                showDescription = selected,
                            )
                        }
                    }
                    PreparationStep.COURSE -> {
                        Column(Modifier.testTag("course_context")) { DemoNotice() }
                        PreparationChoices(state.courses.size, Modifier.testTag("course_choices")) { index, modifier ->
                            val course = state.courses[index]
                            val selected = state.course?.id == course.id
                            ParkedChoice(course.title, course.summary, selected, {
                                onCourse(course.id)
                            }, modifier.testTag("course_${course.id}"), showDescription = selected)
                        }
                        DemoDetails()
                    }
                    PreparationStep.BRIEFING -> {
                        DemoNotice()
                        state.course?.let { course ->
                            PreparationChoices(course.tasks.size, Modifier.testTag("briefing_tasks")) { index, modifier ->
                                val task = course.tasks[index]
                                val selected = state.taskId == task.id
                                ParkedChoice(
                                    task.title,
                                    task.description,
                                    selected,
                                    { onTask(task.id) },
                                    modifier.testTag("task_${task.id}"),
                                    showDescription = selected,
                                )
                            }
                            Column(Modifier.fillMaxWidth().testTag("briefing_summary")) {
                                ParkedDisclosure(
                                    stringResource(R.string.course_details, course.title),
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
                            DemoDetails()
                        }
                    }
                }
                if (state.saveFailed) {
                    Text(
                        stringResource(R.string.save_failed),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
                    ParkedButton(
                        onClick = onNext,
                        enabled = state.canContinue,
                        modifier =
                            Modifier.widthIn(max = HanGilDimensions.parkedActionMaxWidth).fillMaxWidth().testTag("preparation_next"),
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
            ParkedButton(onClick = onBack, enabled = !state.saving, prominent = false) {
                Text(stringResource(R.string.course_back), style = MaterialTheme.typography.titleMedium)
            }
        }
        if (state.allowsInteraction) {
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
            verticalArrangement = Arrangement.spacedBy(HanGilSpacing.md),
        ) {
            if (wide) {
                (0 until count).chunked(2).forEach { indices ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) {
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
private fun DemoDetails() {
    ParkedDisclosure(stringResource(R.string.demo_details), Modifier.testTag("preparation_demo_details")) {
        Text(
            stringResource(R.string.demo_notice),
            Modifier.testTag("preparation_demo_description"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
