package io.github.hangillab.hangil.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object PreparationMigration2To3 : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `vehicle_observation` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT NOT NULL, " +
                "`observedAtEpochMillis` INTEGER NOT NULL, `source` TEXT NOT NULL, `connection` TEXT NOT NULL, " +
                "`gearStatus` TEXT NOT NULL, `gearValue` TEXT, `turnStatus` TEXT NOT NULL, `turnValue` TEXT, " +
                "`brakeStatus` TEXT NOT NULL, `brakeValue` INTEGER, " +
                "FOREIGN KEY(`sessionId`) REFERENCES `practice_session`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_vehicle_observation_sessionId` ON `vehicle_observation` (`sessionId`)")
    }
}
