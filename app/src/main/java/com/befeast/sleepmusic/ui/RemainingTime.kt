package com.befeast.sleepmusic.ui

/** "1 h 47 min" / "2 h" / "23 min": whole minutes, rounded up, so it changes once a minute and never shows 0 early. */
internal fun remainingParts(totalSec: Long): List<Pair<Long, String>> {
    val totalMin = (totalSec.coerceAtLeast(0) + 59) / 60
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 && m == 0L -> listOf(h to "h")
        h > 0 -> listOf(h to "h", m to "min")
        else -> listOf(m to "min")
    }
}
