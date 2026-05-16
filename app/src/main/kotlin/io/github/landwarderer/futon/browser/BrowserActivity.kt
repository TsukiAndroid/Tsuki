package io.github.landwarderer.futon.browser

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContract
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.browser.learning.AiParserGenerator
import io.github.landwarderer.futon.browser.learning.LearningSession
import io.github.landwarderer.futon.browser.learning.PageType
import io.github.landwarderer.futon.browser.webview.ChapterDetector
import io.github.landwarderer.futon.browser.webview.WebViewSettingsManager
import io.github.landwarderer.futon.core.exceptions.InteractiveActionRequiredException
import io.github.landwarderer.futon.core.nav.AppRouter
import io.github.landwarderer.futon.core.nav.router
import io.github.landwarderer.futon.core.parser.ParserMangaRepository
import io.github.landwarderer.futon.core.util.ext.getDisplayMessage
import io.github.landwarderer.futon.core.util.ext.printStackTraceDebug
import io.github.landwarderer.futon.customsource.data.CustomSourcesRepository
import io.github.landwarderer.futon.customsource.domain.CustomMangaSource
import io.github.landwarderer.futon.customsource.data.CmsTypeDetector
import io.github.landwarderer.futon.customsource.domain.CustomSource
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.parsers.model.MangaSource
import java.net.URI
import javax.inject.Inject

@AndroidEntryPoint
class BrowserActivity : BaseBrowserActivity() {

    @Inject
    lateinit var customSourcesRepository: CustomSourcesRepository

    @Inject
    lateinit var webViewSettings: WebViewSettingsManager

    private var customSourceId: Long? = null
    private val learningSession = LearningSession()
    private val aiParserGenerator = AiParserGenerator()
    private var generatedParserJson: String? = null
    private var isBannerDismissed = false

    override fun onCreate2(savedInstanceState: Bundle?, source: MangaSource, repository: ParserMangaRepository?) {
        setDisplayHomeAsUp(isEnabled = true, showUpAsClose = true)

        // Use IntelligentBrowserClient for all enhanced features
        viewBinding.webView.webViewClient = IntelligentBrowserClient(
            callback = this,
            adBlock = adBlock,
            webViewSettings = webViewSettings,
            learningSession = learningSession,
            onPageClassified = { pageType, url -> onPageClassified(pageType, url) },
            onNewLearningData = { onNewLearningData() },
        )

        // Apply per-session JS + UA settings
        viewBinding.webView.settings.javaScriptEnabled = webViewSettings.isJavaScriptEnabled
        val resolvedUa = webViewSettings.resolvedUserAgent()
        if (resolvedUa != null) {
            viewBinding.webView.settings.userAgentString = resolvedUa
        }

        customSourceId = intent?.getStringExtra(AppRouter.KEY_SOURCE)
            ?.let { CustomMangaSource.extractId(it) }

        setupLearningBanner()
        setupFabs()

        lifecycleScope.launch {
            try {
                proxyProvider.applyWebViewConfig()
            } catch (e: Exception) {
                e.printStackTraceDebug("BrowserActivity::onCreate2")
                Snackbar.make(viewBinding.webView, e.getDisplayMessage(resources), Snackbar.LENGTH_LONG).show()
            }
            if (savedInstanceState == null) {
                val baseUrl = intent?.dataString
                if (baseUrl.isNullOrEmpty()) {
                    finishAfterTransition()
                } else {
                    val resumeUrl = customSourceId
                        ?.let { customSourcesRepository.getLastUrl(it) }
                        ?.takeIf { it.isNotEmpty() }
                        ?: baseUrl
                    onTitleChanged(
                        intent?.getStringExtra(AppRouter.KEY_TITLE) ?: getString(R.string.loading_),
                        resumeUrl,
                    )
                    viewBinding.webView.loadUrl(resumeUrl)
                }
            }
        }
    }

    // ── Learning Banner ────────────────────────────────────────────────────────

