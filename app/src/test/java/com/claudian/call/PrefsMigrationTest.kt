package com.claudian.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrefsMigrationTest {
    @Test fun currentVersionIsThree() = assertEquals(3, PrefsMigration.CURRENT_PREFS_VERSION)

    @Test fun v1LowVadValuesAreRaisedAndHighMinSpeechLowered() {
        val r = PrefsMigration.migrate(1, 900L, 600L, true)
        assertEquals(1800L, r.vadSilenceMs)
        assertEquals(200L, r.minSpeechMs)
        assertFalse(r.bargeIn)
    }

    @Test fun v1ValuesAtThresholdAreKept() {
        val r = PrefsMigration.migrate(1, 1200L, 300L, false)
        assertEquals(1200L, r.vadSilenceMs)
        assertEquals(300L, r.minSpeechMs)
    }

    @Test fun v1HighVadKeptButBargeInForcedOff() {
        val r = PrefsMigration.migrate(1, 2500L, 250L, true)
        assertEquals(2500L, r.vadSilenceMs)
        assertEquals(250L, r.minSpeechMs)
        assertFalse(r.bargeIn)
    }

    @Test fun justBelowThresholdsAreAdjusted() {
        val r = PrefsMigration.migrate(1, 1199L, 100L, false)
        assertEquals(1800L, r.vadSilenceMs)
        assertEquals(200L, r.minSpeechMs)
    }

    @Test fun v0IsTreatedLikeV1() {
        assertEquals(1800L, PrefsMigration.migrate(0, 100L, 100L, false).vadSilenceMs)
    }

    @Test fun v3IsUntouchedIncludingBargeIn() {
        val r = PrefsMigration.migrate(3, 400L, 100L, true)
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
