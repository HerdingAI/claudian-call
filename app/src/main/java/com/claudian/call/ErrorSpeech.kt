package com.claudian.call

enum class FailureClass {
    BRIDGE_UNREACHABLE,
    AUTH_REVOKED,
    STT_FAILED,
    TTS_FAILED,
    JOB_FAILED,
    QUOTA_EXHAUSTED,
    TIMEOUT
}

object ErrorSpeech {
    fun classify(t: Throwable): FailureClass {
        val msg = t.message.orEmpty().lowercase()
        return when {
            msg.contains("401") || msg.contains("unauthorized") || msg.contains("403") -> FailureClass.AUTH_REVOKED
            msg.contains("quota") || msg.contains("rate limit") || msg.contains("429") -> FailureClass.QUOTA_EXHAUSTED
            msg.contains("timeout") || msg.contains("timed out") -> FailureClass.TIMEOUT
            msg.contains("stt") || msg.contains("transcrib") -> FailureClass.STT_FAILED
            msg.contains("tts") || msg.contains("kokoro") -> FailureClass.TTS_FAILED
            msg.contains("connect") || msg.contains("unreachable") || msg.contains("failed to connect") || msg.contains("unknownhost") -> FailureClass.BRIDGE_UNREACHABLE
            else -> FailureClass.JOB_FAILED
        }
    }

    fun spokenMessage(cls: FailureClass, detail: String = ""): String {
        val clean = detail.trim().take(100)
        return when (cls) {
            FailureClass.BRIDGE_UNREACHABLE -> "I could not reach your computer's bridge. Check Tailscale or Wi-Fi."
            FailureClass.AUTH_REVOKED -> "Your device token was rejected or revoked. Please re-pair in Settings."
            FailureClass.STT_FAILED -> "Speech transcription failed. Please try saying that again."
            FailureClass.TTS_FAILED -> "Voice synthesis failed, falling back to device speech."
            FailureClass.QUOTA_EXHAUSTED -> "Your model quota or rate limit was reached."
            FailureClass.TIMEOUT -> "The request timed out before finishing."
            FailureClass.JOB_FAILED -> if (clean.isNotBlank()) "The job failed: $clean" else "The job failed."
        }
    }
}
