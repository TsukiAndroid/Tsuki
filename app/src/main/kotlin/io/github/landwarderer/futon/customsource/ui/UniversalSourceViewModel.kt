package io.github.landwarderer.futon.customsource.ui

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.landwarderer.futon.browser.detection.MangaSiteDetector
import io.github.landwarderer.futon.customsource.data.CustomSourcesRepository
import io.github.landwarderer.futon.customsource.data.ParserTemplateRepository
import io.github.landwarderer.futon.customsource.data.SiteAutoDetector
import io.github.landwarderer.futon.customsource.domain.CustomSource
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import io.github.landwarderer.futon.customsource.domain.ParserTemplate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

/**
 * ViewModel for [UniversalSourceActivity].
 *
 * Auto-detect: [autoDetect] fetches the target site's HTML, fingerprints its CMS
 * theme via [SiteAutoDetector], and emits [AutoDetectState.Done] with pre-filled
 * form values AND the resolved [CustomSourceType].
 *
 * Create: [create] maps the fingerprinted CMS theme directly to one of the
 * battle-tested theme parsers already in [CustomMangaRepository] — Madara,
 * MangaThemesia, MangaStream, Keyoapp, MadTheme, Mmrcms, etc. — so the source
 * works exactly like a built-in source with no guesswork.
 *
 * Only when the site's CMS cannot be identified does it fall back to
 * [CustomSourceType.CUSTOM_TEMPLATE] (driven by [TemplateHtmlParser]), which
 * itself now uses cascading selectors, chapterData JS parsing, logo filtering,
 * and correct Referer/UA headers so even unknown sites usually work.
 */
