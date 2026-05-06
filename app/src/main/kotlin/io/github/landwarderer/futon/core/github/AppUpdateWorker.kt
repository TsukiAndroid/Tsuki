package io.github.landwarderer.futon.core.github

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
import io.github.landwarderer.futon.core.util.ext.awaitUniqueWorkInfoByName
import io.github.landwarderer.futon.settings.work.PeriodicWorkScheduler
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltWorker
class AppUpdateWorker @AssistedInject constructor(
        @Assisted context: Context,
        @Assisted workerParams: WorkerParameters,
        private val appUpdateRepository: AppUpdateRepository,
        private val notificationHelper: AppUpdateNotificationHelper,
) : CoroutineWorker(context, workerParams) {

        override suspend fun doWork(): Result {
                return try {
                        val update = appUpdateRepository.fetchUpdate()
                        if (update != null) {
                                notificationHelper.notify(update)
                        }
                        Result.success()
                } catch (e: CancellationException) {
                        throw e
                } catch (e: Throwable) {
                        Result.failure()
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
                        // Check every 6 hours so users see the update notification soon after
                        // CI publishes a new build. KEEP → UPDATE so the schedule is refreshed
                        // after an app update (KEEP would silently keep the stale old schedule).
                        val request = PeriodicWorkRequestBuilder<AppUpdateWorker>(6, TimeUnit.HOURS)
                                .setConstraints(constraints)
                                .addTag(TAG)
                                .build()
                        workManager
                                .enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request)
                                .await()
                }

                override suspend fun unschedule() {
                        workManager.cancelUniqueWork(TAG).await()
                }

                override suspend fun isScheduled(): Boolean {
                        return workManager
                                .awaitUniqueWorkInfoByName(TAG)
                                .any { !it.state.isFinished }
                }
        }

        companion object {
                const val TAG = "app_update_check"
        }
}
