package io.github.hangillab.hangil

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.os.Build
import android.os.Bundle
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run the parked and P-D-P scenarios separately on the dedicated Pleos AVD. */
@RunWith(AndroidJUnit4::class)
class BrandFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun parkedHomeAndCourseExposeOneAccessibleWordmark() {
        waitForBrand()
        compose.onNodeWithTag("hangil_brand").assertTextContains("한길졸업")
        capture("brand-home")
        compose.onNodeWithTag("home_prepare").performScrollTo().performClick()
        waitFor("goal_commute")
        waitForBrand()
        compose.onNodeWithTag("hangil_brand").assertTextContains("한길졸업")
        capture("brand-course")
    }

    @Test fun drivingHidesTheDecorativeBrandAndParkRestoresIt() {
        waitForBrand()
        phase("AWAIT_D")
        compose.waitUntil(90_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("hangil_brand"),
                ).fetchSemanticsNodes()
                .isEmpty() &&
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasText("주차 후 이어서 준비해요"),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
        }
        compose.onNodeWithTag("hangil_brand").assertDoesNotExist()
        capture("brand-driving-locked")
        phase("AWAIT_P")
        waitForBrand()
        capture("brand-parked-again")
    }

    @Test fun installedLauncherResourcesRenderAcrossMasksAndBackgrounds() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assertNotEquals(0, target.applicationInfo.icon)
        val drawable = target.packageManager.getApplicationIcon(target.packageName)
        assertTrue(drawable is AdaptiveIconDrawable)
        val adaptive = drawable as AdaptiveIconDrawable
        for ((name, rounded) in listOf("circle" to false, "rounded-square" to true)) {
            val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val mask =
                Path().apply {
                    if (rounded) {
                        addRoundRect(RectF(0f, 0f, 432f, 432f), 86f, 86f, Path.Direction.CW)
                    } else {
                        addCircle(216f, 216f, 216f, Path.Direction.CW)
                    }
                }
            canvas.clipPath(mask)
            for (layer in listOf(adaptive.background, adaptive.foreground)) {
                // AdaptiveIconDrawable uses a 108dp layer around the visible 72dp viewport.
                layer.setBounds(-108, -108, 540, 540)
                layer.draw(canvas)
            }
            save(bitmap, "brand-launcher-$name")
        }
        if (Build.VERSION.SDK_INT >= 33) {
            val mono = adaptive.monochrome
            assertNotNull(mono)
            for ((name, background, foreground) in listOf(
                Triple("light", Color.rgb(255, 246, 230), Color.rgb(7, 61, 49)),
                Triple("dark", Color.rgb(23, 46, 40), Color.rgb(167, 218, 196)),
            )) {
                val bitmap = Bitmap.createBitmap(432, 432, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                canvas.drawColor(background)
                mono!!.setTint(foreground)
                mono.setBounds(0, 0, 432, 432)
                mono.draw(canvas)
                save(bitmap, "brand-monochrome-$name")
            }
        }
    }

    private fun waitForBrand() {
        compose.waitUntil(20_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("hangil_brand"),
                ).fetchSemanticsNodes()
                .size == 1
        }
    }

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

    private fun phase(name: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "BRAND_SCENARIO_$name\n") })
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        save(checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()), name)
    }

    private fun save(
        bitmap: Bitmap,
        name: String,
    ) {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(target.cacheDir, "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
