package com.palmrail.singlerail.sdk

import android.util.Log
import com.palmrail.singlerail.BuildConfig
import com.palmrail.singlerail.domain.StartDwell
import androidx.compose.ui.input.pointer.PointerId
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.AccessibilityComponent
import com.pico.spatial.core.ecs.ModelComponent
import com.pico.spatial.core.ecs.resource.MeshResource
import com.pico.spatial.core.ecs.resource.UnlitMaterial
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.audio.AudioPlayerController
import com.pico.spatial.core.ecs.simulation.CollisionCastHitMode
import com.pico.spatial.core.ecs.simulation.CollisionGroup
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.tracking.DataProvider
import com.pico.spatial.tracking.eye.EyeTrackingData
import com.pico.spatial.tracking.eye.EyeTrackingProvider
import com.pico.spatial.ui.foundation.gesture.SpatialPointerInfo
import com.pico.spatial.ui.foundation.gesture.data.InteractionKind
import org.json.JSONObject

/** One Attempt owns button input and feedback. The endpoint cycle owns release eligibility. */
internal class StartButton(
    val target: Entity,
    private val cap: Entity,
    private val outward: Vector3,
    private val ring: List<Entity>,
    private val dotMesh: MeshResource,
    private val dotMaterial: UnlitMaterial,
    private val sound: AudioPlayerController,
    private val config: JSONObject,
    realEyes: Boolean,
) : AutoCloseable {
    private val transform = checkNotNull(cap.components[TransformComponent::class.java])
    private val rest = transform.position
    private val eyes = if (realEyes) EyeTrackingProvider() else null
    private var pointer: PointerId? = null
    private var contactKind: InteractionKind? = null
    private var shownDots = 0
    private var pressedAt: Double? = null
    private var bottomReported = false
    private val gaze = StartDwell(
        config.getDouble("dwellDelaySeconds"), config.getDouble("dwellFillSeconds"),
        config.getDouble("dwellGraceSeconds"), config.getDouble("dwellRearmSeconds"),
        config.getDouble("maxEyeFrameAgeSeconds")
    )
    private var lastEyeTimestamp: Long? = null
    private var gazeStatus = ""
    private var nextEyeLog = 0.0
    @Volatile private var sample: EyeSample? = null
    private data class EyeSample(val pose: EyeTrackingData, val received: Double)
    private val listener = object : DataProvider.DataListener<EyeTrackingData> {
        override fun onProvideData(data: EyeTrackingData) {
            sample = EyeSample(data, System.nanoTime() / 1e9)
        }
    }

    init {
        eyes?.let {
            it.addListener(listener)
            Log.i("PalmRailSingleRail", "START_BUTTON_EYES start=${it.start()} support=${it.supportState}")
        }
    }

    fun acceptPointer(events: List<SpatialPointerInfo>, enabled: Boolean, activate: (InteractionKind) -> Unit): Boolean {
        if (!enabled) {
            cancelPointer()
            return false
        }
        val previous = pointer
        if (previous != null) {
            val event = events.firstOrNull { it.pointerId == previous } ?: return false
            if (!event.pressed) {
                val kind = contactKind
                cancelPointer()
                if (event.isUpEvent() && event.targetedEntity == target && kind != null && kind != InteractionKind.Poke) {
                    activate(kind)
                }
            }
            return true
        }
        val down = events.firstOrNull { it.isDownEvent() && it.targetedEntity == target } ?: return false
        pointer = down.pointerId
        contactKind = down.kind
        Log.i("PalmRailSingleRail", "START_BUTTON_DOWN kind=${down.kind}")
        if (down.kind == InteractionKind.Poke) activate(down.kind)
        return true
    }

    fun cancelPointer() {
        pointer = null
        contactKind = null
    }

    fun press(now: Double, source: String) {
        pressedAt = now
        bottomReported = false
        gaze.lock()
        target.components[AccessibilityComponent::class.java]?.value = "已放球，可再次按下"
        sound.stop()
        showProgress(0.0)
        Log.i("PalmRailSingleRail", "START_BUTTON_PRESS source=$source audioPlaying=${sound.play()}")
    }

    fun tick(now: Double, canRelease: Boolean, visibleStage: Boolean): Boolean {
        pressedAt?.let { start ->
            val elapsed = now - start
            val down = config.getDouble("travelInSeconds")
            val hold = config.getDouble("holdSeconds")
            val up = config.getDouble("returnSeconds")
            val fraction = when {
                elapsed < down -> elapsed / down
                elapsed < down + hold -> 1.0
                else -> (1.0 - (elapsed - down - hold) / up).coerceAtLeast(0.0)
            }
            transform.position = rest - outward * (config.number("pressMeters") * fraction.toFloat())
            if (fraction == 1.0 && !bottomReported) {
                bottomReported = true
                Log.i("PalmRailSingleRail", "START_BUTTON_BOTTOM travelMeters=${(rest - transform.position).length()}")
            }
            if (fraction == 0.0 && elapsed >= down+hold+up) {
                pressedAt = null
                Log.i("PalmRailSingleRail", "START_BUTTON_REBOUND")
            }
        }
        if (eyes == null) return false
        val eye = sample?.takeIf { now - it.received <= config.getDouble("maxEyeFrameAgeSeconds") }
        val blocked = when {
            !visibleStage -> "stage-hidden"
            !canRelease -> "button-disabled"
            pointer != null -> "pointer-capture"
            eyes.state != DataProvider.State.STARTED -> "eye-provider-${eyes.state}"
            eye == null -> "eye-sample-stale"
            else -> null
        }
        if (blocked != null || eye == null) return cancelGaze(now, blocked ?: "eye-sample-missing")
        val timestamp = eye.pose.timestamp
        val previousTimestamp = lastEyeTimestamp
        if (previousTimestamp == timestamp) return false
        lastEyeTimestamp = timestamp
        if (timestamp <= 0 || previousTimestamp != null && timestamp < previousTimestamp) {
            return cancelGaze(now, "eye-timestamp-invalid")
        }
        val pose = eye.pose.eyePose
        val q = pose.rotation
        val direction = Vector3(-2f*(q.x*q.z+q.w*q.y), -2f*(q.y*q.z-q.w*q.x),
            -1f+2f*(q.x*q.x+q.y*q.y))
        if (!pose.position.isFinite() || !direction.isFinite() || !q.isFinite() ||
            pose.position == Vector3.ZERO || direction.length() < .99f) {
            return cancelGaze(now, "eye-pose-invalid")
        }

        // EyeTrackingProvider reports world metres. Raycast in an explicit entity space so
        // repositioning/rotating/scaling the boards cannot change which ray is being tested.
        val start = target.convertPositionFrom(pose.position, null)
        val end = target.convertPositionFrom(pose.position + direction * config.number("eyeMaxMeters"), null)
        val ray = end - start
        // The gaze target includes the visible walnut rim. The finger collider
        // still covers the red cap and remains stationary during animation.
        fun faceHit(depth: Float, radius: Float): Float? {
            if (ray.y >= 0f) return null
            val fraction = (depth - start.y) / ray.y
            if (fraction <= 0f || fraction > 1f) return null
            val point = start + ray * fraction
            return fraction.takeIf { point.x * point.x + point.z * point.z <= radius * radius }
        }

        val capFraction = faceHit(config.number("gazeCapDepthMeters"), config.number("gazeCapRadiusMeters"))
        val fraction = capFraction ?:
            faceHit(config.number("gazeFaceDepthMeters"), config.number("gazeFaceRadiusMeters"))
        val hit = fraction != null
        val surface = if (capFraction != null) "red-cap" else "walnut-rim"
        if (fraction != null) {
            val distance = ray.length()
            val scene = target.scene ?: return cancelGaze(now, "scene-missing")
            val nearest = scene.rayCast(start, ray * (1f / distance), distance * fraction,
                CollisionCastHitMode.NEAREST, CollisionGroup(), referenceEntity = target)
                ?.results?.firstOrNull()?.entity
            if (nearest != null && nearest != target) return cancelGaze(now, "button-occluded")
        }
        val view = gaze.sample(eye.received, hit)
        showProgress(view.progress)
        diagnoseGaze(now, when {
            view.press -> "confirmed"
            view.ring -> "ring"
            hit -> "button-hit"
            else -> "miss"
        }, "surface=${if (hit) surface else "none"} progress=${view.progress}")
        return view.press
    }

    private fun cancelGaze(now: Double, reason: String): Boolean {
        gaze.cancel()
        showProgress(0.0)
        diagnoseGaze(now, reason)
        return false
    }

    private fun diagnoseGaze(now: Double, reason: String, detail: String = "") {
        if (!BuildConfig.DEBUG || (reason == gazeStatus && now < nextEyeLog)) return
        // Suppress rapid hit/miss changes from natural eye jitter but keep confirmations.
        if (now < nextEyeLog && reason != "confirmed" && reason != "ring") return
        gazeStatus = reason
        nextEyeLog = now + config.getDouble("gazeLogIntervalSeconds")
        Log.i("PalmRailSingleRail", "START_BUTTON_GAZE phase=$reason $detail")
    }

    private fun showProgress(fraction: Double) {
        val count = (fraction * ring.size).toInt()
        for (index in shownDots until count) ring[index].components.set(ModelComponent(dotMesh, dotMaterial))
        for (index in count until shownDots) ring[index].components.remove(ModelComponent::class.java)
        shownDots = count
    }

    override fun close() {
        eyes?.removeListener(listener)
        eyes?.stop()
        sound.close()
    }
}
