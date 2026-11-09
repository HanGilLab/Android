package io.github.hangillab.hangil

import android.content.res.Configuration
import android.graphics.Bitmap
import android.view.ViewGroup
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.database.OwnedPracticeTestStore
import io.github.hangillab.hangil.core.domain.CourseCompletionDeclaration
import io.github.hangillab.hangil.core.domain.CourseRoadSnapshot
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.NextPracticeSeed
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.RecordingRunOpenResult
import io.github.hangillab.hangil.core.domain.RecordingRunRepository
import io.github.hangillab.hangil.core.domain.RecordingRunStatus
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import io.github.hangillab.hangil.core.domain.SessionReviewRoad
import io.github.hangillab.hangil.core.domain.SessionReviewRoadRepository
import io.github.hangillab.hangil.core.domain.SessionReviewRoadWriteResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import io.github.hangillab.hangil.core.domain.VehicleRecordingRun
import io.github.hangillab.hangil.feature.home.SessionFinishReceipt
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.LineString
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt
import io.github.hangillab.hangil.feature.home.R as HomeR

/** Real production routes and strict Room repository in a UUID-owned Room store; no SDK or user database. */
@RunWith(AndroidJUnit4::class)
class FinishSourceReviewRoomFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()

    @Test fun committedFlowBeforeAcknowledgementReviewsTheFinishedSourceEvenAfterANewerRecordExists() {
        assumeManual()
        fixture { f ->
            val original = f.snapshot()
            runBlocking {
                check(
                    f.store.sessions.finish(f.active.copy(taskTitle = "changed owned source"), TIME + 90_000) { true } ==
                        SessionFinishResult.SourceChanged,
                )
                check(f.store.sessions.finish(f.active, TIME + 90_000) { false } == SessionFinishResult.Blocked)
                check(f.store.sessions.finish(f.active, TIME) { true } == SessionFinishResult.InvalidTime)
            }
            check(f.snapshot() == original) { "Rejected strict writes changed owned data" }
            val committed = CompletableDeferred<SessionFinishResult>()
            val acknowledge = CompletableDeferred<Unit>()
            val proxy = Sessions(f.store.sessions, listOf(f.active))
            proxy.onFinish = { expected, time, allowed ->
                val result = f.store.sessions.finish(expected, time, allowed)
                committed.complete(result)
                acknowledge.await()
                result
            }
            val ui = f.mount(proxy)
            ui.touch("session_finish")
            val result = await(committed)
            check(result is SessionFinishResult.Finished)
            val ended = result.session
            f.assertOnlyEnded(original, ended)
            check(ui.session.finishReceipt.value == null && ui.navigation.isEmpty())
            runBlocking {
                check(f.store.memos.save(ended.sessionId, "Owned finish review memo", null) { true } is SessionMemoWriteResult.Saved)
            }
            val newer = f.addNewerEnded()
            val beforeReview = f.snapshot()
            acknowledge.complete(Unit)
            ui.waitReceipt()
            val receipt = checkNotNull(ui.session.finishReceipt.value)
            check(receipt.source == ended && receipt.kind == SessionFinishReceipt.Kind.FINISHED)
            val oldReview = ui.action("session_finish_review")
            ui.touch("session_finish_review")
            ui.ready(ended)
            ui.invoke(oldReview)
            check(ui.navigation == listOf(ended))
            check(ui.nodes("history_newer").isEmpty() && ui.nodes("history_older").isEmpty())
            check(f.memoQueries.isEmpty()) { "Exact entry must keep a closed memo cold" }
            ui.touch("history_memo")
            ui.waitTag("history_memo_text")
            check(ui.text("history_memo_text") == "Owned finish review memo")
            check(f.memoQueries.toList() == listOf(ended.sessionId))
            check(f.memoQueries.none { it == newer.sessionId })
            check(f.snapshot() == beforeReview) { "Reviewing an exact source changed owned data" }
            check(proxy.strictCalls.get() == 1 && proxy.legacyCalls.get() == 0)
        }
    }

    @Test fun recentExactReviewReopensAfterFinishAndRejectsStaleHomeActions() {
        assumeManual()
        val args = InstrumentationRegistry.getArguments()
        val fontScale = checkNotNull(args.getString("expectedFontScale")).toFloat()
        val dark = checkNotNull(args.getString("expectedDark")).toBooleanStrict()
        val configuration = compose.activity.resources.configuration
        check(kotlin.math.abs(configuration.fontScale - fontScale) < 0.01f)
        check((configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) == dark)

        fixture { f ->
            val original = f.snapshot()
            val ui = f.mount(f.store.sessions)
            ui.touch("session_finish")
            ui.waitReceipt()
            check(ui.nodes("session_recent_review").isEmpty())
            val ended = checkNotNull(ui.session.finishReceipt.value).source
            f.assertOnlyEnded(original, ended)
            val afterFinish = f.snapshot()
            ui.touch("session_finish_review")
            ui.ready(ended)
            ui.touch("history_close")
            ui.waitTag("session_recent_review")
            check(ui.nodes("session_finish_review").isEmpty())
            ui.touch("session_recent_review")
            ui.ready(ended)
            check(ui.navigation == listOf(ended, ended))
            check(f.snapshot() == afterFinish)

            ui.touch("history_close")
            ui.waitTag("session_recent_review")
            val beforeRestriction = ui.action("session_recent_review")
            val allowed = ui.restrictions.value
            compose.runOnIdle { ui.restrictions.value = allowed.copy(uxRestrictionState = UxRestrictionState.RESTRICTED) }
            ui.waitTag("home_locked_title")
            ui.invoke(beforeRestriction)
            compose.runOnIdle { ui.restrictions.value = allowed }
            ui.waitTag("session_recent_review")
            ui.invoke(beforeRestriction)
            check(ui.navigation == listOf(ended, ended))

            val beforeNewer = ui.action("session_recent_review")
            val newer = f.addNewerEnded()
            compose.waitUntil(TIMEOUT) { ui.session.uiState.value.latestFinished == newer }
            ui.waitTag("session_recent_review")
            ui.invoke(beforeNewer)
            check(ui.navigation == listOf(ended, ended))
            val afterNewer = f.snapshot()
            ui.touch("session_recent_review")
            ui.ready(newer)
            check(ui.navigation == listOf(ended, ended, newer))
            check(f.snapshot() == afterNewer)

            ui.dispose()
            check(ui.isStopped)
            f.store.reopen()
            val reopened = f.mount(f.store.sessions)
            reopened.waitTag("session_recent_review")
            check(reopened.nodes("session_finish_review").isEmpty())
            reopened.touch("session_recent_review")
            reopened.ready(newer)
            check(reopened.navigation == listOf(newer))
            check(f.snapshot() == afterNewer)
        }
    }

    @Test fun exactFinishReviewCreatesFirstMemoForTheFinishedSource() {
        assumeManual()
        fixture { f ->
            val original = f.snapshot()
            val committed = CompletableDeferred<SessionFinishResult>()
            val acknowledge = CompletableDeferred<Unit>()
            val proxy = Sessions(f.store.sessions, listOf(f.active))
            proxy.onFinish = { expected, time, allowed ->
                val result = f.store.sessions.finish(expected, time, allowed)
                committed.complete(result)
                acknowledge.await()
                result
            }
            val ui = f.mount(proxy)
            ui.touch("session_finish")
            val result = await(committed)
            check(result is SessionFinishResult.Finished)
            val ended = result.session
            f.assertOnlyEnded(original, ended)
            val newer = f.addNewerEnded()
            val before = f.snapshot()
            check(before.sessions.first() == newer && before.memos.values.all { it == null })
            acknowledge.complete(Unit)

            ui.waitReceipt()
            ui.touch("session_finish_review")
            ui.ready(ended)
            check(ui.navigation == listOf(ended))
            check(ui.nodes("history_newer").isEmpty() && ui.nodes("history_older").isEmpty())
            check(f.memoQueries.isEmpty())
            ui.touch("history_memo")
            ui.waitTag("history_memo_empty")
            ui.touch("history_memo_create")
            ui.waitTag("history_memo_textfield")
            check(ui.nodes("history_memo_source").isEmpty())

            val draft = "다음 연습에서는 진입 전에 좌우를 한 번 더 확인하기"
            compose.onNodeWithTag("history_memo_textfield", useUnmergedTree = true).performScrollTo().performTextReplacement(draft)
            check(f.snapshot() == before)
            ui.touch("history_memo_save")
            ui.waitTag("history_memo_text")
            check(ui.text("history_memo_text") == draft)
            check(ui.nodes("history_memo_create").isEmpty())
            val after = f.snapshot()
            val saved = checkNotNull(after.memos[ended.sessionId])
            check(saved.sessionId == ended.sessionId && saved.text == draft && saved.version.isNotBlank())
            check(after == before.copy(memos = before.memos + (ended.sessionId to saved)))
            check(f.memoQueries.isNotEmpty() && f.memoQueries.all { it == ended.sessionId })
        }
    }

    @Test fun alreadyFinishedAcknowledgementWaitsForTheExactStoredFlowAndPreservesTheFirstEnd() {
        assumeManual()
        fixture { f ->
            val original = f.snapshot()
            val acknowledgeReturned = CompletableDeferred<Unit>()
            val proxy = Sessions(f.store.sessions, listOf(f.active))
            proxy.deliver.value = false
            var committed: PracticeSession? = null
            proxy.onFinish = { expected, time, allowed ->
                val first = f.store.sessions.finish(expected, time - 1_000, allowed)
                check(first is SessionFinishResult.Finished)
                committed = first.session
                val duplicate = f.store.sessions.finish(expected, time, allowed)
                check(duplicate is SessionFinishResult.AlreadyFinished && duplicate.session == first.session)
                acknowledgeReturned.complete(Unit)
                duplicate
            }
            val ui = f.mount(proxy)
            ui.touch("session_finish")
            await(acknowledgeReturned)
            compose.waitUntil(TIMEOUT) { proxy.completed.get() == 1 }
            compose.waitForIdle()
            check(ui.session.finishReceipt.value == null && ui.nodes("session_finish_review").isEmpty()) {
                "An ACK must not substitute for an unreceived exact stored source"
            }
            val ended = checkNotNull(committed)
            f.assertOnlyEnded(original, ended)
            proxy.deliver.value = true
            ui.waitReceipt()
            val receipt = checkNotNull(ui.session.finishReceipt.value)
            check(receipt.kind == SessionFinishReceipt.Kind.ALREADY_FINISHED && receipt.source == ended)
            val beforeReview = f.snapshot()
            ui.touch("session_finish_review")
            ui.ready(ended)
            check(ui.navigation == listOf(ended) && f.snapshot() == beforeReview)
            check(proxy.strictCalls.get() == 1 && proxy.legacyCalls.get() == 0)
        }
    }

    @Test fun committedButCancelledReplyCreatesNoReceiptAndFreshRoomHandleReadsOnlyTheCommittedSource() {
        assumeManual()
        fixture { f ->
            val original = f.snapshot()
            val committed = CompletableDeferred<PracticeSession>()
            val proxy = Sessions(f.store.sessions, listOf(f.active))
            proxy.onFinish = { expected, time, allowed ->
                val result = f.store.sessions.finish(expected, time, allowed)
                check(result is SessionFinishResult.Finished)
                committed.complete(result.session)
                // This delays adapter result delivery after a real COMMIT; it is not an internal Room-dispatcher claim.
                awaitCancellation()
            }
            val ui = f.mount(proxy)
            ui.touch("session_finish")
            val ended = await(committed)
            check(ui.session.finishReceipt.value == null && ui.navigation.isEmpty())
            ui.dispose()
            check(ui.isStopped && proxy.completed.get() == 1 && proxy.strictCalls.get() == 1 && proxy.legacyCalls.get() == 0)
            f.assertOnlyEnded(original, ended)
            val committedData = f.snapshot()
            f.store.reopen()
            check(f.snapshot() == committedData) { "Reopening a real Room handle changed the committed result" }
            val next = f.mount(Sessions(f.store.sessions, listOf(ended)))
            check(next.session.finishReceipt.value == null && next.nodes("session_finish_review").isEmpty()) {
                "A new ViewModel must not invent an acknowledgement receipt or navigation"
            }
            next.openExact(ended)
            next.ready(ended)
            check(next.navigation.isEmpty() && f.snapshot() == committedData)
        }
    }

    @Test fun uxLockDuringFinishShowsBlockedFeedbackAfterUnlock() {
        assumeManual()
        fixture { f ->
            val original = f.snapshot()
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val result = CompletableDeferred<SessionFinishResult>()
            val proxy = Sessions(f.store.sessions, listOf(f.active))
            proxy.onFinish = { expected, time, allowed ->
                check(allowed())
                started.complete(Unit)
                release.await()
                f.store.sessions
                    .finish(expected, time, allowed)
                    .also { result.complete(it) }
            }
            val ui = f.mount(proxy)
            ui.waitTag("session_finish")
            val oldFinish = ui.action("session_finish")
            ui.touch("session_finish")
            await(started)
            ui.waitTag("session_saving")

            compose.runOnIdle {
                ui.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.RESTRICTED)
            }
            ui.waitTag("home_locked_title")
            check(ui.nodes("session_finish").isEmpty() && ui.nodes("session_feedback").isEmpty())
            release.complete(Unit)
            check(await(result) == SessionFinishResult.Blocked)
            compose.waitUntil(TIMEOUT) { proxy.completed.get() == 1 }
            compose.runOnIdle {
                ui.restrictions.value = DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED)
            }

            ui.waitTag("session_feedback")
            compose.onNodeWithTag("session_feedback", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            check(ui.text("session_feedback") == context.getString(HomeR.string.session_blocked))
            ui.waitTag("session_finish")
            ui.invoke(oldFinish)
            check(proxy.strictCalls.get() == 1 && proxy.legacyCalls.get() == 0)
            check(ui.session.finishReceipt.value == null && ui.navigation.isEmpty())
            check(f.snapshot() == original)
        }
    }

    @Test fun userSelectedRoadSurvivesUnconfirmedSaveReopenAndExplicitClear() {
        assumeManual()
        val args = InstrumentationRegistry.getArguments()
        val scale = checkNotNull(args.getString("expectedFontScale")?.toFloatOrNull())
        val dark = checkNotNull(args.getString("expectedDark")?.toBooleanStrictOrNull())
        val config = compose.activity.resources.configuration
        check(kotlin.math.abs(config.fontScale - scale) < 0.001f)
        check((config.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) == dark)
        fixture { f ->
            val ended =
                runBlocking {
                    val result = f.store.sessions.finish(f.active, TIME + 90_000) { true }
                    check(result is SessionFinishResult.Finished)
                    result.session
                }
            val newer = f.addNewerEnded()
            val before = f.snapshot()
            val actual = f.store.reviewRoads
            val saveCalls = AtomicInteger()
            val delegatedSaveCalls = AtomicInteger()
            val loseReply = AtomicBoolean(true)
            val failBeforeNextSave = AtomicBoolean(false)
            val interruptedReply =
                object : SessionReviewRoadRepository by actual {
                    override suspend fun save(
                        expectedSource: PracticeSession,
                        road: CourseRoadSnapshot,
                        expectedCurrent: SessionReviewRoad?,
                        memoText: String,
                        discardMemo: Boolean,
                        discardSample: Boolean,
                        canCommit: () -> Boolean,
                    ): SessionReviewRoadWriteResult {
                        saveCalls.incrementAndGet()
                        if (failBeforeNextSave.getAndSet(false)) throw IOException("Controlled failure before the owned Room write")
                        delegatedSaveCalls.incrementAndGet()
                        val result = actual.save(expectedSource, road, expectedCurrent, memoText, discardMemo, discardSample, canCommit)
                        if (result is SessionReviewRoadWriteResult.Saved && loseReply.getAndSet(false)) {
                            throw IOException("Controlled loss of a committed write reply")
                        }
                        return result
                    }
                }
            var ui = f.mount(f.store.sessions, interruptedReply)
            ui.openExact(ended)
            ui.ready(ended)
            check(ui.nodes("review_road_content").isEmpty())
            ui.touch("history_review_road")
            ui.waitTag("review_road_empty")
            val course = checkNotNull(NextPracticeSeed.resolve(f.store.catalog, ended)).practice.course
            val road = course.roadSnapshots[1]
            check(runBlocking { actual.observe(ended.sessionId).first() } == null)
            val oldCandidate = ui.action("review_road_candidate_0")
            ui.touch("review_road_candidate_1")
            ui.invoke(oldCandidate)
            val oldSave = ui.action("review_road_save")
            ui.invoke(oldSave, oldSave)
            ui.waitTag("review_road_failed")
            check(ui.nodes("review_road_save").isEmpty())
            var saved = checkNotNull(runBlocking { actual.observe(ended.sessionId).first() })
            check(saved.source == ended && saved.road == road && saved.origin == "USER_SELECTION")
            check(saveCalls.get() == 1 && delegatedSaveCalls.get() == 1 && f.snapshot() == before)
            check(runBlocking { actual.observe(newer.sessionId).first() } == null)
            ui.touch("review_road_retry")
            ui.waitTag("review_road_stored_title")
            check(ui.text("review_road_stored_title") == road.title && saveCalls.get() == 1)
            check(saved.memoText.isEmpty())
            val firstMemo = "  지도에서 고른 구간 🧭\n표지를 다시 살펴보기  "
            val revisedMemo = "  지도에서 고른 구간 🧭\n다음에는 진입 전 표지 살펴보기  "
            val emptyMemoVersion = saved.version
            writeRoadMemo(ui, firstMemo)
            val firstMemoStored = checkNotNull(runBlocking { actual.observe(ended.sessionId).first() })
            check(firstMemoStored.memoText == firstMemo && firstMemoStored.version != emptyMemoVersion)
            val oldMemoClear = ui.action("review_road_clear")
            val oldMemoSave =
                writeRoadMemo(ui, revisedMemo, afterFailedSave = {
                    ui.waitTag("review_road_failed")
                    check(ui.text("review_road_memo_unsaved") == revisedMemo)
                    check(runBlocking { actual.observe(ended.sessionId).first() } == firstMemoStored)
                    // The failed API attempt never reached Room. These are calls, not COMMIT counts.
                    check(saveCalls.get() == 3 && delegatedSaveCalls.get() == 2)
                    ui.touch("review_road_retry")
                    ui.waitTag("review_road_memo_textfield")
                    compose.onNodeWithTag("review_road_memo_textfield", useUnmergedTree = true).assertTextEquals(revisedMemo)
                    check(runBlocking { actual.observe(ended.sessionId).first() } == firstMemoStored)
                    check(saveCalls.get() == 3 && delegatedSaveCalls.get() == 2)
                    ui.touch("review_road_memo_save")
                }) {
                    check(runBlocking { actual.observe(ended.sessionId).first() } == firstMemoStored && saveCalls.get() == 2)
                    failBeforeNextSave.set(true)
                }
            saved = checkNotNull(runBlocking { actual.observe(ended.sessionId).first() })
            check(saved.memoText == revisedMemo && saved.version != firstMemoStored.version && saved.road == road && saved.source == ended)
            ui.invoke(oldMemoClear, oldMemoSave)
            check(ui.nodes("review_road_memo_discard_dialog").isEmpty())
            check(runBlocking { actual.observe(ended.sessionId).first() } == saved)
            check(
                runBlocking {
                    actual.save(ended, road, firstMemoStored, memoText = firstMemo) { true }
                } == SessionReviewRoadWriteResult.Conflict,
            )
            check(runBlocking { actual.observe(ended.sessionId).first() } == saved && saveCalls.get() == 4 && delegatedSaveCalls.get() == 3)
            check(f.snapshot() == before)
            awaitReviewRoad(road)
            val directory =
                File(
                    InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
                    "screenshots/session-review-road-" + UUID.randomUUID(),
                )
            check(directory.mkdirs())
            captureReviewRoad(ui, directory, "saved-road-map.png", "session_review_road_map_view")
            captureReviewRoad(ui, directory, "saved-road-title.png", "review_road_stored_title")
            captureReviewRoad(ui, directory, "selection-source.png", "review_road_source")
            captureReviewRoad(ui, directory, "saved-road-memo.png", "review_road_memo_text")
            val oldClear = ui.action("review_road_clear")
            ui.dispose()
            runBlocking { f.store.reopen() }
            check(
                runBlocking {
                    f.store.reviewRoads
                        .observe(ended.sessionId)
                        .first()
                } == saved,
            )
            ui = f.mount(f.store.sessions, f.store.reviewRoads)
            ui.openExact(ended)
            ui.ready(ended)
            ui.touch("history_review_road")
            ui.waitTag("review_road_stored_title")
            ui.invoke(oldSave, oldClear, oldMemoSave)
            check(
                runBlocking {
                    f.store.reviewRoads
                        .observe(ended.sessionId)
                        .first()
                } == saved,
            )
            awaitReviewRoad(road)
            check(ui.text("review_road_memo_text") == revisedMemo)
            ui.touch("review_road_candidate_0")
            ui.touch("review_road_save")
            awaitRoadMemoDialog(ui)
            dismissRoadMemoDialog(ui, confirm = false)
            check(
                runBlocking {
                    f.store.reviewRoads
                        .observe(ended.sessionId)
                        .first()
                } == saved,
            )
            check(ui.text("review_road_memo_text") == revisedMemo)
            ui.touch("review_road_clear")
            awaitRoadMemoDialog(ui)
            dismissRoadMemoDialog(ui, confirm = false)
            check(
                runBlocking {
                    f.store.reviewRoads
                        .observe(ended.sessionId)
                        .first()
                } == saved,
            )
            val beforeDialogClear = ui.action("review_road_clear")
            ui.touch("history_delete")
            compose.waitUntil(TIMEOUT) { ui.nodes("history_delete_confirm").size == 1 }
            check(compose.activity.lifecycle.currentState == Lifecycle.State.RESUMED)
            val focusedDialog = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
            try {
                check(focusedDialog.packageName?.toString() == InstrumentationRegistry.getInstrumentation().targetContext.packageName)
            } finally {
                focusedDialog.recycle()
            }
            check(
                ui.text("history_delete_review_road") ==
                    compose.activity.getString(
                        HomeR.string.history_delete_review_road_memo_present,
                        road.title,
                    ),
            )
            compose.onNodeWithTag("history_delete_review_road", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("history_delete_cancel", useUnmergedTree = true).assertIsDisplayed().performClick()
            compose.waitUntil(TIMEOUT) { ui.nodes("history_delete_dialog").isEmpty() && compose.activity.hasWindowFocus() }
            ui.ready(ended)
            check(ui.nodes("review_road_content").isEmpty())
            ui.invoke(beforeDialogClear)
            check(
                runBlocking {
                    f.store.reviewRoads
                        .observe(ended.sessionId)
                        .first()
                } == saved,
            )
            ui.touch("history_review_road")
            ui.waitTag("review_road_stored_title")
            awaitReviewRoad(road)
            val staleClear = ui.action("review_road_clear")
            ui.touch("review_road_clear")
            awaitRoadMemoDialog(ui)
            val staleConfirm = ui.action("review_road_memo_discard_confirm")
            compose.runOnIdle { ui.live.publish(FinishSourceReviewBoundaryTest.driving()) }
            compose.waitUntil(TIMEOUT) { ui.nodes("review_road_memo_discard_dialog").isEmpty() && compose.activity.hasWindowFocus() }
            ui.waitTag("history_locked")
            ui.invoke(staleClear, staleConfirm)
            check(
                runBlocking {
                    f.store.reviewRoads
                        .observe(ended.sessionId)
                        .first()
                } == saved,
            )
            compose.runOnIdle { ui.live.publish(FinishSourceReviewBoundaryTest.parked()) }
            ui.ready(ended)
            ui.touch("history_review_road")
            ui.waitTag("review_road_stored_title")
            ui.invoke(staleClear, staleConfirm)
            check(
                runBlocking {
                    f.store.reviewRoads
                        .observe(ended.sessionId)
                        .first()
                } == saved,
            )
            val stoppedClear = ui.action("review_road_clear")
            ui.move(Lifecycle.State.CREATED)
            ui.invoke(stoppedClear)
            check(
                runBlocking {
                    f.store.reviewRoads
                        .observe(ended.sessionId)
                        .first()
                } == saved,
            )
            ui.move(Lifecycle.State.RESUMED)
            ui.ready(ended)
            ui.touch("history_review_road")
            ui.waitTag("review_road_stored_title")
            ui.invoke(stoppedClear)
            check(
                runBlocking {
                    f.store.reviewRoads
                        .observe(ended.sessionId)
                        .first()
                } == saved,
            )
            ui.touch("review_road_clear")
            awaitRoadMemoDialog(ui)
            dismissRoadMemoDialog(ui, confirm = true)
            ui.waitTag("review_road_empty")
            check(
                runBlocking {
                    f.store.reviewRoads
                        .observe(ended.sessionId)
                        .first()
                } == null,
            )
            ui.openExact(newer)
            ui.ready(newer)
            ui.invoke(oldSave, oldMemoSave, staleClear, staleConfirm, stoppedClear)
            check(
                runBlocking {
                    f.store.reviewRoads
                        .observe(ended.sessionId)
                        .first()
                } == null,
            )
            check(
                runBlocking {
                    f.store.reviewRoads
                        .observe(newer.sessionId)
                        .first()
                } == null,
            )
            check(f.snapshot() == before && saveCalls.get() == 4 && delegatedSaveCalls.get() == 3)
        }
    }

    private fun writeRoadMemo(
        ui: FinishSourceReviewBoundaryTest.Driver,
        text: String,
        afterFailedSave: (() -> Unit)? = null,
        assertStaleSaveRejected: (() -> Unit)? = null,
    ): () -> Boolean {
        val roadTitle = ui.text("review_road_stored_title")
        ui.touch("review_road_memo_edit")
        ui.waitTag("review_road_memo_textfield")
        check(ui.text("review_road_memo_edit_context").contains(roadTitle))
        for (tag in listOf(
            "review_road_save",
            "review_road_clear",
            "review_road_candidate_0",
            "review_road_candidate_1",
            "session_review_road_map_view",
        )) {
            check(ui.nodes(tag).isEmpty()) { "An editing memo must not retain road actions or the native map" }
        }
        val staleSave =
            if (assertStaleSaveRejected != null) {
                compose.onNodeWithTag("review_road_memo_save", useUnmergedTree = true).assertIsEnabled()
                ui.action("review_road_memo_save")
            } else {
                null
            }
        val field =
            compose
                .onNodeWithTag("review_road_memo_textfield", useUnmergedTree = true)
                .performScrollTo()
                .performClick()
                .assertIsFocused()
        field.performTextReplacement(text)
        field.assertIsFocused()
        if (staleSave != null) {
            ui.invoke(staleSave)
            checkNotNull(assertStaleSaveRejected).invoke()
            check(ui.nodes("review_road_memo_textfield").size == 1)
        }
        val save = ui.action("review_road_memo_save")
        ui.touch("review_road_memo_save")
        afterFailedSave?.invoke()
        compose.waitUntil(TIMEOUT) {
            ui.nodes("review_road_memo_text").size == 1 && ui.text("review_road_memo_text") == text &&
                ui.nodes("review_road_memo_textfield").isEmpty()
        }
        ui.requireHost()
        return save
    }

    private fun awaitRoadMemoDialog(ui: FinishSourceReviewBoundaryTest.Driver) {
        compose.waitUntil(TIMEOUT) { ui.nodes("review_road_memo_discard_dialog").size == 1 }
        check(compose.activity.lifecycle.currentState == Lifecycle.State.RESUMED)
        val active = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow)
        try {
            check(active.packageName?.toString() == InstrumentationRegistry.getInstrumentation().targetContext.packageName)
        } finally {
            active.recycle()
        }
        compose.onNodeWithTag("review_road_memo_discard_dialog", useUnmergedTree = true).assertIsDisplayed()
    }

    private fun dismissRoadMemoDialog(
        ui: FinishSourceReviewBoundaryTest.Driver,
        confirm: Boolean,
    ) {
        compose
            .onNodeWithTag(
                if (confirm) "review_road_memo_discard_confirm" else "review_road_memo_discard_cancel",
                useUnmergedTree = true,
            ).assertIsDisplayed()
            .performClick()
        compose.waitUntil(TIMEOUT) { ui.nodes("review_road_memo_discard_dialog").isEmpty() && compose.activity.hasWindowFocus() }
        ui.requireHost()
    }

    private fun awaitReviewRoad(road: CourseRoadSnapshot) =
        runBlocking {
            compose.onNodeWithTag("session_review_road_map_view", useUnmergedTree = true).performScrollTo()
            withTimeout(90_000) {
                while (true) {
                    val result = CompletableDeferred<Boolean>()
                    InstrumentationRegistry.getInstrumentation().runOnMainSync {
                        fun views(view: android.view.View): List<MapView> =
                            (if (view is MapView) listOf(view) else emptyList()) +
                                (if (view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList())
                        val view = views(compose.activity.window.decorView).singleOrNull { it.isAttachedToWindow }
                        if (view == null) {
                            result.complete(false)
                        } else {
                            view.getMapAsync { map ->
                                try {
                                    val style = map.style
                                    val source = style?.getSourceAs<GeoJsonSource>("hangil-course-preview-roads")
                                    val selected = style?.getLayerAs<LineLayer>("hangil-course-preview-road-selection")
                                    val features = source?.querySourceFeatures(null).orEmpty()
                                    val expected = Expression.eq(Expression.get("road_id"), Expression.literal(road.id))
                                    val points = style?.getSourceAs<GeoJsonSource>("hangil-course-preview-points")
                                    val original =
                                        road.nodes.map { node ->
                                            val location =
                                                org.maplibre.android.geometry
                                                    .LatLng(node.latitude, node.longitude)
                                            map.projection.toScreenLocation(location)
                                        }
                                    val paths =
                                        features.mapNotNull { feature ->
                                            (feature.geometry() as? LineString)?.coordinates()?.map { point ->
                                                val location =
                                                    org.maplibre.android.geometry
                                                        .LatLng(point.latitude(), point.longitude())
                                                map.projection.toScreenLocation(location)
                                            }
                                        }
                                    val placement =
                                        paths.isNotEmpty() &&
                                            paths.all { path ->
                                                path.size >= 2 && path.all { onReviewPath(it, original) }
                                            } && original.all { point -> paths.any { onReviewPath(point, it) } }
                                    result.complete(
                                        view.isAttachedToWindow && style?.isFullyLoaded == true && features.isNotEmpty() && placement &&
                                            features.all {
                                                it.geometry() is LineString && it.getStringProperty("road_source") == "OSM_WAY_NODES" &&
                                                    it.getStringProperty("road_id") == road.id &&
                                                    it.getStringProperty("osm_way_id") == road.osmWayId.toString() &&
                                                    it.getNumberProperty("osm_way_version").toInt() == road.osmWayVersion
                                            } &&
                                            selected?.filter?.toArray()?.contentDeepEquals(expected.toArray()) == true &&
                                            points?.querySourceFeatures(null)?.isEmpty() == true &&
                                            map
                                                .queryRenderedFeatures(
                                                    android.graphics.RectF(0f, 0f, view.width.toFloat(), view.height.toFloat()),
                                                    "hangil-course-preview-road-selection",
                                                ).isNotEmpty(),
                                    )
                                } catch (_: Exception) {
                                    result.complete(false)
                                }
                            }
                        }
                    }
                    if (result.await()) break
                    delay(50)
                }
            }
        }

    private fun onReviewPath(
        point: android.graphics.PointF,
        path: List<android.graphics.PointF>,
    ): Boolean =
        path.zipWithNext().any { (start, end) ->
            val dx = end.x - start.x
            val dy = end.y - start.y
            val length = dx * dx + dy * dy
            val dot = (point.x - start.x) * dx + (point.y - start.y) * dy
            val fraction = if (length == 0f) 0f else (dot / length).coerceIn(0f, 1f)
            val x = point.x - (start.x + fraction * dx)
            val y = point.y - (start.y + fraction * dy)
            x * x + y * y <= 1f
        }

    private fun captureReviewRoad(
        ui: FinishSourceReviewBoundaryTest.Driver,
        directory: File,
        name: String,
        tag: String,
    ) {
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
        compose.waitForIdle()
        ui.requireHost()
        check(compose.activity.display?.displayId == android.view.Display.DEFAULT_DISPLAY)
        val node = ui.nodes(tag).single()
        val origin = node.positionOnScreen - node.positionInWindow
        val bounds = node.boundsInWindow
        val left = (bounds.left + origin.x).roundToInt()
        val top = (bounds.top + origin.y).roundToInt()
        val width = bounds.width.roundToInt()
        val height = bounds.height.roundToInt()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            check(left >= 0 && top >= 0 && width > 0 && height > 0)
            check(left + width <= bitmap.width && top + height <= bitmap.height)
            val crop = Bitmap.createBitmap(bitmap, left, top, width, height)
            try {
                File(directory, name).outputStream().use { check(crop.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                if (crop !== bitmap) crop.recycle()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun fixture(body: (Fixture) -> Unit) {
        val f = Fixture(OwnedPracticeTestStore.create(InstrumentationRegistry.getInstrumentation().targetContext))
        var primary: Throwable? = null
        try {
            f.seed()
            body(f)
        } catch (failure: Throwable) {
            primary = failure
            try {
                f.ui?.captureFailure(failure)
            } catch (_: Throwable) {
                failure.addSuppressed(IllegalStateException("Private UI diagnostic unavailable"))
            }
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null

            fun attempt(action: () -> Unit) {
                try {
                    action()
                } catch (failure: Throwable) {
                    if (cleanupFailure ==
                        null
                    ) {
                        cleanupFailure = failure
                    } else if (cleanupFailure !== failure) {
                        cleanupFailure!!.addSuppressed(failure)
                    }
                    primary?.let { if (it !== failure) it.addSuppressed(failure) }
                }
            }
            attempt { f.ui?.dispose() }
            attempt { check(f.store.schemaVersion() == SCHEMA_VERSION && f.store.foreignKeyViolationCount() == 0) }
            attempt {
                check(f.ui == null || f.ui!!.isStopped)
                f.store.close()
            }
            if (primary == null) cleanupFailure?.let { throw it }
        }
    }

    private inner class Fixture(
        val store: OwnedPracticeTestStore,
    ) {
        lateinit var active: PracticeSession
        var ui: FinishSourceReviewBoundaryTest.Driver? = null
        val memoQueries = ConcurrentLinkedQueue<String>()

        fun seed() =
            runBlocking {
                check(
                    store.schemaVersion() == SCHEMA_VERSION &&
                        store.sessions
                            .observeSessions()
                            .first()
                            .isEmpty(),
                )
                check(store.preparation.observeSelection().first() == null)
                val practice = prepared(0)
                val start = store.sessions.start(practice, TIME + 1_000) { true }
                check(start is SessionStartResult.Started)
                active = start.session
                check(
                    store.observations.append(
                        active.sessionId,
                        VehicleObservation(TIME + 1_100, FinishSourceReviewBoundaryTest.parked()),
                    ) is VehicleObservationAppendResult.Appended,
                )
                check(store.courseCompletions.observe(active.sessionId).first() == null)
            }

        private suspend fun prepared(taskIndex: Int): io.github.hangillab.hangil.core.domain.PreparedPractice {
            val course = store.catalog.courses.first()
            val goal = store.catalog.goals.first { it.id in course.goalIds }
            check(course.tasks.size > taskIndex)
            val selection = PracticeSelection(goal.id, course.id, course.version, course.briefingVersion, course.tasks[taskIndex].id)
            check(store.preparation.saveSelection(selection) { true })
            return (store.catalog.resolve(selection) as Preparation.Ready).practice
        }

        fun addNewerEnded(): PracticeSession =
            runBlocking {
                val start = store.sessions.start(prepared(1), TIME + 100_000) { true }
                check(start is SessionStartResult.Started)
                val result = store.sessions.finish(start.session, TIME + 120_000) { true }
                check(result is SessionFinishResult.Finished)
                result.session
            }

        fun mount(
            sessions: PracticeSessionRepository,
            reviews: SessionReviewRoadRepository? = null,
        ): FinishSourceReviewBoundaryTest.Driver {
            ui?.dispose()
            val memos =
                object : SessionMemoRepository by store.memos {
                    override fun observe(sessionId: String): Flow<SessionMemo?> {
                        memoQueries.add(sessionId)
                        return store.memos.observe(sessionId)
                    }
                }
            // This owned fixture has no runs. Check their real deletion snapshots; a write here must fail.
            val runs =
                object : RecordingRunRepository {
                    override fun observe(sessionId: String): Flow<List<VehicleRecordingRun>> =
                        throw AssertionError("A closed recording-run detail must not start a read")

                    override suspend fun open(
                        source: PracticeSession,
                        runId: String,
                        processId: String,
                        startedAtEpochMillis: Long,
                        canCommit: () -> Boolean,
                    ): RecordingRunOpenResult = throw AssertionError("Unexpected run creation in finish-review fixture")

                    override suspend fun finish(
                        runId: String,
                        processId: String,
                        status: RecordingRunStatus,
                        endedAtEpochMillis: Long?,
                    ): Boolean = throw AssertionError("Unexpected run mutation in finish-review fixture")

                    override suspend fun recoverInterrupted(processId: String): Unit = throw AssertionError("Unexpected run recovery")
                }
            return FinishSourceReviewBoundaryTest
                .Driver(
                    compose,
                    store.catalog,
                    store.preparation,
                    sessions,
                    memos,
                    store.observations,
                    runs,
                    store.courseCompletions,
                    store.deletions,
                    store.historyMemoWriter,
                    reviews,
                ).also {
                    ui = it
                    it.mount()
                    it.waitTag("home_journey")
                }
        }

        fun snapshot(): Data =
            runBlocking {
                withTimeout(TIMEOUT) {
                    val sessions = store.sessions.observeSessions().first()
                    Data(
                        store.preparation.observeSelection().first(),
                        sessions,
                        sessions.associate { it.sessionId to store.observations.observe(it.sessionId).first() },
                        sessions.associate { it.sessionId to store.memos.observe(it.sessionId).first() },
                        sessions.associate { it.sessionId to store.courseCompletions.observe(it.sessionId).first() },
                        sessions.filterNot { it.isActive }.associate { source ->
                            val prepared = store.deletions.prepare(source) { true }
                            check(prepared is SessionDeletionPreparation.Ready)
                            check(prepared.target.recordingRuns.isEmpty())
                            source.sessionId to prepared.target.recordingRuns
                        },
                        store.sequenceSnapshot(),
                    )
                }
            }

        fun assertOnlyEnded(
            before: Data,
            ended: PracticeSession,
        ) {
            check(ended.copy(endedAtEpochMillis = null) == active && ended.endedAtEpochMillis != null)
            val after = snapshot()
            check(after.sessions == before.sessions.map { if (it == active) ended else it })
            check(
                after.preparation == before.preparation && after.observations == before.observations && after.memos == before.memos &&
                    after.declarations == before.declarations && after.sequence == before.sequence,
            )
            check(after.runs.values.all { it.isEmpty() })
        }
    }

    private class Sessions(
        private val real: PracticeSessionRepository,
        private val before: List<PracticeSession>,
    ) : PracticeSessionRepository by real {
        val strictCalls = AtomicInteger(0)
        val legacyCalls = AtomicInteger(0)
        val completed = AtomicInteger(0)
        val deliver = MutableStateFlow(true)
        var onFinish: suspend (
            PracticeSession,
            Long,
            () -> Boolean,
        ) -> SessionFinishResult = { _, _, _ -> error("Unconfigured real Room boundary") }

        override fun observeSessions(): Flow<List<PracticeSession>> =
            combine(real.observeSessions(), deliver) { rows, allowed -> if (allowed) rows else before }

        override suspend fun finish(
            expectedSource: PracticeSession,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult {
            strictCalls.incrementAndGet()
            try {
                return onFinish(expectedSource, endedAtEpochMillis, canCommit)
            } finally {
                completed.incrementAndGet()
            }
        }

        override suspend fun finish(
            sessionId: String,
            endedAtEpochMillis: Long,
            canCommit: () -> Boolean,
        ): SessionFinishResult {
            legacyCalls.incrementAndGet()
            throw AssertionError("The real flow must use only the strict full-source writer")
        }
    }

    private data class Data(
        val preparation: PracticeSelection?,
        val sessions: List<PracticeSession>,
        val observations: Map<String, List<SessionVehicleObservation>>,
        val memos: Map<String, SessionMemo?>,
        val declarations: Map<String, CourseCompletionDeclaration?>,
        val runs: Map<String, List<VehicleRecordingRun>>,
        val sequence: Map<String, Long>,
    )

    private fun <T> await(value: CompletableDeferred<T>): T = runBlocking { withTimeout(TIMEOUT) { value.await() } }

    private companion object {
        const val SCHEMA_VERSION = 11
        const val TIME = FinishSourceReviewBoundaryTest.TIME
        const val TIMEOUT = FinishSourceReviewBoundaryTest.TIMEOUT

        fun assumeManual() = assumeTrue(InstrumentationRegistry.getArguments().getString("manualFinishSourceReviewRoom") == "true")
    }
}
