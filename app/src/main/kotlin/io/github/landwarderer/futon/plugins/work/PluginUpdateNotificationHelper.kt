package io.github.landwarderer.futon.plugins.work

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import io.github.landwarderer.futon.R
import io.github.landwarderer.futon.plugins.domain.Plugin
import io.github.landwarderer.futon.plugins.ui.ManagePluginsActivity
import javax.inject.Inject

/**
 * Creates update-available notifications for plugins.
 *
 * Each plugin gets its own notification so the user can see which ones have updates.
 * Tapping any notification opens [ManagePluginsActivity].
 */
class PluginUpdateNotificationHelper @Inject constructor(
    @ApplicationContext
    private val context: Context,
) {
    companion object {
        const val CHANNEL_ID = "plugin_updates"
        private const val NOTIFICATION_BASE_ID = 90_000
    }

    fun createChannel() {
        val channel = NotificationChannelCompat.Builder(
            CHANNEL_ID,
            NotificationManagerCompat.IMPORTANCE_DEFAULT,
        )
            .setName(context.getString(R.string.plugin_notif_channel))
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    fun notifyUpdateAvailable(plugin: Plugin, newVersion: String) {
        val manager = NotificationManagerCompat.from(context)
        if (!manager.areNotificationsEnabled()) return

        createChannel()

        val intent = Intent(context, ManagePluginsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            plugin.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.plugin_update_available))
            .setContentText(
                context.getString(
                    R.string.plugin_update_version,
                    plugin.name,
                    plugin.version,
                    newVersion,
                )
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        // Use a stable ID per plugin so updates replace rather than stack
        val notifId = NOTIFICATION_BASE_ID + plugin.id.hashCode()
        runCatching { manager.notify(notifId, notification) }
    }
}
