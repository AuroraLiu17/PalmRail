package com.palmrail.singlerail.platform

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import com.pico.spatial.ui.platform.stub.SpatialLaunchActivity

private const val handPermission = "com.picovr.permission.HAND_TRACKING"
private const val eyePermission = "com.picovr.permission.EYE_TRACKING"
private const val trackingPermissionRequest = 31

class LaunchActivity : SpatialLaunchActivity() {
    private var deferredSmoke: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val owner = application as SpatialApplication
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0 && intent.hasExtra("singlerailEnvironmentScale")) {
            owner.lightingEnvironmentScale = intent.getFloatExtra("singlerailEnvironmentScale", 0f).coerceIn(0f, 1f)
        }
        val smoke = if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0)
            intent.getStringExtra("singlerailSmoke") else null
        val usesHands = smoke == null || smoke.substringAfter(":") == "native"
        val pending = listOf(handPermission, eyePermission)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (usesHands && pending.isNotEmpty() && !owner.trackingPermissionsRequested) {
            owner.trackingPermissionsRequested = true
            deferredSmoke = smoke
            requestPermissions(pending.toTypedArray(), trackingPermissionRequest)
        } else {
            owner.requestedSmoke = smoke
            owner.launchReady = true
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        Log.i("PalmRailSingleRail", "TRIAL_REOPEN reusedLauncher=true")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != trackingPermissionRequest) return
        for (permission in permissions) {
            val granted = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            Log.i("PalmRailSingleRail", "TRACKING_PERMISSION $permission granted=$granted")
        }
        (application as SpatialApplication).requestedSmoke = deferredSmoke
        (application as SpatialApplication).launchReady = true
        deferredSmoke = null
    }
}
