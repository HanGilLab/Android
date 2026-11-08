package io.github.hangillab.hangil

import android.app.KeyguardManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.database.OwnedPracticeTestStore
import io.github.hangillab.hangil.core.designsystem.HanGilTheme
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PlannedPracticeRoad
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionDeletionResult
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionReviewRoad
import io.github.hangillab.hangil.core.domain.SessionReviewRoadRepository
import io.github.hangillab.hangil.core.domain.SessionReviewRoadWriteResult
import io.github.hangillab.hangil.core.domain.SessionReviewSample
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.feature.course.NextPracticeRoute
import io.github.hangillab.hangil.feature.course.NextPracticeViewModel
import io.github.hangillab.hangil.feature.home.SessionHistoryRoute
import io.github.hangillab.hangil.feature.home.SessionHistoryViewModel
import io.github.hangillab.hangil.feature.home.SessionReviewRoadRoute
import io.github.hangillab.hangil.feature.home.SessionReviewRoadViewModel
import io.github.hangillab.hangil.feature.map.SessionReviewRoadMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.LineString
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import io.github.hangillab.hangil.feature.home.R as HomeR

/** Explicit opt-in: real Compose and nonce-owned Room, entirely controlled vehicle inputs, no SDK clients. */
@RunWith(AndroidJUnit4::class)
class HistoryNextPracticeFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val hostLeft = AtomicBoolean(false)
    private var expectedFontScale = Float.NaN
    private var expectedNight: Boolean? = null
    private val captures by lazy {
        File(instrument.targetContext.cacheDir, "history-next-${UUID.randomUUID()}").also { check(it.mkdirs()) }
    }

    @Test fun selectedOlderSourceManualTaskCancelLocksAndSavePreserveStoredSessions() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("manualHistoryNextControlled") == "true")
        val expectedScale = checkNotNull(args.getString("expectedHistoryNextFontScale")).toFloat()
        val expectedDark = checkNotNull(args.getString("expectedHistoryNextDark")).toBooleanStrict()
        check(expectedScale == 1f || expectedScale == 1.3f)
        expectedFontScale = expectedScale
        expectedNight = expectedDark
        assertHost()
        val hostLifecycle = compose.activity.lifecycle
        val configuration = compose.activity.resources.configuration
        assertTrue(abs(configuration.fontScale - expectedScale) < 0.01f)
        assertEquals(expectedDark, configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
        val observer =
            LifecycleEventObserver { _, _ ->
                if (compose.activity.lifecycle.currentState != Lifecycle.State.RESUMED) hostLeft.set(true)
            }
        val focusObserver =
            android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
                if (!focused) hostLeft.set(true)
            }
        instrument.runOnMainSync {
            compose.activity.lifecycle.addObserver(observer)
            compose.activity.window.decorView.viewTreeObserver
                .addOnWindowFocusChangeListener(focusObserver)
        }
        val store = OwnedPracticeTestStore.create(instrument.targetContext)
        val trackedSessions = TrackedSessions(store.sessions)
        val referenceEnabled = mutableStateOf(false)
        val targetReadFailure = MutableStateFlow(false)
        val targetRepository =
            object : SessionReviewRoadRepository by store.reviewRoads {
                override fun observe(sessionId: String): Flow<SessionReviewRoad?> =
                    combine(store.reviewRoads.observe(sessionId), targetReadFailure) { road, failed ->
                        check(!failed) { "Controlled target read failure" }
                        road
                    }
            }
        val referenceModels = mutableMapOf<String, SessionReviewRoadViewModel>()
        val referenceCreations = AtomicInteger()
        val referenceQueries = AtomicInteger()
        val referenceReaders = AtomicInteger()
        val referenceJobs = ConcurrentHashMap.newKeySet<Job>()
        val referenceRepository =
            object : SessionReviewRoadRepository by store.reviewRoads {
                override fun observe(sessionId: String): Flow<SessionReviewRoad?> =
                    flow {
                        referenceQueries.incrementAndGet()
                        referenceReaders.incrementAndGet()
                        referenceJobs += checkNotNull(currentCoroutineContext()[Job])
                        try {
                            emitAll(store.reviewRoads.observe(sessionId))
                        } finally {
                            referenceReaders.decrementAndGet()
                        }
                    }

                override suspend fun setSample(
                    expectedSource: PracticeSession,
                    expectedCurrent: SessionReviewRoad,
                    sample: SessionReviewSample?,
                    canCommit: () -> Boolean,
                ): SessionReviewRoadWriteResult = error("A next practice reference cannot change a review sample")

                override suspend fun save(
                    expectedSource: PracticeSession,
                    road: CourseRoadSnapshot,
                    expectedCurrent: SessionReviewRoad?,
                    memoText: String,
                    discardMemo: Boolean,
                    discardSample: Boolean,
                    canCommit: () -> Boolean,
                ): SessionReviewRoadWriteResult = throw AssertionError("A reference road must never be saved by this UI")

                override suspend fun clear(
                    expectedSource: PracticeSession,
                    expectedCurrent: SessionReviewRoad,
                    discardMemo: Boolean,
                    discardSample: Boolean,
                    canCommit: () -> Boolean,
                ): SessionReviewRoadWriteResult = throw AssertionError("A reference road must never be cleared by this UI")
            }
        val models = Models()
        val nextModels = mutableListOf<Models>()
        val nextOwner = FinishSourceReviewBoundaryTest.Owner(models.viewModelStore)
        val referenceGates = mutableListOf<() -> Boolean>()
        val vehicleState = MutableStateFlow(VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK)))
        val uxState = MutableStateFlow(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))
        val vehicle =
            object : VehicleGateway {
                override val state = vehicleState
            }
        val restrictions =
            object : DrivingRestrictionsProvider {
                override val restrictions = uxState
            }
        val memory = HistoryNextPracticeNavigationViewModel()
        val opened = mutableStateOf<Opened?>(null)
        val history =
            models.keep(
                "history",
                SessionHistoryViewModel(
                    vehicle,
                    restrictions,
                    trackedSessions,
                    store.deletions,
                    store.observations,
                    store.memos,
                    store.recordingRuns,
                    store.courseCompletions,
                    store.historyMemoWriter,
                ),
            )
        var primary: Throwable? = null
        var home: FinishSourceReviewBoundaryTest.Driver? = null
        try {
            val course = store.catalog.courses.first { it.tasks.size >= 2 && it.goalIds.isNotEmpty() && it.roadSnapshots.isNotEmpty() }
            val goal = store.catalog.goals.first { it.id in course.goalIds }
            val previousPractice = PreparedPractice(goal, course, course.tasks.first())
            val targetTask = course.tasks.last()

            fun selection(taskId: String) = PracticeSelection(goal.id, course.id, course.version, course.briefingVersion, taskId)
            val older = runBlocking { seed(store, previousPractice, 1000) }
            runBlocking { seed(store, previousPractice.copy(task = targetTask), 3000) }
            var savedRoad =
                runBlocking {
                    val result =
                        store.reviewRoads.save(
                            older,
                            course.roadSnapshots.first(),
                            null,
                            memoText = "  지도에서 고른 구간 🧭\n표지를 다시 살펴보기  ",
                        ) { true }
                    check(result is SessionReviewRoadWriteResult.Saved)
                    result.review
                }
            assertEquals(older, savedRoad.source)
            assertEquals(course.roadSnapshots.first(), savedRoad.road)
            val originalPreparation = selection(previousPractice.task.id)
            runBlocking { store.preparation.saveSelection(originalPreparation) { true } }
            val sessionsBefore = runBlocking { store.sessions.observeSessions().first() }
            var reviewRoadsBefore = reviewRoadSnapshot(store, sessionsBefore)
            val sequencesBefore = store.sequenceSnapshot()
            instrument.runOnMainSync {
                nextOwner.move(Lifecycle.State.RESUMED)
                compose.activity.setContent {
                    HanGilTheme {
                        val current by opened
                        if (current == null) {
                            SessionHistoryRoute(
                                onClose = {},
                                viewModel = history,
                                onPrepareNext = { source ->
                                    val token = checkNotNull(memory.open(source))
                                    val state =
                                        SavedStateHandle(
                                            mapOf(
                                                "historyNextToken" to token,
                                                "nextPracticeSourceId" to source.sessionId,
                                            ),
                                        )
                                    val entryModels = Models().also { nextModels += it }
                                    val model =
                                        entryModels.keep(
                                            token,
                                            NextPracticeViewModel(
                                                vehicle,
                                                restrictions,
                                                trackedSessions,
                                                store.catalog,
                                                store.nextPractice,
                                                state,
                                                targetRepository,
                                            ),
                                        )
                                    opened.value = Opened(token, source, model, state, entryModels)
                                },
                            )
                        } else {
                            val entry = checkNotNull(current)
                            CompositionLocalProvider(LocalLifecycleOwner provides nextOwner) {
                                NextPracticeRoute(
                                    reviewRoad =
                                        if (!referenceEnabled.value) {
                                            null
                                        } else {
                                            { source, canKeepOpen ->
                                                if (referenceGates.lastOrNull() !== canKeepOpen) referenceGates += canKeepOpen
                                                val reference =
                                                    referenceModels.getOrPut(entry.token) {
                                                        referenceCreations.incrementAndGet()
                                                        models.keep(
                                                            "reference-${entry.token}",
                                                            SessionReviewRoadViewModel(
                                                                vehicle,
                                                                restrictions,
                                                                trackedSessions,
                                                                referenceRepository,
                                                                store.catalog,
                                                            ),
                                                        )
                                                    }
                                                SessionReviewRoadRoute(
                                                    expectedSource = source,
                                                    canKeepOpen = canKeepOpen,
                                                    readOnly = true,
                                                    viewModel = reference,
                                                    roadMap = { snapshot, roads, selected, canPresent, changes ->
                                                        SessionReviewRoadMap(snapshot, roads, selected, canPresent, changes)
                                                    },
                                                )
                                            }
                                        },
                                    onClose = {
                                        memory.clear(entry.token)
                                        opened.value = null
                                    },
                                    onPrepareNormally = { error("No fallback is expected for a valid selected fixture") },
                                    viewModel = entry.model,
                                    expectedHistorySource = entry.source,
                                    historyEntryRequired = true,
                                    isHistoryEntryCurrent = {
                                        opened.value === entry &&
                                            memory.source(entry.token, entry.source.sessionId) == entry.source &&
                                            // Room validation may call this under a VM lock; avoid the test rule's main-thread synchronization.
                                            hostLifecycle.currentState == Lifecycle.State.RESUMED
                                    },
                                )
                            }
                        }
                    }
                }
            }
            waitTag("history_prepare_next")
            val staleNewest = clickAction("history_prepare_next")
            click("history_older")
            invoke(staleNewest)
            assertNull(opened.value)
            click("history_prepare_next")
            waitTag("next_practice_ready")
            assertEquals(older, opened.value?.source)
            assertHost()
            assertMissing("next_practice_review_road")
            assertEquals(0, referenceCreations.get())
            assertEquals(0, referenceQueries.get())
            instrument.runOnMainSync { referenceEnabled.value = true }
            waitTag("next_practice_review_road")
            assertEquals(0, referenceCreations.get())
            assertEquals(0, referenceQueries.get())
            assertMissing("review_road_content")
            click("next_practice_review_road")
            assertReferenceRoad(savedRoad)
            var firstRoadMap = awaitReferenceMap(savedRoad.road)
            captureReferenceMap()
            assertEquals(reviewRoadsBefore, reviewRoadSnapshot(store, sessionsBefore))
            // Only this synthetic owner's lifecycle changes; the actual Activity stays resumed.
            val preStopGate = referenceGates.last()
            instrument.runOnMainSync {
                assertTrue(preStopGate())
                nextOwner.move(Lifecycle.State.STARTED)
                nextOwner.move(Lifecycle.State.CREATED)
            }
            compose.waitForIdle()
            compose.waitUntil(20000) { referenceReaders.get() == 0 }
            instrument.runOnMainSync {
                assertFalse(preStopGate())
                assertFalse(firstRoadMap.isAttachedToWindow)
                nextOwner.move(Lifecycle.State.RESUMED)
            }
            waitTag("next_practice_ready")
            assertMissing("review_road_content")
            instrument.runOnMainSync { assertFalse(preStopGate()) }
            compose.onNodeWithTag("next_practice_save").performScrollTo().assertIsNotEnabled()
            click("next_practice_review_road")
            assertReferenceRoad(savedRoad)
            val resumedRoadMap = awaitReferenceMap(savedRoad.road)
            assertTrue(resumedRoadMap !== firstRoadMap)
            firstRoadMap = resumedRoadMap
            for (task in course.tasks) {
                val tag = "next_task_${task.id}"
                val expectedRecords = sessionsBefore.count { it.selection.taskId == task.id && it.taskTitle == task.title }
                compose.onNodeWithTag(tag).performScrollTo().assertTextContains("남긴 마무리 기록 ${expectedRecords}개", substring = true)
                assertTextFits(tag)
            }
            compose.onNodeWithTag("next_practice_save").performScrollTo().assertIsNotEnabled()
            click("next_practice_source_details")
            assertHost()
            compose.onNodeWithTag("next_practice_previous_task").performScrollTo().assertTextContains(older.taskTitle, substring = true)
            assertTextFits("next_practice_previous_task")
            click("next_task_${targetTask.id}")
            compose.onNodeWithTag("next_task_${targetTask.id}").assertIsSelected()
            assertMissing("review_road_content")
            compose.waitUntil(20000) { referenceReaders.get() == 0 }
            instrument.runOnMainSync { assertFalse(firstRoadMap.isAttachedToWindow) }
            click("next_practice_review_road")
            assertReferenceRoad(savedRoad)
            assertTrue(awaitReferenceMap(savedRoad.road) !== firstRoadMap)
            compose.onNodeWithTag("next_task_${targetTask.id}").assertIsSelected()
            waitTag("next_practice_target_road_use")
            val staleUseTarget = clickAction("next_practice_target_road_use")
            click("next_practice_target_road_use")
            waitTag("next_practice_target_road_unlink")
            assertTextFits("next_practice_target_road")
            val beforeTargetStop = clickAction("next_practice_save")
            instrument.runOnMainSync {
                nextOwner.move(Lifecycle.State.STARTED)
                nextOwner.move(Lifecycle.State.CREATED)
            }
            compose.waitForIdle()
            instrument.runOnMainSync { nextOwner.move(Lifecycle.State.RESUMED) }
            waitTag("next_practice_target_road_changed")
            compose.onNodeWithTag("next_practice_save").performScrollTo().assertIsNotEnabled()
            invoke(beforeTargetStop)
            assertEquals(originalPreparation, runBlocking { store.preparation.observeSelection().first() })
            click("next_practice_target_road_use")
            waitTag("next_practice_target_road_unlink")
            val staleUnlink = clickAction("next_practice_target_road_unlink")
            click("next_practice_target_road_unlink")
            waitTag("next_practice_target_road_use")
            assertMissing("next_practice_target_road")
            assertEquals(originalPreparation, runBlocking { store.preparation.observeSelection().first() })
            click("next_practice_target_road_use")
            waitTag("next_practice_target_road_unlink")
            invoke(staleUnlink)
            waitTag("next_practice_target_road")
            click("next_task_${previousPractice.task.id}")
            waitTag("next_practice_target_road")
            click("next_task_${targetTask.id}")
            waitTag("next_practice_target_road")
            val beforeRecreationSave = clickAction("next_practice_save")
            val priorEntry = checkNotNull(opened.value)
            instrument.runOnMainSync {
                assertEquals(true, priorEntry.savedState.get<Boolean>("nextPracticeTargetChoicePending"))
                val copied =
                    priorEntry.savedState.keys().associateWith { stateKey ->
                        when (val value = priorEntry.savedState.get<Any?>(stateKey)) {
                            is ArrayList<*> -> ArrayList(value)
                            else -> value
                        }
                    }
                val restoredState = SavedStateHandle(copied)
                val restoredModels = Models().also { nextModels += it }
                val restored =
                    restoredModels.keep(
                        priorEntry.token,
                        NextPracticeViewModel(
                            vehicle,
                            restrictions,
                            trackedSessions,
                            store.catalog,
                            store.nextPractice,
                            restoredState,
                            targetRepository,
                        ),
                    )
                opened.value = Opened(priorEntry.token, priorEntry.source, restored, restoredState, restoredModels)
                priorEntry.models.viewModelStore.clear()
            }
            runBlocking { withTimeout(20000) { priorEntry.models.awaitStopped() } }
            waitTag("next_practice_target_road_changed")
            compose.onNodeWithTag("next_task_${targetTask.id}").performScrollTo().assertIsSelected()
            assertMissing("next_practice_target_road")
            compose.onNodeWithTag("next_practice_save").performScrollTo().assertIsNotEnabled()
            assertTextFits("next_practice_target_road_changed")
            invoke(beforeRecreationSave)
            assertEquals(originalPreparation, runBlocking { store.preparation.observeSelection().first() })
            click("next_practice_target_road_use")
            waitTag("next_practice_target_road_unlink")
            compose.onNodeWithTag("next_practice_save").performScrollTo().assertIsEnabled()
            // Controlled ViewModel/SavedState recreation, not an OS process kill.
            val staleSave = clickAction("next_practice_save")
            click("next_practice_close")
            waitTag("history_prepare_next")
            assertEquals(originalPreparation, runBlocking { store.preparation.observeSelection().first() })
            assertEquals(reviewRoadsBefore, reviewRoadSnapshot(store, sessionsBefore))
            invoke(staleSave)
            assertEquals(originalPreparation, runBlocking { store.preparation.observeSelection().first() })
            click("history_prepare_next")
            waitTag("next_practice_choose_first")
            assertMissing("review_road_content")
            click("next_task_${targetTask.id}")
            click("next_practice_review_road")
            assertReferenceRoad(savedRoad)
            click("next_practice_target_road_use")
            waitTag("next_practice_target_road_unlink")
            val preUxSave = clickAction("next_practice_save")
            instrument.runOnMainSync { uxState.value = uxState.value.copy(uxRestrictionState = UxRestrictionState.RESTRICTED) }
            waitTag("next_practice_locked")
            assertMissing("review_road_content")
            invoke(preUxSave)
            instrument.runOnMainSync { uxState.value = uxState.value.copy(uxRestrictionState = UxRestrictionState.UNRESTRICTED) }
            waitTag("next_practice_ready")
            assertMissing("review_road_content")
            invoke(preUxSave)
            assertEquals(originalPreparation, runBlocking { store.preparation.observeSelection().first() })
            compose.onNodeWithTag("next_practice_save").performScrollTo().assertIsNotEnabled()
            click("next_practice_target_road_use")
            click("next_practice_review_road")
            assertReferenceRoad(savedRoad)
            val preDriveSave = clickAction("next_practice_save")
            instrument.runOnMainSync { vehicleState.value = vehicleState.value.copy(gear = VehicleReading.Available(GearPosition.DRIVE)) }
            waitTag("next_practice_locked")
            assertMissing("review_road_content")
            invoke(preDriveSave)
            instrument.runOnMainSync { vehicleState.value = vehicleState.value.copy(gear = VehicleReading.Available(GearPosition.PARK)) }
            waitTag("next_practice_ready")
            assertMissing("review_road_content")
            invoke(preDriveSave)
            assertEquals(originalPreparation, runBlocking { store.preparation.observeSelection().first() })
            compose.onNodeWithTag("next_practice_save").performScrollTo().assertIsNotEnabled()
            click("next_practice_target_road_use")
            val beforeReadFailure = clickAction("next_practice_save")
            instrument.runOnMainSync { targetReadFailure.value = true }
            waitTag("next_practice_target_road_failed")
            compose.onNodeWithTag("next_practice_save").performScrollTo().assertIsNotEnabled()
            invoke(beforeReadFailure)
            assertEquals(originalPreparation, runBlocking { store.preparation.observeSelection().first() })
            instrument.runOnMainSync { targetReadFailure.value = false }
            click("next_practice_target_road_retry")
            waitTag("next_practice_target_road_use")
            compose.onNodeWithTag("next_practice_save").performScrollTo().assertIsNotEnabled()
            click("next_practice_target_road_use")
            val beforeReviewChange = clickAction("next_practice_save")
            savedRoad =
                runBlocking {
                    val result =
                        store.reviewRoads.save(
                            older,
                            savedRoad.road,
                            savedRoad,
                            memoText = savedRoad.memoText + "\n다음 선택 전 원본 변경",
                        ) { true }
                    check(result is SessionReviewRoadWriteResult.Saved)
                    result.review
                }
            reviewRoadsBefore = reviewRoadSnapshot(store, sessionsBefore)
            waitTag("next_practice_target_road_changed")
            compose.onNodeWithTag("next_practice_save").performScrollTo().assertIsNotEnabled()
            invoke(beforeReviewChange)
            invoke(staleUseTarget)
            assertEquals(originalPreparation, runBlocking { store.preparation.observeSelection().first() })
            click("next_practice_target_road_use")
            waitTag("next_practice_target_road_unlink")
            val planned = PlannedPracticeRoad.from(savedRoad)
            assertEquals(savedRoad.source.sessionId, planned.sourceSessionId)
            assertEquals(savedRoad.version, planned.reviewVersion)
            assertEquals(savedRoad.road, planned.road)
            assertTextFits("next_practice_save")
            click("next_practice_save")
            waitTag("history_prepare_next")
            val preparedSelection = selection(targetTask.id).copy(plannedRoad = planned)
            assertEquals(preparedSelection, runBlocking { store.preparation.observeSelection().first() })
            assertEquals(sessionsBefore, runBlocking { store.sessions.observeSessions().first() })
            assertEquals(reviewRoadsBefore, reviewRoadSnapshot(store, sessionsBefore))
            assertEquals(sequencesBefore, store.sequenceSnapshot())
            assertEquals(0, store.foreignKeyViolationCount())
            val prepared = (store.catalog.resolve(preparedSelection) as Preparation.Ready).practice
            assertEquals(planned, prepared.plannedRoad)
            // Reuse the existing SDK-free production Home driver, preserving this same owned Room.
            instrument.runOnMainSync {
                opened.value = null
                compose.activity.setContent {}
                nextOwner.move(Lifecycle.State.DESTROYED)
                models.viewModelStore.clear()
                nextModels.forEach { it.viewModelStore.clear() }
            }
            compose.waitForIdle()
            runBlocking {
                withTimeout(20000) {
                    models.awaitStopped()
                    nextModels.forEach { it.awaitStopped() }
                    trackedSessions.awaitStopped()
                    referenceJobs.toList().forEach { it.join() }
                    check(referenceReaders.get() == 0)
                }
            }
            val homeUi =
                FinishSourceReviewBoundaryTest.Driver(
                    compose,
                    store.catalog,
                    store.preparation,
                    store.sessions,
                    store.memos,
                    store.observations,
                    store.recordingRuns,
                    store.courseCompletions,
                    store.deletions,
                    store.historyMemoWriter,
                    store.reviewRoads,
                )
            home = homeUi
            homeUi.mount()
            homeUi.waitTag("saved_planned_road")
            compose
                .onNodeWithTag("saved_planned_road", useUnmergedTree = true)
                .assertTextEquals(instrument.targetContext.getString(HomeR.string.planned_road_summary, planned.road.title))
            assertTextFits("saved_planned_road")
            homeUi.touch("session_start")
            homeUi.waitTag("session_active")
            homeUi.waitTag("session_planned_road")
            compose
                .onNodeWithTag("session_planned_road", useUnmergedTree = true)
                .assertTextEquals(instrument.targetContext.getString(HomeR.string.planned_road_summary, planned.road.title))
            assertTextFits("session_planned_road")
            val started =
                runBlocking {
                    store.sessions
                        .observeSessions()
                        .first { rows -> rows.any { it.isActive } }
                        .single { it.isActive }
                }
            assertEquals(preparedSelection, started.selection)
            assertEquals(planned, started.selection.plannedRoad)
            assertNull(runBlocking { store.reviewRoads.observe(started.sessionId).first() })
            homeUi.touch("session_finish")
            homeUi.waitTag("session_latest_planned_road")
            compose
                .onNodeWithTag("session_latest_planned_road", useUnmergedTree = true)
                .assertTextEquals(instrument.targetContext.getString(HomeR.string.planned_road_summary, planned.road.title))
            assertTextFits("session_latest_planned_road")
            val finished =
                runBlocking {
                    store.sessions
                        .observeSessions()
                        .first { rows ->
                            rows
                                .singleOrNull {
                                    it.sessionId ==
                                        started.sessionId
                                }?.isActive ==
                                false
                        }.single { it.sessionId == started.sessionId }
                }
            assertEquals(planned, finished.selection.plannedRoad)
            assertNull(runBlocking { store.reviewRoads.observe(finished.sessionId).first() })
            val deletion = runBlocking { store.deletions.prepare(older) { true } }
            check(deletion is SessionDeletionPreparation.Ready)
            assertEquals(savedRoad, deletion.target.reviewRoad)
            assertEquals(SessionDeletionResult.Deleted, runBlocking { store.deletions.delete(deletion.target) { true } })
            val afterDelete = runBlocking { store.sessions.observeSessions().first() }
            assertTrue(afterDelete.none { it.sessionId == older.sessionId })
            val copied = afterDelete.single { it.sessionId == finished.sessionId }
            assertEquals(planned, copied.selection.plannedRoad)
            assertEquals(
                planned.road.nodes,
                copied.selection.plannedRoad
                    ?.road
                    ?.nodes,
            )
            assertEquals(preparedSelection, runBlocking { store.preparation.observeSelection().first() })
            assertNull(runBlocking { store.reviewRoads.observe(copied.sessionId).first() })
            assertEquals(0, store.foreignKeyViolationCount())
            homeUi.waitTag("saved_planned_road")
            homeUi.waitTag("session_latest_planned_road")
            assertTextFits("session_latest_planned_road")
            homeUi.waitReceipt()
            homeUi.touch("session_finish_review")
            homeUi.waitTag("history_planned_road")
            compose
                .onNodeWithTag("history_planned_road", useUnmergedTree = true)
                .assertTextEquals(instrument.targetContext.getString(HomeR.string.planned_road_summary, planned.road.title))
            assertTextFits("history_planned_road")
            assertHost()
        } catch (failure: Throwable) {
            primary = failure
            throw failure
        } finally {
            fun cleanup(block: () -> Unit) {
                try {
                    block()
                } catch (failure: Throwable) {
                    if (primary == null) primary = failure else primary?.addSuppressed(failure)
                }
            }
            cleanup { home?.dispose() }
            cleanup {
                instrument.runOnMainSync {
                    compose.activity.lifecycle.removeObserver(observer)
                    compose.activity.window.decorView.viewTreeObserver
                        .removeOnWindowFocusChangeListener(focusObserver)
                    opened.value = null
                    compose.activity.setContent {}
                    if (nextOwner.lifecycle.currentState != Lifecycle.State.INITIALIZED) nextOwner.move(Lifecycle.State.DESTROYED)
                    models.viewModelStore.clear()
                    nextModels.forEach { it.viewModelStore.clear() }
                }
            }
            cleanup { compose.waitForIdle() }
            var jobsStopped = false
            cleanup {
                runBlocking {
                    withTimeout(20000) {
                        models.awaitStopped()
                        nextModels.forEach { it.awaitStopped() }
                        trackedSessions.awaitStopped()
                        referenceJobs.toList().forEach { it.join() }
                        check(referenceReaders.get() == 0 && referenceJobs.all { it.isCompleted })
                        check(home == null || home?.isStopped == true) { "Home fixture must stop before its owned Room closes" }
                    }
                }
                jobsStopped = true
            }
            if (jobsStopped) cleanup { store.close() }
            // If joining fails, retain this owned fixture instead of closing/deleting under a writer.
            primary?.let { throw it }
        }
    }

    private fun reviewRoadSnapshot(
        store: OwnedPracticeTestStore,
        sessions: List<PracticeSession>,
    ): Map<String, SessionReviewRoad?> =
        runBlocking {
            withTimeout(20000) { sessions.associate { it.sessionId to store.reviewRoads.observe(it.sessionId).first() } }
        }

    private fun assertMissing(tag: String) {
        assertTrue(
            "$tag must not be composed",
            compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes(false).isEmpty(),
        )
    }

    private fun assertReferenceRoad(saved: SessionReviewRoad) {
        waitTag("review_road_stored_title")
        assertEquals("USER_SELECTION", saved.origin)
        compose.onNodeWithTag("review_road_stored_title", useUnmergedTree = true).assertTextEquals(saved.road.title)
        compose.onNodeWithTag("review_road_memo_text", useUnmergedTree = true).assertTextEquals(saved.memoText)
        compose
            .onNodeWithTag("review_road_source", useUnmergedTree = true)
            .assertTextEquals(compose.activity.getString(HomeR.string.review_road_reference_source))
        compose
            .onNodeWithTag("review_road_reference_scope", useUnmergedTree = true)
            .assertTextEquals(compose.activity.getString(HomeR.string.review_road_reference_scope))
        for (tag in listOf(
            "review_road_candidate_0",
            "review_road_candidate_1",
            "review_road_save",
            "review_road_clear",
            "review_road_cancel",
            "review_road_busy",
            "review_road_feedback",
            "review_road_memo_edit",
            "review_road_memo_textfield",
            "review_road_memo_save",
            "review_road_memo_cancel_edit",
            "review_road_memo_count",
            "review_road_memo_writing",
            "review_road_memo_edit_context",
        )) {
            assertMissing(tag)
        }
        assertTextFits("review_road_stored_title")
        assertTextFits("review_road_memo_text")
        assertTextFits("review_road_reference_scope")
    }

    private fun awaitReferenceMap(road: CourseRoadSnapshot): MapView =
        runBlocking {
            waitTag("session_review_road_map_view")
            compose.onNodeWithTag("session_review_road_map_view", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            withTimeout(90_000) {
                var accepted: MapView? = null
                while (accepted == null) {
                    val result = CompletableDeferred<MapView?>()
                    instrument.runOnMainSync {
                        fun views(view: View): List<MapView> =
                            (if (view is MapView) listOf(view) else emptyList()) +
                                (if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
                        val view = views(compose.activity.window.decorView).singleOrNull { it.isAttachedToWindow }
                        if (view == null) {
                            result.complete(null)
                        } else {
                            view.getMapAsync { map ->
                                try {
                                    val style = map.style
                                    val source = style?.getSourceAs<GeoJsonSource>("hangil-course-preview-roads")
                                    val selected = style?.getLayerAs<LineLayer>("hangil-course-preview-road-selection")
                                    val points = style?.getSourceAs<GeoJsonSource>("hangil-course-preview-points")
                                    val features = source?.querySourceFeatures(null).orEmpty()
                                    val expectedFilter = Expression.eq(Expression.get("road_id"), Expression.literal(road.id))
                                    val expectedShape =
                                        road.nodes.map {
                                            map.projection.toScreenLocation(
                                                LatLng(it.latitude, it.longitude),
                                            )
                                        }

                                    // Native tiles may split or simplify the line. Check observed vertices
                                    // against the stored shape in screen pixels, not a node count or GPS accuracy.
                                    fun matches(feature: Feature): Boolean {
                                        val line = feature.geometry() as? LineString ?: return false
                                        if (feature.getStringProperty("road_id") != road.id ||
                                            feature.getStringProperty("osm_way_id") != road.osmWayId.toString() ||
                                            feature.getNumberProperty("osm_way_version").toDouble() != road.osmWayVersion.toDouble() ||
                                            feature.getStringProperty("road_source") != road.sourceCode
                                        ) {
                                            return false
                                        }
                                        return line.coordinates().size >= 2 &&
                                            line.coordinates().all { point ->
                                                val actual = map.projection.toScreenLocation(LatLng(point.latitude(), point.longitude()))
                                                expectedShape.zipWithNext().any { (start, end) ->
                                                    val dx = (end.x - start.x).toDouble()
                                                    val dy = (end.y - start.y).toDouble()
                                                    val length = dx * dx + dy * dy
                                                    val along =
                                                        if (length == 0.0) {
                                                            0.0
                                                        } else {
                                                            (((actual.x - start.x) * dx + (actual.y - start.y) * dy) / length).coerceIn(
                                                                0.0,
                                                                1.0,
                                                            )
                                                        }
                                                    hypot(actual.x - start.x - along * dx, actual.y - start.y - along * dy) <= 1.0
                                                }
                                            }
                                    }
                                    val rendered =
                                        map.queryRenderedFeatures(
                                            RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()),
                                            "hangil-course-preview-road-selection",
                                        )
                                    val valid =
                                        view.isAttachedToWindow && style?.isFullyLoaded == true && features.isNotEmpty() &&
                                            features.all(::matches) && rendered.isNotEmpty() && rendered.all(::matches) &&
                                            selected?.sourceId == "hangil-course-preview-roads" &&
                                            selected?.filter?.toArray()?.contentDeepEquals(expectedFilter.toArray()) == true &&
                                            points?.querySourceFeatures(null)?.isEmpty() == true
                                    result.complete(view.takeIf { valid })
                                } catch (_: Exception) {
                                    result.complete(null)
                                }
                            }
                        }
                    }
                    accepted = result.await()
                    if (accepted == null) delay(50)
                }
                accepted
            }
        }

    private fun captureReferenceMap() {
        compose.onNodeWithTag("session_review_road_map_view", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        assertHost()
        val node = compose.onNodeWithTag("session_review_road_map_view", useUnmergedTree = true).fetchSemanticsNode()
        val origin = node.positionOnScreen - node.positionInWindow
        val bounds = node.boundsInWindow
        val left = (bounds.left + origin.x).roundToInt()
        val top = (bounds.top + origin.y).roundToInt()
        val width = bounds.width.roundToInt()
        val height = bounds.height.roundToInt()
        val bitmap = checkNotNull(instrument.uiAutomation.takeScreenshot())
        try {
            check(left >= 0 && top >= 0 && width > 0 && height > 0 && left + width <= bitmap.width && top + height <= bitmap.height)
            val crop = Bitmap.createBitmap(bitmap, left, top, width, height)
            try {
                File(captures, "reference-road-map.png").outputStream().use { check(crop.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                if (crop !== bitmap) crop.recycle()
            }
        } finally {
            bitmap.recycle()
        }
        assertHost()
    }

    private suspend fun seed(
        store: OwnedPracticeTestStore,
        practice: PreparedPractice,
        started: Long,
    ): PracticeSession {
        val selection =
            PracticeSelection(
                practice.goal.id,
                practice.course.id,
                practice.course.version,
                practice.course.briefingVersion,
                practice.task.id,
            )
        store.preparation.saveSelection(selection) { true }
        val source = (store.sessions.start(practice, started) { true } as SessionStartResult.Started).session
        return (store.sessions.finish(source.sessionId, started + 500) { true } as SessionFinishResult.Finished).session
    }

    private fun assertHost() {
        assertFalse("Actual host focus/lifecycle left the verified foreground", hostLeft.get())
        assertFalse(instrument.targetContext.getSystemService(KeyguardManager::class.java).isKeyguardLocked)
        compose.waitUntil(20000) { compose.activity.lifecycle.currentState == Lifecycle.State.RESUMED && compose.activity.hasWindowFocus() }
        assertTrue(compose.activity.display?.isValid == true)
        assertFalse(hostLeft.get())
        val actual = compose.activity.resources.configuration
        assertTrue(abs(actual.fontScale - expectedFontScale) < 0.01f)
        assertEquals(expectedNight, actual.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES)
    }

    private fun waitTag(tag: String) {
        compose.waitUntil(20000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag(tag),
                    useUnmergedTree = true,
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun click(tag: String) {
        assertHost()
        waitTag(tag)
        compose
            .onNodeWithTag(tag)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
    }

    private fun clickAction(tag: String): (() -> Boolean)? {
        assertHost()
        return compose
            .onNodeWithTag(tag)
            .performScrollTo()
            .fetchSemanticsNode()
            .config[SemanticsActions.OnClick]
            .action
    }

    private fun invoke(action: (() -> Boolean)?) {
        assertHost()
        instrument.runOnMainSync { checkNotNull(action).invoke() }
        compose.waitForIdle()
    }

    private fun assertTextFits(tag: String) {
        assertHost()
        val subject = compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        // Only this controlled fixture is rendered; keep the actual window image in target-private cache.
        val mode = "${if (expectedNight == true) "dark" else "light"}-${expectedFontScale.toString().replace('.', '_')}"
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        File(captures, "history-next-$mode-$tag.png").outputStream().use {
            check(image.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        assertHost()
        val subjectBounds = subject.getUnclippedBoundsInRoot()
        val viewport = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue(
            "$tag must fit the actual viewport: subject=$subjectBounds, viewport=$viewport",
            subjectBounds.left >= viewport.left && subjectBounds.right <= viewport.right &&
                subjectBounds.top >= viewport.top && subjectBounds.bottom <= viewport.bottom,
        )
        val textNodes = mutableListOf<SemanticsNode>()

        fun read(current: SemanticsNode) {
            if (current.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null) textNodes += current
            current.children.forEach(::read)
        }
        read(subject.fetchSemanticsNode())
        assertTrue("Actual rendered text layout is required", textNodes.isNotEmpty())
        instrument.runOnMainSync {
            for (textNode in textNodes) {
                val layouts = mutableListOf<TextLayoutResult>()
                assertTrue(checkNotNull(textNode.config[SemanticsActions.GetTextLayoutResult].action).invoke(layouts))
                assertTrue("Actual rendered text results are required", layouts.isNotEmpty())
                val bounds = textNode.boundsInRoot
                assertTrue("$tag text node must have visible area: $bounds", bounds.width > 0f && bounds.height > 0f)
                for (layout in layouts) {
                    val geometry =
                        "$tag node=${bounds.width}x${bounds.height}, size=${layout.size}, " +
                            "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                            "flags=${layout.didOverflowWidth}/${layout.didOverflowHeight}, " +
                            "lines=${layout.lineCount}, scale=${layout.layoutInput.density.fontScale}, " +
                            "font=${layout.layoutInput.style.fontSize}"
                    assertTrue(geometry, layout.lineCount > 0)
                    assertEquals(geometry, expectedFontScale, layout.layoutInput.density.fontScale, 0.01f)
                    assertTrue(geometry, layout.layoutInput.style.fontSize.value >= 24f)
                    // String semantics may rebuild a wider MultiParagraph while retaining the
                    // original natural size. Compare actual lines and remeasure that node instead.
                    assertFalse(geometry, layout.didOverflowHeight)
                    assertEquals(geometry, layout.layoutInput.text.length, layout.getLineEnd(layout.lineCount - 1))
                    for (line in 0 until layout.lineCount) {
                        val lineBounds =
                            "$geometry, line=$line, L/R=${layout.getLineLeft(line)}/${layout.getLineRight(line)}, " +
                                "T/B=${layout.getLineTop(line)}/${layout.getLineBottom(line)}"
                        assertFalse(lineBounds, layout.isLineEllipsized(line))
                        assertTrue(lineBounds, layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= bounds.width + 1f)
                        assertTrue(lineBounds, layout.getLineTop(line) >= -1f && layout.getLineBottom(line) <= bounds.height + 1f)
                    }
                    val input = layout.layoutInput
                    val measured =
                        TextMeasurer(input.fontFamilyResolver, input.density, input.layoutDirection, cacheSize = 0).measure(
                            text = input.text,
                            style = input.style,
                            overflow = input.overflow,
                            softWrap = input.softWrap,
                            maxLines = input.maxLines,
                            placeholders = input.placeholders,
                            constraints = Constraints(maxWidth = bounds.width.roundToInt(), maxHeight = bounds.height.roundToInt()),
                        )
                    assertTrue(geometry, measured.lineCount > 0)
                    assertFalse("$geometry, remeasured=${measured.size}", measured.hasVisualOverflow)
                    assertEquals(geometry, input.text.length, measured.getLineEnd(measured.lineCount - 1))
                    for (line in 0 until measured.lineCount) assertFalse(geometry, measured.isLineEllipsized(line))
                }
            }
        }
    }

    /** Track the real Room collectors, including the history reader owned by Compose rather than the VM. */
    private class TrackedSessions(
        private val delegate: PracticeSessionRepository,
    ) : PracticeSessionRepository by delegate {
        private val jobs = ConcurrentHashMap.newKeySet<Job>()
        private val readers = AtomicInteger()

        override fun observeSessions(): Flow<List<PracticeSession>> =
            flow {
                val job = checkNotNull(currentCoroutineContext()[Job])
                jobs += job
                readers.incrementAndGet()
                try {
                    emitAll(delegate.observeSessions())
                } finally {
                    readers.decrementAndGet()
                }
            }

        suspend fun awaitStopped() {
            jobs.toList().forEach { it.join() }
            check(readers.get() == 0 && jobs.all { it.isCompleted }) {
                "Owned Room readers must finish before the fixture can close"
            }
        }
    }

    private class Models : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
        private val jobs = mutableSetOf<Job>()

        suspend fun awaitStopped() {
            jobs.toList().forEach { it.join() }
        }

        fun <T : ViewModel> keep(
            key: String,
            model: T,
        ): T =
            ViewModelProvider
                .create(
                    this,
                    object : ViewModelProvider.Factory {
                        override fun <V : ViewModel> create(modelClass: Class<V>): V = modelClass.cast(model)
                    },
                )[key, model.javaClass]
                .also { jobs += checkNotNull(it.viewModelScope.coroutineContext[Job]) }
    }

    private data class Opened(
        val token: String,
        val source: PracticeSession,
        val model: NextPracticeViewModel,
        val savedState: SavedStateHandle,
        val models: Models,
    )
}
