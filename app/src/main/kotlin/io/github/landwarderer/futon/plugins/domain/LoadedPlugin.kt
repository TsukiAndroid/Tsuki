package io.github.landwarderer.futon.plugins.domain

/**
 * Represents a plugin that has been successfully loaded from disk into memory.
 *
 * [sources] is the list of [PluginMangaSource] this plugin provides.
 * [pluginInstance] is the raw object loaded from the JAR (used by PluginMangaRepository
 * to delegate actual HTTP calls).
 * [classLoader] is the DexClassLoader that owns the plugin classes.
 */
data class LoadedPlugin(
    val metadata: Plugin,
    val sources: List<PluginMangaSource>,
    val pluginInstance: Any?,
    val classLoader: ClassLoader,
)
