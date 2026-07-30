package io.github.landwarderer.futon.core.db.dao

import androidx.room.*
import io.github.landwarderer.futon.core.db.entity.WebViewSourceEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface WebViewSourceDao {

    // ── Queries ──────────────────────────────────────────────────────────

    @Query("SELECT * FROM webview_sources ORDER BY last_read_at DESC NULLS LAST")
    fun observeAll(): Flow<List<WebViewSourceEntity>>

    @Query("SELECT * FROM webview_sources WHERE id = :id")
    fun observeById(id: Long): Flow<WebViewSourceEntity?>

    @Query("SELECT * FROM webview_sources WHERE id = :id")
    suspend fun getById(id: Long): WebViewSourceEntity?

    /** All sources that have an AniList ID — used by the notification worker. */
    @Query("SELECT * FROM webview_sources WHERE anilist_id IS NOT NULL")
    suspend fun getAllWithAnilist(): List<WebViewSourceEntity>

    /** All sources regardless of AniList linking — for full notification scan. */
    @Query("SELECT * FROM webview_sources")
    suspend fun getAll(): List<WebViewSourceEntity>

    // ── Writes ───────────────────────────────────────────────────────────

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(source: WebViewSourceEntity)

    @Update
    suspend fun update(source: WebViewSourceEntity)

    @Upsert
    suspend fun upsert(source: WebViewSourceEntity)

    @Query("DELETE FROM webview_sources WHERE id = :id")
    suspend fun deleteById(id: Long)

    // ── Progress update (called frequently from the reader) ──────────────

    @Query("""
        UPDATE webview_sources
        SET last_read_url = :url,
            last_read_scroll_percent = :scrollPercent,
            last_read_chapter = :chapter,
            last_read_at = :timestamp
        WHERE id = :id
    """)
    suspend fun updateProgress(
        id: Long,
        url: String,
        scrollPercent: Float,
        chapter: Float?,
        timestamp: Long,
    )

    // ── Notification worker updates ──────────────────────────────────────

    @Query("UPDATE webview_sources SET latest_known_chapter = :chapter WHERE id = :id")
    suspend fun updateLatestKnownChapter(id: Long, chapter: Float)

    // ── Notification opt-out ────────────────────────────────────

    @Query("UPDATE webview_sources SET notifications_enabled = :enabled WHERE id = :id")
    suspend fun setNotificationsEnabled(id: Long, enabled: Boolean)

    // ── AniList linking ──────────────────────────────────────────────────

    @Query("""
        UPDATE webview_sources
        SET anilist_id = :anilistId,
            reading_status = :status
        WHERE id = :id
    """)
    suspend fun updateAnilistLink(id: Long, anilistId: Int, status: String?)
}
