package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseMapPreview
import io.github.hangillab.hangil.core.domain.CoursePreviewPoint
import io.github.hangillab.hangil.core.domain.CoursePreviewSource
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeCourse
import io.github.hangillab.hangil.core.domain.PracticeGoal
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeTask
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {
    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `a map without a visible subscriber never reads preparation or accepts renderer callbacks`() =
        runTest {
            val fixture = Fixture()
            assertEquals(0, fixture.repository.calls)
            assertNull(fixture.model.onMapLoading(0))
            assertFalse(fixture.model.retryPreparation())
            fixture.model.retry()
            runCurrent()
            assertEquals(0, fixture.repository.calls)
            assertEquals(MapPreparationState.Loading, fixture.model.uiState.value.preparation)
        }

    @Test fun `loading preparation never falls back to a ready generic map`() =
        runTest {
            val fixture = Fixture()
            val delayed = CompletableDeferred<PracticeSelection?>()
            fixture.repository.source = { flow { emit(delayed.await()) } }
            subscribe(fixture)
            assertEquals(MapPreparationState.Loading, fixture.model.uiState.value.preparation)
            assertNull(fixture.model.onMapLoading(fixture.model.uiState.value.attempt))
            assertEquals(1, fixture.repository.active)
        }

    @Test fun `empty outdated and a prepared course without map data are distinct nonrendering states`() =
        runTest {
            val fixture = Fixture()
            fixture.repository.values.value = null
            subscribe(fixture)
            assertEquals(MapPreparationState.Empty, fixture.model.uiState.value.preparation)
            assertNull(fixture.model.onMapLoading(fixture.model.uiState.value.attempt))
            fixture.repository.values.value = selection("missing")
            runCurrent()
            assertEquals(MapPreparationState.Outdated, fixture.model.uiState.value.preparation)
            fixture.catalog.courses = listOf(course("a", map = null))
            fixture.repository.values.value = selection("a")
            runCurrent()
            val unavailable = fixture.model.uiState.value.preparation as MapPreparationState.Unavailable
            assertEquals("a", unavailable.prepared.course.id)
            assertNull(fixture.model.onMapLoading(fixture.model.uiState.value.attempt))
        }

    @Test fun `the selected prepared course task and fixed preview stay bound to one render attempt`() =
        runTest {
            val fixture = Fixture()
            fixture.repository.values.value = selection("b")
            subscribe(fixture)
            val ready = ready(fixture)
            assertEquals("b", ready.prepared.course.id)
            assertEquals("task-b", ready.prepared.task.id)
            assertEquals("preview-b", ready.preview.id)
            assertEquals(CoursePreviewSource.FIXED_OSM_BOUNDING_BOX_CENTERS, ready.preview.source)
            val attempt = fixture.model.uiState.value.attempt
            val token = checkNotNull(fixture.model.onMapLoading(attempt))
            fixture.model.onMapRendered(attempt, token)
            runCurrent()
            assertEquals(MapLoadState.READY, fixture.model.uiState.value.load)
            assertEquals(ready, fixture.model.uiState.value.preparation)
            assertEquals(0, fixture.repository.writes)
        }

    @Test fun `course briefing task and goal mismatches stay outdated rather than selecting another preview`() =
        runTest {
            val invalid =
                listOf(
                    selection("a").copy(courseVersion = 99),
                    selection("a").copy(briefingVersion = 99),
                    selection("a").copy(taskId = "task-b"),
                    selection("a").copy(goalId = "missing"),
                )
            for (selection in invalid) {
                val fixture = Fixture()
                fixture.repository.values.value = selection
                val job = subscribe(fixture)
                assertEquals(MapPreparationState.Outdated, fixture.model.uiState.value.preparation)
                assertNull(fixture.model.onMapLoading(fixture.model.uiState.value.attempt))
                job.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `an inconsistent catalog resolution fails explicitly rather than displaying the wrong prepared course`() =
        runTest {
            val fixture = Fixture()
            val wrong = fixture.catalog.courses.single { it.id == "b" }
            fixture.catalog.forced = Preparation.Ready(PreparedPractice(goal(), wrong, wrong.tasks.single()))
            subscribe(fixture)
            assertEquals(
                MapPreparationState.Failed(MapPreparationState.FailureReason.InvalidPreparation),
                fixture.model.uiState.value.preparation,
            )
            assertNull(fixture.model.onMapLoading(fixture.model.uiState.value.attempt))
        }

    @Test fun `factory read errors require an explicit parked retry and never become empty preparation`() =
        runTest {
            val fixture = Fixture()
            fixture.repository.factoryFailure = IOException("fixture read failure")
            subscribe(fixture)
            assertEquals(MapPreparationState.Failed(MapPreparationState.FailureReason.Read), fixture.model.uiState.value.preparation)
            fixture.repository.factoryFailure = null
            runCurrent()
            assertTrue(fixture.model.uiState.value.preparation is MapPreparationState.Failed)
            assertTrue(fixture.model.retryPreparation())
            runCurrent()
            assertEquals("a", ready(fixture).prepared.course.id)
        }

    @Test fun `a read failure after preparation invalidates the previous native renderer callbacks`() =
        runTest {
            val fixture = Fixture()
            val fail = CompletableDeferred<Unit>()
            fixture.repository.source = {
                flow {
                    emit(selection("a"))
                    fail.await()
                    throw IOException("fixture flow failure")
                }
            }
            subscribe(fixture)
            val previous = fixture.model.uiState.value.attempt
            val token = checkNotNull(fixture.model.onMapLoading(previous))
            fail.complete(Unit)
            runCurrent()
            fixture.model.onMapRendered(previous, token)
            fixture.model.onMapFailed(previous, token)
            runCurrent()
            assertEquals(MapPreparationState.Failed(MapPreparationState.FailureReason.Read), fixture.model.uiState.value.preparation)
            assertEquals(MapLoadState.LOADING, fixture.model.uiState.value.load)
            assertNull(fixture.model.onMapLoading(fixture.model.uiState.value.attempt))
        }

    @Test fun `normal preparation completion replaces ready data with a retryable failure`() =
        runTest {
            val fixture = Fixture()
            val complete = CompletableDeferred<Unit>()
            fixture.repository.source = {
                flow {
                    emit(selection("a"))
                    complete.await()
                }
            }
            subscribe(fixture)
            complete.complete(Unit)
            runCurrent()
            assertEquals(
                MapPreparationState.Failed(MapPreparationState.FailureReason.StreamCompleted),
                fixture.model.uiState.value.preparation,
            )
            assertEquals(0, fixture.repository.active)
            fixture.repository.source = { fixture.repository.values }
            assertTrue(fixture.model.retryPreparation())
            runCurrent()
            assertTrue(fixture.model.uiState.value.preparation is MapPreparationState.Ready)
        }

    @Test fun `an internally cancelled read fails while actual caller cancellation releases its query`() =
        runTest {
            val fixture = Fixture()
            fixture.repository.source = { flow { throw CancellationException("fixture internal cancellation") } }
            val job = subscribe(fixture)
            assertEquals(MapPreparationState.Failed(MapPreparationState.FailureReason.Read), fixture.model.uiState.value.preparation)
            assertTrue(job.isActive)
            fixture.repository.source = { fixture.repository.values }
            fixture.model.retryPreparation()
            runCurrent()
            assertEquals(1, fixture.repository.active)
            job.cancelAndJoin()
            runCurrent()
            assertEquals(0, fixture.repository.active)
        }

    @Test fun `closing and reentering the same prepared course owns a fresh reader attempt and no retained database query`() =
        runTest {
            val fixture = Fixture()
            val first = subscribe(fixture)
            val attempt = fixture.model.uiState.value.attempt
            val token = checkNotNull(fixture.model.onMapLoading(attempt))
            fixture.model.onMapRendered(attempt, token)
            runCurrent()
            first.cancelAndJoin()
            runCurrent()
            assertEquals(0, fixture.repository.active)
            assertNull(fixture.model.onMapLoading(attempt))
            fixture.model.onMapRendered(attempt, token)
            subscribe(fixture)
            assertNotEquals(attempt, fixture.model.uiState.value.attempt)
            fixture.model.onMapRendered(attempt, token)
            fixture.model.onMapFailed(attempt, token)
            runCurrent()
            assertEquals(MapLoadState.LOADING, fixture.model.uiState.value.load)
            assertEquals("a", ready(fixture).prepared.course.id)
            assertEquals(1, fixture.repository.maximumActive)
        }

    @Test fun `every unknown denied or restricted live state avoids preparation database subscription`() =
        runTest {
            val denied =
                listOf(
                    parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE)) to unrestricted(),
                    parked().copy(connection = ConnectionState.DISCONNECTED) to unrestricted(),
                    parked().copy(connection = ConnectionState.PERMISSION_DENIED) to unrestricted(),
                    parked().copy(gear = VehicleReading.Unknown) to unrestricted(),
                    parked().copy(gear = VehicleReading.PermissionDenied) to unrestricted(),
                    parked() to unrestricted().copy(uxRestrictionState = UxRestrictionState.UNKNOWN),
                    parked() to unrestricted().copy(uxRestrictionState = UxRestrictionState.RESTRICTED),
                )
            for ((vehicle, restrictions) in denied) {
                val fixture = Fixture()
                fixture.vehicle.state.value = vehicle
                fixture.restrictions.restrictions.value = restrictions
                val job = subscribe(fixture)
                assertFalse(fixture.model.uiState.value.allowsDetailedInteraction)
                assertEquals(MapPreparationState.Locked, fixture.model.uiState.value.preparation)
                assertEquals(0, fixture.repository.calls)
                assertFalse(fixture.model.retryPreparation())
                assertNull(fixture.model.onMapLoading(fixture.model.uiState.value.attempt))
                job.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun `a newer D state blocks renderer callbacks and both retries before UI recombines`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            val attempt = fixture.model.uiState.value.attempt
            val token = checkNotNull(fixture.model.onMapLoading(attempt))
            fixture.vehicle.state.value = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))
            fixture.model.retry()
            assertFalse(fixture.model.retryPreparation())
            fixture.model.onMapRendered(attempt, token)
            fixture.model.onMapFailed(attempt, token)
            assertNull(fixture.model.onMapLoading(attempt))
            runCurrent()
            assertEquals(MapPreparationState.Locked, fixture.model.uiState.value.preparation)
            assertFalse(fixture.model.uiState.value.allowsDetailedInteraction)
            assertEquals(MapLoadState.LOADING, fixture.model.uiState.value.load)
            assertEquals(0, fixture.repository.active)
        }

    @Test fun `parked recovery requires unrestricted UX and reopens only one preparation query`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            val previous = fixture.model.uiState.value.attempt
            val token = checkNotNull(fixture.model.onMapLoading(previous))
            fixture.restrictions.restrictions.value = unrestricted().copy(uxRestrictionState = UxRestrictionState.RESTRICTED)
            runCurrent()
            assertEquals(0, fixture.repository.active)
            fixture.vehicle.state.value = parked()
            runCurrent()
            assertEquals(MapPreparationState.Locked, fixture.model.uiState.value.preparation)
            fixture.restrictions.restrictions.value = unrestricted()
            runCurrent()
            assertTrue(fixture.model.uiState.value.preparation is MapPreparationState.Ready)
            assertNotEquals(previous, fixture.model.uiState.value.attempt)
            fixture.model.onMapRendered(previous, token)
            runCurrent()
            assertEquals(MapLoadState.LOADING, fixture.model.uiState.value.load)
            assertEquals(1, fixture.repository.active)
            assertEquals(1, fixture.repository.maximumActive)
        }

    @Test fun `failed rendering stays failed until explicit renderer retry with a new attempt`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            val previous = fixture.model.uiState.value.attempt
            val token = checkNotNull(fixture.model.onMapLoading(previous))
            fixture.model.onMapFailed(previous, token)
            fixture.model.onMapRendered(previous, token)
            runCurrent()
            assertEquals(MapLoadState.FAILED, fixture.model.uiState.value.load)
            fixture.model.retry()
            fixture.model.onMapRendered(previous, token)
            fixture.model.onMapFailed(previous, token)
            runCurrent()
            val attempt = fixture.model.uiState.value.attempt
            assertNotEquals(previous, attempt)
            assertEquals(MapLoadState.LOADING, fixture.model.uiState.value.load)
            val retried = checkNotNull(fixture.model.onMapLoading(attempt))
            fixture.model.onMapRendered(attempt, retried)
            runCurrent()
            assertEquals(MapLoadState.READY, fixture.model.uiState.value.load)
        }

    @Test fun `renderer callbacks require a token allocated to the current view rather than the unset zero token`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            val attempt = fixture.model.uiState.value.attempt
            fixture.model.onMapRendered(attempt, 0)
            fixture.model.onMapFailed(attempt, 0)
            runCurrent()
            assertEquals(MapLoadState.LOADING, fixture.model.uiState.value.load)
            val token = checkNotNull(fixture.model.onMapLoading(attempt))
            fixture.model.onMapRendered(attempt, token)
            runCurrent()
            assertEquals(MapLoadState.READY, fixture.model.uiState.value.load)
        }

    @Test fun `a recreated native view has a distinct token even when its prepared dataset is identical`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            val attempt = fixture.model.uiState.value.attempt
            val previous = checkNotNull(fixture.model.onMapLoading(attempt))
            val current = checkNotNull(fixture.model.onMapLoading(attempt))
            assertNotEquals(previous, current)
            fixture.model.onMapRendered(attempt, previous)
            runCurrent()
            assertEquals(MapLoadState.LOADING, fixture.model.uiState.value.load)
            fixture.model.onMapRendered(attempt, current)
            fixture.model.onMapFailed(attempt, previous)
            runCurrent()
            assertEquals(MapLoadState.READY, fixture.model.uiState.value.load)
        }

    @Test fun `a changed prepared course cannot be marked ready or failed by the previous course view`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            val previous = fixture.model.uiState.value.attempt
            val token = checkNotNull(fixture.model.onMapLoading(previous))
            fixture.repository.values.value = selection("b")
            runCurrent()
            val state = fixture.model.uiState.value
            assertEquals("b", ready(fixture).prepared.course.id)
            assertNotEquals(previous, state.attempt)
            fixture.model.onMapRendered(previous, token)
            fixture.model.onMapFailed(previous, token)
            runCurrent()
            assertEquals(MapLoadState.LOADING, fixture.model.uiState.value.load)
            val current = checkNotNull(fixture.model.onMapLoading(state.attempt))
            fixture.model.onMapRendered(state.attempt, current)
            runCurrent()
            assertEquals(MapLoadState.READY, fixture.model.uiState.value.load)
            assertEquals("b", ready(fixture).prepared.course.id)
        }

    @Test fun `receiving a different selection fences the old view before catalog resolution and UI recombination`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            val previous = fixture.model.uiState.value.attempt
            checkNotNull(fixture.model.onMapLoading(previous))
            var called = false
            var acceptedOldView: Long? = null
            fixture.catalog.onResolve = { selection ->
                if (selection?.courseId == "b") {
                    called = true
                    acceptedOldView = fixture.model.onMapLoading(previous)
                }
            }
            fixture.repository.values.value = selection("b")
            runCurrent()
            assertTrue(called)
            assertNull(acceptedOldView)
            assertEquals("b", ready(fixture).prepared.course.id)
            assertEquals(MapLoadState.LOADING, fixture.model.uiState.value.load)
        }

    @Test fun `retry during an old resolution cannot publish its course over the fresh pending query`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            val previous = fixture.model.uiState.value.attempt
            val oldToken = checkNotNull(fixture.model.onMapLoading(previous))
            val fresh = CompletableDeferred<Unit>()
            var retried = false
            fixture.catalog.onResolve = { selected ->
                if (selected?.courseId == "b" && !retried) {
                    retried = true
                    fixture.repository.source = {
                        flow {
                            fresh.await()
                            emitAll(MutableStateFlow<PracticeSelection?>(selection("a")))
                        }
                    }
                    assertTrue(fixture.model.retryPreparation())
                }
            }
            fixture.repository.values.value = selection("b")
            runCurrent()
            assertTrue(retried)
            assertEquals(MapPreparationState.Loading, fixture.model.uiState.value.preparation)
            fixture.model.onMapRendered(previous, oldToken)
            fixture.model.onMapFailed(previous, oldToken)
            runCurrent()
            assertEquals(MapPreparationState.Loading, fixture.model.uiState.value.preparation)
            fresh.complete(Unit)
            runCurrent()
            assertEquals("a", ready(fixture).prepared.course.id)
            assertNotEquals(previous, fixture.model.uiState.value.attempt)
            assertEquals(1, fixture.repository.active)
            assertEquals(1, fixture.repository.maximumActive)
        }

    @Test fun `losing valid preparation immediately fences old callbacks and never keeps the old map ready`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            val previous = fixture.model.uiState.value.attempt
            val token = checkNotNull(fixture.model.onMapLoading(previous))
            fixture.model.onMapRendered(previous, token)
            runCurrent()
            fixture.repository.values.value = null
            runCurrent()
            fixture.model.onMapRendered(previous, token)
            fixture.model.onMapFailed(previous, token)
            runCurrent()
            assertEquals(MapPreparationState.Empty, fixture.model.uiState.value.preparation)
            assertEquals(MapLoadState.LOADING, fixture.model.uiState.value.load)
            assertNull(fixture.model.onMapLoading(fixture.model.uiState.value.attempt))
        }

    @Test fun `different prepared courses sharing one preview still receive distinct render epochs`() =
        runTest {
            val fixture = Fixture()
            val a = fixture.catalog.courses.single { it.id == "a" }
            fixture.catalog.courses = listOf(a, course("b", map = a.mapPreview))
            subscribe(fixture)
            val previous = fixture.model.uiState.value.attempt
            val token = checkNotNull(fixture.model.onMapLoading(previous))
            fixture.repository.values.value = selection("b")
            runCurrent()
            assertNotEquals(previous, fixture.model.uiState.value.attempt)
            assertEquals("b", ready(fixture).prepared.course.id)
            fixture.model.onMapRendered(previous, token)
            runCurrent()
            assertEquals(MapLoadState.LOADING, fixture.model.uiState.value.load)
        }

    @Test fun `changed catalog contents on a new selection emission replace the renderer even for equal selection IDs`() =
        runTest {
            val fixture = Fixture()
            val changes = MutableSharedFlow<PracticeSelection?>(replay = 1)
            changes.tryEmit(selection("a"))
            fixture.repository.source = { changes }
            subscribe(fixture)
            val previous = fixture.model.uiState.value.attempt
            val token = checkNotNull(fixture.model.onMapLoading(previous))
            fixture.catalog.courses = listOf(course("a").copy(title = "Updated catalog title"), course("b"))
            changes.emit(selection("a"))
            runCurrent()
            assertEquals("Updated catalog title", ready(fixture).prepared.course.title)
            assertNotEquals(previous, fixture.model.uiState.value.attempt)
            fixture.model.onMapRendered(previous, token)
            runCurrent()
            assertEquals(MapLoadState.LOADING, fixture.model.uiState.value.load)
        }

    @Test fun `preparation retry cancels the previous delayed query and rejects its late result`() =
        runTest {
            val fixture = Fixture()
            val old = CompletableDeferred<PracticeSelection?>()
            fixture.repository.source = { flow { emit(old.await()) } }
            subscribe(fixture)
            fixture.repository.source = { fixture.repository.values }
            assertTrue(fixture.model.retryPreparation())
            runCurrent()
            assertEquals("a", ready(fixture).prepared.course.id)
            old.complete(selection("b"))
            runCurrent()
            assertEquals("a", ready(fixture).prepared.course.id)
            assertEquals(1, fixture.repository.active)
            assertEquals(1, fixture.repository.maximumActive)
        }

    @Test fun `denied first allocation exposes retry when a brief D P transition conflates before UI delivery`() =
        runTest {
            val fixture = Fixture()
            subscribe(fixture)
            val displayed = fixture.model.uiState.value
            val attempt = displayed.attempt
            assertEquals(MapLoadState.LOADING, displayed.load)
            fixture.vehicle.state.value = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))
            assertEquals(displayed, fixture.model.uiState.value)
            assertNull(fixture.model.onMapLoading(attempt))
            fixture.vehicle.state.value = parked()
            runCurrent()
            assertTrue(fixture.model.uiState.value.allowsDetailedInteraction)
            assertEquals(MapLoadState.FAILED, fixture.model.uiState.value.load)
            assertNull(fixture.model.onMapLoading(attempt))
            assertEquals(MapLoadState.FAILED, fixture.model.uiState.value.load)
            fixture.model.retry()
            runCurrent()
            val retried = fixture.model.uiState.value.attempt
            assertNotEquals(attempt, retried)
            val token = checkNotNull(fixture.model.onMapLoading(retried))
            fixture.model.onMapRendered(retried, token)
            runCurrent()
            assertTrue(fixture.model.canInteractWithMap(retried, token))
            assertEquals(MapLoadState.READY, fixture.model.uiState.value.load)
            assertEquals(1, fixture.repository.maximumActive)
            assertEquals(0, fixture.repository.writes)
        }

    @Test fun `native input requires a fully ready current renderer token and active source owner`() =
        runTest {
            val fixture = Fixture()
            assertFalse(fixture.model.canInteractWithMap(0, 0))
            val subscriber = subscribe(fixture)
            val attempt = fixture.model.uiState.value.attempt
            val first = checkNotNull(fixture.model.onMapLoading(attempt))
            assertFalse(fixture.model.canInteractWithMap(attempt, first))
            fixture.model.onMapRendered(attempt, first)
            runCurrent()
            assertTrue(fixture.model.canInteractWithMap(attempt, first))
            assertFalse(fixture.model.canInteractWithMap(attempt, 0))
            assertFalse(fixture.model.canInteractWithMap(attempt + 1, first))
            val recreated = checkNotNull(fixture.model.onMapLoading(attempt))
            assertFalse(fixture.model.canInteractWithMap(attempt, first))
            fixture.model.onMapRendered(attempt, recreated)
            runCurrent()
            assertTrue(fixture.model.canInteractWithMap(attempt, recreated))
            fixture.model.onMapFailed(attempt, recreated)
            assertFalse(fixture.model.canInteractWithMap(attempt, recreated))
            assertNull(fixture.model.onMapLoading(attempt))
            subscriber.cancelAndJoin()
            runCurrent()
            assertFalse(fixture.model.canInteractWithMap(attempt, recreated))
            assertEquals(0, fixture.repository.active)
            assertEquals(0, fixture.repository.writes)
        }

    @Test fun `native input rejects live denied values while a ready parked UI is still displayed`() =
        runTest {
            val denied =
                listOf(
                    parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE)) to unrestricted(),
                    parked().copy(connection = ConnectionState.DISCONNECTED) to unrestricted(),
                    parked().copy(connection = ConnectionState.PERMISSION_DENIED) to unrestricted(),
                    parked().copy(gear = VehicleReading.Unknown) to unrestricted(),
                    parked().copy(gear = VehicleReading.PermissionDenied) to unrestricted(),
                    parked() to unrestricted().copy(parkingState = ParkingState.UNKNOWN),
                    parked() to unrestricted().copy(uxRestrictionState = UxRestrictionState.UNKNOWN),
                    parked() to unrestricted().copy(uxRestrictionState = UxRestrictionState.RESTRICTED),
                )
            for ((vehicle, restrictions) in denied) {
                val fixture = Fixture()
                val subscriber = subscribe(fixture)
                val attempt = fixture.model.uiState.value.attempt
                val token = checkNotNull(fixture.model.onMapLoading(attempt))
                fixture.model.onMapRendered(attempt, token)
                runCurrent()
                val displayed = fixture.model.uiState.value
                assertTrue(displayed.allowsDetailedInteraction && displayed.load == MapLoadState.READY)
                assertTrue(fixture.model.canInteractWithMap(attempt, token))
                fixture.vehicle.state.value = vehicle
                fixture.restrictions.restrictions.value = restrictions
                assertEquals(displayed, fixture.model.uiState.value)
                assertFalse(fixture.model.canInteractWithMap(attempt, token))
                subscriber.cancelAndJoin()
                runCurrent()
                assertEquals(0, fixture.repository.active)
            }
        }

    @Test fun `input change observation does not own preparation and old course permission is fenced before resolution`() =
        runTest {
            val fixture = Fixture()
            var notifications = 0
            val inputObserver =
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    fixture.model.mapInputChanges.collect { notifications++ }
                }
            runCurrent()
            assertEquals(0, fixture.repository.calls)
            val subscriber = subscribe(fixture)
            val attempt = fixture.model.uiState.value.attempt
            val token = checkNotNull(fixture.model.onMapLoading(attempt))
            fixture.model.onMapRendered(attempt, token)
            runCurrent()
            val initialNotifications = notifications
            var checked = false
            fixture.catalog.onResolve = { selection ->
                if (selection?.courseId == "b") {
                    checked = true
                    assertFalse(fixture.model.canInteractWithMap(attempt, token))
                }
            }
            fixture.repository.values.value = selection("b")
            runCurrent()
            assertTrue(checked)
            assertTrue(notifications > initialNotifications)
            assertFalse(fixture.model.canInteractWithMap(attempt, token))
            assertEquals(1, fixture.repository.maximumActive)
            subscriber.cancelAndJoin()
            runCurrent()
            assertEquals(0, fixture.repository.active)
            assertFalse(fixture.model.canInteractWithMap(attempt, token))
            inputObserver.cancelAndJoin()
        }

    private fun TestScope.subscribe(fixture: Fixture): Job {
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.model.uiState.collect {} }
        runCurrent()
        return job
    }

    private fun ready(fixture: Fixture) = fixture.model.uiState.value.preparation as MapPreparationState.Ready

    private class Fixture {
        val vehicle = FakeVehicle()
        val restrictions = FakeRestrictions()
        val repository = FakeRepository()
        val catalog = FakeCatalog()
        val model = MapViewModel(vehicle, restrictions, repository, catalog)
    }

    private class FakeVehicle : VehicleGateway {
        override val state = MutableStateFlow(parked())
    }

    private class FakeRestrictions : DrivingRestrictionsProvider {
        override val restrictions = MutableStateFlow(unrestricted())
    }

    private class FakeCatalog : PracticeCatalog {
        override val goals = listOf(goal())
        override var courses = listOf(course("a"), course("b"))
        var forced: Preparation? = null
        var onResolve: ((PracticeSelection?) -> Unit)? = null

        override fun resolve(selection: PracticeSelection?): Preparation {
            onResolve?.invoke(selection)
            return forced ?: super.resolve(selection)
        }
    }

    private class FakeRepository : PracticeRepository {
        val values = MutableStateFlow<PracticeSelection?>(selection("a"))
        var source: () -> Flow<PracticeSelection?> = { values }
        var factoryFailure: Exception? = null
        var calls = 0
        var active = 0
        var maximumActive = 0
        var writes = 0

        override fun observeSelection(): Flow<PracticeSelection?> {
            calls++
            factoryFailure?.let { throw it }
            return flow {
                active++
                maximumActive = maxOf(maximumActive, active)
                try {
                    emitAll(source())
                } finally {
                    active--
                }
            }
        }

        override suspend fun saveSelection(
            selection: PracticeSelection,
            canCommit: () -> Boolean,
        ): Boolean {
            writes++
            error("Map preparation reads must never save a selection")
        }
    }

    private companion object {
        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        fun unrestricted() = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)

        fun goal() = PracticeGoal("goal", "Fixture goal", "Fixture summary")

        fun selection(id: String) = PracticeSelection("goal", id, 1, 1, "task-$id")

        fun course(
            id: String,
            map: CourseMapPreview? = preview(id),
        ) = PracticeCourse(
            id,
            1,
            1,
            "Course $id",
            "Fixture summary",
            listOf("Fixture section"),
            listOf(PracticeTask("task-$id", "Task $id", "Fixture task")),
            setOf("goal"),
            mapPreview = map,
        )

        fun preview(id: String) =
            CourseMapPreview(
                "preview-$id",
                1,
                "Preview $id",
                listOf(
                    CoursePreviewPoint("$id-1", "Fixture one", 37.0, 127.0, 1, 1),
                    CoursePreviewPoint("$id-2", "Fixture two", 37.1, 127.1, 2, 1),
                ),
            )
    }
}
