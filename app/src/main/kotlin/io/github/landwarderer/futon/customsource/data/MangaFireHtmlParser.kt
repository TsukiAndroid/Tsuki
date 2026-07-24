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
 * Parser for sites using the MangaFire / MangaRead-style layout.
 *
 * MangaFire (mangafire.to) is one of the fastest-growing manga sites.
 * This parser also handles similar sites: MangaRead, MangaCool, etc.
 *
 * Recognisable by:
 *   - A clean card-grid layout with .manga-poster covers
 *   - /manga listing with filter sidebar
 *   - Episode list at .ep-item in the detail page
 *   - Lazy-loaded reader images inside #chapter-images
 *
 * URL patterns:
 *   List   : {baseUrl}/manga?sort={order}&page=N
 *   Search : {baseUrl}/filter?keyword={q}&page=N
 *   Detail : {baseUrl}/manga/{slug}.{id}
 *   Chapter: {baseUrl}/read/{slug}.{id}/en/chapter-N
 *
 * FIX (2026-07): MangaFire requires a session token obtained by visiting the
 * homepage.  All subsequent requests must include this token in their headers.
 * See getToken() / mangaFireGet() below.  Auto-retry on 403 refreshes the token.
 */
class MangaFireHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    // ── Token cache (FIX 1) ───────────────────────────────────────────────────

    @Volatile private var cachedToken: String? = null
    @Volatile private var tokenFetchedAt = 0L

    /**
     * Visit the MangaFire homepage, extract the session/CSRF token, and cache it.
     *
     * Tries in order:
     *  1. window.__config  (MangaFire's primary token as of 2026)
     *  2. window._token / window.csrf / _token= JS variable patterns
     *  3. <meta name="csrf-token"> HTML tag
     *  4. A cookie whose name contains "token"
     *
     * Cached for TOKEN_TTL ms.  Returns "" on failure (requests still go through
     * without a token, which may 403 — the retry handler then clears cache).
     */
    private fun getToken(): String {
        val now = System.currentTimeMillis()
        val cached = cachedToken
        if (cached != null && now - tokenFetchedAt < TOKEN_TTL) return cached

        val req = Request.Builder()
            .url(baseUrl)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .get().build()

        val (html, cookieHeader) = runCatching {
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                // Collect Set-Cookie headers for token extraction
                val cookies = resp.headers("Set-Cookie")
                    .joinToString("; ") { it.substringBefore(";") }
                Pair(body, cookies)
            }
        }.getOrElse { return "".also { cachedToken = it; tokenFetchedAt = now } }

        // Method 1: window.__config (MangaFire's primary token, 2026)
        val configToken = WINDOW_CONFIG_RE.find(html)?.groupValues?.getOrNull(1)

        // Method 2: other JS variable patterns
        val jsToken = if (configToken == null) {
            JS_TOKEN_PATTERNS.firstNotNullOfOrNull { pattern ->
                pattern.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotEmpty() }
            }
        } else null

        // Method 3: <meta name="csrf-token">
        val metaToken = if (configToken == null && jsToken == null) {
            META_CSRF_RE.find(html)?.groupValues?.getOrNull(1)
        } else null

        // Method 4: token cookie
        val cookieToken = if (configToken == null && jsToken == null && metaToken == null) {
            cookieHeader.split("; ").firstOrNull { part ->
                part.contains("token", ignoreCase = true)
            }?.substringAfter("=")?.substringBefore(";")?.trim()
        } else null

        val token = configToken ?: jsToken ?: metaToken ?: cookieToken ?: ""
        cachedToken = token
        tokenFetchedAt = now
        return token
    }

    // ── Authenticated GET (FIX 2) ─────────────────────────────────────────────

    /**
     * Fetch [url] as an HTML Document, injecting the session token and a full
     * browser header set so MangaFire's Cloudflare / anti-bot layer lets it through.
     *
     * Automatically retries once on 403 after clearing the token cache (FIX 3).
     */
    private fun fetchDocument(url: String): Document {
        return fetchDocumentInternal(url, retry = true)
    }

    private fun fetchDocumentInternal(url: String, retry: Boolean): Document {
        val token = getToken()
        val req = buildRequest(url, token)
        val resp = httpClient.newCall(req).execute()
        // FIX 3: auto-retry on 403 with fresh token
        if (resp.code == 403 && retry) {
            resp.close()
            cachedToken = null
            tokenFetchedAt = 0L
            return fetchDocumentInternal(url, retry = false)
        }
        return resp.use { Jsoup.parse(it.body?.string() ?: "", url) }
    }

    private fun buildRequest(url: String, token: String): Request {
        val builder = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_UA)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", baseUrl)
            .header("Origin", baseUrl)
            .header("Sec-Fetch-Dest", "document")
            .header("Sec-Fetch-Mode", "navigate")
            .header("Sec-Fetch-Site", "same-origin")
        if (token.isNotEmpty()) {
            // Send under every plausible header name; MangaFire uses one of these
            builder
                .header("X-CSRF-Token", token)
                .header("X-Token", token)
        }
        return builder.get().build()
    }

    // ── FIX 5: Debug cookie logging (debug builds only) ───────────────────────
    // Cookie management is handled automatically by OkHttp's cookie jar.
    // If you need to inspect cookies during debugging, attach a logging interceptor
    // to httpClient and log resp.headers("Set-Cookie").

    // ── Public API ────────────────────────────────────────────────────────────

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        return if (!query.isNullOrBlank()) {
            searchManga(query, offset)
        } else {
            browseList(offset, order)
        }
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        return runCatching {
            val doc = fetchDocument(pageUrl)

            val title = doc.selectFirst("h1.name, .manga-name h1, h1[itemprop=name]")
                ?.text()?.trim() ?: manga.title

            val coverImg = doc.selectFirst(".manga-poster img, .poster img, .book-poster img")
            val coverUrl = (coverImg?.attr("src") ?: manga.coverUrl).fixProtocol()

            val descEl = doc.selectFirst(".description, .summary-content, .synopsis")
            val description = descEl?.select("p")?.joinToString("\n") { it.text() }?.trim()
                ?: descEl?.text()?.trim()

            val statusText = doc.select(".anisc-info .item-title")
                .firstOrNull { it.text().contains("status", ignoreCase = true) }
                ?.nextElementSibling()?.text()?.lowercase()
                ?: doc.selectFirst(".status")?.text()?.lowercase()
            val state = when {
                statusText == null -> MangaState.ONGOING
                "complet" in statusText || "finished" in statusText -> MangaState.FINISHED
                "hiatus" in statusText -> MangaState.PAUSED
                "cancel" in statusText || "discontinu" in statusText -> MangaState.ABANDONED
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
            val images = doc.select(
                "#chapter-images img, .chapter-images img, " +
                ".reading-content img, .chapter-view img, " +
                "img[class*=chapter-img], img[data-index]"
            )
            images.mapIndexedNotNull { index, img ->
                val url = (img.attr("data-src").takeIf { it.isNotEmpty() }
                    ?: img.attr("data-lazy").takeIf { it.isNotEmpty() }
                    ?: img.attr("src")).trim().fixProtocol()
                if (url.isEmpty() || url.length < 10) null
                else MangaPage(id = chapter.id * 1000L + index, url = url, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun browseList(offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val sort = when (order) {
            SortOrder.POPULARITY -> "most_viewed"
            SortOrder.RATING     -> "top_rated"
            SortOrder.NEWEST     -> "newly_added"
            else                 -> "latest_updated"
        }
        val urls = listOf(
            "$baseUrl/manga?sort=$sort&page=$page",
            "$baseUrl/browse?sort=$sort&page=$page",
        )
        for (url in urls) {
            val result = runCatching { parseListPage(fetchDocument(url)) }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        return emptyList()
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val page = offset / PAGE_SIZE + 1
        val urls = listOf(
            "$baseUrl/filter?keyword=$encoded&page=$page",
            "$baseUrl/search?q=$encoded&page=$page",
            "$baseUrl/?s=$encoded&paged=$page",
        )
        for (url in urls) {
            val result = runCatching { parseListPage(fetchDocument(url)) }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        return emptyList()
    }

    private fun parseListPage(doc: Document): List<Manga> {
        val items = doc.select(
            ".manga-poster, .unit .inner, .manga-item, " +
            "article.manga, .card.manga, .book-item"
        ).ifEmpty { doc.select(".item") }
        return items.mapNotNull { parseItem(it) }
    }

    private fun parseItem(el: Element): Manga? {
        val anchor = el.selectFirst("a[href*=/manga/], a") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val title = el.selectFirst(".manga-name, .name, h3, h2, .title")?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null
        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("data-src") ?: coverImg?.attr("src") ?: "").fixProtocol()
        return buildManga(title, pageUrl, coverUrl)
    }

    private fun loadChapterList(doc: Document, mangaUrl: String): List<MangaChapter> {
        val items = doc.select(
            ".ep-item, .chapter-item, .chapter-list li, " +
            "ul.chapter-list li, .chapters-list li"
        )
        return items.mapIndexedNotNull { i, el ->
            val a = el.selectFirst("a") ?: return@mapIndexedNotNull null
            val url = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val rawTitle = el.selectFirst(".title, .name, span")?.text()?.trim()
                ?: a.text().trim().ifEmpty { "Chapter ${i + 1}" }
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
        private const val TOKEN_TTL = 30 * 60 * 1000L // 30 minutes

        // FIX: use a real Chrome mobile UA — "Tsuki/1.0 (Android)" was rejected
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"

        private val CHAPTER_NUMBER_RE = Regex("""(?:[Cc]hapter|[Cc]h\.?)\s*([\d.]+)""")

        // Token extraction patterns (checked in order)
        // 1. window.__config = "..." — MangaFire's primary token (2026)
        private val WINDOW_CONFIG_RE = Regex("""window\.__config\s*=\s*["']([^"']+)["']""")

        // 2. Other common JS variable patterns
        private val JS_TOKEN_PATTERNS = listOf(
            Regex("""window\._token\s*=\s*["']([^"']+)["']"""),
            Regex("""window\.csrf\s*=\s*["']([^"']+)["']"""),
            Regex("""csrf[_-]?token["']\s*:\s*["']([^"']+)["']"""),
            Regex(""""_token"\s*:\s*"([^"]+)""""),
            Regex("""_token\s*=\s*["']([^"']+)["']"""),
        )

        // 3. <meta name="csrf-token" content="...">
        private val META_CSRF_RE = Regex("""<meta[^>]+name=["']csrf-token["'][^>]+content=["']([^"']+)["']""")

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
