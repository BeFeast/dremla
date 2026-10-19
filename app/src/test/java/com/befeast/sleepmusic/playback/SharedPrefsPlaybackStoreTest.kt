package com.befeast.sleepmusic.playback

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** PRD 9, 15 / AC-13: like, never again, timer and start volume are stored locally and survive a restart. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SharedPrefsPlaybackStoreTest {
    private val app = RuntimeEnvironment.getApplication()

    private fun prefsFile() = File(app.dataDir, "shared_prefs/${SharedPrefsPlaybackStore.FILE}.xml")

    @Test
    fun defaults() {
        val store = SharedPrefsPlaybackStore(app)
        assertTrue(store.liked().isEmpty())
        assertTrue(store.blocked().isEmpty())
        assertEquals(BagState(), store.bag())
        assertEquals(120, store.timerMinutes)
    }

    @Test
    fun everythingIsReadBackByANewStore() {
        val store = SharedPrefsPlaybackStore(app)
        store.setLiked("amb-001", true)
        store.setLiked("pno-002", true)
        store.block("amb-003")
        store.saveBag(BagState(setOf("amb-001", "amb-005"), "amb-005"))
        store.timerMinutes = 45
        val again = SharedPrefsPlaybackStore(app)
        assertEquals(setOf("amb-001", "pno-002"), again.liked())
        assertEquals(setOf("amb-003"), again.blocked())
        assertEquals(BagState(setOf("amb-001", "amb-005"), "amb-005"), again.bag())
        assertEquals(45, again.timerMinutes)
    }

    @Test
    fun decisionsAreOnDiskRightAway() {
        val store = SharedPrefsPlaybackStore(app)
        store.setLiked("pno-007", true)
        store.block("amb-009")
        store.timerMinutes = 90
        // commit, not apply: a process killed right after the press still has it after the restart.
        val xml = prefsFile().readText()
        assertTrue(xml, xml.contains("pno-007"))
        assertTrue(xml, xml.contains("amb-009"))
        assertTrue(xml, xml.contains("\"timer_min\" value=\"90\""))
        assertTrue(xml, xml.contains("\"schema\" value=\"1\""))
    }

    @Test
    fun neverAgainDropsTheLike() {
        val store = SharedPrefsPlaybackStore(app)
        store.setLiked("amb-001", true)
        store.block("amb-001")
        assertTrue(store.liked().isEmpty())
        assertEquals(setOf("amb-001"), store.blocked())
        store.setLiked("amb-002", true)
        store.setLiked("amb-002", false)
        assertTrue(store.liked().isEmpty())
    }

    @Test
    fun invalidStoredValuesReadAsDefaults() {
        app.getSharedPreferences(SharedPrefsPlaybackStore.FILE, Context.MODE_PRIVATE).edit()
            .putInt(SharedPrefsPlaybackStore.KEY_TIMER, 77)
            .commit()
        val store = SharedPrefsPlaybackStore(app)
        assertEquals(120, store.timerMinutes)
        store.timerMinutes = 13
        assertEquals(120, store.timerMinutes)
    }

    @Test
    fun anEmptyBagHasNoLastTrack() {
        val store = SharedPrefsPlaybackStore(app)
        store.saveBag(BagState(emptySet(), null))
        assertNull(store.bag().lastId)
        assertTrue(store.bag().played.isEmpty())
    }
}
