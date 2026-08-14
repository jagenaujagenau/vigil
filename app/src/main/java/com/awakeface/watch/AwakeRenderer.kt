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
     * The day around the rim: the last 24 hours, one arc per stretch spent awake or asleep. The
     * whole circle is the window, so the seam at the top is both "a day ago" and "now".
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
        val stroke = radius * if (ambient) 0.038f else 0.062f
        val inset = stroke / 2f + radius * 0.030f
        val arc = RectF(cx - radius + inset, cy - radius + inset, cx + radius - inset, cy + radius - inset)

        // Butt caps, so neighbouring stretches meet exactly instead of overlapping.
        ringPaint.strokeCap = Paint.Cap.BUTT
        ringPaint.strokeWidth = stroke

        val segments = DayRing.segments(sleepLog, nowMillis, palette)
        if (segments.isEmpty()) {
            ringPaint.color = Palette.UNKNOWN
            canvas.drawArc(arc, 0f, 360f, false, ringPaint)
        } else {
            val total = segments.sumOf { it.weight.toDouble() }.toFloat().coerceAtLeast(1f)
            var angle = START_ANGLE
            for (segment in segments) {
                val sweep = 360f * (segment.weight / total)
                ringPaint.color = if (ambient) ambientColor(segment.phase) else segment.color
                // Overdraw by a hair: exact abutment leaves seams once anti-aliasing rounds down.
                canvas.drawArc(arc, angle, sweep + 0.4f, false, ringPaint)
                angle += sweep
            }
        }

        // A tick at the seam, so it reads as "now" rather than as a gap.
        ringPaint.strokeCap = Paint.Cap.ROUND
        ringPaint.color = if (ambient) Color.argb(120, 255, 255, 255) else Color.WHITE
        ringPaint.strokeWidth = radius * 0.018f
        canvas.drawLine(cx, cy - radius + inset - stroke * 0.75f, cx, cy - radius + inset + stroke * 0.75f, ringPaint)
    }

    /** In ambient the band survives as shape only: sleep dim, awake bright, unknown invisible. */
    private fun ambientColor(phase: Phase?): Int = when (phase) {
        Phase.AWAKE -> Color.argb(150, 255, 255, 255)
        Phase.ASLEEP -> Color.argb(60, 255, 255, 255)
        null -> Color.TRANSPARENT
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
        val size = (radius * 0.20f).toInt()
        val top = (cy - radius * 0.42f).toInt()
        val left = (cx - size / 2f).toInt()

        icon.setTint(if (ambient) Color.argb(150, 255, 255, 255) else palette.colorFor(state.phase))
        icon.setBounds(left, top, left + size, top + size)
        icon.draw(canvas)
    }

    private fun drawDuration(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        state: AwakeState,
        ambient: Boolean,
    ) {
        val hours = if (state.isSet) state.hours.toString() else "--"
        val minutes = if (state.isSet) state.minutes.toString().padStart(2, '0') else "--"

        val bigSize = radius * 0.58f
        val unitSize = radius * 0.21f
        val gap = radius * 0.05f

        textPaint.typeface = if (ambient) condensedLight else condensed
        textPaint.letterSpacing = -0.02f
        textPaint.textAlign = Paint.Align.LEFT
        textPaint.textSize = bigSize
        val hoursWidth = textPaint.measureText(hours)
        val minutesWidth = textPaint.measureText(minutes)

        textPaint.textSize = unitSize
        val hWidth = textPaint.measureText("h")
        val mWidth = textPaint.measureText("m")

        val totalWidth = hoursWidth + hWidth + gap * 1.6f + minutesWidth + mWidth
        val scale = min(1f, (radius * 1.36f) / totalWidth)
        val baseline = cy + radius * 0.14f
        val unitColor = Color.argb(150, 255, 255, 255)

        var x = cx - (totalWidth * scale) / 2f

        textPaint.textSize = bigSize * scale
        textPaint.color = Color.WHITE
        canvas.drawText(hours, x, baseline, textPaint)
        x += hoursWidth * scale

        textPaint.textSize = unitSize * scale
        textPaint.color = unitColor
        canvas.drawText("h", x, baseline, textPaint)
        x += hWidth * scale + gap * 1.6f * scale

        textPaint.textSize = bigSize * scale
        textPaint.color = Color.WHITE
        canvas.drawText(minutes, x, baseline, textPaint)
        x += minutesWidth * scale

        textPaint.textSize = unitSize * scale
        textPaint.color = unitColor
        canvas.drawText("m", x, baseline, textPaint)

        textPaint.textAlign = Paint.Align.CENTER
    }

    /** Time of day and date, each shown only if asked for. */
    private fun drawFooter(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        radius: Float,
        zonedDateTime: ZonedDateTime,
        ambient: Boolean,
    ) {
        val parts = buildList {
            when (store.clockMode) {
                ClockMode.HOUR_12 -> add(zonedDateTime.format(clock12))
                ClockMode.HOUR_24 -> add(zonedDateTime.format(clock24))
                ClockMode.OFF -> Unit
            }
            if (store.showDate) add(zonedDateTime.format(dateFormat))
        }
        if (parts.isEmpty()) return

        textPaint.typeface = condensedLight
        textPaint.textSize = radius * 0.125f
        textPaint.color = if (ambient) Color.argb(110, 255, 255, 255) else Color.argb(160, 255, 255, 255)
        canvas.drawText(parts.joinToString("   "), cx, cy + radius * 0.40f, textPaint)
    }

    companion object {
        /** Minute resolution is all this face shows, so redraw once a minute. */
        private const val UPDATE_DELAY_MILLIS = 60_000L
        private const val START_ANGLE = -90f
    }
}
