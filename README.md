# Vigil — a Wear OS watch face that shows how long you've been awake

Instead of the time of day, the face shows elapsed time since you woke up:

```
                   ☀
               14h 07m
                9:23 AM
               Fri 14 Aug
```

The band around the rim is the last 24 hours, segmented: cyan where you were
awake, indigo where you were asleep, dark grey for time the watch has no record
of. The whole circle is exactly one day, so each stretch takes its true share of
it and a night reads as an arc you can size up at a glance. The tick at the top is
the seam — both "a day ago" and "right now" — and the segment running up to it is
the stretch you are in. The time of day is still there, small and dim, at the
bottom.

A sun or a moon says which stretch is being counted — no word to read, nothing to
translate.

**You never tell it anything.** Health Services reports the wearer's activity
state in the background, including sleep. The moment sleep is detected the face
switches to a moon and counts the night instead, in the scheme's other colour, and
the moment sleep ends the awake counter restarts from zero. There is no way to
enter a wake time by hand and nothing to correct: the times shown are observed,
never typed.

Tapping the face opens the settings, which are only about how it looks:

- **Colours** — one of four schemes, each a pair (awake, asleep)
- **Time** — off, 12-hour, or 24-hour
- **Date** — on or off

The one other thing the app does is ask for the activity permission that makes the
observing possible. If it is declined, the face still runs; it just has nothing to
count until the permission is granted.

## Detecting sleep and the wake-up

`AwakeListenerService` receives `UserActivityInfo` from Health Services and
watches both edges of `USER_ACTIVITY_ASLEEP`: entering it starts the sleep count,
leaving it is the wake-up. `stateChangeTime` — not the time the callback arrives —
is what gets recorded, so a delayed delivery does not skew either count.

Which count is running is decided in one place, `AwakeState.current`: a live
`asleepSince` wins, otherwise it counts from the wake time. Everything else — both
watch faces, the settings screen, the complication — reads that.

Two guards keep an afternoon nap from wiping out the day:

- sleep shorter than **3 hours** is a nap, not a night, and is ignored
- a second wake-up within 3 hours of the recorded one is ignored

If Health Services never reports sleep — some watches, most emulators — there is
a fallback: a gap of 3+ hours between two moments when the wearer was demonstrably
looking at an awake screen is treated as a night. It only engages after Health
Services has been silent for 36 hours, so it never fights the real signal.

Detection needs `ACTIVITY_RECOGNITION`, which the settings screen requests on
first run. Denying it falls back to setting the time by hand; nothing else breaks.

## Two implementations, on purpose

Google changed the rules mid-flight, so the repo ships the watch face twice:

| Module | Format | Runs on |
| --- | --- | --- |
| `:app` | AndroidX / Jetpack watch face (`WatchFaceService`, custom `Canvas` renderer) | Wear OS 3–5 |
| `:watchface` | Watch Face Format (declarative XML, no code) | Wear OS 4+, and the **only** format Wear OS 6 accepts |

Wear OS 6 blocks AndroidX watch faces outright. Confirmed on an API 36 Wear
emulator, where the system logs:

```
WearServices: [WFInfoResolver] Blocked watch face WatchFaceId[com.awakeface.watch, ...AwakeWatchFaceService]
```

A Watch Face Format package may not contain executable code, so it cannot compute
"now minus when you woke up" by itself. Everything therefore comes from `:app`
through complications — one type per job:

| Type | Carries |
| --- | --- |
| `SHORT_TEXT` | the readout, and the sun/moon as its monochromatic image |
| `WEIGHTED_ELEMENTS` | the day ring, already coloured in the chosen scheme |
| `LONG_TEXT` | the display preferences, as a two character code |

That last one deserves a note. A Watch Face Format face *can* carry its own
settings, through `UserConfigurations` in the system editor — but a configuration
chosen there cannot reach back into this app, and the ring's colours have to be
decided here, because they arrive baked into the complication. Splitting settings
across two places to work around that would be worse than the code that avoids it,
so every preference travels the same way: the app owns them, and the face branches
on a code like `2D` (24-hour clock, date on):

```xml
<Expression name="clock24"><![CDATA[subText([COMPLICATION.TEXT], 0, 1) == "2"]]></Expression>
```

The clock and date are drawn *inside* that complication's block, since a
`Condition` can only branch on data in scope.

## How the number stays live

A complication data source is normally only allowed to push updates every few
minutes, which would leave the readout visibly stale. Instead
`AwakeComplicationService` sends a *dynamic* value — an expression the watch
evaluates continuously on device:

```kotlin
DynamicInstant.withSecondsPrecision(wokeUpAt)
    .durationUntil(DynamicInstant.platformTimeWithSecondsPrecision())
```

The hours and minutes are formatted from that expression. The 10 minute poll
declared in the manifest is only a fallback for devices that can't evaluate
dynamic values.

## The day ring

`SleepLog` is an append-only list of transitions — "at this instant the wearer
became awake/asleep" — kept in the same preferences file and pruned to 48 hours.
It records what happened; the nap rules decide what it *means*. That split is
deliberate: an afternoon nap that does not restart the day still shows up on the
ring, because it did happen.

`DayRing` turns that log into weighted segments over the trailing 24 hours. The
Watch Face Format side draws them through a second complication, of type
`WEIGHTED_ELEMENTS` — a type that exists for exactly this shape:

