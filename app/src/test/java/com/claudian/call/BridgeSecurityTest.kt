package com.claudian.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BridgeSecurityTest {
    @Test
    fun allowsHttpsAndPrivateOrTailscaleHttp() {
        assertTrue(BridgeSecurity.isAllowedBridgeUrl("https://bridge.example.com:8790"))
        assertTrue(BridgeSecurity.isAllowedBridgeUrl("http://127.0.0.1:8790"))
        assertTrue(BridgeSecurity.isAllowedBridgeUrl("http://localhost:8790"))
        assertTrue(BridgeSecurity.isAllowedBridgeUrl("http://192.168.1.50:8790"))
        assertTrue(BridgeSecurity.isAllowedBridgeUrl("http://10.0.0.12:8790"))
        assertTrue(BridgeSecurity.isAllowedBridgeUrl("http://172.16.5.4:8790"))
        assertTrue(BridgeSecurity.isAllowedBridgeUrl("http://100.117.208.101:8790"))
        assertTrue(BridgeSecurity.isAllowedBridgeUrl("http://workstation.ts.net:8790"))
    }

    @Test
    fun rejectsPublicCleartextHttp() {
        assertFalse(BridgeSecurity.isAllowedBridgeUrl("http://example.com:8790"))
        assertFalse(BridgeSecurity.isAllowedBridgeUrl("http://8.8.8.8:8790"))
        assertFalse(BridgeSecurity.isAllowedBridgeUrl("http://100.128.0.1:8790"))
        assertFalse(BridgeSecurity.isAllowedBridgeUrl("ftp://192.168.1.1:8790"))
    }

    @Test
    fun sha256HexComputesKnownDigest() {
        val tmp = File.createTempFile("claudian-sha-", ".bin")
        try {
            tmp.writeBytes("abc".toByteArray())
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", BridgeSecurity.sha256Hex(tmp))
        } finally {
            tmp.delete()
        }
    }
}
