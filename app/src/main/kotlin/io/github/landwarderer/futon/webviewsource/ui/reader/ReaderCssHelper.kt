package io.github.landwarderer.futon.webviewsource.ui.reader

import android.webkit.WebView

/**
 * Default CSS injected on every page load.
 * Hides common site chrome (headers, footers, cookie banners, ads)
 * without breaking manga reading content.
 */
const val DEFAULT_CLEANUP_CSS = """
  /* Hide common navigation chrome */
  header, footer, nav, .navbar, .header, .footer,
  .site-header, .site-footer, .top-bar, .bottom-bar,
  #header, #footer, #nav, #navbar,
  /* Cookie / GDPR banners */
  .cookie-banner, .cookie-notice, .gdpr-banner,
  [class*="cookie"], [class*="gdpr"], [id*="cookie"],
  /* Ad containers */
  .advertisement, .ads, .ad-banner, .ad-container,
  [class*="adsbygoogle"], [id*="google_ads"],
  /* Floating overlays */
  .popup, .modal-overlay, .overlay:not(.reader-overlay),
  /* Chapter comments sections */
  .comments, #comments, .disqus_thread {
    display: none !important;
  }

  /* Give the content more breathing room */
  body {
    padding: 0 !important;
    margin: 0 !important;
  }

  /* Make images fill the screen width */
  img.wp-manga-chapter-img,
  img[class*="chapter-img"],
  .reading-content img,
  .chapter-content img {
    width: 100% !important;
    height: auto !important;
    display: block !important;
  }
"""

/**
 * Injects [css] into [webView] by appending a `<style>` element to `<head>`.
 * Safe to call after `onPageFinished`.
 */
fun injectCss(webView: WebView, css: String) {
    val escaped = css
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
    val js = """
        (function() {
            var style = document.createElement('style');
            style.type = 'text/css';
            style.innerHTML = "$escaped";
            document.head.appendChild(style);
        })();
    """.trimIndent()
    webView.evaluateJavascript(js, null)
}
