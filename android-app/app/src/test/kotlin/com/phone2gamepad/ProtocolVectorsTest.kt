package com.phone2gamepad

import com.phone2gamepad.protocol.Decoded
import com.phone2gamepad.protocol.Keys
import com.phone2gamepad.protocol.Packets
import com.phone2gamepad.protocol.RejectReason
import com.phone2gamepad.protocol.Sequence
import com.phone2gamepad.protocol.SequenceTracker
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolVectorsTest {
    private val v: JSONObject = JSONObject(
        javaClass.classLoader!!.getResourceAsStream("vectors.json")!!.readBytes().toString(Charsets.UTF_8)
    )

    private fun hex(s: String): ByteArray = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private fun hexOf(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private val session = v.getJSONObject("session")
    private val sid = session.getLong("sessionId")
    private val kc2s = hex(session.getString("k_c2s"))
    private val ks2c = hex(session.getString("k_s2c"))

    private fun JSONArray.objs() = (0 until length()).map { getJSONObject(it) }

    @Test
    fun sessionKeyDerivationMatchesReference() {
        val dev = hex(session.getString("deviceSecret"))
        val nc = hex(session.getString("nonceC"))
        val ns = hex(session.getString("nonceS"))
        val keys = Keys.sessionKeys(dev, nc, ns, sid)
        assertEquals(session.getString("k_c2s"), hexOf(keys.c2s))
        assertEquals(session.getString("k_s2c"), hexOf(keys.s2c))
        assertEquals(session.getString("macC"), hexOf(Keys.macClient(dev, nc, ns)))
        assertEquals(session.getString("macS"), hexOf(Keys.macServer(dev, nc, ns, sid)))
    }

    @Test
    fun pairingDerivationMatchesReference() {
        val p = v.getJSONObject("pairing")
        val shared = hex(p.getString("shared"))
        val nc = hex(p.getString("nonceC"))
        val ns = hex(p.getString("nonceS"))
        val pubC = hex(p.getString("pubC"))
        val pubS = hex(p.getString("pubS"))
        val ck = Keys.confirmKey(shared, p.getString("pin"), nc, ns)
        assertEquals(p.getString("confirmKey"), hexOf(ck))
        assertEquals(p.getString("proofC"), hexOf(Keys.proofClient(ck, pubC, pubS)))
        assertEquals(p.getString("proofS"), hexOf(Keys.proofServer(ck, pubC, pubS)))
        assertEquals(p.getString("deviceSecret"), hexOf(Keys.deviceSecret(shared, nc, ns)))
    }

    @Test
    fun stateVectorsDecodeAndReencodeByteForByte() {
        val list = v.getJSONArray("state").objs()
        assertTrue(list.isNotEmpty())
        for (e in list) {
            val bytes = hex(e.getString("hex"))
            val d = Packets.decode(bytes, kc2s, sid)
            assertTrue("${e.getString("name")} -> $d", d is Decoded.State)
            d as Decoded.State
            val x = e.getJSONObject("decoded")
            val st = d.state
            assertEquals(e.getString("name"), x.getLong("seq"), d.seq)
            assertEquals(x.getLong("ts"), d.timestamp)
            assertEquals(x.getInt("btn"), st.buttons)
            assertEquals(x.getInt("dpad"), st.dpad)
            assertEquals(x.getInt("lx"), st.lx)
            assertEquals(x.getInt("ly"), st.ly)
            assertEquals(x.getInt("rx"), st.rx)
            assertEquals(x.getInt("ry"), st.ry)
            assertEquals(x.getInt("lt"), st.lt)
            assertEquals(x.getInt("rt"), st.rt)
            assertArrayEquals(e.getString("name"), bytes, Packets.encodeState(sid, d.seq, d.timestamp, st, kc2s))
        }
    }

    @Test
    fun heartbeatVectorsDecodeAndReencodeByteForByte() {
        for (e in v.getJSONArray("heartbeat").objs()) {
            val bytes = hex(e.getString("hex"))
            val d = Packets.decode(bytes, kc2s, sid)
            assertTrue(d is Decoded.Heartbeat)
            d as Decoded.Heartbeat
            val x = e.getJSONObject("decoded")
            assertEquals(x.getLong("seq"), d.seq)
            assertEquals(x.getLong("ts"), d.timestamp)
            assertEquals(x.getLong("echo"), d.echo)
            assertEquals(x.getInt("delay"), d.echoDelay)
            assertArrayEquals(bytes, Packets.encodeHeartbeat(sid, d.seq, d.timestamp, d.echo, d.echoDelay, kc2s))
        }
    }

    @Test
    fun invalidVectorsAreRejectedWithExpectedReason() {
        val map = mapOf(
            "length" to RejectReason.LENGTH, "magic" to RejectReason.MAGIC, "version" to RejectReason.VERSION,
            "tag" to RejectReason.TAG, "session" to RejectReason.SESSION,
        )
        for (e in v.getJSONArray("invalid").objs()) {
            val d = Packets.decode(hex(e.getString("hex")), kc2s, sid)
            assertEquals(e.getString("name"), Decoded.Rejected(map.getValue(e.getString("reason"))), d)
        }
    }

    @Test
    fun sequenceVectors() {
        val arr = v.getJSONArray("sequence")
        for (i in 0 until arr.length()) {
            val t = arr.getJSONArray(i)
            assertEquals("${t.getLong(0)} -> ${t.getLong(1)}", t.getBoolean(2), Sequence.isNewer(t.getLong(0), t.getLong(1)))
        }
    }

    @Test
    fun trackerDropsDuplicatesAndOldPacketsButAcceptsWraparound() {
        val t = SequenceTracker()
        assertTrue(t.accept(1))
        assertFalse(t.accept(1))
        assertTrue(t.accept(5))
        assertFalse(t.accept(3))
        val w = SequenceTracker(0xFFFFFFF0L)
        assertTrue(w.accept(0xFFFFFFFFL))
        assertTrue(w.accept(0))
        assertTrue(w.accept(1))
        assertFalse(w.accept(0xFFFFFFFFL))
        assertFalse(w.accept(0))
    }

    @Test
    fun unauthenticatedGarbageNeverDecodes() {
        val rnd = java.util.Random(1)
        repeat(200) {
            val b = ByteArray(if (it % 2 == 0) 46 else 40).also(rnd::nextBytes)
            assertTrue(Packets.decode(b, kc2s, sid) is Decoded.Rejected)
        }
    }
}
