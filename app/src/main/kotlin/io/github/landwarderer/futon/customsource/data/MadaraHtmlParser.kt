package io.github.landwarderer.futon.customsource.data

import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import okhttp3.FormBody
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
 * HTML scraper for sites built on the WordPress Madara manga theme.
 *
 * Madara is the most widely-deployed manga CMS, powering hundreds of sites
 * (MangaKakalot clones, ReadManga, ManhuaScan, etc.).  All selector patterns
 * here follow the canonical Madara theme markup; sites that override the default
 * templates gracefully fall back to the next strategy.
 *
 * Strategy order:
 *   1. Madara AJAX endpoint  (/wp-admin/admin-ajax.php) – most reliable
 *   2. Direct HTML page scraping – fallback for AJAX-disabled sites
 */
class MadaraHtmlParser(
    private val customSource: CustomMangaSource,
) {

    private val baseUrl get() = customSource.source.cleanBaseUrl

    // ── Public API ────────────────────────────────────────────────────────────

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val tag = filter?.tags?.firstOrNull()
        return when {
            !query.isNullOrBlank() -> searchManga(query, offset)
            tag != null -> browseByGenre(tag.key, offset, order)
            else -> latestManga(offset, order)
        }
    }

    fun getGenres(): Set<MangaTag> {
        // Try all common Madara content-type slugs to find a page with genre checkboxes.
        val slugsToTry = listOf("manga", "manhwa", "manhua", "webtoon", "comic")
        var doc: org.jsoup.nodes.Document? = null
        for (slug in slugsToTry) {
            val candidate = runCatching { fetchDocument("$baseUrl/$slug/") }.getOrNull()
            if (candidate != null && candidate.select(
                    ".checkbox-manga-genre .checkbox, .manga-genres .checkbox, " +
                    ".c-checkbox-list .checkbox, a[href*=manga-genre/], a[href*=/genre/]"
                ).isNotEmpty()) {
                doc = candidate
                break
            }
        }
        // Fall back to the first page that loaded even if no genre widgets found
        if (doc == null) {
            for (slug in slugsToTry) {
                doc = runCatching { fetchDocument("$baseUrl/$slug/") }.getOrNull()
                if (doc != null) break
            }
        }
        doc ?: return emptySet()
        val tags = mutableSetOf<MangaTag>()
        // Primary: checkbox genre inputs (data-value or value) + their labels
        doc.select(".checkbox-manga-genre .checkbox, .manga-genres .checkbox, .c-checkbox-list .checkbox").forEach { el ->
            val input = el.selectFirst("input") ?: return@forEach
            val key = (input.attr("value").takeIf { it.isNotEmpty() }
                ?: input.attr("data-value")).trim().ifEmpty { return@forEach }
            val title = el.selectFirst("label")?.text()?.trim()?.ifEmpty { null } ?: key
            tags += MangaTag(title = title, key = key, source = customSource)
        }
        if (tags.isNotEmpty()) return tags
        // Fallback: genre hyperlinks in sidebar
        doc.select("a[href*=manga-genre/], a[href*=/genre/]").forEach { a ->
            val href = a.attr("href").trimEnd('/')
            val key = href.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return@forEach
            val title = a.text().trim().ifEmpty { return@forEach }
            tags += MangaTag(title = title, key = key, source = customSource)
        }
        return tags
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        val doc = fetchDocument(pageUrl)

        val title = doc.selectFirst("div.post-title h1, div.post-title h3")
            ?.text()?.trim() ?: manga.title

        val coverImg = doc.selectFirst("div.summary_image img, div.tab-summary img")
        val coverUrl = (coverImg?.attr("data-src") ?: coverImg?.attr("src") ?: manga.coverUrl)
            .fixProtocol()

        val description = doc.selectFirst("div.summary__content, div.description-summary")
            ?.select("p")?.joinToString("\n") { it.text() }?.trim()

        val statusText = doc.select("div.post-status .summary-content").getOrNull(1)
            ?.text()?.lowercase()?.trim()
        val state = when {
            statusText == null -> MangaState.ONGOING
            "complet" in statusText -> MangaState.FINISHED
            "hiatus" in statusText || "on hold" in statusText -> MangaState.PAUSED
            "cancel" in statusText || "abandon" in statusText -> MangaState.ABANDONED
            else -> MangaState.ONGOING
        }

        val chapters = loadChapterList(doc, pageUrl)

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
        val images = doc.select(
            "div.page-break img, div.reading-content img, .wp-manga-chapter-img img"
        )
        return images.mapIndexedNotNull { index, img ->
            val url = (img.attr("data-src").takeIf { it.isNotEmpty() }
                ?: img.attr("data-lazy-src").takeIf { it.isNotEmpty() }
                ?: img.attr("src")).trim().fixProtocol()
            if (url.isEmpty()) null
            else MangaPage(
                id = (chapter.id * 1000L + index),
                url = url,
                preview = null,
                source = customSource,
            )
        }
    }

    // ── List fetching ─────────────────────────────────────────────────────────

    private fun latestManga(offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE
        val orderParam = when (order) {
            SortOrder.POPULARITY -> "trending"
            SortOrder.RATING -> "rating"
            SortOrder.NEWEST -> "new-manga"
            else -> "latest"
        }
        val ajaxOrder = when (orderParam) {
            "trending" -> "meta_value_num" to "_wp_manga_views"
            "rating" -> "meta_value_num" to "_wp_manga_average_rating"
            "new-manga" -> "date" to ""
            else -> "modified" to ""
        }
        val ajaxResult = runCatching { fetchListAjax(page, ajaxOrder.first, ajaxOrder.second) }.getOrNull()
        if (!ajaxResult.isNullOrEmpty()) return ajaxResult

        // Different Madara deployments use different URL slugs for the comic listing.
        // Try every common slug so manhwa sites (manhwa/), manhua sites (manhua/),
        // and generic WordPress manga sites (comic/, webtoon/) all work without
        // needing site-specific configuration.
        val paged = page + 1
        val slugsToTry = listOf("manga", "manhwa", "manhua", "webtoon", "comic")
        for (slug in slugsToTry) {
            val result = runCatching {
                parseMangaListPage(fetchDocument("$baseUrl/$slug/?m_orderby=$orderParam&paged=$paged"))
            }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        // Last resort: WordPress root URL with orderby param
        return runCatching {
            parseMangaListPage(fetchDocument("$baseUrl/?m_orderby=$orderParam&paged=$paged"))
        }.getOrElse { emptyList() }
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val page = offset / PAGE_SIZE + 1
        val url = "$baseUrl/?s=$encoded&post_type=wp-manga&paged=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun browseByGenre(genreKey: String, offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val orderParam = when (order) {
            SortOrder.POPULARITY -> "trending"
            SortOrder.RATING -> "rating"
            SortOrder.NEWEST -> "new-manga"
            else -> "latest"
        }
        val url = "$baseUrl/manga/?genre=$genreKey&m_orderby=$orderParam&paged=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun fetchListAjax(page: Int, orderby: String, metaKey: String): List<Manga> {
        val bodyBuilder = FormBody.Builder()
            .add("action", "madara_load_more")
            .add("page", page.toString())
            .add("template", "madara-core/content/content-archive")
            .add("vars[orderby]", orderby)
            .add("vars[template]", "archive")
            .add("vars[sidebar]", "full")
            .add("vars[post_type]", "wp-manga")
            .add("vars[posts_per_page]", PAGE_SIZE.toString())
        if (metaKey.isNotEmpty()) {
            bodyBuilder.add("vars[meta_key]", metaKey)
        }
        val request = Request.Builder()
            .url("$baseUrl/wp-admin/admin-ajax.php")
            .post(bodyBuilder.build())
            .header("User-Agent", USER_AGENT)
            .header("Referer", baseUrl)
            .build()
        // Use ajaxClient (8s/12s timeouts) — AJAX is either fast or unavailable.
        val resp = ajaxClient.newCall(request).execute()
        val html = resp.use { it.body?.string() ?: return emptyList() }
        return parseMangaListItems(Jsoup.parse(html, baseUrl))
    }

    private fun parseMangaListPage(doc: Document): List<Manga> = parseMangaListItems(doc)

    private fun parseMangaListItems(doc: Document): List<Manga> {
        // Selector cascade: standard Madara → older Madara → generic manga CMSes
        val items = doc.select("div.page-item-detail, div.c-tabs-item__content")
            .ifEmpty { doc.select(".c-image-inner").map { it.parent() ?: it } }
            .ifEmpty { doc.select(".manga-item, .comics-item, li.manga-item, .bs, .bsx") }
            .ifEmpty { doc.select("article.manga, div.manga-entry, .item-thumb") }
        return items.mapNotNull { parseMangaItem(it) }
    }

    private fun parseMangaItem(el: Element): Manga? {
        val anchor = el.selectFirst(".post-title a, h3.h5 a, h3 a, h5 a") ?: return null
        val title = anchor.text().trim().takeIf { it.isNotEmpty() } ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("data-src") ?: coverImg?.attr("src") ?: "").fixProtocol()
        return buildManga(title, pageUrl, coverUrl)
    }

    // ── Chapter fetching ──────────────────────────────────────────────────────

    private fun loadChapterList(doc: Document, pageUrl: String): List<MangaChapter> {
        val postId = doc.selectFirst(
            "input#manga-chapters-holder, .rating-post-id, [id=manga-chapters-holder]"
        )?.attr("data-id")

        if (!postId.isNullOrEmpty()) {
            val ajax = runCatching { fetchChaptersAjax(postId, pageUrl) }.getOrNull()
            if (!ajax.isNullOrEmpty()) return ajax
        }

        return doc.select("li.wp-manga-chapter")
            .mapIndexedNotNull { i, el -> chapterFromElement(el, i) }
            .reversed()
    }

    private fun fetchChaptersAjax(postId: String, referer: String): List<MangaChapter> {
        val body = FormBody.Builder()
            .add("action", "manga_get_chapters")
            .add("manga", postId)
            .build()
        val request = Request.Builder()
            .url("$baseUrl/wp-admin/admin-ajax.php")
            .post(body)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .build()
        val resp = ajaxClient.newCall(request).execute()
        val html = resp.use { it.body?.string() ?: return emptyList() }
        return Jsoup.parse(html, baseUrl)
            .select("li.wp-manga-chapter")
            .mapIndexedNotNull { i, el -> chapterFromElement(el, i) }
            .reversed()
    }

    private fun chapterFromElement(el: Element, fallbackIndex: Int): MangaChapter? {
        val anchor = el.selectFirst("a") ?: return null
        val url = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val rawTitle = anchor.text().trim().ifEmpty { "Chapter ${fallbackIndex + 1}" }
        val number = CHAPTER_NUMBER_RE.find(rawTitle)?.groupValues?.getOrNull(1)
            ?.toFloatOrNull() ?: (fallbackIndex + 1).toFloat()
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

    // ── Helpers ───────────────────────────────────────────────────────────────

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
        private const val PAGE_SIZE = 16
        // Browser-like UA avoids being blocked by sites with basic bot-detection.
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val CHAPTER_NUMBER_RE = Regex("""[Cc]hapter[s]?\s*([\d.]+)""")

        /** Standard client for page fetches (detail pages, search, genre browse). */
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }

        /**
         * Shorter-timeout client used exclusively for AJAX calls.
         * Madara AJAX endpoints (/wp-admin/admin-ajax.php) are either fast or
         * not available at all; a 30-second wait just causes the "spinning
         * forever" symptom.  If the AJAX call fails, we fall back to plain HTML.
         */
        private val ajaxClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
