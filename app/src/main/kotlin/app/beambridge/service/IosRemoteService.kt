package app.beambridge.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.beambridge.BeamBridgeApp
import app.beambridge.MainActivity
import app.beambridge.R
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the fake Apple TVs alive for the iPhone remote, separate from the web server so
 * each has its own on/off switch. Holds a Wi-Fi lock and a multicast lock (so Bonjour discovery keeps working
 * with the screen off) and re-announces the devices when the network changes.
 */
class IosRemoteService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wifiLock: WifiManager.WifiLock? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    private var callbackRegistered = false
    private var stateJob: Job? = null
    private val hub get() = (application as BeamBridgeApp).graph.iosRemote

    private val republish = Runnable { hub.republish() }
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = later()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = later()
        private fun later() { handler.removeCallbacks(republish); handler.postDelayed(republish, 1500) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { shutdown(); return START_NOT_STICKY }
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("Starting…"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        acquireLocks()
        hub.start()
        if (stateJob == null) stateJob = (application as BeamBridgeApp).graph.scope.launch {
            hub.state.map { s -> s.entries.size to s.entries.count { it.connected } }.distinctUntilChanged().collect { (n, live) ->
                notify(if (n == 0) "No devices to show yet" else "$n device${if (n == 1) "" else "s"} in the iPhone remote" + if (live > 0) " · $live connected" else "")
            }
        }
        if (!callbackRegistered) runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback); callbackRegistered = true
        }
        return START_STICKY
    }

    private fun shutdown() {
        hub.stop()
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        stateJob?.cancel(); stateJob = null
        if (callbackRegistered) runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        callbackRegistered = false
        handler.removeCallbacksAndMessages(null)
        hub.stop()
        releaseLocks()
        super.onDestroy()
    }

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiLock == null) {
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = runCatching { wm.createWifiLock(mode, "beambridge:ios").apply { setReferenceCounted(false); acquire() } }.getOrNull()
        }
        if (multicastLock == null) multicastLock = runCatching { wm.createMulticastLock("beambridge:mdns").apply { setReferenceCounted(false); acquire() } }.getOrNull()
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { multicastLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null; multicastLock = null
    }

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "iPhone remote", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notify(text: String) = getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 2, Intent(this, IosRemoteService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_beam)
            .setContentTitle("iPhone remote is on")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "app.beambridge.IOS_START"
        const val ACTION_STOP = "app.beambridge.IOS_STOP"
        private const val CHANNEL_ID = "ios_remote"
        private const val NOTIFICATION_ID = 3

        fun start(context: Context) = androidx.core.content.ContextCompat.startForegroundService(
            context, Intent(context, IosRemoteService::class.java).setAction(ACTION_START),
        )

        fun stop(context: Context) { context.startService(Intent(context, IosRemoteService::class.java).setAction(ACTION_STOP)) }
    }
}
