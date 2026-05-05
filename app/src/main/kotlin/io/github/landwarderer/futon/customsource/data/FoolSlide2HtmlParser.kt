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
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.concurrent.TimeUnit

/**
 * HTML scraper for sites running FoolSlide2 — the open-source scanlation CMS
 * used by many group sites (Fallen Angels Scans, Helvetica Scans, etc.).
 *
 * URL patterns:
 *   List   : {baseUrl}/directory/{page}/
 *   Search : {baseUrl}/search/  (POST with q= parameter)
 *   Detail : {baseUrl}/series/{slug}/
 *   Chapter: {baseUrl}/read/{slug}/lang/{lang}/vol/{vol}/ch/{ch}/
 */
class FoolSlide2HtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        return if (!query.isNullOrBlank()) {
            searchManga(query)
        } else {
            browseList(offset)
        }
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        val doc = fetchDocument(pageUrl)

        val title = doc.selectFirst(".comic-info h1, .title h1, h1.comic, .series-title")
            ?.text()?.trim() ?: manga.title

        val coverImg = doc.selectFirst(".comic-info img, .comic_cover img, .thumbnail img")
        val coverUrl = (coverImg?.attr("src") ?: manga.coverUrl).fixProtocol()

        val descEl = doc.selectFirst(".comic-description, .description, .summary")
        val description = descEl?.text()?.trim()

        val statusText = doc.select(".info dd, .comic-info p").firstOrNull {
            it.previousElementSibling()?.text()?.contains("status", ignoreCase = true) == true
        }?.text()?.lowercase()
        val state = when {
            statusText == null -> MangaState.ONGOING
            "complet" in statusText -> MangaState.FINISHED
            "hiatus" in statusText -> MangaState.PAUSED
            "cancel" in statusText || "drop" in statusText -> MangaState.ABANDONED
            else -> MangaState.ONGOING
        }

        val chapters = loadChapterList(doc)

        return Manga(
            id = manga.id,
            title = title,
            altTitles = manga.altTitles,
            url = manga.url,
            publicUrl = manga.publicUrl,
            rating = manga.rating,
            contentRating = manga.contentRating,
            coverUrl = coverUrl,
            tags = manga.tags,
            state = state,
            authors = manga.authors,
            largeCoverUrl = coverUrl,
            description = description,
            chapters = chapters,
            source = customSource,
        )
    }

    fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = fetchDocument(chapter.url)
        // FoolSlide2 stores images in JSON inside a <script> tag: var pages = [{"url":"..."},...]
        val pages = extractPagesFromScript(doc)
        if (pages.isNotEmpty()) return pages.mapIndexed { i, url ->
            MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
        }

        // Fallback: DOM
        val images = doc.select(".page img, #reader img, .comic-page img, img.lazy")
        return images.mapIndexedNotNull { index, img ->
            val url = (img.attr("src").takeIf { it.isNotEmpty() }
                ?: img.attr("data-src")).trim().fixProtocol()
            if (url.isEmpty()) null
            else MangaPage(id = chapter.id * 1000L + index, url = url, preview = null, source = customSource)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun browseList(offset: Int): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val url = "$baseUrl/directory/$page/"
        return runCatching { parseSeriesList(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun searchManga(query: String): List<Manga> {
        // FoolSlide2 search is a POST to /search/
        val body = okhttp3.FormBody.Builder()
            .add("q", query)
            .build()
        val request = Request.Builder()
            .url("$baseUrl/search/")
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Referer", baseUrl)
            .build()
        return runCatching {
            val doc = httpClient.newCall(request).execute().use { resp ->
                Jsoup.parse(resp.body?.string() ?: "", "$baseUrl/search/")
            }
            parseSeriesList(doc)
        }.getOrElse { emptyList() }
    }

    private fun parseSeriesList(doc: Document): List<Manga> {
        val items = doc.select(".list li, .comic-list li, .directory li, .series-list li")
            .ifEmpty { doc.select("article.series, .listing li") }
        return items.mapNotNull { parseSeriesItem(it) }
    }

    private fun parseSeriesItem(el: Element): Manga? {
        val anchor = el.selectFirst("a[href*=/series/], a[href*=/comic/]")
            ?: el.selectFirst("a") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val title = el.selectFirst(".title, h3, h4, strong")?.text()?.trim()
            ?: anchor.text().trim().takeIf { it.isNotEmpty() }
            ?: return null
        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("src") ?: "").fixProtocol()
        return buildManga(title, pageUrl, coverUrl)
    }

    private fun loadChapterList(doc: Document): List<MangaChapter> {
        val items = doc.select(".list-chapters li, .chapter-list li, .chapters li, table.episodes tr")
        return items.mapIndexedNotNull { i, el ->
            val a = el.selectFirst("a") ?: return@mapIndexedNotNull null
            val url = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val rawTitle = a.text().trim().ifEmpty { "Chapter ${i + 1}" }
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

    private fun extractPagesFromScript(doc: Document): List<String> {
        // var pages = [{"url":"https://...","thumb":"..."},...]
        val scripts = doc.select("script:not([src])").map { it.data() }
        for (script in scripts) {
            val match = PAGES_VAR_RE.find(script) ?: continue
            val array = match.groupValues[1]
            return URL_IN_JSON_RE.findAll(array).map { it.groupValues[1].fixProtocol() }.toList()
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
        private val CHAPTER_NUMBER_RE = Regex("""(?:[Cc]h(?:apter)?\.?\s*|#)([\d.]+)""")
        private val PAGES_VAR_RE = Regex("""var\s+pages\s*=\s*(\[.*?])""", RegexOption.DOT_MATCHES_ALL)
        private val URL_IN_JSON_RE = Regex(""""url"\s*:\s*"([^"]+)"""")

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
