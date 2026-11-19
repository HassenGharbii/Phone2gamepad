package com.phone2gamepad.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Wire constants (PROTOCOL.md). */
object Wire {
    const val VERSION = 1
    const val MAGIC0: Byte = 0x50
    const val MAGIC1: Byte = 0x32
    const val TYPE_STATE = 0x01
    const val TYPE_HEARTBEAT = 0x02
    const val TYPE_DISCOVERY_REQUEST = 0x10
    const val TYPE_DISCOVERY_RESPONSE = 0x11
    const val STATE_SIZE = 46
    const val HEARTBEAT_SIZE = 40
    const val TAG_SIZE = 16
    const val NO_ECHO = 0xFFFFFFFFL

    const val DISCOVERY_PORT = 47820
    const val DEFAULT_TCP_PORT = 47821
    const val DEFAULT_UDP_PORT = 47822
}

object Buttons {
    const val A = 1 shl 0
    const val B = 1 shl 1
    const val X = 1 shl 2
    const val Y = 1 shl 3
    const val LB = 1 shl 4
    const val RB = 1 shl 5
    const val BACK = 1 shl 6
    const val START = 1 shl 7
    const val L3 = 1 shl 8
    const val R3 = 1 shl 9
    const val GUIDE = 1 shl 10
    const val MASK = 0x07FF
}

object Dpad {
    const val UP = 1
    const val DOWN = 2
    const val LEFT = 4
    const val RIGHT = 8
}

/** Complete controller state. Sticks are signed 16-bit (positive Y = up), triggers 0..255. */
data class ControllerState(
    val buttons: Int = 0,
    val dpad: Int = 0,
    val lx: Int = 0,
    val ly: Int = 0,
    val rx: Int = 0,
    val ry: Int = 0,
    val lt: Int = 0,
    val rt: Int = 0,
) {
    companion object {
        val NEUTRAL = ControllerState()
    }
}

enum class RejectReason { LENGTH, MAGIC, VERSION, TYPE, SESSION, TAG }

sealed class Decoded {
    data class State(val sessionId: Long, val seq: Long, val timestamp: Long, val state: ControllerState) : Decoded()
    data class Heartbeat(val sessionId: Long, val seq: Long, val timestamp: Long, val echo: Long, val echoDelay: Int) : Decoded()
    data class Rejected(val reason: RejectReason) : Decoded()
}

