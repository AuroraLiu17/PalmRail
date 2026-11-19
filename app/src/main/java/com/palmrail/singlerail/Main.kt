package com.palmrail.singlerail

import android.util.Log
import com.palmrail.singlerail.platform.SpatialApplication
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.findViewTreeLifecycleOwner
import android.view.ViewTreeObserver
import com.pico.spatial.ui.foundation.gesture.TargetEntity
import com.pico.spatial.ui.foundation.gesture.detectSpatialPointerEvent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.palmrail.singlerail.sdk.*
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.AccessibilityComponent
import com.pico.spatial.ui.foundation.content.setAccessibilityActions
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.*
import com.pico.spatial.ui.foundation.content.SpatialView
import com.pico.spatial.ui.foundation.dsl.DefaultStage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import com.pico.spatial.ui.foundation.dsl.SpatialAppScope
import com.pico.spatial.ui.platform.containers.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.min

private const val logTag = "PalmRailSingleRail"

// App-owned high-contrast guidance card requested for the trial.
private val trialGuideBackground = androidx.compose.ui.graphics.Color(0xFFF8F4EB) // design-style: fixed-figma-color design/track-modules/single-rail-trial-v1.md contrast card
private val trialGuideForeground = androidx.compose.ui.graphics.Color(0xFF241C14) // design-style: fixed-figma-color design/track-modules/single-rail-trial-v1.md contrast text

