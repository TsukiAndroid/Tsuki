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
 * Parser for sites using the FmReader CMS — a PHP manga platform used by
 * 5 sites including FmReader.com, KissManga.in, and similar aggregators.
 *
 * Distinctive feature: /?page={n}&search={q} format and .manga-list-4-list cards.
 * Chapter reader uses .chapter-image img elements.
 */
class FmReaderHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val tag = filter?.tags?.firstOrNull()
        val page = offset / PAGE_SIZE + 1
        return runCatching {
            val url = buildString {
                append(baseUrl)
                append("/?page=")
                append(page)
                if (!query.isNullOrBlank()) {
                    append("&search=")
                    append(java.net.URLEncoder.encode(query, "UTF-8"))
                }
                if (tag != null) {
                    append("&genre=")
                    append(java.net.URLEncoder.encode(tag.key, "UTF-8"))
                }
                append("&sort=")
                when (order) {
                    SortOrder.NEWEST -> append("new")
                    SortOrder.POPULARITY -> append("views")
                    SortOrder.ALPHABETICAL -> append("alphabet")
                    SortOrder.RATING -> append("rating")
                    else -> append("latest")
                }
            }
            parseMangaList(fetchDocument(url))
        }.getOrElse { emptyList() }
    }

    fun getDetails(manga: Manga): Manga {
        return runCatching {
            val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
            val doc = fetchDocument(pageUrl)

            val statusText = doc.selectFirst(".manga-info-row:contains(Status) span, .status")
                ?.text()?.lowercase().orEmpty()
            val state = when {
                "ongoing" in statusText -> MangaState.ONGOING
                "completed" in statusText -> MangaState.FINISHED
                "hiatus" in statusText -> MangaState.PAUSED
                else -> null
            }

            val tags = doc.select("a[href*=/genre/], a[href*=/tag/]").mapNotNullToSet { a ->
                val key = a.attr("href").trimEnd('/').substringAfterLast('/')
                    .takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
                MangaTag(title = a.text().trim(), key = key, source = customSource)
            }

            val description = doc.selectFirst(".manga-summary, .detail-description, .story-summary")
                ?.text()?.trim()

            val chapters = doc.select(".chapter-list li a, .manga-chapters li a, #chapters li a")
                .mapIndexedNotNull { i, a ->
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
            val images = doc.select(".chapter-image img, .reader-image img, #chapter-images img")
                .ifEmpty { doc.select("img[data-src], img[src*=/uploads/manga/]") }
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
            val doc = fetchDocument(baseUrl)
            doc.select("a[href*=/genre/]").mapNotNullToSet { a ->
                val key = a.attr("href").trimEnd('/').substringAfterLast('/')
                    .takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
                MangaTag(title = a.text().trim(), key = key, source = customSource)
            }
        }.getOrElse { emptySet() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select(".manga-list-4-list li, .list-manga li, .manga-card").mapNotNull { li ->
            val anchor = li.selectFirst("a[href]") ?: return@mapNotNull null
            val href = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val title = li.selectFirst("h3, .manga-name, .title")?.text()?.trim()
                ?: anchor.text().trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val img = li.selectFirst("img")
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
