package io.github.landwarderer.futon.webviewsource.work

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.landwarderer.futon.core.db.entity.WebViewSourceEntity
import io.github.landwarderer.futon.core.util.ext.checkNotificationPermission
import io.github.landwarderer.futon.webviewsource.data.ChapterPatternDetector
import io.github.landwarderer.futon.webviewsource.ui.reader.WebViewReaderActivity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WebViewSourceNotificationHelper @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    companion object {
        const val CHANNEL_ID = "webview_source_updates"
        const val CHANNEL_NAME = "Manga Updates"
    }

    init {
        createChannel()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "New chapter notifications for WebView manga sources"
            }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }
    }

    fun notify(source: WebViewSourceEntity, newChapter: Float) {
        if (!context.checkNotificationPermission(CHANNEL_ID)) return

        val chapterUrl = ChapterPatternDetector.buildUrl(source.chapterUrlPattern, newChapter)
            ?: source.baseUrl

        val chapterDisplay = if (newChapter == newChapter.toLong().toFloat()) {
            newChapter.toLong().toString()
        } else {
            newChapter.toString()
        }

        val intent = WebViewReaderActivity.createIntent(context, source.id)
            .putExtra("start_url", chapterUrl)

        val pendingIntent = PendingIntent.getActivity(
            context,
            source.id.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(source.title)
            .setContentText("Chapter $chapterDisplay is available")
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context)
            .notify(source.id.toInt(), notification)
    }
}
