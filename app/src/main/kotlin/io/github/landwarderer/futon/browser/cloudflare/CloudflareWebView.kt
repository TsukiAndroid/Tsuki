package io.github.landwarderer.futon.browser.cloudflare

import android.webkit.WebView
import io.github.landwarderer.futon.core.network.webview.WebViewPerformanceConfigurator

/**
 * Cloudflare-specific WebView setup, layered on top of what
 * [io.github.landwarderer.futon.core.util.ext.configureForParser] already
 * configures in [io.github.landwarderer.futon.browser.BaseBrowserActivity].
 *
 * This exists as its own file (rather than being inlined into
 * [CloudFlareActivity]) because the settings/JS here are specific to *passing
 * a Cloudflare challenge*, which is a distinct concern from "browsing a manga
 * site" — keeping it separate means a future change to one doesn't risk
 * silently breaking the other.
 */
object CloudflareWebView {

    /**
     * Applies the WebView settings Cloudflare's challenge page actually needs
     * beyond the parser defaults: it opens `window.open` popups for some
     * challenge variants, and it reads `document.hasFocus()` /
     * `navigator.plugins` as part of its bot heuristic, so file/content access
     * being locked down (fine for parsing manga pages) can look suspicious here.
     */
    fun configure(webView: WebView) {
        WebViewPerformanceConfigurator.applyPerformanceSettings(webView)
        with(webView.settings) {
            javaScriptCanOpenWindowsAutomatically = true
            setSupportMultipleWindows(false)
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            // configureForParser() sets this false for parser scraping; Cloudflare's
            // challenge sometimes probes local storage/content availability as part
            // of its automation checks, so relax it specifically here.
            allowContentAccess = true
        }
    }

    /**
     * Best-effort masking of the most common headless/automation signals that
     * Cloudflare (and similar bot-management scripts) check for. This can't make
     * a WebView indistinguishable from real Chrome, but it removes the handful
     * of dead giveaways that WebView leaves on by default and that a real device
     * running Chrome would never expose.
     */
    fun injectAntiDetectionJs(webView: WebView) {
        webView.evaluateJavascript(ANTI_DETECTION_JS, null)
    }

    private val ANTI_DETECTION_JS = """
        (function() {
            try {
                Object.defineProperty(navigator, 'webdriver', { get: () => undefined });
                if (!window.chrome) {
                    window.chrome = { runtime: {} };
                }
                if (navigator.plugins && navigator.plugins.length === 0) {
                    Object.defineProperty(navigator, 'plugins', {
                        get: () => [1, 2, 3, 4, 5],
                    });
                }
                if (navigator.languages && navigator.languages.length === 0) {
                    Object.defineProperty(navigator, 'languages', {
                        get: () => ['en-US', 'en'],
                    });
                }
                const originalQuery = window.navigator.permissions && window.navigator.permissions.query;
                if (originalQuery) {
                    window.navigator.permissions.query = (parameters) => (
                        parameters.name === 'notifications'
                            ? Promise.resolve({ state: Notification.permission })
                            : originalQuery(parameters)
                    );
                }
            } catch (e) {
                // Best-effort; never break page load over this.
            }
        })();
    """.trimIndent()
}
