package com.claudian.call

import org.junit.Assert.assertEquals
import org.junit.Test

class ApprovalClassifierTest {
    @Test
    fun classifiesAllowDenyRepeatAndAmbiguous() {
        assertEquals(ApprovalIntent.ALLOW, ApprovalClassifier.classify("Yes, go ahead!"))
        assertEquals(ApprovalIntent.ALLOW, ApprovalClassifier.classify("allow"))
        assertEquals(ApprovalIntent.DENY, ApprovalClassifier.classify("No, stop."))
        assertEquals(ApprovalIntent.DENY, ApprovalClassifier.classify("deny"))
        assertEquals(ApprovalIntent.REPEAT, ApprovalClassifier.classify("say again?"))
        assertEquals(ApprovalIntent.AMBIGUOUS, ApprovalClassifier.classify("maybe check the file first"))
    }
}
