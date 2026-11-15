package com.claudian.call

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavEncoderTest {
    private fun str(b: ByteArray, off: Int) = String(b, off, 4, Charsets.US_ASCII)
    private fun le(b: ByteArray) = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

    @Test fun headerFieldsForDefaultRate() {
        val wav = BridgeApi.pcm16ToWav(shortArrayOf(1, -2, 3))
        assertEquals(44 + 6, wav.size)
        assertEquals("RIFF", str(wav, 0))
        assertEquals("WAVE", str(wav, 8))
        assertEquals("fmt ", str(wav, 12))
        assertEquals("data", str(wav, 36))
        val bb = le(wav)
        assertEquals(36 + 6, bb.getInt(4))
        assertEquals(16, bb.getInt(16))
        assertEquals(1, bb.getShort(20).toInt())      // PCM
        assertEquals(1, bb.getShort(22).toInt())      // mono
        assertEquals(16000, bb.getInt(24))
        assertEquals(32000, bb.getInt(28))            // byte rate
        assertEquals(2, bb.getShort(32).toInt())      // block align
        assertEquals(16, bb.getShort(34).toInt())     // bits
        assertEquals(6, bb.getInt(40))
    }

    @Test fun samplesAreLittleEndianAndPreserved() {
        val pcm = shortArrayOf(0, 1, -1, Short.MAX_VALUE, Short.MIN_VALUE, 0x1234)
        val wav = BridgeApi.pcm16ToWav(pcm)
        val bb = le(wav)
        for (i in pcm.indices) assertEquals(pcm[i], bb.getShort(44 + i * 2))
        assertArrayEquals(byteArrayOf(0x34, 0x12), wav.copyOfRange(44 + 10, 44 + 12))
    }

    @Test fun emptyPcmGivesHeaderOnly() {
        val wav = BridgeApi.pcm16ToWav(ShortArray(0))
        assertEquals(44, wav.size)
        assertEquals(36, le(wav).getInt(4))
        assertEquals(0, le(wav).getInt(40))
    }

    @Test fun customSampleRate() {
        val wav = BridgeApi.pcm16ToWav(shortArrayOf(5), 24000)
        assertEquals(24000, le(wav).getInt(24))
        assertEquals(48000, le(wav).getInt(28))
    }
}
