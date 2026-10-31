package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclaration
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclarationResult
import io.github.hangillab.hangil.core.domain.CourseCompletionOrigin
import io.github.hangillab.hangil.core.domain.CourseCompletionRepository
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionDeletionRepository
import io.github.hangillab.hangil.core.domain.SessionDeletionResult
import io.github.hangillab.hangil.core.domain.SessionDeletionTarget
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import io.github.hangillab.hangil.core.domain.VehicleObservationRepository
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.feature.home.SessionHistoryCourseCompletionUiState.FailureReason
import io.github.hangillab.hangil.feature.home.SessionHistoryCourseCompletionUiState.Feedback
import io.github.hangillab.hangil.feature.home.SessionHistoryCourseCompletionUiState.Token
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** Real history readers with controlled repositories and live inputs; no SDK, service or Room writes. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionHistoryCourseCompletionViewModelTest {
    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `uncollected completion detail does not read sessions or declarations`() =
        runTest {
            val f = Fixture()
            f.model.observeCourseCompletion(session("a"), 1, f::hostAllowed)
            runCurrent()
            assertEquals(0, f.sessions.calls)
            assertEquals(0, f.declarations.reads)
            assertEquals(0, f.declarations.writes)
            assertFalse(f.model.canReadCourseCompletion(Token(0, 0, 0)))
        }

    @Test fun `missing or still unknown parent cannot read or declare`() =
        runTest {
            val f = Fixture()
            val d = open(f, session("a"), 1)
            assertTrue(d.value is SessionHistoryCourseCompletionUiState.Unavailable)
            assertFalse(f.model.requestCourseCompletion(checkNotNull(d.value).token))
            assertEquals(0, f.declarations.reads)
            f.sessions.querySource = { flow { awaitCancellation() } }
            start(f)
            assertTrue(f.model.uiState.value is SessionHistoryUiState.Loading)
            val loading = open(f, session("a"), 1)
            assertTrue(loading.value is SessionHistoryCourseCompletionUiState.Unavailable)
            assertEquals(0, f.declarations.writes)
        }

    @Test fun `collapsed history and other details do not query declarations`() =
        runTest {
            val f = Fixture()
            start(f)
            val source = parent(f)
            assertTrue(f.model.canRevealSummary(source.session.sessionId, source.generation))
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model.observeStoredObservations(source.session, source.generation, f::hostAllowed).collect {}
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model.observeStoredMemo(source.session, source.generation, f::hostAllowed).collect {}
            }
            runCurrent()
            assertEquals(1, f.sessions.calls)
            assertEquals(0, f.declarations.reads)
            assertEquals(0, f.declarations.writes)
        }

    @Test fun `full source and current history generation are required`() =
        runTest {
            val f = Fixture()
            start(f)
            val source = parent(f)
            assertTrue(
                open(
                    f,
                    source.session.copy(taskTitle = "changed"),
                    source.generation,
                ).value is SessionHistoryCourseCompletionUiState.Unavailable,
            )
            assertTrue(f.model.retry())
            runCurrent()
            assertTrue(open(f, source.session, source.generation).value is SessionHistoryCourseCompletionUiState.Unavailable)
            assertEquals(0, f.declarations.reads)
        }

    @Test fun `older selection reads only that declaration using the existing parent subscription`() =
        runTest {
            val f = Fixture()
            start(f)
            val source = parent(f)
            assertTrue(f.model.older(source.session.sessionId, source.generation))
            runCurrent()
            val original = declaration(session("b"))
            f.declarations.values.value = original
            val d = open(f)
            assertEquals(original, ready(d).declaration)
            assertEquals(listOf("b"), f.declarations.requested)
            assertEquals(1, f.sessions.calls)
            assertEquals(1, f.declarations.active)
        }

    @Test fun `only actual null is absent and opening does not declare`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            assertTrue(absent(d).canDeclare)
            assertTrue(f.model.canReadCourseCompletion(absent(d).token))
            assertFalse(f.model.retryCourseCompletion(absent(d).token))
            assertEquals(1, f.declarations.reads)
            assertEquals(0, f.declarations.writes)
        }

    @Test fun `stored user source version and positive reversed wall clock are preserved`() =
        runTest {
            val f = Fixture()
            val original = declaration(session("a"), version = " opaque version ", time = 1)
            f.declarations.values.value = original
            start(f)
            val d = open(f)
            assertEquals(original, ready(d).declaration)
            assertEquals(CourseCompletionOrigin.USER_DECLARATION, ready(d).declaration.origin)
            assertNull(ready(d).feedback)
            assertFalse(f.model.requestCourseCompletion(ready(d).token))
            assertEquals(0, f.declarations.writes)
        }

    @Test fun `another active session permits saved reading but denies a new declaration`() =
        runTest {
            val f = Fixture()
            f.sessions.values.value += session("active").copy(endedAtEpochMillis = null)
            start(f)
            val d = open(f)
            assertFalse(absent(d).canDeclare)
            assertFalse(f.model.requestCourseCompletion(absent(d).token))
            f.declarations.values.value = declaration(session("a"))
            runCurrent()
            assertEquals("a", ready(d).declaration.source.sessionId)
            assertTrue(f.model.canReadCourseCompletion(ready(d).token))
            assertEquals(0, f.declarations.writes)
        }

    @Test fun `D and unknown UX do not authorize declaration reads`() =
        runTest {
            val denied = Fixture()
            denied.vehicle.emit(driving())
            start(denied)
            open(denied, session("a"), 1)
            assertEquals(0, denied.declarations.reads)
            val unknown = Fixture()
            unknown.restrictions.emit(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNKNOWN))
            start(unknown)
            open(unknown, session("a"), 1)
            assertEquals(0, unknown.declarations.reads)
            assertEquals(0, unknown.declarations.writes)
        }

    @Test fun `foreign full snapshots and rejected storage decoding never become absent`() =
        runTest {
            for (invalid in listOf(
                declaration(session("b")),
                declaration(session("a").copy(taskTitle = "other snapshot")),
            )) {
                val f = Fixture()
                f.declarations.values.value = invalid
                start(f)
                val d = open(f)
                assertEquals(FailureReason.INVALID_DECLARATION, failed(d).reason)
                assertFalse(f.model.requestCourseCompletion(failed(d).token))
                assertEquals(0, f.declarations.writes)
            }
            // Model construction rejects malformed stored fields before a domain row can be emitted.
            val rejected = Fixture()
            rejected.declarations.querySource = { flow { throw IllegalArgumentException("controlled storage decoding failure") } }
            start(rejected)
            val failure = failed(open(rejected))
            assertEquals(FailureReason.DECLARATION_READ, failure.reason)
            assertFalse(rejected.model.requestCourseCompletion(failure.token))
            assertEquals(0, rejected.declarations.writes)
        }

    @Test fun `factory error is terminal until explicit retry and never becomes absent`() =
        runTest {
            val f = Fixture()
            f.declarations.factoryFailure = IOException("controlled read failure")
            start(f)
            val d = open(f)
            val old = failed(d)
            assertEquals(FailureReason.DECLARATION_READ, old.reason)
            f.declarations.factoryFailure = null
            f.declarations.values.value = declaration(session("a"))
            runCurrent()
            assertEquals(old, d.value)
            assertTrue(f.model.retryCourseCompletion(old.token))
            runCurrent()
            assertEquals(declaration(session("a")), ready(d).declaration)
            assertEquals(2, f.declarations.reads)
            assertFalse(f.model.retryCourseCompletion(old.token))
            assertEquals(0, f.declarations.writes)
        }

    @Test fun `finite null stream and independent cancellation are read failures`() =
        runTest {
            for ((query, expected) in listOf<Pair<() -> Flow<CourseCompletionDeclaration?>, FailureReason>>(
                Pair<() -> Flow<CourseCompletionDeclaration?>, FailureReason>(
                    { flowOf<CourseCompletionDeclaration?>(null) },
                    FailureReason.DECLARATION_STREAM_COMPLETED,
                ),
                Pair<() -> Flow<CourseCompletionDeclaration?>, FailureReason>(
                    { flow<CourseCompletionDeclaration?> { throw CancellationException("independent read failure") } },
                    FailureReason.DECLARATION_READ,
                ),
            )) {
                val f = Fixture()
                f.declarations.querySource = query
                start(f)
                val d = open(f)
                assertEquals(expected, failed(d).reason)
                assertFalse(f.model.requestCourseCompletion(failed(d).token))
                assertEquals(1, f.declarations.reads)
                assertEquals(0, f.declarations.writes)
            }
        }

    @Test fun `corruption remains terminal when a later valid receipt arrives`() =
        runTest {
            val f = Fixture()
            f.declarations.values.value = declaration(session("b"))
            start(f)
            val d = open(f)
            val old = failed(d)
            f.declarations.values.value = declaration(session("a"))
            runCurrent()
            assertEquals(old, d.value)
            assertTrue(f.model.retryCourseCompletion(old.token))
            runCurrent()
            assertEquals(declaration(session("a")), ready(d).declaration)
        }

    @Test fun `a known immutable declaration disappearing or changing fails`() =
        runTest {
            for (replacement in listOf(null, declaration(session("a"), version = "changed"))) {
                val f = Fixture()
                f.declarations.values.value = declaration(session("a"))
                start(f)
                val d = open(f)
                val old = ready(d)
                f.declarations.values.value = replacement
                runCurrent()
                assertEquals(FailureReason.DECLARATION_CHANGED, failed(d).reason)
                assertFalse(f.model.canReadCourseCompletion(old.token))
                assertFalse(f.model.requestCourseCompletion(failed(d).token))
                assertEquals(0, f.declarations.writes)
            }
        }

    @Test fun `request only opens confirmation and requires the current absent receipt`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = absent(d)
            val confirmation = request(f, d)
            assertNotEquals(old.token, confirmation.token)
            assertFalse(f.model.requestCourseCompletion(old.token))
            assertFalse(f.model.confirmCourseCompletion(old.token, confirmation.nonce))
            assertFalse(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce + 1))
            assertFalse(f.model.dismissCourseCompletion(confirmation.token, confirmation.nonce + 1))
            assertEquals(0, f.declarations.writes)
        }

    @Test fun `dismiss discards consent and a fresh confirmation cannot reuse its nonce`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val first = request(f, d)
            assertTrue(f.model.dismissCourseCompletion(first.token, first.nonce))
            runCurrent()
            assertTrue(absent(d).canDeclare)
            assertFalse(f.model.confirmCourseCompletion(first.token, first.nonce))
            val fresh = request(f, d)
            assertNotEquals(first.nonce, fresh.nonce)
            assertFalse(f.model.confirmCourseCompletion(fresh.token, first.nonce))
            assertTrue(f.model.dismissCourseCompletion(fresh.token, fresh.nonce))
            assertEquals(0, f.declarations.writes)
        }

    @Test fun `confirm is once only and the Saving acknowledgement preserves its captured commit guard`() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            var original: CourseCompletionDeclaration? = null
            f.declarations.declareAction = { source, time, guard ->
                assertTrue(guard())
                release.await()
                assertTrue(guard())
                declaration(source, time = time).let {
                    original = it
                    f.declarations.querySource = { MutableStateFlow(it) }
                    CourseCompletionDeclarationResult.Saved(it)
                }
            }
            start(f)
            val d = open(f)
            val confirmation = request(f, d)
            assertTrue(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce))
            runCurrent()
            val saving = d.value as SessionHistoryCourseCompletionUiState.Saving
            assertFalse(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce))
            assertFalse(f.model.confirmCourseCompletion(saving.token, saving.nonce))
            assertFalse(f.model.dismissCourseCompletion(saving.token, saving.nonce))
            assertTrue(checkNotNull(f.declarations.lastGuard).invoke())
            assertEquals(session("a"), f.declarations.lastSource)
            assertTrue(checkNotNull(f.declarations.lastTime) > 0)
            release.complete(Unit)
            runCurrent()
            assertEquals(original, ready(d).declaration)
            assertEquals(Feedback.SAVED, ready(d).feedback)
            assertEquals(1, f.declarations.writes)
            assertFalse(f.model.requestCourseCompletion(ready(d).token))
        }

    @Test fun `already declared response restores the exact original without replacing its time`() =
        runTest {
            val f = Fixture()
            val original = declaration(session("a"), version = "first-version", time = 1)
            f.declarations.declareAction = { _, _, guard ->
                assertTrue(guard())
                f.declarations.querySource = { MutableStateFlow(original) }
                CourseCompletionDeclarationResult.AlreadyDeclared(original)
            }
            start(f)
            val d = open(f)
            val confirmation = request(f, d)
            assertTrue(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce))
            runCurrent()
            assertEquals(original, ready(d).declaration)
            assertEquals(Feedback.ALREADY_DECLARED, ready(d).feedback)
            assertEquals(1, f.declarations.writes)
        }

    @Test fun `an actual saved receipt before the response is authoritative and does not repeat declare`() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            var original: CourseCompletionDeclaration? = null
            f.declarations.declareAction = { source, time, _ ->
                val stored = declaration(source, version = "committed", time = time)
                original = stored
                f.declarations.values.value = stored
                withContext(NonCancellable) { release.await() }
                CourseCompletionDeclarationResult.Saved(stored)
            }
            start(f)
            val d = open(f)
            val confirmation = request(f, d)
            assertTrue(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce))
            runCurrent()
            assertEquals(original, ready(d).declaration)
            assertNull(ready(d).feedback)
            release.complete(Unit)
            runCurrent()
            assertEquals(original, ready(d).declaration)
            assertEquals(1, f.declarations.writes)
            assertFalse(f.model.requestCourseCompletion(ready(d).token))
        }

    @Test fun `repository rejections remain distinct failures without a successful declaration`() =
        runTest {
            val results =
                listOf(
                    CourseCompletionDeclarationResult.MissingSession to FailureReason.MISSING_SESSION,
                    CourseCompletionDeclarationResult.SourceChanged to FailureReason.SOURCE_CHANGED,
                    CourseCompletionDeclarationResult.ActiveSession to FailureReason.ACTIVE_SESSION,
                    CourseCompletionDeclarationResult.Blocked to FailureReason.BLOCKED,
                    CourseCompletionDeclarationResult.InvalidTime to FailureReason.INVALID_TIME,
                )
            for ((result, reason) in results) {
                val f = Fixture()
                f.declarations.declareAction = { _, _, _ -> result }
                start(f)
                val d = open(f)
                val confirmation = request(f, d)
                assertTrue(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce))
                runCurrent()
                assertEquals(reason, failed(d).reason)
                assertEquals(1, f.declarations.writes)
                assertNull(f.declarations.values.value)
            }
        }

    @Test fun `foreign save results and Saved with a different timestamp never become ready`() =
        runTest {
            for (result in listOf<CourseCompletionDeclarationResult>(
                CourseCompletionDeclarationResult.Saved(declaration(session("b"))),
                CourseCompletionDeclarationResult.AlreadyDeclared(declaration(session("b"))),
                CourseCompletionDeclarationResult.AlreadyDeclared(declaration(session("a").copy(taskTitle = "other snapshot"))),
                CourseCompletionDeclarationResult.Saved(declaration(session("a"), time = 1)),
            )) {
                val f = Fixture()
                f.declarations.declareAction = { _, _, _ -> result }
                start(f)
                val d = open(f)
                val confirmation = request(f, d)
                assertTrue(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce))
                runCurrent()
                assertEquals(FailureReason.INVALID_SAVE_RESULT, failed(d).reason)
                assertEquals(1, f.declarations.writes)
            }
        }

    @Test fun `write exception and independent write cancellation are unconfirmed until explicit reread`() =
        runTest {
            for (failure in listOf(IOException("controlled write error"), CancellationException("controlled result loss"))) {
                val f = Fixture()
                f.declarations.declareAction = { _, _, _ -> throw failure }
                start(f)
                val d = open(f)
                val confirmation = request(f, d)
                assertTrue(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce))
                runCurrent()
                val error = failed(d)
                assertEquals(FailureReason.WRITE_UNCONFIRMED, error.reason)
                assertEquals(1, f.declarations.writes)
                assertTrue(f.model.retryCourseCompletion(error.token))
                runCurrent()
                assertTrue(absent(d).canDeclare)
                assertEquals(1, f.declarations.writes)
            }
        }

    @Test fun `duplicate null receipts preserve the same pending confirmation`() =
        runTest {
            val f = Fixture()
            val receipts = MutableSharedFlow<CourseCompletionDeclaration?>(replay = 1)
            receipts.emit(null)
            f.declarations.querySource = { receipts }
            start(f)
            val d = open(f)
            val confirmation = request(f, d)
            receipts.emit(null)
            runCurrent()
            assertEquals(confirmation, d.value)
            assertTrue(f.model.dismissCourseCompletion(confirmation.token, confirmation.nonce))
            assertEquals(0, f.declarations.writes)
        }

    @Test fun `duplicate null receipt during Saving cannot grant a second declaration`() =
        runTest {
            val f = Fixture()
            val receipts = MutableSharedFlow<CourseCompletionDeclaration?>(replay = 1)
            receipts.emit(null)
            f.declarations.querySource = { receipts }
            val release = CompletableDeferred<Unit>()
            f.declarations.declareAction = { _, _, _ ->
                release.await()
                CourseCompletionDeclarationResult.Blocked
            }
            start(f)
            val d = open(f)
            val confirmation = request(f, d)
            assertTrue(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce))
            runCurrent()
            val saving = d.value as SessionHistoryCourseCompletionUiState.Saving
            receipts.emit(null)
            runCurrent()
            assertEquals(saving, d.value)
            assertFalse(f.model.requestCourseCompletion(saving.token))
            assertTrue(checkNotNull(f.declarations.lastGuard).invoke())
            release.complete(Unit)
            runCurrent()
            assertEquals(FailureReason.BLOCKED, failed(d).reason)
            assertEquals(1, f.declarations.writes)
        }

    @Test fun `host denial retires captured consent across a later resume`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val confirmation = request(f, d)
            f.host.set(false)
            assertFalse(f.model.canReadCourseCompletion(confirmation.token))
            f.host.set(true)
            assertFalse(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce))
            assertFalse(f.model.requestCourseCompletion(confirmation.token))
            runCurrent()
            assertTrue(d.value is SessionHistoryCourseCompletionUiState.Unavailable)
            val fresh = open(f)
            assertTrue(absent(fresh).canDeclare)
            assertEquals(0, f.declarations.writes)
        }

    @Test fun `throwing host guard is denial rather than normal permission`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val old = absent(d)
            f.hostFailure = true
            assertFalse(f.model.requestCourseCompletion(old.token))
            f.hostFailure = false
            assertFalse(f.model.requestCourseCompletion(old.token))
            assertEquals(0, f.declarations.writes)
        }

    @Test fun `silent D and UX denials permanently retire the old confirmation`() =
        runTest {
            for (deny in listOf<(Fixture) -> Unit>(
                { it.vehicle.setWithoutEmission(driving()) },
                { it.restrictions.setWithoutEmission(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNKNOWN)) },
            )) {
                val f = Fixture()
                start(f)
                val d = open(f)
                val confirmation = request(f, d)
                deny(f)
                assertFalse(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce))
                f.vehicle.setWithoutEmission(parked())
                f.restrictions.setWithoutEmission(unrestricted())
                runCurrent()
                assertFalse(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce))
                assertFalse(f.model.requestCourseCompletion(confirmation.token))
                assertEquals(0, f.declarations.writes)
            }
        }

    @Test fun `live denial after Saving makes the supplied guard stay false after P returns`() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            var commits = 0
            f.declarations.declareAction = { source, time, guard ->
                release.await()
                if (guard()) {
                    commits++
                    CourseCompletionDeclarationResult.Saved(declaration(source, time = time))
                } else {
                    CourseCompletionDeclarationResult.Blocked
                }
            }
            start(f)
            val d = open(f)
            val confirmation = request(f, d)
            assertTrue(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce))
            runCurrent()
            val guard = checkNotNull(f.declarations.lastGuard)
            assertTrue(guard())
            f.vehicle.setWithoutEmission(driving())
            assertFalse(guard())
            f.vehicle.setWithoutEmission(parked())
            assertFalse(guard())
            release.complete(Unit)
            runCurrent()
            assertEquals(0, commits)
            assertEquals(1, f.declarations.writes)
        }

    @Test fun `another active session arriving after confirmation invalidates consent`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val confirmation = request(f, d)
            f.sessions.values.value += session("active").copy(endedAtEpochMillis = null)
            runCurrent()
            assertFalse(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce))
            val fresh = open(f)
            assertFalse(absent(fresh).canDeclare)
            assertEquals(0, f.declarations.writes)
        }

    @Test fun `same ID metadata replacement cannot reuse the prior source confirmation`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val confirmation = request(f, d)
            val changed = session("a").copy(courseTitle = "new immutable snapshot")
            f.sessions.values.value = listOf(changed, session("b"))
            runCurrent()
            assertFalse(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce))
            assertTrue(d.value is SessionHistoryCourseCompletionUiState.Unavailable)
            assertEquals(changed, absent(open(f)).source)
            assertEquals(0, f.declarations.writes)
        }

    @Test fun `source selection and parent retry retire old confirmation and failure retry`() =
        runTest {
            val f = Fixture()
            start(f)
            val d = open(f)
            val confirmation = request(f, d)
            val source = parent(f)
            assertTrue(f.model.older(source.session.sessionId, source.generation))
            runCurrent()
            assertFalse(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce))
            f.declarations.factoryFailure = IOException("controlled")
            val error = failed(open(f))
            f.declarations.factoryFailure = null
            assertTrue(f.model.retry())
            runCurrent()
            val fresh = open(f)
            assertFalse(f.model.retryCourseCompletion(error.token))
            assertTrue(absent(fresh).canDeclare)
            assertEquals(0, f.declarations.writes)
        }

    @Test fun `parent cancellation and detail disposal revoke the supplied saving guard`() =
        runTest {
            for (cancelParent in listOf(false, true)) {
                val f = Fixture()
                val release = CompletableDeferred<Unit>()
                f.declarations.declareAction = { _, _, _ ->
                    release.await()
                    CourseCompletionDeclarationResult.Blocked
                }
                val parentJob = start(f)
                val d = open(f)
                val confirmation = request(f, d)
                assertTrue(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce))
                runCurrent()
                val guard = checkNotNull(f.declarations.lastGuard)
                if (cancelParent) parentJob.cancelAndJoin() else d.job.cancelAndJoin()
                runCurrent()
                assertFalse(guard())
                assertFalse(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce))
                assertEquals(1, f.declarations.writes)
                release.complete(Unit)
            }
        }

    @Test fun `lost result after a controlled commit restores only the actual row on a fresh read`() =
        runTest {
            val f = Fixture()
            val committed = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            var durable: CourseCompletionDeclaration? = null
            f.declarations.declareAction = { source, time, guard ->
                assertTrue(guard())
                val value = declaration(source, version = "durable-first", time = time)
                durable = value
                f.declarations.querySource = { MutableStateFlow(value) }
                committed.complete(Unit)
                withContext(NonCancellable) { release.await() }
                CourseCompletionDeclarationResult.Saved(value)
            }
            start(f)
            val d = open(f)
            val confirmation = request(f, d)
            assertTrue(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce))
            runCurrent()
            assertTrue(committed.isCompleted)
            d.job.cancel()
            release.complete(Unit)
            d.job.join()
            runCurrent()
            val fresh = open(f)
            assertEquals(durable, ready(fresh).declaration)
            assertNull(ready(fresh).feedback)
            assertFalse(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce))
            assertFalse(f.model.requestCourseCompletion(ready(fresh).token))
            assertEquals(1, f.declarations.writes)
        }

    @Test fun `declaration read failure leaves other stored details and parent source intact`() =
        runTest {
            val f = Fixture()
            start(f)
            val source = parent(f)
            var observations: SessionHistoryObservationUiState? = null
            var memo: SessionHistoryMemoUiState? = null
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model.observeStoredObservations(source.session, source.generation, f::hostAllowed).collect { observations = it }
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model.observeStoredMemo(source.session, source.generation, f::hostAllowed).collect { memo = it }
            }
            f.declarations.factoryFailure = IOException("controlled")
            val error = failed(open(f))
            assertEquals(FailureReason.DECLARATION_READ, error.reason)
            assertTrue(observations is SessionHistoryObservationUiState.Ready)
            assertTrue(memo is SessionHistoryMemoUiState.Empty)
            assertEquals(source.session, parent(f).session)
            assertEquals(0, f.declarations.writes)
        }

    @Test fun `late cancelled read cannot replace a fresh selected source`() =
        runTest {
            val f = Fixture()
            f.declarations.querySource = { lateReceiptOnCancellation() }
            start(f)
            val d = open(f)
            val old = absent(d)
            val source = parent(f)
            assertTrue(f.model.older(source.session.sessionId, source.generation))
            runCurrent()
            val actual = declaration(session("b"))
            f.declarations.querySource = { MutableStateFlow(actual) }
            val fresh = open(f)
            assertEquals(actual, ready(fresh).declaration)
            assertFalse(f.model.requestCourseCompletion(old.token))
            assertEquals(1, f.declarations.active)
            assertEquals(0, f.declarations.writes)
        }

    @Test fun `clock is captured once and a positive timestamp before session start is not corrected`() =
        runTest {
            val repository = Declarations()
            var clockCalls = 0
            val reader =
                SessionHistoryCourseCompletionReader(Any(), repository, { true }) {
                    clockCalls++
                    1L
                }
            var value: SessionHistoryCourseCompletionUiState? = null
            repository.declareAction = { source, time, guard ->
                assertTrue(guard())
                declaration(source, time = time).let { stored ->
                    repository.querySource = { MutableStateFlow(stored) }
                    CourseCompletionDeclarationResult.Saved(stored)
                }
            }
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                reader
                    .observe(session("a"), 1, { true }) {
                        SessionHistoryCourseCompletionReader.Binding({ true }, {}, { true })
                    }.collect { value = it }
            }
            runCurrent()
            assertTrue(reader.request((value as SessionHistoryCourseCompletionUiState.Absent).token))
            runCurrent()
            val confirmation = value as SessionHistoryCourseCompletionUiState.Confirming
            assertTrue(reader.confirm(confirmation.token, confirmation.nonce))
            runCurrent()
            assertEquals(1, clockCalls)
            assertEquals(1L, repository.lastTime)
            assertEquals(1L, (value as SessionHistoryCourseCompletionUiState.Ready).declaration.declaredAtEpochMillis)
            assertEquals(1, repository.writes)
        }

    @Test fun `nonpositive or throwing clock rejects confirmation without entering the repository`() =
        runTest {
            for (clock in listOf<() -> Long>({ 0L }, { -1L }, { throw IOException("controlled clock failure") })) {
                val repository = Declarations()
                val reader = SessionHistoryCourseCompletionReader(Any(), repository, { true }, clock)
                var value: SessionHistoryCourseCompletionUiState? = null
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    reader
                        .observe(session("a"), 1, { true }) {
                            SessionHistoryCourseCompletionReader.Binding({ true }, {}, { true })
                        }.collect { value = it }
                }
                runCurrent()
                assertTrue(reader.request((value as SessionHistoryCourseCompletionUiState.Absent).token))
                runCurrent()
                val confirmation = value as SessionHistoryCourseCompletionUiState.Confirming
                assertFalse(reader.confirm(confirmation.token, confirmation.nonce))
                runCurrent()
                assertEquals(FailureReason.INVALID_TIME, (value as SessionHistoryCourseCompletionUiState.Failed).reason)
                assertEquals(0, repository.writes)
            }
        }

    @Test fun `duplicate null after an unconfirmed write cannot silently become a new absent request`() =
        runTest {
            val f = Fixture()
            val receipts = MutableSharedFlow<CourseCompletionDeclaration?>(replay = 1)
            receipts.emit(null)
            f.declarations.querySource = { receipts }
            f.declarations.declareAction = { _, _, _ -> throw IOException("controlled result loss") }
            start(f)
            val d = open(f)
            val confirmation = request(f, d)
            assertTrue(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce))
            runCurrent()
            val error = failed(d)
            assertEquals(FailureReason.WRITE_UNCONFIRMED, error.reason)
            receipts.emit(null)
            runCurrent()
            assertEquals(error, d.value)
            assertFalse(f.model.requestCourseCompletion(error.token))
            assertTrue(f.model.retryCourseCompletion(error.token))
            runCurrent()
            assertTrue(absent(d).canDeclare)
            assertEquals(1, f.declarations.writes)
        }

    @Test fun `parent read failure retires consent and unknown active status cannot authorize a write`() =
        runTest {
            val f = Fixture()
            val records = MutableSharedFlow<List<PracticeSession>>(replay = 1)
            records.emit(listOf(session("a"), session("b")))
            f.sessions.querySource = { records }
            start(f)
            val d = open(f)
            val confirmation = request(f, d)
            records.emit(listOf(session("a"), session("a")))
            runCurrent()
            assertTrue(f.model.uiState.value is SessionHistoryUiState.Failed)
            assertFalse(f.model.confirmCourseCompletion(confirmation.token, confirmation.nonce))
            assertFalse(f.model.canReadCourseCompletion(confirmation.token))
            assertEquals(0, f.declarations.writes)
        }

    @Test fun `second live read during null publication cannot overwrite Locked with Absent`() =
        runTest {
            val repository = Declarations()
            val receipts = MutableSharedFlow<CourseCompletionDeclaration?>()
            repository.querySource = { receipts }
            var armed = false
            var accessReads = 0
            val reader =
                SessionHistoryCourseCompletionReader(Any(), repository, {
                    !armed || ++accessReads == 1
                })
            val states = mutableListOf<SessionHistoryCourseCompletionUiState>()
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                reader
                    .observe(session("a"), 1, { true }) {
                        SessionHistoryCourseCompletionReader.Binding({ true }, {}, { true })
                    }.collect { states += it }
            }
            runCurrent()
            assertEquals(1, repository.active)
            assertTrue(states.last() is SessionHistoryCourseCompletionUiState.Loading)
            // Receive's context check succeeds, then publication observes a denied live value.
            armed = true
            receipts.emit(null)
            runCurrent()
            assertEquals(2, accessReads)
            assertTrue(states.last() is SessionHistoryCourseCompletionUiState.Locked)
            assertFalse(states.any { it is SessionHistoryCourseCompletionUiState.Absent })
            armed = false
            assertFalse(reader.canRead(states.last().token))
            assertFalse(reader.request(states.last().token))
            assertEquals(0, repository.writes)
        }

    @Test fun `second confirm guard denial preserves Unavailable or Locked without a policy failure`() =
        runTest {
            for (denyHost in listOf(false, true)) {
                val repository = Declarations()
                var armed = false
                var guardReads = 0
                val reader =
                    SessionHistoryCourseCompletionReader(Any(), repository, {
                        !armed || denyHost || ++guardReads == 1
                    })
                val states = mutableListOf<SessionHistoryCourseCompletionUiState>()
                backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                    reader
                        .observe(session("a"), 1, {
                            !armed || !denyHost || ++guardReads == 1
                        }) {
                            SessionHistoryCourseCompletionReader.Binding({ true }, {}, { true })
                        }.collect { states += it }
                }
                runCurrent()
                assertTrue(reader.request((states.last() as SessionHistoryCourseCompletionUiState.Absent).token))
                runCurrent()
                val confirmation = states.last() as SessionHistoryCourseCompletionUiState.Confirming
                val beforeDenial = states.size
                // owner(token) succeeds; canDeclare's next host/live check revokes that same owner.
                armed = true
                assertFalse(reader.confirm(confirmation.token, confirmation.nonce))
                runCurrent()
                assertEquals(2, guardReads)
                if (denyHost) {
                    assertTrue(states.last() is SessionHistoryCourseCompletionUiState.Unavailable)
                } else {
                    assertTrue(states.last() is SessionHistoryCourseCompletionUiState.Locked)
                }
                assertFalse(
                    states.drop(beforeDenial).any {
                        it is SessionHistoryCourseCompletionUiState.Failed ||
                            it is SessionHistoryCourseCompletionUiState.Saving
                    },
                )
                armed = false
                assertFalse(reader.confirm(confirmation.token, confirmation.nonce))
                assertFalse(reader.request(states.last().token))
                assertEquals(0, repository.writes)
            }
        }

    private fun TestScope.start(f: Fixture): Job {
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.model.uiState.collect {} }
        runCurrent()
        return job
    }

    private fun parent(f: Fixture) = f.model.uiState.value as SessionHistoryUiState.Ready

    private fun absent(detail: Detail) = detail.value as SessionHistoryCourseCompletionUiState.Absent

    private fun ready(detail: Detail) = detail.value as SessionHistoryCourseCompletionUiState.Ready

    private fun failed(detail: Detail) = detail.value as SessionHistoryCourseCompletionUiState.Failed

    private fun TestScope.request(
        f: Fixture,
        detail: Detail,
    ): SessionHistoryCourseCompletionUiState.Confirming {
        assertTrue(f.model.requestCourseCompletion(absent(detail).token))
        runCurrent()
        return detail.value as SessionHistoryCourseCompletionUiState.Confirming
    }

    private fun TestScope.open(
        f: Fixture,
        source: PracticeSession = parent(f).session,
        generation: Long = parent(f).generation,
    ): Detail {
        val detail = Detail()
        detail.job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model.observeCourseCompletion(source, generation, f::hostAllowed).collect { detail.value = it }
            }
        runCurrent()
        return detail
    }

    private class Detail {
        lateinit var job: Job
        var value: SessionHistoryCourseCompletionUiState? = null
    }

    private class Fixture {
        val vehicle = SilentLiveState(parked())
        val restrictions = SilentLiveState(unrestricted())
        val sessions = Sessions()
        val declarations = Declarations()
        val host = AtomicBoolean(true)
        var hostFailure = false

        fun hostAllowed(): Boolean = if (hostFailure) throw IOException("controlled host failure") else host.get()

        val model =
            SessionHistoryViewModel(
                object : VehicleGateway {
                    override val state = vehicle
                },
                object : DrivingRestrictionsProvider {
                    override val restrictions = this@Fixture.restrictions
                },
                sessions,
                object : SessionDeletionRepository {
                    override suspend fun prepare(
                        expected: PracticeSession,
                        canRead: () -> Boolean,
                    ): SessionDeletionPreparation = error("No deletion in completion fixture")

                    override suspend fun delete(
                        target: SessionDeletionTarget,
                        canCommit: () -> Boolean,
                    ): SessionDeletionResult = error("No deletion in completion fixture")
                },
                object : VehicleObservationRepository {
                    override fun observe(sessionId: String): Flow<List<SessionVehicleObservation>> =
                        MutableStateFlow(listOf(SessionVehicleObservation(1, sessionId, VehicleObservation(110, parked()))))

                    override suspend fun append(
                        sessionId: String,
                        observation: VehicleObservation,
                    ): VehicleObservationAppendResult = error("No observation write in completion fixture")
                },
                object : SessionMemoRepository {
                    override fun observe(sessionId: String): Flow<SessionMemo?> = MutableStateFlow(null)

                    override suspend fun save(
                        sessionId: String,
                        text: String,
                        expectedVersion: String?,
                        canCommit: () -> Boolean,
                    ): SessionMemoWriteResult = error("No memo write in completion fixture")

                    override suspend fun delete(
                        sessionId: String,
                        expectedVersion: String,
                        canCommit: () -> Boolean,
                    ): SessionMemoWriteResult = error("No memo write in completion fixture")
                },
                UnexpectedHistoryRecordingRunRepository,
                declarations,
            )
    }

    private class Sessions : PracticeSessionRepository {
        val values = MutableStateFlow(listOf(session("a"), session("b")))
        var calls = 0
        var querySource: () -> Flow<List<PracticeSession>> = { values }

        override fun observeSessions(): Flow<List<PracticeSession>> {
            calls++
            return flow { emitAll(querySource()) }
        }

        override suspend fun start(
            practice: PreparedPractice,
            startedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionStartResult = error("Completion must not start a session")

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult = error("Completion must not finish a session")
    }

    private class Declarations : CourseCompletionRepository {
        val values = MutableStateFlow<CourseCompletionDeclaration?>(null)
        var querySource: () -> Flow<CourseCompletionDeclaration?> = { values }
        var factoryFailure: Throwable? = null
        val requested = mutableListOf<String>()
        var reads = 0
        var active = 0
        var writes = 0
        var lastSource: PracticeSession? = null
        var lastTime: Long? = null
        var lastGuard: (() -> Boolean)? = null
        var declareAction: suspend (
            PracticeSession,
            Long,
            () -> Boolean,
        ) -> CourseCompletionDeclarationResult = { _, _, _ -> error("Unexpected declaration") }

        override fun observe(sessionId: String): Flow<CourseCompletionDeclaration?> {
            reads++
            requested += sessionId
            factoryFailure?.let { throw it }
            return flow {
                active++
                try {
                    emitAll(querySource())
                } finally {
                    active--
                }
            }
        }

        override suspend fun declare(
            expectedSource: PracticeSession,
            declaredAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): CourseCompletionDeclarationResult {
            writes++
            lastSource = expectedSource
            lastTime = declaredAtEpochMillis
            lastGuard = canCommit
            return declareAction(expectedSource, declaredAtEpochMillis, canCommit)
        }
    }

    @OptIn(InternalCoroutinesApi::class, ExperimentalForInheritanceCoroutinesApi::class)
    private class SilentLiveState<T>(
        initial: T,
        private val backing: MutableStateFlow<T> = MutableStateFlow(initial),
    ) : StateFlow<T> by backing {
        @Volatile private var current = initial
        override val value: T get() = current

        fun setWithoutEmission(value: T) {
            current = value
        }

        fun emit(value: T) {
            current = value
            backing.value = value
        }
    }

    @OptIn(InternalCoroutinesApi::class)
    private fun lateReceiptOnCancellation(): Flow<CourseCompletionDeclaration?> =
        object : Flow<CourseCompletionDeclaration?> {
            override suspend fun collect(collector: FlowCollector<CourseCompletionDeclaration?>) {
                collector.emit(null)
                try {
                    awaitCancellation()
                } finally {
                    withContext(NonCancellable) { collector.emit(declaration(session("a"), version = "late")) }
                }
            }
        }

    private companion object {
        fun session(id: String) =
            PracticeSession(
                id,
                PracticeSelection("goal", "course", 1, 1, "task"),
                "stored goal",
                "stored course",
                "stored task",
                CourseReviewStatus.DEMO,
                100,
                200,
            )

        fun declaration(
            source: PracticeSession,
            version: String = "first-version",
            time: Long = 300,
        ) = CourseCompletionDeclaration(source, version, time, CourseCompletionOrigin.USER_DECLARATION)

        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        fun driving() = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))

        fun unrestricted() = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
    }
}
