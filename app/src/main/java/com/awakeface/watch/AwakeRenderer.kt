package com.awakeface.watch

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.SurfaceHolder
import androidx.core.content.ContextCompat
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import kotlin.math.min

/**
 * Draws time awake instead of clock time: a sun or moon, the elapsed count, and around the rim the
 * last 24 hours split into the stretches spent awake and asleep.
 *
 * The proportions are the same ones the Watch Face Format face uses, expressed as fractions of the
 * radius so both look identical on any size of watch. One accent, spent on the band; everything
 * else white at three fixed opacities, so the hours are read first and the small print last.
 */
class AwakeRenderer(
    private val context: Context,
    surfaceHolder: SurfaceHolder,
    currentUserStyleRepository: CurrentUserStyleRepository,
    watchState: WatchState,
    canvasType: Int,
) : Renderer.CanvasRenderer2<AwakeRenderer.Assets>(
    surfaceHolder,
    currentUserStyleRepository,
    watchState,
    canvasType,
    UPDATE_DELAY_MILLIS,
    clearWithBackgroundTintBeforeRenderingHighlightLayer = false,
) {

    class Assets : SharedAssets {
        override fun onDestroy() = Unit
    }

    private val store = WakeStore(context)
    private val sleepLog = SleepLog(context)

    private val ringPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint().apply {
        isAntiAlias = true
        textAlign = Paint.Align.CENTER
    }

    private val condensed = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
    private val condensedLight = Typeface.create("sans-serif-condensed-light", Typeface.NORMAL)

    private val clock12 = DateTimeFormatter.ofPattern("h:mm a")
    private val clock24 = DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormat = DateTimeFormatter.ofPattern("EEE d MMM")

    private val awakeIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_awake)
    private val asleepIcon: Drawable? = ContextCompat.getDrawable(context, R.drawable.ic_asleep)

    override suspend fun createSharedAssets(): Assets = Assets()

    override fun render(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime, sharedAssets: Assets) {
        val ambient = renderParameters.drawMode == DrawMode.AMBIENT
        canvas.drawColor(Color.BLACK)

        if (!ambient) {
            // Screen is on and the wearer is looking at it: that is a moment of being awake.
            AwakeDetector.noteAwakeInteraction(context, System.currentTimeMillis())
        }

        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()
        val radius = min(bounds.width(), bounds.height()) / 2f
        val nowMillis = zonedDateTime.toInstant().toEpochMilli()

        val palette = store.palette
        val state = AwakeState.current(store, nowMillis)

        drawDayRing(canvas, cx, cy, radius, nowMillis, palette, ambient)
        drawIcon(canvas, cx, cy, radius, state, palette, ambient)
        drawDuration(canvas, cx, cy, radius, state, ambient)
        drawFooter(canvas, cx, cy, radius, zonedDateTime, ambient)
    }

    override fun renderHighlightLayer(
        canvas: Canvas,
        bounds: Rect,
        zonedDateTime: ZonedDateTime,
        sharedAssets: Assets,
    ) {
        canvas.drawColor(renderParameters.highlightLayer?.backgroundTint ?: Color.TRANSPARENT)
    }

    // --- pieces -------------------------------------------------------------------------------

    /**
     * The day around the rim: today, midnight at the top, one arc per stretch spent awake or
     * asleep. Position on the band is a time of day, so the night sits where the night was, and
     * the marker showing "now" travels round as the day is lived.
     */
    private fun drawDayRing(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        nowMillis: Long,
        palette: Palette,
        ambient: Boolean,
    ) {
        val stroke = radius * if (ambient) 0.028f else 0.036f
        val inset = stroke / 2f + radius * 0.026f
        val arc = RectF(cx - radius + inset, cy - radius + inset, cx + radius - inset, cy + radius - inset)

        // Butt caps, so neighbouring stretches meet exactly instead of overlapping.
        ringPaint.strokeCap = Paint.Cap.BUTT
        ringPaint.strokeWidth = stroke

        val segments = DayRing.segments(store, sleepLog, nowMillis, palette)
        if (segments.isEmpty()) {
            ringPaint.color = Palette.UNKNOWN
            canvas.drawArc(arc, 0f, 360f, false, ringPaint)
        } else {
            val total = segments.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1f)
            var angle = START_ANGLE
            for (segment in segments) {
                val sweep = 360f * (segment.weight / total)
                ringPaint.color = if (ambient) ambientColor(segment) else segment.color
                // Overdraw by a hair: exact abutment leaves seams once anti-aliasing rounds down.
                canvas.drawArc(arc, angle, sweep + 0.4f, false, ringPaint)
                angle += sweep
            }
        }

        drawHourMarks(canvas, cx, cy, radius, inset, stroke, ambient)

        // "Now", as a hairline across the band: enough to find, not enough to read as a segment.
        ringPaint.strokeCap = Paint.Cap.BUTT
        ringPaint.color = if (ambient) Color.argb(110, 255, 255, 255) else Color.argb(179, 255, 255, 255)
        ringPaint.strokeWidth = radius * 0.009f

        canvas.save()
        canvas.rotate(360f * DayRing.fractionOfDay(nowMillis), cx, cy)
        canvas.drawLine(cx, cy - radius + inset - stroke, cx, cy - radius + inset + stroke, ringPaint)
        canvas.restore()
    }

    /**
     * Quarter marks just inside the band, at midnight, 06:00, noon and 18:00.
     *
     * The band is a *24 hour* dial, so a conventional analog clock inside it would disagree with
     * everything around it — the hand would sit at one angle while the same hour on the band sat at
     * another. Four marks give the same reference without the contradiction: find noon at the
     * bottom and the night reads itself. Midnight is brightest, since that is where the day starts.
     */
    private fun drawHourMarks(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        inset: Float,
        stroke: Float,
        ambient: Boolean,
    ) {
        if (ambient) return

        val outer = cy - radius + inset + stroke / 2f + radius * 0.030f
        val length = radius * 0.055f

        ringPaint.strokeCap = Paint.Cap.ROUND
        ringPaint.strokeWidth = radius * 0.012f

        for (quarter in 0..3) {
            ringPaint.color = if (quarter == 0) Color.argb(105, 255, 255, 255) else Color.argb(55, 255, 255, 255)
            canvas.save()
            canvas.rotate(quarter * 90f, cx, cy)
            canvas.drawLine(cx, outer, cx, outer + length, ringPaint)
            canvas.restore()
        }

        // Midnight and noon, named. Two numbers are enough to declare the whole scale — once the
        // top is 00 and the bottom is 12, every other angle follows — and they go top and bottom
        // where nothing else is competing for the space. Written the 24 hour way whatever the
        // clock is set to, because the dial itself is a 24 hour one.
        textPaint.typeface = condensedLight
        textPaint.textSize = radius * 0.082f
        textPaint.color = Color.argb(75, 255, 255, 255)

        // Inside the quarter ticks, which reach down to about 0.85 of the radius.
        val labelRadius = radius * 0.808f
        val half = textPaint.textSize * 0.36f
        canvas.drawText("00", cx, cy - labelRadius + half, textPaint)
        canvas.drawText("12", cx, cy + labelRadius + half, textPaint)
    }

    /**
     * In ambient the band survives as shape only: sleep dim, awake bright. Time with no record
     * stays faintly drawn rather than dropped — on the first day most of the ring is unrecorded,
     * and a band that disappears in ambient reads as a face that has died.
     */
    private fun ambientColor(segment: RingSegment): Int = when {
        segment.color == Palette.FUTURE -> Color.argb(10, 255, 255, 255)
        segment.phase == Phase.AWAKE -> Color.argb(150, 255, 255, 255)
        segment.phase == Phase.ASLEEP -> Color.argb(60, 255, 255, 255)
        else -> Color.argb(22, 255, 255, 255)
    }

    /** A sun or a moon where a label would otherwise be: no word to read, nothing to translate. */
    private fun drawIcon(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        state: AwakeState,
        palette: Palette,
        ambient: Boolean,
    ) {
        val icon = (if (state.isAsleep) asleepIcon else awakeIcon) ?: return
        val size = (radius * 0.125f).toInt()
        val top = (cy - radius * 0.52f).toInt()
        val left = (cx - size / 2f).toInt()

        // Dim white, not the accent: the colour belongs to the band, where it means something.
        icon.setTint(Color.argb(if (ambient) 120 else 179, 255, 255, 255))
        icon.setBounds(left, top, left + size, top + size)
        icon.draw(canvas)
    }

    /**
     * "13h 02m", set as two sizes on one baseline: the hours are the figure, the minutes are
     * detail. The split sits just right of the centre line, which shares the drift between the one
     * and two digit cases instead of letting it all fall on the common one.
     */
    private fun drawDuration(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        state: AwakeState,
        ambient: Boolean,
    ) {
        val hours = if (state.isSet) "${state.hours}h" else "--h"
        val minutes = if (state.isSet) "${state.minutes.toString().padStart(2, '0')}m" else "--m"

        val split = cx + radius * 0.093f
        val baseline = cy + radius * 0.19f

        textPaint.typeface = if (ambient) condensedLight else condensed
        textPaint.letterSpacing = -0.02f

        textPaint.textAlign = Paint.Align.RIGHT
        textPaint.textSize = radius * 0.525f
        textPaint.color = Color.WHITE
        canvas.drawText(hours, split, baseline, textPaint)

        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = radius * 0.24f
        textPaint.color = Color.argb(166, 255, 255, 255)
        canvas.drawText(minutes, split + radius * 0.044f, baseline, textPaint)

        textPaint.textAlign = Paint.Align.CENTER
        textPaint.letterSpacing = 0f
    }

    /** Time of day and date, each shown only if asked for, each dimmer than the thing above it. */
    private fun drawFooter(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        zonedDateTime: ZonedDateTime,
        ambient: Boolean,
    ) {
        textPaint.typeface = condensedLight

        if (store.showClock) {
            val clock = zonedDateTime.format(if (store.use24Hour) clock24 else clock12)
            textPaint.textSize = radius * 0.116f
            textPaint.color = Color.argb(if (ambient) 90 else 115, 255, 255, 255)
            canvas.drawText(clock, cx, cy + radius * 0.418f, textPaint)
        }

        if (store.showDate) {
            textPaint.textSize = radius * 0.093f
            textPaint.color = Color.argb(if (ambient) 70 else 77, 255, 255, 255)
            canvas.drawText(zonedDateTime.format(dateFormat), cx, cy + radius * 0.569f, textPaint)
        }
    }

    companion object {
        /** Minute resolution is all this face shows, so redraw once a minute. */
        private const val UPDATE_DELAY_MILLIS = 60_000L
        private const val START_ANGLE = -90f
    }
}
