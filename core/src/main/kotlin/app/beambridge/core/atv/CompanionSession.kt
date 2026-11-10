package app.beambridge.core.atv

import java.io.OutputStream
import java.util.UUID

/** One fake Apple TV's long-lived identity. Persisted so a paired iPhone recognises it after restarts. */
data class AtvIdentity(
    val name: String,
    /** MAC-style id, e.g. "A2:3F:…". */
    val deviceId: String,
    /** Upper-case UUID string; its bytes are the pairing identifier. */
    val serverIdentifier: String,
    /** 32-byte Ed25519 seed (long-term signing key). */
    val seed: ByteArray,
    /** 16-byte identity-resolving key handed to the iPhone at pairing. */
    val altIrk: ByteArray,
) {
    val macBytes: ByteArray get() = deviceId.split(':').map { it.toInt(16).toByte() }.toByteArray()
    val publicKey: ByteArray by lazy { AtvCrypto.ed25519Public(seed) }

    companion object {
        fun generate(name: String): AtvIdentity {
            val mac = AtvCrypto.randomBytes(6).also { it[0] = ((it[0].toInt() or 0x02) and 0xFE).toByte() } // locally administered, unicast
            return AtvIdentity(
                name = name,
                deviceId = mac.joinToString(":") { "%02X".format(it) },
                serverIdentifier = UUID.randomUUID().toString().uppercase(),
                seed = AtvCrypto.randomBytes(32),
                altIrk = AtvCrypto.randomBytes(16),
            )
        }
    }
}

/** iPhones that completed PIN pairing with a fake Apple TV: pairing id → Ed25519 public key. */
interface AtvPairings {
    fun publicKeyOf(clientId: String): ByteArray?
    fun add(clientId: String, publicKey: ByteArray)
}

class InMemoryAtvPairings : AtvPairings {
    private val map = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
    override fun publicKeyOf(clientId: String) = map[clientId]
    override fun add(clientId: String, publicKey: ByteArray) { map[clientId] = publicKey }
    val size get() = map.size
}

interface AtvSessionListener {
    /** A finished gesture or button press, as an [IosGesture] id. */
    fun onGesture(gesture: String)
    /** Lifecycle note for logs and the status line ("paired", "verified", "remote_ready", "rejected", …). */
    fun onStatus(event: String, detail: String = "") {}
}

object FrameType {
    const val NOOP = 1; const val PS_START = 3; const val PS_NEXT = 4; const val PV_START = 5; const val PV_NEXT = 6
    const val U_OPACK = 7; const val E_OPACK = 8; const val P_OPACK = 9
    fun valid(t: Int) = t in setOf(NOOP, PS_START, PS_NEXT, PV_START, PV_NEXT, U_OPACK, E_OPACK, P_OPACK)
}

/**
 * One Companion connection from an iPhone: PIN pairing (SRP), pair-verify (X25519 + Ed25519), then the encrypted
 * control channel carrying button presses (`_hidC`) and touch strokes (`_hidT`).
 * Ported from atv-core's session.rs (MIT), with these BeamBridge changes:
 * - pair-verify checks the iPhone's signature against the keys saved at pairing ([requirePairing]), so
 *   "Forget paired iPhones" really locks them out; the original accepted any device.
 * - fresh random SRP and X25519 keys per attempt instead of fixed ones.
 * - gestures come out as `ios_*` ids, touch strokes and button presses kept apart.
 */
