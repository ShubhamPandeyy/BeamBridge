package app.beambridge.core.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

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
    /** Only ever a *new* password on its way in; stores keep [passwordHash] and clear this. */
    val password: String = "",
    /** See [Passwords]. */
    val passwordHash: String = "",
    /**
     * Allow actions (blast, raw, ac, scenes, finder) over plain GET URLs, for automations that can only call a URL.
     * Cross-site browser requests are refused either way.
     */
    val allowGetActions: Boolean = true,
    val wakeLock: Boolean = true,
    val autoStart: Boolean = false,
    // ---- app-only preferences (not exposed to the web UI) ----
    /** "system" = Material You wallpaper colours (Android 12+), otherwise a preset palette name, see the app's Theme.kt. */
    val theme: String = "system",
    /** "auto" | "light" | "dark" */
    val darkMode: String = "auto",
    /** Show an open remote over the lock screen. */
    val lockScreen: Boolean = true,
    /** One-time migration marker: builds before 1.5 saved lockScreen=false; it is switched on once, then left to the user. */
    val lockScreenMigrated: Boolean = false,
    /** Phone volume keys control the open remote (both together = power). */
    val volumeKeys: Boolean = true,
    /** Silent "X beamed to Y" notification after each command. */
    val notifyOnSend: Boolean = true,
    /** First-run onboarding finished. */
    val onboarded: Boolean = false,
    /** The "long-press a key to edit" tip was dismissed. */
    val tipLongPress: Boolean = false,
) {
    val hasPassword: Boolean get() = passwordHash.isNotEmpty() || password.isNotEmpty()

    fun validate(): String? = when {
        port !in 1024..65535 -> "Port must be 1024-65535"
        authMode == AuthMode.PASSWORD && username.isBlank() -> "Username needed"
        password.isNotEmpty() && password.length < 4 -> "Password needs 4+ characters"
        authMode == AuthMode.PASSWORD && !hasPassword -> "Password needs 4+ characters"
        else -> null
    }

    fun checkPassword(candidate: String): Boolean = when {
        passwordHash.isNotEmpty() -> Passwords.verify(candidate, passwordHash)
        password.isNotEmpty() -> MessageDigest.isEqual(candidate.toByteArray(), password.toByteArray())
        else -> false
    }

    /** Replaces a plain [password] with its hash. */
    fun sealed(): BridgeSettings = if (password.isEmpty()) this else copy(passwordHash = Passwords.hash(password), password = "")
}

interface SettingsStore {
    val settings: StateFlow<BridgeSettings>
    fun update(transform: (BridgeSettings) -> BridgeSettings): BridgeSettings
}

class JsonFileSettingsStore(private val file: File) : SettingsStore {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private val state = MutableStateFlow(
        SafeFile.read(file, BridgeSettings()) { json.decodeFromString(BridgeSettings.serializer(), it) },
    )
    override val settings: StateFlow<BridgeSettings> = state.asStateFlow()

    init {
        // Older builds stored the web password in plain text: hash it once.
        if (state.value.password.isNotEmpty()) update { it }
    }

    @Synchronized
    override fun update(transform: (BridgeSettings) -> BridgeSettings): BridgeSettings {
        val next = transform(state.value).sealed()
        SafeFile.write(file, json.encodeToString(BridgeSettings.serializer(), next))
        state.value = next
        return next
    }
}

class InMemorySettingsStore(initial: BridgeSettings = BridgeSettings()) : SettingsStore {
    private val state = MutableStateFlow(initial)
    override val settings: StateFlow<BridgeSettings> = state.asStateFlow()
    @Synchronized override fun update(transform: (BridgeSettings) -> BridgeSettings) = transform(state.value).sealed().also { state.value = it }
}
