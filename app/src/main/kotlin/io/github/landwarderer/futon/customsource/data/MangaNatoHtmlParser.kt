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
 * Parser for MangaNato.com and MangaBuddy/MangaBat derivatives.
 *
 * MangaNato is the spiritual successor to Manganelo with different URL
 * structure and selectors. Also covers: MangaBat.com, MangaBuddy.com,
 * ReadManganato.com, Mnato.net.
 *
 * URL patterns:
 *   Browse : {baseUrl}/genre-all/{page}
 *   Search : {baseUrl}/search/story/{query}
 *   Detail : {baseUrl}/manga-{id} OR {baseUrl}/read-{id}
 *   Chapter: {baseUrl}/chapter/{id}/chapter-N
 *
 * Fingerprint: "manganato" OR "mangabat" OR ".panel-story-chapter-list"
 *              OR ".panel-list-story" in HTML
 */
class MangaNatoHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val tag = filter?.tags?.firstOrNull()
        return when {
            !query.isNullOrBlank() -> searchManga(query, offset)
            tag != null -> browseByGenre(tag.key, offset, order)
            else -> browseList(offset, order)
        }
    }

    fun getGenres(): Set<MangaTag> {
        val doc = runCatching { fetchDocument("$baseUrl/genre-all") }.getOrNull() ?: return emptySet()
        val tags = mutableSetOf<MangaTag>()
        doc.select(".panel-genres-list a, a[href*=genre-]").forEach { a ->
            val href = a.attr("href").trimEnd('/')
            val key = href.substringAfterLast('/').substringAfter("genre-").takeIf { it.isNotEmpty() }
                ?: return@forEach
            val title = a.text().trim().ifEmpty { return@forEach }
            tags += MangaTag(title = title, key = key, source = customSource)
        }
        return tags
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        return runCatching {
            val doc = fetchDocument(pageUrl)

            val title = doc.selectFirst(".story-info-right h1, .manga-info-text h1, .panel-story-info h1")
                ?.text()?.trim() ?: manga.title

            val coverImg = doc.selectFirst(".info-image img, .manga-info-pic img, .panel-story-info-picture img")
            val coverUrl = (coverImg?.attr("src") ?: manga.coverUrl).fixProtocol()

            val description = doc.selectFirst("#panel-story-info-description, .panel-story-info-description, .manga-info-text p")
                ?.ownText()?.trim()

            val statusText = doc.select(".variations-tableInfo tr, .manga-info-text li")
                .firstOrNull { it.text().contains("Status", ignoreCase = true) }
                ?.text()?.lowercase()
            val state = when {
                statusText == null -> MangaState.ONGOING
                "complet" in statusText || "finished" in statusText -> MangaState.FINISHED
                "hiatus" in statusText -> MangaState.PAUSED
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
                ".container-chapter-reader img, " +
                ".panel-read-story img, " +
                "img[class*=chapter-img], " +
                "div[class*=read] img"
            )
            images.mapIndexedNotNull { i, img ->
                val url = (img.attr("data-src").takeIf { it.isNotEmpty() }
                    ?: img.attr("src")).trim().fixProtocol()
                if (url.isEmpty() || url.contains("logo") || url.contains("data:image")) null
                else MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun browseList(offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val path = when (order) {
            SortOrder.NEWEST     -> "genre-all/$page?type=newest"
            SortOrder.POPULARITY -> "genre-all/$page?type=topview"
            else                 -> "genre-all/$page"
        }
        return runCatching { parseMangaListPage(fetchDocument("$baseUrl/$path")) }.getOrElse { emptyList() }
    }

    private fun browseByGenre(genreKey: String, offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val url = "$baseUrl/genre-$genreKey/$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val encoded = query.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), "")
            .trim()
            .replace(Regex("\\s+"), "_")
        val page = offset / PAGE_SIZE + 1
        val url = "$baseUrl/search/story/$encoded?page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun parseMangaListPage(doc: Document): List<Manga> {
        val items = doc.select(".content-genres-item, .story_item, .list-story-item, .panel-list-story-item")
            .ifEmpty { doc.select("div.story_item, .content-homepage-item") }
        return items.mapNotNull { parseMangaItem(it) }
    }

    private fun parseMangaItem(el: Element): Manga? {
        val anchor = el.selectFirst("a[href*=manga-], a[href*=read-], a[href*=/manga/]") ?: el.selectFirst("a") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val title = el.selectFirst("h3 a, h2 a, .story_name, .item-title")?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null
        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("src") ?: "").fixProtocol()
        return buildManga(title, pageUrl, coverUrl)
    }

    private fun loadChapterList(doc: Document, pageUrl: String): List<MangaChapter> {
        val rows = doc.select(
            ".panel-story-chapter-list li, .row.chapter-item, " +
            ".chapter-list-two-side li, ul.row-content-chapter li"
        )
        return rows.mapIndexedNotNull { i, el ->
            val a = el.selectFirst("a") ?: return@mapIndexedNotNull null
            val url = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val rawTitle = a.text().trim().ifEmpty { "Chapter ${i + 1}" }
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
        private const val USER_AGENT = "Tsuki/1.0 (Android)"
        private val CHAPTER_NUMBER_RE = Regex("""(?:[Cc]hapter|[Cc]h\.?)\s*([\d.]+)""")

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
