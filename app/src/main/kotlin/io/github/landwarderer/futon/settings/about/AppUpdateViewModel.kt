package io.github.landwarderer.futon.settings.about

import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.landwarderer.futon.core.github.AppUpdateRepository
import io.github.landwarderer.futon.core.ui.BaseViewModel
import javax.inject.Inject

@HiltViewModel
class AppUpdateViewModel @Inject constructor(
        private val repository: AppUpdateRepository,
) : BaseViewModel() {

        val nextVersion = repository.observeAvailableUpdate()

        /** Arch label used in the downloaded APK filename (arm64 / arm32 / x86_64 / universal). */
        val deviceArch: String = repository.getDeviceArch()

        init {
                if (nextVersion.value == null) {
                        launchLoadingJob {
                                repository.fetchUpdate()
                        }
                }
        }
}
