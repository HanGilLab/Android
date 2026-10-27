package io.github.hangillab.hangil.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object PreparationMigration1To2 : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `practice_session` (" +
                "`rowId` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `sessionId` TEXT NOT NULL, " +
                "`goalId` TEXT NOT NULL, `courseId` TEXT NOT NULL, " +
                "`courseVersion` INTEGER NOT NULL, `briefingVersion` INTEGER NOT NULL, `taskId` TEXT NOT NULL, " +
                "`goalTitle` TEXT NOT NULL, `courseTitle` TEXT NOT NULL, `taskTitle` TEXT NOT NULL, " +
                "`courseReviewStatus` TEXT NOT NULL, `startedAtEpochMillis` INTEGER NOT NULL, " +
                "`endedAtEpochMillis` INTEGER, `activeSlot` INTEGER)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_practice_session_sessionId` ON `practice_session` (`sessionId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_practice_session_activeSlot` ON `practice_session` (`activeSlot`)")
    }
}
