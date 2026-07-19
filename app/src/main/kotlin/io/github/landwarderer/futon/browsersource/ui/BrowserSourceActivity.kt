package io.github.landwarderer.futon.browsersource.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import io.github.landwarderer.futon.browser.cloudflare.CloudflareBypassManager
import io.github.landwarderer.futon.browser.cloudflare.CloudflareDetector
import io.github.landwarderer.futon.browser.cloudflare.CloudflareWebView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.browser.detection.DetectionPromptLevel
import io.github.landwarderer.futon.browser.detection.MangaSiteDetector
import io.github.landwarderer.futon.browser.detection.MangaSitePrompt
import io.github.landwarderer.futon.browser.detection.promptLevel
import io.github.landwarderer.futon.browser.webview.WebViewSettingsManager
import io.github.landwarderer.futon.browsersource.data.BrowserSourceChapterDetector
import io.github.landwarderer.futon.browsersource.data.BrowserSourceHistoryTracker
import io.github.landwarderer.futon.browsersource.data.BrowserSourcePageStore
import io.github.landwarderer.futon.browsersource.data.BrowserSourceRepository
import io.github.landwarderer.futon.core.model.parcelable.ParcelableManga
import io.github.landwarderer.futon.core.nav.ReaderIntent
import io.github.landwarderer.futon.core.nav.router
import io.github.landwarderer.futon.core.network.webview.WebViewPerformanceConfigurator
import io.github.landwarderer.futon.core.network.webview.adblock.AdBlock
import io.github.landwarderer.futon.customsource.data.CustomSourcesRepository
import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import io.github.landwarderer.futon.databinding.ActivityBrowserSourceBinding
import kotlinx.coroutines.launch
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaState
import javax.inject.Inject

/**
 * Full in-app browser for BROWSER_SOURCE custom sources.
 *
 * Features:
 *  - URL bar with back / forward / refresh navigation
 *  - Ad blocker integration (reuses [AdBlock] singleton)
 *  - Chapter detection → "📖 Open in Tsuki Reader" FAB
 *  - Pages extracted from WebView → stored in [BrowserSourcePageStore] →
 *    [CustomMangaRepository] serves them to [ReaderActivity] on demand
 *  - Reading history tracked via [BrowserSourceHistoryTracker]
 *  - Last-visited URL and scroll position persisted via [BrowserSourceRepository]
 *  - Cookie persistence is automatic via Android's [CookieManager]
 */
@AndroidEntryPoint
class BrowserSourceActivity : AppCompatActivity() {

    @Inject lateinit var adBlock: AdBlock
    @Inject lateinit var webViewSettings: WebViewSettingsManager
    @Inject lateinit var browserSourceRepository: BrowserSourceRepository
    @Inject lateinit var historyTracker: BrowserSourceHistoryTracker
    @Inject lateinit var customSourcesRepository: CustomSourcesRepository
    @Inject lateinit var mangaSiteDetector: MangaSiteDetector

    private lateinit var binding: ActivityBrowserSourceBinding
    private lateinit var mangaSitePrompt: MangaSitePrompt

    private var sourceId: Long = -1L
    private var sourceName: String = ""
    private var baseUrl: String = ""

    // Domain whose Level 3 "Add Source?" bottom sheet is currently showing, if any --
    // guards against re-showing it every time onPageFinished fires for the same site.
    private var addSourcePromptedDomain: String? = null

    // Safe snapshot of the WebView URL, updated on the main thread in onPageStarted.
    // Used inside shouldInterceptRequest() which runs on a background thread — calling
    // webView.getUrl() from there causes a RuntimeException.
    @Volatile private var currentUrl: String = ""

    // True while a Cloudflare challenge page is active; disables request
    // interception so CF's internal sub-requests are never accidentally blocked.
    @Volatile private var isCloudflareChallenge = false

    private val cloudflareDetector = CloudflareDetector()
    private lateinit var cloudflareBypassManager: CloudflareBypassManager

    // Metadata extracted from the current page (og: tags)
    private var currentPageTitle: String = ""
    private var currentPageCover: String? = null

    private val webViewBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserSourceBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sourceId = intent.getLongExtra(KEY_SOURCE_ID, -1L)
        sourceName = intent.getStringExtra(KEY_SOURCE_NAME) ?: ""
        baseUrl = intent.getStringExtra(KEY_BASE_URL) ?: ""

        onBackPressedDispatcher.addCallback(this, webViewBackCallback)

