package app.beambridge.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.beambridge.MainActivity
import app.beambridge.R
import app.beambridge.core.data.HistoryEntry

/**
 * "Power on beamed to Living room TV". One silent notification that is replaced on every send (so rapid presses
 * don't stack up) and dismisses itself after a few seconds.
 */
class SendNotifier(private val context: Context) {
    private val nm = NotificationManagerCompat.from(context)

    init {
        val sys = context.getSystemService(NotificationManager::class.java)
        if (sys.getNotificationChannel(CHANNEL) == null) {
            sys.createNotificationChannel(NotificationChannel(CHANNEL, "Sent commands", NotificationManager.IMPORTANCE_LOW).apply {
                description = "A short confirmation each time an IR command is sent"
                setShowBadge(false)
            })
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    fun show(e: HistoryEntry) {
        if (!nm.areNotificationsEnabled()) return
        val key = e.button.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }
        val target = e.deviceName ?: e.device
        val title = when {
            !e.ok -> "Couldn't send $key"
            target != null -> "$key beamed to $target"
            else -> "$key beamed"
        }
        val text = buildString {
            append(if (e.ok) "Sent" else e.message ?: "Failed")
            append(" · from ").append(e.source)
        }
        val open = PendingIntent.getActivity(context, 3, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_beam)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setTimeoutAfter(4_000)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        runCatching { nm.notify(ID, n) }
    }

    companion object {
        private const val CHANNEL = "beams"
        private const val ID = 2
    }
}
