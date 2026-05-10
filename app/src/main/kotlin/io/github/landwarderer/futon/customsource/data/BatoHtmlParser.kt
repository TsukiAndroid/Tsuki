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
 * Parser for Bato.to (Batocomic / Comiko) and mirror sites.
 *
 * Bato.to is one of the largest manga/manhwa/manhua community hosts.
 * It renders server-side HTML with embedded JSON data for chapters/pages.
 *
 * URL patterns:
 *   Browse : {baseUrl}/browse?sort={sort}&page=N
 *   Search : {baseUrl}/search?word={query}&page=N
 *   Detail : {baseUrl}/series/{id}-{slug}
 *   Chapter: {baseUrl}/chapter/{id}
 *
 * Fingerprint: "bato.to" OR "batocomic" OR "comiko" OR
 *              ".item .item-text" cards in HTML
 */
class BatoHtmlParser(
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
        val doc = runCatching { fetchDocument("$baseUrl/browse") }.getOrNull() ?: return emptySet()
        val tags = mutableSetOf<MangaTag>()
        doc.select("input[name=genres]").forEach { input ->
            val key = input.attr("value").takeIf { it.isNotEmpty() } ?: return@forEach
            val label = input.parent()?.text()?.trim()?.ifEmpty { null } ?: key
            tags += MangaTag(title = label, key = key, source = customSource)
        }
        if (tags.isNotEmpty()) return tags
        doc.select("a[href*=/browse?genre]").forEach { a ->
            val key = a.attr("href").substringAfter("genre=").substringBefore("&")
                .ifEmpty { return@forEach }
            val title = a.text().trim().ifEmpty { return@forEach }
            tags += MangaTag(title = title, key = key, source = customSource)
        }
        return tags
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        return runCatching {
            val doc = fetchDocument(pageUrl)

            val title = doc.selectFirst("h3.item-title, .series-name, h1.series-title, meta[property=og:title]")
                ?.let { if (it.tagName() == "meta") it.attr("content") else it.text() }
                ?.trim() ?: manga.title

            val coverImg = doc.selectFirst(".cover-item img, .series-thumb img, meta[property=og:image]")
            val coverUrl = (coverImg?.attr("src") ?: coverImg?.attr("content") ?: manga.coverUrl).fixProtocol()

            val description = doc.selectFirst(".limit-html, .series-desc, .detail-desc")?.text()?.trim()

            val statusText = doc.selectFirst(".attr-status .status, .series-status")
                ?.text()?.lowercase()
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
            // Bato stores page URLs in a script: const imgHttpLis = [...]
            val scriptImages = extractPageImages(doc)
            if (scriptImages.isNotEmpty()) return@runCatching scriptImages.mapIndexed { i, url ->
                MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
            }
            // DOM fallback
            doc.select(".page-chapter img, .chapter-img img, img[class*=page-img]")
                .mapIndexedNotNull { i, img ->
                    val url = (img.attr("data-src").takeIf { it.isNotEmpty() }
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
            SortOrder.POPULARITY -> "views_m.da"
            SortOrder.RATING     -> "rating_bay.da"
            SortOrder.NEWEST     -> "create.da"
            else                 -> "update.da"
        }
        val url = "$baseUrl/browse?sort=$sort&page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun browseByGenre(genreKey: String, offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val url = "$baseUrl/browse?genre=$genreKey&sort=update.da&page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val page = offset / PAGE_SIZE + 1
        val url = "$baseUrl/search?word=$encoded&page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun parseMangaListPage(doc: Document): List<Manga> {
        val items = doc.select(".item.item-sap, .item.normal, .item-row, .arc-item, .item")
            .filter { it.selectFirst("a") != null }
        return items.mapNotNull { parseMangaItem(it) }
    }

    private fun parseMangaItem(el: Element): Manga? {
        val anchor = el.selectFirst("a[href*=/series/]") ?: el.selectFirst("a") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val title = el.selectFirst(".item-title, .item-text a, h3, .name")?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null
        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("data-src") ?: coverImg?.attr("src") ?: "").fixProtocol()
        return buildManga(title, pageUrl, coverUrl)
    }

    private fun loadChapterList(doc: Document, pageUrl: String): List<MangaChapter> {
        val rows = doc.select(".chapter-item, .chapter-list li, .episode-item, li[class*=chapter]")
        return rows.mapIndexedNotNull { i, el ->
            val a = el.selectFirst("a[href*=/chapter/]") ?: el.selectFirst("a") ?: return@mapIndexedNotNull null
            val url = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val rawTitle = el.selectFirst(".chapter-title, .chapter-name, .chapter")?.text()?.trim()
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

    private fun extractPageImages(doc: Document): List<String> {
        val scripts = doc.select("script:not([src])").map { it.data() }
        for (script in scripts) {
            val match = IMG_LIST_RE.find(script) ?: continue
            return URL_RE.findAll(match.groupValues[1])
                .map { it.groupValues[1].fixProtocol() }
                .filter { it.startsWith("http") }
                .toList()
        }
        return emptyList()
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
        private val CHAPTER_NUMBER_RE = Regex("""(?:[Cc]hapter|[Cc]h\.?)\s*([\d.]+)""")
        private val IMG_LIST_RE = Regex(
            """(?:imgHttpLis|batoWord|images)\s*=\s*(\[.*?])""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val URL_RE = Regex(""""(https?://[^"]+)"""")

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
