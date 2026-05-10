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
 * Parser for MangaHub (mangahub.io).
 *
 * MangaHub is one of the largest English manga aggregators.
 * It uses a Bootstrap + custom PHP layout distinct from any other covered CMS.
 *
 * URL patterns:
 *   Browse : {baseUrl}/search/page/N?q=&genre=all&order={sort}
 *   Search : {baseUrl}/search/page/N?q={query}&genre=all&order=latest
 *   Detail : {baseUrl}/manga/{slug}
 *   Chapter: {baseUrl}/chapter/{slug}/chapter-{number}
 *
 * Fingerprint: "mangahub" in HTML  OR  ".manga-list" + ".media-heading" elements.
 */
class MangaHubHtmlParser(
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
        val doc = runCatching { fetchDocument("$baseUrl/search/page/1?q=&genre=all&order=latest") }
            .getOrNull() ?: return emptySet()
        val tags = mutableSetOf<MangaTag>()
        doc.select("select[name=genre] option, a[href*=genre=]").forEach { el ->
            val key = if (el.tagName() == "option") el.attr("value")
                      else el.attr("href").substringAfter("genre=").substringBefore("&")
            val title = el.text().trim()
            if (key.isNotEmpty() && key != "all" && title.isNotEmpty())
                tags += MangaTag(title = title, key = key, source = customSource)
        }
        return tags
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        return runCatching {
            val doc = fetchDocument(pageUrl)

            val title = doc.selectFirst("h1, .manga-title, ._3D1-H")
                ?.text()?.trim() ?: manga.title

            val coverImg = doc.selectFirst(".mangahub-cover img, .manga-cover img, .media-left img, .thumbnail")
            val coverUrl = (coverImg?.attr("src")?.takeIf { it.isNotEmpty() }
                ?: coverImg?.attr("data-src")
                ?: manga.coverUrl).fixProtocol()

            val description = doc.selectFirst("._editContent, .manga-description, p.summary, div.media-body p")
                ?.text()?.trim()

            val statusText = doc.select(".media-body p, .info-item")
                .firstOrNull { it.text().contains("Status", ignoreCase = true) }
                ?.text()?.lowercase()
            val state = when {
                statusText == null            -> MangaState.ONGOING
                "complet" in statusText       -> MangaState.FINISHED
                "hiatus"  in statusText       -> MangaState.PAUSED
                "cancel"  in statusText       -> MangaState.ABANDONED
                else                          -> MangaState.ONGOING
            }

            val slug = manga.url.trimStart('/').substringAfterLast('/')
            val chapters = loadChapterList(doc, slug)

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
            // MangaHub chapter reader serves images directly in img.manga-page
            val images = doc.select("img.manga-page, .chapter-images img, #mangaImages img")
            images.mapIndexedNotNull { i, img ->
                val url = (img.attr("src").takeIf { it.isNotEmpty() }
                    ?: img.attr("data-src")).trim().fixProtocol()
                if (url.isEmpty() || url.contains("loading")) null
                else MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun browseList(offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val sort = when (order) {
            SortOrder.POPULARITY -> "trending"
            SortOrder.RATING     -> "rating"
            SortOrder.NEWEST     -> "new-manga"
            else                 -> "latest"
        }
        val url = "$baseUrl/search/page/$page?q=&genre=all&order=$sort"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun browseByGenre(genreKey: String, offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val url  = "$baseUrl/search/page/$page?q=&genre=$genreKey&order=latest"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val page    = offset / PAGE_SIZE + 1
        val url     = "$baseUrl/search/page/$page?q=$encoded&genre=all&order=latest"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun parseMangaListPage(doc: Document): List<Manga> {
        // Two layouts: grid cards and list items — handle both
        val items = doc.select("li._287W_, li.list-group-item, .item.item-lg, .col-md-3.item")
            .ifEmpty { doc.select("div.row div[class*=col] a[href*=/manga/]").map { it.parent() ?: it } }
        return items.mapNotNull { parseMangaItem(it) }
    }

    private fun parseMangaItem(el: Element): Manga? {
        val anchor  = el.selectFirst("a[href*='/manga/']") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null

        val title = el.selectFirst("h4, h3, p.media-heading, .item-title, .manga-name")
            ?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("src")?.takeIf { it.isNotEmpty() }
            ?: coverImg?.attr("data-src") ?: "").fixProtocol()

        return buildManga(title, pageUrl, coverUrl)
    }

    private fun loadChapterList(doc: Document, slug: String): List<MangaChapter> {
        val rows = doc.select(
            "table.chapter-table tr, ._2v9Da tr, .tab-content ul li, " +
            "#chapterList li, .chapter-list-wrapper li"
        )
        return rows.mapIndexedNotNull { i, el ->
            val a = el.selectFirst("a[href*=chapter]") ?: return@mapIndexedNotNull null
            val url = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val rawTitle = el.selectFirst("span.chapter-title, .chapter-name")?.text()?.trim()
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
