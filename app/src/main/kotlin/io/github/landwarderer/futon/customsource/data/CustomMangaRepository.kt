package io.github.landwarderer.futon.customsource.data

import io.github.landwarderer.futon.core.parser.MangaRepository
import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import io.github.landwarderer.futon.customsource.domain.CustomSource
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaState
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * MangaRepository implementation backed by user-defined [CustomSource]s.
 *
 * Four modes:
 *  - [CustomSourceType.MANGADEX_COMPATIBLE]: real REST calls against the
 *    MangaDex v5 API (or any compatible host) to surface manga inside the app.
 *  - [CustomSourceType.MADARA]: full HTML scraper for WordPress Madara-based
 *    sites — shows manga list, chapters and pages exactly like a built-in source.
 *  - [CustomSourceType.GENKAN]: full HTML scraper for Genkan scanlation CMS
 *    sites — shows manga list, chapters and pages exactly like a built-in source.
 *  - [CustomSourceType.WEBVIEW]: returns no list (the source only acts as a
 *    bookmarked entry), letting the user browse it via the in-app browser.
 *
 * Designed to fail soft: any HTTP / parsing error returns an empty list so
 * the source still renders in the Sources tab without crashing the app.
 */
class CustomMangaRepository(
    private val customSource: CustomMangaSource,
) : MangaRepository {

    override val source: CustomMangaSource = customSource

    override val sortOrders: Set<SortOrder> = EnumSet.of(
        SortOrder.UPDATED,
        SortOrder.POPULARITY,
        SortOrder.NEWEST,
        SortOrder.RATING,
        SortOrder.RELEVANCE,
    )

    override var defaultSortOrder: SortOrder = SortOrder.UPDATED

    override val filterCapabilities: MangaListFilterCapabilities =
        MangaListFilterCapabilities(
            isSearchSupported = true,
            isSearchWithFiltersSupported = false,
            isMultipleTagsSupported = false,
            isTagsExclusionSupported = false,
        )

    private val madaraParser: MadaraHtmlParser by lazy { MadaraHtmlParser(customSource) }
    private val genkanParser: GenkanHtmlParser by lazy { GenkanHtmlParser(customSource) }

    override suspend fun getList(
        offset: Int,
        order: SortOrder?,
        filter: MangaListFilter?,
    ): List<Manga> {
        val cs = customSource.source
        return when (cs.type) {
            CustomSourceType.WEBVIEW -> emptyList()
            CustomSourceType.MADARA -> runCatching {
                madaraParser.getList(offset, order, filter)
            }.getOrElse { emptyList() }
            CustomSourceType.GENKAN -> runCatching {
                genkanParser.getList(offset, order, filter)
            }.getOrElse { emptyList() }
            CustomSourceType.MANGADEX_COMPATIBLE -> runCatching {
                fetchMangaDexList(cs, offset, order, filter)
            }.getOrElse { emptyList() }
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        return when (customSource.source.type) {
            CustomSourceType.MADARA -> runCatching { madaraParser.getDetails(manga) }.getOrElse { manga }
            CustomSourceType.GENKAN -> runCatching { genkanParser.getDetails(manga) }.getOrElse { manga }
            else -> manga
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        return when (customSource.source.type) {
            CustomSourceType.MADARA -> runCatching { madaraParser.getPages(chapter) }.getOrElse { emptyList() }
            CustomSourceType.GENKAN -> runCatching { genkanParser.getPages(chapter) }.getOrElse { emptyList() }
            else -> emptyList()
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String = page.url

    override suspend fun getFilterOptions(): MangaListFilterOptions =
        MangaListFilterOptions()

    override suspend fun getRelated(seed: Manga): List<Manga> = emptyList()

    private fun fetchMangaDexList(
        cs: CustomSource,
        offset: Int,
        order: SortOrder?,
        filter: MangaListFilter?,
    ): List<Manga> {
        val baseUrl = cs.cleanBaseUrl
        val limit = PAGE_SIZE
        val orderParam = when (order) {
            SortOrder.POPULARITY -> "order[followedCount]=desc"
            SortOrder.NEWEST -> "order[createdAt]=desc"
            SortOrder.RATING -> "order[rating]=desc"
            SortOrder.RELEVANCE -> "order[relevance]=desc"
            else -> "order[updatedAt]=desc"
        }
        val query = filter?.query?.takeIf { it.isNotBlank() }?.let {
            "&title=${java.net.URLEncoder.encode(it, "UTF-8")}"
        } ?: ""
        val url = "$baseUrl/manga?limit=$limit&offset=$offset&includes[]=cover_art" +
            "&contentRating[]=safe&contentRating[]=suggestive&$orderParam$query"

        val response = httpClient.newCall(
            Request.Builder().url(url).header("User-Agent", USER_AGENT).get().build()
        ).execute()

        response.use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val root = JSONObject(body)
            if (root.optString("result") != "ok") return emptyList()
            val data = root.optJSONArray("data") ?: return emptyList()
            return parseMangaList(data, baseUrl)
        }
    }

    private fun parseMangaList(data: JSONArray, baseUrl: String): List<Manga> {
        val out = ArrayList<Manga>(data.length())
        for (i in 0 until data.length()) {
            val node = data.optJSONObject(i) ?: continue
            val id = node.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val attrs = node.optJSONObject("attributes") ?: continue
            val titleObj = attrs.optJSONObject("title")
            val title = pickLocalisedString(titleObj) ?: id
            val descObj = attrs.optJSONObject("description")
            val description = pickLocalisedString(descObj)
            val statusStr = attrs.optString("status")
            val state = when (statusStr) {
                "completed" -> MangaState.FINISHED
                "hiatus" -> MangaState.PAUSED
                "cancelled" -> MangaState.ABANDONED
                else -> MangaState.ONGOING
            }
            val contentRatingStr = attrs.optString("contentRating")
            val rating = when (contentRatingStr) {
                "safe" -> ContentRating.SAFE
                "suggestive" -> ContentRating.SUGGESTIVE
                else -> ContentRating.ADULT
            }

            // Extract cover filename from relationships
            val rels = node.optJSONArray("relationships")
            var coverFile: String? = null
            if (rels != null) {
                for (j in 0 until rels.length()) {
                    val rel = rels.optJSONObject(j) ?: continue
                    if (rel.optString("type") == "cover_art") {
                        coverFile = rel.optJSONObject("attributes")?.optString("fileName")
                        if (!coverFile.isNullOrEmpty()) break
                    }
                }
            }
            val coverUrl = coverFile?.let { fn ->
                "https://uploads.mangadex.org/covers/$id/$fn.256.jpg"
            } ?: ""

            out += Manga(
                id = id.hashCode().toLong(),
                title = title,
                altTitles = emptySet(),
                url = "/manga/$id",
                publicUrl = "$baseUrl/title/$id",
                rating = 0f,
                contentRating = rating,
                coverUrl = coverUrl,
                tags = emptySet(),
                state = state,
                authors = emptySet(),
                largeCoverUrl = coverUrl,
                description = description,
                chapters = null,
                source = customSource,
            )
        }
        return out
    }

    private fun pickLocalisedString(obj: JSONObject?): String? {
        if (obj == null) return null
        val keys = obj.keys()
        // Prefer English then any
        var fallback: String? = null
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.optString(k)
            if (v.isNullOrEmpty()) continue
            if (k == "en") return v
            if (fallback == null) fallback = v
        }
        return fallback
    }

    companion object {
        private const val PAGE_SIZE = 30
        private const val USER_AGENT = "Tsuki/1.0 (Android)"

        // Lightweight client; keep separate from the app's main pool so a flaky
        // custom site can't starve real source requests.
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
