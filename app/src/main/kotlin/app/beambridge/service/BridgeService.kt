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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.beambridge.BeamBridgeApp
import app.beambridge.MainActivity
import app.beambridge.R
import app.beambridge.core.server.BridgeHttpServer

/**
 * Foreground service hosting the embedded HTTP server.
 * Wi-Fi lock and (optional) partial wake lock are held only while the server is running and released on stop.
 * The server is rebound when the device's IP address changes.
 */
class BridgeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var server: BridgeHttpServer? = null
    private var boundIp: String? = null
    private var boundPort: Int = 0
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var callbackRegistered = false

    private val graph get() = (application as BeamBridgeApp).graph

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = recheck()
        override fun onLost(network: Network) = recheck()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = recheck()
        private fun recheck() { handler.postDelayed({ if (server != null) startServer(force = false) }, 800) }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            shutdown()
            return START_NOT_STICKY
        }
        createChannel()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("Starting…"),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        startServer(force = true)
        if (!callbackRegistered) {
            runCatching {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(networkCallback)
                callbackRegistered = true
            }
        }
        return START_STICKY
    }

    private fun startServer(force: Boolean) {
        val port = graph.settings.port.value
        val ip = NetUtil.localIpv4()
        if (!force && server != null && ip == boundIp && port == boundPort) return

        stopServer()
        val s = BridgeHttpServer(ip, port, graph.controller, graph.endpoints)
        try {
            s.startServer()
            server = s
            boundIp = ip
            boundPort = port
            acquireLocks()
            val url = ip?.let { "http://$it:$port" }
            BridgeRuntime.status.value = ServerStatus(running = true, url = url)
            notify(url ?: "No Wi-Fi connection")
        } catch (e: Exception) {
            releaseLocks()
            BridgeRuntime.status.value = ServerStatus(running = false, error = e.message ?: "Could not start")
            notify("Could not start: ${e.message ?: "port in use"}")
        }
    }

    private fun stopServer() {
        runCatching { server?.stop() }
        server = null
    }

    private fun shutdown() {
        stopServer()
        releaseLocks()
        BridgeRuntime.status.value = ServerStatus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (callbackRegistered) runCatching {
            (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(networkCallback)
        }
        callbackRegistered = false
        handler.removeCallbacksAndMessages(null)
        stopServer()
        releaseLocks()
        if (BridgeRuntime.status.value.running) BridgeRuntime.status.value = ServerStatus()
        super.onDestroy()
    }

    // ---- power management -------------------------------------------------------------------------

    @Suppress("DEPRECATION")
    private fun acquireLocks() {
        if (wifiLock == null) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(mode, "beambridge:wifi").apply { setReferenceCounted(false); acquire() }
        }
        if (graph.settings.wakeLock.value && wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "beambridge:server").apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
        wakeLock = null
    }

    // ---- notification -----------------------------------------------------------------------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Bridge status", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun notify(text: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BridgeService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_beam)
            .setContentTitle("BeamBridge is online")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        const val ACTION_START = "app.beambridge.START"
        const val ACTION_STOP = "app.beambridge.STOP"
        private const val CHANNEL_ID = "bridge"
        private const val NOTIFICATION_ID = 1

        fun start(context: Context) {
            androidx.core.content.ContextCompat.startForegroundService(
                context, Intent(context, BridgeService::class.java).setAction(ACTION_START),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, BridgeService::class.java).setAction(ACTION_STOP))
        }
    }
}
