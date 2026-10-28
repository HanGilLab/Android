package io.github.hangillab.hangil

import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.domain.ConnectionState
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.ParkingState
import io.github.hangillab.hangil.core.domain.UxRestrictionState
import io.github.hangillab.hangil.core.domain.VehicleReading
import io.github.hangillab.hangil.core.vehicle.AaosDrivingRestrictionsProvider
import io.github.hangillab.hangil.core.vehicle.PleosVehicleGateway
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Externally set gear/UX on a dedicated Pleos AVD; no fake SDK or replacement UI state. */
@RunWith(AndroidJUnit4::class)
class VehicleRestrictionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun actualGearAndUxCombination() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        val gear = GearPosition.valueOf(checkNotNull(args.getString("expected_gear")))
        require(gear == GearPosition.PARK || gear == GearPosition.DRIVE)
        val ux = UxRestrictionState.valueOf(checkNotNull(args.getString("expected_ux")))
        require(ux != UxRestrictionState.UNKNOWN)
        val allowed = gear == GearPosition.PARK && ux == UxRestrictionState.UNRESTRICTED

        // Separate real production adapters observe the same platform as the foreground app.
        // The probe neither supplies values to the app nor changes platform state.
        val gateway = PleosVehicleGateway(instrumentation.targetContext)
        val provider = AaosDrivingRestrictionsProvider(instrumentation.targetContext, gateway)
        val observed =
            runBlocking {
                withTimeout(25_000) {
                    combine(gateway.state, provider.restrictions) { vehicle, restrictions -> vehicle to restrictions }
                        .first { (vehicle, restrictions) ->
                            vehicle.connection == ConnectionState.CONNECTED &&
                                vehicle.gear == VehicleReading.Available(gear) &&
                                restrictions.parkingState ==
                                (if (gear == GearPosition.PARK) ParkingState.PARKED else ParkingState.NOT_PARKED) &&
                                restrictions.uxRestrictionState == ux
                        }
                }
            }
        assertEquals(allowed, observed.second.allowsDetailedInteraction)
        val expectedTag = if (allowed) "home_prepare" else "home_locked_title"
        compose.waitUntil(20_000) { exists(expectedTag) }
        compose.onNodeWithTag(expectedTag).performScrollTo().assertIsDisplayed()
        if (allowed) {
            compose.onNodeWithTag("home_locked_title").assertDoesNotExist()
            compose.onNodeWithTag("saved_course").assertExists()
        } else {
            listOf("home_prepare", "home_map", "saved_course", "saved_task", "hangil_brand", "session_start", "session_finish")
                .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        }

        val automation = instrumentation.uiAutomation
        val expectedPackage = instrumentation.targetContext.packageName
        compose.waitUntil(20_000) { automation.rootInActiveWindow?.packageName?.toString() == expectedPackage }
        automation.waitForIdle(500, 5_000)
        assertEquals(expectedPackage, automation.rootInActiveWindow?.packageName?.toString())
        val screenshot = checkNotNull(automation.takeScreenshot())
        val name = "ux-${gear.name}-${ux.name}"
        val directory = File(instrumentation.targetContext.cacheDir, "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "ACTUAL_UX_COMBINATION $name allowed=$allowed\n") })
        // Native foreground/window bounds and the screenshot must also be inspected by the operator.
    }

    private fun exists(tag: String) =
        compose
            .onAllNodes(
                androidx.compose.ui.test
                    .hasTestTag(tag),
            ).fetchSemanticsNodes()
            .size == 1
}
