package io.github.landwarderer.futon.plugins.data

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.landwarderer.futon.plugins.domain.Plugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persists installed plugin metadata in SharedPreferences (as JSON).
 *
 * JAR files themselves are stored in [pluginsDir] — the app's private
 * "plugins/" directory. Metadata is kept separately so it survives
 * even if a .jar is temporarily unreadable.
 */
@Singleton
class PluginRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _plugins = MutableStateFlow(loadAll())
    val plugins: StateFlow<List<Plugin>> = _plugins.asStateFlow()

    /** Directory where plugin .jar files are stored. */
    val pluginsDir: File = File(context.filesDir, "plugins").also { it.mkdirs() }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    suspend fun addPlugin(plugin: Plugin): Unit = withContext(Dispatchers.IO) {
        val updated = _plugins.value.filter { it.id != plugin.id } + plugin
        persist(updated)
        _plugins.value = updated
    }

    suspend fun removePlugin(pluginId: String): Unit = withContext(Dispatchers.IO) {
        val existing = _plugins.value.find { it.id == pluginId } ?: return@withContext
        // Delete the .jar file
        File(existing.jarPath).delete()
        val updated = _plugins.value.filter { it.id != pluginId }
        persist(updated)
        _plugins.value = updated
    }

    suspend fun setEnabled(pluginId: String, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        val updated = _plugins.value.map { p ->
            if (p.id == pluginId) p.copy(isEnabled = enabled) else p
        }
        persist(updated)
        _plugins.value = updated
    }

    suspend fun updatePlugin(updated: Plugin): Unit = withContext(Dispatchers.IO) {
        val list = _plugins.value.map { if (it.id == updated.id) updated else it }
        persist(list)
        _plugins.value = list
    }

    fun getPlugin(pluginId: String): Plugin? = _plugins.value.find { it.id == pluginId }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private fun loadAll(): List<Plugin> {
        val raw = prefs.getString(KEY_PLUGINS, null) ?: return emptyList()
        return runCatching { Json.decodeFromString<List<Plugin>>(raw) }.getOrDefault(emptyList())
    }

    private fun persist(plugins: List<Plugin>) {
        prefs.edit()
            .putString(KEY_PLUGINS, Json.encodeToString(plugins))
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "plugin_repository"
        private const val KEY_PLUGINS = "plugins"
    }
}
