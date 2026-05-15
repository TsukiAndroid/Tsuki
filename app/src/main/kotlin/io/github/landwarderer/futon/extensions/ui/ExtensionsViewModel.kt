package io.github.landwarderer.futon.extensions.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.landwarderer.futon.extensions.data.AvailableExtension
import io.github.landwarderer.futon.extensions.data.ExtensionRepository
import io.github.landwarderer.futon.extensions.data.ExtensionRepoService
import io.github.landwarderer.futon.extensions.domain.Extension
import io.github.landwarderer.futon.extensions.domain.ExtensionType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ExtensionsViewModel @Inject constructor(
    private val extensionRepository: ExtensionRepository,
    private val extensionRepoService: ExtensionRepoService,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _availableExtensions = MutableStateFlow<List<AvailableExtension>>(emptyList())

    val installedExtensions: StateFlow<List<Extension>> = combine(
        extensionRepository.extensions,
        _query,
    ) { list, q ->
        if (q.isBlank()) list else list.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.author.contains(q, ignoreCase = true) ||
                it.baseUrl.contains(q, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val availableExtensions: StateFlow<List<AvailableExtension>> = combine(
        _availableExtensions,
        _query,
    ) { list, q ->
        if (q.isBlank()) list else list.filter {
            it.name.contains(q, ignoreCase = true) ||
                it.author.contains(q, ignoreCase = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun performSearch(q: String?) {
        _query.value = q.orEmpty()
    }

    fun setEnabled(id: String, enabled: Boolean) {
        viewModelScope.launch {
            extensionRepository.setEnabled(id, enabled)
        }
    }

    fun delete(id: String) {
        viewModelScope.launch {
            extensionRepository.delete(id)
        }
    }

    fun refreshAvailable() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            try {
                val repos = extensionRepoService.getRepos()
                val all = repos.flatMap { repo ->
                    runCatching { extensionRepoService.fetchIndex(repo) }.getOrDefault(emptyList())
                }
                _availableExtensions.value = all
            } catch (e: Exception) {
                _errorMessage.value = e.message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun installFromCode(
        name: String,
        version: String,
        author: String,
        description: String,
        baseUrl: String,
        language: String,
        type: ExtensionType,
        sourceCode: String,
        packageName: String = "",
        templateName: String = "",
    ) {
        viewModelScope.launch {
            val extension = Extension(
                id = UUID.randomUUID().toString(),
                name = name,
                version = version,
                author = author,
                description = description,
                baseUrl = baseUrl,
                language = language,
                iconUrl = "",
                type = type,
                sourceCode = sourceCode,
                packageName = packageName,
                templateName = templateName,
                isEnabled = true,
                installedAt = System.currentTimeMillis(),
            )
            extensionRepository.save(extension)
        }
    }

    /**
     * Creates a brand-new extension with auto-generated template source code.
     *
     * Called from [CreateExtensionActivity] when the user fills in the form and
     * taps "Create".
     */
    fun createExtension(
        name: String,
        lang: String,
        baseUrl: String,
        apiUrl: String,
        iconUrl: String,
        notes: String,
        scriptLanguage: ExtensionType,
        sourceType: String,
        contentTarget: String,
    ) {
        viewModelScope.launch {
            val sourceCode = generateTemplate(scriptLanguage, name, baseUrl)
            val extension = Extension(
                id = UUID.randomUUID().toString(),
                name = name,
                version = "1.0.0",
                author = "",
                description = "",
                baseUrl = baseUrl,
                apiUrl = apiUrl,
                language = lang.ifBlank { "en" },
                iconUrl = iconUrl,
                notes = notes,
                type = scriptLanguage,
                sourceType = sourceType,
                contentTarget = contentTarget,
                sourceCode = sourceCode,
                packageName = "",
                templateName = "",
                isEnabled = true,
                installedAt = System.currentTimeMillis(),
            )
            extensionRepository.save(extension)
        }
    }

    private fun generateTemplate(type: ExtensionType, name: String, baseUrl: String): String =
        when (type) {
            ExtensionType.JS -> """
// $name — JavaScript extension
// Base URL: $baseUrl

function getMangaList(offset, query) {
  // TODO: fetch and return manga list
  // Expected: '{"items":[{"url":"/manga/xyz","title":"Title","cover":"https://..."}]}'
  return JSON.stringify({ items: [] });
}

function getMangaDetails(url) {
  // TODO: fetch and return manga details + chapters
  // Expected: '{"url":"...","title":"...","cover":"...","description":"...","chapters":[...]}'
  return JSON.stringify({ url: url, title: "", chapters: [] });
}

function getChapterPages(url) {
  // TODO: fetch and return page URLs
  // Expected: '{"pages":[{"index":0,"url":"https://img.example.com/1.jpg"}]}'
  return JSON.stringify({ pages: [] });
}
""".trimIndent()

            ExtensionType.DART -> """
// $name — Dart extension
// Base URL: $baseUrl

// Returns JSON string: '{"items":[{"url":"...","title":"...","cover":"..."}]}'
String getMangaList(int offset, String? query) {
  // TODO: fetch and return manga list
  return '{"items":[]}';
}

// Returns JSON string: '{"url":"...","title":"...","chapters":[...]}'
String getMangaDetails(String url) {
  // TODO: fetch and return manga details + chapters
  return '{"url":"${'$'}url","title":"","chapters":[]}';
}

// Returns JSON string: '{"pages":[{"index":0,"url":"https://img.example.com/1.jpg"}]}'
String getChapterPages(String url) {
  // TODO: fetch and return chapter page URLs
  return '{"pages":[]}';
}
""".trimIndent()

            else -> ""
        }

    fun installFromAvailable(available: AvailableExtension) {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val sourceCode = if (available.downloadUrl.isNotEmpty()) {
                    extensionRepoService.downloadSourceCode(available.downloadUrl)
                } else {
                    ""
                }
                val extension = Extension(
                    id = UUID.randomUUID().toString(),
                    name = available.name,
                    version = available.version,
                    author = available.author,
                    description = available.description,
                    baseUrl = available.baseUrl,
                    language = available.language,
                    iconUrl = available.iconUrl,
                    type = available.type,
                    sourceCode = sourceCode,
                    packageName = available.packageName,
                    templateName = available.templateName,
                    isEnabled = true,
                    installedAt = System.currentTimeMillis(),
                )
                extensionRepository.save(extension)
            } catch (e: Exception) {
                _errorMessage.value = "Install failed: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
