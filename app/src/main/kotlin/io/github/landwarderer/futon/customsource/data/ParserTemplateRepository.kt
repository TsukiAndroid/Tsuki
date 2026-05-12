package io.github.landwarderer.futon.customsource.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import io.github.landwarderer.futon.customsource.domain.ParserTemplate
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists and exposes the list of user-imported [ParserTemplate]s.
 *
 * Uses a dedicated [SharedPreferences] file so parser template data stays
 * separate from custom sources and other app preferences.
 */
@Singleton
class ParserTemplateRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _templates = MutableStateFlow(loadAll())
    val templates: StateFlow<List<ParserTemplate>> = _templates.asStateFlow()

    init {
        val maxExisting = _templates.value.maxOfOrNull { it.id } ?: 0L
        idCounter.getAndUpdate { current -> maxOf(current, maxExisting + 1) }
    }

    fun getAll(): List<ParserTemplate> = _templates.value

    fun add(template: ParserTemplate) {
        val updated = _templates.value.toMutableList().apply { add(template) }
        saveAll(updated)
        _templates.value = updated
    }

    fun remove(id: Long) {
        val updated = _templates.value.filter { it.id != id }
        saveAll(updated)
        _templates.value = updated
    }

    fun findById(id: Long): ParserTemplate? = _templates.value.find { it.id == id }

    private fun loadAll(): List<ParserTemplate> {
        val json = prefs.getString(KEY_TEMPLATES, null) ?: return emptyList()
        return try {
            val array = JSONArray(json)
            (0 until array.length()).map { i -> array.getJSONObject(i).toParserTemplate() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun saveAll(templates: List<ParserTemplate>) {
        val array = JSONArray(templates.map { it.toJson() })
        prefs.edit().putString(KEY_TEMPLATES, array.toString()).apply()
    }

    private fun JSONObject.toParserTemplate() = ParserTemplate(
        id = getLong("id"),
        name = getString("name"),
        version = getString("version"),
        type = getString("type"),
        rawJson = getString("rawJson"),
        importedAt = optLong("importedAt", System.currentTimeMillis()),
    )

    private fun ParserTemplate.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("version", version)
        put("type", type)
        put("rawJson", rawJson)
        put("importedAt", importedAt)
    }

    companion object {
        private const val PREFS_NAME = "tsuki_parser_templates"
        private const val KEY_TEMPLATES = "templates"

        private val idCounter = AtomicLong(System.currentTimeMillis())

        fun generateId(): Long = idCounter.getAndIncrement()
    }
}
