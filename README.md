# Vigil — a Wear OS watch face that shows how long you've been awake

Instead of the time of day, the face shows elapsed time since you woke up:

```
                   ☀
               14h 07m
                9:23 AM
               Fri 14 Aug
```

The band around the rim is **today**: midnight at the top, the whole circle one
calendar day, coloured where you were awake and where you were asleep, and left
almost dark for the hours still to come. Position on the band is a time of day you
can read directly, so a night sits where the night was.

Two numbers give it a scale — `00` at the top, `12` at the bottom — with quarter
ticks between and a hairline for now that travels round as the day is lived. A
conventional analog clock would not work here: the band is a 24 hour dial, so a
12 hour hand would sit at one angle while the same hour on the band sat at
another.

The big figure is a single stretch, not the day: how long since you woke, or once
sleep is detected, how long you have been asleep. The ring is the record of the
intervals; the figure is the one you are in.

A sun or a moon says which stretch is being counted — no word to read, nothing to
translate.

**You never tell it anything.** Health Services reports the wearer's activity
state in the background, including sleep. The moment sleep is detected the face
switches to a moon and counts the night instead, in the scheme's other colour, and
the moment sleep ends the awake counter restarts from zero. There is no way to
enter a wake time by hand and nothing to correct: the times shown are observed,
never typed.

Tapping the face opens the settings, built from the current Wear OS components —
`SwitchButton` rows and an `EdgeButton` to close — and only about how it looks:

- **Colours** — one of four schemes, each a pair (awake, asleep)
- **Time** — on or off, and 12-hour or 24-hour
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

### Where the first wake-up comes from

Detection only fires on a *transition*, so a fresh install has nothing to count
until the wearer's next morning — a full day of a dead face. Three sources fill
that gap, best-informed first:

1. **Health Services state start.** Every report is stamped with the instant that
   state began, so the first report after installing already knows when the wearer
   stopped being asleep, hours earlier. Adopted as the wake-up.
2. **Health Connect.** The night the watch recorded, which also gives its start,
   so the ring can draw the night rather than inferring it. Best effort: absent,
   refused or empty all fall through.
3. **First run.** Count from now, marked provisional so any real observation
   replaces it.

Waking implies having been asleep, so adopting a wake-up also writes the sleep
before it, bounded to midnight. That inference is replaced the first night the
watch observes properly.

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
| `:app` | AndroidX / Jetpack watch face (`WatchFaceService`, custom `Canvas` renderer) | Wear OS 3–6 in practice — see below |
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
./gradlew :app:assembleRelease :watchface:assembleRelease
```

Build **release**, not debug, even for sideloading. Debug builds are unshrunk and
`debuggable`, which stops ART optimising them ahead of time — on a watch that is
38 MB and a 3.6 second cold start against 3.4 MB and 0.6 seconds. Both are signed
with the debug key so they still install directly. Use debug only when you need
`run-as` to inspect stored state.

Validate the Watch Face Format XML (catches errors the build does not):

```bash
curl -sL -o wff-validator.jar \
  https://github.com/google/watchface/releases/download/latest/wff-validator.jar
java -jar wff-validator.jar 2 watchface/src/main/res/raw/watchface.xml
```

## Install

```bash
adb install -r app/build/outputs/apk/release/app-release.apk   # the face, and everything with logic in it
adb install -r watchface/build/outputs/apk/release/watchface-release.apk   # optional, Watch Face Format
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

### A correction the hardware forced

Emulator images refuse to list a sideloaded AndroidX watch face — Wear OS 5 logs
`WFInfoResolver: Unsupported legacy watch face`, Wear OS 6 logs `Blocked watch
face` — which made the `:app` face look dead on arrival. It is not. On a real
Pixel Watch 2 it appears in the picker, runs, and is the face this was developed
against. Emulators are wrong about this; do not trust them on watch face
availability.

The Watch Face Format module is still the one to keep for the day the platform
does enforce it, and it is the only one testable on an emulator.

### Note on emulators

The Wear OS 6 (API 36) emulator image does not surface *any* sideloaded watch
face in its picker, and its `set-watchface` debug broadcast fails for every
sideloaded package — including Google's own unmodified `SimpleDigital` sample:

```
set-watchface failed. FavoriteOperationException: Watch face package is not installed.
```

On a Wear OS 5 (API 34) image the Watch Face Format face *is* offered — long-press
the current face → **Add new** → **Vigil** — but the AndroidX face is still absent
from that list — a limitation of the images, not of the face.

To exercise sleep detection on an emulator:

```bash
adb shell am broadcast -a "whs.USE_SYNTHETIC_PROVIDERS"        com.google.android.wearable.healthservices
adb shell am broadcast -a "whs.synthetic.user.START_SLEEPING"  com.google.android.wearable.healthservices
adb shell am broadcast -a "whs.synthetic.user.STOP_SLEEPING"   com.google.android.wearable.healthservices
```

A synthetic night is only seconds long, so the 3 hour guard will reject it and say
so in the log; backdate `asleep_since` in the app's shared preferences to see the
wake time actually get written.
