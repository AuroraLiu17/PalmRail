package com.palmrail.singlerail.sdk

import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hand.HandPinch
import com.pico.spatial.tracking.hand.HandTrackingData
import com.pico.spatial.tracking.hand.HandTrackingProvider

internal enum class PinchSide { LEFT, RIGHT }
internal data class PinchPoint(val side: PinchSide, val position: Vector3, val strength: Float)

private fun HandPinch?.point(side: PinchSide): PinchPoint? {
    val pinch = this ?: return null
    val position = pinch.pinchPose?.position ?: return null
    return PinchPoint(side, position, pinch.pinchValue).takeIf { position.isFinite() && it.strength.isFinite() }
}

/** SDK pinch point and pressure, captured independently of the SDK pointer lifecycle. */
class PinchSource : AutoCloseable {
    private val provider = HandTrackingProvider()
    private var lastObserved = Double.NEGATIVE_INFINITY
    private var closed = false
    private var points = emptyList<PinchPoint>()
    private var trackedSides = emptySet<PinchSide>()
    private val listener = object : DataProvider.DataListener<HandTrackingData> {
        override fun onProvideData(data: HandTrackingData) {
            synchronized(this@PinchSource) {
                if (!closed) {
                    points = listOfNotNull(data.leftPinch.point(PinchSide.LEFT), data.rightPinch.point(PinchSide.RIGHT))
                    trackedSides = buildSet {
                        if (data.left != null || data.leftPinch != null) add(PinchSide.LEFT)
                        if (data.right != null || data.rightPinch != null) add(PinchSide.RIGHT)
                    }
                    lastObserved = if (trackedSides.isNotEmpty())
                        System.nanoTime() / 1e9 else Double.NEGATIVE_INFINITY
                }
            }
        }
    }

    init {
        provider.addListener(listener)
        val result = provider.start()
        android.util.Log.i("PalmRailSingleRail", "HAND_TRACKING start=$result support=${provider.supportState}")
    }

    @Synchronized fun hasFreshHands(now: Double, maxAge: Double): Boolean =
        !closed && now - lastObserved <= maxAge

    @Synchronized internal fun pinchPoints(now: Double, maxAge: Double): List<PinchPoint> =
        if (hasFreshHands(now, maxAge)) points else emptyList()

    @Synchronized internal fun hasFreshHand(side: PinchSide, now: Double, maxAge: Double): Boolean =
        hasFreshHands(now, maxAge) && side in trackedSides

    override fun close() {
        synchronized(this) { closed = true }
        provider.removeListener(listener)
        provider.stop()
    }
}