class CompanionSession(
    private val out: OutputStream,
    private val identity: AtvIdentity,
    private val pin: () -> String,
    private val pairings: AtvPairings,
    private val requirePairing: () -> Boolean,
    private val listener: AtvSessionListener,
    private val peer: String = "?",
) {
    companion object {
        const val DEVICE_MODEL = "AppleTV14,1"
        const val MEDIA_CONTROL_VOLUME = 0x0100L
        const val MAX_FRAME = 2 * 1024 * 1024
    }

    private val uniqueId = identity.serverIdentifier.toByteArray()
    private var srp = SrpServer(pin())
    private var verifyKeys: AtvCrypto.X25519KeyPair? = null
    private var verifyClientPublic: ByteArray? = null
    private var verifyKey: ByteArray? = null
    private var verifySecret: ByteArray? = null
    private var cipher: CompanionCipher? = null
    private var currentIdentifier: String? = null
    var remoteSessionId: Long = AtvCrypto.random.nextInt().toLong() and 0xFFFF_FFFFL
    /** The iPhone's id from `_systemInfo`, used to drop its older duplicate connections. */
    @Volatile var clientDeviceId: String? = null
        private set
    @Volatile var established = false
        private set
    /** Wall-clock deadline (ms) after which a connection that never started a remote session is closed. */
    @Volatile var deadline: Long? = null

    private var touchOrigin: Pair<Double, Double>? = null
    private var touchLast: Pair<Double, Double>? = null
    private var volume = 0.5

    /** Hook so the server can find this connection's [clientDeviceId] owner. */
    var onSystemInfo: ((String) -> Long?)? = null

    // ---- framing ---------------------------------------------------------------------------------------

    private fun writeRaw(bytes: ByteArray) = synchronized(out) { out.write(bytes); out.flush() }

    private fun header(type: Int, len: Int) = byteArrayOf(type.toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte())

    private fun sendOpack(type: Int, data: OValue) { val p = Opack.pack(data); writeRaw(header(type, p.size) + p) }

    private fun sendEncrypted(data: OValue) {
        val c = cipher ?: throw IllegalStateException("encryption is not enabled")
        val plain = Opack.pack(data)
        val h = header(FrameType.E_OPACK, plain.size + 16)
        writeRaw(h + c.encrypt(plain, h))
    }

    private fun sendResponse(xid: Long?, content: OValue? = null, resultType: Long = 0) {
        val entries = mutableListOf<Pair<OValue, OValue>>(
            OValue.Str("_c") to (content ?: OValue.Dict(emptyList())),
            OValue.Str("_rT") to OValue.Int(resultType),
            OValue.Str("_t") to OValue.Int(3),
        )
        currentIdentifier?.let { entries += OValue.Str("_i") to OValue.Str(it) }
        xid?.let { entries += OValue.Str("_x") to OValue.Int(it) }
        sendEncrypted(OValue.Dict(entries))
    }

    private fun sendEvent(identifier: String, xid: Long?, content: OValue? = null) {
        val entries = mutableListOf<Pair<OValue, OValue>>(
            OValue.Str("_i") to OValue.Str(identifier),
            OValue.Str("_t") to OValue.Int(1),
            OValue.Str("_c") to (content ?: OValue.Dict(emptyList())),
        )
        xid?.let { entries += OValue.Str("_x") to OValue.Int(it) }
        sendEncrypted(OValue.Dict(entries))
    }

    private fun sendMediaCapabilities(xid: Long?) {
        sendEvent("MediaControlStatus", xid, OValue.dict("MediaControlFlags" to OValue.Int(MEDIA_CONTROL_VOLUME)))
        sendEvent("_iMC", xid, OValue.dict("_mcF" to OValue.Int(MEDIA_CONTROL_VOLUME)))
    }

    private fun sendPowerState(xid: Long?) {
        val c = OValue.dict("state" to OValue.Int(3))
        sendEvent("SystemStatus", xid, c)
        sendEvent("TVSystemStatus", xid, c)
    }

    /** Handles one frame. Throws to drop the connection. */
    fun handleFrame(type: Int, payloadIn: ByteArray, hdr: ByteArray) {
        var payload = payloadIn
        if (type == FrameType.E_OPACK) {
            val c = cipher ?: return // encrypted frame before verification: ignore
            payload = c.decrypt(payload, hdr)
        }
        if (payload.isEmpty()) return
        val (data, _) = Opack.unpack(payload)
        when (type) {
            FrameType.PS_START, FrameType.PS_NEXT, FrameType.PV_START, FrameType.PV_NEXT -> handleAuth(type, data)
            FrameType.E_OPACK -> handleControl(data)
            else -> {}
        }
    }

    // ---- pairing ---------------------------------------------------------------------------------------

    private fun handleAuth(type: Int, data: OValue) {
        val pd = Tlv8.read(data["_pd"]?.bytes ?: ByteArray(0))
        val seq = pd[Tlv8.SEQ_NO]?.foldIndexed(0L) { i, acc, b -> acc or ((b.toLong() and 0xFF) shl (8 * i)) } ?: 0L
        when {
            type == FrameType.PS_START && seq == 1L -> pairSetupM1()
            type == FrameType.PS_NEXT && seq == 3L -> pairSetupM3(pd)
            type == FrameType.PS_NEXT && seq == 5L -> pairSetupM5(pd)
            type == FrameType.PV_START && seq == 1L -> pairVerifyM1(pd)
            type == FrameType.PV_NEXT && seq == 3L -> pairVerifyM3(pd)
            else -> {}
        }
    }

    private fun pairSetupM1() {
        srp = SrpServer(pin())
        val tlv = Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(2), Tlv8.SALT to srp.salt(), Tlv8.PUBLIC_KEY to srp.publicKey(), 27 to byteArrayOf(1))
        listener.onStatus("pairing", peer)
        sendOpack(FrameType.PS_NEXT, OValue.dict("_pd" to OValue.Bytes(tlv), "_pwTy" to OValue.Int(1)))
    }

    private fun pairSetupM3(pd: Map<Int, ByteArray>) {
        val clientPublic = pd[Tlv8.PUBLIC_KEY] ?: throw IllegalStateException("missing client public key")
        srp.process(clientPublic)
        val ok = pd[Tlv8.PROOF]?.let { srp.verifyProof(it) } ?: false
        val tlv = if (ok) Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(4), Tlv8.PROOF to srp.keyProofHash())
        else Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(4), Tlv8.ERROR to byteArrayOf(Tlv8.ERROR_AUTH.toByte()))
        if (!ok) listener.onStatus("wrong_pin", peer)
        sendOpack(FrameType.PS_NEXT, OValue.dict("_pd" to OValue.Bytes(tlv)))
    }

    private fun pairSetupM5(pd: Map<Int, ByteArray>) {
        val shared = srp.sessionKey()
        val sessionKey = AtvCrypto.hkdf("Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info", shared)
        val encrypted = pd[Tlv8.ENCRYPTED_DATA] ?: throw IllegalStateException("missing encrypted data")
        val client = Tlv8.read(CompanionCipher(sessionKey, sessionKey, 8).decrypt(encrypted, null, "PS-Msg05".toByteArray()))

        // The iPhone proves it holds the long-term key it's registering: sign(iOSDeviceX | pairing id | LTPK).
        val clientId = client[Tlv8.IDENTIFIER]
        val clientLtpk = client[Tlv8.PUBLIC_KEY]
        val clientSig = client[Tlv8.SIGNATURE]
        if (clientId == null || clientLtpk == null || clientSig == null) throw IllegalStateException("incomplete pairing data")
        val iosX = AtvCrypto.hkdf("Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info", shared)
        if (!AtvCrypto.ed25519Verify(clientLtpk, iosX + clientId + clientLtpk, clientSig)) {
            listener.onStatus("rejected", "bad pairing signature from $peer")
            sendOpack(FrameType.PS_NEXT, OValue.dict("_pd" to OValue.Bytes(Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(6), Tlv8.ERROR to byteArrayOf(Tlv8.ERROR_AUTH.toByte())))))
            return
        }
        pairings.add(String(clientId, Charsets.UTF_8), clientLtpk)

        val accessoryX = AtvCrypto.hkdf("Pair-Setup-Accessory-Sign-Salt", "Pair-Setup-Accessory-Sign-Info", shared)
        val signature = AtvCrypto.ed25519Sign(identity.seed, accessoryX + uniqueId + identity.publicKey)
        val extra = OValue.dict(
            "altIRK" to OValue.Bytes(identity.altIrk),
            "accountID" to OValue.Str(UUID.randomUUID().toString().uppercase()),
            "model" to OValue.Str(DEVICE_MODEL),
            "wifiMAC" to OValue.Bytes(identity.macBytes),
            "name" to OValue.Str(identity.name),
            "mac" to OValue.Bytes(identity.macBytes),
        )
        val tlv = Tlv8.write(Tlv8.IDENTIFIER to uniqueId, Tlv8.PUBLIC_KEY to identity.publicKey, Tlv8.SIGNATURE to signature, 17 to Opack.pack(extra))
        val enc = CompanionCipher(sessionKey, sessionKey, 8).encrypt(tlv, null, "PS-Msg06".toByteArray())
        sendOpack(FrameType.PS_NEXT, OValue.dict("_pd" to OValue.Bytes(Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(6), Tlv8.ENCRYPTED_DATA to enc))))
        listener.onStatus("paired", peer)
        srp = SrpServer(pin())
    }

    private fun pairVerifyM1(pd: Map<Int, ByteArray>) {
        val clientPublic = pd[Tlv8.PUBLIC_KEY]?.takeIf { it.size == 32 } ?: throw IllegalStateException("client public key must be 32 bytes")
        val keys = AtvCrypto.X25519KeyPair.generate()
        val shared = keys.agree(clientPublic)
        val sessionKey = AtvCrypto.hkdf("Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info", shared)
        val signature = AtvCrypto.ed25519Sign(identity.seed, keys.public + uniqueId + clientPublic)
        val enc = CompanionCipher(sessionKey, sessionKey, 8).encrypt(
            Tlv8.write(Tlv8.IDENTIFIER to uniqueId, Tlv8.SIGNATURE to signature), null, "PV-Msg02".toByteArray(),
        )
        verifyKeys = keys; verifyClientPublic = clientPublic; verifyKey = sessionKey; verifySecret = shared
        sendOpack(FrameType.PV_NEXT, OValue.dict("_pd" to OValue.Bytes(Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(2), Tlv8.PUBLIC_KEY to keys.public, Tlv8.ENCRYPTED_DATA to enc))))
    }

    private fun pairVerifyM3(pd: Map<Int, ByteArray>) {
        val keys = verifyKeys ?: throw IllegalStateException("verify M3 before M1")
        val shared = verifySecret!!
        if (requirePairing() && !clientIsKnown(pd, keys)) {
            listener.onStatus("rejected", "unknown iPhone $peer (pair again with the PIN)")
            sendOpack(FrameType.PV_NEXT, OValue.dict("_pd" to OValue.Bytes(Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(4), Tlv8.ERROR to byteArrayOf(Tlv8.ERROR_AUTH.toByte())))))
            return
        }
        cipher = CompanionCipher(AtvCrypto.hkdf("", "ServerEncrypt-main", shared), AtvCrypto.hkdf("", "ClientEncrypt-main", shared), 12)
        sendOpack(FrameType.PV_NEXT, OValue.dict("_pd" to OValue.Bytes(Tlv8.write(Tlv8.SEQ_NO to byteArrayOf(4)))))
        listener.onStatus("verified", peer)
        deadline = System.currentTimeMillis() + 3000 // must start a remote session soon or be closed
    }

    /** Decrypts PV-Msg03 and checks the iPhone signed (its key | its id | our key) with a key saved at pairing. */
    private fun clientIsKnown(pd: Map<Int, ByteArray>, keys: AtvCrypto.X25519KeyPair): Boolean = runCatching {
        val enc = pd[Tlv8.ENCRYPTED_DATA] ?: return false
        val inner = Tlv8.read(CompanionCipher(verifyKey!!, verifyKey!!, 8).decrypt(enc, null, "PV-Msg03".toByteArray()))
        val id = inner[Tlv8.IDENTIFIER] ?: return false
        val sig = inner[Tlv8.SIGNATURE] ?: return false
        val ltpk = pairings.publicKeyOf(String(id, Charsets.UTF_8)) ?: return false
        AtvCrypto.ed25519Verify(ltpk, verifyClientPublic!! + id + keys.public, sig)
    }.getOrDefault(false)

    // ---- control channel -------------------------------------------------------------------------------

    private fun markActive() { established = true; deadline = null }

    private fun handleControl(data: OValue) {
        val identifier = (data["_i"] ?: data["model"])?.string
        currentIdentifier = identifier
        val xid = data["_x"]?.long
        val content = data["_c"] ?: OValue.Dict(emptyList())
        when (identifier) {
            "_sessionStart" -> {
                markActive()
                sendResponse(xid, OValue.dict("_sid" to OValue.Int(remoteSessionId)))
            }
            "TVRCSessionStart" -> {
                markActive()
                val version = content["ProtocolVersionKey"]?.string ?: "1.2"
                sendResponse(xid, OValue.dict("ProtocolVersionKey" to OValue.Str(version)))
                sendMediaCapabilities(xid)
                listener.onStatus("remote_ready", peer)
            }
            "_sessionStop" -> { established = false; sendResponse(xid) }
            "_hidC" -> { markActive(); handleHid(content, xid) }
            "_touchStart" -> { markActive(); sendResponse(xid, OValue.dict("_i" to OValue.Int(1))) }
            "_hidT" -> { markActive(); sendResponse(xid); handleTouch(content) }
            "_touchStop", "_touchMove", "_tiStop" -> { markActive(); sendResponse(xid) }
            "_interest" -> {
                (content["_regEvents"] as? OValue.Arr)?.v?.mapNotNull { it.string }?.forEach { ev ->
                    when (ev) {
                        "MediaControlStatus" -> sendEvent(ev, xid, OValue.dict("MediaControlFlags" to OValue.Int(MEDIA_CONTROL_VOLUME)))
                        "NowPlayingInfo" -> sendEvent(ev, xid)
                        "SystemStatus", "TVSystemStatus" -> sendEvent(ev, xid, OValue.dict("state" to OValue.Int(3)))
                    }
                }
            }
            "FetchMediaControlStatus" -> sendResponse(xid, OValue.dict("MediaControlFlags" to OValue.Int(MEDIA_CONTROL_VOLUME)))
            "FetchSiriRemoteInfo", "FetchCurrentNowPlayingInfoEvent" -> sendResponse(xid)
            "MediaControlCommand", "_mcc" -> handleMediaControl(content, xid)
            "_tiStart" -> sendResponse(xid, OValue.dict("_tiE" to OValue.Bool(false)))
            "FetchLaunchableApplicationsEvent" -> sendResponse(xid, OValue.dict("com.apple.TVSettings" to OValue.Str("Settings")), 2)
            "FetchAttentionState" -> sendResponse(xid, OValue.dict("state" to OValue.Int(3)))
            "_launchApp" -> sendResponse(xid)
            "_systemInfo", "SystemInfo" -> {
                val idsId = content["_idsID"]?.string
                val pubId = content["_pubID"]?.string
                val primary = idsId ?: pubId ?: peer
                clientDeviceId = primary
                onSystemInfo?.invoke(primary)?.let { remoteSessionId = it }
                sendResponse(xid)
                sendPowerState(null)
                // Background probes (high bit in xid) never start a remote session: release them quickly.
                val probe = xid != null && (xid and 0x8000_0000L) != 0L
                if (!established) deadline = System.currentTimeMillis() + if (probe) 1000 else 2500
            }
            else -> if (xid != null) sendResponse(xid)
        }
    }

    private fun handleHid(content: OValue, xid: Long?) {
        val code = content["_hidC"]?.long ?: 0
        val state = content["_hBtS"]?.long ?: 0
        sendResponse(xid)
        if (state != 2L) return // 1 = pressed, 2 = released: act once, on release
        val gesture = IosGesture.fromHid(code)
        listener.onGesture(gesture)
        if (gesture == "ios_volume_up" || gesture == "ios_volume_down" || gesture == "ios_mute") {
            if (gesture == "ios_volume_up") volume = minOf(1.0, volume + 1.0 / 16)
            if (gesture == "ios_volume_down") volume = maxOf(0.0, volume - 1.0 / 16)
            sendMediaCapabilities(xid) // nudges iOS to refresh its volume HUD
        }
    }

    private fun handleMediaControl(content: OValue, xid: Long?) {
        val cmd = (content["MediaControlCommand"] ?: content["_mcc"])?.long
        var response: OValue = OValue.Dict(emptyList())
        when (cmd) {
            5L -> response = OValue.dict("_vol" to OValue.Dbl(volume)) // GetVolume
            6L -> content["_vol"]?.double?.let { requested -> // SetVolume (slider): report the direction
                val next = requested.coerceIn(0.0, 1.0)
                if (next > volume) listener.onGesture("ios_volume_up") else if (next < volume) listener.onGesture("ios_volume_down")
                volume = next
            }
            12L -> response = OValue.dict("_cse" to OValue.Bool(false)) // GetCaptionSettings
        }
        sendResponse(xid, response)
    }

    private fun handleTouch(content: OValue) {
        val phase = content["_tPh"]?.long ?: 0
        val x = content["_cx"]?.double
        val y = content["_cy"]?.double
        if (phase == 1L) {
            if (x != null && y != null) { touchOrigin = x to y; touchLast = x to y }
            return
        }
        val origin = touchOrigin ?: return
        if (x == null || y == null) return
        if (phase != 4L) { touchLast = x to y; return }
        touchOrigin = null; touchLast = null
        IosGesture.fromStroke(x - origin.first, y - origin.second)?.let(listener::onGesture)
    }
}
