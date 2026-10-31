package io.github.hangillab.hangil.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

internal object PreparationMigration5To6 : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `course_completion_declaration` (" +
                "`sessionId` TEXT NOT NULL, `goalId` TEXT NOT NULL, `courseId` TEXT NOT NULL, " +
                "`courseVersion` INTEGER NOT NULL, `briefingVersion` INTEGER NOT NULL, `taskId` TEXT NOT NULL, " +
                "`goalTitle` TEXT NOT NULL, `courseTitle` TEXT NOT NULL, `taskTitle` TEXT NOT NULL, " +
                "`courseReviewStatus` TEXT NOT NULL, `startedAtEpochMillis` INTEGER NOT NULL, `endedAtEpochMillis` INTEGER NOT NULL, " +
                "`version` TEXT NOT NULL, `declaredAtEpochMillis` INTEGER NOT NULL, `origin` TEXT NOT NULL, " +
                "PRIMARY KEY(`sessionId`), " +
                "FOREIGN KEY(`sessionId`) REFERENCES `practice_session`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
    }
}
