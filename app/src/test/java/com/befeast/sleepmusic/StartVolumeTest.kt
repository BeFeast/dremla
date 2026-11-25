package com.befeast.sleepmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class StartVolumeTest {

    @Test
    fun defaultIs15OnTheBoxHdmiScale() {
        assertEquals(15, StartVolume.defaultIndex(25))
        assertEquals(15, StartVolume.index(StartVolume.DEFAULT_LEVEL, 25))
    }

    @Test
    fun defaultIsSixtyPercentRoundedOnAnyOtherMax() {
        assertEquals(9, StartVolume.defaultIndex(15))
        assertEquals(60, StartVolume.defaultIndex(100))
        assertEquals(6, StartVolume.defaultIndex(10))
        assertEquals(4, StartVolume.defaultIndex(7)) // 4.2
        assertEquals(10, StartVolume.defaultIndex(16)) // 9.6
        for (max in 1..200) {
            if (max == 25) continue
            assertEquals("max $max", (0.6 * max).roundToInt().coerceIn(1, max), StartVolume.defaultIndex(max))
        }
    }

    @Test
    fun menuChoicesOnTheBoxAreExactly8To20() {
        assertEquals(listOf(8, 10, 12, 15, 18, 20), StartVolume.choices(25))
    }

    @Test
    fun menuChoicesScaleWithTheStreamMax() {
        assertEquals(listOf(5, 6, 7, 9, 11, 12), StartVolume.choices(15)) // 4.8 6 7.2 9 10.8 12
        assertEquals(listOf(32, 40, 48, 60, 72, 80), StartVolume.choices(100))
        // Tiny max: duplicates collapse, never 0, never above max.
        assertEquals(listOf(2, 3, 4), StartVolume.choices(5))
        assertEquals(listOf(1), StartVolume.choices(1))
    }

    @Test
    fun indexIsAlwaysAudibleAndWithinMax() {
        for (max in 1..120) for (level in 1..25) {
            val i = StartVolume.index(level, max)
            assertTrue("level $level max $max -> $i", i in 1..max)
        }
        assertEquals(0, StartVolume.index(15, 0))
        assertEquals(25, StartVolume.index(40, 25))
        assertEquals(1, StartVolume.index(0, 25))
    }

    @Test
    fun cycleGoesThroughAllLevelsAndWraps() {
        var level = StartVolume.DEFAULT_LEVEL
        val seen = mutableListOf<Int>()
        repeat(6) {
            level = StartVolume.nextLevel(level, 25)
            seen += level
        }
        assertEquals(listOf(18, 20, 8, 10, 12, 15), seen)
    }

    @Test
    fun cycleFromAnOffListLevelGoesToTheNextOneUp() {
        assertEquals(15, StartVolume.nextLevel(13, 25))
        assertEquals(8, StartVolume.nextLevel(3, 25))
        assertEquals(8, StartVolume.nextLevel(25, 25))
    }

    @Test
    fun cycleSkipsLevelsThatGiveTheSameIndexOnASmallStream() {
        // max 5: 8->2, 10->2, 12->2, 15->3, 18->4, 20->4
        assertEquals(15, StartVolume.nextLevel(8, 5))
        assertEquals(18, StartVolume.nextLevel(15, 5))
        assertEquals(8, StartVolume.nextLevel(18, 5))
    }

    @Test
    fun soundOffWhenMutedOrZeroButNotWhenUnknown() {
        assertTrue(StartVolume.soundOff(muted = true, volume = 0))
        assertTrue(StartVolume.soundOff(muted = true, volume = 15))
        assertTrue(StartVolume.soundOff(muted = false, volume = 0))
        assertFalse(StartVolume.soundOff(muted = false, volume = 15))
        assertFalse(StartVolume.soundOff(muted = false, volume = -1))
    }
}
