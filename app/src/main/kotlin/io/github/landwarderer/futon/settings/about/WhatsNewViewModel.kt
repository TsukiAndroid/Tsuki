package io.github.landwarderer.futon.settings.about

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import io.github.landwarderer.futon.BuildConfig
import io.github.landwarderer.futon.core.github.AppUpdateRepository
import io.github.landwarderer.futon.core.ui.BaseViewModel
import javax.inject.Inject

@HiltViewModel
class WhatsNewViewModel @Inject constructor(
        private val repository: AppUpdateRepository,
) : BaseViewModel() {

        val releaseNotes = MutableStateFlow<String?>(null)

        /** The full version name shown in the dialog title, e.g. "1.60.0-alpha". */
        val versionName: String = BuildConfig.VERSION_NAME

        init {
                launchLoadingJob(Dispatchers.IO) {
                        releaseNotes.value = repository.fetchCurrentReleaseNotes()
                        // Mark seen immediately — dialog won't reappear on back-navigation.
                        repository.markWhatsNewSeen()
                }
        }
}
