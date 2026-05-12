package io.github.landwarderer.futon.customsource.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import io.github.landwarderer.futon.customsource.data.ParserTemplateRepository
import io.github.landwarderer.futon.customsource.data.ParserTemplateValidator
import io.github.landwarderer.futon.customsource.domain.ParserTemplate
import javax.inject.Inject

@HiltViewModel
class ParserTemplateViewModel @Inject constructor(
    private val repository: ParserTemplateRepository,
) : ViewModel() {

    val templates: StateFlow<List<ParserTemplate>> = repository.templates

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    /**
     * Validates [jsonContent] and, if valid, saves the template.
     * Emits [ImportState.Success] or [ImportState.Error] accordingly.
     */
    fun importTemplate(jsonContent: String) {
        viewModelScope.launch {
            when (val result = ParserTemplateValidator.validate(jsonContent)) {
                is ParserTemplateValidator.Result.Invalid -> {
                    _importState.value = ImportState.Error(result.reason)
                }

                is ParserTemplateValidator.Result.Valid -> {
                    // Reject duplicates by name (case-insensitive)
                    val existing = repository.getAll().find {
                        it.name.trim().equals(result.name.trim(), ignoreCase = true)
                    }
                    if (existing != null) {
                        _importState.value = ImportState.Error(
                            "A template named \"${result.name}\" is already imported."
                        )
                        return@launch
                    }
                    val template = ParserTemplate(
                        id = ParserTemplateRepository.generateId(),
                        name = result.name,
                        version = result.version,
                        type = result.type,
                        rawJson = jsonContent,
                    )
                    repository.add(template)
                    _importState.value = ImportState.Success(template)
                }
            }
        }
    }

    fun removeTemplate(id: Long) {
        viewModelScope.launch { repository.remove(id) }
    }

    fun resetState() {
        _importState.value = ImportState.Idle
    }

    sealed class ImportState {
        object Idle : ImportState()
        data class Error(val message: String) : ImportState()
        data class Success(val template: ParserTemplate) : ImportState()
    }
}
