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
 * Parser for sites using the Liliana CMS — a modern PHP manga platform with
 * a tag-based filter system and multi-language support. Used by 7 sites including
 * KulManga, Manga-Raw.club, and various international scanlation portals.
 *
 * Key selectors:
 *   List   → div#main div.grid > div (grid cards)
 *   Search → GET /search/{page}/?keyword={q}
 *   Filter → GET /filter/{page}/?sort=latest-updated&genres={key}&status={status}
 *   Chaps  → .list-chapter a or ul.chapter li a
 *   Pages  → .chapter-content img
 */
class LilianaHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val page = offset / PAGE_SIZE + 1
        return runCatching {
            val url = if (!query.isNullOrBlank()) {
                "$baseUrl/search/$page/?keyword=${java.net.URLEncoder.encode(query, "UTF-8")}"
            } else {
                buildString {
                    append(baseUrl)
                    append("/filter/")
                    append(page)
                    append("/?sort=")
                    when (order) {
                        SortOrder.UPDATED -> append("latest-updated")
                        SortOrder.POPULARITY -> append("views")
                        SortOrder.ALPHABETICAL -> append("az")
                        SortOrder.ALPHABETICAL_DESC -> append("za")
                        SortOrder.NEWEST -> append("new")
                        SortOrder.RATING -> append("score")
                        else -> append("latest-updated")
                    }
                    val includeTags = filter?.tags?.joinToString(",") { it.key }
                    val excludeTags = filter?.tagsExclude?.joinToString(",") { it.key }
                    if (!includeTags.isNullOrBlank()) {
                        append("&genres=")
                        append(includeTags)
                    }
                    if (!excludeTags.isNullOrBlank()) {
                        append("&notGenres=")
                        append(excludeTags)
                    }
                    filter?.states?.firstOrNull()?.let { state ->
                        append("&status=")
                        when (state) {
                            MangaState.ONGOING -> append("on-going")
                            MangaState.FINISHED -> append("completed")
                            MangaState.PAUSED -> append("on-hold")
                            MangaState.ABANDONED -> append("canceled")
                            else -> {}
                        }
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

            val statusText = doc.selectFirst("div.y6x11p i.fas.fa-rss + span.dt, .status span, [class*=status]")
                ?.text()?.lowercase().orEmpty()
            val state = when {
                "on-going" in statusText || "ongoing" in statusText || "đang tiến hành" in statusText -> MangaState.ONGOING
                "completed" in statusText || "hoàn thành" in statusText -> MangaState.FINISHED
                "on-hold" in statusText || "tạm dừng" in statusText -> MangaState.PAUSED
                "canceled" in statusText || "cancelled" in statusText || "đã huỷ bỏ" in statusText -> MangaState.ABANDONED
                else -> null
            }

            val tags = doc.select(".a2 div > a[rel='tag'], .genres a, [class*=genre-item] a").mapNotNullToSet { a ->
                val key = a.attr("href").trimEnd('/').substringAfterLast('/')
                    .takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
                MangaTag(title = a.text().trim(), key = key, source = customSource)
            }

            val description = doc.selectFirst("div#syn-target, .summary, .manga-desc")?.text()?.trim()

            val chapters = doc.select(".list-chapter a, ul.chapter li a, .chap-item a").mapIndexedNotNull { i, a ->
                val href = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                val rawName = a.text().trim().ifEmpty { "Chapter ${i + 1}" }
                val number = CHAPTER_RE.find(rawName)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                    ?: (i + 1).toFloat()
                MangaChapter(
                    id = href.hashCode().toLong(),
                    title = rawName,
                    number = number,
                    volume = 0,
                    url = href,
                    scanlator = null,
                    uploadDate = 0L,
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
            val images = doc.select(".chapter-content img, .reading-content img, #reader-content img")
                .ifEmpty { doc.select("img[data-src], img[src*=/uploads/]") }
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
            val doc = fetchDocument("$baseUrl/filter/1/")
            doc.select(".genre-item, input[name*=genre]").mapNotNullToSet { el ->
                val input = el.selectFirst("input") ?: el
                val key = input.attr("value").ifEmpty { input.attr("id") }.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNullToSet null
                val label = el.selectFirst("label")?.text()?.trim()
                    ?: input.attr("data-label").takeIf { it.isNotEmpty() }
                    ?: key
                MangaTag(title = label, key = key, source = customSource)
            }
        }.getOrElse { emptySet() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select("div#main div.grid > div, .manga-list .manga-card, .book-list .book-item")
            .mapNotNull { div ->
                val anchor = div.selectFirst("a[href]") ?: return@mapNotNull null
                val href = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val title = div.selectFirst(".text-center a, .manga-name, h3, h4")?.text()?.trim()
                    ?: anchor.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val img = div.selectFirst("img")
                val coverUrl = (img?.attr("src") ?: img?.attr("data-src") ?: "").fixProtocol()
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
