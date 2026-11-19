package com.phone2gamepad.data

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Outline shape used for a group of controls. Drawn with vector paths only (no bitmaps). */
enum class SkinShape { Circle, RoundedSquare, Hexagon, Angular }

enum class LabelStyle { Bold, Light, Mono }

/** Every colour a skin defines. [key] is the stable name used in the file format. */
enum class ColorSlot(val key: String, val label: String) {
    Background("background", "Background tint"),
    FaceA("faceA", "A button"),
    FaceB("faceB", "B button"),
    FaceX("faceX", "X button"),
    FaceY("faceY", "Y button"),
    ButtonIdle("buttonIdle", "Button"),
    ButtonPressed("buttonPressed", "Button pressed"),
    StickBase("stickBase", "Stick base"),
    StickKnob("stickKnob", "Stick knob"),
    StickRing("stickRing", "Stick ring"),
    Dpad("dpad", "D-pad base"),
    Trigger("trigger", "Triggers"),
    Bumper("bumper", "Bumpers"),
    LabelText("labelText", "Label text"),
    Accent("accent", "Accent (active)"),
    Outline("outline", "Outline"),
}

/**
 * A controller skin: pure data (colours, shapes, outline, label style). It contains no code, links or images, so a skin
 * file can never do anything but change how the pad looks. Colours are opaque 0xFFRRGGBB ints.
 */
data class Skin(
    val id: String,
    val name: String,
    val author: String = "",
    val description: String = "",
    val colors: Map<ColorSlot, Int>,
    val faceShape: SkinShape = SkinShape.Circle,
    val buttonShape: SkinShape = SkinShape.RoundedSquare,
    val padShape: SkinShape = SkinShape.Circle,
    val outlineWidth: Float = 2f,
    val labelStyle: LabelStyle = LabelStyle.Bold,
) {
    fun color(slot: ColorSlot): Int = colors[slot] ?: Skins.CLASSIC.colors.getValue(slot)

    fun withColor(slot: ColorSlot, argb: Int): Skin = copy(colors = colors + (slot to opaque(argb)))

    /** Clamps ranges, trims text and fills missing colours so any instance is safe to draw and store. */
    fun sanitized(): Skin = copy(
        id = id.take(MAX_ID),
        name = name.trim().take(MAX_NAME).ifEmpty { "Skin" },
        author = author.trim().take(MAX_NAME),
        description = description.trim().take(MAX_DESCRIPTION),
        colors = ColorSlot.entries.associateWith { opaque(colors[it] ?: Skins.CLASSIC.colors.getValue(it)) },
        outlineWidth = if (outlineWidth.isNaN()) 2f else outlineWidth.coerceIn(0f, MAX_OUTLINE),
    )

    companion object {
        const val MAX_ID = 64
        const val MAX_NAME = 40
        const val MAX_DESCRIPTION = 200
        const val MAX_OUTLINE = 6f
        fun opaque(argb: Int): Int = argb or (0xFF shl 24)
    }
}

/** Colour maths (WCAG relative luminance / contrast) shared by the renderer and the tests. */
object SkinColor {
    private fun channel(v: Int): Double {
        val s = v / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    fun luminance(argb: Int): Double =
        0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)

