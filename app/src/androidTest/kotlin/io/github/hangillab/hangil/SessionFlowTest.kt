package io.github.hangillab.hangil

import android.graphics.Bitmap
import android.os.Bundle
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
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

/** Run start, P-D-P, then process-restarted finish on the dedicated AVD with saved preparation. */
@RunWith(AndroidJUnit4::class)
class SessionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun startAndRestoreWhileParked() {
        waitFor("session_start")
        val course = text("saved_course")
        compose.onNodeWithTag("session_start").performScrollTo().performClick()
        waitFor("session_finish")
        compose.onNodeWithTag("session_course").assertTextContains(course)
        ensureRecordGuideExpanded()
        val startedAt = text("session_started_at")
        capture("session-started")
        compose.activityRule.scenario.recreate()
        waitFor("session_finish")
        compose.onNodeWithTag("session_course").assertTextContains(course)
        ensureRecordGuideExpanded()
        compose.onNodeWithTag("session_started_at").assertTextContains(startedAt)
        compose.onNodeWithTag("session_finish").performScrollTo()
        capture("session-recreated")
    }

    @Test fun activeSessionLocksWhileDrivingAndReturnsAfterPark() {
        waitFor("session_finish")
        ensureRecordGuideExpanded()
        val startedAt = text("session_started_at")
        compose.onNodeWithTag("session_finish").performScrollTo()
        phase("AWAIT_D")
        compose.waitUntil(90_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("session_finish"),
                ).fetchSemanticsNodes()
                .isEmpty() &&
                compose
                    .onAllNodes(
                        androidx.compose.ui.test
                            .hasText("주차 후 이어서 준비해요"),
                    ).fetchSemanticsNodes()
                    .isNotEmpty()
        }
        compose.onNodeWithTag("session_course").assertDoesNotExist()
        compose.onNodeWithTag("session_start").assertDoesNotExist()
        compose.onNodeWithTag("session_finish").assertDoesNotExist()
        compose.onNodeWithTag("home_locked_title").assertIsDisplayed()
        capture("session-driving-locked")
        phase("AWAIT_P")
        waitFor("session_finish")
        ensureRecordGuideExpanded()
        compose.onNodeWithTag("session_started_at").assertTextContains(startedAt)
        compose.onNodeWithTag("session_finish").performScrollTo()
        capture("session-parked-again")
    }

    @Test fun finishAfterProcessRestartAndRestoreCompletedRecord() {
        waitFor("session_finish")
        val course = text("session_course")
        capture("session-process-restored")
        compose.onNodeWithTag("session_finish").performScrollTo().performClick()
        waitFor("session_latest_finished")
        compose.onNodeWithTag("session_latest_course").assertTextContains(course)
        compose.onNodeWithTag("session_latest_finished").performScrollTo()
        capture("session-finished")
        compose.activityRule.scenario.recreate()
        waitFor("session_latest_finished")
        compose.onNodeWithTag("session_active").assertDoesNotExist()
        compose.onNodeWithTag("session_latest_course").assertTextContains(course)
        compose.onNodeWithTag("session_latest_finished").performScrollTo()
        capture("session-finished-restored")
    }

    private fun ensureRecordGuideExpanded() {
        if (compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag("session_started_at"),
                ).fetchSemanticsNodes()
                .isEmpty()
        ) {
            compose
                .onNodeWithTag("home_record_guide")
                .performScrollTo()
                .assertIsDisplayed()
                .performClick()
        }
        compose.onNodeWithTag("session_started_at").performScrollTo().assertIsDisplayed()
    }

    private fun text(tag: String): String =
        compose.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsProperties.Text].joinToString {
            it.text
        }

    private fun waitFor(tag: String) {
        compose.waitUntil(20_000) {
            compose
                .onAllNodes(
                    androidx.compose.ui.test
                        .hasTestTag(tag),
                ).fetchSemanticsNodes()
                .size == 1
        }
    }

    private fun phase(name: String) {
        InstrumentationRegistry.getInstrumentation().sendStatus(2, Bundle().apply { putString("stream", "SESSION_SCENARIO_$name\n") })
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val bitmap = checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }
}
