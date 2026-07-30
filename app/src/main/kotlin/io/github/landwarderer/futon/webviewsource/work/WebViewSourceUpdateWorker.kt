package io.github.landwarderer.futon.webviewsource.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
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
import io.github.landwarderer.futon.webviewsource.data.WebViewSourceRepository
import io.github.landwarderer.futon.webviewsource.data.anilist.WebViewAniListRepository
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Periodic WorkManager worker that checks every 6 hours for new chapters across
 * all WebView sources and fires a notification when one is found.
 *
 * Two detection strategies:
 *  - **AniList** — for sources with an [anilistId], queries the public AniList
 *    GraphQL API for the current chapter count. No auth required.
 *  - **HTML polling** — for unlinked sources that have a [chapterUrlPattern],
 *    fetches the source's home page and extracts the highest chapter number
 *    from any link whose URL matches the pattern.
 *
 * Sources with [notificationsEnabled] = false are silently skipped.
 *
 * Follow [TrackWorker] as the project's reference implementation.
 */
@HiltWorker
class WebViewSourceUpdateWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val repository: WebViewSourceRepository,
    private val aniListRepository: WebViewAniListRepository,
    private val okHttpClient: OkHttpClient,
    private val notificationHelper: WebViewSourceNotificationHelper,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val sources = repository.getAll().filter { it.notificationsEnabled }

        for (source in sources) {
            val latestChapter = when {
                source.anilistId != null ->
                    aniListRepository.fetchLatestChapterCount(source.anilistId)
                source.chapterUrlPattern != null ->
                    fetchLatestChapterFromPage(source.baseUrl, source.chapterUrlPattern)
                else -> null
            } ?: continue

            val knownChapter = source.latestKnownChapter ?: source.lastReadChapter ?: 0f

            if (latestChapter > knownChapter) {
                repository.updateLatestKnownChapter(source.id, latestChapter)
                notificationHelper.notify(source = source, newChapter = latestChapter)
            }
        }

        return Result.success()
    }

    // ── Strategy B — HTML polling ─────────────────────────────────────────────

    /**
     * Fetches the manga's home page and extracts the highest chapter number
     * visible in any URL on the page that matches [pattern].
     *
     * Build a regex from the pattern by:
     * 1. Escaping everything except {N}
     * 2. Replacing the escaped {N} with (\d+(?:\.\d+)?)
     *
     * Returns null if no match is found or the network call fails.
     */
    private suspend fun fetchLatestChapterFromPage(
        baseUrl: String,
        pattern: String,
    ): Float? = runCatching {
        val patternRegex = Regex(
            Regex.escape(pattern).replace(Regex.escape("{N}"), """(\d+(?:\.\d+)?)"""),
        )
        val request = okhttp3.Request.Builder()
            .url(baseUrl)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36")
            .build()
        val html = okHttpClient.newCall(request).execute().use { it.body?.string() } ?: return null
        patternRegex.findAll(html)
            .mapNotNull { it.groupValues.getOrNull(1)?.toFloatOrNull() }
            .maxOrNull()
    }.getOrNull()

    // ── Scheduler — registered with WorkScheduleManager ──────────────────────

    @Reusable
    class Scheduler @Inject constructor(
        private val workManager: WorkManager,
    ) : PeriodicWorkScheduler {

        override suspend fun schedule() {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<WebViewSourceUpdateWorker>(
                repeatInterval = 6,
                repeatIntervalTimeUnit = TimeUnit.HOURS,
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
                .addTag(TAG)
                .build()

            workManager.enqueueUniquePeriodicWork(
                TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            ).await()
        }

        override suspend fun unschedule() {
            workManager.cancelUniqueWork(TAG).await()
        }

        override suspend fun isScheduled(): Boolean =
            workManager.awaitUniqueWorkInfoByName(TAG).any { !it.state.isFinished }
    }

    companion object {
        const val TAG = "webview_source_update"
    }
}
