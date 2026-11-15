package com.claudian.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsMigrationTest {
    @Test fun currentVersionIsTwo() = assertEquals(2, PrefsMigration.CURRENT_PREFS_VERSION)

    @Test fun v1LowVadValuesAreRaised() {
        val r = PrefsMigration.migrate(1, 900L, 300L, true)
        assertEquals(1800L, r.vadSilenceMs)
        assertEquals(600L, r.minSpeechMs)
        assertFalse(r.bargeIn)
    }

    @Test fun v1ValuesAtThresholdAreKept() {
        val r = PrefsMigration.migrate(1, 1200L, 500L, false)
        assertEquals(1200L, r.vadSilenceMs)
        assertEquals(500L, r.minSpeechMs)
    }

    @Test fun v1HighValuesKeptButBargeInForcedOff() {
        val r = PrefsMigration.migrate(1, 2500L, 900L, true)
        assertEquals(2500L, r.vadSilenceMs)
        assertEquals(900L, r.minSpeechMs)
        assertFalse(r.bargeIn)
    }

    @Test fun justBelowThresholdsAreRaised() {
        val r = PrefsMigration.migrate(1, 1199L, 499L, false)
        assertEquals(1800L, r.vadSilenceMs)
        assertEquals(600L, r.minSpeechMs)
    }

    @Test fun v0IsTreatedLikeV1() {
        assertEquals(1800L, PrefsMigration.migrate(0, 100L, 100L, false).vadSilenceMs)
    }

    @Test fun v2IsUntouchedIncludingBargeIn() {
        val r = PrefsMigration.migrate(2, 400L, 100L, true)
        assertEquals(400L, r.vadSilenceMs)
        assertEquals(100L, r.minSpeechMs)
        assertTrue(r.bargeIn)
    }

    @Test fun futureVersionIsUntouched() {
        val r = PrefsMigration.migrate(9, 400L, 100L, true)
        assertEquals(400L, r.vadSilenceMs)
        assertTrue(r.bargeIn)
    }
}
