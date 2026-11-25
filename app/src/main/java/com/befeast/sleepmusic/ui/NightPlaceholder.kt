package com.befeast.sleepmusic.ui

import com.befeast.sleepmusic.playback.Phase
import com.befeast.sleepmusic.playback.SessionState
import com.befeast.sleepmusic.playback.SleepRouteStatus

/** #6 may display route status in its menu, but never over a black or finished scene. */
fun nightPlaceholder(session: SessionState, collectionMessage: String?): PlaceholderState =
    if (session.blackScreen || session.phase == Phase.Finished) PlaceholderState()
    else PlaceholderState(message = collectionMessage ?: when {
        session.sleepRouteStatus == SleepRouteStatus.NotEnabled -> "TV sleep route is not enabled."
        session.isActive -> "Music playing"
        else -> null
    })
