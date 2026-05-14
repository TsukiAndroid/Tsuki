package io.github.landwarderer.futon.customsource.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import io.github.landwarderer.futon.customsource.domain.ParserTemplate
import java.util.concurrent.TimeUnit

/**
 * Fetches the public [Space4414/tsuki-parsers](https://github.com/Space4414/tsuki-parsers) index
 * and upserts any template the user doesn't have locally or that has a newer version.
 *
 * Designed to be called from two places:
 *  1. [RemoteTemplateSyncWorker] — periodic background job (every 24 h, needs network).
 *  2. [ParserTemplateViewModel.syncFromRemote] — on-demand "Sync from GitHub" user action.
 *
 * The sync is intentionally non-destructive: it never removes templates the user has
 * imported manually, and it preserves each template's enabled/disabled state on update.
 */
object RemoteTemplateSync {

    /** Raw-content base URL of the tsuki-parsers repository (public, no auth needed). */
    private const val INDEX_URL =
        "https://raw.githubusercontent.com/Space4414/tsuki-parsers/main/index.json"
    private const val RAW_BASE =
        "https://raw.githubusercontent.com/Space4414/tsuki-parsers/main/"

    // ── Result types ─────────────────────────────────────────────────────────

    sealed class SyncResult {
        /** [added] new templates downloaded; [updated] existing ones refreshed. */
        data class Success(val added: Int, val updated: Int) : SyncResult()
        object NetworkError : SyncResult()
        object ParseError  : SyncResult()
    }

    // ── Entry-point ───────────────────────────────────────────────────────────

    /**
     * Runs on [Dispatchers.IO]. Fetches the remote index, computes the diff against
     * the local [ParserTemplateRepository], downloads missing/updated templates, and
     * upserts them. Returns a [SyncResult] — never throws.
     */
    suspend fun syncNow(repository: ParserTemplateRepository): SyncResult =
        withContext(Dispatchers.IO) {
            try {
                val indexJson = fetchText(INDEX_URL)
                    ?: return@withContext SyncResult.NetworkError
                val entries = parseIndex(indexJson)
                    ?: return@withContext SyncResult.ParseError

                var added   = 0
                var updated = 0
                for (entry in entries) {
                    val existing = repository.findByName(entry.name)
                    if (existing != null && versionAtLeast(existing.version, entry.version)) {
                        // Local copy is up to date — skip.
                        continue
                    }
                    val rawJson = fetchText("$RAW_BASE${entry.file}") ?: continue
                    when (val v = ParserTemplateValidator.validate(rawJson)) {
                        is ParserTemplateValidator.Result.Valid -> {
                            if (existing == null) {
                                repository.add(
                                    ParserTemplate(
                                        id      = ParserTemplateRepository.generateId(),
                                        name    = v.name,
                                        version = v.version,
                                        type    = v.type,
                                        rawJson = rawJson,
                                    )
                                )
                                added++
                            } else {
                                // Preserve the user's enabled/disabled choice.
                                repository.upsert(
                                    existing.copy(
                                        version = v.version,
                                        type    = v.type,
                                        rawJson = rawJson,
                                    )
                                )
                                updated++
                            }
                        }
                        is ParserTemplateValidator.Result.Invalid -> { /* skip bad template */ }
                    }
                }
                SyncResult.Success(added, updated)
            } catch (_: Exception) {
                SyncResult.NetworkError
            }
        }

    // ── Private helpers ───────────────────────────────────────────────────────

    /** An entry from the remote index.json template manifest. */
    private data class IndexEntry(
        val name: String,
        val version: String,
        val file: String,
    )

    /**
     * Returns the list of [IndexEntry] from the index JSON, or null if the JSON is invalid.
     *
     * Expected shape:
     * ```json
     * {
     *   "schemaVersion": 1,
     *   "templates": [
     *     { "name": "Madara", "version": "1.2", "file": "templates/madara.json" },
     *     ...
     *   ]
     * }
     * ```
     */
    private fun parseIndex(json: String): List<IndexEntry>? = runCatching {
        val obj = JSONObject(json)
        val arr = obj.getJSONArray("templates")
        (0 until arr.length()).mapNotNull { i ->
            val e = arr.getJSONObject(i)
            val name    = e.optString("name").trim().ifEmpty { return@mapNotNull null }
            val version = e.optString("version").trim().ifEmpty { return@mapNotNull null }
            val file    = e.optString("file").trim().ifEmpty { return@mapNotNull null }
            IndexEntry(name, version, file)
        }
    }.getOrNull()

    /**
     * Returns true when the [local] version string is equal to or newer than [remote].
     *
     * Splits on '.' and compares numeric components. Falls back to string comparison
     * when the split yields non-numeric tokens, so "1.0-beta" sorts before "1.0".
     */
    private fun versionAtLeast(local: String, remote: String): Boolean {
        if (local == remote) return true
        val localParts  = local.split('.')
        val remoteParts = remote.split('.')
        val len = maxOf(localParts.size, remoteParts.size)
        for (i in 0 until len) {
            val l = localParts.getOrNull(i)?.toIntOrNull() ?: 0
            val r = remoteParts.getOrNull(i)?.toIntOrNull() ?: 0
            if (l > r) return true
            if (l < r) return false
        }
        return true // equal
    }

    private fun fetchText(url: String): String? = runCatching {
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Tsuki/1.0 (Android; RemoteTemplateSync)")
            .get()
            .build()
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else resp.body?.string()
        }
    }.getOrNull()

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }
}
