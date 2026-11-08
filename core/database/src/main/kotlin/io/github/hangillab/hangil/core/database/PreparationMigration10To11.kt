package io.github.hangillab.hangil.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Existing review choices keep their source, shape and memo with no invented sample connection. */
internal object PreparationMigration10To11 : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `session_review_road` ADD COLUMN `sampleAnchorJson` TEXT DEFAULT NULL")
    }
}
