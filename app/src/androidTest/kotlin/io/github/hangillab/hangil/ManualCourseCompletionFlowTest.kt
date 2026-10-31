package io.github.hangillab.hangil

import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.domain.PracticeSession
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import io.github.hangillab.hangil.feature.home.R as HomeR

/**
 * Actual Main, received SDK P/UX and production Room READS only. Final confirmation is never
 * clicked and no declaration/session/service/write/recovery request is made. The runner owns
 * full physical SQLite/settings preservation and DDS recovery. The separate UUID-owned Room
 * test proves saving; this flow proves production read/consent cancellation and actual gating.
 * Run separately at font1.0/light and font1.3/dark. Raw evidence remains in private app cache.
 */
@RunWith(AndroidJUnit4::class)
class ManualCourseCompletionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val h by lazy { SessionHistoryRecordingRunFlowTest.Harness(compose) }
    private val hashes = linkedMapOf<File, String>()
    private val evidence by lazy {
        File(h.instrument.targetContext.cacheDir, "screenshots/manual-course-completion-${UUID.randomUUID()}").apply { check(mkdirs()) }
    }

    @Test fun actualConfirmationCancellationAndDrivingLockPreserveAllOriginalsAndZeroDeclarations() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("manualCourseCompletionFlow") == "true")
        var primary: Throwable? = null
        try {
            h.assertEnvironment()
            runBlocking {
                val before = h.snapshot(h.graph)
                assertDeclarationsAbsent(before.sessions)
                assertBaseline(before)
                val ended = before.sessions.filterNot { it.isActive }
                val source = ended.first()
                val environment = h.environmentJson()
                val catalog = h.catalogJson(h.graph)
                val sdkObserver =
                    launch {
                        combine(h.graph.vehicle().state, h.graph.restrictions().restrictions) { vehicle, restrictions ->
                            vehicle to restrictions
                        }.collect { }
                    }
                var bodyFailure: Throwable? = null
                try {
                    h.awaitAllowed()
                    h.touch("home_history")
                    h.assertSource(source, 0, before.sessions.count { !it.isActive })
                    h.assertCollapsed("history_course_completion")
                    openAndCancel(source)
                    // Production deletion preparation is read-only. Never click either final
                    // declaration confirm or delete confirm on an original session.
                    h.touch("history_delete")
                    h.waitFor("history_delete_dialog")
                    h.assertText("history_delete_course", source.courseTitle)
                    h.assertText("history_delete_task", source.taskTitle)
                    h.assertText("history_delete_completion_status", h.text(HomeR.string.history_delete_completion_absent))
                    compose.onNodeWithTag("history_delete_completion_status", useUnmergedTree = true).performScrollTo().assertIsDisplayed()
                    capture("deletion-confirmation-readonly", diagnostic = false)
                    dialogTouch("history_delete_cancel")
                    compose.waitUntil(20_000) { h.nodes("history_delete_dialog").isEmpty() }
                    h.waitFor("history_course_completion_absent")
                    h.assertSource(source, 0, ended.size)
                    h.touch("history_course_completion_request")
                    confirmation(source)
                    dialogTouch("history_course_completion_cancel")
                    compose.waitUntil(20_000) { h.nodes("history_course_completion_dialog").isEmpty() }
                    h.waitFor("history_course_completion_absent")
                    h.assertPreserved(h.graph, before)
                    assertDeclarationsAbsent(before.sessions)
                    assertHashes()
                    h.touch("history_course_completion")
                    h.assertCollapsed("history_course_completion")
                    if (ended.size > 1) {
                        h.touch("history_older")
                        h.assertSource(ended[1], 1, ended.size)
                        h.assertCollapsed("history_course_completion")
                        openAndCancel(ended[1])
                        h.touch("history_course_completion")
                        h.touch("history_newer")
                        h.assertSource(source, 0, ended.size)
                        h.assertCollapsed("history_course_completion")
                    }
                    compose.activityRule.scenario.recreate()
                    h.assertEnvironment()
                    h.awaitAllowed()
                    h.assertSource(source, 0, before.sessions.count { !it.isActive })
                    h.assertCollapsed("history_course_completion")
                    openAndCancel(source)
                    h.touch("history_course_completion")
                    h.assertCollapsed("history_course_completion")
                    h.touch("history_course_completion")
                    h.waitFor("history_course_completion_absent")
                    h.touch("history_course_completion_request")
                    confirmation(source)
                    phase("MANUAL_COURSE_COMPLETION_AWAIT_D")
                    h.awaitDrive()
                    h.waitFor("history_locked")
                    compose.waitUntil(20_000) {
                        h.nodes("history_course_completion_dialog").isEmpty() &&
                            h.nodes("history_course_completion_content").isEmpty()
                    }
                    capture("driving-locked", diagnostic = false)
                    h.assertPreserved(h.graph, before)
                    assertDeclarationsAbsent(before.sessions)
                    phase("MANUAL_COURSE_COMPLETION_AWAIT_P")
                    h.awaitAllowed()
                    h.assertSource(source, 0, before.sessions.count { !it.isActive })
                    h.assertCollapsed("history_course_completion")
                    assertTrue("P recovery must never revive old consent", h.nodes("history_course_completion_dialog").isEmpty())
                    openAndCancel(source)
                    h.closeHistory()
                } catch (failure: Throwable) {
                    bodyFailure = failure
                    captureFailure(failure)
                    throw failure
                } finally {
                    var first: Throwable? = null

                    fun preserve(problem: Throwable) {
                        if (first == null) {
                            first = problem
                        } else if (first !== problem) {
                            first!!.addSuppressed(problem)
                        }
                        bodyFailure?.let { if (it !== problem) it.addSuppressed(problem) }
                    }
                    withContext(NonCancellable) {
                        try {
                            if (h.nodes("history_delete_cancel").size == 1) {
                                if (h.nodes("history_delete_dialog").size == 1) {
                                    dialogTouch("history_delete_cancel")
                                } else {
                                    h.touch("history_delete_cancel")
                                }
                            }
                        } catch (cleanup: Throwable) {
                            preserve(cleanup)
                        }
                        try {
                            if (h.nodes("history_course_completion_cancel").size == 1) dialogTouch("history_course_completion_cancel")
                        } catch (cleanup: Throwable) {
                            preserve(cleanup)
                        }
                        try {
                            if (h.nodes("history_close").size == 1) h.closeHistory()
                        } catch (cleanup: Throwable) {
                            preserve(cleanup)
                        }
                        try {
                            h.assertPreserved(h.graph, before)
                        } catch (cleanup: Throwable) {
                            preserve(cleanup)
                        }
                        try {
                            assertDeclarationsAbsent(before.sessions)
                        } catch (cleanup: Throwable) {
                            preserve(cleanup)
                        }
                        try {
                            assertHashes()
                        } catch (cleanup: Throwable) {
                            preserve(cleanup)
                        }
                        try {
                            h.requireSameJson(environment, h.environmentJson(), "same-run-environment")
                        } catch (cleanup: Throwable) {
                            preserve(cleanup)
                        }
                        try {
                            h.requireSameJson(catalog, h.catalogJson(h.graph), "catalog")
                        } catch (cleanup: Throwable) {
                            preserve(cleanup)
                        }
                        try {
                            sdkObserver.cancelAndJoin()
                        } catch (cleanup: Throwable) {
                            preserve(cleanup)
                        }
                    }
                    if (bodyFailure == null) first?.let { throw it }
                }
            }
        } catch (failure: Throwable) {
            primary = failure
            captureFailure(failure)
            throw failure
        } finally {
            try {
                h.restoreAutomationFlag()
            } catch (cleanup: Throwable) {
                if (primary == null) throw cleanup else primary.addSuppressed(cleanup)
            }
        }
        phase("MANUAL_COURSE_COMPLETION_COMPLETED")
    }

    private fun openAndCancel(source: PracticeSession) {
        h.touch("history_course_completion")
        h.waitFor("history_course_completion_absent")
        for (tag in listOf(
            "history_course_completion_source",
            "history_course_completion_absent",
            "history_course_completion_scope",
            "history_course_completion_request",
        )) {
            h.scrollVisible(tag)
            h.assertTextFits(tag)
        }
        h.assertText("history_course_completion_source", h.text(HomeR.string.course_completion_source))
        h.assertText("history_course_completion_scope", h.text(HomeR.string.course_completion_scope))
        h.touch("history_course_completion_request")
        confirmation(source)
        capture("confirmation-${UUID.randomUUID()}", diagnostic = false)
        dialogTouch("history_course_completion_cancel")
        compose.waitUntil(20_000) { h.nodes("history_course_completion_dialog").isEmpty() }
        h.waitFor("history_course_completion_absent")
    }

    private fun confirmation(source: PracticeSession) {
        h.waitFor("history_course_completion_dialog")
        val expected =
            linkedMapOf(
                "history_course_completion_confirm_goal" to source.goalTitle,
                "history_course_completion_confirm_course" to source.courseTitle,
                "history_course_completion_confirm_task" to source.taskTitle,
                "history_course_completion_confirm_demo" to h.text(HomeR.string.demo_course),
                "history_course_completion_confirm_finished" to
                    h.text(
                        HomeR.string.session_finished_at,
                        h.displayTime(checkNotNull(source.endedAtEpochMillis)),
                    ),
                "history_course_completion_confirm_scope" to h.text(HomeR.string.course_completion_confirm_scope),
            )
        expected.forEach { (tag, value) ->
            h.requireForeground()
            compose.onNodeWithTag(tag, useUnmergedTree = true).performScrollTo().assertIsDisplayed()
            h.assertText(tag, value)
            h.assertTextFits(tag)
            // Layout checks cannot prove glyph appearance. Preserve actual upper/middle/lower
            // dialog pixels privately for the external runner's visual review in both themes.
            if (tag in
                listOf(
                    "history_course_completion_confirm_course",
                    "history_course_completion_confirm_task",
                    "history_course_completion_confirm_scope",
                )
            ) {
                capture("$tag-${UUID.randomUUID()}", diagnostic = false)
            }
        }
        for (tag in listOf("history_course_completion_confirm", "history_course_completion_cancel")) {
            compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
            h.assertTextFits(tag)
        }
        val shown = h.nodes("history_course_completion_dialog").flatMap(h::nodeTexts).joinToString(" ")
        assertFalse("source identity must stay private", shown.contains(source.sessionId))
        h.requireForeground()
    }

    private fun dialogTouch(tag: String) {
        h.requireForeground()
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed().performClick()
        compose.waitForIdle()
    }

    private suspend fun assertDeclarationsAbsent(sessions: List<PracticeSession>) =
        withTimeout(30_000) {
            for (source in sessions) {
                assertEquals(
                    "actual absence is required for every original source",
                    null,
                    h.graph
                        .courseCompletions()
                        .observe(source.sessionId)
                        .first(),
                )
            }
            // Constant read-only SQL also covers a row not reached by a valid source enumeration.
            val file = h.instrument.targetContext.getDatabasePath("hangil.db")
            check(file.isFile)
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
                sqlite.rawQuery("SELECT COUNT(*) FROM course_completion_declaration", null).use { cursor ->
                    check(cursor.moveToFirst())
                    assertEquals("production declarations must remain zero", 0L, cursor.getLong(0))
                }
            }
        }

    private fun assertBaseline(before: SessionHistoryRecordingRunFlowTest.Harness.ExistingData) {
        check(before.sessions.none { it.isActive })
        val source = before.sessions.first { !it.isActive }
        var product: String? = null
        var harness: String? = null
        for ((prefix, series) in listOf(
            "history-opening-manual-course-completion-entry-v1" to "manual-course-completion-entry-v1",
            "history-disclosure-manual-course-completion-tasks-v1" to "manual-course-completion-tasks-v1",
        )) {
            val file = File(h.instrument.targetContext.cacheDir, "measurements/$prefix-baseline.json")
            check(file.isFile && !File(file.path + ".bak").exists() && !File(file.path + ".new").exists())
            val baseline = JSONObject(file.readText())
            check(
                baseline.getInt("schema") == 1 && baseline.getString("measurementStage") == "baseline" &&
                    baseline.getString("measurementSeries") == series && baseline.getString("status") == "completed" &&
                    baseline.getBoolean("preservedOriginalData") && baseline.getBoolean("returnedHome") &&
                    baseline.getInt("excludedSamples") == 0,
            )
            val declaredProduct = baseline.getString("declaredProductCommit")
            val declaredHarness = baseline.getString("declaredHarnessRevision")
            check(declaredProduct.matches(Regex("[0-9a-f]{40}")) && declaredHarness.matches(Regex("[0-9a-f]{40}")))
            check(product == null || product == declaredProduct)
            check(harness == null || harness == declaredHarness)
            product = declaredProduct
            harness = declaredHarness
            check(baseline.getInt("plannedWarmups") == 5 && baseline.getInt("plannedSamples") == 30)
            for ((key, count) in listOf("warmups" to 5, "samples" to 30)) {
                val samples = baseline.getJSONArray(key)
                check(samples.length() == count && (0 until count).all { samples.getJSONObject(it).getString("status") == "success" })
            }
            h.requireSameJson(baseline.getJSONObject("originalData"), h.dataJson(before), "originalData")
            h.requireSameJson(baseline.getJSONObject("fixedSource"), h.sessionJson(source), "fixedSource")
            h.requireSameJson(baseline.getJSONObject("catalog"), h.catalogJson(h.graph), "catalog")
            val expected = baseline.getJSONObject("environment")
            val actual = h.environmentJson()
            // Correctness allows only the explicitly requested font/theme difference from light baseline.
            for (key in listOf(
                "sdkInt",
                "release",
                "buildFingerprint",
                "abis",
                "screenWidthDp",
                "screenHeightDp",
                "densityDpi",
                "locale",
                "timeZone",
                "debuggable",
                "productPackage",
            )) {
                h.requireSameJson(expected.get(key), actual.get(key), "environment.$key")
            }
            hashes[file] = h.sha256(file)
        }
    }

    private fun assertHashes() = hashes.forEach { (file, hash) -> check(h.sha256(file) == hash) }

    private fun phase(value: String) = h.instrument.sendStatus(2, Bundle().apply { putString("stream", value + System.lineSeparator()) })

    private fun captureFailure(original: Throwable) {
        try {
            capture("failure-${UUID.randomUUID()}", diagnostic = true)
        } catch (failure: Throwable) {
            original.addSuppressed(failure)
        }
    }

    private fun capture(
        name: String,
        diagnostic: Boolean,
    ) {
        var first: Throwable? = null

        fun preserve(failure: Throwable) {
            if (first == null) first = failure else first!!.addSuppressed(failure)
        }
        var mainState: String? = null
        var displayId: Int? = null
        var valid = false
        try {
            h.instrument.runOnMainSync {
                mainState = compose.activity.lifecycle.currentState.name
                val display = compose.activity.display
                valid = display != null && display.isValid
                displayId = display?.displayId
            }
        } catch (failure: Throwable) {
            preserve(failure)
        }
        var actualPackage: String? = null
        try {
            actualPackage = h.actualPackage()
        } catch (failure: Throwable) {
            preserve(failure)
        }
        val confirmed = valid && mainState == Lifecycle.State.RESUMED.name && actualPackage == h.instrument.targetContext.packageName
        val proof =
            JSONObject()
                .put("kind", "manual-course-completion-production-read-only")
                .put("main_state", mainState ?: JSONObject.NULL)
                .put("main_display_known", displayId != null)
                .put("main_display_valid", valid)
                .put("actual_package", actualPackage ?: JSONObject.NULL)
                .put("foreground_basis", "single-focused-window-on-actual-Main-display")
                .put("foreground_confirmed", confirmed)
                .put("scope", if (diagnostic) "raw-default-display-diagnostic-not-app-only" else "confirmed-focused-app-window")
                .put("png_saved", false)
        try {
            File(evidence, "$name.json").writeText(proof.toString(2))
        } catch (failure: Throwable) {
            preserve(failure)
        }
        // Failure PNG is independent of failed lifecycle/window probes and never labeled app-only.
        try {
            if (!diagnostic) {
                h.requireForeground()
                check(confirmed && displayId == Display.DEFAULT_DISPLAY)
            }
            val image = checkNotNull(h.instrument.uiAutomation.takeScreenshot())
            try {
                val crop = if (diagnostic) null else focusedBounds(checkNotNull(displayId))
                val output =
                    if (crop == null) {
                        image
                    } else {
                        check(crop.left >= 0 && crop.top >= 0 && crop.right <= image.width && crop.bottom <= image.height)
                        Bitmap.createBitmap(image, crop.left, crop.top, crop.width(), crop.height())
                    }
                try {
                    File(evidence, "$name.png").outputStream().use { check(output.compress(Bitmap.CompressFormat.PNG, 100, it)) }
                } finally {
                    if (output !== image) output.recycle()
                }
                proof.put("png_saved", true)
            } finally {
                image.recycle()
            }
        } catch (failure: Throwable) {
            proof.put("png_failure", failure.javaClass.name)
            preserve(failure)
        }
        try {
            File(evidence, "$name.json").writeText(proof.toString(2))
        } catch (failure: Throwable) {
            preserve(failure)
        }
        first?.let { throw it }
    }

    private fun focusedBounds(display: Int): Rect {
        val all = h.instrument.uiAutomation.windowsOnAllDisplays
        val recycled = mutableListOf<AccessibilityWindowInfo>()
        var root: AccessibilityNodeInfo? = null
        try {
            for (index in 0 until all.size()) recycled.addAll(all.valueAt(index))
            val windows = checkNotNull(all[display])
            check(windows.all { it.displayId == display })
            val focused = windows.filter { it.isFocused }.single()
            root = checkNotNull(focused.root)
            check(root?.packageName?.toString() == h.instrument.targetContext.packageName)
            return Rect().also {
                focused.getBoundsInScreen(it)
                check(it.width() > 0 && it.height() > 0)
            }
        } finally {
            root?.recycle()
            recycled.forEach { it.recycle() }
        }
    }
}
