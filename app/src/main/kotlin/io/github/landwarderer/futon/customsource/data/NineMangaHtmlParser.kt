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
 * Parser for NineManga and sites using the same CMS.
 *
 * NineManga uses a distinctive PHP CMS with subdomain-per-language.
 * Covers: en.ninemanga.com, es.ninemanga.com, de.ninemanga.com, etc.
 * Also covers NineAnime-sibling domains sharing this markup.
 *
 * URL patterns:
 *   Browse : {baseUrl}/manga/new-chapter/{page}.html
 *   Search : {baseUrl}/search/?wd={query}&page={page}
 *   Detail : {baseUrl}/manga/{slug}/ → chapters at .detail_list
 *   Chapter: {baseUrl}/chapter/{id}/ → pages at .reader_nav_bar select
 */
class NineMangaHtmlParser(
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
        doc.select(".cate_list li a, .l_catelist a, a[href*=/category/]").forEach { a ->
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

            val title = doc.selectFirst(".manga_detail_top .title, .bookname h1, h1.title")
                ?.text()?.trim() ?: manga.title

            val coverImg = doc.selectFirst(".manga_detail_top img, .bookcover img")
            val coverUrl = (coverImg?.attr("src") ?: manga.coverUrl).fixProtocol()

            val description = doc.selectFirst(".manga_detail_page .comic_description, p[class*=diction]")
                ?.text()?.trim()

            val statusText = doc.select(".detail_list p, .comic_status")
                .firstOrNull { it.text().contains("Status", ignoreCase = true) }
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
            // Page URLs are in <select class="page_select"> or <select class="reader_nav_bar">
            val pageUrls = doc.select("select.page_select option, select.reader_nav_bar option")
                .map { it.attr("value") }
                .filter { it.isNotEmpty() && !it.contains("javascript") }
            if (pageUrls.isNotEmpty()) {
                // Fetch first page for image; remaining pages follow the same pattern
                return@runCatching pageUrls.mapIndexedNotNull { i, pageUrl ->
                    val fullUrl = if (pageUrl.startsWith("http")) pageUrl else "$baseUrl$pageUrl"
                    val imgUrl = runCatching {
                        val pageDoc = fetchDocument(fullUrl)
                        (pageDoc.selectFirst("#manga_pic img, .reader_main img, .manga_img img")
                            ?.attr("src") ?: "").fixProtocol()
                    }.getOrElse { "" }
                    if (imgUrl.isEmpty()) null
                    else MangaPage(id = chapter.id * 1000L + i, url = imgUrl, preview = null, source = customSource)
                }
            }
            // Fallback: direct image tags
            doc.select(".manga_pic img, img.chapter-img").mapIndexedNotNull { i, img ->
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
            SortOrder.POPULARITY -> "hot"
            SortOrder.RATING     -> "hot"
            SortOrder.NEWEST     -> "new-manga"
            else                 -> "new-chapter"
        }
        val url = "$baseUrl/manga/$path/$page.html"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun browseByGenre(genreKey: String, offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val url = "$baseUrl/category/$genreKey/$page.html"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val page = offset / PAGE_SIZE + 1
        val url = "$baseUrl/search/?wd=$encoded&page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun parseMangaListPage(doc: Document): List<Manga> {
        val items = doc.select("dl.bookinfo, .list_manga li, .book_list li, .bookbg li")
        return items.mapNotNull { parseMangaItem(it) }
    }

    private fun parseMangaItem(el: Element): Manga? {
        val anchor = el.selectFirst("a[href*=/manga/]") ?: el.selectFirst("a") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val title = el.selectFirst("dt a, dd.bookname, h2, h3")?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null
        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("src") ?: "").fixProtocol()
        return buildManga(title, pageUrl, coverUrl)
    }

    private fun loadChapterList(doc: Document, pageUrl: String): List<MangaChapter> {
        val rows = doc.select(".detail_list ul li a, .list_chapters li a, a[href*=/chapter/]")
        return rows.mapIndexedNotNull { i, a ->
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
        private const val PAGE_SIZE = 15
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
