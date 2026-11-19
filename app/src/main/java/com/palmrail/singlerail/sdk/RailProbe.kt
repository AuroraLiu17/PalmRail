package com.palmrail.singlerail.sdk

import android.util.Log
import com.palmrail.singlerail.BuildConfig

import com.palmrail.singlerail.domain.*
import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.ui.foundation.content.clearAccessibilityActions
import com.pico.spatial.core.ecs.*
import com.pico.spatial.core.ecs.event.CollisionEvents
import com.pico.spatial.core.ecs.resource.*
import com.pico.spatial.core.ecs.simulation.*
import com.pico.spatial.core.lifecycle.Cancellable
import com.pico.spatial.core.math.*
import com.pico.spatial.ui.foundation.gesture.SpatialPointerInfo
import com.pico.spatial.ui.foundation.gesture.data.InteractionKind
import androidx.compose.ui.input.pointer.PointerId
import kotlin.math.*

/** SDK observation adapter. Does not provide a formal completed-step receipt. */
internal class RailProbe(private val spec: RailSpec, private val pose: StagePose?, private val lightingEnvironmentScale: Float? = null) : AutoCloseable {
    private val g = spec.geometry
    private val p = spec.physics
    private val limits = spec.interaction
    private val b = spec.base
    private val cfg = spec.probe
    private val resources = mutableListOf<Resource>()
    private val fixed = p.number("fps").let { 1f / it }
    private val crank = Crank(CrankTuning(p.getInt("turns"), cfg.getDouble("releaseStrokePerSecond"),
        p.getDouble("rollback_eject_progress"),
        Math.toRadians(limits.getDouble("unwrap_limit_degrees"))))
    private var root: Entity? = null
    private var viewOwner: Entity? = null
    private var studioLighting: StudioLighting? = null
    private var ball: Entity? = null
    private var modelPivot: Entity? = null
    private var pusherBody: Entity? = null
    private var keeperBody: Entity? = null
    private var endpoints: EndpointRig? = null
    private var startButton: StartButton? = null
    private var source: PinchSource? = null
    private var controller: ControllerGripSource? = null
    private var controllerSide: ControllerGripSource.Side? = null
    private var rayMotion: GripRayMotion? = null
    private var gripSamples = 0
    private var handSide: PinchSide? = null
    private var nearSide: PinchSide? = null
    private val nearPressed = mutableSetOf<PinchSide>()
    private var nearLastObserved = Double.NEGATIVE_INFINITY
    private var handOffset: Vector3? = null
    private var pointerOffset: Vector3? = null
    private var nextDragLog = 0.0
    private var dragTravel = 0.0
    private var lastRollingContact = Double.NEGATIVE_INFINITY
    private val liftFloors = mutableSetOf<Entity>()
    private val rollingFloors = mutableSetOf<Entity>()
    private var gripStartAngle = 0.0
    private var subscription: Cancellable? = null
    private var closed = false
    private var admitted = false
    private var latestTrackContact = Double.NEGATIVE_INFINITY
    private var capturedPointer: PointerId? = null
    private var pivotPoint = Vector3.ZERO
    private var capturedKind: InteractionKind? = null
    private var pointerContent: SpatialViewContent? = null
    private var buttonContent: SpatialViewContent? = null
    private var sawTrackingDuringCapture = false
    private var gripTarget: Entity? = null
    private var trackingReady = false
    private var requiresHandRecovery = false
    private var simulationAngle = 0.0
    private var allowMovement = false
    private var commandedHold = false
    private var indicatorModel: ModelComponent? = null
    private var greenIndicator: UnlitMaterial? = null
    private var idleIndicator: Material? = null
    private lateinit var ballMesh: MeshResource
    private lateinit var ballShape: ShapeResource
    private lateinit var ballMaterial: PhysicallyBasedMaterial
    private var ballGeneration = 0L
    private var nextFlightLog = 0.0
    private var displayedCrankRadians: Double? = null
    private var clockSpeed: Float? = null
    private val bladeDimensions = size("blade_size")
    private val keeperDimensions = size("keeper_size")


    fun attachGuide(panel: Entity) {
        val scene = checkNotNull(root)
        scene.addChild(panel)
        panel.components.set(TransformComponent(
            Vector3(spec.span / 2f, g.number("board_top") + spec.trial.number("guideAboveBoardMeters") / spec.sceneScale,
                spec.trackDepth + spec.trial.number("guideForwardMeters")),
            EulerAngles(0f, 0f, 0f), Vector3(1f / spec.sceneScale)))
    }

    fun setLightingGain(gain: Float) {
        studioLighting?.setGain(gain)
    }

    val hasBall get() = admitted
    fun rollingSpeed(now: Double): Float {
        if (paused || now - lastRollingContact > cfg.getDouble("frameAge")) return 0f
        return ball?.components?.get(PhysicsVelocityComponent::class.java)?.linearVelocity?.length() ?: 0f
    }
    val goalReached get() = endpoints?.struck == true
    val atOutlet get() = crank.atOutlet
    val startReleased get() = endpoints?.released == true
    fun releaseStart(source: String = "panel"): Boolean {
        if (closed || crank.frozen || !allowMovement) return false
        val scene = root ?: return false
        val rig = endpoints ?: return false
        val old = ball
        val oldName = old?.getName()
        val nextGeneration = ballGeneration + 1
        val next = createBall(scene, rig.spawn, "ball.$nextGeneration")
        ball = next
        old?.destroy()
        ballGeneration = nextGeneration
        latestTrackContact = Double.NEGATIVE_INFINITY
        lastRollingContact = Double.NEGATIVE_INFINITY
        admitted = false
        idleIndicator?.let { indicatorModel?.materials?.set(0, it) }
        crank.resetLift()
        rig.reset()
        pusherBody = resetPost(scene, pusherBody, bladeDimensions)
        keeperBody = resetPost(scene, keeperBody, keeperDimensions)
        animate(initial = true)
        check(rig.release())
        startButton?.press(java.lang.System.nanoTime()/1e9, source)
        Log.i("PalmRailSingleRail", "START_BALL_REPLACED removed=$oldName new=${next.getName()}")
        return true
    }
    fun observation(): String = "stroke=${crank.stroke} outlet=${crank.atOutlet} ball=${ball?.let { t(it).position }} velocity=${ball?.components?.get(PhysicsVelocityComponent::class.java)?.linearVelocity} pusher=${pusherBody?.let { t(it).position }} contact=${latestTrackContact.isFinite()} gateY=${endpoints?.gateY} goalEntered=${endpoints?.entered} goalStruck=${endpoints?.struck}"
    val paused get() = crank.frozen
    val mayResume get() = trackingReady
    val angle get() = crank.radians

