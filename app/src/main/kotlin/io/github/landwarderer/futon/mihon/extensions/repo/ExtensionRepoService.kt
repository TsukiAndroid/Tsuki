package io.github.landwarderer.futon.mihon.extensions.repo

import android.util.Log
import androidx.annotation.Keep
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import io.github.landwarderer.futon.core.network.MangaHttpClient
import io.github.landwarderer.futon.core.prefs.AppSettings
import io.github.landwarderer.futon.core.prefs.GitHubMirror
import io.github.landwarderer.futon.mihon.MihonExtensionLoader
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.ProtoNumber
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalSerializationApi::class)
@Singleton
class ExtensionRepoService @Inject constructor(
	@MangaHttpClient private val httpClient: OkHttpClient,
	private val json: Json,
	private val settings: AppSettings,
) {

	private val protoBuf = ProtoBuf {
		@Suppress("OPT_IN_USAGE")
		encodeDefaults = true
	}

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
		if (url.startsWith("https://github.com/")) {
			return when (settings.gitHubMirror) {
				GitHubMirror.KKGITHUB -> url.replace("github.com", "kkgithub.com")
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
		Log.d(TAG, "fetchAvailableExtensions:start type=${repo.type} baseUrl=${repo.baseUrl}")

		if (repo.type == ExternalExtensionType.MIHON || repo.type == ExternalExtensionType.ANIYOMI) {
			val protobufResult = runCatching {
				val protobufUrl = applyMirror("${repo.baseUrl}/index.pb")
				withTimeout(CATALOG_TIMEOUT_MS) {
					val rawBytes = httpClient.newCall(GET(protobufUrl)).awaitSuccess().use { response ->
						response.body.bytes()
					}
					val decodedBytes = if (
						rawBytes.size >= 2 &&
						rawBytes[0] == 0x1f.toByte() &&
						rawBytes[1] == 0x8b.toByte()
					) {
						GZIPInputStream(ByteArrayInputStream(rawBytes)).use { it.readBytes() }
					} else {
						rawBytes
					}
					val store = protoBuf.decodeFromByteArray<NetworkExtensionStoreDto>(decodedBytes)
					store.extensionList?.extensions.orEmpty()
						.asSequence()
						.filterNot { extension ->
							extension.packageName == "eu.kanade.tachiyomi.extension.all.outdated" ||
								extension.packageName.endsWith(".outdated") ||
								extension.name.startsWith("Outdated App", ignoreCase = true) ||
								extension.name.contains("Update to Mihon", ignoreCase = true)
						}
						.mapNotNull { extension -> extension.toAvailableExtension(repo) }
						.toList()
				}
			}

			protobufResult.getOrNull()
				?.takeIf { it.isNotEmpty() }
				?.let { extensions ->
					Log.d(
						TAG,
						"fetchAvailableExtensions:success (protobuf) type=${repo.type} baseUrl=${repo.baseUrl} count=${extensions.size} elapsedMs=${System.currentTimeMillis() - startedAt}",
					)
					return extensions
				}
			protobufResult.exceptionOrNull()?.let { error ->
				Log.d(TAG, "fetchAvailableExtensions:protobuf fallback to json: ${error.message}")
			}

			// Keiyoushi's index.json is a newer JSON schema. Keep it as a fallback for
			// repositories which publish that format but not index.pb.
			val newIndexUrl = applyMirror("${repo.baseUrl}/index.json")
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
				Log.d(
					TAG,
					"fetchAvailableExtensions:success (index.json) type=${repo.type} baseUrl=${repo.baseUrl} count=${newResult.size} elapsedMs=${System.currentTimeMillis() - startedAt}",
				)
				return newResult
			}
		}

		val requestUrl = applyMirror("${repo.baseUrl}/index.min.json")
		return runCatching {
			withTimeout(CATALOG_TIMEOUT_MS) {
				val body = httpClient.newCall(GET(requestUrl)).awaitSuccess().use { response ->
					response.body.string()
				}
				if (repo.type == ExternalExtensionType.IREADER) {
					json.decodeFromString<List<IReaderExtensionIndexDto>>(body)
						.mapNotNull { item -> item.toAvailableExtension(repo) }
				} else {
					json.decodeFromString<List<ExtensionIndexDto>>(body)
						.mapNotNull { item -> item.toAvailableExtension(repo) }
				}
			}
		}.onSuccess { extensions ->
			Log.d(
				TAG,
				"fetchAvailableExtensions:success (json) type=${repo.type} baseUrl=${repo.baseUrl} count=${extensions.size} elapsedMs=${System.currentTimeMillis() - startedAt}",
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
		var url = input.trim().toHttpUrlOrNull() ?: return null
		if (url.scheme != "https") {
			return null
		}

		// GitHub's blob/tree pages are HTML, not repository files. Convert them
		// to the equivalent raw.githubusercontent.com path before saving the repo.
		if (url.host == "github.com") {
			val segments = url.pathSegments.filter { it.isNotEmpty() }
			if (segments.size >= 3 && segments[2] in setOf("raw", "tree", "blob")) {
				val remainder = segments.drop(3).let { path ->
					if (path.size >= 3 && path[0] == "refs" && path[1] in setOf("heads", "tags")) {
						path.drop(2)
					} else {
						path
					}
				}
				if (remainder.isNotEmpty()) {
					url = url.newBuilder()
						.host("raw.githubusercontent.com")
						.encodedPath("/${segments[0]}/${segments[1]}/${remainder.joinToString("/")}")
						.build()
				}
			} else if (
				segments.size >= 5 &&
				segments[2] == "refs" &&
				segments[3] in setOf("heads", "tags")
			) {
				url = url.newBuilder()
					.host("raw.githubusercontent.com")
					.encodedPath("/${segments[0]}/${segments[1]}/${segments.drop(4).joinToString("/")}")
					.build()
			}
		}

		val normalizedSegments = url.pathSegments
			.filter { it.isNotEmpty() }
			.toMutableList()
		if (normalizedSegments.lastOrNull() in setOf("index.min.json", "index.json", "index.pb", "repo.json")) {
			normalizedSegments[normalizedSegments.lastIndex] = "index.min.json"
		} else {
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
		@SerialName("index_v2")
		val indexV2: String? = null,
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

	private fun ExtensionDto.toAvailableExtension(repo: ExternalExtensionRepo): RepoAvailableExtension? {
		val libVersion = runCatching {
			val parts = extensionLib.split('.')
			if (parts.size >= 2) "${parts[0]}.${parts[1]}".toDouble() else parts[0].toDouble()
		}.getOrNull() ?: return null

		val supported = when (repo.type) {
			ExternalExtensionType.MIHON -> libVersion in MihonExtensionLoader.LIB_VERSION_MIN..MihonExtensionLoader.LIB_VERSION_MAX
			ExternalExtensionType.ANIYOMI -> libVersion in (1.2)..(2.5)
			ExternalExtensionType.IREADER, ExternalExtensionType.JAR -> true
		}
		val displayName = when (repo.type) {
			ExternalExtensionType.MIHON -> name.removePrefix("Tachiyomi: ")
			ExternalExtensionType.ANIYOMI -> name.removePrefix("Aniyomi: ")
			ExternalExtensionType.IREADER -> name.removePrefix("IReader: ")
			ExternalExtensionType.JAR -> name
		}
		val languages = sources.map { it.language }.filter { it.isNotEmpty() }.toSet()
		val language = languages.singleOrNull() ?: "all"
		val iconUrl = when {
			resources.iconUrl.startsWith("http://") || resources.iconUrl.startsWith("https://") ->
				applyMirror(resources.iconUrl)
			resources.iconUrl.isNotEmpty() ->
				applyMirror("${repo.baseUrl}/${resources.iconUrl.trimStart('/')}")
			else ->
				applyMirror("${repo.baseUrl}/icon/${resources.apkUrl.substringAfterLast('/').replace(".apk", ".png")}")
		}

		return RepoAvailableExtension(
			type = repo.type,
			name = displayName,
			pkgName = packageName,
			versionName = versionName,
			versionCode = versionCode,
			libVersion = libVersion,
			lang = language,
			isNsfw = contentWarning >= 2,
			sourceNames = sources.map { it.name },
			apkName = resources.apkUrl,
			iconUrl = iconUrl,
			repoUrl = repo.baseUrl,
			repoName = repo.displayName,
			signatureHash = repo.signingKeyFingerprint,
			isCompatible = supported,
		)
	}

	@Keep
	@Serializable
	private data class NetworkExtensionStoreDto(
		@ProtoNumber(1) val name: String = "",
		@ProtoNumber(2) val badgeLabel: String = "",
		@ProtoNumber(3) val signingKey: String = "",
		@ProtoNumber(4) val contact: ContactDto? = null,
		@ProtoNumber(101) val extensionList: ExtensionListDto? = null,
		@ProtoNumber(102) val extensionListUrl: String? = null,
	)

	@Keep
	@Serializable
	private data class ContactDto(
		@ProtoNumber(1) val website: String = "",
		@ProtoNumber(2) val discord: String? = null,
	)

	@Keep
	@Serializable
	private data class ExtensionListDto(
		@ProtoNumber(1) val extensions: List<ExtensionDto> = emptyList(),
	)

	@Keep
	@Serializable
	private data class ExtensionDto(
		@ProtoNumber(1) val name: String = "",
		@ProtoNumber(2) val packageName: String = "",
		@ProtoNumber(3) val resources: ResourcesDto = ResourcesDto(),
		@ProtoNumber(4) val extensionLib: String = "",
		@ProtoNumber(5) val versionCode: Long = 0L,
		@ProtoNumber(6) val versionName: String = "",
		@ProtoNumber(7) val contentWarning: Int = 0,
		@ProtoNumber(8) val sources: List<SourceDto> = emptyList(),
	)

	@Keep
	@Serializable
	private data class ResourcesDto(
		@ProtoNumber(1) val apkUrl: String = "",
		@ProtoNumber(2) val iconUrl: String = "",
	)

	@Keep
	@Serializable
	private data class SourceDto(
		@ProtoNumber(1) val id: Long = 0L,
		@ProtoNumber(2) val name: String = "",
		@ProtoNumber(3) val language: String = "",
		@ProtoNumber(4) val homeUrl: String = "",
		@ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
		@ProtoNumber(7) val message: String? = null,
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
