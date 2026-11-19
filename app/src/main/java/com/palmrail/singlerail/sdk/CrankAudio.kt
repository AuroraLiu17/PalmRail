package com.palmrail.singlerail.sdk

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import kotlin.math.PI
import kotlin.math.floor

class CrankAudio(context: Context, private val spec: RailSpec) : AutoCloseable {
    private val pool = SoundPool.Builder().setMaxStreams(4).setAudioAttributes(
        AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build()).build()
    private val cue = context.assets.openFd("crank-tick.wav").use { pool.load(it, 1) }
    private val boardCue = context.assets.openFd("board-activate.wav").use { pool.load(it, 1) }
    private val rollCue = context.assets.openFd("ball-roll.wav").use { pool.load(it, 1) }
    private var boardPlayed = false
    private var rollStream = 0
    private val spacing = 2 * PI / spec.audio.getInt("ticks_per_turn")
    private val volume = spec.audio.number("tick_peak")
    private val reverse = spec.audio.number("reverse_pitch_ratio")
    private var angle = 0.0

    fun update(next: Double, admitted: Boolean, speed: Float) {
        if (floor(next / spacing) != floor(angle / spacing))
            pool.play(cue, volume, volume, 1, 0, if (next >= angle) 1f else reverse)
        angle = next
        if (admitted && !boardPlayed) {
            val stream = pool.play(boardCue, spec.audio.number("board_gain"), spec.audio.number("board_gain"), 1, 0, 1f)
            boardPlayed = stream != 0
            if (boardPlayed) android.util.Log.i("PalmRailSingleRail", "BOARD_ACTIVATION_AUDIO stream=$stream")
        }
        val minimum = spec.audio.number("roll_min_speed")
        if (speed >= minimum) {
            val fraction = ((speed - minimum) /
                (spec.audio.number("roll_full_speed") - minimum)).coerceIn(0f, 1f)
            val gain = spec.audio.number("roll_peak") * fraction
            if (rollStream == 0) {
                rollStream = pool.play(rollCue, gain, gain, 1, -1, 1f)
                if (rollStream != 0) android.util.Log.i("PalmRailSingleRail", "ROLL_AUDIO_START")
            } else {
                pool.setVolume(rollStream, gain, gain)
            }
        } else if (rollStream != 0) {
            pool.stop(rollStream)
            rollStream = 0
            android.util.Log.i("PalmRailSingleRail", "ROLL_AUDIO_STOP")
        }
    }
    override fun close() { pool.release() }
}
