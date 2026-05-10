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
 * Parser for sites using the MMRCMS (PHP Manga CMS) — one of the older but still
 * widely-deployed manga platforms powering 17+ sites including MangaReader.org,
 * Mangaeden, IsekaiScan, and many international manga sites.
 *
 * Distinctive features:
 *   - /filterList endpoint for browsing + /latest-release for recent updates
 *   - div.media cards in list view (Bootstrap 3 media grid)
 *   - /manga/{slug} for series detail with li.chapter-item chapter list
 */
class MmrcmsHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val tag = filter?.tags?.firstOrNull()
        val page = offset / PAGE_SIZE + 1
        return runCatching {
            if (order == SortOrder.UPDATED && query.isNullOrBlank() && tag == null) {
                return@runCatching parseMangaListUpdated(fetchDocument("$baseUrl/latest-release?page=$page"))
            }
            val url = buildString {
                append(baseUrl)
                append("/filterList?page=")
                append(page)
                append("&author=&tag=&alpha=")
                if (!query.isNullOrBlank()) append(java.net.URLEncoder.encode(query, "UTF-8"))
                append("&cat=")
                if (tag != null) append(java.net.URLEncoder.encode(tag.key, "UTF-8"))
                append("&sortBy=")
                when (order) {
                    SortOrder.POPULARITY -> append("views&asc=false")
                    SortOrder.ALPHABETICAL -> append("name&asc=true")
                    SortOrder.ALPHABETICAL_DESC -> append("name&asc=false")
                    else -> append("name&asc=true")
                }
            }
            parseMangaList(fetchDocument(url))
        }.getOrElse { emptyList() }
    }

    fun getDetails(manga: Manga): Manga {
        return runCatching {
            val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
            val doc = fetchDocument(pageUrl)

            val statusText = (doc.selectFirst(".label-success, .label-warning, .label-danger")
                ?: doc.selectFirst("li:contains(Status) span"))?.text()?.lowercase().orEmpty()
            val state = when {
                "ongoing" in statusText || "on going" in statusText -> MangaState.ONGOING
                "completed" in statusText || "complete" in statusText -> MangaState.FINISHED
                "cancelled" in statusText || "dropped" in statusText -> MangaState.ABANDONED
                else -> null
            }

            val tags = doc.select("a[href*=/category/], a[href*=/tag/]").mapNotNullToSet { a ->
                val key = a.attr("href").trimEnd('/').substringAfterLast('/')
                    .takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
                MangaTag(title = a.text().trim(), key = key, source = customSource)
            }

            val description = doc.selectFirst(".well p, .description, .summary")?.text()?.trim()

            val chapters = doc.select("ul.chp_lst li a, li.chapter-item a").mapIndexedNotNull { i, a ->
                val href = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                val rawName = (a.selectFirst("span.val") ?: a).text().trim()
                    .ifEmpty { "Chapter ${i + 1}" }
                val number = CHAPTER_RE.find(rawName)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                    ?: (i + 1).toFloat()
                val dateText = a.selectFirst("span.dte")?.text()?.trim() ?: ""
                MangaChapter(
                    id = href.hashCode().toLong(),
                    title = rawName,
                    number = number,
                    volume = 0,
                    url = href,
                    scanlator = null,
                    uploadDate = parseMmrDate(dateText),
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
            // MMRCMS sometimes puts image list in a <select> element for page navigation
            val selectOpts = doc.select("select.m option")
            if (selectOpts.isNotEmpty()) {
                return@runCatching selectOpts.mapIndexedNotNull { index, opt ->
                    val imgSrc = opt.attr("value").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                    MangaPage(
                        id = chapter.id * 1000L + index,
                        url = imgSrc.fixProtocol(),
                        preview = null,
                        source = customSource,
                    )
                }
            }
            // Otherwise scrape directly
            val images = doc.select("#reader-container img, .reader-image img, .chapter-image img")
                .ifEmpty { doc.select("img[src*=/content/], img[src*=/manga/]") }
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
            val doc = fetchDocument("$baseUrl/manga-list")
            doc.select("a[href*=/category/]").mapNotNullToSet { a ->
                val key = a.attr("href").trimEnd('/').substringAfterLast('/').takeIf { it.isNotEmpty() }
                    ?: return@mapNotNullToSet null
                MangaTag(title = a.text().trim(), key = key, source = customSource)
            }
        }.getOrElse { emptySet() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select("div.media").mapNotNull { div ->
            val anchor = div.selectFirst("a[href]") ?: return@mapNotNull null
            val href = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = div.selectFirst("div.media-body h5, .manga_name")?.text()?.trim()
                ?: anchor.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val img = div.selectFirst("img")
            val coverUrl = (img?.attr("src") ?: "").fixProtocol()
            buildManga(title, href, coverUrl)
        }
    }

    private fun parseMangaListUpdated(doc: Document): List<Manga> {
        return doc.select("div.manga-item, .group").mapNotNull { div ->
            val anchor = div.selectFirst("a[href]") ?: return@mapNotNull null
            val href = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = div.selectFirst(".manga_name, .title, h4")?.text()?.trim()
                ?: anchor.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val img = div.selectFirst("img")
            val coverUrl = (img?.attr("src") ?: "").fixProtocol()
            buildManga(title, href, coverUrl)
        }
    }

    private fun buildManga(title: String, href: String, coverUrl: String): Manga {
        val relativePath = runCatching {
            val uri = java.net.URI(href)
            uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
        }.getOrElse { href }
        return Manga(
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

    private fun parseMmrDate(text: String): Long {
        if (text.isBlank()) return 0L
        return runCatching {
            java.text.SimpleDateFormat("dd MMM. yyyy", java.util.Locale.ENGLISH).parse(text)?.time ?: 0L
        }.getOrElse {
            runCatching {
                java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.ENGLISH).parse(text)?.time ?: 0L
            }.getOrElse { 0L }
        }
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
        private const val PAGE_SIZE = 20
        private const val USER_AGENT = "Tsuki/1.0 (Android)"
        private val CHAPTER_RE = Regex("""[Cc]hapter[s]?\s*([\d.]+)""")
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
