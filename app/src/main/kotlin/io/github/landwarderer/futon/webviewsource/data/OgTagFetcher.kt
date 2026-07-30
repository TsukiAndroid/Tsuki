package io.github.landwarderer.futon.webviewsource.data

import io.github.landwarderer.futon.core.network.BaseHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

data class OgData(
    val title: String?,
    val imageUrl: String?,
    val siteUrl: String,   // the final URL after any redirects
)

@Singleton
class OgTagFetcher @Inject constructor(
    @BaseHttpClient private val okHttpClient: OkHttpClient,
) {
    /**
     * Fetches the URL and extracts og:title and og:image from the HTML head.
     * Uses a lightweight regex — no full HTML parser needed for meta tags.
     * Returns null on network error; caller should surface errorEvent.
     */
    suspend fun fetch(url: String): OgData? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val body = response.body?.string() ?: return@runCatching null
            val finalUrl = response.request.url.toString()

            val title = OG_TITLE_RE.find(body)?.groupValues?.get(1)?.trim()
                ?: TITLE_TAG_RE.find(body)?.groupValues?.get(1)?.trim()

            val imageUrl = OG_IMAGE_RE.find(body)?.groupValues?.get(1)?.trim()

            OgData(
                title = title?.takeIf { it.isNotBlank() },
                imageUrl = imageUrl?.takeIf { it.isNotBlank() },
                siteUrl = finalUrl,
            )
        }.getOrNull()
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"

        val OG_TITLE_RE = Regex(
            """<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        val OG_IMAGE_RE = Regex(
            """<meta[^>]+property=["']og:image["'][^>]+content=["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        val TITLE_TAG_RE = Regex(
            """<title[^>]*>([^<]+)</title>""",
            RegexOption.IGNORE_CASE,
        )
    }
}
