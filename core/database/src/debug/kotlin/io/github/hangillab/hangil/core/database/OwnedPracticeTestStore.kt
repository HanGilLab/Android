package io.github.hangillab.hangil.core.database

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.room.Room
import io.github.hangillab.hangil.core.domain.CourseCompletionRepository
import io.github.hangillab.hangil.core.domain.PracticeCatalog
import io.github.hangillab.hangil.core.domain.PracticeRepository
import io.github.hangillab.hangil.core.domain.PracticeSessionRepository
import io.github.hangillab.hangil.core.domain.SessionDeletionRepository
import io.github.hangillab.hangil.core.domain.SessionMemoRepository
import io.github.hangillab.hangil.core.domain.VehicleObservationRepository
import java.io.Closeable
import java.io.File
import java.util.UUID

/**
 * Debug-only real Room store for synthetic fixtures. No database name, path or copied rows are accepted.
 * Create and close it off the UI thread, after its collectors and writes have finished. The caller owns
 * fixture values and must create them through these real domain repositories, never from user records.
 */
class OwnedPracticeTestStore private constructor(
    private var database: PreparationDatabase,
    private val reservation: Reservation,
    private val application: Context,
) : Closeable {
    val catalog: PracticeCatalog = LocalPracticeCatalog()
    val preparation: PracticeRepository get() = RoomPracticeRepository(database)
    val sessions: PracticeSessionRepository get() = RoomPracticeSessionRepository(database, catalog)
    val observations: VehicleObservationRepository get() = RoomVehicleObservationRepository(database)
    val memos: SessionMemoRepository get() = RoomSessionMemoRepository(database)
    val deletions: SessionDeletionRepository get() = RoomSessionDeletionRepository(database)
    val courseCompletions: CourseCompletionRepository get() = RoomCourseCompletionRepository(database)

    private var closed = false

    /** Close collectors/writes first and reacquire repositories afterward; names/paths are never accepted. */
    @Synchronized
    fun reopen() {
        check(!closed) { "닫힌 격리 저장소는 다시 열 수 없습니다" }
        reservation.requireOwnership()
        database.close()
        val reopened = openOwned(application, reservation)
        // Retain the new handle even when opening or its first cleanup fails.
        database = reopened
        try {
            reopened.openHelper.writableDatabase
        } catch (failure: Throwable) {
            try {
                reopened.close()
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    /** These diagnostics read only this unique store. They accept no SQL, name or path inputs. */
    @Synchronized
    fun schemaVersion(): Int {
        check(!closed) { "닫힌 격리 저장소는 조회할 수 없습니다" }
        return database.openHelper.readableDatabase.version
    }

    @Synchronized
    fun sequenceSnapshot(): Map<String, Long> {
        check(!closed) { "닫힌 격리 저장소는 조회할 수 없습니다" }
        return database.openHelper.readableDatabase.query("SELECT name, seq FROM sqlite_sequence ORDER BY name").use { cursor ->
            buildMap {
                while (cursor.moveToNext()) put(cursor.getString(0), cursor.getLong(1))
            }
        }
    }

    /** On the repository transaction thread this also observes that transaction's uncommitted rows. */
    @Synchronized
    fun deletionRowCounts(sessionId: String): Triple<Long, Long, Long> {
        check(!closed) { "닫힌 격리 저장소는 조회할 수 없습니다" }
        require(sessionId.isNotBlank()) { "격리 세션 ID가 필요합니다" }
        val sqlite = database.openHelper.readableDatabase

        fun count(query: String): Long =
            sqlite.query(query, arrayOf(sessionId)).use { cursor ->
                check(cursor.moveToFirst()) { "격리 행 수를 읽지 못했습니다" }
                cursor.getLong(0)
            }
        return Triple(
            count("SELECT COUNT(*) FROM practice_session WHERE sessionId = ?"),
            count("SELECT COUNT(*) FROM vehicle_observation WHERE sessionId = ?"),
            count("SELECT COUNT(*) FROM session_memo WHERE sessionId = ?"),
        )
    }

    @Synchronized
    fun foreignKeyViolationCount(): Int {
        check(!closed) { "닫힌 격리 저장소는 조회할 수 없습니다" }
        return database.openHelper.readableDatabase
            .query("PRAGMA foreign_key_check")
            .use { it.count }
    }

    /** Closes only this handle and removes only its nonce-owned file and explicitly named SQLite sidecars. */
    @Synchronized
    override fun close() {
        if (closed) return
        database.close()
        reservation.deleteOwnedFiles()
        closed = true
    }

    companion object {
        fun create(context: Context): OwnedPracticeTestStore {
            val application = context.applicationContext
            check(application.packageName == "io.github.hangillab.hangil.debug") { "전용 debug 앱에서만 격리 저장소를 만듭니다" }
            check(application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) { "debug 빌드가 필요합니다" }
            val reservation = Reservation.create(application)
            var database: PreparationDatabase? = null
            try {
                val opened = openOwned(application, reservation)
                database = opened
                opened.openHelper.writableDatabase
                return OwnedPracticeTestStore(opened, reservation, application)
            } catch (failure: Throwable) {
                var handleClosed = true
                try {
                    database?.close()
                } catch (cleanup: Throwable) {
                    handleClosed = false
                    failure.addSuppressed(cleanup)
                }
                if (handleClosed) {
                    try {
                        reservation.deleteOwnedFiles()
                    } catch (cleanup: Throwable) {
                        failure.addSuppressed(cleanup)
                    }
                }
                throw failure
            }
        }

        private fun openOwned(
            application: Context,
            reservation: Reservation,
        ): PreparationDatabase {
            reservation.requireOwnership()
            return Room
                .databaseBuilder(application, PreparationDatabase::class.java, reservation.name)
                .addMigrations(
                    PreparationMigration1To2,
                    PreparationMigration2To3,
                    PreparationMigration3To4,
                    PreparationMigration4To5,
                    PreparationMigration5To6,
                ).build()
        }
    }

    private class Reservation private constructor(
        val name: String,
        private val directory: File,
        private val nonce: String,
    ) {
        private val marker = File(directory, "$name.owner")
        private val ownedFiles = listOf(name, "$name-wal", "$name-shm", "$name-journal").map { File(directory, it) }

        fun requireOwnership() {
            check(marker.canonicalFile == marker.absoluteFile && marker.isFile && marker.readText() == nonce) {
                "이번 격리 저장소의 소유 확인에 실패했습니다"
            }
        }

        fun deleteOwnedFiles() {
            requireOwnership()
            for (file in ownedFiles) {
                check(file.canonicalFile == file.absoluteFile && file.parentFile == directory) { "격리 파일 경계를 벗어났습니다" }
                check(!file.exists() || file.delete()) { "소유 격리 파일을 정리하지 못했습니다" }
            }
            check(marker.delete()) { "소유 확인 파일을 정리하지 못했습니다" }
        }

        companion object {
            fun create(context: Context): Reservation {
                val name = "hangil-owned-practice-${UUID.randomUUID()}.db"
                val database = context.getDatabasePath(name).absoluteFile
                val directory = checkNotNull(database.parentFile).canonicalFile
                check(directory.isDirectory || directory.mkdirs()) { "격리 저장소 폴더를 준비하지 못했습니다" }
                check(database.canonicalFile == File(directory, name)) { "격리 저장소 경로를 확인하지 못했습니다" }
                val marker = File(directory, "$name.owner")
                check(marker.canonicalFile == marker.absoluteFile) { "소유 확인 파일의 경계가 다릅니다" }
                check(marker.createNewFile()) { "기존 소유 확인 파일을 덮지 않습니다" }
                var reserved = false
                try {
                    val nonce = UUID.randomUUID().toString()
                    marker.writeText(nonce)
                    val files = listOf(name, "$name-wal", "$name-shm", "$name-journal").map { File(directory, it) }
                    check(files.none { it.exists() }) { "기존 격리 파일과 sidecar를 덮지 않습니다" }
                    check(database.createNewFile()) { "이번 실행의 새 격리 파일만 예약합니다" }
                    reserved = true
                    return Reservation(name, directory, nonce)
                } catch (failure: Throwable) {
                    // Room has not opened yet. Never remove pre-existing sidecars or a failed reservation.
                    if (reserved && !database.delete()) failure.addSuppressed(IllegalStateException("새 격리 파일 정리가 실패했습니다"))
                    if (!marker.delete()) failure.addSuppressed(IllegalStateException("새 소유 확인 파일 정리가 실패했습니다"))
                    throw failure
                }
            }
        }
    }
}
