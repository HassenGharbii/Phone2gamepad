package com.phone2gamepad

import com.phone2gamepad.net.ConnState
import com.phone2gamepad.net.Metrics
import com.phone2gamepad.net.PcTarget
import com.phone2gamepad.ui.ConnText
import com.phone2gamepad.ui.DiscoveryHelp
import com.phone2gamepad.ui.PairErrors
import com.phone2gamepad.ui.PinInput
import com.phone2gamepad.ui.Startup
import com.phone2gamepad.ui.Tone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UxLogicTest {
    private val pc = PcTarget("PC", "192.168.1.2")

    @Test fun pinKeepsDigitsOnlyAndCapsAtSix() {
        assertEquals("123", PinInput.sanitize("123"))
        assertEquals("123456", PinInput.sanitize("1234567"))
        assertEquals("", PinInput.sanitize("abc"))
    }

    @Test fun pinPasteWithSpacesAndLabels() {
        assertEquals("123456", PinInput.sanitize("PIN: 123 456"))
        assertEquals("482913", PinInput.sanitize(" 482-913 \n"))
    }

    @Test fun pinCompleteOnlyAtSixDigits() {
        assertFalse(PinInput.isComplete("12345"))
        assertTrue(PinInput.isComplete("123456"))
        assertFalse(PinInput.isComplete("12345a"))
    }

    @Test fun firstRunGoesToOnboardingReturningUserToHome() {
        assertEquals(Screen.Welcome, Startup.initialScreen(""))
        assertEquals(Screen.Welcome, Startup.initialScreen("  "))
        assertEquals(Screen.Home, Startup.initialScreen("192.168.1.2"))
    }

    @Test fun autoConnectOnlyOnHomeWhenIdleAndNotUserDisconnected() {
        assertTrue(Startup.shouldAutoConnect(Screen.Home, ConnState.Idle, "h", userDisconnected = false))
        assertFalse(Startup.shouldAutoConnect(Screen.Home, ConnState.Idle, "h", userDisconnected = true))
        assertFalse(Startup.shouldAutoConnect(Screen.Home, ConnState.Idle, "", userDisconnected = false))
        assertFalse(Startup.shouldAutoConnect(Screen.Settings, ConnState.Idle, "h", userDisconnected = false))
        assertFalse(Startup.shouldAutoConnect(Screen.Home, ConnState.Connecting(pc), "h", userDisconnected = false))
        assertFalse(Startup.shouldAutoConnect(Screen.Home, ConnState.Connected(pc, 1), "h", userDisconnected = false))
        // A failed connection is not retried in a loop by Home; the user taps Connect.
        assertFalse(Startup.shouldAutoConnect(Screen.Home, ConnState.Failed(pc, "x"), "h", userDisconnected = false))
    }

    @Test fun discoveryHelpWaitsForTheScanAndHidesWhenSomethingIsFound() {
        assertFalse(DiscoveryHelp.showHelp(1_000, 0))
        assertTrue(DiscoveryHelp.showHelp(DiscoveryHelp.HELP_AFTER_MS, 0))
        assertFalse(DiscoveryHelp.showHelp(60_000, 1))
    }

    @Test fun pairErrorsHaveSpecificNextSteps() {
        assertTrue(PairErrors.describe("bad_pin", "x").detail.contains("5 wrong tries"))
        assertTrue(PairErrors.describe("pairing_closed", "x").detail.contains("Open pairing"))
        assertTrue(PairErrors.describe("rejected_by_user", "x").title.contains("declined"))
        assertTrue(PairErrors.describe("rate_limited", "x").title.contains("Too many"))
        assertTrue(PairErrors.describe("revoked", "x").detail.contains("Pair again"))
        assertEquals("boom", PairErrors.describe(null, "boom").detail)
        assertEquals("weird", PairErrors.describe("weird", null).detail)
    }

    @Test fun connectionSummaryUsesRealValuesOnly() {
        val connected = ConnText.summarize(ConnState.Connected(pc, 1), Metrics(rttMs = 6))
        assertEquals(Tone.Ok, connected.tone)
        assertEquals("6 ms", connected.detail)
        assertEquals(null, ConnText.summarize(ConnState.Connected(pc, 1), Metrics()).detail)
        assertEquals(Tone.Warn, ConnText.summarize(ConnState.Reconnecting(pc, 2, 1000, "lost"), Metrics()).tone)
        assertEquals(Tone.Error, ConnText.summarize(ConnState.Failed(pc, "bad"), Metrics()).tone)
        val d = ConnText.linkDetails(Metrics(), "Wi-Fi")
        assertTrue(d.contains("latency ---") && d.contains("updates ---") && d.contains("heard ---"))
        assertTrue(ConnText.linkDetails(Metrics(rttMs = 5, sendRateHz = 31, lastPeerPacketAgeMs = 300), "Wi-Fi").contains("PC heard just now"))
    }
}
