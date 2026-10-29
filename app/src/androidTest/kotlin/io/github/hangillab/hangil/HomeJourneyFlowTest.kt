package io.github.hangillab.hangil

import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class HomeJourneyFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    /** Requires a newly installed, separate validation package. Never clears the existing app DB. */
    @Test fun freshInstallPreparationStartFinishAndRecreation() {
        assertPackage("io.github.hangillab.hangil.onboardingprobe.debug")
        waitFor("home_step_prepare")
        compose.onNodeWithTag("saved_course").assertDoesNotExist()
        compose.onNodeWithTag("session_start").assertDoesNotExist()
        capture("fresh")
        touch("home_prepare")
        waitFor("goal_commute")
        touch("goal_commute")
        touch("preparation_next")
        waitFor("course_intersections")
        touch("course_intersections")
        touch("preparation_next")
        waitFor("task_scene")
        touch("task_scene")
        touch("preparation_next")
        waitFor("home_step_ready")
        capture("ready")
        compose.activityRule.scenario.recreate()
        waitFor("home_step_ready")
        touch("session_start")
        waitFor("home_step_active")
        compose.onNodeWithTag("session_course").assertTextContains("교차로 흐름 익히기")
        capture("active")
        compose.activityRule.scenario.recreate()
        waitFor("home_step_active")
        touch("session_finish")
        waitFor("home_step_repeat")
        capture("finished")
        compose.activityRule.scenario.recreate()
        waitFor("home_step_repeat")
        touch("home_prepare")
        waitFor("goal_commute")
        back()
        waitFor("home_step_repeat")
        capture("next-preparation-return")
        touch("home_prepare")
        waitFor("goal_commute")
        touch("goal_commute")
        touch("preparation_next")
        waitFor("course_neighborhood")
        touch("course_neighborhood")
        touch("preparation_next")
        waitFor("task_sequence")
        touch("task_sequence")
        touch("preparation_next")
        waitFor("home_step_ready")
        compose.onNodeWithTag("saved_course").assertTextContains("생활권 한 바퀴")
        compose.activityRule.scenario.recreate()
        waitFor("home_step_ready")
        capture("different-preparation-ready")
    }

    /** Existing preparation and completed records are read only; leave the draft without saving. */
    @Test fun existingRecordsGuideAfterProcessRelaunch() {
        assertPackage("io.github.hangillab.hangil.debug")
        waitFor("home_step_repeat")
        compose.onNodeWithTag("home_journey_flow").assertDoesNotExist()
        touch("home_record_guide")
        compose.onNodeWithTag("home_journey_flow").performScrollTo().assertIsDisplayed()
        touch("home_record_guide")
        compose.onNodeWithTag("home_journey_flow").assertDoesNotExist()
        capture("existing-restored")
        touch("home_prepare")
        waitFor("goal_commute")
        back()
        waitFor("home_step_repeat")
        compose.activityRule.scenario.recreate()
        waitFor("home_step_repeat")
        capture("existing-recreated")
    }

    @Test fun actualDriveTransitionHidesGuideAndRestoresAfterPark() {
        assertPackage("io.github.hangillab.hangil.debug")
        waitFor("home_step_repeat")
        phase("AWAIT_D")
        compose.waitUntil(90_000) { exists("home_locked_title") && !exists("home_journey") }
        compose.onNodeWithTag("home_locked_title").assertIsDisplayed()
        listOf("home_journey_flow", "home_prepare", "session_start", "session_finish", "hangil_brand")
            .forEach { compose.onNodeWithTag(it).assertDoesNotExist() }
        capture("driving-locked")
        phase("AWAIT_P")
        waitFor("home_step_repeat")
        capture("parked-again")
    }

    private fun assertPackage(expected: String) {
        assertEquals(expected, InstrumentationRegistry.getInstrumentation().targetContext.packageName)
    }

    private fun touch(tag: String) {
        compose
            .onNodeWithTag(tag)
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(64.dp)
            .performClick()
    }

    private fun back() {
        compose
            .onNodeWithText("돌아가기")
            .performScrollTo()
            .assertIsDisplayed()
            .performClick()
    }

    private fun waitFor(tag: String) {
        compose.waitUntil(20_000) { exists(tag) }
    }

    // A temporarily absent root is unknown; waitFor still requires the explicit expected node.
    private fun exists(tag: String) =
        compose
            .onAllNodes(
                androidx.compose.ui.test
                    .hasTestTag(tag),
            ).fetchSemanticsNodes(atLeastOneRootRequired = false)
            .size == 1

    private fun phase(name: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "HOME_JOURNEY_$name\n") })
    }

    private fun capture(name: String) {
        val titleTag =
            listOf("home_step_prepare", "home_step_ready", "home_step_active", "home_step_repeat", "home_locked_title")
                .first { exists(it) }
        compose.onNodeWithTag(titleTag).performScrollTo().assertIsDisplayed()
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val expectedPackage = instrumentation.targetContext.packageName
        compose.waitUntil(20_000) { automation.rootInActiveWindow?.packageName?.toString() == expectedPackage }
        automation.waitForIdle(500, 5_000)
        assertEquals(expectedPackage, automation.rootInActiveWindow?.packageName?.toString())
        val screenshot = checkNotNull(automation.takeScreenshot())
        val directory = File(instrumentation.targetContext.cacheDir, "screenshots").apply { mkdirs() }
        File(directory, "journey-$name.png").outputStream().use { check(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        screenshot.recycle()
        // The operator must inspect this full native screenshot for the actual central app window.
    }
}
