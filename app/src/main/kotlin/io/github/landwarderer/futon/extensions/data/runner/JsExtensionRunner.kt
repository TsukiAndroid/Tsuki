package io.github.landwarderer.futon.extensions.data.runner

import com.dokar.quickjs.QuickJs
import io.github.landwarderer.futon.core.network.MangaHttpClient
import io.github.landwarderer.futon.extensions.data.ExtensionMangaSource
import io.github.landwarderer.futon.extensions.domain.Extension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.SortOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs JavaScript extensions via QuickJS (quickjs-kt).
 *
 * ## Architecture
 *
 * Network I/O is handled entirely in Kotlin (OkHttp). The QuickJS sandbox only
 * does HTML parsing. Each extension exports these pure, synchronous JS functions:
 *
 * ```js
 * // (Optional) Returns the URL to fetch for the browse/search page.
 * // If absent, extension.baseUrl is used as the fallback.
 * function getMangaListUrl(offset, query) { return "https://…"; }
 *
 * // Receives pre-fetched HTML; returns parsed list as a JSON string.
 * // Result: '{"items":[{"url":"…","title":"…","cover":"…"}]}'
 * function getMangaList(html, offset, query) { … }
 *
 * // Receives pre-fetched detail-page HTML; returns detail JSON string.
 * // Result: '{"title":"…","cover":"…","description":"…","chapters":[…]}'
 * function getMangaDetails(html, url) { … }
 *
 * // Receives pre-fetched chapter-page HTML; returns pages JSON string.
 * // Result: '{"pages":[{"index":0,"url":"https://img.example.com/1.jpg"}]}'
 * function getChapterPages(html, url) { … }
 * ```
 */
