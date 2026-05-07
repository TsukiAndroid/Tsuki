package io.github.landwarderer.futon.core.github

import android.accounts.AccountManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.landwarderer.futon.BuildConfig
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.network.BaseHttpClient
import io.github.landwarderer.futon.core.prefs.AppSettings
import io.github.landwarderer.futon.core.util.ext.printStackTraceDebug
import org.koitharu.kotatsu.parsers.util.await
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches GitHub releases and filters them by the current build's UPDATE_CHANNEL
 * (injected as a BuildConfig field per productFlavor in build.gradle):
 *   "alpha"  -> only releases whose tag starts with "alpha-"
 *   "beta"   -> only releases whose tag starts with "beta-"
 *   "stable" -> only non-pre-release releases whose tag starts with "v"
 *
 * APK asset matching uses getDeviceArch() which returns "arm64", "arm32",
 * "x86_64", or "universal" to match asset file names.
 */
@Singleton
class AppUpdateRepository @Inject constructor(
        @Suppress("unused") private val settings: AppSettings,
        @BaseHttpClient private val okHttp: OkHttpClient,
        @ApplicationContext private val context: Context,
) {
        private val availableUpdate = MutableStateFlow<AppVersion?>(null)

        // RELEASES_URL and UPDATE_CHANNEL are injected per productFlavor via buildConfigField.
        private val releasesUrl = BuildConfig.RELEASES_URL + "?per_page=50"

        private val changelogUrl =
                "https://raw.githubusercontent.com/Space4414/Tsuki/refs/heads/devel/CHANGELOG.md"

        val isUpdateAvailable: Boolean
                get() = availableUpdate.value != null

        fun observeAvailableUpdate() = availableUpdate.asStateFlow()

        suspend fun fetchUpdate(): AppVersion? = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                        val request = Request.Builder().get().url(releasesUrl).build()
                        val releases = JSONArray(okHttp.newCall(request).await().body?.string() ?: "[]")

                        val channel = BuildConfig.UPDATE_CHANNEL   // "alpha" | "beta" | "stable"

                        var bestRelease: JSONObject? = null
                        var bestVersion: VersionId? = null

                        for (i in 0 until releases.length()) {
                                val item = releases.getJSONObject(i)
                                if (item.optBoolean("draft", false)) continue

                                val tagName = item.optString("tag_name", "")
                                val isPreRelease = item.optBoolean("prerelease", false)

                                // Each channel only considers releases with its own tag prefix
                                val matchesChannel = when (channel) {
                                        "alpha" -> tagName.startsWith("alpha-")
                                        "beta"  -> tagName.startsWith("beta-")
                                        else    -> !isPreRelease && tagName.startsWith("v")
                                }
                                if (!matchesChannel) continue

                                // The CI uses floating tags ("alpha-latest", "beta-latest") that strip
                                // to "latest" — an unparseable pseudo-version that VersionId maps to
                                // (0,0,0), always less than any real install, so update detection never
                                // fires. Fix: extract the real build version from the release *name*
                                // (e.g. "Tsuki Alpha 1.274 (Build #226)" → "1.274").
                                // Fall back to tag-derived semver for properly versioned tags like
                                // "alpha-1.60" or "v1.60.0".
                                val releaseName = item.optString("name", "")
                                val nameVersion =
                                        Regex("\\b(\\d+\\.\\d+(?:\\.\\d+)?)\\b").find(releaseName)?.groupValues?.get(1)
                                val semver = nameVersion ?: when (channel) {
                                        "alpha" -> tagName.removePrefix("alpha-")
                                        "beta"  -> tagName.removePrefix("beta-")
                                        else    -> tagName.removePrefix("v")
                                }
                                if (semver.isEmpty() || !semver.first().isDigit()) continue

                                val version = runCatching { VersionId(semver) }.getOrNull() ?: continue
                                if (bestVersion == null || version > bestVersion) {
                                        bestVersion = version
                                        bestRelease = item
                                }
                        }

                        val release = bestRelease ?: return@runCatchingCancellable null
                        val releaseVersion = bestVersion ?: return@runCatchingCancellable null

                        // Strip our own flavor suffix before comparing so "1.60.0-alpha"
                        // is treated the same as "1.60.0" in the semver comparison.
                        val baseVersionName = BuildConfig.VERSION_NAME.removeSuffix("-" + channel)
                        val currentVersion = runCatching { VersionId(baseVersionName) }.getOrNull()
                                ?: return@runCatchingCancellable null

                        if (releaseVersion <= currentVersion) return@runCatchingCancellable null

                        val arch = getDeviceArch()
                        val assets = release.getJSONArray("assets")
                        val assetList = (0 until assets.length()).map { assets.getJSONObject(it) }

                        // Match by arch string, fall back to "universal", then to any APK
                        val matchingAsset = assetList.find { it.getString("name").contains(arch) }
                                ?: assetList.find { it.getString("name").contains("universal") }
                                ?: assetList.find { it.getString("name").endsWith(".apk") }

                        AppVersion(
                                id = release.getLong("id"),
                                url = release.getString("html_url"),
                                name = release.optString("name").ifEmpty { release.getString("tag_name") }
                                        .removePrefix("v"),
                                apkSize = matchingAsset?.getLong("size") ?: 0L,
                                apkUrl = matchingAsset?.getString("browser_download_url") ?: "",
                                description = release.optString("body", ""),
                        )
                }.onFailure {
                        it.printStackTraceDebug("AppUpdateRepository::fetchUpdate")
                }.onSuccess {
                        availableUpdate.value = it
                }.getOrNull()
        }

        /**
         * Fetches the release notes for the CURRENTLY INSTALLED channel via the
         * floating "{channel}-latest" tag that CI always reassigns on every build.
         *
         * Using a floating tag avoids the previous bug where an exact version tag
         * like "alpha-1.219" was looked up but never exists in the releases list
         * (only "alpha-latest" is ever created by the release workflow).
         */
        suspend fun fetchCurrentReleaseNotes(): String? = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                        val channel = BuildConfig.UPDATE_CHANNEL
                        val floatingTag = when (channel) {
                                "alpha" -> "alpha-latest"
                                "beta"  -> "beta-latest"
                                else    -> "stable-latest"
                        }

                        val tagUrl = "https://api.github.com/repos/Space4414/Tsuki/releases/tags/$floatingTag"
                        val request = Request.Builder().get().url(tagUrl).build()
                        val body = okHttp.newCall(request).await().body?.string() ?: return@runCatchingCancellable null

                        val release = JSONObject(body)
                        if (release.has("message")) return@runCatchingCancellable null

                        release.optString("body", "").ifEmpty { null }
                }.onFailure {
                        it.printStackTraceDebug("AppUpdateRepository::fetchCurrentReleaseNotes")
                }.getOrNull()
        }

        suspend fun fetchChangelog(): String? = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                        val request = Request.Builder().get().url(changelogUrl).build()
                        okHttp.newCall(request).await().body?.string()
                }.onFailure {
                        it.printStackTraceDebug("AppUpdateRepository::fetchChangelog")
                }.getOrNull()
        }

        suspend fun isUpdateSupported(): Boolean = true

        /**
         * Returns true if the currently installed VERSION_CODE is newer than the last
         * version for which the "What's New" dialog was acknowledged.
         */
        fun shouldShowWhatsNew(): Boolean {
                val prefs = context.getSharedPreferences(WHATS_NEW_PREFS, Context.MODE_PRIVATE)
                return BuildConfig.VERSION_CODE > prefs.getInt(KEY_LAST_SEEN_VERSION_CODE, -1)
        }

        /** Persists the current VERSION_CODE so the dialog won't show again for this build. */
        fun markWhatsNewSeen() {
                context.getSharedPreferences(WHATS_NEW_PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putInt(KEY_LAST_SEEN_VERSION_CODE, BuildConfig.VERSION_CODE)
                        .apply()
        }

        /**
         * Returns true if this is a Stable build that previously used the legacy sync account type
         * (io.github.landwarderer.futon.sync) and that old account still exists on the device,
         * meaning the user had sync set up before the authority migration and needs to sign in again.
         * Alpha and Beta have an empty LEGACY_SYNC_ACCOUNT_TYPE so this is always false for them.
         */
        fun shouldShowSyncMigrationBanner(): Boolean {
                if (BuildConfig.LEGACY_SYNC_ACCOUNT_TYPE.isEmpty()) return false
                val prefs = context.getSharedPreferences(SYNC_MIGRATION_PREFS, Context.MODE_PRIVATE)
                if (prefs.getBoolean(KEY_SYNC_MIGRATION_SHOWN, false)) return false
                val am = AccountManager.get(context)
                // Primary path: old account still on device (not yet auto-removed by OS).
                if (am.getAccountsByType(BuildConfig.LEGACY_SYNC_ACCOUNT_TYPE).isNotEmpty()) return true
                // Fallback: OS already cleaned up the orphaned account on package update, but
                // SyncController wrote KEY_HAD_SYNC_ACCOUNT while the old build was running.
                // Only trigger if the user hasn't already signed in under the new account type.
                val newAccountMissing = am.getAccountsByType(
                        context.getString(R.string.account_type_sync),
                ).isEmpty()
                return prefs.getBoolean(KEY_HAD_SYNC_ACCOUNT, false) && newAccountMissing
        }

        /** Records that the migration banner has been shown so it never appears again. */
        fun markSyncMigrationBannerSeen() {
                context.getSharedPreferences(SYNC_MIGRATION_PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_SYNC_MIGRATION_SHOWN, true)
                        .apply()
        }

        /**
         * Removes every account still registered under the legacy account type.
         * Uses removeAccountExplicitly() (API 22+, our minSdk is 23) which requires the caller
         * to be the authenticator UID that owns the account type. If the OS has already
         * auto-removed the orphaned accounts on package update the call is a no-op.
         * SecurityExceptions are swallowed — the OS cleans up stragglers on its own schedule.
         */
        fun removeLegacySyncAccount() {
                if (BuildConfig.LEGACY_SYNC_ACCOUNT_TYPE.isEmpty()) return
                val am = AccountManager.get(context)
                am.getAccountsByType(BuildConfig.LEGACY_SYNC_ACCOUNT_TYPE).forEach { account ->
                        runCatching { am.removeAccountExplicitly(account) }
                }
        }

        /**
         * Returns the arch label used to match APK asset names in GitHub releases.
         * Naming convention (must match what the release CI names the APK files):
         *   arm64-v8a   -> "arm64"
         *   armeabi-v7a -> "arm32"
         *   x86_64      -> "x86_64"
         *   other       -> "universal"
         */
        fun getDeviceArch(): String = when {
                Build.SUPPORTED_ABIS.contains("arm64-v8a")   -> "arm64"
                Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "arm32"
                Build.SUPPORTED_ABIS.contains("x86_64")      -> "x86_64"
                else                                           -> "universal"
        }

        companion object {
                private const val WHATS_NEW_PREFS = "whats_new"
                private const val KEY_LAST_SEEN_VERSION_CODE = "last_seen_version_code"
                private const val SYNC_MIGRATION_PREFS = "sync_migration"
                private const val KEY_SYNC_MIGRATION_SHOWN = "banner_shown"
                // Must match SyncController.KEY_HAD_SYNC_ACCOUNT — both read/write the same prefs file.
                private const val KEY_HAD_SYNC_ACCOUNT = "had_sync_account"
        }
}
