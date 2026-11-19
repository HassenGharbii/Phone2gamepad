package com.phone2gamepad

import com.phone2gamepad.data.ColorSlot
import com.phone2gamepad.data.LabelStyle
import com.phone2gamepad.data.Profile
import com.phone2gamepad.data.ProfileBook
import com.phone2gamepad.data.ProfileCodec
import com.phone2gamepad.data.ShapeGeometry
import com.phone2gamepad.data.Skin
import com.phone2gamepad.data.SkinCodec
import com.phone2gamepad.data.SkinCodec.ImportResult
import com.phone2gamepad.data.SkinColor
import com.phone2gamepad.data.SkinLibrary
import com.phone2gamepad.data.SkinShape
import com.phone2gamepad.data.Skins
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SkinTest {
    private val ids = run { var n = 0; { "id${n++}" } }
    private fun custom(name: String = "Mine") = Skins.TACTICAL.copy(id = "x", name = name, author = "me")

    private fun exportJson(s: Skin = custom()) = JSONObject(SkinCodec.encodeExport(s))
    private fun reject(text: String): String = (SkinCodec.decodeImport(text) as ImportResult.Rejected).reason

    // ---- bundled skins ----

    @Test fun eightBundledSkinsWithUniqueIdsAndNames() {
        assertEquals(8, Skins.BUNDLED.size)
        assertEquals(8, Skins.BUNDLED.map { it.id }.toSet().size)
        assertEquals(8, Skins.BUNDLED.map { it.name.lowercase() }.toSet().size)
        assertEquals("Classic", Skins.BUNDLED.first().name)
        assertEquals(Skins.CLASSIC_ID, Skins.BUNDLED.first().id)
    }

    @Test fun bundledSkinsAreSanitizedFixedPoints() {
        for (s in Skins.BUNDLED) assertEquals(s.name, s, s.sanitized())
    }

    @Test fun bundledTextHasNoTrademarksOrLinks() {
        val banned = listOf("valorant", "riot", "nfs", "need for speed", "black ops", "call of duty", "activision", "ea ", "xbox", "playstation")
        for (s in Skins.BUNDLED) {
            val text = (s.name + " " + s.author + " " + s.description + " " + s.id).lowercase()
            for (b in banned) assertFalse("${s.name} mentions '$b'", b in text)
            assertFalse("://" in text)
        }
    }

    @Test fun bundledLabelsAreReadableOnTheirButtons() {
        for (s in Skins.BUNDLED) {
            val label = s.color(ColorSlot.LabelText)
            for (slot in listOf(ColorSlot.ButtonIdle, ColorSlot.Bumper, ColorSlot.Trigger)) {
                val ratio = SkinColor.contrast(label, s.color(slot))
                assertTrue("${s.name}: label vs ${slot.key} contrast $ratio < 4.5", ratio >= 4.5)
            }
            for (slot in listOf(ColorSlot.FaceA, ColorSlot.FaceB, ColorSlot.FaceX, ColorSlot.FaceY)) {
                val ratio = SkinColor.contrast(label, SkinColor.faceIdleFill(s, slot))
                assertTrue("${s.name}: label vs idle ${slot.key} contrast $ratio < 3", ratio >= 3.0)
            }
        }
    }

    @Test fun bundledSkinsKeepControlsVisibleAndStatesDistinct() {
        for (s in Skins.BUNDLED) {
            val bg = s.color(ColorSlot.Background)
            assertTrue("${s.name}: outline vs background", SkinColor.contrast(s.color(ColorSlot.Outline), bg) >= 1.5)
            assertTrue("${s.name}: stick ring vs background", SkinColor.contrast(s.color(ColorSlot.StickRing), bg) >= 1.5)
            assertTrue("${s.name}: pressed vs idle", SkinColor.contrast(s.color(ColorSlot.ButtonPressed), s.color(ColorSlot.ButtonIdle)) >= 1.3)
            assertTrue("${s.name}: accent vs stick knob", SkinColor.contrast(s.color(ColorSlot.Accent), s.color(ColorSlot.StickKnob)) >= 1.2)
            for (slot in listOf(ColorSlot.FaceA, ColorSlot.FaceB, ColorSlot.FaceX, ColorSlot.FaceY))
                assertTrue("${s.name}: ${slot.key} vs background", SkinColor.contrast(s.color(slot), bg) >= 1.5)
        }
    }

    @Test fun highContrastSkinMeetsWcagAaa() {
        val s = Skins.HIGH_CONTRAST
        assertTrue(SkinColor.contrast(s.color(ColorSlot.LabelText), s.color(ColorSlot.ButtonIdle)) >= 7.0)
        assertTrue(SkinColor.contrast(s.color(ColorSlot.Outline), s.color(ColorSlot.Background)) >= 7.0)
        assertTrue(s.outlineWidth >= 3f)
    }

    @Test fun contrastMathMatchesKnownValues() {
        assertEquals(21.0, SkinColor.contrast(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 1e-9)
        assertEquals(1.0, SkinColor.contrast(0xFF123456.toInt(), 0xFF123456.toInt()), 1e-9)
        assertEquals(0xFFFFFFFF.toInt(), SkinColor.readableOn(0xFF000000.toInt()))
        assertEquals(0xFF000000.toInt(), SkinColor.readableOn(0xFFFFFF00.toInt()))
        assertEquals(0xFF7F7F7F.toInt(), SkinColor.blend(0xFFFFFFFF.toInt(), 0xFF000000.toInt(), 0.5f))
    }

    // ---- shapes ----

    @Test fun polygonShapesStayInsideTheirBoxAndCircleIsNative() {
        assertNull(ShapeGeometry.polygon(SkinShape.Circle, 0f, 0f, 10f, 10f))
        assertNull(ShapeGeometry.polygon(SkinShape.RoundedSquare, 0f, 0f, 10f, 10f))
        val hex = ShapeGeometry.polygon(SkinShape.Hexagon, 10f, 20f, 110f, 90f)!!
        val ang = ShapeGeometry.polygon(SkinShape.Angular, 10f, 20f, 110f, 90f)!!
        assertEquals(6, hex.size)
        assertEquals(8, ang.size)
        for ((x, y) in hex + ang) {
            assertTrue(x in 10f..110f)
            assertTrue(y in 20f..90f)
        }
    }

    // ---- sanitising / clamping ----

    @Test fun sanitizeClampsAndFillsDefaults() {
        val s = Skin(id = "i".repeat(200), name = "  ", colors = emptyMap(), outlineWidth = 99f, description = "d".repeat(999)).sanitized()
        assertEquals(Skin.MAX_ID, s.id.length)
        assertEquals("Skin", s.name)
        assertEquals(Skin.MAX_OUTLINE, s.outlineWidth, 0f)
        assertEquals(Skin.MAX_DESCRIPTION, s.description.length)
        assertEquals(ColorSlot.entries.size, s.colors.size)
        assertEquals(2f, Skin(id = "a", name = "b", colors = emptyMap(), outlineWidth = Float.NaN).sanitized().outlineWidth, 0f)
        assertEquals(0f, Skin(id = "a", name = "b", colors = emptyMap(), outlineWidth = -3f).sanitized().outlineWidth, 0f)
        assertTrue(s.colors.values.all { (it ushr 24) == 0xFF })
    }

    @Test fun hexColorParsingIsStrict() {
        assertEquals(0xFF0A0B0C.toInt(), SkinColor.parseHex("#0a0B0c"))
        for (bad in listOf("", "#", "0A0B0C", "#0A0B0", "#0A0B0CD", "#GGGGGG", "red", "#0A0B0C ", "rgb(1,2,3)", "#-0A0B0")) assertNull(bad, SkinColor.parseHex(bad))
        assertEquals("#0A0B0C", SkinColor.toHex(0xFF0A0B0C.toInt()))
    }

    // ---- export / import ----

    @Test fun exportImportRoundTripIsLossless() {
        val s = custom("Round trip").copy(outlineWidth = 3.5f, labelStyle = LabelStyle.Mono, faceShape = SkinShape.Hexagon, description = "hello")
        val r = SkinCodec.decodeImport(SkinCodec.encodeExport(s)) as ImportResult.Ok
        assertEquals(s, r.skin)
    }

    @Test fun everyBundledSkinSurvivesARoundTrip() {
        for (s in Skins.BUNDLED) assertEquals(s, (SkinCodec.decodeImport(SkinCodec.encodeExport(s)) as ImportResult.Ok).skin)
    }

    @Test fun exportedFilesAreSmallerThanTheImportLimit() {
        for (s in Skins.BUNDLED) assertTrue(SkinCodec.encodeExport(s).toByteArray().size < 4000)
    }

    @Test fun outOfRangeOutlineIsClampedNotRejected() {
        val j = exportJson(); j.getJSONObject("skin").put("outlineWidth", 1e9)
        assertEquals(Skin.MAX_OUTLINE, (SkinCodec.decodeImport(j.toString()) as ImportResult.Ok).skin.outlineWidth, 0f)
        j.getJSONObject("skin").put("outlineWidth", -50)
        assertEquals(0f, (SkinCodec.decodeImport(j.toString()) as ImportResult.Ok).skin.outlineWidth, 0f)
    }

    @Test fun malformedFilesAreRejectedWithAMessageAndNeverThrow() {
        val bad = listOf(
            "", "   ", "not json", "{", "[]", "null", "123", "\"str\"", "{}", """{"type":"phone2gamepad-skin"}""",
            """{"type":"other","schema":1,"skin":{}}""", """{"type":"phone2gamepad-skin","schema":1}""",
            """{"type":"phone2gamepad-skin","schema":"1","skin":{}}""", """{"type":"phone2gamepad-skin","schema":0,"skin":{}}""",
            """{"type":"phone2gamepad-skin","schema":1,"skin":[]}""", """{"type":"phone2gamepad-skin","schema":1,"skin":{}}""",
            "\u0000\u0001\u0002", "{\"type\":\"phone2gamepad-skin\",\"schema\":1,\"skin\":{\"name\":",
        )
        for (t in bad) assertTrue("'$t'", SkinCodec.decodeImport(t) is ImportResult.Rejected)
        for (t in bad) assertTrue(reject(t).isNotBlank())
    }

    @Test fun newerSchemaIsRejectedClearly() {
        val j = exportJson().put("schema", 99)
        assertTrue("newer" in reject(j.toString()))
    }

    @Test fun oversizedFilesAreRejected() {
        val j = exportJson()
        j.getJSONObject("skin").put("description", "x".repeat(SkinCodec.MAX_IMPORT_BYTES))
        assertTrue("too large" in reject(j.toString()))
        assertTrue("too large" in reject(" ".repeat(SkinCodec.MAX_IMPORT_BYTES + 1) + SkinCodec.encodeExport(custom())))
        // multi-byte characters count as bytes, not characters
        assertTrue("too large" in reject("é".repeat(SkinCodec.MAX_IMPORT_BYTES / 2 + 1)))
    }

    @Test fun overlongFieldsAreRejectedNotSilentlyTruncated() {
        for (field in listOf("name" to Skin.MAX_NAME + 1, "author" to Skin.MAX_NAME + 1, "description" to Skin.MAX_DESCRIPTION + 1, "id" to Skin.MAX_ID + 1)) {
            val j = exportJson(); j.getJSONObject("skin").put(field.first, "a".repeat(field.second))
            assertTrue(field.first, "too long" in reject(j.toString()))
        }
    }

    @Test fun unknownFieldsAreRejected() {
        val rootExtra = exportJson().put("script", "evil()")
        assertTrue("Unknown field" in reject(rootExtra.toString()))
        val skinExtra = exportJson().also { it.getJSONObject("skin").put("image", "abc") }
        assertTrue("Unknown field" in reject(skinExtra.toString()))
        val colorExtra = exportJson().also { it.getJSONObject("skin").getJSONObject("colors").put("glow", "#FFFFFF") }
        assertTrue("Unknown colour" in reject(colorExtra.toString()))
    }

    @Test fun badColoursAreRejected() {
        for (v in listOf("red", "#12345", "#1234567", "#GGGGGG", "", "javascript:1", "0xFFFFFF", "#FFFFFFFF")) {
            val j = exportJson(); j.getJSONObject("skin").getJSONObject("colors").put("accent", v)
            assertTrue(v, "#RRGGBB" in reject(j.toString()))
        }
        val num = exportJson().also { it.getJSONObject("skin").getJSONObject("colors").put("accent", 16777215) }
        assertTrue("#RRGGBB" in reject(num.toString()))
        val missing = exportJson().also { it.getJSONObject("skin").getJSONObject("colors").remove("accent") }
        assertTrue("Missing colour" in reject(missing.toString()))
        val noColors = exportJson().also { it.getJSONObject("skin").remove("colors") }
        assertTrue("colors" in reject(noColors.toString()))
    }

    @Test fun linksAndExternalReferencesAreRejected() {
        for (v in listOf("http://evil.example/x.png", "https://a.b", "see www.example.com", "data:image/png;base64,AAAA", "javascript:alert(1)", "file:///sdcard/x", "content://media/1", "ftp://x")) {
            for (field in listOf("name", "author", "description")) {
                val j = exportJson(); j.getJSONObject("skin").put(field, v)
                assertTrue("$field=$v", "links" in reject(j.toString()))
            }
        }
    }

    @Test fun wrongTypesAndBadEnumsAreRejected() {
        fun mutate(f: (JSONObject) -> Unit): String { val j = exportJson(); f(j.getJSONObject("skin")); return j.toString() }
        assertTrue(SkinCodec.decodeImport(mutate { it.put("name", 5) }) is ImportResult.Rejected)
        assertTrue(SkinCodec.decodeImport(mutate { it.put("name", JSONObject()) }) is ImportResult.Rejected)
        assertTrue(SkinCodec.decodeImport(mutate { it.put("name", "   ") }) is ImportResult.Rejected)
        assertTrue(SkinCodec.decodeImport(mutate { it.put("name", "a\u0000b") }) is ImportResult.Rejected)
        assertTrue(SkinCodec.decodeImport(mutate { it.put("name", "a\nb") }) is ImportResult.Rejected)
        assertTrue(SkinCodec.decodeImport(mutate { it.put("faceShape", "Star") }) is ImportResult.Rejected)
        assertTrue(SkinCodec.decodeImport(mutate { it.put("faceShape", 3) }) is ImportResult.Rejected)
        assertTrue(SkinCodec.decodeImport(mutate { it.put("labelStyle", "circle") }) is ImportResult.Rejected)
        assertTrue(SkinCodec.decodeImport(mutate { it.remove("padShape") }) is ImportResult.Rejected)
        assertTrue(SkinCodec.decodeImport(mutate { it.put("outlineWidth", "thick") }) is ImportResult.Rejected)
        assertTrue(SkinCodec.decodeImport(mutate { it.put("outlineWidth", JSONObject.NULL) }) is ImportResult.Rejected)
        assertTrue(SkinCodec.decodeImport(mutate { it.put("colors", "none") }) is ImportResult.Rejected)
    }

    @Test fun deeplyNestedInputIsRejectedWithoutOverflowingTheStack() {
        val deep = "[".repeat(5000) + "]".repeat(5000)
        assertTrue("deeply" in reject(deep))
        assertTrue("deeply" in reject("""{"type":"phone2gamepad-skin","schema":1,"skin":""" + deep + "}"))
        // brackets inside strings do not count
        val j = exportJson(); j.getJSONObject("skin").put("description", "[[[[[[[[[[[[")
        assertTrue(SkinCodec.decodeImport(j.toString()) is ImportResult.Ok)
    }

    @Test fun importedIdIsNotTrustedAndNameIsMadeUnique() {
        val j = exportJson(custom("Tactical")).also { it.getJSONObject("skin").put("id", Skins.CLASSIC_ID) }
        val skin = (SkinCodec.decodeImport(j.toString()) as ImportResult.Ok).skin
        val (lib, added) = SkinLibrary.empty().add(skin, ids)!!
        assertNotEquals(Skins.CLASSIC_ID, added.id)
        assertEquals("id0", added.id)
        assertEquals("Tactical (2)", added.name) // clashes with the bundled "Tactical"
        assertSame(Skins.CLASSIC, lib.resolve(Skins.CLASSIC_ID))
    }

    // ---- library ----

    @Test fun bundledSkinsCannotBeReplacedOrDeleted() {
        val lib = SkinLibrary.empty()
        assertSame(lib, lib.delete(Skins.CLASSIC_ID))
        assertSame(lib, lib.replace(Skins.CLASSIC.copy(name = "Hacked")))
        assertEquals("Classic", lib.resolve(Skins.CLASSIC_ID).name)
        assertTrue(lib.isBundled(Skins.TACTICAL.id))
    }

    @Test fun duplicateOfBundledBecomesEditableCustomCopy() {
        val (lib, copy) = SkinLibrary.empty().duplicate(Skins.TACTICAL.id, ids)!!
        assertEquals("Tactical copy", copy.name)
        assertFalse(lib.isBundled(copy.id))
        assertEquals("", copy.author) // not attributed to the bundled author
        assertEquals(Skins.TACTICAL.colors, copy.colors)
        val edited = copy.withColor(ColorSlot.Accent, 0xFF123456.toInt()).copy(name = "Renamed")
        val lib2 = lib.replace(edited)
        assertEquals("Renamed", lib2.find(copy.id)!!.name)
        assertEquals(0xFF123456.toInt(), lib2.find(copy.id)!!.color(ColorSlot.Accent))
        assertEquals(copy.id, lib2.delete("nope").custom.single().id)
        assertTrue(lib2.delete(copy.id).custom.isEmpty())
        assertNull(SkinLibrary.empty().duplicate("missing"))
    }

    @Test fun customSkinNamesStayUniqueAndRenameClashesAreFixed() {
        var lib = SkinLibrary.empty()
        lib = lib.add(custom("Mine"), ids)!!.first
        lib = lib.add(custom("mine"), ids)!!.first
        assertEquals(listOf("Mine", "mine (2)"), lib.custom.map { it.name })
        val renamed = lib.replace(lib.custom[1].copy(name = "Tactical"))
        assertEquals("Tactical (2)", renamed.custom[1].name)
    }

    @Test fun customSkinCountIsLimited() {
        var lib = SkinLibrary.empty()
        repeat(SkinLibrary.MAX_CUSTOM) { lib = lib.add(custom("S$it"))!!.first }
        assertEquals(SkinLibrary.MAX_CUSTOM, lib.custom.size)
        assertNull(lib.add(custom("one more")))
        assertNull(lib.duplicate(Skins.CLASSIC_ID))
        assertEquals(SkinLibrary.MAX_CUSTOM, SkinLibrary.of(List(100) { custom("n$it").copy(id = "i$it") }).custom.size)
    }

    @Test fun libraryOfRepairsDuplicateAndBundledIds() {
        val lib = SkinLibrary.of(listOf(custom("A").copy(id = "dup"), custom("B").copy(id = "dup"), custom("C").copy(id = Skins.CLASSIC_ID), custom("D").copy(id = "")))
        val idsOut = lib.custom.map { it.id }
        assertEquals(4, idsOut.toSet().size)
        assertTrue(idsOut.none { it in Skins.BUNDLED_IDS || it.isBlank() })
    }

    @Test fun resolveFallsBackToClassic() {
        val lib = SkinLibrary.empty()
        assertSame(Skins.CLASSIC, lib.resolve("deleted-skin"))
        assertSame(Skins.CLASSIC, lib.resolve(null))
        assertSame(Skins.CLASSIC, lib.resolve(""))
    }

    // ---- stored library: schema, corruption, partial recovery ----

    @Test fun libraryStorageRoundTrips() {
        val lib = SkinLibrary.empty().add(custom("One"), ids)!!.first.add(custom("Two").copy(outlineWidth = 4f), ids)!!.first
        val r = SkinCodec.decodeLibrary(SkinCodec.encodeLibrary(lib)) as SkinCodec.LibraryResult.Ok
        assertEquals(lib, r.library)
        assertEquals(SkinCodec.SCHEMA, JSONObject(SkinCodec.encodeLibrary(lib)).getInt("schema"))
    }

    @Test fun corruptLibraryStorageIsDetected() {
        for (t in listOf("", "garbage", "{", "[]", """{"skins":[]}""", """{"schema":0,"skins":[]}""", """{"schema":1}""", "[".repeat(100)))
            assertTrue("'$t'", SkinCodec.decodeLibrary(t) is SkinCodec.LibraryResult.Corrupt)
        assertEquals(SkinCodec.LibraryResult.TooNew(7), SkinCodec.decodeLibrary("""{"schema":7,"skins":[]}"""))
    }

    @Test fun invalidSkinsInStorageAreDroppedAndTheRestKept() {
        val good = SkinCodec.encodeSkin(custom("Good"))
        val bad = SkinCodec.encodeSkin(custom("Bad")).also { it.getJSONObject("colors").put("accent", "nope") }
        val text = """{"schema":1,"skins":[$bad,$good,7,"x",null]}"""
        val lib = (SkinCodec.decodeLibrary(text) as SkinCodec.LibraryResult.Ok).library
        assertEquals(listOf("Good"), lib.custom.map { it.name })
    }

    @Test fun libraryMigrationMechanismWorks() {
        val migrations: Map<Int, (JSONObject) -> JSONObject> = mapOf(
            1 to { o -> o.also { d -> d.getJSONArray("skins").getJSONObject(0).put("name", "migrated") } },
        )
        val text = """{"schema":1,"skins":[${SkinCodec.encodeSkin(custom("old"))}]}"""
        val r = SkinCodec.decodeLibrary(text, schema = 2, migrations = migrations) as SkinCodec.LibraryResult.Ok
        assertEquals("migrated", r.library.custom.single().name)
        assertTrue(SkinCodec.decodeLibrary(text, schema = 2, migrations = emptyMap()) is SkinCodec.LibraryResult.Corrupt)
    }

    // ---- per-profile selection and profile migration ----

    @Test fun profilesDefaultToClassic() {
        assertEquals(Skins.CLASSIC_ID, Profile(id = "a", name = "A").skinId)
        assertEquals(Skins.CLASSIC_ID, ProfileBook.default().active.skinId)
    }

    @Test fun oldSchemaOneProfilesMigrateToClassic() {
        val v1 = """{"schema":1,"active":"b","profiles":[{"id":"a","name":"A","deadZone":0.2},{"id":"b","name":"B","leftHanded":true}]}"""
        val r = ProfileCodec.decodeBook(v1) as ProfileCodec.BookResult.Ok
        assertEquals(listOf(Skins.CLASSIC_ID, Skins.CLASSIC_ID), r.book.profiles.map { it.skinId })
        assertEquals(0.2f, r.book.profiles[0].deadZone, 1e-6f) // everything else untouched
        assertTrue(r.book.profiles[1].leftHanded)
        // re-saving writes schema 2 and keeps loading
        val text = ProfileCodec.encodeBook(r.book)
        assertEquals(ProfileCodec.SCHEMA, JSONObject(text).getInt("schema"))
        assertEquals(r.book, (ProfileCodec.decodeBook(text) as ProfileCodec.BookResult.Ok).book)
    }

    @Test fun migrationKeepsAnExistingSkinField() {
        val v1 = """{"schema":1,"profiles":[{"id":"a","name":"A","skin":"tactical"}]}"""
        assertEquals("tactical", (ProfileCodec.decodeBook(v1) as ProfileCodec.BookResult.Ok).book.active.skinId)
    }

    @Test fun profileSkinSelectionPersistsAndIsPerProfile() {
        var b = ProfileBook.default().create("Two", ids)!!
        val standard = b.profiles.first { it.id == ProfileBook.STANDARD_ID }
        b = b.replace(standard.copy(skinId = "tactical"))
        val two = b.profiles.first { it.id == "id0" }
        b = b.replace(two.copy(skinId = "light"))
        val r = (ProfileCodec.decodeBook(ProfileCodec.encodeBook(b)) as ProfileCodec.BookResult.Ok).book
        assertEquals("tactical", r.profiles.first { it.id == ProfileBook.STANDARD_ID }.skinId)
        assertEquals("light", r.profiles.first { it.id == "id0" }.skinId)
    }

    @Test fun skinSurvivesResetDuplicateAndLayoutResetButNotUnknownIds() {
        val p = Profile(id = "a", name = "A", skinId = "tactical", deadZone = 0.3f)
        assertEquals("tactical", p.resetToDefault().skinId)
        assertEquals(0.1f, p.resetToDefault().deadZone, 1e-6f)
        assertEquals("tactical", p.resetLayout().skinId)
        val b = ProfileBook.of(listOf(p), "a").duplicate("a", ids)!!
        assertEquals(listOf("tactical", "tactical"), b.profiles.map { it.skinId })
        assertEquals(Skins.CLASSIC_ID, Profile(id = "a", name = "A", skinId = "  ").sanitized().skinId.trim().ifEmpty { Skins.CLASSIC_ID })
        assertEquals(Skins.CLASSIC_ID, Profile(id = "a", name = "A", skinId = "").sanitized().skinId)
        assertEquals(Skin.MAX_ID, Profile(id = "a", name = "A", skinId = "z".repeat(500)).sanitized().skinId.length)
    }

    @Test fun deletingASkinSendsItsProfilesBackToClassic() {
        val b = ProfileBook.of(listOf(Profile("a", "A", skinId = "gone"), Profile("b", "B", skinId = "tactical"), Profile("c", "C")), "a")
        val c = b.clearSkin("gone")
        assertEquals(listOf(Skins.CLASSIC_ID, "tactical", Skins.CLASSIC_ID), c.profiles.map { it.skinId })
        assertSame(c, c.clearSkin("gone")) // nothing left to change
    }

    @Test fun profileExportCarriesTheSkinIdAndOldExportsStillImport() {
        val p = Profile("x", "P", skinId = "ops-black")
        assertEquals("ops-black", ProfileCodec.decodeExport(ProfileCodec.encodeExport(p))!!.skinId)
        val v1 = """{"type":"phone2gamepad-profile","schema":1,"profile":{"name":"old","deadZone":0.2}}"""
        assertEquals(Skins.CLASSIC_ID, ProfileCodec.decodeExport(v1)!!.skinId)
    }
}
