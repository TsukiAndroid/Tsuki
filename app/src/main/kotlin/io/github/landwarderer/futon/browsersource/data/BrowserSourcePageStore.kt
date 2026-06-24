package io.github.landwarderer.futon.browsersource.data

import org.koitharu.kotatsu.parsers.model.MangaPage
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory store that bridges [BrowserSourceActivity] with
 * [io.github.landwarderer.futon.customsource.data.CustomMangaRepository].
 *
 * When the user taps "📖 Open in Tsuki Reader":
 *  1. [BrowserSourceActivity] collects image URLs from the WebView.
 *  2. It converts them to [MangaPage] objects and calls [put].
 *  3. It opens [io.github.landwarderer.futon.reader.ui.ReaderActivity] with a
 *     synthetic [org.koitharu.kotatsu.parsers.model.Manga] whose chapter ID
 *     matches the key used in step 2.
 *  4. The reader's [ChaptersLoader] calls
 *     [io.github.landwarderer.futon.customsource.data.CustomMangaRepository.getPages].
 *  5. That method calls [getAndClear] to retrieve and consume the stored pages.
 *
 * Entries are auto-expired after [TTL_MS] to avoid leaking memory if the
 * reader is never opened (e.g. user taps back before pages load).
 */
object BrowserSourcePageStore {

    private const val TTL_MS = 5 * 60 * 1_000L // 5 minutes

    private data class Entry(
        val pages: List<MangaPage>,
        val createdAt: Long = System.currentTimeMillis(),
    )

    private val store = ConcurrentHashMap<Long, Entry>()

    /** Store pages for [chapterId]. Overwrites any previous entry. */
    fun put(chapterId: Long, pages: List<MangaPage>) {
        evictStale()
        store[chapterId] = Entry(pages)
    }

    /**
     * Retrieve and remove the pages for [chapterId].
     * Returns an empty list if no entry exists or it has expired.
     */
    fun getAndClear(chapterId: Long): List<MangaPage> {
        val entry = store.remove(chapterId) ?: return emptyList()
        if (System.currentTimeMillis() - entry.createdAt > TTL_MS) return emptyList()
        return entry.pages
    }

    /** Returns true if pages are pending for [chapterId]. */
    fun has(chapterId: Long): Boolean = store.containsKey(chapterId)

    private fun evictStale() {
        val now = System.currentTimeMillis()
        store.entries.removeAll { (_, v) -> now - v.createdAt > TTL_MS }
    }
}
