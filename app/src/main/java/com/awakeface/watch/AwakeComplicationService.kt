package com.awakeface.watch

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicDuration
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInstant
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicInt32
import androidx.wear.protolayout.expression.DynamicBuilders.DynamicString
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.DynamicComplicationText
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.WeightedElementsComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import java.time.Instant

/**
 * The single channel between this app and the Watch Face Format face, which has no code of its own.
 * Each complication type carries a different part of the face:
 *
 *   SHORT_TEXT        the readout — "14h 07m" as a dynamic expression, plus a sun or moon
 *   WEIGHTED_ELEMENTS the band around the rim — the last 24 hours, segment per stretch
 *   LONG_TEXT         the display preferences, as a short code the face branches on
 */
class AwakeComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? = when (type) {
        ComplicationType.SHORT_TEXT -> readout(
            text = PlainComplicationText.Builder("14h 07m").build(),
            description = PlainComplicationText.Builder("Awake 14 hours 7 minutes").build(),
            phase = Phase.AWAKE,
            withTapAction = false,
        )

        ComplicationType.WEIGHTED_ELEMENTS -> dayRing(System.currentTimeMillis())
        ComplicationType.LONG_TEXT -> displayPreferences()
        else -> null
    }

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        // This service is the only code that runs on the Watch Face Format path, so it is also
        // where background sleep detection gets (re)registered.
        AwakeDetector.start(this)

        return when (request.complicationType) {
            ComplicationType.WEIGHTED_ELEMENTS -> dayRing(System.currentTimeMillis())
            ComplicationType.LONG_TEXT -> displayPreferences()
            ComplicationType.SHORT_TEXT -> currentReadout()
            else -> null
        }
    }

    /** "14h 07m", counting whichever stretch the wearer is in. */
    private fun currentReadout(): ComplicationData {
        val state = AwakeState.current(WakeStore(this), System.currentTimeMillis())

        if (!state.isSet) {
            return readout(
                text = PlainComplicationText.Builder(WAITING).build(),
                description = PlainComplicationText.Builder(getString(R.string.awake_unset)).build(),
                phase = Phase.AWAKE,
                withTapAction = true,
            )
        }

        val elapsed = elapsedSince(state.sinceEpochMillis!!)
        val fallback = "${state.hours}h ${state.minutes.toString().padStart(2, '0')}m"
        val description = getString(
            if (state.isAsleep) R.string.asleep_for else R.string.awake_for,
            state.hours,
            state.minutes,
        )

        return readout(
            text = DynamicComplicationText(durationText(elapsed), fallback),
            description = PlainComplicationText.Builder(description).build(),
            phase = state.phase,
            withTapAction = true,
        )
    }

    private fun readout(
        text: androidx.wear.watchface.complications.data.ComplicationText,
        description: androidx.wear.watchface.complications.data.ComplicationText,
        phase: Phase,
        withTapAction: Boolean,
    ): ComplicationData = ShortTextComplicationData.Builder(text, description)
        // A sun or a moon rather than the words "awake"/"asleep": it reads at a glance and needs
        // no translating.
        .setMonochromaticImage(
            MonochromaticImage.Builder(
                Icon.createWithResource(
                    this,
                    if (phase == Phase.ASLEEP) R.drawable.ic_asleep else R.drawable.ic_awake,
                )
            ).build()
        )
        .apply { if (withTapAction) setTapAction(settingsIntent()) }
        .build()

    /**
     * The band around the edge of the face: the last 24 hours, split into the stretches spent awake
     * and asleep. Weights are milliseconds, so each segment takes exactly its share of the circle.
     */
    private fun dayRing(nowMillis: Long): ComplicationData {
        val palette = WakeStore(this).palette
        val segments = DayRing.simplify(DayRing.segments(SleepLog(this), nowMillis, palette))
            .ifEmpty { listOf(RingSegment(1f, Palette.UNKNOWN, null)) }

        val asleepHours = segments
            .filter { it.phase == Phase.ASLEEP }
            .sumOf { it.weight.toDouble() } / 3_600_000.0

        return WeightedElementsComplicationData.Builder(
            elements = segments.map {
                WeightedElementsComplicationData.Element(weight = it.weight, color = it.color)
            },
            contentDescription = PlainComplicationText.Builder(
                getString(R.string.ring_description, asleepHours.toInt())
            ).build(),
        )
            .setElementBackgroundColor(Palette.UNKNOWN)
            // The type requires text or a title even when a face only draws the segments; this
            // face ignores both, but another face showing the same data gets something readable.
            .setText(PlainComplicationText.Builder("${asleepHours.toInt()}h").build())
            .setTitle(PlainComplicationText.Builder(getString(R.string.slept)).build())
            .setTapAction(settingsIntent())
            .build()
    }

    /**
     * The display preferences, encoded as a two character code the face can branch on: clock mode
     * then date. A Watch Face Format face cannot read preferences, and its own configuration cannot
     * reach back here to colour the ring, so both settings travel the same way — through here.
     */
    private fun displayPreferences(): ComplicationData {
        val store = WakeStore(this)
        val code = store.clockMode.id.take(1).let { if (store.clockMode == ClockMode.OFF) "0" else it } +
            if (store.showDate) "D" else "-"

        return LongTextComplicationData.Builder(
            text = PlainComplicationText.Builder(code).build(),
            contentDescription = PlainComplicationText.Builder(getString(R.string.prefs_description)).build(),
        )
            .setTapAction(settingsIntent())
            .build()
    }

    private fun elapsedSince(startMillis: Long): DynamicDuration =
        DynamicInstant.withSecondsPrecision(Instant.ofEpochMilli(startMillis))
            .durationUntil(DynamicInstant.platformTimeWithSecondsPrecision())

    /** "14h 07m" — total hours, so it keeps counting past a 24 hour day instead of wrapping. */
    private fun durationText(elapsed: DynamicDuration): DynamicString {
        val twoDigits = DynamicInt32.IntFormatter.Builder()
            .setMinIntegerDigits(2)
            .setGroupingUsed(false)
            .build()
        return elapsed.toIntHours().format()
            .concat(DynamicString.constant("h "))
            .concat(elapsed.getMinutesPart().format(twoDigits))
            .concat(DynamicString.constant("m"))
    }

    private fun settingsIntent(): PendingIntent = PendingIntent.getActivity(
        this,
        0,
        Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        /** Shown until sleep detection has seen its first wake-up. */
        private const val WAITING = "--h --m"
    }
}
