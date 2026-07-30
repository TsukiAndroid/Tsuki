package io.github.landwarderer.futon.webviewsource.ui.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.landwarderer.futon.core.ui.BaseViewModel
import io.github.landwarderer.futon.core.db.entity.WebViewSourceEntity
import io.github.landwarderer.futon.webviewsource.data.ChapterPatternDetector
import io.github.landwarderer.futon.webviewsource.data.WebViewSourceRepository
import io.github.landwarderer.futon.webviewsource.data.anilist.WebViewAniListRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class WebViewReaderViewModel @Inject constructor(
    private val repository: WebViewSourceRepository,
    private val aniListRepository: WebViewAniListRepository,
    savedStateHandle: SavedStateHandle,
) : BaseViewModel() {

    /** Source ID passed via Intent extras — key: "source_id" */
    private val sourceId: Long = checkNotNull(savedStateHandle["source_id"])

    private val _source = MutableStateFlow<WebViewSourceEntity?>(null)
    val source: StateFlow<WebViewSourceEntity?> = _source.asStateFlow()

    // Current in-memory progress (flushed to DB periodically and on pause)
    private var currentUrl: String? = null
    private var currentScrollPercent: Float = 0f
    private var currentChapter: Float? = null

    /** Last chapter number that was successfully synced to AniList. */
    private var lastSyncedChapter: Int = -1

    private var autoSaveJob: Job? = null

    init {
        viewModelScope.launch {
            val entity = repository.observeById(sourceId).filterNotNull().first()
            _source.value = entity
        }
    }

    /** Called by the JS bridge when the WebView URL changes. */
    fun onUrlChanged(url: String) {
        currentUrl = url
        val source = _source.value ?: return
        currentChapter = ChapterPatternDetector.extractChapter(url, source.chapterUrlPattern)

        // Sync to AniList when chapter advances
        val chapterInt = currentChapter?.toInt() ?: return
        val anilistId = source.anilistId ?: return
        if (chapterInt > lastSyncedChapter) {
            lastSyncedChapter = chapterInt
            viewModelScope.launch {
                aniListRepository.syncProgress(anilistId, chapterInt)
            }
        }
    }

    /** Called by the JS bridge with a scroll percentage 0.0–1.0. */
    fun onScrollChanged(percent: Float) {
        currentScrollPercent = percent.coerceIn(0f, 1f)
    }

    /** Start the 5-second auto-save loop. Call from onResume. */
    fun startAutoSave() {
        autoSaveJob?.cancel()
        autoSaveJob = viewModelScope.launch {
            while (isActive) {
                delay(5_000)
                flushProgress()
            }
        }
    }

    /** Stop auto-save. Call from onPause. */
    fun stopAutoSave() {
        autoSaveJob?.cancel()
    }

    /** Persist current progress immediately. Call from onPause. */
    fun flushProgress() {
        val url = currentUrl ?: return
        viewModelScope.launch {
            repository.updateProgress(
                id = sourceId,
                url = url,
                scrollPercent = currentScrollPercent,
                chapter = currentChapter,
            )
        }
    }
}
