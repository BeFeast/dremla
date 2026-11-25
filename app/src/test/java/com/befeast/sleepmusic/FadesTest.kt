package com.befeast.sleepmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FadesTest {
    private val eps = 1e-9

    @Test
    fun curveEndpointsAndMonotonic() {
        assertEquals(0.0, Fades.curve(0.0), eps)
        assertEquals(1.0, Fades.curve(1.0), eps)
        assertEquals(0.0, Fades.curve(-3.0), eps)
        assertEquals(1.0, Fades.curve(7.0), eps)
        var prev = -1.0
        for (i in 0..1000) {
            val g = Fades.curve(i / 1000.0)
            assertTrue("monotonic at $i", g >= prev)
            prev = g
        }
        // Perceptual shape: half-way is well below half amplitude (~ -18 dB).
        assertEquals(0.125, Fades.curve(0.5), eps)
    }

    @Test
    fun sessionFadeInTakes30Seconds() {
        assertEquals(0.0, Fades.fadeIn(0, Fades.SESSION_FADE_IN_MS), eps)
        assertTrue(Fades.fadeIn(15_000, Fades.SESSION_FADE_IN_MS) < 0.2)
        assertTrue(Fades.fadeIn(29_000, Fades.SESSION_FADE_IN_MS) in 0.85..1.0)
        assertEquals(1.0, Fades.fadeIn(30_000, Fades.SESSION_FADE_IN_MS), eps)
        assertEquals(1.0, Fades.fadeIn(3_600_000, Fades.SESSION_FADE_IN_MS), eps)
    }

    @Test
    fun sleepFadeIsUnityBeforeWindowAndZeroAtEnd() {
        val fade = 10 * 60_000L
        assertEquals(1.0, Fades.sleepGain(2 * 3_600_000L, fade), eps)
        assertEquals(1.0, Fades.sleepGain(fade, fade), eps)
        assertEquals(0.125, Fades.sleepGain(fade / 2, fade), eps)
        assertEquals(0.0, Fades.sleepGain(0, fade), eps)
        assertEquals(0.0, Fades.sleepGain(-5_000, fade), eps)
        var prev = 2.0
        for (rem in fade downTo 0 step 1_000) {
            val g = Fades.sleepGain(rem, fade)
            assertTrue("never increases while time runs out", g <= prev)
            prev = g
        }
    }

    @Test
    fun shortDebugTimerWithShortFade() {
        // timer=3 min, fade=1 min: full volume for 2 min, then fade.
        val fade = 60_000L
        assertEquals(1.0, Fades.sleepGain(120_000, fade), eps)
        assertTrue(Fades.sleepGain(30_000, fade) in 0.1..0.2)
        assertEquals(0.0, Fades.sleepGain(0, fade), eps)
    }

    @Test
    fun trackEdgesAreSoft() {
        val dur = 300_000L
        assertEquals(0.0, Fades.trackEdgeGain(0, dur), eps)
        assertEquals(1.0, Fades.trackEdgeGain(150_000, dur), eps)
        assertEquals(0.0, Fades.trackEdgeGain(dur, dur), eps)
        assertTrue(Fades.trackEdgeGain(dur - 3_000, dur) in 0.1..0.2)
        // Unknown duration: only the fade-in edge applies.
        assertEquals(1.0, Fades.trackEdgeGain(10_000, 0), eps)
    }

    @Test
    fun loudnessOnlyAttenuates() {
        assertEquals(1.0, Fades.loudnessGain(null), eps)
        assertEquals(1.0, Fades.loudnessGain(-30.0), eps)
        assertEquals(1.0, Fades.loudnessGain(Fades.TARGET_LUFS), eps)
        assertEquals(0.5012, Fades.loudnessGain(-18.0), 1e-3) // 6 dB hotter -> half amplitude
    }

    @Test
    fun combinedMultipliesAndClamps() {
        assertEquals(0.25f, Fades.combined(0.5, 0.5), 1e-6f)
        assertEquals(1f, Fades.combined(3.0, 1.0), 1e-6f)
        assertEquals(0f, Fades.combined(1.0, -1.0), 1e-6f)
    }
}
