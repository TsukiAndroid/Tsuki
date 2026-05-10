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
 * Parser for KissManga / MangaKiss family of sites.
 *
 * The KissManga CMS is recognizable by its .listing table for chapter lists
 * and .rightBox/.barContent layout. Still widely cloned.
 *
 * Covers: kissmanga.org, readcomiconline.to, mangakakalot.tv,
 *         comickiba, kissmangaonline.com, and mirror sites.
 *
 * URL patterns:
 *   Browse : {baseUrl}/MangaList/Newest?page=N
 *   Search : {baseUrl}/Search/Manga?keyword={q}&page=N
 *   Detail : {baseUrl}/Manga/{slug}
 *   Chapter: {baseUrl}/Manga/{slug}/{chapter}
 *
 * Fingerprint: "kissmanga" OR "readcomiconline" OR ".listing" + ".barContent"
 *              OR "lstImagesUrl" in script
 */
class KissMangaHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val tag = filter?.tags?.firstOrNull()
        return when {
            !query.isNullOrBlank() -> searchManga(query, offset)
            tag != null -> browseByGenre(tag.key, offset)
            else -> browseList(offset, order)
        }
    }

    fun getGenres(): Set<MangaTag> {
        val doc = runCatching { fetchDocument("$baseUrl/MangaList/LatestUpdate") }.getOrNull()
            ?: return emptySet()
        val tags = mutableSetOf<MangaTag>()
        doc.select("select[name=genres] option, .genre-item a, a[href*=Category]").forEach { el ->
            val key = if (el.tagName() == "option") el.attr("value").takeIf { it.isNotEmpty() }
                      else el.attr("href").trimEnd('/').substringAfterLast('/')
            if (key.isNullOrEmpty() || key == "0") return@forEach
            val title = el.text().trim().ifEmpty { return@forEach }
            tags += MangaTag(title = title, key = key, source = customSource)
        }
        return tags
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        return runCatching {
            val doc = fetchDocument(pageUrl)

            val title = doc.selectFirst(".barTitle, .rightBox h1, .manga-title")
                ?.text()?.trim() ?: manga.title

            val coverImg = doc.selectFirst(".rightBox img, .cover img, img[class*=cover]")
            val coverUrl = (coverImg?.attr("src") ?: manga.coverUrl).fixProtocol()

            val description = doc.selectFirst(".barContent .full, p[class*=summary]")
                ?.text()?.trim()

            val statusText = doc.select(".barContent p")
                .firstOrNull { it.text().startsWith("Status:") }
                ?.text()?.lowercase()
                ?: doc.selectFirst(".status")?.text()?.lowercase()
            val state = when {
                statusText == null -> MangaState.ONGOING
                "complet" in statusText || "finished" in statusText -> MangaState.FINISHED
                "hiatus" in statusText || "pause" in statusText -> MangaState.PAUSED
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
            // KissManga typically puts image URLs in lstImagesUrl JS array
            val scriptImages = extractImagesFromScript(doc)
            if (scriptImages.isNotEmpty()) return@runCatching scriptImages.mapIndexed { i, url ->
                MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
            }
            // Fallback: direct img elements
            doc.select("#centerDivID img, .vungdoc img, .reading img").mapIndexedNotNull { i, img ->
                val url = (img.attr("data-original").takeIf { it.isNotEmpty() }
                    ?: img.attr("src")).trim().fixProtocol()
                if (url.isEmpty() || url.contains("data:image")) null
                else MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun browseList(offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val path = when (order) {
            SortOrder.POPULARITY -> "MangaList/MostPopular?page=$page"
            SortOrder.RATING     -> "MangaList/TopRated?page=$page"
            SortOrder.NEWEST     -> "MangaList/Newest?page=$page"
            else                 -> "MangaList/LatestUpdate?page=$page"
        }
        return runCatching { parseMangaListPage(fetchDocument("$baseUrl/$path")) }.getOrElse { emptyList() }
    }

    private fun browseByGenre(genreKey: String, offset: Int): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val url = "$baseUrl/MangaList/LatestUpdate?category=$genreKey&page=$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val page = offset / PAGE_SIZE + 1
        val urls = listOf(
            "$baseUrl/Search/Manga?keyword=$encoded&page=$page",
            "$baseUrl/search?keyword=$encoded&page=$page",
            "$baseUrl/?s=$encoded&paged=$page",
        )
        for (url in urls) {
            val result = runCatching { parseMangaListPage(fetchDocument(url)) }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        return emptyList()
    }

    private fun parseMangaListPage(doc: Document): List<Manga> {
        val items = doc.select("div.item, ul.listing + * li, .list-truyen-item-wrap, .manga-item")
            .ifEmpty { doc.select("div[class*=item]") }
        return items.mapNotNull { parseMangaItem(it) }
    }

    private fun parseMangaItem(el: Element): Manga? {
        val anchor = el.selectFirst("a[href*=/Manga/], a[href*=/manga/]") ?: el.selectFirst("a") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val title = el.selectFirst("h3, h2, .title")?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null
        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("data-original") ?: coverImg?.attr("src") ?: "").fixProtocol()
        return buildManga(title, pageUrl, coverUrl)
    }

    private fun loadChapterList(doc: Document, pageUrl: String): List<MangaChapter> {
        val rows = doc.select("table.listing tr, .chapter-list tr, ul.chapter-list li")
        return rows.mapIndexedNotNull { i, el ->
            val a = el.selectFirst("td a, li a, a") ?: return@mapIndexedNotNull null
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

    private fun extractImagesFromScript(doc: Document): List<String> {
        val scripts = doc.select("script:not([src])").map { it.data() }
        for (script in scripts) {
            val match = IMG_LIST_RE.find(script) ?: continue
            return URL_RE.findAll(match.groupValues[1])
                .map { it.groupValues[1].fixProtocol() }
                .filter { it.startsWith("http") && it.contains('.') }
                .toList()
        }
        return emptyList()
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
        private const val PAGE_SIZE = 20
        private const val USER_AGENT = "Tsuki/1.0 (Android)"
        private val CHAPTER_NUMBER_RE = Regex("""(?:[Cc]hapter|[Cc]h\.?)\s*([\d.]+)""")
        private val IMG_LIST_RE = Regex(
            """lstImagesUrl\s*=\s*(\[.*?])""",
            RegexOption.DOT_MATCHES_ALL
        )
        private val URL_RE = Regex(""""(https?://[^"]+)"""")

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
