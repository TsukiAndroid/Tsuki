package io.github.landwarderer.futon.plugins.data

import android.util.Log
import io.github.landwarderer.futon.plugins.domain.LoadedPlugin
import io.github.landwarderer.futon.plugins.domain.PluginMangaSource
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import io.github.landwarderer.futon.core.parser.MangaRepository

/**
 * Bridges a loaded JAR plugin source to the app's [MangaRepository] interface.
 *
 * All calls are delegated to the underlying plugin instance via reflection.
 * Every call is wrapped in [runCatching] so a misbehaving plugin can never
 * crash the app — errors are logged and empty/default values are returned.
 */
class PluginMangaRepository(
    override val source: PluginMangaSource,
    private val loadedPlugin: LoadedPlugin,
    private val sourceName: String,
) : MangaRepository {

    companion object {
        private const val TAG = "PluginMangaRepository"
    }

    override val sortOrders: Set<SortOrder>
        get() = setOf(SortOrder.POPULARITY, SortOrder.UPDATED, SortOrder.NEWEST, SortOrder.ALPHABETICAL)

    override var defaultSortOrder: SortOrder = SortOrder.POPULARITY

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(isSearchSupported = true)

    override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> {
        return runCatching {
            val parserInstance = getParserForSource() ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            when {
                filter?.query != null ->
                    invokeMethod(parserInstance, "search", filter.query, offset) as? List<Manga>
                else ->
                    invokeMethod(parserInstance, "getList", offset, filter, order) as? List<Manga>
            } ?: emptyList()
        }.getOrElse { e ->
            Log.e(TAG, "getList failed for $sourceName: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun getDetails(manga: Manga): Manga {
        return runCatching {
            val parserInstance = getParserForSource() ?: return manga
            invokeMethod(parserInstance, "getDetails", manga) as? Manga ?: manga
        }.getOrElse { e ->
            Log.e(TAG, "getDetails failed for ${manga.title}: ${e.message}", e)
            manga
        }
    }

    override suspend fun getPages(chapter: MangaChapter): List<MangaPage> {
        return runCatching {
            val parserInstance = getParserForSource() ?: return emptyList()
            @Suppress("UNCHECKED_CAST")
            invokeMethod(parserInstance, "getPages", chapter) as? List<MangaPage> ?: emptyList()
        }.getOrElse { e ->
            Log.e(TAG, "getPages failed for chapter ${chapter.name}: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun getPageUrl(page: MangaPage): String {
        return runCatching {
            val parserInstance = getParserForSource() ?: return page.url
            invokeMethod(parserInstance, "getPageUrl", page) as? String ?: page.url
        }.getOrElse { e ->
            Log.e(TAG, "getPageUrl failed: ${e.message}", e)
            page.url
        }
    }

    override suspend fun getFilterOptions(): MangaListFilterOptions {
        return runCatching {
            val parserInstance = getParserForSource() ?: return MangaListFilterOptions()
            invokeMethod(parserInstance, "getFilterOptions") as? MangaListFilterOptions
                ?: MangaListFilterOptions()
        }.getOrElse { e ->
            Log.e(TAG, "getFilterOptions failed: ${e.message}", e)
            MangaListFilterOptions()
        }
    }

    override suspend fun getRelated(seed: Manga): List<Manga> = emptyList()

    // -------------------------------------------------------------------------
    // Reflection helpers
    // -------------------------------------------------------------------------

    /**
     * Finds the parser/source object within the loaded plugin that corresponds
     * to this repository's [sourceName].
     */
    private fun getParserForSource(): Any? {
        val plugin = loadedPlugin.pluginInstance ?: return null
        return runCatching {
            // Try to get a named source from the plugin
            for (getter in listOf("getSource", "getParser", "getSources", "getParsers")) {
                val raw = runCatching {
                    plugin.javaClass.getMethod(getter).invoke(plugin)
                }.getOrNull() ?: continue

                when (raw) {
                    is List<*> -> {
                        // Find the source matching our name
                        val match = raw.firstOrNull { src ->
                            src != null && reflectName(src) == sourceName
                        }
                        if (match != null) return match
                        if (raw.size == 1) return raw[0]
                    }
                    else -> return raw
                }
            }
            plugin
        }.getOrElse { e ->
            Log.w(TAG, "getParserForSource failed: ${e.message}")
            null
        }
    }

    private fun reflectName(obj: Any): String? =
        runCatching { obj.javaClass.getMethod("name").invoke(obj) as? String }.getOrNull()
            ?: runCatching { obj.javaClass.getField("name").get(obj) as? String }.getOrNull()

    private fun invokeMethod(target: Any, methodName: String, vararg args: Any?): Any? {
        val methods = target.javaClass.methods.filter { it.name == methodName }
        for (method in methods) {
            runCatching {
                if (args.isEmpty()) {
                    method.invoke(target)
                } else {
                    method.invoke(target, *args)
                }
            }.getOrNull()?.let { return it }
        }
        return null
    }
}
