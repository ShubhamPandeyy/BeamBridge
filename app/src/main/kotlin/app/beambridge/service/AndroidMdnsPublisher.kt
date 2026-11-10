package app.beambridge.service

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import app.beambridge.core.atv.MdnsPublisher
import app.beambridge.core.atv.MdnsService

/**
 * Announces the fake Apple TVs on the Wi-Fi with Android's built-in Bonjour (NsdManager), the way the iPhone's
 * Control Center remote looks for them. If two devices share a name, Android adds " (2)".
 */
class AndroidMdnsPublisher(context: Context) : MdnsPublisher {
    private val nsd = context.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val active = HashMap<String, MutableList<NsdManager.RegistrationListener>>()

    @Synchronized
    override fun publish(key: String, services: List<MdnsService>) {
        withdraw(key)
        val listeners = mutableListOf<NsdManager.RegistrationListener>()
        for (svc in services) {
            val info = NsdServiceInfo().apply {
                serviceName = svc.name
                serviceType = svc.type
                port = svc.port
                svc.txt.forEach { (k, v) -> runCatching { setAttribute(k, v) }.onFailure { Log.w(TAG, "TXT $k skipped: ${it.message}") } }
            }
            val listener = object : NsdManager.RegistrationListener {
                override fun onServiceRegistered(info: NsdServiceInfo) { Log.i(TAG, "registered ${info.serviceName} ${svc.type}") }
                override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) { Log.w(TAG, "register failed ${svc.type} ($errorCode)") }
                override fun onServiceUnregistered(info: NsdServiceInfo) {}
                override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            }
            runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener); listeners += listener }
                .onFailure { Log.w(TAG, "register ${svc.type}: ${it.message}") }
        }
        active[key] = listeners
    }

    @Synchronized
    override fun withdraw(key: String) {
        active.remove(key)?.forEach { runCatching { nsd.unregisterService(it) } }
    }

    private companion object { const val TAG = "BeamBridgeMdns" }
}
