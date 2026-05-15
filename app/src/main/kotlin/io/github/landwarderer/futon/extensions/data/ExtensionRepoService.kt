package io.github.landwarderer.futon.extensions.data

import android.content.SharedPreferences
import io.github.landwarderer.futon.core.network.BaseHttpClient
import io.github.landwarderer.futon.extensions.domain.ExtensionType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Represents a remote extension repository entry (its index URL + display name).
 */
data class ExtensionRepo(
    val name: String,
    val indexUrl: String,
)

/**
 * Represents an extension available from a remote repository index.
 *
 * index.json expected format:
 * ```json
 * {
 *   "extensions": [
 *     {
 *       "name": "MangaDex",
 *       "version": "1.0.0",
 *       "author": "Tsuki Team",
 *       "description": "MangaDex support via JS",
 *       "baseUrl": "https://mangadex.org",
 *       "language": "en",
 *       "iconUrl": "https://…/icon.png",
 *       "type": "JS",
 *       "downloadUrl": "https://…/mangadex.js",
 *       "packageName": ""
 *     }
 *   ]
 * }
 * ```
 */
data class AvailableExtension(
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val baseUrl: String,
    val language: String,
    val iconUrl: String,
    val type: ExtensionType,
    val downloadUrl: String,
    val packageName: String,
    val templateName: String,
)

/**
 * Fetches and caches extension repository indices and downloads extension source code.
 *
 * Also persists the list of user-added repo URLs via SharedPreferences.
 */
@Singleton
class ExtensionRepoService @Inject constructor(
    @BaseHttpClient private val okHttpClient: OkHttpClient,
    @Named("extensions_prefs") private val prefs: SharedPreferences,
) {

    fun getRepos(): List<ExtensionRepo> {
        val raw = prefs.getString(KEY_REPOS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val obj = arr.getJSONObject(i)
                    ExtensionRepo(
                        name = obj.optString("name", obj.getString("indexUrl")),
                        indexUrl = obj.getString("indexUrl"),
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    fun addRepo(repo: ExtensionRepo) {
        val all = getRepos().toMutableList()
        if (all.none { it.indexUrl == repo.indexUrl }) {
            all.add(repo)
            persistRepos(all)
        }
    }

    fun removeRepo(indexUrl: String) {
        val all = getRepos().toMutableList()
        all.removeAll { it.indexUrl == indexUrl }
        persistRepos(all)
    }

    suspend fun fetchIndex(repo: ExtensionRepo): List<AvailableExtension> = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(repo.indexUrl).build()
        val body = okHttpClient.newCall(request).execute().use { it.body?.string() }
            ?: return@withContext emptyList()
        parseIndex(body)
    }

    suspend fun downloadSourceCode(downloadUrl: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(downloadUrl).build()
        okHttpClient.newCall(request).execute().use { it.body?.string() ?: "" }
    }

    private fun parseIndex(json: String): List<AvailableExtension> = runCatching {
        val root = JSONObject(json)
        val arr = root.optJSONArray("extensions") ?: return emptyList()
        (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val obj = arr.getJSONObject(i)
                AvailableExtension(
                    name = obj.getString("name"),
                    version = obj.optString("version", "1.0.0"),
                    author = obj.optString("author", ""),
                    description = obj.optString("description", ""),
                    baseUrl = obj.optString("baseUrl", ""),
                    language = obj.optString("language", "en"),
                    iconUrl = obj.optString("iconUrl", ""),
                    type = runCatching { ExtensionType.valueOf(obj.getString("type")) }
                        .getOrDefault(ExtensionType.JS),
                    downloadUrl = obj.optString("downloadUrl", ""),
                    packageName = obj.optString("packageName", ""),
                    templateName = obj.optString("templateName", ""),
                )
            }.getOrNull()
        }
    }.getOrDefault(emptyList())

    private fun persistRepos(repos: List<ExtensionRepo>) {
        val arr = JSONArray()
        repos.forEach { repo ->
            arr.put(JSONObject().apply {
                put("name", repo.name)
                put("indexUrl", repo.indexUrl)
            })
        }
        prefs.edit().putString(KEY_REPOS, arr.toString()).apply()
    }

    companion object {
        private const val KEY_REPOS = "extension_repos"
    }
}
