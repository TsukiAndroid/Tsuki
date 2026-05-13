package io.github.landwarderer.futon.core.parser

import io.github.landwarderer.futon.customsource.data.CmsTypeDetector
import io.github.landwarderer.futon.customsource.data.CustomMangaRepository
import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.SortOrder
import java.util.concurrent.atomic.AtomicReference

/**
 * Wraps a [primary] [MangaRepository] (a [ParserMangaRepository] backed by an
 * upstream Kotatsu built-in parser) and transparently falls back to a
 * [CustomMangaRepository] whose CMS type is auto-detected from the site when
 * the primary returns an empty list.
 *
 * **When the fallback fires** — [getList] returns empty:
 * This is the canonical indicator that the upstream parser's HTML selectors no
 * longer match the live site (site redesign, theme switch, outdated upstream
 * library entry).  An empty list from [getList] triggers a one-time network
 * probe via [CmsTypeDetector.detect] and a retry with the matching
 * [CustomMangaRepository].
 *
 * [getDetails] and [getPages] always use the primary parser.  Chapter detail
 * and page URLs were built by the primary parser, so only the primary parser
 * knows how to resolve them.  Only swap those if the primary also errors there
 * after confirming [getList] via the fallback works end-to-end.
 *
 * **Lazy, cached detection:**
 * [CmsTypeDetector.detect] is called at most once per instance.  The detected
 * [CustomMangaRepository] is stored in an [AtomicReference].  Concurrent
 * coroutines that both see an empty list may each probe the site; the CAS
 * ensures exactly one instance wins and all callers converge to it.
 *
 * **Happy-path overhead:**
 * If the upstream parser works, the overhead per [getList] call is a single
 * `isNotEmpty()` check — no extra network traffic, no allocation.
 *
 * This wrapper is only used for [CustomSourceType.KOTATSU_PARSER] sources where
 * we have a valid upstream [ParserMangaRepository].  If the upstream parser
 * lookup fails in the factory the code falls through to a plain
 * [CustomMangaRepository] as before.
 */
internal class KotatsuParserFallbackRepository(
    private val primary: MangaRepository,
    private val customSource: CustomMangaSource,
) : MangaRepository by primary {

    private val fallbackRef = AtomicReference<CustomMangaRepository?>(null)

    // ── getList: try primary, fall back on empty ──────────────────────────────

    override suspend fun getList(
        offset: Int,
        order: SortOrder?,
        filter: MangaListFilter?,
    ): List<Manga> {
        val primaryResult = runCatching {
            primary.getList(offset, order, filter)
        }.getOrElse { emptyList() }

        if (primaryResult.isNotEmpty()) return primaryResult

        // Primary returned empty — the upstream parser's selectors likely don't
        // match the live site anymore.  Detect the real CMS type and retry.
        return fallbackRepo().getList(offset, order, filter)
    }

    // ── Lazy fallback construction ────────────────────────────────────────────

    /**
     * Returns the cached [CustomMangaRepository] or builds one by running
     * [CmsTypeDetector.detect] on the custom source's URL.
     *
     * The first call performs a blocking network probe on [Dispatchers.IO].
     * All subsequent calls return the cached instance immediately.
     */
    private suspend fun fallbackRepo(): CustomMangaRepository {
        fallbackRef.get()?.let { return it }

        val detectedType: CustomSourceType = withContext(Dispatchers.IO) {
            runCatching {
                CmsTypeDetector.detect(customSource.source.baseUrl)
            }.getOrElse { CustomSourceType.WEBVIEW }
        }

        val repo = CustomMangaRepository(
            customSource = customSource.copy(
                source = customSource.source.copy(type = detectedType),
            ),
        )

        // CAS: if a concurrent coroutine built the repo first, use that one.
        fallbackRef.compareAndSet(null, repo)
        return fallbackRef.get()!!
    }
}
