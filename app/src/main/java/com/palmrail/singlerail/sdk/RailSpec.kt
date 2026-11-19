package com.palmrail.singlerail.sdk

import android.content.Context
import com.pico.spatial.core.math.Vector3
import org.json.JSONArray
import org.json.JSONObject

enum class Rail(val asset: String, val title: String) {
    STANDARD("standard", "标准 · 240 mm"),
    STEEP("steep", "陡坡 · 360 mm")
}

fun JSONArray.vector() = Vector3(getDouble(0).toFloat(), getDouble(1).toFloat(), getDouble(2).toFloat())
fun JSONObject.number(key: String) = getDouble(key).toFloat()

class RailSpec(context: Context, val rail: Rail, sizePercent: Int? = null) {
    private val raw = JSONObject(context.assets.open("${rail.asset}.json").bufferedReader().use { it.readText() })
    val trial = JSONObject(context.assets.open("trial.json").bufferedReader().use { it.readText() })
    val endpoints = JSONObject(context.assets.open("endpoints.json").bufferedReader().use { it.readText() })
    val startButton = JSONObject(context.assets.open("start-button.json").bufferedReader().use { it.readText() })
    internal val lighting = JSONObject(context.assets.open("lighting.json").bufferedReader().use { it.readText() })
    val geometry = raw.getJSONObject("geometry")
    val physics = raw.getJSONObject("physics")
    val interaction = raw.getJSONObject("interaction")
    val base = raw.getJSONObject("baseline")
    val probe = raw.getJSONObject("probe")
    val indicator = raw.getJSONObject("indicator")
    val audio = raw.getJSONObject("audio")
    val defaultSizePercent = endpoints.getInt("defaultSizePercent")
    val minSizePercent = endpoints.getInt("minSizePercent")
    val maxSizePercent = endpoints.getInt("maxSizePercent")
    val sizeStepPercent = endpoints.getInt("sizeStepPercent")
    val sizePercent = (sizePercent ?: defaultSizePercent).coerceIn(minSizePercent, maxSizePercent)
    val sceneScale = this.sizePercent / 100f
    val boardDistance = endpoints.number("headBoardDistanceMeters")
    val boardBelowEyes = endpoints.number("headBoardBelowMeters")
    val placement = raw.getJSONArray("placement").vector().let {
        Vector3(it.x * sceneScale, it.y, endpoints.number("sceneDepthMeters"))
    }
    val points = raw.getJSONArray("centerline").let { data -> List(data.length()) { data.getJSONArray(it).vector() } }
    val radius = base.number("BallRadius")
    val span = geometry.number("span")
    val trackDepth = points.first().z
}
