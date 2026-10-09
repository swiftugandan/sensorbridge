package dev.sensorbridge.bridge

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dev.sensorbridge.MainActivity
import dev.sensorbridge.R
import dev.sensorbridge.domain.protocol.ProtocolCodec

object BridgeNotifications {
    const val ID = 1
    private const val CHANNEL_ID = "bridge"

    fun createChannel(context: Context) {
        val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(context.getString(R.string.notification_channel_bridge))
            .setDescription(context.getString(R.string.notification_channel_bridge_description))
            .setShowBadge(false)
            .build()
        NotificationManagerCompat.from(context).createNotificationChannel(channel)
    }

    fun build(context: Context, clients: Int): Notification {
        val openApp = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            context,
            0,
            BridgeService.stopIntent(context),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.resources.getQuantityString(
            R.plurals.notification_bridge_clients,
            clients,
            clients,
            ProtocolCodec.DEFAULT_SOCKET_NAME,
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bridge)
            .setContentTitle(context.getString(R.string.notification_bridge_title))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(0, context.getString(R.string.action_stop), stop)
            .build()
    }
}
