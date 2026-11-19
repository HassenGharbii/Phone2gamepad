package com.phone2gamepad.data

import com.phone2gamepad.protocol.ResponseCurve
import org.json.JSONException
import org.json.JSONObject
import java.util.UUID

enum class TriggerMode { Analog, Digital }

/** Which Xbox input the racing layout's handbrake lever sends (B by default). */
enum class HandbrakeButton(val label: String) { B("B"), A("A"), X("X"), Y("Y"), RB("RB"), L3("L3"), R3("R3") }

/** Ids of the touch controls a layout can position (kept in sync with the default layout in the UI package). */
object ControlIds {
    /** The Xbox layout's controls (other controller types list theirs in [ControllerType.controlIds]). */
    val ALL = ControllerType.Xbox.controlIds
}

/**
 * Per-control customisation. [fx]/[fy] are the control centre as a fraction (0..1) of the screen width/height,
 * both null while the control sits at its default place (so layouts adapt to other screen shapes until moved).
 * [scale] multiplies the control size and [opacity] multiplies the profile opacity.
 */
data class ControlOverride(
    val fx: Float? = null,
    val fy: Float? = null,
    val scale: Float = 1f,
    val opacity: Float = 1f,
    val visible: Boolean = true,
) {
    val isDefault get() = fx == null && fy == null && scale == 1f && opacity == 1f && visible

    fun sanitized(): ControlOverride {
        val hasPos = fx != null && fy != null
        return ControlOverride(
            fx = if (hasPos) fx!!.coerceIn(0f, 1f) else null,
            fy = if (hasPos) fy!!.coerceIn(0f, 1f) else null,
            scale = scale.coerceIn(Profile.MIN_SCALE, Profile.MAX_SCALE),
            opacity = opacity.coerceIn(Profile.MIN_OPACITY, 1f),
            visible = visible,
        )
    }
}

