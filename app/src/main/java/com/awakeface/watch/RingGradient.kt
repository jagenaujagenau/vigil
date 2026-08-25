package com.awakeface.watch

import androidx.core.graphics.ColorUtils

/**
 * The ring's colours as a set of sweep-gradient stops, so that awake and asleep melt into each
 * other rather than meeting at a line.
 *
 * A hard edge claims a precision the underlying data does not have: sleep is detected over minutes,
 * not at an instant, so the moment drawn as the boundary is already an estimate. A short crossfade
 * says the same thing more honestly, and it is what the eye expects of a band that stands for a day
 * passing.
 *
 * Stops are fractions of the whole circle, starting at midnight and running clockwise.
 */
class RingGradient(val colors: IntArray, val positions: FloatArray)

object RingGradientBuilder {

    /**
     * How far either side of a boundary the crossfade reaches, as a fraction of the circle.
     * About three degrees, so a transition spans six — soft enough to read as a fade, short enough
     * that a nap of an hour (fifteen degrees) still has a body of its own colour in the middle.
     */
    private const val BLEND = 0.009f

    /**
     * No boundary may eat more than this share of either stretch it divides. Without it a stretch
     * of a few minutes would be nothing but other stretches' fades, and would lose its colour
     * entirely.
     */
    private const val MAX_SHARE = 0.4f

    fun of(segments: List<RingSegment>, colorOf: (RingSegment) -> Int): RingGradient {
        val colors = segments.map(colorOf)
        // A sweep gradient needs two stops even when there is only ever one colour to give it.
        if (colors.size == 1) {
            return RingGradient(intArrayOf(colors[0], colors[0]), floatArrayOf(0f, 1f))
        }

        val total = segments.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1f)
        val spans = segments.map { it.weight / total }
        val n = spans.size

        // Half-width of the fade at each boundary; boundary i divides segment i-1 from segment i,
        // so boundary 0 is midnight, where the end of the day meets the start of it.
        val half = FloatArray(n) { i ->
            minOf(BLEND, MAX_SHARE * spans[(i - 1 + n) % n], MAX_SHARE * spans[i])
        }

        val stopColors = ArrayList<Int>(2 * n + 2)
        val stopPositions = ArrayList<Float>(2 * n + 2)
        var last = 0f
        fun add(position: Float, color: Int) {
            last = position.coerceIn(last, 1f)
            stopColors += color
            stopPositions += last
        }

        // Midnight is the one boundary the gradient cannot fade across, since it is where the sweep
        // begins and ends. Both ends are pinned to the halfway colour, which is what the middle of
        // a fade would have been anyway, and the two halves meet there invisibly.
        val midnight = ColorUtils.blendARGB(colors[n - 1], colors[0], 0.5f)
        add(0f, midnight)

        var start = 0f
        for (i in 0 until n) {
            val end = start + spans[i]
            // Full colour from the end of the incoming fade to the start of the outgoing one; the
            // gradient runs straight between those two stops and the boundary lands on its midpoint.
            add(start + half[i], colors[i])
            add(end - half[(i + 1) % n], colors[i])
            start = end
        }

        add(1f, midnight)

        return RingGradient(stopColors.toIntArray(), stopPositions.toFloatArray())
    }
}
