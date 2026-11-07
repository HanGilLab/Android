package io.github.hangillab.hangil

import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.DisplayMetrics
import android.util.TypedValue
import androidx.activity.compose.setContent
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.database.OwnedPracticeTestStore
import io.github.hangillab.hangil.core.designsystem.HanGilTheme
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.DrivingRestrictions
import io.github.hangillab.hangil.core.domain.DrivingRestrictionsProvider
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.LocationRecordingAppendResult
import io.github.hangillab.hangil.core.domain.LocationRecordingBeginResult
import io.github.hangillab.hangil.core.domain.LocationRecordingEndResult
import io.github.hangillab.hangil.core.domain.LocationRecordingRunStatus
import io.github.hangillab.hangil.core.domain.LocationRecordingSnapshot
import io.github.hangillab.hangil.core.domain.LocationRecordingStopReason
import io.github.hangillab.hangil.core.domain.LocationUpdateErrorOrigin
import io.github.hangillab.hangil.core.domain.LocationUpdateReading
import io.github.hangillab.hangil.core.domain.LocationUpdateSample
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.Preparation
import io.github.hangillab.hangil.core.domain.RecordingRunOpenResult
import io.github.hangillab.hangil.core.domain.RecordingRunStatus
import io.github.hangillab.hangil.core.domain.SessionDeletionPreparation
import io.github.hangillab.hangil.core.domain.SessionDeletionRepository
import io.github.hangillab.hangil.core.domain.SessionDeletionResult
import io.github.hangillab.hangil.core.domain.SessionDeletionTarget
import io.github.hangillab.hangil.core.domain.SessionFinishResult
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionMemoWriteResult
import io.github.hangillab.hangil.core.domain.SessionStartResult
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleGateway
import io.github.hangillab.hangil.core.domain.VehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationAppendResult
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleRecordingRun
import io.github.hangillab.hangil.core.domain.VehicleState
import io.github.hangillab.hangil.feature.home.SessionDeletionUiState
import io.github.hangillab.hangil.feature.home.SessionHistoryRoute
import io.github.hangillab.hangil.feature.home.SessionHistoryUiState
import io.github.hangillab.hangil.feature.home.SessionHistoryViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.IdentityHashMap
import java.util.Locale
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import io.github.hangillab.hangil.feature.home.R as HomeR

/**
 * Opt-in actual Room/SQLite and real Route/ViewModel correctness checks in a nonce-owned database.
 * All source values and SDK-kind observations here are synthetic fixtures, not actual DDS records,
 * user data, production performance or public screenshot evidence. MainActivity and the application
 * graph are never opened. Only real domain save/start/append/finish/memo APIs create fixture rows.
 * Snapshots compare domain fields/order and SQLite sequences, not all raw SQLite storage types.
 */
