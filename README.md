# Shift Tracker

Offline Android app for tracking work shifts and earnings across several jobs.
Built for part-time work in Poland: amounts in **PLN (zł)**, week starts on **Monday**,
UI in **Ukrainian** by default with **English** as an alternative.

100% native: Kotlin, Jetpack Compose, Material 3 — no WebViews, Flutter or React Native.

## Features

- **Jobs** — name, hourly rate (PLN) and a color from a palette, plus an optional **second rate for a time window**
  (e.g. 33 zł/h base and 37 zł/h from 23:00 to 06:00). Deleting a job asks first and shows how many shifts go with it.
- **Shifts** — job, date, start/end time (overnight shifts supported), unpaid break, note.
  Minutes inside the second-rate window are paid at that rate, the rest at the base rate; the unpaid break is taken
  from both parts proportionally. Rates are *saved with the shift*, so changing a job's rates later never rewrites past
  earnings (the editor offers "use current rate" when they differ).
- **Bonuses** — one-off extra payments for a job on a given day (premium, tips…). They count towards earnings in the
  calendar and summary, but not towards hours or the average hourly rate.
- **Calendar** — swipeable month grid with colored dots per job. Tapping a day morphs its cell from a circle into an
  M3 Expressive squircle and shows that day in a Bento panel right under the grid (shift/bonus cards, day total,
  "Add shift" / "Bonus"), so you can tap through days without opening and closing a sheet. Below it, Bento tiles
  for the month's earnings, hours and shifts. Under the finger every day morphs into the squircle too.
- **Summary** — week / month / year totals (hours, earnings, average rate) with a per-job breakdown,
  plus per-day bars for a week and per-month bars for a year. Paging slides the period label in the direction of travel.
- **Settings** — jobs & rates, theme (system/light/dark), dynamic color (Android 12+), language, JSON export/import.
- **Lists** look like Android 16: every group (a day's entries, by-job, by-day/month, settings sections, jobs) is a
  segmented list — items 2 dp apart, large outer and small inner corners; pressing an item rounds its corners.
  Single choices (week/month/year, unpaid break presets, theme, language) are M3 Expressive connected button groups.
- **Schedule import** — pick an `.ics` file, choose the job, preview the events (duplicates are detected and unchecked), confirm.
  Handles folded lines, `TZID` / UTC / floating times, `DURATION`, Outlook time-zone names, and skips all-day/cancelled events.

## Download the APK

Every push builds the app on GitHub Actions:

- **Permanent link to the newest build:**
  `https://github.com/PoNcHiK08/Shift-Tracker-M3-/releases/download/latest-build/ShiftTracker.apk`
- Or open **Actions → Build APK → latest run → Artifacts**.

All builds are signed with the same development key (`app/signing/dev.keystore`), so a new build installs
as an update over the old one and keeps your data. Android will ask to allow installs from your browser /
file manager the first time.

> Before publishing to Google Play, generate your own key and pass it through the
> `SIGNING_KEYSTORE_PATH`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS` and `SIGNING_KEY_PASSWORD`
> environment variables (e.g. from GitHub Secrets). The dev key in this repo is public and only meant for testing.

## Crash reports

Sideloaded APKs have no Play Console, so the app keeps its own: if it crashes (or closes itself while a
detail screen is open), the next launch shows the error with **Copy** / **Share** buttons. Release builds
are shrunk but not obfuscated, so the report is readable without a mapping file.

## Build locally

Requirements: JDK 17+ and the Android SDK (Android Studio installs both).

```bash
./gradlew testDebugUnitTest   # unit tests + Robolectric UI smoke test
./gradlew assembleRelease     # app/build/outputs/apk/release/app-release.apk
./gradlew installDebug        # install a debug build on a connected phone
```

## Tech stack

| Area | Choice |
| --- | --- |
| UI | Jetpack Compose, Material 3 Expressive (material3 1.5.0-beta02 on Compose BOM 2026.09), dynamic color, edge-to-edge |
| Motion | Springs (MotionScheme.expressive): bouncy spatial springs for position/size/shape, non-overshooting effects springs for colour/alpha; press feedback that squeezes and morphs shapes (segmented list items round their inner corners, day cells become squircles, button groups and icon buttons morph); circle -> squircle cell morph (graphics-shapes `Morph` of `MaterialShapes.Circle`/`Square`); odometer-style rolling digits for money and hours; container transform (`SharedTransitionLayout` + `sharedBounds`) from shift/bonus cards and "add" buttons into the full-screen editors, also driven by predictive back |
| Architecture | Single activity, MVVM + unidirectional data flow (ViewModel + StateFlow) |
| Navigation | Navigation Compose 2.10, type-safe `@Serializable` routes. Two levels: a root stack (home + detail screens) and a nested tab host. Material 3 transition patterns on M3 Expressive springs: shared axis X (30 dp) for forward/back, fade through for tabs, container transform for card -> editor, and M3 predictive back (the closing screen shrinks to 90 %, rounds its corners, casts a shadow and drifts with the finger). Icon-only bottom bar built from M3 Expressive toggle buttons |
| Storage | Room 2.8 (jobs, shifts, bonuses; auto-migrations with exported schemas) + DataStore (settings); money stored in grosze (`Long`) |
| DI | Hilt 2.60 (KSP) |
| Build | AGP 9.5 (built-in Kotlin), Kotlin 2.4, Gradle 9.8, version catalog, R8 for release |
| Language | Per-app language via AppCompat (`values/` = Ukrainian, `values-en/` = English) |

## Project layout

```
app/src/main/java/com/vkurylo/shifttracker/
├── domain/        models, ShiftCalculator, MoneyParser, IcsParser, ImportPlanner, repository interfaces
├── data/          Room entities/DAOs/database, repositories, DataStore, JSON backup, SAF file access
├── di/            Hilt modules
└── ui/            theme, shared components, navigation, and one package per screen
                   (calendar, summary, settings, jobs, shift, importics)
app/src/test/      calculator (incl. second rate), money parser, .ics parser, import planner,
                   DB migration from v1, JSON backup round trip, and an end-to-end UI smoke test
```
