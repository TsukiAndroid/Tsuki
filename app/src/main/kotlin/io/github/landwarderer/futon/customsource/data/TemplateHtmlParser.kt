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
 *  genres      → getGenres()  (auto-detects if no explicit config)
 *
 * Template lookup uses [ParserTemplateRepository.peekByName], which is
 * available without dependency injection via the singleton companion.
 *
 * URL resolution: all relative URLs (/ paths, protocol-relative, relative paths)
 * are resolved against the document's base URI before being stored or returned.
 */
class TemplateHtmlParser(
    private val customSource: CustomMangaSource,
) {

    private val baseUrl get() = customSource.source.cleanBaseUrl

    private val template: JSONObject? by lazy {
        val name = customSource.source.parserSourceName ?: return@lazy null
        val raw = ParserTemplateRepository.peekByName(name)?.rawJson ?: return@lazy null
        runCatching { JSONObject(raw) }.getOrNull()
    }

    private val mangaListSection   get() = template?.optJSONObject("mangaList")
    private val mangaDetailSection get() = template?.optJSONObject("mangaDetail")
    private val chapterListSection get() = template?.optJSONObject("chapterList")
    private val pageListSection    get() = template?.optJSONObject("pageList")
    private val genresSection      get() = template?.optJSONObject("genres")

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

        if (!query.isNullOrBlank()) return searchManga(section, query, page)

        return when (method) {
            "POST" -> {
                val action = section.optString("action").ifEmpty { "madara_load_more" }
                fetchPostList(endpointUrl, page, action, section)
            }
            else -> {
                val base = endpointUrl.trimEnd('/')
                // Page 1: always use the bare endpoint — avoids ?page=1 rejections on
                // sites that only accept the path without a query string (e.g. manhwaread.com).
                // Page 2+: use the pagination strategy stored in the template.
                //   "path" / "wordpress" → WordPress /page/N/ archive style
                //   anything else        → query-param ?pageParam=N style
                val url = when {
                    page <= 1 -> "$base/"
                    pagination == "path" || pagination == "wordpress" -> "$base/page/$page/"
                    else -> {
                        val pageParam = section.optString("pageParam", "page")
                        "$base?$pageParam=$page"
                    }
                }
                fetchGetList(section, url)
            }
        }
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        val doc = runCatching { fetchDocument(pageUrl) }.getOrElse { return manga }
        val section = mangaDetailSection

        val titleSel = section?.optString("titleSelector")?.ifEmpty { null } ?: "h1"
        val coverSel = section?.optString("coverSelector")?.ifEmpty { null }
        val descSel  = section?.optString("descriptionSelector")?.ifEmpty { null }

        // Extract title: use selector but fall back to og:title when selector returns the
        // site name or something clearly wrong (very short or matches the source name).
        val title = extractDetailTitle(doc, titleSel, manga.title, pageUrl)

        // Extract cover: configured selector → list-page cover selector → og:image → existing
        val coverUrl = extractDetailCover(doc, coverSel, pageUrl, manga.coverUrl)

        val description = descSel?.let { sel ->
            doc.selectFirst(sel)?.let { el ->
                el.select("p").joinToString("\n") { it.text() }.trim().ifEmpty { el.text().trim() }
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
            // Resolve ALL relative URL forms against the chapter page URL
            val url = img.imageUrl(chapter.url)
            if (url.isBlank() || !url.startsWith("http")) null
            else MangaPage(
                id      = chapter.id * 1000L + idx,
                url     = url,
                preview = null,
                source  = customSource,
            )
        }
    }

    /**
     * Returns genre tags for the source.
     *
     * Priority:
     *  1. Explicit `genres` section in template (endpoint + selector)
     *  2. Auto-detection: scans homepage and common listing pages for genre links
     */
    fun getGenres(): Set<MangaTag> {
        val section = genresSection
        if (section != null) {
            val endpoint = section.optString("endpoint").ifEmpty { null }
            val selector = section.optString("selector").ifEmpty { null }
            if (endpoint != null && selector != null) {
                val url = if (endpoint.startsWith("http")) endpoint else "$baseUrl$endpoint"
                val doc = runCatching { fetchDocument(url) }.getOrElse { return emptySet() }
                val tags = extractTagsFromDoc(doc, selector)
                if (tags.isNotEmpty()) return tags
            }
        }
        return autoDetectGenres()
    }

    // ── Private: title / cover extraction ─────────────────────────────────────

    private fun extractDetailTitle(
        doc: Document,
        selector: String,
        fallback: String?,
        pageUrl: String,
    ): String {
        // Try the configured selector — but validate it looks like a manga title,
        // not a site name or nav element.
        val sourceName = customSource.source.name
        val selectorText = doc.selectFirst(selector)?.text()?.trim()
        if (!selectorText.isNullOrEmpty() &&
            selectorText != sourceName &&
            selectorText.length > 1
        ) return selectorText

        // og:title is almost always the manga title for detail pages — strip the site suffix
        val ogTitle = doc.selectFirst("meta[property=og:title]")?.attr("content")?.trim()
        if (!ogTitle.isNullOrEmpty()) {
            val cleaned = ogTitle
                .substringBefore(" - ").substringBefore(" | ").substringBefore(" – ")
                .trim()
            if (cleaned.isNotEmpty() && cleaned != sourceName) return cleaned
        }

        // Try more specific heading selectors in priority order
        for (sel in listOf(
            "h1.entry-title", ".post-title h1", ".manga-title h1",
            ".series-title", "h1.title", "h2.title",
        )) {
            val t = doc.selectFirst(sel)?.text()?.trim()
            if (!t.isNullOrEmpty() && t != sourceName) return t
        }

        return fallback.orEmpty()
    }

    private fun extractDetailCover(
        doc: Document,
        selector: String?,
        pageUrl: String,
        fallbackUrl: String?,
    ): String {
        // Configured selector
        if (selector != null) {
            val url = doc.selectFirst(selector)?.imageUrl(pageUrl)
            if (!url.isNullOrEmpty() && url.startsWith("http")) return url
        }
        // Try list-level cover selector if stored in mangaList section
        val listCoverSel = mangaListSection?.optString("coverSelector")?.ifEmpty { null }
        if (listCoverSel != null) {
            val url = doc.selectFirst(listCoverSel)?.imageUrl(pageUrl)
            if (!url.isNullOrEmpty() && url.startsWith("http")) return url
        }
        // Common cover selectors
        for (sel in listOf(
            ".summary_image img", ".manga-thumbnail img", ".manga-cover img",
            ".book-cover img", ".thumb img", "img.wp-post-image",
            ".cover img", ".manga-poster img",
        )) {
            val url = doc.selectFirst(sel)?.imageUrl(pageUrl)
            if (!url.isNullOrEmpty() && url.startsWith("http")) return url
        }
        // og:image as final cover fallback
        val ogImage = doc.selectFirst("meta[property=og:image]")?.attr("content")
            ?.trim()?.resolveUrl(pageUrl)
        if (!ogImage.isNullOrEmpty() && ogImage.startsWith("http")) return ogImage

        return fallbackUrl.orEmpty()
    }

    // ── Private: list fetching ─────────────────────────────────────────────────

    private fun searchManga(section: JSONObject, query: String, page: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val searchEndpoint = section.optString("searchEndpoint").ifEmpty { "/" }
        val searchParam    = section.optString("searchParam").ifEmpty { "s" }
        val pageParam      = section.optString("pageParam", "page")
        val url = if (searchEndpoint.startsWith("http")) {
            "$searchEndpoint?$searchParam=$encoded&$pageParam=$page"
        } else {
            "$baseUrl$searchEndpoint?$searchParam=$encoded&$pageParam=$page"
        }
        return runCatching { parseMangaListPage(section, fetchDocument(url)) }.getOrElse { emptyList() }
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
            .url(url).post(body)
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
        val docBase = doc.baseUri().ifEmpty { baseUrl }
        val itemSel  = section.optString("itemSelector").ifEmpty { null }
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
        return items.mapNotNull { parseMangaItem(it, titleSel, coverSel, linkSel, docBase) }
    }

    private fun parseMangaItem(
        el: Element,
        titleSel: String?,
        coverSel: String?,
        linkSel: String?,
        docBase: String,
    ): Manga? {
        // ── Title ──────────────────────────────────────────────────────────────
        // We use bestTitle() which filters out button texts ("Read", "View", etc.)
        // and prefers heading elements over generic anchors.
        val title = el.bestTitle(titleSel) ?: return null

        // ── Link ───────────────────────────────────────────────────────────────
        val anchor: Element? = when {
            linkSel != null -> el.selectFirst(linkSel)
            else -> el.selectFirst("a[href]")
        }
        val pageUrl = anchor?.absUrl("href")?.ifEmpty { null } ?: return null

        // ── Cover ──────────────────────────────────────────────────────────────
        val coverEl = if (coverSel != null) el.selectFirst(coverSel) else el.selectFirst("img")
        val coverUrl = coverEl?.imageUrl(docBase) ?: ""

        return buildManga(title, pageUrl, coverUrl)
    }

    // ── Private: title helper ──────────────────────────────────────────────────

    /**
     * Extracts the best available title from a manga card element.
     *
     * Strategy:
     *  1. Try each candidate from [titleSel] (comma-separated), pick the first
     *     whose text is NOT a UI-button word and is at least 2 chars.
     *  2. Fall back to heading elements (h1–h5).
     *  3. Fall back to img alt text.
     *  4. Skip any `<a>` whose text is a known button label.
     */
    private fun Element.bestTitle(titleSel: String?): String? {
        if (titleSel != null) {
            // Iterate each candidate selector separately so we try them in order
            for (sel in titleSel.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
                for (candidate in select(sel)) {
                    val text = candidate.ownText().trim().ifEmpty { candidate.text().trim() }
                    if (text.length >= 2 && text.lowercase() !in SKIP_TITLE_TEXTS) return text
                }
            }
        }
        // Heading elements (never contain button labels)
        for (h in listOf("h1", "h2", "h3", "h4", "h5")) {
            val t = selectFirst(h)?.text()?.trim()
            if (!t.isNullOrEmpty() && t.lowercase() !in SKIP_TITLE_TEXTS) return t
        }
        // img[alt] — almost always the manga title on card thumbnails
        val alt = selectFirst("img")?.attr("alt")?.trim()
        if (!alt.isNullOrEmpty() && alt.length >= 2 && alt.lowercase() !in SKIP_TITLE_TEXTS)
            return alt
        // Last resort: any anchor whose text isn't a button
        for (a in select("a[href]")) {
            val t = a.ownText().trim().ifEmpty { a.text().trim() }
            if (t.length >= 2 && t.lowercase() !in SKIP_TITLE_TEXTS) return t
        }
        return null
    }

    // ── Private: image URL helper ──────────────────────────────────────────────

    /**
     * Extracts and resolves the image URL from an element, checking every common
     * lazy-load attribute used by WordPress plugins and CDNs.
     *
     * Attribute check order:
     *  data-src → data-lazy-src → data-original → data-bg →
     *  srcset (first entry) → src
     *
     * All relative URLs are resolved against [docBase].
     */
    private fun Element.imageUrl(docBase: String): String {
        val raw = attr("data-src").takeIf { it.isNotEmpty() }
            ?: attr("data-lazy-src").takeIf { it.isNotEmpty() }
            ?: attr("data-original").takeIf { it.isNotEmpty() }
            ?: attr("data-bg").takeIf { it.isNotEmpty() }
            ?: attr("srcset").trim().takeIf { it.isNotEmpty() }
                ?.split(",")?.firstOrNull()?.trim()?.split("\\s+".toRegex())?.firstOrNull()
            ?: attr("src")
        return raw.trim().resolveUrl(docBase)
    }

    // ── Private: URL resolution ────────────────────────────────────────────────

    /**
     * Resolves any URL form against [base]:
     *  - Absolute (http/https)  → returned as-is
     *  - Protocol-relative (//) → prefixed with "https:"
     *  - Absolute path (/)      → scheme + host prepended from base
     *  - Relative path          → resolved via URI.resolve()
     *  - null / blank           → empty string
     */
    private fun String?.resolveUrl(base: String): String {
        if (this == null || this.isBlank()) return ""
        return when {
            startsWith("http://") || startsWith("https://") -> this
            startsWith("//") -> "https:$this"
            startsWith("/") -> runCatching {
                val u = URI(base)
                "${u.scheme}://${u.host}$this"
            }.getOrElse { this }
            else -> runCatching { URI(base).resolve(this).toString() }.getOrElse { this }
        }
    }

    // ── Private: chapter list ──────────────────────────────────────────────────

    private fun loadChapterList(doc: Document, pageUrl: String): List<MangaChapter> {
        val section  = chapterListSection
        val action   = section?.optString("action")?.ifEmpty { null }
        val endpoint = section?.optString("endpoint")?.ifEmpty { null }

        // Explicit AJAX config
        if (action != null && endpoint != null) {
            val epUrl  = if (endpoint.startsWith("http")) endpoint else "$baseUrl$endpoint"
            val postId = extractPostId(doc)
            if (!postId.isNullOrEmpty()) {
                val ajax = runCatching { fetchChaptersAjax(epUrl, action, postId, pageUrl) }.getOrNull()
                if (!ajax.isNullOrEmpty()) return ajax
            }
        }

        // Static HTML pass
        val chapterSel = section?.optString("selector")?.ifEmpty { null }
            ?: EXTENDED_CHAPTER_SELECTOR
        val titleSel = section?.optString("titleSelector")?.ifEmpty { null } ?: "a"
        val linkSel  = section?.optString("linkSelector")?.ifEmpty { null } ?: "a"
        val staticChapters = doc.select(chapterSel)
            .mapIndexedNotNull { idx, el -> chapterFromElement(el, idx, titleSel, linkSel, pageUrl) }
            .reversed()

        // If very few chapters found, try AJAX fallbacks before giving up
        if (staticChapters.size <= 3) {
            val ajaxChapters = tryWordPressAjaxChapters(doc, pageUrl)
            if (ajaxChapters.size > staticChapters.size) return ajaxChapters
        }

        return staticChapters
    }

    /** Finds the WordPress/Madara post ID used in AJAX chapter requests. */
    private fun extractPostId(doc: Document): String? =
        doc.selectFirst(
            "input#manga-chapters-holder, .manga-chapters-holder, " +
            "[id=manga-chapters-holder], [data-id], [data-manga-id], [data-post]"
        )?.let { el ->
            el.attr("data-id").takeIf { it.isNotEmpty() }
                ?: el.attr("data-manga-id").takeIf { it.isNotEmpty() }
                ?: el.attr("data-post").takeIf { it.isNotEmpty() }
                ?: el.attr("value").takeIf { it.isNotEmpty() }
        }

    /**
     * Tries several WordPress AJAX patterns to load the full chapter list.
     * Called when the static HTML only reveals a partial list (first+last chapter preview).
     */
    private fun tryWordPressAjaxChapters(doc: Document, pageUrl: String): List<MangaChapter> {
        val postId = extractPostId(doc)

        // Pattern 1: Standard wp-admin/admin-ajax.php with various action names
        if (!postId.isNullOrEmpty()) {
            val ajaxUrl = "$baseUrl/wp-admin/admin-ajax.php"
            for (action in listOf("manga_get_chapters", "madara_load_more", "manga-chapters", "get_manga_chapters")) {
                val chapters = runCatching { fetchChaptersAjax(ajaxUrl, action, postId, pageUrl) }.getOrNull()
                if (!chapters.isNullOrEmpty()) return chapters
            }
        }

        // Pattern 2: /ajax/chapters appended to the manga URL (used by some custom themes)
        val slugBase = pageUrl.trimEnd('/')
        val ajaxHtml = runCatching {
            val req = Request.Builder()
                .url("$slugBase/ajax/chapters")
                .post(FormBody.Builder().build())
                .header("User-Agent", USER_AGENT)
                .header("Referer", pageUrl)
                .header("X-Requested-With", "XMLHttpRequest")
                .build()
            httpClient.newCall(req).execute().use { it.body?.string() ?: "" }
        }.getOrNull()
        if (!ajaxHtml.isNullOrEmpty()) {
            val parsed = Jsoup.parse(ajaxHtml, pageUrl)
            val chapters = parsed.select(EXTENDED_CHAPTER_SELECTOR)
                .mapIndexedNotNull { idx, el -> chapterFromElement(el, idx, "a", "a", pageUrl) }
                .reversed()
            if (chapters.isNotEmpty()) return chapters
        }

        // Pattern 3: Structural fallback — find any repeated anchor whose URL contains "chapter"
        val chapterLinks = doc.select("a[href*=chapter], a[href*=chap-], a[href*=/ch/], a[href*=/c/]")
            .filter { a ->
                val href = a.absUrl("href")
                href.startsWith(baseUrl) && href.length > baseUrl.length + 3
            }
        if (chapterLinks.size > 3) {
            return chapterLinks.mapIndexedNotNull { idx, a ->
                val url = a.absUrl("href").ifEmpty { return@mapIndexedNotNull null }
                val rawTitle = a.text().trim().ifEmpty { "Chapter ${idx + 1}" }
                val number = CHAPTER_NUM_RE.find(rawTitle)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                    ?: (idx + 1).toFloat()
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

        return emptyList()
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
            .url(endpointUrl).post(body)
            .header("User-Agent", USER_AGENT)
            .header("Referer", referer)
            .header("X-Requested-With", "XMLHttpRequest")
            .build()
        val html = httpClient.newCall(request).execute().use { it.body?.string() ?: "" }
        return Jsoup.parse(html, baseUrl)
            .select(EXTENDED_CHAPTER_SELECTOR)
            .mapIndexedNotNull { idx, el -> chapterFromElement(el, idx, "a", "a", referer) }
            .reversed()
    }

    private fun chapterFromElement(
        el: Element,
        idx: Int,
        titleSel: String,
        linkSel: String,
        pageBase: String,
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

    // ── Private: genre auto-detection ─────────────────────────────────────────

    /**
     * Scans the site's homepage and common listing pages for genre/category links.
     * Works without any explicit template configuration.
     */
    private fun autoDetectGenres(): Set<MangaTag> {
        // Selectors that typically find genre links on manga sites
        val genreSelectors = listOf(
            "a[href*=/genre/]", "a[href*=/genres/]", "a[href*=/manga-genre/]",
            "a[href*=/category/]", "a[href*=/categories/]",
            "a[href*=/tag/]", "a[href*=/tags/]",
            ".genre-item a", ".cat-item a", ".tag-item a",
            ".genres a", ".tags a", ".categories a",
            "li[class*=genre] a", "li[class*=cat-item] a",
            ".checkbox-manga-genre .checkbox", ".manga-genres .checkbox",
            "a.genre", ".genre-link", ".tag-link",
        )

        val candidateUrls = listOf(
            baseUrl,
            "$baseUrl/manga/",
            "$baseUrl/manhwa/",
            "$baseUrl/comics/",
            "$baseUrl/genre/",
            "$baseUrl/genres/",
        )

        for (url in candidateUrls) {
            val doc = runCatching { fetchDocument(url) }.getOrNull() ?: continue
            for (sel in genreSelectors) {
                val tags = extractTagsFromDoc(doc, sel)
                if (tags.size >= 3) return tags
            }
        }
        return emptySet()
    }

    private fun extractTagsFromDoc(doc: Document, selector: String): Set<MangaTag> =
        doc.select(selector).mapNotNull { el ->
            val anchor = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@mapNotNull null
            val href = anchor.absUrl("href").trimEnd('/')
            val key  = href.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = (anchor.attr("title").takeIf { it.isNotEmpty() }
                ?: anchor.text()).trim().ifEmpty { return@mapNotNull null }
            MangaTag(title = title, key = key, source = customSource)
        }.toSet()

    // ── Private: utilities ────────────────────────────────────────────────────

    private fun buildManga(title: String, pageUrl: String, coverUrl: String): Manga {
        val relativePath = runCatching {
            val uri = URI(pageUrl)
            uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
        }.getOrElse { pageUrl }
        return Manga(
            id           = pageUrl.hashCode().toLong(),
            title        = title,
            altTitles    = emptySet(),
            url          = relativePath,
            publicUrl    = pageUrl,
            rating       = 0f,
            contentRating = ContentRating.SAFE,
            coverUrl     = coverUrl,
            tags         = emptySet(),
            state        = MangaState.ONGOING,
            authors      = emptySet(),
            largeCoverUrl = coverUrl,
            description  = null,
            chapters     = null,
            source       = customSource,
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

    companion object {
        private const val PAGE_SIZE   = 16
        private const val USER_AGENT  = "Tsuki/1.0 (Android)"
        private val CHAPTER_NUM_RE    = Regex("""[Cc]hapter[s]?\s*([\d.]+)""")

        /**
         * Words that should never be used as a manga title.
         * These appear as overlay/button text in card thumbnails.
         */
        private val SKIP_TITLE_TEXTS = setOf(
            "read", "view", "new", "hot", "more", "see", "show", "load",
            "favorite", "add", "latest", "updated", "ongoing", "completed",
            "chapter", "chapters", "manga", "manhwa", "manhua", "webtoon",
        )

        /**
         * Extended chapter selector covering all major WordPress manga themes
         * and common custom layouts.
         */
        private const val EXTENDED_CHAPTER_SELECTOR =
            "li.wp-manga-chapter, .chapter-li, .chapter-item, li[class*=chapter], " +
            ".chapter-list li, .listing-chapters li, #chapters > a, .chapter-row, " +
            ".volume-chapter li, li.volume-chapter, li.chapter, .chapter_list li"

        /**
         * Cascade of container selectors tried when no explicit itemSelector is present.
         * Listed in priority order — most-specific / most-common CMS patterns first.
         * Multiple selectors at the same priority can be combined with commas in the template.
         */
        private val GENERIC_ITEM_SELECTORS = listOf(
            // Madara / WP-Manga
            "div.page-item-detail",
            "div.c-tabs-item__content",
            ".c-image-hover",
            // MangaThemesia
            "div.bsx",
            "div.bs",
            // Common WordPress article types
            "article.type-manga",
            "article.type-manhwa",
            "article.type-comic",
            "article.type-manhua",
            "article.type-webtoon",
            "article[class*=type-manga]",
            "article[class*=type-comic]",
            // Generic semantic class names
            ".manga-item",
            "li.manga-item",
            ".media.manga",
            ".manga-card",
            ".book-item",
            ".novel-item",
            ".series-item",
            ".series-card",
            ".story-item",
            ".list-truyen-item-wrap",
            "div.manga__item",
            // WP block post list
            "li.wp-block-post",
        )

        /** Cascade of title selectors tried when no explicit titleSelector is present. */
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
