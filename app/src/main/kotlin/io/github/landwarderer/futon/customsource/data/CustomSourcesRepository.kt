package io.github.landwarderer.futon.customsource.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import io.github.landwarderer.futon.customsource.domain.CustomSource
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CustomSourcesRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _sources = MutableStateFlow(loadAll())
    val sources: StateFlow<List<CustomSource>> = _sources.asStateFlow()

    init {
        INSTANCE = this
    }

    fun getAll(): List<CustomSource> = _sources.value

    fun add(source: CustomSource) {
        val updated = _sources.value.toMutableList().apply { add(source) }
        saveAll(updated)
        _sources.value = updated
    }

    fun remove(id: Long) {
        val updated = _sources.value.filter { it.id != id }
        saveAll(updated)
        _sources.value = updated
    }

    fun update(source: CustomSource) {
        val updated = _sources.value.map { if (it.id == source.id) source else it }
        saveAll(updated)
        _sources.value = updated
    }

    fun findById(id: Long): CustomSource? = _sources.value.find { it.id == id }

    private fun loadAll(): List<CustomSource> {
        val json = prefs.getString(KEY_SOURCES, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i -> array.getJSONObject(i).toCustomSource() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun saveAll(sources: List<CustomSource>) {
        val array = JSONArray(sources.map { it.toJson() })
        prefs.edit().putString(KEY_SOURCES, array.toString()).apply()
    }

    private fun JSONObject.toCustomSource() = CustomSource(
        id = getLong("id"),
        name = getString("name"),
        baseUrl = getString("baseUrl"),
        type = runCatching { CustomSourceType.valueOf(getString("type")) }.getOrElse { CustomSourceType.WEBVIEW },
        iconUrl = optString("iconUrl").takeIf { it.isNotEmpty() },
        description = optString("description").takeIf { it.isNotEmpty() },
        parserSourceName = optString("parserSourceName").takeIf { it.isNotEmpty() },
        createdAt = optLong("createdAt", System.currentTimeMillis()),
    )

    private fun CustomSource.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("baseUrl", baseUrl)
        put("type", type.name)
        put("iconUrl", iconUrl ?: "")
        put("description", description ?: "")
        put("parserSourceName", parserSourceName ?: "")
        put("createdAt", createdAt)
    }

    fun saveLastUrl(sourceId: Long, url: String) {
        prefs.edit().putString("$KEY_LAST_URL_PREFIX$sourceId", url).apply()
    }

    fun getLastUrl(sourceId: Long): String? =
        prefs.getString("$KEY_LAST_URL_PREFIX$sourceId", null)

    /**
     * Serialises all current sources to a pretty-printed JSON string suitable
     * for writing to a file. The format is a JSON array using the same keys as
     * the internal SharedPreferences store, so it round-trips cleanly through
     * [importJson].
     */
    fun exportJson(): String {
        val array = JSONArray(_sources.value.map { it.toJson() })
        return array.toString(2)
    }

    /**
     * Parses a JSON string (previously produced by [exportJson] or compatible
     * tools) and merges any sources not already present (matched by baseUrl,
     * case-insensitive). Each imported source receives a fresh id to avoid
     * collisions with local sources.
     *
     * @return the number of sources actually added (duplicates are skipped).
     */
    fun importJson(json: String): Int {
        val array = JSONArray(json)
        val existing = _sources.value
        val existingUrls = existing.map { it.baseUrl.lowercase() }.toHashSet()
        val toAdd = mutableListOf<CustomSource>()
        val baseId = System.currentTimeMillis()
        for (i in 0 until array.length()) {
            try {
                val cs = array.getJSONObject(i).toCustomSource()
                if (cs.baseUrl.lowercase() !in existingUrls) {
                    toAdd.add(cs.copy(id = baseId + i))
                    existingUrls.add(cs.baseUrl.lowercase())
                }
            } catch (_: Exception) {
                // skip malformed entries
            }
        }
        if (toAdd.isNotEmpty()) {
            val updated = existing + toAdd
            saveAll(updated)
            _sources.value = updated
        }
        return toAdd.size
    }

    companion object {
        private const val PREFS_NAME = "tsuki_custom_sources"
        private const val KEY_SOURCES = "sources"
        private const val KEY_LAST_URL_PREFIX = "last_url_"

        @Volatile
        private var INSTANCE: CustomSourcesRepository? = null

        fun peekById(id: Long): CustomSource? = INSTANCE?.findById(id)

        fun peekAll(): List<CustomSource> = INSTANCE?.getAll().orEmpty()

        fun generateId(): Long = System.currentTimeMillis()
    }
}