        mangaSitePrompt = MangaSitePrompt(this)

        setupToolbar()
        setupWebView()
        setupFabs()
        cloudflareBypassManager = CloudflareBypassManager(
            activity  = this,
            anchorView = binding.webView,
            onComplete = { url ->
                isCloudflareChallenge = false
                loadUrl(url)
            },
            onTimeout = {
                isCloudflareChallenge = false
            },
            onCancelled = {
                isCloudflareChallenge = false
            },
        )

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(binding.webView, true)

        if (savedInstanceState == null) {
            val startUrl = browserSourceRepository.getLastUrl(sourceId)
                ?.takeIf { it.isNotEmpty() }
                ?: baseUrl
            loadUrl(startUrl)
        }
    }

    // ── Toolbar ───────────────────────────────────────────────────────────────

    private fun setupToolbar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayShowTitleEnabled(false)

        binding.btnBack.setOnClickListener { if (binding.webView.canGoBack()) binding.webView.goBack() }
        binding.btnForward.setOnClickListener { if (binding.webView.canGoForward()) binding.webView.goForward() }
        binding.btnRefresh.setOnClickListener { binding.webView.reload() }
        binding.btnClose.setOnClickListener { finish() }

        updateAdblockIcon()
        binding.btnAdblock.setOnClickListener {
            val newState = !webViewSettings.isAdBlockEnabled
            webViewSettings.isAdBlockEnabled = newState
            updateAdblockIcon()
            val msgRes = if (newState) R.string.webview_adblock_toggled_on
                         else R.string.webview_adblock_toggled_off
            Snackbar.make(binding.webView, msgRes, Snackbar.LENGTH_SHORT).show()
        }

        binding.urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val typed = binding.urlBar.text?.toString().orEmpty().trim()
                val url = if (typed.startsWith("http://") || typed.startsWith("https://")) typed
                          else "https://$typed"
                loadUrl(url)
                hideKeyboard()
                true
            } else false
        }

        binding.urlBar.setOnFocusChangeListener { _, hasFocus -> if (hasFocus) binding.urlBar.selectAll() }
    }

    private fun updateAdblockIcon() {
        binding.btnAdblock.imageAlpha = if (webViewSettings.isAdBlockEnabled) 255 else 100
    }

    private fun updateNavButtons() {
        binding.btnBack.isEnabled = binding.webView.canGoBack()
        binding.btnBack.alpha = if (binding.webView.canGoBack()) 1f else 0.4f
        binding.btnForward.isEnabled = binding.webView.canGoForward()
        binding.btnForward.alpha = if (binding.webView.canGoForward()) 1f else 0.4f
    }

    // ── WebView ───────────────────────────────────────────────────────────────

    private fun setupWebView() {
        with(binding.webView.settings) {
            javaScriptEnabled = webViewSettings.isJavaScriptEnabled
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            webViewSettings.resolvedUserAgent()?.let { userAgentString = it }
        }
        WebViewPerformanceConfigurator.applyPerformanceSettings(binding.webView)

        binding.webView.webChromeClient = ProgressChromeClient(binding.progressBar)

        binding.webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                // NOTE: this callback runs on a background thread. Do NOT call any
                // WebView methods here (webView.url, webView.title, etc.) — that
                // causes "WebView method called on wrong thread" RuntimeException.
                // Use the @Volatile currentUrl field instead, which is always updated
                // on the main thread inside onPageStarted().
                //
                // Fix A1: Cloudflare challenge pages make dozens of internal sub-requests.
                // Intercepting ANY of them (e.g. ad-block filtering) breaks the challenge
                // silently. Let everything through while the challenge is active.
                if (isCloudflareChallenge) return null
                if (webViewSettings.isAdBlockEnabled) {
                    if (!adBlock.shouldLoadUrl(request.url.toString(), currentUrl)) {
                        return WebResourceResponse("text/plain", "utf-8", null)
                    }
                }
                val pageUrl = currentUrl
                if (BrowserSourceChapterDetector.onResourceRequest(pageUrl, request)) {
                    runOnUiThread { maybeShowOpenReaderFab() }
                }
                // Feed reader-page image requests into the universal manga-site
                // detector; cheap/synchronous, safe to call from this background thread.
                val requestUrl = request.url.toString()
                if (looksLikeImageRequest(requestUrl)) {
                    mangaSiteDetector.recordImageUrl(pageUrl, requestUrl)
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // Update the thread-safe URL snapshot FIRST so shouldInterceptRequest()
                // sees the correct URL for all subsequent resource requests on this page.
                url?.let { currentUrl = it }
                // Reset accumulated HTTP-error state so a stale 403/503 from the
                // previous page cannot bleed into the next page's CF analysis.
                cloudflareDetector.reset()
                // Fix A2: Inject anti-detection JS on every page load so Cloudflare
                // (and similar bot-management scripts) cannot fingerprint the WebView.
                CloudflareWebView.injectAntiDetectionJs(view)
                url?.let {
                    binding.urlBar.setText(it)
                    BrowserSourceChapterDetector.resetForPage(it)
                }
                binding.progressBar.isVisible = true
                updateNavButtons()
                binding.fabOpenReader.hide()
                binding.fabAddToLibrary.hide()
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: WebResourceResponse,
            ) {
                super.onReceivedHttpError(view, request, errorResponse)
                // Feed HTTP status + headers into the strict detector. The bypass
                // is only triggered later in onPageFinished once we also have the
                // full HTML and page title to confirm this is really Cloudflare.
                if (request.isForMainFrame) {
                    cloudflareDetector.recordHttpError(
                        errorResponse.statusCode,
                        errorResponse.responseHeaders,
                    )
                }
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                binding.progressBar.isVisible = false
                url?.let { pageUrl ->
                    binding.urlBar.setText(pageUrl)
                    updateNavButtons()

                    if (sourceId != -1L) {
                        browserSourceRepository.saveLastUrl(sourceId, pageUrl)
                    }

                    val savedScroll = browserSourceRepository.getScrollPosition(sourceId, pageUrl)
                    if (savedScroll > 0) {
                        view.evaluateJavascript("window.scrollTo(0, $savedScroll);", null)
                    }

                    when {
                        BrowserSourceChapterDetector.isChapterUrl(pageUrl) -> maybeShowOpenReaderFab()
                        BrowserSourceChapterDetector.isDetailUrl(pageUrl)  -> binding.fabAddToLibrary.show()
                    }

                    view.evaluateJavascript(BrowserSourceChapterDetector.GET_PAGE_META_JS) { json ->
                        val (title, coverUrl, _) = BrowserSourceChapterDetector.parseMetaJson(json)
                        currentPageTitle = title.ifBlank { view.title ?: "" }
                        currentPageCover = coverUrl.takeIf { it.isNotEmpty() }
                    }

                    if (BrowserSourceChapterDetector.isDetailUrl(pageUrl) && sourceId != -1L) {
                        val readUrls = historyTracker.getReadUrls(sourceId)
                        if (readUrls.isNotEmpty()) {
                            view.evaluateJavascript(
                                BrowserSourceChapterDetector.buildMarkReadJs(readUrls), null
                            )
                        }
                    }

                    CookieManager.getInstance().flush()

                    view.evaluateJavascript("document.documentElement.outerHTML") { rawJson ->
                        val html = unescapeJsString(rawJson)
                        if (html.isNotBlank()) {
                            // Strict two-stage Cloudflare detection:
                            // requires 403/503 HTTP status + ≥2 markers + CF page title.
                            if (!isCloudflareChallenge) {
                                val cookies = CookieManager.getInstance().getCookie(pageUrl) ?: ""
                                if (cloudflareDetector.analyzeHtml(html, view.title ?: "", cookies)) {
                                    isCloudflareChallenge = true
                                    cloudflareBypassManager.startBypass(pageUrl)
                                }
                            }
                            runUniversalDetection(pageUrl, html)
                        }
                    }
                }
            }
        }
    }

    /** WebView returns evaluateJavascript results as a JSON string literal; unwrap it. */

    private fun unescapeJsString(raw: String?): String {
        if (raw.isNullOrEmpty() || raw == "null") return ""
        return runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
    }

    private fun looksLikeImageRequest(url: String): Boolean {
        val lower = url.substringBefore('?').lowercase()
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
            lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".avif")
    }

    // ── Universal manga-site detection ───────────────────────────────────────

    private fun runUniversalDetection(pageUrl: String, html: String) {
        lifecycleScope.launch {
            val session = mangaSiteDetector.analyzePage(pageUrl, html) ?: return@launch
            if (session.dismissedThisSession) return@launch
            when (session.promptLevel()) {
                DetectionPromptLevel.NONE -> {
                    mangaSitePrompt.hideLearningIcon(binding.mangaSiteDetectIcon)
                    mangaSitePrompt.hideHintBanner(binding.mangaSiteHintBanner)
                }
                DetectionPromptLevel.LEARNING -> {
                    mangaSitePrompt.hideHintBanner(binding.mangaSiteHintBanner)
                    mangaSitePrompt.showLearningIcon(binding.mangaSiteDetectIcon)
                }
                DetectionPromptLevel.HINT -> {
                    mangaSitePrompt.hideLearningIcon(binding.mangaSiteDetectIcon)
                    mangaSitePrompt.showHintBanner(
                        bannerRoot = binding.mangaSiteHintBanner,
                        messageView = binding.mangaSiteHintMessage,
                        dismissButton = binding.mangaSiteHintDismiss,
                        message = getString(R.string.manga_site_hint_message),
                        onDismiss = { mangaSiteDetector.dismissForSession(session.domain) },
                    )
                }
                DetectionPromptLevel.ADD_SOURCE -> {
                    mangaSitePrompt.hideLearningIcon(binding.mangaSiteDetectIcon)
                    mangaSitePrompt.hideHintBanner(binding.mangaSiteHintBanner)
                    if (addSourcePromptedDomain != session.domain) {
                        addSourcePromptedDomain = session.domain
                        showAddSourceSheet(session.domain)
                    }
                }
            }
        }
    }

    private fun showAddSourceSheet(domain: String) {
        val session = mangaSiteDetector.sessionFor(domain) ?: return
        mangaSitePrompt.showAddSourcePrompt(
            session = session,
            onAddSource = {
                android.util.Log.d("TsukiSourceDebug", "showAddSourceSheet.onAddSource: user tapped Add for domain=$domain")
                lifecycleScope.launch {
                    Snackbar.make(binding.webView, getString(R.string.manga_site_prompt_creating, session.siteTitle), Snackbar.LENGTH_SHORT).show()
                    android.util.Log.d("TsukiSourceDebug", "showAddSourceSheet: calling mangaSiteDetector.createSource(domain=$domain)")
                    when (val result = mangaSiteDetector.createSource(domain)) {
                        is MangaSiteDetector.CreateResult.Success -> {
                            android.util.Log.d("TsukiSourceDebug", "showAddSourceSheet: createSource SUCCESS name=${result.name}")
                            Snackbar.make(binding.webView, getString(R.string.manga_site_prompt_created, result.name), Snackbar.LENGTH_LONG).show()
                        }
                        is MangaSiteDetector.CreateResult.ValidationFailed -> {
                            android.util.Log.d("TsukiSourceDebug", "showAddSourceSheet: createSource ValidationFailed reason=${result.reason}")
                            Snackbar.make(binding.webView, getString(R.string.manga_site_prompt_create_failed, result.reason), Snackbar.LENGTH_LONG).show()
                        }
                        is MangaSiteDetector.CreateResult.Error -> {
                            android.util.Log.d("TsukiSourceDebug", "showAddSourceSheet: createSource Error message=${result.message}")
                            Snackbar.make(binding.webView, getString(R.string.manga_site_prompt_create_failed, result.message), Snackbar.LENGTH_LONG).show()
                        }
                    }
                }
            },
            onTestFirst = {
                lifecycleScope.launch {
                    val sample = mangaSiteDetector.previewSample(domain)
                    val msg = if (sample.isEmpty()) getString(R.string.manga_site_prompt_test_empty)
                              else getString(R.string.manga_site_prompt_test_result, sample.size)
                    Snackbar.make(binding.webView, msg, Snackbar.LENGTH_LONG).show()
                }
            },
            onNotNow = { mangaSiteDetector.dismissForSession(domain) },
            onNeverForSite = { mangaSiteDetector.markNeverForSite(domain) },
        )
    }

    private fun loadUrl(url: String) {
        binding.urlBar.setText(url)
        binding.webView.loadUrl(url)
    }

    // ── FABs ──────────────────────────────────────────────────────────────────

    private fun setupFabs() {
        binding.fabOpenReader.setOnClickListener { openInNativeReader() }
        binding.fabAddToLibrary.setOnClickListener {
            binding.fabAddToLibrary.hide()
            Snackbar.make(
                binding.webView,
                getString(R.string.browser_source_already_added_hint),
                Snackbar.LENGTH_SHORT
            ).show()
        }
    }

    private fun maybeShowOpenReaderFab() {
        if (webViewSettings.isOpenInReaderPromptEnabled) {
            binding.fabOpenReader.show()
        }
    }

    // ── Reader integration ────────────────────────────────────────────────────

    private fun openInNativeReader() {
        binding.webView.evaluateJavascript(BrowserSourceChapterDetector.COLLECT_IMAGES_JS) { jsonResult ->
            val imageUrls = BrowserSourceChapterDetector.parseImageUrls(jsonResult)
            if (imageUrls.isEmpty()) {
                Toast.makeText(this, R.string.webview_no_images_found, Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }

            val chapterUrl = binding.webView.url ?: return@evaluateJavascript
            val pageTitle  = currentPageTitle.ifBlank { binding.webView.title ?: "Chapter" }

            // Stable ID derived from the chapter URL
            val chapterId = chapterUrl.hashCode().toLong()
            val mangaId   = (baseUrl + "_browser").hashCode().toLong()

            // Look up the CustomSource so we can pass the correct MangaSource
            val customSource = customSourcesRepository.findById(sourceId)
                ?.let { CustomMangaSource(it) }
                ?: return@evaluateJavascript

            // Convert image URLs → MangaPage objects
            val pages: List<MangaPage> = imageUrls.mapIndexed { i, url ->
                MangaPage(
                    id      = chapterId * 1000L + i,
                    url     = url,
                    preview = null,
                    source  = customSource,
                )
            }

            // Stash pages so CustomMangaRepository.getPages() can serve them
            BrowserSourcePageStore.put(chapterId, pages)

            // Build synthetic MangaChapter
            val chapter = MangaChapter(
                id          = chapterId,
                title       = pageTitle,
                number      = 1f,
                volume      = 0,
                url         = chapterUrl,
                scanlator   = null,
                uploadDate  = 0L,
                branch      = null,
                source      = customSource,
            )

            // Build synthetic Manga carrying the chapter list
            val manga = Manga(
                id            = mangaId,
                title         = sourceName.ifBlank { pageTitle },
                altTitles     = emptySet(),
                url           = chapterUrl,
                publicUrl     = chapterUrl,
                rating        = 0f,
                contentRating = ContentRating.SAFE,
                coverUrl      = currentPageCover ?: "",
                tags          = emptySet(),
                state         = MangaState.ONGOING,
                authors       = emptySet(),
                largeCoverUrl = currentPageCover,
                description   = null,
                chapters      = listOf(chapter),
                source        = customSource,
            )

            // Record in reading history
            if (sourceId != -1L) {
                historyTracker.recordChapterOpened(
                    sourceId      = sourceId,
                    sourceName    = sourceName,
                    chapterUrl    = chapterUrl,
                    mangaTitle    = pageTitle,
                    mangaCoverUrl = currentPageCover,
                    chapterTitle  = pageTitle,
                )
                historyTracker.markRead(sourceId, chapterUrl)
            }

            // Open the native reader
            val readerIntent = ReaderIntent.Builder(this)
                .manga(manga)
                .incognito(false)
                .build()
            router.openReader(readerIntent)
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onPause() {
        binding.webView.onPause()
        val currentUrl = binding.webView.url
        if (!currentUrl.isNullOrEmpty() && sourceId != -1L) {
            binding.webView.evaluateJavascript("window.scrollY") { scrollY ->
                scrollY?.toIntOrNull()?.let { y ->
                    browserSourceRepository.saveScrollPosition(sourceId, currentUrl, y)
                }
            }
            browserSourceRepository.saveLastUrl(sourceId, currentUrl)
        }
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
    }

    override fun onDestroy() {
        cloudflareBypassManager.cancel()
        binding.webView.stopLoading()
        binding.webView.destroy()
        mangaSiteDetector.clearAllSessions()
        super.onDestroy()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun hideKeyboard() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(binding.urlBar.windowToken, 0)
        binding.urlBar.clearFocus()
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        const val KEY_SOURCE_ID   = "browser_source_id"
        const val KEY_SOURCE_NAME = "browser_source_name"
        const val KEY_BASE_URL    = "browser_source_url"

        fun createIntent(
            context: Context,
            sourceId: Long,
            sourceName: String,
            baseUrl: String,
        ): Intent = Intent(context, BrowserSourceActivity::class.java).apply {
            putExtra(KEY_SOURCE_ID, sourceId)
            putExtra(KEY_SOURCE_NAME, sourceName)
            putExtra(KEY_BASE_URL, baseUrl)
        }
    }
}
