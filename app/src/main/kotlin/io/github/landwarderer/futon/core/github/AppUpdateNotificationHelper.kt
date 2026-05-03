package io.github.landwarderer.futon.core.github

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.core.util.ext.checkNotificationPermission
import io.github.landwarderer.futon.settings.about.AppUpdateActivity
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AppUpdateNotificationHelper @Inject constructor(
        @ApplicationContext private val context: Context,
) {

        fun updateChannels() {
                val manager = NotificationManagerCompat.from(context)
                val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                        .setName(context.getString(R.string.app_updates_channel))
                        .setShowBadge(true)
                        .build()
                manager.createNotificationChannel(channel)
        }

        fun notify(version: AppVersion) {
                updateChannels()
                if (!context.checkNotificationPermission(CHANNEL_ID)) return
                val manager = NotificationManagerCompat.from(context)
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                if (version.id == prefs.getLong(KEY_LAST_NOTIFIED_ID, -1L)) return

                val intent = Intent(context, AppUpdateActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                val contentIntent = PendingIntentCompat.getActivity(
                        context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT, false,
                )

                val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                        .setContentTitle(context.getString(R.string.app_update_available))
                        .setContentText(context.getString(R.string.new_version_s, version.name))
                        .setSmallIcon(R.drawable.ic_stat_book_plus)
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                        .setContentIntent(contentIntent)
                        .build()

                manager.notify(TAG, NOTIFICATION_ID, notification)
                prefs.edit().putLong(KEY_LAST_NOTIFIED_ID, version.id).apply()
        }

        companion object {
                const val CHANNEL_ID = "app_updates"
                const val TAG = "app_update"
                const val NOTIFICATION_ID = 100
                private const val PREFS_NAME = "app_update_notif"
                private const val KEY_LAST_NOTIFIED_ID = "last_notified_id"
        }
}
