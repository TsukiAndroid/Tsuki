package io.github.landwarderer.futon.browser.cloudflare

import io.github.landwarderer.futon.core.network.cookies.MutableCookieJar
import org.koitharu.kotatsu.parsers.network.CloudFlareHelper

/**
 * Reads the `cf_clearance` cookie that the WebView's Cloudflare challenge just
 * set, through the same [MutableCookieJar] the app's OkHttp clients use for
 * every other request.
 *
 * There is deliberately no cookie *copying* code here: on Android,
 * [io.github.landwarderer.futon.core.network.cookies.AndroidCookieJar] reads
 * and writes straight through `android.webkit.CookieManager`, which is the
 * exact same cookie store the WebView itself uses. So the moment Cloudflare's
 * JS sets `cf_clearance` via `document.cookie` (or a Set-Cookie header),
 * OkHttp already sees it on the next request — no manual sync step, no
 * polling a WebView cookie string and copying it into a separate jar. This
 * class exists to make that fact explicit and give [CloudFlareClient] a single
 * named place to ask "has clearance changed?", instead of duplicating a
 * getClearanceCookie call inline.
 */
object CloudflareCookieSyncer {

    /** Null if no clearance cookie has been granted yet for [targetUrl]. */
    fun currentClearance(cookieJar: MutableCookieJar, targetUrl: String): String? =
        CloudFlareHelper.getClearanceCookie(cookieJar, targetUrl)

    /** True once a *new* clearance value (different from [previous]) has appeared. */
    fun hasFreshClearance(cookieJar: MutableCookieJar, targetUrl: String, previous: String?): Boolean {
        val current = currentClearance(cookieJar, targetUrl)
        return current != null && current != previous
    }
}
