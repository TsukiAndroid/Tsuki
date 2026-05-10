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
 * Parser for MangaHere (mangahere.cc) and sites running the Foxaholic CMS.
 *
 * MangaHere is one of the oldest large-scale manga aggregators still active.
 * Although it shares ancestry with FanFox/MangaFox, the Foxaholic CMS used
 * by mangahere.cc has meaningfully different selectors and URL structure.
 *
 * Covers: mangahere.cc, www.mangahere.cc, and Foxaholic-based mirrors.
 *
 * URL patterns:
 *   Browse : {baseUrl}/directory/?page=N
 *   Search : {baseUrl}/search?title={query}&page=N
 *   Detail : {baseUrl}/manga/{slug}/
 *   Chapter: {baseUrl}/manga/{slug}/{chapter}/ (paginated per image)
 *
 * Fingerprint: "mangahere" in HTML  OR  ".manga-list" + ".detail-main-list".
 */
class MangaHereHtmlParser(
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
        val doc = runCatching { fetchDocument("$baseUrl/directory/") }.getOrNull() ?: return emptySet()
        val tags = mutableSetOf<MangaTag>()
        doc.select("#genres_field a, .genres-list a, a[href*=/genre/]").forEach { a ->
            val href  = a.attr("href").trimEnd('/')
            val key   = href.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return@forEach
            val title = a.text().trim().ifEmpty { return@forEach }
            tags += MangaTag(title = title, key = key, source = customSource)
        }
        return tags
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        return runCatching {
            val doc = fetchDocument(pageUrl)

            val title = doc.selectFirst("p.detail-info-right-title-font, .detail-info h1, h1.manga-title")
                ?.text()?.trim() ?: manga.title

            val coverImg = doc.selectFirst(".detail-info-cover img, .manga-cover img")
            val coverUrl = (coverImg?.attr("src")?.takeIf { it.isNotEmpty() }
                ?: coverImg?.attr("data-src")
                ?: manga.coverUrl).fixProtocol()

            val description = doc.selectFirst("#fullcontent, .detail-info-right-content, .manga-description")
                ?.text()?.trim()

            val statusText = doc.select(".detail-info-right-say p, .info-item")
                .firstOrNull { it.text().contains("Status", ignoreCase = true) }
                ?.text()?.lowercase()
                ?: doc.selectFirst("p:contains(Ongoing), p:contains(Completed)")?.text()?.lowercase()
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

            // MangaHere paginates: one image per page URL.
            // Collect all sub-page URLs from the <select class="m"> dropdown.
            val pageOptions = doc.select("select.m option, .page-select option")
                .map { it.attr("value") }
                .filter { it.isNotEmpty() && it != "javascript:void(0)" }

            if (pageOptions.size > 1) {
                return@runCatching pageOptions.mapIndexedNotNull { i, pageRelUrl ->
                    val fullUrl = if (pageRelUrl.startsWith("http")) pageRelUrl
                                  else "$baseUrl$pageRelUrl"
                    val imgUrl = runCatching {
                        val pd = fetchDocument(fullUrl)
                        (pd.selectFirst("#image, img.manga-page, .reader-main-side img")
                            ?.attr("src") ?: "").fixProtocol()
                    }.getOrElse { "" }
                    if (imgUrl.isEmpty()) null
                    else MangaPage(id = chapter.id * 1000L + i, url = imgUrl, preview = null, source = customSource)
                }
            }

            // Fallback: images rendered directly on page
            doc.select("#image, img.manga-page, .reader-main-side img").mapIndexedNotNull { i, img ->
                val url = img.attr("src").trim().fixProtocol()
                if (url.isEmpty()) null
                else MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun browseList(offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val sort = when (order) {
            SortOrder.POPULARITY -> "views"
            SortOrder.RATING     -> "rating"
            SortOrder.NEWEST     -> "created"
            else                 -> "last_chapter_time"
        }
        val url = "$baseUrl/directory/?page=$page&sort=$sort"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun browseByGenre(genreKey: String, offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val url  = "$baseUrl/genre/$genreKey/?page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val page    = offset / PAGE_SIZE + 1
        val url     = "$baseUrl/search?title=$encoded&page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun parseMangaListPage(doc: Document): List<Manga> {
        val items = doc.select("ul.manga-list li, .manga-list-4 li, .manga-directory-list li")
            .ifEmpty { doc.select("div.manga-item, li.list-group-item") }
        return items.mapNotNull { parseMangaItem(it) }
    }

    private fun parseMangaItem(el: Element): Manga? {
        val anchor  = el.selectFirst("a[href*='/manga/']") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null

        val title = el.selectFirst("p.title, a.manga_info, h3, h2, .manga-name")?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: coverImg?.attr("src") ?: "").fixProtocol()

        return buildManga(title, pageUrl, coverUrl)
    }

    private fun loadChapterList(doc: Document): List<MangaChapter> {
        val rows = doc.select("ul.detail-main-list li, .chapter-list li, li.volume-list-item")
        return rows.mapIndexedNotNull { i, el ->
            val a = el.selectFirst("a[href*=/manga/]") ?: return@mapIndexedNotNull null
            val url = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val rawTitle = el.selectFirst("p.title3, .chapter-title, span")
                ?.text()?.trim()
                ?: a.text().trim()
                    .ifEmpty { "Chapter ${i + 1}" }
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
        private const val PAGE_SIZE  = 20
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
