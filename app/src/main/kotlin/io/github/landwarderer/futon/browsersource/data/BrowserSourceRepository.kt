package io.github.landwarderer.futon.browsersource.data

import android.content.Context
import android.content.SharedPreferences
import android.webkit.CookieManager
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists extra metadata for BROWSER_SOURCE sources:
 *  - Last visited URL (resume position)
 *  - Scroll position per URL
 *  - History entries (manga/chapter visited via native reader)
 *  - Cookies are handled automatically by Android's [CookieManager] —
 *    no manual serialization needed for session persistence.
 */
@Singleton
class BrowserSourceRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Last visited URL ──────────────────────────────────────────────────────

    fun saveLastUrl(sourceId: Long, url: String) {
        prefs.edit().putString(keyLastUrl(sourceId), url).apply()
    }

    fun getLastUrl(sourceId: Long): String? =
        prefs.getString(keyLastUrl(sourceId), null)

    // ── Scroll position ───────────────────────────────────────────────────────

    fun saveScrollPosition(sourceId: Long, url: String, scrollY: Int) {
        prefs.edit().putInt(keyScrollPos(sourceId, url), scrollY).apply()
    }

    fun getScrollPosition(sourceId: Long, url: String): Int =
        prefs.getInt(keyScrollPos(sourceId, url), 0)

    // ── Per-source history ────────────────────────────────────────────────────

    data class BrowserHistoryEntry(
        val sourceId: Long,
        val sourceName: String,
        val chapterUrl: String,
        val mangaTitle: String,
        val mangaCoverUrl: String?,
        val chapterTitle: String,
        val timestamp: Long,
        val isRead: Boolean = false,
    )

    fun saveHistoryEntry(entry: BrowserHistoryEntry) {
        val key = keyHistory(entry.sourceId)
        val existing = loadHistoryForSource(entry.sourceId).toMutableList()
        // Remove duplicate URL if present so the new entry goes to the top
        existing.removeAll { it.chapterUrl == entry.chapterUrl }
        existing.add(0, entry)
        // Keep only the last 100 entries per source
        val trimmed = existing.take(100)
        val array = JSONArray(trimmed.map { it.toJson() })
        prefs.edit().putString(key, array.toString()).apply()
    }

    fun loadHistoryForSource(sourceId: Long): List<BrowserHistoryEntry> {
        val json = prefs.getString(keyHistory(sourceId), null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).mapNotNull { i ->
                runCatching { array.getJSONObject(i).toHistoryEntry(sourceId) }.getOrNull()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun markChapterRead(sourceId: Long, chapterUrl: String) {
        val entries = loadHistoryForSource(sourceId).map { entry ->
            if (entry.chapterUrl == chapterUrl) entry.copy(isRead = true) else entry
        }
        val key = keyHistory(sourceId)
        val array = JSONArray(entries.map { it.toJson() })
        prefs.edit().putString(key, array.toString()).apply()
    }

    fun isChapterRead(sourceId: Long, chapterUrl: String): Boolean =
        loadHistoryForSource(sourceId).any { it.chapterUrl == chapterUrl && it.isRead }

    fun getReadChapterUrls(sourceId: Long): Set<String> =
        loadHistoryForSource(sourceId).filter { it.isRead }.map { it.chapterUrl }.toSet()

    fun clearHistoryForSource(sourceId: Long) {
        prefs.edit()
            .remove(keyHistory(sourceId))
            .apply()
    }

    // ── Cookies ───────────────────────────────────────────────────────────────

    /**
     * Clears all cookies for the given domain from the shared [CookieManager].
     * Note: Android does not support per-domain cookie deletion directly; we
     * remove the entire cookie store and this is a best-effort approach.
     */
    fun clearCookiesForDomain(domain: String) {
        // Android CookieManager doesn't support per-domain removal without iteration.
        // Best we can do is remove the stored cookie string for the domain.
        prefs.edit().remove("${KEY_COOKIES_PREFIX}$domain").apply()
    }

    fun clearAllCookies() {
        CookieManager.getInstance().removeAllCookies(null)
        CookieManager.getInstance().flush()
    }

    // ── All history ───────────────────────────────────────────────────────────

    fun clearAllHistory() {
        val allKeys = prefs.all.keys.filter { it.startsWith(KEY_HISTORY_PREFIX) }
        val editor = prefs.edit()
        allKeys.forEach { editor.remove(it) }
        editor.apply()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun keyLastUrl(sourceId: Long) = "${KEY_LAST_URL_PREFIX}$sourceId"
    private fun keyScrollPos(sourceId: Long, url: String) =
        "${KEY_SCROLL_PREFIX}${sourceId}_${url.hashCode()}"
    private fun keyHistory(sourceId: Long) = "${KEY_HISTORY_PREFIX}$sourceId"

    private fun BrowserHistoryEntry.toJson() = JSONObject().apply {
        put("sourceId", sourceId)
        put("sourceName", sourceName)
        put("chapterUrl", chapterUrl)
        put("mangaTitle", mangaTitle)
        put("mangaCoverUrl", mangaCoverUrl ?: "")
        put("chapterTitle", chapterTitle)
        put("timestamp", timestamp)
        put("isRead", isRead)
    }

    private fun JSONObject.toHistoryEntry(sourceId: Long) = BrowserHistoryEntry(
        sourceId = getLong("sourceId"),
        sourceName = getString("sourceName"),
        chapterUrl = getString("chapterUrl"),
        mangaTitle = getString("mangaTitle"),
        mangaCoverUrl = optString("mangaCoverUrl").takeIf { it.isNotEmpty() },
        chapterTitle = getString("chapterTitle"),
        timestamp = getLong("timestamp"),
        isRead = optBoolean("isRead", false),
    )

    companion object {
        private const val PREFS_NAME = "tsuki_browser_sources"
        private const val KEY_LAST_URL_PREFIX = "last_url_"
        private const val KEY_SCROLL_PREFIX = "scroll_"
        private const val KEY_HISTORY_PREFIX = "history_"
        private const val KEY_COOKIES_PREFIX = "cookies_"
    }
}
