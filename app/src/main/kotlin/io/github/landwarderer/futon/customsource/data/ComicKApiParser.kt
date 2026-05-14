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
 * Parser for ComicK.io and sites sharing its open REST API.
 *
 * ComicK exposes a rich public API at api.comick.io — no auth required.
 *
 * Endpoints used:
 *   Browse  : GET https://api.comick.io/v1.0/comic/?limit=N&page=N&sort={sort}&tachiyomi=true
 *   Search  : GET https://api.comick.io/v1.0/comic/?q={query}&limit=N&tachiyomi=true
 *   Detail  : GET https://api.comick.io/comic/{slug}
 *   Chapters: GET https://api.comick.io/comic/{hid}/chapters?page=N&limit=N&lang=en
 *   Pages   : GET https://api.comick.io/chapter/{hid}
 *
 * Fingerprint: "comick" in HTML, or api.comick.io responds to /v1.0/comic/
 */
class ComicKApiParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl
    private val apiBase = "https://api.comick.io"

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        return runCatching {
            if (!query.isNullOrBlank()) searchComics(query, offset) else browseComics(offset, order)
        }.getOrElse { emptyList() }
    }

    fun getDetails(manga: Manga): Manga {
        return runCatching {
            val slug = manga.url.trimStart('/').substringAfterLast('/')
            val json = getJson("$apiBase/comic/$slug")
            val comic = json.optJSONObject("comic") ?: return@runCatching manga

            val title = comic.optString("title", manga.title).takeIf { it.isNotEmpty() } ?: manga.title
            val md_covers = comic.optJSONArray("md_covers")
            val coverPath = md_covers?.optJSONObject(0)?.optString("gpurl")
                ?: md_covers?.optJSONObject(0)?.optString("b2key") ?: ""
            val coverUrl = when {
                coverPath.startsWith("http") -> coverPath
                coverPath.isNotEmpty() -> "https://meo.comick.pictures/$coverPath"
                else -> manga.coverUrl
            }
            val description = comic.optString("desc").takeIf { it.isNotEmpty() }
            val state = when (comic.optInt("status", 1)) {
                2 -> MangaState.FINISHED
                3 -> MangaState.PAUSED
                4 -> MangaState.ABANDONED
                else -> MangaState.ONGOING
            }
            val hid = comic.optString("hid")
            val chapters = if (hid.isNotEmpty()) fetchChapters(manga, hid) else emptyList()

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
            val hid = chapter.url.trimStart('/').substringAfterLast('/')
            val json = getJson("$apiBase/chapter/$hid")
            val chapterObj = json.optJSONObject("chapter") ?: return@runCatching emptyList()
            val images = chapterObj.optJSONArray("md_images") ?: return@runCatching emptyList()
            (0 until images.length()).mapNotNull { i ->
                val img = images.optJSONObject(i) ?: return@mapNotNull null
                val b2key = img.optString("b2key").takeIf { it.isNotEmpty() }
                    ?: img.optString("url").takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val url = if (b2key.startsWith("http")) b2key else "https://meo.comick.pictures/$b2key"
                MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun browseComics(offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val sort = when (order) {
            SortOrder.POPULARITY -> "follow"
            SortOrder.RATING     -> "rating"
            SortOrder.NEWEST     -> "created_at"
            else                 -> "uploaded"
        }
        // api.comick.io returns a raw JSON array for the browse endpoint — use getJsonArray.
        val arr = getJsonArray("$apiBase/v1.0/comic/?limit=$PAGE_SIZE&page=$page&sort=$sort&tachiyomi=true")
        return parseComicList(arr)
    }

    private fun searchComics(query: String, offset: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val page = offset / PAGE_SIZE + 1
        val arr = getJsonArray("$apiBase/v1.0/comic/?q=$encoded&limit=$PAGE_SIZE&page=$page&tachiyomi=true")
        return parseComicList(arr)
    }

    /**
     * Parses a JSON array of comic objects returned by the ComicK browse/search API.
     *
     * Each item has:
     *   hid   — unique identifier used for API sub-requests
     *   slug  — URL slug used in the web UI
     *   title — display title
     *   md_covers — JSONArray of cover objects with "b2key" or "gpurl" fields
     */
    private fun parseComicList(data: JSONArray): List<Manga> {
        val result = mutableListOf<Manga>()
        for (i in 0 until data.length()) {
            val comic = data.optJSONObject(i) ?: continue
            val hid  = comic.optString("hid").takeIf { it.isNotEmpty() } ?: continue
            val slug = comic.optString("slug", hid)
            val title = comic.optString("title").takeIf { it.isNotEmpty() } ?: continue
            // md_covers is a JSONArray — get the first cover's b2key or gpurl.
            // Do NOT call optString("md_covers") — that would return the array's toString.
            val mdCovers = comic.optJSONArray("md_covers")
            val coverKey = mdCovers?.optJSONObject(0)?.let { cover ->
                cover.optString("gpurl").takeIf { it.isNotEmpty() }
                    ?: cover.optString("b2key").takeIf { it.isNotEmpty() }
            } ?: comic.optString("cover_url").takeIf { it.isNotEmpty() }
            val coverUrl = when {
                coverKey == null            -> ""
                coverKey.startsWith("http") -> coverKey
                else                        -> "https://meo.comick.pictures/$coverKey"
            }
            val contentRating = when (comic.optString("content_rating")) {
                "erotica", "pornographic" -> ContentRating.ADULT
                "suggestive"              -> ContentRating.SUGGESTIVE
                else                      -> ContentRating.SAFE
            }
            result += Manga(
                id = hid.hashCode().toLong(),
                title = title,
                altTitles = emptySet(),
                url = "/comic/$slug",
                publicUrl = "https://comick.io/comic/$slug",
                rating = 0f,
                contentRating = contentRating,
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

    private fun fetchChapters(manga: Manga, hid: String): List<MangaChapter> {
        val chapters = mutableListOf<MangaChapter>()
        var page = 1
        while (true) {
            val json = runCatching {
                getJson("$apiBase/comic/$hid/chapters?page=$page&limit=200&lang=en")
            }.getOrNull() ?: break
            val data = json.optJSONArray("chapters") ?: break
            if (data.length() == 0) break
            for (i in 0 until data.length()) {
                val ch = data.optJSONObject(i) ?: continue
                val chHid = ch.optString("hid").takeIf { it.isNotEmpty() } ?: continue
                val num = ch.optString("chap").toFloatOrNull() ?: ch.optDouble("chap", (i + 1).toDouble()).toFloat()
                val vol = ch.optInt("vol", 0)
                val title = buildString {
                    if (vol > 0) append("Vol.$vol ")
                    append("Chapter $num")
                    ch.optString("title").takeIf { it.isNotEmpty() }?.let { append(": $it") }
                }
                chapters += MangaChapter(
                    id = chHid.hashCode().toLong(),
                    title = title,
                    number = num,
                    volume = vol,
                    url = "/chapter/$chHid",
                    scanlator = ch.optJSONArray("group_name")?.optString(0),
                    uploadDate = 0L,
                    branch = null,
                    source = customSource,
                )
            }
            if (data.length() < 200) break
            page++
        }
        return chapters.sortedBy { it.number }
    }

    /**
     * Fetches a URL and parses the response as a JSON array.
     *
     * The ComicK browse/search endpoints return a top-level JSON array [].
     * Some server versions may wrap it in an object; both formats are handled:
     *  - [] directly       → parsed as JSONArray
     *  - {"data":[...]}    → inner array extracted
     *  - {"result":[...]}  → inner array extracted
     */
    private fun getJsonArray(url: String): JSONArray {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Referer", "https://comick.io/")
            .get().build()
        val body = httpClient.newCall(req).execute().use { it.body?.string() ?: "[]" }
        val trimmed = body.trimStart()
        return when (trimmed.firstOrNull()) {
            '[' -> JSONArray(body)
            '{' -> {
                val obj = JSONObject(body)
                obj.optJSONArray("data")
                    ?: obj.optJSONArray("result")
                    ?: obj.optJSONArray("results")
                    ?: obj.optJSONArray("items")
                    ?: JSONArray()
            }
            else -> JSONArray()
        }
    }

    private fun getJson(url: String): JSONObject {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Referer", "https://comick.io/")
            .get().build()
        return httpClient.newCall(req).execute().use { resp ->
            JSONObject(resp.body?.string() ?: "{}")
        }
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
