package com.phone2gamepad.ui

import com.phone2gamepad.Screen
import com.phone2gamepad.net.ConnState
import com.phone2gamepad.net.Metrics

/** PIN entry rules (pure, unit-tested). */
object PinInput {
    const val LENGTH = 6

    /**
     * New PIN field value from whatever the text field now holds: typed digits or a pasted string such as
     * "PIN: 123 456". Non-digits are dropped and the result is cut to 6 digits.
     */
    fun sanitize(raw: String): String = raw.filter { it in '0'..'9' }.take(LENGTH)

    fun isComplete(pin: String): Boolean = pin.length == LENGTH && pin.all { it in '0'..'9' }
}

/** What went wrong while pairing, with the next step for the user. */
data class PairProblem(val title: String, val detail: String)

object PairErrors {
    /** Maps the protocol error [code] (null = unknown) to a user-facing problem. [fallback] is the raw message. */
    fun describe(code: String?, fallback: String?): PairProblem = when (code) {
        "bad_pin" -> PairProblem(
            "That PIN didn't match",
            "Check the 6 digits shown on your PC and try again. After 5 wrong tries the PC closes pairing; open it again there to get a new PIN.",
        )
        "rejected_by_user" -> PairProblem("Pairing was declined on the PC", "If that was a mistake, enter the PIN again and click Allow on the PC.")
        "pairing_closed" -> PairProblem(
            "Pairing isn't open on the PC",
            "PINs expire after 2 minutes. On your PC, click Open pairing to show a new PIN, then enter it here.",
        )
        "rate_limited" -> PairProblem("Too many attempts", "Wait a few seconds, then try again.")
        "revoked" -> PairProblem("This PC no longer trusts this phone", "It was removed on the PC. Pair again with a new PIN.")
        null -> if (fallback.isNullOrBlank()) PairProblem("Pairing failed", "Try again.") else PairProblem("Pairing failed", fallback)
        else -> PairProblem("Pairing failed", fallback ?: code)
    }
}

/** Startup / auto-connect decisions (pure, unit-tested). */
object Startup {
    /** Returning users (a PC was used before) land on Home; first-run users see onboarding. */
    fun initialScreen(lastHost: String): Screen = if (lastHost.isBlank()) Screen.Welcome else Screen.Home

    /** Home connects to the last PC on its own unless the user explicitly disconnected or a connection is already underway. */
    fun shouldAutoConnect(screen: Screen, state: ConnState, lastHost: String, userDisconnected: Boolean): Boolean =
        screen == Screen.Home && state is ConnState.Idle && lastHost.isNotBlank() && !userDisconnected
}

object DiscoveryHelp {
    /** Troubleshooting tips appear only after the scan had a fair chance (broadcasts go out every second). */
    const val HELP_AFTER_MS = 6_000L

    fun showHelp(elapsedMs: Long, found: Int): Boolean = found == 0 && elapsedMs >= HELP_AFTER_MS
}

/** Short connection summary used by Home and the gamepad HUD. */
data class ConnSummary(val tone: Tone, val label: String, val detail: String?)

object ConnText {
    fun summarize(state: ConnState, m: Metrics): ConnSummary = when (state) {
        is ConnState.Connected -> ConnSummary(Tone.Ok, "Connected", m.rttMs?.let { "$it ms" })
        is ConnState.Connecting -> ConnSummary(Tone.Busy, "Connecting…", null)
        is ConnState.Reconnecting -> ConnSummary(Tone.Warn, "Reconnecting…", state.reason)
        is ConnState.NeedsPairing -> ConnSummary(Tone.Warn, "Pairing needed", null)
        is ConnState.Pairing -> ConnSummary(Tone.Busy, "Waiting for approval", null)
        is ConnState.Failed -> ConnSummary(Tone.Error, "Can't connect", state.message)
        is ConnState.Idle -> ConnSummary(Tone.Neutral, "Not connected", null)
    }

    /** Plain-language link details for the expanded HUD ("---" when a value was not measured). */
    fun linkDetails(m: Metrics, transport: String): String {
        val rtt = m.rttMs?.let { "$it ms" } ?: "---"
        val rate = m.sendRateHz?.let { "$it/s" } ?: "---"
        val heard = m.lastPeerPacketAgeMs?.let { if (it < 1000) "just now" else "${it / 1000} s ago" } ?: "---"
        return "$transport · latency $rtt · updates $rate · PC heard $heard"
    }
}
