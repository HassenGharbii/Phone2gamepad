package com.phone2gamepad

import com.phone2gamepad.data.ControlOverride
import com.phone2gamepad.data.Profile
import com.phone2gamepad.data.ProfileBook
import com.phone2gamepad.data.ProfileCodec
import com.phone2gamepad.data.ProfileCodec.BookResult
import com.phone2gamepad.data.TriggerMode
import com.phone2gamepad.protocol.ResponseCurve
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTest {
    private var n = 0
    private val ids: () -> String = { "id${++n}" }

    private fun custom() = Profile(
        id = "p1", name = "Racing",
        controls = mapOf(
            "A" to ControlOverride(fx = 0.5f, fy = 0.25f, scale = 1.5f, opacity = 0.6f),
            "L3" to ControlOverride(visible = false),
        ),
        globalScale = 1.2f, opacity = 0.7f, leftHanded = true, allowOverlap = true,
        deadZone = 0.2f, sensitivity = 1.4f, curve = ResponseCurve.Cubic, triggerMode = TriggerMode.Digital, stickReturnToCenter = false,
    )

    // ---- book operations ----

    @Test fun defaultBookHasOneActiveStandardProfileWithDefaultTuning() {
        val b = ProfileBook.default()
        assertEquals(1, b.profiles.size)
        assertEquals("Standard", b.active.name)
        assertEquals(0.10f, b.active.deadZone, 0f)
        assertEquals(1f, b.active.sensitivity, 0f)
        assertEquals(0.85f, b.active.opacity, 0f)
        assertEquals(TriggerMode.Analog, b.active.triggerMode)
        assertTrue(b.active.controls.isEmpty())
    }

    @Test fun legacySettingsSeedTheStandardProfile() {
        val b = ProfileBook.default(deadZone = 0.25f, sensitivity = 1.5f, opacity = 0.5f, digitalTriggers = true)
        assertEquals(0.25f, b.active.deadZone, 0f)
        assertEquals(1.5f, b.active.sensitivity, 0f)
        assertEquals(0.5f, b.active.opacity, 0f)
        assertEquals(TriggerMode.Digital, b.active.triggerMode)
    }

    @Test fun createSelectsNewProfileAndMakesNamesUnique() {
        val b = ProfileBook.default().create("Standard", ids)!!.create("standard", ids)!!
        // Uniqueness is case-insensitive; the suffix is appended to the name as typed.
        assertEquals(listOf("Standard", "Standard (2)", "standard (3)"), b.profiles.map { it.name })
        assertEquals("id2", b.activeId)
    }

    @Test fun renameKeepsNamesUniqueAndTrims() {
        var b = ProfileBook.default().create("Two", ids)!!.create("Three", ids)!!
        assertEquals("Two", b.rename("id1", "  Two  ").profiles.first { it.id == "id1" }.name) // own name is not a clash
        assertEquals("Two (2)", b.rename("id2", "  two ").profiles.first { it.id == "id2" }.name.replace("two", "Two"))
        b = b.rename("id1", "")
        assertEquals("Profile", b.profiles.first { it.id == "id1" }.name)
        assertEquals(Profile.MAX_NAME, b.rename("id1", "x".repeat(100)).profiles.first { it.id == "id1" }.name.length)
    }

    @Test fun duplicateCopiesEverythingUnderNewIdAndSelectsIt() {
        val b = ProfileBook.default().replace(custom().copy(id = ProfileBook.STANDARD_ID, name = "Standard"))
        val d = b.duplicate(ProfileBook.STANDARD_ID, ids)!!
        assertEquals(2, d.profiles.size)
        assertEquals("id1", d.activeId)
        val copy = d.active
        assertEquals("Standard copy", copy.name)
        assertEquals(b.active.copy(id = "id1", name = "Standard copy"), copy)
        // Independent: changing the copy leaves the original alone.
        val d2 = d.replace(copy.copy(deadZone = 0.4f))
        assertEquals(0.2f, d2.profiles.first { it.id == ProfileBook.STANDARD_ID }.deadZone, 0f)
    }

    @Test fun deleteNeverRemovesLastProfileAndMovesActive() {
        val one = ProfileBook.default()
        assertEquals(one, one.delete(ProfileBook.STANDARD_ID))
        val b = one.create("B", ids)!!.create("C", ids)!! // active = id2 (C)
        val after = b.delete("id2")
        assertEquals(listOf("Standard", "B"), after.profiles.map { it.name })
        assertEquals("id1", after.activeId)
        val afterFirst = b.select(ProfileBook.STANDARD_ID).delete(ProfileBook.STANDARD_ID)
        assertEquals("id1", afterFirst.activeId)
        assertEquals(b, b.delete("nope"))
    }

    @Test fun selectIgnoresUnknownId() {
        val b = ProfileBook.default()
        assertEquals(b, b.select("missing"))
    }

    @Test fun resetRestoresDefaultsButKeepsIdAndName() {
        val b = ProfileBook.default().replace(custom().copy(id = ProfileBook.STANDARD_ID, name = "Mine")).reset(ProfileBook.STANDARD_ID)
        assertEquals(Profile(id = ProfileBook.STANDARD_ID, name = "Mine"), b.active)
    }

    @Test fun resetLayoutKeepsTuning() {
        val r = custom().resetLayout()
        assertTrue(r.controls.isEmpty())
        assertEquals(1f, r.globalScale, 0f)
        assertFalse(r.leftHanded)
        assertEquals(0.2f, r.deadZone, 0f)
        assertEquals(ResponseCurve.Cubic, r.curve)
    }

    @Test fun profileLimit() {
        var b = ProfileBook.default()
        while (b.profiles.size < ProfileBook.MAX_PROFILES) b = b.create("P", ids)!!
        assertNull(b.create("one more", ids))
        assertNull(b.duplicate(b.activeId, ids))
        assertNull(b.importProfile(custom()))
    }

    @Test fun ofRepairsDuplicateIdsAndBadActive() {
        val b = ProfileBook.of(listOf(Profile("x", "A"), Profile("x", "B"), Profile("", "C")), "gone")
        assertEquals(3, b.profiles.map { it.id }.toSet().size)
        assertEquals("x", b.activeId)
        assertEquals(ProfileBook.default().profiles.single().name, ProfileBook.of(emptyList(), null).active.name)
    }

    // ---- sanitising ----

    @Test fun sanitizeClampsEverything() {
        val p = Profile(
            "a", "  ", globalScale = 9f, opacity = -1f, deadZone = 2f, sensitivity = 0f,
            controls = mapOf("A" to ControlOverride(fx = 2f, fy = -3f, scale = 9f, opacity = 0f), "BOGUS" to ControlOverride(scale = 2f)),
        ).sanitized()
        assertEquals("Profile", p.name)
        assertEquals(Profile.MAX_GLOBAL_SCALE, p.globalScale, 0f)
        assertEquals(Profile.MIN_OPACITY, p.opacity, 0f)
        assertEquals(Profile.MAX_DEADZONE, p.deadZone, 0f)
        assertEquals(Profile.MIN_SENS, p.sensitivity, 0f)
        assertEquals(setOf("A"), p.controls.keys)
        val a = p.controls.getValue("A")
        assertEquals(1f, a.fx!!, 0f)
        assertEquals(0f, a.fy!!, 0f)
        assertEquals(Profile.MAX_SCALE, a.scale, 0f)
        assertEquals(Profile.MIN_OPACITY, a.opacity, 0f)
    }

    @Test fun withOverrideDropsDefaultEntries() {
        val p = Profile("a", "A").withOverride("A", ControlOverride(scale = 1.5f))
        assertEquals(setOf("A"), p.controls.keys)
        assertTrue(p.withOverride("A", ControlOverride()).controls.isEmpty())
    }

    // ---- codec ----

    @Test fun bookRoundTrips() {
        val book = ProfileBook.default().replace(custom().copy(id = ProfileBook.STANDARD_ID, name = "Standard")).create("Second", ids)!!
        val text = ProfileCodec.encodeBook(book)
        val r = ProfileCodec.decodeBook(text) as BookResult.Ok
        assertEquals(book, r.book)
    }

    @Test fun decodeIsLenientWithUnknownAndBadFields() {
        val text = """{"schema":1,"active":"p","future":true,"profiles":[{"id":"p","name":"N","deadZone":"abc","sensitivity":99,
            "curve":"Wobbly","triggerMode":"Digital","extra":[1,2],"controls":{"A":{"fx":0.3,"fy":0.4,"scale":"x"},"ZZZ":{"scale":2},"B":{"fx":0.5,"scale":1.2}}}]}"""
        val b = (ProfileCodec.decodeBook(text) as BookResult.Ok).book
        val p = b.active
        assertEquals(0.10f, p.deadZone, 0f) // unparseable -> default
        assertEquals(Profile.MAX_SENS, p.sensitivity, 0f) // out of range -> clamped
        assertEquals(ResponseCurve.Linear, p.curve) // unknown enum -> default
        assertEquals(TriggerMode.Digital, p.triggerMode)
        assertEquals(setOf("A", "B"), p.controls.keys) // unknown control dropped
        assertEquals(0.3f, p.controls.getValue("A").fx!!, 1e-6f)
        assertNull(p.controls.getValue("B").fx) // half a position is ignored
    }

    @Test fun corruptDocumentsAreReported() {
        assertTrue(ProfileCodec.decodeBook("") is BookResult.Corrupt)
        assertTrue(ProfileCodec.decodeBook("not json {") is BookResult.Corrupt)
        assertTrue(ProfileCodec.decodeBook("""{"profiles":[]}""") is BookResult.Corrupt) // no schema
        assertTrue(ProfileCodec.decodeBook("""{"schema":0,"profiles":[{"name":"x"}]}""") is BookResult.Corrupt)
        assertTrue(ProfileCodec.decodeBook("""{"schema":1}""") is BookResult.Corrupt) // no profiles
        assertTrue(ProfileCodec.decodeBook("""{"schema":1,"profiles":[]}""") is BookResult.Corrupt)
        assertTrue(ProfileCodec.decodeBook("""{"schema":1,"profiles":[1,"x",null]}""") is BookResult.Corrupt)
        assertTrue(ProfileCodec.decodeBook("""{"schema":1,"profiles":[{"name":"x"}""") is BookResult.Corrupt) // truncated
    }

    @Test fun newerSchemaIsNotGuessedAt() {
        val r = ProfileCodec.decodeBook("""{"schema":99,"profiles":[{"name":"x"}]}""")
        assertEquals(BookResult.TooNew(99), r)
    }

    @Test fun olderSchemaIsMigratedStepByStep() {
        // Hypothetical v1 -> v2 -> v3 upgrades, injected so the mechanism is tested before a real v2 exists.
        val migrations: Map<Int, (JSONObject) -> JSONObject> = mapOf(
            1 to { o -> o.also { d -> d.getJSONArray("profiles").getJSONObject(0).put("name", "migrated-1-to-2") } },
            2 to { o -> o.also { d -> d.getJSONArray("profiles").getJSONObject(0).put("deadZone", 0.33) } },
        )
        val r = ProfileCodec.decodeBook(
            """{"schema":1,"active":"a","profiles":[{"id":"a","name":"old"}]}""", schema = 3, migrations = migrations,
        ) as BookResult.Ok
        assertEquals("migrated-1-to-2", r.book.active.name)
        assertEquals(0.33f, r.book.active.deadZone, 1e-6f)
        // A missing migration step is reported rather than skipped.
        assertTrue(ProfileCodec.decodeBook("""{"schema":1,"profiles":[{"name":"x"}]}""", schema = 2, migrations = emptyMap()) is BookResult.Corrupt)
    }

    // ---- export / import ----

    @Test fun exportImportRoundTripWithFreshIdAndUniqueName() {
        val text = ProfileCodec.encodeExport(custom())
        val decoded = ProfileCodec.decodeExport(text)
        assertNotNull(decoded)
        assertEquals(custom(), decoded)
        val book = ProfileBook.default().replace(custom().copy(id = ProfileBook.STANDARD_ID, name = "Racing"))
        val imported = book.importProfile(decoded!!)!!
        assertEquals(2, imported.profiles.size)
        assertEquals("Racing (2)", imported.active.name)
        assertNotEquals("p1", imported.active.id) // ids from files are never trusted
        assertEquals(custom().copy(id = imported.active.id, name = "Racing (2)"), imported.active)
    }

    @Test fun importRejectsForeignOrDamagedFiles() {
        assertNull(ProfileCodec.decodeExport(""))
        assertNull(ProfileCodec.decodeExport("{}"))
        assertNull(ProfileCodec.decodeExport("""{"type":"other","schema":1,"profile":{}}"""))
        assertNull(ProfileCodec.decodeExport("""{"type":"phone2gamepad-profile","schema":99,"profile":{"name":"x"}}"""))
        assertNull(ProfileCodec.decodeExport("""{"type":"phone2gamepad-profile","schema":1}"""))
        assertNull(ProfileCodec.decodeExport("x".repeat(ProfileCodec.MAX_IMPORT_BYTES + 1)))
        // Hostile values are clamped, not trusted.
        val longName = "n".repeat(500)
        val p = ProfileCodec.decodeExport(
            """{"type":"phone2gamepad-profile","schema":1,"profile":{"name":"$longName","globalScale":1e9,"controls":{"A":{"scale":-5}}}}"""
        )!!
        assertEquals(Profile.MAX_NAME, p.name.length)
        assertEquals(Profile.MAX_GLOBAL_SCALE, p.globalScale, 0f)
        assertEquals(Profile.MIN_SCALE, p.controls.getValue("A").scale, 0f)
    }
}
