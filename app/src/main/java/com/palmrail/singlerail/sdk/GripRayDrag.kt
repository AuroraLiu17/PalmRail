package com.palmrail.singlerail.sdk

import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import kotlin.math.abs
import kotlin.math.hypot

internal data class GripPose(val origin: Vector3, val rotation: Quat)

/** Hold a ray through the point actually grabbed, in the device's local coordinates. */
internal class GripRayDrag(initial: GripPose, private val grip: Vector3) {
    private val localDirection = initial.rotation.normalize().conjugate()
        .rotateVector((grip - initial.origin).normalize())

    fun point(pose: GripPose): Vector3? {
        val ray = pose.rotation.normalize().rotateVector(localDirection)
        if (!ray.isFinite() || abs(ray.z) < 1e-4f) return null
        val distance = (grip.z - pose.origin.z) / ray.z
        return if (distance > 0f) (pose.origin + ray * distance).takeIf { it.isFinite() } else null
    }
}

internal enum class GripMotionSource { CONTROLLER_POSE, EVENT_POSE, POINTER }

internal class GripRayMotion(private val grip: Vector3, private val minMove: Float) {
    var active: GripMotionSource? = null
        private set
    private var pointerStart: Vector3? = null
    private val rays = mutableMapOf<GripMotionSource, GripRayDrag>()

    fun pose(source: GripMotionSource, pose: GripPose): Vector3? {
        if (active != null && active != source) return null
        if (!pose.origin.isFinite() || !pose.rotation.isFinite() || pose.rotation.length() <= 0f) return null
        if ((grip - pose.origin).length() <= 1e-4f) return null
        val ray = rays.getOrPut(source) { GripRayDrag(pose, grip) }
        return ray.point(pose)?.let { activate(source, it) }
    }

    fun pointer(point: Vector3): Vector3? {
        val start = pointerStart ?: point.also { pointerStart = it }
        return activate(GripMotionSource.POINTER, grip + (point - start))
    }

    private fun activate(source: GripMotionSource, point: Vector3): Vector3? {
        if (active != null) return point.takeIf { active == source }
        if (hypot(point.x - grip.x, point.y - grip.y) < minMove) return null
        active = source
        return point
    }
}
