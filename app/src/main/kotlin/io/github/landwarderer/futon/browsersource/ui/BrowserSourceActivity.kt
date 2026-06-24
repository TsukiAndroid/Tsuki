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
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.browser.webview.WebViewSettingsManager
import io.github.landwarderer.futon.browsersource.data.BrowserSourceChapterDetector
import io.github.landwarderer.futon.browsersource.data.BrowserSourceHistoryTracker
import io.github.landwarderer.futon.browsersource.data.BrowserSourcePageStore
import io.github.landwarderer.futon.browsersource.data.BrowserSourceRepository
import io.github.landwarderer.futon.core.model.parcelable.ParcelableManga
import io.github.landwarderer.futon.core.nav.ReaderIntent
import io.github.landwarderer.futon.core.nav.router
import io.github.landwarderer.futon.core.network.webview.adblock.AdBlock
import io.github.landwarderer.futon.customsource.data.CustomSourcesRepository
import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import io.github.landwarderer.futon.databinding.ActivityBrowserSourceBinding
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

    private lateinit var binding: ActivityBrowserSourceBinding

    private var sourceId: Long = -1L
    private var sourceName: String = ""
    private var baseUrl: String = ""

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

        setupToolbar()
        setupWebView()
        setupFabs()

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

        binding.webView.webChromeClient = ProgressChromeClient(binding.progressBar)

        binding.webView.webViewClient = object : WebViewClient() {

            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest,
            ): WebResourceResponse? {
                if (webViewSettings.isAdBlockEnabled) {
                    if (!adBlock.shouldLoadUrl(request.url.toString(), view.url)) {
                        return WebResourceResponse("text/plain", "utf-8", null)
                    }
                }
                val pageUrl = view.url ?: ""
                if (BrowserSourceChapterDetector.onResourceRequest(pageUrl, request)) {
                    runOnUiThread { maybeShowOpenReaderFab() }
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                url?.let {
                    binding.urlBar.setText(it)
                    BrowserSourceChapterDetector.resetForPage(it)
                }
                binding.progressBar.isVisible = true
                updateNavButtons()
                binding.fabOpenReader.hide()
                binding.fabAddToLibrary.hide()
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
                }
            }
        }
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
        binding.webView.stopLoading()
        binding.webView.destroy()
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
