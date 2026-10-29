package io.github.hangillab.hangil

import android.content.res.Configuration
import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Actual main screens on the dedicated AVD; theme_mode and font_scale are required arguments. */
@RunWith(AndroidJUnit4::class)
class ThemeFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun parkedHomeKeepsActionAndPreparationReachable() {
        assertEnvironment()
        waitFor("home_prepare")
        waitFor("saved_course")
        val expectedLayout = checkNotNull(InstrumentationRegistry.getArguments().getString("home_layout"))
        check(expectedLayout == "wide" || expectedLayout == "stacked")
        compose.onNodeWithTag("home_dashboard_$expectedLayout").assertExists()
        compose.onNodeWithTag("home_illustration").assertExists()
        val savedCourse = text("saved_course")
        val savedTask = text("saved_task")
        val actionBounds = compose.onNodeWithTag("home_prepare").getUnclippedBoundsInRoot()
        val courseBounds = compose.onNodeWithTag("saved_course").getUnclippedBoundsInRoot()
        if (expectedLayout == "wide") {
            assertTrue("Preparation must occupy the other column", courseBounds.left >= actionBounds.right)
        } else {
            assertTrue("Preparation must follow the primary action", courseBounds.top >= actionBounds.bottom)
        }
        // The primary action is immediately visible in the wide dashboard. Expanded text may
        // require scrolling in the stacked dashboard, but must never clip or hide the action.
        if (expectedLayout == "wide") compose.onNodeWithTag("home_prepare").assertIsDisplayed()
        capture("home-heading")
        compose
            .onNodeWithTag("home_prepare")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(64.dp)
        capture("home-dashboard")
        compose
            .onNodeWithTag("saved_course")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains(savedCourse)
        compose
            .onNodeWithTag("saved_task")
            .performScrollTo()
            .assertIsDisplayed()
            .assertTextContains(savedTask)
        compose
            .onNodeWithTag("home_map")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(64.dp)
        capture("home-preparation")
    }

    @Test fun parkedTypographySelectionAndUnsavedBriefing() {
        assertEnvironment()
        waitFor("home_prepare")
        waitFor("saved_course")
        val savedCourse = text("saved_course")
        capture("home")
        touch("home_prepare")
        waitFor("goal_commute")
        touch("goal_commute")
        compose.onNodeWithTag("goal_commute").assertIsSelected()
        capture("goal-selected")
        touch("preparation_next")
        waitFor("course_intersections")
        touch("course_intersections")
        compose.onNodeWithTag("course_intersections").assertIsSelected()
        capture("course-selected")
        touch("preparation_next")
        waitFor("task_scene")
        compose.onNodeWithText("출발 전에, 한 번 살펴봐요").performScrollTo().assertIsDisplayed()
        capture("briefing")
        touch("task_scene")
        compose.onNodeWithTag("task_scene").assertIsSelected()
        capture("briefing-task")
        // Leave the draft without saving; the user's existing preparation must remain unchanged.
        back()
        waitFor("course_intersections")
        back()
        waitFor("goal_commute")
        back()
        waitFor("saved_course")
        compose.onNodeWithTag("saved_course").assertTextContains(savedCourse)
    }

    @Test fun parkedMapFrameAndNavigation() {
        assertEnvironment()
        waitFor("home_map")
        touch("home_map")
        waitFor("map_close")
        compose
            .onNodeWithTag("map_close")
            .performScrollTo()
            .assertIsDisplayed()
            .assertHeightIsAtLeast(64.dp)
        // Read the three alternatives together: loading can finish between separate queries.
        var observed = emptySet<String>()
        compose.waitUntil(20_000) {
            observed =
                compose
                    .onAllNodes(
                        hasTestTag("map_ready") or hasTestTag("map_error") or hasTestTag("map_loading"),
                    ).fetchSemanticsNodes()
                    .map { it.config[SemanticsProperties.TestTag] }
                    .toSet()
            observed.isNotEmpty()
        }
        val outcome =
            when {
                "map_ready" in observed -> "READY_CALLBACK"
                "map_error" in observed -> "FAILED_UI"
                "map_loading" in observed -> "LOADING_UI"
                else -> error("Map must expose an explicit rendering state")
            }
        phase("MAP_$outcome")
        capture("map-frame")
        touch("map_close")
        waitFor("home_prepare")
        // The fixed Liberty map style has not been replaced by an application theme or a fake map.
    }

    @Test fun drivingLockRestoresHomeWithExpandedFont() {
        assertEnvironment()
        waitFor("home_prepare")
        val savedCourse = text("saved_course")
        compose.onNodeWithTag("home_map").performScrollTo()
        phase("AWAIT_D")
        compose.waitUntil(90_000) { exists("home_locked_title") && !exists("home_prepare") }
        compose.onNodeWithTag("home_locked_title").assertIsDisplayed()
        compose.onNodeWithTag("hangil_brand").assertDoesNotExist()
        compose.onNodeWithTag("home_dashboard").assertDoesNotExist()
        compose.onNodeWithTag("home_illustration").assertDoesNotExist()
        compose.onNodeWithTag("session_start").assertDoesNotExist()
        compose.onNodeWithTag("session_finish").assertDoesNotExist()
        capture("driving-locked")
        phase("AWAIT_P")
        waitFor("home_prepare")
        waitFor("saved_course")
        compose.onNodeWithTag("saved_course").assertTextContains(savedCourse)
        capture("parked-again")
    }

    private fun assertEnvironment() {
        val args = InstrumentationRegistry.getArguments()
        val mode = checkNotNull(args.getString("theme_mode"))
        val scale = checkNotNull(args.getString("font_scale")).toFloat()
        val configuration = compose.activity.resources.configuration
        val expectedMode =
            when (mode) {
                "light" -> Configuration.UI_MODE_NIGHT_NO
                "dark" -> Configuration.UI_MODE_NIGHT_YES
                else -> error("Unknown requested theme mode")
            }
        assertEquals(expectedMode, configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        assertEquals(scale, configuration.fontScale, 0.01f)
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
            .assertHeightIsAtLeast(64.dp)
            .performClick()
    }

    private fun exists(tag: String) =
        compose
            .onAllNodes(
                androidx.compose.ui.test
                    .hasTestTag(tag),
            ).fetchSemanticsNodes()
            .size == 1

    private fun waitFor(tag: String) {
        compose.waitUntil(20_000) { exists(tag) }
    }

    private fun text(tag: String) =
        compose
            .onNodeWithTag(tag)
            .fetchSemanticsNode()
            .config[SemanticsProperties.Text]
            .joinToString { it.text }

    private fun phase(name: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "TOKEN_SCENARIO_$name\n") })
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val expectedPackage = instrumentation.targetContext.packageName
        // Compose semantics can still exist behind Pleos navigation or a system overlay.
        // An absent native hierarchy is unknown, never proof that our screen is visible.
        compose.waitUntil(20_000) { automation.rootInActiveWindow?.packageName?.toString() == expectedPackage }
        automation.waitForIdle(500, 5_000)
        assertEquals(expectedPackage, automation.rootInActiveWindow?.packageName?.toString())
        val args = InstrumentationRegistry.getArguments()
        val prefix = "tokens-${args.getString("theme_mode")}-${args.getString("font_scale")}"
        val bitmap = checkNotNull(automation.takeScreenshot())
        val directory = File(instrumentation.targetContext.cacheDir, "screenshots").apply { mkdirs() }
        File(directory, "$prefix-$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
