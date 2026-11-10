package app.beambridge.core.atv

import app.beambridge.core.data.DeviceStore
import app.beambridge.core.data.SafeFile
import app.beambridge.core.data.SavedDevice
import app.beambridge.core.data.SettingsStore
import app.beambridge.core.server.BridgeController
import app.beambridge.core.server.SendResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Base64
import java.util.concurrent.Executors

/** Publishes/withdraws Bonjour records. Android: NsdManager. Tests: a no-op. */
interface MdnsPublisher {
    fun publish(key: String, services: List<MdnsService>)
    fun withdraw(key: String)
}

@Serializable
data class StoredAtvIdentity(val deviceId: String, val serverIdentifier: String, val seed: String, val altIrk: String)

@Serializable
data class StoredPairing(val device: String, val clientId: String, val publicKey: String, val pairedAt: Long = System.currentTimeMillis())

@Serializable
data class IosRemoteFile(val identities: Map<String, StoredAtvIdentity> = emptyMap(), val pairings: List<StoredPairing> = emptyList())

/** One BeamBridge device as the iPhone sees it. */
data class AtvEntry(val deviceId: String, val name: String, val port: Int, val connected: Boolean)

data class IosRemoteState(
    val running: Boolean = false,
    val entries: List<AtvEntry> = emptyList(),
    val pairedPhones: Int = 0,
    /** Last thing that happened, for the status line ("Swipe up → Up on TCL TV"). */
    val lastEvent: String? = null,
    val error: String? = null,
)

/** The gesture-learning screen: which device is listening and the last gesture it caught. */
data class LearnState(val deviceId: String, val gesture: String? = null, val seq: Int = 0)

/**
 * Runs one fake Apple TV per BeamBridge device, so the iPhone's Control Center remote lists "TCL TV", "Bedroom AC"…
 * as separate Apple TVs. A gesture on one of them sends the IR button mapped to it in [SavedDevice.iosGestures].
 * While the setup screen is listening for a device, its gestures are captured instead of sent.
 */
