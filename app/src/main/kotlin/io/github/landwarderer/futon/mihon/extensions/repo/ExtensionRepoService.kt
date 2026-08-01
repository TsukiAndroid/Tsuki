package io.github.landwarderer.futon.mihon.extensions.repo

import android.util.Log
import androidx.annotation.Keep
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import io.github.landwarderer.futon.core.network.MangaHttpClient
import io.github.landwarderer.futon.core.prefs.AppSettings
import io.github.landwarderer.futon.core.prefs.GitHubMirror
import io.github.landwarderer.futon.mihon.MihonExtensionLoader
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ExtensionRepoService @Inject constructor(
	@MangaHttpClient private val httpClient: OkHttpClient,
	private val json: Json,
	private val settings: AppSettings,
) {

	private fun applyMirror(url: String): String {
		if (url.startsWith("https://raw.githubusercontent.com/")) {
			return when (settings.gitHubMirror) {
				GitHubMirror.KEIYOUSHI -> {
					if (url.contains("/keiyoushi/extensions/")) {
						url.replace("raw.githubusercontent.com", "raw.github.com")
					} else {
						"https://raw.github.com/keiyoushi/extensions/refs/heads/repo/${url.substringAfter("raw.githubusercontent.com/")}"
					}
				}
				GitHubMirror.KKGITHUB -> url.replace("raw.githubusercontent.com", "raw.kkgithub.com")
				GitHubMirror.GHPROXY -> "https://mirror.ghproxy.com/$url"
				GitHubMirror.GHPROXY_NET -> "https://ghproxy.net/$url"
				else -> url
			}
		}
		return url
	}

	private fun deriveRepoName(baseUrl: String, defaultName: String): String {
		val url = baseUrl.toHttpUrlOrNull() ?: return defaultName
		val segments = url.pathSegments.filter { it.isNotEmpty() }
		if (segments.size >= 2 && url.host.contains("githubusercontent.com")) {
			return "${segments[0]}/${segments[1]}"
		} else if (segments.size >= 2 && url.host == "github.com") {
			return "${segments[0]}/${segments[1]}"
		} else if (segments.isNotEmpty()) {
			return segments.last()
		}
		return url.host
	}

	suspend fun fetchRepoDetails(baseUrl: String, type: ExternalExtensionType): ExternalExtensionRepo {
		if (type == ExternalExtensionType.IREADER || type == ExternalExtensionType.JAR) {
			val now = System.currentTimeMillis()
			val derived = deriveRepoName(baseUrl, if (type == ExternalExtensionType.IREADER) "IReader" else "Futon")
			val repoName = if (type == ExternalExtensionType.IREADER) "IReader: $derived" else "Futon: $derived"
			val repoShort = derived
			var version: String? = null
			if (type == ExternalExtensionType.JAR) {
				val indexUrl = applyMirror("$baseUrl/index.min.json")
				runCatching {
					withTimeout(REPO_DETAILS_TIMEOUT_MS) {
						val body = httpClient.newCall(GET(indexUrl)).awaitSuccess().use { response ->
							response.body.string()
						}
						val dto = json.decodeFromString<List<ExtensionIndexDto>>(body)
						version = dto.firstOrNull()?.version
					}
				}
			}

			return ExternalExtensionRepo(
				type = type,
				baseUrl = baseUrl,
				name = repoName,
				shortName = repoShort,
				website = baseUrl,
				signingKeyFingerprint = baseUrl.hashCode().toString(16), // Use baseUrl hash as pseudo-fingerprint for JAR/IReader
				createdAt = now,
				updatedAt = now,
				lastSuccessAt = now,
				lastError = null,
				version = version,
			)
		}

		val repoJsonUrl = applyMirror("$baseUrl/repo.json")
		val startedAt = System.currentTimeMillis()
		Log.d(TAG, "fetchRepoDetails:start type=$type url=$repoJsonUrl")
		return withTimeout(REPO_DETAILS_TIMEOUT_MS) {
			val body = httpClient.newCall(GET(repoJsonUrl)).awaitSuccess().use { response ->
				response.body.string()
			}
			val dto = json.decodeFromString<RepoMetaWrapperDto>(body)
			val now = System.currentTimeMillis()
			ExternalExtensionRepo(
				type = type,
				baseUrl = baseUrl,
				name = dto.meta.name,
				shortName = dto.meta.shortName,
				website = dto.meta.website,
				signingKeyFingerprint = dto.meta.signingKeyFingerprint,
				createdAt = now,
				updatedAt = now,
				lastSuccessAt = now,
				lastError = null,
			)
		}.also { repo ->
			Log.d(
				TAG,
				"fetchRepoDetails:success type=$type baseUrl=${repo.baseUrl} name=${repo.displayName} elapsedMs=${System.currentTimeMillis() - startedAt}",
			)
		}
	}

suspend fun fetchAvailableExtensions(repo: ExternalExtensionRepo): List<RepoAvailableExtension> {
val startedAt = System.currentTimeMillis()
// --- Keiyoushi v2: try index.json with the new extensionList.extensions schema first ---
if (repo.type == ExternalExtensionType.MIHON || repo.type == ExternalExtensionType.ANIYOMI) {
val newIndexUrl = applyMirror("${repo.baseUrl}/index.json")
Log.d(TAG, "fetchAvailableExtensions:tryNewFormat type=${repo.type} url=$newIndexUrl")
val newResult = runCatching {
withTimeout(CATALOG_TIMEOUT_MS) {
val body = httpClient.newCall(GET(newIndexUrl)).awaitSuccess().use { it.body.string() }
val wrapper = json.decodeFromString<KeyyoushiIndexWrapperDto>(body)
wrapper.extensionList?.extensions
?.mapNotNull { it.toAvailableExtension(repo) }
?.takeIf { it.isNotEmpty() }
}
}.getOrNull()
if (newResult != null) {
Log.d(TAG, "fetchAvailableExtensions:newFormatSuccess type=${repo.type} baseUrl=${repo.baseUrl} count=${newResult.size} elapsedMs=${System.currentTimeMillis() - startedAt}")
return newResult
}
Log.d(TAG, "fetchAvailableExtensions:newFormatMissOrEmpty type=${repo.type} — falling back to index.min.json")
}
// --- Legacy format: index.min.json flat array ---
val indexUrl = "${repo.baseUrl}/index.min.json"
val requestUrl = applyMirror(indexUrl)
Log.d(TAG, "fetchAvailableExtensions:start type=${repo.type} url=$requestUrl")
return runCatching {
withTimeout(CATALOG_TIMEOUT_MS) {
val body = httpClient.newCall(GET(requestUrl)).awaitSuccess().use { response ->
response.body.string()
}
if (repo.type == ExternalExtensionType.IREADER) {
val dto = json.decodeFromString<List<IReaderExtensionIndexDto>>(body)
dto.asSequence()
.mapNotNull { item -> item.toAvailableExtension(repo) }
.toList()
} else {
val dto = json.decodeFromString<List<ExtensionIndexDto>>(body)
dto.asSequence()
.mapNotNull { item -> item.toAvailableExtension(repo) }
.toList()
}
}
}.onSuccess { extensions ->
Log.d(
TAG,
"fetchAvailableExtensions:success type=${repo.type} baseUrl=${repo.baseUrl} count=${extensions.size} elapsedMs=${System.currentTimeMillis() - startedAt}",
)
}.onFailure { error ->
Log.e(
TAG,
"fetchAvailableExtensions:failed type=${repo.type} baseUrl=${repo.baseUrl} elapsedMs=${System.currentTimeMillis() - startedAt} message=${error.message}",
error,
)
}.getOrDefault(emptyList())
}
	fun normalizeIndexUrl(input: String): String? {
		val processUrl = input.trim()

		val url = processUrl.toHttpUrlOrNull() ?: return null
		if (url.scheme != "https") {
			return null
		}
		val normalizedSegments = url.pathSegments
			.filter { it.isNotEmpty() }
			.toMutableList()
		if (normalizedSegments.lastOrNull() != "index.min.json") {
			normalizedSegments += "index.min.json"
		}
		val normalizedPath = "/" + normalizedSegments.joinToString("/")
		return url.newBuilder()
			.encodedPath(normalizedPath)
			.fragment(null)
			.query(null)
			.build()
			.toString()
	}

	fun baseUrlFromIndexUrl(indexUrl: String): String {
		return indexUrl.removeSuffix("/index.min.json")
	}

	private fun ExtensionIndexDto.toAvailableExtension(repo: ExternalExtensionRepo): RepoAvailableExtension? {
		val libVersion = runCatching { version.substringBeforeLast('.').toDouble() }.getOrNull() ?: if (repo.type == ExternalExtensionType.IREADER) 0.0 else return null
		val supported = when (repo.type) {
			ExternalExtensionType.MIHON -> libVersion in MihonExtensionLoader.LIB_VERSION_MIN..MihonExtensionLoader.LIB_VERSION_MAX
			ExternalExtensionType.ANIYOMI -> libVersion in (1.2)..(1.9)
			ExternalExtensionType.IREADER -> true
			ExternalExtensionType.JAR -> true
		}
		val displayName = when (repo.type) {
			ExternalExtensionType.MIHON -> name.removePrefix("Tachiyomi: ")
			ExternalExtensionType.ANIYOMI -> name.removePrefix("Aniyomi: ")
			ExternalExtensionType.IREADER -> name.removePrefix("IReader: ")
			ExternalExtensionType.JAR -> name
		}

		return RepoAvailableExtension(
			type = repo.type,
			name = displayName,
			pkgName = pkg,
			versionName = version,
			versionCode = code,
			libVersion = libVersion,
			lang = lang,
			isNsfw = nsfw == 1,
			sourceNames = sources.orEmpty().map { it.name },
			apkName = apk,
			iconUrl = applyMirror(if (repo.type == ExternalExtensionType.IREADER) "${repo.baseUrl}/icon/${apk.replace(".apk", ".png")}" else "${repo.baseUrl}/icon/$pkg.png"),
			repoUrl = repo.baseUrl,
			repoName = repo.displayName,
			signatureHash = repo.signingKeyFingerprint,
			isCompatible = supported,
		)
	}

	private fun IReaderExtensionIndexDto.toAvailableExtension(repo: ExternalExtensionRepo): RepoAvailableExtension {
		val libVersion = runCatching { version.substringBeforeLast('.').toDouble() }.getOrNull() ?: 0.0
		val displayName = name.removePrefix("IReader: ")

		return RepoAvailableExtension(
			type = repo.type,
			name = displayName,
			pkgName = pkg,
			versionName = version,
			versionCode = code,
			libVersion = libVersion,
			lang = lang,
			isNsfw = nsfw,
			sourceNames = emptyList(), // IReader plugins don't declare subset sources natively
			apkName = apk,
			iconUrl = applyMirror("${repo.baseUrl}/icon/${apk.replace(".apk", ".png")}"),
			repoUrl = repo.baseUrl,
			repoName = repo.displayName,
			// IReader repos currently don't expose a verifiable APK signing fingerprint.
			// `repo.signingKeyFingerprint` is a synthetic repo identifier for repo management,
			// not the package certificate fingerprint, so using it for trust checks would
			// always misclassify installed IReader extensions as untrusted.
			signatureHash = "",
			isCompatible = true,
		)
	}



	@Keep
	@Serializable
	private data class RepoMetaWrapperDto(
		val meta: RepoMetaDto,
	)

	@Keep
	@Serializable
	private data class RepoMetaDto(
		val name: String,
		@SerialName("shortName")
		val shortName: String? = null,
		val website: String,
		@SerialName("signingKeyFingerprint")
		val signingKeyFingerprint: String,
	)

	@Keep
	@Serializable
	private data class ExtensionIndexDto(
		val name: String,
		val pkg: String,
		val apk: String,
		val lang: String = "all",
		val code: Long,
		val version: String,
		val nsfw: Int = 0,
		val sources: List<ExtensionSourceDto>? = null,
	)

	@Keep
	@Serializable
	private data class ExtensionSourceDto(
		val name: String,
	)

	@Keep
	@Serializable
	private data class IReaderExtensionIndexDto(
		val name: String = "",
		val pkg: String = "",
		val apk: String = "",
		val lang: String = "en",
		val code: Long = 1,
		val version: String = "1.0",
		val nsfw: Boolean = false,
	)


@Keep
@Serializable
private data class KeyyoushiIndexWrapperDto(
val extensionList: KeyyoushiExtensionListDto? = null,
)

@Keep
@Serializable
private data class KeyyoushiExtensionListDto(
val extensions: List<KeyyoushiExtensionItemDto> = emptyList(),
)

@Keep
@Serializable
private data class KeyyoushiExtensionItemDto(
val name: String,
val packageName: String,
val resources: KeyyoushiResourcesDto,
val extensionLib: String,
val versionCode: String,
val versionName: String,
val contentWarning: String? = null,
val sources: List<KeyyoushiSourceDto>? = null,
)

@Keep
@Serializable
private data class KeyyoushiResourcesDto(
val apkUrl: String,
val iconUrl: String,
val jarUrl: String? = null,
)

@Keep
@Serializable
private data class KeyyoushiSourceDto(
val name: String,
val language: String,
val id: String? = null,
val homeUrl: String? = null,
)

private fun KeyyoushiExtensionItemDto.toAvailableExtension(repo: ExternalExtensionRepo): RepoAvailableExtension? {
val lib = runCatching { extensionLib.toDouble() }.getOrNull() ?: return null
val code = versionCode.toLongOrNull() ?: return null
val supported = when (repo.type) {
ExternalExtensionType.MIHON -> lib in MihonExtensionLoader.LIB_VERSION_MIN..MihonExtensionLoader.LIB_VERSION_MAX
ExternalExtensionType.ANIYOMI -> lib in (1.2)..(1.9)
else -> false
}
val displayName = name.removePrefix("Tachiyomi: ").removePrefix("Aniyomi: ")
val apkFileName = resources.apkUrl  // full CDN URL; ExtensionInstallService detects https:// prefix
val primaryLang = sources?.firstOrNull()?.language ?: "all"
return RepoAvailableExtension(
type = repo.type,
name = displayName,
pkgName = packageName,
versionName = versionName,
versionCode = code,
libVersion = lib,
lang = primaryLang,
isNsfw = contentWarning?.contains("NSFW", ignoreCase = true) == true,
sourceNames = sources.orEmpty().map { it.name },
apkName = apkFileName,  // full URL for Keiyoushi v2
iconUrl = applyMirror(resources.iconUrl),
repoUrl = repo.baseUrl,
repoName = repo.displayName,
signatureHash = repo.signingKeyFingerprint,
isCompatible = supported,
)
}

	private companion object {
		const val TAG = "ExtensionRepo"
		const val REPO_DETAILS_TIMEOUT_MS = 15_000L
		const val CATALOG_TIMEOUT_MS = 20_000L
	}
}
