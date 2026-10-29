package app.nestbox.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import app.nestbox.MainActivity
import app.nestbox.R

object Notifications {
    const val CHANNEL_MACHINES = "machines"
    const val CHANNEL_SETUP = "setup"
    const val EXTRA_OPEN_VM = "openVm"

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_MACHINES, "Running machines", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while a machine runs, with a Stop button"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SETUP, "Phone setup", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Wireless debugging pairing code"
            }
        )
    }

    /** Ongoing "Main · Running · Stop" notification for one machine. */
    fun machine(context: Context, slot: Int, vmId: String, name: String, status: String, serviceClass: Class<*>): android.app.Notification {
        val open = PendingIntent.getActivity(
            context, 100 + slot,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_OPEN_VM, vmId)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            context, 200 + slot,
            Intent(context, serviceClass).setAction(VmProtocol.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_MACHINES)
            .setSmallIcon(R.drawable.ic_stat_nestbox)
            .setContentTitle(name)
            .setContentText(status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }
}
