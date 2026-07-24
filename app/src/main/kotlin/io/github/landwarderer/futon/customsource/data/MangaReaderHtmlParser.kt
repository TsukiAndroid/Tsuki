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
 * Parser for MangaReader.to and sites using the same layout.
 *
 * MangaReader.to uses a card-grid layout similar to streaming anime sites
 * (Zoro/9anime family CMS) but serves manga content.
 *
 * Also covers: MangaReader.cc, Mangaread.org, ReadMangaFree.net
 *
 * URL patterns:
 *   Browse : {baseUrl}/filter?sort={sort}&page=N
 *   Search : {baseUrl}/search?keyword={query}&page=N
 *   Detail : {baseUrl}/manga/{slug}
 *   Chapter: {baseUrl}/manga/{slug}/{chapter-id}
 *
 * Fingerprint: "mangareader" in HTML OR ".manga-poster" + ".manga-detail" + ".sort-name"
 */
class MangaReaderHtmlParser(
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
        val doc = runCatching { fetchDocument("$baseUrl/filter") }.getOrNull() ?: return emptySet()
        val tags = mutableSetOf<MangaTag>()
        doc.select(".checkbox input[name=genre], input[name=genres]").forEach { input ->
            val key = input.attr("value").takeIf { it.isNotEmpty() } ?: return@forEach
            val label = input.parent()?.selectFirst("label")?.text()?.trim()
                ?: input.parent()?.text()?.trim()?.ifEmpty { null }
                ?: key
            tags += MangaTag(title = label, key = key, source = customSource)
        }
        if (tags.isNotEmpty()) return tags
        doc.select("a[href*=/genre/], a[href*=genre=]").forEach { a ->
            val href = a.attr("href")
            val key = if (href.contains("genre=")) href.substringAfter("genre=").substringBefore("&")
                      else href.trimEnd('/').substringAfterLast('/')
            val title = a.text().trim().ifEmpty { return@forEach }
            if (key.isNotEmpty()) tags += MangaTag(title = title, key = key, source = customSource)
        }
        return tags
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        return runCatching {
            val doc = fetchDocument(pageUrl)

            val title = doc.selectFirst(".manga-name, .detail-info h2, h1.manga-title, .mi-title")
                ?.text()?.trim() ?: manga.title

            val coverImg = doc.selectFirst(".manga-poster img, .manga-cover img, .cover img")
            val coverUrl = (coverImg?.attr("src") ?: manga.coverUrl).fixProtocol()

            val description = doc.selectFirst(".description, .manga-desc, .synopsis-content p")
                ?.text()?.trim()

            val statusText = doc.select(".anisc-info .item-title")
                .firstOrNull { it.text().contains("status", ignoreCase = true) }
                ?.nextElementSibling()?.text()?.lowercase()
                ?: doc.selectFirst(".status .name")?.text()?.lowercase()
            val state = when {
                statusText == null -> MangaState.ONGOING
                "complet" in statusText || "finished" in statusText -> MangaState.FINISHED
                "hiatus" in statusText || "on hold" in statusText -> MangaState.PAUSED
                "cancel" in statusText || "discontinued" in statusText -> MangaState.ABANDONED
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
            // MangaReader stores image list in #wrapper or .ds-image containers
            val images = doc.select(
                "#wrapper img.ds-image, .chapter-imgs img, " +
                ".reading-content img, .container-reader-chapter img, " +
                "div[class*=chapter-image] img"
            )
            images.mapIndexedNotNull { i, img ->
                val url = (img.attr("data-url").takeIf { it.isNotEmpty() }
                    ?: img.attr("data-src").takeIf { it.isNotEmpty() }
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
            SortOrder.POPULARITY -> "most_viewed"
            SortOrder.RATING     -> "score"
            SortOrder.NEWEST     -> "recently_added"
            else                 -> "latest_updated"
        }
        val urls = listOf(
            "$baseUrl/filter?sort=$sort&page=$page",
            "$baseUrl/all-manga?page=$page&sort=$sort",
        )
        for (url in urls) {
            val result = runCatching { parseMangaListPage(fetchDocument(url)) }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        return emptyList()
    }

    private fun browseByGenre(genreKey: String, offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val url = "$baseUrl/filter?genre=$genreKey&page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val page = offset / PAGE_SIZE + 1
        val urls = listOf(
            "$baseUrl/search?keyword=$encoded&page=$page",
            "$baseUrl/search?q=$encoded&page=$page",
        )
        for (url in urls) {
            val result = runCatching { parseMangaListPage(fetchDocument(url)) }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        return emptyList()
    }

    private fun parseMangaListPage(doc: Document): List<Manga> {
        val items = doc.select(".manga-poster, .item.item-sap, .manga-item, .card.manga, .item-list li")
            .ifEmpty { doc.select("article, .item") }
        return items.mapNotNull { parseMangaItem(it) }
    }

    private fun parseMangaItem(el: Element): Manga? {
        val anchor = el.selectFirst("a[href*=/manga/]") ?: el.selectFirst("a") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val title = el.selectFirst(".manga-name, .sort-name, h3, h2, .title")?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null
        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("src") ?: coverImg?.attr("data-src") ?: "").fixProtocol()
        return buildManga(title, pageUrl, coverUrl)
    }

    private fun loadChapterList(doc: Document, pageUrl: String): List<MangaChapter> {
        val rows = doc.select(
            ".chapters-list li, .chapter-item, ul#x-list li, " +
            ".chapter-list-item, .list-chapter li"
        )
        return rows.mapIndexedNotNull { i, el ->
            val a = el.selectFirst("a[href*=/manga/]") ?: el.selectFirst("a") ?: return@mapIndexedNotNull null
            val url = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val rawTitle = el.selectFirst(".chapter-title, .chapter-name, span")?.text()?.trim()
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
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
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
        // FIX: use a real Chrome mobile UA — "Tsuki/1.0 (Android)" was rejected by anti-bot
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"
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