class IosRemoteHub(
    private val store: DeviceStore,
    private val settings: SettingsStore,
    private val controller: BridgeController,
    private val file: File?,
    private val publisher: MdnsPublisher,
    private val scope: CoroutineScope,
) {
    companion object {
        /** Each Apple TV uses 3 ports and a few threads; this keeps the phone's load sane. */
        const val MAX_DEVICES = 12
        const val SOURCE = "iphone"
    }

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val lock = Any()
    private var data: IosRemoteFile = file?.let { SafeFile.read(it, IosRemoteFile()) { t -> json.decodeFromString(IosRemoteFile.serializer(), t) } } ?: IosRemoteFile()

    private val _state = MutableStateFlow(IosRemoteState(pairedPhones = phoneCount()))
    val state: StateFlow<IosRemoteState> = _state.asStateFlow()

    private val _learn = MutableStateFlow<LearnState?>(null)
    val learn: StateFlow<LearnState?> = _learn.asStateFlow()

    /** deviceId → running server (and the name it was published under). */
    private val servers = LinkedHashMap<String, Pair<AtvServer, String>>()
    private var syncJob: Job? = null
    /** IR sends run one at a time, off the network threads, in the order gestures arrived. */
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "ios-remote-send").apply { isDaemon = true } }

    // ---- lifecycle -----------------------------------------------------------------------------------

    fun start() {
        synchronized(lock) {
            if (_state.value.running) return
            ensurePin()
            _state.update { it.copy(running = true, error = null) }
        }
        syncJob = scope.launch {
            combine(store.devices, settings.settings) { devices, s -> wanted(devices, s.iosHidden) }
                .distinctUntilChanged()
                .collect { sync(it) }
        }
    }

    fun stop() {
        syncJob?.cancel(); syncJob = null
        synchronized(lock) {
            servers.keys.toList().forEach { stopServer(it) }
            _state.update { it.copy(running = false, entries = emptyList()) }
        }
    }

    /** Re-publishes every Apple TV (e.g. after the Wi-Fi network changed). */
    fun republish() {
        synchronized(lock) {
            if (!_state.value.running) return
            servers.keys.forEach { publisher.withdraw(it) }
        }
        // Withdrawal is asynchronous; re-announcing at once could clash with the old record and get renamed "… (2)".
        scope.launch {
            kotlinx.coroutines.delay(1500)
            synchronized(lock) { if (_state.value.running) servers.forEach { (id, s) -> publisher.publish(id, s.first.services()) } }
        }
    }

    private fun ensurePin() {
        if (settings.settings.value.iosPin.isEmpty()) {
            val pin = "%04d".format(AtvCrypto.random.nextInt(10_000))
            settings.update { it.copy(iosPin = pin) }
        }
    }

    /** Devices to show: not archived, not hidden from the iPhone, oldest first, capped at [MAX_DEVICES]. */
    private fun wanted(devices: List<SavedDevice>, hidden: List<String>): List<Pair<String, String>> =
        devices.filter { !it.archived && it.id !in hidden }.sortedBy { it.createdAt }.take(MAX_DEVICES).map { it.id to it.name }

    fun sync(wanted: List<Pair<String, String>>) = synchronized(lock) {
        if (!_state.value.running) return
        val ids = wanted.map { it.first }.toSet()
        servers.keys.filter { it !in ids }.forEach { stopServer(it) }
        for ((id, name) in wanted) {
            val cur = servers[id]
            if (cur != null && cur.second == name) continue
            if (cur != null) stopServer(id) // renamed: republish under the new name, same identity
            try {
                val server = AtvServer(identityFor(id, name), { settings.settings.value.iosPin.ifEmpty { "0000" } }, pairingsFor(id),
                    { settings.settings.value.iosRequirePairing }, listenerFor(id)).start()
                servers[id] = server to name
                publisher.publish(id, server.services())
            } catch (e: Exception) {
                _state.update { it.copy(error = "Couldn't start \"$name\": ${e.message}") }
            }
        }
        refreshEntries()
    }

    private fun stopServer(id: String) {
        val s = servers.remove(id) ?: return
        runCatching { publisher.withdraw(id) }
        s.first.stop()
    }

    private fun refreshEntries() {
        val entries = servers.map { (id, s) -> AtvEntry(id, s.second, s.first.companionPort, s.first.hasRemote) }
        _state.update { it.copy(entries = entries, pairedPhones = phoneCount()) }
    }

    // ---- identities & pairings -----------------------------------------------------------------------

    private fun save() { file?.let { SafeFile.write(it, json.encodeToString(IosRemoteFile.serializer(), data)) } }

    private fun identityFor(id: String, name: String): AtvIdentity {
        val dec = Base64.getDecoder()
        data.identities[id]?.let { s ->
            return AtvIdentity(name, s.deviceId, s.serverIdentifier, dec.decode(s.seed), dec.decode(s.altIrk))
        }
        val fresh = AtvIdentity.generate(name)
        val enc = Base64.getEncoder()
        data = data.copy(identities = data.identities + (id to StoredAtvIdentity(fresh.deviceId, fresh.serverIdentifier, enc.encodeToString(fresh.seed), enc.encodeToString(fresh.altIrk))))
        save()
        return fresh
    }

    private fun pairingsFor(device: String) = object : AtvPairings {
        override fun publicKeyOf(clientId: String): ByteArray? = synchronized(lock) {
            data.pairings.firstOrNull { it.device == device && it.clientId == clientId }?.let { Base64.getDecoder().decode(it.publicKey) }
        }
        override fun add(clientId: String, publicKey: ByteArray) = synchronized(lock) {
            data = data.copy(pairings = data.pairings.filterNot { it.device == device && it.clientId == clientId } +
                StoredPairing(device, clientId, Base64.getEncoder().encodeToString(publicKey)))
            save()
            _state.update { it.copy(pairedPhones = phoneCount()) }
        }
    }

    /** Distinct iPhones paired with any of our Apple TVs. */
    private fun phoneCount() = data.pairings.map { it.clientId }.distinct().size

    /** Removes every saved iPhone pairing. With "Only paired iPhones" on, they must enter the PIN again. */
    fun forgetPairings() = synchronized(lock) {
        data = data.copy(pairings = emptyList()); save()
        // Drop live sessions too, so the forget takes effect now rather than on the next reconnect.
        servers.keys.toList().forEach { stopServer(it) }
        _state.update { it.copy(pairedPhones = 0) }
        if (_state.value.running) sync(wanted(store.devices.value, settings.settings.value.iosHidden))
    }

    /** A device was deleted: forget its Apple TV identity and pairings. */
    fun forgetDevice(id: String) = synchronized(lock) {
        data = data.copy(identities = data.identities - id, pairings = data.pairings.filterNot { it.device == id }); save()
    }

    // ---- gestures ------------------------------------------------------------------------------------

    private fun listenerFor(deviceId: String) = object : AtvSessionListener {
        override fun onGesture(gesture: String) = handleGesture(deviceId, gesture)
        override fun onStatus(event: String, detail: String) {
            val name = store.get(deviceId)?.name ?: deviceId
            val text = when (event) {
                "paired" -> "An iPhone paired with $name"
                "wrong_pin" -> "Wrong PIN entered for $name"
                "rejected" -> "Blocked an unpaired iPhone on $name"
                "remote_ready" -> "iPhone remote connected to $name"
                else -> null
            }
            synchronized(lock) { refreshEntries() }
            if (text != null) _state.update { it.copy(lastEvent = text) }
        }
    }

    fun handleGesture(deviceId: String, gesture: String) {
        val learning = _learn.value
        if (learning != null && learning.deviceId == deviceId) {
            _learn.value = learning.copy(gesture = gesture, seq = learning.seq + 1)
            return
        }
        val device = store.get(deviceId) ?: return
        val button = device.iosGestures[gesture]
        if (button == null) {
            _state.update { it.copy(lastEvent = "${IosGesture.label(gesture)} on ${device.name}: not set up") }
            return
        }
        sender.execute {
            val r = controller.blast(device.id, button, SOURCE)
            val msg = when (r) {
                is SendResult.Ok -> "${IosGesture.label(gesture)} → $button on ${device.name}"
                is SendResult.Failed -> "${IosGesture.label(gesture)} → $button failed: ${r.message}"
                is SendResult.NotFound -> "${IosGesture.label(gesture)} → $button: ${r.message}"
            }
            _state.update { it.copy(lastEvent = msg) }
        }
    }

    fun startLearning(deviceId: String) { _learn.value = LearnState(deviceId) }
    fun clearLearned() { _learn.update { it?.copy(gesture = null) } }
    fun stopLearning() { _learn.value = null }

    /** Maps [gesture] to [button] for a device, or removes the mapping when [button] is null. */
    fun setMapping(deviceId: String, gesture: String, button: String?) = store.update(deviceId) { d ->
        d.copy(iosGestures = if (button == null) d.iosGestures - gesture else d.iosGestures + (gesture to button))
    }
}
