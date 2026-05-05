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
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.concurrent.TimeUnit

/**
 * HTML scraper for sites using the MangaDna / ReadComicOnline / LHTranslation
 * style layout — a popular PHP CMS template used by many translation groups.
 *
 * Recognisable by:
 *   - A /manga or /comic listing page with filter bar
 *   - Manga detail at /manga/{slug} or /comic/{slug}
 *   - A "Select Chapter" or numbered chapter list below the synopsis
 *   - Images in a simple .reading-detail or #view-chapter container
 *
 * This parser also covers MangaDNA, LHTranslation, Manhwa-Clan and similar clones.
 */
class LightNovelPubHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        return if (!query.isNullOrBlank()) {
            searchManga(query, offset)
        } else {
            browseList(offset, order)
        }
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        val doc = fetchDocument(pageUrl)

        val title = doc.selectFirst(
            ".manga-info h1, .series-title h1, .post-title h1, " +
            ".name h1, .title-detail h1, .story-info-right h1"
        )?.text()?.trim() ?: manga.title

        val coverImg = doc.selectFirst(
            ".manga-info-pic img, .series-image img, .info-image img, " +
            ".img-info img, .book-cover img"
        )
        val coverUrl = (coverImg?.attr("src") ?: manga.coverUrl).fixProtocol()

        val descEl = doc.selectFirst(
            ".panel-story-info-description, .story-discription, " +
            "#panel-story-info-description, .summary-content, .manga-story"
        )
        val description = descEl?.text()?.trim()
            ?.removePrefix("Description:")?.trim()

        val statusText = doc.select(
            ".manga-info-text li, .story-info-right .variations-tableInfo tr"
        ).firstOrNull { it.text().contains("status", ignoreCase = true) }
            ?.selectFirst("a, td:last-child")?.text()?.lowercase()
        val state = when {
            statusText == null -> MangaState.ONGOING
            "complet" in statusText -> MangaState.FINISHED
            "hiatus" in statusText || "on hold" in statusText -> MangaState.PAUSED
            "cancel" in statusText || "drop" in statusText -> MangaState.ABANDONED
            else -> MangaState.ONGOING
        }

        val chapters = loadChapterList(doc)

        return Manga(
            id = manga.id,
            title = title,
            altTitles = manga.altTitles,
            url = manga.url,
            publicUrl = manga.publicUrl,
            rating = manga.rating,
            contentRating = manga.contentRating,
            coverUrl = coverUrl,
            tags = manga.tags,
            state = state,
            authors = manga.authors,
            largeCoverUrl = coverUrl,
            description = description,
            chapters = chapters,
            source = customSource,
        )
    }

    fun getPages(chapter: MangaChapter): List<MangaPage> {
        val doc = fetchDocument(chapter.url)
        val images = doc.select(
            ".reading-detail img, #view-chapter img, .chapter-content img, " +
            ".reading-content img, .content-list img, img[class*=chapter-img]"
        )
        return images.mapIndexedNotNull { index, img ->
            val url = (img.attr("data-src").takeIf { it.isNotEmpty() }
                ?: img.attr("data-lazy-src").takeIf { it.isNotEmpty() }
                ?: img.attr("src")).trim().fixProtocol()
            if (url.isEmpty() || url.length < 10) null
            else MangaPage(id = chapter.id * 1000L + index, url = url, preview = null, source = customSource)
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun browseList(offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val type = when (order) {
            SortOrder.POPULARITY -> "topview"
            SortOrder.NEWEST     -> "newest"
            else                 -> "latest"
        }
        val urls = listOf(
            "$baseUrl/manga?page=$page&type=$type",
            "$baseUrl/comics?page=$page&sort=$type",
            "$baseUrl/manga-list?page=$page",
        )
        for (url in urls) {
            val result = runCatching { parseListPage(fetchDocument(url)) }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        return emptyList()
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val page = offset / PAGE_SIZE + 1
        val urls = listOf(
            "$baseUrl/search?q=$encoded&page=$page",
            "$baseUrl/?s=$encoded&paged=$page",
            "$baseUrl/search/$encoded/",
        )
        for (url in urls) {
            val result = runCatching { parseListPage(fetchDocument(url)) }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        return emptyList()
    }

    private fun parseListPage(doc: Document): List<Manga> {
        val items = doc.select(
            ".manga-item, .story-item, .list-story-item, .story_item, " +
            ".list-truyen-item-wrap, article.item, .content-genres-item"
        )
        return items.mapNotNull { parseItem(it) }
    }

    private fun parseItem(el: Element): Manga? {
        val anchor = el.selectFirst("a") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val title = el.selectFirst("h3 a, h2 a, .item-img img[alt], .manga-name")?.text()?.trim()
            ?: el.selectFirst("img")?.attr("alt")?.trim()?.takeIf { it.isNotEmpty() }
            ?: anchor.text().trim().takeIf { it.isNotEmpty() }
            ?: return null
        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("data-src") ?: coverImg?.attr("src") ?: "").fixProtocol()
        return buildManga(title, pageUrl, coverUrl)
    }

    private fun loadChapterList(doc: Document): List<MangaChapter> {
        val rows = doc.select(
            ".row-content-chapter li, .chapter-list li, ul.list-chapter li, " +
            ".chapter_list li, table.table-episodes tr:not(:first-child)"
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
