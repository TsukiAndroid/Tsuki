package io.github.landwarderer.futon.core.parser

import io.github.landwarderer.futon.core.model.isBroken
import io.github.landwarderer.futon.core.network.MangaHttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Matches a user-supplied URL to an existing [MangaParserSource] from the
 * kotatsu-parsers-redo library using two strategies:
 *
 * **1. Exact domain match** (instant, no network):
 * Every non-broken parser's `domain` property is indexed at first use.
 * If the host of the entered URL is already in that index, we return the
 * matching source immediately.
 *
 * **2. API fingerprinting** (makes a few cheap HTTP probes):
 * If no exact match is found we probe the site's well-known API endpoints.
 * Each fingerprint targets a distinct parser API family:
 *   – MangaDex REST API   (`/manga?limit=1`)
 *   – Guya reader API     (`/api/series/`)
 *   – ComicK-compatible   (`/comic?page=1&limit=1`)
 *   – ZeroScans API       (`/comics?page=1&per_page=1`)
 *
 * When a fingerprint matches we return a *template* parser whose class handles
 * that API.  The caller ([CustomSourceViewModel]) stores the template's name in
 * [CustomSource.parserSourceName].  [MangaRepository.Factory] then instantiates
 * that parser through a [DomainOverrideLoaderContext] so the parser hits the
 * user's domain instead of the template's hardcoded one — giving the custom
 * source full inbuilt-source quality even for sites that never existed before.
 */
@Singleton
class KotatsuParserMatcher @Inject constructor(
    private val loaderContext: MangaLoaderContext,
    @MangaHttpClient private val httpClient: OkHttpClient,
) {
    // ── Domain cache (Step 1) ─────────────────────────────────────────────────

    /** domain (no www.) → source.  Built once, lazily. */
    private val domainCache: Map<String, MangaParserSource> by lazy { buildDomainCache() }

    // ── Fingerprint template cache (Step 2) ──────────────────────────────────

    /**
     * API-type key → best template [MangaParserSource] that:
     *   a) belongs to that API family (identified by preferred name list), AND
     *   b) exposes [ConfigKey.Domain] (so its domain can be overridden).
     *
     * Built once, lazily, alongside [domainCache].
     */
    private val fingerprintTemplates: Map<String, MangaParserSource> by lazy {
        buildFingerprintTemplates()
    }

    /**
     * Preferred parser names per API fingerprint type.
     * Keys match those used in [fingerprintTemplates] and [probeFingerprint].
     * Listed most-specific first; first available entry wins.
     */
    private val PREFERRED_TEMPLATES = mapOf(
        "mangadex"  to listOf("MANGADEX", "MANGADEX_TEST", "MANGADEX_ORG"),
        "guya"      to listOf("GUYA", "GUYA_MOE"),
        "comick"    to listOf("COMICK", "COMICK_FUN", "COMICK_IO", "COMICKFUN"),
        "zeroscans" to listOf("ZEROSCANS", "ZERO_SCANS", "ZEROEROSCANS"),
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Returns the best [MangaParserSource] match for [url], or `null` if none.
     *
     * Called from [CustomSourceViewModel] on a background thread
     * ([Dispatchers.IO]); blocking network calls are fine here.
     */
    fun findForUrl(url: String): MangaParserSource? {
        val host = hostFromUrl(url) ?: return null

        // Step 1: free, instant exact-domain hit
        domainCache[host]?.let { return it }

        // Step 2: API fingerprinting — a few cheap HTTP probes
        return probeFingerprint(url)
    }

    // ── Domain cache construction ─────────────────────────────────────────────

    private fun buildDomainCache(): Map<String, MangaParserSource> {
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

    // ── Fingerprint template cache construction ───────────────────────────────

    private fun buildFingerprintTemplates(): Map<String, MangaParserSource> {
        val byName: Map<String, MangaParserSource> = MangaParserSource.entries
            .filter { !it.isBroken }
            .associateBy { it.name }

        return buildMap {
            for ((apiType, candidates) in PREFERRED_TEMPLATES) {
                val template = candidates.firstNotNullOfOrNull { name ->
                    byName[name]?.takeIf { src ->
                        // Only keep parsers that expose ConfigKey.Domain
                        runCatching {
                            loaderContext.newParserInstance(src)
                                .configKeys.any { it is ConfigKey.Domain }
                        }.getOrElse { false }
                    }
                }
                if (template != null) put(apiType, template)
            }
        }
    }

    // ── API fingerprinting ────────────────────────────────────────────────────

    private fun probeFingerprint(baseUrl: String): MangaParserSource? {
        if (probeMangaDex(baseUrl)) return fingerprintTemplates["mangadex"]
        if (probeGuya(baseUrl))     return fingerprintTemplates["guya"]
        if (probeComicK(baseUrl))   return fingerprintTemplates["comick"]
        if (probeZeroScans(baseUrl))return fingerprintTemplates["zeroscans"]
        return null
    }

    /**
     * MangaDex REST API v5:
     * `GET /manga?limit=1` → `{ "result": "ok", "response": "collection", "data": [...] }`
     */
    private fun probeMangaDex(base: String): Boolean = runCatching {
        val body = httpGet("$base/manga?limit=1") ?: return@runCatching false
        val json = JSONObject(body)
        json.optString("result") == "ok" && json.has("data")
    }.getOrElse { false }

    /**
     * Guya / Fan-TL reader API:
     * `GET /api/series/` → JSON object whose values each contain a `chapters` key.
     */
    private fun probeGuya(base: String): Boolean = runCatching {
        val body = httpGet("$base/api/series/") ?: return@runCatching false
        val json = JSONObject(body)
        val firstKey = json.keys().asSequence().firstOrNull() ?: return@runCatching false
        json.optJSONObject(firstKey)?.has("chapters") == true
    }.getOrElse { false }

    /**
     * ComicK-compatible REST API:
     * `GET /comic?page=1&limit=1` → `{ "data": [...], "total": N }`
     */
    private fun probeComicK(base: String): Boolean = runCatching {
        val body = httpGet("$base/comic?page=1&limit=1") ?: return@runCatching false
        val json = JSONObject(body)
        json.has("data") && json.has("total")
    }.getOrElse { false }

    /**
     * ZeroScans API:
     * `GET /comics?page=1&per_page=1` → `{ "data": [{ "name": "...", "slug": "..." }] }`
     */
    private fun probeZeroScans(base: String): Boolean = runCatching {
        val body = httpGet("$base/comics?page=1&per_page=1") ?: return@runCatching false
        val json = JSONObject(body)
        val data: JSONArray = json.optJSONArray("data") ?: return@runCatching false
        data.length() > 0 && data.optJSONObject(0)?.has("slug") == true
    }.getOrElse { false }

    // ── HTTP helper ───────────────────────────────────────────────────────────

    private val probeClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    private fun httpGet(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .get()
            .build()
        probeClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (_: Exception) {
        null
    }

    // ── Shared util ───────────────────────────────────────────────────────────

    private fun hostFromUrl(url: String): String? = try {
        java.net.URI(url).host?.lowercase()?.removePrefix("www.")
    } catch (_: Exception) {
        null
    }
}
