package io.github.landwarderer.futon.extensions.data.runner

import com.dokar.quickjs.QuickJs
import io.github.landwarderer.futon.extensions.domain.Extension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs JavaScript extensions via QuickJS (quickjs-kt).
 *
 * ## JS Extension Contract
 *
 * An extension must export the following functions. Each returns a **JSON string**.
 *
 * ```js
 * // Returns: '{"items":[{"url":"/manga/xyz","title":"…","cover":"…","lang":"en"}]}'
 * async function getMangaList(offset, query) { … }
 *
 * // Returns: '{"url":"…","title":"…","cover":"…","description":"…","chapters":[…]}'
 * async function getMangaDetails(url) { … }
 *
 * // Returns: '{"pages":[{"index":0,"url":"https://img.example.com/1.jpg"}]}'
 * async function getChapterPages(url) { … }
 * ```
 *
 * The runner injects a global `tsukiFetch(url, method, headers, body)` async function
 * backed by OkHttp so extensions can make HTTP requests without bundling a HTTP client.
 */
@Singleton
class JsExtensionRunner @Inject constructor(
    private val okHttpClient: OkHttpClient,
) : ExtensionRunner {

    override suspend fun getList(
        extension: Extension,
        offset: Int,
        order: SortOrder?,
        filter: MangaListFilter?,
    ): List<Manga> = withContext(Dispatchers.IO) {
        val query = filter?.query?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"
        val script = """
            ${extension.sourceCode}
            getMangaList($offset, $query);
        """.trimIndent()

        val json = evalJs(extension, script) ?: return@withContext emptyList()
        parseMangaList(json, extension.baseUrl)
    }

    override suspend fun getDetails(extension: Extension, manga: Manga): Manga = withContext(Dispatchers.IO) {
        val url = manga.url.replace("\"", "\\\"")
        val script = """
            ${extension.sourceCode}
            getMangaDetails("$url");
        """.trimIndent()

        val json = evalJs(extension, script)
        parseDetails(json, manga, extension.baseUrl)
    }

    override suspend fun getPages(extension: Extension, chapter: MangaChapter): List<MangaPage> =
        withContext(Dispatchers.IO) {
            val url = chapter.url.replace("\"", "\\\"")
            val script = """
                ${extension.sourceCode}
                getChapterPages("$url");
            """.trimIndent()

            val json = evalJs(extension, script) ?: return@withContext emptyList()
            parsePages(json)
        }

    private suspend fun evalJs(extension: Extension, script: String): String? = runCatching {
        QuickJs.create(jobDispatcher = Dispatchers.IO).use { qjs ->
            qjs.maxStackSize = 1L shl 20
            qjs.memoryLimit = 64L shl 20

            qjs.asyncFunction("tsukiFetch") { args ->
                val url = args.getOrNull(0) as? String ?: return@asyncFunction "{}"
                val method = args.getOrNull(1) as? String ?: "GET"
                val headersJson = args.getOrNull(2) as? String
                val body = args.getOrNull(3) as? String

                withContext(Dispatchers.IO) {
                    try {
                        val builder = Request.Builder().url(url)
                        if (!headersJson.isNullOrEmpty()) {
                            runCatching {
                                val headers = JSONObject(headersJson)
                                headers.keys().forEach { key ->
                                    builder.addHeader(key, headers.getString(key))
                                }
                            }
                        }
                        val requestBody = if (!body.isNullOrEmpty()) {
                            body.toRequestBody("application/json".toMediaType())
                        } else {
                            null
                        }
                        builder.method(method.uppercase(), requestBody)
                        val response = okHttpClient.newCall(builder.build()).execute()
                        val responseBody = response.use { it.body?.string() ?: "" }
                        JSONObject().apply {
                            put("status", response.code)
                            put("body", responseBody)
                        }.toString()
                    } catch (e: Exception) {
                        JSONObject().apply {
                            put("status", 0)
                            put("body", "")
                            put("error", e.message ?: "Unknown error")
                        }.toString()
                    }
                }
            }

            qjs.evaluate<Any?>(script)?.toString()
        }
    }.getOrElse { e ->
        throw IllegalStateException("JS extension '${extension.name}' failed: ${e.message}", e)
    }

    private fun parseMangaList(json: String, baseUrl: String): List<Manga> = runCatching {
        val root = JSONObject(json)
        val items = root.optJSONArray("items") ?: return emptyList()
        (0 until items.length()).mapNotNull { i ->
            runCatching { items.getJSONObject(i).toMangaStub(baseUrl) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun parseDetails(json: String?, manga: Manga, baseUrl: String): Manga {
        if (json.isNullOrEmpty()) return manga
        return runCatching {
            val obj = JSONObject(json)
            val chaptersArr = obj.optJSONArray("chapters")
            val chapters = if (chaptersArr != null) {
                (0 until chaptersArr.length()).mapNotNull { i ->
                    runCatching { chaptersArr.getJSONObject(i).toChapter(manga.id) }.getOrNull()
                }
            } else {
                emptyList()
            }

            manga.copy(
                title = obj.optString("title", manga.title),
                coverUrl = obj.optString("cover", manga.coverUrl),
                largeCoverUrl = obj.optString("largeCover", null),
                description = obj.optString("description", manga.description),
                chapters = chapters.ifEmpty { manga.chapters },
            )
        }.getOrDefault(manga)
    }

    private fun parsePages(json: String): List<MangaPage> = runCatching {
        val root = JSONObject(json)
        val pages = root.optJSONArray("pages") ?: return emptyList()
        (0 until pages.length()).mapNotNull { i ->
            runCatching {
                val obj = pages.getJSONObject(i)
                MangaPage(
                    id = obj.optInt("index", i).toLong(),
                    url = obj.getString("url"),
                    preview = obj.optString("preview", ""),
                    source = null,
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun JSONObject.toMangaStub(baseUrl: String): Manga {
        val url = optString("url", "")
        val id = url.hashCode().toLong() and 0x7FFFFFFF
        return Manga(
            id = id,
            title = optString("title", ""),
            altTitles = emptySet(),
            url = url,
            publicUrl = if (url.startsWith("http")) url else "$baseUrl$url",
            rating = RATING_UNKNOWN,
            contentRating = ContentRating.SAFE,
            coverUrl = optString("cover", ""),
            largeCoverUrl = null,
            description = optString("description", null),
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = org.koitharu.kotatsu.parsers.model.MangaSource.STUB,
            chapters = null,
            isNsfw = false,
        )
    }

    private fun JSONObject.toChapter(mangaId: Long): MangaChapter {
        val url = getString("url")
        return MangaChapter(
            id = url.hashCode().toLong() and 0x7FFFFFFF,
            title = optString("title", null),
            name = optString("name", "Chapter"),
            number = optDouble("number", 0.0).toFloat(),
            volume = optInt("volume", 0),
            url = url,
            scanlator = optString("scanlator", null),
            uploadDate = optLong("uploadDate", 0L),
            branch = null,
            source = org.koitharu.kotatsu.parsers.model.MangaSource.STUB,
        )
    }

    companion object {
        private const val RATING_UNKNOWN = -1f
    }
}
