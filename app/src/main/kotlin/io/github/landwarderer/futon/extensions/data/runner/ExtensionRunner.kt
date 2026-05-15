package io.github.landwarderer.futon.extensions.data.runner

import io.github.landwarderer.futon.extensions.domain.Extension
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.SortOrder

/**
 * Common interface implemented by each extension runtime (JS, Dart, Mihon bridge, JSON template).
 *
 * Each runner is stateless; callers pass the [Extension] on every call so that
 * a single runner instance can serve multiple installed extensions of the same type.
 */
interface ExtensionRunner {

    /**
     * Returns a page of manga entries for the given [offset].
     *
     * @param extension The extension to execute.
     * @param offset    Paging offset (0 = first page).
     * @param order     Optional sort order hint.
     * @param filter    Optional search / tag filter.
     */
    suspend fun getList(
        extension: Extension,
        offset: Int,
        order: SortOrder?,
        filter: MangaListFilter?,
    ): List<Manga>

    /**
     * Returns full details (chapters, cover, description) for a manga stub.
     */
    suspend fun getDetails(extension: Extension, manga: Manga): Manga

    /**
     * Returns all pages for a chapter.
     */
    suspend fun getPages(extension: Extension, chapter: MangaChapter): List<MangaPage>

    /**
     * Resolves the final image URL for a page (default: returns [MangaPage.url]).
     */
    suspend fun getPageUrl(extension: Extension, page: MangaPage): String = page.url
}
