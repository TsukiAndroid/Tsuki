package io.github.landwarderer.futon.webviewsource.ui.anilist

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.landwarderer.futon.core.ui.BaseViewModel
import io.github.landwarderer.futon.scrobbling.common.domain.model.ScrobblerManga
import io.github.landwarderer.futon.webviewsource.data.WebViewSourceRepository
import io.github.landwarderer.futon.webviewsource.data.anilist.WebViewAniListRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class LinkAniListViewModel @Inject constructor(
    private val aniListRepository: WebViewAniListRepository,
    private val sourceRepository: WebViewSourceRepository,
) : BaseViewModel() {

    private val _results = MutableStateFlow<List<ScrobblerManga>>(emptyList())
    val results: StateFlow<List<ScrobblerManga>> = _results.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _linked = MutableStateFlow(false)
    val linked: StateFlow<Boolean> = _linked.asStateFlow()

    val isLoggedIn: Boolean
        get() = aniListRepository.isLoggedIn

    fun search(query: String) {
        if (query.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            _results.value = aniListRepository.searchManga(query)
            _isLoading.value = false
        }
    }

    /**
     * Links [media] to the WebView source identified by [sourceId].
     * Fetches the current reading entry from AniList (if logged in) so we
     * can store the reading status alongside the link.
     */
    fun link(sourceId: Long, media: ScrobblerManga) {
        viewModelScope.launch {
            val entry = aniListRepository.getListEntry(media.id.toInt())
            sourceRepository.updateAnilistLink(
                id = sourceId,
                anilistId = media.id.toInt(),
                status = entry?.status,
            )
            _linked.value = true
        }
    }
}
