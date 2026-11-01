package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.LocationRecordingController
import io.github.hangillab.hangil.core.domain.LocationRecordingState
import io.github.hangillab.hangil.core.domain.LocationUpdateGateway
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Synthetic session/vehicle/controller inputs. No SDK update, permission request or DB write runs here. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocationRecordingViewModelTest {
    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `hidden model does not observe Room or initialize location updates`() =
        runTest {
            val fixture = Fixture()
            val model = fixture.model()
            runCurrent()
            assertSame(LocationRecordingUiState.Loading, model.uiState.value)
            assertEquals(0, fixture.sessions.reads)
            assertEquals(0, fixture.gateway.permissionReads)
            assertEquals(0, fixture.controller.starts)
            assertSame(fixture.controller.state, model.recordingState)
        }

    @Test fun `only actual refresh resolves unknown and never requests or starts location collection`() =
        runTest {
            val fixture = Fixture()
            val model = fixture.model()
            observe(model)
            assertEquals(LocationPermissionState.UNKNOWN, ready(model).permission)
            fixture.gateway.permission = false
            model.refreshPermission()
            runCurrent()
            assertEquals(LocationPermissionState.DENIED, ready(model).permission)
            fixture.gateway.permission = true
            model.refreshPermission()
            runCurrent()
            assertEquals(LocationPermissionState.GRANTED, ready(model).permission)
            fixture.gateway.failure = IllegalStateException("controlled permission read")
            model.refreshPermission()
            runCurrent()
            assertEquals(LocationPermissionState.FAILED, ready(model).permission)
            assertEquals(3, fixture.gateway.permissionReads)
            assertEquals(0, fixture.controller.starts)
        }

    @Test fun `permission request gate only checks current source and live parking UX`() =
        runTest {
            val fixture = Fixture()
            val model = fixture.model()
            observe(model)
            val shown = ready(model)
            assertTrue(model.canRequestPermission(shown))
            assertEquals(0, fixture.gateway.permissionReads)
            fixture.vehicle.state.value = VehicleState()
            assertFalse(model.canRequestPermission(shown))
            assertEquals(0, fixture.gateway.permissionReads)
            assertEquals(0, fixture.controller.starts)
        }

    @Test fun `fresh permission false or exception denies start without a success fallback`() =
        runTest {
            val fixture = Fixture()
            val model = fixture.model()
            observe(model)
            fixture.gateway.permission = true
            model.refreshPermission()
            runCurrent()
            val original = ready(model)
            fixture.gateway.permission = false
            assertFalse(model.start(original) { true })
            runCurrent()
            assertEquals(LocationPermissionState.DENIED, ready(model).permission)
            fixture.gateway.failure = SecurityException("controlled lookup failure")
            assertFalse(model.canStart(ready(model)))
            runCurrent()
            assertEquals(LocationPermissionState.FAILED, ready(model).permission)
            assertEquals(0, fixture.controller.starts)
        }

    @Test fun `live D or restricted UX blocks an old parked display before recomposition`() =
        runTest {
            val fixture = Fixture()
            val model = fixture.model()
            observeGranted(model, fixture)
            val parked = ready(model)
            fixture.vehicle.state.value = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.DRIVE))
            assertFalse(model.start(parked) { true })
            fixture.vehicle.state.value = Fixture.parked
            fixture.restrictions.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.RESTRICTED)
            assertFalse(model.start(parked) { true })
            assertEquals(0, fixture.controller.starts)
        }

    @Test fun `source replacement and same-ID metadata change retire old Ready receipts`() =
        runTest {
            val fixture = Fixture()
            val model = fixture.model()
            observeGranted(model, fixture)
            val original = ready(model)
            fixture.sessions.records.value = listOf(Fixture.source.copy(courseTitle = "바뀐 당시 코스"))
            runCurrent()
            assertFalse(model.canRequestPermission(original))
            assertFalse(model.start(original) { true })
            val changed = ready(model)
            fixture.sessions.records.value = listOf(Fixture.source)
            runCurrent()
            assertFalse(model.start(changed) { true })
            assertFalse(model.start(original) { true })
            assertEquals(0, fixture.controller.starts)
        }

    @Test fun `ended metadata changes retire current Ready even when the active source stays equal`() =
        runTest {
            val fixture = Fixture()
            val ended = Fixture.source.copy(sessionId = "controlled-ended", endedAtEpochMillis = 3)
            fixture.sessions.records.value = listOf(Fixture.source, ended)
            val model = fixture.model()
            observeGranted(model, fixture)
            val original = ready(model)
            fixture.sessions.records.value = listOf(Fixture.source, ended.copy(taskTitle = "바뀐 당시 과제"))
            runCurrent()
            assertFalse(model.start(original) { true })
            assertEquals(Fixture.source, ready(model).source)
        }

    @Test fun `source absent and multiple active or malformed records are not a usable Ready`() =
        runTest {
            val fixture = Fixture()
            val model = fixture.model()
            observe(model)
            fixture.sessions.records.value = emptyList()
            runCurrent()
            assertTrue(model.uiState.value is LocationRecordingUiState.NoActive)
            fixture.sessions.records.value = listOf(Fixture.source, Fixture.source.copy(sessionId = "another-active"))
            runCurrent()
            assertTrue(model.uiState.value is LocationRecordingUiState.Failed)
            fixture.sessions.records.value = listOf(Fixture.source.copy(endedAtEpochMillis = 0))
            runCurrent()
            assertTrue(model.uiState.value is LocationRecordingUiState.Failed)
            assertEquals(0, fixture.controller.starts)
        }

    @Test fun `source exception and finite completion fail instead of becoming an empty source`() =
        runTest {
            val sources: List<Flow<List<PracticeSession>>> =
                listOf(
                    flow { throw IllegalStateException("controlled read") },
                    flowOf(listOf(Fixture.source)),
                )
            for (source in sources) {
                val fixture = Fixture()
                fixture.sessions.source = source
                val model = fixture.model()
                observe(model)
                assertTrue(model.uiState.value is LocationRecordingUiState.Failed)
                assertEquals(0, fixture.controller.starts)
            }
        }

    @Test fun `unsubscribe retires old source and next display requires a fresh receipt`() =
        runTest {
            val fixture = Fixture()
            val model = fixture.model()
            val observer = observeGranted(model, fixture)
            val original = ready(model)
            observer.cancel()
            runCurrent()
            assertEquals(0, fixture.sessions.activeReaders)
            assertFalse(model.start(original) { true })
            observe(model)
            assertFalse(model.start(original) { true })
            assertTrue(ready(model).revision > original.revision)
            assertEquals(0, fixture.controller.starts)
        }

    @Test fun `start uses exact source and Starting ACK does not revoke the accepted start guard`() =
        runTest {
            val fixture = Fixture()
            fixture.controller.acceptStart = true
            fixture.gateway.permission = true
            val model = fixture.model()
            observeGranted(model, fixture)
            val original = ready(model)
            fixture.controller.onStart = { fixture.controller.state.value = LocationRecordingState.Starting("controlled-run", it) }
            assertTrue(model.start(original) { true })
            runCurrent()
            assertSame(original.source, fixture.controller.requested)
            assertTrue(fixture.controller.guard!!())
            assertFalse(model.start(original) { true })
            assertEquals(1, fixture.controller.starts)
        }

    @Test fun `unchanged active source keeps accepted start while unrelated ended rows change`() =
        runTest {
            val fixture = Fixture()
            val active = Fixture.source
            val unrelatedEnded = active.copy(sessionId = "controlled-ended", endedAtEpochMillis = 3)
            fixture.sessions.records.value = listOf(active, unrelatedEnded)
            fixture.controller.acceptStart = true
            val model = fixture.model()
            observeGranted(model, fixture)
            fixture.controller.onStart = {
                fixture.controller.state.value = LocationRecordingState.Starting("controlled-run", it)
            }
            assertTrue(model.start(ready(model)) { true })
            runCurrent()
            val acceptedGuard = checkNotNull(fixture.controller.guard)
            assertTrue(acceptedGuard())
            val beforeNotification = ready(model)

            fixture.sessions.records.value = listOf(active)
            runCurrent()

            assertTrue("An unchanged active source must retain the accepted registration guard", acceptedGuard())
            assertSame(active, fixture.controller.requested)
            assertTrue(ready(model).revision > beforeNotification.revision)
            assertFalse(model.canRequestPermission(beforeNotification))
            assertFalse(model.start(beforeNotification) { true })
            assertEquals(1, fixture.controller.starts)
        }

    @Test fun `start consumes displayed receipt even if controller state returns immediately to Idle`() =
        runTest {
            val fixture = Fixture()
            fixture.controller.acceptStart = true
            val model = fixture.model()
            observeGranted(model, fixture)
            val original = ready(model)
            fixture.controller.onStart = {
                fixture.controller.state.value = LocationRecordingState.Starting("controlled-run", it)
                fixture.controller.state.value = LocationRecordingState.Idle
            }
            assertTrue(model.start(original) { true })
            assertFalse(model.start(original) { true })
            assertEquals(1, fixture.controller.starts)
        }

    @Test fun `accepted start guard permanently retires after observed host or live gate denial`() =
        runTest {
            val fixture = Fixture()
            fixture.controller.acceptStart = true
            val model = fixture.model()
            observeGranted(model, fixture)
            var host = true
            assertTrue(model.start(ready(model)) { host })
            val old = fixture.controller.guard!!
            assertTrue(old())
            host = false
            assertFalse(old())
            host = true
            assertFalse(old())
            runCurrent()
            assertTrue(model.start(ready(model)) { true })
            val fresh = fixture.controller.guard!!
            fixture.vehicle.state.value = VehicleState()
            assertFalse(fresh())
            fixture.vehicle.state.value = Fixture.parked
            assertFalse(fresh())
        }

    @Test fun `caller host exception denies without calling controller and no automatic retry follows`() =
        runTest {
            val fixture = Fixture()
            val model = fixture.model()
            observeGranted(model, fixture)
            assertFalse(model.start(ready(model)) { throw IllegalStateException("controlled host") })
            model.refreshPermission()
            runCurrent()
            fixture.vehicle.state.value = VehicleState()
            runCurrent()
            fixture.vehicle.state.value = Fixture.parked
            runCurrent()
            assertEquals(0, fixture.controller.starts)
        }

    @Test fun `permission query cannot borrow an earlier parked true after synchronous gate change`() =
        runTest {
            val fixture = Fixture()
            val model = fixture.model()
            observeGranted(model, fixture)
            val original = ready(model)
            fixture.gateway.onRead = { fixture.vehicle.state.value = VehicleState() }
            assertFalse(model.start(original) { true })
            assertEquals(0, fixture.controller.starts)
        }

    @Test fun `refresh retires old display even when framework result repeats and never starts`() =
        runTest {
            val fixture = Fixture()
            val model = fixture.model()
            observeGranted(model, fixture)
            val original = ready(model)
            model.refreshPermission()
            assertFalse(model.canRequestPermission(original))
            runCurrent()
            assertEquals(LocationPermissionState.GRANTED, ready(model).permission)
            assertEquals(0, fixture.controller.starts)
        }

    @Test fun `stop always delegates exact owner independent of denied permission no source or D`() =
        runTest {
            val fixture = Fixture()
            val model = fixture.model()
            observe(model)
            fixture.controller.acceptStop = true
            fixture.sessions.records.value = emptyList()
            fixture.vehicle.state.value = VehicleState()
            fixture.gateway.permission = false
            runCurrent()
            assertTrue(model.stop("controlled-exact-run"))
            assertEquals(listOf("controlled-exact-run"), fixture.controller.stopped)
            assertEquals(0, fixture.gateway.permissionReads)
            assertEquals(0, fixture.controller.starts)
        }

    private fun ready(model: LocationRecordingViewModel): LocationRecordingUiState.Ready =
        model.uiState.value as LocationRecordingUiState.Ready

    private fun TestScope.observe(model: LocationRecordingViewModel): Job {
        val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { model.uiState.collect() }
        runCurrent()
        return observer
    }

    private fun TestScope.observeGranted(
        model: LocationRecordingViewModel,
        fixture: Fixture,
    ): Job {
        fixture.gateway.permission = true
        val observer = observe(model)
        model.refreshPermission()
        runCurrent()
        return observer
    }

    private class Fixture {
        val controller = ControlledController()
        val sessions = ControlledSessions()
        val gateway = ControlledGateway()
        val vehicle = ControlledVehicle()
        val restrictions = ControlledRestrictions()

        fun model(): LocationRecordingViewModel = LocationRecordingViewModel(controller, sessions, gateway, vehicle, restrictions)

        companion object {
            val parked = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))
            val unrestricted = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
            val source =
                PracticeSession(
                    "controlled-active",
                    PracticeSelection("goal", "course", 1, 1, "task"),
                    "목표",
                    "시연 코스",
                    "과제",
                    CourseReviewStatus.DEMO,
                    2,
                    null,
                )
        }
    }

    private class ControlledVehicle : VehicleGateway {
        override val state = MutableStateFlow(Fixture.parked)
    }

    private class ControlledRestrictions : DrivingRestrictionsProvider {
        override val restrictions = MutableStateFlow(Fixture.unrestricted)
    }

    private class ControlledGateway : LocationUpdateGateway {
        var permission: Boolean? = null
        var failure: Exception? = null
        var permissionReads = 0
        var onRead: () -> Unit = { }

        override fun hasPermission(): Boolean {
            permissionReads++
            onRead()
            failure?.let { throw it }
            return permission ?: error("Unexpected framework permission read")
        }

        override fun updates(
            canContinue: () -> Boolean,
            onRegistered: () -> Unit,
            onCleanupFailure: (Throwable) -> Unit,
        ): Flow<LocationUpdateReading> = error("ViewModel must never collect SDK updates")
    }

    private class ControlledSessions : PracticeSessionRepository {
        val records = MutableStateFlow(listOf(Fixture.source))
        var source: Flow<List<PracticeSession>> = records
        var reads = 0
        var activeReaders = 0

        override fun observeSessions(): Flow<List<PracticeSession>> =
            flow {
                reads++
                activeReaders++
                try {
                    emitAll(source)
                } finally {
                    activeReaders--
                }
            }

        override suspend fun start(
            practice: PreparedPractice,
            startedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionStartResult = error("Location control cannot start a practice")

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult = error("Location control cannot finish a practice")
    }

    private class ControlledController : LocationRecordingController {
        override val state = MutableStateFlow<LocationRecordingState>(LocationRecordingState.Idle)
        var starts = 0
        var requested: PracticeSession? = null
        var guard: (() -> Boolean)? = null
        var acceptStart = false
        var acceptStop = false
        var onStart: (PracticeSession) -> Unit = { }
        val stopped = mutableListOf<String>()

        override suspend fun recordWhileStarted(): Unit = error("ViewModel must never own the Main recording lifetime")

        override fun start(
            expected: PracticeSession,
            canStart: () -> Boolean,
        ): Boolean {
            starts++
            requested = expected
            guard = canStart
            onStart(expected)
            return acceptStart
        }

        override fun stop(expectedRunId: String): Boolean {
            stopped += expectedRunId
            return acceptStop
        }
    }
}
