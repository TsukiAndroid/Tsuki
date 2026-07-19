package io.github.landwarderer.futon.browser.cloudflare

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.view.View
import android.webkit.CookieManager
import androidx.appcompat.app.AppCompatActivity
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.github.landwarderer.futon.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Orchestrates the full Cloudflare bypass flow for browser source screens.
 *
 * Flow:
 *  1. [startBypass] — shows a confirmation dialog over the WebView.
 *  2. "Verify Now" → opens the blocked URL in a Chrome Custom Tab.
 *     Chrome uses the real Chromium TLS/HTTP2 stack, which Cloudflare
 *     cannot distinguish from a real desktop browser.
 *  3. Polls [CookieManager] every 500 ms for [CF_CLEARANCE_COOKIE]. On
 *     Android 7+, Chrome and Android's WebView share the same underlying
 *     cookie store, so the cookie set by Chrome is immediately visible here.
 *  4. On detection → brings the hosting activity back to the foreground,
 *     shows a success snackbar, and invokes [onComplete] so the caller can
 *     reload the original URL in the WebView.
 *  5. On 2-minute timeout → shows an error snackbar and invokes [onTimeout].
 *  6. "Cancel" → invokes [onCancelled] immediately.
 *
 * Construct once per activity instance. Call [cancel] in onDestroy.
 */
class CloudflareBypassManager(
    private val activity: AppCompatActivity,
    private val anchorView: View,
    private val onComplete: (originalUrl: String) -> Unit,
    private val onTimeout: () -> Unit,
    private val onCancelled: () -> Unit,
) {
    private var pollingJob: Job? = null

    /**
     * Show the "Verify Now" dialog. If the user confirms, a Chrome Custom
     * Tab is opened and cookie polling begins.
     *
     * @param blockedUrl The URL that triggered the Cloudflare challenge.
     */
    fun startBypass(blockedUrl: String) {
        pollingJob?.cancel()

        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.cloudflare_bypass_title)
            .setMessage(R.string.cloudflare_bypass_message)
            .setPositiveButton(R.string.cloudflare_bypass_verify) { _, _ ->
                launchCct(blockedUrl)
            }
            .setNegativeButton(R.string.cloudflare_bypass_cancel) { _, _ ->
                onCancelled()
            }
            .show()
    }

    /** Cancel any in-progress polling. Call from the host activity's onDestroy. */
    fun cancel() {
        pollingJob?.cancel()
        pollingJob = null
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun launchCct(url: String) {
        val packageName = findCctBrowserPackage(activity)
        if (packageName != null) {
            // Warm up the CCT service for a smoother transition, then launch.
            CustomTabsClient.bindCustomTabsService(
                activity,
                packageName,
                object : CustomTabsServiceConnection() {
                    override fun onCustomTabsServiceConnected(
                        name: ComponentName,
                        client: CustomTabsClient,
                    ) {
                        client.warmup(0)
                        val session = client.newSession(null)
                        CustomTabsIntent.Builder(session)
                            .setShowTitle(true)
                            .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
                            .setUrlBarHidingEnabled(false)
                            .build()
                            .launchUrl(activity, Uri.parse(url))
                        startCookiePolling(url)
                    }

                    override fun onServiceDisconnected(name: ComponentName) {}
                },
            )
        } else {
            // No CCT-capable browser found — open in system default browser.
            try {
                activity.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                Snackbar.make(
                    anchorView,
                    R.string.cloudflare_bypass_fallback_message,
                    Snackbar.LENGTH_LONG,
                ).show()
                startCookiePolling(url)
            } catch (_: Exception) {
                onCancelled()
            }
        }
    }

    private fun startCookiePolling(url: String) {
        val domain = Uri.parse(url).host ?: return
        val startTime = System.currentTimeMillis()

        pollingJob = activity.lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(POLL_INTERVAL_MS)

                if (System.currentTimeMillis() - startTime > POLL_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) { handleTimeout() }
                    break
                }

                val cookies = withContext(Dispatchers.Main) {
                    CookieManager.getInstance().getCookie(domain) ?: ""
                }
                if (cookies.contains(CF_CLEARANCE_COOKIE)) {
                    withContext(Dispatchers.Main) { handleComplete(url) }
                    break
                }
            }
        }
    }

    private fun handleComplete(originalUrl: String) {
        pollingJob?.cancel()
        // Bring the hosting activity back to the foreground, closing the CCT.
        activity.startActivity(
            Intent(activity, activity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        )
        Snackbar.make(anchorView, R.string.cloudflare_bypass_verified, Snackbar.LENGTH_LONG).show()
        onComplete(originalUrl)
    }

    private fun handleTimeout() {
        pollingJob?.cancel()
        Snackbar.make(anchorView, R.string.cloudflare_bypass_timeout, Snackbar.LENGTH_LONG).show()
        onTimeout()
    }

    // ── Companion ─────────────────────────────────────────────────────────────

    companion object {
        private const val CF_CLEARANCE_COOKIE = "cf_clearance"
        private const val POLL_INTERVAL_MS = 500L
        private const val POLL_TIMEOUT_MS = 120_000L

        /**
         * CCT-capable browser package names, checked in preference order.
         * Chrome shares a cookie store with Android WebView on Android 7+,
         * which is required for the cf_clearance sync to work.
         */
        private val CCT_PACKAGES = listOf(
            "com.android.chrome",           // Chrome (preferred)
            "com.microsoft.emmx",           // Edge
            "com.sec.android.app.sbrowser", // Samsung Internet
            "org.mozilla.firefox",          // Firefox
            "com.opera.browser",            // Opera
        )

        /**
         * Returns the package name of the first CCT-capable browser installed
         * on the device, or null if none is found.
         */
        fun findCctBrowserPackage(context: Context): String? {
            val pm = context.packageManager
            return CCT_PACKAGES.firstOrNull { pkg ->
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0L))
                    } else {
                        @Suppress("DEPRECATION")
                        pm.getPackageInfo(pkg, 0)
                    }
                    true
                } catch (_: PackageManager.NameNotFoundException) {
                    false
                }
            }
        }
    }
}
