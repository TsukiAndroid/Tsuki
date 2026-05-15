package io.github.landwarderer.futon.extensions.data

import io.github.landwarderer.futon.core.cache.MemoryContentCache
import io.github.landwarderer.futon.core.parser.CachingMangaRepository
import io.github.landwarderer.futon.extensions.data.runner.DartExtensionRunner
import io.github.landwarderer.futon.extensions.data.runner.JsExtensionRunner
import io.github.landwarderer.futon.extensions.data.runner.JsonTemplateExtensionRunner
import io.github.landwarderer.futon.extensions.data.runner.MihonBridgeExtensionRunner
import io.github.landwarderer.futon.extensions.domain.Extension
import io.github.landwarderer.futon.extensions.domain.ExtensionType
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.SortOrder

/**
 * Adapts any installed [Extension] to the [MangaRepository] interface by
 * delegating to the appropriate [ExtensionRunner] for each extension type.
 *
 * One instance is created per [ExtensionMangaSource] by [MangaRepository.Factory].
 * The Caching layer in [CachingMangaRepository] handles memoisation of details / pages.
 */
class ExtensionMangaRepository(
    override val source: ExtensionMangaSource,
    private val extension: Extension,
    private val jsRunner: JsExtensionRunner,
    private val dartRunner: DartExtensionRunner,
    private val mihonBridgeRunner: MihonBridgeExtensionRunner,
    private val jsonTemplateRunner: JsonTemplateExtensionRunner,
    cache: MemoryContentCache,
) : CachingMangaRepository(cache) {

    override val sortOrders: Set<SortOrder> = setOf(SortOrder.NEWEST, SortOrder.POPULARITY)

    override var defaultSortOrder: SortOrder = SortOrder.NEWEST

    override val filterCapabilities: MangaListFilterCapabilities
        get() = MangaListFilterCapabilities(
            isSearchSupported = true,
            isMultipleTagsSupported = false,
            isSearchWithFiltersSupported = false,
        )

    override suspend fun getList(offset: Int, order: SortOrder?, filter: MangaListFilter?): List<Manga> =
        runner().getList(extension, offset, order, filter)

    override suspend fun getDetailsImpl(manga: Manga): Manga =
        runner().getDetails(extension, manga)

    override suspend fun getPagesImpl(chapter: MangaChapter): List<MangaPage> =
        runner().getPages(extension, chapter)

    override suspend fun getRelatedMangaImpl(seed: Manga): List<Manga> = emptyList()

    override suspend fun getPageUrl(page: MangaPage): String =
        runner().getPageUrl(extension, page)

    override suspend fun getFilterOptions(): MangaListFilterOptions =
        MangaListFilterOptions()

    private fun runner() = when (extension.type) {
        ExtensionType.JS -> jsRunner
        ExtensionType.DART -> dartRunner
        ExtensionType.MIHON_APK -> mihonBridgeRunner
        ExtensionType.JSON_TEMPLATE -> jsonTemplateRunner
    }
}
