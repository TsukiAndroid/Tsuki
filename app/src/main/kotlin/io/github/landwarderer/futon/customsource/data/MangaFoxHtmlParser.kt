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
 * Parser for FanFox.net (formerly MangaFox) and sites sharing its CMS.
 *
 * Covers: fanfox.net, mangafox.me, and mirror sites.
 * FanFox uses a classic PHP CMS with /manga/ listing and per-page chapter reader.
 *
 * URL patterns:
 *   Browse : {baseUrl}/manga/?direction=&type=&rating=&status=&sort={sort}&page=N
 *   Search : {baseUrl}/search?q={query}&page=N
 *   Detail : {baseUrl}/manga/{slug}/
 *   Chapter: {baseUrl}/manga/{slug}/v{vol}/c{chapter}/{page}.html
 *
 * Fingerprint: "fanfox" OR "mangafox" OR ".list-2 .item" + ".listing"
 */
class MangaFoxHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val tag = filter?.tags?.firstOrNull()
        return when {
            !query.isNullOrBlank() -> searchManga(query, offset)
            tag != null -> browseByGenre(tag.key, offset, order)
            else -> browseList(offset, order)
        }
    }

    fun getGenres(): Set<MangaTag> {
        val doc = runCatching { fetchDocument("$baseUrl/manga/") }.getOrNull() ?: return emptySet()
        val tags = mutableSetOf<MangaTag>()
        doc.select("#genres_field a, .genres a, a[href*=/genre/]").forEach { a ->
            val href = a.attr("href").trimEnd('/')
            val key = href.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return@forEach
            val title = a.text().trim().ifEmpty { return@forEach }
            tags += MangaTag(title = title, key = key, source = customSource)
        }
        return tags
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        return runCatching {
            val doc = fetchDocument(pageUrl)

            val title = doc.selectFirst(".detail-info-right h1, .manga-title, .title")
                ?.text()?.trim() ?: manga.title

            val coverImg = doc.selectFirst(".detail-info-cover img, .manga-cover img, .cover img")
            val coverUrl = (coverImg?.attr("src") ?: manga.coverUrl).fixProtocol()

            val description = doc.selectFirst(".detail-info-right-content p, .intro, .summary")
                ?.text()?.trim()

            val statusText = doc.select(".detail-info-right-say span")
                .firstOrNull { it.text().contains("Status", ignoreCase = true) }
                ?.nextElementSibling()?.text()?.lowercase()
                ?: doc.selectFirst("[class*=status]")?.text()?.lowercase()
            val state = when {
                statusText == null -> MangaState.ONGOING
                "complet" in statusText || "finished" in statusText -> MangaState.FINISHED
                "hiatus" in statusText -> MangaState.PAUSED
                "cancel" in statusText -> MangaState.ABANDONED
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

            // FanFox paginates per image; check if there's a select for page count
            val pageUrls = doc.select("select.m option").map { it.attr("value") }
                .filter { it.endsWith(".html") || it.contains("/c") }
            if (pageUrls.size > 1) {
                return@runCatching pageUrls.mapIndexedNotNull { i, pageUrl ->
                    val fullUrl = if (pageUrl.startsWith("http")) pageUrl else "$baseUrl$pageUrl"
                    val imgUrl = runCatching {
                        val pageDoc = fetchDocument(fullUrl)
                        (pageDoc.selectFirst("#viewer img, .reader-main img, img#image")
                            ?.attr("data-original") ?: pageDoc.selectFirst("#viewer img, .reader-main img, img#image")
                            ?.attr("src") ?: "").fixProtocol()
                    }.getOrElse { "" }
                    if (imgUrl.isEmpty()) null
                    else MangaPage(id = chapter.id * 1000L + i, url = imgUrl, preview = null, source = customSource)
                }
            }

            // Fallback: direct images on page
            doc.select("#viewer img, .reader img, .chapter-container img").mapIndexedNotNull { i, img ->
                val url = (img.attr("data-original").takeIf { it.isNotEmpty() }
                    ?: img.attr("src")).trim().fixProtocol()
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
        val url = "$baseUrl/manga/?sort=$sort&page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun browseByGenre(genreKey: String, offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val url = "$baseUrl/genre/$genreKey/?page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val page = offset / PAGE_SIZE + 1
        val url = "$baseUrl/search?q=$encoded&page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun parseMangaListPage(doc: Document): List<Manga> {
        val items = doc.select("ul.manga-list li, .list-2 .item, .manga-list-4 li")
            .ifEmpty { doc.select("li.list-2-item, .manga-item") }
        return items.mapNotNull { parseMangaItem(it) }
    }

    private fun parseMangaItem(el: Element): Manga? {
        val anchor = el.selectFirst("a[href*=/manga/]") ?: el.selectFirst("a") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val title = el.selectFirst("p.title, h2, h3, .title")?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null
        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("data-original") ?: coverImg?.attr("src") ?: "").fixProtocol()
        return buildManga(title, pageUrl, coverUrl)
    }

    private fun loadChapterList(doc: Document, pageUrl: String): List<MangaChapter> {
        val rows = doc.select("ul.detail-main-list li, .chapter-list li, ul.volume-list li")
        return rows.mapIndexedNotNull { i, el ->
            val a = el.selectFirst("a[href*=/c]") ?: el.selectFirst("a") ?: return@mapIndexedNotNull null
            val url = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val rawTitle = el.selectFirst(".title3, .detail-main-list-main p, .chapter-name")?.text()?.trim()
                ?: a.text().trim()
                    .ifEmpty { "Chapter ${i + 1}" }
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
        private const val PAGE_SIZE = 20
        private const val USER_AGENT = "Tsuki/1.0 (Android)"
        private val CHAPTER_NUMBER_RE = Regex("""(?:[Cc]h(?:apter)?\.?)\s*([\d.]+)""")

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
