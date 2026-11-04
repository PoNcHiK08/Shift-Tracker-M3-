# Shift Tracker

Offline Android app for tracking work shifts and earnings across several jobs.
Built for part-time work in Poland: amounts in **PLN (zł)**, week starts on **Monday**,
UI in **Ukrainian** by default with **English** as an alternative.

100% native: Kotlin, Jetpack Compose, Material 3 — no WebViews, Flutter or React Native.

## Features

- **Jobs** — name, hourly rate (PLN) and a color from a palette, plus an optional **second rate for a time window**
  (e.g. 33 zł/h base and 37 zł/h from 23:00 to 06:00). Deleting a job asks first and shows how many shifts go with it.
- **Shifts** — job, date, start/end time (overnight shifts supported), unpaid break, note.
  The time dial gives a light haptic tick for each hour or minute your finger selects (at most one per frame) and a
  confirm click on OK; opening the dial or changing the time from code stays silent. The phone's touch-feedback /
  vibration settings apply.
  Minutes inside the second-rate window are paid at that rate, the rest at the base rate; the unpaid break is taken
  from both parts proportionally. Rates are *saved with the shift*, so changing a job's rates later never rewrites past
  earnings (the editor offers "use current rate" when they differ).
- **Bonuses** — one-off extra payments for a job on a given day (premium, tips…). They count towards earnings in the
  calendar and summary, but not towards hours or the average hourly rate.
- **Calendar** — swipeable month grid with colored dots per job. Tapping a day morphs its cell from a circle into an
  M3 Expressive squircle and shows that day in a Bento panel right under the grid (shift/bonus cards, day total,
  "Add shift" / "Bonus"), so you can tap through days without opening and closing a sheet. The month's earnings,
  hours and shifts are the first thing on screen: one compact connected row right under the month name
  (all three share one height).
  Under the finger every day morphs into the squircle too.
- **Summary** — week / month / year totals (hours, earnings, average rate) with a per-job breakdown,
  plus per-day bars for a week and per-month bars for a year. Paging slides the period label in the direction of travel.
