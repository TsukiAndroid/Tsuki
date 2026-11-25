package io.github.landwarderer.futon.browsersource.data

import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import java.util.concurrent.ConcurrentHashMap

/**
 * Short-lived in-memory bridge between the browser and the native reader.
 * Page request headers are captured while the WebView session is alive so
 * protected image CDNs can be loaded by the reader with the same context.
 */
object BrowserSourcePageStore {

    private const val TTL_MS = 10 * 60 * 1_000L

    private data class Entry(
        val pages: List<MangaPage>,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private data class ChapterEntry(
        val chapter: MangaChapter,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private data class RequestContext(
        val headers: Map<String, String>,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val store = ConcurrentHashMap<Long, Entry>()
    private val chaptersByMangaId = ConcurrentHashMap<Long, ChapterEntry>()
    private val requestContexts = ConcurrentHashMap<Long, RequestContext>()

    /** Store pages and their optional per-image request headers. */
    fun put(
        chapterId: Long,
        pages: List<MangaPage>,
        headersByPageId: Map<Long, Map<String, String>> = emptyMap(),
    ) {
        evictStale()
        store[chapterId] = Entry(pages.toList())
        pages.forEach { page ->
            val headers = headersByPageId[page.id]
            if (!headers.isNullOrEmpty()) {
                requestContexts[page.id] = RequestContext(headers.toMap())
            }
        }
    }

    /** Keep the synthetic chapter available while ReaderActivity reloads Manga details. */
    fun putChapter(mangaId: Long, chapter: MangaChapter) {
        evictStale()
        chaptersByMangaId[mangaId] = ChapterEntry(chapter)
    }

    /** Restore the chapter lost by ParcelableManga when the reader reloads details. */
    fun getChapter(mangaId: Long): MangaChapter? {
        val entry = chaptersByMangaId[mangaId] ?: return null
        if (System.currentTimeMillis() - entry.createdAt > TTL_MS) {
            chaptersByMangaId.remove(mangaId, entry)
            return null
        }
        return entry.chapter
    }

    /** Retrieve and remove the pages for [chapterId]. */
    fun getAndClear(chapterId: Long): List<MangaPage> {
        val entry = store.remove(chapterId) ?: return emptyList()
        if (System.currentTimeMillis() - entry.createdAt > TTL_MS) return emptyList()
        return entry.pages
    }

    /** Returns headers captured for a page, or an empty map when none exist. */
    fun getRequestHeaders(pageId: Long): Map<String, String> {
        val entry = requestContexts[pageId] ?: return emptyMap()
        if (System.currentTimeMillis() - entry.createdAt > TTL_MS) {
            requestContexts.remove(pageId, entry)
            return emptyMap()
        }
        return entry.headers
    }

    fun has(chapterId: Long): Boolean = store.containsKey(chapterId)

    private fun evictStale() {
        val now = System.currentTimeMillis()
        store.entries.removeAll { (_, entry) -> now - entry.createdAt > TTL_MS }
        chaptersByMangaId.entries.removeAll { (_, entry) -> now - entry.createdAt > TTL_MS }
        requestContexts.entries.removeAll { (_, entry) -> now - entry.createdAt > TTL_MS }
    }
}
