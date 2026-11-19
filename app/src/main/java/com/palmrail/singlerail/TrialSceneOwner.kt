package com.palmrail.singlerail

/** Application-scoped ownership prevents two Stage compositions retaining the same trial. */
internal class TrialSceneOwner : AutoCloseable {
    private var current: Lease? = null
    private var closed = false

    fun acquire(resources: AutoCloseable): Lease {
        check(!closed)
        current?.close()
        return Lease(resources).also { current = it }
    }

    inner class Lease(private val resources: AutoCloseable) : AutoCloseable {
        private var released = false

        override fun close() {
            if (released) return
            released = true
            if (current === this) current = null
            resources.close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        current?.close()
    }
}
