package io.github.landwarderer.futon.webviewsource.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.landwarderer.futon.core.db.entity.WebViewSourceEntity
import io.github.landwarderer.futon.core.ui.BaseViewModel
import io.github.landwarderer.futon.webviewsource.data.ChapterPatternDetector
import io.github.landwarderer.futon.webviewsource.data.OgTagFetcher
import io.github.landwarderer.futon.webviewsource.data.WebViewSourceRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AddSourceUiState(
    val isLoading: Boolean = false,
    val title: String = "",
    val coverUrl: String? = null,
    val detectedPattern: String? = null,
    val fetchError: Boolean = false,
    val saved: Boolean = false,
)

@HiltViewModel
class AddWebViewSourceViewModel @Inject constructor(
    private val repository: WebViewSourceRepository,
    private val ogFetcher: OgTagFetcher,
) : BaseViewModel() {

    private val _state = MutableStateFlow(AddSourceUiState())
    val state: StateFlow<AddSourceUiState> = _state.asStateFlow()

    fun fetchUrl(url: String) {
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, fetchError = false)
            val og = ogFetcher.fetch(url)
            if (og == null) {
                _state.value = _state.value.copy(isLoading = false, fetchError = true)
                return@launch
            }
            val pattern = ChapterPatternDetector.detect(url)
            _state.value = _state.value.copy(
                isLoading = false,
                title = og.title ?: url,
                coverUrl = og.imageUrl,
                detectedPattern = pattern,
            )
        }
    }

    fun save(url: String, title: String, pattern: String?) {
        viewModelScope.launch {
            val id = repository.idFromUrl(url)
            val entity = WebViewSourceEntity(
                id = id,
                title = title.trim(),
                baseUrl = url.trim(),
                chapterUrlPattern = pattern?.trim()?.takeIf { it.isNotBlank() },
                coverUrl = _state.value.coverUrl,
                lastReadUrl = null,
                lastReadScrollPercent = 0f,
                lastReadChapter = null,
                latestKnownChapter = null,
                anilistId = null,
                malId = null,
                readingStatus = null,
                addedAt = System.currentTimeMillis(),
                lastReadAt = null,
            )
            repository.save(entity)
            _state.value = _state.value.copy(saved = true)
        }
    }
}
