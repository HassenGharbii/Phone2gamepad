package com.phone2gamepad

import com.phone2gamepad.data.SecretStore
import com.phone2gamepad.net.HelloInfo
import com.phone2gamepad.net.PcSecretResolver
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class PcSecretResolverTest {
    private class FakeStore : SecretStore {
        val map = HashMap<String, ByteArray>()
        override suspend fun deviceId() = "dev"
        override suspend fun loadSecret(pcKey: String) = map[pcKey]
        override suspend fun saveSecret(pcKey: String, secret: ByteArray) { map[pcKey] = secret }
        override suspend fun deleteSecret(pcKey: String) { map.remove(pcKey) }
    }

    private fun hello(name: String = "MY-PC", id: String? = null, paired: Boolean = true) =
        HelloInfo(serverName = name, paired = paired, pairingOpen = false, udpPort = 47822, serverId = id)

    private val s1 = ByteArray(32) { 1 }
    private val s2 = ByteArray(32) { 2 }

    @Test fun oldServerWithoutIdKeepsUsingTheName() = runBlocking {
        val st = FakeStore()
        val r = PcSecretResolver(st, hello())
        assertEquals("MY-PC", r.key)
        assertNull(r.load())
        r.saveNew(s1)
        assertArrayEquals(s1, st.map["MY-PC"])
        assertArrayEquals(s1, PcSecretResolver(st, hello()).load())
    }

    @Test fun serverIdKeysTheSecretSeparatelyFromTheName() = runBlocking {
        val st = FakeStore()
        PcSecretResolver(st, hello(id = "abc")).saveNew(s1)
        assertEquals(setOf("srv:abc"), st.map.keys)
    }

    @Test fun legacyNameKeyedSecretIsAdoptedAndRekeyedOnlyAfterSessionSucceeds() = runBlocking {
        val st = FakeStore().apply { map["MY-PC"] = s1 }
        val r = PcSecretResolver(st, hello(id = "abc"))
        assertArrayEquals(s1, r.load())
        assertEquals("MY-PC", r.adoptedLegacyKey)
        // Nothing moved yet: the secret has not been proven valid for this PC.
        assertEquals(setOf("MY-PC"), st.map.keys)
        r.sessionEstablished(s1)
        assertEquals(setOf("srv:abc"), st.map.keys)
        assertArrayEquals(s1, st.map["srv:abc"])
        assertNull(r.adoptedLegacyKey)
        // Next connection uses the id key directly, no pairing needed.
        assertArrayEquals(s1, PcSecretResolver(st, hello(id = "abc")).load())
    }

    @Test fun rejectedAdoptedSecretKeepsLegacyEntryForTheOtherSameNamedPc() = runBlocking {
        val st = FakeStore().apply { map["MY-PC"] = s1 }
        val r = PcSecretResolver(st, hello(id = "other"))
        assertNotNull(r.load())
        r.rejected()
        assertEquals(setOf("MY-PC"), st.map.keys) // still there for the PC it really belongs to
    }

    @Test fun idKeyedSecretWinsOverLegacy() = runBlocking {
        val st = FakeStore().apply { map["MY-PC"] = s1; map["srv:abc"] = s2 }
        val r = PcSecretResolver(st, hello(id = "abc"))
        assertArrayEquals(s2, r.load())
        assertNull(r.adoptedLegacyKey)
    }

    @Test fun twoPcsWithTheSameNameDoNotCollideOnceTheyHaveIds() = runBlocking {
        val st = FakeStore()
        PcSecretResolver(st, hello(id = "A")).saveNew(s1)
        PcSecretResolver(st, hello(id = "B")).saveNew(s2)
        assertArrayEquals(s1, PcSecretResolver(st, hello(id = "A")).load())
        assertArrayEquals(s2, PcSecretResolver(st, hello(id = "B")).load())
    }

    @Test fun pcThatNoLongerKnowsThePhoneDropsStaleSecretButNotTheLegacyOne() = runBlocking {
        val st = FakeStore().apply { map["srv:abc"] = s2; map["MY-PC"] = s1 }
        val r = PcSecretResolver(st, hello(id = "abc", paired = false))
        assertNull(r.load())
        assertEquals(setOf("MY-PC"), st.map.keys)
    }

    @Test fun helloParsingToleratesMissingOrBadServerId() {
        fun parse(extra: (JSONObject) -> Unit) = HelloInfo.parse(
            JSONObject().put("t", "hello_ok").put("serverName", "PC").put("paired", true).put("udpPort", 47822).also(extra)
        )
        assertEquals("id-1", parse { it.put("serverId", "id-1") }.serverId)
        assertEquals("id-1", parse { it.put("serverId", "  id-1 ") }.serverId)
        assertNull(parse { }.serverId)
        assertNull(parse { it.put("serverId", "") }.serverId)
        assertNull(parse { it.put("serverId", "   ") }.serverId)
        assertNull(parse { it.put("serverId", "x".repeat(65)) }.serverId)
        assertEquals(64, parse { it.put("serverId", "x".repeat(64)) }.serverId!!.length)
        assertNull(parse { it.put("serverId", 12345) }.serverId)
    }
}
