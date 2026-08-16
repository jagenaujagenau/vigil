package com.awakeface.watch

import android.util.Log
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.UserActivityInfo
import androidx.health.services.client.data.UserActivityState
import java.time.Duration
import java.time.Instant

/**
 * Receives background activity-state updates from Health Services.
 *
 * Two edges matter. Entering [UserActivityState.USER_ACTIVITY_ASLEEP] switches the face over to
 * counting sleep; leaving it is the wake-up that starts the day.
 */
class AwakeListenerService : PassiveListenerService() {

    override fun onUserActivityInfoReceived(info: UserActivityInfo) {
        val store = WakeStore(this)
        val state = info.userActivityState
        val changedAt: Instant = info.stateChangeTime
        store.lastHealthReportEpochMillis = System.currentTimeMillis()

        Log.i(AwakeDetector.TAG, "activity state ${state.name} at $changedAt")

        if (state == UserActivityState.USER_ACTIVITY_ASLEEP) {
            // Noted, but not acted on: nothing is written to the log and the face is not told,
            // because most reported sleep on real hardware turns out to be a blip of a minute or
            // two. It becomes visible on its own once it has lasted long enough to be believed.
            if (store.asleepSinceEpochMillis == null) {
                store.asleepSinceEpochMillis = changedAt.toEpochMilli()
            }
            return
        }

        val asleepSince = store.asleepSinceEpochMillis
        if (asleepSince == null) {
            // Nothing to end — but a passive report carries the moment that state began, which on
            // a watch that has been worn since morning is the wake-up itself. That rescues a fresh
            // install from counting from when it was set up.
            if (state == UserActivityState.USER_ACTIVITY_PASSIVE) {
                AwakeDetector.adoptObservedStart(this, changedAt)
            }
            return
        }

        // Any non-asleep state ends the sleep.
        store.asleepSinceEpochMillis = null

        val sleptFor = Duration.between(Instant.ofEpochMilli(asleepSince), changedAt)
        if (sleptFor < AwakeDetector.CONFIRM_SLEEP) {
            // Never shown, so there is nothing to correct and nothing worth recording.
            Log.i(AwakeDetector.TAG, "discarding ${sleptFor.toMinutes()}m of reported sleep")
            return
        }

        // Now that it is real, the log gets both ends of it — the ring draws every genuine sleep,
        // naps included, even though the nap rules will not let a short one restart the day.
        SleepLog(this).record(Phase.ASLEEP, asleepSince)

        AwakeDetector.onDetectedWake(context = this, wokeAt = changedAt, sleptFor = sleptFor)
    }

    override fun onPermissionLost() {
        Log.w(AwakeDetector.TAG, "activity recognition permission lost — detection has stopped")
    }
}
