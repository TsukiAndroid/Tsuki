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
 * Parser for Mangago (mangago.me).
 *
 * Mangago.me is one of the oldest continuously active manga readers,
 * particularly popular for yaoi, yuri, shounen-ai and shoujo-ai titles.
 * It uses a classic PHP CMS that has remained stable for years.
 *
 * URL patterns:
 *   Browse   : {baseUrl}/home/manga/bt/list/?page=N  (latest updates)
 *   Hot      : {baseUrl}/home/manga/hd/list/?page=N
 *   Search   : {baseUrl}/r/listview.php?category=search_title&q={query}&page=N
 *   Detail   : {baseUrl}/read-manga/{slug}/
 *   Chapter  : {baseUrl}/read-manga/{slug}/ch/{number}/pg/{page}/
 *
 * Fingerprint: "mangago" in HTML  OR  "#book_list" + ".booklist_item" elements.
 */
class MangagoHtmlParser(
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
        val doc = runCatching { fetchDocument("$baseUrl/home/manga/bt/list/") }.getOrNull() ?: return emptySet()
        val tags = mutableSetOf<MangaTag>()
        doc.select("select[name=genre_select] option, a[href*=genre]").forEach { el ->
            val key = if (el.tagName() == "option") el.attr("value")
                      else el.attr("href").substringAfter("genre=").substringBefore("&")
            val title = el.text().trim()
            if (key.isNotEmpty() && key != "all" && title.isNotEmpty() && title != "All")
                tags += MangaTag(title = title, key = key, source = customSource)
        }
        return tags
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        return runCatching {
            val doc = fetchDocument(pageUrl)

            val title = doc.selectFirst("#title_pic_box h1, h1.c_h1, .manga_detail_top .title")
                ?.text()?.trim() ?: manga.title

            val coverImg = doc.selectFirst("#title_pic_box img, .manga_detail_top img, .info-manga img")
            val coverUrl = (coverImg?.attr("src")?.takeIf { it.isNotEmpty() }
                ?: manga.coverUrl).fixProtocol()

            val description = doc.selectFirst("#story_discription p, .manga-desc p, .description p")
                ?.text()?.trim()

            val statusText = doc.select("ul.detail_topinfo li, .info-manga li")
                .firstOrNull { it.text().contains("Status", ignoreCase = true) }
                ?.text()?.lowercase()
            val state = when {
                statusText == null      -> MangaState.ONGOING
                "complet" in statusText -> MangaState.FINISHED
                "hiatus"  in statusText -> MangaState.PAUSED
                "cancel"  in statusText -> MangaState.ABANDONED
                else                    -> MangaState.ONGOING
            }

            val chapters = loadChapterList(doc, manga.url)

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

            // Mangago has a paginated reader; gather all sub-page URLs first.
            val pageOptions = doc.select("select.nav_select option, ul.pager li a")
                .mapNotNull { el ->
                    val v = if (el.tagName() == "option") el.attr("value") else el.absUrl("href")
                    v.takeIf { it.isNotEmpty() && it.startsWith("http") }
                }.distinct()

            if (pageOptions.size > 1) {
                return@runCatching pageOptions.mapIndexedNotNull { i, pgUrl ->
                    val imgUrl = runCatching {
                        val pd = fetchDocument(pgUrl)
                        (pd.selectFirst("div#pic_box img, #comicpic img, img#image")
                            ?.attr("src") ?: "").fixProtocol()
                    }.getOrElse { "" }
                    if (imgUrl.isEmpty()) null
                    else MangaPage(id = chapter.id * 1000L + i, url = imgUrl, preview = null, source = customSource)
                }
            }

            // Fallback: extract from JS or direct img elements
            val scriptImages = extractImagesFromScript(doc)
            if (scriptImages.isNotEmpty()) return@runCatching scriptImages.mapIndexed { i, url ->
                MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
            }

            doc.select("div#pic_box img, #comicpic img, img#image").mapIndexedNotNull { i, img ->
                val url = img.attr("src").trim().fixProtocol()
                if (url.isEmpty()) null
                else MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun browseList(offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val path = when (order) {
            SortOrder.POPULARITY -> "home/manga/hd/list/?page=$page"
            SortOrder.NEWEST     -> "home/manga/nm/list/?page=$page"
            else                 -> "home/manga/bt/list/?page=$page"
        }
        return runCatching { parseMangaListPage(fetchDocument("$baseUrl/$path")) }.getOrElse { emptyList() }
    }

    private fun browseByGenre(genreKey: String, offset: Int): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val url  = "$baseUrl/r/listview.php?category=manga&genre=$genreKey&page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val page    = offset / PAGE_SIZE + 1
        val url     = "$baseUrl/r/listview.php?category=search_title&q=$encoded&page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun parseMangaListPage(doc: Document): List<Manga> {
        val items = doc.select("#book_list li.booklist_item, .booklist_item, ul#book_list li")
            .ifEmpty { doc.select("li.book_list, .list_manga li") }
        return items.mapNotNull { parseMangaItem(it) }
    }

    private fun parseMangaItem(el: Element): Manga? {
        val anchor  = el.selectFirst("a[href*=read-manga]") ?: el.selectFirst("a") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null

        val title = el.selectFirst("h2.title, h3.title, .title a, p.manga_name")?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null

        val coverImg = el.selectFirst(".pic img, .cover img, img")
        val coverUrl = (coverImg?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: coverImg?.attr("src") ?: "").fixProtocol()

        return buildManga(title, pageUrl, coverUrl)
    }

    private fun loadChapterList(doc: Document, mangaUrl: String): List<MangaChapter> {
        val rows = doc.select("table#chapter_table tr, .chapter_list li, ul#chapter_list li")
        return rows.mapIndexedNotNull { i, el ->
            val a = el.selectFirst("td a[href*=read-manga], li a, a") ?: return@mapIndexedNotNull null
            val url = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            if (!url.contains("/ch/") && !url.contains("chapter")) return@mapIndexedNotNull null
            val rawTitle = el.selectFirst("td:first-child, span.chapter_title")?.text()?.trim()
                ?: a.text().trim()
                    .ifEmpty { "Chapter ${i + 1}" }
            val number = CHAPTER_NUMBER_RE.find(rawTitle)?.groupValues?.getOrNull(1)
                ?.toFloatOrNull()
                ?: rawTitle.filter { it.isDigit() || it == '.' }.toFloatOrNull()
                ?: (i + 1).toFloat()
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
            val match = IMG_ARRAY_RE.find(script) ?: continue
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
        private val CHAPTER_NUMBER_RE = Regex("""(?:[Cc]hapter|[Cc]h\.?|Vol\.\d+\s+Ch\.?)\s*([\d.]+)""")
        private val IMG_ARRAY_RE      = Regex("""var\s+(?:manga_images|mPages|imgArr)\s*=\s*(\[.*?])""", RegexOption.DOT_MATCHES_ALL)
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
