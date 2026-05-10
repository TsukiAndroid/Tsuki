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
 * Parser for MangaFreak (mangafreak.net) and its mirror domains.
 *
 * MangaFreak uses a bespoke PHP CMS with a distinctive URL and class
 * naming convention not shared by any other covered parser.
 *
 * Covers: mangafreak.net, w12.mangafreak.net, and mirror domains that
 * preserve the same URL structure (/Manga/{slug}, /Search/{query}).
 *
 * URL patterns:
 *   Browse  : {baseUrl}/Manga_List?page=N
 *   Search  : {baseUrl}/Search/{encoded-query}
 *   Detail  : {baseUrl}/Manga/{slug}
 *   Chapter : {baseUrl}/{chapter-slug}  (e.g. /One_Piece_1000)
 *
 * Fingerprint: "mangafreak" in HTML  OR  ".manga_search_item" elements present.
 */
class MangaFreakHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val tag   = filter?.tags?.firstOrNull()
        return when {
            !query.isNullOrBlank() -> searchManga(query)
            tag != null            -> browseByGenre(tag.key, offset)
            else                   -> browseList(offset, order)
        }
    }

    fun getGenres(): Set<MangaTag> {
        val doc = runCatching { fetchDocument("$baseUrl/Manga_List") }.getOrNull() ?: return emptySet()
        val tags = mutableSetOf<MangaTag>()
        doc.select("select.genre_select option, a[href*=/Genre/]").forEach { el ->
            val key = if (el.tagName() == "option") el.attr("value")
                      else el.attr("href").trimEnd('/').substringAfterLast('/')
            val title = el.text().trim()
            if (key.isNotEmpty() && key != "0" && title.isNotEmpty())
                tags += MangaTag(title = title, key = key, source = customSource)
        }
        return tags
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        return runCatching {
            val doc = fetchDocument(pageUrl)

            val title = doc.selectFirst(".manga_right_container h1, .manga_detail h1, h1.manga_name")
                ?.text()?.trim() ?: manga.title

            val coverImg = doc.selectFirst(".manga_image img, .manga_cover img, .book-cover img")
            val coverUrl = (coverImg?.attr("src")?.takeIf { it.isNotEmpty() }
                ?: manga.coverUrl).fixProtocol()

            val description = doc.selectFirst(".manga_detail_content p, .manga_desc, .summary p")
                ?.text()?.trim()

            val statusText = doc.select(".manga_detail_list li, .manga_info li")
                .firstOrNull { it.text().contains("Status", ignoreCase = true) }
                ?.text()?.lowercase()
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
            // MangaFreak renders all images directly in .reader_images
            val images = doc.select(".reader_images img, .chapter_images img, #reader_images img")
            images.mapIndexedNotNull { i, img ->
                val url = (img.attr("data-src").takeIf { it.isNotEmpty() }
                    ?: img.attr("src")).trim().fixProtocol()
                if (url.isEmpty() || url.contains("loading") || url.contains("data:image")) null
                else MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun browseList(offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val url  = "$baseUrl/Manga_List?page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun browseByGenre(genreKey: String, offset: Int): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val url  = "$baseUrl/Genre/$genreKey?page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun searchManga(query: String): List<Manga> {
        // MangaFreak search uses path-encoded query with underscores
        val encoded = query.trim().replace(' ', '_')
            .let { java.net.URLEncoder.encode(it, "UTF-8") }
        val url = "$baseUrl/Search/$encoded"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun parseMangaListPage(doc: Document): List<Manga> {
        val items = doc.select(".manga_search_item, .book_list_item, .manga-item, div.item")
            .ifEmpty { doc.select("div[class*=manga_] a[href*=/Manga/]").map { it.parent() ?: it } }
        return items.mapNotNull { parseMangaItem(it) }
    }

    private fun parseMangaItem(el: Element): Manga? {
        val anchor  = el.selectFirst("a[href*='/Manga/']") ?: el.selectFirst("a") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null

        val title = el.selectFirst("h3, h2, .manga_name, p.title")?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: anchor.text().trim().takeIf { it.isNotEmpty() }
            ?: return null

        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: coverImg?.attr("src") ?: "").fixProtocol()

        return buildManga(title, pageUrl, coverUrl)
    }

    private fun loadChapterList(doc: Document): List<MangaChapter> {
        val rows = doc.select(".detail_list ul li, .chapter_item, .chapter-list li")
        return rows.mapIndexedNotNull { i, el ->
            val a = el.selectFirst("a") ?: return@mapIndexedNotNull null
            val url = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val rawTitle = el.selectFirst(".chapter_title, span")?.text()?.trim()
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
