package io.github.landwarderer.futon.customsource.data

import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
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
 * Parser for MangaPark (v3/v4) — one of the most popular manga aggregators.
 *
 * MangaPark v3+ uses a Next.js-based frontend with a JSON data island
 * embedded in a <script id="__NEXT_DATA__"> tag on every page.
 * This parser extracts data from that JSON blob, which avoids most of the
 * HTML fragility associated with scraping SPAs.
 *
 * URL patterns:
 *   List   : {baseUrl}/browse?orderby={order}&page=N
 *   Search : {baseUrl}/search?word={query}&page=N
 *   Detail : {baseUrl}/title/{id}-{slug}
 *   Chapter: {baseUrl}/title/{id}-{slug}/{chId}
 */
class MangaParkHtmlParser(
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
        return runCatching {
            val doc = fetchDocument(pageUrl)
            val nextData = extractNextData(doc) ?: return@runCatching manga

            val comicData = nextData.optJSONObject("props")
                ?.optJSONObject("pageProps")
                ?.optJSONObject("comic")
                ?: return@runCatching manga

            val title = comicData.optString("name", manga.title)
            val coverUrl = comicData.optString("cover").let { resolveImage(it) }.takeIf { it.isNotEmpty() } ?: manga.coverUrl
            val description = comicData.optString("summary").takeIf { it.isNotEmpty() }
            val statusText = comicData.optString("status", "").lowercase()
            val state = when {
                "complet" in statusText || "finished" in statusText -> MangaState.FINISHED
                "hiatus" in statusText -> MangaState.PAUSED
                "cancel" in statusText || "discontinu" in statusText -> MangaState.ABANDONED
                else -> MangaState.ONGOING
            }

            val chaptersArr = nextData.optJSONObject("props")
                ?.optJSONObject("pageProps")
                ?.optJSONArray("chapters")
            val chapters = if (chaptersArr != null) buildChapters(chaptersArr, pageUrl) else emptyList()

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
            // MangaPark v4 stores images in __NEXT_DATA__ under pageProps.imageSet or images
            val nextData = extractNextData(doc)
            if (nextData != null) {
                val images = nextData.optJSONObject("props")
                    ?.optJSONObject("pageProps")
                    ?.let { it.optJSONArray("images") ?: it.optJSONArray("imageSet") }
                if (images != null && images.length() > 0) {
                    return@runCatching (0 until images.length()).mapNotNull { i ->
                        val url = images.optString(i).let { resolveImage(it) }
                        if (url.isEmpty()) null
                        else MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
                    }
                }
            }
            // Fallback: DOM
            val images = doc.select("img[class*=manga-page], #viewer img, .reader-content img")
            images.mapIndexedNotNull { index, img ->
                val url = (img.attr("data-src").takeIf { it.isNotEmpty() } ?: img.attr("src")).trim().fixProtocol()
                if (url.isEmpty()) null
                else MangaPage(id = chapter.id * 1000L + index, url = url, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun browseList(offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val orderStr = when (order) {
            SortOrder.POPULARITY -> "views"
            SortOrder.RATING     -> "rating"
            SortOrder.NEWEST     -> "create"
            else                 -> "update"
        }
        return runCatching { parseListPage(fetchDocument("$baseUrl/browse?orderby=$orderStr&page=$page")) }
            .getOrElse { emptyList() }
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        return runCatching { parseListPage(fetchDocument("$baseUrl/search?word=$encoded&page=$page")) }
            .getOrElse { emptyList() }
    }

    private fun parseListPage(doc: Document): List<Manga> {
        // Try Next.js data first
        val nextData = extractNextData(doc)
        if (nextData != null) {
            val list = nextData.optJSONObject("props")
                ?.optJSONObject("pageProps")
                ?.optJSONArray("comics")
            if (list != null) return parseComicArray(list)
        }
        // Fallback DOM
        val items = doc.select(".item-manga, .manga-item, .book-item, article")
        return items.mapNotNull { parseItem(it) }
    }

    private fun parseComicArray(array: JSONArray): List<Manga> {
        val result = mutableListOf<Manga>()
        for (i in 0 until array.length()) {
            val comic = array.optJSONObject(i) ?: continue
            val id = comic.optString("id", "").takeIf { it.isNotEmpty() } ?: continue
            val slug = comic.optString("slug", id)
            val title = comic.optString("name").takeIf { it.isNotEmpty() } ?: continue
            val cover = resolveImage(comic.optString("cover"))
            val pageUrl = "$baseUrl/title/$id-$slug"
            result += Manga(
                id = id.hashCode().toLong(),
                title = title,
                altTitles = emptySet(),
                url = "/title/$id-$slug",
                publicUrl = pageUrl,
                rating = 0f,
                contentRating = ContentRating.SAFE,
                coverUrl = cover,
                tags = emptySet(),
                state = MangaState.ONGOING,
                authors = emptySet(),
                largeCoverUrl = cover,
                description = null,
                chapters = null,
                source = customSource,
            )
        }
        return result
    }

    private fun parseItem(el: Element): Manga? {
        val anchor = el.selectFirst("a") ?: return null
        val pageUrl = anchor.absUrl("href").takeIf { it.isNotEmpty() } ?: return null
        val title = el.selectFirst("h3, h2, .title, .name")?.text()?.trim() ?: return null
        val coverImg = el.selectFirst("img")
        val coverUrl = (coverImg?.attr("data-src") ?: coverImg?.attr("src") ?: "").fixProtocol()
        return buildManga(title, pageUrl, coverUrl)
    }

    private fun buildChapters(array: JSONArray, mangaUrl: String): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        for (i in 0 until array.length()) {
            val ch = array.optJSONObject(i) ?: continue
            val chId = ch.optString("id", "").takeIf { it.isNotEmpty() } ?: continue
            val title = ch.optString("title").ifBlank {
                "Chapter ${ch.optString("chap", (i + 1).toString())}"
            }
            val number = ch.optString("chap").toFloatOrNull() ?: (i + 1).toFloat()
            val url = mangaUrl.trimEnd('/') + "/$chId"
            chapters += MangaChapter(
                id = chId.hashCode().toLong(),
                title = title,
                number = number,
                volume = ch.optString("vol").toIntOrNull() ?: 0,
                url = url,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = customSource,
            )
        }
        return chapters.sortedBy { it.number }
    }

    private fun extractNextData(doc: Document): JSONObject? {
        val script = doc.selectFirst("script#__NEXT_DATA__")?.data() ?: return null
        return runCatching { JSONObject(script) }.getOrNull()
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

    private fun resolveImage(raw: String): String = when {
        raw.isBlank() -> ""
        raw.startsWith("http") -> raw
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "$baseUrl$raw"
        else -> raw
    }

    private fun String?.fixProtocol(): String = when {
        this == null -> ""
        startsWith("//") -> "https:$this"
        else -> this
    }

    companion object {
        private const val PAGE_SIZE = 30
        private const val USER_AGENT = "Tsuki/1.0 (Android)"
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
