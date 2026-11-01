package io.github.hangillab.hangil.feature.home

import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.HistorySessionMemoWriter
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
import io.github.hangillab.hangil.feature.home.SessionHistoryMemoUiState.FailureReason
import io.github.hangillab.hangil.feature.home.SessionHistoryMemoUiState.Feedback
import io.github.hangillab.hangil.feature.home.SessionHistoryMemoUiState.Token
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.CoroutineContext

/** Production history VM with controlled source, lifecycle guard and writer; no real SDK or DB. */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionHistoryMemoEditViewModelTest {
    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test fun `cold and parentless detail cannot read or begin an edit`() =
        runTest {
            val f = Fixture()
            f.model.observeStoredMemo(session("a"), 1, f.host::get)
            runCurrent()
            assertEquals(0, f.sessions.reads)
            assertEquals(0, f.memos.reads)
            val detail = open(f, session("a"), 1)
            assertTrue(detail.value is SessionHistoryMemoUiState.Unavailable)
            assertFalse(f.model.beginStoredMemoEdit(checkNotNull(detail.value).token))
            assertEquals(0, f.writer.calls)
        }

    @Test fun `only an existing Ready receipt with confirmed no active session permits editing`() =
        runTest {
            val f = Fixture()
            f.memos.values.value = null
            start(f)
            val detail = open(f)
            assertTrue(detail.value is SessionHistoryMemoUiState.Empty)
            assertFalse(f.model.beginStoredMemoEdit(checkNotNull(detail.value).token))
            f.memos.values.value = memo()
            runCurrent()
            assertTrue(ready(detail).canEdit)
            f.sessions.values.value += session("active").copy(endedAtEpochMillis = null)
            runCurrent()
            val fresh = open(f)
            assertFalse(ready(fresh).canEdit)
            assertFalse(f.model.beginStoredMemoEdit(ready(fresh).token))
            assertEquals(0, f.writer.calls)
        }

    @Test fun `an older memo shares the selected full source without a second parent subscription`() =
        runTest {
            val f = Fixture()
            start(f)
            val newest = parent(f)
            assertTrue(f.model.older(newest.session.sessionId, newest.generation))
            runCurrent()
            f.memos.values.value = memo("b")
            val detail = open(f)
            assertEquals(session("b"), ready(detail).source)
            assertEquals(listOf("b"), f.memos.requested)
            assertEquals(1, f.sessions.reads)
            assertTrue(f.model.beginStoredMemoEdit(ready(detail).token))
            runCurrent()
            assertEquals(session("b"), editing(detail).source)
            assertEquals(1, f.memos.reads)
            assertEquals(0, f.writer.calls)
        }

    @Test fun `duplicate identical memo receipts preserve exact draft token and edit nonce`() =
        runTest {
            val f = Fixture()
            val receipts = MutableSharedFlow<SessionMemo?>(replay = 1)
            receipts.emit(memo())
            f.memos.querySource = { receipts }
            start(f)
            val detail = open(f)
            val edit = begin(f, detail)
            val draft = "  다듬는 초안 🙂\n끝 "
            assertTrue(f.model.editStoredMemo(edit.token, edit.nonce, draft))
            runCurrent()
            val displayed = editing(detail)
            receipts.emit(memo())
            runCurrent()
            assertEquals(displayed, detail.value)
            assertEquals(draft, editing(detail).draft)
            assertEquals(1, f.memos.reads)
            assertEquals(0, f.writer.calls)
        }

    @Test fun `a changed whole memo retires consent preserves draft and waits for explicit reload`() =
        runTest {
            for (replacement in listOf(
                memo().copy(version = "changed-version"),
                memo().copy(text = "same version different stored text"),
            )) {
                val f = Fixture()
                start(f)
                val detail = open(f)
                val edit = begin(f, detail)
                assertTrue(f.model.editStoredMemo(edit.token, edit.nonce, "keep this exact draft"))
                f.memos.values.value = replacement
                runCurrent()
                val error = failed(detail)
                assertEquals(FailureReason.CONFLICT, error.reason)
                assertEquals("keep this exact draft", error.retainedDraft)
                assertFalse(f.model.editStoredMemo(edit.token, edit.nonce, "old input"))
                assertFalse(f.model.saveStoredMemo(edit.token, edit.nonce, "keep this exact draft"))
                f.memos.values.value = memo()
                runCurrent()
                assertEquals(error, detail.value)
                assertTrue(f.model.retryStoredMemo(error.token))
                runCurrent()
                val fresh = begin(f, detail)
                assertNotEquals(edit.nonce, fresh.nonce)
                assertFalse(f.model.saveStoredMemo(fresh.token, edit.nonce, fresh.draft))
                assertEquals(0, f.writer.calls)
            }
        }

    @Test fun `cancel creates a fresh view receipt and old callbacks cannot borrow a later editor`() =
        runTest {
            val f = Fixture()
            start(f)
            val detail = open(f)
            val old = begin(f, detail)
            assertTrue(f.model.editStoredMemo(old.token, old.nonce, "cancelled draft"))
            assertTrue(f.model.cancelStoredMemoEdit(old.token, old.nonce))
            runCurrent()
            assertEquals(memo(), ready(detail).memo)
            assertNotEquals(old.token, ready(detail).token)
            val fresh = begin(f, detail)
            assertNotEquals(old.nonce, fresh.nonce)
            assertFalse(f.model.editStoredMemo(old.token, old.nonce, "late input"))
            assertFalse(f.model.cancelStoredMemoEdit(fresh.token, old.nonce))
            assertFalse(f.model.saveStoredMemo(fresh.token, old.nonce, fresh.draft))
            assertEquals(fresh, detail.value)
            assertEquals(0, f.writer.calls)
        }

    @Test fun `a captured save must match the displayed draft and can enter the writer only once`() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            f.writer.action = { _, _, _, guard ->
                assertTrue(guard())
                release.await()
                SessionMemoWriteResult.Blocked
            }
            start(f)
            val detail = open(f)
            val edit = begin(f, detail)
            assertTrue(f.model.editStoredMemo(edit.token, edit.nonce, "fresh draft"))
            runCurrent()
            assertFalse(f.model.saveStoredMemo(edit.token, edit.nonce, edit.draft))
            assertEquals(0, f.writer.calls)
            assertTrue(f.model.saveStoredMemo(edit.token, edit.nonce, "fresh draft"))
            runCurrent()
            val saving = detail.value as SessionHistoryMemoUiState.Saving
            assertEquals("fresh draft", saving.draft)
            assertEquals(session("a"), f.writer.source)
            assertEquals(memo(), f.writer.expected)
            assertTrue(checkNotNull(f.writer.guard).invoke())
            assertFalse(f.model.saveStoredMemo(edit.token, edit.nonce, "fresh draft"))
            assertFalse(f.model.saveStoredMemo(saving.token, saving.nonce, saving.draft))
            assertFalse(f.model.editStoredMemo(saving.token, saving.nonce, "during save"))
            release.complete(Unit)
            runCurrent()
            assertEquals(FailureReason.BLOCKED, failed(detail).reason)
            assertEquals(1, f.writer.calls)
        }

    @Test fun `invalid drafts remain unsaved instead of being trimmed repaired or sent to the writer`() =
        runTest {
            val f = Fixture()
            start(f)
            val detail = open(f)
            val edit = begin(f, detail)
            for (draft in listOf("", " \n ", "x".repeat(281), "text\u0000value", "\uD800")) {
                assertTrue(f.model.editStoredMemo(edit.token, edit.nonce, draft))
                runCurrent()
                assertEquals(draft, editing(detail).draft)
                assertFalse(f.model.saveStoredMemo(edit.token, edit.nonce, draft))
            }
            assertEquals(0, f.writer.calls)
        }

    @Test fun `an ACK first refreshes the query and only matching persisted data displays Saved`() =
        runTest {
            val f = Fixture()
            val updated = memo().copy(text = " \n반영할 수정 🙂 ", version = "new-version")
            val freshReceipts = MutableSharedFlow<SessionMemo?>()
            f.writer.action = { source, original, text, guard ->
                assertEquals(session("a"), source)
                assertEquals(memo(), original)
                assertEquals(updated.text, text)
                assertTrue(guard())
                f.memos.querySource = { freshReceipts }
                SessionMemoWriteResult.Saved(updated)
            }
            start(f)
            val detail = open(f)
            val edit = begin(f, detail)
            assertTrue(f.model.editStoredMemo(edit.token, edit.nonce, updated.text))
            assertTrue(f.model.saveStoredMemo(edit.token, edit.nonce, updated.text))
            runCurrent()
            assertTrue(detail.value is SessionHistoryMemoUiState.Loading)
            assertEquals(2, f.memos.reads)
            freshReceipts.emit(updated)
            runCurrent()
            assertEquals(updated, ready(detail).memo)
            assertEquals(Feedback.SAVED, ready(detail).feedback)
            assertEquals(2, f.memos.reads)
            assertEquals(1, f.sessions.reads)
            assertEquals(1, f.writer.calls)
        }

    @Test fun `ACK first with a different fresh receipt is unconfirmed and never claims Saved`() =
        runTest {
            val f = Fixture()
            f.writer.action = { _, _, text, _ -> SessionMemoWriteResult.Saved(memo().copy(text = text, version = "ack-version")) }
            start(f)
            val detail = open(f)
            val edit = begin(f, detail)
            assertTrue(f.model.editStoredMemo(edit.token, edit.nonce, "write draft"))
            assertTrue(f.model.saveStoredMemo(edit.token, edit.nonce, "write draft"))
            runCurrent()
            assertEquals(FailureReason.WRITE_UNCONFIRMED, failed(detail).reason)
            assertEquals("write draft", failed(detail).retainedDraft)
            assertEquals(2, f.memos.reads)
            assertEquals(1, f.writer.calls)
        }

    @Test fun `Flow first displays only the received original without claiming ownership of the save`() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            val stored = memo().copy(text = "actual received text", version = "received-version")
            f.writer.action = { _, _, _, _ ->
                f.memos.values.value = stored
                withContext(NonCancellable) { release.await() }
                SessionMemoWriteResult.Saved(stored)
            }
            start(f)
            val detail = open(f)
            val edit = begin(f, detail)
            assertTrue(f.model.editStoredMemo(edit.token, edit.nonce, stored.text))
            assertTrue(f.model.saveStoredMemo(edit.token, edit.nonce, stored.text))
            runCurrent()
            assertEquals(stored, ready(detail).memo)
            assertEquals(Feedback.RELOADED, ready(detail).feedback)
            release.complete(Unit)
            runCurrent()
            assertEquals(Feedback.RELOADED, ready(detail).feedback)
            assertEquals(1, f.memos.reads)
            assertEquals(1, f.writer.calls)
        }

    @Test fun `malformed Saved acknowledgements and Deleted cannot become a successful edit`() =
        runTest {
            val results =
                listOf<SessionMemoWriteResult>(
                    SessionMemoWriteResult.Saved(memo("b").copy(text = "draft", version = "new")),
                    SessionMemoWriteResult.Saved(memo().copy(text = "different text", version = "new")),
                    SessionMemoWriteResult.Saved(memo().copy(text = "draft", version = " ")),
                    SessionMemoWriteResult.Saved(memo().copy(text = "draft")),
                    SessionMemoWriteResult.Deleted,
                )
            for (result in results) {
                val f = Fixture()
                f.writer.action = { _, _, _, _ -> result }
                start(f)
                val detail = open(f)
                val edit = begin(f, detail)
                assertTrue(f.model.editStoredMemo(edit.token, edit.nonce, "draft"))
                assertTrue(f.model.saveStoredMemo(edit.token, edit.nonce, "draft"))
                runCurrent()
                assertEquals(FailureReason.INVALID_SAVE_RESULT, failed(detail).reason)
                assertEquals("draft", failed(detail).retainedDraft)
                assertEquals(1, f.writer.calls)
            }
        }

    @Test fun `write failures keep the retired draft until explicit retry without automatic resave`() =
        runTest {
            for (problem in listOf(IOException("controlled result failure"), CancellationException("independent cancelled result"))) {
                val f = Fixture()
                val receipts = MutableSharedFlow<SessionMemo?>(replay = 1)
                receipts.emit(memo())
                f.memos.querySource = { receipts }
                f.writer.action = { _, _, _, _ -> throw problem }
                start(f)
                val detail = open(f)
                val edit = begin(f, detail)
                assertTrue(f.model.editStoredMemo(edit.token, edit.nonce, "retained draft"))
                assertTrue(f.model.saveStoredMemo(edit.token, edit.nonce, "retained draft"))
                runCurrent()
                val error = failed(detail)
                assertEquals(FailureReason.WRITE_UNCONFIRMED, error.reason)
                assertEquals("retained draft", error.retainedDraft)
                receipts.emit(memo())
                runCurrent()
                assertEquals(error, detail.value)
                assertTrue(f.model.retryStoredMemo(error.token))
                runCurrent()
                assertEquals(memo(), ready(detail).memo)
                assertFalse(f.model.saveStoredMemo(edit.token, edit.nonce, "retained draft"))
                assertEquals(1, f.writer.calls)
            }
        }

    @Test fun `actual save job cancellation releases Saving but keeps commit guard false and requires retry`() =
        runTest {
            val f = Fixture()
            f.writer.action = { _, _, text, _ ->
                checkNotNull(currentCoroutineContext()[Job]).cancel(CancellationException("controlled save job cancellation"))
                SessionMemoWriteResult.Saved(memo().copy(text = text, version = "lost-ack"))
            }
            start(f)
            val detail = open(f)
            val edit = begin(f, detail)
            assertTrue(f.model.editStoredMemo(edit.token, edit.nonce, "lost response draft"))
            assertTrue(f.model.saveStoredMemo(edit.token, edit.nonce, "lost response draft"))
            runCurrent()
            val error = failed(detail)
            assertEquals(FailureReason.WRITE_UNCONFIRMED, error.reason)
            assertEquals("lost response draft", error.retainedDraft)
            assertFalse(checkNotNull(f.writer.guard).invoke())
            assertTrue(f.model.retryStoredMemo(error.token))
            runCurrent()
            assertEquals(memo(), ready(detail).memo)
            assertEquals(1, f.writer.calls)
        }

    @Test fun `expired host and same ID changed source never lend an old input or save nonce to a new reader`() =
        runTest {
            for (expireHost in listOf(false, true)) {
                val f = Fixture()
                start(f)
                val detail = open(f)
                val edit = begin(f, detail)
                if (expireHost) {
                    f.host.set(false)
                    assertFalse(f.model.canReadStoredMemo(edit.token))
                    f.host.set(true)
                } else {
                    f.sessions.values.value = listOf(session("a").copy(courseTitle = "changed source"), session("b"))
                    runCurrent()
                }
                val freshDetail = open(f)
                val fresh = begin(f, freshDetail)
                assertFalse(f.model.editStoredMemo(edit.token, edit.nonce, "old input"))
                assertFalse(f.model.saveStoredMemo(fresh.token, edit.nonce, fresh.draft))
                assertFalse(f.model.cancelStoredMemoEdit(fresh.token, edit.nonce))
                assertEquals(fresh, freshDetail.value)
                assertEquals(0, f.writer.calls)
            }
        }

    @Test fun `silent live denial retires a supplied commit guard even after P and UX return`() =
        runTest {
            for (denyUx in listOf(false, true)) {
                val f = Fixture()
                val release = CompletableDeferred<Unit>()
                f.writer.action = { _, _, _, _ ->
                    release.await()
                    SessionMemoWriteResult.Blocked
                }
                start(f)
                val detail = open(f)
                val edit = begin(f, detail)
                assertTrue(f.model.saveStoredMemo(edit.token, edit.nonce, edit.draft))
                runCurrent()
                val guard = checkNotNull(f.writer.guard)
                assertTrue(guard())
                if (denyUx) {
                    f.restrictions.setWithoutEmission(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNKNOWN))
                } else {
                    f.vehicle.setWithoutEmission(driving())
                }
                assertFalse(guard())
                f.vehicle.setWithoutEmission(parked())
                f.restrictions.setWithoutEmission(unrestricted())
                assertFalse(guard())
                release.complete(Unit)
                runCurrent()
                assertFalse(f.model.saveStoredMemo(edit.token, edit.nonce, edit.draft))
                assertEquals(1, f.writer.calls)
            }
        }

    @Test fun `memo Editing and Saving block deletion preparation until explicit cancel`() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            f.writer.action = { _, _, _, _ ->
                release.await()
                SessionMemoWriteResult.Blocked
            }
            start(f)
            val source = parent(f)
            val detail = open(f)
            val edit = begin(f, detail)
            assertFalse(f.model.requestDeletion(source.session.sessionId, source.generation, f.host::get))
            assertTrue(f.model.saveStoredMemo(edit.token, edit.nonce, edit.draft))
            runCurrent()
            assertFalse(f.model.requestDeletion(source.session.sessionId, source.generation, f.host::get))
            assertEquals(0, f.deletions.prepares)
            release.complete(Unit)
            runCurrent()
            assertTrue(f.model.retryStoredMemo(failed(detail).token))
            runCurrent()
            val fresh = begin(f, detail)
            assertTrue(f.model.cancelStoredMemoEdit(fresh.token, fresh.nonce))
            runCurrent()
            val viewToken = ready(detail).token
            deletionConfirmation(f)
            assertFalse(f.model.beginStoredMemoEdit(viewToken))
            assertEquals(1, f.deletions.prepares)
        }

    @Test fun `deletion confirmation cancellation restores the same collector exactly once with fresh consent`() =
        runTest {
            val f = Fixture()
            start(f)
            val source = parent(f)
            val detail = open(f)
            val old = ready(detail)
            val deletion = deletionConfirmation(f)
            assertFalse(f.model.beginStoredMemoEdit(old.token))
            assertTrue(f.model.dismissDeletion(deletion.nonce))
            runCurrent()
            val fresh = ready(detail)
            assertNotEquals(old.token, fresh.token)
            assertEquals(2, f.memos.reads)
            assertEquals(source, parent(f))
            assertFalse(f.model.dismissDeletion(deletion.nonce))
            runCurrent()
            assertEquals(fresh, detail.value)
            assertEquals(2, f.memos.reads)
            assertEquals(0, f.writer.calls)
        }

    @Test fun `dismissing preparation failure feedback does not retire fresh editing or reread again`() =
        runTest {
            val f = Fixture()
            f.deletions.prepareAction = { _, _ -> throw IOException("controlled preparation failure") }
            start(f)
            val source = parent(f)
            val detail = open(f)
            assertTrue(f.model.requestDeletion(source.session.sessionId, source.generation, f.host::get))
            runCurrent()
            val failure = f.model.deletionState.value as SessionDeletionUiState.Failed
            val fresh = begin(f, detail)
            assertEquals(2, f.memos.reads)
            assertTrue(f.model.dismissDeletion(failure.nonce))
            runCurrent()
            assertEquals(fresh, detail.value)
            assertEquals(2, f.memos.reads)
            assertFalse(f.model.dismissDeletion(failure.nonce))
            assertEquals(0, f.writer.calls)
        }

    @Test fun `cancelling a deleting operation never automatically restarts the old memo reader`() =
        runTest {
            val f = Fixture()
            val release = CompletableDeferred<Unit>()
            f.deletions.deleteAction = { _, _ ->
                withContext(NonCancellable) { release.await() }
                SessionDeletionResult.Blocked
            }
            start(f)
            val detail = open(f)
            val old = ready(detail)
            val confirmation = deletionConfirmation(f)
            assertTrue(f.model.confirmDeletion(confirmation.nonce))
            runCurrent()
            assertTrue(f.model.deletionState.value is SessionDeletionUiState.Deleting)
            assertTrue(f.model.dismissDeletion(confirmation.nonce))
            release.complete(Unit)
            runCurrent()
            assertTrue(detail.value is SessionHistoryMemoUiState.Unavailable)
            assertFalse(f.model.beginStoredMemoEdit(old.token))
            assertEquals(1, f.memos.reads)
            assertEquals(0, f.writer.calls)
        }

    @Test fun `second edit cancel or save guard denial preserves Locked or Unavailable`() =
        runTest {
            for (operation in listOf("edit", "cancel", "save")) {
                for (denyHost in listOf(false, true)) {
                    val repository = Memos()
                    val writer = Writer()
                    var armed = false
                    var reads = 0
                    val reader = SessionHistoryMemoReader(Any(), repository, writer, { !armed || denyHost || ++reads == 1 })
                    var value: SessionHistoryMemoUiState? = null
                    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                        reader
                            .observe(session("a"), 1, { !armed || !denyHost || ++reads == 1 }) {
                                SessionHistoryMemoReader.Binding({ true }, {}, { true })
                            }.collect { value = it }
                    }
                    runCurrent()
                    assertTrue(reader.beginEdit((value as SessionHistoryMemoUiState.Ready).token))
                    runCurrent()
                    val edit = value as SessionHistoryMemoUiState.Editing
                    armed = true
                    val accepted =
                        when (operation) {
                            "edit" -> reader.edit(edit.token, edit.nonce, "late draft")
                            "cancel" -> reader.cancelEdit(edit.token, edit.nonce)
                            else -> reader.save(edit.token, edit.nonce, edit.draft)
                        }
                    assertFalse(accepted)
                    runCurrent()
                    assertEquals(2, reads)
                    if (denyHost) {
                        assertTrue(value is SessionHistoryMemoUiState.Unavailable)
                    } else {
                        assertTrue(value is SessionHistoryMemoUiState.Locked)
                    }
                    armed = false
                    assertFalse(reader.save(edit.token, edit.nonce, edit.draft))
                    assertEquals(0, writer.calls)
                }
            }
        }

    @Test fun `actual memo query job cancellation retires editing until explicit retry`() =
        runTest {
            val f = Fixture()
            val queryJob = CompletableDeferred<Job>()
            f.memos.querySource = {
                flow {
                    emit(memo())
                    queryJob.complete(checkNotNull(currentCoroutineContext()[Job]))
                    awaitCancellation()
                }
            }
            start(f)
            val detail = open(f)
            val edit = begin(f, detail)
            assertTrue(f.model.editStoredMemo(edit.token, edit.nonce, "query cancelled draft"))
            queryJob.await().cancel(CancellationException("controlled actual query job cancellation"))
            runCurrent()
            val error = failed(detail)
            assertEquals(FailureReason.MEMO_READ, error.reason)
            assertEquals("query cancelled draft", error.retainedDraft)
            assertFalse(f.model.saveStoredMemo(edit.token, edit.nonce, "query cancelled draft"))
            f.memos.querySource = { MutableStateFlow(memo()) }
            assertTrue(f.model.retryStoredMemo(error.token))
            runCurrent()
            assertEquals(memo(), ready(detail).memo)
            assertEquals(0, f.writer.calls)
        }

    @Test fun `a later observer error cannot discard a failed edit draft before explicit retry`() =
        runTest {
            assertFailedDraftSurvivesQueryEnding("error")
        }

    @Test fun `a later observer completion cannot discard a failed edit draft before explicit retry`() =
        runTest {
            assertFailedDraftSurvivesQueryEnding("complete")
        }

    @Test fun `a later observer job cancellation cannot discard a failed edit draft before explicit retry`() =
        runTest {
            assertFailedDraftSurvivesQueryEnding("cancel")
        }

    @Test fun `inline cancellation during ACK refresh cannot erase acknowledgement validation or its draft`() =
        runTest {
            val repository = Memos()
            val writer = Writer()
            val freshReceipts = MutableSharedFlow<SessionMemo?>()
            val dispatcher =
                object : CoroutineDispatcher() {
                    // A controlled inline dispatch forces cancellation finally to reenter its caller.
                    // This is a coroutine-order boundary, not an actual Android lifecycle reproduction.
                    override fun dispatch(
                        context: CoroutineContext,
                        block: Runnable,
                    ) {
                        block.run()
                    }
                }
            val reader = SessionHistoryMemoReader(Any(), repository, writer, { true })
            var value: SessionHistoryMemoUiState? = null
            backgroundScope.launch(dispatcher) {
                reader
                    .observe(session("a"), 1, { true }) {
                        SessionHistoryMemoReader.Binding({ true }, {}, { true })
                    }.collect { value = it }
            }
            runCurrent()
            assertTrue(reader.beginEdit((value as SessionHistoryMemoUiState.Ready).token))
            runCurrent()
            val edit = value as SessionHistoryMemoUiState.Editing
            val draft = "ACK remains unconfirmed until the fresh original matches"
            assertTrue(reader.edit(edit.token, edit.nonce, draft))
            writer.action = { _, original, text, canCommit ->
                assertTrue(canCommit())
                repository.querySource = { freshReceipts }
                SessionMemoWriteResult.Saved(original.copy(text = text, version = "ack-new-version"))
            }
            assertTrue(reader.save(edit.token, edit.nonce, draft))
            runCurrent()
            assertTrue(value is SessionHistoryMemoUiState.Loading)
            assertEquals(2, repository.reads)
            // The new query really returns the old note, so the Saved ACK remains unconfirmed.
            freshReceipts.emit(memo())
            runCurrent()
            assertTrue("A fresh mismatching original must not silently become ordinary Ready", value is SessionHistoryMemoUiState.Failed)
            val error = value as SessionHistoryMemoUiState.Failed
            assertEquals(FailureReason.WRITE_UNCONFIRMED, error.reason)
            assertEquals(draft, error.retainedDraft)
            assertFalse(reader.save(edit.token, edit.nonce, draft))
            assertEquals(1, writer.calls)
        }

    private fun TestScope.assertFailedDraftSurvivesQueryEnding(ending: String) {
        val f = Fixture()
        val finish = CompletableDeferred<Unit>()
        f.memos.querySource = {
            flow {
                emit(memo())
                finish.await()
                when (ending) {
                    "error" -> throw IOException("controlled observer error after a failed update")
                    "cancel" ->
                        checkNotNull(currentCoroutineContext()[Job]).cancel(
                            CancellationException("controlled observer job cancellation after a failed update"),
                        )
                    else -> Unit
                }
            }
        }
        f.writer.action = { _, _, _, _ -> SessionMemoWriteResult.Conflict }
        start(f)
        val source = parent(f)
        val detail = open(f)
        val edit = begin(f, detail)
        val draft = "  retain this entered text 🙂\nunchanged  "
        assertTrue(f.model.editStoredMemo(edit.token, edit.nonce, draft))
        assertTrue(f.model.saveStoredMemo(edit.token, edit.nonce, draft))
        runCurrent()
        assertEquals(FailureReason.CONFLICT, failed(detail).reason)
        assertEquals(draft, failed(detail).retainedDraft)
        finish.complete(Unit)
        runCurrent()
        val terminal = failed(detail)
        assertEquals(draft, terminal.retainedDraft)
        assertEquals(source, parent(f))
        assertFalse(f.model.beginStoredMemoEdit(terminal.token))
        assertFalse(f.model.saveStoredMemo(edit.token, edit.nonce, draft))
        assertEquals(1, f.writer.calls)
        // Only this explicit reload may discard the entered text and obtain a new writable receipt.
        f.memos.querySource = { MutableStateFlow(memo()) }
        assertTrue(f.model.retryStoredMemo(terminal.token))
        runCurrent()
        assertEquals(memo(), ready(detail).memo)
        assertEquals(1, f.writer.calls)
    }

    private fun TestScope.start(f: Fixture) {
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { f.model.uiState.collect {} }
        runCurrent()
    }

    private fun parent(f: Fixture) = f.model.uiState.value as SessionHistoryUiState.Ready

    private fun ready(d: Detail): SessionHistoryMemoUiState.Ready {
        assertTrue("Expected a current stored memo receipt", d.value is SessionHistoryMemoUiState.Ready)
        return d.value as SessionHistoryMemoUiState.Ready
    }

    private fun editing(d: Detail) = d.value as SessionHistoryMemoUiState.Editing

    private fun failed(d: Detail) = d.value as SessionHistoryMemoUiState.Failed

    private fun TestScope.begin(
        f: Fixture,
        d: Detail,
    ): SessionHistoryMemoUiState.Editing {
        assertTrue(f.model.beginStoredMemoEdit(ready(d).token))
        runCurrent()
        return editing(d)
    }

    private fun TestScope.open(
        f: Fixture,
        source: PracticeSession = parent(f).session,
        generation: Long = parent(f).generation,
    ): Detail {
        val d = Detail()
        d.job =
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                f.model.observeStoredMemo(source, generation, f.host::get).collect { d.value = it }
            }
        runCurrent()
        return d
    }

    private fun TestScope.deletionConfirmation(f: Fixture): SessionDeletionUiState.Confirming {
        val source = parent(f)
        assertTrue(f.model.requestDeletion(source.session.sessionId, source.generation, f.host::get))
        runCurrent()
        return f.model.deletionState.value as SessionDeletionUiState.Confirming
    }

    private class Detail {
        lateinit var job: Job
        var value: SessionHistoryMemoUiState? = null
    }

    private class Fixture {
        val vehicle = SilentLiveState(parked())
        val restrictions = SilentLiveState(unrestricted())
        val sessions = Sessions()
        val memos = Memos()
        val writer = Writer()
        val deletions = Deletions()
        val host = AtomicBoolean(true)
        val model =
            SessionHistoryViewModel(
                object : VehicleGateway {
                    override val state = vehicle
                },
                object : DrivingRestrictionsProvider {
                    override val restrictions = this@Fixture.restrictions
                },
                sessions,
                deletions,
                object : VehicleObservationRepository {
                    override fun observe(sessionId: String): Flow<List<SessionVehicleObservation>> = error("Unexpected observation read")

                    override suspend fun append(
                        sessionId: String,
                        observation: VehicleObservation,
                    ): VehicleObservationAppendResult = error("Unexpected observation write")
                },
                memos,
                UnexpectedHistoryRecordingRunRepository,
                UnexpectedHistoryCourseCompletionRepository,
                writer,
            )

        init {
            deletions.prepareAction = { source, guard ->
                if (guard()) {
                    SessionDeletionPreparation.Ready(SessionDeletionTarget(source, 0, memos.values.value?.version))
                } else {
                    SessionDeletionPreparation.Blocked
                }
            }
        }
    }

    private class Sessions : PracticeSessionRepository {
        val values = MutableStateFlow(listOf(session("a"), session("b")))
        var reads = 0

        override fun observeSessions(): Flow<List<PracticeSession>> {
            reads++
            return values
        }

        override suspend fun start(
            practice: PreparedPractice,
            startedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionStartResult = error("No session start")

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult = error("No session finish")
    }

    private class Memos : SessionMemoRepository {
        val values = MutableStateFlow<SessionMemo?>(memo())
        var querySource: () -> Flow<SessionMemo?> = { values }
        var reads = 0
        val requested = mutableListOf<String>()

        override fun observe(sessionId: String): Flow<SessionMemo?> {
            reads++
            requested += sessionId
            return flow { emitAll(querySource()) }
        }

        override suspend fun save(
            sessionId: String,
            text: String,
            expectedVersion: String?,
            canCommit: () -> Boolean,
        ): SessionMemoWriteResult = error("History edit must not use the latest-only writer")

        override suspend fun delete(
            sessionId: String,
            expectedVersion: String,
            canCommit: () -> Boolean,
        ): SessionMemoWriteResult = error("History edit cannot delete a memo")
    }

    private class Writer : HistorySessionMemoWriter {
        var calls = 0
        var source: PracticeSession? = null
        var expected: SessionMemo? = null
        var text: String? = null
        var guard: (() -> Boolean)? = null
        var action: suspend (
            PracticeSession,
            SessionMemo,
            String,
            () -> Boolean,
        ) -> SessionMemoWriteResult = { _, _, _, _ -> error("Unconfigured history update") }

        override suspend fun update(
            expectedSource: PracticeSession,
            expectedMemo: SessionMemo,
            text: String,
            canCommit: () -> Boolean,
        ): SessionMemoWriteResult {
            calls++
            source = expectedSource
            expected = expectedMemo
            this.text = text
            guard = canCommit
            return action(expectedSource, expectedMemo, text, canCommit)
        }
    }

    private class Deletions : SessionDeletionRepository {
        var prepares = 0
        var prepareAction: suspend (
            PracticeSession,
            () -> Boolean,
        ) -> SessionDeletionPreparation = { _, _ -> error("Unconfigured deletion preparation") }
        var deleteAction: suspend (SessionDeletionTarget, () -> Boolean) -> SessionDeletionResult = { _, _ -> error("Unexpected deletion") }

        override suspend fun prepare(
            expected: PracticeSession,
            canRead: () -> Boolean,
        ): SessionDeletionPreparation {
            prepares++
            return prepareAction(expected, canRead)
        }

        override suspend fun delete(
            target: SessionDeletionTarget,
            canCommit: () -> Boolean,
        ): SessionDeletionResult = deleteAction(target, canCommit)
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

        fun memo(id: String = "a") = SessionMemo(id, "original memo", "original-version")

        fun parked() = VehicleState(ConnectionState.CONNECTED, VehicleReading.Available(GearPosition.PARK))

        fun driving() = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))

        fun unrestricted() = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
    }
}
