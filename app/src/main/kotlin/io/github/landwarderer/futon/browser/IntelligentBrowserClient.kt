package io.github.landwarderer.futon.browser

import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.annotation.WorkerThread
import io.github.landwarderer.futon.browser.cloudflare.CloudflareWebView
import io.github.landwarderer.futon.browser.webview.WebViewSettingsManager
import io.github.landwarderer.futon.core.network.webview.adblock.AdBlock

/**
 * Enhanced WebViewClient that adds Cloudflare anti-bot bypass, popup blocking,
 * and custom CSS injection on top of [BrowserClient].
 *
 * AI parser learning has been removed in favour of the Universal Detection flow
 * (CMS fingerprinting via FAB → [BrowserActivity.addCurrentSiteToLibrary]).
 */
class IntelligentBrowserClient(
    callback: BrowserCallback,
    adBlock: AdBlock?,
    private val webViewSettings: WebViewSettingsManager,
) : BrowserClient(callback, adBlock) {

    /**
     * Set to true when the page being loaded contains Cloudflare challenge
     * markers. While true, [shouldInterceptRequest] passes all requests through
     * unmodified so Cloudflare's internal challenge scripts are never blocked.
     */
    @Volatile
    private var isOnCloudflarePage = false

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

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        // Inject anti-bot JS on EVERY page start so Cloudflare challenges never
        // see the WebView automation fingerprint before the page is committed.
        view?.let { CloudflareWebView.injectAntiDetectionJs(it) }
    }

    override fun onPageFinished(webView: WebView, url: String) {
        super.onPageFinished(webView, url)

        // Inject popup blocker
        webView.evaluateJavascript(popupBlockerScript, null)

        // Detect Cloudflare challenge page and update bypass flag.
        webView.evaluateJavascript(CLOUDFLARE_DETECT_JS) { result ->
            val isCf = result?.trim()?.removeSurrounding("\"") == "true"
            if (isCf != isOnCloudflarePage) {
                isOnCloudflarePage = isCf
                if (isCf) {
                    // Switch to Cloudflare-compatible UA and re-inject anti-detection JS.
                    webView.post {
                        CloudflareWebView.applyCloudflareUserAgent(webView)
                        CloudflareWebView.injectAntiDetectionJs(webView)
                    }
                }
            }
        }

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
    }

    @WorkerThread
    override fun shouldInterceptRequest(
        view: WebView?,
        request: WebResourceRequest?,
    ): WebResourceResponse? {
        // On Cloudflare challenge pages, pass EVERY request through unmodified.
        // Any interception — even ad-block — can look like a bot to Cloudflare's
        // challenge verifier and cause the spinner to loop forever.
        if (isOnCloudflarePage) return null
        return super.shouldInterceptRequest(view, request)
    }

    @WorkerThread
    @Deprecated("Deprecated in Java")
    override fun shouldInterceptRequest(
        view: WebView?,
        url: String?,
    ): WebResourceResponse? {
        if (isOnCloudflarePage) return null
        return super.shouldInterceptRequest(view, url)
    }

    companion object {
        /**
         * JavaScript that evaluates to the string "true" when the current page
         * is a Cloudflare browser-challenge interstitial.
         */
        private const val CLOUDFLARE_DETECT_JS = """
            (function() {
                try {
                    var title = (document.title || '').toLowerCase();
                    var body  = document.body ? document.body.innerHTML : '';
                    var isCf  = title.indexOf('just a moment') !== -1 ||
                                body.indexOf('cf-browser-verification') !== -1 ||
                                body.indexOf('cf-ray') !== -1 ||
                                body.toLowerCase().indexOf('checking your browser') !== -1 ||
                                document.querySelector('.cf-browser-verification') !== null ||
                                document.querySelector('#cf-wrapper') !== null;
                    return String(isCf);
                } catch(e) { return 'false'; }
            })()
        """
    }
}
