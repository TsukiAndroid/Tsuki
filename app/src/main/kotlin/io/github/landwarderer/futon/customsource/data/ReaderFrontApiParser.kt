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
 * Parser for sites built on ReaderFront — an open-source scanlation group CMS.
 *
 * ReaderFront exposes a GraphQL API used by groups such as:
 * JManga (jmanga.me), ManhwaSmut, and dozens of scanlation group sites.
 *
 * GraphQL endpoint: {baseUrl}/graphql or {baseUrl}/api/graphql
 *
 * Fingerprint: "/graphql" endpoint returns {"data":{"works":...}}
 *              OR "readerfront" appears in HTML/response headers.
 */
class ReaderFrontApiParser(
    private val customSource: CustomMangaSource,
) {
    private val baseUrl get() = customSource.source.cleanBaseUrl

    private val graphqlUrl: String get() {
        return listOf("$baseUrl/graphql", "$baseUrl/api/graphql", "$baseUrl/api")
            .first { url ->
                runCatching {
                    val resp = httpClient.newCall(
                        Request.Builder().url("$url?query={works{name}}").get()
                            .header("User-Agent", USER_AGENT).build()
                    ).execute()
                    resp.isSuccessful
                }.getOrElse { false }
            }.let { it.ifEmpty { "$baseUrl/graphql" } }
    }

    fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        val query = filter?.query?.trim()
        return runCatching {
            if (!query.isNullOrBlank()) {
                fetchAllWorks().filter { it.title.contains(query, ignoreCase = true) }
            } else {
                fetchAllWorks()
            }
        }.getOrElse { emptyList() }
    }

    fun getDetails(manga: Manga): Manga {
        return runCatching {
            val stub = manga.url.trimStart('/')
            val gql = """{"query":"{ works(stub:\"$stub\") { name stub uniqid description status cover chapters { chapter subchapter stub uniqid name language } } }"}"""
            val json = postGraphQL(gql)
            val works = json.optJSONObject("data")?.optJSONArray("works") ?: return@runCatching manga
            val work = works.optJSONObject(0) ?: return@runCatching manga

            val title = work.optString("name", manga.title).takeIf { it.isNotEmpty() } ?: manga.title
            val coverPath = work.optString("cover").takeIf { it.isNotEmpty() } ?: ""
            val coverUrl = when {
                coverPath.startsWith("http") -> coverPath
                coverPath.isNotEmpty()        -> "$baseUrl/$coverPath"
                else                          -> manga.coverUrl
            }
            val description = work.optString("description").takeIf { it.isNotEmpty() }
            val state = when (work.optInt("status", 1)) {
                1 -> MangaState.ONGOING
                2 -> MangaState.FINISHED
                3 -> MangaState.PAUSED
                4 -> MangaState.ABANDONED
                else -> MangaState.ONGOING
            }

            val chapters = parseChapters(manga, work.optJSONArray("chapters"))

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
            // chapter.url = "{workStub}/chapter/{chapterStub}"
            val parts = chapter.url.trimStart('/').split('/')
            val workStub = parts.getOrNull(0) ?: return@runCatching emptyList()
            val chStub = parts.lastOrNull() ?: return@runCatching emptyList()
            val gql = """{"query":"{ chapterReadView(workStub:\"$workStub\",stub:\"$chStub\") { pages { filename } } }"}"""
            val json = postGraphQL(gql)
            val pages = json.optJSONObject("data")
                ?.optJSONObject("chapterReadView")
                ?.optJSONArray("pages")
                ?: return@runCatching emptyList()
            (0 until pages.length()).mapNotNull { i ->
                val page = pages.optJSONObject(i) ?: return@mapNotNull null
                val filename = page.optString("filename").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                val url = if (filename.startsWith("http")) filename else "$baseUrl/$filename"
                MangaPage(id = chapter.id * 1000L + i, url = url, preview = null, source = customSource)
            }
        }.getOrElse { emptyList() }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun fetchAllWorks(): List<Manga> {
        val gql = """{"query":"{ works(language:1,orderBy:\"id\",sortBy:\"DESC\",first:100) { name stub uniqid cover status } }"}"""
        val json = postGraphQL(gql)
        val works = json.optJSONObject("data")?.optJSONArray("works") ?: JSONArray()
        val result = mutableListOf<Manga>()
        for (i in 0 until works.length()) {
            val work = works.optJSONObject(i) ?: continue
            val stub = work.optString("stub").takeIf { it.isNotEmpty() } ?: continue
            val title = work.optString("name").takeIf { it.isNotEmpty() } ?: continue
            val coverPath = work.optString("cover", "")
            val coverUrl = when {
                coverPath.startsWith("http") -> coverPath
                coverPath.isNotEmpty()        -> "$baseUrl/$coverPath"
                else                          -> ""
            }
            result += Manga(
                id = stub.hashCode().toLong(),
                title = title,
                altTitles = emptySet(),
                url = "/$stub",
                publicUrl = "$baseUrl/work/$stub",
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

    private fun parseChapters(manga: Manga, chaptersArray: JSONArray?): List<MangaChapter> {
        if (chaptersArray == null) return emptyList()
        val workStub = manga.url.trimStart('/')
        val chapters = mutableListOf<MangaChapter>()
        for (i in 0 until chaptersArray.length()) {
            val ch = chaptersArray.optJSONObject(i) ?: continue
            val stub = ch.optString("stub").takeIf { it.isNotEmpty() } ?: continue
            val chNum = ch.optString("chapter").toFloatOrNull() ?: (i + 1).toFloat()
            val subNum = ch.optString("subchapter").toFloatOrNull() ?: 0f
            val number = if (subNum > 0) chNum + subNum / 10f else chNum
            val name = ch.optString("name").takeIf { it.isNotEmpty() } ?: "Chapter $chNum"
            chapters += MangaChapter(
                id = stub.hashCode().toLong(),
                title = name,
                number = number,
                volume = 0,
                url = "$workStub/chapter/$stub",
                scanlator = null,
                uploadDate = 0L,
                branch = null,
                source = customSource,
            )
        }
        return chapters.sortedBy { it.number }
    }

    private fun postGraphQL(body: String): JSONObject {
        val reqBody = body.toRequestBody("application/json".toMediaType())
        val req = Request.Builder()
            .url("$baseUrl/graphql")
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(reqBody).build()
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
