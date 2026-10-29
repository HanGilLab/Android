package io.github.hangillab.hangil

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.vehicle.FusedLocationPermission
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Dedicated Pleos AVD only. Granting requires an explicit opt-in and approved external cleanup. */
@RunWith(AndroidJUnit4::class)
class LocationProbeFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun missingPermissionIsExplicitAndRecreationClearsTheProbe() {
        assumeTrue(!permissionGranted())
        openProbe()
        assertEquals(IDLE, status())
        compose.onNodeWithTag("location_read").performScrollTo().performClick()
        compose.waitUntil(20_000) { status().contains("위치 권한이 허용되지 않았어요") }
        compose.onNodeWithTag("location_permission").assertExists()
        compose.onNodeWithTag("location_last_known").assertDoesNotExist()
        capture("location-permission-missing")
        compose.activityRule.scenario.recreate()
        waitFor("location_read")
        compose.waitUntil(20_000) { status() == IDLE }
        compose.onNodeWithTag("location_permission").assertDoesNotExist()
    }

    /** Exercises the real ActivityResult path without granting any permission. */
    @Test fun deniedSystemRequestReturnsExplicitResultAndAllowsRetry() {
        assertTrue("전용 검증은 위치 권한 미허용 상태에서 시작해야 합니다", !permissionGranted())
        openProbe()
        touch("location_read")
        compose.waitUntil(20_000) { status().contains("위치 권한이 허용되지 않았어요") }
        touch("location_permission")
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        var systemWindowObserved = false
        val deadline = android.os.SystemClock.uptimeMillis() + 20_000
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            val root = automation.rootInActiveWindow
            if (root?.packageName?.toString()?.endsWith(".permissioncontroller") == true) {
                systemWindowObserved = true
                // Dismiss only. No grant action or permission shell mutation is used.
                try {
                    captureNativeWindow("location-system-permission-dialog")
                } finally {
                    automation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
                }
            }
            if (runCatching { status().contains("위치 권한이 허용되지 않았어요") }.getOrDefault(false)) break
            android.os.SystemClock.sleep(100)
        }
        phase("SYSTEM_PERMISSION_WINDOW_OBSERVED=$systemWindowObserved")
        assertTrue("시스템 요청 후에도 실제 권한은 미허용이어야 합니다", !permissionGranted())
        compose.waitUntil(5_000) { status().contains("위치 권한이 허용되지 않았어요") }
        compose.onNodeWithTag("location_permission").assertExists()
        compose.onNodeWithTag("location_last_known").assertDoesNotExist()
        capture("location-system-request-denied")
        touch("location_read")
        compose.waitUntil(20_000) { status().contains("위치 권한이 허용되지 않았어요") }
        touch("map_close")
        openProbe()
        assertIdleProbe()
        capture("location-system-request-reentered")
    }

    /**
     * Run only with approved temporary consent and an external runner that restores grants and flags,
     * even if this test fails. Default instrumentation discovery must never grant this permission.
     */
    @Test fun approvedSystemGrantReadsActualSdkResponseAndAllowsFreshRetry() {
        assumeTrue(
            "임시 위치 권한 허용과 외부 복구를 승인한 개별 검증에서만 실행합니다",
            InstrumentationRegistry.getArguments().getString("allowTemporaryFusedLocationGrant") == "true",
        )
        assertTrue("전용 검증은 위치 권한 미허용 상태에서 시작해야 합니다", !permissionGranted())
        openProbe()
        touch("location_read")
        compose.waitUntil(20_000) { status().contains("위치 권한이 허용되지 않았어요") }
        touch("location_permission")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val context = instrumentation.targetContext
        val appLabel = context.applicationInfo.loadLabel(context.packageManager).toString()
        var allowButton: AccessibilityNodeInfo? = null
        val deadline = android.os.SystemClock.uptimeMillis() + 20_000
        while (android.os.SystemClock.uptimeMillis() < deadline && allowButton == null) {
            val root = automation.rootInActiveWindow
            val controllerPackage = root?.packageName?.toString()
            if (controllerPackage == "com.android.permissioncontroller" || controllerPackage == "com.google.android.permissioncontroller") {
                allowButton = automotiveGrantButton(checkNotNull(root), controllerPackage, appLabel)
            }
            if (allowButton == null) android.os.SystemClock.sleep(100)
        }
        val approvedButton = checkNotNull(allowButton) { "시스템 권한창의 명시적인 허용 버튼을 확인하지 못했습니다" }
        assertTrue("시스템 버튼 선택 전에는 실제 권한이 미허용이어야 합니다", !permissionGranted())
        captureNativeWindow("location-system-grant-dialog")
        assertTrue("시스템 권한 허용 버튼이 계속 유효해야 합니다", approvedButton.refresh())
        assertTrue("시스템 권한 허용 버튼 선택에 실패했습니다", approvedButton.performAction(AccessibilityNodeInfo.ACTION_CLICK))
        phase("SYSTEM_PERMISSION_GRANT_WINDOW_OBSERVED=true")
        compose.waitUntil(20_000) { permissionGranted() }
        awaitActualSdkResponse("location-system-grant-sdk-response")
        touch("map_close")
        openProbe()
        assertIdleProbe()
        capture("location-system-grant-reentered-idle")
        touch("location_read")
        awaitActualSdkResponse("location-system-grant-retry-sdk-response")
    }

    private fun automotiveGrantButton(
        root: AccessibilityNodeInfo,
        controllerPackage: String,
        appLabel: String,
    ): AccessibilityNodeInfo? {
        val dialogTitles =
            root.findAccessibilityNodeInfosByViewId("$controllerPackage:id/car_ui_alert_title").filter {
                it.packageName?.toString() == controllerPackage && it.isVisibleToUser && it.text?.contains(appLabel) == true
            }
        if (dialogTitles.isEmpty()) return null
        assertEquals("요청한 앱의 시스템 권한 제목은 하나여야 합니다", 1, dialogTitles.size)
        val resources =
            InstrumentationRegistry.getInstrumentation().targetContext.packageManager.getResourcesForApplication(
                controllerPackage,
            )
        val allowLabelId = resources.getIdentifier("grant_dialog_button_allow", "string", controllerPackage)
        check(allowLabelId != 0) { "설치된 권한 컨트롤러의 허용 문구를 확인하지 못했습니다" }
        val allowLabel = resources.getString(allowLabelId)
        val titleId = "$controllerPackage:id/car_ui_list_item_title"
        val allowTitles =
            root.findAccessibilityNodeInfosByViewId(titleId).filter {
                it.packageName?.toString() == controllerPackage && it.isVisibleToUser && it.isEnabled && it.text?.toString() == allowLabel
            }
        if (allowTitles.isEmpty()) return null
        assertEquals("명시적인 시스템 권한 허용 항목은 하나여야 합니다", 1, allowTitles.size)
        val row = allowTitles.single().parent ?: return null
        val rowTitles = row.findAccessibilityNodeInfosByViewId(titleId)
        assertEquals("허용 문구와 조작 영역이 같은 단일 행에 있어야 합니다", 1, rowTitles.size)
        assertEquals(allowLabel, rowTitles.single().text?.toString())
        val buttons =
            row.findAccessibilityNodeInfosByViewId("$controllerPackage:id/car_ui_list_item_touch_interceptor").filter {
                it.packageName?.toString() == controllerPackage && it.isVisibleToUser && it.isEnabled && it.isClickable
            }
        if (buttons.isEmpty()) return null
        assertEquals("허용 행의 조작 영역은 하나여야 합니다", 1, buttons.size)
        return buttons.single()
    }

    /** Run only after a person has approved the dedicated AVD's custom location permission. */
    @Test fun grantedPermissionIdentifiesTheActualSdkResponse() {
        assumeTrue(permissionGranted())
        openProbe()
        touch("location_read")
        awaitActualSdkResponse("location-sdk-response")
    }

    /**
     * Leaves after the read action without imposing a delay or injecting an SDK callback.
     * The SDK may finish before close; the marker reports only the sampled UI state before it.
     * Re-entry and retry assertions do not claim that a late callback was forced to occur.
     */
    @Test fun grantedReadThenLeaveAndRetryUsesFreshUiState() {
        assumeTrue(permissionGranted())
        openProbe()
        assertIdleProbe()
        touch("location_read")
        phase("OBSERVED_LOADING_BEFORE_CLOSE=" + (status() == LOADING))
        touch("map_close")
        waitFor("home_map")
        compose.waitUntil(20_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("location_probe"),
                ).fetchSemanticsNodes()
                .isEmpty()
        }
        compose.onNodeWithTag("location_last_known").assertDoesNotExist()
        openProbe()
        assertIdleProbe()
        capture("location-reentered-idle")
        touch("location_read")
        awaitActualSdkResponse("location-retry-sdk-response")
    }

    /** Checks the visible probe before and after recreation; not a continuous SDK call trace. */
    @Test fun grantedResponseThenRecreationReturnsIdleWithoutAutomaticQuery() {
        assumeTrue(permissionGranted())
        openProbe()
        assertIdleProbe()
        touch("location_read")
        awaitActualSdkResponse("location-before-recreation")
        compose.activityRule.scenario.recreate()
        assertIdleProbe()
        capture("location-recreated-idle")
    }

    private fun permissionGranted(): Boolean {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("io.github.hangillab.hangil.debug", context.packageName)
        return context.checkSelfPermission(FusedLocationPermission.NAME) == PackageManager.PERMISSION_GRANTED
    }

    private fun touch(tag: String) {
        compose
            .onNodeWithTag(tag)
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
    }

    private fun assertIdleProbe() {
        waitFor("location_read")
        compose.waitUntil(20_000) { status() == IDLE }
        compose.onNodeWithTag("location_status").performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        assertEquals(IDLE, status())
        compose.onNodeWithTag("location_last_known").assertDoesNotExist()
        compose.onNodeWithTag("location_permission").assertDoesNotExist()
        // Idle and absent old-result UI are observed at these checkpoints, not for all future time.
    }

    private fun awaitActualSdkResponse(captureName: String) {
        compose.waitUntil(40_000) { status().let { it != IDLE && it != LOADING } }
        val result = status()
        capture(captureName)
        assertTrue("SDK 응답은 마지막 위치 또는 명시적인 빈 결과여야 합니다: $result", result == LAST_KNOWN || result == EMPTY)
        if (result == LAST_KNOWN) {
            compose.onNodeWithTag("location_last_known").assertExists()
            phase("RESPONSE_LAST_KNOWN")
        } else {
            compose.onNodeWithTag("location_last_known").assertDoesNotExist()
            phase("RESPONSE_EMPTY")
        }
        // SDK coordinates, timestamps, confidence and raw objects are never logged or plotted here.
    }

    private fun phase(name: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("stream", "LOCATION_PROBE_LIFECYCLE_$name\n") },
        )
    }

    private fun openProbe() {
        waitFor("home_map")
        compose.onNodeWithTag("home_map").performScrollTo().performClick()
        waitFor("location_read")
        compose.onNodeWithTag("location_read").performScrollTo()
    }

    private fun status(): String =
        compose
            .onNodeWithTag("location_status")
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString { it.text }

    private fun waitFor(tag: String) {
        compose.waitUntil(20_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag(tag),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        assertNativeForegroundEvidence()
        captureNativeWindow(name)
    }

    private fun captureNativeWindow(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "screenshots").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun assertNativeForegroundEvidence() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val expectedPackage = instrumentation.targetContext.packageName
        compose.waitUntil(20_000) { automation.rootInActiveWindow?.packageName?.toString() == expectedPackage }
        automation.waitForIdle(500, 5_000)
        assertEquals(expectedPackage, automation.rootInActiveWindow?.packageName?.toString())
        // Pleos regional windows can still obscure the central app despite this package evidence.
        // The operator must inspect the full screenshot; this check alone does not prove visibility.
    }

    private companion object {
        const val IDLE = "아직 위치를 조회하지 않았어요."
        const val LOADING = "마지막 위치를 확인하고 있어요."
        const val LAST_KNOWN = "SDK에서 마지막 위치 1건을 받았어요."
        const val EMPTY = "SDK가 마지막 위치를 반환하지 않았어요."
    }
}
