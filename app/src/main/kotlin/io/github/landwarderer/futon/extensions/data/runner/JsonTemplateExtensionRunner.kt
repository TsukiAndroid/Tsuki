package io.github.landwarderer.futon.extensions.data.runner

import io.github.landwarderer.futon.customsource.data.ParserTemplateRepository
import io.github.landwarderer.futon.customsource.data.TemplateHtmlParser
import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import io.github.landwarderer.futon.customsource.domain.CustomSource
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import io.github.landwarderer.futon.extensions.domain.Extension
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.SortOrder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Extension runner that surfaces an existing [ParserTemplate] (TemplateHtmlParser)
 * through the new unified extension system UI.
 *
 * This runner does NOT modify TemplateHtmlParser or CustomSourcesRepository.
 * It only creates a thin [TemplateHtmlParser] wrapper for the template stored in
 * [Extension.templateName].
 */
@Singleton
class JsonTemplateExtensionRunner @Inject constructor() : ExtensionRunner {

    override suspend fun getList(
        extension: Extension,
        offset: Int,
        order: SortOrder?,
        filter: MangaListFilter?,
    ): List<Manga> = withContext(Dispatchers.IO) {
        val parser = resolveParser(extension)
        parser.getList(offset, order, filter)
    }

    override suspend fun getDetails(extension: Extension, manga: Manga): Manga =
        withContext(Dispatchers.IO) {
            resolveParser(extension).getDetails(manga)
        }

    override suspend fun getPages(extension: Extension, chapter: MangaChapter): List<MangaPage> =
        withContext(Dispatchers.IO) {
            resolveParser(extension).getPages(chapter)
        }

    private fun resolveParser(extension: Extension): TemplateHtmlParser {
        if (ParserTemplateRepository.peekByName(extension.templateName) == null) {
            throw IllegalStateException(
                "Parser template '${extension.templateName}' is not installed. " +
                    "Import the template from the Parsers section first.",
            )
        }

        val syntheticSource = CustomMangaSource(
            CustomSource(
                id = extension.id.hashCode().toLong(),
                displayName = extension.name,
                baseUrl = extension.baseUrl,
                type = CustomSourceType.TEMPLATE,
                parserSourceName = extension.templateName,
            ),
        )
        return TemplateHtmlParser(syntheticSource)
    }
}
