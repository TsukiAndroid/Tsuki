package io.github.landwarderer.futon.customsource.data

import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
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
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * Generic scraper driven entirely by the fields of a user-imported [ParserTemplate].
 *
 * All selectors, endpoints, HTTP methods, and pagination strategies come from
 * the template's rawJson — the parser never hard-codes site-specific values.
 * Every field is optional; sensible defaults keep the parser functional even
 * for minimal templates.
 *
 * Template section → method mapping
 *  mangaList   → getList()
 *  mangaDetail → getDetails() (title / cover / description selectors)
 *  chapterList → loadChapterList() called internally from getDetails()
 *  pageList    → getPages()
 *  genres      → getGenres()
 *
 * Template lookup uses [ParserTemplateRepository.peekByName], which is
 * available without dependency injection via the singleton companion.
 */
class TemplateHtmlParser(
    private val customSource: CustomMangaSource,
) {

    private val baseUrl get() = customSource.source.cleanBaseUrl

    /**
     * The parsed template JSON.  Null when the template name stored in
     * [CustomSource.parserSourceName] does not match any imported template.
     */
    private val template: JSONObject? by lazy {
        val name = customSource.source.parserSourceName ?: return@lazy null
        val raw = ParserTemplateRepository.peekByName(name)?.rawJson ?: return@lazy null
        runCatching { JSONObject(raw) }.getOrNull()
    }

    private val mangaListSection    get() = template?.optJSONObject("mangaList")
    private val mangaDetailSection  get() = template?.optJSONObject("mangaDetail")
    private val chapterListSection  get() = template?.optJSONObject("chapterList")
    private val pageListSection     get() = template?.optJSONObject("pageList")
    private val genresSection       get() = template?.optJSONObject("genres")

    // ── Public API ─────────────────────────────────────────────────────────────

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val section = mangaListSection ?: return emptyList()
        val query = filter?.query?.trim()
        val method = section.optString("method", "GET").uppercase()
        val endpoint = section.optString("endpoint", "/").ifEmpty { "/" }
        val pagination = section.optString("pagination", "page")
        val page = when (pagination) {
            "ajax", "offset" -> offset / PAGE_SIZE
            else -> offset / PAGE_SIZE + 1
        }
        val endpointUrl = if (endpoint.startsWith("http")) endpoint else "$baseUrl$endpoint"

        if (!query.isNullOrBlank()) {
            return searchManga(section, query, page)
        }

        return when (method) {
            "POST" -> {
                val action = section.optString("action").ifEmpty { "madara_load_more" }
                fetchPostList(endpointUrl, page, action, section)
            }
            else -> {
                val pageParam = section.optString("pageParam", "page")
                fetchGetList(section, "$endpointUrl?$pageParam=$page")
            }
        }
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        val doc = runCatching { fetchDocument(pageUrl) }.getOrElse { return manga }
        val section = mangaDetailSection

        val titleSel = section?.optString("titleSelector")?.ifEmpty { null } ?: "h1"
        val coverSel = section?.optString("coverSelector")?.ifEmpty { null } ?: "img"
        val descSel  = section?.optString("descriptionSelector")?.ifEmpty { null }

        val title = doc.selectFirst(titleSel)?.text()?.trim()?.ifEmpty { manga.title }
            ?: manga.title
        val coverEl = doc.selectFirst(coverSel)
        val coverUrl = (coverEl?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: coverEl?.attr("data-lazy-src")?.takeIf { it.isNotEmpty() }
            ?: coverEl?.attr("src")?.takeIf { it.isNotEmpty() }
            ?: manga.coverUrl).fixProtocol()
        val description = descSel?.let { sel ->
            doc.selectFirst(sel)?.let { el ->
                val byParagraph = el.select("p").joinToString("\n") { it.text() }.trim()
                byParagraph.ifEmpty { el.text().trim() }
            }?.ifEmpty { null }
        }

        val chapters = loadChapterList(doc, pageUrl)
        return manga.copy(
            title = title,
            coverUrl = coverUrl,
            largeCoverUrl = coverUrl,
            description = description ?: manga.description,
            chapters = chapters,
        )
    }

    fun getPages(chapter: MangaChapter): List<MangaPage> {
        val section = pageListSection ?: return emptyList()
        val imgSel = section.optString("imageSelector").ifEmpty { "img" }
        val doc = runCatching { fetchDocument(chapter.url) }.getOrElse { return emptyList() }
        return doc.select(imgSel).mapIndexedNotNull { idx, img ->
            val url = (img.attr("data-src").takeIf { it.isNotEmpty() }
                ?: img.attr("data-lazy-src").takeIf { it.isNotEmpty() }
                ?: img.attr("src")).trim().fixProtocol()
            if (url.isBlank()) null
            else MangaPage(
                id = chapter.id * 1000L + idx,
                url = url,
                preview = null,
                source = customSource,
            )
        }
    }

    fun getGenres(): Set<MangaTag> {
        val section = genresSection ?: return emptySet()
        val endpoint = section.optString("endpoint").ifEmpty { return emptySet() }
        val selector = section.optString("selector").ifEmpty { return emptySet() }
        val url = if (endpoint.startsWith("http")) endpoint else "$baseUrl$endpoint"
        val doc = runCatching { fetchDocument(url) }.getOrElse { return emptySet() }
        return doc.select(selector).mapNotNull { el ->
            val anchor = el.selectFirst("a")
                ?: (if (el.tagName() == "a") el else null)
                ?: return@mapNotNull null
            val href = anchor.absUrl("href").trimEnd('/')
            val key = href.substringAfterLast('/').takeIf { it.isNotEmpty() }
                ?: return@mapNotNull null
            val tagTitle = anchor.text().trim().ifEmpty { return@mapNotNull null }
            MangaTag(title = tagTitle, key = key, source = customSource)
        }.toSet()
    }

    // ── Private: list fetching ─────────────────────────────────────────────────

    private fun searchManga(section: JSONObject, query: String, page: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val searchEndpoint = section.optString("searchEndpoint").ifEmpty { "/" }
        val searchParam   = section.optString("searchParam").ifEmpty { "s" }
        val pageParam     = section.optString("pageParam", "page")
        val url = if (searchEndpoint.startsWith("http")) {
            "$searchEndpoint?$searchParam=$encoded&$pageParam=$page"
        } else {
            "$baseUrl$searchEndpoint?$searchParam=$encoded&$pageParam=$page"
        }
        return runCatching {
            parseMangaListPage(section, fetchDocument(url))
        }.getOrElse { emptyList() }
    }

    private fun fetchPostList(
        url: String,
        page: Int,
        action: String,
        section: JSONObject,
    ): List<Manga> {
        val body = FormBody.Builder()
            .add("action", action)
            .add("page", page.toString())
            .add("vars[posts_per_page]", PAGE_SIZE.toString())
            .build()
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Referer", baseUrl)
            .build()
        return runCatching {
            val html = httpClient.newCall(request).execute().use { it.body?.string() ?: "" }
            parseMangaListPage(section, Jsoup.parse(html, baseUrl))
        }.getOrElse { emptyList() }
    }

    private fun fetchGetList(section: JSONObject, url: String): List<Manga> =
        runCatching { parseMangaListPage(section, fetchDocument(url)) }.getOrElse { emptyList() }

    private fun parseMangaListPage(section: JSONObject, doc: Document): List<Manga> {
        val itemSel = section.optString("itemSelector").ifEmpty { null }
        val items: List<Element> = if (itemSel != null) {
            doc.select(itemSel)
        } else {
            GENERIC_ITEM_SELECTORS.firstNotNullOfOrNull { sel ->
                doc.select(sel).takeIf { it.isNotEmpty() }
            } ?: emptyList()
        }
        val titleSel = section.optString("titleSelector").ifEmpty { null }
        val coverSel = section.optString("coverSelector").ifEmpty { null }
        val linkSel  = section.optString("linkSelector").ifEmpty { null }
        return items.mapNotNull { parseMangaItem(it, titleSel, coverSel, linkSel) }
    }

    private fun parseMangaItem(
        el: Element,
        titleSel: String?,
        coverSel: String?,
        linkSel: String?,
    ): Manga? {
        val titleEl = if (titleSel != null) {
            el.selectFirst(titleSel)
        } else {
            GENERIC_TITLE_SELECTORS.firstNotNullOfOrNull { el.selectFirst(it) }
        }
        val anchor = if (linkSel != null) {
            el.selectFirst(linkSel)
        } else {
            titleEl?.takeIf { it.tagName() == "a" } ?: el.selectFirst("a[href]")
        }
        val title = titleEl?.text()?.trim()?.ifEmpty { null }
            ?: anchor?.text()?.trim()?.ifEmpty { null }
            ?: return null
        val pageUrl = anchor?.absUrl("href")?.ifEmpty { null } ?: return null
        val coverEl = if (coverSel != null) el.selectFirst(coverSel) else el.selectFirst("img")
        val coverUrl = (coverEl?.attr("data-src")?.takeIf { it.isNotEmpty() }
            ?: coverEl?.attr("data-lazy-src")?.takeIf { it.isNotEmpty() }
            ?: coverEl?.attr("src") ?: "").fixProtocol()
        return buildManga(title, pageUrl, coverUrl)
    }

    // ── Private: chapter list ──────────────────────────────────────────────────

    private fun loadChapterList(doc: Document, pageUrl: String): List<MangaChapter> {
        val section = chapterListSection
        val action   = section?.optString("action")?.ifEmpty { null }
        val endpoint = section?.optString("endpoint")?.ifEmpty { null }

        if (action != null && endpoint != null) {
            val epUrl  = if (endpoint.startsWith("http")) endpoint else "$baseUrl$endpoint"
            val postId = doc.selectFirst(
                "input#manga-chapters-holder, [id=manga-chapters-holder], [data-id]"
            )?.attr("data-id")
            if (!postId.isNullOrEmpty()) {
                val ajax = runCatching {
                    fetchChaptersAjax(epUrl, action, postId, pageUrl)
                }.getOrNull()
                if (!ajax.isNullOrEmpty()) return ajax
            }
        }

        val chapterSel = section?.optString("selector")?.ifEmpty { null }
            ?: DEFAULT_CHAPTER_SELECTOR
        val titleSel = section?.optString("titleSelector")?.ifEmpty { null } ?: "a"
        val linkSel  = section?.optString("linkSelector")?.ifEmpty { null } ?: "a"
        return doc.select(chapterSel)
            .mapIndexedNotNull { idx, el -> chapterFromElement(el, idx, titleSel, linkSel) }
            .reversed()
    }

    private fun fetchChaptersAjax(
        endpointUrl: String,
        action: String,
        postId: String,
        referer: String,
    ): List<MangaChapter> {
        val body = FormBody.Builder()
            .add("action", action)
            .add("manga", postId)
            .build()
        val request = Request.Builder()
            .url(endpointUrl)
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .build()
        val html = httpClient.newCall(request).execute().use { it.body?.string() ?: "" }
        return Jsoup.parse(html, baseUrl)
            .select(DEFAULT_CHAPTER_SELECTOR)
            .mapIndexedNotNull { idx, el -> chapterFromElement(el, idx, "a", "a") }
            .reversed()
    }

    private fun chapterFromElement(
        el: Element,
        idx: Int,
        titleSel: String,
        linkSel: String,
    ): MangaChapter? {
        val anchor = el.selectFirst(linkSel) ?: el.selectFirst("a") ?: return null
        val url = anchor.absUrl("href").ifEmpty { return null }
        val titleEl = el.selectFirst(titleSel)
        val rawTitle = titleEl?.text()?.trim()?.ifEmpty { null }
            ?: anchor.text().trim().ifEmpty { "Chapter ${idx + 1}" }
        val number = CHAPTER_NUM_RE.find(rawTitle)?.groupValues?.getOrNull(1)?.toFloatOrNull()
            ?: (idx + 1).toFloat()
        return MangaChapter(
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
    }

    // ── Private: utilities ────────────────────────────────────────────────────

    private fun buildManga(title: String, pageUrl: String, coverUrl: String): Manga {
        val relativePath = runCatching {
            val uri = URI(pageUrl)
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

    private fun fetchDocument(url: String): Document {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", baseUrl)
            .get()
            .build()
        return httpClient.newCall(request).execute().use { resp ->
            Jsoup.parse(resp.body?.string() ?: "", url)
        }
    }

    private fun String?.fixProtocol(): String = when {
        this == null    -> ""
        startsWith("//") -> "https:$this"
        else            -> this
    }

    companion object {
        private const val PAGE_SIZE = 16
        private const val USER_AGENT = "Tsuki/1.0 (Android)"
        private val CHAPTER_NUM_RE = Regex("""[Cc]hapter[s]?\s*([\d.]+)""")
        private const val DEFAULT_CHAPTER_SELECTOR =
            "li.wp-manga-chapter, .chapter-li, .chapter-item, li[class*=chapter]"

        /**
         * Cascade of container selectors tried across common manga CMSes when no
         * explicit itemSelector is present in the template.
         */
        private val GENERIC_ITEM_SELECTORS = listOf(
            "div.page-item-detail",
            "div.c-tabs-item__content",
            ".manga-item",
            "article.manga",
            "li.manga-item",
            ".media.manga",
            ".novel-item",
            ".book-item",
            ".series-item",
            "div.bs",
        )

        /**
         * Cascade of title/link selectors tried when no explicit titleSelector
         * is present in the template.
         */
        private val GENERIC_TITLE_SELECTORS = listOf(
            ".post-title a",
            "h3.h5 a",
            "h3 a",
            "h2 a",
            "h4 a",
            ".title a",
            ".name a",
            "a.series-title",
        )

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
