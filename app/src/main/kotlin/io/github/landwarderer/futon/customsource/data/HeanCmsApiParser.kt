package io.github.landwarderer.futon.customsource.data

import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
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
 * Parser for sites using the HeanCms platform — a modern JSON REST API CMS
 * used by ReaperScans, FlameScans, VoidScans, LuminousScans, and others.
 *
 * API host is usually at api.{domain}. Key endpoints:
 *   List    : GET https://api.{domain}/query?query_string={q}&series_type=Comic&perPage=20&page={n}&order=desc&order_by=updated_at
 *   Details : GET https://api.{domain}/series/{slug}
 *   Chapters: GET https://api.{domain}/chapter/{chapterSlug}  (returns JSON with pages)
 *   Genres  : GET https://api.{domain}/tags  (or /genres)
 */
class HeanCmsApiParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl
    // HeanCms API is at api.{domain}
    private val apiUrl get() = run {
        val host = java.net.URI(baseUrl).host ?: baseUrl.removePrefix("https://").removePrefix("http://").split("/").first()
        "https://api.$host"
    }

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val tag = filter?.tags?.firstOrNull()
        val page = offset / PAGE_SIZE + 1
        return runCatching {
            val orderParam = when (order) {
                SortOrder.ALPHABETICAL -> "name"
                SortOrder.ALPHABETICAL_DESC -> "name"
                SortOrder.NEWEST -> "created_at"
                SortOrder.POPULARITY -> "total_views"
                SortOrder.RATING -> "rating"
                else -> "updated_at"
            }
            val orderDir = when (order) {
                SortOrder.ALPHABETICAL -> "asc"
                else -> "desc"
            }
            val url = buildString {
                append(apiUrl)
                append("/query?query_string=")
                if (!query.isNullOrBlank()) append(java.net.URLEncoder.encode(query, "UTF-8"))
                append("&series_type=Comic")
                append("&perPage=$PAGE_SIZE")
                append("&page=$page")
                append("&order=$orderDir")
                append("&order_by=$orderParam")
                if (tag != null) {
                    append("&tags[]=")
                    append(java.net.URLEncoder.encode(tag.key, "UTF-8"))
                }
            }
            val json = getJson(url)
            parseMangaList(json, baseUrl)
        }.getOrElse { emptyList() }
    }

    fun getDetails(manga: Manga): Manga {
        return runCatching {
            // slug is stored in url path like /series/{slug}
            val slug = manga.url.trimStart('/').substringAfter("series/")
                .substringAfter('/').substringBefore('/')
                .ifEmpty { manga.url.trimStart('/').substringAfterLast('/') }
            val json = getJson("$apiUrl/series/$slug")
            val series = json.optJSONObject("data") ?: json

            val statusText = series.optString("status", "").lowercase()
            val state = when {
                "ongoing" in statusText || "active" in statusText -> MangaState.ONGOING
                "completed" in statusText || "finished" in statusText -> MangaState.FINISHED
                "hiatus" in statusText || "paused" in statusText -> MangaState.PAUSED
                "cancelled" in statusText || "dropped" in statusText -> MangaState.ABANDONED
                else -> null
            }

            val tags = series.optJSONArray("tags")?.let { arr ->
                (0 until arr.length()).mapNotNullToSet { i ->
                    val t = arr.optJSONObject(i) ?: return@mapNotNullToSet null
                    val name = t.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
                    MangaTag(title = name, key = name.lowercase().replace(' ', '-'), source = customSource)
                }
            } ?: emptySet()

            val description = series.optString("description").takeIf { it.isNotEmpty() }
                ?.let { Jsoup.parse(it).text() }

            val chapterList = series.optJSONArray("chapters") ?: fetchChapters(slug)
            val chapters = parseChapters(chapterList, slug)

            manga.copy(
                state = state,
                tags = tags,
                description = description,
                coverUrl = series.optString("thumbnail").takeIf { it.isNotEmpty() }
                    ?.fixProtocol() ?: manga.coverUrl,
                largeCoverUrl = series.optString("thumbnail").takeIf { it.isNotEmpty() }
                    ?.fixProtocol() ?: manga.largeCoverUrl,
                chapters = chapters,
            )
        }.getOrElse { manga }
    }

    fun getPages(chapter: MangaChapter): List<MangaPage> {
        return runCatching {
            // Chapter URL is like https://api.{domain}/chapter/{slug}
            val chapterSlug = chapter.url.trimStart('/').substringAfter("chapter/")
            val url = if (chapter.url.startsWith("http")) chapter.url
            else "$apiUrl/chapter/$chapterSlug"
            val json = getJson(url)
            val data = json.optJSONObject("data") ?: json
            val content = data.optString("chapter_data").takeIf { it.isNotEmpty() }
                ?: data.optString("content")

            // Pages can be in chapter_data (JSON string), pages array, or images array
            val pagesArr = data.optJSONArray("pages") ?: data.optJSONArray("images")
            if (pagesArr != null) {
                return@runCatching (0 until pagesArr.length()).mapIndexedNotNull { index, _ ->
                    val item = pagesArr.optJSONObject(index)
                    val imgUrl = item?.optString("url") ?: pagesArr.optString(index)
                    if (imgUrl.isNullOrEmpty()) null
                    else MangaPage(
                        id = chapter.id * 1000L + index,
                        url = imgUrl.fixProtocol(),
                        preview = null,
                        source = customSource,
                    )
                }
            }

            // Parse from chapter_data JSON string
            if (!content.isNullOrEmpty()) {
                return@runCatching runCatching {
                    val contentJson = JSONObject(content)
                    val imgs = contentJson.optJSONArray("images") ?: contentJson.optJSONArray("pages")
                    if (imgs != null) {
                        (0 until imgs.length()).mapIndexedNotNull { index, _ ->
                            val imgUrl = imgs.optString(index).takeIf { it.isNotEmpty() }
                                ?: return@mapIndexedNotNull null
                            MangaPage(
                                id = chapter.id * 1000L + index,
                                url = imgUrl.fixProtocol(),
                                preview = null,
                                source = customSource,
                            )
                        }
                    } else emptyList()
                }.getOrElse {
                    // Try parsing as HTML
                    val doc = Jsoup.parse(content)
                    doc.select("img").mapIndexedNotNull { index, img ->
                        val imgUrl = (img.attr("src").takeIf { it.isNotEmpty() }
                            ?: img.attr("data-src")).fixProtocol()
                        if (imgUrl.isEmpty()) null
                        else MangaPage(id = chapter.id * 1000L + index, url = imgUrl, preview = null, source = customSource)
                    }
                }
            }

            emptyList()
        }.getOrElse { emptyList() }
    }

    fun getGenres(): Set<MangaTag> {
        return runCatching {
            val json = runCatching { getJson("$apiUrl/tags") }
                .getOrElse { getJson("$apiUrl/genres") }
            val arr = json.optJSONArray("data") ?: json.optJSONArray("tags") ?: json.optJSONArray("genres")
                ?: return@runCatching emptySet()
            (0 until arr.length()).mapNotNullToSet { i ->
                val item = arr.optJSONObject(i) ?: return@mapNotNullToSet null
                val name = item.optString("name").takeIf { it.isNotEmpty() }
                    ?: item.optString("title").takeIf { it.isNotEmpty() }
                    ?: return@mapNotNullToSet null
                val key = item.optString("slug").ifEmpty { name.lowercase().replace(' ', '-') }
                MangaTag(title = name, key = key, source = customSource)
            }
        }.getOrElse { emptySet() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseMangaList(json: JSONObject, baseUrl: String): List<Manga> {
        val data = json.optJSONArray("data") ?: json.optJSONArray("series") ?: return emptyList()
        val result = mutableListOf<Manga>()
        for (i in 0 until data.length()) {
            val item = data.optJSONObject(i) ?: continue
            val slug = item.optString("series_slug").ifEmpty { item.optString("slug") }.takeIf { it.isNotEmpty() }
                ?: continue
            val title = item.optString("title").takeIf { it.isNotEmpty() }
                ?: item.optString("name").takeIf { it.isNotEmpty() }
                ?: continue
            val cover = (item.optString("thumbnail").ifEmpty { item.optString("cover") }).fixProtocol()
            val statusText = item.optString("status", "").lowercase()
            val state = when {
                "ongoing" in statusText -> MangaState.ONGOING
                "completed" in statusText -> MangaState.FINISHED
                "hiatus" in statusText -> MangaState.PAUSED
                "cancelled" in statusText -> MangaState.ABANDONED
                else -> null
            }
            result += Manga(
                id = slug.hashCode().toLong(),
                title = title,
                altTitles = emptySet(),
                url = "/series/$slug",
                publicUrl = "$baseUrl/series/$slug",
                rating = 0f,
                contentRating = ContentRating.SAFE,
                coverUrl = cover,
                tags = emptySet(),
                state = state,
                authors = emptySet(),
                largeCoverUrl = cover,
                description = null,
                chapters = null,
                source = customSource,
            )
        }
        return result
    }

    private fun fetchChapters(slug: String): JSONArray {
        return runCatching {
            val json = getJson("$apiUrl/series/$slug/chapters?page=1&perPage=9999")
            json.optJSONArray("data") ?: JSONArray()
        }.getOrElse { JSONArray() }
    }

    private fun parseChapters(arr: JSONArray, slug: String): List<MangaChapter> {
        val result = mutableListOf<MangaChapter>()
        for (i in 0 until arr.length()) {
            val ch = arr.optJSONObject(i) ?: continue
            val chSlug = ch.optString("chapter_slug").ifEmpty { ch.optString("slug") }.takeIf { it.isNotEmpty() }
                ?: continue
            val number = ch.optDouble("chapter_index", 0.0).takeIf { it > 0 }?.toFloat()
                ?: ch.optDouble("chapter", (result.size + 1).toDouble()).toFloat()
            val title = ch.optString("chapter_name").ifEmpty { "Chapter $number" }
            result += MangaChapter(
                id = chSlug.hashCode().toLong(),
                title = title,
                number = number,
                volume = ch.optInt("volume", 0),
                url = "$apiUrl/chapter/$chSlug",
                scanlator = null,
                uploadDate = parseIsoDate(ch.optString("created_at")),
                branch = null,
                source = customSource,
            )
        }
        return result.sortedBy { it.number }
    }

    private fun parseIsoDate(iso: String): Long {
        if (iso.isBlank()) return 0L
        return runCatching {
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ENGLISH)
            fmt.parse(iso.substringBefore('.'))?.time ?: 0L
        }.getOrElse { 0L }
    }

    private fun getJson(url: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Referer", baseUrl)
            .get().build()
        return httpClient.newCall(req).execute().use { resp ->
            JSONObject(resp.body?.string() ?: "{}")
        }
    }

    private fun <T : Any, R : Any> Iterable<T>.mapNotNullToSet(transform: (T) -> R?): Set<R> {
        val result = LinkedHashSet<R>()
        for (item in this) { transform(item)?.let { result += it } }
        return result
    }

    private fun <R : Any> IntRange.mapNotNullToSet(transform: (Int) -> R?): Set<R> {
        val result = LinkedHashSet<R>()
        for (i in this) { transform(i)?.let { result += it } }
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
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
