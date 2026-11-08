package io.github.hangillab.hangil.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Old preparations and every stored source keep their original fields and have no copied target. */
internal object PreparationMigration9To10 : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        for (table in listOf(
            "preparation",
            "practice_session",
            "location_recording_run",
            "course_completion_declaration",
            "session_review_road",
        )) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `plannedRoadJson` TEXT DEFAULT NULL")
        }
    }
}
