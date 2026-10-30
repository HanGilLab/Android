package io.github.hangillab.hangil

import android.graphics.Bitmap
import android.os.Bundle
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI

/** Run on the dedicated Pleos emulator with P and unrestricted UX confirmed. */
@RunWith(AndroidJUnit4::class)
class MapFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun opensMapRecreatesAndReopens() {
        openMap()
        waitFor("map_ready")
        compose.onNodeWithTag("map_view").assertExists()
        capture("map-initial")
        compose.activityRule.scenario.recreate()
        waitFor("map_ready")
        compose.onNodeWithTag("map_view").assertExists()
        capture("map-recreated")
        compose.onNodeWithTag("map_close").performScrollTo().performClick()
        openMap()
        waitFor("map_ready")
        capture("map-reopened")
    }

    /** Configure a reversible unreachable network proxy before running this scenario. */
    @Test fun unavailableNetworkShowsFailureAndRetry() {
        openMap()
        waitFor("map_error")
        compose.onNodeWithTag("map_retry").assertExists()
        compose.onNodeWithTag("map_ready").assertDoesNotExist()
        capture("map-network-error")
        val originalProxy = shell("settings get global http_proxy").trim()
        require(originalProxy == "null" || originalProxy.matches(Regex("""[A-Za-z0-9._:\[\]-]+"""))) {
            "원래 프록시를 단일 인자로 안전하게 복구할 수 있어야 합니다"
        }
        try {
            shell("settings put global http_proxy :0")
            compose.waitUntil(20_000) {
                val proxies = ProxySelector.getDefault()?.select(URI("https://tiles.openfreemap.org/styles/liberty"))
                !proxies.isNullOrEmpty() && proxies.all { it.type() == Proxy.Type.DIRECT }
            }
            compose.onNodeWithTag("map_retry").performClick()
            waitFor("map_ready")
            capture("map-network-recovered")
        } finally {
            shell("settings put global http_proxy :0")
            shell(
                if (originalProxy == "null") {
                    "settings delete global http_proxy"
                } else {
                    "settings put global http_proxy " + originalProxy
                },
            )
        }
    }

    /** Change P to D, then return to P with Virtual Console when each status marker appears. */
    @Test fun mapLocksDuringDrivingAndReturnsWhenParked() {
        openMap()
        waitFor("map_ready")
        phase("AWAIT_D")
        compose.waitUntil(90_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("map_view"),
                ).fetchSemanticsNodes()
                .isEmpty() &&
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasText("주차 후 이어서 준비해요"),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
        }
        compose.onNodeWithTag("map_view").assertDoesNotExist()
        capture("map-driving-locked")
        phase("AWAIT_P")
        waitFor("map_ready")
        capture("map-parked-again")
    }

    private fun openMap() {
        waitFor("home_map")
        compose.onNodeWithTag("home_map").performScrollTo().performClick()
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(90_000) {
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
        File(directory, "$name.png").outputStream().use {
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it))
        }
        bitmap.recycle()
    }

    private fun phase(name: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "MAP_SCENARIO_$name\n") })
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor
            .AutoCloseInputStream(InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command))
            .bufferedReader()
            .use { it.readText() }
}
