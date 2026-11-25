package com.befeast.sleepmusic.playback

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** Admin on capable devices, otherwise a currently connected accessibility service. */
class AndroidSleepRoute(
    context: Context,
    private val policyProvider: () -> DevicePolicyManager? = {
        context.applicationContext.getSystemService(DevicePolicyManager::class.java)
    },
) : SleepRoute {
    private val app = context.applicationContext
    private val admin = ComponentName(app, NightAdminReceiver::class.java)

    private fun status(policy: DevicePolicyManager?): SleepRouteStatus = when {
        app.packageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN) &&
            policy?.isAdminActive(admin) == true -> SleepRouteStatus.DeviceAdmin
        NightAccessibilityService.connected != null -> SleepRouteStatus.Accessibility
        else -> SleepRouteStatus.NotEnabled
    }

    override fun status(): SleepRouteStatus = try {
        status(policyProvider())
    } catch (_: RuntimeException) {
        SleepRouteStatus.NotEnabled
    }

    override fun sleep(): Boolean = try {
        // Capability and action use the same service snapshot; the service may be absent.
        val policy = policyProvider()
        when (status(policy)) {
            SleepRouteStatus.DeviceAdmin -> if (policy != null) { policy.lockNow(); true } else false
            SleepRouteStatus.Accessibility -> NightAccessibilityService.connected?.lockScreen() == true
            SleepRouteStatus.NotEnabled -> false
        }
    } catch (_: RuntimeException) {
        // Vendor implementations can throw beyond SecurityException/IllegalStateException.
        false
    }
}
