package io.github.landwarderer.futon.customsource.data

import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import okhttp3.OkHttpClient
import okhttp3.Request
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
 * Parser for sites using the Iken CMS — a JSON API-first platform with optional
 * api.{domain} subdomain, used by Iken.io, Mangaclash, Mangagreat, and 4+ others.
 *
 * API Endpoints:
 *   List    : GET https://api.{domain}/api/query?page={n}&perPage=18&searchTerm={q}&genreIds={ids}
 *   Details : GET https://api.{domain}/api/chapters?postId={id}&skip=0&take=900&order=desc
 *   Pages   : GET /series/{slug}/{chapter-slug} (HTML chapter reader)
 *   Genres  : GET https://api.{domain}/api/genres
 */
class IkenApiParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl
    private val apiUrl get() = run {
        val host = java.net.URI(baseUrl).host ?: baseUrl.removePrefix("https://").removePrefix("http://").split("/").first()
        "https://api.$host"
    }

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val tags = filter?.tags?.joinToString(",") { it.key } ?: ""
        val page = offset / PAGE_SIZE + 1
        return runCatching {
            val url = buildString {
                append(apiUrl)
                append("/api/query?page=")
                append(page)
                append("&perPage=")
                append(PAGE_SIZE)
                append("&searchTerm=")
                if (!query.isNullOrBlank()) append(java.net.URLEncoder.encode(query, "UTF-8"))
                if (tags.isNotEmpty()) {
                    append("&genreIds=")
                    append(tags)
                }
                filter?.states?.firstOrNull()?.let { state ->
                    append("&seriesStatus=")
                    when (state) {
                        MangaState.ONGOING -> append("ONGOING")
                        MangaState.FINISHED -> append("COMPLETED")
                        MangaState.ABANDONED -> append("DROPPED")
                        else -> {}
                    }
                }
            }
            parseMangaList(getJson(url))
        }.getOrElse { emptyList() }
    }

    fun getDetails(manga: Manga): Manga {
        return runCatching {
            val url = "$apiUrl/api/chapters?postId=${manga.id}&skip=0&take=900&order=desc&userid="
            val json = getJson(url)
            val post = json.optJSONObject("post") ?: return@runCatching manga
            val slug = post.optString("slug").takeIf { it.isNotEmpty() }

            val chaptersArr = post.optJSONArray("chapters") ?: return@runCatching manga
            val chapters = mutableListOf<MangaChapter>()
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ENGLISH)
            for (i in 0 until chaptersArr.length()) {
                val ch = chaptersArr.optJSONObject(i) ?: continue
                val chSlug = ch.optString("slug").takeIf { it.isNotEmpty() } ?: continue
                val seriesSlug = slug ?: ch.optJSONObject("mangaPost")?.optString("slug") ?: continue
                val chUrl = "$baseUrl/series/$seriesSlug/$chSlug"
                val number = ch.optDouble("number", (chapters.size + 1).toDouble()).toFloat()
                val dateStr = ch.optString("createdAt").takeIf { it.isNotEmpty() } ?: ""
                chapters += MangaChapter(
                    id = ch.optLong("id", chSlug.hashCode().toLong()),
                    title = ch.optString("title").ifEmpty { "Chapter $number" },
                    number = number,
                    volume = 0,
                    url = chUrl,
                    scanlator = null,
                    uploadDate = runCatching { fmt.parse(dateStr.substringBefore('T'))?.time ?: 0L }.getOrElse { 0L },
                    branch = null,
                    source = customSource,
                )
            }

            manga.copy(
                description = post.optString("description").takeIf { it.isNotEmpty() },
                state = when (post.optString("seriesStatus", "").uppercase()) {
                    "ONGOING" -> MangaState.ONGOING
                    "COMPLETED" -> MangaState.FINISHED
                    "DROPPED", "CANCELLED" -> MangaState.ABANDONED
                    "COMING_SOON" -> MangaState.UPCOMING
                    else -> null
                },
                chapters = chapters.sortedBy { it.number },
            )
        }.getOrElse { manga }
    }

    fun getPages(chapter: MangaChapter): List<MangaPage> {
        return runCatching {
            val doc = fetchDocument(chapter.url)
            // Iken renders images in the HTML chapter reader
            val images = doc.select(".reading-content img, .chapter-images img, #chapter-reader img")
                .ifEmpty { doc.select("img[data-src], img[src*=/uploads/]") }
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
            val json = getJson("$apiUrl/api/genres")
            val arr = json.optJSONArray("data") ?: json.optJSONArray("genres") ?: return@runCatching emptySet()
            (0 until arr.length()).mapNotNullToSet { i ->
                val item = arr.optJSONObject(i) ?: return@mapNotNullToSet null
                val id = item.optLong("id", 0L).takeIf { it != 0L }?.toString()
                    ?: item.optString("slug").takeIf { it.isNotEmpty() }
                    ?: return@mapNotNullToSet null
                val name = item.optString("name").takeIf { it.isNotEmpty() } ?: return@mapNotNullToSet null
                MangaTag(title = name, key = id, source = customSource)
            }
        }.getOrElse { emptySet() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseMangaList(json: JSONObject): List<Manga> {
        val posts = json.optJSONArray("posts") ?: json.optJSONArray("data") ?: return emptyList()
        val result = mutableListOf<Manga>()
        for (i in 0 until posts.length()) {
            val item = posts.optJSONObject(i) ?: continue
            val id = item.optLong("id", 0L).takeIf { it != 0L } ?: continue
            val title = item.optString("postTitle").ifEmpty { item.optString("title") }.takeIf { it.isNotEmpty() }
                ?: continue
            val slug = item.optString("slug").takeIf { it.isNotEmpty() } ?: id.toString()
            val cover = item.optString("featuredImage").fixProtocol()
            val statusText = item.optString("seriesStatus", "").uppercase()
            val state = when (statusText) {
                "ONGOING" -> MangaState.ONGOING
                "COMPLETED" -> MangaState.FINISHED
                "DROPPED", "CANCELLED" -> MangaState.ABANDONED
                "COMING_SOON" -> MangaState.UPCOMING
                else -> null
            }
            result += Manga(
                id = id,
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
                description = item.optString("postContent").ifEmpty { item.optString("description") }
                    .takeIf { it.isNotEmpty() },
                chapters = null,
                source = customSource,
            )
        }
        return result
    }

    private fun getJson(url: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json, text/plain, */*")
            .header("Origin", baseUrl)
            .header("Referer", "$baseUrl/")
            .get().build()
        return httpClient.newCall(req).execute().use { resp ->
            JSONObject(resp.body?.string() ?: "{}")
        }
    }

    private fun fetchDocument(url: String): org.jsoup.nodes.Document {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Referer", baseUrl)
            .get().build()
        return httpClient.newCall(req).execute().use { resp ->
            Jsoup.parse(resp.body?.string() ?: "", url)
        }
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
        private const val PAGE_SIZE = 18
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
