package com.awakeface.watch

import android.content.Context
import android.util.Log
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Duration
import java.time.Instant

/**
 * Reads the sleep the watch has already recorded.
 *
 * Detection only ever fires on a transition, so on a fresh install the face has nothing to count
 * until the wearer's next morning. The watch, though, has usually been tracking sleep all along —
 * on a Pixel Watch that is Fitbit, writing sessions into Health Connect. Reading the most recent
 * session gives a real wake-up to start from, which is a great deal better than guessing.
 *
 * Everything here is best effort. Health Connect may be absent, the permission may be refused, or
 * nothing may have written a session; in each case the caller falls back to counting from now.
 */
object SleepHistory {

    val PERMISSIONS: Set<String> = setOf(HealthPermission.getReadPermission(SleepSessionRecord::class))

    /** How far back a session is still worth treating as "the night you just had". */
    private val LOOKBACK: Duration = Duration.ofHours(36)

    fun isAvailable(context: Context): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    suspend fun hasPermission(context: Context): Boolean {
        if (!isAvailable(context)) return false
        return runCatching {
            HealthConnectClient.getOrCreate(context).permissionController
                .getGrantedPermissions()
                .containsAll(PERMISSIONS)
        }.getOrElse { false }
    }

    /** A night the watch already recorded: when it began, and when the wearer woke from it. */
    data class RecordedSleep(val start: Instant, val end: Instant)

    /**
     * The most recent sleep session, or null if there is nothing recent on record.
     */
    suspend fun lastSleep(context: Context, nowMillis: Long): RecordedSleep? {
        if (!hasPermission(context)) return null

        val now = Instant.ofEpochMilli(nowMillis)
        return runCatching {
            val client = HealthConnectClient.getOrCreate(context)
            val sessions = client.readRecords(
                ReadRecordsRequest(
                    recordType = SleepSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(now.minus(LOOKBACK), now),
                )
            ).records

            sessions.maxByOrNull { it.endTime }
                ?.let { RecordedSleep(it.startTime, it.endTime) }
                ?.also {
                    Log.i(AwakeDetector.TAG, "health connect: ${sessions.size} session(s), last ${it.start}..${it.end}")
                }
        }.getOrElse {
            Log.w(AwakeDetector.TAG, "health connect read failed: ${it.message}")
            null
        }
    }

    /** The permission screen to send the wearer to, when they have not granted sleep access yet. */
    fun permissionContract() = PermissionController.createRequestPermissionResultContract()
}
