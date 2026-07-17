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

    /** Cloudflare-specific user agent that passes bot fingerprinting. */
    const val CLOUDFLARE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    /** Apply the Cloudflare user agent to the given WebView. */
    fun applyCloudflareUserAgent(webView: WebView) {
        webView.settings.userAgentString = CLOUDFLARE_USER_AGENT
    }

    private val ANTI_DETECTION_JS = """
        (function() {
            try {
                Object.defineProperty(navigator, 'webdriver', {
                    get: () => undefined,
                    configurable: true
                });
                window.chrome = {
                    runtime: {},
                    loadTimes: function() { return {}; },
                    csi: function() { return {}; },
                    app: { isInstalled: false }
                };
                Object.defineProperty(navigator, 'plugins', {
                    get: () => [1, 2, 3, 4, 5],
                    configurable: true
                });
                Object.defineProperty(navigator, 'languages', {
                    get: () => ['en-US', 'en'],
                    configurable: true
                });
                Object.defineProperty(navigator, 'platform', {
                    get: () => 'Linux armv8l',
                    configurable: true
                });
                window.outerHeight = window.innerHeight;
                window.outerWidth = window.innerWidth;
                try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Array; } catch(e) {}
                try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Promise; } catch(e) {}
                try { delete window.cdc_adoQpoasnfa76pfcZLmcfl_Symbol; } catch(e) {}
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
