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
 * Parser for sites using the WpComics CMS — a Vietnamese WordPress-based manga
 * platform powering 18 sites including NetTruyen/WpComics.vn, TruyenQQ variants,
 * and related Vietnamese/Japanese aggregator networks.
 *
 * Key selectors:
 *   List  → div.items div.item → div.box_tootip (tooltip) + div.image img (cover)
 *   Chaps → ul.list-chapter li a, with dates in .chapter-time
 *   Pages → div.reading-detail img
 */
class WpComicsHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    // WpComics uses /tim-truyen as the manga listing path with sort param
    private val listPath = "/tim-truyen"

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val tag = filter?.tags?.firstOrNull()
        val page = offset / PAGE_SIZE + 1
        return runCatching {
            val url = if (!query.isNullOrBlank()) {
                "$baseUrl$listPath?keyword=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page"
            } else {
                buildString {
                    append(baseUrl)
                    append(listPath)
                    if (tag != null) {
                        append("/")
                        append(tag.key)
                    }
                    append("?sort=")
                    append(when (order) {
                        SortOrder.POPULARITY -> "10"
                        SortOrder.NEWEST -> "15"
                        SortOrder.RATING -> "20"
                        else -> "0" // UPDATED
                    })
                    append("&page=")
                    append(page)
                }
            }
            parseMangaList(fetchDocument(url))
        }.getOrElse { emptyList() }
    }

    fun getDetails(manga: Manga): Manga {
        return runCatching {
            val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
            val doc = fetchDocument(pageUrl)

            val statusText = (
                doc.selectFirst(".status span") ?: doc.selectFirst("li:contains(Tình trạng) span")
                    ?: doc.selectFirst("li:contains(Status) span")
            )?.text()?.lowercase().orEmpty()

            val state = when {
                "đang tiến hành" in statusText || "ongoing" in statusText || "updating" in statusText -> MangaState.ONGOING
                "hoàn thành" in statusText || "completed" in statusText || "complete" in statusText -> MangaState.FINISHED
                else -> null
            }

            val tags = doc.select(".kind a, .genres a, [class*=genre] a").mapNotNullToSet { a ->
                val href = a.attr("href").trimEnd('/')
                val key = href.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
                MangaTag(title = a.text().trim(), key = key, source = customSource)
            }

            val description = doc.selectFirst(".detail-content p, .summary-content, .manga-summary")
                ?.text()?.trim()

            val chapters = doc.select("ul.list-chapter li a, .row-content-chapter li a").mapIndexedNotNull { i, a ->
                val href = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                val rawName = a.selectFirst(".chapter-name, span:first-child") ?.text()?.trim() ?: a.text().trim()
                val number = CHAPTER_RE.find(rawName)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                    ?: (i + 1).toFloat()
                val dateText = a.selectFirst(".chapter-time, .at-a-glance") ?.text()?.trim() ?: ""
                MangaChapter(
                    id = href.hashCode().toLong(),
                    title = rawName,
                    number = number,
                    volume = 0,
                    url = href,
                    scanlator = null,
                    uploadDate = parseViDate(dateText),
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
            val images = doc.select("div.reading-detail img, .page-chapter img, .chapter-content img")
                .ifEmpty { doc.select("img[data-original], img[src*=/uploads/]") }
            images.mapIndexedNotNull { index, img ->
                val url = (img.attr("data-original").takeIf { it.isNotEmpty() }
                    ?: img.attr("data-src").takeIf { it.isNotEmpty() }
                    ?: img.attr("src")).trim().fixProtocol()
                if (url.isEmpty()) null
                else MangaPage(id = chapter.id * 1000L + index, url = url, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    fun getGenres(): Set<MangaTag> {
        return runCatching {
            val doc = fetchDocument("$baseUrl$listPath")
            doc.select(".genre-item a, .list-genre a, [class*=category] a").mapNotNullToSet { a ->
                val href = a.attr("href").trimEnd('/')
                val key = href.substringAfterLast('/').takeIf { it.isNotEmpty() }
                    ?: return@mapNotNullToSet null
                MangaTag(title = a.text().trim(), key = key, source = customSource)
            }
        }.getOrElse { emptySet() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select("div.items div.item, .manga-list .item").mapNotNull { item ->
            val anchor = item.selectFirst("a[href]") ?: return@mapNotNull null
            val href = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = (item.selectFirst(".manga-name, h3 a, .book-title") ?: anchor).text().trim()
                .ifEmpty { anchor.attr("title") }.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val img = item.selectFirst("div.image img, .book-img img")
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

    private fun parseViDate(text: String): Long {
        if (text.isBlank()) return 0L
        return runCatching {
            val fmt = java.text.SimpleDateFormat("dd/MM/yy", java.util.Locale("vi"))
            fmt.parse(text)?.time ?: 0L
        }.getOrElse {
            runCatching {
                val fmt = java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale("vi"))
                fmt.parse(text)?.time ?: 0L
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
        private const val PAGE_SIZE = 48
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
