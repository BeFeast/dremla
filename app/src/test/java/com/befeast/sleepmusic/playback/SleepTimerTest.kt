package com.befeast.sleepmusic.playback

import com.befeast.sleepmusic.Fades
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD 4 / AC-6: default 120 min, selectable, last 10 min fade without steps to exactly 0. */
class SleepTimerTest {
    private val minute = 60_000L

    @Test
    fun defaultIs120MinutesAndChoicesAreFixed() {
        assertEquals(120, SleepTimer.DEFAULT_MINUTES)
        assertEquals(listOf(30, 45, 60, 90, 120, 180), SleepTimer.CHOICES)
        assertTrue(SleepTimer.DEFAULT_MINUTES in SleepTimer.CHOICES)
    }

    @Test
    fun valuesOutsideTheChoicesReadAsTheDefault() {
        SleepTimer.CHOICES.forEach { assertEquals(it, SleepTimer.sanitize(it)) }
        listOf(0, -5, 1, 61, 999).forEach { assertEquals(120, SleepTimer.sanitize(it)) }
    }

    @Test
    fun sleepFadeIsTheLastTenMinutesForEveryChoice() {
        SleepTimer.CHOICES.forEach { assertEquals(10 * minute, SleepTimer.fadeMsFor(it * minute)) }
        assertEquals(5 * minute, SleepTimer.fadeMsFor(5 * minute))
    }

    @Test
    fun countdownAndFadeWindow() {
        val timer = SleepTimer.start(1_000L, 120)
        assertEquals(120 * minute, timer.remainingMs(1_000L))
        assertFalse(timer.inFade(1_000L + 109 * minute))
        assertTrue(timer.inFade(1_000L + 110 * minute))
        assertEquals(1.0, timer.sleepGain(1_000L + 100 * minute), 0.0)
        assertEquals(0.0, timer.sleepGain(1_000L + 120 * minute), 0.0)
    }

    @Test
    fun fadeCurveHasNoStepsAtTheTickRateAndEndsAtExactlyZero() {
        SleepTimer.CHOICES.forEach { minutes ->
            val start = 5_000L
            val timer = SleepTimer.start(start, minutes)
            val end = start + minutes * minute
            val samples = generateSequence(end - 11 * minute) { it + PlaybackEngine.TICK_MS }
                .takeWhile { it <= end }
                .map { timer.sleepGain(it) }
                .toList()
            var maxStep = 0.0
            samples.zipWithNext().forEach { (a, b) ->
                assertTrue("non-increasing ($minutes min)", b <= a)
                maxStep = maxOf(maxStep, a - b)
            }
            assertTrue("max step $maxStep per tick ($minutes min)", maxStep <= 1e-3)
            // The steepest point of the cubic curve: 3 x 50 ms / 10 min.
            assertEquals(3.0 * PlaybackEngine.TICK_MS / Fades.DEFAULT_SLEEP_FADE_MS, maxStep, 1e-6)
            assertEquals(0.0, samples.last(), 0.0)
            assertEquals(1.0, samples.first(), 0.0)
        }
    }

    @Test
    fun rearmCountsFromNow() {
        val timer = SleepTimer.start(0L, 120)
        timer.rearm(115 * minute, 30)
        assertEquals(30 * minute, timer.remainingMs(115 * minute))
        assertFalse(timer.inFade(115 * minute))
    }

    @Test
    fun aLongerTimerChosenInsideTheFadeClimbsBackWithoutAJump() {
        val envelope = VolumeEnvelope()
        envelope.reset(0L)
        envelope.markPlaying(0L)
        val timer = SleepTimer.start(0L, 30)
        var now = 0L
        var last = 0f
        // Play into the fade until the sleep factor is low.
        while (now < 28 * minute) {
            now += PlaybackEngine.TICK_MS
            last = envelope.gain(now, now, -1, timer.sleepGain(now), null)
        }
        assertTrue("deep in the fade: $last", last < 0.05f)
        timer.rearm(now, 120)
        val climb = mutableListOf(last)
        repeat(100) {
            now += PlaybackEngine.TICK_MS
            climb += envelope.gain(now, now, -1, timer.sleepGain(now), null)
        }
        val maxUp = climb.zipWithNext().maxOf { (a, b) -> b - a }
        assertTrue("climbs back gradually, max step $maxUp", maxUp > 0f && maxUp < 0.06f)
        climb.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
        assertEquals(1f, climb.last(), 1e-6f)
    }
}
