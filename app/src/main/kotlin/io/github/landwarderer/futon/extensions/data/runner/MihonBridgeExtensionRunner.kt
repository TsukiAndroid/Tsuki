package io.github.landwarderer.futon.extensions.data.runner

import io.github.landwarderer.futon.extensions.domain.Extension
import io.github.landwarderer.futon.mihon.MihonExtensionManager
import io.github.landwarderer.futon.mihon.MihonMangaRepository
import io.github.landwarderer.futon.mihon.model.MihonMangaSource
import io.github.landwarderer.futon.core.cache.MemoryContentCache
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
 * Extension runner that bridges to the existing Mihon/Tachiyomi APK system.
 *
 * This runner does NOT duplicate the Mihon infrastructure — it delegates directly
 * to the existing [MihonExtensionManager] and [MihonMangaRepository], keeping
 * the bridge code isolated in one place.
 *
 * An extension with type [io.github.landwarderer.futon.extensions.domain.ExtensionType.MIHON_APK]
 * stores the APK [Extension.packageName] that the Mihon manager already tracks.
 * If the Mihon APK is not installed the runner throws a descriptive error.
 */
@Singleton
class MihonBridgeExtensionRunner @Inject constructor(
    private val mihonExtensionManager: MihonExtensionManager,
    private val memoryContentCache: MemoryContentCache,
) : ExtensionRunner {

    override suspend fun getList(
        extension: Extension,
        offset: Int,
        order: SortOrder?,
        filter: MangaListFilter?,
    ): List<Manga> = withContext(Dispatchers.IO) {
        val repo = resolveRepo(extension)
        repo.getList(offset, order, filter)
    }

    override suspend fun getDetails(extension: Extension, manga: Manga): Manga =
        withContext(Dispatchers.IO) {
            resolveRepo(extension).getDetails(manga)
        }

    override suspend fun getPages(extension: Extension, chapter: MangaChapter): List<MangaPage> =
        withContext(Dispatchers.IO) {
            resolveRepo(extension).getPages(chapter)
        }

    override suspend fun getPageUrl(extension: Extension, page: MangaPage): String =
        withContext(Dispatchers.IO) {
            resolveRepo(extension).getPageUrl(page)
        }

    private fun resolveRepo(extension: Extension): MihonMangaRepository {
        val sources: List<MihonMangaSource> = mihonExtensionManager.getMihonMangaSources()
        val source = sources.firstOrNull { it.pkgName == extension.packageName }
            ?: throw IllegalStateException(
                "Mihon APK '${extension.packageName}' is not installed. " +
                    "Please install the APK first, then re-enable this extension.",
            )
        return MihonMangaRepository(source = source, cache = memoryContentCache)
    }
}
