package com.palmrail.singlerail.sdk

import com.pico.spatial.core.ecs.DirectionalLightComponent
import com.pico.spatial.core.ecs.ImageBasedLightComponent
import com.pico.spatial.core.ecs.ImageBasedLightReceiverComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.ImageBasedLightSource
import com.pico.spatial.core.ecs.EnvironmentLightingSettingsComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.resource.ImageBasedLightingGenerationOption
import com.pico.spatial.core.ecs.resource.ImageBasedLightingSampleQuality
import com.pico.spatial.core.ecs.resource.TextureResource
import com.pico.spatial.core.math.Color4
import com.pico.spatial.core.math.EulerAngles
import com.pico.spatial.core.math.Vector3
import org.json.JSONObject

/** Lighting and the lit models share the same SpatialView and attempt lifetime. */
internal class StudioLighting(private val config: JSONObject, private val environmentScale: Float = config.number("environmentScale")) : AutoCloseable {
    // SDK 6.4 scales the bound local IBL as well as system IBL. Compensate the
    // known local source in linear intensity; the unknown system term remains
    // attenuated. A zero multiplier would discard both and blacken the metals.
    private val localCompensation = if (environmentScale > 0f) -kotlin.math.log2(environmentScale) else 0f
    init { require(environmentScale.isFinite() && environmentScale in 0f..1f) }
    private var texture: TextureResource? = null
    private var source: Entity? = null
    private val direct = mutableListOf<Pair<DirectionalLightComponent, Float>>()

    fun attach(board: Entity) {
        val rig = Entity().apply {
            setName("single-rail.studio"); board.addChild(this)
            components.set(TransformComponent(Vector3.ZERO, EulerAngles(0f, 0f, 0f), Vector3.ONE))
        }
        val lights = config.getJSONArray("directLights")
        for (index in 0 until lights.length()) {
            val light = lights.getJSONObject(index)
            val angles = light.getJSONArray("eulerDegrees").vector()
            rig.addChild(Entity().apply {
                setName(light.getString("name"))
                components.set(TransformComponent(Vector3.ZERO,
                    EulerAngles(angles.x, angles.y, angles.z), Vector3.ONE))
                val baseIntensity = light.number("intensity")
                val lamp = DirectionalLightComponent(Color4(1f, 1f, 1f, 1f), baseIntensity, false)
                components.set(lamp)
                direct.add(lamp to baseIntensity)
            })
        }

        val environment = TextureResource.load(config.getString("asset"),
            option = ImageBasedLightingGenerationOption().apply {
                sampleQuality = ImageBasedLightingSampleQuality.NORMAL
                desiredCubemapFaceSize = config.getLong("cubemapFaceSize")
            }).also { it.toGlobal() }
        texture = environment
        source = rig
        rig.components.set(ImageBasedLightComponent(
            ImageBasedLightSource.Single(environment), config.number("intensityExponent")
        ))
        setGain(config.number("defaultGain"))
    }

    fun setGain(gain: Float) {
        val value = gain.coerceAtLeast(0.01f)
        direct.forEach { (lamp, base) -> lamp.intensity = base * value }
        val exponent = config.number("intensityExponent") + kotlin.math.log2(value) + localCompensation
        source?.components?.get(ImageBasedLightComponent::class.java)?.intensityExponent = exponent
    }

    fun applyTo(model: Entity) {
        source?.let {
            model.components.set(ImageBasedLightReceiverComponent(it))
            model.components.set(EnvironmentLightingSettingsComponent(environmentScale))
        }
    }

    override fun close() {
        source = null
        direct.clear()
        texture?.close()
        texture = null
    }
}
