package io.github.landwarderer.futon.browsersource.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
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
import io.github.landwarderer.futon.core.db.entity.WebViewSourceEntity
import io.github.landwarderer.futon.webviewsource.data.WebViewSourceRepository
import io.github.landwarderer.futon.webviewsource.data.ChapterPatternDetector
import io.github.landwarderer.futon.webviewsource.data.anilist.WebViewAniListRepository
import io.github.landwarderer.futon.webviewsource.ui.anilist.LinkAniListSheet
import io.github.landwarderer.futon.scrobbling.mal.data.MALRepository
import io.github.landwarderer.futon.scrobbling.common.domain.model.ScrobblerManga
import io.github.landwarderer.futon.core.model.parcelable.ParcelableManga
import io.github.landwarderer.futon.core.nav.ReaderIntent
import io.github.landwarderer.futon.reader.ui.ReaderState
import io.github.landwarderer.futon.core.nav.router
import io.github.landwarderer.futon.core.network.webview.WebViewPerformanceConfigurator
import io.github.landwarderer.futon.core.network.webview.adblock.AdBlock
import io.github.landwarderer.futon.customsource.data.CustomSourcesRepository
import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import io.github.landwarderer.futon.databinding.ActivityBrowserSourceBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import org.koitharu.kotatsu.parsers.model.ContentRating
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.parsers.model.MangaState
import javax.inject.Inject
import kotlin.coroutines.resume

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
    @Inject lateinit var webViewSourceRepository: WebViewSourceRepository
    @Inject lateinit var webViewAniListRepository: WebViewAniListRepository
    @Inject lateinit var malRepository: MALRepository

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
    private var lastSyncedAniListChapter: Int = -1
    private var lastSyncedMalChapter: Int = -1

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
        ensureTrackingEntry()

        // Direct-URL mode: opened via TsukiBrowser.open() / AppRouter.openBrowser()
        // instead of via a BROWSER_SOURCE custom source.  No source ID is involved.
        val directUrl = intent.getStringExtra(KEY_DIRECT_URL)
        val directTitle = intent.getStringExtra(KEY_DIRECT_TITLE)

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
            when {
                directUrl != null -> {
                    // Show the provided title immediately while the page loads
                    if (!directTitle.isNullOrEmpty()) {
                        supportActionBar?.title = directTitle
                    }
                    loadUrl(directUrl)
                }
                else -> {
                    val startUrl = browserSourceRepository.getLastUrl(sourceId)
                        ?.takeIf { it.isNotEmpty() }
                        ?: baseUrl
                    loadUrl(startUrl)
                }
            }
        }
    }

    // ── Options menu (overflow ⋮) ─────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(io.github.landwarderer.futon.R.menu.opt_browser_source, menu)
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {

        // Step 6 of the README: let users open the current page in Chrome if they
        // specifically want to, while the default always uses Tsuki's WebView.
        io.github.landwarderer.futon.R.id.action_browser_source_link_mal -> {
            showMalLinkDialog()
            true
        }

        io.github.landwarderer.futon.R.id.action_browser_source_link_anilist -> {
            if (sourceId != -1L) {
                LinkAniListSheet.newInstance(sourceId, sourceName)
                    .show(supportFragmentManager, LinkAniListSheet.TAG)
            } else {
                Snackbar.make(binding.webView, "Save this website as a Browser Source before linking tracking.", Snackbar.LENGTH_LONG).show()
            }
            true
        }

        io.github.landwarderer.futon.R.id.action_browser_source_open_external -> {
            val url = binding.webView.url
            if (url != null) router.openExternalBrowser(url)
            true
        }

        io.github.landwarderer.futon.R.id.action_browser_source_adblock -> {
            val newState = !webViewSettings.isAdBlockEnabled
            webViewSettings.isAdBlockEnabled = newState
            updateAdblockIcon()
            val msgRes = if (newState) io.github.landwarderer.futon.R.string.webview_adblock_toggled_on
                         else io.github.landwarderer.futon.R.string.webview_adblock_toggled_off
            Snackbar.make(binding.webView, msgRes, Snackbar.LENGTH_SHORT).show()
            true
        }

        io.github.landwarderer.futon.R.id.action_browser_source_js -> {
            val newJs = !webViewSettings.isJavaScriptEnabled
            webViewSettings.isJavaScriptEnabled = newJs
            binding.webView.settings.javaScriptEnabled = newJs
            binding.webView.reload()
            val msgRes = if (newJs) io.github.landwarderer.futon.R.string.webview_js_toggled_on
                         else io.github.landwarderer.futon.R.string.webview_js_toggled_off
            Snackbar.make(binding.webView, msgRes, Snackbar.LENGTH_SHORT).show()
            true
        }

        io.github.landwarderer.futon.R.id.action_browser_source_desktop -> {
            val newDesktop = !webViewSettings.isDesktopMode
            webViewSettings.isDesktopMode = newDesktop
            webViewSettings.resolvedUserAgent()?.let { binding.webView.settings.userAgentString = it }
            binding.webView.reload()
            val msgRes = if (newDesktop) io.github.landwarderer.futon.R.string.webview_desktop_on
                         else io.github.landwarderer.futon.R.string.webview_desktop_off
            Snackbar.make(binding.webView, msgRes, Snackbar.LENGTH_SHORT).show()
            true
        }

        io.github.landwarderer.futon.R.id.action_browser_source_clear_cookies -> {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            Snackbar.make(binding.webView, io.github.landwarderer.futon.R.string.cookies_cleared, Snackbar.LENGTH_SHORT).show()
            true
        }

        else -> super.onOptionsItemSelected(item)
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

        binding.urlBar.hint = "Search or enter website"
        binding.urlBar.setOnEditorActionListener { _, actionId, event ->
            if (actionId == EditorInfo.IME_ACTION_GO ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            ) {
                val typed = binding.urlBar.text?.toString().orEmpty().trim()
                if (typed.isBlank()) return@setOnEditorActionListener true
                val url = when {
                    typed.startsWith("http://", ignoreCase = true) ||
                        typed.startsWith("https://", ignoreCase = true) -> typed
                    typed.substringBefore('/').contains('.') ||
                        typed.substringBefore('/').equals("localhost", ignoreCase = true) -> "https://$typed"
                    else -> "https://www.google.com/search?q=" + Uri.encode(typed)
                }
                loadUrl(url)
                hideKeyboard()
                true
            } else false
        }

        binding.urlBar.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                binding.urlBar.setText(binding.webView.url.orEmpty())
                binding.urlBar.selectAll()
            } else {
                binding.urlBar.setText(formatAddress(binding.webView.url.orEmpty()))
            }
        }
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
                    if (!adBlock.shouldLoadUrl(request.url.toString(), currentUrl, requestResourceType(request))) {
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
                    binding.urlBar.setText(formatAddress(it))
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
                    binding.urlBar.setText(formatAddress(pageUrl))
                    updateNavButtons()

                    if (sourceId != -1L) {
                        browserSourceRepository.saveLastUrl(sourceId, pageUrl)
                        syncTrackingProgress(pageUrl)
                    }

                    val savedScroll = browserSourceRepository.getScrollPosition(sourceId, pageUrl)
                    if (savedScroll > 0) {
                        view.evaluateJavascript("window.scrollTo(0, $savedScroll);", null)
                    }

                    // Browser Source now reads directly in the website's own page.
                    // Do not surface the old native-reader handoff or placeholder library FAB.
                    binding.fabOpenReader.hide()
                    binding.fabAddToLibrary.hide()

                    injectCosmeticFilters(view, pageUrl)

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

    /** Apply supported EasyList/EasyPrivacy cosmetic rules without blocking page scripts. */
    private fun injectCosmeticFilters(view: WebView, pageUrl: String) {
        if (!webViewSettings.isAdBlockEnabled) return
        lifecycleScope.launch(Dispatchers.IO) {
            val css = adBlock.cosmeticCss(pageUrl)
            if (css.isBlank()) return@launch
            val script = """
                (function() {
                    var styleId = 'tsuki-adblock-cosmetic';
                    var previous = document.getElementById(styleId);
                    if (previous) previous.remove();
                    var style = document.createElement('style');
                    style.id = styleId;
                    style.textContent = CSS_PLACEHOLDER;
                    (document.head || document.documentElement).appendChild(style);
                })();
            """.trimIndent().replace("CSS_PLACEHOLDER", JSONObject.quote(css))
            withContext(Dispatchers.Main) {
                if (!isFinishing && view.url?.substringBefore('#') == pageUrl.substringBefore('#')) {
                    view.evaluateJavascript(script, null)
                }
            }
        }
    }

    private fun unescapeJsString(raw: String?): String {
        if (raw.isNullOrEmpty() || raw == "null") return ""
        return runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
    }

    /** Maps WebView Fetch Metadata to the resource types used by EasyList modifiers. */
    private fun requestResourceType(request: WebResourceRequest): String? {
        if (request.isForMainFrame) return "document"
        val destination = request.requestHeaders.entries
            .firstOrNull { it.key.equals("Sec-Fetch-Dest", ignoreCase = true) }
            ?.value
            ?.lowercase()
        return when (destination) {
            "document" -> "document"
            "iframe" -> "subdocument"
            "script" -> "script"
            "image" -> "image"
            "style" -> "stylesheet"
            "font" -> "font"
            "audio", "video", "track" -> "media"
            "empty" -> "xmlhttprequest"
            else -> {
                val accept = request.requestHeaders.entries
                    .firstOrNull { it.key.equals("Accept", ignoreCase = true) }
                    ?.value
                    ?.lowercase()
                    .orEmpty()
                when {
                    "image/" in accept -> "image"
                    "text/css" in accept -> "stylesheet"
                    "javascript" in accept -> "script"
                    request.url.encodedPath.lowercase().endsWith(".js") ||
                        request.url.encodedPath.lowercase().endsWith(".mjs") -> "script"
                    request.url.encodedPath.lowercase().endsWith(".css") -> "stylesheet"
                    listOf(".jpg", ".jpeg", ".png", ".gif", ".webp", ".avif").any {
                        request.url.encodedPath.lowercase().endsWith(it)
                    } -> "image"
                    listOf(".woff", ".woff2", ".ttf", ".otf").any {
                        request.url.encodedPath.lowercase().endsWith(it)
                    } -> "font"
                    listOf(".mp4", ".webm", ".mp3", ".m4a").any {
                        request.url.encodedPath.lowercase().endsWith(it)
                    } -> "media"
                    else -> null
                }
            }
        }
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

    /**
     * Reuses the existing WebView-source tracking record so Browser Source can
     * share Tsuki's established AniList linking and OAuth implementation.
     */
    private fun ensureTrackingEntry() {
        if (sourceId == -1L) return
        lifecycleScope.launch {
            if (webViewSourceRepository.getById(sourceId) == null) {
                val now = System.currentTimeMillis()
                webViewSourceRepository.save(
                    WebViewSourceEntity(
                        id = sourceId,
                        title = sourceName.ifBlank { "Browser Source" },
                        baseUrl = baseUrl,
                        chapterUrlPattern = null,
                        coverUrl = null,
                        lastReadUrl = null,
                        lastReadScrollPercent = 0f,
                        lastReadChapter = null,
                        latestKnownChapter = null,
                        anilistId = null,
                        malId = null,
                        readingStatus = null,
                        addedAt = now,
                        lastReadAt = null,
                    )
                )
            }
        }
    }

    /** Save chapter progress and sync AniList only when a chapter number is identifiable. */
    private fun syncTrackingProgress(url: String) {
        lifecycleScope.launch {
            val entry = webViewSourceRepository.getById(sourceId) ?: createTrackingEntry()
            val pattern = entry.chapterUrlPattern ?: ChapterPatternDetector.detect(url)
            val chapter = ChapterPatternDetector.extractChapter(url, pattern)
            if (pattern != null && entry.chapterUrlPattern == null) {
                webViewSourceRepository.save(entry.copy(chapterUrlPattern = pattern))
            }
            if (chapter == null || chapter <= 0f) return@launch

            val now = System.currentTimeMillis()
            webViewSourceRepository.save(
                entry.copy(
                    chapterUrlPattern = pattern,
                    lastReadUrl = url,
                    lastReadChapter = chapter,
                    lastReadAt = now,
                )
            )

            val chapterNumber = chapter.toInt()
            if (chapterNumber <= 0) return@launch

            entry.anilistId?.let { anilistId ->
                if (chapterNumber > lastSyncedAniListChapter) {
                    val current = webViewAniListRepository.getListEntry(anilistId)?.progress ?: 0
                    if (chapterNumber > current && webViewAniListRepository.syncProgress(anilistId, chapterNumber)) {
                        lastSyncedAniListChapter = chapterNumber
                    } else {
                        lastSyncedAniListChapter = maxOf(lastSyncedAniListChapter, current)
                    }
                }
            }

            entry.malId?.let { malId ->
                if (chapterNumber > lastSyncedMalChapter && malRepository.isAuthorized) {
                    runCatching { malRepository.updateRate(malId, sourceId, chapterNumber) }
                        .onSuccess { lastSyncedMalChapter = chapterNumber }
                }
            }
        }
    }

    private suspend fun createTrackingEntry(): WebViewSourceEntity {
        val now = System.currentTimeMillis()
        return WebViewSourceEntity(
            id = sourceId,
            title = sourceName.ifBlank { "Browser Source" },
            baseUrl = baseUrl,
            chapterUrlPattern = null,
            coverUrl = null,
            lastReadUrl = null,
            lastReadScrollPercent = 0f,
            lastReadChapter = null,
            latestKnownChapter = null,
            anilistId = null,
            malId = null,
            readingStatus = null,
            addedAt = now,
            lastReadAt = null,
        ).also { webViewSourceRepository.save(it) }
    }

    /** Search and link a MyAnimeList entry to this Browser Source. */
    private fun showMalLinkDialog() {
        if (sourceId == -1L) {
            Snackbar.make(binding.webView, "Save this website as a Browser Source before linking tracking.", Snackbar.LENGTH_LONG).show()
            return
        }
        if (!malRepository.isAuthorized) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Connect MyAnimeList")
                .setMessage("Connect your MyAnimeList account in Tsuki's Scrobbling settings, then return here to link this manga.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val queryInput = android.widget.EditText(this).apply {
            hint = "Manga title"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Link MyAnimeList")
            .setView(queryInput)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Search", null)
            .show()
            .also { dialog ->
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val query = queryInput.text?.toString().orEmpty().trim()
                    if (query.isBlank()) return@setOnClickListener
                    dialog.dismiss()
                    lifecycleScope.launch {
                        val results = runCatching { malRepository.findManga(query, 0) }.getOrDefault(emptyList())
                        if (results.isEmpty()) {
                            Snackbar.make(binding.webView, "No MyAnimeList matches found.", Snackbar.LENGTH_LONG).show()
                        } else {
                            showMalSearchResults(results)
                        }
                    }
                }
            }
    }

    private fun showMalSearchResults(results: List<ScrobblerManga>) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Choose the matching manga")
            .setItems(results.map { it.name }.toTypedArray()) { _, index ->
                val selected = results[index]
                lifecycleScope.launch {
                    val entry = webViewSourceRepository.getById(sourceId) ?: createTrackingEntry()
                    runCatching {
                        malRepository.createRate(sourceId, selected.id)
                        webViewSourceRepository.save(entry.copy(malId = selected.id.toInt()))
                    }.onSuccess {
                        Snackbar.make(binding.webView, "Linked to MyAnimeList: " + selected.name, Snackbar.LENGTH_LONG).show()
                    }.onFailure {
                        Snackbar.make(binding.webView, "Could not link MyAnimeList: " + (it.message ?: "unknown error"), Snackbar.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Shows only the site identity until the user focuses the address/search field. */
    private fun formatAddress(url: String): String {
        if (url.isBlank()) return ""
        val uri = Uri.parse(url)
        return uri.host ?: url
    }

    private fun loadUrl(url: String) {
        binding.urlBar.setText(url)
        binding.webView.loadUrl(url)
    }

    // ── FABs ──────────────────────────────────────────────────────────────────

    private fun setupFabs() {
        // The website is the reader in Browser Source. Keep the legacy handoff
        // controls hidden rather than prompting users to leave the web reading flow.
        binding.fabOpenReader.hide()
        binding.fabAddToLibrary.hide()
    }

    private fun maybeShowOpenReaderFab() {
        // Kept as a no-op for the legacy request detector; Browser Source no
        // longer asks users to hand the chapter off to the native reader.
        binding.fabOpenReader.hide()
    }

    // ── Reader integration ────────────────────────────────────────────────────

    private fun openInNativeReader() {
        lifecycleScope.launch {
            binding.fabOpenReader.isEnabled = false
            val imageUrls = try {
                collectChapterImagesWithoutScrolling()
            } finally {
                binding.fabOpenReader.isEnabled = true
            }
            if (imageUrls.isEmpty()) {
                Toast.makeText(this@BrowserSourceActivity, R.string.webview_no_images_found, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val chapterUrl = binding.webView.url ?: return@launch
            val pageTitle  = currentPageTitle.ifBlank { binding.webView.title ?: "Chapter" }

            // Stable ID derived from the chapter URL
            val chapterId = chapterUrl.hashCode().toLong()
            val mangaId   = (baseUrl + "_browser").hashCode().toLong()

            // Look up the CustomSource so we can pass the correct MangaSource
            val customSource = customSourcesRepository.findById(sourceId)
                ?.let { CustomMangaSource(it) }
            if (customSource == null) {
                Toast.makeText(
                    this@BrowserSourceActivity,
                    "Save this website as a Browser Source to open chapters in Tsuki Reader.",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }

            // Capture request context per image URL. Some CDNs require cookies
            // scoped to their own host, not just the manga website's host.
            val cookieManager = CookieManager.getInstance()
            val userAgent = binding.webView.settings.userAgentString
            val headersByPageId = LinkedHashMap<Long, Map<String, String>>()

            // Convert image URLs → MangaPage objects
            val pages: List<MangaPage> = imageUrls.mapIndexed { i, url ->
                val pageId = chapterId * 1000L + i
                val headers = linkedMapOf(
                    "Referer" to chapterUrl,
                    "User-Agent" to userAgent,
                )
                cookieManager.getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                    headers["Cookie"] = it
                }
                headersByPageId[pageId] = headers
                MangaPage(
                    id      = pageId,
                    url     = url,
                    preview = null,
                    source  = customSource,
                )
            }

            // Stash pages and their browser-session headers for PageLoader.
            BrowserSourcePageStore.put(chapterId, pages, headersByPageId)

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

            // Keep the chapter outside ParcelableManga too: its parceler intentionally
            // omits chapters, and ReaderActivity reloads details through the source repo.
            BrowserSourcePageStore.putChapter(mangaId, chapter)

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
            val readerIntent = ReaderIntent.Builder(this@BrowserSourceActivity)
                .manga(manga)
                .state(ReaderState(chapterId, 0, 0))
                .incognito(false)
                .build()
            router.openReader(readerIntent)
        }
    }

    /**
     * Collects rendered and lazy-load image URLs without moving the user's page.
     * Lazy-load attributes are included by the DOM collector; setting existing
     * lazy images to eager also helps sites using native loading="lazy".
     */
    private suspend fun collectChapterImagesWithoutScrolling(): List<String> {
        // MangaFire already requests the full ordered page list through its API.
        // Reuse the tokenized request URL observed by this WebView instead of
        // scraping only the currently-rendered/lazy-loaded image elements.
        evaluateJavascriptAwait(BrowserSourceChapterDetector.COLLECT_MANGAFIRE_API_PAGES_JS)
        for (attempt in 0 until MANGAFIRE_API_POLL_ATTEMPTS) {
            delay(MANGAFIRE_API_POLL_DELAY_MS)
            val rawResult = evaluateJavascriptAwait(
                "(function(){return window.__tsukiMangaFireApiPages || '__pending__';})()"
            )
            val result = unescapeJsString(rawResult)
            if (result != "__pending__") {
                val mangaFirePages = BrowserSourceChapterDetector.parseImageUrls(
                    org.json.JSONArray(result).toString()
                )
                if (mangaFirePages.isNotEmpty()) return mangaFirePages
                break
            }
        }

        val collected = LinkedHashSet<String>()

        evaluateJavascriptAwait(
            "document.querySelectorAll('img[loading=lazy]').forEach(function(img){img.loading='eager';});"
        )
        delay(EXTRACTION_SETTLE_DELAY_MS)

        val firstPass = evaluateJavascriptAwait(BrowserSourceChapterDetector.COLLECT_IMAGES_JS)
        collected.addAll(BrowserSourceChapterDetector.parseImageUrls(firstPass))

        // Catch URLs populated asynchronously, without triggering page scrolling.
        delay(EXTRACTION_SETTLE_DELAY_MS)
        val secondPass = evaluateJavascriptAwait(BrowserSourceChapterDetector.COLLECT_IMAGES_JS)
        collected.addAll(BrowserSourceChapterDetector.parseImageUrls(secondPass))

        return collected.toList()
    }

    private suspend fun evaluateJavascriptAwait(script: String): String? =
        suspendCancellableCoroutine { continuation ->
            binding.webView.evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result)
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
        private const val EXTRACTION_SETTLE_DELAY_MS = 500L
        private const val MANGAFIRE_API_POLL_ATTEMPTS = 20
        private const val MANGAFIRE_API_POLL_DELAY_MS = 150L

        const val KEY_SOURCE_ID   = "browser_source_id"
        const val KEY_SOURCE_NAME = "browser_source_name"
        const val KEY_BASE_URL    = "browser_source_url"

        /** Extras used when opening a URL directly (not tied to a saved custom source). */
        const val KEY_DIRECT_URL   = "browser_direct_url"
        const val KEY_DIRECT_TITLE = "browser_direct_title"

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

        /**
         * Create an Intent that opens [url] directly in Tsuki's WebView browser
         * without requiring a saved custom source entry.
         *
         * Used by [TsukiBrowser.open] and [AppRouter.browserIntent].
         */
        fun createDirectIntent(
            context: Context,
            url: String,
            title: String?,
        ): Intent = Intent(context, BrowserSourceActivity::class.java).apply {
            putExtra(KEY_DIRECT_URL, url)
            putExtra(KEY_DIRECT_TITLE, title ?: url)
        }
    }
}
}{JSONObject.quote(css)};
                    (document.head || document.documentElement).appendChild(style);
                })();
            """.trimIndent()
            withContext(Dispatchers.Main) {
                if (!isFinishing && view.url?.substringBefore('#') == pageUrl.substringBefore('#')) {
                    view.evaluateJavascript(script, null)
                }
            }
        }
    }

    /** WebView returns evaluateJavascript results as a JSON string literal; unwrap it. */

    private fun unescapeJsString(raw: String?): String {
        if (raw.isNullOrEmpty() || raw == "null") return ""
        return runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull().orEmpty()
    }

    /** Maps WebView Fetch Metadata to the resource types used by EasyList modifiers. */
    private fun requestResourceType(request: WebResourceRequest): String? {
        val destination = request.requestHeaders.entries
            .firstOrNull { it.key.equals("Sec-Fetch-Dest", ignoreCase = true) }
            ?.value
            ?.lowercase()
        return when (destination) {
            "document" -> "document"
            "iframe" -> "subdocument"
            "script" -> "script"
            "image" -> "image"
            "style" -> "stylesheet"
            "font" -> "font"
            "audio", "video", "track" -> "media"
            "empty" -> "xmlhttprequest"
            else -> {
                val accept = request.requestHeaders.entries
                    .firstOrNull { it.key.equals("Accept", ignoreCase = true) }
                    ?.value
                    ?.lowercase()
                    .orEmpty()
                when {
                    "image/" in accept -> "image"
                    "text/css" in accept -> "stylesheet"
                    "javascript" in accept -> "script"
                    else -> null
                }
            }
        }
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

    /**
     * Reuses the existing WebView-source tracking record so Browser Source can
     * share Tsuki's established AniList linking and OAuth implementation.
     */
    private fun ensureTrackingEntry() {
        if (sourceId == -1L) return
        lifecycleScope.launch {
            if (webViewSourceRepository.getById(sourceId) == null) {
                val now = System.currentTimeMillis()
                webViewSourceRepository.save(
                    WebViewSourceEntity(
                        id = sourceId,
                        title = sourceName.ifBlank { "Browser Source" },
                        baseUrl = baseUrl,
                        chapterUrlPattern = null,
                        coverUrl = null,
                        lastReadUrl = null,
                        lastReadScrollPercent = 0f,
                        lastReadChapter = null,
                        latestKnownChapter = null,
                        anilistId = null,
                        malId = null,
                        readingStatus = null,
                        addedAt = now,
                        lastReadAt = null,
                    )
                )
            }
        }
    }

    /** Save chapter progress and sync AniList only when a chapter number is identifiable. */
    private fun syncTrackingProgress(url: String) {
        lifecycleScope.launch {
            val entry = webViewSourceRepository.getById(sourceId) ?: createTrackingEntry()
            val pattern = entry.chapterUrlPattern ?: ChapterPatternDetector.detect(url)
            val chapter = ChapterPatternDetector.extractChapter(url, pattern)
            if (pattern != null && entry.chapterUrlPattern == null) {
                webViewSourceRepository.save(entry.copy(chapterUrlPattern = pattern))
            }
            if (chapter == null || chapter <= 0f) return@launch

            val now = System.currentTimeMillis()
            webViewSourceRepository.save(
                entry.copy(
                    chapterUrlPattern = pattern,
                    lastReadUrl = url,
                    lastReadChapter = chapter,
                    lastReadAt = now,
                )
            )

            val chapterNumber = chapter.toInt()
            if (chapterNumber <= 0) return@launch

            entry.anilistId?.let { anilistId ->
                if (chapterNumber > lastSyncedAniListChapter) {
                    val current = webViewAniListRepository.getListEntry(anilistId)?.progress ?: 0
                    if (chapterNumber > current && webViewAniListRepository.syncProgress(anilistId, chapterNumber)) {
                        lastSyncedAniListChapter = chapterNumber
                    } else {
                        lastSyncedAniListChapter = maxOf(lastSyncedAniListChapter, current)
                    }
                }
            }

            entry.malId?.let { malId ->
                if (chapterNumber > lastSyncedMalChapter && malRepository.isAuthorized) {
                    runCatching { malRepository.updateRate(malId, sourceId, chapterNumber) }
                        .onSuccess { lastSyncedMalChapter = chapterNumber }
                }
            }
        }
    }

    private suspend fun createTrackingEntry(): WebViewSourceEntity {
        val now = System.currentTimeMillis()
        return WebViewSourceEntity(
            id = sourceId,
            title = sourceName.ifBlank { "Browser Source" },
            baseUrl = baseUrl,
            chapterUrlPattern = null,
            coverUrl = null,
            lastReadUrl = null,
            lastReadScrollPercent = 0f,
            lastReadChapter = null,
            latestKnownChapter = null,
            anilistId = null,
            malId = null,
            readingStatus = null,
            addedAt = now,
            lastReadAt = null,
        ).also { webViewSourceRepository.save(it) }
    }

    /** Search and link a MyAnimeList entry to this Browser Source. */
    private fun showMalLinkDialog() {
        if (sourceId == -1L) {
            Snackbar.make(binding.webView, "Save this website as a Browser Source before linking tracking.", Snackbar.LENGTH_LONG).show()
            return
        }
        if (!malRepository.isAuthorized) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Connect MyAnimeList")
                .setMessage("Connect your MyAnimeList account in Tsuki's Scrobbling settings, then return here to link this manga.")
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val queryInput = android.widget.EditText(this).apply {
            hint = "Manga title"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Link MyAnimeList")
            .setView(queryInput)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("Search", null)
            .show()
            .also { dialog ->
                dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                    val query = queryInput.text?.toString().orEmpty().trim()
                    if (query.isBlank()) return@setOnClickListener
                    dialog.dismiss()
                    lifecycleScope.launch {
                        val results = runCatching { malRepository.findManga(query, 0) }.getOrDefault(emptyList())
                        if (results.isEmpty()) {
                            Snackbar.make(binding.webView, "No MyAnimeList matches found.", Snackbar.LENGTH_LONG).show()
                        } else {
                            showMalSearchResults(results)
                        }
                    }
                }
            }
    }

    private fun showMalSearchResults(results: List<ScrobblerManga>) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Choose the matching manga")
            .setItems(results.map { it.name }.toTypedArray()) { _, index ->
                val selected = results[index]
                lifecycleScope.launch {
                    val entry = webViewSourceRepository.getById(sourceId) ?: createTrackingEntry()
                    runCatching {
                        malRepository.createRate(sourceId, selected.id)
                        webViewSourceRepository.save(entry.copy(malId = selected.id.toInt()))
                    }.onSuccess {
                        Snackbar.make(binding.webView, "Linked to MyAnimeList: " + selected.name, Snackbar.LENGTH_LONG).show()
                    }.onFailure {
                        Snackbar.make(binding.webView, "Could not link MyAnimeList: " + (it.message ?: "unknown error"), Snackbar.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** Shows only the site identity until the user focuses the address/search field. */
    private fun formatAddress(url: String): String {
        if (url.isBlank()) return ""
        val uri = Uri.parse(url)
        return uri.host ?: url
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
        // Direct-URL sessions do not have a persisted CustomMangaSource ID,
        // so the reader cannot reconstruct their source after navigation.
        if (sourceId != -1L && webViewSettings.isOpenInReaderPromptEnabled) {
            binding.fabOpenReader.show()
        }
    }

    // ── Reader integration ────────────────────────────────────────────────────

    private fun openInNativeReader() {
        lifecycleScope.launch {
            binding.fabOpenReader.isEnabled = false
            val imageUrls = try {
                collectChapterImagesWithoutScrolling()
            } finally {
                binding.fabOpenReader.isEnabled = true
            }
            if (imageUrls.isEmpty()) {
                Toast.makeText(this@BrowserSourceActivity, R.string.webview_no_images_found, Toast.LENGTH_SHORT).show()
                return@launch
            }

            val chapterUrl = binding.webView.url ?: return@launch
            val pageTitle  = currentPageTitle.ifBlank { binding.webView.title ?: "Chapter" }

            // Stable ID derived from the chapter URL
            val chapterId = chapterUrl.hashCode().toLong()
            val mangaId   = (baseUrl + "_browser").hashCode().toLong()

            // Look up the CustomSource so we can pass the correct MangaSource
            val customSource = customSourcesRepository.findById(sourceId)
                ?.let { CustomMangaSource(it) }
            if (customSource == null) {
                Toast.makeText(
                    this@BrowserSourceActivity,
                    "Save this website as a Browser Source to open chapters in Tsuki Reader.",
                    Toast.LENGTH_LONG,
                ).show()
                return@launch
            }

            // Capture request context per image URL. Some CDNs require cookies
            // scoped to their own host, not just the manga website's host.
            val cookieManager = CookieManager.getInstance()
            val userAgent = binding.webView.settings.userAgentString
            val headersByPageId = LinkedHashMap<Long, Map<String, String>>()

            // Convert image URLs → MangaPage objects
            val pages: List<MangaPage> = imageUrls.mapIndexed { i, url ->
                val pageId = chapterId * 1000L + i
                val headers = linkedMapOf(
                    "Referer" to chapterUrl,
                    "User-Agent" to userAgent,
                )
                cookieManager.getCookie(url)?.takeIf { it.isNotBlank() }?.let {
                    headers["Cookie"] = it
                }
                headersByPageId[pageId] = headers
                MangaPage(
                    id      = pageId,
                    url     = url,
                    preview = null,
                    source  = customSource,
                )
            }

            // Stash pages and their browser-session headers for PageLoader.
            BrowserSourcePageStore.put(chapterId, pages, headersByPageId)

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

            // Keep the chapter outside ParcelableManga too: its parceler intentionally
            // omits chapters, and ReaderActivity reloads details through the source repo.
            BrowserSourcePageStore.putChapter(mangaId, chapter)

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
            val readerIntent = ReaderIntent.Builder(this@BrowserSourceActivity)
                .manga(manga)
                .state(ReaderState(chapterId, 0, 0))
                .incognito(false)
                .build()
            router.openReader(readerIntent)
        }
    }

    /**
     * Collects rendered and lazy-load image URLs without moving the user's page.
     * Lazy-load attributes are included by the DOM collector; setting existing
     * lazy images to eager also helps sites using native loading="lazy".
     */
    private suspend fun collectChapterImagesWithoutScrolling(): List<String> {
        // MangaFire already requests the full ordered page list through its API.
        // Reuse the tokenized request URL observed by this WebView instead of
        // scraping only the currently-rendered/lazy-loaded image elements.
        evaluateJavascriptAwait(BrowserSourceChapterDetector.COLLECT_MANGAFIRE_API_PAGES_JS)
        repeat(MANGAFIRE_API_POLL_ATTEMPTS) {
            delay(MANGAFIRE_API_POLL_DELAY_MS)
            val rawResult = evaluateJavascriptAwait(
                "(function(){return window.__tsukiMangaFireApiPages || '__pending__';})()"
            )
            val result = unescapeJsString(rawResult)
            if (result != "__pending__") {
                val mangaFirePages = BrowserSourceChapterDetector.parseImageUrls(
                    org.json.JSONArray(result).toString()
                )
                if (mangaFirePages.isNotEmpty()) return mangaFirePages
                break
            }
        }

        val collected = LinkedHashSet<String>()

        evaluateJavascriptAwait(
            "document.querySelectorAll('img[loading=lazy]').forEach(function(img){img.loading='eager';});"
        )
        delay(EXTRACTION_SETTLE_DELAY_MS)

        val firstPass = evaluateJavascriptAwait(BrowserSourceChapterDetector.COLLECT_IMAGES_JS)
        collected.addAll(BrowserSourceChapterDetector.parseImageUrls(firstPass))

        // Catch URLs populated asynchronously, without triggering page scrolling.
        delay(EXTRACTION_SETTLE_DELAY_MS)
        val secondPass = evaluateJavascriptAwait(BrowserSourceChapterDetector.COLLECT_IMAGES_JS)
        collected.addAll(BrowserSourceChapterDetector.parseImageUrls(secondPass))

        return collected.toList()
    }

    private suspend fun evaluateJavascriptAwait(script: String): String? =
        suspendCancellableCoroutine { continuation ->
            binding.webView.evaluateJavascript(script) { result ->
                if (continuation.isActive) continuation.resume(result)
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
        private const val EXTRACTION_SETTLE_DELAY_MS = 500L
        private const val MANGAFIRE_API_POLL_ATTEMPTS = 20
        private const val MANGAFIRE_API_POLL_DELAY_MS = 150L

        const val KEY_SOURCE_ID   = "browser_source_id"
        const val KEY_SOURCE_NAME = "browser_source_name"
        const val KEY_BASE_URL    = "browser_source_url"

        /** Extras used when opening a URL directly (not tied to a saved custom source). */
        const val KEY_DIRECT_URL   = "browser_direct_url"
        const val KEY_DIRECT_TITLE = "browser_direct_title"

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

        /**
         * Create an Intent that opens [url] directly in Tsuki's WebView browser
         * without requiring a saved custom source entry.
         *
         * Used by [TsukiBrowser.open] and [AppRouter.browserIntent].
         */
        fun createDirectIntent(
            context: Context,
            url: String,
            title: String?,
        ): Intent = Intent(context, BrowserSourceActivity::class.java).apply {
            putExtra(KEY_DIRECT_URL, url)
            putExtra(KEY_DIRECT_TITLE, title ?: url)
        }
    }
}
