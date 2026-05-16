package io.github.landwarderer.futon.browser

import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.annotation.WorkerThread
import io.github.landwarderer.futon.browser.learning.LearningSession
import io.github.landwarderer.futon.browser.learning.PageClassifier
import io.github.landwarderer.futon.browser.learning.PageType
import io.github.landwarderer.futon.browser.webview.WebViewSettingsManager
import io.github.landwarderer.futon.core.network.webview.adblock.AdBlock
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Enhanced WebViewClient that adds AI parser learning, popup blocking support,
 * custom domain blocking, and page classification to the existing [BrowserClient].
 *
 * Existing ad-block and history/title callbacks are preserved.
 */
class IntelligentBrowserClient(
    callback: BrowserCallback,
    adBlock: AdBlock?,
    private val webViewSettings: WebViewSettingsManager,
    private val learningSession: LearningSession,
    private val onPageClassified: (PageType, String) -> Unit,
    private val onNewLearningData: () -> Unit,
) : BrowserClient(callback, adBlock) {

    /** Popup-blocker JS injected once per page load. */
    private val popupBlockerScript = """
        (function(){
            window._tsuki_popups_blocked = 0;
            var _orig_open = window.open;
            window.open = function(url, name, specs){
                window._tsuki_popups_blocked = (window._tsuki_popups_blocked || 0) + 1;
                return null;
            };
        })();
    """.trimIndent()

    /** Custom CSS injection script. */
    private fun buildCssScript(css: String): String = """
        (function(){
            var s = document.createElement('style');
            s.textContent = `$css`;
            document.head.appendChild(s);
        })();
    """.trimIndent()

    override fun onPageFinished(webView: WebView, url: String) {
        super.onPageFinished(webView, url)

        // Inject popup blocker
        webView.evaluateJavascript(popupBlockerScript, null)

        // Inject custom CSS
        val domain = runCatching {
            java.net.URI(url).host ?: ""
        }.getOrDefault("")
        val globalCss = webViewSettings.globalCustomCss
        val siteCss = webViewSettings.getSiteCustomCss(domain)
        val combinedCss = buildString {
            if (globalCss.isNotBlank()) appendLine(globalCss)
            if (siteCss.isNotBlank()) appendLine(siteCss)
        }.trim()
        if (combinedCss.isNotBlank()) {
            webView.evaluateJavascript(buildCssScript(combinedCss), null)
        }

        // Capture page HTML for learning if enabled
        if (webViewSettings.isAiParserLearningEnabled) {
            webView.evaluateJavascript("document.documentElement.outerHTML") { html ->
                if (!html.isNullOrBlank() && html != "null") {
                    val cleanHtml = html.removePrefix("\"").removeSuffix("\"")
                        .replace("\\u003C", "<")
                        .replace("\\u003E", ">")
                        .replace("\\\"", "\"")
                        .replace("\\n", "\n")
                        .replace("\\t", "\t")
                    processPageForLearning(url, cleanHtml)
                }
            }
        }
    }

    @WorkerThread
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        if (request == null) return super.shouldInterceptRequest(view, request)
        val url = request.url.toString()

        // Check custom blocked/whitelisted domains
        if (webViewSettings.isAdBlockEnabled) {
            val host = request.url.host ?: ""
            val whitelisted = webViewSettings.whitelistedDomains
            val customBlocked = webViewSettings.customBlockedDomains
            if (whitelisted.none { host.endsWith(it) } &&
                customBlocked.any { host.endsWith(it) }
            ) {
                webViewSettings.incrementBlockedCount()
                return emptyWebResponse()
            }
        }

        return super.shouldInterceptRequest(view, request)
    }

    private fun processPageForLearning(url: String, html: String) {
        val pageType = PageClassifier.classify(url, html)
        if (pageType != PageType.UNKNOWN) {
            learningSession.capture(pageType, url, html)
            onPageClassified(pageType, url)
            if (learningSession.isReadyForGeneration) {
                onNewLearningData()
            }
        }
    }

    private fun emptyWebResponse() =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(byteArrayOf()))

    companion object {
        private const val TAG = "IntelligentBrowserClient"
    }
}
