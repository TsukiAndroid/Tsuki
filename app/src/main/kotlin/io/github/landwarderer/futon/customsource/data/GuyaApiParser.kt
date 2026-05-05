package io.github.landwarderer.futon.customsource.data

import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import okhttp3.OkHttpClient
import okhttp3.Request
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
 * Parser for sites running the Guya reader — an open-source manga reading
 * platform used by many fan translation groups.
 *
 * Sites using Guya:
 *   - Guya.moe (Kaguya-sama official TL)
 *   - Danke fürs Lesen (Oshi no Ko, etc.)
 *   - Mahoushoujo.moe
 *   - TritiniaScans
 *   - and many more
 *
 * API is straightforward JSON:
 *   All series : GET /api/series/
 *   Series info: GET /api/series/{slug}/
 *   Chapter    : GET /api/series/{slug}/{vol}/{ch}/
 *   Images     : {baseUrl}/media/manga/{slug}/chapters/{folder}/{page}
 */
class GuyaApiParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        return runCatching {
            val json = getJson("$baseUrl/api/series/")
            val series = mutableListOf<Manga>()
            val keys = json.keys()
            while (keys.hasNext()) {
                val slug = keys.next()
                val obj = json.optJSONObject(slug) ?: continue
                val title = obj.optString("title", slug)
                if (!query.isNullOrBlank() && !title.contains(query, ignoreCase = true)) continue
                val cover = obj.optString("cover").let { resolveImage(it) }
                series += Manga(
                    id = slug.hashCode().toLong(),
                    title = title,
                    altTitles = emptySet(),
                    url = "/api/series/$slug/",
                    publicUrl = "$baseUrl/$slug/",
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
            series.drop(offset).take(PAGE_SIZE)
        }.getOrElse { emptyList() }
    }

    fun getDetails(manga: Manga): Manga {
        return runCatching {
            val slug = manga.url.trim('/').removePrefix("api/series/").trim('/')
            val json = getJson("$baseUrl/api/series/$slug/")

            val title = json.optString("title", manga.title).takeIf { it.isNotEmpty() } ?: manga.title
            val cover = json.optString("cover").let { resolveImage(it) }.takeIf { it.isNotEmpty() } ?: manga.coverUrl
            val description = json.optString("synopsis").takeIf { it.isNotEmpty() }

            val chapters = buildChapterList(json, slug)

            manga.copy(
                title = title,
                coverUrl = cover,
                largeCoverUrl = cover,
                description = description,
                chapters = chapters,
            )
        }.getOrElse { manga }
    }

    fun getPages(chapter: MangaChapter): List<MangaPage> {
        // chapter.url = "{baseUrl}/api/series/{slug}/{vol}/{ch}/"
        return runCatching {
            val json = getJson(chapter.url)
            val pagesArr = json.optJSONArray("pages") ?: return emptyList()
            val folder = json.optString("folder")
            val slug = chapter.url.trim('/').split('/').let { parts ->
                parts.getOrNull(parts.indexOf("series") + 1) ?: ""
            }
            (0 until pagesArr.length()).map { i ->
                val page = pagesArr.optString(i)
                val imageUrl = "$baseUrl/media/manga/$slug/chapters/$folder/$page"
                MangaPage(id = chapter.id * 1000L + i, url = imageUrl, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun buildChapterList(json: JSONObject, slug: String): List<MangaChapter> {
        val chaptersObj = json.optJSONObject("chapters") ?: return emptyList()
        val chapters = mutableListOf<MangaChapter>()
        val chNums = chaptersObj.keys().asSequence().toList()
        chNums.forEachIndexed { i, chNum ->
            val ch = chaptersObj.optJSONObject(chNum) ?: return@forEachIndexed
            val volsObj = ch.optJSONObject("groups") ?: ch
            val folder = volsObj.keys().asSequence()
                .firstOrNull()?.let { volsObj.optJSONObject(it) }
                ?.let { grp -> grp.optString("folder", "") } ?: ""
            // Try to get vol from the chapter data
            val vol = ch.optInt("volume", 0)
            val title = ch.optString("title", "Chapter $chNum").ifBlank { "Chapter $chNum" }
            val url = "$baseUrl/api/series/$slug/$vol/$chNum/"
            chapters += MangaChapter(
                id = url.hashCode().toLong(),
                title = title,
                number = chNum.toFloatOrNull() ?: (i + 1).toFloat(),
                volume = vol,
                url = url,
                scanlator = null,
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

    private fun resolveImage(raw: String): String = when {
        raw.isBlank() -> ""
        raw.startsWith("http") -> raw
        raw.startsWith("//") -> "https:$raw"
        raw.startsWith("/") -> "$baseUrl$raw"
        else -> "$baseUrl/$raw"
    }

    companion object {
        private const val PAGE_SIZE = 30
        private const val USER_AGENT = "Tsuki/1.0 (Android)"
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