/** A saved controller layout plus its input tuning. Immutable. */
data class Profile(
    val id: String,
    val name: String,
    val controls: Map<String, ControlOverride> = emptyMap(),
    val globalScale: Float = 1f,
    val opacity: Float = 0.85f,
    val leftHanded: Boolean = false,
    val allowOverlap: Boolean = false,
    val deadZone: Float = 0.10f,
    val sensitivity: Float = 1f,
    val curve: ResponseCurve = ResponseCurve.Linear,
    val triggerMode: TriggerMode = TriggerMode.Analog,
    val stickReturnToCenter: Boolean = true,
    /** Id of the [Skin] used to draw this profile; falls back to Classic when the skin no longer exists. */
    val skinId: String = Skins.CLASSIC_ID,
    /** Which controller is drawn and laid out (Xbox / Racing / Fighting); the wire state is the same for all. */
    val controllerType: ControllerType = ControllerType.Xbox,
    /** Art printed on the controller shell. */
    val background: Background = Background.None,
    /** Steering wheel (Racing): full lock-to-lock rotation in degrees, and whether it springs back when released. */
    val wheelLockDeg: Float = WHEEL_DEFAULT_LOCK,
    val wheelSelfCenter: Boolean = true,
    /** Racing wheel response: centre dead zone, sensitivity (output multiplier), curve, and the small angle read-out. */
    val wheelDeadZone: Float = WHEEL_DEFAULT_DEADZONE,
    val wheelSensitivity: Float = 1f,
    val wheelCurve: ResponseCurve = ResponseCurve.Linear,
    val wheelShowAngle: Boolean = true,
    /** Racing pedals: brake response curve/sensitivity, "full press on touch", and whether lower on the pedal presses more. */
    val brakeCurve: ResponseCurve = ResponseCurve.Linear,
    val brakeSensitivity: Float = 1f,
    val pedalFullPress: Boolean = false,
    val pedalPressDown: Boolean = true,
    val handbrake: HandbrakeButton = HandbrakeButton.B,
    /** Optional tilt (gravity sensor) steering for the racing wheel; always off until the user turns it on. */
    val tiltEnabled: Boolean = false,
    val tiltSensitivity: Float = 1f,
    val tiltSmoothing: Float = TILT_DEFAULT_SMOOTHING,
    val tiltInvert: Boolean = false,
) {
    companion object {
        const val WHEEL_MIN_LOCK = 180f
        const val WHEEL_MAX_LOCK = 900f
        const val WHEEL_DEFAULT_LOCK = 540f
        const val WHEEL_DEFAULT_DEADZONE = 0.02f
        const val WHEEL_MAX_DEADZONE = 0.3f
        const val TILT_MIN_SENS = 0.5f
        const val TILT_MAX_SENS = 3f
        const val TILT_MAX_SMOOTHING = 0.9f
        const val TILT_DEFAULT_SMOOTHING = 0.5f
        /** Lock-to-lock rotations offered as one-tap choices in Tuning. */
        val WHEEL_LOCK_CHOICES = listOf(180f, 270f, 360f, 540f, 720f, 900f)
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2f
        const val MIN_GLOBAL_SCALE = 0.6f
        const val MAX_GLOBAL_SCALE = 1.6f
        const val MIN_OPACITY = 0.2f
        const val MAX_DEADZONE = 0.5f
        const val MIN_SENS = 0.5f
        const val MAX_SENS = 2f
        const val MAX_NAME = 40
    }

    fun override(controlId: String): ControlOverride = controls[controllerType.key(controlId)] ?: ControlOverride()

    /** Returns a copy with [controlId] customised; default-valued overrides are dropped to keep the file small. */
    fun withOverride(controlId: String, o: ControlOverride): Profile {
        val s = o.sanitized()
        val k = controllerType.key(controlId)
        return copy(controls = if (s.isDefault) controls - k else controls + (k to s))
    }

    /** Back to the standard layout and tuning, keeping [id], [name] and the look (skin, controller type, background). */
    fun resetToDefault(): Profile = Profile(id = id, name = name, skinId = skinId, controllerType = controllerType, background = background)

    /** Layout only (positions, sizes, visibility, opacity per control, global scale, handedness) of the current controller type. */
    fun resetLayout(): Profile = copy(controls = controls.filterKeys { !controllerType.owns(it) }, globalScale = 1f, leftHanded = false)

    fun sanitized(): Profile = copy(
        name = name.trim().take(MAX_NAME).ifEmpty { "Profile" },
        controls = controls.filterKeys { it in ControllerType.allKeys }.mapValues { it.value.sanitized() }.filterValues { !it.isDefault },
        globalScale = globalScale.coerceIn(MIN_GLOBAL_SCALE, MAX_GLOBAL_SCALE),
        opacity = opacity.coerceIn(MIN_OPACITY, 1f),
        deadZone = deadZone.coerceIn(0f, MAX_DEADZONE),
        sensitivity = sensitivity.coerceIn(MIN_SENS, MAX_SENS),
        skinId = skinId.take(Skin.MAX_ID).ifBlank { Skins.CLASSIC_ID },
        background = background.sanitized(),
        wheelLockDeg = if (wheelLockDeg.isNaN()) WHEEL_DEFAULT_LOCK else wheelLockDeg.coerceIn(WHEEL_MIN_LOCK, WHEEL_MAX_LOCK),
        wheelDeadZone = fin(wheelDeadZone, WHEEL_DEFAULT_DEADZONE).coerceIn(0f, WHEEL_MAX_DEADZONE),
        wheelSensitivity = fin(wheelSensitivity, 1f).coerceIn(MIN_SENS, MAX_SENS),
        brakeSensitivity = fin(brakeSensitivity, 1f).coerceIn(MIN_SENS, MAX_SENS),
        tiltSensitivity = fin(tiltSensitivity, 1f).coerceIn(TILT_MIN_SENS, TILT_MAX_SENS),
        tiltSmoothing = fin(tiltSmoothing, TILT_DEFAULT_SMOOTHING).coerceIn(0f, TILT_MAX_SMOOTHING),
    )

    private fun fin(v: Float, def: Float) = if (v.isNaN() || v.isInfinite()) def else v
}

/**
 * The ordered set of profiles and which one is active. Invariants: at least one profile, unique ids,
 * [activeId] refers to an existing profile. All operations return a new book (or null when a limit stops them).
 */
class ProfileBook private constructor(val profiles: List<Profile>, val activeId: String) {
    val active: Profile get() = profiles.first { it.id == activeId }

