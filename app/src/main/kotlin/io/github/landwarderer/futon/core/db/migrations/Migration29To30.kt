package io.github.landwarderer.futon.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the [notifications_enabled] column to [webview_sources].
 * Existing rows default to 1 (enabled) so behaviour is unchanged.
 */
class Migration29To30 : Migration(29, 30) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE webview_sources ADD COLUMN notifications_enabled INTEGER NOT NULL DEFAULT 1"
        )
    }
}
