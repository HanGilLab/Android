package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.PreparedPractice
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class SessionMemoViewModelTest {
    @Before fun setMain() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun resetMain() {
        Dispatchers.resetMain()
    }

    @Test fun `constructing an editor flow never starts a database query or accepts an invisible mutation`() =
        runTest {
            val fixture = Fixture()
            fixture.vm.observe("a")
            runCurrent()
            assertEquals(0, fixture.sessions.calls)
            assertTrue(fixture.memos.requests.isEmpty())
            assertFalse(fixture.vm.edit("a", "draft"))
            assertFalse(fixture.vm.save("a"))
            assertFalse(fixture.vm.retry("a"))
        }

    @Test fun `every unconfirmed parked or UX condition keeps database reads and editing locked`() =
        runTest {
            val denied =
                listOf(
                    parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE)) to unrestricted(),
                    parked().copy(connection = ConnectionState.DISCONNECTED) to unrestricted(),
                    parked().copy(connection = ConnectionState.PERMISSION_DENIED) to unrestricted(),
                    parked().copy(gear = VehicleReading.Unknown) to unrestricted(),
                    parked().copy(gear = VehicleReading.Unsupported) to unrestricted(),
                    parked() to unrestricted().copy(uxRestrictionState = UxRestrictionState.RESTRICTED),
                    parked() to unrestricted().copy(uxRestrictionState = UxRestrictionState.UNKNOWN),
                )
            for ((vehicle, restrictions) in denied) {
                val fixture = Fixture()
                fixture.vehicle.state.value = vehicle
                fixture.restrictions.restrictions.value = restrictions
                val trace = watch(fixture)
                runCurrent()
                assertEquals(SessionMemoUiState.Load.Locked, trace.last().load)
                assertEquals(0, fixture.sessions.calls)
                assertTrue(fixture.memos.requests.isEmpty())
                assertFalse(fixture.vm.beginEdit("a"))
                assertFalse(fixture.vm.edit("a", "draft"))
                assertFalse(fixture.vm.save("a"))
                assertFalse(fixture.vm.delete("a", "v1"))
                assertFalse(fixture.vm.retry("a"))
                trace.job.cancelAndJoin()
            }
        }

    @Test fun `an explicitly read missing memo opens an empty unsaved editor`() =
        runTest {
            val fixture = Fixture()
            val trace = watch(fixture)
            runCurrent()
            assertEquals(SessionMemoUiState.Load.Ready, trace.last().load)
            assertEquals(null, trace.last().memo)
            assertEquals("", trace.last().draft)
            assertTrue(trace.last().editing)
            assertFalse(trace.last().canSave)
            assertFalse(trace.last().canDelete)
            assertEquals(null, trace.last().feedback)
        }

    @Test fun `stored memo stays in view mode until edit and save preserves the exact Unicode draft`() =
        runTest {
            val fixture = Fixture(memo())
            val trace = watch(fixture)
            runCurrent()
            assertFalse(trace.last().editing)
            assertFalse(fixture.vm.edit("a", "implicit edit"))
            assertTrue(fixture.vm.beginEdit("a"))
            assertTrue(fixture.vm.edit("a", "discard this ordinary draft"))
            assertTrue(fixture.vm.cancelEdit("a"))
            runCurrent()
            assertEquals("saved text", trace.last().draft)
            assertEquals("v1", trace.last().expectedVersion)
            assertFalse(trace.last().editing)
            assertTrue(fixture.vm.beginEdit("a"))
            val exact = "  한 줄\n다음 줄 🚗  "
            assertTrue(fixture.vm.edit("a", exact))
            assertTrue(fixture.vm.save("a"))
            runCurrent()
            assertEquals(
                exact,
                fixture.memos.saved
                    .single()
                    .text,
            )
            assertEquals(
                "v1",
                fixture.memos.saved
                    .single()
                    .expected,
            )
            assertEquals(exact, trace.last().memo?.text)
            assertEquals(exact, trace.last().draft)
            assertFalse(trace.last().editing)
            assertEquals(SessionMemoUiState.Feedback.Saved, trace.last().feedback)
            assertEquals(SessionMemoUiState.Operation.Idle, trace.last().operation)
        }

    @Test fun `blank NUL isolated surrogate and overlong drafts never become writes or automatic deletes`() =
        runTest {
            val fixture = Fixture()
            val trace = watch(fixture)
            runCurrent()
            for (invalid in listOf("", " \n ", "x\u0000y", "\uD800", "\uDC00", "가".repeat(281))) {
                assertTrue(fixture.vm.edit("a", invalid))
                assertFalse(fixture.vm.save("a"))
                assertEquals(invalid, traceAfterCurrent(trace).draft)
                assertEquals(SessionMemoUiState.Feedback.InvalidText, trace.last().feedback)
            }
            assertTrue(fixture.memos.saved.isEmpty())
            assertTrue(fixture.memos.deleted.isEmpty())
        }

    @Test fun `read failure removes write permission while retry preserves draft and optimistic token`() =
        runTest {
            val fixture = Fixture(memo())
            val fail = CompletableDeferred<Unit>()
            fixture.memos.source = {
                flow {
                    emit(memo())
                    fail.await()
                    throw IOException("fixture read failure")
                }
            }
            val trace = watch(fixture)
            runCurrent()
            fixture.vm.beginEdit("a")
            fixture.vm.edit("a", "unsaved")
            fail.complete(Unit)
            runCurrent()
            assertEquals(SessionMemoUiState.Load.Failed, trace.last().load)
            assertEquals(SessionMemoUiState.FailureReason.MemoRead, trace.last().failure)
            assertEquals("unsaved", trace.last().draft)
            assertEquals("v1", trace.last().expectedVersion)
            assertFalse(fixture.vm.save("a"))
            fixture.memos.source = { fixture.memos.values(it) }
            assertTrue(fixture.vm.retry("a"))
            runCurrent()
            assertEquals(SessionMemoUiState.Load.Ready, trace.last().load)
            assertEquals("unsaved", trace.last().draft)
            assertEquals("v1", trace.last().expectedVersion)
        }

    @Test fun `normal memo flow completion fails instead of leaving a writable successful snapshot`() =
        runTest {
            val fixture = Fixture()
            val complete = CompletableDeferred<Unit>()
            fixture.memos.source = {
                flow {
                    emit(null)
                    complete.await()
                }
            }
            val trace = watch(fixture)
            runCurrent()
            fixture.vm.edit("a", "draft")
            complete.complete(Unit)
            runCurrent()
            assertEquals(SessionMemoUiState.FailureReason.MemoStreamCompleted, trace.last().failure)
            assertFalse(trace.last().canSave)
            assertEquals(0, fixture.memos.active)
        }

    @Test fun `normal session completion cancels an infinite memo query and fails explicitly`() =
        runTest {
            val fixture = Fixture()
            val complete = CompletableDeferred<Unit>()
            fixture.sessions.source = {
                flow {
                    emit(listOf(session("a")))
                    complete.await()
                }
            }
            val trace = watch(fixture)
            runCurrent()
            assertEquals(1, fixture.memos.active)
            complete.complete(Unit)
            runCurrent()
            assertEquals(SessionMemoUiState.FailureReason.SessionStreamCompleted, trace.last().failure)
            assertEquals(0, fixture.memos.active)
            assertTrue(trace.job.isActive)
        }

    @Test fun `internal memo and session cancellations are retryable failures while caller cancellation remains distinct`() =
        runTest {
            for (sessionFailure in listOf(false, true)) {
                val fixture = Fixture()
                if (sessionFailure) {
                    fixture.sessions.source = { flow { throw CancellationException("fixture internal cancellation") } }
                } else {
                    fixture.memos.source = { flow { throw CancellationException("fixture internal cancellation") } }
                }
                val trace = watch(fixture)
                runCurrent()
                assertEquals(SessionMemoUiState.Load.Failed, trace.last().load)
                assertEquals(
                    if (sessionFailure) SessionMemoUiState.FailureReason.SessionRead else SessionMemoUiState.FailureReason.MemoRead,
                    trace.last().failure,
                )
                assertTrue(trace.job.isActive)
                fixture.sessions.source = { fixture.sessions.values }
                fixture.memos.source = { fixture.memos.values(it) }
                assertTrue(fixture.vm.retry("a"))
                runCurrent()
                assertEquals(SessionMemoUiState.Load.Ready, trace.last().load)
                trace.job.cancelAndJoin()
            }
        }

    @Test fun `closing releases every query propagates cancellation and preserves the same session draft on reopen`() =
        runTest {
            val fixture = Fixture()
            val trace = watch(fixture)
            runCurrent()
            fixture.vm.edit("a", "draft across close")
            var completed: Throwable? = null
            trace.job.invokeOnCompletion { completed = it }
            val cause = CancellationException("fixture editor removed")
            trace.job.cancel(cause)
            trace.job.join()
            assertSame(cause, completed)
            assertEquals(0, fixture.sessions.active)
            assertEquals(0, fixture.memos.active)
            assertFalse(fixture.vm.save("a"))
            val reopened = watch(fixture)
            runCurrent()
            assertEquals("draft across close", reopened.last().draft)
            assertEquals(null, reopened.last().feedback)
            assertEquals(1, fixture.memos.maximumActive)
        }

    @Test fun `new latest session cancels the old query and never copies its draft into the new editor`() =
        runTest {
            val fixture = Fixture()
            val old = watch(fixture)
            runCurrent()
            fixture.vm.edit("a", "only session A")
            fixture.sessions.values.value = listOf(session("b"), session("a"))
            runCurrent()
            assertEquals(SessionMemoUiState.Load.SessionChanged, old.last().load)
            assertEquals(0, fixture.memos.active)
            assertFalse(fixture.vm.save("a"))
            old.job.cancelAndJoin()
            val fresh = watch(fixture, "b")
            runCurrent()
            assertEquals("b", fresh.last().sessionId)
            assertEquals("", fresh.last().draft)
            assertEquals(null, fresh.last().memo)
            assertTrue(fresh.states.all { it.sessionId == "b" })
        }

    @Test fun `driving cancels reads and rejects actions then parked recovery preserves the draft`() =
        runTest {
            val fixture = Fixture(memo())
            val trace = watch(fixture)
            runCurrent()
            fixture.vm.beginEdit("a")
            fixture.vm.edit("a", "keep while driving")
            fixture.vehicle.state.value = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))
            runCurrent()
            assertEquals(SessionMemoUiState.Load.Locked, trace.last().load)
            assertEquals(0, fixture.memos.active)
            assertEquals(0, fixture.sessions.active)
            assertFalse(fixture.vm.edit("a", "blocked"))
            assertFalse(fixture.vm.save("a"))
            fixture.vehicle.state.value = parked()
            runCurrent()
            assertEquals("keep while driving", trace.last().draft)
            assertEquals("v1", trace.last().expectedVersion)
            assertTrue(trace.last().canSave)
        }

    @Test fun `closing a pending save invalidates its transaction callback and never reports success`() =
        runTest {
            val fixture = Fixture()
            val release = CompletableDeferred<Unit>()
            fixture.memos.saveAction =
                { request ->
                    release.await()
                    if (request.canCommit()) SessionMemoWriteResult.Saved(memo(text = request.text)) else SessionMemoWriteResult.Blocked
                }
            val trace = watch(fixture)
            runCurrent()
            fixture.vm.edit("a", "pending draft")
            fixture.vm.save("a")
            runCurrent()
            assertEquals(SessionMemoUiState.Operation.Saving, trace.last().operation)
            trace.job.cancelAndJoin()
            assertFalse(
                fixture.memos.saved
                    .single()
                    .canCommit(),
            )
            release.complete(Unit)
            runCurrent()
            val reopened = watch(fixture)
            runCurrent()
            assertEquals("pending draft", reopened.last().draft)
            assertEquals(null, reopened.last().memo)
            assertEquals(SessionMemoUiState.Feedback.Blocked, reopened.last().feedback)
        }

    @Test fun `collector cancellation invalidates the write callback before cancellation cleanup is dispatched`() =
        runTest {
            val fixture = Fixture()
            val release = CompletableDeferred<Unit>()
            fixture.memos.saveAction = {
                release.await()
                SessionMemoWriteResult.Blocked
            }
            val trace = watch(fixture)
            runCurrent()
            fixture.vm.edit("a", "pending draft")
            fixture.vm.save("a")
            runCurrent()
            val request = fixture.memos.saved.single()
            assertTrue(request.canCommit())
            trace.job.cancel()
            assertFalse(request.canCommit())
            assertFalse(fixture.vm.edit("a", "too late"))
            assertFalse(fixture.vm.retry("a"))
            trace.job.join()
            release.complete(Unit)
            runCurrent()
        }

    @Test fun `recreating the same editor gives a new reader and never reactivates an old pending callback`() =
        runTest {
            val fixture = Fixture()
            val release = CompletableDeferred<Unit>()
            fixture.memos.saveAction =
                { request ->
                    release.await()
                    if (request.canCommit()) SessionMemoWriteResult.Saved(memo(text = request.text)) else SessionMemoWriteResult.Blocked
                }
            val original = watch(fixture)
            runCurrent()
            fixture.vm.edit("a", "draft across recreation")
            fixture.vm.save("a")
            runCurrent()
            val oldRequest = fixture.memos.saved.single()
            original.job.cancelAndJoin()
            val recreated = watch(fixture)
            runCurrent()
            assertEquals("draft across recreation", recreated.last().draft)
            assertFalse(oldRequest.canCommit())
            release.complete(Unit)
            runCurrent()
            assertFalse(oldRequest.canCommit())
            assertEquals("draft across recreation", recreated.last().draft)
            assertEquals(null, recreated.last().memo)
            assertEquals(SessionMemoUiState.Feedback.Blocked, recreated.last().feedback)
            assertEquals(SessionMemoUiState.Operation.Idle, recreated.last().operation)
        }

    @Test fun `a pending save remains invalid after D then P rather than becoming valid again`() =
        runTest {
            val fixture = Fixture()
            val release = CompletableDeferred<Unit>()
            fixture.memos.saveAction =
                { request ->
                    release.await()
                    if (request.canCommit()) SessionMemoWriteResult.Saved(memo(text = request.text)) else SessionMemoWriteResult.Blocked
                }
            val trace = watch(fixture)
            runCurrent()
            fixture.vm.edit("a", "pending")
            fixture.vm.save("a")
            runCurrent()
            val request = fixture.memos.saved.single()
            assertTrue(request.canCommit())
            fixture.vehicle.state.value = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))
            runCurrent()
            assertFalse(request.canCommit())
            fixture.vehicle.state.value = parked()
            runCurrent()
            assertFalse(request.canCommit())
            release.complete(Unit)
            runCurrent()
            assertEquals(SessionMemoUiState.Feedback.Blocked, trace.last().feedback)
            assertEquals("pending", trace.last().draft)
            assertEquals(null, trace.last().memo)
        }

    @Test fun `late save response for A never changes the B draft or publishes success there`() =
        runTest {
            val fixture = Fixture()
            val release = CompletableDeferred<Unit>()
            fixture.memos.saveAction = { request ->
                release.await()
                SessionMemoWriteResult.Saved(memo(text = request.text))
            }
            val old = watch(fixture)
            runCurrent()
            fixture.vm.edit("a", "draft A")
            fixture.vm.save("a")
            runCurrent()
            fixture.sessions.values.value = listOf(session("b"), session("a"))
            runCurrent()
            old.job.cancelAndJoin()
            val fresh = watch(fixture, "b")
            runCurrent()
            fixture.vm.edit("b", "draft B")
            assertFalse(
                fixture.memos.saved
                    .single()
                    .canCommit(),
            )
            release.complete(Unit)
            runCurrent()
            assertEquals("draft B", fresh.last().draft)
            assertEquals(null, fresh.last().memo)
            assertEquals(null, fresh.last().feedback)
        }

    @Test fun `the mutation mutex rejects double save delete and reload while one action is pending`() =
        runTest {
            val fixture = Fixture(memo())
            val release = CompletableDeferred<Unit>()
            fixture.memos.saveAction = {
                release.await()
                SessionMemoWriteResult.Blocked
            }
            val trace = watch(fixture)
            runCurrent()
            fixture.vm.beginEdit("a")
            fixture.vm.edit("a", "pending")
            assertTrue(fixture.vm.save("a"))
            assertFalse(fixture.vm.save("a"))
            assertFalse(fixture.vm.delete("a", "v1"))
            assertFalse(fixture.vm.reload("a"))
            assertFalse(fixture.vm.cancelEdit("a"))
            runCurrent()
            assertEquals(1, fixture.memos.saved.size)
            assertEquals(SessionMemoUiState.Operation.Saving, trace.last().operation)
            release.complete(Unit)
            runCurrent()
            assertEquals(SessionMemoUiState.Operation.Idle, trace.last().operation)
        }

    @Test fun `optimistic conflict preserves the exact draft and old token until explicit discard reload`() =
        runTest {
            val fixture = Fixture(memo())
            fixture.memos.saveAction = { SessionMemoWriteResult.Conflict }
            val trace = watch(fixture)
            runCurrent()
            fixture.vm.beginEdit("a")
            fixture.vm.edit("a", "  unsaved\n🚗  ")
            fixture.vm.save("a")
            runCurrent()
            assertEquals(SessionMemoUiState.Feedback.Conflict, trace.last().feedback)
            assertTrue(trace.last().needsReload)
            assertEquals("v1", trace.last().expectedVersion)
            assertEquals("  unsaved\n🚗  ", trace.last().draft)
            assertFalse(fixture.vm.save("a"))
            assertFalse(fixture.vm.delete("a", "v1"))
            fixture.memos.values("a").value = memo(text = "other saved text", version = "v2")
            fixture.vm.retry("a")
            runCurrent()
            assertEquals("  unsaved\n🚗  ", trace.last().draft)
            assertTrue(trace.last().needsReload)
            assertEquals("v1", trace.last().expectedVersion)
            fixture.vm.reload("a")
            runCurrent()
            assertEquals("other saved text", trace.last().draft)
            assertEquals("v2", trace.last().expectedVersion)
            assertFalse(trace.last().editing)
            assertFalse(trace.last().needsReload)
        }

    @Test fun `external memo updates preserve an open draft until explicit fresh reload`() =
        runTest {
            val fixture = Fixture(memo())
            val trace = watch(fixture)
            runCurrent()
            fixture.vm.beginEdit("a")
            fixture.vm.edit("a", "local draft")
            fixture.memos.values("a").value = memo(text = "external saved", version = "v2")
            runCurrent()
            assertTrue(trace.last().needsReload)
            assertEquals("local draft", trace.last().draft)
            assertEquals("v1", trace.last().expectedVersion)
            assertFalse(fixture.vm.cancelEdit("a"))
            runCurrent()
            assertEquals("local draft", trace.last().draft)
            assertEquals("v1", trace.last().expectedVersion)
            assertTrue(trace.last().needsReload)
            assertTrue(fixture.vm.reload("a"))
            runCurrent()
            assertEquals("external saved", trace.last().draft)
            assertEquals("v2", trace.last().expectedVersion)
            assertFalse(trace.last().editing)
            assertFalse(trace.last().needsReload)
        }

    @Test fun `conflict cannot discard the draft or bypass a fresh reload through cancel edit`() =
        runTest {
            val fixture = Fixture(memo())
            fixture.memos.saveAction = { SessionMemoWriteResult.Conflict }
            val trace = watch(fixture)
            runCurrent()
            fixture.vm.beginEdit("a")
            fixture.vm.edit("a", "keep conflicted draft")
            fixture.vm.save("a")
            runCurrent()
            assertEquals(SessionMemoUiState.Feedback.Conflict, trace.last().feedback)
            assertTrue(trace.last().needsReload)
            assertFalse(fixture.vm.cancelEdit("a"))
            runCurrent()
            assertEquals("keep conflicted draft", trace.last().draft)
            assertEquals("v1", trace.last().expectedVersion)
            assertTrue(trace.last().needsReload)
            assertFalse(fixture.vm.save("a"))
        }

    @Test fun `confirmation of an old memo token cannot delete the newer memo visible before the click`() =
        runTest {
            val fixture = Fixture(memo())
            val trace = watch(fixture)
            runCurrent()
            val confirmedVersion = checkNotNull(trace.last().memo).version
            val newer = memo(text = "newer saved text", version = "v2")
            fixture.memos.values("a").value = newer
            runCurrent()
            assertEquals("v1", confirmedVersion)
            assertEquals("v2", trace.last().memo?.version)
            val accepted = fixture.vm.delete("a", confirmedVersion)
            runCurrent()
            assertFalse(accepted)
            assertTrue(fixture.memos.deleted.isEmpty())
            assertEquals(newer, fixture.memos.values("a").value)
            assertEquals(SessionMemoUiState.Feedback.Conflict, trace.last().feedback)
            assertTrue(trace.last().needsReload)
            assertFalse(fixture.vm.delete("a", newer.version))
            assertTrue(fixture.vm.reload("a"))
            runCurrent()
            assertFalse(trace.last().needsReload)
            assertTrue(fixture.vm.delete("a", newer.version))
            runCurrent()
            assertEquals(
                newer.version,
                fixture.memos.deleted
                    .single()
                    .expected,
            )
            assertEquals(null, fixture.memos.values("a").value)
            assertEquals(SessionMemoUiState.Feedback.Deleted, trace.last().feedback)
        }

    @Test fun `own Room emission before save response never becomes an external conflict or premature success`() =
        runTest {
            val fixture = Fixture(memo())
            val release = CompletableDeferred<Unit>()
            fixture.memos.saveAction = { request ->
                val saved = memo(text = request.text, version = "v2")
                fixture.memos.values("a").value = saved
                release.await()
                SessionMemoWriteResult.Saved(saved)
            }
            val trace = watch(fixture)
            runCurrent()
            fixture.vm.beginEdit("a")
            fixture.vm.edit("a", "my write")
            fixture.vm.save("a")
            runCurrent()
            assertEquals("v2", trace.last().memo?.version)
            assertEquals("v1", trace.last().expectedVersion)
            assertEquals(SessionMemoUiState.Operation.Saving, trace.last().operation)
            assertFalse(trace.last().needsReload)
            assertEquals(null, trace.last().feedback)
            release.complete(Unit)
            runCurrent()
            assertEquals("v2", trace.last().expectedVersion)
            assertEquals(SessionMemoUiState.Feedback.Saved, trace.last().feedback)
            assertFalse(trace.last().needsReload)
            assertFalse(trace.last().editing)
        }

    @Test fun `explicit deletion uses the saved token and opens an empty editor only after Deleted`() =
        runTest {
            val fixture = Fixture(memo())
            val trace = watch(fixture)
            runCurrent()
            fixture.vm.beginEdit("a")
            fixture.vm.edit("a", "")
            assertFalse(fixture.vm.save("a"))
            assertTrue(fixture.memos.deleted.isEmpty())
            assertTrue(fixture.vm.delete("a", "v1"))
            runCurrent()
            assertEquals(
                "v1",
                fixture.memos.deleted
                    .single()
                    .expected,
            )
            assertEquals(null, trace.last().memo)
            assertEquals("", trace.last().draft)
            assertTrue(trace.last().editing)
            assertEquals(SessionMemoUiState.Feedback.Deleted, trace.last().feedback)
        }

    @Test fun `write exceptions preserve unsaved text and distinguish save from delete failure`() =
        runTest {
            val fixture = Fixture(memo())
            fixture.memos.saveAction = { throw IOException("fixture write failed") }
            fixture.memos.deleteAction = { throw IOException("fixture delete failed") }
            val trace = watch(fixture)
            runCurrent()
            fixture.vm.beginEdit("a")
            fixture.vm.edit("a", "preserve")
            fixture.vm.save("a")
            runCurrent()
            assertEquals(SessionMemoUiState.Feedback.SaveFailed, trace.last().feedback)
            assertEquals("preserve", trace.last().draft)
            assertEquals("saved text", trace.last().memo?.text)
            fixture.vm.delete("a", "v1")
            runCurrent()
            assertEquals(SessionMemoUiState.Feedback.DeleteFailed, trace.last().feedback)
            assertEquals("preserve", trace.last().draft)
            assertEquals("v1", trace.last().expectedVersion)
        }

    @Test fun `wrong session invalid text and blank version reads are errors not writable empty memos`() =
        runTest {
            for (invalid in listOf(memo(sessionId = "b"), memo(text = " "), memo(version = ""), memo(text = "\uD800"))) {
                val fixture = Fixture(invalid)
                val trace = watch(fixture)
                runCurrent()
                assertEquals(SessionMemoUiState.Load.Failed, trace.last().load)
                assertEquals(SessionMemoUiState.FailureReason.InvalidMemo, trace.last().failure)
                assertFalse(fixture.vm.edit("a", "must not overwrite"))
                assertFalse(fixture.vm.save("a"))
                trace.job.cancelAndJoin()
            }
        }

    @Test fun `failed explicit reload retains the draft until a successful refreshed read discards it`() =
        runTest {
            val fixture = Fixture(memo())
            val trace = watch(fixture)
            runCurrent()
            fixture.vm.beginEdit("a")
            fixture.vm.edit("a", "keep if reload fails")
            fixture.memos.factoryFailure = IOException("fixture reload failure")
            fixture.vm.reload("a")
            runCurrent()
            assertEquals(SessionMemoUiState.Load.Failed, trace.last().load)
            assertEquals("keep if reload fails", trace.last().draft)
            fixture.memos.factoryFailure = null
            fixture.vm.retry("a")
            runCurrent()
            assertEquals("saved text", trace.last().draft)
            assertFalse(trace.last().editing)
        }

    @Test fun `a delayed old memo result is cancelled when the latest session changes`() =
        runTest {
            val fixture = Fixture()
            val delayed = CompletableDeferred<SessionMemo?>()
            fixture.memos.source = { flow { emit(delayed.await()) } }
            val trace = watch(fixture)
            runCurrent()
            assertEquals(1, fixture.memos.active)
            fixture.sessions.values.value = listOf(session("b"), session("a"))
            runCurrent()
            assertEquals(SessionMemoUiState.Load.SessionChanged, trace.last().load)
            assertEquals(0, fixture.memos.active)
            delayed.complete(memo())
            runCurrent()
            assertEquals(null, trace.last().memo)
            assertFalse(trace.states.any { it.load == SessionMemoUiState.Load.Ready })
        }

    @Test fun `every explicit repository rejection preserves the draft and never publishes success`() =
        runTest {
            for (result in listOf(
                SessionMemoWriteResult.Blocked,
                SessionMemoWriteResult.SessionChanged,
                SessionMemoWriteResult.MissingSession,
                SessionMemoWriteResult.SessionActive,
                SessionMemoWriteResult.Conflict,
                SessionMemoWriteResult.InvalidText,
            )) {
                val fixture = Fixture()
                fixture.memos.saveAction = { result }
                val trace = watch(fixture)
                runCurrent()
                fixture.vm.edit("a", "keep rejected draft")
                fixture.vm.save("a")
                runCurrent()
                assertEquals("keep rejected draft", trace.last().draft)
                assertEquals(null, trace.last().memo)
                assertFalse(trace.last().feedback == SessionMemoUiState.Feedback.Saved)
                assertEquals(SessionMemoUiState.Operation.Idle, trace.last().operation)
                trace.job.cancelAndJoin()
            }
        }

    private fun TestScope.traceAfterCurrent(trace: Trace): SessionMemoUiState {
        runCurrent()
        return trace.last()
    }

    private fun TestScope.watch(
        fixture: Fixture,
        sessionId: String = "a",
    ): Trace {
        val states = mutableListOf<SessionMemoUiState>()
        val job = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { fixture.vm.observe(sessionId).collect { states += it } }
        return Trace(states, job)
    }

    private data class Trace(
        val states: MutableList<SessionMemoUiState>,
        val job: Job,
    ) {
        fun last() = states.last()
    }

    private class Fixture(
        initial: SessionMemo? = null,
    ) {
        val vehicle = FakeVehicle()
        val restrictions = FakeRestrictions()
        val sessions = FakeSessions()
        val memos = FakeMemos(initial)
        val vm = SessionMemoViewModel(vehicle, restrictions, sessions, memos)
    }

    private class FakeVehicle : VehicleGateway {
        override val state = MutableStateFlow(parked())
    }

    private class FakeRestrictions : DrivingRestrictionsProvider {
        override val restrictions = MutableStateFlow(unrestricted())
    }

    private class FakeSessions : PracticeSessionRepository {
        val values = MutableStateFlow(listOf(session("a")))
        var source: () -> Flow<List<PracticeSession>> = { values }
        var calls = 0
        var active = 0

        override fun observeSessions(): Flow<List<PracticeSession>> {
            calls++
            return flow {
                active++
                try {
                    emitAll(source())
                } finally {
                    active--
                }
            }
        }

        override suspend fun start(
            practice: PreparedPractice,
            startedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionStartResult = error("Memo editor never starts sessions")

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult = error("Memo editor never finishes sessions")
    }

    private data class SaveRequest(
        val sessionId: String,
        val text: String,
        val expected: String?,
        val canCommit: () -> Boolean,
    )

    private data class DeleteRequest(
        val sessionId: String,
        val expected: String,
        val canCommit: () -> Boolean,
    )

    private class FakeMemos(
        initial: SessionMemo?,
    ) : SessionMemoRepository {
        private val entries = mutableMapOf("a" to MutableStateFlow(initial))
        val requests = mutableListOf<String>()
        val saved = mutableListOf<SaveRequest>()
        val deleted = mutableListOf<DeleteRequest>()
        var active = 0
        var maximumActive = 0
        var factoryFailure: Exception? = null
        var source: (String) -> Flow<SessionMemo?> = { values(it) }
        var saveAction: suspend (SaveRequest) -> SessionMemoWriteResult = { request ->
            if (!request.canCommit()) {
                SessionMemoWriteResult.Blocked
            } else {
                val savedMemo = memo(request.sessionId, request.text, "v2")
                values(request.sessionId).value = savedMemo
                SessionMemoWriteResult.Saved(savedMemo)
            }
        }
        var deleteAction: suspend (DeleteRequest) -> SessionMemoWriteResult = { request ->
            if (!request.canCommit()) {
                SessionMemoWriteResult.Blocked
            } else {
                values(request.sessionId).value = null
                SessionMemoWriteResult.Deleted
            }
        }

        fun values(sessionId: String) = entries.getOrPut(sessionId) { MutableStateFlow<SessionMemo?>(null) }

        override fun observe(sessionId: String): Flow<SessionMemo?> {
            requests += sessionId
            factoryFailure?.let { throw it }
            return flow {
                active++
                maximumActive = maxOf(maximumActive, active)
                try {
                    emitAll(source(sessionId))
                } finally {
                    active--
                }
            }
        }

        override suspend fun save(
            sessionId: String,
            text: String,
            expectedVersion: String?,
            canCommit: () -> Boolean,
        ): SessionMemoWriteResult {
            val request = SaveRequest(sessionId, text, expectedVersion, canCommit)
            saved += request
            return saveAction(request)
        }

        override suspend fun delete(
            sessionId: String,
            expectedVersion: String,
            canCommit: () -> Boolean,
        ): SessionMemoWriteResult {
            val request = DeleteRequest(sessionId, expectedVersion, canCommit)
            deleted += request
            return deleteAction(request)
        }
    }

    private companion object {
        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        fun unrestricted() = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)

        fun memo(
            sessionId: String = "a",
            text: String = "saved text",
            version: String = "v1",
        ) = SessionMemo(sessionId, text, version)

        fun session(id: String) =
            PracticeSession(
                id,
                PracticeSelection("goal", "course", 1, 1, "task"),
                "Fixture goal",
                "Fixture course",
                "Fixture task",
                CourseReviewStatus.DEMO,
                100,
                200,
            )
    }
}
