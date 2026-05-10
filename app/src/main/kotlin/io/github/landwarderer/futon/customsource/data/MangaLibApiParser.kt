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
 * Parser for MangaLib (mangalib.me / mangalib.org) and its sibling platforms
 * RanobeLib (ranobelib.me) and HentaiLib (hentailib.me).
 *
 * MangaLib is the largest Russian-language manga/manhwa/manhua platform,
 * with millions of active readers and thousands of translated titles.
 *
 * It exposes a clean, well-structured REST API at api.lib.social (new)
 * and api.mangalib.me (legacy, still active via redirect).
 *
 * Endpoints used:
 *   Browse  : GET /api/manga?page=N&fields[]=name&fields[]=cover.thumbnail&fields[]=slug_url&site_id[]=1
 *   Search  : GET /api/manga?page=N&q={query}&fields[]=name&fields[]=slug_url
 *   Detail  : GET /api/manga/{slug}?fields[]=description&fields[]=status_id&fields[]=cover
 *   Chapters: GET /api/manga/{slug}/chapters
 *   Pages   : GET /api/manga/{slug}/chapter?volume={vol}&number={num}
 *
 * Fingerprint: "mangalib" OR "lib.social" OR "ranobelib" in HTML,
 *              OR /api/manga returns { data: [ { slug_url } ] }.
 */
class MangaLibApiParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    // The API lives at api.lib.social (current) with legacy redirect from api.mangalib.me
    private val apiBase: String get() {
        val host = baseUrl.removePrefix("https://").removePrefix("http://").substringBefore("/")
        return when {
            host.contains("mangalib") -> "https://api.mangalib.me"
            host.contains("ranobelib") -> "https://api.mangalib.me"
            host.contains("lib.social") -> "https://api.lib.social"
            else -> "https://api.lib.social"
        }
    }

    // site_id: 1=MangaLib, 2=RanobeLib, 4=HentaiLib
    private val siteId: Int get() {
        val host = baseUrl.removePrefix("https://").removePrefix("http://")
        return when {
            host.contains("hentai") -> 4
            host.contains("ranobe") -> 2
            else -> 1
        }
    }

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        return runCatching {
            if (!query.isNullOrBlank()) searchManga(query, offset) else browseList(offset, order)
        }.getOrElse { emptyList() }
    }

    fun getDetails(manga: Manga): Manga {
        return runCatching {
            val slug = manga.url.trimStart('/')
            val json = getJson("$apiBase/api/manga/$slug?fields[]=description&fields[]=status_id&fields[]=cover&fields[]=authors&fields[]=genres&fields[]=type_id")
            val data = json.optJSONObject("data") ?: return@runCatching manga

            val title = data.optString("rus_name").takeIf { it.isNotEmpty() }
                ?: data.optString("name", manga.title).takeIf { it.isNotEmpty() }
                ?: manga.title

            val cover = data.optJSONObject("cover")
            val coverUrl = (cover?.optString("default")?.takeIf { it.isNotEmpty() }
                ?: cover?.optString("thumbnail")?.takeIf { it.isNotEmpty() }
                ?: manga.coverUrl).fixProtocol()

            val description = data.optString("summary").takeIf { it.isNotEmpty() }

            val state = when (data.optInt("status_id", 1)) {
                1    -> MangaState.ONGOING
                2    -> MangaState.FINISHED
                3    -> MangaState.PAUSED
                4    -> MangaState.ABANDONED
                5    -> MangaState.UPCOMING
                else -> MangaState.ONGOING
            }

            val contentRating = when (data.optInt("type_id", 1)) {
                4    -> ContentRating.ADULT      // hentai
                else -> ContentRating.SAFE
            }

            val chapters = fetchChapters(manga, slug)

            manga.copy(
                title         = title,
                coverUrl      = coverUrl,
                largeCoverUrl = coverUrl,
                description   = description,
                state         = state,
                contentRating = contentRating,
                chapters      = chapters,
            )
        }.getOrElse { manga }
    }

    fun getPages(chapter: MangaChapter): List<MangaPage> {
        return runCatching {
            // chapter.url = "{slug}?volume={vol}&number={num}"
            val parts   = chapter.url.trimStart('/')
            val slug    = parts.substringBefore("?")
            val vol     = parts.substringAfter("volume=").substringBefore("&")
            val num     = parts.substringAfter("number=").substringBefore("&")
            val json    = getJson("$apiBase/api/manga/$slug/chapter?volume=$vol&number=$num")
            val data    = json.optJSONObject("data") ?: return@runCatching emptyList()
            val pages   = data.optJSONArray("pages") ?: return@runCatching emptyList()
            val cdnHost = "https://img2.mixlib.me"  // primary CDN for MangaLib images

            (0 until pages.length()).mapNotNull { i ->
                val page = pages.optJSONObject(i) ?: return@mapNotNull null
                val rawUrl = page.optString("url").takeIf { it.isNotEmpty() }
                    ?: page.optString("slug").takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null
                val url = when {
                    rawUrl.startsWith("http") -> rawUrl
                    rawUrl.startsWith("/")    -> "$cdnHost$rawUrl"
                    else                      -> "$cdnHost/$rawUrl"
                }.fixProtocol()
                MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun browseList(offset: Int, order: SortOrder?): List<Manga> {
        val page = offset / PAGE_SIZE + 1
        val sort = when (order) {
            SortOrder.POPULARITY -> "views"
            SortOrder.RATING     -> "rating"
            SortOrder.NEWEST     -> "created_at"
            else                 -> "last_chapter_at"
        }
        val url = "$apiBase/api/manga?page=$page&site_id[]=$siteId" +
            "&fields[]=name&fields[]=rus_name&fields[]=cover.thumbnail&fields[]=slug_url&fields[]=status_id" +
            "&sort_by=$sort&sort_direction=desc"
        return parseMangaList(getJson(url))
    }

    private fun searchManga(query: String, offset: Int): List<Manga> {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        val page    = offset / PAGE_SIZE + 1
        val url     = "$apiBase/api/manga?page=$page&q=$encoded&site_id[]=$siteId" +
            "&fields[]=name&fields[]=rus_name&fields[]=cover.thumbnail&fields[]=slug_url"
        return parseMangaList(getJson(url))
    }

    private fun parseMangaList(json: JSONObject): List<Manga> {
        val data = json.optJSONArray("data") ?: return emptyList()
        val result = mutableListOf<Manga>()
        for (i in 0 until data.length()) {
            val item  = data.optJSONObject(i) ?: continue
            val slug  = item.optString("slug_url").takeIf { it.isNotEmpty() }
                ?: item.optString("slug").takeIf { it.isNotEmpty() }
                ?: continue
            val title = item.optString("rus_name").takeIf { it.isNotEmpty() }
                ?: item.optString("name").takeIf { it.isNotEmpty() }
                ?: continue
            val cover    = item.optJSONObject("cover")
            val coverRaw = cover?.optString("thumbnail")?.takeIf { it.isNotEmpty() }
                ?: cover?.optString("default") ?: ""
            val coverUrl = coverRaw.fixProtocol()
            result += Manga(
                id            = slug.hashCode().toLong(),
                title         = title,
                altTitles     = emptySet(),
                url           = "/$slug",
                publicUrl     = "$baseUrl/manga/$slug",
                rating        = 0f,
                contentRating = ContentRating.SAFE,
                coverUrl      = coverUrl,
                tags          = emptySet(),
                state         = MangaState.ONGOING,
                authors       = emptySet(),
                largeCoverUrl = coverUrl,
                description   = null,
                chapters      = null,
                source        = customSource,
            )
        }
        return result
    }

    private fun fetchChapters(manga: Manga, slug: String): List<MangaChapter> {
        val json    = runCatching { getJson("$apiBase/api/manga/$slug/chapters") }.getOrNull() ?: return emptyList()
        val data    = json.optJSONArray("data") ?: return emptyList()
        val chapters = mutableListOf<MangaChapter>()
        for (i in 0 until data.length()) {
            val ch  = data.optJSONObject(i) ?: continue
            val vol = ch.optString("volume", "1")
            val num = ch.optString("number").takeIf { it.isNotEmpty() } ?: continue
            val number = num.toFloatOrNull() ?: continue
            val name   = ch.optString("name").takeIf { it.isNotEmpty() } ?: "Chapter $num"
            chapters += MangaChapter(
                id         = "$slug-$vol-$num".hashCode().toLong(),
                title      = name,
                number     = number,
                volume     = vol.toIntOrNull() ?: 0,
                url        = "$slug?volume=$vol&number=$num",
                scanlator  = null,
                uploadDate = 0L,
                branch     = null,
                source     = customSource,
            )
        }
        return chapters.sortedBy { it.number }
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

    private fun String?.fixProtocol(): String = when {
        this == null     -> ""
        startsWith("//") -> "https:$this"
        else             -> this
    }

    companion object {
        private const val PAGE_SIZE  = 30
        private const val USER_AGENT = "Tsuki/1.0 (Android; MangaLib)"

        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
