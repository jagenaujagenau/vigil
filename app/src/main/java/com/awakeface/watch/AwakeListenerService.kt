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
            if (store.asleepSinceEpochMillis == null) {
                store.asleepSinceEpochMillis = changedAt.toEpochMilli()
                // The log keeps every real transition, naps included, because the ring draws the
                // day as it happened rather than as the nap rules score it.
                SleepLog(this).record(Phase.ASLEEP, changedAt.toEpochMilli())
                // The face counts sleep from here, so it needs the new numbers now.
                AwakeDetector.requestFaceUpdate(this)
            }
            return
        }

        // Any non-asleep state ends the night, but only if we saw the night begin.
        val asleepSince = store.asleepSinceEpochMillis ?: return
        store.asleepSinceEpochMillis = null

        AwakeDetector.onDetectedWake(
            context = this,
            wokeAt = changedAt,
            sleptFor = Duration.between(Instant.ofEpochMilli(asleepSince), changedAt),
        )
    }

    override fun onPermissionLost() {
        Log.w(AwakeDetector.TAG, "activity recognition permission lost — detection has stopped")
    }
}
