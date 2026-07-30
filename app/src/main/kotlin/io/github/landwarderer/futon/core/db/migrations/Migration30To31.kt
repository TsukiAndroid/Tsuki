package io.github.landwarderer.futon.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Adds the [custom_css] column to [webview_sources].
 * Null by default — existing rows are unaffected.
 */
class Migration30To31 : Migration(30, 31) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE webview_sources ADD COLUMN custom_css TEXT")
    }
}
