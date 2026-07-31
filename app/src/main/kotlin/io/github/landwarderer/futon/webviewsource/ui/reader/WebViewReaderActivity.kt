package io.github.landwarderer.futon.webviewsource.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.db.entity.WebViewSourceEntity
import io.github.landwarderer.futon.core.ui.dialog.buildAlertDialog
import io.github.landwarderer.futon.core.ui.dialog.setEditText
import io.github.landwarderer.futon.databinding.ActivityWebviewReaderBinding
import io.github.landwarderer.futon.webviewsource.ui.anilist.LinkAniListSheet
import kotlinx.coroutines.launch

/**
 * Full-screen WebView reader for WebView-as-Source entries.
 *
 * Phase 7 additions:
 *  - Default cleanup CSS hides headers/footers/ads on every page
 *  - Per-source [WebViewSourceEntity.customCss] injected after default CSS
 *  - Immersive fullscreen via [WindowInsetsController] (API 30+) / legacy flags
 *  - Brightness/dim overlay [binding.brightnessOverlay]
 *  - Volume keys mapped to WebView scroll
 *  - Toolbar auto-hides after 3 seconds
 *  - Overflow menu: Link AniList, Custom CSS, Notifications toggle, Open in browser
 */
@AndroidEntryPoint
class WebViewReaderActivity : AppCompatActivity() {

    private val viewModel: WebViewReaderViewModel by viewModels()
    private lateinit var binding: ActivityWebviewReaderBinding

    /** Prevents repeat scroll-restore on subsequent page loads. */
    private var scrollRestored = false

    /** Ensures we only trigger the initial URL load once. */
    private var urlLoaded = false

    private var toolbarVisible = true

