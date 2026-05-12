package io.github.landwarderer.futon.customsource.ui

import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import io.github.landwarderer.futon.customsource.data.CustomSourcesRepository
import io.github.landwarderer.futon.customsource.data.ParserTemplateRepository
import io.github.landwarderer.futon.customsource.data.ParserTemplateValidator
import io.github.landwarderer.futon.customsource.domain.CustomSource
import io.github.landwarderer.futon.customsource.domain.CustomSourceType
import io.github.landwarderer.futon.customsource.domain.ParserTemplate
import javax.inject.Inject

@HiltViewModel
class ParserTemplateViewModel @Inject constructor(
    private val repository: ParserTemplateRepository,
    private val customSourcesRepository: CustomSourcesRepository,
) : ViewModel() {

    val templates: StateFlow<List<ParserTemplate>> = repository.templates

    private val _importState = MutableStateFlow<ImportState>(ImportState.Idle)
    val importState: StateFlow<ImportState> = _importState.asStateFlow()

    private val _addSiteState = MutableStateFlow<AddSiteState>(AddSiteState.Idle)
    val addSiteState: StateFlow<AddSiteState> = _addSiteState.asStateFlow()

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

    /**
     * Creates a [CustomSource] of type [CustomSourceType.CUSTOM_TEMPLATE] backed
     * by [template], pointing at [siteUrl].
     *
     * Emits [AddSiteState.Success] on success or [AddSiteState.Error] if the URL
     * is invalid or the site is already in the user's sources.
     */
    fun addSourceForTemplate(template: ParserTemplate, siteUrl: String, siteName: String) {
        viewModelScope.launch {
            val normalized = normalizeUrl(siteUrl.trim())
            if (normalized == null) {
                _addSiteState.value = AddSiteState.Error(
                    "Please enter a valid URL (e.g. https://example.com)"
                )
                return@launch
            }
            val existing = customSourcesRepository.findByUrl(normalized)
            if (existing != null) {
                _addSiteState.value = AddSiteState.Error(
                    "This site is already added as \"${existing.displayName}\""
                )
                return@launch
            }
            val source = CustomSource(
                id = CustomSourcesRepository.generateId(),
                name = siteName.trim().ifBlank { hostFromUrl(normalized) ?: normalized },
                baseUrl = normalized,
                type = CustomSourceType.CUSTOM_TEMPLATE,
                parserSourceName = template.name,
            )
            customSourcesRepository.add(source)
            _addSiteState.value = AddSiteState.Success(source.displayName, template.name)
        }
    }

    fun removeTemplate(id: Long) {
        viewModelScope.launch { repository.remove(id) }
    }

    fun resetState() {
        _importState.value = ImportState.Idle
    }

    fun resetAddSiteState() {
        _addSiteState.value = AddSiteState.Idle
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun normalizeUrl(url: String): String? {
        val withScheme = when {
            url.startsWith("http://") || url.startsWith("https://") -> url
            url.isNotBlank() -> "https://$url"
            else -> return null
        }
        return if (Patterns.WEB_URL.matcher(withScheme).matches()) {
            withScheme.trimEnd('/')
        } else {
            null
        }
    }

    private fun hostFromUrl(url: String): String? = runCatching {
        val host = java.net.URI(url).host?.removePrefix("www.") ?: return@runCatching null
        host.replaceFirstChar { it.uppercase() }
    }.getOrNull()

    // ── State types ───────────────────────────────────────────────────────────

    sealed class ImportState {
        object Idle : ImportState()
        data class Error(val message: String) : ImportState()
        data class Success(val template: ParserTemplate) : ImportState()
    }

    sealed class AddSiteState {
        object Idle : AddSiteState()
        data class Error(val message: String) : AddSiteState()
        /** [siteName] is the display name of the newly created source. */
        data class Success(val siteName: String, val templateName: String) : AddSiteState()
    }
}
