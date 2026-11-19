package com.palmrail.singlerail.sdk

import com.pico.spatial.tracking.controller.ControllerTrackingProvider

internal class ControllerGripSource : AutoCloseable {
    enum class Side { LEFT, RIGHT }

    private val provider = ControllerTrackingProvider()
    @Volatile private var pressing: Side? = null
    private val listener = ControllerTrackingProvider.ControllerActionListener { action ->
        val left = action.left?.let { it.triggerPressed || it.gripPressed } == true
        val right = action.right?.let { it.triggerPressed || it.gripPressed } == true
        pressing = when {
            left && !right -> Side.LEFT
            right && !left -> Side.RIGHT
            else -> null
        }
    }

    init {
        provider.addControllerActionListener(listener)
        val result = provider.start()
        android.util.Log.i("PalmRailSingleRail", "CONTROLLER_TRACKING start=$result support=${provider.supportState}")
    }

    fun pressingSide(): Side? = pressing

    fun pose(side: Side): GripPose? {
        val data = provider.latestData ?: return null
        val pose = if (side == Side.LEFT) data.left else data.right
        return pose?.let { GripPose(it.position, it.rotation) }
    }

    override fun close() {
        provider.removeControllerActionListener(listener)
        provider.stop()
    }
}
