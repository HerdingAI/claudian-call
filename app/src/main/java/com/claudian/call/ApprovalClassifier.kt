package com.claudian.call

enum class ApprovalIntent {
    ALLOW,
    DENY,
    REPEAT,
    AMBIGUOUS
}

object ApprovalClassifier {
    private val allowWords = setOf("yes", "yeah", "yep", "yup", "allow", "approve", "go ahead", "sure", "ok", "okay", "do it", "proceed")
    private val denyWords = setOf("no", "nope", "nah", "deny", "reject", "stop", "cancel", "don't", "dont", "abort", "never")
    private val repeatWords = setOf("repeat", "what", "say again", "pardon", "huh", "again")

    fun classify(rawUtterance: String): ApprovalIntent {
        val norm = rawUtterance.trim().lowercase().replace(Regex("[^a-z0-9'\\s]"), "").replace(Regex("\\s+"), " ")
        if (norm.isBlank()) return ApprovalIntent.AMBIGUOUS
        if (norm in allowWords || allowWords.any { norm == it || norm.startsWith("$it ") }) {
            if (denyWords.none { norm.contains(it) }) return ApprovalIntent.ALLOW
        }
        if (norm in denyWords || denyWords.any { norm == it || norm.startsWith("$it ") }) {
            return ApprovalIntent.DENY
        }
        if (norm in repeatWords || repeatWords.any { norm.contains(it) }) {
            return ApprovalIntent.REPEAT
        }
        return ApprovalIntent.AMBIGUOUS
    }
}
