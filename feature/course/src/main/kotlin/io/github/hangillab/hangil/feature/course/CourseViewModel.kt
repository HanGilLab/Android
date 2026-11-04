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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
        private var ownsDraft = listOf("step", "goal", "course", "courseVersion", "briefingVersion", "task").any(savedState::contains)
        private val preparationRead =
            MutableStateFlow(
                when {
                    !ownsDraft -> PreparationReadState.LOADING
                    savedState.get<Boolean>("initialPreparationOutdated") == true -> PreparationReadState.OUTDATED
                    else -> PreparationReadState.READY
                },
            )
        private val draft = MutableStateFlow(restoreDraft())
        private val saving = MutableStateFlow(false)
        private val saveFailed = MutableStateFlow(false)
        private val sessionOpen = MutableStateFlow(true)
        private var saveJob: Job? = null
        private var readJob: Job? = null
        private var readOwner: Any? = null

        @Volatile private var visible = false
        private val savedEvents = Channel<Unit>(Channel.BUFFERED)
        val saved = savedEvents.receiveAsFlow()

        internal val uiState =
            combine(
                combine(vehicle.state, restrictions.restrictions, sessionOpen) { v, r, open -> open && allowsPracticeInteraction(v, r) },
                draft,
                saving,
                saveFailed,
                preparationRead,
            ) { allowed, current, busy, failed, read ->
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
                    preparationRead = read,
                )
            }.onStart {
                visible = true
                if (preparationRead.value == PreparationReadState.LOADING) startPreparationRead()
            }.onCompletion {
                visible = false
                cancelPreparationRead()
                saveJob?.cancel()
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(0, 0), CourseUiState())

        /** Rechecks the displayed draft directly, before a delayed combined UI update. */
        internal fun canRevealDetails(expected: CourseUiState): Boolean {
            if (!preparationRead.value.allowsEditing || !livePermission() || !expected.allowsInteraction) return false
            val current = draft.value
            if (current.step != expected.step || current.goalId != expected.goal?.id ||
                current.courseId != expected.course?.id || current.taskId != expected.taskId
            ) {
                return false
            }
            if (current.step != PreparationStep.COURSE && current.step != PreparationStep.BRIEFING) return false
            if (catalog.goals.find { it.id == current.goalId } != expected.goal) return false
            val course = expected.course ?: return current.courseId == null
            return current.courseVersion == course.version && current.briefingVersion == course.briefingVersion &&
                catalog.courses.find { it.id == current.courseId && current.goalId in it.goalIds } == course
        }

        fun selectGoal(id: String) {
            if (!canEdit() || draft.value.step != PreparationStep.GOAL || catalog.goals.none { it.id == id }) return
            if (draft.value.goalId != id) update(PreparationDraft(goalId = id))
        }

        fun selectCourse(id: String) {
            if (!canEdit() || draft.value.step != PreparationStep.COURSE) return
            val current = draft.value
            val course = catalog.courses.find { it.id == id && current.goalId in it.goalIds } ?: return
            if (current.courseId == course.id && current.courseVersion == course.version &&
                current.briefingVersion == course.briefingVersion && course.tasks.any { it.id == current.taskId }
            ) {
                return
            }
            update(current.copy(courseId = id, courseVersion = course.version, briefingVersion = course.briefingVersion, taskId = null))
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
            cancelPreparationRead()
            saveJob?.cancel()
        }

        fun retryPreparationRead() {
            if (preparationRead.value != PreparationReadState.FAILED || !livePermission()) return
            startPreparationRead()
        }

        private fun startPreparationRead() {
            if (ownsDraft || !visible || !sessionOpen.value) return
            cancelPreparationRead()
            val owner = Any()
            readOwner = owner
            preparationRead.value = PreparationReadState.LOADING
            val job =
                viewModelScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val selection = repository.observeSelection().first()
                        coroutineContext.ensureActive()
                        val resolved = catalog.resolve(selection)
                        coroutineContext.ensureActive()
                        if (readOwner !== owner || ownsDraft || !visible || !sessionOpen.value) return@launch
                        val practice = (resolved as? Preparation.Ready)?.practice
                        val initial =
                            if (practice == null) {
                                PreparationDraft()
                            } else {
                                PreparationDraft(
                                    goalId = practice.goal.id,
                                    courseId = practice.course.id,
                                    courseVersion = practice.course.version,
                                    briefingVersion = practice.course.briefingVersion,
                                    taskId = practice.task.id,
                                )
                            }
                        val outdated = resolved == Preparation.Outdated
                        update(initial, initialOutdated = outdated)
                        preparationRead.value = if (outdated) PreparationReadState.OUTDATED else PreparationReadState.READY
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        coroutineContext.ensureActive()
                        if (readOwner === owner && !ownsDraft && visible && sessionOpen.value) {
                            preparationRead.value = PreparationReadState.FAILED
                        }
                    } finally {
                        if (readOwner === owner) {
                            readOwner = null
                            readJob = null
                        }
                    }
                }
            readJob = job
            job.start()
        }

        private fun cancelPreparationRead() {
            readOwner = null
            val job = readJob
            readJob = null
            job?.cancel()
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
                                job.isActive && preparationRead.value.allowsEditing && livePermission() && draft.value == snapshot
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

        private fun canEdit() = !saving.value && preparationRead.value.allowsEditing && livePermission()

        private fun livePermission() =
            visible && sessionOpen.value && allowsPracticeInteraction(vehicle.state.value, restrictions.restrictions.value)

        private fun update(
            value: PreparationDraft,
            initialOutdated: Boolean = false,
        ) {
            savedState["step"] = value.step.name
            savedState["goal"] = value.goalId
            savedState["course"] = value.courseId
            savedState["courseVersion"] = value.courseVersion
            savedState["briefingVersion"] = value.briefingVersion
            savedState["task"] = value.taskId
            savedState["initialPreparationOutdated"] = initialOutdated
            ownsDraft = true
            draft.value = value
            if (!initialOutdated &&
                preparationRead.value == PreparationReadState.OUTDATED
            ) {
                preparationRead.value = PreparationReadState.READY
            }
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