fun mainApp(scope: SpatialAppScope, appScope: CoroutineScope, app: SpatialApplication) = with(scope) {
    DefaultStage {
        PicoTheme {
            val stageContext = LocalContext.current
            var leaving by remember { mutableStateOf(false) }
            val scripted = BuildConfig.DEBUG && app.requestedSmoke?.substringAfter(":") in setOf("repeat", "complete")
            if (app.launchReady) RailStage(
                stageContext, Rail.STEEP, leaving,
                smoke = scripted, smokeReturn = false, smokeResize = false,
                smokeRepeat = scripted && app.requestedSmoke?.substringAfter(":") == "repeat",
                studio = false, sceneOwner = app.scenes,
                lightingEnvironmentScale = app.lightingEnvironmentScale,
                back = {
                    if (!leaving) {
                        leaving = true
                        appScope.launch {
                            closeStage()
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun RailStage(
    context: android.content.Context,
    rail: Rail,
    leaving: Boolean,
    smoke: Boolean,
    smokeReturn: Boolean,
    smokeResize: Boolean,
    smokeRepeat: Boolean,
    studio: Boolean,
    sceneOwner: TrialSceneOwner,
    lightingEnvironmentScale: Float?,
    back: () -> Unit,
) {
    val defaults = remember(rail) { RailSpec(context, rail) }
    var size by remember(rail) { mutableIntStateOf(defaults.defaultSizePercent) }
    val spec = remember(rail, size) { if (size == defaults.sizePercent) defaults else RailSpec(context, rail, size) }
    var attempt by remember { mutableIntStateOf(0) }
    var simulation by remember { mutableStateOf(smoke) }
    var placementReady by remember { mutableStateOf(false) }
    var pose by remember { mutableStateOf<StagePose?>(null) }
    var reposition by remember { mutableIntStateOf(0) }
    var locating by remember { mutableStateOf(true) }
    var placementNotice by remember { mutableStateOf("") }
    LaunchedEffect(reposition) {
        locating = true
        val sampled = sampleStagePose(
            defaults.endpoints.getLong("headPoseTimeoutMs"),
            defaults.endpoints.number("minimumValidEyeHeightMeters")
        )
        if (sampled != null) {
            pose = sampled
            placementNotice = ""
        } else {
            placementNotice = "手动定位时，请戴好头显并面向轨道位置。"
        }
        placementReady = true
        locating = false
    }
    fun resize(delta: Int) {
        val next = (size + delta).coerceIn(spec.minSizePercent, spec.maxSizePercent)
        if (next == size) return
        Log.i(logTag, "BOARD_RESIZE from=$size to=$next")
        size = next
    }
    key(attempt, simulation, spec, pose) {
        val probe = remember { RailProbe(spec, pose, lightingEnvironmentScale) }
        val audio = remember { CrankAudio(context, spec) }
        val ownedAttempt = remember(probe, audio) { TrialAttempt(probe, audio) }
        var ready by remember { mutableStateOf(false) }
        var initializing by remember { mutableStateOf(false) }
        var completed by remember { mutableStateOf(false) }
        var simulatedDirection by remember { mutableIntStateOf(0) }
        var paused by remember { mutableStateOf(false) }
        var mayResume by remember { mutableStateOf(false) }
        var status by remember { mutableStateOf("加载轨道") }
        val view = LocalView.current
        DisposableEffect(probe, view) {
            val observer = view.viewTreeObserver
            val listener = ViewTreeObserver.OnWindowFocusChangeListener { focused ->
                if (!focused && !simulation) probe.cancelSpatialPointers()
            }
            observer.addOnWindowFocusChangeListener(listener)
            onDispose {
                if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
            }
        }
        DisposableEffect(ownedAttempt) {
            val lease = sceneOwner.acquire(ownedAttempt)
            Log.i(logTag, "TRIAL_SCENE_ACQUIRED exclusive=true")
            onDispose { lease.close() }
        }
        val stageLeaving by rememberUpdatedState(leaving)
        LaunchedEffect(ready) {
            if (!ready || ownedAttempt.closed) return@LaunchedEffect
            ownedAttempt.track(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!)
            var previous = System.nanoTime() / 1e9
            probe.logGripHitTest()
            while (true) {
                delay((1000.0 / spec.physics.getDouble("fps")).toLong())
                val now = System.nanoTime() / 1e9
                val lifecycle = view.findViewTreeLifecycleOwner()?.lifecycle
                val stageVisible = view.isShown && !stageLeaving &&
                    (lifecycle == null || lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
                val observed = probe.tick(now, min(now - previous, 4.0 / spec.physics.getDouble("fps")),
                    simulation, simulatedDirection, stageVisible)
                if (status != observed) {
                    status = observed
                    Log.i(logTag, "rail=${spec.rail.asset} input=${if(simulation) "simulated" else "tracked"} state=$observed")
                }
                previous = now
                audio.update(probe.angle, probe.hasBall, probe.rollingSpeed(now))
                val reached = probe.goalReached
                if (completed != reached) Log.i(logTag, "TRIAL_SUCCESS_VISIBLE=$reached")
                completed = reached
                paused = probe.paused
                mayResume = probe.mayResume
            }
        }
        LaunchedEffect(ready, simulation) {
            if (!ready || !simulation || !smoke || ownedAttempt.closed) return@LaunchedEffect
            ownedAttempt.track(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!)
            delay(700)
            if (smokeResize && size == defaults.sizePercent) {
                resize(spec.sizeStepPercent)
                return@LaunchedEffect
            }
            Log.i(logTag, "SMOKE gate-closed ${probe.observation()}")
            delay(350)
            Log.i(logTag, "SMOKE gate-closed-still ${probe.observation()}")
            probe.releaseStart("smoke")
            if (smokeRepeat) {
                delay(260)
                check(probe.releaseStart("smoke-during-run"))
            }
            var tries = 0
            while (!probe.hasBall && tries++ < 480) {
                delay(50)
                if (tries % 20 == 0) Log.i(logTag, "SMOKE start-handoff $tries ${probe.observation()}")
            }
            if (!probe.hasBall) {
                Log.w(logTag, "SMOKE ${spec.rail.asset} entry did not qualify ${probe.observation()}")
                return@LaunchedEffect
            }
            probe.setSimulatedGrip(true); simulatedDirection = 1
            delay(2000)
            simulatedDirection = 0
            Log.i(logTag, "SMOKE held ${probe.observation()}")
            delay(600)
            Log.i(logTag, "SMOKE still ${probe.observation()}")
            probe.setSimulatedGrip(false)
            delay(500)
            Log.i(logTag, "SMOKE released ${probe.observation()}")
            probe.setSimulatedGrip(true)
            delay(400)
            Log.i(logTag, "SMOKE regrasp ${probe.observation()}")
            simulatedDirection = -1
            delay(700)
            Log.i(logTag, "SMOKE reverse-held ${probe.observation()}")
            simulatedDirection = 1
            withTimeoutOrNull(10_000) {
                while (!probe.atOutlet) delay(10)
            }
            probe.setSimulatedGrip(false)
            simulatedDirection = 0
            Log.i(logTag, "SMOKE release-at-outlet ${probe.observation()}")
            withTimeoutOrNull(10_000) {
                while (!probe.goalReached) delay(50)
            }
            Log.i(logTag, "SMOKE terminal ${probe.observation()}")
            if (smokeRepeat && probe.goalReached) {
                check(probe.releaseStart("smoke-after-goal"))
                withTimeoutOrNull(3000) { while (!probe.hasBall) delay(25) }
                Log.i(logTag, "SMOKE repeat ${probe.observation()} admitted=${probe.hasBall}")
            }
            if (smokeReturn) back()
        }
        val sceneModifier = Modifier.fillMaxSize()
            .pointerInput(probe) {
                try {
                    detectSpatialPointerEvent(context, TargetEntity.any(probe::acceptsInteractionTarget)) { events ->
                        probe.onSpatialPointer(events)
                    }
                } finally {
                    probe.cancelSpatialPointers()
                }
            }
        if (placementReady) SpatialView(modifier = sceneModifier, attachments = {
            AttachmentPanel("singlerail.guide") {
                Column(Modifier.width(spec.trial.getInt("guideWidthDp").dp)
                    .background(trialGuideBackground.copy(alpha = spec.trial.number("guideBackgroundOpacity")), RoundedCornerShape(20.dp))
                    .padding(horizontal = 24.dp, vertical = 18.dp)) {
                    Text(if (completed) spec.trial.getString("successText")
                        else spec.trial.getString("guideText"),
                        modifier = Modifier.fillMaxWidth(),
                        style = PicoTheme.typography.bodyLarge,
                        fontSize = (if (completed) spec.trial.getInt("successFontSp")
                            else spec.trial.getInt("guideFontSp")).sp,
                        lineHeight = (if (completed) spec.trial.getInt("successLineHeightSp")
                            else spec.trial.getInt("guideLineHeightSp")).sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        color = trialGuideForeground,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    if (paused) Button(enabled = mayResume, onClick = { probe.resume() }) {
                        Text(if (mayResume) "继续" else "等待手部追踪")
                    }
                }
            }

        }, initial = { content, attachments ->
            if (ready || initializing || ownedAttempt.closed) return@SpatialView
            ownedAttempt.track(kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]!!)
            initializing = true
            try {
                probe.attach(content, real = !simulation, studio = studio)
                attachments.entity("singlerail.guide")?.let { guide ->
                    // The owner names the panel; its text and buttons retain native semantics.
                    guide.components.set(AccessibilityComponent(label = "轨道操作与结果提示", value = "可见").apply {
                        isAccessibilityElement = true
                    })
                    content.setAccessibilityActions(entity = guide, semanticId = "singlerail.guide") {}
                    probe.attachGuide(guide)
                }
                ready = true
                Log.i(logTag, "SCENE_READY ${spec.rail.asset}")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                ready = false
                status = "加载失败：${e.message}"
                Log.e(logTag, status, e)
                probe.close()
            } finally { initializing = false }
        })
    }
}

