package io.github.landwarderer.futon.extensions.data

import io.github.landwarderer.futon.extensions.domain.Extension
import org.koitharu.kotatsu.parsers.model.MangaSource

/**
 * Wraps an installed [Extension] so it can travel through the Explore / Sources
 * pipelines alongside built-in parser sources and Custom Sources.
 *
 * The [name] is prefixed with [NAME_PREFIX] + [Extension.id] so it survives
 * serialisation through any layer that uses [MangaSource.name] as the key.
 *
 * This class is entirely separate from [CustomMangaSource] and [MihonMangaSource].
 */
data class ExtensionMangaSource(
    val extension: Extension,
) : MangaSource {

    override val name: String
        get() = NAME_PREFIX + extension.id

    val displayTitle: String
        get() = extension.name

    companion object {
        const val NAME_PREFIX = "EXTENSION_"

        fun isExtensionSource(name: String): Boolean = name.startsWith(NAME_PREFIX)

        fun extractId(name: String): String? =
            if (name.startsWith(NAME_PREFIX)) name.removePrefix(NAME_PREFIX) else null
    }
}
