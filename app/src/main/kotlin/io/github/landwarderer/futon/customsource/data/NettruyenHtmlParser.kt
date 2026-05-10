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
 * Parser for NetTruyen and its mirror network — the largest Vietnamese-language
 * manga platform with millions of daily readers.
 *
 * Covers all active mirror domains:
 *   nettruyenvn.com, nettruyenco.vn, nettruyenco.com, nettruyento.com,
 *   nettruyenmax.com, nettruyenplus.com, and any domain containing "nettruyen".
 *
 * The CMS is a custom PHP system with a distinctive ".ModuleContent" wrapper
 * and ".reading-detail" chapter reader container.
 *
 * URL patterns:
 *   Browse  : {baseUrl}/tim-truyen?status=-1&sort=15&page=N
 *   Search  : {baseUrl}/tim-truyen?keyword={query}&page=N
 *   Detail  : {baseUrl}/truyen-tranh/{slug}
 *   Chapter : {baseUrl}/{chapter-path}  (stored as relative URL)
 *
 * Fingerprint: "nettruyen" in HTML  OR  ".ModuleContent" + ".reading-detail".
 */
class NettruyenHtmlParser(
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
        val doc = runCatching { fetchDocument("$baseUrl/tim-truyen") }.getOrNull() ?: return emptySet()
        val tags = mutableSetOf<MangaTag>()
        doc.select("a[href*=/the-loai/], .genre-item a, .list-categories a").forEach { a ->
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

            val title = doc.selectFirst("h1.title-detail, .series-name h1, .manga-name")
                ?.text()?.trim() ?: manga.title

            val coverImg = doc.selectFirst(".col-image img, .detail-info img, .book-avatar img")
            val coverUrl = (coverImg?.attr("src")?.takeIf { it.isNotEmpty() }
                ?: coverImg?.attr("data-src")
                ?: manga.coverUrl).fixProtocol()

            val description = doc.selectFirst(".detail-content p, .summary-content, .manga-desc")
                ?.text()?.trim()

            val statusText = doc.selectFirst(".status span, li:contains(Tình trạng) span")
                ?.text()?.lowercase()
                ?: doc.select(".col-info li")
                    .firstOrNull { it.text().contains("Tình trạng", ignoreCase = true) }
                    ?.selectFirst("span")?.text()?.lowercase()
            val state = when {
                statusText == null               -> MangaState.ONGOING
                "hoàn thành" in statusText
                    || "complet" in statusText   -> MangaState.FINISHED
                "tạm dừng"    in statusText
                    || "hiatus" in statusText    -> MangaState.PAUSED
                "drop"        in statusText      -> MangaState.ABANDONED
                else                             -> MangaState.ONGOING
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
            // NetTruyen: images in .reading-detail .page-chapter
            val images = doc.select(".reading-detail .page-chapter img, .reading-content img")
            images.mapIndexedNotNull { i, img ->
                val url = (img.attr("data-src").takeIf { it.isNotEmpty() }
                    ?: img.attr("data-original").takeIf { it.isNotEmpty() }
                    ?: img.attr("src")).trim().fixProtocol()
                if (url.isEmpty() || url.contains("data:image")) null
                else MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun browseList(offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val sort = when (order) {
            SortOrder.POPULARITY -> "10"
            SortOrder.RATING     -> "10"
            SortOrder.NEWEST     -> "0"
            else                 -> "15"   // 15 = latest update (default)
        }
        val url = "$baseUrl/tim-truyen?status=-1&sort=$sort&page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun browseByGenre(genreKey: String, offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val url  = "$baseUrl/the-loai/$genreKey?page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val page    = offset / PAGE_SIZE + 1
        val url     = "$baseUrl/tim-truyen?keyword=$encoded&page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun parseMangaListPage(doc: Document): List<Manga> {
        // NetTruyen: items inside .ModuleContent .items .item
        val items = doc.select(".ModuleContent .items .item, .items-slide .item, .list-manga .item")
            .ifEmpty { doc.select("figure.clearfix, article.item") }
        return items.mapNotNull { parseMangaItem(it) }
    }

    private fun parseMangaItem(el: Element): Manga? {
        val anchor  = el.selectFirst("a[href*=/truyen-tranh/]") ?: el.selectFirst("a") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null

        val title = el.selectFirst("h3 a, h2 a, figure figcaption h3, .title")?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: coverImg?.attr("src") ?: "").fixProtocol()

        return buildManga(title, pageUrl, coverUrl)
    }

    private fun loadChapterList(doc: Document): List<MangaChapter> {
        val rows = doc.select("#nt_listchapter li, .list-chapter li, ul.chapter-list li")
        return rows.mapIndexedNotNull { i, el ->
            val a = el.selectFirst("a") ?: return@mapIndexedNotNull null
            val url = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val rawTitle = a.text().trim().ifEmpty { "Chapter ${i + 1}" }
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
        // Matches "Chapter 1", "Ch.1", "Chương 1" (Vietnamese)
        private val CHAPTER_NUMBER_RE = Regex("""(?:[Cc]hapter|[Cc]h(?:ương)?\.?)\s*([\d.]+)""")

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
