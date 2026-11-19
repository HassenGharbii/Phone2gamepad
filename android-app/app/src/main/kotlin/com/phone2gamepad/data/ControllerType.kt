package com.phone2gamepad.data

/**
 * The kind of physical controller drawn and laid out on the phone. Every type produces the same Xbox-style wire state
 * (no protocol change); a type only decides which on-screen controls exist, how they are arranged and how they look.
 *
 * Layout overrides are stored per type: Xbox keeps the plain control id (so existing profiles are unchanged), the other
 * types prefix it ("racing.GAS"), so switching type and back never mixes up positions.
 */
enum class ControllerType(val id: String, val label: String, val description: String, val controlIds: List<String>) {
    Xbox(
        "xbox", "Xbox", "Two sticks, D-pad, face buttons, bumpers and analog triggers.",
        listOf("LT", "LB", "RT", "RB", "LS", "DPAD", "RS", "Y", "A", "X", "B", "BACK", "START", "L3", "R3"),
    ),
    Racing(
        "racing", "Racing", "Touch steering wheel, gas and brake pedals, paddle shifters.",
        listOf("WHEEL", "SHIFT_DOWN", "SHIFT_UP", "BRAKE", "GAS", "DPAD", "Y", "A", "X", "B", "BACK", "START"),
    ),
    Fighting(
        "fighting", "Fighting", "Big 8-way D-pad and six attack buttons, with an optional turbo toggle.",
        listOf("DPAD", "LB", "LT", "RB", "RT", "Y", "A", "X", "B", "BACK", "START", "TURBO"),
    );

    /** Shell/button look: domed arcade-style buttons. */
    val domedButtons: Boolean get() = this == Fighting

    /** Key under which this type stores the override of control [controlId]. */
    fun key(controlId: String): String = if (this == Xbox) controlId else "$id.$controlId"

    /** Whether the stored override [key] belongs to this type. */
    fun owns(key: String): Boolean = if (this == Xbox) '.' !in key else key.startsWith("$id.")

    companion object {
        fun fromId(id: String?): ControllerType = entries.firstOrNull { it.id == id } ?: Xbox

        /** Every valid override key across all types (used to drop junk when decoding). */
        val allKeys: Set<String> by lazy { entries.flatMap { t -> t.controlIds.map { t.key(it) } }.toSet() }
    }
}

enum class BackgroundKind { None, Builtin, Image }

/**
 * What is "printed" on the controller shell: nothing, one of the built-in vector designs, or an image the user picked
 * (stored privately by file name in [ref]). [scrim] darkens the art, [zoom]/[offsetX]/[offsetY] crop it.
 */
data class Background(
    val kind: BackgroundKind = BackgroundKind.None,
    val ref: String = "",
    val scrim: Float = DEFAULT_SCRIM,
    val zoom: Float = 1f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
) {
    val imageRef: String? get() = if (kind == BackgroundKind.Image) ref else null

    /** Clamps ranges and drops references that cannot be valid (so a hostile value never reaches the file system). */
    fun sanitized(): Background {
        fun f(v: Float, lo: Float, hi: Float, d: Float) = if (v.isNaN() || v.isInfinite()) d else v.coerceIn(lo, hi)
        val ok = when (kind) {
            BackgroundKind.None -> false
            BackgroundKind.Builtin -> ref in BuiltinBackgrounds.IDS
            BackgroundKind.Image -> isValidImageRef(ref)
        }
        val base = if (ok) this else None
        return base.copy(
            scrim = f(scrim, 0f, MAX_SCRIM, DEFAULT_SCRIM), zoom = f(zoom, 1f, MAX_ZOOM, 1f),
            offsetX = f(offsetX, -1f, 1f, 0f), offsetY = f(offsetY, -1f, 1f, 0f),
        )
    }

    /** Copy safe to put in an exported file: a private image file means nothing on another phone, so it is dropped. */
    fun forExport(): Background = if (kind == BackgroundKind.Image) None else this

    companion object {
        const val DEFAULT_SCRIM = 0.30f
        const val MAX_SCRIM = 0.85f
        const val MAX_ZOOM = 3f
        val None = Background()
        private val IMAGE_REF = Regex("^[A-Za-z0-9_-]{1,64}\\.jpg$")
        fun isValidImageRef(ref: String) = IMAGE_REF.matches(ref)
    }
}

/** Ids of the built-in vector backgrounds (original abstract art, drawn in code). */
object BuiltinBackgrounds {
    const val AURORA = "aurora"
    const val CIRCUIT = "circuit"
    const val WAVES = "waves"
    val IDS = listOf(AURORA, CIRCUIT, WAVES)
    fun label(id: String) = when (id) {
        AURORA -> "Aurora"
        CIRCUIT -> "Circuit"
        WAVES -> "Waves"
        else -> id
    }
}

/** Pure rules for user-picked background images (the Android decoding itself lives in [BackgroundStore]). */
object BackgroundLimits {
    const val MAX_FILE_BYTES = 24L * 1024 * 1024
    const val MAX_SIDE_IN = 16384
    const val MAX_PIXELS_IN = 120_000_000L
    const val MIN_SIDE = 16
    const val MAX_SIDE_OUT = 2048

    /** Size to decode a [w] x [h] source to (long side at most [MAX_SIDE_OUT], never upscaled); null when the image must be rejected. */
    fun targetSize(w: Int, h: Int): Pair<Int, Int>? {
        if (w < MIN_SIDE || h < MIN_SIDE || w > MAX_SIDE_IN || h > MAX_SIDE_IN || w.toLong() * h > MAX_PIXELS_IN) return null
        val longSide = maxOf(w, h)
        if (longSide <= MAX_SIDE_OUT) return w to h
        val k = MAX_SIDE_OUT.toFloat() / longSide
        return maxOf(1, (w * k).toInt()) to maxOf(1, (h * k).toInt())
    }

    fun fileSizeAllowed(bytes: Long) = bytes in 1..MAX_FILE_BYTES
}
