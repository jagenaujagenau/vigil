package com.awakeface.watch

/** One arc of the ring: how much of the day it covers, and what colour it is drawn in. */
data class RingSegment(val weight: Float, val color: Int, val phase: Phase?)

/**
 * Turns the sleep log into the band that runs around the edge of the face.
 *
 * The whole ring is one 24 hour window ending now, so the seam at the top is both "24 hours ago"
 * and "this moment", and every segment's share of the circle is its share of the day.
 */
object DayRing {

    val WINDOW_MILLIS = 24L * 60 * 60 * 1000

    /**
     * A weighted-elements complication may carry at most seven elements, so a day with more
     * transitions than that has to be simplified before it can be sent.
     */
    const val MAX_COMPLICATION_ELEMENTS = 7

    fun segments(log: SleepLog, nowMillis: Long, palette: Palette): List<RingSegment> =
        log.segments(nowMillis - WINDOW_MILLIS, nowMillis)
            .filter { it.durationMillis > 0 }
            .map { RingSegment(it.durationMillis.toFloat(), palette.colorFor(it.phase), it.phase) }

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
