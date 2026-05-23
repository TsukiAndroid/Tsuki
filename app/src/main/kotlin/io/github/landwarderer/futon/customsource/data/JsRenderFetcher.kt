package io.github.landwarderer.futon.customsource.data

  import android.annotation.SuppressLint
  import android.content.Context
  import android.util.Log
  import android.webkit.WebResourceRequest
  import android.webkit.WebView
  import android.webkit.WebViewClient
  import kotlinx.coroutines.Dispatchers
  import kotlinx.coroutines.suspendCancellableCoroutine
  import kotlinx.coroutines.withContext
  import kotlinx.coroutines.withTimeoutOrNull
  import kotlin.coroutines.resume

  /**
   * Loads a URL in a hidden [WebView] when the page is detected as JavaScript-rendered.
   * Falls back to the plain HTTP response when the site is server-side rendered (faster).
   *
   * **JS-render detection** — a page is considered JS-rendered when ANY of:
   *  - Fewer than 3 img tags in raw HTML
   *  - Fewer than 200 visible body-text characters
   *  - Framework markers present: __NEXT_DATA__, window.__NUXT__,
   *    ng-app, data-reactroot, div id="app", div id="root"
   *
   * The [fetch] call must originate from a coroutine. WebView creation is dispatched
   * to [Dispatchers.Main] automatically (Android requirement). A 2-second settle delay
   * is applied after window.onload so that lazy-rendered content finishes painting.
   */
  class JsRenderFetcher(private val context: Context) {

      /**
       * Renders [url] in a hidden WebView and returns the final DOM HTML.
       * Returns null if rendering fails or times out after [TIMEOUT_MS] ms.
       */
      @SuppressLint("SetJavaScriptEnabled")
      suspend fun fetch(url: String): String? =
          withTimeoutOrNull(TIMEOUT_MS) {
              withContext(Dispatchers.Main) {
                  suspendCancellableCoroutine { cont ->
                      val wv = WebView(context)
                      wv.settings.apply {
                          javaScriptEnabled = true
                          domStorageEnabled = true
                          @Suppress("DEPRECATION")
                          userAgentString = USER_AGENT
                      }

                      var settled = false

                      wv.webViewClient = object : WebViewClient() {
                          override fun onPageFinished(view: WebView, pageUrl: String) {
                              if (settled) return
                              // Wait for JS frameworks to finish rendering (2-second settle)
                              view.postDelayed({
                                  if (settled) return@postDelayed
                                  settled = true
                                  view.evaluateJavascript("document.documentElement.outerHTML") { raw ->
                                      val html = raw
                                          ?.removeSurrounding(""")
                                          ?.replace("\\n", "\n")
                                          ?.replace("\\"", "\"")
                                          ?.replace("\\'", "'")
                                          ?.replace("\\\\", "\\")
                                      wv.destroy()
                                      cont.resume(html)
                                  }
                              }, SETTLE_DELAY_MS)
                          }

                          override fun shouldOverrideUrlLoading(
                              view: WebView,
                              request: WebResourceRequest,
                          ) = false
                      }

                      cont.invokeOnCancellation {
                          if (!settled) {
                              settled = true
                              wv.destroy()
                          }
                      }

                      wv.loadUrl(url)
                  }
              }
          }.also { if (it == null) Log.w(TAG, "JS render timed out for $url") }

      companion object {
          private const val TAG             = "JsRenderFetcher"
          private const val TIMEOUT_MS      = 25_000L
          private const val SETTLE_DELAY_MS = 2_000L
          private const val USER_AGENT      =
              "Mozilla/5.0 (Linux; Android 13; Pixel 7) " +
              "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

          /**
           * Returns true if [html] shows signs of JavaScript-rendered content.
           * Used by callers to decide whether to invoke [JsRenderFetcher.fetch].
           */
          fun isJsRendered(html: String): Boolean {
              // JavaScript framework markers
              if (html.contains("__NEXT_DATA__")            ||
                  html.contains("window.__NUXT__")          ||
                  html.contains("ng-app")                   ||
                  html.contains("data-reactroot")           ||
                  html.contains("<div id=\"app\"")        ||
                  html.contains("<div id=\"root\"")
              ) return true

              // Almost no images → page content likely not yet rendered
              val imgCount = "<img ".toRegex(RegexOption.IGNORE_CASE).findAll(html).count()
              if (imgCount < 3) return true

              // Almost no visible text → shell HTML only
              val visibleText = html.replace(Regex("<[^>]+>"), "").replace(Regex("\\s+"), " ").trim()
              if (visibleText.length < 200) return true

              return false
          }
      }
  }
  