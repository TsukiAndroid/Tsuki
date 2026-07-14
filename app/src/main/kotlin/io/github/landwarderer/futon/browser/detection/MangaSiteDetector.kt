package io.github.landwarderer.futon.browser.detection

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.landwarderer.futon.core.network.BaseHttpClient
import io.github.landwarderer.futon.customsource.data.CustomSourcesRepository
import io.github.landwarderer.futon.customsource.data.GeminiSelectorAnalyzer
import io.github.landwarderer.futon.customsource.data.ParserTemplateRepository
import io.github.landwarderer.futon.customsource.data.ParserTemplateValidator
import io.github.landwarderer.futon.customsource.domain.CustomSource
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import io.github.landwarderer.futon.customsource.domain.ParserTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates passive universal manga-site detection: feeds page HTML and observed
 * reader-page image URLs into [UniversalPatternDetector], accumulates a per-domain
 * confidence score in [DetectionSessionStore], and (once confidence is high enough)
 * can validate and create a [CustomSource] from what was learned -- with no
 * site-specific rules anywhere in the pipeline.
 *
 * Callers (WebView-hosting screens) drive this by calling [analyzePage] from
 * `onPageFinished` and [recordImageUrl] from `shouldInterceptRequest`, then reading
 * back [DetectionSession.promptLevel] to decide what UI (if any) to show via
 * [MangaSitePrompt]. All page analysis runs on [Dispatchers.IO] and is designed to
 * finish well under the 500ms/page budget -- it operates on HTML that the WebView
 * has already fetched and rendered, not a fresh network round-trip.
 */
