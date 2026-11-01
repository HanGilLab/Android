package io.github.hangillab.hangil.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive only: existing schema6 rows, values and sequences are never rewritten. */
internal object PreparationMigration6To7 : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `location_recording_run` (`rowId` INTEGER NOT NULL PRIMARY KEY " +
                "AUTOINCREMENT, `runId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `processId` TEXT NOT NULL, `goalId` " +
                "TEXT NOT NULL, `courseId` TEXT NOT NULL, `courseVersion` INTEGER NOT NULL, `briefingVersion` INTEGER " +
                "NOT NULL, `taskId` TEXT NOT NULL, `goalTitle` TEXT NOT NULL, `courseTitle` TEXT NOT NULL, " +
                "`taskTitle` TEXT NOT NULL, `courseReviewStatus` TEXT NOT NULL, `sourceStartedAtEpochMillis` INTEGER " +
                "NOT NULL, `startedAtEpochMillis` INTEGER NOT NULL, `startedAtElapsedRealtimeMillis` INTEGER NOT " +
                "NULL, `endedAtEpochMillis` INTEGER, `endedAtElapsedRealtimeMillis` INTEGER, `status` TEXT NOT NULL, " +
                "`stopReason` TEXT, `openSlot` INTEGER, FOREIGN KEY(`sessionId`) REFERENCES " +
                "`practice_session`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `location_recording_event` (`id` INTEGER NOT NULL PRIMARY KEY " +
                "AUTOINCREMENT, `sessionId` TEXT NOT NULL, `runId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, " +
                "`observedAtEpochMillis` INTEGER NOT NULL, `receivedAtElapsedRealtimeMillis` INTEGER NOT NULL, `kind` " +
                "TEXT NOT NULL, `sampleSource` TEXT, `latitude` REAL, `longitude` REAL, `altitudeRaw` REAL, " +
                "`headingRaw` REAL, `confidenceRaw` REAL, `sdkTimestampMillis` INTEGER, `errorOrigin` TEXT, " +
                "`errorClassName` TEXT, FOREIGN KEY(`sessionId`) REFERENCES `practice_session`(`sessionId`) ON UPDATE " +
                "NO ACTION ON DELETE CASCADE, FOREIGN KEY(`runId`, `sessionId`) REFERENCES " +
                "`location_recording_run`(`runId`, `sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_location_recording_run_sessionId` ON `location_recording_run` " +
                "(`sessionId`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_location_recording_run_runId` ON `location_recording_run` " +
                "(`runId`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_location_recording_run_runId_sessionId` ON " +
                "`location_recording_run` (`runId`, `sessionId`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_location_recording_run_openSlot` ON " +
                "`location_recording_run` (`openSlot`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_location_recording_event_sessionId` ON `location_recording_event` " +
                "(`sessionId`)",
        )
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_location_recording_event_runId_sessionId` ON " +
                "`location_recording_event` (`runId`, `sessionId`)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_location_recording_event_runId_ordinal` ON " +
                "`location_recording_event` (`runId`, `ordinal`)",
        )
    }
}
