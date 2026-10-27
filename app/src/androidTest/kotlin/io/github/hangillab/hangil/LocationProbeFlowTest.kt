package io.github.hangillab.hangil

import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsProperties
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
        compose.onNodeWithTag("location_read").performScrollTo().performClick()
        compose.waitUntil(40_000) { status().let { it != IDLE && it != LOADING } }
        val result = status()
        capture("location-sdk-response")
        assertTrue("SDK 응답은 마지막 위치 또는 명시적인 빈 결과여야 합니다: $result", result == LAST_KNOWN || result == EMPTY)
        // A last-known response is deliberately not plotted on the fixed demonstration map.
        if (result == LAST_KNOWN) compose.onNodeWithTag("location_last_known").assertExists()
    }

    private fun permissionGranted(): Boolean =
        InstrumentationRegistry
            .getInstrumentation()
            .targetContext
            .checkSelfPermission(FusedLocationPermission.NAME) == PackageManager.PERMISSION_GRANTED

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
        val directory = File(instrumentation.targetContext.cacheDir, "screenshots").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private companion object {
        const val IDLE = "아직 위치를 조회하지 않았어요."
        const val LOADING = "마지막 위치를 확인하고 있어요."
        const val LAST_KNOWN = "SDK에서 마지막 위치 1건을 받았어요."
        const val EMPTY = "SDK가 마지막 위치를 반환하지 않았어요."
    }
}
