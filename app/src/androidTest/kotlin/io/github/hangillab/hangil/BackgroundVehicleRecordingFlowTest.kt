package io.github.hangillab.hangil

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dagger.hilt.android.EntryPointAccessors
import io.github.hangillab.hangil.core.domain.BackgroundRecordingState
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.RecordingRunStatus
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleObservationSource
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleRecordingRun
import io.github.hangillab.hangil.core.domain.VehicleRecordingState
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import kotlin.math.abs

/**
 * Explicit opt-in actual Main/SDK/Room validation after both comparison measurements. The first
 * flow creates one owned session through the real UI; both themes, process interruption and
 * recovery reuse it. Original preparation, sessions, observations, memos and runs are read only.
 * Evidence and ownership markers stay in private app cache. The external runner owns DDS and
 * restores its vehicle/settings changes. No location, route, permission or SDK state is replaced.
 * The recording flow hands off to the real navigation app through its system launcher intent.
 * This is independent Main/FGS/vehicle evidence, not proof that the app's Navi SDK request opens
 * the navigation screen. Instrumentation itself affects process priority: Main STOP plus native
 * FGS plus received SDK snapshots is the claim, not continuous uninstrumented road support.
 * Foreground evidence comes only from the focused accessibility window on Main's captured display;
 * it does not prove completed navigation rendering or the app's Navi SDK screen-open success.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundVehicleRecordingFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private lateinit var mainActivity: MainActivity
    private var mainDisplayId: Int? = null
    private var interactiveWindowsFlagBefore: Boolean? = null
    private var lastForegroundObservation = ForegroundObservation(null, null, null, false, null, "NOT_OBSERVED")
    private val foregroundUnknownReasons = linkedMapOf<String, Long>()

    // Main-display focused accessibility window/package null samples, never SDK event-loss counts.
    private var foregroundNullSamples = 0L
    private val evidence by lazy {
        File(context.cacheDir, "screenshots/native-background-recording-${UUID.randomUUID()}").apply { check(mkdirs()) }
    }

    @After fun restoreInteractiveWindowsObservationFlag() {
        val original = interactiveWindowsFlagBefore ?: return
        val automation = instrumentation.uiAutomation
        val current = checkNotNull(automation.serviceInfo) { "UiAutomation flag 복원 정보를 읽어야 합니다" }
        val bit = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        val restoredFlags = if (original) current.flags or bit else current.flags and bit.inv()
        if (restoredFlags != current.flags) {
            current.flags = restoredFlags
            automation.serviceInfo = current
        }
        val readback = checkNotNull(automation.serviceInfo) { "UiAutomation flag 복원 결과를 읽어야 합니다" }
        assertTrue("기존 interactive-windows bit를 다른 flags 보존 상태로 복원해야 합니다", (readback.flags and bit != 0) == original)
        interactiveWindowsFlagBefore = null
    }

    @Test fun actualSdkBackgroundRecordingSurvivesRealNavigationAndExplicitStop() =
        runBlocking {
            assertEnvironment("flow")
            val graph = graph()
            awaitAllowed(graph)
            val owner = loadOrCreateOwner(graph)
            guarded(graph, owner) {
                val source = ownedActive(graph, owner)
                assertNoOpenRun(graph, source.sessionId)
                awaitNoService()
                waitTag("background_recording_enable")
                verifyGlyphs("background_recording_scope")
                verifyGlyphs("background_recording_enable")
                capture("main-before-enable")
                phase("BG_RECORDING_OWNED_SESSION_READY")
                val active = enableThroughUi(graph, owner, source)
                capture("main-active-open")
                phase("BG_RECORDING_ACTIVE_OPEN")

                launchSystemNavigation()
                awaitStableNavigation()
                awaitMainBelowStarted()
                assertActiveOwnership(graph, active)
                capture("actual-navigation-main-stopped")
                writeProof("navigation-main-stopped", graph, source.sessionId)
                phase("BG_RECORDING_NAVI_FOREGROUND_MAIN_STOPPED")
                val beforeDrive = observations(graph, source.sessionId).lastOrNull()?.id ?: 0
                phase("BG_RECORDING_AWAIT_D")
                awaitStoredGear(graph, source.sessionId, GearPosition.DRIVE, beforeDrive)
                assertNavigationStillForeground(graph, active)
                assertActiveOwnership(graph, active)
                capture("actual-navigation-drive-appended")
                writeProof("drive-appended-with-navigation", graph, source.sessionId)
                phase("BG_RECORDING_DRIVE_APPENDED_IN_NAVI")
                val beforePark = observations(graph, source.sessionId).lastOrNull()?.id ?: 0
                phase("BG_RECORDING_AWAIT_P")
                awaitStoredGear(graph, source.sessionId, GearPosition.PARK, beforePark)
                assertNavigationStillForeground(graph, active)
                assertActiveOwnership(graph, active)
                capture("actual-navigation-park-appended")
                writeProof("park-appended-with-navigation", graph, source.sessionId)
                phase("BG_RECORDING_PARK_APPENDED_IN_NAVI")

                returnToMain()
                awaitAllowed(graph)
                waitTag("background_recording_stop")
                assertActiveOwnership(graph, active)
                capture("main-fresh-return")
                phase("BG_RECORDING_MAIN_FRESH_RETURN")
                phase("BG_RECORDING_AWAIT_D_MAIN")
                awaitGearValue(graph, GearPosition.DRIVE)
                waitTag("home_locked_title")
                assertTrue("실제 D에서는 상세 조작과 종료가 잠겨야 합니다", nodes("navigation_screen_open").isEmpty() && nodes("session_finish").isEmpty())
                assertTrue("실제 D에서 새 백그라운드 시작을 허용하면 안 됩니다", nodes("background_recording_enable").isEmpty())
                assertActiveOwnership(graph, active)
                capture("main-real-drive-locked")
                phase("BG_RECORDING_MAIN_D_LOCKED")
                phase("BG_RECORDING_AWAIT_P_MAIN")
                awaitAllowed(graph, 90_000)
                waitTag("background_recording_stop")
                stopThroughUi(graph, active)
                waitTag("background_recording_enable")
                assertTrue("명시적 중지는 연습을 종료하면 안 됩니다", ownedActive(graph, owner) == source)
                withTimeout(15_000) { graph.recording().state.first { it == VehicleRecordingState.Recording } }
                assertTrue("복귀한 Main의 일반 기록은 계속 동작할 수 있어야 합니다", graph.recording().state.value == VehicleRecordingState.Recording)
                verifyGlyphs("background_recording_scope")
                verifyGlyphs("background_recording_state")
                capture("main-explicitly-stopped")
                writeProof("explicit-stop-main-resumed", graph, source.sessionId)
                phase("BG_RECORDING_STOPPED")
                val themes = owner.getJSONArray("completed_themes")
                if (themeKey() !in strings(themes)) themes.put(themeKey())
                owner.put("phase", "flow_ready").put("last_owned_snapshot", snapshotJson(snapshot(graph)))
                writeOwner(owner)
                assertOriginalRowsPreserved(graph, owner)
                phase("BG_RECORDING_FLOW_COMPLETED")
            }
        }

    /** This fixture must be externally aborted; reaching its timeout is a failed fixture, not PASS. */
    @Test fun prepareOpenBackgroundRunForExternalProcessStop() =
        runBlocking {
            assertEnvironment("interrupt")
            val graph = graph()
            val owner = readOwner()
            assertBothThemesCompleted(owner)
            guarded(graph, owner) {
                awaitAllowed(graph)
                val source = ownedActive(graph, owner)
                assertNoOpenRun(graph, source.sessionId)
                val active = enableThroughUi(graph, owner, source)
                val rows = runs(graph, source.sessionId)
                val open = rows.single { it.runId == active.runId }
                owner
                    .put("phase", "awaiting_process_stop")
                    .put("interruption_run", runJson(open))
                    .put("owned_runs_before_process_stop", JSONObject().apply { rows.forEach { put(it.runId, it.toString()) } })
                writeOwner(owner)
                capture("main-ready-for-external-process-stop")
                writeProof("ready-for-external-process-stop", graph, source.sessionId)
                assertOriginalRowsPreserved(graph, owner)
                phase("BG_RECORDING_READY_FOR_PROCESS_STOP")
                val until = SystemClock.elapsedRealtime() + 90_000
                while (SystemClock.elapsedRealtime() < until) {
                    assertActiveOwnership(graph, active)
                    delay(100)
                }
                throw AssertionError("외부 프로세스 중단 전용 fixture가 종료 없이 시간 제한에 도달했습니다. 이 실행은 성공이 아닙니다")
            }
        }

    @Test fun interruptedBackgroundRunRequiresFreshEnableThenFinishesOwnedSession() =
        runBlocking {
            assertEnvironment("recover")
            val graph = graph()
            val owner = readOwner()
            assertBothThemesCompleted(owner)
            assertTrue("실제 외부 중단을 준비한 소유 marker가 있어야 합니다", owner.optString("phase") == "awaiting_process_stop")
            guarded(graph, owner) {
                val source = ownedActive(graph, owner)
                val old = runFromJson(owner.getJSONObject("interruption_run"))
                val recovered =
                    withTimeout(25_000) {
                        graph
                            .recordingRuns()
                            .observe(source.sessionId)
                            .first { rows ->
                                rows.any { it.runId == old.runId && it.status == RecordingRunStatus.INTERRUPTED }
                            }.single { it.runId == old.runId }
                    }
                assertTrue(
                    "프로세스 손실 시각을 만들어 내면 안 됩니다",
                    recovered == old.copy(status = RecordingRunStatus.INTERRUPTED, endedAtEpochMillis = null),
                )
                withTimeout(25_000) {
                    graph.recording().background.first { it == BackgroundRecordingState.Interrupted(source.sessionId) }
                }
                awaitNoService()
                val priorRuns = owner.getJSONObject("owned_runs_before_process_stop")
                val afterRecovery = runs(graph, source.sessionId)
                assertTrue("복구만으로 새 run을 만들면 안 됩니다", afterRecovery.map { it.runId }.toSet() == priorRuns.keys().asSequence().toSet())
                afterRecovery.filter { it.runId != old.runId }.forEach {
                    assertTrue("기존 소유 종료 run도 그대로 유지해야 합니다", it.toString() == priorRuns.getString(it.runId))
                }
                assertNoOpenRun(graph, source.sessionId)
                awaitAllowed(graph)
                waitTag("background_recording_interrupted")
                waitTag("background_recording_enable")
                verifyGlyphs("background_recording_interrupted")
                verifyGlyphs("background_recording_scope")
                capture("main-real-process-interruption-warning")
                writeProof("interrupted-no-auto-restart", graph, source.sessionId)
                delay(1_000)
                assertTrue(
                    "경고 확인만으로 백그라운드 기록을 재시작하면 안 됩니다",
                    foregroundServices().isEmpty() &&
                        graph.recording().background.value == BackgroundRecordingState.Interrupted(source.sessionId),
                )
                phase("BG_RECORDING_INTERRUPTED_RECOVERED")

                val fresh = enableThroughUi(graph, owner, source)
                assertTrue("복구 후 새 명시적 시작은 옛 run을 재사용하면 안 됩니다", fresh.runId != old.runId)
                assertTrue(
                    "옛 중단 marker는 새 실행 뒤에도 미확인 종료 시각을 유지해야 합니다",
                    runs(graph, source.sessionId).single { it.runId == old.runId } == recovered,
                )
                capture("main-recovery-fresh-explicit-start")
                phase("BG_RECORDING_RECOVERY_FRESH_ACTIVE")
                stopThroughUi(graph, fresh, allowHistoricalInterruption = true)
                capture("main-recovery-explicit-stop")
                phase("BG_RECORDING_RECOVERY_STOPPED")

                awaitAllowed(graph)
                waitTag("session_finish")
                touch("session_finish")
                val finished =
                    withTimeout(20_000) {
                        graph
                            .sessions()
                            .observeSessions()
                            .first { rows -> rows.any { it.sessionId == source.sessionId && !it.isActive } }
                            .single { it.sessionId == source.sessionId }
                    }
                assertTrue(
                    "마지막 단계에서 검사 소유 연습만 안전한 실제 UI로 종료해야 합니다",
                    finished == source.copy(endedAtEpochMillis = finished.endedAtEpochMillis) && finished.endedAtEpochMillis != null,
                )
                assertTrue(
                    "최종 단계에 활성 연습을 남기면 안 됩니다",
                    graph
                        .sessions()
                        .observeSessions()
                        .first()
                        .none { it.isActive },
                )
                assertNoOpenRun(graph, source.sessionId)
                awaitNoService()
                assertTrue("완료 뒤 옛 중단 근거를 덮어쓰면 안 됩니다", runs(graph, source.sessionId).single { it.runId == old.runId } == recovered)
                assertOriginalRowsPreserved(graph, owner)
                owner.put("phase", "completed").put("owned_final_snapshot", snapshotJson(snapshot(graph)))
                writeOwner(owner)
                waitTag("session_latest_finished")
                capture("main-owned-session-finished")
                writeProof("owned-session-finished", graph, source.sessionId)
                phase("BG_RECORDING_OWNED_SESSION_FINISHED")
                phase("BG_RECORDING_RECOVERY_COMPLETED")
            }
        }

    private fun assertEnvironment(stage: String) {
        assumeTrue("실제 전면 전환, DDS 및 외부 복구를 준비한 단일 실행만 검사합니다", arguments.getString("manualBackgroundRecording") == "true")
        assumeTrue("각 native 단계는 외부 runner가 명시적으로 선택해야 합니다", arguments.getString("backgroundRecordingStage") == stage)
        assertTrue("두 비교 계측이 끝나기 전에는 소유 자료를 만들면 안 됩니다", arguments.getString("measurementComparisonCompleted") == "true")
        assertTrue(
            "전용 debug API34 실제 앱만 검사합니다",
            context.packageName == "io.github.hangillab.hangil.debug" && Build.VERSION.SDK_INT == 34 &&
                context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
        mainActivity = compose.activity
        instrumentation.runOnMainSync {
            assertTrue("실제 Main RESUMED에서 display를 확인해야 합니다", mainActivity.lifecycle.currentState == Lifecycle.State.RESUMED)
            val display = mainActivity.display
            mainDisplayId = display?.displayId
            assertTrue("실제 Main display가 미확인이거나 무효이면 기본 display로 대체하면 안 됩니다", display != null && display.isValid && mainDisplayId != null)
        }
        enableInteractiveWindowsObservation()
        val expectedFont = checkNotNull(arguments.getString("expectedFontScale")).toFloat()
        val expectedDark = arguments.getString("expectedDark") == "true"
        val configuration = context.resources.configuration
        assertTrue("명시한 실제 글꼴 크기를 사용해야 합니다", abs(configuration.fontScale - expectedFont) < 0.001f)
        assertTrue(
            "명시한 실제 테마를 사용해야 합니다",
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) == expectedDark,
        )
        assertTrue("다른 실제 기본 내비 앱을 지정해야 합니다", navigationPackage() != context.packageName && navigationPackage().startsWith("ai."))
        val permissions =
            context.packageManager
                .getPackageInfo(
                    context.packageName,
                    PackageManager.GET_PERMISSIONS,
                ).requestedPermissions
                .orEmpty()
        assertTrue(
            "백그라운드 기록을 위해 위치나 경로 권한을 추가하면 안 됩니다",
            listOf(
                "android.permission.ACCESS_FINE_LOCATION",
                "android.permission.ACCESS_COARSE_LOCATION",
                "android.permission.ACCESS_BACKGROUND_LOCATION",
                "pleos.car.permission.NAVI_ROUTE",
                "pleos.car.permission.NAVI_ROUTE_SEARCH",
            ).none {
                it in
                    permissions
            },
        )
    }

    private fun enableInteractiveWindowsObservation() {
        val automation = instrumentation.uiAutomation
        val current = checkNotNull(automation.serviceInfo) { "UiAutomation window 관찰 설정을 읽어야 합니다" }
        val bit = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
        val original = current.flags and bit != 0
        assertTrue("같은 실행의 원래 flag bit를 덮어쓰면 안 됩니다", interactiveWindowsFlagBefore == null)
        interactiveWindowsFlagBefore = original
        if (!original) {
            current.flags = current.flags or bit
            automation.serviceInfo = current
        }
        val readback = checkNotNull(automation.serviceInfo) { "UiAutomation window 관찰 설정 결과를 읽어야 합니다" }
        assertTrue("focused window 관찰에 필요한 기존 UiAutomation flag bit를 확인해야 합니다", readback.flags and bit != 0)
        awaitInitialMainForeground()
    }

    /** Initial Main has no handoff in progress: another known focused package fails immediately. */
    private fun awaitInitialMainForeground() {
        val until = SystemClock.elapsedRealtime() + 25_000
        while (true) {
            assertTrue("초기 전면 관찰 중 실제 Main은 RESUMED여야 합니다", mainState() == Lifecycle.State.RESUMED)
            val observed = packageNow()
            if (observed != null) {
                assertTrue("실제 Main display의 초기 focused package가 앱이어야 합니다", observed == context.packageName)
                return
            }
            assertTrue("초기 Main focused package가 계속 미확인이면 성공으로 처리하면 안 됩니다", SystemClock.elapsedRealtime() < until)
            SystemClock.sleep(50)
        }
    }

    private fun graph(): RecordingTestEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, RecordingTestEntryPoint::class.java)

    private suspend fun loadOrCreateOwner(graph: RecordingTestEntryPoint): JSONObject {
        if (ownerFile().exists()) {
            val owner = readOwner()
            assertTrue("미완료 생성 marker가 있으면 중복 세션을 만들지 않고 중단합니다", owner.has("owned_session_id"))
            assertTrue(
                "중단/완료 단계 뒤에는 flow를 다시 만들면 안 됩니다",
                owner.optString("phase") == "flow_ready" || owner.optString("phase") == "owned_active",
            )
            assertOriginalRowsPreserved(graph, owner)
            ownedActive(graph, owner)
            return owner
        }
        val before = snapshot(graph)
        assertTrue("원래 활성 연습이 있으면 소유 연습을 만들지 않습니다", before.sessions.none { it.isActive })
        if (evidenceRound() == "review-followup-v1") {
            val prior = JSONObject(File(context.cacheDir, "background-vehicle-recording-owner.json").readText())
            assertTrue(
                "완료된 이전 검증 원형만 후속 기준으로 사용할 수 있습니다",
                prior.optString("producer") == PRODUCER && prior.optString("phase") == "completed",
            )
            assertOriginalRowsPreserved(graph, JSONObject().put("baseline", prior.getJSONObject("owned_final_snapshot")))
        } else {
            assertTrue("보존 대상 원래 준비와 11개 종료 연습이 있어야 합니다", before.preparation != null && before.sessions.size == 11)
            assertTrue(
                "보존 대상 원래 관찰 17개와 메모 1개를 먼저 확인해야 합니다",
                before.observations.values.sumOf { it.size } == 17 && before.memos.values.count { it != null } == 1,
            )
        }
        val owner =
            JSONObject()
                .put("producer", PRODUCER)
                .put("evidence_round", evidenceRound())
                .put("phase", "creating_session")
                .put("baseline", snapshotJson(before))
                .put("owned_run_ids", JSONArray())
                .put("completed_themes", JSONArray())
        assertTrue("소유 marker를 덮어쓰면 안 됩니다", ownerFile().createNewFile())
        writeOwner(owner)
        waitTag("session_start")
        touch("session_start")
        val originalIds = before.sessions.map { it.sessionId }.toSet()
        val active =
            withTimeout(20_000) {
                graph
                    .sessions()
                    .observeSessions()
                    .first { rows -> rows.any { it.isActive && it.sessionId !in originalIds } }
                    .single { it.isActive && it.sessionId !in originalIds }
            }
        owner.put("owned_session_id", active.sessionId).put("owned_active_session", active.toString()).put("phase", "owned_active")
        writeOwner(owner)
        assertOriginalRowsPreserved(graph, owner)
        return owner
    }

    private suspend fun ownedActive(
        graph: RecordingTestEntryPoint,
        owner: JSONObject,
    ): PracticeSession {
        val rows = graph.sessions().observeSessions().first()
        val owned = rows.single { it.sessionId == owner.getString("owned_session_id") }
        assertTrue("같은 소유 활성 연습 하나만 재사용해야 합니다", rows.filter { it.isActive } == listOf(owned) && owned.isActive)
        assertTrue("소유 연습의 준비/시작 필드도 바꾸면 안 됩니다", owned.toString() == owner.getString("owned_active_session"))
        return owned
    }

    private suspend fun enableThroughUi(
        graph: RecordingTestEntryPoint,
        owner: JSONObject,
        source: PracticeSession,
    ): BackgroundRecordingState.Active {
        awaitAllowed(graph)
        awaitMainResumed()
        assertNoOpenRun(graph, source.sessionId)
        waitTag("background_recording_enable")
        touch("background_recording_enable")
        val active =
            withTimeout(25_000) {
                graph.recording().background.first {
                    it is BackgroundRecordingState.Active && it.sessionId == source.sessionId
                } as BackgroundRecordingState.Active
            }
        val ids = owner.getJSONArray("owned_run_ids")
        assertTrue("명시적 시작은 매번 새 run을 만들어야 합니다", active.runId !in strings(ids))
        ids.put(active.runId)
        writeOwner(owner)
        withTimeout(15_000) {
            while (foregroundServices().singleOrNull()?.foreground != true) delay(50)
        }
        assertActiveOwnership(graph, active)
        waitTag("background_recording_stop")
        verifyGlyphs("background_recording_state")
        return active
    }

    private suspend fun stopThroughUi(
        graph: RecordingTestEntryPoint,
        active: BackgroundRecordingState.Active,
        allowHistoricalInterruption: Boolean = false,
    ) {
        waitTag("background_recording_stop")
        touch("background_recording_stop")
        val stopped =
            withTimeout(25_000) {
                graph
                    .recordingRuns()
                    .observe(active.sessionId)
                    .first { rows ->
                        rows.any {
                            it.runId == active.runId &&
                                it.status == RecordingRunStatus.STOPPED
                        }
                    }.single { it.runId == active.runId }
            }
        assertTrue("명시적 중지에는 실제 양수 종료 시각을 보존해야 합니다", stopped.endedAtEpochMillis?.let { it > 0 } == true)
        withTimeout(20_000) {
            graph.recording().background.first {
                it == BackgroundRecordingState.Disabled ||
                    (allowHistoricalInterruption && it == BackgroundRecordingState.Interrupted(active.sessionId))
            }
        }
        awaitNoService()
        assertNoOpenRun(graph, active.sessionId)
    }

    private suspend fun assertActiveOwnership(
        graph: RecordingTestEntryPoint,
        active: BackgroundRecordingState.Active,
    ) {
        assertTrue("컨트롤러의 같은 단일 run 소유권을 유지해야 합니다", graph.recording().background.value == active)
        withTimeout(10_000) { graph.recording().state.first { it == VehicleRecordingState.Recording } }
        assertTrue("중복 recorder 소유 실패 없이 하나의 recorder가 동작해야 합니다", graph.recording().state.value == VehicleRecordingState.Recording)
        val open = runs(graph, active.sessionId).filter { it.status == RecordingRunStatus.OPEN }
        assertTrue(
            "같은 활성 세션에는 정확히 한 OPEN run만 있어야 합니다",
            open.size == 1 && open.single().runId == active.runId && open.single().endedAtEpochMillis == null,
        )
        val services = foregroundServices()
        assertTrue("실제 native foreground service 하나가 기록을 소유해야 합니다", services.size == 1 && services.single().foreground)
        assertTrue(
            "한 개의 같은 소유 세션만 활성 상태여야 합니다",
            graph
                .sessions()
                .observeSessions()
                .first()
                .filter { it.isActive }
                .map { it.sessionId } == listOf(active.sessionId),
        )
    }

    private suspend fun assertNoOpenRun(
        graph: RecordingTestEntryPoint,
        sessionId: String,
    ) {
        assertTrue("명시적 시작 전이나 중지 뒤에는 OPEN run이 없어야 합니다", runs(graph, sessionId).none { it.status == RecordingRunStatus.OPEN })
    }

    private suspend fun awaitStoredGear(
        graph: RecordingTestEntryPoint,
        sessionId: String,
        expected: GearPosition,
        afterId: Long,
    ) {
        val rows =
            withTimeout(90_000) {
                graph.observations().observe(sessionId).first { observations ->
                    observations.any {
                        it.id > afterId && it.observation.state.connection == ConnectionState.CONNECTED &&
                            it.observation.state.gear == VehicleReading.Available(expected)
                    }
                }
            }
        assertTrue(
            "기록 출처와 실제 양수 앱 관찰 시각을 보존해야 합니다",
            rows.all {
                it.observation.source == VehicleObservationSource.SDK_STATE_OBSERVATION &&
                    it.observation.observedAtEpochMillis > 0
            },
        )
        assertTrue(
            "추가 행은 같은 검사 소유 세션에만 연결돼야 합니다",
            rows.all { it.sessionId == sessionId } && rows.map { it.id }.distinct().size == rows.size,
        )
    }

    /** Polls already exposed values; the test does not keep its own SDK subscription alive. */
    private suspend fun awaitAllowed(
        graph: RecordingTestEntryPoint,
        timeout: Long = 25_000,
    ) {
        withTimeout(timeout) {
            while (!allowsPracticeInteraction(graph.vehicle().state.value, graph.restrictions().restrictions.value)) delay(50)
        }
    }

    private suspend fun awaitGearValue(
        graph: RecordingTestEntryPoint,
        expected: GearPosition,
    ) {
        withTimeout(90_000) {
            while (graph.vehicle().state.value.let {
                    it.connection != ConnectionState.CONNECTED ||
                        it.gear != VehicleReading.Available(expected)
                }
            ) {
                delay(50)
            }
        }
    }

    private suspend fun observations(
        graph: RecordingTestEntryPoint,
        sessionId: String,
    ) = graph.observations().observe(sessionId).first()

    private suspend fun runs(
        graph: RecordingTestEntryPoint,
        sessionId: String,
    ) = graph.recordingRuns().observe(sessionId).first()

    private suspend fun snapshot(graph: RecordingTestEntryPoint): ExistingData =
        withTimeout(20_000) {
            val sessions = graph.sessions().observeSessions().first()
            ExistingData(
                graph.preparation().observeSelection().first(),
                sessions,
                sessions.associate { it.sessionId to observations(graph, it.sessionId) },
                sessions.associate { it.sessionId to graph.memos().observe(it.sessionId).first() },
                sessions.associate { it.sessionId to runs(graph, it.sessionId) },
            )
        }

    private fun snapshotJson(data: ExistingData): JSONObject =
        JSONObject()
            .put("preparation", data.preparation.toString())
            .put("session_order", JSONArray(data.sessions.map { it.sessionId }))
            .put("sessions", JSONObject().apply { data.sessions.forEach { put(it.sessionId, it.toString()) } })
            .put("observations", JSONObject().apply { data.observations.forEach { (id, rows) -> put(id, rows.toString()) } })
            .put("memos", JSONObject().apply { data.memos.forEach { (id, memo) -> put(id, memo.toString()) } })
            .put("runs", JSONObject().apply { data.runs.forEach { (id, rows) -> put(id, rows.toString()) } })

    private suspend fun assertOriginalRowsPreserved(
        graph: RecordingTestEntryPoint,
        owner: JSONObject,
    ) {
        val current = snapshot(graph)
        val baseline = owner.getJSONObject("baseline")
        val ids = strings(baseline.getJSONArray("session_order"))
        assertTrue("원래 준비의 모든 필드를 보존해야 합니다", current.preparation.toString() == baseline.getString("preparation"))
        assertTrue("원래 세션의 수와 순서를 보존해야 합니다", current.sessions.filter { it.sessionId in ids }.map { it.sessionId } == ids)
        current.sessions.filter { it.sessionId in ids }.forEach {
            val id = it.sessionId
            assertTrue("원래 세션의 모든 필드를 보존해야 합니다", it.toString() == baseline.getJSONObject("sessions").getString(id))
            assertTrue(
                "원래 관찰의 모든 필드와 순서를 보존해야 합니다",
                current.observations.getValue(id).toString() == baseline.getJSONObject("observations").getString(id),
            )
            assertTrue("원래 메모와 버전을 보존해야 합니다", current.memos.getValue(id).toString() == baseline.getJSONObject("memos").getString(id))
            assertTrue("원래 기록 run을 보존해야 합니다", current.runs.getValue(id).toString() == baseline.getJSONObject("runs").getString(id))
        }
        val ownedId = owner.optString("owned_session_id")
        assertTrue(
            "명시적으로 소유한 세션 하나 외에 추가 자료가 없어야 합니다",
            current.sessions.filter { it.sessionId !in ids }.map { it.sessionId } ==
                if (ownedId.isBlank()) emptyList<String>() else listOf(ownedId),
        )
        if (ownedId.isNotBlank()) assertTrue("검사 소유 세션에 메모를 만들면 안 됩니다", current.memos[ownedId] == null)
    }

    private suspend fun guarded(
        graph: RecordingTestEntryPoint,
        owner: JSONObject,
        action: suspend () -> Unit,
    ) {
        var primary: Throwable? = null
        try {
            assertOriginalRowsPreserved(graph, owner)
            action()
        } catch (failure: Throwable) {
            primary = failure
            try {
                capture("failure-private")
                writeProof("failure-private", graph, owner.getString("owned_session_id"))
            } catch (diagnostic: Throwable) {
                failure.addSuppressed(diagnostic)
            }
            throw failure
        } finally {
            try {
                returnToMain()
                if (primary != null) {
                    val active = graph.recording().background.value as? BackgroundRecordingState.Active
                    if (active != null && active.sessionId == owner.optString("owned_session_id")) {
                        // Failure cleanup is not a successful UI stop assertion and never ends a session.
                        graph.recording().stopBackground(active.runId)
                        awaitNoService()
                    }
                }
                val ownedId = owner.optString("owned_session_id")
                if (ownedId.isNotBlank()) {
                    val ids = owner.getJSONArray("owned_run_ids")
                    runs(graph, ownedId).forEach { if (it.runId !in strings(ids)) ids.put(it.runId) }
                    owner.put("last_owned_snapshot", snapshotJson(snapshot(graph)))
                    writeOwner(owner)
                }
                assertOriginalRowsPreserved(graph, owner)
            } catch (cleanup: Throwable) {
                val original = primary
                if (original == null) throw cleanup else original.addSuppressed(cleanup)
            }
        }
    }

    private fun evidenceRound(): String {
        val round = arguments.getString("backgroundRecordingEvidenceRound") ?: "initial"
        assertTrue("명시된 독립 검증 회차만 사용할 수 있습니다", round == "initial" || round == "review-followup-v1")
        return round
    }

    private fun ownerFile() =
        File(
            context.cacheDir,
            if (evidenceRound() ==
                "initial"
            ) {
                "background-vehicle-recording-owner.json"
            } else {
                "background-recording-review-followup-owner.json"
            },
        )

    private fun readOwner(): JSONObject =
        JSONObject(ownerFile().readText()).also {
            assertTrue("같은 native 검사의 private 소유 marker만 재사용합니다", it.optString("producer") == PRODUCER)
            assertTrue("다른 회차의 소유 영수증을 재사용하면 안 됩니다", it.optString("evidence_round", "initial") == evidenceRound())
        }

    private fun writeOwner(owner: JSONObject) {
        val atomic = AtomicFile(ownerFile())
        val stream = atomic.startWrite()
        try {
            stream.write(owner.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(stream)
        } catch (failure: Throwable) {
            atomic.failWrite(stream)
            throw failure
        }
    }

    private fun assertBothThemesCompleted(owner: JSONObject) {
        assertTrue(
            "실제 기본 라이트와 확대 다크 flow가 모두 끝나야 합니다",
            strings(owner.getJSONArray("completed_themes")).containsAll(listOf("1.0-light", "1.3-dark")),
        )
    }

    private fun strings(array: JSONArray): List<String> = (0 until array.length()).map(array::getString)

    private fun themeKey() =
        "${arguments.getString("expectedFontScale")}-${if (arguments.getString("expectedDark") == "true") "dark" else "light"}"

    private fun navigationPackage() = checkNotNull(arguments.getString("expectedNavigationPackage"))

    private fun packageNow(): String? {
        val observation = observeMainDisplayForeground()
        lastForegroundObservation = observation
        if (observation.actualPackage == null) {
            foregroundNullSamples += 1
            val reason = checkNotNull(observation.unknownReason)
            foregroundUnknownReasons[reason] = foregroundUnknownReasons.getOrDefault(reason, 0L) + 1
        }
        return observation.actualPackage
    }

    /** No expected package is accepted here: only one focused window's actual root is evidence. */
    @Suppress("DEPRECATION")
    private fun observeMainDisplayForeground(): ForegroundObservation {
        val displayId =
            mainDisplayId
                ?: return ForegroundObservation(null, null, null, false, null, "MAIN_DISPLAY_UNKNOWN")
        var flagObserved: Boolean? = null
        var focusCount: Int? = null
        var sameDisplay: Boolean? = null
        var root: AccessibilityNodeInfo? = null
        val windowsToRecycle = mutableListOf<AccessibilityWindowInfo>()

        fun unknown(reason: String) = ForegroundObservation(null, focusCount, flagObserved, true, sameDisplay, reason)
        return try {
            val automation = instrumentation.uiAutomation
            val info = automation.serviceInfo ?: return unknown("SERVICE_INFO_UNAVAILABLE")
            flagObserved = info.flags and AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS != 0
            if (flagObserved != true) return unknown("INTERACTIVE_WINDOWS_FLAG_DISABLED")
            val allDisplays = automation.windowsOnAllDisplays
            for (index in 0 until allDisplays.size()) windowsToRecycle.addAll(allDisplays.valueAt(index))
            val windows = allDisplays.get(displayId) ?: return unknown("MAIN_DISPLAY_WINDOWS_ABSENT")
            val focused = windows.filter { it.isFocused }
            focusCount = focused.size
            if (windows.any { it.displayId != displayId }) {
                sameDisplay = false
                return unknown("DISPLAY_BUCKET_MISMATCH")
            }
            if (focused.size != 1) return unknown(if (focused.isEmpty()) "NO_FOCUSED_WINDOW" else "MULTIPLE_FOCUSED_WINDOWS")
            sameDisplay = true
            root = focused.single().root
            val focusedRoot = root ?: return unknown("FOCUSED_ROOT_NULL")
            val observed = focusedRoot.packageName?.toString() ?: return unknown("FOCUSED_PACKAGE_NULL")
            if (observed.isBlank()) return unknown("FOCUSED_PACKAGE_EMPTY")
            ForegroundObservation(observed, focusCount, flagObserved, true, true, null)
        } catch (_: Exception) {
            unknown("AX_READ_FAILED")
        } finally {
            root?.recycle()
            windowsToRecycle.forEach { it.recycle() }
        }
    }

    private fun awaitPackage(expected: String) {
        val start = SystemClock.elapsedRealtime()
        while (packageNow() != expected && SystemClock.elapsedRealtime() - start < 25_000) SystemClock.sleep(50)
        assertTrue("실제 전면 패키지가 요청한 앱이어야 합니다", packageNow() == expected)
    }

    /**
     * OS handoff only to the runner's previously verified explicit launcher component. The supplied
     * package/component bounds and real foreground/lifecycle postconditions define this fixture;
     * missing package visibility never implies that arbitrary targets are allowed. Installation and
     * exported access are checked by the real OS dispatch. This is not app Navi SDK request evidence.
     */
    private fun launchSystemNavigation() {
        val expected = navigationPackage()
        val supplied =
            checkNotNull(arguments.getString("expectedNavigationComponent")) {
                "외부 runner가 실제로 확인한 내비 launcher component를 명시해야 합니다"
            }
        val component =
            checkNotNull(ComponentName.unflattenFromString(supplied)) {
                "명시한 내비 launcher component 형식이 유효해야 합니다"
            }
        assertTrue(
            "명시적 launcher component는 지정한 내비 package와 그 내부 class만 가리켜야 합니다",
            component.packageName == expected && component.className.startsWith("$expected."),
        )
        val launch =
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.runOnMainSync { context.startActivity(launch) }
        phase("BG_RECORDING_SYSTEM_NAVIGATION_LAUNCH_REQUESTED")
    }

    private fun awaitStableNavigation() {
        awaitPackage(navigationPackage())
        instrumentation.uiAutomation.waitForIdle(1_000, 10_000)
        SystemClock.sleep(1_000)
        assertTrue("전환 뒤에도 실제 내비 전면이 유지돼야 합니다", packageNow() == navigationPackage())
    }

    /** Unknown accessibility roots are retried, never replaced with the expected package. */
    private suspend fun assertNavigationStillForeground(
        graph: RecordingTestEntryPoint,
        active: BackgroundRecordingState.Active,
    ) {
        val expected = navigationPackage()
        val first = packageNow()
        if (first != null) {
            assertTrue("관찰한 실제 전면 package가 지정한 내비여야 합니다", first == expected)
            assertTrue("내비 전면에서 Main은 STARTED 아래여야 합니다", mainState() < Lifecycle.State.STARTED)
            assertActiveOwnership(graph, active)
            return
        }
        phase("BG_RECORDING_NAVI_FOREGROUND_NULL_REOBSERVING")
        withTimeout(20_000) {
            while (true) {
                assertTrue("전면 미확인 재관찰 중에도 Main은 STARTED 아래여야 합니다", mainState() < Lifecycle.State.STARTED)
                assertActiveOwnership(graph, active)
                delay(100)
                val observed = packageNow()
                if (observed != null) {
                    assertTrue("관찰한 다른 package를 내비 전면으로 대체하면 안 됩니다", observed == expected)
                    assertTrue("내비 전면 확인 시 Main은 STARTED 아래여야 합니다", mainState() < Lifecycle.State.STARTED)
                    assertActiveOwnership(graph, active)
                    return@withTimeout
                }
            }
        }
    }

    private fun mainState(): Lifecycle.State {
        var state = Lifecycle.State.DESTROYED
        instrumentation.runOnMainSync { state = mainActivity.lifecycle.currentState }
        return state
    }

    private suspend fun awaitMainBelowStarted() {
        withTimeout(20_000) { while (mainState() >= Lifecycle.State.STARTED) delay(50) }
    }

    private suspend fun awaitMainResumed() {
        withTimeout(20_000) { while (mainState() != Lifecycle.State.RESUMED) delay(50) }
    }

    private fun returnToMain() {
        context.startActivity(
            Intent(
                context,
                MainActivity::class.java,
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        awaitPackage(context.packageName)
        compose.waitForIdle()
    }

    @Suppress("DEPRECATION")
    private fun foregroundServices(): List<ActivityManager.RunningServiceInfo> =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(Int.MAX_VALUE)
            .filter { it.service.packageName == context.packageName && it.service.className == SERVICE_NAME }

    private suspend fun awaitNoService() {
        withTimeout(20_000) { while (foregroundServices().isNotEmpty()) delay(50) }
    }

    private fun nodes(tag: String): List<SemanticsNode> =
        compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes(atLeastOneRootRequired = false)

    private fun waitTag(tag: String) = compose.waitUntil(20_000) { nodes(tag).size == 1 }

    private fun touch(tag: String) {
        waitTag(tag)
        compose
            .onNodeWithTag(tag, useUnmergedTree = true)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
        compose.waitForIdle()
    }

    private fun verifyGlyphs(tag: String) {
        waitTag(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val layouts = mutableListOf<TextLayoutResult>()

        fun visit(node: SemanticsNode) {
            node.config
                .getOrNull(SemanticsActions.GetTextLayoutResult)
                ?.action
                ?.invoke(layouts)
            node.children.forEach(::visit)
        }
        visit(nodes(tag).single())
        assertTrue("기본/확대 테마의 실제 텍스트 레이아웃을 확인해야 합니다", layouts.isNotEmpty())
        assertTrue(
            "실제 백그라운드 기록 문구가 생략되거나 잘리면 안 됩니다",
            layouts.all { layout ->
                val text = layout.layoutInput.text.text
                !layout.multiParagraph.didExceedMaxLines && layout.lineCount > 0 &&
                    layout.getLineEnd(layout.lineCount - 1, visibleEnd = false) == text.length &&
                    (0 until layout.lineCount).none(layout::isLineEllipsized) &&
                    text.indices.filterNot { text[it].isWhitespace() }.all { offset ->
                        val box = layout.getBoundingBox(offset)
                        box.left.isFinite() && box.top.isFinite() && box.right.isFinite() && box.bottom.isFinite() &&
                            box.left >= -1 && box.top >= -1 && box.right <= layout.size.width + 1 && box.bottom <= layout.size.height + 1
                    }
            },
        )
    }

    private fun capture(name: String) {
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(evidence, "$name.png").outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            screenshot.recycle()
        }
    }

    private suspend fun writeProof(
        name: String,
        graph: RecordingTestEntryPoint,
        sessionId: String,
    ) {
        val actualPackage = packageNow()
        val observation = lastForegroundObservation
        val proof =
            JSONObject()
                .put("theme", themeKey())
                .put("evidence_round", evidenceRound())
                .put("foreground_package", actualPackage ?: JSONObject.NULL)
                .put("foreground_actual_package", actualPackage ?: JSONObject.NULL)
                .put("foreground_observation_basis", "AX_FOCUSED_WINDOW_ON_CAPTURED_MAIN_DISPLAY")
                .put("foreground_focus_count", observation.focusCount ?: JSONObject.NULL)
                .put("foreground_interactive_windows_flag_observed", observation.flagObserved ?: JSONObject.NULL)
                .put("foreground_main_display_known", observation.mainDisplayKnown)
                .put("foreground_same_display", observation.sameDisplay ?: JSONObject.NULL)
                .put("foreground_unknown_reason", observation.unknownReason ?: JSONObject.NULL)
                .put("foreground_unknown_reason_counts", JSONObject(foregroundUnknownReasons.toMap()))
                .put("foreground_null_samples", foregroundNullSamples)
                .put(
                    "foreground_null_sample_scope",
                    "Main-display focused accessibility window/package observations only; not missing SDK events",
                ).put("main_lifecycle", mainState().name)
                .put("native_service_count", foregroundServices().size)
                .put("native_foreground_service_count", foregroundServices().count { it.foreground })
                .put(
                    "background_state",
                    graph
                        .recording()
                        .background.value
                        .toString(),
                ).put(
                    "recording_state",
                    graph
                        .recording()
                        .state.value
                        .toString(),
                ).put("owned_runs", JSONArray(runs(graph, sessionId).map { runJson(it) }))
                .put("owned_observations", observations(graph, sessionId).toString())
                .put("navigation_handoff_policy", "system_launcher_for_recording_flow_only")
                .put(
                    "claim_limit",
                    "Instrumentation affects process priority; Main STOP, native FGS and actual SDK snapshot receipt only; system launcher handoff does not establish app Navi SDK request success; focused-window package does not establish completed navigation rendering",
                )
        File(evidence, "$name.json").writeText(proof.toString(2))
    }

    private fun runJson(run: VehicleRecordingRun): JSONObject =
        JSONObject()
            .put("run_id", run.runId)
            .put("session_id", run.sessionId)
            .put("process_id", run.processId)
            .put(
                "started_at",
                run.startedAtEpochMillis,
            ).put("ended_at", run.endedAtEpochMillis ?: JSONObject.NULL)
            .put("status", run.status.name)

    private fun runFromJson(value: JSONObject): VehicleRecordingRun =
        VehicleRecordingRun(
            value.getString("run_id"),
            value.getString("session_id"),
            value.getString("process_id"),
            value.getLong("started_at"),
            if (value.isNull("ended_at")) null else value.getLong("ended_at"),
            RecordingRunStatus.valueOf(value.getString("status")),
        )

    private fun phase(name: String) = instrumentation.sendStatus(2, Bundle().apply { putString("stream", "$name\n") })

    private data class ForegroundObservation(
        val actualPackage: String?,
        val focusCount: Int?,
        val flagObserved: Boolean?,
        val mainDisplayKnown: Boolean,
        val sameDisplay: Boolean?,
        val unknownReason: String?,
    )

    private data class ExistingData(
        val preparation: PracticeSelection?,
        val sessions: List<PracticeSession>,
        val observations: Map<String, List<SessionVehicleObservation>>,
        val memos: Map<String, SessionMemo?>,
        val runs: Map<String, List<VehicleRecordingRun>>,
    )

    private companion object {
        const val PRODUCER = "BackgroundVehicleRecordingFlowTest"
        const val SERVICE_NAME = "io.github.hangillab.hangil.VehicleRecordingService"
    }
}
