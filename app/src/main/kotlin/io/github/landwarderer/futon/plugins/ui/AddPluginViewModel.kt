package io.github.landwarderer.futon.plugins.ui

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.landwarderer.futon.plugins.data.PluginDownloader
import io.github.landwarderer.futon.plugins.data.PluginLoader
import io.github.landwarderer.futon.plugins.data.PluginManager
import io.github.landwarderer.futon.plugins.data.PluginRepository
import io.github.landwarderer.futon.plugins.domain.LoadedPlugin
import io.github.landwarderer.futon.plugins.domain.Plugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

sealed class AddPluginUiState {
    object Idle : AddPluginUiState()
    object Loading : AddPluginUiState()
    data class Preview(val loaded: LoadedPlugin) : AddPluginUiState()
    data class Error(val message: String) : AddPluginUiState()
    object Success : AddPluginUiState()
}

@HiltViewModel
class AddPluginViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val pluginLoader: PluginLoader,
    private val pluginDownloader: PluginDownloader,
    private val pluginRepository: PluginRepository,
    private val pluginManager: PluginManager,
) : ViewModel() {

    companion object {
        private const val TAG = "AddPluginViewModel"
    }

    private val _uiState = MutableStateFlow<AddPluginUiState>(AddPluginUiState.Idle)
    val uiState: StateFlow<AddPluginUiState> = _uiState.asStateFlow()

    private val _downloadProgress = MutableStateFlow(0f)
    val downloadProgress: StateFlow<Float> = _downloadProgress.asStateFlow()

    /** Holds the temporary file used during the preview step (before user confirms install). */
    private var pendingJarFile: File? = null
    /** Holds the GitHub repo URL for the file under preview (for update tracking). */
    private var pendingGithubRepo: String? = null

    // -------------------------------------------------------------------------
    // Option A – Import from file picker
    // -------------------------------------------------------------------------

    fun previewFromUri(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = AddPluginUiState.Loading
            runCatching {
                // Copy the selected file to a temp location
                val tempFile = File(context.cacheDir, "plugin_import_${System.currentTimeMillis()}.jar")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                } ?: error("Cannot open selected file")

                val loaded = pluginLoader.loadPlugin(tempFile)
                    ?: error("File does not appear to be a valid plugin JAR")

                pendingJarFile = tempFile
                pendingGithubRepo = null
                _uiState.value = AddPluginUiState.Preview(loaded)
            }.getOrElse { e ->
                Log.e(TAG, "previewFromUri failed: ${e.message}", e)
                _uiState.value = AddPluginUiState.Error(e.message ?: "Invalid plugin file")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Option B – Import from GitHub
    // -------------------------------------------------------------------------

    fun previewFromGithub(repoUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = AddPluginUiState.Loading
            runCatching {
                val tempDir = File(context.cacheDir, "plugin_preview_${System.currentTimeMillis()}")
                tempDir.mkdirs()

                var lastProgress = 0f
                val jarFile = pluginDownloader.downloadFromGithub(
                    repoUrl   = repoUrl,
                    outputDir = tempDir,
                    onProgress = { downloaded, total ->
                        if (total > 0) {
                            val progress = downloaded.toFloat() / total.toFloat()
                            if (progress - lastProgress > 0.01f) {
                                lastProgress = progress
                                _downloadProgress.value = progress
                            }
                        }
                    },
                ) ?: error("No .jar asset found in latest release")

                val loaded = pluginLoader.loadPlugin(jarFile)
                    ?: error("Downloaded file is not a valid plugin JAR")

                pendingJarFile = jarFile
                pendingGithubRepo = repoUrl
                _uiState.value = AddPluginUiState.Preview(loaded)
            }.getOrElse { e ->
                Log.e(TAG, "previewFromGithub failed for $repoUrl: ${e.message}", e)
                _uiState.value = AddPluginUiState.Error(e.message ?: "Download failed")
            }
        }
    }

    // -------------------------------------------------------------------------
    // Install confirmed by user
    // -------------------------------------------------------------------------

    fun confirmInstall(githubRepo: String? = pendingGithubRepo) {
        val tempJar = pendingJarFile ?: run {
            _uiState.value = AddPluginUiState.Error("No pending plugin to install")
            return
        }
        val preview = (_uiState.value as? AddPluginUiState.Preview) ?: run {
            _uiState.value = AddPluginUiState.Error("Preview state lost")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val pluginsDir = pluginRepository.pluginsDir
                pluginsDir.mkdirs()
                val destFile = File(pluginsDir, tempJar.name)
                tempJar.copyTo(destFile, overwrite = true)
                tempJar.delete()

                val metadata = preview.loaded.metadata.copy(
                    jarPath     = destFile.absolutePath,
                    githubRepo  = githubRepo?.let { cleanGithubRepo(it) },
                    installedAt = System.currentTimeMillis(),
                    lastUpdated = System.currentTimeMillis(),
                    isEnabled   = true,
                )
                pluginRepository.addPlugin(metadata)
                pluginManager.reloadPlugin(metadata.id)

                pendingJarFile = null
                pendingGithubRepo = null
                _uiState.value = AddPluginUiState.Success
            }.getOrElse { e ->
                Log.e(TAG, "confirmInstall failed: ${e.message}", e)
                _uiState.value = AddPluginUiState.Error(e.message ?: "Install failed")
            }
        }
    }

    fun reset() {
        pendingJarFile?.delete()
        pendingJarFile = null
        pendingGithubRepo = null
        _uiState.value = AddPluginUiState.Idle
        _downloadProgress.value = 0f
    }

    private fun cleanGithubRepo(url: String): String =
        url.removePrefix("https://github.com/").removePrefix("http://github.com/").trim('/')
}
