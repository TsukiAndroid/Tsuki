package io.github.landwarderer.futon.plugins.work

import android.content.Context
import android.util.Log
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
import io.github.landwarderer.futon.plugins.data.PluginDownloader
import io.github.landwarderer.futon.plugins.data.PluginRepository
import io.github.landwarderer.futon.plugins.domain.Plugin
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * WorkManager job that runs daily to check all installed plugins for updates.
 *
 * For each enabled plugin that has a [Plugin.githubRepo] configured, it calls
 * the GitHub Releases API to see if a newer version exists.
 * When an update is found, a notification is posted via [PluginUpdateNotificationHelper].
 */
@HiltWorker
class PluginUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val pluginRepository: PluginRepository,
    private val pluginDownloader: PluginDownloader,
    private val notificationHelper: PluginUpdateNotificationHelper,
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG    = "PluginUpdateWorker"
        const val WORK_TAG = "plugin_update_check"
    }

    override suspend fun doWork(): Result {
        return try {
            val plugins = pluginRepository.plugins.value
                .filter { it.isEnabled && it.githubRepo != null }

            for (plugin in plugins) {
                checkPluginUpdate(plugin)
            }
            Result.success()
        } catch (e: Throwable) {
            Log.e(TAG, "Plugin update check failed: ${e.message}", e)
            Result.failure()
        }
    }

    private suspend fun checkPluginUpdate(plugin: Plugin) {
        val repo = plugin.githubRepo ?: return
        runCatching {
            val newVersion = pluginDownloader.checkForUpdate(repo, plugin.version)
            if (newVersion != null) {
                Log.i(TAG, "Update available for ${plugin.name}: ${plugin.version} → $newVersion")
                notificationHelper.notifyUpdateAvailable(plugin, newVersion)

                // Record the update check timestamp
                pluginRepository.updatePlugin(
                    plugin.copy(lastUpdated = System.currentTimeMillis())
                )
            }
        }.getOrElse { e ->
            Log.w(TAG, "Update check failed for ${plugin.name}: ${e.message}")
        }
    }

    // -------------------------------------------------------------------------
    // Scheduler
    // -------------------------------------------------------------------------

    @Reusable
    class Scheduler @Inject constructor(
        private val workManager: WorkManager,
    ) {
        suspend fun schedule() {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<PluginUpdateWorker>(1, TimeUnit.DAYS)
                .setConstraints(constraints)
                .addTag(WORK_TAG)
                .build()
            workManager
                .enqueueUniquePeriodicWork(
                    WORK_TAG,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
                .await()
        }

        suspend fun unschedule() {
            workManager.cancelUniqueWork(WORK_TAG).await()
        }
    }
}
