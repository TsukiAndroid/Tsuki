package io.github.landwarderer.futon.plugins.domain

import org.koitharu.kotatsu.parsers.model.MangaSource

/**
 * A [MangaSource] wrapper that represents a single manga source provided
 * by an installed JAR plugin.
 *
 * The [name] uses [NAME_PREFIX] + pluginId + "_" + sourceName so the source
 * round-trips correctly through any pipeline that serialises [MangaSource] by name.
 */
data class PluginMangaSource(
    val pluginId: String,
    val sourceName: String,
    val displayName: String,
    val pluginDisplayName: String,
    val locale: String = "",
) : MangaSource {

    override val name: String
        get() = "$NAME_PREFIX${pluginId}_$sourceName"

    companion object {
        const val NAME_PREFIX = "PLUGIN_"

        fun isPluginSourceName(name: String): Boolean = name.startsWith(NAME_PREFIX)

        /**
         * Extracts the pluginId from a serialised plugin source name.
         * Returns null if the name is not a plugin source name.
         */
        fun extractPluginId(name: String): String? {
            if (!isPluginSourceName(name)) return null
            val withoutPrefix = name.removePrefix(NAME_PREFIX)
            return withoutPrefix.substringBefore('_').takeIf { it.isNotEmpty() }
        }
    }
}
