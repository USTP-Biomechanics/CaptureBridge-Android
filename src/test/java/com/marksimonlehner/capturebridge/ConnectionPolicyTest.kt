package com.marksimonlehner.capturebridge

import java.io.IOException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionPolicyTest {
    @Test
    fun parseDiscoveryResponse_readsHubPortAndFallsBackToDefaultPort() {
        assertEquals(6000, parseDiscoveryResponse("UDPCAMERA_OK 6000", 7000))
        assertEquals(6001, parseDiscoveryResponse("  UDPCAMERA_OK 6001\n", 6000))
        assertEquals(6000, parseDiscoveryResponse("UDPCAMERA_OK", 6000))
        assertEquals(6000, parseDiscoveryResponse("UDPCAMERA_OK 0", 6000))
        assertEquals(6000, parseDiscoveryResponse("UDPCAMERA_OK port", 6000))
        assertEquals(6000, parseDiscoveryResponse("UDPCAMERA_OK 65536", 6000))
    }

    @Test
    fun parseDiscoveryResponse_ignoresOtherDatagrams() {
        assertNull(parseDiscoveryResponse("", 6000))
        assertNull(parseDiscoveryResponse("DISCOVER_UDPCAMERA", 6000))
        assertNull(parseDiscoveryResponse("UDPCAMERA_OKAY 6000", 6000))
        assertNull(parseDiscoveryResponse("HELLO UDPCAMERA_OK 6000", 6000))
    }

    @Test
    fun classifyDiscoveryError_detectsMissingNetworkAlongCauseChain() {
        assertEquals(
            DiscoveryErrorKind.NoNetwork,
            classifyDiscoveryError(SocketException("sendto failed: ENETUNREACH (Network is unreachable)")),
        )
        assertEquals(
            DiscoveryErrorKind.NoNetwork,
            classifyDiscoveryError(IOException("Discovery send failed", SocketException("sendto failed: EHOSTUNREACH"))),
        )
        assertEquals(DiscoveryErrorKind.NoNetwork, classifyDiscoveryError(NoRouteToHostException()))
    }

    @Test
    fun classifyDiscoveryError_separatesBlockedFromOtherFailures() {
        assertEquals(
            DiscoveryErrorKind.Blocked,
            classifyDiscoveryError(SocketException("sendto failed: EPERM (Operation not permitted)")),
        )
        assertEquals(
            DiscoveryErrorKind.Other,
            classifyDiscoveryError(SocketException("connect failed: ECONNREFUSED (Connection refused)")),
        )
        assertEquals(DiscoveryErrorKind.Other, classifyDiscoveryError(SocketException()))
    }

    @Test
    fun discoveryProbeTargets_sendsSubnetBroadcastsFirstAndLimitedBroadcastLastOnce() {
        val limited = InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))
        val wifi = InetAddress.getByAddress(byteArrayOf(192.toByte(), 168.toByte(), 1, -1))
        val tether = InetAddress.getByAddress(byteArrayOf(10, 42, 0, -1))

        assertEquals(
            listOf(wifi, tether, limited),
            discoveryProbeTargets(listOf(wifi, tether, wifi), limited),
        )
        assertEquals(listOf(limited), discoveryProbeTargets(emptyList(), limited))
    }
}
