package io.github.hangillab.hangil.feature.course

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import io.github.hangillab.hangil.core.designsystem.HanGilBrand
import io.github.hangillab.hangil.core.designsystem.HanGilDimensions
import io.github.hangillab.hangil.core.designsystem.HanGilSpacing
import io.github.hangillab.hangil.core.designsystem.PracticeBadge
import io.github.hangillab.hangil.core.designsystem.PracticeButton
import io.github.hangillab.hangil.core.designsystem.PracticeChoice
import io.github.hangillab.hangil.core.designsystem.PracticeLockedNotice
import io.github.hangillab.hangil.core.designsystem.PracticePage
import io.github.hangillab.hangil.core.designsystem.PracticePanel
import io.github.hangillab.hangil.core.designsystem.PracticeTextButton
import io.github.hangillab.hangil.core.designsystem.R as DesignR

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
        PracticePage {
            PreparationHeader(state, onBack)
            if (!state.allowsInteraction) {
                PracticeLockedNotice()
            } else {
                when (state.step) {
                    PreparationStep.GOAL -> {
                        Text(stringResource(R.string.goal_intro), style = MaterialTheme.typography.bodyLarge)
                        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.md)) {
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
                        PreparationColumns(first = {
                            PracticePanel(
                                Modifier.testTag("course_context"),
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            ) {
                                DemoNotice()
                                PreparationIllustration()
                            }
                        }, second = {
                            Column(
                                Modifier.fillMaxWidth().testTag("course_choices").selectableGroup(),
                                verticalArrangement = Arrangement.spacedBy(HanGilSpacing.md),
                            ) {
                                state.courses.forEach { course ->
                                    PracticeChoice(course.title, course.summary, state.course?.id == course.id, {
                                        onCourse(course.id)
                                    }, Modifier.testTag("course_${course.id}"))
                                }
                            }
                        })
                    }
                    PreparationStep.BRIEFING -> {
                        DemoNotice()
                        state.course?.let { course ->
                            PreparationColumns(first = {
                                PracticePanel(
                                    Modifier.testTag("briefing_summary"),
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ) {
                                    Text(course.title, style = MaterialTheme.typography.headlineMedium)
                                    Text(course.summary, style = MaterialTheme.typography.bodyLarge)
                                    course.sections.forEachIndexed { index, section ->
                                        Row(
                                            Modifier.padding(vertical = HanGilSpacing.xs),
                                            horizontalArrangement = Arrangement.spacedBy(HanGilSpacing.lg),
                                        ) {
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
                                PracticePanel(Modifier.testTag("briefing_tasks")) {
                                    Text(stringResource(R.string.task_title), style = MaterialTheme.typography.headlineSmall)
                                    Text(stringResource(R.string.task_intro), style = MaterialTheme.typography.bodyLarge)
                                    Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.md)) {
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
                PracticeButton(
                    onClick = onNext,
                    enabled = state.canContinue,
                    modifier = Modifier.fillMaxWidth().testTag("preparation_next"),
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
}

@Composable
private fun PreparationHeader(
    state: CourseUiState,
    onBack: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(HanGilSpacing.md)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (state.allowsInteraction) {
                HanGilBrand(Modifier.weight(1f))
            } else {
                Text(
                    stringResource(R.string.course_brand),
                    Modifier.weight(1f).testTag("preparation_locked_title"),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            PracticeTextButton(onClick = onBack, enabled = !state.saving) {
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
                )
            }
        }
    }
}

@Composable
private fun PreparationColumns(
    first: @Composable () -> Unit,
    second: @Composable () -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= HanGilDimensions.wideBreakpoint * fontScale) {
            Row(
                Modifier.fillMaxWidth().testTag("preparation_columns_wide"),
                horizontalArrangement = Arrangement.spacedBy(HanGilSpacing.xl),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) { first() }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.lg)) { second() }
            }
        } else {
            Column(
                Modifier.fillMaxWidth().testTag("preparation_columns_stacked"),
                verticalArrangement = Arrangement.spacedBy(HanGilSpacing.xl),
            ) {
                first()
                second()
            }
        }
    }
}

@Composable
private fun PreparationIllustration() {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        if (maxWidth >= HanGilDimensions.wideBreakpoint / 2 * fontScale) {
            Image(
                painterResource(DesignR.drawable.il_hangil_prepare),
                contentDescription = null,
                modifier = Modifier.width(HanGilDimensions.homeIllustrationMaxWidth).aspectRatio(1.5f).testTag("preparation_illustration"),
            )
        }
    }
}

@Composable
private fun DemoNotice() {
    Column(Modifier.fillMaxWidth().testTag("preparation_demo_notice"), verticalArrangement = Arrangement.spacedBy(HanGilSpacing.md)) {
        PracticeBadge(stringResource(R.string.demo_badge))
        Text(stringResource(R.string.demo_notice), style = MaterialTheme.typography.bodyLarge)
    }
}