- **Settings** — jobs & rates, theme (system/light/dark), dynamic color (Android 12+), language, JSON export/import.
  An import checks the whole file before touching anything (format versions 1–3; a file from a newer app version
  gets its own message; unique job IDs, amounts within what the editors accept, a break shorter than the shift, a
  second-rate window that isn't empty, bonuses above zero) and replaces the data in one transaction, so a bad or
  half-written file leaves the existing data exactly as it was.
- **Lists** look like Android 16: every group (a day's entries, by-job, by-day/month, settings sections, jobs) is a
  segmented list — items 2 dp apart, large outer and small inner corners; pressing an item rounds its corners.
  Single choices (week/month/year, unpaid break presets, theme, language) are M3 Expressive connected button groups;
  picking a job (shift/bonus editors, schedule import) uses toggle buttons that morph from a squircle into a pill.
- **Schedule import** — pick an `.ics` file, choose the job, preview the events (duplicates are detected and unchecked), confirm.
  Handles folded lines, `TZID` / UTC / floating times, `DURATION`, Outlook time-zone names, and skips all-day/cancelled events.
  A broken event (an impossible date or duration) is skipped and counted instead of failing the whole file.
  Time zones are matched by name only: IANA IDs, Windows/Outlook names, path-style IDs (`/mozilla.org/…/Europe/Warsaw`)
  and the `X-LIC-LOCATION` of a `VTIMEZONE` block. The rules inside a custom `VTIMEZONE` are *not* interpreted, so an
  ID that matches none of these is listed in the preview with a warning that its times were read as local time.

## Download the APK

Every push builds the app on GitHub Actions:

- **Permanent link to the newest build:**
  `https://github.com/PoNcHiK08/Shift-Tracker-M3-/releases/download/latest-build/ShiftTracker.apk`
- Or open **Actions → Build APK → latest run → Artifacts**.

All builds are signed with the same development key (`app/signing/dev.keystore`), so a new build installs
as an update over the old one and keeps your data. Android will ask to allow installs from your browser /
file manager the first time.

### Signing

| Release build has… | Result |
| --- | --- |
| `SIGNING_KEYSTORE_PATH`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` | Signed with that key (a missing variable stops the build and names it) |
| `ALLOW_DEV_RELEASE_SIGNING=true` (env) or `-PallowDevReleaseSigning=true` | Signed with the public dev key, with a warning banner in the build log |
| neither | The build stops before signing and explains both options |

Debug builds always use the dev key. The GitHub workflow opts in explicitly for its builds. The dev key's
password is in this repo, so anyone can sign an APK with it: fine for test builds, **not** for Google Play —
generate your own key and pass it through the `SIGNING_*` variables (e.g. from GitHub Secrets).

## Crash reports

Sideloaded APKs have no Play Console, so the app keeps its own: if it crashes (or closes itself while a
detail screen is open), the next launch shows the error with **Copy** / **Share** buttons. Release builds
are shrunk but not obfuscated, so the report is readable without a mapping file.

## Build locally

Requirements: JDK 17+ and the Android SDK (Android Studio installs both).

```bash
./gradlew testDebugUnitTest   # unit tests + Robolectric UI smoke test
ALLOW_DEV_RELEASE_SIGNING=true ./gradlew assembleRelease
                              # app/build/outputs/apk/release/app-release.apk, signed with the dev key
./gradlew installDebug        # install a debug build on a connected phone
```

## Tech stack

| Area | Choice |
| --- | --- |
| UI | Jetpack Compose, Material 3 Expressive (material3 1.5.0-beta02 on Compose BOM 2026.09), dynamic color, edge-to-edge |
| Motion | Springs (MotionScheme.expressive; screen transitions use them ~15 % quicker — same damping, stiffness × 1.38): bouncy spatial springs for position/size/shape, non-overshooting effects springs for colour/alpha; press feedback that squeezes and morphs shapes (segmented list items round their inner corners, day cells become squircles, button groups and icon buttons morph); circle -> squircle cell morph (graphics-shapes `Morph` of `MaterialShapes.Circle`/`Square`); odometer-style rolling digits for money and hours; container transform (`SharedTransitionLayout` + `sharedBounds`) from shift/bonus cards and "add" buttons into the full-screen editors, also driven by predictive back |
| Architecture | Single activity, MVVM + unidirectional data flow (ViewModel + StateFlow) |
| Navigation | Navigation Compose 2.10, type-safe `@Serializable` routes. Two levels: a root stack (home + detail screens) and a nested tab host. Material 3 transition patterns on M3 Expressive springs: shared axis X (30 dp) for forward/back, fade through for tabs, container transform for card -> editor, and M3 predictive back: while you drag, the closing screen shrinks to 90 %, rounds its corners, casts a shadow and drifts towards the swiping edge; when you let go it keeps going from that exact point (shrinks to 85 % and fades out within at most ~250 ms) instead of pausing first, and a cancelled swipe returns it to its place. An editor opened from a card is only held by the finger: it shrinks a little, rounds its corners and drifts, on a spring and up to a limit (rubber band), however far you drag, with the calendar visible behind it; it never morphs into the card under the finger. On release it flies into its card from where it was held (on a calm spring, ~5 % longer than a normal close) in plain view all the way: its content fades out early, the card's own look fades in on the shrinking container and lands exactly in the card's place. This happens on every back, however quick the flick (even one released before predictive back has started) and with the system back button; the editor's own close/save/delete buttons keep the normal container transform. Normal open/close keeps the container transform springs. Icon-only bottom bar built from M3 Expressive toggle buttons |
| Storage | Room 2.8 (jobs, shifts, bonuses; auto-migrations with exported schemas) + DataStore (settings); money stored in grosze (`Long`) |
| DI | Hilt 2.60 (KSP) |
| Build | AGP 9.5 (built-in Kotlin), Kotlin 2.4, Gradle 9.8, version catalog, R8 for release |
| Language | Per-app language via AppCompat (`values/` = Ukrainian, `values-en/` = English) |

## Known questions

- **Pay on the nights the clocks change.** A shift's length is wall-clock time. A 22:00–06:00 shift on the night
  the clocks go back (24→25 October 2026) really lasts 9 hours but is paid as 8; on the night they go forward
  (last Sunday of March) it lasts 7 and is paid as 8. Whether to pay real elapsed time is an open product decision,
  so the calculator deliberately stays as it is for now. (Schedule import already converts UTC and `TZID`
  times correctly across these nights; this is only about how a shift's hours are counted.)

## Project layout

```
app/src/main/java/com/vkurylo/shifttracker/
├── domain/        models, ShiftCalculator, MoneyParser, IcsParser, ImportPlanner, repository interfaces
├── data/          Room entities/DAOs/database, repositories, DataStore, JSON backup, SAF file access
├── di/            Hilt modules
└── ui/            theme, shared components, navigation, and one package per screen
                   (calendar, summary, settings, jobs, shift, importics)
app/src/test/      calculator (incl. second rate), money parser, .ics parser (incl. DST and time zones),
                   import planner, DB migration from v1, JSON backup (validation, versions, rollback),
                   time-dial haptics, predictive back frame by frame, and an end-to-end UI smoke test
```
