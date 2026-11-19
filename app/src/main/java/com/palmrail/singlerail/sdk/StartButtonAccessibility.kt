package com.palmrail.singlerail.sdk

import com.pico.spatial.core.container.SpatialViewContent
import com.pico.spatial.core.ecs.AccessibilityComponent
import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.ui.foundation.content.setAccessibilityActions

internal fun publishStartButton(content: SpatialViewContent, target: Entity, release: () -> Boolean) {
    target.components.set(AccessibilityComponent(label = "起点放球按钮", value = "可放球").apply {
        isAccessibilityElement = true
    })
    content.setAccessibilityActions(entity = target, semanticId = "start.release-button") {
        onClick(label = "放球") { release() }
    }
}
