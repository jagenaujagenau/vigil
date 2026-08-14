package com.awakeface.watch

import android.graphics.Color

/**
 * The colour schemes the face can be set to.
 *
 * A scheme is two colours — one for time awake, one for time asleep — because the ring around the
 * rim is where this face carries its colour. They travel to the Watch Face Format face inside the
 * complication data, since a face with no code cannot recolour what it is handed.
 */
enum class Palette(
    val id: String,
    val labelRes: Int,
    val awake: Int,
    val asleep: Int,
) {
    AURORA("aurora", R.string.palette_aurora, Color.parseColor("#63E6BE"), Color.parseColor("#3B5BDB")),
    EMBER("ember", R.string.palette_ember, Color.parseColor("#FFB25E"), Color.parseColor("#7048E8")),
    CORAL("coral", R.string.palette_coral, Color.parseColor("#FF8787"), Color.parseColor("#0CA678")),
    GRAPHITE("graphite", R.string.palette_graphite, Color.parseColor("#DEE2E6"), Color.parseColor("#495057"));

    fun colorFor(phase: Phase?): Int = when (phase) {
        Phase.AWAKE -> awake
        Phase.ASLEEP -> asleep
        null -> UNKNOWN
    }

    companion object {
        val DEFAULT = AURORA

        /**
         * Time the watch has no record of. Near black on purpose: absence should register as a
         * quiet gap in the band, not as a third colour competing with the two that mean something.
         */
        val UNKNOWN: Int = Color.parseColor("#16171A")

        fun fromId(id: String?): Palette = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}

/** Whether the time of day is shown, and in which format. */
enum class ClockMode(val id: String, val labelRes: Int) {
    OFF("off", R.string.clock_off),
    HOUR_12("12", R.string.clock_12),
    HOUR_24("24", R.string.clock_24);

    companion object {
        val DEFAULT = HOUR_12

        fun fromId(id: String?): ClockMode = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