```xml
<WeightedStroke thickness="14"
    colors="[COMPLICATION.WEIGHTED_ELEMENTS_COLORS]"
    weights="[COMPLICATION.WEIGHTED_ELEMENTS_WEIGHTS]"/>
```

That type caps out at **seven** elements, so a day with more transitions is
simplified first: `DayRing.simplify` repeatedly folds the shortest segment into a
neighbour, which loses the stretches too brief to resolve on a 45mm screen and
keeps the ones that carry the shape of the day.

The ring redraws when a transition happens and on the data source's own refresh,
so between refreshes it can trail the truth by a few minutes — a couple of degrees
of arc. The number in the middle does not have that problem; it is a dynamic
expression and ticks on its own.

## Layout

```
:app/
  AwakeWatchFaceService.kt     AndroidX watch face + tap-to-configure
  AwakeRenderer.kt             Canvas renderer (day ring, big duration, ambient variants)
  AwakeComplicationService.kt  Publishes readout, ring and preferences as complications
  AwakeListenerService.kt      Health Services sleep/wake transitions
  AwakeDetector.kt             Registration, nap guards, fallback heuristic
  SleepLog.kt                  Append-only history of transitions
  DayRing.kt                   That history as 24 hours of weighted segments
  WakeStore.kt                 Observed times, kept apart from chosen preferences
  SettingsActivity.kt          Colours, time, date — and the permission ask
  Palette.kt                   The four colour schemes and the clock modes
:watchface/
  res/raw/watchface.xml        The Watch Face Format face
  res/xml/watch_face_info.xml  Required; points at the preview image
  res/drawable/preview.png     Required; shown in the watch face picker
```

Both implementations read the same `SharedPreferences` file, so switching between
them keeps your wake time and your history.

## Build

```bash
./gradlew :app:assembleDebug :watchface:assembleDebug
```

Validate the Watch Face Format XML (catches errors the build does not):

```bash
curl -sL -o wff-validator.jar \
  https://github.com/google/watchface/releases/download/latest/wff-validator.jar
java -jar wff-validator.jar 2 watchface/src/main/res/raw/watchface.xml
```

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk         # required: the data source
adb install -r watchface/build/outputs/apk/debug/watchface-debug.apk
```

Then pick **Vigil** from the watch face picker: long-press the current face →
**Add new** → **Vigil**, then tap it once more to make it current.

Tapping the face opens the settings; the **Vigil** app in the launcher opens the
same screen. Grant the activity permission when asked and the face starts counting
at your next wake-up.

## What has actually been tested

Verified on a Wear OS 5 (API 34) emulator, with the Watch Face Format face active:

- the face renders and the readout advances on its own (0h 01m → 0h 03m with no
  complication refresh in between), confirming the dynamic expression evaluates
  on device
- the face binds to the complication provider through `DefaultProviderPolicy`
  (`DWF:WearComplicationProvider: [11:RANGED_VALUE] com.awakeface.watch/...`)
- tapping the face opens the settings screen
- sleep detection, driven end to end with Health Services synthetic data
  (`whs.synthetic.user.START_SLEEPING` / `STOP_SLEEPING`): falling asleep switched
  the icon to the moon and started counting the night; waking switched it back to
  the sun and left the day's count alone, the short synthetic night being
  correctly rejected as a nap
- both nap guards reject the wake-ups they are supposed to reject
- the day ring, seeded with a night, a day and a nap: rendered as grey / indigo /
  cyan / indigo / cyan segments in the right proportions, ending at the "now"
  tick, and a live sleep transition appended to the log and redrew the band
- all three settings reaching the face: switching to Ember recoloured the ring,
  24-hour dropped the AM/PM, and turning the date off removed its line
- the permission flow both ways — granted, and declined into the in-app card that
  explains why and offers to ask again
- `watchface.xml` passes Google's `wff-validator` against format version 2

Not verified: the AndroidX face in `:app`. It builds, but Wear OS 5 refuses it
outright — `WFInfoResolver: Unsupported legacy watch face WatchFaceId[...
AwakeWatchFaceService]` — and Wear OS 6 blocks it too, so no available image will
run it. Its renderer, including its own segmented-ring drawing, has never executed
on a device. On the evidence, AndroidX watch faces are finished from Wear OS 5
onward and that module is only useful for a Wear OS 3–4 watch.

### Note on emulators

The Wear OS 6 (API 36) emulator image does not surface *any* sideloaded watch
face in its picker, and its `set-watchface` debug broadcast fails for every
sideloaded package — including Google's own unmodified `SimpleDigital` sample:

```
set-watchface failed. FavoriteOperationException: Watch face package is not installed.
```

On a Wear OS 5 (API 34) image the Watch Face Format face *is* offered — long-press
the current face → **Add new** → **Vigil** — but the AndroidX face is still absent
from that list. Test the AndroidX path on a real Wear OS 3–5 watch.

To exercise sleep detection on an emulator:

```bash
adb shell am broadcast -a "whs.USE_SYNTHETIC_PROVIDERS"        com.google.android.wearable.healthservices
adb shell am broadcast -a "whs.synthetic.user.START_SLEEPING"  com.google.android.wearable.healthservices
adb shell am broadcast -a "whs.synthetic.user.STOP_SLEEPING"   com.google.android.wearable.healthservices
```

A synthetic night is only seconds long, so the 3 hour guard will reject it and say
so in the log; backdate `asleep_since` in the app's shared preferences to see the
wake time actually get written.
