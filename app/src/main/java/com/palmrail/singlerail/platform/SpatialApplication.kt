package com.palmrail.singlerail.platform

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.palmrail.singlerail.mainApp
import com.pico.spatial.ui.foundation.dsl.launch
import kotlinx.coroutines.*

class SpatialApplication : Application() {
    internal val scenes = com.palmrail.singlerail.TrialSceneOwner()
    var lightingEnvironmentScale: Float? = null
    var launchReady by mutableStateOf(false)
    var trackingPermissionsRequested = false
    var requestedSmoke by mutableStateOf<String?>(null)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    override fun onCreate() {
        super.onCreate()
        launch { mainApp(this, scope, this@SpatialApplication) }
    }
    override fun onTerminate() { scenes.close(); scope.cancel(); super.onTerminate() }
}
