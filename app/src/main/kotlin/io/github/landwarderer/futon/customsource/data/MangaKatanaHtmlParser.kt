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
 * Parser for MangaKatana (mangakatana.com).
 *
 * MangaKatana is a clean, fast English aggregator with a stable PHP layout.
 * Its chapter reader uses `img.chapter-img[data-src]` — a distinctive selector
 * not shared by any other covered parser.
 *
 * URL patterns:
 *   Browse  : {baseUrl}/manga/page:{N}
 *   Search  : {baseUrl}/manga/?search={query}&search_by=book_name&page={N}
 *   Genre   : {baseUrl}/manga/genre:{genreKey}/page:{N}
 *   Detail  : {baseUrl}/manga/{slug}
 *   Chapter : {baseUrl}/manga/{slug}/{chapterId}
 *
 * Fingerprint: "mangakatana" in HTML  OR  "img.chapter-img" + "#chapters" present.
 */
class MangaKatanaHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val tag   = filter?.tags?.firstOrNull()
        return when {
            !query.isNullOrBlank() -> searchManga(query, offset)
            tag != null            -> browseByGenre(tag.key, offset)
            else                   -> browseList(offset)
        }
    }

    fun getGenres(): Set<MangaTag> {
        val doc = runCatching { fetchDocument("$baseUrl/manga/page:1") }.getOrNull() ?: return emptySet()
        val tags = mutableSetOf<MangaTag>()
        doc.select("a[href*=genre:], .genre-list a, ul.genres a").forEach { a ->
            val href  = a.attr("href")
            val key   = href.substringAfter("genre:").substringBefore("/").substringBefore("?")
                .takeIf { it.isNotEmpty() } ?: return@forEach
            val title = a.text().trim().ifEmpty { return@forEach }
            tags += MangaTag(title = title, key = key, source = customSource)
        }
        return tags
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        return runCatching {
            val doc = fetchDocument(pageUrl)

            val title = doc.selectFirst("h1.heading, .manga-title h1, h1")
                ?.text()?.trim() ?: manga.title

            val coverImg = doc.selectFirst("img.lazy, .cover img, .manga-cover img")
            val coverUrl = (coverImg?.attr("data-src")?.takeIf { it.isNotEmpty() }
                ?: coverImg?.attr("src")
                ?: manga.coverUrl).fixProtocol()

            val description = doc.selectFirst("div.summary p, .manga-description p, #summary")
                ?.text()?.trim()

            val statusText = doc.select("div.info div, .book-info div")
                .firstOrNull { it.text().contains("Status", ignoreCase = true) }
                ?.text()?.lowercase()
                ?: doc.selectFirst(".status, span.status")?.text()?.lowercase()
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
            // MangaKatana's distinctive chapter reader: img.chapter-img[data-src]
            val images = doc.select("img.chapter-img[data-src], div#imgs img[data-src], .chapter-content img")
            if (images.isNotEmpty()) {
                return@runCatching images.mapIndexedNotNull { i, img ->
                    val url = (img.attr("data-src").takeIf { it.isNotEmpty() }
                        ?: img.attr("src")).trim().fixProtocol()
                    if (url.isEmpty()) null
                    else MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
                }
            }
            // Fallback: try to extract from JS variable
            val scriptImages = extractImagesFromScript(doc)
            scriptImages.mapIndexed { i, url ->
                MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun browseList(offset: Int): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val url  = "$baseUrl/manga/page:$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun browseByGenre(genreKey: String, offset: Int): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val url  = "$baseUrl/manga/genre:$genreKey/page:$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val page    = offset / PAGE_SIZE + 1
        val url     = "$baseUrl/manga/?search=$encoded&search_by=book_name&page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun parseMangaListPage(doc: Document): List<Manga> {
        val items = doc.select("div.item, .manga-list .item, ul.list-truyen li")
            .ifEmpty { doc.select("div[class*=book-item], article.manga") }
        return items.mapNotNull { parseMangaItem(it) }
    }

    private fun parseMangaItem(el: Element): Manga? {
        val anchor  = el.selectFirst("a[href*=/manga/]") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        // Exclude genre/browse links
        if (pageUrl.contains("genre:") || pageUrl.contains("page:")) return null

        val title = el.selectFirst("h3, h2, .book_name, div.title")?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        val coverImg = el.selectFirst("img.lazy, img[data-src], img")
        val coverUrl = (coverImg?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: coverImg?.attr("src") ?: "").fixProtocol()

        return buildManga(title, pageUrl, coverUrl)
    }

    private fun loadChapterList(doc: Document): List<MangaChapter> {
        // MangaKatana: chapters in #chapters table
        val rows = doc.select("#chapters table tr, .chapter-list tr, .chapter_list li")
        return rows.mapIndexedNotNull { i, el ->
            val a = el.selectFirst("td:first-child a, a") ?: return@mapIndexedNotNull null
            val url = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val rawTitle = el.selectFirst("td a, .chapter-title")?.text()?.trim()
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

    private fun extractImagesFromScript(doc: Document): List<String> {
        val scripts = doc.select("script:not([src])").map { it.data() }
        for (script in scripts) {
            val match = IMG_VAR_RE.find(script) ?: continue
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
        private val CHAPTER_NUMBER_RE = Regex("""(?:[Cc]hapter|[Cc]h\.?)\s*([\d.]+)""")
        private val IMG_VAR_RE        = Regex("""var\s+(?:thzq|images|imgs)\s*=\s*(\[.*?])""", RegexOption.DOT_MATCHES_ALL)
        private val URL_RE            = Regex(""""(https?://[^"]+)"""")

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
