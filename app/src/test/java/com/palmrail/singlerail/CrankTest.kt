package com.palmrail.singlerail

import com.palmrail.singlerail.domain.*
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.PI

class CrankTest {
    private fun crank() = Crank(CrankTuning(4, .12, .185, 2.0))
    private fun Crank.held(angle: Double, admitted: Boolean = true, outlet: Boolean = false) =
        advance(.1, CrankInput(Grip.HELD, angle), admitted, outlet)
    private fun Crank.open() = advance(.1, CrankInput(Grip.OPEN), true, false)

    @Test fun emptyCrankRotatesWithoutMovingTheLiftingStroke() {
        val crank = crank()
        crank.held(0.0, admitted = false)
        crank.held(1.0, admitted = false)
        assertEquals(1.0, crank.radians, 1e-12)
        assertEquals(0.0, crank.stroke, 1e-12)
        crank.held(0.4, admitted = false)
        assertEquals(0.4, crank.radians, 1e-12)
        crank.open()
        assertEquals(0.4, crank.radians, 1e-12)
        crank.held(1.0)
        crank.held(1.2)
        assertEquals(0.6, crank.radians, 1e-12)
        assertEquals(0.2 / (8 * PI), crank.stroke, 1e-12)
    }

    @Test fun stopAndRegraspDoNotJump() {
        val crank = crank()
        crank.held(0.0); crank.held(1.0)
        val raised = crank.stroke
        crank.held(1.0)
        assertEquals(raised, crank.stroke, 1e-12)
        crank.open()
        assertEquals(raised-.012, crank.stroke, 1e-12)
        val lower = crank.stroke
        crank.held(-1.0)
        assertEquals(lower, crank.stroke, 1e-12)
        crank.held(-.5)
        assertTrue(crank.stroke > lower)
    }

    @Test fun nativeReacquisitionBeforeNextTickResetsAngularBaseline() {
        val crank = crank()
        crank.held(0.0)
        crank.held(.8)
        val before = crank.stroke
        // Native up and new down can both arrive before the next solver sample.
        crank.resetGripBaseline()
        crank.held(1.7)
        assertEquals(before, crank.stroke, 1e-12)
        crank.held(1.9)
        assertTrue(crank.stroke > before)
    }

    @Test fun crankKeepsFollowingBothWaysAtEndOfTravelAndAfterOutlet() {
        val crank = crank()
        crank.held(0.0)
        for (sample in 1..30) crank.held(sample.toDouble())
        assertEquals(1.0, crank.stroke, 1e-12)
        assertEquals(30.0, crank.radians, 1e-12)
        crank.held(29.0)
        assertTrue(crank.stroke < 1.0)
        crank.held(29.0, outlet = true)
        val stroke = crank.stroke
        crank.held(28.0)
        assertEquals(28.0, crank.radians, 1e-12)
        assertEquals(stroke, crank.stroke, 1e-12)
    }

    @Test fun completeOutletLatchesThroughRelease() {
        val crank = crank()
        crank.held(0.0); crank.held(1.0); crank.held(1.0, outlet=true)
        val stroke = crank.stroke
        repeat(50) { crank.open() }
        assertTrue(crank.atOutlet)
        assertEquals(stroke, crank.stroke, 1e-12)
    }

    @Test fun lossFreezesUntilTrackedManualResume() {
        val crank = crank()
        crank.held(0.0); crank.held(1.0)
        crank.advance(.1, CrankInput(Grip.LOST), true, false)
        val stroke = crank.stroke
        crank.resume(false)
        crank.open()
        assertTrue(crank.frozen)
        assertEquals(stroke, crank.stroke, 1e-12)
        crank.resume(true)
        crank.open()
        assertTrue(crank.stroke < stroke)
    }

    @Test fun crossingAtanBoundaryWorksInBothDirections() {
        val crank = crank()
        crank.held(PI-.1); crank.held(-PI+.1)
        assertEquals(.2/(8*PI), crank.stroke, 1e-12)
        crank.held(PI-.1)
        assertEquals(0.0, crank.stroke, 1e-12)
    }

    @Test fun dragEventsKeepTheirAngleAcrossSeveralFastTurnsWithoutATick() {
        val crank = crank()
        fun drag(angle: Double) = crank.dragTo(kotlin.math.atan2(kotlin.math.sin(angle), kotlin.math.cos(angle)), true)
        drag(.4)
        for (sample in 1..20) {
            drag(.4 + sample * .9)
            assertEquals(sample * .9, crank.radians, 1e-12)
        }
        for (sample in 19 downTo 1) {
            drag(.4 + sample * .9)
            assertEquals(sample * .9, crank.radians, 1e-12)
        }
    }

    @Test fun manualReverseStopsAtEntryAndReleaseCanStillEject() {
        val crank = crank()
        crank.held(0.0); crank.held(1.0); crank.held(-.2)
        assertEquals(0.0, crank.stroke, 1e-12)
        crank.open()
        assertEquals(-.012, crank.stroke, 1e-12)
        crank.held(0.0); crank.held(-.5)
        assertEquals(-.012, crank.stroke, 1e-12)
        crank.held(0.0)
        assertTrue(crank.stroke > 0.0)
    }

    @Test fun rollbackCompletesWithoutLiftingAnEjectedBall() {
        val crank = crank()
        crank.held(0.0); crank.held(.8)
        repeat(100) { crank.open() }
        assertEquals(-.185, crank.stroke, 1e-12)
        crank.held(0.0); crank.held(.8)
        assertEquals(-.185, crank.stroke, 1e-12)
    }
}
