package com.befeast.sleepmusic

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.befeast.sleepmusic.ui.PlaceholderScene
import com.befeast.sleepmusic.ui.PlaceholderState

/** Launcher entry point. The scaffold shows only the black placeholder scene. */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PlaceholderScene(PlaceholderState())
        }
    }
}
