package com.awakeface.watch

import android.content.Context
import android.content.SharedPreferences

/**
 * Everything the face persists.
 *
 * Two kinds of thing live here and they are deliberately separate. The wake and sleep timestamps
 * are *observations* — written only by sleep detection, never by the user. The rest are display
 * preferences, the only things anyone gets to choose.
 */
class WakeStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- observations ---------------------------------------------------------------------------

    /** Epoch millis of the last detected wake-up, or null before the first one is seen. */
    var wakeEpochMillis: Long?
        get() = prefs.getLong(KEY_WAKE_EPOCH, NOT_SET).takeIf { it != NOT_SET }
        set(value) = putLong(KEY_WAKE_EPOCH, value)

    /**
     * True while the wake time is only a first-run guess, not something observed. A recorded night
     * or a detected wake-up replaces it and clears this.
     */
    var wakeIsProvisional: Boolean
        get() = prefs.getBoolean(KEY_WAKE_PROVISIONAL, false)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_PROVISIONAL, value).apply()

    /** When the wearer fell asleep, while they still are. Null once they wake. */
    var asleepSinceEpochMillis: Long?
        get() = prefs.getLong(KEY_ASLEEP_SINCE, NOT_SET).takeIf { it != NOT_SET }
        set(value) = putLong(KEY_ASLEEP_SINCE, value)

    /** The last time Health Services told us anything, used to notice that it never does. */
    var lastHealthReportEpochMillis: Long?
        get() = prefs.getLong(KEY_LAST_HEALTH_REPORT, NOT_SET).takeIf { it != NOT_SET }
        set(value) = putLong(KEY_LAST_HEALTH_REPORT, value)

    /** The last time the wearer was demonstrably looking at an awake screen. */
    var lastInteractionEpochMillis: Long?
        get() = prefs.getLong(KEY_LAST_INTERACTION, NOT_SET).takeIf { it != NOT_SET }
        set(value) = putLong(KEY_LAST_INTERACTION, value)

    // --- preferences ----------------------------------------------------------------------------

    var palette: Palette
        get() = Palette.fromId(prefs.getString(KEY_PALETTE, null))
        set(value) = prefs.edit().putString(KEY_PALETTE, value.id).apply()

    var showClock: Boolean
        get() = prefs.getBoolean(KEY_SHOW_CLOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_CLOCK, value).apply()

    var use24Hour: Boolean
        get() = prefs.getBoolean(KEY_USE_24_HOUR, false)
        set(value) = prefs.edit().putBoolean(KEY_USE_24_HOUR, value).apply()

    /** Whether the one-time Health Connect sleep request has been made. */
    var sleepHistoryAsked: Boolean
        get() = prefs.getBoolean(KEY_SLEEP_HISTORY_ASKED, false)
        set(value) = prefs.edit().putBoolean(KEY_SLEEP_HISTORY_ASKED, value).apply()

    var showDate: Boolean
        get() = prefs.getBoolean(KEY_SHOW_DATE, true)
        set(value) = prefs.edit().putBoolean(KEY_SHOW_DATE, value).apply()

    fun registerListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun putLong(key: String, value: Long?) {
        prefs.edit().apply { if (value == null) remove(key) else putLong(key, value) }.apply()
    }

    companion object {
        const val PREFS_NAME = "awake_face_prefs"
        const val KEY_WAKE_EPOCH = "wake_epoch_millis"
        const val KEY_ASLEEP_SINCE = "asleep_since"
        const val KEY_WAKE_PROVISIONAL = "wake_provisional"
        const val KEY_LAST_HEALTH_REPORT = "last_health_report"
        const val KEY_LAST_INTERACTION = "last_interaction"
        const val KEY_PALETTE = "palette"
        const val KEY_SHOW_CLOCK = "show_clock"
        const val KEY_USE_24_HOUR = "use_24_hour"
        const val KEY_SHOW_DATE = "show_date"
        const val KEY_SLEEP_HISTORY_ASKED = "sleep_history_asked"
        private const val NOT_SET = -1L
    }
}

/** Which side of the day the wearer is on. The face counts either way. */
enum class Phase(val code: String) {
    AWAKE("A"),
    ASLEEP("S"),
}

/**
 * Immutable snapshot of "how long has this been going on" at a given instant — time awake while
 * the wearer is up, time asleep once sleep is detected.
 */
data class AwakeState(
    val phase: Phase,
    val isSet: Boolean,
    val sinceEpochMillis: Long?,
    val hours: Long,
    val minutes: Long,
    val totalMinutes: Long,
) {
    val isAsleep: Boolean get() = phase == Phase.ASLEEP

    companion object {
        /** Reads whichever count is currently running. Sleep, once detected, takes precedence. */
        fun current(store: WakeStore, nowMillis: Long): AwakeState {
            val asleepSince = store.asleepSinceEpochMillis
            return if (asleepSince != null) {
                from(Phase.ASLEEP, asleepSince, nowMillis)
            } else {
                from(Phase.AWAKE, store.wakeEpochMillis, nowMillis)
            }
        }

        fun from(phase: Phase, sinceEpochMillis: Long?, nowMillis: Long): AwakeState {
            if (sinceEpochMillis == null) {
                return AwakeState(phase, isSet = false, sinceEpochMillis = null, hours = 0, minutes = 0, totalMinutes = 0)
            }
            val elapsed = (nowMillis - sinceEpochMillis).coerceAtLeast(0L)
            val totalMinutes = elapsed / 60_000L
            return AwakeState(
                phase = phase,
                isSet = true,
                sinceEpochMillis = sinceEpochMillis,
                hours = totalMinutes / 60,
                minutes = totalMinutes % 60,
                totalMinutes = totalMinutes,
            )
        }
    }
}
