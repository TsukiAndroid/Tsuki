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
        // Register this singleton so the MangaSource(name) factory can resolve
        // CUSTOM_<id> entries without a Hilt entry-point. Hilt creates one
        // instance, so this assignment is safe.
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
        type = CustomSourceType.valueOf(getString("type")),
        iconUrl = optString("iconUrl").takeIf { it.isNotEmpty() },
        description = optString("description").takeIf { it.isNotEmpty() },
        createdAt = optLong("createdAt", System.currentTimeMillis()),
    )

    private fun CustomSource.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("baseUrl", baseUrl)
        put("type", type.name)
        put("iconUrl", iconUrl ?: "")
        put("description", description ?: "")
        put("createdAt", createdAt)
    }

    companion object {
        private const val PREFS_NAME = "tsuki_custom_sources"
        private const val KEY_SOURCES = "sources"

        @Volatile
        private var INSTANCE: CustomSourcesRepository? = null

        /**
         * Lookup hook used by [io.github.landwarderer.futon.core.model.MangaSource]'s
         * factory function so it can resolve `CUSTOM_<id>` source names back to a
         * fully-formed [CustomSource]. Returns `null` if the singleton has not
         * been built yet (very early app startup) or the id is unknown.
         */
        fun peekById(id: Long): CustomSource? = INSTANCE?.findById(id)

        fun peekAll(): List<CustomSource> = INSTANCE?.getAll().orEmpty()

        fun generateId(): Long = System.currentTimeMillis()
    }
}
