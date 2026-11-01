package io.github.hangillab.hangil

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.os.Bundle
import android.os.SystemClock
import android.util.AtomicFile
import android.view.Display
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.hangillab.hangil.core.domain.GearPosition
import io.github.hangillab.hangil.core.domain.NextPracticeSeed
import io.github.hangillab.hangil.core.domain.PracticeSelection
import io.github.hangillab.hangil.core.domain.PracticeSession
import io.github.hangillab.hangil.core.domain.VehicleObservationSource
import io.github.hangillab.hangil.core.domain.VehicleReading
import kotlinx.coroutines.Job
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
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.UUID
import io.github.hangillab.hangil.feature.home.R as HomeR

/**
 * Actual Hilt Main, SDK P/UX and production Room. Preparation, start and finish are real UI
 * actions; repositories are read only from this harness. This creates one owned session and
 * its SDK observations. The root runner must restore the canonical schema6 DB, sequences,
 * settings and DDS even after failure. The protected schema7 device is never a test target.
 * This is correctness/UI QA, not a performance measurement or stale-callback simulation.
 */
@RunWith(AndroidJUnit4::class)
class FinishedSessionReviewFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val h by lazy { SessionHistoryRecordingRunFlowTest.Harness(compose) }
    private val directory by lazy {
        File(h.instrument.targetContext.cacheDir, "screenshots/finished-source-review-${UUID.randomUUID()}").apply { check(mkdirs()) }
    }
    private val report = JSONObject().put("kind", "actual-Main-SDK-UI-finish-explicit-review").put("status", "started")
    private var actualHost: MainActivity? = null
    private var actualDisplay: Int? = null
    private var owned: PracticeSession? = null
    private var expectedPreparation: PracticeSelection? = null
    private var baselineHash: String? = null
    private val baselineFile by lazy {
        File(h.instrument.targetContext.cacheDir, "measurements/history-memo-read-history-memo-edit-read-v1-baseline.json")
    }

    @Test fun actualUiFinishExplicitExactReviewAndDrivingLockPreserveOriginals() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("manualFinishedSessionReviewFlow") == "true")
        var primary: Throwable? = null
        var sdkObserver: Job? = null
        var before: SessionHistoryRecordingRunFlowTest.Harness.ExistingData? = null
        var physical: JSONObject? = null
        var environment: JSONObject? = null
        var catalog: JSONObject? = null
        try {
            h.assertEnvironment()
            captureHostReceipt()
            runBlocking {
                val original = h.snapshot(h.graph)
                before = original
                check(original.sessions.none { it.isActive } && original.preparation != null)
                expectedPreparation = original.preparation
                val originalPhysical = physicalDatabase()
                physical = originalPhysical
                assertBaseline(original, originalPhysical)
                environment = h.environmentJson()
                catalog = h.catalogJson(h.graph)
                report
                    .put("originalData", h.dataJson(original))
                    .put("physicalBefore", originalPhysical)
                    .put("environment", environment)
                    .put("catalog", catalog)
                    .put("rootCanonicalAndSequenceRestoreRequired", true)
                    .put("nativeRepositoryWrites", 0)
                persist()
                sdkObserver =
                    launch {
                        combine(h.graph.vehicle().state, h.graph.restrictions().restrictions) { v, r -> v to r }.collect { }
                    }
                h.awaitAllowed()
                requireHost()
                prepareThroughUiIfRequired(original)
                report.put("uiStartRequested", true)
                persist()
                touch("session_start")
                val started =
                    withTimeout(20_000) {
                        h.graph
                            .sessions()
                            .observeSessions()
                            .first { rows ->
                                rows.count { it.isActive } == 1 &&
                                    rows.count { it.sessionId !in original.sessions.map { it.sessionId } } == 1
                            }.single { it.isActive }
                    }
                check(started.sessionId !in original.sessions.map { it.sessionId })
                check(started.selection == expectedPreparation)
                owned = started
                report.put("ownedActiveSource", h.sessionJson(started)).put("status", "owned-active")
                persist()
                withTimeout(20_000) {
                    h.graph.observations().observe(started.sessionId).first { rows ->
                        rows.any {
                            it.observation.source == VehicleObservationSource.SDK_STATE_OBSERVATION &&
                                it.observation.state.gear == VehicleReading.Available(GearPosition.PARK)
                        }
                    }
                }
                assertOriginals(original, originalPhysical)
                check(h.nodes("history_ready").isEmpty())
                touch("session_finish")
                val ended =
                    withTimeout(20_000) {
                        h.graph
                            .sessions()
                            .observeSessions()
                            .first { rows ->
                                rows.singleOrNull { it.sessionId == started.sessionId }?.isActive == false
                            }.single { it.sessionId == started.sessionId }
                    }
                check(ended == started.copy(endedAtEpochMillis = checkNotNull(ended.endedAtEpochMillis)))
                owned = ended
                report.put("ownedEndedSource", h.sessionJson(ended)).put("status", "owned-ended")
                persist()
                requireHost()
                h.waitFor("session_finish_review")
                check(h.nodes("history_ready").isEmpty()) { "finish must not navigate automatically" }
                visibleText("session_finish_review")
                capture("explicit-review-action", false)
                touch("session_finish_review")
                assertExact(ended)
                assertCold()
                visibleText("history_course")
                visibleText("history_task")
                visibleText("history_finished")
                capture("exact-review", false)
                compose.activityRule.scenario.recreate()
                h.assertEnvironment()
                captureHostReceipt()
                h.awaitAllowed()
                assertExact(ended)
                assertCold()
                touch("history_observations")
                h.waitFor("review_ready")
                h.assertText("review_source", h.text(HomeR.string.review_source))
                visibleText("review_source")
                capture("stored-actual-SDK-observation", false)
                phase("FINISH_SOURCE_REVIEW_AWAIT_D")
                h.awaitDrive()
                requireHost()
                h.waitFor("history_locked")
                compose.waitUntil(20_000) {
                    listOf("history_ready", "history_observation_content", "history_memo_content").all { h.nodes(it).isEmpty() }
                }
                check(!h.liveAllowed())
                capture("actual-D-locked", false)
                assertOriginals(original, originalPhysical)
                phase("FINISH_SOURCE_REVIEW_AWAIT_P")
                h.awaitAllowed()
                requireHost()
                assertExact(ended)
                assertCold()
                check(h.nodes("review_ready").isEmpty()) { "D/P recovery must not revive an old detail disclosure" }
                touch("history_observations")
                h.waitFor("review_ready")
                h.assertText("review_source", h.text(HomeR.string.review_source))
                capture("fresh-P-exact-review", false)
                touch("history_close")
                h.waitFor("home_history")
                check(h.nodes("history_ready").isEmpty() && h.nodes("session_finish_review").isEmpty())
                requireHost()
                assertOriginals(original, originalPhysical)
                report
                    .put("status", "body-complete")
                    .put("actualUiStart", true)
                    .put("actualUiFinish", true)
                    .put("explicitSameEndedSourceReview", true)
                    .put("retainedAcrossRecreation", true)
                    .put("actualSDKDrivingLockAndFreshParkReopen", true)
                persist()
                sdkObserver?.cancelAndJoin()
                sdkObserver = null
            }
        } catch (failure: Throwable) {
            primary = failure
            report.put("status", "failed").put("failureClass", failure.javaClass.name)
            try {
                persist()
            } catch (diagnostic: Throwable) {
                failure.addSuppressed(diagnostic)
            }
            try {
                capture("failure-diagnostic-unverified", true)
            } catch (diagnostic: Throwable) {
                failure.addSuppressed(diagnostic)
            }
            throw failure
        } finally {
            var first: Throwable? = null

            fun attempt(
                name: String,
                action: () -> Unit,
            ) {
                try {
                    action()
                    report.put(name, true)
                } catch (failure: Throwable) {
                    report.put(name, false)
                    if (first == null) {
                        first = failure
                    } else if (first !== failure) {
                        first!!.addSuppressed(failure)
                    }
                    primary?.let { if (it !== failure) it.addSuppressed(failure) }
                }
            }
            runBlocking {
                withContext(NonCancellable) {
                    attempt("originalDomainAndTypedRowsPreserved") {
                        runBlocking { assertOriginals(checkNotNull(before), checkNotNull(physical)) }
                    }
                    attempt("baselineFilePreserved") { check(sha256(baselineFile) == checkNotNull(baselineHash)) }
                    attempt("environmentPreserved") { h.requireSameJson(checkNotNull(environment), h.environmentJson(), "environment") }
                    attempt("catalogPreserved") { h.requireSameJson(checkNotNull(catalog), h.catalogJson(h.graph), "catalog") }
                    attempt("sdkObserverCancelled") { runBlocking { sdkObserver?.cancelAndJoin() } }
                    attempt("automationFlagRestored") { h.restoreAutomationFlag() }
                    if (first != null) report.put("cleanupFailureClass", first!!.javaClass.name).put("status", "failed")
                    attempt("privateReportSaved") { persist() }
                }
            }
            if (primary == null) first?.let { throw it }
        }
        report.put("status", "completed")
        persist()
        phase("FINISH_SOURCE_REVIEW_COMPLETED")
    }

    private suspend fun prepareThroughUiIfRequired(before: SessionHistoryRecordingRunFlowTest.Harness.ExistingData) {
        compose.waitUntil(20_000) {
            h.nodes("session_start").size == 1 || h.nodes("home_next_practice").size == 1
        }
        val start = h.nodes("session_start").singleOrNull()
        if (start != null && !start.config.contains(SemanticsProperties.Disabled)) return
        val latest = before.sessions.first { !it.isActive }
        val seed =
            checkNotNull(NextPracticeSeed.resolve(h.graph.catalog(), latest)) { "actual UI preparation is unavailable; no seed write" }
        val task =
            seed.practice.course.tasks
                .firstOrNull { it.id != before.preparation?.taskId } ?: seed.practice.course.tasks
                .first()
        val selection =
            PracticeSelection(
                seed.practice.goal.id,
                seed.practice.course.id,
                seed.practice.course.version,
                seed.practice.course.briefingVersion,
                task.id,
            )
        touch("home_next_practice")
        h.waitFor("next_practice_ready")
        h.assertText("next_practice_course", latest.courseTitle)
        touch("next_task_${task.id}")
        touch("next_practice_save")
        withTimeout(20_000) {
            h.graph
                .preparation()
                .observeSelection()
                .first { it == selection }
        }
        expectedPreparation = selection
        report.put("intentionalUiPreparationSelection", h.selectionJson(selection))
        persist()
        h.waitFor("session_start")
        check(
            !h
                .nodes("session_start")
                .single()
                .config
                .contains(SemanticsProperties.Disabled),
        ) { "actual UI start remains blocked" }
    }

    private fun captureHostReceipt() {
        h.instrument.runOnMainSync {
            val host = compose.activity
            val display = checkNotNull(host.display)
            check(host.lifecycle.currentState == Lifecycle.State.RESUMED && host.hasWindowFocus() && display.isValid)
            actualHost = host
            actualDisplay = display.displayId
        }
        requireHost()
    }

    private fun requireHost() {
        h.instrument.runOnMainSync {
            val host = compose.activity
            val display = host.display
            check(host === actualHost && host.lifecycle.currentState == Lifecycle.State.RESUMED && host.hasWindowFocus())
            check(display != null && display.isValid && display.displayId == actualDisplay)
        }
        h.requireForeground()
    }

    private fun touch(tag: String) {
        requireHost()
        check(h.liveAllowed())
        h.touch(tag)
        requireHost()
    }

    private suspend fun assertExact(source: PracticeSession) {
        requireHost()
        check(h.liveAllowed())
        h.waitFor("history_ready")
        val actual =
            h.graph
                .sessions()
                .observeSessions()
                .first()
        check(actual.single { it.sessionId == source.sessionId } == source && !source.isActive)
        val ended = actual.filterNot { it.isActive }
        h.assertSource(source, ended.indexOf(source), ended.size)
        for (tag in listOf("history_newer", "history_older", "history_reset", "finished_review_unavailable", "history_failed")) {
            check(h.nodes(tag).isEmpty())
        }
    }

    private fun assertCold() {
        for (tag in listOf(
            "history_observations",
            "history_memo",
            "history_summary_tasks",
            "history_recording_runs",
            "history_course_completion",
        )) {
            h.assertCollapsed(tag)
        }
        for (tag in listOf(
            "history_observation_content",
            "history_memo_content",
            "history_recording_run_content",
            "history_course_completion_content",
        )) {
            check(h.nodes(tag).isEmpty())
        }
    }

    private fun visibleText(tag: String) {
        requireHost()
        h.scrollVisible(tag)
        compose.onNodeWithTag(tag, useUnmergedTree = true).assertIsDisplayed()
        h.assertTextFits(tag)
        requireHost()
    }

    private suspend fun assertOriginals(
        before: SessionHistoryRecordingRunFlowTest.Harness.ExistingData,
        originalPhysical: JSONObject,
    ) {
        val after = h.snapshot(h.graph)
        check(after.preparation == expectedPreparation)
        val ids = before.sessions.map { it.sessionId }.toSet()
        check(after.sessions.filter { it.sessionId in ids } == before.sessions)
        val extra = after.sessions.filter { it.sessionId !in ids }
        check(extra.size == if (owned == null) 0 else 1)
        owned?.let { check(extra.single() == it) }
        for (id in ids) {
            check(after.observations.getValue(id) == before.observations.getValue(id))
            check(after.memos.getValue(id) == before.memos.getValue(id))
            check(after.recordingRuns.getValue(id) == before.recordingRuns.getValue(id))
        }
        owned?.let {
            check(after.memos.getValue(it.sessionId) == null && after.recordingRuns.getValue(it.sessionId).isEmpty())
            check(
                after.observations.getValue(it.sessionId).all { row ->
                    row.observation.source == VehicleObservationSource.SDK_STATE_OBSERVATION && row.observation.observedAtEpochMillis > 0
                },
            )
        }
        val current = physicalDatabase()
        val oldTables = originalPhysical.getJSONArray("tablesInNameOrder")
        val newTables = current.getJSONArray("tablesInNameOrder")
        check(oldTables.length() == newTables.length())
        for (i in 0 until oldTables.length()) {
            val old = oldTables.getJSONObject(i)
            val fresh = newTables.getJSONObject(i)
            check(old.getString("table") == fresh.getString("table"))
            h.requireSameJson(old.get("columns"), fresh.get("columns"), "typed-column-order")
            h.requireSameJson(old.get("createSql"), fresh.get("createSql"), "table-schema")
            val table = old.getString("table")
            val rows = old.getJSONArray("rowsInRowIdOrder")
            val now = fresh.getJSONArray("rowsInRowIdOrder")
            when (table) {
                "preparation" -> {
                    check(now.length() == rows.length() && rows.length() == 1)
                    val selection = checkNotNull(expectedPreparation)
                    val fields =
                        mapOf(
                            "goalId" to selection.goalId,
                            "courseId" to selection.courseId,
                            "courseVersion" to selection.courseVersion,
                            "briefingVersion" to selection.briefingVersion,
                            "taskId" to selection.taskId,
                        )
                    val expected = JSONArray(rows.toString())
                    val columns = old.getJSONArray("columns")
                    for (column in 0 until columns.length()) {
                        fields[columns.getString(column)]?.let { expected.getJSONArray(0).getJSONObject(column).put("value", it) }
                    }
                    h.requireSameJson(expected, now, "only-explicit-UI-preparation-fields")
                }
                "sqlite_sequence" -> {
                    check(now.length() == rows.length())
                    for (row in 0 until rows.length()) {
                        val previous = rows.getJSONArray(row)
                        val freshRow = now.getJSONArray(row)
                        val name = previous.getJSONObject(1).getString("value")
                        if (name == "practice_session" || name == "vehicle_observation") {
                            h.requireSameJson(previous.get(0), freshRow.get(0), "sequence-rowid")
                            h.requireSameJson(previous.get(1), freshRow.get(1), "sequence-name")
                            check(freshRow.getJSONObject(2).getString("type") == "INTEGER")
                            check(freshRow.getJSONObject(2).getLong("value") >= previous.getJSONObject(2).getLong("value"))
                        } else {
                            h.requireSameJson(previous, freshRow, "unrelated-original-sequence")
                        }
                    }
                }
                "practice_session", "vehicle_observation" -> {
                    check(now.length() >= rows.length())
                    for (row in 0 until rows.length()) h.requireSameJson(rows.get(row), now.get(row), "original-typed-row")
                }
                else -> h.requireSameJson(rows, now, "original-typed-table")
            }
        }
        report.put("physicalAfter", current).put("currentData", h.dataJson(after))
        persist()
    }

    private fun assertBaseline(
        data: SessionHistoryRecordingRunFlowTest.Harness.ExistingData,
        physical: JSONObject,
    ) {
        check(baselineFile.isFile && !File(baselineFile.path + ".bak").exists() && !File(baselineFile.path + ".new").exists())
        val baseline = JSONObject(baselineFile.readText())
        check(baseline.getString("status") == "completed" && baseline.getBoolean("preservedOriginalData"))
        check(
            baseline.getString("measurementSeries") == "history-memo-edit-read-v1" && baseline.getString("measurementStage") == "baseline",
        )
        check(baseline.getInt("plannedWarmups") == 5 && baseline.getInt("plannedSamples") == 30)
        for ((key, count) in listOf("warmups" to 5, "samples" to 30)) {
            val values = baseline.getJSONArray(key)
            check(values.length() == count)
            for (index in 0 until count) check(values.getJSONObject(index).getString("status") == "success")
        }
        val frozen = JSONObject(baseline.getJSONObject("originalData").toString())
        h.requireSameJson(frozen.get("physicalDatabase"), physical, "canonical-typed-baseline")
        frozen.remove("physicalDatabase")
        h.requireSameJson(frozen, h.dataJson(data), "canonical-domain-baseline")
        h.requireSameJson(baseline.getJSONObject("catalog"), h.catalogJson(h.graph), "catalog-baseline")
        baselineHash = sha256(baselineFile)
        report.put("frozenBaselineSha256", baselineHash).put("baselineKind", "completed-frozen-M1-canonical-input-only")
    }

    private fun physicalDatabase(): JSONObject =
        SQLiteDatabase
            .openDatabase(
                h.instrument.targetContext
                    .getDatabasePath("hangil.db")
                    .path,
                null,
                SQLiteDatabase.OPEN_READONLY,
            ).use { db ->
                check(db.version == 6)
                val tables = JSONArray()
                db.rawQuery("SELECT name, sql FROM sqlite_master WHERE type='table' ORDER BY name", null).use { names ->
                    while (names.moveToNext()) {
                        val name = names.getString(0)
                        val quoted = "\"" + name.replace("\"", "\"\"") + "\""
                        val rows = JSONArray()
                        val columns = JSONArray()
                        db.rawQuery("SELECT rowid AS __measurement_rowid__, * FROM $quoted ORDER BY rowid", null).use { cursor ->
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
                                            Cursor.FIELD_TYPE_FLOAT ->
                                                JSONObject()
                                                    .put(
                                                        "type",
                                                        "FLOAT",
                                                    ).put("value", cursor.getDouble(column))
                                            Cursor.FIELD_TYPE_STRING ->
                                                JSONObject()
                                                    .put(
                                                        "type",
                                                        "STRING",
                                                    ).put("value", cursor.getString(column))
                                            Cursor.FIELD_TYPE_BLOB ->
                                                JSONObject().put("type", "BLOB_HEX").put(
                                                    "value",
                                                    cursor
                                                        .getBlob(
                                                            column,
                                                        ).joinToString("") { "%02x".format(Locale.ROOT, it.toInt() and 255) },
                                                )
                                            else -> error("unknown SQLite field type")
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
                db
                    .rawQuery(
                        "SELECT COUNT(*) FROM course_completion_declaration",
                        null,
                    ).use { check(it.moveToFirst() && it.getLong(0) == 0L) }
                db.rawQuery("PRAGMA foreign_key_check", null).use { check(it.count == 0) }
                JSONObject().put("schemaVersion", db.version).put("tablesInNameOrder", tables).put("foreignKeyViolations", 0)
            }

    private fun capture(
        stage: String,
        diagnostic: Boolean,
    ) {
        var first: Throwable? = null

        fun attempt(action: () -> Unit) {
            try {
                action()
            } catch (failure: Throwable) {
                if (first == null) {
                    first = failure
                } else if (first !== failure) {
                    first!!.addSuppressed(failure)
                }
            }
        }
        val proof =
            JSONObject()
                .put("scope", if (diagnostic) "failure-diagnostic-unverified" else "actual-Main-SDK-confirmed")
                .put("captureDefaultDisplayOnly", true)
                .put("pngSaved", false)
        var display: Int? = null
        attempt {
            h.instrument.runOnMainSync {
                val host = compose.activity
                display = host.display?.displayId
                proof.put("mainLifecycle", host.lifecycle.currentState.name).put("windowFocus", host.hasWindowFocus())
            }
            proof.put("display", display ?: JSONObject.NULL).put("actualFocusedPackage", h.actualPackage() ?: JSONObject.NULL)
        }
        attempt {
            if (!diagnostic) requireHost()
            check(display == Display.DEFAULT_DISPLAY) { "default screenshot cannot prove a different display" }
            val image = checkNotNull(h.instrument.uiAutomation.takeScreenshot())
            try {
                File(directory, "$stage.png").outputStream().use { check(image.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            } finally {
                image.recycle()
            }
            proof.put("pngSaved", true)
        }
        attempt { File(directory, "$stage.json").writeText(proof.toString(2)) }
        first?.let { throw it }
    }

    private fun persist() {
        val file = AtomicFile(File(directory, "owner-and-result.json"))
        val stream = file.startWrite()
        try {
            stream.write(report.toString(2).toByteArray())
            file.finishWrite(stream)
        } catch (
            failure: Throwable,
        ) {
            file.failWrite(stream)
            throw failure
        }
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun phase(value: String) = h.instrument.sendStatus(2, Bundle().apply { putString("stream", value + System.lineSeparator()) })
}
