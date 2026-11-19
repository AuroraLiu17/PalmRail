package com.palmrail.singlerail.domain

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

data class CrankTuning(val turns: Int, val rollback: Double, val eject: Double,
                       val maxDelta: Double)
enum class Grip { OPEN, HELD, LOST }
data class CrankInput(val grip: Grip, val angle: Double? = null)

/** One attempt owns its crank position. Angle zero is the grip on the right. */
class Crank(private val tuning: CrankTuning) {
    var stroke = 0.0
        private set
    var frozen = false
        private set
    var atOutlet = false
        private set
    private var lastAngle: Double? = null
    private var returned = false
    private var rollbackArmed = false
    private val radiansPerStroke = tuning.turns * 2 * PI
    var radians = 0.0
        private set

    fun advance(dt: Double, input: CrankInput, admitted: Boolean, outletContact: Boolean) {
        if (input.grip == Grip.LOST) { pause(); return }
        if (frozen) return
        atOutlet = atOutlet || outletContact
        when (input.grip) {
            Grip.HELD -> input.angle?.let { dragTo(it, admitted) }
            Grip.OPEN -> {
                resetGripBaseline()
                if (admitted && !atOutlet && !returned && rollbackArmed) {
                    val previous = stroke
                    stroke = (stroke - tuning.rollback * dt).coerceAtLeast(-tuning.eject)
                    radians += (stroke - previous) * radiansPerStroke
                    returned = stroke <= -tuning.eject
                }
            }
            Grip.LOST -> Unit
        }
    }

    fun dragTo(angle: Double, admitted: Boolean) {
        if (frozen) return
        val previous = lastAngle
        lastAngle = angle
        if (previous == null) return
        val delta = atan2(sin(angle - previous), cos(angle - previous))
        if (abs(delta) > tuning.maxDelta) return
        radians += delta
        if (!admitted || atOutlet || returned) return
        // Only automatic return can move below the lower stop to release the ball.
        stroke = (stroke + delta / radiansPerStroke).coerceIn(minOf(stroke, 0.0), 1.0)
        rollbackArmed = rollbackArmed || stroke > 0
    }

    fun resetLift() {
        stroke = 0.0
        atOutlet = false
        returned = false
        rollbackArmed = false
    }

    fun resetGripBaseline() { lastAngle = null }
    fun pause() { frozen = true; resetGripBaseline() }
    fun resume(tracked: Boolean) { if (tracked) frozen = false }
}
