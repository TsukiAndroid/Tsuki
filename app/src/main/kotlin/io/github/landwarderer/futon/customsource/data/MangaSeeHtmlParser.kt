package io.github.landwarderer.futon.customsource.data

import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.concurrent.TimeUnit

/**
 * Parser for sites built on the MangaSee / MangaLife CMS.
 *
 * These are among the most trafficked English manga reading sites.
 * The CMS is distinctive because it stores all data inside JavaScript
 * variables embedded in the HTML rather than in the DOM.
 *
 * Fingerprints:
 *   - vm.Directory = [...] in the main page (full manga catalogue)
 *   - vm.CurChapter = {...} in chapter pages
 *   - vm.CurPathName (CDN hostname for image URLs)
 *   - vm.CHAPTERS = [...] (chapter list on detail page)
 *
 * URL patterns:
 *   List   : {baseUrl}/search/ (uses vm.Directory JS variable)
 *   Detail : {baseUrl}/manga/{IndexName}
 *   Chapter: {baseUrl}/read-online/{IndexName}-chapter-N-page-1.html
 *   Images : https://{vm.CurPathName}/manga/{IndexName}/{chapter}/{page}.png
 */
class MangaSeeHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        return runCatching {
            val doc = fetchDocument("$baseUrl/search/")
            val dirJson = extractJsVar(doc, "vm.Directory") ?: return emptyList()
            val array = JSONArray(dirJson)
            val results = mutableListOf<Manga>()
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val indexName = obj.optString("i").takeIf { it.isNotEmpty() } ?: continue
                val title = obj.optString("s").takeIf { it.isNotEmpty() } ?: indexName
                if (!query.isNullOrBlank() && !title.contains(query, ignoreCase = true)) continue
                val coverUrl = "https://cover.nep.li/cover/$indexName.jpg"
                val pageUrl = "$baseUrl/manga/$indexName"
                results += buildManga(title, pageUrl, coverUrl, indexName)
            }
            // Simple offset/sort
            val sorted = when (order) {
                SortOrder.NEWEST -> results.reversed()
                else -> results
            }
            sorted.drop(offset).take(PAGE_SIZE)
        }.getOrElse { emptyList() }
    }

    fun getDetails(manga: Manga): Manga {
        val pageUrl = manga.publicUrl.takeIf { it.isNotEmpty() } ?: (baseUrl + manga.url)
        return runCatching {
            val doc = fetchDocument(pageUrl)

            val title = doc.selectFirst("h1, .SeriesName")?.text()?.trim() ?: manga.title
            val coverImg = doc.selectFirst("img.img-fluid, .SeriesImage img")
            val coverUrl = (coverImg?.attr("src") ?: manga.coverUrl).fixProtocol()
            val description = doc.selectFirst(".top-5.Content, .Description")?.text()?.trim()

            val statusText = doc.select(".OfficialTL, .status, .PublishStatus")
                .firstOrNull()?.text()?.lowercase()
            val state = when {
                statusText == null -> MangaState.ONGOING
                "complet" in statusText -> MangaState.FINISHED
                "hiatus" in statusText -> MangaState.PAUSED
                "cancel" in statusText || "discontinued" in statusText -> MangaState.ABANDONED
                else -> MangaState.ONGOING
            }

            val indexName = pageUrl.trimEnd('/').substringAfterLast('/')
            val chapters = loadChapters(doc, indexName)

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
            val pathName = extractJsVar(doc, "vm.CurPathName")?.trim('"') ?: "scans-hot.lastation.us"
            val curChapterJson = extractJsVar(doc, "vm.CurChapter") ?: return emptyList()

            // Parse vm.CurChapter = {"Chapter":"100010","Type":"Chapter","Page":"X",...}
            val chObj = org.json.JSONObject(curChapterJson)
            val encodedChapter = chObj.optString("Chapter")
            val pageCount = chObj.optString("Page").toIntOrNull() ?: 0
            val indexName = chapter.url
                .substringAfterLast('/')
                .substringBefore("-chapter-")

            val chapterNum = decodeChapter(encodedChapter)
            (1..pageCount).mapIndexed { i, pageNum ->
                val paddedPage = pageNum.toString().padStart(3, '0')
                val paddedChapter = chapterNum.replace(".", "-")
                val imageUrl = "https://$pathName/manga/$indexName/$paddedChapter-$paddedPage.png"
                MangaPage(
                    id = chapter.id * 1000L + i,
                    url = imageUrl,
                    preview = null,
                    source = customSource,
                )
            }
        }.getOrElse { emptyList() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun loadChapters(doc: Document, indexName: String): List<MangaChapter> {
        val chaptersJson = extractJsVar(doc, "vm.Chapters") ?: return emptyList()
        val array = runCatching { JSONArray(chaptersJson) }.getOrNull() ?: return emptyList()
        val chapters = mutableListOf<MangaChapter>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val encoded = obj.optString("Chapter").takeIf { it.isNotEmpty() } ?: continue
            val chapterNum = decodeChapter(encoded)
            val type = obj.optString("Type", "Chapter")
            val title = "$type $chapterNum"
            val url = "$baseUrl/read-online/$indexName-chapter-${chapterNum.replace(".", "-")}-page-1.html"
            chapters += MangaChapter(
                id = url.hashCode().toLong(),
                title = title,
                number = chapterNum.toFloatOrNull() ?: (i + 1).toFloat(),
                volume = 0,
                url = url,
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = customSource,
            )
        }
        return chapters.sortedBy { it.number }
    }

    /** MangaSee encodes chapter numbers as a 4-digit base + 1-digit decimal, e.g. "100132" → "13.2" */
    private fun decodeChapter(encoded: String): String {
        if (encoded.length < 4) return encoded
        val odd = encoded[0].toString().toIntOrNull() ?: 0
        val main = encoded.substring(1, encoded.length - 1).trimStart('0').ifEmpty { "0" }
        val dec = encoded.last().toString().toIntOrNull() ?: 0
        return if (dec == 0) main else "$main.$dec"
    }

    private fun extractJsVar(doc: Document, varName: String): String? {
        val scripts = doc.select("script:not([src])").map { it.data() }
        for (script in scripts) {
            val pattern = Regex("""${Regex.escape(varName)}\s*=\s*(\[.*?]|\{.*?})""", RegexOption.DOT_MATCHES_ALL)
            val match = pattern.find(script) ?: continue
            return match.groupValues[1]
        }
        return null
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

    private fun buildManga(title: String, pageUrl: String, coverUrl: String, indexName: String): Manga {
        return Manga(
            id = indexName.hashCode().toLong(),
            title = title,
            altTitles = emptySet(),
            url = "/manga/$indexName",
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
