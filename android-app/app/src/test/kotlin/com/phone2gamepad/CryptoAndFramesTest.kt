package com.phone2gamepad

import com.phone2gamepad.protocol.ControlFrames
import com.phone2gamepad.protocol.Crypto
import com.phone2gamepad.protocol.Discovery
import com.phone2gamepad.protocol.ProtocolException
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.EOFException

class CryptoAndFramesTest {
    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Test
    fun hkdfMatchesRfc5869TestCase1() {
        val okm = Crypto.hkdf(
            ikm = ByteArray(22) { 0x0b },
            salt = hex("000102030405060708090a0b0c"),
            info = hex("f0f1f2f3f4f5f6f7f8f9"),
            length = 42,
        )
        assertArrayEquals(
            hex("3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865"), okm
        )
    }

    @Test
    fun hmacMatchesRfc4231TestCase2() {
        val mac = Crypto.hmacSha256("Jefe".toByteArray(), "what do ya want for nothing?".toByteArray())
        assertArrayEquals(hex("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843"), mac)
    }

    @Test
    fun ecdhAgreesAndPublicKeysAre65ByteUncompressed() {
        val a = Crypto.generateEcKeyPair()
        val b = Crypto.generateEcKeyPair()
        val pa = Crypto.encodePublicKey(a.public)
        val pb = Crypto.encodePublicKey(b.public)
        assertEquals(65, pa.size)
        assertEquals(4, pa[0].toInt())
        val s1 = Crypto.ecdh(a.private, pb)
        val s2 = Crypto.ecdh(b.private, pa)
        assertEquals(32, s1.size)
        assertArrayEquals(s1, s2)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMalformedPublicKey() {
        Crypto.decodePublicKey(ByteArray(64))
    }

    @Test
    fun controlFrameRoundTrip() {
        val msg = JSONObject().put("t", "hello").put("ver", 1)
        val back = ControlFrames.read(ByteArrayInputStream(ControlFrames.encode(msg)))
        assertEquals("hello", back.getString("t"))
        assertEquals(1, back.getInt("ver"))
    }

    @Test
    fun controlFrameRejectsBadLengthAndJson() {
        for (bytes in listOf(
            byteArrayOf(0, 0, 0, 0),
            byteArrayOf(0, 0x50, 0, 0) + ByteArray(8), // 20480 > max
            byteArrayOf(3, 0, 0, 0, 'x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte()),
            byteArrayOf(2, 0, 0, 0, '{'.code.toByte(), '}'.code.toByte()), // no "t"
        )) {
            try {
                ControlFrames.read(ByteArrayInputStream(bytes))
                fail("expected ProtocolException")
            } catch (e: ProtocolException) {
                assertEquals("bad_frame", e.code)
            }
        }
    }

    @Test(expected = EOFException::class)
    fun controlFrameTruncatedThrowsEof() {
        ControlFrames.read(ByteArrayInputStream(byteArrayOf(10, 0, 0, 0, '{'.code.toByte())))
    }

    @Test
    fun discoveryRequestLayout() {
        assertArrayEquals(hex("50320110efbeadde"), Discovery.encodeRequest(0xDEADBEEFL))
    }

    @Test
    fun discoveryResponseParsing() {
        val name = "DESKTOP-X"
        val bytes = hex("5032011178563412") + byteArrayOf(0x35, 0xB8.toByte(), 0x36, 0xB8.toByte(), 1, name.length.toByte()) + name.toByteArray()
        val r = Discovery.parseResponse(bytes)!!
        assertEquals(0x12345678L, r.nonce)
        assertEquals(47157, r.tcpPort)
        assertEquals(47158, r.udpPort)
        assertEquals(true, r.pairingOpen)
        assertEquals(name, r.name)
        assertNull(Discovery.parseResponse(bytes.copyOf(bytes.size - 1)))
        assertNull(Discovery.parseResponse(bytes.copyOf().also { it[0] = 0 }))
        assertNull(Discovery.parseResponse(bytes.copyOf().also { it[2] = 2 }))
        assertNotNull(Discovery.parseResponse(bytes))
    }
}
