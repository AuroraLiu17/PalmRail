package com.palmrail.singlerail.domain

/** Single Full-space button, driven only by consecutive valid eye samples. */
internal class StartDwell(
    private val delay: Double,
    private val fill: Double,
    private val grace: Double,
    private val rearm: Double,
    private val maxGap: Double,
) {
    private var last: Double? = null
    private var elapsed = 0.0
    private var away = 0.0
    private var wasHit = false
    private var locked = false

    data class View(val ring: Boolean = false, val progress: Double = 0.0, val press: Boolean = false)

    fun cancel() {
        last = null
        resetProgress()
    }

    fun lock() {
        locked = true
        cancel()
    }

    private fun resetProgress() {
        elapsed = 0.0
        away = 0.0
        wasHit = false
    }

    fun sample(time: Double, hit: Boolean): View {
        val gap = last?.let { time - it } ?: 0.0
        last = time
        if (gap < 0.0 || gap > maxGap) {
            resetProgress()
            return View()
        }
        if (locked) {
            away = if (hit) 0.0 else away + gap
            if (away >= rearm) {
                locked = false
                resetProgress()
            }
            return View()
        }
        if (!hit) {
            if (wasHit || away > 0.0) away += gap
            wasHit = false
            if (away > grace) resetProgress()
            return View()
        }
        if (away > 0.0 && away + gap > grace) resetProgress()
        if (wasHit) elapsed += gap
        wasHit = true
        away = 0.0
        val progress = ((elapsed - delay) / fill).coerceIn(0.0, 1.0)
        val pressed = progress >= 1.0
        if (pressed) lock()
        return View(elapsed >= delay || pressed, progress, pressed)
    }
}
