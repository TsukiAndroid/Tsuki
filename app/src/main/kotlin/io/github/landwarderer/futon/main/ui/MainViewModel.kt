package io.github.landwarderer.futon.main.ui

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import io.github.landwarderer.futon.core.exceptions.EmptyHistoryException
import io.github.landwarderer.futon.core.github.AppUpdateRepository
import io.github.landwarderer.futon.core.prefs.AppSettings
import io.github.landwarderer.futon.core.prefs.observeAsFlow
import io.github.landwarderer.futon.core.prefs.observeAsStateFlow
import io.github.landwarderer.futon.core.ui.BaseViewModel
import io.github.landwarderer.futon.core.util.ext.MutableEventFlow
import io.github.landwarderer.futon.core.util.ext.call
import io.github.landwarderer.futon.explore.data.MangaSourcesRepository
import io.github.landwarderer.futon.history.data.HistoryRepository
import io.github.landwarderer.futon.main.domain.ReadingResumeEnabledUseCase
import org.koitharu.kotatsu.parsers.model.Manga
import io.github.landwarderer.futon.tracker.domain.TrackingRepository
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
        private val historyRepository: HistoryRepository,
        private val appUpdateRepository: AppUpdateRepository,
        trackingRepository: TrackingRepository,
        private val settings: AppSettings,
        readingResumeEnabledUseCase: ReadingResumeEnabledUseCase,
        private val sourcesRepository: MangaSourcesRepository,
) : BaseViewModel() {

        val onOpenReader = MutableEventFlow<Manga>()
        val onFirstStart = MutableEventFlow<Unit>()
        val onShowWhatsNew = MutableEventFlow<Unit>()
        val onShowCrashConsent = MutableEventFlow<Unit>()
        val onShowSyncMigrationBanner = MutableEventFlow<Unit>()
        val onShowUpdateBanner = MutableEventFlow<Unit>()

        val isResumeEnabled = readingResumeEnabledUseCase()
                .withErrorHandling()
                .stateIn(
                        scope = viewModelScope + Dispatchers.IO,
                        started = SharingStarted.WhileSubscribed(5000),
                        initialValue = false,
                )

        val appUpdate = appUpdateRepository.observeAvailableUpdate()

        val feedCounter = trackingRepository.observeUnreadUpdatesCount()
                .withErrorHandling()
                .stateIn(viewModelScope + Dispatchers.IO, SharingStarted.Lazily, 0)

        val isBottomNavPinned = settings.observeAsFlow(
                AppSettings.KEY_NAV_PINNED,
        ) {
                isNavBarPinned
        }.flowOn(Dispatchers.IO)

        val isIncognitoModeEnabled = settings.observeAsStateFlow(
                scope = viewModelScope + Dispatchers.IO,
                key = AppSettings.KEY_INCOGNITO_MODE,
                valueProducer = { isIncognitoModeEnabled },
        )

        init {
                launchJob(Dispatchers.IO) {
                        if (!settings.isCrashConsentShown) {
                                onShowCrashConsent.call(Unit)
                        }
                }
                launchJob(Dispatchers.IO) {
                        if (sourcesRepository.isSetupRequired()) {
                                onFirstStart.call(Unit)
                        }
                }
                launchJob(Dispatchers.IO) {
                        if (appUpdateRepository.shouldShowWhatsNew()) {
                                onShowWhatsNew.call(Unit)
                        }
                }
                launchJob(Dispatchers.IO) {
                        if (appUpdateRepository.shouldShowSyncMigrationBanner()) {
                                appUpdateRepository.removeLegacySyncAccount()
                                appUpdateRepository.markSyncMigrationBannerSeen()
                                onShowSyncMigrationBanner.call(Unit)
                        }
                }
                launchJob(Dispatchers.IO) {
                        // Fetch on startup so the in-app indicator reflects the real state.
                        // AppUpdateWorker keeps availableUpdate populated in background every 6h,
                        // but it's an in-memory StateFlow that resets to null on each process start.
                        val update = appUpdateRepository.fetchUpdate()
                        if (update != null) {
                                onShowUpdateBanner.call(Unit)
                        }
                }
        }

        fun openLastReader() {
                launchLoadingJob(Dispatchers.IO) {
                        val manga = historyRepository.getLastOrNull() ?: throw EmptyHistoryException()
                        onOpenReader.call(manga)
                }
        }

        fun setIncognitoMode(isEnabled: Boolean) {
                settings.isIncognitoModeEnabled = isEnabled
        }
}
