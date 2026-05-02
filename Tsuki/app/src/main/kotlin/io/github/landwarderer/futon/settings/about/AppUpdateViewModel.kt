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

	init {
		if (nextVersion.value == null) {
			launchLoadingJob {
				repository.fetchUpdate()
			}
		}
	}
}
