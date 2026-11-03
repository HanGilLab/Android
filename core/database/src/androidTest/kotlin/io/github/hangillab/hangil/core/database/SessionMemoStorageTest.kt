package io.github.hangillab.hangil.core.database

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The external runner supplies only a consistent, privately owned v3 copy in the isolated package.
 * This test neither reads the original app database nor prints private rows or filesystem paths.
 * The runner owns verification evidence and cleanup of the supplied copy and its sidecars.
 */
@RunWith(AndroidJUnit4::class)
class SessionMemoStorageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun privateVersionThreeCopyMigratesWithoutChangingExistingRows() {
        assumeTrue(
            "Runs only for an explicitly supplied private copy; default discovery is not migration evidence",
            InstrumentationRegistry.getArguments().getString("allowPrivateVersionThreeMemoCopy") == "true",
        )
        assertEquals("io.github.hangillab.hangil.core.database.test", context.packageName)
        val file = context.getDatabasePath("session-memo-private-copy.db")
        check(file.isFile) { "The externally supplied private database copy is missing" }
        val before =
            SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
                assertEquals(3, db.version)
                snapshot { query -> db.rawQuery(query, null) }
            }
        val migrated =
            Room
                .databaseBuilder(context, PreparationDatabase::class.java, file.name)
                .addMigrations(
                    PreparationMigration1To2,
                    PreparationMigration2To3,
                    PreparationMigration3To4,
                    PreparationMigration4To5,
                    PreparationMigration5To6,
                    PreparationMigration6To7,
                ).build()
        try {
            val sqlite = migrated.openHelper.writableDatabase
            assertEquals(7, sqlite.version)
            val after = snapshot { query -> sqlite.query(query) }
            for (table in before.keys) {
                // Compare every original column, physical type and value without exposing them.
                assertTrue("Existing private table contents and identities must be preserved", before[table] == after[table])
            }
            sqlite.query("SELECT COUNT(*) FROM session_memo").use { cursor ->
                check(cursor.moveToFirst())
                assertEquals(0L, cursor.getLong(0))
            }
            sqlite.query("PRAGMA foreign_key_check").use { assertEquals(0, it.count) }
        } finally {
            migrated.close()
        }
        InstrumentationRegistry.getInstrumentation().sendStatus(
            2,
            Bundle().apply { putString("stream", "SESSION_MEMO_STORAGE_PRIVATE_V3_COPY_PRESERVED_NEW_TABLE_EMPTY\n") },
        )
    }

    private fun snapshot(query: (String) -> Cursor) =
        mapOf(
            "preparation" to query("SELECT * FROM preparation ORDER BY slot").use(::readRows),
            "practice_session" to query("SELECT * FROM practice_session ORDER BY rowId").use(::readRows),
            "vehicle_observation" to query("SELECT * FROM vehicle_observation ORDER BY id").use(::readRows),
            "sqlite_sequence" to query("SELECT * FROM sqlite_sequence ORDER BY name").use(::readRows),
        )

    private fun readRows(cursor: Cursor): TableData =
        TableData(
            cursor.columnNames.toList(),
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        (0 until cursor.columnCount).map { column ->
                            cursor.getType(column) to
                                when (cursor.getType(column)) {
                                    Cursor.FIELD_TYPE_NULL -> null
                                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(column)
                                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(column)
                                    Cursor.FIELD_TYPE_STRING -> cursor.getString(column)
                                    Cursor.FIELD_TYPE_BLOB -> cursor.getBlob(column).toList()
                                    else -> error("Unexpected private database storage type")
                                }
                        },
                    )
                }
            },
        )

    private data class TableData(
        val columns: List<String>,
        val values: List<List<Pair<Int, Any?>>>,
    )
}
