package com.befeast.sleepmusic

import com.befeast.sleepmusic.ui.remainingParts
import org.junit.Assert.assertEquals
import org.junit.Test

/** Player remaining time: "1 h 47 min" style, whole minutes rounded up, never seconds. */
class RemainingTimeTest {
    private fun text(sec: Long) = remainingParts(sec).joinToString(" ") { (n, u) -> "$n $u" }

    @Test
    fun hoursAndMinutes() {
        assertEquals("1 h 47 min", text(3600L + 47 * 60))
        assertEquals("1 h 47 min", text(3600L + 46 * 60 + 1))
        assertEquals("1 h 53 min", text(2 * 3600L - 7 * 60))
    }

    @Test
    fun wholeHourDropsMinutes() {
        assertEquals("2 h", text(2 * 3600L))
        assertEquals("3 h", text(3 * 3600L))
    }

    @Test
    fun underAnHourIsMinutesOnly() {
        assertEquals("23 min", text(23 * 60L))
        assertEquals("7 min", text(6 * 60L + 40))
        assertEquals("1 min", text(1))
        assertEquals("0 min", text(0))
    }
}