    private fun setupLearningBanner() {
        viewBinding.learningBannerDismiss.setOnClickListener {
            isBannerDismissed = true
            viewBinding.learningBanner.isVisible = false
        }

        viewBinding.learningBanner.setOnClickListener {
            val json = generatedParserJson
            if (json != null) showParserCreationDialog(json)
        }

        updateLearningBanner()
    }

    private fun updateLearningBanner() {
        if (isBannerDismissed || !webViewSettings.isAiParserLearningEnabled ||
            !webViewSettings.isLearningBannerVisible
        ) {
            viewBinding.learningBanner.isVisible = false
            return
        }

        val captured = learningSession.capturedTypes
        val hasJson = generatedParserJson != null

        val message = when {
            hasJson -> getString(R.string.webview_parser_ready)
            PageType.MANGA_LIST !in captured -> getString(R.string.webview_learning_msg_0)
            PageType.MANGA_DETAIL !in captured -> getString(R.string.webview_learning_msg_1)
            PageType.CHAPTER_READER !in captured -> getString(R.string.webview_learning_msg_2)
            else -> getString(R.string.webview_learning_generating)
        }
        viewBinding.learningBannerMessage.text = message

        val listCheck = if (PageType.MANGA_LIST in captured) "✓" else "○"
        val detailCheck = if (PageType.MANGA_DETAIL in captured) "✓" else "○"
        val chapterCheck = if (PageType.CHAPTER_READER in captured) "✓" else "○"
        viewBinding.checkList.text =
            "[$listCheck ${getString(R.string.webview_checklist_list)}] " +
            "[$detailCheck ${getString(R.string.webview_checklist_detail)}] " +
            "[$chapterCheck ${getString(R.string.webview_checklist_chapter)}]"

        viewBinding.learningBanner.isVisible = true
    }

    private fun onPageClassified(pageType: PageType, url: String) {
        runOnUiThread { updateLearningBanner() }
    }

    private fun onNewLearningData() {
        if (!webViewSettings.isAiParserLearningEnabled) return
        lifecycleScope.launch {
            val json = withContext(Dispatchers.IO) {
                runCatching {
                    aiParserGenerator.generate(
                        session = learningSession,
                        geminiApiKey = webViewSettings.geminiApiKey.takeIf { it.isNotBlank() },
                    )
                }.getOrNull()
            }
            if (json != null) {
                generatedParserJson = json
                updateLearningBanner()
            }
        }
    }

    private fun showParserCreationDialog(parserJson: String) {
        val domain = learningSession.domain
        val siteName = learningSession.siteName.ifBlank {
            domain.removePrefix("www.").substringBefore(".")
                .replaceFirstChar { it.uppercase() }
        }

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(R.string.webview_parser_sheet_title)
            .setMessage("Site: $siteName\nDomain: $domain\n\nParser generated. Add it as a custom source?")
            .setPositiveButton(R.string.webview_parser_add_source) { _, _ ->
                saveParserAsSource(siteName, parserJson)
            }
            .setNeutralButton(R.string.webview_parser_dismiss, null)
            .show()
    }

