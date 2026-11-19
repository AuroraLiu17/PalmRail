package com.palmrail.singlerail

import com.palmrail.singlerail.domain.StartDwell
import org.junit.Assert.*
import org.junit.Test

class StartDwellTest {
    private fun dwell() = StartDwell(400.0, 1000.0, 120.0, 250.0, 150.0)

    @Test fun waitThenRingThenExactlyOneRelease() {
        val state = dwell()
        for (time in 0..300 step 100) assertFalse(state.sample(time.toDouble(), true).ring)
        assertEquals(StartDwell.View(true, 0.0), state.sample(400.0, true))
        for (time in 500..1300 step 100) assertFalse(state.sample(time.toDouble(), true).press)
        assertTrue(state.sample(1400.0, true).press)
        for (time in 1500..3500 step 100) assertFalse(state.sample(time.toDouble(), true).press)
    }

    @Test fun shortLookAwayPausesAndLongLookAwayClears() {
        val state = dwell()
        for (time in 0..700 step 100) state.sample(time.toDouble(), true)
        assertFalse(state.sample(735.0, false).ring)
        assertEquals(.30, state.sample(795.0, true).progress, 0.001)
        state.sample(840.0, false)
        assertFalse(state.sample(930.0, true).ring)
    }

    @Test fun manualPressRequiresValidNeutralTime() {
        val state = dwell()
        state.lock()
        state.sample(0.0, false)
        state.sample(100.0, false)
        state.cancel()
        for (time in 200..1800 step 100) assertFalse(state.sample(time.toDouble(), true).press)
        for (time in 1900..2200 step 100) state.sample(time.toDouble(), false)
        for (time in 2300..3600 step 100) assertFalse(state.sample(time.toDouble(), true).press)
        assertTrue(state.sample(3700.0, true).press)
    }

    @Test fun missingFrameCannotSkipProgress() {
        val state = dwell()
        for (time in 0..1300 step 100) assertFalse(state.sample(time.toDouble(), true).press)
        assertEquals(StartDwell.View(), state.sample(1500.0, true))
    }
}
