package com.phone2gamepad

import com.phone2gamepad.net.ControlChannel
import com.phone2gamepad.protocol.ControlFrames
import com.phone2gamepad.protocol.Crypto
import com.phone2gamepad.protocol.Keys
import com.phone2gamepad.protocol.ProtocolException
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * Loopback handshake test: a minimal PC-side peer written from PROTOCOL.md section 6 (same JCA primitives)
 * exercised by the real [ControlChannel]. The independent cross-language check was done against a Python peer
 * (tools/fake_pc_server.py) on a real device.
 */
class HandshakeLoopbackTest {
    private lateinit var server: ServerSocket
    private val pin = "123456"
    private val deviceSecret = AtomicReference<ByteArray>()
    private val serverError = AtomicReference<Throwable>()

    @Before
    fun start() {
        server = ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())
    }

    @After
    fun stop() {
        server.close()
        serverError.get()?.let { throw AssertionError("server thread failed", it) }
    }

    private fun serve(serverPin: String, tamperServerProof: Boolean = false, body: (java.net.Socket) -> Unit = {}) = thread(isDaemon = true) {
        try {
            server.accept().use { s ->
                val inp = s.getInputStream()
                val out = s.getOutputStream()
                assertEquals("hello", ControlFrames.read(inp).getString("t"))
                ControlFrames.write(out, JSONObject().put("t", "hello_ok").put("ver", 1).put("serverName", "TEST-PC")
                    .put("paired", false).put("pairingOpen", true).put("udpPort", 47822).put("caps", org.json.JSONArray()))
                val req = ControlFrames.read(inp)
                assertEquals("pair_request", req.getString("t"))
                val pubC = ControlFrames.unb64(req.getString("pub"))
                val nonceC = ControlFrames.unb64(req.getString("nonce"))
                val kp = Crypto.generateEcKeyPair()
                val pubS = Crypto.encodePublicKey(kp.public)
                val nonceS = Crypto.randomBytes(16)
                ControlFrames.write(out, JSONObject().put("t", "pair_challenge").put("pub", ControlFrames.b64(pubS)).put("nonce", ControlFrames.b64(nonceS)))
                val shared = Crypto.ecdh(kp.private, pubC)
                val ck = Keys.confirmKey(shared, serverPin, nonceC, nonceS)
                val proof = ControlFrames.unb64(ControlFrames.read(inp).getString("proof"))
                if (!Crypto.constantTimeEquals(proof, Keys.proofClient(ck, pubC, pubS))) {
                    ControlFrames.write(out, JSONObject().put("t", "pair_result").put("ok", false).put("reason", "bad_pin"))
                } else {
                    deviceSecret.set(Keys.deviceSecret(shared, nonceC, nonceS))
                    val ps = Keys.proofServer(ck, pubC, pubS).also { if (tamperServerProof) it[0] = (it[0] + 1).toByte() }
                    ControlFrames.write(out, JSONObject().put("t", "pair_result").put("ok", true).put("proof", ControlFrames.b64(ps)))
                    body(s)
                }
            }
        } catch (e: Throwable) {
            if (!server.isClosed) serverError.set(e)
        }
    }

    private fun client() = ControlChannel.open("127.0.0.1", server.localPort).also { it.hello("dev-1", "Test phone") }

    @Test
    fun pairingWithCorrectPinYieldsSameSecretOnBothSides() {
        val t = serve(pin)
        val secret = client().use { it.pair(pin) }
        t.join(2000)
        assertEquals(32, secret.size)
        assertArrayEquals(deviceSecret.get(), secret)
    }

    @Test
    fun wrongPinIsRejectedAsBadPin() {
        val t = serve(serverPin = "999999")
        try {
            client().use { it.pair(pin) }
            fail("expected bad_pin")
        } catch (e: ProtocolException) {
            assertEquals("bad_pin", e.code)
        }
        t.join(2000)
    }

    @Test
    fun forgedServerProofIsRejected() {
        val t = serve(pin, tamperServerProof = true)
        try {
            client().use { it.pair(pin) }
            fail("expected auth_failed")
        } catch (e: ProtocolException) {
            assertEquals("auth_failed", e.code)
        }
        t.join(2000)
    }

    @Test
    fun sessionStartDerivesKeysMatchingThePeer() {
        val secret = Crypto.randomBytes(32)
        val sid = 0xCAFEBABEL
        val keysSeenByServer = AtomicReference<Keys.SessionKeys>()
        val t = thread(isDaemon = true) {
            try {
                server.accept().use { s ->
                    val inp = s.getInputStream(); val out = s.getOutputStream()
                    val req = ControlFrames.read(inp)
                    assertEquals("session_request", req.getString("t"))
                    val nc = ControlFrames.unb64(req.getString("nonce"))
                    val ns = Crypto.randomBytes(16)
                    ControlFrames.write(out, JSONObject().put("t", "session_challenge").put("nonce", ControlFrames.b64(ns)))
                    val mac = ControlFrames.unb64(ControlFrames.read(inp).getString("mac"))
                    assertTrue(Crypto.constantTimeEquals(mac, Keys.macClient(secret, nc, ns)))
                    keysSeenByServer.set(Keys.sessionKeys(secret, nc, ns, sid))
                    ControlFrames.write(out, JSONObject().put("t", "session_start").put("ok", true).put("sessionId", sid)
                        .put("mac", ControlFrames.b64(Keys.macServer(secret, nc, ns, sid))))
                }
            } catch (e: Throwable) {
                if (!server.isClosed) serverError.set(e)
            }
        }
        val info = ControlChannel.open("127.0.0.1", server.localPort).use { it.startSession(secret) }
        t.join(2000)
        assertEquals(sid, info.sessionId)
        assertArrayEquals(keysSeenByServer.get().c2s, info.keys.c2s)
        assertArrayEquals(keysSeenByServer.get().s2c, info.keys.s2c)
    }
}
