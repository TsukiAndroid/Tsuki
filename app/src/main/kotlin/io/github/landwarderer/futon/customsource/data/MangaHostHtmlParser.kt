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
 * Parser for MangaHost and Brazilian/Portuguese manga aggregator sites.
 *
 * Covers: mangahost4.com, mangahost.app, leitor.net, mangás.net,
 * readmanga.app, mangaonline.biz, and other Portuguese-language clones.
 *
 * URL patterns:
 *   Browse : {baseUrl}/mangas/{sort}/page/{N}
 *   Search : {baseUrl}/find/{query}/page/{N}
 *   Detail : {baseUrl}/manga/{slug}
 *   Chapter: {baseUrl}/manga/{slug}/{chapter}
 *
 * Fingerprint: "mangahost" OR "leitor.net" OR ".manga-card" + ".slider-right-content"
 */
class MangaHostHtmlParser(
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
        val doc = runCatching { fetchDocument("$baseUrl/mangas") }.getOrNull() ?: return emptySet()
        val tags = mutableSetOf<MangaTag>()
        doc.select("ul.genres-list li a, a[href*=/genres/], a[href*=/genre/], .genres-items a").forEach { a ->
            val href = a.attr("href").trimEnd('/')
            val key = href.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return@forEach
            val title = a.text().trim().ifEmpty { return@forEach }
            tags += MangaTag(title = title, key = key, source = customSource)
        }
        return tags
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        return runCatching {
            val doc = fetchDocument(pageUrl)

            val title = doc.selectFirst("h1.kw-title, .manga-info h1, h1[itemprop=name], .title-manga")
                ?.text()?.trim() ?: manga.title

            val coverImg = doc.selectFirst(".cover img, .manga-cover img, img[class*=cover]")
            val coverUrl = (coverImg?.attr("data-src") ?: coverImg?.attr("src") ?: manga.coverUrl).fixProtocol()

            val description = doc.selectFirst("div.manga-info-text p, .sinopse, .description-manga")
                ?.text()?.trim()

            val statusText = doc.select(".manga-info-text li, .manga-info li")
                .firstOrNull { it.text().contains("Status", ignoreCase = true) }
                ?.text()?.lowercase()
                ?: doc.selectFirst(".status")?.text()?.lowercase()
            val state = when {
                statusText == null -> MangaState.ONGOING
                "complet" in statusText || "finaliz" in statusText -> MangaState.FINISHED
                "hiatus" in statusText || "pausad" in statusText -> MangaState.PAUSED
                "cancel" in statusText || "abandon" in statusText -> MangaState.ABANDONED
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
            // MangaHost stores images as JS: var pages = [...]
            val scriptImages = extractPagesFromScript(doc)
            if (scriptImages.isNotEmpty()) return@runCatching scriptImages.mapIndexed { i, url ->
                MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
            }
            doc.select("#divImagens img, .reader-content img, img[class*=page]").mapIndexedNotNull { i, img ->
                val url = (img.attr("data-src").takeIf { it.isNotEmpty() }
                    ?: img.attr("src")).trim().fixProtocol()
                if (url.isEmpty()) null
                else MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun browseList(offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val sort = when (order) {
            SortOrder.POPULARITY -> "populares"
            SortOrder.NEWEST     -> "novos"
            SortOrder.RATING     -> "populares"
            else                 -> "atualizados"
        }
        val urls = listOf(
            "$baseUrl/mangas/$sort/page/$page",
            "$baseUrl/manga-list?sort=$sort&page=$page",
        )
        for (url in urls) {
            val result = runCatching { parseMangaListPage(fetchDocument(url)) }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        return emptyList()
    }

    private fun browseByGenre(genreKey: String, offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val url = "$baseUrl/genres/$genreKey/page/$page"
        return runCatching { parseMangaListPage(fetchDocument(url)) }.getOrElse { emptyList() }
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val page = offset / PAGE_SIZE + 1
        val urls = listOf(
            "$baseUrl/find/$encoded/page/$page",
            "$baseUrl/?s=$encoded&paged=$page",
        )
        for (url in urls) {
            val result = runCatching { parseMangaListPage(fetchDocument(url)) }.getOrNull()
            if (!result.isNullOrEmpty()) return result
        }
        return emptyList()
    }

    private fun parseMangaListPage(doc: Document): List<Manga> {
        val items = doc.select(".manga-card, .card-manga, .manga-block, li.item-manga")
            .ifEmpty { doc.select("article.manga, .manga-item") }
        return items.mapNotNull { parseMangaItem(it) }
    }

    private fun parseMangaItem(el: Element): Manga? {
        val anchor = el.selectFirst("a[href*=/manga/]") ?: el.selectFirst("a") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val title = el.selectFirst("h2, h3, .title, .manga-title")?.text()?.trim()
            ?: anchor.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null
        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("data-src") ?: coverImg?.attr("src") ?: "").fixProtocol()
        return buildManga(title, pageUrl, coverUrl)
    }

    private fun loadChapterList(doc: Document, pageUrl: String): List<MangaChapter> {
        val rows = doc.select(".chapter-list li, ul.chapters li, .chapter-item, div[class*=chapter] a")
        return rows.mapIndexedNotNull { i, el ->
            val a = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@mapIndexedNotNull null
            val url = a.absUrl("href").takeIf { it.isNotEmpty() } ?: return@mapIndexedNotNull null
            val rawTitle = el.selectFirst(".chapter-name, span")?.text()?.trim()
                ?: a.text().trim()
                    .ifEmpty { "Capítulo ${i + 1}" }
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

    private fun extractPagesFromScript(doc: Document): List<String> {
        val scripts = doc.select("script:not([src])").map { it.data() }
        for (script in scripts) {
            val match = PAGES_RE.find(script) ?: continue
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
        private const val PAGE_SIZE = 24
        private const val USER_AGENT = "Tsuki/1.0 (Android)"
        private val CHAPTER_NUMBER_RE = Regex("""(?:[Cc]ap[íi]tulo|[Cc]hapter|[Cc]h\.?)\s*([\d.]+)""")
        private val PAGES_RE = Regex("""var\s+pages\s*=\s*(\[.*?])""", RegexOption.DOT_MATCHES_ALL)
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
