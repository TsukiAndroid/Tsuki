package io.github.landwarderer.futon.core.network.webview

import android.content.Context
import android.util.Log
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Creating the very first [WebView] on a cold app start pays a fixed cost
 * (loading the WebView provider APK, spinning up its render process) that
 * otherwise happens the moment the user opens a manga's browser source or hits
 * a Cloudflare challenge, making that first navigation feel stalled. Prewarming
 * a throwaway instance at app startup moves that cost off the user's critical
 * path.
 *
 * Best-effort only: any failure here must never crash the app, since this is a
 * pure performance optimization.
 */
object WebViewPrewarmer {

    private const val TAG = "WebViewPrewarmer"

    /** How long to keep the throwaway instance alive before disposing it. */
    private const val PREWARM_LIFETIME_MS = 3_000L

    fun prewarm(context: Context) {
        CoroutineScope(Dispatchers.Main.immediate).launch {
            var webView: WebView? = null
            try {
                webView = WebView(context.applicationContext)
                webView.settings.javaScriptEnabled = true
                webView.loadUrl("about:blank")
                delay(PREWARM_LIFETIME_MS)
            } catch (e: Exception) {
                // Some OEM WebView providers (or a missing WebView update) can
                // throw here; prewarming is an optimization, not a requirement.
                Log.w(TAG, "WebView prewarm failed, ignoring", e)
            } finally {
                webView?.destroy()
            }
        }
    }
}
