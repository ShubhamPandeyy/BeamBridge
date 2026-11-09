package app.beambridge.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
enum class AuthMode { GUEST, PASSWORD }

/**
 * Gateway settings shared by the app and the web UI.
 * [bindAddress]: "auto" (Wi-Fi IP, rebinds when it changes), "0.0.0.0" (all interfaces) or a specific local IPv4.
 */
@Serializable
data class BridgeSettings(
    val port: Int = 8080,
    val bindAddress: String = "auto",
    val authMode: AuthMode = AuthMode.GUEST,
    val username: String = "admin",
    val password: String = "",
    val wakeLock: Boolean = true,
    val autoStart: Boolean = false,
) {
    fun validate(): String? = when {
        port !in 1024..65535 -> "Port must be 1024-65535"
        authMode == AuthMode.PASSWORD && username.isBlank() -> "Username needed"
        authMode == AuthMode.PASSWORD && password.length < 4 -> "Password needs 4+ characters"
        else -> null
    }
}

interface SettingsStore {
    val settings: StateFlow<BridgeSettings>
    fun update(transform: (BridgeSettings) -> BridgeSettings): BridgeSettings
}

class JsonFileSettingsStore(private val file: File) : SettingsStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val state = MutableStateFlow(
        runCatching { json.decodeFromString(BridgeSettings.serializer(), file.readText()) }.getOrElse { BridgeSettings() },
    )
    override val settings: StateFlow<BridgeSettings> = state.asStateFlow()

    @Synchronized
    override fun update(transform: (BridgeSettings) -> BridgeSettings): BridgeSettings {
        val next = transform(state.value)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(json.encodeToString(BridgeSettings.serializer(), next))
        if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        state.value = next
        return next
    }
}

class InMemorySettingsStore(initial: BridgeSettings = BridgeSettings()) : SettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<BridgeSettings> = state.asStateFlow()
    @Synchronized override fun update(transform: (BridgeSettings) -> BridgeSettings) = transform(state.value).also { state.value = it }
}