    private fun t(e: Entity) = checkNotNull(e.components[TransformComponent::class.java])
    private fun entity(parent: Entity, name: String, pos: Vector3): Entity = Entity().apply {
        setName(name); parent.addChild(this)
        components.set(TransformComponent(pos, EulerAngles(0f, 0f, 0f), Vector3.ONE))
    }
    private fun all(entity: Entity): Sequence<Entity> = sequence {
        yield(entity)
        entity.getChildren().forEach { yieldAll(all(it)) }
    }
    private fun <T : Resource> keep(resource: T): T = resource.also { it.toGlobal(); resources.add(it) }
    private lateinit var contactMaterial: PhysicsMaterialResource
    private fun box(parent: Entity, name: String, pos: Vector3, size: Vector3, roll: Float,
                    moving: Boolean = false, shape: ShapeResource? = null): Entity {
        val e = entity(parent, name, pos)
        if (name.startsWith("start.floor.") || name.startsWith("goal.floor.")) rollingFloors.add(e)
        t(e).quaternion = rotation(roll.toDouble())
        e.components.set(CollisionComponent(listOf(shape ?: keep(ShapeResource.createBox(size))), contactMaterial,
            CollisionResponseMode.COLLIDER_FULL, collisionInfoDetailLevel = CollisionInfoDetailLevel.DETAILED))
        if (moving) {
            e.components.set(RigidBodyComponent(MassProperties(1f), RigidBodyMode.KINEMATIC).apply {
                collisionDetectionMode = CollisionDetectionMode.CONTINUOUS_SPECULATIVE
            })
            e.components.set(PhysicsVelocityComponent())
        }
        return e
    }
    private fun rotation(angle: Double) = Quat(0f, 0f, sin(angle / 2).toFloat(), cos(angle / 2).toFloat())
    private fun size(name: String) = g.getJSONArray(name).vector().let { Vector3(it.x, it.z, it.y) }

    suspend fun attach(content: SpatialViewContent, real: Boolean, studio: Boolean) {
        check(root == null && !closed)
        val model = Entity.loadSuspend("asset://${spec.rail.asset}.glb")
        if (closed) { model.destroy(); return }
        val scene = Entity().apply { enabled = false }
        root = scene
        scene.setName("single-rail.attempt")
        val owner = Entity()
        owner.setName("single-rail.input-space")
        content.addEntity(owner)
        viewOwner = owner
        owner.addChild(scene)
        scene.addChild(model)
        val backdrop = Entity.loadSuspend("asset://stage-timber.glb")
        if (closed) { backdrop.destroy(); return }
        owner.addChild(backdrop)
        if (pose != null) t(backdrop).apply {
            position = owner.convertPositionFrom(pose.floor, null)
            quaternion = owner.convertRotationFrom(pose.orientation, null)
        }
        Log.i("PalmRailSingleRail", "STAGE_TIMBER_READY ownedByAttempt=true")
        scene.components.set(TransformComponent(
            owner.convertPositionFrom(pose?.boardOrigin(spec) ?: spec.placement, null),
            EulerAngles(0f, 0f, 0f), Vector3(spec.sceneScale)))
        if (pose != null) t(scene).quaternion = owner.convertRotationFrom(pose.orientation, null)
        android.util.Log.i("PalmRailSingleRail", "BOARD_PLACEMENT world=${scene.convertPositionTo(Vector3.ZERO, null)} scale=${spec.sceneScale} headAnchored=${pose != null}")
        if (studio || spec.trial.getBoolean("neutralFillEnabled")) {
            val lighting = StudioLighting(spec.lighting, lightingEnvironmentScale ?: spec.lighting.number("environmentScale"))
            studioLighting = lighting
            try {
                lighting.attach(scene)
                Log.i("PalmRailSingleRail", "STUDIO_LIGHTS_ATTACHED sameView=true environmentScale=${lightingEnvironmentScale ?: spec.lighting.number("environmentScale")} localGain=${spec.lighting.number("defaultGain")}")
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("PalmRailSingleRail", "STUDIO_ENVIRONMENT_UNAVAILABLE", error)
            }
        }
        val nodes = all(model).associateBy { it.getName() }
        seatMainBoard(nodes)
        val pivot = checkNotNull(nodes["DRIVEN | crank pivot"]) { "模型缺少摇柄轴心" }
        modelPivot = pivot
        pivotPoint = pivot.convertPositionTo(Vector3.ZERO, scene)
        indicatorModel = checkNotNull(nodes["BADGE | bare hemisphere status lamp"]
            ?.components?.get(ModelComponent::class.java)) { "缺少状态灯" }
        idleIndicator = checkNotNull(indicatorModel?.materials?.get(0)).let { keep(it) }
        val green = spec.indicator.getJSONArray("green")
        greenIndicator = keep(UnlitMaterial.create().apply {
            setBaseColor(Color4(green.getDouble(0).toFloat(), green.getDouble(1).toFloat(),
                green.getDouble(2).toFloat(), green.getDouble(3).toFloat()))
        })
        scene.components.set(PhysicsWorldComponent())
        clock(0f)
        contactMaterial = keep(PhysicsMaterialResource(b.number("ContactFriction"), b.number("ContactFriction"), b.number("ContactRestitution")))
        val surfaces = nodes.filterKeys { it.startsWith("TRACK |") }
        check(surfaces.size == 4) { "预期四片轨道碰撞网格，实际 ${surfaces.size}" }
        for ((name, surface) in surfaces) {
            val isFloor = name.startsWith("TRACK | floor")
            surface.setName(if (isFloor) "rail.floor" else "rail.wall")
            if (isFloor) { liftFloors.add(surface); rollingFloors.add(surface) }
            attachStaticCollider(surface)
        }
        val rig = attachEndpoints(scene, content, real)
        endpoints = rig
        ballMaterial = keep(PhysicallyBasedMaterial.create().apply {
            val color = spec.endpoints.getJSONArray("steelColor")
            setBaseColor(Color4(color.getDouble(0).toFloat(), color.getDouble(1).toFloat(),
                color.getDouble(2).toFloat(), color.getDouble(3).toFloat()))
            setMetallic(spec.endpoints.number("steelMetallic"))
            setRoughness(spec.endpoints.number("steelRoughness"))
        })
        ballMesh = keep(MeshResource.createSphere(spec.radius))
        ballShape = keep(ShapeResource.createSphere(spec.radius))
        ball = createBall(scene, rig.spawn, "ball.0")
        pusherBody = mountPost(scene, nodes.getValue("DRIVEN | pusher"), "pusher.collider", bladeDimensions)
        keeperBody = mountPost(scene, nodes.getValue("DRIVEN | front keeper"), "keeper.collider", keeperDimensions)
        studioLighting?.let { lighting ->
            val receivers = all(scene).filter { it.components[ModelComponent::class.java] != null }.toList()
            receivers.forEach(lighting::applyTo)
            check(receivers.all { it.components[ImageBasedLightReceiverComponent::class.java] != null })
            Log.i("PalmRailSingleRail", "LIGHTING_RECEIVERS_BOUND count=${receivers.size} dynamicBallIncluded=true")
        }
        animate(initial = true)
        subscription = content.subscribe(CollisionEvents.Update::class.java) { event ->
            val liveBall = ball ?: return@subscribe
            val other = when (liveBall) {
                event.entityA -> event.entityB
                event.entityB -> event.entityA
                else -> return@subscribe
            }
            val now = java.lang.System.nanoTime() / 1e9
            // Stable entity identities avoid SDK name lookups and strings for every contact.
            if (other in liftFloors) latestTrackContact = now
            if (other in rollingFloors) lastRollingContact = now
            rig.contact(other, now)
        }
        if (real) {
            val grip = checkNotNull(nodes["DRIVEN | wooden grip"]) { "模型缺少握把" }
            val padding = cfg.number("gripCapturePaddingMeters")
            val diameter = 2f * (g.number("grip_radius") + padding)
            val hitSize = Vector3(diameter, diameter, g.number("grip_length") + 2f * padding) *
                (cfg.number("gripWorldSizeRatio") / spec.sceneScale)
            grip.components.set(CollisionComponent(listOf(keep(ShapeResource.createBox(hitSize))), contactMaterial,
                CollisionResponseMode.TRIGGER_LITE))
            grip.components.set(InteractableComponent())
            grip.components.set(HoverEffectComponent())
            gripTarget = grip
            pointerContent = content
            android.util.Log.i("PalmRailSingleRail", "NATIVE_GRIP_TARGET_READY ${spec.rail.asset} size=$hitSize")
            source = PinchSource()
            controller = ControllerGripSource()
        }
        allowMovement = true
        scene.enabled = true
        clock(1f)
    }

