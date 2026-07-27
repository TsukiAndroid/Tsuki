package io.github.landwarderer.futon.plugins.data

import android.util.Log
import io.github.landwarderer.futon.core.network.MangaHttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads plugin .jar files from GitHub releases.
 *
 * Parses the GitHub Releases API to find the latest .jar asset and saves
 * it to the provided output directory.
 */
@Singleton
class PluginDownloader @Inject constructor(
    @MangaHttpClient private val okHttpClient: OkHttpClient,
) {
    companion object {
        private const val TAG = "PluginDownloader"
    }

    /**
     * Downloads the latest .jar from [repoUrl].
     *
     * [repoUrl] can be either "owner/repo" or a full "https://github.com/owner/repo" URL.
     *
     * @param outputDir  directory where the .jar will be saved
     * @param onProgress callback with bytes downloaded and total size (total may be -1 if unknown)
     * @return The saved [File] on success, null on failure.
     */
    suspend fun downloadFromGithub(
        repoUrl: String,
        outputDir: File,
        onProgress: ((downloaded: Long, total: Long) -> Unit)? = null,
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val (owner, repo) = parseGithubRepo(repoUrl)
            val releaseJson   = fetchLatestRelease(owner, repo)
            val assets        = releaseJson.getJSONArray("assets")
            val jarAsset      = findJarAsset(assets) ?: run {
                Log.w(TAG, "No .jar asset found in latest release of $owner/$repo")
                return@runCatching null
            }
            val downloadUrl = jarAsset.getString("browser_download_url")
            val fileName    = jarAsset.getString("name")

            outputDir.mkdirs()
            val outputFile = File(outputDir, fileName)
            downloadFile(downloadUrl, outputFile, onProgress)
            outputFile
        }.getOrElse { e ->
            Log.e(TAG, "Download failed for $repoUrl: ${e.message}", e)
            null
        }
    }

    /**
     * Fetches the latest release JSON for a repo. Used to extract plugin metadata
     * before committing to a download (UI preview step).
     */
    suspend fun fetchLatestReleaseInfo(repoUrl: String): JSONObject? = withContext(Dispatchers.IO) {
        runCatching {
            val (owner, repo) = parseGithubRepo(repoUrl)
            fetchLatestRelease(owner, repo)
        }.getOrElse { e ->
            Log.e(TAG, "Failed to fetch release info for $repoUrl: ${e.message}", e)
            null
        }
    }

    /**
     * Returns the latest version tag from GitHub releases if it differs from
     * [currentVersion], otherwise returns null (plugin is up to date).
     */
    suspend fun checkForUpdate(repoUrl: String, currentVersion: String): String? =
        withContext(Dispatchers.IO) {
            runCatching {
                val (owner, repo) = parseGithubRepo(repoUrl)
                val release = fetchLatestRelease(owner, repo)
                val latestTag = release.getString("tag_name")
                if (latestTag != currentVersion) latestTag else null
            }.getOrElse { e ->
                Log.e(TAG, "Update check failed for $repoUrl: ${e.message}", e)
                null
            }
        }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    fun parseGithubRepo(input: String): Pair<String, String> {
        val cleaned = input
            .removePrefix("https://github.com/")
            .removePrefix("http://github.com/")
            .trim('/')
        val parts = cleaned.split("/")
        require(parts.size >= 2) { "Invalid GitHub repo: $input" }
        return Pair(parts[0], parts[1])
    }

    private fun fetchLatestRelease(owner: String, repo: String): JSONObject {
        val url = "https://api.github.com/repos/$owner/$repo/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .build()
        val response = okHttpClient.newCall(request).execute()
        check(response.isSuccessful) { "GitHub API returned ${response.code} for $url" }
        return JSONObject(response.body?.string() ?: error("Empty response from GitHub API"))
    }

    private fun findJarAsset(assets: JSONArray): JSONObject? {
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".jar")) return asset
        }
        return null
    }

    private fun downloadFile(
        url: String,
        outputFile: File,
        onProgress: ((Long, Long) -> Unit)?,
    ) {
        val request  = Request.Builder().url(url).build()
        val response = okHttpClient.newCall(request).execute()
        check(response.isSuccessful) { "Download failed: HTTP ${response.code}" }

        val body  = response.body ?: error("Empty download response")
        val total = body.contentLength()
        var downloaded = 0L

        body.byteStream().use { input ->
            outputFile.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var bytes: Int
                while (input.read(buffer).also { bytes = it } != -1) {
                    output.write(buffer, 0, bytes)
                    downloaded += bytes
                    onProgress?.invoke(downloaded, total)
                }
            }
        }
    }
}
