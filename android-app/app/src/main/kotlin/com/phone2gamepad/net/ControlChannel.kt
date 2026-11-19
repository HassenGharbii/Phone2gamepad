package com.phone2gamepad.net

import com.phone2gamepad.protocol.ControlFrames
import com.phone2gamepad.protocol.Crypto
import com.phone2gamepad.protocol.Keys
import com.phone2gamepad.protocol.ProtocolException
import com.phone2gamepad.protocol.Wire
import org.json.JSONArray
import org.json.JSONObject
import java.io.Closeable
import java.net.InetSocketAddress
import java.net.Socket

/** [serverId] is the optional stable PC identifier from `hello_ok` (absent on older servers). */
data class HelloInfo(
    val serverName: String,
    val paired: Boolean,
    val pairingOpen: Boolean,
    val udpPort: Int,
    val serverId: String? = null,
) {
    companion object {
        const val MAX_SERVER_ID = 64

        /** Parses a `hello_ok` frame. A missing, blank, non-string or over-long `serverId` is treated as absent. */
        fun parse(r: org.json.JSONObject): HelloInfo {
            val id = (r.opt("serverId") as? String)?.trim()?.takeIf { it.isNotEmpty() && it.length <= MAX_SERVER_ID }
            return HelloInfo(
                serverName = r.optString("serverName", "PC").take(63).ifEmpty { "PC" },
                paired = r.optBoolean("paired"),
                pairingOpen = r.optBoolean("pairingOpen"),
                udpPort = r.optInt("udpPort", Wire.DEFAULT_UDP_PORT),
                serverId = id,
            )
        }
    }
}

class SessionInfo(val sessionId: Long, val keys: Keys.SessionKeys)

/** Blocking TCP control client (PROTOCOL.md sections 5 and 6). Call from Dispatchers.IO only. */
class ControlChannel private constructor(private val socket: Socket) : Closeable {
    val peerAddress: java.net.InetAddress get() = socket.inetAddress

    companion object {
        fun open(host: String, port: Int, timeoutMs: Int = 4000): ControlChannel {
            val s = Socket()
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(host, port), timeoutMs)
            return ControlChannel(s)
        }
    }

    private val out = socket.getOutputStream()
    private val input = socket.getInputStream().buffered()

    fun send(msg: JSONObject) = ControlFrames.write(out, msg)

    /** Reads one frame; a protocol `error` frame becomes a [ProtocolException]. */
    fun read(timeoutMs: Int): JSONObject {
        socket.soTimeout = timeoutMs
        val m = ControlFrames.read(input)
        if (m.optString("t") == "error") throw ProtocolException(m.optString("code", "internal"), m.optString("message", "error from PC"))
        return m
    }

    private fun expect(type: String, timeoutMs: Int): JSONObject {
        val m = read(timeoutMs)
        if (m.optString("t") != type) throw ProtocolException("bad_frame", "expected $type, got ${m.optString("t")}")
        return m
    }

    fun hello(deviceId: String, deviceName: String): HelloInfo {
        send(
            JSONObject().put("t", "hello").put("ver", Wire.VERSION).put("deviceId", deviceId)
                .put("deviceName", deviceName.take(64)).put("caps", JSONArray())
        )
        val r = expect("hello_ok", 5000)
        if (r.optInt("ver") != Wire.VERSION) throw ProtocolException("bad_version", "PC speaks protocol ${r.optInt("ver")}")
        return HelloInfo.parse(r)
    }

    /**
     * Runs the PIN pairing exchange. Returns the 32-byte device secret.
     * Throws [ProtocolException] (bad_pin, rejected_by_user, pairing_closed, rate_limited, auth_failed...).
     */
    fun pair(pin: String, userConfirmTimeoutMs: Int = 90_000): ByteArray {
        val kp = Crypto.generateEcKeyPair()
        val pubC = Crypto.encodePublicKey(kp.public)
        val nonceC = Crypto.randomBytes(16)
        send(
            JSONObject().put("t", "pair_request").put("pub", ControlFrames.b64(pubC)).put("nonce", ControlFrames.b64(nonceC))
        )
        val ch = expect("pair_challenge", 5000)
        val pubS = decode(ch, "pub", 65)
        val nonceS = decode(ch, "nonce", 16)
        val shared = try {
            Crypto.ecdh(kp.private, pubS)
        } catch (e: Exception) {
            throw ProtocolException("auth_failed", "PC sent an invalid public key")
        }
        val confirm = Keys.confirmKey(shared, pin, nonceC, nonceS)
        send(JSONObject().put("t", "pair_proof").put("proof", ControlFrames.b64(Keys.proofClient(confirm, pubC, pubS))))
        val res = expect("pair_result", userConfirmTimeoutMs)
        if (!res.optBoolean("ok")) throw ProtocolException(res.optString("reason", "rejected_by_user"), "pairing refused")
        val proofS = decode(res, "proof", 32)
        if (!Crypto.constantTimeEquals(proofS, Keys.proofServer(confirm, pubC, pubS))) {
            throw ProtocolException("auth_failed", "PC failed to prove it knows the PIN")
        }
        return Keys.deviceSecret(shared, nonceC, nonceS)
    }

    /** Authenticates with a stored device secret and derives fresh per-session keys. */
    fun startSession(deviceSecret: ByteArray): SessionInfo {
        val nonceC = Crypto.randomBytes(16)
        send(JSONObject().put("t", "session_request").put("nonce", ControlFrames.b64(nonceC)))
        val ch = expect("session_challenge", 5000)
        val nonceS = decode(ch, "nonce", 16)
        send(JSONObject().put("t", "session_auth").put("mac", ControlFrames.b64(Keys.macClient(deviceSecret, nonceC, nonceS))))
        val r = expect("session_start", 5000)
        if (!r.optBoolean("ok")) throw ProtocolException(r.optString("reason", "auth_failed"), "session refused")
        val sid = r.getLong("sessionId") and 0xFFFFFFFFL
        if (sid == 0L) throw ProtocolException("bad_frame", "session id 0")
        val macS = decode(r, "mac", 32)
        if (!Crypto.constantTimeEquals(macS, Keys.macServer(deviceSecret, nonceC, nonceS, sid))) {
            throw ProtocolException("auth_failed", "PC failed to authenticate")
        }
        return SessionInfo(sid, Keys.sessionKeys(deviceSecret, nonceC, nonceS, sid))
    }

    fun sendDisconnect(reason: String) {
        try {
            send(JSONObject().put("t", "disconnect").put("reason", reason))
        } catch (_: Exception) {
        }
    }

    private fun decode(m: JSONObject, field: String, size: Int): ByteArray {
        val b = try {
            ControlFrames.unb64(m.getString(field))
        } catch (e: Exception) {
            throw ProtocolException("bad_frame", "bad $field")
        }
        if (b.size != size) throw ProtocolException("bad_frame", "$field must be $size bytes")
        return b
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }
}
