package io.github.hangillab.hangil.feature.course

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.hangillab.hangil.core.designsystem.PracticeBadge
import io.github.hangillab.hangil.core.designsystem.PracticeChoice
import io.github.hangillab.hangil.core.designsystem.PracticeColumns
import io.github.hangillab.hangil.core.designsystem.PracticeLockedNotice
import io.github.hangillab.hangil.core.designsystem.PracticePage
import io.github.hangillab.hangil.core.designsystem.PracticePanel

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
    PracticePage {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(stringResource(R.string.course_brand), style = MaterialTheme.typography.titleLarge)
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 64.dp), enabled = !state.saving) {
                Text(stringResource(R.string.course_back), style = MaterialTheme.typography.titleMedium)
            }
        }
        if (!state.allowsInteraction) {
            PracticeLockedNotice()
        } else {
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
                style = MaterialTheme.typography.displaySmall,
            )
            when (state.step) {
                PreparationStep.GOAL -> {
                    Text(stringResource(R.string.goal_intro), style = MaterialTheme.typography.bodyLarge)
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        state.goals.forEach { goal ->
                            PracticeChoice(
                                goal.title,
                                goal.description,
                                state.goal?.id == goal.id,
                                { onGoal(goal.id) },
                                Modifier.testTag("goal_${goal.id}"),
                            )
                        }
                    }
                }
                PreparationStep.COURSE -> {
                    DemoNotice()
                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        state.courses.forEach { course ->
                            PracticeChoice(course.title, course.summary, state.course?.id == course.id, {
                                onCourse(course.id)
                            }, Modifier.testTag("course_${course.id}"))
                        }
                    }
                }
                PreparationStep.BRIEFING -> {
                    DemoNotice()
                    state.course?.let { course ->
                        PracticeColumns(first = {
                            PracticePanel {
                                Text(course.title, style = MaterialTheme.typography.headlineMedium)
                                Text(course.summary, style = MaterialTheme.typography.bodyLarge)
                                course.sections.forEachIndexed { index, section ->
                                    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                        Text(
                                            "0${index + 1}",
                                            color = MaterialTheme.colorScheme.primary,
                                            style = MaterialTheme.typography.titleLarge,
                                        )
                                        Text(section, style = MaterialTheme.typography.titleLarge)
                                    }
                                }
                            }
                        }, second = {
                            Text(stringResource(R.string.task_title), style = MaterialTheme.typography.headlineSmall)
                            Text(stringResource(R.string.task_intro), style = MaterialTheme.typography.bodyLarge)
                            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                                course.tasks.forEach { task ->
                                    PracticeChoice(
                                        task.title,
                                        task.description,
                                        state.taskId == task.id,
                                        { onTask(task.id) },
                                        Modifier.testTag("task_${task.id}"),
                                    )
                                }
                            }
                        })
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
            Button(
                onClick = onNext,
                enabled = state.canContinue,
                modifier = Modifier.fillMaxWidth().heightIn(min = 76.dp).testTag("preparation_next"),
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
                )
            }
        }
    }
}

@Composable
private fun DemoNotice() {
    PracticeBadge(stringResource(R.string.demo_badge))
    Text(stringResource(R.string.demo_notice), style = MaterialTheme.typography.bodyLarge)
}
