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
 * Parser for sites built on the Keyoapp CMS — a modern, Tailwind-based scanlation
 * platform used by AsuraScans, FingerScans, Luminous Scans, and 12+ other groups.
 *
 * All series live on a single /series/ or /latest page (SinglePage paradigm).
 * Chapters are listed at #chapters > a on each series detail page.
 */
class KeyoappHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val tag = filter?.tags?.firstOrNull()
        // Keyoapp loads all series on one page — no real pagination
        if (offset > 0 && query.isNullOrBlank() && tag == null) return emptyList()
        return runCatching {
            val url = when (order) {
                SortOrder.UPDATED -> "$baseUrl/latest"
                else -> "$baseUrl/series"
            }
            val doc = fetchDocument(url)
            parseMangaList(doc, query, tag?.title)
        }.getOrElse { emptyList() }
    }

    fun getDetails(manga: Manga): Manga {
        return runCatching {
            val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
            val doc = fetchDocument(pageUrl)

            val stateText = doc.selectFirst("div[alt=Status]")?.text()?.lowercase().orEmpty()
            val state = when {
                "ongoing" in stateText -> MangaState.ONGOING
                "completed" in stateText || "finished" in stateText -> MangaState.FINISHED
                "paused" in stateText || "hiatus" in stateText -> MangaState.PAUSED
                "dropped" in stateText || "cancelled" in stateText -> MangaState.ABANDONED
                else -> null
            }

            val tags = doc.select("div.grid:has(>h1) > div > a, .genres a, .tag-item").mapNotNullToSet { a ->
                val key = a.attr("href").substringAfterLast("=").takeIf { it.isNotEmpty() }
                    ?: a.attr("href").substringAfterLast("/").takeIf { it.isNotEmpty() }
                    ?: return@mapNotNullToSet null
                val title = a.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
                MangaTag(title = title, key = key, source = customSource)
            }

            val description = doc.selectFirst("div.grid > div.overflow-hidden > p, .description p, .synopsis")
                ?.text()?.trim()

            val chapters = doc.select("#chapters > a, .chapter-list a").mapIndexedNotNull { i, a ->
                val href = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                val rawName = (a.selectFirst("span.truncate, .chapter-name") ?: a).text().trim()
                    .ifEmpty { "Chapter ${i + 1}" }
                val number = CHAPTER_RE.find(rawName)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                    ?: (i + 1).toFloat()
                val dateText = a.selectLast("div.text-xs, .chapter-date")?.text()?.trim() ?: ""
                MangaChapter(
                    id = href.hashCode().toLong(),
                    title = rawName,
                    number = number,
                    volume = 0,
                    url = href,
                    scanlator = null,
                    uploadDate = parseDate(dateText),
                    branch = null,
                    source = customSource,
                )
            }.reversed()

            manga.copy(
                state = state,
                tags = tags,
                description = description,
                chapters = chapters,
            )
        }.getOrElse { manga }
    }

    fun getPages(chapter: MangaChapter): List<MangaPage> {
        return runCatching {
            val doc = fetchDocument(chapter.url)
            val images = doc.select("img.chapter-image, .reading-content img, div#chapter-reader img")
                .ifEmpty { doc.select("img[data-src], img[src*=/uploads/], img[src*=/manga/]") }
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
            val doc = fetchDocument("$baseUrl/series")
            doc.requireElementById("series_tags_page").select("button").mapNotNullToSet { btn ->
                val key = btn.attr("tag").takeIf { it.isNotEmpty() }
                    ?: btn.text().trim().lowercase().replace(' ', '-').takeIf { it.isNotEmpty() }
                    ?: return@mapNotNullToSet null
                MangaTag(title = btn.text().trim(), key = key, source = customSource)
            }
        }.getOrElse {
            // Fallback: extract from series cards
            runCatching {
                val doc = fetchDocument("$baseUrl/series")
                doc.select("div.gap-1 a, .genre-link").mapNotNullToSet { a ->
                    val key = a.attr("href").substringAfterLast("=").takeIf { it.isNotEmpty() }
                        ?: return@mapNotNullToSet null
                    MangaTag(title = a.text().trim(), key = key, source = customSource)
                }
            }.getOrElse { emptySet() }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseMangaList(doc: Document, query: String?, tagFilter: String?): List<Manga> {
        val items = doc.select("#searched_series_page button").ifEmpty {
            doc.select("div.grid > div.group").ifEmpty {
                doc.select("div.series-card, .manga-card")
            }
        }
        return items.mapNotNull { parseMangaItem(it, query, tagFilter) }
    }

    private fun parseMangaItem(el: Element, query: String?, tagFilter: String?): Manga? {
        val anchor = el.selectFirst("a") ?: return null
        val href = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val title = (el.selectFirst("h3, h4, .series-title") ?: anchor).text().trim()
            .ifEmpty { anchor.attr("title") }.takeIf { it.isNotEmpty() } ?: return null

        // Keyword filter
        if (!query.isNullOrBlank() && !title.contains(query, ignoreCase = true)) return null

        // Tag filter
        if (!tagFilter.isNullOrBlank()) {
            val cardTags = el.attr("tags") + el.select("div.gap-1 a, .genre-tag").joinToString { it.text() }
            if (!cardTags.contains(tagFilter, ignoreCase = true)) return null
        }

        val coverUrl = extractCoverUrl(el).fixProtocol()
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
            tags = el.select("div.gap-1 a, .genre-tag").mapNotNullToSet { a ->
                val key = a.attr("href").substringAfterLast("=").takeIf { it.isNotEmpty() }
                    ?: return@mapNotNullToSet null
                MangaTag(title = a.text().trim(), key = key, source = customSource)
            },
            state = null,
            authors = emptySet(),
            largeCoverUrl = coverUrl,
            description = null,
            chapters = null,
            source = customSource,
        )
    }

    private fun extractCoverUrl(el: Element): String {
        // Keyoapp stores covers as CSS background-image in a div
        for (selector in listOf("a div.bg-cover", "div.bg-cover", "a.bg-cover", "[style*=background-image]")) {
            val elem = el.selectFirst(selector)
            if (elem != null) {
                val style = elem.attr("style")
                val match = BG_URL_RE.find(style)
                if (match != null) return match.groupValues[1]
            }
        }
        // Fallback to img
        return el.selectFirst("img")?.run {
            attr("data-src").takeIf { it.isNotEmpty() } ?: attr("src")
        }.orEmpty()
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

    private fun Document.requireElementById(id: String) = getElementById(id)
        ?: throw NoSuchElementException("Element #$id not found")

    private fun <T : Any, R : Any> Collection<T>.mapNotNullToSet(transform: (T) -> R?): Set<R> {
        val result = LinkedHashSet<R>()
        for (item in this) { transform(item)?.let { result += it } }
        return result
    }

    private fun parseDate(text: String): Long {
        if (text.isBlank()) return 0L
        return runCatching {
            val lower = text.lowercase().trim()
            val now = System.currentTimeMillis()
            when {
                "ago" in lower -> {
                    val num = Regex("(\\d+)").find(lower)?.groupValues?.getOrNull(1)?.toLong() ?: return@runCatching 0L
                    val unit = when {
                        "min" in lower -> 60_000L
                        "hour" in lower -> 3_600_000L
                        "day" in lower -> 86_400_000L
                        "week" in lower -> 604_800_000L
                        "month" in lower -> 2_592_000_000L
                        "year" in lower -> 31_536_000_000L
                        else -> 1L
                    }
                    now - num * unit
                }
                else -> java.text.SimpleDateFormat("MMM d, yyyy", java.util.Locale.ENGLISH).parse(text)?.time ?: 0L
            }
        }.getOrElse { 0L }
    }

    private fun String?.fixProtocol(): String = when {
        this == null || isEmpty() -> ""
        startsWith("//") -> "https:$this"
        else -> this
    }

    companion object {
        private const val USER_AGENT = "Tsuki/1.0 (Android)"
        private val CHAPTER_RE = Regex("""[Cc]hapter[s]?\s*([\d.]+)""")
        private val BG_URL_RE = Regex("""url\(['"]?([^'")\s]+)['"]?\)""")
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
