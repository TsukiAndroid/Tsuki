package io.github.landwarderer.futon.core.db.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Moves the built-in Keiyoushi repository back to its live repo branch.
 * Keiyoushi's current repo.json and compressed index.pb are published there.
 */
class Migration32To33 : Migration(32, 33) {

    override fun migrate(db: SupportSQLiteDatabase) {
        val target = "https://raw.githubusercontent.com/keiyoushi/extensions/repo"
        val oldMain = "https://raw.githubusercontent.com/keiyoushi/extensions/main"
        val oldRefs = "https://raw.githubusercontent.com/keiyoushi/extensions/refs/heads/repo"
        val now = System.currentTimeMillis()

        // Keep the already-canonical row if a user has added it separately.
        db.execSQL(
            """
            DELETE FROM external_extension_repos
            WHERE type = 'MIHON'
              AND baseUrl IN ('$oldMain', '$oldRefs')
              AND EXISTS (
                  SELECT 1 FROM external_extension_repos AS current
                  WHERE current.type = 'MIHON' AND current.baseUrl = '$target'
              )
            """.trimIndent()
        )

        // Prefer migrating the most recent seeded URL, then remove any duplicate legacy row.
        db.execSQL(
            """
            UPDATE external_extension_repos
            SET baseUrl = '$target',
                signingKeyFingerprint = '9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2',
                updatedAt = $now
            WHERE type = 'MIHON'
              AND baseUrl = '$oldMain'
              AND NOT EXISTS (
                  SELECT 1 FROM external_extension_repos AS current
                  WHERE current.type = 'MIHON' AND current.baseUrl = '$target'
              )
            """.trimIndent()
        )
        db.execSQL(
            """
            DELETE FROM external_extension_repos
            WHERE type = 'MIHON' AND baseUrl = '$oldRefs'
              AND EXISTS (
                  SELECT 1 FROM external_extension_repos AS current
                  WHERE current.type = 'MIHON' AND current.baseUrl = '$target'
              )
            """.trimIndent()
        )
        db.execSQL(
            """
            UPDATE external_extension_repos
            SET baseUrl = '$target',
                signingKeyFingerprint = '9add655a78e96c4ec7a53ef89dccb557cb5d767489fac5e785d671a5a75d4da2',
                updatedAt = $now
            WHERE type = 'MIHON' AND baseUrl = '$oldRefs'
              AND NOT EXISTS (
                  SELECT 1 FROM external_extension_repos AS current
                  WHERE current.type = 'MIHON' AND current.baseUrl = '$target'
              )
            """.trimIndent()
        )
    }
}