    companion object {
        const val STANDARD_ID = "standard"
        const val MAX_PROFILES = 20

        /** Builds a valid book from possibly inconsistent input (used after decoding). */
        fun of(profiles: List<Profile>, activeId: String?): ProfileBook {
            val seen = HashSet<String>()
            val cleaned = ArrayList<Profile>()
            for (p in profiles.take(MAX_PROFILES)) {
                var q = p.sanitized()
                if (q.id.isBlank() || !seen.add(q.id)) {
                    q = q.copy(id = freshId(seen))
                    seen.add(q.id)
                }
                cleaned += q
            }
            if (cleaned.isEmpty()) return default()
            val active = if (cleaned.any { it.id == activeId }) activeId!! else cleaned.first().id
            return ProfileBook(cleaned, active)
        }

        /** One "Standard" profile; the legacy single-set settings seed its tuning so existing users keep their feel. */
        fun default(
            deadZone: Float = 0.10f, sensitivity: Float = 1f, opacity: Float = 0.85f, digitalTriggers: Boolean = false,
        ): ProfileBook = ProfileBook(
            listOf(
                Profile(
                    id = STANDARD_ID, name = "Standard", opacity = opacity, deadZone = deadZone, sensitivity = sensitivity,
                    triggerMode = if (digitalTriggers) TriggerMode.Digital else TriggerMode.Analog,
                ).sanitized()
            ),
            STANDARD_ID,
        )

        fun freshId(taken: Set<String> = emptySet()): String {
            while (true) {
                val id = UUID.randomUUID().toString()
                if (id !in taken) return id
            }
        }

        /** [base] made unique among [existing] (case-insensitive) by appending " (2)", " (3)"... within the length limit. */
        fun uniqueName(base: String, existing: Collection<String>): String {
            val clean = base.trim().take(Profile.MAX_NAME).ifEmpty { "Profile" }
            val used = existing.map { it.lowercase() }.toSet()
            if (clean.lowercase() !in used) return clean
            var n = 2
            while (true) {
                val suffix = " ($n)"
                val cand = clean.take(Profile.MAX_NAME - suffix.length) + suffix
                if (cand.lowercase() !in used) return cand
                n++
            }
        }
    }

    private fun with(newProfiles: List<Profile>, newActive: String = activeId) = ProfileBook(newProfiles, newActive)
    private fun find(id: String) = profiles.firstOrNull { it.id == id }

    /** Profiles using skin [skinId] go back to Classic (called when a custom skin is deleted). */
    fun clearSkin(skinId: String): ProfileBook =
        if (profiles.none { it.skinId == skinId }) this else with(profiles.map { if (it.skinId == skinId) it.copy(skinId = Skins.CLASSIC_ID) else it })

    fun select(id: String): ProfileBook = if (find(id) == null) this else with(profiles, id)

    /** New profile with default layout/tuning; null when the profile limit is reached. */
    fun create(name: String, newId: () -> String = { freshId(profiles.map { it.id }.toSet()) }): ProfileBook? {
        if (profiles.size >= MAX_PROFILES) return null
        val p = Profile(id = newId(), name = uniqueName(name, profiles.map { it.name }))
        return with(profiles + p, p.id)
    }

    fun rename(id: String, name: String): ProfileBook {
        val p = find(id) ?: return this
        val others = profiles.filter { it.id != id }.map { it.name }
        return with(profiles.map { if (it.id == id) p.copy(name = uniqueName(name, others)) else it })
    }

    /** Copy named "<name> copy"; the copy becomes active. Null at the profile limit. */
    fun duplicate(id: String, newId: () -> String = { freshId(profiles.map { it.id }.toSet()) }): ProfileBook? {
        val p = find(id) ?: return this
        if (profiles.size >= MAX_PROFILES) return null
        val copy = p.copy(id = newId(), name = uniqueName(p.name.take(Profile.MAX_NAME - 5) + " copy", profiles.map { it.name }))
        val idx = profiles.indexOf(p)
        return with(profiles.toMutableList().apply { add(idx + 1, copy) }, copy.id)
    }

