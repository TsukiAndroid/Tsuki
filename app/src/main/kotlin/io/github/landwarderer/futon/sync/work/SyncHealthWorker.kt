package io.github.landwarderer.futon.sync.work

import android.accounts.AccountManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import dagger.Reusable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.util.ext.awaitUniqueWorkInfoByName
import io.github.landwarderer.futon.core.util.ext.checkNotificationPermission
import io.github.landwarderer.futon.settings.SettingsActivity
import io.github.landwarderer.futon.settings.work.PeriodicWorkScheduler
import io.github.landwarderer.futon.sync.domain.SyncController
import kotlinx.coroutines.CancellationException
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@HiltWorker
class SyncHealthWorker @AssistedInject constructor(
        @Assisted context: Context,
        @Assisted workerParams: WorkerParameters,
        private val syncController: SyncController,
) : CoroutineWorker(context, workerParams) {

        override suspend fun doWork(): Result = try {
                val accountType = applicationContext.getString(R.string.account_type_sync)
                val am = AccountManager.get(applicationContext)
                val account = am.getAccountsByType(accountType).firstOrNull()
                        ?: return Result.success() // sync not configured — nothing to check

                val authorityFavourites = applicationContext.getString(R.string.sync_authority_favourites)
                val authorityHistory = applicationContext.getString(R.string.sync_authority_history)
                val lastFavourites = syncController.getLastSync(account, authorityFavourites)
                val lastHistory = syncController.getLastSync(account, authorityHistory)
                val lastSync = maxOf(lastFavourites, lastHistory)

                val overdue = lastSync == 0L || (System.currentTimeMillis() - lastSync) > OVERDUE_THRESHOLD_MS
                if (overdue) {
                        notifyOverdue(account.name)
                }
                Result.success()
        } catch (e: CancellationException) {
                throw e
        } catch (e: Throwable) {
                Result.retry()
        }

        private fun notifyOverdue(accountName: String) {
                if (!applicationContext.checkNotificationPermission(CHANNEL_ID)) return
                val manager = NotificationManagerCompat.from(applicationContext)
                val channel = NotificationChannelCompat.Builder(
                        CHANNEL_ID,
                        NotificationManagerCompat.IMPORTANCE_DEFAULT,
                ).setName(applicationContext.getString(R.string.sync_health_channel)).build()
                manager.createNotificationChannel(channel)

                val intent = Intent(applicationContext, SettingsActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val pendingIntent = PendingIntentCompat.getActivity(
                        applicationContext, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT, false,
                )

                val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_sync)
                        .setContentTitle(applicationContext.getString(R.string.sync_overdue_title))
                        .setContentText(
                                applicationContext.getString(R.string.sync_overdue_message, accountName),
                        )
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setContentIntent(pendingIntent)
                        .build()

                manager.notify(NOTIFICATION_ID, notification)
        }

        @Reusable
        class Scheduler @Inject constructor(
                private val workManager: WorkManager,
        ) : PeriodicWorkScheduler {

                override suspend fun schedule() {
                        val request = PeriodicWorkRequestBuilder<SyncHealthWorker>(1, TimeUnit.DAYS)
                                .setConstraints(Constraints.Builder().build())
                                .addTag(TAG)
                                .build()
                        workManager
                                .enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.KEEP, request)
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
                const val TAG = "sync_health_check"
                const val CHANNEL_ID = "sync_health"
                private const val NOTIFICATION_ID = 201
                private val OVERDUE_THRESHOLD_MS = TimeUnit.HOURS.toMillis(26)
        }
}