    private fun createBall(scene: Entity, spawn: Vector3, name: String): Entity {
        val sphere = entity(scene, name, spawn)
        sphere.components.set(ModelComponent(ballMesh, ballMaterial))
        studioLighting?.applyTo(sphere)
        sphere.components.set(CollisionComponent(listOf(ballShape), contactMaterial,
            CollisionResponseMode.COLLIDER_FULL, collisionInfoDetailLevel = CollisionInfoDetailLevel.DETAILED))
        val mass = b.number("BallMass")
        val inertia = (2f / 5f) * mass * spec.radius * spec.radius
        sphere.components.set(RigidBodyComponent(MassProperties(mass, Vector3(inertia)), RigidBodyMode.DYNAMIC).apply {
            collisionDetectionMode = CollisionDetectionMode.CONTINUOUS_DYNAMIC
            linearDamping = b.number("BallLinearDamping")
            angularDamping = b.number("BallAngularDamping")
        })
        sphere.components.set(PhysicsVelocityComponent())
        return sphere
    }

    private fun seatMainBoard(nodes: Map<String, Entity>) {
        val offset = cfg.number("boardSeatOffsetMeters")
        for ((name, node) in nodes) {
            val shift = when {
                name.startsWith("BOARD |") || name.startsWith("BADGE |") ||
                    name.startsWith("MOUNT | crank integral pin") -> offset
                name == "DRIVEN | crank pivot" || name == "MECHANISM | crank axle" ||
                    name == "MECHANISM | crank hub" || name == "MOUNT | crank integral wooden hub" ->
                    cfg.number("crankSeatOffsetMeters")
                name.startsWith("MOUNT | rail integral pin") -> {
                    val old = t(node).scaleVector
                    t(node).scaleVector = Vector3(old.x, old.y, old.z * cfg.number("railPinDepthScale"))
                    offset / 2
                }
                else -> continue
            }
            t(node).position += Vector3(0f, 0f, shift)
        }
    }

    private fun attachStaticCollider(entity: Entity) {
        val mesh = checkNotNull(entity.components[ModelComponent::class.java]?.mesh) {
            "缺少碰撞网格：${entity.getName()}"
        }
        entity.components.set(CollisionComponent(listOf(keep(ShapeResource.createStaticMesh(mesh))),
            contactMaterial, CollisionResponseMode.COLLIDER_FULL,
            collisionInfoDetailLevel = CollisionInfoDetailLevel.DETAILED))
    }

