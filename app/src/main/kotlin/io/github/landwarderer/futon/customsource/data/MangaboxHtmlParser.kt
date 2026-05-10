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
 * Parser for sites using the Mangabox CMS — a Mangakakalot successor platform
 * used by 7 sites including Mangakakalot.to, MangaBox, and derivatives.
 *
 * Mangabox is spiritually similar to Manganelo but uses slightly different
 * URL structures and selectors. Distinguishing feature: /manga-list endpoint
 * with type=latest/top and distinct .content-genres-item tag list.
 *
 * Key selectors:
 *   List  → .content-genres-item (grid), .list-truyen-item-wrap (list)
 *   Sort  → /manga-list?type=latest|topview&category={id}&page={n}
 *   Chaps → #chapter container, row-content-chapter li a
 *   Pages → .container-chapter-reader img
 */
class MangaboxHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val tag = filter?.tags?.firstOrNull()
        val page = offset / PAGE_SIZE + 1
        return runCatching {
            if (!query.isNullOrBlank()) {
                val encoded = java.net.URLEncoder.encode(query, "UTF-8").replace("+", "_")
                val searchUrl = "$baseUrl/search/story/${encoded}?page=$page"
                return@runCatching parseMangaList(fetchDocument(searchUrl))
            }
            val url = buildString {
                append(baseUrl)
                append("/manga-list")
                append("?type=")
                when (order) {
                    SortOrder.POPULARITY -> append("topview")
                    SortOrder.NEWEST -> append("newest")
                    else -> append("latest")
                }
                if (tag != null) {
                    append("&category=")
                    append(tag.key)
                }
                append("&page=")
                append(page)
            }
            parseMangaList(fetchDocument(url))
        }.getOrElse { emptyList() }
    }

    fun getDetails(manga: Manga): Manga {
        return runCatching {
            val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
            val doc = fetchDocument(pageUrl)

            val statusText = doc.selectFirst("li:has(.info-status), table.variations-tableInfo tr:has(.table-label:contains(Status)) .table-value")
                ?.text()?.lowercase().orEmpty()
            val state = when {
                "ongoing" in statusText || "on going" in statusText -> MangaState.ONGOING
                "completed" in statusText || "complete" in statusText -> MangaState.FINISHED
                else -> null
            }

            val tags = doc.select(".variations-tableInfo tr:has(.table-label:contains(Genres)) .table-value a, " +
                ".info-genres a, a.a-h[href*=/manga-list?category]").mapNotNullToSet { a ->
                val href = a.attr("href")
                val key = if (href.contains("category=")) {
                    href.substringAfter("category=").substringBefore("&").takeIf { it.isNotEmpty() }
                } else {
                    href.trimEnd('/').substringAfterLast('/').takeIf { it.isNotEmpty() }
                } ?: return@mapNotNullToSet null
                MangaTag(title = a.text().trim(), key = key, source = customSource)
            }

            val description = doc.selectFirst(".panel-story-description .panel-story-description__story, " +
                ".story-detail-info-description")?.text()?.trim()

            val chapters = doc.select(".row-content-chapter li.a-h, #chapter_list li, ul.chapter-list li")
                .mapIndexedNotNull { i, li ->
                    val a = li.selectFirst("a[href]") ?: return@mapIndexedNotNull null
                    val href = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                    val rawName = a.text().trim().ifEmpty { "Chapter ${i + 1}" }
                    val number = CHAPTER_RE.find(rawName)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                        ?: (i + 1).toFloat()
                    val dateText = li.selectFirst("span.chapter-time, .chapter_time, .chapter-date")
                        ?.text()?.trim() ?: ""
                    MangaChapter(
                        id = href.hashCode().toLong(),
                        title = rawName,
                        number = number,
                        volume = 0,
                        url = href,
                        scanlator = null,
                        uploadDate = parseMangaboxDate(dateText),
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
            val images = doc.select(".container-chapter-reader img, .reading-content img, #chapter-content img")
            images.mapIndexedNotNull { index, img ->
                val url = (img.attr("data-src").takeIf { it.isNotEmpty() }
                    ?: img.attr("data-lazy-src").takeIf { it.isNotEmpty() }
                    ?: img.attr("src")).trim().fixProtocol()
                if (url.isEmpty()) null
                else MangaPage(id = chapter.id * 1000L + index, url = url, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    fun getGenres(): Set<MangaTag> {
        return runCatching {
            val doc = fetchDocument("$baseUrl/manga-list")
            doc.select("a[href*=/manga-list?category], a[href*=category=].a-h").mapNotNullToSet { a ->
                val key = a.attr("href").substringAfter("category=").substringBefore("&")
                    .takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
                MangaTag(title = a.text().trim(), key = key, source = customSource)
            }
        }.getOrElse { emptySet() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseMangaList(doc: Document): List<Manga> {
        val items = doc.select(".content-genres-item, .list-truyen-item-wrap")
            .ifEmpty { doc.select(".story_item, .truyen_item") }
        return items.mapNotNull { div ->
            val anchor = div.selectFirst("a[href]") ?: return@mapNotNull null
            val href = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = div.selectFirst("h3 a, h3, .story_name a")?.text()?.trim()
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

    private fun parseMangaboxDate(text: String): Long {
        if (text.isBlank()) return 0L
        return runCatching {
            java.text.SimpleDateFormat("MMM dd,yyyy", java.util.Locale.ENGLISH).parse(text)?.time ?: 0L
        }.getOrElse {
            runCatching {
                java.text.SimpleDateFormat("MM/dd/yy", java.util.Locale.ENGLISH).parse(text)?.time ?: 0L
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
        private const val PAGE_SIZE = 24
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
