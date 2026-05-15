package io.github.landwarderer.futon.extensions.data

import android.content.SharedPreferences
import io.github.landwarderer.futon.extensions.domain.Extension
import io.github.landwarderer.futon.extensions.domain.ExtensionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Stores and retrieves user-installed [Extension]s via SharedPreferences + JSON.
 *
 * Follows the same persistence pattern as CustomSourcesRepository.
 * Thread-safe via @Synchronized; all mutations refresh the public [extensions] flow.
 */
@Singleton
class ExtensionRepository @Inject constructor(
    @Named("extensions_prefs") private val prefs: SharedPreferences,
) {

    private val _extensions = MutableStateFlow<List<Extension>>(emptyList())
    val extensions: StateFlow<List<Extension>> = _extensions.asStateFlow()

    init {
        _extensions.value = loadAll()
    }

    @Synchronized
    fun save(extension: Extension) {
        val all = loadAll().toMutableList()
        val idx = all.indexOfFirst { it.id == extension.id }
        if (idx >= 0) all[idx] = extension else all.add(extension)
        persist(all)
    }

    @Synchronized
    fun delete(id: String) {
        val all = loadAll().toMutableList()
        all.removeAll { it.id == id }
        persist(all)
    }

    @Synchronized
    fun setEnabled(id: String, isEnabled: Boolean) {
        val all = loadAll().toMutableList()
        val idx = all.indexOfFirst { it.id == id }
        if (idx >= 0) all[idx] = all[idx].copy(isEnabled = isEnabled)
        persist(all)
    }

    fun getAll(): List<Extension> = _extensions.value

    fun getEnabled(): List<Extension> = _extensions.value.filter { it.isEnabled }

    fun findById(id: String): Extension? = _extensions.value.firstOrNull { it.id == id }

    private fun loadAll(): List<Extension> {
        val raw = prefs.getString(KEY_EXTENSIONS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                runCatching { arr.getJSONObject(i).toExtension() }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    private fun persist(list: List<Extension>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        prefs.edit().putString(KEY_EXTENSIONS, arr.toString()).apply()
        _extensions.value = list.toList()
    }

    private fun JSONObject.toExtension(): Extension = Extension(
        id = getString("id"),
        name = optString("name", ""),
        version = optString("version", "1.0.0"),
        author = optString("author", ""),
        description = optString("description", ""),
        baseUrl = optString("baseUrl", ""),
        language = optString("language", "en"),
        iconUrl = optString("iconUrl", ""),
        type = runCatching { ExtensionType.valueOf(getString("type")) }.getOrDefault(ExtensionType.JS),
        sourceCode = optString("sourceCode", ""),
        packageName = optString("packageName", ""),
        templateName = optString("templateName", ""),
        isEnabled = optBoolean("isEnabled", true),
        installedAt = optLong("installedAt", System.currentTimeMillis()),
    )

    private fun Extension.toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        put("version", version)
        put("author", author)
        put("description", description)
        put("baseUrl", baseUrl)
        put("language", language)
        put("iconUrl", iconUrl)
        put("type", type.name)
        put("sourceCode", sourceCode)
        put("packageName", packageName)
        put("templateName", templateName)
        put("isEnabled", isEnabled)
        put("installedAt", installedAt)
    }

    companion object {
        private const val KEY_EXTENSIONS = "installed_extensions"
    }
}
