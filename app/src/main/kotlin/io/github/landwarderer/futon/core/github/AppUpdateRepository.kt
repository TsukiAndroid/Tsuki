package io.github.landwarderer.futon.core.github

import android.content.Context
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

private const val BUILD_TYPE_RELEASE = "release"

@Singleton
class AppUpdateRepository @Inject constructor(
        private val settings: AppSettings,
        @BaseHttpClient private val okHttp: OkHttpClient,
        @ApplicationContext context: Context,
) {
        private val availableUpdate = MutableStateFlow<AppVersion?>(null)

        private val releasesUrl = buildString {
                append("https://api.github.com/repos/")
                append(context.getString(R.string.github_updates_repo))
                append("/releases?per_page=20")
        }

        private val changelogUrl = buildString {
                append("https://raw.githubusercontent.com/")
                append(context.getString(R.string.github_updates_repo))
                append("/refs/heads/devel/CHANGELOG.md")
        }

        val isUpdateAvailable: Boolean
                get() = availableUpdate.value != null

        fun observeAvailableUpdate() = availableUpdate.asStateFlow()

        suspend fun fetchUpdate(): AppVersion? = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                        val request = Request.Builder()
                                .get()
                                .url(releasesUrl)
                                .build()
                        val response = okHttp.newCall(request).await()
                        val releases = JSONArray(response.body?.string() ?: "[]")
                        val allowUnstable = settings.isUnstableUpdatesAllowed || BuildConfig.BUILD_TYPE == "alpha"

                        val currentVersion = VersionId(BuildConfig.VERSION_NAME)

                        var bestRelease: JSONObject? = null
                        var bestVersion: VersionId? = null
                        for (i in 0 until releases.length()) {
                                val item = releases.getJSONObject(i)
                                if (item.optBoolean("draft", false)) continue
                                if (item.optBoolean("prerelease", false) && !allowUnstable) continue
                                val tagName = item.optString("tag_name").removePrefix("v")
                                if (tagName.isEmpty()) continue
                                val version = runCatching { VersionId(tagName) }.getOrNull() ?: continue
                                if (!allowUnstable && !version.isStable) continue
                                if (bestVersion == null || version > bestVersion) {
                                        bestVersion = version
                                        bestRelease = item
                                }
                        }

                        val release = bestRelease ?: return@runCatchingCancellable null
                        val releaseVersion = bestVersion ?: return@runCatchingCancellable null

                        if (releaseVersion <= currentVersion) {
                                return@runCatchingCancellable null
                        }

                        val arch = getDeviceArch()
                        val assets = release.getJSONArray("assets")
                        val assetList = (0 until assets.length()).map { assets.getJSONObject(it) }
                        val matchingAsset = assetList.find {
                                it.getString("name").contains(arch)
                        } ?: assetList.find {
                                it.getString("name").contains("universal")
                        } ?: assetList.firstOrNull()

                        AppVersion(
                                id = release.getLong("id"),
                                url = release.getString("html_url"),
                                name = release.optString("name").ifEmpty { release.getString("tag_name") }.removePrefix("v"),
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

        suspend fun fetchChangelog(): String? = withContext(Dispatchers.IO) {
                runCatchingCancellable {
                        val request = Request.Builder()
                                .get()
                                .url(changelogUrl)
                                .build()
                        okHttp.newCall(request).await().body?.string()
                }.onFailure {
                        it.printStackTraceDebug("AppUpdateRepository::fetchChangelog")
                }.getOrNull()
        }

        suspend fun isUpdateSupported(): Boolean {
                return true
        }

        private fun getDeviceArch(): String {
                return when {
                        android.os.Build.SUPPORTED_ABIS.contains("arm64-v8a") -> "arm64-v8a"
                        android.os.Build.SUPPORTED_ABIS.contains("armeabi-v7a") -> "armeabi-v7a"
                        android.os.Build.SUPPORTED_ABIS.contains("x86_64") -> "x86_64"
                        else -> "universal"
                }
        }
}
