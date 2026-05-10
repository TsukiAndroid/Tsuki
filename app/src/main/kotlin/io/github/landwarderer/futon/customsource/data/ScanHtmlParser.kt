package io.github.landwarderer.futon.customsource.data

import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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
 * Parser for sites using the Scan CMS — a PHP scanlation group platform used by
 * 8 sites including Sushiscan.net, Lelscans, Reaperscans (legacy), and several
 * French/Spanish scanlation teams.
 *
 * Key selectors:
 *   List → /manga?page={n}&order={sort} → .manga-item, .entry-header, .series-item
 *   Search → /search?q={query} → same card structure
 *   Chaps → .chapter-list li a or .chapters-list li a
 *   Pages → images extracted from JS variable or .reading-content img
 */
class ScanHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val tag = filter?.tags?.firstOrNull()
        val page = offset / PAGE_SIZE + 1
        return runCatching {
            val url = if (!query.isNullOrBlank()) {
                "$baseUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page"
            } else {
                buildString {
                    append(baseUrl)
                    append("/manga?page=")
                    append(page)
                    if (tag != null) {
                        append("&genre=")
                        append(java.net.URLEncoder.encode(tag.key, "UTF-8"))
                    }
                    append("&order=")
                    when (order) {
                        SortOrder.ALPHABETICAL -> append("title")
                        SortOrder.POPULARITY -> append("views")
                        SortOrder.RATING -> append("rating")
                        else -> append("update")
                    }
                }
            }
            parseMangaList(fetchDocument(url))
        }.getOrElse { emptyList() }
    }

    fun getDetails(manga: Manga): Manga {
        return runCatching {
            val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
            val doc = fetchDocument(pageUrl)

            val statusText = doc.selectFirst(".status, .manga-status, [class*=status]")
                ?.text()?.lowercase().orEmpty()
            val state = when {
                "ongoing" in statusText || "en cours" in statusText || "en curso" in statusText -> MangaState.ONGOING
                "completed" in statusText || "terminé" in statusText || "finalizado" in statusText -> MangaState.FINISHED
                "hiatus" in statusText -> MangaState.PAUSED
                "dropped" in statusText || "abandonné" in statusText -> MangaState.ABANDONED
                else -> null
            }

            val tags = doc.select(".genres a, .genre-tags a, [class*=genre-item] a").mapNotNullToSet { a ->
                val key = a.attr("href").trimEnd('/').substringAfterLast('/')
                    .takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
                MangaTag(title = a.text().trim(), key = key, source = customSource)
            }

            val description = doc.selectFirst(".synopsis, .manga-synopsis, .description")?.text()?.trim()

            val chapters = doc.select(".chapter-list li a, .chapters-list li a, ul.chapter-list a")
                .mapIndexedNotNull { i, a ->
                    val href = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                    val rawName = a.text().trim().ifEmpty { "Chapter ${i + 1}" }
                    val number = CHAPTER_RE.find(rawName)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                        ?: (i + 1).toFloat()
                    val dateText = a.selectFirst("span.date, .chapter-date, time")?.text()?.trim() ?: ""
                    MangaChapter(
                        id = href.hashCode().toLong(),
                        title = rawName,
                        number = number,
                        volume = 0,
                        url = href,
                        scanlator = null,
                        uploadDate = parseScanDate(dateText),
                        branch = null,
                        source = customSource,
                    )
                }.reversed()

            manga.copy(state = state, tags = tags, description = description, chapters = chapters)
        }.getOrElse { manga }
    }

    fun getPages(chapter: MangaChapter): List<MangaPage> {
        return runCatching {
            val doc = fetchDocument(chapter.url)
            // Scan sites sometimes embed images in a JS variable
            val scriptText = doc.select("script").joinToString("\n") { it.html() }
            val jsonMatch = JS_IMAGES_RE.find(scriptText)
            if (jsonMatch != null) {
                val jsonStr = jsonMatch.groupValues[1]
                return@runCatching runCatching {
                    val arr = org.json.JSONArray(jsonStr)
                    (0 until arr.length()).mapNotNull { i ->
                        val url = arr.optString(i).takeIf { it.isNotEmpty() }?.fixProtocol()
                            ?: return@mapNotNull null
                        MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
                    }
                }.getOrElse { emptyList() }
            }
            // HTML fallback
            val images = doc.select(".reading-content img, .chapter-content img, #reader img")
                .ifEmpty { doc.select("img[src*=/scans/], img[src*=/manga/]") }
            images.mapIndexedNotNull { index, img ->
                val url = (img.attr("data-src").takeIf { it.isNotEmpty() }
                    ?: img.attr("src")).trim().fixProtocol()
                if (url.isEmpty()) null
                else MangaPage(id = chapter.id * 1000L + index, url = url, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    fun getGenres(): Set<MangaTag> {
        return runCatching {
            val doc = fetchDocument("$baseUrl/manga")
            doc.select(".genre-list a, .filter-genre a, [class*=genre] a").mapNotNullToSet { a ->
                val key = a.attr("href").trimEnd('/').substringAfterLast('/')
                    .takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
                MangaTag(title = a.text().trim(), key = key, source = customSource)
            }
        }.getOrElse { emptySet() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select(".manga-item, .entry-header, .series-item, .book-item, .manga-poster")
            .ifEmpty { doc.select("div.col-6, div.col-md-3, div.col-sm-4").filter { it.selectFirst("a[href]") != null } }
            .mapNotNull { div ->
                val anchor = div.selectFirst("a[href]") ?: return@mapNotNull null
                val href = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val title = div.selectFirst("h3, h4, .title, .manga-title")?.text()?.trim()
                    ?: anchor.attr("title").ifEmpty { anchor.text().trim() }.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val img = div.selectFirst("img")
                val coverUrl = (img?.attr("data-src") ?: img?.attr("src") ?: "").fixProtocol()
                val relativePath = runCatching {
                    val uri = java.net.URI(href)
                    uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
                }.getOrElse { href }
                Manga(
                    id = href.hashCode().toLong(),
                    title = title,
                    altTitles = emptySet(),
                    url = relativePath,
                    publicUrl = href,
                    rating = 0f,
                    contentRating = ContentRating.SAFE,
                    coverUrl = coverUrl,
                    tags = emptySet(),
                    state = null,
                    authors = emptySet(),
                    largeCoverUrl = coverUrl,
                    description = null,
                    chapters = null,
                    source = customSource,
                )
            }
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

    private fun parseScanDate(text: String): Long {
        if (text.isBlank()) return 0L
        return runCatching {
            val lower = text.lowercase()
            val now = System.currentTimeMillis()
            when {
                "ago" in lower || "il y a" in lower || "hace" in lower -> {
                    val num = Regex("(\\d+)").find(lower)?.groupValues?.getOrNull(1)?.toLong() ?: 0L
                    val unit = when {
                        "min" in lower -> 60_000L
                        "hour" in lower || "heure" in lower || "hora" in lower -> 3_600_000L
                        "day" in lower || "jour" in lower || "día" in lower -> 86_400_000L
                        "week" in lower || "semaine" in lower || "semana" in lower -> 604_800_000L
                        "month" in lower || "mois" in lower || "mes" in lower -> 2_592_000_000L
                        else -> 1L
                    }
                    now - num * unit
                }
                else -> java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.FRENCH).parse(text)?.time ?: 0L
            }
        }.getOrElse { 0L }
    }

    private fun <T : Any, R : Any> Iterable<T>.mapNotNullToSet(transform: (T) -> R?): Set<R> {
        val result = LinkedHashSet<R>()
        for (item in this) { transform(item)?.let { result += it } }
        return result
    }

    private fun String?.fixProtocol(): String = when {
        this == null || isEmpty() -> ""
        startsWith("//") -> "https:$this"
        else -> this
    }

    companion object {
        private const val PAGE_SIZE = 24
        private const val USER_AGENT = "Tsuki/1.0 (Android)"
        private val CHAPTER_RE = Regex("""[Cc]hapter[s]?\s*([\d.]+)""")
        private val JS_IMAGES_RE = Regex("""(?:images|pages|imgs)\s*[:=]\s*(\[[^\]]+])""")
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
