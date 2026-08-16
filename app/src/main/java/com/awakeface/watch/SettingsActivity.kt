package com.awakeface.watch

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.EdgeButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SwitchButton
import androidx.wear.compose.material3.Text

/**
 * The whole of the face's settings: how it looks.
 *
 * There is deliberately nothing here about sleeping or waking. Those times are observed, never
 * entered — so the only thing this screen does besides appearance is ask for the permission that
 * makes the observing possible.
 */
class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val store = WakeStore(this)

        setContent {
            SettingsScreen(
                store = store,
                hasActivityPermission = { AwakeDetector.hasPermission(this) },
                onPermissionGranted = { AwakeDetector.start(this, force = true) },
                onChanged = { AwakeDetector.requestFaceUpdate(this) },
                onDone = { finish() },
            )
        }

        askForSleepHistoryOnce(store)
    }

    override fun onResume() {
        super.onResume()
        AwakeDetector.start(this)
        // Looking at this screen is proof of being awake, which is the fallback signal on watches
        // where Health Services never reports sleep.
        AwakeDetector.noteAwakeInteraction(this, System.currentTimeMillis())
        // Push what the face is showing whenever this screen opens, not only when something is
        // changed here: detection may have moved the numbers on while the face sat on cached data.
        AwakeDetector.requestFaceUpdate(this)
    }

    /**
     * Asks Health Connect for read access to sleep, once, on first run.
     *
     * This is what lets a fresh install draw the night you actually had rather than inferring it.
     * A nicety, not a requirement — refuse it and the face still works.
     */
    private fun askForSleepHistoryOnce(store: WakeStore) {
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

    companion object {
        private const val HEALTH_PERMISSIONS_ACTION = "androidx.health.ACTION_REQUEST_PERMISSIONS"
        private const val EXTRA_REQUEST_PERMISSIONS = "androidx.health.EXTRA_REQUEST_PERMISSIONS"
        private const val EXTRA_CALLING_PACKAGE = "androidx.health.EXTRA_CALLING_PACKAGE"
    }
}

@Composable
private fun SettingsScreen(
    store: WakeStore,
    hasActivityPermission: () -> Boolean,
    onPermissionGranted: () -> Unit,
    onChanged: () -> Unit,
    onDone: () -> Unit,
) {
    MaterialTheme {
        var palette by remember { mutableStateOf(store.palette) }
        var showClock by remember { mutableStateOf(store.showClock) }
        var use24Hour by remember { mutableStateOf(store.use24Hour) }
        var showDate by remember { mutableStateOf(store.showDate) }
        var granted by remember { mutableStateOf(hasActivityPermission()) }

        val requestPermission = androidx.activity.compose.rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { allowed ->
            granted = allowed
            if (allowed) onPermissionGranted()
        }

        // Asked on the way in, because without it the face has nothing to show.
        androidx.compose.runtime.LaunchedEffect(Unit) {
            if (!granted) requestPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION)
        }

        val listState = rememberScalingLazyListState()

        AppScaffold {
            ScreenScaffold(
                scrollState = listState,
                // The bottom-hugging button Wear uses to close a settings screen.
                edgeButton = {
                    EdgeButton(onClick = onDone) { Text(stringResource(R.string.done)) }
                },
            ) { contentPadding ->
                ScalingLazyColumn(
                    state = listState,
                    contentPadding = contentPadding,
                    // Room to breathe between rows; a watch list is read at arm's length.
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    item { ListHeader { Text(stringResource(R.string.app_name)) } }

                    if (!granted) {
                        item {
                            Button(
                                onClick = { requestPermission.launch(Manifest.permission.ACTIVITY_RECOGNITION) },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text(stringResource(R.string.permission_allow)) },
                                secondaryLabel = { Text(stringResource(R.string.permission_why_short)) },
                            )
                        }
                    }

                    item { ListHeader { Text(stringResource(R.string.colours)) } }

                    item {
                        PaletteRow(
                            selected = palette,
                            modifier = Modifier.padding(bottom = 4.dp),
                        ) {
                            palette = it
                            store.palette = it
                            onChanged()
                        }
                    }

                    item {
                        SwitchButton(
                            checked = showClock,
                            onCheckedChange = {
                                showClock = it
                                store.showClock = it
                                onChanged()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.setting_time)) },
                        )
                    }

                    item {
                        SwitchButton(
                            checked = use24Hour,
                            onCheckedChange = {
                                use24Hour = it
                                store.use24Hour = it
                                onChanged()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            // Meaningless with the clock hidden, so it says so rather than lying.
                            enabled = showClock,
                            label = { Text(stringResource(R.string.setting_24_hour)) },
                        )
                    }

                    item {
                        SwitchButton(
                            checked = showDate,
                            onCheckedChange = {
                                showDate = it
                                store.showDate = it
                                onChanged()
                            },
                            modifier = Modifier.fillMaxWidth(),
                            label = { Text(stringResource(R.string.setting_date)) },
                        )
                    }
                }
            }
        }
    }
}

/** One tappable disc per scheme, each showing the two colours that scheme actually draws. */
@Composable
private fun PaletteRow(
    selected: Palette,
    modifier: Modifier = Modifier,
    onPick: (Palette) -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Palette.entries.forEach { palette ->
            val isSelected = palette == selected
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            0.0f to Color(palette.awake),
                            0.5f to Color(palette.awake),
                            0.5f to Color(palette.asleep),
                            1.0f to Color(palette.asleep),
                        )
                    )
                    .border(
                        BorderStroke(if (isSelected) 3.dp else 0.dp, Color.White),
                        CircleShape,
                    )
                    .clickable { onPick(palette) }
                    .padding(2.dp)
            )
        }
    }
}
