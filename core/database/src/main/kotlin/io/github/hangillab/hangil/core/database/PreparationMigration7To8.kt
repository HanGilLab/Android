package io.github.hangillab.hangil.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Additive only; historical records and their SQLite sequences are never rewritten. */
internal object PreparationMigration7To8 : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `session_review_road` (`sessionId` TEXT NOT NULL, `goalId` TEXT NOT NULL, " +
                "`courseId` TEXT NOT NULL, `courseVersion` INTEGER NOT NULL, `briefingVersion` INTEGER NOT NULL, " +
                "`taskId` TEXT NOT NULL, `goalTitle` TEXT NOT NULL, `courseTitle` TEXT NOT NULL, `taskTitle` TEXT NOT NULL, " +
                "`courseReviewStatus` TEXT NOT NULL, `startedAtEpochMillis` INTEGER NOT NULL, `endedAtEpochMillis` INTEGER NOT NULL, " +
                "`version` TEXT NOT NULL, `origin` TEXT NOT NULL, `roadId` TEXT NOT NULL, `roadTitle` TEXT NOT NULL, " +
                "`highway` TEXT NOT NULL, `osmWayId` INTEGER NOT NULL, `osmWayVersion` INTEGER NOT NULL, " +
                "`capturedAtEpochMillis` INTEGER NOT NULL, `nodeCount` INTEGER NOT NULL, PRIMARY KEY(`sessionId`), " +
                "FOREIGN KEY(`sessionId`) REFERENCES `practice_session`(`sessionId`) ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `session_review_road_node` (`sessionId` TEXT NOT NULL, `ordinal` INTEGER NOT NULL, " +
                "`osmNodeId` INTEGER NOT NULL, `osmNodeVersion` INTEGER NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, " +
                "PRIMARY KEY(`sessionId`, `ordinal`), FOREIGN KEY(`sessionId`) REFERENCES `session_review_road`(`sessionId`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
    }
}
