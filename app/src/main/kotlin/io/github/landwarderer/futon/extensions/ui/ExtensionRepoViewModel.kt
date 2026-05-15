package io.github.landwarderer.futon.extensions.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.landwarderer.futon.extensions.data.ExtensionRepo
import io.github.landwarderer.futon.extensions.data.ExtensionRepoService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExtensionRepoViewModel @Inject constructor(
    private val repoService: ExtensionRepoService,
) : ViewModel() {

    private val _repos = MutableStateFlow<List<ExtensionRepo>>(emptyList())
    val repos: StateFlow<List<ExtensionRepo>> = _repos.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _repos.value = repoService.getRepos()
    }

    fun addRepo(name: String, indexUrl: String) {
        viewModelScope.launch {
            if (indexUrl.isBlank()) {
                _errorMessage.value = "Repository URL must not be empty"
                return@launch
            }
            val trimmedUrl = indexUrl.trim()
            if (!trimmedUrl.startsWith("http")) {
                _errorMessage.value = "Repository URL must start with http:// or https://"
                return@launch
            }
            repoService.addRepo(ExtensionRepo(name = name.ifBlank { trimmedUrl }, indexUrl = trimmedUrl))
            refresh()
        }
    }

    fun removeRepo(indexUrl: String) {
        viewModelScope.launch {
            repoService.removeRepo(indexUrl)
            refresh()
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
