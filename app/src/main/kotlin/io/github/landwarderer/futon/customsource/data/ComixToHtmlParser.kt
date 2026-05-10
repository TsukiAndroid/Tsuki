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
 * Parser for comix.to and sites using the same layout.
 *
 * comix.to is a popular manga/comic aggregator with a PHP-based CMS.
 *
 * URL patterns:
 *   Browse : {baseUrl}/comic-genre/all/?page=N&sort={sort}
 *   Search : {baseUrl}/search-comic?q={query}&page=N
 *   Detail : {baseUrl}/comic/{slug}/
 *   Chapter: {baseUrl}/comic/{slug}/chapter-N/
 *
 * Fingerprint markers:
 *   - "comix.to" string in HTML  OR
 *   - .list-chapter + .detail-info  OR
 *   - .list-story-item + .story-item-wrap
 */
class ComixToHtmlParser(
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
        val doc = runCatching { fetchDocument("$baseUrl/comic-genre/") }.getOrNull() ?: return emptySet()
        val tags = mutableSetOf<MangaTag>()
        doc.select(".panel-body a[href*=genre], .genres-list a, a[href*=comic-genre]").forEach { a ->
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

            val title = doc.selectFirst(".detail-info h1, .story-title, h1.title-detail")
                ?.text()?.trim() ?: manga.title

            val coverImg = doc.selectFirst(".detail-info-cover img, .story-cover img, .book-thumbnail img")
            val coverUrl = (coverImg?.attr("src") ?: coverImg?.attr("data-src") ?: manga.coverUrl).fixProtocol()

            val descEl = doc.selectFirst(".detail-content p, .story-summary p, .summary-content")
            val description = descEl?.text()?.trim()

            val statusText = doc.select(".detail-info-right p, .story-detail-right p")
                .firstOrNull { it.text().contains("Status", ignoreCase = true) }
                ?.text()?.lowercase()
                ?: doc.selectFirst(".status span, span.status")?.text()?.lowercase()
            val state = when {
                statusText == null -> MangaState.ONGOING
                "complet" in statusText || "finished" in statusText -> MangaState.FINISHED
                "hiatus" in statusText || "on hold" in statusText -> MangaState.PAUSED
                "cancel" in statusText || "drop" in statusText -> MangaState.ABANDONED
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

            // comix.to stores images in a JS array: var chapImages = [...]
            val scriptImages = extractImagesFromScript(doc)
            if (scriptImages.isNotEmpty()) return@runCatching scriptImages.mapIndexed { i, url ->
                MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
            }

            val images = doc.select(
                ".reading-detail img, .reading-content img, " +
                ".page-chapter img, img.chapter-img, " +
                "#vungdoc img, .container-chapter-reader img"
            )
            images.mapIndexedNotNull { index, img ->
                val url = (img.attr("data-src").takeIf { it.isNotEmpty() }
                    ?: img.attr("data-original").takeIf { it.isNotEmpty() }
                    ?: img.attr("src")).trim().fixProtocol()
                if (url.isEmpty() || url.contains("data:image")) null
                else MangaPage(id = chapter.id * 1000L + index, url = url, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun browseList(offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val sort = when (order) {
            SortOrder.POPULARITY -> "topview"
            SortOrder.NEWEST     -> "newest"
            SortOrder.RATING     -> "topview"
            else                 -> "latest"
        }
        val urls = listOf(
            "$baseUrl/comic-genre/all/?page=$page&sort=$sort",
            "$baseUrl/manga-list/type-manga/order-$sort/page-$page/",
            "$baseUrl/advanced-search?sort=$sort&page=$page",
        )
        for (url in urls) {
            val result = runCatching { parseMangaListPage(fetchDocument(url)) }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        return emptyList()
    }

    private fun browseByGenre(genreKey: String, offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val url = "$baseUrl/comic-genre/$genreKey/?page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val page = offset / PAGE_SIZE + 1
        val urls = listOf(
            "$baseUrl/search-comic?q=$encoded&page=$page",
            "$baseUrl/?s=$encoded&post_type=comics&paged=$page",
            "$baseUrl/search?q=$encoded&page=$page",
        )
        for (url in urls) {
            val result = runCatching { parseMangaListPage(fetchDocument(url)) }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        return emptyList()
    }

    private fun parseMangaListPage(doc: Document): List<Manga> {
        val items = doc.select(
            ".list-story-item, .story-item, .manga-item, " +
            ".book-item, article.manga, .itemupdate"
        ).ifEmpty { doc.select(".content-homepage-item, li[class*=item]") }
        return items.mapNotNull { parseMangaItem(it) }
    }

    private fun parseMangaItem(el: Element): Manga? {
        val anchor = el.selectFirst("a[href*=/comic/], a[href*=/manga/], a[href]") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val title = el.selectFirst("h3, h2, .story-title, .title")?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null
        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("data-src") ?: coverImg?.attr("src") ?: "").fixProtocol()
        return buildManga(title, pageUrl, coverUrl)
    }

    private fun loadChapterList(doc: Document, mangaUrl: String): List<MangaChapter> {
        val rows = doc.select(
            ".list-chapter li, .chapter-list li, " +
            ".row.chapter-li, ul.list-chapter a"
        )
        return rows.mapIndexedNotNull { i, el ->
            val a = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@mapIndexedNotNull null
            val url = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val rawTitle = el.selectFirst(".chapter-name, .chapter-text")?.text()?.trim()
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

    private fun extractImagesFromScript(doc: Document): List<String> {
        val scripts = doc.select("script:not([src])").map { it.data() }
        for (script in scripts) {
            val match = CHAP_IMAGES_RE.find(script) ?: continue
            return URL_RE.findAll(match.groupValues[1])
                .map { it.groupValues[1].fixProtocol() }
                .filter { it.startsWith("http") && it.contains('.') }
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
        private const val PAGE_SIZE = 24
        private const val USER_AGENT = "Tsuki/1.0 (Android)"
        private val CHAPTER_NUMBER_RE = Regex("""(?:[Cc]hapter|[Cc]h\.?)\s*([\d.]+)""")
        private val CHAP_IMAGES_RE = Regex(
            """(?:chapImages|lstImages|imgArr|var\s+images)\s*=\s*(\[.*?])""",
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