object Packets {
    private fun buf(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    private fun putHeader(b: ByteBuffer, type: Int, sessionId: Long, seq: Long) {
        b.put(Wire.MAGIC0).put(Wire.MAGIC1).put(Wire.VERSION.toByte()).put(type.toByte())
        b.putInt(sessionId.toInt()).putInt(seq.toInt())
    }

    private fun tag(key: ByteArray, data: ByteArray, len: Int): ByteArray =
        Crypto.hmacSha256(key, data.copyOf(len)).copyOf(Wire.TAG_SIZE)

    private fun seal(b: ByteBuffer, key: ByteArray): ByteArray {
        val body = b.array()
        val bodyLen = body.size - Wire.TAG_SIZE
        System.arraycopy(tag(key, body, bodyLen), 0, body, bodyLen, Wire.TAG_SIZE)
        return body
    }

    fun encodeState(sessionId: Long, seq: Long, timestamp: Long, s: ControllerState, key: ByteArray): ByteArray {
        val b = buf(Wire.STATE_SIZE)
        putHeader(b, Wire.TYPE_STATE, sessionId, seq)
        b.putInt(timestamp.toInt())
        b.putShort((s.buttons and Buttons.MASK).toShort())
        b.put((s.dpad and 0x0F).toByte())
        b.put(0)
        b.putShort(s.lx.coerceIn(-32768, 32767).toShort())
        b.putShort(s.ly.coerceIn(-32768, 32767).toShort())
        b.putShort(s.rx.coerceIn(-32768, 32767).toShort())
        b.putShort(s.ry.coerceIn(-32768, 32767).toShort())
        b.put(s.lt.coerceIn(0, 255).toByte())
        b.put(s.rt.coerceIn(0, 255).toByte())
        return seal(b, key)
    }

    fun encodeHeartbeat(sessionId: Long, seq: Long, timestamp: Long, echo: Long, echoDelay: Int, key: ByteArray): ByteArray {
        val b = buf(Wire.HEARTBEAT_SIZE)
        putHeader(b, Wire.TYPE_HEARTBEAT, sessionId, seq)
        b.putInt(timestamp.toInt())
        b.putInt(echo.toInt())
        b.putShort(echoDelay.coerceIn(0, 0xFFFF).toShort())
        b.putShort(0)
        return seal(b, key)
    }

    /**
     * Validates in the order of PROTOCOL.md section 4 (length, magic, version, session, tag).
     * The source-IP and sequence checks are done by the caller ([SequenceTracker]).
     */
    fun decode(data: ByteArray, key: ByteArray, expectedSession: Long): Decoded {
        if (data.size < 4) return Decoded.Rejected(RejectReason.LENGTH)
        val type = data[3].toInt() and 0xFF
        val expected = when (type) {
            Wire.TYPE_STATE -> Wire.STATE_SIZE
            Wire.TYPE_HEARTBEAT -> Wire.HEARTBEAT_SIZE
            else -> return Decoded.Rejected(RejectReason.TYPE)
        }
        if (data.size != expected) return Decoded.Rejected(RejectReason.LENGTH)
        if (data[0] != Wire.MAGIC0 || data[1] != Wire.MAGIC1) return Decoded.Rejected(RejectReason.MAGIC)
        if ((data[2].toInt() and 0xFF) != Wire.VERSION) return Decoded.Rejected(RejectReason.VERSION)
        val b = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        val session = b.getInt(4).toLong() and 0xFFFFFFFFL
        if (session != expectedSession) return Decoded.Rejected(RejectReason.SESSION)
        val bodyLen = expected - Wire.TAG_SIZE
        val expectedTag = tag(key, data, bodyLen)
        if (!Crypto.constantTimeEquals(expectedTag, data.copyOfRange(bodyLen, expected))) {
            return Decoded.Rejected(RejectReason.TAG)
        }
        val seq = b.getInt(8).toLong() and 0xFFFFFFFFL
        val ts = b.getInt(12).toLong() and 0xFFFFFFFFL
        return if (type == Wire.TYPE_STATE) {
            Decoded.State(
                session, seq, ts,
                ControllerState(
                    buttons = b.getShort(16).toInt() and Buttons.MASK,
                    dpad = b.get(18).toInt() and 0x0F,
                    lx = b.getShort(20).toInt(), ly = b.getShort(22).toInt(),
                    rx = b.getShort(24).toInt(), ry = b.getShort(26).toInt(),
                    lt = b.get(28).toInt() and 0xFF, rt = b.get(29).toInt() and 0xFF,
                ),
            )
        } else {
            Decoded.Heartbeat(session, seq, ts, b.getInt(16).toLong() and 0xFFFFFFFFL, b.getShort(20).toInt() and 0xFFFF)
        }
    }
}

/** Serial-number arithmetic acceptance (wraparound-safe): accept only strictly newer sequence numbers. */
object Sequence {
    fun isNewer(last: Long, seq: Long): Boolean = (((seq - last) and 0xFFFFFFFFL).toInt()) > 0
}

class SequenceTracker(private var last: Long = 0) {
    /** Returns true and records [seq] if it is newer than everything accepted so far. */
    fun accept(seq: Long): Boolean {
        if (!Sequence.isNewer(last, seq)) return false
        last = seq
        return true
    }
}

data class DiscoveryResponse(val nonce: Long, val tcpPort: Int, val udpPort: Int, val pairingOpen: Boolean, val name: String)

object Discovery {
    fun encodeRequest(nonce: Long): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .put(Wire.MAGIC0).put(Wire.MAGIC1).put(Wire.VERSION.toByte()).put(Wire.TYPE_DISCOVERY_REQUEST.toByte())
            .putInt(nonce.toInt()).array()

    /** Returns null for anything that is not a well-formed response. */
    fun parseResponse(data: ByteArray, len: Int = data.size): DiscoveryResponse? {
        if (len < 14) return null
        if (data[0] != Wire.MAGIC0 || data[1] != Wire.MAGIC1) return null
        if ((data[2].toInt() and 0xFF) != Wire.VERSION || (data[3].toInt() and 0xFF) != Wire.TYPE_DISCOVERY_RESPONSE) return null
        val b = ByteBuffer.wrap(data, 0, len).order(ByteOrder.LITTLE_ENDIAN)
        val nonce = b.getInt(4).toLong() and 0xFFFFFFFFL
        val tcp = b.getShort(8).toInt() and 0xFFFF
        val udp = b.getShort(10).toInt() and 0xFFFF
        val flags = data[12].toInt() and 0xFF
        val n = data[13].toInt() and 0xFF
        if (n < 1 || n > 63 || len != 14 + n || tcp == 0 || udp == 0) return null
        return DiscoveryResponse(nonce, tcp, udp, (flags and 1) != 0, String(data, 14, n, Charsets.UTF_8))
    }
}
