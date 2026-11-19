package com.palmrail.singlerail.sdk

import android.util.Log
import com.palmrail.singlerail.domain.EndpointCycle
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ObjectAudioComponent
import com.pico.spatial.core.ecs.PhysicsVelocityComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.audio.AudioPlayerController
import com.pico.spatial.core.ecs.resource.AudioResource
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/** One physical gate, one terminal plate and a spatial sound source at each endpoint. */
internal class EndpointRig(
    private val spec: RailSpec,
    private val gate: Entity,
    private val bell: Entity,
    private val gateSound: AudioPlayerController,
    private val bellSound: AudioPlayerController,
    private val landingSound: AudioPlayerController,
    val spawn: Vector3,
    private val goalPort: Vector3,
    private val floor: Set<Entity>,
    private val plate: Entity,
) : AutoCloseable {
    private var cycle = EndpointCycle()
    private val cfg = spec.endpoints
    private val gateTransform = checkNotNull(gate.components[TransformComponent::class.java])
    private val gateVelocity = checkNotNull(gate.components[PhysicsVelocityComponent::class.java])
    private val gateRest = gateTransform.position
    private val gateTop = gateRest.y + cfg.number("gateLiftMeters")
    private val bellTransform = checkNotNull(bell.components[TransformComponent::class.java])
    private val bellRest = bellTransform.quaternion
    private var bellAge = 0.0
    private var floorAt = Double.NEGATIVE_INFINITY
    private var strikeAt = Double.NEGATIVE_INFINITY

    val released get() = cycle.released
    val struck get() = cycle.struck
    val entered get() = cycle.entered
    val gateY get() = gateTransform.position.y

    fun release(): Boolean {
        if (!cycle.release()) return false
        gateSound.play().also { Log.i("PalmRailSingleRail", "START_GATE_RELEASE audioPlaying=$it") }
        return true
    }

    fun reset() {
        cycle = EndpointCycle()
        gateSound.stop()
        bellSound.stop()
        landingSound.stop()
        gateTransform.position = gateRest
        gateVelocity.linearVelocity = Vector3.ZERO
        bellTransform.quaternion = bellRest
        bellAge = 0.0
        floorAt = Double.NEGATIVE_INFINITY
        strikeAt = Double.NEGATIVE_INFINITY
    }

    fun contact(other: Entity?, now: Double) {
        if (other in floor) floorAt = now
        if (other == plate) strikeAt = now
    }

    fun tick(position: Vector3, liftExited: Boolean, now: Double, dt: Double) {
        if (released && gateTransform.position.y < gateTop) {
            val speed = cfg.number("gateLiftMeters") / cfg.number("gateSeconds")
            val climb = minOf(speed, ((gateTop - gateTransform.position.y) / dt).toFloat())
            val velocity = Vector3(0f, climb * spec.sceneScale, 0f)
            if (gateVelocity.linearVelocity != velocity) gateVelocity.linearVelocity = velocity
        } else {
            if (gateVelocity.linearVelocity != Vector3.ZERO) gateVelocity.linearVelocity = Vector3.ZERO
        }
        val freshFor = spec.probe.getDouble("frameAge")
        val fullEntry = position.x - spec.radius >= goalPort.x &&
            position.x + spec.radius <= goalPort.x + cfg.number("railLengthMeters") &&
            abs(position.z - goalPort.z) + spec.radius <= cfg.number("clearWidthMeters") / 2f &&
            now - floorAt <= freshFor
        if (cycle.observeLanding(liftExited, now - floorAt <= freshFor)) {
            landingSound.play().also { Log.i("PalmRailSingleRail", "GOAL_LANDING audioPlaying=$it") }
        }
        val wasEntered = entered
        val ring = cycle.observe(liftExited, fullEntry, now - strikeAt <= freshFor)
        if (entered && !wasEntered) Log.i("PalmRailSingleRail", "GOAL_FULL_ENTRY")
        if (ring) bellSound.play().also { Log.i("PalmRailSingleRail", "GOAL_PLATE_STRUCK audioPlaying=$it") }
        if (struck && bellAge < cfg.getDouble("bellSwingSeconds")) {
            bellAge = minOf(bellAge + dt, cfg.getDouble("bellSwingSeconds"))
            val angle = if (bellAge >= cfg.getDouble("bellSwingSeconds")) 0.0 else
                cfg.getDouble("bellSwingRadians") * exp(-cfg.getDouble("bellDecayPerSecond") * bellAge) *
                    sin(2 * PI * cfg.getDouble("bellFrequencyHz") * bellAge)
            bellTransform.quaternion = Quat(0f, 0f, sin(angle / 2).toFloat(), cos(angle / 2).toFloat())
        }
    }

    override fun close() {
        gateSound.close()
        bellSound.close()
        landingSound.close()
    }

    companion object {
        fun prepareSound(emitter: Entity, sample: AudioResource): AudioPlayerController {
            emitter.components.set(ObjectAudioComponent())
            return emitter.prepareAudio(sample).also { check(it.valid) { "空间音效准备失败" } }
        }
    }
}
