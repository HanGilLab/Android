package io.github.hangillab.hangil.core.database

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/** Add only an unwritten memo field; existing road snapshots, versions and node rows stay exact. */
internal object PreparationMigration8To9 : Migration(8, 9) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `session_review_road` ADD COLUMN `memoText` TEXT NOT NULL DEFAULT ''")
    }
}