    private suspend fun attachEndpoints(scene: Entity, content: SpatialViewContent, real: Boolean): EndpointRig {
        val cfg = spec.endpoints
        val worldGap = cfg.number("boardGapMeters")
        check(worldGap >= b.number("MinimumBoardGap"))
        val gap = worldGap / spec.sceneScale
        val startRoot = entity(scene, "start", Vector3.ZERO)
        val goalRoot = entity(scene, "goal", Vector3.ZERO)
        suspend fun attachModel(root: Entity, asset: String, port: String, target: Vector3): Map<String, Entity> {
            val model = Entity.loadSuspend("asset://$asset.glb")
            if (closed) {
                model.destroy()
                throw kotlinx.coroutines.CancellationException("Attempt closed")
            }
            root.addChild(model)
            val nodes = all(model).associateBy { it.getName() }
            val local = nodes.getValue(port).convertPositionTo(Vector3.ZERO, root)
            val offset = t(root).quaternion.rotateVector(local)
            t(root).position = target - offset
            return nodes
        }
        val startAngle = Math.toRadians(spec.trial.getDouble("startYawDegrees"))
        t(startRoot).quaternion = Quat(0f, sin(startAngle / 2).toFloat(), 0f, cos(startAngle / 2).toFloat())
        val goalPort = Vector3(spec.span + gap + spec.trial.number("goalRightMeters") / spec.sceneScale,
            spec.points.last().y - cfg.number("goalPortBelowRailMeters") -
                spec.trial.number("goalDownMeters") / spec.sceneScale, spec.trackDepth)
        Log.i("PalmRailSingleRail", "TRIAL_LAYOUT startYaw=${spec.trial.getDouble("startYawDegrees")} goalPort=$goalPort verticalBoards=true")
        val startGap = spec.trial.number("startGapMeters") / spec.sceneScale
        val startNodes = attachModel(startRoot, "start", "Start_Port", Vector3(-startGap,
            spec.points.first().y + cfg.number("startPortAboveRailMeters"),
            spec.trackDepth + spec.trial.number("startLateralBiasMeters") / spec.sceneScale))
        val goalNodes = attachModel(goalRoot, "goal", "Goal_Port", goalPort)
        val sourceBounds = startNodes.getValue("Start_Rail").getVisualBounds(scene, recursive = true, enabledOnly = false)
        val trackGap = -sourceBounds.max.x * spec.sceneScale
        check(trackGap >= b.number("MinimumBoardGap")) { "起点槽间隙不足 $trackGap" }
        Log.i("PalmRailSingleRail", "START_TRACK_GEOMETRY_GAP meters=$trackGap")

        startNodes["Start_Direction"]?.destroy()
        val buttonModel = Entity.loadSuspend("asset://start-release-button.glb")
        if (closed) {
            buttonModel.destroy()
            throw kotlinx.coroutines.CancellationException("Attempt closed")
        }
        startRoot.addChild(buttonModel)
        mountStartButton(startRoot, buttonModel, content, real)
        val startPort = startNodes.getValue("Start_Port").convertPositionTo(Vector3.ZERO, startRoot)
        addEndpointGuide(startRoot, Vector3(startPort.x - cfg.number("railLengthMeters"),
            startPort.y, startPort.z), start = true)
        attachStaticCollider(startNodes.getValue("Start_Backstop"))
        val goalFloor = addEndpointGuide(scene, goalPort, start = false)
        val plate = goalNodes.getValue("Goal_SoftBumper")
        attachStaticCollider(goalNodes.getValue("Goal_Backstop"))
        attachStaticCollider(plate)

        val movingGate = startNodes.getValue("Start_Gate")
        val gateSize = cfg.getJSONArray("gateSizeMeters").vector()
        movingGate.components.set(CollisionComponent(listOf(keep(ShapeResource.createBox(gateSize))),
            contactMaterial, CollisionResponseMode.COLLIDER_FULL))
        movingGate.components.set(RigidBodyComponent(MassProperties(1f), RigidBodyMode.KINEMATIC).apply {
            collisionDetectionMode = CollisionDetectionMode.CONTINUOUS_SPECULATIVE
        })
        movingGate.components.set(PhysicsVelocityComponent())
        val spawn = startNodes.getValue("Start_BallSpawn").convertPositionTo(Vector3.ZERO, scene)
        val clearSpawn = Vector3(spawn.x - cfg.number("startSpawnClearanceMeters"),
            spawn.y + spec.radius - cfg.number("sourceBallRadiusMeters") + p.number("ball_drop"), spawn.z)
        val gateSound = EndpointRig.prepareSound(movingGate, keep(AudioResource.load("start gate", "asset://gate.wav")))
        try {
            val bellSound = EndpointRig.prepareSound(goalNodes.getValue("Goal_AudioAnchor"),
                keep(AudioResource.load("goal bell", "asset://bell.wav")))
            try {
                val landingEmitter = entity(scene, "goal.landing-audio", goalPort)
                val landingSound = EndpointRig.prepareSound(landingEmitter,
                    keep(AudioResource.load("steel on wood landing", "asset://goal-landing.wav")))
                return EndpointRig(spec, movingGate, goalNodes.getValue("Goal_BellPivot"), gateSound,
                    bellSound, landingSound, clearSpawn, goalPort, goalFloor, plate)
            } catch (e: Exception) {
                bellSound.close()
                throw e
            }
        } catch (e: Exception) {
            gateSound.close()
            throw e
        }
    }

    private fun mountStartButton(root: Entity, model: Entity, content: SpatialViewContent, real: Boolean) {
        val cap = all(model).first { it.getName() == "Red button | unmarked cap" }
        val target = all(model).first { it.getName() == "Red button | walnut bezel" }
        val capParent = checkNotNull(cap.getParent())
        val outward = capParent.convertPositionFrom(Vector3(0f, 0f, 1f), root) -
            capParent.convertPositionFrom(Vector3.ZERO, root)
        val cfg = spec.startButton
        val size = cfg.getJSONArray("hitboxSizeMeters").vector()
        val blank = keep(ShapeResource.createBox(size))
        val shape = keep(blank.offsetByTranslation(cap.convertPositionTo(Vector3.ZERO, target)))
        target.components.set(CollisionComponent(listOf(shape), contactMaterial, CollisionResponseMode.TRIGGER_LITE))
        target.components.set(InteractableComponent())
        target.components.set(HoverEffectComponent())
        publishStartButton(content, target) { releaseStart("accessibility") }
        buttonContent = content
        val center = cap.convertPositionTo(Vector3.ZERO, root)
        val dot = keep(MeshResource.createSphere(cfg.number("ringMarkerRadiusMeters")))
        val tint = keep(UnlitMaterial.create().apply { setBaseColor(Color4(0.98f, 0.89f, 0.67f, 1f)) })
        val count = cfg.getInt("ringSegments")
        val radius = cfg.number("ringRadiusMeters")
        val ring = List(count) { index ->
            val angle = index * (2 * PI / count)
            entity(root, "start.gaze.$index", center +
                Vector3(sin(angle).toFloat()*radius, cos(angle).toFloat()*radius, 0.006f))
        }
        val audio = EndpointRig.prepareSound(cap, keep(AudioResource.load("start key", "asset://start-key.wav")))
        startButton = StartButton(target, cap, outward * (1f / outward.length()), ring, dot, tint, audio, cfg, real)
        Log.i("PalmRailSingleRail", "START_BUTTON_READY rail=${spec.rail.asset}")
    }

