package com.palmrail.singlerail.domain

/** Attempt-local mechanism outcomes. The probe supplies observations from the dynamic ball. */
class EndpointCycle {
    var released = false
        private set
    var entered = false
        private set
    var struck = false
        private set

    fun release(): Boolean {
        if (released) return false
        released = true
        return true
    }

    private var landed = false

    /** Presentation cue only; landing does not complete the goal. */
    fun observeLanding(liftExited: Boolean, floorContact: Boolean): Boolean {
        if (!released || !liftExited || !floorContact || landed) return false
        landed = true
        return true
    }

    fun observe(liftExited: Boolean, fullySupported: Boolean, struckPlate: Boolean): Boolean {
        if (!released || !liftExited || struck) return false
        entered = entered || fullySupported
        if (!entered || !struckPlate) return false
        struck = true
        return true
    }
}
