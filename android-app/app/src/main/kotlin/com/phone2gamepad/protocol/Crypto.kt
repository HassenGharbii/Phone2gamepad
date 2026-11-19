package com.phone2gamepad.protocol

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Thin wrappers over standard JCA primitives (HMAC-SHA256, HKDF per RFC 5869, ECDH P-256). No custom crypto. */
object Crypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    fun hmacSha256(key: ByteArray, vararg parts: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        // SecretKeySpec rejects empty keys; an all-zero 32-byte key is HMAC-equivalent to an empty one.
        mac.init(SecretKeySpec(if (key.isEmpty()) ByteArray(32) else key, "HmacSHA256"))
        for (p in parts) mac.update(p)
        return mac.doFinal()
    }

    /** RFC 5869 HKDF-SHA256. */
    fun hkdf(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt, ikm)
        val out = ByteArray(length)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            t = hmacSha256(prk, t, info, byteArrayOf(counter.toByte()))
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n
            counter++
        }
        return out
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean = MessageDigest.isEqual(a, b)

    // ---- ECDH P-256 ----

    private val ecParams: ECParameterSpec by lazy {
        val ap = AlgorithmParameters.getInstance("EC")
        ap.init(ECGenParameterSpec("secp256r1"))
        ap.getParameterSpec(ECParameterSpec::class.java)
    }

    fun generateEcKeyPair(): KeyPair {
        val g = KeyPairGenerator.getInstance("EC")
        g.initialize(ECGenParameterSpec("secp256r1"), random)
        return g.generateKeyPair()
    }

    private fun to32(v: BigInteger): ByteArray {
        val raw = v.toByteArray()
        val out = ByteArray(32)
        if (raw.size >= 32) System.arraycopy(raw, raw.size - 32, out, 0, 32)
        else System.arraycopy(raw, 0, out, 32 - raw.size, raw.size)
        return out
    }

    /** 65-byte X9.62 uncompressed point: 0x04 || X || Y. */
    fun encodePublicKey(key: PublicKey): ByteArray {
        val w = (key as ECPublicKey).w
        return byteArrayOf(4) + to32(w.affineX) + to32(w.affineY)
    }

    fun decodePublicKey(raw: ByteArray): PublicKey {
        require(raw.size == 65 && raw[0] == 4.toByte()) { "bad EC point" }
        val x = BigInteger(1, raw.copyOfRange(1, 33))
        val y = BigInteger(1, raw.copyOfRange(33, 65))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), ecParams))
    }

    /** Raw 32-byte shared x-coordinate. */
    fun ecdh(privateKey: PrivateKey, peerPublic: ByteArray): ByteArray {
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(privateKey)
        ka.doPhase(decodePublicKey(peerPublic), true)
        return ka.generateSecret()
    }
}
