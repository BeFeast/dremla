package com.befeast.sleepmusic.playback

import android.content.Intent
import android.os.Bundle

/** Builds the pure [LaunchSignal] from what the activity received. */
fun launchSignal(intent: Intent?, entry: LaunchEntry, savedInstanceState: Bundle?): LaunchSignal =
    LaunchSignal(
        entry = entry,
        hasIntent = intent != null,
        action = intent?.action,
        categories = intent?.categories?.toSet().orEmpty(),
        flags = intent?.flags ?: 0,
        hasSavedState = savedInstanceState != null,
    )
