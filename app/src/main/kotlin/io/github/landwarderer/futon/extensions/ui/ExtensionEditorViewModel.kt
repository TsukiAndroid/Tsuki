package io.github.landwarderer.futon.extensions.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.landwarderer.futon.extensions.data.ExtensionRepository
import io.github.landwarderer.futon.extensions.domain.Extension
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ExtensionEditorViewModel @Inject constructor(
    private val extensionRepository: ExtensionRepository,
) : ViewModel() {

    private val _extension = MutableStateFlow<Extension?>(null)
    val extension: StateFlow<Extension?> = _extension.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    fun load(id: String) {
        _extension.value = extensionRepository.findById(id)
    }

    fun saveCode(id: String, newCode: String) {
        viewModelScope.launch {
            val ext = extensionRepository.findById(id) ?: return@launch
            extensionRepository.save(ext.copy(sourceCode = newCode))
            _saved.value = true
        }
    }

    fun clearSaved() {
        _saved.value = false
    }
}
