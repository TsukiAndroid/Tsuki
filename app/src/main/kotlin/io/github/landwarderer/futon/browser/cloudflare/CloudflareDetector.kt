package io.github.landwarderer.futon.browser.cloudflare

/**
 * Strict two-stage Cloudflare interstitial detector.
 *
 * Stage 1 — [recordHttpError]: called from WebViewClient.onReceivedHttpError when the
 * main frame returns 403 or 503. Saves the status code and counts any
 * Cloudflare-specific HTTP response headers as markers.
 *
 * Stage 2 — [analyzeHtml]: called from inside the evaluateJavascript callback in
 * WebViewClient.onPageFinished, after the full page HTML has been retrieved.
 * Combines saved header markers with HTML markers and cookie markers.
 * Returns true **only** when ALL three conditions are satisfied:
 *   1. HTTP status was 403 or 503 (200 = definitely not a CF interstitial)
 *   2. Total marker count ≥ 2 (from headers + HTML + cookies)
 *   3. Page title exactly matches one of the known CF challenge titles
 *
 * Call [reset] in WebViewClient.onPageStarted so stale 403/503 state from
 * the previous page cannot bleed into the HTML analysis of the next page.
 */
class CloudflareDetector {

    private var pendingStatusCode = 0
    private var pendingHeaderMarkers = 0

    /**
     * Call from [android.webkit.WebViewClient.onReceivedHttpError] when
     * [request.isForMainFrame] is true. Returns true if the status code
     * is in the Cloudflare-relevant range so the caller can decide whether
     * to skip expensive processing on other status codes.
     */
    fun recordHttpError(statusCode: Int, headers: Map<String, String>?): Boolean {
        if (statusCode != 403 && statusCode != 503) return false
        pendingStatusCode = statusCode
        pendingHeaderMarkers = countHeaderMarkers(headers)
        return true
    }

    /**
     * Full Cloudflare certainty check. Call inside the [android.webkit.WebView.evaluateJavascript]
     * callback in [android.webkit.WebViewClient.onPageFinished] after the HTML has been obtained.
     *
     * @param html    Full page outer HTML (lowercase conversion happens internally).
     * @param title   Current WebView page title (view.title ?: "").
     * @param cookies Cookie string from CookieManager.getCookie(url) for the current URL.
     * @return true only when we are CERTAIN this is an active Cloudflare challenge page.
     */
    fun analyzeHtml(html: String, title: String, cookies: String): Boolean {
        if (pendingStatusCode == 0) return false
        val lower = html.lowercase()
        val totalMarkers = pendingHeaderMarkers +
            countHtmlMarkers(lower) +
            countCookieMarkers(cookies)
        return totalMarkers >= 2 && isCfTitle(title.trim())
    }

    /**
     * Reset accumulated state. Must be called in
     * [android.webkit.WebViewClient.onPageStarted] so a 403/503 from the
     * previous page cannot contaminate the next page's HTML analysis.
     */
    fun reset() {
        pendingStatusCode = 0
        pendingHeaderMarkers = 0
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private fun countHeaderMarkers(headers: Map<String, String>?): Int {
        if (headers == null) return 0
        val lower = headers.entries.associate { it.key.lowercase() to it.value.lowercase() }
        var count = 0
        if (lower.containsKey("cf-ray")) count++
        if (lower["server"]?.contains("cloudflare") == true) count++
        return count
    }

    private fun countHtmlMarkers(lower: String): Int {
        var count = 0
        if (lower.contains("cdn-cgi/challenge-platform")) count++
        if (lower.contains("_cf_chl_opt")) count++
        if (lower.contains("cf-turnstile")) count++
        if (lower.contains("challenges.cloudflare.com")) count++
        return count
    }

    private fun countCookieMarkers(cookies: String): Int =
        if (cookies.contains("__cf_bm") || cookies.contains("cf_clearance")) 1 else 0

    private fun isCfTitle(title: String): Boolean = title in CF_TITLES

    companion object {
        private val CF_TITLES = setOf(
            "Just a moment...",
            "Attention Required!",
            "One more step",
            "Please Wait...",
            "Security Check",
        )
    }
}
