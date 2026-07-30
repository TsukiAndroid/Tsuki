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

    // NOTE: isLoading is inherited from BaseViewModel (driven by loadingCounter).
    // Do NOT redeclare it here — that causes a "hides member of supertype" compile error.

    private val _linked = MutableStateFlow(false)
    val linked: StateFlow<Boolean> = _linked.asStateFlow()

    val isLoggedIn: Boolean
        get() = aniListRepository.isLoggedIn

    /**
     * Search AniList for manga matching [query].
     * Uses [launchLoadingJob] so the inherited [isLoading] state is updated automatically.
     */
    fun search(query: String) {
        if (query.isBlank()) return
        launchLoadingJob {
            _results.value = aniListRepository.searchManga(query)
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