    fun contrast(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    /** [fg] drawn over [bg] at [alpha] (0..1), opaque result. */
    fun blend(fg: Int, bg: Int, alpha: Float): Int {
        fun mix(shift: Int): Int {
            val f = (fg shr shift) and 0xFF
            val b = (bg shr shift) and 0xFF
            return (f * alpha + b * (1f - alpha)).toInt().coerceIn(0, 255)
        }
        return Skin.opaque((mix(16) shl 16) or (mix(8) shl 8) or mix(0))
    }

    /** Black or white, whichever reads better on [bg]. */
    fun readableOn(bg: Int): Int = if (contrast(0xFFFFFFFF.toInt(), bg) >= contrast(0xFF000000.toInt(), bg)) 0xFFFFFFFF.toInt() else 0xFF000000.toInt()

    /** Fill of an idle face button (its colour tinted over the background), the surface its label sits on. */
    fun faceIdleFill(skin: Skin, slot: ColorSlot): Int = blend(skin.color(slot), skin.color(ColorSlot.Background), FACE_IDLE_ALPHA)

    const val FACE_IDLE_ALPHA = 0.30f

    fun toHex(argb: Int): String = "#%06X".format(argb and 0xFFFFFF)

    /** Parses exactly "#RRGGBB"; null otherwise. */
    fun parseHex(s: String): Int? =
        if (s.length == 7 && s[0] == '#' && s.drop(1).all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' })
            Skin.opaque(s.substring(1).toInt(16)) else null
}

/** Pure outline geometry for the polygon shapes, so it can be unit-tested; null for shapes drawn natively (circle/rounded). */
object ShapeGeometry {
    /** Polygon vertices (x,y pairs, clockwise) for [shape] inside the box l,t,r,b. */
    fun polygon(shape: SkinShape, l: Float, t: Float, r: Float, b: Float): List<Pair<Float, Float>>? {
        val w = r - l
        val h = b - t
        val cy = (t + b) / 2f
        return when (shape) {
            SkinShape.Hexagon -> listOf(
                l to cy, (l + w * 0.25f) to t, (r - w * 0.25f) to t, r to cy, (r - w * 0.25f) to b, (l + w * 0.25f) to b,
            )
            SkinShape.Angular -> {
                val big = min(w, h) * 0.30f
                val small = min(w, h) * 0.10f
                listOf(
                    (l + big) to t, (r - small) to t, r to (t + small), r to (b - big),
                    (r - big) to b, (l + small) to b, l to (b - small), l to (t + big),
                )
            }
            else -> null
        }
    }
}

/** The bundled, original skins. Names are generic on purpose; the descriptions only talk about mood and colour. */
object Skins {
    private fun c(rgb: Int) = Skin.opaque(rgb)

    private fun skin(
        id: String, name: String, description: String,
        bg: Int, a: Int, b: Int, x: Int, y: Int, idle: Int, pressed: Int,
        stickBase: Int, knob: Int, ring: Int, dpad: Int, trigger: Int, bumper: Int, label: Int, accent: Int, outline: Int,
        face: SkinShape, button: SkinShape, pad: SkinShape, outlineWidth: Float, labelStyle: LabelStyle = LabelStyle.Bold,
    ) = Skin(
        id = id, name = name, author = "Phone2Gamepad", description = description,
        colors = mapOf(
            ColorSlot.Background to c(bg), ColorSlot.FaceA to c(a), ColorSlot.FaceB to c(b), ColorSlot.FaceX to c(x), ColorSlot.FaceY to c(y),
            ColorSlot.ButtonIdle to c(idle), ColorSlot.ButtonPressed to c(pressed), ColorSlot.StickBase to c(stickBase),
            ColorSlot.StickKnob to c(knob), ColorSlot.StickRing to c(ring), ColorSlot.Dpad to c(dpad), ColorSlot.Trigger to c(trigger),
            ColorSlot.Bumper to c(bumper), ColorSlot.LabelText to c(label), ColorSlot.Accent to c(accent), ColorSlot.Outline to c(outline),
        ),
        faceShape = face, buttonShape = button, padShape = pad, outlineWidth = outlineWidth, labelStyle = labelStyle,
    )

    const val CLASSIC_ID = "classic"

    val CLASSIC = skin(
        CLASSIC_ID, "Classic", "The original look: dark slate with green, red, blue and amber face buttons.",
        bg = 0x0E1013, a = 0x58C46A, b = 0xE5534B, x = 0x4D8FE8, y = 0xE6B450, idle = 0x22262D, pressed = 0x3A7573,
        stickBase = 0x191C21, knob = 0x59616E, ring = 0x3A404A, dpad = 0x171A1F, trigger = 0x22262D, bumper = 0x22262D,
        label = 0xFFFFFF, accent = 0x4DB6AC, outline = 0x3A404A,
        face = SkinShape.Circle, button = SkinShape.RoundedSquare, pad = SkinShape.Circle, outlineWidth = 2f,
    )

    val TACTICAL = skin(
        "tactical", "Tactical", "Near-black with red and teal accents and sharp, angular cuts. A competitive shooter mood.",
        bg = 0x0A0D10, a = 0x3DDBC2, b = 0xE8404F, x = 0x9AA7B2, y = 0xF2D16B, idle = 0x1B2229, pressed = 0x8C2430,
        stickBase = 0x12171C, knob = 0x55606B, ring = 0xE8404F, dpad = 0x12171C, trigger = 0x1B2229, bumper = 0x1B2229,
        label = 0xF2F5F7, accent = 0xE8404F, outline = 0xC8363F,
        face = SkinShape.Angular, button = SkinShape.Angular, pad = SkinShape.Angular, outlineWidth = 2.5f,
    )

    val STREET_RACER = skin(
        "street-racer", "Street Racer", "Dark asphalt with orange and cyan. Angular shapes for an arcade street-racing mood.",
        bg = 0x0C0C10, a = 0xFF8A1F, b = 0x22C7E6, x = 0xE6E6EE, y = 0xFFD23F, idle = 0x24242C, pressed = 0x8A4A12,
        stickBase = 0x16161B, knob = 0x5A5A68, ring = 0x22C7E6, dpad = 0x16161B, trigger = 0x24242C, bumper = 0x2E2E38,
        label = 0xFFFFFF, accent = 0xFF8A1F, outline = 0xFF8A1F,
        face = SkinShape.Angular, button = SkinShape.Angular, pad = SkinShape.Circle, outlineWidth = 2f,
    )

    val OPS_BLACK = skin(
        "ops-black", "Ops Black", "Matte black with olive and amber and hexagonal buttons. A military-tech mood.",
        bg = 0x0A0B0A, a = 0x8FA05A, b = 0xC1432E, x = 0x7C8FA3, y = 0xF2A900, idle = 0x1E211C, pressed = 0x6B5A1E,
        stickBase = 0x141612, knob = 0x5A6150, ring = 0x4B5240, dpad = 0x141612, trigger = 0x1E211C, bumper = 0x262A22,
        label = 0xE6E8DF, accent = 0xF2A900, outline = 0x4B5240,
        face = SkinShape.Hexagon, button = SkinShape.Angular, pad = SkinShape.Hexagon, outlineWidth = 2f,
    )

    val NEON_PULSE = skin(
        "neon-pulse", "Neon Pulse", "Deep blue-black with soft teal, coral and sky accents. Colourful but no glow effects.",
        bg = 0x0B0F14, a = 0x2DD4BF, b = 0xFB7185, x = 0x60A5FA, y = 0xFACC15, idle = 0x17202B, pressed = 0x1F5F66,
        stickBase = 0x111820, knob = 0x4B5B6E, ring = 0x2DD4BF, dpad = 0x111820, trigger = 0x17202B, bumper = 0x1D2937,
        label = 0xF1F5F9, accent = 0x2DD4BF, outline = 0x1E7F78,
        face = SkinShape.Circle, button = SkinShape.RoundedSquare, pad = SkinShape.Circle, outlineWidth = 2f,
    )

    val LIGHT = skin(
        "light", "Light", "Bright grey background with white buttons and dark labels, for use in daylight.",
        bg = 0xE8EAEE, a = 0x1E9E4A, b = 0xD63A33, x = 0x2F6FD6, y = 0xC98A00, idle = 0xFFFFFF, pressed = 0x9CC7C2,
        stickBase = 0xD6DAE0, knob = 0x9AA1AC, ring = 0x9AA1AC, dpad = 0xD6DAE0, trigger = 0xFFFFFF, bumper = 0xFFFFFF,
        label = 0x1B1E23, accent = 0x0F8B80, outline = 0x7A828E,
        face = SkinShape.Circle, button = SkinShape.RoundedSquare, pad = SkinShape.Circle, outlineWidth = 2f,
    )

    val HIGH_CONTRAST = skin(
        "high-contrast", "High Contrast", "Black and white with thick outlines and a bright yellow highlight. Built for easy reading.",
        bg = 0x000000, a = 0x00E676, b = 0xFF5252, x = 0x40C4FF, y = 0xFFD400, idle = 0x111111, pressed = 0xFFD400,
        stickBase = 0x000000, knob = 0xBFBFBF, ring = 0xFFFFFF, dpad = 0x000000, trigger = 0x111111, bumper = 0x111111,
        label = 0xFFFFFF, accent = 0xFFD400, outline = 0xFFFFFF,
        face = SkinShape.Circle, button = SkinShape.RoundedSquare, pad = SkinShape.Circle, outlineWidth = 4f,
    )

    val RETRO_PAD = skin(
        "retro-pad", "Retro Pad", "Warm grey-brown plastic, a monospaced font and classic red, yellow, blue and green face buttons.",
        bg = 0x1A1612, a = 0xD94F4F, b = 0xE0C04A, x = 0x4F7FD9, y = 0x5AAD62, idle = 0x3A322B, pressed = 0x7A5A3C,
        stickBase = 0x2A241F, knob = 0xA08C74, ring = 0x6B5B4B, dpad = 0x2A241F, trigger = 0x3A322B, bumper = 0x3A322B,
        label = 0xF2E6D0, accent = 0xE0A458, outline = 0x6B5B4B,
        face = SkinShape.Circle, button = SkinShape.RoundedSquare, pad = SkinShape.Circle, outlineWidth = 2f, labelStyle = LabelStyle.Mono,
    )

    val BUNDLED: List<Skin> = listOf(CLASSIC, TACTICAL, STREET_RACER, OPS_BLACK, NEON_PULSE, LIGHT, HIGH_CONTRAST, RETRO_PAD)
    val BUNDLED_IDS: Set<String> = BUNDLED.map { it.id }.toSet()
}

/**
 * Bundled skins plus the user's custom ones. Bundled skins cannot be changed or deleted. All operations return a new library
 * (or null when a limit stops them).
 */
class SkinLibrary private constructor(val custom: List<Skin>) {
    val all: List<Skin> get() = Skins.BUNDLED + custom

    fun find(id: String?): Skin? = all.firstOrNull { it.id == id }

    /** The skin for [id], or Classic when it does not exist (deleted, from another phone, never set). */
    fun resolve(id: String?): Skin = find(id) ?: Skins.CLASSIC

    fun isBundled(id: String) = id in Skins.BUNDLED_IDS

    companion object {
        const val MAX_CUSTOM = 30

        fun empty() = SkinLibrary(emptyList())

        /** Builds a valid library from possibly inconsistent input: sanitized, unique non-bundled ids, limited count. */
        fun of(skins: List<Skin>): SkinLibrary {
            val seen = HashSet<String>(Skins.BUNDLED_IDS)
            val out = ArrayList<Skin>()
            for (s in skins) {
                if (out.size >= MAX_CUSTOM) break
                var q = s.sanitized()
                if (q.id.isBlank() || !seen.add(q.id)) {
                    q = q.copy(id = freshId(seen))
                    seen.add(q.id)
                }
                out += q
            }
            return SkinLibrary(out)
        }

        fun freshId(taken: Set<String> = emptySet()): String {
            while (true) {
                val id = UUID.randomUUID().toString()
                if (id !in taken) return id
            }
        }

        fun uniqueName(base: String, existing: Collection<String>): String {
            val clean = base.trim().take(Skin.MAX_NAME).ifEmpty { "Skin" }
            val used = existing.map { it.lowercase() }.toSet()
            if (clean.lowercase() !in used) return clean
            var n = 2
            while (true) {
                val suffix = " ($n)"
                val cand = clean.take(Skin.MAX_NAME - suffix.length) + suffix
                if (cand.lowercase() !in used) return cand
                n++
            }
        }
    }

    private fun names(except: String? = null) = all.filter { it.id != except }.map { it.name }

    /** Adds [skin] under a fresh id and a unique name. Null at the limit. */
    fun add(skin: Skin, newId: () -> String = { freshId(all.map { it.id }.toSet()) }): Pair<SkinLibrary, Skin>? {
        if (custom.size >= MAX_CUSTOM) return null
        val s = skin.sanitized().let { it.copy(id = newId(), name = uniqueName(it.name, names())) }
        return SkinLibrary(custom + s) to s
    }

    /** Duplicate of any skin (bundled or custom) as a new custom skin named "<name> copy". */
    fun duplicate(id: String, newId: () -> String = { freshId(all.map { it.id }.toSet()) }): Pair<SkinLibrary, Skin>? {
        val src = find(id) ?: return null
        return add(src.copy(name = src.name.take(Skin.MAX_NAME - 5) + " copy", author = if (isBundled(id)) "" else src.author), newId)
    }

    /** Replaces a custom skin (same id). Bundled and unknown ids are ignored. */
    fun replace(skin: Skin): SkinLibrary {
        if (isBundled(skin.id) || custom.none { it.id == skin.id }) return this
        val s = skin.sanitized().let { it.copy(name = uniqueName(it.name, names(it.id))) }
        return SkinLibrary(custom.map { if (it.id == s.id) s else it })
    }

    /** Removes a custom skin; bundled skins cannot be deleted. */
    fun delete(id: String): SkinLibrary = if (isBundled(id)) this else SkinLibrary(custom.filter { it.id != id })

    override fun equals(other: Any?) = other is SkinLibrary && other.custom == custom
    override fun hashCode() = custom.hashCode()
}

/** JSON (de)serialisation for stored skins and the shareable `.p2gskin` file. Parsing is strict and data-only. */
object SkinCodec {
    const val SCHEMA = 1
    const val EXPORT_TYPE = "phone2gamepad-skin"
    const val MAX_IMPORT_BYTES = 16 * 1024
    private const val MAX_DEPTH = 8
    const val FILE_EXTENSION = ".p2gskin"

    /** Migrations keyed by the schema they upgrade *from*. Empty while only v1 exists. */
    val MIGRATIONS: Map<Int, (JSONObject) -> JSONObject> = emptyMap()

    sealed class ImportResult {
        data class Ok(val skin: Skin) : ImportResult()
        data class Rejected(val reason: String) : ImportResult()
    }

    sealed class LibraryResult {
        data class Ok(val library: SkinLibrary) : LibraryResult()
        data class Corrupt(val reason: String) : LibraryResult()
        data class TooNew(val version: Int) : LibraryResult()
    }

    private val SKIN_KEYS = setOf("id", "name", "author", "description", "colors", "faceShape", "buttonShape", "padShape", "outlineWidth", "labelStyle")

    fun encodeSkin(s: Skin): JSONObject {
        val colors = JSONObject()
        for (slot in ColorSlot.entries) colors.put(slot.key, SkinColor.toHex(s.color(slot)))
        return JSONObject()
            .put("id", s.id).put("name", s.name).put("author", s.author).put("description", s.description)
            .put("colors", colors)
            .put("faceShape", s.faceShape.name).put("buttonShape", s.buttonShape.name).put("padShape", s.padShape.name)
            .put("outlineWidth", s.outlineWidth.toDouble()).put("labelStyle", s.labelStyle.name)
    }

    fun encodeExport(s: Skin): String =
        JSONObject().put("type", EXPORT_TYPE).put("schema", SCHEMA).put("skin", encodeSkin(s)).toString(2)

    /** Maximum bracket nesting, ignoring string contents. Used before parsing so hostile input cannot overflow the stack. */
    private fun nestingDepth(text: String): Int {
        var depth = 0
        var max = 0
        var inStr = false
        var esc = false
        for (ch in text) {
            if (inStr) {
                if (esc) esc = false else if (ch == '\\') esc = true else if (ch == '"') inStr = false
                continue
            }
            when (ch) {
                '"' -> inStr = true
                '{', '[' -> { depth++; if (depth > max) max = depth }
                '}', ']' -> depth--
            }
        }
        return max
    }

    private fun looksLikeLink(s: String): Boolean {
        val l = s.lowercase()
        return "://" in l || "www." in l || l.startsWith("data:") || l.startsWith("javascript:") || "file:" in l || "content:" in l
    }

    private fun hasControlChars(s: String) = s.any { it.code < 0x20 || it.code == 0x7F }

    private fun reject(msg: String) = ImportResult.Rejected(msg)

    private fun text(o: JSONObject, key: String, max: Int, required: Boolean): Any? {
        // returns String, or ImportResult.Rejected, or null when optional and absent
        if (!o.has(key)) return if (required) reject("Missing \"$key\".") else null
        val v = o.opt(key)
        if (v !is String) return reject("\"$key\" must be text.")
        if (v.length > max) return reject("\"$key\" is too long (max $max characters).")
        if (hasControlChars(v)) return reject("\"$key\" contains control characters.")
        if (looksLikeLink(v)) return reject("\"$key\" must not contain links.")
        return v
    }

    private inline fun <reified E : Enum<E>> enumOf(o: JSONObject, key: String): Any {
        val v = o.opt(key) ?: return reject("Missing \"$key\".")
        if (v !is String) return reject("\"$key\" must be text.")
        return enumValues<E>().firstOrNull { it.name == v } ?: reject("Unknown value \"${v.take(20)}\" for \"$key\".")
    }

    /** Strict parse of one skin object. Unknown fields, wrong types, bad colours and links are all rejected. */
    fun parseSkin(o: JSONObject): ImportResult {
        for (k in o.keys()) if (k !in SKIN_KEYS) return reject("Unknown field \"${k.take(20)}\".")
        val id = text(o, "id", Skin.MAX_ID, false)
        if (id is ImportResult.Rejected) return id
        val name = text(o, "name", Skin.MAX_NAME, true)
        if (name is ImportResult.Rejected) return name
        if ((name as String).isBlank()) return reject("\"name\" is empty.")
        val author = text(o, "author", Skin.MAX_NAME, false)
        if (author is ImportResult.Rejected) return author
        val desc = text(o, "description", Skin.MAX_DESCRIPTION, false)
        if (desc is ImportResult.Rejected) return desc

        val co = o.opt("colors") as? JSONObject ?: return reject("Missing \"colors\".")
        val known = ColorSlot.entries.map { it.key }.toSet()
        for (k in co.keys()) if (k !in known) return reject("Unknown colour \"${k.take(20)}\".")
        val colors = LinkedHashMap<ColorSlot, Int>()
        for (slot in ColorSlot.entries) {
            val v = co.opt(slot.key) ?: return reject("Missing colour \"${slot.key}\".")
            val parsed = (v as? String)?.let { SkinColor.parseHex(it) } ?: return reject("Colour \"${slot.key}\" must look like #RRGGBB.")
            colors[slot] = parsed
        }

        val face = enumOf<SkinShape>(o, "faceShape")
        if (face is ImportResult.Rejected) return face
        val button = enumOf<SkinShape>(o, "buttonShape")
        if (button is ImportResult.Rejected) return button
        val pad = enumOf<SkinShape>(o, "padShape")
        if (pad is ImportResult.Rejected) return pad
        val label = enumOf<LabelStyle>(o, "labelStyle")
        if (label is ImportResult.Rejected) return label

        val ow = o.opt("outlineWidth")
        val outline = (ow as? Number)?.toDouble() ?: return reject("\"outlineWidth\" must be a number.")
        if (outline.isNaN() || outline.isInfinite()) return reject("\"outlineWidth\" must be a number.")

        return ImportResult.Ok(
            Skin(
                id = (id as? String) ?: "", name = name, author = (author as? String) ?: "", description = (desc as? String) ?: "",
                colors = colors, faceShape = face as SkinShape, buttonShape = button as SkinShape, padShape = pad as SkinShape,
                outlineWidth = outline.toFloat().coerceIn(0f, Skin.MAX_OUTLINE), labelStyle = label as LabelStyle,
            ).sanitized()
        )
    }

    /** Parses the text of a `.p2gskin` file. The id inside is never trusted (the caller assigns a fresh one). */
    fun decodeImport(raw: String): ImportResult {
        if (raw.toByteArray(Charsets.UTF_8).size > MAX_IMPORT_BYTES) return reject("The file is too large for a skin (max 16 KB).")
        if (raw.isBlank()) return reject("The file is empty.")
        if (nestingDepth(raw) > MAX_DEPTH) return reject("The file is nested too deeply.")
        val root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return reject("This is not a valid skin file.")
        } catch (e: StackOverflowError) {
            return reject("This is not a valid skin file.")
        }
        for (k in root.keys()) if (k !in setOf("type", "schema", "skin")) return reject("Unknown field \"${k.take(20)}\".")
        if (root.opt("type") != EXPORT_TYPE) return reject("This is not a Phone2Gamepad skin file.")
        val v = root.opt("schema") as? Int ?: return reject("Missing schema version.")
        if (v < 1) return reject("Bad schema version.")
        if (v > SCHEMA) return reject("This skin was made with a newer version of the app.")
        var skinObj = root.opt("skin") as? JSONObject ?: return reject("Missing skin data.")
        var version = v
        try {
            while (version < SCHEMA) {
                skinObj = (MIGRATIONS[version] ?: return reject("Unsupported skin version."))(skinObj)
                version++
            }
        } catch (e: JSONException) {
            return reject("This is not a valid skin file.")
        }
        return parseSkin(skinObj)
    }

    // ---- stored library (custom skins only) ----

    fun encodeLibrary(lib: SkinLibrary): String {
        val arr = JSONArray()
        lib.custom.forEach { arr.put(encodeSkin(it)) }
        return JSONObject().put("schema", SCHEMA).put("skins", arr).toString()
    }

    /** Skins that fail strict validation are dropped one by one; an unparseable document is [LibraryResult.Corrupt]. */
    fun decodeLibrary(
        raw: String,
        schema: Int = SCHEMA,
        migrations: Map<Int, (JSONObject) -> JSONObject> = MIGRATIONS,
    ): LibraryResult {
        if (nestingDepth(raw) > MAX_DEPTH) return LibraryResult.Corrupt("nested too deeply")
        var root = try {
            JSONObject(raw)
        } catch (e: JSONException) {
            return LibraryResult.Corrupt("not valid JSON")
        }
        var version = root.optInt("schema", -1)
        if (!root.has("schema") || version < 1) return LibraryResult.Corrupt("bad schema version")
        if (version > schema) return LibraryResult.TooNew(version)
        try {
            while (version < schema) {
                root = (migrations[version] ?: return LibraryResult.Corrupt("no migration from schema $version"))(root)
                version++
                root.put("schema", version)
            }
            val arr = root.optJSONArray("skins") ?: return LibraryResult.Corrupt("missing skins")
            val list = ArrayList<Skin>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                (parseSkin(o) as? ImportResult.Ok)?.let { list += it.skin }
            }
            return LibraryResult.Ok(SkinLibrary.of(list))
        } catch (e: JSONException) {
            return LibraryResult.Corrupt("invalid structure")
        }
    }
}
