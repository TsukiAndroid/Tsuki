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
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.concurrent.TimeUnit

/**
 * Parser for MangaOwl (mangaowl.net / mangaowl.io) and compatible mirrors.
 *
 * MangaOwl is a high-traffic manga aggregator with a clean, distinct PHP
 * layout. It uses a `.comic-item` card grid for browse and `#images` for
 * the chapter image list — both are reliable fingerprints.
 *
 * URL patterns:
 *   Browse  : {baseUrl}/list/{page}/hot  |  /list/{page}/new
 *   Search  : {baseUrl}/search?q={query}&p={page}
 *   Detail  : {baseUrl}/single/{id}/{slug}
 *   Chapter : {baseUrl}/read/{chapterId}
 *
 * Fingerprint: "mangaowl" in HTML  OR  ".comic-item" + "#images" in HTML.
 */
class MangaOwlHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val tag   = filter?.tags?.firstOrNull()
        return when {
            !query.isNullOrBlank() -> searchManga(query, offset)
            tag != null            -> browseByGenre(tag.key, offset, order)
            else                   -> browseList(offset, order)
        }
    }

    fun getGenres(): Set<MangaTag> {
        val doc = runCatching { fetchDocument("$baseUrl/list/1/hot") }.getOrNull() ?: return emptySet()
        val tags = mutableSetOf<MangaTag>()
        doc.select("a[href*=/genre/], .genre-list a, select.genre option").forEach { el ->
            val key = if (el.tagName() == "option") el.attr("value")
                      else el.attr("href").trimEnd('/').substringAfterLast('/')
            val title = el.text().trim()
            if (key.isNotEmpty() && key.length > 1 && title.isNotEmpty())
                tags += MangaTag(title = title, key = key, source = customSource)
        }
        return tags
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        return runCatching {
            val doc = fetchDocument(pageUrl)

            val title = doc.selectFirst(".story-title h2, h1.story-name, .comic-title h1")
                ?.text()?.trim() ?: manga.title

            val coverImg = doc.selectFirst(".book-cover img, .comic-cover img, .story-cover img")
            val coverUrl = (coverImg?.attr("src")?.takeIf { it.isNotEmpty() }
                ?: coverImg?.attr("data-src")
                ?: manga.coverUrl).fixProtocol()

            val description = doc.selectFirst(".story-detail p, .comic-description p, .summary-text")
                ?.text()?.trim()

            val statusText = doc.select(".story-info li, .comic-info li, .info-item")
                .firstOrNull { it.text().contains("Status", ignoreCase = true) }
                ?.text()?.lowercase()
            val state = when {
                statusText == null      -> MangaState.ONGOING
                "complet" in statusText -> MangaState.FINISHED
                "hiatus"  in statusText -> MangaState.PAUSED
                "cancel"  in statusText -> MangaState.ABANDONED
                else                    -> MangaState.ONGOING
            }

            val chapters = loadChapterList(doc)

            manga.copy(
                title         = title,
                coverUrl      = coverUrl,
                largeCoverUrl = coverUrl,
                description   = description,
                state         = state,
                chapters      = chapters,
            )
        }.getOrElse { manga }
    }

    fun getPages(chapter: MangaChapter): List<MangaPage> {
        return runCatching {
            val doc = fetchDocument(chapter.url)
            // MangaOwl chapter reader: all images in #images container
            val images = doc.select("#images img, .page-images img, .reading-content img")
            images.mapIndexedNotNull { i, img ->
                val url = (img.attr("data-src").takeIf { it.isNotEmpty() }
                    ?: img.attr("src")).trim().fixProtocol()
                if (url.isEmpty() || url.contains("data:image") || url.contains("loading")) null
                else MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun browseList(offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val sort = when (order) {
            SortOrder.POPULARITY -> "hot"
            SortOrder.NEWEST     -> "new"
            SortOrder.RATING     -> "hot"
            else                 -> "latest"
        }
        val urls = listOf(
            "$baseUrl/list/$page/$sort",
            "$baseUrl/list/$page/latest",
        )
        for (url in urls) {
            val result = runCatching { parseMangaListPage(fetchDocument(url)) }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        return emptyList()
    }

    private fun browseByGenre(genreKey: String, offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val url  = "$baseUrl/genre/$genreKey?p=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val page    = offset / PAGE_SIZE + 1
        val url     = "$baseUrl/search?q=$encoded&p=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun parseMangaListPage(doc: Document): List<Manga> {
        val items = doc.select(".comic-item, .story-item, .manga-item, div[class*=comic-card]")
            .ifEmpty { doc.select("li.book_list li, .grid-item") }
        return items.mapNotNull { parseMangaItem(it) }
    }

    private fun parseMangaItem(el: Element): Manga? {
        val anchor  = el.selectFirst("a[href*=/single/]") ?: el.selectFirst("a") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null

        val title = el.selectFirst("h2, h3, .comic-title, .story-title, p.title")?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: coverImg?.attr("src") ?: "").fixProtocol()

        return buildManga(title, pageUrl, coverUrl)
    }

    private fun loadChapterList(doc: Document): List<MangaChapter> {
        val rows = doc.select(".chapter-list li, ul.chapter_list li, .story-chapter-item")
        return rows.mapIndexedNotNull { i, el ->
            val a = el.selectFirst("a[href*=/read/]") ?: el.selectFirst("a") ?: return@mapIndexedNotNull null
            val url = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val rawTitle = el.selectFirst(".chapter-title, span, p")?.text()?.trim()
                ?: a.text().trim().ifEmpty { "Chapter ${i + 1}" }
            val number = CHAPTER_NUMBER_RE.find(rawTitle)?.groupValues?.getOrNull(1)
                ?.toFloatOrNull() ?: (i + 1).toFloat()
            MangaChapter(
                id         = url.hashCode().toLong(),
                title      = rawTitle,
                number     = number,
                volume     = 0,
                url        = url,
                scanlator  = null,
                uploadDate = 0L,
                branch     = null,
                source     = customSource,
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
        val relative = runCatching {
            val uri = java.net.URI(pageUrl)
            uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
        }.getOrElse { pageUrl }
        return Manga(
            id            = pageUrl.hashCode().toLong(),
            title         = title,
            altTitles     = emptySet(),
            url           = relative,
            publicUrl     = pageUrl,
            rating        = 0f,
            contentRating = ContentRating.SAFE,
            coverUrl      = coverUrl,
            tags          = emptySet(),
            state         = MangaState.ONGOING,
            authors       = emptySet(),
            largeCoverUrl = coverUrl,
            description   = null,
            chapters      = null,
            source        = customSource,
        )
    }

    private fun String?.fixProtocol(): String = when {
        this == null     -> ""
        startsWith("//") -> "https:$this"
        else             -> this
    }

    companion object {
        private const val PAGE_SIZE  = 24
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
