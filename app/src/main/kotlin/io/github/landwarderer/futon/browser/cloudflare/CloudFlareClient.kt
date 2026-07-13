package io.github.landwarderer.futon.browser.cloudflare

import android.graphics.Bitmap
import android.webkit.WebView
import io.github.landwarderer.futon.browser.BrowserClient
import io.github.landwarderer.futon.core.network.cookies.MutableCookieJar
import io.github.landwarderer.futon.core.network.webview.adblock.AdBlock

private const val LOOP_COUNTER = 3

open class CloudFlareClient(
	private val cookieJar: MutableCookieJar,
	private val callback: CloudFlareCallback,
	// Nullable on purpose: ad-block filtering is disabled for the Cloudflare
	// challenge itself (see CloudFlareActivity) since a false-positive block of
	// a challenge-platform script/request is indistinguishable from "captcha
	// never completes" to the user, and there's nothing to ad-block on an
	// interstitial page anyway.
	adBlock: AdBlock?,
	private val targetUrl: String,
) : BrowserClient(callback, adBlock) {

	private val oldClearance = getClearance()
	private var counter = 0

	override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
		super.onPageStarted(view, url, favicon)
		view?.let { CloudflareWebView.injectAntiDetectionJs(it) }
		checkClearance()
	}

	override fun onPageCommitVisible(view: WebView, url: String) {
		super.onPageCommitVisible(view, url)
		callback.onPageLoaded()
	}

	override fun onPageFinished(webView: WebView, url: String) {
		super.onPageFinished(webView, url)
		callback.onPageLoaded()
	}

	fun reset() {
		counter = 0
	}

	private fun checkClearance() {
		if (CloudflareCookieSyncer.hasFreshClearance(cookieJar, targetUrl, oldClearance)) {
			callback.onCheckPassed()
		} else {
			counter++
			if (counter >= LOOP_COUNTER) {
				reset()
				callback.onLoopDetected()
			}
		}
	}

	private fun getClearance() = CloudflareCookieSyncer.currentClearance(cookieJar, targetUrl)
}
