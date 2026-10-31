package io.github.hangillab.hangil.feature.map

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseMapPreview
import io.github.hangillab.hangil.core.domain.CoursePreviewPoint
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
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class MapPointSelectionViewModelTest {
    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun noReaderCannotReadOrSelectAnyPoint() =
        runTest {
            val f = Fixture()
            assertNull(f.model.selectPoint(0, 1, preview("a"), "a-1") { true })
            assertFalse(f.model.canApplyPointSelection(0, 1, 0))
            f.model.onPointSelectionRendered(0, 1, 1)
            f.model.dismissPointSelection(1)
            runCurrent()
            assertNull(f.model.uiState.value.pointSelection)
            assertEquals(0, f.repository.calls)
            assertEquals(0, f.repository.writes)
        }

    @Test fun readyMapExposesItsTokenWithoutAnAutomaticSelection() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val r = renderReady(f)
            assertTrue(r.token > 0)
            assertEquals(r.token, f.model.uiState.value.rendererToken)
            assertNull(f.model.uiState.value.pointSelection)
            assertTrue(f.model.canApplyPointSelection(r.attempt, r.token, 0))
            assertEquals(1, f.repository.calls)
            assertEquals(0, f.repository.writes)
        }

    @Test fun selectedIdentityStaysPendingUntilTheExactAck() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val r = renderReady(f)
            val nonce = select(f, r, "a-1")
            val pending = checkNotNull(f.model.uiState.value.pointSelection)
            assertTrue(nonce > 0)
            assertEquals("a-1", pending.pointId)
            assertEquals(nonce, pending.nonce)
            assertFalse(pending.rendered)
            assertTrue(f.model.canApplyPointSelection(r.attempt, r.token, nonce))
            f.model.onPointSelectionRendered(r.attempt, r.token, nonce)
            runCurrent()
            assertTrue(checkNotNull(f.model.uiState.value.pointSelection).rendered)
            assertEquals(0, f.repository.writes)
        }

    @Test fun unreadyAndFailedRenderersCannotSelectOrAck() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val attempt = f.model.uiState.value.attempt
            assertNull(f.model.selectPoint(attempt, 0, preview("a"), "a-1") { true })
            val token = checkNotNull(f.model.onMapLoading(attempt))
            assertNull(f.model.selectPoint(attempt, token, preview("a"), "a-1") { true })
            f.model.onMapRendered(attempt, token)
            runCurrent()
            val r = Renderer(attempt, token, prepared(f).preview)
            val nonce = select(f, r, "a-1")
            f.model.onMapFailed(attempt, token)
            f.model.onPointSelectionRendered(attempt, token, nonce)
            runCurrent()
            assertEquals(MapLoadState.FAILED, f.model.uiState.value.load)
            assertNull(f.model.uiState.value.pointSelection)
            assertFalse(f.model.canApplyPointSelection(attempt, token, nonce))
            assertFalse(f.model.canApplyPointSelection(attempt, token, 0))
            assertNull(f.model.selectPoint(attempt, token, r.preview, "a-1") { true })
        }

    @Test fun invalidPointIdsNeverBecomeSelections() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val r = renderReady(f)
            for (id in listOf("", " ", "missing", "b-1")) {
                assertNull(f.model.selectPoint(r.attempt, r.token, r.preview, id) { true })
                runCurrent()
                assertNull(f.model.uiState.value.pointSelection)
            }
            assertEquals(1, f.repository.calls)
            assertEquals(0, f.repository.writes)
        }

    @Test fun theWholePreviewMustMatchIncludingCoordinatesTitlesAndProvenance() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val r = renderReady(f)
            val original = r.preview
            val first = original.points.first()
            val altered =
                listOf(
                    original.copy(id = "other"),
                    original.copy(version = 2),
                    original.copy(title = "other preview"),
                    original.copy(points = original.points.reversed()),
                    original.copy(points = listOf(first.copy(title = "other point")) + original.points.drop(1)),
                    original.copy(points = listOf(first.copy(latitude = 37.01)) + original.points.drop(1)),
                    original.copy(points = listOf(first.copy(longitude = 127.01)) + original.points.drop(1)),
                    original.copy(points = listOf(first.copy(osmWayId = 99)) + original.points.drop(1)),
                    original.copy(points = listOf(first.copy(osmVersion = 99)) + original.points.drop(1)),
                )
            for (value in altered) assertNull(f.model.selectPoint(r.attempt, r.token, value, first.id) { true })
            runCurrent()
            assertNull(f.model.uiState.value.pointSelection)
            assertEquals(0, f.repository.writes)
        }

    @Test fun staleAttemptsTokensAndAcksCannotBorrowCurrentSelection() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val r = renderReady(f)
            assertNull(f.model.selectPoint(r.attempt + 1, r.token, r.preview, "a-1") { true })
            assertNull(f.model.selectPoint(r.attempt, r.token + 1, r.preview, "a-1") { true })
            assertNull(f.model.selectPoint(r.attempt, 0, r.preview, "a-1") { true })
            val nonce = select(f, r, "a-1")
            f.model.onPointSelectionRendered(r.attempt + 1, r.token, nonce)
            f.model.onPointSelectionRendered(r.attempt, r.token + 1, nonce)
            runCurrent()
            assertFalse(checkNotNull(f.model.uiState.value.pointSelection).rendered)
            assertTrue(f.model.canApplyPointSelection(r.attempt, r.token, nonce))
        }

    @Test fun rapidChoicesRejectOldAckAndDismissWithoutChangingTheNewChoice() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val r = renderReady(f)
            val first = select(f, r, "a-1")
            val second = select(f, r, "a-2")
            assertNotEquals(first, second)
            assertFalse(f.model.canApplyPointSelection(r.attempt, r.token, first))
            f.model.onPointSelectionRendered(r.attempt, r.token, first)
            f.model.dismissPointSelection(first)
            runCurrent()
            val pending = checkNotNull(f.model.uiState.value.pointSelection)
            assertEquals("a-2", pending.pointId)
            assertEquals(second, pending.nonce)
            assertFalse(pending.rendered)
            f.model.onPointSelectionRendered(r.attempt, r.token, second)
            runCurrent()
            assertTrue(checkNotNull(f.model.uiState.value.pointSelection).rendered)
        }

    @Test fun selectingTheSamePointAgainCreatesANewPendingNonce() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val r = renderReady(f)
            val first = select(f, r, "a-1")
            f.model.onPointSelectionRendered(r.attempt, r.token, first)
            runCurrent()
            val second = select(f, r, "a-1")
            assertNotEquals(first, second)
            f.model.onPointSelectionRendered(r.attempt, r.token, first)
            runCurrent()
            assertFalse(checkNotNull(f.model.uiState.value.pointSelection).rendered)
            assertTrue(f.model.canApplyPointSelection(r.attempt, r.token, second))
        }

    @Test fun zeroNonceCanOnlyClearTheCurrentReadyRendererWithNoChoice() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val r = renderReady(f)
            assertFalse(f.model.canApplyPointSelection(r.attempt + 1, r.token, 0))
            assertFalse(f.model.canApplyPointSelection(r.attempt, r.token + 1, 0))
            val nonce = select(f, r, "a-1")
            assertFalse(f.model.canApplyPointSelection(r.attempt, r.token, 0))
            f.model.onPointSelectionRendered(r.attempt, r.token, nonce)
            runCurrent()
            assertFalse(f.model.canApplyPointSelection(r.attempt, r.token, 0))
            f.model.dismissPointSelection(nonce + 1)
            runCurrent()
            assertNotNull(f.model.uiState.value.pointSelection)
            f.model.dismissPointSelection(nonce)
            runCurrent()
            assertNull(f.model.uiState.value.pointSelection)
            assertFalse(f.model.canApplyPointSelection(r.attempt, r.token, nonce))
            assertTrue(f.model.canApplyPointSelection(r.attempt, r.token, 0))
        }

    @Test fun stoppedHostsCannotStartOrReviveAnExistingSelection() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val r = renderReady(f)
            assertNull(f.model.selectPoint(r.attempt, r.token, r.preview, "a-1") { false })
            var visible = true
            val nonce = select(f, r, "a-1") { visible }
            visible = false
            assertFalse(f.model.canApplyPointSelection(r.attempt, r.token, nonce))
            visible = true
            assertFalse(f.model.canApplyPointSelection(r.attempt, r.token, nonce))
            f.model.onPointSelectionRendered(r.attempt, r.token, nonce)
            runCurrent()
            assertNull(f.model.uiState.value.pointSelection)
            assertNotEquals(nonce, select(f, r, "a-2") { visible })
        }

    @Test fun silentLiveDenialsRejectSelectionBeforeTheParkedUiChanges() =
        runTest {
            for ((vehicle, restrictions) in deniedValues()) {
                val f = Fixture()
                val subscriber = subscribe(f)
                val r = renderReady(f)
                val displayed = f.model.uiState.value
                f.vehicle.live.setWithoutEmission(vehicle)
                f.restrictions.live.setWithoutEmission(restrictions)
                assertEquals(displayed, f.model.uiState.value)
                assertNull(f.model.selectPoint(r.attempt, r.token, r.preview, "a-1") { true })
                runCurrent()
                assertNull(f.model.uiState.value.pointSelection)
                subscriber.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun canApplyDiscardsDeniedNoncesSoSilentParkedReturnCannotReviveThem() =
        runTest {
            for ((vehicle, restrictions) in deniedValues()) {
                val f = Fixture()
                val subscriber = subscribe(f)
                val r = renderReady(f)
                val nonce = select(f, r, "a-1")
                val displayed = f.model.uiState.value
                f.vehicle.live.setWithoutEmission(vehicle)
                f.restrictions.live.setWithoutEmission(restrictions)
                assertEquals(displayed, f.model.uiState.value)
                assertFalse(f.model.canApplyPointSelection(r.attempt, r.token, nonce))
                f.vehicle.live.setWithoutEmission(parked())
                f.restrictions.live.setWithoutEmission(unrestricted())
                assertFalse(f.model.canApplyPointSelection(r.attempt, r.token, nonce))
                f.model.onPointSelectionRendered(r.attempt, r.token, nonce)
                runCurrent()
                assertNull(f.model.uiState.value.pointSelection)
                assertTrue(f.model.canApplyPointSelection(r.attempt, r.token, 0))
                subscriber.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun nativeInputLiveDenialDiscardsPendingChoicesBeforeSilentParkedReturn() =
        runTest {
            for ((vehicle, restrictions) in deniedValues()) {
                val f = Fixture()
                val subscriber = subscribe(f)
                val r = renderReady(f)
                val nonce = select(f, r, "a-1")
                val displayed = f.model.uiState.value
                f.vehicle.live.setWithoutEmission(vehicle)
                f.restrictions.live.setWithoutEmission(restrictions)
                assertEquals(displayed, f.model.uiState.value)
                assertFalse(f.model.canInteractWithMap(r.attempt, r.token))
                f.vehicle.live.setWithoutEmission(parked())
                f.restrictions.live.setWithoutEmission(unrestricted())
                assertFalse(f.model.canApplyPointSelection(r.attempt, r.token, nonce))
                f.model.onPointSelectionRendered(r.attempt, r.token, nonce)
                runCurrent()
                assertNull(f.model.uiState.value.pointSelection)
                assertTrue(f.model.canApplyPointSelection(r.attempt, r.token, 0))
                subscriber.cancelAndJoin()
                runCurrent()
            }
        }

    @Test fun anAckThatFirstObservesSilentDDiscardsItsNonce() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val r = renderReady(f)
            val nonce = select(f, r, "a-1")
            f.vehicle.live.setWithoutEmission(driving())
            f.model.onPointSelectionRendered(r.attempt, r.token, nonce)
            f.vehicle.live.setWithoutEmission(parked())
            runCurrent()
            assertNull(f.model.uiState.value.pointSelection)
            assertFalse(f.model.canApplyPointSelection(r.attempt, r.token, nonce))
        }

    @Test fun aRejectedNewRequestObservingSilentDAlsoDiscardsTheOldIntent() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val r = renderReady(f)
            val nonce = select(f, r, "a-1")
            f.vehicle.live.setWithoutEmission(driving())
            assertNull(f.model.selectPoint(r.attempt, r.token, r.preview, "a-2") { true })
            f.vehicle.live.setWithoutEmission(parked())
            assertFalse(f.model.canApplyPointSelection(r.attempt, r.token, nonce))
            runCurrent()
            assertNull(f.model.uiState.value.pointSelection)
        }

    @Test fun anExpiredIncomingHostCannotClearAnotherCurrentValidHostChoice() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val r = renderReady(f)
            val nonce = select(f, r, "a-1") { true }
            assertNull(f.model.selectPoint(r.attempt, r.token, r.preview, "a-2") { false })
            runCurrent()
            assertEquals(nonce, checkNotNull(f.model.uiState.value.pointSelection).nonce)
            assertTrue(f.model.canApplyPointSelection(r.attempt, r.token, nonce))
        }

    @Test fun aFreshTokenInTheSameAttemptClearsThePreviousViewSelection() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val first = renderReady(f)
            val nonce = select(f, first, "a-1")
            f.model.onPointSelectionRendered(first.attempt, first.token, nonce)
            runCurrent()
            val replacement = checkNotNull(f.model.onMapLoading(first.attempt))
            assertNotEquals(first.token, replacement)
            runCurrent()
            assertNull(f.model.uiState.value.pointSelection)
            assertFalse(f.model.canApplyPointSelection(first.attempt, first.token, nonce))
            f.model.onMapRendered(first.attempt, replacement)
            runCurrent()
            val second = Renderer(first.attempt, replacement, prepared(f).preview)
            val fresh = select(f, second, "a-2")
            f.model.onPointSelectionRendered(first.attempt, first.token, nonce)
            f.model.onMapFailed(first.attempt, first.token)
            f.model.dismissPointSelection(nonce)
            runCurrent()
            assertEquals(fresh, checkNotNull(f.model.uiState.value.pointSelection).nonce)
            assertEquals(MapLoadState.READY, f.model.uiState.value.load)
        }

    @Test fun retryClearsSelectionWithoutCarryingItIntoTheFreshRenderer() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val first = renderReady(f)
            val nonce = select(f, first, "a-1")
            f.model.retry()
            runCurrent()
            assertNotEquals(first.attempt, f.model.uiState.value.attempt)
            assertNull(f.model.uiState.value.pointSelection)
            assertFalse(f.model.canApplyPointSelection(first.attempt, first.token, nonce))
            val replacement = renderReady(f)
            assertTrue(f.model.canApplyPointSelection(replacement.attempt, replacement.token, 0))
        }

    @Test fun preparationRetryRevokesTheOldQueryWithoutWritingSelection() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val first = renderReady(f)
            val nonce = select(f, first, "a-1")
            assertTrue(f.model.retryPreparation())
            assertFalse(f.model.canApplyPointSelection(first.attempt, first.token, nonce))
            runCurrent()
            assertNull(f.model.uiState.value.pointSelection)
            assertEquals(1, f.repository.active)
            assertEquals(1, f.repository.maximumActive)
            assertEquals(0, f.repository.writes)
        }

    @Test fun receivedCourseChangeRevokesPointApplicationBeforeCatalogResolution() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val first = renderReady(f)
            val nonce = select(f, first, "a-1")
            var checked = false
            f.catalog.onResolve = { value ->
                if (value?.courseId == "b") {
                    checked = true
                    assertFalse(f.model.canApplyPointSelection(first.attempt, first.token, nonce))
                    f.model.onPointSelectionRendered(first.attempt, first.token, nonce)
                }
            }
            f.repository.values.value = selection("b")
            runCurrent()
            assertTrue(checked)
            assertEquals("b", prepared(f).prepared.course.id)
            assertNull(f.model.uiState.value.pointSelection)
            val second = renderReady(f)
            val fresh = select(f, second, "b-1")
            f.model.onPointSelectionRendered(first.attempt, first.token, nonce)
            f.model.dismissPointSelection(nonce)
            runCurrent()
            assertEquals(fresh, checkNotNull(f.model.uiState.value.pointSelection).nonce)
            assertFalse(checkNotNull(f.model.uiState.value.pointSelection).rendered)
        }

    @Test fun outdatedPreparationClearsPointSelectionWithoutAGenericFallback() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val first = renderReady(f)
            val nonce = select(f, first, "a-1")
            f.repository.values.value = selection("missing")
            runCurrent()
            assertEquals(MapPreparationState.Outdated, f.model.uiState.value.preparation)
            assertNull(f.model.uiState.value.pointSelection)
            assertFalse(f.model.canApplyPointSelection(first.attempt, first.token, nonce))
            assertNull(f.model.onMapLoading(f.model.uiState.value.attempt))
        }

    @Test fun receivedDAndParkedReturnRequireANewExplicitChoice() =
        runTest {
            val f = Fixture()
            subscribe(f)
            val first = renderReady(f)
            val nonce = select(f, first, "a-1")
            f.vehicle.live.publish(driving())
            runCurrent()
            assertEquals(MapPreparationState.Locked, f.model.uiState.value.preparation)
            assertNull(f.model.uiState.value.pointSelection)
            f.vehicle.live.publish(parked())
            runCurrent()
            val second = renderReady(f)
            assertNull(f.model.uiState.value.pointSelection)
            assertFalse(f.model.canApplyPointSelection(first.attempt, first.token, nonce))
            assertTrue(f.model.canApplyPointSelection(second.attempt, second.token, 0))
        }

    @Test fun stoppedReaderCallbacksCannotBorrowAReenteredReader() =
        runTest {
            val f = Fixture()
            val subscriber = subscribe(f)
            val first = renderReady(f)
            val nonce = select(f, first, "a-1")
            subscriber.cancelAndJoin()
            runCurrent()
            assertEquals(0, f.repository.active)
            assertNull(f.model.uiState.value.pointSelection)
            assertFalse(f.model.canApplyPointSelection(first.attempt, first.token, nonce))
            subscribe(f)
            val second = renderReady(f)
            val fresh = select(f, second, "a-2")
            f.model.onPointSelectionRendered(first.attempt, first.token, nonce)
            f.model.dismissPointSelection(nonce)
            runCurrent()
            assertEquals(fresh, checkNotNull(f.model.uiState.value.pointSelection).nonce)
            assertEquals(1, f.repository.active)
            assertEquals(1, f.repository.maximumActive)
            assertEquals(0, f.repository.writes)
        }

    @Test fun preparationFailureClearsSelectionAndRequiresExplicitRetry() =
        runTest {
            val f = Fixture()
            val fail = CompletableDeferred<Unit>()
            f.repository.querySource = {
                flow {
                    emit(selection("a"))
                    fail.await()
                    throw IOException("fixture preparation failure")
                }
            }
            subscribe(f)
            val first = renderReady(f)
            val nonce = select(f, first, "a-1")
            fail.complete(Unit)
            runCurrent()
            assertEquals(MapPreparationState.Failed(MapPreparationState.FailureReason.Read), f.model.uiState.value.preparation)
            assertNull(f.model.uiState.value.pointSelection)
            assertFalse(f.model.canApplyPointSelection(first.attempt, first.token, nonce))
            f.repository.querySource = { f.repository.values }
            assertTrue(f.model.retryPreparation())
            runCurrent()
            renderReady(f)
            assertNull(f.model.uiState.value.pointSelection)
        }

    @Test fun normalStreamCompletionCannotKeepAnAcknowledgedSelectionAlive() =
        runTest {
            val f = Fixture()
            val finish = CompletableDeferred<Unit>()
            f.repository.querySource = {
                flow {
                    emit(selection("a"))
                    finish.await()
                }
            }
            subscribe(f)
            val first = renderReady(f)
            val nonce = select(f, first, "a-1")
            f.model.onPointSelectionRendered(first.attempt, first.token, nonce)
            runCurrent()
            finish.complete(Unit)
            runCurrent()
            assertEquals(
                MapPreparationState.Failed(MapPreparationState.FailureReason.StreamCompleted),
                f.model.uiState.value.preparation,
            )
            assertNull(f.model.uiState.value.pointSelection)
            assertFalse(f.model.canApplyPointSelection(first.attempt, first.token, nonce))
        }

    private fun TestScope.subscribe(fixture: Fixture): Job {
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.model.uiState.collect {} }
        runCurrent()
        return job
    }

    private fun TestScope.renderReady(fixture: Fixture): Renderer {
        val attempt = fixture.model.uiState.value.attempt
        val token = checkNotNull(fixture.model.onMapLoading(attempt))
        fixture.model.onMapRendered(attempt, token)
        runCurrent()
        assertEquals(MapLoadState.READY, fixture.model.uiState.value.load)
        assertEquals(token, fixture.model.uiState.value.rendererToken)
        return Renderer(attempt, token, prepared(fixture).preview)
    }

    private fun TestScope.select(
        fixture: Fixture,
        renderer: Renderer,
        pointId: String,
        canPresent: () -> Boolean = { true },
    ): Long {
        val nonce = checkNotNull(fixture.model.selectPoint(renderer.attempt, renderer.token, renderer.preview, pointId, canPresent))
        runCurrent()
        return nonce
    }

    private fun prepared(fixture: Fixture) = fixture.model.uiState.value.preparation as MapPreparationState.Ready

    private data class Renderer(
        val attempt: Int,
        val token: Long,
        val preview: CourseMapPreview,
    )

    private class Fixture {
        val vehicle = FakeVehicle()
        val restrictions = FakeRestrictions()
        val repository = FakeRepository()
        val catalog = FakeCatalog()
        val model = MapViewModel(vehicle, restrictions, repository, catalog)
    }

    private class FakeVehicle : VehicleGateway {
        val live = SilentLiveState(parked())
        override val state: StateFlow<VehicleState> = live
    }

    private class FakeRestrictions : DrivingRestrictionsProvider {
        val live = SilentLiveState(unrestricted())
        override val restrictions: StateFlow<DrivingRestrictions> = live
    }

    private class FakeCatalog : PracticeCatalog {
        override val goals = listOf(PracticeGoal("goal", "Fixture goal", "Fixture summary"))
        override val courses = listOf(course("a"), course("b"))
        var onResolve: ((PracticeSelection?) -> Unit)? = null

        override fun resolve(selection: PracticeSelection?): Preparation {
            onResolve?.invoke(selection)
            return super.resolve(selection)
        }
    }

    private class FakeRepository : PracticeRepository {
        val values = MutableStateFlow<PracticeSelection?>(selection("a"))
        var querySource: () -> Flow<PracticeSelection?> = { values }
        var calls = 0
        var active = 0
        var maximumActive = 0
        var writes = 0

        override fun observeSelection(): Flow<PracticeSelection?> {
            calls++
            return flow {
                active++
                maximumActive = maxOf(maximumActive, active)
                try {
                    emitAll(querySource())
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
            error("Point selection must not write preparation")
        }
    }

    @OptIn(InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
    private class SilentLiveState<T>(
        initial: T,
        private val emitted: MutableStateFlow<T> = MutableStateFlow(initial),
    ) : StateFlow<T> by emitted {
        @Volatile private var liveValue = initial
        override val value: T get() = liveValue

        fun setWithoutEmission(value: T) {
            liveValue = value
        }

        fun publish(value: T) {
            liveValue = value
            emitted.value = value
        }
    }

    private companion object {
        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        fun driving() = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))

        fun unrestricted() = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)

        fun deniedValues() =
            listOf(
                driving() to unrestricted(),
                parked().copy(connection = ConnectionState.DISCONNECTED) to unrestricted(),
                parked().copy(connection = ConnectionState.PERMISSION_DENIED) to unrestricted(),
                parked().copy(gear = VehicleReading.Unknown) to unrestricted(),
                parked().copy(gear = VehicleReading.PermissionDenied) to unrestricted(),
                parked() to unrestricted().copy(parkingState = ParkingState.UNKNOWN),
                parked() to unrestricted().copy(uxRestrictionState = UxRestrictionState.UNKNOWN),
                parked() to unrestricted().copy(uxRestrictionState = UxRestrictionState.RESTRICTED),
            )

        fun selection(id: String) = PracticeSelection("goal", id, 1, 1, "task-$id")

        fun course(id: String) =
            PracticeCourse(
                id,
                1,
                1,
                "Course $id",
                "Fixture summary",
                listOf("Fixture section"),
                listOf(PracticeTask("task-$id", "Task $id", "Fixture task")),
                setOf("goal"),
                mapPreview = preview(id),
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
