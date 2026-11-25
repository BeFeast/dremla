package com.befeast.sleepmusic.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp

/** The ground of every night scene: pure black, `brand_night_ground`. */
val PlaceholderGround = Color(0xFF000000)

/**
 * What the placeholder scene shows. The default is the shipped state: black ground only.
 * The real scenes replace this in the UI issue.
 */
@Immutable
data class PlaceholderState(
    /** Optional square in the top-left corner. Null in the shipped and reference state. */
    val marker: PlaceholderMarker? = null,
    val message: String? = null,
)

/** A small, known change that tests can inject into the scene. */
@Immutable
data class PlaceholderMarker(val size: Dp, val color: Color)

@Composable
fun PlaceholderScene(state: PlaceholderState, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().background(PlaceholderGround)) {
        state.message?.let {
            androidx.compose.foundation.text.BasicText(
                it, modifier = Modifier.align(Alignment.Center),
                style = androidx.compose.ui.text.TextStyle(color = Color(0xFFAD7A5B)),
            )
        }
        state.marker?.let { marker ->
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .size(marker.size)
                    .background(marker.color),
            )
        }
    }
}
