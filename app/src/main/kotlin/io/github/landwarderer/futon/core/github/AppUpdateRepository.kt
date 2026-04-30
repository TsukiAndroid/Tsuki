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

	private val latestReleaseUrl = buildString {
		append("https://api.github.com/repos/")
		append(context.getString(R.string.github_updates_repo))
		append("/releases/latest")
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
				.url(latestReleaseUrl)
				.build()
			val response = okHttp.newCall(request).await()
			val json = JSONObject(response.body?.string() ?: "{}")

			val currentVersion = VersionId(BuildConfig.VERSION_NAME)
			val releaseVersion = VersionId(json.getString("tag_name").removePrefix("v"))

			if (releaseVersion <= currentVersion) {
				return@runCatchingCancellable null
			}

			val arch = getDeviceArch()
			val assets = json.getJSONArray("assets")
			val assetList = (0 until assets.length()).map { assets.getJSONObject(it) }
			val matchingAsset = assetList.find { 
				it.getString("name").contains(arch) 
			}

			AppVersion(
				id = json.getLong("id"),
				url = json.getString("html_url"),
				name = json.getString("name").removePrefix("v"),
				apkSize = matchingAsset?.getLong("size") ?: 0L,
				apkUrl = matchingAsset?.getString("browser_download_url") ?: "",
				description = json.getString("body"),
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
