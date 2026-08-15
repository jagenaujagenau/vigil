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
         * Time the watch has no record of. Dark enough to stay out of the way of the two colours
         * that mean something, but not so dark it disappears: on the first day the whole ring is
         * unknown, and a band that cannot be seen at all reads as a broken face rather than as an
         * honest "nothing recorded yet".
         */
        val UNKNOWN: Int = Color.parseColor("#25272C")

        /**
         * The part of today that has not happened yet. Darker than unknown, because "not yet" and
         * "no record" are different statements and the day should visibly fill as it is lived.
         */
        val FUTURE: Int = Color.parseColor("#131418")

        fun fromId(id: String?): Palette = entries.firstOrNull { it.id == id } ?: DEFAULT
    }
}
