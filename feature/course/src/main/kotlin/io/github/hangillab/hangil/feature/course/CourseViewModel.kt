package io.github.hangillab.hangil.feature.course

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CourseViewModel
    @Inject
    constructor(
        private val catalog: PracticeCatalog,
        private val repository: PracticeRepository,
        private val vehicle: VehicleGateway,
        private val restrictions: DrivingRestrictionsProvider,
        private val savedState: SavedStateHandle,
    ) : ViewModel() {
        private val draft = MutableStateFlow(restoreDraft())
        private val saving = MutableStateFlow(false)
        private val saveFailed = MutableStateFlow(false)
        private val sessionOpen = MutableStateFlow(true)
        private var saveJob: Job? = null

        @Volatile private var visible = false
        private val savedEvents = Channel<Unit>(Channel.BUFFERED)
        val saved = savedEvents.receiveAsFlow()

        internal val uiState =
            combine(
                combine(vehicle.state, restrictions.restrictions, sessionOpen) { v, r, open -> open && allowsPracticeInteraction(v, r) },
                draft,
                saving,
                saveFailed,
            ) { allowed, current, busy, failed ->
                if (!allowed) saveJob?.cancel()
                CourseUiState(
                    allowsInteraction = allowed,
                    step = current.step,
                    goals = catalog.goals,
                    courses = catalog.courses.filter { current.goalId in it.goalIds },
                    goal = catalog.goals.find { it.id == current.goalId },
                    course = catalog.courses.find { it.id == current.courseId },
                    taskId = current.taskId,
                    saving = busy,
                    saveFailed = failed,
                )
            }.onStart { visible = true }
                .onCompletion {
                    visible = false
                    saveJob?.cancel()
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), CourseUiState())

        fun selectGoal(id: String) {
            if (!canEdit() || draft.value.step != PreparationStep.GOAL || catalog.goals.none { it.id == id }) return
            if (draft.value.goalId != id) update(PreparationDraft(goalId = id))
        }

        fun selectCourse(id: String) {
            if (!canEdit() || draft.value.step != PreparationStep.COURSE) return
            val course = catalog.courses.find { it.id == id && draft.value.goalId in it.goalIds } ?: return
            update(draft.value.copy(courseId = id, courseVersion = course.version, briefingVersion = course.briefingVersion, taskId = null))
        }

        fun selectTask(id: String) {
            if (!canEdit() || draft.value.step != PreparationStep.BRIEFING) return
            val course = catalog.courses.find { it.id == draft.value.courseId } ?: return
            if (course.tasks.any { it.id == id }) update(draft.value.copy(taskId = id))
        }

        fun next() {
            if (!canEdit()) return
            when (draft.value.step) {
                PreparationStep.GOAL -> if (draft.value.goalId != null) update(draft.value.copy(step = PreparationStep.COURSE))
                PreparationStep.COURSE -> if (draft.value.courseId != null) update(draft.value.copy(step = PreparationStep.BRIEFING))
                PreparationStep.BRIEFING -> save()
            }
        }

        fun back(): Boolean {
            if (!canEdit()) {
                leave()
                return false
            }
            val previous =
                when (draft.value.step) {
                    PreparationStep.GOAL -> {
                        leave()
                        return false
                    }
                    PreparationStep.COURSE -> PreparationStep.GOAL
                    PreparationStep.BRIEFING -> PreparationStep.COURSE
                }
            update(draft.value.copy(step = previous))
            return true
        }

        fun leave() {
            sessionOpen.value = false
            saveJob?.cancel()
        }

        private fun save() {
            val snapshot = draft.value
            val selection = snapshot.selection() ?: return
            if (catalog.resolve(selection) !is Preparation.Ready) return
            saving.value = true
            saveFailed.value = false
            saveJob =
                viewModelScope.launch {
                    try {
                        val job = coroutineContext[Job]!!
                        val committed =
                            repository.saveSelection(selection) {
                                job.isActive && livePermission() && draft.value == snapshot
                            }
                        coroutineContext.ensureActive()
                        if (committed && sessionOpen.value) savedEvents.send(Unit)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        saveFailed.value = true
                    } finally {
                        saving.value = false
                    }
                }
        }

        private fun canEdit() = !saving.value && livePermission()

        private fun livePermission() =
            visible && sessionOpen.value && allowsPracticeInteraction(vehicle.state.value, restrictions.restrictions.value)

        private fun update(value: PreparationDraft) {
            savedState["step"] = value.step.name
            savedState["goal"] = value.goalId
            savedState["course"] = value.courseId
            savedState["courseVersion"] = value.courseVersion
            savedState["briefingVersion"] = value.briefingVersion
            savedState["task"] = value.taskId
            draft.value = value
            saveFailed.value = false
        }

        private fun restoreDraft(): PreparationDraft {
            val goal = catalog.goals.find { it.id == savedState.get<String>("goal") } ?: return PreparationDraft()
            val course =
                catalog.courses.find {
                    it.id == savedState.get<String>("course") && goal.id in it.goalIds &&
                        it.version == savedState.get<Int>("courseVersion") && it.briefingVersion == savedState.get<Int>("briefingVersion")
                }
            val step = PreparationStep.entries.find { it.name == savedState.get<String>("step") } ?: PreparationStep.GOAL
            if (course == null) {
                return PreparationDraft(
                    step = if (step == PreparationStep.GOAL) step else PreparationStep.COURSE,
                    goalId = goal.id,
                )
            }
            return PreparationDraft(
                step,
                goal.id,
                course.id,
                course.version,
                course.briefingVersion,
                savedState.get<String>("task")?.takeIf { id -> course.tasks.any { it.id == id } },
            )
        }
    }
