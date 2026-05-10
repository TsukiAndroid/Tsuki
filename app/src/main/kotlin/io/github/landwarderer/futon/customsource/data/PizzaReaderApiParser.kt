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
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.concurrent.TimeUnit

/**
 * Parser for sites using PizzaReader — an open-source Italian scanlation CMS
 * with a clean JSON REST API. Used by 9 sites including PizzaReader.it,
 * SushiScan.fr, and other European scanlation groups.
 *
 * All series are loaded at once from /api/comics (SinglePage paradigm).
 * Key endpoints:
 *   List    : GET /api/comics  → { comics: [{url, title, thumbnail, status, genres}] }
 *   Search  : GET /api/search/{query}  → { comics: [...] }
 *   Detail  : GET /api{seriesUrl}  → { comic: {chapters: [...]} }
 *   Pages   : GET /api{chapterUrl}  → { chapter: {images: [...]} }
 */
class PizzaReaderApiParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    // Cache the full comic list to enable local filtering
    private var cachedComics: List<JSONObject>? = null

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        val tags = filter?.tags ?: emptySet()
        val states = filter?.states ?: emptySet()
        // SinglePage: only load on first call
        if (offset > 0 && query.isNullOrBlank() && tags.isEmpty() && states.isEmpty()) return emptyList()
        return runCatching {
            val comics = if (!query.isNullOrBlank()) {
                val json = getJson("$baseUrl/api/search/${java.net.URLEncoder.encode(query, "UTF-8")}")
                parseComicsArray(json.optJSONArray("comics") ?: JSONArray())
            } else {
                val json = getJson("$baseUrl/api/comics")
                val all = parseComicsArray(json.optJSONArray("comics") ?: JSONArray())
                cachedComics = json.optJSONArray("comics")?.let { arr ->
                    (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                }
                all
            }
            // Apply local filters
            comics.filter { manga ->
                val raw = cachedComics?.find { it.optString("url") == manga.url }
                if (tags.isNotEmpty() && raw != null) {
                    val genresArr = raw.optJSONArray("genres")?.toString() ?: ""
                    tags.any { genresArr.contains(it.key, ignoreCase = true) }
                } else if (states.isNotEmpty() && raw != null) {
                    val status = raw.optString("status", "").lowercase()
                    states.any { state ->
                        when (state) {
                            MangaState.ONGOING -> "in corso" in status || "ongoing" in status || "en cours" in status
                            MangaState.FINISHED -> "concluso" in status || "completed" in status || "terminé" in status
                            MangaState.PAUSED -> "pausa" in status || "hiatus" in status
                            MangaState.ABANDONED -> "droppato" in status || "dropped" in status
                            else -> false
                        }
                    }
                } else true
            }
        }.getOrElse { emptyList() }
    }

    fun getDetails(manga: Manga): Manga {
        return runCatching {
            // Detail URL is /api + series URL (e.g. /api/comic/my-manga)
            val apiUrl = if (manga.url.startsWith("/api")) manga.url else "/api${manga.url}"
            val json = getJson("$baseUrl$apiUrl")
            val comic = json.optJSONObject("comic") ?: return@runCatching manga

            val statusText = comic.optString("status", "").lowercase()
            val state = when {
                "in corso" in statusText || "ongoing" in statusText -> MangaState.ONGOING
                "concluso" in statusText || "completed" in statusText || "terminé" in statusText -> MangaState.FINISHED
                "pausa" in statusText || "hiatus" in statusText -> MangaState.PAUSED
                "droppato" in statusText || "dropped" in statusText -> MangaState.ABANDONED
                else -> null
            }

            val tags = comic.optJSONArray("genres")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    val g = arr.optString(i).takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                    MangaTag(title = g, key = g.lowercase(), source = customSource)
                }.toSet()
            } ?: emptySet()

            val description = comic.optString("description").takeIf { it.isNotEmpty() }

            val chaptersArr = comic.optJSONArray("chapters") ?: JSONArray()
            val chapters = (0 until chaptersArr.length()).mapNotNull { i ->
                val ch = chaptersArr.optJSONObject(i) ?: return@mapNotNull null
                val chUrl = ch.optString("url").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val volume = ch.optInt("volume", 0)
                val number = ch.optDouble("chapter", (i + 1).toDouble()).toFloat()
                val title = "Vol.${volume} Ch.${number}"
                val dateStr = ch.optString("published_on").ifEmpty { ch.optString("created_at") }
                MangaChapter(
                    id = chUrl.hashCode().toLong(),
                    title = title,
                    number = number,
                    volume = volume,
                    url = "/api$chUrl",
                    scanlator = null,
                    uploadDate = parseIsoDate(dateStr),
                    branch = null,
                    source = customSource,
                )
            }.sortedBy { it.number }

            manga.copy(state = state, tags = tags, description = description, chapters = chapters)
        }.getOrElse { manga }
    }

    fun getPages(chapter: MangaChapter): List<MangaPage> {
        return runCatching {
            val apiUrl = if (chapter.url.startsWith("http")) chapter.url
            else "$baseUrl${chapter.url}"
            val json = getJson(apiUrl)
            val chObj = json.optJSONObject("chapter") ?: json
            val images = chObj.optJSONArray("images") ?: chObj.optJSONArray("pages") ?: return@runCatching emptyList()
            (0 until images.length()).mapNotNull { i ->
                val imgUrl = images.optString(i).takeIf { it.isNotEmpty() }?.fixProtocol()
                    ?: return@mapNotNull null
                MangaPage(id = chapter.id * 1000L + i, url = imgUrl, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    fun getGenres(): Set<MangaTag> {
        return runCatching {
            val json = getJson("$baseUrl/api/comics")
            val arr = json.optJSONArray("comics") ?: return@runCatching emptySet()
            val tags = LinkedHashSet<MangaTag>()
            for (i in 0 until arr.length()) {
                val comic = arr.optJSONObject(i) ?: continue
                val genres = comic.optJSONArray("genres") ?: continue
                for (j in 0 until genres.length()) {
                    val g = genres.optString(j).takeIf { it.isNotEmpty() } ?: continue
                    tags += MangaTag(title = g, key = g.lowercase(), source = customSource)
                }
            }
            tags
        }.getOrElse { emptySet() }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseComicsArray(arr: JSONArray): List<Manga> {
        val result = mutableListOf<Manga>()
        for (i in 0 until arr.length()) {
            val j = arr.optJSONObject(i) ?: continue
            val url = j.optString("url").takeIf { it.isNotEmpty() } ?: continue
            val title = j.optString("title").takeIf { it.isNotEmpty() } ?: continue
            val thumbnail = j.optString("thumbnail").fixProtocol()
            val statusText = j.optString("status", "").lowercase()
            val state = when {
                "in corso" in statusText || "ongoing" in statusText -> MangaState.ONGOING
                "concluso" in statusText || "completed" in statusText -> MangaState.FINISHED
                "pausa" in statusText || "hiatus" in statusText -> MangaState.PAUSED
                "droppato" in statusText || "dropped" in statusText -> MangaState.ABANDONED
                else -> null
            }
            result += Manga(
                id = url.hashCode().toLong(),
                title = title,
                altTitles = emptySet(),
                url = "/api$url",
                publicUrl = "$baseUrl$url",
                rating = 0f,
                contentRating = if (j.optBoolean("adult", false)) ContentRating.ADULT else ContentRating.SAFE,
                coverUrl = thumbnail,
                tags = emptySet(),
                state = state,
                authors = emptySet(),
                largeCoverUrl = thumbnail,
                description = null,
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
            .header("Accept", "application/json")
            .header("Referer", baseUrl)
            .get().build()
        return httpClient.newCall(req).execute().use { resp ->
            JSONObject(resp.body?.string() ?: "{}")
        }
    }

    private fun parseIsoDate(iso: String): Long {
        if (iso.isBlank()) return 0L
        return runCatching {
            java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.ENGLISH)
                .parse(iso.substringBefore('.'))?.time ?: 0L
        }.getOrElse { 0L }
    }

    private fun String?.fixProtocol(): String = when {
        this == null || isEmpty() -> ""
        startsWith("//") -> "https:$this"
        startsWith("/") -> "$baseUrl$this"
        else -> this
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
