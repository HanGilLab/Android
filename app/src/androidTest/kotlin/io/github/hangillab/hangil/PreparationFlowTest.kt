package io.github.hangillab.hangil

import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Run individual scenarios on the dedicated Pleos emulator in the corresponding P or D state. */
@RunWith(AndroidJUnit4::class)
class PreparationFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun selectSaveAndRestoreWhileParked() {
        waitFor("home_prepare")
        capture("01-home")
        compose.onNodeWithTag("home_prepare").performScrollTo().performClick()
        waitFor("goal_commute")
        compose.onNodeWithTag("goal_commute").performClick()
        capture("02-goal")
        compose.onNodeWithTag("preparation_next").performScrollTo().performClick()
        compose.onNodeWithTag("course_intersections").performScrollTo().performClick()
        capture("03-course")
        compose.onNodeWithTag("preparation_next").performScrollTo().performClick()
        compose.onNodeWithTag("task_scene").performScrollTo().performClick()
        capture("04-briefing")
        compose.onNodeWithTag("preparation_next").performScrollTo().performClick()
        assertSaved()
        capture("05-saved")
        compose.activityRule.scenario.recreate()
        assertSaved()
    }

    @Test fun restoredPreparationAfterProcessRestart() {
        assertSaved()
        capture("06-restored")
    }

    @Test fun drivingStateHidesPreparation() {
        compose.waitUntil(
            20_000,
        ) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasText("주차 후 이어서 준비해요"),
                ).fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag("home_prepare").assertDoesNotExist()
        compose.onNodeWithTag("saved_course").assertDoesNotExist()
        compose.onNodeWithTag("saved_task").assertDoesNotExist()
        capture("07-driving-locked")
    }

    private fun assertSaved() {
        waitFor("saved_course")
        compose.onNodeWithTag("saved_course").assertTextContains("교차로 흐름 익히기")
        compose.onNodeWithTag("saved_task").assertTextContains("낯선 장면 하나 떠올리기")
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

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.cacheDir, "screenshots")
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use {
            check(
                compose
                    .onRoot()
                    .captureToImage()
                    .asAndroidBitmap()
                    .compress(Bitmap.CompressFormat.PNG, 100, it),
            )
        }
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "Screenshot: ${File(directory, "$name.png")}\n") })
    }
}
