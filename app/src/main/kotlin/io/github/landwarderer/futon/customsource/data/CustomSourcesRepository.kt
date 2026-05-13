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
import java.util.concurrent.atomic.AtomicLong
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
        // Seed ID counter above the highest existing ID so new IDs never collide
        // with persisted ones even across app restarts.
        val maxExisting = _sources.value.maxOfOrNull { it.id } ?: 0L
        idCounter.getAndUpdate { current -> maxOf(current, maxExisting + 1) }
    }

    fun getAll(): List<CustomSource> = _sources.value

    /** Returns all sources that are currently enabled. */
    fun getEnabled(): List<CustomSource> = _sources.value.filter { it.isEnabled }

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

    /** Flip the enabled flag of a source. No-op if the id is not found. */
    fun setEnabled(id: Long, enabled: Boolean) {
        val source = findById(id) ?: return
        update(source.copy(isEnabled = enabled))
    }

    fun findById(id: Long): CustomSource? = _sources.value.find { it.id == id }

    /**
     * Finds an existing source whose normalised base URL matches [url].
     * Used for duplicate detection before adding a new source.
     */
    fun findByUrl(url: String): CustomSource? {
        val normalised = url.trimEnd('/').lowercase()
        return _sources.value.find { it.baseUrl.trimEnd('/').lowercase() == normalised }
    }

    // ── Built-in parser enable/disable ────────────────────────────────────────

    /**
     * Returns the set of [CustomSourceType] names that the user has disabled in
     * the parser picker. Disabled parsers are still stored but hidden from the
     * picker UI and skipped during manual selection flows.
     */
    fun getDisabledBuiltinParsers(): Set<String> =
        prefs.getStringSet(KEY_DISABLED_PARSERS, emptySet()).orEmpty().toSet()

    /**
     * Enable or disable a built-in parser type in the parser picker.
     * Persists to [SharedPreferences] immediately.
     */
    fun setBuiltinParserEnabled(type: CustomSourceType, enabled: Boolean) {
        val current = getDisabledBuiltinParsers().toMutableSet()
        if (enabled) current.remove(type.name) else current.add(type.name)
        prefs.edit().putStringSet(KEY_DISABLED_PARSERS, current).apply()
    }

    /** Returns true when the given built-in parser type is not in the disabled set. */
    fun isBuiltinParserEnabled(type: CustomSourceType): Boolean =
        type.name !in getDisabledBuiltinParsers()

    // ── Persistence ───────────────────────────────────────────────────────────

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
        isEnabled = if (has("isEnabled")) getBoolean("isEnabled") else true,
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
        put("isEnabled", isEnabled)
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
     * tools) and merges any sources not already present (matched by normalised
     * baseUrl — trailing slashes and case are ignored). Each imported source
     * receives a fresh id to avoid collisions with local sources.
     *
     * @return the number of sources actually added (duplicates are skipped).
     */
    fun importJson(json: String): Int {
        val array = JSONArray(json)
        val existing = _sources.value
        // Build a normalised URL set for deduplication
        val existingUrls = existing
            .map { it.baseUrl.trimEnd('/').lowercase() }
            .toHashSet()
        val toAdd = mutableListOf<CustomSource>()
        for (i in 0 until array.length()) {
            try {
                val cs = array.getJSONObject(i).toCustomSource()
                // Normalise the imported URL the same way addSource() does
                val normUrl = cs.baseUrl.trimEnd('/').lowercase()
                if (normUrl !in existingUrls) {
                    toAdd.add(cs.copy(id = generateId()))
                    existingUrls.add(normUrl)
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
        private const val KEY_DISABLED_PARSERS = "disabled_builtin_parsers"

        /**
         * Monotonically-increasing ID generator. Seeded in [init] to sit above
         * the highest persisted ID, so IDs are unique across restarts and even
         * if [generateId] is called many times within the same millisecond.
         */
        private val idCounter = AtomicLong(System.currentTimeMillis())

        /** Returns a unique ID safe to use even under rapid concurrent adds. */
        fun generateId(): Long = idCounter.getAndIncrement()

        @Volatile
        private var INSTANCE: CustomSourcesRepository? = null

        fun peekById(id: Long): CustomSource? = INSTANCE?.findById(id)
        fun peekAll(): List<CustomSource> = INSTANCE?.getAll().orEmpty()
    }
}
