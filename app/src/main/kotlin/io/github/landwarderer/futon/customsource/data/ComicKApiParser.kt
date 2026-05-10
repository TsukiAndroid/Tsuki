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
        val json = getJson("$apiBase/v1.0/comic/?limit=$PAGE_SIZE&page=$page&sort=$sort&tachiyomi=true")
        return parseComicList(json)
    }

    private fun searchComics(query: String, offset: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val page = offset / PAGE_SIZE + 1
        val json = getJson("$apiBase/v1.0/comic/?q=$encoded&limit=$PAGE_SIZE&page=$page&tachiyomi=true")
        return parseComicList(json)
    }

    private fun parseComicList(json: JSONObject): List<Manga> {
        val data = when {
            json.has("results") -> json.optJSONArray("results")
            json.has("data")    -> json.optJSONArray("data")
            else                -> null
        } ?: JSONArray().also { /* root might be an array wrapper */ }

        val result = mutableListOf<Manga>()
        for (i in 0 until data.length()) {
            val comic = data.optJSONObject(i) ?: continue
            val hid  = comic.optString("hid").takeIf { it.isNotEmpty() } ?: continue
            val slug = comic.optString("slug", hid)
            val title = comic.optString("title").takeIf { it.isNotEmpty() } ?: continue
            val coverKey = comic.optString("md_covers")
                .takeIf { it.isNotEmpty() }
                ?: comic.optString("cover_url")
            val coverUrl = when {
                coverKey == null || coverKey.isEmpty() -> ""
                coverKey.startsWith("http")            -> coverKey
                else                                   -> "https://meo.comick.pictures/$coverKey"
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
