package io.github.landwarderer.futon.core.github

import android.content.Context
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.landwarderer.futon.BuildConfig
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

                                // Strip the channel prefix to get a plain semver for VersionId
                                val semver = when (channel) {
                                        "alpha" -> tagName.removePrefix("alpha-")
                                        "beta"  -> tagName.removePrefix("beta-")
                                        else    -> tagName.removePrefix("v")
                                }
                                if (semver.isEmpty()) continue

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
         * Fetches the release notes for the CURRENTLY INSTALLED version.
         * Used by the "What's New" dialog shown on first launch after an upgrade.
         */
        suspend fun fetchCurrentReleaseNotes(): String? = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                        val channel = BuildConfig.UPDATE_CHANNEL
                        val baseVersion = BuildConfig.VERSION_NAME.removeSuffix("-" + channel)
                        val expectedTag = when (channel) {
                                "alpha" -> "alpha-" + baseVersion
                                "beta"  -> "beta-"  + baseVersion
                                else    -> "v"      + baseVersion
                        }

                        val request = Request.Builder().get().url(releasesUrl).build()
                        val releases = JSONArray(okHttp.newCall(request).await().body?.string() ?: "[]")

                        for (i in 0 until releases.length()) {
                                val item = releases.getJSONObject(i)
                                if (item.optString("tag_name") == expectedTag) {
                                        return@runCatchingCancellable item.optString("body", "")
                                }
                        }
                        null
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
        }
}