    /**
     * Convex sections sample the authored floor profile; the saved model stays the render mesh.
     * The boards and their original port positions remain separate.
     */
    private fun addEndpointGuide(scene: Entity, inlet: Vector3, start: Boolean): Set<Entity> {
        val c = spec.endpoints
        val span = c.number("railLengthMeters")
        val count = c.getInt("collisionSegments")
        val run = span / count
        val wall = b.number("TrackWallThickness")
        val wallHeight = b.number("TrackWallHeight")
        val floor = b.number("FixedFloorThickness")
        val clear = c.number("clearWidthMeters")
        fun elevation(distance: Float): Float {
            if (start) {
                val t = (distance / c.number("startSlopeLengthMeters")).coerceIn(0f, 1f)
                return c.number("startSlopeDropMeters") * (1 - 3 * t * t + 2 * t * t * t)
            }
            val t = ((distance - c.number("goalEntryLengthMeters")) /
                c.number("goalSlopeLengthMeters")).coerceIn(0f, 1f)
            return (-c.number("goalSlopeDropMeters") * (0.5 - 0.5 * cos(PI * t))).toFloat()
        }
        val name = if (start) "start" else "goal"
        return (0 until count).mapTo(mutableSetOf()) { section ->
            val begin = section * run
            val left = elevation(begin)
            val right = elevation(begin + run)
            val angle = atan2(right - left, run)
            val x = inlet.x + begin + run / 2
            val y = inlet.y + (left + right) / 2
            val length = hypot(run, right - left)
            for (side in listOf(-1, 1)) {
                box(scene, "$name.wall.$section.$side",
                    Vector3(x - sin(angle) * wallHeight / 2, y + cos(angle) * wallHeight / 2,
                        inlet.z + side * (clear + wall) / 2),
                    Vector3(length, wallHeight, wall), angle)
            }
            box(scene, "$name.floor.$section",
                Vector3(x + sin(angle) * floor / 2, y - cos(angle) * floor / 2, inlet.z),
                Vector3(length, floor, clear + 2 * wall), angle)
        }
    }

    private fun clock(speed: Float) {
        if (clockSpeed == speed) return
        val world = root?.components?.get(PhysicsWorldComponent::class.java) ?: return
        world.simulationClock = SimulationClock(fixed, cfg.number("physicsCatchUpSeconds"), speed)
        clockSpeed = speed
    }

    fun acceptsInteractionTarget(candidate: Entity): Boolean = candidate == gripTarget || candidate == startButton?.target
    private fun acceptsGripTarget(candidate: Entity): Boolean = candidate == gripTarget

    fun logGripHitTest() {
        startButton?.target?.let { target ->
            val board = root
            if (board != null) {
                val center = target.convertPositionTo(Vector3.ZERO, board)
                val face = target.scene?.rayCast(center + Vector3(0f, 0f, .1f), Vector3(0f, 0f, -1f), .2f,
                    CollisionCastHitMode.NEAREST, CollisionGroup(), referenceEntity = board)
                val hit = face?.results?.firstOrNull()?.entity
                Log.i("PalmRailSingleRail", "START_BUTTON_PICK hit=${hit == target} target=${hit?.getName()} center=$center")
            }
        }
        val grip = gripTarget ?: return
        val distance = 2f * g.number("grip_length")
        fun hitsGrip(offset: Float): Boolean = grip.scene?.rayCast(
            Vector3(0f, offset, distance), Vector3(0f, 0f, -1f),
            distance, CollisionCastHitMode.NEAREST, CollisionGroup(), referenceEntity = grip
        )?.results?.firstOrNull()?.entity == grip
        val edge = cfg.number("gripCapturePaddingMeters")
        android.util.Log.i("PalmRailSingleRail",
            "NATIVE_GRIP_PICK ${spec.rail.asset} center=${hitsGrip(0f)} offset=$edge bothEdges=${hitsGrip(edge) && hitsGrip(-edge)}")
    }

    /** One captured spatial pointer owns drag until its up event or cancellation. */
    fun onSpatialPointer(events: List<SpatialPointerInfo>): Boolean {
        val buttonHandled = startButton?.acceptPointer(events, !closed && !paused) { kind ->
            releaseStart("spatial.${kind.name}")
        } == true
        val gripHandled = handleGripPointer(events)
        return buttonHandled || gripHandled
    }

    fun cancelSpatialPointers() {
        startButton?.cancelPointer()
        cancelNativeGrip()
    }

