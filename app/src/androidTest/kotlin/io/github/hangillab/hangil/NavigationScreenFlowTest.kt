package io.github.hangillab.hangil

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
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
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import dagger.hilt.android.EntryPointAccessors
import io.github.hangillab.hangil.core.domain.CourseReviewStatus
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionMemo
import io.github.hangillab.hangil.core.domain.SessionVehicleObservation
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.domain.VehicleRecordingRun
import io.github.hangillab.hangil.core.domain.allowsPracticeInteraction
import kotlinx.coroutines.flow.combine
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
import io.github.hangillab.hangil.feature.home.R as HomeR

/** Actual Main/SDK/default-navigation foreground test. Never requests route, info, or location. */
@RunWith(AndroidJUnit4::class)
class NavigationScreenFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val evidence by lazy {
        File(context.cacheDir, "screenshots/native-navigation-screen-${UUID.randomUUID()}").apply { check(mkdirs()) }
    }

    private lateinit var mainActivity: MainActivity
    private var mainDisplayId: Int? = null
    private var interactiveWindowsFlagBefore: Boolean? = null
    private var lastForegroundObservation = ForegroundObservation(null, null, null, false, null, "NOT_OBSERVED")
    private val foregroundUnknownReasons = linkedMapOf<String, Long>()
    private var foregroundNullSamples = 0L

    @Test fun actualDefaultNavigationOpensAndFreshHostReturnsWithoutDataWrites() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("실제 전면 전환과 원래 자료 보존을 준비한 실행에서만 검사합니다", args.getString("manualNativeNavigationScreen") == "true")
        val expectedNavigationPackage = checkNotNull(args.getString("expectedNavigationPackage"))
        assertTrue(
            "같은 일반 SDK 서비스와 다른 실제 기본 내비 앱이어야 합니다",
            expectedNavigationPackage != context.packageName && expectedNavigationPackage.startsWith("ai."),
        )
        assertTrue(
            "전용 debug API34만 검사합니다",
            context.packageName == "io.github.hangillab.hangil.debug" && Build.VERSION.SDK_INT == 34 &&
                context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0,
        )
        val font = checkNotNull(args.getString("expectedFontScale")).toFloat()
        val dark = args.getString("expectedDark") == "true"
        val configuration = context.resources.configuration
        assertTrue("명시한 실제 글꼴을 사용해야 합니다", kotlin.math.abs(configuration.fontScale - font) < 0.001f)
        assertTrue(
            "명시한 실제 테마를 사용해야 합니다",
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES) == dark,
        )
        prepareForegroundObservation()
        assertPermissionBoundary()
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, RecordingTestEntryPoint::class.java)
        val before = snapshot(graph)
        assertTrue("활성 연습은 새로 만들거나 변경하지 않습니다", before.sessions.none { it.isActive })
        assertTrue("원래 준비/이력을 유지한 실제 앱이어야 합니다", before.preparation != null && before.sessions.isNotEmpty())
        var primary: Throwable? = null
        try {
            awaitAllowed(graph)
            awaitPackage(context.packageName)
            waitTag("navigation_screen_open")
            verifyGlyphs("navigation_screen_scope")
            verifyGlyphs("navigation_screen_open")
            assertText("navigation_screen_scope", context.getString(HomeR.string.navigation_screen_scope))
            capture("main-before-request")
            val old = clickAction()
            compose.onNodeWithTag("navigation_screen_open").performScrollTo().performClick()
            awaitStableNavigation(expectedNavigationPackage)
            capture("actual-navigation-first")
            phase("NAVI_SCREEN_ACTUAL_FOREGROUND_OBSERVED")
            returnToMain()
            waitTag("navigation_screen_open")
            assertTrue(
                "복귀한 호스트는 옛 결과나 연결 중 상태를 가져오면 안 됩니다",
                nodes("navigation_screen_result").isEmpty() && nodes("navigation_screen_connecting").isEmpty(),
            )
            instrumentation.runOnMainSync { old() }
            compose.waitForIdle()
            assertTrue("옛 클릭은 복귀한 Main을 떠나면 안 됩니다", packageNow() == context.packageName)
            capture("main-fresh-return")
            val beforeDriving = clickAction()
            phase("NAVI_SCREEN_AWAIT_D")
            runBlocking {
                withTimeout(90_000) {
                    combine(graph.vehicle().state, graph.restrictions().restrictions) { v, r -> v to r }
                        .first { (v, r) -> v.gear == VehicleReading.Available(GearPosition.DRIVE) && !allowsPracticeInteraction(v, r) }
                }
            }
            waitTag("home_locked_title")
            assertTrue("D에서는 내비 상세 버튼이 없어야 합니다", nodes("navigation_screen_open").isEmpty())
            instrumentation.runOnMainSync { beforeDriving() }
            compose.waitForIdle()
            assertTrue("실제 D의 옛 클릭은 Main을 떠나면 안 됩니다", packageNow() == context.packageName)
            capture("main-real-d-locked")
            phase("NAVI_SCREEN_AWAIT_P")
            awaitAllowed(graph, 90_000)
            waitTag("navigation_screen_open")
            assertTrue(
                "P 복귀만으로 요청을 실행하면 안 됩니다",
                nodes("navigation_screen_result").isEmpty() && nodes("navigation_screen_connecting").isEmpty(),
            )
            instrumentation.runOnMainSync { beforeDriving() }
            compose.waitForIdle()
            assertTrue("옛 P 클릭은 새 P 호스트를 빌리면 안 됩니다", packageNow() == context.packageName)
            compose.onNodeWithTag("navigation_screen_open").performScrollTo().performClick()
            awaitStableNavigation(expectedNavigationPackage)
            capture("actual-navigation-fresh-request")
            returnToMain()
            waitTag("navigation_screen_open")
            verifyGlyphs("navigation_screen_scope")
            verifyGlyphs("navigation_screen_open")
            capture("main-final-return")
            assertTrue("모든 원래 domain 자료가 정확히 유지돼야 합니다", before == snapshot(graph))
            assertPermissionBoundary()
            phase("NAVI_SCREEN_COMPLETED")
        } catch (failure: Throwable) {
            primary = failure
            runCatching { saveForegroundProof("flow-failure", failure.javaClass.simpleName) }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            listOf<() -> Unit>(
                { returnToMain() },
                { assertTrue("실패해도 원래 자료를 지우거나 대체하지 않습니다", before == snapshot(graph)) },
            ).forEach { cleanup ->
                try {
                    cleanup()
                } catch (failure: Throwable) {
                    val original = primary ?: cleanupFailure
                    if (original == null) cleanupFailure = failure else original.addSuppressed(failure)
                }
            }
            cleanupFailure?.let { throw it }
        }
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

    private fun prepareForegroundObservation() {
        mainActivity = compose.activity
        instrumentation.runOnMainSync {
            check(mainActivity.lifecycle.currentState == Lifecycle.State.RESUMED)
            val display = checkNotNull(mainActivity.display)
            check(display.isValid)
            mainDisplayId = display.displayId
        }
        enableInteractiveWindowsObservation()
    }

    private fun mainState(): Lifecycle.State {
        var result: Lifecycle.State? = null
        instrumentation.runOnMainSync {
            val activities =
                Stage
                    .values()
                    .filter { it != Stage.DESTROYED }
                    .flatMap { ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it) }
            check(activities.filterIsInstance<MainActivity>().none { it !== mainActivity }) { "MAIN_RECREATED" }
            result = mainActivity.lifecycle.currentState
            check(result != Lifecycle.State.DESTROYED) { "MAIN_DESTROYED" }
        }
        return checkNotNull(result)
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

    @Test fun measureActualDefaultNavigationForegroundWithoutDataWrites() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue("실제 SDK 전면 전환 계측을 준비한 실행만 검사합니다", args.getString("manualNativeNavigationMeasurement") == "true")
        val expected = checkNotNull(args.getString("expectedNavigationPackage"))
        check(expected != context.packageName && expected.startsWith("ai."))
        check(Build.VERSION.SDK_INT == 34 && context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
        check(kotlin.math.abs(context.resources.configuration.fontScale - 1f) < 0.001f)
        check(context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_NO)
        prepareForegroundObservation()
        assertPermissionBoundary()
        val graph = EntryPointAccessors.fromApplication(context.applicationContext, RecordingTestEntryPoint::class.java)
        val before = snapshot(graph)
        check(before.sessions.isNotEmpty() && before.preparation != null && before.sessions.none { it.isActive })
        val samples = JSONArray()
        var primary: Throwable? = null
        try {
            repeat(7) { index ->
                returnToMain()
                awaitAllowed(graph)
                waitTag("navigation_screen_open")
                compose.onNodeWithTag("navigation_screen_open").performScrollTo().assertIsDisplayed()
                compose.waitForIdle()
                check(packageNow() == context.packageName && mainState() == Lifecycle.State.RESUMED)
                val unknownBefore = foregroundNullSamples
                var state = mainState()
                var successNs: Long? = null
                var actual: String? = null
                val started = SystemClock.elapsedRealtimeNanos()
                val deadline = started + 25_000_000_000L
                var finished = started
                var outcome = "TIMEOUT"
                var dispatchFailure: String? = null
                try {
                    compose.onNodeWithTag("navigation_screen_open").performClick()
                    while (SystemClock.elapsedRealtimeNanos() < deadline) {
                        actual = packageNow()
                        state = mainState()
                        finished = SystemClock.elapsedRealtimeNanos()
                        if (finished < deadline && actual == expected && !state.isAtLeast(Lifecycle.State.STARTED)) {
                            successNs = finished - started
                            outcome = "ACTUAL_FOREGROUND_OBSERVED"
                            break
                        }
                        SystemClock.sleep(50)
                    }
                    if (successNs == null) {
                        finished = SystemClock.elapsedRealtimeNanos()
                        outcome =
                            when {
                                actual == expected && !state.isAtLeast(Lifecycle.State.STARTED) -> "LATE_ENDPOINT"
                                actual == null -> "FOREGROUND_UNKNOWN"
                                actual == context.packageName -> "MAIN_REMAINED_FOREGROUND"
                                actual == expected -> "MAIN_LIFECYCLE_NOT_STOPPED"
                                else -> "OTHER_FOREGROUND_PACKAGE"
                            }
                    }
                } catch (failure: Throwable) {
                    finished = SystemClock.elapsedRealtimeNanos()
                    outcome = "INPUT_OR_OBSERVATION_ERROR"
                    dispatchFailure = failure.message ?: failure.javaClass.simpleName
                    if (dispatchFailure in listOf("MAIN_RECREATED", "MAIN_DESTROYED")) outcome = checkNotNull(dispatchFailure)
                }
                val sample =
                    JSONObject()
                        .put("index", index)
                        .put("phase", if (index < 2) "warm" else "main")
                        .put("started_elapsed_realtime_ns", started)
                        .put("finished_elapsed_realtime_ns", finished)
                        .put("latency_ns", successNs ?: JSONObject.NULL)
                        .put("elapsed_wait_ns", finished - started)
                        .put("outcome", outcome)
                        .put("actual_foreground_package", actual ?: JSONObject.NULL)
                        .put("main_lifecycle", state.name)
                        .put("unknown_samples", foregroundNullSamples - unknownBefore)
                        .put("input_or_observation_failure", dispatchFailure ?: JSONObject.NULL)
                sample.put("product_diagnostic", productDiagnostic(actual))
                samples.put(sample)
                File(evidence, "foreground-samples.json").writeText(
                    JSONObject()
                        .put("contract", "native-navigation-foreground-v1")
                        .put("deadline_ns", 25_000_000_000L)
                        .put("warmup_count", 2)
                        .put("main_count", 5)
                        .put("samples", samples)
                        .toString(2),
                )
                saveForegroundProof("attempt-$index", outcome)
                phase("NAVI_FOREGROUND_ATTEMPT_$index" + "_RECORDED")
                returnToMain()
                check(before == snapshot(graph))
            }
            phase("NAVI_FOREGROUND_MEASUREMENT_COMPLETED")
        } catch (failure: Throwable) {
            primary = failure
            runCatching {
                saveForegroundProof(
                    "measurement-failure",
                    failure.javaClass.simpleName,
                )
            }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        } finally {
            var cleanupFailure: Throwable? = null
            listOf<() -> Unit>(
                { returnToMain() },
                { check(before == snapshot(graph)) },
            ).forEach { cleanup ->
                try {
                    cleanup()
                } catch (failure: Throwable) {
                    val original = primary ?: cleanupFailure
                    if (original == null) cleanupFailure = failure else original.addSuppressed(failure)
                }
            }
            cleanupFailure?.let { throw it }
        }
    }

    private fun productDiagnostic(actualPackage: String?): JSONObject {
        val value = JSONObject().put("observation_kind", "post_endpoint_diagnostic")
        if (actualPackage != context.packageName) return value.put("status", "MAIN_NOT_OBSERVED_FOREGROUND")
        return try {
            value
                .put("status", "READ")
                .put(
                    "result_text",
                    JSONArray(
                        nodes("navigation_screen_result").flatMap { node ->
                            node.config
                                .getOrNull(SemanticsProperties.Text)
                                .orEmpty()
                                .map { it.text }
                        },
                    ),
                ).put("connecting_visible", nodes("navigation_screen_connecting").isNotEmpty())
                .put("locked_visible", nodes("home_locked_title").isNotEmpty())
        } catch (failure: Throwable) {
            value.put("status", "UNAVAILABLE").put("failure", failure.javaClass.simpleName)
        }
    }

    private fun saveForegroundProof(
        name: String,
        outcome: String,
    ) {
        val actual = packageNow()
        val observation = lastForegroundObservation
        File(evidence, "$name-proof.json").writeText(
            JSONObject()
                .put("observation_kind", "post_endpoint_diagnostic")
                .put("preceding_outcome", outcome)
                .put("observed_elapsed_realtime_ns", SystemClock.elapsedRealtimeNanos())
                .put("actual_foreground_package", actual ?: JSONObject.NULL)
                .put("main_lifecycle", mainState().name)
                .put("focus_count", observation.focusCount ?: JSONObject.NULL)
                .put("interactive_windows_flag", observation.flagObserved ?: JSONObject.NULL)
                .put("main_display_known", observation.mainDisplayKnown)
                .put("same_display", observation.sameDisplay ?: JSONObject.NULL)
                .put("unknown_reason", observation.unknownReason ?: JSONObject.NULL)
                .put("unknown_samples", foregroundNullSamples)
                .put("unknown_reasons", JSONObject(foregroundUnknownReasons as Map<*, *>))
                .toString(2),
        )
        capture(name)
    }

    private fun assertPermissionBoundary() {
        val permissions =
            context.packageManager
                .getPackageInfo(
                    context.packageName,
                    PackageManager.GET_PERMISSIONS,
                ).requestedPermissions
                .orEmpty()
        assertTrue(
            "화면 권한만 정상 선언/허용되어야 합니다",
            "pleos.car.permission.NAVI_CUSTOM_MAP" in permissions &&
                context.checkSelfPermission("pleos.car.permission.NAVI_CUSTOM_MAP") == PackageManager.PERMISSION_GRANTED,
        )
        assertTrue(
            "경로나 경로 정보 권한으로 확대하지 않습니다",
            listOf("pleos.car.permission.NAVI_ROUTE", "pleos.car.permission.NAVI_ROUTE_SEARCH").none {
                it in
                    permissions
            },
        )
        val info = context.packageManager.getPermissionInfo("pleos.car.permission.NAVI_CUSTOM_MAP", 0)
        assertTrue(
            "이 검사에서 새 dangerous 허용 흐름을 대신하면 안 됩니다",
            info.protectionLevel and android.content.pm.PermissionInfo.PROTECTION_MASK_BASE ==
                android.content.pm.PermissionInfo.PROTECTION_NORMAL,
        )
    }

    private fun awaitAllowed(
        graph: RecordingTestEntryPoint,
        timeout: Long = 25_000,
    ) = runBlocking {
        withTimeout(timeout) {
            combine(graph.vehicle().state, graph.restrictions().restrictions) { v, r -> v to r }.first { (v, r) ->
                allowsPracticeInteraction(v, r)
            }
        }
        Unit
    }

    private fun snapshot(graph: RecordingTestEntryPoint): ExistingData =
        runBlocking {
            withTimeout(20_000) {
                val sessions = graph.sessions().observeSessions().first()
                ExistingData(
                    graph.preparation().observeSelection().first(),
                    sessions,
                    sessions.map { graph.observations().observe(it.sessionId).first() },
                    sessions.map { graph.memos().observe(it.sessionId).first() },
                    sessions.map { graph.recordingRuns().observe(it.sessionId).first() },
                )
            }
        }

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

    private fun awaitStableNavigation(expected: String) {
        val deadline = SystemClock.elapsedRealtimeNanos() + 25_000_000_000L
        var observed = false
        while (SystemClock.elapsedRealtimeNanos() < deadline) {
            val actual = packageNow()
            val state = mainState()
            val receipt = SystemClock.elapsedRealtimeNanos()
            if (receipt < deadline && actual == expected && !state.isAtLeast(Lifecycle.State.STARTED)) {
                observed = true
                break
            }
            SystemClock.sleep(50)
        }
        assertTrue("같은 Main display의 실제 내비 전면과 Main STOP을 제한 시간 안에 관찰해야 합니다", observed)
        // Accessibility can change before the compositor presents the new window.
        // This settling wait is outside every performance measurement.
        instrumentation.uiAutomation.waitForIdle(1_000, 10_000)
        SystemClock.sleep(1_000)
        assertTrue("전환 뒤에도 실제 내비 전면과 Main STOP이 유지돼야 합니다", packageNow() == expected && !mainState().isAtLeast(Lifecycle.State.STARTED))
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
        val current = compose.activity
        instrumentation.runOnMainSync {
            check(current.lifecycle.currentState == Lifecycle.State.RESUMED)
            val display = checkNotNull(current.display)
            check(display.isValid && display.displayId == mainDisplayId)
            mainActivity = current
        }
    }

    private fun nodes(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    private fun waitTag(tag: String) {
        compose.waitUntil(20_000) { nodes(tag).size == 1 }
        compose.waitForIdle()
    }

    private fun clickAction(): () -> Boolean {
        compose.onNodeWithTag("navigation_screen_open").performScrollTo()
        return checkNotNull(nodes("navigation_screen_open").single().config[SemanticsActions.OnClick].action)
    }

    private fun assertText(
        tag: String,
        expected: String,
    ) {
        val node = nodes(tag).single()
        assertTrue("실제 원문과 일치해야 합니다", node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == expected } == true)
    }

    private fun verifyGlyphs(tag: String) {
        waitTag(tag)
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val node = nodes(tag).single()
        val bounds = node.boundsInRoot
        assertTrue(
            "요소가 실제 앱 화면 안에 있어야 합니다",
            bounds.left >= -1 && bounds.top >= -1 && bounds.right <= compose.activity.window.decorView.width + 1 &&
                bounds.bottom <= compose.activity.window.decorView.height + 1,
        )
        if (tag == "navigation_screen_scope") {
            val layouts = mutableListOf<TextLayoutResult>()
            assertTrue(
                "실제 TextLayout을 검사해야 합니다",
                node.config
                    .getOrNull(SemanticsActions.GetTextLayoutResult)
                    ?.action
                    ?.invoke(layouts) == true && layouts.isNotEmpty(),
            )
            assertTrue(
                "기본/확대 글자가 생략되거나 잘리면 안 됩니다",
                layouts.none { it.hasVisualOverflow || (0 until it.lineCount).any(it::isLineEllipsized) },
            )
        }
    }

    private fun capture(name: String) {
        val screenshot = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            File(evidence, "$name.png").outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        } finally {
            screenshot.recycle()
        }
    }

    private fun phase(name: String) {
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "$name\n") })
    }

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
        val observations: List<List<SessionVehicleObservation>>,
        val memos: List<SessionMemo?>,
        val runs: List<List<VehicleRecordingRun>>,
    )
}
