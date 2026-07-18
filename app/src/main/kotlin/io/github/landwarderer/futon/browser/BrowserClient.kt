package io.github.landwarderer.futon.browser

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.AnyThread
import androidx.annotation.WorkerThread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import io.github.landwarderer.futon.core.network.webview.adblock.AdBlock
import java.io.ByteArrayInputStream

open class BrowserClient(
	private val callback: BrowserCallback,
	private val adBlock: AdBlock?,
) : WebViewClient() {

	/**
	 * https://stackoverflow.com/questions/57414530/illegalstateexception-reasonphrase-cant-be-empty-with-android-webview
	 */

	override fun onPageFinished(webView: WebView, url: String) {
		super.onPageFinished(webView, url)
		callback.onLoadingStateChanged(isLoading = false)
	}

	override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
		super.onPageStarted(view, url, favicon)
		callback.onLoadingStateChanged(isLoading = true)
	}

	override fun onPageCommitVisible(view: WebView, url: String) {
		super.onPageCommitVisible(view, url)
		callback.onTitleChanged(view.title.orEmpty(), url)
	}

	override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
		super.doUpdateVisitedHistory(view, url, isReload)
		callback.onHistoryChanged()
	}

	@WorkerThread
	@Deprecated("Deprecated in Java")
	override fun shouldInterceptRequest(
		view: WebView?,
		url: String?
	): WebResourceResponse? = if (url.isNullOrEmpty() || adBlock?.shouldLoadUrl(url, view?.getUrlSafe()) ?: true) {
		super.shouldInterceptRequest(view, url)
	} else {
		emptyResponse()
	}

	@WorkerThread
	override fun shouldInterceptRequest(
		view: WebView?,
		request: WebResourceRequest?
	): WebResourceResponse? =
		if (request == null || adBlock?.shouldLoadUrl(request.url.toString(), view?.getUrlSafe()) ?: true) {
			super.shouldInterceptRequest(view, request)
		} else {
			emptyResponse()
		}

	private fun emptyResponse(): WebResourceResponse =
		WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(byteArrayOf()))

	/**
	 * Redirect OAuth / sign-in URLs to Chrome or the system browser.
	 *
	 * Google (and Twitter, Facebook, Discord, GitHub) explicitly block OAuth
	 * inside embedded WebViews that don't identify as a full Chrome session.
	 * Opening in the system browser lets the user complete login and return.
	 *
	 * Nullable params match the underlying Java [WebViewClient] platform-type
	 * signature so subclasses can safely override with nullable receivers.
	 */
	override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
		val url = request?.url?.toString() ?: return super.shouldOverrideUrlLoading(view, request)
		if (isOAuthUrl(url) && view != null) {
			// Inform the user before leaving the in-app WebView (Step B3)
			Toast.makeText(
				view.context,
				"Opening login in Chrome for security. Return to Tsuki after signing in.",
				Toast.LENGTH_LONG,
			).show()
			// Step B2 — try Chrome Custom Tab first (best experience)
			runCatching {
				val customTabIntent = CustomTabsIntent.Builder()
					.setShowTitle(true)
					.setShareState(CustomTabsIntent.SHARE_STATE_OFF)
					.build()
				customTabIntent.launchUrl(view.context, Uri.parse(url))
				return true
			}
			// Fall back to system browser if Custom Tab is unavailable
			runCatching {
				val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
					addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
				}
				view.context.startActivity(intent)
				return true
			}
			return true
		}
		return super.shouldOverrideUrlLoading(view, request)
	}

	private fun isOAuthUrl(url: String): Boolean {
		val lower = url.lowercase()
		return OAUTH_DOMAINS.any { lower.contains(it) } ||
			OAUTH_PATTERNS.any { lower.contains(it) }
	}

	companion object {
		/**
		 * URL substrings (domains) that must open in an external browser rather
		 * than the in-app WebView. Google, Twitter, Facebook, Discord, and GitHub
		 * explicitly block OAuth inside embedded WebViews.
		 */
		private val OAUTH_DOMAINS = listOf(
			"accounts.google.com",
			"oauth.google.com",
			"accounts.youtube.com",
			"accounts.twitter.com",
			"www.facebook.com/dialog/oauth",
			"www.facebook.com/login",
			"discord.com/oauth2",
			"github.com/login/oauth",
			"twitter.com/i/oauth",
		)

		/**
		 * URL patterns that indicate an OAuth / login flow regardless of domain.
		 * Checked in addition to [OAUTH_DOMAINS].
		 */
		private val OAUTH_PATTERNS = listOf(
			"/oauth",
			"/oauth2",
			"/auth/",
			"/login/oauth",
			"/connect/",
			"response_type=code",
			"response_type=token",
		)
	}

	@SuppressLint("WrongThread")
	@AnyThread
	private fun WebView.getUrlSafe(): String? = if (Looper.myLooper() == Looper.getMainLooper()) {
		url
	} else {
		runBlocking(Dispatchers.Main.immediate) {
			url
		}
	}
}
