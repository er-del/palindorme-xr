package com.example.xr

import com.example.xr.companion.CompanionNetworkSecurity
import com.example.xr.companion.CompanionProtocol
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Interop vectors shared with the PC companion server (pc-companion/companion_server.py).
 *
 * If any of these fail, the Python server and the phone will silently fail to pair,
 * so the expected values are pinned here rather than recomputed on both sides.
 */
class CompanionInteropVectorTest {

    private val pairingCode = "123456"
    private val clientNonce = "aabbccddeeff00112233445566778899"
    private val serverNonce = "00112233445566778899aabbccddeeff"

    @Test
    fun derivedSessionKeyMatchesPcServer() {
        val key = CompanionNetworkSecurity.deriveSessionKey(pairingCode, clientNonce, serverNonce)
        assertEquals(
            "1a7249599d79ac981f4521af8faed08c6a017e7fcd23b6c4929531e782c3079a",
            key.joinToString("") { "%02x".format(it) }
        )
    }

    @Test
    fun authProofsMatchPcServer() {
        val key = CompanionNetworkSecurity.deriveSessionKey(pairingCode, clientNonce, serverNonce)

        val serverProof = CompanionNetworkSecurity.computeAuthProof(key, "SERVER:$clientNonce:$serverNonce")
        assertEquals(
            "86795cefecace19af09ab0dd9f11a80d8e2daa2e960f60a3d0330f4279d768fc",
            serverProof
        )

        val clientProof = CompanionNetworkSecurity.computeAuthProof(key, "CLIENT:$serverNonce:$clientNonce")
        assertEquals(
            "0a0ffe99f55d6aa173a63e0643429fbbaddea592e7a678f04f495a6f1a579eec",
            clientProof
        )
    }

    /**
     * The Python server unpacks the UDP header as ">HBBiqIi" (24 bytes big-endian).
     * This pins the exact byte layout that format depends on.
     */
    @Test
    fun udpHeaderLayoutMatchesPcStructFormat() {
        val packet = CompanionProtocol.UdpPacket(
            type = CompanionProtocol.PKT_CAMERA_H264,
            flags = 0,
            seq = 7,
            timestampNs = 0x0102030405060708L,
            payload = byteArrayOf(0x11, 0x22)
        )
        val encoded = CompanionProtocol.encodeUdpPacket(packet)

        assertEquals(CompanionProtocol.HEADER_BYTES + 2, encoded.size)

        // magic 'PX' = 0x50 0x58
        assertEquals(0x50.toByte(), encoded[0])
        assertEquals(0x58.toByte(), encoded[1])
        // type, flags
        assertEquals(1.toByte(), encoded[2])
        assertEquals(0.toByte(), encoded[3])
        // seq = 7 big-endian 4 bytes
        assertArrayEquals(byteArrayOf(0, 0, 0, 7), encoded.copyOfRange(4, 8))
        // timestampNs big-endian 8 bytes
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8),
            encoded.copyOfRange(8, 16)
        )
        // payloadLen big-endian 4 bytes = 2
        assertArrayEquals(byteArrayOf(0, 0, 0, 2), encoded.copyOfRange(16, 20))
        // reserved 4 bytes = 0
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), encoded.copyOfRange(20, 24))
        // payload
        assertArrayEquals(byteArrayOf(0x11, 0x22), encoded.copyOfRange(24, 26))
    }
}
