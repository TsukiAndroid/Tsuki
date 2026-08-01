package io.github.landwarderer.futon.plugins.domain

import java.lang.reflect.Method

/**
 * Represents a plugin that has been successfully loaded from disk into memory.
 *
 * [sources] is the list of [PluginMangaSource] this plugin provides.
 * [classLoader] is the DexClassLoader that owns the plugin classes.
 *
 * Two loading formats are supported:
 *
 * **Legacy format** (old reflection-based plugins):
 * - [pluginInstance] holds the root plugin object; [PluginMangaRepository] calls
 *   `getSources()` / `getParsers()` on it via reflection.
 * - [newParserMethod], [sourceEnumClass], and [loaderContextProxy] are null.
 *
 * **Tsuki/UMA factory format** (modern Usagi-compatible plugins built with KSP):
 * - [pluginInstance] is null.
 * - [sourceEnumClass] is the `MangaParserSource` enum listing every source.
 * - [newParserMethod] is the static `MangaParserFactoryKt.newParser(source, ctx)` method.
 * - [loaderContextProxy] is a `java.lang.reflect.Proxy` that wraps the host's
 *   `MangaLoaderContext` so it appears as the plugin's context type.
 */
data class LoadedPlugin(
    val metadata: Plugin,
    val sources: List<PluginMangaSource>,
    val pluginInstance: Any?,
    val classLoader: ClassLoader,
    // ---- UMA / tsuki-format fields (null for legacy plugins) ----
    val newParserMethod: Method? = null,
    val sourceEnumClass: Class<*>? = null,
    val loaderContextProxy: Any? = null,
) {
    /** True when this plugin was loaded via the tsuki/UMA factory pattern. */
    val isTsukiFormat: Boolean get() = newParserMethod != null && sourceEnumClass != null
}
