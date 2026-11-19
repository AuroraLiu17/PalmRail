package com.palmrail.singlerail.sdk

import android.util.Log
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.hmd.HMDTrackingProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** A world-space anchor sampled once per Stage. The boards stay level and stationary. */
internal data class StagePose(val eye: Vector3, val yaw: Float) {
    val orientation get() = Quat(0f, sin(yaw / 2), 0f, cos(yaw / 2))
    val floor get() = Vector3(eye.x, 0f, eye.z)

    fun boardOrigin(spec: RailSpec): Vector3 = eye + rotate(
        Vector3(-spec.span * spec.sceneScale / 2, -spec.boardBelowEyes, -spec.boardDistance))

    fun rotate(local: Vector3): Vector3 {
        val c = cos(yaw)
        val s = sin(yaw)
        return Vector3(c * local.x + s * local.z, local.y, -s * local.x + c * local.z)
    }
}

internal suspend fun sampleStagePose(timeoutMs: Long, minimumEyeHeight: Float): StagePose? {
    val provider = HMDTrackingProvider()
    return try {
        val started = provider.start()
        if (started != DataProvider.StartResult.SUCCESS) {
            Log.w("PalmRailSingleRail", "STAGE_HEAD unavailable start=$started")
            return null
        }
        val head = withTimeoutOrNull(timeoutMs) {
            provider.dataFlow.first {
                it.hmdPose.position.isFinite() && it.hmdPose.position.y >= minimumEyeHeight
            }.hmdPose
        } ?: run {
            Log.w("PalmRailSingleRail", "STAGE_HEAD unavailable; last=${provider.latestData?.hmdPose?.position}")
            return null
        }
        val q = head.rotation
        // Project the headset's local -Z forward onto the floor. If looking
        // nearly straight up or down, use the projected local right instead.
        val forwardX = -2f * (q.x * q.z + q.w * q.y)
        val forwardZ = -1f + 2f * (q.x * q.x + q.y * q.y)
        val yaw = if (forwardX * forwardX + forwardZ * forwardZ > 0.0001f) {
            atan2(-forwardX, -forwardZ)
        } else {
            atan2(-2f * (q.x * q.z - q.w * q.y), 1f - 2f * (q.y * q.y + q.z * q.z))
        }
        if (!yaw.isFinite()) return null
        Log.i("PalmRailSingleRail", "STAGE_HEAD eye=${head.position} yaw=$yaw")
        StagePose(head.position, yaw)
    } finally {
        provider.stop()
    }
}
