package com.palmrail.singlerail

import org.junit.Assert.assertEquals
import org.junit.Test

class TrialSceneOwnerTest {
    @Test fun backgroundReplacementReleasesTheOldSceneAndLateDisposalKeepsTheNewScene() {
        val owner = TrialSceneOwner()
        var firstClosed = 0
        var secondClosed = 0
        val first = owner.acquire(AutoCloseable { firstClosed++ })
        val second = owner.acquire(AutoCloseable { secondClosed++ })
        assertEquals(1, firstClosed)
        assertEquals(0, secondClosed)

        first.close()
        assertEquals(0, secondClosed)
        second.close()
        second.close()
        owner.close()
        assertEquals(1, firstClosed)
        assertEquals(1, secondClosed)
    }

    @Test fun applicationExitReleasesTheActiveSceneExactlyOnce() {
        val owner = TrialSceneOwner()
        var released = 0
        val scene = owner.acquire(AutoCloseable { released++ })
        owner.close()
        scene.close()
        owner.close()
        assertEquals(1, released)
    }
}
