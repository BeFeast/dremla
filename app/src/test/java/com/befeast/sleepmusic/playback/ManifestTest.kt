package com.befeast.sleepmusic.playback

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.media3.session.MediaSessionService
import com.befeast.sleepmusic.MainActivity
import com.befeast.sleepmusic.SleepMusicApp
import com.befeast.sleepmusic.playback.testing.MergedManifest
import com.befeast.sleepmusic.playback.testing.MergedManifest.android
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** PRD 1, 7, 13: the merged manifest (libraries included) declares exactly what playback needs. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ManifestTest {
    private val app = RuntimeEnvironment.getApplication()
    private val pm = app.packageManager

    @Test
    fun thePlaybackServiceIsAnExportedMediaPlaybackSessionService() {
        val resolved = pm.queryIntentServices(Intent(MediaSessionService.SERVICE_INTERFACE).setPackage(app.packageName), 0)
        assertEquals(PlaybackService::class.java.name, resolved.single().serviceInfo.name)
        val info = pm.getServiceInfo(ComponentName(app, PlaybackService::class.java), 0)
        assertTrue(info.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK, info.foregroundServiceType)
    }

    @Test
    fun permissionsArePlaybackOnly() {
        val permissions = MergedManifest.permissions
        listOf(
            "android.permission.FOREGROUND_SERVICE",
            "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
            "android.permission.WAKE_LOCK",
        ).forEach { assertTrue("requests $it", it in permissions) }
        assertEquals(
            setOf(
                "android.permission.FOREGROUND_SERVICE",
                "android.permission.FOREGROUND_SERVICE_MEDIA_PLAYBACK",
                "android.permission.WAKE_LOCK",
                "android.permission.INTERNET",
                "android.permission.ACCESS_NETWORK_STATE",
                "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
            ),
            permissions,
        )
        listOf(
            "android.permission.POST_NOTIFICATIONS",
            "android.permission.RECEIVE_BOOT_COMPLETED",
        ).forEach { assertFalse("does not request $it", it in permissions) }
        val requested = pm.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertEquals(permissions, requested)
    }

    @Test
    fun noMediaButtonReceiverCanStartADeadProcess() {
        val mediaButton = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(app.packageName)
        assertTrue(pm.queryBroadcastReceivers(mediaButton, 0).isEmpty())
        assertTrue(pm.queryIntentServices(mediaButton, 0).isEmpty())
        assertTrue(MergedManifest.elements("action").none { it.android("name") == Intent.ACTION_MEDIA_BUTTON })
        assertTrue(MergedManifest.elements("receiver").none { it.android("name").endsWith("MediaButtonReceiver") })
        val services = MergedManifest.elements("service").map { it.android("name") }
        assertEquals(setOf(PlaybackService::class.java.name, NightAccessibilityService::class.java.name), services.toSet())
    }

    @Test
    fun sleepComponentsRequireSystemBindingPermissions() {
        val service = pm.getServiceInfo(ComponentName(app, NightAccessibilityService::class.java), PackageManager.GET_META_DATA)
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", service.permission)
        assertTrue(service.exported)
        assertTrue(service.metaData.getInt("android.accessibilityservice") != 0)
        val receiver = pm.getReceiverInfo(ComponentName(app, NightAdminReceiver::class.java), PackageManager.GET_META_DATA)
        assertEquals("android.permission.BIND_DEVICE_ADMIN", receiver.permission)
        assertTrue(receiver.exported)
        assertTrue(receiver.metaData.getInt("android.app.device_admin") != 0)
        assertEquals(listOf(com.befeast.sleepmusic.SessionCommandReceiver::class.java.name, NightAdminReceiver::class.java.name,
                "androidx.profileinstaller.ProfileInstallReceiver"),
            MergedManifest.elements("receiver").map { it.android("name") })
    }

    @Test
    fun sleepConfigurationsRequestNoEventsOrContentAndOnlyForceLock() {
        val ns = "http://schemas.android.com/apk/res/android"
        app.resources.getXml(com.befeast.sleepmusic.R.xml.night_accessibility).use { xml ->
            while (xml.next() != org.xmlpull.v1.XmlPullParser.START_TAG) Unit
            assertEquals("accessibility-service", xml.name)
            assertEquals("false", xml.getAttributeValue(ns, "canRetrieveWindowContent"))
            assertEquals(null, xml.getAttributeValue(ns, "accessibilityEventTypes"))
            assertEquals(null, xml.getAttributeValue(ns, "accessibilityFlags"))
        }
        val tags = mutableListOf<String>()
        app.resources.getXml(com.befeast.sleepmusic.R.xml.night_admin).use { xml ->
            while (xml.next() != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
                if (xml.eventType == org.xmlpull.v1.XmlPullParser.START_TAG) tags += xml.name
            }
        }
        assertEquals(listOf("device-admin", "uses-policies", "force-lock"), tags)
    }

    @Test
    fun theLauncherActivityIsSingleTaskSoATilePressArrivesAsNewIntent() {
        val info = pm.getActivityInfo(ComponentName(app, MainActivity::class.java), 0)
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, info.launchMode)
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).setPackage(app.packageName)
        assertEquals(MainActivity::class.java.name, pm.queryIntentActivities(launcher, 0).single().activityInfo.name)
    }

    @Test
    fun theApplicationHoldsThePlaybackGraph() {
        assertEquals(SleepMusicApp::class.java.name, MergedManifest.application.android("name"))
    }
}
