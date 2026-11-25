package com.befeast.sleepmusic.playback

import android.content.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PRD 1 / AC-2: only a launcher start autoplays. */
class LaunchClassifierTest {
    private fun signal(
        entry: LaunchEntry = LaunchEntry.Create,
        hasIntent: Boolean = true,
        action: String? = LaunchClassifier.ACTION_MAIN,
        categories: Set<String> = setOf(LaunchClassifier.CATEGORY_LEANBACK_LAUNCHER),
        flags: Int = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
        saved: Boolean = false,
    ) = LaunchSignal(entry, hasIntent, action, categories, flags, saved)

    private fun reason(signal: LaunchSignal) = LaunchClassifier.classify(signal).reason

    @Test
    fun leanbackLauncherTileOnANewTaskIsALauncherStart() {
        val verdict = LaunchClassifier.classify(signal())
        assertTrue(verdict.isLauncherStart)
        assertEquals(LaunchEntry.Create, verdict.entry)
    }

    @Test
    fun phoneLauncherCategoryAlsoCounts() {
        assertEquals(LaunchReason.LauncherStart, reason(signal(categories = setOf(LaunchClassifier.CATEGORY_LAUNCHER))))
    }

    @Test
    fun tilePressWhileTheTaskExistsArrivesAsNewIntentAndCounts() {
        assertEquals(LaunchReason.LauncherStart, reason(signal(entry = LaunchEntry.NewIntent)))
    }

    @Test
    fun recreationWithSavedStateIsPassive() {
        // Process restart or configuration change: the system re-delivers the launcher intent.
        assertEquals(LaunchReason.RestoredState, reason(signal(saved = true)))
    }

    @Test
    fun relaunchFromRecentsIsPassive() {
        val fromHistory = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY
        assertEquals(LaunchReason.FromHistory, reason(signal(flags = fromHistory)))
        assertEquals(LaunchReason.FromHistory, reason(signal(entry = LaunchEntry.NewIntent, flags = fromHistory)))
    }

    @Test
    fun mainWithoutALauncherCategoryIsPassive() {
        assertEquals(LaunchReason.NoLauncherCategory, reason(signal(categories = emptySet())))
        assertEquals(LaunchReason.NoLauncherCategory, reason(signal(categories = setOf("android.intent.category.DEFAULT"))))
    }

    @Test
    fun explicitComponentWithoutActionIsPassive() {
        assertEquals(LaunchReason.NotMain, reason(signal(action = null, categories = emptySet())))
    }

    @Test
    fun mediaSessionOpenUiIntentIsPassive() {
        assertEquals(LaunchReason.NotMain, reason(signal(action = LaunchClassifier.ACTION_SHOW, categories = emptySet())))
        assertEquals(
            LaunchReason.NotMain,
            reason(signal(entry = LaunchEntry.NewIntent, action = LaunchClassifier.ACTION_SHOW, categories = emptySet())),
        )
    }

    @Test
    fun noIntentIsPassive() {
        assertEquals(LaunchReason.NoIntent, reason(signal(hasIntent = false, action = null, categories = emptySet())))
    }

    @Test
    fun onlyTheLauncherStartReasonStartsMusic() {
        LaunchReason.entries.forEach { reason ->
            assertEquals(reason == LaunchReason.LauncherStart, LaunchVerdict(LaunchEntry.Create, reason).isLauncherStart)
        }
        assertFalse(LaunchVerdict(LaunchEntry.NewIntent, LaunchReason.FromHistory).isLauncherStart)
    }

    @Test
    fun constantsMatchTheFramework() {
        assertEquals(Intent.ACTION_MAIN, LaunchClassifier.ACTION_MAIN)
        assertEquals(Intent.CATEGORY_LAUNCHER, LaunchClassifier.CATEGORY_LAUNCHER)
        assertEquals(Intent.CATEGORY_LEANBACK_LAUNCHER, LaunchClassifier.CATEGORY_LEANBACK_LAUNCHER)
        assertEquals(Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY, LaunchClassifier.FLAG_LAUNCHED_FROM_HISTORY)
    }
}
