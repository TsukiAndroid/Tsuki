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
 * HTML scraper for sites built on the Genkan scanlation CMS
 * (https://github.com/OneTwoTree/Genkan).
 *
 * Genkan is the most common self-hosted CMS used by scanlation groups
 * (Leviatan Scans, Hatigarm Scans, and many others).
 *
 * URL patterns:
 *   List    : {baseUrl}/comics?page=N
 *   Search  : {baseUrl}/comics?query={q}&page=N
 *   Detail  : {baseUrl}/comics/{slug}
 *   Chapter : {baseUrl}/comics/{slug}/{volume}/{chapter}
 */
class GenkanHtmlParser(
    private val customSource: CustomMangaSource,
) {

    private val baseUrl get() = customSource.source.cleanBaseUrl

    // ── Public API ────────────────────────────────────────────────────────────

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val page = offset / PAGE_SIZE + 1
        val url = if (!query.isNullOrBlank()) {
            "$baseUrl/comics?query=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page"
        } else {
            "$baseUrl/comics?page=$page"
        }
        return runCatching { parseMangaList(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        val doc = fetchDocument(pageUrl)

        val title = doc.selectFirst("h5.card-title, .comic-title h3, h5, h4")
            ?.text()?.trim() ?: manga.title

        val coverImg = doc.selectFirst(".media img, .fimg img, .comic-image img, .card-img-top")
        val coverUrl = (coverImg?.attr("data-src") ?: coverImg?.attr("src") ?: manga.coverUrl)
            .fixProtocol()

        val descEl = doc.selectFirst("#tab-summary, #comic-description, .novel-detail, .description, .summary")
        val description = descEl?.select("p")
            ?.joinToString("\n") { it.text() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: descEl?.text()?.trim()

        val statusText = doc.select(".novel-details dd, .status, .comic-status")
            .firstOrNull()?.text()?.lowercase()
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
        // Standard Genkan reader puts images inside #pages; fallback to common reader selectors
        val images = doc.select("#pages img, #readerArea img, .reader-image img, .reading-content img")
            .ifEmpty { doc.select("img.img-fluid").filter { it.hasAttr("data-src") || it.absUrl("src").isNotEmpty() } }
        return images.mapIndexedNotNull { index, img ->
            val url = (img.attr("data-src").takeIf { it.isNotEmpty() }
                ?: img.attr("data-lazy-src").takeIf { it.isNotEmpty() }
                ?: img.attr("src")).trim().fixProtocol()
            // Filter out placeholder/spinner images (tiny GIFs, empty URLs)
            if (url.isEmpty() || (url.endsWith(".gif") && index == 0 && images.size > 1)) null
            else MangaPage(
                id = (chapter.id * 1000L + index),
                url = url,
                preview = null,
                source = customSource,
            )
        }
    }

    // ── List parsing ──────────────────────────────────────────────────────────

    private fun parseMangaList(doc: Document): List<Manga> {
        val items = doc.select("div.col-lg-2, div.comic-item, .list-item, .media.flex-fill")
        return items.mapNotNull { parseMangaItem(it) }
    }

    private fun parseMangaItem(el: Element): Manga? {
        val anchor = el.selectFirst("h6 a, .comic-title a, h3 a, h4 a") ?: return null
        val title = anchor.text().trim().takeIf { it.isNotEmpty() } ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("data-src") ?: coverImg?.attr("src") ?: "").fixProtocol()
        return buildManga(title, pageUrl, coverUrl)
    }

    // ── Chapter parsing ───────────────────────────────────────────────────────

    private fun loadChapterList(doc: Document): List<MangaChapter> {
        val anchors = doc.select(
            "div.list-group a.list-group-item, .chapter-list a, .chapters-list a, " +
                "#chapters a, .chapter-container a"
        )
        return anchors.mapIndexedNotNull { i, el -> chapterFromElement(el, i) }.reversed()
    }

    private fun chapterFromElement(el: Element, fallbackIndex: Int): MangaChapter? {
        val url = el.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val rawText = (el.selectFirst(".chapter-title, span.left, strong, span")
            ?.text()
            ?: el.ownText()).trim()
        val title = rawText.ifEmpty { "Chapter ${fallbackIndex + 1}" }
        val number = CHAPTER_NUMBER_RE.find(title)?.groupValues?.getOrNull(1)
            ?.toFloatOrNull() ?: (fallbackIndex + 1).toFloat()
        return MangaChapter(
            id = url.hashCode().toLong(),
            title = title,
            number = number,
            volume = 0,
            url = url,
            scanlator = null,
            uploadDate = 0L,
            branch = null,
            source = customSource,
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun fetchDocument(url: String): Document {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", baseUrl)
            .get()
            .build()
        return httpClient.newCall(request).execute().use { resp ->
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
        private const val PAGE_SIZE = 20
        private const val USER_AGENT = "Tsuki/1.0 (Android)"

        /** Matches "Chapter 12", "Ch.12", "Ch 12.5", "#12" */
        private val CHAPTER_NUMBER_RE = Regex("""(?:[Cc]h(?:apter)?\.?\s*|#)([\d.]+)""")

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
