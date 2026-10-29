package app.nestbox.adb

import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.RemoteInput
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import app.nestbox.R
import app.nestbox.service.Notifications
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Keeps pairing alive while the user is in Settings. The Settings pairing dialog closes when
 * another app opens, so the code is typed into this notification's reply field instead.
 */
class PairingService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var working = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.ensureChannels(this)
        when (intent?.action) {
            ACTION_CODE -> {
                val code = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(KEY_CODE)?.toString()
                if (code != null) submit(code)
            }
            ACTION_CANCEL -> finish()
            else -> begin()
        }
        return START_NOT_STICKY
    }

    private var begun = false

    private fun begin() {
        val again = begun
        begun = true
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification("Looking for the pairing screen", "Open Wireless debugging › Pair device with pairing code", false),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        ProcessLimit.startDiscovery(this)
        if (again) return
        scope.launch {
            ProcessLimit.state.collect { s ->
                when (s) {
                    is PairState.Ready -> update("Enter the pairing code", "Type the 6 digits shown in Settings", true)
                    is PairState.Working -> update("${s.step}…", "Nestbox is setting up this phone", false)
                    PairState.Done -> {
                        update("Background process limit is off", "Machines can now run all their processes", false)
                        delay(4000)
                        finish()
                    }
                    is PairState.Failed -> if (!working) Unit else update("Could not finish setup", s.message, true)
                    else -> Unit
                }
            }
        }
        // Give up after 5 minutes so the notification does not linger.
        scope.launch {
            delay(5 * 60_000)
            if (!working) finish()
        }
    }

    private fun submit(code: String) {
        working = true
        scope.launch { ProcessLimit.pairAndFix(this@PairingService, code) }
    }

    private fun update(title: String, text: String, askCode: Boolean) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(title, text, askCode))
    }

    private fun notification(title: String, text: String, askCode: Boolean): android.app.Notification {
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, PairingService::class.java).setAction(ACTION_CANCEL), PendingIntent.FLAG_IMMUTABLE,
        )
        val b = NotificationCompat.Builder(this, Notifications.CHANNEL_SETUP)
            .setSmallIcon(R.drawable.ic_stat_nestbox)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(!askCode)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .addAction(0, "Cancel", cancel)
        if (askCode) {
            val reply = PendingIntent.getService(
                this, 2, Intent(this, PairingService::class.java).setAction(ACTION_CODE),
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val input = RemoteInput.Builder(KEY_CODE).setLabel("Pairing code").build()
            b.addAction(NotificationCompat.Action.Builder(0, "Enter code", reply).addRemoteInput(input).build())
        }
        return b.build()
    }

    private fun finish() {
        ProcessLimit.stopDiscovery()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 900
        private const val KEY_CODE = "code"
        private const val ACTION_CODE = "app.nestbox.action.PAIR_CODE"
        private const val ACTION_CANCEL = "app.nestbox.action.PAIR_CANCEL"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PairingService::class.java))
        }
    }
}
