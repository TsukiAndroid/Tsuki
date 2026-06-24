package io.github.landwarderer.futon.browsersource.data

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Records reading history when chapters are opened from Browser Sources.
 * Delegates persistence to [BrowserSourceRepository].
 *
 * Usage: call [recordChapterOpened] whenever the user taps
 * "📖 Open in Tsuki Reader" from a Browser Source browser.
 */
@Singleton
class BrowserSourceHistoryTracker @Inject constructor(
    private val repository: BrowserSourceRepository,
) {

    /**
     * Records that a chapter was opened in the native reader from a Browser Source.
     *
     * @param sourceId     The ID of the [CustomSource] with type BROWSER_SOURCE.
     * @param sourceName   Display name of the source.
     * @param chapterUrl   The full URL of the chapter page.
     * @param mangaTitle   Title extracted from og:title or page title.
     * @param mangaCoverUrl Cover image URL from og:image, or null.
     * @param chapterTitle Chapter title (often the same as mangaTitle on reader pages).
     */
    fun recordChapterOpened(
        sourceId: Long,
        sourceName: String,
        chapterUrl: String,
        mangaTitle: String,
        mangaCoverUrl: String?,
        chapterTitle: String,
    ) {
        val entry = BrowserSourceRepository.BrowserHistoryEntry(
            sourceId = sourceId,
            sourceName = sourceName,
            chapterUrl = chapterUrl,
            mangaTitle = mangaTitle.ifBlank { chapterUrl },
            mangaCoverUrl = mangaCoverUrl,
            chapterTitle = chapterTitle.ifBlank { mangaTitle.ifBlank { "Chapter" } },
            timestamp = System.currentTimeMillis(),
            isRead = false,
        )
        repository.saveHistoryEntry(entry)
        Log.d(TAG, "Recorded: '$mangaTitle' @ $chapterUrl")
    }

    /** Marks a chapter URL as read (called after native reader is opened). */
    fun markRead(sourceId: Long, chapterUrl: String) {
        repository.markChapterRead(sourceId, chapterUrl)
    }

    /** Returns all chapter URLs that have been read for this source. */
    fun getReadUrls(sourceId: Long): Set<String> =
        repository.getReadChapterUrls(sourceId)

    /** Returns the full history for a source, newest-first. */
    fun getHistory(sourceId: Long): List<BrowserSourceRepository.BrowserHistoryEntry> =
        repository.loadHistoryForSource(sourceId)

    fun clearHistory(sourceId: Long) {
        repository.clearHistoryForSource(sourceId)
        Log.d(TAG, "Cleared history for source $sourceId")
    }

    companion object {
        private const val TAG = "BrowserSourceHistory"
    }
}
