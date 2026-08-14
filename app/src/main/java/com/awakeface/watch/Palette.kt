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
    AQUA("aqua", R.string.palette_aqua, Color.parseColor("#4DD0E1"), Color.parseColor("#7E6BD6")),
    EMBER("ember", R.string.palette_ember, Color.parseColor("#FF8A65"), Color.parseColor("#5C6BC0")),
    MEADOW("meadow", R.string.palette_meadow, Color.parseColor("#9CCC65"), Color.parseColor("#26A69A")),
    MONO("mono", R.string.palette_mono, Color.parseColor("#E0E0E0"), Color.parseColor("#5F6368"));

    fun colorFor(phase: Phase?): Int = when (phase) {
        Phase.AWAKE -> awake
        Phase.ASLEEP -> asleep
        null -> UNKNOWN
    }

    companion object {
        val DEFAULT = AQUA

        /** Time the watch has no record of. Deliberately the same in every scheme. */
        val UNKNOWN: Int = Color.parseColor("#2E2E33")

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
