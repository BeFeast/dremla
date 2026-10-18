package com.befeast.sleepmusic.playback

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.befeast.sleepmusic.StartVolume

/**
 * [PlaybackStore] in one app-private SharedPreferences file, `playback`. Decisions of the listener
 * (likes, blocks, timer, start volume) are written with commit, so a process killed right after a
 * block still has it; shuffle progress uses apply, where a lost write allows at most one repeat.
 * Backup and device transfer exclude the whole app (res/xml), so nothing here leaves the device.
 */
class SharedPrefsPlaybackStore(context: Context) : PlaybackStore {
    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun liked(): Set<String> = prefs.getStringSet(KEY_LIKED, null)?.toSet().orEmpty()

    override fun blocked(): Set<String> = prefs.getStringSet(KEY_BLOCKED, null)?.toSet().orEmpty()

    override fun setLiked(id: String, liked: Boolean) {
        val next = if (liked) liked() + id else liked() - id
        prefs.edit(commit = true) {
            putSchema()
            putStringSet(KEY_LIKED, next)
        }
    }

    override fun block(id: String) {
        prefs.edit(commit = true) {
            putSchema()
            putStringSet(KEY_BLOCKED, blocked() + id)
            putStringSet(KEY_LIKED, liked() - id)
        }
    }

    override fun bag(): BagState = BagState(
        played = prefs.getStringSet(KEY_BAG_PLAYED, null)?.toSet().orEmpty(),
        lastId = prefs.getString(KEY_BAG_LAST, null),
    )

    override fun saveBag(state: BagState) {
        prefs.edit {
            putSchema()
            putStringSet(KEY_BAG_PLAYED, state.played.toSet())
            putString(KEY_BAG_LAST, state.lastId)
        }
    }

    override var timerMinutes: Int
        get() = SleepTimer.sanitize(prefs.getInt(KEY_TIMER, SleepTimer.DEFAULT_MINUTES))
        set(value) {
            prefs.edit(commit = true) {
                putSchema()
                putInt(KEY_TIMER, SleepTimer.sanitize(value))
            }
        }

    override var startVolumeLevel: Int
        get() = StartVolumeLevel.sanitize(prefs.getInt(KEY_START_VOLUME, StartVolume.DEFAULT_LEVEL))
        set(value) {
            prefs.edit(commit = true) {
                putSchema()
                putInt(KEY_START_VOLUME, StartVolumeLevel.sanitize(value))
            }
        }

    private fun SharedPreferences.Editor.putSchema() {
        putInt(KEY_SCHEMA, SCHEMA)
    }

    companion object {
        const val FILE = "playback"
        const val SCHEMA = 1
        const val KEY_SCHEMA = "schema"
        const val KEY_LIKED = "liked"
        const val KEY_BLOCKED = "blocked"
        const val KEY_BAG_PLAYED = "bag_played"
        const val KEY_BAG_LAST = "bag_last"
        const val KEY_TIMER = "timer_min"
        const val KEY_START_VOLUME = "start_volume_level"
    }
}
