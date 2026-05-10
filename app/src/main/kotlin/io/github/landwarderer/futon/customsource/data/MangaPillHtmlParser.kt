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
 * Parser for MangaPill (mangapill.com).
 *
 * MangaPill is a fast, clean manga aggregator. Its chapter reader is
 * distinctive: every page image is an `img.js-page` element with a
 * `data-src` attribute — the most reliable fingerprint for this CMS.
 *
 * URL patterns:
 *   Browse : {baseUrl}/manga?q=&type=&status=&page=N
 *   Search : {baseUrl}/search?q={query}
 *   Detail : {baseUrl}/manga/{id}/{slug}
 *   Chapter: {baseUrl}/chapters/{chapter-id}/{chapter-slug}
 *
 * Fingerprint: "mangapill" in HTML  OR  img.js-page elements in a chapter page.
 */
class MangaPillHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val tag   = filter?.tags?.firstOrNull()
        return when {
            !query.isNullOrBlank() -> searchManga(query)
            tag != null            -> browseByGenre(tag.key, offset)
            else                   -> browseList(offset, order)
        }
    }

    fun getGenres(): Set<MangaTag> {
        val doc = runCatching { fetchDocument("$baseUrl/manga") }.getOrNull() ?: return emptySet()
        val tags = mutableSetOf<MangaTag>()
        // Genre/type filter links rendered as <a> or <select option> on browse page
        doc.select("select[name=genre] option, a[href*=genre=], .genres a").forEach { el ->
            val key = if (el.tagName() == "option") el.attr("value")
                      else el.attr("href").substringAfter("genre=").substringBefore("&")
            val title = el.text().trim()
            if (key.isNotEmpty() && key != "0" && title.isNotEmpty())
                tags += MangaTag(title = title, key = key, source = customSource)
        }
        return tags
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        return runCatching {
            val doc = fetchDocument(pageUrl)

            // Title: first h1 or the og:title meta
            val title = doc.selectFirst("h1.font-black, h1")
                ?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
                ?: manga.title

            // Cover: first img inside the info column, or og:image
            val coverImg = doc.selectFirst(".order-first img, div[class*=cover] img")
            val coverUrl = (coverImg?.attr("data-src")?.takeIf { it.isNotEmpty() }
                ?: coverImg?.attr("src")
                ?: doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?: manga.coverUrl).fixProtocol()

            // Description
            val description = doc.selectFirst("#story, div[class*=description], p.summary")
                ?.text()?.trim()

            // Status badges — rendered as small bordered spans
            val statusText = doc.select("div[class*=border] span, .status, .badge")
                .firstOrNull { it.text().trim().let { t -> t == "Ongoing" || t == "Completed" || t == "Hiatus" } }
                ?.text()?.lowercase()
                ?: doc.selectFirst("meta[property*=status]")?.attr("content")?.lowercase()
            val state = when {
                statusText == null            -> MangaState.ONGOING
                "complet" in statusText       -> MangaState.FINISHED
                "hiatus"  in statusText       -> MangaState.PAUSED
                "cancel"  in statusText       -> MangaState.ABANDONED
                else                          -> MangaState.ONGOING
            }

            // Chapters — each is an <a> link in the #chapters container
            val chapters = doc.select("#chapters a[href*='/chapters/']")
                .mapIndexedNotNull { i, a -> chapterFromAnchor(a, i) }
                .reversed()

            manga.copy(
                title       = title,
                coverUrl    = coverUrl,
                largeCoverUrl = coverUrl,
                description = description,
                state       = state,
                chapters    = chapters,
            )
        }.getOrElse { manga }
    }

    fun getPages(chapter: MangaChapter): List<MangaPage> {
        return runCatching {
            val doc = fetchDocument(chapter.url)
            // MangaPill's distinctive reader: every page is img.js-page[data-src]
            doc.select("img.js-page[data-src], img[data-src][class*=page]")
                .mapIndexedNotNull { i, img ->
                    val url = img.attr("data-src").trim().fixProtocol()
                    if (url.isEmpty()) null
                    else MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
                }
        }.getOrElse { emptyList() }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun browseList(offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        // MangaPill browse has type/status filters; default is all
        val url = "$baseUrl/manga?q=&type=&status=&page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun browseByGenre(genreKey: String, offset: Int): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val url  = "$baseUrl/manga?genre=$genreKey&page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun searchManga(query: String): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val url     = "$baseUrl/search?q=$encoded"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun parseMangaListPage(doc: Document): List<Manga> {
        // Cards are flex/grid divs — each contains exactly one <a> to the manga page
        val items = doc.select("div.grid > div, .manga-list > div, div[class*=grid] > div")
            .filter { it.selectFirst("a[href*='/manga/']") != null }
            .ifEmpty {
                doc.select("a[href*='/manga/']")
                    .filter { it.attr("href").matches(Regex("/manga/\\d+/.+")) }
            }
        return items.mapNotNull { parseMangaItem(it) }
    }

    private fun parseMangaItem(el: Element): Manga? {
        val anchor  = el.selectFirst("a[href*='/manga/']") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null

        val title = el.select("div").lastOrNull { it.text().trim().isNotEmpty() && it.children().isEmpty() }
            ?.text()?.trim()
            ?: el.selectFirst("div[class*=font], h3, h2")?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: coverImg?.attr("src") ?: "").fixProtocol()

        return buildManga(title, pageUrl, coverUrl)
    }

    private fun chapterFromAnchor(a: Element, fallbackIndex: Int): MangaChapter? {
        val url = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val rawTitle = a.text().trim().ifEmpty { "Chapter ${fallbackIndex + 1}" }
        val number   = CHAPTER_NUMBER_RE.find(rawTitle)?.groupValues?.getOrNull(1)
            ?.toFloatOrNull() ?: (fallbackIndex + 1).toFloat()
        return MangaChapter(
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
        this == null        -> ""
        startsWith("//")    -> "https:$this"
        else                -> this
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
