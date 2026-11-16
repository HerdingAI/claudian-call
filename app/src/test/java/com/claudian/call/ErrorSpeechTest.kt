package com.claudian.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ErrorSpeechTest {
    @Test
    fun everyFailureClassProducesNonEmptySpokenMessage() {
        for (cls in FailureClass.values()) {
            val msg = ErrorSpeech.spokenMessage(cls, "sample detail")
            assertTrue("Missing spoken message for $cls", msg.isNotBlank())
        }
        assertEquals(FailureClass.AUTH_REVOKED, ErrorSpeech.classify(IOException("HTTP 401")))
        assertEquals(FailureClass.QUOTA_EXHAUSTED, ErrorSpeech.classify(IOException("HTTP 429 quota exceeded")))
        assertEquals(FailureClass.TIMEOUT, ErrorSpeech.classify(IOException("Socket timed out")))
        assertEquals(FailureClass.BRIDGE_UNREACHABLE, ErrorSpeech.classify(IOException("Failed to connect to /100.1.2.3:8790")))
    }
}