@RunWith(AndroidJUnit4::class)
class SessionDeletionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentCatalogActivity>()

    @Test fun locationDeletionDisclosurePreservesCancelledAndLockedTargets() =
        withFixture(syntheticLocations = true) { fixture ->
            val before = fixture.snapshot()
            val target = fixture.seed.older
            assertTrue(
                "DELETE_LOCATION_FIXTURE_PRESENT",
                before.locations
                    .getValue(target.sessionId)
                    .runs.size == 1,
            )
            val readings =
                before.locations
                    .getValue(target.sessionId)
                    .events
                    .map { it.reading }
            assertTrue(
                "DELETE_LOCATION_FIXTURE_SAMPLE_AND_STATES",
                readings.size == 3 && readings[0] is LocationUpdateReading.Sample &&
                    readings[1] is LocationUpdateReading.Empty && readings[2] is LocationUpdateReading.Error,
            )
            assertTrue("DELETE_RECORDING_FIXTURE_PRESENT", before.recordingRuns.getValue(target.sessionId).size == 1)
            val emptyLocations = before.locations.getValue(fixture.seed.latest.sessionId)
            assertTrue(
                "DELETE_LOCATION_ZERO_REQUIRES_EMPTY_LISTS",
                emptyLocations.events.isEmpty() && emptyLocations.runs.isEmpty() &&
                    before.recordingRuns.getValue(fixture.seed.latest.sessionId).isEmpty(),
            )
            openAndReadConfirmation(fixture, fixture.seed.latest)
            touch("history_delete_cancel")
            waitAbsent("history_delete_dialog")
            fixture.assertExact(before)
            emitDeletionPhase("EMPTY_COUNTS_VISIBLE")
            touch("history_older")
            fixture.awaitReady(target.sessionId)
            openAndReadConfirmation(fixture, target)
            bringIntoView("history_delete_location_counts")
            bringIntoView("history_delete_recording_run_count")
            assertTrue(
                "DELETE_LINKED_COUNTS_VISIBLE",
                listOf("history_delete_location_counts", "history_delete_location_run_count", "history_delete_recording_run_count")
                    .all { fullyVisible(nodes(it).single()) },
            )
            requireForeground()
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val directory = File(instrumentation.targetContext.cacheDir, "session-deletion-confirmations/${UUID.randomUUID()}")
            check(directory.mkdirs()) { "DELETE_CONFIRMATION_CACHE" }
            val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) { "DELETE_CONFIRMATION_CAPTURE" }
            try {
                File(directory, "linked-records.png").outputStream().use {
                    check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) { "DELETE_CONFIRMATION_WRITE" }
                }
            } finally {
                bitmap.recycle()
            }
            emitDeletionPhase("CONFIRMATION_VISIBLE")
            assertLocationDeletionDisclosure()
            touch("history_delete_cancel")
            waitAbsent("history_delete_dialog")
            fixture.assertExact(before)
            emitDeletionPhase("CANCEL_PRESERVED")

            openAndReadConfirmation(fixture, target)
            val staleConfirm = clickAction("history_delete_confirm")
            compose.runOnIdle {
                fixture.vehicleState.setWithoutEmission(driving())
                staleConfirm.invoke()
            }
            compose.waitForIdle()
            fixture.assertExact(before)
            assertTrue("DELETE_LOCATION_LOCK_REJECTED", fixture.deletions.calls.get() == 0)
            compose.runOnIdle {
                fixture.vehicleState.setWithoutEmission(parked())
                staleConfirm.invoke()
            }
            compose.waitForIdle()
            fixture.assertExact(before)
            assertTrue("DELETE_LOCATION_OLD_CONFIRM_RETIRED", fixture.deletions.calls.get() == 0)
            emitDeletionPhase("LOCK_PRESERVED")
            fixture.publishParked()
            fixture.awaitReady(target.sessionId)
            openAndReadConfirmation(fixture, target)
            assertLocationDeletionDisclosure()
            touch("history_delete_confirm")
            fixture.deletions.awaitCompletion()
            assertTrue("DELETE_LOCATION_ONCE", fixture.deletions.calls.get() == 1)
            fixture.assertOnlyDeleted(before, target.sessionId)
            waitFor("history_delete_success")
            emitDeletionPhase("SELECTED_CASCADE")
        }

    private fun emitDeletionPhase(code: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("stream", "LOCATION_DELETION_$code\n") },
        )
    }

    private fun assertLocationDeletionDisclosure() {
        bringIntoView("history_delete_warning")
        val node = nodes("history_delete_warning").single()
        val warning =
            node.config
                .getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .joinToString("") { it.text }
        // Independent semantic requirement: a resource compared with itself cannot detect this omission.
        assertTrue("DELETE_LOCATION_DISCLOSURE_MISSING", warning.contains("\uC704\uCE58 \uD45C\uBCF8\uACFC \uC218\uC9D1 \uC0C1\uD0DC"))
        assertLayout(node, warning)
    }

    @Test fun explicitSelectedDeletionCascadesOnceAndPreservesEveryOtherRowAndSequence() =
        withFixture { fixture ->
            val before = fixture.snapshot()
            val target = fixture.seed.older
            touch("history_older")
            fixture.awaitReady(target.sessionId)
            openAndReadConfirmation(fixture, target)
            touch("history_delete_cancel")
            waitAbsent("history_delete_dialog")
            fixture.assertExact(before)
            openAndReadConfirmation(fixture, target)
            val confirmation = clickAction("history_delete_confirm")
            compose.runOnIdle {
                confirmation.invoke()
                confirmation.invoke()
            }
            fixture.deletions.awaitCompletion()
            assertTrue("동일 확인은 실제 삭제를 한 번만 요청해야 합니다", fixture.deletions.calls.get() == 1)
            fixture.assertOnlyDeleted(before, target.sessionId)
            waitFor("history_delete_success")
            assertTrue("성공 뒤 다른 기록을 자동 선택하면 안 됩니다", fixture.model.uiState.value !is SessionHistoryUiState.Ready)
        }

    @Test fun absentMemoBecomingPresentInvalidatesTheOldConfirmation() = memoConflict(initialMemo = false)

    @Test fun changedMemoVersionInvalidatesTheOldConfirmation() = memoConflict(initialMemo = true)

    private fun memoConflict(initialMemo: Boolean) =
        withFixture(initialMemo) { fixture ->
            val target = fixture.seed.latest
            openAndReadConfirmation(fixture, target)
            val confirmation = clickAction("history_delete_confirm")
            val before = fixture.snapshot()
            val oldMemo = before.memos.getValue(target.sessionId)
            val changed =
                runBlocking {
                    fixture.store.memos.save(target.sessionId, "제어용 변경 메모", oldMemo?.version) { true }
                }
            assertTrue("실제 메모 저장으로만 충돌을 만들어야 합니다", changed is SessionMemoWriteResult.Saved)
            val changedData = fixture.snapshot()
            compose.runOnIdle { confirmation.invoke() }
            fixture.deletions.awaitCompletion()
            fixture.assertExact(changedData)
            waitFor("history_delete_error")
            assertTrue("연결 메모 충돌은 삭제 성공이면 안 됩니다", nodes("history_delete_success").isEmpty())
            assertTrue("실제 삭제 계약은 변경 충돌을 보고해야 합니다", fixture.deletions.lastResult.get() == SessionDeletionResult.Changed)
            compose.runOnIdle { confirmation.invoke() }
            fixture.assertExact(changedData)
            assertTrue("옛 충돌 확인을 다시 전송하면 안 됩니다", fixture.deletions.calls.get() == 1)
            openAndReadConfirmation(fixture, target)
            touch("history_delete_confirm")
            fixture.deletions.awaitCompletion()
            fixture.assertOnlyDeleted(changedData, target.sessionId)
        }

    @Test fun silentLiveDRejectsOldDisplayedConfirmationAndPDoesNotReviveIt() =
        withFixture { fixture ->
            val before = fixture.snapshot()
            openAndReadConfirmation(fixture, fixture.seed.latest)
            val originalReady = fixture.model.uiState.value
            val confirmation = clickAction("history_delete_confirm")
            compose.runOnIdle {
                fixture.vehicleState.setWithoutEmission(driving())
                assertTrue("UI가 아직 옛 Ready여야 무방출 경계를 재현합니다", fixture.model.uiState.value == originalReady)
                assertTrue("최신 live gate는 D를 거부해야 합니다", !fixture.model.canRead())
                confirmation.invoke()
            }
            compose.waitForIdle()
            fixture.assertExact(before)
            assertTrue("D에서 실제 삭제 저장소에 진입하면 안 됩니다", fixture.deletions.calls.get() == 0)
            compose.runOnIdle {
                fixture.vehicleState.setWithoutEmission(parked())
                confirmation.invoke()
            }
            compose.waitForIdle()
            fixture.assertExact(before)
            assertTrue("P 복귀는 옛 확인을 다시 활성화하면 안 됩니다", fixture.deletions.calls.get() == 0)
            fixture.publishParked()
            fixture.awaitReady(fixture.seed.latest.sessionId)
            openAndReadConfirmation(fixture, fixture.seed.latest)
            touch("history_delete_confirm")
            fixture.deletions.awaitCompletion()
            fixture.assertOnlyDeleted(before, fixture.seed.latest.sessionId)
        }

    @Test fun changingSelectionInvalidatesTheCapturedTargetAndRequiresNewConfirmation() =
        withFixture { fixture ->
            val before = fixture.snapshot()
            openAndReadConfirmation(fixture, fixture.seed.latest)
            val confirmation = clickAction("history_delete_confirm")
            val ready = fixture.model.uiState.value as SessionHistoryUiState.Ready
            compose.runOnIdle {
                assertTrue("실제 ViewModel의 명시 이전 이동이 가능해야 합니다", fixture.model.older(ready.session.sessionId, ready.generation))
            }
            fixture.awaitReady(fixture.seed.older.sessionId)
            waitAbsent("history_delete_dialog")
            compose.runOnIdle { confirmation.invoke() }
            fixture.assertExact(before)
            assertTrue("다른 선택의 옛 확인으로 삭제하면 안 됩니다", fixture.deletions.calls.get() == 0)
            openAndReadConfirmation(fixture, fixture.seed.older)
            touch("history_delete_confirm")
            fixture.deletions.awaitCompletion()
            fixture.assertOnlyDeleted(before, fixture.seed.older.sessionId)
        }

    @Test fun receivingChangedSessionSourceInvalidatesTheCapturedConfirmation() =
        withFixture { fixture ->
            openAndReadConfirmation(fixture, fixture.seed.latest)
            val confirmation = clickAction("history_delete_confirm")
            val generation = (fixture.model.uiState.value as SessionHistoryUiState.Ready).generation
            val finished =
                runBlocking {
                    fixture.store.sessions.finish(fixture.seed.active.sessionId, FIXED_TIME + 9_000) { true }
                }
            assertTrue("실제 종료 저장으로만 새 source를 만들어야 합니다", finished is SessionFinishResult.Finished)
            val changedData = fixture.snapshot()
            compose.waitUntil(20_000) {
                (fixture.model.uiState.value as? SessionHistoryUiState.Ready)?.let { it.generation != generation } == true
            }
            waitAbsent("history_delete_dialog")
            compose.runOnIdle { confirmation.invoke() }
            fixture.assertExact(changedData)
            assertTrue("수신 source 변경 뒤 옛 확인을 쓰면 안 됩니다", fixture.deletions.calls.get() == 0)
            fixture.awaitReady(fixture.seed.latest.sessionId)
            openAndReadConfirmation(fixture, fixture.seed.latest)
            touch("history_delete_confirm")
            fixture.deletions.awaitCompletion()
            fixture.assertOnlyDeleted(changedData, fixture.seed.latest.sessionId)
        }

    @Test fun stoppedOrRecreatedOwnerCannotBorrowAFreshConfirmation() =
        withFixture { fixture ->
            val before = fixture.snapshot()
            openAndReadConfirmation(fixture, fixture.seed.latest)
            val beforeStop = clickAction("history_delete_confirm")
            compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
            InstrumentationRegistry.getInstrumentation().runOnMainSync { beforeStop.invoke() }
            fixture.assertExact(before)
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            fixture.awaitReady(fixture.seed.latest.sessionId)
            waitAbsent("history_delete_dialog")
            compose.runOnIdle { beforeStop.invoke() }
            fixture.assertExact(before)
            openAndReadConfirmation(fixture, fixture.seed.latest)
            val beforeRecreate = clickAction("history_delete_confirm")
            compose.activityRule.scenario.recreate()
            fixture.mount()
            fixture.awaitReady(fixture.seed.latest.sessionId)
            waitAbsent("history_delete_dialog")
            compose.runOnIdle { beforeRecreate.invoke() }
            fixture.assertExact(before)
            assertTrue("옛 화면 수명으로 실제 삭제를 요청하면 안 됩니다", fixture.deletions.calls.get() == 0)
            openAndReadConfirmation(fixture, fixture.seed.latest)
            touch("history_delete_confirm")
            fixture.deletions.awaitCompletion()
            fixture.assertOnlyDeleted(before, fixture.seed.latest.sessionId)
        }

    @Test fun liveDAtThePostWriteGuardRollsBackActualCascade() = rollbackAfterWrite(stop = false)

    @Test fun stoppingAtThePostWriteGuardRollsBackActualCascade() = rollbackAfterWrite(stop = true)

    private fun rollbackAfterWrite(stop: Boolean) =
        withFixture { fixture ->
            val before = fixture.snapshot()
            openAndReadConfirmation(fixture, fixture.seed.latest)
            fixture.deletions.afterWrite.set {
                if (stop) {
                    compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
                } else {
                    fixture.vehicleState.setWithoutEmission(driving())
                }
            }
            touch("history_delete_confirm")
            fixture.deletions.awaitCompletion()
            assertTrue(
                "실제 삭제/cascade 뒤 guard에서만 차단을 주입해야 합니다",
                fixture.deletions.commitChecks.get() == 2 && fixture.deletions.postWriteRowsConfirmed.get(),
            )
            fixture.assertExact(before)
            val result = fixture.deletions.lastResult.get()
            assertTrue(
                "차단/취소를 삭제 성공으로 바꾸면 안 됩니다",
                result == SessionDeletionResult.Blocked || fixture.deletions.lastFailure.get() is CancellationException,
            )
            if (stop) compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            fixture.publishParked()
            fixture.awaitReady(fixture.seed.latest.sessionId)
            waitAbsent("history_delete_dialog")
            fixture.assertExact(before)
            fixture.deletions.afterWrite.set(null)
            openAndReadConfirmation(fixture, fixture.seed.latest)
            touch("history_delete_confirm")
            fixture.deletions.awaitCompletion()
            fixture.assertOnlyDeleted(before, fixture.seed.latest.sessionId)
        }

    private fun withFixture(
        latestMemo: Boolean = true,
        syntheticLocations: Boolean = false,
        block: (Fixture) -> Unit,
    ) {
        assumeTrue(
            "명시적인 격리 Room native 실행에서만 fixture를 삭제합니다",
            InstrumentationRegistry.getArguments().getString("manualSessionDeletion") == "true",
        )
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val permissionInvariant = InstrumentationRegistry.getArguments().getString("sdkFreeDeletionPermissionInvariant")
        check(permissionInvariant == null || permissionInvariant == "false" || permissionInvariant == "true")
        check(permissionInvariant != "true" || syntheticLocations) { "SDK_FREE_LOCATION_FIXTURE_REQUIRED" }
        val originalLocationPermission = context.checkSelfPermission("pleos.car.permission.FUSED_LOCATION")
        if (syntheticLocations) {
            // The separate SDK-free host mode never grants, revokes or exercises this permission.
            // Keep the original denied-host scenario as the default, without an automatic fallback.
            if (permissionInvariant == "true") {
                emitDeletionPhase("SDK_FREE_EXISTING_PERMISSION")
            } else {
                assertTrue("DELETE_LOCATION_FUSED_NOT_DENIED", originalLocationPermission == PackageManager.PERMISSION_DENIED)
                emitDeletionPhase("FUSED_DENIED")
            }
        }
        val store = OwnedPracticeTestStore.create(context)
        var fixture: Fixture? = null
        var primary: Throwable? = null
        try {
            establishHostForeground()
            applyCatalogResourceConfigurationIfRequested()
            verifyConfiguration()
            if (catalogResourceApplied.get()) emitDeletionPhase("RESOURCES_APPLIED")
            assertTrue("새 격리 저장소는 원래 데이터가 없는 스키마7여야 합니다", store.schemaVersion() == 7)
            assertTrue("새 격리 저장소의 FK 위반은 없어야 합니다", store.foreignKeyViolationCount() == 0)
            val seed = runBlocking { seed(store, latestMemo, syntheticLocations) }
            fixture = Fixture(store, seed)
            fixture.mount()
            fixture.awaitReady(seed.latest.sessionId)
            requireForeground()
            block(fixture)
        } catch (failure: Throwable) {
            primary = failure
            preserveFailure(fixture, failure)
            throw failure
        } finally {
            val cleanupFailures = mutableListOf<Throwable>()
            var storeClosed = false
            var safeToClose = fixture == null
            val current = fixture
            if (current != null) {
                try {
                    val disposal = current.dispose()
                    safeToClose = disposal.safeToClose
                    cleanupFailures.addAll(disposal.failures)
                } catch (cleanup: Throwable) {
                    cleanupFailures.add(cleanup)
                }
            }
            if (safeToClose) {
                try {
                    assertTrue("격리 검사 뒤 실제 FK 무결성을 유지해야 합니다", store.foreignKeyViolationCount() == 0)
                } catch (cleanup: Throwable) {
                    cleanupFailures.add(cleanup)
                }
                try {
                    store.close()
                    storeClosed = true
                } catch (cleanup: Throwable) {
                    cleanupFailures.add(cleanup)
                }
            } else {
                cleanupFailures.add(IllegalStateException("취소와 DB 작업 종료를 확인하지 못해 소유 격리 파일을 보존했습니다"))
            }
            try {
                restoreCatalogResourceConfiguration()
                if (catalogResourceRestored.get()) emitDeletionPhase("RESOURCES_RESTORED")
            } catch (cleanup: Throwable) {
                cleanupFailures.add(cleanup)
            }
            if (syntheticLocations && storeClosed) emitDeletionPhase("FIXTURE_CLOSED")
            if (syntheticLocations) {
                try {
                    assertTrue(
                        "DELETE_LOCATION_PERMISSION_CHANGED",
                        context.checkSelfPermission("pleos.car.permission.FUSED_LOCATION") == originalLocationPermission,
                    )
                    emitDeletionPhase("SDK_FREE_PERMISSION_UNCHANGED")
                } catch (cleanup: Throwable) {
                    cleanupFailures.add(cleanup)
                }
            }
            if (cleanupFailures.isNotEmpty()) {
                val original = primary
                if (original != null) {
                    cleanupFailures.filterNot { it === original }.forEach(original::addSuppressed)
                } else {
                    val first = cleanupFailures.first()
                    cleanupFailures.drop(1).filterNot { it === first }.forEach(first::addSuppressed)
                    throw first
                }
            }
        }
    }

    /** One setup request for the Rule-owned host; never recover focus during a scenario. */
    private fun establishHostForeground() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val scenario = compose.activityRule.scenario
        val deadline = SystemClock.elapsedRealtime() + 20_000
        lateinit var expectedHost: ComponentCatalogActivity
        scenario.onActivity { host ->
            expectedHost = host
            host.startActivity(
                Intent(host, ComponentCatalogActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
        }
        val remaining = deadline - SystemClock.elapsedRealtime()
        assertTrue("전면 setup 요청도 원래 20초 안에 끝나야 합니다", remaining > 0)
        compose.waitUntil(remaining) {
            var sameResumedFocusedHost = false
            scenario.onActivity { current ->
                sameResumedFocusedHost =
                    current === expectedHost && current.lifecycle.currentState == Lifecycle.State.RESUMED && current.hasWindowFocus()
            }
            sameResumedFocusedHost &&
                instrumentation.uiAutomation.rootInActiveWindow
                    ?.packageName
                    ?.toString() == instrumentation.targetContext.packageName
        }
        assertTrue("호스트 identity/RESUMED/포커스/실제 전면 확인이 20초 안에 끝나야 합니다", SystemClock.elapsedRealtime() <= deadline)
    }

    private suspend fun seed(
        store: OwnedPracticeTestStore,
        latestMemo: Boolean,
        syntheticLocations: Boolean,
    ): Seed {
        assertTrue(
            "fixture는 새 빈 DB에서만 생성해야 합니다",
            store.sessions
                .observeSessions()
                .first()
                .isEmpty(),
        )
        assertTrue(
            "새 DB에 준비나 sequence가 남으면 안 됩니다",
            store.preparation.observeSelection().first() == null && store.sequenceSnapshot().isEmpty(),
        )
        val course = store.catalog.courses.first()
        val goal = store.catalog.goals.first { it.id in course.goalIds }
        assertTrue("서로 다른 과제 두 개로 fixture를 만들어야 합니다", course.tasks.size >= 2)

        fun selection(index: Int) = PracticeSelection(goal.id, course.id, course.version, course.briefingVersion, course.tasks[index].id)

        suspend fun create(
            index: Int,
            offset: Long,
            finish: Boolean,
            memo: String?,
        ): PracticeSession {
            val selected = selection(index)
            assertTrue("fixture 준비는 실제 저장 경로로 만들어야 합니다", store.preparation.saveSelection(selected) { true })
            val practice = (store.catalog.resolve(selected) as Preparation.Ready).practice
            val started = store.sessions.start(practice, FIXED_TIME + offset) { true }
            assertTrue("fixture는 실제 시작 저장으로만 만듭니다", started is SessionStartResult.Started)
            val active = (started as SessionStartResult.Started).session
            val one = store.observations.append(active.sessionId, VehicleObservation(FIXED_TIME + offset + 100, parked()))
            assertTrue("SDK-kind fixture 관찰은 실제 append로 저장해야 합니다", one is VehicleObservationAppendResult.Appended)
            if (index == 0 && finish) {
                val two = store.observations.append(active.sessionId, VehicleObservation(FIXED_TIME + offset + 50, driving()))
                assertTrue("관찰 시각 역순도 원형으로 보존해야 합니다", two is VehicleObservationAppendResult.Appended)
            }
            if (syntheticLocations && finish && index == 0) {
                val background =
                    store.recordingRuns.open(
                        active,
                        "deletion-background-${UUID.randomUUID()}",
                        "deletion-background-process-${UUID.randomUUID()}",
                        FIXED_TIME + offset + 125,
                    ) { true }
                assertTrue("DELETE_RECORDING_OPEN", background is RecordingRunOpenResult.Opened)
                val backgroundRun = (background as RecordingRunOpenResult.Opened).run
                assertTrue(
                    "DELETE_RECORDING_FINISH",
                    store.recordingRuns.finish(
                        backgroundRun.runId,
                        backgroundRun.processId,
                        RecordingRunStatus.STOPPED,
                        FIXED_TIME + offset + 400,
                    ),
                )
                val begun =
                    store.locationRecordings.begin(
                        active,
                        "deletion-run-${UUID.randomUUID()}",
                        "deletion-process-${UUID.randomUUID()}",
                        FIXED_TIME + offset + 150,
                        offset + 100,
                    ) { true }
                assertTrue("DELETE_LOCATION_BEGIN", begun is LocationRecordingBeginResult.Begun)
                val run = (begun as LocationRecordingBeginResult.Begun).run
                val sample = LocationUpdateReading.Sample(LocationUpdateSample(12.5, 34.5, 0.0, 0f, 0f, 123, offset + 200))
                assertTrue(
                    "DELETE_LOCATION_SAMPLE",
                    store.locationRecordings.append(
                        active,
                        run,
                        sample,
                        FIXED_TIME + offset + 250,
                    ) { true } is LocationRecordingAppendResult.Appended,
                )
                listOf(
                    LocationUpdateReading.Empty(offset + 300),
                    LocationUpdateReading.Error(
                        LocationUpdateErrorOrigin.SDK_CALLBACK,
                        IllegalStateException::class.java.name,
                        offset + 310,
                    ),
                ).forEachIndexed { stateIndex, reading ->
                    assertTrue(
                        "DELETE_LOCATION_STATE",
                        store.locationRecordings.append(active, run, reading, FIXED_TIME + offset + 300 + stateIndex * 10) {
                            true
                        } is LocationRecordingAppendResult.Appended,
                    )
                }
                assertTrue(
                    "DELETE_LOCATION_END",
                    store.locationRecordings.end(
                        run,
                        LocationRecordingRunStatus.FAILED,
                        LocationRecordingStopReason.SDK_ERROR,
                        FIXED_TIME + offset + 350,
                        offset + 350,
                    ) is LocationRecordingEndResult.Ended,
                )
            }
            if (!finish) return active
            val ended = store.sessions.finish(active.sessionId, FIXED_TIME + offset + 1_000) { true }
            assertTrue("fixture는 실제 종료 저장으로만 만듭니다", ended is SessionFinishResult.Finished)
            if (memo != null) {
                assertTrue(
                    "사용자 메모형 fixture는 실제 저장으로만 만듭니다",
                    store.memos.save(active.sessionId, memo, null) { true } is SessionMemoWriteResult.Saved,
                )
            }
            return (ended as SessionFinishResult.Finished).session
        }
        val older = create(0, 0, true, "제어용 첫 메모")
        val latest = create(1, 2_000, true, if (latestMemo) "제어용 둘째 메모" else null)
        val active = create(0, 4_000, false, null)
        return Seed(older, latest, active)
    }

    private inner class Fixture(
        val store: OwnedPracticeTestStore,
        val seed: Seed,
    ) {
        val vehicleState = SilentLiveState(parked())
        private val restrictionState = SilentLiveState(DrivingRestrictions(ParkingState.PARKED, UxRestrictionState.UNRESTRICTED))
        private val vehicle =
            object : VehicleGateway {
                override val state: StateFlow<VehicleState> = vehicleState
            }
        private val restrictions =
            object : DrivingRestrictionsProvider {
                override val restrictions: StateFlow<DrivingRestrictions> = restrictionState
            }
        private val visible = mutableStateOf(true)
        private val ownedModelStores = IdentityHashMap<ViewModelStore, Unit>()
        private val readers = AtomicInteger(0)
        private val sessions =
            object : PracticeSessionRepository by store.sessions {
                override fun observeSessions(): Flow<List<PracticeSession>> =
                    flow {
                        readers.incrementAndGet()
                        try {
                            emitAll(store.sessions.observeSessions())
                        } finally {
                            readers.decrementAndGet()
                        }
                    }
            }
        val deletions = ControlledDeletion(store.deletions, store::deletionRowCounts)
        lateinit var model: SessionHistoryViewModel

        fun mount() {
            compose.activityRule.scenario.onActivity { activity ->
                // Record the real owner before construction, including a partially failed mount.
                ownedModelStores[activity.viewModelStore] = Unit
                val factory =
                    object : ViewModelProvider.Factory {
                        override fun <T : ViewModel> create(modelClass: Class<T>): T {
                            assertTrue("실제 History ViewModel만 생성해야 합니다", modelClass == SessionHistoryViewModel::class.java)
                            @Suppress("UNCHECKED_CAST")
                            return SessionHistoryViewModel(
                                vehicle,
                                restrictions,
                                sessions,
                                deletions,
                                store.observations,
                                UnexpectedHistoryMemoRepository,
                                UnexpectedHistoryRecordingRunRepository,
                                UnexpectedHistoryCourseCompletionRepository,
                                UnexpectedHistorySessionMemoWriter,
                            ) as T
                        }
                    }
                model = ViewModelProvider(activity, factory)[SessionHistoryViewModel::class.java]
                activity.setContent {
                    HanGilTheme {
                        if (visible.value) SessionHistoryRoute(onClose = { visible.value = false }, viewModel = model)
                    }
                }
            }
        }

        fun awaitReady(id: String) {
            compose.waitUntil(20_000) { (model.uiState.value as? SessionHistoryUiState.Ready)?.session?.sessionId == id }
            waitFor("history_ready")
            assertText("history_course", (model.uiState.value as SessionHistoryUiState.Ready).session.courseTitle)
        }

        fun publishParked() {
            // Give each real flow emission its own received state; do not rely on conflated D/P.
            compose.runOnIdle { vehicleState.publish(driving()) }
            compose.waitUntil(20_000) { model.uiState.value == SessionHistoryUiState.Locked }
            compose.runOnIdle { vehicleState.publish(parked()) }
        }

        fun snapshot(): ExistingData =
            runBlocking {
                withTimeout(20_000) {
                    val records = store.sessions.observeSessions().first()
                    ExistingData(
                        store.preparation.observeSelection().first(),
                        records,
                        records.associate { it.sessionId to store.observations.observe(it.sessionId).first() },
                        records.associate { it.sessionId to store.memos.observe(it.sessionId).first() },
                        records.associate { it.sessionId to store.locationRecordings.observe(it.sessionId).first() },
                        records.associate { it.sessionId to store.recordingRuns.observe(it.sessionId).first() },
                        store.sequenceSnapshot(),
                    )
                }
            }

        fun assertExact(expected: ExistingData) {
            assertTrue("격리 domain 필드/순서와 sequence를 보존해야 합니다", snapshot() == expected)
            assertTrue("실제 FK 위반이 생기면 안 됩니다", store.foreignKeyViolationCount() == 0)
        }

        fun assertOnlyDeleted(
            before: ExistingData,
            targetId: String,
        ) {
            val after = snapshot()
            assertTrue("삭제 대상 하나만 세션 목록에서 없어져야 합니다", after.sessions == before.sessions.filter { it.sessionId != targetId })
            assertTrue("준비는 삭제와 무관하게 보존해야 합니다", after.preparation == before.preparation)
            assertTrue("DELETE_LOCATION_RETAINED_ROWS", after.locations == before.locations.filterKeys { it != targetId })
            assertTrue("DELETE_RECORDING_RETAINED_ROWS", after.recordingRuns == before.recordingRuns.filterKeys { it != targetId })
            assertTrue("다른 연결 관찰의 ID/원문/순서를 보존해야 합니다", after.observations == before.observations.filterKeys { it != targetId })
            assertTrue("다른 메모의 원문과 version을 보존해야 합니다", after.memos == before.memos.filterKeys { it != targetId })
            assertTrue("삭제로 자동증가 sequence를 낮추면 안 됩니다", after.sequences == before.sequences)
            runBlocking {
                val deletedLocations = store.locationRecordings.observe(targetId).first()
                assertTrue("DELETE_LOCATION_SELECTED_CASCADE", deletedLocations.runs.isEmpty() && deletedLocations.events.isEmpty())
                assertTrue(
                    "DELETE_RECORDING_SELECTED_CASCADE",
                    store.recordingRuns
                        .observe(targetId)
                        .first()
                        .isEmpty(),
                )
                assertTrue(
                    "선택한 관찰만 실제 cascade로 없어져야 합니다",
                    store.observations
                        .observe(targetId)
                        .first()
                        .isEmpty(),
                )
                assertTrue("선택한 메모만 실제 cascade로 없어져야 합니다", store.memos.observe(targetId).first() == null)
            }
            assertTrue("스키마 버전과 FK 무결성을 보존해야 합니다", store.schemaVersion() == 7 && store.foreignKeyViolationCount() == 0)
        }

        fun failureState(): JSONObject =
            JSONObject()
                .put("modelInitialized", ::model.isInitialized)
                .put("uiStateKind", if (::model.isInitialized) model.uiState.value.javaClass.simpleName else JSONObject.NULL)
                .put("deletionStateKind", if (::model.isInitialized) model.deletionState.value.javaClass.simpleName else JSONObject.NULL)
                .put("activeReaders", readers.get())
                .put("activeMutationCalls", deletions.active.get())

        fun dispose(): FixtureDisposal {
            val failures = mutableListOf<Throwable>()
            var ownersCleared = false
            try {
                // Neither ActivityScenario state recovery nor a live Compose root is a prerequisite.
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    try {
                        visible.value = false
                    } catch (cleanup: Throwable) {
                        failures.add(cleanup)
                    }
                    if (::model.isInitialized) {
                        try {
                            model.deletionState.value.nonce
                                ?.let(model::dismissDeletion)
                        } catch (cleanup: Throwable) {
                            failures.add(cleanup)
                        }
                    }
                    ownersCleared = ownedModelStores.isNotEmpty() || !::model.isInitialized
                    for (owner in ownedModelStores.keys.toList()) {
                        try {
                            owner.clear()
                        } catch (cleanup: Throwable) {
                            ownersCleared = false
                            failures.add(cleanup)
                        }
                    }
                }
            } catch (cleanup: Throwable) {
                ownersCleared = false
                failures.add(cleanup)
            }
            var operationsStopped = false
            try {
                runBlocking {
                    withTimeout(20_000) {
                        while (readers.get() != 0 || deletions.active.get() != 0) delay(10)
                    }
                }
                operationsStopped = true
            } catch (cleanup: Throwable) {
                failures.add(cleanup)
            }
            return FixtureDisposal(ownersCleared && operationsStopped, failures)
        }
    }

    private data class FixtureDisposal(
        val safeToClose: Boolean,
        val failures: List<Throwable>,
    )

    /** Observations are taken before cleanup. Raw text, source identities and times are never serialized. */
    private fun preserveFailure(
        fixture: Fixture?,
        original: Throwable,
    ) {
        try {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val directory = File(instrumentation.targetContext.cacheDir, "session-deletion-failures/${UUID.randomUUID()}")
            check(directory.mkdirs()) { "새 비공개 실패 근거 폴더를 준비하지 못했습니다" }
            val details =
                JSONObject()
                    .put("schema", 1)
                    .put("fixtureState", fixture?.failureState() ?: JSONObject.NULL)
                    .put("foregroundInspectionComplete", false)
                    .put("foregroundAvailable", JSONObject.NULL)
                    .put("foregroundPackageMatches", JSONObject.NULL)
                    .put("screenshotCaptured", false)
                    .put("semanticsInspectionComplete", false)
            var screenshot: Bitmap? = null
            try {
                val foreground =
                    instrumentation.uiAutomation.rootInActiveWindow
                        ?.packageName
                        ?.toString()
                details.put("foregroundAvailable", foreground != null)
                details.put(
                    "foregroundPackageMatches",
                    foreground?.let { it == instrumentation.targetContext.packageName } ?: JSONObject.NULL,
                )
                details.put("foregroundInspectionComplete", true)
                val captured = instrumentation.uiAutomation.takeScreenshot()
                screenshot = captured
                if (captured != null) {
                    val written =
                        File(directory, "failure.png").outputStream().use {
                            captured.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                    details.put("screenshotCaptured", written)
                    check(written) { "실제 실패 화면을 저장하지 못했습니다" }
                }
            } catch (diagnostic: Throwable) {
                if (diagnostic !== original) original.addSuppressed(diagnostic)
            } finally {
                screenshot?.recycle()
            }
            val counts = JSONObject()
            details.put("tagCounts", counts)
            try {
                listOf(
                    "history_ready",
                    "history_course",
                    "history_task",
                    "history_locked",
                    "history_loading",
                    "history_failed",
                    "history_selection_changed",
                    "history_delete_dialog",
                    "history_delete_confirm",
                    "history_delete_loading",
                    "history_delete_error",
                    "history_delete_success",
                ).forEach { counts.put(it, nodes(it).size) }
                details.put(
                    "semanticsRootCount",
                    compose
                        .onAllNodes(isRoot(), useUnmergedTree = true)
                        .fetchSemanticsNodes(atLeastOneRootRequired = false)
                        .size,
                )
                details.put("semanticsInspectionComplete", true)
            } catch (diagnostic: Throwable) {
                if (diagnostic !== original) original.addSuppressed(diagnostic)
            }
            File(directory, "failure.json").writeText(details.toString(2))
        } catch (diagnostic: Throwable) {
            // Evidence failure supplements the original assertion and never changes its outcome.
            if (diagnostic !== original) original.addSuppressed(diagnostic)
        }
    }

    private class ControlledDeletion(
        private val delegate: SessionDeletionRepository,
        private val rowCounts: (String) -> Triple<Long, Long, Long>,
    ) : SessionDeletionRepository by delegate {
        val calls = AtomicInteger(0)
        val active = AtomicInteger(0)
        val commitChecks = AtomicInteger(0)
        val postWriteRowsConfirmed = AtomicBoolean(false)
        val afterWrite = AtomicReference<(() -> Unit)?>(null)
        val lastResult = AtomicReference<SessionDeletionResult?>(null)
        val lastFailure = AtomicReference<Throwable?>(null)
        private val completions = LinkedBlockingQueue<Unit>()

        override suspend fun prepare(
            expected: PracticeSession,
            canRead: () -> Boolean,
        ): SessionDeletionPreparation {
            active.incrementAndGet()
            try {
                return delegate.prepare(expected, canRead)
            } finally {
                active.decrementAndGet()
            }
        }

        override suspend fun delete(
            target: SessionDeletionTarget,
            canCommit: () -> Boolean,
        ): SessionDeletionResult {
            calls.incrementAndGet()
            active.incrementAndGet()
            commitChecks.set(0)
            postWriteRowsConfirmed.set(false)
            lastResult.set(null)
            lastFailure.set(null)
            try {
                return delegate
                    .delete(target) {
                        val checkNumber = commitChecks.incrementAndGet()
                        val injection = afterWrite.get()
                        if (injection != null) {
                            val rows = rowCounts(target.session.sessionId)
                            if (checkNumber == 1) {
                                check(rows == Triple(1L, target.observationCount, if (target.memoVersion == null) 0L else 1L)) {
                                    "쓰기 전 실제 대상/연결 자료가 확인 스냅샷과 달라졌습니다"
                                }
                            } else if (checkNumber == 2) {
                                check(rows == Triple(0L, 0L, 0L)) { "실제 삭제/cascade 전에 쓰기 후 차단을 주입할 수 없습니다" }
                                postWriteRowsConfirmed.set(true)
                                injection()
                            } else {
                                error("예상하지 않은 commit guard 순서입니다")
                            }
                        }
                        canCommit()
                    }.also(lastResult::set)
            } catch (failure: Throwable) {
                lastFailure.set(failure)
                throw failure
            } finally {
                active.decrementAndGet()
                completions.offer(Unit)
            }
        }

        fun awaitCompletion() {
            assertTrue("실제 삭제 요청이 끝나야 합니다", completions.poll(20, TimeUnit.SECONDS) != null)
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

    private fun openAndReadConfirmation(
        fixture: Fixture,
        target: PracticeSession,
    ) {
        fixture.awaitReady(target.sessionId)
        touch("history_delete")
        waitFor("history_delete_dialog")
        waitFor("history_delete_confirm")
        assertDialogReadable()
        assertText("history_delete_course", target.courseTitle)
        assertText("history_delete_task", target.taskTitle)
        assertText(
            "history_delete_finished",
            text(HomeR.string.session_finished_at, displayTime(checkNotNull(target.endedAtEpochMillis))),
        )
        val rows =
            runBlocking {
                fixture.store.observations
                    .observe(target.sessionId)
                    .first()
            }
        val memo =
            runBlocking {
                fixture.store.memos
                    .observe(target.sessionId)
                    .first()
            }
        val locations =
            runBlocking {
                fixture.store.locationRecordings
                    .observe(target.sessionId)
                    .first()
            }
        val recordingRuns =
            runBlocking {
                fixture.store.recordingRuns
                    .observe(target.sessionId)
                    .first()
            }
        val prepared = (fixture.model.deletionState.value as SessionDeletionUiState.Confirming).target
        assertTrue(
            "확인창은 선택한 원본과 실제 Room 연결 자료의 준비된 스냅샷을 표시해야 합니다",
            prepared.session == target && prepared.observationCount == rows.size.toLong() && prepared.memoVersion == memo?.version &&
                prepared.locationRecording == locations && prepared.recordingRuns == recordingRuns,
        )
        val sampleCount = locations.events.count { it.reading is LocationUpdateReading.Sample }
        val stateCount = locations.events.count { it.reading !is LocationUpdateReading.Sample }
        assertText("history_delete_location_counts", text(HomeR.string.history_delete_location_counts, sampleCount, stateCount))
        assertText("history_delete_location_run_count", text(HomeR.string.history_delete_location_run_count, locations.runs.size))
        assertText("history_delete_recording_run_count", text(HomeR.string.history_delete_recording_run_count, recordingRuns.size))
        assertText("history_delete_observation_count", text(HomeR.string.history_delete_observation_count, rows.size))
        assertText(
            "history_delete_memo_status",
            text(if (memo == null) HomeR.string.history_delete_memo_absent else HomeR.string.history_delete_memo_present),
        )
        assertText("history_delete_warning", text(HomeR.string.history_delete_warning))
        bringIntoView("history_delete_cancel")
        bringIntoView("history_delete_confirm")
        assertTrue(
            "삭제 확인과 취소를 실제 화면에서 조작할 수 있어야 합니다",
            !nodes("history_delete_confirm").single().config.contains(SemanticsProperties.Disabled),
        )
    }

    private fun clickAction(tag: String): () -> Boolean {
        bringIntoView(tag)
        return checkNotNull(
            nodes(tag)
                .single()
                .config
                .getOrNull(SemanticsActions.OnClick)
                ?.action,
        )
    }

    private data class Seed(
        val older: PracticeSession,
        val latest: PracticeSession,
        val active: PracticeSession,
    )

    private data class ExistingData(
        val preparation: PracticeSelection?,
        val sessions: List<PracticeSession>,
        val observations: Map<String, List<SessionVehicleObservation>>,
        val memos: Map<String, SessionMemo?>,
        val locations: Map<String, LocationRecordingSnapshot>,
        val recordingRuns: Map<String, List<VehicleRecordingRun>>,
        val sequences: Map<String, Long>,
    )

    private companion object {
        const val FIXED_TIME = 1_700_000_000_000L

        fun parked() = VehicleState(connection = ConnectionState.CONNECTED, gear = VehicleReading.Available(GearPosition.PARK))

        fun driving() = parked().copy(gear = VehicleReading.Available(GearPosition.DRIVE))
    }

    private val catalogResourceConfiguration = AtomicReference<CatalogResourceConfiguration?>(null)
    private val catalogResourceApplied = AtomicBoolean(false)
    private val catalogResourceRestored = AtomicBoolean(false)

    private class CatalogResourceState(
        val resources: Resources,
    ) {
        val configuration = Configuration(resources.configuration)
        val metrics = DisplayMetrics().apply { setTo(resources.displayMetrics) }
    }

    private class CatalogThemeState(
        val theme: Resources.Theme,
        val attributes: List<Int>,
    )

    private class CatalogResourceConfiguration(
        val activity: ComponentCatalogActivity,
        val target: CatalogResourceState,
        val related: List<CatalogResourceState>,
        val themes: List<CatalogThemeState>,
    )

    // Selected cache checks only, matching the existing LocationRecordingFlowTest pattern.
    private fun catalogThemeAttributes(theme: Resources.Theme): List<Int> =
        listOf(android.R.attr.colorBackground, android.R.attr.textColorPrimary, android.R.attr.windowBackground).flatMap { attribute ->
            val value = TypedValue()
            val resolved = theme.resolveAttribute(attribute, value, true)
            listOf(if (resolved) 1 else 0, value.type, value.data, value.resourceId)
        }

    private fun catalogResourceConfigurationRequested(): Boolean {
        val args = InstrumentationRegistry.getArguments()
        val requested = args.getString("useCatalogResourceConfiguration")
        check(requested == null || requested == "false" || requested == "true") { "SDRC_FLAG" }
        if (requested != "true") return false
        check(args.getString("manualSessionDeletion") == "true") { "SDRC_OPT_IN" }
        check(args.getString("expectedFontScale") in setOf("1.0", "1.3")) { "SDRC_FONT_ARGUMENT" }
        check(args.getString("expectedDark") in setOf("false", "true")) { "SDRC_NIGHT_ARGUMENT" }
        return true
    }

    @Suppress("DEPRECATION")
    private fun applyCatalogResourceConfigurationIfRequested() {
        if (!catalogResourceConfigurationRequested()) return
        check(!catalogResourceApplied.get() && catalogResourceConfiguration.get() == null) { "SDRC_SINGLE_OWNER" }
        val instrument = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val scale = checkNotNull(args.getString("expectedFontScale")).toFloat()
        val dark = args.getString("expectedDark") == "true"
        instrument.runOnMainSync {
            val activity = compose.activity
            val contexts = listOf(activity, instrument.targetContext, activity.applicationContext, instrument.context)
            val resources = IdentityHashMap<Resources, CatalogResourceState>()
            contexts.forEach { context -> resources.getOrPut(context.resources) { CatalogResourceState(context.resources) } }
            val system = Resources.getSystem()
            resources.getOrPut(system) { CatalogResourceState(system) }
            val themes = IdentityHashMap<Resources.Theme, CatalogThemeState>()
            contexts.forEach { context ->
                context.theme?.let { theme -> themes.getOrPut(theme) { CatalogThemeState(theme, catalogThemeAttributes(theme)) } }
            }
            val target = checkNotNull(resources[activity.resources])
            check(target.resources !== system) { "SDRC_SYSTEM_RESOURCE_OWNER" }
            val saved = CatalogResourceConfiguration(activity, target, resources.values.toList(), themes.values.toList())
            // Save before the first mutation, so assertion/partial-application failure still restores.
            check(catalogResourceConfiguration.compareAndSet(null, saved)) { "SDRC_CAPTURE_OWNER" }
            val expected =
                Configuration(target.configuration).apply {
                    fontScale = scale
                    uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                        if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                }
            target.resources.updateConfiguration(expected, DisplayMetrics().apply { setTo(target.metrics) })
            saved.themes.forEach { it.theme.rebase() }
            check(activity.resources === target.resources && activity.resources.configuration == expected) { "SDRC_ACTUAL_ACTIVITY" }
            val metrics = activity.resources.displayMetrics
            check(
                metrics.widthPixels == target.metrics.widthPixels && metrics.heightPixels == target.metrics.heightPixels &&
                    metrics.densityDpi == target.metrics.densityDpi && metrics.density == target.metrics.density &&
                    metrics.xdpi == target.metrics.xdpi && metrics.ydpi == target.metrics.ydpi,
            ) { "SDRC_PHYSICAL_METRICS" }
            saved.related.filterNot { it.resources === target.resources }.forEach { before ->
                check(before.resources.configuration == before.configuration && before.resources.displayMetrics == before.metrics) {
                    "SDRC_UNRELATED_RESOURCES"
                }
            }
            check(catalogResourceApplied.compareAndSet(false, true)) { "SDRC_APPLIED_ONCE" }
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreCatalogResourceConfiguration() {
        val saved = catalogResourceConfiguration.get() ?: return
        val failures = mutableListOf<Throwable>()

        fun cleanup(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                failures.add(failure)
            }
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            check(catalogResourceConfiguration.get() === saved) { "SDRC_RESTORE_OWNER" }
            cleanup {
                saved.target.resources.updateConfiguration(
                    Configuration(saved.target.configuration),
                    DisplayMetrics().apply { setTo(saved.target.metrics) },
                )
            }
            saved.themes.forEach { before -> cleanup { before.theme.rebase() } }
            cleanup { check(saved.activity.resources === saved.target.resources) { "SDRC_RESTORE_ACTIVITY" } }
            saved.related.forEach { before ->
                cleanup {
                    check(before.resources.configuration == before.configuration && before.resources.displayMetrics == before.metrics) {
                        "SDRC_RESTORE_RESOURCES"
                    }
                }
            }
            saved.themes.forEach { before ->
                cleanup { check(catalogThemeAttributes(before.theme) == before.attributes) { "SDRC_RESTORE_THEME_ATTRIBUTES" } }
            }
            if (failures.isEmpty()) {
                check(catalogResourceConfiguration.compareAndSet(saved, null)) { "SDRC_RETIRE_OWNER" }
                catalogResourceRestored.set(true)
            }
        }
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).filterNot { it === first }.forEach(first::addSuppressed)
            throw first
        }
    }

    private fun verifyConfiguration() {
        val arguments = InstrumentationRegistry.getArguments()
        val expectedScale =
            arguments.getString("expectedFontScale")?.let { checkNotNull(it.toFloatOrNull()) { "글꼴 선언이 잘못됐습니다" } } ?: 1f
        val expectedDark =
            arguments.getString("expectedDark")?.let { checkNotNull(it.toBooleanStrictOrNull()) { "야간 선언이 잘못됐습니다" } } ?: false
        assertTrue("정의한 글꼴 조건만 검사합니다", expectedScale == 1f || expectedScale == 1.3f)
        val configuration = compose.activity.resources.configuration
        assertTrue("실제 글꼴 조건이 선언과 같아야 합니다", kotlin.math.abs(configuration.fontScale - expectedScale) < 0.001f)
        assertTrue(
            "실제 야간 조건이 선언과 같아야 합니다",
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) == expectedDark,
        )
    }

    private fun assertDialogReadable() {
        val original =
            descendants(nodes("history_delete_dialog").single())
                .filter {
                    it.config
                        .getOrNull(SemanticsProperties.Text)
                        .orEmpty()
                        .isNotEmpty()
                }
        assertTrue("실제 확인창의 경고 본문을 확인해야 합니다", original.isNotEmpty())
        for (node in original) {
            val expected =
                node.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .joinToString("") { it.text }
            val matcher = SemanticsMatcher("익명 확인창 텍스트 노드") { it.id == node.id }
            if (!fullyVisible(node)) compose.onNode(matcher, useUnmergedTree = true).performScrollTo()
            compose.waitForIdle()
            val current = compose.onNode(matcher, useUnmergedTree = true).fetchSemanticsNode()
            assertTrue("확인창 원문 전체가 실제 화면에 보여야 합니다", fullyVisible(current))
            assertLayout(current, expected)
        }
    }

    private fun assertText(
        tag: String,
        expected: String,
    ) {
        bringIntoView(tag)
        val target = nodes(tag).single()
        val matches =
            descendants(target).filter {
                it.config
                    .getOrNull(SemanticsProperties.Text)
                    .orEmpty()
                    .any { value -> value.text == expected }
            }
        assertTrue("표시값은 저장 원문 또는 실제 연결 수와 일치해야 합니다", matches.isNotEmpty())
        matches.forEach { assertLayout(it, expected) }
    }

    private fun assertLayout(
        node: SemanticsNode,
        expected: String,
    ) {
        var valid = false
        compose.runOnIdle {
            val layouts = mutableListOf<TextLayoutResult>()
            node.config
                .getOrNull(SemanticsActions.GetTextLayoutResult)
                ?.action
                ?.invoke(layouts)
            val matching = layouts.filter { it.layoutInput.text.text == expected }
            val actualScale = compose.activity.resources.configuration.fontScale
            valid = matching.isNotEmpty() &&
                matching.all {
                    kotlin.math.abs(it.layoutInput.density.fontScale - actualScale) < 0.001f && textGeometryFits(it, expected)
                }
        }
        assertTrue("전체 글자와 줄이 잘리거나 말줄임되지 않아야 합니다", valid)
    }

    /** Paragraph container width can exceed tight semantics size; check real lines and glyph coverage. */
    private fun textGeometryFits(
        layout: TextLayoutResult,
        expected: String,
    ): Boolean {
        if (layout.layoutInput.text.text != expected || expected.isEmpty() || layout.size.width <= 0 || layout.size.height <= 0 ||
            layout.lineCount <= 0 || layout.multiParagraph.didExceedMaxLines
        ) {
            return false
        }
        var covered = 0
        for (line in 0 until layout.lineCount) {
            val end = layout.getLineEnd(line, visibleEnd = false)
            if (layout.getLineStart(line) != covered || end < covered || end > expected.length || layout.isLineEllipsized(line)) {
                return false
            }
            if (!inside(
                    layout.getLineLeft(line),
                    layout.getLineTop(line),
                    layout.getLineRight(line),
                    layout.getLineBottom(line),
                    layout.size,
                )
            ) {
                return false
            }
            covered = end
        }
        if (covered != expected.length) return false
        for (index in expected.indices) {
            if (expected[index].isWhitespace()) continue
            val box = layout.getBoundingBox(index)
            if (!inside(box.left, box.top, box.right, box.bottom, layout.size)) return false
        }
        return true
    }

    private fun inside(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        size: IntSize,
    ): Boolean =
        left.isFinite() && top.isFinite() && right.isFinite() && bottom.isFinite() && left <= right && top <= bottom &&
            left >= -1f && top >= -1f && right <= size.width + 1f && bottom <= size.height + 1f

    private fun nodes(tag: String): List<SemanticsNode> =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)

    private fun waitFor(tag: String) = compose.waitUntil(20_000) { nodes(tag).size == 1 }

    private fun waitAbsent(tag: String) = compose.waitUntil(20_000) { nodes(tag).isEmpty() }

    private fun touch(tag: String) {
        bringIntoView(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
    }

    private fun bringIntoView(tag: String) {
        waitFor(tag)
        if (!fullyVisible(nodes(tag).single())) compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo()
        compose.waitUntil(15_000) { nodes(tag).singleOrNull()?.let(::fullyVisible) == true }
    }

    private fun fullyVisible(node: SemanticsNode): Boolean {
        val available = Rect()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            compose.activity.window.decorView
                .getWindowVisibleDisplayFrame(available)
        }
        val bounds = node.boundsInWindow
        val origin = node.positionOnScreen - node.positionInWindow
        return node.size.width > 0 && node.size.height > 0 &&
            bounds.width >= node.size.width - 1 && bounds.height >= node.size.height - 1 &&
            bounds.left + origin.x >= available.left - 1 && bounds.top + origin.y >= available.top - 1 &&
            bounds.right + origin.x <= available.right + 1 && bounds.bottom + origin.y <= available.bottom + 1
    }

    private fun requireForeground() {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val expected = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        compose.waitUntil(20_000) { automation.rootInActiveWindow?.packageName?.toString() == expected }
        assertTrue("실제 앱 전면에서만 확인창을 읽어야 합니다", automation.rootInActiveWindow?.packageName?.toString() == expected)
    }

    private fun displayTime(time: Long): String =
        DateTimeFormatter
            .ofLocalizedDateTime(FormatStyle.MEDIUM)
            .withLocale(Locale.getDefault())
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(time))

    private fun text(
        resource: Int,
        vararg arguments: Any,
    ): String = InstrumentationRegistry.getInstrumentation().targetContext.getString(resource, *arguments)
}
