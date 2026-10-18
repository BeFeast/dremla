package com.befeast.sleepmusic

import kotlin.math.roundToInt

/**
 * System STREAM_MUSIC volume set at the start of every session (pure math, no Android).
 *
 * The preference is stored as a *level* on the SEI600GO's HDMI scale (max index 25). On a stream
 * with another max the level is scaled: index = round(level * max / 25). The default level 15
 * therefore gives 15 on max 25 and round(0.6 * max) anywhere else.
 * Ties never occur: 2 * level * max is even, 25 * (2n + 1) is odd.
 */
object StartVolume {
    /** STREAM_MUSIC max index of the SEI600GO over HDMI; the menu choices are written in these steps. */
    const val REFERENCE_MAX = 25
    const val DEFAULT_LEVEL = 15

    /** Menu "Start volume" cycles through these (on the 25-step scale). */
    val LEVELS = listOf(8, 10, 12, 15, 18, 20)

    /** Stream index for [level] on a stream whose max index is [max]. Always 1..max (0 if max <= 0). */
    fun index(level: Int, max: Int): Int {
        if (max <= 0) return 0
        val raw = if (max == REFERENCE_MAX) level else (level.toDouble() * max / REFERENCE_MAX).roundToInt()
        return raw.coerceIn(1, max)
    }

    /** 15 when max == 25, otherwise round(0.6 * max). */
    fun defaultIndex(max: Int): Int = index(DEFAULT_LEVEL, max)

    /** What the menu offers on this stream (duplicates removed for tiny max values). */
    fun choices(max: Int): List<Int> = LEVELS.map { index(it, max) }.distinct()

    /**
     * Next level for the menu's cycle button: the next entry of [LEVELS] after [level] (wrapping
     * 20 -> 8) that gives a different stream index than [level] does on this stream. A level that is
     * not in [LEVELS] (debug extra) moves to the first entry above it.
     */
    fun nextLevel(level: Int, max: Int): Int {
        val start = LEVELS.indexOfFirst { it > level }.let { if (it < 0) 0 else it }
        val now = index(level, max)
        for (i in LEVELS.indices) {
            val candidate = LEVELS[(start + i) % LEVELS.size]
            if (index(candidate, max) != now) return candidate
        }
        return LEVELS[start]
    }

    /**
     * "Sound off" indicator: the stream is muted or at index 0 (getStreamVolume returns 0 while
     * muted). A negative [volume] means "not read yet" and does not count.
     */
    fun soundOff(muted: Boolean, volume: Int): Boolean = muted || volume == 0
}
