package io.github.landwarderer.futon.core.parser

import io.github.landwarderer.futon.core.model.isBroken
import io.github.landwarderer.futon.core.network.MangaHttpClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
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
 * **2. API / HTML fingerprinting** (cheap HTTP probes):
 * If no exact match is found we probe the site's well-known API endpoints or
 * HTML markers.  Each fingerprint targets a distinct CMS or API family:
 *
 *   WordPress-based (most common, checked first):
 *     – Madara theme         (`/wp-json/` + madara namespace, or HTML markers)
 *     – MangaThemesia theme  (`/wp-json/` + mangathemesia namespace)
 *     – MangaStream theme    (HTML: themes/mangastream or WP manga markers)
 *
 *   JSON-API-based:
 *     – MangaDex v5         (`/manga?limit=1`)
 *     – ComicK              (`/comic?page=1&limit=1`)
 *     – ZeroScans           (`/comics?page=1&per_page=1`)
 *     – Tachidesk/Suwayomi  (`/api/v1/settings` → JSON with `ip`)
 *     – Genkan CMS          (`/api/chapter_groups?per_page=1`)
 *     – FoolSlide2          (`/fs_api/reader/chapter_groups` or `/api/reader/`)
 *     – MangaPark v5        (`/api/v5/search/comic?limit=1`)
 *
 *   HTML-based (reader-specific page structure):
 *     – Guya / fan-TL      (`/api/series/`)
 *     – MangaSee / MangaLife (HTML: `vm.Directory =`)
 *     – MangaFire           (HTML: `site:mangafire` meta or specific JS)
 *     – LHTranslation       (HTML: themes/lhscans or specific body class)
 *     – Manganelo / Manganato (HTML: `.panel-story-list` + manganelo-specific)
 *     – MangaFox / FanFox   (HTML: meta[name=site] or specific headers)
 *     – Dynasty Reader      (HTML: `dynasty-reader` body class or CSS)
 *     – Cubari reader       (HTML: `cubari` title or meta)
 *     – MangaHub            (HTML: `mangahub-reader` or specific API path)
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
     * API-type key → best template [MangaParserSource] that exists in the
     * library and can be instantiated cleanly.
     *
     * Built once, lazily.
     */
    private val fingerprintTemplates: Map<String, MangaParserSource> by lazy {
        buildFingerprintTemplates()
    }

    /**
     * Preferred parser names per API/CMS fingerprint family.
     * Listed most-specific first; first entry that actually exists in the
     * library wins.
     *
     * Tip: add new candidate names here whenever the upstream library gains
     * a new generic/template parser for a family.
     */
    @Suppress("SpellCheckingInspection")
    private val PREFERRED_TEMPLATES = mapOf(
        // ── WordPress-based ───────────────────────────────────────────────────

        // Madara — most widely deployed manga WordPress theme (500+ sites).
        "madara" to listOf(
            "MADARA_TEMPLATE", "GENERIC_MADARA", "MADARA", "MADARA_CMS",
            "MANGAKAKALOT_TEMPLATE", "WP_MADARA",
        ),
        // MangaThemesia — used by Reaper Scans, Asura Scans, etc.
        "mangathemesia" to listOf(
            "MANGATHEMESIA_TEMPLATE", "GENERIC_MANGATHEMESIA", "MANGATHEMESIA",
            "ASURA_SCANS_TEMPLATE", "REAPERSCANS_TEMPLATE",
        ),
        // MangaStream / WPMangaStream — another widespread WP manga theme.
        "mangastream" to listOf(
            "MANGASTREAM_TEMPLATE", "GENERIC_MANGASTREAM", "MANGASTREAM",
            "WPMANGA_TEMPLATE", "WPMANGASTREAM",
        ),

        // ── Pure JSON API ─────────────────────────────────────────────────────

        "mangadex" to listOf("MANGADEX", "MANGADEX_TEST", "MANGADEX_ORG"),
        "comick"   to listOf("COMICK", "COMICK_FUN", "COMICK_IO", "COMICKFUN"),
        "zeroscans" to listOf("ZEROSCANS", "ZERO_SCANS", "ZEROEROSCANS"),
        // Tachidesk / Suwayomi self-hosted manga server.
        "tachidesk" to listOf(
            "TACHIDESK", "SUWAYOMI", "TACHIDESK_SERVER", "SUWAYOMI_SERVER",
        ),
        // Genkan — open-source scanlation group CMS.
        "genkan" to listOf(
            "GENKAN", "GENKAN_TEMPLATE", "LEVIATAN_SCANS_TEMPLATE",
        ),
        // FoolSlide2 — scanlation reader / CMS.
        "foolslide2" to listOf(
            "FOOLSLIDE2", "FOOLSLIDE_TEMPLATE", "FOOLSLIDE2_TEMPLATE",
        ),
        // MangaPark v5 REST API.
        "mangapark" to listOf(
            "MANGAPARK", "MANGAPARK3", "MANGAPARK4", "MANGAPARK5", "MANGAPARK_V5",
        ),

        // ── HTML/JS-based ─────────────────────────────────────────────────────

        // Guya — fan-TL reader (also used as Cubari base).
        "guya" to listOf("GUYA", "GUYA_MOE", "CUBARI"),
        // MangaSee / MangaLife — JS-driven catalogue.
        "mangasee" to listOf("MANGASEE", "MANGALIFE", "MANGASEE123", "MANGALIFE123"),
        // MangaFire — multi-language aggregator.
        "mangafire" to listOf("MANGAFIRE", "MANGA_FIRE"),
        // LHTranslation / LHScans / MangaDNA.
        "lhtranslation" to listOf(
            "LHTRANSLATION", "LHSCANS", "MANGADNA", "LH_TRANSLATION", "LHTRANS",
        ),
        // Manganelo / Manganato / MangaKakalot family.
        "manganelo" to listOf(
            "MANGANELO", "MANGANATO", "MANGAKAKALOT", "READMANGANATO",
            "MANGANELO_TEMPLATE",
        ),
        // MangaFox / FanFox.
        "mangafox" to listOf("MANGAFOX", "FANFOX", "MANGA_FOX"),
        // Dynasty Reader — scanlation reader.
        "dynasty" to listOf("DYNASTY_READER", "DYNASTY", "DYNASTYSCANS"),
        // Cubari (Guya fork with extra features).
        "cubari" to listOf("CUBARI", "CUBARI_MANGO"),
        // MangaHub.
        "mangahub" to listOf("MANGAHUB", "MANGA_HUB", "MANGAHUB_IO"),
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

        // Step 2: API / HTML fingerprinting
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
                // Pick the first candidate name that actually exists in the library
                // and can be instantiated cleanly.
                val template = candidates.firstNotNullOfOrNull { name: String ->
                    byName[name]?.takeIf { src ->
                        runCatching { loaderContext.newParserInstance(src) }.isSuccess
                    }
                }
                if (template != null) put(apiType, template)
            }
        }
    }

    // ── Master fingerprint dispatcher ─────────────────────────────────────────

    private fun probeFingerprint(baseUrl: String): MangaParserSource? {
        // ── WordPress-based (checked first — most common by far) ──────────────
        if (probeMadara(baseUrl))        return fingerprintTemplates["madara"]
        if (probeMangaThemesia(baseUrl)) return fingerprintTemplates["mangathemesia"]
        if (probeMangaStream(baseUrl))   return fingerprintTemplates["mangastream"]

        // ── Pure JSON APIs (cheap, no HTML parsing needed) ────────────────────
        if (probeMangaDex(baseUrl))      return fingerprintTemplates["mangadex"]
        if (probeComicK(baseUrl))        return fingerprintTemplates["comick"]
        if (probeZeroScans(baseUrl))     return fingerprintTemplates["zeroscans"]
        if (probeTachidesk(baseUrl))     return fingerprintTemplates["tachidesk"]
        if (probeGenkan(baseUrl))        return fingerprintTemplates["genkan"]
        if (probeFoolSlide2(baseUrl))    return fingerprintTemplates["foolslide2"]
        if (probeMangaPark(baseUrl))     return fingerprintTemplates["mangapark"]

        // ── HTML / JS catalogue-style sites (fetch homepage) ─────────────────
        // Fetch once, share across remaining probes to avoid hammering the server.
        val html = httpGetHtml(baseUrl)
        if (html != null) {
            if (probeGuya(baseUrl))              return fingerprintTemplates["guya"]
            if (probeMangaSee(html))             return fingerprintTemplates["mangasee"]
            if (probeMangaFire(html))            return fingerprintTemplates["mangafire"]
            if (probeLhTranslation(html))        return fingerprintTemplates["lhtranslation"]
            if (probeManganelo(html))            return fingerprintTemplates["manganelo"]
            if (probeMangaFox(html))             return fingerprintTemplates["mangafox"]
            if (probeDynastyReader(html))        return fingerprintTemplates["dynasty"]
            if (probeCubari(html))               return fingerprintTemplates["cubari"]
            if (probeMangaHub(html, baseUrl))    return fingerprintTemplates["mangahub"]
        }

        return null
    }

    // ── WordPress / Madara ────────────────────────────────────────────────────

    /**
     * WordPress Madara theme:
     * `GET /wp-json/` → JSON whose body contains "madara" AND "wp/v2".
     * Fallback: homepage HTML contains Madara theme/plugin paths.
     */
    private fun probeMadara(base: String): Boolean = runCatching {
        val body = httpGet("$base/wp-json/") ?: return@runCatching false
        if (body.contains("madara", ignoreCase = true) &&
            body.contains("wp/v2", ignoreCase = true)
        ) return@runCatching true
        val html = httpGetHtml(base) ?: return@runCatching false
        html.contains("wp-content/themes/madara", ignoreCase = true) ||
            html.contains("/wp-content/plugins/madara-", ignoreCase = true) ||
            html.contains("madara-core", ignoreCase = true)
    }.getOrElse { false }

    /**
     * MangaThemesia WordPress theme (Reaper Scans, Asura Scans, etc.):
     * `GET /wp-json/` → body contains "mangathemesia".
     * Fallback: homepage HTML theme path.
     */
    private fun probeMangaThemesia(base: String): Boolean = runCatching {
        val body = httpGet("$base/wp-json/") ?: return@runCatching false
        if (body.contains("mangathemesia", ignoreCase = true)) return@runCatching true
        val html = httpGetHtml(base) ?: return@runCatching false
        html.contains("wp-content/themes/mangathemesia", ignoreCase = true) ||
            html.contains("mangathemesia", ignoreCase = true)
    }.getOrElse { false }

    /**
     * MangaStream / WPMangaStream WordPress theme:
     * Homepage HTML contains theme directory markers or WP-manga post type paths.
     * Also detects `.listupd`, `.komiklist` layout classes used by this theme family.
     */
    private fun probeMangaStream(base: String): Boolean = runCatching {
        val html = httpGetHtml(base) ?: return@runCatching false
        html.contains("wp-content/themes/mangastream", ignoreCase = true) ||
            html.contains("wp-content/themes/wpmanga", ignoreCase = true) ||
            (html.contains("wp-manga", ignoreCase = true) &&
                (html.contains("class=\"listupd\"", ignoreCase = true) ||
                    html.contains("class=\"komiklist\"", ignoreCase = true)))
    }.getOrElse { false }

    // ── Pure JSON APIs ────────────────────────────────────────────────────────

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
     * ComicK REST API:
     * `GET /comic?page=1&limit=1` → `{ "data": [...], "total": N }`
     */
    private fun probeComicK(base: String): Boolean = runCatching {
        val body = httpGet("$base/comic?page=1&limit=1") ?: return@runCatching false
        val json = JSONObject(body)
        json.has("data") && json.has("total")
    }.getOrElse { false }

    /**
     * ZeroScans API:
     * `GET /comics?page=1&per_page=1` → `{ "data": [{ "slug": "..." }] }`
     */
    private fun probeZeroScans(base: String): Boolean = runCatching {
        val body = httpGet("$base/comics?page=1&per_page=1") ?: return@runCatching false
        val json = JSONObject(body)
        val data: JSONArray = json.optJSONArray("data") ?: return@runCatching false
        data.length() > 0 && data.optJSONObject(0)?.has("slug") == true
    }.getOrElse { false }

    /**
     * Tachidesk / Suwayomi self-hosted server:
     * `GET /api/v1/settings` → JSON with `ip` field (server configuration endpoint).
     * Secondary check: `GET /api/v1/manga/list?pageNum=0` returns `hasNextPage`.
     */
    private fun probeTachidesk(base: String): Boolean = runCatching {
        val body = httpGet("$base/api/v1/settings") ?: return@runCatching false
        val json = JSONObject(body)
        if (json.has("ip") || json.has("serverPort")) return@runCatching true
        // Alternate endpoint for older versions
        val body2 = httpGet("$base/api/v1/manga/list?pageNum=0") ?: return@runCatching false
        JSONObject(body2).has("hasNextPage")
    }.getOrElse { false }

    /**
     * Genkan open-source scanlation CMS:
     * `GET /api/chapter_groups?per_page=1` → JSON with `data` array of chapter groups.
     * Each entry has `"chapters"` inside it.
     */
    private fun probeGenkan(base: String): Boolean = runCatching {
        val body = httpGet("$base/api/chapter_groups?per_page=1") ?: return@runCatching false
        val json = JSONObject(body)
        val data: JSONArray = json.optJSONArray("data") ?: return@runCatching false
        data.length() > 0
    }.getOrElse { false }

    /**
     * FoolSlide2 scanlation reader / CMS:
     * `GET /api/reader/chapter_groups` or `GET /fs_api/reader/chapter_groups`
     * → JSON with `"chapter_groups"` key.
     * Also checks `/api/reader/` which returns HTML with FoolSlide markers.
     */
    private fun probeFoolSlide2(base: String): Boolean = runCatching {
        val candidates = listOf(
            "$base/api/reader/chapter_groups",
            "$base/fs_api/reader/chapter_groups",
            "$base/reader/api/reader/chapter_groups",
        )
        for (url in candidates) {
            val body = httpGet(url) ?: continue
            if (body.contains("chapter_groups", ignoreCase = true) ||
                body.contains("foolslide", ignoreCase = true)
            ) return@runCatching true
        }
        // Fallback: HTML reader page
        val html = httpGetHtml("$base/reader") ?: return@runCatching false
        html.contains("foolslide", ignoreCase = true) ||
            html.contains("fs_reader", ignoreCase = true)
    }.getOrElse { false }

    /**
     * MangaPark v5 REST API:
     * `GET /api/v5/search/comic?limit=1` → JSON with `"data": { "items": [...] }`.
     * Older v3/v4 used a different path; secondary probe covers those.
     */
    private fun probeMangaPark(base: String): Boolean = runCatching {
        // v5 API
        val body5 = httpGet("$base/api/v5/search/comic?limit=1")
        if (body5 != null) {
            val json = JSONObject(body5)
            if (json.has("data")) return@runCatching true
        }
        // v3/v4 GraphQL hint: the homepage HTML contains "__NEXT_DATA__" and
        // a specific "mangapark" brand string.
        val html = httpGetHtml(base) ?: return@runCatching false
        html.contains("__NEXT_DATA__") &&
            html.contains("mangapark", ignoreCase = true)
    }.getOrElse { false }

    // ── Guya / fan-TL reader ──────────────────────────────────────────────────

    /**
     * Guya reader and fan-TL forks (also covers Cubari when accessed as a proxy):
     * `GET /api/series/` → JSON object whose values each contain a `"chapters"` key.
     */
    private fun probeGuya(base: String): Boolean = runCatching {
        val body = httpGet("$base/api/series/") ?: return@runCatching false
        val json = JSONObject(body)
        val firstKey = json.keys().asSequence().firstOrNull() ?: return@runCatching false
        json.optJSONObject(firstKey)?.has("chapters") == true
    }.getOrElse { false }

    // ── HTML / JS catalogue sites ─────────────────────────────────────────────

    /**
     * MangaSee / MangaLife (JavaScript-driven catalogue):
     * Homepage HTML embeds `vm.Directory =` as a JS variable containing manga data.
     */
    private fun probeMangaSee(html: String): Boolean =
        html.contains("vm.Directory =", ignoreCase = false) ||
            html.contains("vm.Chapters =", ignoreCase = false)

    /**
     * MangaFire aggregator:
     * Homepage HTML contains the MangaFire brand string and a specific JS bundle path.
     */
    private fun probeMangaFire(html: String): Boolean =
        html.contains("mangafire", ignoreCase = true) &&
            (html.contains("site:mangafire", ignoreCase = true) ||
                html.contains("\"mangafire\"", ignoreCase = true) ||
                html.contains("/manga/filter", ignoreCase = true))

    /**
     * LHTranslation / LHScans / MangaDNA:
     * Homepage HTML contains theme directory `lhscans` or body class `lhscans`.
     */
    private fun probeLhTranslation(html: String): Boolean =
        html.contains("wp-content/themes/lhscans", ignoreCase = true) ||
            html.contains("class=\"lhscans", ignoreCase = true) ||
            html.contains("lhtranslation", ignoreCase = true) ||
            html.contains("mangadna", ignoreCase = true)

    /**
     * Manganelo / Manganato / MangaKakalot family:
     * Unique CSS class `.panel-story-list` combined with the site-family brand name.
     */
    private fun probeManganelo(html: String): Boolean =
        (html.contains("panel-story-list", ignoreCase = true) ||
            html.contains("panel_story_list", ignoreCase = true)) &&
            (html.contains("manganelo", ignoreCase = true) ||
                html.contains("manganato", ignoreCase = true) ||
                html.contains("mangakakalot", ignoreCase = true))

    /**
     * MangaFox / FanFox:
     * Homepage HTML contains "mangafox" or "fanfox" brand strings in script/link tags.
     */
    private fun probeMangaFox(html: String): Boolean =
        html.contains("mangafox.me", ignoreCase = true) ||
            html.contains("fanfox.net", ignoreCase = true) ||
            (html.contains("mangafox", ignoreCase = true) &&
                html.contains("cdn.fanfox", ignoreCase = true))

    /**
     * Dynasty Reader — scanlation reader used by many doujin groups:
     * Body class `dynasty-reader` or specific CSS path.
     */
    private fun probeDynastyReader(html: String): Boolean =
        html.contains("dynasty-reader", ignoreCase = true) ||
            html.contains("dynasty_reader", ignoreCase = true) ||
            html.contains("/assets/dynasty-reader", ignoreCase = true)

    /**
     * Cubari reader (Guya-based proxy/reader):
     * Page title or meta contains "cubari" or the HTML links to cubari-specific assets.
     */
    private fun probeCubari(html: String): Boolean =
        html.contains("cubari", ignoreCase = true) &&
            (html.contains("<title>Cubari", ignoreCase = true) ||
                html.contains("cubari-priv", ignoreCase = true) ||
                html.contains("/cubari/", ignoreCase = true))

    /**
     * MangaHub reader:
     * Specific CDN domain `img.mghubcdn.com` or API prefix `/graphql` with MangaHub schema.
     */
    private fun probeMangaHub(html: String, base: String): Boolean {
        if (html.contains("mghubcdn.com", ignoreCase = true) ||
            html.contains("mangahub", ignoreCase = true)
        ) return true
        // Secondary: GraphQL endpoint check
        return runCatching {
            val body = httpGet("$base/graphql") ?: return@runCatching false
            body.contains("manga", ignoreCase = true) &&
                body.contains("errors", ignoreCase = true)
        }.getOrElse { false }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private val probeClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** Fetch as JSON (Accept: application/json). Returns null on any error. */
    private fun httpGet(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        probeClient.newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (_: Exception) {
        null
    }

    /** Fetch as HTML (Accept: text/html). Returns null on any error. */
    private fun httpGetHtml(url: String): String? = try {
        val req = Request.Builder()
            .url(url)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("User-Agent", USER_AGENT)
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

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Android 14; Mobile) Tsuki/1.0"
    }
}
