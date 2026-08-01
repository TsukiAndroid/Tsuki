package io.github.landwarderer.futon.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migrates the pre-seeded Keiyoushi extension repo entry to the new index URL.
 *
 * Keiyoushi stopped publishing real extensions to their `repo` branch `index.min.json`
 * (it now returns a "Outdated App / Update to Mihon 0.20.1+" stub). The canonical
 * extension index is now at the `main` branch `index.json` with a new schema.
 *
 * Base URL change: refs/heads/repo → main
 * Signing key update: new key published in main/index.json signingKey field
 */
class Migration31To32 : Migration(31, 32) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            UPDATE external_extension_repos
            SET baseUrl = 'https://raw.githubusercontent.com/keiyoushi/extensions/main',
                signingKeyFingerprint = '9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2',
                updatedAt = ${System.currentTimeMillis()}
            WHERE type = 'MIHON'
              AND baseUrl = 'https://raw.githubusercontent.com/keiyoushi/extensions/refs/heads/repo'
            """.trimIndent()
        )
    }
}
