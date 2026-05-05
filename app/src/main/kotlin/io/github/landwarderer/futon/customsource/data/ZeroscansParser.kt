package io.github.landwarderer.futon.customsource.data

import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
 * Parser for sites using the Zeroscans / ComicK-style REST + JSON API.
 *
 * These sites expose a clean JSON API (no HTML scraping needed).
 * Common endpoints:
 *   List   : GET {baseUrl}/api/chapter?limit=N&skip=N&quality=high&format=json
 *   Series : GET {baseUrl}/api/comic/{slug}
 *   Chapters: GET {baseUrl}/api/comic/{slug}/chapters?limit=N&skip=N
 *   Pages  : GET {baseUrl}/api/chapter/{id} → { chapter: { high_quality: [...] } }
 *
 * Also handles sites built on the "zeroscans" open API format which returns:
 *   { data: [ { id, name, cover, season, chapters } ] }
 */
class ZeroscansParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        return runCatching {
            if (!query.isNullOrBlank()) {
                searchComics(query)
            } else {
                fetchComicList(offset, order)
            }
        }.getOrElse { emptyList() }
    }

    fun getDetails(manga: Manga): Manga {
        return runCatching {
            val slug = manga.url.trimStart('/')
            val json = getJson("$baseUrl/api/comic/$slug")
            val comic = json.optJSONObject("comic") ?: json
            val title = comic.optString("name", manga.title).takeIf { it.isNotEmpty() } ?: manga.title
            val coverUrl = resolveImage(comic.optString("cover")).takeIf { it.isNotEmpty() } ?: manga.coverUrl
            val description = comic.optString("description").takeIf { it.isNotEmpty() }
            val statusText = comic.optString("status", "").lowercase()
            val state = when {
                "complet" in statusText || "finished" in statusText -> MangaState.FINISHED
                "hiatus" in statusText -> MangaState.PAUSED
                "cancel" in statusText || "drop" in statusText -> MangaState.ABANDONED
                else -> MangaState.ONGOING
            }
            val chapters = fetchChapters(manga, slug)
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
            val chapterId = chapter.url.trimStart('/').substringAfterLast('/')
            val json = getJson("$baseUrl/api/chapter/$chapterId")
            val chapterObj = json.optJSONObject("chapter") ?: json
            val highQuality = chapterObj.optJSONArray("high_quality")
            if (highQuality != null && highQuality.length() > 0) {
                (0 until highQuality.length()).mapNotNull { i ->
                    val url = highQuality.optString(i).takeIf { it.isNotEmpty() }
                        ?.let { resolveImage(it) } ?: return@mapNotNull null
                    MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
                }
            } else {
                // Fallback: try "images" array
                val images = chapterObj.optJSONArray("images")
                    ?: chapterObj.optJSONArray("pages")
                    ?: return@runCatching emptyList()
                (0 until images.length()).mapNotNull { i ->
                    val item = images.optJSONObject(i)
                    val url = (item?.optString("url") ?: images.optString(i)).let { resolveImage(it) }
                    if (url.isEmpty()) null
                    else MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
                }
            }
        }.getOrElse { emptyList() }
    }

    // ── Comic list ────────────────────────────────────────────────────────────

    private fun fetchComicList(offset: Int, order: SortOrder?): List<Manga> {
        val sort = when (order) {
            SortOrder.POPULARITY -> "views"
            SortOrder.RATING     -> "rating"
            SortOrder.NEWEST     -> "created_at"
            else                 -> "updated_at"
        }
        val json = getJson("$baseUrl/api/chapter?limit=$PAGE_SIZE&skip=$offset&sort=$sort&format=json")
        val data = json.optJSONArray("data") ?: json.optJSONArray("comics") ?: return emptyList()
        return parseComicArray(data)
    }

    private fun searchComics(query: String): List<Manga> {
        // Try GET search first, fall back to POST
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val getJson = runCatching {
            getJson("$baseUrl/api/comic?query=$encoded&limit=$PAGE_SIZE")
        }.getOrNull()
        val getData = getJson?.optJSONArray("data") ?: getJson?.optJSONArray("comics")
        if (getData != null && getData.length() > 0) return parseComicArray(getData)

        val bodyJson = """{"query":"$query"}"""
        val postJson = runCatching {
            postJson("$baseUrl/api/search", bodyJson)
        }.getOrNull() ?: return emptyList()
        val postData = postJson.optJSONArray("data") ?: postJson.optJSONArray("comics") ?: return emptyList()
        return parseComicArray(postData)
    }

    private fun parseComicArray(data: JSONArray): List<Manga> {
        val result = mutableListOf<Manga>()
        for (i in 0 until data.length()) {
            val comic = data.optJSONObject(i) ?: continue
            val id = comic.optInt("id", 0).takeIf { it != 0 }?.toString()
                ?: comic.optString("slug").takeIf { it.isNotEmpty() }
                ?: continue
            val title = comic.optString("name").takeIf { it.isNotEmpty() }
                ?: comic.optString("title").takeIf { it.isNotEmpty() }
                ?: continue
            val slug = comic.optString("slug", id)
            val coverRaw = comic.optString("cover")
            val coverUrl = resolveImage(coverRaw)
            result += Manga(
                id = id.hashCode().toLong(),
                title = title,
                altTitles = emptySet(),
                url = "/api/comic/$slug",
                publicUrl = "$baseUrl/comics/$slug",
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
        return result
    }

    private fun fetchChapters(manga: Manga, slug: String): List<MangaChapter> {
        val json = runCatching {
            getJson("$baseUrl/api/comic/$slug/chapters?limit=500&skip=0")
        }.getOrNull() ?: return emptyList()
        val data = json.optJSONArray("data") ?: json.optJSONArray("chapters") ?: return emptyList()
        val chapters = mutableListOf<MangaChapter>()
        for (i in 0 until data.length()) {
            val ch = data.optJSONObject(i) ?: continue
            val chId = ch.optInt("id", 0).takeIf { it != 0 } ?: continue
            val number = ch.optDouble("chapter", (i + 1).toDouble()).toFloat()
            val title = "Chapter $number"
            chapters += MangaChapter(
                id = chId.toLong(),
                title = title,
                number = number,
                volume = ch.optInt("volume", 0),
                url = "$baseUrl/api/chapter/$chId",
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = customSource,
            )
        }
        return chapters.sortedBy { it.number }
    }

    // ── Network helpers ───────────────────────────────────────────────────────

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

    private fun postJson(url: String, body: String): JSONObject {
        val reqBody = body.toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Referer", baseUrl)
            .post(reqBody).build()
        return httpClient.newCall(req).execute().use { resp ->
            JSONObject(resp.body?.string() ?: "{}")
        }
    }

    private fun resolveImage(raw: String): String {
        if (raw.isBlank()) return ""
        return when {
            raw.startsWith("http") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "$baseUrl$raw"
            else -> raw
        }
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
