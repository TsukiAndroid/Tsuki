package io.github.landwarderer.futon.core.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fires a HEAD request for a browser-source's base URL as soon as the user
 * taps it in the sources list, in parallel with the activity transition.
 * By the time [io.github.landwarderer.futon.browsersource.ui.BrowserSourceActivity]
 * actually constructs its WebView and calls `loadUrl`, DNS resolution and the
 * TLS handshake for that host are already warm, so the real WebView request
 * reuses the connection instead of paying for it from scratch.
 *
 * Uses the shared [BaseHttpClient] so the connection pool (and thus the warmed
 * socket) is the same one OkHttp-backed requests use; WebView's own network
 * stack is separate, but DNS resolution is cached at the OS level so it still
 * benefits.
 */
@Singleton
class ConnectionWarmer @Inject constructor(
	@BaseHttpClient private val httpClient: OkHttpClient,
) {

	fun warm(url: String, scope: CoroutineScope) {
		scope.launch(Dispatchers.IO) {
			runCatching {
				val request = Request.Builder().url(url).head().build()
				httpClient.newCall(request).execute().close()
			}.onFailure {
				Log.d(TAG, "Connection warm-up failed for $url (non-fatal): ${it.message}")
			}
		}
	}

	private companion object {
		const val TAG = "ConnectionWarmer"
	}
}