    private fun saveParserAsSource(name: String, parserJson: String) {
        val currentUrl = viewBinding.webView.url ?: return
        val baseUrl = runCatching {
            val uri = URI(currentUrl)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(currentUrl)

        val newSource = CustomSource(
            id = CustomSourcesRepository.generateId(),
            name = name,
            baseUrl = baseUrl,
            type = CustomSourceType.CUSTOM_TEMPLATE,
        )
        customSourcesRepository.add(newSource)
        generatedParserJson = null
        learningSession.reset()
        updateLearningBanner()
        Snackbar.make(viewBinding.webView, R.string.webview_source_added, Snackbar.LENGTH_SHORT).show()
    }

    // ── FABs ───────────────────────────────────────────────────────────────────

    private fun setupFabs() {
        viewBinding.fabOpenReader.setOnClickListener { openInNativeReader() }
        viewBinding.fabAddToLibrary.setOnClickListener { addCurrentSiteToLibrary() }
    }

    private fun showHideFabs(url: String) {
        if (webViewSettings.isAutoDetectChapterEnabled &&
            webViewSettings.isOpenInReaderPromptEnabled &&
            ChapterDetector.isChapterUrl(url)
        ) {
            viewBinding.fabOpenReader.show()
        } else {
            viewBinding.fabOpenReader.hide()
        }

        if (webViewSettings.isAutoDetectDetailEnabled &&
            webViewSettings.isAddToLibraryPromptEnabled &&
            ChapterDetector.isDetailUrl(url)
        ) {
            viewBinding.fabAddToLibrary.show()
        } else {
            viewBinding.fabAddToLibrary.hide()
        }
    }

    private fun openInNativeReader() {
        viewBinding.webView.evaluateJavascript(ChapterDetector.COLLECT_IMAGES_JS) { jsonResult ->
            val urls = ChapterDetector.parseImageUrls(jsonResult)
            if (urls.isEmpty()) {
                Toast.makeText(this, R.string.webview_no_images_found, Toast.LENGTH_SHORT).show()
                return@evaluateJavascript
            }
            Toast.makeText(this, R.string.webview_reader_opened, Toast.LENGTH_SHORT).show()
        }
    }

    private fun addCurrentSiteToLibrary() {
        val currentUrl = viewBinding.webView.url ?: return
        val baseUrl = runCatching {
            val uri = URI(currentUrl)
            "${uri.scheme}://${uri.host}"
        }.getOrDefault(currentUrl)

        val title = viewBinding.webView.title?.takeIf { it.isNotBlank() }
            ?: baseUrl.removePrefix("https://").removePrefix("www.")

        // Check if already added
        val existing = customSourcesRepository.findByUrl(baseUrl)
        if (existing != null && existing.type != CustomSourceType.WEBVIEW) {
            // Already a proper parseable source — nothing to do
            Snackbar.make(
                viewBinding.webView,
                getString(R.string.webview_source_already_added, existing.type.label),
                Snackbar.LENGTH_SHORT,
            ).show()
            return
        }

        // Show detecting state on the FAB
        viewBinding.fabAddToLibrary.text = getString(R.string.webview_detecting)
        viewBinding.fabAddToLibrary.isEnabled = false

        lifecycleScope.launch {
            // Run CMS fingerprinting in background (makes network calls)
            val detectedType = withContext(Dispatchers.IO) {
                runCatching { CmsTypeDetector.detect(baseUrl) }
                    .getOrElse { CustomSourceType.WEBVIEW }
            }

            if (existing != null && existing.type == CustomSourceType.WEBVIEW) {
                // Upgrade the previously-saved WEBVIEW shell to its real parser type
                customSourcesRepository.update(existing.copy(type = detectedType))
                Snackbar.make(
                    viewBinding.webView,
                    getString(R.string.webview_source_upgraded, detectedType.label),
                    Snackbar.LENGTH_LONG,
                ).show()
            } else {
                // Add brand-new source with the real detected type
                val source = CustomSource(
                    id = CustomSourcesRepository.generateId(),
                    name = title,
                    baseUrl = baseUrl,
                    type = detectedType,
                )
                customSourcesRepository.add(source)
                val msg = if (detectedType == CustomSourceType.WEBVIEW)
                    getString(R.string.webview_source_added)
                else
                    getString(R.string.webview_source_added_as, detectedType.label)
                Snackbar.make(viewBinding.webView, msg, Snackbar.LENGTH_LONG).show()
            }

            viewBinding.fabAddToLibrary.hide()
            // Restore FAB for future use
            viewBinding.fabAddToLibrary.text = getString(R.string.webview_add_to_library)
            viewBinding.fabAddToLibrary.isEnabled = true
        }
    }

    // ── Title / URL changes ────────────────────────────────────────────────────

    override fun onTitleChanged(title: CharSequence, subtitle: CharSequence?) {
        super.onTitleChanged(title, subtitle)
        val url = subtitle?.toString()
        if (!url.isNullOrEmpty()) {
            customSourceId?.let { customSourcesRepository.saveLastUrl(it, url) }
            showHideFabs(url)
        }
    }

    override fun onStop() {
        if (hasViewBinding()) {
            val currentUrl = viewBinding.webView.url
            if (!currentUrl.isNullOrEmpty()) {
                customSourceId?.let { customSourcesRepository.saveLastUrl(it, currentUrl) }
            }
        }
        super.onStop()
    }

    // ── Toolbar Menu ───────────────────────────────────────────────────────────

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.opt_browser, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            viewBinding.webView.stopLoading()
            finishAfterTransition()
            true
        }