@Singleton
class JsExtensionRunner @Inject constructor(
    @MangaHttpClient private val okHttpClient: OkHttpClient,
) : ExtensionRunner {

    override suspend fun getList(
        extension: Extension,
        offset: Int,
        order: SortOrder?,
        filter: MangaListFilter?,
    ): List<Manga> = withContext(Dispatchers.IO) {
        val rawQuery = filter?.query
        val queryArg = if (!rawQuery.isNullOrEmpty()) "\"${rawQuery.jsEscape()}\"" else "null"

        val listUrl = resolveListUrl(extension, offset, queryArg)
        val html = fetchHtml(listUrl) ?: return@withContext emptyList()

        val htmlArg = JSONObject.quote(html)
        val script = "${extension.sourceCode}\ngetMangaList($htmlArg, $offset, $queryArg);"
        val json = evalJs(extension, script) ?: return@withContext emptyList()
        parseMangaList(json, extension.baseUrl, extension)
    }

    override suspend fun getDetails(extension: Extension, manga: Manga): Manga =
        withContext(Dispatchers.IO) {
            val pageUrl = if (manga.url.startsWith("http")) manga.url
                          else "${extension.baseUrl}${manga.url}"
            val html = fetchHtml(pageUrl) ?: return@withContext manga

            val htmlArg = JSONObject.quote(html)
            val urlArg = "\"${manga.url.jsEscape()}\""
            val script = "${extension.sourceCode}\ngetMangaDetails($htmlArg, $urlArg);"
            val json = evalJs(extension, script)
            parseDetails(json, manga, extension)
        }

    override suspend fun getPages(extension: Extension, chapter: MangaChapter): List<MangaPage> =
        withContext(Dispatchers.IO) {
            val pageUrl = if (chapter.url.startsWith("http")) chapter.url
                          else "${extension.baseUrl}${chapter.url}"
            val html = fetchHtml(pageUrl) ?: return@withContext emptyList()

            val htmlArg = JSONObject.quote(html)
            val urlArg = "\"${chapter.url.jsEscape()}\""
            val script = "${extension.sourceCode}\ngetChapterPages($htmlArg, $urlArg);"
            val json = evalJs(extension, script) ?: return@withContext emptyList()
            parsePages(json, extension)
        }

    // ─── Internal helpers ────────────────────────────────────────────────────

    /**
     * Evaluates the extension's optional `getMangaListUrl(offset, query)` to
     * obtain the browse/search URL. Falls back to [Extension.baseUrl] on any
     * error or when the function is not defined.
     */
    private fun resolveListUrl(extension: Extension, offset: Int, queryArg: String): String {
        val script = "${extension.sourceCode}\n" +
            "(typeof getMangaListUrl === 'function') " +
            "? getMangaListUrl($offset, $queryArg) : null;"
        return runCatching {
            var result: String? = null
            QuickJs.create(jobDispatcher = Dispatchers.Default).use { qjs ->
                qjs.maxStackSize = 512L * 1024L
                qjs.memoryLimit = 32L shl 20
                result = qjs.evaluate<Any?>(script)?.toString()
            }
            result?.trim('"')?.takeIf { it.startsWith("http") }
        }.getOrNull() ?: extension.baseUrl
    }

    private fun fetchHtml(url: String): String? = runCatching {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
            )
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .build()
        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) null else response.body?.string()
        }
    }.getOrNull()

    private suspend fun evalJs(extension: Extension, script: String): String? = runCatching {
        QuickJs.create(jobDispatcher = Dispatchers.Default).use { qjs ->
            qjs.maxStackSize = 1L shl 20
            qjs.memoryLimit = 64L shl 20
            qjs.evaluate<Any?>(script)?.toString()
        }
    }.getOrElse { e ->
        throw IllegalStateException("JS extension '${extension.name}' failed: ${e.message}", e)
    }

    /** Escapes a Kotlin string for safe embedding inside a JS double-quoted string literal. */
    private fun String.jsEscape(): String = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")

    // ─── JSON → Domain models ────────────────────────────────────────────────

    private fun parseMangaList(json: String, baseUrl: String, extension: Extension): List<Manga> =
        runCatching {
            val root = JSONObject(json)
            val items = root.optJSONArray("items") ?: return emptyList()
            (0 until items.length()).mapNotNull { i ->
                runCatching { items.getJSONObject(i).toMangaStub(baseUrl, extension) }.getOrNull()
            }
        }.getOrDefault(emptyList())

    private fun parseDetails(json: String?, manga: Manga, extension: Extension): Manga {
        if (json.isNullOrEmpty()) return manga
        return runCatching {
            val obj = JSONObject(json)
            val chaptersArr = obj.optJSONArray("chapters")
            val chapters = if (chaptersArr != null) {
                (0 until chaptersArr.length()).mapNotNull { i ->
                    runCatching { chaptersArr.getJSONObject(i).toChapter(extension) }.getOrNull()
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

    private fun parsePages(json: String, extension: Extension): List<MangaPage> = runCatching {
        val root = JSONObject(json)
        val pages = root.optJSONArray("pages") ?: return emptyList()
        val source = ExtensionMangaSource(extension)
        (0 until pages.length()).mapNotNull { i ->
            runCatching {
                val obj = pages.getJSONObject(i)
                MangaPage(
                    id = obj.optInt("index", i).toLong(),
                    url = obj.getString("url"),
                    preview = obj.optString("preview", "").ifEmpty { null },
                    source = source,
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun JSONObject.toMangaStub(baseUrl: String, extension: Extension): Manga {
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
            source = ExtensionMangaSource(extension),
            chapters = null,
        )
    }

    private fun JSONObject.toChapter(extension: Extension): MangaChapter {
        val url = getString("url")
        return MangaChapter(
            id = url.hashCode().toLong() and 0x7FFFFFFF,
            title = optString("title", null).ifEmpty { null },
            number = optDouble("number", 0.0).toFloat(),
            volume = optInt("volume", 0),
            url = url,
            scanlator = optString("scanlator", null).ifEmpty { null },
            uploadDate = optLong("uploadDate", 0L),
            branch = null,
            source = ExtensionMangaSource(extension),
        )
    }

    companion object {
        private const val RATING_UNKNOWN = -1f
    }
}
