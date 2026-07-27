package io.github.landwarderer.futon.plugins.data

import android.util.Log
import dagger.hilt.android.scopes.ActivityRetainedScoped
import io.github.landwarderer.futon.plugins.domain.LoadedPlugin
import io.github.landwarderer.futon.plugins.domain.Plugin
import io.github.landwarderer.futon.plugins.domain.PluginMangaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central coordinator for the plugin system.
 *
 * - Holds the current map of [LoadedPlugin]s (keyed by pluginId).
 * - Exposes [pluginSources] as a [StateFlow] so [MangaSourcesRepository] can
 *   react to changes without polling.
 * - Owns the lifecycle of loaded plugins; call [reload] after install/remove/toggle.
 */
@Singleton
class PluginManager @Inject constructor(
    private val pluginRepository: PluginRepository,
    private val pluginLoader: PluginLoader,
) {
    companion object {
        private const val TAG = "PluginManager"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Map of pluginId → LoadedPlugin for all currently-enabled plugins. */
    private val loadedPlugins = mutableMapOf<String, LoadedPlugin>()

    private val _pluginSources = MutableStateFlow<List<PluginMangaSource>>(emptyList())
    /** Live list of [PluginMangaSource]s from all enabled plugins. */
    val pluginSources: StateFlow<List<PluginMangaSource>> = _pluginSources.asStateFlow()

    init {
        // Observe plugin list changes and reload whenever a plugin is added/removed/toggled
        scope.launch {
            pluginRepository.plugins.collect { plugins ->
                reloadPlugins(plugins)
            }
        }
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /** Returns the [LoadedPlugin] for [pluginId], or null if not loaded. */
    fun getLoadedPlugin(pluginId: String): LoadedPlugin? = loadedPlugins[pluginId]

    /**
     * Returns a [PluginMangaRepository] for the given [source], or null if the
     * plugin is not loaded / source not found.
     */
    fun createRepositoryForSource(source: PluginMangaSource): PluginMangaRepository? {
        val loaded = loadedPlugins[source.pluginId] ?: return null
        return PluginMangaRepository(
            source       = source,
            loadedPlugin = loaded,
            sourceName   = source.sourceName,
        )
    }

    /** Force-reloads a specific plugin from disk. Called after install/update. */
    suspend fun reloadPlugin(pluginId: String) {
        val plugin = pluginRepository.getPlugin(pluginId) ?: return
        reloadPlugins(listOf(plugin), mergeWithExisting = true)
    }

    /** Unloads a plugin from memory. The .jar and metadata are removed by [PluginRepository]. */
    fun unloadPlugin(pluginId: String) {
        loadedPlugins.remove(pluginId)
        refreshSourcesList()
    }

    // -------------------------------------------------------------------------
    // Internal
    // -------------------------------------------------------------------------

    private fun reloadPlugins(
        plugins: List<Plugin>,
        mergeWithExisting: Boolean = false,
    ) {
        if (!mergeWithExisting) loadedPlugins.clear()

        plugins.filter { it.isEnabled }.forEach { plugin ->
            val jarFile = File(plugin.jarPath)
            if (!jarFile.exists()) {
                Log.w(TAG, "JAR missing for plugin ${plugin.id}: ${plugin.jarPath}")
                return@forEach
            }
            val loaded = pluginLoader.loadPlugin(jarFile, existingMetadata = plugin)
            if (loaded != null) {
                loadedPlugins[plugin.id] = loaded
                Log.d(TAG, "Loaded plugin: ${plugin.name} with ${loaded.sources.size} sources")
            } else {
                Log.w(TAG, "Failed to load plugin: ${plugin.name}")
            }
        }

        // Remove disabled plugins from memory
        if (!mergeWithExisting) {
            plugins.filter { !it.isEnabled }.forEach { loadedPlugins.remove(it.id) }
        }

        refreshSourcesList()
    }

    private fun refreshSourcesList() {
        _pluginSources.value = loadedPlugins.values.flatMap { it.sources }
    }
}
