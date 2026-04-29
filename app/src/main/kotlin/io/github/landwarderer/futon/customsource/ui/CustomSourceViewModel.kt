package io.github.landwarderer.futon.customsource.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import io.github.landwarderer.futon.customsource.data.CustomSourcesRepository
import io.github.landwarderer.futon.customsource.domain.CustomSource
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import javax.inject.Inject

@HiltViewModel
class CustomSourceViewModel @Inject constructor(
    private val repository: CustomSourcesRepository,
) : ViewModel() {

    val sources: StateFlow<List<CustomSource>> = repository.sources

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    fun addSource(name: String, url: String, type: CustomSourceType, description: String) {
        viewModelScope.launch {
            val trimmedUrl = url.trim()
            if (trimmedUrl.isBlank() || !trimmedUrl.startsWith("http")) {
                _uiState.value = UiState.Error("Please enter a valid URL starting with http:// or https://")
                return@launch
            }
            val source = CustomSource(
                id = CustomSourcesRepository.generateId(),
                name = name.trim().ifBlank { trimmedUrl },
                baseUrl = trimmedUrl,
                type = type,
                description = description.trim().takeIf { it.isNotEmpty() },
            )
            repository.add(source)
            _uiState.value = UiState.SourceAdded(source)
        }
    }

    fun removeSource(id: Long) {
        viewModelScope.launch {
            repository.remove(id)
        }
    }

    fun resetState() {
        _uiState.value = UiState.Idle
    }

    sealed class UiState {
        object Idle : UiState()
        data class Error(val message: String) : UiState()
        data class SourceAdded(val source: CustomSource) : UiState()
    }
}
