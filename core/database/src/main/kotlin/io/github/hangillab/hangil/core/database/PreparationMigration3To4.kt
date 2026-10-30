package io.github.hangillab.hangil.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object PreparationMigration3To4 : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `session_memo` (" +
                "`sessionId` TEXT NOT NULL, `text` TEXT NOT NULL, `version` TEXT NOT NULL, " +
                "PRIMARY KEY(`sessionId`), " +
                "FOREIGN KEY(`sessionId`) REFERENCES `practice_session`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
    }
}
