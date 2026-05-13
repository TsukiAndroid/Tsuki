package io.github.landwarderer.futon.customsource.ui

import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.landwarderer.futon.core.parser.KotatsuParserMatcher
import io.github.landwarderer.futon.customsource.data.CmsTypeDetector
import io.github.landwarderer.futon.customsource.data.CustomSourcesRepository
import io.github.landwarderer.futon.customsource.data.ParserTemplateRepository
import io.github.landwarderer.futon.customsource.domain.CustomSource
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import io.github.landwarderer.futon.customsource.domain.ParserTemplate
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltViewModel
class CustomSourceViewModel @Inject constructor(
    private val repository: CustomSourcesRepository,
    private val kotatsuParserMatcher: KotatsuParserMatcher,
) : ViewModel() {

    val sources: StateFlow<List<CustomSource>> = repository.sources

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    /** Look up a saved source by its id (used to pre-fill the edit sheet). */
    fun findById(id: Long): CustomSource? = repository.findById(id)

    /** Add a source with an already-known [type]. Rejects duplicates. */
    fun addSource(name: String, url: String, type: CustomSourceType, description: String) {
        viewModelScope.launch {
            val normalized = normalizeUrl(url)
            if (normalized == null) {
                _uiState.value = UiState.Error("Please enter a valid website URL (e.g. example.com)")
                return@launch
            }
            val existing = repository.findByUrl(normalized)
            if (existing != null) {
                _uiState.value = UiState.Error(
                    "Already added as \"${existing.displayName}\". Edit it from the list instead."
                )
                return@launch
            }
            // parserSourceName is only meaningful for KOTATSU_PARSER, which cannot be
            // manually selected — so it is always null on the manual-add path.
            val source = CustomSource(
                id = CustomSourcesRepository.generateId(),
                name = name.trim().ifBlank { hostFromUrl(normalized) ?: normalized },
                baseUrl = normalized,
                type = type,
                parserSourceName = null,
                description = description.trim().takeIf { it.isNotEmpty() },
            )
            repository.add(source)
            _uiState.value = UiState.SourceAdded(source)
            fetchAndStoreFavicon(source)
        }
    }

    /**
     * Auto-detects the CMS type of [url] and saves a new source with that type.
     * Rejects duplicate URLs.
     *
     * Detection order:
     *  1. Check [KotatsuParserMatcher] — if the domain matches a built-in parser,
     *     save as [CustomSourceType.KOTATSU_PARSER] so the factory routes it to
     *     [ParserMangaRepository] giving full inbuilt-source quality.
     *  2. Fall back to [CmsTypeDetector] HTML fingerprinting.
     *
     * Emits [UiState.Detecting] while the probe is in flight.
     */
    fun detectAndAddSource(name: String, url: String, description: String) {
        viewModelScope.launch {
            val normalized = normalizeUrl(url)
            if (normalized == null) {
                _uiState.value = UiState.Error("Please enter a valid website URL (e.g. example.com)")
                return@launch
            }
            val existing = repository.findByUrl(normalized)
            if (existing != null) {
                _uiState.value = UiState.Error(
                    "Already added as \"${existing.displayName}\". Edit it from the list instead."
                )
                return@launch
            }
            _uiState.value = UiState.Detecting

            val (detectedType, parserSourceName, reachable) = withContext(Dispatchers.IO) {
                runDetectionPipeline(normalized)
            }

            val source = CustomSource(
                id = CustomSourcesRepository.generateId(),
                // Always use what the user typed; fall back to the site hostname.
                // Never inject the parser's internal title — the user's chosen name
                // is what appears everywhere in the app.
                name = name.trim().ifBlank { hostFromUrl(normalized) ?: normalized },
                baseUrl = normalized,
                type = detectedType,
                parserSourceName = parserSourceName,
                description = description.trim().takeIf { it.isNotEmpty() },
            )
            repository.add(source)
            _uiState.value = UiState.SourceAdded(source, detectedType, parserSourceName, reachable)
            fetchAndStoreFavicon(source)
        }
    }

    /**
     * Save edits to an existing source.
     * Preserves [createdAt] and [iconUrl]; re-fetches the favicon if the URL changed.
     *
     * [parserSourceName] is only valid when [type] == [CustomSourceType.KOTATSU_PARSER].
     * For every other type it is explicitly cleared so stale data never lingers.
     */
    fun updateSource(
        id: Long,
        name: String,
        url: String,
        type: CustomSourceType,
        description: String,
    ) {
        viewModelScope.launch {
            val existing = repository.findById(id) ?: run {
                _uiState.value = UiState.Error("Source not found")
                return@launch
            }
            val normalized = normalizeUrl(url)
            if (normalized == null) {
                _uiState.value = UiState.Error("Please enter a valid website URL (e.g. example.com)")
                return@launch
            }
            // Detect duplicate only when the URL actually changed
            if (normalized != existing.baseUrl) {
                val duplicate = repository.findByUrl(normalized)
                if (duplicate != null && duplicate.id != id) {
                    _uiState.value = UiState.Error(
                        "\"${duplicate.displayName}\" already uses that URL."
                    )
                    return@launch
                }
            }
            // Clear parserSourceName when the type is not KOTATSU_PARSER — it would
            // point to the wrong (or old) parser and cause silent misfetches.
            val resolvedParserName = if (type == CustomSourceType.KOTATSU_PARSER) {
                existing.parserSourceName
            } else {
                null
            }
            val updated = existing.copy(
                name = name.trim().ifBlank { hostFromUrl(normalized) ?: normalized },
                baseUrl = normalized,
                type = type,
                parserSourceName = resolvedParserName,
                description = description.trim().takeIf { it.isNotEmpty() },
            )
            repository.update(updated)
            _uiState.value = UiState.SourceUpdated(updated)
            if (normalized != existing.baseUrl) fetchAndStoreFavicon(updated)
        }
    }

    /**
     * Probes [url] using the full two-stage detection pipeline (Kotatsu matcher
     * first, then HTML fingerprinting) and calls [onDetected] on the main thread
     * with the result. Emits [UiState.Detecting] while the probe runs.
     *
     * Also persists the updated [CustomSourceType] and [CustomSource.parserSourceName]
     * into the stored source so a subsequent "Save" doesn't lose the result.
     *
     * Used by the edit sheet's "Re-detect" button.
     */
    fun redetectType(sourceId: Long, url: String, onDetected: (CustomSourceType) -> Unit) {
        viewModelScope.launch {
            val normalized = normalizeUrl(url)
            if (normalized == null) {
                _uiState.value = UiState.Error("Please enter a valid website URL (e.g. example.com)")
                return@launch
            }
            _uiState.value = UiState.Detecting

            val (detectedType, parserSourceName, _) = withContext(Dispatchers.IO) {
                runDetectionPipeline(normalized)
            }

            // Persist the detection result immediately so the edit sheet's "Save"
            // button doesn't have to re-run detection or lose parserSourceName.
            repository.findById(sourceId)?.let { existing ->
                repository.update(
                    existing.copy(
                        type = detectedType,
                        parserSourceName = when (detectedType) {
                            CustomSourceType.KOTATSU_PARSER,
                            CustomSourceType.CUSTOM_TEMPLATE -> parserSourceName
                            else -> null
                        },
                    )
                )
            }

            _uiState.value = UiState.Idle
            onDetected(detectedType)
        }
    }

    /** Flip the enabled/disabled flag of a source. */
    fun toggleEnabled(id: Long) {
        viewModelScope.launch {
            val source = repository.findById(id) ?: return@launch
            repository.setEnabled(id, !source.isEnabled)
        }
    }

    fun removeSource(id: Long) {
        viewModelScope.launch { repository.remove(id) }
    }

    fun resetState() {
        _uiState.value = UiState.Idle
    }

    fun exportSourcesJson(): String = repository.exportJson()

    fun importSourcesJson(json: String): Int = repository.importJson(json)

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Runs the three-stage detection pipeline on [normalizedUrl]:
     *  1. [KotatsuParserMatcher] — built-in parser domain cache (highest priority)
     *  2. Template matching — checks imported [ParserTemplate]s using two strategies:
     *       a. `fingerprints` — HTML substrings matched against the homepage (one fetch shared)
     *       b. `endpointProbes` — API paths probed individually (fallback for API-only sites)
     *     Templates are checked before generic CMS detection so user rules take priority.
     *  3. [CmsTypeDetector] — generic HTML fingerprinting (49 built-in CMS checks)
     *
     * Also performs a lightweight reachability probe; [Triple.third] is false
     * if the site returned an error or timed out entirely.
     *
     * Must be called from an IO coroutine.
     */
    private fun runDetectionPipeline(normalizedUrl: String): Triple<CustomSourceType, String?, Boolean> {
        // Step 1: check if a Kotatsu parser already covers this domain
        val matchedParser = runCatching { kotatsuParserMatcher.findForUrl(normalizedUrl) }.getOrNull()
        if (matchedParser != null) {
            return Triple(CustomSourceType.KOTATSU_PARSER, matchedParser.name, true)
        }

        // Step 1.5: check if any imported template's fingerprints match this site.
        // No-op when the user has not imported any templates (peekAll returns empty).
        val templateMatch = runCatching {
            matchTemplateFingerprints(normalizedUrl, ParserTemplateRepository.peekAll())
        }.getOrNull()
        if (templateMatch != null) {
            return Triple(CustomSourceType.CUSTOM_TEMPLATE, templateMatch, true)
        }

        // Step 2: HTML fingerprinting — capture reachability from the result
        val cms = runCatching { CmsTypeDetector.detect(normalizedUrl) }.getOrElse { null }
        val reachable = cms != null
        return Triple(cms ?: CustomSourceType.WEBVIEW, null, reachable)
    }

    /**
     * Returns the name of the first imported [ParserTemplate] whose optional
     * `fingerprints` JSON array (list of HTML substrings) all appear in the
     * site's homepage HTML or via endpoint probes, or null if no template matches.
     *
     * Two detection strategies are supported per template (checked in order):
     *
     * **1. HTML fingerprints** — substrings that must all appear in the homepage HTML.
     * The homepage is fetched at most once, shared across all fingerprint-bearing templates.
     * ```json
     * { "fingerprints": ["wp-manga", "my-special-class"] }
     * ```
     *
     * **2. Endpoint probes** — API paths probed with a real HTTP request; used as a
     * fallback when a template has no `fingerprints` (or as the primary strategy for
     * API-driven sites where the homepage carries no CMS markers). Each probe must
     * return a response containing the expected substring.
     * ```json
     * { "endpointProbes": [
     *     { "path": "/api/comics",    "contains": "\"slug\""     },
     *     { "path": "/api/v1/series", "contains": "\"chapters\"" }
     * ] }
     * ```
     * `path` may be a full URL or a root-relative path (prefixed with [url]).
     * All probes must pass for the template to match.
     *
     * Returns null immediately when [templates] is empty (zero network requests made).
     */
    private fun matchTemplateFingerprints(
        url: String,
        templates: List<ParserTemplate>,
    ): String? {
        if (templates.isEmpty()) return null

        // Lazy homepage fetch — shared across all fingerprint checks so the site
        // is probed at most once even when multiple templates declare fingerprints.
        var homepageFetched = false
        var homepageHtml: String? = null
        fun homepage(): String? {
            if (!homepageFetched) {
                homepageHtml = fetchEndpoint(url.trimEnd('/'))
                homepageFetched = true
            }
            return homepageHtml
        }

        for (template in templates) {
            val root = runCatching { JSONObject(template.rawJson) }.getOrNull() ?: continue

            // Strategy 1: HTML fingerprints (homepage markers, no extra requests)
            val fpArr = root.optJSONArray("fingerprints")
            if (fpArr != null && fpArr.length() > 0) {
                val html = homepage() ?: continue   // site unreachable; skip template
                val fingerprints = (0 until fpArr.length()).map { fpArr.getString(it) }
                if (fingerprints.all { marker -> html.contains(marker, ignoreCase = true) }) {
                    return template.name
                }
                // fingerprints present but didn't match — do NOT fall through to probes
                continue
            }

            // Strategy 2: Endpoint probes (one HTTP request per probe entry)
            val probeArr = root.optJSONArray("endpointProbes") ?: continue
            if (probeArr.length() == 0) continue
            val allProbesPass = (0 until probeArr.length()).all { i ->
                val probe = probeArr.optJSONObject(i) ?: return@all false
                val path = probe.optString("path").takeIf { it.isNotEmpty() } ?: return@all false
                val expected = probe.optString("contains").takeIf { it.isNotEmpty() } ?: return@all false
                val probeUrl = if (path.startsWith("http")) path
                               else "${url.trimEnd('/')}$path"
                val body = fetchEndpoint(probeUrl) ?: return@all false
                body.contains(expected, ignoreCase = true)
            }
            if (allProbesPass) return template.name
        }
        return null
    }

    /**
     * Fetches [url] and returns the response body (up to 64 KB), or null on
     * any network or HTTP error. Used for both homepage fingerprinting and
     * endpoint probe checks.
     */
    private fun fetchEndpoint(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url.trimEnd('/'))
            .header("User-Agent", "Tsuki/1.0 (Android)")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()?.take(65_536)
        }
    }.getOrNull()

    private fun normalizeUrl(raw: String): String? {
        var trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return null
        if (!trimmed.startsWith("http://", ignoreCase = true) &&
            !trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed = "https://$trimmed"
        }
        return if (Patterns.WEB_URL.matcher(trimmed).matches()) trimmed else null
    }

    private fun hostFromUrl(url: String): String? = runCatching {
        URI(url).host?.removePrefix("www.")
    }.getOrNull()

    /**
     * Tries multiple favicon sources in order and stores the first one that
     * returns a non-trivially-small image body:
     *  1. Google S2 favicon API (128 px, usually best quality)
     *  2. /favicon.ico (standard location)
     *  3. /apple-touch-icon.png (often higher-res on modern sites)
     *  4. /apple-touch-icon-precomposed.png (older sites)
     */
    private suspend fun fetchAndStoreFavicon(source: CustomSource) {
        val host = hostFromUrl(source.baseUrl) ?: return
        val clean = source.cleanBaseUrl
        val candidates = listOf(
            "https://www.google.com/s2/favicons?domain=$host&sz=128",
            "$clean/favicon.ico",
            "$clean/apple-touch-icon.png",
            "$clean/apple-touch-icon-precomposed.png",
        )
        val iconUrl = withContext(Dispatchers.IO) {
            for (url in candidates) {
                val found = runCatching {
                    val req = Request.Builder().url(url).get().build()
                    httpClient.newCall(req).execute().use { resp ->
                        if (resp.isSuccessful && (resp.body?.contentLength() ?: 0L) > 200L) url
                        else null
                    }
                }.getOrNull()
                if (found != null) return@withContext found
            }
            null
        } ?: return
        repository.update(source.copy(iconUrl = iconUrl))
    }

    // ── UI state ──────────────────────────────────────────────────────────────

    sealed class UiState {
        object Idle : UiState()
        object Detecting : UiState()
        data class Error(val message: String) : UiState()
        data class SourceAdded(
            val source: CustomSource,
            /** Non-null when auto-detect was used. */
            val detectedType: CustomSourceType? = null,
            /** Non-null when detection matched a built-in Kotatsu parser. */
            val parserName: String? = null,
            /** False when the site was unreachable during detection. */
            val siteReachable: Boolean = true,
        ) : UiState()
        data class SourceUpdated(
            val source: CustomSource,
            val detectedType: CustomSourceType? = null,
        ) : UiState()
    }

    companion object {
        private val httpClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }
}
