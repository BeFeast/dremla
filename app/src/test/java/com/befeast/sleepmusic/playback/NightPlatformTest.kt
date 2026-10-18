package com.befeast.sleepmusic.playback

import android.accessibilityservice.AccessibilityService
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import android.view.WindowManager
import com.befeast.sleepmusic.MainActivity
import com.befeast.sleepmusic.playback.testing.RecordingControls
import com.befeast.sleepmusic.playback.testing.TestApp
import com.befeast.sleepmusic.playback.testing.TestGraph
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApp::class, shadows = [NightPlatformTest.LockServiceShadow::class, NightPlatformTest.PolicyShadow::class])
class NightPlatformTest {
    @Implements(AccessibilityService::class)
    class LockServiceShadow : org.robolectric.shadows.ShadowService() {
        @Implementation fun performGlobalAction(action: Int): Boolean {
            failure?.let { throw it }
            actions += action
            return accepted
        }
        companion object {
            val actions = mutableListOf<Int>()
            var accepted = true
            var failure: RuntimeException? = null
        }
    }

    @Implements(DevicePolicyManager::class)
    class PolicyShadow : org.robolectric.shadows.ShadowDevicePolicyManager() {
        @Implementation override fun lockNow() {
            calls++
            failure?.let { throw it }
        }
        companion object {
            var calls = 0
            var failure: RuntimeException? = null
        }
    }

