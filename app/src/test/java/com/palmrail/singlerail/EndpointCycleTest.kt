package com.palmrail.singlerail

import com.palmrail.singlerail.domain.EndpointCycle
import org.junit.Assert.*
import org.junit.Test

class EndpointCycleTest {
    @Test fun actualGoalLandingPlaysOnceWithoutCompletingGoal() {
        val cycle = EndpointCycle()
        assertFalse(cycle.observeLanding(true, true))
        cycle.release()
        assertFalse(cycle.observeLanding(false, true))
        assertFalse(cycle.observeLanding(true, false))
        assertTrue(cycle.observeLanding(true, true))
        repeat(20) { assertFalse(cycle.observeLanding(true, true)) }
        assertFalse(cycle.struck)
        assertFalse(cycle.entered)
        val next = EndpointCycle()
        next.release()
        assertTrue(next.observeLanding(true, true))
    }

    @Test fun gateReleaseAndQualifiedPlateStrikeAreOneShot() {
        val cycle = EndpointCycle()
        assertFalse(cycle.observe(true, true, true))
        assertTrue(cycle.release())
        assertFalse(cycle.release())
        assertFalse(cycle.observe(false, true, true))
        assertFalse(cycle.entered)
        assertFalse(cycle.observe(true, false, true))
        assertFalse(cycle.observe(true, true, false))
        assertTrue(cycle.entered)
        assertTrue(cycle.observe(true, false, true))
        assertFalse(cycle.observe(true, true, true))
        assertTrue(cycle.struck)
    }
}
