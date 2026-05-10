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
 * Parser for sites using the Gattsu CMS — a WordPress-derived manga platform
 * used by 5 sites. Uses /page/{n}/ URL pagination and has a distinctive
 * manga grid layout with cover images stored as CSS backgrounds.
 *
 * Key selectors:
 *   List  → .manga-item, .entry-article (WordPress-like grid)
 *   Pages → /page/{n}/ URL pattern, /manga/ as base path
 *   Chaps → .chapters-list li a, .chapter-list a
 *   Reader→ .chapter-images img, .reading-content img
 */
class GattsuHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val tag = filter?.tags?.firstOrNull()
        val page = offset / PAGE_SIZE + 1
        return runCatching {
            if (!query.isNullOrBlank()) {
                val url = "$baseUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}&page/$page/"
                return@runCatching parseMangaList(fetchDocument(url))
            }
            val url = if (tag != null) {
                "$baseUrl/genre/${tag.key}/page/$page/"
            } else {
                when (order) {
                    SortOrder.UPDATED -> "$baseUrl/manga/page/$page/"
                    SortOrder.POPULARITY -> "$baseUrl/popular/page/$page/"
                    else -> "$baseUrl/manga/page/$page/"
                }
            }
            parseMangaList(fetchDocument(url))
        }.getOrElse { emptyList() }
    }

    fun getDetails(manga: Manga): Manga {
        return runCatching {
            val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
            val doc = fetchDocument(pageUrl)

            val statusText = doc.selectFirst(".manga-status, .post-status, [class*=status]")
                ?.text()?.lowercase().orEmpty()
            val state = when {
                "ongoing" in statusText || "en cours" in statusText -> MangaState.ONGOING
                "completed" in statusText || "finished" in statusText -> MangaState.FINISHED
                "hiatus" in statusText -> MangaState.PAUSED
                else -> null
            }

            val tags = doc.select(".genres a, .manga-genres a, a[rel=tag]").mapNotNullToSet { a ->
                val key = a.attr("href").trimEnd('/').substringAfterLast('/')
                    .takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
                MangaTag(title = a.text().trim(), key = key, source = customSource)
            }

            val description = doc.selectFirst(".manga-summary p, .entry-content p, .synopsis")
                ?.text()?.trim()

            val chapters = doc.select(".chapters-list li a, .chapter-list a, ul.chapter li a")
                .mapIndexedNotNull { i, a ->
                    val href = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
                    val rawName = a.text().trim().ifEmpty { "Chapter ${i + 1}" }
                    val number = CHAPTER_RE.find(rawName)?.groupValues?.getOrNull(1)?.toFloatOrNull()
                        ?: (i + 1).toFloat()
                    val dateText = a.parent()?.selectFirst("time, .chapter-date, span.date")?.text()?.trim() ?: ""
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
            val images = doc.select(".chapter-images img, .reading-content img, .chapter-content img")
                .ifEmpty { doc.select("img[data-src], img[src*=/wp-content/uploads/]") }
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
            val doc = fetchDocument("$baseUrl/manga/")
            doc.select("a[href*=/genre/], a[rel=tag][href*=/manga/]").mapNotNullToSet { a ->
                val key = a.attr("href").trimEnd('/').substringAfterLast('/')
                    .takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
                MangaTag(title = a.text().trim(), key = key, source = customSource)
            }
        }.getOrElse { emptySet() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseMangaList(doc: Document): List<Manga> {
        return doc.select(".manga-item, .entry-article, .post-item, article.manga")
            .ifEmpty { doc.select("div.col-6, div.col-md-3, div.col-lg-2").filter { it.selectFirst("a[href]") != null } }
            .mapNotNull { div ->
                val anchor = div.selectFirst("a[href]") ?: return@mapNotNull null
                val href = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val title = div.selectFirst("h3, h4, .entry-title, .manga-title")?.text()?.trim()
                    ?: anchor.attr("title").ifEmpty { anchor.text().trim() }.takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                // Cover: try img first, then CSS background
                val img = div.selectFirst("img")
                val coverUrl = (img?.attr("data-src") ?: img?.attr("src")
                    ?: extractBgUrl(div)).fixProtocol()
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

    private fun extractBgUrl(el: org.jsoup.nodes.Element): String {
        for (selector in listOf("[style*=background-image]", "div[style]")) {
            val elem = el.selectFirst(selector) ?: continue
            val style = elem.attr("style")
            val m = BG_URL_RE.find(style) ?: continue
            return m.groupValues[1]
        }
        return ""
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
            java.text.SimpleDateFormat("MMMM d, yyyy", java.util.Locale.ENGLISH).parse(text)?.time ?: 0L
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
        private const val PAGE_SIZE = 20
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
