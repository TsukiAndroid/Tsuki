package io.github.landwarderer.futon.customsource.data

import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.concurrent.TimeUnit

/**
 * Parser for sites using the MangaFire / MangaRead-style layout.
 *
 * MangaFire (mangafire.to) is one of the fastest-growing manga sites.
 * This parser also handles similar sites: MangaRead, MangaCool, etc.
 *
 * Recognisable by:
 *   - A clean card-grid layout with .manga-poster covers
 *   - /manga listing with filter sidebar
 *   - Episode list at .ep-item in the detail page
 *   - Lazy-loaded reader images inside #chapter-images
 *
 * URL patterns:
 *   List   : {baseUrl}/manga?sort={order}&page=N
 *   Search : {baseUrl}/filter?keyword={q}&page=N
 *   Detail : {baseUrl}/manga/{slug}.{id}
 *   Chapter: {baseUrl}/read/{slug}.{id}/en/chapter-N
 */
class MangaFireHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        return if (!query.isNullOrBlank()) {
            searchManga(query, offset)
        } else {
            browseList(offset, order)
        }
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        return runCatching {
            val doc = fetchDocument(pageUrl)

            val title = doc.selectFirst("h1.name, .manga-name h1, h1[itemprop=name]")
                ?.text()?.trim() ?: manga.title

            val coverImg = doc.selectFirst(".manga-poster img, .poster img, .book-poster img")
            val coverUrl = (coverImg?.attr("src") ?: manga.coverUrl).fixProtocol()

            val descEl = doc.selectFirst(".description, .summary-content, .synopsis")
            val description = descEl?.select("p")?.joinToString("\n") { it.text() }?.trim()
                ?: descEl?.text()?.trim()

            val statusText = doc.select(".anisc-info .item-title")
                .firstOrNull { it.text().contains("status", ignoreCase = true) }
                ?.nextElementSibling()?.text()?.lowercase()
                ?: doc.selectFirst(".status")?.text()?.lowercase()
            val state = when {
                statusText == null -> MangaState.ONGOING
                "complet" in statusText || "finished" in statusText -> MangaState.FINISHED
                "hiatus" in statusText -> MangaState.PAUSED
                "cancel" in statusText || "discontinu" in statusText -> MangaState.ABANDONED
                else -> MangaState.ONGOING
            }

            val chapters = loadChapterList(doc, pageUrl)

            manga.copy(
                title = title,
                coverUrl = coverUrl,
                largeCoverUrl = coverUrl,
                description = description,
                state = state,
                chapters = chapters,
            )
        }.getOrElse { manga }
    }

    fun getPages(chapter: MangaChapter): List<MangaPage> {
        return runCatching {
            val doc = fetchDocument(chapter.url)
            val images = doc.select(
                "#chapter-images img, .chapter-images img, " +
                ".reading-content img, .chapter-view img, " +
                "img[class*=chapter-img], img[data-index]"
            )
            images.mapIndexedNotNull { index, img ->
                val url = (img.attr("data-src").takeIf { it.isNotEmpty() }
                    ?: img.attr("data-lazy").takeIf { it.isNotEmpty() }
                    ?: img.attr("src")).trim().fixProtocol()
                if (url.isEmpty() || url.length < 10) null
                else MangaPage(id = chapter.id * 1000L + index, url = url, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun browseList(offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val sort = when (order) {
            SortOrder.POPULARITY -> "most_viewed"
            SortOrder.RATING     -> "top_rated"
            SortOrder.NEWEST     -> "newly_added"
            else                 -> "latest_updated"
        }
        val urls = listOf(
            "$baseUrl/manga?sort=$sort&page=$page",
            "$baseUrl/browse?sort=$sort&page=$page",
        )
        for (url in urls) {
            val result = runCatching { parseListPage(fetchDocument(url)) }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        return emptyList()
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val page = offset / PAGE_SIZE + 1
        val urls = listOf(
            "$baseUrl/filter?keyword=$encoded&page=$page",
            "$baseUrl/search?q=$encoded&page=$page",
            "$baseUrl/?s=$encoded&paged=$page",
        )
        for (url in urls) {
            val result = runCatching { parseListPage(fetchDocument(url)) }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        return emptyList()
    }

    private fun parseListPage(doc: Document): List<Manga> {
        val items = doc.select(
            ".manga-poster, .unit .inner, .manga-item, " +
            "article.manga, .card.manga, .book-item"
        ).ifEmpty { doc.select(".item") }
        return items.mapNotNull { parseItem(it) }
    }

    private fun parseItem(el: Element): Manga? {
        val anchor = el.selectFirst("a[href*=/manga/], a") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val title = el.selectFirst(".manga-name, .name, h3, h2, .title")?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null
        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("data-src") ?: coverImg?.attr("src") ?: "").fixProtocol()
        return buildManga(title, pageUrl, coverUrl)
    }

    private fun loadChapterList(doc: Document, mangaUrl: String): List<MangaChapter> {
        val items = doc.select(
            ".ep-item, .chapter-item, .chapter-list li, " +
            "ul.chapter-list li, .chapters-list li"
        )
        return items.mapIndexedNotNull { i, el ->
            val a = el.selectFirst("a") ?: return@mapIndexedNotNull null
            val url = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val rawTitle = el.selectFirst(".title, .name, span")?.text()?.trim()
                ?: a.text().trim().ifEmpty { "Chapter ${i + 1}" }
            val number = CHAPTER_NUMBER_RE.find(rawTitle)?.groupValues?.getOrNull(1)
                ?.toFloatOrNull() ?: (i + 1).toFloat()
            MangaChapter(
                id = url.hashCode().toLong(),
                title = rawTitle,
                number = number,
                volume = 0,
                url = url,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = customSource,
            )
        }.reversed()
    }

    private fun fetchDocument(url: String): Document {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", baseUrl)
            .get().build()
        return httpClient.newCall(req).execute().use { resp ->
            Jsoup.parse(resp.body?.string() ?: "", url)
        }
    }

    private fun buildManga(title: String, pageUrl: String, coverUrl: String): Manga {
        val relativePath = runCatching {
            val uri = java.net.URI(pageUrl)
            uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
        }.getOrElse { pageUrl }
        return Manga(
            id = pageUrl.hashCode().toLong(),
            title = title,
            altTitles = emptySet(),
            url = relativePath,
            publicUrl = pageUrl,
            rating = 0f,
            contentRating = ContentRating.SAFE,
            coverUrl = coverUrl,
            tags = emptySet(),
            state = MangaState.ONGOING,
            authors = emptySet(),
            largeCoverUrl = coverUrl,
            description = null,
            chapters = null,
            source = customSource,
        )
    }

    private fun String?.fixProtocol(): String = when {
        this == null -> ""
        startsWith("//") -> "https:$this"
        else -> this
    }

    companion object {
        private const val PAGE_SIZE = 24
        private const val USER_AGENT = "Tsuki/1.0 (Android)"
        private val CHAPTER_NUMBER_RE = Regex("""(?:[Cc]hapter|[Cc]h\.?)\s*([\d.]+)""")
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