    private fun handleGripPointer(events: List<SpatialPointerInfo>): Boolean {
        val board = root ?: return false
        val content = pointerContent ?: return false
        if (gripTarget == null) return false
        if (closed || crank.frozen || nearSide != null) return false
        val owner = capturedPointer
        val event = if (owner != null) {
            events.firstOrNull { it.pointerId == owner }
        } else {
            events.firstOrNull {
                it.isDownEvent() && it.kind != InteractionKind.Poke &&
                    it.targetedEntity?.let(::acceptsGripTarget) == true
            }
        } ?: return false

        if (owner != null && !event.pressed) {
            if (event.isUpEvent()) {
                clearNativeGrip()
                android.util.Log.i("PalmRailSingleRail", "NATIVE_GRIP_UP")
            } else cancelNativeGrip()
            return true
        }

        if (owner == null) {
            capturedPointer = event.pointerId
            capturedKind = event.kind
            gripStartAngle = crank.radians
            nextDragLog = 0.0
            dragTravel = 0.0
            crank.resetGripBaseline()
            if (event.kind == InteractionKind.RayBasedPinch || event.kind == InteractionKind.Pointer) {
                val grip = checkNotNull(gripTarget).convertPositionTo(Vector3.ZERO, board)
                rayMotion = GripRayMotion(grip, cfg.number("rayMotionMinMeters"))
                applyDragPoint(grip)
                pollControllerDrag(board)
            }
            android.util.Log.i("PalmRailSingleRail", "NATIVE_GRIP_DOWN ${event.kind} controller=$controllerSide admitted=$admitted")
        }
        gripSamples++

        val ray = rayMotion
        if (ray != null) {
            val pose = event.inputDevicePose
            val raw = pose.rawPosition
            val rotation = pose.rawRotation.toQuaternion()
            // The SDK reports an identity pose when the event contains no device pose.
            val rawPosition = Vector3(raw.x, raw.y, raw.z)
            if (rawPosition.isFinite() && rotation.isFinite() &&
                (rawPosition != Vector3.ZERO || abs(rotation.w) < 1f)) {
                val origin = content.convertPosition(rawPosition, ViewCoordinateSpace.Global, board)
                val orientation = content.convertRotation(rotation, ViewCoordinateSpace.Global, board)
                ray.pose(GripMotionSource.EVENT_POSE, GripPose(origin, orientation))?.let(::applyDragPoint)
            }
        }
        val point = event.position3D
        if (point.x.isFinite() && point.y.isFinite() && point.z.isFinite()) {
            val ownerSpace = viewOwner ?: return true
            val inView = content.convertPosition(Vector3(point.x, point.y, point.z),
                ViewCoordinateSpace.Local, content.localSpatialCoordinateSpace)
            val local = board.convertPositionFrom(inView, ownerSpace)
            if (ray != null) {
                ray.pointer(local)?.let(::applyDragPoint)
            } else {
                val now = java.lang.System.nanoTime() / 1e9
                if (!pollHandDrag(board, now) && handSide == null) {
                    val offset = pointerOffset ?: (gripCenter(board) - local).also { pointerOffset = it }
                    applyDragPoint(local + offset)
                    val raw = event.inputDevicePose.rawPosition
                    logDrag(now, "SDK_POINTER", local, local + offset, event.uptimeMillis,
                        Vector3(raw.x, raw.y, raw.z))
                }
            }
        }
        return true
    }

    private fun gripCenter(board: Entity) =
        checkNotNull(gripTarget).convertPositionTo(Vector3.ZERO, board)

    private fun pollHandDrag(board: Entity, now: Double): Boolean {
        if (capturedKind != InteractionKind.DirectPinch && capturedKind != InteractionKind.GazePinch) return false
        val points = source?.pinchPoints(now, cfg.getDouble("frameAge")).orEmpty()
        val point = handSide?.let { side -> points.firstOrNull { it.side == side } }
            ?: if (handSide == null) {
                if (capturedKind == InteractionKind.DirectPinch) {
                    val grip = gripCenter(board)
                    points.minByOrNull { (board.convertPositionFrom(it.position, null) - grip).length() }
                } else points.maxByOrNull { it.strength }
            } else null
        if (point == null) return false
        val local = board.convertPositionFrom(point.position, null)
        val offset = handOffset ?: (gripCenter(board) - local).also {
            handSide = point.side
            handOffset = it
            crank.resetGripBaseline()
            android.util.Log.i("PalmRailSingleRail", "HAND_DRAG_BIND kind=$capturedKind side=$handSide local=$local")
        }
        val mapped = local + offset
        applyDragPoint(mapped)
        logDrag(now, "SDK_PINCH_$handSide", local, mapped)
        return true
    }

    private fun logDrag(now: Double, source: String, local: Vector3, mapped: Vector3,
                        eventMs: Long? = null, device: Vector3? = null) {
        if (!com.palmrail.singlerail.BuildConfig.DEBUG || now < nextDragLog) return
        nextDragLog = now + cfg.getDouble("dragLogIntervalSeconds")
        android.util.Log.i("PalmRailSingleRail",
            "HAND_DRAG t=$now eventMs=$eventMs source=$source device=$device local=$local mapped=$mapped pivot=$pivotPoint " +
                "angle=${crank.radians} travel=$dragTravel stroke=${crank.stroke} admitted=$admitted")
    }

    private fun pollControllerDrag(board: Entity) {
        val ray = rayMotion ?: return
        val source = controller ?: return
        val side = controllerSide ?: source.pressingSide()?.also { controllerSide = it } ?: return
        val pose = source.pose(side) ?: return
        val local = GripPose(board.convertPositionFrom(pose.origin, null),
            board.convertRotationFrom(pose.rotation, null))
        ray.pose(GripMotionSource.CONTROLLER_POSE, local)?.let(::applyDragPoint)
    }

    private fun applyDragPoint(point: Vector3) {
        val dx = (point.x - pivotPoint.x).toDouble()
        val dy = (point.y - pivotPoint.y).toDouble()
        if (hypot(dx, dy) < cfg.number("dragMinRadiusMeters")) {
            crank.resetGripBaseline()
            return
        }
        val before = crank.radians
        crank.dragTo(atan2(-dy, dx), admitted)
        if (before != crank.radians) {
            dragTravel += abs(crank.radians - before)
            updateCrankModel()
        }
    }

    private fun clearNativeGrip() {
        if (capturedPointer != null || nearSide != null) {
            android.util.Log.i("PalmRailSingleRail", "GRIP_MOTION source=${rayMotion?.active} controller=$controllerSide " +
                "hand=$handSide pointerSamples=$gripSamples radians=${crank.radians - gripStartAngle} travel=$dragTravel admitted=$admitted")
        }
        nearSide = null
        nearLastObserved = Double.NEGATIVE_INFINITY
        capturedPointer = null
        capturedKind = null
        controllerSide = null
        handSide = null
        handOffset = null
        pointerOffset = null
        rayMotion = null
        gripSamples = 0
        sawTrackingDuringCapture = false
        crank.resetGripBaseline()
    }

