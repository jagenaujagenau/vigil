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

    /** An adopted stretch older than this is stale history, not the morning just gone. */
    private val MAX_ADOPTED_AGE: Duration = Duration.ofHours(20)

    /** Don't rewrite the "last seen awake" timestamp more often than this. */
    private const val INTERACTION_WRITE_INTERVAL_MILLIS = 60_000L

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Starts (or restarts) passive monitoring. Safe to call repeatedly — Health Services replaces
     * any previous registration for this app.
     */
    /**
     * Gives the face something to count on a watch that has not yet seen a wake-up.
     *
     * Detection only ever fires on a *transition*, so a fresh install would otherwise show nothing
     * at all until the wearer's next morning — a day of a dead face. Someone setting up a watch
     * face is almost certainly awake, so the count starts from now and the first real wake-up
     * replaces it. Everything before this moment stays unknown on the ring, which is the honest
     * way to say the watch was not there for it.
     */
    fun ensureCounting(context: Context) {
        val store = WakeStore(context)
        if (store.wakeEpochMillis != null || store.asleepSinceEpochMillis != null) return

        val now = System.currentTimeMillis()
        store.wakeEpochMillis = now
        store.wakeIsProvisional = true
        SleepLog(context).record(Phase.AWAKE, now)
        Log.i(TAG, "no wake-up on record; counting from first run until a real one turns up")
    }

    fun start(context: Context) {
        // Before the permission check: the face should count even where detection cannot run, and
        // the screen-gap fallback can still correct it later.
        ensureCounting(context)

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
            store.wakeIsProvisional = false
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

    /**
     * Adopts the start of the wearer's current non-asleep stretch as the wake-up.
     *
     * Health Services stamps every report with the instant that state began, so the first report
     * after installing already knows when the wearer stopped being asleep — hours before the face
     * existed. Only used to replace a first-run guess, and only if it is recent enough to be
     * today's waking, so it can never overwrite a wake-up that was properly observed.
     */
    fun adoptObservedStart(context: Context, startedAt: Instant) {
        val store = WakeStore(context)
        if (!store.wakeIsProvisional) return

        val now = System.currentTimeMillis()
        val startMillis = startedAt.toEpochMilli()
        if (startMillis > now || now - startMillis > MAX_ADOPTED_AGE.toMillis()) return

        store.wakeEpochMillis = startMillis
        store.wakeIsProvisional = false
        SleepLog(context).record(Phase.AWAKE, startMillis)

        Log.i(TAG, "adopted observed wake-up at $startedAt")
        requestFaceUpdate(context)
    }

    /**
     * Replaces a first-run guess with the night the watch actually recorded.
     *
     * Nothing to do once the wake time is a real observation, so this costs one preference read on
     * every call after the first success.
     */
    suspend fun refineFromHistory(context: Context) {
        val store = WakeStore(context)
        if (!store.wakeIsProvisional || store.asleepSinceEpochMillis != null) return

        val now = System.currentTimeMillis()
        val sleep = SleepHistory.lastSleep(context, now) ?: return
        val wokeAt = sleep.end.toEpochMilli()
        if (wokeAt > now) return

        store.wakeEpochMillis = wokeAt
        store.wakeIsProvisional = false

        // Log both ends, so the ring shows the night rather than starting at the install.
        val log = SleepLog(context)
        log.record(Phase.ASLEEP, sleep.start.toEpochMilli())
        log.record(Phase.AWAKE, wokeAt)

        Log.i(TAG, "recorded night ${sleep.start}..${sleep.end}; counting from its end")
        requestFaceUpdate(context)
    }

    /** Pushes the current numbers to whichever watch face is showing them. */
    fun requestFaceUpdate(context: Context) {
        ComplicationDataSourceUpdateRequester
            .create(context, ComponentName(context, AwakeComplicationService::class.java))
            .requestUpdateAll()
    }
}
