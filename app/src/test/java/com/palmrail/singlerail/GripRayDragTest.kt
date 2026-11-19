package com.palmrail.singlerail

import com.palmrail.singlerail.domain.Crank
import com.palmrail.singlerail.domain.CrankTuning
import com.palmrail.singlerail.sdk.GripMotionSource
import com.palmrail.singlerail.sdk.GripPose
import com.palmrail.singlerail.sdk.GripRayMotion
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class GripRayDragTest {
    private val grip = Vector3(.165f, 0f, 0f)
    private val origin = Vector3(.165f, 0f, 1f)

    private fun rotation(pitch: Double) =
        Quat(sin(pitch / 2).toFloat(), 0f, 0f, cos(pitch / 2).toFloat())

    @Test fun controllerPoseTurnsTheCrankWithAFrozenPointer() {
        val motion = GripRayMotion(grip, .003f)
        val source = GripMotionSource.CONTROLLER_POSE
        val crank = Crank(CrankTuning(4, .12, .185, 2.0))
        val cursor = Vector3(0.2f, .4f, .1f)
        assertNull(motion.pointer(cursor))
        assertNull(motion.pose(source, GripPose(origin, rotation(0.0))))
        crank.dragTo(0.0, admitted = true)

        val moved = checkNotNull(motion.pose(source, GripPose(origin, rotation(-.08))))
        val clockwise = atan2(-moved.y.toDouble(), moved.x.toDouble())
        crank.dragTo(clockwise, admitted = true)
        assertTrue(crank.radians > .4)
        assertNull(motion.pointer(cursor))
        assertNull(motion.pointer(cursor + Vector3(.02f, 0f, 0f)))
        assertEquals(source, motion.active)

        val reversed = checkNotNull(motion.pose(source, GripPose(origin, rotation(-.04))))
        crank.dragTo(atan2(-reversed.y.toDouble(), reversed.x.toDouble()), admitted = true)
        assertTrue(crank.radians < clockwise)
        assertTrue(crank.radians > 0.0)
    }

    @Test fun rayTranslationAndFirstMotionHaveNoGrabJump() {
        val motion = GripRayMotion(grip, .003f)
        val source = GripMotionSource.EVENT_POSE
        assertNull(motion.pose(source, GripPose(origin, rotation(0.0))))
        assertNull(motion.pose(source, GripPose(origin + Vector3(0f, .001f, 0f), rotation(0.0))))
        val point = checkNotNull(motion.pose(source, GripPose(origin + Vector3(.02f, -.04f, 0f), rotation(0.0))))
        assertEquals(.185f, point.x, 1e-6f)
        assertEquals(-.04f, point.y, 1e-6f)
    }

    @Test fun pointerDragCanStartWhenTheDeviceDoesNotSupplyPose() {
        val motion = GripRayMotion(grip, .003f)
        val cursor = Vector3(0.45f, .4f, .1f)
        assertNull(motion.pointer(cursor))
        val point = checkNotNull(motion.pointer(cursor + Vector3(0f, -.05f, 0f)))
        assertEquals(grip.x, point.x, 1e-6f)
        assertEquals(-.05f, point.y, 1e-6f)
        assertEquals(GripMotionSource.POINTER, motion.active)
    }
}
