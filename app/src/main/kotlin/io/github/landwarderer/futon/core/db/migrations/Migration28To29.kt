package io.github.landwarderer.futon.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

class Migration28To29 : Migration(28, 29) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS webview_sources (
                id INTEGER NOT NULL PRIMARY KEY,
                title TEXT NOT NULL,
                base_url TEXT NOT NULL,
                chapter_url_pattern TEXT,
                cover_url TEXT,
                last_read_url TEXT,
                last_read_scroll_percent REAL NOT NULL DEFAULT 0,
                last_read_chapter REAL,
                latest_known_chapter REAL,
                anilist_id INTEGER,
                mal_id INTEGER,
                reading_status TEXT,
                added_at INTEGER NOT NULL,
                last_read_at INTEGER
            )
            """.trimIndent()
        )
    }
}