@HiltViewModel
class UniversalSourceViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val parserTemplateRepository: ParserTemplateRepository,
    private val customSourcesRepository: CustomSourcesRepository,
    private val mangaSiteDetector: MangaSiteDetector,
) : ViewModel() {

    // ── Create result ─────────────────────────────────────────────────────────

    sealed interface Result {
        object Idle : Result
        data class Error(val message: String) : Result
        data class Success(val name: String, val parserLabel: String) : Result
    }

    private val _result = MutableStateFlow<Result>(Result.Idle)
    val result: StateFlow<Result> = _result.asStateFlow()

    fun resetResult() {
        _result.value = Result.Idle
    }

    // ── Auto-detect state ─────────────────────────────────────────────────────

    sealed interface AutoDetectState {
        object Idle : AutoDetectState
        object Loading : AutoDetectState
        data class Done(val fields: SiteAutoDetector.DetectedFields) : AutoDetectState
        data class Error(val message: String) : AutoDetectState
    }

    private val _autoDetectState = MutableStateFlow<AutoDetectState>(AutoDetectState.Idle)

    // ── Progress step (real-time sub-steps during auto-detect) ──────────────
    private val _progressStep = MutableStateFlow("")
    /** Emits the current work step during [autoDetect]; empty string otherwise. */
    val progressStep: StateFlow<String> = _progressStep.asStateFlow()
    val autoDetectState: StateFlow<AutoDetectState> = _autoDetectState.asStateFlow()

    private var lastDetectedPaginationType: String = "page"
    private var lastDetectedCmsType: SiteAutoDetector.CmsType = SiteAutoDetector.CmsType.UNKNOWN

    /**
     * Fetches [url]'s HTML, fingerprints its CMS theme via [SiteAutoDetector],
     * and emits [AutoDetectState.Done] with pre-filled form values on success.
     */
    fun autoDetect(url: String) {
        val trimUrl = url.trim()
        if (trimUrl.isEmpty() ||
            (!trimUrl.startsWith("http://") && !trimUrl.startsWith("https://"))
        ) {
            _autoDetectState.value = AutoDetectState.Error(
                "Enter the site URL (starting with https://) before auto-detecting."
            )
            return
        }
        viewModelScope.launch {
            _autoDetectState.value = AutoDetectState.Loading
            _progressStep.value = ""

            // Fast path: if the universal passive detector (see MangaSiteDetector) already
            // built up a high-confidence session for this domain from earlier browsing --
            // e.g. the user was just looking at it in the in-app browser -- reuse those
            // selectors instead of re-running the full fetch + CMS-fingerprint pipeline.
            val warmDomain = runCatching { java.net.URI(trimUrl).host }.getOrNull()
            val warmSession = warmDomain?.let { mangaSiteDetector.sessionFor(it) }
            if (warmSession != null && warmSession.mangaListDetected) {
                val list = warmSession.mangaListSelectors!!
                val detail = warmSession.mangaDetailSelectors
                lastDetectedPaginationType = "page"
                lastDetectedCmsType = SiteAutoDetector.CmsType.UNKNOWN
                _progressStep.value = ""
                _autoDetectState.value = AutoDetectState.Done(
                    SiteAutoDetector.DetectedFields(
                        siteName = warmSession.siteTitle,
                        cardSelector = list.itemSelector,
                        titleSelector = list.titleSelector,
                        coverSelector = list.coverSelector,
                        detailTitle = detail?.titleSelector.orEmpty(),
                        description = detail?.descriptionSelector.orEmpty(),
                        chapterSelector = detail?.chapterListSelector.orEmpty(),
                        pageImageSelector = warmSession.pageImageSelectors?.imageSelector.orEmpty(),
                        fieldConfidence = mapOf(
                            "cardSelector" to SiteAutoDetector.Confidence.MEDIUM,
                            "titleSelector" to SiteAutoDetector.Confidence.MEDIUM,
                        ),
                        cmsType = SiteAutoDetector.CmsType.UNKNOWN,
                    ),
                )
                return@launch
            }

            runCatching {
                SiteAutoDetector(
                    context      = appContext,
                    geminiApiKey = getGeminiApiKey(),
                    onProgress   = { step -> _progressStep.value = step },
                ).detect(trimUrl)
            }
                .onSuccess { fields ->
                    _progressStep.value = ""
                    lastDetectedPaginationType = fields.paginationType
                    lastDetectedCmsType = fields.cmsType
                    _autoDetectState.value = AutoDetectState.Done(fields)
                }
                .onFailure { e ->
                    _progressStep.value = ""
                    _autoDetectState.value = AutoDetectState.Error(
                        e.message ?: "Detection failed — please fill in selectors manually."
                    )
                }
        }
    }

    fun resetAutoDetect() {
        _autoDetectState.value = AutoDetectState.Idle
    }

    // ── Create source ─────────────────────────────────────────────────────────

    fun create(
        name: String,
        baseUrl: String,
        listPath: String,
        searchPath: String,
        cardSelector: String,
        titleSelector: String,
        coverSelector: String,
        detailTitle: String,
        description: String,
        chapterSelector: String,
        pageImageSelector: String,
    ) {
        val trimName = name.trim()
        val trimUrl  = baseUrl.trim().trimEnd('/')

        if (trimName.isEmpty()) {
            _result.value = Result.Error("Site name is required.")
            return
        }
        if (trimUrl.isEmpty() || (!trimUrl.startsWith("http://") && !trimUrl.startsWith("https://"))) {
            _result.value = Result.Error("Base URL must start with https://")
            return
        }

        // Map fingerprinted CMS theme → proven battle-tested parser.
        // Only fall back to CUSTOM_TEMPLATE when the CMS is completely unknown.
        val resolvedType = cmsTypeToSourceType(lastDetectedCmsType)
        val needsTemplate = resolvedType == CustomSourceType.CUSTOM_TEMPLATE

        // For the generic template fallback, page image selector is required
        // because TemplateHtmlParser needs it. Proven parsers don't use it.
        if (needsTemplate) {
            val trimPageImg = pageImageSelector.trim()
            if (trimPageImg.isEmpty()) {
                _result.value = Result.Error(
                    "Page image selector is required for this site type — auto-detect couldn't " +
                    "identify a known CMS theme, so you need to provide the CSS selector for " +
                    "chapter page images manually."
                )
                return
            }
        }

        val timestamp = System.currentTimeMillis()

        // Only save a ParserTemplate when using the template-driven fallback parser.
        // Proven theme parsers (Madara, MangaThemesia, etc.) use hardcoded selectors
        // and never consult the template JSON, so there is no point persisting it.
        if (needsTemplate) {
            parserTemplateRepository.add(
                ParserTemplate(
                    id        = timestamp,
                    name      = trimName,
                    version   = "1.0",
                    type      = "html",
                    rawJson   = buildTemplateJson(
                        name           = trimName,
                        listPath       = listPath.trim().ifEmpty { "/" },
                        searchPath     = searchPath.trim(),
                        cardSelector   = cardSelector.trim(),
                        titleSelector  = titleSelector.trim(),
                        coverSelector  = coverSelector.trim(),
                        detailTitle    = detailTitle.trim(),
                        description    = description.trim(),
                        chapterSel     = chapterSelector.trim(),
                        pageImageSel   = pageImageSelector.trim(),
                        paginationType = lastDetectedPaginationType,
                    ),
                    isEnabled = true,
                ),
            )
        }

        customSourcesRepository.add(
            CustomSource(
                id               = timestamp + 1L,
                name             = trimName,
                baseUrl          = trimUrl,
                type             = resolvedType,
                parserSourceName = if (needsTemplate) trimName else null,
                isEnabled        = true,
            ),
        )

        _result.value = Result.Success(trimName, resolvedType.label)
    }

    // ── CMS → parser type mapping ─────────────────────────────────────────────

    /**
     * Maps a fingerprinted [SiteAutoDetector.CmsType] to the [CustomSourceType]
     * that routes to the proven, battle-tested parser for that theme.
     *
     * Every mapping here corresponds to a fully-implemented parser in
     * [CustomMangaRepository] that handles manga list, detail, chapters, and pages
     * correctly — including hotlink-protection headers, lazy-load image attributes,
     * and JS-rendered content where applicable.
     *
     * [CmsType.WORDPRESS_GENERIC] and [CmsType.UNKNOWN] fall back to
     * [CustomSourceType.CUSTOM_TEMPLATE] which uses the improved [TemplateHtmlParser].
     */
    private fun cmsTypeToSourceType(cms: SiteAutoDetector.CmsType): CustomSourceType =
        when (cms) {
            SiteAutoDetector.CmsType.MADARA          -> CustomSourceType.MADARA
            SiteAutoDetector.CmsType.MANGA_THEMESIA  -> CustomSourceType.MANGATHEMESIA
            SiteAutoDetector.CmsType.MANGA_STREAM    -> CustomSourceType.MANGASTREAM
            SiteAutoDetector.CmsType.KEYOAPP         -> CustomSourceType.KEYOAPP
            SiteAutoDetector.CmsType.MAD_THEME       -> CustomSourceType.MADTHEME
            SiteAutoDetector.CmsType.MMRCMS          -> CustomSourceType.MMRCMS
            SiteAutoDetector.CmsType.WORDPRESS_GENERIC,
            SiteAutoDetector.CmsType.UNKNOWN         -> CustomSourceType.CUSTOM_TEMPLATE
        }

    // ── Gemini API key helper ──────────────────────────────────────────────
    /**
     * Reads the Gemini API key from shared preferences.
     * The key is stored by SettingsActivity under "gemini_api_key".
     */
    private fun getGeminiApiKey(): String? {
        val prefs = appContext.getSharedPreferences("futon_prefs", android.content.Context.MODE_PRIVATE)
        return prefs.getString("gemini_api_key", null)?.takeIf { it.isNotBlank() }
    }

    // ── Template JSON (used only for CUSTOM_TEMPLATE fallback) ────────────────

    private fun buildTemplateJson(
        name: String,
        listPath: String,
        searchPath: String,
        cardSelector: String,
        titleSelector: String,
        coverSelector: String,
        detailTitle: String,
        description: String,
        chapterSel: String,
        pageImageSel: String,
        paginationType: String = "page",
    ): String {
        val root = JSONObject()
        root.put("name", name)
        root.put("version", "1.0")
        root.put("type", "html")

        val resolvedPagination = when {
            paginationType != "page" -> paginationType
            listPath.trim('/').isNotEmpty() && !listPath.contains('?') &&
                listPath.trim('/').none { it == '/' } -> "path"
            else -> "page"
        }

        val mangaList = JSONObject()
        mangaList.put("endpoint", listPath)
        mangaList.put("pagination", resolvedPagination)
        mangaList.put("pageParam", "page")
        if (cardSelector.isNotEmpty())  mangaList.put("itemSelector",  cardSelector)
        if (titleSelector.isNotEmpty()) mangaList.put("titleSelector", titleSelector)
        if (coverSelector.isNotEmpty()) mangaList.put("coverSelector", coverSelector)
        if (searchPath.isNotEmpty()) {
            val rawParam  = if (searchPath.contains("?")) searchPath.substringAfterLast("?") else ""
            val paramName = if (rawParam.contains("=")) rawParam.substringBefore("=") else "s"
            val endpoint  = searchPath.substringBefore("?").ifEmpty { searchPath }
            mangaList.put("searchEndpoint", endpoint)
            mangaList.put("searchParam", paramName)
        }
        root.put("mangaList", mangaList)

        val mangaDetail = JSONObject()
        if (detailTitle.isNotEmpty()) mangaDetail.put("titleSelector",       detailTitle)
        if (description.isNotEmpty()) mangaDetail.put("descriptionSelector", description)
        if (coverSelector.isNotEmpty()) mangaDetail.put("coverSelector",     coverSelector)
        root.put("mangaDetail", mangaDetail)

        val chapterList = JSONObject()
        if (chapterSel.isNotEmpty()) chapterList.put("selector", chapterSel)
        chapterList.put("titleSelector", "a")
        chapterList.put("linkSelector",  "a")
        root.put("chapterList", chapterList)

        val pageList = JSONObject()
        pageList.put("imageSelector", pageImageSel)
        root.put("pageList", pageList)

        return root.toString(2)
    }
}
