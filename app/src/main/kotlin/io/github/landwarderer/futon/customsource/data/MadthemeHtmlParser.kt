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
 * Parser for sites using the Madtheme — a modern Bootstrap-based manga theme
 * used by 12+ sites including MangaSee123, MangaKomi, Zinmanga, and others.
 *
 * Key selectors:
 *   List   → div.book-item (Bootstrap card grid)
 *   Search → GET /search/?page={n}&q={query}&sort={sort}&genre[]={genre}
 *   Chaps  → li.chapter-item a (chapter link with date span)
 *   Pages  → div.chapter-images img or .chapter-content img
 */
class MadthemeHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val page = offset / PAGE_SIZE + 1
        return runCatching {
            val url = buildString {
                append(baseUrl)
                append("/search/?page=")
                append(page)
                if (!query.isNullOrBlank()) {
                    append("&q=")
                    append(java.net.URLEncoder.encode(query, "UTF-8"))
                }
                append("&sort=")
                when (order) {
                    SortOrder.POPULARITY -> append("views")
                    SortOrder.ALPHABETICAL -> append("name")
                    SortOrder.NEWEST -> append("created_at")
                    SortOrder.RATING -> append("rating")
                    else -> append("updated_at")
                }
                filter?.tags?.forEach { tag ->
                    append("&genre[]=")
                    append(java.net.URLEncoder.encode(tag.key, "UTF-8"))
                }
                filter?.states?.firstOrNull()?.let { state ->
                    append("&status=")
                    when (state) {
                        MangaState.ONGOING -> append("ongoing")
                        MangaState.FINISHED -> append("completed")
                        else -> append("all")
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

            val statusText = doc.selectFirst(".status span, .status-label, [class*=status]")
                ?.text()?.lowercase().orEmpty()
            val state = when {
                "on going" in statusText || "ongoing" in statusText -> MangaState.ONGOING
                "completed" in statusText || "finished" in statusText -> MangaState.FINISHED
                "hiatus" in statusText -> MangaState.PAUSED
                else -> null
            }

            val tags = doc.select("div.genres span, .genres a, [class*=genre] a").mapNotNullToSet { el ->
                val key = el.attr("class").takeIf { it.isNotEmpty() && !it.contains(' ') }
                    ?: el.attr("href").trimEnd('/').substringAfterLast('/')
                    ?: return@mapNotNullToSet null
                MangaTag(title = el.text().trim(), key = key, source = customSource)
            }

            val description = doc.selectFirst(".summary-content p, .manga-summary, .description")
                ?.text()?.trim()

            val chapters = doc.select("li.chapter-item a, .chapter-list li a").mapIndexedNotNull { i, a ->
                val href = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                val rawName = a.selectFirst(".chapter-title, span:first-child")?.text()?.trim()
                    ?: a.ownText().trim().ifEmpty { "Chapter ${i + 1}" }
                val number = CHAPTER_RE.find(rawName)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                    ?: (i + 1).toFloat()
                val dateText = a.selectFirst("time, .chapter-date, span.time")?.text()?.trim() ?: ""
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

            manga.copy(state = state, tags = tags, description = description, chapters = chapters)
        }.getOrElse { manga }
    }

    fun getPages(chapter: MangaChapter): List<MangaPage> {
        return runCatching {
            val doc = fetchDocument(chapter.url)
            val images = doc.select("div.chapter-images img, .chapter-content img, .reading-content img")
                .ifEmpty { doc.select("img[data-src], img[class*=chapter]") }
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
            val doc = fetchDocument("$baseUrl/search/")
            doc.select("div.genres .checkbox, .genre-list .checkbox").mapNotNullToSet { el ->
                val input = el.selectFirst("input") ?: return@mapNotNullToSet null
                val key = input.attr("value").takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
                val title = el.selectFirst("label")?.text()?.trim()?.ifEmpty { null } ?: key
                MangaTag(title = title, key = key, source = customSource)
            }.ifEmpty {
                // Fallback: genre links
                doc.select("a[href*=/genre/], a[href*=genre[]").mapNotNullToSet { a ->
                    val key = a.attr("href").trimEnd('/').substringAfterLast('/')
                        .trimStart('?').takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
                    MangaTag(title = a.text().trim(), key = key, source = customSource)
                }
            }
        }.getOrElse { emptySet() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select("div.book-item, .manga-item").mapNotNull { div ->
            val anchor = div.selectFirst("a[href]") ?: return@mapNotNull null
            val href = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = div.selectFirst("div.title, .manga-name, h3")?.text()?.trim()
                ?: anchor.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
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

    private fun parseDate(text: String): Long {
        if (text.isBlank()) return 0L
        return runCatching {
            java.text.SimpleDateFormat("MMM dd, yyyy", java.util.Locale.ENGLISH).parse(text)?.time ?: 0L
        }.getOrElse {
            runCatching {
                java.text.SimpleDateFormat("dd/MM/yyyy", java.util.Locale.ENGLISH).parse(text)?.time ?: 0L
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
