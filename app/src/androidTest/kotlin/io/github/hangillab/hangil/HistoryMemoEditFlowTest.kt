package io.github.hangillab.hangil

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Bundle
import android.view.Display
import android.view.KeyEvent
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.SessionMemo
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale
import java.util.UUID
import io.github.hangillab.hangil.feature.home.R as HomeR

/**
 * Actual Main/SDK/Room and actual Android key-event input with visible system IME. Only edit,
 * cancel, source navigation and recreation are exercised. Neither save nor delete is clicked,
 * no writer is invoked directly, and every original field/type/order/sequence remains frozen.
 * Captured stale save callback races are proved separately by controlled/UUID-owned tests.
 */
@RunWith(AndroidJUnit4::class)
class HistoryMemoEditFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val h by lazy { SessionHistoryRecordingRunFlowTest.Harness(compose) }
    private val evidence by lazy {
        File(h.instrument.targetContext.cacheDir, "screenshots/history-memo-edit-actual-${UUID.randomUUID()}").apply { check(mkdirs()) }
    }
    private val baselineFile by lazy {
        File(h.instrument.targetContext.cacheDir, "measurements/history-memo-read-history-memo-edit-read-v1-baseline.json")
    }
    private var baselineHash: String? = null

    @Test fun actualOlderMemoImeCancellationSourceRecreationAndDrivingLockPreserveEveryOriginal() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("manualHistoryMemoEditFlow") == "true")
        var primary: Throwable? = null
        try {
            h.assertEnvironment()
            runBlocking {
                val before = h.snapshot(h.graph)
                val physical = physicalDatabase()
                val ended = before.sessions.filterNot { it.isActive }
                check(before.sessions.none { it.isActive })
                val rows = ended.filter { before.memos.getValue(it.sessionId) != null }
                check(rows.size == 1)
                val source = rows.single()
                val memo = checkNotNull(before.memos.getValue(source.sessionId))
                val index = ended.indexOf(source)
                check(index > 0)
                assertBaseline(before, source, memo, index, physical)
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
                    h.assertSource(ended.first(), 0, ended.size)
                    for (position in 1..index) {
                        h.touch("history_older")
                        h.assertSource(ended[position], position, ended.size)
                    }
                    h.assertCollapsed("history_memo")
                    h.touch("history_memo")
                    h.assertText("history_memo_text", memo.text)
                    beginEditWithIme(memo.text)
                    typeUsingAndroidKeys("CANCELLEDNATIVEMEMO")
                    assertVisibleText("history_memo_edit_context")
                    assertVisibleText("history_memo_save")
                    assertVisibleText("history_memo_cancel_edit")
                    check(imeVisible() == true)
                    capture("actual-edit-ime", false)
                    h.touch("history_memo_cancel_edit")
                    awaitIme(false)
                    h.assertText("history_memo_text", memo.text)
                    assertOriginals(before, physical)
                    h.touch("history_newer")
                    h.assertSource(ended[index - 1], index - 1, ended.size)
                    h.assertCollapsed("history_memo")
                    assertNoEditor()
                    h.touch("history_older")
                    h.assertSource(source, index, ended.size)
                    h.touch("history_memo")
                    h.assertText("history_memo_text", memo.text)
                    compose.activityRule.scenario.recreate()
                    h.assertEnvironment()
                    h.awaitAllowed()
                    h.assertSource(source, index, ended.size)
                    h.assertCollapsed("history_memo")
                    assertNoEditor()
                    h.touch("history_memo")
                    h.assertText("history_memo_text", memo.text)
                    beginEditWithIme(memo.text)
                    typeUsingAndroidKeys("DISCARDEDDRIVINGDRAFT")
                    capture("actual-edit-before-driving", false)
                    phase("HISTORY_MEMO_EDIT_AWAIT_D")
                    h.awaitDrive()
                    h.waitFor("history_locked")
                    assertNoEditor()
                    awaitIme(false)
                    capture("actual-driving-locked", false)
                    assertOriginals(before, physical)
                    phase("HISTORY_MEMO_EDIT_AWAIT_P")
                    h.awaitAllowed()
                    h.assertSource(source, index, ended.size)
                    h.assertCollapsed("history_memo")
                    assertNoEditor()
                    h.touch("history_memo")
                    h.assertText("history_memo_text", memo.text)
                    beginEditWithIme(memo.text)
                    capture("actual-fresh-editor-original", false)
                    h.touch("history_memo_cancel_edit")
                    awaitIme(false)
                    h.assertText("history_memo_text", memo.text)
                    assertOriginals(before, physical)
                    h.closeHistory()
                } catch (failure: Throwable) {
                    bodyFailure = failure
                    captureFailure(failure)
                    throw failure
                } finally {
                    var first: Throwable? = null

                    fun preserve(failure: Throwable) {
                        if (first == null) {
                            first = failure
                        } else if (first !== failure) {
                            first!!.addSuppressed(failure)
                        }
                        bodyFailure?.let { if (it !== failure) it.addSuppressed(failure) }
                    }
                    withContext(NonCancellable) {
                        try {
                            if (h.nodes("history_memo_cancel_edit").size == 1) h.touch("history_memo_cancel_edit")
                        } catch (
                            failure: Throwable,
                        ) {
                            preserve(failure)
                        }
                        try {
                            if (h.nodes("history_close").size == 1) h.closeHistory()
                        } catch (failure: Throwable) {
                            preserve(failure)
                        }
                        try {
                            assertOriginals(before, physical)
                        } catch (failure: Throwable) {
                            preserve(failure)
                        }
                        try {
                            h.requireSameJson(environment, h.environmentJson(), "environment")
                        } catch (
                            failure: Throwable,
                        ) {
                            preserve(failure)
                        }
                        try {
                            h.requireSameJson(catalog, h.catalogJson(h.graph), "catalog")
                        } catch (
                            failure: Throwable,
                        ) {
                            preserve(failure)
                        }
                        try {
                            sdkObserver.cancelAndJoin()
                        } catch (failure: Throwable) {
                            preserve(failure)
                        }
                    }
                    if (bodyFailure == null) first?.let { throw it }
                }
            }
        } catch (
            failure: Throwable,
        ) {
            primary = failure
            captureFailure(failure)
            throw failure
        } finally {
            try {
                h.restoreAutomationFlag()
            } catch (
                failure: Throwable,
            ) {
                if (primary == null) {
                    throw failure
                } else if (primary !== failure) {
                    primary.addSuppressed(failure)
                }
            }
        }
        phase("HISTORY_MEMO_EDIT_FLOW_COMPLETED")
    }

    private fun beginEditWithIme(original: String) {
        h.touch("history_memo_edit")
        h.waitFor("history_memo_textfield")
        assertDraft(original)
        h.touch("history_memo_textfield")
        awaitIme(true)
        assertDraft(original)
    }

    private fun typeUsingAndroidKeys(text: String) {
        h.requireForeground()
        check(imeVisible() == true)
        // Real Android key dispatch to the focused editor. No test-only SetText or direct VM edit.
        h.instrument.sendKeySync(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON))
        h.instrument.sendKeySync(KeyEvent(0, 0, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_A, 0, KeyEvent.META_CTRL_ON))
        h.requireForeground()
        check(imeVisible() == true)
        h.instrument.sendStringSync(text)
        compose.waitForIdle()
        assertDraft(text)
        check(imeVisible() == true)
        h.requireForeground()
    }

    private fun descendants(node: SemanticsNode): List<SemanticsNode> = listOf(node) + node.children.flatMap(::descendants)

    private fun assertDraft(expected: String) {
        h.waitFor("history_memo_textfield")
        compose.waitUntil(20_000) {
            descendants(h.nodes("history_memo_textfield").single()).any {
                it.config.getOrNull(SemanticsProperties.EditableText)?.text == expected
            }
        }
    }

    private fun imeVisible(): Boolean? {
        var actual: Boolean? = null
        h.instrument.runOnMainSync {
            actual =
                compose.activity.window.decorView.rootWindowInsets
                    ?.isVisible(WindowInsets.Type.ime())
        }
        return actual
    }

    private fun awaitIme(visible: Boolean) = compose.waitUntil(20_000) { imeVisible() == visible }

    private fun assertNoEditor() {
        compose.waitUntil(20_000) {
            listOf(
                "history_memo_textfield",
                "history_memo_save",
                "history_memo_cancel_edit",
                "history_memo_writing",
            ).all { h.nodes(it).isEmpty() }
        }
    }

    private fun assertVisibleText(tag: String) {
        h.requireForeground()
        h.scrollVisible(tag)
        val node = h.nodes(tag).single()
        val available = Rect()
        h.instrument.runOnMainSync {
            val activity = compose.activity
            activity.window.decorView.getWindowVisibleDisplayFrame(available)
            val insets = checkNotNull(activity.window.decorView.rootWindowInsets)
            if (insets.isVisible(WindowInsets.Type.ime())) {
                val bottom = insets.getInsets(WindowInsets.Type.ime()).bottom
                check(bottom > 0)
                available.bottom = minOf(available.bottom, activity.windowManager.currentWindowMetrics.bounds.bottom - bottom)
            }
        }
        val bounds = node.boundsInWindow
        val origin = node.positionOnScreen - node.positionInWindow
        check(
            bounds.left + origin.x >= available.left - 1 && bounds.top + origin.y >= available.top - 1 &&
                bounds.right + origin.x <= available.right + 1 && bounds.bottom + origin.y <= available.bottom + 1,
        )
        val layouts = mutableListOf<TextLayoutResult>()
        compose.runOnIdle {
            descendants(h.nodes(tag).single()).forEach {
                it.config
                    .getOrNull(SemanticsActions.GetTextLayoutResult)
                    ?.action
                    ?.invoke(layouts)
            }
        }
        check(layouts.isNotEmpty() && layouts.all(::textGeometryFits))
    }

    private fun textGeometryFits(layout: TextLayoutResult): Boolean {
        val text = layout.layoutInput.text.text
        if (text.isEmpty() || layout.size.width <= 0 || layout.size.height <= 0 || layout.lineCount <= 0 ||
            layout.multiParagraph.didExceedMaxLines
        ) {
            return false
        }

        fun inside(
            l: Float,
            t: Float,
            r: Float,
            b: Float,
        ): Boolean =
            l.isFinite() && t.isFinite() && r.isFinite() && b.isFinite() &&
                l <= r && t <= b && l >= -1 && t >= -1 && r <= layout.size.width + 1 && b <= layout.size.height + 1
        var covered = 0
        for (line in 0 until layout.lineCount) {
            val end = layout.getLineEnd(line, false)
            if (layout.getLineStart(line) != covered || end < covered || end > text.length || layout.isLineEllipsized(line) ||
                !inside(layout.getLineLeft(line), layout.getLineTop(line), layout.getLineRight(line), layout.getLineBottom(line))
            ) {
                return false
            }
            covered = end
        }
        return covered == text.length &&
            text.indices.filterNot { text[it].isWhitespace() }.all {
                val box = layout.getBoundingBox(it)
                inside(box.left, box.top, box.right, box.bottom)
            }
    }

    private suspend fun assertOriginals(
        before: SessionHistoryRecordingRunFlowTest.Harness.ExistingData,
        physical: JSONObject,
    ) {
        h.assertPreserved(h.graph, before)
        for (source in before.sessions) {
            check(
                h.graph
                    .courseCompletions()
                    .observe(source.sessionId)
                    .first() == null,
            )
        }
        h.requireSameJson(physical, physicalDatabase(), "all original SQLite fields/types/order/sequences")
        check(baselineHash != null && h.sha256(baselineFile) == baselineHash)
        check(!File(baselineFile.path + ".bak").exists() && !File(baselineFile.path + ".new").exists())
    }

    private fun assertBaseline(
        before: SessionHistoryRecordingRunFlowTest.Harness.ExistingData,
        source: PracticeSession,
        memo: SessionMemo,
        index: Int,
        physical: JSONObject,
    ) {
        check(baselineFile.isFile && !File(baselineFile.path + ".bak").exists() && !File(baselineFile.path + ".new").exists())
        baselineHash = h.sha256(baselineFile)
        val baseline = JSONObject(baselineFile.readText())
        check(
            baseline.getString("measurementSeries") == "history-memo-edit-read-v1" &&
                baseline.getString("measurementStage") == "baseline" && baseline.getString("status") == "completed",
        )
        check(
            baseline.getBoolean("preservedOriginalData") && baseline.getBoolean("returnedHome") && baseline.getInt("excludedSamples") == 0,
        )
        for ((group, size) in listOf("warmups" to 5, "samples" to 30)) {
            val samples = baseline.getJSONArray(group)
            check(samples.length() == size && (0 until size).all { samples.getJSONObject(it).getString("status") == "success" })
        }
        h.requireSameJson(baseline.getJSONObject("fixedSource"), h.sessionJson(source), "fixedSource")
        h.requireSameJson(
            baseline.getJSONObject("fixedMemo"),
            JSONObject()
                .put("sessionId", memo.sessionId)
                .put("text", memo.text)
                .put("version", memo.version),
            "fixedMemo",
        )
        check(baseline.getInt("fixedSourceIndex") == index)
        val original = JSONObject(baseline.getJSONObject("originalData").toString())
        h.requireSameJson(original.getJSONObject("physicalDatabase"), physical, "schema6 physical originals")
        original.remove("physicalDatabase")
        original.remove("courseCompletions")
        h.requireSameJson(original, h.dataJson(before), "all original domain fields")
        h.requireSameJson(baseline.getJSONObject("catalog"), h.catalogJson(h.graph), "catalog")
        val expected = baseline.getJSONObject("environment")
        val actual = h.environmentJson()
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
    }

    private fun phase(value: String) = h.instrument.sendStatus(2, Bundle().apply { putString("stream", value + System.lineSeparator()) })

    private fun captureFailure(original: Throwable) {
        try {
            capture("failure-${UUID.randomUUID()}", true)
        } catch (failure: Throwable) {
            if (failure !==
                original
            ) {
                original.addSuppressed(failure)
            }
        }
    }

    private fun capture(
        stage: String,
        diagnostic: Boolean,
    ) {
        var first: Throwable? = null

        fun preserve(failure: Throwable) {
            if (first == null) {
                first = failure
            } else if (first !== failure) {
                first!!.addSuppressed(failure)
            }
        }
        var state: String? = null
        var display: Int? = null
        var valid = false
        try {
            h.instrument.runOnMainSync {
                state = compose.activity.lifecycle.currentState.name
                val observed = compose.activity.display
                valid = observed != null && observed.isValid
                display = observed?.displayId
            }
        } catch (failure: Throwable) {
            preserve(failure)
        }
        var actual: String? = null
        try {
            actual = h.actualPackage()
        } catch (failure: Throwable) {
            preserve(failure)
        }
        var hostVerified = false
        try {
            h.requireForeground()
            hostVerified = true
        } catch (failure: Throwable) {
            preserve(failure)
        }
        val confirmed =
            hostVerified && state == Lifecycle.State.RESUMED.name && valid &&
                actual == h.instrument.targetContext.packageName
        var observedIme: Boolean? = null
        try {
            observedIme = imeVisible()
        } catch (failure: Throwable) {
            preserve(failure)
        }
        val proof =
            JSONObject()
                .put("kind", "actual-Main-SDK-production-Room-cancel-only")
                .put("actual_sdk", true)
                .put("main_state", state ?: JSONObject.NULL)
                .put("display_known", display != null)
                .put("display_valid", valid)
                .put("actual_package", actual ?: JSONObject.NULL)
                .put("foreground_confirmed", confirmed)
                .put("ime_visible", observedIme ?: JSONObject.NULL)
                .put("png_saved", false)
                .put(
                    "capture_scope",
                    if (diagnostic) {
                        "raw-default-display-diagnostic-not-confirmed-Main"
                    } else {
                        "confirmed-Main-default-display-including-system-IME"
                    },
                )
        try {
            File(evidence, "$stage.json").writeText(proof.toString(2))
        } catch (failure: Throwable) {
            preserve(failure)
        }
        try {
            if (!diagnostic) {
                h.requireForeground()
                check(confirmed && display == Display.DEFAULT_DISPLAY && observedIme != null)
            }
            val image = checkNotNull(h.instrument.uiAutomation.takeScreenshot())
            try {
                File(evidence, "$stage.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                image.recycle()
            }
            proof.put("png_saved", true)
        } catch (failure: Throwable) {
            proof.put("png_failure", failure.javaClass.name)
            preserve(failure)
        }
        try {
            File(evidence, "$stage.json").writeText(proof.toString(2))
        } catch (failure: Throwable) {
            preserve(failure)
        }
        first?.let { throw it }
    }

    private fun physicalDatabase(): JSONObject {
        val file = h.instrument.targetContext.getDatabasePath("hangil.db")
        check(file.isFile)
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { sqlite ->
            check(sqlite.version == 6) { "original schema6 is required" }
            val tables = JSONArray()
            sqlite.rawQuery("SELECT name, sql FROM sqlite_master WHERE type='table' ORDER BY name", null).use { names ->
                while (names.moveToNext()) {
                    val name = names.getString(0)
                    val quoted = "\"" + name.replace("\"", "\"\"") + "\""
                    val rows = JSONArray()
                    val columns = JSONArray()
                    sqlite.rawQuery("SELECT rowid AS __measurement_rowid__, * FROM $quoted ORDER BY rowid", null).use { cursor ->
                        cursor.columnNames.forEach { columns.put(it) }
                        while (cursor.moveToNext()) {
                            val row = JSONArray()
                            for (column in 0 until cursor.columnCount) {
                                val value =
                                    when (cursor.getType(column)) {
                                        Cursor.FIELD_TYPE_NULL -> JSONObject().put("type", "NULL").put("value", JSONObject.NULL)
                                        Cursor.FIELD_TYPE_INTEGER ->
                                            JSONObject()
                                                .put(
                                                    "type",
                                                    "INTEGER",
                                                ).put("value", cursor.getLong(column))
                                        Cursor.FIELD_TYPE_FLOAT -> JSONObject().put("type", "FLOAT").put("value", cursor.getDouble(column))
                                        Cursor.FIELD_TYPE_STRING ->
                                            JSONObject()
                                                .put(
                                                    "type",
                                                    "STRING",
                                                ).put("value", cursor.getString(column))
                                        Cursor.FIELD_TYPE_BLOB ->
                                            JSONObject().put("type", "BLOB_HEX").put(
                                                "value",
                                                cursor.getBlob(column).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) },
                                            )
                                        else -> error("unknown actual SQLite field type")
                                    }
                                row.put(value)
                            }
                            rows.put(row)
                        }
                    }
                    tables.put(
                        JSONObject()
                            .put("table", name)
                            .put("columns", columns)
                            .put("rowsInRowIdOrder", rows)
                            .put("createSql", if (names.isNull(1)) JSONObject.NULL else names.getString(1)),
                    )
                }
            }
            sqlite.rawQuery("SELECT COUNT(*) FROM course_completion_declaration", null).use { cursor ->
                check(cursor.moveToFirst() && cursor.getLong(0) == 0L) { "original declarations must stay absent" }
            }
            sqlite.rawQuery("PRAGMA foreign_key_check", null).use { check(it.count == 0) }
            JSONObject().put("schemaVersion", sqlite.version).put("tablesInNameOrder", tables).put("foreignKeyViolations", 0)
        }
    }
}
