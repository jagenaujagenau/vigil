package com.awakeface.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Passive monitoring registrations do not survive a reboot, so put it back. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        AwakeDetector.start(context)
    }
}
