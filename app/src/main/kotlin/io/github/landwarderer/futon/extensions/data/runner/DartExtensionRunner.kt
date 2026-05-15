package io.github.landwarderer.futon.extensions.data.runner

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
 * Runs Dart extensions via D4rt (com.github.kodjodevf:d4rt).
 *
 * ## Dart Extension Contract
 *
 * A Dart extension is a single `.dart` file that must define:
 *
 * ```dart
 * // Returns JSON string: '{"items":[{"url":"…","title":"…","cover":"…"}]}'
 * String getMangaList(int offset, String? query) { … }
 *
 * // Returns JSON string: '{"url":"…","title":"…","chapters":[…]}'
 * String getMangaDetails(String url) { … }
 *
 * // Returns JSON string: '{"pages":[{"index":0,"url":"…"}]}'
 * String getChapterPages(String url) { … }
 * ```
 *
 * D4rt is loaded reflectively so the app continues to function even if the dependency
 * is not yet available on the device's classpath; Dart extension items in the UI will
 * show a "Dart engine unavailable" error instead of crashing.
 */
@Singleton
class DartExtensionRunner @Inject constructor() : ExtensionRunner {

    override suspend fun getList(
        extension: Extension,
        offset: Int,
        order: SortOrder?,
        filter: MangaListFilter?,
    ): List<Manga> = withContext(Dispatchers.IO) {
        val query = filter?.query
        val json = evalDart(
            extension = extension,
            call = "getMangaList($offset, ${if (query != null) "\"${query.replace("\"", "\\\"")}\"" else "null"})",
        ) ?: return@withContext emptyList()
        parseMangaList(json, extension.baseUrl, extension)
    }

    override suspend fun getDetails(extension: Extension, manga: Manga): Manga =
        withContext(Dispatchers.IO) {
            val url = manga.url.replace("\"", "\\\"")
            val json = evalDart(extension = extension, call = "getMangaDetails(\"$url\")")
            parseDetails(json, manga)
        }

    override suspend fun getPages(extension: Extension, chapter: MangaChapter): List<MangaPage> =
        withContext(Dispatchers.IO) {
            val url = chapter.url.replace("\"", "\\\"")
            val json = evalDart(extension = extension, call = "getChapterPages(\"$url\")")
                ?: return@withContext emptyList()
            parsePages(json, extension)
        }

    /**
     * Evaluates Dart code using D4rt loaded reflectively.
     *
     * Falls back with a descriptive error if D4rt is not on the classpath.
     */
    private suspend fun evalDart(extension: Extension, call: String): String? {
        return runCatching {
            val fullSource = "${extension.sourceCode}\nvoid main() { print($call); }"
            val interpreterClass = Class.forName("com.github.kodjodevf.d4rt.D4rtInterpreter")
            val interpreter = interpreterClass.getDeclaredConstructor().newInstance()
            val executeMethod = interpreterClass.getMethod("execute", String::class.java)
            executeMethod.invoke(interpreter, fullSource) as? String
        }.getOrElse { e ->
            when (e) {
                is ClassNotFoundException ->
                    throw UnsupportedOperationException(
                        "Dart extensions require D4rt which is not available on this device.",
                        e,
                    )
                else ->
                    throw IllegalStateException(
                        "Dart extension '${extension.name}' execution failed: ${e.message}",
                        e,
                    )
            }
        }
    }

    private fun parseMangaList(
        json: String,
        baseUrl: String,
        extension: Extension,
    ): List<Manga> = runCatching {
        val root = JSONObject(json)
        val items = root.optJSONArray("items") ?: return emptyList()
        (0 until items.length()).mapNotNull { i ->
            runCatching { items.getJSONObject(i).toMangaStub(baseUrl, extension) }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun parseDetails(json: String?, manga: Manga): Manga {
        if (json.isNullOrEmpty()) return manga
        return runCatching {
            val obj = JSONObject(json)
            manga.copy(
                title = obj.optString("title", manga.title),
                coverUrl = obj.optString("cover", manga.coverUrl),
                description = obj.optString("description", manga.description),
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
                    preview = null,
                    source = source,
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun JSONObject.toMangaStub(baseUrl: String, extension: Extension): Manga {
        val url = optString("url", "")
        return Manga(
            id = url.hashCode().toLong() and 0x7FFFFFFF,
            title = optString("title", ""),
            altTitles = emptySet(),
            url = url,
            publicUrl = if (url.startsWith("http")) url else "$baseUrl$url",
            rating = -1f,
            contentRating = ContentRating.SAFE,
            coverUrl = optString("cover", ""),
            largeCoverUrl = null,
            description = null,
            tags = emptySet(),
            state = null,
            authors = emptySet(),
            source = ExtensionMangaSource(extension),
            chapters = null,
            isNsfw = false,
        )
    }
}
