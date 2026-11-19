package com.phone2gamepad.protocol

import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

class ProtocolException(val code: String, message: String) : IOException(message)

/** TCP control framing: u32 LE length (1..16384) followed by a UTF-8 JSON object with a string field "t". */
object ControlFrames {
    const val MAX_FRAME = 16384

    fun encode(msg: JSONObject): ByteArray {
        val body = msg.toString().toByteArray(Charsets.UTF_8)
        require(body.size in 1..MAX_FRAME) { "frame too large" }
        return ByteBuffer.allocate(4 + body.size).order(ByteOrder.LITTLE_ENDIAN).putInt(body.size).put(body).array()
    }

    fun write(out: OutputStream, msg: JSONObject) {
        out.write(encode(msg))
        out.flush()
    }

    /** Blocking read of one frame. Throws [ProtocolException] for bad frames and [IOException] at end of stream. */
    fun read(input: InputStream): JSONObject {
        val din = DataInputStream(input)
        val hdr = ByteArray(4)
        din.readFully(hdr)
        val len = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN).int
        if (len < 1 || len > MAX_FRAME) throw ProtocolException("bad_frame", "invalid frame length $len")
        val body = ByteArray(len)
        din.readFully(body)
        val obj = try {
            JSONObject(String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            throw ProtocolException("bad_frame", "invalid JSON")
        }
        if (obj.optString("t").isEmpty()) throw ProtocolException("bad_frame", "missing message type")
        return obj
    }

    fun b64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
    fun unb64(s: String): ByteArray = Base64.getDecoder().decode(s)
}
