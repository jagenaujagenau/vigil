package com.awakeface.watch

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.PassiveListenerConfig
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import java.time.Duration
import java.time.Instant

/**
 * Wires up sleep detection.
 *
 * Health Services reports the wearer's activity state in the background, including when they are
 * asleep. Nothing here is configurable: the face has no manual entry, so this is the only thing
 * that ever sets the times it shows. The one gate is the activity recognition permission.
 */
object AwakeDetector {

    const val TAG = "AwakeDetector"

    /**
     * Sleep shorter than this is a nap, not a night, and must not reset the day's counter.
     * Three hours is above any ordinary nap and below any ordinary night.
     */
    val MIN_SLEEP: Duration = Duration.ofHours(3)

    /** If Health Services has said nothing for this long, assume it is not going to. */
    private val HEALTH_SERVICES_SILENT: Duration = Duration.ofHours(36)

    /** Don't rewrite the "last seen awake" timestamp more often than this. */
    private const val INTERACTION_WRITE_INTERVAL_MILLIS = 60_000L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts (or restarts) passive monitoring. Safe to call repeatedly — Health Services replaces
     * any previous registration for this app.
     */
    fun start(context: Context) {
        if (!hasPermission(context)) return

        val config = PassiveListenerConfig.builder()
            .setShouldUserActivityInfoBeRequested(true)
            .build()

        HealthServices.getClient(context.applicationContext)
            .passiveMonitoringClient
            .setPassiveListenerServiceAsync(AwakeListenerService::class.java, config)
            .addListener({ Log.i(TAG, "passive sleep detection registered") }, Runnable::run)
    }

    /**
     * Fallback detection for watches where Health Services never reports sleep.
     *
     * Called whenever the wearer is actually looking at the watch — screen on, not ambient. A long
     * gap between two such moments is a night, so the end of the gap is the wake-up.
     */
    fun noteAwakeInteraction(context: Context, nowMillis: Long) {
        val store = WakeStore(context)

        val healthReport = store.lastHealthReportEpochMillis
        val healthServicesWorking = healthReport != null &&
            nowMillis - healthReport < HEALTH_SERVICES_SILENT.toMillis()

        val lastSeen = store.lastInteractionEpochMillis
        if (lastSeen == null) {
            store.lastInteractionEpochMillis = nowMillis
            return
        }

        val gap = Duration.ofMillis(nowMillis - lastSeen)
        if (!healthServicesWorking && gap >= MIN_SLEEP) {
            SleepLog(context).record(Phase.ASLEEP, lastSeen)
            onDetectedWake(context, Instant.ofEpochMilli(nowMillis), gap)
        }

        if (nowMillis - lastSeen >= INTERACTION_WRITE_INTERVAL_MILLIS) {
            store.lastInteractionEpochMillis = nowMillis
        }
    }

    /**
     * Records a detected wake-up.
     *
     * Only the first wake-up after a real night counts: naps and misreads would otherwise keep
     * resetting the counter to zero.
     */
    fun onDetectedWake(context: Context, wokeAt: Instant, sleptFor: Duration?) {
        val store = WakeStore(context)

        SleepLog(context).record(Phase.AWAKE, wokeAt.toEpochMilli())

        // A rejected nap still ends the sleep count, so the face needs refreshing either way.
        if (shouldStartNewDay(store, wokeAt, sleptFor)) {
            store.wakeEpochMillis = wokeAt.toEpochMilli()
            Log.i(TAG, "detected wake-up at $wokeAt")
        }
        requestFaceUpdate(context)
    }

    private fun shouldStartNewDay(store: WakeStore, wokeAt: Instant, sleptFor: Duration?): Boolean {
        if (sleptFor != null && sleptFor < MIN_SLEEP) {
            Log.i(TAG, "ignoring ${sleptFor.toMinutes()}m of sleep — too short to be a night")
            return false
        }

        val previous = store.wakeEpochMillis ?: return true
        val sincePrevious = Duration.between(Instant.ofEpochMilli(previous), wokeAt)
        if (sincePrevious.isNegative) return false
        if (sincePrevious < MIN_SLEEP) {
            Log.i(TAG, "ignoring wake-up ${sincePrevious.toMinutes()}m after the last one")
            return false
        }
        return true
    }

    /** Pushes the current numbers to whichever watch face is showing them. */
    fun requestFaceUpdate(context: Context) {
        ComplicationDataSourceUpdateRequester
            .create(context, ComponentName(context, AwakeComplicationService::class.java))
            .requestUpdateAll()
    }
}
