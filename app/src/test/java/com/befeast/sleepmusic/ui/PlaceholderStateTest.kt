package com.befeast.sleepmusic.ui

import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceholderStateTest {
    @Test
    fun defaultStateShowsNothingButTheGround() {
        assertNull(PlaceholderState().marker)
    }

    @Test
    fun groundIsPureBlack() {
        assertEquals(0xFF000000.toInt(), PlaceholderGround.toArgb())
    }
}