    private val webBackCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            if (binding.webView.canGoBack()) {
                binding.webView.goBack()
            } else {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            }
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        binding = ActivityWebviewReaderBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        // Push toolbar down below the status bar
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            binding.toolbar.updatePadding(top = systemBars.top)
            insets
        }

        onBackPressedDispatcher.addCallback(this, webBackCallback)

        setupWebView()
        observeSource()

        // Auto-hide toolbar after 3 seconds
        binding.root.postDelayed({
            if (!isFinishing) toggleToolbar()
        }, 3_000)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.opt_reader, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        val source = viewModel.source.value
        val notifItem = menu.findItem(R.id.action_notifications_toggle)
        if (source != null) {
            notifItem?.title = if (source.notificationsEnabled) {
                getString(R.string.action_notifications_off)
            } else {
                getString(R.string.action_notifications_on)
            }
        }
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onResume() {
        super.onResume()
        binding.webView.onResume()
        viewModel.startAutoSave()
    }

    override fun onPause() {
        viewModel.stopAutoSave()
        viewModel.flushProgress()
        binding.webView.onPause()
        super.onPause()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        android.R.id.home -> {
            finish()
            true
        }
        R.id.action_link_anilist -> {
            val source = viewModel.source.value ?: return true
            LinkAniListSheet.newInstance(source.id, source.title)
                .show(supportFragmentManager, LinkAniListSheet.TAG)
            true
        }
        R.id.action_custom_css -> {
            showCustomCssDialog()
            true
        }
        R.id.action_notifications_toggle -> {
            viewModel.toggleNotifications()
            invalidateOptionsMenu()
            true
        }
        R.id.action_open_in_browser -> {
            val url = viewModel.currentUrlForBrowser()
            if (url != null) {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            }
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    // Volume keys scroll the WebView
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val scrollAmount = binding.webView.height / 3
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                binding.webView.scrollBy(0, scrollAmount)
                true
            }
            KeyEvent.KEYCODE_VOLUME_UP -> {
                binding.webView.scrollBy(0, -scrollAmount)
                true
            }
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ── WebView setup ─────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true

            addJavascriptInterface(
                ProgressJsBridge { percent -> viewModel.onScrollChanged(percent) },
                "TsukiBridge",
            )

            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    viewModel.onUrlChanged(url)
                }

                override fun onPageFinished(view: WebView, url: String) {
                    evaluateJavascript(PROGRESS_JS, null)
                    injectCss(this@apply, DEFAULT_CLEANUP_CSS)
                    viewModel.source.value?.customCss?.takeIf { it.isNotBlank() }?.let { css ->
                        injectCss(this@apply, css)
                    }
                    restoreScrollIfNeeded()
                }
            }

            webChromeClient = WebChromeClient()
        }
    }



    // ── Toolbar toggle ────────────────────────────────────────────────────────

    private fun toggleToolbar() {
        toolbarVisible = !toolbarVisible
        binding.toolbar.isVisible = toolbarVisible
        if (toolbarVisible) {
            showSystemBars()
        } else {
            hideSystemBars()
        }
    }

    // ── Immersive fullscreen ──────────────────────────────────────────────────

    @Suppress("DEPRECATION")
    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsets.Type.systemBars())
            }
        } else {
            window.decorView.systemUiVisibility = (
                android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                    or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                )
        }
    }

    @Suppress("DEPRECATION")
    private fun showSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.show(WindowInsets.Type.systemBars())
        } else {
            window.decorView.systemUiVisibility = android.view.View.SYSTEM_UI_FLAG_VISIBLE
        }
    }

    // ── Custom CSS dialog ─────────────────────────────────────────────────────

    private fun showCustomCssDialog() {
        val source = viewModel.source.value ?: return
        val ctx = this
        val dialog = buildAlertDialog(ctx, isCentered = true) {
            setTitle(getString(R.string.action_custom_css))
            setMessage(getString(R.string.custom_css_hint))
            val et = setEditText(
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                singleLine = false,
            )
            et.setText(source.customCss.orEmpty())
            et.hint = "/* Example: div.chapter-warning { display: none !important; } */"
            et.minLines = 5
            setNegativeButton(android.R.string.cancel, null)
            setPositiveButton(android.R.string.ok) { _, _ ->
                val css = et.text?.toString().orEmpty().trim().takeIf { it.isNotBlank() }
                viewModel.saveCustomCss(css)
            }
        }
        dialog.show()
    }

    // ── Load & resume ─────────────────────────────────────────────────────────

    private fun loadSource(source: WebViewSourceEntity) {
        if (urlLoaded) return
        urlLoaded = true
        // Notifications pass a specific chapter URL via "start_url"; use it if present.
        val overrideUrl = intent.getStringExtra("start_url")
        val startUrl = overrideUrl ?: source.lastReadUrl ?: source.baseUrl
        binding.webView.loadUrl(startUrl)
        // Update toolbar subtitle
        supportActionBar?.title = source.title
    }

    private fun observeSource() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.source.collect { source ->
                    if (source != null && !urlLoaded) {
                        loadSource(source)
                    }
                    // Keep toolbar title in sync with chapter info
                    if (source != null) {
                        val chapterStr = viewModel.currentChapter
                            ?.let { ch ->
                                val n = if (ch == ch.toLong().toFloat()) ch.toLong().toString()
                                else ch.toString()
                                " · Ch. $n"
                            }.orEmpty()
                        supportActionBar?.title = source.title + chapterStr
                    }
                }
            }
        }
    }

    private fun restoreScrollIfNeeded() {
        if (scrollRestored) return
        val source = viewModel.source.value ?: return
        if (source.lastReadScrollPercent > 0.01f) {
            val percent = (source.lastReadScrollPercent * 100).toInt()
            binding.webView.evaluateJavascript(
                "window.scrollTo(0, document.documentElement.scrollHeight * $percent / 100);",
                null,
            )
            scrollRestored = true
        }
    }

    // ── Factory ───────────────────────────────────────────────────────────────

    companion object {
        fun createIntent(context: Context, sourceId: Long): Intent =
            Intent(context, WebViewReaderActivity::class.java)
                .putExtra("source_id", sourceId)
    }
}
