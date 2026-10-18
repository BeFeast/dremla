package com.befeast.sleepmusic.playback

import com.befeast.sleepmusic.playback.testing.EngineHarness
import kotlinx.coroutines.launch
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class CoroutineFailureTest {
    @Test
    fun settleFailsForAnUncaughtCoroutineException() {
        val h = EngineHarness()
        val error = IllegalStateException("engine failure")
        h.scope.launch { throw error }
        assertSame(error, assertThrows(AssertionError::class.java) { h.settle() }.cause)
    }

    @Test
    fun advanceFailsForAnUncaughtCoroutineException() {
        val h = EngineHarness()
        val error = IllegalStateException("engine failure")
        h.scope.launch { throw error }
        assertSame(error, assertThrows(AssertionError::class.java) { h.advance(50) }.cause)
    }
}