    @Config(sdk = [28, 34])
    @Test fun routeSelectionRequiresFeatureAndActiveAdminOtherwiseConnectedAccessibility() {
        val app = RuntimeEnvironment.getApplication()
        val pm = shadowOf(app.packageManager)
        val policy = app.getSystemService(DevicePolicyManager::class.java)
        val route = AndroidSleepRoute(app)
        pm.setSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN, false)
        assertEquals(SleepRouteStatus.NotEnabled, route.status())
        assertFalse(route.sleep())
        LockServiceShadow.actions.clear()
        LockServiceShadow.accepted = true
        val service = Robolectric.buildService(NightAccessibilityService::class.java).create()
        NightAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service.get())
        assertTrue(LockServiceShadow.actions.isEmpty())
        assertEquals(SleepRouteStatus.Accessibility, route.status())
        assertTrue(route.sleep())
        assertEquals(listOf(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN), LockServiceShadow.actions)
        LockServiceShadow.accepted = false
        assertFalse(route.sleep())
        pm.setSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN, true)
        assertEquals(SleepRouteStatus.Accessibility, route.status())
        shadowOf(policy).setActiveAdmin(ComponentName(app, NightAdminReceiver::class.java))
        assertEquals(SleepRouteStatus.DeviceAdmin, route.status())
        PolicyShadow.calls = 0
        assertTrue(route.sleep())
        assertEquals(1, PolicyShadow.calls)
        pm.setSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN, false)
        assertEquals(SleepRouteStatus.Accessibility, route.status())
        service.get().onUnbind(Intent())
        assertEquals(SleepRouteStatus.NotEnabled, route.status())
        service.destroy()
    }

    @Config(sdk = [28, 34])
    @Test fun routeExceptionsFinishBlackWithoutCrashing() {
        val app = RuntimeEnvironment.getApplication()
        val pm = shadowOf(app.packageManager)
        val policy = app.getSystemService(DevicePolicyManager::class.java)
        val service = Robolectric.buildService(NightAccessibilityService::class.java).create()
        NightAccessibilityService::class.java.getDeclaredMethod("onServiceConnected").apply { isAccessible = true }.invoke(service.get())
        shadowOf(policy).setActiveAdmin(ComponentName(app, NightAdminReceiver::class.java))
        try {
            for (admin in listOf(false, true)) for (error in listOf(null, SecurityException(), IllegalStateException(), RuntimeException())) {
                if (admin && error == null) continue
                LockServiceShadow.accepted = error != null
                pm.setSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN, admin)
                PolicyShadow.failure = if (admin) error else null
                LockServiceShadow.failure = if (admin) null else error
                val h = com.befeast.sleepmusic.playback.testing.EngineHarness(sleepRoute = AndroidSleepRoute(app))
                h.launcherStart()
                h.sleepThrough(7_200_000)
                h.advance(50)
                assertEquals(Phase.Finished, h.state.phase)
                assertEquals(SleepRouteStatus.NotEnabled, h.state.sleepRouteStatus)
                assertTrue(h.state.blackScreen)
                assertFalse(h.state.closeActivity)
                h.engine.refreshSleepRoute()
                h.advance(100)
                assertEquals(listOf(1), h.events.sleepFailures)
            }
        } finally {
            PolicyShadow.failure = null
            LockServiceShadow.failure = null
            LockServiceShadow.accepted = true
            service.destroy()
        }
    }

    @Config(sdk = [28, 34])
    @Test fun missingPolicyFailsBlackAndEachSleepUsesOnePolicySnapshot() {
        val app = RuntimeEnvironment.getApplication()
        shadowOf(app.packageManager).setSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN, true)
        val policy = app.getSystemService(DevicePolicyManager::class.java)
        shadowOf(policy).setActiveAdmin(ComponentName(app, NightAdminReceiver::class.java))
        var reads = 0
        val route = AndroidSleepRoute(app) { reads++; if (reads == 1) policy else null }
        PolicyShadow.calls = 0
        assertTrue(route.sleep())
        assertEquals(1, reads)
        assertEquals(1, PolicyShadow.calls)
        assertFalse(route.sleep())
        assertEquals(2, reads)
        assertEquals(1, PolicyShadow.calls)
        val h = com.befeast.sleepmusic.playback.testing.EngineHarness(sleepRoute = route)
        h.launcherStart()
        h.sleepThrough(7_200_000)
        h.advance(50)
        assertEquals(Phase.Finished, h.state.phase)
        assertEquals(SleepRouteStatus.NotEnabled, h.state.sleepRouteStatus)
        assertTrue(h.state.blackScreen)
        assertFalse(h.state.closeActivity)
        h.advance(100)
        assertEquals(listOf(1), h.events.sleepFailures)
    }

    @Test fun tvOffDefaultsOnPersistsAndIsPublishedByNewEngine() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences(SharedPrefsPlaybackStore.FILE, 0).edit().clear().commit()
        val store = SharedPrefsPlaybackStore(app)
        assertTrue(store.tvOff)
        val h = com.befeast.sleepmusic.playback.testing.EngineHarness(store = store)
        h.engine.setTvOff(false)
        assertFalse(h.state.tvOff)
        assertFalse(SharedPrefsPlaybackStore(app).tvOff)
        val replacement = com.befeast.sleepmusic.playback.testing.EngineHarness(store = SharedPrefsPlaybackStore(app))
        assertFalse(replacement.state.tvOff)
        assertEquals(Phase.Idle, replacement.state.phase)
    }

    @Test fun blackWindowKeepsScreenOnAndSuccessfulEndFinishesEvenWhileStopped() {
        val controls = RecordingControls(SessionState(phase = Phase.Playing, blackScreen = true))
        TestApp.graph = TestGraph(controls)
        val activity = Robolectric.buildActivity(MainActivity::class.java, Intent()).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0)
        activity.pause().stop()
        controls.mutableState.value = SessionState(phase = Phase.Finished, blackScreen = true, closeActivity = true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.get().isFinishing)
        assertEquals(0, activity.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        assertTrue(controls.calls.isEmpty())
        activity.destroy()
    }

    @Test fun unsuccessfulEndStaysBlackWithoutKeepingPanelAwake() {
        val controls = RecordingControls(SessionState(phase = Phase.Finished, blackScreen = true))
        TestApp.graph = TestGraph(controls)
        val activity = Robolectric.buildActivity(MainActivity::class.java, Intent()).setup()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(activity.get().isFinishing)
        assertEquals(0, activity.get().window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        activity.pause().stop().destroy()
    }
}
