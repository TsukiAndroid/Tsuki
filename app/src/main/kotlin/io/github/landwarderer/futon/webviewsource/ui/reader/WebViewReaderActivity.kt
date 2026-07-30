package io.github.landwarderer.futon.webviewsource.ui.reader

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.MenuItem
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import io.github.landwarderer.futon.core.db.entity.WebViewSourceEntity
import io.github.landwarderer.futon.databinding.ActivityWebviewReaderBinding
import kotlinx.coroutines.launch

/**
 * Full-screen WebView reader for WebView-as-Source entries.
 *
 * Responsibilities:
 *  - Loads the source's last-read URL (or base URL if never opened)
 *  - Injects [PROGRESS_JS] to report scroll position back via [ProgressJsBridge]
 *  - Restores scroll position on first page load
 *  - Auto-saves progress every 5 s and immediately on pause
 *  - Back button navigates within the WebView before closing the Activity
 *
 * Entry point: [createIntent]
 */
@AndroidEntryPoint
class WebViewReaderActivity : AppCompatActivity() {

    private val viewModel: WebViewReaderViewModel by viewModels()
    private lateinit var binding: ActivityWebviewReaderBinding

    /** Prevents repeat scroll-restore on subsequent page loads. */
    private var scrollRestored = false

    /** Ensures we only trigger the initial URL load once. */
    private var urlLoaded = false

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
        android.R.id.home -> { finish(); true }
        else -> super.onOptionsItemSelected(item)
    }

    // ── Source observation ────────────────────────────────────────────────────

    private fun observeSource() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.source.collect { source ->
                    source ?: return@collect
                    supportActionBar?.title = source.title
                    if (!urlLoaded) {
                        urlLoaded = true
                        loadSource(source)
                    }
                }
            }
        }
    }

    // ── WebView setup ─────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        with(binding.webView) {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true

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
                    restoreScrollIfNeeded()
                }
            }

            webChromeClient = WebChromeClient()
        }
    }

    // ── Load & resume ─────────────────────────────────────────────────────────

    private fun loadSource(source: WebViewSourceEntity) {
        val startUrl = source.lastReadUrl ?: source.baseUrl
        binding.webView.loadUrl(startUrl)
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
