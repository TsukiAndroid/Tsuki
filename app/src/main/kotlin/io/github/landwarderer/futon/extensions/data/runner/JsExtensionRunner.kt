package io.github.landwarderer.futon.extensions.data.runner

import com.dokar.quickjs.QuickJs
import io.github.landwarderer.futon.extensions.data.ExtensionMangaSource
import io.github.landwarderer.futon.extensions.domain.Extension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
 * ## JS Extension Contract
 *
 * An extension must export the following functions. Each returns a **JSON string**.
 *
 * ```js
 * // Returns: '{"items":[{"url":"/manga/xyz","title":"…","cover":"…","lang":"en"}]}'
 * function getMangaList(offset, query) { … }
 *
 * // Returns: '{"url":"…","title":"…","cover":"…","description":"…","chapters":[…]}'
 * function getMangaDetails(url) { … }
 *
 * // Returns: '{"pages":[{"index":0,"url":"https://img.example.com/1.jpg"}]}'
 * function getChapterPages(url) { … }
 * ```
 */
@Singleton
class JsExtensionRunner @Inject constructor() : ExtensionRunner {

    override suspend fun getList(
        extension: Extension,
        offset: Int,
        order: SortOrder?,
        filter: MangaListFilter?,
    ): List<Manga> = withContext(Dispatchers.Default) {
        val query = filter?.query?.let { "\"${it.replace("\"", "\\\"")}\"" } ?: "null"
        val script = "${extension.sourceCode}\ngetMangaList($offset, $query);"
        val json = evalJs(extension, script) ?: return@withContext emptyList()
        parseMangaList(json, extension.baseUrl, extension)
    }

    override suspend fun getDetails(extension: Extension, manga: Manga): Manga =
        withContext(Dispatchers.Default) {
            val url = manga.url.replace("\"", "\\\"")
            val script = "${extension.sourceCode}\ngetMangaDetails(\"$url\");"
            val json = evalJs(extension, script)
            parseDetails(json, manga, extension)
        }

    override suspend fun getPages(extension: Extension, chapter: MangaChapter): List<MangaPage> =
        withContext(Dispatchers.Default) {
            val url = chapter.url.replace("\"", "\\\"")
            val script = "${extension.sourceCode}\ngetChapterPages(\"$url\");"
            val json = evalJs(extension, script) ?: return@withContext emptyList()
            parsePages(json, extension)
        }

    private suspend fun evalJs(extension: Extension, script: String): String? = runCatching {
        QuickJs.create(jobDispatcher = Dispatchers.Default).use { qjs ->
            qjs.maxStackSize = 1L shl 20
            qjs.memoryLimit = 64L shl 20
            qjs.evaluate<Any?>(script)?.toString()
        }
    }.getOrElse { e ->
        throw IllegalStateException("JS extension '${extension.name}' failed: ${e.message}", e)
    }

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
            isNsfw = false,
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
