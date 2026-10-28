package io.github.hangillab.hangil

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
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

/** Dedicated Pleos AVD only. These tests inspect permission state and never grant it. */
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
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        assertNativeForegroundEvidence()
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
