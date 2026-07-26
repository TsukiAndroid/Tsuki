package io.github.landwarderer.futon.core.nav

import android.content.Context
import android.content.Intent
import io.github.landwarderer.futon.browsersource.ui.BrowserSourceActivity

/**
 * Central launcher that ALWAYS opens URLs in Tsuki's built-in WebView
 * ([BrowserSourceActivity]) rather than Chrome or any external browser.
 *
 * Every "Open in browser" action inside Tsuki must go through this object.
 *
 * Exceptions (external browser intentionally kept):
 *  - Google / OAuth sign-in pages  → handled in [BrowserClient.shouldOverrideUrlLoading]
 *  - Cloudflare captcha verification → handled in [CloudflareBypassManager]
 *  - Explicit "Share" / "Open in External Browser" user action → use
 *    [AppRouter.openExternalBrowser] directly
 */
object TsukiBrowser {

    /**
     * Open [url] in Tsuki's built-in WebView browser.
     *
     * @param context  Any valid Android context.
     * @param url      The HTTP/HTTPS URL to load.
     * @param title    Optional title shown in the toolbar while the page loads.
     *                 Falls back to [url] when null.
     */
    fun open(
        context: Context,
        url: String,
        title: String? = null,
    ) {
        val intent = BrowserSourceActivity.createDirectIntent(context, url, title)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
