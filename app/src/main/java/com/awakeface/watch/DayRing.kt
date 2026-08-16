package com.awakeface.watch

import java.time.Instant
import java.time.ZoneId

/** One arc of the ring: how much of the day it covers, and what colour it is drawn in. */
data class RingSegment(val weight: Float, val color: Int, val phase: Phase?)

/**
 * Turns the sleep log into the band that runs around the edge of the face.
 *
 * The ring is *today*: midnight at the top, the whole circle one calendar day. Position on the band
 * is therefore a time of day you can read directly — the night sits where the night was — and the
 * part of the day still to come is left almost dark, so the ring fills as the day is lived.
 */
object DayRing {

    /**
     * A weighted-elements complication may carry at most seven elements, so a day with more
     * transitions than that has to be simplified before it can be sent.
     */
    const val MAX_COMPLICATION_ELEMENTS = 7

    private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000

    /** Where midnight sits, as a fraction of the day, for callers drawing the ring themselves. */
    fun startOfDay(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        Instant.ofEpochMilli(nowMillis).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

    /** How far through the day it is now, 0 at midnight and 1 at the next — where "now" points. */
    fun fractionOfDay(nowMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Float =
        ((nowMillis - startOfDay(nowMillis, zone)).toFloat() / MILLIS_PER_DAY).coerceIn(0f, 1f)

    fun segments(
        store: WakeStore,
        log: SleepLog,
        nowMillis: Long,
        palette: Palette,
        zone: ZoneId = ZoneId.systemDefault(),
    ): List<RingSegment> {
        val dayStart = startOfDay(nowMillis, zone)

        // A sleep in progress is not in the log yet — it is only written once it ends, so that a
        // blip of a minute or two never reaches the ring at all. Draw it from the live timestamp.
        val sleepStart = store.confirmedAsleepSince(nowMillis)
        val loggedUntil = sleepStart?.coerceAtLeast(dayStart) ?: nowMillis

        val lived = log.segments(dayStart, loggedUntil)
            .filter { it.durationMillis > 0 }
            .map { RingSegment(it.durationMillis.toFloat(), palette.colorFor(it.phase), it.phase) } +
            if (sleepStart != null) {
                val from = maxOf(sleepStart, dayStart)
                listOf(RingSegment((nowMillis - from).toFloat(), palette.asleep, Phase.ASLEEP))
            } else {
                emptyList()
            }

        // The rest of the day, so every segment keeps its true share of the circle and the band
        // reads as a clock face rather than a bar that has been stretched to fit.
        val remaining = (dayStart + MILLIS_PER_DAY - nowMillis).coerceAtLeast(0L)
        return if (remaining > 0) {
            lived + RingSegment(remaining.toFloat(), Palette.FUTURE, null)
        } else {
            lived
        }
    }

    /**
     * Reduces the ring to at most [limit] segments by repeatedly folding the shortest one into the
     * neighbour it least distorts — the shortest stretches are the ones a glance cannot resolve
     * anyway, so they are the ones worth losing.
     */
    fun simplify(segments: List<RingSegment>, limit: Int = MAX_COMPLICATION_ELEMENTS): List<RingSegment> {
        if (segments.size <= limit) return segments

        val working = segments.toMutableList()
        while (working.size > limit) {
            val index = working.indices.minBy { working[it].weight }
            val absorbed = working.removeAt(index)
            // Prefer the shorter neighbour, so a long stretch is not quietly stretched further.
            val target = when {
                index == 0 -> 0
                index == working.size -> working.size - 1
                working[index - 1].weight <= working[index].weight -> index - 1
                else -> index
            }
            working[target] = working[target].copy(weight = working[target].weight + absorbed.weight)
        }
        return working
    }
}
