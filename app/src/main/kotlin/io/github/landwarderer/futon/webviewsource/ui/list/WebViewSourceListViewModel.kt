package io.github.landwarderer.futon.webviewsource.ui.list

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.landwarderer.futon.core.db.entity.WebViewSourceEntity
import io.github.landwarderer.futon.core.ui.BaseViewModel
import io.github.landwarderer.futon.webviewsource.data.WebViewSourceRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WebViewSourceListViewModel @Inject constructor(
    private val repository: WebViewSourceRepository,
) : BaseViewModel() {

    /** All WebView sources, sorted by last-read timestamp (Room handles ordering). */
    val sources: StateFlow<List<WebViewSourceEntity>> = repository.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(id: Long) {
        viewModelScope.launch {
            repository.delete(id)
        }
    }

    fun updateTitle(source: WebViewSourceEntity, newTitle: String) {
        viewModelScope.launch {
            repository.save(source.copy(title = newTitle.trim()))
        }
    }

    fun updatePattern(source: WebViewSourceEntity, newPattern: String) {
        viewModelScope.launch {
            repository.save(
                source.copy(
                    chapterUrlPattern = newPattern.trim().takeIf { it.isNotBlank() },
                ),
            )
        }
    }
}
