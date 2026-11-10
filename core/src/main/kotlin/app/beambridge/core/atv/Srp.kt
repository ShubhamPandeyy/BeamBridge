package app.beambridge.core.atv

import java.math.BigInteger
import java.security.MessageDigest

/**
 * SRP-6a server for Apple Pair-Setup: SHA-512, the RFC 5054 3072-bit group, username "Pair-Setup", password = the PIN.
 * It proves both sides know the PIN without ever sending it. Byte layout matches srptools/pyatv
 * (integers as minimal big-endian bytes; only `pad()` widens to the prime size).
 */
class SrpServer(pin: String, private val salt: ByteArray = AtvCrypto.randomBytes(16), serverPrivate: ByteArray = AtvCrypto.randomBytes(32)) {
    private val n = N
    private val g = BigInteger.valueOf(5)
    private val verifier: BigInteger
    private val b = BigInteger(1, serverPrivate)
    private val bigB: BigInteger
    private var clientPublic: BigInteger? = null
    private var sessionKey: ByteArray? = null
    private var keyProof: ByteArray? = null

    init {
        val k = h(bytes(n), pad(g))
        val x = BigInteger(1, h(salt, sha512("Pair-Setup:$pin".toByteArray())))
        verifier = g.modPow(x, n)
        bigB = (BigInteger(1, k) * verifier + g.modPow(b, n)).mod(n)
    }

    fun salt(): ByteArray = salt
    fun publicKey(): ByteArray = bytes(bigB)

    fun process(clientPublicKey: ByteArray) {
        val a = BigInteger(1, clientPublicKey)
        require(a.mod(n).signum() != 0) { "client public key is zero mod N" }
        val u = BigInteger(1, h(pad(a), pad(bigB)))
        val s = (a * verifier.modPow(u, n)).mod(n).modPow(b, n)
        val key = sha512(bytes(s))
        val hn = BigInteger(1, sha512(bytes(n)))
        val hg = BigInteger(1, sha512(bytes(g)))
        keyProof = h(bytes(hn.xor(hg)), sha512("Pair-Setup".toByteArray()), salt, bytes(a), bytes(bigB), key)
        clientPublic = a
        sessionKey = key
    }

    fun verifyProof(proof: ByteArray): Boolean = keyProof?.let { MessageDigest.isEqual(it, proof) } ?: false

    /** H(A | M | K), the server's proof back to the client. */
    fun keyProofHash(): ByteArray = h(bytes(checkNotNull(clientPublic) { "process() first" }), keyProof!!, sessionKey!!)

    fun sessionKey(): ByteArray = checkNotNull(sessionKey) { "process() first" }

    private fun pad(v: BigInteger): ByteArray { val w = bytes(n).size; val bb = bytes(v); return ByteArray(maxOf(0, w - bb.size)) + bb }

    companion object {
        private val N = BigInteger(
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DDEF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245" +
                "E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7EDEE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3BE39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9" +
                "DE2BCBF6955817183995497CEA956AE515D2261898FA051015728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200CBBE117577A615D6C770988C0BAD946E208E24FA074E5AB31" +
                "43DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF", 16,
        )

        /** Minimal big-endian bytes (no sign byte). */
        fun bytes(v: BigInteger): ByteArray {
            if (v.signum() == 0) return byteArrayOf(0)
            val raw = v.toByteArray()
            return if (raw[0].toInt() == 0 && raw.size > 1) raw.copyOfRange(1, raw.size) else raw
        }

        fun sha512(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").digest(data)
        private fun h(vararg parts: ByteArray): ByteArray = MessageDigest.getInstance("SHA-512").run { parts.forEach { update(it) }; digest() }
    }
}
