package io.github.landwarderer.futon.customsource.domain

import org.koitharu.kotatsu.parsers.model.MangaSource

/**
 * Wraps a user-defined [CustomSource] so it can travel through the same
 * pipelines (Explore, Sources list, MangaListActivity, etc.) that built-in
 * parser sources use.
 *
 * The [name] uses the prefix [NAME_PREFIX] + the source's id so the source
 * survives any place that serializes a [MangaSource] just by its [name].
 */
data class CustomMangaSource(
    val source: CustomSource,
) : MangaSource {

    override val name: String
        get() = NAME_PREFIX + source.id

    val displayTitle: String
        get() = source.displayName

    companion object {
        const val NAME_PREFIX = "CUSTOM_"

        fun extractId(name: String): Long? =
            if (name.startsWith(NAME_PREFIX)) {
                name.removePrefix(NAME_PREFIX).toLongOrNull()
            } else {
                null
            }
    }
}