    fun cancelNativeGrip() {
        if (capturedPointer == null && nearSide == null) return
        pause()
        android.util.Log.i("PalmRailSingleRail", "NATIVE_GRIP_CANCEL")
    }

    private fun captureNearPinch(board: Entity, point: PinchPoint, now: Double) {
        if (capturedPointer == null ||
            (capturedKind == InteractionKind.DirectPinch && (handSide == null || handSide == point.side))) {
            val grip = gripCenter(board)
            if (handSide != point.side || handOffset == null) {
                handOffset = grip - board.convertPositionFrom(point.position, null)
                crank.resetGripBaseline()
            }
            if (capturedPointer == null) {
                gripStartAngle = crank.radians
                dragTravel = 0.0
                nextDragLog = 0.0
            }
            capturedPointer = null
            capturedKind = InteractionKind.DirectPinch
            nearSide = point.side
            nearLastObserved = now
            handSide = point.side
            android.util.Log.i("PalmRailSingleRail", "NEAR_PINCH_DOWN side=${point.side} strength=${point.strength}")
        }
    }

    private fun latchNearPinch(board: Entity, now: Double) {
        val palm = source ?: return
        val grip = gripTarget ?: return
        val maxAge = cfg.getDouble("frameAge")
        val press = cfg.number("nearPinchPress")
        val release = cfg.number("nearPinchRelease")
        val points = palm.pinchPoints(now, maxAge)
        val newPinches = points.filter { it.strength >= press && nearPressed.add(it.side) }
        nearPressed.removeAll { side ->
            palm.hasFreshHand(side, now, maxAge) && points.none { it.side == side && it.strength > release }
        }
        if (nearSide != null || crank.frozen) return
        val padding = cfg.number("nearGripPaddingMeters")
        val radius = g.number("grip_radius") * spec.sceneScale + padding
        val halfLength = g.number("grip_length") * spec.sceneScale / 2 + padding
        val candidates = if (capturedKind == InteractionKind.DirectPinch)
            points.filter { it.strength >= press } else newPinches
        val measured = candidates.map { it to grip.convertPositionFrom(it.position, null) * spec.sceneScale }
        val nearest = measured.filter { (_, delta) -> hypot(delta.x, delta.y) <= radius && abs(delta.z) <= halfLength }
            .minByOrNull { it.second.length() }
        if (nearest != null) {
            captureNearPinch(board, nearest.first, now)
        } else if (com.palmrail.singlerail.BuildConfig.DEBUG && capturedPointer == null) {
            for ((point, delta) in measured) {
                android.util.Log.i("PalmRailSingleRail",
                    "NEAR_PINCH_MISS side=${point.side} strength=${point.strength} gripDeltaMeters=$delta")
            }
        }
    }

    private fun nearHand(now: Double, side: PinchSide): CrankInput {
        val maxAge = cfg.getDouble("frameAge")
        val palm = source
        val point = palm?.pinchPoints(now, maxAge)?.firstOrNull { it.side == side }
        if (palm?.hasFreshHand(side, now, maxAge) == true) {
            nearLastObserved = now
            if (point == null || point.strength <= cfg.number("nearPinchRelease")) {
                clearNativeGrip()
                android.util.Log.i("PalmRailSingleRail", "NEAR_PINCH_UP side=$side")
                return CrankInput(Grip.OPEN)
            }
        }
        if (now - nearLastObserved > maxAge) {
            requiresHandRecovery = true
            trackingReady = false
            cancelNativeGrip()
            return CrankInput(Grip.LOST)
        }
        return CrankInput(Grip.HELD)
    }

    private fun nativeHand(now: Double): CrankInput {
        val handCapture = capturedKind == InteractionKind.DirectPinch ||
            capturedKind == InteractionKind.GazePinch
        val freshHands = (handCapture || requiresHandRecovery) &&
            source?.hasFreshHands(now, cfg.getDouble("frameAge")) == true
        trackingReady = !requiresHandRecovery || freshHands
        root?.let { latchNearPinch(it, now) }
        nearSide?.let { return nearHand(now, it) }
        if (capturedPointer == null) return CrankInput(Grip.OPEN)
        if (sawTrackingDuringCapture && !freshHands) {
            requiresHandRecovery = true
            trackingReady = false
            cancelNativeGrip()
            return CrankInput(Grip.LOST)
        }
        sawTrackingDuringCapture = sawTrackingDuringCapture || freshHands
        return CrankInput(Grip.HELD)
    }

    fun tick(now: Double, dt: Double, simulated: Boolean, simulatedDirection: Int, visibleStage: Boolean): String {
        if (closed) return "已退出"
        if (startButton?.tick(now, !paused, visibleStage) == true) releaseStart("gaze")
        val sphere = ball ?: return "加载模型"
        val input = if (simulated) {
            trackingReady = true
            if (commandedHold) {
                simulationAngle += simulatedDirection * dt * 2 * PI * p.getInt("turns") / p.getDouble("drive_seconds")
            }
            CrankInput(if (commandedHold) Grip.HELD else Grip.OPEN, simulationAngle)
        } else nativeHand(now)
        if (!allowMovement) return "尚未就绪"
        val position = t(sphere).position
        if (BuildConfig.DEBUG && crank.atOutlet && position.x > spec.span && !goalReached && now >= nextFlightLog) {
            Log.i("PalmRailSingleRail", "GOAL_FLIGHT now=$now position=$position velocity=${sphere.components[PhysicsVelocityComponent::class.java]?.linearVelocity}")
            nextFlightLog = now + spec.trial.getDouble("flightLogSeconds")
        }
        val recent = now - latestTrackContact <= cfg.getDouble("frameAge")
        val entryClear = position.x - spec.radius >= g.number("blade_start_x") +
            bladeDimensions.x / 2 + p.number("entry_rear_clearance")
        val containedAcross = abs(position.z - spec.trackDepth) + spec.radius <= b.number("TrackClearWidth") / 2f
        if (!crank.frozen && !admitted && recent && entryClear && containedAcross) {
            admitted = true
            indicatorModel?.materials?.set(0, checkNotNull(greenIndicator))
            android.util.Log.i("PalmRailSingleRail", "INDICATOR_GREEN ${spec.rail.asset}")
        }
        val outlet = recent && position.x-spec.radius >= p.number("platform_start_x") &&
            position.x+spec.radius <= spec.span &&
            abs(position.z-spec.trackDepth)+spec.radius <= b.number("TrackClearWidth")/2 &&
            abs(position.y-spec.radius-spec.points.last().y) <= b.number("TrackWallHeight")
        val wasAtOutlet = crank.atOutlet
        crank.advance(dt, input, admitted, outlet)
        if (!simulated && input.grip == Grip.HELD) root?.let {
            pollControllerDrag(it)
            pollHandDrag(it, now)
        }
        if (crank.frozen) clock(0f) else {
            animate()
            if (crank.atOutlet && !wasAtOutlet) {
                val rearMode = pusherBody?.components?.get(CollisionComponent::class.java)?.collisionResponseMode
                val frontMode = keeperBody?.components?.get(CollisionComponent::class.java)?.collisionResponseMode
                android.util.Log.i("PalmRailSingleRail", "OUTLET_POSTS_CLEAR rear=$rearMode front=$frontMode ${observation()}")
            }
            endpoints?.tick(position, crank.atOutlet, now, dt)
        }
        return when {
            crank.frozen -> if (trackingReady) "已暂停 · 可手动继续" else "等待手追踪"
            endpoints?.struck == true -> "终点已响铃"
            endpoints?.entered == true -> "已进入终点"
            crank.atOutlet -> "到达抬升出口"
            input.grip == Grip.HELD -> if (admitted) "已抓住" else "已抓住 · 空轨试摇"
            !startReleased -> "等待放球"
            !admitted -> "等待进球"
            else -> "已松手"
        }
    }

