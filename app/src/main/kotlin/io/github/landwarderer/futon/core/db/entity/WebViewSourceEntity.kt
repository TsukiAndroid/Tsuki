package io.github.landwarderer.futon.core.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "webview_sources")
data class WebViewSourceEntity(

    /** Stable ID — generated as the absolute URL's hashCode (Long). */
    @PrimaryKey
    val id: Long,

    /** Display title, initially from the page's og:title. User-editable. */
    val title: String,

    /** The URL the user originally pasted — the manga's home page on that site. */
    @ColumnInfo(name = "base_url")
    val baseUrl: String,

    /**
     * Regex or template string that identifies chapter URLs for this source.
     *
     * Template format: a URL string with `{N}` in place of the chapter number.
     * Example: "https://manganato.com/manga-abc123/chapter-{N}"
     *
     * Null if the user has not confirmed a pattern yet.
     */
    @ColumnInfo(name = "chapter_url_pattern")
    val chapterUrlPattern: String?,

    /** Full URL to the cover image. Initially from og:image. */
    @ColumnInfo(name = "cover_url")
    val coverUrl: String?,

    /** Full URL of the last chapter page the user was on. Null if never opened. */
    @ColumnInfo(name = "last_read_url")
    val lastReadUrl: String?,

    /**
     * Vertical scroll progress through the last read page, 0.0–1.0.
     * 0.0 = top, 1.0 = bottom.
     */
    @ColumnInfo(name = "last_read_scroll_percent")
    val lastReadScrollPercent: Float = 0f,

    /**
     * The chapter number the user last read, extracted from the URL.
     * Stored as Float to handle decimal chapters (e.g. 12.5).
     * Null if no chapter has been read yet.
     */
    @ColumnInfo(name = "last_read_chapter")
    val lastReadChapter: Float?,

    /**
     * The highest chapter number seen — updated by the notification worker
     * when a new chapter is detected. Used to compute "N chapters behind".
     */
    @ColumnInfo(name = "latest_known_chapter")
    val latestKnownChapter: Float?,

    /** AniList media ID if the user has linked this source to an AniList entry. */
    @ColumnInfo(name = "anilist_id")
    val anilistId: Int?,

    /** MyAnimeList manga ID if the user has linked this source to a MAL entry. */
    @ColumnInfo(name = "mal_id")
    val malId: Int?,

    /**
     * Reading status string synced from AniList.
     * Values: "CURRENT", "COMPLETED", "PAUSED", "DROPPED", "PLANNING", "REPEATING"
     * Null if not linked or not yet fetched.
     */
    @ColumnInfo(name = "reading_status")
    val readingStatus: String?,

    /** Unix timestamp (ms) when this source was added. */
    @ColumnInfo(name = "added_at")
    val addedAt: Long,

    /**
     * Unix timestamp (ms) of the last time the user opened the reader.
     * Used to sort the recent/history tab.
     */
    @ColumnInfo(name = "last_read_at")
    val lastReadAt: Long?,
)
