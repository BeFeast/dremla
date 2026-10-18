package com.befeast.sleepmusic.playback

/** Which activity callback delivered the intent. */
enum class LaunchEntry { Create, NewIntent }

/** The launch facts the classifier needs, without Android types. */
data class LaunchSignal(
    val entry: LaunchEntry,
    val hasIntent: Boolean,
    val action: String?,
    val categories: Set<String>,
    val flags: Int,
    /** `onCreate` received a saved instance state (re-creation after process death or a configuration change). */
    val hasSavedState: Boolean,
)

enum class LaunchReason { LauncherStart, NoIntent, RestoredState, NotMain, NoLauncherCategory, FromHistory }

data class LaunchVerdict(val entry: LaunchEntry, val reason: LaunchReason) {
    val isLauncherStart: Boolean get() = reason == LaunchReason.LauncherStart
}

/**
 * A launcher start is an intent with action MAIN and category LEANBACK_LAUNCHER or LAUNCHER,
 * without FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY, delivered to `onCreate` without saved state (a new
 * task) or to `onNewIntent` of the running activity (the tile pressed while the task exists).
 * Everything else is passive. Resume and wake never reach the classifier.
 */
object LaunchClassifier {
    const val ACTION_MAIN = "android.intent.action.MAIN"
    const val CATEGORY_LAUNCHER = "android.intent.category.LAUNCHER"
    const val CATEGORY_LEANBACK_LAUNCHER = "android.intent.category.LEANBACK_LAUNCHER"
    const val FLAG_LAUNCHED_FROM_HISTORY = 0x00100000

    /** The MediaSession's "open UI" intent; passive by construction (not MAIN). */
    const val ACTION_SHOW = "com.befeast.sleepmusic.action.SHOW"

    fun classify(signal: LaunchSignal): LaunchVerdict {
        val reason = when {
            !signal.hasIntent -> LaunchReason.NoIntent
            signal.entry == LaunchEntry.Create && signal.hasSavedState -> LaunchReason.RestoredState
            signal.action != ACTION_MAIN -> LaunchReason.NotMain
            CATEGORY_LEANBACK_LAUNCHER !in signal.categories &&
                CATEGORY_LAUNCHER !in signal.categories -> LaunchReason.NoLauncherCategory
            signal.flags and FLAG_LAUNCHED_FROM_HISTORY != 0 -> LaunchReason.FromHistory
            else -> LaunchReason.LauncherStart
        }
        return LaunchVerdict(signal.entry, reason)
    }
}
