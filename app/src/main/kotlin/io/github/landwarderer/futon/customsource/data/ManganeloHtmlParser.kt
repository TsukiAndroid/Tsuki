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
 * HTML scraper for sites running the MangaKakalot / Manganelo / Chapmanganelo
 * style CMS — a widely-cloned custom PHP layout that powers dozens of mirrors.
 *
 * Recognisable by:
 *   - /manga-list.html or /manga-list?type=topview as the browse page
 *   - Manga detail page at /manga/{id} or /manga-{id}
 *   - Chapter at /chapter/{id}/chapter_{n}
 *   - Images served from s1.mkklcdn.com or similar CDN subdomains
 *
 * URL patterns vary by clone, so this parser attempts several selector
 * strategies before giving up.
 */
class ManganeloHtmlParser(
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

        val title = doc.selectFirst("h1.story-info-right, .info-image + .manga-info-text h1, .manga-name")
            ?.text()?.trim() ?: manga.title

        val coverImg = doc.selectFirst(".info-image img, .manga-info-pic img, .img-loading, .manga-cover img")
        val coverUrl = (coverImg?.attr("src") ?: manga.coverUrl).fixProtocol()

        val descEl = doc.selectFirst(
            "#panel-story-info-description, .manga-info-text .panel-story-info-description, " +
            ".summary__content, #story_discription, .story-discription"
        )
        val description = descEl?.ownText()?.trim()
            ?.removePrefix("Description :")?.trim()
            ?.removePrefix("Description:")?.trim()

        val statusEl = doc.select(".variations-tableInfo tr, .manga-info-text li").firstOrNull { row ->
            row.text().contains("status", ignoreCase = true)
        }
        val statusText = statusEl?.selectFirst("td:last-child, .manga-info-text span")?.text()?.lowercase()
            ?: statusEl?.ownText()?.lowercase()
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
            ".container-chapter-reader img, " +
            ".reading-content img[src], " +
            "#vungdoc img, .vung-doc img"
        )
        return images.mapIndexedNotNull { index, img ->
            val url = (img.attr("data-src").takeIf { it.isNotEmpty() }
                ?: img.attr("src")).trim().fixProtocol()
            if (url.isEmpty() || url.contains("loading")) null
            else MangaPage(id = chapter.id * 1000L + index, url = url, preview = null, source = customSource)
        }
    }

    // ── Browsing & search ─────────────────────────────────────────────────────

    private fun browseList(offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val type = when (order) {
            SortOrder.POPULARITY -> "topview"
            SortOrder.NEWEST     -> "newest"
            else                 -> "latest"
        }
        // Try both common URL styles
        val urls = listOf(
            "$baseUrl/manga-list.html?type=$type&page=$page",
            "$baseUrl/genre-all/$page?type=$type",
            "$baseUrl/manga-list?type=$type&page=$page",
        )
        for (url in urls) {
            val result = runCatching { parseMangaListPage(fetchDocument(url)) }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        return emptyList()
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val encoded = query.replace(" ", "_")
        val urls = listOf(
            "$baseUrl/search/story/${encoded}?page=$page",
            "$baseUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page",
        )
        for (url in urls) {
            val result = runCatching { parseMangaListPage(fetchDocument(url)) }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        return emptyList()
    }

    private fun parseMangaListPage(doc: Document): List<Manga> {
        val items = doc.select(
            ".content-genres-item, .list-story-item, .list-truyen-item-wrap, " +
            ".item, .story_item"
        )
        return items.mapNotNull { parseMangaItem(it) }
    }

    private fun parseMangaItem(el: Element): Manga? {
        val anchor = el.selectFirst("a[href]:not([href=''])") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val title = el.selectFirst("h3 a, h2 a, .item-img img, h3")?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: anchor.text().trim().takeIf { it.isNotEmpty() }
            ?: return null
        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("data-src") ?: coverImg?.attr("src") ?: "").fixProtocol()
        return buildManga(title, pageUrl, coverUrl)
    }

    // ── Chapter list ──────────────────────────────────────────────────────────

    private fun loadChapterList(doc: Document): List<MangaChapter> {
        val rows = doc.select(
            ".row-content-chapter li, .chapter-list .row, ul.list-chapter li, " +
            ".chapter_list li"
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

    // ── Helpers ───────────────────────────────────────────────────────────────

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
