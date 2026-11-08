package io.github.hangillab.hangil.feature.course

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.PlannedPracticeRoad
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSelection
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
        private val preparationRead = MutableStateFlow(PreparationReadState.LOADING)
        private var expectedCurrent: PracticeSelection? = null
        private var expectedLoaded = false
        private var originalPlannedRoad: PlannedPracticeRoad? = null
        private val draft = MutableStateFlow(restoreDraft())
        private val saving = MutableStateFlow(false)
        private val saveFailed = MutableStateFlow(false)
        private val sessionOpen = MutableStateFlow(true)
        private var saveJob: Job? = null
        private var readJob: Job? = null
        private var readOwner: Any? = null
        private var readGeneration = 0L

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
                    plannedRoad = current.plannedRoad,
                    plannedRoadCleared = originalPlannedRoad != null && current.plannedRoad == null,
                    readGeneration = readGeneration,
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
            if (expected.readGeneration != readGeneration || !preparationRead.value.allowsEditing || !livePermission() ||
                !expected.allowsInteraction
            ) {
                return false
            }
            val current = draft.value
            if (current.step != expected.step || current.goalId != expected.goal?.id ||
                current.courseId != expected.course?.id || current.taskId != expected.taskId || current.plannedRoad != expected.plannedRoad
            ) {
                return false
            }
            if (current.step != PreparationStep.COURSE && current.step != PreparationStep.BRIEFING) return false
            if (catalog.goals.find { it.id == current.goalId } != expected.goal) return false
            val course = expected.course ?: return current.courseId == null
            return current.courseVersion == course.version && current.briefingVersion == course.briefingVersion &&
                catalog.courses.find { it.id == current.courseId && current.goalId in it.goalIds } == course
        }

        fun selectGoal(
            id: String,
            expectedReadGeneration: Long? = null,
        ) {
            if (expectedReadGeneration != null && expectedReadGeneration != readGeneration) return
            if (!canEdit() || draft.value.step != PreparationStep.GOAL || catalog.goals.none { it.id == id }) return
            if (draft.value.goalId != id) update(PreparationDraft(goalId = id))
        }

        fun selectCourse(
            id: String,
            expectedReadGeneration: Long? = null,
        ) {
            if (expectedReadGeneration != null && expectedReadGeneration != readGeneration) return
            if (!canEdit() || draft.value.step != PreparationStep.COURSE) return
            val current = draft.value
            val course = catalog.courses.find { it.id == id && current.goalId in it.goalIds } ?: return
            if (current.courseId == course.id && current.courseVersion == course.version &&
                current.briefingVersion == course.briefingVersion && course.tasks.any { it.id == current.taskId }
            ) {
                return
            }
            val expected = expectedCurrent
            val retainedPlan =
                expected?.plannedRoad?.takeIf {
                    expected.goalId == current.goalId && expected.courseId == id &&
                        expected.courseVersion == course.version && expected.briefingVersion == course.briefingVersion
                }
            update(
                current.copy(
                    courseId = id,
                    courseVersion = course.version,
                    briefingVersion = course.briefingVersion,
                    taskId = null,
                    plannedRoad = retainedPlan,
                ),
            )
        }

        fun selectTask(
            id: String,
            expectedReadGeneration: Long? = null,
        ) {
            if (expectedReadGeneration != null && expectedReadGeneration != readGeneration) return
            if (!canEdit() || draft.value.step != PreparationStep.BRIEFING) return
            val course = catalog.courses.find { it.id == draft.value.courseId } ?: return
            if (course.tasks.any { it.id == id }) update(draft.value.copy(taskId = id))
        }

        fun next(expectedReadGeneration: Long? = null) {
            if (expectedReadGeneration != null && expectedReadGeneration != readGeneration) return
            if (!canEdit()) return
            when (draft.value.step) {
                PreparationStep.GOAL -> if (draft.value.goalId != null) update(draft.value.copy(step = PreparationStep.COURSE))
                PreparationStep.COURSE -> if (draft.value.courseId != null) update(draft.value.copy(step = PreparationStep.BRIEFING))
                PreparationStep.BRIEFING -> save()
            }
        }

        fun back(expectedReadGeneration: Long? = null): Boolean {
            // A retired Back is consumed; it must not close the replacement preparation.
            if (expectedReadGeneration != null && expectedReadGeneration != readGeneration) return true
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

        fun retryPreparationRead(expectedReadGeneration: Long? = null) {
            if (expectedReadGeneration != null && expectedReadGeneration != readGeneration) return
            if (!livePermission() || saving.value) return
            if (saveFailed.value) {
                // Only this explicit reload discards the failed draft and its old compare-and-set source.
                cancelPreparationRead()
                expectedLoaded = false
                expectedCurrent = null
                originalPlannedRoad = null
                ownsDraft = false
                listOf(
                    "step",
                    "goal",
                    "course",
                    "courseVersion",
                    "briefingVersion",
                    "task",
                    "initialPreparationOutdated",
                    "preparationBaselineFields",
                ).forEach { savedState.remove<Any?>(it) }
                draft.value = PreparationDraft()
                saveFailed.value = false
            } else if (preparationRead.value != PreparationReadState.FAILED) {
                return
            }
            startPreparationRead(reload = true)
        }

        private fun startPreparationRead(reload: Boolean = false) {
            if (!visible || !sessionOpen.value) return
            cancelPreparationRead()
            val owner = Any()
            readOwner = owner
            readGeneration++
            preparationRead.value = PreparationReadState.LOADING
            val job =
                viewModelScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        val selection = repository.observeSelection().first()
                        coroutineContext.ensureActive()
                        val resolved = catalog.resolve(selection)
                        coroutineContext.ensureActive()
                        if (readOwner !== owner || !visible || !sessionOpen.value) return@launch
                        val practice = (resolved as? Preparation.Ready)?.practice
                        val priorBinding = savedState.get<Any?>("preparationBaselineFields")
                        val sameBaseline = priorBinding is ArrayList<*> && priorBinding == selection.savedFields()
                        if (!reload && ownsDraft &&
                            ((priorBinding != null && !sameBaseline) || (priorBinding == null && selection?.plannedRoad != null))
                        ) {
                            preparationRead.value = PreparationReadState.FAILED
                            return@launch
                        }
                        val currentDraft = draft.value
                        val restoreOwned = ownsDraft && !reload && (sameBaseline || priorBinding == null)
                        val initial =
                            if (restoreOwned) {
                                val sameContext =
                                    currentDraft.goalId == selection?.goalId && currentDraft.courseId == selection?.courseId &&
                                        currentDraft.courseVersion == selection?.courseVersion &&
                                        currentDraft.briefingVersion == selection?.briefingVersion
                                currentDraft.copy(plannedRoad = if (sameContext) selection?.plannedRoad else null)
                            } else if (practice == null) {
                                PreparationDraft()
                            } else {
                                PreparationDraft(
                                    goalId = practice.goal.id,
                                    courseId = practice.course.id,
                                    courseVersion = practice.course.version,
                                    briefingVersion = practice.course.briefingVersion,
                                    taskId = practice.task.id,
                                    plannedRoad = practice.plannedRoad,
                                )
                            }
                        expectedCurrent = selection
                        expectedLoaded = true
                        originalPlannedRoad = selection?.plannedRoad
                        savedState["preparationBaselineFields"] = selection.savedFields()
                        val outdated =
                            if (restoreOwned) {
                                savedState.get<Boolean>("initialPreparationOutdated") == true
                            } else {
                                resolved ==
                                    Preparation.Outdated
                            }
                        update(initial, initialOutdated = outdated)
                        preparationRead.value = if (outdated) PreparationReadState.OUTDATED else PreparationReadState.READY
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        coroutineContext.ensureActive()
                        if (readOwner === owner && visible && sessionOpen.value) {
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
            if (!expectedLoaded) return
            val expected = expectedCurrent
            if (catalog.resolve(selection) !is Preparation.Ready) return
            saving.value = true
            saveFailed.value = false
            saveJob =
                viewModelScope.launch {
                    try {
                        val job = coroutineContext[Job]!!
                        val committed =
                            repository.replaceSelection(expected, selection) {
                                job.isActive && expectedLoaded && expectedCurrent == expected && preparationRead.value.allowsEditing &&
                                    livePermission() &&
                                    draft.value == snapshot
                            }
                        coroutineContext.ensureActive()
                        if (committed && sessionOpen.value) {
                            savedEvents.send(Unit)
                        } else if (sessionOpen.value) {
                            saveFailed.value = true
                        }
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