    /** Deletes a profile; the last remaining one cannot be deleted. Deleting the active one activates its neighbour. */
    fun delete(id: String): ProfileBook {
        if (profiles.size <= 1) return this
        val idx = profiles.indexOfFirst { it.id == id }
        if (idx < 0) return this
        val rest = profiles.filter { it.id != id }
        val newActive = if (activeId == id) rest[minOf(idx, rest.lastIndex)].id else activeId
        return with(rest, newActive)
    }

    fun reset(id: String): ProfileBook {
        val p = find(id) ?: return this
        return with(profiles.map { if (it.id == id) p.resetToDefault() else it })
    }

    /** Replaces the profile with the same id (e.g. after the editor saved it). Name/limits re-validated. */
    fun replace(p: Profile): ProfileBook {
        if (find(p.id) == null) return this
        val s = p.sanitized()
        val others = profiles.filter { it.id != p.id }.map { it.name }
        return with(profiles.map { if (it.id == p.id) s.copy(name = uniqueName(s.name, others)) else it })
    }

    /** Adds an imported profile under a fresh id and a unique name, and activates it. Null at the limit. */
    fun importProfile(p: Profile): ProfileBook? {
        if (profiles.size >= MAX_PROFILES) return null
        val s = p.sanitized().copy(id = freshId(profiles.map { it.id }.toSet()))
        val named = s.copy(name = uniqueName(s.name, profiles.map { it.name }))
        return with(profiles + named, named.id)
    }

    override fun equals(other: Any?) = other is ProfileBook && other.profiles == profiles && other.activeId == activeId
    override fun hashCode() = profiles.hashCode() * 31 + activeId.hashCode()
}

/** JSON (de)serialisation with schema versioning, lenient clamping and corruption detection. */
object ProfileCodec {
    const val SCHEMA = 4
    private const val EXPORT_TYPE = "phone2gamepad-profile"
    const val MAX_IMPORT_BYTES = 256 * 1024

