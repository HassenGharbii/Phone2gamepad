package com.phone2gamepad.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Key schedule of PROTOCOL.md section 6. */
object Keys {
    private fun u32le(v: Long): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()
    private fun ascii(s: String) = s.toByteArray(Charsets.US_ASCII)

    class SessionKeys(val c2s: ByteArray, val s2c: ByteArray)

    fun confirmKey(shared: ByteArray, pin: String, nonceC: ByteArray, nonceS: ByteArray): ByteArray =
        Crypto.hkdf(shared + ascii(pin), nonceC + nonceS, ascii("P2G confirm v1"), 32)

    fun proofClient(confirmKey: ByteArray, pubC: ByteArray, pubS: ByteArray): ByteArray =
        Crypto.hmacSha256(confirmKey, ascii("client"), pubC, pubS)

    fun proofServer(confirmKey: ByteArray, pubC: ByteArray, pubS: ByteArray): ByteArray =
        Crypto.hmacSha256(confirmKey, ascii("server"), pubC, pubS)

    fun deviceSecret(shared: ByteArray, nonceC: ByteArray, nonceS: ByteArray): ByteArray =
        Crypto.hkdf(shared, nonceC + nonceS, ascii("P2G device secret v1"), 32)

    fun macClient(deviceSecret: ByteArray, nonceC: ByteArray, nonceS: ByteArray): ByteArray =
        Crypto.hmacSha256(deviceSecret, ascii("auth-c"), nonceC, nonceS)

    fun macServer(deviceSecret: ByteArray, nonceC: ByteArray, nonceS: ByteArray, sessionId: Long): ByteArray =
        Crypto.hmacSha256(deviceSecret, ascii("auth-s"), nonceC, nonceS, u32le(sessionId))

    fun sessionKeys(deviceSecret: ByteArray, nonceC: ByteArray, nonceS: ByteArray, sessionId: Long): SessionKeys {
        val okm = Crypto.hkdf(deviceSecret, nonceC + nonceS, ascii("P2G session v1") + u32le(sessionId), 64)
        return SessionKeys(okm.copyOfRange(0, 32), okm.copyOfRange(32, 64))
    }
}
