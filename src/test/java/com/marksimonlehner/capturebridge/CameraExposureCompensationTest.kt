package com.marksimonlehner.capturebridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraExposureCompensationTest {
    @Test
    fun isAcceptedHighSpeedAeCompensationEv_acceptsFiniteValuesWithinTenEv() {
        listOf(0.0, -2.0, -1.0 / 3.0, 10.0, -10.0).forEach { assertTrue(isAcceptedHighSpeedAeCompensationEv(it)) }
        listOf(10.000001, -10.000001, 11.0, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY)
            .forEach { assertFalse(isAcceptedHighSpeedAeCompensationEv(it)) }
    }

    @Test
    fun autoHighSpeedAeCompensationEv_matchesRequestedExposureProduct() {
        // Requested ISO 100 at 4 ms; auto exposure chose ISO 400 at 4 ms without compensation.
        assertEquals(-2.0, autoHighSpeedAeCompensationEv(100.0, 0.004, 400.0, 0.004, 0.0)!!, 1e-9)
        // Already at -2 EV the measured ISO 100 is the target, so the answer stays -2 EV.
        assertEquals(-2.0, autoHighSpeedAeCompensationEv(100.0, 0.004, 100.0, 0.004, -2.0)!!, 1e-9)
        // ISO 800 at 1/2000 s asks for 8x less light than ISO 100 at 1/250 s would give ISO 400 at 4 ms.
        assertEquals(
            Math.log(0.4 / 1.6) / Math.log(2.0),
            autoHighSpeedAeCompensationEv(800.0, 0.0005, 400.0, 0.004, 0.0)!!,
            1e-9
        )
        // A brighter request gives a positive EV.
        assertEquals(
            Math.log((800.0 / 240.0) / 1.6) / Math.log(2.0),
            autoHighSpeedAeCompensationEv(800.0, 1.0 / 240.0, 400.0, 0.004, 0.0)!!,
            1e-9
        )
    }

    @Test
    fun autoHighSpeedAeCompensationEv_rejectsUnusableInputs() {
        assertNull(autoHighSpeedAeCompensationEv(0.0, 0.004, 400.0, 0.004, 0.0))
        assertNull(autoHighSpeedAeCompensationEv(100.0, -0.004, 400.0, 0.004, 0.0))
        assertNull(autoHighSpeedAeCompensationEv(100.0, 0.004, Double.NaN, 0.004, 0.0))
        assertNull(autoHighSpeedAeCompensationEv(100.0, 0.004, 400.0, 0.0, 0.0))
        assertNull(autoHighSpeedAeCompensationEv(100.0, 0.004, 400.0, 0.004, Double.POSITIVE_INFINITY))
    }

    @Test
    fun aeCompensationIndexForEv_roundsAndClampsTenthEvSteps() {
        assertEquals(-20, aeCompensationIndexForEv(-2.0, 1, 10, -20, 20))
        assertEquals(-20, aeCompensationIndexForEv(-3.0, 1, 10, -20, 20))
        assertEquals(0, aeCompensationIndexForEv(0.04, 1, 10, -20, 20))
        assertEquals(13, aeCompensationIndexForEv(1.25, 1, 10, -20, 20))
        assertEquals(-3, aeCompensationIndexForEv(-0.3, 1, 10, -20, 20))
    }

    @Test
    fun aeCompensationIndexForEv_handlesCoarserSteps() {
        assertEquals(-12, aeCompensationIndexForEv(-2.0, 1, 6, -12, 12))
        assertEquals(-2, aeCompensationIndexForEv(-0.3, 1, 6, -12, 12))
        assertEquals(-6, aeCompensationIndexForEv(-2.0, 1, 3, -6, 6))
    }

    @Test
    fun aeCompensationIndexForEv_returnsZeroWhenUnsupportedOrInvalid() {
        assertEquals(0, aeCompensationIndexForEv(-2.0, 1, 10, 0, 0))
        assertEquals(0, aeCompensationIndexForEv(Double.NaN, 1, 10, -20, 20))
        assertEquals(0, aeCompensationIndexForEv(Double.POSITIVE_INFINITY, 1, 10, -20, 20))
        assertEquals(0, aeCompensationIndexForEv(Double.NEGATIVE_INFINITY, 1, 10, -20, 20))
        assertEquals(0, aeCompensationIndexForEv(-2.0, 0, 10, -20, 20))
        assertEquals(0, aeCompensationIndexForEv(-2.0, 1, 0, -20, 20))
        assertEquals(0, aeCompensationIndexForEv(-2.0, -1, 10, -20, 20))
        assertEquals(0, aeCompensationIndexForEv(-2.0, 1, 10, 20, -20))
    }

    @Test
    fun aeCompensationEvForIndex_invertsIndexForEv() {
        assertEquals(-2.0, aeCompensationEvForIndex(-20, 1, 10), 1e-9)
        assertEquals(-2.0, aeCompensationEvForIndex(-12, 1, 6), 1e-9)
        assertEquals(-2.0, aeCompensationEvForIndex(-6, 1, 3), 1e-9)
        assertEquals(1.3, aeCompensationEvForIndex(13, 1, 10), 1e-9)
        assertEquals(0.0, aeCompensationEvForIndex(5, 0, 10), 0.0)
        for (index in -20..20) {
            assertEquals(index, aeCompensationIndexForEv(aeCompensationEvForIndex(index, 1, 10), 1, 10, -20, 20))
        }
        for (index in -12..12) {
            assertEquals(index, aeCompensationIndexForEv(aeCompensationEvForIndex(index, 1, 6), 1, 6, -12, 12))
        }
    }

    @Test
    fun formatAeCompensationEv_showsSignedTenths() {
        assertEquals("-2.0 EV", formatAeCompensationEv(-2.0))
        assertEquals("0.0 EV", formatAeCompensationEv(0.0))
        assertEquals("+1.0 EV", formatAeCompensationEv(1.0))
        assertEquals("-0.3 EV", formatAeCompensationEv(-1.0 / 3.0))
        assertEquals("0.0 EV", formatAeCompensationEv(-0.04))
        assertEquals("0.0 EV", formatAeCompensationEv(Double.NaN))
    }
}