    fun setSimulatedGrip(held: Boolean) {
        if (held && !commandedHold) simulationAngle = crank.radians
        commandedHold = held
    }
    fun pause() {
        clearNativeGrip()
        crank.pause()
        clock(0f)
    }
    fun resume() {
        crank.resume(trackingReady)
        if (!crank.frozen) {
            requiresHandRecovery = false
            clock(1f)
        }
    }

    private fun trackHeight(x: Float): Float {
        if (x <= 0) return spec.points.first().y
        val points = spec.points
        val index = (x / spec.span * points.lastIndex).toInt().coerceIn(0, points.lastIndex-1)
        val a = points[index]; val b = points[index+1]
        val slope = (b.y-a.y)/(b.x-a.x)
        return a.y + (x-a.x)*slope
    }

    private fun updateCrankModel() {
        val radians = crank.radians
        if (displayedCrankRadians == radians) return
        modelPivot?.let {
            t(it).quaternion = rotation(-radians)
            displayedCrankRadians = radians
        }
    }

    private fun resetPost(scene: Entity, previous: Entity?, dimensions: Vector3): Entity {
        val old = checkNotNull(previous)
        val visual = old.getChildren().single()
        val shape = checkNotNull(old.components[CollisionComponent::class.java]).collisionShape.single()
        val next = mountPost(scene, visual, old.getName(), dimensions, shape)
        old.destroy()
        return next
    }

    private fun mountPost(scene: Entity, model: Entity, name: String, dimensions: Vector3, shape: ShapeResource? = null): Entity {
        val body = box(scene, name, Vector3.ZERO, dimensions, 0f, moving = true, shape = shape)
        model.removeFromParent()
        body.addChild(model)
        t(model).position = Vector3.ZERO
        t(model).quaternion = rotation(0.0)
        return body
    }

    private fun drivePost(body: Entity, at: Float, dimensions: Vector3, retracted: Boolean, initial: Boolean) {
        val halfHeight = dimensions.y / 2
        val offset = if (retracted) -halfHeight - cfg.number("retractedPostClearanceMeters")
            else halfHeight + g.number("blade_floor_clearance")
        val target = Vector3(at, trackHeight(at) + offset, spec.trackDepth)
        val shape = checkNotNull(body.components[CollisionComponent::class.java])
        val mode = if (retracted) CollisionResponseMode.TRIGGER_LITE else CollisionResponseMode.COLLIDER_FULL
        if (shape.collisionResponseMode != mode) shape.collisionResponseMode = mode
        val transform = t(body)
        if (initial) {
            transform.position = target
            body.components[PhysicsVelocityComponent::class.java]?.linearVelocity = Vector3.ZERO
            return
        }

        val slide = (target - transform.position) * (1f / cfg.number("kinematicResponseSeconds"))
        val speed = slide.length()
        val limit = cfg.number("kinematicMaxMetersPerSecond")
        val velocity = if (speed > limit) slide * (limit / speed) else slide
        val scaledVelocity = velocity * spec.sceneScale
        val drive = checkNotNull(body.components[PhysicsVelocityComponent::class.java])
        val worldVelocity = pose?.rotate(scaledVelocity) ?: scaledVelocity
        if (drive.linearVelocity != worldVelocity) drive.linearVelocity = worldVelocity
    }

    private fun animate(initial: Boolean = false) {
        updateCrankModel()
        val start = g.number("blade_start_x")
        val x = start + crank.stroke.toFloat() * (g.number("blade_end_x") - start)
        pusherBody?.let {
            drivePost(it, x, bladeDimensions, !admitted || crank.atOutlet || crank.stroke < 0, initial)
        }
        keeperBody?.let {
            val at = max(g.number("keeper_exit_x"), x + g.number("keeper_offset_x"))
            val retract = crank.atOutlet || crank.stroke <= -p.getDouble("rollback_eject_progress")
            drivePost(it, at, keeperDimensions, retract, initial)
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        clearNativeGrip()
        gripTarget = null
        pointerContent = null
        source?.close()
        controller?.close()
        subscription?.cancel()
        liftFloors.clear()
        rollingFloors.clear()
        startButton?.let { button ->
            buttonContent?.clearAccessibilityActions(button.target)
            button.close()
        }
        buttonContent = null
        endpoints?.close()
        viewOwner?.destroy()
        viewOwner = null
        root = null
        ball = null
        studioLighting?.close()
        studioLighting = null
        resources.asReversed().forEach { it.close() }
        resources.clear()
    }
}
