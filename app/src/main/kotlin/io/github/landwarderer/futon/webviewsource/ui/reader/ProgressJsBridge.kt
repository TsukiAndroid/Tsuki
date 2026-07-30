package io.github.landwarderer.futon.webviewsource.ui.reader

import android.webkit.JavascriptInterface

/**
 * Injected into the WebView as window.TsukiBridge.
 * The injected JavaScript calls these methods as the user reads.
 */
class ProgressJsBridge(
    private val onScrollPercent: (Float) -> Unit,
) {
    /**
     * Called by the injected JS every 2 seconds with the current scroll
     * percentage (0–100 as an integer for simplicity, we divide by 100).
     */
    @JavascriptInterface
    fun reportScroll(percent: Int) {
        onScrollPercent(percent / 100f)
    }
}

/**
 * JavaScript injected into every page load.
 * Sets up a recurring poll (every 2 s) that calls TsukiBridge.reportScroll
 * with the current vertical scroll percentage (0–100, integer).
 *
 * The guard `window._tsukiTracker` prevents duplicate listeners if the script
 * is injected again after a soft navigation / SPA route change.
 */
const val PROGRESS_JS = """
(function() {
    if (window._tsukiTracker) return;
    window._tsukiTracker = true;
    function report() {
        var el = document.documentElement;
        var scrolled = el.scrollTop || document.body.scrollTop;
        var total = el.scrollHeight - el.clientHeight;
        var pct = total > 0 ? Math.round((scrolled / total) * 100) : 0;
        TsukiBridge.reportScroll(pct);
    }
    setInterval(report, 2000);
})();
"""
