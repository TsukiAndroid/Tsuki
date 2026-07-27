package io.github.landwarderer.futon.plugins.ui

import android.util.Log
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.landwarderer.futon.core.ui.BaseViewModel
import io.github.landwarderer.futon.core.util.ext.MutableEventFlow
import io.github.landwarderer.futon.core.util.ext.call
import io.github.landwarderer.futon.plugins.data.PluginManager
import io.github.landwarderer.futon.plugins.data.PluginRepository
import io.github.landwarderer.futon.plugins.domain.Plugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ManagePluginsViewModel @Inject constructor(
    private val pluginRepository: PluginRepository,
    private val pluginManager: PluginManager,
) : BaseViewModel() {

    companion object {
        private const val TAG = "ManagePluginsViewModel"
    }

    val plugins: StateFlow<List<Plugin>> = pluginRepository.plugins
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val onPluginRemoved = MutableEventFlow<String>() // plugin name

    fun setEnabled(pluginId: String, enabled: Boolean) {
        launchJob(Dispatchers.IO) {
            runCatching {
                pluginRepository.setEnabled(pluginId, enabled)
                if (enabled) {
                    pluginManager.reloadPlugin(pluginId)
                } else {
                    pluginManager.unloadPlugin(pluginId)
                }
            }.getOrElse { e ->
                Log.e(TAG, "setEnabled failed: ${e.message}", e)
                errorEvent.call(e)
            }
        }
    }

    fun removePlugin(pluginId: String) {
        launchJob(Dispatchers.IO) {
            runCatching {
                val plugin = pluginRepository.getPlugin(pluginId)
                pluginRepository.removePlugin(pluginId)
                pluginManager.unloadPlugin(pluginId)
                onPluginRemoved.call(plugin?.name ?: pluginId)
            }.getOrElse { e ->
                Log.e(TAG, "removePlugin failed: ${e.message}", e)
                errorEvent.call(e)
            }
        }
    }
}
