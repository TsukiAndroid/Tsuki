package io.github.landwarderer.futon.customsource.ui.visualpicker

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.landwarderer.futon.core.network.webview.adblock.AdBlock

/**
 * A [WebView] subclass that injects element-picking JavaScript on every page load.
 *
 * Communicates selected elements back to Kotlin via the JS interface
 * `window.TsukiPicker`. Highlights are done entirely in JS/CSS; this class
 * only handles the injection and the interface bridge.
 *
 * @param onElementSelected Called on the main thread when the user taps an element.
 * @param onPageStarted     Called when a new page navigation begins (use to re-arm step hints).
 * @param adBlock           Optional AdBlock instance — null means no ad filtering.
 */
@SuppressLint("SetJavaScriptEnabled")
class ElementPickerWebView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    private val onElementSelected: (info: ElementInfo) -> Unit = {},
    private val onPageStarted: (url: String) -> Unit = {},
    private val adBlock: AdBlock? = null,
) : WebView(context, attrs, defStyleAttr) {

    /**
     * All data about a tapped element, decoded from the JS interface call.
     */
    data class ElementInfo(
        val selector: String,
        val tagName: String,
        val innerText: String,
        val outerHtml: String,
        val siblingCount: Int,
        val warning: Warning,
        val autoCardSelector: String,
        val autoCardCount: Int,
    )

    enum class Warning {
        NONE,
        WARN_NAV,
        WARN_LOGO,
        WARN_AD,
    }

    private var pickerJs: String = ""

    init {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(true)
            builtInZoomControls = true
            displayZoomControls = false
            userAgentString = BROWSER_UA
        }

        // Load JS from assets once
        pickerJs = context.assets.open("element_picker.js")
            .bufferedReader().use { it.readText() }

        addJavascriptInterface(JsBridge(), "TsukiPicker")

        webViewClient = PickerWebViewClient()
    }

    // ── JS Interface bridge ───────────────────────────────────────────────────

    private inner class JsBridge {
        /**
         * Called from JavaScript when the user taps an element.
         * Note: always called on a background thread; post to main.
         */
        @JavascriptInterface
        fun onElementSelected(
            selector: String,
            tagName: String,
            innerText: String,
            outerHtml: String,
            siblingCount: Int,
            warningStr: String,
            autoCardSelector: String,
            autoCardCount: Int,
        ) {
            val warning = when (warningStr) {
                "WARN_NAV"  -> Warning.WARN_NAV
                "WARN_LOGO" -> Warning.WARN_LOGO
                "WARN_AD"   -> Warning.WARN_AD
                else        -> Warning.NONE
            }
            val info = ElementInfo(
                selector         = selector,
                tagName          = tagName,
                innerText        = innerText,
                outerHtml        = outerHtml,
                siblingCount     = siblingCount,
                warning          = warning,
                autoCardSelector = autoCardSelector,
                autoCardCount    = autoCardCount,
            )
            handler.post { onElementSelected(info) }
        }
    }

    // ── WebViewClient ─────────────────────────────────────────────────────────

    private inner class PickerWebViewClient : WebViewClient() {

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            // Inject picker JS after every page load (including navigations)
            view?.evaluateJavascript(pickerJs, null)
        }

        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            url?.let { onPageStarted(it) }
        }

        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
            val adBlockInstance = adBlock ?: return null
            val url = request.url.toString()
            return if (!adBlockInstance.shouldLoadUrl(url, view.url)) {
                WebResourceResponse("text/plain", "utf-8", null)
            } else {
                null
            }
        }
    }

    // ── Public helpers ────────────────────────────────────────────────────────

    /** Clear all picker highlights without changing step. */
    fun clearHighlights() {
        evaluateJavascript("if(window.tsukiClearHighlights) tsukiClearHighlights();", null)
    }

    /** Highlight all elements matching [selector] and return match count via callback. */
    fun highlightSelector(selector: String, callback: (Int) -> Unit) {
        val escaped = selector.replace("\"", "\\\"")
        evaluateJavascript("if(window.tsukiHighlightSelector) tsukiHighlightSelector(\"$escaped\"); else 0;") { result ->
            callback(result?.toIntOrNull() ?: 0)
        }
    }

    companion object {
        private const val BROWSER_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/124.0.0.0 Mobile Safari/537.36"
    }
}
