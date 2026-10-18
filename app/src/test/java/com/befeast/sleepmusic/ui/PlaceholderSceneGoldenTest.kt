package com.befeast.sleepmusic.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = Goldens.TV_1080P)
class PlaceholderSceneGoldenTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun placeholderBlack() {
        compose.setContent { PlaceholderScene(PlaceholderState()) }
        compose.onRoot().captureGolden("placeholder-black.png")
    }
}
