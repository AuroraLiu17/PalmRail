package com.palmrail.singlerail

import com.palmrail.singlerail.sdk.CrankAudio
import com.palmrail.singlerail.sdk.RailProbe
import kotlinx.coroutines.Job

/** One Stage attempt owns its physics, audio and asynchronous frame/test tasks. */
internal class TrialAttempt(val probe: RailProbe, val audio: CrankAudio) : AutoCloseable {
    private val tasks = mutableSetOf<Job>()
    var closed = false
        private set

    fun track(job: Job) {
        if (closed) job.cancel() else tasks.add(job)
    }

    override fun close() {
        if (closed) return
        closed = true
        tasks.forEach { it.cancel() }
        tasks.clear()
        try { probe.close() } finally { audio.close() }
    }
}
