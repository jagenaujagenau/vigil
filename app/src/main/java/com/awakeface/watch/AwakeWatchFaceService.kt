package com.awakeface.watch

import android.content.Intent
import android.content.SharedPreferences
import android.view.SurfaceHolder
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.ComplicationSlot
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.TapEvent
import androidx.wear.watchface.TapType
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import androidx.wear.watchface.style.UserStyleSchema

/**
 * Watch face that reports how long you have been awake instead of the time of day.
 *
 * It carries no watch face styles of its own: everything adjustable lives in [SettingsActivity],
 * which a tap on the face opens, so that both this face and the Watch Face Format one are
 * configured in the same single place.
 */
class AwakeWatchFaceService : WatchFaceService() {

    private var renderer: AwakeRenderer? = null

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        renderer?.invalidate()
    }

    override fun createUserStyleSchema(): UserStyleSchema = UserStyleSchema(emptyList())

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository,
    ): WatchFace {
        WakeStore(applicationContext).registerListener(prefsListener)

        // Detection has to be live even if the user never opens the settings screen.
        AwakeDetector.start(applicationContext)

        val awakeRenderer = AwakeRenderer(
            context = applicationContext,
            surfaceHolder = surfaceHolder,
            currentUserStyleRepository = currentUserStyleRepository,
            watchState = watchState,
            canvasType = CanvasType.HARDWARE,
        )
        renderer = awakeRenderer

        return WatchFace(WatchFaceType.DIGITAL, awakeRenderer)
            .setTapListener(object : WatchFace.TapListener {
                override fun onTapEvent(tapType: Int, tapEvent: TapEvent, complicationSlot: ComplicationSlot?) {
                    if (tapType != TapType.UP) return
                    startActivity(
                        Intent(applicationContext, SettingsActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            })
    }

    override fun onDestroy() {
        WakeStore(applicationContext).unregisterListener(prefsListener)
        renderer = null
        super.onDestroy()
    }
}
