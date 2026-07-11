package com.marksimonlehner.capturebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptureProtocolTest {
    @Test
    fun parseCaptureCommand_preservesPayloadAndTrimsOuterWhitespace() {
        assertEquals(
            ParsedCaptureCommand(
                text = "SETTINGS {\"width\":1920}",
                head = "SETTINGS",
                payload = "{\"width\":1920}",
            ),
            parseCaptureCommand("  SETTINGS {\"width\":1920}  "),
        )
        assertEquals(
            ParsedCaptureCommand(text = "STOP", head = "STOP", payload = ""),
            parseCaptureCommand("STOP"),
        )
    }

    @Test
    fun protocolFieldParsers_acceptCurrentTextFormatAndRejectInvalidTargets() {
        assertEquals(
            mapOf("seq" to "7", "hub_tx_ns" to "123"),
            parseProtocolFields("seq=7 ignored hub_tx_ns=123"),
        )
        assertEquals(123456L, parsePhoneElapsedNs("phone_elapsed_ns=123456"))
        assertNull(parsePhoneElapsedNs("phone_elapsed_ns=0"))
        assertNull(parsePhoneElapsedNs("not_a_target"))
        assertEquals(750L, parsePrerollMs("750"))
    }

    @Test
    fun batteryProtocolLine_usesStableNormalizedFields() {
        val snapshot = BatterySnapshot(
            levelPct = 87,
            status = BatteryChargeStatus.Charging,
            plugged = BatteryPluggedSource.Usb,
        )

        assertEquals(
            "BATTERY level_pct=87 status=charging plugged=usb",
            snapshot.toProtocolLine(),
        )
    }

    @Test
    fun normalizeBatterySnapshot_scalesLevelAndMapsKnownCodes() {
        assertEquals(
            BatterySnapshot(
                levelPct = 86,
                status = BatteryChargeStatus.Full,
                plugged = BatteryPluggedSource.Ac,
            ),
            normalizeBatterySnapshot(
                level = 43,
                scale = 50,
                statusCode = 5,
                pluggedCode = 1,
            ),
        )
        assertNull(normalizeBatterySnapshot(level = -1, scale = 100, statusCode = 1, pluggedCode = 0))
    }

    @Test
    fun reconnectDelay_isExponentialAndCappedAtEightSeconds() {
        assertEquals(
            listOf(1L, 1L, 2L, 4L, 8L, 8L, 8L),
            listOf(-1, 0, 1, 2, 3, 4, 20).map(::reconnectDelaySeconds),
        )
    }
}
