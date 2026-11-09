package app.beambridge.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _port = MutableStateFlow(prefs.getInt(KEY_PORT, 8080))
    private val _wakeLock = MutableStateFlow(prefs.getBoolean(KEY_WAKE, true))
    private val _autoStart = MutableStateFlow(prefs.getBoolean(KEY_AUTO, false))

    val port: StateFlow<Int> = _port
    val wakeLock: StateFlow<Boolean> = _wakeLock
    val autoStart: StateFlow<Boolean> = _autoStart

    fun setPort(value: Int) { _port.value = value; prefs.edit().putInt(KEY_PORT, value).apply() }
    fun setWakeLock(value: Boolean) { _wakeLock.value = value; prefs.edit().putBoolean(KEY_WAKE, value).apply() }
    fun setAutoStart(value: Boolean) { _autoStart.value = value; prefs.edit().putBoolean(KEY_AUTO, value).apply() }

    private companion object {
        const val KEY_PORT = "port"
        const val KEY_WAKE = "wake_lock"
        const val KEY_AUTO = "auto_start"
    }
}
