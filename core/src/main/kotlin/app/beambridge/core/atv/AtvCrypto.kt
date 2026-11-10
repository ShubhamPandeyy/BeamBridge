package app.beambridge.core.atv

import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA512Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.SecureRandom

/**
 * Crypto building blocks for Apple pairing, on BouncyCastle's lightweight API (pure Java, so the same code runs
 * in JVM tests and on every Android version BeamBridge supports).
 */
object AtvCrypto {
    val random = SecureRandom()

    fun randomBytes(n: Int) = ByteArray(n).also { random.nextBytes(it) }

    /** HKDF-SHA512, 32-byte output. */
    fun hkdf(salt: String, info: String, secret: ByteArray): ByteArray {
        val gen = HKDFBytesGenerator(SHA512Digest())
        gen.init(HKDFParameters(secret, salt.toByteArray(), info.toByteArray()))
        return ByteArray(32).also { gen.generateBytes(it, 0, 32) }
    }

    fun ed25519Public(seed: ByteArray): ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray =
        Ed25519Signer().run { init(true, Ed25519PrivateKeyParameters(seed, 0)); update(message, 0, message.size); generateSignature() }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean = runCatching {
        Ed25519Signer().run { init(false, Ed25519PublicKeyParameters(publicKey, 0)); update(message, 0, message.size); verifySignature(signature) }
    }.getOrDefault(false)

    class X25519KeyPair(val private: ByteArray) {
        val public: ByteArray = X25519PrivateKeyParameters(private, 0).generatePublicKey().encoded
        fun agree(peerPublic: ByteArray): ByteArray = ByteArray(32).also {
            X25519Agreement().run { init(X25519PrivateKeyParameters(private, 0)); calculateAgreement(X25519PublicKeyParameters(peerPublic, 0), it, 0) }
        }
        companion object { fun generate() = X25519KeyPair(randomBytes(32)) }
    }
}

/**
 * ChaCha20-Poly1305 with separate send/receive keys and counter nonces, as the Companion channel uses.
 * [nonceLength] 12 = the encrypted control channel, 8 = the one-shot pairing messages.
 */
class CompanionCipher(private val outputKey: ByteArray, private val inputKey: ByteArray, private val nonceLength: Int = 12) {
    private var outCounter = 0L
    private var inCounter = 0L

    init { require(outputKey.size == 32 && inputKey.size == 32) { "ChaCha20Poly1305 keys must be 32 bytes" } }

    private fun counterNonce(counter: Long): ByteArray {
        val n = ByteArray(12)
        val pad = 12 - nonceLength
        for (i in 0 until minOf(nonceLength, 8)) n[pad + i] = (counter ushr (8 * i)).toByte()
        return n
    }

    private fun explicitNonce(nonce: ByteArray): ByteArray =
        if (nonce.size >= 12) nonce.copyOf(12) else ByteArray(12 - nonce.size) + nonce

    private fun run(encrypt: Boolean, key: ByteArray, nonce: ByteArray, data: ByteArray, aad: ByteArray?): ByteArray {
        val c = ChaCha20Poly1305()
        c.init(encrypt, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(c.getOutputSize(data.size))
        var len = c.processBytes(data, 0, data.size, out, 0)
        len += c.doFinal(out, len)
        return if (len == out.size) out else out.copyOf(len)
    }

    fun encrypt(data: ByteArray, aad: ByteArray? = null, nonce: ByteArray? = null): ByteArray =
        run(true, outputKey, nonce?.let(::explicitNonce) ?: counterNonce(outCounter++), data, aad)

    /** @throws org.bouncycastle.crypto.InvalidCipherTextException when the data was tampered with or the keys don't match. */
    fun decrypt(data: ByteArray, aad: ByteArray? = null, nonce: ByteArray? = null): ByteArray =
        run(false, inputKey, nonce?.let(::explicitNonce) ?: counterNonce(inCounter++), data, aad)
}
