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
import android.util.Log
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
        INSTANCE = this
        val maxExisting = _templates.value.maxOfOrNull { it.id } ?: 0L
        idCounter.getAndUpdate { current -> maxOf(current, maxExisting + 1) }
    }

    fun getAll(): List<ParserTemplate> = _templates.value

    /** Returns only templates that are currently enabled. */
    fun getEnabled(): List<ParserTemplate> = _templates.value.filter { it.isEnabled }

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

    /** Returns the template whose name matches [name] (case-insensitive), or null. */
    fun findByName(name: String): ParserTemplate? =
        _templates.value.find { it.name.equals(name, ignoreCase = true) }

    /**
     * Replaces an existing template with [template] (matched by [ParserTemplate.id]).
     *
     * Used by [RemoteTemplateSync] to apply version updates while preserving the
     * template's [ParserTemplate.importedAt] timestamp and enabled state.  If no
     * template with the given id exists this is a no-op (use [add] instead).
     */
    fun upsert(template: ParserTemplate) {
        val updated = _templates.value.map { if (it.id == template.id) template else it }
        if (updated == _templates.value) return // nothing changed
        saveAll(updated)
        _templates.value = updated
    }

    /** Flip the enabled flag of a template. No-op if the id is not found. */
    fun setEnabled(id: Long, enabled: Boolean) {
        val template = findById(id) ?: return
        val updated = _templates.value.map {
            if (it.id == id) it.copy(isEnabled = enabled) else it
        }
        saveAll(updated)
        _templates.value = updated
    }

    private fun loadAll(): List<ParserTemplate> {
        val json = prefs.getString(KEY_TEMPLATES, null)
        if (json == null) {
            Log.d("USB-PTR", "loadAll: no saved templates (first launch or cleared data)")
            return emptyList()
        }
        return try {
            val array = JSONArray(json)
            val loaded = (0 until array.length()).map { i -> array.getJSONObject(i).toParserTemplate() }
            Log.d("USB-PTR", "loadAll: restored ${loaded.size} template(s): ${loaded.map { it.name }}")
            loaded
        } catch (e: Exception) {
            Log.e("USB-PTR", "loadAll: JSON parse failed, returning empty", e)
            emptyList()
        }
    }

    private fun saveAll(templates: List<ParserTemplate>) {
        val array = JSONArray(templates.map { it.toJson() })
        prefs.edit().putString(KEY_TEMPLATES, array.toString()).apply()
        Log.d("USB-PTR", "saveAll: persisted ${templates.size} template(s): ${templates.map { it.name }}")
        // Verify the write landed
        val verify = prefs.getString(KEY_TEMPLATES, null)
        if (verify == null) Log.e("USB-PTR", "saveAll: WRITE FAILED — prefs returned null immediately after apply()")
        else Log.d("USB-PTR", "saveAll: write verified OK (${verify.length} bytes)")
    }

    private fun JSONObject.toParserTemplate() = ParserTemplate(
        id = getLong("id"),
        name = getString("name"),
        version = getString("version"),
        type = getString("type"),
        rawJson = getString("rawJson"),
        importedAt = optLong("importedAt", System.currentTimeMillis()),
        isEnabled = if (has("isEnabled")) getBoolean("isEnabled") else true,
    )

    private fun ParserTemplate.toJson() = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("version", version)
        put("type", type)
        put("rawJson", rawJson)
        put("importedAt", importedAt)
        put("isEnabled", isEnabled)
    }

    companion object {
        private const val PREFS_NAME = "tsuki_parser_templates"
        private const val KEY_TEMPLATES = "templates"

        private val idCounter = AtomicLong(System.currentTimeMillis())

        @Volatile
        private var INSTANCE: ParserTemplateRepository? = null

        fun generateId(): Long = idCounter.getAndIncrement()

        /**
         * Returns the [ParserTemplate] with the given [name] (case-insensitive),
         * or null if no template with that name has been imported yet.
         *
         * Intended for use by [TemplateHtmlParser], which is constructed outside
         * of Hilt's dependency-injection graph and therefore cannot receive
         * [ParserTemplateRepository] as a constructor parameter.
         */
        fun peekByName(name: String): ParserTemplate? =
            INSTANCE?.getAll()?.find { it.name.equals(name, ignoreCase = true) }

        /** Returns true when [INSTANCE] has been initialised by Hilt. */
        fun instanceIsReady(): Boolean = INSTANCE != null

        fun peekAll(): List<ParserTemplate> = INSTANCE?.getAll().orEmpty()

        /** Returns only enabled templates — safe to call from any thread. */
        fun peekEnabled(): List<ParserTemplate> = INSTANCE?.getEnabled().orEmpty()
    }
}