        R.id.action_browser -> {
            if (!router.openExternalBrowser(viewBinding.webView.url.orEmpty(), item.title)) {
                Snackbar.make(viewBinding.webView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
            }
            true
        }

        R.id.action_adblock_toggle -> {
            val newState = !webViewSettings.isAdBlockEnabled
            webViewSettings.isAdBlockEnabled = newState
            val msgRes = if (newState) R.string.webview_adblock_toggled_on else R.string.webview_adblock_toggled_off
            Snackbar.make(viewBinding.webView, msgRes, Snackbar.LENGTH_SHORT).show()
            true
        }

        R.id.action_js_toggle -> {
            val newState = !webViewSettings.isJavaScriptEnabled
            webViewSettings.isJavaScriptEnabled = newState
            viewBinding.webView.settings.javaScriptEnabled = newState
            viewBinding.webView.reload()
            val msgRes = if (newState) R.string.webview_js_toggled_on else R.string.webview_js_toggled_off
            Snackbar.make(viewBinding.webView, msgRes, Snackbar.LENGTH_SHORT).show()
            true
        }

        R.id.action_desktop_mode -> {
            val newState = !webViewSettings.isDesktopMode
            webViewSettings.isDesktopMode = newState
            val newUa = webViewSettings.resolvedUserAgent()
            viewBinding.webView.settings.userAgentString = newUa
            viewBinding.webView.reload()
            val msgRes = if (newState) R.string.webview_desktop_on else R.string.webview_desktop_off
            Snackbar.make(viewBinding.webView, msgRes, Snackbar.LENGTH_SHORT).show()
            true
        }

        R.id.action_add_as_source -> {
            addCurrentSiteToLibrary()
            true
        }

        R.id.action_webview_settings -> {
            router.openSettings()
            true
        }

        R.id.action_share -> {
            val url = viewBinding.webView.url
            if (!url.isNullOrEmpty()) {
                router.openExternalBrowser(url, getString(R.string.share))
            }
            true
        }

        R.id.action_download_images -> {
            viewBinding.webView.evaluateJavascript(ChapterDetector.COLLECT_IMAGES_JS) { jsonResult ->
                val urls = ChapterDetector.parseImageUrls(jsonResult)
                Snackbar.make(
                    viewBinding.webView,
                    if (urls.isEmpty()) getString(R.string.webview_no_images_found)
                    else "Found ${urls.size} images",
                    Snackbar.LENGTH_LONG,
                ).show()
            }
            true
        }

        else -> super.onOptionsItemSelected(item)
    }

    class Contract : ActivityResultContract<InteractiveActionRequiredException, Unit>() {
        override fun createIntent(
            context: Context,
            input: InteractiveActionRequiredException,
        ): Intent = AppRouter.browserIntent(
            context = context,
            url = input.url,
            source = input.source,
            title = null,
        )

        override fun parseResult(resultCode: Int, intent: Intent?): Unit = Unit
    }

    companion object {
        const val TAG = "BrowserActivity"
    }
}
