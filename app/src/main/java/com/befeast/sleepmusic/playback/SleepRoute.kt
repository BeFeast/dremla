package com.befeast.sleepmusic.playback

enum class SleepRouteStatus { DeviceAdmin, Accessibility, NotEnabled }

interface SleepRoute {
    fun status(): SleepRouteStatus
    /** True only when the platform accepted the sleep request. */
    fun sleep(): Boolean

    companion object {
        val NONE = object : SleepRoute {
            override fun status() = SleepRouteStatus.NotEnabled
            override fun sleep() = false
        }
    }
}
