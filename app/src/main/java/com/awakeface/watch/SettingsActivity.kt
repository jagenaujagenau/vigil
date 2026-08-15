package com.awakeface.watch

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * The whole of the face's settings: how it looks.
 *
 * There is deliberately nothing here about sleeping or waking. Those times are observed, never
 * entered — so the only thing this screen does besides appearance is ask for the permission that
 * makes the observing possible.
 */
class SettingsActivity : Activity() {

    private lateinit var store: WakeStore
    private lateinit var swatches: LinearLayout
    private lateinit var clockButton: Button
    private lateinit var dateButton: Button
    private lateinit var permissionCard: View
    private lateinit var permissionButton: Button
    private lateinit var permissionNote: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        store = WakeStore(this)
        swatches = findViewById(R.id.swatches)
        clockButton = findViewById(R.id.clockButton)
        dateButton = findViewById(R.id.dateButton)
        permissionCard = findViewById(R.id.permissionCard)
        permissionButton = findViewById(R.id.permissionButton)
        permissionNote = findViewById(R.id.permissionNote)

        buildSwatches()

        clockButton.setOnClickListener { cycleClockMode() }
        dateButton.setOnClickListener {
            store.showDate = !store.showDate
            refresh()
        }
        permissionButton.setOnClickListener { requestActivityRecognition() }
        findViewById<Button>(R.id.done).setOnClickListener { finish() }

        // Asked once, on the way in, because without it the face has nothing to show.
        if (!AwakeDetector.hasPermission(this)) {
            requestActivityRecognition()
        }
        askForSleepHistoryOnce()
    }

    override fun onResume() {
        super.onResume()
        AwakeDetector.start(this)
        // Looking at this screen is proof of being awake, which is the fallback signal on watches
        // where Health Services never reports sleep.
        AwakeDetector.noteAwakeInteraction(this, System.currentTimeMillis())
        refresh()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_ACTIVITY_RECOGNITION) return

        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            AwakeDetector.start(this)
        }
        refresh()
    }

    private fun requestActivityRecognition() {
        requestPermissions(arrayOf(Manifest.permission.ACTIVITY_RECOGNITION), REQUEST_ACTIVITY_RECOGNITION)
    }

    /**
     * Asks Health Connect for read access to sleep, once, on first run.
     *
     * This is what lets a fresh install start from the night you actually had rather than from the
     * moment you installed it. It is a nicety, not a requirement — refuse it and the face still
     * works, it just takes until tomorrow morning to be right.
     */
    private fun askForSleepHistoryOnce() {
        if (store.sleepHistoryAsked || !SleepHistory.isAvailable(this)) return
        store.sleepHistoryAsked = true

        runCatching {
            startActivity(
                Intent(HEALTH_PERMISSIONS_ACTION)
                    .putExtra(EXTRA_REQUEST_PERMISSIONS, SleepHistory.PERMISSIONS.toTypedArray())
                    .putExtra(EXTRA_CALLING_PACKAGE, packageName)
            )
        }
    }

    /** One tappable disc per scheme, each showing the two colours that scheme actually draws. */
    private fun buildSwatches() {
        val size = resources.getDimensionPixelSize(R.dimen.swatch_size)
        val gap = resources.getDimensionPixelSize(R.dimen.swatch_gap)

        Palette.entries.forEach { palette ->
            val swatch = View(this).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginStart = gap / 2
                    marginEnd = gap / 2
                }
                contentDescription = getString(palette.labelRes)
                setOnClickListener {
                    store.palette = palette
                    refresh()
                }
            }
            swatches.addView(swatch)
        }
    }

    private fun cycleClockMode() {
        val modes = ClockMode.entries
        store.clockMode = modes[(modes.indexOf(store.clockMode) + 1) % modes.size]
        refresh()
    }

    private fun refresh() {
        val selected = store.palette
        Palette.entries.forEachIndexed { index, palette ->
            val view = swatches.getChildAt(index) ?: return@forEachIndexed
            view.background = swatchDrawable(palette, selected = palette == selected)
        }

        clockButton.text = getString(R.string.clock_setting, getString(store.clockMode.labelRes))
        dateButton.text = getString(
            R.string.date_setting,
            getString(if (store.showDate) R.string.on else R.string.off),
        )

        val granted = AwakeDetector.hasPermission(this)
        permissionCard.visibility = if (granted) View.GONE else View.VISIBLE
        permissionNote.setText(R.string.permission_why)

        // Preferences reach the face through complication data, so push them out now.
        AwakeDetector.requestFaceUpdate(this)
    }

    /** Half awake colour, half asleep colour, ringed when it is the one in use. */
    private fun swatchDrawable(palette: Palette, selected: Boolean): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(palette.awake, palette.awake, palette.asleep, palette.asleep),
        ).apply {
            shape = GradientDrawable.OVAL
            if (selected) {
                setStroke(resources.getDimensionPixelSize(R.dimen.swatch_stroke), Color.WHITE)
            }
        }

    companion object {
        private const val REQUEST_ACTIVITY_RECOGNITION = 1
        private const val HEALTH_PERMISSIONS_ACTION = "androidx.health.ACTION_REQUEST_PERMISSIONS"
        private const val EXTRA_REQUEST_PERMISSIONS = "androidx.health.EXTRA_REQUEST_PERMISSIONS"
        private const val EXTRA_CALLING_PACKAGE = "androidx.health.EXTRA_CALLING_PACKAGE"
    }
}
