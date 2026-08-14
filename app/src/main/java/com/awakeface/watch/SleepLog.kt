package com.awakeface.watch

import android.content.Context
import android.content.SharedPreferences

/** One stretch of being awake or asleep, or an unknown stretch from before the log begins. */
data class Segment(
    val startMillis: Long,
    val endMillis: Long,
    val phase: Phase?,
) {
    val durationMillis: Long get() = endMillis - startMillis
}

/**
 * The recent history of falling asleep and waking up, which is what the ring around the face draws.
 *
 * It is a short append-only list of transitions — "at this instant the wearer became awake/asleep"
 * — kept in the same preferences file as everything else and pruned to the last couple of days. The
 * log records what happened; the nap rules in [AwakeDetector] decide what it *means*, so a nap
 * still shows up on the ring even when it does not restart the day.
 */
class SleepLog(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(WakeStore.PREFS_NAME, Context.MODE_PRIVATE)

    private data class Transition(val atMillis: Long, val phase: Phase)

    /** Appends a transition, unless it repeats the phase the wearer is already in. */
    fun record(phase: Phase, atMillis: Long) {
        val existing = read()
        if (existing.lastOrNull()?.phase == phase) return

        val updated = (existing + Transition(atMillis, phase))
            .sortedBy { it.atMillis }
            .let { prune(it, atMillis) }

        prefs.edit().putString(KEY_LOG, encode(updated)).apply()
    }

    /**
     * Splits the window [fromMillis, toMillis] into consecutive segments. The first segment carries
     * a null phase when the log does not reach back far enough to know.
     */
    fun segments(fromMillis: Long, toMillis: Long): List<Segment> {
        if (toMillis <= fromMillis) return emptyList()

        val transitions = read()
        val result = mutableListOf<Segment>()

        // The phase at the start of the window is set by the last transition before it.
        val startingPhase = transitions.lastOrNull { it.atMillis <= fromMillis }?.phase
        var cursor = fromMillis
        var phase = startingPhase

        for (transition in transitions.filter { it.atMillis in (fromMillis + 1) until toMillis }) {
            if (transition.atMillis > cursor) {
                result += Segment(cursor, transition.atMillis, phase)
            }
            cursor = transition.atMillis
            phase = transition.phase
        }

        if (cursor < toMillis) {
            result += Segment(cursor, toMillis, phase)
        }
        return result
    }

    private fun read(): List<Transition> =
        prefs.getString(KEY_LOG, null)
            ?.split(SEPARATOR)
            ?.mapNotNull(::decodeOne)
            ?.sortedBy { it.atMillis }
            ?: emptyList()

    /**
     * Drops transitions that fall out of the retained window, but always keeps the last one before
     * it — that is what says which phase the window opens in.
     */
    private fun prune(transitions: List<Transition>, nowMillis: Long): List<Transition> {
        val cutoff = nowMillis - RETENTION_MILLIS
        val lastBeforeCutoff = transitions.lastOrNull { it.atMillis <= cutoff }
        val within = transitions.filter { it.atMillis > cutoff }
        return (listOfNotNull(lastBeforeCutoff) + within).takeLast(MAX_TRANSITIONS)
    }

    private fun encode(transitions: List<Transition>): String =
        transitions.joinToString(SEPARATOR) { "${it.atMillis}$FIELD${it.phase.code}" }

    private fun decodeOne(raw: String): Transition? {
        val parts = raw.split(FIELD)
        if (parts.size != 2) return null
        val at = parts[0].toLongOrNull() ?: return null
        val phase = Phase.entries.firstOrNull { it.code == parts[1] } ?: return null
        return Transition(at, phase)
    }

    companion object {
        const val KEY_LOG = "sleep_log"
        private const val SEPARATOR = ","
        private const val FIELD = ":"

        /** Two days is enough to draw a 24 hour ring with the phase before it known. */
        private const val RETENTION_MILLIS = 48L * 60 * 60 * 1000

        /** A hard cap so a misbehaving sensor cannot grow the preference without bound. */
        private const val MAX_TRANSITIONS = 64
    }
}
