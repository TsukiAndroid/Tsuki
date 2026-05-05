package io.github.landwarderer.futon.core.parser

import okhttp3.OkHttpClient
import okhttp3.Response
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.MangaParser
import org.koitharu.kotatsu.parsers.bitmap.Bitmap
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.config.MangaSourceConfig
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.webview.InterceptedRequest
import org.koitharu.kotatsu.parsers.webview.InterceptionConfig
import java.util.Locale

/**
 * A [MangaLoaderContext] wrapper that overrides the domain returned by [getConfig]
 * for a specific [templateSource].  Every other method delegates unchanged to
 * [delegate].
 *
 * Purpose: when a user adds a custom source whose URL is a mirror or renamed
 * clone of a site already covered by a Kotatsu parser, we instantiate that
 * parser via [templateSource] but point it at [customDomain] instead of its
 * hardcoded default — giving the custom source full inbuilt-source quality
 * (genre filters, chapter lists, page reader, mirror switching, everything).
 */
class DomainOverrideLoaderContext(
    private val delegate: MangaLoaderContext,
    private val templateSource: MangaParserSource,
    private val customDomain: String,
) : MangaLoaderContext() {

    override val httpClient: OkHttpClient get() = delegate.httpClient
    override val cookieJar get() = delegate.cookieJar

    /**
     * For [templateSource] return a config that answers [customDomain] on any
     * [ConfigKey.Domain] request.  Everything else passes through to the real
     * [SourceSettings]-backed config.
     */
    override fun getConfig(source: MangaSource): MangaSourceConfig {
        val base = delegate.getConfig(source)
        return if (source == templateSource) DomainOverrideConfig(base, customDomain) else base
    }

    // ── Delegation boilerplate — all other abstract members pass through ──────

    @Deprecated("Provide a base url", ReplaceWith("evaluateJs(baseUrl, script, timeout)"))
    override suspend fun evaluateJs(script: String): String? =
        @Suppress("DEPRECATION") delegate.evaluateJs(script)

    override suspend fun evaluateJs(baseUrl: String, script: String, timeout: Long): String? =
        delegate.evaluateJs(baseUrl, script, timeout)

    override fun getDefaultUserAgent(): String = delegate.getDefaultUserAgent()
    override fun encodeBase64(data: ByteArray): String = delegate.encodeBase64(data)
    override fun decodeBase64(data: String): ByteArray = delegate.decodeBase64(data)
    override fun getPreferredLocales(): List<Locale> = delegate.getPreferredLocales()

    override fun requestBrowserAction(parser: MangaParser, url: String): Nothing =
        delegate.requestBrowserAction(parser, url)

    override fun redrawImageResponse(response: Response, redraw: (Bitmap) -> Bitmap): Response =
        delegate.redrawImageResponse(response, redraw)

    override fun createBitmap(width: Int, height: Int): Bitmap =
        delegate.createBitmap(width, height)

    override suspend fun interceptWebViewRequests(
        url: String,
        interceptorScript: String,
        timeout: Long,
    ): List<InterceptedRequest> = delegate.interceptWebViewRequests(url, interceptorScript, timeout)

    override suspend fun interceptWebViewRequests(
        url: String,
        config: InterceptionConfig,
    ): List<InterceptedRequest> = delegate.interceptWebViewRequests(url, config)

    override suspend fun captureWebViewUrls(
        pageUrl: String,
        urlPattern: Regex,
        timeout: Long,
    ): List<String> = delegate.captureWebViewUrls(pageUrl, urlPattern, timeout)
}

/**
 * A [MangaSourceConfig] that intercepts [ConfigKey.Domain] requests and returns
 * [customDomain], delegating every other key to [delegate].
 */
internal class DomainOverrideConfig(
    private val delegate: MangaSourceConfig,
    private val customDomain: String,
) : MangaSourceConfig {
    @Suppress("UNCHECKED_CAST")
    override fun <T> get(key: ConfigKey<T>): T =
        if (key is ConfigKey.Domain) customDomain as T else delegate[key]
}
