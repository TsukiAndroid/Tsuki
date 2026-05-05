package io.github.landwarderer.futon.core.parser

import io.github.landwarderer.futon.core.model.isBroken
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Matches a user-supplied URL to an existing [MangaParserSource] from the
 * kotatsu-parsers-redo library.
 *
 * The match is performed by comparing the host of the entered URL against the
 * `domain` property of every non-broken parser.  The domain→source map is
 * built once (lazily) and cached for the lifetime of the app.
 *
 * Usage: inject and call [findForUrl] in CustomSourceViewModel.detectAndAddSource
 * *before* CmsTypeDetector so that sites already covered by a Kotatsu parser
 * are routed to the high-quality [ParserMangaRepository] instead of the generic
 * HTML scrapers.
 */
@Singleton
class KotatsuParserMatcher @Inject constructor(
    private val loaderContext: MangaLoaderContext,
) {
    /** domain (no www.) → source.  Built once, never changes at runtime. */
    private val domainCache: Map<String, MangaParserSource> by lazy { buildCache() }

    /**
     * Returns the [MangaParserSource] whose domain matches the host of [url],
     * or `null` if no Kotatsu parser covers that site.
     */
    fun findForUrl(url: String): MangaParserSource? {
        val host = try {
            java.net.URI(url).host?.lowercase()?.removePrefix("www.") ?: return null
        } catch (_: Exception) {
            return null
        }
        return domainCache[host]
    }

    private fun buildCache(): Map<String, MangaParserSource> {
        val map = HashMap<String, MangaParserSource>()
        for (source in MangaParserSource.entries) {
            if (source.isBroken) continue
            runCatching {
                val parser = loaderContext.newParserInstance(source)
                val domain = parser.domain.lowercase().removePrefix("www.")
                map.putIfAbsent(domain, source)
            }
        }
        return map
    }
}
