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
 * Parser for TruyenQQ (truyenqq.com.vn) — the second-largest Vietnamese
 * manga aggregator, with a completely distinct CMS from NetTruyen.
 *
 * TruyenQQ uses a different URL scheme (`.html` suffixes) and unique
 * class names (.book_avatar, .book_name, .listChapters) that make it
 * easy to fingerprint without false-positives on NetTruyen.
 *
 * Also covers: truyenqqviet.com, truyenqqpro.com, and mirror domains
 * preserving the same `.html` URL convention.
 *
 * URL patterns:
 *   Browse  : {baseUrl}/truyen-tranh.html?page=N
 *   Search  : {baseUrl}/tim-kiem.html?keyword={query}&page=N
 *   Detail  : {baseUrl}/truyen-tranh/{slug}.html
 *   Chapter : {baseUrl}/truyen-tranh/{slug}/{chapter}.html
 *
 * Fingerprint: "truyenqq" in HTML  OR  ".book_avatar" + ".listChapters".
 */
class TruyenQQHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val tag   = filter?.tags?.firstOrNull()
        return when {
            !query.isNullOrBlank() -> searchManga(query, offset)
            tag != null            -> browseByGenre(tag.key, offset)
            else                   -> browseList(offset, order)
        }
    }

    fun getGenres(): Set<MangaTag> {
        val doc = runCatching { fetchDocument("$baseUrl/truyen-tranh.html") }.getOrNull() ?: return emptySet()
        val tags = mutableSetOf<MangaTag>()
        doc.select("a[href*=/the-loai/], .nav-genre a, ul.genres-filter a").forEach { a ->
            val href  = a.attr("href").trimEnd('/').removeSuffix(".html")
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

            val title = doc.selectFirst("h1.book_other_name, .book_detail h1, h1.comic-title")
                ?.text()?.trim()
                ?: doc.selectFirst("h1")?.text()?.trim()
                ?: manga.title

            val coverImg = doc.selectFirst(".book_avatar img, .comic-cover img, .left-info img")
            val coverUrl = (coverImg?.attr("src")?.takeIf { it.isNotEmpty() }
                ?: coverImg?.attr("data-src")
                ?: manga.coverUrl).fixProtocol()

            val description = doc.selectFirst(".book_detail .detail_item .content, .summary p, .manga-desc")
                ?.text()?.trim()

            val statusText = doc.select(".book_detail .detail_item, .info-item")
                .firstOrNull { it.text().contains("Tình trạng", ignoreCase = true) || it.text().contains("Status", ignoreCase = true) }
                ?.selectFirst("span, p")?.text()?.lowercase()
            val state = when {
                statusText == null               -> MangaState.ONGOING
                "hoàn thành" in statusText
                    || "complet" in statusText   -> MangaState.FINISHED
                "tạm dừng"    in statusText
                    || "hiatus"  in statusText   -> MangaState.PAUSED
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
            val images = doc.select(".chapter-content img, #content-reading img, .reading img")
            images.mapIndexedNotNull { i, img ->
                val url = (img.attr("data-src").takeIf { it.isNotEmpty() }
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
            SortOrder.POPULARITY -> "&sort=5"
            SortOrder.NEWEST     -> "&sort=1"
            else                 -> ""
        }
        val url = "$baseUrl/truyen-tranh.html?page=$page$sort"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun browseByGenre(genreKey: String, offset: Int): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val url  = "$baseUrl/the-loai/$genreKey.html?page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val page    = offset / PAGE_SIZE + 1
        val url     = "$baseUrl/tim-kiem.html?keyword=$encoded&page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun parseMangaListPage(doc: Document): List<Manga> {
        val items = doc.select(".list_grid_out .list_grid li, .book_grid li, .list-truyen li")
            .ifEmpty { doc.select("li.item, div.book-item") }
        return items.mapNotNull { parseMangaItem(it) }
    }

    private fun parseMangaItem(el: Element): Manga? {
        val anchor  = el.selectFirst("a[href*=/truyen-tranh/]") ?: el.selectFirst("a") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null

        val title = el.selectFirst("p.book_name, h3.book-name, .title a, h2 a")?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        val coverImg = el.selectFirst(".book_avatar img, .book-cover img, img")
        val coverUrl = (coverImg?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: coverImg?.attr("src") ?: "").fixProtocol()

        return buildManga(title, pageUrl, coverUrl)
    }

    private fun loadChapterList(doc: Document): List<MangaChapter> {
        val rows = doc.select(".listChapters li, .chapter-list li, ul.list-chapter li")
        return rows.mapIndexedNotNull { i, el ->
            val a = el.selectFirst("a") ?: return@mapIndexedNotNull null
            val url = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val rawTitle = el.selectFirst("span, .chapter-name")?.text()?.trim()
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
