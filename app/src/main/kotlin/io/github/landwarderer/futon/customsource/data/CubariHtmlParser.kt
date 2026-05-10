package io.github.landwarderer.futon.customsource.data

import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.concurrent.TimeUnit

/**
 * Parser for Cubari.moe — a manga proxy reader that hosts content from
 * GitHub Gists, Imgur albums, CatManga, and other backends.
 *
 * Cubari exposes a clean read-only JSON API:
 *   Series list : {baseUrl}/read/api/gist/series/  (or /imgur/, /mangadex/, etc.)
 *   Series info  : {baseUrl}/read/api/{type}/{slug}/
 *   Chapter pages: {baseUrl}/read/api/{type}/{slug}/{chapter}/{group}/
 *
 * The slug encodes the Gist ID or Imgur ID, making it suitable for fan
 * translations served from GitHub Gists (Guya.moe use-case) or private hosts.
 *
 * Fingerprint: "cubari" in HTML OR /read/api/ responds to {type}/series/
 */
class CubariHtmlParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        return runCatching {
            val all = fetchSeriesList()
            if (!query.isNullOrBlank()) all.filter { it.title.contains(query, ignoreCase = true) }
            else all
        }.getOrElse { emptyList() }
    }

    fun getDetails(manga: Manga): Manga {
        return runCatching {
            val apiPath = manga.url.trimStart('/')
            val json = getJson("$baseUrl/read/api/$apiPath/")

            val title = json.optString("title", manga.title).takeIf { it.isNotEmpty() } ?: manga.title
            val description = json.optString("description").takeIf { it.isNotEmpty() }
            val coverPath = json.optString("cover").takeIf { it.isNotEmpty() } ?: ""
            val coverUrl = if (coverPath.startsWith("http")) coverPath
                           else if (coverPath.isNotEmpty()) "$baseUrl/$coverPath"
                           else manga.coverUrl

            val chapters = loadChapters(manga, json)

            manga.copy(
                title = title,
                coverUrl = coverUrl,
                largeCoverUrl = coverUrl,
                description = description,
                chapters = chapters,
            )
        }.getOrElse { manga }
    }

    fun getPages(chapter: MangaChapter): List<MangaPage> {
        return runCatching {
            // chapter.url format: "{type}/{slug}/{chapterNum}/{group}"
            val json = getJson("$baseUrl/read/api/${chapter.url}/")
            val pages = mutableListOf<MangaPage>()
            var i = 0
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val pageUrl = json.optString(k).takeIf { it.isNotEmpty() }
                    ?: json.optJSONObject(k)?.optString("src") ?: continue
                val full = if (pageUrl.startsWith("http")) pageUrl else "$baseUrl$pageUrl"
                pages += MangaPage(id = chapter.id * 1000L + i, url = full, preview = null, source = customSource)
                i++
            }
            pages.sortedBy { it.id }
        }.getOrElse { emptyList() }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun fetchSeriesList(): List<Manga> {
        val types = listOf("gist", "imgur", "mangadex", "guya", "cubari")
        val result = mutableListOf<Manga>()
        for (type in types) {
            val json = runCatching { getJson("$baseUrl/read/api/$type/series/") }.getOrNull() ?: continue
            val keys = json.keys()
            while (keys.hasNext()) {
                val slug = keys.next()
                val series = json.optJSONObject(slug) ?: continue
                val title = series.optString("title", slug).takeIf { it.isNotEmpty() } ?: slug
                val coverPath = series.optString("cover", "")
                val coverUrl = if (coverPath.startsWith("http")) coverPath
                               else if (coverPath.isNotEmpty()) "$baseUrl/$coverPath"
                               else ""
                result += Manga(
                    id = "$type/$slug".hashCode().toLong(),
                    title = title,
                    altTitles = emptySet(),
                    url = "$type/$slug",
                    publicUrl = "$baseUrl/read/$type/$slug/",
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
            if (result.isNotEmpty()) break
        }
        return result
    }

    private fun loadChapters(manga: Manga, json: JSONObject): List<MangaChapter> {
        val chaptersObj = json.optJSONObject("chapters") ?: return emptyList()
        val apiPath = manga.url.trimStart('/')
        val chapters = mutableListOf<MangaChapter>()
        val chapterKeys = chaptersObj.keys()
        while (chapterKeys.hasNext()) {
            val chNum = chapterKeys.next()
            val chObj = chaptersObj.optJSONObject(chNum) ?: continue
            val number = chNum.toFloatOrNull() ?: continue
            // Get first available group
            val groupsObj = chObj.optJSONObject("groups") ?: continue
            val firstGroup = groupsObj.keys().asSequence().firstOrNull() ?: continue
            val title = chObj.optString("title").takeIf { it.isNotEmpty() } ?: "Chapter $chNum"
            val urlPath = "$apiPath/$chNum/$firstGroup"
            chapters += MangaChapter(
                id = urlPath.hashCode().toLong(),
                title = title,
                number = number,
                volume = chObj.optInt("volume", 0),
                url = urlPath,
                scanlator = firstGroup,
                uploadDate = 0L,
                branch = null,
                source = customSource,
            )
        }
        return chapters.sortedBy { it.number }
    }

    private fun getJson(url: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .get().build()
        return httpClient.newCall(req).execute().use { resp ->
            JSONObject(resp.body?.string() ?: "{}")
        }
    }

    companion object {
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