@Singleton
class MangaSiteDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    @BaseHttpClient private val httpClient: okhttp3.OkHttpClient,
    private val parserTemplateRepository: ParserTemplateRepository,
    private val customSourcesRepository: CustomSourcesRepository,
) {

    sealed interface CreateResult {
        data class Success(val name: String) : CreateResult
        data class ValidationFailed(val reason: String) : CreateResult
        data class Error(val message: String) : CreateResult
    }

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val geminiAnalyzer = GeminiSelectorAnalyzer()

    /**
     * Analyzes a freshly-loaded page's HTML for list/detail/search patterns and
     * domain/URL keyword signals, updating (and returning) that domain's session.
     * Returns `null` if the domain is on the "never for this site" list or the URL
     * can't be parsed.
     */
    suspend fun analyzePage(url: String, html: String): DetectionSession? = withContext(Dispatchers.IO) {
        val domain = domainOf(url) ?: return@withContext null
        if (isNeverListed(domain)) return@withContext null

        val session = DetectionSessionStore.getOrCreate(domain)
        if (session.dismissedThisSession) return@withContext session

        val doc = runCatching { Jsoup.parse(html, url) }.getOrNull() ?: return@withContext session

        if (!session.mangaListDetected) {
            UniversalPatternDetector.detectMangaList(doc)?.let { match ->
                session.mangaListDetected = true
                session.mangaListSelectors = match.selectors
                session.listPageUrl = url
                Log.d(TAG, "List pattern detected on $domain (${match.itemCount} items)")
            }
        }

        if (!session.mangaDetailDetected) {
            UniversalPatternDetector.detectMangaDetail(doc)?.let { match ->
                session.mangaDetailDetected = true
                session.mangaDetailSelectors = match.selectors
                Log.d(TAG, "Detail pattern detected on $domain")
            }
        }

        if (!session.searchDetected) {
            UniversalPatternDetector.detectSearch(doc)?.let { search ->
                session.searchDetected = true
                session.searchSelectors = search
            }
        }

        if (session.siteTitle.isBlank()) {
            session.siteTitle = doc.title().takeIf { it.isNotBlank() }
                ?: domain.removePrefix("www.").substringBefore(".").replaceFirstChar { it.uppercase() }
        }
        if (session.faviconUrl.isBlank()) {
            session.faviconUrl = doc.selectFirst("link[rel~=icon]")?.attr("abs:href").orEmpty()
        }

        recomputeConfidence(session, url)
        session.lastUpdated = System.currentTimeMillis()

        if (session.promptLevel() == DetectionPromptLevel.HINT) {
            tryGeminiRefinement(session)
        }

        session
    }

    /**
     * Feeds an observed image request URL into the reader-page heuristic. Cheap and
     * synchronous (no parsing) so it's safe to call directly from
     * `shouldInterceptRequest` on whatever thread WebView uses for that callback.
     */
    fun recordImageUrl(url: String, imageUrl: String) {
        val domain = domainOf(url) ?: return
        if (isNeverListed(domain)) return
        val session = DetectionSessionStore.get(domain) ?: return
        if (session.chapterReaderDetected) return

        session.capturedPageUrls.add(imageUrl)
        if (session.capturedPageUrls.size > 40) {
            session.capturedPageUrls = session.capturedPageUrls.takeLast(40).toMutableList()
        }

        UniversalPatternDetector.detectChapterReader(session.capturedPageUrls)?.let { match ->
            session.chapterReaderDetected = true
            session.pageImageSelectors = match.selectors
            recomputeConfidence(session, url)
            session.lastUpdated = System.currentTimeMillis()
            Log.d(TAG, "Reader pattern detected on $domain")
        }
    }

    fun sessionFor(domain: String): DetectionSession? = DetectionSessionStore.get(domain)

    fun sessionForUrl(url: String): DetectionSession? = domainOf(url)?.let { DetectionSessionStore.get(it) }

    /** "Not now" -- suppress further prompts for this domain for the rest of this browsing session. */
    fun dismissForSession(domain: String) {
        DetectionSessionStore.get(domain)?.dismissedThisSession = true
    }

    /** "Never for this site" -- persists across app restarts. */
    fun markNeverForSite(domain: String) {
        val current = prefs.getStringSet(KEY_NEVER_DOMAINS, emptySet()).orEmpty().toMutableSet()
        current.add(domain)
        prefs.edit().putStringSet(KEY_NEVER_DOMAINS, current).apply()
        DetectionSessionStore.remove(domain)
    }

    fun isNeverListed(domain: String): Boolean =
        domain in prefs.getStringSet(KEY_NEVER_DOMAINS, emptySet()).orEmpty()

    /** Called when a WebView-hosting screen (e.g. the in-app browser) is closed. */
    fun clearAllSessions() {
        DetectionSessionStore.clearAll()
    }

    /**
     * Fetches a fresh copy of the detected list page and applies the session's
     * selectors, returning up to a handful of (title, coverUrl) pairs as a
     * lightweight "Test First" preview without creating anything.
     */
    suspend fun previewSample(domain: String): List<Pair<String, String>> = withContext(Dispatchers.IO) {
        val session = DetectionSessionStore.get(domain) ?: return@withContext emptyList()
        val list = session.mangaListSelectors ?: return@withContext emptyList()
        val doc = fetchDocument(session.listPageUrl) ?: return@withContext emptyList()
        doc.select(list.itemSelector).take(6).map { card ->
            val title = runCatching { card.select(list.titleSelector).first()?.text() }.getOrNull().orEmpty()
            val cover = runCatching {
                card.select(list.coverSelector).first()
                    ?.let { it.attr("abs:src").ifBlank { it.attr("abs:data-src") } }
            }.getOrNull().orEmpty()
            title to cover
        }
    }

    /**
     * Validates the session's detected list selectors actually find real manga
     * (>= 3, per spec) on a fresh fetch, then saves a [ParserTemplate] +
     * [CustomSource] pair from it. Never saves an unvalidated template.
     */
    suspend fun createSource(domain: String): CreateResult = withContext(Dispatchers.IO) {
        val session = DetectionSessionStore.get(domain)
            ?: return@withContext CreateResult.Error("Detection session expired -- revisit the site to try again.")
        val listSelectors = session.mangaListSelectors
            ?: return@withContext CreateResult.ValidationFailed("No manga list was detected on this site.")

        val doc = fetchDocument(session.listPageUrl)
            ?: return@withContext CreateResult.ValidationFailed("Could not re-fetch the list page to validate selectors.")

        val foundCount = runCatching { doc.select(listSelectors.itemSelector).size }.getOrDefault(0)
        if (foundCount < MIN_VALIDATED_MANGA) {
            return@withContext CreateResult.ValidationFailed(
                "Only found $foundCount manga on re-check (need at least $MIN_VALIDATED_MANGA). " +
                    "Try the Visual Rule Builder instead.",
            )
        }

        val baseUrl = runCatching {
            val uri = URI(session.listPageUrl)
            "${uri.scheme}://${uri.host}"
        }.getOrElse { return@withContext CreateResult.Error("Could not resolve the site's base URL.") }

        if (customSourcesRepository.findByUrl(baseUrl) != null) {
            return@withContext CreateResult.Error("This site is already in your sources.")
        }

        val templateJson = UniversalSelectorExtractor.buildParserTemplateJson(session, baseUrl)
        val validation = ParserTemplateValidator.validate(templateJson)
        if (validation is ParserTemplateValidator.Result.Invalid) {
            return@withContext CreateResult.ValidationFailed(validation.reason)
        }

        val template = ParserTemplate(
            id = ParserTemplateRepository.generateId(),
            name = session.siteTitle.ifBlank { domain },
            version = "1",
            type = "UNIVERSAL_DETECTED",
            rawJson = templateJson,
        )
        parserTemplateRepository.add(template)

        val source = CustomSource(
            id = CustomSourcesRepository.generateId(),
            name = session.siteTitle.ifBlank { domain },
            baseUrl = baseUrl,
            type = CustomSourceType.CUSTOM_TEMPLATE,
            iconUrl = session.faviconUrl.takeIf { it.isNotBlank() },
        )
        customSourcesRepository.add(source)

        DetectionSessionStore.remove(domain)
        CreateResult.Success(source.name)
    }

    // ── Internals ─────────────────────────────────────────────────────────────

    private fun recomputeConfidence(session: DetectionSession, url: String) {
        var score = 0
        if (session.mangaListDetected) score += UniversalPatternDetector.POINTS_LIST
        if (session.mangaDetailDetected) score += UniversalPatternDetector.POINTS_DETAIL
        if (session.chapterReaderDetected) score += UniversalPatternDetector.POINTS_READER
        if (session.searchDetected) score += UniversalPatternDetector.POINTS_SEARCH
        score += UniversalPatternDetector.domainKeywordScore(url)
        score += UniversalPatternDetector.urlKeywordScore(url)
        session.confidence = score.coerceAtMost(140)
    }

    /**
     * When confidence lands in the "hint" band (70-99) but the template isn't
     * complete enough to be validated yet, ask Gemini to fill in *only* the
     * missing pieces (list or detail selectors) rather than re-analyzing the
     * whole site -- keeps this cheap and keeps [GeminiSelectorAnalyzer] focused on
     * gap-filling here rather than duplicating [io.github.landwarderer.futon.customsource.ui.UniversalSourceViewModel]'s
     * full-site flow.
     */
    private suspend fun tryGeminiRefinement(session: DetectionSession) {
        if (session.mangaListDetected && session.mangaDetailDetected) return
        val apiKey = geminiApiKey() ?: return
        if (session.listPageUrl.isBlank()) return

        val html = fetchDocument(session.listPageUrl)?.outerHtml() ?: return
        val result = runCatching {
            geminiAnalyzer.analyze(
                listHtml = html.takeIf { !session.mangaListDetected },
                detailHtml = null,
                chapterHtml = null,
                listUrl = session.listPageUrl,
                domain = session.domain,
                apiKey = apiKey,
            )
        }.getOrNull() ?: return

        if (!session.mangaListDetected && result.fields.cardSelector.isNotBlank()) {
            session.mangaListDetected = true
            session.mangaListSelectors = MangaListSelectors(
                itemSelector = result.fields.cardSelector,
                titleSelector = result.fields.titleSelector,
                coverSelector = result.fields.coverSelector,
            )
            recomputeConfidence(session, session.listPageUrl)
        }
    }

    private fun geminiApiKey(): String? =
        context.getSharedPreferences(GEMINI_PREFS_NAME, Context.MODE_PRIVATE)
            .getString(GEMINI_KEY_PREF, null)
            ?.takeIf { it.isNotBlank() }

    private suspend fun fetchDocument(url: String): Document? {
        if (url.isBlank()) return null
        return runCatching {
            val request = Request.Builder().url(url).build()
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: return null
                Jsoup.parse(body, url)
            }
        }.getOrNull()
    }

    private fun domainOf(url: String): String? = runCatching { URI(url).host }.getOrNull()

    companion object {
        private const val TAG = "MangaSiteDetector"
        private const val PREFS_NAME = "tsuki_manga_site_detector"
        private const val KEY_NEVER_DOMAINS = "never_domains"
        // Mirrors io.github.landwarderer.futon.browser.webview.WebViewSettingsManager's
        // storage so the user's existing Gemini key (set once in Settings) is reused
        // here without a second key-entry flow.
        private const val GEMINI_PREFS_NAME = "tsuki_webview_settings"
        private const val GEMINI_KEY_PREF = "wv_gemini_key"
        private const val MIN_VALIDATED_MANGA = 3
    }
}
