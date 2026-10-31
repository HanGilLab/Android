package io.github.hangillab.hangil.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object PreparationMigration4To5 : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `vehicle_recording_run` (" +
                "`runId` TEXT NOT NULL, `sessionId` TEXT NOT NULL, `processId` TEXT NOT NULL, " +
                "`startedAtEpochMillis` INTEGER NOT NULL, `endedAtEpochMillis` INTEGER, `status` TEXT NOT NULL, " +
                "`openSessionId` TEXT, PRIMARY KEY(`runId`), " +
                "FOREIGN KEY(`sessionId`) REFERENCES `practice_session`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_vehicle_recording_run_sessionId` ON `vehicle_recording_run` (`sessionId`)")
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_vehicle_recording_run_openSessionId` ON `vehicle_recording_run` (`openSessionId`)",
        )
    }
}