    /**
     * Migrations keyed by the schema version they upgrade *from* (to version + 1).
     * v1 -> v2: profiles gained a skin; existing ones keep the look they had (Classic).
     * v2 -> v3: profiles gained a controller type and a background; existing ones stay Xbox with no background.
     * v3 -> v4: the racing wheel got its own dead zone / sensitivity / curve (the wheel used the stick values until now, so
     * existing profiles copy those and steer exactly as before); pedal, handbrake and tilt settings take their defaults.
     */
    val MIGRATIONS: Map<Int, (JSONObject) -> JSONObject> = mapOf(
        1 to { root ->
            root.optJSONArray("profiles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    if (!p.has("skin")) p.put("skin", Skins.CLASSIC_ID)
                }
            }
            root
        },
        2 to { root ->
            root.optJSONArray("profiles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    if (!p.has("type")) p.put("type", ControllerType.Xbox.id)
                }
            }
            root
        },
        3 to { root ->
            root.optJSONArray("profiles")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val p = arr.optJSONObject(i) ?: continue
                    if (!p.has("wheelDeadZone") && p.has("deadZone")) p.put("wheelDeadZone", p.get("deadZone"))
                    if (!p.has("wheelSensitivity") && p.has("sensitivity")) p.put("wheelSensitivity", p.get("sensitivity"))
                    if (!p.has("wheelCurve") && p.has("curve")) p.put("wheelCurve", p.get("curve"))
                }
            }
            root
        },
    )

    sealed class BookResult {
        data class Ok(val book: ProfileBook) : BookResult()
        /** Unparseable or structurally invalid. */
        data class Corrupt(val reason: String) : BookResult()
        /** Written by a newer app version; do not guess. */
        data class TooNew(val version: Int) : BookResult()
    }

    fun encodeProfile(p: Profile): JSONObject {
        val controls = JSONObject()
        for ((id, o) in p.controls) {
            val c = JSONObject()
            if (o.fx != null && o.fy != null) c.put("fx", o.fx.toDouble()).put("fy", o.fy.toDouble())
            if (o.scale != 1f) c.put("scale", o.scale.toDouble())
            if (o.opacity != 1f) c.put("opacity", o.opacity.toDouble())
            if (!o.visible) c.put("visible", false)
            controls.put(id, c)
        }
        return JSONObject()
            .put("id", p.id).put("name", p.name)
            .put("globalScale", p.globalScale.toDouble()).put("opacity", p.opacity.toDouble())
            .put("leftHanded", p.leftHanded).put("allowOverlap", p.allowOverlap)
            .put("deadZone", p.deadZone.toDouble()).put("sensitivity", p.sensitivity.toDouble())
            .put("curve", p.curve.name).put("triggerMode", p.triggerMode.name)
            .put("stickReturnToCenter", p.stickReturnToCenter)
            .put("skin", p.skinId)
            .put("type", p.controllerType.id)
            .put("wheelLock", p.wheelLockDeg.toDouble()).put("wheelSelfCenter", p.wheelSelfCenter)
            .put("wheelDeadZone", p.wheelDeadZone.toDouble()).put("wheelSensitivity", p.wheelSensitivity.toDouble())
            .put("wheelCurve", p.wheelCurve.name).put("wheelShowAngle", p.wheelShowAngle)
            .put("brakeCurve", p.brakeCurve.name).put("brakeSensitivity", p.brakeSensitivity.toDouble())
            .put("pedalFullPress", p.pedalFullPress).put("pedalPressDown", p.pedalPressDown)
            .put("handbrake", p.handbrake.name)
            .put("tilt", p.tiltEnabled).put("tiltSensitivity", p.tiltSensitivity.toDouble())
            .put("tiltSmoothing", p.tiltSmoothing.toDouble()).put("tiltInvert", p.tiltInvert)
            .put("background", encodeBackground(p.background))
            .put("controls", controls)
    }

    private fun encodeBackground(b: Background): JSONObject = JSONObject()
        .put("kind", b.kind.name).put("ref", b.ref).put("scrim", b.scrim.toDouble()).put("zoom", b.zoom.toDouble())
        .put("offsetX", b.offsetX.toDouble()).put("offsetY", b.offsetY.toDouble())

    private fun decodeBackground(o: JSONObject?): Background {
        if (o == null) return Background.None
        val d = Background()
        return Background(
            kind = BackgroundKind.entries.firstOrNull { it.name == o.optString("kind") } ?: BackgroundKind.None,
            ref = o.optString("ref", "").take(80),
            scrim = o.float("scrim", d.scrim), zoom = o.float("zoom", d.zoom),
            offsetX = o.float("offsetX", d.offsetX), offsetY = o.float("offsetY", d.offsetY),
        ).sanitized()
    }

    private fun JSONObject.float(key: String, def: Float): Float {
        val v = optDouble(key, Double.NaN)
        return if (v.isNaN() || v.isInfinite()) def else v.toFloat()
    }

    private inline fun <reified E : Enum<E>> JSONObject.enum(key: String, def: E): E =
        enumValues<E>().firstOrNull { it.name == optString(key) } ?: def

    /** Lenient: unknown fields are ignored, bad/missing values fall back to defaults, ranges are clamped. */
    fun decodeProfile(o: JSONObject): Profile {
        val d = Profile(id = "", name = "")
        val controls = LinkedHashMap<String, ControlOverride>()
        o.optJSONObject("controls")?.let { co ->
            for (id in ControllerType.allKeys) {
                val c = co.optJSONObject(id) ?: continue
                val fx = c.float("fx", Float.NaN)
                val fy = c.float("fy", Float.NaN)
                val hasPos = !fx.isNaN() && !fy.isNaN()
                controls[id] = ControlOverride(
                    fx = if (hasPos) fx else null, fy = if (hasPos) fy else null,
                    scale = c.float("scale", 1f), opacity = c.float("opacity", 1f),
                    visible = if (c.has("visible")) c.optBoolean("visible", true) else true,
                )
            }
        }
        return Profile(
            id = o.optString("id", ""),
            name = o.optString("name", ""),
            controls = controls,
            globalScale = o.float("globalScale", d.globalScale),
            opacity = o.float("opacity", d.opacity),
            leftHanded = o.optBoolean("leftHanded", d.leftHanded),
            allowOverlap = o.optBoolean("allowOverlap", d.allowOverlap),
            deadZone = o.float("deadZone", d.deadZone),
            sensitivity = o.float("sensitivity", d.sensitivity),
            curve = o.enum("curve", d.curve),
            triggerMode = o.enum("triggerMode", d.triggerMode),
            stickReturnToCenter = o.optBoolean("stickReturnToCenter", d.stickReturnToCenter),
            skinId = o.optString("skin", d.skinId),
            controllerType = ControllerType.fromId(o.optString("type", "")),
            background = decodeBackground(o.optJSONObject("background")),
            wheelLockDeg = o.float("wheelLock", d.wheelLockDeg),
            wheelSelfCenter = o.optBoolean("wheelSelfCenter", d.wheelSelfCenter),
            wheelDeadZone = o.float("wheelDeadZone", d.wheelDeadZone),
            wheelSensitivity = o.float("wheelSensitivity", d.wheelSensitivity),
            wheelCurve = o.enum("wheelCurve", d.wheelCurve),
            wheelShowAngle = o.optBoolean("wheelShowAngle", d.wheelShowAngle),
            brakeCurve = o.enum("brakeCurve", d.brakeCurve),
            brakeSensitivity = o.float("brakeSensitivity", d.brakeSensitivity),
            pedalFullPress = o.optBoolean("pedalFullPress", d.pedalFullPress),
            pedalPressDown = o.optBoolean("pedalPressDown", d.pedalPressDown),
            handbrake = o.enum("handbrake", d.handbrake),
            tiltEnabled = o.optBoolean("tilt", d.tiltEnabled),
            tiltSensitivity = o.float("tiltSensitivity", d.tiltSensitivity),
            tiltSmoothing = o.float("tiltSmoothing", d.tiltSmoothing),
            tiltInvert = o.optBoolean("tiltInvert", d.tiltInvert),
        ).sanitized()
    }

    fun encodeBook(b: ProfileBook): String {
        val arr = org.json.JSONArray()
        b.profiles.forEach { arr.put(encodeProfile(it)) }
        return JSONObject().put("schema", SCHEMA).put("active", b.activeId).put("profiles", arr).toString()
    }

    /** [schema]/[migrations] are parameters so the upgrade path can be tested before a real v2 exists. */
    fun decodeBook(
        text: String,
        schema: Int = SCHEMA,
        migrations: Map<Int, (JSONObject) -> JSONObject> = MIGRATIONS,
    ): BookResult {
        var root = try {
            JSONObject(text)
        } catch (e: JSONException) {
            return BookResult.Corrupt("not valid JSON")
        }
        if (!root.has("schema")) return BookResult.Corrupt("missing schema version")
        var version = root.optInt("schema", -1)
        if (version < 1) return BookResult.Corrupt("bad schema version")
        if (version > schema) return BookResult.TooNew(version)
        try {
            while (version < schema) {
                val step = migrations[version] ?: return BookResult.Corrupt("no migration from schema $version")
                root = step(root)
                version++
                root.put("schema", version)
            }
            val arr = root.optJSONArray("profiles") ?: return BookResult.Corrupt("missing profiles")
            val list = ArrayList<Profile>()
            for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list += decodeProfile(it) }
            if (list.isEmpty()) return BookResult.Corrupt("no usable profiles")
            return BookResult.Ok(ProfileBook.of(list, root.optString("active", "")))
        } catch (e: JSONException) {
            return BookResult.Corrupt("invalid structure")
        }
    }

    fun encodeExport(p: Profile): String =
        JSONObject().put("type", EXPORT_TYPE).put("schema", SCHEMA)
            .put("profile", encodeProfile(p.copy(background = p.background.forExport()))).toString(2)

    /** Parses an exported profile file; null if it is not one of ours, is too new or too large. The id is not trusted. */
    fun decodeExport(text: String): Profile? {
        if (text.length > MAX_IMPORT_BYTES) return null
        return try {
            val root = JSONObject(text)
            if (root.optString("type") != EXPORT_TYPE) return null
            val v = root.optInt("schema", -1)
            if (v < 1 || v > SCHEMA) return null
            // A private image file named in someone else's export means nothing here (and is never trusted).
            decodeProfile(root.getJSONObject("profile")).let { it.copy(background = it.background.forExport()) }
        } catch (e: JSONException) {
            null
        }
    }
}
