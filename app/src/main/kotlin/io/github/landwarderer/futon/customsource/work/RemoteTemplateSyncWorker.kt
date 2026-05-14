package io.github.landwarderer.futon.customsource.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.Reusable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.landwarderer.futon.customsource.data.ParserTemplateRepository
import io.github.landwarderer.futon.customsource.data.RemoteTemplateSync
import io.github.landwarderer.futon.core.util.ext.awaitUniqueWorkInfoByName
import io.github.landwarderer.futon.settings.work.PeriodicWorkScheduler
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Background periodic job that silently syncs parser templates from the public
 * [Space4414/tsuki-parsers](https://github.com/Space4414/tsuki-parsers) GitHub repository.
 *
 * Scheduled to run once per day whenever the device has a network connection.
 * The actual sync logic lives in [RemoteTemplateSync] so it can also be triggered
 * on-demand from [ParserTemplateViewModel.syncFromRemote].
 *
 * New templates are added automatically; existing templates are updated only when
 * the remote version is newer. The user's enabled/disabled state is always preserved.
 */
@HiltWorker
class RemoteTemplateSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val templateRepository: ParserTemplateRepository,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        return try {
            RemoteTemplateSync.syncNow(templateRepository)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            // Silently retry next run — never surface a notification for a background sync failure.
            Result.retry()
        }
    }

    @Reusable
    class Scheduler @Inject constructor(
        private val workManager: WorkManager,
    ) : PeriodicWorkScheduler {

        override suspend fun schedule() {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<RemoteTemplateSyncWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .addTag(TAG)
                .build()
            workManager
                .enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.KEEP, request)
                .await()
        }

        override suspend fun unschedule() {
            workManager.cancelUniqueWork(TAG).await()
        }

        override suspend fun isScheduled(): Boolean =
            workManager.awaitUniqueWorkInfoByName(TAG).any { !it.state.isFinished }
    }

    companion object {
        const val TAG = "remote_template_sync"
    }
}
