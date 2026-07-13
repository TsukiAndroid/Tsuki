package io.github.landwarderer.futon.core.network.webview

import android.os.Build
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * Shared WebView performance settings applied to every in-app browser surface
 * (BrowserSourceActivity, BaseBrowserActivity / CloudFlareActivity, the
 * VisualRuleBuilder's ElementPickerWebView). Centralized here so a future fix
 * only needs to happen once instead of being copy-pasted per WebView.
 *
 * See AGENTS.md "WebView performance" session for the reasoning behind each flag.
 */
object WebViewPerformanceConfigurator {

    /**
     * Applies caching, rendering, and hardware-acceleration settings. Does NOT
     * touch JavaScript/user-agent/cookie settings — those are already owned by
     * each caller (WebViewSettingsManager, configureForParser, etc.) since they
     * carry feature-specific tradeoffs (e.g. parser bot detection).
     */
    fun applyPerformanceSettings(webView: WebView) {
        with(webView.settings) {
            cacheMode = WebSettings.LOAD_DEFAULT
            loadsImagesAutomatically = true
            blockNetworkImage = false
            defaultTextEncodingName = "UTF-8"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Manga aggregator sites trigger Safe Browsing false-positives
                // constantly (piracy-adjacent domains); disabling it removes a
                // network round-trip Google Safe Browsing does per navigation.
                safeBrowsingEnabled = false
            }

            // Many manga CDNs serve images over http while the page is https.
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            @Suppress("DEPRECATION")
            setRenderPriority(WebSettings.RenderPriority.HIGH)
        }

        // Hardware layer avoids falling back to software rendering, which is
        // the single biggest visible-scroll-jank cause on WebView vs Chrome.
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
    }

    /**
     * Android's default WebView UA embeds a `; wv)` (or trailing ` wv`) token
     * that flags the request as coming from an embedded WebView rather than
     * standalone Chrome. Some sites (and Cloudflare's bot heuristics) serve a
     * degraded/blocked response when they see it. Stripping the token keeps the
     * rest of the fingerprint authentic (real device model, real installed
     * Chrome version) instead of substituting a hardcoded UA string that goes
     * stale the moment Chrome updates.
     */
    fun stripWebViewMarker(userAgent: String): String =
        userAgent.replace(WV_TOKEN_WITH_PAREN, ")").replace(WV_TOKEN_TRAILING, "")

    private val WV_TOKEN_WITH_PAREN = Regex(";\\s*wv\\)")
    private val WV_TOKEN_TRAILING = Regex("\\s*\\bwv\\b")
}
